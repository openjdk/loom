/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.nio.ch;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Selector implementation based on WASPoll.
 *
 * KB4550945 needs to be installed, otherwise a socket registered to poll for
 * a connect to complete will not be polled when the connection cannot be
 * established.
 */

class WSAPollSelectorImpl extends SelectorImpl {
    // initial capacity of poll array
    private static final int INITIAL_CAPACITY = 16;

    // poll array, grows as needed
    private long pollArrayAddress;
    private int pollArrayCapacity;  // allocated
    private int pollArraySize;      // in use

    // keys for file descriptors in poll array, synchronize on selector
    private final List<SelectionKeyImpl> pollKeys = new ArrayList<>();

    // pending updates, queued by putEventOps
    private final Object updateLock = new Object();
    private final Deque<SelectionKeyImpl> updateKeys = new ArrayDeque<>();

    // interrupt/wakeup
    private final Object interruptLock = new Object();
    private boolean interruptTriggered;
    private final Pipe pipe;
    private final FileDescriptor fd0, fd1;
    private final int fd0Val, fd1Val;

    WSAPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);

        this.pollArrayAddress = WSAPoll.allocatePollArray(INITIAL_CAPACITY);
        this.pollArrayCapacity = INITIAL_CAPACITY;

        // wakeup support
        this.pipe = new PipeImpl(null, /*no delay*/ false);
        SourceChannelImpl source = (SourceChannelImpl) pipe.source();
        SinkChannelImpl sink = (SinkChannelImpl) pipe.sink();
        this.fd0 = source.getFD();
        this.fd1 = sink.getFD();
        this.fd0Val = source.getFDVal();
        this.fd1Val = sink.getFDVal();

        // element 0 in poll array is for wakeup.
        synchronized (this) {
            putDescriptor(0, fd0Val);
            putEvents(0, Net.POLLIN);
            putRevents(0, (short) 0);
            pollArraySize = 1;
            pollKeys.add(null);  // dummy element
        }
    }

    private void ensureOpen() {
        if (!isOpen())
            throw new ClosedSelectorException();
    }

    @Override
    protected int doSelect(Consumer<SelectionKey> action, long timeout)
        throws IOException
    {
        assert Thread.holdsLock(this);

        int to = (int) Math.min(timeout, Integer.MAX_VALUE); // max poll timeout
        boolean blocking = (to != 0);

        processUpdateQueue();
        processDeregisterQueue();
        try {
            begin(blocking);
            int numPolled;
            if (blocking && Thread.currentThread().isVirtual()) {
                numPolled = managedPoll(to);
            } else {
                numPolled = implPoll(to);
            }
            assert numPolled <= pollArraySize;
        } finally {
            end(blocking);
        }

        processDeregisterQueue();
        return processEvents(action);
    }

    @Override
    protected int implPoll(long timeout) throws IOException {
        return WSAPoll.poll(pollArrayAddress, pollArraySize, (int) timeout);
    }

    /**
     * Process changes to the interest ops.
     */
    private void processUpdateQueue() {
        assert Thread.holdsLock(this);

        synchronized (updateLock) {
            SelectionKeyImpl ski;
            while ((ski = updateKeys.pollFirst()) != null) {
                int newEvents = ski.translateInterestOps();
                if (ski.isValid()) {
                    int index = ski.getIndex();
                    assert index >= 0 && index < pollArraySize;
                    if (index > 0) {
                        assert pollKeys.get(index) == ski;
                        if (newEvents == 0) {
                            remove(ski);
                        } else {
                            update(ski, newEvents);
                        }
                    } else if (newEvents != 0) {
                        add(ski, newEvents);
                    }
                }
            }
        }
    }

    /**
     * Process the polled events.
     * If the interrupt fd has been selected, drain it and clear the interrupt.
     */
    private int processEvents(Consumer<SelectionKey> action)
        throws IOException
    {
        assert Thread.holdsLock(this);
        assert pollArraySize > 0 && pollArraySize == pollKeys.size();

        int numKeysUpdated = 0;
        for (int i = 1; i < pollArraySize; i++) {
            int rOps = getRevents(i);
            if (rOps != 0) {
                SelectionKeyImpl ski = pollKeys.get(i);
                assert ski.getFDVal() == getDescriptor(i);
                if (ski.isValid()) {
                    if ((rOps & Net.POLLRDBAND) != 0) {
                        Net.discardUrgentData(ski.getFD());
                    }
                    numKeysUpdated += processReadyEvents(rOps, ski, action);
                }
            }
        }

        // check for interrupt
        if (getRevents(0) != 0) {
            clearInterrupt();
        }

        return numKeysUpdated;
    }

    @Override
    protected void implClose() throws IOException {
        assert !isOpen() && Thread.holdsLock(this);

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        WSAPoll.freePollArray(pollArrayAddress);
        pipe.sink().close();
        pipe.source().close();
    }

    @Override
    protected void implRegister(SelectionKeyImpl ski) {
        assert ski.getIndex() == 0;
        ensureOpen();
    }

    @Override
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert !ski.isValid() && Thread.holdsLock(this);

        // remove from poll array
        int index = ski.getIndex();
        if (index > 0) {
            remove(ski);
        }
    }

    @Override
    public void setEventOps(SelectionKeyImpl ski) {
        ensureOpen();
        synchronized (updateLock) {
            updateKeys.addLast(ski);
        }
    }

    @Override
    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                try {
                    IOUtil.write1(fd1Val, (byte) 0);
                } catch (IOException ioe) {
                    throw new InternalError(ioe);
                }
                interruptTriggered = true;
            }
        }
        return this;
    }

    private void clearInterrupt() throws IOException {
        synchronized (interruptLock) {
            IOUtil.drain(fd0Val);
            interruptTriggered = false;
        }
    }

    /**
     * Adds a pollfd entry to the poll array, expanding the poll array if needed.
     */
    private void add(SelectionKeyImpl ski, int ops) {
        expandIfNeeded();

        int index = pollArraySize;
        assert index > 0;
        putDescriptor(index, ski.getFDVal());
        putEvents(index, (short) ops);
        putRevents(index, (short) 0);
        ski.setIndex(index);
        pollArraySize++;

        pollKeys.add(ski);
        assert pollKeys.size() == pollArraySize;
    }

    /**
     * Update the events of pollfd entry.
     */
    private void update(SelectionKeyImpl ski, int ops) {
        int index = ski.getIndex();
        assert index > 0 && index < pollArraySize;
        assert getDescriptor(index) == ski.getFDVal();
        putEvents(index, (short) ops);
    }

    /**
     * Removes a pollfd entry from the poll array.
     */
    private void remove(SelectionKeyImpl ski) {
        int index = ski.getIndex();
        assert index > 0 && index < pollArraySize;
        assert getDescriptor(index) == ski.getFDVal();

        // replace pollfd at index with the last pollfd in array
        int lastIndex = pollArraySize - 1;
        if (lastIndex != index) {
            SelectionKeyImpl lastKey = pollKeys.get(lastIndex);
            assert lastKey.getIndex() == lastIndex;
            int lastFd = getDescriptor(lastIndex);
            short lastOps = getEvents(lastIndex);
            short lastRevents = getRevents(lastIndex);
            assert lastKey.getFDVal() == lastFd;
            putDescriptor(index, lastFd);
            putEvents(index, lastOps);
            putRevents(index, lastRevents);
            pollKeys.set(index, lastKey);
            lastKey.setIndex(index);
        }
        pollKeys.remove(lastIndex);
        pollArraySize--;
        assert pollKeys.size() == pollArraySize;

        ski.setIndex(0);
    }

    /**
     * Expand poll array if at capacity.
     */
    private void expandIfNeeded() {
        if (pollArraySize == pollArrayCapacity) {
            int newCapacity = pollArrayCapacity + INITIAL_CAPACITY;
            pollArrayAddress = WSAPoll.reallocatePollArray(pollArrayAddress, pollArrayCapacity, newCapacity);
            pollArrayCapacity = newCapacity;
        }
    }

    private void putDescriptor(int i, int fd) {
        WSAPoll.putDescriptor(pollArrayAddress, i, fd);
    }

    private int getDescriptor(int i) {
        return WSAPoll.getDescriptor(pollArrayAddress, i);
    }

    private void putEvents(int i, short events) {
        WSAPoll.putEvents(pollArrayAddress, i, events);
    }

    private short getEvents(int i) {
       return WSAPoll.getEvents(pollArrayAddress, i);
    }

    private void putRevents(int i, short revents) {
        WSAPoll.putRevents(pollArrayAddress, i, revents);
    }

    private short getRevents(int i) {
        return WSAPoll.getRevents(pollArrayAddress, i);
    }
}

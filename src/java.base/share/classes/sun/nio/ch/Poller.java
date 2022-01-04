/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOError;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.VirtualThreads;
import sun.security.action.GetPropertyAction;

/**
 * A Poller of file descriptors. A virtual thread registers the file descriptor
 * for a socket with a Poller before parking. The poller unparks the thread when
 * the socket is ready for I/O.
 */
public abstract class Poller {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Poller[] READ_POLLERS;
    private static final Poller[] WRITE_POLLERS;
    private static final int READ_MASK, WRITE_MASK;

    static {
        PollerProvider provider = PollerProvider.provider();
        try {
            Poller[] readPollers = createReadPollers(provider);
            READ_POLLERS = readPollers;
            READ_MASK = readPollers.length - 1;
            Poller[] writePollers = createWritePollers(provider);
            WRITE_POLLERS = writePollers;
            WRITE_MASK = writePollers.length - 1;
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }
    }

    /**
     * Create the read poller(s).
     */
    private static Poller[] createReadPollers(PollerProvider provider) throws IOException {
        int readPollerCount = pollerCount("jdk.readPollers");
        Poller[] readPollers = new Poller[readPollerCount];
        for (int i = 0; i< readPollerCount; i++) {
            var poller = provider.readPoller();
            poller.startPollerThread("Read-Poller-" + i);
            readPollers[i] = poller;
        }
        return readPollers;
    }

    /**
     * Create the write poller(s).
     */
    private static Poller[] createWritePollers(PollerProvider provider) throws IOException {
        int writePollerCount = pollerCount("jdk.writePollers");
        Poller[] writePollers = new Poller[writePollerCount];
        for (int i = 0; i< writePollerCount; i++) {
            var poller = provider.writePoller();
            poller.startPollerThread("Write-Poller-" + i);
            writePollers[i] = poller;
        }
        return writePollers;
    }

    /**
     * Reads the given property name to get the poller count. If the property is
     * set then the value must be a power of 2. Returns 1 if the property is not
     * set.
     * @throws IllegalArgumentException if the property is set to a value that
     * is not a power of 2.
     */
    private static int pollerCount(String propName) {
        String s = GetPropertyAction.privilegedGetProperty(propName, "1");
        int count = Integer.parseInt(s);

        // check power of 2
        if (count != (1 << log2(count))) {
            String msg = propName + " is set to a vale that is not a power of 2";
            throw new IllegalArgumentException(msg);
        }
        return count;
    }

    private static int log2(int n) {
        return 31 - Integer.numberOfLeadingZeros(n);
    }

    /**
     * Start a platform thread with the given name to poll file descriptors
     * registered with this poller.
     */
    private void startPollerThread(String name) {
        Runnable task = () -> {
            try {
                for (;;) {
                    poll();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        try {
            Thread thread = JLA.executeOnCarrierThread(() ->
                InnocuousThread.newSystemThread(name, task)
            );
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    /**
     * Registers the current thread to be unparked when a file descriptor is
     * ready for I/O.
     *
     * @throws IOException if the register fails
     * @throws IllegalArgumentException if the event is not POLLIN or POLLOUT
     * @throws IllegalStateException if another thread is already registered
     *         to be unparked when the file descriptor is ready for this event
     */
    static void register(int fdVal, int event) throws IOException {
        if (event == Net.POLLIN) {
            readPoller(fdVal).register(fdVal);
        } else if (event == Net.POLLOUT) {
            writePoller(fdVal).register(fdVal);
        } else {
            throw new IllegalArgumentException("Unknown event " + event);
        }
    }

    /**
     * Maps the file descriptor value to a read poller.
     */
    private static Poller readPoller(int fdVal) {
        return READ_POLLERS[fdVal & READ_MASK];
    }

    /**
     * Maps the file descriptor value to a write poller.
     */
    private static Poller writePoller(int fdVal) {
        return WRITE_POLLERS[fdVal & WRITE_MASK];
    }

    /**
     * Deregister the current thread so it will not be unparked when a file descriptor
     * is ready for I/O.
     *
     * @throws IllegalArgumentException if the event is not POLLIN or POLLOUT
     */
    static void deregister(int fdVal, int event) {
        if (event == Net.POLLIN) {
            readPoller(fdVal).deregister(fdVal);
        } else if (event == Net.POLLOUT) {
            writePoller(fdVal).deregister(fdVal);
        } else {
            throw new IllegalArgumentException("Unknown event " + event);
        }
    }

    /**
     * Stops polling the file descriptor for the given event and unpark any
     * strand registered to be unparked when the file descriptor is ready for I/O.
     */
    static void stopPoll(int fdVal, int event) {
        if (event == Net.POLLIN) {
            readPoller(fdVal).wakeup(fdVal);
        } else if (event == Net.POLLOUT) {
            writePoller(fdVal).wakeup(fdVal);
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Stops polling the file descriptor and unpark any threads that are registered
     * to be unparked when the file descriptor is ready for I/O.
     */
    static void stopPoll(int fdVal) {
        stopPoll(fdVal, Net.POLLIN);
        stopPoll(fdVal, Net.POLLOUT);
    }

    private final Map<Integer, Thread> map = new ConcurrentHashMap<>();

    protected Poller() { }

    private void register(int fdVal) throws IOException {
        assert Thread.currentThread().isVirtual();
        Thread previous = map.putIfAbsent(fdVal, Thread.currentThread());
        assert previous == null;
        implRegister(fdVal);
    }

    private void deregister(int fdVal) {
        Thread previous = map.remove(fdVal);
        assert previous == null || previous == Thread.currentThread();
    }

    private Stream<Thread> registeredThreads() {
        return map.values().stream();
    }

    private void wakeup(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            implDeregister(fdVal);
            VirtualThreads.unpark(t);
        }
    }

    /**
     * Called by the polling facility when the file descriptor is polled
     */
    final void polled(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            VirtualThreads.unpark(t);
        }
    }

    /**
     * Poll for events. The {@link #polled(int)} method is invoked for each
     * polled file descriptor.
     *
     * @param timeout if positive then block for up to {@code timeout} milliseconds,
     *     if zero then don't block, if -1 then block indefinitely
     */
    abstract int poll(int timeout) throws IOException;

    /**
     * Poll for events, blocks indefinitely.
     */
    final int poll() throws IOException {
        return poll(-1);
    }

    /**
     * Poll for events, non-blocking.
     */
    final int pollNow() throws IOException {
        return poll(0);
    }

    /**
     * Returns the poller's file descriptor, or -1 if none.
     */
    int fdVal() {
        return -1;
    }

    /**
     * Register the file descriptor
     */
    abstract void implRegister(int fdVal) throws IOException;

    /**
     * Deregister (or disarm) the file descriptor
     */
    abstract void implDeregister(int fdVal);

    /**
     * Return a stream of all threads blocked waiting for I/O operations.
     */
    public static Stream<Thread> blockedThreads() {
        Stream<Thread> s = Stream.empty();
        for (int i = 0; i < READ_POLLERS.length; i++) {
            s = Stream.concat(s, READ_POLLERS[i].registeredThreads());
        }
        for (int i = 0; i < WRITE_POLLERS.length; i++) {
            s = Stream.concat(s, WRITE_POLLERS[i].registeredThreads());
        }
        return s;
    }
}

/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates. All rights reserved.
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

/**
 * A Poller of file descriptors. A virtual thread registers a file descriptors with
 * a Poller. The thread is unparked when the file descriptor is ready for I/O.
 */
public abstract class Poller {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Poller READ_POLLER;
    private static final Poller WRITE_POLLER;
    static {
        PollerProvider provider = PollerProvider.provider();
        try {
            READ_POLLER = provider.readPoller();
            WRITE_POLLER = provider.writePoller();
            READ_POLLER.startPollerThread("Read-Poller");
            WRITE_POLLER.startPollerThread("Write-Poller");
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }
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
            READ_POLLER.register(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.register(fdVal);
        } else {
            throw new IllegalArgumentException("Unknown event " + event);
        }
    }

    /**
     * Deregister the current thread so it will not be unparked when a file descriptor
     * is ready for I/O.
     *
     * @throws IllegalArgumentException if the event is not POLLIN or POLLOUT
     */
    static void deregister(int fdVal, int event) {
        if (event == Net.POLLIN) {
            READ_POLLER.deregister(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.deregister(fdVal);
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
            READ_POLLER.wakeup(fdVal);
        } else if (event == Net.POLLOUT) {
            WRITE_POLLER.wakeup(fdVal);
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
    final void polled(int fdVal, boolean tryPush) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            VirtualThreads.unpark(t, tryPush);
        }
    }

    final void polled(int fdVal) {
        polled(fdVal, false);
    }

    /**
     * Poll for events. The {@link #polled(int, boolean)} method is invoked for each
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
        return Stream.concat(READ_POLLER.registeredThreads(),
                WRITE_POLLER.registeredThreads());
    }
}

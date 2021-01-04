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
import java.util.concurrent.locks.LockSupport;

import jdk.internal.misc.InnocuousThread;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

abstract class Poller implements Runnable {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Poller READ_POLLER;
    private static final Poller WRITE_POLLER;

    static {
        try {
            PollerProvider provider = PollerProvider.provider();
            READ_POLLER = startPollerThread("Read-Poller", provider.readPoller());
            WRITE_POLLER = startPollerThread("Write-Poller", provider.writePoller());
        } catch (IOException ioe) {
            throw new IOError(ioe);
        }
    }

    private static Poller startPollerThread(String name, Poller poller) {
        try {
            Thread t = JLA.executeOnCarrierThread(() ->
                    InnocuousThread.newSystemThread(name, poller));
            t.setDaemon(true);
            t.start();
            return poller;
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
     * Stops polling the file descriptor and unpark any strands that are registered
     * to be unparked when the file descriptor is ready for I/O.
     */
    static void stopPoll(int fdVal) {
        stopPoll(fdVal, Net.POLLIN);
        stopPoll(fdVal, Net.POLLOUT);
    }

    private final Map<Integer, Thread> map = new ConcurrentHashMap<>();

    protected Poller() { }

    private void register(int fdVal) throws IOException {
        Thread previous = map.putIfAbsent(fdVal, Thread.currentThread());
        assert previous == null;
        implRegister(fdVal);
    }

    private void deregister(int fdVal) {
        Thread previous = map.remove(fdVal);
        assert previous == null || previous == Thread.currentThread();
    }

    private void wakeup(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            implDeregister(fdVal);
            LockSupport.unpark(t);
        }
    }

    /**
     * Called by the polling facility when the file descriptor is polled
     */
    final protected void polled(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * Register the file descriptor
     */
    abstract protected void implRegister(int fdVal) throws IOException;

    /**
     * Deregister (or disarm) the file descriptor
     */
    abstract protected void implDeregister(int fdVal);
}

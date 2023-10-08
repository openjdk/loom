/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import sun.security.action.GetPropertyAction;

/**
 * Polls file descriptors. Virtual threads invoke the poll method to park
 * until a given file descriptor is ready for I/O.
 */
public abstract class Poller {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Poller MASTER_POLLER;
    private static final Poller[] READ_POLLERS;
    private static final Poller[] WRITE_POLLERS;
    private static final int READ_MASK, WRITE_MASK;

    // true if this is a poller for reading, false for writing
    private final boolean read;

    // maps file descriptors to parked Thread
    private final Map<Integer, Thread> map = new ConcurrentHashMap<>();

    /**
     * Poller mode.
     */
    public enum Mode {
        /**
         * ReadPoller and WritePoller are dedicated platform threads that block waiting
         * for events and unpark virtual threads when file descriptors are ready for I/O.
         */
        SYSTEM_THREADS,

        /**
         * ReadPoller and WritePoller threads are virtual threads that poll for events,
         * yielding between polls and unparking virtual threads when file descriptors are
         * ready for I/O. If there are no events then the poller threads park until there
         * are I/O events to poll. This mode helps to integrate polling with virtual
         * thread scheduling. The approach is similar to the default scheme in "User-level
         * Threading: Have Your Cake and Eat It Too" by Karsten and Barghi 2020
         * (https://dl.acm.org/doi/10.1145/3379483).
         */
        VTHREAD_POLLERS
    }

    /**
     * Initialize a Poller for reading or writing.
     * @param read true if this poller is for read events, false for write events
     */
    protected Poller(boolean read) {
        this.read = read;
    }

    /**
     * Parks the current thread until a file descriptor is ready for the given op.
     * @param fdVal the file descriptor
     * @param event POLLIN or POLLOUT
     * @param nanos the waiting time or 0 to wait indefinitely
     * @param supplier supplies a boolean to indicate if the enclosing object is open
     */
    public static void poll(int fdVal, int event, long nanos, BooleanSupplier supplier)
        throws IOException
    {
        assert nanos >= 0L;
        if (event == Net.POLLIN) {
            readPoller(fdVal).poll(fdVal, nanos, supplier);
        } else if (event == Net.POLLOUT) {
            writePoller(fdVal).poll(fdVal, nanos, supplier);
        } else {
            assert false;
        }
    }

    /**
     * Parks the current thread until a file descriptor is ready.
     */
    private void poll(int fdVal, long nanos, BooleanSupplier supplier) throws IOException {
        register(fdVal);
        try {
            boolean isOpen = supplier.getAsBoolean();
            if (isOpen) {
                if (nanos > 0) {
                    LockSupport.parkNanos(nanos);
                } else {
                    LockSupport.park();
                }
            }
        } finally {
            deregister(fdVal);
        }
    }

    /**
     * Registers the file descriptor.
     */
    private void register(int fdVal) throws IOException {
        Thread previous = map.putIfAbsent(fdVal, Thread.currentThread());
        assert previous == null;
        implRegister(fdVal);
    }

    /**
     * Deregister the file descriptor, a no-op if already polled.
     */
    private void deregister(int fdVal) {
        Thread previous = map.remove(fdVal);
        assert previous == null || previous == Thread.currentThread();
        if (previous != null) {
            implDeregister(fdVal);
        }
    }

    /**
     * Returns the poller file descriptor, for use with hierarchical polling.
     */
    abstract int fdVal();

    /**
     * Register the file descriptor.
     */
    abstract void implRegister(int fdVal) throws IOException;

    /**
     * Deregister the file descriptor.
     */
    abstract void implDeregister(int fdVal);

    /**
     * Starts a poller thread.
     */
    private void start() {
        String prefix = (read) ? "Read" : "Write";
        if (MASTER_POLLER == null) {
            startPlatformThread(prefix + "-Poller", this::pollerLoop);
        } else {
            startVirtualThread(prefix + "-Poller", this::subPollerLoop);
        }
    }

    /**
     * Starts a platform thread to run the given task.
     */
    private void startPlatformThread(String name, Runnable task) {
        try {
            Thread thread = JLA.executeOnCarrierThread(() ->
                InnocuousThread.newSystemThread(name, task)
            );
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
            thread.start();
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    /**
     * Starts a virtual thread to run the given task.
     */
    private void startVirtualThread(String name, Runnable task) {
        Thread.ofVirtual()
                .name(name)
                .inheritInheritableThreadLocals(false)
                .uncaughtExceptionHandler((t, e) -> e.printStackTrace())
                .start(task);
    }

    /**
     * Master polling loop. The {@link #polled(int)} method is invoked for each file
     * descriptor that is polled.
     */
    private void pollerLoop() {
        try {
            for (;;) {
                poll();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sub-poller polling loop. The {@link #polled(int)} method is invoked for each file
     * descriptor that is polled.
     */
    private void subPollerLoop() {
        assert Thread.currentThread().isVirtual();
        try {
            int polled = 0;
            for (;;) {
                if (polled == 0) {
                    MASTER_POLLER.poll(fdVal(),0, () -> true);
                }
                for (int i = 0; i < 3; i++) {
                    polled = poll(0);
                    Thread.yield();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
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
     * Unparks any thread that is polling the given file descriptor for the
     * given event.
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
     * Unparks any threads that are polling the given file descriptor.
     */
    static void stopPoll(int fdVal) {
        stopPoll(fdVal, Net.POLLIN);
        stopPoll(fdVal, Net.POLLOUT);
    }

    /**
     * Unparks any thread that is polling the given file descriptor.
     */
    private void wakeup(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * Called by the polling facility when the file descriptor is polled
     */
    final void polled(int fdVal) {
        wakeup(fdVal);
    }

    /**
     * Poll for events. The {@link #polled(int)} method is invoked for each
     * polled file descriptor.
     *
     * @param timeout if positive then block for up to {@code timeout} milliseconds,
     *     if zero then don't block, if -1 then block indefinitely
     * @return the number of file descriptors polled
     */
    abstract int poll(int timeout) throws IOException;

    /**
     * Poll for events, blocks indefinitely.
     * @return the number of file descriptors polled
     */
    final int poll() throws IOException {
        return poll(-1);
    }

    /**
     * Creates the read and writer pollers.
     */
    static {
        PollerProvider provider = PollerProvider.provider();
        Poller.Mode mode;
        String s = GetPropertyAction.privilegedGetProperty("jdk.pollerMode");
        if (s != null) {
            if (s.equalsIgnoreCase(Mode.SYSTEM_THREADS.name()) || s.equals("1")) {
                mode = Mode.SYSTEM_THREADS;
            } else if (s.equalsIgnoreCase(Mode.VTHREAD_POLLERS.name()) || s.equals("2")) {
                mode = Mode.VTHREAD_POLLERS;
            } else {
                throw new IllegalArgumentException("Can't parse '" + s + "' as polling mode");
            }
        } else {
            mode = provider.defaultPollerMode();
        }

        try {
            // master poller
            MASTER_POLLER = switch (mode) {
                case SYSTEM_THREADS -> null;
                case VTHREAD_POLLERS -> {
                    var poller = provider.readPoller();
                    poller.startPlatformThread("NetPoller", poller::pollerLoop);
                    yield poller;
                }
            };

            // read pollers
            Poller[] readPollers = createReadPollers(mode, provider);
            READ_POLLERS = readPollers;
            READ_MASK = readPollers.length - 1;
            Arrays.stream(readPollers).forEach(p -> p.start());

            // write pollers
            Poller[] writePollers = createWritePollers(mode, provider);
            WRITE_POLLERS = writePollers;
            WRITE_MASK = writePollers.length - 1;
            Arrays.stream(writePollers).forEach(p -> p.start());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Create the read poller(s).
     */
    private static Poller[] createReadPollers(Poller.Mode mode, PollerProvider provider)
        throws IOException
    {
        int readPollerCount = pollerCount("jdk.readPollers", provider.defaultReadPollers(mode));
        Poller[] readPollers = new Poller[readPollerCount];
        for (int i = 0; i < readPollerCount; i++) {
            readPollers[i] = provider.readPoller();
        }
        return readPollers;
    }

    /**
     * Create the write poller(s).
     */
    private static Poller[] createWritePollers(Poller.Mode mode, PollerProvider provider)
        throws IOException
    {
        int writePollerCount = pollerCount("jdk.writePollers", provider.defaultWritePollers(mode));
        Poller[] writePollers = new Poller[writePollerCount];
        for (int i = 0; i < writePollerCount; i++) {
            writePollers[i] = provider.writePoller();
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
    private static int pollerCount(String propName, int defaultCount) {
        String s = GetPropertyAction.privilegedGetProperty(propName);
        int count = (s != null) ? Integer.parseInt(s) : defaultCount;

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
}

/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates. All rights reserved.
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
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.vm.annotation.Stable;

/**
 * Polls file descriptors. Virtual threads invoke the poll method to park
 * until a given file descriptor is ready for I/O.
 */
public abstract class Poller {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private static final PollerProvider PROVIDER = PollerProvider.provider();

    private static final Mode POLLER_MODE = pollerMode();

    private static final Executor DEFAULT_SCHEDULER = JLA.defaultVirtualThreadScheduler();

    // poller group for default scheduler
    private static final Supplier<PollerGroup> DEFAULT_POLLER_GROUP = StableValue.supplier(PollerGroup::create);

    // maps scheduler to PollerGroup, custom schedulers can't be GC'ed at this time
    private static final Map<Executor, PollerGroup> POLLER_GROUPS = new ConcurrentHashMap<>();

    // the poller or sub-poller thread
    private @Stable Thread owner;

    // maps file descriptors to parked Thread
    private final Map<Integer, Thread> map = new ConcurrentHashMap<>();

    /**
     * Poller mode.
     */
    enum Mode {
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
     * Initialize a Poller.
     */
    protected Poller() {
    }

    /**
     * Closes the poller and release resources. This method can only be used to cleanup
     * when creating a poller group fails.
     */
    abstract void close();

    /**
     * Returns the poller's file descriptor, used when the read and write poller threads
     * are virtual threads.
     *
     * @throws UnsupportedOperationException if not supported
     */
    int fdVal() {
        throw new UnsupportedOperationException();
    }

    /**
     * Register the file descriptor. The registration is "one shot", meaning it should
     * be polled at most once.
     */
    abstract void implRegister(int fdVal) throws IOException;

    /**
     * Deregister the file descriptor.
     * @param polled true if the file descriptor has already been polled
     */
    abstract void implDeregister(int fdVal, boolean polled);

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
     * Callback by the poll method when a file descriptor is polled.
     */
    final void polled(int fdVal) {
        wakeup(fdVal);
    }

    /**
     * Parks the current thread until a file descriptor is ready for the given op.
     * @param fdVal the file descriptor
     * @param event POLLIN or POLLOUT
     * @param nanos the waiting time or 0 to wait indefinitely
     * @param supplier supplies a boolean to indicate if the enclosing object is open
     */
    static void poll(int fdVal, int event, long nanos, BooleanSupplier supplier)
        throws IOException
    {
        assert nanos >= 0L;
        PollerGroup pollerGroup = pollerGroup(Thread.currentThread());
        if (event == Net.POLLIN) {
            pollerGroup.readPoller(fdVal).poll(fdVal, nanos, supplier);
        } else if (event == Net.POLLOUT) {
            pollerGroup.writePoller(fdVal).poll(fdVal, nanos, supplier);
        } else {
            assert false;
        }
    }

    /**
     * Parks the current thread until a Selector's file descriptor is ready.
     * @param fdVal the Selector's file descriptor
     * @param nanos the waiting time or 0 to wait indefinitely
     */
    static void pollSelector(int fdVal, long nanos) throws IOException {
        assert nanos >= 0L;
        PollerGroup pollerGroup = pollerGroup(Thread.currentThread());
        Poller poller = pollerGroup.masterPoller();
        if (poller == null) {
            poller = pollerGroup.readPoller(fdVal);
        }
        poller.poll(fdVal, nanos, () -> true);
    }

    /**
     * Unpark the given thread so that it stops polling.
     */
    static void stopPoll(Thread thread) {
        LockSupport.unpark(thread);
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
     * Registers the file descriptor to be polled at most once when the file descriptor
     * is ready for I/O.
     */
    private void register(int fdVal) throws IOException {
        Thread previous = map.put(fdVal, Thread.currentThread());
        assert previous == null;
        try {
            implRegister(fdVal);
        } catch (Throwable t) {
            map.remove(fdVal);
            throw t;
        }
    }

    /**
     * Deregister the file descriptor so that the file descriptor is not polled.
     */
    private void deregister(int fdVal) {
        Thread previous = map.remove(fdVal);
        boolean polled = (previous == null);
        assert polled || previous == Thread.currentThread();
        implDeregister(fdVal, polled);
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
     * Master polling loop. The {@link #polled(int)} method is invoked for each file
     * descriptor that is polled.
     */
    private void pollerLoop() {
        owner = Thread.currentThread();
        try {
            for (;;) {
                poll(-1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sub-poller polling loop. The {@link #polled(int)} method is invoked for each file
     * descriptor that is polled.
     *
     * The sub-poller registers its file descriptor with the master poller to park until
     * there are events to poll. When unparked, it does non-blocking polls and parks
     * again when there are no more events. The sub-poller yields after each poll to help
     * with fairness and to avoid re-registering with the master poller where possible.
     */
    private void subPollerLoop(PollerGroup pollerGroup, Poller masterPoller) {
        assert Thread.currentThread().isVirtual();
        owner = Thread.currentThread();
        try {
            int polled = 0;
            while (!pollerGroup.isShutdown()) {
                if (polled == 0) {
                    masterPoller.poll(fdVal(), 0, () -> true);  // park
                } else {
                    Thread.yield();
                }
                polled = poll(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String toString() {
        return String.format("%s [registered = %d, owner = %s]",
            Objects.toIdentityString(this), map.size(), owner);
    }

    /**
     * The read/write pollers for a virtual thread scheduler.
     */
    private static class PollerGroup {
        private final Executor scheduler;
        private final Poller[] readPollers;
        private final Poller[] writePollers;
        private final Poller masterPoller;
        private final Executor executor;
        private volatile boolean shutdown;

        PollerGroup(Executor scheduler,
                    Poller masterPoller,
                    int readPollerCount,
                    int writePollerCount) throws IOException {
            boolean subPoller = (POLLER_MODE == Mode.VTHREAD_POLLERS);
            Executor executor = null;
            if (subPoller) {
                String namePrefix;
                if (scheduler == DEFAULT_SCHEDULER) {
                    namePrefix = "SubPoller-";
                } else {
                    namePrefix = Objects.toIdentityString(scheduler) + "-SubPoller-";
                }
                @SuppressWarnings("restricted")
                ThreadFactory factory = Thread.ofVirtual()
                        .scheduler(scheduler)
                        .inheritInheritableThreadLocals(false)
                        .name(namePrefix, 0)
                        .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                        .factory();
                executor = Executors.newThreadPerTaskExecutor(factory);
            }

            // read and write pollers (or sub-pollers)
            Poller[] readPollers = new Poller[readPollerCount];
            Poller[] writePollers = new Poller[writePollerCount];
            try {
                for (int i = 0; i < readPollerCount; i++) {
                    readPollers[i] = PROVIDER.readPoller(subPoller);
                }
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = PROVIDER.writePoller(subPoller);
                }
            } catch (Exception e) {
                closeAll(readPollers);
                closeAll(writePollers);
                throw e;
            }

            this.scheduler = scheduler;
            this.masterPoller = masterPoller;
            this.readPollers = readPollers;
            this.writePollers = writePollers;
            this.executor = executor;
        }

        /**
         * Create and starts the poller group for the default scheduler.
         */
        static PollerGroup create() {
            try {
                Poller masterPoller = (POLLER_MODE == Mode.VTHREAD_POLLERS)
                        ? PROVIDER.readPoller(false)
                        : null;
                PollerGroup pollerGroup;
                try {
                    int rc = pollerCount("jdk.readPollers", PROVIDER.defaultReadPollers(POLLER_MODE));
                    int wc = pollerCount("jdk.writePollers", PROVIDER.defaultWritePollers(POLLER_MODE));
                    pollerGroup = new PollerGroup(DEFAULT_SCHEDULER, masterPoller, rc, wc);
                } catch (Exception e) {
                    masterPoller.close();
                    throw e;
                }
                pollerGroup.start();
                return pollerGroup;
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        /**
         * Create and starts the poller group for a custom scheduler.
         */
        static PollerGroup create(Executor scheduler) {
            try {
                Poller masterPoller = DEFAULT_POLLER_GROUP.get().masterPoller();
                var pollerGroup = new PollerGroup(scheduler, masterPoller, 1, 1);
                pollerGroup.start();
                return pollerGroup;
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        /**
         * Start poller threads.
         */
        private void start() {
            if (POLLER_MODE == Mode.VTHREAD_POLLERS) {
                if (scheduler == DEFAULT_SCHEDULER) {
                    startPlatformThread("Master-Poller", masterPoller::pollerLoop);
                }
                Arrays.stream(readPollers).forEach(p -> {
                    executor.execute(() -> p.subPollerLoop(this, masterPoller));
                });
                Arrays.stream(writePollers).forEach(p -> {
                    executor.execute(() -> p.subPollerLoop(this, masterPoller));
                });
            } else {
                // Mode.SYSTEM_THREADS
                Arrays.stream(readPollers).forEach(p -> {
                    startPlatformThread("Read-Poller", p::pollerLoop);
                });
                Arrays.stream(writePollers).forEach(p -> {
                    startPlatformThread("Write-Poller", p::pollerLoop);
                });
            }
        }

        /**
         * Close the given pollers.
         */
        private void closeAll(Poller... pollers) {
            for (Poller poller : pollers) {
                if (poller != null) {
                    poller.close();
                }
            }
        }

        /**
         * Invoked during shutdown to unpark all subpoller threads and wait for
         * them to terminate.
         */
        private void shutdownPollers(Poller... pollers) {
            boolean interrupted = false;
            for (Poller poller : pollers) {
                if (poller.owner instanceof Thread owner) {
                    LockSupport.unpark(owner);
                    while (owner.isAlive()) {
                        try {
                            owner.join();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        void shutdown() {
            if (scheduler == DEFAULT_SCHEDULER || POLLER_MODE == Mode.SYSTEM_THREADS) {
                throw new UnsupportedOperationException();
            }
            shutdown = true;
            shutdownPollers(readPollers);
            shutdownPollers(writePollers);
        }

        /**
         *
         * @return
         */
        boolean isShutdown() {
            return shutdown;
        }

        Poller masterPoller() {
            return masterPoller;
        }

        List<Poller> readPollers() {
            return List.of(readPollers);
        }

        List<Poller> writePollers() {
            return List.of(writePollers);
        }

        /**
         * Returns the read poller for the given file descriptor.
         */
        Poller readPoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, readPollers.length);
            return readPollers[index];
        }

        /**
         * Returns the write poller for the given file descriptor.
         */
        Poller writePoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, writePollers.length);
            return writePollers[index];
        }

        /**
         * Reads the given property name to get the poller count. If the property is
         * set then the value must be a power of 2. Returns 1 if the property is not
         * set.
         * @throws IllegalArgumentException if the property is set to a value that
         * is not a power of 2.
         */
        private static int pollerCount(String propName, int defaultCount) {
            String s = System.getProperty(propName);
            int count = (s != null) ? Integer.parseInt(s) : defaultCount;

            // check power of 2
            if (count != Integer.highestOneBit(count)) {
                String msg = propName + " is set to a value that is not a power of 2";
                throw new IllegalArgumentException(msg);
            }
            return count;
        }

        /**
         * Starts a platform thread to run the given task.
         */
        private void startPlatformThread(String name, Runnable task) {
            try {
                Thread thread = InnocuousThread.newSystemThread(name, task);
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
                thread.start();
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
    }

    /**
     * Returns the poller mode.
     */
    private static Mode pollerMode() {
        String s = System.getProperty("jdk.pollerMode");
        if (s != null) {
            if (s.equalsIgnoreCase(Mode.SYSTEM_THREADS.name()) || s.equals("1")) {
                return Mode.SYSTEM_THREADS;
            } else if (s.equalsIgnoreCase(Mode.VTHREAD_POLLERS.name()) || s.equals("2")) {
                return Mode.VTHREAD_POLLERS;
            } else {
                throw new RuntimeException("Can't parse '" + s + "' as polling mode");
            }
        } else {
            return PROVIDER.defaultPollerMode();
        }
    }

    /**
     * Returns the PollerGroup that the given thread uses to poll file descriptors.
     */
    private static PollerGroup pollerGroup(Thread thread) {
        if (POLLER_MODE == Mode.SYSTEM_THREADS) {
            return DEFAULT_POLLER_GROUP.get();
        }
        Executor scheduler;
        if (thread.isVirtual()) {
            scheduler = JLA.virtualThreadScheduler(thread);
        } else {
            scheduler = DEFAULT_SCHEDULER;
        }
        return POLLER_GROUPS.computeIfAbsent(scheduler, _ -> PollerGroup.create(scheduler));
    }

    /**
     * Invoked before the given scheduler is shutdown. In VTHREAD_POLLERS mode, this
     * method will arrange for the sub poller threads to terminate. Does nothing in
     * SYSTEM_THREADS mode.
     */
    public static void beforeShutdown(Executor executor) {
        if (POLLER_MODE == Mode.VTHREAD_POLLERS) {
            PollerGroup group = POLLER_GROUPS.remove(executor);
            if (group != null) {
                group.shutdown();
            }
        }
    }

    /**
     * Return the master poller or null if there is no master poller.
     */
    public static Poller masterPoller() {
        return DEFAULT_POLLER_GROUP.get().masterPoller();
    }

    /**
     * Return the list of read pollers.
     */
    public static List<Poller> readPollers() {
        return DEFAULT_POLLER_GROUP.get().readPollers();
    }

    /**
     * Return the list of write pollers.
     */
    public static List<Poller> writePollers() {
        return DEFAULT_POLLER_GROUP.get().writePollers();
    }

}

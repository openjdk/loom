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
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationSupport;
import jdk.internal.vm.annotation.Stable;

/**
 * Polls file descriptors. Virtual threads invoke the poll method to park
 * until a given file descriptor is ready for I/O.
 */
public abstract class Poller {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private static final PollerGroup POLLER_GROUP;
    static {
        try {
            PollerProvider provider = PollerProvider.provider();
            Mode mode = pollerMode(provider.defaultPollerMode());
            PollerGroup group = switch (mode) {
                case SYSTEM_THREADS -> new SystemThreadsPollerGroup(provider);
                case VTHREAD_POLLERS -> new VirtualThreadsPollerGroup(provider);
                case PER_CARRIER -> new PerCarrierPollerGroup(provider);
            };
            group.start();
            POLLER_GROUP = group;
        } catch (IOException ioe) {
            throw new ExceptionInInitializerError(ioe);
        }
    }

    // the poller or sub-poller thread
    private @Stable Thread owner;

    // maps file descriptors to parked Thread
    private final Map<Integer, Thread> map = new ConcurrentHashMap<>();

    // shutdown if supported by poller group.
    private volatile boolean shutdown;

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
        VTHREAD_POLLERS,

        /**
         * A dedicated ReadPoller is created for each carrier thread.
         * There is one system-wide (carrier agnostic) WritePoller.
         */
        PER_CARRIER
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
    abstract void close() throws IOException;

    /**
     * Returns the poller's thread owner.
     */
    private Thread owner() {
        return owner;
    }

    /**
     * Sets the poller's thread owner.
     */
    private void setOwner() {
        owner = Thread.currentThread();
    }

    /**
     * Returns true if this poller is marked for shutdown.
     */
    final boolean isShutdown() {
        return shutdown;
    }

    /**
     * Marks this poller for shutdown.
     */
    private void setShutdown() {
        shutdown = true;
    }

    /**
     * Returns the poller's file descriptor to use when polling with the master poller.
     * @throws UnsupportedOperationException if not supported
     */
    int fdVal() {
        throw new UnsupportedOperationException();
    }

    /**
     * Invoked if when this poller's file descriptor is polled by the master poller.
     */
    void pollerPolled() throws IOException {
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
    abstract void implDeregister(int fdVal, boolean polled) throws IOException;

    /**
     * Poll for events. The {@link #polled(int)} method is invoked for each
     * polled file descriptor.
     *
     * @param timeout if positive then block for up to {@code timeout} milliseconds,
     *     if zero then don't block, if -1 then block indefinitely
     * @return >0 if file descriptors are polled, 0 if no file descriptor polled
     */
    abstract int poll(int timeout) throws IOException;

    /**
     * Wakeup the poller thread if blocked in poll.
     *
     * @throws UnsupportedOperationException if not supported
     */
    void wakeupPoller() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Callback by the poll method when a file descriptor is polled.
     */
    final void polled(int fdVal) {
        Thread t = map.remove(fdVal);
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    /**
     * Parks the current thread until a file descriptor is ready for the given op.
     * @param fdVal the file descriptor
     * @param event POLLIN or POLLOUT
     * @param nanos the waiting time or 0 to wait indefinitely
     * @param isOpen supplies a boolean to indicate if the enclosing object is open
     */
    static void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
        POLLER_GROUP.poll(fdVal, event, nanos, isOpen);
    }

    /**
     * Parks the current thread until a Selector's file descriptor is ready.
     * @param fdVal the Selector's file descriptor
     * @param nanos the waiting time or 0 to wait indefinitely
     */
    static void pollSelector(int fdVal, long nanos) throws IOException {
        POLLER_GROUP.pollSelector(fdVal, nanos);
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
    private void poll(int fdVal, long nanos, BooleanSupplier isOpen) throws IOException {
        register(fdVal);
        try {
            if (isOpen.getAsBoolean() && !isShutdown()) {
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
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Deregister the file descriptor so that the file descriptor is not polled.
     */
    private void deregister(int fdVal) throws IOException {
        Thread previous = map.remove(fdVal);
        boolean polled = (previous == null);
        assert polled || previous == Thread.currentThread();
        try {
            implDeregister(fdVal, polled);
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    /**
     * Master polling loop. The {@link #polled(int)} method is invoked for each file
     * descriptor that is polled.
     */
    private void pollerLoop() {
        setOwner();
        try {
            while (!isShutdown()) {
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
    private void subPollerLoop(Poller masterPoller) {
        assert Thread.currentThread().isVirtual();
        setOwner();
        try {
            int polled = 0;
            while (!isShutdown()) {
                if (polled == 0) {
                    masterPoller.poll(fdVal(), 0, () -> true);  // park
                    pollerPolled();
                } else {
                    Thread.yield();
                }
                polled = poll(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Unparks all threads waiting on a file descriptor registered with this poller.
     */
    private void wakeupAll() {
        map.values().forEach(LockSupport::unpark);
    }

    @Override
    public String toString() {
        return String.format("%s [registered = %d, owner = %s]",
            Objects.toIdentityString(this), map.size(), owner);
    }

    /**
     * A group of poller threads that support virtual threads polling file descriptors.
     */
    private static abstract class PollerGroup {
        private final PollerProvider provider;

        PollerGroup(PollerProvider provider) {
            this.provider = provider;
        }

        PollerProvider provider() {
            return provider;
        }

        /**
         * Starts the poller group and any system-wide poller threads.
         */
        abstract void start();

        /**
         * Parks the current thread until a file descriptor is ready for the given op.
         */
        abstract void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException;

       /**
        * Parks the current thread until a Selector's file descriptor is ready.
        */
        void pollSelector(int fdVal, long nanos) throws IOException {
            poll(fdVal, Net.POLLIN, nanos, () -> true);
        }

        /**
         * Starts a platform thread to run the given task.
         */
        protected final void startPlatformThread(String name, Runnable task) {
            Thread thread = InnocuousThread.newSystemThread(name, task);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());
            thread.start();
        }

        /**
         * Return the master poller for default scheduler, or null if no master poller.
         */
        abstract Poller defaultMasterPoller();

        /**
         * Return the read pollers for the default scheduler.
         */
        abstract List<Poller> defaultReadPollers();

        /**
         * Return the write pollers for the default scheduler.
         */
        abstract List<Poller> defaultWritePollers();
    }

    /**
     * The poller group for the SYSTEM_THREADS polling mode. The read and write pollers
     * are system-wide (scheduler agnostic) platform threads.
     */
    private static class SystemThreadsPollerGroup extends PollerGroup {
        // system-wide read and write pollers
        private final Poller[] readPollers;
        private final Poller[] writePollers;

        SystemThreadsPollerGroup(PollerProvider provider) throws IOException {
            super(provider);

            int readPollerCount = pollerCount("jdk.readPollers",
                    provider.defaultReadPollers(Mode.SYSTEM_THREADS));
            int writePollerCount = pollerCount("jdk.writePollers",
                    provider.defaultWritePollers(Mode.SYSTEM_THREADS));

            Poller[] readPollers = new Poller[readPollerCount];
            Poller[] writePollers = new Poller[writePollerCount];
            try {
                for (int i = 0; i < readPollerCount; i++) {
                    readPollers[i] = provider.readPoller(false);
                }
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = provider.writePoller(false);
                }
            } catch (Throwable e) {
                closeAll(readPollers);
                closeAll(writePollers);
                throw e;
            }

            this.readPollers = readPollers;
            this.writePollers = writePollers;
        }

        /**
         * Close the given pollers.
         */
        private static void closeAll(Poller... pollers) {
            for (Poller poller : pollers) {
                if (poller != null) {
                    try {
                        poller.close();
                    } catch (IOException _) { }
                }
            }
        }

        /**
         * Start poller threads.
         */
        @Override
        void start() {
            Arrays.stream(readPollers).forEach(p -> {
                startPlatformThread("Read-Poller", p::pollerLoop);
            });
            Arrays.stream(writePollers).forEach(p -> {
                startPlatformThread("Write-Poller", p::pollerLoop);
            });
        }

        private Poller readPoller(int fdVal) {
            int index = provider().fdValToIndex(fdVal, readPollers.length);
            return readPollers[index];
        }

        private Poller writePoller(int fdVal) {
            int index = provider().fdValToIndex(fdVal, writePollers.length);
            return writePollers[index];
        }

        @Override
        void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
            Poller poller = (event == Net.POLLIN)
                    ? readPoller(fdVal)
                    : writePoller(fdVal);
            poller.poll(fdVal, nanos, isOpen);
        }

        @Override
        Poller defaultMasterPoller() {
            return null;
        }

        @Override
        List<Poller> defaultReadPollers() {
            return List.of(readPollers);
        }

        @Override
        List<Poller> defaultWritePollers() {
            return List.of(writePollers);
        }
    }

    /**
     * The poller group for the VTHREAD_POLLERS poller mode. The read and write pollers
     * are scheduler-specific virtual threads. The default scheduler may have many read
     * and write pollers. Custom schedulers have one read poller and one write poller.
     * When read and write pollers must block then they register with a system-wide
     * (scheduler agnostic) "master poller" that runs in a dedicated platform thread.
     */
    private static class VirtualThreadsPollerGroup extends PollerGroup {
        private static final Thread.VirtualThreadScheduler DEFAULT_SCHEDULER = JLA.defaultVirtualThreadScheduler();

        // system-wide master poller
        private final Poller masterPoller;

        // number of read and write pollers for default scheduler
        private final int defaultReadPollerCount;
        private final int defaultWritePollerCount;

        // maps scheduler to a set of read and write pollers
        private record Pollers(ExecutorService executor, Poller[] readPollers, Poller[] writePollers) { }
        private final Map<Thread.VirtualThreadScheduler, Pollers> POLLERS = new ConcurrentHashMap<>();

        VirtualThreadsPollerGroup(PollerProvider provider) throws IOException {
            super(provider);

            var mode = Mode.VTHREAD_POLLERS;
            this.defaultReadPollerCount = pollerCount("jdk.readPollers",
                    provider.defaultReadPollers(mode));
            this.defaultWritePollerCount = pollerCount("jdk.writePollers",
                    provider.defaultWritePollers(mode));
            this.masterPoller = provider.readPoller(false);
        }

        @Override
        void start() {
            startPlatformThread("Master-Poller", masterPoller::pollerLoop);
        }

        /**
         * Create the read and write pollers for the given scheduler.
         */
        private Pollers createPollers(Thread.VirtualThreadScheduler scheduler) {
            int readPollerCount;
            int writePollerCount;
            if (scheduler == DEFAULT_SCHEDULER) {
                readPollerCount = defaultReadPollerCount;
                writePollerCount = defaultWritePollerCount;
            } else {
                readPollerCount = 1;
                writePollerCount = 1;
            }

            Poller[] readPollers = new Poller[readPollerCount];
            Poller[] writePollers = new Poller[writePollerCount];
            try {
                for (int i = 0; i < readPollerCount; i++) {
                    readPollers[i] = provider().readPoller(true);
                }
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = provider().writePoller(true);
                }
            } catch (IOException ioe) {
                closeAll(readPollers);
                closeAll(writePollers);
                throw new UncheckedIOException(ioe);
            }

            @SuppressWarnings("restricted")
            ThreadFactory factory = Thread.ofVirtual()
                    .scheduler(scheduler)
                    .inheritInheritableThreadLocals(false)
                    .name("SubPoller-", 0)
                    .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(factory);

            Arrays.stream(readPollers).forEach(p -> {
                executor.execute(() -> p.subPollerLoop(masterPoller));
            });
            Arrays.stream(writePollers).forEach(p -> {
                executor.execute(() -> p.subPollerLoop(masterPoller));
            });

            return new Pollers(executor, readPollers, writePollers);
        }

        @Override
        void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
            Thread.VirtualThreadScheduler scheduler;
            if (Thread.currentThread().isVirtual()) {
                scheduler = JLA.virtualThreadScheduler(Thread.currentThread());
            } else {
                scheduler = DEFAULT_SCHEDULER;
            }
            Pollers pollers;
            try {
                pollers = POLLERS.computeIfAbsent(scheduler, _ -> createPollers(scheduler));
            } catch (UncheckedIOException uioe) {
                throw uioe.getCause();
            }

            Poller poller;
            if (event == Net.POLLIN) {
                Poller[] readPollers = pollers.readPollers();
                int index = provider().fdValToIndex(fdVal, readPollers.length);
                poller = readPollers[index];
            } else {
                Poller[] writePollers = pollers.writePollers();
                int index = provider().fdValToIndex(fdVal, writePollers.length);
                poller = writePollers[index];
            }
            poller.poll(fdVal, nanos, isOpen);
        }

        @Override
        void pollSelector(int fdVal, long nanos) throws IOException {
            masterPoller.poll(fdVal, nanos, () -> true);
        }

        /**
         * Close the given pollers.
         */
        private static void closeAll(Poller... pollers) {
            for (Poller poller : pollers) {
                if (poller != null) {
                    try {
                        poller.close();
                    } catch (IOException _) { }
                }
            }
        }

        @Override
        Poller defaultMasterPoller() {
            return masterPoller;
        }

        @Override
        List<Poller> defaultReadPollers() {
            Pollers pollers = POLLERS.get(DEFAULT_SCHEDULER);
            return (pollers != null) ? List.of(pollers.readPollers()) : List.of();
        }

        @Override
        List<Poller> defaultWritePollers() {
            Pollers pollers = POLLERS.get(DEFAULT_SCHEDULER);
            return (pollers != null) ? List.of(pollers.writePollers()) : List.of();
        }
    }

    /**
     * The poller group for the PER_CARRIER polling mode. A dedicated read poller is
     * created for each carrier thread. When a virtual thread polls a file descriptor
     * for POLLIN, then it will use (almost always, not guaranteed) to see the read
     * poller for its carrier. The read poller terminates if carrier thread terminates.
     * There is one system-wide (carrier agnostic) write poller.
     */
    private static class PerCarrierPollerGroup extends PollerGroup {
        private static final boolean USE_SUBPOLLER;
        static {
            String s = System.getProperty("jdk.useSubPoller");
            USE_SUBPOLLER = (s == null) || s.isEmpty() || Boolean.parseBoolean(s);
        }

        private static final TerminatingThreadLocal<PerCarrierPollerGroup> POLLER_GROUP =
            new TerminatingThreadLocal<>() {
                @Override
                protected void threadTerminated(PerCarrierPollerGroup pollerGroup) {
                    pollerGroup.carrierTerminated();
                }
            };

        // -XX:-VMContinuations or called from platform thread
        private static final Thread PLACEHOLDER = new Thread();

        // maps carrier thread to its read poller
        private final Map<Thread, Poller> READ_POLLERS = new ConcurrentHashMap<>();

        private final Poller writePoller;
        private final Poller masterPoller;

        PerCarrierPollerGroup(PollerProvider provider) throws IOException {
            super(provider);

            this.writePoller = provider.writePoller(false);
            if (USE_SUBPOLLER) {
                this.masterPoller = provider.readPoller(false);
            } else {
                this.masterPoller = null;
            }
        }

        @Override
        void start() {
            startPlatformThread("Write-Poller", writePoller::pollerLoop);
            if (masterPoller != null) {
                startPlatformThread("Master-Poller", masterPoller::pollerLoop);
            }
        }

        /**
         * Starts a read poller with the given name.
         * @throws UncheckedIOException if an I/O error occurs
         */
        private Poller startReadPoller(String name) {
            try {
                Poller readPoller = provider().readPoller(USE_SUBPOLLER);
                if (USE_SUBPOLLER) {
                    var scheduler = JLA.virtualThreadScheduler(Thread.currentThread());
                    @SuppressWarnings("restricted")
                    var _ = Thread.ofVirtual().scheduler(scheduler)
                            .inheritInheritableThreadLocals(false)
                            .name(name)
                            .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                            .start(() -> subPollerLoop(readPoller));
                } else {
                    startPlatformThread(name, () -> pollLoop(readPoller));
                }
                return readPoller;
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        }

        @Override
        void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
            if (event == Net.POLLOUT) {
                writePoller.poll(fdVal, nanos, isOpen);
                return;
            }

            assert event == Net.POLLIN;
            if (Thread.currentThread().isVirtual() && ContinuationSupport.isSupported()) {
                Poller readPoller;
                // get read poller for this carrier
                Continuation.pin();
                try {
                    Thread carrier = JLA.currentCarrierThread();
                    try {
                        readPoller = READ_POLLERS.computeIfAbsent(carrier, _ -> {
                            String name = carrier.getName() + "-Read-Poller";
                            return startReadPoller(name);
                        });
                    } catch (UncheckedIOException uioe) {
                        throw uioe.getCause();
                    }
                    POLLER_GROUP.set(this);
                } finally {
                    Continuation.unpin();
                }
                // may execute on a different carrier
                readPoller.poll(fdVal, nanos, isOpen);
                return;
            }

            // -XX:-VMContinuations or called from platform thread
            if (masterPoller != null) {
                masterPoller.poll(fdVal, nanos, isOpen);
            } else {
                Poller readPoller;
                try {
                    readPoller = READ_POLLERS.computeIfAbsent(PLACEHOLDER,
                            _ -> startReadPoller("Read-Poller"));
                } catch (UncheckedIOException uioe) {
                    throw uioe.getCause();
                }
                readPoller.poll(fdVal, nanos, isOpen);
            }
        }

        @Override
        void pollSelector(int fdVal, long nanos) throws IOException {
            poll(fdVal, Net.POLLIN, nanos, () -> true);
        }

        /**
         * Read-poller polling loop.
         */
        private void pollLoop(Poller readPoller) {
            try {
                readPoller.pollerLoop();
            } finally {
                // wakeup all threads waiting on file descriptors registered with the
                // read poller, these I/O operation will migrate to another carrier.
                readPoller.wakeupAll();
            }
        }

        /**
         * Read-poll sub-poller polling loop.
         */
        private void subPollerLoop(Poller readPoller) {
            try {
                readPoller.subPollerLoop(masterPoller);
            } finally {
                // wakeup all threads waiting on file descriptors registered with the
                // read poller, these I/O operation will migrate to another carrier.
                readPoller.wakeupAll();
            }
        }

        /**
         * Invoked by the carrier thread before it terminates.
         */
        private void carrierTerminated() {
            Poller readPoller = READ_POLLERS.remove(Thread.currentThread());
            if (readPoller != null) {
                readPoller.setShutdown();
                try {
                    readPoller.wakeupPoller();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        Poller defaultMasterPoller() {
            return masterPoller;
        }

        @Override
        List<Poller> defaultReadPollers() {
            // return all read pollers for now.
            return READ_POLLERS.values().stream().toList();
        }

        @Override
        List<Poller> defaultWritePollers() {
            return List.of(writePoller);
        }
    }

    /**
     * Returns the poller mode.
     */
    private static Mode pollerMode(Mode defaultPollerMode) {
        String s = System.getProperty("jdk.pollerMode");
        if (s != null) {
            if (s.equalsIgnoreCase(Mode.SYSTEM_THREADS.name()) || s.equals("1")) {
                return Mode.SYSTEM_THREADS;
            } else if (s.equalsIgnoreCase(Mode.VTHREAD_POLLERS.name()) || s.equals("2")) {
                return Mode.VTHREAD_POLLERS;
            } else if (s.equalsIgnoreCase(Mode.PER_CARRIER.name()) || s.equals("3")) {
                return Mode.PER_CARRIER;
            } else {
                throw new RuntimeException("Can't parse '" + s + "' as polling mode");
            }
        } else {
            return defaultPollerMode;
        }
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
     * Return the master poller or null if there is no master poller.
     */
    public static Poller masterPoller() {
        return POLLER_GROUP.defaultMasterPoller();
    }

    /**
     * Return the list of read pollers.
     */
    public static List<Poller> readPollers() {
        return POLLER_GROUP.defaultReadPollers();
    }

    /**
     * Return the list of write pollers.
     */
    public static List<Poller> writePollers() {
        return POLLER_GROUP.defaultWritePollers();
    }
}

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
import java.lang.Thread.VirtualThreadScheduler;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private static final PollerProvider PROVIDER = PollerProvider.provider();

    // no default scheduler with -XX:-VMContinuations
    private static final VirtualThreadScheduler DEFAULT_SCHEDULER =
            ContinuationSupport.isSupported()
                    ? JLA.defaultVirtualThreadScheduler()
                    : null;

    // PollerGroup for the default virtual thread scheduler
    private static final PollerGroup DEFAULT_POLLER_GROUP = defaultPollerGroup();

    // PollerGroup for virtual threads scheduled by custom schedulers
    private static final Supplier<PollerGroup> CUSTOM_SCHEDULER_POLLER_GROUP =
            StableValue.supplier(Poller::customSchedulerPollerGroup);

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
         * Read and write pollers are platform threads that block waiting for events and
         * unpark virtual threads when file descriptors are ready for I/O.
         */
        SYSTEM_THREADS,

        /**
         * Read and write pollers are virtual threads that poll for events, yielding
         * between polls and unparking virtual threads when file descriptors are
         * ready for I/O. If there are no events then the poller threads park until there
         * are I/O events to poll. This mode helps to integrate polling with virtual
         * thread scheduling. The approach is similar to the default scheme in "User-level
         * Threading: Have Your Cake and Eat It Too" by Karsten and Barghi 2020
         * (https://dl.acm.org/doi/10.1145/3379483).
         */
        VTHREAD_POLLERS,

        /**
         * Read pollers are per-carrier virtual threads that poll for events, yielding
         * between polls and unparking virtual threads when file descriptors are ready
         * for I/O. If there are no events then the poller threads park until there
         * are I/O events to poll. The write poller is a system-wide platform thread.
         */
        POLLER_PER_CARRIER
    }

    /**
     * Create and return the PollerGroup to use when polling with virtual threads
     * that are scheduled with the default virtual thread scheduler.
     */
    private static PollerGroup defaultPollerGroup() {
        try {
            Mode mode = pollerMode(PROVIDER.defaultPollerMode());
            PollerGroup group = switch (mode) {
                case SYSTEM_THREADS     -> new SystemThreadsPollerGroup();
                case VTHREAD_POLLERS    -> new VThreadsPollerGroup();
                case POLLER_PER_CARRIER -> {
                    int defaultWritePollers = PROVIDER.defaultWritePollers(Mode.POLLER_PER_CARRIER);
                    int writePollers = pollerCount("jdk.writePollers", defaultWritePollers);
                    yield new PollerPerCarrierPollerGroup(writePollers);
                }
            };
            group.start();
            return group;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    /**
     * Create and return the PollerGroup to use when polling with virtual threads
     * that are scheduled with a custom virtual therad scheduler.
     */
    private static PollerGroup customSchedulerPollerGroup() {
        try {
            var group = new PollerPerCarrierPollerGroup(1);
            group.start();
            return group;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
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
     * Returns the PollerGroup to use for the current thread.
     */
    private static PollerGroup pollerGroup() {
        if (Thread.currentThread().isVirtual()
                && ContinuationSupport.isSupported()
                && JLA.virtualThreadScheduler(Thread.currentThread()) != DEFAULT_SCHEDULER) {
            return CUSTOM_SCHEDULER_POLLER_GROUP.get();
        } else {
            return DEFAULT_POLLER_GROUP;
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
        pollerGroup().poll(fdVal, event, nanos, isOpen);
    }

    /**
     * Parks the current thread until a Selector's file descriptor is ready.
     * @param fdVal the Selector's file descriptor
     * @param nanos the waiting time or 0 to wait indefinitely
     */
    static void pollSelector(int fdVal, long nanos) throws IOException {
        pollerGroup().pollSelector(fdVal, nanos);
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
        PollerGroup() { }

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
         * Return the master poller, or null if no master poller.
         */
        abstract Poller masterPoller();

        /**
         * Return the read pollers.
         */
        abstract List<Poller> readPollers();

        /**
         * Return the write pollers.
         */
        abstract List<Poller> writePollers();

        /**
         * Close the given pollers.
         */
        static void closeAll(Poller... pollers) {
            for (Poller poller : pollers) {
                if (poller != null) {
                    try {
                        poller.close();
                    } catch (IOException _) { }
                }
            }
        }
    }

    /**
     * SYSTEM_THREADS poller group. The read and write pollers are system-wide platform threads.
     */
    private static class SystemThreadsPollerGroup extends PollerGroup {
        // system-wide read and write pollers
        private final Poller[] readPollers;
        private final Poller[] writePollers;

        SystemThreadsPollerGroup() throws IOException {
            int readPollerCount = pollerCount("jdk.readPollers",
                    PROVIDER.defaultReadPollers(Mode.SYSTEM_THREADS));
            int writePollerCount = pollerCount("jdk.writePollers",
                    PROVIDER.defaultWritePollers(Mode.SYSTEM_THREADS));

            Poller[] readPollers = new Poller[readPollerCount];
            Poller[] writePollers = new Poller[writePollerCount];
            try {
                for (int i = 0; i < readPollerCount; i++) {
                    readPollers[i] = PROVIDER.readPoller(false);
                }
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = PROVIDER.writePoller(false);
                }
            } catch (Throwable e) {
                closeAll(readPollers);
                closeAll(writePollers);
                throw e;
            }

            this.readPollers = readPollers;
            this.writePollers = writePollers;
        }

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
            int index = PROVIDER.fdValToIndex(fdVal, readPollers.length);
            return readPollers[index];
        }

        private Poller writePoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, writePollers.length);
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
        Poller masterPoller() {
            return null;
        }

        @Override
        List<Poller> readPollers() {
            return List.of(readPollers);
        }

        @Override
        List<Poller> writePollers() {
            return List.of(writePollers);
        }
    }

    /**
     * VTHREAD_POLLERS poller group. The read and write pollers are virtual threads.
     * When read and write pollers need to block then they register with a system-wide
     * "master poller" that runs in a dedicated platform thread.
     */
    private static class VThreadsPollerGroup extends PollerGroup {
        private final Poller[] readPollers;
        private final Poller[] writePollers;
        private final Poller masterPoller;

        // keep virtual thread pollers alive
        private final Executor executor;

        VThreadsPollerGroup() throws IOException {
            int readPollerCount = pollerCount("jdk.readPollers",
                    PROVIDER.defaultReadPollers(Mode.VTHREAD_POLLERS));
            int writePollerCount = pollerCount("jdk.writePollers",
                    PROVIDER.defaultWritePollers(Mode.VTHREAD_POLLERS));

            Poller[] readPollers = new Poller[readPollerCount];
            Poller[] writePollers = new Poller[writePollerCount];
            Poller masterPoller = PROVIDER.readPoller(false);
            try {
                for (int i = 0; i < readPollerCount; i++) {
                    readPollers[i] = PROVIDER.readPoller(true);
                }
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = PROVIDER.writePoller(true);
                }
            } catch (Throwable e) {
                masterPoller.close();
                closeAll(readPollers);
                closeAll(writePollers);
                throw e;
            }

            this.readPollers = readPollers;
            this.writePollers = writePollers;
            this.masterPoller = masterPoller;

            ThreadFactory factory = Thread.ofVirtual()
                    .inheritInheritableThreadLocals(false)
                    .name("SubPoller-", 0)
                    .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                    .factory();
            this.executor = Executors.newThreadPerTaskExecutor(factory);
        }

        @Override
        void start() {
            Arrays.stream(readPollers).forEach(p -> {
                executor.execute(() -> p.subPollerLoop(masterPoller));
            });
            Arrays.stream(writePollers).forEach(p -> {
                executor.execute(() -> p.subPollerLoop(masterPoller));
            });
            startPlatformThread("Master-Poller", masterPoller::pollerLoop);
        }

        private Poller readPoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, readPollers.length);
            return readPollers[index];
        }

        private Poller writePoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, writePollers.length);
            return writePollers[index];
        }

        @Override
        void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
            Poller poller = (event == Net.POLLIN) ? readPoller(fdVal) : writePoller(fdVal);
            poller.poll(fdVal, nanos, isOpen);
        }

        @Override
        void pollSelector(int fdVal, long nanos) throws IOException {
            masterPoller.poll(fdVal, nanos, () -> true);
        }

        @Override
        Poller masterPoller() {
            return masterPoller;
        }

        @Override
        List<Poller> readPollers() {
            return List.of(readPollers);
        }

        @Override
        List<Poller> writePollers() {
            return List.of(writePollers);
        }
    }

    /**
     * POLLER_PER_CARRIER poller group. The read poller is a per-carrier virtual thread.
     * When a virtual thread polls a file descriptor for POLLIN, then it will use (almost
     * always, not guaranteed) the read poller for its carrier. When a read poller needs
     * to block then it registers with a system-wide "master poller" that runs in a
     * dedicated platform thread. The read poller terminates if the carrier terminates.
     * The write pollers are system-wide platform threads (usually one).
     */
    private static class PollerPerCarrierPollerGroup extends PollerGroup {
        private record CarrierPoller(PollerPerCarrierPollerGroup group, Poller readPoller) { }
        private static final TerminatingThreadLocal<CarrierPoller> CARRIER_POLLER =
            new TerminatingThreadLocal<>() {
                @Override
                protected void threadTerminated(CarrierPoller carrierPoller) {
                    Poller readPoller = carrierPoller.readPoller();
                    carrierPoller.group().carrierTerminated(readPoller);
                }
            };

        private final Set<Poller> readPollers = ConcurrentHashMap.newKeySet();
        private final Poller[] writePollers;
        private final Poller masterPoller;

        PollerPerCarrierPollerGroup(int writePollerCount) throws IOException {
            Poller[] writePollers = new Poller[writePollerCount];
            Poller masterPoller = PROVIDER.readPoller(false);
            try {
                for (int i = 0; i < writePollerCount; i++) {
                    writePollers[i] = PROVIDER.writePoller(false);
                }
            } catch (Throwable e) {
                masterPoller.close();
                closeAll(writePollers);
                throw e;
            }

            this.writePollers = writePollers;
            this.masterPoller = masterPoller;
        }

        @Override
        void start() {
            Arrays.stream(writePollers).forEach(p -> {
                startPlatformThread("Write-Poller", p::pollerLoop);
            });
            startPlatformThread("Master-Poller", masterPoller::pollerLoop);
        }

        private Poller writePoller(int fdVal) {
            int index = PROVIDER.fdValToIndex(fdVal, writePollers.length);
            return writePollers[index];
        }

        /**
         * Starts a read sub-poller in a virtual thread.
         */
        private Poller startReadPoller() throws IOException {
            assert Thread.currentThread().isVirtual() && ContinuationSupport.isSupported();
            Thread carrier = JLA.currentCarrierThread();
            var scheduler = JLA.virtualThreadScheduler(Thread.currentThread());

            // create read subpoller
            Poller readPoller = PROVIDER.readPoller(true);
            readPollers.add(readPoller);

            // start virtual thread to execute sub-polling loop
            @SuppressWarnings("restricted")
            var _ = Thread.ofVirtual().scheduler(scheduler)
                    .inheritInheritableThreadLocals(false)
                    .name(carrier.getName() + "-Read-Poller")
                    .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                    .start(() -> subPollerLoop(readPoller));
            return readPoller;
        }

        @Override
        void poll(int fdVal, int event, long nanos, BooleanSupplier isOpen) throws IOException {
            if (event == Net.POLLOUT) {
                writePoller(fdVal).poll(fdVal, nanos, isOpen);
                return;
            }

            assert event == Net.POLLIN;
            if (Thread.currentThread().isVirtual() && ContinuationSupport.isSupported()) {
                // get read poller for this carrier
                Poller readPoller;
                Continuation.pin();
                try {
                    CarrierPoller carrierPoller = CARRIER_POLLER.get();
                    if (carrierPoller != null) {
                        readPoller = carrierPoller.readPoller();
                    } else {
                        // first poll on this carrier
                        readPoller = startReadPoller();
                        CARRIER_POLLER.set(new CarrierPoller(this, readPoller));
                    }
                } finally {
                    Continuation.unpin();
                }
                // may execute on a different carrier
                readPoller.poll(fdVal, nanos, isOpen);
            } else {
                // -XX:-VMContinuations or called from platform thread
                masterPoller.poll(fdVal, nanos, isOpen);
            }
        }

        @Override
        void pollSelector(int fdVal, long nanos) throws IOException {
            masterPoller.poll(fdVal, nanos, () -> true);
        }

        /**
         * Sub-poller polling loop.
         */
        private void subPollerLoop(Poller readPoller) {
            try {
                readPoller.subPollerLoop(masterPoller);
            } finally {
                // wakeup all threads waiting on file descriptors registered with the
                // read poller, these I/O operation will migrate to another carrier.
                readPoller.wakeupAll();

                // remove from serviceability view
                readPollers.remove(readPoller);
            }
        }

        /**
         * Invoked by the carrier thread before it terminates.
         */
        private void carrierTerminated(Poller readPoller) {
            readPoller.setShutdown();
            try {
                readPoller.wakeupPoller();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        @Override
        Poller masterPoller() {
            return masterPoller;
        }

        @Override
        List<Poller> readPollers() {
            return readPollers.stream().toList();
        }

        @Override
        List<Poller> writePollers() {
            return List.of(writePollers);
        }
    }

    /**
     * Returns the poller mode.
     */
    private static Mode pollerMode(Mode defaultPollerMode) {
        String s = System.getProperty("jdk.pollerMode");
        if (s != null) {
            int intValue = Integer.parseInt(s);
            return switch (intValue) {
                case 1 -> Mode.SYSTEM_THREADS;
                case 2 -> Mode.VTHREAD_POLLERS;
                case 3 -> Mode.POLLER_PER_CARRIER;
                default -> {
                    throw new RuntimeException(intValue + " is not a valid polling mode");
                }
            };
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
        return DEFAULT_POLLER_GROUP.masterPoller();
    }

    /**
     * Return the list of read pollers.
     */
    public static List<Poller> readPollers() {
        return DEFAULT_POLLER_GROUP.readPollers();
    }

    /**
     * Return the list of write pollers.
     */
    public static List<Poller> writePollers() {
        return DEFAULT_POLLER_GROUP.writePollers();
    }
}

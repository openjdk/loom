/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
package java.lang;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.ThreadDumper;
import jdk.internal.vm.annotation.ChangesCurrentThread;
import sun.nio.ch.Interruptible;
import sun.security.action.GetPropertyAction;
import static java.util.concurrent.TimeUnit.*;

/**
 * A thread that is scheduled by the Java virtual machine rather than the operating
 * system.
 */
class VirtualThread extends Thread {
    private static final ContinuationScope VTHREAD_SCOPE = new ContinuationScope("VirtualThreads");
    private static final ForkJoinPool DEFAULT_SCHEDULER = createDefaultScheduler();
    private static final ScheduledExecutorService UNPARKER = createDelayedTaskScheduler();
    private static final int TRACE_PINNING_MODE = tracePinningMode();

    private static final VarHandle STATE;
    private static final VarHandle PARK_PERMIT;
    private static final VarHandle CARRIER_THREAD;
    private static final VarHandle LOCK;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(VirtualThread.class, "state", int.class);
            PARK_PERMIT = l.findVarHandle(VirtualThread.class, "parkPermit", boolean.class);
            CARRIER_THREAD = l.findVarHandle(VirtualThread.class, "carrierThread", Thread.class);
            LOCK = l.findVarHandle(VirtualThread.class, "lock", ReentrantLock.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    // scheduler and continuation
    private final Executor scheduler;
    private final Continuation cont;
    private final Runnable runContinuation;

    // virtual thread state, accessed by VM so need to coordinate changes
    private volatile int state;

    private static final int NEW      = 0;
    private static final int STARTED  = 1;
    private static final int RUNNABLE = 2;     // runnable-unmounted
    private static final int RUNNING  = 3;     // runnable-mounted
    private static final int PARKING  = 4;
    private static final int PARKED   = 5;     // unmounted
    private static final int PINNED   = 6;     // mounted
    private static final int YIELDING = 7;     // Thread.yield
    private static final int TERMINATED = 99;  // final state

    // can be suspended from scheduling when parked
    private static final int SUSPENDED = 1 << 8;
    private static final int PARKED_SUSPENDED = (PARKED | SUSPENDED);

    // parking permit, may eventually be merged into state
    private volatile boolean parkPermit;

    // carrier thread when mounted
    private volatile Thread carrierThread;

    // lock/condition used when waiting (join or pinned)
    private volatile ReentrantLock lock;   // created lazily
    private Condition condition;           // created lazily while holding lock

    /**
     * Creates a new {@code VirtualThread} to run the given task with the given
     * scheduler. If the given scheduler is {@code null} and the current thread
     * is a platform thread then the newly created virtual thread will use the
     * default scheduler. If given scheduler is {@code null} and the current
     * thread is a virtual thread then the current thread's scheduler is used.
     *
     * @param scheduler the scheduler or null.
     * @param name thread name
     * @param characteristics characteristics
     * @param task the task to execute
     */
    VirtualThread(Executor scheduler, String name, int characteristics, Runnable task) {
        super(name, characteristics);
        Objects.requireNonNull(task);

        // choose scheduler if not specified
        if (scheduler == null) {
            Thread parent = Thread.currentThread();
            if (parent instanceof VirtualThread vparent) {
                scheduler = vparent.scheduler;
            } else {
                scheduler = DEFAULT_SCHEDULER;
            }
        }

        this.scheduler = scheduler;
        this.cont = new VThreadContinuation(this, task);
        if (scheduler != DEFAULT_SCHEDULER) {
            this.runContinuation = new CustomRunner(this);
        } else {
            this.runContinuation = this::runContinuation;
        }
    }

    /**
     * Returns the default scheduler.
     */
    static Executor defaultScheduler() {
        return DEFAULT_SCHEDULER;
    }

    /**
     * Returns the continuation scope used for virtual threads.
     */
    static ContinuationScope continuationScope() {
        return VTHREAD_SCOPE;
    }

    /**
     * The continuation that a virtual thread executes.
     */
    private static class VThreadContinuation extends Continuation {
        VThreadContinuation(VirtualThread vthread, Runnable task) {
            super(VTHREAD_SCOPE, new TaskWrapper(vthread, task));
        }
        @Override
        protected void onPinned(Continuation.Pinned reason) {
            if (TRACE_PINNING_MODE > 0) {
                boolean printAll = (TRACE_PINNING_MODE == 1);
                PinnedThreadPrinter.printStackTrace(System.out, printAll);
            }
        }
    }

    /**
     * Wraps a task so that it executes in the context of a virtual thread.
     * The virtual thread is mounted before the task runs, then unmounts when
     * the task completes.
     */
    private static class TaskWrapper implements Runnable {
        private final VirtualThread vthread;
        private final Runnable task;
        TaskWrapper(VirtualThread vthread, Runnable task) {
            this.vthread = vthread;
            this.task = task;
        }
        @ChangesCurrentThread
        public void run() {
            vthread.mount(true);
            try {
                task.run();
            } catch (Throwable exc) {
                vthread.dispatchUncaughtException(exc);
            } finally {
                vthread.unmount();
            }
        }
    }

    /**
     * The task to execute when using a custom scheduler.
     */
    private static class CustomRunner implements VirtualThreadTask {
        private final VirtualThread vthread;
        private static final VarHandle ATTACHMENT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                ATTACHMENT = l.findVarHandle(CustomRunner.class, "attachment", Object.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private volatile Object attachment;
        CustomRunner(VirtualThread vthread) {
            this.vthread = vthread;
        }
        @Override
        public void run() {
            vthread.runContinuation();
        }
        @Override
        public Thread thread() {
            return vthread;
        }
        @Override
        public Object attach(Object ob) {
            return ATTACHMENT.getAndSet(this, ob);
        }
        @Override
        public Object attachment() {
            return attachment;
        }
    }

    /**
     * Runs or continues execution of the continuation on the current thread.
     */
    private void runContinuation() {
        // the carrier thread should be a platform thread
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalCallerException();
        }

        // set state to RUNNING
        int initialState = state();
        if (initialState == STARTED && compareAndSetState(STARTED, RUNNING)) {
            // first run
        } else if (initialState == RUNNABLE && compareAndSetState(RUNNABLE, RUNNING)) {
            // consume parking permit
            setParkPermit(false);
        } else {
            throw new IllegalStateException();
        }

        try {
            cont.run();
        } finally {
            if (cont.isDone()) {
                afterTerminate(/*executed*/ true);
            } else {
                afterYield();
            }
        }
    }

    /**
     * Submits the runContinuation task to the scheduler. If externalExecuteTask
     * is true then it pushes the tasks current carrier thread's work queue.
     * @throws RejectedExecutionException
     */
    private void submitRunContinuation(boolean externalExecuteTask) {
        if (externalExecuteTask && scheduler == DEFAULT_SCHEDULER) {
            ForkJoinPools.externalExecuteTask(DEFAULT_SCHEDULER, runContinuation);
        } else {
            scheduler.execute(runContinuation);
        }
    }

    private void submitRunContinuation() {
        submitRunContinuation(true);
    }

    /**
     * Mounts this virtual thread onto the current carrier thread.
     */
    @ChangesCurrentThread
    private void mount(boolean firstMount) {
        //assert this.carrierThread == null;

        // notify JVMTI agents
        boolean notifyJvmti = notifyJvmtiEvents;
        if (notifyJvmti) {
            notifyJvmtiMountBegin(firstMount);
        }

        // sets the carrier thread
        Thread carrier = Thread.currentCarrierThread();
        CARRIER_THREAD.setRelease(this, carrier);

        // sync up carrier thread interrupt status if needed
        if (interrupted) {
            carrier.setInterrupt();
        } else if (carrier.isInterrupted()) {
            synchronized (interruptLock) {
                if (!interrupted) {
                    carrier.clearInterrupt();
                }
            }
        }

        // set Thread.currentThread() to return this virtual thread
        carrier.setCurrentThread(this);

        // notify JVMTI agents
        if (notifyJvmti) {
            notifyJvmtiMountEnd(firstMount);
        }
    }

    /**
     * Unmounts this virtual thread from the current carrier thread.
     */
    @ChangesCurrentThread
    private void unmount() {
        //assert this.carrierThread == Thread.currentCarrierThread();

        // notify JVMTI agents
        boolean notifyJvmti = notifyJvmtiEvents;
        if (notifyJvmti) {
            notifyJvmtiUnmountBegin();
        }

        // set Thread.currentThread() to return the carrier thread
        Thread carrier = this.carrierThread;
        carrier.setCurrentThread(carrier);

        // break connection to carrier thread
        synchronized (interruptLock) {   // synchronize with interrupt
            CARRIER_THREAD.setRelease(this, null);
        }
        carrier.clearInterrupt();

        // notify JVMTI agents
        if (notifyJvmti) {
            notifyJvmtiUnmountEnd();
        }
    }

    /**
     * Unmounts this virtual thread, invokes Continuation.yield, and re-mounts the
     * thread when continued.
     */
    @ChangesCurrentThread
    private void yieldContinuation() {
        unmount();
        boolean yielded = false;
        try {
            yielded = Continuation.yield(VTHREAD_SCOPE);
        } finally {
            mount(false);
            if (!yielded) {
                // pinned or resource error
                if (state() == PARKING) {
                    parkOnCarrierThread();
                } else {
                    setState(RUNNING);
                }
            }
            assert (Thread.currentThread() == this) && (state() == RUNNING);
        }
    }

    /**
     * Invoked after the continuation yields. If parking then it sets the state
     * and also re-submits the task to continue if unparked while parking.
     * If yielding due to Thread.yield then it just submits the task to continue.
     */
    private void afterYield() {
        int s = state();
        assert (s == PARKING || s == YIELDING) && (carrierThread == null);

        if (s == PARKING) {
            setState(PARKED);
            // may have been unparked while parking
            if (parkPermit && compareAndSetState(PARKED, RUNNABLE)) {
                submitRunContinuation();
            }
        } else if (s == YIELDING) {   // Thread.yield
            setState(RUNNABLE);
            // submit to random queue periodically
            int r = ThreadLocalRandom.current().nextInt(8);
            submitRunContinuation(r != 0);   // 1 in 8
        }
    }

    /**
     * Invoked when the task completes (or start failed). This method sets
     * the state to TERMINATED and notifies anyone waiting for the thread
     * to terminate.
     *
     * @param executed true if the thread executed, false if it failed to start
     */
    private void afterTerminate(boolean executed) {
        assert (state() == STARTED || state() == RUNNING) && (carrierThread == null);
        setState(TERMINATED);   // final state

        // notify anyone waiting for this virtual thread to terminate
        ReentrantLock lock = this.lock;
        if (lock != null) {
            lock.lock();
            try {
                getCondition().signalAll();
            } finally {
                lock.unlock();
            }
        }

        // notify JVMTI agents
        if (executed && notifyJvmtiEvents) {
            notifyJvmtiTerminated();
        }

        // notify thread dumper, no-op if not tracking threads
        ThreadDumper.notifyTerminate(this);

        // clear references to thread locals, this method is assumed to be
        // called on its carrier thread on which it terminated.
        if (executed) {
            clearReferences();
        }
    }

    /**
     * Parks on the carrier thread until it is signalled or interrupted.
     */
    @ChangesCurrentThread
    private void parkOnCarrierThread() {
        assert state() == PARKING;

        boolean awaitInterrupted = false;

        // switch to carrier thread
        Thread carrier = this.carrierThread;
        carrier.setCurrentThread(carrier);

        final ReentrantLock lock = getLock();
        lock.lock();
        try {
            setState(PINNED);

            if (!parkPermit) {
                // wait to be signalled or interrupted
                getCondition().await();
            }
        } catch (InterruptedException e) {
            awaitInterrupted = true;
        } finally {
            lock.unlock();

            // continue running on the carrier thread
            assert state() == PINNED;
            setState(RUNNING);

            // consume parking permit
            setParkPermit(false);

            // switch back to virtual thread
            carrier.setCurrentThread(this);
        }

        // restore interrupt status
        if (awaitInterrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Schedules this {@code VirtualThread} to execute.
     *
     * @throws IllegalThreadStateException if the thread has already been started
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    @Override
    public void start() {
        if (!compareAndSetState(NEW, STARTED)) {
            throw new IllegalThreadStateException("Already started");
        }
        ThreadDumper.notifyStart(this);  // no-op if threads not tracked
        try {
            submitRunContinuation();
        } catch (RejectedExecutionException ree) {
            // assume executor has been shutdown
            afterTerminate(/*executed*/ false);
            throw ree;
        }
    }

    /**
     * Disables the current virtual thread for scheduling purposes.
     *
     * <p> If this virtual thread has already been {@link #unpark() unparked} then the
     * parking permit is consumed and this method completes immediately;
     * otherwise the current virtual thread is disabled for scheduling purposes and lies
     * dormant until it is {@linkplain #unpark() unparked} or the thread is
     * {@link Thread#interrupt() interrupted}.
     *
     * @throws IllegalCallerException if not called from a virtual thread
     */
    static void park() {
        Thread thread = Thread.currentThread();
        if (!thread.isVirtual()) {
            throw new IllegalCallerException("Not a virtual thread");
        }
        ((VirtualThread) thread).tryPark();
    }

    /**
     * Disables the current virtual thread for scheduling purposes for up to the
     * given waiting time.
     *
     * <p> If this virtual thread has already been {@link #unpark() unparked} then the
     * parking permit is consumed and this method completes immediately;
     * otherwise if the time to wait is greater than zero then the current virtual thread
     * is disabled for scheduling purposes and lies dormant until it is {@link
     * #unpark unparked}, the waiting time elapses or the thread is
     * {@linkplain Thread#interrupt() interrupted}.
     *
     * @param nanos the maximum number of nanoseconds to wait.
     *
     * @throws IllegalCallerException if not called from a virtual thread
     */
    static void parkNanos(long nanos) {
        Thread thread = Thread.currentThread();
        if (!thread.isVirtual()) {
            throw new IllegalCallerException("Not a virtual thread");
        }
        ((VirtualThread) thread).tryPark(nanos);
    }

    /**
     * Try to park. If already been unparked (parking permit available) or the
     * interrupt status is set then this method completes immediately without
     * yielding.
     */
    private void tryPark() {
        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || interrupted)
            return;

        // park the thread
        setState(PARKING);
        try {
            yieldContinuation();
        } finally {
            assert (Thread.currentThread() == this) && (state() == RUNNING);
        }
    }

    /**
     * Try to park for up to the given waiting time. If already been unparked
     * (parking permit available) or the interrupt status is set then this method
     * completes immediately without yielding.
     */
    private void tryPark(long nanos) {
        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || interrupted)
            return;

        // park the thread for the waiting time
        if (nanos > 0) {
            Future<?> unparker = scheduleUnpark(nanos);
            setState(PARKING);
            try {
                yieldContinuation();
            } finally {
                assert (Thread.currentThread() == this) && (state() == RUNNING);
                cancel(unparker);
            }
        } else {
            // consume permit when not parking
            tryYield();
            setParkPermit(false);
        }
    }

    /**
     * Schedules this thread to be unparked after the given delay.
     */
    @ChangesCurrentThread
    private Future<?> scheduleUnpark(long nanos) {
        //assert Thread.currentThread() == this;
        Thread carrier = this.carrierThread;
        // need to switch to carrier thread to avoid nested parking
        carrier.setCurrentThread(carrier);
        try {
            return UNPARKER.schedule(this::unpark, nanos, NANOSECONDS);
        } finally {
            carrier.setCurrentThread(this);
        }
    }

    /**
     * Cancels a task if it has not completed.
     */
    @ChangesCurrentThread
    private void cancel(Future<?> future) {
        //assert Thread.currentThread() == this;
        if (!future.isDone()) {
            Thread carrier = this.carrierThread;
            // need to switch to carrier thread to avoid nested parking
            carrier.setCurrentThread(carrier);
            try {
                future.cancel(false);
            } finally {
                carrier.setCurrentThread(this);
            }
        }
    }

    /**
     * Re-enables this virtual thread for scheduling. If the virtual thread was
     * {@link #park() parked} then it will be unblocked, otherwise its next call
     * to {@code park} or {@linkplain #parkNanos(long) parkNanos} is guaranteed
     * not to block.
     *
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    void unpark() {
        if (!getAndSetParkPermit(true) && Thread.currentThread() != this) {
            int s = state();
            if (s == PARKED && compareAndSetState(PARKED, RUNNABLE)) {
                submitRunContinuation();
            } else if (s == PINNED) {
                // signal pinned thread so that it continues
                final ReentrantLock lock = getLock();
                lock.lock();
                try {
                    if (state() == PINNED) {
                        getCondition().signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Attempts to yield, as in Thread.yield.
     */
    void tryYield() {
        assert Thread.currentThread() == this && state() == RUNNING;
        setState(YIELDING);
        try {
            yieldContinuation();
        } finally {
            assert Thread.currentThread() == this && state() == RUNNING;
        }
    }

    /**
     * Waits up to {@code nanos} nanoseconds for this virtual thread to terminate.
     * A timeout of {@code 0} means to wait forever.
     *
     * @throws InterruptedException if interrupted while waiting
     * @return true if the thread has terminated
     */
    boolean joinNanos(long nanos) throws InterruptedException {
        final ReentrantLock lock = getLock();
        lock.lock();
        try {
            final Condition condition = getCondition();
            if (nanos == 0) {
                while (state() != TERMINATED) {
                    condition.await();
                }
                return true;
            } else {
                long remainingNanos = nanos;
                while (remainingNanos > 0 && state() != TERMINATED) {
                    remainingNanos = condition.awaitNanos(remainingNanos);
                }
                return (state() == TERMINATED);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void interrupt() {
        if (Thread.currentThread() != this) {
            checkAccess();
            synchronized (interruptLock) {
                interrupted = true;
                Interruptible b = nioBlocker;
                if (b != null) {
                    b.interrupt(this);
                }

                // interrupt carrier thread
                Thread carrier = carrierThread;
                if (carrier != null) carrier.setInterrupt();
            }
        } else {
            interrupted = true;
            carrierThread.setInterrupt();
        }
        unpark();
    }

    @Override
    public boolean isInterrupted() {
        return interrupted;
    }

    @Override
    boolean getAndClearInterrupt() {
        assert Thread.currentThread() == this;
        synchronized (interruptLock) {
            boolean oldValue = interrupted;
            if (oldValue)
                interrupted = false;
            carrierThread.clearInterrupt();
            return oldValue;
        }
    }

    /**
     * Sleep the current thread for the given sleep time (in nanoseconds)
     *
     * @implNote This implementation parks the thread for the given sleeping time
     * and will therefore be observed in PARKED state during the sleep. Parking
     * will consume the parking permit so this method makes available the parking
     * permit after the sleep. This will observed as spurious, but benign, wakeup
     * when the thread subsequently attempts to park.
     *
     * @throws InterruptedException if interrupted while sleeping
     */
    void sleepNanos(long nanos) throws InterruptedException {
        assert Thread.currentThread() == this;
        if (nanos >= 0) {
            if (getAndClearInterrupt())
                throw new InterruptedException();
            if (nanos == 0) {
                tryYield();
            } else {
                // park for the sleep time
                try {
                    long remainingNanos = nanos;
                    long startNanos = System.nanoTime();
                    while (remainingNanos > 0) {
                        parkNanos(remainingNanos);
                        if (getAndClearInterrupt()) {
                            throw new InterruptedException();
                        }
                        remainingNanos = nanos - (System.nanoTime() - startNanos);
                    }
                } finally {
                    // may have been unparked while sleeping
                    setParkPermit(true);
                }
            }
        }
    }

    @Override
    public Thread.State getState() {
        switch (state()) {
            case NEW:
                return Thread.State.NEW;
            case STARTED:
            case RUNNABLE:
                // runnable, not mounted
                return Thread.State.RUNNABLE;
            case RUNNING:
                // if mounted then return state of carrier thread
                synchronized (interruptLock) {
                    Thread carrierThread = this.carrierThread;
                    if (carrierThread != null) {
                        return carrierThread.threadState();
                    }
                }
                // runnable, mounted
                return Thread.State.RUNNABLE;
            case PARKING:
            case YIELDING:
                // runnable, mounted, not yet waiting
                return Thread.State.RUNNABLE;
            case PARKED:
            case PARKED_SUSPENDED:
            case PINNED:
                return Thread.State.WAITING;
            case TERMINATED:
                return Thread.State.TERMINATED;
            default:
                throw new InternalError();
        }
    }

    @Override
    StackTraceElement[] asyncGetStackTrace() {
        StackTraceElement[] stackTrace;
        do {
            stackTrace = (carrierThread != null)
                    ? super.asyncGetStackTrace()  // mounted
                    : tryGetStackTrace();         // unmounted
            if (stackTrace == null) {
                Thread.yield();
            }
        } while (stackTrace == null);
        return stackTrace;
    }

    /**
     * Returns the stack trace for this virtual thread if it newly created,
     * parked, or terminated. Returns null if the thread is in another state.
     */
    private StackTraceElement[] tryGetStackTrace() {
        if (compareAndSetState(PARKED, PARKED_SUSPENDED)) {
            try {
                return cont.getStackTrace();
            } finally {
                assert state() == PARKED_SUSPENDED;
                setState(PARKED);

                // may have been unparked while suspended
                if (parkPermit && compareAndSetState(PARKED, RUNNABLE)) {
                    try {
                        submitRunContinuation();
                    } catch (RejectedExecutionException ignore) { }
                }
            }
        } else {
            int s = state();
            if (s == NEW || s == TERMINATED) {
                return new StackTraceElement[0];   // empty stack
            } else {
                return null;
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VirtualThread[");
        String name = getName();
        if (name.isEmpty() || name.equals("<unnamed>")) {
            sb.append("#");
            sb.append(getId());
        } else {
            sb.append(name);
        }
        sb.append(",");
        Thread carrier = carrierThread;
        if (carrier != null) {
            sb.append(carrier.getName());
            ThreadGroup g = carrier.getThreadGroup();
            if (g != null) {
                sb.append(",");
                sb.append(g.getName());
            }
        } else {
            if (state() == TERMINATED) {
                sb.append("<terminated>");
            } else {
                sb.append("<no carrier thread>");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return (int) getId();
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    /**
     * Returns the lock object, creating it if needed.
     */
    private ReentrantLock getLock() {
        ReentrantLock lock = this.lock;
        if (lock == null) {
            lock = new ReentrantLock();
            if (!LOCK.compareAndSet(this, null, lock)) {
                lock = this.lock;
            }
        }
        return lock;
    }

    /**
     * Returns the condition object for signalling, creating it if needed.
     */
    private Condition getCondition() {
        assert getLock().isHeldByCurrentThread();
        Condition condition = this.condition;
        if (condition == null) {
            this.condition = condition = lock.newCondition();
        }
        return condition;
    }

    // -- wrappers for VarHandle methods --

    private int state() {
        return state;  // volatile read
    }

    private void setState(int newValue) {
        state = newValue;  // volatile write
    }

    private void setReleaseState(int newValue) {
        STATE.setRelease(this, newValue);
    }

    private boolean compareAndSetState(int expectedValue, int newValue) {
        return STATE.compareAndSet(this, expectedValue, newValue);
    }

    private void setParkPermit(boolean newValue) {
        if (parkPermit != newValue) {
            parkPermit = newValue;
        }
    }

    private boolean getAndSetParkPermit(boolean newValue) {
        if (parkPermit != newValue) {
            return (boolean) PARK_PERMIT.getAndSet(this, newValue);
        } else {
            return newValue;
        }
    }

    // -- JVM TI support --

    private static volatile boolean notifyJvmtiEvents;  // set by VM
    private static native void notifyMountBegin0(Thread carrierThread, VirtualThread vthread, boolean firstMount);
    private static native void notifyMountEnd0(Thread carrierThread, VirtualThread vthread, boolean firstMount);
    private static native void notifyUnmountBegin0(Thread carrierThread, VirtualThread vthread);
    private static native void notifyUnmountEnd0(Thread carrierThread, VirtualThread vthread);
    private static native void notifyTerminated0(Thread carrierThread, VirtualThread vthread);
    private static native void registerNatives();
    static {
        registerNatives();
    }

    private void notifyJvmtiMountBegin(boolean firstMount) {
        notifyMountBegin0(Thread.currentCarrierThread(), this, firstMount);
    }

    private void notifyJvmtiMountEnd(boolean firstMount) {
        notifyMountEnd0(Thread.currentCarrierThread(), this, firstMount);
    }

    private void notifyJvmtiUnmountBegin() {
        notifyUnmountBegin0(Thread.currentCarrierThread(), this);
    }

    private void notifyJvmtiUnmountEnd() {
        notifyUnmountEnd0(Thread.currentCarrierThread(), this);
    }

    private void notifyJvmtiTerminated() {
        notifyTerminated0(Thread.currentCarrierThread(), this);
    }

    /**
     * Creates the default scheduler.
     */
    private static ForkJoinPool createDefaultScheduler() {
        ForkJoinWorkerThreadFactory factory = pool -> {
            PrivilegedAction<ForkJoinWorkerThread> pa = () -> new CarrierThread(pool);
            return AccessController.doPrivileged(pa);
        };
        PrivilegedAction<ForkJoinPool> pa = () -> {
            int parallelism, maxPoolSize;
            String parallelismValue = System.getProperty("jdk.defaultScheduler.parallelism");
            String maxPoolSizeValue = System.getProperty("jdk.defaultScheduler.maxPoolSize");
            if (parallelismValue != null) {
                parallelism = Integer.parseInt(parallelismValue);
            } else {
                parallelism = Runtime.getRuntime().availableProcessors();
            }
            if (maxPoolSizeValue != null) {
                maxPoolSize = Integer.parseInt(maxPoolSizeValue);
                parallelism = Integer.min(parallelism, maxPoolSize);
            } else {
                maxPoolSize = Integer.max(parallelism, 256);
            }
            Thread.UncaughtExceptionHandler handler = (t, e) -> { };
            boolean asyncMode = true; // FIFO
            return new ForkJoinPool(parallelism, factory, handler, asyncMode,
                         0, maxPoolSize, 1, pool -> true, 30, SECONDS);
        };
        return AccessController.doPrivileged(pa);
    }

    /**
     * Defines static methods to invoke non-public ForkJoinPool methods.
     */
    private static class ForkJoinPools {
        static final MethodHandle externalExecuteTask;
        static {
            try {
                PrivilegedExceptionAction<MethodHandles.Lookup> pa = () ->
                    MethodHandles.privateLookupIn(ForkJoinPool.class, MethodHandles.lookup());
                MethodHandles.Lookup l = AccessController.doPrivileged(pa);
                MethodType methodType = MethodType.methodType(void.class, Runnable.class);
                externalExecuteTask = l.findVirtual(ForkJoinPool.class, "externalExecuteTask", methodType);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        /**
         * Invokes the non-public ForkJoinPool.externalExecuteTask method to
         * submit the task to the current carrier thread's work queue.
         */
        static void externalExecuteTask(ForkJoinPool pool, Runnable task) {
            try {
                ForkJoinPools.externalExecuteTask.invoke(pool, task);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                throw new InternalError(e);
            }
        }
    }

    /**
     * A thread in the ForkJoinPool created by the default scheduler.
     */
    private static class CarrierThread extends ForkJoinWorkerThread {
        private static final ThreadGroup CARRIER_THREADGROUP = carrierThreadGroup();
        private static final AccessControlContext INNOCUOUS_ACC = innocuousACC();

        private static final Unsafe UNSAFE;
        private static final long CONTEXTCLASSLOADER;
        private static final long INHERITABLETHREADLOCALS;
        private static final long INHERITEDACCESSCONTROLCONTEXT;

        CarrierThread(ForkJoinPool pool) {
            super(CARRIER_THREADGROUP, pool);
            UNSAFE.putReference(this, CONTEXTCLASSLOADER, ClassLoader.getSystemClassLoader());
            UNSAFE.putReference(this, INHERITABLETHREADLOCALS, null);
            UNSAFE.putReferenceRelease(this, INHERITEDACCESSCONTROLCONTEXT, INNOCUOUS_ACC);
        }

        @Override
        public void setUncaughtExceptionHandler(UncaughtExceptionHandler ueh) { }

        @Override
        public void setContextClassLoader(ClassLoader cl) {
            throw new SecurityException("setContextClassLoader");
        }

        /**
         * The thread group for the carrier threads.
         */
        private static final ThreadGroup carrierThreadGroup() {
            return AccessController.doPrivileged(new PrivilegedAction<ThreadGroup>() {
                public ThreadGroup run() {
                    ThreadGroup group = Thread.currentCarrierThread().getThreadGroup();
                    for (ThreadGroup p; (p = group.getParent()) != null; )
                        group = p;
                    return new ThreadGroup(group, "CarrierThreads");
                }
            });
        }

        /**
         * Return an AccessControlContext that doesn't support any permissions.
         */
        private static AccessControlContext innocuousACC() {
            return new AccessControlContext(new ProtectionDomain[] {
                    new ProtectionDomain(null, null)
            });
        }

        static {
            UNSAFE = Unsafe.getUnsafe();
            CONTEXTCLASSLOADER = UNSAFE.objectFieldOffset(Thread.class,
                    "contextClassLoader");
            INHERITABLETHREADLOCALS = UNSAFE.objectFieldOffset(Thread.class,
                    "inheritableThreadLocals");
            INHERITEDACCESSCONTROLCONTEXT = UNSAFE.objectFieldOffset(Thread.class,
                    "inheritedAccessControlContext");
        }
    }

    /**
     * Creates the ScheduledThreadPoolExecutor used for timed unpark.
     */
    private static ScheduledExecutorService createDelayedTaskScheduler() {
        int poolSize = Math.max(Runtime.getRuntime().availableProcessors()/4, 1);
        ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
            Executors.newScheduledThreadPool(poolSize, task -> {
                var thread = InnocuousThread.newThread("VirtualThread-unparker", task);
                thread.setDaemon(true);
                return thread;
            });
        stpe.setRemoveOnCancelPolicy(true);
        return stpe;
    }

    /**
     * Reads the value of the jdk.tracePinnedThreads property to determine if stack
     * traces should be printed when a carrier thread is pinned when a virtual thread
     * attempts to park.
     */
    private static int tracePinningMode() {
        String propValue = GetPropertyAction.privilegedGetProperty("jdk.tracePinnedThreads");
        if (propValue != null) {
            if (propValue.length() == 0 || "full".equalsIgnoreCase(propValue))
                return 1;
            if ("short".equalsIgnoreCase(propValue))
                return 2;
        }
        return 0;
    }
}

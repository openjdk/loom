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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import jdk.internal.event.VirtualThreadEndEvent;
import jdk.internal.event.VirtualThreadPinnedEvent;
import jdk.internal.event.VirtualThreadStartEvent;
import jdk.internal.event.VirtualThreadSubmitFailedEvent;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import jdk.internal.vm.ThreadContainer;
import jdk.internal.vm.ThreadContainers;
import jdk.internal.vm.annotation.ChangesCurrentThread;
import jdk.internal.vm.annotation.JvmtiMountTransition;
import sun.nio.ch.Interruptible;
import sun.security.action.GetPropertyAction;
import static java.util.concurrent.TimeUnit.*;

/**
 * A thread that is scheduled by the Java virtual machine rather than the operating
 * system.
 */
class VirtualThread extends Thread {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final ContinuationScope VTHREAD_SCOPE = new ContinuationScope("VirtualThreads");
    private static final ForkJoinPool DEFAULT_SCHEDULER = createDefaultScheduler();
    private static final ScheduledExecutorService UNPARKER = createDelayedTaskScheduler();
    private static final int TRACE_PINNING_MODE = tracePinningMode();

    private static final VarHandle STATE;
    private static final VarHandle PARK_PERMIT;
    private static final VarHandle CARRIER_THREAD;
    private static final VarHandle TERMINATION;
    private static final VarHandle NEXT_UNPARKER_ID;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(VirtualThread.class, "state", int.class);
            PARK_PERMIT = l.findVarHandle(VirtualThread.class, "parkPermit", boolean.class);
            CARRIER_THREAD = l.findVarHandle(VirtualThread.class, "carrierThread", Thread.class);
            TERMINATION = l.findVarHandle(VirtualThread.class, "termination", CountDownLatch.class);
            NEXT_UNPARKER_ID = l.findStaticVarHandle(VirtualThread.class, "nextUnparkerId", int.class);
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

    // termination object when joining, created lazily if needed
    private volatile CountDownLatch termination;

    /**
     * Returns the continuation scope used for virtual threads.
     */
    static ContinuationScope continuationScope() {
        return VTHREAD_SCOPE;
    }

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
        this.runContinuation = this::runContinuation;
    }

    /**
     * The continuation that a virtual thread executes.
     */
    private static class VThreadContinuation extends Continuation {
        VThreadContinuation(VirtualThread vthread, Runnable task) {
            super(VTHREAD_SCOPE, () -> vthread.run(task));
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
     * Runs or continues execution of the continuation on the current thread.
     */
    private void runContinuation() {
        // the carrier thread should be a platform thread
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalCallerException();
        }

        // set state to RUNNING
        boolean firstRun;
        int initialState = state();
        if (initialState == STARTED && compareAndSetState(STARTED, RUNNING)) {
            // first run
            firstRun = true;
        } else if (initialState == RUNNABLE && compareAndSetState(RUNNABLE, RUNNING)) {
            // consume parking permit
            setParkPermit(false);
            firstRun = false;
        } else {
            throw new IllegalStateException();
        }

        // notify JVMTI before mount
        if (notifyJvmtiEvents) notifyJvmtiMountBegin(firstRun);

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
     * Submits the runContinuation task to the scheduler.
     * In the case of the default scheduler, and the current carrier thread is one
     * of FJP worker threads, then the task is queued to the current thread's queue.
     * In that case, the parameter {@code signalOnEmpty} indicates if workers are
     * signalled when its queue is empty.
     * @throws RejectedExecutionException
     */
    private void submitRunContinuationAndSignal(boolean signalOnEmpty) {
        try {
            if (scheduler == DEFAULT_SCHEDULER) {
                ForkJoinPools.externalExecuteTask(DEFAULT_SCHEDULER,runContinuation, signalOnEmpty);
            } else {
                scheduler.execute(runContinuation);
            }
        } catch (RejectedExecutionException ree) {
            // record event
            var event = new VirtualThreadSubmitFailedEvent();
            if (event.isEnabled()) {
                event.javaThreadId = getId();
                event.exceptionMessage = ree.getMessage();
                event.commit();
            }
            throw ree;
        }
    }

    /**
     * Submits the runContinuation task to the scheduler.
     * @throws RejectedExecutionException
     */
    private void submitRunContinuation() {
        submitRunContinuationAndSignal(true);
    }

    /**
     * Runs a task in the context of this virtual thread. The virtual thread is
     * mounted on the current (carrier) thread before the task runs. It unmounts
     * from its carrier thread when the task completes.
     *
     * When enabled, JVMTI must be notified from this method.
     */
    @ChangesCurrentThread
    private void run(Runnable task) {
        assert state == RUNNING;
        boolean notifyJvmti = notifyJvmtiEvents;

        // first mount
        mount();
        if (notifyJvmti) notifyJvmtiMountEnd(true);

        try {
            task.run();
        } catch (Throwable exc) {
            dispatchUncaughtException(exc);
        } finally {

            // last unmount
            if (notifyJvmti) notifyJvmtiUnmountBegin(true);
            unmount();
            if (notifyJvmti) notifyJvmtiUnmountEnd(true);

            // final state
            setState(TERMINATED);
        }
    }

    /**
     * Mounts this virtual thread onto the current carrier thread.
     */
    @ChangesCurrentThread
    private void mount() {
        //assert this.carrierThread == null;

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
    }

    /**
     * Unmounts this virtual thread from the current carrier thread.
     */
    @ChangesCurrentThread
    private void unmount() {
        //assert this.carrierThread == Thread.currentCarrierThread();

        // set Thread.currentThread() to return the carrier thread
        Thread carrier = this.carrierThread;
        carrier.setCurrentThread(carrier);

        // break connection to carrier thread
        synchronized (interruptLock) {   // synchronize with interrupt
            CARRIER_THREAD.setRelease(this, null);
        }
        carrier.clearInterrupt();
    }

    /**
     * Unmounts this virtual thread, invokes Continuation.yield, and re-mounts the
     * thread when continued. When enabled, JVMTI must be notified from this method.
     * @return true if the yield was successful
     */
    @ChangesCurrentThread
    private boolean yieldContinuation() {
        boolean notifyJvmti = notifyJvmtiEvents;

        // unmount
        if (notifyJvmti) notifyJvmtiUnmountBegin(false);
        unmount();
        try {
            return Continuation.yield(VTHREAD_SCOPE);
        } finally {
            // re-mount
            mount();
            if (notifyJvmti) notifyJvmtiMountEnd(false);
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

            // notify JVMTI that unmount has completed, thread is parked
            if (notifyJvmtiEvents) notifyJvmtiUnmountEnd(false);

            // may have been unparked while parking
            if (parkPermit && compareAndSetState(PARKED, RUNNABLE)) {
                // re-submit to continue on this carrier thread if possible
                submitRunContinuationAndSignal(false);
            }
        } else if (s == YIELDING) {   // Thread.yield
            setState(RUNNABLE);

            // notify JVMTI that unmount has completed, thread is runnable
            if (notifyJvmtiEvents) notifyJvmtiUnmountEnd(false);

            // re-submit to continue on this carrier thread if possible
            submitRunContinuationAndSignal(false);
        }
    }

    /**
     * Invoked after the thread terminates (or start failed). This method
     * notifies anyone waiting for the thread to terminate.
     *
     * @param executed true if the thread executed, false if it failed to start
     */
    private void afterTerminate(boolean executed) {
        assert (state() == TERMINATED) && (carrierThread == null);

        // notify anyone waiting for this virtual thread to terminate
        CountDownLatch termination = this.termination;
        if (termination != null) {
            assert termination.getCount() == 1;
            termination.countDown();
        }

        if (VirtualThreadEndEvent.isTurnedOn()) {
            var event = new VirtualThreadEndEvent();
            event.javaThreadId = getId();
            event.commit();
        }

        if (executed) {
            // notify container if thread executed
            threadContainer().onExit(this);

            // clear references to thread locals
            clearReferences();
        }
    }

    /**
     * Parks on the carrier thread up to the given waiting time or until unparked
     * or interrupted. If the virtual thread is interrupted then the interrupt
     * status will be propagated to the carrier thread so it will wakeup.
     * @param timed true for a timed park, false for untimed
     * @param nanos the waiting time in nanoseconds
     */
    private void parkOnCarrierThread(boolean timed, long nanos) {
        assert state() == PARKING;

        var pinnedEvent = new VirtualThreadPinnedEvent();
        pinnedEvent.begin();

        setState(PINNED);
        try {
            if (!parkPermit) {
                if (!timed) {
                    U.park(false, 0);
                } else if (nanos > 0) {
                    U.park(false, nanos);
                }
            }
        } finally {
            setState(RUNNING);
        }

        // consume parking permit
        setParkPermit(false);

        // commit event if enabled
        if (pinnedEvent.isEnabled()) {
            pinnedEvent.commit();
        }
    }

    /**
     * Schedules this {@code VirtualThread} to execute.
     *
     * @throws IllegalStateException if the container is shutdown or closed
     * @throws IllegalThreadStateException if the thread has already been started
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    @Override
    void start(ThreadContainer container) {
        if (!compareAndSetState(NEW, STARTED)) {
            throw new IllegalThreadStateException("Already started");
        }

        boolean started = false;
        container.onStart(this); // may throw
        try {
            // inherit scope locals from structured container
            Object bindings = container.scopeLocalBindings();
            if (bindings != null) {
                if (Thread.currentThread().scopeLocalBindings != bindings) {
                    throw new IllegalStateException("Scope local bindings have changed");
                }
                this.scopeLocalBindings = (ScopeLocal.Snapshot) bindings;
            }

            // bind thread to container
            setThreadContainer(container);
            if (VirtualThreadStartEvent.isTurnedOn()) {
                var event = new VirtualThreadStartEvent();
                event.javaThreadId = getId();
                event.commit();
            }

            // submit task to run thread
            submitRunContinuation();
            started = true;
        } finally {
            if (!started) {
                setState(TERMINATED);
                container.onExit(this);
                afterTerminate(/*executed*/ false);
            }
        }
    }

    @Override
    public void start() {
        start(ThreadContainers.root());
    }

    /**
     * Parks the current virtual thread until it is unparked or interrupted.
     * If already unparked then the parking permit is consumed and this method
     * completes immediately (meaning it doesn't yield). It also completes
     * immediately if the interrupt status is set.
     */
    void park() {
        assert Thread.currentThread() == this;

        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || interrupted)
            return;

        // park the thread
        setState(PARKING);
        try {
            if (!yieldContinuation()) {
                // park on the carrier thread when pinned
                parkOnCarrierThread(false, 0);
            }
        } finally {
            assert (Thread.currentThread() == this) && (state() == RUNNING);
        }
    }

    /**
     * Parks the current virtual thread up to the given waiting time or until it
     * is unparked or interrupted. If already unparked then the parking permit is
     * consumed and this method completes immediately (meaning it doesn't yield).
     * It also completes immediately if the interrupt status is set or the waiting
     * time is {@code <= 0}.
     *
     * @param nanos the maximum number of nanoseconds to wait.
     */
    void parkNanos(long nanos) {
        assert Thread.currentThread() == this;

        // complete immediately if parking permit available or interrupted
        if (getAndSetParkPermit(false) || interrupted)
            return;

        // park the thread for the waiting time
        if (nanos > 0) {
           long startTime = System.nanoTime();

            boolean yielded;
            Future<?> unparker = scheduleUnpark(nanos);
            setState(PARKING);
            try {
               yielded = yieldContinuation();
            } finally {
                assert (Thread.currentThread() == this)
                        && (state() == RUNNING || state() == PARKING);
                cancel(unparker);
            }

            // park on the carrier thread for remaining time when pinned
            if (!yielded) {
                long deadline = startTime + nanos;
                if (deadline < 0L)
                    deadline = Long.MAX_VALUE;
                parkOnCarrierThread(true, deadline - System.nanoTime());
            }
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
            return UNPARKER.schedule(() -> unpark(), nanos, NANOSECONDS);
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
     * @throws RejectedExecutionException if the scheduler cannot accept a task
     */
    @ChangesCurrentThread
    void unpark() {
        Thread currentThread = Thread.currentThread();
        if (!getAndSetParkPermit(true) && currentThread != this) {
            int s = state();
            if (s == PARKED && compareAndSetState(PARKED, RUNNABLE)) {
                if (currentThread instanceof VirtualThread vthread) {
                    Thread carrier = vthread.carrierThread;
                    carrier.setCurrentThread(carrier);
                    try {
                        submitRunContinuation();
                    } finally {
                        carrier.setCurrentThread(vthread);
                    }
                } else {
                    submitRunContinuation();
                }
            } else if (s == PINNED) {
                // unpark carrier thread when pinned.
                synchronized (interruptLock) {
                    Thread carrier = carrierThread;
                    if (carrier != null && state() == PINNED) {
                        U.unpark(carrier);
                    }
                }
            }
        }
    }

    /**
     * Attempts to yield (Thread.yield).
     */
    void tryYield() {
        assert Thread.currentThread() == this;
        setState(YIELDING);
        try {
            yieldContinuation();
        } finally {
            assert Thread.currentThread() == this;
            if (state() != RUNNING) {
                assert state() == YIELDING;
                setState(RUNNING);
            }
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
        if (state() == TERMINATED)
            return true;

        // ensure termination object exists, then re-check state
        CountDownLatch termination = getTermination();
        if (state() == TERMINATED)
            return true;

        // wait for virtual thread to terminate
        if (nanos == 0) {
            termination.await();
        } else {
            boolean terminated = termination.await(nanos, NANOSECONDS);
            if (!terminated) {
                // waiting time elapsed
                return false;
            }
        }
        assert state() == TERMINATED;
        return true;
    }

    @Override
    @SuppressWarnings("removal")
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
     * permit after the sleep. This will observed as a spurious, but benign, wakeup
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
     * started, parked, or terminated. Returns null if the thread is in
     * another state.
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
            if (s == NEW || s == STARTED || s == TERMINATED) {
                return new StackTraceElement[0];   // empty stack
            } else {
                return null;
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("VirtualThread[#");
        sb.append(getId());
        String name = getName();
        if (!name.isEmpty() && !name.equals("<unnamed>")) {
            sb.append(",");
            sb.append(name);
        }
        sb.append("]/");
        Thread carrier = carrierThread;
        if (carrier != null) {
            // include the carrier thread state and name when mounted
            synchronized (interruptLock) {
                carrier = carrierThread;
                if (carrier != null) {
                    String stateAsString = carrier.threadState().toString();
                    sb.append(stateAsString.toLowerCase(Locale.ROOT));
                    sb.append('@');
                    sb.append(carrier.getName());
                }
            }
        }
        // include virtual thread state when not mounted
        if (carrier == null) {
            String stateAsString = getState().toString();
            sb.append(stateAsString.toLowerCase(Locale.ROOT));
        }
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
     * Returns the termination object, creating it if needed.
     */
    private CountDownLatch getTermination() {
        CountDownLatch termination = this.termination;
        if (termination == null) {
            termination = new CountDownLatch(1);
            if (!TERMINATION.compareAndSet(this, null, termination)) {
                termination = this.termination;
            }
        }
        return termination;
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

    @JvmtiMountTransition
    private native void notifyJvmtiMountBegin(boolean firstMount);

    @JvmtiMountTransition
    private native void notifyJvmtiMountEnd(boolean firstMount);

    @JvmtiMountTransition
    private native void notifyJvmtiUnmountBegin(boolean lastUnmount);

    @JvmtiMountTransition
    private native void notifyJvmtiUnmountEnd(boolean lastUnmount);

    private static native void registerNatives();
    static {
        registerNatives();
    }

    /**
     * Creates the default scheduler.
     */
    @SuppressWarnings("removal")
    private static ForkJoinPool createDefaultScheduler() {
        ForkJoinWorkerThreadFactory factory = pool -> {
            PrivilegedAction<ForkJoinWorkerThread> pa = () -> new CarrierThread(pool);
            return AccessController.doPrivileged(pa);
        };
        PrivilegedAction<ForkJoinPool> pa = () -> {
            int parallelism, maxPoolSize, minRunnable;
            String parallelismValue = System.getProperty("jdk.defaultScheduler.parallelism");
            String maxPoolSizeValue = System.getProperty("jdk.defaultScheduler.maxPoolSize");
            String minRunnableValue = System.getProperty("jdk.defaultScheduler.minRunnable");
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
            if (minRunnableValue != null) {
                minRunnable = Integer.parseInt(minRunnableValue);
            } else {
                minRunnable = Integer.max(parallelism / 2, 1);
            }
            Thread.UncaughtExceptionHandler handler = (t, e) -> { };
            boolean asyncMode = true; // FIFO
            return new ForkJoinPool(parallelism, factory, handler, asyncMode,
                         0, maxPoolSize, minRunnable, pool -> true, 30, SECONDS);
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
                @SuppressWarnings("removal")
                MethodHandles.Lookup l = AccessController.doPrivileged(pa);
                MethodType methodType = MethodType.methodType(void.class, Runnable.class, boolean.class);
                externalExecuteTask = l.findVirtual(ForkJoinPool.class, "externalExecuteTask", methodType);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        /**
         * Invokes the non-public ForkJoinPool.externalExecuteTask method to
         * submit the task to the current carrier thread's work queue.
         */
        static void externalExecuteTask(ForkJoinPool pool, Runnable task, boolean signalOnEmpty) {
            try {
                externalExecuteTask.invoke(pool, task, signalOnEmpty);
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
        @SuppressWarnings("removal")
        private static final AccessControlContext INNOCUOUS_ACC = innocuousACC();

        private static final long CONTEXTCLASSLOADER;
        private static final long INHERITABLETHREADLOCALS;
        private static final long INHERITEDACCESSCONTROLCONTEXT;

        CarrierThread(ForkJoinPool pool) {
            super(CARRIER_THREADGROUP, pool);
            U.putReference(this, CONTEXTCLASSLOADER, ClassLoader.getSystemClassLoader());
            U.putReference(this, INHERITABLETHREADLOCALS, null);
            U.putReferenceRelease(this, INHERITEDACCESSCONTROLCONTEXT, INNOCUOUS_ACC);
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
        @SuppressWarnings("removal")
        private static final ThreadGroup carrierThreadGroup() {
            return AccessController.doPrivileged(new PrivilegedAction<ThreadGroup>() {
                public ThreadGroup run() {
                    ThreadGroup group = Thread.currentCarrierThread().getThreadGroup();
                    for (ThreadGroup p; (p = group.getParent()) != null; )
                        group = p;
                    @SuppressWarnings("deprecation")
                    var carrierThreadsGroup = new ThreadGroup(group, "CarrierThreads");
                    return carrierThreadsGroup;
                }
            });
        }

        /**
         * Return an AccessControlContext that doesn't support any permissions.
         */
        @SuppressWarnings("removal")
        private static AccessControlContext innocuousACC() {
            return new AccessControlContext(new ProtectionDomain[] {
                new ProtectionDomain(null, null)
            });
        }

        static {
            CONTEXTCLASSLOADER = U.objectFieldOffset(Thread.class,
                    "contextClassLoader");
            INHERITABLETHREADLOCALS = U.objectFieldOffset(Thread.class,
                    "inheritableThreadLocals");
            INHERITEDACCESSCONTROLCONTEXT = U.objectFieldOffset(Thread.class,
                    "inheritedAccessControlContext");
        }
    }

    /**
     * Creates the ScheduledThreadPoolExecutor used for timed unpark.
     */
    private static ScheduledExecutorService createDelayedTaskScheduler() {
        String propValue = GetPropertyAction.privilegedGetProperty("jdk.unparker.maxPoolSize");
        int poolSize;
        if (propValue != null) {
            poolSize = Integer.parseInt(propValue);
        } else {
            poolSize = 1;
        }
        ScheduledThreadPoolExecutor stpe = (ScheduledThreadPoolExecutor)
            Executors.newScheduledThreadPool(poolSize, task -> {
                String name = "VirtualThread-unparker";
                if (poolSize > 1)
                    name += "-" + nextUnparkerId();
                var thread = InnocuousThread.newThread(name, task);
                return thread;
            });
        stpe.setRemoveOnCancelPolicy(true);
        return stpe;
    }

    private static volatile int nextUnparkerId;
    private static int nextUnparkerId() {
        return (int) NEXT_UNPARKER_ID.getAndAdd(1) + 1;
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

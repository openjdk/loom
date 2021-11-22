/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import jdk.internal.misc.ThreadFlock;
import jdk.internal.javac.PreviewFeature;

/**
 * A basic API for <em>structured concurrency</em>. StructuredExecutor supports cases
 * where a task splits into several concurrent sub-tasks to be executed in their own
 * threads and where the sub-tasks must complete before the main task can continue.
 *
 * <p> <b>StructuredExecutor is work-in-progress.</b>
 *
 * <p> StructuredExecutor defines the {@link #open() open} method to open a new executor,
 * the {@link #fork(Callable) fork} method to start a thread to execute a task, the {@link
 * #join() join} method to wait for all threads to finish, and the {@link #close() close}
 * method to close the executor. The API is intended to be used with the {@code
 * try-with-resources} construct. The intention is that code in the <em>block</em> uses
 * the {@code fork} method to fork threads to execute the sub-tasks, wait for the threads
 * to finish with the {@code join} method, and then <em>process the results</em>.
 * Processing of results may include handling or re-throwing of exceptions.
 * <pre>{@code
 *         try (var executor = StructuredExecutor.open()) {
 *             Future<String> future1 = executor.fork(task1);
 *             Future<String> future2 = executor.fork(task2);
 *
 *             executor.join();
 *
 *             ... process results/exceptions ...
 *
 *         }
 * }</pre>
 * To ensure correct usage, the {@code join} and {@code close} methods may only be invoked
 * by the <em>executor owner</em> (the thread that opened the executor), and the {@code close}
 * method throws an exception after closing if the owner did not invoke the {@code join}
 * method.
 *
 * <p> StructuredExecutor defines the {@link #shutdown() shutdown} method to shut down an
 * executor without closing it. Shutdown is useful for cases where a task completes with
 * a result (or exception) and the results of other unfinished tasks are no longer needed.
 * Invoking {@code shutdown} while the owner is waiting in the {@code join} method will cause
 * the {@code join} to wakeup. It also interrupts all unfinished threads and prevents new
 * threads from starting in the executor.
 *
 * <p> StructuredExecutor defines the 2-arg {@link #fork(Callable, BiConsumer) fork} method
 * for cases where it is useful to execute an operation after a task completes. The operation
 * will typically record results and/or implement a <em>policy</em> on how to handle tasks
 * that fail. When using the 2-arg {@code fork method} then the code that follows the {@code
 * join} method will process results/exceptions with knowledge of the policy.
 * <pre>{@code
 *         try (var executor = StructuredExecutor.open()) {
 *
 *             BiConsumer<StructuredExecutor, Future<String>> onComplete = ...
 *
 *             Future<String> future1 = executor.fork(task1, onComplete);
 *             Future<String> future2 = executor.fork(task2, onComplete);
 *
 *             executor.join();
 *
 *             ... process results/exceptions with knowledge of the onComplete policy
 *
 *         }
 * }</pre>
 *
 * <p> StructuredExecutor defines two operations for common cases. {@link ShutdownOnSuccess
 * ShutdownOnSuccess} is an operation that capture the first result, then shuts down the
 * executor to interrupt unfinished threads and wakeup the owner. This operation can be
 * used for cases where it is pointless to wait for remaining tasks when any task completes
 * with a result. The other operation is {@link ShutdownOnFailure ShutdownOnFailure}
 * to capture the first exception and shut down the executor. This operation is used for
 * cases where it is pointless waiting for remaining tasks when any task fails.
 *
 * <p> The following are two examples that use the built-in operations. In both cases,
 * a pair of tasks are forked to fetch resources from two URL locations "left" and "right".
 * The first example creates a ShutdownOnSuccess object to capture the result of the first
 * task to complete normally, cancelling the other by way of shutting down the executor.
 * The main task waits in {@code join} until either task completes with a result or both
 * tasks fail.
 * <pre>{@code
 *         try (var executor = StructuredExecutor.open()) {
 *             var handler = new ShutdownOnSuccess<String>();
 *
 *             executor.fork(() -> fetch(left), handler);
 *             executor.fork(() -> fetch(right), handler);
 *
 *             executor.join();
 *
 *             String result = handler.result(e -> new WebApplicationException(e));
 *
 *             :
 *         }
 * }</pre>
 * The second example creates a ShutdownOnFailure operation to capture the exception thrown
 * if either task fails, cancelling the other by way of shutting down the executor. The
 * main task waits in {@link #joinUntil(Instant)} until both tasks complete with a result,
 * either fails, or a deadline is reached. It invokes {@link
 * ShutdownOnFailure#throwIfFailed(Function) throwIfFailed(Function)} to throw an exception
 * when either task fails. If uses {@code Future}'s {@link Future#resultNow() resultNow()}
 * method to obtain the results when both task complete successfully.
 *
 * <pre>{@code
 *        Instant deadline = ...
 *
 *        try (var executor = StructuredExecutor.open()) {
 *             var handler = new ShutdownOnFailure();
 *
 *             Future<String> future1 = executor.fork(() -> query(left), handler);
 *             Future<String> future2 = executor.fork(() -> query(right), handler);
 *
 *             executor.joinUntil(deadline);
 *
 *             handler.throwIfFailed(e -> new WebApplicationException(e));
 *
 *             // both tasks completed successfully
 *             String result = Stream.of(future1, future2)
 *                 .map(Future::resultNow)
 *                 .collect(Collectors.join(", ", "{ ", " }"));
 *
 *             :
 *         }
 * }</pre>
 *
 * <p> A StructuredExecutor is conceptually a node in a tree. A thread started in executor
 * "A" may itself open a new executor "B", implicitly forming a tree where executor "A" is
 * the parent of executor "B". When nested, say where thread opens executor "B" and then
 * invokes a method that opens executor "C", then the enclosing executor "B" is conceptually
 * the parent of the nested executor "C". The tree structure supports the inheritance of
 * {@linkplain ScopeLocal scope-local} bindings. It also supports confinement checks.
 * The phrase "threads contained in the executor" in method descriptions means threads in
 * executors in the tree. StructuredExecutor does not define APIs that exposes the tree
 * structure at this time.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * @since 99
 */
@PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
public class StructuredExecutor implements Executor, AutoCloseable {
    private static final VarHandle FUTURES;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            FUTURES = l.findVarHandle(StructuredExecutor.class, "futures", Set.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private final ThreadFactory factory;
    private final ThreadFlock flock;
    private final ReentrantLock shutdownLock = new ReentrantLock();

    // the set of "tracked" Future objects, created lazily
    private volatile Set<Future<?>> futures;

    // set to true when owner calls join
    private boolean joinInvoked;

    // states: OPEN -> SHUTDOWN -> CLOSED
    private static final int OPEN     = 0;
    private static final int SHUTDOWN = 1;
    private static final int CLOSED   = 2;
    private volatile int state;

    StructuredExecutor(String name, ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory);
        this.flock = ThreadFlock.open(name);
    }

    /**
     * Throws IllegalStateException if the current thread is not the owner.
     */
    private void ensureOwner() {
        if (Thread.currentThread() != flock.owner())
            throw new IllegalStateException("Not owner");
    }

    /**
     * Throws IllegalStateException if the current thread is not the owner
     * or a thread contained in the tree.
     */
    private void ensureOwnerOrContainsThread() {
        Thread currentThread = Thread.currentThread();
        if (currentThread != flock.owner() && !flock.containsThread(currentThread))
            throw new IllegalStateException("Current thread not owner or thread in executor");
    }

    /**
     * Tests if the executor is shutdown.
     */
    private boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    /**
     * Track the given Future.
     */
    private void track(Future<?> future) {
        // create the set of Futures if not already created
        Set<Future<?>> futures = this.futures;
        if (futures == null) {
            futures = ConcurrentHashMap.newKeySet();
            if (!FUTURES.compareAndSet(this, null, futures)) {
                // lost the race
                futures = this.futures;
            }
        }
        futures.add(future);
    }

    /**
     * Stop tracking the Future.
     */
    private void untrack(Future<?> future) {
        assert futures != null;
        futures.remove(future);
    }

    /**
     * Opens a new StructuredExecutor that creates threads with given thread factory
     * to run tasks. The executor is owned by the current thread. The executor is
     * optionally named.
     *
     * <p> This method captures the current thread's {@linkplain ScopeLocal scope-local}
     * bindings for inheritance by threads created in the executor.
     *
     * <p> For the purposes of confinement and inheritance of scope-local bindings, the
     * parent of the executor is determined as follows:
     * <ul>
     * <li> If the current thread is the owner of open executors then the most recently
     * created, and open, executor is the parent of the new executor. In other words, the
     * <em>enclosing executor</em> is the parent.
     * <li> If the current thread is not the owner of any open executors then the
     * parent of the new executor is the current thread's executor. If the current thread
     * was not started in an executor then the new executor does not have a parent.
     * </ul>
     *
     * @param name the name of the executor, can be null
     * @param factory the thread factory
     * @return a new StructuredExecutor
     */
    public static StructuredExecutor open(String name, ThreadFactory factory) {
        return new StructuredExecutor(name, factory);
    }

    /**
     * Opens a new StructuredExecutor that creates virtual threads to run tasks.
     *
     * <p> This method is equivalent to invoking {@link #open(String, ThreadFactory)}
     * with the given name and a thread factory that creates virtual threads.
     *
     * @param name the name of the executor
     * @return a new StructuredExecutor
     */
    public static StructuredExecutor open(String name) {
        ThreadFactory factory = Thread.ofVirtual().factory();
        return new StructuredExecutor(Objects.requireNonNull(name), factory);
    }

    /**
     * Opens a new StructuredExecutor that creates virtual threads to run tasks.
     * The executor is unnamed.
     *
     * <p> This method is equivalent to invoking {@link #open(String, ThreadFactory)}
     * with a name of {@code null} and a thread factory that creates virtual threads.
     *
     * @return a new StructuredExecutor
     */
    public static StructuredExecutor open() {
        ThreadFactory factory = Thread.ofVirtual().factory();
        return new StructuredExecutor(null, factory);
    }

    /**
     * Starts a new thread in this executor to run the given task. If onComplete is
     * non-null then it is invoked when the task completes and the executor is not
     * shutdown.
     */
    private <U, V extends U> Future<V> spawn(Callable<V> task,
                                             BiConsumer<StructuredExecutor, Future<U>> onComplete) {
        Objects.requireNonNull(task);

        // create future
        var future = new FutureImpl<U, V>(this, task, onComplete);

        // check state before creating thread
        int s = state;
        if (s >= SHUTDOWN) {
            // the executor is closed, shutdown, or in the process of shutting down
            if (state == CLOSED)
                throw new IllegalStateException("Executor is closed");
            future.cancel(false);
            return future;
        }

        // create thread
        Thread thread = factory.newThread(future);
        if (thread == null)
            throw new RejectedExecutionException();

        // attempt to start the thread
        try {
            flock.start(thread);
        } catch (IllegalStateException e) {
            if (flock.isShutdown()) {
                // the executor is closed, shutdown, or in the process of shutting down
                if (state == CLOSED)
                    throw new IllegalStateException("Executor is closed");
                future.cancel(false);
            } else {
                // scope-locals don't match
                throw e;
            }
        }

        return future;
    }

    /**
     * Starts a new thread in this executor to run the given task.
     *
     * <p> The thread inherits the current thread's {@linkplain ScopeLocal scope-local}
     * bindings and must match the bindings captured when the executor was created.
     *
     * <p> If this executor is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then this method returns a Future representing a {@link
     * Future.State#CANCELLED cancelled} task that was not run.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor.
     *
     * @param task the task to run
     * @param <V> the task return type
     * @return a future
     * @throws IllegalStateException if this executor is closed, the current
     * scope-local bindings are not the same as when the executor was created,
     * or the caller thread is not the owner or a thread contained in the executor
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <V> Future<V> fork(Callable<V> task) {
        return spawn(task, null);
    }

    /**
     * Starts a new thread in this executor to run the given task and an operation to
     * run when the task completes.
     *
     * <p> The thread inherits the current thread's {@linkplain ScopeLocal scope-local}
     * bindings and must match the bindings captured when the executor was created.
     *
     * <p> The {@link BiFunction#apply(Object, Object) apply} method of the {@code
     * onComplete} operation is invoked if the task completes before the executor is
     * {@link #shutdown() shutdown}. If the executor shuts down at or around the same
     * time that the task completes then {@code onComplete} may or may not be invoked.
     * The {@link BiFunction#andThen(Function) andThen} method can be used to compose
     * more than one operation where required. The {@code apply} method is run by the
     * thread when the task completes with a result or exception. If the {@link
     * Future#cancel(boolean) Future.cancel} is used to cancel a task, and before the
     * executor is shutdown, then the {@code apply} method is run by the thread that
     * invokes {@code cancel}.
     *
     * <p> If this executor is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then this method returns a Future representing a {@link
     * Future.State#CANCELLED cancelled} task that was not run.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor.
     *
     * @param task the task to run
     * @param onComplete the operation to run when the task completes
     * @param <V> the task return type
     * @param <U> the return type handled by the operation
     * @return a future
     *
     * @throws IllegalStateException if this executor is closed, the current
     * scope-local bindings are not the same as when the executor was created,
     * or the caller thread is not the owner or a thread contained in the executor
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <U, V extends U> Future<V> fork(Callable<V> task,
                                           BiConsumer<StructuredExecutor, Future<U>> onComplete) {
        return spawn(task, Objects.requireNonNull(onComplete));
    }

    /**
     * Starts a new thread in this executor to run the given task.
     *
     * <p> The thread inherits the current thread's {@linkplain ScopeLocal scope-local}
     * bindings and must match the bindings captured when the executor was created.
     *
     * <p> If this executor is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then the task does not run.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor.
     *
     * @param task the task to run
     * @throws IllegalStateException if this executor is closed, the current
     * scope-local bindings are not the same as when the executor was created,
     * or the caller thread is not the owner or a thread contained in the executor
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    @Override
    public void execute(Runnable task) {
        spawn(Executors.callable(task), null);
    }

    /**
     * Wait for all threads to finish or the executor to shutdown.
     */
    private void implJoin(Duration timeout)
        throws InterruptedException, TimeoutException
    {
        ensureOwner();
        joinInvoked = true;
        int s = state;
        if (s >= SHUTDOWN) {
            if (s == CLOSED)
                throw new IllegalStateException("Executor is closed");
            return;
        }

        // wait for all threads, wakeup, interrupt, or timeout
        if (timeout != null) {
            flock.awaitAll(timeout);
        } else {
            flock.awaitAll();
        }
    }

    /**
     * Wait for all unfinished threads or the executor to shutdown. This method waits
     * until all threads in the executor finish their tasks (including {@linkplain
     * #fork(Callable, BiConsumer) onComplete} operations), the {@link #shutdown()
     * shutdown} method is invoked to shut down the executor, or the current thread is
     * interrupted.
     *
     * <p> This method may only be invoked by the executor owner.
     *
     * @throws IllegalStateException if this executor is closed or the caller thread
     * is not the owner
     * @throws InterruptedException if interrupted while waiting
     */
    public void join() throws InterruptedException {
        try {
            implJoin(null);
        } catch (TimeoutException e) {
            throw new InternalError();
        }
    }

    /**
     * Wait for all unfinished threads or the executor to shutdown, up to the given
     * deadline. This method waits until all threads in the executor finish their
     * tasks (including {@linkplain #fork(Callable, BiConsumer) onComplete} operations),
     * the {@link #shutdown() shutdown} method is invoked to shut down the executor,
     * the current thread is interrupted, or the deadline is reached.
     *
     * <p> This method may only be invoked by the executor owner.
     *
     * @param deadline the deadline
     * @throws IllegalStateException if this executor is closed or the caller thread
     * is not the owner
     * @throws InterruptedException if interrupted while waiting
     * @throws TimeoutException if the deadline is reached while waiting
     */
    public void joinUntil(Instant deadline)
        throws InterruptedException, TimeoutException
    {
        Duration timeout = Duration.between(Instant.now(), deadline);
        implJoin(timeout);
    }

    /**
     * Cancel all tracked Future objects.
     */
    private void cancelTrackedFutures() {
        Set<Future<?>> futures = this.futures;
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
        }
    }

    /**
     * Interrupt all unfinished threads.
     */
    private void implInterruptAll() {
        flock.threads().forEach(t -> {
            if (t != Thread.currentThread()) {
                t.interrupt();
            }
        });
    }

    @SuppressWarnings("removal")
    private void interruptAll() {
        if (System.getSecurityManager() == null) {
            implInterruptAll();
        } else {
            PrivilegedAction<Void> pa = () -> {
                implInterruptAll();
                return null;
            };
            AccessController.doPrivileged(pa);
        }
    }

    /**
     * Shutdown the executor if not already shutdown. Return true if this method
     * shutdowns the executor, false if already shutdown.
     */
    private boolean implShutdown() {
        if (state < SHUTDOWN) {
            shutdownLock.lock();
            try {
                if (state < SHUTDOWN) {

                    // prevent new threads from starting
                    flock.shutdown();

                    // wakeup any threads waiting in Future::get
                    cancelTrackedFutures();

                    // interrupt all unfinished threads
                    interruptAll();

                    state = SHUTDOWN;
                    return true;
                }
            } finally {
                shutdownLock.unlock();
            }
        }
        assert state >= SHUTDOWN;
        return false;
    }

    /**
     * Shutdown the executor without closing it. Shutting down an executor prevents new
     * threads from starting, interrupts all unfinished threads, and causes the
     * {@link #join() join} method to wakeup. Shutdown is useful for cases where the
     * results of unfinished tasks are no longer needed.
     *
     * <p> More specifically, this method:
     * <ul>
     * <li> {@linkplain Future#cancel(boolean) Cancels} the tasks that have threads
     * {@linkplain Future#get() waiting} on a result so that the waiting threads wakeup.
     * <li> {@linkplain Thread#interrupt() Interrupts} all unfinished threads in the
     * executor (except the current thread).
     * <li> Wakes up the owner if it is waiting in {@link #join()} or {@link
     * #joinUntil(Instant)}. If the owner is not waiting then its next call to {@code
     * join} or {@code joinUntil} will return immediately.
     * </ul>
     *
     * <p> When this method completes then the Future objects for all tasks will be
     * {@linkplain Future#isDone() done}, normally or abnormally. There may still be
     * threads that have not finished because they are executing code that did not
     * respond (or respond promptly) to thread interrupt. This method does not wait
     * for these threads. When the owner invokes the {@link #close() close} method
     * to close the executor then it will wait for the remaining threads to finish.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor.
     *
     * @throws IllegalStateException if this executor is closed, or the caller thread
     * is not the owner or a thread contained in the executor
     */
    public void shutdown() {
        ensureOwnerOrContainsThread();
        if (state == CLOSED)
            throw new IllegalStateException("Executor is closed");
        if (implShutdown())
            flock.wakeup();
    }

    /**
     * Closes this executor.
     *
     * <p> This method first shuts down the executor (as if by invoking the {@link
     * #shutdown() shutdown} method). It then waits for the threads executing any
     * unfinished tasks to finish. If interrupted then this method will continue to
     * wait for the threads to finish before completing with the interrupt status set.
     *
     * <p> This method may only be invoked by the executor owner.
     *
     * <p> A StructuredExecutor is intended to be used in a <em>structured manner</em>. If
     * this method is called to close an executor before nested executors are closed then
     * it closes the underlying construct of each nested executor (in the reverse order
     * that they were created in), closes this executor, and then throws {@link
     * StructureViolationException}.
     *
     * Similarly, if called to close an executor that <em>encloses</em> {@linkplain
     * ScopeLocal.Carrier#run(Runnable) operations} with scope-local bindings then
     * it also throws {@code StructureViolationException} after closing the executor.
     *
     * @throws IllegalStateException if invoked by a thread that is not the owner,
     * or thrown after closing the executor if the owner did not join the executor
     */
    @Override
    public void close() {
        ensureOwner();
        if (state == CLOSED)
            return;

        try {
            implShutdown();
            flock.close();
        } finally {
            state = CLOSED;
        }

        if (!joinInvoked) {
            throw new IllegalStateException("Owner did not invoke join or joinUntil");
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String name = flock.name();
        if (name != null) {
            sb.append(name);
            sb.append('/');
        }
        String id = getClass().getName() + "@" + System.identityHashCode(this);
        sb.append(id);
        int s = state;
        if (s == CLOSED)
            sb.append("/closed");
        else if (s == SHUTDOWN)
            sb.append("/shutdown");
        return sb.toString();
    }

    /**
     * The Future implementation returned by the fork methods. Most methods are
     * overridden to support cancellation when the executor is shutdown.
     * The blocking get methods register the Future with the executor so that they
     * are cancelled when the executor shuts down.
     */
    private class FutureImpl<U, V extends U>  extends FutureTask<V> {
        private final StructuredExecutor executor;
        private final BiConsumer<StructuredExecutor, Future<U>> onComplete;

        FutureImpl(StructuredExecutor executor,
                   Callable<V> task,
                   BiConsumer<StructuredExecutor, Future<U>> onComplete) {
            super(task);
            this.executor = executor;
            this.onComplete = onComplete;
        }

        @Override
        protected void done() {
            if (onComplete != null && !executor.isShutdown()) {
                @SuppressWarnings("unchecked")
                Future<U> f = (Future<U>) this;
                onComplete.accept(executor, f);
            }
        }

        private void cancelIfShutdown() {
            if (executor.isShutdown() && !super.isDone()) {
                super.cancel(false);
            }
        }

        @Override
        public boolean isDone() {
            cancelIfShutdown();
            return super.isDone();
        }

        @Override
        public boolean isCancelled() {
            cancelIfShutdown();
            return super.isCancelled();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelIfShutdown();
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            if (super.isDone())
                return super.get();
            executor.track(this);
            try {
                cancelIfShutdown();
                return super.get();
            } finally {
                executor.untrack(this);
            }
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(unit);
            if (super.isDone())
                return super.get();
            executor.track(this);
            try {
                cancelIfShutdown();
                return super.get(timeout, unit);
            } finally {
                executor.untrack(this);
            }
        }

        @Override
        public V resultNow() {
            cancelIfShutdown();
            return super.resultNow();
        }

        @Override
        public Throwable exceptionNow() {
            cancelIfShutdown();
            return super.exceptionNow();
        }

        @Override
        public State state() {
            cancelIfShutdown();
            return super.state();
        }

        @Override
        public String toString() {
            cancelIfShutdown();
            return super.toString();
        }
    }

    /**
     * An operation for the {@link #fork(Callable, BiConsumer) fork} method that
     * {@linkplain #shutdown() shuts down} the executor when first invoked with a
     * task that completed with a result. This operation can be used for cases where
     * it is not useful to wait for remaining tasks when one or more tasks
     * complete with a result.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @param <V> the result type
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnSuccess<V>
            implements BiConsumer<StructuredExecutor, Future<V>> {
        private static final VarHandle FIRST_SUCCESS;
        private static final VarHandle FIRST_FAILED;
        private static final VarHandle FIRST_CANCELLED;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_SUCCESS = l.findVarHandle(ShutdownOnSuccess.class, "firstSuccess", Future.class);
                FIRST_FAILED = l.findVarHandle(ShutdownOnSuccess.class, "firstFailed", Future.class);
                FIRST_CANCELLED = l.findVarHandle(ShutdownOnSuccess.class, "firstCancelled", Future.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private volatile Future<V> firstSuccess;
        private volatile Future<V> firstFailed;
        private volatile Future<V> firstCancelled;

        /**
         * Creates a new ShutdownOnSuccess object.
         */
        public ShutdownOnSuccess() { }

        /**
         * Shutdown the given executor when invoked for the first time with a task
         * that completed with a result.
         *
         * @param executor the executor
         * @param future the completed task
         * @throws IllegalArgumentException if the task has not completed
         * @see #shutdown()
         * @see Future.State#SUCCESS
         */
        @Override
        public void accept(StructuredExecutor executor, Future<V> future) {
            Objects.requireNonNull(executor);
            switch (future.state()) {
                case RUNNING -> throw new IllegalArgumentException("Task is not completed");
                case SUCCESS -> {
                    // capture first task to complete normally
                    if (firstSuccess == null
                            && FIRST_SUCCESS.compareAndSet(this, null, future)) {
                        executor.shutdown();
                    }
                }
                case FAILED -> {
                    // capture first task to complete with an exception
                    if (firstSuccess == null && firstFailed == null) {
                        FIRST_FAILED.compareAndSet(this, null, future);
                    }
                }
                case CANCELLED ->  {
                    // capture the first cancelled task
                    if (firstSuccess == null && firstFailed == null && firstCancelled == null) {
                        FIRST_CANCELLED.compareAndSet(this, null, future);
                    }
                }
            }
        }

        /**
         * {@return the result of the first task that completed with a result}
         *
         * <p> When no task completed with a result but a task completed with an exception
         * then {@code ExecutionException} is thrown with the exception as the {@linkplain
         * Throwable#getCause() cause}. If only cancelled tasks were notified to the {@code
         * accept} method then {@code CancellationException} is thrown.
         *
         * @throws ExecutionException if no tasks completed with a result but a task
         * completed with an exception
         * @throws CancellationException if all tasks were cancelled
         * @throws IllegalStateException if the accept method was not invoked with a
         * completed task
         */
        public V result() throws ExecutionException {
            Future<V> f = firstSuccess;
            if (f != null)
                return f.resultNow();
            if ((f = firstFailed) != null)
                throw new ExecutionException(f.exceptionNow());
            if (firstCancelled != null)
                throw new CancellationException();
            throw new IllegalStateException("No completed tasks");
        }

        /**
         * Returns the result of the first task that completed with a result, otherwise
         * throws an exception produced by the given exception supplying function.
         *
         * <p> When no task completed with a result but a task completed with an
         * exception then the exception supplying function is invoked with the
         * exception. If only cancelled tasks were notified to the {@code accept}
         * method then the exception supplying function is invoked with a
         * {@code CancellationException}.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @return the result of the first task that completed with a result
         * @throws X if no task completed with a result
         * @throws IllegalStateException if the accept method was not invoked with a
         * completed task
         */
        public <X extends Throwable> V result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            Future<V> f = firstSuccess;
            if (f != null)
                return f.resultNow();
            Throwable throwable = null;
            if ((f = firstFailed) != null) {
                throwable = f.exceptionNow();
            } else if (firstCancelled != null) {
                throwable = new CancellationException();
            }
            if (throwable != null) {
                X ex = esf.apply(throwable);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
            throw new IllegalStateException("No tasks completed");
        }
    }

    /**
     * An operation for the {@link #fork(Callable, BiConsumer) fork} method that
     * {@linkplain #shutdown() shuts down} the executor when first invoked with a
     * task that completed abnormally (with an exception or cancelled).
     * This operation can be used for cases where it is not useful to wait for
     * remaining tasks when one or more tasks fail.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnFailure
            implements BiConsumer<StructuredExecutor, Future<Object>> {
        private static final VarHandle FIRST_FAILED;
        private static final VarHandle FIRST_CANCELLED;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_FAILED = l.findVarHandle(ShutdownOnFailure.class, "firstFailed", Future.class);
                FIRST_CANCELLED = l.findVarHandle(ShutdownOnFailure.class, "fistCancelled", Future.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private volatile Future<Object> firstFailed;
        private volatile Future<Object> fistCancelled;

        /**
         * Creates a new ShutdownOnFailure object.
         */
        public ShutdownOnFailure() { }

        /**
         * Shutdown the given executor when invoked for the first time with a task
         * that completed abnormally (exception or cancelled).
         *
         * @param executor the executor
         * @param future the completed task
         * @throws IllegalArgumentException if the task has not completed
         * @see #shutdown()
         * @see Future.State#FAILED
         * @see Future.State#CANCELLED
         */
        @Override
        public void accept(StructuredExecutor executor, Future<Object> future) {
            Objects.requireNonNull(executor);
            switch (future.state()) {
                case RUNNING -> throw new IllegalArgumentException("Task is not completed");
                case SUCCESS -> { }
                case FAILED -> {
                    if (firstFailed == null
                            && FIRST_FAILED.compareAndSet(this, null, future)) {
                        executor.shutdown();
                    }
                }
                case CANCELLED -> {
                    if (firstFailed == null && fistCancelled == null
                            && FIRST_CANCELLED.compareAndSet(this, null, future)) {
                        executor.shutdown();
                    }
                }
            }
        }

        /**
         * Returns the exception for the first task that completed with an exception.
         * If no task completed with an exception but cancelled tasks were notified
         * to the {@code accept} method then a {@code CancellationException} is returned.
         * If no tasks completed abnormally then an empty {@code Optional} is returned.
         *
         * @return the exception for a task that completed abnormally or an empty
         * optional if no tasks completed
         */
        public Optional<Throwable> exception() {
            Future<Object> f = firstFailed;
            if (f != null)
                return Optional.of(f.exceptionNow());
            if (fistCancelled != null)
                return Optional.of(new CancellationException());
            return Optional.empty();
        }

        /**
         * Throws if a task completed abnormally. If any task completed with an
         * exception then {@code ExecutionException} is thrown with the exception of
         * the first task to fail as the {@linkplain Throwable#getCause() cause}.
         * If no task completed with an exception but cancelled tasks were notified
         * to the {@code accept} method then {@code CancellationException} is thrown.
         * This method does nothing if no tasks completed abnormally.
         *
         * @throws ExecutionException if a task completed with an exception
         * @throws CancellationException if no tasks completed with an exception but
         * tasks were cancelled
         */
        public void throwIfFailed() throws ExecutionException {
            Future<Object> f = firstFailed;
            if (f != null)
                throw new ExecutionException(f.exceptionNow());
            if (fistCancelled != null)
                throw new CancellationException();
        }

        /**
         * Throws the exception produced by the given exception supplying function if
         * a task completed abnormally. If any task completed with an exception then
         * the function is invoked with the exception of the first task to fail.
         * If no task completed with an exception but cancelled tasks were notified to
         * the {@code accept} method then the function is called with a {@code
         * CancellationException}. The exception returned by the function is thrown.
         * This method does nothing if no tasks completed abnormally.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @throws X produced by the exception supplying function
         */
        public <X extends Throwable>
        void throwIfFailed(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            Throwable throwable = null;
            Future<Object> f = firstFailed;
            if (f != null) {
                throwable = f.exceptionNow();
            } else if (fistCancelled != null) {
                throwable = new CancellationException();
            }
            if (throwable != null) {
                X ex = esf.apply(throwable);
                Objects.requireNonNull(ex, "esf returned null");
                throw ex;
            }
        }
    }
}
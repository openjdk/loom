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
 * {@snippet :
 *     try (var executor = StructuredExecutor.open()) {      // @highlight substring="open"
 *
 *         Future<String> future1 = executor.fork(task1);    // @highlight substring="fork"
 *         Future<String> future2 = executor.fork(task2);    // @highlight substring="fork"
 *
 *         executor.join();    // @highlight substring="join"
 *
 *         ... process results/exceptions ...
 *
 *     } // close // @highlight substring="close"
 * }
 * To ensure correct usage, the {@code join} and {@code close} methods may only be invoked
 * by the <em>executor owner</em> (the thread that opened the executor), and the {@code close}
 * method throws an exception after closing if the owner did not invoke the {@code join}
 * method after forking.
 *
 * <p> StructuredExecutor defines the {@link #shutdown() shutdown} method to shut down an
 * executor without closing it. Shutdown is useful for cases where a task completes with
 * a result (or exception) and the results of other unfinished tasks are no longer needed.
 * Invoking {@code shutdown} while the owner is waiting in the {@code join} method will cause
 * the {@code join} to wakeup. It also interrupts all unfinished threads and prevents new
 * threads from starting in the executor.
 *
 * <p> StructuredExecutor defines the 2-arg {@link #fork(Callable, CompletionHandler) fork}
 * method that executes a {@link CompletionHandler CompletionHandler} after a task completes.
 * The completion handler can be used to implement policy, collect results and/or exceptions,
 * and provide an API that makes available the outcome to the main task to process after the
 * {@code join} method.
 * {@snippet :
 *     try (var executor = StructuredExecutor.open()) {
 *
 *         MyHandler<String> handler = ...
 *
 *         Future<String> future1 = executor.fork(task1, handler);
 *         Future<String> future2 = executor.fork(task2, handler);
 *
 *         executor.join();
 *
 *         ... invoke handler methods to examine outcome, process results/exceptions, ...
 *
 *     }
 * }
 *
 * <p> StructuredExecutor defines two completion handlers that implement policy for two
 * common cases:
 * <ol>
 *   <li> {@link ShutdownOnSuccess ShutdownOnSuccess} captures the first result and shuts
 *   down the executor to interrupt unfinished threads and wakeup the owner. This handler
 *   is intended for cases where the result of any task will do ("invoke any") and where the
 *   results of other unfinished tasks are no longer needed. It defines methods to get the
 *   first result or throw an exception if all tasks fail.
 *   <li> {@link ShutdownOnFailure ShutdownOnFailure} captures the first exception and shuts
 *   down the executor. This handler is intended for cases where the results of all tasks
 *   are required ("invoke all"); if any task fails then the results of other unfinished tasks
 *   are no longer needed. If defines methods to throw an exception if any of the tasks fail.
 * </ol>
 *
 * <p> The following are two examples that use the built-in completion handlers. In both
 * cases, a pair of tasks are forked to fetch resources from two URL locations "left" and
 * "right". The first example creates a ShutdownOnSuccess object to capture the result of
 * the first task to complete normally, cancelling the other by way of shutting down the
 * executor. The main task waits in {@code join} until either task completes with a result
 * or both tasks fail. It invokes the handler's {@link ShutdownOnSuccess#result(Function)
 * result(Function)} method to get the captured result. If both tasks fails then this
 * method throws WebApplicationException with the exception from one of the tasks as the
 * cause.
 * {@snippet :
 *     try (var executor = StructuredExecutor.open()) {
 *
 *         var handler = new ShutdownOnSuccess<String>();
 *
 *         executor.fork(() -> fetch(left), handler);
 *         executor.fork(() -> fetch(right), handler);
 *
 *         executor.join();
 *
 *         // @link regex="result(?=\()" target="ShutdownOnSuccess#result" :
 *         String result = handler.result(e -> new WebApplicationException(e));
 *
 *         :
 *     }
 * }
 * The second example creates a ShutdownOnFailure operation to capture the exception of
 * the first task to fail, cancelling the other by way of shutting down the executor. The
 * main task waits in {@link #joinUntil(Instant)} until both tasks complete with a result,
 * either fails, or a deadline is reached. It invokes the handler's {@link
 * ShutdownOnFailure#throwIfFailed(Function) throwIfFailed(Function)} to throw an exception
 * when either task fails. This method is a no-op if no tasks fail. The main task uses
 * {@code Future}'s {@link Future#resultNow() resultNow()} method to retrieve the results.
 *
 * {@snippet :
 *    Instant deadline = ...
 *
 *    try (var executor = StructuredExecutor.open()) {
 *
 *         var handler = new ShutdownOnFailure();
 *
 *         Future<String> future1 = executor.fork(() -> query(left), handler);
 *         Future<String> future2 = executor.fork(() -> query(right), handler);
 *
 *         executor.joinUntil(deadline);
 *
 *         // @link substring="throwIfFailed" target="ShutdownOnFailure#throwIfFailed" :
 *         handler.throwIfFailed(e -> new WebApplicationException(e));
 *
 *         // both tasks completed successfully
 *         String result = Stream.of(future1, future2)
 *                 // @link substring="Future::resultNow" target="Future#resultNow" :
 *                 .map(Future::resultNow)
 *                 .collect(Collectors.join(", ", "{ ", " }"));
 *
 *         :
 *     }
 * }
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

    // set when owner calls fork, reset when owner calls join
    private boolean needJoin;

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
     * Throws WrongThreadException if the current thread is not the owner.
     */
    private void ensureOwner() {
        if (Thread.currentThread() != flock.owner())
            throw new WrongThreadException("Current thread not owner");
    }

    /**
     * Throws WrongThreadException if the current thread is not the owner
     * or a thread contained in the tree.
     */
    private void ensureOwnerOrContainsThread() {
        Thread currentThread = Thread.currentThread();
        if (currentThread != flock.owner() && !flock.containsThread(currentThread))
            throw new WrongThreadException("Current thread not owner or thread in executor");
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
     * Starts a new thread to run the given task. If handler is non-null then it is
     * invoked if the task completes before the executor is shutdown.
     * @throws IllegalStateException
     * @throws WrongThreadException
     * @throws StructureViolationException
     * @throws RejectedExecutionException
     */
    private <V> Future<V> spawn(Callable<? extends V> task,
                                CompletionHandler<? super V> handler) {
        Objects.requireNonNull(task);

        // create future
        var future = new FutureImpl<V>(this, task, handler);

        boolean shutdown = (state >= SHUTDOWN);

        if (!shutdown) {
            // create thread
            Thread thread = factory.newThread(future);
            if (thread == null)
                throw new RejectedExecutionException("Rejected by thread factory");

            // attempt to start the thread
            try {
                flock.start(thread);
            } catch (IllegalStateException e) {
                // shutdown or in the process of shutting down
                shutdown = true;
            }
        }

        if (shutdown) {
            if (state == CLOSED) {
                throw new IllegalStateException("Executor is closed");
            } else {
                future.cancel(false);
            }
        }

        // if owner forks then it will need to join
        if (Thread.currentThread() == flock.owner() && !needJoin) {
            needJoin = true;
        }

        return future;
    }

    /**
     * Starts a new thread to run the given task.
     *
     * <p> The thread inherits the current thread's {@linkplain ScopeLocal scope-local}
     * bindings and must match the bindings captured when the executor was created.
     *
     * <p> If this executor is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then this method returns a Future representing a {@link
     * Future.State#CANCELLED cancelled} task that was not run.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor. The {@link Future#cancel(boolean) cancel} method of the returned
     * {@code Future} object is also restricted to the executor owner or threads contained
     * in the executor; {@link WrongThreadException} is thrown if {@code cancel} is invoked
     * from another thread.
     *
     * @param task the task to run
     * @param <V> the task return type
     * @return a future
     * @throws IllegalStateException if this executor is closed
     * @throws WrongThreadException if the current thread is not the owner or a thread
     * contained in the executor
     * @throws StructureViolationException if the current scope-local bindings are not
     * the same as when the executor was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <V> Future<V> fork(Callable<? extends V> task) {
        return spawn(task, null);
    }

    /**
     * Starts a new thread to run the given task and a completion handler when the task
     * completes.
     *
     * <p> The thread inherits the current thread's {@linkplain ScopeLocal scope-local}
     * bindings and must match the bindings captured when the executor was created.
     *
     * <p> The completion handler's {@link CompletionHandler#handle(StructuredExecutor, Future)
     * handle} method is invoked if the task completes before the executor is {@link
     * #shutdown() shutdown}. The {@code handle} method is run by the thread when the task
     * completes with a result or exception. If the {@link Future#cancel(boolean) Future.cancel}
     * method is used to cancel a task, before the executor is shutdown, then the {@code handle}
     * method is run by the thread that invokes {@code cancel}. If the executor shuts down at
     * or around the same time that the task completes or is cancelled then the completion
     * handler may or may not be invoked.
     *
     * The {@link CompletionHandler#compose(CompletionHandler, CompletionHandler) compose}
     * method can be used to compose more than one handler where required.
     *
     * <p> If this executor is {@linkplain #shutdown() shutdown} (or in the process of
     * shutting down) then this method returns a Future representing a {@link
     * Future.State#CANCELLED cancelled} task that was not run.
     *
     * <p> This method may only be invoked by the executor owner or threads contained
     * in the executor. The {@link Future#cancel(boolean) cancel} method of the returned
     * {@code Future} object is also restricted to the executor owner or threads contained
     * in the executor; {@link WrongThreadException} is thrown if {@code cancel} is invoked
     * from another thread.
     *
     * @param task the task to run
     * @param handler the completion handler to run when the task completes
     * @param <V> the task return type
     * @return a future
     * @throws IllegalStateException if this executor is closed
     * @throws WrongThreadException if the current thread is not the owner or a thread
     * contained in the executor
     * @throws StructureViolationException if the current scope-local bindings are not
     * the same as when the executor was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <V> Future<V> fork(Callable<? extends V> task,
                              CompletionHandler<? super V> handler) {
        return spawn(task, Objects.requireNonNull(handler));
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
     * @throws RejectedExecutionException if this executor is closed, the current thread
     * is not the owner or a thread contained in the executor, the current scope-local
     * bindings are not the same as when the executor was created, or if the thread
     * factory rejected creating a thread to run the task
     */
    @Override
    public void execute(Runnable task) {
        try {
            spawn(Executors.callable(task), null);
        } catch (IllegalStateException |            // executor closed
                WrongThreadException |              // called from wrong thread
                StructureViolationException e) {    // scope-local bindings changed
            throw new RejectedExecutionException(e);
        }
    }

    /**
     * Wait for all threads to finish or the executor to shutdown.
     */
    private void implJoin(Duration timeout)
        throws InterruptedException, TimeoutException
    {
        ensureOwner();
        needJoin = false;
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
     * #fork(Callable, CompletionHandler) completion handlers}), the {@link #shutdown()
     * shutdown} method is invoked to shut down the executor, or the current thread is
     * interrupted.
     *
     * <p> This method may only be invoked by the executor owner.
     *
     * @throws IllegalStateException if this executor is closed
     * @throws WrongThreadException if the current thread is not the owner
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
     * tasks (including {@linkplain #fork(Callable, CompletionHandler) completion
     * handlers}), the {@link #shutdown() shutdown} method is invoked to shut down
     * the executor, the current thread is interrupted, or the deadline is reached.
     *
     * <p> This method may only be invoked by the executor owner.
     *
     * @param deadline the deadline
     * @throws IllegalStateException if this executor is closed
     * @throws WrongThreadException if the current thread is not the owner
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
     * @throws IllegalStateException if this executor is closed
     * @throws WrongThreadException if the current thread is not the owner or
     * a thread contained in the executor
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
     * @throws IllegalStateException thrown after closing the executor if the owner
     * did not invoke join after forking
     * @throws WrongThreadException if the current thread is not the owner
     * @throws StructureViolationException if a structure violation was detected
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

        if (needJoin) {
            throw new IllegalStateException("Owner did not invoke join or joinUntil after fork");
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
    private static class FutureImpl<V> extends FutureTask<V> {
        private final StructuredExecutor executor;
        private final CompletionHandler<? super V> handler;

        @SuppressWarnings("unchecked")
        FutureImpl(StructuredExecutor executor,
                   Callable<? extends V> task,
                   CompletionHandler<? super V> handler) {
            super((Callable<V>) task);
            this.executor = executor;
            this.handler = handler;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void done() {
            if (handler != null && !executor.isShutdown()) {
                var handler = (CompletionHandler<Object>) this.handler;
                var future = (Future<Object>) this;
                handler.handle(executor, future);
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
            executor.ensureOwnerOrContainsThread();
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
     * A handler that accepts a StructuredExecutor and a Future object for a completed
     * task.
     *
     * A CompletionHandler is specified to the {@link #fork(Callable, CompletionHandler)
     * fork} method to execute after the task completes. It defines the {@link
     * #handle(StructuredExecutor, Future) handle} method to handle the completed task.
     * The {@code handle} method does not return a result, it is expected to operate via
     * side-effects.
     *
     * <p> A completion handler implements a policy on how tasks that complete normally and
     * abnormally are handled. It may, for example, collect the results of tasks that complete
     * with a result and ignore tasks that fail. It may collect exceptions when tasks fail. It
     * may invoke the {@link #shutdown() shutdown} method to shut down the executor and
     * cause {@link #join() join} to wakeup when some condition arises.
     *
     * <p> A completion handler will typically define methods to make available results, state,
     * or other outcome to code that executes after the {@code join} method. A completion
     * handler that collects results and ignores tasks that fail may define a method that
     * returns a possibly-empty collection of results. A completion handler that implements
     * a policy to shut down the executor when a task fails may define a method to retrieve
     * the exception of the first task to fail.
     *
     * <p> A completion handler implementation is required to be thread safe. The {@code
     * handle} method may be invoked by several threads at around the same time.
     *
     * <p> The {@link #compose(CompletionHandler, CompletionHandler) compose} method may be
     * used to compose more than one completion handler if required.
     *
     * <p> The following is an example of a completion handler that collects the results
     * of tasks that complete successfully. It defines a {@code results()} method for the
     * main task to invoke to retrieve the results.
     *
     * {@snippet :
     *     class MyHandler<V> implements CompletionHandler<V> {
     *         private final Queue<V> results = new ConcurrentLinkedQueue<>();
     *
     *         @Override
     *         public void handle(StructuredExecutor executor, Future<V> future) {
     *             switch (future.state()) {
     *                 case RUNNING -> throw new IllegalArgumentException();
     *                 case SUCCESS -> {
     *                     V result = future.resultNow();
     *                     results.add(result);
     *                 }
     *                 case FAILED, CANCELLED -> {
     *                     // ignore for now
     *                 }
     *             }
     *         }
     *
     *         // Returns a stream of results from tasks that completed successfully.
     *         public Stream<V> results() {
     *             return results.stream();
     *         }
     *     }
     * }
     *
     * @param <V> the result type
     * @since 99
     */
    @FunctionalInterface
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public interface CompletionHandler<V> {
        /**
         * Handles a completed task.
         *
         * @param executor the executor
         * @param future the Future for the completed task
         * @throws IllegalArgumentException if the task has not completed
         * @throws NullPointerException if executor or future is {@code null}
         */
        void handle(StructuredExecutor executor, Future<V> future);

        /**
         * Returns a composed {@code CompletionHandler} that performs, in sequence, a
         * {@code first} handler followed by a {@code second} handler. If performing
         * either handler throws an exception, it is relayed to the caller of the
         * composed handler. If performing the first handler throws an exception,
         * the {@code second} handler will not be performed.
         *
         * @param first the first handler
         * @param second the second handler
         * @param <V> the result type
         * @return a composed CompletionHandler that performs in sequence the first
         * and second handlers
         * @throws NullPointerException if first or second is {@code null}
         */
        static <V> CompletionHandler<V> compose(CompletionHandler<? extends V> first,
                                                CompletionHandler<? extends V> second) {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            @SuppressWarnings("unchecked")
            var handler1 = (CompletionHandler<V>) first;
            @SuppressWarnings("unchecked")
            var handler2 = (CompletionHandler<V>) second;
            return (e, f) -> {
                handler1.handle(e, f);
                handler2.handle(e, f);
            };
        }
    }

    /**
     * A CompletionHandler that captures the result of the first task to complete
     * successfully. Once captured, the handler {@linkplain #shutdown() shuts down}
     * the executor to interrupt unfinished threads and wakeup the owner. This handler
     * is intended for cases where the result of any task will do ("invoke any") and
     * where the results of other unfinished tasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @param <V> the result type
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnSuccess<V> implements CompletionHandler<V> {
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
         * @throws IllegalArgumentException {@inheritDoc}
         * @see #shutdown()
         * @see Future.State#SUCCESS
         */
        @Override
        public void handle(StructuredExecutor executor, Future<V> future) {
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
         * handle} method then {@code CancellationException} is thrown.
         *
         * @throws ExecutionException if no tasks completed with a result but a task
         * completed with an exception
         * @throws CancellationException if all tasks were cancelled
         * @throws IllegalStateException if the handle method was not invoked with a
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
         * exception. If only cancelled tasks were notified to the {@code handle}
         * method then the exception supplying function is invoked with a
         * {@code CancellationException}.
         *
         * @param esf the exception supplying function
         * @param <X> type of the exception to be thrown
         * @return the result of the first task that completed with a result
         * @throws X if no task completed with a result
         * @throws IllegalStateException if the handle method was not invoked with a
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
     * A CompletionHandler that captures the exception of the first task to complete
     * abnormally. Once captured, the handler {@linkplain #shutdown() shuts down} the
     * executor to interrupt unfinished threads and wakeup the owner. This handler is
     * intended for cases where the results for all tasks are required ("invoke all");
     * if any task fails then the results of other unfinished tasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnFailure implements CompletionHandler<Object> {
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
         * @throws IllegalArgumentException {@inheritDoc}
         * @see #shutdown()
         * @see Future.State#FAILED
         * @see Future.State#CANCELLED
         */
        @Override
        public void handle(StructuredExecutor executor, Future<Object> future) {
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
         * to the {@code handle} method then a {@code CancellationException} is returned.
         * If no tasks completed abnormally then an empty {@code Optional} is returned.
         *
         * @return the exception for a task that completed abnormally or an empty
         * optional if no tasks completed abnormally
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
         * to the {@code handle} method then {@code CancellationException} is thrown.
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
         * the {@code handle} method then the function is called with a {@code
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
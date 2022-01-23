/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * A basic API for <em>structured concurrency</em>. StructuredTaskScope supports cases
 * where a task splits into several concurrent sub-tasks, to be executed in their own
 * threads, and where the sub-tasks must complete before the main task continues. A
 * StructuredTaskScope can be used to ensure that the lifetime of a concurrent operation
 * is confined by a <em>syntax block</em>, just like that of a sequential operation in
 * structured programming.
 *
 * <h2>Basic usage</h2>
 *
 * StructuredTaskScope defines the {@link #open() open} methods to open a new task scope,
 * the {@link #fork(Callable) fork} method to start a thread to execute a task, the {@link
 * #join() join} method to wait for all threads to finish, and the {@link #close() close}
  * method to close the task scope. The API is intended to be used with the {@code
 * try-with-resources} construct. The intention is that code in the <em>block</em> uses
 * the {@code fork} method to fork threads to execute the sub-tasks, wait for the threads
 * to finish with the {@code join} method, and then <em>process the results</em>.
 * Processing of results may include handling or re-throwing of exceptions.
 * {@snippet lang=java :
 *     try (var scope = StructuredTaskScope.<String>open()) {  // @highlight substring="open"
 *
 *         Future<String> future1 = scope.fork(task1);    // @highlight substring="fork"
 *         Future<String> future2 = scope.fork(task2);    // @highlight substring="fork"
 *
 *         scope.join();    // @highlight substring="join"
 *
 *         ... process results/exceptions ...
 *
 *     } // close // @highlight substring="close"
 * }
 * To ensure correct usage, the {@code join} and {@code close} methods may only be invoked
 * by the <em>owner</em> (the thread that opened/created the StructuredTaskScope, and the
 * {@code close} method throws an exception after closing if the owner did not invoke the
 * {@code join} method after forking.
 *
 * <p> StructuredTaskScope defines the {@link #shutdown() shutdown} method to shut down a
 * task scope without closing it. Shutdown is useful for cases where a task completes with
 * a result (or exception) and the results of other unfinished tasks are no longer needed.
 * Invoking {@code shutdown} while the owner is waiting in the {@code join} method will cause
 * {@code join} to wakeup. It also interrupts all unfinished threads and prevents new threads
 * from starting in the task scope.
 *
 * <h2><a id="CompletionPolicy">Completion policy</a></h2>
 *
 * A StructuredTaskScope may be opened with a {@link CompletionPolicy CompletionPolicy} that
 * executes after each task completes. A completion policy can be used to implement policy,
 * collect results and/or exceptions, and provide an API that makes available the outcome to the
 * main task to process after the {@code join} method. A completion policy may, for example,
 * collect the results of tasks that complete with a result and ignore tasks that fail. It may
 * collect exceptions when tasks fail. It may invoke the {@link #shutdown() shutdown} method to
 * shut down the task scope and cause {@link #join() join} to wakeup when some condition arises.
 * CompletionPolicy extends {@link AutoCloseable} to allow a completion policy be declared in
 * the resource specification of a try-with-resources statement.
 * {@snippet lang=java :
 *     try (CompletionPolicy<String> policy = ...               // @highlight substring="policy"
 *          var scope = StructuredTaskScope.open(policy)) {     // @highlight substring="policy"
 *
 *         Future<String> future1 = scope.fork(task1);
 *         Future<String> future2 = scope.fork(task2);
 *
 *         scope.join();
 *
 *         // @highlight region
 *         ... invoke policy methods to obtain outcome, process results/exceptions, ...
 *         // @end
 *
 *     }
 * }
 *
 * <h2><a id="BuiltinCompletionPolicies">ShutdownOnSuccess and ShutdownOnFailure</a></h2>
 *
 * StructuredTaskScope defines two completion policies that implement policy for two common
 * cases:
 * <ol>
 *   <li> {@link ShutdownOnSuccess ShutdownOnSuccess} captures the first result and shuts
 *   down the task scope to interrupt unfinished threads and wakeup the owner. This policy
 *   is intended for cases where the result of any task will do ("invoke any") and where the
 *   results of other unfinished tasks are no longer needed. It defines methods to get the
 *   first result or throw an exception if all tasks fail.
 *   <li> {@link ShutdownOnFailure ShutdownOnFailure} captures the first exception and shuts
 *   down the task scope. This policy is intended for cases where the results of all tasks
 *   are required ("invoke all"); if any task fails then the results of other unfinished tasks
 *   are no longer needed. If defines methods to throw an exception if any of the tasks fail.
 * </ol>
 *
 * <p> The following are two examples that use the built-in completion policies. In both
 * cases, a pair of tasks are forked to fetch resources from two URL locations "left" and
 * "right". The first example creates a ShutdownOnSuccess object to capture the result of
 * the first task to complete normally, cancelling the other by way of shutting down the
 * task scope. The main task waits in {@code join} until either task completes with a result
 * or both tasks fail. It invokes the policy's {@link ShutdownOnSuccess#result(Function)
 * result(Function)} method to get the captured result. If both tasks fail then this
 * method throws WebApplicationException with the exception from one of the tasks as the
 * cause.
 * {@snippet lang=java :
 *     try (var policy = new ShutdownOnSuccess<String>();
 *          var scope = StructuredTaskScope.open(policy)) {
 *
 *         scope.fork(() -> fetch(left));
 *         scope.fork(() -> fetch(right));
 *
 *         scope.join();
 *
 *         // @link regex="result(?=\()" target="ShutdownOnSuccess#result" :
 *         String result = policy.result(e -> new WebApplicationException(e));
 *
 *         ...
 *     }
 * }
 * The second example creates a ShutdownOnFailure operation to capture the exception of
 * the first task to fail, cancelling the other by way of shutting down the task scope. The
 * main task waits in {@link #joinUntil(Instant)} until both tasks complete with a result,
 * either fails, or a deadline is reached. It invokes the policy's {@link
 * ShutdownOnFailure#throwIfFailed(Function) throwIfFailed(Function)} to throw an exception
 * when either task fails. This method is a no-op if no tasks fail. The main task uses
 * {@code Future}'s {@link Future#resultNow() resultNow()} method to retrieve the results.
 *
 * {@snippet lang=java :
 *    Instant deadline = ...
 *
 *    try (var policy = new ShutdownOnFailure();
 *         var scope = StructuredTaskScope.open(policy)) {
 *
 *         Future<String> future1 = scope.fork(() -> query(left));
 *         Future<String> future2 = scope.fork(() -> query(right));
 *
 *         scope.joinUntil(deadline);
 *
 *         // @link substring="throwIfFailed" target="ShutdownOnFailure#throwIfFailed" :
 *         policy.throwIfFailed(e -> new WebApplicationException(e));
 *
 *         // both tasks completed successfully
 *         String result = Stream.of(future1, future2)
 *                 // @link substring="Future::resultNow" target="Future#resultNow" :
 *                 .map(Future::resultNow)
 *                 .collect(Collectors.joining(", ", "{ ", " }"));
 *
 *         ...
 *     }
 * }
 *
 * <h2>Tree structure</h2>
 *
 * StructuredTaskScopes form a tree where parent-child relations are established implicitly
 * when opening a new task scope:
 * <ul>
 *   <li> A parent-child relation is established when a thread started in a task scope opens
 *   its own task scope. A thread started in task scope "A" opens task scope "B" establishes
 *   a parent-child relation where task scope "A" is the parent of task scope "B".
 *   <li> A parent-child relation is established with nesting. If a thread opens task scope
 *   "B", then open task scope "C" (before it closes "B"), then the enclosing task scope "B"
 *   is the parent of the nested task scope "C".
 * </ul>
 *
 * <p> The tree structure supports:
 * <ul>
 *   <li> Confinement checks. The phrase "threads contained in the task scope" in method
 *   descriptions means threads started in the task scope or descendant scopes.
 *   <li> Inheritance of {@linkplain ScopeLocal scope-local} bindings by threads.
 * </ul>
 *
 * <p> The following example demonstrates the inheritance of a scope-local binding. A scope
 * local {@code NAME} is bound to the value "duke". A StructuredTaskScope is created and
 * its {@code fork} method invoked to start a thread to execute {@code childTask}. The thread
 * inherits the scope-local binding. The code in {@code childTask} uses the value of the
 * scope-local and so reads the value "duke".
 * {@snippet lang=java :
 *     private static final ScopeLocal<String> NAME = ScopeLocal.newInstance();
 *
 *     // @link substring="where" target="ScopeLocal#bind" :
 *     try (var binding = ScopeLocal.where(NAME, "duke").bind();
 *          var scope = StructuredTaskScope.open(policy)) {
 *
 *         scope.fork(() -> childTask());    // @highlight substring="fork"
 *         ...
 *      }
 *
 *     ...
 *
 *     String childTask() {
 *         String name = NAME.get();   // "duke"    // @highlight substring="get"
 *         ...
 *     }
 * }
 *
 * <p> StructuredTaskScope does not define APIs that exposes the tree structure at this time.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * @param <T> the result type of tasks executed in the scope
 * @since 99
 */
@PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
public class StructuredTaskScope<T> implements AutoCloseable {
    private static final VarHandle FUTURES;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            FUTURES = l.findVarHandle(StructuredTaskScope.class, "futures", Set.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private final ThreadFactory factory;
    private final CompletionPolicy<? super T> policy;
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

    StructuredTaskScope(String name, ThreadFactory factory, CompletionPolicy<? super T> policy) {
        this.factory = Objects.requireNonNull(factory, "'factory' is null");
        this.policy = Objects.requireNonNull(policy, "'policy' is null");
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
            throw new WrongThreadException("Current thread not owner or thread in the tree");
    }

    /**
     * Return the completion policy.
     */
    private CompletionPolicy<? super T> policy() {
        return policy;
    }

    /**
     * Tests if the task scope is shutdown.
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
     * Opens a structured task scope that uses the given thread factory and completion policy.
     * The thread factory is used to {@link ThreadFactory#newThread(Runnable) create} threads
     * when tasks are {@linkplain  #fork(Callable) forked}. The completion policy's
     * {@link CompletionPolicy#handle(StructuredTaskScope, Future) handle} method is invoked
     * when tasks complete. The task scope is optionally named for the purposes of monitoring
     * and management. The task scope is owned by the current thread.
     *
     * <p> This method captures the current thread's {@linkplain ScopeLocal scope-local}
     * bindings for inheritance by threads created in the task scope.
     *
     * <p> For the purposes of confinement and inheritance of scope-local bindings, the
     * parent of the task scope is determined as follows:
     * <ul>
     * <li> If the current thread is the owner of open task scopes then the most recently
     * created, and open, task scope is the parent of the new task scope. In other words,
     * the <em>enclosing task scope</em> is the parent.
     * <li> If the current thread is not the owner of any open task scopes then the
     * parent of the new task scope is the current thread's task scope. If the current
     * thread was not started in a task scope then the new task scope does not have a parent.
     * </ul>
     *
     * @param name the name of the task scope, can be null
     * @param factory the thread factory
     * @param policy the completion policy
     * @param <T> the result type of tasks executed in the scope
     * @return a new StructuredTaskScope
     */
    public static <T> StructuredTaskScope<T> open(String name,
                                                  ThreadFactory factory,
                                                  CompletionPolicy<? super T> policy) {
        return new StructuredTaskScope<>(name, factory, policy);
    }

    /**
     * Opens a structured task scope that creates virtual threads and uses the given
     * completion policy. The completion policy's
     * {@link CompletionPolicy#handle(StructuredTaskScope, Future) handle} method is invoked
     * when tasks complete. The task scope is unnamed. The task scope is owned by the current
     * thread.
     *
     * <p> This method is equivalent to invoking {@link #open(String, ThreadFactory, CompletionPolicy)}
     * with a name of {@code null}, a thread factory that creates virtual threads, and the
     * completion policy.
     *
     * @param policy the completion policy
     * @param <T> the result type of tasks executed in the scope
     * @return a new StructuredTaskScope
     */
    public static <T> StructuredTaskScope<T> open(CompletionPolicy<? super T> policy) {
        ThreadFactory factory = Thread.ofVirtual().factory();
        return new StructuredTaskScope<>(null, factory, policy);
    }

    /**
     * Opens a structured task scope that creates virtual threads. The task scope does
     * not have a completion policy and is unnamed. The task scope is owned by the current
     * thread.
     *
     * <p> This method is equivalent to invoking {@link #open(String, ThreadFactory, CompletionPolicy)}
     * with a name of {@code null}, a thread factory that creates virtual threads, and a
     * completion policy that does nothing.
     *
     * @param <T> the result type of tasks executed in the scope
     * @return a new StructuredTaskScope
     */
    public static <T> StructuredTaskScope<T> open() {
        ThreadFactory factory = Thread.ofVirtual().factory();
        return new StructuredTaskScope<>(null, factory, (s, f) -> { });
    }

    /**
     * Starts a new thread to run the given task.
     *
     * <p> The new thread is created with the task scope's {@link ThreadFactory}. It inherits
     * the current thread's {@linkplain ScopeLocal scope-local} bindings. The bindings must
     * match the bindings captured when the task scope was created.
     *
     * <p> If the task completes before the task scope is {@link #shutdown() shutdown} then
     * the completion policy's {@link CompletionPolicy#handle(StructuredTaskScope, Future)
     * handle} method is invoked to consume the task. The {@code handle} method is run
     * when the task completes with a result or exception. If the {@code Future}
     * {@link Future#cancel(boolean) cancel} method is used the cancel a task before the
     * task scope is shutdown, then the {@code handle} method is run by the thread that
     * invokes {@code cancel}. If the task scope shuts down at or around the same time that
     * the task completes or is cancelled then the completion policy may or may not be invoked.
     *
     * <p> If this task scope is {@linkplain #shutdown() shutdown} (or in the process
     * of shutting down) then {@code fork} returns a Future representing a {@link
     * Future.State#CANCELLED cancelled} task that was not run.
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope. The {@link Future#cancel(boolean) cancel} method of the returned
     * {@code Future} object is also restricted to the task scope owner or threads contained
     * in the task scope. The {@code cancel} method throws {@link WrongThreadException} if
     * invoked from another thread.
     *
     * @param task the task to run
     * @param <U> the result type
     * @return a future
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the owner or a thread
     * contained in the task scope
     * @throws StructureViolationException if the current scope-local bindings are not
     * the same as when the task scope was created
     * @throws RejectedExecutionException if the thread factory rejected creating a
     * thread to run the task
     */
    public <U extends T> Future<U> fork(Callable<? extends U> task) {
        Objects.requireNonNull(task, "'task' is null");

        // create future
        @SuppressWarnings("unchecked")
        Callable<T> t = (Callable<T>) task;
        var future = new FutureImpl<T>(this, t);

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
                throw new IllegalStateException("Task scope is closed");
            } else {
                future.cancel(false);
            }
        }

        // if owner forks then it will need to join
        if (Thread.currentThread() == flock.owner() && !needJoin) {
            needJoin = true;
        }

        @SuppressWarnings("unchecked")
        var f = (Future<U>) future;
        return f;
    }

    /**
     * Wait for all threads to finish or the task scope to shut down.
     */
    private void implJoin(Duration timeout)
        throws InterruptedException, TimeoutException
    {
        ensureOwner();
        needJoin = false;
        int s = state;
        if (s >= SHUTDOWN) {
            if (s == CLOSED)
                throw new IllegalStateException("Task scope is closed");
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
     * Wait for all threads to finish or the task scope to shutdown. This method waits
     * until all threads started in the task scope finish execution (of both task
     * and completion policy), the {@link #shutdown() shutdown} method is invoked to
     * shut down the task scope, or the current thread is interrupted.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @throws IllegalStateException if this task scope is closed
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
     * Wait for all threads to finish or the task scope to shutdown, up to the given
     * deadline. This method waits until all threads started in the task scope finish
     * execution (of both task and completion policy), the {@link #shutdown() shutdown}
     * method is invoked to shut down the task scope, the current thread is interrupted,
     * or the deadline is reached.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * @param deadline the deadline
     * @throws IllegalStateException if this task scope is closed
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
     * Shutdown the task scope if not already shutdown. Return true if this method
     * shutdowns the task scope, false if already shutdown.
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
     * Shutdown the task scope without closing it. Shutting down a task scope prevents new
     * threads from starting, interrupts all unfinished threads, and causes the
     * {@link #join() join} method to wakeup. Shutdown is useful for cases where the
     * results of unfinished tasks are no longer needed.
     *
     * <p> More specifically, this method:
     * <ul>
     * <li> {@linkplain Future#cancel(boolean) Cancels} the tasks that have threads
     * {@linkplain Future#get() waiting} on a result so that the waiting threads wakeup.
     * <li> {@linkplain Thread#interrupt() Interrupts} all unfinished threads in the
     * task scope (except the current thread).
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
     * to close the task scope then it will wait for the remaining threads to finish.
     *
     * <p> This method may only be invoked by the task scope owner or threads contained
     * in the task scope.
     *
     * @throws IllegalStateException if this task scope is closed
     * @throws WrongThreadException if the current thread is not the owner or
     * a thread contained in the task scope
     */
    public void shutdown() {
        ensureOwnerOrContainsThread();
        if (state == CLOSED)
            throw new IllegalStateException("Task scope is closed");
        if (implShutdown())
            flock.wakeup();
    }

    /**
     * Closes this task scope.
     *
     * <p> This method first shuts down the task scope (as if by invoking the {@link
     * #shutdown() shutdown} method). It then waits for the threads executing any
     * unfinished tasks to finish. If interrupted then this method will continue to
     * wait for the threads to finish before completing with the interrupt status set.
     *
     * <p> This method may only be invoked by the task scope owner.
     *
     * <p> A StructuredTaskScope is intended to be used in a <em>structured manner</em>.
     * If this method is called to close a task scope before nested task scopes are closed
     * then it closes the underlying construct of each nested task scope (in the reverse
     * order that they were created in), closes this task scope, and then throws {@link
     * StructureViolationException}.
     *
     * Similarly, if called to close a task scope that <em>encloses</em> {@linkplain
     * ScopeLocal.Carrier#run(Runnable) operations} with scope-local bindings then
     * it also throws {@code StructureViolationException} after closing the task scope.
     *
     * @throws IllegalStateException thrown after closing the task scope if the owner
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
     * overridden to support cancellation when the task scope is shutdown.
     * The blocking get methods register the Future with the task scope so that they
     * are cancelled when the task scope shuts down.
     */
    private static class FutureImpl<V> extends FutureTask<V> {
        private final StructuredTaskScope<V> scope;

        FutureImpl(StructuredTaskScope<V> scope, Callable<V> task) {
            super(task);
            this.scope = scope;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void done() {
            if (!scope.isShutdown()) {
                var policy = (CompletionPolicy<Object>) scope.policy();
                var s = (StructuredTaskScope<Object>) this.scope;
                var f = (Future<Object>) this;
                policy.handle(s, f);
            }
        }

        private void cancelIfShutdown() {
            if (scope.isShutdown() && !super.isDone()) {
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
            scope.ensureOwnerOrContainsThread();
            cancelIfShutdown();
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            if (super.isDone())
                return super.get();
            scope.track(this);
            try {
                cancelIfShutdown();
                return super.get();
            } finally {
                scope.untrack(this);
            }
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            Objects.requireNonNull(unit);
            if (super.isDone())
                return super.get();
            scope.track(this);
            try {
                cancelIfShutdown();
                return super.get(timeout, unit);
            } finally {
                scope.untrack(this);
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
     * An operation that implements a <em>completion policy</em>. A CompletionPolicy can be
     * specified when creating a {@link StructuredTaskScope} so that the {@link
     * #handle(StructuredTaskScope, Future) handle} method is invoked to consume each
     * completed task.
     *
     * <p> A CompletionPolicy implements policy on how tasks that complete normally and
     * abnormally are handled. It may, for example, collect the results of tasks that complete
     * with a result and ignore tasks that fail. It may collect exceptions when tasks fail. It
     * may invoke the {@link #shutdown() shutdown} method to shut down the task scope and
     * cause {@link #join() join} to wakeup when some condition arises.
     *
     * <p> A completion policy will typically define methods to make available results, state,
     * or other outcome to code that executes after the {@code join} method. A completion
     * policy that collects results and ignores tasks that fail may define a method that
     * returns a possibly-empty collection of results. A completion policy that implements
     * a policy to shut down the task scope when a task fails may define a method to retrieve
     * the exception of the first task to fail.
     *
     * <p> A completion policy implementation is required to be thread safe. The {@code
     * handle} method may be invoked by several threads at around the same time. The {@code
     * handle} method does not return a result, it is expected to operate via side-effects.
     *
     * <p> The {@link #compose(CompletionPolicy, CompletionPolicy) compose} method may be
     * used to compose more than one completion policy if required.
     *
     * <p> The following is an example of a completion policy that collects the results
     * of tasks that complete successfully. It defines the method <b>{@code results()}</b>
     * to be used by the main task to retrieve the results.
     *
     * {@snippet lang=java :
     *     class MyPolicy<V> implements CompletionPolicy<V> {
     *         private final Queue<V> results = new ConcurrentLinkedQueue<>();
     *
     *         @Override
     *         public void handle(StructuredTaskScope<V> task scope, Future<V> future) {
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
     *         public Stream<V> results() {     // @highlight substring="results"
     *             return results.stream();
     *         }
     *     }
     * }
     *
     * @apiNote CompletionPolicy extends AutoCloseable to allow a completion policy be
     * declared in the resource specification of a try-with-resources statement.
     *
     * @param <T> the result type
     * @since 99
     */
    @FunctionalInterface
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public interface CompletionPolicy<T> extends AutoCloseable {
        /**
         * Handles a completed task.
         *
         * @param scope scope the task scope
         * @param future the Future for the completed task
         * @throws IllegalArgumentException if the task has not completed
         * @throws NullPointerException if task scope or future is {@code null}
         */
        void handle(StructuredTaskScope<T> scope, Future<T> future);

        /**
         * Does nothing.
         *
         * @implSpec The default implementation does nothing.
         */
        @Override
        default void close() { }

        /**
         * Returns a composed {@code CompletionPolicy} that performs, in sequence, a
         * {@code first} policy followed by a {@code second} policy. If performing
         * either policy throws an exception, it is relayed to the caller of the
         * composed policy. If performing the first policy throws an exception,
         * the {@code second} policy will not be performed.
         *
         * @param first the first policy
         * @param second the second policy
         * @param <V> the result type
         * @return a composed CompletionPolicy that performs in sequence the first
         * and second policies
         * @throws NullPointerException if first or second is {@code null}
         */
        static <V> CompletionPolicy<V> compose(CompletionPolicy<? extends V> first,
                                                CompletionPolicy<? extends V> second) {
            @SuppressWarnings("unchecked")
            var policy1 = (CompletionPolicy<V>) Objects.requireNonNull(first);
            @SuppressWarnings("unchecked")
            var policy2 = (CompletionPolicy<V>) Objects.requireNonNull(second);
            return (e, f) -> {
                policy1.handle(e, f);
                policy2.handle(e, f);
            };
        }
    }

    /**
     * A CompletionPolicy that captures the result of the first task to complete
     * successfully. Once captured, the policy {@linkplain #shutdown() shuts down}
     * the task scope to interrupt unfinished threads and wakeup the owner. This policy
     * is intended for cases where the result of any task will do ("invoke any") and
     * where the results of other unfinished tasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @param <T> the result type
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnSuccess<T> implements CompletionPolicy<T> {
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
        private volatile Future<T> firstSuccess;
        private volatile Future<T> firstFailed;
        private volatile Future<T> firstCancelled;

        /**
         * Creates a new ShutdownOnSuccess object.
         */
        public ShutdownOnSuccess() { }

        /**
         * Shutdown the given task scope when invoked for the first time with a task
         * that completed with a result.
         *
         * @param scope the task scope
         * @param future the completed task
         * @throws IllegalArgumentException {@inheritDoc}
         * @see #shutdown()
         * @see Future.State#SUCCESS
         */
        @Override
        public void handle(StructuredTaskScope<T> scope, Future<T> future) {
            Objects.requireNonNull(scope);
            switch (future.state()) {
                case RUNNING -> throw new IllegalArgumentException("Task is not completed");
                case SUCCESS -> {
                    // capture first task to complete normally
                    if (firstSuccess == null
                            && FIRST_SUCCESS.compareAndSet(this, null, future)) {
                        scope.shutdown();
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
        public T result() throws ExecutionException {
            Future<T> f = firstSuccess;
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
        public <X extends Throwable> T result(Function<Throwable, ? extends X> esf) throws X {
            Objects.requireNonNull(esf);
            Future<T> f = firstSuccess;
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
     * A CompletionPolicy that captures the exception of the first task to complete
     * abnormally. Once captured, the policy {@linkplain #shutdown() shuts down} the
     * task scope to interrupt unfinished threads and wakeup the owner. This policy is
     * intended for cases where the results for all tasks are required ("invoke all");
     * if any task fails then the results of other unfinished tasks are no longer needed.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a method
     * in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     */
    @PreviewFeature(feature = PreviewFeature.Feature.STRUCTURED_CONCURRENCY)
    public static final class ShutdownOnFailure implements CompletionPolicy<Object> {
        private static final VarHandle FIRST_FAILED;
        private static final VarHandle FIRST_CANCELLED;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                FIRST_FAILED = l.findVarHandle(ShutdownOnFailure.class, "firstFailed", Future.class);
                FIRST_CANCELLED = l.findVarHandle(ShutdownOnFailure.class, "firstCancelled", Future.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private volatile Future<Object> firstFailed;
        private volatile Future<Object> firstCancelled;

        /**
         * Creates a new ShutdownOnFailure object.
         */
        public ShutdownOnFailure() { }

        /**
         * Shutdown the given task scope when invoked for the first time with a task
         * that completed abnormally (exception or cancelled).
         *
         * @param scope the task scope
         * @param future the completed task
         * @throws IllegalArgumentException {@inheritDoc}
         * @see #shutdown()
         * @see Future.State#FAILED
         * @see Future.State#CANCELLED
         */
        @Override
        public void handle(StructuredTaskScope<Object> scope, Future<Object> future) {
            Objects.requireNonNull(scope);
            switch (future.state()) {
                case RUNNING -> throw new IllegalArgumentException("Task is not completed");
                case SUCCESS -> { }
                case FAILED -> {
                    if (firstFailed == null
                            && FIRST_FAILED.compareAndSet(this, null, future)) {
                        scope.shutdown();
                    }
                }
                case CANCELLED -> {
                    if (firstFailed == null && firstCancelled == null
                            && FIRST_CANCELLED.compareAndSet(this, null, future)) {
                        scope.shutdown();
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
            if (firstCancelled != null)
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
            if (firstCancelled != null)
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
            } else if (firstCancelled != null) {
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

/*
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

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * An {@link Executor} that provides methods to manage termination and
 * methods that can produce a {@link Future} for tracking progress of
 * one or more asynchronous tasks.
 *
 * <p>An {@code ExecutorService} can be shut down, which will cause
 * it to reject new tasks.  Two different methods are provided for
 * shutting down an {@code ExecutorService}. The {@link #shutdown}
 * method will allow previously submitted tasks to execute before
 * terminating, while the {@link #shutdownNow} method prevents waiting
 * tasks from starting and attempts to stop currently executing tasks.
 * Upon termination, an executor has no tasks actively executing, no
 * tasks awaiting execution, and no new tasks can be submitted.  An
 * unused {@code ExecutorService} should be shut down to allow
 * reclamation of its resources.
 *
 * <p>Method {@code submit} extends base method {@link
 * Executor#execute(Runnable)} by creating and returning a {@link Future}
 * that can be used to cancel execution and/or wait for completion.
 * Methods {@code invokeAny} and {@code invokeAll} perform the most
 * commonly useful forms of bulk execution, executing a collection of
 * tasks and then waiting for at least one, or all, to
 * complete. (Class {@link ExecutorCompletionService} can be used to
 * write customized variants of these methods.)
 *
 * <p>The {@link Executors} class provides factory methods for the
 * executor services provided in this package.
 *
 * <h2>Usage Examples</h2>
 *
 * Here is a sketch of a network service in which threads in a thread
 * pool service incoming requests. It uses the preconfigured {@link
 * Executors#newFixedThreadPool} factory method:
 *
 * <pre> {@code
 * class NetworkService implements Runnable {
 *   private final ServerSocket serverSocket;
 *   private final ExecutorService pool;
 *
 *   public NetworkService(int port, int poolSize)
 *       throws IOException {
 *     serverSocket = new ServerSocket(port);
 *     pool = Executors.newFixedThreadPool(poolSize);
 *   }
 *
 *   public void run() { // run the service
 *     try {
 *       for (;;) {
 *         pool.execute(new Handler(serverSocket.accept()));
 *       }
 *     } catch (IOException ex) {
 *       pool.shutdown();
 *     }
 *   }
 * }
 *
 * class Handler implements Runnable {
 *   private final Socket socket;
 *   Handler(Socket socket) { this.socket = socket; }
 *   public void run() {
 *     // read and service request on socket
 *   }
 * }}</pre>
 *
 * The following method shuts down an {@code ExecutorService} in two phases,
 * first by calling {@code shutdown} to reject incoming tasks, and then
 * calling {@code shutdownNow}, if necessary, to cancel any lingering tasks:
 *
 * <pre> {@code
 * void shutdownAndAwaitTermination(ExecutorService pool) {
 *   pool.shutdown(); // Disable new tasks from being submitted
 *   try {
 *     // Wait a while for existing tasks to terminate
 *     if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
 *       pool.shutdownNow(); // Cancel currently executing tasks
 *       // Wait a while for tasks to respond to being cancelled
 *       if (!pool.awaitTermination(60, TimeUnit.SECONDS))
 *           System.err.println("Pool did not terminate");
 *     }
 *   } catch (InterruptedException ex) {
 *     // (Re-)Cancel if current thread also interrupted
 *     pool.shutdownNow();
 *     // Preserve interrupt status
 *     Thread.currentThread().interrupt();
 *   }
 * }}</pre>
 *
 * <p>Memory consistency effects: Actions in a thread prior to the
 * submission of a {@code Runnable} or {@code Callable} task to an
 * {@code ExecutorService}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * any actions taken by that task, which in turn <i>happen-before</i> the
 * result is retrieved via {@code Future.get()}.
 *
 * @since 1.5
 * @author Doug Lea
 */
public interface ExecutorService extends Executor, AutoCloseable {

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * @throws SecurityException if a security manager exists and
     *         shutting down this ExecutorService may manipulate
     *         threads that the caller is not permitted to modify
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")},
     *         or the security manager's {@code checkAccess} method
     *         denies access.
     */
    void shutdown();

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  For example, typical
     * implementations will cancel via {@link Thread#interrupt}, so any
     * task that fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution
     * @throws SecurityException if a security manager exists and
     *         shutting down this ExecutorService may manipulate
     *         threads that the caller is not permitted to modify
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")},
     *         or the security manager's {@code checkAccess} method
     *         denies access.
     */
    List<Runnable> shutdownNow();

    /**
     * Returns {@code true} if this executor has been shut down.
     *
     * @return {@code true} if this executor has been shut down
     */
    boolean isShutdown();

    /**
     * Returns {@code true} if all tasks have completed following shut down.
     * Note that {@code isTerminated} is never {@code true} unless
     * either {@code shutdown} or {@code shutdownNow} was called first.
     *
     * @return {@code true} if all tasks have completed following shut down
     */
    boolean isTerminated();

    /**
     * Blocks until all tasks have completed execution after a shutdown
     * request, or the timeout occurs, or the current thread is
     * interrupted, whichever happens first.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated and
     *         {@code false} if the timeout elapsed before termination
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException;

    /**
     * Submits a value-returning task for execution and returns a
     * Future representing the pending results of the task. The
     * Future's {@code get} method will return the task's result upon
     * successful completion.
     *
     * <p>
     * If you would like to immediately block waiting
     * for a task, you can use constructions of the form
     * {@code result = exec.submit(aCallable).get();}
     *
     * <p>Note: The {@link Executors} class includes a set of methods
     * that can convert some other common closure-like objects,
     * for example, {@link java.security.PrivilegedAction} to
     * {@link Callable} form so they can be submitted.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     * @throws NullPointerException if the task is null
     */
    <T> Future<T> submit(Callable<T> task);

    /**
     * Submits a Runnable task for execution and returns a Future
     * representing that task. The Future's {@code get} method will
     * return the given result upon successful completion.
     *
     * @param task the task to submit
     * @param result the result to return
     * @param <T> the type of the result
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     * @throws NullPointerException if the task is null
     */
    <T> Future<T> submit(Runnable task, T result);

    /**
     * Submits a Runnable task for execution and returns a Future
     * representing that task. The Future's {@code get} method will
     * return {@code null} upon <em>successful</em> completion.
     *
     * @param task the task to submit
     * @return a Future representing pending completion of the task
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     * @throws NullPointerException if the task is null
     */
    Future<?> submit(Runnable task);

    /**
     * Submits the given value-returning tasks for execution and returns a
     * lazily populated stream of completed Future objects with the result
     * of each task.
     *
     * <p> Invoking the stream's {@linkplain Stream#close() close()} method
     * cancels any remaining tasks as if by invoking {@linkplain
     * Future#cancel(boolean) cancel(true)} on each remaining task.
     *
     * <p> If a thread is interrupted while waiting on the stream for a task to
     * complete then the remaining tasks are cancelled, as if by invoking
     * {@linkplain Future#cancel(boolean) cancel(true)}, and {@link
     * CancellationException} is thrown with the interrupt status set.
     *
     * @implSpec
     * The default implementation {@link #submit(Callable) submits} the tasks
     * for execution and returns a stream that is lazily populated when the
     * tasks complete.
     *
     * @apiNote This method is not atomic. RejectedExecutionException may
     * be thrown after some tasks have been submitted for execution. This
     * method makes a best effort attempt to cancel the tasks that it
     * submitted when RejectedExecutionException is thrown.
     *
     * <p> The following example invokes {@code submit} with a collection of
     * tasks and performs an action on the result of each task that completes
     * normally.
     * <pre> {@code
     *     ExecutorService executor = ...
     *     Collection<Callable<...>> tasks = ...
     *     executor.submit(tasks)
     *                 .filter(Future::isCompletedNormally)
     *                 .map(Future::join)
     *                 .forEach(result -> { });
     * }</pre>
     *
     * @param tasks the collection of tasks
     * @param <T> the type of the values returned from the tasks
     * @return stream of completed Futures
     * @throws RejectedExecutionException if any task cannot be
     *         scheduled for execution
     * @throws NullPointerException if tasks or any of its elements are {@code null}
     * @see CompletionService
     * @since 99
     */
    default <T> Stream<Future<T>> submit(Collection<? extends Callable<T>> tasks) {
        return ExecutorServiceHelper.submit(this, tasks);
    }

    /**
     * Executes the given tasks, returning a list of Futures holding
     * their status and results when all complete.
     * {@link Future#isDone} is {@code true} for each
     * element of the returned list.
     * Note that a <em>completed</em> task could have
     * terminated either normally or by throwing an exception.
     * The results of this method are undefined if the given
     * collection is modified while this operation is in progress.
     *
     * @apiNote This method is equivalent to invoking {@linkplain
     * #invokeAll(Collection, boolean)} with {@code cancelOnException} set
     * to {@code false}.
     *
     * @param tasks the collection of tasks
     * @param <T> the type of the values returned from the tasks
     * @return a list of Futures representing the tasks, in the same
     *         sequential order as produced by the iterator for the
     *         given task list, each of which has completed
     * @throws InterruptedException if interrupted while waiting, in
     *         which case unfinished tasks are cancelled
     * @throws NullPointerException if tasks or any of its elements are {@code null}
     * @throws RejectedExecutionException if any task cannot be
     *         scheduled for execution
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException;

    /**
     * Executes the given tasks, returning a list of Futures holding
     * their status and results when all complete. {@link Future#isDone} is
     * {@code true} for each element of the returned list.
     *
     * <p> The parameter {@code cancelOnException} determines if this
     * method should wait for unfinished tasks to complete when a task
     * completes with an exception. If {@code true}, unfinished tasks are
     * cancelled, as if by invoking {@code cancel(true)}, when any task
     * completes with an exception.
     *
     * @implSpec
     * The default implementation {@link #submit(Callable) submits} the tasks
     * for execution. It then waits until all tasks have completed, or in the
     * case that {@code cancelOnException} is true, that a task completes with
     * an exception.
     *
     * @param tasks the collection of tasks
     * @param cancelOnException true to cancel unfinished tasks when
     *         any task fails
     * @param <T> the type of the values returned from the tasks
     * @return a list of Futures representing the tasks, in the same
     *         sequential order as produced by the iterator for the
     *         given task list, each of which has completed
     * @throws InterruptedException if interrupted while waiting, in
     *         which case unfinished tasks are cancelled
     * @throws NullPointerException if tasks or any of its elements are {@code null}
     * @throws RejectedExecutionException if any task cannot be
     *         scheduled for execution
     * @since 99
     */
    default <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                          boolean cancelOnException)
            throws InterruptedException {
        return ExecutorServiceHelper.invokeAll(this, tasks, cancelOnException);
    }

    /**
     * Executes the given tasks, returning a list of Futures holding
     * their status and results
     * when all complete or the timeout expires, whichever happens first.
     * {@link Future#isDone} is {@code true} for each
     * element of the returned list.
     * Upon return, tasks that have not completed are cancelled.
     * Note that a <em>completed</em> task could have
     * terminated either normally or by throwing an exception.
     * The results of this method are undefined if the given
     * collection is modified while this operation is in progress.
     *
     * @param tasks the collection of tasks
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @param <T> the type of the values returned from the tasks
     * @return a list of Futures representing the tasks, in the same
     *         sequential order as produced by the iterator for the
     *         given task list. If the operation did not time out,
     *         each task will have completed. If it did time out, some
     *         of these tasks will not have completed.
     * @throws InterruptedException if interrupted while waiting, in
     *         which case unfinished tasks are cancelled
     * @throws NullPointerException if tasks, any of its elements, or
     *         unit are {@code null}
     * @throws RejectedExecutionException if any task cannot be scheduled
     *         for execution
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                  long timeout, TimeUnit unit)
        throws InterruptedException;

    /**
     * Executes the given tasks, returning the result
     * of one that has completed successfully (i.e., without throwing
     * an exception), if any do. Upon normal or exceptional return,
     * tasks that have not completed are cancelled.
     * The results of this method are undefined if the given
     * collection is modified while this operation is in progress.
     *
     * @param tasks the collection of tasks
     * @param <T> the type of the values returned from the tasks
     * @return the result returned by one of the tasks
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if tasks or any element task
     *         subject to execution is {@code null}
     * @throws IllegalArgumentException if tasks is empty
     * @throws ExecutionException if no task successfully completes
     * @throws RejectedExecutionException if tasks cannot be scheduled
     *         for execution
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException;

    /**
     * Executes the given tasks, returning the result
     * of one that has completed successfully (i.e., without throwing
     * an exception), if any do before the given timeout elapses.
     * Upon normal or exceptional return, tasks that have not
     * completed are cancelled.
     * The results of this method are undefined if the given
     * collection is modified while this operation is in progress.
     *
     * @param tasks the collection of tasks
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @param <T> the type of the values returned from the tasks
     * @return the result returned by one of the tasks
     * @throws InterruptedException if interrupted while waiting
     * @throws NullPointerException if tasks, or unit, or any element
     *         task subject to execution is {@code null}
     * @throws TimeoutException if the given timeout elapses before
     *         any task successfully completes
     * @throws ExecutionException if no task successfully completes
     * @throws RejectedExecutionException if tasks cannot be scheduled
     *         for execution
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                    long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;

    /**
     * Initiates an orderly shutdown in which previously submitted tasks are
     * executed, but no new tasks will be accepted. This method waits until all
     * tasks have completed execution.
     *
     * <p> If interrupted while waiting, this method stops all executing tasks as
     * if by invoking {@link #shutdownNow()}. It then continues to wait until all
     * actively executing tasks have completed. Tasks that were awaiting
     * execution are not executed. The interrupt status will be re-asserted
     * before this method returns.
     *
     * <p> If already terminated, invoking this method has no effect.
     *
     * @implSpec
     * The default implementation invokes {@code shutdown()} and waits for tasks
     * to complete execution with {@code awaitTermination}.
     *
     * @throws SecurityException if a security manager exists and
     *         shutting down this ExecutorService may manipulate
     *         threads that the caller is not permitted to modify
     *         because it does not hold {@link
     *         java.lang.RuntimePermission}{@code ("modifyThread")},
     *         or the security manager's {@code checkAccess} method
     *         denies access.
     * @since 99
     */
    @Override
    default void close() {
        boolean terminated = isTerminated();
        if (!terminated) {
            shutdown();
            boolean interrupted = false;
            while (!terminated) {
                try {
                    terminated = awaitTermination(1L, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    if (!interrupted) {
                        shutdownNow();
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns an Executor that attempts to stop all tasks executing if a
     * deadline is reached before it has terminated. The newly created Executor
     * delegates all operations to this Executor. If the deadline is reached
     * before the Executor has terminated then it is shutdown, as if by
     * invoking {@link #shutdownNow()}. The {@code shutdownNow()} method
     * may be invoked on a thread supporting the deadline mechanism.
     *
     * <p> If this method is invoked with a deadline that has already expired
     * then its {@code shutdownNow()} method is invoked immediately. If the
     * deadline has already expired or the executor has already terminated
     * then this Executor is returned (a new Executor is not created).
     *
     * @implSpec
     * The default implementation schedules a task to run when the deadline
     * expires. The task invokes the {@code shutdownNow()} method to attempt
     * to stop all executing tasks.
     *
     * @param deadline the deadline
     * @return a new Executor that delegates operations to this Executor
     * @throws NullPointerException if deadline is null
     * @throws SecurityException if a security manager exists and it denies
     *         {@link java.lang.RuntimePermission}{@code ("modifyThread")}.
     * @since 99
     */
//    default ExecutorService withDeadline(Instant deadline) {
//        return TimedExecutorService.create(this, deadline);
//    }

    /**
     * Submits a value-returning task for execution and returns a
     * CompletableFuture representing the pending result of the task.
     *
     * @implSpec
     * The default implementation invokes the {@linkplain #execute(Runnable)
     * execute(Runnable)} method with a task that completes the {@code
     * CompletableFuture} when the given task completes.
     *
     * @param task the task to submit
     * @param <T> the type of the task's result
     * @return a CompletableFuture representing pending completion of the task
     * @throws RejectedExecutionException if the task cannot be
     *         scheduled for execution
     * @throws NullPointerException if the task is null
     * @since 99
     */
//    default <T> CompletableFuture<T> submitTask(Callable<T> task) {
//        class RunnableCompletableFuture<T>
//                extends CompletableFuture<T> implements RunnableFuture<T> {
//            private final Callable<T> task;
//            RunnableCompletableFuture(Callable<T> task) {
//                this.task = Objects.requireNonNull(task);
//            }
//            public void run() {
//                try {
//                    T result = task.call();
//                    complete(result);
//                } catch (Throwable e) {
//                    completeExceptionally(e);
//                }
//            }
//        }
//        var future = new RunnableCompletableFuture<>(task);
//        execute(future);
//        return future;
//    }

    /**
     * Submits the given value-returning tasks for execution and returns a list
     * of CompletableFutures representing the pending results of the tasks.
     *
     * @implSpec
     * The default implementation invokes {@link #submitTask(Callable)} for each
     * task and returns a list of the resulting CompletableFutures objects. If
     * {@code submitTask} fails then it cancels the {@code CompletableFuture}
     * objects corresponding to the tasks submitted prior to the failure.
     *
     * @apiNote This method is not atomic. RejectedExecutionException may
     * be thrown after some tasks have been submitted for execution. This
     * method makes a best effort attempt to cancel the tasks that it
     * submitted when RejectedExecutionException is thrown.
     *
     * @param tasks the collection of tasks
     * @param <T> the type of the values returned from the tasks
     * @return a list of CompletableFuture representing the tasks, in the same
     *         sequential order as produced by the iterator for the
     *         given collection of tasks
     * @throws RejectedExecutionException if any task cannot be
     *         scheduled for execution
     * @throws NullPointerException if tasks or any of its elements are {@code null}
     * @since 99
     * @see CompletableFuture#completed(Collection)
     * @see CompletableFuture#completed(CompletableFuture[])
     */
//    default <T> List<CompletableFuture<T>> submitTasks(Collection<? extends Callable<T>> tasks) {
//        List<CompletableFuture<T>> futures = new ArrayList<>();
//        try {
//            for (Callable<T> t : tasks) {
//                futures.add(submitTask(t));
//            }
//            return futures;
//        } catch (Throwable e) {
//            for (Future<T> f : futures) {
//                if (!f.isDone()) {
//                    f.cancel(true);
//                }
//            }
//            throw e;
//        }
//    }
}
/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * An ExecutorService that executes each task in its own thread.
 */
class ThreadExecutor implements ExecutorService {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final VarHandle STATE;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(ThreadExecutor.class, "state", int.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private final ThreadFactory factory;
    private final Lifetime lifetime = Lifetime.start();   // experimental
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final ReentrantLock terminationLock = new ReentrantLock();
    private final Condition terminationCondition = terminationLock.newCondition();

    // states: RUNNING -> SHUTDOWN -> TERMINATED
    private static final int RUNNING    = 0;
    private static final int SHUTDOWN   = 1;
    private static final int TERMINATED = 2;
    private volatile int state;

    public ThreadExecutor(ThreadFactory factory) {
        Objects.requireNonNull(factory);
        this.factory = factory;
    }

    /**
     * Throws RejectedExecutionException if the executor has been shutdown.
     */
    private void ensureNotShutdown() {
        if (state >= SHUTDOWN) {
            // shutdown or terminated
            throw new RejectedExecutionException();
        }
    }

    /**
     * Sets the state to TERMINATED if there are no remaining threads.
     */
    private boolean tryTerminate() {
        assert state >= SHUTDOWN;
        if (threads.isEmpty()) {
            terminationLock.lock();
            try {
                STATE.set(this, TERMINATED);
                terminationCondition.signalAll();
            } finally {
                terminationLock.unlock();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sets the state to SHUTDOWN and attempts to terminate if not already shutdown
     * @throws SecurityException if denied by security manager
     */
    private void tryShutdownAndTerminate() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("modifyThread"));
        }
        if (STATE.compareAndSet(this, RUNNING, SHUTDOWN)) {
            tryTerminate();
        }
    }

    @Override
    public void shutdown() {
        tryShutdownAndTerminate();
    }

    @Override
    public List<Runnable> shutdownNow() {
        tryShutdownAndTerminate();
        threads.forEach(Thread::interrupt);
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state >= TERMINATED;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        if (isTerminated()) {
            return true;
        } else {
            terminationLock.lock();
            try {
                if (!isTerminated()) {
                    terminationCondition.await(timeout, unit);
                }
            } finally {
                terminationLock.unlock();
            }
            return isTerminated();
        }
    }

    @Override
    public void close() {
        try {
            ExecutorService.super.close(); // waits for executor to terminate
        } finally {
            lifetime.close();
        }
    }

    /**
     * Creates a thread to run the given task.
     */
    private Thread newThread(Runnable task) {
        Thread thread = factory.newThread(task);
        JLA.unsafeSetLifetime(thread, lifetime);  // experimental
        return thread;
    }

    /**
     * Notify the executor that the task executed by the given thread is complete.
     * If the executor has been shutdown then this method will attempt to terminate
     * the executor.
     */
    private void taskComplete(Thread thread) {
        boolean removed = threads.remove(thread);
        assert removed;
        if (state == SHUTDOWN) {
            tryTerminate();
        }
    }

    /**
     * Adds a thread to the set of threads and starts it.
     * @throws RejectedExecutionException
     */
    private void start(Thread thread) {
        assert thread.getState() == Thread.State.NEW;
        threads.add(thread);

        boolean started = false;
        try {
            if (state == RUNNING) {
                thread.start();
                started = true;
            }
        } finally {
            if (!started) {
                taskComplete(thread);
            }
        }

        // throw REE if thread not started and no exception thrown
        if (!started) {
            throw new RejectedExecutionException();
        }
    }

    /**
     * Starts a thread to execute the given task.
     * @throws RejectedExecutionException
     */
    private Thread start(Runnable task) {
        Objects.requireNonNull(task);
        ensureNotShutdown();
        Thread thread = newThread(new TaskRunner(this, task));
        if (thread == null)
            throw new RejectedExecutionException();
        start(thread);
        return thread;
    }

    @Override
    public void execute(Runnable task) {
        start(task);
    }

    @Override
    public <T> CompletableFuture<T> submitTask(Callable<T> task) {
        Objects.requireNonNull(task);
        ensureNotShutdown();
        var future = new ThreadBoundCompletableFuture<>(this, task);
        Thread thread = future.thread();
        if (thread == null)
            throw new RejectedExecutionException();
        start(thread);
        return future;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return submitTask(task);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return submitTask(Executors.callable(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return submitTask(Executors.callable(task, result));
    }

    /**
     * Runs a task and notifies a ThreadExecutor when it completes.
     */
    private static class TaskRunner implements Runnable {
        final ThreadExecutor executor;
        final Runnable task;
        TaskRunner(ThreadExecutor executor, Runnable task) {
            this.executor = executor;
            this.task = task;
        }
        @Override
        public void run() {
            try {
                task.run();
            } finally {
                executor.taskComplete(Thread.currentThread());
            }
        }
    }

    /**
     * A CompletableFuture for a task that runs in its own thread. The thread
     * is created (but not started) when the CompletableFuture is created. The
     * thread is interrupted when the future is cancelled. Its ThreadExecutor
     * is notified when the task completes.
     */
    private static class ThreadBoundCompletableFuture<T>
            extends CompletableFuture<T> implements Runnable {

        final ThreadExecutor executor;
        final Callable<T> task;
        final Thread thread;

        ThreadBoundCompletableFuture(ThreadExecutor executor, Callable<T> task) {
            this.executor = executor;
            this.task = task;
            this.thread = executor.newThread(this);
        }

        Thread thread() {
            return thread;
        }

        @Override
        public void run() {
            if (Thread.currentThread() != thread) {
                // should not happen except where something casts this object
                // to a Runnable and invokes the run method.
                throw new IllegalCallerException();
            }
            try {
                T result = task.call();
                complete(result);
            } catch (Throwable e) {
                completeExceptionally(e);
            } finally {
                executor.taskComplete(thread);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (mayInterruptIfRunning)
                thread.interrupt();
            return cancelled;
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {

        Objects.requireNonNull(tasks);
        List<Future<T>> futures = new ArrayList<>();
        int j = 0;
        try {
            for (Callable<T> t : tasks) {
                Future<T> f = submit(t);
                futures.add(f);
            }
            for (int size = futures.size(); j < size; j++) {
                Future<T> f = futures.get(j);
                if (!f.isDone()) {
                    try {
                        f.get();
                    } catch (ExecutionException | CancellationException ignore) { }
                }
            }
            return futures;
        } finally {
            cancelAll(futures, j);
        }
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
            throws InterruptedException {

        Objects.requireNonNull(tasks);
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Future<T>> futures = new ArrayList<>();
        int j = 0;
        try {
            for (Callable<T> t : tasks) {
                Future<T> f = submit(t);
                futures.add(f);
            }
            for (int size = futures.size(); j < size; j++) {
                Future<T> f = futures.get(j);
                if (!f.isDone()) {
                    try {
                        f.get(deadline - System.nanoTime(), NANOSECONDS);
                    } catch (TimeoutException e) {
                        break;
                    } catch (ExecutionException | CancellationException ignore) { }
                }
            }
            return futures;
        } finally {
            cancelAll(futures, j);
        }
    }

    private <T> void cancelAll(List<Future<T>> futures, int j) {
        for (int size = futures.size(); j < size; j++)
            futures.get(j).cancel(true);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        try {
            return invokeAny(tasks, false, 0, null);
        } catch (TimeoutException e) {
            // should not happen
            throw new InternalError(e);
        }
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        return invokeAny(tasks, true, timeout, unit);
    }

    private <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                            boolean timed,
                            long timeout,
                            TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {

        int size = tasks.size();
        if (size == 0) {
            throw new IllegalArgumentException();
        }

        var holder = new AnyResultHolder<T>(Thread.currentThread());
        var threadList = new ArrayList<Thread>(size);
        long nanos = (timed) ? unit.toNanos(timeout) : 0;
        long startNanos = (timed) ? System.nanoTime() : 0;

        try {
            int count = 0;
            Iterator<? extends Callable<T>> iterator = tasks.iterator();
            while (count < size && iterator.hasNext()) {
                Callable<T> task = iterator.next();
                Objects.requireNonNull(task);
                Thread thread = start(() -> {
                    try {
                        T r = task.call();
                        holder.complete(r);
                    } catch (Throwable e) {
                        holder.completeExceptionally(e);
                    }
                });
                threadList.add(thread);
                count++;
            }
            if (count == 0) {
                throw new IllegalArgumentException();
            }

            if (Thread.interrupted())
                throw new InterruptedException();
            T result = holder.result();
            while (result == null && holder.exceptionCount() < count) {
                if (timed) {
                    long remainingNanos = nanos - (System.nanoTime() - startNanos);
                    if (remainingNanos <= 0)
                        throw new TimeoutException();
                    LockSupport.parkNanos(remainingNanos);
                } else {
                    LockSupport.park();
                }
                if (Thread.interrupted())
                    throw new InterruptedException();
                result = holder.result();
            }

            if (result != null) {
                return (result != AnyResultHolder.NULL) ? result : null;
            } else {
                throw new ExecutionException(holder.firstException());
            }

        } finally {
            // interrupt any threads that are still running
            for (Thread t : threadList) {
                if (t.isAlive()) {
                    t.interrupt();
                }
            }
        }
    }

    /**
     * An object for use by invokeAny to hold the result of the first task
     * to complete normally and/or the first exception thrown. The object
     * also maintains a count of the number of tasks that attempted to
     * complete up to when the first tasks completes normally.
     */
    static class AnyResultHolder<T> {
        private static final VarHandle RESULT;
        private static final VarHandle EXCEPTION;
        private static final VarHandle EXCEPTION_COUNT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                RESULT = l.findVarHandle(AnyResultHolder.class, "result", Object.class);
                EXCEPTION = l.findVarHandle(AnyResultHolder.class, "exception", Throwable.class);
                EXCEPTION_COUNT = l.findVarHandle(AnyResultHolder.class, "exceptionCount", int.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private static final Object NULL = new Object();

        private final Thread owner;
        private volatile T result;
        private volatile Throwable exception;
        private volatile int exceptionCount;

        AnyResultHolder(Thread owner) {
            this.owner = owner;
        }

        /**
         * Complete with the given result if not already completed. The winner
         * unparks the owner thread.
         */
        void complete(T value) {
            @SuppressWarnings("unchecked")
            T v = (value != null) ? value : (T) NULL;
            if (result == null && RESULT.compareAndSet(this, null, v)) {
                LockSupport.unpark(owner);
            }
        }

        /**
         * Complete with the given exception. If the result is not already
         * set then it unparks the owner thread.
         */
        void completeExceptionally(Throwable exc) {
            if (result == null) {
                if (exception == null)
                    EXCEPTION.compareAndSet(this, null, exc);
                EXCEPTION_COUNT.getAndAdd(this, 1);
                LockSupport.unpark(owner);
            }
        }

        /**
         * Returns non-null if a task completed successfully. The result is
         * NULL if completed with null.
         */
        T result() {
            return result;
        }

        /**
         * Returns the first exception thrown if recorded by this object.
         *
         * @apiNote The result() method should be used to test if there is
         * a result before invoking the exception method.
         */
        Throwable firstException() {
            return exception;
        }

        /**
         * Returns the number of tasks that terminated with an exception before
         * a task completed normally.
         */
        int exceptionCount() {
            return exceptionCount;
        }
    }
}

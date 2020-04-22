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

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ExecutorService that executes each task in its own thread. Threads are not
 * re-used and the number of threads/tasks is unbounded.
 *
 * This is a inefficient/simple implementation for now, it will likely be replaced.
 */
class UnboundedExecutor extends AbstractExecutorService {
    private static final VarHandle STATE;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(UnboundedExecutor.class, "state", int.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private final ThreadFactory factory;
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final ReentrantLock terminationLock = new ReentrantLock();
    private final Condition terminationCondition = terminationLock.newCondition();

    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    // states: RUNNING -> SHUTDOWN -> TERMINATED
    private static final int RUNNING    = 0;
    private static final int SHUTDOWN   = 1;
    private static final int TERMINATED = 2;
    private volatile int state;

    private final Lifetime lifetime;

    public UnboundedExecutor(ThreadFactory factory) {
        Objects.requireNonNull(factory);
        this.lifetime = Lifetime.start();
        this.factory = task -> {
            Thread t = factory.newThread(task);
            JLA.unsafeSetLifetime(t, lifetime);
            return t;
        };
    }

    public void close() {
        super.close(); // waits for all threads to terminate
        lifetime.close();
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

    /**
     * Removes the thread from the set of threads and attempts to terminate
     * the executor if shutdown but not terminated.
     */
    private void onTerminate(Thread thread) {
        threads.remove(thread);
        if (state == SHUTDOWN) {
            tryTerminate();
        }
    }

    private void onTerminate() {
        onTerminate(Thread.currentThread());
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

    private void ensureNotShutdown() {
        if (state >= SHUTDOWN) {
            // shutdown or terminated
            throwRejectedExecutionException();
        }
    }

    private void start(Thread thread) {
        threads.add(thread);
        boolean started = false;
        try {
            if (state == RUNNING) {
                thread.start();
                started = true;
            }
        } finally {
            if (!started) {
                onTerminate(thread);
                throwRejectedExecutionException();
            }
        }
    }

    private Thread fork(Runnable task) {
        Objects.requireNonNull(task);
        ensureNotShutdown();
        Thread thread = factory.newThread(() -> {
            try {
                task.run();
            } finally {
                onTerminate();
            }
        });
        start(thread);
        return thread;
    }

    @Override
    public void execute(Runnable task) {
        fork(task);
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
        var threads = ConcurrentHashMap.<Thread>newKeySet();
        long nanos = (timed) ? TimeUnit.NANOSECONDS.convert(timeout, unit) : 0;
        long startNanos = (timed) ? System.nanoTime() : 0;

        try {
            int count = 0;
            Iterator<? extends Callable<T>> iterator = tasks.iterator();
            while (count < size && iterator.hasNext()) {
                Callable<T> task = iterator.next();
                Objects.requireNonNull(task);
                Thread thread = fork(() -> {
                    try {
                        T r = task.call();
                        holder.complete(r);
                    } catch (Throwable e) {
                        holder.completeExceptionally(e);
                    }
                });
                threads.add(thread);
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
            threads.forEach(Thread::interrupt);
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
         * Returns non-null if a task completed successfully. The result is NULL_RESULT
         * if completed with null.
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

    @Override
    public <T> CompletableFuture<T> submitTask(Callable<T> task) {
        Objects.requireNonNull(task);
        ensureNotShutdown();

        class Runner extends CompletableFuture<T> implements Runnable {
            final Thread thread;

            Runner() {
                thread = factory.newThread(this);
            }

            Thread thread() {
                return thread;
            }

            @Override
            public void run() {
                if (Thread.currentThread() != thread) {
                    // should not happen
                    throw new IllegalCallerException();
                }
                try {
                    T result = task.call();
                    complete(result);
                } catch (Throwable e) {
                    completeExceptionally(e);
                } finally {
                    onTerminate();
                }
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (mayInterruptIfRunning)
                    thread.interrupt();
                return super.cancel(mayInterruptIfRunning);
            }
        }

        var runner = new Runner();
        start(runner.thread());
        return runner;
    }

    private static void throwRejectedExecutionException() {
        throw new RejectedExecutionException();
    }
}
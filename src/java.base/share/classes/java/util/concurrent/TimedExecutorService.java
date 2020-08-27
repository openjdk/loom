/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import jdk.internal.misc.InnocuousThread;

/**
 * A delegating ExecutorService that stops all tasks executing and interrupts
 * the "owner" if a deadline is reached before the Executor has terminated.
 */
class TimedExecutorService implements ExecutorService {
    private final ExecutorService delegate;
    private final Instant deadline;
    private final Thread owner;
    private final Future<?> timerTask;

    // used to coordinate timer cancellation with close/awaitTermination
    private final Object lock = new Object();
    private volatile boolean cancelled;

    private TimedExecutorService(ExecutorService delegate,
                                 Instant deadline,
                                 Duration timeout) {
        this.delegate = delegate;
        this.deadline = deadline;
        this.owner = Thread.currentThread();

        long nanos = NANOSECONDS.convert(timeout);
        this.timerTask = STPE.schedule(this::shutdownAndInterrupt, nanos, NANOSECONDS);
    }

    /**
     * Returns an Executor that stops all tasks executing, and interrupts the
     * current thread, if a deadline is reached before the Executor has terminated.
     * The newly created Executor delegates all operations to the given Executor.
     *
     * <p> If this method is invoked with a deadline that has already expired
     * then the {@code shutdownNow()} method is invoked immediately and the owner
     * is interrupted. If the deadline has already expired or the executor has
     * already terminated then the given Executor is returned (a new Executor is
     * not created).
     */
    static ExecutorService create(ExecutorService delegate, Instant deadline) {
        Duration timeout = Duration.between(Instant.now(), deadline);

        // need same permission as shutdownNow
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(MODIFY_THREAD);
        }

        // deadline has already expired
        if (timeout.isZero() || timeout.isNegative()) {
            delegate.shutdownNow();
            Thread.currentThread().interrupt();
            return delegate;
        }

        // nothing to do
        if (delegate.isTerminated()) {
            return delegate;
        }

        return new TimedExecutorService(delegate, deadline, timeout);
    }

    /**
     * Invoked when the deadline is reached.
     */
    private Void shutdownAndInterrupt() {
        if (!delegate.isTerminated()) {
            // timer task needs permission to invoke shutdownNow
            PrivilegedAction<?> pa = delegate::shutdownNow;
            AccessController.doPrivileged(pa, null, MODIFY_THREAD);

            // do not interrupt if owner thread has cancelled timer
            synchronized (lock) {
                if (!cancelled) {
                    owner.interrupt();
                }
            }
        }
        return null;
    }

    /**
     * Invoked by the owner thread to cancel the timer task.
     */
    private void cancelTimer() {
        assert isTerminated() && Thread.currentThread() == owner;
        synchronized (lock) {
            cancelled = true;
        }
        if (!timerTask.isDone()) {
            timerTask.cancel(false);
        }
    }

    /**
     * Returns true if the deadline has been reached.
     */
    private boolean isDeadlineReached() {
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isZero() || remaining.isNegative();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {

        Objects.requireNonNull(unit);
        boolean terminated = isTerminated();
        if (terminated) {
            return true;
        } else if (Thread.currentThread() != owner) {
            return delegate.awaitTermination(timeout, unit);
        }

        // Owner waits for termination. If the deadline is reached while
        // waiting then it needs to coordinate with the timer task to ensure
        // this method throws InterruptedException.
        terminated = delegate.awaitTermination(timeout, unit);
        if (terminated && isDeadlineReached()) {
            cancelTimer();
            Thread.interrupted(); // clear interrupt status
            throw new InterruptedException();
        }
        return terminated;
    }

    @Override
    public void close() {
        boolean terminated = isTerminated();
        if (terminated) {
            return;
        } else if (Thread.currentThread() != owner) {
            delegate.close();
            return;
        }

        // Owner waits for termination. If the deadline is reached while
        // waiting then it needs to coordinate with the timer task to ensure
        // that this method completes with the interrupt status set.
        shutdown();
        boolean interrupted = false;
        while (!terminated) {
            try {
                terminated = delegate.awaitTermination(1L, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                if (!interrupted) {
                    delegate.shutdownNow();  // interrupt running tasks
                    interrupted = true;
                }
            }
        }
        cancelTimer();
        if (interrupted || isDeadlineReached()) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
        if (isTerminated() && Thread.currentThread() == owner) {
            // timer no longer needed
            cancelTimer();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks = delegate.shutdownNow();
        if (isTerminated() && Thread.currentThread() == owner) {
            // timer no longer needed
            cancelTimer();
        }
        return tasks;
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout,TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout,
                           TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    private static final Permission MODIFY_THREAD = new RuntimePermission("modifyThread");

    private static final ScheduledThreadPoolExecutor STPE;
    static {
        STPE = new ScheduledThreadPoolExecutor(0, task -> {
            Thread thread = InnocuousThread.newThread(task);
            thread.setDaemon(true);
            return thread;
        });
        STPE.setRemoveOnCancelPolicy(true);
    }
}

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
import jdk.internal.misc.InnocuousThread;

/**
 * A delegating ExecutorService that stops all tasks executing if a deadline
 * is reached before the Executor has terminated.
 */
class TimedExecutorService implements ExecutorService {
    private final ExecutorService delegate;
    private final Future<?> timerTask;

    private TimedExecutorService(ExecutorService delegate, Duration timeout) {
        this.delegate = delegate;
        long nanos = TimeUnit.NANOSECONDS.convert(timeout);
        this.timerTask = STPE.schedule(this::timeout, nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Returns an Executor that stops all tasks executing if a deadline is
     * reached before the Executor has terminated.
     *
     * <p> If this method is invoked with a deadline that has already expired
     * then its {@code shutdownNow()} method is invoked and returned (a new
     * executor is not created).
     */
    static ExecutorService create(ExecutorService delegate, Instant deadline) {
        Duration timeout = Duration.between(Instant.now(), deadline);

        // deadline has already expired
        if (timeout.isZero() || timeout.isNegative()) {
            delegate.shutdownNow();  // may throw security exception
            return delegate;
        }

        // need same permission as shutdownNow
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(MODIFY_THREAD);
        }

        if (delegate.isTerminated()) {
            return delegate;
        } else {
            return new TimedExecutorService(delegate, timeout);
        }
    }

    /**
     * Invoked when the timeout is reached.
     */
    private void timeout() {
        if (!delegate.isTerminated()) {
            // timer task needs permission to invoke shutdownNow
            PrivilegedAction<List<Runnable>> pa = delegate::shutdownNow;
            List<Runnable> tasks = AccessController.doPrivileged(pa, null, MODIFY_THREAD);

            // cancel any of the Future tasks that didn't execute
            for (Runnable task : tasks) {
                if (task instanceof Future) {
                    ((Future<?>) task).cancel(false);
                }
            }
        }
    }

    /**
     * Cancels the timer if not already cancelled.
     */
    private void cancelTimer() {
        if (!timerTask.isDone()) {
            timerTask.cancel(false);
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        boolean terminated = delegate.awaitTermination(timeout, unit);
        if (terminated) {
            cancelTimer();
        }
        return terminated;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
        if (isTerminated()) {
            cancelTimer();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks = delegate.shutdownNow();
        if (isTerminated()) {
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

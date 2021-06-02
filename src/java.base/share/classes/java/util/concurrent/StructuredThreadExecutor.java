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

import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.InnocuousThread;
import jdk.internal.vm.ThreadContainer;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * An ExecutorService intended to be used in a <em>structured manner</em> that
 * starts a new thread for each task. It may be created with a deadline.
 */
class StructuredThreadExecutor
        extends ThreadExecutor implements StructuredExecutorService {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final Permission MODIFY_THREAD = new RuntimePermission("modifyThread");

    private final Thread owner;
    private volatile ThreadContainer previous;

    // set to true when closed, accessed by owner only
    private boolean closed;

    // deadline support
    private final Future<?> timerTask;
    private volatile boolean deadlineExpired;  // set to true if deadline expired
    private final Object closeLock = new Object();
    private boolean closeInvoked;  // true if owner invokes close, needs closeLock

    StructuredThreadExecutor(ThreadFactory factory, Instant deadline) {
        super(factory, false);

        this.owner = Thread.currentThread();

        Future<?> timerTask = null;
        boolean stillborn = false;
        if (deadline != null) {
            Duration timeout = Duration.between(Instant.now(), deadline);
            if (timeout.isZero() || timeout.isNegative()) {
                stillborn = true;
            } else {
                long nanos = NANOSECONDS.convert(timeout);
                timerTask = TimerSupport.schedule(this::timeout, nanos, NANOSECONDS);
            }
        }
        this.timerTask = timerTask;
        JLA.pushThreadContainer(this);

        // deadline has already expired so terminate now
        if (stillborn) {
            tryShutdownAndTerminate(false);
            deadlineExpired = true;
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Throws IllegalCallerException if not owned by this thread.
     */
    private void checkOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalCallerException("Not owned by this thread");
    }

    @Override
    public Thread owner() {
        return owner;
    }

    @Override
    public ThreadContainer previous() {
        return previous;
    }

    @Override
    public void setPrevious(ThreadContainer previous) {
        this.previous = previous;
    }

    @Override
    Thread newThread(Runnable task) {
        checkOwner();
        return super.newThread(task);
    }

    @Override
    public void shutdown() {
        checkPermission();
        checkOwner();
        if (!isShutdown())
            tryShutdownAndTerminate(false);
    }

    @Override
    public List<Runnable> shutdownNow() {
        checkPermission();
        checkOwner();
        if (!isTerminated())
            tryShutdownAndTerminate(true);
        return List.of();
    }

    @Override
    public void close() {
        checkPermission();
        checkOwner();

        if (!closed) {
            Future<?> timer = this.timerTask;

            // coordinate with timeout task
            if (timer != null) {
                synchronized (closeLock) {
                    closeInvoked = true;
                }
            }

            // wait for the execute to terminate
            try {
                awaitTermination();
            } finally {
                JLA.popThreadContainer(this);
                closed = true;
            }

            // cancel timer
            if (timer != null && !timer.isDone()) {
                timer.cancel(false);
            }

            // throw if the deadline expired
            if (deadlineExpired) {
                throw new DeadlineExpiredException();
            }
        }
    }

    /**
     * Invoked when the timeout expires.
     */
    @SuppressWarnings("removal")
    private void timeout() {
        deadlineExpired = true;

        if (!isTerminated()) {
            // timer task may need permission to interrupt threads
            PrivilegedAction<Void> pa = () -> {
                tryShutdownAndTerminate(true);
                // interrupt owner if it hasn't invoked close
                synchronized (closeLock) {
                    if (!closeInvoked) {
                        owner.interrupt();
                    }
                }
                return null;
            };
            AccessController.doPrivileged(pa, null, MODIFY_THREAD);
        }
    }

    /**
     * Encapsulates a ScheduledThreadPoolExecutor for scheduling tasks.
     */
    private static class TimerSupport {
        private static final ScheduledThreadPoolExecutor STPE;
        static {
            STPE = new ScheduledThreadPoolExecutor(0, task -> {
                Thread thread = InnocuousThread.newThread(task);
                thread.setDaemon(true);
                return thread;
            });
            STPE.setRemoveOnCancelPolicy(true);
        }
        static ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            return STPE.schedule(task, delay, unit);
        }
    }
}

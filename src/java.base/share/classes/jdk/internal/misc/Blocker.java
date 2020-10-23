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

package jdk.internal.misc;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * Defines static methods to execute blocking tasks on virtual threads.
 *
 * The managedBlock methods are used to execute blocking tasks on the caller
 * thread or its carrier thread. If the carrier thread is a ForkJoinWorkerThread
 * then the task runs in ForkJoinPool.ManagedBlocker to that its pool may be
 * expanded to support additional parallelism during the blocking operation.
 */
public class Blocker {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private Blocker() { }

    /**
     * A task that returns a result and may throw an exception.
     */
    @FunctionalInterface
    public interface BlockingCallable<V, X extends Throwable> {
        V call() throws X;
    }

    /**
     * A task that may throw an exception.
     */
    @FunctionalInterface
    public interface BlockingRunnable<X extends Throwable> {
        void run() throws X;
    }

    private static class CallableBlocker<V, X extends Throwable>
            implements ForkJoinPool.ManagedBlocker {

        private final BlockingCallable<V, X> task;
        private boolean done;
        private V result;

        CallableBlocker(BlockingCallable<V, X> task) {
            this.task = task;
        }

        V result() {
            return result;
        }

        @Override
        public boolean block() {
            try {
                result = task.call();
            } catch (Throwable e) {
                U.throwException(e);
            } finally {
                done = true;
            }
            return true;
        }

        @Override
        public boolean isReleasable() {
            return done;
        }
    }

    private static class RunnableBlocker<X extends Throwable>
            implements ForkJoinPool.ManagedBlocker {
        private final BlockingRunnable<X> task;
        private boolean done;

        RunnableBlocker(BlockingRunnable<X> task) {
            this.task = task;
        }

        @Override
        public boolean block() {
            try {
                task.run();
            } catch (Throwable e) {
                U.throwException(e);
            } finally {
                done = true;
            }
            return true;
        }

        @Override
        public boolean isReleasable() {
            return done;
        }
    }

    /**
     * Executes a task that may block and pin the current thread. If invoked on a
     * virtual thread and the current carrier thread is in a ForkJoinPool then the
     * pool may be expanded to support additional parallelism during the call to
     * this method.
     */
    public static <V, X extends Throwable> V managedBlock(BlockingCallable<V, X> task) {
        Thread thread = Thread.currentThread();
        if (thread.isVirtual()) {
            Thread carrier = JLA.currentCarrierThread();
            if (carrier instanceof ForkJoinWorkerThread) {
                JLA.setCurrentThread(carrier);
                try {
                    var blocker = new CallableBlocker<>(task);
                    ForkJoinPool.managedBlock(blocker);
                    return blocker.result();
                } catch (Throwable e) {
                    U.throwException(e);
                } finally {
                    JLA.setCurrentThread(thread);
                }
                assert false;  // should not get here
            }
        }

        // run directly
        try {
            return task.call();
        } catch (Throwable e) {
            U.throwException(e);
            return null;
        }
    }

    /**
     * Executes a task that may block and pin the current thread. If invoked on a
     * virtual thread and the current carrier thread is in a ForkJoinPool then the
     * pool may be expanded to support additional parallelism during the call to
     * this method.
     */
    public static <X extends Throwable> void managedBlock(BlockingRunnable<X> task) {
        Thread thread = Thread.currentThread();
        if (thread.isVirtual()) {
            Thread carrier = JLA.currentCarrierThread();
            if (carrier instanceof ForkJoinWorkerThread) {
                JLA.setCurrentThread(carrier);
                try {
                    ForkJoinPool.managedBlock(new RunnableBlocker<>(task));
                    return;
                } catch (Throwable e) {
                    U.throwException(e);
                } finally {
                    JLA.setCurrentThread(thread);
                }
                assert false;  // should not get here
            }
        }

        // run directly
        try {
            task.run();
        } catch (Throwable e) {
            U.throwException(e);
        }
    }

    /**
     * Runs the given task in a background thread pool.
     */
    public static <V> V runInThreadPool(Callable<V> task) {
        Future<V> future = ThreadPool.THREAD_POOL.submit(task);
        try {
            return future.join();
        } catch (CompletionException e) {
            U.throwException(e.getCause());
            return null;
        }
    }

    /**
     * Runs the given task in a background thread pool.
     */
    public static void runInThreadPool(Runnable task) {
        Future<?> future = ThreadPool.THREAD_POOL.submit(task);
        try {
            future.join();
        } catch (CompletionException e) {
            U.throwException(e.getCause());
        }
    }

    private static class ThreadPool {
        private static final ExecutorService THREAD_POOL;
        static {
            int parallelism = Runtime.getRuntime().availableProcessors() << 1;
            ThreadFactory factory = task -> InnocuousThread.newThread(task);
            THREAD_POOL = Executors.newFixedThreadPool(parallelism, factory);
        }
    }
}

/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Defines static methods to execute blocking tasks in a thread pool. This class
 * is intended to be used by lightweight threads need to off-load a blocking task
 * to avoid pinning the carrier thread, e.g. InetAddress.getByXXXX.
 */

public class Blocker {
    private Blocker() { }

    public interface BlockingRunnable<X extends Throwable> {
        void run() throws X;
    }

    public interface BlockingCallable<V, X extends Throwable> {
        V call() throws X;
    }

    public static <X extends Throwable> void runBlocking(BlockingRunnable<X> task) throws X {
        if (Thread.currentThread().isVirtual()) {
            Callable<?> wrapper = () -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    U.throwException(e);
                }
                return null;
            };
            runBlockingTask(wrapper);
        } else {
            task.run();
        }
    }

    public static <V, X extends Throwable> V runBlocking(BlockingCallable<V, X> task) throws X {
        if (Thread.currentThread().isVirtual()) {
            Callable<V> wrapper = () -> {
                try {
                    return task.call();
                } catch (Throwable e) {
                    U.throwException(e);
                    return null;
                }
            };
            return runBlockingTask(wrapper);
        } else {
            return task.call();
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> V runBlockingTask(Callable<V> task) {
        Future<?> future = THREAD_POOL.submit(task);

        V result = null;
        boolean interrupted = false;
        try {
            boolean done = false;
            while (!done) {
                try {
                    result = (V) future.get();
                    done = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    U.throwException(e.getCause());
                    done = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return result;
    }

    private static final Unsafe U = Unsafe.getUnsafe();
    private static final ExecutorService THREAD_POOL;
    static {
        int parallelism = Runtime.getRuntime().availableProcessors() << 1;
        ThreadFactory factory = task -> InnocuousThread.newThread(task);
        THREAD_POOL =  Executors.newFixedThreadPool(parallelism, factory);
    }

}

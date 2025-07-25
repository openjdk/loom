/*
 * Copyright (c) 2022, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.test.lib.thread;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import jdk.management.VirtualThreadSchedulerMXBean;

/**
 * Helper class to support tests running tasks in a virtual thread.
 */
public class VThreadRunner {
    private VThreadRunner() { }

    /**
     * Characteristic value signifying that initial values for inheritable
     * thread locals are not inherited from the constructing thread.
     */
    public static final int NO_INHERIT_THREAD_LOCALS = 1 << 2;

    /**
     * Represents a task that does not return a result but may throw an exception.
     */
    @FunctionalInterface
    public interface ThrowingRunnable<X extends Throwable> {
        void run() throws X;
    }

    /**
     * Run a task in a virtual thread and wait for it to terminate.
     * If the task completes with an exception then it is thrown by this method.
     *
     * @param name thread name, can be null
     * @param characteristics thread characteristics
     * @param task the task to run
     * @throws X the exception thrown by the task
     */
    public static <X extends Throwable> void run(String name,
                                                 int characteristics,
                                                 ThrowingRunnable<X> task) throws X {
        Thread.Builder.OfVirtual builder = Thread.ofVirtual();
        if (name != null)
            builder.name(name);
        if ((characteristics & NO_INHERIT_THREAD_LOCALS) != 0)
            builder.inheritInheritableThreadLocals(false);
        run(builder, task);
    }

    /**
     * Run a task in a virtual thread and wait for it to terminate.
     * If the task completes with an exception then it is thrown by this method.
     *
     * @param builder the builder to create the thread
     * @param task the task to run
     * @throws X the exception thrown by the task
     */
    public static <X extends Throwable> void run(Thread.Builder.OfVirtual builder,
                                                 ThrowingRunnable<X> task) throws X {
        var throwableRef = new AtomicReference<Throwable>();
        Runnable target = () -> {
            try {
                task.run();
            } catch (Throwable ex) {
                throwableRef.set(ex);
            }
        };
        Thread thread = builder.start(target);

        // wait for thread to terminate
        try {
            while (thread.join(Duration.ofSeconds(10)) == false) {
                System.out.println("-- " + thread + " --");
                for (StackTraceElement e : thread.getStackTrace()) {
                    System.out.println("  " + e);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Throwable ex = throwableRef.get();
        if (ex != null) {
            if (ex instanceof RuntimeException e)
                throw e;
            if (ex instanceof Error e)
                throw e;
            @SuppressWarnings("unchecked")
            var x = (X) ex;
            throw x;
        }
    }

    /**
     * Run a task in a virtual thread and wait for it to terminate.
     * If the task completes with an exception then it is thrown by this method.
     *
     * @param name thread name, can be null
     * @param task the task to run
     * @throws X the exception thrown by the task
     */
    public static <X extends Throwable> void run(String name, ThrowingRunnable<X> task) throws X {
        run(name, 0, task);
    }

    /**
     * Run a task in a virtual thread and wait for it to terminate.
     * If the task completes with an exception then it is thrown by this method.
     *
     * @param characteristics thread characteristics
     * @param task the task to run
     * @throws X the exception thrown by the task
     */
    public static <X extends Throwable> void run(int characteristics, ThrowingRunnable<X> task) throws X {
        run(null, characteristics, task);
    }

    /**
     * Run a task in a virtual thread and wait for it to terminate.
     * If the task completes with an exception then it is thrown by this method.
     *
     * @param task the task to run
     * @throws X the exception thrown by the task
     */
    public static <X extends Throwable> void run(ThrowingRunnable<X> task) throws X {
        run(null, 0, task);
    }

    /**
     * Sets the virtual thread scheduler's target parallelism.
     *
     * <p> Tests using this method should use "{@code @modules jdk.management}" to help
     * test selection.
     *
     * @return the previous parallelism level
     */
    public static int setParallelism(int size) {
        var bean = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        int parallelism = bean.getParallelism();
        bean.setParallelism(size);
        return parallelism;
    }

    /**
     * Ensures that the virtual thread scheduler's target parallelism is at least
     * the given size. If the target parallelism is less than the given size then
     * it is changed to the given size.
     *
     * <p> Tests using this method should use "{@code @modules jdk.management}" to help
     * test selection.
     *
     * @return the previous parallelism level
     */
    public static int ensureParallelism(int size) {
        var bean = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        int parallelism = bean.getParallelism();
        if (size > parallelism) {
            bean.setParallelism(size);
        }
        return parallelism;
    }
}

/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @summary Test default implementation of Future.isCompletedNormally
 * @run testng IsCompletedNormallyTest
 */

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class IsCompletedNormallyTest {

    /**
     * Test isCompletedNormally when the task has already completed.
     */
    @Test
    public void testIsCompletedNormally1() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<String> future = submit(executor, () -> "foo");
            await(future);
            assertTrue(future.isCompletedNormally());
        }
    }

    /**
     * Test isCompletedNormally when the task has not completed.
     */
    @Test
    public void testIsCompletedNormally2() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });
            try {
                assertFalse(future.isCompletedNormally());
            } finally {
                future.cancel(true); // interrupt sleep
            }
        }
    }

    /**
     * Test isCompletedNormally when the task has completed with an exception.
     */
    @Test
    public void testIsCompletedNormally3() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> { throw new RuntimeException(); });
            await(future);
            assertFalse(future.isCompletedNormally());
        }
    }

    /**
     * Test isCompletedNormally when the task is cancelled.
     */
    @Test
    public void testIsCompletedNormally4() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });
            future.cancel(true);
            assertFalse(future.isCompletedNormally());
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status and the task has
     * already completed.
     */
    @Test
    public void testIsCompletedNormally5() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<String> future = submit(executor, () -> "foo");
            await(future);

            Thread.currentThread().interrupt();
            try {
                assertTrue(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status set and when
     * the task has not completed.
     */
    @Test
    public void testIsCompletedNormally6() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });

            Thread.currentThread().interrupt();
            try {
                assertFalse(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                future.cancel(true);  // interrupt sleep
            }
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status and the task has
     * already completed with an exception.
     */
    @Test
    public void testIsCompletedNormally7() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> { throw new RuntimeException(); });
            await(future);

            Thread.currentThread().interrupt();
            try {
                assertFalse(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status and the task is
     * cancelled.
     */
    @Test
    public void testIsCompletedNormally8() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });
            future.cancel(true);

            Thread.currentThread().interrupt();
            try {
                assertFalse(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Submits the task to the executor and wraps the Future so that its
     * default methods can be tested.
     */
    private static <V> Future<V> submit(ExecutorService executor, Callable<V> task) {
        Future<V> future = executor.submit(task);
        return new Future<V>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return future.cancel(mayInterruptIfRunning);
            }
            @Override
            public boolean isCancelled() {
                return future.isCancelled();
            }
            @Override
            public boolean isDone() {
                return future.isDone();
            }
            @Override
            public V get() throws InterruptedException, ExecutionException {
                return future.get();
            }
            @Override
            public V get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return future.get(timeout, unit);
            }
        };
    }

    /**
     * Waits for the future to be done.
     */
    private static void await(Future<?> future) {
        boolean interrupted = false;
        while (!future.isDone()) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

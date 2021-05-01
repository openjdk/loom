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
 * @summary Test Future::isCompletedNormally, including default implementation
 * @library ../ExecutorService
 * @run testng IsCompletedNormallyTest
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class IsCompletedNormallyTest {

    @DataProvider(name = "executors")
    public Object[][] executors() {
        return new Object[][] {
            // ensures that default implementation is tested
            { new DelegatingExecutorService(Executors.newCachedThreadPool()), },

            // executors that may return a Future that overrides isCompletedNormally
            { new ForkJoinPool(), },
            { Executors.newCachedThreadPool(), },
        };
    }
    
    /**
     * Test isCompletedNormally when the task has already completed.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally1(ExecutorService executor) {
        try (executor) {
            Future<String> future = executor.submit(() -> "foo");
            awaitDone(future);
            assertTrue(future.isCompletedNormally());
        }
    }

    /**
     * Test isCompletedNormally when the task has not completed.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally2(ExecutorService executor) throws Exception {
        try (executor) {
            var latch = new CountDownLatch(1);
            Future<?> future = executor.submit(() -> { latch.await(); return null; });
            try {
                assertFalse(future.isCompletedNormally());
            } finally {
                latch.countDown();
            }
        }
    }

    /**
     * Test isCompletedNormally when the task has completed with an exception.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally3(ExecutorService executor) {
        try (executor) {
            Future<?> future = executor.submit(() -> { throw new RuntimeException(); });
            awaitDone(future);
            assertFalse(future.isCompletedNormally());
        }
    }

    /**
     * Test isCompletedNormally when the task is cancelled.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally4(ExecutorService executor) {
        try (executor) {
            var latch = new CountDownLatch(1);
            Future<?> future = executor.submit(() -> { latch.await(); return null; });
            future.cancel(true);
            try {
                assertFalse(future.isCompletedNormally());
            } finally {
                latch.countDown();
            }
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status and the task has
     * already completed.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally5(ExecutorService executor) {
        try (executor) {
            Future<String> future = executor.submit(() -> "foo");
            awaitDone(future);
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
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally6(ExecutorService executor) {
        try (executor) {
            var latch = new CountDownLatch(1);
            Future<?> future = executor.submit(() -> { latch.await(); return null; });
            Thread.currentThread().interrupt();
            try {
                assertFalse(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                latch.countDown();
            }
        }
    }

    /**
     * Test isCompletedNormally with the interrupt status and the task has
     * already completed with an exception.
     */
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally7(ExecutorService executor) {
        try (executor) {
            Future<?> future = executor.submit(() -> { throw new RuntimeException(); });
            awaitDone(future);
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
    @Test(dataProvider = "executors")
    public void testIsCompletedNormally8(ExecutorService executor) {
        try (executor) {
            var latch = new CountDownLatch(1);
            Future<?> future = executor.submit(() -> { latch.await(); return null; });
            future.cancel(true);
            Thread.currentThread().interrupt();
            try {
                assertFalse(future.isCompletedNormally());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                latch.countDown();
            }
        }
    }

    /**
     * Waits for the future to be done.
     */
    private static void awaitDone(Future<?> future) {
        boolean interrupted = false;
        while (!future.isDone()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

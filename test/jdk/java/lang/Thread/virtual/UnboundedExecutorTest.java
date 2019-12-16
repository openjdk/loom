/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @run testng UnboundedExecutorTest
 * @summary Basic tests for Executors.newUnboundedExecutor
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class UnboundedExecutorTest {

    /**
     * Test that a new thread is created for each task.
     */
    public void testNewThreadPerTask() throws Exception {
        final int NUM_TASKS = 1000;
        AtomicInteger threadCount = new AtomicInteger();

        ThreadFactory factory1 = Thread.builder().virtual().factory();
        ThreadFactory factory2 = task -> {
            threadCount.addAndGet(1);
            return factory1.newThread(task);
        };

        var results = new ArrayList<Future<?>>();
        ExecutorService executor = Executors.newUnboundedExecutor(factory2);
        try (executor) {
            for (int i=0; i<NUM_TASKS; i++) {
                Future<?> result = executor.submit(() -> {
                    Thread.sleep(Duration.ofSeconds(1));
                    return null;
                });
                results.add(result);
            }
        }

        assertTrue(executor.isTerminated());
        assertTrue(threadCount.get() == NUM_TASKS);
        for (Future<?> result : results) {
            assertTrue(result.get() == null);
        }
    }

    /**
     * Test that shutdownNow stops executing tasks.
     */
    public void testShutdownNow() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        ExecutorService executor = Executors.newUnboundedExecutor(factory);
        Future<?> result;
        try {
            result = executor.submit(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
        } finally {
            executor.shutdownNow();
        }
        Throwable e = expectThrows(ExecutionException.class, result::get);
        assertTrue(e.getCause() instanceof InterruptedException);
    }

    /**
     * Test submit when the Executor is shutdown but not terminated.
     */
    public void testSubmitAfterShutdown() {
        Phaser barrier = new Phaser(2);
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        ExecutorService executor = Executors.newUnboundedExecutor(factory);
        try {
            // submit task to prevent executor from terminating
            executor.submit(barrier::arriveAndAwaitAdvance);
            executor.shutdown();
            assertTrue(executor.isShutdown() && !executor.isTerminated());
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
        } finally {
            // allow task to complete
            barrier.arriveAndAwaitAdvance();
        }
    }

    /**
     * Test submit when the Executor is terminated.
     */
    public void testSubmitAfterTermination() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        ExecutorService executor = Executors.newUnboundedExecutor(factory);
        executor.shutdown();
        assertTrue(executor.isShutdown() && executor.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
    }
}

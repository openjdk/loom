/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng/othervm/timeout=300 WithDeadlineTest
 * @summary Basic tests for ExecutorExecutor.withDeadline
 */

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class WithDeadlineTest {

    // long running interruptible task
    private static final Callable<Void> SLEEP_FOR_A_DAY = () -> {
        Thread.sleep(Duration.ofDays(1));
        return null;
    };

    /**
     * The executors to test.
     */
    @DataProvider(name = "executors")
    public Object[][] executors() {
        return new Object[][] {
            { Executors.newVirtualThreadExecutor() },
            { Executors.newCachedThreadPool() },
            { Executors.newFixedThreadPool(1) },
            { new ForkJoinPool() },
            { new ForkJoinPool(1) },
        };
    }

    /**
     * Deadline expires with running tasks, thread blocked in Future::get.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineFutureGet(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = delegate.withDeadline(deadline)) {
            // submit long running task
            Future<?> future = executor.submit(SLEEP_FOR_A_DAY);

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, future::get);
            if (!(delegate instanceof ForkJoinPool)) {
                assertTrue(e.getCause() instanceof InterruptedException);
            }

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in CompletableFuture::get.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineCompletableFutureGet(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = delegate.withDeadline(deadline)) {
            // submit long running task
            CompletableFuture<?> future = executor.submitTask(SLEEP_FOR_A_DAY);

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, future::get);
            if (!(delegate instanceof ForkJoinPool)) {
                assertTrue(e.getCause() instanceof InterruptedException);
            }

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in invokeAll.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineInvokeAll(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (var executor = delegate.withDeadline(deadline)) {
            // execute long running tasks
            List<Future<Void>> futures = executor.invokeAll(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
            for (Future<Void> f : futures) {
                assertTrue(f.isDone());
                try {
                    Object result = f.get();
                    assertTrue(false);
                } catch (ExecutionException | CancellationException e) {
                    // expected
                }
            }
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in invokeAny.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineInvokeAny(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (var executor = delegate.withDeadline(deadline)) {
            try {
                // execute long running tasks
                executor.invokeAny(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
                assertTrue(false);
            } catch (ExecutionException e) {
                // expected
            }
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in close.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineClose(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        Future<?> future;
        try (var executor = delegate.withDeadline(deadline)) {
            // submit long running task
            future = executor.submit(SLEEP_FOR_A_DAY);
        }

        // task should be interrupted
        assertTrue(future.isDone());
        Throwable e = expectThrows(ExecutionException.class, future::get);
        if (!(delegate instanceof ForkJoinPool)) {
            assertTrue(e.getCause() instanceof InterruptedException);
        }
    }

    /**
     * Deadline expires after the executor is shutdown.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineAfterShutdown(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = delegate.withDeadline(deadline)) {
            // submit long running task
            Future<?> future = executor.submit(SLEEP_FOR_A_DAY);

            // shutdown with running task
            executor.shutdown();

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, future::get);
            if (!(delegate instanceof ForkJoinPool)) {
                assertTrue(e.getCause() instanceof InterruptedException);
            }

            // executor should terminate
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires after executor has terminated.
     */
    @Test(dataProvider = "executors")
    public void testDeadlineAfterTerminate(ExecutorService delegate) throws Exception {
        var deadline = Instant.now().plusSeconds(10);
        Future<?> future;
        try (var executor = delegate.withDeadline(deadline)) {
            future = executor.submit(() -> { });
        }
        assertTrue(future.get() == null);
    }

    /**
     * Deadline has already expired
     */
    @Test(dataProvider = "executors")
    public void testDeadlineAlreadyExpired1(ExecutorService delegate) throws Exception {
        // now
        Instant now = Instant.now();
        try (var executor = delegate.withDeadline(now)) {
            assertTrue(executor.isTerminated());
        }
    }

    @Test(dataProvider = "executors")
    public void testDeadlineAlreadyExpired2(ExecutorService delegate) throws Exception {
        // in the past
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        try (var executor = delegate.withDeadline(yesterday)) {
            assertTrue(executor.isTerminated());
        }
    }
}

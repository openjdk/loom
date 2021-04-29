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
 * @summary Test default implementation of Future.join
 * @run testng JoinTest
 */

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class JoinTest {
    private ScheduledExecutorService scheduler;

    @BeforeClass
    public void setUp() throws Exception {
        ThreadFactory factory = (task) -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @AfterClass
    public void tearDown() {
        scheduler.shutdown();
    }

    /**
     * Schedules a future to be cancelled after the given delay.
     */
    private void scheduleCancel(Future<?> future, Duration delay) {
        long millis = delay.toMillis();
        scheduler.schedule(() -> future.cancel(true), millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a thread to be interrupted after the given delay.
     */
    private void scheduleInterrupt(Thread thread, Duration delay) {
        long millis = delay.toMillis();
        scheduler.schedule(thread::interrupt, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Test join when the task has already completed.
     */
    @Test
    public void testJoin1() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<String> future = submit(executor, () -> "foo");
            await(future);
            assertEquals(future.join(), "foo");
        }
    }

    /**
     * Test join when the task has already completed with an exception.
     */
    @Test
    public void testJoin2() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> { throw new RuntimeException(); });
            await(future);
            expectThrows(CompletionException.class, future::join);
        }
    }

    /**
     * Test join when the task is cancelled.
     */
    @Test
    public void testJoin3() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });
            future.cancel(true);
            expectThrows(CancellationException.class, future::join);
        }
    }

    /**
     * Test join waiting for a task to complete, task completes normally.
     */
    @Test
    public void testJoin4() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<String> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(1));
                return "foo";
            });
            assertEquals(future.join(), "foo");
        }
    }

    /**
     * Test join waiting for a task to complete, task completes with exception.
     */
    @Test
    public void testJoin5() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(1));
                throw new RuntimeException();
            });
            expectThrows(CompletionException.class, future::join);
        }
    }

    /**
     * Test join waiting for a task to complete, task is cancelled while waiting.
     */
    @Test
    public void testJoin6() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });
            scheduleCancel(future, Duration.ofSeconds(1));
            expectThrows(CancellationException.class, future::join);
        }
    }

    /**
     * Test join waiting for a task to complete with the interrupt status set,
     * task completes normally.
     */
    @Test
    public void testJoin7() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(1));
                return "foo";
            });

            Thread.currentThread().interrupt();
            try {
                assertEquals(future.join(), "foo");
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Test join waiting for a task to complete with the interrupt status set,
     * task completes with an exception.
     */
    @Test
    public void testJoin8() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(1));
                throw new RuntimeException();
            });

            Thread.currentThread().interrupt();
            try {
                expectThrows(CompletionException.class, future::join);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Test join waiting for a task to complete with the interrupt status set,
     * task is cancelled while waiting.
     */
    @Test
    public void testJoin9() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return null;
            });

            scheduleCancel(future, Duration.ofSeconds(1));
            Thread.currentThread().interrupt();
            try {
                expectThrows(CancellationException.class, future::join);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Test join waiting for a task to complete. The thread is interrupted while
     * waiting., the task completes normally.
     */
    @Test
    public void testJoin10() {
        try (var executor = Executors.newCachedThreadPool()) {
            Future<?> future = submit(executor, () -> {
                Thread.sleep(Duration.ofSeconds(5));
                return "foo";
            });

            // schedule thread to be interrupted
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                assertEquals(future.join(), "foo");
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

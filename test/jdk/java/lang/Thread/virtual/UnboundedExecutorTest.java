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
 * @run testng UnboundedExecutorTest
 * @summary Basic tests for Executors.newUnboundedExecutor
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * Test invokeAny where all tasks complete normally.
     */
    public void testInvokeAnyCompleteNormally1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result) || "bar".equals(result));
        }
    }

    /**
     * Test invokeAny where all tasks complete normally.
     */
    public void testInvokeAnyCompleteNormally2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(60));
                return "bar";
            };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test invokeAll where all tasks complete with exception.
     */
    public void testInvokeAnyCompleteExceptionally1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> { throw new FooException(); };
            try {
                executor.invokeAny(Set.of(task1, task2));
                assertTrue(false);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                assertTrue(cause instanceof FooException);
            }
        }
    }

    /**
     * Test invokeAll where all tasks complete with exception.
     */
    public void testInvokeAnyCompleteExceptionally2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(2));
                throw new FooException();
            };
            try {
                executor.invokeAny(Set.of(task1, task2));
                assertTrue(false);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                assertTrue(cause instanceof FooException);
            }
        }
    }

    /**
     * Test invokeAny where some, not all, tasks complete normally.
     */
    public void testInvokeAnySomeCompleteNormally1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> { throw new FooException(); };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test invokeAny where some, not all, tasks complete normally.
     */
    public void testInvokeAnySomeCompleteNormally2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofSeconds(2));
                return "foo";
            };
            Callable<String> task2 = () -> { throw new FooException(); };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test invokeAny where all tasks complete normally before timeout expires.
     */
    public void testInvokeAnyWithTimeout1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            String result = executor.invokeAny(Set.of(task1, task2), 1, TimeUnit.MINUTES);
            assertTrue("foo".equals(result) || "bar".equals(result));
        }
    }

    /**
     * Test invokeAny where timeout expires before any task completes.
     */
    @Test(expectedExceptions = { TimeoutException.class })
    public void testInvokeAnyWithTimeout2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            executor.invokeAny(Set.of(task1, task2), 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Test invokeAny where timeout expires after some tasks have completed
     * with exception.
     */
    @Test(expectedExceptions = { TimeoutException.class })
    public void testInvokeAnyWithTimeout3() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            executor.invokeAny(Set.of(task1, task2), 2, TimeUnit.SECONDS);
        }
    }

    /**
     * Test invokeAny cancels remaining tasks
     */
    public void testInvokeAnyCanceRemaining() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            DelayedResult<String> task1 = new DelayedResult("foo", Duration.ofMillis(50));
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofSeconds(60));
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result) && task1.isDone());
            while (!task2.isDone()) {
                Thread.sleep(Duration.ofMillis(100));
            }
            assertTrue(task2.exception() instanceof InterruptedException);
        }
    }
    static class DelayedResult<T> implements Callable<T> {
        final T result;
        final Duration delay;
        volatile boolean done;
        volatile Exception exception;
        DelayedResult(T result, Duration delay) {
            this.result = result;
            this.delay = delay;
        }
        public T call() throws Exception {
            try {
                Thread.sleep(delay);
                return result;
            } catch (Exception e) {
                this.exception = e;
                throw e;
            } finally {
                done = true;
            }
        }
        boolean isDone() {
            return done;
        }
        Exception exception() {
            return exception;
        }
    }

    /**
     * Test invokeAny with interrupt status set.
     */
    public void testInvokeAnyInterrupt1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            Thread.currentThread().interrupt();
            try {
                executor.invokeAny(Set.of(task1, task2));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test interrupt with thread blocked in invokeAny.
     */
    public void testInvokeAnyInterrupt2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            ScheduledInterrupter.schedule(Thread.currentThread(), 1000);
            try {
                executor.invokeAny(Set.of(task1, task2));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test invokeAny after ExecutorService has been shutdown.
     */
    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAnyAfterShutdown() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        var executor = Executors.newUnboundedExecutor(factory);
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAny(Set.of(task1, task2));
    }


    /**
     * Test invokeAny with empty collection.
     */
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testInvokeAnyEmpty1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            executor.invokeAny(Set.of());
        }
    }

    /**
     * Test invokeAny with empty collection.
     */
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testInvokeAnyEmpty2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            executor.invokeAny(Set.of(), 1, TimeUnit.MINUTES);
        }
    }
    /**
     * Test invokeAny with null.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull1() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            executor.invokeAny(null);
        }
    }

    /**
     * Test invokeAny with null element
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull2() throws Exception {
        ThreadFactory factory = Thread.builder().virtual().factory();
        try (var executor = Executors.newUnboundedExecutor(factory)) {
            List<Callable<String>> list = new ArrayList<>();
            list.add(() -> "foo");
            list.add(null);
            executor.invokeAny(null);
        }
    }

    static class ScheduledInterrupter implements Runnable {
        private final Thread thread;
        private final long delay;

        ScheduledInterrupter(Thread thread, long delay) {
            this.thread = thread;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) { }
        }

        static void schedule(Thread thread, long delay) {
            new Thread(new ScheduledInterrupter(thread, delay)).start();
        }
    }
}

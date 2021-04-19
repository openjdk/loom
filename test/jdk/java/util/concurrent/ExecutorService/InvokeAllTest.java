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
 * @summary Test default implementation of 2-arg ExecutorService.invokeAll
 * @run testng InvokeAllTest
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class InvokeAllTest {

    /**
     * Test invokeAll where all tasks complete normally.
     */
    public void testAllTasksComplete1() throws Exception {
        testAllTasksComplete(false);
    }
    public void testAllTasksComplete2() throws Exception {
        testAllTasksComplete(true);
    }
    private void testAllTasksComplete(boolean cancelOnException) throws Exception {
        try (var executor = newExecutorService()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                return "bar";
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2), cancelOnException);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            List<String> results = list.stream()
                    .map(Future::join)
                    .toList();
            assertEquals(results, List.of("foo", "bar"));
        }
    }

    /**
     * Test invokeAll where all tasks complete with exception.
     */
    public void testAllTasksFail() throws Exception {
        try (var executor = newExecutorService()) {
            class FooException extends Exception { }
            class BarException extends Exception { }
            Callable<String> task1 = () -> {
                throw new FooException();
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                throw new BarException();
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2), false);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            Throwable e1 = expectThrows(ExecutionException.class, () -> list.get(0).get());
            assertTrue(e1.getCause() instanceof FooException);
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);
        }
    }

    /**
     * Test invokeAll with cancelOnException=true, last task should be cancelled
     */
    public void testCancelOnException1() throws Exception {
        try (var executor = newExecutorService()) {
            class BarException extends Exception { }
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(3));
                throw new BarException();
            };
            Callable<String> task3 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "baz";
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), true);

            // list should have three elements, all should be done
            assertTrue(list.size() == 3);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // task1 should have a result
            String s = list.get(0).get();
            assertTrue("foo".equals(s));

            // task2 should have failed with an exception
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);

            // task3 should be cancelled
            expectThrows(CancellationException.class, () -> list.get(2).get());
        }
    }

    /**
     * Test invokeAll with cancelOnException=true, first task should be cancelled
     */
    public void testCancelOnException2() throws Exception {
        try (var executor = newExecutorService()) {
            class BarException extends Exception { }
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(3));
                throw new BarException();
            };
            Callable<String> task3 = () -> "baz";

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), true);

            // list should have three elements, all should be done
            assertTrue(list.size() == 3);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // task1 should be cancelled
            expectThrows(CancellationException.class, () -> list.get(0).get());

            // tasl2 should have failed with an exception
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);

            // task3 should have a result
            String s = list.get(2).get();
            assertTrue("baz".equals(s));
        }
    }

    /**
     * Call invokeAll with interrupt status set.
     */
    public void testWithInterruptStatusSet1() {
        testWithInterruptStatusSet(false);
    }
    public void testWithInterruptStatusSet2() {
        testWithInterruptStatusSet(true);
    }
    void testWithInterruptStatusSet(boolean cancelOnException) {
        try (var executor = newExecutorService()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "bar";
            };
            Thread.currentThread().interrupt();
            try {
                executor.invokeAll(List.of(task1, task2), cancelOnException);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Interrupt thread blocked in invokeAll.
     */
    public void testInterruptInvokeAll1() throws Exception {
        testInterruptInvokeAll(false);
    }
    public void testInterruptInvokeAll2() throws Exception {
        testInterruptInvokeAll(true);
    }
    void testInterruptInvokeAll(boolean cancelOnException) throws Exception {
        try (var executor = newExecutorService()) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));

            scheduleInterrupt(Thread.currentThread(), Duration.ofSeconds(2));
            try {
                executor.invokeAll(Set.of(task1, task2), cancelOnException);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }

            // if task2 started then it should have been interrupted
            if (task2.isStarted()) {
                while (!task2.isDone()) {
                    Thread.sleep(Duration.ofMillis(10));
                }
                assertTrue(task2.exception() instanceof InterruptedException);
            }
        }
    }

    /**
     * A task that returns a value after a delay.
     */
    private static class DelayedResult<T> implements Callable<T> {
        final T result;
        final Duration delay;
        volatile boolean started;
        volatile boolean done;
        volatile Exception exception;
        DelayedResult(T result, Duration delay) {
            this.result = result;
            this.delay = delay;
        }
        public T call() throws Exception {
            started = true;
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
        boolean isStarted() {
            return started;
        }
        boolean isDone() {
            return done;
        }
        Exception exception() {
            return exception;
        }
    }

    /**
     * Test invokeAll after ExecutorService has been shutdown.
     */
    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown() throws Exception {
        var executor = newExecutorService();
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), false);
    }

    /**
     * Test invokeAll with an empty collection.
     */
    public void testInvokeAllWithNoTasks() throws Exception {
        try (var executor = newExecutorService()) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), false);
            assertTrue(list.size() == 0);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull1() throws Exception {
        try (var executor = newExecutorService()) {
            executor.invokeAll(null, false);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull2() throws Exception {
        try (var executor = newExecutorService()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks, false);
        }
    }

    private static ExecutorService newExecutorService() {
        ExecutorService executor = Executors.newCachedThreadPool();
        return new ExecutorService() {
            @Override
            public void shutdown() {
                executor.shutdown();
            }
            @Override
            public List<Runnable> shutdownNow() {
                return executor.shutdownNow();
            }
            @Override
            public boolean isShutdown() {
                return executor.isShutdown();
            }
            @Override
            public boolean isTerminated() {
                return executor.isTerminated();
            }
            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit)
                    throws InterruptedException {
                return executor.awaitTermination(timeout, unit);
            }
            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return executor.submit(task);
            }
            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return executor.submit(task, result);
            }
            @Override
            public Future<?> submit(Runnable task) {
                return executor.submit(task);
            }
            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException {
                return executor.invokeAll(tasks);
            }
            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                    throws InterruptedException {
                return executor.invokeAll(tasks, timeout, unit);
            }
            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException, ExecutionException {
                return executor.invokeAny(tasks);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return executor.invokeAny(tasks, timeout, unit);
            }
            @Override
            public void execute(Runnable task) {
                executor.execute(task);
            }
        };
    }

    /**
     * Schedules a thread to be interrupted after the given delay.
     */
    private static void scheduleInterrupt(Thread thread, Duration delay) {
        long millis = delay.toMillis();
        SES.schedule(thread::interrupt, millis, TimeUnit.MILLISECONDS);
    }

    private static final ScheduledExecutorService SES;
    static {
        ThreadFactory factory = (task) -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        };
        SES = Executors.newSingleThreadScheduledExecutor(factory);
    }
}

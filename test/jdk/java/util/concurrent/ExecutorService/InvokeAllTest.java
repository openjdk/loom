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
 * @summary Test 2-arg ExecutorService.invokeAll, including default implementation
 * @run testng InvokeAllTest
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import static java.util.concurrent.Future.State.*;

import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class InvokeAllTest {
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

    @DataProvider(name = "executors")
    public Object[][] executors() {
        var defaultThreadFactory = Executors.defaultThreadFactory();
        var virtualThreadFactory = Thread.ofVirtual().factory();
        return new Object[][] {
            // ensures that default invokeAll(Collectiom, boolean) method is tested
            { new DelegatingExecutorService(Executors.newCachedThreadPool()), },

            // implementations that may override invokeAll(Collection, boolean)
            { new ForkJoinPool(), },
            { Executors.newThreadPerTaskExecutor(defaultThreadFactory), },
            { Executors.newThreadPerTaskExecutor(virtualThreadFactory), },
        };
    }

    /**
     * Schedules a thread to be interrupted after the given delay.
     */
    private void scheduleInterrupt(Thread thread, Duration delay) {
        long millis = delay.toMillis();
        scheduler.schedule(thread::interrupt, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Test invokeAll where all tasks complete normally.
     */
    @Test(dataProvider = "executors")
    public void testAllTasksComplete1(ExecutorService executor) throws Exception {
        testAllTasksComplete(executor, true);
    }
    @Test(dataProvider = "executors")
    public void testAllTasksComplete2(ExecutorService executor) throws Exception {
        testAllTasksComplete(executor, false);
    }
    private void testAllTasksComplete(ExecutorService executor, boolean waitAll) throws Exception {
        try (executor) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                return "bar";
            };

            List<Callable<String>> tasks = List.of(task1, task2);
            List<Future<String>> list = executor.invokeAll(tasks, waitAll);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            List<String> results = list.stream()
                    .map(Future::resultNow)
                    .toList();
            assertEquals(results, List.of("foo", "bar"));
        }
    }

    /**
     * Test invokeAll where all tasks complete with exception.
     */
    @Test(dataProvider = "executors")
    public void testAllTasksFail(ExecutorService executor) throws Exception {
        try (executor) {
            class FooException extends Exception { }
            class BarException extends Exception { }
            Callable<String> task1 = () -> {
                throw new FooException();
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                throw new BarException();
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2), true);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            Throwable e1 = expectThrows(ExecutionException.class, () -> list.get(0).get());
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            if (!(executor instanceof ForkJoinPool)) {   // FJP wraps cause
                assertTrue(e1.getCause() instanceof FooException);
                assertTrue(e2.getCause() instanceof BarException);
            }
        }
    }

    /**
     * Test invokeAll with waitAll=false, last task should be cancelled
     */
    @Test(dataProvider = "executors")
    public void testWaitAll1(ExecutorService executor) throws Exception {
        try (executor) {
            class BarException extends Exception { }
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                throw new BarException();
            };
            Callable<String> task3 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "baz";
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), false);

            // list should have three elements, all should be done
            assertTrue(list.size() == 3);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // task1 should have a result or be cancelled
            Future<String> future = list.get(0);
            if (future.state() == SUCCESS) {
                assertEquals(future.get(), "foo");
            } else {
                expectThrows(CancellationException.class, future::get);
            }

            // task2 should have failed with an exception
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);

            // task3 should be cancelled
            expectThrows(CancellationException.class, () -> list.get(2).get());
        }
    }

    /**
     * Test invokeAll with waitAll=true, first task should be cancelled
     */
    @Test(dataProvider = "executors")
    public void testWaitAll2(ExecutorService executor) throws Exception {
        try (executor) {
            class BarException extends Exception { }
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                throw new BarException();
            };
            Callable<String> task3 = () -> "baz";

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), false);

            // list should have three elements, all should be done
            assertTrue(list.size() == 3);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // task1 should be cancelled
            expectThrows(CancellationException.class, () -> list.get(0).get());

            // task2 should have failed with an exception
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);

            // task3 should have a result or be cancelled
            Future<String> future = list.get(2);
            if (future.state() == SUCCESS) {
                assertEquals(future.get(), "baz");
            } else {
                expectThrows(CancellationException.class, future::get);
            }
        }
    }

    /**
     * Call invokeAll with interrupt status set.
     */
    @Test(dataProvider = "executors")
    public void testWithInterruptStatusSet1(ExecutorService executor) {
        testWithInterruptStatusSet(executor, true);
    }
    @Test(dataProvider = "executors")
    public void testWithInterruptStatusSet2(ExecutorService executor) {
        testWithInterruptStatusSet(executor, false);
    }
    void testWithInterruptStatusSet(ExecutorService executor, boolean waitAll) {
        try (executor) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofDays(1));
                return "bar";
            };
            Thread.currentThread().interrupt();
            try {
                executor.invokeAll(List.of(task1, task2), waitAll);
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
    @Test(dataProvider = "executors")
    public void testInterruptInvokeAll1(ExecutorService executor) throws Exception {
        testInterruptInvokeAll(executor, true);
    }
    @Test(dataProvider = "executors")
    public void testInterruptInvokeAll2(ExecutorService executor) throws Exception {
        testInterruptInvokeAll(executor, false);
    }
    void testInterruptInvokeAll(ExecutorService executor, boolean waitAll) throws Exception {
        try (executor) {

            // skip test on ForkJoinPool for now
            if (executor instanceof ForkJoinPool) {
                throw new SkipException("FJP: Futre.cancel(true) does not interrupt tasks");
            }

            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofDays(1));

            scheduleInterrupt(Thread.currentThread(), Duration.ofSeconds(1));
            try {
                executor.invokeAll(Set.of(task1, task2), waitAll);
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
                if (!(executor instanceof ForkJoinPool)) {
                    assertTrue(task2.exception() instanceof InterruptedException);
                }
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
    @Test(dataProvider = "executors",
          expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown(ExecutorService executor) throws Exception {
        executor.shutdown();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), true);
    }

    /**
     * Test invokeAll with an empty collection.
     */
    @Test(dataProvider = "executors")
    public void testInvokeAllWithNoTasks(ExecutorService executor) throws Exception {
        try (executor) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), true);
            assertTrue(list.size() == 0);
        }
    }

    /**
     * Test invokeAll with null collection.
     */
    @Test(dataProvider = "executors",
          expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull1(ExecutorService executor) throws Exception {
        try (executor) {
            executor.invokeAll(null, true);
        }
    }

    /**
     * Test invokeAll with collection containing null element.
     */
    @Test(dataProvider = "executors",
          expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull2(ExecutorService executor) throws Exception {
        try (executor) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks, true);
        }
    }
}

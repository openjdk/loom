/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic tests for ThreadExecutor
 * @run testng/othervm/timeout=300 ThreadExecutorTest
 */

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ThreadExecutorTest {
    // long running interruptible task
    private static final Callable<Void> SLEEP_FOR_A_DAY = () -> {
        Thread.sleep(Duration.ofDays(1));
        return null;
    };

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

    @DataProvider(name = "factories")
    public Object[][] factories() {
        return new Object[][] {
            { Executors.defaultThreadFactory(), },
            { Thread.ofVirtual().factory(), },
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
     * Test that a thread is created for each task.
     */
    @Test(dataProvider = "factories")
    public void testThreadPerTask(ThreadFactory factory) throws Exception {
        final int NUM_TASKS = 100;
        AtomicInteger threadCount = new AtomicInteger();

        ThreadFactory wrapper = task -> {
            threadCount.addAndGet(1);
            return factory.newThread(task);
        };

        var futures = new ArrayList<Future<Integer>>();
        ExecutorService executor = Executors.newThreadExecutor(wrapper);
        try (executor) {
            for (int i=0; i<NUM_TASKS; i++) {
                int result = i;
                Future<Integer> future = executor.submit(() -> result);
                futures.add(future);
            }
        }

        assertTrue(executor.isTerminated());
        assertEquals(threadCount.get(), NUM_TASKS);
        for (int i=0; i<NUM_TASKS; i++) {
            Future<Integer> future = futures.get(i);
            assertEquals((int) future.get(), i);
        }
    }

    /**
     * Test that the given thread factory specified to newThreadExecutor is used.
     */
    public void testThreadFactory() throws Exception {
        var ref1 = new AtomicReference<Thread>();
        var ref2 = new AtomicReference<Thread>();
        ThreadFactory factory = task -> {
            assertTrue(ref1.get() == null);
            Thread vthread = Thread.ofVirtual().unstarted(task);
            ref1.set(vthread);
            return vthread;
        };
        try (ExecutorService executor = Executors.newThreadExecutor(factory)) {
            executor.submit(() -> ref2.set(Thread.currentThread())).join();
            assertTrue(ref1.get() != null && ref1.get() == ref2.get());
        }
    }

    /**
     * Test shutdown.
     */
    @Test(dataProvider = "factories")
    public void testShutdown1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            assertFalse(executor.isShutdown());
            assertFalse(executor.isTerminated());
            assertFalse(executor.awaitTermination(10, TimeUnit.MILLISECONDS));

            Future<?> result = executor.submit(SLEEP_FOR_A_DAY);
            try {
                executor.shutdown();
                assertTrue(executor.isShutdown());
                assertFalse(executor.isTerminated());
                assertFalse(executor.awaitTermination(500, TimeUnit.MILLISECONDS));
            } finally {
                result.cancel(true);
            }
        }
    }

    /**
     * Test shutdown from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testShutdown2(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.shutdown();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test shutdown a shared executor from a different thread.
     */
    @Test(dataProvider = "factories")
    public void testShutdown3(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newUnownedThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.shutdown();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() == null);
    }

    /**
     * Test shutdownNow.
     */
    @Test(dataProvider = "factories")
    public void testShutdownNow1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            assertFalse(executor.isShutdown());
            assertFalse(executor.isTerminated());
            assertFalse(executor.awaitTermination(10, TimeUnit.MILLISECONDS));

            Future<?> result = executor.submit(SLEEP_FOR_A_DAY);
            try {
                List<Runnable> tasks = executor.shutdownNow();
                assertTrue(executor.isShutdown());
                assertTrue(tasks.isEmpty());

                Throwable e = expectThrows(ExecutionException.class, result::get);
                assertTrue(e.getCause() instanceof InterruptedException);

                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(executor.isTerminated());
            } finally {
                result.cancel(true);
            }
        }
    }

    /**
     * Test shutdowNow from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testShutdownNow2(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.shutdownNow();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test shutdownNow a shared executor from a different thread.
     */
    @Test(dataProvider = "factories")
    public void testShutdownNow3(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newUnownedThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.shutdownNow();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() == null);
    }

    /**
     * Test close with no threads running.
     */
    @Test(dataProvider = "factories")
    public void testClose1(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    /**
     * Test close with threads running.
     */
    @Test(dataProvider = "factories")
    public void testClose2(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        Future<String> future;
        try (executor) {
            future = executor.submit(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        assertEquals(future.get(), "foo");   // task should complete
    }

    /**
     * Invoke close with interrupt status set, should cancel task.
     */
    @Test(dataProvider = "factories")
    public void testClose3(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        Future<?> future;
        try (executor) {
            future = executor.submit(SLEEP_FOR_A_DAY);
            Thread.currentThread().interrupt();
        } finally {
            assertTrue(Thread.currentThread().isInterrupted());
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        expectThrows(ExecutionException.class, future::get);
    }

    /**
     * Interrupt thread blocked in close.
     */
    @Test(dataProvider = "factories")
    public void testClose4(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        Future<?> future;
        try (executor) {
            future = executor.submit(SLEEP_FOR_A_DAY);
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        expectThrows(ExecutionException.class, future::get);
    }

    /**
     * Close executor that is already closed.
     */
    @Test(dataProvider = "factories")
    public void testClose5(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newThreadExecutor(factory);
        var executor2 = Executors.newThreadExecutor(factory);
        executor2.close();
        executor2.close(); // already closed
        executor1.close();
        executor1.close(); // already closed
        executor2.close(); // out of order, already closed
    }

    /**
     * Attempt to close from a different thread.
     */
    @Test(dataProvider = "factories")
    public void testClose6(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.close();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Attempt to close a closed executor from a different thread.
     * @throws Exception
     */
    @Test(dataProvider = "factories")
    public void testClose7(ThreadFactory factory) throws Exception {
        var executor = Executors.newThreadExecutor(factory);
        executor.close();
        assertTrue(executor.isTerminated());
        var exception = new AtomicReference<Exception>();
        Thread.startVirtualThread(() -> {
            try {
                executor.close();
            } catch (Exception e) {
                exception.set(e);
            }
        }).join();
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test that close enforces nesting
     */
    @Test(dataProvider = "factories")
    public void testClose8(ThreadFactory factory) throws Exception {
        try (var executor1 = Executors.newThreadExecutor(factory)) {
            try (var executor2 = Executors.newThreadExecutor(factory)) {
                try {
                    executor1.close();
                    assertTrue(false);
                } catch (IllegalStateException expected) { }
            }
        }
    }

    /**
     * Test closing a shared executor from a different thread.
     */
    @Test(dataProvider = "factories")
    public void testClose10(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newUnownedThreadExecutor(factory)) {
            Thread.startVirtualThread(() -> {
                try {
                    executor.close();
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() == null);
    }

    /**
     * Test awaitTermination when not shutdown.
     */
    @Test(dataProvider = "factories")
    public void testAwaitTermination1(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        assertFalse(executor.awaitTermination(100, TimeUnit.MILLISECONDS));
        executor.close();
        assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
    }

    /**
     * Test awaitTermination with task running.
     */
    @Test(dataProvider = "factories")
    public void testAwaitTermination2(ThreadFactory factory) throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(factory);
        Phaser barrier = new Phaser(2);
        Future<?> result = executor.submit(barrier::arriveAndAwaitAdvance);
        try {
            executor.shutdown();
            assertFalse(executor.awaitTermination(100, TimeUnit.MILLISECONDS));
            barrier.arriveAndAwaitAdvance();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            result.cancel(true);
        }
    }

    /**
     * Test submit when the Executor is shutdown but not terminated.
     */
    @Test(dataProvider = "factories")
    public void testSubmitAfterShutdown(ThreadFactory factory) {
        Phaser barrier = new Phaser(2);
        try (ExecutorService executor = Executors.newThreadExecutor(factory)) {
            // submit task to prevent executor from terminating
            executor.submit(barrier::arriveAndAwaitAdvance);
            try {
                executor.shutdown();
                assertTrue(executor.isShutdown() && !executor.isTerminated());
                expectThrows(RejectedExecutionException.class,
                             () -> executor.submit(() -> {  }));
            } finally {
                barrier.arriveAndAwaitAdvance();
            }
        }
    }

    /**
     * Test submit when the Executor is terminated.
     */
    @Test(dataProvider = "factories")
    public void testSubmitAfterTermination(ThreadFactory factory) {
        var executor = Executors.newThreadExecutor(factory);
        executor.shutdown();
        assertTrue(executor.isShutdown() && executor.isTerminated());
        expectThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
    }

    /**
     * Test submit with null.
     */
    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls1(ThreadFactory factory) {
        var executor = Executors.newThreadExecutor(factory);
        executor.submit((Runnable) null);
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls2(ThreadFactory factory) {
        var executor = Executors.newThreadExecutor(factory);
        executor.submit((Callable<String>) null);
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls3(ThreadFactory factory) {
        var executor = Executors.newThreadExecutor(factory);
        executor.submit((Collection<? extends Callable<String>>) null);
    }

    /**
     * Test invokeAny where all tasks complete normally.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAny1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result) || "bar".equals(result));
        }
    }

    /**
     * Test invokeAny where all tasks complete normally. The completion of the
     * first task should cancel remaining tasks.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAny2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            AtomicBoolean task2Started = new AtomicBoolean();
            AtomicReference<Throwable> task2Exception = new AtomicReference<>();
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                task2Started.set(true);
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (Exception e) {
                    task2Exception.set(e);
                }
                return "bar";
            };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));

            // if task2 started then the sleep should have been interrupted
            if (task2Started.get()) {
                Throwable exc;
                while ((exc = task2Exception.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

    /**
     * Test invokeAny where all tasks complete with exception.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAny3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
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
     * Test invokeAny where all tasks complete with exception. The completion
     * of the last task is delayed.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAny4(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
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
    @Test(dataProvider = "factories")
    public void testInvokeAny5(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> { throw new FooException(); };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test invokeAny where some, not all, tasks complete normally. The
     * completion of the last task is delayed.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAny6(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            };
            Callable<String> task2 = () -> { throw new FooException(); };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test timed-invokeAny where all tasks complete normally before the timeout.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAnyWithTimeout1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            String result = executor.invokeAny(Set.of(task1, task2), 1, TimeUnit.MINUTES);
            assertTrue("foo".equals(result) || "bar".equals(result));
        }
    }

    /**
     * Test timed-invokeAny where one task completes normally before the timeout.
     * The remaining tests should be cancelled.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAnyWithTimeout2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            AtomicBoolean task2Started = new AtomicBoolean();
            AtomicReference<Throwable> task2Exception = new AtomicReference<>();
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                task2Started.set(true);
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (Exception e) {
                    task2Exception.set(e);
                }
                return "bar";
            };
            String result = executor.invokeAny(Set.of(task1, task2), 1, TimeUnit.MINUTES);
            assertTrue("foo".equals(result));

            // if task2 started then the sleep should have been interrupted
            if (task2Started.get()) {
                Throwable exc;
                while ((exc = task2Exception.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

    /**
     * Test timed-invokeAny where timeout expires before any task completes.
     */
    @Test(dataProvider = "factories", expectedExceptions = { TimeoutException.class })
    public void testInvokeAnyWithTimeout3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            executor.invokeAny(Set.of(task1, task2), 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Test invokeAny where timeout expires after some tasks have completed
     * with exception.
     */
    @Test(dataProvider = "factories", expectedExceptions = { TimeoutException.class })
    public void testInvokeAnyWithTimeout4(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            class FooException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            executor.invokeAny(Set.of(task1, task2), 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Test invokeAny with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAnyWithInterruptSet(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
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
     * Test interrupting a thread blocked in invokeAny.
     */
    @Test(dataProvider = "factories")
    public void testInterruptInvokeAny(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
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
    @Test(dataProvider = "factories", expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAnyAfterShutdown(ThreadFactory factory) throws Exception {
        var executor = Executors.newThreadExecutor(factory);
        executor.shutdown();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAny(Set.of(task1, task2));
    }

    /**
     * Test invokeAny with empty collection.
     */
    @Test(dataProvider = "factories", expectedExceptions = { IllegalArgumentException.class })
    public void testInvokeAnyEmpty1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            executor.invokeAny(Set.of());
        }
    }

    /**
     * Test timed-invokeAny with empty collection.
     */
    @Test(dataProvider = "factories", expectedExceptions = { IllegalArgumentException.class })
    public void testInvokeAnyEmpty2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            executor.invokeAny(Set.of(), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * Test invokeAll with deadline, deadline expires with running tasks.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAnyWithDeadline(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        try (ExecutorService executor = Executors.newThreadExecutor(factory, deadline)) {
            try {
                // execute long running tasks
                executor.invokeAny(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
                assertTrue(false);
            } catch (ExecutionException e) {
                // expected
            }

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Test invokeAny with null.
     */
    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            executor.invokeAny(null);
        }
    }

    /**
     * Test invokeAny with null element
     */
    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            List<Callable<String>> list = new ArrayList<>();
            list.add(() -> "foo");
            list.add(null);
            executor.invokeAny(null);
        }
    }

    /**
     * Test invokeAll where all tasks complete normally.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                return "bar";
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2));

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            List<String> results = list.stream().map(Future::join).collect(Collectors.toList());
            assertEquals(results, List.of("foo", "bar"));
        }
    }

    /**
     * Test invokeAll where all tasks complete with exception.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            class FooException extends Exception { }
            class BarException extends Exception { }
            Callable<String> task1 = () -> { throw new FooException(); };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(1));
                throw new BarException();
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2));

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
     * Test invokeAll where all tasks complete normally before the timeout expires.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMillis(500));
                return "bar";
            };

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2), 1, TimeUnit.DAYS);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            List<String> results = list.stream().map(Future::join).collect(Collectors.toList());
            assertEquals(results, List.of("foo", "bar"));
        }
    }

    /**
     * Test invokeAll where some tasks do not complete before the timeout expires.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll4(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            AtomicReference<Exception> exc = new AtomicReference<>();
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                try {
                    Thread.sleep(Duration.ofDays(1));
                    return "bar";
                } catch (Exception e) {
                    exc.set(e);
                    throw e;
                }
            };

            var list = executor.invokeAll(List.of(task1, task2), 2, TimeUnit.SECONDS);

            // list should have two elements, both should be done
            assertTrue(list.size() == 2);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // check results
            assertEquals(list.get(0).get(), "foo");
            assertTrue(list.get(1).isCancelled());

            // task2 should be interrupted
            Exception e;
            while ((e = exc.get()) == null) {
                Thread.sleep(50);
            }
            assertTrue(e instanceof InterruptedException);
        }
    }

    /**
     * Test invokeAll with cancelOnException=true, last task should be cancelled
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll5(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
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

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), true);

            // list should have three elements, all should be done
            assertTrue(list.size() == 3);
            boolean notDone = list.stream().anyMatch(r -> !r.isDone());
            assertFalse(notDone);

            // task1 should have a result or be cancelled
            Future<String> future = list.get(0);
            if (future.isCompletedNormally()) {
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
     * Test invokeAll with cancelOnException=true, first task should be cancelled
     */
    @Test(dataProvider = "factories")
    public void testInvokeAll6(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
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

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2, task3), true);

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
            if (future.isCompletedNormally()) {
                assertEquals(future.get(), "baz");
            } else {
                expectThrows(CancellationException.class, future::get);
            }
        }
    }

    /**
     * Test invokeAll with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "bar";
            };

            Thread.currentThread().interrupt();
            try {
                executor.invokeAll(List.of(task1, task2));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test invokeAll cancelOnException=true and with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "bar";
            };

            Thread.currentThread().interrupt();
            try {
                executor.invokeAll(List.of(task1, task2), true);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test timed-invokeAll with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "bar";
            };

            Thread.currentThread().interrupt();
            try {
                executor.invokeAll(List.of(task1, task2), 1, TimeUnit.SECONDS);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test interrupt with thread blocked in invokeAll
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt4(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                executor.invokeAll(Set.of(task1, task2));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());

                // task2 should have been interrupted
                while (!task2.isDone()) {
                    Thread.sleep(Duration.ofMillis(100));
                }
                assertTrue(task2.exception() instanceof InterruptedException);
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test interrupt with thread blocked in invokeAll cancelOnException=true
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt5(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                executor.invokeAll(Set.of(task1, task2), true);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());

                // task2 should have been interrupted
                while (!task2.isDone()) {
                    Thread.sleep(Duration.ofMillis(100));
                }
                assertTrue(task2.exception() instanceof InterruptedException);
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test interrupt with thread blocked in timed-invokeAll
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllInterrupt6(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                executor.invokeAll(Set.of(task1, task2), 1, TimeUnit.DAYS);
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.currentThread().isInterrupted());

                // task2 should have been interrupted
                while (!task2.isDone()) {
                    Thread.sleep(Duration.ofMillis(100));
                }
                assertTrue(task2.exception() instanceof InterruptedException);
            } finally {
                Thread.interrupted(); // clear interrupt
            }
        }
    }

    /**
     * Test invokeAll after ExecutorService has been shutdown.
     */
    @Test(dataProvider = "factories", expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown1(ThreadFactory factory) throws Exception {
        var executor = Executors.newThreadExecutor(factory);
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2));
    }

    @Test(dataProvider = "factories", expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown2(ThreadFactory factory) throws Exception {
        var executor = Executors.newThreadExecutor(factory);
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), true);
    }

    @Test(dataProvider = "factories", expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown3(ThreadFactory factory) throws Exception {
        var executor = Executors.newThreadExecutor(factory);
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), 1, TimeUnit.SECONDS);
    }

    /**
     * Test invokeAll with empty collection.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllEmpty1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Future<Object>> list = executor.invokeAll(Set.of());
            assertTrue(list.size() == 0);
        }
    }

    @Test(dataProvider = "factories")
    public void testInvokeAllEmpty2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), true);
            assertTrue(list.size() == 0);
        }
    }

    @Test(dataProvider = "factories")
    public void testInvokeAllEmpty3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), 1, TimeUnit.SECONDS);
            assertTrue(list.size() == 0);
        }
    }

    /**
     * Test invokeAll with deadline, deadline expires with running tasks.
     */
    @Test(dataProvider = "factories")
    public void testInvokeAllWithDeadline(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (var executor = Executors.newThreadExecutor(factory, deadline)) {
            // execute long running tasks
            var futures = executor.invokeAll(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
            for (Future<Void> f : futures) {
                assertTrue(f.isDone());
                try {
                    Object result = f.get();
                    assertTrue(false);
                } catch (ExecutionException | CancellationException e) {
                    // expected
                }
            }

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull1(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            executor.invokeAll(null);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull2(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull3(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            executor.invokeAll(null, 1, TimeUnit.SECONDS);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull4(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            Callable<String> task = () -> "foo";
            executor.invokeAll(List.of(task), 1, null);
        }
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull5(ThreadFactory factory) throws Exception {
        try (var executor = Executors.newThreadExecutor(factory)) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineDuringFutureGet(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newThreadExecutor(factory, deadline)) {
            // submit long running task
            Future<?> future = executor.submit(SLEEP_FOR_A_DAY);

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, future::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked waiting on a stream.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineDuringSubmit(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newThreadExecutor(factory, deadline)) {
            var futures = executor.submit(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY)).toList();
            for (Future<Void> f : futures) {
                assertTrue(f.isDone());
                try {
                    Object result = f.get();
                    assertTrue(false);
                } catch (ExecutionException e) {
                    assertTrue(e.getCause() instanceof InterruptedException);
                } catch (CancellationException e) {
                    // didn't run
                }
            }

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in close.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineDuringClose(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        Future<?> future;
        try (ExecutorService executor = Executors.newThreadExecutor(factory, deadline)) {
            // submit long running task
            future = executor.submit(SLEEP_FOR_A_DAY);
        }

        // task should be interrupted
        assertTrue(future.isDone());
        Throwable e = expectThrows(ExecutionException.class, future::get);
        assertTrue(e.getCause() instanceof InterruptedException);
    }

    /**
     * Deadline expires after the executor is shutdown.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAfterShutdown(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newThreadExecutor(factory, deadline)) {
            // submit long running task
            Future<?> future = executor.submit(SLEEP_FOR_A_DAY);

            // shutdown with running task
            executor.shutdown();

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, future::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor should terminate
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires after executor has terminated.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAfterTerminate(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(10);
        Future<?> future;
        try (ExecutorService executor = Executors.newThreadExecutor(factory, deadline)) {
            future = executor.submit(() -> { });
        }
        assertTrue(future.get() == null);
    }

    /**
     * Deadline has already expired
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired1(ThreadFactory factory) throws Exception {
        // now
        Instant now = Instant.now();
        try (ExecutorService executor = Executors.newThreadExecutor(factory, now)) {
            assertTrue(executor.isTerminated());
        }
    }

    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired2(ThreadFactory factory) throws Exception {
        // in the past
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        try (ExecutorService executor = Executors.newThreadExecutor(factory, yesterday)) {
            assertTrue(executor.isTerminated());
        }
    }

    /**
     * Test ThreadFactory that does not produce any threads
     */
    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testNoThreads1() throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(task -> null);
        executor.execute(() -> { });
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testNoThreads2() throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(task -> null);
        executor.submit(() -> "foo");
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testNoThreads3() throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(task -> null);
        executor.invokeAll(List.of(() -> "foo"));
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testNoThreads4() throws Exception {
        ExecutorService executor = Executors.newThreadExecutor(task -> null);
        executor.invokeAny(List.of(() -> "foo"));
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls1() {
        Executors.newThreadExecutor(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls2() {
        ThreadFactory factory = Thread.ofPlatform().factory();
        Executors.newThreadExecutor(factory, null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls3() {
        Instant deadline = Instant.now().plusSeconds(10);
        Executors.newThreadExecutor(null, deadline);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls4() {
        Executors.newVirtualThreadExecutor(null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls5() {
        Executors.newUnownedThreadExecutor(null);
    }

    // -- supporting classes --

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

}

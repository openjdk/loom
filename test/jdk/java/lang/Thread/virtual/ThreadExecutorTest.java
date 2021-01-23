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

/**
 * @test
 * @run testng/othervm/timeout=300 ThreadExecutorTest
 * @summary Basic tests for ThreadExecutor
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ThreadExecutorTest {
    // long running interruptible task
    private static final Callable<Void> SLEEP_FOR_A_DAY = () -> {
        Thread.sleep(Duration.ofDays(1));
        return null;
    };

    /**
     * Test that a thread is created for each task.
     */
    public void testThreadPerTask() throws Exception {
        final int NUM_TASKS = 1000;
        AtomicInteger threadCount = new AtomicInteger();

        ThreadFactory factory1 = Thread.builder().virtual().factory();
        ThreadFactory factory2 = task -> {
            threadCount.addAndGet(1);
            return factory1.newThread(task);
        };

        var results = new ArrayList<Future<?>>();
        ExecutorService executor = Executors.newThreadExecutor(factory2);
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
     * Tests that newVirtualThreadExecutor creates virtual threads
     */
    public void testNewVirtualThreadExecutor() {
        final int NUM_TASKS = 10;
        AtomicInteger virtualThreadCount = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadExecutor()) {
            for (int i=0; i<NUM_TASKS; i++) {
                executor.submit(() -> {
                    if (Thread.currentThread().isVirtual()) {
                        virtualThreadCount.addAndGet(1);
                    }
                });
            }
        }
        assertTrue(virtualThreadCount.get() == NUM_TASKS);
    }

    /**
     * Test shutdown.
     */
    public void testShutdown1() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadExecutor()) {
            assertFalse(executor.isShutdown());
            assertFalse(executor.isTerminated());
            assertFalse(executor.awaitTermination(10, TimeUnit.MILLISECONDS));

            Future<?> result = executor.submit(SLEEP_FOR_A_DAY);
            try {
                executor.shutdown();
                assertTrue(executor.isShutdown());
                assertFalse(executor.isTerminated());
                assertFalse(executor.awaitTermination(1, TimeUnit.SECONDS));
            } finally {
                result.cancel(true);
            }
        }
    }

    /**
     * Attempt to shutdown from a different thread.
     */
    public void testShutdown2() throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
    public void testShutdown3() throws Exception {
        var exception = new AtomicReference<Exception>();

        ThreadFactory factory = Thread.builder().virtual().factory();
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
    public void testShutdownNow1() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadExecutor()) {
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
     * Attempt to shutdownNow from a different thread.
     */
    public void testShutdownNow2() throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
    public void testShutdownNow3() throws Exception {
        var exception = new AtomicReference<Exception>();

        ThreadFactory factory = Thread.builder().virtual().factory();
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
     * Test close.
     */
    public void testClose1() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    public void testClose2() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        try (executor) {
            executor.submit(() -> {
                Thread.sleep(Duration.ofSeconds(2));
                return null;
            });
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    public void testClose3() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        try (executor) {
            Future<?> result = executor.submit(SLEEP_FOR_A_DAY);
            ScheduledInterrupter.schedule(Thread.currentThread(), 2000);
        } finally {
            assertTrue(Thread.interrupted());
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    /**
     * Close executor that is already closed.
     */
    public void testClose4() throws Exception {
        var executor1 = Executors.newVirtualThreadExecutor();
        var executor2 = Executors.newVirtualThreadExecutor();
        executor2.close();
        executor2.close(); // already closed
        executor1.close();
        executor1.close(); // already closed
        executor2.close(); // out of order, already closed
    }

    /**
     * Attempt to close from a different thread.
     */
    public void testClose5() throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
    public void testClose6() throws Exception {
        var executor = Executors.newVirtualThreadExecutor();
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
    public void testClose7() throws Exception {
        try (var executor1 = Executors.newVirtualThreadExecutor()) {
            try (var executor2 = Executors.newVirtualThreadExecutor()) {
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
    public void testClose8() throws Exception {
        var exception = new AtomicReference<Exception>();

        ThreadFactory factory = Thread.builder().virtual().factory();
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
     * Test awaitTermination.
     */
    public void testAwaitTermination1() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        assertFalse(executor.awaitTermination(1, TimeUnit.SECONDS));
        executor.close();
        assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
    }

    public void testAwaitTermination2() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        Future<?> result = executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(3));
            return null;
        });
        try {
            executor.shutdown();
            assertFalse(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            result.cancel(true);
        }
    }

    /**
     * Test submit method.
     */
    public void testSubmit() throws Exception {
        var ref1 = new AtomicReference<Thread>();
        var ref2 = new AtomicReference<Thread>();
        ThreadFactory factory = task -> {
            assertTrue(ref1.get() == null);
            Thread vthread = Thread.builder().virtual().task(task).build();
            ref1.set(vthread);
            return vthread;
        };
        try (ExecutorService executor = Executors.newThreadExecutor(factory)) {
            executor.submit(() -> ref2.set(Thread.currentThread())).join();
            assertTrue(ref1.get() != null && ref1.get() == ref2.get());
        }
    }

    /**
     * Test submit when the Executor is shutdown but not terminated.
     */
    public void testSubmitAfterShutdown() {
        Phaser barrier = new Phaser(2);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor()) {
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
    public void testSubmitAfterTermination() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        ExecutorService executor = Executors.newVirtualThreadExecutor();
        executor.shutdown();
        assertTrue(executor.isShutdown() && executor.isTerminated());
        expectThrows(RejectedExecutionException.class, () -> executor.submit(() -> {}));
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls1() {
        var executor = Executors.newVirtualThreadExecutor();
        executor.submit((Runnable) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls2() {
        var executor = Executors.newVirtualThreadExecutor();
        executor.submit((Callable<String>) null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitNulls3() {
        var executor = Executors.newVirtualThreadExecutor();
        executor.submit((Collection<? extends Callable<String>>) null);
    }

    /**
     * Test invokeAny where all tasks complete normally.
     */
    public void testInvokeAnyCompleteNormally1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "bar";
            };
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result));
        }
    }

    /**
     * Test invokeAny where all tasks complete with exception.
     */
    public void testInvokeAnyCompleteExceptionally1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
     * Test invokeAny where all tasks complete with exception.
     */
    public void testInvokeAnyCompleteExceptionally2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
    public void testInvokeAnyCancelRemaining() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            DelayedResult<String> task1 = new DelayedResult("foo", Duration.ofMillis(50));
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            String result = executor.invokeAny(Set.of(task1, task2));
            assertTrue("foo".equals(result) && task1.isDone());
            while (!task2.isDone()) {
                Thread.sleep(Duration.ofMillis(100));
            }
            assertTrue(task2.exception() instanceof InterruptedException);
        }
    }

    /**
     * Test invokeAny with interrupt status set.
     */
    public void testInvokeAnyInterrupt1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> {
                Thread.sleep(Duration.ofMinutes(1));
                return "foo";
            };
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofMinutes(2));
                return "bar";
            };
            ScheduledInterrupter.schedule(Thread.currentThread(), 2000);
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
        var executor = Executors.newVirtualThreadExecutor();
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
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.invokeAny(Set.of());
        }
    }

    /**
     * Test invokeAny with empty collection.
     */
    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testInvokeAnyEmpty2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.invokeAny(Set.of(), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * Test invokeAll with deadline, deadline expires with running tasks.
     */
    public void testInvokeAnyWithDeadline()throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
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
    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.invokeAny(null);
        }
    }

    /**
     * Test invokeAny with null element
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAnyNull2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> list = new ArrayList<>();
            list.add(() -> "foo");
            list.add(null);
            executor.invokeAny(null);
        }
    }

    /**
     * Test invokeAll where all tasks complete normally.
     */
    public void testInvokeAll1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(1));
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
    public void testInvokeAll2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
    public void testInvokeAll3() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> {
                Thread.sleep(Duration.ofSeconds(1));
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
    public void testInvokeAll4() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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

            List<Future<String>> list = executor.invokeAll(List.of(task1, task2), 3, TimeUnit.SECONDS);

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
    public void testInvokeAll5() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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

            // tasl2 should have failed with an exception
            Throwable e2 = expectThrows(ExecutionException.class, () -> list.get(1).get());
            assertTrue(e2.getCause() instanceof BarException);

            // task3 should be cancelled
            expectThrows(CancellationException.class, () -> list.get(2).get());
        }
    }

    /**
     * Test invokeAll with cancelOnException=true, first task should be cancelled
     */
    public void testInvokeAll6() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
     * Test invokeAll with interrupt status set.
     */
    public void testInvokeAllInterrupt1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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

    public void testInvokeAllInterrupt2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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

    public void testInvokeAllInterrupt3() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
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
     * Test interrupt with thread blocked in invokeAll
     */
    public void testInvokeAllInterrupt4() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            ScheduledInterrupter.schedule(Thread.currentThread(), 2000);
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
     * Test interrupt with thread blocked in invokeAll with timeout
     */
    public void testInvokeAllInterrupt5() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            ScheduledInterrupter.schedule(Thread.currentThread(), 2000);
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
     * Test interrupt with thread blocked in invokeAll cancelOnException=true
     */
    public void testInvokeAllInterrupt6() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            DelayedResult<String> task2 = new DelayedResult("bar", Duration.ofMinutes(1));
            ScheduledInterrupter.schedule(Thread.currentThread(), 2000);
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
     * Test invokeAll after ExecutorService has been shutdown.
     */
    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown1() throws Exception {
        var executor = Executors.newVirtualThreadExecutor();
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2));
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown2() throws Exception {
        var executor = Executors.newVirtualThreadExecutor();
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), 1, TimeUnit.SECONDS);
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testInvokeAllAfterShutdown3() throws Exception {
        var executor = Executors.newVirtualThreadExecutor();
        executor.shutdown();

        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        executor.invokeAll(Set.of(task1, task2), true);
    }

    /**
     * Test invokeAll with empty collection.
     */
    public void testInvokeAllEmpty1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Future<Object>> list = executor.invokeAll(Set.of());
            assertTrue(list.size() == 0);
        }
    }

    public void testInvokeAllEmpty2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), 1, TimeUnit.SECONDS);
            assertTrue(list.size() == 0);
        }
    }

    public void testInvokeAllEmpty3() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Future<Object>> list = executor.invokeAll(Set.of(), true);
            assertTrue(list.size() == 0);
        }
    }

    /**
     * Test invokeAll with deadline, deadline expires with running tasks.
     */
    public void testInvokeAllWithDeadline()throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
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

            // executor should be shutdown and should terminate
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull1() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.invokeAll(null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull2() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull3() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.invokeAll(null, 1, TimeUnit.SECONDS);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull4() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task = () -> "foo";
            executor.invokeAll(List.of(task), 1, null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testInvokeAllNull5() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.invokeAll(tasks, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Deadline expires with running tasks, thread blocked in Future::get.
     */
    public void testDeadlineDuringFutureGet() throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
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
    public void testDeadlineDuringSubmit()throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
            List<Future<Void>> futures = executor.submit(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY))
                    .collect(Collectors.toList());
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
    public void testDeadlineDuringClose()throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        Future<?> future;
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
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
    public void testDeadlineAfterShutdown()throws Exception {
        var deadline = Instant.now().plusSeconds(5);
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
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
    public void testDeadlineAfterTerminate()throws Exception {
        var deadline = Instant.now().plusSeconds(10);
        Future<?> future;
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(deadline)) {
            future = executor.submit(() -> { });
        }
        assertTrue(future.get() == null);
    }

    /**
     * Deadline has already expired
     */
    public void testDeadlineAlreadyExpired1() throws Exception {
        // now
        Instant now = Instant.now();
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(now)) {
            assertTrue(executor.isTerminated());
        }
    }

    public void testDeadlineAlreadyExpired2() throws Exception {
        // in the past
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        try (ExecutorService executor = Executors.newVirtualThreadExecutor(yesterday)) {
            assertTrue(executor.isTerminated());
        }
    }

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
        ThreadFactory factory = Thread.builder().factory();
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

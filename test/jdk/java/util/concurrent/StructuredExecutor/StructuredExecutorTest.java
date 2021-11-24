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
 * @summary Basic tests for StructuredExecutor
 * @compile --enable-preview -source ${jdk.version} StructuredExecutorTest.java
 * @run testng/othervm --enable-preview StructuredExecutorTest
 */

import java.time.Duration;
import java.io.IOException;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.StructuredExecutor.CompletionHandler;
import java.util.concurrent.StructuredExecutor.ShutdownOnSuccess;
import java.util.concurrent.StructuredExecutor.ShutdownOnFailure;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class StructuredExecutorTest {

    private ScheduledExecutorService scheduler;

    private static class FooException extends RuntimeException {
        FooException() { }
        FooException(Throwable cause) { super(cause); }
    }

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
        var defaultThreadFactory = Executors.defaultThreadFactory();
        var virtualThreadFactory = Thread.ofVirtual().factory();
        return new Object[][] {
                { defaultThreadFactory, },
                { virtualThreadFactory, },
        };
    }

    /**
     * Test that each fork creates a thread.
     */
    @Test(dataProvider = "factories")
    public void testFork1(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var executor = StructuredExecutor.open(null, factory)) {
            for (int i = 0; i < 100; i++) {
                executor.fork(() -> count.incrementAndGet());
            }
            executor.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test that fork uses the specified thread factory.
     */
    @Test(dataProvider = "factories")
    public void testFork2(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory countingFactory = task -> {
            count.incrementAndGet();
            return factory.newThread(task);
        };
        try (var executor = StructuredExecutor.open(null, countingFactory)) {
            for (int i = 0; i < 100; i++) {
                executor.fork(() -> null);
            }
            executor.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test fork is confined to threads in the executor "tree".
     */
    public void testForkConfined() throws Exception {
        try (var executor1 = StructuredExecutor.open();
             var executor2 = StructuredExecutor.open()) {

            // thread in executor1 cannot fork thread in executor2
            Future<Void> future1 = executor1.fork(() -> {
                executor2.fork(() -> null).get();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // thread in executor2 can fork thread in executor1
            Future<Void> future2 = executor2.fork(() -> {
                executor1.fork(() -> null).get();
                return null;
            });
            future2.get();
            assertTrue(future2.resultNow() == null);

            // random thread cannot fork
            try (var pool = Executors.newCachedThreadPool()) {
                Future<Void> future = pool.submit(() -> {
                    executor1.fork(() -> null);
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            executor2.join();
            executor1.join();
        }
    }

    /**
     * Test fork when executor is shutdown.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterShutdown(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var executor = StructuredExecutor.open(null, factory)) {
            executor.shutdown();
            Future<String> future = executor.fork(() -> {
                count.incrementAndGet();
                return "foo";
            });
            assertTrue(future.isCancelled());
            executor.join();
        }
        assertTrue(count.get() == 0);   // check that task did not run.
    }

    /**
     * Test fork when executor is closed.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterClose(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            executor.join();
            executor.close();
            expectThrows(IllegalStateException.class, () -> executor.fork(() -> null));
        }
    }

    /**
     * Test fork when the thread factory rejects creating a thread.
     */
    public void testForkReject() throws Exception {
        ThreadFactory factory = task -> null;
        try (var executor = StructuredExecutor.open(null, factory)) {
            expectThrows(RejectedExecutionException.class, () -> executor.fork(() -> null));
            executor.join();
        }
    }

    /**
     * CompletionHandler that collects all Future objects notified to the handle method.
     */
    private static class CollectAll<V> implements CompletionHandler<V> {
        final StructuredExecutor executor;
        final List<Future<V>> futures = new CopyOnWriteArrayList<>();

        CollectAll(StructuredExecutor executor) {
            this.executor = executor;
        }

        @Override
        public void handle(StructuredExecutor executor, Future<V> future) {
            assertTrue(executor == this.executor);
            assertTrue(future.isDone());
            futures.add(future);
        }

        Stream<Future<V>> futures() {
            return futures.stream();
        }
    }

    /**
     * Test fork with handler operation. It should be invoked for tasks that
     * complete normally and abnormally.
     */
    @Test(dataProvider = "factories")
    public void testForkWithCompletionHandler1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            var handler = new CollectAll<String>(executor);

            // completes normally
            Future<String> future1 = executor.fork(() -> "foo", handler);

            // completes with exception
            Future<String> future2 = executor.fork(() -> {
                throw new FooException();
            }, handler);

            // cancelled
            Future<String> future3 = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            }, handler);
            future3.cancel(true);

            executor.join();

            Set<Future<String>> futures = handler.futures().collect(Collectors.toSet());
            assertEquals(futures, Set.of(future1, future2, future3));
        }
    }

    /**
     * Test fork with handler operation. It should not be invoked for tasks that
     * complete after the executor has been shutdown
     */
    @Test(dataProvider = "factories")
    public void testForkWithCompletionHandler2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            var handler = new CollectAll<String>(executor);

            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            Future<String> future1 = executor.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            }, handler);

            // start a second task to shutdown the executor after 500ms
            Future<String> future2 = executor.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                executor.shutdown();
                return null;
            });

            executor.join();

            // let task finish
            latch.countDown();

            // handler should not have been called
            assertTrue(future1.isDone());
            assertTrue(handler.futures().count() == 0L);
        }
    }

    /**
     * Test that each execute creates a thread.
     */
    @Test(dataProvider = "factories")
    public void testExecute1(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var executor = StructuredExecutor.open(null, factory)) {
            for (int i = 0; i < 100; i++) {
                executor.execute(() -> count.incrementAndGet());
            }
            executor.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test that execute uses the specified thread factory.
     */
    @Test(dataProvider = "factories")
    public void testExecute2(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        ThreadFactory countingFactory = task -> {
            count.incrementAndGet();
            return factory.newThread(task);
        };
        try (var executor = StructuredExecutor.open(null, countingFactory)) {
            for (int i = 0; i < 100; i++) {
                executor.execute(() -> { });
            }
            executor.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test execute throws when executor is shutdown.
     */
    public void testExecuteAfterShutdown() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var executor = StructuredExecutor.open()) {
            executor.shutdown();
            executor.execute(() -> count.incrementAndGet());
            executor.join();
        }
        assertTrue(count.get() == 0);   // check that task did not run.
    }

    /**
     * Test join with no threads.
     */
    public void testJoinWithNoThreads() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            executor.join();
        }
    }

    /**
     * Test join with threads running.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithThreads(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
            executor.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join is owner confined.
     */
    public void testJoinConfined() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            // attempt to join on thread in executor
            Future<Void> future1 = executor.fork(() -> {
                executor.join();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // random thread cannot join
            try (var pool = Executors.newCachedThreadPool()) {
                Future<Void> future2 = pool.submit(() -> {
                    executor.join();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            executor.join();
        }
    }

    /**
     * Test join with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                executor.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            executor.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                executor.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            executor.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join when executor is already shutdown.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });
            executor.shutdown();  // interrupts task
            executor.join();

            // task should have completed abnormally
            assertTrue(future.isDone() && future.state() != Future.State.SUCCESS);
        }
    }

    /**
     * Test shutdown when owner is blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future1 = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });

            CompletionHandler<String> handler = (e, f) -> e.shutdown();
            Future<String> future2 = executor.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return null;
            }, handler);
            executor.join();

            // task1 should have completed abnormally
            assertTrue(future1.isDone() && future1.state() != Future.State.SUCCESS);

            // task2 should have completed normally
            assertTrue(future2.isDone() && future2.state() == Future.State.SUCCESS);
        }
    }

    /**
     * Test join after executor is shutdown.
     */
    public void testJoinAfterShutdown() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            executor.shutdown();
            executor.join();
        }
    }

    /**
     * Test join after executor is closed.
     */
    public void testJoinAfterClose() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            executor.join();
            executor.close();
            expectThrows(IllegalStateException.class, () -> executor.join());
            expectThrows(IllegalStateException.class, () -> executor.joinUntil(Instant.now()));
        }
    }

    /**
     * Test joinUntil, threads finish before deadline expires.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            executor.joinUntil(Instant.now().plusSeconds(30));
            assertTrue(future.isDone() && future.resultNow() == null);
            checkDuration(startMillis, 1900, 4000);
        }
    }

    /**
     * Test joinUntil, deadline expires before threads finish.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            try {
                executor.joinUntil(Instant.now().plusSeconds(2));
            } catch (TimeoutException e) {
                checkDuration(startMillis, 1900, 4000);
            }
            assertFalse(future.isDone());
        }
    }

    /**
     * Test joinUntil many times.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil3(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {
                for (int i = 0; i < 3; i++) {
                    try {
                        executor.joinUntil(Instant.now().plusSeconds(1));
                        assertTrue(false);
                    } catch (TimeoutException expected) {
                        assertFalse(future.isDone());
                    }
                }
            } finally {
                future.cancel(true);
            }
        }
    }

    /**
     * Test joinUntil with a deadline that has already expired.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil4(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {

                // now
                try {
                    executor.joinUntil(Instant.now());
                    assertTrue(false);
                } catch (TimeoutException expected) {
                    assertFalse(future.isDone());
                }

                // in the past
                try {
                    executor.joinUntil(Instant.now().minusSeconds(1));
                    assertTrue(false);
                } catch (TimeoutException expected) {
                    assertFalse(future.isDone());
                }

            } finally {
                future.cancel(true);
            }
        }
    }

    /**
     * Test joinUntil with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoinUntil1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                executor.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            executor.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in joinUntil
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoinUntil2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                executor.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            executor.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test shutdown after executor is closed.
     */
    public void testShutdownAfterClose() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            executor.join();
            executor.close();
            expectThrows(IllegalStateException.class, () -> executor.shutdown());
        }
    }

    /**
     * Test close without join, no threads running.
     */
    public void testCloseWithoutJoin1() {
        var executor = StructuredExecutor.open();
        expectThrows(IllegalStateException.class, executor::close);
    }

    /**
     * Test close without join, threads running.
     */
    @Test(dataProvider = "factories")
    public void testCloseWithoutJoin2(ThreadFactory factory) {
        try (var executor = StructuredExecutor.open(null, factory)) {
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            expectThrows(IllegalStateException.class, executor::close);
            assertTrue(future.isDone() && future.exceptionNow() != null);
        }
    }

    /**
     * Test close is owner confined.
     */
    public void testCloseConfined() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            // attempt to close on thread in executor
            Future<Void> future1 = executor.fork(() -> {
                executor.close();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // random thread cannot close executor
            try (var pool = Executors.newCachedThreadPool()) {
                Future<Void> future2 = pool.submit(() -> {
                    executor.close();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            executor.join();
        }
    }

    /**
     * Test close with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptClose1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            executor.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            executor.shutdown();
            executor.join();

            // release task after a delay
            scheduler.schedule(latch::countDown, 1, TimeUnit.SECONDS);

            // invoke close with interrupt status set
            Thread.currentThread().interrupt();
            try {
                executor.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
            }
        }
    }

    /**
     * Test interrupting thread waiting in close.
     */
    @Test(dataProvider = "factories")
    public void testInterruptClose2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            executor.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            executor.shutdown();
            executor.join();

            // release task after a delay
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            scheduler.schedule(latch::countDown, 3, TimeUnit.SECONDS);
            try {
                executor.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
            }
        }
    }

    /**
     * Test that closing an enclosing executor closes the thread flock of a
     * nested executor.
     */
    public void testStructureViolation1() throws Exception {
        try (var executor1 = StructuredExecutor.open()) {
            try (var executor2 = StructuredExecutor.open()) {

                // join + close enclosing executor
                executor1.join();
                try {
                    executor1.close();
                    assertTrue(false);
                } catch (StructureViolationException expected) { }


                // underlying flock should be closed, fork should return a cancelled task
                AtomicBoolean ran = new AtomicBoolean();
                Future<String> future = executor2.fork(() -> {
                    ran.set(true);
                    return null;
                });
                assertTrue(future.isCancelled());
                executor2.join();
                assertFalse(ran.get());
            }
        }
    }

    /**
     * Test exiting a scope local operation should close the thread flock
     * is a nested executor.
     */
    public void testStructureViolation2() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        class Box {
            StructuredExecutor executor;
        }
        var box = new Box();
        try {
            try {
                ScopeLocal.where(name, "x1").run(() -> {
                    box.executor = StructuredExecutor.open();
                });
                assertTrue(false);
            } catch (StructureViolationException expected) { }

            // underlying flock should be closed, fork should return a cancelled task
            StructuredExecutor executor = box.executor;
            AtomicBoolean ran = new AtomicBoolean();
            Future<String> future = executor.fork(() -> {
                ran.set(true);
                return null;
            });
            assertTrue(future.isCancelled());
            executor.join();
            assertFalse(ran.get());

        } finally {
            StructuredExecutor executor = box.executor;
            if (executor != null) {
                executor.close();
            }
        }
    }

    /**
     * Test Future::get, task completes normally.
     */
    @Test(dataProvider = "factories")
    public void testFuture1(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {

            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                return "foo";
            });

            assertEquals(future.get(), "foo");
            assertTrue(future.state() == Future.State.SUCCESS);
            assertEquals(future.resultNow(), "foo");

            executor.join();
        }
    }

    /**
     * Test Future::get, task completes with exception.
     */
    @Test(dataProvider = "factories")
    public void testFuture2(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {

            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                throw new FooException();
            });

            Throwable ex = expectThrows(ExecutionException.class, future::get);
            assertTrue(ex.getCause() instanceof FooException);
            assertTrue(future.state() == Future.State.FAILED);
            assertTrue(future.exceptionNow() instanceof FooException);

            executor.join();
        }
    }

    /**
     * Test Future::get, task is cancelled.
     */
    @Test(dataProvider = "factories")
    public void testFuture3(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {

            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            // timed-get, should timeout
            try {
                future.get(100, TimeUnit.MICROSECONDS);
                assertTrue(false);
            } catch (TimeoutException expected) { }

            future.cancel(true);
            expectThrows(CancellationException.class, future::get);
            assertTrue(future.state() == Future.State.CANCELLED);

            executor.join();
        }
    }

    /**
     * Test executor shutdown with a thread blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testFutureWithShutdown(ThreadFactory factory) throws Exception {
        try (var executor = StructuredExecutor.open(null, factory)) {

            // long running task
            Future<String> future = executor.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            // start a thread to wait in Future::get
            AtomicBoolean waitDone = new AtomicBoolean();
            Thread waiter = Thread.startVirtualThread(() -> {
                try {
                    future.get();
                } catch (ExecutionException | CancellationException e) {
                    waitDone.set(true);
                } catch (InterruptedException e) {
                    System.out.println("waiter thread interrupted!");
                }
            });

            // shutdown executor
            executor.shutdown();

            // Future should be done and thread should be awakened
            assertTrue(future.isDone());
            waiter.join();
            assertTrue(waitDone.get());

            executor.join();
        }
    }

    /**
     * Test toString includes the executor name.
     */
    public void testToString() throws Exception {
        try (var executor = StructuredExecutor.open("xxx")) {
            // open
            assertTrue(executor.toString().contains("xxx"));

            // shutdown
            executor.shutdown();
            assertTrue(executor.toString().contains("xxx"));

            // closed
            executor.join();
            executor.close();
            assertTrue(executor.toString().contains("xxx"));
        }
    }

    /**
     * Test for NullPointerException.
     */
    public void testNulls() throws Exception {
        expectThrows(NullPointerException.class, () -> StructuredExecutor.open(null));
        expectThrows(NullPointerException.class, () -> StructuredExecutor.open("", null));

        try (var executor = StructuredExecutor.open()) {
            expectThrows(NullPointerException.class, () -> executor.fork(null));
            expectThrows(NullPointerException.class, () -> executor.fork(() -> null, null));
            expectThrows(NullPointerException.class, () -> executor.joinUntil(null));
            executor.join();
        }

        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> handler.handle(executor, null));
            expectThrows(NullPointerException.class, () -> handler.handle(null, future));
            expectThrows(NullPointerException.class, () -> handler.result(null));
            executor.join();
        }

        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            handler.handle(executor, future);
            expectThrows(NullPointerException.class, () -> handler.result(e -> null));
            executor.join();
        }

        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> handler.handle(executor, null));
            expectThrows(NullPointerException.class, () -> handler.handle(null, future));
            expectThrows(NullPointerException.class, () -> handler.throwIfFailed(null));
            executor.join();
        }

        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            handler.handle(executor, future);
            expectThrows(NullPointerException.class, () -> handler.throwIfFailed(e -> null));
            executor.join();
        }

        var handler = new ShutdownOnSuccess<String>();
        expectThrows(NullPointerException.class, () -> CompletionHandler.compose(handler, null));
        expectThrows(NullPointerException.class, () -> CompletionHandler.compose(null, handler));
        expectThrows(NullPointerException.class, () -> CompletionHandler.compose(null, null));
    }

    /**
     * Test ShutdownOnSuccess with no completed tasks.
     */
    public void testShutdownOnSuccess1() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<String>();

            // invoke handle with task that has not completed
            var future = new CompletableFuture<String>();
            expectThrows(IllegalArgumentException.class, () -> handler.handle(executor, future));

            // no tasks completed
            expectThrows(IllegalStateException.class, () -> handler.result());
            expectThrows(IllegalStateException.class, () -> handler.result(e -> null));

            executor.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally.
     */
    public void testShutdownOnSuccess2() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<String>();

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.complete("foo");
            future2.complete("bar");
            handler.handle(executor, future1);   // first
            handler.handle(executor, future2);

            assertEquals(handler.result(), "foo");
            assertEquals(handler.result(e -> null), "foo");

            executor.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally and abnormally.
     */
    public void testShutdownOnSuccess3() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<String>();

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.completeExceptionally(new ArithmeticException());
            future2.complete("foo");
            handler.handle(executor, future1);   // first
            handler.handle(executor, future2);

            assertEquals(handler.result(), "foo");
            assertEquals(handler.result(e -> null), "foo");

            executor.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a task that completed with an exception.
     */
    public void testShutdownOnSuccess4() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<String>();

            // failed task
            var future = new CompletableFuture<String>();
            future.completeExceptionally(new ArithmeticException());
            handler.handle(executor, future);

            Throwable ex = expectThrows(ExecutionException.class, () -> handler.result());
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            ex = expectThrows(FooException.class, () -> handler.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            executor.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a cancelled task.
     */
    public void testShutdownOnSuccess5() throws Exception {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnSuccess<String>();

            // cancelled task
            var future = new CompletableFuture<String>();
            future.cancel(false);
            handler.handle(executor, future);

            expectThrows(CancellationException.class, () -> handler.result());
            Throwable ex = expectThrows(FooException.class,
                                        () -> handler.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            executor.join();
        }
    }

    /**
     * Test ShutdownOnFailure with no completed tasks.
     */
    public void testShutdownOnFailure1() throws Throwable {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();

            // invoke handle with task that has not completed
            var future = new CompletableFuture<Object>();
            expectThrows(IllegalArgumentException.class, () -> handler.handle(executor, future));

            // no exception
            assertTrue(handler.exception().isEmpty());
            handler.throwIfFailed();
            handler.throwIfFailed(e -> new FooException(e));

            executor.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally.
     */
    public void testShutdownOnFailure2() throws Throwable {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();

            // tasks complete with result
            var future = new CompletableFuture<Object>();
            future.complete("foo");
            handler.handle(executor, future);

            // no exception
            assertTrue(handler.exception().isEmpty());
            handler.throwIfFailed();
            handler.throwIfFailed(e -> new FooException(e));

            executor.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally and abnormally.
     */
    public void testShutdownOnFailure3() throws Throwable {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();

            // tasks complete with result
            var future1 = new CompletableFuture<Object>();
            var future2 = new CompletableFuture<Object>();
            future1.complete("foo");
            future2.completeExceptionally(new ArithmeticException());
            handler.handle(executor, future1);
            handler.handle(executor, future2);

            Throwable ex = handler.exception().orElse(null);
            assertTrue(ex instanceof ArithmeticException);

            ex = expectThrows(ExecutionException.class, () -> handler.throwIfFailed());
            assertTrue(ex.getCause() instanceof ArithmeticException);

            ex = expectThrows(FooException.class,
                    () -> handler.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof ArithmeticException);

            executor.join();
        }
    }

    /**
     * Test ShutdownOnFailure with a cancelled task.
     */
    public void testShutdownOnFailure4() throws Throwable {
        try (var executor = StructuredExecutor.open()) {
            var handler = new ShutdownOnFailure();

            // cancelled task
            var future = new CompletableFuture<Object>();
            future.cancel(false);
            handler.handle(executor, future);

            Throwable ex = handler.exception().orElse(null);
            assertTrue(ex instanceof CancellationException);

            expectThrows(CancellationException.class, () -> handler.throwIfFailed());

            ex = expectThrows(FooException.class,
                    () -> handler.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            executor.join();
        }
    }

    /**
     * Test CompletionHandler.compose.
     */
    public void testCompletionHandlerCompose() throws Throwable {
        try (var executor = StructuredExecutor.open()) {

            // completed future
            var future = new CompletableFuture<String>();
            future.complete("foo");

            AtomicInteger counter = new AtomicInteger();

            // handler1 should run first
            CompletionHandler<String> handler1 = (e, f) -> {
                assertTrue(e == executor);
                assertTrue(f == future);
                assertTrue(counter.incrementAndGet() == 1);
            };

            // handler1 should run second
            CompletionHandler<String> handler2 = (e, f) -> {
                assertTrue(e == executor);
                assertTrue(f == future);
                assertTrue(counter.incrementAndGet() == 2);
            };

            var handler = CompletionHandler.compose(handler1, handler2);
            handler.handle(executor, future);
            
            executor.join();
        }
    }

    /**
     * Schedules a thread to be interrupted after the given delay.
     */
    private void scheduleInterrupt(Thread thread, Duration delay) {
        long millis = delay.toMillis();
        scheduler.schedule(thread::interrupt, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the current time in milliseconds.
     */
    private static long millisTime() {
        long now = System.nanoTime();
        return TimeUnit.MILLISECONDS.convert(now, TimeUnit.NANOSECONDS);
    }

    /**
     * Check the duration of a task
     * @param start start time, in milliseconds
     * @param min minimum expected duration, in milliseconds
     * @param max maximum expected duration, in milliseconds
     * @return the duration (now - start), in milliseconds
     */
    private static long checkDuration(long start, long min, long max) {
        long duration = millisTime() - start;
        assertTrue(duration >= min,
                "Duration " + duration + "ms, expected >= " + min + "ms");
        assertTrue(duration <= max,
                "Duration " + duration + "ms, expected <= " + max + "ms");
        return duration;
    }
}

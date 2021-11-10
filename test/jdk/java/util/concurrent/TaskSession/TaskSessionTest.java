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
 * @summary Basic tests for TaskSession
 * @compile --enable-preview -source ${jdk.version} TaskSessionTest.java
 * @run testng/othervm --enable-preview TaskSessionTest
 */

import java.time.Duration;
import java.io.IOException;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class TaskSessionTest {

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
        try (var session = TaskSession.open(null, factory)) {
            for (int i = 0; i < 100; i++) {
                session.fork(() -> count.incrementAndGet());
            }
            session.join();
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
        try (var session = TaskSession.open(null, countingFactory)) {
            for (int i = 0; i < 100; i++) {
                session.fork(() -> null);
            }
            session.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test fork is confined to threads in the session "tree".
     */
    public void testForkConfined() throws Exception {
        try (var session1 = TaskSession.open();
             var session2 = TaskSession.open()) {

            // thread in session1 cannot fork thread in session2
            Future<Void> future1 = session1.fork(() -> {
                session2.fork(() -> null).get();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // thread in session2 can fork thread in session1
            Future<Void> future2 = session2.fork(() -> {
                session1.fork(() -> null).get();
                return null;
            });
            future2.get();
            assertTrue(future2.resultNow() == null);

            // random thread cannot fork
            try (var executor = Executors.newCachedThreadPool()) {
                Future<Void> future = executor.submit(() -> {
                    session1.fork(() -> null);
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            session2.join();
            session1.join();
        }
    }

    /**
     * Test fork when session is shutdown.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterShutdown(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var session = TaskSession.open(null, factory)) {
            session.shutdown();
            Future<String> future = session.fork(() -> {
                count.incrementAndGet();
                return "foo";
            });
            assertTrue(future.isCancelled());
            session.join();
        }
        assertTrue(count.get() == 0);   // check that task did not run.
    }

    /**
     * Test fork when session is closed.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterClose(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            session.join();
            session.close();
            expectThrows(IllegalStateException.class, () -> session.fork(() -> null));
        }
    }

    /**
     * Test fork when the thread factory rejects creating a thread.
     */
    public void testForkReject() throws Exception {
        ThreadFactory factory = task -> null;
        try (var session = TaskSession.open(null, factory)) {
            expectThrows(RejectedExecutionException.class, () -> session.fork(() -> null));
            session.join();
        }
    }

    /**
     * BiConsumer that captures all Future objects notified to the accept method.
     */
    private static class OnComplete<V> implements BiConsumer<TaskSession, Future<V>> {
        final TaskSession session;
        final List<Future<V>> futures = new CopyOnWriteArrayList<>();

        OnComplete(TaskSession session) {
            this.session = session;
        }

        @Override
        public void accept(TaskSession session, Future<V> future) {
            assertTrue(session == this.session);
            assertTrue(future.isDone());
            futures.add(future);
        }

        Stream<Future<V>> futures() {
            return futures.stream();
        }
    }

    /**
     * Test fork with onComplete operation. It should be invoked for tasks that
     * complete normally and abnormally.
     */
    @Test(dataProvider = "factories")
    public void testForkOnCompleteOp1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            var onComplete = new OnComplete<String>(session);

            // completes normally
            Future<String> future1 = session.fork(() -> "foo", onComplete);

            // completes with exception
            Future<String> future2 = session.fork(() -> {
                throw new FooException();
            }, onComplete);

            // cancelled
            Future<String> future3 = session.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            }, onComplete);
            future3.cancel(true);

            session.join();

            Set<Future<String>> futures = onComplete.futures().collect(Collectors.toSet());
            assertEquals(futures, Set.of(future1, future2, future3));
        }
    }

    /**
     * Test fork with onComplete operation. It should not be invoked for tasks that
     * complete after the session has been shutdown
     */
    @Test(dataProvider = "factories")
    public void testForkOnCompleteOp2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            var onComplete = new OnComplete<String>(session);

            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            Future<String> future1 = session.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            }, onComplete);

            // start a second task to shutdown the session after 500ms
            Future<String> future2 = session.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                session.shutdown();
                return null;
            });

            session.join();

            // let task finish
            latch.countDown();

            // onComplete should not have been called
            assertTrue(future1.isDone());
            assertTrue(onComplete.futures().count() == 0L);
        }
    }

    /**
     * Test join with no threads.
     */
    public void testJoinWithNoThreads() throws Exception {
        try (var session = TaskSession.open()) {
            session.join();
        }
    }

    /**
     * Test join with threads running.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithThreads(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
            session.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join is owner confined.
     */
    public void testJoinConfined() throws Exception {
        try (var session = TaskSession.open()) {
            // attempt to join on thread in session
            Future<Void> future1 = session.fork(() -> {
                session.join();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // random thread cannot join
            try (var executor = Executors.newCachedThreadPool()) {
                Future<Void> future2 = executor.submit(() -> {
                    session.join();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            session.join();
        }
    }

    /**
     * Test join with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                session.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            session.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                session.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            session.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join when session is already shutdown.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });
            session.shutdown();  // interrupts task
            session.join();

            // task should have completed abnormally
            assertTrue(future.isDone() && future.exceptionNow() != null);
        }
    }

    /**
     * Test shutdown when owner is blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future1 = session.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });

            BiConsumer<TaskSession, Future<String>> onComplete = (s, f) -> s.shutdown();
            Future<String> future2 = session.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return null;
            }, onComplete);
            session.join();

            // task1 should have completed abnormally
            assertTrue(future1.isDone() && future1.exceptionNow() != null);

            // task2 should have completed normally
            assertTrue(future2.isDone() && future2.resultNow() == null);
        }
    }

    /**
     * Test join after session is shutdown.
     */
    public void testJoinAfterShutdown() throws Exception {
        try (var session = TaskSession.open()) {
            session.shutdown();
            session.join();
        }
    }

    /**
     * Test join after session is closed.
     */
    public void testJoinAfterClose() throws Exception {
        try (var session = TaskSession.open()) {
            session.join();
            session.close();
            expectThrows(IllegalStateException.class, () -> session.join());
            expectThrows(IllegalStateException.class, () -> session.joinUntil(Instant.now()));
        }
    }

    /**
     * Test joinUntil, threads finish before deadline expires.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            session.joinUntil(Instant.now().plusSeconds(30));
            assertTrue(future.isDone() && future.resultNow() == null);
            checkDuration(startMillis, 1900, 4000);
        }
    }

    /**
     * Test joinUntil, deadline expires before threads finish.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            try {
                session.joinUntil(Instant.now().plusSeconds(2));
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
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {
                for (int i = 0; i < 3; i++) {
                    try {
                        session.joinUntil(Instant.now().plusSeconds(1));
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
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {

                // now
                try {
                    session.joinUntil(Instant.now());
                    assertTrue(false);
                } catch (TimeoutException expected) {
                    assertFalse(future.isDone());
                }

                // in the past
                try {
                    session.joinUntil(Instant.now().minusSeconds(1));
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
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                session.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            session.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in joinUntil
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoinUntil2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                session.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            session.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test Future::get, task completes normally.
     */
    @Test(dataProvider = "factories")
    public void testFuture1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {

            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                return "foo";
            });

            assertEquals(future.get(), "foo");
            assertTrue(future.state() == Future.State.SUCCESS);
            assertEquals(future.resultNow(), "foo");

            session.join();
        }
    }

    /**
     * Test Future::get, task completes with exception.
     */
    @Test(dataProvider = "factories")
    public void testFuture2(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {

            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                throw new FooException();
            });

            Throwable ex = expectThrows(ExecutionException.class, future::get);
            assertTrue(ex.getCause() instanceof FooException);
            assertTrue(future.state() == Future.State.FAILED);
            assertTrue(future.exceptionNow() instanceof FooException);

            session.join();
        }
    }

    /**
     * Test Future::get, task is cancelled.
     */
    @Test(dataProvider = "factories")
    public void testFuture3(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {

            Future<String> future = session.fork(() -> {
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
            assertTrue(future.exceptionNow() instanceof CancellationException);

            session.join();
        }
    }

    /**
     * Test session shutdown with a thread blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testFutureWithShutdown(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {

            // long running task
            Future<String> future = session.fork(() -> {
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

            // shutdown session
            session.shutdown();

            // Future should be done and thread should be awakened
            assertTrue(future.isDone());
            waiter.join();
            assertTrue(waitDone.get());

            session.join();
        }
    }

    /**
     * Test that closing an enclosing session closes the thread flock of a
     * nested session.
     */
    public void testStructureViolation1() throws Exception {
        try (var session1 = TaskSession.open()) {
            try (var session2 = TaskSession.open()) {

                // join + close enclosing session
                session1.join();
                try {
                    session1.close();
                    assertTrue(false);
                } catch (StructureViolationException expected) { }


                // underlying flock should be closed, fork should return a cancelled task
                AtomicBoolean ran = new AtomicBoolean();
                Future<String> future = session2.fork(() -> {
                    ran.set(true);
                    return null;
                });
                assertTrue(future.isCancelled());
                session2.join();
                assertFalse(ran.get());
            }
        }
    }

    /**
     * Test exiting a scope local operation should close the thread flock
     * is a nested session.
     */
    public void testStructureViolation2() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        class Box {
            TaskSession session;
        }
        var box = new Box();
        try {
            try {
                ScopeLocal.where(name, "x1").run(() -> {
                    box.session = TaskSession.open();
                });
                assertTrue(false);
            } catch (StructureViolationException expected) { }

            // underlying flock should be closed, fork should return a cancelled task
            TaskSession session = box.session;
            AtomicBoolean ran = new AtomicBoolean();
            Future<String> future = session.fork(() -> {
                ran.set(true);
                return null;
            });
            assertTrue(future.isCancelled());
            session.join();
            assertFalse(ran.get());

        } finally {
            TaskSession session = box.session;
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Test shutdown after session is closed.
     */
    public void testShutdownAfterClose() throws Exception {
        try (var session = TaskSession.open()) {
            session.join();
            session.close();
            expectThrows(IllegalStateException.class, () -> session.shutdown());
        }
    }

    /**
     * Test ShutdownOnSuccess with no completed tasks.
     */
    public void testShutdownOnSuccess1() throws Exception {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<String>();

            // invoke accept with task that has not completed
            var future = new CompletableFuture<String>();
            expectThrows(IllegalArgumentException.class, () -> handler.accept(session, future));

            // no tasks completed
            expectThrows(IllegalStateException.class, () -> handler.result());
            expectThrows(IllegalStateException.class, () -> handler.result(e -> null));

            session.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally.
     */
    public void testShutdownOnSuccess2() throws Exception {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<String>();

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.complete("foo");
            future2.complete("bar");
            handler.accept(session, future1);   // first
            handler.accept(session, future2);

            assertEquals(handler.result(), "foo");
            assertEquals(handler.result(e -> null), "foo");

            session.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally and abnormally.
     */
    public void testShutdownOnSuccess3() throws Exception {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<String>();

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.completeExceptionally(new ArithmeticException());
            future2.complete("foo");
            handler.accept(session, future1);   // first
            handler.accept(session, future2);

            assertEquals(handler.result(), "foo");
            assertEquals(handler.result(e -> null), "foo");

            session.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a task that completed with an exception.
     */
    public void testShutdownOnSuccess4() throws Exception {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<String>();

            // failed task
            var future = new CompletableFuture<String>();
            future.completeExceptionally(new ArithmeticException());
            handler.accept(session, future);

            Throwable ex = expectThrows(ExecutionException.class, () -> handler.result());
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            ex = expectThrows(FooException.class, () -> handler.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            session.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a cancelled task.
     */
    public void testShutdownOnSuccess5() throws Exception {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<String>();

            // cancelled task
            var future = new CompletableFuture<String>();
            future.cancel(false);
            handler.accept(session, future);

            expectThrows(CancellationException.class, () -> handler.result());
            Throwable ex = expectThrows(FooException.class,
                                        () -> handler.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            session.join();
        }
    }

    /**
     * Test ShutdownOnFailure with no completed tasks.
     */
    public void testShutdownOnFailure1() throws Throwable {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();

            // invoke accept with task that has not completed
            var future = new CompletableFuture<Object>();
            expectThrows(IllegalArgumentException.class, () -> handler.accept(session, future));

            // no exception
            assertTrue(handler.exception().isEmpty());
            handler.throwIfFailed();
            handler.throwIfFailed(e -> new FooException(e));

            session.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally.
     */
    public void testShutdownOnFailure2() throws Throwable {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();

            // tasks complete with result
            var future = new CompletableFuture<Object>();
            future.complete("foo");
            handler.accept(session, future);

            // no exception
            assertTrue(handler.exception().isEmpty());
            handler.throwIfFailed();
            handler.throwIfFailed(e -> new FooException(e));

            session.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally and abnormally.
     */
    public void testShutdownOnFailure3() throws Throwable {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();

            // tasks complete with result
            var future1 = new CompletableFuture<Object>();
            var future2 = new CompletableFuture<Object>();
            future1.complete("foo");
            future2.completeExceptionally(new ArithmeticException());
            handler.accept(session, future1);
            handler.accept(session, future2);

            Throwable ex = handler.exception().orElse(null);
            assertTrue(ex instanceof ArithmeticException);

            ex = expectThrows(ExecutionException.class, () -> handler.throwIfFailed());
            assertTrue(ex.getCause() instanceof ArithmeticException);

            ex = expectThrows(FooException.class,
                    () -> handler.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof ArithmeticException);

            session.join();
        }
    }

    /**
     * Test ShutdownOnFailure with a cancelled task.
     */
    public void testShutdownOnFailure4() throws Throwable {
        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();

            // cancelled task
            var future = new CompletableFuture<Object>();
            future.cancel(false);
            handler.accept(session, future);

            Throwable ex = handler.exception().orElse(null);
            assertTrue(ex instanceof CancellationException);

            expectThrows(CancellationException.class, () -> handler.throwIfFailed());

            ex = expectThrows(FooException.class,
                    () -> handler.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            session.join();
        }
    }

    /**
     * Test close without join, no threads running.
     */
    public void testCloseWithoutJoin1() {
        var session = TaskSession.open();
        expectThrows(IllegalStateException.class, session::close);
    }

    /**
     * Test close without join, threads running.
     */
    @Test(dataProvider = "factories")
    public void testCloseWithoutJoin2(ThreadFactory factory) {
        try (var session = TaskSession.open(null, factory)) {
            Future<String> future = session.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            expectThrows(IllegalStateException.class, session::close);
            assertTrue(future.isDone() && future.exceptionNow() != null);
        }
    }

    /**
     * Test close is owner confined.
     */
    public void testCloseConfined() throws Exception {
        try (var session = TaskSession.open()) {
            // attempt to close on thread in session
            Future<Void> future1 = session.fork(() -> {
                session.close();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof IllegalStateException);

            // random thread cannot close session
            try (var executor = Executors.newCachedThreadPool()) {
                Future<Void> future2 = executor.submit(() -> {
                    session.close();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof IllegalStateException);
            }

            session.join();
        }
    }

    /**
     * Test close with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptClose1(ThreadFactory factory) throws Exception {
        try (var session = TaskSession.open(null, factory)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            session.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            session.shutdown();
            session.join();

            // release task after a delay
            scheduler.schedule(latch::countDown, 1, TimeUnit.SECONDS);

            // invoke close with interrupt status set
            Thread.currentThread().interrupt();
            try {
                session.close();
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
        try (var session = TaskSession.open(null, factory)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            session.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            session.shutdown();
            session.join();

            // release task after a delay
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            scheduler.schedule(latch::countDown, 3, TimeUnit.SECONDS);
            try {
                session.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
            }
        }
    }

    /**
     * Test toString includes the session name.
     */
    public void testToString() throws Exception {
        try (var session = TaskSession.open("xxx")) {
            // open
            assertTrue(session.toString().contains("xxx"));

            // shutdown
            session.shutdown();
            assertTrue(session.toString().contains("xxx"));

            // closed
            session.join();
            session.close();
            assertTrue(session.toString().contains("xxx"));
        }
    }

    /**
     * Test for NullPointerException.
     */
    public void testNulls() throws Exception {
        expectThrows(NullPointerException.class, () -> TaskSession.open(null));
        expectThrows(NullPointerException.class, () -> TaskSession.open("", null));

        try (var session = TaskSession.open()) {
            expectThrows(NullPointerException.class, () -> session.fork(null));
            expectThrows(NullPointerException.class, () -> session.fork(() -> null, null));
            expectThrows(NullPointerException.class, () -> session.joinUntil(null));
            session.join();
        }

        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> handler.accept(session, null));
            expectThrows(NullPointerException.class, () -> handler.accept(null, future));
            expectThrows(NullPointerException.class, () -> handler.result(null));
            session.join();
        }

        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            handler.accept(session, future);
            expectThrows(NullPointerException.class, () -> handler.result(e -> null));
            session.join();
        }

        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> handler.accept(session, null));
            expectThrows(NullPointerException.class, () -> handler.accept(null, future));
            expectThrows(NullPointerException.class, () -> handler.throwIfFailed(null));
            session.join();
        }

        try (var session = TaskSession.open()) {
            var handler = new TaskSession.ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            handler.accept(session, future);
            expectThrows(NullPointerException.class, () -> handler.throwIfFailed(e -> null));
            session.join();
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

/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic tests for StructuredTaskScope
 * @compile --enable-preview -source ${jdk.version} StructuredTaskScopeTest.java
 * @run testng/othervm --enable-preview StructuredTaskScopeTest
 */

import java.time.Duration;
import java.io.IOException;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.StructuredTaskScope.CompletionPolicy;
import java.util.concurrent.StructuredTaskScope.ShutdownOnSuccess;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class StructuredTaskScopeTest {
    private final static CompletionPolicy<Object> NULL_POLICY = (s, f) -> { };

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
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            for (int i = 0; i < 100; i++) {
                scope.fork(() -> count.incrementAndGet());
            }
            scope.join();
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
        try (var scope = StructuredTaskScope.open(null, countingFactory, NULL_POLICY)) {
            for (int i = 0; i < 100; i++) {
                scope.fork(() -> null);
            }
            scope.join();
        }
        assertTrue(count.get() == 100);
    }

    /**
     * Test fork is confined to threads in the scope "tree".
     */
    @Test(dataProvider = "factories")
    public void testForkConfined(ThreadFactory factory) throws Exception {
        try (var scope1 = StructuredTaskScope.open();
             var scope2 = StructuredTaskScope.open()) {

            // thread in scope1 cannot fork thread in scope2
            Future<Void> future1 = scope1.fork(() -> {
                scope2.fork(() -> null).get();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof WrongThreadException);

            // thread in scope2 can fork thread in scope1
            Future<Void> future2 = scope2.fork(() -> {
                scope1.fork(() -> null).get();
                return null;
            });
            future2.get();
            assertTrue(future2.resultNow() == null);

            // random thread cannot fork
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Void> future = pool.submit(() -> {
                    scope1.fork(() -> null);
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future::get);
                assertTrue(ex.getCause() instanceof WrongThreadException);
            }

            scope2.join();
            scope1.join();
        }
    }

    /**
     * Test fork when scope is shutdown.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterShutdown(ThreadFactory factory) throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            scope.shutdown();
            Future<String> future = scope.fork(() -> {
                count.incrementAndGet();
                return "foo";
            });
            assertTrue(future.isCancelled());
            scope.join();
        }
        assertTrue(count.get() == 0);   // check that task did not run.
    }

    /**
     * Test fork when scope is closed.
     */
    @Test(dataProvider = "factories")
    public void testForkAfterClose(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            scope.join();
            scope.close();
            expectThrows(IllegalStateException.class, () -> scope.fork(() -> null));
        }
    }

    /**
     * Test fork when the thread factory rejects creating a thread.
     */
    public void testForkReject() throws Exception {
        ThreadFactory factory = task -> null;
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            expectThrows(RejectedExecutionException.class, () -> scope.fork(() -> null));
            scope.join();
        }
    }

    /**
     * CompletionPolicy that collects all Future objects notified to the handle method.
     */
    private static class CollectAll<V> implements CompletionPolicy<V> {
        private final List<Future<V>> futures = new CopyOnWriteArrayList<>();
        private volatile StructuredTaskScope<V> expectedScope;

        void setExpectedScope(StructuredTaskScope<V> scope) {
            expectedScope = scope;
        }

        @Override
        public void handle(StructuredTaskScope<V> scope, Future<V> future) {
            assertTrue(scope == expectedScope);
            assertTrue(future.isDone());
            futures.add(future);
        }

        Stream<Future<V>> futures() {
            return futures.stream();
        }

        Set<Future<V>> futuresAsSet() {
            return futures.stream().collect(Collectors.toSet());
        }
    }

    /**
     * Test that the completion policy is invoked for tasks that complete normally and
     * and abnormally.
     */
    @Test(dataProvider = "factories")
    public void testCompletionPolicy1(ThreadFactory factory) throws Exception {
        try (var policy = new CollectAll<String>();
             var scope = StructuredTaskScope.open(null, factory, policy)) {

            policy.setExpectedScope(scope);

            // completes normally
            Future<String> future1 = scope.fork(() -> "foo");

            // completes with exception
            Future<String> future2 = scope.fork(() -> { throw new FooException(); });

            // cancelled
            Future<String> future3 = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            future3.cancel(true);

            scope.join();

            Set<Future<String>> futures = policy.futuresAsSet();
            assertEquals(futures, Set.of(future1, future2, future3));
        }
    }

    /**
     * Test that the completion policy is not invoked after the scope has been shutdown.
     */
    @Test(dataProvider = "factories")
    public void testCompletionPolicy2(ThreadFactory factory) throws Exception {
        try (var policy = new CollectAll<String>();
             var scope = StructuredTaskScope.open(null, factory, policy)) {

            policy.setExpectedScope(scope);

            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            Future<String> future1 = scope.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            // start a second task to shutdown the scope after 500ms
            Future<String> future2 = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                scope.shutdown();
                return null;
            });

            scope.join();

            // let task finish
            latch.countDown();

            // policy should not have been called
            assertTrue(future1.isDone());
            assertTrue(policy.futures().count() == 0L);
        }
    }

    /**
     * Test that fork inherits a scope-local binding.
     */
    public void testForkInheritsScopeLocals1() throws Exception {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();
        String value = ScopeLocal.where(NAME, "x").call(() -> {
            try (var scope = StructuredTaskScope.open()) {
                Future<String> future = scope.fork(() -> {
                    // child
                    return NAME.get();
                });
                scope.join();
                return future.resultNow();
            }
        });
        assertEquals(value, "x");
    }

    /**
     * Test that fork inherits a scope-local binding.
     */
    public void testForkInheritsScopeLocals2() throws Exception {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();
        try (var ignore = ScopeLocal.where(NAME, "x").bind();
             var scope = StructuredTaskScope.open()) {
            Future<String> future = scope.fork(() -> {
                // child
                return NAME.get();
            });
            scope.join();
            assertEquals(future.resultNow(), "x");
        }
    }

    /**
     * Test that fork inherits a scope-local binding into a grandchild.
     */
    public void testForkInheritsScopeLocals3() throws Exception {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();
        String value = ScopeLocal.where(NAME, "x").call(() -> {
            try (var scope1 = StructuredTaskScope.open()) {
                Future<String> future1 = scope1.fork(() -> {
                    try (var scope2 = StructuredTaskScope.open()) {
                        Future<String> future2 = scope2.fork(() -> {
                            // grandchild
                            return NAME.get();
                        });
                        scope2.join();
                        return future2.resultNow();
                    }
                });
                scope1.join();
                return future1.resultNow();
            }
        });
        assertEquals(value, "x");
    }

    /**
     * Test that fork inherits a scope-local binding into a grandchild.
     */
    public void testForkInheritsScopeLocals4() throws Exception {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();

        try (var binding = ScopeLocal.where(NAME, "x").bind();
             var scope1 = StructuredTaskScope.open()) {

            Future<String> future1 = scope1.fork(() -> {
                try (var scope2 = StructuredTaskScope.open()) {
                    Future<String> future2 = scope2.fork(() -> {
                        // grandchild
                        return NAME.get();
                    });
                    scope2.join();
                    return future2.resultNow();
                }
            });

            scope1.join();
            assertEquals(future1.resultNow(), "x");
        }
    }

    /**
     * Test join with no threads.
     */
    public void testJoinWithNoThreads() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.join();
        }
    }

    /**
     * Test join with threads running.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithThreads(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
            scope.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join is owner confined.
     */
    @Test(dataProvider = "factories")
    public void testJoinConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            // attempt to join on thread in scope
            Future<Void> future1 = scope.fork(() -> {
                scope.join();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof WrongThreadException);

            // random thread cannot join
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Void> future2 = pool.submit(() -> {
                    scope.join();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof WrongThreadException);
            }

            scope.join();
        }
    }

    /**
     * Test join with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                scope.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            scope.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoin2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                scope.join();
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            scope.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test join when scope is already shutdown.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });
            scope.shutdown();  // interrupts task
            scope.join();

            // task should have completed abnormally
            assertTrue(future.isDone() && future.state() != Future.State.SUCCESS);
        }
    }

    /**
     * Test shutdown when owner is blocked in join.
     */
    @Test(dataProvider = "factories")
    public void testJoinWithShutdown2(ThreadFactory factory) throws Exception {
        try (CompletionPolicy<String> policy = (s, f) -> s.shutdown();
             var scope = StructuredTaskScope.open(null, factory, policy)) {
            Future<String> future1 = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });
            Future<String> future2 = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return null;
            });
            scope.join();

            // task1 should have completed abnormally
            assertTrue(future1.isDone() && future1.state() != Future.State.SUCCESS);

            // task2 should have completed normally
            assertTrue(future2.isDone() && future2.state() == Future.State.SUCCESS);
        }
    }

    /**
     * Test join after scope is shutdown.
     */
    public void testJoinAfterShutdown() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.shutdown();
            scope.join();
        }
    }

    /**
     * Test join after scope is closed.
     */
    public void testJoinAfterClose() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.join();
            scope.close();
            expectThrows(IllegalStateException.class, () -> scope.join());
            expectThrows(IllegalStateException.class, () -> scope.joinUntil(Instant.now()));
        }
    }

    /**
     * Test joinUntil, threads finish before deadline expires.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            scope.joinUntil(Instant.now().plusSeconds(30));
            assertTrue(future.isDone() && future.resultNow() == null);
            checkDuration(startMillis, 1900, 4000);
        }
    }

    /**
     * Test joinUntil, deadline expires before threads finish.
     */
    @Test(dataProvider = "factories")
    public void testJoinUntil2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            long startMillis = millisTime();
            try {
                scope.joinUntil(Instant.now().plusSeconds(2));
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
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {
                for (int i = 0; i < 3; i++) {
                    try {
                        scope.joinUntil(Instant.now().plusSeconds(1));
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
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) { }
                return null;
            });

            try {

                // now
                try {
                    scope.joinUntil(Instant.now());
                    assertTrue(false);
                } catch (TimeoutException expected) {
                    assertFalse(future.isDone());
                }

                // in the past
                try {
                    scope.joinUntil(Instant.now().minusSeconds(1));
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
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                scope.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            scope.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test interrupt of thread blocked in joinUntil
     */
    @Test(dataProvider = "factories")
    public void testInterruptJoinUntil2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                return "foo";
            });

            // join should throw
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            try {
                scope.joinUntil(Instant.now().plusSeconds(10));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            }

            // join should complete
            scope.join();
            assertEquals(future.resultNow(), "foo");
        }
    }

    /**
     * Test shutdown after scope is closed.
     */
    public void testShutdownAfterClose() throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            scope.join();
            scope.close();
            expectThrows(IllegalStateException.class, () -> scope.shutdown());
        }
    }

    /**
     * Test shutdown is confined to threads in the scope "tree".
     */
    @Test(dataProvider = "factories")
    public void testShutdownConfined(ThreadFactory factory) throws Exception {
        try (var scope1 = StructuredTaskScope.open();
             var scope2 = StructuredTaskScope.open()) {

            // random thread cannot shutdown
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Void> future = pool.submit(() -> {
                    scope1.shutdown();
                    return null;
                });
                Throwable ex = expectThrows(ExecutionException.class, future::get);
                assertTrue(ex.getCause() instanceof WrongThreadException);
            }

            // thread in scope1 cannot shutdown scope2
            Future<Void> future1 = scope1.fork(() -> {
                scope2.shutdown();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof WrongThreadException);

            // thread in scope2 can shutdown scope1
            Future<Void> future2 = scope2.fork(() -> {
                scope1.shutdown();
                return null;
            });
            future2.get();
            assertTrue(future2.resultNow() == null);

            scope2.join();
            scope1.join();
        }
    }

    /**
     * Test close without join, no threads forked.
     */
    public void testCloseWithoutJoin1() {
        try (var scope = StructuredTaskScope.open()) {
            // do nothing
        }
    }

    /**
     * Test close without join, threads forked.
     */
    @Test(dataProvider = "factories")
    public void testCloseWithoutJoin2(ThreadFactory factory) {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            expectThrows(IllegalStateException.class, scope::close);
            assertTrue(future.isDone() && future.exceptionNow() != null);
        }
    }

    /**
     * Test close with threads forked after join.
     */
    @Test(dataProvider = "factories")
    public void testCloseWithoutJoin3(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            scope.fork(() -> "foo");
            scope.join();

            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            expectThrows(IllegalStateException.class, scope::close);
            assertTrue(future.isDone() && future.exceptionNow() != null);
        }
    }

    /**
     * Test close is owner confined.
     */
    @Test(dataProvider = "factories")
    public void testCloseConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            // attempt to close on thread in scope
            Future<Void> future1 = scope.fork(() -> {
                scope.close();
                return null;
            });
            Throwable ex = expectThrows(ExecutionException.class, future1::get);
            assertTrue(ex.getCause() instanceof WrongThreadException);

            // random thread cannot close scope
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Void> future2 = pool.submit(() -> {
                    scope.close();
                    return null;
                });
                ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof WrongThreadException);
            }

            scope.join();
        }
    }

    /**
     * Test close with interrupt status set.
     */
    @Test(dataProvider = "factories")
    public void testInterruptClose1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            scope.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            scope.shutdown();
            scope.join();

            // release task after a delay
            scheduler.schedule(latch::countDown, 1, TimeUnit.SECONDS);

            // invoke close with interrupt status set
            Thread.currentThread().interrupt();
            try {
                scope.close();
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
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {
            var latch = new CountDownLatch(1);

            // start task that does not respond to interrupt
            scope.fork(() -> {
                boolean done = false;
                while (!done) {
                    try {
                        latch.await();
                        done = true;
                    } catch (InterruptedException e) { }
                }
                return null;
            });

            scope.shutdown();
            scope.join();

            // release task after a delay
            scheduleInterrupt(Thread.currentThread(), Duration.ofMillis(500));
            scheduler.schedule(latch::countDown, 3, TimeUnit.SECONDS);
            try {
                scope.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
            }
        }
    }

    /**
     * Test that closing an enclosing scope closes the thread flock of a
     * nested scope.
     */
    public void testStructureViolation1() throws Exception {
        try (var scope1 = StructuredTaskScope.open()) {
            try (var scope2 = StructuredTaskScope.open()) {

                // join + close enclosing scope
                scope1.join();
                try {
                    scope1.close();
                    assertTrue(false);
                } catch (StructureViolationException expected) { }

                // underlying flock should be closed, fork should return a cancelled task
                AtomicBoolean ran = new AtomicBoolean();
                Future<String> future = scope2.fork(() -> {
                    ran.set(true);
                    return null;
                });
                assertTrue(future.isCancelled());
                scope2.join();
                assertFalse(ran.get());
            }
        }
    }

    /**
     * Test exiting a scope local operation closes the thread flock of a
     * nested scope.
     */
    public void testStructureViolation2() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        class Box {
            StructuredTaskScope<Object> scope;
        }
        var box = new Box();
        try {
            try {
                ScopeLocal.where(name, "x").run(() -> {
                    box.scope = StructuredTaskScope.open();
                });
                assertTrue(false);
            } catch (StructureViolationException expected) { }

            // underlying flock should be closed, fork should return a cancelled task
            StructuredTaskScope<Object> scope = box.scope;
            AtomicBoolean ran = new AtomicBoolean();
            Future<String> future = scope.fork(() -> {
                ran.set(true);
                return null;
            });
            assertTrue(future.isCancelled());
            scope.join();
            assertFalse(ran.get());

        } finally {
            StructuredTaskScope<Object> scope = box.scope;
            if (scope != null) {
                scope.close();
            }
        }
    }

    /**
     * Test closing a scope local binder will close the thread flock of a
     * nested scope.
     */
    public void testStructureViolation3() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        StructuredTaskScope<Object> scope = null;
        try {
            try (var binding = ScopeLocal.where(name, "x").bind()) {
                scope = StructuredTaskScope.open();
            } catch (StructureViolationException expected) { }

            // underlying flock should be closed, fork should return a cancelled task
            AtomicBoolean ran = new AtomicBoolean();
            Future<String> future = scope.fork(() -> {
                ran.set(true);
                return null;
            });
            assertTrue(future.isCancelled());
            scope.join();
            assertFalse(ran.get());

        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    /**
     * Test that fork throws StructureViolationException if scope-local bindings
     * created after StructuredTaskScope is created.
     */
    public void testStructureViolation4() throws Exception {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();

        try (var scope = StructuredTaskScope.open()) {
            ScopeLocal.where(NAME, "x").run(() -> {
                expectThrows(StructureViolationException.class,
                             () -> scope.fork(() -> "foo"));
            });
        }

        try (var scope = StructuredTaskScope.open();
             var ignore = ScopeLocal.where(NAME, "x").bind()) {
            expectThrows(StructureViolationException.class,
                         () -> scope.fork(() -> "foo"));
        }
    }

    /**
     * Test that fork throws StructureViolationException if scope-local bindings
     * changed after StructuredTaskScope is created.
     */
    public void testStructureViolation5() throws Exception {
        ScopeLocal<String> NAME1 = ScopeLocal.newInstance();
        ScopeLocal<String> NAME2 = ScopeLocal.newInstance();

        // re-bind
        ScopeLocal.where(NAME1, "x").run(() -> {
            try (var scope = StructuredTaskScope.open()) {
                ScopeLocal.where(NAME1, "y").run(() -> {
                    expectThrows(StructureViolationException.class,
                                 () -> scope.fork(() -> "foo"));
                });
            }
        });

        // new binding
        ScopeLocal.where(NAME1, "x").run(() -> {
            try (var scope = StructuredTaskScope.open()) {
                ScopeLocal.where(NAME2, "y").run(() -> {
                    expectThrows(StructureViolationException.class,
                                 () -> scope.fork(() -> "foo"));
                });
            }
        });

        // re-bind
        try (var binding = ScopeLocal.where(NAME1, "x").bind();
             var scope = StructuredTaskScope.open()) {
            ScopeLocal.where(NAME1, "y").run(() -> {
                expectThrows(StructureViolationException.class,
                             () -> scope.fork(() -> "foo"));
            });
        }

        // new binding
        try (var binding = ScopeLocal.where(NAME1, "x").bind();
             var scope = StructuredTaskScope.open()) {
            ScopeLocal.where(NAME2, "y").run(() -> {
                expectThrows(StructureViolationException.class,
                             () -> scope.fork(() -> "foo"));
            });
        }
    }

    /**
     * Test Future::get, task completes normally.
     */
    @Test(dataProvider = "factories")
    public void testFuture1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {

            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                return "foo";
            });

            assertEquals(future.get(), "foo");
            assertTrue(future.state() == Future.State.SUCCESS);
            assertEquals(future.resultNow(), "foo");

            scope.join();
        }
    }

    /**
     * Test Future::get, task completes with exception.
     */
    @Test(dataProvider = "factories")
    public void testFuture2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {

            Future<String> future = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                throw new FooException();
            });

            Throwable ex = expectThrows(ExecutionException.class, future::get);
            assertTrue(ex.getCause() instanceof FooException);
            assertTrue(future.state() == Future.State.FAILED);
            assertTrue(future.exceptionNow() instanceof FooException);

            scope.join();
        }
    }

    /**
     * Test Future::get, task is cancelled.
     */
    @Test(dataProvider = "factories")
    public void testFuture3(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {

            Future<String> future = scope.fork(() -> {
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

            scope.join();
        }
    }

    /**
     * Test scope shutdown with a thread blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testFutureWithShutdown(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(null, factory, NULL_POLICY)) {

            // long running task
            Future<String> future = scope.fork(() -> {
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

            // shutdown scope
            scope.shutdown();

            // Future should be done and thread should be awakened
            assertTrue(future.isDone());
            waiter.join();
            assertTrue(waitDone.get());

            scope.join();
        }
    }

    /**
     * Test Future::cancel throws if invoked by a thread that is not in the tree.
     * @throws Exception
     */
    @Test(dataProvider = "factories")
    public void testFutureCancelConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open()) {
            Future<String> future1 = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "foo";
            });

            // random thread cannot cancel
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Void> future2 = pool.submit(() -> {
                    future1.cancel(true);
                    return null;
                });
                Throwable ex = expectThrows(ExecutionException.class, future2::get);
                assertTrue(ex.getCause() instanceof WrongThreadException);
            } finally {
                future1.cancel(true);
            }
            scope.join();
        }
    }

    /**
     * Test toString includes the scope name.
     */
    public void testToString() throws Exception {
        ThreadFactory factory = Thread.ofVirtual().factory();
        try (var scope = StructuredTaskScope.open("xxx", factory, NULL_POLICY)) {
            // open
            assertTrue(scope.toString().contains("xxx"));

            // shutdown
            scope.shutdown();
            assertTrue(scope.toString().contains("xxx"));

            // closed
            scope.join();
            scope.close();
            assertTrue(scope.toString().contains("xxx"));
        }
    }

    /**
     * Test generics. This is a compile-time test.
     */
    public void testGenerics() throws Exception {
    }

    /**
     * Test for NullPointerException.
     */
    public void testNulls() throws Exception {
        expectThrows(NullPointerException.class, () -> StructuredTaskScope.open(null));
        expectThrows(NullPointerException.class,
                     () -> StructuredTaskScope.open("", Thread.ofVirtual().factory(), null));
        expectThrows(NullPointerException.class,
                     () -> StructuredTaskScope.open("", null, NULL_POLICY));

        try (var scope = StructuredTaskScope.open()) {
            expectThrows(NullPointerException.class, () -> scope.fork(null));
            expectThrows(NullPointerException.class, () -> scope.joinUntil(null));
            scope.join();
        }

        try (var scope = StructuredTaskScope.open()) {
            var policy = new ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> policy.handle(scope, null));
            expectThrows(NullPointerException.class, () -> policy.handle(null, future));
            expectThrows(NullPointerException.class, () -> policy.result(null));
            scope.join();
        }

        try (var scope = StructuredTaskScope.open()) {
            var policy = new ShutdownOnSuccess<Object>();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            policy.handle(scope, future);
            expectThrows(NullPointerException.class, () -> policy.result(e -> null));
            scope.join();
        }

        try (var scope = StructuredTaskScope.open()) {
            var policy = new ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.complete(null);
            expectThrows(NullPointerException.class, () -> policy.handle(scope, null));
            expectThrows(NullPointerException.class, () -> policy.handle(null, future));
            expectThrows(NullPointerException.class, () -> policy.throwIfFailed(null));
            scope.join();
        }

        try (var scope = StructuredTaskScope.open()) {
            var policy = new ShutdownOnFailure();
            var future = new CompletableFuture<Object>();
            future.completeExceptionally(new FooException());
            policy.handle(scope, future);
            expectThrows(NullPointerException.class, () -> policy.throwIfFailed(e -> null));
            scope.join();
        }

        var policy = new ShutdownOnSuccess<String>();
        expectThrows(NullPointerException.class, () -> CompletionPolicy.compose(policy, null));
        expectThrows(NullPointerException.class, () -> CompletionPolicy.compose(null, policy));
        expectThrows(NullPointerException.class, () -> CompletionPolicy.compose(null, null));
    }

    /**
     * Test ShutdownOnSuccess with no completed tasks.
     */
    public void testShutdownOnSuccess1() throws Exception {
        try (var policy = new ShutdownOnSuccess<String>();
             var scope = StructuredTaskScope.open(policy)) {

            // invoke handle with task that has not completed
            var future = new CompletableFuture<String>();
            expectThrows(IllegalArgumentException.class, () -> policy.handle(scope, future));

            // no tasks completed
            expectThrows(IllegalStateException.class, () -> policy.result());
            expectThrows(IllegalStateException.class, () -> policy.result(e -> null));

            scope.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally.
     */
    public void testShutdownOnSuccess2() throws Exception {
        try (var policy = new ShutdownOnSuccess<String>();
             var scope = StructuredTaskScope.open(policy)) {

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.complete("foo");
            future2.complete("bar");
            policy.handle(scope, future1);   // first
            policy.handle(scope, future2);

            assertEquals(policy.result(), "foo");
            assertEquals(policy.result(e -> null), "foo");

            scope.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with tasks that completed normally and abnormally.
     */
    public void testShutdownOnSuccess3() throws Exception {
        try (var policy = new ShutdownOnSuccess<String>();
             var scope = StructuredTaskScope.open(policy)) {

            // tasks complete with result
            var future1 = new CompletableFuture<String>();
            var future2 = new CompletableFuture<String>();
            future1.completeExceptionally(new ArithmeticException());
            future2.complete("foo");
            policy.handle(scope, future1);   // first
            policy.handle(scope, future2);

            assertEquals(policy.result(), "foo");
            assertEquals(policy.result(e -> null), "foo");

            scope.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a task that completed with an exception.
     */
    public void testShutdownOnSuccess4() throws Exception {
        try (var policy = new ShutdownOnSuccess<String>();
             var scope = StructuredTaskScope.open(policy)) {

            // failed task
            var future = new CompletableFuture<String>();
            future.completeExceptionally(new ArithmeticException());
            policy.handle(scope, future);

            Throwable ex = expectThrows(ExecutionException.class, () -> policy.result());
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            ex = expectThrows(FooException.class, () -> policy.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof  ArithmeticException);

            scope.join();
        }
    }

    /**
     * Test ShutdownOnSuccess with a cancelled task.
     */
    public void testShutdownOnSuccess5() throws Exception {
        try (var policy = new ShutdownOnSuccess<String>();
             var scope = StructuredTaskScope.open(policy)) {

            // cancelled task
            var future = new CompletableFuture<String>();
            future.cancel(false);
            policy.handle(scope, future);

            expectThrows(CancellationException.class, () -> policy.result());
            Throwable ex = expectThrows(FooException.class,
                                        () -> policy.result(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            scope.join();
        }
    }

    /**
     * Test ShutdownOnFailure with no completed tasks.
     */
    public void testShutdownOnFailure1() throws Throwable {
        try (var policy = new ShutdownOnFailure();
             var scope = StructuredTaskScope.open(policy)) {

            // invoke handle with task that has not completed
            var future = new CompletableFuture<Object>();
            expectThrows(IllegalArgumentException.class, () -> policy.handle(scope, future));

            // no exception
            assertTrue(policy.exception().isEmpty());
            policy.throwIfFailed();
            policy.throwIfFailed(e -> new FooException(e));

            scope.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally.
     */
    public void testShutdownOnFailure2() throws Throwable {
        try (var policy = new ShutdownOnFailure();
             var scope = StructuredTaskScope.open(policy)) {

            // tasks complete with result
            var future = new CompletableFuture<Object>();
            future.complete("foo");
            policy.handle(scope, future);

            // no exception
            assertTrue(policy.exception().isEmpty());
            policy.throwIfFailed();
            policy.throwIfFailed(e -> new FooException(e));

            scope.join();
        }
    }

    /**
     * Test ShutdownOnFailure with tasks that completed normally and abnormally.
     */
    public void testShutdownOnFailure3() throws Throwable {
        try (var policy = new ShutdownOnFailure();
             var scope = StructuredTaskScope.open(policy)) {

            // tasks complete with result
            var future1 = new CompletableFuture<Object>();
            var future2 = new CompletableFuture<Object>();
            future1.complete("foo");
            future2.completeExceptionally(new ArithmeticException());
            policy.handle(scope, future1);
            policy.handle(scope, future2);

            Throwable ex = policy.exception().orElse(null);
            assertTrue(ex instanceof ArithmeticException);

            ex = expectThrows(ExecutionException.class, () -> policy.throwIfFailed());
            assertTrue(ex.getCause() instanceof ArithmeticException);

            ex = expectThrows(FooException.class,
                    () -> policy.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof ArithmeticException);

            scope.join();
        }
    }

    /**
     * Test ShutdownOnFailure with a cancelled task.
     */
    public void testShutdownOnFailure4() throws Throwable {
        try (var policy = new ShutdownOnFailure();
             var scope = StructuredTaskScope.open(policy)) {

            // cancelled task
            var future = new CompletableFuture<Object>();
            future.cancel(false);
            policy.handle(scope, future);

            Throwable ex = policy.exception().orElse(null);
            assertTrue(ex instanceof CancellationException);

            expectThrows(CancellationException.class, () -> policy.throwIfFailed());

            ex = expectThrows(FooException.class,
                    () -> policy.throwIfFailed(e -> new FooException(e)));
            assertTrue(ex.getCause() instanceof CancellationException);

            scope.join();
        }
    }

    /**
     * Test CompletionPolicy.compose.
     */
    public void testCompletionPolicyCompose() throws Throwable {
        // completed future
        var future = new CompletableFuture<String>();
        future.complete("foo");

        var expectedScope = new AtomicReference<StructuredTaskScope<String>>();
        var counter = new AtomicInteger();


        // policy1 should run first
        CompletionPolicy<String> policy1 = (s, f) -> {
            assertTrue(s == expectedScope.get());
            assertTrue(f == future);
            assertTrue(counter.incrementAndGet() == 1);
        };

        // policy1 should run second
        CompletionPolicy<String> policy2 = (s, f) -> {
            assertTrue(s == expectedScope.get());
            assertTrue(f == future);
            assertTrue(counter.incrementAndGet() == 2);
        };

        var policy = CompletionPolicy.compose(policy1, policy2);

        try (var scope = StructuredTaskScope.open(policy)) {
            expectedScope.set(scope);
            policy.handle(scope, future);
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

/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @test id=platform
 * @bug 8284199 8296779 8306647
 * @summary Basic tests for StructuredTaskScope
 * @enablePreview
 * @run junit/othervm -DthreadFactory=platform StructuredTaskScopeTest
 */

/*
 * @test id=virtual
 * @enablePreview
 * @run junit/othervm -DthreadFactory=virtual StructuredTaskScopeTest
 */

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.*;
import java.util.concurrent.StructureViolationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import static java.lang.Thread.State.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class StructuredTaskScopeTest {
    private static ScheduledExecutorService scheduler;
    private static List<ThreadFactory> threadFactories;

    @BeforeAll
    static void setup() throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // thread factories
        String value = System.getProperty("threadFactory");
        List<ThreadFactory> list = new ArrayList<>();
        if (value == null || value.equals("platform"))
            list.add(Thread.ofPlatform().factory());
        if (value == null || value.equals("virtual"))
            list.add(Thread.ofVirtual().factory());
        assertTrue(list.size() > 0, "No thread factories for tests");
        threadFactories = list;
    }

    @AfterAll
    static void shutdown() {
        scheduler.shutdown();
    }

    private static Stream<ThreadFactory> factories() {
        return threadFactories.stream();
    }

    /**
     * Test that fork creates a new thread for each task.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkCreatesThread(ThreadFactory factory) throws Exception {
        Set<Long> tids = ConcurrentHashMap.newKeySet();
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            for (int i = 0; i < 50; i++) {
                // runnable
                scope.fork(() -> {
                    tids.add(Thread.currentThread().threadId());
                });

                // callable
                scope.fork(() -> {
                    tids.add(Thread.currentThread().threadId());
                    return null;
                });
            }
            scope.join();
        }
        assertEquals(100, tids.size());
    }

    /**
     * Test that fork creates virtual threads when no ThreadFactory is configured.
     */
    @Test
    void testForkCreateVirtualThread() throws Exception {
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            for (int i = 0; i < 50; i++) {
                // runnable
                scope.fork(() -> {
                    threads.add(Thread.currentThread());
                });

                // callable
                scope.fork(() -> {
                    threads.add(Thread.currentThread());
                    return null;
                });
            }
            scope.join();
        }
        assertEquals(100, threads.size());
        threads.forEach(t -> assertTrue(t.isVirtual()));
    }

    /**
     * Test that fork create threads with the configured ThreadFactory.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkUsesThreadFactory(ThreadFactory factory) throws Exception {
        var countingThreadFactory = new CountingThreadFactory(factory);
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(countingThreadFactory))) {

            for (int i = 0; i < 50; i++) {
                // runnable
                scope.fork(() -> { });

                // callable
                scope.fork(() -> null);
            }
            scope.join();
        }
        assertEquals(100, countingThreadFactory.threadCount());
    }

    /**
     * Test fork is owner confined.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<Boolean>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            // thread in scope cannot fork
            Subtask<Boolean> subtask = scope.fork(() -> {
                assertThrows(WrongThreadException.class, () -> {
                    scope.fork(() -> null);
                });
                return true;
            });
            scope.join();
            assertTrue(subtask.get());

            // random thread cannot fork
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<Void> future = pool.submit(() -> {
                    assertThrows(WrongThreadException.class, () -> {
                        scope.fork(() -> null);
                    });
                    return null;
                });
                future.get();
            }
        }
    }

    /**
     * Test fork after join, no subtasks forked before join.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkAfterJoin1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            scope.join();
            assertThrows(IllegalStateException.class, () -> scope.fork(() -> "bar"));
        }
    }

    /**
     * Test fork after join, subtasks forked before join.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkAfterJoin2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> "foo");
            scope.join();
            assertThrows(IllegalStateException.class, () -> scope.fork(() -> "bar"));
        }
    }

    /**
     * Test fork after join throws.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkAfterJoinThrows(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            var latch = new CountDownLatch(1);
            var subtask1 = scope.fork(() -> {
                latch.await();
                return "foo";
            });

            // join throws
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, scope::join);

            // allow subtask1 to finish
            latch.countDown();

            // continue to fork
            var subtask2 = scope.fork(() -> "bar");
            scope.join();
            assertEquals("foo", subtask1.get());
            assertEquals("bar", subtask2.get());
        }
    }

    /**
     * Test fork after task scope is cancelled.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkAfterCancel(ThreadFactory factory) throws Exception {
        var countingThreadFactory = new CountingThreadFactory(factory);
        var testPolicy = new CancelAfterOnePolicy<String>();

        try (var scope = StructuredTaskScope.open(testPolicy,
                cf -> cf.withThreadFactory(countingThreadFactory))) {

            // fork subtask, the scope should be cancelled when the subtask completes
            var subtask1 = scope.fork(() -> "foo");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            assertEquals(1, countingThreadFactory.threadCount());
            assertEquals(1, testPolicy.onForkCount());
            assertEquals(1, testPolicy.onCompleteCount());

            // fork second subtask, it should not run
            var subtask2 = scope.fork(() -> "bar");

            // onFork should be invoked, newThread and onComplete should not be invoked
            assertEquals(1, countingThreadFactory.threadCount());
            assertEquals(2, testPolicy.onForkCount());
            assertEquals(1, testPolicy.onCompleteCount());

            scope.join();

            assertEquals(1, countingThreadFactory.threadCount());
            assertEquals(2, testPolicy.onForkCount());
            assertEquals(1, testPolicy.onCompleteCount());
            assertEquals("foo", subtask1.get());
            assertEquals(Subtask.State.UNAVAILABLE, subtask2.state());
        }
    }

    /**
     * Test fork after task scope is closed.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testForkAfterClose(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            scope.close();
            assertThrows(IllegalStateException.class, () -> scope.fork(() -> null));
        }
    }

    /**
     * Test fork when the ThreadFactory rejects creating a thread.
     */
    @Test
    void testForkRejectedExecutionException() throws Exception {
        ThreadFactory factory = task -> null;
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            assertThrows(RejectedExecutionException.class, () -> scope.fork(() -> null));
        }
    }

    /**
     * Test join with no subtasks.
     */
    @Test
    void testJoinWithNoSubtasks() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            scope.join();
        }
    }

    /**
     * Test join with a running subtask.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinWithSubtasks(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            Subtask<String> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofMillis(100));
                return "foo";
            });
            scope.join();
            assertEquals("foo", subtask.get());
        }
    }

    /**
     * Test repated calls to join.
     */
    @Test
    void testJoinAfterJoin() throws Exception {
        var results = new LinkedTransferQueue<>(List.of("foo", "bar", "baz"));
        Policy<Object, String> policy = results::take;
        try (var scope = StructuredTaskScope.open(policy)) {
            scope.fork(() -> "foo");

            // each call to join should invoke Policy::result
            assertEquals("foo", scope.join());
            assertEquals("bar", scope.join());
            assertEquals("baz", scope.join());
        }
    }

    /**
     * Test join is owner confined.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<Boolean>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            // thread in scope cannot join
            Subtask<Boolean> subtask = scope.fork(() -> {
                assertThrows(WrongThreadException.class, () -> { scope.join(); });
                return true;
            });

            scope.join();

            assertTrue(subtask.get());

            // random thread cannot join
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<Void> future = pool.submit(() -> {
                    assertThrows(WrongThreadException.class, scope::join);
                    return null;
                });
                future.get();
            }
        }
    }

    /**
     * Test join with interrupt status set.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testInterruptJoin1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            var latch = new CountDownLatch(1);

            Subtask<String> subtask = scope.fork(() -> {
                latch.await();
                return "foo";
            });

            // join should throw
            Thread.currentThread().interrupt();
            try {
                scope.join();
                fail("join did not throw");
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be cleared
            } finally {
                // let task continue
                latch.countDown();
            }

            // join should complete
            scope.join();
            assertEquals("foo", subtask.get());
        }
    }

    /**
     * Test interrupt of thread blocked in join.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testInterruptJoin2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            var latch = new CountDownLatch(1);
            Subtask<String> subtask = scope.fork(() -> {
                latch.await();
                return "foo";
            });

            // join should throw
            scheduleInterruptAt("java.util.concurrent.StructuredTaskScope.join");
            try {
                scope.join();
                fail("join did not throw");
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());   // interrupt status should be clear
            } finally {
                // let task continue
                latch.countDown();
            }

            // join should complete
            scope.join();
            assertEquals("foo", subtask.get());
        }
    }

    /**
     * Test join when scope is cancelled.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinWhenCancelled(ThreadFactory factory) throws Exception {
        var countingThreadFactory = new CountingThreadFactory(factory);
        var testPolicy = new CancelAfterOnePolicy<String>();

        try (var scope = StructuredTaskScope.open(testPolicy,
                    cf -> cf.withThreadFactory(countingThreadFactory))) {

            // fork subtask, the scope should be cancelled when the subtask completes
            var subtask1 = scope.fork(() -> "foo");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            // fork second subtask, it should not run
            var subtask2 = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "bar";
            });

            scope.join();

            assertEquals("foo", subtask1.get());
            assertEquals(Subtask.State.UNAVAILABLE, subtask2.state());
        }
    }

    /**
     * Test join after scope is closed.
     */
    @Test
    void testJoinAfterClose() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            scope.close();
            assertThrows(IllegalStateException.class, () -> scope.join());
        }
    }

    /**
     * Test join with timeout, subtasks finish before timeout expires.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinWithTimeout1(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory)
                        .withTimeout(Duration.ofDays(1)))) {

            Subtask<String> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofSeconds(1));
                return "foo";
            });

            scope.join();

            assertFalse(scope.isCancelled());
            assertEquals("foo", subtask.get());
        }
    }

    /**
     * Test join with timeout, timeout expires before subtasks finish.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinWithTimeout2(ThreadFactory factory) throws Exception {
        long startMillis = millisTime();
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory)
                        .withTimeout(Duration.ofSeconds(2)))) {

            Subtask<Void> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            try {
                scope.join();
                fail();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof TimeoutException);
            }
            expectDuration(startMillis, /*min*/1900, /*max*/20_000);

            assertTrue(scope.isCancelled());
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
        }
    }

    /**
     * Test join with timeout that has already expired.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testJoinWithTimeout3(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory)
                        .withTimeout(Duration.ofSeconds(-1)))) {

            Subtask<Void> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            try {
                scope.join();
                fail();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof TimeoutException);
            }
            assertTrue(scope.isCancelled());
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
        }
    }

    /**
     * Test that cancel interrupts unfinished subtasks.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testCancelInterruptsThreads1(ThreadFactory factory) throws Exception {
        var testPolicy = new CancelAfterOnePolicy<String>();

        try (var scope = StructuredTaskScope.open(testPolicy,
                cf -> cf.withThreadFactory(factory))) {

            // fork subtask1 that runs for a long time
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var subtask1 = scope.fork(() -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            });
            started.await();

            // fork subtask2, the scope should be cancelled when the subtask completes
            var subtask2 = scope.fork(() -> "bar");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            // subtask1 should be interrupted
            interrupted.await();

            scope.join();

            assertEquals(Subtask.State.UNAVAILABLE, subtask1.state());
            assertEquals("bar", subtask2.get());
        }
    }

    /**
     * Test close without join, no subtasks forked.
     */
    @Test
    void testCloseWithoutJoin1() {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            // do nothing
        }
    }

    /**
     * Test close without join, unfinished subtasks.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testCloseWithoutJoin2(ThreadFactory factory) {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            Subtask<String> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            assertThrows(IllegalStateException.class, scope::close);

            // subtask result/exception not available
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertThrows(IllegalStateException.class, subtask::exception);
        }
    }

    /**
     * Test close after join throws. Close should not throw as join attempted.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testCloseAfterJoinThrows(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            var subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            // join throws
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, scope::join);
            assertThrows(IllegalStateException.class, subtask::get);

        }  // close should not throw
    }

    /**
     * Test close is owner confined.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testCloseConfined(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<Boolean>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {

            // attempt to close from thread in scope
            Subtask<Boolean> subtask = scope.fork(() -> {
                assertThrows(WrongThreadException.class, scope::close);
                return true;
            });

            scope.join();
            assertTrue(subtask.get());

            // random thread cannot close scope
            try (var pool = Executors.newCachedThreadPool(factory)) {
                Future<Boolean> future = pool.submit(() -> {
                    assertThrows(WrongThreadException.class, scope::close);
                    return null;
                });
                future.get();
            }
        }
    }

    /**
     * Test close with interrupt status set.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testInterruptClose1(ThreadFactory factory) throws Exception {
        var testPolicy = new CancelAfterOnePolicy<String>();
        try (var scope = StructuredTaskScope.open(testPolicy,
                cf -> cf.withThreadFactory(factory))) {

            // fork first subtask, a straggler as it continues after being interrupted
            var started = new CountDownLatch(1);
            var done = new AtomicBoolean();
            scope.fork(() -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (InterruptedException e) {
                    // interrupted by cancel, expected
                }
                Thread.sleep(Duration.ofMillis(100)); // force close to wait
                done.set(true);
                return null;
            });
            started.await();

            // fork second subtask, the scope should be cancelled when this subtask completes
            scope.fork(() -> "bar");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            scope.join();

            // invoke close with interrupt status set
            Thread.currentThread().interrupt();
            try {
                scope.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
                assertTrue(done.get());
            }
        }
    }

    /**
     * Test interrupting thread waiting in close.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testInterruptClose2(ThreadFactory factory) throws Exception {
        var testPolicy = new CancelAfterOnePolicy<String>();
        try (var scope = StructuredTaskScope.open(testPolicy,
                cf -> cf.withThreadFactory(factory))) {

            Thread mainThread = Thread.currentThread();

            // fork first subtask, a straggler as it continues after being interrupted
            var started = new CountDownLatch(1);
            var done = new AtomicBoolean();
            scope.fork(() -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (InterruptedException e) {
                    // interrupted by cancel, expected
                }

                // interrupt main thread when it blocks in close
                interruptThreadAt(mainThread, "java.util.concurrent.StructuredTaskScope.close");

                Thread.sleep(Duration.ofMillis(100)); // force close to wait
                done.set(true);
                return null;
            });
            started.await();

            // fork second subtask, the scope should be cancelled when this subtask completes
            scope.fork(() -> "bar");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            scope.join();

            // main thread will be interrupted while blocked in close
            try {
                scope.close();
            } finally {
                assertTrue(Thread.interrupted());   // clear interrupt status
                assertTrue(done.get());
            }
        }
    }

    /**
     * Test that closing an enclosing scope closes the thread flock of a nested scope.
     */
    @Test
    void testCloseThrowsStructureViolation() throws Exception {
        try (var scope1 = StructuredTaskScope.open(Policy.ignoreFailures())) {
            try (var scope2 = StructuredTaskScope.open(Policy.ignoreFailures())) {

                // close enclosing scope
                try {
                    scope1.close();
                    fail("close did not throw");
                } catch (StructureViolationException expected) { }

                // underlying flock should be closed
                var executed = new AtomicBoolean();
                Subtask<?> subtask = scope2.fork(() -> executed.set(true));
                assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
                scope2.join();
                assertFalse(executed.get());
                assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            }
        }
    }

    /**
     * Test that isCancelled returns true after close.
     */
    @Test
    void testIsCancelledAfterClose() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            assertFalse(scope.isCancelled());
            scope.close();
            assertTrue(scope.isCancelled());
        }
    }

    /**
     * Test Policy.onFork throwing exception.
     */
    @Test
    void testOnForkThrows() throws Exception {
        var policy = new Policy<String, Void>() {
            @Override
            public boolean onFork(Subtask<? extends String> subtask) {
                throw new FooException();
            }
            @Override
            public Void result() {
                return null;
            }
        };
        try (var scope = StructuredTaskScope.open(policy)) {
            assertThrows(FooException.class, () -> scope.fork(() -> "foo"));
        }
    }

    /**
     * Test Policy.onFork returning true to cancel execution.
     */
    @Test
    void testOnForkCancelsExecution() throws Exception {
        var policy = new Policy<String, Void>() {
            @Override
            public boolean onFork(Subtask<? extends String> subtask) {
                return true;
            }
            @Override
            public Void result() {
                return null;
            }
        };
        try (var scope = StructuredTaskScope.open(policy)) {
            assertFalse(scope.isCancelled());
            scope.fork(() -> "foo");
            assertTrue(scope.isCancelled());
            scope.join();
        }
    }

    /**
     * Test Policy.onComplete returning true to cancel execution.
     */
    @Test
    void testOnCompleteCancelsExecution() throws Exception {
        var policy = new Policy<String, Void>() {
            @Override
            public boolean onComplete(Subtask<? extends String> subtask) {
                return true;
            }
            @Override
            public Void result() {
                return null;
            }
        };
        try (var scope = StructuredTaskScope.open(policy)) {
            assertFalse(scope.isCancelled());
            scope.fork(() -> "foo");
            while (!scope.isCancelled()) {
                Thread.sleep(10);
            }
            scope.join();
        }
    }

    /**
     * Test toString.
     */
    @Test
    void testToString() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withName("duke"))) {

            // open
            assertTrue(scope.toString().contains("duke"));

            // closed
            scope.close();
            assertTrue(scope.toString().contains("duke"));
        }
    }

    /**
     * Test Subtask with task that completes successfully.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testSubtaskWhenSuccess(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            Subtask<String> subtask = scope.fork(() -> "foo");
            scope.join();

            // after join
            assertEquals(Subtask.State.SUCCESS, subtask.state());
            assertEquals("foo", subtask.get());
            assertThrows(IllegalStateException.class, subtask::exception);
        }
    }

    /**
     * Test Subtask with task that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testSubtaskWhenFailed(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            Subtask<String> subtask = scope.fork(() -> { throw new FooException(); });
            scope.join();

            // after join
            assertEquals(Subtask.State.FAILED, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertTrue(subtask.exception() instanceof FooException);
        }
    }

    /**
     * Test Subtask with a task that has not completed.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testSubtaskWhenNotCompleted(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            Subtask<Void> subtask = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            // before join
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertThrows(IllegalStateException.class, subtask::exception);

            // attempt join, join throws
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, scope::join);

            // after join
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertThrows(IllegalStateException.class, subtask::exception);
        }
    }

    /**
     * Test Subtask forked after execution cancelled.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testSubtaskWhenCancelled(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(new CancelAfterOnePolicy<String>())) {
            scope.fork(() -> "foo");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }

            var subtask = scope.fork(() -> "foo");

            // before join
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertThrows(IllegalStateException.class, subtask::exception);

            scope.join();

            // after join
            assertEquals(Subtask.State.UNAVAILABLE, subtask.state());
            assertThrows(IllegalStateException.class, subtask::get);
            assertThrows(IllegalStateException.class, subtask::exception);
        }
    }

    /**
     * Test Subtask::toString.
     */
    @Test
    void testSubtaskToString() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            var latch = new CountDownLatch(1);
            var subtask1 = scope.fork(() -> {
                latch.await();
                return "foo";
            });
            var subtask2 = scope.fork(() -> { throw new FooException(); });

            // subtask1 result is unavailable
            assertTrue(subtask1.toString().contains("Unavailable"));
            latch.countDown();

            scope.join();

            assertTrue(subtask1.toString().contains("Completed successfully"));
            assertTrue(subtask2.toString().contains("Failed"));
        }
    }

    /**
     * Test Policy.throwIfFailed() with no subtasks.
     */
    @Test
    void testThrowIfFailed1() throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.throwIfFailed())) {
            var result = scope.join();
            assertNull(result);
        }
    }

    /**
     * Test Policy.throwIfFailed() with subtasks that complete successfully.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testThrowIfFailed2(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>throwIfFailed(),
                cf -> cf.withThreadFactory(factory))) {
            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> "bar");
            var result = scope.join();
            assertNull(result);
            assertEquals("foo", subtask1.get());
            assertEquals("bar", subtask2.get());
        }
    }

    /**
     * Test Policy.throwIfFailed() with a subtask that complete successfully and
     * a subtask that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testThrowIfFailed3(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>throwIfFailed(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> "foo");
            scope.fork(() -> { throw new FooException(); });
            try {
                scope.join();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof FooException);
            }
        }
    }

    /**
     * Test Policy.allSuccessfulOrThrow() with no subtasks.
     */
    @Test
    void testAllSuccessful1() throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.allSuccessfulOrThrow())) {
            var subtasks = scope.join().toList();
            assertTrue(subtasks.isEmpty());
        }
    }

    /**
     * Test Policy.allSuccessfulOrThrow() with subtasks that complete successfully.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAllSuccessful2(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>allSuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> "bar");
            var subtasks = scope.join().toList();
            assertEquals(List.of(subtask1, subtask2), subtasks);
            assertEquals("foo", subtask1.get());
            assertEquals("bar", subtask2.get());
        }
    }

    /**
     * Test Policy.allSuccessfulOrThrow() with a subtask that complete successfully and
     * a subtask that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAllSuccessful3(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>allSuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> "foo");
            scope.fork(() -> { throw new FooException(); });
            try {
                scope.join();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof FooException);
            }
        }
    }

    /**
     * Test Policy.anySuccessfulOrThrow() with no subtasks.
     */
    @Test
    void testAnySuccessful1() throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.anySuccessfulOrThrow())) {
            try {
                scope.join();
            } catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof NoSuchElementException);
            }
        }
    }

    /**
     * Test Policy.anySuccessfulOrThrow() with a subtask that completes successfully.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAnySuccessful2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>anySuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> "foo");
            String result = scope.join();
            assertEquals("foo", result);
        }
    }

    /**
     * Test Policy.anySuccessfulOrThrow() with a subtask that completes successfully
     * with a null result.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAnySuccessful3(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>anySuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> null);
            String result = scope.join();
            assertNull(result);
        }
    }

    /**
     * Test Policy.anySuccessfulOrThrow() with a subtask that complete succcessfully and
     * a subtask that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAnySuccessful4(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>anySuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> "foo");
            scope.fork(() -> { throw new FooException(); });
            String first = scope.join();
            assertEquals("foo", first);
        }
    }

    /**
     * Test Policy.anySuccessfulOrThrow() with a subtask that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAnySuccessful5(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.anySuccessfulOrThrow(),
                cf -> cf.withThreadFactory(factory))) {
            scope.fork(() -> { throw new FooException(); });
            Throwable ex = assertThrows(ExecutionException.class, scope::join);
            assertTrue(ex.getCause() instanceof FooException);
        }
    }

    /**
     * Test Policy.ignoreFailures() with no subtasks.
     */
    @Test
    void testIgnoreFailures1() throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            var result = scope.join();
            assertNull(result);
        }
    }

    /**
     * Test Policy.ignoreFailures() with subtasks that complete successfully.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testIgnoreFailures2(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> "bar");
            var result = scope.join();
            assertNull(result);
            assertEquals("foo", subtask1.get());
            assertEquals("bar", subtask2.get());
        }
    }

    /**
     * Test Policy.ignoreFailures() with a subtask that complete successfully and
     * a subtask that fails.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testIgnoreFailures3(ThreadFactory factory) throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.<String>ignoreFailures(),
                cf -> cf.withThreadFactory(factory))) {
            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> { throw new FooException(); });
            var result = scope.join();
            assertNull(result);
            assertEquals("foo", subtask1.get());
            assertTrue(subtask2.exception() instanceof FooException);
        }
    }

    /**
     * Test Policy.allForked(Predicate) with no subtasks.
     */
    @Test
    void testAllForked1() throws Throwable {
        try (var scope = StructuredTaskScope.open(Policy.allForked(s -> false))) {
            var subtasks = scope.join();
            assertEquals(0, subtasks.count());
        }
    }

    /**
     * Test Policy.allForked(Predicate) with no cancellation.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAllForked2(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>allForked(s -> false),
                cf -> cf.withThreadFactory(factory))) {

            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> { throw new FooException(); });

            var subtasks = scope.join().toList();
            assertEquals(2, subtasks.size());

            assertTrue(subtasks.get(0) == subtask1);
            assertTrue(subtasks.get(1) == subtask2);
            assertEquals("foo", subtask1.get());
            assertTrue(subtask2.exception() instanceof FooException);
        }
    }

    /**
     * Test Policy.allForked(Predicate) with cancellation after one subtask completes.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAllForked3(ThreadFactory factory) throws Exception {
        try (var scope = StructuredTaskScope.open(Policy.<String>allForked(s -> true),
                cf -> cf.withThreadFactory(factory))) {

            var subtask1 = scope.fork(() -> "foo");
            var subtask2 = scope.fork(() -> {
                Thread.sleep(Duration.ofDays(1));
                return "bar";
            });

            var subtasks = scope.join().toList();

            assertEquals(2, subtasks.size());
            assertTrue(subtasks.get(0) == subtask1);
            assertTrue(subtasks.get(1) == subtask2);
            assertEquals("foo", subtask1.get());
            assertEquals(Subtask.State.UNAVAILABLE, subtask2.state());
        }
    }

    /**
     * Test Policy.allForked(Predicate) with cancellation after serveral subtasks completes.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testAllForked4(ThreadFactory factory) throws Exception {

        // cancel execution if 2 or more failures
        class AtMostTwoFailures<T> implements Predicate<Subtask<? extends T>> {
            final AtomicInteger failedCount = new AtomicInteger();
            @Override
            public boolean test(Subtask<? extends T> subtask) {
                return subtask.state() == Subtask.State.FAILED
                        && failedCount.incrementAndGet() >= 2;
            }
        }
        var policy = Policy.allForked(new AtMostTwoFailures<String>());

        try (var scope = StructuredTaskScope.open(policy)) {
            int forkCount = 0;

            // fork subtasks until execution cancelled
            while (!scope.isCancelled()) {
                scope.fork(() -> "foo");
                scope.fork(() -> { throw new FooException(); });
                forkCount += 2;
                Thread.sleep(Duration.ofMillis(10));
            }

            var subtasks = scope.join().toList();
            assertEquals(forkCount, subtasks.size());

            long failedCount = subtasks.stream()
                    .filter(s -> s.state() == Subtask.State.FAILED)
                    .count();
            assertTrue(failedCount >= 2);
        }
    }

    /**
     * Test Config equals/hashCode/toString
     */
    @Test
    void testConfigMethods() throws Exception {
        Function<Config, Config> testConfig = cf -> {
            var name = "duke";
            var threadFactory = Thread.ofPlatform().factory();
            var timeout = Duration.ofSeconds(10);

            assertEquals(cf, cf);
            assertEquals(cf.withName(name), cf.withName(name));
            assertEquals(cf.withThreadFactory(threadFactory), cf.withThreadFactory(threadFactory));
            assertEquals(cf.withTimeout(timeout), cf.withTimeout(timeout));

            assertNotEquals(cf, cf.withName(name));
            assertNotEquals(cf, cf.withThreadFactory(threadFactory));
            assertNotEquals(cf, cf.withTimeout(timeout));

            assertEquals(cf.withName(name).hashCode(), cf.withName(name).hashCode());
            assertEquals(cf.withThreadFactory(threadFactory).hashCode(),
                    cf.withThreadFactory(threadFactory).hashCode());
            assertEquals(cf.withTimeout(timeout).hashCode(), cf.withTimeout(timeout).hashCode());

            assertTrue(cf.withName(name).toString().contains(name));
            assertTrue(cf.withThreadFactory(threadFactory).toString().contains(threadFactory.toString()));
            assertTrue(cf.withTimeout(timeout).toString().contains(timeout.toString()));

            return cf;
        };
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures(), testConfig)) {
            // do nothing
        }
    }

    /**
     * Test Policy's default methods.
     */
    @Test
    void testPolicyDefaultMethods() throws Exception {
        try (var scope = StructuredTaskScope.open(new CancelAfterOnePolicy<String>())) {

            // need subtasks to test default methods
            var subtask1 = scope.fork(() -> "foo");
            while (!scope.isCancelled()) {
                Thread.sleep(20);
            }
            var subtask2 = scope.fork(() -> "bar");
            scope.join();

            assertEquals(Subtask.State.SUCCESS, subtask1.state());
            assertEquals(Subtask.State.UNAVAILABLE, subtask2.state());

            // Policy that does not override default methods
            Policy<Object, Void> policy = () -> null;
            assertThrows(NullPointerException.class, () -> policy.onFork(null));
            assertThrows(NullPointerException.class, () -> policy.onComplete(null));
            assertThrows(IllegalArgumentException.class, () -> policy.onFork(subtask1));
            assertFalse(policy.onFork(subtask2));
            assertFalse(policy.onComplete(subtask1));
            assertThrows(IllegalArgumentException.class, () -> policy.onComplete(subtask2));
        }
    }

    /**
     * Test for NullPointerException.
     */
    @Test
    void testNulls() throws Exception {
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(null));
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(null, cf -> cf));
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(Policy.ignoreFailures(), null));
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(Policy.ignoreFailures(), cf -> null));

        assertThrows(NullPointerException.class, () -> Policy.allForked(null));

        // fork
        try (var scope = StructuredTaskScope.open(Policy.ignoreFailures())) {
            assertThrows(NullPointerException.class, () -> scope.fork((Callable<Object>) null));
            assertThrows(NullPointerException.class, () -> scope.fork((Runnable) null));
        }

        // withXXX
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(Policy.ignoreFailures(), cf -> cf.withName(null)));
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(Policy.ignoreFailures(), cf -> cf.withThreadFactory(null)));
        assertThrows(NullPointerException.class,
                () -> StructuredTaskScope.open(Policy.ignoreFailures(), cf -> cf.withTimeout(null)));
    }

    /**
     * ThreadFactory that counts usage.
     */
    private static class CountingThreadFactory implements ThreadFactory {
        final ThreadFactory delegate;
        final AtomicInteger threadCount = new AtomicInteger();
        CountingThreadFactory(ThreadFactory delegate) {
            this.delegate = delegate;
        }
        @Override
        public Thread newThread(Runnable task) {
            threadCount.incrementAndGet();
            return delegate.newThread(task);
        }
        int threadCount() {
            return threadCount.get();
        }
    }

    /**
     * Policy that cancels execution when a subtask completes.
     */
    private static class CancelAfterOnePolicy<T> implements Policy<T, Void> {
        final AtomicInteger onForkCount = new AtomicInteger();
        final AtomicInteger onCompleteCount = new AtomicInteger();
        @Override
        public boolean onFork(Subtask<? extends T> subtask) {
            onForkCount.incrementAndGet();
            return false;
        }
        @Override
        public boolean onComplete(Subtask<? extends T> subtask) {
            onCompleteCount.incrementAndGet();
            return true;
        }
        @Override
        public Void result() {
            return null;
        }
        int onForkCount() {
            return onForkCount.get();
        }
        int onCompleteCount() {
            return onCompleteCount.get();
        }
    };

    /**
     * A runtime exception for tests.
     */
    private static class FooException extends RuntimeException {
        FooException() { }
        FooException(Throwable cause) { super(cause); }
    }

    /**
     * Returns the current time in milliseconds.
     */
    private long millisTime() {
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
    private long expectDuration(long start, long min, long max) {
        long duration = millisTime() - start;
        assertTrue(duration >= min,
                "Duration " + duration + "ms, expected >= " + min + "ms");
        assertTrue(duration <= max,
                "Duration " + duration + "ms, expected <= " + max + "ms");
        return duration;
    }

    /**
     * Interrupts a thread when it waits (timed or untimed) at location "{@code c.m}".
     * {@code c} is the fully qualified class name and {@code m} is the method name.
     */
    private void interruptThreadAt(Thread target, String location) throws InterruptedException {
        int index = location.lastIndexOf('.');
        String className = location.substring(0, index);
        String methodName = location.substring(index + 1);

        boolean found = false;
        while (!found) {
            Thread.State state = target.getState();
            assertTrue(state != TERMINATED);
            if ((state == WAITING || state == TIMED_WAITING)
                    && contains(target.getStackTrace(), className, methodName)) {
                found = true;
            } else {
                Thread.sleep(20);
            }
        }
        target.interrupt();
    }

    /**
     * Schedules the current thread to be interrupted when it waits (timed or untimed)
     * at the given location.
     */
    private void scheduleInterruptAt(String location) {
        Thread target = Thread.currentThread();
        scheduler.submit(() -> {
            interruptThreadAt(target, location);
            return null;
        });
    }

    /**
     * Returns true if the given stack trace contains an element for the given class
     * and method name.
     */
    private boolean contains(StackTraceElement[] stack, String className, String methodName) {
        return Arrays.stream(stack)
                .anyMatch(e -> className.equals(e.getClassName())
                        && methodName.equals(e.getMethodName()));
    }
}

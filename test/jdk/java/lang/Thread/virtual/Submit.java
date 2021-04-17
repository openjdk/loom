/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic tests for ExecutorService.submit with a virtual thread executor
 * @run testng Submit
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Submit {

    /**
     * Basic test of submit where the tasks completed immediately.
     */
    public void testBasic1() {
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Set<String> results = executor.submit(List.of(task1, task2))
                    .peek(f -> assertTrue(f.isDone()))
                    .map(Future::join)
                    .collect(Collectors.toSet());
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Basic test of submit where the tasks do not complete immediately.
     */
    public void testBasic2() {
        Callable<String> task1 = () -> {
            Thread.sleep(Duration.ofMillis(500));
            return "foo";
        };
        Callable<String> task2 = () -> {
            Thread.sleep(Duration.ofSeconds(1));
            return "bar";
        };
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Set<String> results = executor.submit(List.of(task1, task2))
                    .peek(f -> assertTrue(f.isDone()))
                    .map(Future::join)
                    .collect(Collectors.toSet());
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test closing a stream after it has been consumed.
     */
    public void testClose1() {
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        try (var executor = Executors.newVirtualThreadExecutor()) {
            try (Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {
                Set<String> results = stream
                        .peek(f -> assertTrue(f.isDone()))
                        .map(Future::join)
                        .collect(Collectors.toSet());
                assertEquals(results, Set.of("foo", "bar"));
            }
        }
    }

    /**
     * Test closing a stream before all tasks have completed. The remaining tasks
     * should be cancelled.
     */
    public void testClose2() throws Exception {
        AtomicBoolean taskStarted = new AtomicBoolean();
        AtomicReference<Throwable> taskException = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            taskStarted.set(true);
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (Exception e) {
                taskException.set(e);
            }
            return "bar";
        };

        try (var executor = Executors.newVirtualThreadExecutor()) {
            try (Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {
                String first = stream
                        .peek(f -> assertTrue(f.isDone()))
                        .map(Future::join)
                        .findFirst()
                        .orElseThrow();
                assertEquals(first, "foo");
            }

            // task2 sleep should be interrupted if task2 started
            if (taskStarted.get()) {
                Throwable exc;
                while ((exc = taskException.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

    /**
     * Test closing a stream while blocked waiting for an element.
     */
    public void testClose3() throws Exception {
        AtomicReference<Throwable> taskException = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                taskException.set(e);
            }
            return "bar";
        };

        try (var executor = Executors.newVirtualThreadExecutor()) {
            try (Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {

                // schedule close, give enough time for task1 and task2 to run
                Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(3));
                    } catch (InterruptedException ignore) { }
                    stream.close();
                });

                List<Future<String>> futures = stream
                        .peek(f -> assertTrue(f.isDone()))
                        .collect(Collectors.toList());
                assertTrue(futures.size() == 2);

                // task1
                Future<String> future1 = futures.get(0);
                assertEquals(future1.join(), "foo");

                // task2 sleep should be interrupted
                Future<String> future2 = futures.get(1);
                assertTrue(future2.isCancelled());
                Throwable exc;
                while ((exc = taskException.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

    /**
     * Test invoking an operation on the stream with the interrupt status set.
     */
    public void testInterrupt1() throws Exception {
        AtomicBoolean taskStarted = new AtomicBoolean();
        AtomicReference<Throwable> taskException = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            taskStarted.set(true);
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                taskException.set(e);
            }
            return "bar";
        };
        try (var executor = Executors.newVirtualThreadExecutor();
             Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {

            Thread.currentThread().interrupt();
            try {
                stream.peek(f -> assertTrue(f.isDone())).mapToLong(x -> 1L).sum();
                assertTrue(false);
            } catch (CancellationException e) {
                // interrupt status should be set
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }

            // task2 sleep should be interrupted if task2 started
            if (taskStarted.get()) {
                Throwable exc;
                while ((exc = taskException.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

    /**
     * Test interrupt a thread when blocked waiting for an element.
     */
    public void testInterrupt2() throws Exception {
        AtomicReference<Throwable> taskException = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                taskException.set(e);
            }
            return "bar";
        };

        try (var executor = Executors.newVirtualThreadExecutor();
             Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {

            // schedule main thread to be interrupted
            Thread thread = Thread.currentThread();
            executor.submit(() -> {
                Thread.sleep(Duration.ofSeconds(3));
                thread.interrupt();
                return null;
            });

            try {
                stream.peek(f -> assertTrue(f.isDone())).mapToLong(x -> 1L).sum();
                assertTrue(false);
            } catch (CancellationException e) {
                // interrupt status should be set
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // clear interrupt
            }

            // task2 should be cancelled
            Throwable exc;
            while ((exc = taskException.get()) == null) {
                Thread.sleep(20);
            }
            assertTrue(exc instanceof InterruptedException);
        }
    }

    /**
     * Test submit with an empty collection of tasks.
     */
    public void testEmpty() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            long count = executor.submit(List.of()).mapToLong(e -> 1L).sum();
            assertTrue(count == 0);
        }
    }

    /**
     * Test submit with a null value.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = null;
            executor.submit(tasks);
        }
    }

    /**
     * Test submit with a collection containing a null task.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(null);
            executor.submit(tasks);
        }
    }

    /**
     * Test submit with a collection containing a null task. Tasks submitted before
     * throwing NPE should be cancelled.
     */
    public void testNull3() throws Exception {
        AtomicBoolean taskStarted = new AtomicBoolean();
        AtomicReference<Throwable> taskException = new AtomicReference<>();
        Callable<String> task = () -> {
            taskStarted.set(true);
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                taskException.set(e);
            }
            return "bar";
        };

        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(task);
            tasks.add(null);
            try {
                executor.submit(tasks);
                assertTrue(false);
            } catch (NullPointerException expected) { }

            // if task ran then it should have been interrupted
            if (taskStarted.get()) {
                Throwable exc;
                while ((exc = taskException.get()) == null) {
                    Thread.sleep(20);
                }
                assertTrue(exc instanceof InterruptedException);
            }
        }
    }

}

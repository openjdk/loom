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
 * @run testng SubmitTasks
 * @summary Basic tests for submitTask/submitTasks with a virtual thread executor
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class SubmitTasks {

    public void testSubmitTask1() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            CompletableFuture<String> result = executor.submitTask(() -> {
                Thread.sleep(Duration.ofMillis(100));
                return "foo";
            });
            assertTrue("foo".equals(result.join()));
        }
    }

    public void testSubmitTask2() {
        class FooException extends RuntimeException { };
        try (var executor = Executors.newVirtualThreadExecutor()) {
            CompletableFuture<String> result = executor.submitTask(() -> {
                Thread.sleep(Duration.ofMillis(100));
                throw new FooException();
            });
            Throwable exc = expectThrows(ExecutionException.class, result::get);
            assertTrue(exc.getCause() instanceof FooException);
        }
    }

    public void testSubmitTasks1() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> "bar";
            List<CompletableFuture<String>> list = executor.submitTasks(List.of(task1, task2));
            assertTrue(list.size() == 2);
            assertTrue("foo".equals(list.get(0).join()));
            assertTrue("bar".equals(list.get(1).join()));
        }
    }

    public void testSubmitTasks2() {
        class FooException extends RuntimeException { };
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Callable<String> task1 = () -> "foo";
            Callable<String> task2 = () -> { throw new FooException(); };
            List<CompletableFuture<String>> list = executor.submitTasks(List.of(task1, task2));
            assertTrue(list.size() == 2);
            assertTrue("foo".equals(list.get(0).join()));
            CompletableFuture<String> result2 = list.get(1);
            Throwable exc = expectThrows(ExecutionException.class, result2::get);
            assertTrue(exc.getCause() instanceof FooException);
        }
    }

    /**
     * Test submitTasks with an empty collection
     */
    public void testSubmitTasks3() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<CompletableFuture<Object>> list = executor.submitTasks(List.of());
            assertTrue(list.size() == 0);
        }
    }

    /**
     * Test that cancel(false) does not interrupt the thread.
     */
    public void testCancel1() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadExecutor()) {
            CompletableFuture<String> result = executor.submitTask(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return "foo";
            });

            Thread.sleep(Duration.ofSeconds(1));  // give task time to sleep

            // cancel task, thread should not be interrupted
            result.cancel(false);
            expectThrows(CancellationException.class, result::get);
        }
        assertFalse(interrupted.get());
    }

    /**
     * Test that cancel(true) interrupts the thread.
     */
    public void testCancel2() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadExecutor()) {
            CompletableFuture<String> result = executor.submitTask(() -> {
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
                return "foo";
            });

            Thread.sleep(Duration.ofSeconds(1));  // give task time to sleep

            // cancel task, thread should be interrupted
            result.cancel(true);
            expectThrows(CancellationException.class, result::get);
        }
        assertTrue(interrupted.get());
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testSubmitTaskAfterShutdown() {
        var executor = Executors.newVirtualThreadExecutor();
        executor.close();
        CompletableFuture<String> result = executor.submitTask(() -> "foo");
    }

    @Test(expectedExceptions = { RejectedExecutionException.class })
    public void testSubmitTasksAfterShutdown() {
        var executor = Executors.newVirtualThreadExecutor();
        executor.close();
        Callable<String> task = () -> "foo";
        List<CompletableFuture<String>> results = executor.submitTasks(List.of(task));
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitTaskWithNull() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            CompletableFuture<Object> result = executor.submitTask(null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitTasksWithNull1() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            executor.submitTasks(null);
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testSubmitTasksWithNull2() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            List<Callable<String>> tasks = new ArrayList<>();
            tasks.add(() -> "foo");
            tasks.add(null);
            executor.submitTasks(tasks);
        }
    }
}
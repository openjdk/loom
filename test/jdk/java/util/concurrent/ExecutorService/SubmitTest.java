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
 * @summary Test default implementation of ExecutorService.submit
 * @run testng SubmitTest
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class SubmitTest {

    /**
     * Test submit where the tasks completed immediately.
     */
    public void testSubmit1() {
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        try (var executor = newExecutorService()) {
            Set<String> results = executor.submit(List.of(task1, task2))
                    .peek(f -> assertTrue(f.isDone()))
                    .map(Future::join)
                    .collect(Collectors.toSet());
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test submit where the tasks do not complete immediately.
     */
    public void testSubmit2() {
        Callable<String> task1 = () -> {
            Thread.sleep(Duration.ofMillis(500));
            return "foo";
        };
        Callable<String> task2 = () -> {
            Thread.sleep(Duration.ofSeconds(1));
            return "bar";
        };
        try (var executor = newExecutorService()) {
            Set<String> results = executor.submit(List.of(task1, task2))
                    .peek(f -> assertTrue(f.isDone()))
                    .map(Future::join)
                    .collect(Collectors.toSet());
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test submit with an empty collection of tasks.
     */
    public void testSubmit3() {
        try (var executor = newExecutorService()) {
            long count = executor.submit(List.of()).mapToLong(e -> 1L).sum();
            assertTrue(count == 0);
        }
    }

    /**
     * Test closing a stream after it has been consumed.
     */
    public void testCloseStream1() {
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> "bar";
        try (var executor = newExecutorService()) {
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
    public void testCloseStream2() throws Exception {
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

        try (var executor = newExecutorService()) {
            try (Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {
                String first = stream
                        .peek(f -> assertTrue(f.isDone()))
                        .map(Future::join)
                        .findFirst()
                        .orElseThrow();
                assertEquals(first, "foo");
            }

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
     * Test closing a stream while blocked waiting for an element.
     */
    public void testCloseStream3() throws Exception {
        AtomicInteger tasksStarted = new AtomicInteger();
        AtomicReference<Throwable> task2Exception = new AtomicReference<>();
        Callable<String> task1 = () -> {
            tasksStarted.incrementAndGet();
            return "foo";
        };
        Callable<String> task2 = () -> {
            tasksStarted.incrementAndGet();
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                task2Exception.set(e);
            }
            return "bar";
        };

        try (var executor = newExecutorService()) {
            try (Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {

                // schedule close, give enough time for tasks to start
                scheduleClose(stream, Duration.ofSeconds(1));

                List<Future<String>> futures = stream
                        .peek(f -> assertTrue(f.isDone()))
                        .collect(Collectors.toList());

                if (tasksStarted.get() == 2) {
                    assertTrue(futures.size() == 2);

                    int completed = 0, cancelled = 0;
                    for (Future<String> future : futures) {
                        if (future.isCompletedNormally()) {
                            completed++;
                        } else if (future.isCancelled()) {
                            cancelled++;
                        }
                    }
                    assertTrue((completed == 1 && cancelled == 1)
                            ^ (completed == 0 && cancelled == 2));

                    Future<String> future1 = futures.get(0);
                    Future<String> future2 = futures.get(1);
                    if (future2.isCompletedNormally()) {
                        Future<String> tmp = future1;
                        future1 = future2;
                        future2 = tmp;
                    }

                    if (future1.isCompletedNormally()) {
                        assertEquals(future1.join(), "foo");
                        assertTrue(future2.isCancelled());
                    } else {
                        assertTrue(future1.isCancelled());
                        assertTrue(future2.isCancelled());
                    }

                    // task2 sleep should be interrupted
                    Throwable exc;
                    while ((exc = task2Exception.get()) == null) {
                        Thread.sleep(20);
                    }
                    assertTrue(exc instanceof InterruptedException);
                }
            }
        }
    }

    /**
     * Test invoking an operation on the stream with the interrupt status set.
     */
    public void testInterruptStream1() throws Exception {
        AtomicBoolean task2Started = new AtomicBoolean();
        AtomicReference<Throwable> task2Exception = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            task2Started.set(true);
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                task2Exception.set(e);
            }
            return "bar";
        };

        try (var executor = newExecutorService();
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
     * Test interrupting a thread that is blocked on the stream waiting for
     * an element.
     */
    public void testInterruptStream2() throws Exception {
        AtomicBoolean task2Started = new AtomicBoolean();
        AtomicReference<Throwable> task2Exception = new AtomicReference<>();
        Callable<String> task1 = () -> "foo";
        Callable<String> task2 = () -> {
            task2Started.set(true);
            try {
                Thread.sleep(Duration.ofDays(1));
            } catch (InterruptedException e) {
                task2Exception.set(e);
            }
            return "bar";
        };

        try (var executor = newExecutorService();
             Stream<Future<String>> stream = executor.submit(List.of(task1, task2))) {

            // schedule main thread to be interrupted
            scheduleInterrupt(Thread.currentThread(), Duration.ofSeconds(1));
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
     * Test submit with a null value.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        try (var executor = newExecutorService()) {
            List<Callable<String>> tasks = null;
            executor.submit(tasks);
        }
    }

    /**
     * Test submit with a collection containing a null task.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        try (var executor = newExecutorService()) {
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

        try (var executor = newExecutorService()) {
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

    private static void scheduleClose(AutoCloseable closeable, Duration delay) {
        long millis = delay.toMillis();
        Callable<Void> action = () -> {
            closeable.close();
            return null;
        };
        SES.schedule(action, millis, TimeUnit.MILLISECONDS);
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

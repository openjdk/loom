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
 * @summary Test default implementation of ExecutorService.close
 * @run testng CloseTest
 */

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class CloseTest {

    /**
     * Test close with no tasks running.
     */
    public void testCloseWithNoTasks() throws Exception {
        ExecutorService executor = newExecutorService();
        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    /**
     * Test close with tasks running.
     */
    public void testCloseWithRunningTasks() throws Exception {
        ExecutorService executor = newExecutorService();
        Future<?> future = executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1));
            return "foo";
        });
        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        assertEquals(future.get(), "foo");
    }

    /**
     * Test close when executor is shutdown but not terminated.
     */
    public void testShutdownBeforeClose() throws Exception {
        ExecutorService executor = newExecutorService();

        Phaser phaser = new Phaser(2);
        Future<?> future = executor.submit(() -> {
            phaser.arriveAndAwaitAdvance();
            Thread.sleep(Duration.ofSeconds(1));
            return "foo";
        });
        phaser.arriveAndAwaitAdvance();
        executor.shutdown();  // shutdown, will not immediately terminate

        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
        assertEquals(future.get(), "foo");
    }

    /**
     * Test close when terminated.
     */
    public void testTerminateBeforeClose() throws Exception {
        ExecutorService executor = newExecutorService();
        executor.shutdown();
        assertTrue(executor.isTerminated());

        executor.close();
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10,  TimeUnit.MILLISECONDS));
    }

    /**
     * Test close with interrupt status set.
     */
    public void testInterruptBeforeClose() throws Exception {
        ExecutorService executor = newExecutorService();
        Future<?> future = executor.submit(() -> {
            Thread.sleep(Duration.ofDays(1));
            return null;
        });
        Thread.currentThread().interrupt();
        try {
            executor.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
        expectThrows(ExecutionException.class, future::get);
    }

    /**
     * Test interrupt when blocked in close.
     */
    public void testInterruptDuringClose() throws Exception {
        ExecutorService executor = newExecutorService();
        Future<?> future = executor.submit(() -> {
            Thread.sleep(Duration.ofDays(1));
            return null;
        });
        Thread thread = Thread.currentThread();
        new Thread(() -> {
            try { Thread.sleep( Duration.ofMillis(500)); } catch (Exception ignore) { }
            thread.interrupt();
        }).start();
        try {
            executor.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertTrue(executor.awaitTermination(10, TimeUnit.MILLISECONDS));
        expectThrows(ExecutionException.class, future::get);
    }

    /**
     * Wraps a ExecutorService with another ExecutorService that delegates. The
     * wrapper does not override the default methods to allow them to be tested.
     */
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
}

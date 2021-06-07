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
 * @summary Basic tests for structured exectors
 * @run testng/othervm/timeout=300 StructuredThreadPerTaskExecutorTest
 */

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.time.Instant;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class StructuredThreadPerTaskExecutorTest {
    // long running interruptible task
    private static final Callable<Void> SLEEP_FOR_A_DAY = () -> {
        Thread.sleep(Duration.ofDays(1));
        return null;
    };

    @DataProvider(name = "factories")
    public Object[][] factories() {
        return new Object[][] {
                { Executors.defaultThreadFactory(), },
                { Thread.ofVirtual().factory(), },
        };
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder1(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor1.close();
        executor2.close();
        executor3.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder2(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor1.close();
        executor3.close();
        executor2.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder3(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor2.close();
        executor1.close();
        executor3.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder4(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor2.close();
        executor3.close();
        executor1.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder5(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor3.close();
        executor1.close();
        executor2.close();
    }

    @Test(dataProvider = "factories")
    public void testCloseOrder6(ThreadFactory factory) throws Exception {
        var executor1 = Executors.newStructuredThreadExecutor(factory);
        var executor2 = Executors.newStructuredThreadExecutor(factory);
        var executor3 = Executors.newStructuredThreadExecutor(factory);
        executor3.close();
        executor2.close();
        executor1.close();
    }

    /**
     * Test submit task from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testSubmitByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newStructuredThreadExecutor(factory)) {
            Thread.ofVirtual().start(() -> {
                try {
                    executor.submit(() -> { });
                } catch (Exception e) {
                    exception.set(e);
                }
            }).join();
        }
        assertTrue(exception.get() instanceof IllegalCallerException);
    }

    /**
     * Test shutdown from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testShutdownByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newStructuredThreadExecutor(factory)) {
            Thread.ofVirtual().start(() -> {
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
     * Test shutdowNow from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testShutdownNowByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newStructuredThreadExecutor(factory)) {
            Thread.ofVirtual().start(() -> {
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
     * Test close from a thread that is not the owner.
     */
    @Test(dataProvider = "factories")
    public void testCloseByNonOwner(ThreadFactory factory) throws Exception {
        var exception = new AtomicReference<Exception>();
        try (var executor = Executors.newStructuredThreadExecutor(factory)) {
            Thread.ofVirtual().start(() -> {
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
     * Deadline expires before owner invokes close. The owner should be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineBeforeClose(ThreadFactory factory) {
        var deadline = Instant.now().plusSeconds(2);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
            Thread.sleep(Duration.ofDays(1));
            assertTrue(false);
        } catch (InterruptedException e) {
            assertFalse(Thread.currentThread().isInterrupted()); // should be cleared
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        }
    }

    /**
     * Deadline expires while owner is blocked in close. The owner should not be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInClose(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(2);
        var executor = Executors.newStructuredThreadExecutor(factory, deadline);
        Future<?> future = executor.submit(SLEEP_FOR_A_DAY);
        try {
            executor.close();
            assertTrue(false);
        } catch (DeadlineExpiredException e) {
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(future.isCompletedNormally());
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires after the executor is closed. The owner should not be interrupted.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAfterClose(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
        }
        Thread.sleep(Duration.ofSeconds(5)); // should not be interrupted
    }

    /**
     * Deadline expires with owner blocked in Future::get.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInFutureGet(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(2);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
            executor.submit(SLEEP_FOR_A_DAY).get();
            assertTrue(false);
        } catch (ExecutionException | InterruptedException e) {
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires with owner blocked in invokeAny.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInInvokeAny(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(1);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
            executor.invokeAny(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
            assertTrue(false);
        } catch (ExecutionException | InterruptedException e) {
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires with owner blocked in invokeAll.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInInvokeAll(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(1);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
            List<Future<Void>> futures = executor.invokeAll(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY));
            assertTrue(futures.size() == 2);
            assertFalse(futures.get(0).isCompletedNormally());
            assertFalse(futures.get(1).isCompletedNormally());
        } catch (DeadlineExpiredException e) {
            // possible
        } catch (InterruptedException e) {
            // invokeAll interrupted
            assertFalse(Thread.currentThread().isInterrupted());  // should be cleared
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline expires with owner blocked consuming stream.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineInStream(ThreadFactory factory) throws Exception {
        var deadline = Instant.now().plusSeconds(1);
        try (var executor = Executors.newStructuredThreadExecutor(factory, deadline)) {
            long completedNormally = executor.submit(List.of(SLEEP_FOR_A_DAY, SLEEP_FOR_A_DAY))
                    .filter(Future::isCompletedNormally)
                    .count();
            assertTrue(completedNormally == 0);
        } catch (DeadlineExpiredException e) {
            // possible
        } catch (CancellationException e) {
            // consuming stream interrupted
            assertTrue(Thread.currentThread().isInterrupted());
            Throwable[] suppressed = e.getSuppressed();
            assertTrue(suppressed[0] instanceof DeadlineExpiredException);
        } finally {
            Thread.interrupted();  // clear interrupt status
        }
    }

    /**
     * Deadline has already expired.
     */
    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired1(ThreadFactory factory) throws Exception {
        Instant now = Instant.now();
        var executor = Executors.newStructuredThreadExecutor(factory, now);
        assertTrue(Thread.interrupted());  // clears interrupt status
        assertTrue(executor.isTerminated());
        expectThrows(DeadlineExpiredException.class, executor::close);
    }

    @Test(dataProvider = "factories")
    public void testDeadlineAlreadyExpired2(ThreadFactory factory) throws Exception {
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        var executor = Executors.newStructuredThreadExecutor(factory, yesterday);
        assertTrue(Thread.interrupted());   // clears interrupt status
        assertTrue(executor.isTerminated());
        expectThrows(DeadlineExpiredException.class, executor::close);
    }

    /**
     * Test nulls.
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls1() {
        Executors.newStructuredThreadExecutor(null);
    }

    @Test(dataProvider = "factories", expectedExceptions = { NullPointerException.class })
    public void testNulls2(ThreadFactory factory) {
        Executors.newStructuredThreadExecutor(factory, null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNulls3() {
        Instant deadline = Instant.now().plusSeconds(10);
        Executors.newStructuredThreadExecutor(null, deadline);
    }
}

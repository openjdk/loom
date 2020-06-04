/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng WithDeadlineTest
 * @summary Basic tests for ExecutorExecutor.withDeadline
 */

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class WithDeadlineTest {

    private static final Duration ONE_DAY = Duration.ofDays(1);

    /**
     * Deadline expires with running tasks before the executor is shutdown.
     */
    public void testDeadlineBeforeShutdown() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(5);
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(deadline)) {
            // assume this is submitted before the deadline expires
            Future<?> result = executor.submit(() -> {
                Thread.sleep(ONE_DAY);
                return null;
            });

            // current thread should be interrupted
            expectThrows(InterruptedException.class, () -> Thread.sleep(ONE_DAY));

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, result::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor should be shutdown and should almost immediately
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }
    }

    /**
     * Deadline expires with running tasks after the executor is shutdown.
     */
    public void testDeadlineAfterShutdown() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(5);
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(deadline)) {
            // assume this is submitted before the deadline expires
            Future<?> result = executor.submit(() -> {
                Thread.sleep(ONE_DAY);
                return null;
            });
            executor.shutdown();

            // current thread should be interrupted
            expectThrows(InterruptedException.class, () -> Thread.sleep(ONE_DAY));

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, result::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor should almost terminate immediately
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }
    }

    /**
     * Deadline expires while the owner is waiting in close.
     */
    public void testDeadlineDuringClose() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(5);
        Future<?> result;
        try {
            try (var executor = Executors.newThreadExecutor(factory).withDeadline(deadline)) {
                // assume this is submitted before the deadline expires
                result = executor.submit(() -> {
                    Thread.sleep(ONE_DAY);
                    return null;
                });
            }

            // interrupt status should be set
            assertTrue(Thread.interrupted());

            // task should be interrupted
            Throwable e = expectThrows(ExecutionException.class, result::get);
            assertTrue(e.getCause() instanceof InterruptedException);

        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }
    }

    /**
     * Deadline expires after executor has terminated.
     */
    public void testDeadlineAfterTerminate() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(60);
        Future<?> result;
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(deadline)) {
            result = executor.submit(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return null;
            });
        }
        assertFalse(Thread.interrupted());
        assertTrue(result.get() == null);
    }

    /**
     * Deadline has already expired
     */
    public void testDeadlineAlreadyExpired() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();

        // now
        Instant now = Instant.now();
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(now)) {
            assertTrue(executor.isTerminated());
        }
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(now)) {
            assertTrue(Thread.interrupted());
            assertTrue(executor.isTerminated());
        }

        // in the past
        var yesterday = Instant.now().minus(ONE_DAY);
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(yesterday)) {
            assertTrue(executor.isTerminated());
        }
        try (var executor = Executors.newThreadExecutor(factory).withDeadline(yesterday)) {
            assertTrue(Thread.interrupted());
            assertTrue(executor.isTerminated());
        }
    }
}

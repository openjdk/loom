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

    /**
     * Deadline expires with running tasks before the executor is shutdown.
     */
    public void testDeadlineBeforeShutdown() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newUnboundedExecutor(factory).withDeadline(deadline, null)) {
            // assume this is submitted before the deadline expires
            Future<?> result = executor.submit(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });

            // task should be interrupted when deadline expires
            Throwable e = expectThrows(ExecutionException.class, result::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor should be shutdown and should almost immediately
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires with running tasks after the executor is shutdown.
     */
    public void testDeadlineAfterShutdown() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newUnboundedExecutor(factory).withDeadline(deadline, null)) {
            // assume this is submitted before the deadline expires
            Future<?> result = executor.submit(() -> {
                Thread.sleep(Duration.ofDays(1));
                return null;
            });
            executor.shutdown();

            // task should be interrupted when deadline expires
            Throwable e = expectThrows(ExecutionException.class, result::get);
            assertTrue(e.getCause() instanceof InterruptedException);

            // executor terminate immediately
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    /**
     * Deadline expires after executor has terminated.
     */
    public void testDeadlineAfterTerminate() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(60);
        Future<?> result;
        var executor = Executors.newUnboundedExecutor(factory).withDeadline(deadline, null);
        try (executor) {
            result = executor.submit(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return null;
            });
        }
        assertTrue(result.get() == null);
        assertTrue(executor.isShutdown() && executor.isTerminated());
    }

    /**
     * Deadline expires before owner closes the executor.
     */
    public void testInterruptBeforeDeadline() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(3);
        try (var executor = Executors.newUnboundedExecutor(factory)
                                     .withDeadline(deadline, Thread.currentThread())) {
            // sleep should be interrupted
            try {
                Thread.sleep(Duration.ofDays(1));
                assertTrue(false);
            } catch (InterruptedException expected) { }

            // executor should be shutdown and should terminate almost immediately
            assertTrue(executor.isShutdown());
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }
    }

    /**
     * Deadline expires while the owner is waiting in close.
     */
    public void testInterruptDuringClose() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(3);
        try {
            try (var executor = Executors.newUnboundedExecutor(factory)
                                         .withDeadline(deadline, Thread.currentThread())) {
                // assume this is submitted before the deadline expires
                executor.submit(() -> {
                    Thread.sleep(Duration.ofDays(1));
                    return null;
                });
            }

            // interrupt status should be set
            assertTrue(Thread.interrupted());

        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }

    }

    /**
     * Deadline expires after the executor has terminated.
     */
    public void testInterruptAfterTerminate() throws Exception {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        var deadline = Instant.now().plusSeconds(3);
        try {
            Executors.newUnboundedExecutor(factory)
                    .withDeadline(deadline, Thread.currentThread())
                    .close();

            // sleep should not be interrupted
            Thread.sleep(Duration.between(Instant.now(), deadline));

        } finally {
            Thread.interrupted();  // ensure interrupt status is cleared
        }
    }

    /**
     * Deadline has already expired
     */
    public void testDeadlineAlreadyExpired() {
        ThreadFactory factory = Thread.builder().daemon(true).factory();
        // now
        Instant now = Instant.now();
        try (var executor = Executors.newUnboundedExecutor(factory).withDeadline(now, null)) {
            assertTrue(executor.isTerminated());
        }
        try (var executor = Executors.newUnboundedExecutor(factory)
                                     .withDeadline(now, Thread.currentThread())) {
            assertTrue(Thread.interrupted());
            assertTrue(executor.isTerminated());
        }
        // in the past
        var yesterday = Instant.now().minus(Duration.ofDays(1));
        try (var executor = Executors.newUnboundedExecutor(factory).withDeadline(yesterday, null)) {
            assertTrue(executor.isTerminated());
        }
        try (var executor = Executors.newUnboundedExecutor(factory)
                                     .withDeadline(yesterday, Thread.currentThread())) {
            assertTrue(Thread.interrupted());
            assertTrue(executor.isTerminated());
        }
    }
}

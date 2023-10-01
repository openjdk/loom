/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test that a virtual thread's carrier is released when it blocks waiting to
 *    enter a monitor or parks while holding a monitor
 * @modules java.base/java.lang:+open
 * @run junit MonitorEnterReleasesCarrier
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class MonitorEnterReleasesCarrier {
    static final int VTHREAD_COUNT = Runtime.getRuntime().availableProcessors() * 4;

    /**
     * Test that parking while holding a monitor releases the carrier.
     */
    @Disabled
    @Test
    void testReleaseWhenParked() throws Exception {
        assumeTrue(ThreadBuilders.supportsCustomScheduler(), "No support for custom schedulers");
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread.Builder builder = ThreadBuilders.virtualThreadBuilder(scheduler);

            var lock = new Object();

            // thread enters monitor and parks
            var started = new CountDownLatch(1);
            var vthread1 = builder.start(() -> {
                started.countDown();
                synchronized (lock) {
                    LockSupport.park();
                }
            });

            try {
                // wait for thread to start and park
                started.await();
                await(vthread1, Thread.State.WAITING);

                // carrier should be released, use it for another thread
                var executed = new AtomicBoolean();
                var vthread2 = builder.start(() -> {
                    executed.set(true);
                });
                vthread2.join();
                assertTrue(executed.get());

            } finally {
                LockSupport.unpark(vthread1);
                vthread1.join();
            }
        }
    }

    /**
     * Test that blocked waiting to enter a monitor releases the carrier.
     */
    @Disabled
    @Test
    void testReleaseWhenBlocked() throws Exception {
        assumeTrue(ThreadBuilders.supportsCustomScheduler(), "No support for custom schedulers");
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread.Builder builder = ThreadBuilders.virtualThreadBuilder(scheduler);

            var lock = new Object();

            // thread enters monitor
            var started = new CountDownLatch(1);
            var vthread1 = builder.unstarted(() -> {
                started.countDown();
                synchronized (lock) {
                }
            });

            synchronized (lock) {
                try {
                    // start thread and wait for it to block
                    vthread1.start();
                    started.await();
                    await(vthread1, Thread.State.BLOCKED);

                    // carrier should be released, use it for another thread
                    var executed = new AtomicBoolean();
                    var vthread2 = builder.start(() -> {
                        executed.set(true);
                    });
                    vthread2.join();
                    assertTrue(executed.get());

                } finally {
                    LockSupport.unpark(vthread1);
                    vthread1.join();
                }
            }
        }
    }

    /**
     * Test lots of virtual threads parked while holding a monitor. If the number of
     * virtual threads exceeds the number of carrier threads then this test will hang if
     * carriers aren't released.
     */
    @Disabled
    @Test
    void testManyParkedThreads() throws Exception {
        Thread[] vthreads = new Thread[VTHREAD_COUNT];
        var done = new AtomicBoolean();
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            var lock = new Object();
            var started = new CountDownLatch(1);
            var vthread = Thread.ofVirtual().start(() -> {
                started.countDown();
                synchronized (lock) {
                    while (!done.get()) {
                        LockSupport.park();
                    }
                }
            });
            // wait for thread to start and park
            started.await();
            await(vthread, Thread.State.WAITING);
            vthreads[i] = vthread;
        }

        // cleanup
        done.set(true);
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            var vthread = vthreads[i];
            LockSupport.unpark(vthread);
            vthread.join();
        }
    }

    /**
     * Test lots of virtual threads blocked waiting to enter a monitor. If the number
     * of virtual threads exceeds the number of carrier threads this test will hang if
     * carriers aren't released.
     */
    @Disabled
    @Test
    void testManyBlockedThreads() throws Exception {
        Thread[] vthreads = new Thread[VTHREAD_COUNT];
        var lock = new Object();
        synchronized (lock) {
            for (int i = 0; i < VTHREAD_COUNT; i++) {
                var started = new CountDownLatch(1);
                var vthread = Thread.ofVirtual().start(() -> {
                    started.countDown();
                    synchronized (lock) {
                    }
                });
                // wait for thread to start and block
                started.await();
                await(vthread, Thread.State.BLOCKED);
                vthreads[i] = vthread;
            }
        }

        // cleanup
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    private void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.yield();
            state = thread.getState();
        }
    }
}

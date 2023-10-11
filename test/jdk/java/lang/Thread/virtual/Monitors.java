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

/*
 * @test
 * @summary Test virtual thread monitor enter/exit
 * @key randomness
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm --enable-native-access=ALL-UNNAMED Monitors
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.Platform;
import jdk.test.lib.thread.VThreadRunner;
import jdk.test.lib.thread.VThreadPinner;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class Monitors {
    static final int MAX_VTHREAD_COUNT = 4 * Runtime.getRuntime().availableProcessors();
    static final int MAX_ENTER_DEPTH = MAX_VTHREAD_COUNT;

    static ThreadFactory randomThreadFactory() {
        return ThreadLocalRandom.current().nextBoolean()
                ? Thread.ofPlatform().factory()
                : Thread.ofVirtual().factory();
    }

    /**
     * Test monitor enter with no contention.
     */
    @Test
    void testEnterNoContention() throws Exception {
        var lock = new Object();
        VThreadRunner.run(() -> {
            assertFalse(Thread.holdsLock(lock));
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
            }
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test monitor enter where monitor is held by platform thread.
     */
    @Test
    void testEnterWithContention() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            synchronized (lock) {
                entered.set(true);
            }
        });
        try {
            synchronized (lock) {
                vthread.start();

                // wait for thread to start and block
                started.await();
                await(vthread, Thread.State.BLOCKED);
            }
        } finally {
            vthread.join();
        }
        assertTrue(entered.get());
    }

    /**
     * Test monitor enter where monitor is held by virtual thread.
     */
    @Test
    void testEnterWithContention2() throws Exception {
        VThreadRunner.run(this::testEnterWithContention);
    }

    /**
     * Test monitor reenter.
     */
    @Test
    void testReenter() throws Exception {
        var lock = new Object();
        VThreadRunner.run(() -> {
            assertFalse(Thread.holdsLock(lock));
            testReenter(lock, 0);
            assertFalse(Thread.holdsLock(lock));
        });
    }

    private void testReenter(Object lock, int depth) {
        if (depth < MAX_ENTER_DEPTH) {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
                testReenter(lock, depth + 1);
                assertTrue(Thread.holdsLock(lock));
            }
        }
    }

    /**
     * Test monitor reenter when there are threads blocked trying to enter the monitor.
     */
    @Test
    @EnabledIf("platformIsX64")
    void testReenterWithContention() throws Exception {
        var lock = new Object();
        VThreadRunner.run(() -> {
            List<Thread> threads = new ArrayList<>();
            testReenter(lock, 0, threads);

            // wait for threads to terminate
            for (Thread vthread : threads) {
                vthread.join();
            }
        });
    }

    private void testReenter(Object lock, int depth, List<Thread> threads) throws Exception {
        if (depth < MAX_ENTER_DEPTH) {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                // start thread that blocks waiting to enter
                var started = new CountDownLatch(1);
                var thread = randomThreadFactory().newThread(() -> {
                    started.countDown();
                    synchronized (lock) {
                        /* do nothing */
                    }
                });
                thread.start();

                // wait for thread to start and block
                started.await();
                await(thread, Thread.State.BLOCKED);
                threads.add(thread);

                // test reenter
                testReenter(lock, depth + 1, threads);
            }
        }
    }

    /**
     * Test monitor enter when pinned.
     */
    @Test
    void testEnterWhenPinned() throws Exception {
        VThreadRunner.run(() -> {
            var lock = new Object();
            VThreadPinner.runPinned(() -> {
                synchronized (lock) {
                    assertTrue(Thread.holdsLock(lock));
                }
            });
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test monitor reenter when pinned.
     */
    @Test
    void testReenterWhenPinned() throws Exception {
        VThreadRunner.run(() -> {
            var lock = new Object();
            synchronized (lock) {
                VThreadPinner.runPinned(() -> {
                    assertTrue(Thread.holdsLock(lock));
                    synchronized (lock) {
                        assertTrue(Thread.holdsLock(lock));
                    }
                    assertTrue(Thread.holdsLock(lock));
                });
            }
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test contended monitor enter when pinned. Monitor is held by platform thread.
     */
    @Test
    void testContendedMonitorEnterWhenPinned() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        Thread vthread  = Thread.ofVirtual().unstarted(() -> {
            VThreadPinner.runPinned(() -> {
                started.countDown();
                synchronized (lock) {
                    entered.set(true);
                }
            });
        });
        synchronized (lock) {
            // start thread and wait for it to block
            vthread.start();
            started.await();
            await(vthread, Thread.State.BLOCKED);
        }
        vthread.join();

        // check thread entered monitor
        assertTrue(entered.get());
    }

    /**
     * Test contended monitor enter when pinned. Monitor is held by virtual thread.
     */
    @Test
    void testContendedMonitorEnterWhenPinned2() throws Exception {
        VThreadRunner.run(this::testContendedMonitorEnterWhenPinned);
    }

    /**
     * Test that parking while holding a monitor releases the carrier.
     */
    @Test
    @EnabledIf("platformIsX64")
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
    @Test
    @EnabledIf("platformIsX64")
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

            try {
                synchronized (lock) {
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
                }
            } finally {
                vthread1.join();
            }
        }
    }

    /**
     * Test lots of virtual threads parked while holding a monitor. If the number of
     * virtual threads exceeds the number of carrier threads then this test will hang if
     * carriers aren't released.
     */
    @Test
    @EnabledIf("platformIsX64")
    void testManyParkedThreads() throws Exception {
        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        var done = new AtomicBoolean();
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
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
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
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
    @Test
    @EnabledIf("platformIsX64")
    void testManyBlockedThreads() throws Exception {
        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        var lock = new Object();
        synchronized (lock) {
            for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
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
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    /**
     * Test that unblocking a virtual thread waiting to enter a monitor does not consume
     * the thread's parking permit.
     */
    @Disabled
    @Test
    void testParkingPermitNotConsumed() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            LockSupport.unpark(Thread.currentThread());
            synchronized (lock) { }  // should block
            LockSupport.park();      // should not park
        });

        synchronized (lock) {
            vthread.start();
            // wait for thread to start and block
            started.await();
            await(vthread, Thread.State.BLOCKED);
        }
        vthread.join();
    }

    /**
     * Test that unblocking a virtual thread waiting to enter a monitor does not make
     * available the thread's parking permit.
     */
    @Test
    void testParkingPermitNotOffered() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            synchronized (lock) { }  // should block
            LockSupport.park();      // should park
        });

        synchronized (lock) {
            vthread.start();
            // wait for thread to start and block
            started.await();
            await(vthread, Thread.State.BLOCKED);
        }

        try {
            // wait for thread to park, it should not terminate
            await(vthread, Thread.State.WAITING);
            vthread.join(Duration.ofMillis(100));
            assertEquals(Thread.State.WAITING, vthread.getState());
        } finally {
            LockSupport.unpark(vthread);
            vthread.join();
        }
    }

    /**
     * Wait for the given thread to reach the given state.
     */
    private void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.yield();
            state = thread.getState();
        }
    }

    private boolean platformIsX64() {
        return Platform.isX64();
    }
}

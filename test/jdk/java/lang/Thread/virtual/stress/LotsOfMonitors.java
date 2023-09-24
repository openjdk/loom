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
 * @summary Test virtual thread entering (and owning) a lot of monitors
 * @library /test/lib
 * @run junit LotsOfMonitors
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LotsOfMonitors {
    static final int MAX_MONITORS = 256;

    static ThreadFactory threadFactory() {
        return ThreadLocalRandom.current().nextBoolean()
                ? Thread.ofPlatform().factory()
                : Thread.ofVirtual().factory();
    }

    /**
     * Test entering lots of monitors with no contention.
     */
    @Test
    void testEnterNoContention() throws Exception {
        VThreadRunner.run(() -> {
            testEnter(List.of(), MAX_MONITORS);
        });
    }

    /**
     * Enter the monitor for a new object, then reenter a monitor that is already held.
     */
    private void testEnter(List<Object> locksHeld, int remaining) {
        if (remaining > 0) {
            var lock = new Object();
            assertFalse(Thread.holdsLock(lock));

            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                var locks = new ArrayList<>();
                locks.addAll(locksHeld);
                locks.add(lock);
                testEnter(locks, remaining - 1);
                assertTrue(Thread.holdsLock(lock));

                // reenter a lock that is already held
                int index = ThreadLocalRandom.current().nextInt(locks.size());
                var otherLock = locks.get(index);
                assertTrue(Thread.holdsLock(otherLock));
                synchronized (otherLock) {
                    assertTrue(Thread.holdsLock(otherLock));
                }
                assertTrue(Thread.holdsLock(otherLock));

                assertTrue(Thread.holdsLock(lock));
            }

            assertFalse(Thread.holdsLock(lock));
        }
    }

    /**
     * Test entering lots of monitors with contention.
     */
    @Disabled
    @Test
    void testEnterWithContention() throws Exception {
        VThreadRunner.run(() -> {
            var threads = new ArrayList<Thread>();
            testEnter(MAX_MONITORS, threads);
            assertEquals(MAX_MONITORS, threads.size());

            // wait for threads to terminate
            for (Thread vthread : threads) {
                vthread.join();
            }
        });
    }

    /**
     * Enter the monitor for a new object, racing with another thread that attempts to
     * enter around the same time.
     */
    private void testEnter(int remaining, List<Thread> threads) throws Exception {
        if (remaining > 0) {
            var lock = new Object();
            assertFalse(Thread.holdsLock(lock));

            // start thread to enter monitor for brief period, then enters again when signalled
            var started = new CountDownLatch(1);
            var signal = new CountDownLatch(1);
            var thread = threadFactory().newThread(() -> {
                started.countDown();
                // enter, may be contended
                synchronized (lock) {
                    Thread.onSpinWait();
                }
                // wait to be signalled
                try {
                    signal.await();
                } catch (InterruptedException e) {
                }
                // enter again
                synchronized (lock) {
                    // do nothing
                }
            });
            thread.start();
            started.await();

            // enter, may be contended
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                // signal thread to enter monitor again, it should block
                signal.countDown();
                await(thread, Thread.State.BLOCKED);

                threads.add(thread);

                testEnter(remaining -1, threads);
            }

            assertFalse(Thread.holdsLock(lock));
        }
    }

    private void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.onSpinWait();
            state = thread.getState();
        }
    }
}

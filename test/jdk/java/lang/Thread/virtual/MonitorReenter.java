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
 * @summary Test virtual thread reentering monitor
 * @key randomness
 * @library /test/lib
 * @run junit MonitorReenter
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorReenter {
    static final int MAX_ENTER_DEPTH = 256;

    static ThreadFactory threadFactory() {
        return ThreadLocalRandom.current().nextBoolean()
                ? Thread.ofPlatform().factory()
                : Thread.ofVirtual().factory();
    }

    /**
     * Test monitor reenter with no contention.
     */
    @Test
    void testReenterNoContention() throws Exception {
        var lock = new Object();
        VThreadRunner.run(() -> {
            assertFalse(Thread.holdsLock(lock));
            testReenter(lock, MAX_ENTER_DEPTH);
            assertFalse(Thread.holdsLock(lock));
        });
    }

    private void testReenter(Object lock, int depth) {
        if (depth > 0) {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
                testReenter(lock, depth - 1);
                assertTrue(Thread.holdsLock(lock));
            }
        }
    }

    /**
     * Test monitor reenter with contention. At each enter, a thread is started that
     * blocks waiting to enter the monitor.
     */
    @Disabled
    @Test
    void testReenterWithContention() throws Exception {
        var lock = new Object();
        VThreadRunner.run(() -> {
            List<Thread> threads = new ArrayList<>();
            testReenter(lock, MAX_ENTER_DEPTH, threads);
            assertEquals(MAX_ENTER_DEPTH, threads.size());

            // wait for threads to terminate
            for (Thread vthread : threads) {
                vthread.join();
            }
        });
    }

    private void testReenter(Object lock, int depth, List<Thread> threads) {
        if (depth > 0) {
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                // start thread that blocks waiting to enter
                var thread = threadFactory().newThread(() -> {
                    synchronized (lock) {
                        /* do nothing */
                    }
                });
                thread.start();
                // wait for thread to block
                await(thread, Thread.State.BLOCKED);
                threads.add(thread);

                testReenter(lock, depth - 1, threads);
            }
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

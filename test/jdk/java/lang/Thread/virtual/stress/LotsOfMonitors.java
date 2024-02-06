/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test virtual thread entering a lot of monitors, both with and without contention
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib
 * @run junit LotsOfMonitors
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

class LotsOfMonitors {

    /**
     * Test entering lots of monitors with no contention.
     */
    @Test
    void testEnterNoContention() throws Exception {
        VThreadRunner.run(() -> {
            testEnter(List.of(), 16);    // creates 65535 monitors
        });
    }

    /**
     * Enter the monitor for a new object, then reenter a monitor that is already held.
     */
    private void testEnter(List<Object> ownedMonitors, int remaining) {
        if (remaining > 0) {
            var lock = new Object();
            assertFalse(Thread.holdsLock(lock));

            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                // new list of owned monitors
                var monitors = concat(ownedMonitors, lock);
                testEnter(monitors, remaining - 1);

                // reenter a monitor that is already owned
                int index = ThreadLocalRandom.current().nextInt(monitors.size());
                var otherLock = monitors.get(index);
                assertTrue(Thread.holdsLock(otherLock));
                synchronized (otherLock) {
                    testEnter(monitors, remaining - 1);
                }
            }

            assertFalse(Thread.holdsLock(lock));
        }
    }

    /**
     * Test entering lots of monitors with contention.
     */
    @Test
    void testEnterWithContention() throws Exception {
        final int MAX_MONITORS = 1024;

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
            var thread = Thread.ofVirtual().start(() -> {
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
            threads.add(thread);

            // wait for thread to start, then enter monitor (may be contended)
            started.await();
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));

                // signal thread to enter monitor again, it should block
                signal.countDown();
                await(thread, Thread.State.BLOCKED);
                testEnter(remaining -1, threads);
            }

            assertFalse(Thread.holdsLock(lock));
        }
    }


    /**
     * Waits for the given thread to reach a given state.
     */
    private void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.yield();
            state = thread.getState();
        }
    }

    /**
     * Adds an element to a list, returning a new list.
     */
    private <T> List<T> concat(List<T> list, T object) {
        var newList = new ArrayList<>(list);
        newList.add(object);
        return newList;
    }
}

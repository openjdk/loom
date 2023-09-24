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
 * @run junit MonitorEnterReleasesCarrier
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorEnterReleasesCarrier {
    static final int VTHREAD_COUNT = Runtime.getRuntime().availableProcessors() * 4;

    /**
     * Test lots of virtual threads blocked waiting to enter a monitor. If the number
     * of virtual threads exceeds the number of carrier threads this test will hang if
     * carriers aren't released.
     */
    @Disabled
    @Test
    void testReleaseWhenBlocked() throws Exception {
        Thread[] vthreads = new Thread[VTHREAD_COUNT];
        var theLock = new Object();
        synchronized (theLock) {
            for (int i = 0; i < VTHREAD_COUNT; i++) {
                var started = new CountDownLatch(1);
                var vthread = Thread.ofVirtual().start(() -> {
                    started.countDown();
                    synchronized (theLock) {
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

    /**
     * Test lots of virtual threads parked while holding a monitor. If the number of
     * virtual threads exceeds the number of carrier threads then this test will hang if
     * carriers aren't released.
     */
    @Disabled
    @Test
    void testReleaseWhenParked() throws Exception {
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

    private void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.onSpinWait();
            state = thread.getState();
        }
    }
}

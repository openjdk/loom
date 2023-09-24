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
 * @summary Test monitor enter/reenter when pinned
 * @enablePreview
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit MonitorEnterWhenPinned
 */

import java.util.concurrent.atomic.AtomicBoolean;

import jdk.test.lib.thread.VThreadRunner;
import jdk.test.lib.thread.VThreadPinner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorEnterWhenPinned {

    /**
     * Test monitor enter from virtual thread when its carrier is pinned.
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
     * Test monitor reenter from virtual thread when its carrier is pinned.
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
                });
            }
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test contended monitor enter from virtual thread when its carrier is pinned.
     */
    @Test
    void testContendedMonitorEnterWhenPinned() throws Exception {
        var lock = new Object();
        var entered = new AtomicBoolean();
        Thread vthread  = Thread.ofVirtual().unstarted(() -> {
            VThreadPinner.runPinned(() -> {
                synchronized (lock) {
                    entered.set(true);
                }
            });
        });
        synchronized (lock) {
            vthread.start();
            await(vthread, Thread.State.BLOCKED);
            assertFalse(entered.get());
        }
        vthread.join();
        assertTrue(entered.get());
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

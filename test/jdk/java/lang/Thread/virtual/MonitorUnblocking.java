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
 * @summary Test unblocking a virtual thread waiting to enter a monitor
 * @key randomness
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit MonitorUnblocking
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;

class MonitorUnblocking {

    /**
     * Test unblocking a virtual thread waiting to enter a monitor held by a platform thread.
     */
    @RepeatedTest(50)
    void testUnblocking() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            synchronized (lock) {
                // do nothing
            }
        });
        try {
            synchronized (lock) {
                vthread.start();
                started.await();

                // random delay before exiting monitor
                switch (ThreadLocalRandom.current().nextInt(4)) {
                    case 0 -> { /* no delay */}
                    case 1 -> Thread.onSpinWait();
                    case 2 -> Thread.yield();
                    case 3 -> await(vthread, Thread.State.BLOCKED);
                    default -> fail();
                }
            }
        } finally {
            vthread.join();
        }
    }

    /**
     * Test unblocking a virtual thread waiting to enter a monitor held by another
     * virtual thread.
     */
    @RepeatedTest(50)
    void testUnblocking2() throws Exception {
        // need at least two carrier threads
        int previousParallelism = VThreadRunner.ensureParallelism(2);
        try {
            VThreadRunner.run(this::testUnblocking);
        } finally {
            VThreadRunner.setParallelism(previousParallelism);
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

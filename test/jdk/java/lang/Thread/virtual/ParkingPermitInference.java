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
 * @summary Test that monitor enter/exit does not interfere with the thread's parking permit.
 * @run junit ParkingPermitInference
 */

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class ParkingPermitInference {

    static Stream<ThreadFactory> factories() {
        return Stream.of(
                Thread.ofPlatform().factory(),
                Thread.ofVirtual().factory()
        );
    }

    /**
     * Test that unblocking a virtual thread waiting to enter a monitor does not interfere
     * with its parking permit.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void testParkPermitNotConsumed(ThreadFactory factory) throws Exception {
        var lock = new Object();
        var thread = factory.newThread(() -> {
            synchronized (lock) {
            }

            LockSupport.park();  // should park
        });
        synchronized (lock) {
            thread.start();
            await(thread, Thread.State.BLOCKED);
        }
        try {
            // wait for thread to park, it should not terminate
            await(thread, Thread.State.WAITING);
            thread.join(Duration.ofMillis(100));
            assertEquals(Thread.State.WAITING, thread.getState());
        } finally {
            LockSupport.unpark(thread);
            thread.join();
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

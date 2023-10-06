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
 * @summary Test synchronization by virtual thread on synchronized native method
 * @library /test/lib
 * @run junit/othervm/native -Xint MonitorSyncNativeMethod
 * @run junit/othervm/native -Xcomp MonitorSyncNativeMethod
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class MonitorSyncNativeMethod {
    static int VTHREAD_COUNT = Runtime.getRuntime().availableProcessors();
    static AtomicInteger counter = new AtomicInteger(0);

    private static synchronized native void nativeMethod();

    @Test
    void testSyncOnNativeMethod() throws Exception {
        System.loadLibrary("MonitorSyncNativeMethod");
        if (Thread.currentThread().isVirtual()) {
            // Make sure we always have a carrier for this vthread
            // since we yield in the await() call.
            VTHREAD_COUNT -= 1;
        }
        Thread[] vthreads = new Thread[VTHREAD_COUNT];
        startVThreads(vthreads);

        // Wait for all vthreads to finish
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
        assertTrue(counter.get() == VTHREAD_COUNT, counter.get() + "!=" + VTHREAD_COUNT);
    }

    private static synchronized void startVThreads(Thread[] vthreads) throws Exception {
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            var started = new CountDownLatch(1);
            var vthread = Thread.ofVirtual().start(() -> {
                started.countDown();
                nativeMethod();
                counter.getAndIncrement();
            });
            // wait for thread to start
            started.await();
            //await(vthread, Thread.State.BLOCKED);
            vthreads[i] = vthread;
        }
    }

    private static void await(Thread thread, Thread.State expectedState) {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.yield();
            state = thread.getState();
        }
    }
}

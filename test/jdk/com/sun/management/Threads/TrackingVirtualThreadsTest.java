
/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng/othervm -Djdk.trackAllVirtualThreads=true TrackingVirtualThreadsTest
 */
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import com.sun.management.Threads;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class TrackingVirtualThreadsTest {

    public void testOneThread() throws Exception {
        assertTrue(Threads.virtualThreads().count() == 0L);
        Thread thread = Thread.ofVirtual().start(LockSupport::park);
        try {
            assertTrue(Threads.virtualThreads().anyMatch(t -> t == thread));
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
        assertTrue(Threads.virtualThreads().count() == 0L);
    }

    public void testManyThreads() throws Exception {
        assertTrue(Threads.virtualThreads().count() == 0L);

        int nthreads = 5;
        try (var executor = Executors.newVirtualThreadExecutor()) {
            var latch = new CountDownLatch(1);
            for (int i = 0; i < nthreads; i++) {
                executor.submit(() -> {
                    latch.await();
                    return null;
                });
            }
            try {
                long count;
                while ((count = Threads.virtualThreads().count()) != nthreads) {
                    System.out.println(count);
                    Thread.sleep(Duration.ofMillis(100));
                }
            } finally {
                latch.countDown();
            }
        }
        while (Threads.virtualThreads().count() != 0L) {
            Thread.sleep(100);
        }
    }
}

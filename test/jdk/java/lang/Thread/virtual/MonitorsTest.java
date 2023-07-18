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
 * @summary Test virtual threads using synchronized
 * @library /test/lib
 * @modules java.base/java.lang:+open
 * @run junit/othervm/timeout=10 -Xint MonitorsTest
 * @run junit/othervm/timeout=30 -Xcomp MonitorsTest
 * @run junit/othervm/timeout=30 MonitorsTest
 */

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorsTest {
    final int VT_COUNT = 8;
    static final Object globalLock = new Object();
    static volatile boolean finish = false;
    static volatile int counter = 0;

    ExecutorService scheduler = Executors.newFixedThreadPool(VT_COUNT);


    static final Runnable FOO = () -> {
        Object lock = new Object();
        synchronized(lock) {
            while(!finish) {
                Thread.yield();
            }
        }
        System.out.println("Exiting FOO from thread " + Thread.currentThread().getName());
    };

    static final Runnable BAR = () -> {
        synchronized(globalLock) {
            counter++;
        }
        System.out.println("Exiting BAR from thread " + Thread.currentThread().getName());
    };

    /**
     *  Yield while holding monitor.
     */
    @Test
    void testBasic() throws Exception {
        // Create first batch of VT threads.
        Thread firstBatch[] = new Thread[VT_COUNT];
        for (int i = 0; i < VT_COUNT; i++) {
            //Thread.ofVirtual().name("FirstBatchVT-" + i).start(FOO);
            firstBatch[i] = ThreadBuilders.virtualThreadBuilder(scheduler).name("FirstBatchVT-" + i).start(FOO);
        }

        // Give time for all threads to reach Thread.yield
        Thread.sleep(1000);

        // Create second batch of VT threads.
        Thread secondBatch[] = new Thread[VT_COUNT];
        for (int i = 0; i < VT_COUNT; i++) {
            //vthreads[i] = Thread.ofVirtual().name("SecondBatchVT-" + i).start(BAR);
            secondBatch[i] = ThreadBuilders.virtualThreadBuilder(scheduler).name("SecondBatchVT-" + i).start(BAR);
        }

        while(counter != VT_COUNT) {}

        finish = true;

        for (int i = 0; i < VT_COUNT; i++) {
            firstBatch[i].join();
        }
        for (int i = 0; i < VT_COUNT; i++) {
            secondBatch[i].join();
        }
    }


    static final Runnable FOO2 = () -> {
        Object lock = new Object();
        synchronized(lock) {
            while(!finish) {
                Thread.yield();
            }
        }
        System.out.println("Exiting FOO2 from thread " + Thread.currentThread().getName());
    };

    static final Runnable BAR2 = () -> {
        synchronized(globalLock) {
            counter++;
        }
        recursive(10);
        System.out.println("Exiting BAR2 from thread " + Thread.currentThread().getName() + "with counter=" + counter);
    };

    static void recursive(int count) {
        synchronized(Thread.currentThread()) {
            if (count > 0) {
                recursive(count - 1);
            } else {
                synchronized(globalLock) {
                    counter++;
                    Thread.yield();
                }
            }
        }
    }

    /**
     *  Test recursive locking.
     */
    @Test
    void testRecursive() throws Exception {
        counter = 0;
        finish = false;

        ExecutorService scheduler = Executors.newFixedThreadPool(VT_COUNT);

        // Create first batch of VT threads.
        Thread firstBatch[] = new Thread[VT_COUNT];
        for (int i = 0; i < VT_COUNT; i++) {
            //Thread.ofVirtual().name("FirstBatchVT-" + i).start(FOO);
            firstBatch[i] = ThreadBuilders.virtualThreadBuilder(scheduler).name("FirstBatchVT-" + i).start(FOO2);
        }

        // Give time for all threads to reach Thread.yield
        Thread.sleep(1000);

        // Create second batch of VT threads.
        Thread secondBatch[] = new Thread[VT_COUNT];
        for (int i = 0; i < VT_COUNT; i++) {
            //vthreads[i] = Thread.ofVirtual().name("SecondBatchVT-" + i).start(BAR);
            secondBatch[i] = ThreadBuilders.virtualThreadBuilder(scheduler).name("SecondBatchVT-" + i).start(BAR2);
        }

        while (counter != 2 * VT_COUNT) {}

        finish = true;

        for (int i = 0; i < VT_COUNT; i++) {
            firstBatch[i].join();
        }
        for (int i = 0; i < VT_COUNT; i++) {
            secondBatch[i].join();
        }
    }
}

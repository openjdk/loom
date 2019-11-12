/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng Parking
 * @summary Test lightweight threads using park/unpark
 */

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Parking {

    // lightweight thread parks, unparked by dinosaur thread
    public void testPark1() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> LockSupport.park());
        thread.start();
        Thread.sleep(1000); // give time for lightweight thread to park
        LockSupport.unpark(thread);
        thread.join();
    }

    // lightweight thread parks, unparked by another lightweight thread
    public void testPark2() throws Exception {
        var thread1 = Thread.newThread(Thread.LIGHTWEIGHT, () -> LockSupport.park());
        thread1.start();
        Thread.sleep(1000); // give time for lightweight thread to park
        var thread2 = Thread.newThread(Thread.LIGHTWEIGHT, () -> LockSupport.unpark(thread1));
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // park while holding monitor
    public void testPark3() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
        });
        thread.start();
        Thread.sleep(1000); // give time for lightweight thread to park
        LockSupport.unpark(thread);
        thread.join();
    }

    /*

    // park with native frame on the stack
    public void testPark4() throws Exception {
        var lightweight thread = lightweight threadScope.background().schedule(() -> {
            try {
                Method m = Basic.class.getDeclaredMethod("doPark");
                m.invoke(null);
            } catch (Exception e) {
                assertTrue(false);
            }
        });
        Thread.sleep(1000); // give time for lightweight thread to park
        LockSupport.unpark(lightweight thread);
        lightweight thread.join();
    }
    static void doPark() {
        LockSupport.park();
    }

    */

    // unpark before park
    public void testPark5() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            LockSupport.unpark(Thread.currentThread());
            LockSupport.park();
        });
        thread.start();
        thread.join();
    }

    // 2 x unpark before park
    public void testPark6() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            Thread me = Thread.currentThread();
            LockSupport.unpark(me);
            LockSupport.unpark(me);
            LockSupport.park();
            LockSupport.park();  // should park
        });
        thread.start();
        Thread.sleep(1000); // give time for lightweight thread to park
        LockSupport.unpark(thread);
        thread.join();
    }

    // 2 x park
    public void testPark7() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            LockSupport.park();
            LockSupport.park();
        });
        thread.start();

        Thread.sleep(1000); // give time for lightweight thread to park

        // unpark, lightweight thread should park again
        LockSupport.unpark(thread);
        Thread.sleep(1000);
        assertTrue(thread.isAlive());

        // let it terminate
        LockSupport.unpark(thread);
        thread.join();
    }

    // interrupt before park
    public void testPark8() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            LockSupport.park();
            assertTrue(t.isInterrupted());
        });
    }

    // interrupt while parked
    public void testPark9() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            Thread t = Thread.currentThread();
            TestHelper.scheduleInterrupt(t, 1000);
            LockSupport.park();
            assertTrue(t.isInterrupted());
        });
    }

    // interrupt before park (pinned park)
    public void testPark10() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
        });
    }

    // interrupt while parked (pinned park)
    public void testPark11() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            Thread t = Thread.currentThread();
            TestHelper.scheduleInterrupt(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
            assertTrue(t.isInterrupted());
        });
    }

    // parkNanos(-1) completes immediately
    public void testParkNanos1() throws Exception {
        TestHelper.runInLightweightThread(() -> LockSupport.parkNanos(-1));
    }

    // parkNanos(0) completes immediately
    public void testParkNanos2() throws Exception {
        TestHelper.runInLightweightThread(() -> LockSupport.parkNanos(0));
    }

    // parkNanos(1000ms) completes quickly
    public void testParkNanos3() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            // park for 1000ms
            long nanos = TimeUnit.NANOSECONDS.convert(1000, TimeUnit.MILLISECONDS);
            long start = System.nanoTime();
            LockSupport.parkNanos(nanos);

            // check that lightweight thread parks for >= 900ms
            long elapsed = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start,
                    TimeUnit.NANOSECONDS);
            assertTrue(elapsed >= 900);
        });
    }

    // lightweight thread parks, unparked by dinosaur thread
    public void testParkNanos4() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        thread.start();
        Thread.sleep(1000); // give time for lightweight thread to park
        LockSupport.unpark(thread);
        thread.join();
    }

    // lightweight thread parks, unparked by another lightweight thread
    public void testParkNanos5() throws Exception {
        var thread1 = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
        thread1.start();
        Thread.sleep(1000);  // give time for lightweight thread to park
        var thread2 = Thread.newThread(Thread.LIGHTWEIGHT, () -> LockSupport.unpark(thread1));
        thread2.start();
        thread1.join();
        thread2.join();
    }

    // unpark before parkNanos
    public void testParkNanos6() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            LockSupport.unpark(Thread.currentThread());
            long nanos = TimeUnit.NANOSECONDS.convert(30, TimeUnit.SECONDS);
            LockSupport.parkNanos(nanos);
        });
    }

    // unpark before parkNanos(0), should consume permit
    public void testParkNanos7() throws Exception {
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            LockSupport.unpark(Thread.currentThread());
            LockSupport.parkNanos(0);
            LockSupport.park(); // should block
        });
        thread.start();
        boolean isAlive = thread.join(Duration.ofSeconds(2));
        assertTrue(isAlive);
        LockSupport.unpark(thread);
        thread.join();
    }
}
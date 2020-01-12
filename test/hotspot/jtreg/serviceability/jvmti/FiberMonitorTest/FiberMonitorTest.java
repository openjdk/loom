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
 * @summary Test JVMTI Monitor functions for fibers
 * @compile FiberMonitorTest.java
 * @run main/othervm/native -agentlib:FiberMonitorTest FiberMonitorTest
 */

import java.io.PrintStream;

public class FiberMonitorTest {

    static {
        try {
            System.loadLibrary("FiberMonitorTest");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load FiberMonitorTest library");
            System.err.println("java.library.path: "
                               + System.getProperty("java.library.path"));
            throw ule;
        }
    }
    private static native boolean hasEventPosted();
    private static native void checkContendedMonitor(Thread thread, Object monitor);
    private static native int check();

    private static void log(String str) { System.out.println(str); }
    private static String thrName() { return Thread.currentThread().getName(); }

    private static final FiberMonitorTest lock0 = new FiberMonitorTest();
    private static final Object lock1 = new Object();
    private static final Object lock2 = new Object();

    static void m0() {
        synchronized (lock0) {
            log("Thread entered sync section with lock0: " + thrName());
        }
    }
    static void m1() {
        synchronized (lock1) {
            log("Thread entered sync section with lock1: " + thrName());
            m0();
        }
    }
    static void m2() {
        synchronized (lock2) {
            log("Thread entered sync section with lock2: " + thrName());
            m1();
        }
    }

    static final Runnable VT1 = () -> {
        m2();
    };

    public static void main(String[] args) throws Exception {
        Thread vt1 = Thread.newThread("VirtualThread-1", Thread.VIRTUAL, VT1);

        // Make sure VT1 is blocked on monitor lock0
        synchronized (lock0) {
            log("Main starting VT2 virtual thread.");
            vt1.start();

            // Wait for the MonitorContendedEnter event
            while (!hasEventPosted()) {
                log("Main thread is waiting for event.");
                Thread.sleep(100);
            }
            checkContendedMonitor(vt1, lock0);
        }

        vt1.join();

        if (check() != 0) {
            throw new RuntimeException("FAILED status returned from the agent");
        }
    }
}

/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

/*
 * @test HandshakeDirectTest
 * @bug 8240918
 * @summary This test tries to stress direct handshakes between threads while suspending them.
 * @library /testlibrary /test/lib
 * @build HandshakeDirectTest
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI HandshakeDirectTest
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:GuaranteedSafepointInterval=10 -XX:+HandshakeALot -XX:+SafepointALot HandshakeDirectTest
 */

import jvmti.JVMTIUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Semaphore;
import jdk.test.whitebox.WhiteBox;
import java.io.*;

public class HandshakeDirectTest  implements Runnable {
    static final int WORKING_THREADS = 32;
    static final int DIRECT_HANDSHAKES_MARK = 300000;
    static Thread[] workingThreads = new Thread[WORKING_THREADS];
    static Object[] locks = new Object[WORKING_THREADS];
    static AtomicInteger handshakeCount = new AtomicInteger(0);

    static void suspendThread(Thread t) {
        try {
            JVMTIUtils.suspendThread(t);
        } catch (JVMTIUtils.JvmtiException e) {
            if (e.getCode() != JVMTIUtils.JVMTI_ERROR_THREAD_NOT_ALIVE
                && e.getCode() != JVMTIUtils.JVMTI_ERROR_WRONG_PHASE) {
                throw e;
            }
        }
    }

    static void resumeThread(Thread t) {
        try {
            JVMTIUtils.resumeThread(t);
        } catch (JVMTIUtils.JvmtiException e) {
            if (e.getCode() != JVMTIUtils.JVMTI_ERROR_THREAD_NOT_ALIVE
                && e.getCode() != JVMTIUtils.JVMTI_ERROR_WRONG_PHASE) {
                throw e;
            }
        }
    }

    @Override
    public void run() {
        int me = Integer.parseInt(Thread.currentThread().getName());
        WhiteBox wb = WhiteBox.getWhiteBox();

        while (handshakeCount.get() < DIRECT_HANDSHAKES_MARK) {
            boolean walked = false;
            synchronized(locks[me]) {
                // Handshake directly some other worker
                int handshakee = ThreadLocalRandom.current().nextInt(0, WORKING_THREADS - 1);
                if (handshakee == me) {
                    // Pick another thread instead of me.
                    handshakee = handshakee != 0 ? handshakee - 1 : handshakee + 1;
                }
                // Inflate locks[handshakee] if possible
                System.identityHashCode(locks[handshakee]);
                walked = wb.handshakeReadMonitors(workingThreads[handshakee]);
                if (walked) {
                    handshakeCount.incrementAndGet();
                }
            }
            locks[me] = new Object();
        }
    }

    public static void main(String... args) throws Exception {
        HandshakeDirectTest test = new HandshakeDirectTest();

        // Initialize locks
        for (int i = 0; i < WORKING_THREADS; i++) {
            locks[i] = new Object();
        }

        // Fire-up working threads.
        for (int i = 0; i < WORKING_THREADS; i++) {
            workingThreads[i] = new Thread(test, Integer.toString(i));
            workingThreads[i].start();
        }

        // Fire-up suspend-resume thread
        Thread suspendResumeThread = new Thread() {
            @Override
            public void run() {
                while (true) {
                    int i = ThreadLocalRandom.current().nextInt(0, WORKING_THREADS - 1);
                    suspendThread(workingThreads[i]);
                    try {
                        Thread.sleep(1); // sleep for 1 ms
                    } catch(InterruptedException ie) {
                    }
                    resumeThread(workingThreads[i]);
                }
            }
        };
        suspendResumeThread.setDaemon(true);
        suspendResumeThread.start();

        // Wait until the desired number of direct handshakes is reached
        // and check that all workers exited
        for (int i = 0; i < WORKING_THREADS; i++) {
            workingThreads[i].join();
        }
    }
}

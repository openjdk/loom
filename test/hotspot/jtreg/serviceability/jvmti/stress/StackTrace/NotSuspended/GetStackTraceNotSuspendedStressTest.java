/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Verifies JVMTI GetStackTrace functions called without suspend.
 * @requires vm.continuations
 * @library /test/lib
 * @compile --enable-preview -source ${jdk.version} GetStackTraceNotSuspendedStressTest.java
 * @run main/othervm/native --enable-preview -agentlib:GetStackTraceNotSuspendedStress GetStackTraceNotSuspendedStressTest
 */

import jdk.test.lib.jvmti.DebugeeClass;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class GetStackTraceNotSuspendedStressTest extends DebugeeClass {
    private static final String agentLib = "GetStackTraceNotSuspendedStress";

    static final int MSG_COUNT = 1000;
    static final SynchronousQueue<String> QUEUE = new SynchronousQueue<>();

    static void producer(String msg) throws InterruptedException {
        int ii = 1;
        long ll = 2*(long)ii;
        float ff = ll + 1.2f;
        double dd = ff + 1.3D;
        msg += dd;
        QUEUE.put(msg);
    }

    static final Runnable PRODUCER = () -> {
        try {
            for (int i = 0; i < MSG_COUNT; i++) {
                producer("msg: ");
            }
        } catch (InterruptedException e) { }
    };

    static final Runnable CONSUMER = () -> {
        try {
            for (int i = 0; i < MSG_COUNT; i++) {
                String s = QUEUE.take();
            }
        } catch (InterruptedException e) { }
    };

    public static void test1() throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            threads.add(Thread.ofVirtual().name("VThread-Producer-" + i).unstarted(PRODUCER));
            threads.add(Thread.ofVirtual().name("VThread-Consumer-" + i).unstarted(CONSUMER));
        }

        for (Thread t: threads) {
            t.start();
        }

        for (Thread t: threads) {
            t.join();
        }
    }

    void runTest() throws Exception {
        // sync point to start agent thread
        checkStatus(0);
        test1();
    }

    public static void main(String[] args) throws Exception {
        try {
            System.loadLibrary(agentLib);
        } catch (UnsatisfiedLinkError ex) {
            System.err.println("Failed to load " + agentLib + " lib");
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw ex;
        }

        GetStackTraceNotSuspendedStressTest obj = new GetStackTraceNotSuspendedStressTest();
        obj.runTest();
    }
}

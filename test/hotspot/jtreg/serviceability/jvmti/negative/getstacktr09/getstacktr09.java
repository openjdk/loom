/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jvmti/GetStackTrace/getstacktr009.
 * VM Testbase keywords: [quick, jpda, jvmti, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercises JVMTI function GetStackTrace.
 *     The test checks if the function returns JVMTI_ERROR_THREAD_NOT_ALIVE
 *     for not yet started and already finished thread.
 * COMMENTS
 *     Test adjusted for JVMTI spec 0.2.90:
 *     - chack thread against specific error code JVMTI_ERROR_THREAD_NOT_ALIVE
 *       instead of general JVMTI_ERROR_INVALID_THREAD
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:getstacktr09 getstacktr09
 */


import java.io.PrintStream;

public class getstacktr09 {

    final static int JCK_STATUS_BASE = 95;
    final static int FAILED = 2;

    static {
        try {
            System.loadLibrary("getstacktr09");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load getstacktr09 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native static int check(Thread thread1, Thread thread2);

    public static void main(String args[]) {


        // produce JCK-like exit status.
        System.exit(run(args, System.out) + JCK_STATUS_BASE);
    }

    public static int run(String args[], PrintStream out) {
        TestThread tested_thread_thr1 = new TestThread();
        TestThread thr2 = new TestThread();

        thr2.start();
        try {
            thr2.join();
        } catch (InterruptedException ex) {
            out.println("# Unexpected " + ex);
            return FAILED;
        }

        return check(tested_thread_thr1, thr2);
    }

    static class TestThread extends Thread {
        public void run() {
        }
    }
}

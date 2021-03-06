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
 * @summary converted from VM Testbase nsk/jvmti/GetStackTrace/getstacktr005.
 * VM Testbase keywords: [quick, jpda, jvmti, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercises JVMTI function GetStackTrace via breakpoint
 *     and consequent step.
 *     The test starts a new thread, does some nested calls, stops at breakpoint,
 *     does single step and checks if the number of frames in the thread's
 *     stack is as expected and the function returns all the expected frames.
 * COMMENTS
 *     Ported from JVMDI.
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:getstacktr05 getstacktr05
 */

import java.io.PrintStream;

public class getstacktr05 {

    final static int FAILED = 2;
    final static int JCK_STATUS_BASE = 95;

    static {
        try {
            System.loadLibrary("getstacktr05");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load getstacktr05 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native static void getReady(Class clazz);
    native static int getRes();

    public static void main(String args[]) {


        // produce JCK-like exit status.
        System.exit(run(args, System.out) + JCK_STATUS_BASE);
    }

    public static int run(String args[], PrintStream out) {
        TestThread thr = new TestThread();
        getReady(TestThread.class);

        thr.start();
        try {
            thr.join();
        } catch (InterruptedException ex) {
            out.println("# Unexpected " + ex);
            return FAILED;
        }

        return getRes();
    }

    static class TestThread extends Thread {
        public void run() {
            chain1();
        }

        void chain1() {
            chain2();
        }

        void chain2() {
            chain3();
        }

        void chain3() {
            chain4();
        }

        void chain4() {
            checkPoint();
        }

        // dummy method to be breakpointed in agent
        void checkPoint() {
        }
    }
}

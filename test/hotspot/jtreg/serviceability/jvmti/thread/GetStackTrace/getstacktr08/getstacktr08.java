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
 * @summary converted from VM Testbase nsk/jvmti/GetStackTrace/getstacktr008.
 * VM Testbase keywords: [quick, jpda, jvmti, noras, redefine]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercises JVMTI function GetStackTrace.
 *     The test starts a new thread, does some nested calls with a native
 *     call in the middle, and stops at breakpoint.
 *     Then the test does the following:
 *         - checks the stack on expected frames
 *         - steps
 *         - checks the stack on expected frames
 *         - pops frame
 *         - checks the stack on expected frames
 *         - redefines class
 *         - checks the stack on expected frames
 *         - checks the stack on expected frames just before
 *           returning from the native call.
 * COMMENTS
 *     Ported from JVMDI.
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:getstacktr08 getstacktr08
 */

import java.io.*;

public class getstacktr08 {

    final static int FAILED = 2;
    final static int JCK_STATUS_BASE = 95;
    final static String fileName =
        TestThread.class.getName().replace('.', File.separatorChar) + ".class";

    static {
        try {
            System.loadLibrary("getstacktr08");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load getstacktr08 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native static void getReady(Thread thr, byte bytes[]);
    native static void nativeChain();
    native static int getRes();

    public static void main(String args[]) {


        // produce JCK-like exit status.
        System.exit(run(args, System.out) + JCK_STATUS_BASE);
    }

    public static int run(String args[], PrintStream out) {
        ClassLoader cl = getstacktr08.class.getClassLoader();
        TestThread thr = new TestThread();

        // Read data from class
        byte[] bytes;
        try {
            InputStream in = cl.getSystemResourceAsStream(fileName);
            if (in == null) {
                out.println("# Class file \"" + fileName + "\" not found");
                return FAILED;
            }
            bytes = new byte[in.available()];
            in.read(bytes);
            in.close();
        } catch (Exception ex) {
            out.println("# Unexpected exception while reading class file:");
            out.println("# " + ex);
            return FAILED;
        }

        getReady(thr, bytes);

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

        static void chain1() {
            chain2();
        }

        static void chain2() {
            chain3();
        }

        static void chain3() {
            nativeChain();
        }

        static void chain4() {
            chain5();
        }

        static void chain5() {
            checkPoint();
        }

        // dummy method to be breakpointed in agent
        static void checkPoint() {
        }
    }
}

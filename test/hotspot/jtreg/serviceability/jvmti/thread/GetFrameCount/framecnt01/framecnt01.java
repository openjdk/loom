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
 * @summary converted from VM Testbase nsk/jvmti/GetFrameCount/framecnt001.
 * VM Testbase keywords: [quick, jpda, jvmti, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercise JVMTI function GetFrameCount.
 *     The function is called by a native method for the current thread
 *     and for two more threads. The test suspends these two threads
 *     before GetFrameCount invocation.
 * COMMENTS
 *     Ported from JVMDI.
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:framecnt01 framecnt01
 */

import java.io.PrintStream;

public class framecnt01 {

    final static int JCK_STATUS_BASE = 95;

    native static void checkFrames(Thread thr, int thr_num, int ans);
    native static int getRes();

    static {
        try {
            System.loadLibrary("framecnt01");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load framecnt01 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    static Object flag1 = new Object();
    static Object flag2 = new Object();
    static Object check_flag = new Object();

    public static void main(String args[]) {


        // produce JCK-like exit status.
        System.exit(run(args, System.out) + JCK_STATUS_BASE);
    }

    public static int run(String argv[], PrintStream ref) {
        Thread currThread = Thread.currentThread();
        framecnt01a thr1 = new framecnt01a("thr1", 0, flag1);
        framecnt01a thr2 = new framecnt01a("thr2", 500, flag2);
        synchronized(check_flag) {
            synchronized(flag1) {
                synchronized(flag2) {
                    thr1.start();
                    thr2.start();
                    checkFrames(currThread, 0, 9);
                    // Stack should looks like this in jtreg
                    // TODO fix test to check new thread instead of main
//                      0: framecnt01: checkFrames(Ljava/lang/Thread;II)V
//                      1: framecnt01: run([Ljava/lang/String;Ljava/io/PrintStream;)I
//                      2: framecnt01: main([Ljava/lang/String;)V
//                      3: jdk/internal/reflect/NativeMethodAccessorImpl: invoke0(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
//                      4: jdk/internal/reflect/NativeMethodAccessorImpl: invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
//                      5: jdk/internal/reflect/DelegatingMethodAccessorImpl: invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
//                      6: java/lang/reflect/Method: invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
//                      7: com/sun/javatest/regtest/agent/MainWrapper$MainThread: run()V
//                      8: java/lang/Thread: run()V
                    try {
                        flag1.wait();
                        flag2.wait();
                    } catch(InterruptedException e) {}
                }
            }
            checkFrames(thr1, 1, 1);
            checkFrames(thr2, 2, 501);
        }
        return getRes();
    }
}

class framecnt01a extends Thread {
    int steps;
    Object flag;

    framecnt01a(String name, int steps, Object flag) {
        super(name);
        this.steps = steps;
        this.flag = flag;
    }

    public void run() {
        if (steps > 0) {
            steps--;
            run();
        }
        synchronized(flag) {
            flag.notify();  // let main thread know that all frames are in place
        }
        synchronized(framecnt01.check_flag) {  // wait for the check done
        }
    }
}

/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

package nsk.jvmti.SuspendThread;

import java.io.PrintStream;
import nsk.share.*;
import nsk.share.jvmti.*;

public class suspendvthr001 extends DebugeeClass {

    // load native library if required
    static {
        System.loadLibrary("suspendvthr001");
    }

    static public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new Failure("Interruption in suspendvthr001Thread.sleep: \n\t" + e);
        }
    }

    // run test from command line
    public static void main(String argv[]) {
        argv = nsk.share.jvmti.JVMTITest.commonInit(argv);

        // JCK-compatible exit
        System.exit(run(argv, System.out) + Consts.JCK_STATUS_BASE);
    }

    // run test from JCK-compatible environment
    public static int run(String argv[], PrintStream out) {
        return new suspendvthr001().runIt(argv, out);
    }

    private static final int VTHREADS_CNT = 30;

    // scaffold objects
    ArgumentHandler argHandler = null;
    Log log = null;
    long timeout = 0;
    int status = Consts.TEST_PASSED;

    // run debuggee
    public int runIt(String argv[], PrintStream out) {
        argHandler = new ArgumentHandler(argv);
        log = new Log(out, argHandler);
        timeout = argHandler.getWaitTime() * 60 * 1000; // milliseconds

        System.out.println("\n## Java: runIt: Starting threads");
        status = test_vthreads();
        if (status != Consts.TEST_PASSED) {
            return status;
        }
        return status;
    }

    private int test_vthreads() {
        suspendvthr001Thread[] threads = new suspendvthr001Thread[VTHREADS_CNT];
        Thread vts[] = new Thread[VTHREADS_CNT];

        for (int i = 0; i < VTHREADS_CNT; i++) {
            String name = "TestedThread" + i;
            suspendvthr001Thread thread = new suspendvthr001Thread(name);
            threads[i] = thread;
            vts[i] = start_thread(name, thread);
        }
        status = checkStatus(status);
        if (status != Consts.TEST_PASSED) {
            return status;
        }
        sleep(10); // let tested vthreads work while they are tested in native agent

        System.out.println("\n## Java: runIt: Finishing vthreads");
        try {
            for (int i = 0; i < VTHREADS_CNT; i++) {
                // let thread to finish
                suspendvthr001Thread thread = threads[i];
                thread.letFinish();
                vts[i].join();
            }
        } catch (InterruptedException e) {
            throw new Failure(e);
        }
        System.out.println("\n## Java: runIt: Checking status");
        return status;
    }

    Thread start_thread(String name, suspendvthr001Thread thread) {
        System.out.println("## Java: start_thread: Starting thread: " + name);
        Thread vt = Thread.newThread(name, Thread.VIRTUAL, thread); // create tested vthread
        vt.start(); // run tested vthread
        if (!thread.checkReady()) {
            throw new Failure("## Java: start_thread: Unable to prepare tested vthread: " + name);
        }
        // testing sync
        log.display("Sync: thread started: " + name);
        return vt;
    }
}

// class for tested threads
class suspendvthr001Thread extends Thread {
    private volatile boolean threadReady = false;
    private volatile boolean shouldFinish = false;

    // make thread with specific name
    public suspendvthr001Thread(String name) {
        super(name);
    }

    // run thread continuously
    public void run() {
        // run in a loop
        threadReady = true;
        int i = 0;
        int n = 1000;
        while (!shouldFinish) {
            if (n <= 0) {
                n = 1000;
                suspendvthr001.sleep(10);
            }
            if (i > n) {
                i = 0;
                n = n - 1;
            }
            i = i + 1;
        }
    }

    // check if thread is ready
    public boolean checkReady() {
        try {
            while (!threadReady) {
                sleep(1000);
            }
        } catch (InterruptedException e) {
            throw new Failure("Interruption while preparing tested thread: \n\t" + e);
        }
        return threadReady;
    }

    // let thread to finish
    public void letFinish() {
        shouldFinish = true;
    }
}

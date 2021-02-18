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
 * @summary Test set/get thread local storage in different locations like:
 *  -- cbThreadStart
 *  -- by AgentThread
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:SetGetThreadLocalStorageStress SetGetThreadLocalStorageStressTest
 */


import jdk.test.lib.jvmti.DebugeeClass;


public class SetGetThreadLocalStorageStressTest extends DebugeeClass {

    static {
        System.loadLibrary("SetGetThreadLocalStorageStress");
    }


    static int status = DebugeeClass.TEST_PASSED;


    // run test from command line
    public static void main(String argv[]) throws InterruptedException {
        int size = 10;
        if (argv.length > 0) {
            size = Integer.parseInt(argv[0]);
        }

        // need to sync start with agent thread
        checkStatus(status);

        for (int c = 0; c < size; c++) {
            Thread[] threads = new Thread[10];
            for (int i = 0; i < 10; i++) {
                TaskMonitor task = new TaskMonitor();

                threads[i] = Thread.builder()
                        .task(task)
                        .name("TestedThread")
                     //   .virtual()
                        .build();
            }

            for (Thread t : threads) {
                t.start();
            }

            for (Thread t : threads) {
                t.join();
            }
        }
    }
}


class TaskMonitor implements Runnable {
    public Object startingMonitor = new Object();
    public Object runningMonitor = new Object();
    public Object endingMonitor = new Object();

    // run thread continuously
    public void run() {
        // notify about starting
        synchronized (startingMonitor) {
            startingMonitor.notifyAll();
        }

        // notify about running
        synchronized (runningMonitor) {
            runningMonitor.notifyAll();
        }

        // wait for finish permit
        synchronized (endingMonitor) {
            // just finish
        }
    }
}

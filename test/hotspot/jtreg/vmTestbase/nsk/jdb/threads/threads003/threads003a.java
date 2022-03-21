/*
 * Copyright (c) 2002, 2022, Oracle and/or its affiliates. All rights reserved.
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

package nsk.jdb.threads.threads003;

import nsk.share.*;
import nsk.share.jpda.*;
import nsk.share.jdb.*;
import nsk.share.jdi.JDIThreadFactory;

import java.io.*;

/* This is debuggee aplication */
public class threads003a {
    public static void main(String args[]) {
        threads003a _threads003a = new threads003a();
        System.exit(threads003.JCK_STATUS_BASE + _threads003a.runIt(args, System.out));
    }

    static void breakpoint () {}

    static final String THREAD_NAME = nsk.jdb.threads.threads003.threads003.THREAD_NAME;
    static int numThreads           = nsk.jdb.threads.threads003.threads003.NUM_THREADS;
    static Object waitnotify        = new Object();

    public int runIt(String args[], PrintStream out) {
        JdbArgumentHandler argumentHandler = new JdbArgumentHandler(args);
        Log log = new Log(out, argumentHandler);

        Thread holder [] = new Thread[numThreads];
        Lock lock = new Lock();

        try {
            lock.setLock();
            for (int i = 0; i < numThreads ; i++) {
                boolean doBreakpoint = i % 2 == 1;  // breakpoint on odd threads only
                String name = THREAD_NAME + "-" + i;
                holder[i] = JDIThreadFactory.newThread(new MyThread(lock, doBreakpoint), name);
                synchronized (waitnotify) {
                    holder[i].start();
                    waitnotify.wait();
                }
            }
        } catch (Exception e) {
            System.err.println("TEST ERROR: Caught unexpected Exception while waiting in main thread: " +
                e.getMessage());
            System.exit(threads003.FAILED);
        }

        breakpoint();   // When jdb stops here, there should be 5 running MyThreads.
        lock.releaseLock();

        for (int i = 0; i < numThreads ; i++) {
            if (holder[i].isAlive()) {
                try {
                    holder[i].join(argumentHandler.getWaitTime() * 60000);
                } catch (InterruptedException e) {
                    throw new Failure("Unexpected InterruptedException catched while waiting for join of: " + holder[i]);
                }
            }
        }

        log.display("Debuggee PASSED");
        return threads003.PASSED;
    }

}

class Lock {
    boolean lockSet;

    synchronized void setLock() throws InterruptedException {
        while (lockSet == true)
            wait();
        lockSet = true;
    }

    synchronized void releaseLock() {
        if (lockSet == true) {
            lockSet = false;
            notify();
        }
    }
}

class MyThread implements Runnable {

    Lock lock;
    boolean doBreakpoint;
    MyThread (Lock l, boolean doBreakpoint) {
        this.lock = l;
        this.doBreakpoint = doBreakpoint;
    }

    public void run() {
        if (doBreakpoint) {
            threads003a.breakpoint();
        }
        synchronized (threads003a.waitnotify) {
            threads003a.waitnotify.notifyAll();
        }
        try {
            lock.setLock();
        } catch(Exception e) {
            System.err.println("TEST ERROR: Caught unexpected Exception while waiting in MyThread: " +
                e.getMessage());
            System.exit(threads003.FAILED);
        }
        lock.releaseLock();
    }
}

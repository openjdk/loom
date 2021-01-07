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

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;


/*
 * @test
 * @library /test/lib
 * @run main/othervm/native -agentlib:MethodExitVThread MethodExitVThreadTest
 */

public class MethodExitVThreadTest {

    static {
        try {
            System.loadLibrary("MethodExitVThread");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load MethodExitVThread library");
            System.err.println("java.library.path:" + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    final static AtomicInteger result = new AtomicInteger(0);
    native static int check();
    native static int init0();

    final static long NUM_OF_TASKS = 100_000;
    public static void main(String args[]) throws InterruptedException {

        ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < NUM_OF_TASKS; i++) {
            Runnable task = new Task();
            //Thread tested = Thread.newThread("tested", Thread.VIRTUAL, task);
            Thread thread = new Thread(task, "tested");
            threads.add(thread);
            thread.start();
        }

        for (Thread thread: threads) {
            thread.join();
        }
        /*
        if (result.get() != NUM_OF_TASKS * 3) {
            throw new RuntimeException("Check() returned " + result);
        }
        */
    }

    static void method1() {

    }

    static void method2() {

    }

    static class Task implements Runnable {

        @Override
        public void run() {
            init0();
            method1();
            LockSupport.parkNanos(10);
            method2();
            result.addAndGet(check());
        }
    }
}

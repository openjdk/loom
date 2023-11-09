/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test compensation on class initialization
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run junit/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI CompensationOnClassInit
 */

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.test.whitebox.WhiteBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

class CompensationOnClassInit {
    static WhiteBox wb = WhiteBox.getWhiteBox();
    static int VTHREAD_COUNT = Runtime.getRuntime().availableProcessors() * 8;
    static AtomicInteger initalizedCount = new AtomicInteger(0);
    static CyclicBarrier barrier;
    static Object lock = new Object();
    static volatile boolean done;

    class A {
        static {
            synchronized (lock) {
                initalizedCount.getAndIncrement();
            }
        }
    }

    @Disabled
    @Test
    void testCompensationOnClassInit() throws Exception {
        Thread[] contenders = new Thread[4];
        for (int i = 0; i < 4; i++) {
            contenders[i] = Thread.ofVirtual().start(() -> contenders());
        }

        Runnable barrierAction = () -> triggerUnloading();
        barrier = new CyclicBarrier(VTHREAD_COUNT, barrierAction);

        Thread[] loaders = new Thread[VTHREAD_COUNT];
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            loaders[i] = Thread.ofVirtual().start(() -> loadA());
        }

        for (int i = 0; i < 4; i++) {
            contenders[i].join();
        }
        for (int i = 0; i < VTHREAD_COUNT; i++) {
            loaders[i].join();
        }
    }

    private void contenders() {
        while (!done) {
            synchronized (lock) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {}
            }
            // Backoff to avoid monopolizing lock
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {}
        }
    }

    private void loadA() {
        int iterations = 10;
        while (iterations-- > 0) {
            A a = new A();
            a = null;
            try {
                barrier.await();
            } catch (Exception e) {}
        }
        done = true;
    }

    private static void triggerUnloading() {
        wb.fullGC();  // will do class unloading
        System.out.println("Starting new cycle. Counter is " + initalizedCount.get());
    }
}
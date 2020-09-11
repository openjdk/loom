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

/**
 * @test
 * @requires vm.debug != true
 * @run main/othervm -XX:-UseContinuationChunks GetStackTraceALot
 * @run main/othervm -XX:+UseContinuationChunks GetStackTraceALot
 * @run main/othervm -XX:-UseContinuationChunks -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler GetStackTraceALot
 * @run main/othervm -XX:+UseContinuationChunks -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler GetStackTraceALot
 * @summary Stress test asynchronous Thread.getStackTrace()
 */

/**
 * @test
 * @requires vm.debug == true
 * @run main/othervm/timeout=300 -XX:-UseContinuationChunks GetStackTraceALot 1000
 * @run main/othervm/timeout=300 -XX:+UseContinuationChunks GetStackTraceALot 1000
 * @run main/othervm/timeout=300 -XX:-UseContinuationChunks -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler GetStackTraceALot 1000
 * @run main/othervm/timeout=300 -XX:+UseContinuationChunks -XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler GetStackTraceALot 1000
 * @summary Stress test asynchronous Thread.getStackTrace()
 */

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class GetStackTraceALot {
    static class RoundRobinExecutor implements Executor {
        private final Executor[] es;
        private int e = 0;

        RoundRobinExecutor() {
            var tf = Thread.builder().name("worker-", 1).daemon(true).factory();
            this.es = new Executor[2];
            for (int i=0; i<es.length; i++)
                es[i] = Executors.newSingleThreadExecutor(tf);
        }

        @Override
        public void execute(Runnable task) {
            es[e].execute(task);
            e = (e + 1) % es.length;
        }
    }

    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) 
                                         : 10_000;

        final int ITERATIONS = iterations;
        final int SPIN_NANOS = 5000;

        Executor exec = new RoundRobinExecutor();
        AtomicInteger count = new AtomicInteger();

        Thread thread = Thread.builder().virtual(exec).task(() -> {
                while (count.incrementAndGet() < ITERATIONS) {
                    long start = System.nanoTime();
                    while (System.nanoTime() - start < SPIN_NANOS) Thread.onSpinWait();

                    LockSupport.parkNanos(500_000);
                }
            }).start();

        long start = System.nanoTime();
        while (thread.isAlive()) {
            StackTraceElement[] trace = thread.getStackTrace();
            // printStackTrace(trace);
            Thread.sleep(5);
            if (System.nanoTime() - start > 500_000_000) {
                System.out.println(count.get());
                start = System.nanoTime();
            }
        }

        int countValue = count.get();
        if (countValue != ITERATIONS) {
            throw new RuntimeException("count = " + countValue);
        }
    }

    static void printStackTrace(StackTraceElement[] stes) {
        if (stes == null) {
            System.out.println("NULL");
        } else {
            for (var ste : stes) {
                System.out.println("\t" + ste);
            }
        }
    }
}

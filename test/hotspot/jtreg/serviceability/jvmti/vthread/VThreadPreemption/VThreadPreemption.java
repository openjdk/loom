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
* @test id=default
* @summary Verifies JVMTI support in the context of virtual thread preemption
* @requires vm.continuations
* @library /test/lib
* @modules java.base/java.lang:+open
* @enablePreview
*
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations VThreadPreemption
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Dsuspendresumer=true VThreadPreemption
*/

/**
* @test id=interpreter-only
* @requires vm.continuations
* @requires vm.debug
* @library /test/lib
* @modules java.base/java.lang:+open
* @enablePreview
*
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+ShowHiddenFrames -XX:+VerifyContinuations -Xint -Dsleeptime=3 VThreadPreemption
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+ShowHiddenFrames -XX:-VerifyContinuations -XX:-UseTLAB VThreadPreemption
*/

/**
* @test id=compiler-only
* @requires vm.continuations
* @library /test/lib
* @modules java.base/java.lang:+open
* @enablePreview
*
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Xcomp -XX:-TieredCompilation -XX:CompileOnly=jdk/internal/vm/Continuation,VThreadPreemption VThreadPreemption
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Xcomp -XX:TieredStopAtLevel=3 -XX:CompileOnly=jdk/internal/vm/Continuation,VThreadPreemption VThreadPreemption
*/

/**
* @test id=miscellaneous
* @requires vm.continuations
* @library /test/lib
* @modules java.base/java.lang:+open
* @enablePreview
*
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:+DeoptimizeALot VThreadPreemption
*/

/**
* @test id=zgc
* @summary Tests for virtual thread preemption
* @requires vm.continuations
* @requires vm.debug
* @requires vm.gc.Z
* @library /test/lib
* @modules java.base/java.lang:+open
* @enablePreview
*
* @run main/othervm/native -agentlib:VThreadPreemption -XX:+ShowHiddenFrames -XX:+VerifyContinuations -XX:+UseZGC -Dsleeptime=3 VThreadPreemption
*/

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static jdk.test.lib.Asserts.assertEquals;

public class VThreadPreemption {
    private static final String agentLib = "VThreadPreemption";
    static final int SLEEPTIME = Integer.getInteger("sleeptime", 1);
    static final long NANOS_PER_SECOND = 1000000000;

    static boolean addSuspendResume = false;

    static native boolean check(int count, boolean strict);
    static native void markStart(int test);
    static native void markFinish();
    static native void suspendResume(Thread vthread);

    class PreemptTest {
        Runnable task;
        Thread preempters[];
        Thread vthread;
        Thread suspendResumer;
        AtomicInteger preemptCount;

        PreemptTest(Runnable task) {
            this.task = task;
            this.preempters = new Thread[1];
            this.preemptCount = new AtomicInteger(0);
        }

        PreemptTest(Runnable task, Integer preempters) {
            this.task = task;
            this.preempters = new Thread[preempters];
            this.preemptCount = new AtomicInteger(0);
        }

        void preempterLoop() {
            Integer preemptSuccessCount = 0, preemptFailedCount = 0;

            while (vthread.getState() != Thread.State.TERMINATED) {
                if (vthread.tryPreempt()) {
                    preemptSuccessCount++;
                } else {
                    preemptFailedCount++;
                    try {
                        Thread.sleep(SLEEPTIME);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            preemptCount.getAndAdd(preemptSuccessCount);
        }

        void waitTestEnd() throws Exception {
            vthread.join();
            for (int i = 0; i < preempters.length; i++) {
                preempters[i].join();
            }
            if (addSuspendResume) {
                suspendResumer.join();
            }
        }

        void startTest() {
            vthread = Thread.ofVirtual().unstarted(() -> task.run());
            for (int i = 0; i < preempters.length; i++) {
                preempters[i] = new Thread(() -> preempterLoop());
                preempters[i].start();
            }
            if (addSuspendResume) {
                suspendResumer = new Thread(() -> suspendResume(vthread));
                suspendResumer.start();
            }
            vthread.start();
        }

        int preemptCount() { return preemptCount.get(); }
    }

    public void test1() throws Exception {
        // Basic preempt test1
        System.out.println("test1");
        final long TOTAL_RUN_TIME = 5 * NANOS_PER_SECOND;
        Runnable testTask = () -> this.foo1(TOTAL_RUN_TIME);

        PreemptTest ptest = new PreemptTest(testTask);
        ptest.startTest();
        ptest.waitTestEnd();

        int totalEvents = ptest.preemptCount() + 1;
        if (!check(totalEvents, false /* strict */)) {
            throw new RuntimeException("VThreadTest failed!");
        }
    }

    private void foo1(long runTimeNanos) {
        markStart(1);
        int count = 0;
        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < runTimeNanos) {
            recurse1(20);
            if (count++ % 1000 == 0) {
              LockSupport.parkNanos(1000);
            }
        }
        markFinish();
    }

    private void recurse1(int depth) {
        if (depth > 0) {
            recurse1(depth - 1);
        }
    }

    public void test2() throws Exception {
        // Methods with stack-passed arguments
        System.out.println("test2");
        final long TOTAL_RUN_TIME = 8 * NANOS_PER_SECOND;
        Runnable testTask = () -> this.foo2(TOTAL_RUN_TIME);

        PreemptTest ptest = new PreemptTest(testTask);
        ptest.startTest();
        ptest.waitTestEnd();

        int totalEvents = ptest.preemptCount() + 1;
        if (!check(totalEvents, true /* strict */)) {
            throw new RuntimeException("VThreadTest failed!");
        }
    }

    private void foo2(long runTimeNanos) {
        markStart(2);
        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < runTimeNanos) {
            String r = "";
            for (int k = 1; k < 300; k++) {
                int x = 3;
                String s = "abc";
                r = bar2(3L, 3, 4, 5, 6, 7, 8,
                        1.1f, 2.2, 3.3, 4.4, 5.5, 6.6, 7.7,
                        1001, 1002, 1003, 1004, 1005, 1006, 1007);
            }
            double result = Double.parseDouble(r)+1;
        }
        markFinish();
    }

    private String bar2(long l1, int i1, int i2, int i3, int i4, int i5, int i6,
                       float f1, double d1, double d2, double d3, double d4, double d5, double d6,
                       Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        double x = 9.99;
        String s = "zzz";
        String r = baz2(i6, i5, i4, i3, i2, i1, l1,
                       f1, d1 + x, d2, d3, d4, d5, d6,
                       o1, o2, o3, o4, o5, o6, o7);
        return "" + r;
    }

    private String baz2(int i1, int i2, int i3, int i4, int i5, int i6, long l1,
                       float f1, double d1, double d2, double d3, double d4, double d5, double d6,
                       Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7) {
        double x = 9.99 + d3;
        String s = "zzz";
        loop2(i4, i5, i6);
        double r = l1 + x;
        return "" + r;
    }

    private void loop2(int a, int b, int c) {
        long start_time = System.currentTimeMillis();
        // loop for 1 ms
        while (System.currentTimeMillis() < start_time + 1) {
        }
    }

    public void test3() throws Exception {
        // Exercise return oop on stub case
        System.out.println("test3");
        final long TOTAL_RUN_TIME = 8 * NANOS_PER_SECOND;
        Runnable testTask = () -> this.foo3(TOTAL_RUN_TIME);

        PreemptTest ptest = new PreemptTest(testTask);
        ptest.startTest();
        ptest.waitTestEnd();

        int totalEvents = ptest.preemptCount() + 1;
        if (!check(totalEvents, true /* strict */)) {
            throw new RuntimeException("VThreadTest failed!");
        }
    }

    private void foo3(long runTimeNanos) {
        markStart(3);
        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < runTimeNanos) {
            List<Integer> list = new ArrayList<>();
            recurse3(list, 40);
            checkListContents(list, 41);
        }
        markFinish();
    }

    private List<Integer> recurse3(List<Integer> list, int depth) {
        list.add(depth);
        if (depth > 0) {
            return recurse3(list, depth - 1);
        }
        return list;
    }

    private void checkListContents(List<Integer> l, int n) {
        for (int i = 0; i < n; i++) {
            assertEquals(l.contains(i), true);
        }
    }

    public void test4() throws Exception {
        // Multiple preempters
        System.out.println("test4");
        final long TOTAL_RUN_TIME = 5 * NANOS_PER_SECOND;
        Runnable testTask = () -> this.foo1(TOTAL_RUN_TIME);

        PreemptTest ptest = new PreemptTest(testTask, 3);
        ptest.startTest();
        ptest.waitTestEnd();

        int totalEvents = ptest.preemptCount() + 1;
        if (!check(totalEvents, false /* strict */)) {
            throw new RuntimeException("VThreadTest failed!");
        }
    }

    void runTest() throws Exception {
        test1();
        test2();
        test3();
        test4();
    }

    public static void main(String[] args) throws Exception {
        try {
            System.loadLibrary(agentLib);
        } catch (UnsatisfiedLinkError ex) {
            System.err.println("Failed to load " + agentLib + " lib");
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw ex;
        }

        if (args.length == 1) {
            System.out.println("SuspendResume added");
            addSuspendResume = true;
        }

        VThreadPreemption obj = new VThreadPreemption();
        obj.runTest();
    }

    private static Thread.Builder.OfVirtual virtualThreadBuilder(Executor scheduler) {
        Thread.Builder.OfVirtual builder = Thread.ofVirtual();
        try {
            Class<?> clazz = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
            Constructor<?> ctor = clazz.getDeclaredConstructor(Executor.class);
            ctor.setAccessible(true);
            return (Thread.Builder.OfVirtual) ctor.newInstance(scheduler);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

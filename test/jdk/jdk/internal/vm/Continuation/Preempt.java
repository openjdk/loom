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
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @modules java.base/jdk.internal.vm
* @modules java.base/jdk.internal.access
* @enablePreview
*
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations Preempt
*/

/**
* @test id=interpreter-only
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @requires vm.debug
* @modules java.base/jdk.internal.vm
* @modules java.base/jdk.internal.access
* @enablePreview
*
* @run testng/othervm -XX:+ShowHiddenFrames -XX:+VerifyContinuations -Xint -Dsleeptime=3 Preempt
* @run testng/othervm -XX:+ShowHiddenFrames -XX:-VerifyContinuations -Xint -XX:-UseTLAB Preempt
*/

/**
* @test id=compiler-only
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @modules java.base/jdk.internal.vm
* @modules java.base/jdk.internal.access
* @enablePreview
*
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Xcomp -XX:-TieredCompilation -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt Preempt
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -Xcomp -XX:TieredStopAtLevel=3 -XX:CompileOnly=jdk/internal/vm/Continuation,Preempt Preempt
*/

/**
* @test id=miscellaneous
* @requires vm.continuations
* @modules java.base/jdk.internal.vm
* @modules java.base/jdk.internal.access
* @enablePreview
*
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:+DeoptimizeALot Preempt
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames -XX:+IgnoreUnrecognizedVMOptions -XX:-VerifyContinuations -XX:+FullGCALot -XX:FullGCALotInterval=1000 Preempt
*/

/**
* @test id=zgc
* @summary Tests for jdk.internal.vm.Continuation preemption
* @requires vm.continuations
* @requires vm.debug
* @requires vm.gc.Z
* @modules java.base/jdk.internal.vm
* @modules java.base/jdk.internal.access
* @enablePreview
*
* @run testng/othervm -XX:+ShowHiddenFrames -XX:+VerifyContinuations -XX:+ShowHiddenFrames -XX:+UseZGC -Dsleeptime=3 Preempt
*/

import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

public class Preempt {
    static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    static final ContinuationScope FOO = new ContinuationScope() {};
    static final int SLEEPTIME = Integer.getInteger("sleeptime", 1);
    static final long NANOS_PER_SECOND = 1000000000;
    static final long MILLIS_PER_NANOS = 1000000;
    volatile int x = 0;

    class PreemptTest {
        List<Thread> preempters = new ArrayList<>();
        Semaphore runContSem = new Semaphore(1);
        Continuation cont;
        volatile Thread target;
        boolean isVirtual;
        boolean done = false;
        boolean doGC = false;
        static final Integer countToGC = 20;

        PreemptTest(Continuation c) {
            cont = c;
            target = JLA.currentCarrierThread();
            isVirtual = Thread.currentThread().isVirtual();
        }

        PreemptTest(Continuation c, Boolean gc) {
            cont = c;
            target = JLA.currentCarrierThread();
            isVirtual = Thread.currentThread().isVirtual();
            doGC = gc;
        }

        void runOnce() throws Exception {
            runContSem.acquire();
            assertEquals(runContSem.availablePermits(), 0);
            if (isVirtual) {
                // Update target in case carrier changed.
                target = JLA.currentCarrierThread();
            }
            cont.run();
        }

        void preempterLoop() {
            Continuation.PreemptStatus res;
            Integer preemptSuccessCount = 0, preemptFailedCount = 0;
            long maxTime = 0, minTime = Long.MAX_VALUE, totalTime = 0;
            long runStartTime = System.nanoTime();

            while (!done) {
                long startTime = System.nanoTime();
                res = cont.tryPreempt(target);
                if (res == Continuation.PreemptStatus.SUCCESS) {
                    // Keep stats
                    long duration = System.nanoTime() - startTime;
                    if (duration > maxTime) maxTime = duration;
                    if (duration < minTime) minTime = duration;
                    totalTime += duration;
                    preemptSuccessCount++;

                    List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                    assertEquals(frames.containsAll(Arrays.asList("enter")), true);
                    if (doGC && ((preemptSuccessCount % countToGC) == 0)) {
                        System.gc();
                    }
                    assertEquals(runContSem.availablePermits(), 0);
                    runContSem.release();
                } else {
                    preemptFailedCount++;
                    assertEquals(res != Continuation.PreemptStatus.TRANSIENT_FAIL_PINNED_CRITICAL_SECTION && res != Continuation.PreemptStatus.PERM_FAIL_UNSUPPORTED, true);
                    try {
                        Thread.sleep(SLEEPTIME);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            long totalRunningTime = (System.nanoTime() - runStartTime) / MILLIS_PER_NANOS;
            assertEquals(preemptSuccessCount != 0, true);
            System.out.println("Preempter exiting. Total running time=" + totalRunningTime + "ms, preemptSuccessCount=" + preemptSuccessCount + ", preemptFailedCount=" + preemptFailedCount);
            System.out.println("Preemption time stats: averageTime=" + (totalTime/preemptSuccessCount)/1000 + "us, minTime=" + minTime/1000 + "us, maxTime=" + maxTime/1000 + "us");
        }

        void startPreempter() {
            Thread t = new Thread(() -> preempterLoop());
            t.start();
            preempters.add(t);
        }

        void endTest() throws Exception {
            done = true;
            for (Thread preempter : preempters) {
                preempter.join();
            }
        }
    }

    @Test
    public void test1() throws Exception {
        // Basic preempt test
        System.out.println("test1");
        final long TOTAL_RUN_TIME = 5 * NANOS_PER_SECOND;
        Continuation cont = new Continuation(FOO, () -> { this.foo1(); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();

        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < TOTAL_RUN_TIME) {
            ptest.runOnce();
            assertEquals(cont.isDone(), false);
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo1", "enter")), true);
        }
        assertEquals(cont.isDone(), false);
        ptest.endTest();
    }

    private void foo1() {
        while (true) {
            x++;
            recurse1(20);
        }
    }

    private void recurse1(int depth) {
        if (depth > 0) {
            recurse1(depth - 1);
        }
    }

    @Test
    public void test2() throws Exception {
        // Methods with stack-passed arguments
        System.out.println("test2");
        final AtomicBoolean finished = new AtomicBoolean(false);
        final long TOTAL_RUN_TIME = 8 * NANOS_PER_SECOND;
        Continuation cont = new Continuation(FOO, () -> { this.foo2(finished, TOTAL_RUN_TIME); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();

        while(!cont.isDone()) {
            ptest.runOnce();
            if (cont.isDone()) break;
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo2", "enter")) || finished.get(), true);
        }
        assertEquals(cont.isPreempted(), false);
        ptest.endTest();
    }

    private double foo2(AtomicBoolean finished, long runTimeNanos) {
        String r = "";
        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < runTimeNanos) {
            int x = 3;
            String s = "abc";
            r = bar2(3L, 3, 4, 5, 6, 7, 8,
                    1.1f, 2.2, 3.3, 4.4, 5.5, 6.6, 7.7,
                    1001, 1002, 1003, 1004, 1005, 1006, 1007);
        }
        double result = Double.parseDouble(r)+1;
        finished.set(true);
        return result;
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
        // loop for 5 ms
        while (System.currentTimeMillis() < start_time + 1) {
            x++;
        }
    }

    @Test
    public void test3() throws Exception {
        // Return oop on stub case
        System.out.println("test3");
        final AtomicBoolean finished = new AtomicBoolean(false);
        final long TOTAL_RUN_TIME = 8 * NANOS_PER_SECOND;
        Continuation cont = new Continuation(FOO, () -> { this.foo3(finished, TOTAL_RUN_TIME); });

        PreemptTest ptest = new PreemptTest(cont, true /* doGC */);
        ptest.startPreempter();

        while(!cont.isDone()) {
            ptest.runOnce();
            if (cont.isDone()) break;
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo3", "enter")) || finished.get(), true);
        }
        assertEquals(cont.isPreempted(), false);
        ptest.endTest();
    }

    private void foo3(AtomicBoolean finished, long runTimeNanos) {
        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < runTimeNanos) {
            x++;
            List<Integer> list = new ArrayList<>();
            recurse3(list, 40);
            checkListContents(list, 41);
        }
        finished.set(true);
    }

    private List<Integer> recurse3(List<Integer> list, int depth) {
        x++;
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

    @Test
    public void test4() throws Exception {
        // Multiple preempters
        System.out.println("test4");
        final long TOTAL_RUN_TIME = 5 * NANOS_PER_SECOND;
        Continuation cont = new Continuation(FOO, () -> { this.foo1(); });

        PreemptTest ptest = new PreemptTest(cont);
        ptest.startPreempter();
        ptest.startPreempter();
        ptest.startPreempter();

        long startTime = System.nanoTime();
        while (System.nanoTime() - startTime < TOTAL_RUN_TIME) {
            ptest.runOnce();
            assertEquals(cont.isDone(), false);
            assertEquals(cont.isPreempted(), true);

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames.containsAll(Arrays.asList("foo1", "enter")), true);
        }
        assertEquals(cont.isDone(), false);
        ptest.endTest();
    }
}
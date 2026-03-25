/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test carrier affinity hints for virtual threads
 * @requires vm.continuations
 * @modules java.base/java.lang:+open
 * @run junit CarrierAffinity
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CarrierAffinity {

    /**
     * Returns the builtin scheduler parallelism.
     */
    private static int schedulerParallelism() {
        return ((ForkJoinPool) VirtualThread.defaultScheduler()).getParallelism();
    }

    /**
     * Returns the current carrier thread's worker index, or -1 if not on a FJ worker.
     */
    private static int currentWorkerIndex() {
        Thread carrier = Thread.currentCarrierThread();
        if (carrier instanceof ForkJoinWorkerThread fjwt) {
            return fjwt.getPoolIndex();
        }
        return -1;
    }

    /**
     * Test that roundRobinAffinity distributes threads across workers in sequence.
     */
    @Test
    void testRoundRobinAffinityIndex() throws Exception {
        int parallelism = schedulerParallelism();
        ThreadFactory factory = Thread.ofVirtual()
                .name("rr-", 0)
                .roundRobinAffinity()
                .factory();

        int numThreads = parallelism * 3;
        for (int i = 0; i < numThreads; i++) {
            int expectedIndex = i % parallelism;
            VirtualThread vt = (VirtualThread) factory.newThread(() -> {});
            assertEquals(expectedIndex, vt.affinityWorkerIndex,
                    "Thread " + i + " should have affinity index " + expectedIndex);
        }
    }

    /**
     * Test that roundRobinAffinity threads tend to run on their target worker
     * after park/unpark cycles.
     */
    @Test
    void testRoundRobinAffinityExecution() throws Exception {
        int parallelism = schedulerParallelism();
        ThreadFactory factory = Thread.ofVirtual()
                .name("rr-exec-", 0)
                .roundRobinAffinity()
                .factory();

        int numThreads = parallelism;
        AtomicIntegerArray affinityHits = new AtomicIntegerArray(numThreads);
        AtomicIntegerArray totalRuns = new AtomicIntegerArray(numThreads);
        CountDownLatch done = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadIdx = i;
            Thread t = factory.newThread(() -> {
                int expectedWorker = threadIdx % parallelism;
                for (int j = 0; j < 50; j++) {
                    totalRuns.incrementAndGet(threadIdx);
                    if (currentWorkerIndex() == expectedWorker) {
                        affinityHits.incrementAndGet(threadIdx);
                    }
                    LockSupport.parkNanos(1);
                }
                done.countDown();
            });
            t.start();
        }

        done.await();

        for (int i = 0; i < numThreads; i++) {
            int hits = affinityHits.get(i);
            int total = totalRuns.get(i);
            assertTrue(hits > total / 2,
                    "Thread " + i + ": only " + hits + "/" + total + " on target worker");
        }
    }

    /**
     * Test that inheritAffinity(Thread) copies the affinity index from a virtual thread.
     */
    @Test
    void testInheritAffinity() throws Exception {
        var parentAffinityIndex = new AtomicInteger(-2);
        var childAffinityIndex = new AtomicInteger(-2);
        CountDownLatch childDone = new CountDownLatch(1);

        Thread parent = Thread.ofVirtual()
                .name("parent")
                .roundRobinAffinity()
                .unstarted(() -> {
                    parentAffinityIndex.set(((VirtualThread) Thread.currentThread()).affinityWorkerIndex);

                    ThreadFactory childFactory = Thread.ofVirtual()
                            .name("child")
                            .inheritAffinity(Thread.currentThread())
                            .factory();

                    VirtualThread child = (VirtualThread) childFactory.newThread(() -> childDone.countDown());
                    childAffinityIndex.set(child.affinityWorkerIndex);
                    child.start();
                    try { childDone.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                });
        parent.start();
        parent.join();

        assertTrue(parentAffinityIndex.get() >= 0, "Parent should have a valid affinity index");
        assertEquals(parentAffinityIndex.get(), childAffinityIndex.get(),
                "Child should inherit parent's affinity index");
    }

    /**
     * Test that no-arg inheritAffinity() resolves the caller's affinity at newThread time.
     */
    @Test
    void testInheritAffinityFromCaller() throws Exception {
        ThreadFactory handlerFactory = Thread.ofVirtual()
                .name("handler-", 0)
                .inheritAffinity()
                .factory();

        var callerAffinity = new AtomicInteger(-2);
        var childAffinity = new AtomicInteger(-2);
        CountDownLatch done = new CountDownLatch(1);

        Thread eventLoop = Thread.ofVirtual()
                .name("event-loop")
                .roundRobinAffinity()
                .unstarted(() -> {
                    callerAffinity.set(((VirtualThread) Thread.currentThread()).affinityWorkerIndex);
                    VirtualThread handler = (VirtualThread) handlerFactory.newThread(() -> done.countDown());
                    childAffinity.set(handler.affinityWorkerIndex);
                    handler.start();
                    try { done.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                });
        eventLoop.start();
        eventLoop.join();

        assertTrue(callerAffinity.get() >= 0, "Caller should have affinity");
        assertEquals(callerAffinity.get(), childAffinity.get(),
                "Handler should inherit caller's affinity at newThread time");
    }

    /**
     * Test that inheritAffinity with a non-virtual thread degrades to no affinity.
     */
    @Test
    void testInheritAffinityFromPlatformThread() {
        ThreadFactory factory = Thread.ofVirtual()
                .name("inherit-platform-", 0)
                .inheritAffinity(Thread.currentThread())
                .factory();

        VirtualThread vt = (VirtualThread) factory.newThread(() -> {});
        assertEquals(-1, vt.affinityWorkerIndex,
                "inheritAffinity from platform thread should yield no affinity");
    }

    /**
     * Test that inheritAffinity with null degrades to no affinity.
     */
    @Test
    void testInheritAffinityFromNull() {
        ThreadFactory factory = Thread.ofVirtual()
                .name("inherit-null-", 0)
                .inheritAffinity(null)
                .factory();

        VirtualThread vt = (VirtualThread) factory.newThread(() -> {});
        assertEquals(-1, vt.affinityWorkerIndex,
                "inheritAffinity from null should yield no affinity");
    }

    /**
     * Test that setting roundRobinAffinity clears a previous inheritAffinity.
     */
    @Test
    void testAffinityModeSwitch() {
        ThreadFactory factory = Thread.ofVirtual()
                .name("switch-", 0)
                .inheritAffinity(Thread.currentThread())
                .roundRobinAffinity()
                .factory();

        VirtualThread t1 = (VirtualThread) factory.newThread(() -> {});
        VirtualThread t2 = (VirtualThread) factory.newThread(() -> {});
        assertEquals(0, t1.affinityWorkerIndex, "First round-robin thread should get index 0");
        assertEquals(1, t2.affinityWorkerIndex, "Second round-robin thread should get index 1");
    }

    /**
     * Test that threads without affinity still work normally.
     */
    @Test
    void testNoAffinity() throws Exception {
        ThreadFactory factory = Thread.ofVirtual()
                .name("no-affinity-", 0)
                .factory();

        VirtualThread vt = (VirtualThread) factory.newThread(() -> {});
        assertEquals(-1, vt.affinityWorkerIndex, "Default thread should have no affinity");

        CountDownLatch done = new CountDownLatch(1);
        factory.newThread(done::countDown).start();
        done.await();
    }

    /**
     * Test affinity re-submission after Thread.yield.
     */
    @Test
    void testAffinityAfterYield() throws Exception {
        ThreadFactory factory = Thread.ofVirtual()
                .name("yield-", 0)
                .roundRobinAffinity()
                .factory();

        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger totalChecks = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        factory.newThread(() -> {
            for (int i = 0; i < 100; i++) {
                totalChecks.incrementAndGet();
                if (currentWorkerIndex() == 0) {
                    targetHits.incrementAndGet();
                }
                Thread.yield();
            }
            done.countDown();
        }).start();
        done.await();

        int hits = targetHits.get();
        int total = totalChecks.get();
        assertTrue(hits > total / 2,
                "Only " + hits + "/" + total + " runs on target worker after yield");
    }

    /**
     * Test that inheritAffinity from a thread with no affinity degrades to -1.
     */
    @Test
    void testInheritAffinityFromNoAffinityThread() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        var childAffinity = new AtomicInteger(-2);

        Thread vt = Thread.ofVirtual().name("no-aff").start(() -> {
            // This thread has no affinity (affinityWorkerIndex == -1)
            ThreadFactory factory = Thread.ofVirtual()
                    .name("child-", 0)
                    .inheritAffinity(Thread.currentThread())
                    .factory();
            VirtualThread child = (VirtualThread) factory.newThread(() -> {});
            childAffinity.set(child.affinityWorkerIndex);
            ready.countDown();
        });
        ready.await();
        vt.join();

        assertEquals(-1, childAffinity.get(),
                "inheritAffinity from thread with no affinity should yield -1");
    }
}

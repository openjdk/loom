/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng Monitoring
 * @summary Test javax.management.ThreadMXBean with virtual threads
 */

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Monitoring {

    /**
     * Test that ThreadMXBean::getAllThreadsIds does not include thread ids for
     * virtual threads.
     */
    public void testGetAllThreadIds() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long currentTid = Thread.currentThread().getId();
            long[] tids = ManagementFactory.getThreadMXBean().getAllThreadIds();
            boolean found = Arrays.stream(tids).anyMatch(tid -> tid == currentTid);
            assertFalse(found);
        });
    }

    /**
     * Test that ThreadMXBean::getThreadInfo returns null for a virual thread.
     */
    public void testGetThreadInfo1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long tid = Thread.currentThread().getId();
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(tid);
            assertTrue(info == null);
        });
    }

    /**
     * Test that ThreadMXBean::getThreadInfo on a carrier thread. The stack
     * frames of the virtual thread should not be returned.
     */
    public void testGetThreadInfo2() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            var carrierRef = new AtomicReference<Thread>();
            Executor scheduler = (task) -> {
                pool.execute(() -> {
                    carrierRef.set(Thread.currentThread());
                    task.run();
                });
            };

            // start virtual thread so carrier Thread can be captured
            Thread.ofVirtual().scheduler(scheduler).start(() -> { }).join();
            Thread carrier = carrierRef.get();
            assertTrue(carrier != null && !carrier.isVirtual());

            try (Selector sel = Selector.open()) {
                // start virtual thread that blocks in a native method
                Thread.ofVirtual().scheduler(scheduler).start(() -> {
                    try {
                        sel.select();
                    } catch (Exception e) { }
                });

                // invoke getThreadInfo get and check the stack trace
                long tid = carrier.getId();
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(tid, 100);

                // should only see carrier frames
                StackTraceElement[] stack = info.getStackTrace();
                assertTrue(contains(stack, "java.util.concurrent.ThreadPoolExecutor"));
                assertFalse(contains(stack, "java.nio.channels.Selector"));

                // carrier should not be holding any monitors
                assertTrue(info.getLockedMonitors().length == 0);
            }
        }
    }

    /**
     * Test that getThreadCpuTime returns -1 for a virual thread..
     */
    public void testGetThreadCpuTime() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long tid = Thread.currentThread().getId();
            long cpuTime = ManagementFactory.getThreadMXBean().getThreadCpuTime(tid);
            assertTrue(cpuTime == -1L);
        });
    }

    /**
     * Test that getThreadUserTime returns -1 for a virual thread.
     */
    public void testGetThreadUserTime() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long tid = Thread.currentThread().getId();
            long cpuTime = ManagementFactory.getThreadMXBean().getThreadUserTime(tid);
            assertTrue(cpuTime == -1L);
        });
    }

    /**
     * Test that ThreadMXBean::getCurrentThreadCpuTime throws UOE when invoked
     * on a virtual thread.
     */
    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testGetCurrentThreadCpuTime() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        });
    }

    /**
     * Test that ThreadMXBean::getCurrentThreadUserTime throws UOE when
     * invoked on a virtual thread.
     */
    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testGetCurrentThreadUserTime() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ManagementFactory.getThreadMXBean().getCurrentThreadUserTime();
        });
    }

    /**
     * Test that ThreadMXBean::getCurrentThreadAllocatedBytes returns -1 when
     * invoked on a virtual thread.
     */
    public void testGetCurrentThreadAllocatedBytes() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long allocated = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class)
                    .getCurrentThreadAllocatedBytes();
            assertTrue(allocated == -1L);
        });
    }

    private static boolean contains(StackTraceElement[] stack, String className) {
        return Arrays.stream(stack)
                .map(StackTraceElement::getClassName)
                .anyMatch(className::equals);
    }
}

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
 * @run testng BasicTests
 * @summary Unit tests for java.lang.ThreadGroup
 */

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class BasicTests {

    public void testGetName1() {
        ThreadGroup group = new ThreadGroup(null);
        assertTrue(group.getName() == null);
    }

    public void testGetName2() {
        ThreadGroup group = new ThreadGroup("fred");
        assertEquals(group.getName(), "fred");
    }

    public void testGetParent() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        ThreadGroup group3 = new ThreadGroup(group2, "group3");

        assertTrue(group1.getParent() == Thread.currentThread().getThreadGroup());
        assertTrue(group2.getParent() == group1);
        assertTrue(group3.getParent() == group2);
    }

    public void testParentOf() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        ThreadGroup group3 = new ThreadGroup(group2, "group3");

        assertFalse(group1.parentOf(null));
        assertTrue(group1.parentOf(group1));
        assertTrue(group1.parentOf(group2));
        assertTrue(group1.parentOf(group3));

        assertFalse(group2.parentOf(null));
        assertFalse(group2.parentOf(group1));
        assertTrue(group2.parentOf(group2));
        assertTrue(group2.parentOf(group3));

        assertFalse(group3.parentOf(null));
        assertFalse(group3.parentOf(group1));
        assertFalse(group3.parentOf(group2));
        assertTrue(group3.parentOf(group3));
    }

    public void testActiveCount1() {
        ThreadGroup group = new ThreadGroup("group");
        assertTrue(group.activeCount() == 0);
        TestThread thread = TestThread.start(group, "foo");
        try {
            assertTrue(group.activeCount() == 1);
        } finally {
            thread.terminate();
        }
        assertTrue(group.activeCount() == 0);
    }

    public void testActiveCount2() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        assertTrue(group1.activeCount() == 0);
        assertTrue(group2.activeCount() == 0);
        TestThread thread1 = TestThread.start(group1, "foo");
        try {
            assertTrue(group1.activeCount() == 1);
            assertTrue(group2.activeCount() == 0);
            TestThread thread2 = TestThread.start(group2, "bar");
            try {
                assertTrue(group1.activeCount() == 2);
                assertTrue(group2.activeCount() == 1);
            } finally {
                thread2.terminate();
            }
            assertTrue(group1.activeCount() == 1);
            assertTrue(group2.activeCount() == 0);
        } finally {
            thread1.terminate();
        }
        assertTrue(group1.activeCount() == 0);
        assertTrue(group2.activeCount() == 0);
    }

    public void enumerateThreads1() {
        ThreadGroup group = new ThreadGroup("group");
        Thread[] threads = new Thread[100];
        assertTrue(group.enumerate(threads) == 0);
        assertTrue(group.enumerate(threads, true) == 0);
        assertTrue(group.enumerate(threads, false) == 0);
        TestThread thread = TestThread.start(group, "foo");
        try {
            assertTrue(group.enumerate(threads) == 1);
            assertTrue(threads[0] == thread);
            assertTrue(group.enumerate(threads, true) == 1);
            assertTrue(threads[0] == thread);
            assertTrue(group.enumerate(threads, false) == 1);
            assertTrue(threads[0] == thread);
        } finally {
            thread.terminate();
        }
        assertTrue(group.activeCount() == 0);
    }

    public void enumerateThreads2() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");

        Thread[] threads = new Thread[100];
        assertTrue(group1.enumerate(threads) == 0);
        assertTrue(group1.enumerate(threads, true) == 0);
        assertTrue(group1.enumerate(threads, false) == 0);
        assertTrue(group2.enumerate(threads) == 0);
        assertTrue(group2.enumerate(threads, true) == 0);
        assertTrue(group2.enumerate(threads, false) == 0);

        TestThread thread1 = TestThread.start(group1, "foo");
        try {
            assertTrue(group1.enumerate(threads) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group1.enumerate(threads, true) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group1.enumerate(threads, false) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group2.enumerate(threads) == 0);
            assertTrue(group2.enumerate(threads, true) == 0);
            assertTrue(group2.enumerate(threads, false) == 0);

            TestThread thread2 = TestThread.start(group2, "bar");
            try {
                assertTrue(group1.enumerate(threads) == 2);
                assertEquals(toSet(threads, 2), Set.of(thread1, thread2));
                assertTrue(group1.enumerate(threads, true) == 2);
                assertEquals(toSet(threads, 2), Set.of(thread1, thread2));
                assertTrue(group1.enumerate(threads, false) == 1);
                assertTrue(threads[0] == thread1);
                assertTrue(group2.enumerate(threads) == 1);
                assertTrue(group2.enumerate(threads, true) == 1);
                assertTrue(threads[0] == thread2);
                assertTrue(group2.enumerate(threads, false) == 1);
                assertTrue(threads[0] == thread2);
            } finally {
                thread2.terminate();
            }

            assertTrue(group1.enumerate(threads) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group1.enumerate(threads, true) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group1.enumerate(threads, false) == 1);
            assertTrue(threads[0] == thread1);
            assertTrue(group2.enumerate(threads) == 0);
            assertTrue(group2.enumerate(threads, true) == 0);
            assertTrue(group2.enumerate(threads, false) == 0);
        } finally {
            thread1.terminate();
        }
        assertTrue(group1.activeCount() == 0);
        assertTrue(group2.activeCount() == 0);
    }

    public void enumerateThreads3() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        ThreadGroup group3 = new ThreadGroup(group2, "group3");

        Thread[] threads = new Thread[100];
        assertTrue(group1.enumerate(threads) == 0);
        assertTrue(group1.enumerate(threads, true) == 0);
        assertTrue(group1.enumerate(threads, false) == 0);
        assertTrue(group2.enumerate(threads) == 0);
        assertTrue(group2.enumerate(threads, true) == 0);
        assertTrue(group2.enumerate(threads, false) == 0);
        assertTrue(group3.enumerate(threads) == 0);
        assertTrue(group3.enumerate(threads, true) == 0);
        assertTrue(group3.enumerate(threads, false) == 0);

        TestThread thread2 = TestThread.start(group2, "foo");
        try {
            assertTrue(group1.enumerate(threads) == 1);
            assertTrue(group1.enumerate(threads, true) == 1);
            assertTrue(group1.enumerate(threads, false) == 0);

            assertTrue(group2.enumerate(threads) == 1);
            assertTrue(group2.enumerate(threads, true) == 1);
            assertTrue(threads[0] == thread2);
            assertTrue(group2.enumerate(threads, false) == 1);
            assertTrue(threads[0] == thread2);

            assertTrue(group3.enumerate(threads) == 0);
            assertTrue(group3.enumerate(threads, true) == 0);
            assertTrue(group3.enumerate(threads, false) == 0);

            TestThread thread3 = TestThread.start(group3, "bar");
            try {
                assertTrue(group1.enumerate(threads) == 2);
                assertTrue(group1.enumerate(threads, true) == 2);
                assertEquals(toSet(threads, 2), Set.of(thread2, thread3));
                assertTrue(group1.enumerate(threads, false) == 0);

                assertTrue(group2.enumerate(threads) == 2);
                assertEquals(toSet(threads, 2), Set.of(thread2, thread3));
                assertTrue(group2.enumerate(threads, true) == 2);
                assertEquals(toSet(threads, 2), Set.of(thread2, thread3));
                assertTrue(group2.enumerate(threads, false) == 1);
                assertTrue(threads[0] == thread2);

                assertTrue(group3.enumerate(threads) == 1);
                assertTrue(threads[0] == thread3);
                assertTrue(group3.enumerate(threads, true) == 1);
                assertTrue(threads[0] == thread3);
                assertTrue(group3.enumerate(threads, false) == 1);
                assertTrue(threads[0] == thread3);
            } finally {
                thread3.terminate();
            }

            assertTrue(group1.enumerate(threads) == 1);
            assertTrue(group1.enumerate(threads, true) == 1);
            assertTrue(group1.enumerate(threads, false) == 0);

            assertTrue(group2.enumerate(threads) == 1);
            assertTrue(group2.enumerate(threads, true) == 1);
            assertTrue(threads[0] == thread2);
            assertTrue(group2.enumerate(threads, false) == 1);
            assertTrue(threads[0] == thread2);

            assertTrue(group3.enumerate(threads) == 0);
            assertTrue(group3.enumerate(threads, true) == 0);
            assertTrue(group3.enumerate(threads, false) == 0);

        } finally {
            thread2.terminate();
        }

        assertTrue(group1.enumerate(threads) == 0);
        assertTrue(group1.enumerate(threads, true) == 0);
        assertTrue(group1.enumerate(threads, false) == 0);
        assertTrue(group2.enumerate(threads) == 0);
        assertTrue(group2.enumerate(threads, true) == 0);
        assertTrue(group2.enumerate(threads, false) == 0);
        assertTrue(group3.enumerate(threads) == 0);
        assertTrue(group3.enumerate(threads, true) == 0);
        assertTrue(group3.enumerate(threads, false) == 0);
    }

    public void enumerateGroups1() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        assertTrue(group1.activeGroupCount() == 0);
        assertTrue(group2.activeGroupCount() == 0);
        TestThread thread = TestThread.start(group2, "foo");
        try {
            assertTrue(group1.activeGroupCount() == 1);
            assertTrue(group2.activeGroupCount() == 0);
        } finally {
            thread.terminate();
        }
        assertTrue(group1.activeGroupCount() == 0);
        assertTrue(group2.activeGroupCount() == 0);
    }

    public void enumerateGroups2() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        ThreadGroup group3 = new ThreadGroup(group2, "group3");
        assertTrue(group1.activeGroupCount() == 0);
        assertTrue(group2.activeGroupCount() == 0);
        assertTrue(group3.activeGroupCount() == 0);
        TestThread thread = TestThread.start(group3, "foo");
        try {
            assertTrue(group1.activeGroupCount() == 2);
            assertTrue(group2.activeGroupCount() == 1);
            assertTrue(group3.activeGroupCount() == 0);
        } finally {
            thread.terminate();
        }
        assertTrue(group1.activeGroupCount() == 0);
        assertTrue(group2.activeGroupCount() == 0);
        assertTrue(group3.activeGroupCount() == 0);
    }

    public void testMaxPriority1() {
        ThreadGroup group = new ThreadGroup("group");
        int maxPriority = group.getMaxPriority();
        assertTrue(maxPriority <= Thread.currentThread().getThreadGroup().getMaxPriority());

        group.setMaxPriority(Thread.MIN_PRIORITY-1);
        assertTrue(group.getMaxPriority() == maxPriority);

        group.setMaxPriority(Thread.MAX_PRIORITY+1);
        assertTrue(group.getMaxPriority() == maxPriority);

        group.setMaxPriority(maxPriority+1);
        assertTrue(group.getMaxPriority() == maxPriority);

        if (maxPriority > Thread.MIN_PRIORITY) {
            group.setMaxPriority(maxPriority-1);
            assertTrue(group.getMaxPriority() == (maxPriority-1));
        }
    }

    public void testMaxPriority2() {
        ThreadGroup group1 = new ThreadGroup("group1");
        int maxPriority = group1.getMaxPriority();
        if (maxPriority > Thread.MIN_PRIORITY) {
            ThreadGroup group2 = new ThreadGroup(group1, "group2");
            assertTrue(group2.getMaxPriority() == maxPriority);

            ThreadGroup group3 = new ThreadGroup(group2, "group3");
            assertTrue(group3.getMaxPriority() == maxPriority);

            group1.setMaxPriority(Thread.MIN_PRIORITY);
            assertTrue(group1.getMaxPriority() == Thread.MIN_PRIORITY);
            assertTrue(group2.getMaxPriority() == Thread.MIN_PRIORITY);
            assertTrue(group3.getMaxPriority() == Thread.MIN_PRIORITY);
            group1.setMaxPriority(maxPriority);

            assertTrue(group1.getMaxPriority() == maxPriority);
            assertTrue(group2.getMaxPriority() == maxPriority);
            assertTrue(group3.getMaxPriority() == maxPriority);

            group2.setMaxPriority(Thread.MIN_PRIORITY);
            assertTrue(group1.getMaxPriority() == maxPriority);
            assertTrue(group2.getMaxPriority() == Thread.MIN_PRIORITY);
            assertTrue(group3.getMaxPriority() == Thread.MIN_PRIORITY);
            group2.setMaxPriority(maxPriority);

            assertTrue(group1.getMaxPriority() == maxPriority);
            assertTrue(group2.getMaxPriority() == maxPriority);
            assertTrue(group3.getMaxPriority() == maxPriority);

            group3.setMaxPriority(Thread.MIN_PRIORITY);
            assertTrue(group1.getMaxPriority() == maxPriority);
            assertTrue(group2.getMaxPriority() == maxPriority);
            assertTrue(group3.getMaxPriority() == Thread.MIN_PRIORITY);
        }
    }

    public void testMaxPriority3() {
        ThreadGroup group = new ThreadGroup("group");
        if (group.getMaxPriority() > Thread.MIN_PRIORITY) {
            int maxPriority = Thread.MIN_PRIORITY + 1;
            group.setMaxPriority(maxPriority);
            assertTrue(group.getMaxPriority() == maxPriority);

            Thread thread = new Thread(group, () -> { });
            int priority = thread.getPriority();

            int expectedPriority = Math.min(Thread.currentThread().getPriority(), maxPriority);
            assertTrue(priority == expectedPriority);

            thread.setPriority(Thread.MAX_PRIORITY);
            assertTrue(thread.getPriority() == maxPriority);

            thread.setPriority(maxPriority);
            assertTrue(thread.getPriority() == maxPriority);

            thread.setPriority(Thread.MIN_PRIORITY);
            assertTrue(thread.getPriority() == Thread.MIN_PRIORITY);

        }
    }

    public void testInterrupt1() {
        ThreadGroup group = new ThreadGroup("group");
        assertTrue(group.activeCount() == 0);
        TestThread thread = TestThread.start(group, "foo");
        try {
            group.interrupt();
        } finally {
            thread.terminate();
        }
        assertTrue(thread.wasInterrupted());
    }

    public void testInterrupt2() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        TestThread thread1 = TestThread.start(group1, "foo");
        TestThread thread2 = TestThread.start(group2, "bar");
        try {
            group1.interrupt();
        } finally {
            thread1.terminate();
            thread2.terminate();
        }
        assertTrue(thread2.wasInterrupted());
        assertTrue(thread1.wasInterrupted());
    }

    public void testInterrupt3() {
        ThreadGroup group1 = new ThreadGroup("group1");
        ThreadGroup group2 = new ThreadGroup(group1, "group2");
        TestThread thread1 = TestThread.start(group1, "foo");
        TestThread thread2 = TestThread.start(group2, "bar");
        try {
            group2.interrupt();
        } finally {
            thread1.terminate();
            thread2.terminate();
        }
        assertTrue(thread2.wasInterrupted());
        assertFalse(thread1.wasInterrupted());
    }

    public void testDestroy() {
        ThreadGroup group = new ThreadGroup("group");
        group.destroy();
        assertFalse(group.isDestroyed());
    }

    public void testDaemon() {
        ThreadGroup group = new ThreadGroup("group");
        assertFalse(group.isDaemon());
        group.setDaemon(false);
        assertFalse(group.isDaemon());
        group.setDaemon(true);
        assertFalse(group.isDaemon());
    }

    public void testList() {
        ThreadGroup group = new ThreadGroup("foo");
        group.list(); // does nothing
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testSuspend() {
        ThreadGroup group = new ThreadGroup("foo");
        group.suspend();
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testResume() {
        ThreadGroup group = new ThreadGroup("foo");
        group.resume();
    }

    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testStop() {
        ThreadGroup group = new ThreadGroup("foo");
        group.stop();
    }
    
    @Test(expectedExceptions = { UnsupportedOperationException.class })
    public void testAllowThreadSuspension() {
        ThreadGroup group = new ThreadGroup("foo");
        group.allowThreadSuspension(true);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        new ThreadGroup(null, "group");
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        ThreadGroup group = new ThreadGroup("group");
        group.enumerate((Thread[])null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() {
        ThreadGroup group = new ThreadGroup("group");
        group.enumerate((Thread[])null, false);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() {
        ThreadGroup group = new ThreadGroup("group");
        group.enumerate((ThreadGroup[])null);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull5() {
        ThreadGroup group = new ThreadGroup("group");
        group.enumerate((ThreadGroup[])null, false);
    }

    private <T> Set<T> toSet(T[] array, int len) {
        return Arrays.stream(array, 0, len).collect(Collectors.toSet());
    }

    static class TestThread extends Thread {
        TestThread(ThreadGroup group, String name) {
            super(group, name);
        }

        static TestThread start(ThreadGroup group, String name) {
            TestThread thread = new TestThread(group, name);
            thread.start();
            return thread;
        }

        private volatile boolean done;
        private volatile boolean interrupted;

        public void run() {
            if (Thread.currentThread() != this)
                throw new IllegalCallerException();
            while (!done) {
                LockSupport.park();
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (Thread.interrupted()) {
                interrupted = true;
            }
        }

        boolean wasInterrupted() {
            return interrupted;
        }

        void terminate() {
            done = true;
            LockSupport.unpark(this);
            boolean interrupted = false;
            while (isAlive()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

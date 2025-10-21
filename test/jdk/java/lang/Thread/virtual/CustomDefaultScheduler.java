/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test using a custom scheduler as the default virtual thread scheduler
 * @requires vm.continuations
 * @run junit/othervm -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler1
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 * @run junit/othervm -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler2
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 */

import java.lang.Thread.VirtualThreadScheduler;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class CustomDefaultScheduler {
    private static String schedulerClassName;

    @BeforeAll
    static void setup() {
        schedulerClassName = System.getProperty("jdk.virtualThreadScheduler.implClass");
    }

    /**
     * Custom scheduler that uses a thread pool.
     */
    public static class CustomScheduler1 implements VirtualThreadScheduler {
        private final ExecutorService pool;

        public CustomScheduler1() {
            ThreadFactory factory = Thread.ofPlatform().daemon().factory();
            pool = Executors.newFixedThreadPool(1, factory);
        }

        void execute(Thread thread, Runnable task) {
            if (thread.isVirtual()) {
                pool.execute(task);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @Override
        public void onStart(Thread thread, Runnable task) {
            execute(thread, task);
        }

        @Override
        public void onContinue(Thread thread, Runnable task) {
            execute(thread, task);
        }
    }

    /**
     * Custom scheduler that delegates to the built-in default scheduler.
     */
    public static class CustomScheduler2 implements VirtualThreadScheduler {
        private final VirtualThreadScheduler builtinScheduler;

        // the set of threads that executed with this scheduler
        private final Set<Thread> executed = ConcurrentHashMap.newKeySet();

        public CustomScheduler2(VirtualThreadScheduler builtinScheduler) {
            this.builtinScheduler = builtinScheduler;
        }

        VirtualThreadScheduler builtinScheduler() {
            return builtinScheduler;
        }

        @Override
        public void onStart(Thread vthread, Runnable task) {
            executed.add(vthread);
            builtinScheduler.onStart(vthread, task);
        }

        @Override
        public void onContinue(Thread vthread, Runnable task) {
            executed.add(vthread);
            builtinScheduler.onContinue(vthread, task);
        }

        Set<Thread> threadsExecuted() {
            return executed;
        }
    }

    /**
     * Test that a virtual thread uses the custom default scheduler.
     */
    @Test
    void testUseCustomScheduler() throws Exception {
        var ref = new AtomicReference<VirtualThreadScheduler>();
        Thread.startVirtualThread(() -> {
            ref.set(VirtualThreadScheduler.current());
        }).join();
        VirtualThreadScheduler scheduler = ref.get();
        assertEquals(schedulerClassName, scheduler.getClass().getName());
    }

    /**
     * Test virtual thread park/unpark using custom default scheduler.
     */
    @Test
    void testPark() throws Exception {
        var done = new AtomicBoolean();
        var thread = Thread.startVirtualThread(() -> {
            while (!done.get()) {
                LockSupport.park();
            }
        });
        try {
            await(thread, Thread.State.WAITING);
        } finally {
            done.set(true);
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test virtual thread blocking on monitor when using custom default scheduler.
     */
    @Test
    void testBlock() throws Exception {
        var ready = new CountDownLatch(1);
        var lock = new Object();
        var thread = Thread.ofVirtual().unstarted(() -> {
            ready.countDown();
            synchronized (lock) {
            }
        });
        synchronized (lock) {
            thread.start();
            ready.await();
            await(thread, Thread.State.BLOCKED);
        }
        thread.join();
    }

    /**
     * Test custom default scheduler execute method with bad parameters.
     */
    @Test
    void testExecuteThrows() throws Exception {
        var ref = new AtomicReference<VirtualThreadScheduler>();
        Thread vthread = Thread.startVirtualThread(() -> {
            ref.set(VirtualThreadScheduler.current());
        });
        vthread.join();
        VirtualThreadScheduler scheduler = ref.get();

        Runnable task = () -> { };

        // platform thread
        Thread thread = Thread.ofPlatform().unstarted(() -> { });
        assertThrows(UnsupportedOperationException.class, () -> scheduler.onContinue(thread, task));

        // nulls
        assertThrows(NullPointerException.class, () -> scheduler.onContinue(null, task));
        assertThrows(NullPointerException.class, () -> scheduler.onContinue(vthread, null));
    }

    /**
     * Test custom default scheduler delegating to builtin default scheduler.
     */
    @Test
    void testDelegatingToBuiltin1() throws Exception {
        assumeTrue(schedulerClassName.equals("CustomDefaultScheduler$CustomScheduler2"));

        var ref = new AtomicReference<VirtualThreadScheduler>();
        Thread vthread = Thread.startVirtualThread(() -> {
            ref.set(VirtualThreadScheduler.current());
        });
        vthread.join();

        var customScheduler1 = new CustomScheduler1();
        var customScheduler2 = (CustomScheduler2) ref.get();
        var builtinScheduler = customScheduler2.builtinScheduler();

        // ensure builtin default scheduler can't be shutdown
        assertThrows(ClassCastException.class, () -> { var e = (AutoCloseable) builtinScheduler; });

        var vthread0 = Thread.ofVirtual().scheduler(builtinScheduler).unstarted(() -> { });
        var vthread1 = Thread.ofVirtual().scheduler(customScheduler1).unstarted(() -> { });
        var vthread2 = Thread.ofVirtual().scheduler(customScheduler2).unstarted(() -> { });

        Runnable task = () -> { };

        // builtin scheduler can execute tasks for itself or customScheduler2
        builtinScheduler.onContinue(vthread0, task);
        assertThrows(IllegalArgumentException.class, () -> builtinScheduler.onContinue(vthread1, task));
        builtinScheduler.onContinue(vthread2, task);

        assertThrows(IllegalArgumentException.class, () -> customScheduler2.onContinue(vthread1, task));
        customScheduler2.onContinue(vthread2, task);
    }

    /**
     * Test one virtual thread starting a second virtual thread when both are scheduled
     * by a custom default scheduler delegating to builtin default scheduler.
     */
    @Test
    void testDelegatingToBuiltin2() throws Exception {
        assumeTrue(schedulerClassName.equals("CustomDefaultScheduler$CustomScheduler2"));

        var schedulerRef = new AtomicReference<VirtualThreadScheduler>();
        var vthreadRef = new AtomicReference<Thread>();

        var vthread1 = Thread.ofVirtual().start(() -> {
            schedulerRef.set(VirtualThreadScheduler.current());
            Thread vthread2 = Thread.ofVirtual().start(() -> {
                assertTrue(VirtualThreadScheduler.current() == schedulerRef.get());
                vthreadRef.set(Thread.currentThread());
            });
            try {
                vthread2.join();
            } catch (InterruptedException e) {
                // fail();
            }
        });

        vthread1.join();
        Thread vthread2 = vthreadRef.get();

        var customScheduler = (CustomScheduler2) schedulerRef.get();
        assertTrue(customScheduler.threadsExecuted().contains(vthread1));
        assertTrue(customScheduler.threadsExecuted().contains(vthread2));
    }

    /**
     * Waits for the given thread to reach a given state.
     */
    private void await(Thread thread, Thread.State expectedState) throws InterruptedException {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.sleep(10);
            state = thread.getState();
        }
    }
}

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

/*
 * @test
 * @summary Test virtual threads with native synchronized and native methods that enter
 *     monitors with JNI MonitorEnter
 * @library /test/lib
 * @run junit NativeSynchronized
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSynchronized {

    @BeforeAll
    static void setup() throws Exception {
        System.loadLibrary("NativeSynchronized");
    }

    /**
     * Test calling through a native synchronized method.
     */
    @Test
    void testNativeSynchronized() throws Exception {
        Object lock = this;
        VThreadRunner.run(() -> {
            synchronizedInvoke(() -> {
                assertTrue(Thread.holdsLock(lock));
            });
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test calling through native synchronized method with contention.
     */
    @Test
    void testNativeSynchronizedWithContention() throws Exception {
        var lock = this;
        var started = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            synchronizedInvoke(() -> {
                assertTrue(Thread.holdsLock(lock));
                entered.set(true);
            });
        });
        try {
            synchronized (lock) {
                vthread.start();

                // wait for thread to start and block
                started.await();
                await(vthread, Thread.State.BLOCKED);
            }
        } finally {
            vthread.join();
        }
        assertTrue(entered.get());
    }

    /**
     * Test parking with native synchronized method on stack.
     */
    @Test
    void testNativeSynchronizedAndParking() throws Exception {
        var lock = this;
        var started = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        var done = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            started.countDown();
            synchronizedInvoke(() -> {
                assertTrue(Thread.holdsLock(lock));
                entered.set(true);
                while (!done.get()) {
                    LockSupport.park();
                }
            });
        });
        try {
            // wait for thread to start and block
            started.await();
            await(vthread, Thread.State.WAITING);
        } finally {
            done.set(true);
            LockSupport.unpark(vthread);
            vthread.join();
        }
        assertTrue(entered.get());
    }

    /**
     * Test calling through a native method that enters a monitor with JNI MonitorEnter.
     */
    @Test
    void testEnteredInNative() throws Exception {
        Object lock = this;
        VThreadRunner.run(() -> {
            invoke(lock, () -> {
                assertTrue(Thread.holdsLock(lock));
            });
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test calling through a native method that enters a monitor with JNI MonitorEnter
     * with contention.
     */
    @Test
    void testEnteredInNativeWithContention() throws Exception {
        var lock = this;
        var started = new CountDownLatch(1);
        var entered = new AtomicBoolean();
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            invoke(lock, () -> {
                assertTrue(Thread.holdsLock(lock));
                entered.set(true);
            });
        });
        try {
            synchronized (lock) {
                vthread.start();

                // wait for thread to start and block
                started.await();
                await(vthread, Thread.State.BLOCKED);
            }
        } finally {
            vthread.join();
        }
        assertTrue(entered.get());
    }

    /**
     * Invokes the given task's run method.
     */
    private native synchronized void synchronizedInvoke(Runnable task);

    /**
     * Invokes the given task's run method while holding the given lock.
     */
    private native void invoke(Object lock, Runnable task);

    private void run(Runnable task) {
        task.run();
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

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

/*
 * @test id=default
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xint
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xint -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp-TieredStopAtLevel1
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:TieredStopAtLevel=1 -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp-noTieredCompilation
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=gc
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.debug == true & vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -XX:+UnlockDiagnosticVMOptions -XX:+FullGCALot -XX:FullGCALotInterval=1000 -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KlassInit {
    static final int MAX_VTHREAD_COUNT = 8 * Runtime.getRuntime().availableProcessors();

    /**
     * Test that threads blocked waiting for klass to be initialized
     * on invokestatic bytecode release the carrier.
     */
    @Test
    void testReleaseAtKlassInitInvokeStatic1() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            static void m() {
            }
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                TestClass.m();
            });
            // Make sure this is the initializer thread
            if (i == 0) {
                started[0].await();
                await(vthreads[0], Thread.State.WAITING);
            }
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        LockSupport.unpark(vthreads[0]);
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    /**
     * Test with static method that takes arguments.
     */
    @Test
    void testReleaseAtKlassInitInvokeStatic2() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            static void m(ArrayList<String> list, int id) {
                String str = list.get(0);
                if (str != null && str.equals("VThread#" + id)) {
                    list.add("Success");
                }
            }
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        ArrayList<String>[] lists = new ArrayList[MAX_VTHREAD_COUNT];
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            lists[i] = new ArrayList<>(List.of("VThread#" + i));
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                TestClass.m(lists[id], id);
            });
            // Make sure this is the initializer thread
            if (i == 0) {
                started[0].await();
                await(vthreads[0], Thread.State.WAITING);
            }
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        System.gc();
        LockSupport.unpark(vthreads[0]);
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
            assertEquals(lists[i].get(1), "Success");
        }
    }

    /**
     * Test invokestatic as first bytecode in method.
     */
    @Test
    void testReleaseAtKlassInitInvokeStatic3() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            static void m() {
            }
        }
        class Driver {
            static void foo() {
                TestClass.m();
            }
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        started[0] = new CountDownLatch(1);
        vthreads[0] = Thread.ofVirtual().start(() -> {
            started[0].countDown();
            TestClass.m();
        });
        started[0].await();
        await(vthreads[0], Thread.State.WAITING);

        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                Driver.foo();
            });
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        LockSupport.unpark(vthreads[0]);
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    /**
     * Test that threads blocked waiting for klass to be initialized
     * on new bytecode release the carrier.
     */
    @Test
    void testReleaseAtKlassInitNew() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            void m() {
            }
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                TestClass x = new TestClass();
                x.m();
            });
            // Make sure this is the initializer thread
            if (i == 0) {
                started[0].await();
                await(vthreads[0], Thread.State.WAITING);
            }
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        LockSupport.unpark(vthreads[0]);
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    /**
     * Test that threads blocked waiting for klass to be initialized
     * on getstatic bytecode release the carrier.
     */
    @Test
    void testReleaseAtKlassInitGetStatic() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            public static int NUMBER = 150;
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        AtomicInteger[] result = new AtomicInteger[MAX_VTHREAD_COUNT];
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            result[i] = new AtomicInteger();
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                result[id].set(TestClass.NUMBER);
            });
            // Make sure this is the initializer thread
            if (i == 0) {
                started[0].await();
                await(vthreads[0], Thread.State.WAITING);
            }
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        LockSupport.unpark(vthreads[0]);
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
            assertEquals(result[i].get(), TestClass.NUMBER);
        }
    }

    /**
     * Test that threads blocked waiting for klass to be initialized
     * on putstatic release the carrier.
     */
    @Test
    void testReleaseAtKlassInitPutStatic() throws Exception {
        class TestClass {
            static {
                LockSupport.park();
            }
            public static int NUMBER;
        }

        Thread[] vthreads = new Thread[MAX_VTHREAD_COUNT];
        CountDownLatch[] started = new CountDownLatch[MAX_VTHREAD_COUNT];
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            final int id = i;
            started[i] = new CountDownLatch(1);
            vthreads[i] = Thread.ofVirtual().start(() -> {
                started[id].countDown();
                TestClass.NUMBER = id;
            });
            // Make sure this is the initializer thread
            if (i == 0) {
                started[0].await();
                await(vthreads[0], Thread.State.WAITING);
            }
        }
        for (int i = 1; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        LockSupport.unpark(vthreads[0]);
        boolean found = false;
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
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

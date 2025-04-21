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
 * @run junit/othervm -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xint
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xint -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp-TieredStopAtLevel1
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:TieredStopAtLevel=1 -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=Xcomp-noTieredCompilation
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

/*
 * @test id=gc
 * @modules java.base/java.lang:+open jdk.management
 * @library /test/lib
 * @requires vm.debug == true & vm.continuations & vm.opt.LockingMode != 1
 * @run junit/othervm -XX:+UnlockDiagnosticVMOptions -XX:+FullGCALot -XX:FullGCALotInterval=1000 -XX:CompileCommand=exclude,KlassInit::lambda$testReleaseAtKlassInit* -XX:CompileCommand=exclude,KlassInit$$Lambda*::run -XX:CompileCommand=exclude,KlassInit$1Driver::foo KlassInit
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

import java.util.stream.Stream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class KlassInit {
    static final int MAX_VTHREAD_COUNT = 8 * Runtime.getRuntime().availableProcessors();
    static CountDownLatch finishInvokeStatic1 = new CountDownLatch(1);
    static CountDownLatch finishInvokeStatic2 = new CountDownLatch(1);
    static CountDownLatch finishInvokeStatic3 = new CountDownLatch(1);
    static CountDownLatch finishNew = new CountDownLatch(1);
    static CountDownLatch finishGetStatic = new CountDownLatch(1);
    static CountDownLatch finishPutStatic = new CountDownLatch(1);

    /**
     * Test that threads blocked waiting for klass to be initialized
     * on invokestatic bytecode release the carrier.
     */
    @Test
    void testReleaseAtKlassInitInvokeStatic1() throws Exception {
        class TestClass {
            static {
                try {
                    finishInvokeStatic1.await();
                } catch(InterruptedException e) {}
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
        }
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        finishInvokeStatic1.countDown();
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
                try {
                    finishInvokeStatic2.await();
                } catch(InterruptedException e) {}
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
        }
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        System.gc();
        finishInvokeStatic2.countDown();
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
                try {
                    finishInvokeStatic3.await();
                } catch(InterruptedException e) {}
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

        finishInvokeStatic3.countDown();
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
                try {
                    finishNew.await();
                } catch(InterruptedException e) {}
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
        }
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        finishNew.countDown();
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
                try {
                    finishGetStatic.await();
                } catch(InterruptedException e) {}
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
        }
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        finishGetStatic.countDown();
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
                try {
                    finishPutStatic.await();
                } catch(InterruptedException e) {}
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
        }
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            started[i].await();
            await(vthreads[i], Thread.State.WAITING);
        }

        finishPutStatic.countDown();
        for (int i = 0; i < MAX_VTHREAD_COUNT; i++) {
            vthreads[i].join();
        }
    }

    /**
     * Test that interruptions during preemption on klass init
     * are preserved.
     */
    @ParameterizedTest
    @MethodSource("interruptTestCases")
    void testReleaseAtKlassInitPreserverInterrupt(int timeout, Runnable m, CountDownLatch finish) throws Exception {
        // Start vthread1 and wait until it blocks in TestClassX initializer
        var vthread1_started = new CountDownLatch(1);
        var vthread1 = Thread.ofVirtual().start(() -> {
                vthread1_started.countDown();
                m.run();
            });
        vthread1_started.await();
        await(vthread1, Thread.State.WAITING);

        // Start vthread2 and wait until it gets preempted on TestClassX initialization
        var lock = new Object();
        var interruptedException = new AtomicBoolean();
        var vthread2_started = new CountDownLatch(1);
        var vthread2 = Thread.ofVirtual().start(() -> {
                vthread2_started.countDown();
                m.run();
                synchronized (lock) {
                    try {
                        if (timeout > 0) {
                            lock.wait(timeout);
                        } else {
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        // check stack trace has the expected frames
                        Set<String> expected = Set.of("wait0", "wait", "run");
                        Set<String> methods = Stream.of(e.getStackTrace())
                                .map(StackTraceElement::getMethodName)
                                .collect(Collectors.toSet());
                        assertTrue(methods.containsAll(expected));
                        interruptedException.set(true);
                    }
                }
            });
        vthread2_started.await();
        await(vthread2, Thread.State.WAITING);

        // Interrupt vthread2 and let initialization of TestClassX finish
        vthread2.interrupt();
        finish.countDown();
        vthread1.join();
        vthread2.join();
        assertTrue(interruptedException.get());
    }

    static CountDownLatch finishInterrupt0 = new CountDownLatch(1);
    class TestClass0 {
        static {
            try {
                finishInterrupt0.await();
            } catch(InterruptedException e) {}
        }
        static void m() {}
    }

    static CountDownLatch finishInterrupt30000 = new CountDownLatch(1);
    class TestClass30000 {
        static {
            try {
                finishInterrupt30000.await();
            } catch(InterruptedException e) {}
        }
        static void m() {}
    }

    static CountDownLatch finishInterruptMax = new CountDownLatch(1);
    class TestClassMax {
        static {
            try {
                finishInterruptMax.await();
            } catch(InterruptedException e) {}
        }
        static void m() {}
    }

    static Stream<Arguments> interruptTestCases() {
        return Stream.of(
            Arguments.of(0, (Runnable) TestClass0::m, finishInterrupt0),
            Arguments.of(30000, (Runnable) TestClass30000::m, finishInterrupt30000),
            Arguments.of(Integer.MAX_VALUE, (Runnable) TestClassMax::m, finishInterruptMax)
        );
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

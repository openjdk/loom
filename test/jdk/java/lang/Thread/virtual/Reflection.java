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
 * @run testng Reflection
 * @summary Test virtual threads using core reflection
 */

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Reflection {
    
    // -- invoke static method --

    public void testInvokeStatic1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            int result = (int) divideMethod().invoke(null, 20, 2);
            assertTrue(result == 10);
        });
    }

    // InvocationTargetException with cause
    public void testInvokeStatic2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try {
                divideMethod().invoke(null, 20, 0);
                assertTrue(false);
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof ArithmeticException);
            }
        });
    }

    // IllegalArgumentException
    public void testInvokeStatic3() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(null));
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(null, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(null, 1, 2, 3));
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(new Object()));
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(new Object(), 1));
            assertThrows(IllegalArgumentException.class,
                    () -> divideMethod().invoke(new Object(), 1, 2, 3));
        });
    }

    // ExceptionInInitializerError
    public void testInvokeStatic4() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Method foo = BadClass1.class.getDeclaredMethod("foo");
            try {
                foo.invoke(null);
                assertTrue(false);
            } catch (ExceptionInInitializerError e) {
                assertTrue(e.getCause() instanceof ArithmeticException);
            }
        });
    }

    static class BadClass1 {
        static {
            if (1==1) throw new ArithmeticException();
        }
        static void foo() { }
    }


    // <clinit> throws Error, specified behavior?
    public void testInvokeStatic5() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Method foo = BadClass2.class.getDeclaredMethod("foo");
            assertThrows(AbstractMethodError.class, () -> foo.invoke(null));
        });
    }

    static class BadClass2 {
        static {
            if (1==1) throw new AbstractMethodError();
        }
        static void foo() { }
    }

    // test that invoke does not pin the carrier thread
    public void testInvokeStatic6() throws Exception {
        Method parkMethod = Parker.class.getDeclaredMethod("park");
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread vthread = Thread.builder().virtual(scheduler).task(() -> {
                try {
                    parkMethod.invoke(null);   // blocks
                } catch (Exception e) { }
            }).start();

            Thread.sleep(100); // give thread time to be scheduled

            // unpark with another virtual thread, runs on same carrier thread
            Thread.builder().virtual(scheduler).task(() -> LockSupport.unpark(vthread));
        }
    }


    // -- invoke instance method --

    public void testInvokeInstance1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var adder = new Adder();
            Adder.addMethod().invoke(adder, 5);
            assertTrue(adder.sum == 5);
        });
    }

    // test exception throw by method
    public void testInvokeInstance2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var adder = new Adder();
            try {
                Adder.addMethod().invoke(adder, -5);
                assertTrue(false);
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IllegalArgumentException);
            }
        });
    }

    // NullPointerException/IllegalArgumentException
    public void testInvokeInstance3() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var adder = new Adder();
            Method addMethod = Adder.addMethod();
            assertThrows(NullPointerException.class,
                    () -> addMethod.invoke(null));
            assertThrows(IllegalArgumentException.class,
                    () -> addMethod.invoke(adder));
            assertThrows(IllegalArgumentException.class,
                    () -> addMethod.invoke(adder, 1, 2));
            assertThrows(IllegalArgumentException.class,
                    () -> addMethod.invoke(adder, 1, "hi"));
            assertThrows(IllegalArgumentException.class,
                    () -> addMethod.invoke(adder, "hi"));
        });
    }


    // -- newInstance to construct objects --

    public void testNewInstance1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Constructor<?> ctor = Adder.class.getDeclaredConstructor(long.class);
            Adder adder = (Adder) ctor.newInstance(10);
            assertTrue(adder.sum == 10);
        });
    }

    // InvocationTargetException with cause
    public void testNewInstance2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Constructor<?> ctor = Adder.class.getDeclaredConstructor(long.class);
            try {
                ctor.newInstance(-10);
                assertTrue(false);
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IllegalArgumentException);
            }
        });
    }

    // IllegalArgumentException
    public void testNewInstance3() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var adder = new Adder();
            Constructor<?> ctor = Adder.class.getDeclaredConstructor(long.class);
            assertThrows(IllegalArgumentException.class,
                    () -> ctor.newInstance((Object[])null));
            assertThrows(IllegalArgumentException.class,
                    () -> ctor.newInstance(adder));
            assertThrows(IllegalArgumentException.class,
                    () -> ctor.newInstance(adder, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ctor.newInstance(adder, "foo"));
            assertThrows(IllegalArgumentException.class,
                    () -> ctor.newInstance(adder, 1, 2));
        });
    }

    // ExceptionInInitializerError
    public void testNewInstance4() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Constructor<?> ctor = BadClass3.class.getDeclaredConstructor();
            try {
                ctor.newInstance((Object[])null);
                assertTrue(false);
            } catch (ExceptionInInitializerError e) {
                assertTrue(e.getCause() instanceof ArithmeticException);
            }
        });
    }

    static class BadClass3 {
        static {
            if (1==1) throw new ArithmeticException();
        }
        static void foo() { }
    }

    // <clinit> throws Error, specified behavior?
    public void testNewInstance5() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Constructor<?> ctor = BadClass4.class.getDeclaredConstructor();
            assertThrows(AbstractMethodError.class, () -> ctor.newInstance((Object[])null));
        });
    }

    static class BadClass4 {
        static {
            if (1==1) throw new AbstractMethodError();
        }
        static void foo() { }
    }

    // test that newInstance does not pin the carrier thread
    public void testNewInstance6() throws Exception {
        Constructor<?> ctor = Parker.class.getDeclaredConstructor();
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread vthread = Thread.builder().virtual(scheduler).task(() -> {
                try {
                    ctor.newInstance();
                } catch (Exception e) { }
            }).start();

            Thread.sleep(100); // give thread time to be scheduled

            // unpark with another virtual thread, runs on same carrier thread
            Thread.builder().virtual(scheduler).task(() -> LockSupport.unpark(vthread));
        }
    }


    // -- support classes and methods --

    static int divide(int x, int y) {
        return x / y;
    }

    static Method divideMethod() throws NoSuchMethodException {
        return Reflection.class.getDeclaredMethod("divide", int.class, int.class);
    }

    static class Adder {
        long sum;
        Adder() { }
        Adder(long x) {
            if (x < 0)
                throw new IllegalArgumentException();
            sum = x;
        }
        Adder add(long x) {
            if (x < 0)
                throw new IllegalArgumentException();
            sum += x;
            return this;
        }
        static Method addMethod() throws NoSuchMethodException {
            return Adder.class.getDeclaredMethod("add", long.class);
        }
        long sum() {
            return sum;
        }
    }

    static class Parker {
        Parker() {
            LockSupport.park();
        }
        static void park() {
            LockSupport.park();
        }
    }

}
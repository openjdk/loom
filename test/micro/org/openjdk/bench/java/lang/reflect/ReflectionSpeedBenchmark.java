/*
 * Copyright (c) 2014, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.lang.reflect;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark measuring field access and method invocation using different conditions:
 * <ul>
 *     <li>Const - Constructor/Method/Field is constant-foldable</li>
 *     <li>Var - Constructor/Method/Field is single-instance but not constant-foldable</li>
 *     <li>Poly - multiple Constructor/Method/Field instances used at single call-site</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 10, time = 1, batchSize = 10)
@Measurement(iterations = 10, time = 1, batchSize = 10)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(value = 1, warmups = 0)
public class ReflectionSpeedBenchmark {

    static final Method staticMethodConst;
    static final Method instanceMethodConst;
    static final Field staticFieldConst;
    static final Field instanceFieldConst;
    static final Constructor<?> constructorConst;
    static final Object[] constructorArgs;

    static Method staticMethodVar;
    static Method instanceMethodVar;
    static Field staticFieldVar;
    static Field instanceFieldVar;
    static Constructor<?> constructorVar;

    static Method[] staticMethodsPoly;
    static Method[] instanceMethodsPoly;
    static Field[] staticFieldsPoly;
    static Field[] instanceFieldsPoly;
    static Constructor<?>[] constructorsPoly;
    static Object[][] constructorsArgsPoly;

    static {
        try {
            staticMethodConst = staticMethodVar = ReflectionSpeedBenchmark.class.getDeclaredMethod("sumStatic", Integer.class, Integer.class);
            instanceMethodConst = instanceMethodVar = ReflectionSpeedBenchmark.class.getDeclaredMethod("sumInstance", Integer.class, Integer.class);

            staticFieldConst = staticFieldVar = ReflectionSpeedBenchmark.class.getDeclaredField("staticField");
            instanceFieldConst = instanceFieldVar = ReflectionSpeedBenchmark.class.getDeclaredField("instanceField");

            constructorConst = constructorVar = NestedConstruction.class.getDeclaredConstructor();
            constructorArgs = new Object[0];

            staticMethodsPoly = NestedStatic.class.getDeclaredMethods();
            staticFieldsPoly = NestedStatic.class.getDeclaredFields();
            instanceMethodsPoly = NestedInstance.class.getDeclaredMethods();
            instanceFieldsPoly = NestedInstance.class.getDeclaredFields();

            constructorsPoly = NestedConstruction.class.getDeclaredConstructors();
            constructorsArgsPoly = new Object[constructorsPoly.length][];
            for (int i = 0; i < constructorsPoly.length; i++) {
                constructorsArgsPoly[i] = new Object[constructorsPoly[i].getParameterCount()];
            }
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(e.getMessage());
        } catch (NoSuchFieldException e) {
            throw new NoSuchFieldError(e.getMessage());
        }
    }

    public static class NestedStatic {
        // # of fields must be 2^N
        public static Object
            f00, f01, f02, f03, f04, f05, f06, f07, f08, f09, f0A, f0B, f0C, f0D, f0E, f0F,
            f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f1A, f1B, f1C, f1D, f1E, f1F;

        // # of methods must be 2^N
        public static Object m00(Object p) {return p;}

        public static Object m01(Object p) {return p;}

        public static Object m02(Object p) {return p;}

        public static Object m03(Object p) {return p;}

        public static Object m04(Object p) {return p;}

        public static Object m05(Object p) {return p;}

        public static Object m06(Object p) {return p;}

        public static Object m07(Object p) {return p;}

        public static Object m08(Object p) {return p;}

        public static Object m09(Object p) {return p;}

        public static Object m0A(Object p) {return p;}

        public static Object m0B(Object p) {return p;}

        public static Object m0C(Object p) {return p;}

        public static Object m0D(Object p) {return p;}

        public static Object m0E(Object p) {return p;}

        public static Object m0F(Object p) {return p;}

        public static Object m10(Object p) {return p;}

        public static Object m11(Object p) {return p;}

        public static Object m12(Object p) {return p;}

        public static Object m13(Object p) {return p;}

        public static Object m14(Object p) {return p;}

        public static Object m15(Object p) {return p;}

        public static Object m16(Object p) {return p;}

        public static Object m17(Object p) {return p;}

        public static Object m18(Object p) {return p;}

        public static Object m19(Object p) {return p;}

        public static Object m1A(Object p) {return p;}

        public static Object m1B(Object p) {return p;}

        public static Object m1C(Object p) {return p;}

        public static Object m1D(Object p) {return p;}

        public static Object m1E(Object p) {return p;}

        public static Object m1F(Object p) {return p;}
    }

    public static class NestedInstance {
        // # of fields must be 2^N
        public Object
            f00, f01, f02, f03, f04, f05, f06, f07, f08, f09, f0A, f0B, f0C, f0D, f0E, f0F,
            f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f1A, f1B, f1C, f1D, f1E, f1F;

        // # of methods must be 2^N
        public Object m00(Object p) {return p;}

        public Object m01(Object p) {return p;}

        public Object m02(Object p) {return p;}

        public Object m03(Object p) {return p;}

        public Object m04(Object p) {return p;}

        public Object m05(Object p) {return p;}

        public Object m06(Object p) {return p;}

        public Object m07(Object p) {return p;}

        public Object m08(Object p) {return p;}

        public Object m09(Object p) {return p;}

        public Object m0A(Object p) {return p;}

        public Object m0B(Object p) {return p;}

        public Object m0C(Object p) {return p;}

        public Object m0D(Object p) {return p;}

        public Object m0E(Object p) {return p;}

        public Object m0F(Object p) {return p;}

        public Object m10(Object p) {return p;}

        public Object m11(Object p) {return p;}

        public Object m12(Object p) {return p;}

        public Object m13(Object p) {return p;}

        public Object m14(Object p) {return p;}

        public Object m15(Object p) {return p;}

        public Object m16(Object p) {return p;}

        public Object m17(Object p) {return p;}

        public Object m18(Object p) {return p;}

        public Object m19(Object p) {return p;}

        public Object m1A(Object p) {return p;}

        public Object m1B(Object p) {return p;}

        public Object m1C(Object p) {return p;}

        public Object m1D(Object p) {return p;}

        public Object m1E(Object p) {return p;}

        public Object m1F(Object p) {return p;}
    }

    public static class NestedConstruction {
        // # of constructors must be 2^N
        public NestedConstruction() {}

        public NestedConstruction(Void p1) {}

        public NestedConstruction(Void p1, Void p2) {}

        public NestedConstruction(Void p1, Void p2, Void p3) {}

        public NestedConstruction(Void p1, Void p2, Void p3, Void p4) {}

        public NestedConstruction(Void p1, Void p2, Void p3, Void p4, Void p5) {}

        public NestedConstruction(Void p1, Void p2, Void p3, Void p4, Void p5, Void p6) {}

        public NestedConstruction(Void p1, Void p2, Void p3, Void p4, Void p5, Void p6, Void p7) {}
    }

    private int rnd = 0;
    private Integer a, b;
    private Object o;
    private NestedInstance instance;

    private int nextRnd() {
        return rnd += 7;
    }

    // @Param({"true", "false"})
    private boolean polluteProfile = true;

    @Setup(Level.Iteration)
    public void setup() {
        a = nextRnd();
        b = nextRnd();
        o = new Object();
        instance = new NestedInstance();

        if (polluteProfile) {
            try {
                Constructor ctor = ReflectionSpeedBenchmark.class.getDeclaredConstructor(Integer.class);
                Method test1 = ReflectionSpeedBenchmark.class.getDeclaredMethod("test1", Object.class);
                Method test2 = ReflectionSpeedBenchmark.class.getDeclaredMethod("test2", Object.class, Object.class);
                Field f = ReflectionSpeedBenchmark.class.getDeclaredField("testField");
                for (int i = 0; i < 20_000; i++) {
                    invokeHelper2(staticMethodVar, null, a, b);
                    invokeHelper2(instanceMethodVar, this, a, b);
                    invokeHelper2(test2, null, a, b);
                    invokeHelper1(staticMethodsPoly[i & (staticMethodsPoly.length - 1)], instance, o);
                    invokeHelper1(instanceMethodsPoly[i & (instanceMethodsPoly.length - 1)], instance, o);
                    invokeHelper1(test1, null, a);

                    newInstanceHelper(constructorVar, constructorArgs);
                    int index = i & (constructorsPoly.length - 1);
                    newInstanceHelper(constructorsPoly[index], constructorsArgsPoly[index]);
                    newInstanceHelper(ctor, new Object[]{a});

                    getIntHelper(staticFieldVar, null);
                    getIntHelper(instanceFieldVar, this);
                    getDoubleHelper(f, null);
                    getHelper(staticFieldsPoly[i & (staticFieldsPoly.length - 1)], null);
                    getHelper(instanceFieldsPoly[i & (instanceFieldsPoly.length - 1)], instance);
                }
            } catch (ReflectiveOperationException e) {
                 throw new InternalError(e);
            }
        }
    }

    public static Integer sumStatic(Integer a, Integer b) {
        return a; // a + b;
    }

    public Integer sumInstance(Integer a, Integer b) {
        return a; // a + b;
    }

    public static int staticField;
    public int instanceField;
    public ReflectionSpeedBenchmark() {}

    // used for polluting the profile
    private ReflectionSpeedBenchmark(Integer a) {}
    static void test1(Object a) {}
    static void test2(Object a, Object b) {}
    static double testField;

    // methods
    @CompilerControl(CompilerControl.Mode.INLINE)
    static Object invokeHelper1(Method m, Object recv, Object arg1) throws InvocationTargetException, IllegalAccessException {
        return m.invoke(recv, arg1);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    static Object invokeHelper2(Method m, Object recv, Object arg1, Object arg2) throws InvocationTargetException, IllegalAccessException {
        return m.invoke(recv, arg1, arg2);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    static Object newInstanceHelper(Constructor ctor, Object[] args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        return ctor.newInstance(args);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    static int getIntHelper(Field f, Object recv) throws IllegalAccessException {
        return f.getInt(recv);
    }
    @CompilerControl(CompilerControl.Mode.INLINE)
    static double getDoubleHelper(Field f, Object recv) throws IllegalAccessException {
        return f.getDouble(recv);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    static Object getHelper(Field f, Object recv) throws IllegalAccessException {
        return f.get(recv);
    }

    @Benchmark
    public Object staticMethodConst() {
        try {
            return invokeHelper2(staticMethodConst, null, a, b);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object instanceMethodConst() {
        try {
            return invokeHelper2(instanceMethodConst, this, a, b);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object staticMethodVar() {
        try {
            return invokeHelper2(staticMethodVar, null, a, b);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object instanceMethodVar() {
        try {
            return invokeHelper2(instanceMethodVar, this, a, b);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object staticMethodPoly() {
        try {
            return invokeHelper1(staticMethodsPoly[nextRnd() & (staticMethodsPoly.length - 1)], null, o);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object instanceMethodPoly() {
        try {
            return invokeHelper1(instanceMethodsPoly[nextRnd() & (instanceMethodsPoly.length - 1)], instance, o);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    // fields

    @Benchmark
    public int staticFieldConst() {
        try {
            return getIntHelper(staticFieldConst, null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public int instanceFieldConst() {
        try {
            return getIntHelper(instanceFieldConst, this);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public int staticFieldVar() {
        try {
            return getIntHelper(staticFieldVar, null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public int instanceFieldVar() {
        try {
            return getIntHelper(instanceFieldVar, this);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object staticFieldPoly() {
        try {
            return getHelper(staticFieldsPoly[nextRnd() & (staticFieldsPoly.length - 1)], null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object instanceFieldPoly() {
        try {
            return getHelper(instanceFieldsPoly[nextRnd() & (instanceFieldsPoly.length - 1)], instance);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    // constructors

    @Benchmark
    public Object constructorConst() {
        try {
            return newInstanceHelper(constructorConst, constructorArgs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object constructorVar() {
        try {
            return newInstanceHelper(constructorVar, constructorArgs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Benchmark
    public Object constructorPoly() {
        try {
            int i = nextRnd() & (constructorsPoly.length - 1);
            return newInstanceHelper(constructorsPoly[i], constructorsArgsPoly[i]);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

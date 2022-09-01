/*
 *  Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * @test
 * @enablePreview
 * @library ../
 * @requires ((os.arch == "amd64" | os.arch == "x86_64") & sun.arch.data.model == "64") | os.arch == "aarch64"
 * @modules java.base/jdk.internal.foreign
 *          java.base/jdk.internal.foreign.abi
 *          java.base/jdk.internal.foreign.abi.x64
 *          java.base/jdk.internal.foreign.abi.x64.sysv
 *          java.base/jdk.internal.foreign.abi.x64.windows
 *          java.base/jdk.internal.foreign.abi.aarch64
 *          java.base/jdk.internal.foreign.abi.aarch64.linux
 *          java.base/jdk.internal.foreign.abi.aarch64.macos
 *          java.base/jdk.internal.foreign.abi.aarch64.windows
 * @run testng/othervm --enable-native-access=ALL-UNNAMED VaListTest
 */

import java.lang.foreign.*;
import java.lang.foreign.VaList;
import jdk.internal.foreign.abi.aarch64.linux.LinuxAArch64Linker;
import jdk.internal.foreign.abi.aarch64.macos.MacOsAArch64Linker;
import jdk.internal.foreign.abi.x64.sysv.SysVx64Linker;
import jdk.internal.foreign.abi.x64.windows.Windowsx64Linker;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static jdk.internal.foreign.PlatformLayouts.*;
import static org.testng.Assert.*;

public class VaListTest extends NativeTestHelper {

    private static final Linker abi = Linker.nativeLinker();
    static {
        System.loadLibrary("VaList");
    }

    private static final MethodHandle ADDRESS_TO_VALIST;

    static {
        try {
            ADDRESS_TO_VALIST = MethodHandles.lookup().findStatic(VaList.class, "ofAddress", MethodType.methodType(VaList.class, MemoryAddress.class, MemorySession.class));
        } catch (Throwable ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }


    private static final MethodHandle MH_sumInts = linkVaList("sumInts",
            FunctionDescriptor.of(C_INT, C_INT, C_POINTER));
    private static final MethodHandle MH_sumDoubles = linkVaList("sumDoubles",
            FunctionDescriptor.of(C_DOUBLE, C_INT, C_POINTER));
    private static final MethodHandle MH_getInt = linkVaList("getInt",
            FunctionDescriptor.of(C_INT, C_POINTER));
    private static final MethodHandle MH_sumStruct = linkVaList("sumStruct",
            FunctionDescriptor.of(C_INT, C_POINTER));
    private static final MethodHandle MH_sumBigStruct = linkVaList("sumBigStruct",
            FunctionDescriptor.of(C_LONG_LONG, C_POINTER));
    private static final MethodHandle MH_sumHugeStruct = linkVaList("sumHugeStruct",
            FunctionDescriptor.of(C_LONG_LONG, C_POINTER));
    private static final MethodHandle MH_sumFloatStruct = linkVaList("sumFloatStruct",
            FunctionDescriptor.of(C_FLOAT, C_POINTER));
    private static final MethodHandle MH_sumStack = linkVaList("sumStack",
            FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, C_POINTER));

    private static MethodHandle link(String symbol, FunctionDescriptor fd) {
        return linkInternal(symbol, fd);
    }

    private static MethodHandle linkVaList(String symbol, FunctionDescriptor fd) {
        return linkInternal(symbol, fd);
    }


    private static MethodHandle linkInternal(String symbol, FunctionDescriptor fd) {
        return abi.downcallHandle(findNativeOrThrow(symbol), fd);
    }

    private static MethodHandle linkVaListCB(String symbol) {
        return link(symbol,
                FunctionDescriptor.ofVoid(C_POINTER));

    }

    private static final Function<Consumer<VaList.Builder>, VaList> winVaListFactory
            = actions -> Windowsx64Linker.newVaList(actions, MemorySession.openImplicit());
    private static final Function<Consumer<VaList.Builder>, VaList> sysvVaListFactory
            = actions -> SysVx64Linker.newVaList(actions, MemorySession.openImplicit());
    private static final Function<Consumer<VaList.Builder>, VaList> linuxAArch64VaListFactory
            = actions -> LinuxAArch64Linker.newVaList(actions, MemorySession.openImplicit());
    private static final Function<Consumer<VaList.Builder>, VaList> macAArch64VaListFactory
            = actions -> MacOsAArch64Linker.newVaList(actions, MemorySession.openImplicit());
    private static final Function<Consumer<VaList.Builder>, VaList> platformVaListFactory
            = (builder) -> VaList.make(builder, MemorySession.openConfined());

    private static final BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> winVaListScopedFactory
            = Windowsx64Linker::newVaList;
    private static final BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> sysvVaListScopedFactory
            = SysVx64Linker::newVaList;
    private static final BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> linuxAArch64VaListScopedFactory
            = LinuxAArch64Linker::newVaList;
    private static final BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> macAArch64VaListScopedFactory
            = MacOsAArch64Linker::newVaList;
    private static final BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> platformVaListScopedFactory
            = VaList::make;

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] sumInts() {
        Function<ValueLayout.OfInt, BiFunction<Integer, VaList, Integer>> sumIntsJavaFact = layout ->
                (num, list) -> IntStream.generate(() -> list.nextVarg(layout)).limit(num).sum();
        BiFunction<Integer, VaList, Integer> sumIntsNative
                = MethodHandleProxies.asInterfaceInstance(BiFunction.class, MH_sumInts);
        return new Object[][]{
                { winVaListFactory,          sumIntsJavaFact.apply(Win64.C_INT),   Win64.C_INT   },
                { sysvVaListFactory,         sumIntsJavaFact.apply(SysV.C_INT),    SysV.C_INT    },
                { linuxAArch64VaListFactory, sumIntsJavaFact.apply(AArch64.C_INT), AArch64.C_INT },
                { macAArch64VaListFactory,   sumIntsJavaFact.apply(AArch64.C_INT), AArch64.C_INT },
                { platformVaListFactory,     sumIntsNative,                        C_INT         },
        };
    }

    @Test(dataProvider = "sumInts")
    public void testIntSum(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                           BiFunction<Integer, VaList, Integer> sumInts,
                           ValueLayout.OfInt intLayout) {
        VaList vaList = vaListFactory.apply(b ->
            b.addVarg(intLayout, 10)
                    .addVarg(intLayout, 15)
                    .addVarg(intLayout, 20));
        int x = sumInts.apply(3, vaList);
        assertEquals(x, 45);
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] sumDoubles() {
        Function<ValueLayout.OfDouble, BiFunction<Integer, VaList, Double>> sumDoublesJavaFact  = layout ->
                (num, list) -> DoubleStream.generate(() -> list.nextVarg(layout)).limit(num).sum();
        BiFunction<Integer, VaList, Double> sumDoublesNative
                = MethodHandleProxies.asInterfaceInstance(BiFunction.class, MH_sumDoubles);
        return new Object[][]{
                { winVaListFactory,          sumDoublesJavaFact.apply(Win64.C_DOUBLE),   Win64.C_DOUBLE   },
                { sysvVaListFactory,         sumDoublesJavaFact.apply(SysV.C_DOUBLE),    SysV.C_DOUBLE    },
                { linuxAArch64VaListFactory, sumDoublesJavaFact.apply(AArch64.C_DOUBLE), AArch64.C_DOUBLE },
                { macAArch64VaListFactory,   sumDoublesJavaFact.apply(AArch64.C_DOUBLE), AArch64.C_DOUBLE },
                { platformVaListFactory,     sumDoublesNative,                           C_DOUBLE         },
        };
    }

    @Test(dataProvider = "sumDoubles")
    public void testDoubleSum(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                              BiFunction<Integer, VaList, Double> sumDoubles,
                              ValueLayout.OfDouble doubleLayout) {
        VaList vaList = vaListFactory.apply(b ->
            b.addVarg(doubleLayout, 3.0D)
                    .addVarg(doubleLayout, 4.0D)
                    .addVarg(doubleLayout, 5.0D));
        double x = sumDoubles.apply(3, vaList);
        assertEquals(x, 12.0D);
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] pointers() {
        Function<ValueLayout.OfAddress, Function<VaList, Integer>> getIntJavaFact = layout ->
                list -> {
                    MemoryAddress ma = list.nextVarg(layout);
                    return ma.get(JAVA_INT, 0);
                };
        Function<VaList, Integer> getIntNative = MethodHandleProxies.asInterfaceInstance(Function.class, MH_getInt);
        return new Object[][]{
                { winVaListFactory,          getIntJavaFact.apply(Win64.C_POINTER),   Win64.C_POINTER   },
                { sysvVaListFactory,         getIntJavaFact.apply(SysV.C_POINTER),    SysV.C_POINTER    },
                { linuxAArch64VaListFactory, getIntJavaFact.apply(AArch64.C_POINTER), AArch64.C_POINTER },
                { macAArch64VaListFactory,   getIntJavaFact.apply(AArch64.C_POINTER), AArch64.C_POINTER },
                { platformVaListFactory,     getIntNative,                            C_POINTER         },
        };
    }

    @Test(dataProvider = "pointers")
    public void testVaListMemoryAddress(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                                        Function<VaList, Integer> getFromPointer,
                                        ValueLayout.OfAddress pointerLayout) {
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment msInt = MemorySegment.allocateNative(JAVA_INT, session);
            msInt.set(JAVA_INT, 0, 10);
            VaList vaList = vaListFactory.apply(b -> b.addVarg(pointerLayout, msInt.address()));
            int x = getFromPointer.apply(vaList);
            assertEquals(x, 10);
        }
    }

    interface TriFunction<S, T, U, R> {
        R apply(S s, T t, U u);
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] structs() {
        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Integer>> sumStructJavaFact
                = (pointLayout, VH_Point_x, VH_Point_y) ->
                list -> {
                    MemorySegment struct = MemorySegment.allocateNative(pointLayout, MemorySession.openImplicit());
                    list.nextVarg(pointLayout, SegmentAllocator.prefixAllocator(struct));
                    int x = (int) VH_Point_x.get(struct);
                    int y = (int) VH_Point_y.get(struct);
                    return x + y;
                };

        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Integer>> sumStructNativeFact
                = (pointLayout, VH_Point_x, VH_Point_y) ->
                MethodHandleProxies.asInterfaceInstance(Function.class, MH_sumStruct);

        TriFunction<Function<Consumer<VaList.Builder>, VaList>, MemoryLayout,
                TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Integer>>, Object[]> argsFact
                = (vaListFact, intLayout, sumStructFact) -> {
            GroupLayout pointLayout =  MemoryLayout.structLayout(
                    intLayout.withName("x"),
                    intLayout.withName("y")
            );
            VarHandle VH_Point_x = pointLayout.varHandle(groupElement("x"));
            VarHandle VH_Point_y = pointLayout.varHandle(groupElement("y"));
            return new Object[] { vaListFact, sumStructFact.apply(pointLayout, VH_Point_x, VH_Point_y),
                    pointLayout, VH_Point_x, VH_Point_y  };
        };
        return new Object[][]{
                argsFact.apply(winVaListFactory,          Win64.C_INT,   sumStructJavaFact),
                argsFact.apply(sysvVaListFactory,         SysV.C_INT,    sumStructJavaFact),
                argsFact.apply(linuxAArch64VaListFactory, AArch64.C_INT, sumStructJavaFact),
                argsFact.apply(macAArch64VaListFactory,   AArch64.C_INT, sumStructJavaFact),
                argsFact.apply(platformVaListFactory,     C_INT,         sumStructNativeFact),
        };
    }

    @Test(dataProvider = "structs")
    public void testStruct(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                           Function<VaList, Integer> sumStruct,
                           GroupLayout Point_LAYOUT, VarHandle VH_Point_x, VarHandle VH_Point_y) {
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment struct = MemorySegment.allocateNative(Point_LAYOUT, session);
            VH_Point_x.set(struct, 5);
            VH_Point_y.set(struct, 10);

            VaList vaList = vaListFactory.apply(b -> b.addVarg(Point_LAYOUT, struct));
            int sum = sumStruct.apply(vaList);
            assertEquals(sum, 15);
        }
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] bigStructs() {
        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Long>> sumStructJavaFact
                = (BigPoint_LAYOUT, VH_BigPoint_x, VH_BigPoint_y) ->
                list -> {
                    MemorySegment struct = MemorySegment.allocateNative(BigPoint_LAYOUT, MemorySession.openImplicit());
                    list.nextVarg(BigPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    long x = (long) VH_BigPoint_x.get(struct);
                    long y = (long) VH_BigPoint_y.get(struct);
                    return x + y;
                };

        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Long>> sumStructNativeFact
                = (pointLayout, VH_BigPoint_x, VH_BigPoint_y) ->
                MethodHandleProxies.asInterfaceInstance(Function.class, MH_sumBigStruct);

        TriFunction<Function<Consumer<VaList.Builder>, VaList>, MemoryLayout,
                TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Long>>, Object[]> argsFact
                = (vaListFact, longLongLayout, sumBigStructFact) -> {
            GroupLayout BigPoint_LAYOUT =  MemoryLayout.structLayout(
                    longLongLayout.withName("x"),
                    longLongLayout.withName("y")
            );
            VarHandle VH_BigPoint_x = BigPoint_LAYOUT.varHandle(groupElement("x"));
            VarHandle VH_BigPoint_y = BigPoint_LAYOUT.varHandle(groupElement("y"));
            return new Object[] { vaListFact, sumBigStructFact.apply(BigPoint_LAYOUT, VH_BigPoint_x, VH_BigPoint_y),
                    BigPoint_LAYOUT, VH_BigPoint_x, VH_BigPoint_y  };
        };
        return new Object[][]{
                argsFact.apply(winVaListFactory,          Win64.C_LONG_LONG,   sumStructJavaFact),
                argsFact.apply(sysvVaListFactory,         SysV.C_LONG_LONG,    sumStructJavaFact),
                argsFact.apply(linuxAArch64VaListFactory, AArch64.C_LONG_LONG, sumStructJavaFact),
                argsFact.apply(macAArch64VaListFactory,   AArch64.C_LONG_LONG, sumStructJavaFact),
                argsFact.apply(platformVaListFactory,     C_LONG_LONG,         sumStructNativeFact),
        };
    }

    @Test(dataProvider = "bigStructs")
    public void testBigStruct(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                              Function<VaList, Long> sumBigStruct,
                              GroupLayout BigPoint_LAYOUT, VarHandle VH_BigPoint_x, VarHandle VH_BigPoint_y) {
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment struct = MemorySegment.allocateNative(BigPoint_LAYOUT, session);
            VH_BigPoint_x.set(struct, 5);
            VH_BigPoint_y.set(struct, 10);

            VaList vaList = vaListFactory.apply(b -> b.addVarg(BigPoint_LAYOUT, struct));
            long sum = sumBigStruct.apply(vaList);
            assertEquals(sum, 15);
        }
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] floatStructs() {
        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Float>> sumStructJavaFact
                = (FloatPoint_LAYOUT, VH_FloatPoint_x, VH_FloatPoint_y) ->
                list -> {
                    MemorySegment struct = MemorySegment.allocateNative(FloatPoint_LAYOUT, MemorySession.openImplicit());
                    list.nextVarg(FloatPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    float x = (float) VH_FloatPoint_x.get(struct);
                    float y = (float) VH_FloatPoint_y.get(struct);
                    return x + y;
                };

        TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Float>> sumStructNativeFact
                = (pointLayout, VH_FloatPoint_x, VH_FloatPoint_y) ->
                MethodHandleProxies.asInterfaceInstance(Function.class, MH_sumFloatStruct);

        TriFunction<Function<Consumer<VaList.Builder>, VaList>, MemoryLayout,
                TriFunction<GroupLayout, VarHandle, VarHandle, Function<VaList, Float>>, Object[]> argsFact
                = (vaListFact, floatLayout, sumFloatStructFact) -> {
            GroupLayout FloatPoint_LAYOUT = MemoryLayout.structLayout(
                    floatLayout.withName("x"),
                    floatLayout.withName("y")
            );
            VarHandle VH_FloatPoint_x = FloatPoint_LAYOUT.varHandle(groupElement("x"));
            VarHandle VH_FloatPoint_y = FloatPoint_LAYOUT.varHandle(groupElement("y"));
            return new Object[] { vaListFact, sumFloatStructFact.apply(FloatPoint_LAYOUT, VH_FloatPoint_x, VH_FloatPoint_y),
                    FloatPoint_LAYOUT, VH_FloatPoint_x, VH_FloatPoint_y  };
        };
        return new Object[][]{
                argsFact.apply(winVaListFactory,          Win64.C_FLOAT,   sumStructJavaFact),
                argsFact.apply(sysvVaListFactory,         SysV.C_FLOAT,    sumStructJavaFact),
                argsFact.apply(linuxAArch64VaListFactory, AArch64.C_FLOAT, sumStructJavaFact),
                argsFact.apply(macAArch64VaListFactory,   AArch64.C_FLOAT, sumStructJavaFact),
                argsFact.apply(platformVaListFactory,     C_FLOAT,         sumStructNativeFact),
        };
    }

    @Test(dataProvider = "floatStructs")
    public void testFloatStruct(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                                Function<VaList, Float> sumFloatStruct,
                                GroupLayout FloatPoint_LAYOUT,
                                VarHandle VH_FloatPoint_x, VarHandle VH_FloatPoint_y) {
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment struct = MemorySegment.allocateNative(FloatPoint_LAYOUT, session);
            VH_FloatPoint_x.set(struct, 1.234f);
            VH_FloatPoint_y.set(struct, 3.142f);

            VaList vaList = vaListFactory.apply(b -> b.addVarg(FloatPoint_LAYOUT, struct));
            float sum = sumFloatStruct.apply(vaList);
            assertEquals(sum, 4.376f, 0.00001f);
        }
    }

    interface QuadFunc<T0, T1, T2, T3, R> {
        R apply(T0 t0, T1 t1, T2 t2, T3 t3);
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] hugeStructs() {
        QuadFunc<GroupLayout, VarHandle, VarHandle, VarHandle, Function<VaList, Long>> sumStructJavaFact
                = (HugePoint_LAYOUT, VH_HugePoint_x, VH_HugePoint_y, VH_HugePoint_z) ->
                list -> {
                    MemorySegment struct = MemorySegment.allocateNative(HugePoint_LAYOUT, MemorySession.openImplicit());
                    list.nextVarg(HugePoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    long x = (long) VH_HugePoint_x.get(struct);
                    long y = (long) VH_HugePoint_y.get(struct);
                    long z = (long) VH_HugePoint_z.get(struct);
                    return x + y + z;
                };

        QuadFunc<GroupLayout, VarHandle, VarHandle, VarHandle, Function<VaList, Long>> sumStructNativeFact
                = (pointLayout, VH_HugePoint_x, VH_HugePoint_y, VH_HugePoint_z) ->
                MethodHandleProxies.asInterfaceInstance(Function.class, MH_sumHugeStruct);

        TriFunction<Function<Consumer<VaList.Builder>, VaList>, MemoryLayout,
                QuadFunc<GroupLayout, VarHandle, VarHandle, VarHandle, Function<VaList, Long>>, Object[]> argsFact
                = (vaListFact, longLongLayout, sumBigStructFact) -> {
            GroupLayout HugePoint_LAYOUT = MemoryLayout.structLayout(
                    longLongLayout.withName("x"),
                    longLongLayout.withName("y"),
                    longLongLayout.withName("z")
            );
            VarHandle VH_HugePoint_x = HugePoint_LAYOUT.varHandle(groupElement("x"));
            VarHandle VH_HugePoint_y = HugePoint_LAYOUT.varHandle(groupElement("y"));
            VarHandle VH_HugePoint_z = HugePoint_LAYOUT.varHandle(groupElement("z"));
            return new Object[] { vaListFact,
                    sumBigStructFact.apply(HugePoint_LAYOUT, VH_HugePoint_x, VH_HugePoint_y, VH_HugePoint_z),
                    HugePoint_LAYOUT, VH_HugePoint_x, VH_HugePoint_y, VH_HugePoint_z  };
        };
        return new Object[][]{
                argsFact.apply(winVaListFactory,          Win64.C_LONG_LONG,   sumStructJavaFact),
                argsFact.apply(sysvVaListFactory,         SysV.C_LONG_LONG,    sumStructJavaFact),
                argsFact.apply(linuxAArch64VaListFactory, AArch64.C_LONG_LONG, sumStructJavaFact),
                argsFact.apply(macAArch64VaListFactory,   AArch64.C_LONG_LONG, sumStructJavaFact),
                argsFact.apply(platformVaListFactory,     C_LONG_LONG,         sumStructNativeFact),
        };
    }

    @Test(dataProvider = "hugeStructs")
    public void testHugeStruct(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                               Function<VaList, Long> sumHugeStruct,
                               GroupLayout HugePoint_LAYOUT,
                               VarHandle VH_HugePoint_x, VarHandle VH_HugePoint_y, VarHandle VH_HugePoint_z) {
        // On AArch64 a struct needs to be larger than 16 bytes to be
        // passed by reference.
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment struct = MemorySegment.allocateNative(HugePoint_LAYOUT, session);
            VH_HugePoint_x.set(struct, 1);
            VH_HugePoint_y.set(struct, 2);
            VH_HugePoint_z.set(struct, 3);

            VaList vaList = vaListFactory.apply(b -> b.addVarg(HugePoint_LAYOUT, struct));
            long sum = sumHugeStruct.apply(vaList);
            assertEquals(sum, 6);
        }
    }

    public interface SumStackFunc {
        void invoke(MemorySegment longSum, MemorySegment doubleSum, VaList list);
    }

    @DataProvider
    public static Object[][] sumStack() {
        BiFunction<ValueLayout.OfLong, ValueLayout.OfDouble, SumStackFunc> sumStackJavaFact = (longLayout, doubleLayout) ->
                (longSum, doubleSum, list) -> {
                    long lSum = 0L;
                    for (int i = 0; i < 16; i++) {
                        lSum += list.nextVarg(longLayout);
                    }
                    longSum.set(JAVA_LONG, 0, lSum);
                    double dSum = 0D;
                    for (int i = 0; i < 16; i++) {
                        dSum += list.nextVarg(doubleLayout);
                    }
                    doubleSum.set(JAVA_DOUBLE, 0, dSum);
                };
        SumStackFunc sumStackNative = (longSum, doubleSum, list) -> {
            try {
                MH_sumStack.invoke(longSum, doubleSum, list);
            } catch (Throwable ex) {
                throw new AssertionError(ex);
            }
        };
        return new Object[][]{
                { winVaListFactory,           sumStackJavaFact.apply(Win64.C_LONG_LONG, Win64.C_DOUBLE),     Win64.C_LONG_LONG,   Win64.C_DOUBLE   },
                { sysvVaListFactory,          sumStackJavaFact.apply(SysV.C_LONG_LONG, SysV.C_DOUBLE),       SysV.C_LONG_LONG,    SysV.C_DOUBLE    },
                { linuxAArch64VaListFactory,  sumStackJavaFact.apply(AArch64.C_LONG_LONG, AArch64.C_DOUBLE), AArch64.C_LONG_LONG, AArch64.C_DOUBLE },
                { macAArch64VaListFactory,    sumStackJavaFact.apply(AArch64.C_LONG_LONG, AArch64.C_DOUBLE), AArch64.C_LONG_LONG, AArch64.C_DOUBLE },
                { platformVaListFactory,      sumStackNative,                                                C_LONG_LONG,         C_DOUBLE         },
        };
    }

    @Test(dataProvider = "sumStack")
    public void testStack(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                          SumStackFunc sumStack,
                          ValueLayout.OfLong longLayout,
                          ValueLayout.OfDouble doubleLayout) {
        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment longSum = MemorySegment.allocateNative(longLayout, session);
            MemorySegment doubleSum = MemorySegment.allocateNative(doubleLayout, session);
            longSum.set(JAVA_LONG, 0, 0L);
            doubleSum.set(JAVA_DOUBLE, 0, 0D);

            VaList list = vaListFactory.apply(b -> {
                for (long l = 1; l <= 16L; l++) {
                    b.addVarg(longLayout, l);
                }
                for (double d = 1; d <= 16D; d++) {
                    b.addVarg(doubleLayout, d);
                }
            });

            sumStack.invoke(longSum, doubleSum, list);

            long lSum = longSum.get(JAVA_LONG, 0);
            double dSum = doubleSum.get(JAVA_DOUBLE, 0);

            assertEquals(lSum, 136L);
            assertEquals(dSum, 136D);
        }
    }

    @Test(dataProvider = "upcalls")
    public void testUpcall(MethodHandle target, MethodHandle callback) throws Throwable {
        FunctionDescriptor desc = FunctionDescriptor.ofVoid(C_POINTER);
        try (MemorySession session = MemorySession.openConfined()) {
            Addressable stub = abi.upcallStub(callback, desc, session);
            target.invoke(stub);
        }
    }

    @DataProvider
    public Object[][] emptyVaLists() {
        return new Object[][] {
                { Windowsx64Linker.emptyVaList()           },
                { winVaListFactory.apply(b -> {})          },
                { SysVx64Linker.emptyVaList()              },
                { sysvVaListFactory.apply(b -> {})         },
                { LinuxAArch64Linker.emptyVaList()         },
                { linuxAArch64VaListFactory.apply(b -> {}) },
                { MacOsAArch64Linker.emptyVaList()         },
                { macAArch64VaListFactory.apply(b -> {})   },
        };
    }

    @DataProvider
    @SuppressWarnings("unchecked")
    public static Object[][] sumIntsScoped() {
        Function<ValueLayout.OfInt, BiFunction<Integer, VaList, Integer>> sumIntsJavaFact = layout ->
                (num, list) -> IntStream.generate(() -> list.nextVarg(layout)).limit(num).sum();
        BiFunction<Integer, VaList, Integer> sumIntsNative
                = MethodHandleProxies.asInterfaceInstance(BiFunction.class, MH_sumInts);
        return new Object[][]{
                { winVaListScopedFactory,          sumIntsJavaFact.apply(Win64.C_INT),   Win64.C_INT   },
                { sysvVaListScopedFactory,         sumIntsJavaFact.apply(SysV.C_INT),    SysV.C_INT    },
                { linuxAArch64VaListScopedFactory, sumIntsJavaFact.apply(AArch64.C_INT), AArch64.C_INT },
                { macAArch64VaListScopedFactory,   sumIntsJavaFact.apply(AArch64.C_INT), AArch64.C_INT },
                { platformVaListScopedFactory,     sumIntsNative,                        C_INT         },
        };
    }

    @Test(dataProvider = "sumIntsScoped")
    public void testScopedVaList(BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> vaListFactory,
                                 BiFunction<Integer, VaList, Integer> sumInts,
                                 ValueLayout.OfInt intLayout) {
        VaList listLeaked;
        try (MemorySession session = MemorySession.openConfined()) {
            VaList list = vaListFactory.apply(b -> b.addVarg(intLayout, 4)
                            .addVarg(intLayout, 8), session);
            int x = sumInts.apply(2, list);
            assertEquals(x, 12);
            listLeaked = list;
        }
        assertFalse(listLeaked.session().isAlive());
    }

    @Test(dataProvider = "structs")
    public void testScopeMSRead(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                                Function<VaList, Integer> sumStruct, // ignored
                                GroupLayout Point_LAYOUT, VarHandle VH_Point_x, VarHandle VH_Point_y) {
        MemorySegment pointOut;
        try (MemorySession session = MemorySession.openConfined()) {
            try (MemorySession innerSession = MemorySession.openConfined()) {
                MemorySegment pointIn = MemorySegment.allocateNative(Point_LAYOUT, innerSession);
                VH_Point_x.set(pointIn, 3);
                VH_Point_y.set(pointIn, 6);
                VaList list = vaListFactory.apply(b -> b.addVarg(Point_LAYOUT, pointIn));
                pointOut = MemorySegment.allocateNative(Point_LAYOUT, session);
                list.nextVarg(Point_LAYOUT, SegmentAllocator.prefixAllocator(pointOut));
                assertEquals((int) VH_Point_x.get(pointOut), 3);
                assertEquals((int) VH_Point_y.get(pointOut), 6);
                assertTrue(pointOut.session().isAlive()); // after VaList freed
            }
            assertTrue(pointOut.session().isAlive()); // after inner session freed
        }
        assertFalse(pointOut.session().isAlive()); // after outer session freed
    }

    @DataProvider
    public Object[][] copy() {
        return new Object[][] {
                { winVaListScopedFactory,          Win64.C_INT   },
                { sysvVaListScopedFactory,         SysV.C_INT    },
                { linuxAArch64VaListScopedFactory, AArch64.C_INT },
                { macAArch64VaListScopedFactory,   AArch64.C_INT },
        };
    }

    @Test(dataProvider = "copy")
    public void testCopy(BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> vaListFactory, ValueLayout.OfInt intLayout) {
        try (var session = MemorySession.openConfined()) {
            VaList list = vaListFactory.apply(b -> b.addVarg(intLayout, 4)
                    .addVarg(intLayout, 8), session);
            VaList copy = list.copy();
            assertEquals(copy.nextVarg(intLayout), 4);
            assertEquals(copy.nextVarg(intLayout), 8);

            //        try { // this logic only works on Windows!
            //            int x = copy.vargAsInt(intLayout);
            //            fail();
            //        } catch (IndexOutOfBoundsException ex) {
            //            // ok - we exhausted the list
            //        }

            assertEquals(list.nextVarg(intLayout), 4);
            assertEquals(list.nextVarg(intLayout), 8);
        }
    }

    @Test(dataProvider = "copy",
            expectedExceptions = IllegalStateException.class)
    public void testCopyUnusableAfterOriginalClosed(BiFunction<Consumer<VaList.Builder>, MemorySession, VaList> vaListFactory,
                                                    ValueLayout.OfInt intLayout) {
        VaList copy;
        try (var session = MemorySession.openConfined()) {
            VaList list = vaListFactory.apply(b -> b.addVarg(intLayout, 4)
                    .addVarg(intLayout, 8), session);
            copy = list.copy();
        }

        copy.nextVarg(intLayout); // should throw
    }

    @DataProvider
    public static Object[][] upcalls() {
        GroupLayout BigPoint_LAYOUT = MemoryLayout.structLayout(
                C_LONG_LONG.withName("x"),
                C_LONG_LONG.withName("y")
        );
        VarHandle VH_BigPoint_x = BigPoint_LAYOUT.varHandle(groupElement("x"));
        VarHandle VH_BigPoint_y = BigPoint_LAYOUT.varHandle(groupElement("y"));
        GroupLayout Point_LAYOUT = MemoryLayout.structLayout(
                C_INT.withName("x"),
                C_INT.withName("y")
        );
        VarHandle VH_Point_x = Point_LAYOUT.varHandle(groupElement("x"));
        VarHandle VH_Point_y = Point_LAYOUT.varHandle(groupElement("y"));
        GroupLayout FloatPoint_LAYOUT = MemoryLayout.structLayout(
                C_FLOAT.withName("x"),
                C_FLOAT.withName("y")
        );
        VarHandle VH_FloatPoint_x = FloatPoint_LAYOUT.varHandle(groupElement("x"));
        VarHandle VH_FloatPoint_y = FloatPoint_LAYOUT.varHandle(groupElement("y"));
        GroupLayout HugePoint_LAYOUT = MemoryLayout.structLayout(
                C_LONG_LONG.withName("x"),
                C_LONG_LONG.withName("y"),
                C_LONG_LONG.withName("z")
        );
        VarHandle VH_HugePoint_x = HugePoint_LAYOUT.varHandle(groupElement("x"));
        VarHandle VH_HugePoint_y = HugePoint_LAYOUT.varHandle(groupElement("y"));
        VarHandle VH_HugePoint_z = HugePoint_LAYOUT.varHandle(groupElement("z"));

        return new Object[][]{
                { linkVaListCB("upcallBigStruct"), VaListConsumer.mh(vaList -> {
                    MemorySegment struct = MemorySegment.allocateNative(BigPoint_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(BigPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((long) VH_BigPoint_x.get(struct), 8);
                    assertEquals((long) VH_BigPoint_y.get(struct), 16);
                })},
                { linkVaListCB("upcallBigStruct"), VaListConsumer.mh(vaList -> {
                    VaList copy = vaList.copy();
                    MemorySegment struct = MemorySegment.allocateNative(BigPoint_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(BigPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((long) VH_BigPoint_x.get(struct), 8);
                    assertEquals((long) VH_BigPoint_y.get(struct), 16);

                    VH_BigPoint_x.set(struct, 0);
                    VH_BigPoint_y.set(struct, 0);

                    // should be independent
                    copy.nextVarg(BigPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((long) VH_BigPoint_x.get(struct), 8);
                    assertEquals((long) VH_BigPoint_y.get(struct), 16);
                })},
                { linkVaListCB("upcallBigStructPlusScalar"), VaListConsumer.mh(vaList -> {
                    MemorySegment struct = MemorySegment.allocateNative(BigPoint_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(BigPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((long) VH_BigPoint_x.get(struct), 8);
                    assertEquals((long) VH_BigPoint_y.get(struct), 16);

                    assertEquals(vaList.nextVarg(C_LONG_LONG), 42);
                })},
                { linkVaListCB("upcallBigStructPlusScalar"), VaListConsumer.mh(vaList -> {
                    vaList.skip(BigPoint_LAYOUT);
                    assertEquals(vaList.nextVarg(C_LONG_LONG), 42);
                })},
                { linkVaListCB("upcallStruct"), VaListConsumer.mh(vaList -> {
                    MemorySegment struct = MemorySegment.allocateNative(Point_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(Point_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((int) VH_Point_x.get(struct), 5);
                    assertEquals((int) VH_Point_y.get(struct), 10);
                })},
                { linkVaListCB("upcallHugeStruct"), VaListConsumer.mh(vaList -> {
                    MemorySegment struct = MemorySegment.allocateNative(HugePoint_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(HugePoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((long) VH_HugePoint_x.get(struct), 1);
                    assertEquals((long) VH_HugePoint_y.get(struct), 2);
                    assertEquals((long) VH_HugePoint_z.get(struct), 3);
                })},
                { linkVaListCB("upcallFloatStruct"), VaListConsumer.mh(vaList -> {
                    MemorySegment struct = MemorySegment.allocateNative(FloatPoint_LAYOUT, MemorySession.openImplicit());
                    vaList.nextVarg(FloatPoint_LAYOUT, SegmentAllocator.prefixAllocator(struct));
                    assertEquals((float) VH_FloatPoint_x.get(struct), 1.0f);
                    assertEquals((float) VH_FloatPoint_y.get(struct), 2.0f);
                })},
                { linkVaListCB("upcallMemoryAddress"), VaListConsumer.mh(vaList -> {
                    MemoryAddress intPtr = vaList.nextVarg(C_POINTER);
                    MemorySegment ms = MemorySegment.ofAddress(intPtr, C_INT.byteSize(), MemorySession.global());
                    int x = ms.get(JAVA_INT, 0);
                    assertEquals(x, 10);
                })},
                { linkVaListCB("upcallDoubles"), VaListConsumer.mh(vaList -> {
                    assertEquals(vaList.nextVarg(C_DOUBLE), 3.0);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 4.0);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 5.0);
                })},
                { linkVaListCB("upcallInts"), VaListConsumer.mh(vaList -> {
                    assertEquals(vaList.nextVarg(C_INT), 10);
                    assertEquals(vaList.nextVarg(C_INT), 15);
                    assertEquals(vaList.nextVarg(C_INT), 20);
                })},
                { linkVaListCB("upcallStack"), VaListConsumer.mh(vaList -> {
                    // skip all registers
                    for (long l = 1; l <= 16; l++) {
                        assertEquals(vaList.nextVarg(C_LONG_LONG), l);
                    }
                    for (double d = 1; d <= 16; d++) {
                        assertEquals(vaList.nextVarg(C_DOUBLE), d);
                    }

                    // test some arbitrary values on the stack
                    assertEquals((byte) vaList.nextVarg(C_INT), (byte) 1);
                    assertEquals((char) vaList.nextVarg(C_INT), 'a');
                    assertEquals((short) vaList.nextVarg(C_INT), (short) 3);
                    assertEquals(vaList.nextVarg(C_INT), 4);
                    assertEquals(vaList.nextVarg(C_LONG_LONG), 5L);
                    assertEquals((float) vaList.nextVarg(C_DOUBLE), 6.0F);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 7.0D);
                    assertEquals((byte) vaList.nextVarg(C_INT), (byte) 8);
                    assertEquals((char) vaList.nextVarg(C_INT), 'b');
                    assertEquals((short) vaList.nextVarg(C_INT), (short) 10);
                    assertEquals(vaList.nextVarg(C_INT), 11);
                    assertEquals(vaList.nextVarg(C_LONG_LONG), 12L);
                    assertEquals((float) vaList.nextVarg(C_DOUBLE), 13.0F);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 14.0D);

                    MemorySegment buffer = MemorySegment.allocateNative(BigPoint_LAYOUT, MemorySession.openImplicit());
                    SegmentAllocator bufferAllocator = SegmentAllocator.prefixAllocator(buffer);

                    MemorySegment point = vaList.nextVarg(Point_LAYOUT, bufferAllocator);
                    assertEquals((int) VH_Point_x.get(point), 5);
                    assertEquals((int) VH_Point_y.get(point), 10);

                    VaList copy = vaList.copy();
                    MemorySegment bigPoint = vaList.nextVarg(BigPoint_LAYOUT, bufferAllocator);
                    assertEquals((long) VH_BigPoint_x.get(bigPoint), 15);
                    assertEquals((long) VH_BigPoint_y.get(bigPoint), 20);

                    VH_BigPoint_x.set(bigPoint, 0);
                    VH_BigPoint_y.set(bigPoint, 0);

                    // should be independent
                    MemorySegment struct = copy.nextVarg(BigPoint_LAYOUT, bufferAllocator);
                    assertEquals((long) VH_BigPoint_x.get(struct), 15);
                    assertEquals((long) VH_BigPoint_y.get(struct), 20);
                })},
                // test skip
                { linkVaListCB("upcallStack"), VaListConsumer.mh(vaList -> {
                    vaList.skip(C_LONG_LONG, C_LONG_LONG, C_LONG_LONG, C_LONG_LONG);
                    assertEquals(vaList.nextVarg(C_LONG_LONG), 5L);
                    vaList.skip(C_LONG_LONG, C_LONG_LONG, C_LONG_LONG, C_LONG_LONG);
                    assertEquals(vaList.nextVarg(C_LONG_LONG), 10L);
                    vaList.skip(C_LONG_LONG, C_LONG_LONG, C_LONG_LONG, C_LONG_LONG, C_LONG_LONG, C_LONG_LONG);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 1.0D);
                    vaList.skip(C_DOUBLE, C_DOUBLE, C_DOUBLE, C_DOUBLE);
                    assertEquals(vaList.nextVarg(C_DOUBLE), 6.0D);
                })},
        };
    }

    interface VaListConsumer {
        void accept(VaList list);

        static MethodHandle mh(VaListConsumer instance) {
            try {
                MethodHandle handle = MethodHandles.lookup().findVirtual(VaListConsumer.class, "accept",
                        MethodType.methodType(void.class, VaList.class)).bindTo(instance);
                return MethodHandles.filterArguments(handle, 0,
                        MethodHandles.insertArguments(ADDRESS_TO_VALIST, 1, MemorySession.openConfined()));
            } catch (ReflectiveOperationException e) {
                throw new InternalError(e);
            }
        }
    }

    @DataProvider
    public static Object[][] overflow() {
        List<Function<Consumer<VaList.Builder>, VaList>> factories = List.of(
            winVaListFactory,
            sysvVaListFactory,
            linuxAArch64VaListFactory,
            macAArch64VaListFactory
        );
        List<List<MemoryLayout>> contentsCases = List.of(
            List.of(JAVA_INT),
            List.of(JAVA_LONG),
            List.of(JAVA_DOUBLE),
            List.of(ADDRESS),
            List.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
                    JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG,
                    JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                    JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE,
                    JAVA_INT, JAVA_LONG, JAVA_DOUBLE, ADDRESS)
        );
        List<MemoryLayout> overflowCases = List.of(
            JAVA_INT,
            JAVA_LONG,
            JAVA_DOUBLE,
            ADDRESS
        );
        return factories.stream()
                .<Object[]>mapMulti((factory, sink) -> {
                    for (List<MemoryLayout> content : contentsCases) {
                        for (MemoryLayout overflow : overflowCases) {
                            sink.accept(new Object[]{ factory, content, overflow });
                        }
                    }
                })
                .toArray(Object[][]::new);
    }

    private static void buildVaList(VaList.Builder builder, List<MemoryLayout> contents) {
        for (MemoryLayout layout : contents) {
            if (layout instanceof ValueLayout.OfInt ofInt) {
                 builder.addVarg(ofInt, 1);
            } else if (layout instanceof ValueLayout.OfLong ofLong) {
                 builder.addVarg(ofLong, 1L);
            } else if (layout instanceof ValueLayout.OfDouble ofDouble) {
                 builder.addVarg(ofDouble, 1D);
            } else if (layout instanceof ValueLayout.OfAddress ofAddress) {
                 builder.addVarg(ofAddress, MemoryAddress.ofLong(1));
            }
        }
    }

    @Test(dataProvider = "overflow")
    public void testSkipOverflow(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                                 List<MemoryLayout> contents,
                                 MemoryLayout skipped) {
        VaList vaList = vaListFactory.apply(b -> buildVaList(b, contents));
        vaList.skip(contents.toArray(MemoryLayout[]::new));
        assertThrows(NoSuchElementException.class, () -> vaList.skip(skipped));
    }

    private static void nextVarg(VaList vaList, MemoryLayout layout) {
        if (layout instanceof ValueLayout.OfInt ofInt) {
            assertEquals(vaList.nextVarg(ofInt), 1);
        } else if (layout instanceof ValueLayout.OfLong ofLong) {
            assertEquals(vaList.nextVarg(ofLong), 1L);
        } else if (layout instanceof ValueLayout.OfDouble ofDouble) {
            assertEquals(vaList.nextVarg(ofDouble), 1D);
        } else if (layout instanceof ValueLayout.OfAddress ofAddress) {
            assertEquals(vaList.nextVarg(ofAddress), MemoryAddress.ofLong(1));
        }
    }

    @Test(dataProvider = "overflow")
    public void testVargOverflow(Function<Consumer<VaList.Builder>, VaList> vaListFactory,
                                 List<MemoryLayout> contents,
                                 MemoryLayout next) {
        VaList vaList = vaListFactory.apply(b -> buildVaList(b, contents));
        for (MemoryLayout layout : contents) {
            nextVarg(vaList, layout);
        }
        assertThrows(NoSuchElementException.class, () -> nextVarg(vaList, next));
    }

}

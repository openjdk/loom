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

import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.function.IntFunction;


/**
 * @test
 * @requires (os.arch != "ppc64") & (os.arch != "ppc64le")
 * @modules jdk.incubator.vector
 * @modules java.base/jdk.internal.vm.annotation
 * @run testng/othervm  -XX:-TieredCompilation --add-opens jdk.incubator.vector/jdk.incubator.vector=ALL-UNNAMED
 *      Vector64ConversionTests
 */

/**
 * @test VectorConversionHighTimeout
 * @requires os.arch == "ppc64" | os.arch == "ppc64le"
 * @modules jdk.incubator.vector
 * @modules java.base/jdk.internal.vm.annotation
 * @run testng/othervm/timeout=1800  -XX:-TieredCompilation --add-opens jdk.incubator.vector/jdk.incubator.vector=ALL-UNNAMED
 *      Vector64ConversionTests
 */

/*
 * @test VectorConversionPPC64
 * @bug 8256479
 * @requires os.arch == "ppc64" | os.arch == "ppc64le"
 * @summary VectorConversion on PPC64 without Vector Register usage
 * @modules jdk.incubator.vector
 * @modules java.base/jdk.internal.vm.annotation
 * @run testng/othervm/timeout=1800  -XX:-SuperwordUseVSX -XX:-TieredCompilation --add-opens jdk.incubator.vector/jdk.incubator.vector=ALL-UNNAMED
 * Vector64ConversionTests
 */

@Test
public class Vector64ConversionTests extends AbstractVectorConversionTest {

    static final VectorShape SHAPE = VectorShape.S_64_BIT;

    @DataProvider
    public Object[][] fixedShapeXfixedShape() {
        return fixedShapeXFixedShapeSpeciesArgs(SHAPE);
    }

    @DataProvider
    public Object[][] fixedShapeXShape() {
        return fixedShapeXShapeSpeciesArgs(SHAPE);
    }

    @Test(dataProvider = "fixedShapeXfixedShape")
    static <I, O> void convert(VectorSpecies<I> src, VectorSpecies<O> dst, IntFunction<?> fa) {
        Object a = fa.apply(1024);
        conversion_kernel(src, dst, a, ConvAPI.CONVERT);
    }

    @Test(dataProvider = "fixedShapeXShape")
    static <I, O> void convertShape(VectorSpecies<I> src, VectorSpecies<O> dst, IntFunction<?> fa) {
        Object a = fa.apply(1024);
        conversion_kernel(src, dst, a, ConvAPI.CONVERTSHAPE);
    }

    @Test(dataProvider = "fixedShapeXShape")
    static <I, O> void castShape(VectorSpecies<I> src, VectorSpecies<O> dst, IntFunction<?> fa) {
        Object a = fa.apply(1024);
        conversion_kernel(src, dst, a, ConvAPI.CASTSHAPE);
    }

    @Test(dataProvider = "fixedShapeXShape")
    static <I, O> void reinterpret(VectorSpecies<I> src, VectorSpecies<O> dst, IntFunction<?> fa) {
        Object a = fa.apply(1024);
        reinterpret_kernel(src, dst, a);
    }
}

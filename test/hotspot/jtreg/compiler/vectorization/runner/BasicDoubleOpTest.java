/*
 * Copyright (c) 2022, Arm Limited. All rights reserved.
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
 * @summary Vectorization test on basic double operations
 * @library /test/lib /
 *
 * @build jdk.test.whitebox.WhiteBox
 *        compiler.vectorization.runner.VectorizationTestRunner
 *
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:.
 *                   -XX:+UnlockDiagnosticVMOptions
 *                   -XX:+WhiteBoxAPI
 *                   compiler.vectorization.runner.BasicDoubleOpTest
 *
 * @requires vm.compiler2.enabled & vm.flagless
 */

package compiler.vectorization.runner;

public class BasicDoubleOpTest extends VectorizationTestRunner {

    private static final int SIZE = 2345;

    private double[] a;
    private double[] b;
    private double[] c;

    public BasicDoubleOpTest() {
        a = new double[SIZE];
        b = new double[SIZE];
        c = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            a[i] = 850.0 * i + 22222.22;
            b[i] = -12345.678;
            c[i] = -1.23456e7;
        }
    }

    // ---------------- Arithmetic ----------------
    @Test
    public double[] vectorNeg() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = -a[i];
        }
        return res;
    }

    @Test
    public double[] vectorAbs() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.abs(a[i]);
        }
        return res;
    }

    @Test
    public double[] vectorSqrt() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.sqrt(a[i]);
        }
        return res;
    }

    @Test
    public double[] vectorCeil() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.ceil(a[i]);
        }
        return res;
    }

    @Test
    public double[] vectorFloor() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.floor(a[i]);
        }
        return res;
    }

    @Test
    public double[] vectorRint() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.rint(a[i]);
        }
        return res;
    }

    @Test
    public double[] vectorAdd() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = a[i] + b[i];
        }
        return res;
    }

    @Test
    public double[] vectorSub() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = a[i] - b[i];
        }
        return res;
    }

    @Test
    public double[] vectorMul() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = a[i] * b[i];
        }
        return res;
    }

    @Test
    public double[] vectorDiv() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = a[i] / b[i];
        }
        return res;
    }

    @Test
    public double[] vectorMax() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.max(a[i], b[i]);
        }
        return res;
    }

    @Test
    public double[] vectorMin() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.min(a[i], b[i]);
        }
        return res;
    }

    @Test
    public double[] vectorMulAdd() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(a[i], b[i], c[i]);
        }
        return res;
    }

    @Test
    public double[] vectorMulSub1() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(-a[i], b[i], c[i]);
        }
        return res;
    }

    @Test
    public double[] vectorMulSub2() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(a[i], -b[i], c[i]);
        }
        return res;
    }

    @Test
    public double[] vectorNegateMulAdd1() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(-a[i], b[i], -c[i]);
        }
        return res;
    }

    @Test
    public double[] vectorNegateMulAdd2() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(a[i], -b[i], -c[i]);
        }
        return res;
    }

    @Test
    public double[] vectorNegateMulSub() {
        double[] res = new double[SIZE];
        for (int i = 0; i < SIZE; i++) {
            res[i] = Math.fma(a[i], b[i], -c[i]);
        }
        return res;
    }

    // ---------------- Reduction ----------------
    @Test
    public double reductionAdd() {
        double res = 0.0;
        for (int i = 0; i < SIZE; i++) {
            res += a[i];
        }
        return res;
    }

    @Test
    public double reductionMax() {
        double res = Double.MIN_VALUE;
        for (int i = 0; i < SIZE; i++) {
            res = Math.max(res, a[i]);
        }
        return res;
    }

    @Test
    public double reductionMin() {
        double res = Double.MAX_VALUE;
        for (int i = 0; i < SIZE; i++) {
            res = Math.min(res, a[i]);
        }
        return res;
    }
}


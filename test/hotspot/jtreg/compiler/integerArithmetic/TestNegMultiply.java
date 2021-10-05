/*
 * Copyright (c) 2021, Red Hat, Inc. All rights reserved.
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
 * @key randomness
 * @bug 8273454
 * @summary Test transformation (-a)*(-b) = a*b
 *
 * @library /test/lib
 *
 * @run main/othervm -XX:-TieredCompilation -XX:-BackgroundCompilation -XX:-UseOnStackReplacement -XX:CompileCommand="dontinline,TestNegMultiply::test*" TestNegMultiply
 *
 */

import java.util.Random;
import jdk.test.lib.Utils;
import jdk.test.lib.Asserts;

public class TestNegMultiply {
    private static final Random random = Utils.getRandomInstance();
    // Enough cycles to ensure test methods are JIT-ed
    private static final int TEST_COUNT = 20_000;

    private static int testInt(int a, int b) {
        return (-a) * (-b);
    }
    private static long testLong(long a, long b) {
        return (-a) * (-b);
    }
    private static float testFloat(float a, float b) {
        return (-a) * (-b);
    }
    private static double testDouble(double a, double b) {
        return (-a) * (-b);
    }

    private static void runIntTests() {
        for (int index = 0; index < TEST_COUNT; index ++) {
            int a = random.nextInt();
            int b = random.nextInt();
            int expected = (-a) * (-b);
            int res = testInt(a, b);
            Asserts.assertEQ(res, expected);
        }
    }

    private static void runLongTests() {
        for (int index = 0; index < TEST_COUNT; index ++) {
            long a = random.nextLong();
            long b = random.nextLong();
            long expected = (-a) * (-b);
            long res = testLong(a, b);
            Asserts.assertEQ(res, expected);
        }
    }

    private static void runFloatTests() {
        for (int index = 0; index < TEST_COUNT; index ++) {
            float a = random.nextFloat();
            float b = random.nextFloat();
            float expected = (-a) * (-b);
            float res = testFloat(a, b);
            Asserts.assertEQ(res, expected);
        }
    }

    private static void runDoubleTests() {
        for (int index = 0; index < TEST_COUNT; index ++) {
            double a = random.nextDouble();
            double b = random.nextDouble();
            double expected = (-a) * (-b);
            double res = testDouble(a, b);
            Asserts.assertEQ(res, expected);
        }
    }

    public static void main(String[] args) {
        runIntTests();
        runLongTests();
        runFloatTests();
        runDoubleTests();
    }
}

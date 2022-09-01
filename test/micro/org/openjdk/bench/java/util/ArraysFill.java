/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 4, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(value = 3)
public class ArraysFill {

    @Param({"16", "31", "250", "266", "511", "2047", "2048", "8195"})
    public int size;

    public byte[] testByteArray;
    public char[] testCharArray;
    public short[] testShortArray;
    public int[] testIntArray;
    public long[] testLongArray;
    public float[] testFloatArray;
    public double[] testDoubleArray;

    @Setup
    public void setup() {
        testByteArray = new byte[size];
        testCharArray = new char[size];
        testShortArray = new short[size];
        testIntArray = new int[size];
        testLongArray = new long[size];
        testFloatArray = new float[size];
        testDoubleArray = new double[size];

    }

    @Benchmark
    public void testCharFill() {
        Arrays.fill(testCharArray, (char) -1);
    }

    @Benchmark
    public void testByteFill() {
        Arrays.fill(testByteArray, (byte) -1);
    }

    @Benchmark
    public void testShortFill() {
        Arrays.fill(testShortArray, (short) -1);
    }

    @Benchmark
    public void testIntFill() {
        Arrays.fill(testIntArray, -1);
    }

    @Benchmark
    public void testLongFill() {
        Arrays.fill(testLongArray, -1);
    }

    @Benchmark
    public void testFloatFill() {
        Arrays.fill(testFloatArray, (float) -1.0);
    }

    @Benchmark
    public void testDoubleFill() {
        Arrays.fill(testDoubleArray, -1.0);
    }
}

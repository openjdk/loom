/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.lang;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Param;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class LongDivMod {

    RandomGenerator randomGenerator;

    @Param({"mixed", "positive", "negative"})
    String divisorType;
    @Param({"1024"})
    int BUFFER_SIZE;
    long[] dividends, divisors, quotients, remainders;

    @Setup
    public void setup() {
        dividends = new long[BUFFER_SIZE];
        divisors = new long[BUFFER_SIZE];
        quotients =  new long[BUFFER_SIZE];
        remainders =  new long[BUFFER_SIZE];
        RandomGenerator rng = RandomGeneratorFactory.getDefault().create(0);
        for (int i = 0; i < BUFFER_SIZE; i++) {
            dividends[i] = rng.nextLong();
            long divisor = rng.nextLong();
            if (divisorType.equals("positive")) {
                divisor = Math.abs(divisor);
            } else if (divisorType.equals("negative")) {
                divisor = -Math.abs(divisor);
            }
            divisors[i] = divisor;
        }
    }

    @Benchmark
    public void testDivide() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            quotients[i] = dividends[i] / divisors[i];
        }
    }

    @Benchmark
    public void testDivideKnownPositive() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            quotients[i] = dividends[i] / Math.max(1, divisors[i]);
        }
    }

    @Benchmark
    public void testDivideHoistedDivisor() {
        long x = divisors[0];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            quotients[i] = dividends[i] / x;
        }
    }

    @Benchmark
    public void testDivideUnsigned() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            quotients[i] = Long.divideUnsigned(dividends[i], divisors[i]);
        }
    }

    @Benchmark
    public void testRemainderUnsigned() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            remainders[i] = Long.remainderUnsigned(dividends[i], divisors[i]);
        }
    }

    @Benchmark
    public void testDivideRemainderUnsigned() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            divmod(dividends[i], divisors[i], i);
        }
    }

    public void divmod(long dividend, long divisor, int i) {
        quotients[i] = Long.divideUnsigned(dividend, divisor);
        remainders[i] = Long.remainderUnsigned(dividend, divisor);
    }
}

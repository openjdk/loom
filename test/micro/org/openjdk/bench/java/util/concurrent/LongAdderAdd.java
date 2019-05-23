/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.util.concurrent;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.LongAdderCPU;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import jdk.internal.misc.Unsafe;

/**
 * Benchmark vanilla LongAdder vs LongAdder with Unsafe.compareAndSetLongCPU support.
 * Benchmark vanilla compareAndSet vs compareAndSetLongCPU.
 *
 */
@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Threads(Threads.MAX)
@State(Scope.Benchmark)
public class LongAdderAdd {

    private LongAdder count0;
    private LongAdderCPU count1;

    @Setup
    public void setup() {
        count0 = new LongAdder();
        count1 = new LongAdderCPU();
    }

    @Benchmark
    public void testAddBaseline() {
        count0.add(1);
    }

    @Benchmark
    public void testAddCPU() {
        count1.add(1);
    }

    @State(Scope.Thread)
    public static class ThreadState {
        volatile long value = 0L;
    }

    private static final Unsafe U = Unsafe.getUnsafe();
    private static final VarHandle VALUE;
    private static final long offset;

    static {
	try {
	    MethodHandles.Lookup l = MethodHandles.lookup();
	    VALUE = l.findVarHandle(ThreadState.class, "value", long.class);
	    offset = U.objectFieldOffset(ThreadState.class, "value");
	} catch (ReflectiveOperationException e) {
	    throw new ExceptionInInitializerError(e);
	}
    }

    @Benchmark
    public void testCAS(ThreadState s) {
        VALUE.compareAndSet(s, 0L, 0L);
    }

    @Benchmark
    public void testCASCPU(ThreadState s) {
	int cpu = U.getProcessorId();
        U.compareAndSetLongCPU(s, offset, cpu, 0L, 0L);
    }

}

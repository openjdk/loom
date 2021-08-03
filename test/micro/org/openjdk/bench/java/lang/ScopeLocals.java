/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static org.openjdk.bench.java.lang.ScopeLocalsData.*;

/**
 * Tests java.lang.ScopeLocal
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations=4, time=1)
@Measurement(iterations=10, time=1)
@Threads(1)
@Fork(value = 1,
        jvmArgsPrepend = {"-Djmh.executor.class=org.openjdk.bench.java.lang.ScopeLocalsExecutorService", "-Djmh.executor=CUSTOM", "--enable-preview"})
@State(Scope.Thread)
@SuppressWarnings("preview")
public class ScopeLocals {

    // Test 1: make sure ScopeLocal.get() is hoisted out of loops.

    @Benchmark
    public void thousandAdds_ScopeLocal(Blackhole bh) throws Exception {
        int result = 0;
        for (int i = 0; i < 1_000; i++) {
            result += ScopeLocalsData.sl1.get();
        }
        bh.consume(result);
    }

    @Benchmark
    public void thousandAdds_ThreadLocal(Blackhole bh) throws Exception {
        int result = 0;
        for (int i = 0; i < 1_000; i++) {
            result += ScopeLocalsData.tl1.get();
        }
        bh.consume(result);
    }

    // Test 2: stress the ScopeLocal cache.
    // The idea here is to use a bunch of bound values cyclically, which
    // stresses the ScopeLocal cache.

    int combine(int n, int i1, int i2, int i3, int i4, int i5, int i6) {
        return n + ((i1 ^ i2 >>> 6) + (i3 << 7) + i4 - i5 | i6);
    }

    @Benchmark
    public int sixValues_ScopeLocal() throws Exception {
        int result = 0;
        for (int i = 0 ; i < 166; i++) {
            result = combine(result, sl1.get(), sl2.get(), sl3.get(), sl4.get(), sl5.get(), sl6.get());
        }
        return result;
    }

    @Benchmark
    public int sixValues_ThreadLocal() throws Exception {
        int result = 0;
        for (int i = 0 ; i < 166; i++) {
            result = combine(result, tl1.get(), tl2.get(), tl3.get(), tl4.get(), tl5.get(), tl6.get());
        }
        return result;
    }

    // Test 3: The cost of bind, then get
    // This is the worst case for ScopeLocals because we have to create
    // a binding, link it in, then search the current bindings. In addition, we
    // create a cache entry for the bound value, then we immediately have to
    // destroy it.

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int bindThenGetThenRemove_ScopeLocal() throws Exception {
        return ScopeLocal.where(sl1, 42, sl1::get);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int bindThenGetThenRemove_ThreadLocal() throws Exception {
        try {
            tl1.set(42);
            return tl1.get();
        } finally {
            tl1.remove();
        }
    }

    // This has no exact equivalent in ScopeLocal, but it's provided here for
    // information.
    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int bindThenGetNoRemove_ThreadLocal() throws Exception {
        tl1.set(42);
        return tl1.get();
    }

    // Test 4: The cost of binding, but not using the any result

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public Object bind_ScopeLocal() throws Exception {
        return ScopeLocal.where(sl1, 42, this::getClass);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public Object bind_ThreadLocal() throws Exception {
        try {
            tl1.set(42);
            return this.getClass();
        } finally {
            tl1.remove();
        }
    }

    // Simply set a ThreadLocal so that the caller can see it
    // This has no exact equivalent in ScopeLocal, but it's provided here for
    // information.
    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void setNoRemove_ThreadLocal() throws Exception {
        tl1.set(42);
    }

    // This is the closest I can think of to setNoRemove_ThreadLocal in that it
    // returns a value in a ScopeLocal container. The container must already
    // be bound to an AtomicReference for this to work.
    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void setNoRemove_ScopeLocal() throws Exception {
        sl_atomicRef.get().setPlain(42);
    }

    // Test 5: A simple counter

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void counter_ScopeLocal() {
        sl_atomicInt.get().setPlain(
                sl_atomicInt.get().getPlain() + 1);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void counter_ThreadLocal() {
        // Very slow:
        // tl1.set(tl1.get() + 1);
        var ctr = tl_atomicInt.get();
        ctr.setPlain(ctr.getPlain() + 1);
    }
}

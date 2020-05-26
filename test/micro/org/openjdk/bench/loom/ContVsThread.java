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

package org.openjdk.bench.loom;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 40, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class ContVsThread {

    static final ContinuationScope scope = new ContinuationScope() { };

    // ThreadFactory that uses the current Thread as the carrier thread
    static final ThreadFactory vthreadFactory = Thread.builder().virtual(Runnable::run).factory();

    Object obj;


    // compare creating a Continuation vs. virtual Thread

    @Benchmark
    public void contCreate() {
        // store in obj to prevent scalar replacement
        obj = new Continuation(scope, () -> { });
    }

    @Benchmark
    public void vthreadCreate() {
        obj = vthreadFactory.newThread(() -> { });
    }

    // compare create+run a continuation to completion vs. create+start+join virtual Thread

    @Benchmark
    public void contRunNoop() {
        Continuation cont = new Continuation(scope, () -> { });
        cont.run();
        if (!cont.isDone()) throw new Error();
    }

    @Benchmark
    public void vthreadRunNoop() throws Exception {
        Thread vthread = vthreadFactory.newThread(() -> { });
        vthread.start();
        vthread.join();
    }


    // compare Continuation run+yield vs. virtual Thread unpark+park

    static final Continuation yieldingCont = new Continuation(scope, () -> {
        while (true) { Continuation.yield(scope); }
    });

    static final Thread parkingVThread = Thread.builder()
            .virtual(Runnable::run)
            .task(() -> { while (true) { LockSupport.park(); } })
            .start();

    @Benchmark
    public void contRunYield() {
        yieldingCont.run();
    }

    @Benchmark
    public void vthreadRunYield() {
        LockSupport.unpark(parkingVThread);
    }


    // compare starting a virtual thread to submitting tasks to a FJ thread pool

    static final int NPROCS = Runtime.getRuntime().availableProcessors();
    static final ExecutorService forkJoinPool = new ForkJoinPool(NPROCS);

    @Benchmark
    public void noopRunInTask() throws Exception {
        forkJoinPool.submit(() -> { }).get();    // external submit
    }

    @Benchmark
    public void contRunInTask() throws Exception {
        Continuation cont = new Continuation(scope, () -> { });
        forkJoinPool.submit(cont::run).get();
    }

    @Benchmark
    public void vthreadRunInTask() throws Exception {
        Thread.startVirtualThread(() -> { }).join();
    }

}

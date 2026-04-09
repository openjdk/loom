/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.bench.java.util.concurrent.forkjoin;

import java.util.*;
import java.util.concurrent.*;
import org.openjdk.jmh.annotations.*;

/**
 * One thread scheduling + cancelling delayed tasks. CPU optionally saturated.
 */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ScheduleAndCancelDelayedTasks1 {
    private ForkJoinPool fjpPool;
    private ExecutorService threadPool;
    private volatile boolean done;
    private Deque<Future<?>> pendingDelayedTasks;

    private void spinUntilDone() {
        while (!done) {
            Thread.onSpinWait();
        }
    }

    @Param({"true", "false"})
    boolean cpuSaturated;

    @Param({"1", "100"})
    int maxPending;

    @Setup
    public void setup() {
        int ncores = Runtime.getRuntime().availableProcessors();
        fjpPool = new ForkJoinPool(ncores);
        fjpPool.cancelDelayedTasksOnShutdown();

        // saturate CPU
        if (cpuSaturated) {
            threadPool = Executors.newCachedThreadPool();
            for (int i = 0; i < (ncores - 1); i++) {
                threadPool.submit(this::spinUntilDone);
            }
        }

        pendingDelayedTasks = new ArrayDeque<>();
    }

    @TearDown
    public void teardown() {
        done = true;
        if (threadPool != null) threadPool.close();
        fjpPool.close();
    }

    @Benchmark
    public void test() throws Exception {
        long delay = 60 + ThreadLocalRandom.current().nextInt(60);
        Future<?> future = fjpPool.schedule(() -> { }, delay, TimeUnit.SECONDS);
        if (maxPending > 1) {
            pendingDelayedTasks.offer(future);
            if (pendingDelayedTasks.size() >= maxPending) {
                future = pendingDelayedTasks.poll();
            }
        }
        future.cancel(false);
    }
}

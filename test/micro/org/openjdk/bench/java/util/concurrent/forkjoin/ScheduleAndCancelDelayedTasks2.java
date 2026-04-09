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
 * CPU saturated with threads scheduling + cancelling delayed tasks.
 */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ScheduleAndCancelDelayedTasks2 {
    private ForkJoinPool fjpPool;
    private ExecutorService threadPool;
    private volatile boolean done;

    private void scheduleAndCancelDelayedTask(Deque<Future<?>> queue, int maxPending) {
        long delay = 60 + ThreadLocalRandom.current().nextInt(60);
        Future<?> future = fjpPool.schedule(() -> { }, delay, TimeUnit.SECONDS);
        if (maxPending > 1) {
            queue.offer(future);
            if (queue.size() >= maxPending) {
                future = queue.poll();
            }
        }
        future.cancel(false);
    }

    private Deque<Future<?>> pendingDelayedTasks;

    @Param({"1", "100"})
    int maxPending;

    @Param({"true", "false"})
    boolean internal;

    @Setup
    public void setup() {
        int ncores = Runtime.getRuntime().availableProcessors();
        fjpPool = new ForkJoinPool(ncores);
        fjpPool.cancelDelayedTasksOnShutdown();

        // saturate CPU with tasks that schedule and cancel delayed tasks
        Executor executor;
        if (internal) {
            executor = fjpPool;
        } else {
            executor = threadPool = Executors.newCachedThreadPool();
        }
        for (int i = 0; i <  (ncores - 1); i++) {
            executor.execute(() -> {
                Deque<Future<?>> queue = new ArrayDeque<>();
                while (!done) {
                    scheduleAndCancelDelayedTask(queue, maxPending);
                }
            });
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
        scheduleAndCancelDelayedTask(pendingDelayedTasks, maxPending);
    }
}

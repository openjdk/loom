/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Stress test Thread.getStackTrace on virtual threads that are blocking or
 *     blocked on monitorenter
 */

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class GetStackTraceALotWhenBlocked {
    static final long DURATION_IN_NANOS = Duration.ofSeconds(5).toNanos();

    public static void main(String[] args) throws Exception {
        var done = new AtomicBoolean();
        var lock = new Object();

        Runnable task = () -> {
            long count = 0L;
            while (!done.get()) {
                synchronized (lock) {
                    pause();
                }
                count++;
            }
            System.out.format("%s => %d%n", Thread.currentThread(), count);
        };

        var thread1 = Thread.ofVirtual().start(task);
        var thread2 = Thread.ofVirtual().start(task);
        try {
            long startTime = System.nanoTime();
            while ((System.nanoTime() - startTime) < DURATION_IN_NANOS) {
                thread1.getStackTrace();
                pause();
                thread2.getStackTrace();
                pause();
            }
        } finally {
            done.set(true);
            thread1.join();
            thread2.join();
        }
    }

    private static void pause() {
        if (ThreadLocalRandom.current().nextBoolean()) {
            Thread.onSpinWait();
        } else {
            Thread.yield();
        }
    }
}

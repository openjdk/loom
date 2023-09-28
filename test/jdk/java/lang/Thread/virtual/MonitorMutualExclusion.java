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
 * @summary Test mutual exclusion of monitors with platform and virtual threads
 * @key randomness
 * @run junit MonitorMutualExclusion
 */

import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class MonitorMutualExclusion {

    // 0,2,4,..16 platform threads
    // 2,4,6,..32 virtual threads
    static Stream<Arguments> threadCounts() {
        return IntStream.range(0, 17)
                .filter(i -> i % 2 == 0)
                .mapToObj(i -> i)
                .flatMap(np -> IntStream.range(2, 33)
                        .filter(i -> i % 2 == 0)
                        .mapToObj(vp -> Arguments.of(np, vp)));
    }

    @ParameterizedTest
    @MethodSource("threadCounts")
    void testExclusion(int nPlatformThreads, int nVirtualThreads) throws Exception {
        class Counter {
            int count;
            synchronized void increment() {
                count++;
                if (ThreadLocalRandom.current().nextBoolean()) {
                    Thread.onSpinWait();
                } else {
                    Thread.yield();
                }
            }
        }
        var counter = new Counter();
        int nThreads = nPlatformThreads + nVirtualThreads;
        var threads = new Thread[nThreads];
        int index = 0;
        for (int i = 0; i < nPlatformThreads; i++) {
            threads[index++] = Thread.ofPlatform().unstarted(counter::increment);
        }
        for (int i = 0; i < nVirtualThreads; i++) {
            threads[index++] = Thread.ofVirtual().unstarted(counter::increment);
        }
        // start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        // wait for all threads to terminate
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(nThreads, counter.count);
    }
}


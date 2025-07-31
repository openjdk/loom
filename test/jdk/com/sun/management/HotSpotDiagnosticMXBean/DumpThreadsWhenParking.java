/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @bug 8364343
 * @summary HotSpotDiagnosticMXBean.dumpThreads while virtual threads are parking and unparking
 * @requires vm.continuations
 * @modules jdk.management
 * @library /test/lib
 * @run main DumpThreadsWhenParking 1000 100
 */

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import com.sun.management.HotSpotDiagnosticMXBean;
import jdk.test.lib.thread.VThreadRunner;
import jdk.test.lib.threaddump.ThreadDump;

public class DumpThreadsWhenParking {

    public static void main(String... args) throws Throwable {
        int nthreads = Integer.parseInt(args[0]);
        int iterations = Integer.parseInt(args[1]);

        // need >=2 carriers for JTREG_TEST_THREAD_FACTORY=Virtual runs
        if (Thread.currentThread().isVirtual()) {
            VThreadRunner.ensureParallelism(2);
        }

        Path file = Path.of("threads.json").toAbsolutePath();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var done = new AtomicBoolean();
            var started = new CountDownLatch(nthreads);
            for (int i = 0; i < nthreads; i++) {
                executor.submit(() -> {
                    started.countDown();
                    while (!done.get()) {
                        LockSupport.parkNanos(1);
                    }
                });
            }
            started.await();  // wait for threads to start so all have a non-empty stack

            // invoke HotSpotDiagnosticMXBean.dumpThreads many times
            String executorName = Objects.toIdentityString(executor);
            try {
                for (int i = 0; i < iterations; i++) {
                    System.out.format("%s %d ...%n", Instant.now(), i);
                    Files.deleteIfExists(file);
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);

                    // read and parse the dump
                    String jsonText = Files.readString(file);
                    ThreadDump threadDump = ThreadDump.parse(jsonText);
                    var container = threadDump.findThreadContainer(executorName).orElse(null);
                    if (container == null) {
                        fail(executorName + " not found in thread dump");
                    }

                    // check expected virtual thread count
                    long threadCount = container.threads().count();
                    if (threadCount != nthreads) {
                        fail(threadCount + " virtual threads found, expected " + nthreads);
                    }

                    // check each thread is a virtual thread with stack frames
                    container.threads().forEach(t -> {
                        if (!t.isVirtual()) {
                            fail("#" + t.tid() + "(" + t.name() + ") is not a virtual thread");
                        }
                        long stackFrameCount = t.stack().count();
                        if (stackFrameCount == 0) {
                            fail("#" + t.tid() + " has empty stack");
                        }
                    });

                }
            } finally {
                done.set(true);
            }
        }
    }

    private static void fail(String message) {
        throw new RuntimeException(message);
    }
}

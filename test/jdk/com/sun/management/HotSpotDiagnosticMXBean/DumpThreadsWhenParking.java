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
 * @bug 1234567
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import com.sun.management.HotSpotDiagnosticMXBean;
import jdk.test.lib.threaddump.ThreadDump;

public class DumpThreadsWhenParking {

    public static void main(String... args) throws Throwable {
        int nthreads = (args.length > 0) ? Integer.parseInt(args[0]) : 1000;
        int iterations = (args.length > 1) ? Integer.parseInt(args[1]) : 100;

        Path file = Path.of("threads.json").toAbsolutePath();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var done = new AtomicBoolean();
            for (int i = 0; i < nthreads; i++) {
                executor.submit(() -> {
                    while (!done.get()) {
                        LockSupport.parkNanos(1);
                    }
                });
            }

            try {
                for (int i = 0; i < iterations; i++) {
                    System.out.format("%s %d ...%n", Instant.now(), i);
                    Files.deleteIfExists(file);
                    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);

                    // read and parse the dump
                    String jsonText = Files.readString(file);
                    ThreadDump.parse(jsonText);
                }
            } finally {
                done.set(true);
            }
        }
    }
}

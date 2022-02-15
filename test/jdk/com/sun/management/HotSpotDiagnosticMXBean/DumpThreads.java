/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic test for com.sun.management.HotSpotDiagnosticMXBean.dumpThreads
 * @compile --enable-preview -source ${jdk.version} DumpThreads.java
 * @run testng/othervm --enable-preview DumpThreads
 */

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class DumpThreads {

    /**
     * Thread dump in plain text format.
     */
    @Test
    public void testPlainText() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Path file = genOutputPath("txt");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread vthread = forkParker(executor);

            mbean.dumpThreads(file.toString(), ThreadDumpFormat.TEXT_PLAIN);
            cat(file);

            // virtual thread should be found
            try {
                assertTrue(isPresent(file, vthread));
            } finally {
                LockSupport.unpark(vthread);
            }

            // if the current thread is a platform thread then it should be included
            Thread currentThread = Thread.currentThread();
            if (!currentThread.isVirtual()) {
                assertTrue(isPresent(file, currentThread));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Thread dump in JSON format.
     */
    @Test
    public void testJson() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Path file = genOutputPath("json");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Thread vthread = forkParker(executor);

            mbean.dumpThreads(file.toString(), ThreadDumpFormat.JSON);
            cat(file);

            assertTrue(count(file, "threadDump") >= 1L);
            assertTrue(count(file, "threadContainers") >= 1L);
            assertTrue(count(file, "threads") >= 1L);

            // virtual thread should be found
            try {
                assertTrue(isJsonPresent(file, vthread));
            } finally {
                LockSupport.unpark(vthread);
            }

            // if the current thread is a platform thread then it should be included
            Thread currentThread = Thread.currentThread();
            if (!currentThread.isVirtual()) {
                assertTrue(isJsonPresent(file, currentThread));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testRelativePath1() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        mbean.dumpThreads("threads.txt", ThreadDumpFormat.TEXT_PLAIN);
    }

    @Test(expectedExceptions = { IllegalArgumentException.class })
    public void testRelativePath2() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        mbean.dumpThreads("threads.json", ThreadDumpFormat.JSON);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        mbean.dumpThreads(null, ThreadDumpFormat.TEXT_PLAIN);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        mbean.dumpThreads(genOutputPath("txt").toString(), null);
    }

    /**
     * Submits a parking task to the given executor, returns the Thread object of
     * the parked thread.
     */
    private static Thread forkParker(ExecutorService executor) throws Exception {
        var ref = new AtomicReference<Thread>();
        executor.submit(() -> {
            ref.set(Thread.currentThread());
            LockSupport.park();
        });
        Thread thread;
        while ((thread = ref.get()) == null) {
            Thread.sleep(10);
        }
        return thread;
    }

    /**
     * Returns true if the file contains #<tid>
     */
    private static boolean isPresent(Path file, Thread thread) throws Exception {
        String expect = "#" + thread.getId();
        return count(file, expect) > 0;
    }

    /**
     * Returns true if the file contains "tid": <tid>
     */
    private static boolean isJsonPresent(Path file, Thread thread) throws Exception {
        String expect = "\"tid\": " + thread.getId();
        return count(file, expect) > 0;
    }

    private static Path genOutputPath(String suffix) throws Exception {
        Path dir = Path.of(".").toAbsolutePath();
        Path file = Files.createTempFile(dir, "dump", suffix);
        Files.delete(file);
        return file;
    }

    static long count(Path file, CharSequence cs) throws Exception {
        try (Stream<String> stream = Files.lines(file)) {
            return stream.filter(line -> line.contains(cs)).count();
        }
    }

    private static void cat(Path file) throws Exception {
        try (Stream<String> stream = Files.lines(file)) {
            stream.forEach(System.out::println);
        }
    }
}

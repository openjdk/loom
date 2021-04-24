
/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng DumpThreads
 */

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class DumpThreads {

    @Test
    public void testPlainText() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Path file = genOutputPath("txt");
        try {
            mbean.dumpThreads(file.toString(), ThreadDumpFormat.TEXT_PLAIN);
            cat(file);
            String expect = "#" + Thread.currentThread().getId();
            assertTrue(count(file, expect) == 1L);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void testJson() throws Exception {
        var mbean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        Path file = genOutputPath("json");
        try {
            mbean.dumpThreads(file.toString(), ThreadDumpFormat.JSON);
            cat(file);
            assertTrue(count(file, "threadDump") >= 1L);
            assertTrue(count(file, "threadExecutors") >= 1L);
            assertTrue(count(file, "threads") >= 1L);
            String expect = "\"tid\": " + Thread.currentThread().getId();
            assertTrue(count(file, expect) >= 1L);
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
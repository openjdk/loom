/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8284161 8289284
 * @summary Basic test of debugging option to trace pinned threads
 * @requires vm.continuations
 * @enablePreview
 * @library /test/lib
 * @run testng/othervm -Djdk.tracePinnedThreads=full TracePinnedThreads
 * @run testng/othervm -Djdk.tracePinnedThreads=short TracePinnedThreads
 */

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadRunner;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TracePinnedThreads {
    static final Object lock = new Object();

    /**
     * Parks current thread for 1 second.
     */
    private static void park() {
        long nanos = Duration.ofSeconds(1).toNanos();
        LockSupport.parkNanos(nanos);
    }

    /**
     * Invokes the park method through a native frame to park the current
     * thread for 1 second.
     */
    private static native void invokePark();

    /**
     * Test parking inside synchronized block.
     */
    @Test
    public void testPinnedCausedBySynchronizedBlock() throws Exception {
        String output = run(() -> {
            synchronized (lock) {
                park();
            }
        });
        assertContains(output, "<== monitors:1");
        assertDoesNotContain(output, "(Native Method)");
    }

    /**
     * Test parking with native frame on stack.
     */
    @Test
    public void testPinnedCausedByNativeMethod() throws Exception {
        System.loadLibrary("TracePinnedThreads");
        String output = run(() -> invokePark());
        assertContains(output, "(Native Method)");
        assertDoesNotContain(output, "<== monitors");
    }

    /**
     * Runs a task in a virutal thread, returning a String with any output printed
     * to standard output.
     */
    private static String run(Runnable task) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(baos, true));
        try {
            VThreadRunner.run(task::run);
        } finally {
            System.setOut(original);
        }
        String output = new String(baos.toByteArray());
        System.out.println(output);
        return output;
    }

    /**
     * Tests that s1 contains s2.
     */
    private static void assertContains(String s1, String s2) {
        assertTrue(s1.contains(s2), s2 + " not found!!!");
    }

    /**
     * Tests that s1 does not contain s2.
     */
    private static void assertDoesNotContain(String s1, String s2) {
        assertFalse(s1.contains(s2), s2 + " found!!");
    }
}

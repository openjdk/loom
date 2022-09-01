/*
 * Copyright (c) 2014, 2022, Oracle and/or its affiliates. All rights reserved.
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

package compiler.codecache.stress;

import jdk.test.lib.Asserts;
import jdk.test.lib.ByteCodeLoader;
import jdk.test.lib.InfiniteLoop;
import jdk.test.whitebox.WhiteBox;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;

public final class Helper {
    public static final boolean VIRTUAL_THREAD = Boolean.getBoolean("helperVirtualThread");
    public static final WhiteBox WHITE_BOX = WhiteBox.getWhiteBox();

    private static final long THRESHOLD = WHITE_BOX.getIntxVMFlag("CompileThreshold");
    private static final String TEST_CASE_IMPL_CLASS_NAME = TestCaseImpl.class.getName();
    private static byte[] CLASS_DATA;
    static {
        try {
            CLASS_DATA = loadClassData(TEST_CASE_IMPL_CLASS_NAME);
        } catch (IOException e) {
            throw new Error("TESTBUG: cannot load class byte code " + TEST_CASE_IMPL_CLASS_NAME, e);
        }
    }

    private Helper() {
    }

    public static void startInfiniteLoopThread(Runnable action) {
        startInfiniteLoopThread(action, 0L);
    }

    public static void startInfiniteLoopThread(Runnable action, long millis) {
        startInfiniteLoopThread(threadFactory(VIRTUAL_THREAD), action, millis);
    }

    public static void startInfiniteLoopThread(ThreadFactory threadFactory, Runnable action, long millis) {
        threadFactory.newThread(new InfiniteLoop(action, millis)).start();
    }

    public static ThreadFactory threadFactory(boolean virtual) {
        // After virtual thread Preview:
        // return (virtual ? Thread.ofVirtual() : Thread.ofPlatform().daemon()).factory();
        if (virtual) {
            return virtualThreadFactory();
        } else {
            return runnable -> {
                Thread t = new Thread(runnable);
                t.setDaemon(true);
                return t;
            };
        }
    }

    private static ThreadFactory virtualThreadFactory() {
        try {
            return (ThreadFactory)Class.forName("java.lang.Thread$Builder").getMethod("factory")
                .invoke(Thread.class.getMethod("ofVirtual").invoke(null));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    public static int callMethod(Callable<Integer> callable, int expected) {
        int result = 0;
        for (int i = 0; i < THRESHOLD; ++i) {
            try {
                result = callable.call();
            } catch (Exception e) {
                throw new AssertionError(
                        "Exception occurred during test method execution", e);
            }
            Asserts.assertEQ(result, expected, "Method returns unexpected value");
        }
        return result;
    }

    private static byte[] loadClassData(String name) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(
                ClassLoader.getSystemResourceAsStream(name.replace(".", "/")
                        + ".class"))) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                result.write(buffer, 0, read);
            }
            return result.toByteArray();
        }
    }

    public interface TestCase {

        public static TestCase get() {
            try {
                Class clazz = ByteCodeLoader.load(
                        TEST_CASE_IMPL_CLASS_NAME, CLASS_DATA);
                return (TestCase) clazz.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new Error(String.format(
                        "TESTBUG: error while creating %s instance from reloaded class",
                        TEST_CASE_IMPL_CLASS_NAME), e);
            }
        }

        Callable<Integer> getCallable();
        int method();
        int expectedValue();
    }
}

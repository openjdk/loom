/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @modules java.management
 * @run testng StackTraces
 * @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+ShowCarrierFrames StackTraces
 * @summary Test that the stack traces for carrier threads are hidden by
 *     exceptions and the StackWalker API
 */

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import static java.lang.StackWalker.Option.*;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class StackTraces {

    // The stack frames for the carrier thread may be hidden
    public void testStackTrace() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Exception e = new Exception();
            boolean found = Arrays.stream(e.getStackTrace())
                    .map(StackTraceElement::getClassName)
                    .anyMatch("java.util.concurrent.ForkJoinPool"::equals);
            assertTrue(found == hasJvmArgument("-XX:+ShowCarrierFrames"));
        });
    }

    // carrier frames should be hidden
    public void testStackWalker() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            StackWalker walker = StackWalker.getInstance(Set.of(RETAIN_CLASS_REFERENCE));
            boolean found = walker.walk(sf ->
                    sf.map(StackWalker.StackFrame::getDeclaringClass)
                            .anyMatch(c -> c == ForkJoinPool.class));
            assertFalse(found);
        });
    }

    private static boolean hasJvmArgument(String arg) {
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.equals(arg)) return true;
        }
        return false;
    }
}
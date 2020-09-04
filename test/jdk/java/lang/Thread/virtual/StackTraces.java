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
 * @modules java.base/java.lang:+open
 * @run testng StackTraces
 * @run testng/othervm -Djdk.showFullStackTrace=true StackTraces
 * @summary Test that the stack traces for carrier threads are hidden by
 *     exceptions and the StackWalker API
 */

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            boolean expected = Boolean.getBoolean("jdk.showFullStackTrace");
            assertTrue(found == expected);
        });
    }

    // carrier frames should be hidden
    public void testStackWalker1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            StackWalker walker = StackWalker.getInstance(Set.of(RETAIN_CLASS_REFERENCE));
            boolean found = walker.walk(sf ->
                    sf.map(StackWalker.StackFrame::getDeclaringClass)
                            .anyMatch(c -> c == ForkJoinPool.class));
            assertFalse(found);
        });
    }

    @Test(enabled = false)
    public void testStackWalker2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var options = Set.of(RETAIN_CLASS_REFERENCE);
            StackWalker walker = StackWalkers.newInstance(options, StackWalkers.SHOW_CARRIER_FRAMES);
            boolean found = walker.walk(sf ->
                    sf.map(StackWalker.StackFrame::getDeclaringClass)
                            .anyMatch(c -> c == ForkJoinPool.class));
            if (!found) {
                throw new RuntimeException();
            }
        });
    }

    static class StackWalkers {
        static final Object SHOW_CARRIER_FRAMES;
        static final Method NEW_INSTANCE;
        static {
            try {
                Class<?> extendedOptionClass = Class.forName("java.lang.StackWalker$ExtendedOption");

                Field f = extendedOptionClass.getDeclaredField("SHOW_CARRIER_FRAMES");
                f.setAccessible(true);
                SHOW_CARRIER_FRAMES = f.get(null);

                Method m = StackWalker.class.getDeclaredMethod("newInstance", Set.class, extendedOptionClass);
                m.setAccessible(true);
                NEW_INSTANCE = m;
            } catch (Exception e) {
                throw new Error(e);
            }
        }

        static StackWalker newInstance(Set<StackWalker.Option> options, Object extendedOption) {
            try {
                return (StackWalker) NEW_INSTANCE.invoke(null, options, extendedOption);
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

}
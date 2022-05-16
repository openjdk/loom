/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic tests for StructuredTaskScope with extent-locals
 * @enablePreview
 * @modules jdk.incubator.concurrent
 * @run testng/othervm ExtentLocalsTest
 */

import jdk.incubator.concurrent.ExtentLocal;
import jdk.incubator.concurrent.StructuredTaskScope;
import jdk.incubator.concurrent.StructureViolationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ExtentLocalsTest {

    /**
     * Test that fork inherits extent-local bindings.
     */
    @Test
    public void testForkInheritsExtentLocals1() throws Exception {
        ExtentLocal<String> NAME = ExtentLocal.newInstance();
        String value = ExtentLocal.where(NAME, "x").call(() -> {
            try (var scope = new StructuredTaskScope()) {
                Future<String> future = scope.fork(() -> {
                    // child
                    return NAME.get();
                });
                scope.join();
                return future.resultNow();
            }
        });
        assertEquals(value, "x");
    }

    /**
     * Test that fork inherits extent-local bindings into a grandchild.
     */
    @Test
    public void testForkInheritsExtentLocals2() throws Exception {
        ExtentLocal<String> NAME = ExtentLocal.newInstance();
        String value = ExtentLocal.where(NAME, "x").call(() -> {
            try (var scope1 = new StructuredTaskScope()) {
                Future<String> future1 = scope1.fork(() -> {
                    try (var scope2 = new StructuredTaskScope()) {
                        Future<String> future2 = scope2.fork(() -> {
                            // grandchild
                            return NAME.get();
                        });
                        scope2.join();
                        return future2.resultNow();
                    }
                });
                scope1.join();
                return future1.resultNow();
            }
        });
        assertEquals(value, "x");
    }

    /**
     * Test exiting an extent local operation closes the thread flock of a nested scope
     * and throws StructureViolationException.
     */
    @Test
    public void testStructureViolation1() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        class Box {
            StructuredTaskScope<Object> scope;
        }
        var box = new Box();
        try {
            try {
                ExtentLocal.where(name, "x").run(() -> {
                    box.scope = new StructuredTaskScope();
                });
                fail();
            } catch (StructureViolationException expected) { }

            // underlying flock should be closed, fork should return a cancelled task
            StructuredTaskScope<Object> scope = box.scope;
            AtomicBoolean ran = new AtomicBoolean();
            Future<String> future = scope.fork(() -> {
                ran.set(true);
                return null;
            });
            assertTrue(future.isCancelled());
            scope.join();
            assertFalse(ran.get());

        } finally {
            StructuredTaskScope<Object> scope = box.scope;
            if (scope != null) {
                scope.close();
            }
        }
    }

    /**
     * Test that fork throws StructureViolationException if extent-local bindings
     * created after StructuredTaskScope is created.
     */
    @Test
    public void testStructureViolation2() throws Exception {
        ExtentLocal<String> NAME = ExtentLocal.newInstance();

        try (var scope = new StructuredTaskScope()) {
            ExtentLocal.where(NAME, "x").run(() -> {
                assertThrows(StructureViolationException.class,
                        () -> scope.fork(() -> "foo"));
            });
        }
    }

    /**
     * Test that fork throws StructureViolationException if extent-local bindings
     * change after StructuredTaskScope is created.
     */
    @Test
    public void testStructureViolation3() throws Exception {
        ExtentLocal<String> NAME1 = ExtentLocal.newInstance();
        ExtentLocal<String> NAME2 = ExtentLocal.newInstance();

        // re-bind
        ExtentLocal.where(NAME1, "x").run(() -> {
            try (var scope = new StructuredTaskScope()) {
                ExtentLocal.where(NAME1, "y").run(() -> {
                    assertThrows(StructureViolationException.class,
                            () -> scope.fork(() -> "foo"));
                });
            }
        });

        // new binding
        ExtentLocal.where(NAME1, "x").run(() -> {
            try (var scope = new StructuredTaskScope()) {
                ExtentLocal.where(NAME2, "y").run(() -> {
                    assertThrows(StructureViolationException.class,
                            () -> scope.fork(() -> "foo"));
                });
            }
        });
    }
}
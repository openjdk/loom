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

/*
 * @test
 * @summary Test ThreadFlock with scoped values
 * @enablePreview
 * @modules java.base/jdk.internal.misc
 * @modules jdk.incubator.concurrent
 * @run testng ScopedValuesTest
 */

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import jdk.internal.misc.ThreadFlock;
import jdk.incubator.concurrent.ScopedValue;
import jdk.incubator.concurrent.StructureViolationException;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ScopedValuesTest {

    @DataProvider(name = "factories")
    public Object[][] factories() {
        var defaultThreadFactory = Executors.defaultThreadFactory();
        var virtualThreadFactory = Thread.ofVirtual().factory();
        return new Object[][]{
                { defaultThreadFactory, },
                { virtualThreadFactory, },
        };
    }

    /**
     * Test inheritance of scoped-value bindings.
     */
    @Test(dataProvider = "factories")
    public void testInheritsScopedValues(ThreadFactory factory) throws Exception {
        ScopedValue<String> NAME = ScopedValue.newInstance();
        String value = ScopedValue.where(NAME, "fred").call(() -> {
            var result = new AtomicReference<String>();
            try (var flock = ThreadFlock.open(null)) {
                Thread thread = factory.newThread(() -> {
                    // child
                    result.set(NAME.get());
                });
                flock.start(thread);
            }
            return result.get();
        });
        assertEquals(value, "fred");
    }

    /**
     * Test exiting a scoped value operation should close nested thread flocks.
     */
    @Test
    public void testStructureViolation1() {
        ScopedValue<String> name = ScopedValue.newInstance();
        class Box {
            ThreadFlock flock1;
            ThreadFlock flock2;
        }
        var box = new Box();
        try {
            ScopedValue.where(name, "x1").run(() -> {
                box.flock1 = ThreadFlock.open(null);
                box.flock2 = ThreadFlock.open(null);
            });
            fail();
        } catch (StructureViolationException expected) { }
        assertTrue(box.flock1.isClosed());
        assertTrue(box.flock2.isClosed());
    }

    /**
     * Test closing a thread flock with enclosing scoped value operations and
     * thread flocks. This test closes enclosing flock1.
     */
    @Test
    public void testStructureViolation2() {
        ScopedValue<String> name = ScopedValue.newInstance();
        try (var flock1 = ThreadFlock.open("flock1")) {
            ScopedValue.where(name, "x1").run(() -> {
                try (var flock2 = ThreadFlock.open("flock2")) {
                    ScopedValue.where(name, "x2").run(() -> {
                        try (var flock3 = ThreadFlock.open("flock3")) {
                            ScopedValue.where(name, "x3").run(() -> {
                                var flock4 = ThreadFlock.open("flock4");

                                try {
                                    flock1.close();
                                    fail();
                                } catch (StructureViolationException expected) { }

                                assertTrue(flock1.isClosed());
                                assertTrue(flock2.isClosed());
                                assertTrue(flock3.isClosed());
                                assertTrue(flock4.isClosed());

                            });
                        }
                    });
                }
            });
        }
    }

    /**
     * Test closing a thread flock with enclosing scoped value operations and
     * thread flocks. This test closes enclosing flock2.
     */
    @Test
    public void testStructureViolation3() {
        ScopedValue<String> name = ScopedValue.newInstance();
        try (var flock1 = ThreadFlock.open("flock1")) {
            ScopedValue.where(name, "x1").run(() -> {
                try (var flock2 = ThreadFlock.open("flock2")) {
                    ScopedValue.where(name, "x2").run(() -> {
                        try (var flock3 = ThreadFlock.open("flock3")) {
                            ScopedValue.where(name, "x3").run(() -> {
                                var flock4 = ThreadFlock.open("flock4");

                                try {
                                    flock2.close();
                                    fail();
                                } catch (StructureViolationException expected) { }

                                assertFalse(flock1.isClosed());
                                assertTrue(flock2.isClosed());
                                assertTrue(flock3.isClosed());
                                assertTrue(flock4.isClosed());
                            });
                        }
                    });
                }
            });
        }
    }

    /**
     * Test closing a thread flock with enclosing scoped value operations and
     * thread flocks. This test closes enclosing flock3.
     */
    @Test
    public void testStructureViolation4() {
        ScopedValue<String> name = ScopedValue.newInstance();
        try (var flock1 = ThreadFlock.open("flock1")) {
            ScopedValue.where(name, "x1").run(() -> {
                try (var flock2 = ThreadFlock.open("flock2")) {
                    ScopedValue.where(name, "x2").run(() -> {
                        try (var flock3 = ThreadFlock.open("flock3")) {
                            ScopedValue.where(name, "x3").run(() -> {
                                var flock4 = ThreadFlock.open("flock4");

                                try {
                                    flock3.close();
                                    fail();
                                } catch (StructureViolationException expected) { }

                                assertFalse(flock1.isClosed());
                                assertFalse(flock2.isClosed());
                                assertTrue(flock3.isClosed());
                                assertTrue(flock4.isClosed());
                            });
                        }
                    });
                }
            });
        }
    }

    /**
     * Test that start throws StructureViolationException if scoped-value bindings
     * have changed.
     */
    @Test(dataProvider = "factories")
    public void testStructureViolation5(ThreadFactory factory) throws Exception {
        ScopedValue<String> NAME = ScopedValue.newInstance();
        try (var flock = ThreadFlock.open(null)) {
            ScopedValue.where(NAME, "fred").run(() -> {
                Thread thread = factory.newThread(() -> { });
                expectThrows(StructureViolationException.class, () -> flock.start(thread));
            });
        }
    }
}

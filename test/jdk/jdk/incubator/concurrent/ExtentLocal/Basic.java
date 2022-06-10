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
 * @summary Basic test for ExtentLocal
 * @modules jdk.incubator.concurrent
 * @run testng Basic
 */

import jdk.incubator.concurrent.ExtentLocal;
import jdk.incubator.concurrent.StructureViolationException;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class Basic {

    @Test
    public void testUnbound1() {
        ExtentLocal<String> v = ExtentLocal.newInstance();
        assertFalse(v.isBound());
        assertThrows(NoSuchElementException.class, () -> v.get());
    }

    @Test
    public void testOrElse() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        assertFalse(name.isBound());
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        ExtentLocal.where(name, "fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
        });
    }

    @Test
    public void testOrElseThrow() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        assertFalse(name.isBound());
        assertThrows(IllegalStateException.class, () -> name.orElseThrow(IllegalStateException::new));
        assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        ExtentLocal.where(name, "fred", () -> {
            assertEquals(name.orElseThrow(IllegalStateException::new), "fred");
            assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        });
    }

    @Test
    public void testRunWithBinding1() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    @Test
    public void testRunWithBinding2() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ExtentLocal.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    @Test
    public void testRunWithBinding3() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        ExtentLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
        });
    }

    @Test
    public void testRunWithBinding4() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ExtentLocal.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    @Test
    public void testRunWithBinding9() {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        assertThrows(NullPointerException.class,
                     () -> ExtentLocal.where(name, "fred", (Runnable) null));
    }

    @Test
    public void testCallWithBinding1() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        int result = ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            return 1;
        });
        assertTrue(result == 1);
    }

    @Test
    public void testCallWithBinding2() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        int result1 = ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));

            int result2 = ExtentLocal.where(name, "joe", () -> {
                assertTrue(name.isBound());
                String value2 = name.get();
                assertTrue("joe".equals(value2));
                return 2;
            });
            assertTrue(result2 == 2);

            return 1;
        });
        assertTrue(result1 == 1);
    }

    @Test
    public void testCallWithBinding3() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        int result = ExtentLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            return 1;
        });
        assertTrue(result == 1);
    }

    @Test
    public void testCallWithBinding4() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        int result1 = ExtentLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));

            int result2 = ExtentLocal.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
                return 2;
            });
            assertTrue(result2 == 2);

            return 1;
        });
        assertTrue(result1 == 1);
    }

    @Test
    public void testCallWithBinding9() throws Exception {
        ExtentLocal<String> name = ExtentLocal.newInstance();
        assertThrows(NullPointerException.class,
                     () -> ExtentLocal.where(name, "fred", (Callable) null));
    }
}

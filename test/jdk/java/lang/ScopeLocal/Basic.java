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
 * @compile --enable-preview -source ${jdk.version} Basic.java
 * @run testng/othervm --enable-preview Basic
 * @summary Basic test for java.lang.ScopeLocal
 */

import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Basic {

    @Test(expectedExceptions = { NoSuchElementException.class })
    public void testUnbound1() {
        ScopeLocal<String> v = ScopeLocal.newInstance();
        assertFalse(v.isBound());
        v.get();
    }

    public void testOrElse() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        assertFalse(name.isBound());
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        ScopeLocal.where(name, "fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
        });
    }

    public void testOrElseThrow() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        assertFalse(name.isBound());
        assertThrows(IllegalStateException.class, () -> name.orElseThrow(IllegalStateException::new));
        assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        ScopeLocal.where(name, "fred", () -> {
            assertEquals(name.orElseThrow(IllegalStateException::new), "fred");
            assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        });
    }

    /**
     * Test runWithBinding with non-inheritable scope variable.
     */
    public void testRunWithBinding1() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    public void testRunWithBinding2() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    /**
     * Test runWithBinding with non-inheritable scope variable, null value.
     */
    public void testRunWithBinding3() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
        });
    }

    public void testRunWithBinding4() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
        });
    }

    /**
     * Test runWithBinding with inheritable scope variable.
     */

    /**
     * Test runWithBinding with null operation
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testRunWithBinding9() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", (Runnable)null);
    }

    /**
     * Test callWithBinding with non-inheritable scope variable.
     */
    public void testCallWithBinding1() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        int result = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding2() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));

            int result2 = ScopeLocal.where(name, "joe", () -> {
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

    /**
     * Test callWithBinding with non-inheritable scope variable, null value.
     */
    public void testCallWithBinding3() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        int result = ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding4() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));

            int result2 = ScopeLocal.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
                return 2;
            });
            assertTrue(result2 == 2);

            return 1;
        });
        assertTrue(result1 == 1);
    }

    /**
     * Test callWithBinding with null operation
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testCallWithBinding9() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", (Callable)null);
    }
}

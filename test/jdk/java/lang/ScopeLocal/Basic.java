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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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

    @Test(expectedExceptions = { NullPointerException.class })
    public void testRunWithBinding9() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", (Runnable)null);
    }

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

    @Test(expectedExceptions = { NullPointerException.class })
    public void testCallWithBinding9() throws Exception {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "fred", (Callable)null);
    }

    /**
     * Basic test of bind method.
     */
    public void testTryWithResources1() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        try (var binding = ScopeLocal.where(name, "x").bind()) {
            assertEquals(name.get(), "x");

            // re-bind should fail
            expectThrows(RuntimeException.class, () -> ScopeLocal.where(name, "y").bind());
            expectThrows(RuntimeException.class, () -> name.bind("y"));

            assertEquals(name.get(), "x");
        }
        assertFalse(name.isBound());
    }

    /**
     * Basic test of bind method with nested bindings.
     */
    public void testTryWithResources2() {
        ScopeLocal<String> name1 = ScopeLocal.newInstance();
        ScopeLocal<String> name2 = ScopeLocal.newInstance();
        try (var binding1 = ScopeLocal.where(name1, "x").bind()) {
            assertEquals(name1.get(), "x");
            assertFalse(name2.isBound());

            try (var binding2 = ScopeLocal.where(name2, "y").bind()) {
                assertEquals(name1.get(), "x");
                assertEquals(name2.get(), "y");
            }

            assertEquals(name1.get(), "x");
            assertFalse(name2.isBound());
        }
        assertFalse(name1.isBound());
    }

    /**
     * Basic test of re-binding after bind.
     */
    public void testTryWithResources3() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        try (var binding = ScopeLocal.where(name, "x").bind()) {
            assertEquals(name.get(), "x");

            // re-bind
            ScopeLocal.where(name, "y").run(() -> {
                assertEquals(name.get(), "y");
            });

            assertEquals(name.get(), "x");
        }
        assertFalse(name.isBound());
    }

    /**
     * Basic test that bind cannot re-bind.
     */
    public void testTryWithResources4() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        ScopeLocal.where(name, "x").run(() -> {
            assertEquals(name.get(), "x");

            // re-bind should fail
            expectThrows(RuntimeException.class, () -> ScopeLocal.where(name, "y").bind());
            expectThrows(RuntimeException.class, () -> name.bind("y"));

            assertEquals(name.get(), "x");
        });
        assertFalse(name.isBound());
    }

    /**
     * Basic test that the shorthand form of bind works.
     */
    public void testTryWithResources5() {
        ScopeLocal<String> name = ScopeLocal.newInstance();
        try (var binding = name.bind("x")) {
            assertEquals(name.get(), "x");

            // re-bind should fail
            expectThrows(RuntimeException.class, () -> ScopeLocal.where(name, "y").bind());

            assertEquals(name.get(), "x");
        }
        assertFalse(name.isBound());
    }

    /**
     * Test closing enclosing binder, should close nested binder.
     */
    public void testStructureViolation1() {
        ScopeLocal<String> NAME1 = ScopeLocal.newInstance();
        ScopeLocal<String> NAME2 = ScopeLocal.newInstance();
        try (var binding1 = ScopeLocal.where(NAME1, "x").bind()) {
            try (var binding2 = ScopeLocal.where(NAME2, "y").bind()) {
                expectThrows(StructureViolationException.class, binding1::close);

                // binding1 and binding2 should be removed
                assertFalse(NAME1.isBound());
                assertFalse(NAME2.isBound());
            }
        }
    }

    /**
     * Test closing enclosing binder, should not disrupt nested binding
     * when running an op.
     */
    public void testStructureViolation2() {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();
        try (var binding = ScopeLocal.where(NAME, "x").bind()) {
            ScopeLocal.where(NAME, "y").run(() -> {
                expectThrows(StructureViolationException.class, binding::close);
                assertEquals(NAME.get(), "y");
            });
            assertFalse(NAME.isBound());
        }
    }

    /**
     * Test that close is idempotent.
     */
    public void testCloseIsIdempotent() {
        ScopeLocal<String> NAME1 = ScopeLocal.newInstance();
        ScopeLocal<String> NAME2 = ScopeLocal.newInstance();
        try (var binding1 = ScopeLocal.where(NAME1, "x").bind()) {
            try (var binding2 = ScopeLocal.where(NAME2, "y").bind()) {

                assertTrue(NAME1.isBound());
                assertTrue(NAME2.isBound());

                // call binding2::close several times.
                for (int i = 0; i < 3; i++) {
                    binding2.close();
                    assertTrue(NAME1.isBound());
                    assertFalse(NAME2.isBound());
                }

                // call binding1::close several times.
                for (int i = 0; i < 3; i++) {
                    binding1.close();
                    assertFalse(NAME1.isBound());
                    assertFalse(NAME2.isBound());
                }

                // call binding2::close again, should not throw
                binding2.close();
            }
        }
    }

    /**
     * Test that WrongThreadException if close is invoked by a thread other than the owner.
     */
    public void testCloseConfined() {
        ScopeLocal<String> NAME = ScopeLocal.newInstance();
        try (var binding = ScopeLocal.where(NAME, "x").bind();
             var executor = Executors.newFixedThreadPool(1)) {

            // attempt to close binding from wrong thread
            Future<?> future = executor.submit(binding::close);
            Throwable ex = expectThrows(ExecutionException.class, future::get);
            assertTrue(ex.getCause() instanceof WrongThreadException);
        }
    }
}

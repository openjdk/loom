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
 * @run testng Basic
 * @summary Basic test for java.lang.Scoped
 */

import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Basic {

    @Test(expectedExceptions = { NoSuchElementException.class })
    public void testUnbound1() {
        Scoped<String> v = Scoped.forType(String.class);
        assertFalse(v.isBound());
        v.get();
    }

    @Test(expectedExceptions = { NoSuchElementException.class })
    public void testUnbound2() {
        Scoped<String> v = Scoped.inheritableForType(String.class);
        assertFalse(v.isBound());
        v.get();
    }

    public void testOrElse() {
        Scoped<String> name = Scoped.forType(String.class);
        assertFalse(name.isBound());
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        name.runWithBinding("fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
        });
    }

    public void testOrElseThrow() {
        Scoped<String> name = Scoped.forType(String.class);
        assertFalse(name.isBound());
        assertThrows(IllegalStateException.class, () -> name.orElseThrow(IllegalStateException::new));
        assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        name.runWithBinding("fred", () -> {
            assertEquals(name.orElseThrow(IllegalStateException::new), "fred");
            assertThrows(NullPointerException.class, () -> name.orElseThrow(null));
        });
    }

    /**
     * Test runWithBinding with non-inheritable scope variable.
     */
    public void testRunWithBinding1() {
        Scoped<String> name = Scoped.forType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureNotInherited(name);
        });
    }

    public void testRunWithBinding2() {
        Scoped<String> name = Scoped.forType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            name.runWithBinding("joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
                ensureNotInherited(name);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureNotInherited(name);
        });
    }

    /**
     * Test runWithBinding with non-inheritable scope variable, null value.
     */
    public void testRunWithBinding3() {
        Scoped<String> name = Scoped.forType(String.class);
        name.runWithBinding(null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureNotInherited(name);
        });
    }

    public void testRunWithBinding4() {
        Scoped<String> name = Scoped.forType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            name.runWithBinding(null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
                ensureNotInherited(name);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureNotInherited(name);
        });
    }

    /**
     * Test runWithBinding with inheritable scope variable.
     */
    public void testRunWithBinding5() {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureInherited(name);
        });
    }

    public void testRunWithBinding6() {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            name.runWithBinding("joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
                ensureInherited(name);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureInherited(name);
        });
    }

    /**
     * Test runWithBinding with inheritable scope variable, null value.
     */
    public void testRunWithBinding7() {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        name.runWithBinding(null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureInherited(name);
        });
    }

    public void testRunWithBinding8() {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        name.runWithBinding("fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            name.runWithBinding(null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
                ensureInherited(name);
            });

            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureInherited(name);
        });
    }

    /**
     * Test runWithBinding with null operation
     */
    @Test(expectedExceptions = { NullPointerException.class })
    public void testRunWithBinding9() {
        Scoped<String> name = Scoped.forType(String.class);
        name.runWithBinding("fred", null);
    }

    /**
     * Test callWithBinding with non-inheritable scope variable.
     */
    public void testCallWithBinding1() throws Exception {
        Scoped<String> name = Scoped.forType(String.class);
        int result = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            ensureNotInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding2() throws Exception {
        Scoped<String> name = Scoped.forType(String.class);
        int result1 = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureNotInherited(name);

            int result2 = name.callWithBinding("joe", () -> {
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
        Scoped<String> name = Scoped.forType(String.class);
        int result = name.callWithBinding(null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureNotInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding4() throws Exception {
        Scoped<String> name = Scoped.forType(String.class);
        int result1 = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureNotInherited(name);

            int result2 = name.callWithBinding(null, () -> {
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
     * Test callWithBinding with inheritable scope variable.
     */
    public void testCallWithBinding5() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        int result = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            ensureInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding6() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        int result1 = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureInherited(name);

            int result2 = name.callWithBinding("joe", () -> {
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
     * Test callWithBinding with inheritable scope variable, null value.
     */
    public void testCallWithBinding7() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        int result = name.callWithBinding(null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding8() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        int result1 = name.callWithBinding("fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureInherited(name);

            int result2 = name.callWithBinding(null, () -> {
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
        Scoped<String> name = Scoped.forType(String.class);
        name.callWithBinding("fred", null);
    }

    /**
     * Test that inheritable scope variable are inherited at Thread create time.
     */
    public void testInheritAtCreateTime() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        name.callWithBinding("fred", () -> {
            AtomicReference<String> ref = new AtomicReference<>();
            Thread thread = new Thread(() -> ref.set(name.get()));
            // start thread with name set to joe
            name.runWithBinding("joe", thread::start);
            thread.join();
            assertEquals(ref.get(), "fred");
            return null;
        });
    }

    /**
     * Test snapshot inheritance.
     */
    public void testSnapshotInheritance() throws Exception {
        Scoped<String> name = Scoped.inheritableForType(String.class);
        Scoped<String> occupation = Scoped.inheritableForType(String.class);
        var snapshot = name.callWithBinding("aristotle", () -> Scoped.snapshot());
        assertFalse(name.isBound());
        assertBoundInSnapshot(snapshot, name, true);
        occupation.callWithBinding("undertaker", () -> {
            assertBoundInSnapshot(snapshot, occupation, false);
            assertEquals(occupation.get(), "undertaker");
            assertTrue(occupation.isBound());
            return null;
        });
        assertEqualsInSnapshot(snapshot, name, "aristotle");
    }

    /**
     * Test for snapshot non-inheritance.
     */
    public void testSnapshotNonInheritance() throws Exception {
        Scoped<String> name = Scoped.forType(String.class);
        Scoped<String> occupation = Scoped.forType(String.class);
        var snapshot = name.callWithBinding("aristotle", () -> Scoped.snapshot());
        assertFalse(name.isBound());
        assertBoundInSnapshot(snapshot, name, false);
        occupation.callWithBinding("undertaker", () -> {
            assertBoundInSnapshot(snapshot, occupation, true);
            assertEquals(occupation.get(), "undertaker");
            assertEqualsInSnapshot(snapshot, occupation, "undertaker");
            assertTrue(occupation.isBound());
            return null;
        });
        name.callWithBinding("joe", () -> {
            assertEqualsInSnapshot(snapshot, name, "joe");
            return null;
        });
    }

    private <T> void assertEqualsInSnapshot(Scoped.Snapshot snapshot, Scoped<T> var, T expected)
            throws Exception {
        snapshot.callWithSnapshot(() -> {
            assertEquals(var.get(), expected);
            return null;
        });
    }

    private <T> void assertBoundInSnapshot(Scoped.Snapshot snapshot, Scoped<T> var, boolean expected)
            throws Exception {
        snapshot.callWithSnapshot(() -> {
            assertEquals(var.isBound(), expected);
            return null;
        });
    }

    /**
     * Ensures that a inheritable scope variable is inherited
     */
    private void ensureInherited(Scoped<?> v) {
        Object valueInParent = v.get();

        // check inherited by platform thread
        ThreadFactory factory = Thread.builder().factory();
        try (var executor = Executors.newThreadExecutor(factory)) {
            Object valueInChild = executor.submit(v::get).join();
            assertEquals(valueInChild, valueInParent);
        }

        // check inherited by virtual thread
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Object valueInChild = executor.submit(v::get).join();
            assertEquals(valueInChild, valueInParent);
        }
    }

    /**
     * Ensures that a non-inheritable scope variable is not inherited
     */
    private void ensureNotInherited(Scoped<?> v) {
        assertTrue(v.isBound());

        // check not inherited by platform thread
        ThreadFactory factory = Thread.builder().factory();
        try (var executor = Executors.newThreadExecutor(factory)) {
            boolean boundInChild = executor.submit(v::isBound).join();
            assertFalse(boundInChild);
        }

        // check no inherited by virtual thread
        try (var executor = Executors.newVirtualThreadExecutor()) {
            boolean boundInChild = executor.submit(v::isBound).join();
            assertFalse(boundInChild);
        }
    }
}

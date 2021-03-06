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
        ScopeLocal<String> v = ScopeLocal.forType(String.class);
        assertFalse(v.isBound());
        v.get();
    }

    @Test(expectedExceptions = { NoSuchElementException.class })
    public void testUnbound2() {
        ScopeLocal<String> v = ScopeLocal.inheritableForType(String.class);
        assertFalse(v.isBound());
        v.get();
    }

    public void testOrElse() {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        assertFalse(name.isBound());
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        ScopeLocal.where(name, "fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
        });
    }

    public void testOrElseThrow() {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureNotInherited(name);
        });
    }

    public void testRunWithBinding2() {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, "joe", () -> {
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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureNotInherited(name);
        });
    }

    public void testRunWithBinding4() {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, null, () -> {
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
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));
            ensureInherited(name);
        });
    }

    public void testRunWithBinding6() {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, "joe", () -> {
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
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureInherited(name);
        });
    }

    public void testRunWithBinding8() {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertTrue("fred".equals(name.get()));

            ScopeLocal.where(name, null, () -> {
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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, "fred", (Runnable)null);
    }

    /**
     * Test callWithBinding with non-inheritable scope variable.
     */
    public void testCallWithBinding1() throws Exception {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        int result = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            ensureNotInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding2() throws Exception {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureNotInherited(name);

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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        int result = ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureNotInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding4() throws Exception {
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureNotInherited(name);

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
     * Test callWithBinding with inheritable scope variable.
     */
    public void testCallWithBinding5() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        int result = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value = name.get();
            assertTrue("fred".equals(value));
            ensureInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding6() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureInherited(name);

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
     * Test callWithBinding with inheritable scope variable, null value.
     */
    public void testCallWithBinding7() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        int result = ScopeLocal.where(name, null, () -> {
            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            ensureInherited(name);
            return 1;
        });
        assertTrue(result == 1);
    }

    public void testCallWithBinding8() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        int result1 = ScopeLocal.where(name, "fred", () -> {
            assertTrue(name.isBound());
            String value1 = name.get();
            assertTrue("fred".equals(value1));
            ensureInherited(name);

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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal.where(name, "fred", (Callable)null);
    }

    /**
     * Test that inheritable scope variable are inherited at Thread create time.
     */
    public void testInheritAtCreateTime() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal.where(name, "fred", () -> {
            AtomicReference<String> ref = new AtomicReference<>();
            Thread thread = new Thread(() -> ref.set(name.get()));
            // start thread with name set to joe
            ScopeLocal.where(name, "joe", thread::start);
            thread.join();
            assertEquals(ref.get(), "fred");
            return null;
        });
    }

    /**
     * Test snapshot inheritance.
     */
    public void testSnapshotInheritance() throws Exception {
        ScopeLocal<String> name = ScopeLocal.inheritableForType(String.class);
        ScopeLocal<String> occupation = ScopeLocal.inheritableForType(String.class);
        var snapshot = ScopeLocal.where(name, "aristotle", () -> ScopeLocal.snapshot());
        assertFalse(name.isBound());
        assertBoundInSnapshot(snapshot, name, true);
        ScopeLocal.where(occupation, "undertaker", () -> {
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
        ScopeLocal<String> name = ScopeLocal.forType(String.class);
        ScopeLocal<String> occupation = ScopeLocal.forType(String.class);
        var snapshot = ScopeLocal.where(name, "aristotle", () -> ScopeLocal.snapshot());
        assertFalse(name.isBound());
        assertBoundInSnapshot(snapshot, name, false);
        ScopeLocal.where(occupation, "undertaker", () -> {
            assertBoundInSnapshot(snapshot, occupation, true);
            assertEquals(occupation.get(), "undertaker");
            assertEqualsInSnapshot(snapshot, occupation, "undertaker");
            assertTrue(occupation.isBound());
            return null;
        });
        ScopeLocal.where(name, "joe", () -> {
            assertEqualsInSnapshot(snapshot, name, "joe");
            return null;
        });
    }

    private <R> R callWithSnapshot(ScopeLocal.Snapshot snapshot, Callable<R> c) {
        try {
            return snapshot.call(c);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <T> void assertEqualsInSnapshot(ScopeLocal.Snapshot snapshot, ScopeLocal<T> var, T expected)
            throws Exception {
        callWithSnapshot(snapshot, () -> {
            assertEquals(var.get(), expected);
            return null;
        });
    }

    private <T> void assertBoundInSnapshot(ScopeLocal.Snapshot snapshot, ScopeLocal<T> var, boolean expected)
            throws Exception {
        callWithSnapshot(snapshot, () -> {
            assertEquals(var.isBound(), expected);
            return null;
        });
    }

    /**
     * Ensures that a inheritable scope variable is inherited
     */
    private void ensureInherited(ScopeLocal<?> v) {
        Object valueInParent = v.get();

        // check inherited by platform thread
        var platformThreadFactory = Thread.ofPlatform().factory();
        try (var executor = Executors.newThreadPerTaskExecutor(platformThreadFactory)) {
            Object valueInChild = executor.submit(v::get).join();
            assertEquals(valueInChild, valueInParent);
        }

        // check inherited by virtual thread
        var virtualThreadFactory = Thread.ofVirtual().factory();
        try (var executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
            Object valueInChild = executor.submit(v::get).join();
            assertEquals(valueInChild, valueInParent);
        }
    }

    /**
     * Ensures that a non-inheritable scope variable is not inherited
     */
    private void ensureNotInherited(ScopeLocal<?> v) {
        assertTrue(v.isBound());

        // check not inherited by platform thread
        var platformThreadFactory = Thread.ofPlatform().factory();
        try (var executor = Executors.newThreadPerTaskExecutor(platformThreadFactory)) {
            boolean boundInChild = executor.submit(v::isBound).join();
            assertFalse(boundInChild);
        }

        // check no inherited by virtual thread
        var virtualThreadFactory = Thread.ofVirtual().factory();
        try (var executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
            boolean boundInChild = executor.submit(v::isBound).join();
            assertFalse(boundInChild);
        }
    }
}

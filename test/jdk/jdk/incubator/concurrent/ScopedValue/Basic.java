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
 * @summary Basic test for ScopedValue
 * @modules jdk.incubator.concurrent
 * @run testng Basic
 */

import jdk.incubator.concurrent.ScopedValue;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Basic {

    /**
     * Test isBound from runnable op.
     */
    public void testIsBoundInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertFalse(name.isBound());
        ScopedValue.where(name, "fred", () -> assertTrue(name.isBound()));
    }

    /**
     * Test isBound from callable op.
     */
    public void testIsBoundInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertFalse(name.isBound());
        boolean isBound = ScopedValue.where(name, "fred", name::isBound);
        assertTrue(isBound);
    }

    /**
     * Test get from runnable op.
     */
    public void testGetInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertThrows(NoSuchElementException.class, name::get);
        ScopedValue.where(name, "fred", () -> assertEquals(name.get(), "fred"));
    }

    /**
     * Test get from callable op.
     */
    public void testGetInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertThrows(NoSuchElementException.class, name::get);
        String value = ScopedValue.where(name, "fred", name::get);
        assertEquals(value, "fred");
    }

    /**
     * Test orElse from runnable op.
     */
    public void testOrElseInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        ScopedValue.where(name, "fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
        });
    }

    /**
     * Test orElse from callable op.
     */
    public void testOrElseInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        assertTrue(name.orElse(null) == null);
        assertEquals(name.orElse("default"), "default");
        var ignore = ScopedValue.where(name, "fred", () -> {
            assertEquals(name.orElse(null), "fred");
            assertEquals(name.orElse("default"), "fred");
            return null;
        });
    }

    /**
     * Test orElseThrow from runnable op.
     */
    public void testOrElseThrowInRun() {
        class FooException extends RuntimeException { }
        ScopedValue<String> name = ScopedValue.newInstance();
        assertThrows(FooException.class, () -> name.orElseThrow(FooException::new));
        ScopedValue.where(name, "fred", () -> {
            assertEquals(name.orElseThrow(FooException::new), "fred");
        });
    }

    /**
     * Test orElseThrow from callable op.
     */
    public void testOrElseThrowInCall() throws Exception {
        class FooException extends RuntimeException { }
        ScopedValue<String> name = ScopedValue.newInstance();
        assertThrows(FooException.class, () -> name.orElseThrow(FooException::new));
        var ignore = ScopedValue.where(name, "fred", () -> {
            assertEquals(name.orElseThrow(FooException::new), "fred");
            return null;
        });
    }

    /**
     * Test more than one binding in runnable op.
     */
    public void testMultipleBindingsInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue<Integer> age = ScopedValue.newInstance();
        ScopedValue.where(name, "fred").where(age, 100).run(() -> {
            assertTrue(name.isBound());
            assertTrue(age.isBound());
            assertEquals(name.get(), "fred");
            assertEquals((int) age.get(), 100);
        });
        assertFalse(name.isBound());
        assertFalse(age.isBound());
    }

    /**
     * Test more than one binding in callable op.
     */
    public void testMultipleBindingsInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue<Integer> age = ScopedValue.newInstance();
        var ignore = ScopedValue.where(name, "fred").where(age, 100).call(() -> {
            assertTrue(name.isBound());
            assertTrue(age.isBound());
            assertEquals(name.get(), "fred");
            assertEquals((int) age.get(), 100);
            return null;
        });
        assertFalse(name.isBound());
        assertFalse(age.isBound());
    }

    /**
     * Test rebinding in a runnable task.
     */
    public void testRebindingInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");

            ScopedValue.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
            });

            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test rebinding from null to another value in a runnable task.
     */
    public void testRebindingFromNullInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue.where(name, null, () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), null);

            ScopedValue.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
            });

            assertTrue(name.isBound());
            assertTrue(name.get() == null);
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test rebinding to null in a runnable task.
     */
    public void testRebindingToNullInRun() {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");

            ScopedValue.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
            });

            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test rebinding in a callable task.
     */
    public void testRebindingInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        var ignore1 = ScopedValue.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");

            var ignore2 = ScopedValue.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
                return null;
            });

            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");
            return null;
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test rebinding from null to another value in a callable task.
     */
    public void testRebindingFromNullInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        var ignore1 = ScopedValue.where(name, null, () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), null);

            var ignore2 = ScopedValue.where(name, "joe", () -> {
                assertTrue(name.isBound());
                assertTrue("joe".equals(name.get()));
                return null;
            });

            assertTrue(name.isBound());
            assertTrue(name.get() == null);
            return null;
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test rebinding to null in a callable task.
     */
    public void testRebindingToNullInCall() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        var ignore1 = ScopedValue.where(name, "fred", () -> {
            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");

            var ignore2 = ScopedValue.where(name, null, () -> {
                assertTrue(name.isBound());
                assertTrue(name.get() == null);
                return null;
            });

            assertTrue(name.isBound());
            assertEquals(name.get(), "fred");
            return null;
        });
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test runnable op throwing exception.
     */
    public void testRunThrows() {
        class FooException extends RuntimeException { }
        ScopedValue<String> name = ScopedValue.newInstance();
        Runnable op = () -> { throw new FooException(); };
        assertThrows(FooException.class, () -> ScopedValue.where(name, "fred", op));
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test callable op throwing exception.
     */
    public void testCallThrows() {
        class FooException extends RuntimeException { }
        ScopedValue<String> name = ScopedValue.newInstance();
        Callable<Void> op = () -> { throw new FooException(); };
        assertThrows(FooException.class, () -> ScopedValue.where(name, "fred", op));
        assertFalse(name.isBound()); // no longer bound
    }

    /**
     * Test Carrier.get.
     */
    public void testCarrierGet() throws Exception {
        ScopedValue<String> name = ScopedValue.newInstance();
        ScopedValue<Integer> age = ScopedValue.newInstance();

        // one scoped value
        var carrier1 = ScopedValue.where(name, "fred");
        assertEquals(carrier1.get(name), "fred");
        assertThrows(NoSuchElementException.class, () -> carrier1.get(age));

        // two scoped values
        var carrier2 = carrier1.where(age, 20);
        assertEquals(carrier2.get(name), "fred");
        assertEquals((int) carrier2.get(age), 20);
    }

    /**
     * Test NullPointerException.
     */
    public void testNullPointerException() {
        ScopedValue<String> name = ScopedValue.newInstance();

        assertThrows(NullPointerException.class, () -> ScopedValue.where(null, "value"));
        assertThrows(NullPointerException.class, () -> ScopedValue.where(null, "value", () -> { }));
        assertThrows(NullPointerException.class, () -> ScopedValue.where(null, "value", () -> null));

        assertThrows(NullPointerException.class, () -> name.orElseThrow(null));

        var carrier = ScopedValue.where(name, "fred");
        assertThrows(NullPointerException.class, () -> carrier.where(null, "value"));
        assertThrows(NullPointerException.class, () -> carrier.get(null));
        assertThrows(NullPointerException.class, () -> carrier.run(null));
        assertThrows(NullPointerException.class, () -> carrier.call(null));
    }
}

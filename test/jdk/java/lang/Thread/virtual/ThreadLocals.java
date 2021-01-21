/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng ThreadLocals
 * @summary Test Virtual threads using thread locals
 */

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ThreadLocals {
    static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    static final ThreadLocal<Object> INHERITED_LOCAL = new InheritableThreadLocal<>();

    public void testThreadLocal1() throws Exception {
        for (int i = 0; i < 10; i++) {
            TestHelper.runInVirtualThread(() -> {
                assertTrue(LOCAL.get() == null);
                Object obj = new Object();
                LOCAL.set(obj);
                assertTrue(LOCAL.get() == obj);
            });
        }
    }

    public void testThreadLocal2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            assertTrue(LOCAL.get() == null);
            Object obj = new Object();
            LOCAL.set(obj);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            assertTrue(LOCAL.get() == obj);
        });
    }

    // Thread cannot set values for its copy of thread-locals.
    public void testThreadLocal3() throws Exception {
        Object INITIAL_VALUE = new Object();
        ThreadLocal<Object> LOCAL2 = new ThreadLocal<>() {
            @Override
            protected Object initialValue() {
                return INITIAL_VALUE;
            }
        };
        ThreadLocal<Object> INHERITED_LOCAL2 = new InheritableThreadLocal<>()  {
            @Override
            protected Object initialValue() {
                return INITIAL_VALUE;
            }
        };

        TestHelper.runInVirtualThread(TestHelper.NO_THREAD_LOCALS, () -> {
            assertThrows(UnsupportedOperationException.class, () -> LOCAL.set(null));
            assertThrows(UnsupportedOperationException.class, () -> LOCAL.set(new Object()));
            assertTrue(LOCAL.get() == null);
            LOCAL.remove();  // should not throw

            assertThrows(UnsupportedOperationException.class, () -> LOCAL2.set(null));
            assertThrows(UnsupportedOperationException.class, () -> LOCAL2.set(new Object()));
            assertTrue(LOCAL2.get() == INITIAL_VALUE);
            LOCAL2.remove();  // should not throw

            assertThrows(UnsupportedOperationException.class, () -> INHERITED_LOCAL.set(null));
            assertThrows(UnsupportedOperationException.class, () -> INHERITED_LOCAL.set(new Object()));
            assertTrue(INHERITED_LOCAL.get() == null);
            INHERITED_LOCAL.remove();  // should not throw

            assertThrows(UnsupportedOperationException.class, () -> INHERITED_LOCAL2.set(null));
            assertThrows(UnsupportedOperationException.class, () -> INHERITED_LOCAL2.set(new Object()));
            assertTrue(INHERITED_LOCAL2.get() == INITIAL_VALUE);
            INHERITED_LOCAL2.remove();  // should not throw
        });
    }

    public void testInheritedThreadLocal1() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        for (int i = 0; i < 10; i++) {
            TestHelper.runInVirtualThread(() -> {
                assertTrue(INHERITED_LOCAL.get() == null);
                Object obj = new Object();
                INHERITED_LOCAL.set(obj);
                assertTrue(INHERITED_LOCAL.get() == obj);
            });
        }
        assertTrue(INHERITED_LOCAL.get() == null);
    }

    // initial value inherited from kernel thread
    public void testInheritedThreadLocal2() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        var obj = new Object();
        INHERITED_LOCAL.set(obj);
        try {
            TestHelper.runInVirtualThread(() -> {
                assertTrue(INHERITED_LOCAL.get() == obj);
            });
        } finally {
            INHERITED_LOCAL.remove();
        }
    }

    // initial value inherited from virtual thread
    public void testInheritedThreadLocal3() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        TestHelper.runInVirtualThread(() -> {
            var obj = new Object();
            INHERITED_LOCAL.set(obj);
            TestHelper.runInVirtualThread(() -> {
                assertTrue(INHERITED_LOCAL.get() == obj);
            });
            assertTrue(INHERITED_LOCAL.get() == obj);

        });
        assertTrue(INHERITED_LOCAL.get() == null);
    }

    // initial value not inherited from kernel thread
    public void testInheritedThreadLocal4() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        var obj = new Object();
        INHERITED_LOCAL.set(obj);
        try {
            int characteristics = TestHelper.NO_INHERIT_INHERITABLE_THREAD_LOCALS;
            TestHelper.runInVirtualThread(characteristics, () -> {
                assertTrue(INHERITED_LOCAL.get() == null);
            });
        } finally {
            INHERITED_LOCAL.remove();
        }
    }

    // initial value not inherited from virtual thread
    public void testInheritedThreadLocal5() throws Exception {
        assertTrue(INHERITED_LOCAL.get() == null);
        TestHelper.runInVirtualThread(() -> {
            var obj = new Object();
            INHERITED_LOCAL.set(obj);
            int characteristics = TestHelper.NO_INHERIT_INHERITABLE_THREAD_LOCALS;
            TestHelper.runInVirtualThread(characteristics, () -> {
                assertTrue(INHERITED_LOCAL.get() == null);
            });
            assertTrue(INHERITED_LOCAL.get() == obj);

        });
        assertTrue(INHERITED_LOCAL.get() == null);
    }
}
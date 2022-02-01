/*
 * Copyright (c) 2021, Red Hat, Inc. All rights reserved.
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
 * @compile --enable-preview -source ${jdk.version} Stress.java
 * @run testng/othervm --enable-preview Stress
 * @summary Stress test for java.lang.ScopeLocal
 */

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Stress {

    private ScopeLocal<Integer> sl1 = ScopeLocal.newInstance();
    private ScopeLocal<Integer> sl2 = ScopeLocal.newInstance();

    private final ScopeLocal<Integer>[] scopeLocals;

    Stress() {
        scopeLocals = new ScopeLocal[500];
        for (int i = 0; i < scopeLocals.length; i++) {
            scopeLocals[i] = ScopeLocal.newInstance();
        }
    }

    private int deepBindings(int depth) {
        try {
            if (depth > 0) {
                try (var unused = scopeLocals[depth].bind(depth)) {
                    var vx = scopeLocals[depth].get();
                    return ScopeLocal.where(sl1, sl1.get() + 1)
                            .where(scopeLocals[depth], scopeLocals[depth].get() * 2)
                            .call(() -> scopeLocals[depth].get() + deepBindings(depth - 1) + sl1.get());
                }
            } else {
                return sl2.get();
            }
        } catch (Exception foo) {
            return 0;
        }
    }

    public void deepBindings() {
        int result;
        try {
            result = ScopeLocal.where(sl2, 42).where(sl1, 99).call(() ->
                    deepBindings(scopeLocals.length - 1));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(result, 423693);
    }

    private static final ScopeLocal<ThreadFactory> factory = ScopeLocal.newInstance();
    private static final ScopeLocal.Carrier platformFactoryCarrier = ScopeLocal.where(factory, Thread.ofPlatform().factory());
    private static final ScopeLocal.Carrier virtualFactoryCarrier = ScopeLocal.where(factory, Thread.ofVirtual().factory());

    private int deepBindings2(int depth) throws Exception {
        if (depth > 0) {
            try (var unused = scopeLocals[depth].bind(depth);
                 var scope = new StructuredTaskScope<Integer>(null, factory.get())) {
                var future = scope.fork(
                    () -> ScopeLocal.where(sl1, sl1.get() + 1)
                            .where(scopeLocals[depth], scopeLocals[depth].get() * 2)
                            .call(() -> scopeLocals[depth].get() + deepBindings2(depth - 1) + sl1.get()));
                scope.join();
                return future.get();
            }
        } else {
            return sl2.get();
        }
    }

    public void manyScopeLocals() {
        ScopeLocal<Object>[] scopeLocals = new ScopeLocal[10_000];
        ScopeLocal.Binder[] binders = new ScopeLocal.Binder[scopeLocals.length];

        for (int i = 0; i < scopeLocals.length; i++) {
            scopeLocals[i] = ScopeLocal.newInstance();
            binders[i] = scopeLocals[i].bind(i);
        }
        long n = 0;
        for (var sl : scopeLocals) {
            n += (Integer)sl.get();
        }
        assertEquals(n, 49995000);
        for (int i = scopeLocals.length - 1; i >= 0; --i) {
            binders[i].close();
        }
        for (int i = 0; i < scopeLocals.length; i++) {
            binders[i] = scopeLocals[i].bind(i);
        }
        int caught = 0;
        for (int i = scopeLocals.length - 2; i >= 0; i -= 2) {
            try {
                binders[i].close();
            } catch (StructureViolationException x) {
                caught++;
            }
        }
        System.out.println(caught);
        assertEquals(caught, 5000);

        // They should all be closed now
        caught = 0;
        for (int i = scopeLocals.length - 1; i >= 0; --i) {
            binders[i].close();
            try {
                binders[i].close();
            } catch (StructureViolationException x) {
                caught++;
            }
        }
        assertEquals(caught, 0);
    }

    private void test(ScopeLocal.Carrier factoryCarrier) {
        int val = 0;
        try (var unused = factoryCarrier.where(sl2, 42).where(sl1, 99).bind()) {
            val = deepBindings2(scopeLocals.length - 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(val, 423693);
    }

    public void run() {
        test(platformFactoryCarrier);
        test(virtualFactoryCarrier);
    }
}

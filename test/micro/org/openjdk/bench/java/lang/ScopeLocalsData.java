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

package org.openjdk.bench.java.lang;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("preview")
public class ScopeLocalsData {

    static final ScopeLocal<Integer> sl1 = ScopeLocal.forType(Integer.class);
    static final ThreadLocal<Integer> tl1 = new ThreadLocal<>();

    static final ScopeLocal<Integer> sl2 = ScopeLocal.forType(Integer.class);
    static final ScopeLocal<Integer> sl3 = ScopeLocal.forType(Integer.class);
    static final ScopeLocal<Integer> sl4 = ScopeLocal.forType(Integer.class);
    static final ScopeLocal<Integer> sl5 = ScopeLocal.forType(Integer.class);
    static final ScopeLocal<Integer> sl6 = ScopeLocal.forType(Integer.class);
    static final ScopeLocal<AtomicInteger> sl_atomicInt = ScopeLocal.forType(AtomicInteger.class);

    static final ScopeLocal<AtomicReference<Integer>> sl_atomicRef = ScopeLocal.forType(AtomicReference.class);

    static final ThreadLocal<Integer> tl2 = new ThreadLocal<>();
    static final ThreadLocal<Integer> tl3 = new ThreadLocal<>();
    static final ThreadLocal<Integer> tl4 = new ThreadLocal<>();
    static final ThreadLocal<Integer> tl5 = new ThreadLocal<>();
    static final ThreadLocal<Integer> tl6 = new ThreadLocal<>();
    static final ThreadLocal<AtomicInteger> tl_atomicInt = new ThreadLocal<>();

    static final ScopeLocal.Snapshot aSnapshot;

    static {
        try {
            aSnapshot = ScopeLocal.where(sl1, 99,
                    () -> ScopeLocal.snapshot());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void run(Runnable action) {
        try {
            tl1.set(42); tl2.set(2); tl3.set(3); tl4.set(4); tl5.set(5); tl6.set(6);
            tl_atomicInt.set(new AtomicInteger());
            ScopeLocal.where(sl1, 42).where(sl2, 2).where(sl3, 3)
                    .where(sl4, 4).where(sl5, 5).where(sl6, 6)
                    .where(sl_atomicInt, new AtomicInteger())
                    .where(sl_atomicRef, new AtomicReference<>())
                    .run(action);
        } finally {
            tl1.remove(); tl2.remove(); tl3.remove(); tl4.remove(); tl5.remove(); tl6.remove();
            tl_atomicInt.remove();
        }
    }
}


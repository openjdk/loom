/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package java.lang;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * TBD
 * */
public final class Scoped<T> {

    final Class<? super T> type;

    static final class Binding<T> {
        final Scoped<T> key;
        final T value;
        final Binding<?> prev;

        Binding(Scoped<T> key, T value, Binding<?> prev) {
            key.type.cast(value);
            this.key = key;
            this.value = value;
            this.prev = prev;
        }

        final T get() {
            return value;
        }

        final Scoped<T> getKey() {
            return key;
        }
    }

    private Scoped(Class<? super T> type) {
        this.type = type;
    }

    /**
     * TBD
     *
     * @param <T>   TBD
     * @param <U>   TBD
     * @param type TBD
     * @return TBD
     */
    public static <U,T extends U> Scoped<T> forType(Class<U> type) {
        return new Scoped<T>(type);
    }

    /**
     * TBD
     *
     * @return TBD
     */
    @SuppressWarnings("unchecked")
    public boolean isBound() {
        for (var b = Thread.currentThread().scopeLocalBindings;
             b != null; b = b.prev) {
            if (b.getKey() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * TBD
     *
     * @return TBD
     */
    @SuppressWarnings("unchecked")
    public T get() {
        for (var b = Thread.currentThread().scopeLocalBindings;
             b != null; b = b.prev) {
            if (b.getKey() == this) {
                return (T)(b.get());
            }
        }
        throw new RuntimeException("unbound");
    }

    /**
     * TBD
     *
     * @param r TBD
     * @param value   TBD
     */
    public void runWithBinding(T value, Runnable r) {
        Binding<?> top = Thread.currentThread().scopeLocalBindings;
        try {
            Thread.currentThread().scopeLocalBindings =
                    new Binding<T>(this, value, top);
            r.run();
        } finally {
            assert(top == Thread.currentThread().scopeLocalBindings.prev);
            Thread.currentThread().scopeLocalBindings = top;
        }
    }

    /**
     * TBD
     *
     * @param <T>   TBD
     * @param <X>   TBD
     * @param r TBD
     * @param value TBD
     * @return TBD
     * @throws Exception TBD
     */
    public <X> X callWithBinding(T value, Callable<X> r) throws Exception {
        Binding<?> top = Thread.currentThread().scopeLocalBindings;
        try {
            Thread.currentThread().scopeLocalBindings =
                    new Binding<T>(this, value, top);
            return r.call();
        } finally {
            Thread.currentThread().scopeLocalBindings = top;
        }
    }

    /**
     * @param r TBD
     * @param value TBD
     * @param <X> TBD
     * @return TBD
     */
    public <X> X getWithBinding(T value, Supplier<X> r) {
        Binding<?> top = Thread.currentThread().scopeLocalBindings;
        try {
            Thread.currentThread().scopeLocalBindings =
                    new Binding<T>(this, value, top);
            return r.get();
        } finally {
            Thread.currentThread().scopeLocalBindings = top;
        }
    }
}
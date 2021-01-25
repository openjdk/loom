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

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import java.util.concurrent.Callable;

/**
 * TBD
 *
 * @param <T> TBD
 */
public final class Scoped<T> {

    final @Stable Class<? super T> type;
    final @Stable int hash;

    // Is this scope-local value inheritable? We could handle this by
    // making Scoped an abstract base class and scopeLocalBindings() a
    // virtual method, but that seems a little excessive.
    final @Stable boolean isInheritable;

    public final int hashCode() { return hash; }

    static class Binding<T> {
        final Scoped<T> key;
        final T value;
        final Binding<?> prev;

        private static final Object NIL = new Object();

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

        Object find(Scoped<?> key) {
            for (Binding<?> b = this; b != null; b = b.prev) {
                if (b.getKey() == key) {
                    Object value = b.get();
                    return value;
                }
            }
            return NIL;
        }
    }

    private Scoped(Class<? super T> type, boolean isInheritable) {
        this.isInheritable = isInheritable;
        this.type = type;
        this.hash = generateKey();
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
        return new Scoped<T>(type, false);
    }

    /**
     * TBD
     *
     * @param <T>   TBD
     * @param <U>   TBD
     * @param type TBD
     * @return TBD
     */
    public static <U,T extends U> Scoped<T> inheritableForType(Class<U> type) {
        return new Scoped<T>(type, true);
    }

    private final Binding<?> scopeLocalBindings() {
        Thread currentThread = Thread.currentThread();
        return isInheritable
                ? currentThread.inheritableScopeLocalBindings
                : currentThread.noninheritableScopeLocalBindings;
    }

    private final void setScopeLocalBindings(Binding<?> bindings) {
        Thread currentThread = Thread.currentThread();
        if (isInheritable) {
            currentThread.inheritableScopeLocalBindings = bindings;
        } else {
            currentThread.noninheritableScopeLocalBindings = bindings;
        }
    }

    /**
     * TBD
     *
     * @return TBD
     */
    @SuppressWarnings("unchecked")
    public boolean isBound() {
        var bindings = scopeLocalBindings();
        if (bindings == null) {
            return false;
        }
        return (bindings.find(this) != Binding.NIL);
    }

    /**
     * TBD
     *
     * @return TBD
     */
    @SuppressWarnings("unchecked")
    T slowGet() {
        var bindings = scopeLocalBindings();
        if (bindings == null) {
            throw new RuntimeException("unbound");
        }
        for (var b = bindings; b != null; b = b.prev) {
            if (b.getKey() == this) {
                T value = (T)b.get();
                Cache.put(this, value);
                return value;
            }
        }
        throw new RuntimeException("unbound");
    }

    /**
     * TBD
     *
     * @return TBD
     */
    @ForceInline
    @SuppressWarnings("unchecked")
    public T get() {
        Object[] objects;
        if ((objects = Thread.scopedCache()) != null) {
            // This code should perhaps be in class Cache. We do it
            // here because the generated code is small and fast and
            // we really want it to be inlined in the caller.
            int n = (hash & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return (T)objects[n + 1];
            }
        }
        return slowGet();
    }

    /**
     * TBD
     *
     * @param r TBD
     * @param value   TBD
     */
    public void runWithBinding(T value, Runnable r) {
        Binding<?> top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new Binding<T>(this, value, top));
            r.run();
        } finally {
            // assert(top == Thread.currentThread().scopeLocalBindings.prev);
            setScopeLocalBindings(top);
            Cache.remove(this);
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
        Binding<?> top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new Binding<T>(this, value, top));
            return r.call();
        } finally {
            setScopeLocalBindings(top);
            Cache.remove(this);
        }
    }

    private static class Cache {

        static final int INDEX_BITS = 4;  // Must be a power of 2
        static final int TABLE_SIZE = 1 << INDEX_BITS;
        static final int TABLE_MASK = TABLE_SIZE - 1;

        static void put(Scoped<?> key, Object value) {
            if (Thread.scopedCache() == null) {
                Thread.setScopedCache(new Object[TABLE_SIZE * 2]);
            }
            setKeyAndObjectAt(chooseVictim(Thread.currentCarrierThread(), key.hashCode()), key, value);
        }

        private static final void update(Object key, Object value) {
            Object[] objects;
            if ((objects = Thread.scopedCache()) != null) {

                int k1 = key.hashCode() & TABLE_MASK;
                if (getKey(objects, k1) == key) {
                    setKeyAndObjectAt(k1, key, value);
                }
                int k2 = (key.hashCode() >> INDEX_BITS) & TABLE_MASK;
                if (getKey(objects, k2) == key) {
                    setKeyAndObjectAt(k2, key, value);
                }
            }
        }

        private static final void remove(Object key) {
            Object[] objects;
            if ((objects = Thread.scopedCache()) != null) {

                int k1 = key.hashCode() & TABLE_MASK;
                if (getKey(objects, k1) == key) {
                    setKeyAndObjectAt(k1, null, null);
                }
                int k2 = (key.hashCode() >> INDEX_BITS) & TABLE_MASK;
                if (getKey(objects, k2) == key) {
                    setKeyAndObjectAt(k2, null, null);
                }
            }
        }

        private static void setKeyAndObjectAt(int n, Object key, Object value) {
            Thread.scopedCache()[n * 2] = key;
            Thread.scopedCache()[n * 2 + 1] = value;
        }

        private static Object getKey(Object[] objs, long hash) {
            int n = (int) (hash & TABLE_MASK);
            return objs[n * 2];
        }

        private static void setKey(Object[] objs, long hash, Object key) {
            int n = (int) (hash & TABLE_MASK);
            objs[n * 2] = key;
        }

        @SuppressWarnings("unchecked")  // one map has entries for all types <T>
        final Object getKey(int n) {
            return Thread.scopedCache()[n * 2];
        }

        @SuppressWarnings("unchecked")  // one map has entries for all types <T>
        private static Object getObject(int n) {
            return Thread.scopedCache()[n * 2 + 1];
        }

        private static int chooseVictim(Thread thread, int hash) {
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            int k1 = hash & TABLE_MASK;
            int k2 = (hash >> INDEX_BITS) & TABLE_MASK;
            int tmp = thread.victims;
            thread.victims = (tmp << 31) | (tmp >>> 1);
            return (tmp & 1) == 0 ? k1 : k2;
        }

        public static void invalidate() {
            Thread.setScopedCache(null);
        }
    }

    private static int nextKey = 0xf0f0_f0f0;

    // A Marsaglia xor-shift generator used to generate hashes. This one has full period, so
    // it generates 2**32 - 1 hashes before it repeats. We're going to use the lowest n bits
    // and the next n bits as cache indexes, so we make sure that those indexes are
    // different.
    private static synchronized int generateKey() {
        int x = nextKey;
        do {
            x ^= x >>> 12;
            x ^= x << 9;
            x ^= x >>> 23;
        } while ((x & Cache.TABLE_MASK)
                == ((x >>> Cache.INDEX_BITS) & Cache.TABLE_MASK));
        return (nextKey = x);
    }

    /**
     * TBD
     */
     public static final class Snapshot {
        private final Binding<?> bindings;

        /**
         * TBD
         * @return TBD
         */
        private Snapshot() {
            bindings = Thread.currentThread().inheritableScopeLocalBindings;
        }

        /**
         * TBD
         * @param r TBD
         */
        @SuppressWarnings("rawtypes")
        public void runWithSnapshot(Runnable r) {
            var prev = Thread.currentThread().inheritableScopeLocalBindings;
            var cache = Thread.scopedCache();
            Cache.invalidate();
            try {
                Thread.currentThread().inheritableScopeLocalBindings = bindings;
                r.run();
            } finally {
                Thread.currentThread().inheritableScopeLocalBindings = prev;
                Thread.setScopedCache(cache);
            }
        }

        /**
         * @param r TBD
         * @param <T> type
         * @return T tbd
         * @throws Exception TBD
         */
        public <T> T callWithSnapshot(Callable<T> r) throws Exception {
            var prev = Thread.currentThread().inheritableScopeLocalBindings;
            var cache = Thread.scopedCache();
            Cache.invalidate();
            try {
                Thread.currentThread().inheritableScopeLocalBindings = bindings;
                return r.call();
            } finally {
                Thread.currentThread().inheritableScopeLocalBindings = prev;
                Thread.setScopedCache(cache);
            }
        }
    }

    /**
     * TBD
     * @return TBD
     */
    public static final Snapshot snapshot() {
        return new Snapshot();
    }
}
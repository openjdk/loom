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

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

/**
 * Represents a scoped variable.
 *
 * <p> A scoped variable differs to a normal variable in that it is dynamically
 * scoped and intended for cases where context needs to be passed from a caller
 * to a transitive callee without using an explicit parameter. A scoped variable
 * does not have a default/initial value, it is bound, meaning it gets a value,
 * when executing an operation specified to {@link #runWithBinding(Object, Runnable)}
 * or {@link #callWithBinding(Object, Callable)}. Code executed by the operation
 * uses the {@link #get()} method to get the value of the variable. The variable reverts
 * to being unbound (or its previous value) when the operation completes.
 *
 * <p> Access to the value of a scoped variable is controlled by the accessibility
 * of the {@code Scoped} object. A {@code Scoped} object  will typically be declared
 * in a private static field so that it can only be accessed by code in that class
 * (or other classes within its nest).
 *
 * <p> Scoped variables support nested bindings. If a scoped variable has a value
 * then the {@code runWithBinding} or {@code callWithBinding} can be invoked to run
 * another operation with a new value. Code executed by this methods "sees" the new
 * value of the variable. The variable reverts to its previous value when the
 * operation completes.
 *
 * <p> An <em>inheritable scoped variable</em> is created with the {@link
 * #inheritableForType(Class)} method and provides inheritance of values from
 * parent thread to child thread that is arranged when the child thread is
 * created. Unlike {@link InheritableThreadLocal}, inheritable scoped variable
 * are not copied into the child thread, instead the child thread will access
 * the same variable as the parent thread. The value of inheritable scoped
 * variables should be immutable to avoid needing synchronization to coordinate
 * access.
 *
 * <p> As an advanced feature, the {@link #snapshot()} method is defined to obtain
 * a {@link Snapshot} of the inheritable scoped variables that are currently bound.
 * This can be used to support cases where inheritance needs to be done at times
 * other than thread creation.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * @apiNote
 * The following example uses a scoped variable to make credentials available to callees.
 *
 * <pre>{@code
 *   private static final Scoped<Credentials> CREDENTIALS = Scoped.forType(Credentials.class);
 *
 *   Credentials creds = ...
 *   CREDENTIALS.runWithBinding(creds, () -> {
 *       :
 *       Connection connection = connectDatabase();
 *       :
 *   });
 *
 *   Connection connectDatabase() {
 *       Credentials credentials = CREDENTIALS.get();
 *       :
 *   }
 * }</pre>
 *
 * @param <T> the variable type
 * @since 99
 */
public final class Scoped<T> {
    private final @Stable Class<? super T> type;
    private final @Stable int hash;

    // Is this scope-local value inheritable? We could handle this by
    // making Scoped an abstract base class and scopeLocalBindings() a
    // virtual method, but that seems a little excessive.
    private final @Stable boolean isInheritable;

    public int hashCode() { return hash; }

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
        this.type = Objects.requireNonNull(type);
        this.isInheritable = isInheritable;
        this.hash = generateKey();
    }

    /**
     * Creates a scoped variable to hold a value with the given type.
     *
     * @param <T> TBD
     * @param <U>   TBD
     * @param type TBD
     * @return a scope variable
     */
    public static <U,T extends U> Scoped<T> forType(Class<U> type) {
        return new Scoped<T>(type, false);
    }

    /**
     Creates an inheritable scoped variable to hold a value with the given type.
     *
     * @param <T> TBD
     * @param <U>   TBD
     * @param type TBD
     * @return a scope variable
     */
    public static <U,T extends U> Scoped<T> inheritableForType(Class<U> type) {
        return new Scoped<T>(type, true);
    }

    private Binding<?> scopeLocalBindings() {
        Thread currentThread = Thread.currentThread();
        return isInheritable
                ? currentThread.inheritableScopeLocalBindings
                : currentThread.noninheritableScopeLocalBindings;
    }

    private void setScopeLocalBindings(Binding<?> bindings) {
        Thread currentThread = Thread.currentThread();
        if (isInheritable) {
            currentThread.inheritableScopeLocalBindings = bindings;
        } else {
            currentThread.noninheritableScopeLocalBindings = bindings;
        }
    }

    /**
     * Returns {@code true} if the variable is bound to a value.
     *
     * @return {@code true} if the variable is bound to a value, otherwise {@code false}
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
     * Return the value of the variable or NIL if not bound.
     */
    private Object findBinding() {
        var bindings = scopeLocalBindings();
        if (bindings != null) {
            return bindings.find(this);
        } else {
            return Binding.NIL;
        }
    }

    /**
     * Return the value of the variable if bound, otherwise returns {@code other}.
     * @param other the value to return if not bound, can be {@code null}
     * @return the value of the variable if bound, otherwise {@code other}
     */
    public T orElse(T other) {
        Object obj = findBinding();
        if (obj != Binding.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            return other;
        }
    }

    /**
     * Return the value of the variable if bound, otherwise throws an exception
     * produced by the exception supplying function.
     * @param <X> Type of the exception to be thrown
     * @param exceptionSupplier the supplying function that produces an
     *        exception to be thrown
     * @return the value of the variable if bound
     * @throws X if the variable is unbound
     */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(exceptionSupplier);
        Object obj = findBinding();
        if (obj != Binding.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            throw exceptionSupplier.get();
        }
    }

    @SuppressWarnings("unchecked")
    private T slowGet() {
        var bindings = scopeLocalBindings();
        if (bindings != null) {
            for (var b = bindings; b != null; b = b.prev) {
                if (b.getKey() == this) {
                    return (T) b.get();
                }
            }
        }
        throw new NoSuchElementException();
    }

    /**
     * Returns the value the variable.
     * @return the value the variable
     * @throws NoSuchElementException if the variable is not bound (exception is TBD)
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
     * Runs an operation with this variable bound to the given value. Code
     * executed by the operation can use the {@link #get()} method to get the
     * value of the variable. The variable reverts to its previous value or
     * becomes {@linkplain #isBound() unbound} when the operation completes.
     *
     * @param value the value for the variable, can be null
     * @param op the operation to run
     */
    public void runWithBinding(T value, Runnable op) {
        Objects.requireNonNull(op);
        Binding<?> top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new Binding<T>(this, value, top));
            op.run();
        } finally {
            // assert(top == Thread.currentThread().scopeLocalBindings.prev);
            setScopeLocalBindings(top);
            Cache.remove(this);
        }
    }

    /**
     * Runs a value-returning operation with this variable bound to the given
     * value. Code executed by the operation can use the {@link #get()} method to
     * get the value of the variable. The variable reverts to its previous value or
     * becomes {@linkplain #isBound() unbound} when the operation completes.
     *
     * @param value the value for the variable, can be null
     * @param op the operation to run
     * @param <R> the type of the result of the function
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public <R> R callWithBinding(T value, Callable<R> op) throws Exception {
        Objects.requireNonNull(op);
        Binding<?> top = scopeLocalBindings();
        Cache.update(this, value);
        try {
            setScopeLocalBindings(new Binding<T>(this, value, top));
            return op.call();
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
            int victim = chooseVictim(Thread.currentCarrierThread(), key.hashCode());
            setKeyAndObjectAt(victim, key, value);
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
     * Represents a snapshot of inheritable scoped variables.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
     * or method in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     * @see Scoped#snapshot()
     */
    public static final class Snapshot {
        private final Binding<?> bindings;

        private Snapshot() {
            bindings = Thread.currentThread().inheritableScopeLocalBindings;
        }

        /**
         * Runs an operation with this snapshot of inheritable scoped variables.
         *
         * @param op the operation to run
         */
        @SuppressWarnings("rawtypes")
        public void runWithSnapshot(Runnable op) {
            var prev = Thread.currentThread().inheritableScopeLocalBindings;
            var cache = Thread.scopedCache();
            Cache.invalidate();
            try {
                Thread.currentThread().inheritableScopeLocalBindings = bindings;
                op.run();
            } finally {
                Thread.currentThread().inheritableScopeLocalBindings = prev;
                Thread.setScopedCache(cache);
            }
        }

        /**
         * Runs a value-returning operation with this snapshot of inheritable
         * scoped variables.
         *
         * @param op the operation to run
         * @param <R> the type of the result of the function
         * @return the result
         * @throws Exception if the operation completes with an exception
         */
        public <R> R callWithSnapshot(Callable<R> op) throws Exception {
            var prev = Thread.currentThread().inheritableScopeLocalBindings;
            var cache = Thread.scopedCache();
            Cache.invalidate();
            try {
                Thread.currentThread().inheritableScopeLocalBindings = bindings;
                return op.call();
            } finally {
                Thread.currentThread().inheritableScopeLocalBindings = prev;
                Thread.setScopedCache(cache);
            }
        }
    }

    /**
     * Returns a "snapshot" of the inheritable scoped variables that are currently
     * bound.
     *
     * @return a "snapshot" of the inheritable scoped variables
     */
    public static Snapshot snapshot() {
        return new Snapshot();
    }
}
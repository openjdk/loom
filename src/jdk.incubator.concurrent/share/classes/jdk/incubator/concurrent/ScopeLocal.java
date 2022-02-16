/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2021, Red Hat Inc.
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

package jdk.incubator.concurrent;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.JavaUtilThreadLocalRandomAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.ScopeLocalContainer;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.ReservedStackAccess;
import jdk.internal.vm.annotation.Stable;

/**
 * Represents a scoped value.
 *
 * <p> A scope-local value (hereinafter called a scope local) differs from a normal variable in that it is dynamically
 * scoped and intended for cases where context needs to be passed from a caller
 * to a transitive callee without using an explicit parameter. A scope-local value
 * does not have a default/initial value: it is bound, meaning it gets a value,
 * when executing an operation specified to {@link #where(ScopeLocal, Object)}.
 * Code executed by the operation
 * uses the {@link #get()} method to get the value of the scope local. The scope local reverts
 * to being unbound (or its previous value) when the operation completes.
 *
 * <p> Access to the value of a scope local is controlled by the accessibility
 * of the {@code ScopeLocal} object. A {@code ScopeLocal} object  will typically be declared
 * in a private static field so that it can only be accessed by code in that class
 * (or other classes within its nest).
 *
 * <p> Scope locals support nested bindings. If a scope local has a value
 * then the {@code runWithBinding} or {@code callWithBinding} can be invoked to run
 * another operation with a new value. Code executed by this methods "sees" the new
 * value of the scope local. The scope local reverts to its previous value when the
 * operation completes.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be thrown.
 *
 * @apiNote
 * The following example uses a scope local to make credentials available to callees.
 *
 * <pre>{@code
 *   private static final ScopeLocal<Credentials> CREDENTIALS = ScopeLocal.newInstance();
 *
 *   Credentials creds = ...
 *   ScopeLocal.where(CREDENTIALS, creds).run(() -> {
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
 * @implNote Scope locals are designed to be used in fairly small numbers. {@link
 * #get} initially performs a linear search through enclosing scopes to find a
 * scope local's innermost binding. It then caches the result of the search in a
 * small thread-local cache. Subsequent invocations of {@link #get} for that
 * scope local will almost always be very fast. However, if a program has many
 * scope locals that it uses cyclically, the cache hit rate will be low and
 * performance will be poor. On the other hand, this design allows scope-local
 * inheritance by {@link StructuredTaskScope} threads to be
 * very fast: in essence, no more than copying a pointer, and leaving a
 * scope-local binding also requires little more than updating a pointer.
 *
 * Because the scope-local per-thread cache is small, you should try to minimize
 * the number of bound scope locals in use. For example, if you need to pass a
 * number of values as scope locals, it makes sense to create a record class to
 * hold those values, and then bind a single scope local to an instance of that
 * record.
 *
 * @param <T> the scope local's type
 * @since 19
 */
public final class ScopeLocal<T> {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private final @Stable int hash;

    public final int hashCode() { return hash; }

    /**
     * An immutable map from {@code ScopeLocal} to values.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
     * or method in this class will cause a {@link NullPointerException} to be thrown.
     */
    static sealed class Snapshot permits EmptySnapshot {
        final Snapshot prev;
        final Carrier bindings;
        final int bitmask;

        private static final Object NIL = new Object();

        Snapshot(Carrier bindings, Snapshot prev) {
            this.prev = prev;
            this.bindings = bindings;
            this.bitmask = bindings.bitmask | prev.bitmask;
        }

        protected Snapshot() {
            this.prev = null;
            this.bindings = null;
            this.bitmask = 0;
        }

        Object find(ScopeLocal<?> key) {
            int bits = key.bitmask();
            for (Snapshot snapshot = this;
                 containsAll(snapshot.bitmask, bits);
                 snapshot = snapshot.prev) {
                for (Carrier carrier = snapshot.bindings;
                     carrier != null && containsAll(carrier.bitmask, bits);
                     carrier = carrier.prev) {
                    if (carrier.getKey() == key) {
                        Object value = carrier.get();
                        return value;
                    }
                }
            }
            return NIL;
        }
    }

    static final class EmptySnapshot extends Snapshot {

        private EmptySnapshot() {
            super();
        }

        private static final Snapshot SINGLETON = new EmptySnapshot();

        static final Snapshot getInstance() {
            return SINGLETON;
        }
    }

    /**
     * An immutable map from a set of ScopeLocals to their bound values.
     * When map() or call() is invoked, the ScopeLocals bound in this set
     * are bound, such that calling the get() method returns the associated
     * value.
     * @since 19
     */
    public static final class Carrier {
        // Bit masks: a 1 in postion n indicates that this set of bound values
        // hits that slot in the cache.
        final int bitmask;
        final ScopeLocal<?> key;
        final Object value;
        final Carrier prev;

        Carrier(ScopeLocal<?> key, Object value, Carrier prev) {
            this.key = key;
            this.value = value;
            this.prev = prev;
            int bits = key.bitmask();
            if (prev != null) {
                bits |= prev.bitmask;
            }
            this.bitmask = bits;
        }

        /**
         * Add a binding to this map, returning a new Carrier instance.
         */
        private static final <T> Carrier where(ScopeLocal<T> key, T value,
                                               Carrier prev) {
            return new Carrier(key, value, prev);
        }

        /**
         * Return a new map, which consists of the contents of this map plus a
         * new binding of key and value.
         * @param key   The ScopeLocal to bind a value to
         * @param value The new value
         * @param <T>   The type of the ScopeLocal
         * @return A new map, consisting of {@code this}. plus a new binding. {@code this} is unchanged.
         */
        public final <T> Carrier where(ScopeLocal<T> key, T value) {
            return where(key, value, this);
        }

        /*
         * Return a new set consisting of a single binding.
         */
        static final <T> Carrier of(ScopeLocal<T> key, T value) {
            return where(key, value, null);
        }

        final Object get() {
            return value;
        }

        final ScopeLocal<?> getKey() {
            return key;
        }

        /**
         * Search for the value of a binding in this set
         * @param key the ScopeLocal to find
         * @param <T> the type of the ScopeLocal
         * @return the value
         * @throws NoSuchElementException if key is not bound to any value
         *
         */
        @SuppressWarnings("unchecked")
        public final <T> T get(ScopeLocal<T> key) {
            var bits = key.bitmask();
            for (Carrier carrier = this;
                 carrier != null && containsAll(carrier.bitmask, bits);
                 carrier = carrier.prev) {
                if (carrier.getKey() == key) {
                    Object value = carrier.get();
                    return (T)value;
                }
            }
            throw new NoSuchElementException();
        }

        /**
         * Run a value-returning operation with some ScopeLocals bound to values.
         * Code executed by the operation can use the {@link #get()} method to
         * get the value of the scope local. The scope locals revert to their previous values or
         * become {@linkplain #isBound() unbound} when the operation completes.
         *
         * <p> Scope locals are intended to be used in a <em>structured manner</em>. If the
         * operation creates {@link StructuredTaskScope}
         * but does not close them, then exiting the operation causes the underlying construct
         * of each executor to be closed (in the reverse order that they were created in), and
         * {@link StructureViolationException} to be thrown.
         *
         * @param op    the operation to run
         * @param <R>   the type of the result of the function
         * @return the result
         * @throws Exception if the operation completes with an exception
         */
        public final <R> R call(Callable<R> op) throws Exception {
            Objects.requireNonNull(op);
            Cache.invalidate(bitmask);
            var prevBindings = addScopeLocalBindings(this);
            try {
                return ScopeLocalContainer.call(op);
            } catch (Throwable t) {
                setScopeLocalCache(null); // Cache.invalidate();
                throw t;
            } finally {
                setScopeLocalBindings(prevBindings);
                Cache.invalidate(bitmask);
            }
        }

        /**
         * Run a value-returning operation with this set of ScopeLocals bound to values,
         * in the same way as {@code call()}.<p>
         *     If the operation throws an exception, pass it as a single argument to the {@link Function}
         *     {@code handler}. {@code handler} must return a value compatible with the type returned by {@code op}.
         * </p>
         * @param op    the operation to run
         * @param <R>   the type of the result of the function
         * @param handler the handler to be applied if {code op} threw an exception
         * @return the result.
          */
        public final <R> R callOrElse(Callable<R> op,
                                      Function<? super Exception, ? extends R> handler) {
            try {
                return call(op);
            } catch (Exception e) {
                return handler.apply(e);
            }
        }

        /**
         * Runs an operation with some ScopeLocals bound to our values.
         * Code executed by the operation can use the {@link #get()} method to
         * get the value of the scope local. The scope locals revert to their previous values or
         * becomes {@linkplain #isBound() unbound} when the operation completes.
         *
         * <p> Scope locals are intended to be used in a <em>structured manner</em>. If the
         * operation creates {@link StructuredTaskScope}s
         * but does not close them, then exiting the operation causes the underlying construct
         * of each executor to be closed (in the reverse order that they were created in), and
         * {@link StructureViolationException} to be thrown.
         *
         * @param op    the operation to run
         */
        public final void run(Runnable op) {
            Objects.requireNonNull(op);
            Cache.invalidate(bitmask);
            var prevBindings = addScopeLocalBindings(this);
            try {
                ScopeLocalContainer.run(op);
            } catch (Throwable t) {
                setScopeLocalCache(null); // Cache.invalidate();
                throw t;
            } finally {
                setScopeLocalBindings(prevBindings);
                Cache.invalidate(bitmask);
            }
        }

        /*
         * Add a list of bindings to the current Thread's set of bound values.
         */
        private static final Snapshot addScopeLocalBindings(Carrier bindings) {
            Snapshot prev = scopeLocalBindings();
            var b = new Snapshot(bindings, prev);
            ScopeLocal.setScopeLocalBindings(b);
            return prev;
        }

        /*
         * Ensure that none of these bindings is already bound.
         */
        void checkNotBound() {
            for (Carrier c = this; c != null; c = c.prev) {
                if (c.key.isBound()) {
                    throw new RuntimeException("Scope Local already bound");
                }
            }
        }
    }

    /**
     * Create a binding for a ScopeLocal instance.
     * That {@link Carrier} may be used later to invoke a {@link Callable} or
     * {@link Runnable} instance. More bindings may be added to the {@link Carrier}
     * by the {@link Carrier#where(ScopeLocal, Object)} method.
     *
     * @param key the ScopeLocal to bind
     * @param value The value to bind it to
     * @param <T> the type of the ScopeLocal
     * @return A Carrier instance that contains one binding, that of key and value
     */
    public static <T> Carrier where(ScopeLocal<T> key, T value) {
        return Carrier.of(key, value);
    }

    /**
     * Creates a binding for a ScopeLocal instance and runs a value-returning
     * operation with that bound ScopeLocal.
     * @param key the ScopeLocal to bind
     * @param value The value to bind it to
     * @param <T> the type of the ScopeLocal
     * @param <U> the type of the Result
     * @param op the operation to call
     * @return the result
     * @throws Exception if the operation completes with an exception
     */
    public static <T, U> U where(ScopeLocal<T> key, T value, Callable<U> op) throws Exception {
        return where(key, value).call(op);
    }

    /**
     * Creates a binding for a ScopeLocal instance and runs an
     * operation with that bound ScopeLocal.
     * @param key the ScopeLocal to bind
     * @param value The value to bind it to
     * @param <T> the type of the ScopeLocal
     * @param op the operation to run
     */
    public static <T> void where(ScopeLocal<T> key, T value, Runnable op) {
        where(key, value).run(op);
    }

    private ScopeLocal() {
        this.hash = generateKey();
    }

    /**
     * Creates a scope-local handle to refer to a value of type T.
     *
     * @param <T> the type of the scope local's value.
     * @return a scope-local handle
     */
    public static <T> ScopeLocal<T> newInstance() {
        return new ScopeLocal<T>();
    }

    /**
     * Returns the value of the scope local.
     * @return the value of the scope local
     * @throws NoSuchElementException if the scope local is not bound (exception is TBD)
     */
    @ForceInline
    @SuppressWarnings("unchecked")
    public T get() {
        Object[] objects;
        if ((objects = scopeLocalCache()) != null) {
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

    @SuppressWarnings("unchecked")
    private T slowGet() {
        var value = findBinding();
        if (value == Snapshot.NIL) {
            throw new NoSuchElementException();
        }
        Cache.put(this, value);
        return (T)value;
    }

    /**
     * Returns {@code true} if the scope local is bound to a value.
     *
     * @return {@code true} if the scope local is bound to a value, otherwise {@code false}
     */
    @SuppressWarnings("unchecked")
    public boolean isBound() {
        // ??? Do we want to search cache for this? In most cases we don't expect
        // this {@link ScopeLocal} to be bound, so it's not worth it. But I may
        // be wrong about that.
/*
        if (Cache.find(this) != Snapshot.NIL) {
            return true;
        }
 */
        return findBinding() != Snapshot.NIL;
    }

    /**
     * Return the value of the scope local or NIL if not bound.
     */
    private Object findBinding() {
        Object value = scopeLocalBindings().find(this);
        return value;
    }

    /**
     * Return the value of the scope local if bound, otherwise returns {@code other}.
     * @param other the value to return if not bound, can be {@code null}
     * @return the value of the scope local if bound, otherwise {@code other}
     */
    public T orElse(T other) {
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            return other;
        }
    }

    /**
     * Return the value of the scope local if bound, otherwise throw an exception
     * produced by the exception supplying function.
     * @param <X> Type of the exception to be thrown
     * @param exceptionSupplier the supplying function that produces an
     *        exception to be thrown
     * @return the value of the scope local if bound
     * @throws X if the scope local is unbound
     */
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        Objects.requireNonNull(exceptionSupplier);
        Object obj = findBinding();
        if (obj != Snapshot.NIL) {
            @SuppressWarnings("unchecked")
            T value = (T) obj;
            return value;
        } else {
            throw exceptionSupplier.get();
        }
    }

    private static Object[] scopeLocalCache() {
        return JLA.scopeLocalCache();
    }

    private static void setScopeLocalCache(Object[] cache) {
        JLA.setScopeLocalCache(cache);
    }

    private static Snapshot scopeLocalBindings() {
        Object bindings = JLA.scopeLocalBindings();
        if (bindings != null) {
            return (Snapshot) bindings;
        } else {
            return EmptySnapshot.getInstance();
        }
    }

    private static void setScopeLocalBindings(Snapshot bindings) {
        JLA.setScopeLocalBindings(bindings);
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
     * Return a bit mask that may be used to determine if this ScopeLocal is
     * bound in the current context. Each Carrier holds a bit mask which is
     * the OR of all the bit masks of the bound ScopeLocals.
     * @return the bitmask
     */
    int bitmask() {
        return (1 << Cache.primaryIndex(this)) | (1 << (Cache.secondaryIndex(this) + Cache.TABLE_SIZE));
    }

    // Return true iff bitmask, considered as a set of bits, contains all
    // of the bits in targetBits.
    static boolean containsAll(int bitmask, int targetBits) {
        return (bitmask & targetBits) == targetBits;
    }

    // A small fixed-size key-value cache. When a scope scope local's get() method
    // is invoked, we record the result of the lookup in this per-thread cache
    // for fast access in future.
    private static class Cache {
        static final int INDEX_BITS = 4;  // Must be a power of 2
        static final int TABLE_SIZE = 1 << INDEX_BITS;
        static final int TABLE_MASK = TABLE_SIZE - 1;
        static final int PRIMARY_MASK = (1 << TABLE_SIZE) - 1;

        static final int primaryIndex(ScopeLocal<?> key) {
            return key.hash & TABLE_MASK;
        }

        static final int secondaryIndex(ScopeLocal<?> key) {
            return (key.hash >> INDEX_BITS) & TABLE_MASK;
        }

        static void put(ScopeLocal<?> key, Object value) {
            Object[] theCache = scopeLocalCache();
            if (theCache == null) {
                theCache = new Object[TABLE_SIZE * 2];
                setScopeLocalCache(theCache);
            }
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            int k1 = primaryIndex(key);
            int k2 = secondaryIndex(key);
            var usePrimaryIndex = chooseVictim();
            int victim = usePrimaryIndex ? k1 : k2;
            int other = usePrimaryIndex ? k2 : k1;
            setKeyAndObjectAt(victim, key, value);
            if (getKey(theCache, other) == key) {
                setKey(theCache, other, null);
            }
        }

        private static final void update(Object key, Object value) {
            Object[] objects;
            if ((objects = scopeLocalCache()) != null) {
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
            if ((objects = scopeLocalCache()) != null) {
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
            scopeLocalCache()[n * 2] = key;
            scopeLocalCache()[n * 2 + 1] = value;
        }

        private static Object getKey(Object[] objs, int n) {
            return objs[n * 2];
        }

        private static void setKey(Object[] objs, int n, Object key) {
            objs[n * 2] = key;
        }

        private static final JavaUtilThreadLocalRandomAccess THREAD_LOCAL_RANDOM_ACCESS
                = SharedSecrets.getJavaUtilThreadLocalRandomAccess();

        // Return either true or false, at pseudo-random, with a bias towards true.
        // This chooses either the primary or secondary cache slot, but the
        // primary slot is approximately twice as likely to be chosen as the
        // secondary one.
        private static boolean chooseVictim() {
            int r = THREAD_LOCAL_RANDOM_ACCESS.nextSecondaryThreadLocalRandomSeed();
            return (r & 15) >= 5;
        }

        @ReservedStackAccess @DontInline
        public static void invalidate() {
            setScopeLocalCache(null);
        }

        // Null a set of cache entries, indicated by the 1-bits given
        @ReservedStackAccess @DontInline
        static void invalidate(int toClearBits) {
            toClearBits = (toClearBits >>> TABLE_SIZE) | (toClearBits & PRIMARY_MASK);
            Object[] objects;
            if ((objects = scopeLocalCache()) != null) {
                for (int bits = toClearBits; bits != 0; ) {
                    int index = Integer.numberOfTrailingZeros(bits);
                    setKeyAndObjectAt(index, null, null);
                    bits &= ~1 << index;
                }
            }
        }
    }
}

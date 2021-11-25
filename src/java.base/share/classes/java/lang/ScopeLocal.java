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
import java.util.function.Function;
import java.util.function.Supplier;
import jdk.internal.vm.StackableScope;
import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.annotation.Stable;

import static jdk.internal.javac.PreviewFeature.Feature.SCOPE_LOCALS;

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
 * <p> Scope locals  support nested bindings. If a scope local has a value
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
 *   ScopeLocal.where(CREDENTIALS, creds).run(creds, () -> {
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
 * @param <T> the scope local's type
 * @since 99
 */
@jdk.internal.javac.PreviewFeature(feature=SCOPE_LOCALS)
public final class ScopeLocal<T> {
    private final @Stable int hash;

    public final int hashCode() { return hash; }

    /**
     * An immutable map from {@code ScopeLocal} to values.
     *
     * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
     * or method in this class will cause a {@link NullPointerException} to be thrown.
     *
     * @since 99
     */

    static class Snapshot {
        final Snapshot prev;
        final Carrier bindings;
        final short primaryBits;

        private static final Object NIL = new Object();

        Snapshot(Carrier bindings, Snapshot prev, short primaryBits) {
            this.prev = prev;
            this.bindings = bindings;
            this.primaryBits = primaryBits;
        }

        Object find(ScopeLocal<?> key) {
            for (Snapshot b = this; b != null; b = b.prev) {
                if (((1 << Cache.primaryIndex(key)) & b.primaryBits) != 0) {
                    for (Carrier binding = b.bindings;
                         binding != null;
                         binding = binding.prev) {
                        if (binding.getKey() == key) {
                            Object value = binding.get();
                            return value;
                        }
                    }
                }
            }
            return NIL;
        }
    }

     static final class EmptySnapshot extends Snapshot {
        private EmptySnapshot() {
            super(null, null, (short)0);
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
     */
    public static final class Carrier {
        // Bit masks: a 1 in postion n indicates that this set of bound values
        // hits that slot in the cache
        final short primaryBits, secondaryBits;
        final ScopeLocal<?> key;
        final Object value;
        final Carrier prev;

        Carrier(ScopeLocal<?> key, Object value, Carrier prev, short primaryBits, short secondaryBits) {
            this.key = key;
            this.value = value;
            this.prev = prev;
            this.primaryBits = primaryBits;
            this.secondaryBits = secondaryBits;
        }

        /**
         * Add a binding to this map, returning a new Carrier instance.
         */
        private static final <T> Carrier where(ScopeLocal<T> key, T value,
                                               Carrier prev,
                                               short primaryBits, short secondaryBits) {
            primaryBits |= (short)(1 << Cache.primaryIndex(key));
            secondaryBits |= (short)(1 << Cache.secondaryIndex(key));
            return new Carrier(key, value, prev, primaryBits, secondaryBits);
        }

        /**
         * Return a new map, which consists of the contents of this map plus a
         * new binding of key and value.
         * @param key   The ScopeLocal to bind a value to
         * @param value The new value
         * @param <T>   The type of the ScopeLocal
         * @return TBD
         */
        public final <T> Carrier where(ScopeLocal<T> key, T value) {
            return where(key, value, this, primaryBits, secondaryBits);
        }

        /*
         * Return a new set consisting of a single binding.
         */
        static final <T> Carrier of(ScopeLocal<T> key, T value) {
            return where(key, value, null, (short)0, (short)0);
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
            for (Carrier b = this;
                 b != null; b = b.prev) {
                if (b.getKey() == key) {
                    Object value = b.get();
                    return (T)value;
                }
            }
            throw new NoSuchElementException();
        }

        /**
         * Runs a value-returning operation with this some ScopeLocals bound to values.
         * Code executed by the operation can use the {@link #get()} method to
         * get the value of the scope local. The scope locals revert to their previous values or
         * becomes {@linkplain #isBound() unbound} when the operation completes.
         *
         * <p> Scope locals are intended to be used in a <em>structured manner</em>. If the
         * operation creates {@link java.util.concurrent.StructuredExecutor StructuredExecutor}s
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
            Cache.invalidate(primaryBits | secondaryBits);
            var prevBindings = addScopeLocalBindings(this, primaryBits);
            try {
                return StackableScope.call(op);
            } finally {
                Thread.currentThread().scopeLocalBindings = prevBindings;
                Cache.invalidate(primaryBits | secondaryBits);
            }
        }

        /**
         * Runs a value-returning operation with this some ScopeLocals bound to values.
         * If the operation terminates with an exception {@code e}, apply {@code handler}
         * to {@code e} and return the result.
         *
         * @param op the operation to run
         * @param handler a function to be applied if the operation completes with an exception
         * @param <R> the type of the result of the function
         * @return the result
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
         * operation creates {@link java.util.concurrent.StructuredExecutor StructuredExecutor}s
         * but does not close them, then exiting the operation causes the underlying construct
         * of each executor to be closed (in the reverse order that they were created in), and
         * {@link StructureViolationException} to be thrown.
         *
         * @param op    the operation to run
         */
        public final void run(Runnable op) {
            Objects.requireNonNull(op);
            Cache.invalidate(primaryBits | secondaryBits);
            var prevBindings = addScopeLocalBindings(this, primaryBits);
            try {
                StackableScope.run(op);
            } catch (Throwable e) {
                Thread.currentThread().scopeLocalBindings = prevBindings;
                Cache.invalidate(primaryBits | secondaryBits);
            }
        }

        /*
         * Add a list of bindings to the current Thread's set of bound values.
         */
        private static final Snapshot addScopeLocalBindings(Carrier bindings, short primaryBits) {
            Snapshot prev = getScopeLocalBindings();
            var b = new Snapshot(bindings, prev, primaryBits);
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

        /**
         * Create a try-with-resources ScopeLocal binding
         * @return a Binder
         */
        public Binder bind() {
            checkNotBound();
            return new Binder().push(this);
        }

        /**
         * An @AutoCloseable that's used to bind a {@code ScopeLocal} in a try-with-resources construct.
         */
        static public class Binder implements AutoCloseable {
            private Snapshot bindings;
            private int bits;
            private StackableScope scope;

            Binder() {
            }

            Binder push(Carrier carrier) {
                // Push a StackableScope first
                scope = new StackableScope().push();

                // Then push the ScopeLocal bindings
                var prev = TWRBindings();
                bits = carrier.primaryBits | carrier.secondaryBits;
                bindings = new Snapshot(carrier, prev, carrier.primaryBits);
                Cache.invalidate(bits);
                setTWRBindings(bindings);
                return this;
            }

            /**
             * Close a scope local binding context.
             *
             * @throws StructureViolationException if {@code this} isn't the current top binding
             */
            public void close() throws StructureViolationException {
                Throwable ex = null;

                // First, remove the ScopeLocal bindings
                Cache.invalidate(bits);
                var top = TWRBindings();
                setTWRBindings(bindings.prev);
                if (top != bindings) {
                    // It's all gone wrong
                    Cache.invalidate();
                    ex = new StructureViolationException();
                }

                // Second, remove the StackableScope
                boolean atTop = scope.popForcefully();
                if (ex != null || !atTop) {
                    if (!atTop) {
                        var e = new StructureViolationException();
                        if (ex == null) {
                            ex = e;
                        } else {
                            ex.addSuppressed(e);
                        }
                    }
                    if (ex instanceof RuntimeException e)
                        throw e;
                    assert false;
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
        if ((objects = Thread.scopeLocalCache()) != null) {
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
        var value =  findBinding();
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
        return findBinding() != Snapshot.NIL;
    }

    /**
     * Return the value of the scope local or NIL if not bound.
     */
    private Object findBinding() {
        Object value = scopeLocalBindings().find(this);
        if (value != Snapshot.NIL) {
            return value;
        }
        return TWRBindings().find(this);
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
     * Return the value of the scope local if bound, otherwise throws an exception
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

    private static Snapshot getScopeLocalBindings() {
        return Thread.currentThread().scopeLocalBindings;
    }

    private static void setScopeLocalBindings(Snapshot bindings) {
        Thread currentThread = Thread.currentThread();
        currentThread.scopeLocalBindings = bindings;
    }

    private Snapshot scopeLocalBindings() {
        return getScopeLocalBindings();
    }

    private static void setTWRBindings(Snapshot bindings) {
        Objects.requireNonNull(bindings);
        Thread.currentThread().TWRBindings = bindings;
    }

    private static Snapshot TWRBindings() {
        return Thread.currentThread().TWRBindings;
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

    // A small fixed-size key-value cache. When a scope scope local's get() method
    // is invoked, we record the result of the lookup in this per-thread cache
    // for fast access in future.
    private static class Cache {
        static final int INDEX_BITS = 4;  // Must be a power of 2
        static final int TABLE_SIZE = 1 << INDEX_BITS;
        static final int TABLE_MASK = TABLE_SIZE - 1;

        static final int primaryIndex(ScopeLocal<?> key) {
            return key.hash & TABLE_MASK;
        }

        static final int secondaryIndex(ScopeLocal<?> key) {
            return (key.hash >> INDEX_BITS) & TABLE_MASK;
        }

        static void put(ScopeLocal<?> key, Object value) {
            Object[] theCache = Thread.scopeLocalCache();
            if (theCache == null) {
                theCache = new Object[TABLE_SIZE * 2];
                Thread.setScopeLocalCache(theCache);
            }
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            Thread thread = Thread.currentThread();
            int k1 = primaryIndex(key);
            int k2 = secondaryIndex(key);
            var usePrimaryIndex = chooseVictim(thread);
            int victim = usePrimaryIndex ? k1 : k2;
            int other = usePrimaryIndex ? k2 : k1;
            setKeyAndObjectAt(victim, key, value);
            if (getKey(theCache, other) == key) {
                setKey(theCache, other, null);
            }
        }

        private static final void update(Object key, Object value) {
            Object[] objects;
            if ((objects = Thread.scopeLocalCache()) != null) {
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
            if ((objects = Thread.scopeLocalCache()) != null) {
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
            Thread.scopeLocalCache()[n * 2] = key;
            Thread.scopeLocalCache()[n * 2 + 1] = value;
        }

        private static Object getKey(Object[] objs, int n) {
            return objs[n * 2];
        }

        private static void setKey(Object[] objs, int n, Object key) {
            objs[n * 2] = key;
        }

        // Return either true or false, at pseudo-random, with a bias towards true.
        // This chooses either the primary or secondary cache slot, but the
        // primary slot is approximately twice as likely to be chosen as the
        // secondary one.
        private static boolean chooseVictim(Thread thread) {
            int tmp = thread.victims;
            tmp ^= tmp << 13;
            tmp ^= tmp >>> 17;
            tmp ^= tmp << 5;
            thread.victims = tmp;
            return (tmp & 15) >= 5;
        }

        public static void invalidate() {
            Thread.setScopeLocalCache(null);
        }

        // Null a set of cache entries, indicated by the 1-bits given
        static void invalidate(int toClearBits) {
            assert(toClearBits == (short)toClearBits);
            Object[] objects;
            if ((objects = Thread.scopeLocalCache()) != null) {
                for (short bits = (short)toClearBits; bits != 0; ) {
                    int index = Integer.numberOfTrailingZeros(bits);
                    setKeyAndObjectAt(index, null, null);
                    bits &= ~1 << index;
                }
            }
        }
    }
}

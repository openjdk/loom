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

import static java.lang.ScopedMap.NULL_PLACEHOLDER;

/**
 * This class extends {@code ThreadLocal} to provide inheritance of
 * values from parent thread to child thread: when a thread is started
 * by a bounded construct such as an ExecutorService or a parallel
 * stream, the thread executing the task is passed a reference to the
 * parent's set of LightweightThreadLocals. While the thread is
 * running the parent thread's set of LightweightThreadLocals is
 * immutable: any attempt to modify it returns a LifetimeError. The
 * child thread has its own set of LightweightThreadLocals.
 *
 */

public class LightweightThreadLocal<T> extends ThreadLocal<T> {

    @Stable
    private final int hash = Scoped.generateKey();

    @Stable
    private final Class<T> theType;

    /**
     * TBD
     *
     * @return TBD
     */
    final Class<T> getType() {
        return theType;
    }

    @ForceInline
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private static final Object getObject(int hash, LightweightThreadLocal<?> key) {
        Object[] objects;
        if (Scoped.USE_CACHE && (objects = Thread.scopedCache()) != null) {
            // This code should perhaps be in class Cache. We do it
            // here because the generated code is small and fast and
            // we really want it to be inlined in the caller.
            int n = (hash & Scoped.Cache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
            n = ((hash >>> Scoped.Cache.INDEX_BITS) & Scoped.Cache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
        }
        return key.slowGet(Thread.currentThread());
    }

    @Override
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    public T get() {
        return (T)getObject(hashCode(), this);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.
     *
     * @since 1.5
     */
    public void remove() {
    var map = Thread.currentThread().scopedMap();
        map.remove(hashCode(), this);
        Scoped.Cache.remove(this);
    }

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private T slowGet(Thread thread) {
        Lifetime currentLifetime = thread.currentLifetime();

        var value = NULL_PLACEHOLDER;

        if (Lifetime.version == Lifetime.Version.V1) {
            for (Lifetime aLifetime = currentLifetime;
                 aLifetime != null;
                 aLifetime = aLifetime.parent) {
                var map = aLifetime.scopedMapOrNull();
                if (map == null) continue;
                value = map.get(hashCode(), this);
                if (value != NULL_PLACEHOLDER) break;
            }
        } else {
            for (var t = thread; t != null; t = t.parentThread) {
                var map = t.scopedMapOrNull();
                if (map == null) continue;
                value = map.get(hashCode(), this);
                if (value != NULL_PLACEHOLDER) break;
            }
        }

        if (value == NULL_PLACEHOLDER)
            value = initialValue();

        if (Scoped.USE_CACHE) {
            Scoped.Cache.put(thread, this, value);
        }

        return (T) value;
    }

    /**
     * TBD
     *
     * @param t     TBD
     * @param chain TBD
     * @return TBD
     */
    @Override
    @SuppressWarnings(value = {"unchecked", "rawtypes"})
    // one map has entries for all types <T>
    public void set(T t) {
        if (t != null && ! theType.isInstance(t))
            throw new ClassCastException(ScopedBinding.cannotBindMsg(t, theType));
        var map = Thread.currentThread().scopedMap();
        map.put(hashCode(), this, t);

        Scoped.Cache.update(this, t);
    }

    /**
     * TBD
     *
     * @param t     TBD
     * @param chain TBD
     * @return TBD
     */
    @SuppressWarnings(value = {"unchecked", "rawtypes"})
    // one map has entries for all types <T>
    public ScopedBinding bind(T t) {
        if (t != null && ! theType.isInstance(t))
            throw new ClassCastException(ScopedBinding.cannotBindMsg(t, theType));
        var lifetime = Lifetime.start();
        var map = Thread.currentThread().scopedMap();
        Object previousMapping = map.put(hashCode(), this, t);

        var b = new ScopedBinding(this, t, previousMapping, lifetime);

        Scoped.Cache.update(this, t);

        return b;
    }

    /**
     * Creates an inheritable thread local variable.
     */
    private LightweightThreadLocal() {
        theType = null;
    }

    LightweightThreadLocal(Class<T> klass) {
        theType = klass;
    }

    final void release(Object prev) {
        var map = Thread.currentThread().scopedMap();
        if (prev != NULL_PLACEHOLDER) {
            map.put(hashCode(), this, prev);
        } else {
            map.remove(hashCode(), this);
        }
        Scoped.Cache.remove(this);
    }
}
/*
 * Copyright (c) 2019, Red Hat, Inc. and/or its affiliates. All rights reserved.
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

import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.FieldVisitor;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.FieldAccessor;
import jdk.internal.reflect.Reflection;
import jdk.internal.vm.annotation.ForceInline;
import static jdk.internal.misc.UnsafeConstants.SCOPED_CACHE_SHIFT;
import static jdk.internal.org.objectweb.asm.Opcodes.*;
import static java.lang.ScopedMap.NULL_PLACEHOLDER;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * TBD
 */
public abstract class Scoped<T> {

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    private static final boolean USE_CACHE = Cache.INDEX_BITS > 0;

    private static final boolean DEBUG
        = System.getProperty("java.lang.Scoped.DEBUG") != null
            && System.getProperty("java.lang.Scoped.DEBUG").equals("true");

    private static int nextKey = 0xf0f0_f0f0;

    @ForceInline
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    static final Object getObject(int hash, Scoped<?> key) {
        Object[] objects;
        if (USE_CACHE && (objects = Thread.scopedCache()) != null) {
            // This code should perhaps be in class Cache. We do it
            // here because the generated code is small and fast and
            // we really want it to be inlined in the caller.
            int n = (hash & Cache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.TABLE_MASK) * 2;
            if (objects[n] == key) {
                return objects[n + 1];
            }
        }
        return key.slowGet(Thread.currentThread());
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
        if (t != null && ! getType().isInstance(t))
            throw new ClassCastException(ScopedBinding.cannotBindMsg(t, getType()));
        var lifetime = Lifetime.start();
        var map = Thread.currentThread().scopedMap();
        Object previousMapping = map.put(hashCode(), this, t);

        var b = new ScopedBinding(this, t, previousMapping, lifetime);

        Cache.update(this, t);

        return b;
    }

    /**
     * TBD
     *
     * @param <T>   TBD
     * @param klass TBD
     * @return TBD
     */
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    @CallerSensitive
    public static <T> Scoped<T> forType(Class<T> klass) {
        Class<?> caller = Reflection.getCallerClass();
        return (Scoped<T>) writeClass(klass, generateKey(), caller, Scoped.class);
    }

    /**
     * TBD
     *
     * @param <T>   TBD
     * @param klass TBD
     * @return TBD
     */
    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    @CallerSensitive
    public static <T> Scoped<T> finalForType(Class<T> klass) {
        Class<?> caller = Reflection.getCallerClass();
        return (Scoped<T>) writeClass(klass, generateKey(), caller, ScopedFinal.class);
    }

    /**
     * TBD
     *
     * @return TBD
     */
    public abstract T get();

    abstract Class<?> getType();

    final void release(Object prev) {
        var map = Thread.currentThread().scopedMap();
        if (prev != NULL_PLACEHOLDER) {
            map.put(hashCode(), this, prev);
        } else {
            map.remove(hashCode(), this);
        }
        Cache.remove(this);
    }

    /**
     * TBD
     *
     * @return TBD
     */
    public boolean isBound() {
        var hash = hashCode();
        Object[] objects;
        if (USE_CACHE && (objects = Thread.scopedCache()) != null) {
            int n = (hash & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return true;
            }
            n = ((hash >>> Cache.INDEX_BITS) & Cache.TABLE_MASK) * 2;
            if (objects[n] == this) {
                return true;
            }
        }

        var value = Thread.currentThread().scopedMap().get(hashCode(), this);

        if (value == NULL_PLACEHOLDER)
            return false;

        return true;
    }

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private T slowGet(Thread thread) {
        Lifetime currentLifetime = thread.currentLifetime();

        var value = NULL_PLACEHOLDER;

        for (Lifetime aLifetime = currentLifetime;
             aLifetime != null;
             aLifetime = aLifetime.parent) {
            var map = aLifetime.scopedMapOrNull();
            if (map == null)  continue;
            value = map.get(hashCode(), this);
            if (value != NULL_PLACEHOLDER)  break;
        }

        if (value == NULL_PLACEHOLDER)
            throw new UnboundScopedException("Scoped<" + getType().getName() + "> is not bound");

        if (USE_CACHE) {
            Cache.put(thread, this, value);
        }

        return (T) value;
    }

    // A Marsaglia xor-shift generator used to generate hashes.
    private static synchronized int generateKey() {
        int x = nextKey;
        do {
            x ^= x >>> 12;
            x ^= x << 9;
            x ^= x >>> 23;
        } while (USE_CACHE && ((x & Cache.TABLE_MASK) == ((x >>> Cache.INDEX_BITS)
                & Cache.TABLE_MASK)));
        return (nextKey = x);
    }

    private static long sequenceNumber = 0;

    @SuppressWarnings("unchecked")  // one map has entries for all types <T>
    private static Scoped<?> writeClass(Class<?> klass, int hashKey, Class<?> caller,
                                        Class<?> superClass) {
        long seq;
        synchronized (Scoped.class) {
            seq = sequenceNumber++;
        }
        String superClassName = superClass.getName().replace(".", "/");
        String className = superClassName + "$" + seq;

        ClassWriter cw = new ClassWriter(0);
        cw.visit(V11, ACC_PUBLIC | ACC_SUPER | ACC_FINAL, className, null, superClassName, null);

        {
            FieldVisitor fv = cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "boundClass",
                                            "Ljava/lang/Class;", null, null);
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);

            mv.visitCode();
            mv.visitIntInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, superClassName, "<init>", "()V", false);
            mv.visitInsn(RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);

            mv.visitCode();
            mv.visitLdcInsn(hashKey);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();

        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_FINAL, "getType", "()Ljava/lang/Class;",
                    null, null);

            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, className, "boundClass", "Ljava/lang/Class;");
            mv.visitInsn(ARETURN);
            mv.visitMaxs(2, 1);
            mv.visitEnd();

        }
        {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "get",
                    // "()" + klass.descriptorString(),
                    "()Ljava/lang/Object;",
                    null, null);

            mv.visitCode();
            mv.visitLdcInsn(hashKey);
            mv.visitIntInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Scoped", "getObject", "(ILjava/lang/Scoped;)Ljava/lang/Object;", false);
            // mv.visitTypeInsn(CHECKCAST, klass.getName().replace('.', '/'));
            mv.visitInsn(ARETURN);
            mv.visitMaxs(3, 1);
            mv.visitEnd();
        }
        cw.visitEnd();

        byte[] bytes = cw.toByteArray();

        if (DEBUG) {
            try {
                FileOutputStream out = new FileOutputStream("/tmp/sample/"
                        + className.replace('.', '/') + ".class");
                out.write(bytes);
                out.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        ClassLoader cl = caller.getClass().getClassLoader();
        ProtectionDomain pd = (cl != null) ? Scoped.class.getProtectionDomain() : null;
        Class<?> c = SharedSecrets.getJavaLangAccess().defineClass(cl, className, bytes,
                pd, "Scoped_forType");

        try {
            Field f = c.getDeclaredField("boundClass");
            Object base = UNSAFE.staticFieldBase(f);
            long offset = UNSAFE.staticFieldOffset(f);
            UNSAFE.putReference(base, offset, klass);

            Scoped<?> singleton = (Scoped<?>) UNSAFE.allocateInstance(c);
            if (singleton.getType() != klass) {
                throw new Error("wrong class in Scoped");
            }
            return singleton;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class Cache {
        static final boolean CACHE_LIFETIMES = false;

        static final int INDEX_BITS = SCOPED_CACHE_SHIFT;

        static final int TABLE_SIZE = 1 << INDEX_BITS;

        static final int TABLE_MASK = TABLE_SIZE - 1;

        static boolean isActive(Lifetime lt) {
            if (! CACHE_LIFETIMES)  return false;
            Object[] objects = Thread.scopedCache();
            if (objects == null)  return false;
            int n = TABLE_SIZE;
            return (objects[n] == lt || objects[n+1] == lt);
        }

        static void setActive(Lifetime lt) {
            if (! CACHE_LIFETIMES)  return;
            Object[] objects = Thread.scopedCache();
            if (objects == null) {
                objects = createCache();
            }
            int slot = TABLE_SIZE + (chooseVictim(Thread.currentCarrierThread()) & 1);
            objects[slot] = lt;
        }

        static void clearActive() {
            if (! CACHE_LIFETIMES)  return;
            Object[] objects = Thread.scopedCache();
            if (objects != null) {
                int n = TABLE_SIZE;
                objects[n] = objects[n + 1] = null;
            }
        }

        // An alternative cache for lifetimes which uses two fields in Thread
        // instead of utilizing the scoped cache. Looks like it should work
        // well but performs spectacularly badly on a buffer copying test.

        // static boolean isActive(Lifetime lt) {
        //     if (! CACHE_LIFETIMES)  return false;
        //     Thread t = Thread.currentCarrierThread();
        //     return (t.lt0 == lt || t.lt1 == lt);
        // }

        // static void setActive(Lifetime lt) {
        //     if (! CACHE_LIFETIMES)  return;
        //     Thread t = Thread.currentCarrierThread();
        //     int slot = TABLE_SIZE + (chooseVictim(t) & 1);
        //     if (slot == 0) {
        //         t.lt0 = lt;
        //     } else {
        //         t.lt1 = lt;
        //     }
        // }

        // static void clearActive() {
        //     if (! CACHE_LIFETIMES)  return;
        //     Thread t = Thread.currentCarrierThread();
        //     t.lt0 = t.lt1 = null;
        // }

        static Object[] createCache() {
            Object[] objects = new Object[TABLE_SIZE * 2 + 2];
            Thread.setScopedCache(objects);  // 2 extra slots for lifetimes
            return objects;
        }

        static void put(Thread t, Scoped<?> key, Object value) {
            if (Thread.scopedCache() == null) {
                createCache();
            }
            setKeyAndObjectAt(chooseVictim(t, key.hashCode()), key, value);
        }

        private static final void update(Object key, Object value) {
            Object[] objects;
            if (USE_CACHE && (objects = Thread.scopedCache()) != null) {

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
            if (USE_CACHE && (objects = Thread.scopedCache()) != null) {

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
            return (chooseVictim(thread) & 1) == 0 ? k1 : k2;
        }

        private static int chooseVictim(Thread thread) {
            // Update the cache to replace one entry with the value we just looked up.
            // Each value can be in one of two possible places in the cache.
            // Pick a victim at (pseudo-)random.
            int tmp = thread.victims;
            thread.victims = (tmp << 31) | (tmp >>> 1);
            return tmp & TABLE_MASK;
        }

        static void clearCache() {
            // We need to do this when we yield a Continuation.
            if (! USE_CACHE) return;
            Object[] objects = Thread.scopedCache();
            if (objects != null) {
                Arrays.fill(objects, null);
            }
        }
    }


}

abstract class ScopedFinal<T> extends Scoped<T> {
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
        if (isBound()) {
            throw new ScopedAlreadyBoundException("Scoped<" + getType().getName()
                                                  + "> is already bound");
        }
        return super.bind(t);
    }
}

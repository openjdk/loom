/*
 * Copyright (c) 2001, 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.reflect;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import jdk.internal.misc.Unsafe;
import sun.reflect.misc.ReflectUtil;

/** Used only for the first few invocations of a Method; afterward,
    switches to bytecode-based implementation */

class NativeMethodAccessorImpl extends MethodAccessorImpl {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long GENERATED_OFFSET
        = U.objectFieldOffset(NativeMethodAccessorImpl.class, "generated");

    private final Method method;
    private DelegatingMethodAccessorImpl parent;
    private int numInvocations;
    private volatile int generated;

    NativeMethodAccessorImpl(Method method) {
        this.method = method;
    }

    public Object invoke(Object obj, Object[] args)
        throws IllegalArgumentException, InvocationTargetException
    {
        boolean generate = false;
        if (Thread.currentThread().isVirtual()) {
            generate = true;
        } else {
            if (++numInvocations > ReflectionFactory.inflationThreshold()
                    && generated == 0
                    && U.compareAndSetInt(this, GENERATED_OFFSET, 0, 1)) {
                generate = true;
            }
        }

        if (generate) {
            Class<?> declaringClass = method.getDeclaringClass();

            // class initializer may not have run
            Unsafe.getUnsafe().ensureClassInitialized(declaringClass);

            MethodAccessorImpl acc;
            try {
                // use bytecode generated implementation for @CS methods for now
                if (Reflection.isCallerSensitive(method)) {
                    assert !declaringClass.isHidden()
                        && !ReflectUtil.isVMAnonymousClass(declaringClass);

                    acc = (MethodAccessorImpl) new MethodAccessorGenerator()
                                .generateMethod(declaringClass,
                                                method.getName(),
                                                method.getParameterTypes(),
                                                method.getReturnType(),
                                                method.getExceptionTypes(),
                                                method.getModifiers());
                } else {
                    acc = NewAccessorImplFactory.newMethodAccessorImpl(method);
                }
            } catch (Throwable t) {
                // generateMethod failed, restore generated to 0
                generated = 0;
                throw t;
            }

            parent.setDelegate(acc);
            return acc.invoke(obj, args);
        }

        return invoke0(method, obj, args);
    }

    void setParent(DelegatingMethodAccessorImpl parent) {
        this.parent = parent;
    }

    private static native Object invoke0(Method m, Object obj, Object[] args);
}

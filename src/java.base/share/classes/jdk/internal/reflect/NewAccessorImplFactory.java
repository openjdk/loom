/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import static java.lang.invoke.MethodType.methodType;
import jdk.internal.access.JavaLangInvokeAccess;
import jdk.internal.access.SharedSecrets;

/**
 * Factory to create MethodAccessor and ConstructorAccessor implementations
 * based on method handles.
 */
class NewAccessorImplFactory {
    private static final JavaLangInvokeAccess JLIA = SharedSecrets.getJavaLangInvokeAccess();
    private static final MethodHandle WRAP;
    static {
        try {
            var methodType = methodType(void.class, Throwable.class);
            WRAP = JLIA.privilegedFindStatic(NewAccessorImplFactory.class, "wrap", methodType);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private NewAccessorImplFactory() { }

    /**
     * Creates a MethodAccessorImpl to invoke the given method. The method should
     * not be caller sensitive.
     */
    static MethodAccessorImpl newMethodAccessorImpl(Method method) {
        assert !Reflection.isCallerSensitive(method);

        MethodHandle target = JLIA.privilegedUnreflect(method);

        // adapt to run with exception handler that throws InvocationTargetException
        target = adaptToWrapException(target);

        int paramCount = method.getParameterCount();
        if (Modifier.isStatic(method.getModifiers())) {
            MethodHandle spreader = target.asSpreader(Object[].class, paramCount);
            spreader = MethodHandles.dropArguments(spreader, 0, Object.class);
            target = spreader.asType(methodType(Object.class, Object.class, Object[].class));
        } else {
            // instance method
            MethodHandle spreader = target.asSpreader(Object[].class, paramCount);
            target = spreader.asType(methodType(Object.class, Object.class, Object[].class));
        }

        MethodHandle targetMethod = target;
        return new MethodAccessorImpl() {
            @Override
            public Object invoke(Object obj, Object[] args) throws InvocationTargetException {
                try {
                    return targetMethod.invokeExact(obj, args);
                } catch (IllegalArgumentException | InvocationTargetException e) {
                    throw e;
                } catch (ClassCastException | NullPointerException e) {
                    throw new IllegalArgumentException(e.getMessage());
                } catch (Throwable e) {
                    throw new InvocationTargetException(e);
                }
            }
        };
    }

    /**
     * Creates a ConstructorAccessorImpl to construct an object with the given
     * constructor.
     */
    static ConstructorAccessorImpl newConstructorAccessorImpl(Constructor<?> ctor) {
        MethodHandle target = JLIA.privilegedUnreflect(ctor);

        // adapt to run with exception handler that throws InvocationTargetException
        target = adaptToWrapException(target);

        int paramCount = ctor.getParameterCount();
        MethodHandle spreader = target.asSpreader(Object[].class, paramCount);
        target = spreader.asType(methodType(Object.class, Object[].class));

        MethodHandle targetMethod = target;
        return new ConstructorAccessorImpl() {
            @Override
            public Object newInstance(Object[] args) throws InvocationTargetException {
                try {
                    return targetMethod.invokeExact(args);
                } catch (IllegalArgumentException | InvocationTargetException e) {
                    throw e;
                } catch (ClassCastException | NullPointerException e) {
                    throw new IllegalArgumentException(e.getMessage());
                } catch (Throwable e) {
                    throw new InvocationTargetException(e);
                }
            }
        };
    }

    /**
     * Creates a method that adapts the given method handle to run into
     * inside an exception handler.
     */
    private static MethodHandle adaptToWrapException(MethodHandle target) {
        MethodHandle wrapper = WRAP.asType(methodType(target.type().returnType(), Throwable.class));
        return MethodHandles.catchException(target, Throwable.class, wrapper);
    }

    /**
     * Throws InvocationTargetException with the given exception or error
     * as cause.
     */
    private static void wrap(Throwable e) throws InvocationTargetException {
        throw new InvocationTargetException(e);
    }
}

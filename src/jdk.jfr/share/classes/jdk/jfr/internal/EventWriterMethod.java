/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jfr.internal;

import jdk.internal.org.objectweb.asm.commons.Method;
import jdk.jfr.internal.EventInstrumentation.FieldInfo;
import jdk.jfr.internal.event.EventConfiguration;

public enum EventWriterMethod {

    BEGIN_EVENT("(" + jdk.internal.org.objectweb.asm.Type.getType(EventConfiguration.class).getDescriptor() + "J)Z", "???", "beginEvent"),
    END_EVENT("()Z", "???", "endEvent"),
    PUT_BYTE("(B)V", "byte", "putByte"),
    PUT_SHORT("(S)V", "short", "putShort"),
    PUT_INT("(I)V", "int", "putInt"),
    PUT_LONG("(J)V", "long", "putLong"),
    PUT_FLOAT("(F)V", "float", "putFloat"),
    PUT_DOUBLE("(D)V", "double", "putDouble"),
    PUT_CHAR("(C)V", "char", "putChar"),
    PUT_BOOLEAN("(Z)V", "boolean", "putBoolean"),
    PUT_THREAD("(Ljava/lang/Thread;)V", Type.THREAD.getName(), "putThread"),
    PUT_CLASS("(Ljava/lang/Class;)V", Type.CLASS.getName(), "putClass"),
    PUT_STRING("(Ljava/lang/String;)V", Type.STRING.getName(), "putString"),
    PUT_EVENT_THREAD("()V", Type.THREAD.getName(), "putEventThread"),
    PUT_STACK_TRACE("()V", Type.TYPES_PREFIX + "StackTrace", "putStackTrace");

    final Method asmMethod;
    final String typeDescriptor;

    EventWriterMethod(String paramSignature, String typeName, String methodName) {
        this.typeDescriptor = ASMToolkit.getDescriptor(typeName);
        this.asmMethod = new Method(methodName, paramSignature);
    }

    public Method asASM() {
        return asmMethod;
    }

    /**
     * Return method in {@link EventWriter} class to use when writing event of
     * a certain type.
     *
     * @param v field info
     *
     * @return the method
     */
    public static EventWriterMethod lookupMethod(FieldInfo field) {
        // event thread
        if (field.name().equals(EventInstrumentation.FIELD_EVENT_THREAD)) {
            return EventWriterMethod.PUT_EVENT_THREAD;
        }
        for (EventWriterMethod m : EventWriterMethod.values()) {
            if (field.descriptor().equals(m.typeDescriptor)) {
                return m;
            }
        }
        throw new Error("Unknown type " + field.descriptor());
    }
}

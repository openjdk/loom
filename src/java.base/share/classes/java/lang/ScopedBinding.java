/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import jdk.internal.misc.UnsafeConstants;

/**
 * TBD
 */
public final class ScopedBinding
    implements AutoCloseable {

    final Scoped<?> referent;

    final Object prev;

    static String cannotBindMsg(Object obj, Class<?> klass) {
        return "Cannot bind " + obj.getClass().getName() + " to " + klass.getName();
    }

    /**
     * TBD
     * @param v TBD
     * @param t TBD
     * @param prev TBD
     */
    ScopedBinding(Scoped<?> v, Object t, Object prev) {
        if (t != null && !v.getType().isInstance(t))
            throw new ClassCastException(cannotBindMsg(t, v.getType()));
        referent = v;
        this.prev = prev;
    }

    /**
     * TBD
     */
    public final void close() {
        referent.release(prev);
    }
}

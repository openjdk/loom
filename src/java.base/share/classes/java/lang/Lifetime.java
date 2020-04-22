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

/**
 * TBD
 * A Lifetime is a Thing.
 */
public class Lifetime {
    // v1:
    final Lifetime parent;

    // v2:
    final Thread thread;
    private int depth;

    ScopedMap scopedMap;

    final int index;

    private static boolean USE_CACHE = true;

    enum Version { V1, V2 }

    static final Version version = Version.V2;

    static private int counter;

    private Lifetime(Lifetime parent, Thread thread, int depth) {
        this.parent = parent;
        this.thread = thread;
        this.depth = depth;
        synchronized (Lifetime.class){
            index = counter++;
        }
    }

    // v1:
    private Lifetime(Lifetime parent) {
        this(parent, null, 0);
    }

    // v2:
    Lifetime(Thread thread, int depth) {
        this(null, thread, depth);
    }
    int depth() {
        return depth;
    }

    /**
     * TBD
     * @return Lifetime
     */
    static public Lifetime start() {
        if (version == Version.V1) {
            var t = Thread.currentThread();
            var lt = new Lifetime(t.currentLifetime());
            t.pushLifetime(lt);
            return lt;
        } else {
            return Thread.currentThread().pushLifetime();
        }
    }

    /**
     * TBD
     */
    public void close() {
        if (Lifetime.version == Lifetime.Version.V1) {
            Thread.currentThread().popLifetime(this);
        } else {
            thread.popLifetime(this);
            this.depth = Integer.MAX_VALUE;
        }
    }

    final ScopedMap scopedMap() {
        var map = scopedMapOrNull();
        if (map == null) {
            map = scopedMap = new ScopedMap();
        }
        return map;
    }

    final ScopedMap scopedMapOrNull() {
        if (version == Version.V1) {
            return scopedMap;
        } else {
            return thread.scopedMap();
        }
    }

    public String toString() {
        return "parent: " + parent + " index: " + index;
    }
}
/*
 * Copyright (c) 2002, 2018, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;


// Signalling operations on native threads

public class NativeThread {
    private static final long VIRTUAL_THREAD_ID = -1L;

    /**
     * Returns a token representing the current thread or -1 if called in the
     * context of a virtual thread
     */
    public static long current() {
        if (Thread.currentThread().isVirtual()) {
            return VIRTUAL_THREAD_ID;
        } else {
            return 0;
        }
    }

    static long currentKernelThread() {
        return 0;
    }

    static void signal(long tid) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns true if the token presents a virtual thread
     */
    static boolean isVirtualThread(long tid) {
        return (tid == VIRTUAL_THREAD_ID);
    }

    static boolean isKernelThread(long tid) {
        return false;
    }
}

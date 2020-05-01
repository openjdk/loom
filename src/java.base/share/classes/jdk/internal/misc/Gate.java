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

package jdk.internal.misc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A gate object to detect reentrancy. A thread that enters the gate must exit
 * before it can enter again. Multiple threads can be "inside" at the same time.
 * A set of threads to track those that are inside to avoid using ThreadLocals.
 */
public class Gate {
    // holder class with thread set to allow it be lazily created
    private static class Holder {
        private static final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        static Set<Thread> threads() {
            return threads;
        }
    }

    private Gate() { }

    /**
     * Creates a new gate.
     */
    public static Gate create() {
        return new Gate();
    }

    /**
     * Enters if not already inside. The current thread is added to the set of threads
     * that are inside, if not already in the set.
     * @return true if the current thread entered, false if already inside.
     */
    public boolean tryEnter() {
        Set<Thread> threads = Holder.threads();
        return threads.add(Thread.currentThread());
    }

    /**
     * Enter. The current thread is added to the set of threads that are inside.
     * @throws IllegalStateException if current thread is already inside
     */
    public void enter() {
        if (!tryEnter())
            throw new IllegalStateException();
    }

    /**
     * Exit. The current thread is removed from the set of threads that are inside.
     * @throws IllegalStateException if the current thread is not inside
     */
    public void exit() {
        Set<Thread> threads = Holder.threads();
        boolean removed = threads.remove(Thread.currentThread());
        if (!removed)
            throw new IllegalStateException();
    }

    /**
     * Returns true if the current thread is inside.
     */
    public boolean inside() {
        Set<Thread> threads = Holder.threads();
        return threads.contains(Thread.currentThread());
    }
}

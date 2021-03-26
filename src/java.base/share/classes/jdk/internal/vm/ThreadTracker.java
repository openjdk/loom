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
package jdk.internal.vm;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import sun.security.action.GetPropertyAction;

/**
 * Thread tracking, for debugging/monitoring purposes.
 */
public class ThreadTracker {
    // the set of all virtual threads when tracking is enabled, otherwise null
    private static final Set<Thread> VIRTUAL_THREADS;
    static {
        String s = GetPropertyAction.privilegedGetProperty("jdk.trackAllVirtualThreads");
        if (s != null && (s.isEmpty() || s.equalsIgnoreCase("true"))) {
            VIRTUAL_THREADS = ConcurrentHashMap.newKeySet();
        } else {
            VIRTUAL_THREADS = null;
        }
    }

    private ThreadTracker() { }

    /**
     * Notifies the thread dumper of new virtual thread. A no-op if tracking of
     * virtual threads is not enabled.
     */
    public static void notifyStart(Thread thread) {
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            assert thread.isVirtual();
            threads.add(thread);
        }
    }

    /**
     * Notifies the thread dumper that a virtual thread has virtual. A no-op if
     * tracking of virtual threads is not enabled.
     */
    public static void notifyTerminate(Thread thread) {
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            assert thread.isVirtual();
            threads.remove(thread);
        }
    }

    /**
     * Returns the set of virtual threads, if tracked, or an empty {@code Optional}
     * if not tracked.
     */
    public static Optional<Set<Thread>> virtualThreads() {
        if (VIRTUAL_THREADS == null) {
            return Optional.empty();
        } else {
            return Optional.of(Collections.unmodifiableSet(VIRTUAL_THREADS));
        }
    }
}

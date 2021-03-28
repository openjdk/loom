/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.management;

import java.util.Set;
import java.util.stream.Stream;
import jdk.internal.vm.ThreadTracker;

/**
 * This class consists exclusively of static methods that provide access to
 * threads in the Java virtual machine. It is intended for use by debuggers
 * or monitoring tools that run with the system property
 * "{@code jdk.trackAllVirtualThreads}" set to the value of "{@code true}" to
 * track all virtual threads in the runtime.
 *
 * @since 99
 */
public class Threads {
    private Threads() { }

    /**
     * Returns the stream of the virtual threads that have been started but have
     * not terminated. Returns an empty stream if tracking of virtual threads
     * is not enabled.
     *
     * @return a stream of virtual threads
     * @throws SecurityException if denied by the security manager
     */
    public static Stream<Thread> virtualThreads() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("modifyThreadGroup"));
        }
        return ThreadTracker.virtualThreads().orElse(Set.of()).stream();
    }
}
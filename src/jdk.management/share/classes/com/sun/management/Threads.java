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

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import jdk.internal.vm.ThreadTracker;

/**
 * This class consists exclusively of static methods that provide access to threads
 * in the Java virtual machine. It is intended for use by debugging and monitoring
 * tools.
 *
 * @since 99
 */
public class Threads {
    private Threads() { }

    private static void checkPermission() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("modifyThreadGroup"));
        }
    }

    /**
     * Returns the stream of the virtual threads that have been started but have
     * not terminated. Returns an empty stream if tracking of virtual threads is
     * not enabled.
     *
     * <p> This method requires that the Java virtual machine be started with the
     * system property "{@code jdk.trackAllVirtualThreads}" set to the value of
     * "{@code true}", otherwise virtual threads are not tracked.
     *
     * @return the stream of virtual threads
     * @throws SecurityException if denied by the security manager
     */
    public static Stream<Thread> virtualThreads() {
        checkPermission();
        return ThreadTracker.virtualThreads().orElse(Set.of()).stream();
    }

    /**
     * A thread executor.
     * @since 99
     * @see java.util.concurrent.Executors#newThreadExecutor(ThreadFactory)
     */
    public interface ThreadExecutor {
        /**
         * {@return the stream of the threads running in this executor}
         */
        Stream<Thread> threads();
    }

    private static class ThreadExecutorImpl implements ThreadExecutor {
        private final jdk.internal.vm.ThreadExecutor delegate;
        ThreadExecutorImpl(jdk.internal.vm.ThreadExecutor delegate) {
            this.delegate = delegate;
        }
        static ThreadExecutor wrap(jdk.internal.vm.ThreadExecutor delegate) {
            return new ThreadExecutorImpl(delegate);
        }
        public Stream<Thread> threads() {
            return delegate.threads();
        }
    }

    /**
     * Returns the list of active thread executors owned by the given thread.
     * The list is ordered, enclosing executors before nested executors.
     *
     * @param thread the thread
     * @return the list of active thread executors owned by the thread
     * @throws SecurityException if denied by the security manager
     * @see java.util.concurrent.Executors#newThreadExecutor(ThreadFactory)
     */
    public static List<ThreadExecutor> executors(Thread thread) {
        checkPermission();
        return jdk.internal.vm.ThreadExecutor.executors(thread).stream()
                .map(c -> ThreadExecutorImpl.wrap(c))
                .toList();
    }

}
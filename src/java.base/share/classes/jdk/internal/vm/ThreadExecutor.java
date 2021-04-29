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
package jdk.internal.vm;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

/**
 * A Executor of threads, a proxy to a java.util.concurrent.ThreadExecutor.
 *
 * @see java.util.concurrent.Executors#newThreadExecutor(ThreadFactory)
 */
public class ThreadExecutor {
    private final Object threadExecutor;

    private ThreadExecutor(Object threadExecutor) {
        this.threadExecutor = Objects.requireNonNull(threadExecutor);
    }

    private static ThreadExecutor latestThreadExecutor(Thread thread) {
        Object threadExecutor = Threads.latestThreadExecutor(thread);
        if (threadExecutor != null) {
            return new ThreadExecutor(threadExecutor);
        } else {
            return null;
        }
    }

    private ThreadExecutor previous() {
        Object previous = ThreadExecutors.previous(threadExecutor);
        if (previous != null) {
            return new ThreadExecutor(previous);
        } else {
            return null;
        }
    }

    /**
     * Returns the stream of the threads running in this Executor.
     */
    public Stream<Thread> threads() {
        return ThreadExecutors.threads(threadExecutor).stream();
    }

    /**
     * Returns the list of active thread executors owned by the given thread.
     * The list is ordered, enclosing executors before nested executors.
     */
    public static List<ThreadExecutor> executors(Thread thread) {
        Objects.requireNonNull(thread);
        ThreadExecutor executor = latestThreadExecutor(thread);
        if (executor == null) {
            return List.of();
        } else {
            List<ThreadExecutor> list = new ArrayList<>();
            while (executor != null) {
                list.add(executor);
                executor = executor.previous();
            }
            Collections.reverse(list);
            return list;
        }
    }

    @Override
    public int hashCode() {
        return threadExecutor.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ThreadExecutor other) {
            return this.threadExecutor == other.threadExecutor;
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        return threadExecutor.getClass().getName()
                + "@" + System.identityHashCode(threadExecutor);
    }

    /**
     * Provides read access to Thread.latestThreadExecutor
     */
    private static class Threads {
        private static final VarHandle LATEST_THREAD_EXECUTOR;
        static {
            try {
                PrivilegedExceptionAction<MethodHandles.Lookup> pa = () ->
                        MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
                MethodHandles.Lookup l = AccessController.doPrivileged(pa);
                LATEST_THREAD_EXECUTOR = l.findVarHandle(Thread.class, "latestThreadExecutor", Object.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static Object latestThreadExecutor(Thread thread) {
            return LATEST_THREAD_EXECUTOR.getVolatile(thread);
        }
    }

    /**
     * Provides access to java.util.concurrent.ThreadExecutor
     */
    private static class ThreadExecutors {
        private static final VarHandle THREADS;
        private static final VarHandle PREVIOUS;
        static {
            try {
                Class<?> clazz = Class.forName("java.util.concurrent.ThreadExecutor");
                PrivilegedExceptionAction<MethodHandles.Lookup> pa = () ->
                        MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
                MethodHandles.Lookup l = AccessController.doPrivileged(pa);
                THREADS = l.findVarHandle(clazz, "threads", Set.class);
                PREVIOUS = l.findVarHandle(clazz, "previous", clazz);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        @SuppressWarnings("unchecked")
        static Set<Thread> threads(Object threadExecutor) {
            return (Set<Thread>) THREADS.get(threadExecutor);
        }
        static Object previous(Object threadExecutor) {
            return PREVIOUS.get(threadExecutor);
        }
    }
}


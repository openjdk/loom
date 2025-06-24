/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.management.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.management.ObjectName;
import jdk.management.VirtualThreadSchedulerMXBean;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.ContinuationSupport;
import sun.management.Util;

/**
 * Provides the implementation of the management interface for the JDK's default virtual
 * thread scheduler.
 */
public class VirtualThreadSchedulerImpls {
    private VirtualThreadSchedulerImpls() {
    }

    public static VirtualThreadSchedulerMXBean create() {
        if (ContinuationSupport.isSupported()) {
            return new VirtualThreadSchedulerImpl();
        } else {
            return new BoundVirtualThreadSchedulerImpl();
        }
    }

    /**
     * Base implementation of VirtualThreadSchedulerMXBean.
     */
    private abstract static class BaseVirtualThreadSchedulerImpl
            implements VirtualThreadSchedulerMXBean {

        @Override
        public final ObjectName getObjectName() {
            return Util.newObjectName("jdk.management:type=VirtualThreadScheduler");
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("[parallelism=");
            sb.append(getParallelism());
            append(sb, "size", getPoolSize());
            append(sb, "mounted", getMountedVirtualThreadCount());
            append(sb, "queued", getQueuedVirtualThreadCount());
            sb.append(']');
            return sb.toString();
        }

        private void append(StringBuilder sb, String name, long value) {
            sb.append(", ").append(name).append('=');
            if (value >= 0) {
                sb.append(value);
            } else {
                sb.append("<unavailable>");
            }
        }
    }

    /**
     * Implementation of VirtualThreadSchedulerMXBean when virtual threads are
     * implemented with continuations + scheduler.
     */
    private static final class VirtualThreadSchedulerImpl extends BaseVirtualThreadSchedulerImpl {
        /**
         * Holder class for scheduler.
         */
        private static class Scheduler {
            private static final Executor SCHEDULER =
                SharedSecrets.getJavaLangAccess().virtualThreadDefaultScheduler();
            static Executor instance() {
                return SCHEDULER;
            }
        }

        /**
         * Reflective access for custom schedulers.
         */
        private static class SchedulerMethods {
            private final static Method GET_PARALLELISM = findMethod("getParallelism");
            private final static Method SET_PARALLELISM = findMethod("setParallelism", int.class);
            private final static Method GET_POOL_SIZE = findMethod("getPoolSize");
            private final static Method GET_MOUNTED_VTHREAD_COUNT = findMethod("getMountedVirtualThreadCount");
            private final static Method GET_QUEUED_VTHREAD_COUNT = findMethod("getQueuedVirtualThreadCount");

            static Method findMethod(String method, Class<?>... params) {
                try {
                    return Scheduler.instance().getClass().getMethod(method, params);
                } catch (Exception e) {
                    return null;
                }
            }
        }

        @Override
        public int getParallelism() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getParallelism();
            }

            // custom scheduler
            if (SchedulerMethods.GET_PARALLELISM instanceof Method m) {
                return (int) invokeSchedulerMethod(m);
            }

            return -1;  // unknown
        }

        @Override
        public void setParallelism(int size) {Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                pool.setParallelism(size);
                if (pool.getPoolSize() < size) {
                    // FJ worker thread creation is on-demand
                    Thread.startVirtualThread(() -> { });
                }
                return;
            }

            // custom scheduler
            if (SchedulerMethods.SET_PARALLELISM instanceof Method m) {
                invokeSchedulerMethod(m, size);
            }

            throw new UnsupportedOperationException();
        }

        @Override
        public int getPoolSize() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getPoolSize();
            }

            // custom scheduler
            if (SchedulerMethods.GET_POOL_SIZE instanceof Method m) {
                return (int) invokeSchedulerMethod(m);
            }

            return -1;  // unknown
        }

        @Override
        public int getMountedVirtualThreadCount() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getActiveThreadCount();
            }

            // custom scheduler
            if (SchedulerMethods.GET_MOUNTED_VTHREAD_COUNT instanceof Method m) {
                return (int) invokeSchedulerMethod(m);
            }

            return -1;  // unknown
        }

        @Override
        public long getQueuedVirtualThreadCount() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
            }

            // custom scheduler
            if (SchedulerMethods.GET_QUEUED_VTHREAD_COUNT instanceof Method m) {
                return (long) invokeSchedulerMethod(m);
            }

            return -1L;  // unknown
        }

        private static Object invokeSchedulerMethod(Method m, Object... args) {
            try {
                return m.invoke(Scheduler.instance(), args);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Implementation of VirtualThreadSchedulerMXBean when virtual threads are backed
     * by platform threads.
     */
    private static final class BoundVirtualThreadSchedulerImpl extends BaseVirtualThreadSchedulerImpl {
        @Override
        public int getParallelism() {
            return Integer.MAX_VALUE;
        }

        @Override
        public void setParallelism(int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getPoolSize() {
            return -1;
        }

        @Override
        public int getMountedVirtualThreadCount() {
            return -1;
        }

        @Override
        public long getQueuedVirtualThreadCount() {
            return -1L;
        }
    }
}


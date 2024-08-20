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

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
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

        abstract void impSetParallelism(int size);

        @Override
        public final void setParallelism(int size) {
            Util.checkControlAccess();
            impSetParallelism(size);
        }

        @Override
        public final ObjectName getObjectName() {
            return Util.newObjectName("jdk.management:type=VirtualThreadScheduler");
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("[parallelism=");
            sb.append(getParallelism());
            append(sb, "threads", getThreadCount());
            append(sb, "carriers", getCarrierThreadCount());
            append(sb, "queued-vthreads", getQueuedVirtualThreadCount());
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
    private static class VirtualThreadSchedulerImpl extends BaseVirtualThreadSchedulerImpl {
        VirtualThreadSchedulerImpl() {
        }

        /**
         * Holder class for scheduler.
         */
        private static class Scheduler {
            private static final Executor scheduler =
                SharedSecrets.getJavaLangAccess().virtualThreadDefaultScheduler();

            static Executor instance() {
                return scheduler;
            }
        }

        @Override
        public int getParallelism() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getParallelism();
            }
            if (scheduler instanceof ThreadPoolExecutor pool) {
                return pool.getMaximumPoolSize();
            }
            return -1;
        }

        @Override
        void impSetParallelism(int size) {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                pool.setParallelism(size);
                return;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public int getThreadCount() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getPoolSize();
            }
            if (scheduler instanceof ThreadPoolExecutor pool) {
                return pool.getPoolSize();
            }
            return -1;
        }

        @Override
        public int getCarrierThreadCount() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getActiveThreadCount();
            }
            if (scheduler instanceof ThreadPoolExecutor pool) {
                return pool.getActiveCount();
            }
            return -1;
        }

        @Override
        public long getQueuedVirtualThreadCount() {
            Executor scheduler = Scheduler.instance();
            if (scheduler instanceof ForkJoinPool pool) {
                return pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
            }
            if (scheduler instanceof ThreadPoolExecutor pool) {
                return pool.getQueue().size();
            }
            return -1L;
        }
    }

    /**
     * Implementation of VirtualThreadSchedulerMXBean when virtual threads are backed
     * by platform threads.
     */
    private static class BoundVirtualThreadSchedulerImpl extends BaseVirtualThreadSchedulerImpl {
        @Override
        public int getParallelism() {
            return Integer.MAX_VALUE;
        }

        @Override
        void impSetParallelism(int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getThreadCount() {
            return -1;
        }

        @Override
        public int getCarrierThreadCount() {
            return -1;
        }

        @Override
        public long getQueuedVirtualThreadCount() {
            return -1L;
        }
    }
}
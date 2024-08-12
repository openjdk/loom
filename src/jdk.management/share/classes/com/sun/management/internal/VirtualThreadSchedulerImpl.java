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
import sun.management.Util;

/**
 * Implements the management interface for the JDK's default virtual thread scheduler.
 */
public class VirtualThreadSchedulerImpl implements VirtualThreadSchedulerMXBean {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    public VirtualThreadSchedulerImpl() {
    }

    @Override
    public int getParallelism() {
        Executor scheduler = JLA.virtualThreadDefaultScheduler();
        if (scheduler instanceof ForkJoinPool pool) {
            return pool.getParallelism();
        }
        return -1;
    }

    @Override
    public void setParallelism(int size) {
        Util.checkControlAccess();
        Executor scheduler = JLA.virtualThreadDefaultScheduler();
        if (scheduler instanceof ForkJoinPool pool) {
            pool.setParallelism(size);
            return;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public int getThreadCount() {
        Executor scheduler = JLA.virtualThreadDefaultScheduler();
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
        Executor scheduler = JLA.virtualThreadDefaultScheduler();
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
        Executor scheduler = JLA.virtualThreadDefaultScheduler();
        if (scheduler instanceof ForkJoinPool pool) {
            return pool.getQueuedTaskCount() + pool.getQueuedSubmissionCount();
        }
        if (scheduler instanceof ThreadPoolExecutor pool) {
            return pool.getQueue().size();
        }
        return -1L;
    }

    @Override
    public ObjectName getObjectName() {
        return Util.newObjectName("jdk.management:type=VirtualThreadScheduler");
    }
}
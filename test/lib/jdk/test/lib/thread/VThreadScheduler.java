/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.test.lib.thread;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Helper class to allow tests run virtual threads with a custom scheduler.
 *
 * Tests using this class need to open java.base/java.lang.
 */
public class VThreadScheduler {
    private VThreadScheduler() { }

    /**
     * Returns the default virtual thread scheduler.
     */
    public static Thread.VirtualThreadScheduler defaultScheduler() {
        try {
            Method m = Class.forName("java.lang.VirtualThread")
                    .getDeclaredMethod("defaultScheduler");
            m.setAccessible(true);
            return (Thread.VirtualThreadScheduler ) m.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the scheduler for the given virtual thread.
     */
    public static Thread.VirtualThreadScheduler scheduler(Thread thread) {
        if (!thread.isVirtual())
            throw new IllegalArgumentException("Not a virtual thread");
        try {
            Field scheduler = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("scheduler");
            scheduler.setAccessible(true);
            return (Thread.VirtualThreadScheduler) scheduler.get(thread);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return true if custom schedulers are supported.
     */
    public static boolean supportsCustomScheduler() {
        try (var pool = Executors.newCachedThreadPool()) {
            try {
                virtualThreadBuilder(pool);
                return true;
            } catch (UnsupportedOperationException e) {
                return false;
            }
        }
    }

    /**
     * Returns a builder to create virtual threads that use the given scheduler.
     * @throws UnsupportedOperationException if custom schedulers are not supported
     */
    @SuppressWarnings("restricted")
    public static Thread.Builder.OfVirtual virtualThreadBuilder(Thread.VirtualThreadScheduler scheduler) {
        return Thread.ofVirtual().scheduler(scheduler);
    }

    public static Thread.Builder.OfVirtual virtualThreadBuilder(Executor executor) {
        Thread.VirtualThreadScheduler scheduler = (_, task) -> executor.execute(task);
        return virtualThreadBuilder(scheduler);
    }

    /**
     * Returns a ThreadFactory to create virtual threads that use the given scheduler.
     * @throws UnsupportedOperationException if custom schedulers are not supported
     */
    public static ThreadFactory virtualThreadFactory(Thread.VirtualThreadScheduler scheduler) {
        return virtualThreadBuilder(scheduler).factory();
    }

    public static ThreadFactory virtualThreadFactory(Executor executor) {
        return virtualThreadBuilder(executor).factory();
    }
}

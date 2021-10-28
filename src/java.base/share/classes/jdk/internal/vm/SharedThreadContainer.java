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
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Stream;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * A "shared" thread container. A shared thread container doesn't have an owner
 * and is intended for unstructured uses, e.g. thread pools.
 */
public class SharedThreadContainer extends ThreadContainer implements AutoCloseable {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private static final VarHandle CLOSED;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            CLOSED = l.findVarHandle(SharedThreadContainer.class, "closed", boolean.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private final String name;
    private final Supplier<Stream<Thread>> threadsSupplier;
    private final LongAdder threadCount;
    private final Object key;
    private volatile boolean closed;

    private SharedThreadContainer(String name, Supplier<Stream<Thread>> threadsSupplier) {
        this.name = name;
        if (threadsSupplier != null) {
            this.threadsSupplier = threadsSupplier;
            this.threadCount = null;
        } else {
            this.threadsSupplier = null;
            this.threadCount = new LongAdder();
        }
        this.key = ThreadContainers.registerContainer(this);
    }

    /**
     * Creates a shared thread container with the given name.
     */
    public static SharedThreadContainer create(String name) {
        return new SharedThreadContainer(name, null);
    }

    /**
     * Creates a shared thread container with the given name and threads supplier.
     */
    public static SharedThreadContainer create(String name,
                                               Supplier<Stream<Thread>> threadsSupplier) {
        return new SharedThreadContainer(name, threadsSupplier);
    }

    @Override
    public ThreadContainer push() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Thread owner() {
        return null;
    }

    @Override
    public void onStart(Thread thread) {
        if (threadCount != null) {
            threadCount.add(1L);
        }
    }

    @Override
    public void onExit(Thread thread) {
        if (threadCount != null) {
            threadCount.add(-1L);
        }
    }

    @Override
    public long threadCount() {
        if (threadCount != null) {
            return threadCount.sum();
        } else {
            return threads().mapToLong(e -> 1L).sum();
        }
    }

    @Override
    public Stream<Thread> threads() {
        if (threadsSupplier != null) {
            return threadsSupplier.get();
        } else {
            return Stream.of(JLA.getAllThreads())
                    .filter(t -> JLA.threadContainer(t) == this);
        }
    }

    /**
     * Starts a thread in this container.
     * @throws IllegalStateException if the container is closed
     */
    public void start(Thread thread) {
        if (closed)
            throw new IllegalStateException();
        JLA.start(thread, this);
    }

    /**
     * Closes this container. Further attempts to start a thread in this container
     * throw IllegalStateException. This method has no impact on threads that are
     * still running or starting around the time that this method is invoked.
     */
    @Override
    public void close() {
        if (!closed && CLOSED.compareAndSet(this, false, true)) {
            ThreadContainers.deregisterContainer(key);
        }
    }

    @Override
    public String toString() {
        String id = getClass().getName() + "@" + System.identityHashCode(this);
        String name = name();
        if (name != null) {
            return name + "/" + id;
        } else {
            return id;
        }
    }
}

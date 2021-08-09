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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import sun.nio.ch.Poller;

/**
 * This class consists exclusively of static methods to support debugging and
 * monitoring of threads.
 */
public class ThreadContainers {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    // the count of virtual threads that are not in a thread container
    private static final LongAdder UNMANAGED_VTHREAD_COUNT = new LongAdder();

    // the set of shared thread containers
    private static final Set<WeakReference<ThreadContainer>> SHARED_CONTAINERS = ConcurrentHashMap.newKeySet();
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();

    private ThreadContainers() { }

    /**
     * Increments the count of virtual threads.
     */
    public static void incrementUnmanagedVirtualThreadCount() {
        UNMANAGED_VTHREAD_COUNT.add(1L);
    }

    /**
     * Decrements the count of virtual threads.
     */
    public static void decrementUnmanagedVirtualThreadCount() {
        UNMANAGED_VTHREAD_COUNT.add(-1L);
    }

    /**
     * Expunge stale entries from set of shared thread containers.
     */
    private static void expungeStaleEntries() {
        Object key;
        while ((key = QUEUE.poll()) != null) {
            SHARED_CONTAINERS.remove(key);
        }
    }

    /**
     * Registers a shared thread container to be tracked this class, returning
     * a key that is used to remove it from the registry.
     */
    public static Object registerSharedContainer(ThreadContainer container) {
        assert container.owner() == null;
        expungeStaleEntries();
        var ref = new WeakReference<>(container);
        SHARED_CONTAINERS.add(ref);
        return ref;
    }

    /**
     * Removes a shared thread container from being tracked by specifying the
     * key returned when the thread container was registered.
     */
    public static void deregisterSharedContainer(Object key) {
        assert key instanceof WeakReference;
        SHARED_CONTAINERS.remove(key);
    }

    /**
     * Returns the root thread container.
     */
    public static ThreadContainer root() {
        return RootContainer.INSTANCE;
    }

    /**
     * Returns the "parent" of the given thread container.
     *
     * The parent of an owned container is the enclosing container when nested,
     * otherwise the parent of an owned container is the owner's container.
     *
     * The root thread container is the parent of all unowned/shared thread
     * containers. The parent of the root container is null.
     */
    public static ThreadContainer parent(ThreadContainer container) {
        ThreadContainer parent = container.previous();
        if (parent != null)
            return parent;
        Thread owner = container.owner();
        if (owner != null && (parent = JLA.threadContainer(owner)) != null)
            return parent;
        ThreadContainer root = root();
        return (container != root) ? root : null;
    }

    /**
     * Returns a stream of the given thread container's "children".
     *
     * An owned thread container is the parent of the thread container that is
     * encloses. The "top most" container owned by threads in the container are also
     * children.
     *
     * Unowned/shared thread containers that are reachable from the root container
     * are children of the root container.
     */
    public static Stream<ThreadContainer> children(ThreadContainer container) {
        Stream<ThreadContainer> s1 = Stream.empty();
        Thread owner = container.owner();
        if (owner != null) {
            // container may enclose another container
            ThreadContainer next = next(container);
            s1 = Stream.ofNullable(next);
        } else if (container == root()) {
            // the root container is the parent of all shared containers
            s1 = SHARED_CONTAINERS.stream()
                    .map(WeakReference::get)
                    .filter(c -> c != null);
        }

        // the top-most container owned by the threads in the container
        Stream<ThreadContainer> s2 = container.threads()
                .map(t -> Optional.ofNullable(top(t)))
                .flatMap(Optional::stream);
        return Stream.concat(s1, s2);
    }

    /**
     * Returns the thread container that the given Thread is in or the root
     * container if not started in a container.
     */
    public static ThreadContainer container(Thread thread) {
        ThreadContainer container = JLA.threadContainer(thread);
        return (container != null) ? container : root();
    }

    /**
     * Returns the top-most thread container owned by the given thread.
     */
    private static ThreadContainer top(Thread thread) {
        ThreadContainer container = JLA.headThreadContainer(thread);
        while (container != null) {
            ThreadContainer previous = container.previous();
            if (previous == null)
                return container;
            container = previous;
        }
        return null;
    }

    /**
     * Returns the thread container that the given thread container encloses.
     */
    private static ThreadContainer next(ThreadContainer container) {
        ThreadContainer head = JLA.headThreadContainer(container.owner());
        if (head != null) {
            ThreadContainer next = head;
            ThreadContainer previous = next.previous();
            while (previous != null) {
                if (previous == container) {
                    return next;
                }
                next = previous;
                previous = next.previous();
            }
        }
        return null;
    }

    /**
     * Root container.
     */
    private static class RootContainer implements ThreadContainer {
        static final RootContainer INSTANCE = new RootContainer();
        @Override
        public Thread owner() {
            return null;
        }
        @Override
        public long threadCount() {
            // platform threads that are not in a container
            long platformThreadCount = Stream.of(JLA.getAllThreads())
                    .filter(t -> JLA.threadContainer(t) == null)
                    .count();
            return platformThreadCount + UNMANAGED_VTHREAD_COUNT.sum();
        }
        @Override
        public Stream<Thread> threads() {
            Stream<Thread> s1 = Stream.of(JLA.getAllThreads());
            Stream<Thread> s2 = Poller.blockedThreads().filter(Thread::isVirtual);
            return Stream.concat(s1, s2).filter(t -> JLA.threadContainer(t) == null);
        }
        @Override
        public void setPrevious(ThreadContainer container) {
            throw new UnsupportedOperationException();
        }
        @Override
        public String toString() {
            return "<root>";
        }
    }
}

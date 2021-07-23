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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Removes a shared thread container ffrom being tracked by specifying the
     * key returned when the thread container was registered.
     */
    public static void deregisterSharedContainer(Object key) {
        assert key instanceof WeakReference;
        SHARED_CONTAINERS.remove(key);
    }

    /**
     * Returns a stream of the shared thread containers tracked by this class.
     * The stream include the root container.
     */
    public static Stream<ThreadContainer> sharedContainers() {
        Stream<ThreadContainer> s1 = Stream.of(RootContainer.INSTANCE);
        Stream<ThreadContainer> s2 = SHARED_CONTAINERS.stream()
                .map(WeakReference::get)
                .filter(c -> c != null);
        return Stream.concat(s1, s2);
    }

    /**
     * Returns the list of thread containers owned by the given thread. The
     * list is ordered, enclosing containers before nested containers.
     */
    public static List<ThreadContainer> ownedContainers(Thread thread) {
        ThreadContainer container = JLA.headThreadContainer(thread);
        if (container == null) {
            return List.of();
        } else {
            List<ThreadContainer> list = new ArrayList<>();
            while (container != null) {
                list.add(container);
                container = container.previous();
            }
            Collections.reverse(list);
            return list;
        }
    }

    /**
     * Returns a map of containers found by walking the graph from the set
     * of root threads. The map is keyed on the owner thread that. The map
     * value is the list of container owned by the thread, in creation order,
     * so that an enclosing container is before nested containers.
     */
    public static Map<Thread, List<ThreadContainer>> ownedContainers() {
        Map<Thread, List<ThreadContainer>> map = new HashMap<>();
        Deque<ThreadContainer> stack = new ArrayDeque<>();
        roots().forEach(t -> {
            List<ThreadContainer> containers = ownedContainers(t);
            if (!containers.isEmpty()) {
                map.put(t, containers);
                containers.forEach(stack::push);
            }
        });
        while (!stack.isEmpty()) {
            ThreadContainer container = stack.pop();
            container.threads().forEach(t -> {
                List<ThreadContainer> containers = ownedContainers(t);
                if (!containers.isEmpty()) {
                    map.put(t, containers);
                    containers.forEach(stack::push);
                }
            });
        }
        return map;
    }

    /**
     * Returns the thread container that the given Thread is in.
     */
    public static ThreadContainer container(Thread thread) {
        return JLA.threadContainer(thread);
    }

    /**
     * Returns the set of root Threads to use to find thread containers that
     * are owned by threads.
     */
    private static Stream<Thread> roots() {
        Stream<Thread> platformThreads = Stream.of(JLA.getAllThreads());

        // virtual threads in shared containers or parked waiting for I/O
        Stream<Thread> s1 = sharedContainers()
                .flatMap(ThreadContainer::threads)
                .filter(Thread::isVirtual);
        Stream<Thread> s2 = Poller.blockedThreads()
                .filter(t -> t.isVirtual()
                        && JLA.threadContainer(t) == null);
        Stream<Thread> virtualThreads = Stream.concat(s1, s2);

        return Stream.concat(platformThreads, virtualThreads);
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
        public ThreadContainer previous() {
            throw new UnsupportedOperationException();
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

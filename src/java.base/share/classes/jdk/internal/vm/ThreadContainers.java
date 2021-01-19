/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A registry of thread containers to support thread dump operations. Thread
 * containers register with this class at creation time, and deregister when
 * they are closed.
 */
public class ThreadContainers {
    private ThreadContainers() { }

    // Map of threads containers to the thread id of the Thread that created it
    private static final Map<WeakReference<ThreadContainer>, Long> containers = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();

    private static void expungeStaleEntries() {
        Object key;
        while ((key = queue.poll()) != null) {
            containers.remove(key);
        }
    }

    /**
     * An opaque key representing a registration. The deregister method should
     * be invoked to drop the registration.
     */
    public static class Key extends WeakReference<ThreadContainer> {
        long tid;

        Key(ThreadContainer executor) {
            super(executor, queue);
            tid = Thread.currentThread().getId();
        }

        /**
         * Drop the registration.
         */
        public void deregister() {
            clear();
            containers.remove(this);
        }
    }

    /**
     * Register a ThreadContainer so that its threads can be located for thread
     * dumping operations.
     */
    public static Key register(ThreadContainer container) {
        expungeStaleEntries();
        var key = new Key(container);
        containers.put(key, Thread.currentThread().getId());
        return key;
    }


    /**
     * Return a stream of all threads in all thread containers.
     */
    static Stream<Thread> allThreads() {
        return containers.keySet()
                .stream()
                .map(WeakReference::get)
                .filter(c -> c != null)
                .flatMap(c -> c.threads());
    }

    /**
     * Return a snapshot of the thread containers and their members.
     */
    static Map<Long, Set<Thread>> snapshot() {
        Map<Long, Set<Thread>> map = new HashMap<>();
        for (Map.Entry<WeakReference<ThreadContainer>, Long> e : containers.entrySet()) {
            ThreadContainer container = e.getKey().get();
            if (container != null) {
                Long tid = e.getValue();
                Set<Thread> threads = container.threads().collect(Collectors.toSet());;
                map.put(tid, threads);
            }
        }
        return map;
    }
}

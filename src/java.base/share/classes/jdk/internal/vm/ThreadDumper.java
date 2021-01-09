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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import sun.nio.ch.Poller;
import sun.security.action.GetPropertyAction;

/**
 * Thread dump support.
 *
 * This class consists exclusively of static methods that are used to track the
 * creation and termination of thread containers. It can optionally support tracking
 * the start and termination of virtual threads.
 *
 * This class defines methods to dump threads to an output stream or file in
 * plain text or JSON format.
 */
public class ThreadDumper {
    private ThreadDumper() { }

    // the set of all virtual threads when tracking is enabled, otherwise null
    private static final Set<Thread> VIRTUAL_THREADS;
    static {
        String s = GetPropertyAction.privilegedGetProperty("jdk.trackAllVirtualThreads");
        if (s != null && (s.isEmpty() || s.equalsIgnoreCase("true"))) {
            VIRTUAL_THREADS = ConcurrentHashMap.newKeySet();
        } else {
            VIRTUAL_THREADS = null;
        }
    }

    /**
     * Notifies the thread dumper of new virtual thread. A no-op if tracking of
     * virtual threads is not enabled.
     */
    public static void notifyStart(Thread thread) {
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            assert thread.isVirtual();
            threads.add(thread);
        }
    }

    /**
     * Notifies the thread dumper that a virtual thread has virtual. A no-op if
     * tracking of virtual threads is not enabled.
     */
    public static void notifyTerminate(Thread thread) {
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            assert thread.isVirtual();
            threads.remove(thread);
        }
    }

    /**
     * Provides access to Thread.threadDumperHeadNode
     */
    private static class ThreadFields {
        private static final VarHandle HEAD_NODE;
        static {
            try {
                PrivilegedExceptionAction<MethodHandles.Lookup> pa = () ->
                    MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
                MethodHandles.Lookup l = AccessController.doPrivileged(pa);
                HEAD_NODE = l.findVarHandle(Thread.class, "threadDumperHeadNode", Object.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        /**
         * Sets the current thread's head node.
         */
        static void setCurrentThreadHeadNode(Node node) {
            HEAD_NODE.setVolatile(Thread.currentThread(), node);
        }
        /**
         * Returns the current thread's head node.
         */
        static Node currentThreadHeadNode() {
            return (Node) HEAD_NODE.getVolatile(Thread.currentThread());
        }
        /**
         * Returns the given thread's head node.
         */
        static Node threadHeadNode(Thread thread) {
            return (Node) HEAD_NODE.getVolatile(thread);
        }
    }

    /**
     * A key returned by notifyCreate(ThreadContainer). The owner invokes close
     * to deregister the container.
     */
    public interface Key {
        void close();
    }

    /**
     * A node in a list of nodes that keep track of the thread containers created
     * by a thread. Invoking the node close method removes it from the current
     * thread's list of nodes.
     */
    private final static class Node
            extends WeakReference<ThreadContainer> implements Key {
        private final long owner;
        private volatile Node next;
        Node(ThreadContainer container, Node next) {
            super(container);
            this.owner = Thread.currentThread().getId();
            this.next = next;
        }
        ThreadContainer containerOrNull() {
            return get();
        }
        Node next() {
            return next;
        }
        void setNext(Node next) {
            assert Thread.currentThread().getId() == owner;
            this.next = next;
        }
        @Override
        public void close() {
            Node head = ThreadFields.currentThreadHeadNode();
            if (head == this) {
                // pop
                ThreadFields.setCurrentThreadHeadNode(head.next());
            } else if (Thread.currentThread().getId() == owner) {
                // out of order close by owner, need to unlink node
                Node current = head;
                while (current != null && current.next() != this) {
                    current = current.next();
                }
                if (current != null && current.next() == this) {
                    current.next = this.next();
                }
            }
        }
    }

    /**
     * Expunges stale nodes from the current thread's list of nodes, returning
     * the (possibly new) head node. This method is O(n) but the list is
     * unlikely to only have more than 2 or 3 elements.
     */
    private static Node expungeStaleNodes() {
        Node head = ThreadFields.currentThreadHeadNode();
        Node current = head;
        Node previous = null;
        while (current != null) {
            Node next = current.next();
            if (current.refersTo(null)) {
                if (previous == null) {
                    head = next;
                } else {
                    previous.setNext(next);
                }
            } else {
                previous = current;
            }
            current = next;
        }
        return head;
    }

    /**
     * Notifies the thread dumper that a thread container has been created.
     * Returns a registration key can be used to notify the thread dumper
     * that the thread container has been closed.
     */
    public static Key notifyCreate(ThreadContainer container) {
        Node head = expungeStaleNodes();
        // push
        var node = new Node(container, head);
        ThreadFields.setCurrentThreadHeadNode(node);
        return node;
    }

    /**
     * Returns a stream of the containers that the given thread has created.
     */
    private static Stream<ThreadContainer> containers(Thread thread) {
        Node head = ThreadFields.threadHeadNode(thread);
        if (head == null) {
            return Stream.empty();
        } else {
            List<ThreadContainer> containers = new ArrayList<>();
            Node node = head;
            while (node != null) {
                ThreadContainer container = node.containerOrNull();
                if (container != null)
                    containers.add(container);
                node = node.next();
            }
            return containers.stream();
        }
    }

    /**
     * Returns a map of all thread containers that are found by walking the graph
     * from the given set of root threads.
     */
    private static Map<ThreadContainer, Long> findContainers(Set<Thread> roots) {
        Map<ThreadContainer, Long> map = new HashMap<>();
        Deque<ThreadContainer> stack = new ArrayDeque<>();
        roots.stream().forEach(owner ->
            containers(owner).forEach(c -> {
                map.put(c, owner.getId());
                stack.push(c);
            }));
        while (!stack.isEmpty()) {
            ThreadContainer container = stack.pop();
            container.threads().forEach(owner -> {
                containers(owner).forEach(c -> {
                    map.put(c, owner.getId());
                    stack.push(c);
                });
            });
        }
        return map;
    }

    /**
     * Returns the set of all virtual threads that are found by walking the graph
     * from the given set of root threads.
     */
    private static Set<Thread> findVirtualThreads(Set<Thread> roots) {
        Set<Thread> threads = new HashSet<>();
        Deque<ThreadContainer> stack = new ArrayDeque<>();
        roots.stream()
                .flatMap(t -> containers(t))
                .forEach(stack::push);
        while (!stack.isEmpty()) {
            ThreadContainer container = stack.pop();
            container.threads().forEach(t -> {
                if (t.isVirtual()) {
                    threads.add(t);
                }
                containers(t).forEach(stack::push);
            });
        }
        return threads;
    }

    /**
     * Generate a thread dump in plain text format to a byte array, UTF-8 encoded.
     */
    public static byte[] dumpThreads() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dumpThreads(out);
        return out.toByteArray();
    }

    /**
     * Generate a thread dump in plain text format to the given file, UTF-8 encoded.
     */
    public static byte[] dumpThreads(String file) throws IOException {
        Path path = Path.of(file).toAbsolutePath();
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
             PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            dumpThreads(ps);
        }
        return String.format("Created %s%n", path).getBytes("UTF-8");
    }

    /**
     * Generate a thread dump in plain text format to the given output stream,
     * UTF-8 encoded.
     */
    public static void dumpThreads(OutputStream out) {
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        Map<Thread, StackTraceElement[]> allTraceTraces = Thread.getAllStackTraces();

        // virtual threads
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            threads.forEach(t -> dumpThread(t, t.getStackTrace(), ps));
        } else {
            Set<Thread> roots = allTraceTraces.keySet();
            Stream<Thread> managedVThreads = findVirtualThreads(roots).stream();
            Stream<Thread> blockedVThreads = Poller.blockedThreads().filter(Thread::isVirtual);
            Stream.concat(managedVThreads, blockedVThreads)
                    .distinct()
                    .forEach(t -> dumpThread(t, t.getStackTrace(), ps));
        }

        // platform threads
        for (Map.Entry<Thread, StackTraceElement[]> e : allTraceTraces.entrySet()) {
            Thread thread = e.getKey();
            StackTraceElement[] stackTrace = e.getValue();
            dumpThread(thread, stackTrace, ps);
        }

        ps.flush();
    }

    private static void dumpThread(Thread thread,
                                   StackTraceElement[] stackTrace,
                                   PrintStream ps) {
        String suffix = thread.isVirtual() ? " virtual" : "";
        ps.format("\"%s\" #%d%s%n", thread.getName(), thread.getId(), suffix);
        for (StackTraceElement ste : stackTrace) {
            ps.format("      %s%n", ste);
        }
        ps.println();
    }

    /**
     * Generate a thread dump in JSON format to a byte array, UTF-8 encoded.
     */
    public static byte[] dumpThreadsToJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dumpThreadsToJson(out);
        return out.toByteArray();
    }

    /**
     * Generate a thread dump in JSON format to the given file, UTF-8 encoded.
     */
    public static byte[] dumpThreadsToJson(String file) throws IOException {
        Path path = Path.of(file).toAbsolutePath();
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
             PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            dumpThreadsToJson(ps);
        }
        return String.format("Created %s%n", path).getBytes("UTF-8");
    }

    /**
     * Generate a thread dump in JSON format to the given output stream, UTF-8 encoded.
     */
    public static void dumpThreadsToJson(OutputStream out) {
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        dumpThreadsToJson(ps);
        ps.flush();
    }

    /**
     * Generate a thread dump to the given print stream in JSON format.
     */
    private static void dumpThreadsToJson(PrintStream out) {
        out.println("{");
        out.println("   \"threadDump\": {");

        Map<Thread, StackTraceElement[]> allTraceTraces = Thread.getAllStackTraces();

        Set<Thread> roots = allTraceTraces.keySet();
        Map<ThreadContainer, Long> containers = findContainers(roots);

        out.println("      \"threadContainers\": [");

        Iterator<Map.Entry<ThreadContainer, Long>> iterator1 = containers.entrySet().iterator();
        while (iterator1.hasNext()) {
            Map.Entry<ThreadContainer, Long> e = iterator1.next();
            ThreadContainer container = e.getKey();
            long creatorTid = e.getValue();

            out.println("        {");
            out.format("          \"creatorTid\": %d,%n", creatorTid);

            out.println("          \"tids\": [");
            long[] members = container.threads().mapToLong(Thread::getId).toArray();
            int j = 0;
            while (j < members.length) {
                out.format("              %d", members[j]);
                j++;
                if (j < members.length) {
                    out.println(",");
                } else {
                    out.println();  // last element, no trailing comma
                }
            }
            out.println("          ]");

            boolean more = iterator1.hasNext();
            if (more) {
                out.println("        },");
            } else {
                out.println("        }"); // last element, no trailing comma
            }
        }

        out.println("      ],");   // end of threadExecutors

        // thread array
        out.println("      \"threads\": [");

        // virtual threads
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            threads.forEach(t -> dumpThreadToJson(t, t.getStackTrace(), out, true));
        } else {
            Stream<Thread> managedThreads = containers.keySet().stream().flatMap(s -> s.threads());
            Stream.concat(managedThreads, Poller.blockedThreads())
                    .filter(Thread::isVirtual)
                    .distinct()
                    .forEach(t -> dumpThreadToJson(t, t.getStackTrace(), out, true));
        }

        // platform threads
        Iterator<Map.Entry<Thread, StackTraceElement[]>> iterator2 = allTraceTraces.entrySet().iterator();
        while (iterator2.hasNext()) {
            Map.Entry<Thread, StackTraceElement[]> e = iterator2.next();
            Thread thread = e.getKey();
            StackTraceElement[] stackTrace = e.getValue();
            boolean more = iterator2.hasNext();
            dumpThreadToJson(thread, stackTrace, out, more);
        }

        out.println("      ]");   // end of threads

        out.println("    }");   // end threadDump
        out.println("}");  // end object
    }

    /**
     * Dump the given thread and its stack trace to the print stream in JSON format.
     */
    private static void dumpThreadToJson(Thread thread,
                                         StackTraceElement[] stackTrace,
                                         PrintStream out,
                                         boolean more) {
        out.println("         {");
        out.format("           \"name\": \"%s\",%n", escape(thread.getName()));
        out.format("           \"tid\": %d,%n", thread.getId());
        out.format("           \"stack\": [%n");
        int i = 0;
        while (i < stackTrace.length) {
            out.format("              \"%s\"", escape(stackTrace[i].toString()));
            i++;
            if (i < stackTrace.length) {
                out.println(",");
            } else {
                out.println();  // last element, no trailing comma
            }
        }
        out.println("           ]");
        if (more) {
            out.println("         },");
        } else {
            out.println("         }");  // last thread, no trailing comma
        }
    }

    /**
     * Escape any characters that need to be escape in the JSON output.
     */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '/'  -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // TBD handle control characters, Unicode, ...
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}

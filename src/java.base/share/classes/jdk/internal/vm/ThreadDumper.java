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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import sun.nio.ch.Poller;
import sun.security.action.GetPropertyAction;

/**
 * Thread dump support.
 *
 * This class defines methods to dump threads to an output stream or file in
 * plain text or JSON format. Virtual threads are located if they are created in
 * a structured way with a ThreadExecutor. This class optionally support tracking
 * the start and termination of all virtual threads.
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
     * A container of threads, backed by a ThreadExecutor.
     */
    private static class ThreadContainer {
        private final Object threadExecutor;

        ThreadContainer(Object threadExecutor) {
            this.threadExecutor = Objects.requireNonNull(threadExecutor);
        }

        Stream<Thread> threads() {
            return ThreadExecutorFields.threads(threadExecutor).stream();
        }

        private static ThreadContainer latestThreadContainer(Thread thread) {
            Object threadExecutor = ThreadFields.latestThreadExecutor(thread);
            if (threadExecutor != null) {
                return new ThreadContainer(threadExecutor);
            } else {
                return null;
            }
        }

        private ThreadContainer previous() {
            Object previous = ThreadExecutorFields.previous(threadExecutor);
            if (previous != null) {
                return new ThreadContainer(previous);
            } else {
                return null;
            }
        }
        
        /**
         * Returns the list of "active" containers created by the given thread. The
         * list is ordered, enclosing containers before nested containers.
         */
        static List<ThreadContainer> containers(Thread thread) {
            ThreadContainer container = latestThreadContainer(thread);
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

        @Override
        public int hashCode() {
            return threadExecutor.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ThreadContainer other) {
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
    }

    /**
     * Returns a stream of the virtual threads that are found by walking the
     * graph from the given set of root threads.
     */
    private static Stream<Thread> virtualThreads(Set<Thread> roots) {
        Set<Thread> threads = new HashSet<>();
        Deque<ThreadContainer> stack = new ArrayDeque<>();
        roots.stream()
                .flatMap(t -> ThreadContainer.containers(t).stream())
                .forEach(stack::push);
        while (!stack.isEmpty()) {
            ThreadContainer container = stack.pop();
            container.threads().forEach(t -> {
                if (t.isVirtual()) {
                    threads.add(t);
                }
                ThreadContainer.containers(t).forEach(stack::push);
            });
        }
        return threads.stream();
    }

    /**
     * Returns a map of "active" containers found by walking the graph from the given
     * set of root threads. The map is keyed on the identifier of the thread that
     * created the containers. Each map value is a list of containers created by that
     * thread, in creation order, so that enclosing containers are best nested
     * containers.
     */
    private static Map<Long, List<ThreadContainer>> findContainers(Set<Thread> roots) {
        Map<Long, List<ThreadContainer>> map = new HashMap<>();
        Deque<ThreadContainer> stack = new ArrayDeque<>();
        roots.stream().forEach(t -> {
            List<ThreadContainer> containers = ThreadContainer.containers(t);
            if (!containers.isEmpty()) {
                map.put(t.getId(), containers);
                containers.forEach(stack::push);
            }
        });
        while (!stack.isEmpty()) {
            ThreadContainer container = stack.pop();
            container.threads().forEach(t -> {
                List<ThreadContainer> containers = ThreadContainer.containers(t);
                if (!containers.isEmpty()) {
                    map.put(t.getId(), containers);
                    containers.forEach(stack::push);
                }
            });
        }
        return map;
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

        // platform threads
        for (Map.Entry<Thread, StackTraceElement[]> e : allTraceTraces.entrySet()) {
            Thread thread = e.getKey();
            StackTraceElement[] stackTrace = e.getValue();
            dumpThread(thread, stackTrace, ps);
        }

        // virtual threads
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            threads.forEach(t -> dumpThread(t, t.getStackTrace(), ps));
        } else {
            Set<Thread> roots = allTraceTraces.keySet();
            Stream<Thread> managedVThreads = virtualThreads(roots);
            Stream<Thread> blockedVThreads = Poller.blockedThreads().filter(Thread::isVirtual);
            Stream.concat(managedVThreads, blockedVThreads)
                    .distinct()
                    .forEach(t -> dumpThread(t, t.getStackTrace(), ps));
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
        Map<Long, List<ThreadContainer>> map = findContainers(roots);

        // threadContainers
        out.println("      \"threadContainers\": [");
        Iterator<Map.Entry<Long, List<ThreadContainer>>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<ThreadContainer>> e = iterator.next();
            long creatorTid = e.getKey();
            List<ThreadContainer> containers = e.getValue();

            out.println("        {");
            out.format("          \"creatorTid\": %d,%n", creatorTid);
            out.println("          \"containerIds\": [");

            int i = 0;
            while (i < containers.size()) {
                ThreadContainer container = containers.get(i++);
                out.println("            {");
                out.format("              \"containerId\": \"%s\",%n", escape(container.toString()));

                out.println("              \"memberTids\": [");
                long[] members = container.threads().mapToLong(Thread::getId).toArray();
                int j = 0 ;
                while (j < members.length) {
                    long tid = members[j++];
                    out.format("                %d", tid);
                    if (j < members.length) out.print(",");
                    out.println();
                }
                out.println("              ]");   // end of memberTids

                out.print("            }");
                if (i < containers.size()) out.print(",");
                out.println();
            }

            out.println("          ]");   // end of containerIds

            out.print("        }");
            if (iterator.hasNext()) out.print(",");
            out.println();
        }

        out.println("      ],");   // end of threadContainers

        // threads
        out.println("      \"threads\": [");

        // virtual threads
        Set<Thread> threads = VIRTUAL_THREADS;
        if (threads != null) {
            threads.forEach(t -> dumpThreadToJson(t, t.getStackTrace(), out, true));
        } else {
            Stream<Thread> managedThreads = map.values().stream()
                    .flatMap(l -> l.stream())
                    .flatMap(c -> c.threads());
            Stream.concat(managedThreads, Poller.blockedThreads())
                    .filter(Thread::isVirtual)
                    .distinct()
                    .forEach(t -> dumpThreadToJson(t, t.getStackTrace(), out, true));
        }

        // platform threads
        Iterator<Map.Entry<Thread, StackTraceElement[]>> platformThreads = allTraceTraces.entrySet().iterator();
        while (platformThreads.hasNext()) {
            Map.Entry<Thread, StackTraceElement[]> e = platformThreads.next();
            Thread thread = e.getKey();
            StackTraceElement[] stackTrace = e.getValue();
            boolean more = platformThreads.hasNext();
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
                    if (c <= 0x1f) {
                        sb.append(String.format("\\u%04x", c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Provides read access to Thread.latestThreadExecutor
     */
    private static class ThreadFields {
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
     * Provides access to ThreadExecutor
     */
    private static class ThreadExecutorFields {
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

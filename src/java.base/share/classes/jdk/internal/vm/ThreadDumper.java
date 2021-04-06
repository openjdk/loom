/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import sun.nio.ch.Poller;

/**
 * Thread dump support.
 *
 * This class defines methods to dump threads to an output stream or file in
 * plain text or JSON format. Virtual threads are located if they are created in
 * a structured way with a ThreadExecutor.
 */
public class ThreadDumper {
    private ThreadDumper() { }

    /**
     * Returns a stream of the virtual threads that are found by walking the
     * graph from the given set of root threads.
     */
    private static Stream<Thread> virtualThreads(Set<Thread> roots) {
        Set<Thread> threads = new HashSet<>();
        Deque<ThreadExecutor> stack = new ArrayDeque<>();
        roots.stream()
                .flatMap(t -> ThreadExecutor.executors(t).stream())
                .forEach(stack::push);
        while (!stack.isEmpty()) {
            ThreadExecutor executor = stack.pop();
            executor.threads().forEach(t -> {
                if (t.isVirtual()) {
                    threads.add(t);
                }
                ThreadExecutor.executors(t).forEach(stack::push);
            });
        }
        return threads.stream();
    }

    /**
     * Returns a map of "active" executors found by walking the graph from the given
     * set of root threads. The map is keyed on the identifier of the thread that
     * created the executors. Each map value is a list of executors created by that
     * thread, in creation order, so that enclosing executors are best nested
     * executors.
     */
    private static Map<Long, List<ThreadExecutor>> findExecutors(Set<Thread> roots) {
        Map<Long, List<ThreadExecutor>> map = new HashMap<>();
        Deque<ThreadExecutor> stack = new ArrayDeque<>();
        roots.stream().forEach(t -> {
            List<ThreadExecutor> executors = ThreadExecutor.executors(t);
            if (!executors.isEmpty()) {
                map.put(t.getId(), executors);
                executors.forEach(stack::push);
            }
        });
        while (!stack.isEmpty()) {
            ThreadExecutor executor = stack.pop();
            executor.threads().forEach(t -> {
                List<ThreadExecutor> executors = ThreadExecutor.executors(t);
                if (!executors.isEmpty()) {
                    map.put(t.getId(), executors);
                    executors.forEach(stack::push);
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
        Set<Thread> threads = ThreadTracker.virtualThreads().orElse(null);
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
        Map<Long, List<ThreadExecutor>> map = findExecutors(roots);

        // threadExecutors
        out.println("      \"threadExecutors\": [");
        Iterator<Map.Entry<Long, List<ThreadExecutor>>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<ThreadExecutor>> e = iterator.next();
            long creatorTid = e.getKey();
            List<ThreadExecutor> executors = e.getValue();

            out.println("        {");
            out.format("          \"creatorTid\": %d,%n", creatorTid);
            out.println("          \"executorIds\": [");

            int i = 0;
            while (i < executors.size()) {
                ThreadExecutor executor = executors.get(i++);
                out.println("            {");
                out.format("              \"executorId\": \"%s\",%n", escape(executor.toString()));

                out.println("              \"memberTids\": [");
                long[] members = executor.threads().mapToLong(Thread::getId).toArray();
                int j = 0 ;
                while (j < members.length) {
                    long tid = members[j++];
                    out.format("                %d", tid);
                    if (j < members.length) out.print(",");
                    out.println();
                }
                out.println("              ]");   // end of memberTids

                out.print("            }");
                if (i < executors.size()) out.print(",");
                out.println();
            }

            out.println("          ]");   // end of executorIds

            out.print("        }");
            if (iterator.hasNext()) out.print(",");
            out.println();
        }

        out.println("      ],");   // end of threadExecutors

        // threads
        out.println("      \"threads\": [");

        // virtual threads
        Set<Thread> threads = ThreadTracker.virtualThreads().orElse(null);;
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
}
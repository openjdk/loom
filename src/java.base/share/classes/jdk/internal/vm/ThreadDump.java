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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import sun.nio.ch.Poller;

/**
 * This class defines methods to dump threads to an output stream or file in
 * plain text or JSON format.
 */
public class ThreadDump {
    private ThreadDump() { }
    
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
        PrivilegedAction<Path> pa = () -> Path.of(file).toAbsolutePath();
        Path path = AccessController.doPrivileged(pa);
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

        // virtual threads
        Stream.concat(Poller.blockedThreads(), ThreadContainers.allThreads())
            .filter(Thread::isVirtual)
            .distinct()
            .forEach(t -> {
                PrivilegedAction<StackTraceElement[]> pa = t::getStackTrace;
                StackTraceElement[] stackTrace = AccessController.doPrivileged(pa);
                dumpThread(t, stackTrace, ps);
            });

        // kernel threads
        PrivilegedAction<Map<Thread, StackTraceElement[]>> pa = Thread::getAllStackTraces;
        Map<Thread, StackTraceElement[]> stacks = AccessController.doPrivileged(pa);
        for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
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
        PrivilegedAction<Path> pa = () -> Path.of(file).toAbsolutePath();
        Path path = AccessController.doPrivileged(pa);
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

        // map of create thread identifier to members
        Map<Long, Set<Thread>> map = ThreadContainers.snapshot();

        // thread containers array
        out.println("      \"threadContainers\": [");

        Iterator<Map.Entry<Long, Set<Thread>>> iterator1 = map.entrySet().iterator();
        while (iterator1.hasNext()) {
            Map.Entry<Long, Set<Thread>> e = iterator1.next();

            out.println("        {");
            out.format("          \"creatorTid\": %d,%n", e.getKey());

            out.println("          \"tids\": [");
            long[] members = e.getValue().stream().mapToLong(Thread::getId).toArray();
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

        out.println("      ],");   // end of threadContainers

        // thread array
        out.println("      \"threads\": [");

        // virtual threads
        Stream<Thread> managedThreads = map.values().stream().flatMap(s -> s.stream());
        Stream.concat(Poller.blockedThreads(), managedThreads)
                .filter(Thread::isVirtual)
                .distinct()
                .forEach(t ->  dumpThreadToJson(t, out, true));

        // kernel threads
        PrivilegedAction<Map<Thread, StackTraceElement[]>> pa = Thread::getAllStackTraces;
        Map<Thread, StackTraceElement[]> allThreads = AccessController.doPrivileged(pa);
        Iterator<Map.Entry<Thread, StackTraceElement[]>> iterator2 = allThreads.entrySet().iterator();
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

    private static void dumpThreadToJson(Thread thread, PrintStream out, boolean more) {
        PrivilegedAction<StackTraceElement[]> pa = thread::getStackTrace;
        StackTraceElement[] stackTrace = AccessController.doPrivileged(pa);
        dumpThreadToJson(thread, stackTrace, out, more);
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

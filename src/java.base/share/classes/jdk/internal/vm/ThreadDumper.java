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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

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
     * Generate a thread dump in plain text or JSON format to the given file, UTF-8 encoded.
     */
    private static byte[] dumpThreads(String file, boolean okayToOverwrite, boolean json) {
        Path path = Path.of(file).toAbsolutePath();
        OpenOption[] options = (okayToOverwrite) ?
                new OpenOption[0] : new OpenOption[] { StandardOpenOption.CREATE_NEW };
        String reply;
        try (OutputStream out = Files.newOutputStream(path, options);
             PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            if (json) {
                dumpThreadsToJson(ps);
            } else {
                dumpThreads(ps);
            }
            reply = String.format("Created %s%n", path);
        } catch (FileAlreadyExistsException e) {
            reply = String.format("%s exists, use -overwrite to overwrite%n", path);
        } catch (IOException ioe) {
            reply = String.format("Failed: %s%n", ioe);
        }
        return reply.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate a thread dump in plain text format to the given file, UTF-8 encoded.
     */
    public static byte[] dumpThreads(String file, boolean okayToOverwrite) {
        return dumpThreads(file, okayToOverwrite, false);
    }

    /**
     * Generate a thread dump in JSON format to the given file, UTF-8 encoded.
     */
    public static byte[] dumpThreadsToJson(String file, boolean okayToOverwrite) {
        return dumpThreads(file, okayToOverwrite, true);
    }

    /**
     * Generate a thread dump in plain text format to the given output stream,
     * UTF-8 encoded.
     */
    public static void dumpThreads(OutputStream out) {
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);

        ThreadContainers.sharedContainers()
                .flatMap(ThreadContainer::threads)
                .forEach(t -> dumpThread(t, ps));

        ThreadContainers.ownedContainers()
                .values()
                .stream()
                .flatMap(List::stream)
                .flatMap(ThreadContainer::threads)
                .forEach(t -> dumpThread(t, ps));

        ps.flush();
    }

    private static void dumpThread(Thread thread, PrintStream ps) {
        String suffix = thread.isVirtual() ? " virtual" : "";
        ps.format("\"%s\" #%d%s%n", thread.getName(), thread.getId(), suffix);
        for (StackTraceElement ste : thread.getStackTrace()) {
            ps.format("      %s%n", ste);
        }
        ps.println();
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
        out.println("  \"threadDump\": {");

        out.println("    \"threadContainers\": [");

        Stream<ThreadContainer> shared = ThreadContainers.sharedContainers();
        Stream<ThreadContainer> owned = ThreadContainers.ownedContainers()
                .values()
                .stream()
                .flatMap(List::stream);

        Iterator<ThreadContainer> iterator = Stream.concat(shared, owned).iterator();
        while (iterator.hasNext()) {
            ThreadContainer container = iterator.next();

            out.println("      {");
            out.format("        \"container\": \"%s\",%n", escape(container.toString()));

            Thread owner = container.owner();
            if (owner == null) {
                out.format("        \"owner\": null,%n");
            } else {
                out.format("        \"owner\": %d,%n", owner.getId());
            }

            long threadCount = 0;
            out.println("        \"threads\": [");
            Iterator<Thread> threads = container.threads().iterator();
            while (threads.hasNext()) {
                Thread thread = threads.next();
                dumpThreadToJson(thread, out, threads.hasNext());
                threadCount++;
            }
            out.println("        ],");   // end of threads

            // thread count
            threadCount = Long.max(threadCount, container.threadCount());
            out.format("        \"threadCount\": %d%n", threadCount);

            boolean more = iterator.hasNext();
            if (more) {
                out.println("      },");
            } else {
                out.println("      }");  // last container, no trailing comma
            }
        }

        out.println("    ]");   // end of threadContainers

        out.println("  }");   // end threadDump
        out.println("}");  // end object
    }

    /**
     * Dump the given thread and its stack trace to the print stream in JSON format.
     */
    private static void dumpThreadToJson(Thread thread, PrintStream out, boolean more) {
        out.println("         {");
        out.format("           \"name\": \"%s\",%n", escape(thread.getName()));
        out.format("           \"tid\": %d,%n", thread.getId());
        out.format("           \"stack\": [%n");
        int i = 0;
        StackTraceElement[] stackTrace = thread.getStackTrace();
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
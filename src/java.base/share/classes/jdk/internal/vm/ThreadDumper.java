/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.AbstractOwnableSynchronizer;

/**
 * Thread dump support.
 *
 * This class defines methods to dump threads to an output stream or file in plain
 * text or JSON format.
 */
public class ThreadDumper {
    private ThreadDumper() { }

    // the maximum byte array to return when generating the thread dump to a byte array
    private static final int MAX_BYTE_ARRAY_SIZE = 16_000;

    /**
     * Generate a thread dump in plain text format to a byte array or file, UTF-8 encoded.
     *
     * This method is invoked by the VM for the Thread.dump_to_file diagnostic command.
     *
     * @param file the file path to the file, null or "-" to return a byte array
     * @param okayToOverwrite true to overwrite an existing file
     * @return the UTF-8 encoded thread dump or message to return to the user
     */
    public static byte[] dumpThreads(String file, boolean okayToOverwrite) {
        if (file == null || file.equals("-")) {
            return dumpThreadsToByteArray(false, MAX_BYTE_ARRAY_SIZE);
        } else {
            return dumpThreadsToFile(file, okayToOverwrite, false);
        }
    }

    /**
     * Generate a thread dump in JSON format to a byte array or file, UTF-8 encoded.
     *
     * This method is invoked by the VM for the Thread.dump_to_file diagnostic command.
     *
     * @param file the file path to the file, null or "-" to return a byte array
     * @param okayToOverwrite true to overwrite an existing file
     * @return the UTF-8 encoded thread dump or message to return to the user
     */
    public static byte[] dumpThreadsToJson(String file, boolean okayToOverwrite) {
        if (file == null || file.equals("-")) {
            return dumpThreadsToByteArray(true, MAX_BYTE_ARRAY_SIZE);
        } else {
            return dumpThreadsToFile(file, okayToOverwrite, true);
        }
    }

    /**
     * Generate a thread dump in plain text or JSON format to a byte array, UTF-8 encoded.
     */
    private static byte[] dumpThreadsToByteArray(boolean json, int maxSize) {
        try (var out = new BoundedByteArrayOutputStream(maxSize);
             PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
            if (json) {
                dumpThreadsToJson(ps);
            } else {
                dumpThreads(ps);
            }
            return out.toByteArray();
        }
    }

    /**
     * Generate a thread dump in plain text or JSON format to the given file, UTF-8 encoded.
     */
    private static byte[] dumpThreadsToFile(String file, boolean okayToOverwrite, boolean json) {
        Path path = Path.of(file).toAbsolutePath();
        OpenOption[] options = (okayToOverwrite)
                ? new OpenOption[0]
                : new OpenOption[] { StandardOpenOption.CREATE_NEW };
        String reply;
        try (OutputStream out = Files.newOutputStream(path, options);
             BufferedOutputStream bos = new BufferedOutputStream(out);
             PrintStream ps = new PrintStream(bos, false, StandardCharsets.UTF_8)) {
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
     * Generate a thread dump in plain text format to the given output stream,
     * UTF-8 encoded.
     *
     * This method is invoked by HotSpotDiagnosticMXBean.dumpThreads.
     */
    public static void dumpThreads(OutputStream out) {
        BufferedOutputStream bos = new BufferedOutputStream(out);
        PrintStream ps = new PrintStream(bos, false, StandardCharsets.UTF_8);
        try {
            dumpThreads(ps);
        } finally {
            ps.flush();  // flushes underlying stream
        }
    }

    /**
     * Generate a thread dump in plain text format to the given print stream.
     */
    private static void dumpThreads(PrintStream ps) {
        ps.println(processId());
        ps.println(Instant.now());
        ps.println(Runtime.version());
        ps.println();
        dumpThreads(ThreadContainers.root(), ps);
    }

    private static void dumpThreads(ThreadContainer container, PrintStream ps) {
        container.threads().forEach(t -> dumpThread(t, ps));
        container.children().forEach(c -> dumpThreads(c, ps));
    }

    private static void dumpThread(Thread thread, PrintStream ps) {
        ThreadSnapshot snapshot = ThreadSnapshot.of(thread);
        Thread.State state = snapshot.threadState();
        ps.println("#" + thread.threadId() + " \"" + snapshot.threadName()
                +  "\" " + state + " " + Instant.now());

        // park blocker
        Object parkBlocker = snapshot.parkBlocker();
        if (parkBlocker != null) {
            ps.print("      // parked on " + Objects.toIdentityString(parkBlocker));
            if (parkBlocker instanceof AbstractOwnableSynchronizer
                    && snapshot.exclusiveOwnerThread() instanceof Thread owner) {
                ps.print(", owned by #" + owner.threadId());
            }
            ps.println();
        }

        // blocked on monitor enter or Object.wait
        if (state == Thread.State.BLOCKED) {
            Object obj = snapshot.blockedOn();
            if (obj != null) {
                ps.println("      // blocked on " + Objects.toIdentityString(obj));
            }
        } else if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
            Object obj = snapshot.waitingOn();
            if (obj != null) {
                ps.println("      // waiting on " + Objects.toIdentityString(obj));
            }
        }

        StackTraceElement[] stackTrace = snapshot.stackTrace();
        int depth = 0;
        while (depth < stackTrace.length) {
            snapshot.ownedMonitorsAt(depth).forEach(obj -> {
                ps.print("      // locked ");
                ps.println(Objects.toIdentityString(obj));
            });
            ps.print("      ");
            ps.println(stackTrace[depth]);
            depth++;
        }
        ps.println();
    }

    /**
     * Generate a thread dump in JSON format to the given output stream, UTF-8 encoded.
     *
     * This method is invoked by HotSpotDiagnosticMXBean.dumpThreads.
     */
    public static void dumpThreadsToJson(OutputStream out) {
        BufferedOutputStream bos = new BufferedOutputStream(out);
        PrintStream ps = new PrintStream(bos, false, StandardCharsets.UTF_8);
        try {
            dumpThreadsToJson(ps);
        } finally {
            ps.flush();  // flushes underlying stream
        }
    }

    /**
     * Generate a thread dump to the given print stream in JSON format.
     */
    private static void dumpThreadsToJson(PrintStream ps) {
        try (JsonWriter jsonWriter = JsonWriter.wrap(ps)) {
            jsonWriter.startObject("threadDump");

            jsonWriter.writeProperty("processId", processId());
            jsonWriter.writeProperty("time", Instant.now());
            jsonWriter.writeProperty("runtimeVersion", Runtime.version());

            jsonWriter.startArray("threadContainers");
            dumpThreads(ThreadContainers.root(), jsonWriter);
            jsonWriter.endArray();

            jsonWriter.endObject();  // threadDump
        }
    }

    /**
     * Write a thread container to the given JSON writer.
     */
    private static void dumpThreads(ThreadContainer container, JsonWriter jsonWriter) {
        jsonWriter.startObject();
        jsonWriter.writeProperty("container", container);
        jsonWriter.writeProperty("parent", container.parent());

        Thread owner = container.owner();
        jsonWriter.writeProperty("owner", (owner != null) ? owner.threadId() : null);

        long threadCount = 0;
        jsonWriter.startArray("threads");
        Iterator<Thread> threads = container.threads().iterator();
        while (threads.hasNext()) {
            Thread thread = threads.next();
            dumpThread(thread, jsonWriter);
            threadCount++;
        }
        jsonWriter.endArray(); // threads

        // thread count
        if (!ThreadContainers.trackAllThreads()) {
            threadCount = Long.max(threadCount, container.threadCount());
        }
        jsonWriter.writeProperty("threadCount", threadCount);

        jsonWriter.endObject();

        // the children of the thread container follow
        container.children().forEach(c -> dumpThreads(c, jsonWriter));
    }

    /**
     * Write a thread to the given JSON writer.
     */
    private static void dumpThread(Thread thread, JsonWriter jsonWriter) {
        Instant now = Instant.now();
        ThreadSnapshot snapshot = ThreadSnapshot.of(thread);
        Thread.State state = snapshot.threadState();
        StackTraceElement[] stackTrace = snapshot.stackTrace();

        jsonWriter.startObject();
        jsonWriter.writeProperty("tid", thread.threadId());
        jsonWriter.writeProperty("time", now);
        jsonWriter.writeProperty("name", snapshot.threadName());
        jsonWriter.writeProperty("state", state);

        // park blocker
        Object parkBlocker = snapshot.parkBlocker();
        if (parkBlocker != null) {
            jsonWriter.startObject("parkBlocker");
            jsonWriter.writeProperty("object", Objects.toIdentityString(parkBlocker));
            if (parkBlocker instanceof AbstractOwnableSynchronizer
                    && snapshot.exclusiveOwnerThread() instanceof Thread owner) {
                jsonWriter.writeProperty("exclusiveOwnerThreadId", owner.threadId());
            }
            jsonWriter.endObject();
        }

        // blocked on monitor enter or Object.wait
        if (state == Thread.State.BLOCKED) {
            Object obj = snapshot.blockedOn();
            if (obj != null) {
                jsonWriter.writeProperty("blockedOn", Objects.toIdentityString(obj));
            }
        } else if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
            Object obj = snapshot.waitingOn();
            if (obj != null) {
                jsonWriter.writeProperty("waitingOn", Objects.toIdentityString(obj));
            }
        }

        // stack trace
        jsonWriter.startArray("stack");
        Arrays.stream(stackTrace).forEach(jsonWriter::writeProperty);
        jsonWriter.endArray();

        // monitors owned, skip if none
        if (snapshot.ownsMonitors()) {
            jsonWriter.startArray("monitorsOwned");
            int depth = 0;
            while (depth < stackTrace.length) {
                List<Object> objs = snapshot.ownedMonitorsAt(depth).toList();
                if (!objs.isEmpty()) {
                    jsonWriter.startObject();
                    jsonWriter.writeProperty("depth", depth);
                    jsonWriter.startArray("locks");
                    snapshot.ownedMonitorsAt(depth)
                            .map(Objects::toIdentityString)
                            .forEach(jsonWriter::writeProperty);
                    jsonWriter.endArray();
                    jsonWriter.endObject();
                }
                depth++;
            }
            jsonWriter.endArray();
        }

        jsonWriter.endObject();
    }

    /**
     * Simple JSON writer to stream objects/arrays to a PrintStream.
     */
    private static class JsonWriter implements AutoCloseable {
        private final PrintStream ps;

        // current depth and indentation
        private int depth = -1;
        private int indent;

        // indicates if there are properties at depth N
        private boolean[] hasProperties = new boolean[10];

        private JsonWriter(PrintStream out) {
            this.ps = out;
        }

        static JsonWriter wrap(PrintStream ps) {
            var writer = new JsonWriter(ps);
            writer.startObject();
            return writer;
        }

        /**
         * Start of object or array.
         */
        private void startObject(String name, boolean array) {
            if (depth >= 0) {
                if (hasProperties[depth]) {
                    ps.println(",");
                } else {
                    hasProperties[depth] = true;  // first property at this depth
                }
            }
            ps.print(" ".repeat(indent));
            if (name != null) {
                ps.print("\"" + name + "\": ");
            }
            if (array) {
                ps.println("[");
            } else {
                ps.println("{");
            }
            indent += 2;
            depth++;
            hasProperties[depth] = false;
        }

        /**
         * End of object or array.
         */
        private void endObject(boolean array) {
            assert depth >= 0;
            if (hasProperties[depth]) {
                ps.println();
                hasProperties[depth] = false;
            }
            depth--;
            indent -= 2;
            ps.print(" ".repeat(indent));
            if (array) {
                ps.print("]");
            } else {
                ps.print("}");
            }
        }

        /**
         * Write a property.
         * @param name the property name, null for an unnamed property
         * @param obj the value or null
         */
        void writeProperty(String name, Object obj) {
            assert depth >= 0;
            if (hasProperties[depth]) {
                ps.println(",");
            } else {
                hasProperties[depth] = true;
            }
            ps.print(" ".repeat(indent));
            if (name != null) {
                ps.print("\"" + name + "\": ");
            }
            switch (obj) {
                case Number _ -> ps.print(obj);
                case null     -> ps.print("null");
                default       -> ps.print("\"" + escape(obj.toString()) + "\"");
            }
        }

        /**
         * Write an unnamed property.
         */
        void writeProperty(Object obj) {
            writeProperty(null, obj);
        }

        /**
         * Start named object.
         */
        void startObject(String name) {
            startObject(name, false);
        }

        /**
         * Start unnamed object.
         */
        void startObject() {
            startObject(null);
        }

        /**
         * End of object.
         */
        void endObject() {
            endObject(false);
        }

        /**
         * Start named array.
         */
        void startArray(String name) {
            startObject(name, true);
        }

        /**
         * End of array.
         */
        void endArray() {
            endObject(true);
        }

        @Override
        public void close() {
            endObject();
            ps.flush();
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

    /**
     * A ByteArrayOutputStream of bounded size. Once the maximum number of bytes is
     * written the subsequent bytes are discarded.
     */
    private static class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        final int max;
        BoundedByteArrayOutputStream(int max) {
            this.max = max;
        }
        @Override
        public void write(int b) {
            if (max < count) {
                super.write(b);
            }
        }
        @Override
        public void write(byte[] b, int off, int len) {
            int remaining = max - count;
            if (remaining > 0) {
                super.write(b, off, Integer.min(len, remaining));
            }
        }
        @Override
        public void close() {
        }
    }

    /**
     * Returns the process ID or -1 if not supported.
     */
    private static long processId() {
        try {
            return ProcessHandle.current().pid();
        } catch (UnsupportedOperationException e) {
            return -1L;
        }
    }
}

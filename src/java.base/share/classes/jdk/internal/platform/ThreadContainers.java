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
package jdk.internal.platform;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry of thread containers to support thread dump operations
 * Thread containers register with this class at creation time, and deregister
 * when they are closed.
 *
 * This class defines methods to dump threads to an output or print stream in
 * JSON format. Strict JSON does not have trailing commas so the JSON may be
 * rejected by some parsers.
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
     * Register a ThreadContainer so that its threads can be located for
     * thread dumping operations.
     */
    public static Key register(ThreadContainer container) {
        expungeStaleEntries();
        var key = new Key(container);
        containers.put(key, Thread.currentThread().getId());
        return key;
    }

    /**
     * Write a thread dump to output stream in JSON format.
     */
    public static void dumpThreadsToJson(OutputStream out) {
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        dumpThreadsToJson(ps);
    }

    /**
     * Write a thread dump to print stream in JSON format.
     */
    public static void dumpThreadsToJson(PrintStream out) {
        out.println("{");
        out.println("   \"threadDump\": {");

        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        out.println("      \"threads\": [");

        // dump kernel threads
        for (Map.Entry<Thread, StackTraceElement[]> e : stacks.entrySet()) {
            Thread thread = e.getKey();
            dumpThread(thread, out);
        }

        List<Long> creators = new ArrayList<>();   // tid of thread that created container
        List<long[]> members = new ArrayList<>();

        // dump virtual threads
        for (Map.Entry<WeakReference<ThreadContainer>, Long> e : containers.entrySet()) {
            ThreadContainer container = e.getKey().get();
            long creatorTid = e.getValue();
            if (container != null) {
                Set<Thread> threads = container.threads();

                // virtual threads
                threads.stream().filter(Thread::isVirtual).forEach(t -> dumpThread(t, out));

                // add to creators/members
                creators.add(creatorTid);
                long[] tids = threads.stream().mapToLong(Thread::getId).toArray();
                members.add(tids);
            }
        }

        out.println("      ], ");

        // dump thread containers and tid of the creator/members
        out.println("      \"threadContainers\": [");
        for (int i = 0; i < creators.size(); i++) {
            Long creatorTid = creators.get(i);
            long[] tids = members.get(i);
            out.println("        {");
            out.format("          \"creator\": %d,%n", creatorTid);
            out.println("          \"members\": [");
            Arrays.stream(tids).forEach(tid -> out.format("              %d,%n", tid));
            out.println("          ], ");
            out.println("        },");
        }
        out.println("    ]");

        out.println("  }");
        out.println("}");
    }

    /**
     * Dump the given thread and its stack trace to the print stream.
     */
    private static void dumpThread(Thread thread, PrintStream out) {
        PrivilegedAction<StackTraceElement[]> pa = thread::getStackTrace;
        StackTraceElement[] stackTrace = AccessController.doPrivileged(pa);

        out.println("         {");
        out.format("           \"name\": \"%s\",%n", escape(thread.getName()));
        out.format("           \"tid\": %d,%n", thread.getId());
        out.format("           \"stack\": [%n");
        int i = 0;
        while (i < stackTrace.length) {
            out.format("              \"%s\"", escape(stackTrace[i].toString()));
            i++;
            if (i >= stackTrace.length) {
                out.println();  // last element, no trailing comma
            } else {
                out.println(",");
            }
        }
        out.println("           ]");
        out.println("         },");
    }

    /**
     * Escape any characters that need to be escape in the JSON output.
     */
    private static String escape(String name) {
        // TBD
        return name;
    }
}

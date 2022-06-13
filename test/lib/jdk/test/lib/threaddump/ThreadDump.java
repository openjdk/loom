/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.test.lib.threaddump;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import jdk.test.lib.json.JSONValue;

/**
 * Represents a thread dump that is obtained by parsing JSON text. A thread dump in JSON
 * format is generated with the {@code com.sun.management.HotSpotDiagnosticMXBean} API or
 * using {@code jcmd <pid> Thread.dump_to_file -format=json <file>}.
 *
 * <p> The following is an example thread dump that is parsed by this class. Many of the
 * objects are collapsed to reduce the size.
 *
 * <pre>{@code
 * {
 *   "threadDump": {
 *     "processId": "63406",
 *     "time": "2022-05-20T07:37:16.308017Z",
 *     "runtimeVersion": "19",
 *     "threadContainers": [
 *       {
 *         "container": "<root>",
 *         "parent": null,
 *         "owner": null,
 *         "threads": [
 *          {
 *            "tid": "1",
 *            "name": "main",
 *            "stack": [...]
 *          },
 *          {
 *            "tid": "8",
 *            "name": "Reference Handler",
 *            "stack": [
 *               "java.base\/java.lang.ref.Reference.waitForReferencePendingList(Native Method)",
 *               "java.base\/java.lang.ref.Reference.processPendingReferences(Reference.java:245)",
 *               "java.base\/java.lang.ref.Reference$ReferenceHandler.run(Reference.java:207)"
 *            ]
 *          },
 *          {"name": "Finalizer"...},
 *          {"name": "Signal Dispatcher"...},
 *          {"name": "Common-Cleaner"...},
 *          {"name": "Monitor Ctrl-Break"...},
 *          {"name": "Notification Thread"...}
 *         ],
 *         "threadCount": "7"
 *       },
 *       {
 *         "container": "ForkJoinPool.commonPool\/jdk.internal.vm.SharedThreadContainer@56aac163",
 *         "parent": "<root>",
 *         "owner": null,
 *         "threads": [...],
 *         "threadCount": "1"
 *       },
 *       {
 *         "container": "java.util.concurrent.ThreadPoolExecutor@20322d26\/jdk.internal.vm.SharedThreadContainer@184f6be2",
 *         "parent": "<root>",
 *         "owner": null,
 *         "threads": [...],
 *         "threadCount": "1"
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <p> The following is an example using this class to print the tree of thread containers
 * (grouping of threads) and the threads in each container:
 *
 * <pre>{@code
 *    void printThreadDump(Path file) throws IOException {
 *         String json = Files.readString(file);
 *         ThreadDump dump = ThreadDump.parse(json);
 *         printThreadContainer(dump.rootThreadContainer(), 0);
 *     }
 *
 *     void printThreadContainer(ThreadDump.ThreadContainer container, int indent) {
 *         out.printf("%s%s%n", " ".repeat(indent), container);
 *         container.threads().forEach(t -> out.printf("%s%s%n", " ".repeat(indent), t.name()));
 *         container.children().forEach(c -> printThreadContainer(c, indent + 2));
 *     }
 * }</pre>
 */
public final class ThreadDump {
    private final long processId;
    private final String time;
    private final String runtimeVersion;
    private ThreadContainer rootThreadContainer;

    /**
     * Represents an element in the threadDump/threadContainers array.
     */
    public static class ThreadContainer {
        private final String name;
        private long owner;
        private ThreadContainer parent;
        private Set<ThreadInfo> threads;
        private final Set<ThreadContainer> children = new HashSet<>();

        ThreadContainer(String name) {
            this.name = name;
        }

        /**
         * Returns the thread container name.
         */
        public String name() {
            return name;
        }

        /**
         * Return the thread identifier of the owner or empty OptionalLong if not owned.
         */
        public OptionalLong owner() {
           return (owner != 0) ? OptionalLong.of(owner) : OptionalLong.empty();
        }

        /**
         * Returns the parent thread container or empty Optional if this is the root.
         */
        public Optional<ThreadContainer> parent() {
            return Optional.ofNullable(parent);
        }

        /**
         * Returns a stream of the children thread containers.
         */
        public Stream<ThreadContainer> children() {
            return children.stream();
        }

        /**
         * Returns a stream of {@code ThreadInfo} objects for the threads in this container.
         */
        public Stream<ThreadInfo> threads() {
            return threads.stream();
        }

        /**
         * Finds the thread in this container with the given thread identifier.
         */
        public Optional<ThreadInfo> findThread(long tid) {
            return threads()
                    .filter(ti -> ti.tid() == tid)
                    .findAny();
        }


        /**
         * Helper method to recursively find a container with the given name.
         */
        ThreadContainer findThreadContainer(String name) {
            if (name().equals(name))
                return this;
            if (name().startsWith(name + "/"))
                return this;
            return children()
                    .map(c -> c.findThreadContainer(name))
                    .filter(c -> c != null)
                    .findAny()
                    .orElse(null);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ThreadContainer other) {
                return name.equals(other.name);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Represents an element in the threadDump/threadContainers/threads array.
     */
    public static final class ThreadInfo {
        private final long tid;
        private final String name;
        private final List<String> stack;

        ThreadInfo(long tid, String name, List<String> stack) {
            this.tid = tid;
            this.name = name;
            this.stack = stack;
        }

        /**
         * Returns the thread identifier.
         */
        public long tid() {
            return tid;
        }

        /**
         * Returns the thread name.
         */
        public String name() {
            return name;
        }

        /**
         * Returns the thread stack.
         */
        public Stream<String> stack() {
            return stack.stream();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(tid);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ThreadInfo other) {
                return this.tid == other.tid;
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("#");
            sb.append(tid);
            if (name.length() > 0) {
                sb.append(",");
                sb.append(name);
            }
            return sb.toString();
        }
    }

    /**
     * Parses the given JSON text as a thread dump.
     */
    private ThreadDump(String json) {
        JSONValue threadDumpObj = JSONValue.parse(json).get("threadDump");

        // maps container name to ThreadContainer
        Map<String, ThreadContainer> map = new HashMap<>();

        // threadContainers array
        JSONValue threadContainersObj = threadDumpObj.get("threadContainers");
        for (JSONValue containerObj : threadContainersObj.asArray()) {
            String name = containerObj.get("container").asString();
            String parentName = containerObj.get("parent").asString();
            String owner = containerObj.get("owner").asString();
            JSONValue.JSONArray threadsObj = containerObj.get("threads").asArray();

            // threads array
            Set<ThreadInfo> threadInfos = new HashSet<>();
            for (JSONValue threadObj : threadsObj) {
                long tid = Long.parseLong(threadObj.get("tid").asString());
                String threadName = threadObj.get("name").asString();
                JSONValue.JSONArray stackObj = threadObj.get("stack").asArray();
                List<String> stack = new ArrayList<>();
                for (JSONValue steObject : stackObj) {
                    stack.add(steObject.asString());
                }
                threadInfos.add(new ThreadInfo(tid, threadName, stack));
            }

            // add to map if not already encountered
            var container = map.computeIfAbsent(name, k -> new ThreadContainer(name));
            if (owner != null)
                container.owner = Long.parseLong(owner);
            container.threads = threadInfos;

            if (parentName == null) {
                rootThreadContainer = container;
            } else {
                // add parent to map if not already encountered and add to its set of children
                var parent = map.computeIfAbsent(parentName, k -> new ThreadContainer(parentName));
                container.parent = parent;
                parent.children.add(container);
            }
        }

        this.processId = Long.parseLong(threadDumpObj.get("processId").asString());
        this.time = threadDumpObj.get("time").asString();
        this.runtimeVersion = threadDumpObj.get("runtimeVersion").asString();
    }

    /**
     * Returns the value of threadDump/processId.
     */
    public long processId() {
        return processId;
    }

    /**
     * Returns the value of threadDump/time.
     */
    public String time() {
        return time;
    }

    /**
     * Returns the value of threadDump/runtimeVersion.
     */
    public String runtimeVersion() {
        return runtimeVersion;
    }

    /**
     * Returns the root container in the threadDump/threadContainers array.
     */
    public ThreadContainer rootThreadContainer() {
        return rootThreadContainer;
    }

    /**
     * Finds a container in the threadDump/threadContainers array with the given name.
     */
    public Optional<ThreadContainer> findThreadContainer(String name) {
        ThreadContainer container = rootThreadContainer.findThreadContainer(name);
        return Optional.ofNullable(container);
    }

    /**
     * Parses JSON text as a thread dump.
     * @throws RuntimeException if an error occurs
     */
    public static ThreadDump parse(String json) {
        return new ThreadDump(json);
    }
}

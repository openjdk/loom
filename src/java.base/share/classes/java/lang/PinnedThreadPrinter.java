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
package java.lang;

import java.io.PrintStream;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import jdk.internal.vm.annotation.ChangesCurrentThread;
import sun.nio.ch.ConsoleStreams;

import static java.lang.StackWalker.Option.*;

/**
 * Helper class to print the virtual thread stack trace when its carrier thread is
 * pinned.
 *
 * The class maintains a mapping of Class to the hashes of stack traces that are by
 * pinned by code in that Class. This is used to avoid printing the same stack trace
 * many times.
 */
class PinnedThreadPrinter {
    static final StackWalker STACK_WALKER;
    static {
        var options = Set.of(SHOW_REFLECT_FRAMES, RETAIN_CLASS_REFERENCE);
        PrivilegedAction<StackWalker> pa = () ->
            LiveStackFrame.getStackWalker(options, VirtualThread.continuationScope());
        STACK_WALKER = AccessController.doPrivileged(pa);
    }

    // maps a class to the hashes of stack traces pinned by that code in that class
    private static final Map<Class<?>, Hashes> classToHashes = new WeakHashMap<>();

    @SuppressWarnings("serial")
    private static class Hashes extends LinkedHashMap<Integer, Boolean> {
        boolean add(int hash) {
            return (putIfAbsent(hash, Boolean.TRUE) == null);
        }
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> oldest) {
            // limit number of hashes
            return size() > 8;
        }
    }

    /**
     * Returns a hash of the given stack trace. The hash is based on the class,
     * method and bytecode index.
     */
    private static int hash(List<LiveStackFrame> stack) {
        int hash = 0;
        for (LiveStackFrame frame : stack) {
            hash = (31 * hash) + Objects.hash(frame.getDeclaringClass(),
                    frame.getMethodName(),
                    frame.getByteCodeIndex());
        }
        return hash;
    }

    /**
     * Prints the stack trace of the current virtual thread to the given print
     * stream.
     *
     * This method does nothing if standard output or error locked by the current
     * thread. This avoid recursive usage if pinned when when printing to either
     * stream.
     *
     * @param printAll true to print all stack frames, false to only print the
     *        frames that are native or holding a monitor
     */
    @ChangesCurrentThread
    static void printStackTrace(PrintStream out, boolean printAll) {
        assert Thread.currentThread().isVirtual();
        if (!ConsoleStreams.isOutOrErrLocked(Thread.currentThread())) {
            // switch to carrier thread as the printing may park
            Thread vthread = Thread.currentThread();
            Thread carrier = Thread.currentCarrierThread();
            carrier.setCurrentThread(carrier);
            try {
                List<LiveStackFrame> stack = STACK_WALKER.walk(s ->
                    s.map(f -> (LiveStackFrame) f)
                            .filter(f -> f.getDeclaringClass() != PinnedThreadPrinter.class)
                            .collect(Collectors.toList())
                );

                // find the closest frame that is causing the thread to be pinned
                stack.stream()
                    .filter(f -> (f.isNativeMethod() || f.getMonitors().length > 0))
                    .map(LiveStackFrame::getDeclaringClass)
                    .findFirst()
                    .ifPresent(key -> {
                        int hash = hash(stack);
                        synchronized (classToHashes) {
                            // print the stack trace if not already seen
                            if (classToHashes.computeIfAbsent(key, k -> new Hashes()).add(hash)) {
                                printStackTrace(stack, out, printAll);
                            }
                        }
                    });
            } finally {
                carrier.setCurrentThread(vthread);
            }
        }
    }

    private static void printStackTrace(List<LiveStackFrame> stack,
                                        PrintStream out,
                                        boolean printAll) {
        out.println(Thread.currentThread());
        for (LiveStackFrame frame : stack) {
            var ste = frame.toStackTraceElement();
            int monitorCount = frame.getMonitors().length;
            if (monitorCount > 0 || frame.isNativeMethod()) {
                out.format("    %s <== monitors:%d%n", ste, monitorCount);
            } else if (printAll) {
                out.format("    %s%n", ste);
            }
        }
    }
}

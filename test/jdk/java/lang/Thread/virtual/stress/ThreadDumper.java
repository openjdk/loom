/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;

class ThreadDumper {
    private ThreadDumper() { }

    static void dumpThreads(ExecutorService executor) throws Exception {
        Field threadsField = Class.forName("java.util.concurrent.ThreadExecutor")
                .getDeclaredField("threads");
        threadsField.setAccessible(true);
        dumpThreads((Set<Thread>) threadsField.get(executor));
    }
    static void dumpThreads(Set<Thread> threads) {
        for (Thread t : threads) {
            System.out.println(t);
            StackTraceElement[] stackTrace = t.getStackTrace();
            for (StackTraceElement e : stackTrace) {
                System.out.println("    " + e);
            }
        }
    }

    static void monitor(ExecutorService executor) {
        // monitoring thread
        Thread.builder().daemon(true).name("Thread-Monitor").task(() -> {
            for (;;) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                    System.out.println("=== " + Instant.now() + " ===");
                    ThreadDumper.dumpThreads(executor);
                } catch (Exception e) {  }
            }
        }).start();
    }

}

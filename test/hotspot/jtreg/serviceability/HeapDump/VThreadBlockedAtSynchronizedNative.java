/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @summary Test dumping the heap while a virtual thread is blocked entering a synchronized native method
 * @requires vm.continuations
 * @modules jdk.management
 * @library /test/lib
 * @run main/othervm --enable-native-access=ALL-UNNAMED VThreadBlockedAtSynchronizedNative
 */

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import com.sun.management.HotSpotDiagnosticMXBean;

import jdk.test.lib.hprof.model.Snapshot;
import jdk.test.lib.hprof.model.ThreadObject;
import jdk.test.lib.hprof.parser.Reader;

public class VThreadBlockedAtSynchronizedNative {

    public static void main(String... args) throws Exception {
        var runner = new VThreadBlockedAtSynchronizedNative();

        // Dump heap while a virtual thread is blocked entering a synchronized native method
        Path dumpFile = runner.callWithVThreadBlockedAtSynchronizedNative(() -> {
            Path df = Files.createTempFile(Path.of("."), "dump", ".hprof");
            Files.delete(df);
            var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            bean.dumpHeap(df.toString(), false);
            return df;
        });

        // Make sure that heap dump can be parsed
        System.out.println("Parse " + dumpFile.toAbsolutePath() + " ...");
        try (Snapshot snapshot = Reader.readFile(dumpFile.toString(), false, 0)) {
            snapshot.resolve(true);

            // find virtual threads
            List<ThreadObject> vthreads = snapshot.getThreads()
                    .stream()
                    .filter(t -> snapshot.findThing(t.getId())
                            .getClazz()
                            .getName().equals("java.lang.VirtualThread"))
                    .toList();

            if (vthreads.isEmpty()) {
                throw new RuntimeException("No virtual threads found!!");
            }
            System.out.format("%s virtual thread(s) found%n", vthreads.size());
        }
    }

    /**
     * Calls with the given task while a virtual thread is blocked trying to invoke a
     * synchronized native method.
     */
    private <T> T callWithVThreadBlockedAtSynchronizedNative(Callable<T> task) throws Exception {
        var lock = this;
        var started = new CountDownLatch(1);
        var vthread = Thread.ofVirtual().unstarted(() -> {
            started.countDown();
            runWithSynchronizedNative(() -> { });
        });
        try {
            synchronized (lock) {
                vthread.start();

                // wait for thread to start and block
                started.await();
                await(vthread, Thread.State.BLOCKED);

                // invoke task while virtual thread is blocked
                return task.call();
            }
        } finally {
            vthread.join();
        }
    }

    /**
     * Invokes the given task's run method while holding the monitor for "this".
     */
    private synchronized native void runWithSynchronizedNative(Runnable task);

    /**
     * Called from the native method to run the given task.
     */
    private void run(Runnable task) {
        task.run();
    }

    /**
     * Waits for the given thread to reach a given state.
     */
    private void await(Thread thread, Thread.State expectedState) throws InterruptedException {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            Thread.sleep(10);
            state = thread.getState();
        }
    }

    static {
        System.loadLibrary("VThreadBlockedAtSynchronizedNative");
    }
}
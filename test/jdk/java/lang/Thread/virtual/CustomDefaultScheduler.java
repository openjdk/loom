/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
 * @test id=default
 * @summary Test using a custom scheduler as the default virtual thread scheduler
 * @requires vm.continuations
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler1
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 * @run junit/othervm -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler2
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 */

/*
 * @test id=poller-modes
 * @requires vm.continuations
 * @requires (os.family == "linux") | (os.family == "mac")
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.pollerMode=3
 *     -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler1
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 * @run junit/othervm -Djdk.pollerMode=3
 *     -Djdk.virtualThreadScheduler.implClass=CustomDefaultScheduler$CustomScheduler2
 *     --enable-native-access=ALL-UNNAMED CustomDefaultScheduler
 */

import java.io.Closeable;
import java.io.IOException;
import java.lang.Thread.VirtualThreadScheduler;
import java.lang.Thread.VirtualThreadTask;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import jdk.test.lib.thread.VThreadRunner;
import jdk.test.lib.thread.VThreadScheduler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class CustomDefaultScheduler {
    private static String schedulerClassName;

    @BeforeAll
    static void setup() {
        schedulerClassName = System.getProperty("jdk.virtualThreadScheduler.implClass");
    }

    /**
     * Custom scheduler that uses a thread pool.
     */
    public static class CustomScheduler1 implements VirtualThreadScheduler {
        private final ExecutorService pool;

        public CustomScheduler1() {
            ThreadFactory factory = Thread.ofPlatform().daemon().factory();
            pool = Executors.newFixedThreadPool(1, factory);
        }

        @Override
        public void onStart(VirtualThreadTask task) {
            pool.execute(task);
        }

        @Override
        public void onContinue(VirtualThreadTask task) {
            pool.execute(task);
        }
    }

    /**
     * Custom scheduler that delegates to the built-in default scheduler.
     */
    public static class CustomScheduler2 implements VirtualThreadScheduler {
        private final VirtualThreadScheduler builtinScheduler;

        // the set of threads that executed with this scheduler
        private final Set<Thread> executed = ConcurrentHashMap.newKeySet();

        public CustomScheduler2(VirtualThreadScheduler builtinScheduler) {
            this.builtinScheduler = builtinScheduler;
        }

        VirtualThreadScheduler builtinScheduler() {
            return builtinScheduler;
        }

        @Override
        public void onStart(VirtualThreadTask task) {
            executed.add(task.thread());
            builtinScheduler.onStart(task);
        }

        @Override
        public void onContinue(VirtualThreadTask task) {
            executed.add(task.thread());
            builtinScheduler.onContinue(task);
        }

        Set<Thread> threadsExecuted() {
            return executed;
        }
    }

    /**
     * Test that a virtual thread uses the custom default scheduler.
     */
    @Test
    void testUseCustomScheduler() throws Exception {
        var ref = new AtomicReference<VirtualThreadScheduler>();
        Thread.startVirtualThread(() -> {
            ref.set(currentScheduler());
        }).join();
        VirtualThreadScheduler scheduler = ref.get();
        assertEquals(schedulerClassName, scheduler.getClass().getName());
    }

    /**
     * Test virtual thread park/unpark when using custom default scheduler.
     */
    @Test
    void testPark() throws Exception {
        var done = new AtomicBoolean();
        var thread = Thread.startVirtualThread(() -> {
            while (!done.get()) {
                LockSupport.park();
            }
        });
        try {
            await(thread, Thread.State.WAITING);
        } finally {
            done.set(true);
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test virtual thread blocking on a monitor when using custom default scheduler.
     */
    @Test
    void testBlockMonitor() throws Exception {
        var ready = new CountDownLatch(1);
        var lock = new Object();
        var thread = Thread.ofVirtual().unstarted(() -> {
            ready.countDown();
            synchronized (lock) {
            }
        });
        synchronized (lock) {
            thread.start();
            ready.await();
            await(thread, Thread.State.BLOCKED);
        }
        thread.join();
    }

    /**
     * Test virtual thread blocking on a socket I/O when using custom default scheduler.
     */
    @Test
    void testBlockSocket() throws Exception {
        VThreadRunner.run(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // write bytes after current virtual thread has parked
                byte[] ba1 = "XXX".getBytes("UTF-8");
                runAfterParkedAsync(() -> s1.getOutputStream().write(ba1));

                byte[] ba2 = new byte[10];
                int n = s2.getInputStream().read(ba2);
                assertTrue(n > 0);
                assertTrue(ba2[0] == 'X');
            }
        });
    }

    /**
     * Test one virtual thread starting a second virtual thread when both are scheduled
     * by a custom default scheduler delegating to builtin default scheduler.
     */
    @Test
    void testDelegatingToBuiltin() throws Exception {
        assumeTrue(schedulerClassName.equals("CustomDefaultScheduler$CustomScheduler2"));

        var schedulerRef = new AtomicReference<VirtualThreadScheduler>();
        var vthreadRef = new AtomicReference<Thread>();

        var vthread1 = Thread.ofVirtual().start(() -> {
            schedulerRef.set(currentScheduler());
            Thread vthread2 = Thread.ofVirtual().start(() -> {
                assertTrue(currentScheduler() == schedulerRef.get());
                vthreadRef.set(Thread.currentThread());
            });
            try {
                vthread2.join();
            } catch (InterruptedException e) {
                // fail();
            }
        });

        vthread1.join();
        Thread vthread2 = vthreadRef.get();

        var customScheduler = (CustomScheduler2) schedulerRef.get();
        assertTrue(customScheduler.threadsExecuted().contains(vthread1));
        assertTrue(customScheduler.threadsExecuted().contains(vthread2));
    }

    /**
     * Returns the scheduler for the current virtual thread.
     */
    private static VirtualThreadScheduler currentScheduler() {
        return VThreadScheduler.scheduler(Thread.currentThread());
    }

    /**
     * Waits for the given thread to reach a given state.
     */
    private void await(Thread thread, Thread.State expectedState) throws InterruptedException {
        Thread.State state = thread.getState();
        while (state != expectedState) {
            assertTrue(state != Thread.State.TERMINATED, "Thread has terminated");
            Thread.sleep(10);
            state = thread.getState();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Runs the given task asynchronously after the current virtual thread has parked.
     * @return the thread started to run the task
     */
    private static Thread runAfterParkedAsync(ThrowingRunnable task) {
        Thread target = Thread.currentThread();
        if (!target.isVirtual())
            throw new WrongThreadException();
        return Thread.ofPlatform().daemon().start(() -> {
            try {
                Thread.State state = target.getState();
                while (state != Thread.State.WAITING
                        && state != Thread.State.TIMED_WAITING) {
                    Thread.sleep(20);
                    state = target.getState();
                }
                Thread.sleep(20);  // give a bit more time to release carrier
                task.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Creates a loopback connection
     */
    private static class Connection implements Closeable {
        private final Socket s1;
        private final Socket s2;
        Connection() throws IOException {
            var lh = InetAddress.getLoopbackAddress();
            try (var listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress(lh, 0));
                Socket s1 = new Socket();
                Socket s2;
                try {
                    s1.connect(listener.getLocalSocketAddress());
                    s2 = listener.accept();
                } catch (IOException ioe) {
                    s1.close();
                    throw ioe;
                }
                this.s1 = s1;
                this.s2 = s2;
            }

        }
        Socket socket1() {
            return s1;
        }
        Socket socket2() {
            return s2;
        }
        @Override
        public void close() throws IOException {
            s1.close();
            s2.close();
        }
    }
}

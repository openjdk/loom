/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test virtual threads using Object.wait/notifyAll
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=LM_LEGACY
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -XX:LockingMode=1 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=LM_LIGHTWEIGHT
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -XX:LockingMode=2 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xint-LM_LEGACY
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xint -XX:LockingMode=1 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xint-LM_LIGHTWEIGHT
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xint -XX:LockingMode=2 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xcomp-TieredStopAtLevel1-LM_LEGACY
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xcomp -XX:TieredStopAtLevel=1 -XX:LockingMode=1 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xcomp-TieredStopAtLevel1-LM_LIGHTWEIGHT
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xcomp -XX:TieredStopAtLevel=1 -XX:LockingMode=2 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xcomp-noTieredCompilation-LM_LEGACY
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xcomp -XX:-TieredCompilation -XX:LockingMode=1 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

/*
 * @test id=Xcomp-noTieredCompilation-LM_LIGHTWEIGHT
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm -Xcomp -XX:-TieredCompilation -XX:LockingMode=2 --enable-native-access=ALL-UNNAMED MonitorWaitNotify
 */

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import jdk.test.lib.thread.VThreadRunner;
import jdk.test.lib.thread.VThreadPinner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MonitorWaitNotify {

    @BeforeAll
    static void setup() {
        // need >=2 carriers for testing pinning when main thread is a virtual thread
        if (Thread.currentThread().isVirtual()) {
            VThreadRunner.ensureParallelism(2);
        }
    }

    /**
     * Test virtual thread waits, notified by platform thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testWaitNotify1(boolean pinned) throws Exception {
        var lock = new Object();
        var ready = new AtomicBoolean();
        var thread = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                try {
                    if (pinned) {
                        VThreadPinner.runPinned(() -> {
                            ready.set(true);
                            lock.wait();
                        });
                    } else {
                        ready.set(true);
                        lock.wait();
                    }
                } catch (InterruptedException e) { }
            }
        });
        awaitTrue(ready);

        // notify, thread should block waiting to reenter
        synchronized (lock) {
            lock.notifyAll();
            await(thread, Thread.State.BLOCKED);
        }
        thread.join();
    }

    /**
     * Test platform thread waits, notified by virtual thread.
     */
    @Test
    void testWaitNotify2() throws Exception {
        var lock = new Object();
        var thread = Thread.ofVirtual().unstarted(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            thread.start();
            lock.wait();
        }
        thread.join();
    }

    /**
     * Test virtual thread waits, notified by another virtual thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void testWaitNotify3(boolean pinned) throws Exception {
        var lock = new Object();
        var ready = new AtomicBoolean();
        var thread1 = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                try {
                    if (pinned) {
                        VThreadPinner.runPinned(() -> {
                            ready.set(true);
                            lock.wait();
                        });
                    } else {
                        ready.set(true);
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        var thread2 = Thread.ofVirtual().start(() -> {
            try {
                awaitTrue(ready);

                // notify, thread should block waiting to reenter
                synchronized (lock) {
                    lock.notifyAll();
                    await(thread1, Thread.State.BLOCKED);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        thread1.join();
        thread2.join();
    }

    /**
     * Testing invoking Object.wait with interrupt status set.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testWaitWithInterruptSet(int timeout) throws Exception {
        VThreadRunner.run(() -> {
            Object lock = new Object();
            synchronized (lock) {
                Thread.currentThread().interrupt();
                if (timeout > 0) {
                    assertThrows(InterruptedException.class, () -> lock.wait(timeout));
                } else {
                    assertThrows(InterruptedException.class, lock::wait);
                }
                assertFalse(Thread.currentThread().isInterrupted());
            }
        });
    }

    /**
     * Test interrupting a virtual thread waiting in Object.wait.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testInterruptWait(int timeout) throws Exception {
        var lock = new Object();
        var ready = new AtomicBoolean();
        var interruptedException = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                try {
                    ready.set(true);
                    if (timeout > 0) {
                        lock.wait(timeout);
                    } else {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    checkInterruptedException(e);
                    interruptedException.set(true);
                }
            }
        });

        // wait for thread to start and wait
        awaitTrue(ready);
        await(vthread, timeout > 0 ? Thread.State.TIMED_WAITING : Thread.State.WAITING);

        // interrupt thread, it should throw InterruptedException and terminate
        vthread.interrupt();
        vthread.join();
        assertTrue(interruptedException.get());
    }

    /**
     * Test interrupting a virtual thread blocked waiting to reenter after waiting.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testInterruptReenter(int timeout) throws Exception {
        var lock = new Object();
        var ready = new AtomicBoolean();
        var interruptedException = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                try {
                    ready.set(true);
                    if (timeout > 0) {
                        lock.wait(timeout);
                    } else {
                        lock.wait();
                    }
                } catch (InterruptedException e) {
                    checkInterruptedException(e);
                    interruptedException.set(true);
                }
            }
        });

        // wait for thread to start and wait
        awaitTrue(ready);
        await(vthread, timeout > 0 ? Thread.State.TIMED_WAITING : Thread.State.WAITING);

        // notify, thread should block waiting to reenter
        synchronized (lock) {
            lock.notifyAll();
            await(vthread, Thread.State.BLOCKED);
            vthread.interrupt();
        }

        vthread.join();
        assertFalse(interruptedException.get());
        assertTrue(vthread.isInterrupted());
    }

    /**
     * Test Object.wait with recursive locking.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testRecursive(int timeout) throws Exception {
        var lock = new Object();
        var ready = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                synchronized (lock) {
                    synchronized (lock) {
                        try {
                            ready.set(true);
                            if (timeout > 0) {
                                lock.wait(timeout);
                            } else {
                                lock.wait();
                            }
                        } catch (InterruptedException e) { }
                    }
                }
            }
        });

        // wait for thread to start and wait
        awaitTrue(ready);
        await(vthread, timeout > 0 ? Thread.State.TIMED_WAITING : Thread.State.WAITING);

        // notify, thread should block waiting to reenter
        synchronized (lock) {
            lock.notifyAll();
            await(vthread, Thread.State.BLOCKED);
        }
        vthread.join();
    }

    /**
     * Test that Object.wait does not consume the thread's parking permit.
     */
    @Test
    void testParkingPermitNotConsumed() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            started.countDown();
            LockSupport.unpark(Thread.currentThread());
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    fail("wait interrupted");
                }
            }
            LockSupport.park();      // should not park
            completed.set(true);
        });

        // wait for thread to start and wait
        started.await();
        await(vthread, Thread.State.WAITING);

        // wakeup thread
        synchronized (lock) {
            lock.notifyAll();
        }

        // thread should terminate
        vthread.join();
        assertTrue(completed.get());
    }

    /**
     * Test that Object.wait does not make available the thread's parking permit.
     */
    @Test
    void testParkingPermitNotOffered() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var readyToPark = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            started.countDown();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    fail("wait interrupted");
                }
            }
            readyToPark.countDown();
            LockSupport.park();      // should park
            completed.set(true);
        });

        // wait for thread to start and wait
        started.await();
        await(vthread, Thread.State.WAITING);

        // wakeup thread
        synchronized (lock) {
            lock.notifyAll();
        }

        // thread should park
        readyToPark.await();
        await(vthread, Thread.State.WAITING);

        LockSupport.unpark(vthread);

        // thread should terminate
        vthread.join();
        assertTrue(completed.get());
    }

    /**
     * Test that Object.wait releases the carrier.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testReleaseWhenWaiting1(int timeout) throws Exception {
        assertTrue(ThreadBuilders.supportsCustomScheduler(), "No support for custom schedulers");
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread.Builder builder = ThreadBuilders.virtualThreadBuilder(scheduler);

            var lock = new Object();
            var ready = new AtomicBoolean();
            var completed = new AtomicBoolean();

            var vthread1 = builder.start(() -> {
                synchronized (lock) {
                    try {
                        ready.set(true);
                        if (timeout > 0) {
                            lock.wait(timeout);
                        } else {
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        fail("wait interrupted");
                    }
                }
                completed.set(true);
            });

            // wait for vthread1 to start and wait
            awaitTrue(ready);
            await(vthread1, timeout > 0 ? Thread.State.TIMED_WAITING : Thread.State.WAITING);

            // carrier should be released, use it for another thread
            var executed = new AtomicBoolean();
            var vthread2 = builder.start(() -> {
                executed.set(true);
            });
            vthread2.join();
            assertTrue(executed.get());

            // wakeup vthread1
            synchronized (lock) {
                lock.notifyAll();
            }

            vthread1.join();
            assertTrue(completed.get());
        }
    }

    /**
     * Test that Object.wait releases the carrier with multiple virtual threads waiting.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, 30000, Integer.MAX_VALUE })
    void testReleaseWhenWaiting2(int timeout) throws Exception {
        int VTHREAD_COUNT = 4 * Runtime.getRuntime().availableProcessors();
        CountDownLatch latch = new CountDownLatch(VTHREAD_COUNT);
        Object lock = new Object();
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < VTHREAD_COUNT; i++) {
            Thread.ofVirtual().name("vthread-" + i).start(() -> {
                synchronized (lock) {
                    if (counter.incrementAndGet() == VTHREAD_COUNT) {
                        lock.notifyAll();
                    } else {
                        try {
                            if (timeout > 0) {
                                lock.wait(timeout);
                            } else {
                                lock.wait();
                            }
                        } catch (InterruptedException e) {}
                    }
                }
                latch.countDown();
            });
        }
        latch.await();
    }

    /**
     * Test that wait(long) throws IAE when timeout is negative.
     */
    @Test
    void testIllegalArgumentException() throws Exception {
        VThreadRunner.run(() -> {
            Object obj = new Object();
            synchronized (obj) {
                assertThrows(IllegalArgumentException.class, () -> obj.wait(-1L));
                assertThrows(IllegalArgumentException.class, () -> obj.wait(-1000L));
                assertThrows(IllegalArgumentException.class, () -> obj.wait(Long.MIN_VALUE));
            }
        });
    }

    /**
     * Test that wait throws IMSE when not owner.
     */
    @Test
    void testIllegalMonitorStateException() throws Exception {
        VThreadRunner.run(() -> {
            Object obj = new Object();
            assertThrows(IllegalMonitorStateException.class, () -> obj.wait());
            assertThrows(IllegalMonitorStateException.class, () -> obj.wait(0));
            assertThrows(IllegalMonitorStateException.class, () -> obj.wait(1000));
            assertThrows(IllegalMonitorStateException.class, () -> obj.wait(Long.MAX_VALUE));
        });
    }

    /**
     * Waits for the boolean value to become true.
     */
    private static void awaitTrue(AtomicBoolean ref) throws InterruptedException {
        while (!ref.get()) {
            Thread.sleep(20);
        }
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

    /**
     * Test that an InterruptedException thrown by Object.wait has the expected methods in
     * the stack trace.
     */
    private static void checkInterruptedException(InterruptedException e) {
        Set<String> expected = Set.of("wait0", "wait", "run");
        Set<String> methods = Stream.of(e.getStackTrace())
                .map(StackTraceElement::getMethodName)
                .collect(Collectors.toSet());
        assertTrue(methods.containsAll(expected));
    }
}

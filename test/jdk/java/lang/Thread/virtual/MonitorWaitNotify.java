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

/**
 * @test
 * @summary Test virtual threads using Object.wait/notifyAll
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit MonitorWaitNotify
 */

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorWaitNotify {

    /**
     * Test virtual thread waits, notified by platform thread.
     */
    @Test
    void testWaitNotify1() throws Exception {
        var lock = new Object();
        var ready = new Semaphore(0);
        var thread = Thread.ofVirtual().start(() -> {
            synchronized (lock) {
                ready.release();
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });
        // thread invokes notify
        ready.acquire();
        synchronized (lock) {
            lock.notifyAll();
        }
        thread.join();
    }

    /**
     * Test platform thread waits, notified by virtual thread.
     */
    @Test
    void testWaitNotify2() throws Exception {
        var lock = new Object();
        var ready = new Semaphore(0);
        var thread = Thread.ofVirtual().start(() -> {
            ready.acquireUninterruptibly();
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            ready.release();
            lock.wait();
        }
        thread.join();
    }

    /**
     * Test virtual thread waits, notified by another virtual thread.
     */
    @Test
    void testWaitNotify3() throws Exception {
        // need at least two carrier threads due to pinning
        int previousParallelism = VThreadRunner.ensureParallelism(2);
        try {
            var lock = new Object();
            var ready = new Semaphore(0);
            var thread1 = Thread.ofVirtual().start(() -> {
                synchronized (lock) {
                    ready.release();
                    try {
                        lock.wait();
                    } catch (InterruptedException e) { }
                }
            });
            var thread2 = Thread.ofVirtual().start(() -> {
                ready.acquireUninterruptibly();
                synchronized (lock) {
                    lock.notifyAll();
                }
            });
            thread1.join();
            thread2.join();
        } finally {
            // restore
            VThreadRunner.setParallelism(previousParallelism);
        }
    }

    /**
     * Test interrupt status set when calling Object.wait.
     */
    @Test
    void testWaitNotify4() throws Exception {
        VThreadRunner.run(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    fail();
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
    }

    /**
     * Test interrupt when blocked in Object.wait.
     */
    @Test
    void testWaitNotify5() throws Exception {
        VThreadRunner.run(() -> {
            Thread t = Thread.currentThread();
            scheduleInterrupt(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    fail();
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
    }

    /**
     * Testing invoking Object.wait with interrupt status set.
     */
    @Test
    void testWaitWithInterruptSet() throws Exception {
        VThreadRunner.run(() -> {
            Object obj = new Object();
            synchronized (obj) {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedException.class, obj::wait);
                assertFalse(Thread.currentThread().isInterrupted());
            }
        });
    }

    /**
     * Test interrupting a virtual thread waiting in Object.wait.
     */
    @Test
    void testInterruptWait() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var interruptedException = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            started.countDown();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interruptedException.set(true);
                }
            }
        });

        // wait for thread to start and wait
        started.await();
        await(vthread, Thread.State.WAITING);

        // interrupt thread, it should throw InterruptedException and terminate
        vthread.interrupt();
        vthread.join();
        assertTrue(interruptedException.get());
    }

    /**
     * Test interrupting a virtual thread blocked waiting to reenter after waiting.
     */
    @Test
    void testInterruptReenter() throws Exception {
        var lock = new Object();
        var started = new CountDownLatch(1);
        var interruptedException = new AtomicBoolean();
        var vthread = Thread.ofVirtual().start(() -> {
            started.countDown();
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    interruptedException.set(true);
                }
            }
        });

        // wait for thread to start and wait
        started.await();
        await(vthread, Thread.State.WAITING);

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
     * Schedule a thread to be interrupted after a delay.
     */
    private static void scheduleInterrupt(Thread thread, long delay) {
        Runnable interruptTask = () -> {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        new Thread(interruptTask).start();
    }
}

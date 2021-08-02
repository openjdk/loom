/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test Thread API with virtual threads
 * @run testng ThreadAPI
 */

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.nio.channels.Selector;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ThreadAPI {

    /**
     * Test Thread.currentThread before/after park.
     */
    @Test
    public void testCurrentThread1() throws Exception {
        var before = new AtomicReference<Thread>();
        var after = new AtomicReference<Thread>();
        var thread = Thread.ofVirtual().start(() -> {
            before.set(Thread.currentThread());
            LockSupport.park();
            after.set(Thread.currentThread());
        });
        Thread.sleep(100); // give time for virtual thread to park
        LockSupport.unpark(thread);
        thread.join();
        assertTrue(before.get() == thread);
        assertTrue(after.get() == thread);
    }

    /**
     * Test Thread.currentThread before/after entering synchronized block.
     */
    @Test
    public void testCurrentThread2() throws Exception {
        var ref1 = new AtomicReference<Thread>();
        var ref2 = new AtomicReference<Thread>();
        var ref3 = new AtomicReference<Thread>();
        var lock = new Object();
        var thread = Thread.ofVirtual().unstarted(() -> {
            ref1.set(Thread.currentThread());
            synchronized (lock) {
                ref2.set(Thread.currentThread());
            }
            ref3.set(Thread.currentThread());
        });
        synchronized (lock) {
            thread.start();
            Thread.sleep(100); // give time for virtual thread to block
        }
        thread.join();
        assertTrue(ref1.get() == thread);
        assertTrue(ref2.get() == thread);
        assertTrue(ref3.get() == thread);
    }

    /**
     * Test Thread.currentThread before/after acquiring lock.
     */
    @Test
    public void testCurrentThread3() throws Exception {
        var ref1 = new AtomicReference<Thread>();
        var ref2 = new AtomicReference<Thread>();
        var ref3 = new AtomicReference<Thread>();
        var lock = new ReentrantLock();
        var thread = Thread.ofVirtual().unstarted(() -> {
            ref1.set(Thread.currentThread());
            lock.lock();
            try {
                ref2.set(Thread.currentThread());
            } finally {
                lock.unlock();
            }
            ref3.set(Thread.currentThread());
        });
        lock.lock();
        try {
            thread.start();
            Thread.sleep(100); // give time for virtual thread to block
        } finally {
            lock.unlock();
        }
        thread.join();
        assertTrue(ref1.get() == thread);
        assertTrue(ref2.get() == thread);
        assertTrue(ref3.get() == thread);
    }

    /**
     * Test Thread::run.
     */
    @Test
    public void testRun1() throws Exception {
        var ref = new AtomicBoolean();
        var thread = Thread.ofVirtual().unstarted(() -> ref.set(true));
        thread.run();
        assertFalse(ref.get());
    }

    /**
     * Test Thread::start, thread already started.
     */
    @Test
    public void testStart1() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        try {
            assertThrows(IllegalThreadStateException.class, thread::start);
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test Thread::start, thread already terminated.
     */
    @Test
    public void testStart2() throws Exception {
        var thread = Thread.ofVirtual().start(() -> { });
        thread.join();
        assertThrows(IllegalThreadStateException.class, thread::start);
    }

    /**
     * Test Thread::stop from current thread.
     */
    @Test
    public void testStop1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            try {
                t.stop();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
    }

    /**
     * Test Thread::stop from another thread.
     */
    @Test
    public void testStop2() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20*1000);
            } catch (InterruptedException e) { }
        });
        try {
            assertThrows(UnsupportedOperationException.class, () -> thread.stop());
        } finally {
            thread.interrupt();
            thread.join();
        }
    }

    /**
     * Test Thread::suspend from current thread.
     */
    @Test
    public void testSuspend1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            try {
                t.suspend();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
    }

    /**
     * Test Thread::suspend from another thread.
     */
    @Test
    public void testSuspend2() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20*1000);
            } catch (InterruptedException e) { }
        });
        try {
            assertThrows(UnsupportedOperationException.class, () -> thread.suspend());
        } finally {
            thread.interrupt();
            thread.join();
        }
    }

    /**
     * Test Thread::resume from current thread.
     */
    @Test
    public void testResume1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            try {
                t.resume();
                assertTrue(false);
            } catch (UnsupportedOperationException e) {
                // expected
            }
        });
    }

    /**
     * Test Thread::resume from another thread.
     */
    @Test
    public void testResume2() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20*1000);
            } catch (InterruptedException e) { }
        });
        try {
            assertThrows(UnsupportedOperationException.class, () -> thread.resume());
        } finally {
            thread.interrupt();
            thread.join();
        }
    }

    /**
     * Test Thread.join before thread starts, platform thread invokes join.
     */
    @Test
    public void testJoin1() throws Exception {
        var thread = Thread.ofVirtual().unstarted(() -> { });

        // invoke join from platform thread
        thread.join();
        thread.join(0);
        thread.join(100);
        assertThrows(IllegalThreadStateException.class,
                () -> thread.join(Duration.ofMillis(-100)));
        assertThrows(IllegalThreadStateException.class,
                () -> thread.join(Duration.ofMillis(0)));
        assertThrows(IllegalThreadStateException.class,
                () -> thread.join(Duration.ofMillis(100)));
    }

    /**
     * Test Thread.join before thread starts, virtual thread invokes join.
     */
    @Test
    public void testJoin2() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin1);
    }

    /**
     * Test Thread.join where thread does not terminate, platform thread invokes join.
     */
    @Test
    public void testJoin3() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        try {
            thread.join(100);
            assertFalse(thread.join(Duration.ofMillis(-100)));
            assertFalse(thread.join(Duration.ofMillis(0)));
            assertFalse(thread.join(Duration.ofMillis(100)));
            assertTrue(thread.isAlive());
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test Thread.join where thread does not terminate, virtual thread invokes join.
     */
    @Test
    public void testJoin4() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin3);
    }

    /**
     * Test Thread.join where thread terminates, platform thread invokes join.
     */
    @Test
    public void testJoin5() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) { }
        });
        thread.join();
        assertFalse(thread.isAlive());
    }

    /**
     * Test Thread.join where thread terminates, virtual thread invokes join.
     */
    @Test
    public void testJoin6() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin5);
    }

    /**
     * Test Thread.join where thread terminates, platform thread invokes timed-join.
     */
    @Test
    public void testJoin7() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) { }
        });
        thread.join(10*1000);
        assertFalse(thread.isAlive());
    }

    /**
     * Test Thread.join where thread terminates, virtual thread invokes timed-join.
     */
    @Test
    public void testJoin8() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin7);
    }

    /**
     * Test Thread.join where thread terminates, platform thread invokes timed-join.
     */
    @Test
    public void testJoin11() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) { }
        });
        assertTrue(thread.join(Duration.ofSeconds(10)));
        assertFalse(thread.isAlive());
    }

    /**
     * Test Thread.join where thread terminates, virtual thread invokes timed-join.
     */
    @Test
    public void testJoin12() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin11);
    }

    /**
     * Test Thread.join where thread already terminated, platform thread invokes join.
     */
    @Test
    public void testJoin13() throws Exception {
        var thread = Thread.ofVirtual().start(() -> { });
        while (thread.isAlive()) {
            Thread.sleep(10);
        }
        thread.join();
        thread.join(100);
        assertTrue(thread.join(Duration.ofMillis(-100)));
        assertTrue(thread.join(Duration.ofMillis(0)));
        assertTrue(thread.join(Duration.ofMillis(100)));
    }

    /**
     * Test Thread.join where thread already terminated, virtual thread invokes join.
     */
    @Test
    public void testJoin14() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin13);
    }

    /**
     * Test platform thread invoking Thread.join with interrupt status set.
     */
    @Test
    public void testJoin15() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        Thread.currentThread().interrupt();
        try {
            thread.join();
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test virtual thread invoking Thread.join with interrupt status set.
     */
    @Test
    public void testJoin16() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin15);
    }

    /**
     * Test platform thread invoking timed-Thread.join with interrupt status set.
     */
    @Test
    public void testJoin17() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        Thread.currentThread().interrupt();
        try {
            thread.join(100);
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test virtual thread invoking timed-Thread.join with interrupt status set.
     */
    @Test
    public void testJoin18() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin17);
    }

    /**
     * Test platform thread invoking timed-Thread.join with interrupt status set.
     */
    @Test
    public void testJoin19() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        Thread.currentThread().interrupt();
        try {
            thread.join(Duration.ofMillis(100));
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test virtual thread invoking timed-Thread.join with interrupt status set.
     */
    @Test
    public void testJoin20() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin19);
    }

    /**
     * Test interrupt of platform thread blocked in Thread.join.
     */
    @Test
    public void testJoin21() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        TestHelper.scheduleInterrupt(Thread.currentThread(), 100);
        try {
            thread.join();
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test interrupt of virtual thread blocked in Thread.join.
     */
    @Test
    public void testJoin22() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin17);
    }

    /**
     * Test interrupt of platform thread blocked in timed-Thread.join.
     */
    @Test
    public void testJoin23() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        TestHelper.scheduleInterrupt(Thread.currentThread(), 100);
        try {
            thread.join(10*1000);
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test interrupt of virtual thread blocked in Thread.join.
     */
    @Test
    public void testJoin24() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin23);
    }

    /**
     * Test interrupt of platform thread blocked in Thread.join.
     */
    @Test
    public void testJoin25() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        TestHelper.scheduleInterrupt(Thread.currentThread(), 100);
        try {
            thread.join(Duration.ofSeconds(10));
            assertTrue(false);
        } catch (InterruptedException expected) {
            // okay
        } finally {
            Thread.interrupted();
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test interrupt of virtual thread blocked in Thread.join.
     */
    @Test
    public void testJoin26() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin25);
    }

    /**
     * Test virtual thread calling Thread.join to wait for platform thread to terminate.
     */
    @Test
    public void testJoin27() throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        TestHelper.runInVirtualThread(() -> {
            var thread = new Thread(() -> {
                while (!done.get()) {
                    LockSupport.park();
                }
            });
            thread.start();
            try {
                assertFalse(thread.join(Duration.ofMillis(-100)));
                assertFalse(thread.join(Duration.ofMillis(0)));
                assertFalse(thread.join(Duration.ofMillis(100)));
            } finally {
                done.set(true);
                LockSupport.unpark(thread);
                thread.join();
            }
        });
    }

    /**
     * Test virtual thread calling Thread.join to wait for platform thread to terminate.
     */
    @Test
    public void testJoin28() throws Exception {
        long nanos = TimeUnit.NANOSECONDS.convert(2, TimeUnit.SECONDS);
        TestHelper.runInVirtualThread(() -> {
            var thread = new Thread(() -> LockSupport.parkNanos(nanos));
            thread.start();
            try {
                assertTrue(thread.join(Duration.ofSeconds(Integer.MAX_VALUE)));
                assertFalse(thread.isAlive());
            } finally {
                LockSupport.unpark(thread);
                thread.join();
            }
        });
    }

    /**
     * Test virtual thread with interrupt status set calling Thread.join to wait
     * for platform thread to terminate.
     */
    @Test
    public void testJoin29() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var thread = new Thread(LockSupport::park);
            thread.start();
            Thread.currentThread().interrupt();
            try {
                thread.join(Duration.ofSeconds(Integer.MAX_VALUE));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());
            } finally {
                LockSupport.unpark(thread);
                thread.join();
            }
        });
    }

    /**
     * Test interrupting virtual thread that is waiting in Thread.join for
     * platform thread to terminate.
     */
    @Test
    public void testJoin30() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            AtomicBoolean done = new AtomicBoolean();
            var thread = new Thread(() -> {
                while (!done.get()) {
                    LockSupport.park();
                }
            });
            thread.start();
            TestHelper.scheduleInterrupt(Thread.currentThread(), 100);
            try {
                thread.join(Duration.ofSeconds(Integer.MAX_VALUE));
                assertTrue(false);
            } catch (InterruptedException expected) {
                assertFalse(Thread.interrupted());
            } finally {
                done.set(true);
                LockSupport.unpark(thread);
                thread.join();
            }
        });
    }

    /**
     * Test platform thread invoking Thread.join on a thread that is parking
     * and unparking.
     */
    @Test
    public void testJoin31() throws Exception {
        Thread thread = Thread.ofVirtual().start(() -> {
            Object lock = new Object();
            synchronized (lock) {
                for (int i=0; i<10; i++) {
                    LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
                }
            }
        });
        thread.join();
        assertFalse(thread.isAlive());
    }

    /**
     * Test virtual thread invoking Thread.join on a thread that is parking
     * and unparking.
     */
    @Test
    public void testJoin32() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin31);
    }

    /**
     * Test platform thread invoking timed-Thread.join on a thread that is parking
     * and unparking.
     */
    @Test
    public void testJoin33() throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().start(() -> {
            Object lock = new Object();
            synchronized (lock) {
                while (!done.get()) {
                    LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
                }
            }
        });
        try {
            assertFalse(thread.join(Duration.ofSeconds(1)));
        } finally {
            done.set(true);
        }
    }

    /**
     * Test virtual thread invoking timed-Thread.join on a thread that is parking
     * and unparking.
     */
    @Test
    public void testJoin34() throws Exception {
        TestHelper.runInVirtualThread(this::testJoin33);
    }

    /**
     * Test Thread.interrupt on current thread.
     */
    @Test
    public void testInterrupt1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            assertFalse(me.isInterrupted());
            me.interrupt();
            assertTrue(me.isInterrupted());
            Thread.interrupted();  // clear interrupt status
            assertFalse(me.isInterrupted());
            me.interrupt();
        });
    }

    /**
     * Test Thread.interrupt before thread started.
     */
    @Test
    public void testInterrupt2() throws Exception {
        var thread = Thread.ofVirtual().unstarted(() -> { });
        thread.interrupt();
        assertTrue(thread.isInterrupted());
    }

    /**
     * Test Thread.interrupt after thread started.
     */
    @Test
    public void testInterrupt3() throws Exception {
        var thread = Thread.ofVirtual().start(() -> { });
        thread.join();
        thread.interrupt();
        assertTrue(thread.isInterrupted());
    }

    /**
     * Test termination with interrupt status set.
     */
    @Test
    public void testInterrupt4() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
        });
        thread.join();
        assertTrue(thread.isInterrupted());
    }

    /**
     * Test Thread.interrupt of thread blocked in Selector.select.
     */
    @Test
    public void testInterrupt5() throws Exception {
        var exception = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                try (var sel = Selector.open()) {
                    sel.select();
                    assertTrue(Thread.currentThread().isInterrupted());
                }
            } catch (Exception e) {
                exception.set(e);
            }
        });
        Thread.sleep(100);  // give time for thread to block
        thread.interrupt();
        thread.join();
        assertTrue(exception.get() == null);
    }

    /**
     * Test Thread.interrupt of thread parked in sleep.
     */
    @Test
    public void testInterrupt6() throws Exception {
        var exception = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                try {
                    Thread.sleep(60*1000);
                    assertTrue(false);
                } catch (InterruptedException e) {
                    // interrupt status should be reset
                    assertFalse(Thread.interrupted());
                }
            } catch (Exception e) {
                exception.set(e);
            }
        });
        Thread.sleep(100);  // give time for thread to block
        thread.interrupt();
        thread.join();
        assertTrue(exception.get() == null);
    }

    /**
     * Test Thread.interrupt of parked thread.
     */
    @Test
    public void testInterrupt7() throws Exception {
        var exception = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                LockSupport.park();
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (Exception e) {
                exception.set(e);
            }
        });
        Thread.sleep(100);  // give time for thread to block
        thread.interrupt();
        thread.join();
        assertTrue(exception.get() == null);
    }

    /**
     * Test trying to park, wait or block with interrupt status set.
     */
    @Test
    public void testInterrupt8() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            LockSupport.park();
            assertTrue(Thread.interrupted());
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    assertTrue(false);
                } catch (InterruptedException expected) {
                    assertFalse(Thread.interrupted());
                }
            }
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            try (Selector sel = Selector.open()) {
                sel.select();
                assertTrue(Thread.interrupted());
            }
        });
    }

    /**
     * Test Thread.getName and setName from current thread, started without name.
     */
    @Test
    public void testSetName1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            assertEquals(me.getName(), "<unnamed>");
            me.setName("fred");
            assertEquals(me.getName(), "fred");
        });
    }

    /**
     * Test Thread.getName and setName from current thread, started with name.
     */
    @Test
    public void testSetName2() throws Exception {
        TestHelper.runInVirtualThread("fred", () -> {
            Thread me = Thread.currentThread();
            assertEquals(me.getName(), "fred");
            me.setName("joe");
            assertEquals(me.getName(), "joe");
        });
    }

    /**
     * Test Thread.getName and setName from another thread.
     */
    @Test
    public void testSetName3() throws Exception {
        var thread = Thread.ofVirtual().unstarted(LockSupport::park);
        assertEquals(thread.getName(), "<unnamed>");

        // not started
        thread.setName("fred1");
        assertEquals(thread.getName(), "fred1");

        // started
        thread.start();
        try {
            assertEquals(thread.getName(), "fred1");
            thread.setName("fred2");
            assertEquals(thread.getName(), "fred2");
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }

        // terminated
        assertEquals(thread.getName(), "fred2");
        thread.setName("fred3");
        assertEquals(thread.getName(), "fred3");
    }

    /**
     * Test Thread.getPriority and setPriority from current thread.
     */
    @Test
    public void testSetPriority1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            assertTrue(me.getPriority() == Thread.NORM_PRIORITY);

            me.setPriority(Thread.MAX_PRIORITY);
            assertTrue(me.getPriority() == Thread.NORM_PRIORITY);

            me.setPriority(Thread.NORM_PRIORITY);
            assertTrue(me.getPriority() == Thread.NORM_PRIORITY);

            me.setPriority(Thread.MIN_PRIORITY);
            assertTrue(me.getPriority() == Thread.NORM_PRIORITY);

            assertThrows(IllegalArgumentException.class, () -> me.setPriority(-1));
        });
    }

    /**
     * Test Thread.getPriority and setPriority from another thread.
     */
    @Test
    public void testSetPriority2() throws Exception {
        var thread = Thread.ofVirtual().unstarted(LockSupport::park);

        // not started
        assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

        thread.setPriority(Thread.MAX_PRIORITY);
        assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

        thread.setPriority(Thread.NORM_PRIORITY);
        assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

        thread.setPriority(Thread.MIN_PRIORITY);
        assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

        assertThrows(IllegalArgumentException.class, () -> thread.setPriority(-1));

        // running
        thread.start();
        try {
            assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);
            thread.setPriority(Thread.NORM_PRIORITY);

            thread.setPriority(Thread.MAX_PRIORITY);
            assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

            thread.setPriority(Thread.NORM_PRIORITY);
            assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

            thread.setPriority(Thread.MIN_PRIORITY);
            assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);

            assertThrows(IllegalArgumentException.class, () -> thread.setPriority(-1));

        } finally {
            LockSupport.unpark(thread);
        }
        thread.join();

        // terminated
        assertTrue(thread.getPriority() == Thread.NORM_PRIORITY);
    }

    /**
     * Test Thread.isDaemon and setDaemon from current thread.
     */
    @Test
    public void testSetDaemon1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            assertTrue(me.isDaemon());
            assertThrows(IllegalThreadStateException.class, () -> me.setDaemon(true));
            assertThrows(IllegalArgumentException.class, () -> me.setDaemon(false));
        });
    }

    /**
     * Test Thread.isDaemon and setDaemon from another thread.
     */
    @Test
    public void testSetDaemon2() throws Exception {
        var thread = Thread.ofVirtual().unstarted(LockSupport::park);

        // not started
        assertTrue(thread.isDaemon());
        thread.setDaemon(true);
        assertThrows(IllegalArgumentException.class, () -> thread.setDaemon(false));

        // running
        thread.start();
        try {
            assertTrue(thread.isDaemon());
            assertThrows(IllegalThreadStateException.class, () -> thread.setDaemon(true));
            assertThrows(IllegalArgumentException.class, () -> thread.setDaemon(false));
        } finally {
            LockSupport.unpark(thread);
        }
        thread.join();

        // terminated
        assertTrue(thread.isDaemon());
    }

    /**
     * Test Thread.yield releases thread when not pinned.
     */
    @Test
    public void testYield1() throws Exception {
        var list = new CopyOnWriteArrayList<String>();
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            ThreadFactory factory = Thread.ofVirtual().scheduler(scheduler).factory();
            var thread = factory.newThread(() -> {
                list.add("A");
                var child = factory.newThread(() -> {
                    list.add("B");
                    Thread.yield();
                    list.add("B");
                });
                child.start();
                Thread.yield();
                list.add("A");
                try { child.join(); } catch (InterruptedException e) { }
            });
            thread.start();
            thread.join();
        }
        assertEquals(list, List.of("A", "B", "A", "B"));
    }

    /**
     * Test Thread.yield when thread is pinned.
     */
    @Test
    public void testYield2() throws Exception {
        var list = new CopyOnWriteArrayList<String>();
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            ThreadFactory factory = Thread.ofVirtual().scheduler(scheduler).factory();
            var thread = factory.newThread(() -> {
                list.add("A");
                var child = factory.newThread(() -> {
                    list.add("B");
                });
                child.start();
                Object lock = new Object();
                synchronized (lock) {
                    Thread.yield();   // pinned so will be a no-op
                    list.add("A");
                }
                try { child.join(); } catch (InterruptedException e) { }
            });
            thread.start();
            thread.join();
        }
        assertEquals(list, List.of("A", "A", "B"));
    }

    /**
     * Test Thread.onSpinWait.
     */
    @Test
    public void testOnSpinWait() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            Thread.onSpinWait();
            assertTrue(Thread.currentThread() == me);
        });
    }

    /**
     * Test Thread.sleep(-1).
     */
    @Test
    public void testSleep1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try {
                Thread.sleep(-1);
                assertTrue(false);
            } catch (IllegalArgumentException e) {
                // expected
            }
        });
        TestHelper.runInVirtualThread(() -> Thread.sleep(Duration.ofMillis(-1)));
    }

    /**
     * Test Thread.sleep(0).
     */
    @Test
    public void testSleep2() throws Exception {
        TestHelper.runInVirtualThread(() -> Thread.sleep(0));
        TestHelper.runInVirtualThread(() -> Thread.sleep(Duration.ofMillis(0)));
    }

    /**
     * Test Thread.sleep(2000), thread should sleep.
     */
    @Test
    public void testSleep3() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            long start = millisTime();
            Thread.sleep(2000);
            expectDuration(start, /*min*/1900, /*max*/4000);
        });
        TestHelper.runInVirtualThread(() -> {
            long start = millisTime();
            Thread.sleep(Duration.ofMillis(2000));
            expectDuration(start, /*min*/1900, /*max*/4000);
        });
    }

    /**
     * Test Thread.sleep with interrupt status set.
     */
    @Test
    public void testSleep4() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            try {
                Thread.sleep(0);
                assertTrue(false);
            } catch (InterruptedException e) {
                // expected
                assertFalse(me.isInterrupted());
            }
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            try {
                Thread.sleep(1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // expected
                assertFalse(me.isInterrupted());
            }
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            Thread.sleep(Duration.ofMillis(-1000));  // does nothing
            assertTrue(me.isInterrupted());
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            try {
                Thread.sleep(Duration.ofMillis(0));
                assertTrue(false);
            } catch (InterruptedException e) {
                // expected
                assertFalse(me.isInterrupted());
            }
        });

        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.interrupt();
            try {
                Thread.sleep(Duration.ofMillis(1000));
                assertTrue(false);
            } catch (InterruptedException e) {
                // expected
                assertFalse(me.isInterrupted());
            }
        });
    }

    /**
     * Test Thread interrupt when sleeping.
     */
    @Test
    public void testSleep5() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            TestHelper.scheduleInterrupt(t, 2000);
            try {
                Thread.sleep(20*1000);
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be clearer
                assertFalse(t.isInterrupted());
            }
        });

        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            TestHelper.scheduleInterrupt(t, 2000);
            try {
                Thread.sleep(Duration.ofSeconds(20));
                assertTrue(false);
            } catch (InterruptedException e) {
                // interrupt status should be clearer
                assertFalse(t.isInterrupted());
            }
        });
    }

    /**
     * Test that Thread.sleep should not disrupt parking permit.
     */
    @Test
    public void testSleep6() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            LockSupport.unpark(Thread.currentThread());

            long start = millisTime();
            Thread.sleep(2000);
            expectDuration(start, /*min*/1900, /*max*/4000);

            // check that parking permit was not consumed
            LockSupport.park();
        });
    }

    /**
     * Test that Thread.sleep is not disrupted by unparking thread.
     */
    @Test
    public void testSleep7() throws Exception {
        AtomicReference<Exception> exc = new AtomicReference<>();
        var thread = Thread.ofVirtual().start(() -> {
            long start = millisTime();
            try {
                Thread.sleep(2000);
                long elapsed = millisTime() - start;
                if (elapsed < 1900) {
                    exc.set(new RuntimeException("sleep too short"));
                }
            } catch (InterruptedException e) {
                exc.set(e);
            }

        });
        // attempt to disrupt sleep
        for (int i=0; i<5; i++) {
            Thread.sleep(20);
            LockSupport.unpark(thread);
        }
        thread.join();
        Exception e = exc.get();
        if (e != null) {
            throw e;
        }
    }

    /**
     * Returns the current time in milliseconds.
     */
    private static long millisTime() {
        long now = System.nanoTime();
        return TimeUnit.MILLISECONDS.convert(now, TimeUnit.NANOSECONDS);
    }

    /**
     * Check the duration of a task
     * @param start start time, in milliseconds
     * @param min minimum expected duration, in milliseconds
     * @param max maximum expected duration, in milliseconds
     * @return the duration (now - start), in milliseconds
     */
    private static void expectDuration(long start, long min, long max) {
        long duration = millisTime() - start;
        assertTrue(duration >= min,
                "Duration " + duration + "ms, expected >= " + min + "ms");
        assertTrue(duration <= max,
                "Duration " + duration + "ms, expected <= " + max + "ms");
    }


    /**
     * Test Thread.xxxContextClassLoader from the current thread.
     */
    @Test
    public void testContextClassLoader1() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            t.setContextClassLoader(loader);
            assertTrue(t.getContextClassLoader() == loader);
        });
    }

    /**
     * Test inheriting initial value of TCCL from platform thread.
     */
    @Test
    public void testContextClassLoader2() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            TestHelper.runInVirtualThread(() -> {
                assertTrue(Thread.currentThread().getContextClassLoader() == loader);
            });
        } finally {
            t.setContextClassLoader(savedLoader);
        }
    }

    /**
     * Test inheriting initial value of TCCL from virtual thread.
     */
    @Test
    public void testContextClassLoader3() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ClassLoader loader = new ClassLoader() { };
            Thread.currentThread().setContextClassLoader(loader);
            TestHelper.runInVirtualThread(() -> {
                assertTrue(Thread.currentThread().getContextClassLoader() == loader);
            });
        });
    }

    /**
     * Test inheriting initial value of TCCL through an intermediate virtual thread.
     */
    @Test
    public void testContextClassLoader4() throws Exception {
        ClassLoader loader = new ClassLoader() { };
        Thread t = Thread.currentThread();
        ClassLoader savedLoader = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            TestHelper.runInVirtualThread(() -> {
                TestHelper.runInVirtualThread(() -> {
                    assertTrue(Thread.currentThread().getContextClassLoader() == loader);
                });
            });
        } finally {
            t.setContextClassLoader(savedLoader);
        }
    }

    /**
     * Test Thread.xxxContextClassLoader when thread locals not supported.
     */
    @Test
    public void testContextClassLoader5() throws Exception {
        ClassLoader scl = ClassLoader.getSystemClassLoader();
        ClassLoader loader = new ClassLoader() { };
        TestHelper.runInVirtualThread(TestHelper.NO_THREAD_LOCALS, () -> {
            Thread t = Thread.currentThread();
            assertTrue(t.getContextClassLoader() == scl);
            assertThrows(UnsupportedOperationException.class,
                         () -> t.setContextClassLoader(loader));
            assertTrue(t.getContextClassLoader() == scl);
        });
    }

    /**
     * Test Thread.xxxContextClassLoader when thread does not inherit the
     * initial value of inheritable thread locals.
     */
    @Test
    public void testContextClassLoader6() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ClassLoader loader = new ClassLoader() { };
            Thread.currentThread().setContextClassLoader(loader);
            int characteristics = TestHelper.NO_INHERIT_THREAD_LOCALS;
            TestHelper.runInVirtualThread(characteristics, () -> {
                Thread t = Thread.currentThread();
                assertTrue(t.getContextClassLoader() == ClassLoader.getSystemClassLoader());
                t.setContextClassLoader(loader);
                assertTrue(t.getContextClassLoader() == loader);
            });
        });
    }

    /**
     * Test Thread.setUncaughtExceptionHandler.
     */
    @Test
    public void testUncaughtExceptionHandler1() throws Exception {
        class FooException extends RuntimeException { }
        var exception = new AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler handler = (thread, exc) -> exception.set(exc);
        Thread thread = Thread.ofVirtual().start(() -> {
            Thread me = Thread.currentThread();
            assertTrue(me.getUncaughtExceptionHandler() == me.getThreadGroup());
            me.setUncaughtExceptionHandler(handler);
            assertTrue(me.getUncaughtExceptionHandler() == handler);
            throw new FooException();
        });
        thread.join();
        assertTrue(exception.get() instanceof FooException);
        assertTrue(thread.getUncaughtExceptionHandler() == null);
    }

    /**
     * Test default UncaughtExceptionHandler.
     */
    @Test
    public void testUncaughtExceptionHandler2() throws Exception {
        class FooException extends RuntimeException { }
        var exception = new AtomicReference<Throwable>();
        Thread.UncaughtExceptionHandler handler = (thread, exc) -> exception.set(exc);
        Thread.UncaughtExceptionHandler savedHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread thread;
        try {
            thread = Thread.ofVirtual().start(() -> {
                Thread me = Thread.currentThread();
                throw new FooException();
            });
            thread.join();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(savedHandler);
        }
        assertTrue(exception.get() instanceof FooException);
        assertTrue(thread.getUncaughtExceptionHandler() == null);
    }

    /**
     * Test no UncaughtExceptionHandler set.
     */
    @Test
    public void testUncaughtExceptionHandler3() throws Exception {
        class FooException extends RuntimeException { }
        Thread thread = Thread.ofVirtual().start(() -> {
            throw new FooException();
        });
        thread.join();
        assertTrue(thread.getUncaughtExceptionHandler() == null);
    }

    /**
     * Test Thread::getId.
     */
    @Test
    public void testGetId() throws Exception {
        var ref1 = new AtomicReference<Long>();
        var ref2 = new AtomicReference<Long>();
        TestHelper.runInVirtualThread(() -> ref1.set(Thread.currentThread().getId()));
        TestHelper.runInVirtualThread(() -> ref2.set(Thread.currentThread().getId()));
        long id1 = ref1.get();
        long id2 = ref2.get();
        long id3 = Thread.currentThread().getId();
        assertTrue(id1 != id2);
        assertTrue(id1 != id3);
        assertTrue(id2 != id3);
    }

    /**
     * Test Thread::getState when thread is not started.
     */
    @Test
    public void testGetState1() {
        var thread = Thread.ofVirtual().unstarted(() -> { });
        assertTrue(thread.getState() == Thread.State.NEW);
    }

    /**
     * Test Thread::getState when thread is runnable (mounted).
     */
    @Test
    public void testGetState2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread.State state = Thread.currentThread().getState();
            assertTrue(state == Thread.State.RUNNABLE);
        });
    }

    /**
     * Test Thread::getState when thread is runnable (not mounted).
     */
    @Test
    public void testGetState3() throws Exception {
        AtomicBoolean completed = new AtomicBoolean();
        try (ExecutorService scheduler = Executors.newFixedThreadPool(1)) {
            Thread.Builder.OfVirtual builder = Thread.ofVirtual().scheduler(scheduler);
            Thread t1 = builder.start(() -> {
                Thread t2 = builder.unstarted(LockSupport::park);
                assertTrue(t2.getState() == Thread.State.NEW);

                // runnable (not mounted)
                t2.start();
                assertTrue(t2.getState() == Thread.State.RUNNABLE);

                // yield to allow t2 to run and park
                Thread.yield();
                assertTrue(t2.getState() == Thread.State.WAITING);

                // unpark t2 and check runnable (not mounted)
                LockSupport.unpark(t2);
                assertTrue(t2.getState() == Thread.State.RUNNABLE);

                completed.set(true);
            });
            t1.join();
        }
        assertTrue(completed.get() == true);
    }

    /**
     * Test Thread::getState when thread is parked.
     */
    @Test
    public void testGetState4() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(thread);
        thread.join();
    }

    /**
     * Test Thread::getState when thread is parked while holding a monitor.
     */
    @Test
    public void testGetState5() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            var lock = new Object();
            synchronized (lock) {
                LockSupport.park();
            }
        });
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        LockSupport.unpark(thread);
        thread.join();
    }

    /**
     * Test Thread::getState when thread is waiting in Object.wait.
     */
    @Test
    public void testGetState6() throws Exception {
        var thread = Thread.ofVirtual().start(() -> {
            var lock = new Object();
            synchronized (lock) {
                try { lock.wait(); } catch (InterruptedException e) { }
            }
        });
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }
        thread.interrupt();
        thread.join();
    }

    /**
     * Test Thread::getState when thread is terminated.
     */
    @Test
    public void testGetState7() throws Exception {
        var thread = Thread.ofVirtual().start(() -> { });
        thread.join();
        assertTrue(thread.getState() == Thread.State.TERMINATED);
    }

    /**
     * Test Thread::isAlive.
     */
    @Test
    public void testIsAlive1() throws Exception {
        // unstarted
        var thread = Thread.ofVirtual().unstarted(LockSupport::park);
        assertFalse(thread.isAlive());

        // started
        thread.start();
        try {
            assertTrue(thread.isAlive());
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }

        // terminated
        assertFalse(thread.isAlive());
    }

    /**
     * Test Thread.holdLock when lock not held.
     */
    @Test
    public void testHoldsLock1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var lock = new Object();
            assertFalse(Thread.holdsLock(lock));
        });
    }

    /**
     * Test Thread.holdLock when lock held.
     */
    @Test
    public void testHoldsLock2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            var lock = new Object();
            synchronized (lock) {
                assertTrue(Thread.holdsLock(lock));
            }
        });
    }

    /**
     * Test Thread::getStackTrace on unstarted thread.
     */
    @Test
    public void testGetStackTrace1() {
        var thread = Thread.ofVirtual().unstarted(() -> { });
        StackTraceElement[] stack = thread.getStackTrace();
        assertTrue(stack.length == 0);
    }

    /**
     * Test Thread::getStackTrace on thread that has been started but
     * has not run.
     */
    @Test
    public void testGetStackTrace2() throws Exception {
        List<Thread> threads = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean();
        try {
            Thread target = null;

            // start virtual threads that are CPU bound until we find
            // a thread that does not run
            while (target == null) {
                CountDownLatch latch = new CountDownLatch(1);
                Thread vthread = Thread.ofVirtual().start(() -> {
                    latch.countDown();
                    while (!done.get()) { }
                });
                threads.add(vthread);
                if (!latch.await(3, TimeUnit.SECONDS)) {
                    // thread did not run
                    target = vthread;
                }
            }

            // stack trace should be empty
            StackTraceElement[] stack = target.getStackTrace();
            assertTrue(stack.length == 0);
        } finally {
            done.set(true);

            // wait for threads to terminate
            for (Thread thread : threads) {
                thread.join();
            }
        }
    }

    /**
     * Test Thread::getStackTrace on running thread.
     */
    @Test
    public void testGetStackTrace3() throws Exception {
        var sel = Selector.open();
        var thread = Thread.ofVirtual().start(() -> {
            try { sel.select(); } catch (Exception e) { }
        });
        Thread.sleep(200);  // give him for thread to block
        try {
            assertTrue(thread.getState() == Thread.State.RUNNABLE);
            StackTraceElement[] stack = thread.getStackTrace();
            assertTrue(contains(stack, "select"));
        } finally {
            sel.close();
            thread.join();
        }
    }

    /**
     * Test Thread::getStackTrace on running thread waiting in Object.wait.
     */
    @Test
    public void testGetStackTrace4() throws Exception {
        try (ForkJoinPool pool = new ForkJoinPool(1)) {
            AtomicReference<Thread> ref = new AtomicReference<>();
            Executor scheduler = task -> {
                pool.submit(() -> {
                    ref.set(Thread.currentThread());
                    task.run();
                });
            };

            Object lock = new Object();
            Thread vthread = Thread.ofVirtual().scheduler(scheduler).start(() -> {
                synchronized (lock) {
                    try {
                        lock.wait();
                    } catch (Exception e) { }
                }
            });

            // get carrier Thread
            Thread carrier;
            while ((carrier = ref.get()) == null) {
                Thread.sleep(20);
            }

            // wait for virtual thread to block in wait
            while (vthread.getState() != Thread.State.WAITING) {
                Thread.sleep(20);
            }

            // get stack trace of both carrier and virtual thread
            StackTraceElement[] carrierStackTrace = carrier.getStackTrace();
            StackTraceElement[] vthreadStackTrace = vthread.getStackTrace();

            // allow virtual thread to terminate
            synchronized (lock) {
                lock.notifyAll();
            }

            // check carrier thread's stack trace
            assertTrue(contains(carrierStackTrace, "java.util.concurrent.ForkJoinPool.runWorker"));
            assertFalse(contains(carrierStackTrace, "java.lang.Object.wait"));

            // check virtual thread's stack trace
            assertFalse(contains(vthreadStackTrace, "java.util.concurrent.ForkJoinPool.runWorker"));
            assertTrue(contains(vthreadStackTrace, "java.lang.Object.wait"));
        }
    }

    /**
     * Test Thread::getStackTrace on parked thread.
     */
    @Test
    public void testGetStackTrace5() throws Exception {
        var thread = Thread.ofVirtual().start(LockSupport::park);

        // wait for thread to park
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(20);
        }

        try {
            StackTraceElement[] stack = thread.getStackTrace();
            assertTrue(contains(stack, "LockSupport.park"));
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test Thread::getStackTrace on terminated thread.
     */
    @Test
    public void testGetStackTrace6() throws Exception {
        var thread = Thread.ofVirtual().start(() -> { });
        thread.join();
        StackTraceElement[] stack = thread.getStackTrace();
        assertTrue(stack.length == 0);
    }

    /**
     * Test that Thread.getAllStackTraces does not include virtual threads.
     */
    @Test
    public void testGetAllStackTraces1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Set<Thread> threads = Thread.getAllStackTraces().keySet();
            assertFalse(threads.stream().anyMatch(Thread::isVirtual));
        });
    }

    /**
     * Test that Thread.getAllStackTraces includes carrier threads.
     */
    @Test
    public void testGetAllStackTraces2() throws Exception {
        try (ForkJoinPool pool = new ForkJoinPool(1)) {
            AtomicReference<Thread> ref = new AtomicReference<>();
            Executor scheduler = task -> {
                pool.submit(() -> {
                    ref.set(Thread.currentThread());
                    task.run();
                });
            };

            Object lock = new Object();
            Thread vthread = Thread.ofVirtual().scheduler(scheduler).start(() -> {
                synchronized (lock) {
                    try {
                        lock.wait();
                    } catch (Exception e) { }
                }
            });

            // get carrier Thread
            Thread carrier;
            while ((carrier = ref.get()) == null) {
                Thread.sleep(20);
            }

            // wait for virtual thread to block in wait
            while (vthread.getState() != Thread.State.WAITING) {
                Thread.sleep(20);
            }

            // get all stack traces
            Map<Thread, StackTraceElement[]> map = Thread.getAllStackTraces();

            // allow virtual thread to terminate
            synchronized (lock) {
                lock.notifyAll();
            }

            // get stack trace for the carrier thread
            StackTraceElement[] stackTrace = map.get(carrier);
            assertTrue(stackTrace != null);
            assertTrue(contains(stackTrace, "java.util.concurrent.ForkJoinPool"));
            assertFalse(contains(stackTrace, "java.lang.Object.wait"));

            // there should be no stack trace for the virtual thread
            assertTrue(map.get(vthread) == null);
        }
    }

    private boolean contains(StackTraceElement[] stack, String expected) {
        return Stream.of(stack)
                .map(Object::toString)
                .anyMatch(s -> s.contains(expected));
    }


    /**
     * Test Thread::getThreadGroup of virtual thread created by platform thread.
     */
    @Test
    public void testThreadGroup1() throws Exception {
        var thread = Thread.ofVirtual().unstarted(LockSupport::park);
        var vgroup = thread.getThreadGroup();
        thread.start();
        try {
            assertTrue(thread.getThreadGroup() == vgroup);
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
        assertTrue(thread.getThreadGroup() == null);
    }

    /**
     * Test Thread::getThreadGroup of platform thread created by virtual thread.
     */
    @Test
    public void testThreadGroup2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ThreadGroup vgroup = Thread.currentThread().getThreadGroup();
            Thread child = new Thread(() -> { });
            ThreadGroup group = child.getThreadGroup();
            assertTrue(group == vgroup);
        });
    }

    /**
     * Test Thread.enumerate(false).
     */
    @Test
    public void testEnumerate1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ThreadGroup vgroup = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[100];
            int n = vgroup.enumerate(threads, /*recurse*/false);
            assertTrue(n == 0);
        });
    }

    /**
     * Test Thread.enumerate(true).
     */
    @Test
    public void testEnumerate2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            ThreadGroup vgroup = Thread.currentThread().getThreadGroup();
            Thread[] threads = new Thread[100];
            int n = vgroup.enumerate(threads, /*recurse*/true);
            assertFalse(Arrays.stream(threads, 0, n).anyMatch(Thread::isVirtual));
        });
    }

    /**
     * Test toString on unstarted thread.
     */
    @Test
    public void testToString1() {
        Thread thread = Thread.ofVirtual().unstarted(() -> { });
        thread.setName("fred");
        assertTrue(thread.toString().contains("fred"));
    }

    /**
     * Test toString on running thread.
     */
    @Test
    public void testToString2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread me = Thread.currentThread();
            me.setName("fred");
            assertTrue(me.toString().contains("fred"));
        });
    }

    /**
     * Test toString on parked thread.
     */
    @Test
    public void testToString3() throws Exception {
        Thread thread = Thread.ofVirtual().start(() -> {
            Thread me = Thread.currentThread();
            me.setName("fred");
            LockSupport.park();
        });
        while (thread.getState() != Thread.State.WAITING) {
            Thread.sleep(10);
        }
        try {
            assertTrue(thread.toString().contains("fred"));
        } finally {
            LockSupport.unpark(thread);
            thread.join();
        }
    }

    /**
     * Test toString on terminated thread.
     */
    @Test
    public void testToString4() throws Exception {
        Thread thread = Thread.ofVirtual().start(() -> {
            Thread me = Thread.currentThread();
            me.setName("fred");
        });
        thread.join();
        assertTrue(thread.toString().contains("fred"));
    }
}

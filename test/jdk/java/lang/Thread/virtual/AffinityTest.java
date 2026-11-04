/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test Thread.Builder.OfVirtual.affinity and stickyAffinity placement rules
 * @requires vm.continuations
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm AffinityTest
 */

/*
 * @test id=mpsc
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.parallelism=4 AffinityTest
 */

/*
 * @test id=mpsc-steal
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.mpscSteal=true
 *                    -Djdk.virtualThreadScheduler.parallelism=4 AffinityTest
 */

/*
 * @test id=mpsc-steal-mode4
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.mpscSteal=true
 *                    -Djdk.pollerMode=4
 *                    -Djdk.virtualThreadScheduler.parallelism=4 AffinityTest
 */

/*
 * @test id=mpsc-mode2
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.pollerMode=2
 *                    -Djdk.virtualThreadScheduler.parallelism=4 AffinityTest
 */

/*
 * @test id=mpsc-steal-mode3
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 *          java.base/sun.nio.ch:+open
 * @library /test/lib
 * @run junit/othervm -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.mpscSteal=true
 *                    -Djdk.pollerMode=3
 *                    -Djdk.virtualThreadScheduler.parallelism=4 AffinityTest
 */

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadScheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class AffinityTest {
    private static final boolean MPSC = Boolean.getBoolean("jdk.virtualThreadScheduler.useMpsc");
    private static final boolean STEAL = Boolean.getBoolean("jdk.virtualThreadScheduler.mpscSteal");
    private static final int PARALLELISM = Integer.getInteger("jdk.virtualThreadScheduler.parallelism", 0);

    private static Thread carrier() {
        return VThreadScheduler.currentCarrierThread();
    }

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /**
     * Test the poller mode: with MPSC, Mode 4 (CARRIER_LOCAL_POLLER) when jdk.pollerMode
     * is not set or is 4, else the requested mode; never Mode 4 on another scheduler.
     */
    @Test
    void testPollerMode() throws Exception {
        // a virtual thread first, so the scheduler exists; Class.forName initializes
        // Poller, which creates the poller group
        Thread.ofVirtual().start(() -> { }).join();
        Class<?> poller = Class.forName("sun.nio.ch.Poller");
        Field groupField = poller.getDeclaredField("POLLER_GROUP");
        groupField.setAccessible(true);
        Object group = groupField.get(null);
        Method provider = Class.forName("sun.nio.ch.Poller$PollerGroup").getDeclaredMethod("provider");
        provider.setAccessible(true);
        Method pollerMode = Class.forName("sun.nio.ch.PollerProvider").getDeclaredMethod("pollerMode");
        pollerMode.setAccessible(true);
        String mode = ((Enum<?>) pollerMode.invoke(provider.invoke(group))).name();

        String requested = System.getProperty("jdk.pollerMode");
        if (!MPSC) {
            assertNotEquals("CARRIER_LOCAL_POLLER", mode);
            return;
        }
        String expected = switch (requested == null ? "4" : requested) {
            case "1" -> "SYSTEM_THREADS";
            case "2" -> "VTHREAD_POLLERS";
            case "3" -> "POLLER_PER_CARRIER";
            case "4" -> "CARRIER_LOCAL_POLLER";
            default -> throw new IllegalStateException("jdk.pollerMode=" + requested);
        };
        assertEquals(expected, mode, "jdk.pollerMode=" + requested);
    }

    /**
     * Test the API contract on every scheduler: null is rejected, the builder and the
     * factory create runnable threads, and each draw from a group is a new affinity.
     */
    @Test
    void testApi() throws Exception {
        Thread.Builder.OfVirtual builder = Thread.ofVirtual();
        assertThrows(NullPointerException.class, () -> builder.affinity((Thread.Affinity) null));
        assertThrows(NullPointerException.class, () -> builder.affinity((Thread.Affinity.Group) null));

        Thread.Affinity.Group group = Thread.Affinity.newGroup();
        assertNotSame(group, Thread.Affinity.newGroup());
        Thread.Affinity a = group.newAffinity();
        assertNotSame(a, group.newAffinity());

        var ran = new AtomicInteger();
        Thread.ofVirtual().affinity(a).start(ran::incrementAndGet).join();
        Thread.ofVirtual().affinity(group).start(ran::incrementAndGet).join();
        Thread t = Thread.ofVirtual().affinity(a).stickyAffinity().factory().newThread(ran::incrementAndGet);
        t.start();
        t.join();
        assertEquals(3, ran.get());
    }

    /**
     * Test that a custom scheduler sees each thread's affinity through its task: the
     * builder's affinity, a new one per thread from a group, or none.
     */
    @Test
    void testCustomSchedulerSeesAffinity() throws Exception {
        assumeTrue(VThreadScheduler.supportsCustomScheduler(), "No support for custom schedulers");
        var seen = new ConcurrentHashMap<Thread, Optional<Thread.Affinity>>();
        Thread.VirtualThreadScheduler scheduler = new Thread.VirtualThreadScheduler() {
            private void submit(Thread.VirtualThreadTask task) {
                seen.putIfAbsent(task.thread(), Optional.ofNullable(task.affinity()));
                Thread.ofPlatform().start(task);
            }
            @Override
            public void onStart(Thread.VirtualThreadTask task) {
                submit(task);
            }
            @Override
            public void onContinue(Thread.VirtualThreadTask task) {
                submit(task);
            }
        };
        Thread.Affinity.Group group = Thread.Affinity.newGroup();
        Thread.Affinity a = group.newAffinity();

        Thread t1 = VThreadScheduler.virtualThreadBuilder(scheduler).affinity(a).start(() -> { });
        Thread t2 = VThreadScheduler.virtualThreadBuilder(scheduler).affinity(a).start(() -> { });
        ThreadFactory spread = VThreadScheduler.virtualThreadBuilder(scheduler).affinity(group).factory();
        Thread t3 = spread.newThread(() -> { });
        Thread t4 = spread.newThread(() -> { });
        t3.start();
        t4.start();
        Thread t5 = VThreadScheduler.virtualThreadBuilder(scheduler).start(() -> { });
        for (Thread t : List.of(t1, t2, t3, t4, t5)) {
            t.join();
        }
        assertSame(a, seen.get(t1).orElseThrow());
        assertSame(a, seen.get(t2).orElseThrow());
        Thread.Affinity a3 = seen.get(t3).orElseThrow();
        Thread.Affinity a4 = seen.get(t4).orElseThrow();
        assertNotSame(a3, a4);
        assertNotSame(a, a3);
        assertTrue(seen.get(t5).isEmpty());
    }

    /**
     * Test that threads sharing an affinity all run on one carrier for their whole life,
     * across yields and wakeups from other carriers (MPSC only).
     */
    @Test
    void testColocate() throws Exception {
        assumeTrue(MPSC, "MPSC scheduler only");
        Thread.Affinity home = Thread.Affinity.newGroup().newAffinity();
        Set<Thread> carriers = ConcurrentHashMap.newKeySet();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().affinity(home).start(() -> {
                for (int y = 0; y < 200; y++) {
                    carriers.add(carrier());
                    spin(20_000);
                    Thread.yield();
                }
            }));
        }
        // stealable load around them
        Thread load = Thread.ofVirtual().start(() -> {
            var kids = new ArrayList<Thread>();
            for (int i = 0; i < 32; i++) {
                kids.add(Thread.ofVirtual().start(() -> spin(2_000_000)));
            }
            for (Thread k : kids) {
                try { k.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        });
        for (Thread t : threads) {
            t.join();
        }
        load.join();
        assertEquals(1, carriers.size(), "carriers used: " + carriers);
    }

    /**
     * Test that the threads of a group factory are spread, one per carrier, and that
     * each stays on its carrier (MPSC only).
     */
    @Test
    void testSpread() throws Exception {
        assumeTrue(MPSC && PARALLELISM > 0, "MPSC scheduler with a set parallelism only");
        ThreadFactory factory = Thread.ofVirtual().affinity(Thread.Affinity.newGroup()).factory();
        Set<Thread> first = ConcurrentHashMap.newKeySet();
        AtomicInteger moved = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < PARALLELISM; i++) {
            Thread t = factory.newThread(() -> {
                Thread c = carrier();
                first.add(c);
                for (int y = 0; y < 200; y++) {
                    spin(20_000);
                    Thread.yield();
                    if (carrier() != c) {
                        moved.incrementAndGet();
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(PARALLELISM, first.size(), "carriers used: " + first);
        assertEquals(0, moved.get());
    }

    /**
     * Test that a parked thread without an affinity, unparked by a sticky thread on
     * another carrier, resumes on the sticky thread's carrier, unless stealing lets
     * another carrier take it (MPSC only; the FJP behaviour is covered by
     * StickyAffinityTest).
     */
    @Test
    void testStickyWakeup() throws Exception {
        assumeTrue(MPSC && PARALLELISM >= 2, "MPSC scheduler with parallelism >= 2 only");
        Thread.Affinity.Group group = Thread.Affinity.newGroup();
        Thread.Affinity a = group.newAffinity();
        Thread.Affinity b = group.newAffinity();
        int trials = 20, counted = 0, local = 0;
        for (int trial = 0; trial < trials; trial++) {
            var before = new AtomicReference<Thread>();
            var after = new AtomicReference<Thread>();
            var waker = new AtomicReference<Thread>();
            var target = new AtomicReference<Thread>();
            // the starter, placed at a, starts the target on a's carrier; the target has
            // no affinity of its own
            Thread.ofVirtual().affinity(a).start(() -> {
                target.set(Thread.ofVirtual().start(() -> {
                    before.set(carrier());
                    while (waker.get() == null) {
                        LockSupport.park();
                    }
                    after.set(carrier());
                }));
            }).join();
            while (target.get().getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            Thread.ofVirtual().affinity(b).stickyAffinity().start(() -> {
                waker.set(carrier());
                LockSupport.unpark(target.get());
            }).join();
            target.get().join();
            // with stealing, the target may have been taken by another carrier before it
            // first ran, or before it resumed: sticky work stays stealable and the
            // unpark signals, so where it resumes is not specified
            if (before.get() != waker.get()) {
                counted++;
                if (after.get() == waker.get()) {
                    local++;
                }
            }
        }
        assertTrue(counted > 0);
        if (!STEAL) {
            assertEquals(counted, local);
        }
    }

    /**
     * Test that placement beats sticky: a thread with an affinity, unparked by a sticky
     * thread on another carrier, resumes on its own carrier (MPSC only).
     */
    @Test
    void testPlacementBeatsSticky() throws Exception {
        assumeTrue(MPSC && PARALLELISM >= 2, "MPSC scheduler with parallelism >= 2 only");
        Thread.Affinity.Group group = Thread.Affinity.newGroup();
        Thread.Affinity a = group.newAffinity();
        Thread.Affinity b = group.newAffinity();
        for (int trial = 0; trial < 20; trial++) {
            var before = new AtomicReference<Thread>();
            var after = new AtomicReference<Thread>();
            var woken = new CountDownLatch(1);
            Thread target = Thread.ofVirtual().affinity(a).start(() -> {
                before.set(carrier());
                while (woken.getCount() > 0) {
                    LockSupport.park();
                }
                after.set(carrier());
            });
            while (target.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            Thread.ofVirtual().affinity(b).stickyAffinity().start(() -> {
                woken.countDown();
                LockSupport.unpark(target);
            }).join();
            target.join();
            assertSame(before.get(), after.get(), "trial " + trial);
        }
    }
}

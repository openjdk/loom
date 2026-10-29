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
 * @test id=mode4
 * @summary Thread.yield and Thread.sleep(0) on the MPSC scheduler, with the
 *          scheduler's assertions enabled: placed and unplaced yielders, mixed
 *          with parking, I/O readiness and pinned yields
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm/native -esa --enable-native-access=ALL-UNNAMED
 *                    -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.pollerMode=4
 *                    -Djdk.virtualThreadScheduler.parallelism=4 MpscYieldTest
 */

/*
 * @test id=mode4-steal
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm/native -esa --enable-native-access=ALL-UNNAMED
 *                    -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.mpscSteal=true
 *                    -Djdk.pollerMode=4
 *                    -Djdk.virtualThreadScheduler.parallelism=4 MpscYieldTest
 */

/*
 * @test id=mode4-one-carrier
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm/native -esa --enable-native-access=ALL-UNNAMED
 *                    -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.pollerMode=4
 *                    -Djdk.virtualThreadScheduler.parallelism=1 MpscYieldTest
 */

/*
 * @test id=mode3-steal
 * @requires vm.continuations & os.family == "linux"
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit/othervm/native -esa --enable-native-access=ALL-UNNAMED
 *                    -Djdk.virtualThreadScheduler.useMpsc=true
 *                    -Djdk.virtualThreadScheduler.mpscSteal=true
 *                    -Djdk.pollerMode=3
 *                    -Djdk.virtualThreadScheduler.parallelism=4 MpscYieldTest
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import jdk.test.lib.thread.VThreadPinner;
import jdk.test.lib.thread.VThreadScheduler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MpscYieldTest {
    private static final int YIELDS = 2_000;

    @BeforeAll
    static void assertionsEnabled() throws Exception {
        // the scheduler's own asserts are what this test exercises
        Class<?> c = Class.forName("java.lang.MpscVirtualThreadScheduler");
        assertTrue(c.desiredAssertionStatus(), "run with -esa");
    }

    private static void yieldOnce(int i) {
        if ((i & 1) == 0) {
            Thread.yield();
        } else {
            try {
                Thread.sleep(0);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join();
        }
    }

    /**
     * Placed yielders sharing one affinity complete, each yield returns, and all of
     * them stay on one carrier.
     */
    @Test
    void testPlacedYielders() throws Exception {
        Thread.Affinity home = Thread.Affinity.newGroup().newAffinity();
        Set<Thread> carriers = ConcurrentHashMap.newKeySet();
        AtomicInteger yields = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int k = 0; k < 8; k++) {
            threads.add(Thread.ofVirtual().affinity(home).start(() -> {
                for (int i = 0; i < YIELDS; i++) {
                    carriers.add(VThreadScheduler.currentCarrierThread());
                    yieldOnce(i);
                    yields.incrementAndGet();
                }
            }));
        }
        joinAll(threads);
        assertEquals(8 * YIELDS, yields.get());
        assertEquals(1, carriers.size(), "carriers used: " + carriers);
    }

    /**
     * Unplaced yielders, more than there are carriers, complete; a lone unplaced
     * yielder too.
     */
    @Test
    void testUnplacedYielders() throws Exception {
        AtomicInteger yields = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int k = 0; k < 16; k++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < YIELDS; i++) {
                    yieldOnce(i);
                    yields.incrementAndGet();
                }
            }));
        }
        joinAll(threads);
        assertEquals(16 * YIELDS, yields.get());

        Thread lone = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < YIELDS; i++) {
                yieldOnce(i);
            }
        });
        lone.join();
    }

    /**
     * Placed, unplaced and sticky yielders that also park and unpark one another, so
     * that yields, park races, sticky wakeups and wakeups from other carriers
     * interleave.
     */
    @Test
    void testYieldAndPark() throws Exception {
        Thread.Affinity home = Thread.Affinity.newGroup().newAffinity();
        int n = 6;
        Thread[] ring = new Thread[n];
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        for (int k = 0; k < n; k++) {
            int me = k;
            // placed, plain, and sticky (its unparks go through submitLocal)
            Thread.Builder.OfVirtual b = switch (k % 3) {
                case 0 -> Thread.ofVirtual().affinity(home);
                case 1 -> Thread.ofVirtual();
                default -> Thread.ofVirtual().stickyAffinity();
            };
            ring[k] = b.unstarted(() -> {
                for (int i = 0; i < YIELDS; i++) {
                    yieldOnce(i);
                    LockSupport.unpark(ring[(me + 1) % n]);
                    if ((i % 7) == 0) {
                        LockSupport.parkNanos(10_000);
                    }
                }
                done.incrementAndGet();
            });
        }
        for (Thread t : ring) {
            t.start();
        }
        // a platform thread unparks them too, from outside every carrier
        Thread unparker = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                for (Thread t : ring) {
                    LockSupport.unpark(t);
                }
                Thread.onSpinWait();
            }
        });
        try {
            for (Thread t : ring) {
                t.join();
            }
        } finally {
            stop.set(true);
            unparker.join();
        }
        assertEquals(n, done.get());
    }

    /**
     * A reader placed with yielders on one carrier receives every message written
     * from a platform thread: I/O readiness and yields interleave on that carrier.
     */
    @Test
    void testReaderBehindYielders() throws Exception {
        Thread.Affinity home = Thread.Affinity.newGroup().newAffinity();
        int messages = 200;
        try (ServerSocket listener = new ServerSocket()) {
            InetAddress lb = InetAddress.getLoopbackAddress();
            listener.bind(new InetSocketAddress(lb, 0));
            try (Socket writerSide = new Socket(lb, listener.getLocalPort());
                 Socket readerSide = listener.accept()) {
                AtomicBoolean stop = new AtomicBoolean();
                AtomicInteger received = new AtomicInteger();
                var yielders = new ArrayList<Thread>();
                for (int k = 0; k < 4; k++) {
                    yielders.add(Thread.ofVirtual().affinity(home).start(() -> {
                        int i = 0;
                        while (!stop.get()) {
                            yieldOnce(i++);
                        }
                    }));
                }
                Thread reader = Thread.ofVirtual().affinity(home).start(() -> {
                    try {
                        InputStream in = readerSide.getInputStream();
                        while (received.get() < messages) {
                            if (in.read() < 0) {
                                break;
                            }
                            received.incrementAndGet();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                OutputStream out = writerSide.getOutputStream();
                for (int i = 0; i < messages; i++) {
                    out.write(i);
                    out.flush();
                    Thread.sleep(1);
                }
                reader.join();
                stop.set(true);
                joinAll(yielders);
                assertEquals(messages, received.get());
            }
        }
    }

    /**
     * A yield while pinned (a native frame on the stack) does not unmount and
     * returns; a yield while holding a monitor, and the yields around both, return.
     */
    @Test
    void testPinnedYield() throws Exception {
        Object lock = new Object();
        Thread.Affinity home = Thread.Affinity.newGroup().newAffinity();
        for (Thread.Builder.OfVirtual b : List.of(Thread.ofVirtual(), Thread.ofVirtual().affinity(home))) {
            Thread t = b.start(() -> {
                for (int i = 0; i < 100; i++) {
                    int n = i;
                    Thread before = VThreadScheduler.currentCarrierThread();
                    VThreadPinner.runPinned(() -> yieldOnce(n));
                    assertSame(before, VThreadScheduler.currentCarrierThread());
                    synchronized (lock) {
                        yieldOnce(i);
                    }
                    yieldOnce(i);
                }
            });
            t.join();
        }
    }
}

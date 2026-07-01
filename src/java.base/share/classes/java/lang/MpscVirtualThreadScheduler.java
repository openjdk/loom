/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.Thread.VirtualThreadScheduler;
import java.lang.Thread.VirtualThreadTask;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Contended;
import sun.nio.ch.CarrierLocalPoller;

/**
 * An alternative virtual thread scheduler using a single MPSC queue per carrier.
 * No work stealing — each carrier drains only its own queue.
 *
 * <p>External submissions use a probe-based hash (FJP-style) to distribute
 * across carriers. Carrier affinity is set once at start via affinityHint;
 * onContinue always routes back to the same carrier.
 *
 * <p>With poller Mode 4 (CARRIER_LOCAL_POLLER), each carrier owns its own
 * epoll fd. VT fds register directly — no sub-pollers, no master poller.
 * The carrier interleaves task draining with I/O polling.
 */
final class MpscVirtualThreadScheduler implements VirtualThreadScheduler {

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long PROBE =
            U.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

    private final CarrierThread[] carriers;

    MpscVirtualThreadScheduler(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.carriers = new CarrierThread[parallelism];
        for (int i = 0; i < parallelism; i++) {
            carriers[i] = new CarrierThread(i, this);
        }
        for (int i = 0; i < parallelism; i++) {
            carriers[i].start();
        }
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        VirtualThread vt = (VirtualThread) task.thread();
        CarrierThread target;
        if (vt.affinityHint >= 0) {
            target = carriers[Math.floorMod(vt.affinityHint, carriers.length)];
        } else {
            target = carrierFor();
        }
        vt.affinityHint = target.id;
        enqueue(target, task);
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        int hint = ((VirtualThread) task.thread()).affinityHint;
        if (hint >= 0 && hint < carriers.length) {
            enqueue(carriers[hint], task);
            return;
        }
        onStart(task);
    }

    private static void enqueue(CarrierThread carrier, VirtualThreadTask task) {
        carrier.queue.offer(task);
        if (carrier.carrierState == CarrierThread.PARKED) {
            if (carrier.poller != null) {
                try {
                    carrier.poller.wakeup();
                } catch (IOException e) {
                    LockSupport.unpark(carrier);
                }
            } else {
                LockSupport.unpark(carrier);
            }
        }
    }

    private CarrierThread carrierFor() {
        Thread caller = Thread.currentCarrierThread();
        if (caller instanceof CarrierThread ct && ct.scheduler == this) {
            return ct;
        }
        return carriers[Math.floorMod(probe(), carriers.length)];
    }

    private static int probe() {
        int p = U.getInt(Thread.currentThread(), PROBE);
        if (p == 0) {
            long tid = Thread.currentThread().threadId();
            p = (int) (tid ^ (tid >>> 16));
            if (p == 0) p = 1;
            U.putInt(Thread.currentThread(), PROBE, p);
        }
        return p;
    }

    // ---- Carrier thread ----

    static final class CarrierThread extends Thread {
        static final int RUNNING = 0;
        static final int PARKED  = 1;

        final int id;
        final MpscUnboundedQueue<VirtualThreadTask> queue = new MpscUnboundedQueue<>(64);
        final MpscVirtualThreadScheduler scheduler;

        // carrier-local poller (Mode 4), null if using Mode 3 or lower
        CarrierLocalPoller poller;

        @Contended
        volatile int carrierState;

        CarrierThread(int id, MpscVirtualThreadScheduler scheduler) {
            super(null, null, "mpsc-carrier-" + id, 0, false);
            this.id = id;
            this.scheduler = scheduler;
            setDaemon(true);
        }

        @Override
        public void run() {
            if ("4".equals(System.getProperty("jdk.pollerMode"))) {
                try {
                    this.poller = new CarrierLocalPoller();
                    eventLoop();
                    return;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            plainLoop();
        }

        /**
         * Mode 4: interleave task draining with I/O polling.
         * Like Netty's EventLoop: drain tasks → poll I/O → drain → ...
         * Block in epoll_wait only when both queue and I/O are idle.
         */
        private static final long DRAIN_BUDGET_NS = java.util.concurrent.TimeUnit.MICROSECONDS
                .toNanos(Integer.getInteger("jdk.virtualThreadScheduler.drainBudgetUs", 50));
        private static final int TIME_CHECK_INTERVAL = 4;

        private void eventLoop() {
            var queue = this.queue;
            var poller = this.poller;
            boolean ioActive = false;
            for (;;) {
                // drain tasks with time budget
                int drained = 0;
                long drainStart = System.nanoTime();
                VirtualThreadTask task;
                while ((task = queue.poll()) != null) {
                    try { task.run(); } catch (Throwable t) { }
                    drained++;
                    if ((drained & (TIME_CHECK_INTERVAL - 1)) == 0
                            && System.nanoTime() - drainStart >= DRAIN_BUDGET_NS) {
                        break;
                    }
                }

                // non-blocking I/O poll
                int ioEvents = 0;
                try {
                    ioEvents = poller.poll(0);
                } catch (IOException e) { }

                if (drained + ioEvents > 0) {
                    ioActive = true;
                    continue;
                }

                // nothing happened
                if (ioActive) {
                    ioActive = false;
                    continue;
                }

                // genuinely idle: blocking poll
                carrierState = PARKED;

                if ((task = queue.poll()) != null) {
                    carrierState = RUNNING;
                    try { task.run(); } catch (Throwable t) { }
                    ioActive = true;
                    continue;
                }

                try {
                    poller.poll(-1);
                } catch (IOException e) { }
                carrierState = RUNNING;
                ioActive = true;
            }
        }

        /**
         * Plain loop (Mode 3 or lower): poll tasks, park when idle.
         */
        private void plainLoop() {
            var queue = this.queue;
            for (;;) {
                VirtualThreadTask task = queue.poll();
                if (task != null) {
                    try { task.run(); } catch (Throwable t) { }
                    continue;
                }

                carrierState = PARKED;

                if ((task = queue.poll()) != null) {
                    carrierState = RUNNING;
                    try { task.run(); } catch (Throwable t) { }
                    continue;
                }

                LockSupport.park();
                carrierState = RUNNING;
            }
        }
    }

    @Override
    public String toString() {
        return "MpscVirtualThreadScheduler[carriers=" + carriers.length + "]";
    }
}

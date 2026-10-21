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
import jdk.internal.vm.annotation.Contended;
import sun.nio.ch.CarrierLocalPoller;

/**
 * An alternative virtual thread scheduler using a single MPSC queue per carrier.
 * No work stealing — each carrier drains only its own queue.
 *
 * <p>External submissions use a per-thread probe: each submitting platform
 * thread targets one carrier for its lifetime, and different threads are spread
 * across carriers. Carrier affinity is set once at start via affinityHint;
 * onContinue always routes back to the same carrier.
 *
 * <p>With poller Mode 4 (CARRIER_LOCAL_POLLER), each carrier owns its own
 * epoll fd. VT fds register directly — no sub-pollers, no master poller.
 * The carrier interleaves task draining with I/O polling.
 */
final class MpscVirtualThreadScheduler implements VirtualThreadScheduler {

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
            // A virtual thread never runs while its carrier's state reads PARKED: the
            // carrier stores RUNNING before it executes anything. So an enqueue that
            // observes PARKED from the carrier's own thread can only originate inside the
            // carrier's blocking poll -- in Mode 4, a readiness unpark from
            // CarrierLocalPoller.poll(-1) -- and the carrier unconditionally stores
            // RUNNING and drains the queue when that poll returns. Signalling it would
            // cost an eventfd write, and a later spurious wakeup, for a carrier already
            // on its way back to the drain loop.
            if (Thread.currentCarrierThread() == carrier) {
                return;
            }
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

    /**
     * Hands out the starting value of each platform thread's probe, so that different
     * submitting threads start on different carriers. A global sequence rather than
     * anything derived from the thread, for the reason ThreadLocalRandom.localInit()
     * uses one: thread ids are sequential, and a hash of a handful of them spreads
     * poorly over a small carrier count.
     */
    private static final java.util.concurrent.atomic.AtomicInteger PROBE_SEEDER =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Returns the carrier index for external submissions from the calling platform
     * thread. Stable for the life of the thread: every virtual thread it starts goes to
     * the same carrier, and different threads are spread by the seeder.
     *
     * Stability is deliberate. A thread submitting a burst wakes its target carrier
     * once and then finds it RUNNING for the rest of the burst, so the burst costs one
     * eventfd write. Rotating the index instead sends consecutive tasks to different
     * carriers, each of which drains its share and re-parks before its next task
     * arrives, so nearly every submission finds a parked carrier and pays a wakeup.
     * This is the same reason ForkJoinPool keeps its probe stable and advances it only
     * on a failed queue acquisition. Callers that need their threads spread ask for it
     * with roundRobinAffinity(), which assigns carriers at the factory and does not
     * come through here.
     *
     * Only platform threads reach here: a submission from a virtual thread takes the
     * calling-carrier path in carrierFor().
     */
    private static int probe() {
        Thread t = Thread.currentThread();
        int p = t.mpscProbe;
        if (p == 0) {
            p = PROBE_SEEDER.incrementAndGet();
            t.mpscProbe = p;
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
        private void eventLoop() {
            var poller = this.poller;
            for (;;) {
                int drained = drainRound();

                // non-blocking I/O poll
                int ioEvents = 0;
                try {
                    ioEvents = poller.poll(0);
                } catch (IOException e) { }

                if (drained + ioEvents > 0) {
                    continue;
                }

                // one more non-blocking check before parking
                try {
                    if (poller.poll(0) > 0) continue;
                } catch (IOException e) { }

                // genuinely idle: blocking poll
                carrierState = PARKED;

                VirtualThreadTask task = queue.poll();
                if (task != null) {
                    carrierState = RUNNING;
                    try { task.run(); } catch (Throwable t) { }
                    continue;
                }

                try {
                    poller.poll(-1);
                } catch (IOException e) { }
                carrierState = RUNNING;
            }
        }

        /**
         * Runs the tasks the queue held when the round began, and no more.
         *
         * The snapshot bounds the round without reading a clock. Tasks that arrive while
         * the round is running -- the continuations this round's own task.run() calls
         * submit, and the ones the following poll will unpark -- are left for the next
         * round, so arrivals can never keep the poll from being reached at all, which
         * draining to empty can.
         *
         * What it does not do is bound the round in time. The quota is the queue depth
         * at entry and nothing here bounds that, so a round lasts quota x service time
         * and the interval between polls grows with the backlog. That interval bounds
         * discovery of an fd that becomes ready mid-round: its continuation is enqueued
         * only once a poll sees it, so every task that runs before that poll is ahead of
         * it in the FIFO. A time budget does bound the interval while tasks are short --
         * it cannot bound a round holding one slow task, having no way to preempt a task
         * already running -- and charges a clock read per round to do it. Which bound is
         * better is a question about the depth distribution of this queue under a real
         * workload, and that has not been measured.
         *
         * Only not-yet-discovered readiness pays that cost. A continuation an earlier
         * poll already enqueued sits at the tail of this same FIFO, and ending the round
         * sooner does not move it ahead of anything queued before it.
         */
        private int drainRound() {
            var queue = this.queue;
            int quota = queue.size();
            int ran = 0;
            VirtualThreadTask task;
            while (ran < quota && (task = queue.poll()) != null) {
                try { task.run(); } catch (Throwable t) { }
                ran++;
            }
            return ran;
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

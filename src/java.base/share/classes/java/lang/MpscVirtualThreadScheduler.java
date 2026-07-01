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

import java.lang.Thread.VirtualThreadScheduler;
import java.lang.Thread.VirtualThreadTask;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Contended;
import sun.nio.ch.Poller;

/**
 * An alternative virtual thread scheduler using two MPSC queues per carrier:
 * a sticky queue (for threads with sticky affinity, never stolen) and an
 * external queue (for stealable threads, protected by a ticket lock).
 *
 * <p>External submissions use a probe-based hash (FJP-style) to distribute
 * across carriers. Work stealing from external queues uses a ticket lock and
 * only activates when a carrier is genuinely idle. The carrier loop alternates
 * draining between the two queues using a counter and mask to prevent starvation.
 *
 * <p>Yield always keeps a virtual thread on its current carrier for both
 * sticky and non-sticky threads.
 */
final class MpscVirtualThreadScheduler implements VirtualThreadScheduler {

    private static final Unsafe U = Unsafe.getUnsafe();

    // Thread.threadLocalRandomProbe offset for FJP-style probing
    private static final long PROBE =
            U.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

    private final Carrier[] carriers;
    private final boolean carrierMasterPoller;
    private volatile boolean shutdown;

    MpscVirtualThreadScheduler(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.carriers = new Carrier[parallelism];
        this.carrierMasterPoller = "4".equals(System.getProperty("jdk.pollerMode"));
        for (int i = 0; i < parallelism; i++) {
            carriers[i] = new Carrier(i, this);
        }
        for (int i = 0; i < parallelism; i++) {
            int[] sibs = new int[parallelism - 1];
            int idx = 0;
            for (int j = 0; j < parallelism; j++) {
                if (j != i) sibs[idx++] = j;
            }
            carriers[i].siblings = sibs;
        }
        for (int i = 0; i < parallelism; i++) {
            carriers[i].thread.start();
        }
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");
        Carrier target = carrierForStart(task);
        enqueue(target, task, isSticky(task));
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");

        Object att = task.attachment();
        if (att instanceof Carrier target) {
            enqueue(target, task, isSticky(task));
            return;
        }

        // fallback: route like onStart
        enqueue(carrierForStart(task), task, isSticky(task));
    }

    private Carrier carrierForStart(VirtualThreadTask task) {
        // check round-robin affinity hint from factory
        if (task.thread() instanceof VirtualThread vt && vt.affinityHint >= 0) {
            return carriers[Math.floorMod(vt.affinityHint, carriers.length)];
        }
        // check preferredCarrier (set by newThread for sub-pollers etc.)
        try {
            Thread preferred = task.preferredCarrier();
            if (preferred instanceof MpscCarrierThread mct && mct.scheduler == this) {
                return mct.carrier;
            }
        } catch (UnsupportedOperationException e) {
            // VThreadTask (built-in) doesn't support preferredCarrier
        }
        return carrierFor();
    }

    private Carrier carrierFor() {
        Thread caller = Thread.currentCarrierThread();
        if (caller instanceof MpscCarrierThread mct && mct.scheduler == this) {
            return mct.carrier;
        }
        // external submission: FJP-style probing
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

    private void enqueue(Carrier target, VirtualThreadTask task, boolean sticky) {
        if (sticky) {
            target.stickyQueue.offer(task);
        } else {
            target.externalQueue.offer(task);
        }
        wakeCarrier(target);
    }

    private static void wakeCarrier(Carrier carrier) {
        if ((int) Carrier.CARRIER_STATE.getAcquire(carrier) == Carrier.PARKED) {
            if (carrier.pollerHandle != null) {
                try {
                    carrier.pollerHandle.wakeup();
                } catch (IOException e) {
                    LockSupport.unpark(carrier.thread);
                }
            } else {
                LockSupport.unpark(carrier.thread);
            }
        }
    }

    private static boolean isSticky(VirtualThreadTask task) {
        Thread vt = task.thread();
        return vt instanceof VirtualThread vthread && vthread.hasStickyAffinity();
    }

    // ---- Carrier ----

    static final class Carrier {
        static final VarHandle CARRIER_STATE;
        static final VarHandle CONSUMER_TICKET;
        static final VarHandle CONSUMER_SERVING;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CARRIER_STATE = l.findVarHandle(Carrier.class, "carrierState", int.class);
                CONSUMER_TICKET = l.findVarHandle(Carrier.class, "consumerTicket", int.class);
                CONSUMER_SERVING = l.findVarHandle(Carrier.class, "consumerServing", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final int RUNNING = 0;
        static final int PARKED  = 1;

        final int id;
        final MpscUnboundedQueue<VirtualThreadTask> stickyQueue = new MpscUnboundedQueue<>(64);
        final MpscUnboundedQueue<VirtualThreadTask> externalQueue = new MpscUnboundedQueue<>(64);
        final MpscVirtualThreadScheduler scheduler;
        final MpscCarrierThread thread;

        // plain field, only the carrier thread writes it
        int drainCounter;

        // ticket lock for external queue (stealers + carrier serialize on this)
        @Contended("ticket")
        @SuppressWarnings("unused") volatile int consumerTicket;
        @Contended("ticket")
        @SuppressWarnings("unused") volatile int consumerServing;

        // carrier state (read by external producers checking PARKED)
        @Contended
        @SuppressWarnings("unused") volatile int carrierState;

        // per-carrier master poller (null if not in Mode 4)
        Poller.CarrierPollerHandle pollerHandle;

        // sibling carrier IDs for steal scan (excludes self)
        int[] siblings;

        Carrier(int id, MpscVirtualThreadScheduler scheduler) {
            this.id = id;
            this.scheduler = scheduler;
            this.thread = new MpscCarrierThread(this, scheduler);
        }

        // ---- Ticket lock for external queue ----

        int acquireTicket() {
            int myTicket = (int) CONSUMER_TICKET.getAndAdd(this, 1);
            while ((int) CONSUMER_SERVING.getAcquire(this) != myTicket) {
                Thread.onSpinWait();
            }
            return myTicket;
        }

        void releaseTicket(int ticket) {
            CONSUMER_SERVING.setRelease(this, ticket + 1);
        }

        /**
         * Try to steal one task from this carrier's external queue.
         * Returns null if another stealer is active or the queue is empty.
         */
        VirtualThreadTask tryStealOne() {
            int t = (int) CONSUMER_TICKET.getAcquire(this);
            if (t != (int) CONSUMER_SERVING.getAcquire(this)) {
                return null;
            }
            if (!CONSUMER_TICKET.compareAndSet(this, t, t + 1)) {
                return null;
            }
            try {
                return externalQueue.poll();
            } finally {
                CONSUMER_SERVING.setRelease(this, t + 1);
            }
        }

        VirtualThreadTask pollExternal() {
            int ticket = acquireTicket();
            try {
                return externalQueue.poll();
            } finally {
                releaseTicket(ticket);
            }
        }

        boolean bothQueuesEmpty() {
            return stickyQueue.isEmpty() && externalQueue.isEmpty();
        }
    }

    // ---- Carrier thread ----

    static final class MpscCarrierThread extends Thread {
        final Carrier carrier;
        final MpscVirtualThreadScheduler scheduler;

        MpscCarrierThread(Carrier carrier, MpscVirtualThreadScheduler scheduler) {
            super(null, null, "mpsc-carrier-" + carrier.id, 0, false);
            this.carrier = carrier;
            this.scheduler = scheduler;
            setDaemon(true);
        }

        @Override
        public void run() {
            carrierLoop(carrier);
        }
    }

    // ---- Carrier loop ----

    static void carrierLoop(Carrier carrier) {
        // create per-carrier master poller if Mode 4 is active
        if (carrier.scheduler.carrierMasterPoller) {
            try {
                carrier.pollerHandle = Poller.createCarrierPollerHandle();
                Poller.registerCarrierMasterPoller(carrier.pollerHandle);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        for (;;) {
            VirtualThreadTask task = pollTask(carrier);
            if (task != null) {
                runTask(carrier, task);
                continue;
            }

            // try stealing from siblings before parking
            task = trySteal(carrier);
            if (task != null) {
                runTask(carrier, task);
                continue;
            }

            // genuinely idle: park (or poll if Mode 4)
            Carrier.CARRIER_STATE.setRelease(carrier, Carrier.PARKED);

            // re-check after setting PARKED to avoid missed wakeups
            if (!carrier.bothQueuesEmpty()) {
                Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
                continue;
            }

            if (carrier.pollerHandle != null) {
                try {
                    int polled = carrier.pollerHandle.poll(-1);
                    Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
                    if (polled > 0) {
                        // I/O wakeup: prioritize sticky queue (sub-pollers land there)
                        VirtualThreadTask stickyTask = carrier.stickyQueue.poll();
                        if (stickyTask != null) {
                            runTask(carrier, stickyTask);
                        }
                    }
                } catch (IOException e) {
                    Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
                }
            } else {
                LockSupport.park();
                Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
            }
        }
    }

    /**
     * Poll from one of the two queues, alternating with a counter to prevent
     * starvation. Falls back to the other queue if the primary is empty.
     */
    private static VirtualThreadTask pollTask(Carrier carrier) {
        carrier.drainCounter++;
        if ((carrier.drainCounter & 1) == 0) {
            VirtualThreadTask task = carrier.stickyQueue.poll();
            if (task != null) return task;
            return carrier.pollExternal();
        } else {
            VirtualThreadTask task = carrier.pollExternal();
            if (task != null) return task;
            return carrier.stickyQueue.poll();
        }
    }

    private static void runTask(Carrier carrier, VirtualThreadTask task) {
        task.attach(carrier);
        try {
            task.run();
        } catch (Throwable t) {
            // don't let carrier die
        }
    }

    /**
     * Try to steal one task from a sibling carrier's external queue.
     * Only probes carriers that are RUNNING (active carriers are likely
     * to have work piling up; parked carriers have empty queues).
     */
    private static VirtualThreadTask trySteal(Carrier carrier) {
        Carrier[] all = carrier.scheduler.carriers;
        int[] siblings = carrier.siblings;
        for (int siblingId : siblings) {
            Carrier victim = all[siblingId];
            if ((int) Carrier.CARRIER_STATE.getAcquire(victim) != Carrier.RUNNING) {
                continue;
            }
            VirtualThreadTask task = victim.tryStealOne();
            if (task != null) {
                return task;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "MpscVirtualThreadScheduler[carriers=" + carriers.length + "]";
    }
}

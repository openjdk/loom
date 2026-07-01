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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Contended;

/**
 * An alternative virtual thread scheduler using a single MPSC queue per carrier.
 * No work stealing — each carrier drains only its own queue.
 *
 * <p>External submissions use a probe-based hash (FJP-style) to distribute
 * across carriers. Carrier affinity is maintained via task attachment: after
 * first run, onContinue always routes back to the same carrier.
 *
 * <p>Yield always keeps a virtual thread on its current carrier.
 */
final class MpscVirtualThreadScheduler implements VirtualThreadScheduler {

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long PROBE =
            U.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

    private final Carrier[] carriers;
    private volatile boolean shutdown;

    MpscVirtualThreadScheduler(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.carriers = new Carrier[parallelism];
        for (int i = 0; i < parallelism; i++) {
            carriers[i] = new Carrier(i, this);
        }
        for (int i = 0; i < parallelism; i++) {
            carriers[i].thread.start();
        }
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");
        Carrier target = carrierForStart(task);
        enqueue(target, task);
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        if (shutdown) throw new RejectedExecutionException("Scheduler is shut down");

        Object att = task.attachment();
        if (att instanceof Carrier target) {
            enqueue(target, task);
            return;
        }

        enqueue(carrierForStart(task), task);
    }

    private Carrier carrierForStart(VirtualThreadTask task) {
        if (task.thread() instanceof VirtualThread vt && vt.affinityHint >= 0) {
            return carriers[Math.floorMod(vt.affinityHint, carriers.length)];
        }
        try {
            Thread preferred = task.preferredCarrier();
            if (preferred instanceof MpscCarrierThread mct && mct.scheduler == this) {
                return mct.carrier;
            }
        } catch (UnsupportedOperationException e) {
        }
        return carrierFor();
    }

    private Carrier carrierFor() {
        Thread caller = Thread.currentCarrierThread();
        if (caller instanceof MpscCarrierThread mct && mct.scheduler == this) {
            return mct.carrier;
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

    private void enqueue(Carrier target, VirtualThreadTask task) {
        target.queue.offer(task);
        if ((int) Carrier.CARRIER_STATE.getAcquire(target) == Carrier.PARKED) {
            LockSupport.unpark(target.thread);
        }
    }

    // ---- Carrier ----

    static final class Carrier {
        static final VarHandle CARRIER_STATE;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                CARRIER_STATE = l.findVarHandle(Carrier.class, "carrierState", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static final int RUNNING = 0;
        static final int PARKED  = 1;

        final int id;
        final MpscUnboundedQueue<VirtualThreadTask> queue = new MpscUnboundedQueue<>(64);
        final MpscVirtualThreadScheduler scheduler;
        final MpscCarrierThread thread;

        @Contended
        @SuppressWarnings("unused") volatile int carrierState;

        Carrier(int id, MpscVirtualThreadScheduler scheduler) {
            this.id = id;
            this.scheduler = scheduler;
            this.thread = new MpscCarrierThread(this, scheduler);
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
        for (;;) {
            VirtualThreadTask task = carrier.queue.poll();
            if (task != null) {
                task.attach(carrier);
                try {
                    task.run();
                } catch (Throwable t) {
                }
                continue;
            }

            Carrier.CARRIER_STATE.setVolatile(carrier, Carrier.PARKED);

            if (!carrier.queue.isEmpty()) {
                Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
                continue;
            }

            LockSupport.park();
            Carrier.CARRIER_STATE.set(carrier, Carrier.RUNNING);
        }
    }

    @Override
    public String toString() {
        return "MpscVirtualThreadScheduler[carriers=" + carriers.length + "]";
    }
}

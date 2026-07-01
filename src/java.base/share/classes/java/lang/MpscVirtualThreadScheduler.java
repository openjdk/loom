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
import java.util.concurrent.locks.LockSupport;
import jdk.internal.misc.Unsafe;
import jdk.internal.vm.annotation.Contended;

/**
 * An alternative virtual thread scheduler using a single MPSC queue per carrier.
 * No work stealing — each carrier drains only its own queue.
 *
 * <p>External submissions use a probe-based hash (FJP-style) to distribute
 * across carriers. Carrier affinity is set once at start via task attachment;
 * onContinue always routes back to the same carrier.
 *
 * <p>Yield always keeps a virtual thread on its current carrier.
 */
final class MpscVirtualThreadScheduler implements VirtualThreadScheduler {

    private static final Unsafe U = Unsafe.getUnsafe();

    private static final long PROBE =
            U.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

    private static final int SPINS_BEFORE_PARK = 16;

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
        CarrierThread target = carrierForStart(task);
        task.attach(target);
        target.queue.offer(task);
        if (target.carrierState == CarrierThread.PARKED) {
            LockSupport.unpark(target);
        }
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        Object att = task.attachment();
        if (att instanceof CarrierThread target) {
            target.queue.offer(task);
            if (target.carrierState == CarrierThread.PARKED) {
                LockSupport.unpark(target);
            }
            return;
        }
        onStart(task);
    }

    private CarrierThread carrierForStart(VirtualThreadTask task) {
        if (task.thread() instanceof VirtualThread vt && vt.affinityHint >= 0) {
            return carriers[Math.floorMod(vt.affinityHint, carriers.length)];
        }
        try {
            Thread preferred = task.preferredCarrier();
            if (preferred instanceof CarrierThread ct && ct.scheduler == this) {
                return ct;
            }
        } catch (UnsupportedOperationException e) {
        }
        return carrierFor();
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
            var queue = this.queue;
            for (;;) {
                VirtualThreadTask task = queue.poll();
                if (task != null) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                    }
                    continue;
                }

                // spin briefly before parking
                boolean found = false;
                for (int i = 0; i < SPINS_BEFORE_PARK; i++) {
                    Thread.onSpinWait();
                    if (!queue.isEmpty()) {
                        found = true;
                        break;
                    }
                }
                if (found) continue;

                carrierState = PARKED;

                if (!queue.isEmpty()) {
                    carrierState = RUNNING;
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

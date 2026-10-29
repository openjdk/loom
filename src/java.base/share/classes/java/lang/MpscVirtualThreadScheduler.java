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
import sun.nio.ch.LocalPoller;
import sun.nio.ch.LocalPollers;

/**
 * A virtual thread scheduler with one MPSC queue per carrier (MpscCarrierThread).
 *
 * <p>Each virtual thread has a home, a carrier and one of its queues, set on start
 * and used by every continuation. A placed thread (affinity, or a preferred carrier of
 * this scheduler) stays on its carrier's pinned queue for life. An unplaced thread
 * starts on the starting carrier or, from a platform thread, on the carrier that
 * thread's stable probe picks; a sticky submission (submitLocal) moves it to the
 * caller's carrier. Placement beats sticky.
 *
 * <p>Mode 4 (CARRIER_LOCAL_POLLER) is the default where available (Linux, with
 * continuations, jdk.pollerMode unset or 4): each carrier owns a LocalPoller that VT
 * fds register with directly, and alternates rounds of its queues with polls.
 *
 * <p>A yield resubmits the thread. In Mode 4 a thread with a pinned home is held by
 * its carrier and runs at the end of the next round, after everything queued before
 * it, including what the poll woke; any other goes to the tail of its home queue. A
 * lone yielder never makes its carrier block.
 *
 * <p>With {@link #STEAL}, every unplaced thread's home is its carrier's stealable
 * queue, and then the last carrier that took it. A producer of a stealable task wakes
 * the owner if it is parked, else signals (SearchGate), except for the owner's own
 * submissions between tasks and an owner that is WAKING, which announces for them. See MpscCarrierThread for the carrier states and SearchGate for
 * the searcher token.
 */
final class MpscVirtualThreadScheduler
        implements VirtualThread.BuiltinScheduler, LocalPoller.Scheduler {

    /** Opt-in work stealing; when off, none of the code that tests it runs. */
    static final boolean STEAL = Boolean.getBoolean("jdk.virtualThreadScheduler.mpscSteal");

    private final MpscCarrierThread[] carriers;

    MpscVirtualThreadScheduler(int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        LocalPoller[] pollers = createLocalPollers(parallelism);
        this.carriers = new MpscCarrierThread[parallelism];
        IdleSet idleSet = STEAL ? new IdleSet(parallelism) : null;
        SearchGate gate = STEAL ? new SearchGate(carriers, idleSet) : null;
        for (int i = 0; i < parallelism; i++) {
            carriers[i] = new MpscCarrierThread(i, this, idleSet, gate,
                    (pollers != null) ? pollers[i] : null);
        }
        for (int i = 0; i < parallelism; i++) {
            carriers[i].start();
        }
    }

    /** On failure, closes those created and throws: no carrier runs without its poller. */
    private static LocalPoller[] createLocalPollers(int count) {
        if (!LocalPollers.enabled()) {
            return null;
        }
        LocalPoller[] pollers = new LocalPoller[count];
        int created = 0;
        try {
            for (; created < count; created++) {
                pollers[created] = LocalPollers.create();
            }
        } catch (IOException e) {
            closeAll(pollers, created);
            throw new UncheckedIOException(e);
        } catch (Throwable e) {
            closeAll(pollers, created);
            throw e;
        }
        return pollers;
    }

    private static void closeAll(LocalPoller[] pollers, int count) {
        for (int i = 0; i < count; i++) {
            pollers[i].close();
        }
    }

    static final class Home {
        private final MpscCarrierThread carrier;
        private final boolean stealable;

        Home(MpscCarrierThread carrier, boolean stealable) {
            this.carrier = carrier;
            this.stealable = stealable;
        }

        /** Never for a stealable home, whose thread must stay where a thief can take it. */
        boolean holdYield(VirtualThreadTask task) {
            return !(STEAL && stealable) && carrier.holdYield(task);
        }

        private void submit(VirtualThreadTask task) {
            if (STEAL && stealable) {
                carrier.offerStealable(task);
            } else {
                carrier.offer(task);
            }
        }
    }

    static final class MpscTask extends VirtualThread.VThreadTask {
        // a carrier index, taken mod the carrier count, or -1 if unplaced
        final int placement;

        // written before each offer, which publishes it to the next submitter
        private Home home;

        // link in the held yields of the carrier the thread yielded on (holdYield)
        MpscTask nextYielded;

        MpscTask(VirtualThread vthread, int placement) {
            super(vthread);
            this.placement = placement;
        }
    }

    @Override
    public VirtualThread.VThreadTask newTask(VirtualThread vthread, int placement, Thread preferredCarrier) {
        if (placement < 0 && preferredCarrier instanceof MpscCarrierThread ct && ct.belongsTo(this)) {
            placement = ct.id;
        }
        return new MpscTask(vthread, placement);
    }

    // A custom scheduler's task keeps its home in builtinState.

    private int placement(VirtualThreadTask task) {
        if (task instanceof MpscTask t) {
            return t.placement;
        }
        Thread.Affinity affinity = task.affinity();
        if (affinity != null) {
            return affinity.index;
        }
        return (task.preferredCarrier() instanceof MpscCarrierThread ct && ct.belongsTo(this)) ? ct.id : -1;
    }

    private static Home home(VirtualThreadTask task) {
        if (task instanceof MpscTask t) {
            return t.home;
        }
        return (Home) ((VirtualThread.CustomVThreadTask) task).builtinState;
    }

    static void setHome(VirtualThreadTask task, Home home) {
        if (task instanceof MpscTask t) {
            t.home = home;
        } else {
            ((VirtualThread.CustomVThreadTask) task).builtinState = home;
        }
    }

    @Override
    public void onStart(VirtualThreadTask task) {
        int placement = placement(task);
        Home home;
        if (placement >= 0) {
            home = carriers[Math.floorMod(placement, carriers.length)].pinnedHome();
        } else {
            MpscCarrierThread target = carrierFor();
            home = target.unplacedHome();
        }
        setHome(task, home);
        home.submit(task);
    }

    @Override
    public void onContinue(VirtualThreadTask task) {
        Home home = home(task);
        if (home == null) {
            onStart(task);
            return;
        }
        // holdYield relies on this
        assert !(task instanceof MpscTask t && t.isYielded())
                || Thread.currentThread() == home.carrier
                : "YIELDED thread submitted by " + Thread.currentThread();
        if (!home.holdYield(task)) {
            home.submit(task);
        }
    }

    @Override
    public boolean submitLocal(VirtualThread.VThreadTask task) {
        if (!(Thread.currentCarrierThread() instanceof MpscCarrierThread c)
                || !c.belongsTo(this)
                || placement(task) >= 0) {
            return false;
        }
        Home home = c.unplacedHome();
        if (home(task) != home) {
            setHome(task, home);
        }
        home.submit(task);
        return true;
    }

    private MpscCarrierThread carrierFor() {
        Thread caller = Thread.currentCarrierThread();
        if (caller instanceof MpscCarrierThread ct && ct.belongsTo(this)) {
            return ct;
        }
        return carriers[Math.floorMod(probe(), carriers.length)];
    }

    // a sequence, as in ThreadLocalRandom.localInit: hashes of a few sequential thread
    // ids spread poorly over a few carriers
    private static final java.util.concurrent.atomic.AtomicInteger PROBE_SEEDER =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Stable for the calling platform thread's life, as ForkJoinPool's probe: a burst
     * then wakes its carrier once and finds it RUNNING after, where rotating would find
     * a parked carrier nearly every time. Threads wanting spread use an affinity group.
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

    @Override
    public String toString() {
        return "MpscVirtualThreadScheduler[carriers=" + carriers.length + "]";
    }
}

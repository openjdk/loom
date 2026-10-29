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
import java.lang.MpscVirtualThreadScheduler.Home;
import java.lang.MpscVirtualThreadScheduler.MpscTask;
import java.lang.Thread.VirtualThreadTask;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.vm.annotation.Contended;
import sun.nio.ch.LocalPoller;

import static java.lang.MpscVirtualThreadScheduler.STEAL;
import static java.lang.MpscVirtualThreadScheduler.setHome;

/**
 * A carrier thread of MpscVirtualThreadScheduler; localPoller() implements
 * LocalPoller.Carrier for sun.nio.ch.Poller. Mode 4 when poller is not null.
 *
 * <p>States of {@code carrierState}:
 * <pre>
 *   RUNNING -> PARKED                 the carrier
 *   PARKED  -> RUNNING                the carrier
 *   PARKED  -> WAKING                 a waker
 *   PARKED  -> SEARCHING + victim id  SearchGate's token holder
 *   WAKING, SEARCHING -> RUNNING      the carrier
 * </pre>
 *
 * <p>Invariants:
 * <ul>
 * <li>A task offered to a parked carrier is never missed, and a park costs at most
 *     one wake.
 * <li>No task runs while the state reads PARKED.
 * <li>With stealing, every stealable task is announced, by its producer or by the
 *     owner after WAKING, or run by its owner.
 * <li>stealQueue is single-consumer: the owner and thieves take turns.
 * </ul>
 */
final class MpscCarrierThread extends Thread implements LocalPoller.Carrier {
    private static final VarHandle STATE;
    private static final VarHandle STEAL_LOCK;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(MpscCarrierThread.class, "carrierState", int.class);
            STEAL_LOCK = l.findVarHandle(MpscCarrierThread.class, "stealLock", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final int id;
    private final MpscUnboundedQueue<VirtualThreadTask> queue = new MpscUnboundedQueue<>(64);
    private final MpscVirtualThreadScheduler scheduler;
    private final IdleSet idleSet;
    private final SearchGate gate;
    private final Home pinnedHome = new Home(this, false);
    private final Home stealableHome = STEAL ? new Home(this, true) : null;

    private final MpscUnboundedQueue<VirtualThreadTask> stealQueue =
            STEAL ? new MpscUnboundedQueue<>(64) : null;

    private final LocalPoller poller;

    @Contended("local")
    private MpscTask yieldedHead, yieldedTail;

    @Contended("state")
    private volatile int carrierState;

    @Contended("stealLock")
    private volatile int stealLock;

    @Contended("local")
    private int xorshift;

    // stealable tasks queued on this carrier from its own thread since the last poll began
    @Contended("local")
    private int pollQueued;

    MpscCarrierThread(int id, MpscVirtualThreadScheduler scheduler, IdleSet idleSet,
                      SearchGate gate, LocalPoller poller) {
        super(null, null, "mpsc-carrier-" + id, 0, false);
        this.id = id;
        this.scheduler = scheduler;
        this.idleSet = idleSet;
        this.gate = gate;
        this.poller = poller;
        this.xorshift = (id + 1) * 0x9E3779B9 | 1;
        setDaemon(true);
    }

    // ---- Carrier state ----

    private static final int RUNNING = 0;
    private static final int PARKED  = 1;
    // A waker claimed this park and its wake is in flight. Producers that read it
    // don't signal: the carrier announces its backlog once it runs, so a burst to a
    // parked carrier costs the producer one wake and the carrier wakes the helpers.
    private static final int WAKING = 2;
    private static final int SEARCHING = 3;

    private int wakeParked() {
        int witness = (int) STATE.compareAndExchange(this, PARKED, WAKING);
        if (witness == PARKED) {
            wake();
        }
        return witness;
    }

    private void wake() {
        if (poller != null) {
            try {
                poller.wakeup();
            } catch (IOException e) {
                LockSupport.unpark(this);
            }
        } else {
            LockSupport.unpark(this);
        }
    }

    boolean isParked() {
        return carrierState == PARKED;
    }

    boolean tryStartSearch(MpscCarrierThread victim) {
        if (STATE.compareAndSet(this, PARKED, SEARCHING + victim.id)) {
            wake();
            return true;
        }
        return false;
    }

    private void enterParked() {
        carrierState = PARKED;
        idleSet.setIdle(this);
    }

    /**
     * After the blocking poll or park. polled, what that poll queued here, is announced
     * only from PARKED: WAKING's backlog counts it, SEARCHING's release rescans for it.
     */
    private void leaveParked(int polled) {
        int prev = (int) STATE.getAndSet(this, RUNNING);
        idleSet.clearIdle(this);
        if (prev == WAKING) {
            announceBacklog(false);
        } else if (prev < SEARCHING) {
            announcePolled(polled);
        } else {
            search(gate.carrier(prev - SEARCHING));
        }
    }

    /** Leaves PARKED without blocking, with a task in hand. */
    private void abortPark() {
        int prev = (int) STATE.getAndSet(this, RUNNING);
        idleSet.clearIdle(this);
        if (prev == WAKING) {
            announceBacklog(true);
        } else if (prev >= SEARCHING) {
            gate.release(nextRandom());
        }
    }

    // ---- Used by the scheduler and SearchGate ----

    void offer(VirtualThreadTask task) {
        queue.offer(task);
        if (carrierState == PARKED) {
            // readiness from this carrier's own blocking poll, which drains on return
            if (Thread.currentCarrierThread() == this) {
                return;
            }
            wakeParked();
        }
    }

    void offerStealable(VirtualThreadTask task) {
        stealQueue.offer(task);
        int state = carrierState;
        if (state == PARKED) {
            if (Thread.currentCarrierThread() != this) {
                if (wakeParked() == RUNNING && hasStealable()) {
                    gate.signal(this);
                }
            } else {
                pollQueued++;
            }
        } else if (state != WAKING && Thread.currentThread() != this) {
            gate.signal(this);
        } else if (Thread.currentThread() == this) {
            pollQueued++;
        }
    }

    Home pinnedHome() {
        return pinnedHome;
    }

    Home unplacedHome() {
        return STEAL ? stealableHome : pinnedHome;
    }

    boolean belongsTo(MpscVirtualThreadScheduler s) {
        return scheduler == s;
    }

    boolean usesGate(SearchGate g) {
        return gate == g;
    }

    /**
     * Mode 4: holds a yield's resubmit, which only this carrier with no thread mounted
     * makes, until the next round (drainRound); false if the caller must queue it.
     */
    boolean holdYield(VirtualThreadTask task) {
        if (Thread.currentThread() != this || poller == null
                || !(task instanceof MpscTask t) || !t.isYielded()) {
            return false;
        }
        MpscTask tail = yieldedTail;
        if (tail == null) {
            yieldedHead = t;
        } else {
            tail.nextYielded = t;
        }
        yieldedTail = t;
        return true;
    }

    int nextRandom() {
        int r = xorshift;
        r ^= r << 13;
        r ^= r >>> 17;
        r ^= r << 5;
        return xorshift = r;
    }

    @Override
    public LocalPoller localPoller() {
        return poller;
    }

    // ---- The carrier's own loops ----

    @Override
    public void run() {
        if (poller != null) {
            if (STEAL) {
                eventLoopSteal();
            } else {
                eventLoop();
            }
        } else if (STEAL) {
            plainLoopSteal();
        } else {
            plainLoop();
        }
    }

    private void eventLoop() {
        var poller = this.poller;
        for (;;) {
            int drained = drainRound();

            int ioEvents = 0;
            try {
                ioEvents = poller.poll(0);
            } catch (IOException e) { }

            if (drained + ioEvents > 0) {
                continue;
            }

            try {
                if (poller.poll(0) > 0) continue;
            } catch (IOException e) { }

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
     * Runs the tasks the queue held when the round began, then the held yields.
     * Arrivals wait for the next round, so the poll between rounds is always reached;
     * the bound is a count, not a time, so the gap between polls grows with the backlog.
     */
    private int drainRound() {
        var queue = this.queue;
        int quota = queue.size();
        MpscTask yielded = takeYielded();
        int ran = 0;
        VirtualThreadTask task;
        while (ran < quota && (task = queue.poll()) != null) {
            try { task.run(); } catch (Throwable t) { }
            ran++;
        }
        return ran + runYielded(yielded);
    }

    private MpscTask takeYielded() {
        MpscTask head = yieldedHead;
        if (head != null) {
            yieldedHead = yieldedTail = null;
        }
        return head;
    }

    private static int runYielded(MpscTask task) {
        int ran = 0;
        while (task != null) {
            MpscTask next = task.nextYielded;
            // before the run: a yield in it links the task into the next round's list
            task.nextYielded = null;
            try { task.run(); } catch (Throwable t) { }
            ran++;
            task = next;
        }
        return ran;
    }

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

    // ---- STEAL only ----

    private void eventLoopSteal() {
        var poller = this.poller;
        for (;;) {
            int drained = drainRoundSteal();

            int ioEvents = 0;
            pollQueued = 0;
            try {
                ioEvents = poller.poll(0);
            } catch (IOException e) { }
            announcePolled(pollQueued);

            if (drained + ioEvents > 0) {
                continue;
            }

            pollQueued = 0;
            try {
                ioEvents = poller.poll(0);
            } catch (IOException e) { }
            announcePolled(pollQueued);
            if (ioEvents > 0) {
                continue;
            }

            enterParked();
            if (workBeforeBlocking()) {
                continue;
            }

            pollQueued = 0;
            try {
                poller.poll(-1);
            } catch (IOException e) { }
            // read first: a stolen task leaveParked runs adds its resubmits, not readiness
            int polled = pollQueued;
            leaveParked(polled);
        }
    }

    private void announcePolled(int polled) {
        if (polled > 1) {
            gate.signal(this);
        }
    }

    private void plainLoopSteal() {
        for (;;) {
            if (drainRoundSteal() > 0) {
                continue;
            }

            enterParked();
            if (workBeforeBlocking()) {
                continue;
            }

            LockSupport.park();
            leaveParked(0);
        }
    }

    private int drainRoundSteal() {
        var queue = this.queue;
        int quota = queue.size();
        int stealQuota = stealQueue.size();
        MpscTask yielded = takeYielded();
        int ran = 0;
        VirtualThreadTask task;
        for (int i = 0; i < quota && (task = queue.poll()) != null; i++) {
            try { task.run(); } catch (Throwable t) { }
            ran++;
        }
        for (int i = 0; i < stealQuota && (task = pollStealable()) != null; i++) {
            try { task.run(); } catch (Throwable t) { }
            ran++;
        }
        return ran + runYielded(yielded);
    }

    /** Still PARKED: rechecks its own queues, then tries one steal; false to block. */
    private boolean workBeforeBlocking() {
        VirtualThreadTask task = queue.poll();
        if (task == null) {
            task = pollStealable();
        }
        if (task != null) {
            abortPark();
            try { task.run(); } catch (Throwable t) { }
            return true;
        }
        MpscCarrierThread victim = gate.bestVictim(this, nextRandom());
        if (victim != null && (task = victim.trySteal()) != null) {
            abortPark();
            // no fence before the emptiness test, as in SearchGate.handOn
            if (victim.hasStealable()) {
                gate.signal(victim);
            }
            runStolen(task);
            return true;
        }
        return false;
    }

    /** Keeps one task of the backlog for itself unless other work runs first. */
    private void announceBacklog(boolean taskInHand) {
        int backlog = stealableBacklog();
        if (backlog > 1 || (backlog == 1 && (taskInHand || !queue.isEmpty()))) {
            gate.signal(this);
        }
    }

    private void search(MpscCarrierThread victim) {
        VirtualThreadTask task = victim.trySteal();
        if (task == null) {
            gate.release(nextRandom());
            return;
        }
        gate.handOn(victim, nextRandom());
        runStolen(task);
    }

    private void runStolen(VirtualThreadTask task) {
        // before the run, so that a sticky wakeup during it can set a newer home
        setHome(task, stealableHome);
        try { task.run(); } catch (Throwable t) { }
    }

    private VirtualThreadTask pollStealable() {
        while (!STEAL_LOCK.compareAndSet(this, 0, 1)) {
            Thread.onSpinWait();
        }
        VirtualThreadTask task = stealQueue.poll();
        STEAL_LOCK.setRelease(this, 0);
        return task;
    }

    // ---- The stealable queue as other carriers and SearchGate see it ----

    int stealableBacklog() {
        return stealQueue.sizeEstimate();
    }

    boolean hasStealable() {
        return !stealQueue.isEmpty();
    }

    private VirtualThreadTask trySteal() {
        var q = stealQueue;
        if (q.isEmpty()) {
            return null;
        }
        if (!STEAL_LOCK.compareAndSet(this, 0, 1)) {
            return null;
        }
        VirtualThreadTask task = q.poll();
        STEAL_LOCK.setRelease(this, 0);
        return task;
    }
}

/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.vm.annotation.IntrinsicCandidate;
import jdk.internal.vm.annotation.ReservedStackAccess;

/**
 * Class {@code MonitorSupport} encapsulates all the supporting
 * functionality for the different Monitor implementation policies
 */
final class MonitorSupport {

    /**
     * Control use of monitor synchronization during VM initialisation.
     * Should only be used for monitor enter and exit as the other monitor
     * methods should not be used during initialization when there is only
     * a single thread. This field is set by the VM.
     *
     * The exact use of this flag depends on the implementation in use. For
     * fast locks we expect to be able to use synchronization much earlier than
     * for heavy monitors, as fast-locking won't encounter inflation at this
     * stage. So we need to avoid any use of synchronized blocks and methods in
     * any code prior to fast locking being initialized, and so syncEnabled acts
     * as an assertion for that. Once fast locks are initialized we can set
     * syncEnabled to true. If fast locks were the only mode supported then we
     * could dispense with this field altogether and also revert some of the core
     * class changes that explicitly avoid synchronization.
     *
     * For heavy monitors there is too much code involved in the transitive
     * initialization to avoid all use of synchronized methods and blocks, so
     * in that case syncEnabled is used to turn synchronization operations into
     * no-ops. When all the necessary code has been initialized we can then set
     * syncEnabled to true.
     *
     * Legacy and native modes ignore the syncEnabled field.
     */
    static int syncEnabled = 0;


    static interface Policy {

        /** Entry point for monitor entry from the VM (all cases) */
        void monitorEnter(Object o);

        /** Entry point for monitor exit from the VM (bytecode, ObjectLocker)*/
        void monitorExit(Object o);

        /** Entry point for direct monitor exit from the VM (sync methods, early returns) */
        void monitorExitAll(int count);

        /** Entry point for uninterruptible monitor wait from the VM
         *  (used only for class initialization when using Java monitors)
         */
        void monitorWaitUninterruptibly(Object o);

        /** Redirection of Object.wait(long ms) */
        void monitorWait(Object o, long timeoutMillis) throws InterruptedException;

        /** Redirection of Object.notify() */
        void monitorNotify(Object o);

        /** Redirection of Object.notifyAll() */
        void monitorNotifyAll(Object o);

        /** Queries if the expected thread owns the monitor for the
         *  given object.
         */
        boolean hasLockedObject(Object o, Thread expected);
    }

    static final Policy policy = initPolicy();

    static final Policy policy() {
        return policy;
    }

    static final Policy initPolicy() {
        switch(getMonitorPolicy()) {
            case -1: return new Legacy();
            case 0: return new Native();
            case 1: return new Heavy();
            case 2: return new Fast();
            default:
                abort("Invalid policy value");
                return null;
        }
    }

    /**
     * API to call into the VM to get the monitor implementation policy:
     *
     *  -1: Original/legacy monitor implementation
     *
     * Otherwise, we are using a Java upcall from the VM, combined with the
     * use of the Thread lockStack and then a particular monitor implementation
     * as follows:
     *  - 0: native: call back into VM to use native monitors, but always
     *       inflate (no fast-locking via markWord or stackLocks).
     *  - 1: heavy: use Monitor class with always-inflate policy. No deflation.
     *  - 2: fast: use Java-based fast-locks via markWord and inflate to Monitor
     *       class on contention, or when needed for wait(). No deflation.
     *
     */
    private static final native int getMonitorPolicy();

    // --- Policy Class Definitions ---


    static final class Legacy implements Policy {

        public void monitorEnter(Object o) {
            abort("monitorEnter: Should not reach here for legacy sync");
        }

        public void monitorExit(Object o) {
            abort("monitorExit: Should not reach here for legacy sync");
        }

        public void monitorExitAll(int count) {
            abort("monitorExitAll: Should not reach here for legacy sync");
        }

        public void monitorWaitUninterruptibly(Object o){
            abort("monitorWaitUninterruptibly: Should not reach here for legacy sync");
        }

        @ReservedStackAccess
        public final void monitorWait(Object o, long timeoutMillis) throws InterruptedException {
            o.wait0(timeoutMillis);
        }

        @ReservedStackAccess
        public final void monitorNotify(Object o) {
            o.notify0();
        }

        @ReservedStackAccess
        public final void monitorNotifyAll(Object o) {
            o.notifyAll0();
        }

        @ReservedStackAccess
        public final boolean hasLockedObject(Object o, Thread current) {
            return Thread.holdsLock0(o);
        }
    }

    static final class Native implements Policy {

        @ReservedStackAccess
        public void monitorEnter(Object o) {
            Thread.currentThread().push(o);
            Object.monitorEnter0(o);
        }

        @ReservedStackAccess
        public void monitorExit(Object o) {
            Thread.currentThread().pop(o);
            Object.monitorExit0(o);
        }

        @ReservedStackAccess
        public void monitorExitAll(int count) {
            Thread t = Thread.currentThread();
            for (int i = 0; i < count; i++) {
                Object o = t.pop();
                if (o instanceof Monitor)  // sanity check
                    abort("Impossible!");
                Object.monitorExit0(o);
            }
        }

        public void monitorWaitUninterruptibly(Object o){
            abort("monitorWaitUninterruptibly: Should not reach here for native sync");
        }

        @ReservedStackAccess
        public final void monitorWait(Object o, long timeoutMillis) throws InterruptedException {
            o.wait0(timeoutMillis);
        }

        @ReservedStackAccess
        public final void monitorNotify(Object o) {
            o.notify0();
        }

        @ReservedStackAccess
        public final void monitorNotifyAll(Object o) {
            o.notifyAll0();
        }

        @ReservedStackAccess
        public final boolean hasLockedObject(Object o, Thread current) {
            return Thread.holdsLock0(o);
        }
    }

    static class Heavy implements Policy {

        @ReservedStackAccess
        public void monitorEnter(Object o) {
            if (syncEnabled == 0)
                return;
            Thread t = Thread.currentThread();
            t.push(o);
            Monitor.of(o).enter(t);
        }

        @ReservedStackAccess
        public void monitorExit(Object o) {
            if (syncEnabled == 0)
                return;
            Thread t = Thread.currentThread();
            t.pop(o);
            Monitor.of(o).exit(t);
        }

        @ReservedStackAccess
        public void monitorExitAll(int count) {
            Thread t = Thread.currentThread();
            for (int i = 0; i < count; i++) {
                Object o = t.pop();
                if (o instanceof Monitor)  // sanity check
                    abort("Impossible!");
                Monitor.of(o).exit(t);
            }
        }

        @ReservedStackAccess
        public void monitorWaitUninterruptibly(Object o){
            if (syncEnabled == 0) abort("invariant");
            Thread t = Thread.currentThread();
            Monitor.of(o).awaitUninterruptibly(t);
        }

        @ReservedStackAccess
        public final void monitorWait(Object o, long timeoutMillis) throws InterruptedException {
            if (syncEnabled == 0) abort("invariant");
            Monitor.of(o).await(Thread.currentThread(), timeoutMillis);
        }

        @ReservedStackAccess
        public final void monitorNotify(Object o) {
            if (syncEnabled == 0) abort("invariant");
            Monitor.of(o).signal(Thread.currentThread());
        }

        @ReservedStackAccess
        public final void monitorNotifyAll(Object o) {
            if (syncEnabled == 0) abort("invariant");
            Monitor.of(o).signalAll(Thread.currentThread());
        }

        @ReservedStackAccess
        public final boolean hasLockedObject(Object o, Thread current) {
            boolean owned = current.hasLocked(o);
            // We can sanity check the lockStack
            if (Monitor.of(o).isOwnedBy(current) != owned) {
                abort("Lock ownership mismatch for Heavy implementaion");
            }
            return owned;
        }
    }


    static class Fast implements Policy {

        @ReservedStackAccess
        public void monitorEnter(Object o) {
            if (syncEnabled == 0)
                abort("Synchronization not ready for use");
            Thread t = Thread.currentThread();
            if (!quickEnter(t, o)) {
                Monitor.slowEnter(t, o);
            }
        }

        @ReservedStackAccess
        public void monitorExit(Object o) {
            if (syncEnabled == 0)
                abort("Synchronization not ready for use");
            Thread t = Thread.currentThread();
            if (!quickExit(t, o)) {
                Monitor.slowExit(t, o);
            }
        }

        @ReservedStackAccess
        public void monitorExitAll(int count) {
            Thread t = Thread.currentThread();
            for (int i = 0; i < count; i++) {
                Object o = t.peek();
                if (o instanceof Monitor) { // inflated
                    Monitor m = (Monitor)o;
                    t.pop(m);
                    m.exit(t);
                } else if (!quickExit(t, o)) {
                    if (syncEnabled == 0)
                        abort("Synchronization not ready for use");

                    Monitor.slowExitOnRemoveActivation(t, o);
                }
            }
        }

        @ReservedStackAccess
        public void monitorWaitUninterruptibly(Object o){
            if (syncEnabled == 0) abort("invariant");
            Thread t = Thread.currentThread();
            Monitor.slowWaitUninterruptibly(t, o);
        }

        @ReservedStackAccess
        public final void monitorWait(Object o, long timeoutMillis) throws InterruptedException {
            if (syncEnabled == 0) abort("invariant");
            Monitor.slowWait(Thread.currentThread(), o, timeoutMillis);
        }


        @ReservedStackAccess
        public final void monitorNotify(Object o) {
            if (syncEnabled == 0) abort("invariant");
            Monitor.slowNotify(Thread.currentThread(), o);
        }

        @ReservedStackAccess
        public final void monitorNotifyAll(Object o) {
            if (syncEnabled == 0) abort("invariant");
            Monitor.slowNotifyAll(Thread.currentThread(), o);
        }

        @ReservedStackAccess
        public final boolean hasLockedObject(Object o, Thread current) {
            boolean owned = current.hasLocked(o);
            // We could sanity check the lockStack in the inflated
            // case but we don't.
            return owned;
        }

        // --- Implementations details for fast locks ---

        // In the markWord the lower two bits are the lock-bits.
        // We don't have an INFLATING state as that protocol is
        // handled in the Java code. Once INFLATED we never deflate.
        // The Monitor is kept live in the MonitorMap until the associated
        // Object is eligible for GC and its entry removed from the map.

        // These are accessed from the inflation code in Monitor as well.
        static final int UNLOCKED = 1;
        static final int LOCKED = 0;
        static final int INFLATED = 2;

        // lowest level enter/exit via markWord ops

        private static boolean quickLock(Thread current, Object lockee) {
            if (casLockState(lockee, LOCKED, UNLOCKED)) {
                current.push(lockee);
                return true;
            }
            return false;
        }

        private static boolean quickUnlock(Thread current, Object lockee) {
            if (casLockState(lockee, UNLOCKED, LOCKED)) {
                current.pop(lockee);
                if (current.lockCount(lockee) > 0) {
                    abort("Bad lockstack: unlocked object still on stack");
                }
                return true;
            }
            return false;
        }


        // Attempted fast-path monitor entry and exit

        static boolean quickEnter(Thread current, Object lockee) {
            while (true) {
                int lockState = getLockState(lockee);
                switch (lockState) {
                case UNLOCKED:
                    if (current.lockCount(lockee) > 0) {
                        abort("Bad lockstack: lockee unlocked but on stack");
                    }
                    if (quickLock(current, lockee)) {
                        return true;
                    }
                    break; // re-check, CAS failed
                case LOCKED:
                    // recursive locking ?
                    if (current.hasLockedDirect(lockee)) {
                        current.push(lockee);
                        return true;
                    }
                    return false; // take slow path
                case INFLATED:
                    return false; // take slow path
                default:
                    abort("Bad markword state: " + lockState);
                }
                Thread.yield();
            }
        }

        static boolean quickExit(Thread current, Object lockee) {
            while (true) {
                int lockState = getLockState(lockee);
                switch (lockState) {
                case UNLOCKED:
                    abort("Bad markword: unlocked on exit");
                    break;
                case LOCKED:
                    int locksHeld = current.lockCount(lockee);
                    if (locksHeld == 1) {
                        if (quickUnlock(current, lockee)) {
                            return true;
                        } else {
                            break; // re-check, CAS failed
                        }
                    } else if (locksHeld > 1) {
                        // recursive locking
                        current.pop(lockee);
                        return true;
                    } else {
                        if (current.hasLocked(lockee))
                            abort("lockCount and hasLocked disagree");
                        abort("Invalid lockCount when locked: " + locksHeld);
                    }
                    break; // can't reach here but keeps compiler happy
                case INFLATED:
                    return false; // take slow path
                default:
                    abort("Bad markword: " + lockState);
                }
                Thread.yield();
            }
        }
    }



    // Native hooks into the VM - these are registered by the
    // VM, not via registerNatives, as the latter calls back into
    // the ClassLoader code and requires synchronization.

    final static native void log(String msg);
    final static native void log_exitAll(int count);
    final static native void abort(String error);
    final static native void abortException(String msg, Throwable t);
    @IntrinsicCandidate
    final static native boolean casLockState(Object o, int to, int from);
    @IntrinsicCandidate
    final static native int getLockState(Object o);

    static {
        log("MonitorSupport.<clinit> executing");
    }
}

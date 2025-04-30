/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.vm;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Represents a snapshot of information about a Thread.
 */
class ThreadSnapshot {
    private static final StackTraceElement[] EMPTY_STACK = new StackTraceElement[0];
    private static final ThreadLock[] EMPTY_LOCKS = new ThreadLock[0];

    private String name;
    private int threadStatus;
    private StackTraceElement[] stackTrace;
    private ThreadLock[] locks;

    /**
     * Take a snapshot of a Thread to get all information about the thread.
     */
    static ThreadSnapshot of(Thread thread) {
        ThreadSnapshot snapshot = create(thread, true);
        if (snapshot.stackTrace == null) {
            snapshot.stackTrace = EMPTY_STACK;
        }
        snapshot.locks = snapshot.locks == null
                         ? snapshot.locks = EMPTY_LOCKS
                         : ThreadLock.of(snapshot.locks);
        return snapshot;
    }

    /**
     * Returns the thread name.
     */
    String threadName() {
        return name;
    }

    /**
     * Returns the thread state.
     */
    Thread.State threadState() {
        // is this valid for virtual threads
        return jdk.internal.misc.VM.toThreadState(threadStatus);
    }

    /**
     * Returns the thread stack trace.
     */
    StackTraceElement[] stackTrace() {
        return stackTrace;
    }

    /**
     * Returns the thread's parkBlocker.
     */
    Object parkBlocker() {
        return findLockObject(0, LockType.PARKING_TO_WAIT)
                .findAny()
                .orElse(null);
    }

    /**
     * Returns the object that the thread is blocked on.
     * @throws IllegalStateException if not in the blocked state
     */
    Object blockedOn() {
        if (threadState() != Thread.State.BLOCKED) {
            throw new IllegalStateException();
        }
        return findLockObject(0, LockType.WAITING_TO_LOCK)
                .findAny()
                .orElse(null);
    }

    /**
     * Returns the object that the thread is waiting on.
     * @throws IllegalStateException if not in the waiting state
     */
    Object waitingOn() {
        if (threadState() != Thread.State.WAITING
                && threadState() != Thread.State.TIMED_WAITING) {
            throw new IllegalStateException();
        }
        return findLockObject(0, LockType.WAITING_ON)
                .findAny()
                .orElse(null);
    }

    /**
     * Returns true if the thread owns any object monitors.
     */
    boolean ownsMonitors() {
        return Arrays.stream(locks)
                .anyMatch(lock -> lock.type() == LockType.LOCKED);
    }

    /**
     * Returns the objects that the thread locked at the given depth.
     */
    Stream<Object> ownedMonitorsAt(int depth) {
        return findLockObject(depth, LockType.LOCKED);
    }

    private Stream<Object> findLockObject(int depth, LockType type) {
        return Arrays.stream(locks)
                .filter(lock -> lock.depth() == depth
                        && lock.type() == type
                        && lock.lockObject() != null)
                .map(ThreadLock::lockObject);
    }

    /**
     * Represents information about a locking operation.
     */
    private enum LockType {
        // Park blocker
        PARKING_TO_WAIT,
        // Lock object is a class of the eliminated monitor
        ELEMINATED_SCALAR_REPLACED,
        ELEMINATED_MONITOR,
        LOCKED,
        WAITING_TO_LOCK,
        WAITING_ON,
        WAITING_TO_RELOCK,
        // No corresponding stack frame, depth is always == -1
        OWNABLE_SYNCHRONIZER
    }

    /**
     * Represents a locking operation of a thread at a specific stack depth.
     */
    private class ThreadLock {
        private static final LockType[] lockTypeValues = LockType.values(); // cache

        // set by the VM
        private int depth;
        private int typeOrdinal;
        private Object obj;

        private LockType type;

        static ThreadLock[] of(ThreadLock[] locks) {
            for (ThreadLock lock: locks) {
                lock.type = lockTypeValues[lock.typeOrdinal];
            }
            return locks;
        }

        int depth() {
            return depth;
        }

        LockType type() {
            return type;
        }

        Object lockObject() {
            if (type == LockType.ELEMINATED_SCALAR_REPLACED) {
                // we have no lock object, lock contains lock class
                return null;
            }
            return obj;
        }
    }

    private static native ThreadSnapshot create(Thread thread, boolean withLocks);
}

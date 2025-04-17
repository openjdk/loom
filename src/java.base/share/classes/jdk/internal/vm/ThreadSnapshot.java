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

    // called by the VM
    private ThreadSnapshot(StackTraceElement[] stackTrace,
                           ThreadLock[] locks,
                           String name,
                           int threadStatus) {
        this.stackTrace = stackTrace;
        this.locks = locks;
        this.name = name;
        this.threadStatus = threadStatus;
    }

    /**
     * Take a snapshot of a Thread to get all information about the thread.
     */
    static ThreadSnapshot of(Thread thread) {
        ThreadSnapshot snapshot = create(thread, true);
        if (snapshot.stackTrace == null) {
            snapshot.stackTrace = EMPTY_STACK;
        }
        if (snapshot.locks == null) {
            snapshot.locks = EMPTY_LOCKS;
        }
        if (snapshot.name == null) {
            snapshot.name = thread.getName();  // temp
        }
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
     * Returns a stream of the thread lock usage at the given stack depth.
     */
    Stream<ThreadLock> locksAtDepth(int depth) {
        return Arrays.stream(locks)
            .filter(lock -> lock.depth == depth);
    }

    /**
     * Represents information about a locking operation.
     */
    private enum LockType {
        // Park blocker
        PARKING_TO_WAIT("parked waiting on"),
        // Lock object is a class of the eliminated monitor
        ELEMINATED_SCALAR_REPLACED(null),
        ELEMINATED_MONITOR(null),
        LOCKED("locked"),
        WAITING_TO_LOCK("waiting to lock"),
        WAITING_ON("waiting on"),
        WAITING_TO_RELOCK(null),
        // No corresponding stack frame, depth is always == -1
        OWNABLE_SYNCHRONIZER(null);

        private final String message;
        LockType(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            if (message != null) {
                return message;
            } else {
                return super.toString();
            }
        }
    }

    /**
     * Represents a locking operation of a thread at a specific stack depth.
     */
    record ThreadLock(int depth, LockType type, Object obj) {
        private static final LockType[] lockTypeValues = LockType.values(); // cache

        // called by the VM
        private ThreadLock(int depth, int typeOrdinal, Object obj) {
            this(depth, lockTypeValues[typeOrdinal], obj);
        }

        String lockOperation() {
            return type.toString();
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

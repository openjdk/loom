/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package java.util.concurrent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ExecutorService that executes each task in its own thread. Threads are not
 * re-used and the number of threads/tasks is unbounded.
 *
 * This is a inefficient/simple implementation for now, it will likely be replaced.
 */
class UnboundedExecutor extends AbstractExecutorService {
    private final ThreadFactory factory;

    private static final VarHandle STATE;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            STATE = l.findVarHandle(UnboundedExecutor.class, "state", int.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }
    private volatile int state;

    // states: RUNNING -> SHUTDOWN -> TERMINATED
    private static final int RUNNING    = 0;
    private static final int SHUTDOWN   = 1;
    private static final int TERMINATED = 2;

    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final ReentrantLock terminationLock = new ReentrantLock();
    private final Condition terminationCondition = terminationLock.newCondition();

    UnboundedExecutor(ThreadFactory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    /**
     * Sets the state to TERMINATED if there are no remaining threads.
     */
    private boolean tryTerminate() {
        assert state >= SHUTDOWN;
        if (threads.isEmpty()) {
            terminationLock.lock();
            try {
                STATE.set(this, TERMINATED);
                terminationCondition.signalAll();
            } finally {
                terminationLock.unlock();
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sets the state to SHUTDOWN and attempts to terminate if not already shutdown
     * @throws SecurityException if denied by security manager
     */
    private void tryShutdownAndTerminate() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("modifyThread"));
        }
        if (STATE.compareAndSet(this, RUNNING, SHUTDOWN)) {
            tryTerminate();
        }
    }

    /**
     * Removes the thread from the set of threads and attempts to terminate
     * the executor if shutdown but not terminated.
     */
    private void onTerminate(Thread thread) {
        threads.remove(thread);
        if (state == SHUTDOWN) {
            tryTerminate();
        }
    }

    @Override
    public void shutdown() {
        tryShutdownAndTerminate();
    }

    @Override
    public List<Runnable> shutdownNow() {
        tryShutdownAndTerminate();
        threads.forEach(Thread::interrupt);
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return state >= SHUTDOWN;
    }

    @Override
    public boolean isTerminated() {
        return state >= TERMINATED;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        if (isTerminated()) {
            return true;
        } else {
            terminationLock.lock();
            try {
                if (!isTerminated()) {
                    terminationCondition.await(timeout, unit);
                }
            } finally {
                terminationLock.unlock();
            }
            return isTerminated();
        }
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task);
        if (state >= SHUTDOWN) {
            // shutdown or terminated
            reject();
        }
        Runnable wrapper = () -> {
            try {
                task.run();
            } finally {
                onTerminate(Thread.currentThread());
            }
        };
        Thread thread = factory.newThread(wrapper);
        threads.add(thread);
        boolean started = false;
        try {
            if (state == RUNNING) {
                thread.start();
                started = true;
            }
        } finally {
            if (!started) {
                onTerminate(thread);
                reject();
            }
        }
    }

    private static void reject() {
        throw new RejectedExecutionException();
    }
}

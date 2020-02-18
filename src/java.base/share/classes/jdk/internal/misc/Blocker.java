/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.misc;

import java.util.concurrent.ForkJoinPool;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

/**
 * Defines static methods to execute blocking tasks in a managed blocker. This
 * class is intended to be used by code executing in virtual thread that need
 * to support additional parallelism while pinning the carrier thread.
 */

public class Blocker {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private Blocker() { }

    /**
     * A task that returns a result and may throw an exception.
     */
    public interface BlockingCallable<V, X extends Throwable> {
        V call() throws X;
    }

    /**
     * A task that may throw an exception.
     */
    public interface BlockingRunnable<X extends Throwable> {
        void run() throws X;
    }

    private static <V, X extends Throwable> V call(BlockingCallable<V, X> task) throws Exception {
        class Block<V, X extends Throwable> implements ForkJoinPool.ManagedBlocker {
            private final BlockingCallable<V, X> task;
            private boolean done;
            private V result;

            Block(BlockingCallable<V, X> task) {
                this.task = task;
            }

            V result() {
                return result;
            }

            @Override
            public boolean block() {
                try {
                    result = task.call();
                } catch (Throwable e) {
                    U.throwException(e);
                } finally {
                    done = true;
                }
                return true;
            }

            @Override
            public boolean isReleasable() {
                return done;
            }
        }
        var block = new Block<V, X>(task);
        ForkJoinPool.managedBlock(block);
        return block.result();
    }

    /**
     * Executes a task that may block and pin the current thread.
     *
     * If the current carrier thread is in a ForkJoinPool then the pool may be expanded
     * to support additional parallelism during the call to this method.
     */
    public static <V, X extends Throwable> V managedBlock(BlockingCallable<V, X> task)  throws X {
        try {
            return JLA.executeOnCarrierThread(() -> call(task));
        }  catch (Throwable e) {
            U.throwException(e);
            return null;
        }
    }

    /**
     * Executes a task that may block and pin the current thread.
     */
    public static <X extends Throwable> void managedBlock(BlockingRunnable<X> task) throws X {
        managedBlock(() -> {
            task.run();
            return null;
        });
    }


}

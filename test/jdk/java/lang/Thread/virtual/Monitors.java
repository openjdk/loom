/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/**
 * @test
 * @summary Test virtual threads with native monitors
 * @compile --enable-preview -source ${jdk.version} Monitors.java
 * @run testng/othervm --enable-preview --add-opens java.base/java.lang=ALL-UNNAMED Monitors
 */

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class Monitors {
    static final Object lock1 = new Object();
    static final Object lock2 = new Object();

    @Test
    public void testOwner() throws Exception {
        var q = new ArrayBlockingQueue<Runnable>(5);

        Thread carrier = Thread.ofPlatform().start(() -> {
            synchronized (lock1) {
                eventLoop(q);
            }
        });

        var ex = new AtomicReference<Throwable>();
        Thread vt = spawnVirtual(ex, executor(q), () -> {
            Thread virtual = Thread.currentThread();
            assert virtual.isVirtual();
            assert !carrier.isVirtual();

            synchronized (lock2) {
                assert carrier.holdsLock(lock1);
                assert !carrier.holdsLock(lock2);

                assert virtual.holdsLock(lock2);
                assert !virtual.holdsLock(lock1);
            }
        });

        join(vt, ex);
        stop(carrier);
    }

    static void eventLoop(BlockingQueue<Runnable> q) {
        try {
            while (!Thread.interrupted())
                q.take().run();
        } catch (InterruptedException e) {}
    }

    static void stop(Thread t) throws InterruptedException {
        t.interrupt();
        t.join();
    }

    static void join(Thread t, AtomicReference<Throwable> ex) throws Exception {
        t.join();
        var ex0 = ex.get();
        if (ex0 != null)
            throw new ExecutionException("Thread " + t + " threw an uncaught exception.", ex0);
    }

    static Executor executor(BlockingQueue<Runnable> q) {
        return r -> {
            if (!q.offer(r)) throw new RejectedExecutionException();
        };
    }

    static Thread spawnVirtual(AtomicReference<Throwable> ex, Executor scheduler, Runnable task) {
        var t = newThread(scheduler, null, 0, () -> {
            try {
                task.run();
            } catch (Throwable x) {
                ex.set(x);
            }
        });
        t.start();
        return t;
    }

    private static final java.lang.reflect.Constructor<?> VTHREAD_CONSTRUCTOR;
    static {
        try {
            VTHREAD_CONSTRUCTOR = Class.forName("java.lang.VirtualThread")
                .getDeclaredConstructor(Executor.class, String.class, int.class, Runnable.class);
            VTHREAD_CONSTRUCTOR.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    static Thread newThread(Executor scheduler, String name, int characteristics, Runnable task) {
        try {
            return (Thread)VTHREAD_CONSTRUCTOR.newInstance(scheduler, name, characteristics, task);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
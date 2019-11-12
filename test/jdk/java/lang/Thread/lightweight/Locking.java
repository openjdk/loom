/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng Locking
 * @summary Test lightweight threads using java.util.concurrent locks
 */

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Locking {

    // lock/unlock
    public void testReentrantLock1() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
        });
    }

    // tryLock/unlock
    public void testReentrantLock2() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            boolean acquired = lock.tryLock();
            assertTrue(acquired);
            assertTrue(lock.isHeldByCurrentThread());
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
        });
    }

    // lock/lock/unlock/unlock
    public void testReentrantLock3() throws Exception {
        TestHelper.runInLightweightThread(() -> {
            ReentrantLock lock = new ReentrantLock();
            assertFalse(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 0);
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 1);
            lock.lock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 2);
            lock.unlock();
            assertTrue(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 1);
            lock.unlock();
            assertFalse(lock.isHeldByCurrentThread());
            assertTrue(lock.getHoldCount() == 0);
        });
    }

    // locked by dinoasur thread, lightweight thread tries to lock
    public void testReentrantLock4() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var holdsLock = new AtomicBoolean();

        Thread thread;

        // thread acquires lock
        lock.lock();
        try {
            thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
                lock.lock();  // should block
                holdsLock.set(true);
                LockSupport.park();
                lock.unlock();
                holdsLock.set(false);
            });
            thread.start();
            // give time for lightweight thread to block
            Thread.sleep(500);
            assertFalse(holdsLock.get());
        } finally {
            lock.unlock();
        }

        // lightweight thread should acquire lock, park, unpark, and then release lock
        while (!holdsLock.get()) {
            Thread.sleep(20);
        }
        LockSupport.unpark(thread);
        while (holdsLock.get()) {
            Thread.sleep(20);
        }
        thread.join();
    }

    // locked by lightweight thread, dinoasur thread tries to lock
    public void testReentrantLock5() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var thread = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            lock.lock();
            try {
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        });
        thread.start();

        // wat for lightweight thread to acquire lock
        while (!lock.isLocked()) {
            Thread.sleep(20);
        }

        // thread cannot acquire lock
        try {
            assertFalse(lock.tryLock());
        } finally {
            // lightweight thread should unlock
            LockSupport.unpark(thread);

            // thread should be able to acquire lock
            lock.lock();
            lock.unlock();
            thread.join();
        }
    }

    // lock by lightweight thread, another lightweight thread tries to lock
    public void testReentrantLock6() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        var thread1 = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            lock.lock();
            try {
                LockSupport.park();
            } finally {
                lock.unlock();
            }
        });
        thread1.start();

        // wat for lightweight thread to acquire lock
        while (!lock.isLocked()) {
            Thread.sleep(10);
        }

        var holdsLock  = new AtomicBoolean();
        var thread2 = Thread.newThread(Thread.LIGHTWEIGHT, () -> {
            lock.lock();
            holdsLock.set(true);
            LockSupport.park();
            lock.unlock();
            holdsLock.set(false);
        });
        thread2.start();

        // lightweight thread2 should block
        Thread.sleep(1000);
        assertFalse(holdsLock.get());

        // unpark lightweight thread1
        LockSupport.unpark(thread1);

        // lightweight thread2 should acquire lock
        while (!holdsLock.get()) {
            Thread.sleep(20);
        }
        // unpark lightweight thread and it should release lock
        LockSupport.unpark(thread2);
        while (holdsLock.get()) {
            Thread.sleep(20);
        }

        thread1.join();
        thread2.join();
    }
}
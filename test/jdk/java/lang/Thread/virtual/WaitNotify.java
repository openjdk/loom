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
 * @run testng WaitNotify
 * @summary Test virtual threads using Object.wait/notify
 */

import java.util.concurrent.Semaphore;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class WaitNotify {

    // virtual thread waits, notified by kernel thread
    public void testWaitNotify1() throws Exception {
        var lock = new Object();
        var ready = new Semaphore(0);
        var thread = Thread.startVirtualThread(() -> {
            synchronized (lock) {
                ready.release();
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });
        // thread invokes notify
        ready.acquire();
        synchronized (lock) {
            lock.notifyAll();
        }
        thread.join();
    }

    // kernel thread waits, notified by virtual thread
    public void testWaitNotify2() throws Exception {
        var lock = new Object();
        var ready = new Semaphore(0);
        var thread = Thread.startVirtualThread(() -> {
            ready.acquireUninterruptibly();
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            ready.release();
            lock.wait();
        }
        thread.join();
    }

    // virtual thread waits, notified by other virtual thread
    public void testWaitNotify3() throws Exception {
        var lock = new Object();
        var ready = new Semaphore(0);
        var thread1 = Thread.startVirtualThread(() -> {
            synchronized (lock) {
                ready.release();
                try {
                    lock.wait();
                } catch (InterruptedException e) { }
            }
        });
        var thread2 = Thread.startVirtualThread(() -> {
            ready.acquireUninterruptibly();
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        thread1.join();
        thread2.join();
    }

    // interrupt before Object.wait
    public void testWaitNotify4() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            t.interrupt();
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    assertTrue(false);
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
    }

    // interrupt while waiting in Object.wait
    public void testWaitNotify5() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            Thread t = Thread.currentThread();
            TestHelper.scheduleInterrupt(t, 1000);
            Object lock = new Object();
            synchronized (lock) {
                try {
                    lock.wait();
                    assertTrue(false);
                } catch (InterruptedException e) {
                    // interrupt status should be cleared
                    assertFalse(t.isInterrupted());
                }
            }
        });
    }
}

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
 * @summary Test Thread.stop() and synchronized
 * @run main ExceptionInMonitor
 */

public class ExceptionInMonitor implements Runnable {
  
    public static Object lock = new Object();
    public static volatile boolean gohead = false;
  
    public static void main(String[] args) {
        ExceptionInMonitor eim = new ExceptionInMonitor();
        Thread target = new Thread(eim);
        target.start();
        while (!gohead) {
            Thread.yield();
        }
        synchronized (lock) {
            lock.notify();
        }
        try {
            synchronized (eim) {
                target.join();
            }
        } catch (InterruptedException ie) {
            System.exit(1);
        }
    }
    @Override
    public void run() {
        try {
            foo(lock);
        } catch (Throwable e) {
            synchronized (lock) {
                System.out.println("All good!");
            }
        }
    }
    public synchronized void bar(Object o) {
        try {
            o.wait();
        } catch(InterruptedException e) {}
        throw new RuntimeException("The E");
    }
    public void foo(Object o) {
        synchronized (o) {
            gohead = true;
            bar(o);
        }
    }
}


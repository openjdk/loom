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
package java.lang;


public class MonitorTest {

    static void checkEquals(int x, int y) {
        if (x != y)
            throw new Error(x + " does not equal " + y);
    }

    static void checkNotEquals(int x, int y) {
        if (x == y)
            throw new Error(x + " does equal " + y);
    }

    static void checkEquals(Object x, Object y) {
        if (!x.equals(y))
            throw new Error(x + " does not equal " + y);
    }

    static void checkNotEquals(Object x, Object y) {
        if (x.equals(y))
            throw new Error(x + " does equal " + y);
    }

    static void check(boolean val, String msg) {
        if (!val)
            throw new Error(msg);
    }

    static void checkOwned(Monitor m, int holdCount) {
        boolean owned = m.isOwnedBy(Thread.currentThread());
        if (holdCount > 0) {
            check(owned, "Thread should be owner!");
        }
        checkEquals(holdCount, m.holdCount());
    }

    public static void main(String[] args) {
        System.out.println("testSingleUse()");
        testSingleUse();
        System.out.println("testRecursiveUse()");
        testRecursiveUse();
        System.out.println("testTimwedWait()");
        testTimedAwait();
        System.out.println("testIMSE()");
        testIMSE();

        System.out.println("testObjectRef()");
        testObjectRef();
        System.out.println("testMonitorMap()");
        testMonitorMap();
        System.out.println("testCleaner()");
        testCleaner();
    }

    static void testIMSE() {
        Object o = new Object();
        Monitor.ObjectRef or = new Monitor.ObjectRef(o);
        Monitor m = new Monitor(or);
        checkOwned(m, 0);
        Thread t = Thread.currentThread();
        try {
            m.exit(t);
            throw new Error("Missing IMSE");
        }
        catch (IllegalMonitorStateException imse) {}
        try {
            m.awaitUninterruptibly(t);
            throw new Error("Missing IMSE");
        }
        catch (IllegalMonitorStateException imse) {}
        try {
            m.signal(t);
            throw new Error("Missing IMSE");
        }
        catch (IllegalMonitorStateException imse) {}
        try {
            m.signalAll(t);
            throw new Error("Missing IMSE");
        }
        catch (IllegalMonitorStateException imse) {}
    }

    static void testTimedAwait() {
        Object o = new Object();
        Monitor.ObjectRef or = new Monitor.ObjectRef(o);
        Monitor m = new Monitor(or);
        checkOwned(m, 0);
        Thread t = Thread.currentThread();
        m.enter(t);
        checkOwned(m, 1);
        try {
            m.await(t, 10);
            checkOwned(m, 1);
        }
        catch(InterruptedException ex) {
            throw new Error("Unexpected interrupt", ex);
        }
        m.signal(t);
        checkOwned(m, 1);
        m.signalAll(t);
        checkOwned(m, 1);
        m.exit(t);
        checkOwned(m, 0);
    }



    static void testRecursiveUse() {
        Object o = new Object();
        Monitor.ObjectRef or = new Monitor.ObjectRef(o);
        Monitor m = new Monitor(or);
        checkOwned(m, 0);
        Thread t = Thread.currentThread();
        int count = 10;
        for (int i = 1; i <= count; i++) {
            m.enter(t);
            checkOwned(m, i);
        }
        for (int i = count; i > 0; i--) {
            checkOwned(m, i);
            m.exit(t);
        }
        checkOwned(m, 0);
    }

    static void testSingleUse() {
        Object o = new Object();
        Monitor.ObjectRef or = new Monitor.ObjectRef(o);
        Monitor m = new Monitor(or);
        checkOwned(m, 0);
        Thread t = Thread.currentThread();
        m.enter(t);
        checkOwned(m, 1);
        m.exit(t);
        checkOwned(m, 0);
    }

    static void testMonitorMap() {
        Monitor.ObjectRef.debugging = true;

        Object o1 = new Object();
        checkEquals(Monitor.map.size(), 0);
        Monitor m1 = Monitor.of(o1);
        checkEquals(Monitor.map.size(), 1);
        Monitor m2 = Monitor.of(o1);
        checkEquals(Monitor.map.size(), 1);
        checkEquals(m1, m2);
        Monitor.ObjectRef r1 = new Monitor.ObjectRef(o1);
        Monitor gm = Monitor.map.get(r1);
        checkEquals(m1, gm);
        r1 = new Monitor.ObjectRef(o1);
        gm = Monitor.map.get(r1);
        checkEquals(m1, gm);
        Object o2 = new Object();
        Monitor m3 = Monitor.of(o2);
        checkEquals(Monitor.map.size(), 2);
        Monitor m4 = Monitor.of(o2);
        checkEquals(Monitor.map.size(), 2);
        checkEquals(m3, m4);
        checkNotEquals(m1, m3);
        checkNotEquals(m1, m4);
        checkNotEquals(m2, m3);
        checkNotEquals(m2, m4);
        o1 = o2 = null;
        System.gc();
        System.gc();
        System.gc();
        try { Thread.sleep(5000); } catch (InterruptedException ex) {}
        if (!m1.obj.isCleared())
            System.out.println("Monitor ObjRef for o1 is not cleared!");
        else
            System.out.println("Monitor ObjRef for o1 is cleared!");
        if (!m3.obj.isCleared())
            System.out.println("Monitor ObjRef for o2 is not cleared!");
        else
            System.out.println("Monitor ObjRef for o2 is cleared!");

        checkEquals(Monitor.map.size(), 0);

        final int size = 64;
        final Object[] objs = new Object[size];
        for (int i = 0; i < size; i++) {
            objs[i] = new Object();
        }
        Runnable r = new Runnable() {
                public void run() {
                    for (int i = 0; i < size; i++) {
                        Monitor m = Monitor.of(objs[i]);
                        for (int j = 0; j < size; j++) {
                            Monitor.ObjectRef r1 = new Monitor.ObjectRef(objs[i]);
                            Monitor m2 = Monitor.map.get(r1);
                            checkEquals(m, m2);
                        }
                    }
                }
            };
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(r);
            threads[i].start();
        }

        for (int i = 0; i < threads.length; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException ie) {
            }
        }

        //        System.out.println(Monitor.map);


        for (int i = 0; i < size; i++) {
            objs[i] = null;
        }
        System.gc();
        System.gc();

        checkEquals(Monitor.map.size(), 0);
    }

    static void testCleaner() {
        Object o1 = new Object();
        // Simulate concurrency race - two ObjectRefs for one Obj
        Monitor.ObjectRef r1 = new Monitor.ObjectRef(o1);
        Monitor.ObjectRef r2 = new Monitor.ObjectRef(o1);
        Monitor m1 = Monitor.of(r1);
        Monitor m2 = Monitor.of(r2);  // r2 won't be added due to r1
        checkEquals(Monitor.map.size(), 1);
        // The cleaner registered for r2 will actually try to remove the
        // mapping for r1
        r2.createCleaner();
        checkEquals(m1, m2);
        o1 = null;
        System.gc();
        System.gc();
        checkEquals(Monitor.map.size(), 0);
    }

    static void testObjectRef() {
        Monitor.ObjectRef.debugging = true;
        Object o = new Object();
        Monitor.ObjectRef r1 = new Monitor.ObjectRef(o);
        Monitor.ObjectRef r2 = new Monitor.ObjectRef(o);
        checkEquals(r1, r2);
        checkEquals(r1.hashCode(), r2.hashCode());
    }
}

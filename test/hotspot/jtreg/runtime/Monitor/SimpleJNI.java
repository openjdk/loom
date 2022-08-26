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
 *
 */

/*
 * @test SimpleJNI
 * @summary This does a sanity test of JNI monitor enter/exit
 * @library /testlibrary /test/lib
 * @build SimpleJNI
 * @run main/native SimpleJNI
 */

public class SimpleJNI {
    static {
        System.loadLibrary("SimpleJNI");
    }

    static native boolean jniLock(Object o);
    static native boolean jniUnlock(Object o);

    static void checkLocked(Object o) {
        if (!Thread.holdsLock(o))
            throw new Error("Thread doesn't hold lock");
    }

    static void checkNotLocked(Object o) {
        if (Thread.holdsLock(o))
            throw new Error("Thread still holds lock");
    }


    public static void main(String[] args) throws Exception {
        Object o = new Object();
        jniLock(o);
        checkLocked(o);
        jniUnlock(o);
        checkNotLocked(o);
        try {
            jniUnlock(o);
            throw new Error("Illegal JNI unlock permitted!");
        } catch (IllegalMonitorStateException expected) {}
        System.out.println("Part 1: ok");

        o = new Object();
        synchronized(o) {
            jniLock(o);
            checkLocked(o);
            jniUnlock(o);
            checkLocked(o);
        }
        checkNotLocked(o);
        System.out.println("Part 2: ok");

        o = new Object();
        jniLock(o);
        synchronized(o) {
            checkLocked(o);
        }
        checkLocked(o);
        jniUnlock(o);
        checkNotLocked(o);
        System.out.println("Part 3: ok");

        o = new Object();
        jniLock(o);
        synchronized(o) {
            jniLock(o);
            jniUnlock(o);
            checkLocked(o);
        }
        checkLocked(o);
        jniUnlock(o);
        checkNotLocked(o);
        System.out.println("Part 4: ok");

        o = new Object();
        synchronized(o) {
            jniLock(o);
            synchronized(o) {
                jniLock(o);
                checkLocked(o);
                jniUnlock(o);
                checkLocked(o);
            }
            checkLocked(o);
            jniUnlock(o);
            checkLocked(o);
        }
        checkNotLocked(o);
        System.out.println("Part 5: ok");

        // Test illegal JNI unlocks (no matching JNI lock)

        // case 1: not inflated, not Java locked
        o = new Object();
        try {
            jniUnlock(o);
            throw new Error("Illegal JNI unlock permitted!");
        } catch (IllegalMonitorStateException expected) {}

        // case 2: not inflated, Java locked
        o = new Object();
        synchronized(o) {
            try {
                jniUnlock(o);
                throw new Error("Illegal JNI unlock permitted!");
            } catch (IllegalMonitorStateException expected) {}
            checkLocked(o);
        }
        checkNotLocked(o);

        // case 3: inflated, not Java locked
        o = new Object();
        synchronized(o) {
            o.wait(1);
        }
        try {
            jniUnlock(o);
            throw new Error("Illegal JNI unlock permitted!");
        } catch (IllegalMonitorStateException expected) {}

        // case 4: inflated, Java locked
        o = new Object();
        synchronized(o) {
            o.wait(1);
            try {
                jniUnlock(o);
                throw new Error("Illegal JNI unlock permitted!");
            } catch (IllegalMonitorStateException expected) {}
            checkLocked(o);
        }
        checkNotLocked(o);

        System.out.println("Part 6: ok");

        // Test the jniList management in each thread by locking a number of
        // objects and then unlocking in different orders, so that we verify
        // the search and removal code. As the block size is 16 test up to 33
        // objects. This has to happen in a new thread each time of course.
        for (int i = 1; i <= 33; i++) {
            final int ii = i;
            for (int j = 0; j < i; j++) {
                final int jj = j;
                Thread t = new Thread() {
                        public void run() {
                            testLocking(ii, jj);
                    }
                    };
                t.start();
                t.join();
            }
        }
    }

    static void testLocking(int n, int firstUnlock) {
        // First create and lock n objects
        Object[] objs = new Object[n];
        for (int i = 0; i < n; i++) {
            objs[i] = new Object();
            jniLock(objs[i]);
            checkLocked(objs[i]);
        }

        jniUnlock(objs[firstUnlock]);
        checkNotLocked(objs[firstUnlock]);

        // Now unlock the rest
        for (int i = 0; i < n; i++) {
            if (i != firstUnlock) {
                jniUnlock(objs[i]);
                checkNotLocked(objs[i]);
            }
        }
    }
}

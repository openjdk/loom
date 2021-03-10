/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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

import java.io.PrintStream;

/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jvmti/FieldModification/fieldmod001.
 * VM Testbase keywords: [quick, jpda, jvmti, noras, quarantine]
 * VM Testbase comments: 8016181
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercise JVMTI event callback function FieldModification.
 *     The test checks if the parameters of the function contain the
 *     expected values.
 * COMMENTS
 *     Fixed according to 4669812 bug.
 *     Ported from JVMDI.
 *
 * @library /test/lib
 * @compile fieldmod01a.jasm
 * @run main/othervm/native -agentlib:fieldmod01 fieldmod01
 */

public class fieldmod01 {

    final static int JCK_STATUS_BASE = 95;

    static {
        try {
            System.loadLibrary("fieldmod01");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load fieldmod01 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    static volatile int result;
    native static void getReady(Object o1, Object o2, int a1[], int a2[]);
    native static int check();

    static Object obj1 = new Object();
    static Object obj2 = new Object();
    static int arr1[] = new int[1];
    static int arr2[] = new int[2];
    
    public static void main(String args[]) {
        testKernel();
        testVirtual();
    }
    public static void testVirtual() {
        Thread thread = Thread.startVirtualThread(() -> {
            getReady(obj1, obj2, arr1, arr2);
            fieldmod01a t = new fieldmod01a();
            t.run();
            result = check();
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (result != 0) {
            throw new RuntimeException("check failed with result " + result);
        }
    }
    public static void testKernel() {
        getReady(obj1, obj2, arr1, arr2);
        fieldmod01a t = new fieldmod01a();
        t.run();
        result = check();
        if (result != 0) {
            throw new RuntimeException("check failed with result " + result);
        }
    }
}

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

/*
 * @test
 * @bug 8288683
 * @library /test/lib
 * @summary Test that And nodes are added to the CCP worklist if they have an LShift as input.
 * @run main/othervm -Xbatch compiler.c2.TestAndShiftZeroCCP
 */
package compiler.c2;

import jdk.test.lib.Asserts;

public class TestAndShiftZeroCCP {
    static int iFld = 0xfffff;

    public static void main(String[] strArr) {
        for (int i = 0; i < 10000; i++) {
            Asserts.assertEQ(testAndI(), 224);
            Asserts.assertEQ(testAndL(), 224L);
            Asserts.assertEQ(testAndLConvI2L(), 224L);
        }
    }

    static int testAndI() {
        int x = 10;
        int y = iFld;
        int z = 3;
        int q;
        for (int i = 62; i < 70; ++i) {
            q = y << i;
            for (int j = 0; j < 8; j++) {
                z += i;
            }
            z = q & 0xff;
        }
        return z;
    }

    static long testAndL() {
        long x = 10;
        long y = iFld;
        long z = 3;
        long q;
        for (int i = 62; i < 70; ++i) {
            q = y << i;
            for (int j = 0; j < 8; j++) {
                z += i;
            }
            z = q & 0xff;
        }
        return z;
    }

    static long testAndLConvI2L() {
        long x = 10;
        long y = iFld;
        long z = 3;
        long q;
        for (int i = 62; i < 70; ++i) {
            q = y << i;
            for (int j = 0; j < 8; j++) {
                z += i;
            }
            z = q & 0xff;
        }
        return z;
    }
}

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
 * @bug 8287223
 * @library /test/lib / patches
 *
 * @build java.base/java.lang.invoke.MethodHandleHelper
 * @run main/bootclasspath/othervm -Xbatch -XX:CompileCommand=compileonly,*::test -XX:-TieredCompilation                         compiler.jsr292.NullConstantMHReceiver
 * @run main/bootclasspath/othervm -Xbatch -XX:CompileCommand=compileonly,*::test -XX:+TieredCompilation -XX:TieredStopAtLevel=1 compiler.jsr292.NullConstantMHReceiver
 */

package compiler.jsr292;

import java.lang.invoke.MethodHandleHelper;

public class NullConstantMHReceiver {
    static void test() throws Throwable {
        MethodHandleHelper.invokeBasicL(null);
    }

    public static void main(String[] args) throws Throwable {
        for (int i = 0; i < 15000; i++) {
            try {
                test();
            } catch (NullPointerException e) {
                // expected
                continue;
            }
            throw new AssertionError("NPE wasn't thrown");
        }
        System.out.println("TEST PASSED");
    }
}

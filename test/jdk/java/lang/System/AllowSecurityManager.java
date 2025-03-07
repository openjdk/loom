/*
 * Copyright (c) 2018, 2024, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8191053 8270380
 * @summary Test that the allow/disallow options are ignored
 * @library /test/lib
 * @run main/othervm AllowSecurityManager
 * @run main/othervm -Djava.security.manager=disallow AllowSecurityManager
 * @run main/othervm AllowSecurityManager extra
 */

import jdk.test.lib.process.ProcessTools;

public class AllowSecurityManager {

    public static void main(String args[]) throws Exception {
        if (args.length > 0) {
            // Any other system property value would fail
            if (args[0].equals("extra")) {
                ProcessTools.executeTestJava("-Djava.security.manager=other",
                                "AllowSecurityManager", "test")
                        .shouldHaveExitValue(1)
                        .shouldContain("Enabling a Security Manager is not supported.");
            } else {
                // The sub-process is here
            }
        } else {
            try {
                System.setSecurityManager(new SecurityManager());
                throw new Exception("System.setSecurityManager did not " +
                        "throw UnsupportedOperationException");
            } catch (UnsupportedOperationException uoe) {
                // Will always happen
            }
        }
    }
}

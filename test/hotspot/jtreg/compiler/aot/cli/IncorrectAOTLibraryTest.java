/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @requires vm.aot
 * @library /test/lib /testlibrary /
 * @modules java.base/jdk.internal.misc
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.lib.helpers.ClassFileInstaller
 * @run driver compiler.aot.cli.IncorrectAOTLibraryTest
 * @summary check if incorrect aot library is handled properly
 */

package compiler.aot.cli;

import java.nio.file.Paths;

public class IncorrectAOTLibraryTest {
    private static final String OPTION
            = "-XX:AOTLibrary=./" + Paths.get("jdk", "test", "lib", "helpers", "ClassFileInstaller.class").toString();
    private static final String[] EXPECTED_MESSAGES = new String[] {
        "error opening file:"
    };

    public static void main(String args[]) {
        AotLibraryNegativeBase.launchTest(OPTION, EXPECTED_MESSAGES);
    }
}

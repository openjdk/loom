/*
 * Copyright (c) 2016, 2024, Oracle and/or its affiliates. All rights reserved.
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

package gc;

/*
 * @test TestNumWorkerOutputG1
 * @bug 8165292
 * @summary Check that when PrintGCDetails is enabled, gc,task output is printed only once per collection.
 * @requires vm.gc.G1
 * @modules java.base/jdk.internal.misc
 * @library /test/lib
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver gc.TestNumWorkerOutput UseG1GC
 */

import jdk.test.whitebox.WhiteBox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestNumWorkerOutput {

    public static void checkPatternOnce(String pattern, String what) throws Exception {
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(what);

        if (!m.find()) {
            throw new RuntimeException("Could not find pattern " + pattern + " in output");
        }
        if (m.find()) {
            throw new RuntimeException("Could find pattern " + pattern + " in output more than once");
        }
    }

    public static void runTest(String gcArg) throws Exception {
        OutputAnalyzer output = ProcessTools.executeLimitedTestJava(
            "-Xbootclasspath/a:.",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+WhiteBoxAPI",
            "-XX:+" + gcArg,
            "-Xmx10M",
            "-XX:+PrintGCDetails",
            GCTest.class.getName());

        output.shouldHaveExitValue(0);

        System.out.println(output.getStdout());

        String stdout = output.getStdout();

        checkPatternOnce(".*[info.*].*[gc,task.*].*GC\\(0\\) .*Using \\d+ workers of \\d+ for evacuation.*", stdout);
    }

    public static void main(String[] args) throws Exception {
        runTest(args[0]);
    }

    static class GCTest {
        private static final WhiteBox WB = WhiteBox.getWhiteBox();

        public static Object holder;

        public static void main(String [] args) {
            holder = new byte[100];
            WB.youngGC();
            System.out.println(holder);
        }
    }
}

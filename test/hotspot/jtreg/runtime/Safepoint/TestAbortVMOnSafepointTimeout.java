/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2019 SAP SE. All rights reserved.
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

import jdk.test.lib.*;
import jdk.test.lib.process.*;
import sun.hotspot.WhiteBox;

/*
 * @test TestAbortVMOnSafepointTimeout
 * @summary Check if VM can kill thread which doesn't reach safepoint.
 * @bug 8219584 8227528
 * @library /testlibrary /test/lib
 * @build TestAbortVMOnSafepointTimeout
 * @run driver ClassFileInstaller sun.hotspot.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI TestAbortVMOnSafepointTimeout
 */

public class TestAbortVMOnSafepointTimeout {

    public static void main(String[] args) throws Exception {
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
                "-Xbootclasspath/a:.",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                "-XX:+SafepointTimeout",
                "-XX:+SafepointALot",
                "-XX:+AbortVMOnSafepointTimeout",
                "-XX:SafepointTimeoutDelay=50",
                "-XX:GuaranteedSafepointInterval=1",
                "-XX:-CreateCoredumpOnCrash",
                "-Xms64m",
                "TestAbortVMOnSafepointTimeout$Test",
                "999" /* 999 is max unsafe sleep */
        );

        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        output.shouldMatch("Timed out while spinning to reach a safepoint.");
        if (Platform.isWindows()) {
            output.shouldMatch("Safepoint sync time longer than");
        } else {
            output.shouldMatch("SIGILL");
            if (Platform.isLinux()) {
                output.shouldMatch("(sent by kill)");
            }
        }
        output.shouldNotHaveExitValue(0);
    }

    public static class Test {
        public static void main(String[] args) throws Exception {
            Integer waitTime = Integer.parseInt(args[0]);
            WhiteBox wb = WhiteBox.getWhiteBox();
            // Loop here to cause a safepoint timeout.
            while (true) {
                wb.waitUnsafe(waitTime);
            }
        }
    }
}

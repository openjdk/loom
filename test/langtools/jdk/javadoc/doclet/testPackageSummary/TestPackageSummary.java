/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8189841 8253117
 * @summary Error in alternate row coloring in package-summary files
 * @library  ../../lib/
 * @modules jdk.javadoc/jdk.javadoc.internal.tool
 * @build    javadoc.tester.* TestPackageSummary
 * @run main TestPackageSummary
 */

import javadoc.tester.JavadocTester;

public class TestPackageSummary extends JavadocTester {

    public static void main(String... args) throws Exception {
        TestPackageSummary tester = new TestPackageSummary();
        tester.runTests();
    }

    @Test
    public void testStripes() {
        javadoc("-d", "out",
                "-sourcepath", testSrc,
                "pkg");
        checkExit(Exit.OK);

        checkOutput("pkg/package-summary.html", true,
                """
                    <div class="col-first even-row-color"><a href="C0.html" title="class in pkg">C0</a></div>
                    <div class="col-last even-row-color">&nbsp;</div>
                    <div class="col-first odd-row-color"><a href="C1.html" title="class in pkg">C1</a></div>
                    <div class="col-last odd-row-color">&nbsp;</div>
                    <div class="col-first even-row-color"><a href="C2.html" title="class in pkg">C2</a></div>
                    <div class="col-last even-row-color">&nbsp;</div>
                    <div class="col-first odd-row-color"><a href="C3.html" title="class in pkg">C3</a></div>
                    <div class="col-last odd-row-color">&nbsp;</div>
                    <div class="col-first even-row-color"><a href="C4.html" title="class in pkg">C4</a></div>
                    <div class="col-last even-row-color">&nbsp;</div>
                    """
        );
    }
}


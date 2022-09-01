/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @test
 * @bug 8274944 8276184
 * @summary VM should not crash during CDS dump when a lambda proxy class
 *          contains an old version of interface.
 * @requires vm.cds
 * @library /test/lib /test/hotspot/jtreg/runtime/cds/appcds
 *          /test/hotspot/jtreg/runtime/cds/appcds/test-classes
 *          /test/hotspot/jtreg/runtime/cds/appcds/dynamicArchive/test-classes
 * @build LambdaContainsOldInfApp jdk.test.whitebox.WhiteBox OldProvider LambdaVerification
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar lambda_contains_old_inf.jar LambdaVerification
 *             LambdaContainsOldInfApp OldProvider
 * @run driver jdk.test.lib.helpers.ClassFileInstaller -jar WhiteBox.jar jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. LambdaContainsOldInf
 */

import jdk.test.lib.helpers.ClassFileInstaller;

public class LambdaContainsOldInf extends DynamicArchiveTestBase {
    public static void main(String[] args) throws Exception {
        runTest(LambdaContainsOldInf::test);
    }

    static void test() throws Exception {
        String topArchiveName = getNewArchiveName();
        String appJar = ClassFileInstaller.getJarPath("lambda_contains_old_inf.jar");
        String mainClass = "LambdaContainsOldInfApp";
        String wbJar = ClassFileInstaller.getJarPath("WhiteBox.jar");
        String use_whitebox_jar = "-Xbootclasspath/a:" + wbJar;

        String[] mainArgs = { "dummy", "addLambda" };

        for (String mainArg : mainArgs) {

            dump(topArchiveName,
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                "-Xlog:class+load=debug,cds=debug,cds+dynamic=info",
                use_whitebox_jar,
                "-cp", appJar, mainClass, mainArg)
                .assertNormalExit(output -> {
                    output.shouldContain("Skipping OldProvider: Old class has been linked")
                          .shouldMatch("Skipping.LambdaContainsOldInfApp[$][$]Lambda[$].*0x.*:.*Old.class.has.been.linked")
                          .shouldHaveExitValue(0);
            });

            run(topArchiveName,
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                use_whitebox_jar,
                "-Xlog:class+load=debug",
                "-cp", appJar, mainClass, mainArg)
                .assertNormalExit(output -> {
                    output.shouldContain("[class,load] LambdaContainsOldInfApp source: shared objects file (top)")
                          .shouldMatch(".class.load. OldProvider.source:.*lambda_contains_old_inf.jar")
                          .shouldMatch(".class.load. LambdaContainsOldInfApp[$][$]Lambda[$].*/0x.*source:.*LambdaContainsOldInf")
                          .shouldHaveExitValue(0);
            });
        }
    }
}

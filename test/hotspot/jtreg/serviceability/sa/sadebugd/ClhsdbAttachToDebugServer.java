/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021 NTT DATA.
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

import jdk.test.lib.JDKToolLauncher;
import jdk.test.lib.apps.LingeredApp;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.SA.SATestUtils;

import jtreg.SkippedException;

/**
 * @test
 * @bug 8262520
 * @summary Test clhsdb connect, detach, reattach commands
 * @requires vm.hasSA
 * @requires os.family != "windows"
 * @library /test/lib
 * @run main/othervm ClhsdbAttachToDebugServer
 */

public class ClhsdbAttachToDebugServer {

    public static void main(String[] args) throws Exception {
        SATestUtils.skipIfCannotAttach(); // throws SkippedException if attach not expected to work.

        if (SATestUtils.needsPrivileges()) {
            // This tests has issues if you try adding privileges on OSX. The debugd process cannot
            // be killed if you do this (because it is a root process and the test is not), so the destroy()
            // call fails to do anything, and then waitFor() will time out. If you try to manually kill it with
            // a "sudo kill" command, that seems to work, but then leaves the LingeredApp it was
            // attached to in a stuck state for some unknown reason, causing the stopApp() call
            // to timeout. For that reason we don't run this test when privileges are needed. Note
            // it does appear to run fine as root, so we still allow it to run on OSX when privileges
            // are not required.
            throw new SkippedException("Cannot run this test on OSX if adding privileges is required.");
        }

        System.out.println("Starting ClhsdbAttachToDebugServer test");

        LingeredApp theApp = null;
        DebugdUtils debugd = null;
        try {
            theApp = LingeredApp.startApp();
            System.out.println("Started LingeredApp with pid " + theApp.getPid());
            debugd = new DebugdUtils(null);
            debugd.attach(theApp.getPid());

            JDKToolLauncher jhsdbLauncher = JDKToolLauncher.createUsingTestJDK("jhsdb");
            jhsdbLauncher.addToolArg("clhsdb");

            Process jhsdb = (SATestUtils.createProcessBuilder(jhsdbLauncher)).start();
            OutputAnalyzer out = new OutputAnalyzer(jhsdb);

            try (PrintStream console = new PrintStream(jhsdb.getOutputStream(), true)) {
                console.println("echo true");
                console.println("verbose true");
                console.println("attach localhost");
                console.println("class java.lang.Object");
                console.println("detach");
                console.println("reattach");
                console.println("class java.lang.String");
                console.println("quit");
            }

            jhsdb.waitFor();
            System.out.println(out.getStdout());
            System.err.println(out.getStderr());

            out.stderrShouldBeEmptyIgnoreDeprecatedWarnings();
            out.shouldMatch("^java/lang/Object @0x[0-9a-f]+$"); // for "class java.lang.Object"
            out.shouldMatch("^java/lang/String @0x[0-9a-f]+$"); // for "class java.lang.String"
            out.shouldHaveExitValue(0);

            // This will detect most SA failures, including during the attach.
            out.shouldNotMatch("^sun.jvm.hotspot.debugger.DebuggerException:.*$");
            // This will detect unexpected exceptions, like NPEs and asserts, that are caught
            // by sun.jvm.hotspot.CommandProcessor.
            out.shouldNotMatch("^Error: .*$");
        } catch (SkippedException se) {
            throw se;
        } catch (Exception ex) {
            throw new RuntimeException("Test ERROR " + ex, ex);
        } finally {
            if (debugd != null) {
                debugd.detach();
            }
            LingeredApp.stopApp(theApp);
        }
        System.out.println("Test PASSED");
    }
}

/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test dumping with limited metaspace with loading of JVMCI related classes.
 *          VM should not crash but CDS dump will abort upon failure in allocating metaspace.
 * @requires vm.cds & vm.graal.enabled & vm.compMode == "Xmixed"
 * @library /test/lib
 * @build UseAppCDS_Test
 * @run driver ClassFileInstaller -jar test.jar UseAppCDS_Test
 * @run main/othervm -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
 *      -XX:+TieredCompilation -XX:+UseJVMCICompiler -Djvmci.Compiler=graal
 *      GraalWithLimitedMetaspace
 */

import jdk.test.lib.cds.CDSOptions;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.process.OutputAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.io.*;

public class GraalWithLimitedMetaspace {

    // Class UseAppCDS_Test is loaded by the App loader

    static final String TEST_OUT = "UseAppCDS_Test.main--executed";

    private static final String TESTJAR = "./test.jar";
    private static final String TESTNAME = "UseAppCDS_Test";
    private static final String TESTCLASS = TESTNAME + ".class";

    private static final String CLASSLIST_FILE = "./GraalWithLimitedMetaspace.classlist";
    private static final String ARCHIVE_FILE = "./GraalWithLimitedMetaspace.jsa";
    private static final String BOOTCLASS = "java.lang.Class";

    public static void main(String[] args) throws Exception {

        // dump loaded classes into a classlist file
        dumpLoadedClasses(new String[] { BOOTCLASS, TESTNAME });


        // create an archive using the classlist
        dumpArchive();

    }

    public static List<String> toClassNames(String filename) throws IOException {
        ArrayList<String> classes = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(filename)))) {
            for (; ; ) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                classes.add(line.replaceAll("/", "."));
            }
        }
        return classes;
    }

    static void dumpLoadedClasses(String[] expectedClasses) throws Exception {
        CDSTestUtils.dumpClassList(
                       CLASSLIST_FILE,
                       // trigger JVMCI runtime init so that JVMCI classes will be
                       // included in the classlist
                       "-XX:+UnlockExperimentalVMOptions",
                       "-XX:+EnableJVMCI",
                       "-XX:+EagerJVMCI",
                       "-cp",
                       TESTJAR,
                       TESTNAME,
                       TEST_OUT)
            .assertNormalExit(output -> {
                output.shouldContain(TEST_OUT);
            });

        List<String> dumpedClasses = toClassNames(CLASSLIST_FILE);

        for (String clazz : expectedClasses) {
            if (!dumpedClasses.contains(clazz)) {
                throw new RuntimeException(clazz + " missing in " +
                                           CLASSLIST_FILE);
            }
        }
    }

    static void dumpArchive() throws Exception {
        CDSOptions opts = (new CDSOptions())
            .addPrefix("-cp",
                       TESTJAR,
                       "-XX:SharedClassListFile=" + CLASSLIST_FILE,
                       "-Xlog:cds",
                       "-Xshare:dump",
                       "-XX:MetaspaceSize=12M",
                       "-XX:MaxMetaspaceSize=12M")
            .setArchiveName(ARCHIVE_FILE);
        OutputAnalyzer output = CDSTestUtils.createArchive(opts);
        int exitValue = output.getExitValue();
        if (exitValue == 1) {
            output.shouldContain("Failed allocating metaspace object type");
        } else if (exitValue == 0) {
            output.shouldContain("Loading classes to share");
        } else {
            throw new RuntimeException("Unexpected exit value " + exitValue);
        }
    }
}

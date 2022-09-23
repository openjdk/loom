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

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;

import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.Optional;

/*
 * @test
 * @summary Throw error if default java.security file is missing
 * @bug 8155246 8292297
 * @library /test/lib
 * @run main ConfigFileTest
 */
public class ConfigFileTest {

    public static void main(String[] args) throws Exception {
        Path copyJdkDir = Path.of("./jdk-8155246-tmpdir");
        Path copiedJava = Optional.of(
                        Path.of(copyJdkDir.toString(), "bin", "java"))
                .orElseThrow(() -> new RuntimeException("Unable to locate new JDK")
                );

        if (args.length == 1) {
            // set up is complete. Run code to exercise loading of java.security
            Provider[] provs = Security.getProviders();
            System.out.println(Arrays.toString(provs) + "NumProviders: " + provs.length);
        } else {
            Files.createDirectory(copyJdkDir);
            Path jdkTestDir = Path.of(Optional.of(System.getProperty("test.jdk"))
                            .orElseThrow(() -> new RuntimeException("Couldn't load JDK Test Dir"))
            );

            copyJDK(jdkTestDir, copyJdkDir);
            String extraPropsFile = Path.of(System.getProperty("test.src"), "override.props").toString();

            // exercise some debug flags while we're here
            // regular JDK install - should expect success
            exerciseSecurity(0, "java",
                    copiedJava.toString(), "-cp", System.getProperty("test.classes"),
                    "-Djava.security.debug=all", "-Djavax.net.debug=all", "ConfigFileTest", "runner");

            // given an overriding security conf file that doesn't exist, we shouldn't
            // overwrite the properties from original/master security conf file
            exerciseSecurity(0, "SUN version",
                    copiedJava.toString(), "-cp", System.getProperty("test.classes"),
                    "-Djava.security.debug=all", "-Djavax.net.debug=all",
                    "-Djava.security.properties==file:///" + extraPropsFile + "badFileName",
                    "ConfigFileTest", "runner");

            // test JDK launch with customized properties file
            exerciseSecurity(0, "NumProviders: 6",
                    copiedJava.toString(), "-cp", System.getProperty("test.classes"),
                    "-Djava.security.debug=all", "-Djavax.net.debug=all",
                    "-Djava.security.properties==file:///" + extraPropsFile,
                    "ConfigFileTest", "runner");

            // delete the master conf file
            Files.delete(Path.of(copyJdkDir.toString(), "conf",
                    "security","java.security"));

            // launch JDK without java.security file being present or specified
            exerciseSecurity(1, "Error loading java.security file",
                    copiedJava.toString(), "-cp", System.getProperty("test.classes"),
                    "-Djava.security.debug=all", "-Djavax.net.debug=all",
                    "ConfigFileTest", "runner");

            // test the override functionality also. Should not be allowed since
            // "security.overridePropertiesFile=true" Security property is missing.
            exerciseSecurity(1, "Error loading java.security file",
                    copiedJava.toString(), "-cp", System.getProperty("test.classes"),
                    "-Djava.security.debug=all", "-Djavax.net.debug=all",
                    "-Djava.security.properties==file:///" + extraPropsFile, "ConfigFileTest", "runner");
        }
    }

    private static void exerciseSecurity(int exitCode, String output, String... args) throws Exception {
        ProcessBuilder process = new ProcessBuilder(args);
        OutputAnalyzer oa = ProcessTools.executeProcess(process);
        oa.shouldHaveExitValue(exitCode).shouldContain(output);
    }

    private static void copyJDK(Path src, Path dst) throws Exception {
        Files.walk(src)
            .skip(1)
            .forEach(file -> {
                try {
                    Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException ioe) {
                    throw new UncheckedIOException(ioe);
                }
            });
    }
}

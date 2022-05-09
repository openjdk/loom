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

/*
 * @test
 * @bug 8155701
 * @summary Ensure that compiler surfaces diagnostics about inaccessible class
 * @library /tools/lib
 * @modules jdk.compiler/com.sun.tools.javac.api
 *          jdk.compiler/com.sun.tools.javac.main
 * @run main MissingAnnotationClassFile
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import toolbox.*;

public class MissingAnnotationClassFile {

    public static void main(String [] args) throws Exception {

        ToolBox tb = new ToolBox();
        Path base = Paths.get(".");
        Path testSrc = base.resolve("test-src");
        tb.createDirectories(testSrc);
        Path libSrc = testSrc.resolve("lib-src");
        tb.createDirectories(libSrc);
        tb.writeJavaFiles(libSrc, "package lib;\n" +
                                  "public @interface I {\n" +
                                  "    String value();\n" +
                                  "}\n",

                                  "package lib;\n" +
                                  "@I(\"Foo\")\n" +
                                  "public class Foo {}\n");
        Path libClasses = base.resolve("lib-classes");
        tb.createDirectories(libClasses);
        new JavacTask(tb).outdir(libClasses.toString())
                         .sourcepath(libSrc.toString())
                         .files(tb.findJavaFiles(libSrc))
                         .run()
                         .writeAll();

        Files.delete(libClasses.resolve("lib/I.class"));

        tb.writeJavaFiles(testSrc, "import lib.Foo;\n" +
                                   "public class Bar {\n" +
                                   "@lib.I(\"Bar\")\n" +
                                   "public void bar() {}\n" +
                                   "}\n");
        Path testClasses = base.resolve("test-classes");
        tb.createDirectories(testClasses);

        Path bar = testSrc.resolve("Bar.java");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> errors = new ArrayList<>();

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            com.sun.source.util.JavacTask task = (com.sun.source.util.JavacTask)
                    compiler.getTask(null,
                                     null,
                                     d -> errors.add(d.getCode()),
                                     Arrays.asList("-XDrawDiagnostics",
                                                   "-classpath",
                                                   libClasses.toString()),
                                     null,
                                     fm.getJavaFileObjects(bar));
            task.parse();
            task.analyze();
            task.generate();
        }

        List<String> expected = Arrays.asList("compiler.err.cant.resolve.location");

        if (!expected.equals(errors)) {
            throw new IllegalStateException("Expected error not found!");
        }
    }
}

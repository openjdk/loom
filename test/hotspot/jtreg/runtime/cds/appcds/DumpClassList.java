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
 *
 */

/*
 * @test
 * @summary DumpLoadedClassList should exclude generated classes, classes in bootclasspath/a and
 *          --patch-module.
 * @requires vm.cds
 * @library /test/lib
 * @compile test-classes/ArrayListTest.java
 * @run driver DumpClassList
 */

import jdk.test.lib.cds.CDSOptions;
import jdk.test.lib.cds.CDSTestUtils;
import jdk.test.lib.compiler.InMemoryJavaCompiler;
import jdk.test.lib.helpers.ClassFileInstaller;

public class DumpClassList {
    public static void main(String[] args) throws Exception {
        // build The app
        String[] appClass = new String[] {"ArrayListTest"};
        String classList = "app.list";

        JarBuilder.build("app", appClass[0]);
        String appJar = TestCommon.getTestJar("app.jar");

        // build patch-module
        String source = "package java.lang; "                       +
                        "public class NewClass { "                  +
                        "    static { "                             +
                        "        System.out.println(\"NewClass\"); "+
                        "    } "                                    +
                        "}";

        ClassFileInstaller.writeClassToDisk("java/lang/NewClass",
             InMemoryJavaCompiler.compile("java.lang.NewClass", source, "--patch-module=java.base"),
             System.getProperty("test.classes"));

        String patchJar = JarBuilder.build("javabase", "java/lang/NewClass");

        // build bootclasspath/a
        String source2 = "package boot.append; "                 +
                        "public class Foo { "                    +
                        "    static { "                          +
                        "        System.out.println(\"Foo\"); "  +
                        "    } "                                 +
                        "}";

        ClassFileInstaller.writeClassToDisk("boot/append/Foo",
             InMemoryJavaCompiler.compile("boot.append.Foo", source2),
             System.getProperty("test.classes"));

        String appendJar = JarBuilder.build("bootappend", "boot/append/Foo");

        // dump class list
        CDSTestUtils.dumpClassList(classList,
                                   "--patch-module=java.base=" + patchJar,
                                   "-Xbootclasspath/a:" + appendJar,
                                   "-cp",
                                   appJar,
                                   appClass[0])
            .assertNormalExit(output -> {
                output.shouldContain("hello world");
            });

        CDSOptions opts = (new CDSOptions())
            .setClassList(appClass)
            .addPrefix("-cp", appJar,
                       "-Xbootclasspath/a:" + appendJar,
                       "-Xlog:class+load",
                       "-XX:SharedClassListFile=" + classList);
        CDSTestUtils.createArchiveAndCheck(opts)
            .shouldNotContain("Preload Warning: Cannot find java/lang/invoke/LambdaForm")
            .shouldNotContain("Preload Warning: Cannot find boot/append/Foo")
            .shouldContain("[info][class,load] boot.append.Foo");
    }
}

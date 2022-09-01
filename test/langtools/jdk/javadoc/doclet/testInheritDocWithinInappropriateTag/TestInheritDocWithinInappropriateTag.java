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
 * @bug 8284299 8287379
 * @library /tools/lib ../../lib
 * @modules jdk.javadoc/jdk.javadoc.internal.tool
 * @build toolbox.ToolBox javadoc.tester.*
 * @run main TestInheritDocWithinInappropriateTag
 */

import java.nio.file.Path;

import javadoc.tester.JavadocTester;
import toolbox.ToolBox;

public class TestInheritDocWithinInappropriateTag extends JavadocTester {

    public static void main(String... args) throws Exception {
        new TestInheritDocWithinInappropriateTag()
                .runTests(m -> new Object[]{Path.of(m.getName())});
    }

    private final ToolBox tb = new ToolBox();

    @Test
    public void test(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                """
                        public class A {
                            /**
                             * A.x().
                             */
                            public void x() { }
                        }
                        """,
                """
                        public class B extends A {
                            /**
                             * {@summary {@inheritDoc}}
                             *
                             * {@link Object#hashCode() {@inheritDoc}}
                             * {@linkplain Object#hashCode() {@inheritDoc}}
                             *
                             * {@index term {@inheritDoc}}
                             */
                            @Override
                            public void x() { }
                        }
                        """);
        javadoc("-Xdoclint:none",
                "-d", base.resolve("out").toString(),
                src.resolve("A.java").toString(),
                src.resolve("B.java").toString());
        checkExit(Exit.OK);
        new OutputChecker(Output.OUT).setExpectOrdered(false).check(
                """
                        warning: @inheritDoc cannot be used within this tag
                             * {@summary {@inheritDoc}}
                               ^
                        """,
                """
                        warning: @inheritDoc cannot be used within this tag
                             * {@link Object#hashCode() {@inheritDoc}}
                               ^
                        """,
                """
                        warning: @inheritDoc cannot be used within this tag
                             * {@linkplain Object#hashCode() {@inheritDoc}}
                               ^
                        """,
                """
                        warning: @inheritDoc cannot be used within this tag
                             * {@index term {@inheritDoc}}
                               ^
                        """);
    }

    @Test
    public void testClassOrInterfaceMainDescription(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                """
                        /** Class A. */
                        public class A { }
                        """,
                """
                        /** {@inheritDoc} */
                        public class B extends A { }
                        """,
                """
                        /** Interface C. */
                        public interface C { }
                        """,
                """
                        /** {@inheritDoc} */
                        public interface D extends C { }
                        """,
                """
                        /** Interface E. */
                        public interface E { }
                        """,
                """
                        /** {@inheritDoc} */
                        public class F implements E { }
                        """);
        javadoc("-Xdoclint:none",
                "-d", base.resolve("out").toString(),
                src.resolve("A.java").toString(),
                src.resolve("B.java").toString(),
                src.resolve("C.java").toString(),
                src.resolve("D.java").toString(),
                src.resolve("E.java").toString(),
                src.resolve("F.java").toString());
        checkExit(Exit.OK);
        new OutputChecker(Output.OUT).setExpectOrdered(false).check(
                """
                        B.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** {@inheritDoc} */
                            ^
                            """,
                """
                        D.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** {@inheritDoc} */
                            ^
                            """,
                """
                        F.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** {@inheritDoc} */
                            ^
                            """);
    }

    @Test
    public void testClassOrInterfaceTypeParameter(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src,
                """
                        /** @param <T> A's parameter */
                        public class A<T> { }
                        """,
                """
                        /** @param <T> {@inheritDoc} */
                        public class B extends A { }
                        """,
                """
                        /** @param <T> C's parameter */
                        public interface C<T> { }
                        """,
                """
                        /** @param <T> {@inheritDoc} */
                        public interface D<T> extends C<T> { }
                        """,
                """
                        /** @param <T> E's parameter */
                        public interface E<T> { }
                        """,
                """
                        /** @param <T> {@inheritDoc} */
                        public class F<T> implements E<T> { }
                        """);
        javadoc("-Xdoclint:none",
                "-d", base.resolve("out").toString(),
                src.resolve("A.java").toString(),
                src.resolve("B.java").toString(),
                src.resolve("C.java").toString(),
                src.resolve("D.java").toString(),
                src.resolve("E.java").toString(),
                src.resolve("F.java").toString());
        checkExit(Exit.OK);
        new OutputChecker(Output.OUT).setExpectOrdered(false).check(
                """
                        B.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** @param <T> {@inheritDoc} */
                                       ^
                                       """,
                """
                        D.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** @param <T> {@inheritDoc} */
                                       ^
                                       """,
                """
                        F.java:1: warning: Tag @inheritDoc cannot be used in class documentation.\
                          It can only be used in the following types of documentation: method.
                        /** @param <T> {@inheritDoc} */
                                       ^
                                       """);
    }

    @Test
    public void testOverview(Path base) throws Exception {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, """
                package p;

                /** A class */
                public class C { }
                """);
        tb.writeFile(src.resolve("overview.html"), """
                <HTML lang="EN">
                <HEAD>
                    <TITLE>overview</TITLE>
                </HEAD>
                <BODY>
                {@inheritDoc}
                </BODY>
                </HTML>
                """);
        tb.writeFile(
                src.resolve("p").resolve("doc-files").resolve("example.html"), """
                <HTML lang="EN">
                <HEAD>
                    <TITLE>example</TITLE>
                </HEAD>
                <BODY>
                {@inheritDoc}
                </BODY>
                </HTML>
                """);
        javadoc("-Xdoclint:none",
                "-overview", src.resolve("overview.html").toString(),
                "-d", base.resolve("out").toString(),
                "-sourcepath", src.toString(),
                "p");
        checkExit(Exit.OK);
        new OutputChecker(Output.OUT).setExpectOrdered(false).check(
                """
                        overview.html:6: warning: Tag @inheritDoc cannot be used in overview documentation.\
                          It can only be used in the following types of documentation: method.
                        {@inheritDoc}
                        ^
                        """,
                """
                        example.html:6: warning: Tag @inheritDoc cannot be used in overview documentation.\
                          It can only be used in the following types of documentation: method.
                        {@inheritDoc}
                        ^
                        """);
    }
}

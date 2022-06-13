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

/**
 * @test
 * @compile --enable-preview -source ${jdk.version} TypedDeconstructionPatternExc.java
 * @run main/othervm --enable-preview TypedDeconstructionPatternExc
 */

import java.util.Objects;
import java.util.function.Function;

public class TypedDeconstructionPatternExc {

    public static void main(String... args) throws Throwable {
        new TypedDeconstructionPatternExc().run();
    }

    void run() {
        run(this::testExpr);
        run(this::testExprCond);
    }

    void run(Function<Pair<String, Integer>, Integer> tested) {
        assertEquals(2, tested.apply(new Pair<>("1", 1)));
        try {
            tested.apply((Pair<String, Integer>) (Object) new Pair<Integer, Integer>(1, 1));
            fail("Expected an exception, but none happened!");
        } catch (ClassCastException ex) {
            System.err.println("expected exception:");
            ex.printStackTrace();
        }
        try {
            tested.apply(new Pair<String, Integer>("fail", 1));
            fail("Expected an exception, but none happened!");
        } catch (MatchException ex) {
            assertEquals(TestPatternFailed.class.getName() + ": " + EXCEPTION_MESSAGE,
                         ex.getMessage());
            if (ex.getCause() instanceof TestPatternFailed ex2) {
                System.err.println("expected exception:");
                ex2.printStackTrace();
            } else {
                fail("Not the correct exception.");
            }
        }
    }

    int testExpr(Pair<String, Integer> p) {
        return switch (p) {
            case Pair<String, Integer>(String s, Integer i) -> s.length() + i;
            case Object o -> -1;
        };
    }

    int testExprCond(Pair<String, Integer> p) {
        if (switch (p) {
            case Pair<String, Integer>(String s, Integer i) -> true;
            case Object o -> false;
        }) {
            return p.l().length() + p.r();
        } else {
            return -1;
        }
    }

    static final String EXCEPTION_MESSAGE = "exception-message";

    record Pair<L, R>(L l, R r) {
        public L l() {
            if ("fail".equals(l)) {
                throw new TestPatternFailed(EXCEPTION_MESSAGE);
            }
            return l;
        }
        public R r() {
            return r;
        }
    }

    void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected: " + expected + "," +
                                     "got: " + actual);
        }
    }

    void fail(String message) {
        throw new AssertionError(message);
    }

    public static class TestPatternFailed extends AssertionError {

        public TestPatternFailed(String message) {
            super(message);
        }

    }

}

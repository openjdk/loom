/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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


import java.io.*;

/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jvmti/Breakpoint/breakpoint001.
 * VM Testbase keywords: [quick, jpda, jvmti, onload_only_caps, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     This test exercises the JVMTI event Breakpoint.
 *     It verifies that thread info, method info and location of received
 *     Breakpoint events will be the same with two breakpoints previously
 *     set on the methods 'bpMethod()' and 'bpMethod2()' via the function
 *     SetBreakpoint().
 * COMMENTS
 *
 * @library /test/lib
 *
 * @comment make sure breakpoint001 is compiled with full debug info
 * @build breakpoint001
 * @clean breakpoint001
 * @compile -g:lines,source,vars breakpoint001.java
 *
 * @run main/othervm/native -agentlib:breakpoint breakpoint001
 */


/**
 * This test exercises the JVMTI event <code>Breakpoint</code>.
 * <br>It verifies that thread info, method info and location of
 * received Breakpoint events will be the same with two breakpoints
 * previously set on the methods <code>bpMethod()</code> and
 * <code>bpMethod2()</code> via the function SetBreakpoint().
 */
public class breakpoint001 {
    static {
        try {
            System.loadLibrary("breakpoint");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load \"breakpoint001\" library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native int check();

    public static void main(String[] argv) {
       new breakpoint001().runThis();
    }

    private int runThis() {
        Thread.currentThread().setName("breakpoint001Thr");

        System.out.println("Reaching a breakpoint method ...");
        bpMethod();
        System.out.println("The breakpoint method leaved ...");

        return check();
    }

    /**
     * dummy method used only to reach breakpoint set in the agent
     */
    private void bpMethod() {
        int dummyVar = bpMethod2();
    }

    /**
     * dummy method used only to reach breakpoint set in the agent
     */
    private int bpMethod2() {
        return 0;
    }
}

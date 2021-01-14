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
import java.io.PrintStream;

/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jvmti/FieldAccess/fieldacc004.
 * VM Testbase keywords: [quick, jpda, jvmti, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercise JVMTI event callback function FieldAccess.
 *     The test sets access watches on fields which are defined in
 *     superinterface, then triggers access watch events on these fields
 *     and checks if clazz, method, location, field_clazz, field and
 *     object parameters of the function contain the expected values.
 * COMMENTS
 *     Ported from JVMDI.
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:fieldacc04 fieldacc04
 */


public class fieldacc04 {

    final static int JCK_STATUS_BASE = 95;

    static {
        try {
            System.loadLibrary("fieldacc04");
        } catch (UnsatisfiedLinkError ule) {
            System.err.println("Could not load fieldacc04 library");
            System.err.println("java.library.path:"
                + System.getProperty("java.library.path"));
            throw ule;
        }
    }

    native static void getReady();
    native static int check();

    public static void main(String args[]) {

        // produce JCK-like exit status.
        System.exit(run(args, System.out) + JCK_STATUS_BASE);
    }

    public static int run(String args[], PrintStream out) {
        getReady();
        fieldacc04a t = new fieldacc04a();
        t.run();
        return check();
    }
}

interface fieldacc04i {
    static Object interfaceObject = new Object();
    static int interfaceArrInt[] = {1, 2};
}

class fieldacc04a implements fieldacc04i {
    public int run() {
        int i = 0;
        if (interfaceObject == this) i++;
        if (interfaceArrInt[0] == 3) i++;
        return i;
    }
}

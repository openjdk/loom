/*
 * Copyright (c) 2004, 2022, Oracle and/or its affiliates. All rights reserved.
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

import jdk.test.lib.jvmti.DebugeeClass;

/*
 * @test
 *
 * @summary converted from VM Testbase nsk/jvmti/VMObjectAlloc/vmobjalloc001.
 * VM Testbase keywords: [quick, jpda, jvmti, noras]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test exercises JVMTI event callback function VMObjectAlloc.
 *     The test enables the event and counts a number of received events.
 *     There is no guarantee that VM allocates any special objects, so if
 *     no JVMTI_EVENT_VM_OBJECT_ALLOC has been received then the test
 *     just prints warning message and passes anyway.
 * COMMENTS
 *     Fixed the 5001806 bug.
 *     Modified due to fix of the bug
 *     5010571 TEST_BUG: jvmti tests with VMObjectAlloc callbacks should
 *             be adjusted to new spec
 *
 * @library /test/lib
 * @run main/othervm/native -agentlib:vmobjalloc01 vmobjalloc01
 */

public class vmobjalloc01 extends DebugeeClass {

    public static void main(String argv[]) {
        new vmobjalloc01().runIt();
    }

    int status = TEST_PASSED;

    // run debuggee
    public void runIt() {

        System.out.println("Sync: debuggee started");
        int result = checkStatus(status);
        if (result != 0) {
            throw new RuntimeException("checkStatus() returned " + result);
        }
        System.out.println("TEST PASSED");
    }
}

/*
 * Copyright (c) 2007, 2025, Oracle and/or its affiliates. All rights reserved.
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
 * @modules java.base/jdk.internal.misc:+open
 *
 * @summary converted from VM Testbase nsk/jdi/VirtualMachine/setDefaultStratum/setDefaultStratum003.
 * VM Testbase keywords: [jpda, jdi, feature_sde, vm6]
 * VM Testbase readme:
 * DESCRIPTION
 *     The test checks up that method 'com.sun.jdi.VirtualMachine.setDefaultStratum(String stratum)' affects StepEvents generation.
 *     Debugger creates copy of class file for class 'nsk.share.jdi.TestClass1' with SourceDebugExtension attribute
 *     which contains informations for 3 stratums('TestStratum1'-'TestStratum3') and for each of this stratums following line mapping
 *     is defined:
 *         "Java"          "TestStratum"
 *         <init>
 *         9       -->     1000, source1
 *         11      -->     1002, source1
 *         ...             ...
 *         sde_testMethod1
 *         20      -->     1100, source1
 *         22      -->     1101, source1
 *         ...             ...
 *         sde_testMethod1
 *         31      -->     1200, source1
 *         33      -->     1201, source1
 *         ...             ...
 *     Then debugger forces debuggee to load 'TestClass1' from updated class file, starts event listener thread which saves all received StepEvents,
 *     enables StepEvent request(class filter is used to receive events only for 'TestClass1').
 *     for TestStratum in 'TestStratum1'-'TestStratum3'
 *     do
 *         - set TestStratum as VM default
 *         - force debuggee to execute all methods defined in 'TestClass1'
 *         - when all methods was executed check up that StepEvents was generated for each location specified for TestStratum
 *     done
 *
 * @library /vmTestbase
 *          /test/lib
 * @build nsk.jdi.VirtualMachine.setDefaultStratum.setDefaultStratum003.setDefaultStratum003
 * @run driver
 *      nsk.jdi.VirtualMachine.setDefaultStratum.setDefaultStratum003.setDefaultStratum003
 *      -verbose
 *      -arch=${os.family}-${os.simpleArch}
 *      -waittime=5
 *      -debugee.vmkind=java
 *      -transport.address=dynamic
 *      -debugee.vmkeys="${test.vm.opts} ${test.java.opts}"
 *      -testClassPath ${test.class.path}
 *      -testWorkDir .
 *      -testStratumCount 3
 */

package nsk.jdi.VirtualMachine.setDefaultStratum.setDefaultStratum003;

import java.io.*;
import java.util.*;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.*;
import com.sun.jdi.ThreadReference;
import nsk.share.Consts;
import nsk.share.jdi.EventHandler;
import nsk.share.jdi.sde.*;

public class setDefaultStratum003 extends SDEDebugger {
    public static void main(String argv[]) {
        int result = run(argv,System.out);
        if (result != 0) {
            throw new RuntimeException("TEST FAILED with result " + result);
        }
    }

    public static int run(String argv[], PrintStream out) {
        return new setDefaultStratum003().runIt(argv, out);
    }

    protected String[] doInit(String args[], PrintStream out) {
        args = super.doInit(args, out);

        ArrayList<String> standardArgs = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-testStratumCount") && (i < args.length - 1)) {
                testStratumCount = Integer.parseInt(args[i + 1]);
                i++;
            } else
                standardArgs.add(args[i]);
        }

        return standardArgs.toArray(new String[] {});
    }

    private int testStratumCount = 1;

    private EventHandler eventHandler;

    public void doTest() {
        String className = TestClass1.class.getName();

        Map<String, LocationsData> testStratumData = prepareDefaultPatchedClassFile_Type3(
                className,
                testStratumCount,
                true);
        /*
         * Method 'prepareDefaultPatchedClassFile_Type3' creates class file with
         * following line mapping for each test stratum:
         *
         * "Java" "TestStratum"
         *
         * <init>
         * 9 --> 1001, source1
         * 11 --> 1002, source1
         * 14 --> 1003, source1
         * 16 --> 1004, source1
         *
         * sde_testMethod1
         * 20 --> 1101, source1
         * 22 --> 1102, source1
         * 24 --> 1103, source1
         * 26 --> 1104, source1
         *
         * sde_testMethod2
         * 31 --> 1201, source1
         * 33 --> 1202, source1
         * 35 --> 1203, source1
         * 37 --> 1204, source1
         */

        eventHandler = new EventHandler(debuggee, log);
        eventHandler.startListening();

        StepEventListener stepEventListener = new StepEventListener();
        eventHandler.addListener(stepEventListener);

        ReferenceType debuggeeClass = debuggee.classByName(SDEDebuggee.class.getName());
        ThreadReference mainThread =
            debuggee.threadByFieldNameOrThrow(debuggeeClass, "mainThread",
                                              SDEDebuggee.mainThreadName);
        StepRequest stepRequest = debuggee.getEventRequestManager().createStepRequest(
                mainThread,
                StepRequest.STEP_LINE,
                StepRequest.STEP_INTO);

        stepRequest.setSuspendPolicy(StepRequest.SUSPEND_EVENT_THREAD);
        stepRequest.addClassFilter(TestClass1.class.getName());
        stepRequest.enable();

        // for each stratum available for class
        for (String defaultStratum : testStratumData.keySet()) {
            log.display("Set default stratum: " + defaultStratum);

            stepEventListener.clearLocations();

            vm.setDefaultStratum(defaultStratum);

            pipe.println(SDEDebuggee.COMMAND_EXECUTE_TEST_METHODS + ":" + className);

            if (!isDebuggeeReady())
                return;

            compareLocations(
                    stepEventListener.stepLocations(),
                    testStratumData.get(defaultStratum).allLocations,
                    defaultStratum);
        }
    }
}

/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Verifies JVMTI RawMoitor functions works correctly on virtual threads
 * @run main/othervm/native -agentlib:RawMonitorTest RawMonitorTest
 */

public class RawMonitorTest {
    private static final String AGENT_LIB = "RawMonitorTest";
    
    native void rawMonitorEnter(); 
    native void rawMonitorExit(); 
    native void rawMonitorWait(); 
    native void rawMonitorNotifyAll(); 

    final Runnable parkingTask = () -> {
       for (int i = 0; i < 40; i++) {
            rawMonitorEnter();
            rawMonitorNotifyAll();
            // uncomment lines below to get failures with NOT_MONITOR_OWNER
            // try {
            //     Thread.sleep(1);
            // } catch (InterruptedException ie) {
            // }
            rawMonitorWait();
            rawMonitorExit();
        }
    };

    void runTest() throws Exception { 
        Thread vt1 = Thread.ofVirtual().name("VT1").start(parkingTask);
        Thread vt2 = Thread.ofVirtual().name("VT2").start(parkingTask);
        Thread vt3 = Thread.ofVirtual().name("VT3").start(parkingTask);
        vt1.join();
        vt2.join();
        vt3.join();
    }

    public static void main(String[] args) throws Exception {
        try {
            System.loadLibrary(AGENT_LIB);
        } catch (UnsatisfiedLinkError ex) {
            System.err.println("Failed to load " + AGENT_LIB + " lib");
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw ex;
        } 
        RawMonitorTest t = new RawMonitorTest();
        t.runTest();
    }
}

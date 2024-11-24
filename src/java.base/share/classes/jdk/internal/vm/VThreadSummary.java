/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.internal.vm;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;
import sun.nio.ch.Poller;

/**
 * The implementation for the jcmd Thread.vthread_summary diagnostic command.
 */
public class VThreadSummary {
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private VThreadSummary() { }

    /**
     * Invoked by the VM to print virtual thread summary information.
     * @return the UTF-8 encoded information to print
     */
    private static byte[] print() {
        StringBuilder sb = new StringBuilder();

        // print virtual thread scheduler
        printSchedulers(sb);
        sb.append(System.lineSeparator());

        // print I/O pollers if initialized
        if (!U.shouldBeInitialized(Poller.class)) {
            printPollers(sb);
            sb.append(System.lineSeparator());
        }

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Print information on the virtual thread schedulers to given string builder.
     */
    private static void printSchedulers(StringBuilder sb) {
        sb.append("Virtual thread scheduler:")
                .append(System.lineSeparator());
        sb.append(JLA.virtualThreadDefaultScheduler())
                .append(System.lineSeparator());

        sb.append(System.lineSeparator());

        sb.append("Timeout schedulers:")
                .append(System.lineSeparator());
        var schedulers = JLA.virtualThreadDelayedTaskSchedulers().toList();
        for (int i = 0; i < schedulers.size(); i++) {
            sb.append('[')
                    .append(i)
                    .append("] ")
                    .append(schedulers.get(i))
                    .append(System.lineSeparator());
        }
    }

    /**
     * Print information on threads registered for I/O to the given string builder.
     */
    private static void printPollers(StringBuilder sb) {
        Poller masterPoller = Poller.masterPoller();
        List<Poller> readPollers = Poller.readPollers();
        List<Poller> writePollers = Poller.writePollers();

        if (masterPoller != null) {
            sb.append("Master I/O poller:")
                    .append(System.lineSeparator())
                    .append(masterPoller)
                    .append(System.lineSeparator());
            sb.append(System.lineSeparator());
        }

        sb.append("Read I/O pollers:");
        sb.append(System.lineSeparator());
        IntStream.range(0, readPollers.size())
                .forEach(i -> sb.append('[')
                        .append(i)
                        .append("] ")
                        .append(readPollers.get(i))
                        .append(System.lineSeparator()));
        sb.append(System.lineSeparator());

        sb.append("Write I/O pollers:");
        sb.append(System.lineSeparator());
        IntStream.range(0, writePollers.size())
                .forEach(i -> sb.append('[')
                        .append(i)
                        .append("] ")
                        .append(writePollers.get(i))
                        .append(System.lineSeparator()));
    }
}

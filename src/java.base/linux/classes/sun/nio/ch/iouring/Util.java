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

package sun.nio.ch.iouring;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static jdk.internal.ffi.generated.iouring.iouring_h.*;

@SuppressWarnings("restricted")
class Util {

    public static void print(MemorySegment segment, String title) {
        print(segment, title, 32);
    }

    /**
     * Prints to System.out contents of MemorySegment
     *
     * @param segment
     * @param title
     * @param wrap Number of bytes to wrap line at
     */
    public static void print(MemorySegment segment, String title, int wrap) {
        long size = segment.byteSize();
        long startAddress = segment.address();
        System.out.printf("%s, Size: %d bytes, Wrap: %d", title, size, wrap);
        for (long offset=0L; offset<size; offset++) {
            int b = segment.get(ValueLayout.JAVA_BYTE, offset) & 0xff;
            long address = startAddress + offset;
            if (offset % wrap == 0) {
                System.out.println("");
                System.out.printf("%08X ", address);
            }
            System.out.printf("%02X", b);
            if (offset % 4 == 3)
                System.out.print(" ");
        }
        System.out.println("");
    }

    public static String sqe_opcode(int code) {
        if (code == IORING_OP_ACCEPT())
            return "IORING_OP_ACCEPT";
        else if (code == IORING_OP_CLOSE())
            return "IORING_OP_CLOSE";
        else if (code == IORING_OP_SOCKET())
            return "IORING_OP_SOCKET";
        else if (code == IORING_OP_CONNECT())
            return "IORING_OP_CONNECT";
        else if (code == IORING_OP_WRITE())
            return "IORING_OP_WRITE";
        else if (code == IORING_OP_OPENAT())
            return "IORING_OP_OPENAT";
        else if (code == IORING_OP_READ_FIXED())
            return "IORING_OP_READ_FIXED";
        else if (code == IORING_OP_WRITE_FIXED())
            return "IORING_OP_WRITE_FIXED";
        else if (code == IORING_OP_POLL_ADD())
            return "IORING_OP_POLL_ADD";
        else if (code == IORING_OP_POLL_REMOVE())
            return "IORING_OP_POLL_REMOVE";
        else if (code == IORING_OP_TIMEOUT())
            return "IORING_OP_TIMEOUT";
        else if (code == IORING_OP_READ())
            return "IORING_OP_READ";
        else if (code == IORING_OP_LINK_TIMEOUT())
            return "IORING_OP_LINK_TIMEOUT";
        else if (code == IORING_OP_NOP())
            return "IORING_OP_NOP";
        else if (code == IORING_OP_MSG_RING())
            return "IORING_OP_MSG_RING";
        else return String.format("UNKNOWN(%d)", code);
    }

    static MethodHandle locateStdHandle(String name,
                                        FunctionDescriptor descriptor,
                                        Linker.Option... options) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup stdlib = linker.defaultLookup();
            return linker.downcallHandle(
                    stdlib.find(name).orElseThrow(),
                    descriptor,
                    options
            );
        } catch (NoSuchElementException e) {
            String msg = String.format("Error loading %s from standard lib",
                name);
            throw new RuntimeException(msg);
        }
    }

    static MethodHandle locateHandleFromLib(String libname,
                                            String symbol,
                                            FunctionDescriptor descriptor,
                                            Linker.Option... options) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = SymbolLookup.libraryLookup(
                libname, Arena.global());
            MethodHandle fn = linker.downcallHandle(
                lib.find(symbol).orElseThrow(),
                descriptor,
                options
            );
            return fn;
        } catch (NoSuchElementException e) {
            String msg = String.format("Error loading %s from %s",
                symbol, libname);
            throw new RuntimeException(msg);
        }
    }

    public final static ValueLayout INT_POINTER =
        ValueLayout.ADDRESS.withTargetLayout(
            ValueLayout.JAVA_INT
    );

    private final static ValueLayout POINTER =
        ValueLayout.ADDRESS.withTargetLayout(
            MemoryLayout.sequenceLayout(Long.MAX_VALUE, JAVA_BYTE)
    );

    private static final MethodHandle strerror_fn = locateStdHandle(
            "strerror",
            FunctionDescriptor.of(
                    POINTER,
                    ValueLayout.JAVA_INT
            )
    );
    public static String strerror(int errno) {
        try {
            MemorySegment result = (MemorySegment) strerror_fn.invokeExact(errno);
            return result.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "Error: " + errno;
        }
    }

}

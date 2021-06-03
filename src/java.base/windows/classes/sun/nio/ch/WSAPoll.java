/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
package sun.nio.ch;

import java.io.IOException;
import jdk.internal.misc.Unsafe;

/**
 * Provides access to WSAPoll.
 */
class WSAPoll {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final int ADDRESS_SIZE = UNSAFE.addressSize();
    
    private WSAPoll() { }

    /**
     * typedef struct pollfd {
     *   SOCKET fd;
     *   SHORT events;
     *   SHORT revents;
     * } WSAPOLLFD;
     */
    private static final int SIZE_POLLFD    = pollfdSize();
    private static final int FD_OFFSET      = fdOffset();
    private static final int EVENTS_OFFSET  = eventsOffset();
    private static final int REVENTS_OFFSET = reventsOffset();

    /**
     * Allocates a poll array of {@code size} WSAPOLLFD structures.
     */
    static long allocatePollArray(int size) {
        if (size <= 0)
            throw new IllegalArgumentException();
        return UNSAFE.allocateMemory(size * SIZE_POLLFD);
    }

    /**
     * Reallocates a poll array from {@code size} to {@code newSize}
     * WSAPOLLFD structures.
     */
    static long reallocatePollArray(long address, int size, int newSize) {
        if (newSize < size)
            throw new IllegalArgumentException();
        long newAddress = allocatePollArray(newSize);
        UNSAFE.copyMemory(address, newAddress, size * SIZE_POLLFD);
        UNSAFE.freeMemory(address);
        return newAddress;
    }

    /**
     * Free a poll array.
     */
    static void freePollArray(long address) {
        UNSAFE.freeMemory(address);
    }

    static void putDescriptor(long address, int i, int fd) {
        int offset = SIZE_POLLFD * i + FD_OFFSET;
        if (ADDRESS_SIZE == 8) {
            UNSAFE.putLong(address + offset, fd);
        } else {
            UNSAFE.putInt(address + offset, fd);
        }
    }

    static int getDescriptor(long address, int i) {
        int offset = SIZE_POLLFD * i + FD_OFFSET;
        long s;
        if (ADDRESS_SIZE == 8) {
            s = UNSAFE.getLong(address + offset);
        } else {
            s = UNSAFE.getInt(address + offset);
        }
        int fd = (int) s;
        assert ((long) fd) == s;
        return fd;
    }

    static void putEvents(long address, int i, short events) {
        int offset = SIZE_POLLFD * i + EVENTS_OFFSET;
        UNSAFE.putShort(address + offset, events);
    }

    static short getEvents(long address, int i) {
        int offset = SIZE_POLLFD * i + EVENTS_OFFSET;
        return UNSAFE.getShort(address + offset);
    }

    static void putRevents(long address, int i, short revents) {
        int offset = SIZE_POLLFD * i + REVENTS_OFFSET;
        UNSAFE.putShort(address + offset, revents);
    }

    static short getRevents(long address, int i) {
        int offset = SIZE_POLLFD * i + REVENTS_OFFSET;
        return UNSAFE.getShort(address + offset);
    }

    // -- Native methods --

    private static native int pollfdSize();

    private static native int fdOffset();

    private static native int eventsOffset();

    private static native int reventsOffset();

    static native int poll(long pollAddress, int numfds, int timeout)
        throws IOException;

    static {
        IOUtil.load();
    }
}
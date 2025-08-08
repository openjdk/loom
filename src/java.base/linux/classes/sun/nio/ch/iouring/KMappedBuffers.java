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

import jdk.internal.ffi.generated.iouring.io_uring_rsrc_register;
import jdk.internal.ffi.generated.iouring.iovec;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static sun.nio.ch.iouring.Util.locateStdHandle;
import static jdk.internal.ffi.generated.iouring.iouring_h.IORING_REGISTER_BUFFERS2;

/**
 * Kernel mapped direct ByteBuffers. Buffers are allocated by the constructor
 * and later registered with an IOUringImpl. Users must obtain a buffer from
 * this object to be used with any async/fixed I/O operation on the ring.
 */
class KMappedBuffers {
    private final List<ByteBuffer> registeredBufferList;
    private final ConcurrentLinkedQueue<ByteBuffer> registeredFreeList;

    // Map which records the kernel index of each buffer
    private final IdentityHashMap<ByteBuffer,Integer> registeredBuffers
            = new IdentityHashMap<>();
    private final Arena autoArena = Arena.ofAuto();

    KMappedBuffers(int num, int size) throws IOException {
        this.registeredBufferList = List.copyOf(getDirectBuffers(num, size));
        this.registeredFreeList = new ConcurrentLinkedQueue<>(registeredBufferList);
    }

    /**
     * Registers this List of (direct) ByteBuffers with an IOUringImpl such
     * that the buffers can be accessed directly by user code
     * and by the kernel without copying, as part of
     * fixed read and write operations.
     *
     * The buffer's position and limit are ignored for registration
     * as the entire capacity is registered. Buffer's position and limit
     * are however observed during readFixed and writeFixed calls.
     *
     * It probably makes sense to register the same number of buffers
     * as there are entries in the Submission Queue.
     *
     * @param buffers
     */
    void register(int ringfd) throws IOException {
        int index = 0;
        registeredBuffers.clear();
        int size = registeredBufferList.size();
        MemorySegment rsrc = autoArena.allocate(io_uring_rsrc_register.$LAYOUT());
        MemorySegment iov = iovec.allocateArray(size, autoArena);
        io_uring_rsrc_register.data(rsrc, iov.address());
        io_uring_rsrc_register.tags(rsrc, 0); // Disable tagging for now
        io_uring_rsrc_register.nr(rsrc, size);
        for (ByteBuffer buffer : registeredBufferList) {
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException("Buffers must be direct");
            }
            if (registeredBuffers.containsKey(buffer)) {
                throw new IllegalArgumentException("Buffers must be unique");
            }
            registeredBuffers.put(buffer, index);
            MemorySegment seg = iovec.asSlice(iov, index);
            iovec.iov_base(seg, MemorySegment.ofBuffer(buffer));
            iovec.iov_len(seg, buffer.capacity());
            index++;
        }
        registerBufferSegment(ringfd, rsrc, (int)io_uring_rsrc_register.sizeof());
    }

    /**
     * Returns a registered buffer.
     * @return
     */
    ByteBuffer getRegisteredBuffer() {
        return registeredFreeList.poll();
    }

    void returnRegisteredBuffer(ByteBuffer buf) {
        checkAndGetIndexForBuffer(buf);
        registeredFreeList.add(buf);
    }

    int checkAndGetIndexForBuffer(ByteBuffer buf) {
        int ret;
        if ((ret = getRegisteredIndexFor(buf)) == -1) {
            throw new IllegalArgumentException("Not a a registered buffer");
        }
        registeredFreeList.forEach((buf1) -> {
            if (buf == buf1)
                throw new IllegalArgumentException("buffer was not allocated");
        });
        return ret;
    }

    int getRegisteredIndexFor(ByteBuffer buf) {
        Integer ind = registeredBuffers.get(buf);
        return ind == null ? -1 : ind.intValue();
    }

    private static List<ByteBuffer> getDirectBuffers(int n, int size) {
        LinkedList<ByteBuffer> l = new LinkedList<>();
        for (int i=0; i<n; i++)
            l.add(ByteBuffer.allocateDirect(size));
        return l;
    }
    private void registerBufferSegment(int ringfd, MemorySegment segment, int nentries) throws IOException {
        int ret;
        SystemCallContext ctx = SystemCallContext.get();
        try {
            ret = (int)register_fn
                    .invokeExact(
                            NR_io_uring_register,
                            ctx.errnoCaptureSegment(),
                            ringfd, IORING_REGISTER_BUFFERS2(),
                            segment.address(), nentries
                    );
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        ctx.throwIOExceptionOnError(ret);
    }

    // syscall number
    private static final int NR_io_uring_register = 427;

    private static final MethodHandle register_fn = locateStdHandle(
            "syscall",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,  // result
                    ValueLayout.JAVA_INT, // syscall
                    ValueLayout.JAVA_INT, // ring fd
                    ValueLayout.JAVA_INT, // opcode
                    ValueLayout.JAVA_LONG, // iovec array
                    ValueLayout.JAVA_INT), // array length
            SystemCallContext.errnoLinkerOption()
    );
}

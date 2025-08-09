/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicInteger;
import static jdk.internal.ffi.generated.iouring.iouring_h.IOSQE_IO_LINK;
import static jdk.internal.ffi.generated.iouring.iouring_h_1.IORING_OP_MSG_RING;
import static jdk.internal.ffi.generated.iouring.iouring_h_1.IORING_OP_POLL_ADD;
import static jdk.internal.ffi.generated.iouring.iouring_h_1.IORING_OP_POLL_REMOVE;

/**
 * Provides an API to io_ring. Submission is synchronized so can be called
 * from multiple threads. Polling() is not synchronized and should
 * only be done from one thread, which can be a different thread to
 * the submitters.
 */
public class IoUring implements Closeable {

    private final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();

    private static final int SQ_ENTRIES = 5;

    private final IOUringImpl impl;

    private IoUring() throws IOException {
        this.impl = new IOUringImpl(SQ_ENTRIES);
    }

    private IoUring(int entries) throws IOException {
        this.impl = new IOUringImpl(entries);
    }

    /**
     * Closes this IoUring. If a thread is blocked in
     * poll() it will be unblocked and its callback
     * will be invoked with a data value of {@code -1}
     */
    @Override
    public void close() {
        // Experiment. This could be implemented
        // as a IORING_OP_MSG_RING message passed to
        // the receiving poller thread. But, this has
        // the same effect and MSG_RING is not supported
        // in all kernels. Any fd can be used, whether
        // it is registered with the ring or not
        // Seems safest to use the fd that we are about
        // to close anyway.
        try {
            //poll_remove(impl.ringFd(), -1);
            impl.close();
        } catch (IOException e) {}
    }

    // Event values from Linux headers
    public static final int POLLIN = 1;
    public static final int POLLOUT = 4;

    /**
     * Creates an io_uring with a default submission queue size.
     * The completion queue is always twice the size of the
     * submission queue
     */
    public static IoUring create() throws IOException {
        return new IoUring();
    }

    /**
     * Creates an io_uring with the given submission queue size.
     * The completion queue is always twice the size of the
     * submission queue
     *
     * @param entries the size of the submission queue
     */
    public static IoUring create(int entries) throws IOException {
        return new IoUring(entries);
    }

    /**
     * For testing register_eventfd()
     * Will remove eventually
     */
    public int eventfd() throws IOException {
        return impl.eventfd();
    }

    private void enterNSubmissions(int n) throws IOException {
        int ret = impl.enter(n, 0, 0);
        if (ret < 1) {
            throw new IOException(
                String.format("enterNSubmissions error %d", ret));
        }
    }

    /**
     * Submits a request to poll the given file descriptor. The request is tagged with
     * the given user data.
     * Submission errors may be reported immediately.
     */
    public void poll_add(int fd, int events, long udata) throws IOException {
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_ADD())
                .fd(fd)
                .user_data(udata)
                .poll_events(events);
        impl.submit(sqe);
        enterNSubmissions(1);
    }

    /**
     * Submits a request to remove an existing poll request.
     * Submission errors may be reported immediately.
     * @param req_udata identifies the poll request
     * @param udata the user data for the poll remove operation
     */
    public void poll_remove(long req_udata, long udata) throws IOException {
        @SuppressWarnings("restricted")
        MemorySegment address = MemorySegment.ofAddress(req_udata).reinterpret(ADDRESS_SIZE);
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_REMOVE())
                .addr(address)
                .user_data(udata);
        impl.submit(sqe);
        enterNSubmissions(1);
    }

    /**
     * Adds the given eventfd(2) file descriptor to the iouring poller.
     */
    public void register_eventfd(int efd) throws IOException {
        impl.register_eventfd(efd);
    }

    /**
     * Removes the previously registered eventfd(2) descriptor
     */
    public void unregister_eventfd() throws IOException {
        impl.unregister_eventfd();
    }

    /**
     * Poll and consume up to {@code max} completion events. If {@code block} is
     * true then wait for completion events.
     *
     * @param consumer callback informed with the user data
     *               and the error if one occurred.
     *               If the ring is closed while blocked
     *               in this method, the callback will be
     *               invoked with a data value of {@code -1}
     *
     * @param max the maximum number of completion events to consume
     *
     * @param block true if call should block waiting for event
     *              {@code false} if call should not block
     *
     * @return the number of events consumed, maybe be 0
     *
     * @throws IOException Non fd specific errors are signaled
     *         via exception
     */
    public int poll(BiConsumer<Long, Integer> consumer, int max, boolean block) throws IOException {
        int polled = pollCompletionQueue(consumer, max);
        if (polled > 0 || !block) {
            return polled;
        }
        impl.enter(0, 1, 0);
        return pollCompletionQueue(consumer, max);
    }

    /**
     * Poll and consume up to max completion events without waiting.
     * @return the number of completion events consumed
     */
    private int pollCompletionQueue(BiConsumer<Long, Integer> consumer, int max) {
        int polled = 0;
        while (polled < max && pollCompletionQueue(consumer) > 0) {
            polled++;
        }
        return polled;
    }

    /**
     * Poll and consume a completion event without waiting.
     * @return 1 if a completion event was consume, 0 if no completion event was consumed
     */
    private int pollCompletionQueue(BiConsumer<Long, Integer> consumer) {
        Cqe cqe = impl.pollCompletion();
        if (cqe == null) {
            return 0;
        }
        int res = cqe.res();
        long data = cqe.user_data();
        consumer.accept(data, res);
        return 1;
    }
}

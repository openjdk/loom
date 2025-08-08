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
            poll_remove(impl.ringFd(), -1);
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
        return new IoUring(entries );
    }

    /**
     * Adds the given file descriptor to the iouring poller.
     * Submission errors may be reported immediately.
     */
    public void poll_add(int sock, int events, long data) throws IOException {
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_ADD())
                .fd(sock)
                .flags(IOSQE_IO_LINK())
                .user_data(data)
                .poll_events(events);
        impl.submit(sqe);
        enterNSubmissions(1);
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
     * Removes the given file descriptor to the iouring poller.
     * Submission errors may be reported immediately.
     */
    public void poll_remove(int sock, long data) throws IOException {
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_REMOVE())
                .flags(IOSQE_IO_LINK())
                .fd(sock)
                .user_data(data);
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
     * Blocks until some file descriptor is ready or an error
     * has occurred.
     *
     * @param polled callback informed with the user data
     *               and the error (if any) relating to it.
     *               If the ring is closed while blocked
     *               in this method, the callback will be
     *               invoked with a data value of {@code -1}
     *
     * @return the number of events signalled. Will be {@code 1}
     *         on success.
     *
     * @throws IOException Non fd specific errors are signaled
     *         via exception
     */
    public int poll(BiConsumer<Long, Integer> polled) throws IOException {
        return poll(polled, true);
    }

    /**
     * Blocks until some file descriptor is ready or an error
     * has occurred
     *
     * @param polled callback informed with the user data
     *               and the error if one occurred.
     *               If the ring is closed while blocked
     *               in this method, the callback will be
     *               invoked with a data value of {@code -1}
     *
     * @param block true if call should block waiting for event
     *              {@code false} if call should not block
     *
     * @return the number of events signalled. Will be either
     *         {@code 1} or {@code 0} if block is {@code false}.
     *
     * @throws IOException Non fd specific errors are signaled
     *         via exception
     */
    public int poll(BiConsumer<Long, Integer> polled, boolean block)
            throws IOException {
        if (pollCompletionQueue(polled) == 1)
            return 1;
        if (!block)
            return 0;
        impl.enter(0, 1, 0);
        return pollCompletionQueue(polled); // should return 1
    }

    /**
     * Poll the Completion Queue and returning 0 or 1
     * if an event found and the completion consumer was called
     */
    private int pollCompletionQueue(BiConsumer<Long, Integer> polled) {
        Cqe cqe = impl.pollCompletion();
        if (cqe == null) {
            return 0;
        }
        int res = cqe.res();
        long data = cqe.user_data();
        polled.accept(data, res);
        return 1;
    }
}

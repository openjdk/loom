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
package sun.nio.ch;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner.Cleanable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.ref.CleanerFactory;
import sun.nio.ch.iouring.IOUringImpl;
import sun.nio.ch.iouring.Cqe;
import sun.nio.ch.iouring.Sqe;
import static jdk.internal.ffi.generated.iouring.iouring_h.IORING_OP_POLL_ADD;
import static jdk.internal.ffi.generated.iouring.iouring_h.IORING_OP_POLL_REMOVE;

/**
 * Poller implementation based io_uring.
 */

public class IoUringPoller extends Poller {
    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();

    // submition and completion queue sizes
    private static final int SQ_SIZE = 8;
    private static final int CQ_SIZE = Math.max(SQ_SIZE + 1, 1024);

    // max completion events to consume in a blocking poll and non-blocking subpoll
    private static final int MAX_EVENTS_PER_POLL    = 64;
    private static final int MAX_EVENTS_PER_SUBPOLL = 8;

    private final int event;
    private final IOUringImpl ring;
    private final EventFD readyEvent;   // completion events posted to CQ ring
    private final EventFD wakeupEvent;  // wakeup event, used for shutdown

    // close action, and cleaner if this is subpoller
    private final Runnable closer;
    private final Cleanable cleaner;

    // used to coordinate access to submission queue
    private final Object submitLock = new Object();

    // maps file descriptor to Thread when cancelling poll
    private final Map<Integer, Thread> cancels = new ConcurrentHashMap<>();

    IoUringPoller(boolean subPoller, boolean read) throws IOException {
        IOUringImpl ring = null;
        EventFD wakeupEvent = null;
        EventFD readyEvent = null;
        try {
            ring = new IOUringImpl(SQ_SIZE, CQ_SIZE, 0);

            // need event to register with master poller
            if (subPoller) {
                readyEvent = new EventFD();
                ring.register_eventfd(readyEvent.efd());
            }

            // register event with io_uring to allow for wakeup
            wakeupEvent = new EventFD();
            int efd = wakeupEvent.efd();
            IOUtil.configureBlocking(efd, false);
            submitPollAdd(ring, efd, Net.POLLIN, efd);
        } catch (Throwable e) {
            if (ring != null) ring.close();
            if (readyEvent != null) readyEvent.close();
            if (wakeupEvent != null) wakeupEvent.close();
            throw e;
        }

        this.event = (read) ? Net.POLLIN : Net.POLLOUT;
        this.ring = ring;
        this.readyEvent = readyEvent;
        this.wakeupEvent = wakeupEvent;

        // create action to io_uring instance, register cleaner if this is a subpoller
        this.closer = closer(ring, readyEvent, wakeupEvent);
        if (subPoller) {
            this.cleaner = CleanerFactory.cleaner().register(this, closer);
        } else {
            this.cleaner = null;
        }
    }

    /**
     * Returns an action to close the io_uring and release other resources.
     */
    private static Runnable closer(IOUringImpl ring, EventFD readyEvent, EventFD wakeupEvent) {
        return () -> {
            try {
                ring.close();
                if (readyEvent != null) readyEvent.close();
                wakeupEvent.close();
            } catch (IOException _) { }
        };
    }

    @Override
    void close() throws IOException {
        if (cleaner != null) {
            cleaner.clean();
        } else {
            closer.run();
        }
    }

    @Override
    int fdVal() {
        if (readyEvent == null) {
            throw new UnsupportedOperationException();
        }
        return readyEvent.efd();
    }

    @Override
    void pollerPolled() throws IOException {
        readyEvent.reset();
    }

    @Override
    void implRegister(int fd) throws IOException {
        assert fd != 0;
        synchronized (submitLock) {
            // fd is the user data for IORING_OP_POLL_ADD request
            submitPollAdd(ring, fd, event, fd);
        }
    }

    @Override
    void implDeregister(int fd, boolean polled) throws IOException {
        if (!polled && !isShutdown()) {
            cancels.put(fd, Thread.currentThread());
            synchronized (submitLock) {
                // fd was the user data for IORING_OP_POLL_ADD request
                // -fd is the user data for IORING_OP_POLL_REMOVE request
                submitPollRemove(ring, fd, -fd);
            }
            while (cancels.containsKey(fd) && !isShutdown()) {
                LockSupport.park();
            }
        }
    }

    @Override
    void wakeupPoller() throws IOException {
        wakeupEvent.set();
    }

    @Override
    int poll(int timeout) throws IOException {
        if (timeout > 0) {
            throw new UnsupportedOperationException();
        }
        boolean block = (timeout == -1);
        int max = block ? MAX_EVENTS_PER_POLL : MAX_EVENTS_PER_SUBPOLL;
        int polled = tryPoll(max);
        if (polled > 0 || !block) {
            return polled;
        } else {
            ring.enter(0, 1, 0);  // wait for at least one completion
            return tryPoll(max);
        }
    }

    /**
     * Poll up to max sockets without blocking. Also handles the completion of any
     * POLL_REMOVE operations.
     * @retutn the number of sockets polled
     */
    private int tryPoll(int max) {
        int polled = 0;
        Cqe cqe;
        while (polled < max && ((cqe = ring.pollCompletion()) != null)) {
            // user data is fd or -fd
            int fd = (int) cqe.user_data();
            if (fd > 0 && fd != wakeupEvent.efd()) {
                // poll done
                polled(fd);
                polled++;
            } else if (fd < 0) {
                // cancel done
                Thread t = cancels.remove(-fd);
                if (t != null) {
                    LockSupport.unpark(t);
                }
            }
        }
        return polled;
    }

    /**
     * Submit IORING_OP_POLL_ADD operation.
     */
    private static void submitPollAdd(IOUringImpl ring,
                                      int fd,
                                      int events,
                                      long udata) throws IOException {
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_ADD())
                .fd(fd)
                .user_data(udata)
                .poll_events(events);
        ring.submit(sqe);
        int ret = ring.enter(1, 0, 0);  // submit 1
        if (ret < 1) {
            throw new IOException("io_uring_enter failed, ret=" + ret);
        }
    }

    /**
     * Submit IORING_OP_POLL_REMOVE operation.
     * @param req_udata the user data to identify the original POLL_ADD
     * @param udata the user data for the POLL_REMOVE op
     */
    private static void submitPollRemove(IOUringImpl ring,
                                         long req_udata,
                                         long udata) throws IOException {
        @SuppressWarnings("restricted")
        MemorySegment address = MemorySegment.ofAddress(req_udata).reinterpret(ADDRESS_SIZE);
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_POLL_REMOVE())
                .addr(address)
                .user_data(udata);
        ring.submit(sqe);
        int ret = ring.enter(1, 0, 0);  // submit 1
        if (ret < 1) {
            throw new IOException("io_uring_enter failed, ret=" + ret);
        }
    }
}
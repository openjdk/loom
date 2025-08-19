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
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
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
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();

    // true to batch submits and reduce calls to io_using_enter
    private static final boolean BATCH_SUBMITS = Boolean.getBoolean("jdk.io_uring.batchSubmits");

    // submition and completion queue sizes
    private static final int SQ_SIZE = 16;
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

    // queue of file descriptors that are pending a poll add to submit
    private final BlockingQueue<Integer> pendingPollAdds;

    // maps file descriptor to Thread when cancelling poll
    private final Map<Integer, Thread> cancels = new ConcurrentHashMap<>();

    IoUringPoller(boolean subPoller, boolean read) throws IOException {
        IOUringImpl ring = new IOUringImpl(SQ_SIZE, CQ_SIZE, 0);
        EventFD wakeupEvent = null;
        EventFD readyEvent = null;

        if (subPoller) {
            try {
                // event to allow registering with master poller
                readyEvent = new EventFD();
                ring.register_eventfd(readyEvent.efd());

                // wakeup event to allow for shutdown
                wakeupEvent = new EventFD();
                int efd = wakeupEvent.efd();
                IOUtil.configureBlocking(efd, false);
                submitPollAdd(ring, efd, Net.POLLIN, efd);
                enter(ring, 1);
            } catch (Throwable e) {
                ring.close();
                if (readyEvent != null) readyEvent.close();
                if (wakeupEvent != null) wakeupEvent.close();
                throw e;
            }
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

        // optionally start the (per-carrier) submit thread to batch submits
        if (subPoller && BATCH_SUBMITS) {
            this.pendingPollAdds = new LinkedTransferQueue<>();

            var scheduler = JLA.virtualThreadScheduler(Thread.currentThread());
            Thread carrier = JLA.currentCarrierThread();
            String name = carrier.getName() + "-Submitter";
            @SuppressWarnings("restricted")
            var _ = Thread.ofVirtual().scheduler(scheduler)
                    .inheritInheritableThreadLocals(false)
                    .name(name)
                    .uncaughtExceptionHandler((_, e) -> e.printStackTrace())
                    .start(this::submitLoop);
        } else {
            this.pendingPollAdds = null;
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
                if (wakeupEvent != null) wakeupEvent.close();
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
        assert fd > 0;  // fd == 0 used for wakeup

        if (pendingPollAdds == null) {
            // single submit
            synchronized (submitLock) {
                // fd is the user data for IORING_OP_POLL_ADD request
                submitPollAdd(ring, fd, event, fd);
                enter(ring, 1);
            }
        } else {
            // queue request to submit in batch
            boolean inserted = pendingPollAdds.offer(fd);
            assert inserted;
        }
    }

    @Override
    void implDeregister(int fd, boolean polled) throws IOException {
        if (!polled && !isShutdown()) {
            cancels.put(fd, Thread.currentThread());

            synchronized (submitLock) {
                // submit pending requests to ensure OP_POLL_ADD consumed before OP_POLL_REMOVE
                submitPendingPollAdds();

                // fd was the user data for IORING_OP_POLL_ADD request
                // -fd is the user data for IORING_OP_POLL_REMOVE request
                submitPollRemove(ring, fd, -fd);
                enter(ring, 1);
            }

            while (cancels.containsKey(fd) && !isShutdown()) {
                LockSupport.park();
            }
        }
    }

    @Override
    void wakeupPoller() throws IOException {
        if (wakeupEvent == null) {
            throw new UnsupportedOperationException();
        }

        // causes subpoller to wakeup
        wakeupEvent.set();

        // causes submit loop to wakeup
        if (pendingPollAdds != null) {
            pendingPollAdds.offer(0);
        }
    }

    @Override
    int poll(int timeout) throws IOException {
        if (timeout > 0) {
            // timed polls not supported by this Poller
            throw new UnsupportedOperationException();
        }
        boolean block = (timeout == -1);
        int max = block ? MAX_EVENTS_PER_POLL : MAX_EVENTS_PER_SUBPOLL;
        int polled = tryPoll(max);
        if (polled > 0 || !block) {
            return polled;
        } else {
            int ret = ring.enter(0, 1, 0);  // wait for at least one completion
            if (ret < 0) {
                throw new IOException("io_uring_enter failed, ret=" + ret);
            }
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
            if (fd > 0 && (wakeupEvent == null || fd != wakeupEvent.efd())) {
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
     * Submits any pending requests. For use during cancellation to ensure that
     * pending requests are consumed before another request is submitted.
     */
    private void submitPendingPollAdds() throws IOException {
        assert Thread.holdsLock(submitLock);
        if (pendingPollAdds != null) {
            boolean stoleWakeup = false;
            Integer next = pendingPollAdds.poll();
            while (next != null) {
                int fd = next.intValue();
                if (fd == 0) {
                    stoleWakeup = true;
                } else {
                    assert fd > 0;
                    submitPollAdd(ring, fd, event, fd);
                    enter(ring, 1);
                }
                next = pendingPollAdds.poll();
            }

            // return fd 0 to the pending requests if taken here
            if (stoleWakeup) {
                pendingPollAdds.offer(0);
            }
        }
    }

    /**
     * Submit loop to submit pending requests in batch, reducing calls to io_uring_enter.
     */
    private void submitLoop() {
        try {
            while (!isShutdown()) {
                int fd = pendingPollAdds.take();
                assert fd >= 0;
                synchronized (submitLock) {
                    int n = 0;
                    if (fd > 0) {
                        submitPollAdd(ring, fd, event, fd);
                        n++;
                    }
                    Integer next;
                    while (ring.sqfree() > 0 && ((next = pendingPollAdds.poll()) != null)) {
                        fd = next.intValue();
                        assert fd >= 0;
                        if (fd > 0) {
                            submitPollAdd(ring, fd, event, fd);
                            n++;
                        }
                    }
                    if (n > 0) {
                        enter(ring, n);
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /**
     * Invoke io_uring_enter to submit the SQE entries
     */
    private static void enter(IOUringImpl ring, int n) throws IOException {
        int ret = ring.enter(n, 0, 0);
        if (ret < 0) {
            throw new IOException("io_uring_enter failed, ret=" + ret);
        }
        assert ret == n;
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
    }
}
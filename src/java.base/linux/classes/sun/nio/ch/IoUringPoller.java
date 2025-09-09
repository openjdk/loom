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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.ref.Cleaner.Cleanable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import jdk.internal.ref.CleanerFactory;
import sun.nio.ch.iouring.IOUringImpl;
import sun.nio.ch.iouring.Cqe;
import sun.nio.ch.iouring.Sqe;
import jdk.internal.ffi.generated.iouring.*;
import static jdk.internal.ffi.generated.iouring.iouring_h.*;

/**
 * Poller implementation based io_uring.
 *
 * @apiNote This implementation is experimental. There are many design choices, esp.
 * around buffer management, that have to be explored.
 */

public class IoUringPoller extends Poller {
    private static final Arena ARENA = Arena.ofAuto();
    private static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();
    private static final int MAX_READV_BUF_SIZE = 8192;

    // submition and completion queue sizes
    private static final int SQ_SIZE_DEFAULT = 16;

    static final int SQ_SIZE =
        Integer.getInteger("jdk.io_uring.sqsize", SQ_SIZE_DEFAULT);

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

    // maps iovec to in progress readv/writev operations
    private final Map<Long, Op> ops;
    
    static final int sqpoll_idle_time =
        Integer.getInteger("jdk.io_uring.sqpoll_idle", 0);

    // per poller cache of buf/iovec bufs used for readv/writve ops
    private final BlockingQueue<IoBufs> ioBufsQueue;

    IoUringPoller(Poller.Mode mode,
                  boolean subPoller,
                  boolean read,
                  boolean supportIoOps) throws IOException {
	IOUringImpl ring = new IOUringImpl(
            SQ_SIZE, CQ_SIZE, 0, 0, 0, sqpoll_idle_time); 
        EventFD wakeupEvent = null;
        EventFD readyEvent = null;

        if (subPoller) {
            try {
                // event to allow registering with master poller
                readyEvent = new EventFD();
                ring.register_eventfd(readyEvent.efd());

                // wakeup event to allow for shutdown
                if (mode == Poller.Mode.POLLER_PER_CARRIER) {
                    wakeupEvent = new EventFD();
                    int efd = wakeupEvent.efd();
                    IOUtil.configureBlocking(efd, false);
                    submitPollAdd(ring, efd, Net.POLLIN, efd);
                    enter(ring, 1);
                }
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

        // setup if supporting readv/writev ops
        if (supportIoOps) {
            this.ops = new ConcurrentHashMap<>();
            this.ioBufsQueue = new LinkedTransferQueue<>();
        } else {
            this.ops = null;
            this.ioBufsQueue = null;
        }

        // create action to close io_uring instance, register cleaner if this is a subpoller
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

    /**
     * Initiate polling the given file descriptor.
     */
    @Override
    void implRegister(int fd) throws IOException {
        assert fd > 0;  // fd == 0 used for wakeup

        synchronized (submitLock) {
            // fd is the user data for IORING_OP_POLL_ADD request
            submitPollAdd(ring, fd, event, fd);
            enter(ring, 1);
        }
    }

    /**
     * Stop polling of the given file descriptorr if not already polled.
     */
    @Override
    void implDeregister(int fd, boolean polled) throws IOException {
        if (!polled && !isShutdown()) {
            cancels.put(fd, Thread.currentThread());

            synchronized (submitLock) {
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

    /**
     * Uses IORING_OP_READV op to read bytes into the given byte array.
     */
    @Override
    int implRead(int fd, byte[] b, int off, int len, long nanos, BooleanSupplier isOpen)
        throws IOException
    {
        assert ops != null;

        IoBufs bufs = takeIoBufs(len);
        long udata = bufs.vec().address();
        var op = new Op(Thread.currentThread());
        ops.put(udata, op);

        int res = 0;
        try {
            synchronized (submitLock) {
                submitRead(ring, fd, bufs.vec(), udata);
            }
            if (isOpen.getAsBoolean() && !isShutdown()) {
                if (nanos > 0) {
                    LockSupport.parkNanos(nanos);
                } else {
                    LockSupport.park();
                }
            }
        } finally {
            Op previous = ops.remove(udata);
            assert previous == op;

            res = op.result();
            try {
                if (res > 0) {
                    // copy bytes into byte[], need to improve this
                    assert res <= len;
                    MemorySegment dst = MemorySegment.ofArray(b);
                    MemorySegment.copy(bufs.buf(), 0, dst, off, res);
                } else if (res == -1) {
                    // EOF
                } else if (res < 0) {
                    // read failed
                    throw new IOException("IORING_OP_READV failed errno=" + res);
                } else {
                    // read did not complete, need to cancel. If the cancel fails then
                    // we can't return the bufs to the cache.
                    cancelOp(fd, bufs.vec().address());
                    res = IOStatus.UNAVAILABLE;
                }
            } finally {
                if (res != 0) {
                    offerIoBufs(bufs);  // return to cache
                }
            }
        }
        return res;
    }

    @Override
    void wakeupPoller() throws IOException {
        if (wakeupEvent == null) {
            throw new UnsupportedOperationException();
        }

        // causes subpoller to wakeup
        wakeupEvent.set();
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
     * Poll or handle completions up to the given max without blocking. This method also
     * handles the completion of any cancelled operations.
     * @retutn the number of sockets polled and I/O operations completed
     */
    private int tryPoll(int max) {
        int polled = 0;
        Cqe cqe;
        while (polled < max && ((cqe = ring.pollCompletion()) != null)) {
            long udata = cqe.user_data();

            // handle read ops
            if (ops != null) {
                Op op = ops.get(udata);
                if (op != null) {
                    int res = cqe.res();
                    op.setResult((res != 0) ? res : -1);   // map 0 to -1 at EOF
                    LockSupport.unpark(op.thread());
                    polled++;
                    continue;
                }
            }

            // handle poll and cancls ops, user data is fd or -fd
            int fd = (int) udata;
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
     * Invoke io_uring_enter to submit the SQE entries
     */
    private static void enter(IOUringImpl ring, int n) throws IOException {
        if (sqpoll_idle_time > 0) {
            ring.pollingEnter(n);
        } else {
            int ret = ring.enter(n, 0, 0);
            if (ret < 0) {
                throw new IOException("io_uring_enter failed, ret=" + ret);
            }
            assert ret == n;
        }
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

    /**
     * Submit IORING_OP_READV operation.
     * @param fd file descriptior
     * @param iov already populared iov struct
     * @param udata the user data for the READV op
     */
    private static void submitRead(IOUringImpl ring, int fd, MemorySegment iov, long udata)
        throws IOException
    {
        Sqe sqe = new Sqe()
                .opcode(IORING_OP_READV())
                .fd(fd)
                .addr(iov).len(1).off(0)   // one buffer
                .user_data(udata);
        ring.submit(sqe);
        enter(ring, 1);
    }

    /**
     * Cancels an operation submitted with the given user_data.
     */
    private void cancelOp(int fd, long req_udata) throws IOException {
        @SuppressWarnings("restricted")
        MemorySegment address = MemorySegment.ofAddress(req_udata).reinterpret(ADDRESS_SIZE);
        cancels.put(fd, Thread.currentThread());
        synchronized (submitLock) {
            Sqe sqe = new Sqe()
                    .opcode(IORING_OP_ASYNC_CANCEL())
                    .addr(address)
                    .user_data(-fd);   // user data for IORING_OP_ASYNC_CANCEL
            ring.submit(sqe);
            enter(ring, 1);
        }
        while (cancels.containsKey(fd) && !isShutdown()) {
            LockSupport.park();
        }
    }

    private static class Op {
        final Thread thread;
        volatile int result;
        Op(Thread thread) {
            this.thread = thread;
        }
        Thread thread() {
            return thread;
        }
        int result() {
            return result;
        }
        void setResult(int result) {
            this.result = result;
        }
    }

    private static class IoBufs {
        final MemorySegment vec;
        final MemorySegment buf;

        IoBufs() {
            vec = ARENA.allocate(iovec.$LAYOUT());
            buf = ARENA.allocate(MAX_READV_BUF_SIZE);
            iovec.iov_base(vec, buf);
        }

        MemorySegment vec() { return vec; }
        MemorySegment buf() { return buf; }
    }

    private IoBufs takeIoBufs(int len) {
        IoBufs req = ioBufsQueue.poll();
        if (req == null) {
            req = new IoBufs();
        }
        iovec.iov_len(req.vec(), Math.min(len, MAX_READV_BUF_SIZE));
        return req;
    }

    private void offerIoBufs(IoBufs req) {
        ioBufsQueue.offer(req);
    }
}

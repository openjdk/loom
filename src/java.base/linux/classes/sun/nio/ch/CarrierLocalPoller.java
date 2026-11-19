/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import static sun.nio.ch.EPoll.*;

/**
 * A carrier-local I/O poller using EPOLLONESHOT. Each carrier thread owns
 * one instance. VTs that park on I/O register their fd directly with the
 * carrier's epoll fd. No sub-pollers, no master poller.
 *
 * <p>The carrier calls {@link #poll(int)} when idle. When I/O arrives,
 * the blocked VTs are unparked and enqueued to the carrier's own queue.
 * External task submissions wake the carrier via the eventfd.
 *
 * <p>Created only by the Linux DefaultPollerProvider; everything else uses it as a
 * LocalPoller.
 */
final class CarrierLocalPoller implements LocalPoller {

    private static final int ENOENT = 2;
    private static final int MAX_EVENTS = 64;

    private final int epfd;
    private final long pollAddress;
    private final EventFD eventfd;
    private final FdMap fdToThread = new FdMap();

    CarrierLocalPoller() throws IOException {
        int epfd = EPoll.create();
        long address = 0L;
        EventFD eventfd = null;
        try {
            address = EPoll.allocatePollArray(MAX_EVENTS);
            eventfd = new EventFD();
            IOUtil.configureBlocking(eventfd.efd(), false);
            EPoll.ctl(epfd, EPOLL_CTL_ADD, eventfd.efd(), EPOLLIN);
        } catch (Throwable e) {
            // close what was opened, as EPollPoller does
            FileDispatcherImpl.closeIntFD(epfd);
            if (address != 0L) EPoll.freePollArray(address);
            if (eventfd != null) eventfd.close();
            throw e;
        }
        this.epfd = epfd;
        this.pollAddress = address;
        this.eventfd = eventfd;
    }

    /**
     * Register a file descriptor for read or write polling. Called by a VT mounted
     * on this poller's carrier, with its continuation pinned, before parking: only
     * this carrier inserts into the map. The VT is unparked when the fd is ready.
     */
    @Override
    public void register(int fdVal, int event, Thread thread) throws IOException {
        fdToThread.put(fdVal, thread);
        int err = EPoll.ctl(epfd, EPOLL_CTL_MOD, fdVal, (event | EPOLLONESHOT));
        if (err == ENOENT) {
            err = EPoll.ctl(epfd, EPOLL_CTL_ADD, fdVal, (event | EPOLLONESHOT));
        }
        if (err != 0) {
            fdToThread.remove(fdVal, thread);
            throw new IOException("epoll_ctl failed: " + err);
        }
    }

    /**
     * Deregister a file descriptor if the given thread's registration is still in
     * place, i.e. it was unparked by something other than I/O readiness (interrupt,
     * timeout). May be called from any carrier: the thread may run elsewhere by the
     * time it deregisters (work stealing, a sticky wakeup).
     */
    @Override
    public void deregister(int fdVal, Thread thread) {
        if (fdToThread.remove(fdVal, thread)) {
            EPoll.ctl(epfd, EPOLL_CTL_DEL, fdVal, 0);
        }
    }

    /**
     * Poll for I/O events. Returns the number of VTs unparked.
     *
     * @param timeout milliseconds: -1 to block, 0 for non-blocking
     */
    @Override
    public int poll(int timeout) throws IOException {
        if (timeout == 0 && fdToThread.isEmpty()) {
            return 0;
        }
        int n = EPoll.wait(epfd, pollAddress, MAX_EVENTS, timeout);
        int unparked = 0;
        for (int i = 0; i < n; i++) {
            long eventAddress = EPoll.getEvent(pollAddress, i);
            int fd = EPoll.getDescriptor(eventAddress);
            if (fd == eventfd.efd()) {
                eventfd.reset();
            } else {
                Thread vt = fdToThread.take(fd);
                if (vt != null) {
                    LockSupport.unpark(vt);
                    unparked++;
                }
            }
        }
        return unparked;
    }

    /**
     * Wake the carrier from a blocking {@link #poll} call.
     * Called by external threads submitting tasks to this carrier.
     */
    @Override
    public void wakeup() throws IOException {
        eventfd.set();
    }

    @Override
    public void close() {
        try {
            FileDispatcherImpl.closeIntFD(epfd);
        } catch (IOException _) { }
        EPoll.freePollArray(pollAddress);
        try {
            eventfd.close();
        } catch (IOException _) { }
    }

    /**
     * The fd to waiting thread map. Only the owning carrier inserts, so only it
     * allocates pages and grows the directory; any carrier may remove. Indexed
     * directly by fd, in pages that are never freed: growing copies page references
     * only, so a remover that reads an older directory still reaches the page its
     * entry is in (the entry was inserted before the removal, and the directory is
     * volatile). Nothing is allocated once the pages for the fds in use exist.
     */
    private static final class FdMap {
        private static final int PAGE_SHIFT = 8;
        private static final int PAGE_MASK = (1 << PAGE_SHIFT) - 1;
        private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Thread[].class);
        private static final VarHandle PAGE = MethodHandles.arrayElementVarHandle(Thread[][].class);
        private static final VarHandle COUNT;
        static {
            try {
                COUNT = MethodHandles.lookup().findVarHandle(FdMap.class, "count", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile Thread[][] directory = new Thread[16][];

        // entries in the map; only read by isEmpty()
        private volatile int count;

        /** Owner only. */
        void put(int fd, Thread thread) {
            Thread[] page = page(fd);
            if (page == null) {
                page = addPage(fd);
            }
            if (SLOT.getAndSet(page, fd & PAGE_MASK, thread) == null) {
                COUNT.getAndAdd(this, 1);
            }
        }

        /** Removes and returns the thread registered for fd, or null. */
        Thread take(int fd) {
            Thread[] page = page(fd);
            if (page == null) {
                return null;
            }
            Thread thread = (Thread) SLOT.getAndSet(page, fd & PAGE_MASK, (Thread) null);
            if (thread != null) {
                COUNT.getAndAdd(this, -1);
            }
            return thread;
        }

        /** Removes the entry for fd only if it is the given thread's. */
        boolean remove(int fd, Thread thread) {
            Thread[] page = page(fd);
            if (page != null && SLOT.compareAndSet(page, fd & PAGE_MASK, thread, (Thread) null)) {
                COUNT.getAndAdd(this, -1);
                return true;
            }
            return false;
        }

        boolean isEmpty() {
            return count == 0;
        }

        private Thread[] page(int fd) {
            int p = fd >>> PAGE_SHIFT;
            Thread[][] dir = directory;
            return (p < dir.length) ? (Thread[]) PAGE.getAcquire(dir, p) : null;
        }

        /** Owner only. */
        private Thread[] addPage(int fd) {
            int p = fd >>> PAGE_SHIFT;
            Thread[][] dir = directory;
            Thread[] page = new Thread[1 << PAGE_SHIFT];
            if (p < dir.length) {
                PAGE.setRelease(dir, p, page);
            } else {
                Thread[][] bigger = Arrays.copyOf(dir, Math.max(p + 1, dir.length * 2));
                bigger[p] = page;
                directory = bigger;
            }
            return page;
        }
    }
}

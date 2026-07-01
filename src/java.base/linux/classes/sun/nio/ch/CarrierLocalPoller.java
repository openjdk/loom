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
import java.util.HashMap;
import java.util.concurrent.locks.LockSupport;
import static sun.nio.ch.EPoll.*;

/**
 * A carrier-local I/O poller. Each carrier thread owns one instance.
 * VTs that park on I/O register their fd directly with the carrier's
 * epoll fd. No sub-pollers, no master poller, no ConcurrentHashMap.
 *
 * <p>The carrier calls {@link #poll(int)} when idle. When I/O arrives,
 * the blocked VTs are unparked and enqueued to the carrier's own queue.
 * External task submissions wake the carrier via the eventfd.
 */
public final class CarrierLocalPoller {

    private static final int ENOENT = 2;
    private static final int MAX_EVENTS = 64;

    private final int epfd;
    private final long pollAddress;
    private final EventFD eventfd;
    private final HashMap<Integer, Thread> fdToThread = new HashMap<>();

    public CarrierLocalPoller() throws IOException {
        this.epfd = EPoll.create();
        this.pollAddress = EPoll.allocatePollArray(MAX_EVENTS);
        this.eventfd = new EventFD();
        IOUtil.configureBlocking(eventfd.efd(), false);
        EPoll.ctl(epfd, EPOLL_CTL_ADD, eventfd.efd(), EPOLLIN);
    }

    /**
     * Register a file descriptor for read or write polling. Called by VTs
     * on this carrier before parking. The VT is unparked when the fd is ready.
     */
    public void register(int fdVal, int event, Thread thread) throws IOException {
        fdToThread.put(fdVal, thread);
        int err = EPoll.ctl(epfd, EPOLL_CTL_MOD, fdVal, (event | EPOLLONESHOT));
        if (err == ENOENT) {
            err = EPoll.ctl(epfd, EPOLL_CTL_ADD, fdVal, (event | EPOLLONESHOT));
        }
        if (err != 0) {
            fdToThread.remove(fdVal);
            throw new IOException("epoll_ctl failed: " + err);
        }
    }

    /**
     * Deregister a file descriptor. Called if the VT was unparked by
     * something other than I/O readiness (e.g. interrupt, timeout).
     */
    public void deregister(int fdVal) {
        if (fdToThread.remove(fdVal) != null) {
            EPoll.ctl(epfd, EPOLL_CTL_DEL, fdVal, 0);
        }
    }

    /**
     * Poll for I/O events. Returns the number of VTs unparked.
     *
     * @param timeout milliseconds: -1 to block, 0 for non-blocking
     */
    public int poll(int timeout) throws IOException {
        int n = EPoll.wait(epfd, pollAddress, MAX_EVENTS, timeout);
        int unparked = 0;
        for (int i = 0; i < n; i++) {
            long eventAddress = EPoll.getEvent(pollAddress, i);
            int fd = EPoll.getDescriptor(eventAddress);
            if (fd == eventfd.efd()) {
                eventfd.reset();
            } else {
                Thread vt = fdToThread.remove(fd);
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
    public void wakeup() throws IOException {
        eventfd.set();
    }

    /**
     * Returns true if there are fds registered for polling.
     */
    public boolean hasPendingFds() {
        return !fdToThread.isEmpty();
    }
}

/*
 * Copyright (c) 2017, 2023, Oracle and/or its affiliates. All rights reserved.
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
import java.lang.ref.Cleaner.Cleanable;
import jdk.internal.ref.CleanerFactory;
import static sun.nio.ch.EPoll.*;

/**
 * Poller implementation based on the epoll facility.
 */

class EPollPoller extends Poller {
    private static final int ENOENT = 2;

    private final int epfd;
    private final int event;
    private final int maxEvents;
    private final long address;
    private final EventFD eventfd;  // wakeup event, used for shutdown

    // close action, and cleaner if this is subpoller
    private final Runnable closer;
    private final Cleanable cleaner;

    EPollPoller(boolean subPoller, boolean read) throws IOException {
        int maxEvents = (subPoller) ? 16 : 64;

        int epfd = -1;
        long address = 0L;
        EventFD eventfd = null;
        try {
            epfd = EPoll.create();
            address = EPoll.allocatePollArray(maxEvents);

            // register event with epoll to allow for wakeup
            eventfd = new EventFD();
            IOUtil.configureBlocking(eventfd.efd(), false);
            EPoll.ctl(epfd, EPOLL_CTL_ADD, eventfd.efd(), EPOLLIN);
        } catch (Throwable e) {
            if (epfd >= 0) FileDispatcherImpl.closeIntFD(epfd);
            if (address != 0L) EPoll.freePollArray(address);
            if (eventfd != null) eventfd.close();
            throw e;
        }

        this.epfd = epfd;
        this.event = (read) ? EPOLLIN : EPOLLOUT;
        this.maxEvents = maxEvents;
        this.address = address;
        this.eventfd = eventfd;

        // create action to close epoll instance, register cleaner if this is a subpoller
        this.closer = closer(epfd, address, eventfd);
        if (subPoller) {
            this.cleaner = CleanerFactory.cleaner().register(this, closer);
        } else {
            this.cleaner = null;
        }
    }

    /**
     * Returns an action to close the epoll instance and release other resources.
     */
    private static Runnable closer(int epfd, long address, EventFD eventfd) {
        return () -> {
            try {
                FileDispatcherImpl.closeIntFD(epfd);
                EPoll.freePollArray(address);
                eventfd.close();
            } catch (IOException _) { }
        };
    }

    @Override
    void close() {
        if (cleaner != null) {
            cleaner.clean();
        } else {
            closer.run();
        }
    }

    @Override
    int fdVal() {
        return epfd;
    }

    @Override
    void implRegister(int fdVal) throws IOException {
        // re-enable if already registered but disabled (previously polled)
        int err = EPoll.ctl(epfd, EPOLL_CTL_MOD, fdVal, (event | EPOLLONESHOT));
        if (err == ENOENT)
            err = EPoll.ctl(epfd, EPOLL_CTL_ADD, fdVal, (event | EPOLLONESHOT));
        if (err != 0)
            throw new IOException("epoll_ctl failed: " + err);
    }

    @Override
    void implDeregister(int fdVal, boolean polled) {
        // event is disabled if already polled
        if (!polled) {
            EPoll.ctl(epfd, EPOLL_CTL_DEL, fdVal, 0);
        }
    }

    @Override
    void wakeupPoller() throws IOException {
        eventfd.set();
    }

    @Override
    int poll(int timeout) throws IOException {
        int n = EPoll.wait(epfd, address, maxEvents, timeout);
        int polled = 0;
        int i = 0;
        while (i < n) {
            long eventAddress = EPoll.getEvent(address, i);
            int fd = EPoll.getDescriptor(eventAddress);
            if (fd != eventfd.efd()) {
                polled(fd);
                polled++;
            }
            i++;
        }
        return polled;
    }
}


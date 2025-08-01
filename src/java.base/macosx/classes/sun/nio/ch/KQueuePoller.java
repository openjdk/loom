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
import static sun.nio.ch.KQueue.*;

/**
 * Poller implementation based on the kqueue facility.
 */
class KQueuePoller extends Poller {
    private final int kqfd;
    private final int filter;
    private final int maxEvents;
    private final long address;
    private final Cleanable cleaner;

    // file descriptors used for wakeup
    private final int fd0;
    private final int fd1;

    KQueuePoller(boolean subPoller, boolean read) throws IOException {
        this.kqfd = KQueue.create();
        this.filter = (read) ? EVFILT_READ : EVFILT_WRITE;
        this.maxEvents = (subPoller) ? 64 : 512;
        this.address = KQueue.allocatePollArray(maxEvents);

        long fds =  IOUtil.makePipe(false);
        this.fd0 = (int) (fds >>> 32);
        this.fd1 = (int) fds;
        KQueue.register(kqfd, fd0, EVFILT_READ, EV_ADD);

        this.cleaner = CleanerFactory.cleaner()
                .register(this, releaser(kqfd, address, fd0, fd1));
    }

    @Override
    void close() {
        cleaner.clean();
    }

    /**
     * Releases the kqueue instance and other resources.
     */
    private static Runnable releaser(int kqfd, long address, int fd0, int fd1) {
        return () -> {
            try {
                FileDispatcherImpl.closeIntFD(kqfd);
                KQueue.freePollArray(address);
                FileDispatcherImpl.closeIntFD(fd0);
                FileDispatcherImpl.closeIntFD(fd1);
            } catch (IOException _) { }
        };
    }

    @Override
    int fdVal() {
        return kqfd;
    }

    @Override
    void implRegister(int fdVal) throws IOException {
        int err = KQueue.register(kqfd, fdVal, filter, (EV_ADD|EV_ONESHOT));
        if (err != 0)
            throw new IOException("kevent failed: " + err);
    }

    @Override
    void implDeregister(int fdVal, boolean polled) {
        // event was deleted if already polled
        if (!polled) {
            KQueue.register(kqfd, fdVal, filter, EV_DELETE);
        }
    }

    @Override
    void wakeupPoller() throws IOException {
        IOUtil.write1(fd1, (byte)0);
    }

    @Override
    int poll(int timeout) throws IOException {
        int n = KQueue.poll(kqfd, address, maxEvents, timeout);
        int i = 0;
        while (i < n) {
            long keventAddress = KQueue.getEvent(address, i);
            int fdVal = KQueue.getDescriptor(keventAddress);
            if (fdVal != fd0) {
                polled(fdVal);
            }
            i++;
        }
        return n;
    }
}

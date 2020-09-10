/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
import static sun.nio.ch.WEPoll.*;

/**
 * Poller implementation based on wepoll.
 */
class WEPollPoller extends Poller {
    private static final int MAX_EVENTS_TO_POLL = 512;
    private static final int ENOENT = 2;

    private final long handle;
    private final int event;
    private final long address;

    WEPollPoller(boolean read) throws IOException {
        this.handle = WEPoll.create();
        this.event = (read) ? EPOLLIN : EPOLLOUT;
        this.address = WEPoll.allocatePollArray(MAX_EVENTS_TO_POLL);
    }

    @Override
    protected void implRegister(int fdVal) throws IOException {
        // re-arm
        int err = WEPoll.ctl(handle, EPOLL_CTL_MOD, fdVal, (event | EPOLLONESHOT));
        if (err == ENOENT)
            err = WEPoll.ctl(handle, EPOLL_CTL_ADD, fdVal, (event | EPOLLONESHOT));
        if (err != 0)
            throw new IOException("epoll_ctl failed: " + err);
    }

    @Override
    protected void implDeregister(int fdVal) {
        WEPoll.ctl(handle, EPOLL_CTL_DEL, fdVal, 0);
    }

    @Override
    public void run() {
        try {
            for (;;) {
                int n = WEPoll.wait(handle, address, MAX_EVENTS_TO_POLL, -1);
                while (n-- > 0) {
                    long event = WEPoll.getEvent(address, n);
                    long s = (int) WEPoll.getSocket(event);
                    polled((int) s);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}


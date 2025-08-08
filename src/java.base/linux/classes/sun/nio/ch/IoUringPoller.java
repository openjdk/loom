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

import java.io.IOException;
import java.lang.ref.Cleaner.Cleanable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import jdk.internal.ref.CleanerFactory;
import sun.nio.ch.iouring.IoUring;

/**
 * Poller implementation based io_uring.
 */

public class IoUringPoller extends Poller implements BiConsumer<Long, Integer> {
    private final int event;
    private final IoUring ring;
    private final EventFD readyEvent;   // completions posted to CQ ring
    private final EventFD wakeupEvent;
    private final Cleanable cleaner;

    // used to coordinate access to submission queue
    private final Object submitLock = new Object();

    // maps file descriptor to Thread when cancelling poll
    private final Map<Integer, Thread> cancels = new ConcurrentHashMap<>();

    IoUringPoller(boolean subPoller, boolean read) throws IOException {
        IoUring ring = IoUring.create();

        if (subPoller) {
            this.readyEvent = new EventFD();
            ring.register_eventfd(readyEvent.efd());
        } else {
            this.readyEvent = null;
        }

        this.wakeupEvent = new EventFD();
        IOUtil.configureBlocking(wakeupEvent.efd(), false);
        int efd = wakeupEvent.efd();
        ring.poll_add(efd, Net.POLLIN, efd);

        this.event = (read) ? Net.POLLIN : Net.POLLOUT;
        this.ring = ring;
        this.cleaner = CleanerFactory.cleaner()
                .register(this, releaser(ring, readyEvent, wakeupEvent));
    }

    /**
     * Releases resources.
     */
    private static Runnable releaser(IoUring ring, EventFD readyEvent, EventFD wakeupEvent) {
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
        cleaner.clean();
    }

    @Override
    int fdVal() {
        if (readyEvent == null) {
            throw new UnsupportedOperationException();
        } else {
            return readyEvent.efd();
        }
    }

    @Override
    void pollerPolled() throws IOException {
        readyEvent.reset();
    }

    @Override
    void implRegister(int fd) throws IOException {
        assert fd != 0;
        synchronized (submitLock) {
            long data = fd;
            ring.poll_add(fd, event, data);
        }
    }

    @Override
    void implDeregister(int fd, boolean polled) throws IOException {
        if (!polled && !isShutdown()) {
            cancels.put(fd, Thread.currentThread());
            synchronized (submitLock) {
                long data = -fd;
                ring.poll_remove(fd, data);
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
        return ring.poll(this, block);
    }

    @Override
    public void accept(Long data, Integer errno) {
        int fd = data.intValue();
        if (fd > 0 && fd != wakeupEvent.efd()) {
            // poll done
            polled(fd);
        } else if (fd < 0) {
            // cancel done
            Thread t = cancels.remove(-fd);
            if (t != null) {
                LockSupport.unpark(t);
            }
        }
    }
}
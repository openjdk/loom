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
import jdk.internal.vm.ContinuationSupport;

/**
 * Default PollerProvider for Linux.
 */
class DefaultPollerProvider extends PollerProvider {
    private static final boolean USE_IOURING;
    private static final boolean USE_IORING_OP_READ;
    private static final boolean USE_IORING_OP_WRITE;
    static {
        String s = System.getProperty("jdk.io_uring");
        if ("".equals(s) || Boolean.parseBoolean(s)) {
            USE_IOURING = true;
            s = System.getProperty("jdk.io_uring.read");
            USE_IORING_OP_READ = "".equals(s) || Boolean.parseBoolean(s);
            s = System.getProperty("jdk.io_uring.write");
            USE_IORING_OP_WRITE = "".equals(s) || Boolean.parseBoolean(s);
        } else {
            USE_IOURING = false;
            USE_IORING_OP_READ = false;
            USE_IORING_OP_WRITE = false;
        }
    }

    DefaultPollerProvider(Poller.Mode mode) {
        super(mode);
    }

    DefaultPollerProvider() {
        this(ContinuationSupport.isSupported()
                ? Poller.Mode.VTHREAD_POLLERS
                : Poller.Mode.SYSTEM_THREADS);
    }

    @Override
    int defaultReadPollers() {
        int ncpus = Runtime.getRuntime().availableProcessors();
        return switch (pollerMode()) {
            case SYSTEM_THREADS  -> Math.max(Integer.highestOneBit(ncpus / 4), 1);
            case VTHREAD_POLLERS -> Math.min(Integer.highestOneBit(ncpus), 32);
            default              -> super.defaultReadPollers();
        };
    }

    @Override
    Poller readPoller(boolean subPoller) throws IOException {
        Poller.Mode mode = pollerMode();
        if (USE_IOURING) {
            // read poller is system thread in SYSTEM_THREADS mode
            boolean supportReadOps = USE_IORING_OP_READ
                    && ((mode == Poller.Mode.SYSTEM_THREADS) || subPoller);
            return new IoUringPoller(mode, subPoller, true, supportReadOps);
        } else {
            return new EPollPoller(mode, subPoller, true);
        }
    }

    @Override
    Poller writePoller(boolean subPoller) throws IOException {
        Poller.Mode mode = pollerMode();
        if (USE_IOURING) {
            // write poller is system thread in SYSTEM_THREADS and POLLER_PER_CARRIER modes
            boolean supportWriteOps = USE_IORING_OP_WRITE
                    && ((mode != Poller.Mode.VTHREAD_POLLERS) || subPoller);
            return new IoUringPoller(mode, subPoller, false, supportWriteOps);
        } else {
            return new EPollPoller(mode, subPoller, false);
        }
    }

    @Override
    boolean supportReadOps() {
        return USE_IORING_OP_READ;
    }

    @Override
    boolean supportWriteOps() {
        return USE_IORING_OP_WRITE;
    }
}

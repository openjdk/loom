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
    static {
        String s = System.getProperty("jdk.io_uring");
        USE_IOURING = "".equals(s) || Boolean.parseBoolean(s);
    }

    DefaultPollerProvider() { }

    @Override
    Poller.Mode defaultPollerMode() {
        if (ContinuationSupport.isSupported()) {
            return USE_IOURING ? Poller.Mode.PER_CARRIER : Poller.Mode.VTHREAD_POLLERS;
        } else {
            return Poller.Mode.SYSTEM_THREADS;
        }
    }

    @Override
    int defaultReadPollers(Poller.Mode mode) {
        int ncpus = Runtime.getRuntime().availableProcessors();
        return switch (mode) {
            case SYSTEM_THREADS  -> Math.max(Integer.highestOneBit(ncpus / 4), 1);
            case VTHREAD_POLLERS -> Math.min(Integer.highestOneBit(ncpus), 32);
            default              -> super.defaultReadPollers(mode);
        };
    }

    @Override
    Poller readPoller(boolean subPoller) throws IOException {
        if (USE_IOURING) {
            return new IoUringPoller(subPoller, true);
        } else {
            return new EPollPoller(subPoller, true);
        }
    }

    @Override
    Poller writePoller(boolean subPoller) throws IOException {
        if (USE_IOURING) {
            return new IoUringPoller(subPoller, false);
        } else {
            return new EPollPoller(subPoller, false);
        }
    }
}

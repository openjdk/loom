/*
 * Copyright (c) 2022, 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.io.FileDescriptor;
import java.io.IOException;

abstract class UnixDispatcher extends NativeDispatcher {
    private static final boolean SUPPORTS_PENDING_SIGNALS = NativeThread.supportPendingSignals();

    @Override
    void close(FileDescriptor fd) throws IOException {
        close0(fd);
    }

    private void signalThreads(NativeThread reader, NativeThread writer) {
        if (NativeThread.isNativeThread(reader))
            reader.signal();
        if (NativeThread.isNativeThread(writer))
            writer.signal();
    }

    @Override
    void implPreClose(FileDescriptor fd, NativeThread reader, NativeThread writer) throws IOException {
        if (SUPPORTS_PENDING_SIGNALS) {
            signalThreads(reader, writer);
        }
        preClose0(fd);
        if (!SUPPORTS_PENDING_SIGNALS) {
            signalThreads(reader, writer);
        }
    }

    private static native void close0(FileDescriptor fd) throws IOException;

    private static native void preClose0(FileDescriptor fd) throws IOException;

    static native void init();

    static {
        IOUtil.load();
        init();
    }
}

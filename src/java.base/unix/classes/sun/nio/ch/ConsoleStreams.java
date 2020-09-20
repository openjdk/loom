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

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.VirtualThreads;

public class ConsoleStreams {
    private ConsoleStreams() { }

    public static final ConsoleInputStream in = new ConsoleInputStream(FileDescriptor.in);
    public static final ConsoleOutputStream out = new ConsoleOutputStream(FileDescriptor.out);
    public static final ConsoleOutputStream err = new ConsoleOutputStream(FileDescriptor.err);

    // Holder class to avoid loading of NativeDispatcher during initPhase1
    private static class Holder {
        static final NativeDispatcher nd = new FileDispatcherImpl();
    }

    private static NativeDispatcher nd() {
        return Holder.nd;
    }

    private static void park(FileDescriptor fd, int event) throws IOException {
        assert Thread.currentThread().isVirtual();
        int fdVal = SharedSecrets.getJavaIOFileDescriptorAccess().get(fd);
        Poller.register(fdVal, event);
        try {
            VirtualThreads.park();
        } finally {
            Poller.deregister(fdVal, event);
        }
    }

    /**
     * Returns true if the out or err streams are locked by the given thread.
     */
    public static boolean isOutOrErrLocked(Thread thread) {
        return (thread == out.currentWriter() || thread == err.currentWriter());
    }

    private static class ConsoleInputStream extends InputStream {
        private final FileDescriptor fd;
        private final ReentrantLock readLock = new ReentrantLock();

        public ConsoleInputStream(FileDescriptor fd) {
            this.fd = fd;
        }

        private int tryRead(byte[] b, int off, int len) throws IOException {
            ByteBuffer dst = Util.getTemporaryDirectBuffer(len);
            assert dst.position() == 0;
            try {
                int n = nd().read(fd, ((DirectBuffer)dst).address(), len);
                if (n > 0) {
                    dst.get(b, off, n);
                }
                return n;
            } finally {
                Util.offerFirstTemporaryDirectBuffer(dst);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] a = new byte[1];
            int n = read(a, 0, 1);
            return (n > 0) ? (a[0] & 0xff) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            } else {
                readLock.lock();
                try {
                    if (Thread.currentThread().isVirtual()) {
                        park(fd, Net.POLLIN);
                    }
                    int n;
                    do {
                        n = tryRead(b, off, len);
                    } while (n == IOStatus.INTERRUPTED);
                    return n;
                } finally {
                    readLock.unlock();
                }
            }
        }

        @Override
        public void close() throws IOException {
            // not fully implemented yet, should dup to /dev/null
            nd().close(fd);
        }
    }

    private static class ConsoleOutputStream extends OutputStream {
        private final FileDescriptor fd;
        private final ReentrantLock writeLock = new ReentrantLock();
        private volatile Thread writer;

        ConsoleOutputStream(FileDescriptor fd) {
            this.fd = fd;
        }

        Thread currentWriter() {
            return writer;
        }

        private int tryWrite(byte[] b, int off, int len) throws IOException {
            ByteBuffer src = Util.getTemporaryDirectBuffer(len);
            assert src.position() == 0;
            try {
                src.put(b, off, len);
                return nd().write(fd, ((DirectBuffer)src).address(), len);
            } finally {
                Util.offerFirstTemporaryDirectBuffer(src);
            }
        }

        private int implWrite(byte[] b, int off, int len) throws IOException {
            int n;
            Thread thread = Thread.currentThread();
            if (thread.isVirtual()) {
                park(fd, Net.POLLOUT);
            }
            do {
                n = tryWrite(b, off, len);
            } while (n == IOStatus.INTERRUPTED);
            return n;
        }

        @Override
        public void write(int b) throws IOException {
            byte[] a = new byte[]{(byte) b};
            write(a, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            Thread thread = Thread.currentThread();
            if (len > 0) {
                writeLock.lock();
                try {
                    writer = thread;
                    int pos = off;
                    int end = off + len;
                    while (pos < end) {
                        int size = Math.min((end - pos), 16*1024);
                        int n = implWrite(b, pos, size);
                        pos += n;
                    }
                } finally {
                    writer = null;
                    writeLock.unlock();
                }
            }
        }

        @Override
        public void close() throws IOException {
            // not fully implemented yet, should dup to /dev/null
            nd().close(fd);
        }
    }
}

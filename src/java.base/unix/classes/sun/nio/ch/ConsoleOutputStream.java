/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.VirtualThreads;

public class ConsoleOutputStream extends OutputStream {

    // Holder class to avoid loading during initPhase1
    private static class NativeDispatcherHolder {
        static final NativeDispatcher ND = new FileDispatcherImpl();
        static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    }

    private static NativeDispatcher nd() {
        return NativeDispatcherHolder.ND;
    }

    private static boolean isVirtualThreadParking(Thread thread) {
        return NativeDispatcherHolder.JLA.isVirtualThreadParking(thread);
    }

    private final ReentrantLock writeLock = new ReentrantLock();
    private final FileDescriptor fd;

    // set to true when the file descriptor is in non-blocking mode
    private volatile boolean nonBlocking;

    private void configureNonBlockingIfNeeded() throws IOException {
        if (!nonBlocking && Thread.currentThread().isVirtual()) {
            IOUtil.configureBlocking(fd, false);
            nonBlocking = true;
        }
    }

    public ConsoleOutputStream(FileDescriptor fd) {
        this.fd = fd;
    }

    private void park(int event) throws IOException {
        Thread thread = Thread.currentThread();
        if (thread.isVirtual() && !isVirtualThreadParking(thread)) {
            int fdVal = fdVal(fd);
            Poller.register(fdVal, event);
            try {
                VirtualThreads.park();
            } finally {
                Poller.deregister(fdVal, event);
            }
        } else {
            // kernel thread, or virtual thread that is parking
            Net.poll(fd, event, -1);
        }

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
        configureNonBlockingIfNeeded();
        int n = tryWrite(b, off, len);
        while (IOStatus.okayToRetry(n)) {
            park(Net.POLLOUT);
            n = tryWrite(b, off, len);
        }
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
        if (len > 0) {
            writeLock.lock();
            try {
                int pos = off;
                int end = off + len;
                while (pos < end) {
                    int size = Math.min((end - pos), 16*1024);
                    int n = implWrite(b, pos, size);
                    pos += n;
                }
            } finally {
                writeLock.unlock();
            }
        }
    }

    @Override
    public void close() throws IOException {
        // open /dev/null
        // dup2
        nd().close(fd);
    }

    private static int fdVal(FileDescriptor fd) {
        int fdVal = SharedSecrets.getJavaIOFileDescriptorAccess().get(fd);
        assert fdVal == IOUtil.fdVal(fd);
        return fdVal;
    }
}
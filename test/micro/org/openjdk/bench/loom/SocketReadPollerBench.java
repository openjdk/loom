/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package org.openjdk.bench.loom;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RPC-style benchmark for virtual thread poller registration overhead.
 *
 * <p>A single platform thread runs a non-blocking NIO echo server
 * spinning on {@code selectNow()}. Persistent JMH virtual threads
 * do blocking I/O round-trips through the poller path.
 *
 * <p>Each JMH iteration does a write-then-blocking-read round-trip.
 * The blocking read exercises the poller registration path
 * (epoll_ctl for one-shot, or skip for edge-triggered).
 *
 * <p>Usage (constrain to 1 carrier to make per-op CPU savings visible):
 * <pre>
 * java -jar benchmarks.jar SocketReadPollerBench -t 100 -p readSize=1 \
 *   -jvmArgs "-Djdk.pollerMode=2 -Djdk.virtualThreadScheduler.parallelism=1"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(value = 3, jvmArgsAppend = "-Djmh.executor=VIRTUAL")
@Threads(100)
public class SocketReadPollerBench {

    @Param({"1", "64"})
    int readSize;

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread serverThread;
    private volatile boolean serverRunning;
    private int serverPort;

    // Guard: JMH VIRTUAL executor may call Scope.Benchmark setup() per thread.
    private final AtomicBoolean serverStarted = new AtomicBoolean();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        if (!serverStarted.compareAndSet(false, true)) {
            return;
        }
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1000);
        serverPort = serverChannel.socket().getLocalPort();
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        serverRunning = true;
        int size = readSize;

        // Single platform thread: NIO spin loop echo server
        serverThread = Thread.ofPlatform().daemon(true).start(() -> {
            try {
                while (serverRunning) {
                    selector.selectNow();
                    var it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        if (key.isAcceptable()) {
                            SocketChannel ch = serverChannel.accept();
                            if (ch != null) {
                                ch.configureBlocking(false);
                                ch.socket().setTcpNoDelay(true);
                                ch.register(selector, SelectionKey.OP_READ,
                                        ByteBuffer.allocateDirect(size));
                            }
                        } else if (key.isReadable()) {
                            SocketChannel ch = (SocketChannel) key.channel();
                            ByteBuffer buf = (ByteBuffer) key.attachment();
                            int n = ch.read(buf);
                            if (n < 0) {
                                key.cancel();
                                ch.close();
                            } else if (!buf.hasRemaining()) {
                                buf.flip();
                                while (buf.hasRemaining()) {
                                    ch.write(buf);
                                }
                                buf.clear();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (serverRunning) e.printStackTrace();
            }
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        serverRunning = false;
        selector.wakeup();
        serverThread.join(5000);
        selector.close();
        serverChannel.close();
    }

    /**
     * Per-thread state: owns one TCP connection to the NIO server.
     * Uses direct ByteBuffers to avoid heap-to-direct copies.
     */
    @State(Scope.Thread)
    public static class Connection {
        SocketChannel channel;
        ByteBuffer readBuf;
        ByteBuffer writeBuf;

        @Setup(Level.Trial)
        public void setup(SocketReadPollerBench bench) throws Exception {
            channel = SocketChannel.open(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), bench.serverPort));
            channel.socket().setTcpNoDelay(true);
            readBuf = ByteBuffer.allocateDirect(bench.readSize);
            writeBuf = ByteBuffer.allocateDirect(bench.readSize);
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            channel.close();
        }
    }

    /**
     * One RPC round-trip on a persistent virtual thread. The VT writes
     * a request then blocking-reads the response. The blocking read
     * parks through the poller because the NIO server hasn't echoed
     * the response yet at the time of the read call.
     */
    @Benchmark
    public int rpcRoundTrip(Connection conn) throws Exception {
        conn.writeBuf.clear();
        conn.channel.write(conn.writeBuf);
        conn.readBuf.clear();
        readFully(conn.channel, conn.readBuf);
        return conn.readBuf.position();
    }

    private static void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n < 0) throw new IOException("unexpected EOF");
        }
    }
}

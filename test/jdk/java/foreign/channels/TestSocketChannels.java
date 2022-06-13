/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @enablePreview
 * @library /test/lib
 * @modules java.base/sun.nio.ch
 * @key randomness
 * @run testng/othervm TestSocketChannels
 */

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemorySession;
import org.testng.annotations.*;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.testng.Assert.*;

/**
 * Tests consisting of buffer views with synchronous NIO network channels.
 */
public class TestSocketChannels extends AbstractChannelsTest {

    static final Class<IllegalStateException> ISE = IllegalStateException.class;
    static final Class<WrongThreadException> WTE = WrongThreadException.class;

    @Test(dataProvider = "closeableSessions")
    public void testBasicIOWithClosedSegment(Supplier<MemorySession> sessionSupplier)
        throws Exception
    {
        try (var channel = SocketChannel.open();
             var server = ServerSocketChannel.open();
             var connectedChannel = connectChannels(server, channel)) {
            MemorySession session = sessionSupplier.get();
            ByteBuffer bb = segmentBufferOfSize(session, 16);
            ((MemorySession)session).close();
            assertMessage(expectThrows(ISE, () -> channel.read(bb)),                           "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.read(new ByteBuffer[] {bb})),        "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.read(new ByteBuffer[] {bb}, 0, 1)),  "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.write(bb)),                          "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.write(new ByteBuffer[] {bb})),       "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.write(new ByteBuffer[] {bb}, 0 ,1)), "Already closed");
        }
    }

    @Test(dataProvider = "closeableSessions")
    public void testScatterGatherWithClosedSegment(Supplier<MemorySession> sessionSupplier)
        throws Exception
    {
        try (var channel = SocketChannel.open();
             var server = ServerSocketChannel.open();
             var connectedChannel = connectChannels(server, channel)) {
            MemorySession session = (MemorySession)sessionSupplier.get();
            ByteBuffer[] buffers = segmentBuffersOfSize(8, session, 16);
            session.close();
            assertMessage(expectThrows(ISE, () -> channel.write(buffers)),       "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.read(buffers)),        "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.write(buffers, 0 ,8)), "Already closed");
            assertMessage(expectThrows(ISE, () -> channel.read(buffers, 0, 8)),  "Already closed");
        }
    }

    @Test(dataProvider = "allSessions")
    public void testBasicIO(Supplier<MemorySession> sessionSupplier)
        throws Exception
    {
        MemorySession session;
        try (var sc1 = SocketChannel.open();
             var ssc = ServerSocketChannel.open();
             var sc2 = connectChannels(ssc, sc1);
             var scp = closeableSessionOrNull(session = sessionSupplier.get())) {
            MemorySegment segment1 = MemorySegment.allocateNative(10, 1, session);
            MemorySegment segment2 = MemorySegment.allocateNative(10, 1, session);
            for (int i = 0; i < 10; i++) {
                segment1.set(JAVA_BYTE, i, (byte) i);
            }
            ByteBuffer bb1 = segment1.asByteBuffer();
            ByteBuffer bb2 = segment2.asByteBuffer();
            assertEquals(sc1.write(bb1), 10);
            assertEquals(sc2.read(bb2), 10);
            assertEquals(bb2.flip(), ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}));
        }
    }

    @Test
    public void testBasicHeapIOWithGlobalSession() throws Exception {
        try (var sc1 = SocketChannel.open();
             var ssc = ServerSocketChannel.open();
             var sc2 = connectChannels(ssc, sc1)) {
            var segment1 = MemorySegment.ofArray(new byte[10]);
            var segment2 = MemorySegment.ofArray(new byte[10]);
            for (int i = 0; i < 10; i++) {
                segment1.set(JAVA_BYTE, i, (byte) i);
            }
            ByteBuffer bb1 = segment1.asByteBuffer();
            ByteBuffer bb2 = segment2.asByteBuffer();
            assertEquals(sc1.write(bb1), 10);
            assertEquals(sc2.read(bb2), 10);
            assertEquals(bb2.flip(), ByteBuffer.wrap(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}));
        }
    }

    @Test(dataProvider = "confinedSessions")
    public void testIOOnConfinedFromAnotherThread(Supplier<MemorySession> sessionSupplier)
        throws Exception
    {
        try (var channel = SocketChannel.open();
             var server = ServerSocketChannel.open();
             var connected = connectChannels(server, channel);
             var session = closeableSessionOrNull(sessionSupplier.get())) {
            var segment = MemorySegment.allocateNative(10, 1, session);
            ByteBuffer bb = segment.asByteBuffer();
            List<ThrowingRunnable> ioOps = List.of(
                    () -> channel.write(bb),
                    () -> channel.read(bb),
                    () -> channel.write(new ByteBuffer[] {bb}),
                    () -> channel.read(new ByteBuffer[] {bb}),
                    () -> channel.write(new ByteBuffer[] {bb}, 0, 1),
                    () -> channel.read(new ByteBuffer[] {bb}, 0, 1)
            );
            for (var ioOp : ioOps) {
                AtomicReference<Exception> exception = new AtomicReference<>();
                Runnable task = () -> exception.set(expectThrows(WTE, ioOp));
                var t = new Thread(task);
                t.start();
                t.join();
                assertMessage(exception.get(), "Attempted access outside owning thread");
            }
        }
    }

    @Test(dataProvider = "allSessions")
    public void testScatterGatherIO(Supplier<MemorySession> sessionSupplier)
        throws Exception
    {
        MemorySession session;
        try (var sc1 = SocketChannel.open();
             var ssc = ServerSocketChannel.open();
             var sc2 = connectChannels(ssc, sc1);
             var scp = closeableSessionOrNull(session = sessionSupplier.get())) {
            var writeBuffers = mixedBuffersOfSize(32, session, 64);
            var readBuffers = mixedBuffersOfSize(32, session, 64);
            long expectedCount = remaining(writeBuffers);
            assertEquals(writeNBytes(sc1, writeBuffers, 0, 32, expectedCount), expectedCount);
            assertEquals(readNBytes(sc2, readBuffers, 0, 32, expectedCount), expectedCount);
            assertEquals(flip(readBuffers), clear(writeBuffers));
        }
    }

    @Test(dataProvider = "closeableSessions")
    public void testBasicIOWithDifferentSessions(Supplier<MemorySession> sessionSupplier)
         throws Exception
    {
        try (var sc1 = SocketChannel.open();
             var ssc = ServerSocketChannel.open();
             var sc2 = connectChannels(ssc, sc1);
             var session1 = closeableSessionOrNull(sessionSupplier.get());
             var session2 = closeableSessionOrNull(sessionSupplier.get())) {
            var writeBuffers = Stream.of(mixedBuffersOfSize(16, session1, 64), mixedBuffersOfSize(16, session2, 64))
                                     .flatMap(Arrays::stream)
                                     .toArray(ByteBuffer[]::new);
            var readBuffers = Stream.of(mixedBuffersOfSize(16, session1, 64), mixedBuffersOfSize(16, session2, 64))
                                    .flatMap(Arrays::stream)
                                    .toArray(ByteBuffer[]::new);

            long expectedCount = remaining(writeBuffers);
            assertEquals(writeNBytes(sc1, writeBuffers, 0, 32, expectedCount), expectedCount);
            assertEquals(readNBytes(sc2, readBuffers, 0, 32, expectedCount), expectedCount);
            assertEquals(flip(readBuffers), clear(writeBuffers));
        }
    }

    static SocketChannel connectChannels(ServerSocketChannel ssc, SocketChannel sc)
        throws Exception
    {
        ssc.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sc.connect(ssc.getLocalAddress());
        return ssc.accept();
    }

    static long writeNBytes(SocketChannel channel,
                            ByteBuffer[] buffers, int offset, int len,
                            long bytes)
        throws Exception
    {
        long total = 0L;
        do {
            long n = channel.write(buffers, offset, len);
            assertTrue(n > 0, "got:" + n);
            total += n;
        } while (total < bytes);
        return total;
    }

    static long readNBytes(SocketChannel channel,
                           ByteBuffer[] buffers, int offset, int len,
                           long bytes)
        throws Exception
    {
        long total = 0L;
        do {
            long n = channel.read(buffers, offset, len);
            assertTrue(n > 0, "got:" + n);
            total += n;
        } while (total < bytes);
        return total;
    }
}


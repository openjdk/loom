/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @run testng/othervm/timeout=300 NetSockets
 * @summary Basic tests for virtual threads using java.net sockets.
 */

/**
 * @test
 * @requires (os.family == "windows")
 * @run testng/othervm/timeout=300 -Djdk.PollerProvider=sun.nio.ch.WSAPollPollerProvider NetSockets
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class NetSockets {

    private static final long DELAY = 2000;
    
    /**
     * Socket read/write, no blocking.
     */
    public void testSocketReadWrite1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // write should not block
                byte[] ba = "XXX".getBytes("UTF-8");
                s1.getOutputStream().write(ba);

                // read should not block
                ba = new byte[10];
                int n = s2.getInputStream().read(ba);
                assertTrue(n > 0);
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Virtual thread blocks in read.
     */
    public void testSocketRead1() throws Exception {
        testSocketRead(0);
    }

    /**
     * Virtual thread blocks in timed read.
     */
    public void testSocketRead2() throws Exception {
        testSocketRead(60_000);
    }

    void testSocketRead(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // schedule write
                byte[] ba = "XXX".getBytes("UTF-8");
                ScheduledWriter.schedule(s1, ba, DELAY);

                // read should block
                if (timeout > 0) {
                    assert timeout > DELAY;
                    s2.setSoTimeout(timeout);
                }
                ba = new byte[10];
                int n = s2.getInputStream().read(ba);
                assertTrue(n > 0);
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Virtual thread blocks in write.
     */
    public void testSocketWrite1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // schedule thread to read to EOF
                ScheduledReader.schedule(s2, true, DELAY);

                // write should block
                byte[] ba = new byte[100*1024];
                OutputStream out = s1.getOutputStream();
                for (int i=0; i<1000; i++) {
                    out.write(ba);
                }
            }
        });
    }

    /**
     * Virtual thread blocks in read, peer closes connection.
     */
    public void testSocketReadPeerClose1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                ScheduledCloser.schedule(s2, DELAY);

                int n = s1.getInputStream().read();
                assertTrue(n == -1);
            }
        });
    }

    /**
     * Virtual thread blocks in read, peer closes connection abruptly.
     */
    public void testSocketReadPeerClose2() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                s2.setSoLinger(true, 0);
                ScheduledCloser.schedule(s2, DELAY);

                try {
                    s1.getInputStream().read();
                    assertTrue(false);
                } catch (IOException ioe) {
                    // expected
                }
            }
        });
    }

    /**
     * Socket close while virtual thread blocked in read.
     */
    public void testSocketReadAsyncClose1() throws Exception {
        testSocketReadAsyncClose(0);
    }

    /**
     * Socket close while virtual thread blocked in timed read.
     */
    public void testSocketReadAsyncClose2() throws Exception {
        testSocketReadAsyncClose(0);
    }

    void testSocketReadAsyncClose(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledCloser.schedule(s, DELAY);
                try {
                    if (timeout > 0) {
                        assert timeout > DELAY;
                        s.setSoTimeout(timeout);
                    }
                    int n = s.getInputStream().read();
                    throw new RuntimeException("read returned " + n);
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Virtual thread interrupted while blocked in Socket read.
     */
    public void testSocketReadInterrupt1() throws Exception {
        testSocketReadInterrupt(0);
    }

    /**
     * Virtual thread interrupted while blocked in Socket read with timeout
     */
    public void testSocketReadInterrupt2() throws Exception {
        testSocketReadInterrupt(60_000);
    }

    void testSocketReadInterrupt(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    if (timeout > 0) {
                        assert timeout > DELAY;
                        s.setSoTimeout(timeout);
                    }
                    int n = s.getInputStream().read();
                    throw new RuntimeException("read returned " + n);
                } catch (SocketException expected) {
                    assertTrue(Thread.interrupted());
                    assertTrue(s.isClosed());
                }
            }
        });
    }

    /**
     * Socket close while virtual thread blocked in write.
     */
    public void testSocketWriteAsyncClose() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledCloser.schedule(s, DELAY);
                try {
                    byte[] ba = new byte[100*1024];
                    OutputStream out = s.getOutputStream();
                    for (;;) {
                        out.write(ba);
                    }
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Virtual thread interrupted while blocked in Socket write
     */
    public void testSocketWriteInterrupt() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s = connection.socket1();
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    byte[] ba = new byte[100*1024];
                    OutputStream out = s.getOutputStream();
                    for (;;) {
                        out.write(ba);
                    }
                } catch (SocketException expected) {
                    assertTrue(Thread.interrupted());
                    assertTrue(s.isClosed());
                }
            }
        });
    }

    /**
     * Virtual thread reading urgent data when SO_OOBINLINE is enabled.
     */
    public void testSocketReadUrgentData() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var connection = new Connection()) {
                Socket s1 = connection.socket1();
                Socket s2 = connection.socket2();

                // urgent data should be received
                ScheduledUrgentData.scheduleUrgentData(s2, 'X', DELAY);
                s1.setOOBInline(true);
                byte[] ba = new byte[10];
                int n = s1.getInputStream().read(ba);
                assertTrue(n == 1);
                assertTrue(ba[0] == 'X');

                // urgent data should not be received
                s1.setOOBInline(false);
                s1.setSoTimeout(500);
                s2.sendUrgentData('X');
                try {
                    s1.getInputStream().read(ba);
                    assertTrue(false);
                } catch (SocketTimeoutException expected) { }
            }
        });
    }

    /**
     * ServerSocket accept, no blocking.
     */
    public void testServerSocketAccept1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var listener = new ServerSocket(0)) {
                var socket1 = new Socket(listener.getInetAddress(), listener.getLocalPort());
                // accept should not block
                var socket2 = listener.accept();
                socket1.close();
                socket2.close();
            }
        });
    }

    /**
     * Virtual thread blocks in accept.
     */
    public void testServerSocketAccept2() throws Exception {
        testServerSocketAccept(0);
    }

    /**
     * Virtual thread blocks in timed accept.
     */
    public void testServerSocketAccept3() throws Exception {
        testServerSocketAccept(60_000);
    }

    void testServerSocketAccept(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var listener = new ServerSocket(0)) {
                var socket1 = new Socket();
                ScheduledConnector.schedule(socket1, listener.getLocalSocketAddress(), DELAY);
                // accept will block
                if (timeout > 0) {
                    assert timeout > DELAY;
                    listener.setSoTimeout(timeout);
                }
                var socket2 = listener.accept();
                socket1.close();
                socket2.close();
            }
        });
    }

    /**
     * ServerSocket close while virtual thread blocked in accept.
     */
    public void testServerSocketAcceptAsyncClose1() throws Exception {
        testServerSocketAcceptAsyncClose(0);
    }

    /**
     * ServerSocket close while virtual thread blocked in timed accept.
     */
    public void testServerSocketAcceptAsyncClose2() throws Exception {
        testServerSocketAcceptAsyncClose(60_000);
    }

    void testServerSocketAcceptAsyncClose(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var listener = new ServerSocket(0)) {
                ScheduledCloser.schedule(listener, DELAY);
                if (timeout > 0) {
                    assert timeout > DELAY;
                    listener.setSoTimeout(timeout);
                }
                try {
                    listener.accept().close();
                    throw new RuntimeException("connection accepted???");
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Virtual thread interrupted while blocked in ServerSocket accept
     */
    public void testServerSocketAcceptInterrupt1() throws Exception {
        testServerSocketAcceptInterrupt(0);
    }

    /**
     * Virtual thread interrupted while blocked in ServerSocket accept with timeout
     */
    public void testServerSocketAcceptInterrupt2() throws Exception {
        testServerSocketAcceptInterrupt(60_000);
    }

    void testServerSocketAcceptInterrupt(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (var listener = new ServerSocket(0)) {
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                if (timeout > 0) {
                    assert timeout > DELAY;
                    listener.setSoTimeout(timeout);
                }
                try {
                    listener.accept().close();
                    throw new RuntimeException("connection accepted???");
                } catch (SocketException expected) {
                    assertTrue(Thread.interrupted());
                    assertTrue(listener.isClosed());
                }
            }
        });
    }

    /**
     * DatagramSocket receive/send, no blocking.
     */
    public void testDatagramSocketSendReceive1() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (DatagramSocket s1 = new DatagramSocket(null);
                 DatagramSocket s2 = new DatagramSocket(null)) {

                InetAddress lh = InetAddress.getLoopbackAddress();
                s1.bind(new InetSocketAddress(lh, 0));
                s2.bind(new InetSocketAddress(lh, 0));

                // send should not block
                byte[] bytes = "XXX".getBytes("UTF-8");
                DatagramPacket p1 = new DatagramPacket(bytes, bytes.length);
                p1.setSocketAddress(s2.getLocalSocketAddress());
                s1.send(p1);

                // receive should not block
                byte[] ba = new byte[100];
                DatagramPacket p2 = new DatagramPacket(ba, ba.length);
                s2.receive(p2);
                assertEquals(p2.getSocketAddress(), s1.getLocalSocketAddress());
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Virtual thread blocks in DatagramSocket receive
     */
    public void testDatagramSocketSendReceive2() throws Exception {
        testDatagramSocketSendReceive(0);
    }

    /**
     * Virtual thread blocks in DatagramSocket receive with timeout
     */
    public void testDatagramSocketSendReceive3() throws Exception {
        testDatagramSocketSendReceive(60_000);
    }

    private void testDatagramSocketSendReceive(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (DatagramSocket s1 = new DatagramSocket(null);
                 DatagramSocket s2 = new DatagramSocket(null)) {

                InetAddress lh = InetAddress.getLoopbackAddress();
                s1.bind(new InetSocketAddress(lh, 0));
                s2.bind(new InetSocketAddress(lh, 0));

                // schedule send
                byte[] bytes = "XXX".getBytes("UTF-8");
                DatagramPacket p1 = new DatagramPacket(bytes, bytes.length);
                p1.setSocketAddress(s2.getLocalSocketAddress());
                ScheduledSender.schedule(s1, p1, DELAY);

                // receive should block
                if (timeout > 0) {
                    assert timeout > DELAY;
                    s2.setSoTimeout(timeout);
                }
                byte[] ba = new byte[100];
                DatagramPacket p2 = new DatagramPacket(ba, ba.length);
                s2.receive(p2);
                assertEquals(p2.getSocketAddress(), s1.getLocalSocketAddress());
                assertTrue(ba[0] == 'X');
            }
        });
    }

    /**
     * Virtual thread blocks in DatagramSocket receive that times out
     */
    public void testDatagramSocketReceiveTimeout() throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                InetAddress lh = InetAddress.getLoopbackAddress();
                s.bind(new InetSocketAddress(lh, 0));
                s.setSoTimeout(2000);
                byte[] ba = new byte[100];
                DatagramPacket p = new DatagramPacket(ba, ba.length);
                try {
                    s.receive(p);
                    assertTrue(false);
                } catch (SocketTimeoutException expected) { }
            }
        });
    }

    /**
     * DatagramSocket close while virtual thread blocked in receive.
     */
    public void testDatagramSocketReceiveAsyncClose1() throws Exception {
        testDatagramSocketReceiveAsyncClose(0);
    }

    /**
     * DatagramSocket close while virtual thread blocked with timeout.
     */
    public void testDatagramSocketReceiveAsyncClose2() throws Exception {
        testDatagramSocketReceiveAsyncClose(60_000);
    }

    private void testDatagramSocketReceiveAsyncClose(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                InetAddress lh = InetAddress.getLoopbackAddress();
                s.bind(new InetSocketAddress(lh, 0));

                // schedule close
                ScheduledCloser.schedule(s, DELAY);

                // receive
                if (timeout > 0) {
                    assert timeout > DELAY;
                    s.setSoTimeout(timeout);
                }
                try {
                    byte[] ba = new byte[100];
                    DatagramPacket p = new DatagramPacket(ba, ba.length);
                    s.receive(p);
                    assertTrue(false);
                } catch (SocketException expected) { }
            }
        });
    }

    /**
     * Virtual thread interrupted while blocked in DatagramSocket receive.
     */
    public void testDatagramSocketReceiveInterrupt1() throws Exception {
        testDatagramSocketReceiveInterrupt(0);
    }

    /**
     * Virtual thread interrupted while blocked in DatagramSocket receive with timeout
     */
    public void testDatagramSocketReceiveInterrupt2() throws Exception {
        testDatagramSocketReceiveInterrupt(60_000);
    }

    private void testDatagramSocketReceiveInterrupt(int timeout) throws Exception {
        TestHelper.runInVirtualThread(() -> {
            try (DatagramSocket s = new DatagramSocket(null)) {
                InetAddress lh = InetAddress.getLoopbackAddress();
                s.bind(new InetSocketAddress(lh, 0));
                if (timeout > 0) {
                    assert timeout > DELAY;
                    s.setSoTimeout(timeout);
                }

                // schedule interrupt
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);

                // receive
                try {
                    byte[] ba = new byte[100];
                    DatagramPacket p = new DatagramPacket(ba, ba.length);
                    s.receive(p);
                    assertTrue(false);
                } catch (SocketException expected) {
                    assertTrue(Thread.interrupted());
                    assertTrue(s.isClosed());
                }
            }
        });
    }

    // -- supporting classes --

    /**
     * Creates a loopback connection
     */
    static class Connection implements Closeable {
        private final ServerSocket ss;
        private final Socket s1;
        private final Socket s2;
        Connection() throws IOException {
            ServerSocket ss = new ServerSocket();
            var lh = InetAddress.getLoopbackAddress();
            ss.bind(new InetSocketAddress(lh, 0));
            Socket s = new Socket();
            s.connect(ss.getLocalSocketAddress());

            this.ss = ss;
            this.s1 = s;
            this.s2 = ss.accept();
        }
        Socket socket1() {
            return s1;
        }
        Socket socket2() {
            return s2;
        }
        @Override
        public void close() throws IOException {
            if (ss != null) ss.close();
            if (s1 != null) s1.close();
            if (s2 != null) s2.close();
        }
    }

    /**
     * Closes a socket after a delay
     */
    static class ScheduledCloser implements Runnable {
        private final Closeable c;
        private final long delay;
        ScheduledCloser(Closeable c, long delay) {
            this.c = c;
            this.delay = delay;
        }
        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                c.close();
            } catch (Exception e) { }
        }
        static void schedule(Closeable c, long delay) {
            new Thread(new ScheduledCloser(c, delay)).start();
        }
    }

    /**
     * Interrupts a thread after a delay
     */
    static class ScheduledInterrupter implements Runnable {
        private final Thread thread;
        private final long delay;

        ScheduledInterrupter(Thread thread, long delay) {
            this.thread = thread;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) { }
        }

        static void schedule(Thread thread, long delay) {
            new Thread(new ScheduledInterrupter(thread, delay)).start();
        }
    }

    /**
     * Reads from a socket, and to EOF, after a delay
     */
    static class ScheduledReader implements Runnable {
        private final Socket s;
        private final boolean readAll;
        private final long delay;

        ScheduledReader(Socket s, boolean readAll, long delay) {
            this.s = s;
            this.readAll = readAll;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                byte[] ba = new byte[8192];
                InputStream in = s.getInputStream();
                for (;;) {
                    int n = in.read(ba);
                    if (n == -1 || !readAll)
                        break;
                }
            } catch (Exception e) { }
        }

        static void schedule(Socket s, boolean readAll, long delay) {
            new Thread(new ScheduledReader(s, readAll, delay)).start();
        }
    }

    /**
     * Writes to a socket after a delay
     */
    static class ScheduledWriter implements Runnable {
        private final Socket s;
        private final byte[] ba;
        private final long delay;

        ScheduledWriter(Socket s, byte[] ba, long delay) {
            this.s = s;
            this.ba = ba.clone();
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                s.getOutputStream().write(ba);
            } catch (Exception e) { }
        }

        static void schedule(Socket s, byte[] ba, long delay) {
            new Thread(new ScheduledWriter(s, ba, delay)).start();
        }
    }

    /**
     * Establish a connection to a socket address after a delay
     */
    static class ScheduledConnector implements Runnable {
        private final Socket socket;
        private final SocketAddress address;
        private final long delay;

        ScheduledConnector(Socket socket, SocketAddress address, long delay) {
            this.socket = socket;
            this.address = address;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                socket.connect(address);
            } catch (Exception e) { }
        }

        static void schedule(Socket socket, SocketAddress address, long delay) {
            new Thread(new ScheduledConnector(socket, address, delay)).start();
        }
    }

    /**
     * Sends a datagram to a target address after a delay
     */
    static class ScheduledSender implements Runnable {
        private final DatagramSocket socket;
        private final DatagramPacket packet;
        private final long delay;

        ScheduledSender(DatagramSocket socket, DatagramPacket packet, long delay) {
            this.socket = socket;
            this.packet = packet;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                socket.send(packet);
            } catch (Exception e) { }
        }

        static void schedule(DatagramSocket socket, DatagramPacket packet, long delay) {
            new Thread(new ScheduledSender(socket, packet, delay)).start();
        }
    }

    /**
     * Sends urgent data after a delay
     */
    static class ScheduledUrgentData implements Runnable {
        private final Socket s;
        private final int data;
        private final long delay;

        ScheduledUrgentData(Socket s, int data, long delay) {
            this.s = s;
            this.data = data;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                s.sendUrgentData(data);
            } catch (Exception e) { }
        }

        static void scheduleUrgentData(Socket s, int data, long delay) {
            new Thread(new ScheduledUrgentData(s, data, delay)).start();
        }
    }
}

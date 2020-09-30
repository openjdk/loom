/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @requires vm.debug != true
 * @run main/othervm/timeout=600 HttpALot 50 10 1000
 * @summary Stress test the HTTP protocol handler
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

public class HttpALot {

    static AtomicInteger requestsSent = new AtomicInteger();
    static AtomicInteger requestErrors = new AtomicInteger();
    static AtomicInteger requestsHandled = new AtomicInteger();

    /**
     * Usage: {@code java HttpALot <iterations> <parallelism> <url-count>}
     */
    public static void main(String[] args) throws Exception {
        int iterations = 50;
        int parallelism = 10;
        int urlCount = 1000;

        if (args.length > 0) {
            iterations = Integer.parseInt(args[0]);
            parallelism = Integer.parseInt(args[1]);
            urlCount = Integer.parseInt(args[2]);
        }

        System.out.format("%d iterations, %d HTTP requests/iteration%n",
                iterations, (parallelism * urlCount));

        // Create HTTP on the loopback address. The server will reply to
        // the requests to GET /hello. It uses virtual threads.
        InetAddress lb = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(lb, 0), 0);
        server.setExecutor(Executors.newVirtualThreadExecutor());
        server.createContext("/hello", e -> {
            requestsHandled.incrementAndGet();
            byte[] response = "Hello".getBytes("UTF-8");
            e.sendResponseHeaders(200, response.length);
            try (OutputStream out = e.getResponseBody()) {
                out.write(response);
            }
        });

        // URL for hello service
        var address = server.getAddress();
        URL url = new URL("http://" + address.getHostName() + ":" + address.getPort() + "/hello");

        // monitoring thread
        Thread.builder().daemon(true).task(() -> {
            for (;;) {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException e) { }
                System.out.format("%s: %d requests, %d errors, %d handled%n", Instant.now(),
                        requestsSent.get(), requestErrors.get(), requestsHandled.get());
            }
        }).start();

        // go
        server.start();
        try {
            for (int i = 1; i <= iterations; i++) {
                try (var executor = Executors.newVirtualThreadExecutor()) {
                    for (int k = 0; k < parallelism; k++) {
                        int count = urlCount;
                        executor.submit(() -> fetch(url, count));
                    }
                }
            }
        } finally {
            server.stop(1);
        }

        System.out.format("FINISHED: %d requests, %d errors, %d handled%n",
                requestsSent.get(), requestErrors.get(), requestsHandled.get());
        int expected = iterations * parallelism * urlCount;
        if ((expected - requestErrors.get()) > requestsSent.get()) {
            throw new RuntimeException();
        }
    }

    private static void fetch(URL url, int count) {
        for (int k = 0; k < count; k++) {
            try (InputStream in = url.openConnection(Proxy.NO_PROXY).getInputStream()) {
                byte[] bytes = in.readAllBytes();
                String s = new String(bytes, "UTF-8");
                requestsSent.incrementAndGet();
            } catch (IOException ioe) {
                requestErrors.incrementAndGet();
            }
        }
    }
}

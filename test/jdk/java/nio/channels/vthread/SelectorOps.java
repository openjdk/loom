/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test virtual threads using Selector
 * @library /test/lib
 * @run junit SelectorOps
 */

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import jdk.test.lib.thread.VThreadRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SelectorOps {
    private static ScheduledExecutorService scheduler;

    @BeforeAll
    static void setup() throws Exception {
        ThreadFactory factory = Executors.defaultThreadFactory();
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @AfterAll
    static void finish() {
        scheduler.shutdown();
    }

    /**
     * Test that select wakes up when a channel is ready for I/O.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testSelect(boolean timed) throws Exception {
        VThreadRunner.run(() -> {
            Pipe p = Pipe.open();
            try (Selector sel = Selector.open()) {
                Pipe.SinkChannel sink = p.sink();
                Pipe.SourceChannel source = p.source();
                source.configureBlocking(false);
                SelectionKey key = source.register(sel, SelectionKey.OP_READ);

                // write to sink to ensure source is readable
                ByteBuffer buf = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
                Callable<Void> writer = () -> { sink.write(buf); return null; };
                scheduler.schedule(writer, 500, TimeUnit.MILLISECONDS);

                int n = timed ? sel.select(60_000) : sel.select();
                assertEquals(1, n);
                assertTrue(sel.isOpen());
                assertTrue(key.isReadable());
            } finally {
                closePipe(p);
            }
        });
    }

    /**
     * Test that select wakes up when timeout is reached.
     */
    @Test
    public void testSelectTimeout() throws Exception {
        VThreadRunner.run(() -> {
            Pipe p = Pipe.open();
            try (Selector sel = Selector.open()) {
                Pipe.SourceChannel source = p.source();
                source.configureBlocking(false);
                SelectionKey key = source.register(sel, SelectionKey.OP_READ);

                long start = millisTime();
                int n = sel.select(1000);
                expectDuration(start, /*min*/500, /*max*/20_000);

                assertEquals(0, n);
                assertTrue(sel.isOpen());
                assertFalse(key.isReadable());
            } finally {
                closePipe(p);
            }
        });
    }

    /**
     * Test that selectNow is non-blocking.
     */
    @Test
    public void testSelectNow() throws Exception {
        VThreadRunner.run(() -> {
            Pipe p = Pipe.open();
            try (Selector sel = Selector.open()) {
                Pipe.SinkChannel sink = p.sink();
                Pipe.SourceChannel source = p.source();
                source.configureBlocking(false);
                SelectionKey key = source.register(sel, SelectionKey.OP_READ);

                // write to sink to ensure source is readable
                ByteBuffer buf = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
                Callable<Void> writer = () -> { sink.write(buf); return null; };
                scheduler.schedule(writer, 500, TimeUnit.MILLISECONDS);

                // call selectNow until key added to selected key set
                int n = 0;
                while (n == 0) {
                    long start = millisTime();
                    n = sel.selectNow();
                    expectDuration(start, -1, /*max*/20_000);
                    if (n == 0) {
                        Thread.sleep(100);
                    }
                }
                assertEquals(1, n);
                assertTrue(sel.isOpen());
                assertTrue(key.isReadable());
            } finally {
                closePipe(p);
            }
        });
    }

    /**
     * Test calling wakeup before select.
     */
    @Test
    public void testWakeupBeforeSelect() throws Exception {
        VThreadRunner.run(() -> {
            try (Selector sel = Selector.open()) {
                sel.wakeup();
                int n = sel.select();
                assertEquals(0, n);
                assertTrue(sel.isOpen());
            }
        });
    }

    /**
     * Test calling wakeup while a thread is blocked in select.
     */
    @Test
    public void testWakeupDuringSelect() throws Exception {
        VThreadRunner.run(() -> {
            try (Selector sel = Selector.open()) {
                scheduler.schedule(() -> sel.wakeup(), 500, TimeUnit.MILLISECONDS);
                int n = sel.select();
                assertEquals(0, n);
                assertTrue(sel.isOpen());
            }
        });
    }

    /**
     * Test closing selector while a thread is blocked in select.
     */
    public void testCloseDuringSelect() throws Exception {
        VThreadRunner.run(() -> {
            try (Selector sel = Selector.open()) {
                Callable<Void> close = () -> { sel.close(); return null; };
                scheduler.schedule(close, 500, TimeUnit.MILLISECONDS);
                int n = sel.select();
                assertEquals(0, n);
                assertFalse(sel.isOpen());
            }
        });
    }

    /**
     * Test calling select with interrupt status set.
     */
    @Test
    public void testInterruptBeforeSelect() throws Exception {
        VThreadRunner.run(() -> {
            try (Selector sel = Selector.open()) {
                Thread me = Thread.currentThread();
                me.interrupt();
                int n = sel.select();
                assertEquals(0, n);
                assertTrue(me.isInterrupted());
                assertTrue(sel.isOpen());
            }
        });
    }

    /**
     * Test interrupting a thread blocked in select.
     */
    @Test
    public void testInterruptDuringSelect() throws Exception {
        VThreadRunner.run(() -> {
            try (Selector sel = Selector.open()) {
                Thread me = Thread.currentThread();
                scheduler.schedule(me::interrupt, 500, TimeUnit.MILLISECONDS);
                int n = sel.select();
                assertEquals(0, n);
                assertTrue(me.isInterrupted());
                assertTrue(sel.isOpen());
            }
        });
    }

    /**
     * Close a pipe's sink and source channels.
     */
    private void closePipe(Pipe p) {
        try { p.sink().close(); } catch (IOException ignore) { }
        try { p.source().close(); } catch (IOException ignore) { }
    }

    /**
     * Returns the current time in milliseconds.
     */
    private static long millisTime() {
        long now = System.nanoTime();
        return TimeUnit.MILLISECONDS.convert(now, TimeUnit.NANOSECONDS);
    }

    /**
     * Check the duration of a task
     * @param start start time, in milliseconds
     * @param min minimum expected duration, in milliseconds
     * @param max maximum expected duration, in milliseconds
     * @return the duration (now - start), in milliseconds
     */
    private static void expectDuration(long start, long min, long max) {
        long duration = millisTime() - start;
        assertTrue(duration >= min,
                "Duration " + duration + "ms, expected >= " + min + "ms");
        assertTrue(duration <= max,
                "Duration " + duration + "ms, expected <= " + max + "ms");
    }
}

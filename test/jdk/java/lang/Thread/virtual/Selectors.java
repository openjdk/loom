/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic tests for virtual threads doing blocking Selector select
 * @requires (os.family == "linux")
 * @compile --enable-preview --add-exports=java.base/sun.nio.ch=ALL-UNNAMED  -source ${jdk.version} Selectors.java
 * @run testng/othervm/timeout=300 --enable-preview --add-exports=java.base/sun.nio.ch=ALL-UNNAMED  Selectors
 * @run testng/othervm/timeout=300 --enable-preview --add-exports=java.base/sun.nio.ch=ALL-UNNAMED -Djdk.useRecursivePoll=true Selectors
 * @run testng/othervm/timeout=300 --enable-preview --add-exports=java.base/sun.nio.ch=ALL-UNNAMED -Djdk.useRecursivePoll=true -Djdk.useDirectRegister Selectors
 */

import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import sun.nio.ch.Poller;
import static org.testng.Assert.*;


public class Selectors {
    @Test
    public void testSelectorMounted() throws Exception {
        var selectorThread =  Thread.ofVirtual().start(() -> {
            try {
                Selector selector = Selector.open();
                selector.select();
            } catch (Exception ignored) {
            }
        });
        Thread.sleep(200);
        assertEquals(selectorThread.getState(),
                (Boolean.parseBoolean(System.getProperty("jdk.useRecursivePoll"))? Thread.State.WAITING : Thread.State.RUNNABLE));
        selectorThread.interrupt();
        selectorThread.join();
    }

    @Test
    public void testSelectorWakeup() throws Exception {
        var selectorSet = new CountDownLatch(1);
        var wakened = new CountDownLatch(1);
        var selector = new AtomicReference<Selector>();
        var selectorThread =  Thread.ofVirtual().start(() -> {
            try {
                selector.set(Selector.open());
                selectorSet.countDown();
                selector.get().select();
                wakened.countDown();
            } catch (Exception ignored) {
            }
        });
        selectorSet.await();
        selector.get().wakeup();
        wakened.await();
        selectorThread.join();
    }

    @Test
    public void testSelectorInterrupt() throws Exception {
        var wakened = new CountDownLatch(1);
        var selector = new AtomicReference<Selector>();
        var exception = new AtomicReference<Exception>();
        var selectorThread =  Thread.ofVirtual().start(() -> {
            try {
                selector.set(Selector.open());
                selector.get().select();
                assertTrue(Thread.currentThread().isInterrupted());
                wakened.countDown();
            } catch (Exception e) {
                exception.set(e);
            }
        });
        Thread.sleep(100);  // give time for thread to block
        selectorThread.interrupt();
        wakened.await();
        assertTrue(exception.get() == null);
        selectorThread.join();
    }

    @Test
    public void testSelectNow() throws Exception {
        var selector = Selector.open();
        var p = SelectorProvider.provider().openPipe();
        var sink = p.sink();
        var source = p.source();
        source.configureBlocking(false);
        sink.configureBlocking(false);
        var selectResult = new AtomicReference<Integer>();
        var exception = new AtomicReference<Exception>();

        // selectNow return expected result
        Thread.ofVirtual().start(() -> {
            try {
                selectResult.set(selector.selectNow());
            } catch (Exception e) {
                exception.set(e);
            }
        }).join();

        assertTrue(exception.get() == null);
        assertTrue(selectResult.get() == 0);

        var readKey = source.register(selector, SelectionKey.OP_READ);
        var writeBuffer = ByteBuffer.allocateDirect(128);
        writeBuffer.put("helloworld".getBytes());
        sink.write(writeBuffer);

        Thread.ofVirtual().start(() -> {
            try {
                selectResult.set(selector.selectNow());
            } catch (Exception e) {
                exception.set(e);
            }
        }).join();

        assertTrue(exception.get() == null);
        assertTrue(selectResult.get() == 1);
    }

    @Test
    public void testSelectWithTimeout() throws Exception {
        // timed select wakeup eventually
        var exception = new AtomicReference<Exception>();
        Thread.ofVirtual().start(() -> {
            try {
                Selector.open().select(1000);
            } catch (Exception e) {
                exception.set(e);
            }
        }).join();
    }

}

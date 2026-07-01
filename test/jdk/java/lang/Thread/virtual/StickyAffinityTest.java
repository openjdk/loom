/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test virtual threads with sticky affinity
 * @requires vm.continuations
 * @modules java.base/java.lang:+open
 * @library /test/lib
 * @run junit StickyAffinityTest
 */

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StickyAffinityTest {

    private static Thread currentCarrierThread() throws Exception {
        Field f = Class.forName("java.lang.VirtualThread")
                .getDeclaredField("carrierThread");
        f.setAccessible(true);
        return (Thread) f.get(Thread.currentThread());
    }

    /**
     * Test that stickyAffinity() builder method creates and starts a thread.
     */
    @Test
    void testBuilderApi() throws Exception {
        var ran = new AtomicBoolean();
        Thread thread = Thread.ofVirtual()
                .stickyAffinity()
                .start(() -> ran.set(true));
        thread.join();
        assertTrue(ran.get());
    }

    /**
     * Test that stickyAffinity works with factory().
     */
    @Test
    void testFactoryApi() throws Exception {
        ThreadFactory factory = Thread.ofVirtual()
                .stickyAffinity()
                .name("sticky-", 0)
                .factory();
        var ran = new AtomicBoolean();
        Thread thread = factory.newThread(() -> ran.set(true));
        thread.start();
        thread.join();
        assertTrue(ran.get());
        assertTrue(thread.getName().startsWith("sticky-"));
    }

    /**
     * Test that when a sticky VT unparks another VT, the unparked VT resumes
     * on the same carrier as the sticky VT (builtin scheduler).
     */
    @Test
    void testStickyUnparkPreservesCarrier() throws Exception {
        var stickyCarrier = new AtomicReference<Thread>();
        var targetCarrierAfterUnpark = new AtomicReference<Thread>();
        var parked = new CountDownLatch(1);
        var done = new CountDownLatch(1);

        Thread target = Thread.ofVirtual().start(() -> {
            parked.countDown();
            LockSupport.park();
            try {
                targetCarrierAfterUnpark.set(currentCarrierThread());
            } catch (Exception e) { throw new RuntimeException(e); }
            done.countDown();
        });
        parked.await();

        Thread sticky = Thread.ofVirtual()
                .stickyAffinity()
                .start(() -> {
                    try {
                        stickyCarrier.set(currentCarrierThread());
                    } catch (Exception e) { throw new RuntimeException(e); }
                    LockSupport.unpark(target);
                });
        sticky.join();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        target.join();

        assertNotNull(stickyCarrier.get());
        assertNotNull(targetCarrierAfterUnpark.get());
        assertEquals(stickyCarrier.get(), targetCarrierAfterUnpark.get(),
                "unparked VT should resume on the sticky VT's carrier");
    }

}

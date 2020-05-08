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
 * @run testng CustomScheduler
 * @summary Test virtual threads using a custom scheduler
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class CustomScheduler {

    /**
     * Test task is a VirtualThreadTask and that its thread() method returns
     * the Thread object for the virtual thread.
     */
    public void testThread() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            var ref = new AtomicReference<Thread>();
            Executor scheduler = (task) -> {
                Thread vthread = ((Thread.VirtualThreadTask) task).thread();
                ref.set(vthread);
                pool.execute(task);
            };
            Thread thread = Thread.builder().virtual(scheduler).task(() -> { }).start();
            thread.join();
            assertTrue(ref.get() == thread);
        }
    }

    /**
     * Test task is a VirtualThreadTask and that an object can be attached to
     * the task.
     */
    public void testAttach() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            Object context = new Object();

            // records value of attachment at each submit
            var attachments = new CopyOnWriteArrayList<Object>();

            Executor scheduler = (task) -> {
                var vtask = (Thread.VirtualThreadTask) task;
                Object att = vtask.attachment();
                attachments.add(att);
                if (att == null)
                    vtask.attach(context);   // attach context on first submit
                pool.execute(task);
            };

            Thread.builder()
                    .virtual(scheduler)
                    .task(() -> {
                        long nanos = Duration.ofSeconds(2).toNanos();
                        LockSupport.parkNanos(nanos);
                        LockSupport.parkNanos(nanos);
                    })
                    .start()
                    .join();

            var expected = new ArrayList<>();
            expected.add(null);
            expected.add(context);
            expected.add(context);

            assertEquals(attachments, expected);
        }
    }

}
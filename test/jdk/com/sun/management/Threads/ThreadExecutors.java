/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng ThreadExecutors
 * @summary @summary Basic test for com.sun.management.Threads.executors
 */

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import com.sun.management.Threads;
import com.sun.management.Threads.ThreadExecutor;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class ThreadExecutors {

    /**
     * One virtual thread executor, two threads.
     */
    public void testOneExecutor() throws Exception {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            Thread vthread1 = execute(executor, LockSupport::park);
            Thread vthread2 = execute(executor, LockSupport::park);
            try {
                List<ThreadExecutor> currentExecutors = currentExecutors();
                assertTrue(currentExecutors.size() == 1);
                ThreadExecutor threadExecutor = currentExecutors.get(0);

                // ThreadExecutor::threads should enumerate both threads
                Set<Thread> threads = threadExecutor.threads().collect(Collectors.toSet());
                assertTrue(threads.size() == 2);
                assertTrue(threads.contains(vthread1));
                assertTrue(threads.contains(vthread2));
            } finally {
                LockSupport.unpark(vthread1);
                LockSupport.unpark(vthread2);
            }
        }
    }

    /**
     * Two nest virtual thread executors, each with one thread.
     */
    public void testNestedExecutors() throws Exception {
        try (var executor1 = Executors.newVirtualThreadExecutor()) {
            Thread vthread1 = execute(executor1, LockSupport::park);
            try {
                List<ThreadExecutor> currentExecutors = currentExecutors();
                assertTrue(currentExecutors.size() == 1);
                ThreadExecutor threadExecutor1 = currentExecutors.get(0);

                // ThreadExecutor::threads should enumerate one thread
                Set<Thread> threads1 = threadExecutor1.threads().collect(Collectors.toSet());
                assertTrue(threads1.size() == 1);
                assertTrue(threads1.contains(vthread1));

                // created nested thread executor
                try (var executor2 = Executors.newVirtualThreadExecutor()) {
                    Thread vthread2 = execute(executor2, LockSupport::park);
                    try {
                        currentExecutors = currentExecutors();
                        assertTrue(currentExecutors.size() == 2);

                        // check ordering
                        assertEquals(currentExecutors.get(0), threadExecutor1);
                        ThreadExecutor threadExecutor2 = currentExecutors().get(1);

                        // ThreadExecutor::threads should enumerate one thread
                        Set<Thread> threads2 = threadExecutor2.threads().collect(Collectors.toSet());
                        assertTrue(threads2.size() == 1);
                        assertTrue(threads2.contains(vthread2));
                    } finally {
                        LockSupport.unpark(vthread2);
                    }
                }

                assertTrue(currentExecutors().size() == 1);
            } finally {
                LockSupport.unpark(vthread1);
            }
        }
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull() {
        Threads.executors(null);
    }

    private static List<ThreadExecutor> currentExecutors() {
        return Threads.executors(Thread.currentThread());
    }

    private static Thread execute(Executor executor, Runnable task) throws Exception {
        var ref = new AtomicReference<Thread>();
        executor.execute(() -> {
            ref.set(Thread.currentThread());
            task.run();
        });
        Thread thread;
        while ((thread = ref.get()) == null) {
            Thread.sleep(10);
        }
        return thread;
    }
}
/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
 * @run testng BuilderTest
 * @summary Unit test for Thread.Builder
 */

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class BuilderTest {

    // kernel thread
    public void testKernelThread1() throws Exception {
        Thread parent = Thread.currentThread();
        Thread.Builder builder = Thread.builder();

        // build
        AtomicBoolean done1 = new AtomicBoolean();
        Thread thread1 = builder.task(() -> done1.set(true)).build();
        assertFalse(thread1.isVirtual());
        assertTrue(thread1.getState() == Thread.State.NEW);
        assertFalse(thread1.getName().isEmpty());
        assertTrue(thread1.getThreadGroup() == parent.getThreadGroup());
        assertTrue(thread1.isDaemon() == parent.isDaemon());
        assertTrue(thread1.getPriority() == parent.getPriority());
        assertTrue(thread1.getContextClassLoader() == parent.getContextClassLoader());
        thread1.start();
        thread1.join();
        assertTrue(done1.get());

        // start
        AtomicBoolean done2 = new AtomicBoolean();
        Thread thread2 = builder.task(() -> done2.set(true)).start();
        assertFalse(thread2.isVirtual());
        assertTrue(thread2.getState() != Thread.State.NEW);
        assertFalse(thread2.getName().isEmpty());
        ThreadGroup group2 = thread2.getThreadGroup();
        assertTrue(group2 == parent.getThreadGroup() || group2 == null);
        assertTrue(thread2.isDaemon() == parent.isDaemon());
        assertTrue(thread2.getPriority() == parent.getPriority());
        assertTrue(thread2.getContextClassLoader() == parent.getContextClassLoader());
        thread2.join();
        assertTrue(done2.get());

        // factory
        AtomicBoolean done3 = new AtomicBoolean();
        Thread thread3 = builder.factory().newThread(() -> done3.set(true));
        assertFalse(thread3.isVirtual());
        assertTrue(thread3.getState() == Thread.State.NEW);
        assertFalse(thread3.getName().isEmpty());
        assertTrue(thread3.getThreadGroup() == parent.getThreadGroup());
        assertTrue(thread3.isDaemon() == parent.isDaemon());
        assertTrue(thread3.getPriority() == parent.getPriority());
        assertTrue(thread3.getContextClassLoader() == parent.getContextClassLoader());
        thread3.start();
        thread3.join();
        assertTrue(done3.get());
    }

    // virtual thread
    public void testVirtualThread1() throws Exception {
        Thread parent = Thread.currentThread();
        Thread.Builder builder = Thread.builder().virtual();

        // build
        AtomicBoolean done1 = new AtomicBoolean();
        Thread thread1 = builder.task(() -> done1.set(true)).build();
        assertTrue(thread1.isVirtual());
        assertTrue(thread1.getState() == Thread.State.NEW);
        assertEquals(thread1.getName(), "<unnamed>");
        assertTrue(thread1.getContextClassLoader() == parent.getContextClassLoader());
        assertTrue(thread1.isDaemon());
        assertTrue(thread1.getPriority() == Thread.NORM_PRIORITY);
        thread1.start();
        thread1.join();
        assertTrue(done1.get());

        // start
        AtomicBoolean done2 = new AtomicBoolean();
        Thread thread2 = builder.task(() -> done2.set(true)).start();
        assertTrue(thread2.isVirtual());
        assertTrue(thread2.getState() != Thread.State.NEW);
        assertEquals(thread2.getName(), "<unnamed>");
        assertTrue(thread2.getContextClassLoader() == parent.getContextClassLoader());
        assertTrue(thread2.isDaemon());
        assertTrue(thread2.getPriority() == Thread.NORM_PRIORITY);
        thread2.join();
        assertTrue(done2.get());

        // factory
        AtomicBoolean done3 = new AtomicBoolean();
        Thread thread3 = builder.factory().newThread(() -> done3.set(true));
        assertTrue(thread3.isVirtual());
        assertTrue(thread3.getState() == Thread.State.NEW);
        assertEquals(thread3.getName(), "<unnamed>");
        assertTrue(thread3.getContextClassLoader() == parent.getContextClassLoader());
        assertTrue(thread3.isDaemon());
        assertTrue(thread3.getPriority() == Thread.NORM_PRIORITY);
        thread3.start();
        thread3.join();
        assertTrue(done3.get());
    }

    // thread name
    public void testName1() {
        Thread.Builder builder = Thread.builder().name("duke");

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.getName().equals("duke"));
        assertTrue(thread2.getName().equals("duke"));
        assertTrue(thread3.getName().equals("duke"));
    }

    public void testName2() {
        Thread.Builder builder = Thread.builder().virtual().name("duke");

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.getName().equals("duke"));
        assertTrue(thread2.getName().equals("duke"));
        assertTrue(thread3.getName().equals("duke"));
    }

    public void testName3() {
        Thread.Builder builder = Thread.builder().name("duke-", 100);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).build();
        Thread thread3 = builder.task(() -> { }).build();

        assertTrue(thread1.getName().equals("duke-100"));
        assertTrue(thread2.getName().equals("duke-101"));
        assertTrue(thread3.getName().equals("duke-102"));

        ThreadFactory factory = builder.factory();
        Thread thread4 = factory.newThread(() -> { });
        Thread thread5 = factory.newThread(() -> { });
        Thread thread6 = factory.newThread(() -> { });

        assertTrue(thread4.getName().equals("duke-103"));
        assertTrue(thread5.getName().equals("duke-104"));
        assertTrue(thread6.getName().equals("duke-105"));
    }

    public void testName4() {
        Thread.Builder builder = Thread.builder().virtual().name("duke-", 100);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).build();
        Thread thread3 = builder.task(() -> { }).build();

        assertTrue(thread1.getName().equals("duke-100"));
        assertTrue(thread2.getName().equals("duke-101"));
        assertTrue(thread3.getName().equals("duke-102"));

        ThreadFactory factory = builder.factory();
        Thread thread4 = factory.newThread(() -> { });
        Thread thread5 = factory.newThread(() -> { });
        Thread thread6 = factory.newThread(() -> { });

        assertTrue(thread4.getName().equals("duke-103"));
        assertTrue(thread5.getName().equals("duke-104"));
        assertTrue(thread6.getName().equals("duke-105"));
    }

    // ThreadGroup
    public void testThreadGroup1() {
        ThreadGroup group = new ThreadGroup("groupies");
        Thread.Builder builder = Thread.builder().group(group);

        Thread thread1 = builder.task(() -> { }).build();

        AtomicBoolean done = new AtomicBoolean();
        Thread thread2 = builder.task(() -> {
            while (!done.get()) {
                LockSupport.park();
            }
        }).start();

        Thread thread3 = builder.factory().newThread(() -> { });

        try {
            assertTrue(thread1.getThreadGroup() == group);
            assertTrue(thread2.getThreadGroup() == group);
            assertTrue(thread3.getThreadGroup() == group);
        } finally {
            done.set(true);
            LockSupport.unpark(thread2);
        }
    }
    public void testThreadGroup2() {
        ThreadGroup group = new ThreadGroup("groupies");

        Thread.Builder builder1 = Thread.builder().virtual();
        assertThrows(IllegalStateException.class, () -> builder1.group(group));

        Thread.Builder builder2 = Thread.builder().group(group);
        assertThrows(IllegalStateException.class, () -> builder2.virtual());

    }

    // Executor
    public void testExecutor() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Thread carrierThread = pool.submit(Thread::currentThread).get();

            // wrap the executor to capture the carrier thread
            var threads = new ArrayBlockingQueue<Thread>(10);
            Executor wrapper = task -> pool.execute(() -> {
                try {
                    threads.put(Thread.currentThread());
                    pool.execute(task);
                } catch (InterruptedException ignore) { }
            });

            Thread.Builder builder = Thread.builder().virtual(wrapper::execute);

            Thread thread1 = builder.task(() -> { }).build();
            thread1.start();
            thread1.join();

            Thread thread2 = builder.task(() -> { }).start();
            thread2.join();

            Thread thread3 = builder.factory().newThread(() -> { });
            thread3.start();
            thread3.join();

            assertTrue(threads.size() == 3);
            threads.forEach(t -> assertTrue(t == carrierThread));
        } finally {
            pool.shutdown();
        }
    }

    // priority
    public void testPriority1() {
        int priority = Thread.currentThread().getThreadGroup().getMaxPriority();
        Thread.Builder builder = Thread.builder().priority(priority);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.getPriority() == priority);
        assertTrue(thread2.getPriority() == priority);
        assertTrue(thread3.getPriority() == priority);
    }

    public void testPriority2() {
        Thread.Builder builder = Thread.builder().virtual().priority(Thread.MAX_PRIORITY);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.getPriority() == Thread.NORM_PRIORITY);
        assertTrue(thread2.getPriority() == Thread.NORM_PRIORITY);
        assertTrue(thread3.getPriority() == Thread.NORM_PRIORITY);
    }

    // daemon status
    public void testDaemon1() {
        Thread.Builder builder = Thread.builder().daemon(false);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertFalse(thread1.isDaemon());
        assertFalse(thread2.isDaemon());
        assertFalse(thread3.isDaemon());
    }

    public void testDaemon2() {
        Thread.Builder builder = Thread.builder().daemon(true);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.isDaemon());
        assertTrue(thread2.isDaemon());
        assertTrue(thread3.isDaemon());
    }

    public void testDaemon3() {
        Thread.Builder builder = Thread.builder().virtual().daemon(false);

        Thread thread1 = builder.task(() -> { }).build();
        Thread thread2 = builder.task(() -> { }).start();
        Thread thread3 = builder.factory().newThread(() -> { });

        assertTrue(thread1.isDaemon());
        assertTrue(thread2.isDaemon());
        assertTrue(thread3.isDaemon());
    }

    // uncaught exception handler
    public void testUncaughtExceptionHandler1() throws Exception {
        class FooException extends RuntimeException { }
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        Thread thread = Thread.builder()
                .task(() -> { throw new FooException(); })
                .uncaughtExceptionHandler((t, e) -> {
                    assertTrue(t == Thread.currentThread());
                    threadRef.set(t);
                    exceptionRef.set(e);
                })
                .start();
        thread.join();
        assertTrue(threadRef.get() == thread);
        assertTrue(exceptionRef.get() instanceof FooException);
    }

    public void testUncaughtExceptionHandler2() throws Exception {
        class FooException extends RuntimeException { }
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        Thread thread = Thread.builder()
                .virtual()
                .task(() -> { throw new FooException(); })
                .uncaughtExceptionHandler((t, e) -> {
                    assertTrue(t == Thread.currentThread());
                    threadRef.set(t);
                    exceptionRef.set(e);
                })
                .start();
        thread.join();
        assertTrue(threadRef.get() == thread);
        assertTrue(exceptionRef.get() instanceof FooException);
    }

    public void testUncaughtExceptionHandler3() throws Exception {
        class FooException extends RuntimeException { }
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        Thread thread = Thread.builder()
                .uncaughtExceptionHandler((t, e) -> {
                    assertTrue(t == Thread.currentThread());
                    threadRef.set(t);
                    exceptionRef.set(e);
                })
                .factory()
                .newThread(() -> { throw new FooException(); });
        thread.start();
        thread.join();
        assertTrue(threadRef.get() == thread);
        assertTrue(exceptionRef.get() instanceof FooException);
    }

    public void testUncaughtExceptionHandler4() throws Exception {
        class FooException extends RuntimeException { }
        AtomicReference<Thread> threadRef = new AtomicReference<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
        Thread thread = Thread.builder()
                .virtual()
                .uncaughtExceptionHandler((t, e) -> {
                    assertTrue(t == Thread.currentThread());
                    threadRef.set(t);
                    exceptionRef.set(e);
                })
                .factory()
                .newThread(() -> { throw new FooException(); });
        thread.start();
        thread.join();
        assertTrue(threadRef.get() == thread);
        assertTrue(exceptionRef.get() instanceof FooException);
    }


    static final ThreadLocal<Object> LOCAL = new ThreadLocal<>();
    static final ThreadLocal<Object> INHERITED_LOCAL = new InheritableThreadLocal<>();

    /**
     * Tests that a builder creates threads that support thread locals
     */
    private void testThreadLocals(Thread.Builder builder) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        Runnable task = () -> {
            Object value = new Object();
            LOCAL.set(value);
            assertTrue(LOCAL.get() == value);
            done.set(true);
        };

        done.set(false);
        Thread thread1 = builder.task(task).build();
        thread1.start();
        thread1.join();
        assertTrue(done.get());

        done.set(false);
        Thread thread2 = builder.task(task).start();
        thread2.join();
        assertTrue(done.get());


        done.set(false);
        Thread thread3 = builder.factory().newThread(task);
        thread3.start();
        thread3.join();
        assertTrue(done.get());
    }

    /**
     * Tests that a builder creates threads that do not support thread locals
     */
    private void testNoThreadLocals(Thread.Builder builder) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        Runnable task = () -> {
            try {
                LOCAL.set(new Object());
            } catch (UnsupportedOperationException expected) {
                done.set(true);
            }
        };

        done.set(false);
        Thread thread1 = builder.task(task).build();
        thread1.start();
        thread1.join();
        assertTrue(done.get());

        done.set(false);
        Thread thread2 = builder.task(task).start();
        thread2.join();
        assertTrue(done.get());

        done.set(false);
        Thread thread3 = builder.factory().newThread(task);
        thread3.start();
        thread3.join();
        assertTrue(done.get());
    }

    public void testThreadLocals1() throws Exception {
        Thread.Builder builder = Thread.builder();
        testThreadLocals(builder);
    }

    public void testThreadLocals2() throws Exception {
        Thread.Builder builder = Thread.builder().virtual();
        testThreadLocals(builder);
    }

    public void testThreadLocals3() throws Exception {
        Thread.Builder builder = Thread.builder().disallowThreadLocals();
        testNoThreadLocals(builder);
    }

    public void testThreadLocals4() throws Exception {
        Thread.Builder builder = Thread.builder().virtual().disallowThreadLocals();
        testNoThreadLocals(builder);
    }

    /**
     * Tests that a build creates threads that inherit thread locals
     */
    private void testInheritedThreadLocals(Thread.Builder builder) throws Exception {
        Object value = new Object();
        INHERITED_LOCAL.set(value);

        AtomicBoolean done = new AtomicBoolean();
        Runnable task = () -> {
            assertTrue(INHERITED_LOCAL.get() == value);
            done.set(true);
        };

        done.set(false);
        Thread thread1 = builder.task(task).build();
        thread1.start();
        thread1.join();
        assertTrue(done.get());

        done.set(false);
        Thread thread2 = builder.task(task).start();
        thread2.join();
        assertTrue(done.get());


        done.set(false);
        Thread thread3 = builder.factory().newThread(task);
        thread3.start();
        thread3.join();
        assertTrue(done.get());
    }

    private void testNoInheritedThreadLocals(Thread.Builder builder) throws Exception {
        Object value = new Object();
        INHERITED_LOCAL.set(value);

        AtomicBoolean done = new AtomicBoolean();
        Runnable task = () -> {
            assertTrue(INHERITED_LOCAL.get() == null);
            done.set(true);
        };

        done.set(false);
        Thread thread1 = builder.task(task).build();
        thread1.start();
        thread1.join();
        assertTrue(done.get());

        done.set(false);
        Thread thread2 = builder.task(task).start();
        thread2.join();
        assertTrue(done.get());


        done.set(false);
        Thread thread3 = builder.factory().newThread(task);
        thread3.start();
        thread3.join();
        assertTrue(done.get());
    }

    public void testInheritedThreadLocals1() throws Exception {
        Thread.Builder builder = Thread.builder().inheritThreadLocals();
        testInheritedThreadLocals(builder);
    }

    public void testInheritedThreadLocals2() throws Exception {
        Thread.Builder builder = Thread.builder().virtual().inheritThreadLocals();
        testInheritedThreadLocals(builder);
    }

    public void testInheritedThreadLocals3() throws Exception {
        Thread.Builder builder = Thread.builder();
        testNoInheritedThreadLocals(builder);
    }

    public void testInheritedThreadLocals4() throws Exception {
        Thread.Builder builder = Thread.builder().virtual();
        testNoInheritedThreadLocals(builder);
    }

    // test null parameters
    public void testNulls() {
        Thread.Builder builder = Thread.builder();
        assertThrows(NullPointerException.class, () -> builder.group(null));
        assertThrows(NullPointerException.class, () -> builder.name(null));
        assertThrows(NullPointerException.class, () -> builder.name(null, 0));
        assertThrows(NullPointerException.class, () -> builder.task(null));
        assertThrows(NullPointerException.class, () -> builder.uncaughtExceptionHandler(null));
    }
}

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
 * @modules java.base/java.lang:+open
 * @run testng SchedulerCocktail
 * @summary Test kernel and virtual threads creating virtual threads that use
 *          the same or different schedulers
 */

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class SchedulerCocktail {
    private static final Executor DEFAULT_SCHEDULER = defaultScheduler();
    private static ExecutorService SCHEDULER_1;
    private static ExecutorService SCHEDULER_2;

    @BeforeClass
    public void setup() {
        SCHEDULER_1 = Executors.newFixedThreadPool(1);
        SCHEDULER_2 = Executors.newFixedThreadPool(1);
    }

    @AfterClass
    public void shutdown() {
        SCHEDULER_1.shutdown();
        SCHEDULER_2.shutdown();
    }

    /**
     * Test kernel thread invoking Thread.startVirtualThread.
     */
    public void testStartVirtualThread1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            ref.set(scheduler(Thread.currentThread()));
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.startVirtualThread.
     */
    public void testStartVirtualThread2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                Thread.startVirtualThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.startVirtualThread.
     */
    public void testStartVirtualThread3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                Thread.startVirtualThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create virtual thread
     * without specifying the scheduler.
     */
    public void testBuilderNoScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual().task(() -> {
            ref.set(scheduler(Thread.currentThread()));
        }).start().join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create virtual thread without specifying the scheduler.
     */
    public void testBuilderNoScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                Thread.builder().virtual().task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create virtual thread without specifying the scheduler.
     */
    public void testBuilderNoScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                Thread.builder().virtual().task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create virtual thread
     * that uses a custom scheduler.
     */
    public void testBuilderCustomScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            ref.set(scheduler(Thread.currentThread()));
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create virtual thread that uses a custom scheduler.
     */
    public void testBuilderCustomScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                Thread.builder().virtual(SCHEDULER_1).task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create virtual thread that uses a different custom scheduler.
     */
    public void testBuilderCustomScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                Thread.builder().virtual(SCHEDULER_2).task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_2);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create virtual thread
     * that uses the default scheduler.
     */
    public void testBuilderNullScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(null).task(() -> {
            ref.set(scheduler(Thread.currentThread()));
        }).start().join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create virtual thread that uses the default scheduler.
     */
    public void testBuilderNullScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                Thread.builder().virtual(null).task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create virtual thread that uses the default scheduler.
     */
    public void testBuilderNullScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                Thread.builder().virtual(null).task(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                }).start().join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create a ThreadFactory
     * that creates virtual thread without specifying the scheduler.
     */
    public void testThreadFactoryNoScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        ThreadFactory factory = Thread.builder().virtual().factory();
        Thread thread = factory.newThread(() -> {
            ref.set(scheduler(Thread.currentThread()));
        });
        thread.start();
        thread.join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread without specifying
     * the scheduler.
     */
    public void testThreadFactoryNoScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual().factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread without specifying
     * the scheduler.
     */
    public void testThreadFactoryNoScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual().factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create a ThreadFactory
     * that creates virtual thread using a custom scheduler.
     */
    public void testThreadFactoryCustomScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        ThreadFactory factory = Thread.builder().virtual(SCHEDULER_1).factory();
        Thread thread = factory.newThread(() -> {
            ref.set(scheduler(Thread.currentThread()));
        });
        thread.start();
        thread.join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread using a custom
     * scheduler.
     */
    public void testThreadFactoryCustomScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual(SCHEDULER_1).factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == SCHEDULER_1);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread using a custom
     * scheduler.
     */
    public void testThreadFactoryCustomScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual(SCHEDULER_2).factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == SCHEDULER_2);
    }

    /**
     * Test kernel thread invoking Thread.Builder to create a ThreadFactory
     * that creates virtual thread using the default scheduler.
     */
    public void testThreadFactoryNullScheduler1() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        ThreadFactory factory = Thread.builder().virtual(null).factory();
        Thread thread = factory.newThread(() -> {
            ref.set(scheduler(Thread.currentThread()));
        });
        thread.start();
        thread.join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using default scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread using the
     * default scheduler.
     */
    public void testThreadFactoryNullScheduler2() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual(null).factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Test virtual thread using custom scheduler invoking Thread.Builder
     * to create a ThreadFactory that creates virtual thread using the
     * default scheduler.
     */
    public void testThreadFactoryNullScheduler3() throws Exception {
        AtomicReference<Executor> ref = new AtomicReference<>();
        Thread.builder().virtual(SCHEDULER_1).task(() -> {
            try {
                ThreadFactory factory = Thread.builder().virtual(null).factory();
                Thread thread = factory.newThread(() -> {
                    ref.set(scheduler(Thread.currentThread()));
                });
                thread.start();
                thread.join();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start().join();
        assertTrue(ref.get() == DEFAULT_SCHEDULER);
    }

    /**
     * Returns the default scheduler.
     */
    private static Executor defaultScheduler() {
        try {
            Field defaultScheduler = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("DEFAULT_SCHEDULER");
            defaultScheduler.setAccessible(true);
            return (Executor) defaultScheduler.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the scheduler for the given virtual thread.
     */
    private static Executor scheduler(Thread thread) {
        if (!thread.isVirtual())
            throw new IllegalArgumentException("Not a virtual thread");
        try {
            Field scheduler = Class.forName("java.lang.VirtualThread")
                    .getDeclaredField("scheduler");
            scheduler.setAccessible(true);
            return (Executor) scheduler.get(thread);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
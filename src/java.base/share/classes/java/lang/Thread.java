/*
 * Copyright (c) 1994, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package java.lang;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import jdk.internal.misc.TerminatingThreadLocal;
import jdk.internal.misc.Unsafe;
import sun.nio.ch.Interruptible;
import jdk.internal.reflect.CallerSensitive;
import jdk.internal.reflect.Reflection;
import sun.security.util.SecurityConstants;
import jdk.internal.HotSpotIntrinsicCandidate;

/**
 * A <i>thread</i> is a thread of execution in a program. The Java
 * virtual machine allows an application to have multiple threads of
 * execution running concurrently.
 *
 * <p> {@code Thread} supports the creation of threads that are scheduled by the
 * operating system. These threads are sometimes known as <i>kernel threads</i>
 * or <i>heavyweight threads</i> and will usually have a large stack and other
 * resources that are maintained by the operating system. Kernel threads are
 * suitable for executing all tasks but they are a limited resource.
 *
 * <p> {@code Thread} also supports the creation of <i>virtual threads</i> that
 * are scheduled by the Java virtual machine rather than the operating system.
 * Virtual threads will typically require few resources and a single Java virtual
 * machine may support millions of virtual threads. Virtual threads are suitable
 * for executing tasks that spend most of the time blocked, often waiting for
 * synchronous blocking I/O operations to complete.
 * Virtual threads execute on a pool of <i>carrier threads</i>, essentially
 * a pool of kernel threads that have been created and allocated to support the
 * execution of virtual threads. Locking and I/O operations are the <i>scheduling
 * points</i> where a carrier thread is re-scheduled from one virtual thread to
 * another. Code executing in virtual threads will usually not be aware of the
 * underlying carrier thread, and in particular, the {@linkplain Thread#currentThread()}
 * method, to obtain a reference to the <i>current thread</i>, will return the
 * {@code Thread} object for the virtual thread.
 *
 * <p> {@code Thread} defines factory methods, and a {@linkplain Builder} API,
 * for creating kernel or virtual threads. It also defines (for compatibility and
 * customization reasons) constructors for creating kernel threads. Newer code
 * is encouraged to use the factory methods or the builder rather than the constructors.
 *
 * <p> Kernel threads are designated <i>daemon</i> or <i>non-daemon</i> threads.
 * When the Java virtual machine starts up, there is usually one non-daemon
 * thread (the thread that typically calls the applications's {@code main} method).
 * The Java virtual machine terminates when all non-daemon threads have terminated.
 * The Java virtual machine can also be terminated by invoking the
 * {@linkplain Runtime#exit(int)} method, in which case it will terminate even
 * if there are many non-daemon threads still running. The daemon status of
 * virtual threads is meaningless and have no influence on when the Java virtual
 * machine terminates.
 *
 * <p> Unless otherwise specified, passing a {@code null} argument to a constructor
 * or method in this class will cause a {@link NullPointerException} to be
 * thrown.
 *
 * @author  unascribed
 * @see     Runtime#exit(int)
 * @since   1.0
 */
public class Thread implements Runnable {
    /* Make sure registerNatives is the first thing <clinit> does. */
    private static native void registerNatives();
    static {
        registerNatives();
    }

    /* Reserved for exclusive use by the JVM, TBD: move to FieldHolder */
    private long eetop;

    // holds fields for kernel threads
    private static class FieldHolder {
        final ThreadGroup group;
        final Runnable task;
        final long stackSize;
        int priority;
        boolean daemon;
        volatile int threadStatus;
        boolean stillborn;

        FieldHolder(ThreadGroup group,
                    Runnable task,
                    long stackSize,
                    int priority,
                    boolean daemon) {
            this.group = group;
            this.task = task;
            this.stackSize = stackSize;
            this.priority = priority;
            this.daemon = daemon;
        }
    }
    private final FieldHolder holder;
    
    // interrupt status (read/written by VM)
    volatile boolean interrupted;

    // thread name
    private volatile String name;

    // thread id
    private final long tid;

    // context ClassLoader
    private ClassLoader contextClassLoader;

    // inherited AccessControlContext, TBD: move this to FieldHolder
    private AccessControlContext inheritedAccessControlContext;

    /* For autonumbering anonymous threads. */
    private static int threadInitNumber;
    private static synchronized int nextThreadNum() {
        return threadInitNumber++;
    }

    /* ThreadLocal values pertaining to this thread. This map is maintained
     * by the ThreadLocal class. */
    ThreadLocal.ThreadLocalMap threadLocals = null;

    /*
     * InheritableThreadLocal values pertaining to this thread. This map is
     * maintained by the InheritableThreadLocal class.
     */
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null;

    /**
     * Helper class to generate unique thread identifiers. The identifiers start
     * at 2 as this class cannot be used during early startup to generate the
     * identifier for the primordial thread.
     */
    private static class ThreadIdentifiers {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static final long nextTidOffset =
            U.objectFieldOffset(ThreadIdentifiers.class, "nextTid");
        private static volatile long nextTid = 2;
        private static long next() {
            return U.getAndAddLong(ThreadIdentifiers.class, nextTidOffset, 1);
        }
    }

    /*
     * Lock object for thread interrupt.
     */
    final Object interruptLock = new Object();

    /**
     * The argument supplied to the current call to
     * java.util.concurrent.locks.LockSupport.park.
     * Set by (private) java.util.concurrent.locks.LockSupport.setBlocker
     * Accessed using java.util.concurrent.locks.LockSupport.getBlocker
     */
    private volatile Object parkBlocker;

    /* The object in which this thread is blocked in an interruptible I/O
     * operation, if any.  The blocker's interrupt method should be invoked
     * after setting this thread's interrupt status.
     */
    volatile Interruptible nioBlocker;

    /* Set the blocker field; invoked via jdk.internal.access.SharedSecrets
     * from java.nio code
     */
    static void blockedOn(Interruptible b) {
        Thread me = Thread.currentThread();
        synchronized (me.interruptLock) {
            me.nioBlocker = b;
        }
    }

    /**
     * The minimum priority that a thread can have.
     */
    public static final int MIN_PRIORITY = 1;

   /**
     * The default priority that is assigned to a thread.
     */
    public static final int NORM_PRIORITY = 5;

    /**
     * The maximum priority that a thread can have.
     */
    public static final int MAX_PRIORITY = 10;

    // current inner-most continuation
    private Continuation cont;

    // the virtual thread mounted on this thread
    private VirtualThread vthread;

    /**
     * Sets the virtual thread that is currently mounted on this thread.
     */
    void setVirtualThread(VirtualThread vthread) {
        // assert this == currentThread0();
        this.vthread = vthread;
    }

    /**
     * Returns the virtual thread that is currently mounted on this thread.
     */
    VirtualThread getVirtualThread() {
        // assert this == currentThread0();
        return vthread;
    }

    /**
     * Returns the Thread object for the current thread.
     *
     * @return  the current thread
     */
    public static Thread currentThread() {
        Thread t = currentThread0();
        VirtualThread vthread = t.vthread;
        if (vthread != null) {
            return vthread;
        } else {
            return t;
        }
    }

    /**
     * Returns the current carrier thread.
     */
    static Thread currentCarrierThread() {
        return currentThread0();
    }

    // Scoped support:

    /**
     * TBD
     * @return TBD
     */
    @HotSpotIntrinsicCandidate
    static native Object[] scopedCache();

    @HotSpotIntrinsicCandidate
    static native void setScopedCache(Object[] cache);

    // A simple (not very) random string of bits to use when evicting
    // cache entries.
    int victims
        = 0b1100_1001_0000_1111_1101_1010_1010_0010;

    private ScopedMap scopedMap;

    final ScopedMap scopedMap() {
        var map = scopedMap;
        if (map == null) {
            map = scopedMap = new ScopedMap();
        }
        return map;
    }

    // end Scoped support

    /**
     * TBD
     * @return TBD
     */
    @HotSpotIntrinsicCandidate
    static native Thread currentThread0();

    /**
     * A hint to the scheduler that the current thread is willing to yield
     * its current use of a processor. The scheduler is free to ignore this
     * hint.
     *
     * <p> Yield is a heuristic attempt to improve relative progression
     * between threads that would otherwise over-utilise a CPU. Its use
     * should be combined with detailed profiling and benchmarking to
     * ensure that it actually has the desired effect.
     *
     * <p> It is rarely appropriate to use this method. It may be useful
     * for debugging or testing purposes, where it may help to reproduce
     * bugs due to race conditions. It may also be useful when designing
     * concurrency control constructs such as the ones in the
     * {@link java.util.concurrent.locks} package.
     */
    public static void yield() {
        VirtualThread vthread = currentCarrierThread().getVirtualThread();
        if (vthread != null) {
            vthread.tryYield();
        } else {
            yield0();
        }
    }
    private static native void yield0();

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified number of milliseconds, subject to
     * the precision and accuracy of system timers and schedulers. The thread
     * does not lose ownership of any monitors.
     *
     * @param  millis
     *         the length of time to sleep in milliseconds
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public static void sleep(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        VirtualThread vthread = currentCarrierThread().getVirtualThread();
        if (vthread != null) {
            vthread.sleepNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        } else {
            sleep0(millis);
        }
    }
    private static native void sleep0(long millis) throws InterruptedException;

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified number of milliseconds plus the specified
     * number of nanoseconds, subject to the precision and accuracy of system
     * timers and schedulers. The thread does not lose ownership of any
     * monitors.
     *
     * @param  millis
     *         the length of time to sleep in milliseconds
     *
     * @param  nanos
     *         {@code 0-999999} additional nanoseconds to sleep
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative, or the value of
     *          {@code nanos} is not in the range {@code 0-999999}
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }

        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++;
        }

        sleep(millis);
    }

    /**
     * Causes the currently executing thread to sleep (temporarily cease
     * execution) for the specified duration, subject to the precision and
     * accuracy of system timers and schedulers. This method is a no-op if
     * the duration is less than zero.
     *
     * @param  duration
     *         the duration to sleep
     *
     * @throws  InterruptedException
     *          if the current thread is interrupted while sleeping. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     *
     * @since 99
     */
    public static void sleep(Duration duration) throws InterruptedException {
        if (!duration.isNegative()) {
            // ignore nano precision for now
            long millis = Long.max(TimeUnit.MILLISECONDS.convert(duration), 1);
            sleep(millis);
        }
    }

    /**
     * Indicates that the caller is momentarily unable to progress, until the
     * occurrence of one or more actions on the part of other activities. By
     * invoking this method within each iteration of a spin-wait loop construct,
     * the calling thread indicates to the runtime that it is busy-waiting.
     * The runtime may take action to improve the performance of invoking
     * spin-wait loop constructions.
     *
     * @apiNote
     * As an example consider a method in a class that spins in a loop until
     * some flag is set outside of that method. A call to the {@code onSpinWait}
     * method should be placed inside the spin loop.
     * <pre>{@code
     *     class EventHandler {
     *         volatile boolean eventNotificationNotReceived;
     *         void waitForEventAndHandleIt() {
     *             while ( eventNotificationNotReceived ) {
     *                 java.lang.Thread.onSpinWait();
     *             }
     *             readAndProcessEvent();
     *         }
     *
     *         void readAndProcessEvent() {
     *             // Read event from some source and process it
     *              . . .
     *         }
     *     }
     * }</pre>
     * <p>
     * The code above would remain correct even if the {@code onSpinWait}
     * method was not called at all. However on some architectures the Java
     * Virtual Machine may issue the processor instructions to address such
     * code patterns in a more beneficial way.
     *
     * @since 9
     */
    @HotSpotIntrinsicCandidate
    public static void onSpinWait() {}

    /**
     * Returns the context class loader to inherit from the given parent thread
     */
    private static ClassLoader contextClassLoader(Thread parent) {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || isCCLOverridden(parent.getClass())) {
            return parent.getContextClassLoader();
        } else {
            return parent.contextClassLoader;
        }
    }

    /**
     * Initializes a kernel Thread.
     *
     * @param g the Thread group
     * @param name the name of the new Thread
     * @param characteristics thread characteristics
     * @param task the object whose run() method gets called

     * @param stackSize the desired stack size for the new thread, or
     *        zero to indicate that this parameter is to be ignored.
     * @param acc the AccessControlContext to inherit, or
     *            AccessController.getContext() if null
     * @throws IllegalArgumentException if invalid characteristics are specified
     */
    private Thread(ThreadGroup g, String name, int characteristics, Runnable task,
                   long stackSize, AccessControlContext acc) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        checkCharacteristics(characteristics);

        Thread parent = currentThread();
        boolean primordial = (parent == this);

        SecurityManager security = System.getSecurityManager();
        if (g == null) {
            /* Determine if it's an applet or not */

            /* If there is a security manager, ask the security manager
               what to do. */
            if (security != null) {
                g = security.getThreadGroup();
            }

            /* If the security manager doesn't have a strong opinion
               on the matter, use the parent thread group. */
            if (g == null) {
                g = parent.getThreadGroup();
            }
        }

        /* checkAccess regardless of whether or not threadgroup is
           explicitly passed in. */
        g.checkAccess();

        /*
         * Do we have the required permissions?
         */
        if (security != null) {
            if (isCCLOverridden(getClass())) {
                security.checkPermission(
                        SecurityConstants.SUBCLASS_IMPLEMENTATION_PERMISSION);
            }
        }

        g.addUnstarted();

        this.name = name;
        this.tid = primordial ? 1 : ThreadIdentifiers.next();
        this.contextClassLoader = contextClassLoader(parent);
        this.inheritedAccessControlContext = (acc != null) ? acc : AccessController.getContext();

        // thread locals
        if ((characteristics & NO_THREAD_LOCALS) != 0) {
            this.threadLocals = ThreadLocal.ThreadLocalMap.NOT_SUPPORTED;
            this.inheritableThreadLocals = ThreadLocal.ThreadLocalMap.NOT_SUPPORTED;
        } else if ((characteristics & INHERIT_THREAD_LOCALS) != 0) {
            ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
            if (parentMap != null && parentMap != ThreadLocal.ThreadLocalMap.NOT_SUPPORTED) {
                this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
            }
        }

        int priority;
        boolean daemon;
        if (primordial) {
            // primordial or attached thread
            priority = NORM_PRIORITY;
            daemon = false;
        } else {
            priority = parent.getPriority();
            daemon = parent.isDaemon();
        }
        this.holder = new FieldHolder(g, task, stackSize, priority, daemon);
    }

    /**
     * Initializes a virtual Thread.
     *
     * @param name thread name, can be null
     * @param characteristics thread characteristics
     * @throws IllegalArgumentException if invalid characteristics are specified
     */
    Thread(String name, int characteristics) {
        checkCharacteristics(characteristics);

        Thread parent = currentThread();

        this.name = (name != null) ? name : "<unnamed>";
        this.tid = ThreadIdentifiers.next();
        this.contextClassLoader = contextClassLoader(parent);
        this.inheritedAccessControlContext = VirtualThreads.ACCESS_CONTROL_CONTEXT;

        // thread locals
        if ((characteristics & NO_THREAD_LOCALS) != 0) {
            this.threadLocals = ThreadLocal.ThreadLocalMap.NOT_SUPPORTED;
            this.inheritableThreadLocals = ThreadLocal.ThreadLocalMap.NOT_SUPPORTED;
        } else if ((characteristics & INHERIT_THREAD_LOCALS) != 0) {
            ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
            if (parentMap != null && parentMap != ThreadLocal.ThreadLocalMap.NOT_SUPPORTED) {
                this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
            }
        }

        // no additional fields
        this.holder = null;
    }

    /**
     * Returns a builder for creating {@code Thread} or {@code ThreadFactory} objects.
     *
     * @apiNote The following are examples using the builder:
     *
     * <pre>{@code
     *   // Create a daemon thread that is scheduled by the operating system
     *   Thread thread = Thread.builder()
     *                 .name("duke")
     *                 .daemon(true)
     *                 .priority(Thread.NORM_PRIORITY)
     *                 .inheritThreadLocals()
     *                 .task(...)
     *                 .build();
     *
     *   // A ThreadFactory that creates daemon threads named "worker-0", "worker-1", ...
     *   ThreadFactory factory = Thread.builder().daemon(true).name("worker-", 0).factory();
     *
     *   // Create an unnamed virtual thread
     *   Thread thread1 = Thread.builder().virtual().task(...).build();
     *
     *   // Create a named virtual thread
     *   Thread thread2 = Thread.builder().virtual().name("duke").task(...).build();
     *
     *   // Create and start a virtual thread
     *   Thread thread = Thread.builder().virtual().task(...).start();
     *
     *   // A ThreadFactory that creates virtual threads
     *   ThreadFactory factory = Thread.builder().virtual().factory();
     *
     *   // A ThreadFactory that creates virtual threads and uses a custom scheduler
     *   Executor scheduler = ...
     *   ThreadFactory factory = Thread.builder().virtual(scheduler).factory();
     * }</pre>
     *
     * @return A builder for creating {@code Thread} or {@code ThreadFactory} objects.
     *
     * @since 99
     */
    public static Builder builder() {
        return new BuilderImpl();
    }

    /**
     * A mutable builder for a {@link Thread} or {@link ThreadFactory}.
     *
     * <p> {@code Builder} defines methods to set the {@code Thread} characteristics
     * and features. Once set, a {@code Thread} or {@code ThreadFactory} can be
     * created with the following methods:
     *
     * <ul>
     *     <li> The {@linkplain #build() build} method creates an unstarted {@code Thread}.
     *     <li> The {@linkplain #start() start} method creates and starts a {@code Thread}.
     *     <li> The {@linkplain #factory() factory} method creates a {@code ThreadFactory}.
     * </ul>
     *
     * <p> A {@code Builder} is not thread safe. The {@code ThreadFactory}
     * returned by the builder's {@code factory() method} is thread safe.
     *
     * <p> Unless otherwise specified, passing a null argument to a method in
     * this interface causes a {@code NullPointerException} to be thrown.
     *
     * @apiNote {@code Builder} checks invariants as components are added to the builder.
     * The rationale for this is to detect errors as early as possible and not defer
     * all validation to the {@code build} method.
     *
     * @see Thread#builder()
     * @since 99
     */
    public interface Builder {

        /**
         * Sets the thread group.
         *
         * <p> The thread group for threads that are scheduled by the Java virtual
         * machine threads does not support all features of regular thread groups.
         * The thread group can only be set for threads that are scheduled by
         * the operating system.
         *
         * @param group the thread group
         * @return this builder
         * @throws IllegalStateException if this is a builder for a virtual thread
         */
        Builder group(ThreadGroup group);

        /**
         * Sets the thread name.
         * @param name thread name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the thread name to be the concatenation of a string prefix and
         * a counter value.
         * @param prefix thread name prefix
         * @param start counter start
         * @return this builder
         * @throws IllegalArgumentException if count is negative
         */
        Builder name(String prefix, int start);

        /**
         * The thread will be scheduled by the Java virtual machine rather than
         * the operating system with the default scheduler.
         * @return this builder
         * @throws IllegalStateException if a thread group has been set
         */
        Builder virtual();

        /**
         * The thread will be scheduled by the Java virtual machine rather than
         * the operating system with the given scheduler.
         * @param scheduler the scheduler
         * @return this builder
         * @throws IllegalStateException if a thread group has been set
         */
        Builder virtual(Executor scheduler);

        /**
         * Disallow threads locals.
         * @return this builder
         * @throws IllegalStateException if inheritThreadLocals has already been set
         */
        Builder disallowThreadLocals();

        /**
         * Inherit threads locals. Thread locals are inherited when the {@code Thread}
         * is created with the {@link #build() build} method or when the thread
         * factory {@link ThreadFactory#newThread(Runnable) newThread} method
         * is invoked.
         * @return this builder
         * @throws IllegalStateException if disallowThreadLocals has already been set
         */
        Builder inheritThreadLocals();

        /**
         * Sets the daemon status.
         * @param on {@code true} to create daemon threads
         * @return this builder
         */
        Builder daemon(boolean on);

        /**
         * Sets the thread priority.
         * @param priority priority
         * @return this builder
         * @throws IllegalArgumentException if the priority is less than
         *        {@link Thread#MIN_PRIORITY} or greater than {@link Thread#MAX_PRIORITY}
         */
        Builder priority(int priority);

        /**
         * Sets the uncaught exception handler.
         * @param ueh uncaught exception handler
         * @return this builder
         */
        Builder uncaughtExceptionHandler(UncaughtExceptionHandler ueh);

        /**
         * The thread is <em>managed</em>.
         * @return this builder
         */
        Builder managed();

        /**
         * Sets the task for the thread to run.
         * @param task the task to run
         * @return this builder
         */
        Builder task(Runnable task);

        /**
         * Creates a new unstarted {@code Thread} from the current state of the
         * builder.
         *
         * @return a new unstarted Thread
         * @throws IllegalStateException if the task object to run object has not been set
         * @throws SecurityException if a thread group has been set and the current thread
         *         cannot create a thread in that thread group
         */
        Thread build();

        /**
         * Returns a {@code ThreadFactory} to create threads from the current
         * state of the builder. The returned thread factory is safe for use by
         * multiple concurrent threads.
         *
         * @return a thread factory to create threads
         */
        ThreadFactory factory();

        /**
         * Creates a new {@code Thread} from the current state of the builder
         * and starts it as if by invoking the {@linkplain Thread#start() start}
         * method.
         *
         * @implSpec The default implementation invokes {@linkplain #build() build}
         * to create a {@code Thread} and then invokes its {@linkplain Thread#start()
         * start} method to start it.
         *
         * @return The started thread
         * @throws IllegalStateException if the task object to run object has not been set
         * @throws SecurityException if a thread group has been set and the current thread
         *         cannot create a thread in that thread group
         */
        default Thread start() {
            Thread thread = build();
            thread.start();
            return thread;
        }
    }

    private static class BuilderImpl implements Builder {
        private ThreadGroup group;
        private Executor scheduler;
        private String name;
        private int counter;
        private boolean virtual;
        private boolean disallowThreadLocals;
        private boolean inheritThreadLocals;
        private boolean daemon;
        private boolean daemonChanged;
        private int priority;
        private UncaughtExceptionHandler uhe;
        private Runnable task;

        BuilderImpl() { }

        private int characteristics() {
            int characteristics = 0;
            if (virtual)
                characteristics |= Thread.VIRTUAL;
            if (disallowThreadLocals)
                characteristics |= Thread.NO_THREAD_LOCALS;
            if (inheritThreadLocals)
                characteristics |= Thread.INHERIT_THREAD_LOCALS;
            return characteristics;
        }

        @Override
        public Builder group(ThreadGroup group) {
            Objects.requireNonNull(group);
            if (virtual)
                throw new IllegalStateException();
            this.group = group;
            return this;
        }

        @Override
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name);
            this.counter = -1;
            return this;
        }

        @Override
        public Builder name(String prefix, int start) {
            Objects.requireNonNull(prefix);
            if (start < 0)
                throw new IllegalArgumentException("'start' is negative");
            this.name = prefix;
            this.counter = start;
            return this;
        }

        @Override
        public Builder virtual() {
            if (group != null)
                throw new IllegalStateException();
            this.virtual = true;
            this.scheduler = null;
            return this;
        }

        @Override
        public Builder virtual(Executor scheduler) {
            Objects.requireNonNull(scheduler);
            if (group != null)
                throw new IllegalStateException();
            this.virtual = true;
            this.scheduler = scheduler;
            return this;
        }

        @Override
        public Builder disallowThreadLocals() {
            if (inheritThreadLocals)
                throw new IllegalStateException();
            this.disallowThreadLocals = true;
            return this;
        }

        @Override
        public Builder inheritThreadLocals() {
            if (disallowThreadLocals)
                throw new IllegalStateException();
            this.inheritThreadLocals = true;
            return this;
        }

        @Override
        public Builder daemon(boolean on) {
            daemon = on;
            daemonChanged = true;
            return this;
        }

        @Override
        public Builder priority(int priority) {
            if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY)
                throw new IllegalArgumentException();
            this.priority = priority;
            return this;
        }

        @Override
        public Builder uncaughtExceptionHandler(UncaughtExceptionHandler ueh) {
            this.uhe = Objects.requireNonNull(ueh);
            return this;
        }

        @Override
        public Builder managed() {
            // TDB
            return this;
        }

        @Override
        public Thread build() {
            Runnable task = this.task;
            if (task == null)
                throw new IllegalStateException("No task specified");

            int characteristics = characteristics();
            Thread thread;
            if ((characteristics & Thread.VIRTUAL) != 0) {
                String name = this.name;
                if (name != null && counter >= 0) {
                    name = name + (counter++);
                }
                thread = new VirtualThread(scheduler, name, characteristics, task);
            } else {
                String name = this.name;
                if (name == null) {
                    name = "Thread-" + nextThreadNum();
                } else if (counter >= 0) {
                    name = name + (counter++);
                }
                thread = new Thread(group, name, characteristics, task, 0, null);
                if (daemonChanged)
                    thread.daemon(daemon);
                if (priority != 0)
                    thread.priority(priority);
            }
            if (uhe != null)
                thread.uncaughtExceptionHandler(uhe);
            return thread;
        }

        @Override
        public Builder task(Runnable task) {
            this.task = Objects.requireNonNull(task);
            return this;
        }

        @Override
        public ThreadFactory factory() {
            int characteristics = characteristics();
            if ((characteristics & Thread.VIRTUAL) != 0) {
                return new VirtualThreadFactory(scheduler, name, counter, characteristics, uhe);
            } else {
                return new KernelThreadFactory(group, name, counter, characteristics,
                                               daemon, priority, uhe);
            }
        }
    }

    private static abstract class CountingThreadFactory implements ThreadFactory {
        private static final VarHandle COUNT;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                COUNT = l.findVarHandle(CountingThreadFactory.class, "count", int.class);
            } catch (Exception e) {
                throw new InternalError(e);
            }
        }
        private volatile int count;
        private final boolean hasCounter;

        CountingThreadFactory(int start) {
            if (start >= 0) {
                count = start;
                hasCounter = true;
            } else {
                hasCounter = false;
            }
        }

        boolean hasCounter() {
            return hasCounter;
        }

        int next() {
            return (int) COUNT.getAndAdd(this, 1);
        }
    }

    private static class VirtualThreadFactory extends CountingThreadFactory {
        private final Executor scheduler;
        private String name;
        private final int characteristics;
        private final UncaughtExceptionHandler uhe;

        VirtualThreadFactory(Executor scheduler,
                             String name,
                             int start,
                             int characteristics,
                             UncaughtExceptionHandler uhe) {
            super(start);
            this.scheduler = scheduler;
            this.name = name;
            this.characteristics = characteristics;
            this.uhe = uhe;
        }

        @Override
        public Thread newThread(Runnable task) {
            Objects.requireNonNull(task);
            String name = this.name;
            if (name != null && hasCounter()) {
                name += next();
            }
            Thread thread = new VirtualThread(scheduler, name, characteristics, task);
            if (uhe != null)
                thread.uncaughtExceptionHandler(uhe);
            return thread;
        }
    }

    private static class KernelThreadFactory extends CountingThreadFactory {
        private final ThreadGroup group;
        private final String name;
        private final int characteristics;
        private final boolean daemon;
        private final int priority;
        private final UncaughtExceptionHandler uhe;

        KernelThreadFactory(ThreadGroup group,
                            String name,
                            int start,
                            int characteristics,
                            boolean daemon,
                            int priority,
                            UncaughtExceptionHandler uhe) {
            super(start);
            this.group = group;
            this.name = name;
            this.characteristics = characteristics;
            this.daemon = daemon;
            this.priority = priority;
            this.uhe = uhe;
        }

        @Override
        public Thread newThread(Runnable task) {
            Objects.requireNonNull(task);
            String name = this.name;
            if (name == null) {
                name = "Thread-" + nextThreadNum();
            } else if (hasCounter()) {
                name += next();
            }
            Thread thread = new Thread(group, name, characteristics, task, 0, null);
            if (daemon)
                thread.daemon(true);
            if (priority != 0)
                thread.priority(priority);
            if (uhe != null)
                thread.uncaughtExceptionHandler(uhe);
            return thread;
        }
    }

    /**
     * Throws CloneNotSupportedException as a Thread can not be meaningfully
     * cloned. Construct a new Thread instead.
     *
     * @throws  CloneNotSupportedException
     *          always
     */
    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, null, gname)}, where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     */
    public Thread() {
        this(null, null, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, task, gname)}, where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this classes {@code run} method does
     *         nothing.
     */
    public Thread(Runnable task) {
        this(null, task, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Creates a new Thread that inherits the given AccessControlContext
     * but thread-local variables are not inherited.
     * This is not a public constructor.
     */
    Thread(Runnable task, AccessControlContext acc) {
        this(null, "Thread-" + nextThreadNum(), 0, task, 0, acc);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (group, task, gname)} ,where {@code gname} is a newly generated
     * name. Automatically generated names are of the form
     * {@code "Thread-"+}<i>n</i>, where <i>n</i> is an integer.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     */
    public Thread(ThreadGroup group, Runnable task) {
        this(group, task, "Thread-" + nextThreadNum(), 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, null, name)}.
     *
     * @param   name
     *          the name of the new thread
     */
    public Thread(String name) {
        this(null, null, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (group, null, name)}.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  name
     *         the name of the new thread
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     */
    public Thread(ThreadGroup group, String name) {
        this(group, null, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object. This constructor has the same
     * effect as {@linkplain #Thread(ThreadGroup,Runnable,String) Thread}
     * {@code (null, task, name)}.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     */
    public Thread(Runnable task, String name) {
        this(null, task, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code task}
     * as its run object, has the specified {@code name} as its name,
     * and belongs to the thread group referred to by {@code group}.
     *
     * <p>If there is a security manager, its
     * {@link SecurityManager#checkAccess(ThreadGroup) checkAccess}
     * method is invoked with the ThreadGroup as its argument.
     *
     * <p>In addition, its {@code checkPermission} method is invoked with
     * the {@code RuntimePermission("enableContextClassLoaderOverride")}
     * permission when invoked directly or indirectly by the constructor
     * of a subclass which overrides the {@code getContextClassLoader}
     * or {@code setContextClassLoader} methods.
     *
     * <p>The priority of the newly created thread is set equal to the
     * priority of the thread creating it, that is, the currently running
     * thread. The method {@linkplain #setPriority setPriority} may be
     * used to change the priority to a new value.
     *
     * <p>The newly created thread is initially marked as being a daemon
     * thread if and only if the thread creating it is currently marked
     * as a daemon thread. The method {@linkplain #setDaemon setDaemon}
     * may be used to change whether or not a thread is a daemon.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group or cannot override the context class loader methods.
     */
    public Thread(ThreadGroup group, Runnable task, String name) {
        this(group, task, name, 0);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code task}
     * as its run object, has the specified {@code name} as its name,
     * and belongs to the thread group referred to by {@code group}, and has
     * the specified <i>stack size</i>.
     *
     * <p>This constructor is identical to {@link
     * #Thread(ThreadGroup,Runnable,String)} with the exception of the fact
     * that it allows the thread stack size to be specified.  The stack size
     * is the approximate number of bytes of address space that the virtual
     * machine is to allocate for this thread's stack.  <b>The effect of the
     * {@code stackSize} parameter, if any, is highly platform dependent.</b>
     *
     * <p>On some platforms, specifying a higher value for the
     * {@code stackSize} parameter may allow a thread to achieve greater
     * recursion depth before throwing a {@link StackOverflowError}.
     * Similarly, specifying a lower value may allow a greater number of
     * threads to exist concurrently without throwing an {@link
     * OutOfMemoryError} (or other internal error).  The details of
     * the relationship between the value of the {@code stackSize} parameter
     * and the maximum recursion depth and concurrency level are
     * platform-dependent.  <b>On some platforms, the value of the
     * {@code stackSize} parameter may have no effect whatsoever.</b>
     *
     * <p>The virtual machine is free to treat the {@code stackSize}
     * parameter as a suggestion.  If the specified value is unreasonably low
     * for the platform, the virtual machine may instead use some
     * platform-specific minimum value; if the specified value is unreasonably
     * high, the virtual machine may instead use some platform-specific
     * maximum.  Likewise, the virtual machine is free to round the specified
     * value up or down as it sees fit (or to ignore it completely).
     *
     * <p>Specifying a value of zero for the {@code stackSize} parameter will
     * cause this constructor to behave exactly like the
     * {@code Thread(ThreadGroup, Runnable, String)} constructor.
     *
     * <p><i>Due to the platform-dependent nature of the behavior of this
     * constructor, extreme care should be exercised in its use.
     * The thread stack size necessary to perform a given computation will
     * likely vary from one JRE implementation to another.  In light of this
     * variation, careful tuning of the stack size parameter may be required,
     * and the tuning may need to be repeated for each JRE implementation on
     * which an application is to run.</i>
     *
     * <p>Implementation note: Java platform implementers are encouraged to
     * document their implementation's behavior with respect to the
     * {@code stackSize} parameter.
     *
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @param  stackSize
     *         the desired stack size for the new thread, or zero to indicate
     *         that this parameter is to be ignored.
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     *
     * @since 1.4
     */
    public Thread(ThreadGroup group, Runnable task, String name, long stackSize) {
        this(group, name, Thread.INHERIT_THREAD_LOCALS, task, stackSize, null);
    }

    /**
     * Allocates a new {@code Thread} object so that it has {@code task}
     * as its run object, has the specified {@code name} as its name,
     * belongs to the thread group referred to by {@code group}, has
     * the specified {@code stackSize}, and inherits initial values for
     * {@linkplain InheritableThreadLocal inheritable thread-local} variables
     * if {@code inheritThreadLocals} is {@code true}.
     *
     * <p> This constructor is identical to {@link
     * #Thread(ThreadGroup,Runnable,String,long)} with the added ability to
     * suppress, or not, the inheriting of initial values for inheritable
     * thread-local variables from the constructing thread. This allows for
     * finer grain control over inheritable thread-locals. Care must be taken
     * when passing a value of {@code false} for {@code inheritThreadLocals},
     * as it may lead to unexpected behavior if the new thread executes code
     * that expects a specific thread-local value to be inherited.
     *
     * <p> Specifying a value of {@code true} for the {@code inheritThreadLocals}
     * parameter will cause this constructor to behave exactly like the
     * {@code Thread(ThreadGroup, Runnable, String, long)} constructor.
     *
     * @param  group
     *         the thread group. If {@code null} and there is a security
     *         manager, the group is determined by {@linkplain
     *         SecurityManager#getThreadGroup SecurityManager.getThreadGroup()}.
     *         If there is not a security manager or {@code
     *         SecurityManager.getThreadGroup()} returns {@code null}, the group
     *         is set to the current thread's thread group.
     *
     * @param  task
     *         the object whose {@code run} method is invoked when this thread
     *         is started. If {@code null}, this thread's run method is invoked.
     *
     * @param  name
     *         the name of the new thread
     *
     * @param  stackSize
     *         the desired stack size for the new thread, or zero to indicate
     *         that this parameter is to be ignored
     *
     * @param  inheritThreadLocals
     *         if {@code true}, inherit initial values for inheritable
     *         thread-locals from the constructing thread, otherwise no initial
     *         values are inherited
     *
     * @throws  SecurityException
     *          if the current thread cannot create a thread in the specified
     *          thread group
     *
     * @since 9
     */
    public Thread(ThreadGroup group, Runnable task, String name,
                  long stackSize, boolean inheritThreadLocals) {
        this(group, name, (inheritThreadLocals ? Thread.INHERIT_THREAD_LOCALS : 0),
                task, stackSize, null);
    }

    /**
     * Characteristic value signifying that the thread should be scheduled by
     * the Java virtual machine rather than the operating system.
     *
     * @since 99
     */
    public static final int VIRTUAL = 1 << 0;

    /**
     * Characteristic value signifying that {@link ThreadLocal thread-locals}
     * are not supported by the thread.
     *
     * @apiNote This is for experimental purposes, a lot of existing code will
     * not run if thread locals are not supported.
     *
     * @since 99
     */
    public static final int NO_THREAD_LOCALS = 1 << 1;

    /**
     * Characteristic value signifying that {@link InheritableThreadLocal
     * inheritable-thread-locals} are inherihted from the constructing thread.
     * This characteristic is incompatible with {@linkplain #NO_THREAD_LOCALS},
     * they may not be used together.
     *
     * @since 99
     */
    public static final int INHERIT_THREAD_LOCALS = 1 << 2;

    /**
     * Characteristic value signifying that the thread is <em>managed.</em>
     *
     * @since 99
     */
    public static final int MANAGED = 1 << 3;

    private static int validCharacteristics() {
        return (VIRTUAL | NO_THREAD_LOCALS | INHERIT_THREAD_LOCALS | MANAGED);
    }

    private static void checkCharacteristics(int characteristics) {
        if (characteristics != 0) {
            if ((characteristics & ~validCharacteristics()) != 0)
                throw new IllegalArgumentException();
            if ((characteristics & NO_THREAD_LOCALS) != 0
                    && (characteristics & INHERIT_THREAD_LOCALS) != 0)
                throw new IllegalArgumentException();
        }
    }

    /**
     * Creates an unnamed thread.
     *
     * By default, the thread is scheduled by the operating system, supports
     * {@link ThreadLocal thread-locals}, and does not inherit any initial values
     * for {@link InheritableThreadLocal inheritable-thread-locals}.
     * The {@link ThreadGroup ThreadGroup}, {@link #isDaemon() daemon status},
     * {@link #getPriority() priority}, and the {@link #getContextClassLoader()
     * context-class-loader} are inherited from the current thread.
     *
     * <p> The characteristic {@linkplain Thread#VIRTUAL VIRTUAL} is
     * used to create a thread that is scheduled by the Java virtual machine
     * using the default scheduler. The default in this case is to only inherit
     * the {@link #getContextClassLoader() context-class-loader} from the current
     * thread.
     *
     * @apiNote The characteristics will probably be replaced by an enum
     *
     * @param characteristics characteristics of the thread
     * @param task the object to run when the thread executes
     * @throws IllegalArgumentException if an unknown characteristic or an invalid
     *         combination of characteristic is specified
     * @return an un-started virtual thread
     *
     * @since 99
     */
    public static Thread newThread(int characteristics, Runnable task) {
        if ((characteristics & VIRTUAL) != 0) {
            return new VirtualThread(null, null, characteristics, task);
        } else {
            return new Thread(null, "Thread-" + nextThreadNum(), characteristics, task, 0, null);
        }
    }

    /**
     * Creates a named thread.
     *
     * By default, the thread is scheduled by the operating system, supports
     * {@link ThreadLocal thread-locals}, and does not inherit any initial values
     * for {@link InheritableThreadLocal inheritable-thread-locals}.
     * The {@link ThreadGroup ThreadGroup}, {@link #isDaemon() daemon status},
     * {@link #getPriority() priority}, and the {@link #getContextClassLoader()
     * context-class-loader} are inherited from the current thread.
     *
     * <p> The characteristic {@linkplain Thread#VIRTUAL VIRTUAL} is
     * used to create a thread that is scheduled by the Java virtual machine
     * using the default scheduler. The default in this case is to only inherit
     * the {@link #getContextClassLoader() context-class-loader} from the current
     * thread.
     *
     * @apiNote The characteristics will probably be replaced by an enum
     *
     * @param name the thread name
     * @param characteristics characteristics of the thread
     * @param task the object to run when the thread executes
     * @throws IllegalArgumentException if an unknown characteristic or an invalid
     *         combination of characteristic is specified
     * @return an un-started virtual thread
     *
     * @since 99
     */
    public static Thread newThread(String name, int characteristics, Runnable task) {
        if ((characteristics & VIRTUAL) != 0) {
            return new VirtualThread(null, name, characteristics, task);
        } else {
            return new Thread(null, name, characteristics, task, 0, null);
        }
    }

    /**
     * Returns {@code true} if this thread scheduled by the Java virtual machine
     * rather than the operating system.
     *
     * <p> Threads that are scheduled by the Java virtual machine do not support
     * all features of Thread. In particular, the Thread is not an <i>active thread</i>
     * in its thread group and so is not enumerated or acted on by thread group
     * operations. In addition it does not support the stop, suspend or resume
     * methods.
     *
     * @return {@code true} if this thread is scheduled by the Java virtual
     *         machine rather than the operating system
     *
     * @since 99
     */
    public final boolean isVirtual() {
        return (this instanceof VirtualThread);
    }

    /**
     * Schedules this thread to begin execution. The thread will execute
     * independently of the current thread.
     * <p>
     * It is never legal to start a thread more than once.
     * In particular, a thread may not be restarted once it has completed
     * execution.
     *
     * @throws     IllegalThreadStateException  if the thread was already started.
     * @see        #run()
     * @see        Builder#start()
     */
    public synchronized void start() {
        /**
         * This method is not invoked for the main method thread or "system"
         * group threads created/set up by the VM. Any new functionality added
         * to this method in the future may have to also be added to the VM.
         *
         * A zero status value corresponds to state "NEW".
         */
        if (holder.threadStatus != 0)
            throw new IllegalThreadStateException();

        /* Notify the group that this thread is about to be started
         * so that it can be added to the group's list of threads
         * and the group's unstarted count can be decremented. */
        ThreadGroup group = holder.group;
        group.add(this);

        boolean started = false;
        try {
            start0();
            started = true;
        } finally {
            try {
                if (!started) {
                    group.threadStartFailed(this);
                }
            } catch (Throwable ignore) {
                /* do nothing. If start0 threw a Throwable then
                  it will be passed up the call stack */
            }
        }
    }

    private native void start0();

    /**
     * If this thread was constructed using a separate
     * {@code Runnable} run object, then that
     * {@code Runnable} object's {@code run} method is called;
     * otherwise, this method does nothing and returns.
     * This method does nothing when invoked on a {@linkplain #isVirtual()
     * virtual} thread.
     * <p>
     * Subclasses of {@code Thread} should override this method.
     *
     * @see     #start()
     * @see     #Thread(ThreadGroup, Runnable, String)
     */
    @Override
    public void run() {
        if (!isVirtual()) {
            Runnable task = holder.task;
            if (task != null) {
                task.run();
            }
        }
    }

    /**
     * This method is called by the system to give a Thread
     * a chance to clean up before it actually exits.
     */
    private void exit() {
        // assert !isVirtual();
        if (threadLocals != null && TerminatingThreadLocal.REGISTRY.isPresent()) {
            TerminatingThreadLocal.threadTerminated();
        }
        ThreadGroup group = holder.group;
        if (group != null) {
            group.threadTerminated(this);
        }
        /* Aggressively null out all reference fields: see bug 4006245 */
        /* Speed the release of some of these resources */
        threadLocals = null;
        inheritableThreadLocals = null;
        inheritedAccessControlContext = null;
        nioBlocker = null;
        uncaughtExceptionHandler = null;
    }

    /**
     * Forces the thread to stop executing.
     * <p>
     * If there is a security manager installed, its {@code checkAccess}
     * method is called with {@code this}
     * as its argument. This may result in a
     * {@code SecurityException} being raised (in the current thread).
     * <p>
     * If this thread is different from the current thread (that is, the current
     * thread is trying to stop a thread other than itself), the
     * security manager's {@code checkPermission} method (with a
     * {@code RuntimePermission("stopThread")} argument) is called in
     * addition.
     * Again, this may result in throwing a
     * {@code SecurityException} (in the current thread).
     * <p>
     * The thread represented by this thread is forced to stop whatever
     * it is doing abnormally and to throw a newly created
     * {@code ThreadDeath} object as an exception.
     * <p>
     * It is permitted to stop a thread that has not yet been started.
     * If the thread is eventually started, it immediately terminates.
     * <p>
     * An application should not normally try to catch
     * {@code ThreadDeath} unless it must do some extraordinary
     * cleanup operation (note that the throwing of
     * {@code ThreadDeath} causes {@code finally} clauses of
     * {@code try} statements to be executed before the thread
     * officially terminates).  If a {@code catch} clause catches a
     * {@code ThreadDeath} object, it is important to rethrow the
     * object so that the thread actually terminates.
     * <p>
     * The top-level error handler that reacts to otherwise uncaught
     * exceptions does not print out a message or otherwise notify the
     * application if the uncaught exception is an instance of
     * {@code ThreadDeath}.
     *
     * @throws     SecurityException  if the current thread cannot
     *             modify this thread.
     * @throws     UnsupportedOperationException if invoked on a virtual thread
     * @see        #interrupt()
     * @see        #checkAccess()
     * @see        #run()
     * @see        #start()
     * @see        ThreadDeath
     * @see        ThreadGroup#uncaughtException(Thread,Throwable)
     * @see        SecurityManager#checkAccess(Thread)
     * @see        SecurityManager#checkPermission
     * @deprecated This method is inherently unsafe.  Stopping a thread with
     *       Thread.stop causes it to unlock all of the monitors that it
     *       has locked (as a natural consequence of the unchecked
     *       {@code ThreadDeath} exception propagating up the stack).  If
     *       any of the objects previously protected by these monitors were in
     *       an inconsistent state, the damaged objects become visible to
     *       other threads, potentially resulting in arbitrary behavior.  Many
     *       uses of {@code stop} should be replaced by code that simply
     *       modifies some variable to indicate that the task thread should
     *       stop running.  The task thread should check this variable
     *       regularly, and return from its run method in an orderly fashion
     *       if the variable indicates that it is to stop running.  If the
     *       task thread waits for long periods (on a condition variable,
     *       for example), the {@code interrupt} method should be used to
     *       interrupt the wait.
     *       For more information, see
     *       <a href="{@docRoot}/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html">Why
     *       are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     */
    @Deprecated(since="1.2")
    public final void stop() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            checkAccess();
            if (this != Thread.currentThread()) {
                security.checkPermission(SecurityConstants.STOP_THREAD_PERMISSION);
            }
        }

        if (isVirtual())
            throw new UnsupportedOperationException();

        // A zero status value corresponds to "NEW", it can't change to
        // not-NEW because we hold the lock.
        if (holder.threadStatus != 0) {
            resume(); // Wake up thread if it was suspended; no-op otherwise
        }

        // The VM can handle all thread states
        stop0(new ThreadDeath());
    }

    /**
     * Interrupts this thread.
     *
     * <p> Unless the current thread is interrupting itself, which is
     * always permitted, the {@link #checkAccess() checkAccess} method
     * of this thread is invoked, which may cause a {@link
     * SecurityException} to be thrown.
     *
     * <p> If this thread is blocked in an invocation of the {@link
     * Object#wait() wait()}, {@link Object#wait(long) wait(long)}, or {@link
     * Object#wait(long, int) wait(long, int)} methods of the {@link Object}
     * class, or of the {@link #join()}, {@link #join(long)}, {@link
     * #join(long, int)}, {@link #sleep(long)}, or {@link #sleep(long, int)},
     * methods of this class, then its interrupt status will be cleared and it
     * will receive an {@link InterruptedException}.
     *
     * <p> If this thread is blocked in an I/O operation upon an {@link
     * java.nio.channels.InterruptibleChannel InterruptibleChannel}
     * then the channel will be closed, the thread's interrupt
     * status will be set, and the thread will receive a {@link
     * java.nio.channels.ClosedByInterruptException}.
     *
     * <p> If this thread is blocked in a {@link java.nio.channels.Selector}
     * then the thread's interrupt status will be set and it will return
     * immediately from the selection operation, possibly with a non-zero
     * value, just as if the selector's {@link
     * java.nio.channels.Selector#wakeup wakeup} method were invoked.
     *
     * <p> If none of the previous conditions hold then this thread's interrupt
     * status will be set. </p>
     *
     * <p> Interrupting a thread that is not alive need not have any effect.
     *
     * @implNote In the JDK Reference Implementation, interruption of a thread
     * that is not alive still records that the interrupt request was made and
     * will report it via {@link #interrupted} and {@link #isInterrupted()}.
     *
     * @throws  SecurityException
     *          if the current thread cannot modify this thread
     *
     * @revised 6.0, 14
     * @spec JSR-51
     */
    public void interrupt() {
        if (this != Thread.currentThread()) {
            checkAccess();

            // thread may be blocked in an I/O operation
            synchronized (interruptLock) {
                Interruptible b = nioBlocker;
                if (b != null) {
                    interrupted = true;
                    interrupt0();  // inform VM of interrupt
                    b.interrupt(this);
                    return;
                }
            }
        }
        interrupted = true;
        interrupt0();  // inform VM of interrupt
    }

    /**
     * Tests whether the current thread has been interrupted.  The
     * <i>interrupted status</i> of the thread is cleared by this method.  In
     * other words, if this method were to be called twice in succession, the
     * second call would return false (unless the current thread were
     * interrupted again, after the first call had cleared its interrupted
     * status and before the second call had examined it).
     *
     * @return  {@code true} if the current thread has been interrupted;
     *          {@code false} otherwise.
     * @see #isInterrupted()
     * @revised 6.0, 14
     */
    public static boolean interrupted() {
        return currentThread().getAndClearInterrupt();
    }

    /**
     * Tests whether this thread has been interrupted.  The <i>interrupted
     * status</i> of the thread is unaffected by this method.
     *
     * @return  {@code true} if this thread has been interrupted;
     *          {@code false} otherwise.
     * @see     #interrupted()
     * @revised 6.0, 14
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    final void setInterrupt() {
        interrupted = true;
        interrupt0();  // inform VM of interrupt
    }

    final void clearInterrupt() {
        interrupted = false;
        clearInterruptEvent();
    }

    boolean getAndClearInterrupt() {
        boolean oldValue = interrupted;
        // We may have been interrupted the moment after we read the field,
        // so only clear the field if we saw that it was set and will return
        // true; otherwise we could lose an interrupt.
        if (oldValue) {
            interrupted = false;
            clearInterruptEvent();
        }
        return oldValue;
    }

    /**
     * Tests if this thread is alive. A thread is alive if it has
     * been started and has not yet terminated.
     *
     * @return  {@code true} if this thread is alive;
     *          {@code false} otherwise.
     */
    public final boolean isAlive() {
        if (isVirtual()) {
            State state = getState();
            return (state != State.NEW && state != State.TERMINATED);
        } else {
            return isAlive0();
        }
    }
    private native boolean isAlive0();

    /**
     * Suspends this thread.
     * <p>
     * First, the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a
     * {@code SecurityException }(in the current thread).
     * <p>
     * If the thread is alive, it is suspended and makes no further
     * progress unless and until it is resumed.
     *
     * @throws     SecurityException  if the current thread cannot modify
     *             this thread.
     * @throws     UnsupportedOperationException if invoked on a virtual thread
     * @see #checkAccess
     * @deprecated   This method has been deprecated, as it is
     *   inherently deadlock-prone.  If the task thread holds a lock on the
     *   monitor protecting a critical system resource when it is suspended, no
     *   thread can access this resource until the task thread is resumed. If
     *   the thread that would resume the task thread attempts to lock this
     *   monitor prior to calling {@code resume}, deadlock results.  Such
     *   deadlocks typically manifest themselves as "frozen" processes.
     *   For more information, see
     *   <a href="{@docRoot}/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html">Why
     *   are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void suspend() {
        checkAccess();
        if (isVirtual())
            throw new UnsupportedOperationException();
        suspend0();
    }

    void suspendThread() {
        suspend0();
    }

    /**
     * Resumes a suspended thread.
     * <p>
     * First, the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a
     * {@code SecurityException} (in the current thread).
     * <p>
     * If the thread is alive but suspended, it is resumed and is
     * permitted to make progress in its execution.
     *
     * @throws     SecurityException  if the current thread cannot modify this
     *             thread.
     * @throws     UnsupportedOperationException if invoked on a virtual thread
     * @see        #checkAccess
     * @see        #suspend()
     * @deprecated This method exists solely for use with {@link #suspend},
     *     which has been deprecated because it is deadlock-prone.
     *     For more information, see
     *     <a href="{@docRoot}/java.base/java/lang/doc-files/threadPrimitiveDeprecation.html">Why
     *     are Thread.stop, Thread.suspend and Thread.resume Deprecated?</a>.
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void resume() {
        checkAccess();
        if (isVirtual())
            throw new UnsupportedOperationException();
        resume0();
    }

    void resumeThread() {
        resume0();
    }

    /**
     * Changes the priority of this thread.
     * <p>
     * First the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a {@code SecurityException}.
     * <p>
     * The priority of virtual-threads is always {@linkplain Thread#NORM_PRIORITY}
     * and is not changed by this method.
     * Otherwise, the priority of this thread is set to the smaller of
     * the specified {@code newPriority} and the maximum permitted
     * priority of the thread's thread group.
     *
     * @param newPriority priority to set this thread to
     * @throws     IllegalArgumentException  If the priority is not in the
     *               range {@code MIN_PRIORITY} to
     *               {@code MAX_PRIORITY}.
     * @throws     SecurityException  if the current thread cannot modify
     *               this thread.
     * @see        #getPriority
     * @see        #checkAccess()
     * @see        #getThreadGroup()
     * @see        #MAX_PRIORITY
     * @see        #MIN_PRIORITY
     * @see        ThreadGroup#getMaxPriority()
     */
    public final void setPriority(int newPriority) {
        if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
            throw new IllegalArgumentException();
        }
        checkAccess();
        priority(newPriority);
    }

    void priority(int newPriority) {
        ThreadGroup g;
        if (!isVirtual() && (g = getThreadGroup()) != null) {
            if (newPriority > g.getMaxPriority()) {
                newPriority = g.getMaxPriority();
            }
            setPriority0(holder.priority = newPriority);
        }
    }

    /**
     * Returns this thread's priority.
     * The priority of a virtual thread is always {@linkplain Thread#NORM_PRIORITY}.
     *
     * @return  this thread's priority.
     * @see     #setPriority
     */
    public final int getPriority() {
        if (isVirtual()) {
            return Thread.NORM_PRIORITY;
        } else {
            return holder.priority;
        }
    }

    /**
     * Changes the name of this thread to be equal to the argument {@code name}.
     * <p>
     * First the {@code checkAccess} method of this thread is called
     * with no arguments. This may result in throwing a
     * {@code SecurityException}.
     *
     * @param      name   the new name for this thread.
     * @throws     SecurityException  if the current thread cannot modify this
     *             thread.
     * @see        #getName
     * @see        #checkAccess()
     */
    public final synchronized void setName(String name) {
        checkAccess();
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }

        this.name = name;
        if (!isVirtual() && holder.threadStatus != 0) {
            setNativeName(name);
        }
    }

    /**
     * Returns this thread's name.
     *
     * @return  this thread's name.
     * @see     #setName(String)
     */
    public final String getName() {
        return name;
    }

    /**
     * Returns the thread group to which this thread belongs.
     * This method returns null if the thread has terminated.
     *
     * <p> The thread group for virtual threads does not support all features
     * of regular thread groups. Virtual threads are not considered <i>active
     * threads</i> in the thread group and so are not enumerated or acted on by
     * thread group operations.
     *
     * @return  this thread's thread group.
     */
    public final ThreadGroup getThreadGroup() {
        if (getState() == State.TERMINATED) {
            return null;
        } else {
            return isVirtual() ? VirtualThreads.THREAD_GROUP : holder.group;
        }
    }

    /**
     * Returns an estimate of the number of active threads in the current
     * thread's {@linkplain java.lang.ThreadGroup thread group} and its
     * subgroups. Recursively iterates over all subgroups in the current
     * thread's thread group.
     *
     * <p> The value returned is only an estimate because the number of
     * threads may change dynamically while this method traverses internal
     * data structures, and might be affected by the presence of certain
     * system threads. This method is intended primarily for debugging
     * and monitoring purposes.
     *
     * @return  an estimate of the number of active threads in the current
     *          thread's thread group and in any other thread group that
     *          has the current thread's thread group as an ancestor
     */
    public static int activeCount() {
        return currentThread().getThreadGroup().activeCount();
    }

    /**
     * Copies into the specified array every active thread in the current
     * thread's thread group and its subgroups. This method simply
     * invokes the {@link java.lang.ThreadGroup#enumerate(Thread[])}
     * method of the current thread's thread group.
     *
     * <p> An application might use the {@linkplain #activeCount activeCount}
     * method to get an estimate of how big the array should be, however
     * <i>if the array is too short to hold all the threads, the extra threads
     * are silently ignored.</i>  If it is critical to obtain every active
     * thread in the current thread's thread group and its subgroups, the
     * invoker should verify that the returned int value is strictly less
     * than the length of {@code tarray}.
     *
     * <p> Due to the inherent race condition in this method, it is recommended
     * that the method only be used for debugging and monitoring purposes.
     *
     * @param  tarray
     *         an array into which to put the list of threads
     *
     * @return  the number of threads put into the array
     *
     * @throws  SecurityException
     *          if {@link java.lang.ThreadGroup#checkAccess} determines that
     *          the current thread cannot access its thread group
     */
    public static int enumerate(Thread tarray[]) {
        return currentThread().getThreadGroup().enumerate(tarray);
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @return     nothing
     *
     * @deprecated This method was originally designed to count the number of
     *             stack frames but the results were never well-defined and it
     *             depended on thread-suspension.
     *             This method is subject to removal in a future version of Java SE.
     * @see        StackWalker
     */
    @Deprecated(since="1.2", forRemoval=true)
    public int countStackFrames() {
        throw new UnsupportedOperationException();
    }

    /**
     * Waits at most {@code millis} milliseconds for this thread to terminate.
     * A timeout of {@code 0} means to wait forever.
     * This method returns immediately, without waiting, if the thread has not
     * been {@link #start() started}.
     *
     * <p> For non-{@linkplain #isVirtual() virtual}, this
     * implementation uses a loop of {@code this.wait} calls
     * conditioned on {@code this.isAlive}. As a thread terminates the
     * {@code this.notifyAll} method is invoked. It is recommended that
     * applications not use {@code wait}, {@code notify}, or
     * {@code notifyAll} on {@code Thread} instances.
     *
     * @param  millis
     *         the time to wait in milliseconds
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public final void join(long millis) throws InterruptedException {
        if (millis < 0)
            throw new IllegalArgumentException("timeout value is negative");

        if (isVirtual()) {
            if (isAlive()) {
                long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
                ((VirtualThread) this).joinNanos(nanos);
            }
            return;
        }

        synchronized (this) {
            if (millis > 0) {
                if (isAlive()) {
                    final long startTime = System.nanoTime();
                    long delay = millis;
                    do {
                        wait(delay);
                    } while (isAlive() && (delay = millis -
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)) > 0);
                }
            } else if (millis == 0) {
                while (isAlive()) {
                    wait(0);
                }
            }
        }
    }

    /**
     * Waits at most {@code millis} milliseconds plus
     * {@code nanos} nanoseconds for this thread to terminate.
     * If both arguments are {@code 0}, it means to wait forever.
     * This method returns immediately, without waiting, if the thread has not
     * been {@link #start() started}.
     *
     * <p> For non-{@link #isVirtual() virtual}, this
     * implementation uses a loop of {@code this.wait} calls
     * <p> This implementation uses a loop of {@code this.wait} calls
     * conditioned on {@code this.isAlive}. As a thread terminates the
     * {@code this.notifyAll} method is invoked. It is recommended that
     * applications not use {@code wait}, {@code notify}, or
     * {@code notifyAll} on {@code Thread} instances.
     *
     * @param  millis
     *         the time to wait in milliseconds
     *
     * @param  nanos
     *         {@code 0-999999} additional nanoseconds to wait
     *
     * @throws  IllegalArgumentException
     *          if the value of {@code millis} is negative, or the value
     *          of {@code nanos} is not in the range {@code 0-999999}
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public final void join(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }

        if (nanos > 0 && millis < Long.MAX_VALUE) {
            millis++;
        }

        join(millis);
    }

    /**
     * Waits for this thread to terminate.
     *
     * <p> An invocation of this method behaves in exactly the same
     * way as the invocation
     *
     * <blockquote>
     * {@linkplain #join(long) join}{@code (0)}
     * </blockquote>
     *
     * @throws  InterruptedException
     *          if any thread has interrupted the current thread. The
     *          <i>interrupted status</i> of the current thread is
     *          cleared when this exception is thrown.
     */
    public final void join() throws InterruptedException {
        join(0);
    }

    /**
     * Waits for this thread to terminate for up to the given waiting duration.
     * This method does not wait if the duration to wait is less than or equal
     * to zero.
     *
     * @param   duration
     *          the maximum duration to wait
     *
     * @return  {@code true} if the thread has terminated
     *
     * @throws  InterruptedException
     *          if the current thread is interrupted while waiting.
     *          The <i>interrupted status</i> of the current thread is cleared
     *          when this exception is thrown.
     *
     * @throws  IllegalThreadStateException
     *          if this thread has not been started.
     *
     * @since 99
     */
    public final boolean join(Duration duration) throws InterruptedException {
        Objects.requireNonNull(duration);

        Thread.State state = getState();
        if (state == State.TERMINATED)
            return true;
        if (state == State.NEW)
            throw new IllegalThreadStateException("Thread not started");
        if (duration.isZero() || duration.isNegative())
            return false;

        if (isVirtual()) {
            long nanos = TimeUnit.NANOSECONDS.convert(duration);
            return ((VirtualThread) this).joinNanos(nanos);
        } else {
            // ignore nano precision for now
            long millis = Long.max(TimeUnit.MILLISECONDS.convert(duration), 1);
            join(millis);
            return getState() == State.TERMINATED;
        }
    }

    /**
     * Prints a stack trace of the current thread to the standard error stream.
     * This method is useful for debugging.
     */
    public static void dumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    /**
     * Marks this thread as either a {@linkplain #isDaemon daemon} thread
     * or a user thread.
     * The daemon status of a virtual thread is meaningless and is not
     * changed by this method (the {@linkplain #isDaemon() isDaemon} method
     * always returns {@code true}).
     * The Java Virtual Machine exits when the only threads running are all
     * daemon threads.
     *
     * <p> This method must be invoked before the thread is started.
     *
     * @param  on
     *         if {@code true}, marks this thread as a daemon thread
     *
     * @throws  IllegalThreadStateException
     *          if this thread is {@linkplain #isAlive alive}
     *
     * @throws  SecurityException
     *          if {@link #checkAccess} determines that the current
     *          thread cannot modify this thread
     */
    public final void setDaemon(boolean on) {
        checkAccess();
        if (isAlive())
            throw new IllegalThreadStateException();
        daemon(on);
    }

    void daemon(boolean on) {
        if (!isVirtual())
            holder.daemon = on;
    }

    /**
     * Tests if this thread is a daemon thread.
     * The daemon status of a virtual thread is meaningless, this method
     * returns {@code true} if this is a virtual thread.
     *
     * @return  {@code true} if this thread is a daemon thread;
     *          {@code false} otherwise.
     * @see     #setDaemon(boolean)
     */
    public final boolean isDaemon() {
        if (isVirtual()) {
            return true;
        } else {
            return holder.daemon;
        }
    }

    /**
     * Determines if the currently running thread has permission to
     * modify this thread.
     * <p>
     * If there is a security manager, its {@code checkAccess} method
     * is called with this thread as its argument. This may result in
     * throwing a {@code SecurityException}.
     *
     * @throws  SecurityException  if the current thread is not allowed to
     *          access this thread.
     * @see        SecurityManager#checkAccess(Thread)
     */
    public final void checkAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkAccess(this);
        }
    }

    /**
     * Returns a string representation of this thread. The string representation
     * will usually include the thread's name. The default implementation
     * includes the thread's name, priority, and the name of the thread group.
     *
     * @return  a string representation of this thread.
     */
    public String toString() {
        ThreadGroup group = getThreadGroup();
        if (group != null) {
            return "Thread[" + getName() + "," + getPriority() + "," +
                           group.getName() + "]";
        } else {
            return "Thread[" + getName() + "," + getPriority() + "," +
                            "" + "]";
        }
    }

    /**
     * Returns the context {@code ClassLoader} for this thread. The context
     * {@code ClassLoader} is provided by the creator of the thread for use
     * by code running in this thread when loading classes and resources.
     * If not {@linkplain #setContextClassLoader set}, the default is the
     * {@code ClassLoader} context of the parent thread. The context
     * {@code ClassLoader} of the
     * primordial thread is typically set to the class loader used to load the
     * application.
     *
     *
     * @return  the context {@code ClassLoader} for this thread, or {@code null}
     *          indicating the system class loader (or, failing that, the
     *          bootstrap class loader)
     *
     * @throws  SecurityException
     *          if a security manager is present, and the caller's class loader
     *          is not {@code null} and is not the same as or an ancestor of the
     *          context class loader, and the caller does not have the
     *          {@link RuntimePermission}{@code ("getClassLoader")}
     *
     * @since 1.2
     */
    @CallerSensitive
    public ClassLoader getContextClassLoader() {
        if (contextClassLoader == null)
            return null;
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            ClassLoader.checkClassLoaderPermission(contextClassLoader,
                                                   Reflection.getCallerClass());
        }
        return contextClassLoader;
    }

    /**
     * TBD
     */
    Continuation getContinuation() {
        return cont;
    }

    /**
     * TBD
     */
    void setContinuation(Continuation cont) {
        this.cont = cont;
    }

    /**
     * Sets the context ClassLoader for this Thread. The context
     * ClassLoader can be set when a thread is created, and allows
     * the creator of the thread to provide the appropriate class loader,
     * through {@code getContextClassLoader}, to code running in the thread
     * when loading classes and resources.
     *
     * <p>If a security manager is present, its {@link
     * SecurityManager#checkPermission(java.security.Permission) checkPermission}
     * method is invoked with a {@link RuntimePermission RuntimePermission}{@code
     * ("setContextClassLoader")} permission to see if setting the context
     * ClassLoader is permitted.
     *
     * @param  cl
     *         the context ClassLoader for this Thread, or null  indicating the
     *         system class loader (or, failing that, the bootstrap class loader)
     *
     * @throws  SecurityException
     *          if the current thread cannot set the context ClassLoader
     *
     * @since 1.2
     */
    public void setContextClassLoader(ClassLoader cl) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("setContextClassLoader"));
        }
        contextClassLoader = cl;
    }

    /**
     * Returns {@code true} if and only if the current thread holds the
     * monitor lock on the specified object.
     *
     * <p>This method is designed to allow a program to assert that
     * the current thread already holds a specified lock:
     * <pre>
     *     assert Thread.holdsLock(obj);
     * </pre>
     *
     * @param  obj the object on which to test lock ownership
     * @throws NullPointerException if obj is {@code null}
     * @return {@code true} if the current thread holds the monitor lock on
     *         the specified object.
     * @since 1.4
     */
    public static native boolean holdsLock(Object obj);

    private static final StackTraceElement[] EMPTY_STACK_TRACE
        = new StackTraceElement[0];

    /**
     * Returns an array of stack trace elements representing the stack dump
     * of this thread.  This method will return a zero-length array if
     * this thread has not started, has started but has not yet been
     * scheduled to run by the system, or has terminated.
     * If the returned array is of non-zero length then the first element of
     * the array represents the top of the stack, which is the most recent
     * method invocation in the sequence.  The last element of the array
     * represents the bottom of the stack, which is the least recent method
     * invocation in the sequence.
     *
     * <p>If there is a security manager, and this thread is not
     * the current thread, then the security manager's
     * {@code checkPermission} method is called with a
     * {@code RuntimePermission("getStackTrace")} permission
     * to see if it's ok to get the stack trace.
     *
     * <p>Some virtual machines may, under some circumstances, omit one
     * or more stack frames from the stack trace.  In the extreme case,
     * a virtual machine that has no stack trace information concerning
     * this thread is permitted to return a zero-length array from this
     * method.
     *
     * @return an array of {@code StackTraceElement},
     * each represents one stack frame.
     *
     * @throws SecurityException
     *        if a security manager exists and its
     *        {@code checkPermission} method doesn't allow
     *        getting the stack trace of thread.
     * @see SecurityManager#checkPermission
     * @see RuntimePermission
     * @see Throwable#getStackTrace
     *
     * @since 1.5
     */
    public StackTraceElement[] getStackTrace() {
        if (this != Thread.currentThread()) {
            // check for getStackTrace permission
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(
                    SecurityConstants.GET_STACK_TRACE_PERMISSION);
            }
            // optimization so we do not call into the vm for threads that
            // have not yet started or have terminated
            if (!isAlive()) {
                return EMPTY_STACK_TRACE;
            }
            StackTraceElement[][] stackTraceArray = dumpThreads(new Thread[] {this});
            StackTraceElement[] stackTrace = stackTraceArray[0];
            // a thread that was alive during the previous isAlive call may have
            // since terminated, therefore not having a stacktrace.
            if (stackTrace == null) {
                stackTrace = EMPTY_STACK_TRACE;
            }
            return stackTrace;
        } else {
            return (new Exception()).getStackTrace();
        }
    }

    /**
     * Returns a map of stack traces for all live threads. The map does not
     * include virtual threads.
     * The map keys are threads and each map value is an array of
     * {@code StackTraceElement} that represents the stack dump
     * of the corresponding {@code Thread}.
     * The returned stack traces are in the format specified for
     * the {@link #getStackTrace getStackTrace} method.
     *
     * <p>The threads may be executing while this method is called.
     * The stack trace of each thread only represents a snapshot and
     * each stack trace may be obtained at different time.  A zero-length
     * array will be returned in the map value if the virtual machine has
     * no stack trace information about a thread.
     *
     * <p>If there is a security manager, then the security manager's
     * {@code checkPermission} method is called with a
     * {@code RuntimePermission("getStackTrace")} permission as well as
     * {@code RuntimePermission("modifyThreadGroup")} permission
     * to see if it is ok to get the stack trace of all threads.
     *
     * @return a {@code Map} from {@code Thread} to an array of
     * {@code StackTraceElement} that represents the stack trace of
     * the corresponding thread.
     *
     * @throws SecurityException
     *        if a security manager exists and its
     *        {@code checkPermission} method doesn't allow
     *        getting the stack trace of thread.
     * @see #getStackTrace
     * @see SecurityManager#checkPermission
     * @see RuntimePermission
     * @see Throwable#getStackTrace
     *
     * @since 1.5
     */
    public static Map<Thread, StackTraceElement[]> getAllStackTraces() {
        // check for getStackTrace permission
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(
                SecurityConstants.GET_STACK_TRACE_PERMISSION);
            security.checkPermission(
                SecurityConstants.MODIFY_THREADGROUP_PERMISSION);
        }

        // Get a snapshot of the list of all threads
        Thread[] threads = getThreads();
        StackTraceElement[][] traces = dumpThreads(threads);
        Map<Thread, StackTraceElement[]> m = new HashMap<>(threads.length);
        for (int i = 0; i < threads.length; i++) {
            StackTraceElement[] stackTrace = traces[i];
            if (stackTrace != null) {
                m.put(threads[i], stackTrace);
            }
            // else terminated so we don't put it in the map
        }
        return m;
    }

    /** cache of subclass security audit results */
    /* Replace with ConcurrentReferenceHashMap when/if it appears in a future
     * release */
    private static class Caches {
        /** cache of subclass security audit results */
        static final ConcurrentMap<WeakClassKey,Boolean> subclassAudits =
            new ConcurrentHashMap<>();

        /** queue for WeakReferences to audited subclasses */
        static final ReferenceQueue<Class<?>> subclassAuditsQueue =
            new ReferenceQueue<>();
    }

    /**
     * Verifies that this (possibly subclass) instance can be constructed
     * without violating security constraints: the subclass must not override
     * security-sensitive non-final methods, or else the
     * "enableContextClassLoaderOverride" RuntimePermission is checked.
     */
    private static boolean isCCLOverridden(Class<?> cl) {
        if (cl == Thread.class)
            return false;

        processQueue(Caches.subclassAuditsQueue, Caches.subclassAudits);
        WeakClassKey key = new WeakClassKey(cl, Caches.subclassAuditsQueue);
        Boolean result = Caches.subclassAudits.get(key);
        if (result == null) {
            result = Boolean.valueOf(auditSubclass(cl));
            Caches.subclassAudits.putIfAbsent(key, result);
        }

        return result.booleanValue();
    }

    /**
     * Performs reflective checks on given subclass to verify that it doesn't
     * override security-sensitive non-final methods.  Returns true if the
     * subclass overrides any of the methods, false otherwise.
     */
    private static boolean auditSubclass(final Class<?> subcl) {
        Boolean result = AccessController.doPrivileged(
            new PrivilegedAction<>() {
                public Boolean run() {
                    for (Class<?> cl = subcl;
                         cl != Thread.class;
                         cl = cl.getSuperclass())
                    {
                        try {
                            cl.getDeclaredMethod("getContextClassLoader", new Class<?>[0]);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                        try {
                            Class<?>[] params = {ClassLoader.class};
                            cl.getDeclaredMethod("setContextClassLoader", params);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                    }
                    return Boolean.FALSE;
                }
            }
        );
        return result.booleanValue();
    }

    private static native StackTraceElement[][] dumpThreads(Thread[] threads);
    private static native Thread[] getThreads();

    /**
     * Returns the identifier of this Thread.  The thread ID is a positive
     * {@code long} number generated when this thread was created.
     * The thread ID is unique and remains unchanged during its lifetime.
     * When a thread is terminated, this thread ID may be reused.
     *
     * @return this thread's ID.
     * @since 1.5
     */
    public long getId() {
        return tid;
    }

    /**
     * A thread state.  A thread can be in one of the following states:
     * <ul>
     * <li>{@link #NEW}<br>
     *     A thread that has not yet started is in this state.
     *     </li>
     * <li>{@link #RUNNABLE}<br>
     *     A thread executing in the Java virtual machine is in this state.
     *     </li>
     * <li>{@link #BLOCKED}<br>
     *     A thread that is blocked waiting for a monitor lock
     *     is in this state.
     *     </li>
     * <li>{@link #WAITING}<br>
     *     A thread that is waiting indefinitely for another thread to
     *     perform a particular action is in this state.
     *     </li>
     * <li>{@link #TIMED_WAITING}<br>
     *     A thread that is waiting for another thread to perform an action
     *     for up to a specified waiting time is in this state.
     *     </li>
     * <li>{@link #TERMINATED}<br>
     *     A thread that has exited is in this state.
     *     </li>
     * </ul>
     *
     * <p>
     * A thread can be in only one state at a given point in time.
     * These states are virtual machine states which do not reflect
     * any operating system thread states.
     *
     * @since   1.5
     * @see #getState
     */
    public enum State {
        /**
         * Thread state for a thread which has not yet started.
         */
        NEW,

        /**
         * Thread state for a runnable thread.  A thread in the runnable
         * state is executing in the Java virtual machine but it may
         * be waiting for other resources from the operating system
         * such as processor.
         */
        RUNNABLE,

        /**
         * Thread state for a thread blocked waiting for a monitor lock.
         * A thread in the blocked state is waiting for a monitor lock
         * to enter a synchronized block/method or
         * reenter a synchronized block/method after calling
         * {@link Object#wait() Object.wait}.
         */
        BLOCKED,

        /**
         * Thread state for a waiting thread.
         * A thread is in the waiting state due to calling one of the
         * following methods:
         * <ul>
         *   <li>{@link Object#wait() Object.wait} with no timeout</li>
         *   <li>{@link #join() Thread.join} with no timeout</li>
         *   <li>{@link LockSupport#park() LockSupport.park}</li>
         * </ul>
         *
         * <p>A thread in the waiting state is waiting for another thread to
         * perform a particular action.
         *
         * For example, a thread that has called {@code Object.wait()}
         * on an object is waiting for another thread to call
         * {@code Object.notify()} or {@code Object.notifyAll()} on
         * that object. A thread that has called {@code Thread.join()}
         * is waiting for a specified thread to terminate.
         */
        WAITING,

        /**
         * Thread state for a waiting thread with a specified waiting time.
         * A thread is in the timed waiting state due to calling one of
         * the following methods with a specified positive waiting time:
         * <ul>
         *   <li>{@link #sleep Thread.sleep}</li>
         *   <li>{@link Object#wait(long) Object.wait} with timeout</li>
         *   <li>{@link #join(long) Thread.join} with timeout</li>
         *   <li>{@link LockSupport#parkNanos LockSupport.parkNanos}</li>
         *   <li>{@link LockSupport#parkUntil LockSupport.parkUntil}</li>
         * </ul>
         */
        TIMED_WAITING,

        /**
         * Thread state for a terminated thread.
         * The thread has completed execution.
         */
        TERMINATED;
    }

    /**
     * Returns the state of this thread.
     * This method is designed for use in monitoring of the system state,
     * not for synchronization control.
     *
     * @return this thread's state.
     * @since 1.5
     */
    public State getState() {
        return threadState();
    }

    /**
     * Returns the state of this thread.
     *
     * @apiNote For VirtualThread use as getState may be overridden and run
     * arbitrary code.
     */
    State threadState() {
        return jdk.internal.misc.VM.toThreadState(holder.threadStatus);
    }

    // Added in JSR-166

    /**
     * Interface for handlers invoked when a {@code Thread} abruptly
     * terminates due to an uncaught exception.
     * <p>When a thread is about to terminate due to an uncaught exception
     * the Java Virtual Machine will query the thread for its
     * {@code UncaughtExceptionHandler} using
     * {@link #getUncaughtExceptionHandler} and will invoke the handler's
     * {@code uncaughtException} method, passing the thread and the
     * exception as arguments.
     * If a thread has not had its {@code UncaughtExceptionHandler}
     * explicitly set, then its {@code ThreadGroup} object acts as its
     * {@code UncaughtExceptionHandler}. If the {@code ThreadGroup} object
     * has no
     * special requirements for dealing with the exception, it can forward
     * the invocation to the {@linkplain #getDefaultUncaughtExceptionHandler
     * default uncaught exception handler}.
     *
     * @see #setDefaultUncaughtExceptionHandler
     * @see #setUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    @FunctionalInterface
    public interface UncaughtExceptionHandler {
        /**
         * Method invoked when the given thread terminates due to the
         * given uncaught exception.
         * <p>Any exception thrown by this method will be ignored by the
         * Java Virtual Machine.
         * @param t the thread
         * @param e the exception
         */
        void uncaughtException(Thread t, Throwable e);
    }

    // null unless explicitly set
    private volatile UncaughtExceptionHandler uncaughtExceptionHandler;

    // null unless explicitly set
    private static volatile UncaughtExceptionHandler defaultUncaughtExceptionHandler;

    /**
     * Set the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception, and no other handler has been defined
     * for that thread.
     *
     * <p>Uncaught exception handling is controlled first by the thread, then
     * by the thread's {@link ThreadGroup} object and finally by the default
     * uncaught exception handler. If the thread does not have an explicit
     * uncaught exception handler set, and the thread's thread group
     * (including parent thread groups)  does not specialize its
     * {@code uncaughtException} method, then the default handler's
     * {@code uncaughtException} method will be invoked.
     * <p>By setting the default uncaught exception handler, an application
     * can change the way in which uncaught exceptions are handled (such as
     * logging to a specific device, or file) for those threads that would
     * already accept whatever &quot;default&quot; behavior the system
     * provided.
     *
     * <p>Note that the default uncaught exception handler should not usually
     * defer to the thread's {@code ThreadGroup} object, as that could cause
     * infinite recursion.
     *
     * @param eh the object to use as the default uncaught exception handler.
     * If {@code null} then there is no default handler.
     *
     * @throws SecurityException if a security manager is present and it denies
     *         {@link RuntimePermission}{@code ("setDefaultUncaughtExceptionHandler")}
     *
     * @see #setUncaughtExceptionHandler
     * @see #getUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(
                new RuntimePermission("setDefaultUncaughtExceptionHandler")
                    );
        }

         defaultUncaughtExceptionHandler = eh;
     }

    /**
     * Returns the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception. If the returned value is {@code null},
     * there is no default.
     * @since 1.5
     * @see #setDefaultUncaughtExceptionHandler
     * @return the default uncaught exception handler for all threads
     */
    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler(){
        return defaultUncaughtExceptionHandler;
    }

    /**
     * Returns the handler invoked when this thread abruptly terminates
     * due to an uncaught exception. If this thread has not had an
     * uncaught exception handler explicitly set then this thread's
     * {@code ThreadGroup} object is returned, unless this thread
     * has terminated, in which case {@code null} is returned.
     * @since 1.5
     * @return the uncaught exception handler for this thread
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler != null ?
            uncaughtExceptionHandler : getThreadGroup();
    }

    /**
     * Set the handler invoked when this thread abruptly terminates
     * due to an uncaught exception.
     * <p>A thread can take full control of how it responds to uncaught
     * exceptions by having its uncaught exception handler explicitly set.
     * If no such handler is set then the thread's {@code ThreadGroup}
     * object acts as its handler.
     * @param eh the object to use as this thread's uncaught exception
     * handler. If {@code null} then this thread has no explicit handler.
     * @throws  SecurityException  if the current thread is not allowed to
     *          modify this thread.
     * @see #setDefaultUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        checkAccess();
        uncaughtExceptionHandler(eh);
    }

    private void uncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        uncaughtExceptionHandler = eh;
    }

    /**
     * Dispatch an uncaught exception to the handler. This method is
     * called when a thread terminates with an exception.
     */
    void dispatchUncaughtException(Throwable e) {
        getUncaughtExceptionHandler().uncaughtException(this, e);
    }

    /**
     * Removes from the specified map any keys that have been enqueued
     * on the specified reference queue.
     */
    static void processQueue(ReferenceQueue<Class<?>> queue,
                             ConcurrentMap<? extends
                             WeakReference<Class<?>>, ?> map)
    {
        Reference<? extends Class<?>> ref;
        while((ref = queue.poll()) != null) {
            map.remove(ref);
        }
    }

    /**
     *  Weak key for Class objects.
     **/
    static class WeakClassKey extends WeakReference<Class<?>> {
        /**
         * saved value of the referent's identity hash code, to maintain
         * a consistent hash code after the referent has been cleared
         */
        private final int hash;

        /**
         * Create a new WeakClassKey to the given object, registered
         * with a queue.
         */
        WeakClassKey(Class<?> cl, ReferenceQueue<Class<?>> refQueue) {
            super(cl, refQueue);
            hash = System.identityHashCode(cl);
        }

        /**
         * Returns the identity hash code of the original referent.
         */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * Returns true if the given object is this identical
         * WeakClassKey instance, or, if this object's referent has not
         * been cleared, if the given object is another WeakClassKey
         * instance with the identical non-null referent as this one.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (obj instanceof WeakClassKey) {
                Object referent = get();
                return (referent != null) &&
                       (referent == ((WeakClassKey) obj).get());
            } else {
                return false;
            }
        }
    }

    private static class VirtualThreads {
        static final ThreadGroup THREAD_GROUP = threadGroup();
        static final AccessControlContext ACCESS_CONTROL_CONTEXT = accessControlContext();

        /**
         * The thread group for virtual threads.
         */
        private static ThreadGroup threadGroup() {
            return AccessController.doPrivileged(new PrivilegedAction<ThreadGroup>() {
                public ThreadGroup run() {
                    ThreadGroup group = Thread.currentCarrierThread().getThreadGroup();
                    for (ThreadGroup p; (p = group.getParent()) != null; )
                        group = p;
                    var newGroup = new ThreadGroup(group, "VirtualThreads", true);
                    newGroup.setDaemon(true);
                    return newGroup;
                }});
        }

        /**
         * Return an AccessControlContext that doesn't support any permissions.
         */
        private static AccessControlContext accessControlContext() {
            return new AccessControlContext(new ProtectionDomain[] {
                new ProtectionDomain(null, null)
            });
        }
    }

    // The following three initially uninitialized fields are exclusively
    // managed by class java.util.concurrent.ThreadLocalRandom. These
    // fields are used to build the high-performance PRNGs in the
    // concurrent code. Upcoming changes in the concurrent code avoid
    // needing to use @Contented here.

    /** The current seed for a ThreadLocalRandom */
    long threadLocalRandomSeed;

    /** Probe hash value; nonzero if threadLocalRandomSeed initialized */
    int threadLocalRandomProbe;

    /** Secondary seed isolated from public ThreadLocalRandom sequence */
    int threadLocalRandomSecondarySeed;

    /**
     * TBD
     */
    public Object userObject;

    /* Some private helper methods */
    private native void setPriority0(int newPriority);
    private native void stop0(Object o);
    private native void suspend0();
    private native void resume0();
    private native void interrupt0();
    private static native void clearInterruptEvent();
    private native void setNativeName(String name);
}

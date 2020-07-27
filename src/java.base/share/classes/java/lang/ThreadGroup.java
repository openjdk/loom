/*
 * Copyright (c) 1995, 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.HashSet;
import java.util.Set;

/**
 * A thread group represents a set of threads. In addition, a thread
 * group can also include other thread groups. The thread groups form
 * a tree in which every thread group except the initial thread group
 * has a parent. A thread group is considered <i>active</i> if there
 * are any {@linkplain Thread#isAlive() alive} threads in the group or
 * any of its subgroups.
 * <p>
 * A thread is allowed to access information about its own thread
 * group, but not to access information about its thread group's
 * parent thread group or any other thread groups.
 *
 * @since   1.0
 */

public class ThreadGroup implements Thread.UncaughtExceptionHandler {
    private final ThreadGroup parent;
    private final String name;
    private volatile int maxPriority;

    /**
     * Creates an empty Thread group that is not in any Thread group.
     * This method is used to create the system Thread group.
     */
    private ThreadGroup() {     // called during VM initialization
        this.parent = null;
        this.name = "system";
        this.maxPriority = Thread.MAX_PRIORITY;
    }

    /**
     * Creates a new thread group without a permission check.
     */
    ThreadGroup(ThreadGroup parent, String name, int maxPriority) {
        this.parent = parent;
        this.name = name;
        this.maxPriority = maxPriority;
    }

    /**
     * Constructs a new thread group. The parent of this new group is
     * the thread group of the currently running thread.
     * <p>
     * The {@code checkAccess} method of the parent thread group is
     * called with no arguments; this may result in a security exception.
     *
     * @param   name   the name of the new thread group, can be {@code null}
     * @throws  SecurityException  if the current thread cannot create a
     *               thread in the specified thread group.
     * @see     java.lang.ThreadGroup#checkAccess()
     * @since   1.0
     */
    public ThreadGroup(String name) {
        this(Thread.currentThread().getThreadGroup(), name);
    }

    /**
     * Creates a new thread group. The parent of this new group is the
     * specified thread group.
     * <p>
     * The {@code checkAccess} method of the parent thread group is
     * called with no arguments; this may result in a security exception.
     *
     * @param     parent   the parent thread group.
     * @param     name     the name of the new thread group, can be {@code null}
     * @throws    NullPointerException  if the thread group argument is
     *               {@code null}.
     * @throws    SecurityException  if the current thread cannot create a
     *               thread in the specified thread group.
     * @see     java.lang.SecurityException
     * @see     java.lang.ThreadGroup#checkAccess()
     * @since   1.0
     */
    public ThreadGroup(ThreadGroup parent, String name) {
        this(checkParentAccess(parent), parent, name);
    }

    private ThreadGroup(Void unused, ThreadGroup parent, String name) {
        this.parent = parent;
        this.name = name;
        this.maxPriority = parent.getMaxPriority();
    }

    /*
     * @throws  NullPointerException  if the parent argument is {@code null}
     * @throws  SecurityException     if the current thread cannot create a
     *                                thread in the specified thread group.
     */
    private static Void checkParentAccess(ThreadGroup parent) {
        parent.checkAccess();
        return null;
    }

    /**
     * Returns the name of this thread group.
     *
     * @return  the name of this thread group.
     * @since   1.0
     */
    public final String getName() {
        return name;
    }

    /**
     * Returns the parent of this thread group.
     * <p>
     * First, if the parent is not {@code null}, the
     * {@code checkAccess} method of the parent thread group is
     * called with no arguments; this may result in a security exception.
     *
     * @return  the parent of this thread group. The top-level thread group
     *          is the only thread group whose parent is {@code null}.
     * @throws  SecurityException  if the current thread cannot modify
     *               this thread group.
     * @see        java.lang.ThreadGroup#checkAccess()
     * @see        java.lang.SecurityException
     * @see        java.lang.RuntimePermission
     * @since   1.0
     */
    public final ThreadGroup getParent() {
        if (parent != null)
            parent.checkAccess();
        return parent;
    }

    /**
     * Returns the <i>effective</i> maximum priority of this thread group.
     * This is the maximum priority for new threads created in the group.
     *
     * <p> The <i>effective</i> maximum priority of the group is the smaller of
     * this group's maximum priority and the <i>effective</i> maximum priority
     * of the parent of this thread group. If this thread group is the system
     * thread group, which has no parent, then the effective maximum priority
     * is the group's maximum priority.
     *
     * @return  the effective maximum priority of this thread group, the
     *          maximum priority for new threads created in the group
     * @see     #setMaxPriority
     * @since   1.0
     */
    public final int getMaxPriority() {
        int priority = this.maxPriority;
        ThreadGroup g = parent;
        while (g != null) {
            priority = Math.min(priority, g.maxPriority);
            g = g.parent;
        }
        return priority;
    }

    /**
     * Returns false.
     *
     * @return false
     *
     * @deprecated This method originally indicated if the thread group
     *             was automatically destroyed when the last thread in
     *             the group terminated or its last thread group was
     *             destroyed. The concept of daemon thread group no
     *             longer exists.
     *
     * @since   1.0
     */
    @Deprecated(since="99", forRemoval=true)
    public final boolean isDaemon() {
        return false;
    }

    /**
     * Returns false.
     *
     * @return false
     *
     * @deprecated This method originally indicated if the thread group
     *             was destroyed. The ability to destroy a thread group
     *             no longer exists.
     *
     * @since   1.1
     */
    @Deprecated(since="99", forRemoval=true)
    public boolean isDestroyed() {
        return false;
    }

    /**
     * Does nothing.
     *
     * @param daemon ignored
     *
     * @deprecated This method originally changed the daemon status of the
     *             thread group. The concept of daemon thread group no
     *             longer exists.
     *
     * @since   1.0
     */
    @Deprecated(since="99", forRemoval=true)
    public final void setDaemon(boolean daemon) {
    }

    /**
     * Sets the maximum priority of the group. Threads in the thread
     * group that already have a higher priority are not affected.
     * <p>
     * First, the {@code checkAccess} method of this thread group is
     * called with no arguments; this may result in a security exception.
     * <p>
     * If the {@code pri} argument is less than
     * {@link Thread#MIN_PRIORITY} or greater than
     * {@link Thread#MAX_PRIORITY}, the maximum priority of the group
     * remains unchanged.
     * <p>
     * Otherwise, the maximum priority of the group is set to {@code pri}.
     * The <i>effective</i> maximum priority of the group will be the smaller
     * of {@code pri} and the <i>effective</i> maximum priority of the parent
     * of this thread group. If this thread group is the system thread group,
     * which has no parent, then the effective maximum priority will be
     * {@code pri}.
     *
     * @param      pri   the new maximum priority of the thread group.
     * @throws     SecurityException  if the current thread cannot modify
     *               this thread group.
     * @see        #getMaxPriority
     * @see        java.lang.SecurityException
     * @see        java.lang.ThreadGroup#checkAccess()
     * @since      1.0
     */
    public final void setMaxPriority(int pri) {
        checkAccess();
        if (pri >= Thread.MIN_PRIORITY && pri <= Thread.MAX_PRIORITY) {
            this.maxPriority = pri;
        }
    }

    /**
     * Tests if this thread group is either the thread group
     * argument or one of its ancestor thread groups.
     *
     * @param   g   a thread group.
     * @return  {@code true} if this thread group is the thread group
     *          argument or one of its ancestor thread groups;
     *          {@code false} otherwise.
     * @since   1.0
     */
    public final boolean parentOf(ThreadGroup g) {
        for (; g != null ; g = g.parent) {
            if (g == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines if the currently running thread has permission to
     * modify this thread group.
     * <p>
     * If there is a security manager, its {@code checkAccess} method
     * is called with this thread group as its argument. This may result
     * in throwing a {@code SecurityException}.
     *
     * @throws     SecurityException  if the current thread is not allowed to
     *               access this thread group.
     * @see        java.lang.SecurityManager#checkAccess(java.lang.ThreadGroup)
     * @since      1.0
     */
    public final void checkAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkAccess(this);
        }
    }

    /**
     * Returns an estimate of the number of active threads in this thread
     * group and its subgroups.
     *
     * <p> The value returned is only an estimate because the number of
     * threads may change dynamically while this method traverses internal
     * data structures, and might be affected by the presence of certain
     * system threads. This method is intended primarily for debugging
     * and monitoring purposes.
     *
     * @return  an estimate of the number of active threads in this thread
     *          group and in any other thread group that has this thread
     *          group as an ancestor
     *
     * @deprecated {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int activeCount() {
        int n = 0;
        for (Thread thread : Thread.getAllThreads()) {
            ThreadGroup g = thread.getThreadGroup();
            if (parentOf(g)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Copies into the specified array every active thread in this
     * thread group and its subgroups.
     *
     * <p> An invocation of this method behaves in exactly the same
     * way as the invocation
     *
     * <blockquote>
     * {@linkplain #enumerate(Thread[], boolean) enumerate}{@code (list, true)}
     * </blockquote>
     *
     * @param  list
     *         an array into which to put the list of threads
     *
     * @return  the number of threads put into the array
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @deprecated This method is inherently racy.
     *             {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int enumerate(Thread list[]) {
        return enumerate(list, true);
    }

    /**
     * Copies into the specified array every active thread in this
     * thread group. If {@code recurse} is {@code true},
     * this method recursively enumerates all subgroups of this
     * thread group and references to every active thread in these
     * subgroups are also included. If the array is too short to
     * hold all the threads, the extra threads are silently ignored.
     *
     * <p> An application might use the {@linkplain #activeCount activeCount}
     * method to get an estimate of how big the array should be, however
     * <i>if the array is too short to hold all the threads, the extra threads
     * are silently ignored.</i>  If it is critical to obtain every active
     * thread in this thread group, the caller should verify that the returned
     * int value is strictly less than the length of {@code list}.
     *
     * <p> Due to the inherent race condition in this method, it is recommended
     * that the method only be used for debugging and monitoring purposes.
     *
     * @param  list
     *         an array into which to put the list of threads
     *
     * @param  recurse
     *         if {@code true}, recursively enumerate all subgroups of this
     *         thread group
     *
     * @return  the number of threads put into the array
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @deprecated This method is inherently racy.
     *             {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int enumerate(Thread list[], boolean recurse) {
        checkAccess();
        int n = 0;
        if (list.length > 0) {
            for (Thread thread : Thread.getAllThreads()) {
                ThreadGroup g = thread.getThreadGroup();
                if (g == this || (recurse && parentOf(g))) {
                    list[n++] = thread;
                    if (n == list.length) {
                        // list full
                        break;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Returns an estimate of the number of active groups in this
     * thread group and its subgroups. Recursively iterates over
     * all subgroups in this thread group.
     *
     * <p> The value returned is only an estimate because the number of
     * thread groups may change dynamically while this method traverses
     * internal data structures. This method is intended primarily for
     * debugging and monitoring purposes.
     *
     * @return  the number of active thread groups with this thread group as
     *          an ancestor
     *
     * @deprecated {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int activeGroupCount() {
        return subgroups(true).size();
    }

    /**
     * Copies into the specified array references to every active
     * subgroup in this thread group and its subgroups.
     * A thread group is considered <i>active</i> if there are any {@linkplain
     * Thread#isAlive() alive} threads in the group or any of its subgroups.
     *
     * <p> An invocation of this method behaves in exactly the same
     * way as the invocation
     *
     * <blockquote>
     * {@linkplain #enumerate(ThreadGroup[], boolean) enumerate}{@code (list, true)}
     * </blockquote>
     *
     * @param  list
     *         an array into which to put the list of thread groups
     *
     * @return  the number of thread groups put into the array
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @deprecated This method is inherently racy.
     *             {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int enumerate(ThreadGroup list[]) {
        return enumerate(list, true);
    }

    /**
     * Copies into the specified array references to every active
     * subgroup in this thread group.
     * A thread group is considered <i>active</i> if there are any {@linkplain
     * Thread#isAlive() alive} threads in the group or any of its subgroups.
     * If {@code recurse} is {@code true}, this method recursively enumerates
     * all subgroups of this thread group and references to every active
     * thread group in these subgroups are also included.
     *
     * <p> An application might use the
     * {@linkplain #activeGroupCount activeGroupCount} method to
     * get an estimate of how big the array should be, however <i>if the
     * array is too short to hold all the thread groups, the extra thread
     * groups are silently ignored.</i>  If it is critical to obtain every
     * active subgroup in this thread group, the caller should verify that
     * the returned int value is strictly less than the length of
     * {@code list}.
     *
     * <p> Due to the inherent race condition in this method, it is recommended
     * that the method only be used for debugging and monitoring purposes.
     *
     * @param  list
     *         an array into which to put the list of thread groups
     *
     * @param  recurse
     *         if {@code true}, recursively enumerate all subgroups
     *
     * @return  the number of thread groups put into the array
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @deprecated This method is inherently racy.
     *             {@linkplain java.lang.management.ThreadMXBean} provides a
     *             more suitable interface for monitoring threads.
     *
     * @since   1.0
     */
    @Deprecated(since="99")
    public int enumerate(ThreadGroup list[], boolean recurse) {
        checkAccess();
        int n = 0;
        if (list.length > 0) {
            for (ThreadGroup g : subgroups(recurse)) {
                list[n++] = g;
                if (n == list.length) {
                    // list full
                    break;
                }
            }
        }
        return n;
    }

    /**
     * Returns the set of active subgroups.
     */
    private Set<ThreadGroup> subgroups(boolean recurse) {
        Set<ThreadGroup> groups = new HashSet<>();
        for (Thread thread : Thread.getAllThreads()) {
            ThreadGroup g = thread.getThreadGroup();
            if (g != this && parentOf(g)) {
                if (recurse) {
                    // include intermediate subgroups
                    while (g != this) {
                        groups.add(g);
                        g = g.parent;
                    }
                } else {
                    // include direct subgroup
                    while (g.parent != this) {
                        g = g.parent;
                    }
                    groups.add(g);
                }
            }
        }
        return groups;
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @deprecated This method was originally specified to stop all threads in
     *             the thread group. It was inherently unsafe.
     *
     * @since   1.0
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void stop() {
        throw new UnsupportedOperationException();
    }

    /**
     * Interrupts all threads in this thread group.
     * <p>
     * First, the {@code checkAccess} method of this thread group is
     * called with no arguments; this may result in a security exception.
     * <p>
     * This method then calls the {@code interrupt} method on all the
     * threads in this thread group and in all of its subgroups.
     *
     * @throws     SecurityException  if the current thread is not allowed
     *               to access this thread group or any of the threads in
     *               the thread group.
     * @see        java.lang.Thread#interrupt()
     * @see        java.lang.SecurityException
     * @see        java.lang.ThreadGroup#checkAccess()
     * @since      1.2
     */
    public final void interrupt() {
        checkAccess();
        for (Thread thread : Thread.getAllThreads()) {
            ThreadGroup g = thread.getThreadGroup();
            if (parentOf(g)) {
                thread.interrupt();
            }
        }
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @deprecated This method was originally specified to suspend all threads
     *             in the thread group.
     *
     * @since   1.0
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void suspend() {
        throw new UnsupportedOperationException();
    }


    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @deprecated This method was originally specified to resume all threads
     *             in the thread group.
     *
     * @since   1.0
     */
    @Deprecated(since="1.2", forRemoval=true)
    public final void resume() {
        throw new UnsupportedOperationException();
    }

    /**
     * Does nothing.
     *
     * @deprecated This method was originally specified to destroy an empty
     *             thread group. The ability to destroy a thread group no
     *             longer exists.
     *
     * @since   1.0
     */
    @Deprecated(since="99", forRemoval=true)
    public final void destroy() {
    }

    /**
     * Does nothing.
     *
     * @deprecated This method was originally intended for debugging purposes.
     *
     * @since   1.0
     */
    @Deprecated(since="99", forRemoval=true)
    public void list() {
    }

    /**
     * Called by the Java Virtual Machine when a thread in this
     * thread group stops because of an uncaught exception, and the thread
     * does not have a specific {@link Thread.UncaughtExceptionHandler}
     * installed.
     * <p>
     * The {@code uncaughtException} method of
     * {@code ThreadGroup} does the following:
     * <ul>
     * <li>If this thread group has a parent thread group, the
     *     {@code uncaughtException} method of that parent is called
     *     with the same two arguments.
     * <li>Otherwise, this method checks to see if there is a
     *     {@linkplain Thread#getDefaultUncaughtExceptionHandler default
     *     uncaught exception handler} installed, and if so, its
     *     {@code uncaughtException} method is called with the same
     *     two arguments.
     * <li>Otherwise, this method determines if the {@code Throwable}
     *     argument is an instance of {@link ThreadDeath}. If so, nothing
     *     special is done. Otherwise, a message containing the
     *     thread's name, as returned from the thread's {@link
     *     Thread#getName getName} method, and a stack backtrace,
     *     using the {@code Throwable}'s {@link
     *     Throwable#printStackTrace() printStackTrace} method, is
     *     printed to the {@linkplain System#err standard error stream}.
     * </ul>
     * <p>
     * Applications can override this method in subclasses of
     * {@code ThreadGroup} to provide alternative handling of
     * uncaught exceptions.
     *
     * @param   t   the thread that is about to exit.
     * @param   e   the uncaught exception.
     * @since   1.0
     */
    public void uncaughtException(Thread t, Throwable e) {
        if (parent != null) {
            parent.uncaughtException(t, e);
        } else {
            Thread.UncaughtExceptionHandler ueh =
                Thread.getDefaultUncaughtExceptionHandler();
            if (ueh != null) {
                ueh.uncaughtException(t, e);
            } else if (!(e instanceof ThreadDeath)) {
                System.err.print("Exception in thread \""
                                 + t.getName() + "\" ");
                e.printStackTrace(System.err);
            }
        }
    }

    /**
     * Throws {@code UnsupportedOperationException}.
     *
     * @return nothing
     *
     * @param b ignore
     *
     * @deprecated This method was originally intended for controlling suspension
     *             in low memory conditions. It was never specified.
     *
     * @since   1.1
     */
    @Deprecated(since="1.2", forRemoval=true)
    public boolean allowThreadSuspension(boolean b) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a string representation of this Thread group.
     *
     * @return  a string representation of this thread group.
     * @since   1.0
     */
    public String toString() {
        return getClass().getName() + "[name=" + getName() + ",maxpri=" + getMaxPriority() + "]";
    }
}

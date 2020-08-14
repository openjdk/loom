/*
 * Copyright (c) 1995, 2020, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import jdk.internal.misc.VM;

/**
 * A thread group represents a set of threads. In addition, a thread
 * group can also include other thread groups. The thread groups form
 * a tree in which every thread group except the initial thread group
 * has a parent.
 *
 * <p> A newly created thread group is a <i>daemon thread group</i>. It is
 * <i>weakly reachable</i> from its parent so that it is eligible for garbage
 * collection when there are no {@linkplain Thread#isAlive() live} threads in
 * the group and there are no other objects keeping it alive. The {@link
 * #setDaemon(boolean)} method can be used to change a thread group to be
 * a <i>non-daemon thread group</i>. A non-daemon thread group is <i>strongly
 * reachable</i> from its parent.
 *
 * @apiNote
 * The concept of <i>daemon thread group</i> is not related to the concept
 * of {@linkplain Thread#isDaemon() daemon threads}. There may be both
 * daemon and non-daemon threads in a thread group, independent of whether
 * the group is a daemon or non-daemon thread group.
 *
 * @since   1.0
 */
public class ThreadGroup implements Thread.UncaughtExceptionHandler {
    /**
     * All fields are accessed directly by the VM and from JVMTI functions.
     * Recursive operations that require synchronization should synchronize
     * top-down, meaning parent first.
     */
    private final ThreadGroup parent;
    private final String name;
    private volatile int maxPriority;
    private volatile boolean daemon;

    // non-daemon subgroups (strongly reachable from this group)
    private int ngroups;
    private ThreadGroup[] groups;

    // daemon subgroups (weakly reachable from this group)
    private int nweaks;
    private WeakReference<ThreadGroup>[] weaks;

    /**
     * Creates the top-level "system" ThreadGroup. This method is invoked early
     * in the VM startup.
     */
    private ThreadGroup() {
        this.parent = null;
        this.name = "system";
        this.maxPriority = Thread.MAX_PRIORITY;
    }

    /**
     * Creates a ThreadGroup without any permission or other checks.
     *
     * The daemon status is ignored during VM startup. All ThreadGroups created
     * during VM startup are non-daemon.
     */
    ThreadGroup(ThreadGroup parent, String name, int maxPriority, boolean daemon) {
        this.parent = parent;
        this.name = name;
        this.maxPriority = maxPriority;
        if (daemon && VM.isBooted()) {
            parent.synchronizedAddWeak(this);
            this.daemon = true;
        } else {
            parent.synchronizedAdd(this);
        }
    }

    private ThreadGroup(Void unused, ThreadGroup parent, String name) {
        this(parent, name, parent.getMaxPriority(), true);
    }

    private static Void checkParentAccess(ThreadGroup parent) {
        parent.checkAccess();
        return null;
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

    /**
     * Returns the name of this thread group.
     *
     * @return  the name of this thread group, may be {@code null}
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
     * Returns the maximum priority of this thread group. This is the maximum
     * priority for new threads created in the thread group.
     *
     * @return  the maximum priority for new threads created in the thread group
     * @see     #setMaxPriority
     * @since   1.0
     */
    public final int getMaxPriority() {
        return maxPriority;
    }

    /**
     * Tests if this thread group is a daemon thread group. A daemon thread
     * group is <i>weakly reachable</i>  from its parent so it can be garbage
     * collected when there are no {@linkplain Thread#isAlive() alive} threads
     * in the group (and it is otherwise eligible for garbage collection).
     *
     * @return  {@code true} if this thread group is a daemon thread group;
     *          {@code false} otherwise.
     *
     * @since   1.0
     */
    public final boolean isDaemon() {
        return daemon;
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
     * Changes the daemon status of this thread group. A daemon thread group is
     * <i>weakly reachable</i> from its parent so it can be garbage collected
     * when there are no {@linkplain Thread#isAlive() alive} threads in the
     * group (and it is otherwise eligible for garbage collection). A non-daemon
     * thread is <i>strongly reachable</i> from its parent. This method does not
     * change the daemon status of subgroups.
     * <p>
     * First, the {@code checkAccess} method of this thread group is
     * called with no arguments; this may result in a security exception.
     *
     * @param      daemon   if {@code true}, marks this thread group as
     *                      a daemon thread group; otherwise, marks this
     *                      thread group as normal.
     * @throws     SecurityException  if the current thread cannot modify
     *               this thread group.
     * @see        java.lang.SecurityException
     * @see        java.lang.ThreadGroup#checkAccess()
     * @since      1.0
     */
    public final void setDaemon(boolean daemon) {
        checkAccess();
        if (parent == null) {
            this.daemon = daemon;
        } else {
            // update the parent so that it has a weak or strong
            // reference to this group
            synchronized (parent) {
                synchronized (this) {
                    if (daemon != this.daemon) {
                        if (daemon) {
                            // add a weak reference to this group
                            parent.addWeak(this);
                            parent.remove(this);
                        } else {
                            // add a strong reference to this group
                            parent.add(this);
                            parent.removeWeak(this);
                        }
                        this.daemon = daemon;
                    }
                }
            }
        }
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
     * Otherwise, the priority of this ThreadGroup object is set to the
     * smaller of the specified {@code pri} and the maximum permitted
     * priority of the parent of this thread group. (If this thread group
     * is the system thread group, which has no parent, then its maximum
     * priority is simply set to {@code pri}.) Then this method is
     * called recursively, with {@code pri} as its argument, for
     * every thread group that belongs to this thread group.
     *
     * @param      pri   the new priority of the thread group.
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
            if (parent == null) {
                maxPriority = pri;
            } else {
                maxPriority(pri);
            }
        }
    }

    private void maxPriority(int pri) {
        synchronized (this) {
            maxPriority = Math.min(pri, parent.maxPriority);
            forEachSubgroup(g -> g.maxPriority(pri));
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
     * Returns an estimate of the number of active (meaning
     * {@linkplain Thread#isAlive() alive}) threads in this thread group
     * and its subgroups. Recursively iterates over all subgroups in this
     * thread group.
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
     * @since   1.0
     */
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
     * Copies into the specified array every active (meaning {@linkplain
     * Thread#isAlive() alive}) thread in this thread group and its subgroups.
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
     * @throws  NullPointerException
     *          if {@code list} is null
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @since   1.0
     */
    public int enumerate(Thread list[]) {
        return enumerate(list, true);
    }

    /**
     * Copies into the specified array every active (meaning {@linkplain
     * Thread#isAlive() alive}) thread in this thread group. If {@code
     * recurse} is {@code true}, this method recursively enumerates all
     * subgroups of this thread group and references to every active thread
     * in these subgroups are also included. If the array is too short to
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
     * @apiNote {@linkplain java.lang.management.ThreadMXBean} supports
     * monitoring and management of active threads in the Java virtual
     * machine and may be a more suitable API some many debugging and
     * monitoring purposes.
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
     * @throws  NullPointerException
     *          if {@code list} is null
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @since   1.0
     */
    public int enumerate(Thread list[], boolean recurse) {
        Objects.requireNonNull(list);
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
     * Returns an estimate of the number of groups in this thread group and its
     * subgroups. Recursively iterates over all subgroups in this thread group.
     *
     * <p> The value returned is only an estimate because the number of
     * thread groups may change dynamically while this method traverses
     * internal data structures. This method is intended primarily for
     * debugging and monitoring purposes.
     *
     * @return  the number of thread groups with this thread group as
     *          an ancestor
     *
     * @since   1.0
     */
    public int activeGroupCount() {
        int n;
        synchronized (this) {
            n = ngroups;
            for (int i = 0; i < ngroups; i++) {
                n+= groups[i].activeGroupCount();
            }
            for (int i = 0; i < nweaks; ) {
                ThreadGroup g = weaks[i].get();
                if (g == null) {
                    removeWeak(i);
                } else {
                    n = n + 1 + g.activeGroupCount();
                    i++;
                }
            }
        }
        return n;
    }

    /**
     * Copies into the specified array references to every subgroup in this
     * thread group and its subgroups.
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
     * @throws  NullPointerException
     *          if {@code list} is null
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @since   1.0
     */
    public int enumerate(ThreadGroup list[]) {
        return enumerate(list, true);
    }

    /**
     * Copies into the specified array references to every subgroup in this
     * thread group. If {@code recurse} is {@code true}, this method recursively
     * enumerates all subgroups of this thread group and references to every
     * thread group in these subgroups are also included.
     *
     * <p> An application might use the
     * {@linkplain #activeGroupCount activeGroupCount} method to
     * get an estimate of how big the array should be, however <i>if the
     * array is too short to hold all the thread groups, the extra thread
     * groups are silently ignored.</i>  If it is critical to obtain every
     * subgroup in this thread group, the caller should verify that
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
     * @throws  NullPointerException
     *          if {@code list} is null
     *
     * @throws  SecurityException
     *          if {@linkplain #checkAccess checkAccess} determines that
     *          the current thread cannot access this thread group
     *
     * @since   1.0
     */
    public int enumerate(ThreadGroup list[], boolean recurse) {
        Objects.requireNonNull(list);
        checkAccess();
        return enumerate(list, 0, recurse);
    }

    /**
     * Add a reference to each subgroup to the given array, starting at
     * the given index. Returns the new index.
     */
    private int enumerate(ThreadGroup list[], int i, boolean recurse) {
        synchronized (this) {
            // non-daemon thread groups
            for (int j = 0; j < ngroups && i < list.length; ) {
                ThreadGroup group = groups[j];
                list[i++] = group;
                if (recurse) {
                    i = group.enumerate(list, i, true);
                }
                j++;
            }

            // daemon thread groups
            for (int j = 0; j < nweaks && i < list.length; ) {
                ThreadGroup group = weaks[j].get();
                if (group == null) {
                    removeWeak(j);
                } else {
                    list[i++] = group;
                    if (recurse) {
                        i = group.enumerate(list, i, true);
                    }
                    j++;
                }
            }
        }
        return i;
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
     * Interrupts all active (meaning {@linkplain Thread#isAlive() alive}) in
     * this thread group and its subgroups.
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
     * Does nothing.
     *
     * @return false
     *
     * @param b ignored
     *
     * @deprecated This method was originally intended for controlling suspension
     *             in low memory conditions. It was never specified.
     *
     * @since   1.1
     */
    @Deprecated(since="1.2", forRemoval=true)
    public boolean allowThreadSuspension(boolean b) {
        return false;
    }

    /**
     * Returns a string representation of this Thread group.
     *
     * @return  a string representation of this thread group.
     * @since   1.0
     */
    public String toString() {
        return getClass().getName()
                + "[name=" + getName()
                + ",maxpri=" + getMaxPriority()
                + ",daemon=" + isDaemon()
                + "]";
    }

    /**
     * Add a non-daemon subgroup.
     */
    private void synchronizedAdd(ThreadGroup group) {
        synchronized (this) {
            add(group);
        }
    }

    /**
     * Add a non-daemon subgroup.
     */
    private void add(ThreadGroup group) {
        assert Thread.holdsLock(this);
        if (ngroups == 0) {
            groups = new ThreadGroup[4];
        } else if (groups.length == ngroups) {
            groups = Arrays.copyOf(groups, ngroups + 4);
        }
        groups[ngroups++] = group;
    }

    /**
     * Remove a non-daemon subgroup.
     */
    private boolean remove(ThreadGroup group) {
        assert Thread.holdsLock(this);
        for (int i = 0; i < ngroups; ) {
            if (groups[i] == group) {
                int last = ngroups - 1;
                if (i < last)
                    groups[i] = groups[last];
                groups[last] = null;
                ngroups--;
                return true;
            } else {
                i++;
            }
        }
        return false;
    }

    /**
     * Add a daemon subgroup
     */
    private void synchronizedAddWeak(ThreadGroup group) {
        synchronized (this) {
            addWeak(group);
        }
    }

    /**
     * Add a daemon subgroup.
     */
    private void addWeak(ThreadGroup group) {
        assert Thread.holdsLock(this);
        if (nweaks == 0) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            WeakReference<ThreadGroup>[] array = new WeakReference[4];
            weaks = array;
        } else {
            removeWeak(null);
            if (weaks.length == nweaks) {
                weaks = Arrays.copyOf(weaks, nweaks + 4);
            }
        }
        weaks[nweaks++] = new WeakReference<>(group);
    }

    /**
     * Remove a daemon subgroup, expunging stale elements as a side effect.
     *
     * @param group the non-daemon subgroup to remove, can be null to just
     *              expunge stale elements
     */
    private void removeWeak(ThreadGroup group) {
        assert Thread.holdsLock(this);
        for (int i = 0; i < nweaks; ) {
            ThreadGroup g = weaks[i].get();
            if (g == null || g == group) {
                removeWeak(i);
            } else {
                i++;
            }
        }
    }

    /**
     * Remove the daemon thread at the given index of the weaks array.
     */
    private void removeWeak(int index) {
        assert Thread.holdsLock(this) && index < nweaks;
        int last = nweaks - 1;
        if (index < nweaks)
            weaks[index] = weaks[last];
        weaks[last] = null;
        nweaks--;
    }

    /**
     * Performs an action for each (direct) subgroup.
     */
    private void forEachSubgroup(Consumer<ThreadGroup> action) {
        assert Thread.holdsLock(this);
        for (int i = 0; i < ngroups; i++) {
            action.accept(groups[i]);
        }
        for (int i = 0; i < nweaks; ) {
            ThreadGroup g = weaks[i].get();
            if (g == null) {
                removeWeak(i);
            } else {
                action.accept(g);
                i++;
            }
        }
    }
}

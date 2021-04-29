/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

import jdk.internal.misc.StackChunk;
import jdk.internal.vm.annotation.DontInline;
import jdk.internal.vm.annotation.IntrinsicCandidate;
import sun.security.action.GetPropertyAction;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TBD
 */
public class Continuation {
    static {
        StackChunk.init(); // ensure StackChunk class is initialized
    }

    // private static final WhiteBox WB = sun.hotspot.WhiteBox.WhiteBox.getWhiteBox();
    private static final jdk.internal.misc.Unsafe unsafe = jdk.internal.misc.Unsafe.getUnsafe();

    private static final boolean TRACE = isEmptyOrTrue("java.lang.Continuation.trace");
    private static final boolean DEBUG = TRACE | isEmptyOrTrue("java.lang.Continuation.debug");

    private static final VarHandle MOUNTED;

    /** Reason for pinning */
    public enum Pinned { 
        /** Native frame on stack */ NATIVE,
        /** Monitor held */          MONITOR,
        /** In critical section */   CRITICAL_SECTION }
    /** Preemption attempt result */
    public enum PreemptStatus { 
        /** Success */                                                      SUCCESS(null), 
        /** Permanent failure */                                            PERM_FAIL_UNSUPPORTED(null), 
        /** Permanent failure: continuation alreay yielding */              PERM_FAIL_YIELDING(null), 
        /** Permanent failure: continuation not mounted on the thread */    PERM_FAIL_NOT_MOUNTED(null), 
        /** Transient failure: continuation pinned due to a held CS */      TRANSIENT_FAIL_PINNED_CRITICAL_SECTION(Pinned.CRITICAL_SECTION),
        /** Transient failure: continuation pinned due to native frame */   TRANSIENT_FAIL_PINNED_NATIVE(Pinned.NATIVE), 
        /** Transient failure: continuation pinned due to a held monitor */ TRANSIENT_FAIL_PINNED_MONITOR(Pinned.MONITOR);

        final Pinned pinned;
        private PreemptStatus(Pinned reason) { this.pinned = reason; }
        /** 
         * TBD
         * @return TBD
         **/
        public Pinned pinned() { return pinned; }
    }

    private static PreemptStatus preemptStatus(int status) {
        switch (status) {
            case -5: return PreemptStatus.PERM_FAIL_UNSUPPORTED;
            case  0: return PreemptStatus.SUCCESS;
            case -1: return PreemptStatus.PERM_FAIL_NOT_MOUNTED;
            case -2: return PreemptStatus.PERM_FAIL_YIELDING;
            case  2: return PreemptStatus.TRANSIENT_FAIL_PINNED_CRITICAL_SECTION;
            case  3: return PreemptStatus.TRANSIENT_FAIL_PINNED_NATIVE;
            case  4: return PreemptStatus.TRANSIENT_FAIL_PINNED_MONITOR;
            default: throw new AssertionError("Unknown status: " + status);
        }
    }

    private static Pinned pinnedReason(int reason) {
        switch (reason) {
            case 2: return Pinned.CRITICAL_SECTION;
            case 3: return Pinned.NATIVE;
            case 4: return Pinned.MONITOR;
            default:
                throw new AssertionError("Unknown pinned reason: " + reason);
        }
    }

    private static Thread currentCarrierThread() {
        return Thread.currentCarrierThread();
    }

    static {
        try {
            registerNatives();

            MethodHandles.Lookup l = MethodHandles.lookup();
            MOUNTED = l.findVarHandle(Continuation.class, "mounted", boolean.class);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    private Runnable target;

    /* While the native JVM code is aware that every continuation has a scope, it is, for the most part,
     * oblivious to the continuation hierarchy. The only time this hierarchy is traversed in native code
     * is when a hierarchy of continuations is mounted on the native stack.
     */
    private final ContinuationScope scope;
    private Continuation parent; // null for native stack
    private Continuation child; // non-null when we're yielded in a child continuation

    private StackChunk tail;

    private boolean done;
    private volatile boolean mounted = false;
    private Object yieldInfo;
    private boolean preempted;

    private short cs; // critical section semaphore

    private boolean reset = false; // perftest only

    private Object[] scopeLocalCache;

    // private long[] nmethods = null; // grows up
    // private int numNmethods = 0;

    /**
     * TBD
     * @param scope TBD
     * @param target TBD
     */
    public Continuation(ContinuationScope scope, Runnable target) {
        this.scope = scope;
        this.target = target;
    }

    @Override
    public String toString() {
        return super.toString() + " scope: " + scope;
    }

    ContinuationScope getScope() {
        return scope;
    }

    Continuation getParent() {
        return parent;
    }

    /**
     * TBD
     * @param scope TBD
     * @return TBD
     */
    public static Continuation getCurrentContinuation(ContinuationScope scope) {
        Continuation cont = currentCarrierThread().getContinuation();
        while (cont != null && cont.scope != scope)
            cont = cont.parent;
        return cont;
    }

    /**
     * TBD
     * @return TBD
     */
    public StackWalker stackWalker() {
        return stackWalker(EnumSet.noneOf(StackWalker.Option.class));
    }

    /**
     * TBD
     * @param option TBD
     * @return TBD
     */
    public StackWalker stackWalker(StackWalker.Option option) {
        return stackWalker(EnumSet.of(Objects.requireNonNull(option)));
    }

    /**
     * TBD
     * @param options TBD
     * @return TBD
     */
    public StackWalker stackWalker(Set<StackWalker.Option> options) {
        return stackWalker(options, this.scope);
    }

    /**
     * TBD
     * @param options TBD
     * @param scope TBD
     * @return TBD
     */
    public StackWalker stackWalker(Set<StackWalker.Option> options, ContinuationScope scope) {
        // if (scope != null) {
        //     // verify the given scope exists in this continuation
        //     Continuation c;
        //     for (c = innermost(); c != null; c = c.parent) {
        //         if (c.scope == scope)
        //             break;
        //     }
        //     if (c.scope != scope)
        //         scope = this.scope; // throw new IllegalArgumentException("Continuation " + this + " not in scope " + scope); -- don't throw exception to have the same behavior as no continuation
        // } else {
        //     scope = this.scope;
        // }
        return StackWalker.newInstance(options, null, scope, innermost());
    }

    /**
     * TBD
     * @return TBD
     * @throws IllegalStateException if the continuation is mounted
     */
    public StackTraceElement[] getStackTrace() {
        return stackWalker(StackWalker.Option.SHOW_REFLECT_FRAMES)
            .walk(s -> s.map(StackWalker.StackFrame::toStackTraceElement)
            .toArray(StackTraceElement[]::new));
    }

    /// Support for StackWalker
    static <R> R wrapWalk(Continuation inner, ContinuationScope scope, Supplier<R> walk) {
        try {
            for (Continuation c = inner; c != null && c.scope != scope; c = c.parent)
                c.mount();

            // if (!inner.isStarted())
            //     throw new IllegalStateException("Continuation not started");
                
            return walk.get();
        } finally {
            for (Continuation c = inner; c != null && c.scope != scope; c = c.parent)
                c.unmount();
        }
    }

    private Continuation innermost() {
        Continuation c = this;
        while (c.child != null)
            c = c.child;
        return c;
    }

    private void mount() {
        if (!compareAndSetMounted(false, true))
            throw new IllegalStateException("Mounted!!!!");
        Thread.setScopeLocalCache(scopeLocalCache);
    }

    private void unmount() {
        scopeLocalCache = Thread.scopeLocalCache();
        Thread.setScopeLocalCache(null);
        setMounted(false);
    }
    
    /**
     * TBD
     */
    public final void run() {
        while (true) {
            if (TRACE) System.out.println("\n++++++++++++++++++++++++++++++");

            mount();

            if (done)
                throw new IllegalStateException("Continuation terminated");

            Thread t = currentCarrierThread();
            if (parent != null) {
                if (parent != t.getContinuation())
                    throw new IllegalStateException();
            } else
                this.parent = t.getContinuation();
            t.setContinuation(this);

            try {
                if (!isStarted()) { // is this the first run? (at this point we know !done)
                    if (TRACE) System.out.println("ENTERING " + id());
                    if (TRACE) System.out.println("start done: " + done);
                    enterSpecial(this, false);
                } else {
                    assert !isEmpty();
                    if (TRACE) System.out.println("continue done: " + done);
                    enterSpecial(this, true);
                }
            } finally {
                fence();
                StackChunk c = tail;

                try {
                if (TRACE) System.out.println("run (after): preemted: " + preempted);

                assert isEmpty() == done : "empty: " + isEmpty() + " done: " + done + " cont: " + Integer.toHexString(System.identityHashCode(this));
                currentCarrierThread().setContinuation(this.parent);
                if (parent != null)
                    parent.child = null;

                postYieldCleanup();

                unmount();
                } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
            }
            // we're now in the parent continuation

            assert yieldInfo == null || yieldInfo instanceof ContinuationScope;
            if (yieldInfo == null || yieldInfo == scope) {
                this.parent = null;
                this.yieldInfo = null;
                return;
            } else {
                parent.child = this;
                parent.yield0((ContinuationScope)yieldInfo, this);
                parent.child = null;
            }
        }
    }

    private void postYieldCleanup() {
        if (done) {
            this.tail = null;
        }
    }

    private void finish() {
        done = true;
        // assert doneX;
        // System.out.println("-- done!  " + id());
        if (TRACE) System.out.println(">>>>>>>> DONE <<<<<<<<<<<<< " + id());
        assert isEmpty();
    }

    @IntrinsicCandidate
    private static int doYield(int scopes) { throw new Error("Intrinsic not installed"); };

    @IntrinsicCandidate
    private native static void enterSpecial(Continuation c, boolean isContinue);


    @DontInline
    @IntrinsicCandidate
    private static void enter(Continuation c, boolean isContinue) {
      // This method runs in the "entry frame".
      // A yield jumps to this method's caller as if returning from this method.
      try {
        c.enter0();
      } finally {
        c.finish();
      }
    }

    private void enter0() {
      target.run();
    }

    private boolean isStarted() {
        return tail != null;
    }

    private boolean isEmpty() {
        for (StackChunk c = tail; c != null; c = c.parent()) {
            if (!c.isEmpty())
                return false;
        }
        return true;
    }

    /**
     * TBD
     * 
     * @param scope The {@link ContinuationScope} to yield
     * @return {@code true} for success; {@code false} for failure
     * @throws IllegalStateException if not currently in the given {@code scope},
     */
    public static boolean yield(ContinuationScope scope) {
        Continuation cont = currentCarrierThread().getContinuation();
        Continuation c;
        for (c = cont; c != null && c.scope != scope; c = c.parent)
            ;
        if (c == null)
            throw new IllegalStateException("Not in scope " + scope);

        return cont.yield0(scope, null);
    }

    private boolean yield0(ContinuationScope scope, Continuation child) {
        if (TRACE) System.out.println(this + " yielding on scope " + scope + ". child: " + child);

        preempted = false;

        if (scope != this.scope)
            this.yieldInfo = scope;
        int res = doYield(0);
        unsafe.storeFence(); // needed to prevent certain transformations by the compiler
        
        if (TRACE) System.out.println(this + " awake on scope " + scope + " child: " + child + " res: " + res + " yieldInfo: " + yieldInfo);

        try {
        assert scope != this.scope || yieldInfo == null : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;
        assert yieldInfo == null || scope == this.scope || yieldInfo instanceof Integer : "scope: " + scope + " this.scope: " + this.scope + " yieldInfo: " + yieldInfo + " res: " + res;

        if (child != null) { // TODO: ugly
            if (res != 0) {
                child.yieldInfo = res;
            } else if (yieldInfo != null) {
                assert yieldInfo instanceof Integer;
                child.yieldInfo = yieldInfo;
            } else {
                child.yieldInfo = res;
            }
            this.yieldInfo = null;

            if (TRACE) System.out.println(this + " child.yieldInfo = " + child.yieldInfo);
        } else {
            if (res == 0 && yieldInfo != null) {
                res = (Integer)yieldInfo;
            }
            this.yieldInfo = null;

            if (res == 0)
                onContinue();
            else
                onPinned0(res);

            if (TRACE) System.out.println(this + " res: " + res);
        }
        assert yieldInfo == null;

        return res == 0;
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    private void onPinned0(int reason) {
        if (TRACE) System.out.println("PINNED " + this + " reason: " + reason);
        onPinned(pinnedReason(reason));
    }

    /**
     * TBD
     * @param reason TBD
     */
    protected void onPinned(Pinned reason) {
        if (DEBUG)
            System.out.println("PINNED! " + reason);
        throw new IllegalStateException("Pinned: " + reason);
    }

    /**
     * TBD
     */
    protected void onContinue() {
        if (TRACE)
            System.out.println("On continue");
    }

    /**
     * TBD
     * @return TBD
     */
    public boolean isDone() {
        return done;
    }

    /**
     * TBD
     * @return TBD
     */
    public boolean isPreempted() {
        return preempted;
    }

    /**
     * Pins the current continuation (enters a critical section).
     * This increments an internal semaphore that, when greater than 0, pins the continuation.
     */
    public static void pin() {
        Continuation cont = currentCarrierThread().getContinuation();
        if (cont != null) {
            assert cont.cs >= 0;
            if (cont.cs == Short.MAX_VALUE)
                throw new IllegalStateException("Too many pins");
            cont.cs++;
        }
    }

    /**
     * Unpins the current continuation (exits a critical section).
     * This decrements an internal semaphore that, when equal 0, unpins the current continuation
     * if pinne with {@link #pin()}.
     */
    public static void unpin() {
        Continuation cont = currentCarrierThread().getContinuation();
        if (cont != null) {
            assert cont.cs >= 0;
            if (cont.cs == 0)
                throw new IllegalStateException("Not pinned");
            cont.cs--;
        }
    }

    /**
     * Tests whether the given scope is pinned. 
     * This method is slow.
     * 
     * @param scope the continuation scope
     * @return {@code} true if we're in the give scope and are pinned; {@code false otherwise}
     */
    public static boolean isPinned(ContinuationScope scope) {
        int res = isPinned0(scope);
        return res != 0;
    }

    static private native int isPinned0(ContinuationScope scope);

    private void clean() {
        // if (!isStackEmpty())
        //     clean0();
    }

    private boolean fence() {
        unsafe.storeFence(); // needed to prevent certain transformations by the compiler
        return true;
    }

    // /**
    //  * TBD
    //  */
    // public void doneX() {
    //     // System.out.println("DONEX");
    //     this.doneX = true;
    // }


    /**
     * temporary testing
     */
    public void something_something_1() {
        this.done = false;

        setMounted(false);
    }

    /**
     * temporary testing
     */
    public void something_something_2() {
        reset = true;
    }

    /**
     * temporary testing
     */
    public void something_something_3() {
        this.done = false;
    }

    // private void pushNmethod(long nmethod) {
    //     if (nmethods == null) {
    //         nmethods = new long[8];
    //     } else {
    //         if (numNmethods == nmethods.length) {
    //             long[] newNmethods = new long[nmethods.length * 2];
    //             System.arraycopy(nmethods, 0, newNmethods, 0, numNmethods);
    //             this.nmethods = newNmethods;
    //         }
    //     }
    //     nmethods[numNmethods++] = nmethod;
    // }

    // private void popNmethod() {
    //     numNmethods--;
    // }

    private static Map<Long, Integer> liveNmethods = new ConcurrentHashMap<>();

    private void processNmethods(int before, int after) {

    }

    private boolean compareAndSetMounted(boolean expectedValue, boolean newValue) {
       boolean res = MOUNTED.compareAndSet(this, expectedValue, newValue);
    //    System.out.println("-- compareAndSetMounted:  ex: " + expectedValue + " -> " + newValue + " " + res + " " + id());
       return res;
     }

    private void setMounted(boolean newValue) {
        // System.out.println("-- setMounted:  " + newValue + " " + id());
        mounted = newValue;
        // MOUNTED.setVolatile(this, newValue);
    }

    private String id() {
        return Integer.toHexString(System.identityHashCode(this)) + " [" + currentCarrierThread().getId() + "]";
    }

    // private native void clean0();

    /**
     * TBD
     * Subclasses may throw an {@link UnsupportedOperationException}, but this does not prevent
     * the continuation from being preempted on a parent scope.
     * 
     * @param thread TBD
     * @return TBD
     * @throws UnsupportedOperationException if this continuation does not support preemption
     */
    public PreemptStatus tryPreempt(Thread thread) {
        PreemptStatus res = preemptStatus(tryForceYield0(thread));
        if (res == PreemptStatus.PERM_FAIL_UNSUPPORTED) {
            throw new UnsupportedOperationException("Thread-local handshakes disabled");
        }
        return res;
    }

    private native int tryForceYield0(Thread thread);

    // native methods
    private static native void registerNatives();

    private void dump() {
        System.out.println("Continuation@" + Long.toHexString(System.identityHashCode(this)));
        System.out.println("\tparent: " + parent);
        int i = 0;
        for (StackChunk c = tail; c != null; c = c.parent()) {
            System.out.println("\tChunk " + i);
            System.out.println(c);
        }
    }

    private static boolean isEmptyOrTrue(String property) {
        String value = GetPropertyAction.privilegedGetProperty(property);
        if (value == null)
            return false;
        return value.isEmpty() || Boolean.parseBoolean(value);
    }
}

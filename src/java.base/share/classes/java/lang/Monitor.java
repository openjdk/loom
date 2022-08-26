/*
 * Java implementation of Monitors.
 *
 * Actual monitor based on Doug Lea's code:
 *  // snapshot Tue Jul 23 11:04:46 2019  Doug Lea  (dl at nuc40)
 * - changes:
 *    - don't extend AbstractOwnableSynchronizer
 *    - add _owner field
 *    - rename lock->enter, unlock->exit, to match bytecode names
 *    - add tryEnter
 *    - methods pass the current thread
 *    - add complete_exit/reenter needed for classloader deadlock handling
 *      (this pair acts as an await() without any blocking)
 *    - move recursive owner check out of acquire into tryEnter
 *    - replace LockSupport usage with direct Unsafe.parkMonitor* so we
 *      use the correct ParkEvent in the VM
 *
 * Plus additional supporting infrastructure for monitor lookup via MonitorMap
 * Plus fast-locking layer with inflation (adapted from Robbin's version)
 */

package java.lang;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.lang.ref.WeakReference;
import jdk.internal.misc.Unsafe;
import jdk.internal.ref.Cleaner;
import jdk.internal.vm.annotation.IntrinsicCandidate;

/**
 * A monitor class to be used to implement Java 'synchronized' and the
 * Object monitor methods (wait/notify/notifyAll).
 *
 * Plus a bit more ...
 */

/* package */ final class Monitor {

    /**
     * The object this Monitor is associated with. This can't be a direct
     * strong reference, and we don't want yet-another-weak-reference so
     * we store the ObjectRef instead. This coupling between the Monitor
     * and the mapping scheme is unfortunate.
     */
    final ObjectRef obj;

    /**
     * Return the object associated with this Monitor.
     */
    Object object() {
        return obj.get();
    }

    /**
     * Return true if the expected thread is the owner of this Monitor
     * @param expected the expected owner
     * @return true if the owner is the expected thread
     */
    public boolean isOwnedBy(Thread expected) {
        return _owner == expected;
    }

    /**
     * Return true if the expected thread is the owner of the monitor
     * for the given Object.
     * The is the VM upcall to check an object is locked.
     * @param o the target Object
     * @param expected the expected owner (should be current thread)
     * @return true if the owner is the expected thread
     */
    @SuppressWarnings("fallthrough")
    public static boolean hasLockedObject(Object o, Thread expected) {
        if (Object.monitorPolicy == -1) {
            abort("ShouldNotReachHere!");
        }

        if (expected != Thread.currentThread()) {
            abort("Can only check monitor ownership of current thread");
        }

        // No matter which policy is used the lockStack will track ownership.
        // But we can cross-check with other ways for some policies.
        boolean owned = expected.hasLocked(o);

        switch (Object.monitorPolicy) {
        case 0: // native
            if (Thread.holdsLock(0) != owned) {
                abort("Lock ownership mismatch for native implementation");
            }
            break;
        case 1: // always inflated
            if (Monitor.of(o).isOwnedBy(expected) != owned) {
                abort("Lock ownership mismatch for always inflated implementaion");
            }
        case 2: // fast-locks
            break; // no other way to check for fast-locks
        default:
            abort("Invalid policy value " + monitorPolicy);
        }

        return owned;
    }

    /**
     * Current owning thread of this monitor.
     */
    private transient volatile Thread _owner;

    /**
     * Sentinel owner value for use during the inflation protocol.
     */
    // NOTE: synchronization is disabled when we execute this, else
    // we'd trip over the synchronized code in the Thread construction
    // process. Thread must be named so that we don't surprise tests
    // that "know" the first un-named thread is always Thread-0.
    private static final Thread SENTINEL = new Thread("Sentinel");

    /**
     * The synchronization state: 0 == unlocked, else recursion count.
     */
    private volatile int state;

    /* For testing only */
    /**
     * Return the number of times this monitor has been locked by its owner.
     * @return the holdCount
     */
    public int holdCount() { return state; }

    /**
     * Create a new Monitor
     * @param o the ObjectRef
     */
    public /*private*/ Monitor(ObjectRef o) { obj = o; }

    /**
     * Create a new Monitor that appears owned by the SENTINEL
     * @param o the ObjectRef
     * @param unused used for overloading
     */
    public /*private*/ Monitor(ObjectRef o, int unused) {
        obj = o;
        _owner = SENTINEL;
        state = 1;
    }


    /*
     * Values for the holder.threadStatus variable as defined in the VM
     * to match with JVMTI - see javaThreadStatus.hpp.
     */
    private static final int RUNNABLE           = 0x0004 | 0x0001;
    private static final int BLOCKED_ENTER      = 0x0400 | 0x0001;
    private static final int BLOCKED_WAIT       = 0x0100 | 0x0080 | 0x0010 | 0x0001;
    private static final int BLOCKED_TIMED_WAIT = 0x0100 | 0x0080 | 0x0020 | 0x0001;

    /**
     * Transitions the Monitor from SENTINEL owned to
     * the actual owner.
     * @param current the actual owner
     * @param heldCount the number of times the lock is held
     */
    private void enterByOwner(Thread current, int holdCount) {
        if (_owner != SENTINEL) abort("Should be in SENTINEL state - owner: " + _owner);
        _owner = current;
        if (state != 1) abort("Should be in SENTINEL state - state = " + state);
        state = holdCount;
    }

    /**
     * Acquires the monitor
     * @param current the current thread
     */
    public void enter(Thread current) {
        if (!tryEnter(current)) {
            acquire(current, null, 1);
        }
    }

    /**
     * Releases the monitor
     * @param current the current thread
     */
    public void exit(Thread current) {
        if (_owner != current)
            throw new IllegalMonitorStateException();
        int c = state - 1;
        if (c == 0)
            _owner = null;
        state = c;
        if (c == 0)
            signalFirst();
    }

    /** Tries to obtain the monitor lock if free, or recursively owned */
    private boolean tryEnter(Thread current) {
        if (casState(0, 1)) { // first attempt is unguarded
            if (_owner != null) throw new Error("owner was already set");
            _owner = current;
            return true;
        }
        else if (_owner == current) { // check recursive acquires
            int c = state + 1;
            if (c < 0) // overflow
                throw new Error("Maximum lock count exceeded");
            state = c;
            return true;
        } else {
            return false;
        }
    }


    // Node status bits, also used as argument and return values
    static final int WAITING   = 1;          // must be positive
    static final int COND      = 2;          // in a condition wait

    /** CLH Nodes, same as AQS */
    static abstract class Node {
        volatile Node prev;       // initially attached via casTail
        volatile Node next;       // visibly nonnull when signallable
        Thread waiter;            // visibly nonnull when enqueued
        volatile int status;      // written by owner, atomic bit ops by others

        // methods for atomic operations
        final boolean casPrev(Node c, Node v) {   // currently unused
            return U.weakCompareAndSetReference(this, PREV, c, v);
        }
        final boolean casNext(Node c, Node v) {   // currently unused
            return U.weakCompareAndSetReference(this, NEXT, c, v);
        }
        final int getAndUnsetStatus(int v) {       // for signalling
            return U.getAndBitwiseAndInt(this, STATUS, ~v);
        }
        final void setRelaxedPrev(Node p) {      // for off-queue assignment
            U.putReference(this, PREV, p);
        }
        final void setRelaxedStatus(int s) {     // for off-queue assignment
            U.putInt(this, STATUS, s);
        }
        final void clearStatus() {               // for reducing unneeded signals
            U.putIntOpaque(this, STATUS, 0);
        }


        private static final long STATUS;
        private static final long NEXT;
        private static final long PREV;
        static {
            try {
                PREV = U.objectFieldOffset
                    (Node.class.getDeclaredField("prev"));
                NEXT = U.objectFieldOffset
                    (Node.class.getDeclaredField("next"));
                STATUS = U.objectFieldOffset
                    (Node.class.getDeclaredField("status"));
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
    // Concrete classes tagged by type
    static final class LockNode extends Node { }

    static final class ConditionNode extends Node {
        ConditionNode nextWaiter; // link to next waiting node
    }

    /**
     * Head of the entry queue, lazily initialized.
     */
    private transient volatile Node head;

    /**
     * Tail of the entry queue. After initialization, modified only via casTail.
     */
    private transient volatile Node tail;

    private boolean casState(int expect, int update) {
        return U.compareAndSetInt(this, STATE, expect, update);
    }

    // Queuing utilities

    private boolean casTail(Node c, Node v) {
        return U.compareAndSetReference(this, TAIL, c, v);
    }

    /** tries once to CAS a new dummy node for head */
    private void tryInitializeHead() {
        Node h = new LockNode();
        if (U.compareAndSetReference(this, HEAD, null, h))
            tail = h;
    }

    /**
     * Enqueues the node unless null. (Currently used only for
     * ConditionNodes; other cases are interleaved with acquires.)
     */
    private void enqueue(Node node) {
        if (node != null) {
            for (;;) {
                Node t = tail;
                node.setRelaxedPrev(t);        // avoid unnecessary fence
                if (t == null)                 // initialize
                    tryInitializeHead();
                else if (casTail(t, node)) {
                    t.next = node;
                    break;
                }
            }
        }
    }

    /** Returns true if node is found in traversal from tail */
    private boolean isEnqueued(Node node) {
        for (Node t = tail; t != null; t = t.prev)
            if (t == node)
                return true;
        return false;
    }

    private void signalFirst() {
        Node h, s;
        if ((h = head) != null && (s = h.next) != null && s.status != 0) {
            s.getAndUnsetStatus(WAITING);
            Thread t = s.waiter;
            // We already released the lock so another thread may have
            // acquired it and then released it and so already have unparked
            // the first waiter, which may have cleared its node.
            if (t != null) {
                U.unparkMonitor(t);
            }
        }
    }

    private void acquire(Thread current, Node node, int arg) {
        boolean interrupted = false, first = false;
        byte spins = 0, postSpins = 0;   // retries upon unpark of first thread
        Node pred = (node != null) ? node.prev : null;

        boolean contended = false;  // for JVM TI event posting

        for (;;) {
            if ((first || pred == null || (first = (pred.prev == null))) &&
                state == 0 && casState(0, arg)) {
                if (node != null && pred != null) {
                    node.waiter = null;
                    node.prev = null;        // detach before assign head
                    head = node;
                    pred.next = null;
                    if (interrupted)
                        current.interrupt();
                }
                _owner = current;
                if (contended) {
                    // Post JVMTI_EVENT_MONITOR_CONTENDED_ENTERED
                    postJvmtiEvent(BLOCKED_ENTER + 1, current, this.obj.get(), -1 /* ignored */, false /* ignored */);
                }
                return;
            }
            if (node == null)                   // allocate; retry before enqueue
                node = new LockNode();
            else if (pred == null) {            // try to enqueue
                Node t = tail;
                node.setRelaxedPrev(t);         // avoid unecessary fence
                if (t == null)
                    tryInitializeHead();
                else if (!casTail(t, node))
                    node.setRelaxedPrev(null);  // back out
                else {
                    t.next = node;
                    pred = t;
                }
            } else if (first && spins > 0) {
                --spins;                        // reduce unfairness on rewaits
                Thread.onSpinWait();
            } else if (node.status == 0) {
                interrupted |= Thread.interrupted();  // clear before park
                if (node.waiter == null)
                    node.waiter = current;
                node.status = WAITING;          // enable signal
            } else {
                spins = postSpins = (byte)((postSpins << 1) | 1);
                int status = current.holder.threadStatus;
                current.holder.threadStatus = BLOCKED_ENTER;
                // Post JVMTI_EVENT_MONITOR_CONTENDED_ENTER
                postJvmtiEvent(BLOCKED_ENTER, current, this.obj.get(), -1 /* ignored */, false /* ignored */);
                contended = true;
                try {
                    U.parkMonitor();
                } finally {
                    current.holder.threadStatus = status;
                }
                node.clearStatus();
            }
        }
    }


    // Condition support

    /** First node of condition queue. */
    private transient ConditionNode firstWaiter;
    /** Last node of condition queue. */
    private transient ConditionNode lastWaiter;

    private void doSignal(ConditionNode first, boolean all) {
        while (first != null) {
            ConditionNode next = first.nextWaiter;
            if ((firstWaiter = next) == null)
                lastWaiter = null;
            if ((first.getAndUnsetStatus(COND) & COND) != 0) {
                enqueue(first);
                if (!all)
                    break;
            }
            //            if (next != null)
            //  next.notifierId = first.notifierId;
            first = next;
        }
    }

    /**
     * Performs Object.notify
     * @param current the current thread
     */
    public final void signal(Thread current) {
        ConditionNode first = firstWaiter;
        if (_owner != current)
            throw new IllegalMonitorStateException();
        if (first != null) {
            // first.notifierId = notifierId;
            doSignal(first, false);
        }
    }

    /**
     * Performs Object.notifyAll
     * @param current the current thread
     */
    public final void signalAll(Thread current) {
        ConditionNode first = firstWaiter;
        if (_owner != current)
            throw new IllegalMonitorStateException();
        if (first != null) {
            doSignal(first, true);
        }
    }

    /**
     * Returns true if a node that was initially placed on a condition
     * queue is now ready to reacquire on sync queue.
     * @param node the node
     * @return true if is reacquiring
     */
    private boolean canReacquire(ConditionNode node) {
        // check links, not status to avoid enqueue race
        return node != null && node.prev != null && isEnqueued(node);
    }

    /**
     * Unlinks the given node and other non-waiting nodes from
     * condition queue unless already unlinked.
     */
    private void unlinkCancelledWaiters(ConditionNode node) {
        if (node == null || node.nextWaiter != null || node == lastWaiter) {
            ConditionNode w = firstWaiter, trail = null;
            while (w != null) {
                ConditionNode next = w.nextWaiter;
                if ((w.status & COND) == 0) {
                    w.nextWaiter = null;
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)
                        lastWaiter = trail;
                } else
                    trail = w;
                w = next;
            }
        }
    }

    /**
     * Peforms Object.wait(millis)
     *
     * @param current the current thread
     * @param millis milliseconds
     * @throws InterruptedException if the current thread is interrupted
     */
    public void await(Thread current, long millis) throws InterruptedException {
        // Post JVMTI_EVENT_MONITOR_WAIT
        postJvmtiEvent(BLOCKED_WAIT, current, this.obj.get(), millis, false /* ignored */);

        if (_owner != current) {
            // Note: native implementation doesn't post
            // JVM TI event in this case.
            throw new IllegalMonitorStateException();
        }

        if (Thread.interrupted()) {
            // Post JVMTI_EVENT_MONITOR_WAITED
            postJvmtiEvent(BLOCKED_WAIT+1, current, this.obj.get(), -1 /* ignored */, true);
            throw new InterruptedException();
        }

        boolean timedOut = true; // assume interrupted
        try {
            timedOut = !doAwait(current, millis, true);
            // if we threw IE then timedOut remains true,
            // else it reflects whether or not we timed out
        } finally {
            // Post JVMTI_EVENT_MONITOR_WAITED
            postJvmtiEvent(BLOCKED_WAIT+1, current, this.obj.get(), -1 /* ignored */, timedOut);
        }
    }

    /**
     * Waits on the monitor without being interruptible.
     * Used from the VM to wait for concurrent static initialization
     * of a class to complete.
     * NOTE: No JVM TI events posted for this.
     * @param current the current thread
     */
    public void awaitUninterruptibly(Thread current) {
        if (_owner != current)
            throw new IllegalMonitorStateException();
        try {
            doAwait(current, 0, false);
        }
        catch (InterruptedException cantHappen) {
            throw new Error("Unexpected InterruptedException");
        }
    }

    // returns true if signalled, false if timed-out
    private boolean doAwait(Thread current, long millis, boolean interruptible)
        throws InterruptedException {

        int savedState = state;
        ConditionNode node = new ConditionNode();
        node.waiter = current;
        node.setRelaxedStatus(COND | WAITING);

        // add to waiters list
        ConditionNode last = lastWaiter;
        if (last == null)
            firstWaiter = node;
        else
            last.nextWaiter = node;
        lastWaiter = node;

        // release lock
        _owner = null;
        state = 0;
        signalFirst();

        boolean timed; long deadline;
        if (millis != 0L) {
            timed = true;
            deadline = System.nanoTime() + millis * 1000000L;
        } else {
            timed = false;
            deadline = 0L;
        }

        // wait for signal, interrupt, or timeout
        long nanos = 0L;
        boolean cancelled = false, interrupted = false;
        while (!canReacquire(node)) {
            if (((interrupted |= Thread.interrupted()) && interruptible) ||
                (timed && (nanos = deadline - System.nanoTime()) <= 0L)) {
                if (cancelled = (node.getAndUnsetStatus(COND) & COND) != 0)
                    break;
            } else if (timed) {
                int status = current.holder.threadStatus;
                current.holder.threadStatus = BLOCKED_TIMED_WAIT;
                try {
                    U.parkMonitorNanos(nanos);
                } finally {
                    current.holder.threadStatus = status;
                }
            } else {
                int status = current.holder.threadStatus;
                current.holder.threadStatus = BLOCKED_WAIT;
                try {
                    U.parkMonitor();
                } finally {
                    current.holder.threadStatus = status;
                }
            }
        }

        // reacquire lock
        node.clearStatus();
        acquire(current, node, savedState);
        if (cancelled)
            unlinkCancelledWaiters(node);
        if (interrupted) {
            if (cancelled && interruptible) {
                Thread.interrupted();
                throw new InterruptedException();
            }
            current.interrupt();
        }

        return !cancelled;
    }

    /**
     * Completely exit the monitor - called from the VM as part of
     * classloader deadlock avoidance.
     * @param current the current thread
     * @return recursion count
     */
    private int completeExit(Thread current) {
        // guarantee: _owner == current
        int savedState = state;
         // release lock
        _owner = null;
        state = 0;
        signalFirst();
        return savedState;
    }

    /**
     * Renter the monitor with given recursion count - called from
     * the VM as part of the classloader deadlock avoidance.
     * @param current the current thread
     * @param recursions
     */
    private void reenter(Thread current, int recursions) {
        enter(current);
        state = recursions;
    }

    // Unsafe
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long STATE;
    private static final long HEAD;
    private static final long TAIL;

    static {
        Class<?> nodeClass = Node.class;
        Class<?> monitorClass = Monitor.class;
        try {
            STATE = U.objectFieldOffset
                (monitorClass.getDeclaredField("state"));
            HEAD = U.objectFieldOffset
                (monitorClass.getDeclaredField("head"));
            TAIL = U.objectFieldOffset
                (monitorClass.getDeclaredField("tail"));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        // Force initialization of remaining nested classes.
        Object o = new LockNode();
        o = new ConditionNode();
        o = new ObjectRef(o);
        // And Inflater
        o = Inflater.locked;
    }


    //------------------------------------------------------------

    // This is the monitor lookup support via MonitorMap

    //------------------------------------------------------------

    // FIXME: Note the MonitorMap usage may not be fully correct with respect
    //        to Object lifecyle and reachability. In particular if a finalize()
    //        method uses synchronization then we will use a different
    //        monitor to that previously used with the Object, and we will
    //        temporarily resurrect the Object as well.

    /**
     * Wrapper class so that we can provide an Object-to-Monitor mapping
     * in the MonitorMap, that is based on identityHashCode, uses a
     * non-strong reference to the Object, and in which key equality is
     * defined by the referent identity.
     * We can't use the Object's hashCode() as it may be synchronized.
     */
    public static final class ObjectRef {

        /**
         * For test code, when running with Java Monitors disabled,
         * so we can test this like a regular library class.
         */
        public static boolean debugging = false;

        final WeakReference<Object> ref;
        final int hash;  // cached identity hash

        private Cleaner cleaner;

        /**
         * Create an Object ref for the target object
         * @param o the target Object
         */
        public ObjectRef(Object o) {
            if (debugging) System.out.println("ObjectRef for " + o);
            // assert:
            java.util.Objects.requireNonNull(o);
            ref = new WeakReference<>(o);
            hash = System.identityHashCode(o);
        }

        /**
         * Return the Object associated with this ObjectRef
         * @return the Object associated with this ObjectRef
         */
        public Object get() {
            return ref.get();
        }

        /**
         * Query if our WeakRef has been cleared.
         * @returns true if the weakref has been cleared
         */
        public boolean isCleared() {
            return ref.refersTo(null);
        }

        @Override
        public String toString() {
            return getClass().getName() + "@" +
                Integer.toHexString(System.identityHashCode(this)) + "(for " +
                Integer.toHexString(System.identityHashCode(ref.get())) + ")";
        }

        /**
         * Creates the Cleaner for this ObjectRef once we know
         * it may have been added to the map.
         */
        public void createCleaner() {
            if (debugging) System.out.println("Created Cleaner for " + ObjectRef.this);
            cleaner = Cleaner.create(ref.get(), new Runnable() {
                    public void run() {
                        if (debugging) System.out.println("Cleaner for " + ObjectRef.this);
                        if (Monitor.map.remove(ObjectRef.this) != null && debugging) {
                            System.out.println("Removed map entry for " + ObjectRef.this);
                        }
                    }
                });
        }

        public int hashCode() {
            return hash;
        }

        public boolean equals(Object o) {
            return o instanceof ObjectRef && equals((ObjectRef)o);
        }

        /**
         * Specialization of equals for ObjectRefs.
         * @param o the ObjectRef to compare with
         * @return true if this ObjectRef and o refer to the same referent,
         *              or both referents are null and o == this.
         */
        public boolean equals(ObjectRef o) {
            Object o1 = o.ref.get();
            Object o2 = ref.get();
            if (debugging) {
                System.out.println("Comparing ObjectRef " + this);
                System.out.println("With      ObjectRef " + o);
            }
            // With a non-null referent we are seeing if two ObjectRef's
            // refer to the same object. If the referents are both null
            // then we are looking up this key for deletion purposes so we
            // match on our ObjectRef identity.
            if (o1 == null && o2 == null) {
                return this == o;
            } else {
                return o1 == o2;
            }
        }

    }

    // Used by the inflation protocol to identify a monitor that still needs
    // to be fixed up by its owner.
    private boolean isInited() {
        return _owner != SENTINEL;
    }

    // TODO: do we actually need a reference from the Monitor to the Object
    // to ensure the Object remains alive? For the JIT the answer appears to
    // be yes (though the JIT could also arrange this by other means). For
    // the interpreter the object should always be live on the stack. It might
    // be useful for testing/validation purposes regardless.

    /**
     * Our Object-to-Monitor map.
     * Public only for standalone testing.
     */
    public static final MonitorMap<ObjectRef, Monitor> map = new MonitorMap<>();

    static final Function<ObjectRef, Monitor> monitorFactory =
        new Function<>() {
            public Monitor apply(ObjectRef o) { return new Monitor(o); }
        };

    static final Function<ObjectRef, Monitor> sentinelMonitorFactory =
        new Function<>() {
            public Monitor apply(ObjectRef o) { return new Monitor(o, 1); }
        };

    /**
     * Lookup the Monitor for the given Object, creating it if needed using
     * the normal Monitor factory to return an unowned Monitor.
     *
     * @param o the Object to obtain the monitor for
     * @return the Monitor associated with o
     */
    public /* package-private */ static final Monitor of(Object o) {
        return of(o, monitorFactory);
    }

    /**
     * Lookup the Monitor for the given Object, creating it if needed.
     *
     * @param o the Object to obtain the monitor for
     * @param factory the Monitor factory to use
     * @return the Monitor associated with o
     */
    public /* package-private */ static final Monitor of(Object o, Function<ObjectRef, Monitor> factory) {
        // We have to use the wrapper type to "override" hashCode
        ObjectRef r = new ObjectRef(o);
        Monitor m = map.get(r);
        if (m == null) {
            if (ObjectRef.debugging)
                System.out.println("Computing new Monitor for " + r);
            m = map.computeIfAbsent(r, factory);

            // assertion logic:
            Monitor m2;
            ObjectRef r2 = new ObjectRef(o);
            if ((m2 = map.get(r2)) != m)
                abort("Monitor lookup of " + r2 + "failed: got " + m2 +
                      ", expected " + m + ", map: " + map);

            m2 = map.computeIfAbsent(new ObjectRef(o), monitorFactory);
            if (m != m2)
                abort("Map is broken: computeIfAbsent returned " + m2 +
                      ", expected " + m + ", map: " + map);

            Object lockee = m.object();
            if (lockee != o)
                abort("Map is broken: lockee is " + lockee +
                      ", expected " + o + ", map: " + map);

            // end assertion

            // There is no way to tell if r was actually added to the map
            // or whether another thread installed r' for the same object.
            // So we have to create the Cleaner even though that may mean
            // there are multiple cleaners associated with the lifetime of
            // 'o'. Only the Cleaner for the installed ObjectRef will
            // actually do the removal.
            r.createCleaner();
        } else {
            if (ObjectRef.debugging)
                System.out.println("Found existing Monitor for " + r);
        }
        return m;
    }

    /**
     * Lookup the Monitor for the given ObjectRef, creating it if needed.
     * TEST CODE ONLY
     * @param r the ObjectRef to obtain the monitor for
     * @return the Monitor associated with r.obj
     */
    public /* package-private */ static final Monitor of(ObjectRef r) {
        Monitor m = map.get(r);
        if (m == null) {
            m = map.computeIfAbsent(r, monitorFactory);
            r.createCleaner();
        }
        return m;
    }


    /** JVMTI event hook
     * The id is just a convention:
     *  - BLOCKED_ENTER   => JVMTI_EVENT_MONITOR_CONTENDED_ENTER
     *  - BLOCKED_ENTER+1 => JVMTI_EVENT_MONITOR_CONTENDED_ENTERED
     *  - BLOCKED_WAIT    => JVMTI_EVENT_MONITOR_WAIT
     *  - BLOCKED_WAIT+1  => JVMTI_EVENT_MONITOR_WAITED
     *
     * @param id the event identifier
     * @param current the current thread
     * @param obj the object involved
     * @param ms the timeout value for wait() (ignored otherwise)
     * @param timedOut whether wait timed-out or was interrupted (ignored otherwise)
     */
    private static native void postJvmtiEvent(int id, Thread current, Object obj, long ms, boolean timedOut);

    //------------------------------------------------------------

    // This is the fast-locking support

    //------------------------------------------------------------

    // For the markword operation we could use Unsafe (which will call
    // into the VM; or we can call directly to the VM and use the existing
    // markWord API. Robbin did the latter so we use that.

    private static native void registerNatives();
    static {
        registerNatives();
        log("Monitor class registered natives");
    }

    // In the markWord the lower two bits are the lock-bits.
    // We don't have an INFLATING state as that protocol is
    // handled in the Java code. Once INFLATED we never deflate.
    // The Monitor is kept live in the MonitorMap until the associated
    // Object is eligible for GC and its entry removed from the map.

    static final int UNLOCKED = 1;
    static final int LOCKED = 0;
    static final int INFLATED = 2;

    @IntrinsicCandidate
    final static native boolean casLockState(Object o, int to, int from);

    @IntrinsicCandidate
    final static native int getLockState(Object o);

    // TODO intrinsic
    final static native Object getVMResult();

    // TODO intrinsic
    final static native void storeVMResult(Object vmresult);

    final static native void abort(String error);

    final static native void log(String msg);

    final static native void logEnter(Object o, long fid);

    final static native void logExit(Object o, long fid);

    // lowest level enter/exit via markWord ops

    private static boolean quickLock(Thread current, Object lockee, long fid) {
        if (casLockState(lockee, LOCKED, UNLOCKED)) {
            current.push(lockee, fid);
            return true;
        }
        return false;
    }

    private static boolean quickUnlock(Thread current, Object lockee, long fid) {
        if (casLockState(lockee, UNLOCKED, LOCKED)) {
            current.pop(lockee, fid);
            if (current.lockCount(lockee) > 0) {
                abort("Bad lockstack: unlocked object still on stack");
            }
            return true;
        }
        return false;
    }

    // Attempted fast-path monitor entry and exit - called from Object

    static boolean quickEnter(Thread current, Object lockee, long fid) {
        while (true) {
            int lockState = getLockState(lockee);
            switch (lockState) {
            case UNLOCKED:
                if (current.lockCount(lockee) > 0) {
                    abort("Bad lockstack: lockee unlocked but on stack");
                }
                if (quickLock(current, lockee, fid)) {
                    return true;
                }
                break; // re-check, CAS failed
            case LOCKED:
                // recursive locking ?
                if (current.hasLockedDirect(lockee)) {
                    current.push(lockee, fid);
                    return true;
                }
                return false; // take slow path
            case INFLATED:
                return false; // take slow path
            default:
                abort("Bad markword state: " + lockState);
            }
            Thread.yield();
        }
    }

    static boolean quickExit(Thread current, Object lockee, long fid) {
        while (true) {
            int lockState = getLockState(lockee);
            switch (lockState) {
            case UNLOCKED:
                abort("Bad markword: unlocked on exit");
                break;
            case LOCKED:
                int locksHeld = current.lockCount(lockee);
                if (locksHeld == 1) {
                    if (quickUnlock(current, lockee, fid)) {
                        return true;
                    } else {
                        break; // re-check, CAS failed
                    }
                } else if (locksHeld > 1) {
                    // recursive locking
                    current.pop(lockee, fid);
                    return true;
                } else {
                    if (current.hasLocked(lockee))
                        abort("lockCount and hasLocked disagree");
                    abort("Invalid lockCount when locked: " + locksHeld);
                }
                break; // can't reach here but keeps compiler happy
            case INFLATED:
                return false; // take slow path
            default:
                abort("Bad markword: " + lockState);
            }
            Thread.yield();
        }
    }

    // Slow-path monitor entry and exit - called from Object

    /**
     * Fetch an existing Monitor for 'o' else go through the
     * inflation process to fetch or create it.
     */
    static Monitor monitorFor(Object o, Thread current, boolean isOwned) {
        // Unfortunately we need to wrap o to look it up
        ObjectRef r = new ObjectRef(o);
        Monitor m = map.get(r);
        if (m == null || getLockState(o) != INFLATED || (isOwned && !m.isInited())) {
            // We either need to inflate, or serialize with an
            // in-progress inflation. If the monitor is not fully
            // initialized then the owner must always go through inflate().
            m = Inflater.inflate(current, o, isOwned);
        }
        return m;
    }

    static void slowEnter(Thread current, Object o, long fid) {
        boolean isOwned = current.hasLocked(o);
        Monitor m = monitorFor(o, current, isOwned);
        m.enter(current);
        current.push(m, fid);
    }

    static void jniEnter(Thread current, Object o) {
        // Always inflate if needed
        Monitor m = monitorFor(o, current, current.hasLocked(o));
        current.addJNIMonitor(m);
        m.enter(current);
    }

    static void slowExitOnRemoveActivation(Thread current, Object o, long fid) {
        Object vmResult = getVMResult();
        if (vmResult != null) {
            storeVMResult(null);
            slowExit(current, o, fid);
            storeVMResult(vmResult);
        } else {
            slowExit(current, o, fid);
        }
    }

    static void slowExit(Thread current, Object o, long fid) {
        if (!current.hasLocked(o))
            throw new IllegalMonitorStateException();

        Monitor m = monitorFor(o, current, true /* isOwned */);
        // Caution: does the order here matter?
        current.pop(m, fid);
        m.exit(current);
    }

    static void jniExit(Thread current, Object o) {
        if (!current.hasLocked(o))
            throw new IllegalMonitorStateException();

        // The JNI specification disallows unlocking a monitor at the JNI
        // level if it was locked by Java code. So if we don't already have
        // an inflated monitor then this call is illegal.
        Monitor m = Monitor.of(o);
        // Even if inflated we still must be JNI locked.
        if ( m == null || !current.removeJNIMonitor(m)) {
            throw new IllegalMonitorStateException("JNI MonitorExit called for an Object not locked by JNI MonitorEnter");
        }
        m.exit(current);
    }

    static void slowWaitUninterruptibly(Thread current, Object o) {
        if (!current.hasLocked(o))
            throw new IllegalMonitorStateException();
        Monitor m = monitorFor(o, current, true /* isOwned */);
        m.awaitUninterruptibly(current);
    }

    static void slowWait(Thread current, Object o, long millis) throws InterruptedException {
        // FIXME: native hotspot implemention posts the JVMTI
        // monitor_wait event before the ownership check. But note that
        // it fails to post the monitor_waited event if it throws IMSE.
        if (!current.hasLocked(o))
            throw new IllegalMonitorStateException();
        // wait() has to inflate
        Monitor m = monitorFor(o, current, true /* isOwned */);
        m.await(current, millis);
    }

    static void slowNotify(Thread current, Object o) {
        // Notification is a no-op if the Monitor is not inflated
        int lockState = getLockState(o);
        switch(lockState) {
        case LOCKED:
            break;
        case UNLOCKED:
            throw new IllegalMonitorStateException();
        case INFLATED:
            if (!current.hasLocked(o))
                throw new IllegalMonitorStateException();

            Monitor m = monitorFor(o, current, true /* isOwned */);
            m.signal(current);
            break;
        default:
            abort("Bad markword state: " + lockState);
        }
    }

    static void slowNotifyAll(Thread current, Object o) {
        // Notification is a no-op if the Monitor is not inflated
        int lockState = getLockState(o);
        switch(lockState) {
        case LOCKED:
            break;
        case UNLOCKED:
            throw new IllegalMonitorStateException();
        case INFLATED:
            if (!current.hasLocked(o))
                throw new IllegalMonitorStateException();

            Monitor m = monitorFor(o, current, true /* isOwned */);
            m.signalAll(current);
            break;
        default:
            abort("Bad markword state: " + lockState);
        }
    }

    //-----------------------------------------------------
    // Monitor inflation logic
    //----------------------------------------------------

    static class Inflater {

        // We need to serialize inflation attempts for the same object so we
        // utilise a simple spinLock
        private static AtomicInteger locked = new AtomicInteger(0);

        private static void spinLockAcquire() {
            while (!locked.compareAndSet(0, 1)) {
                Thread.onSpinWait();
            }
        }

        private static void spinLockRelease() {
            locked.set(0);
        }

        /**
         * Walks the lockStack of the given thread replacing each
         * ocurrence of 'o' with the Monitor 'm'. Then takes proper
         * ownership of 'm'. By placing 'm' in the lockStack
         * we can identify an inflated object without having to
         * read the markWord. With our MonitorMap we do not need to
         * put M in the map to keep it alive.
         */
        static void replace(Thread current, Monitor m, Object o) {
            int holds = 0;
            for (int i = 0; i < current.lockStackPos; i++) {
                if (current.lockStack[i] == o) {
                    current.lockStack[i] = m; // Change object to monitor
                    holds++;
                }
            }
            if (holds == 0) {
                // The monitor became unlocked during the inflation
                // race, so the current thread is taking ownership
                // for the first time - hence no lockStack entry.
                holds = 1;
            }
            m.enterByOwner(current, holds);
        }

        /**
         * Inflates the Monitor for the given Object, or returns the already
         * inflated Monitor if the race was lost to another thread.
         * Inflation can be initiated by the owning thread in the case of Object.wait.
         * Inflation can be initiated by another thread on a contended monitorenter.
         * The owner must be prepared to find an inflated monitor when attempting a
         * fast-unlock.
         * If is_owner is true then if state is LOCKED we must be inflating for wait;
         * otherwise the state must be INFLATED due to contended enter or exit.
         *
         * NOTE: we must maintain an appropriate thread state during this protocol. If
         * inflating due to a contended enter then we should set the thread state to
         * BLOCKED directly and we need to use a version of the Monitor methods that
         * does not set the thread state internally when waiting. To ensure correct
         * restoration of the state, the state management needs to happen in our caller.
         * Even so the operation of the inflation protocol could surprise observers
         * like debuggers.
         */
        private static Monitor inflate(Thread current, Object lockee, boolean is_owner) {
            Monitor m = null;
            boolean original_owner = is_owner;
            spinLockAcquire();
            try {
                int lockState = getLockState(lockee);
                if (lockState != INFLATED) {
                    // Create a "locked" Monitor and add it to the map.
                    // We could optimise for this case as we know it is not
                    // present.

                    m = Monitor.of(lockee, sentinelMonitorFactory);

                    // Now switch to INFLATED. This can race with the owner
                    // who could unlock and relock many times while we try to
                    // inflate.
                    do {
                        if (casLockState(lockee, INFLATED, UNLOCKED)) {
                            // assert: not owner
                            if (is_owner) abort("Inflation by owner found unlocked obj");

                            // The current thread is trying to inflate to enter but
                            // object is now unlocked, so after inflation we can take
                            // ownership below.
                            is_owner = true;
                        } else {
                            // Could be the owner inflating for wait(), or a
                            // contended enter by another thread - is_owner will
                            // already distinguish the two cases.
                            casLockState(lockee, INFLATED, LOCKED);
                        }
                    } while (getLockState(lockee) != INFLATED);

                } else {
                    // Already inflated so we must have lost the race, but if we are
                    // the owner then we will have more work to do below.

                    // Unfortunately we need to wrap lockee to look it up
                    ObjectRef r = new ObjectRef(lockee);
                    m = map.get(r);

                    if (m == null) abort("Monitor was not found in map!");
                }
            } catch (Throwable t) {
                t.printStackTrace();
                abort("inflate:" + t.getMessage());
            } finally {
                spinLockRelease();
            }

            // The Object is marked as inflated but the Monitor may still
            // be in the SENTINEL state, so if we are the owner we fix that.
            // If we are not the owner we will block on m.enter() later.
            try {
                if (is_owner && !m.isInited()) {
                    replace(current, m, lockee);
                    if (!original_owner) {
                        // We won the inflation race but found the monitor unlocked
                        // so we had to transition it to a proper state, but now we must
                        // release it again as we will acquire it properly in our caller.
                        if (m.state != 1) abort("Unexpected state: " + m.state);
                        m.exit(current);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                abort("inflate:" + t.getMessage());
            }
            return m;
        }
    } // Inflater
}

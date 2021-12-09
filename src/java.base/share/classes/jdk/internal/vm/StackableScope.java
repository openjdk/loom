/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.internal.vm;

import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;

import java.util.concurrent.Callable;

/**
 * A stackable scope.
 */
public class StackableScope {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    private final Thread owner;
    private volatile StackableScope previous;

    /**
     * Creates a stackable scope.
     */
    StackableScope(boolean shared) {
        if (shared) {
            this.owner = null;
        } else {
            this.owner = Thread.currentThread();
        }
    }

    /**
     * Creates a stackable scope owned by the current thread.
     */
    public StackableScope() {
        this(false);
    }

    /**
     * Returns the scope owner or null is not owned.
     */
    public Thread owner() {
        return owner;
    }

    /**
     * Pushes this scope onto the current thread's scope stack.
     */
    public StackableScope push() {
        if (owner == null)
            throw new UnsupportedOperationException();
        assert Thread.currentThread() == owner;
        previous = head();
        setHead(this);
        return this;
    }

    private static void handleThrows(Throwable ex, boolean atTop) {
        // re-throw exception if op completed with exception
        // throw exception if a structure mismatch was detected
        if (ex != null || !atTop) {
            if (!atTop) {
                var e = new StructureViolationException();
                if (ex == null) {
                    ex = e;
                } else {
                    ex.addSuppressed(e);
                }
            }
            if (ex instanceof RuntimeException e)
                throw e;
            if (ex instanceof Error e)
                throw e;
            assert false;
        }
    }

    /**
     * Call op, wrapped in a {@code StackableScope}
     * @param op a Callable
     * @param <T> a class
     * @return a T
     * @throws Exception
     */
    public static <T> T call(Callable<T> op) throws Exception {
        if (head() == null) {
            Throwable ex = null;
            T result = null;
            try {
                result = op.call();
            } catch (Throwable e) {
                ex = e;
            } finally {
                StackableScope head = head();
                if (head != null) {
                    popAll();
                }
                handleThrows(ex, head == null);
            }
            return result;
        } else {
            // Slow path
            return new StackableScope().doCall(op);
        }
    }

    private <T> T doCall(Callable<T> op) throws Exception {
        Throwable ex = null;
        boolean atTop;
        T result = null;
        var scope = push();
        try {
            result = op.call();
        } catch (Throwable e) {
            ex = e;
        } finally {
            atTop = scope.popForcefully();  // may block
        }
        handleThrows(ex, atTop);
        return result;
    }

    /**
     * Run op, wrapped in a {@code StackableScope}
     * @param op a Runnable
     */
    public static void run(Runnable op) {
        if (head() == null) {
            Throwable ex = null;
            try {
                op.run();
            } catch (Throwable e) {
                ex = e;
            } finally {
                StackableScope head = head();
                if (head != null) {
                    popAll();
                }
                handleThrows(ex, head == null);
            }
        } else {
            // Slow path
            new StackableScope().doRun(op);
        }
    }

    private void doRun(Runnable op) {
        Throwable ex = null;
        boolean atTop;
        var scope = push();
        try {
            op.run();
        } catch (Throwable e) {
            ex = e;
        } finally {
            atTop = scope.popForcefully();  // may block
        }
        handleThrows(ex, atTop);
    }

    /**
     * Pops this scope from the current thread's scope stack if the scope is
     * at the top of stack.
     * @return true if the pop succeeded, false if this scope is not the top of stack
     */
    public boolean tryPop() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Not owner");
        if (head() == this) {
            setHead(previous);
            previous = null;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Pops this scope from the current thread's scope stack.
     *
     * For well behaved usages, this scope is at the top of the stack. It is popped
     * from the stack and the method returns {@code true}.
     *
     * If this scope is not at the top of the stack then this method attempts to
     * close each of the intermediate scopes by invoking their {@link #tryClose()}
     * method. If tryClose succeeds then the scope is removed from the stack. When
     * done, this scope is removed from the stack and {@code false} is returned.
     *
     * This method does nothing, and returns {@code false}, if this scope is not
     * on the current thread's scope stack.
     *
     * @return true if this scope was at the top of the stack, otherwise false
     */
    public boolean popForcefully() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Not owner");
        final StackableScope head = head();
        if (head == this) {
            setHead(previous);
            previous = null;
            return true;
        }

        // scope is not the top of stack
        if (contains(this)) {
            StackableScope current = head;
            while (current != this) {
                StackableScope previous = current.previous();
                // attempt to forcefully close the scope and remove from stack
                if (current.tryClose()) {
                    current.unlink();
                }
                current = previous;
            }
            unlink();
        }
        return false;
    }

    /**
     * Pops all scopes from the current thread's scope stack.
     */
    public static void popAll() {
        StackableScope head = head();
        if (head != null) {
            StackableScope current = head;
            while (current != null) {
                assert Thread.currentThread() == current.owner();
                current.tryClose();
                current = current.previous();
            }
            setHead(null);
        }
    }

    /**
     * Returns the scope that encloses this scope.
     */
    public StackableScope enclosingScope() {
        StackableScope previous = this.previous;
        if (previous != null)
            return previous;
        if (owner != null)
            return JLA.threadContainer(owner);
        return null;
    }

    /**
     * Returns the scope of the given type that encloses this scope.
     */
    public <T extends StackableScope> T enclosingScope(Class<T> type) {
        StackableScope enclosing = enclosingScope();
        if (enclosing != null) {
            return enclosing.innermostScope(type);
        }
        return null;
    }

    public <T extends StackableScope> T innermostScope(Class<T> type) {
        StackableScope current = this;
        while (current != null) {
            if (type.isInstance(current)) {
                @SuppressWarnings("unchecked")
                T tmp = (T) current;
                return tmp;
            }
            current = current.enclosingScope();
        }
        return null;
    }

    /**
     * Returns the scope that directly encloses this scope, null if none.
     */
    StackableScope previous() {
        return previous;
    }

    /**
     * Returns the scope that this scope directly encloses, null if none.
     */
    private StackableScope next() {
        assert contains(this);
        StackableScope current = head();
        StackableScope next = null;
        while (current != this) {
            next = current;
            current = current.previous();
        }
        return next;
    }

    /**
     * Override this method to close this scope and release its resources.
     * This method should not pop the scope from the stack.
     * This method is guaranteed to execute on the owner thread.
     * @return true if this method closed the scope, false if it failed
     */
    protected boolean tryClose() {
        assert Thread.currentThread() == owner;
        return false;
    }

    /**
     * Removes this scope from the current thread's scope stack.
     */
    private void unlink() {
        assert contains(this);
        StackableScope next = next();
        if (next == null) {
            setHead(previous);
        } else {
            next.previous = previous;
        }
        previous = null;
    }

    /**
     * Returns true if the given scope is on the current thread's scope stack.
     */
    private static boolean contains(StackableScope scope) {
        assert scope != null;
        StackableScope current = head();
        while (current != null && current != scope) {
            current = current.previous();
        }
        return (current == scope);
    }

    /**
     * Returns the head of the current thread's scope stack.
     */
    private static StackableScope head() {
        return JLA.headStackableScope(Thread.currentThread());
    }

    /**
     * Sets the head (top) of the current thread's scope stack.
     */
    private static void setHead(StackableScope scope) {
        JLA.setHeadStackableScope(scope);
    }

}

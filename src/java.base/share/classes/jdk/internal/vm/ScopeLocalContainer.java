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

import java.util.concurrent.Callable;
import jdk.internal.access.JavaLangAccess;
import jdk.internal.access.SharedSecrets;
import jdk.internal.misc.Unsafe;

/**
 * A StackableScope to represent scope-local bindings.
 *
 * This class defines static methods to run an operation with a ScopeLocalContainer
 * on the scope stack. It also defines a method to get the latest ScopeLocalContainer
 * and a method to return a snapshot of the scope local bindings.
 */
public class ScopeLocalContainer extends StackableScope {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();

    /**
     * Returns the "latest" ScopeLocalContainer for the current Thread. This
     * may be on the current thread's scope task or may require walking up the
     * tree to find it.
     */
    public static ScopeLocalContainer latest() {
        StackableScope scope = head();
        if (scope == null) {
            scope = JLA.threadContainer(Thread.currentThread());
            if (scope == null || scope.owner() == null)
                return null;
        }
        if (scope instanceof ScopeLocalContainer container) {
            return container;
        } else {
            return scope.enclosingScope(ScopeLocalContainer.class);
        }
    }

    /**
     * A snapshot of the scope local bindings. The snapshot includes the bindings
     * established for the current thread and scope local container.
     */
    public record BindingsSnapshot(Object scopeLocalBindings,
                                   ScopeLocalContainer container) { }

    /**
     * Returns a scope local bindings for the current thread.
     */
    public static BindingsSnapshot captureBindings() {
        return new BindingsSnapshot(JLA.scopeLocalBindings(), latest());
    }

    /**
     * For use by ScopeLocal to run an operation in a structured context.
     */
    public static void run(Runnable op) {
        if (head() == null) {
            // no need to push scope when stack is empty
            runWithoutScope(op);
        } else {
            new ScopeLocalContainer().doRun(op);
        }
    }

    /**
     * Run an operation without a scope on the stack.
     */
    private static void runWithoutScope(Runnable op) {
        assert head() == null;
        Throwable ex;
        boolean atTop;
        try {
            op.run();
            ex = null;
        } catch (Throwable e) {
            ex = e;
        } finally {
            atTop = (head() == null);
            if (!atTop) popAll();   // may block
        }
        throwIfFailed(ex, atTop);
    }

    /**
     * Run an operation with this scope on the stack.
     */
    private void doRun(Runnable op) {
        Throwable ex;
        boolean atTop;
        push();
        try {
            op.run();
            ex = null;
        } catch (Throwable e) {
            ex = e;
        } finally {
            atTop = popForcefully();  // may block
        }
        throwIfFailed(ex, atTop);
    }

    /**
     * For use by ScopeLocal to call a value returning operation in a structured context.
     */
    public static <V> V call(Callable<V> op) throws Exception {
        if (head() == null) {
            // no need to push scope when stack is empty
            return callWithoutScope(op);
        } else {
            return new ScopeLocalContainer().doCall(op);
        }
    }

    /**
     * Call an operation without a scope on the stack.
     */
    private static <V> V callWithoutScope(Callable<V> op) {
        assert head() == null;
        Throwable ex;
        boolean atTop;
        V result;
        try {
            result = op.call();
            ex = null;
        } catch (Throwable e) {
            result = null;
            ex = e;
        } finally {
            atTop = (head() == null);
            if (!atTop) popAll();  // may block
        }
        throwIfFailed(ex, atTop);
        return result;
    }

    /**
     * Call an operation with this scope on the stack.
     *
     * Move to this ScopeLocalNode !!!
     */
    private <V> V doCall(Callable<V> op) {
        Throwable ex;
        boolean atTop;
        V result;
        push();
        try {
            result = op.call();
            ex = null;
        } catch (Throwable e) {
            result = null;
            ex = e;
        } finally {
            atTop = popForcefully();  // may block
        }
        throwIfFailed(ex, atTop);
        return result;
    }

    /**
     * Throws {@code ex} if not null. Throws StructureViolationException
     */
    private static void throwIfFailed(Throwable ex, boolean atTop) {
        if (ex != null || !atTop) {
            if (!atTop) {
                var e = new StructureViolationException();
                if (ex == null) {
                    ex = e;
                } else {
                    ex.addSuppressed(e);
                }
            }
            Unsafe.getUnsafe().throwException(ex);
        }
    }
}

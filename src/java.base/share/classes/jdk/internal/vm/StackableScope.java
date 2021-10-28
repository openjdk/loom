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

/**
 * A stackable scope.
 */
public class StackableScope {
    private static final JavaLangAccess JLA = SharedSecrets.getJavaLangAccess();
    private volatile StackableScope previous;

    /**
     * Creates a new stackable scope.
     */
    public StackableScope() { }

    /**
     * Pushes this scope onto the current thread's scope stack.
     */
    public StackableScope push() {
        previous = head();
        setHead(this);
        return this;
    }

    /**
     * Pops this scope from the current thread's scope stack.
     *
     * For well behaved usages, this scope is at the top of the stack. It is popped
     * from the stack and the method returns {@code true}.
     *
     * If not used correctly, and this scope is not at the top of the stack, then
     * the intermediate scopes between this scope and the top of the stack are
     * removed and closed if possible. At this time, only thread containers can be
     * forcefully closed and removed from the stack. This scope is then removed
     * from the stack and the method returns {@code false}.
     *
     * @return true if this cope was at the top of the stack, false if this scope
     * was not at the top of the stack
     */
    public boolean pop() {
        final StackableScope head = head();
        if (head == this) {
            // restore ref to previous scope
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
                current.tryClose();
                current = current.previous();
            }
            setHead(null);
        }
    }

    /**
     * Returns the scope that this scope encloses, null if none.
     */
    StackableScope previous() {
        return previous;
    }

    /**
     * Returns the scope that encloses this scope, null if not enclosed.
     */
    StackableScope next() {
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
     * Attempts to close this scope and release its resources.
     * This method should not pop the scope from the stack.
     * @return true if this method closed the scope, false if it failed
     */
    public boolean tryClose() {
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

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

import java.util.stream.Stream;

/**
 * A container of threads. Thread containers can be arranged in linked list.
 */
public interface ThreadContainer {
    /**
     * Return the owner, null if not owned.
     */
    default Thread owner() {
        return null;
    }

    /**
     * Return a count of the number of threads in this container.
     */
    long threadCount();

    /**
     * Returns a stream of the threads in this container.
     */
    Stream<Thread> threads();

    /**
     * Returns the previous thread container in the list.
     */
    default ThreadContainer previous() {
        return null;
    }

    /**
     * Sets the previous thread container in the list.
     */
    default void setPrevious(ThreadContainer container) {
        throw new UnsupportedOperationException();
    }
}
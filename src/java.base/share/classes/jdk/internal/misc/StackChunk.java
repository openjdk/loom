/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.misc;

public final class StackChunk {
    public static void init() {}

    public static final byte FLAG_HAS_INTERPRETED_FRAMES = 1 << 2;

    private Continuation cont; // must not be accessed by Java code, as this oop's processing is essential for the chunk's GC protocol
    private StackChunk parent;
    private int size; // in words
    private int sp; // in words
    private int argsize; // bottom stack-passed arguments, in words
    private byte flags;
    private long pc;

    private int gcSP;
    private long markCycle;

    private int maxSize; // size when fully thawed on stack
    private int numFrames;
    private int numOops;
    private boolean mode;
   
   // the stack itself is appended here by the VM

    public StackChunk parent() { return parent; }
    public int size()          { return size; }
    public int sp()            { return sp; }
    public int argsize()       { return argsize; }
    public int maxSize()       { return maxSize; }
    public boolean isEmpty()   { return sp >= (size - argsize); }
    public boolean isFlag(byte flag) { return (flags & flag) != 0; }
 }

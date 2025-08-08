/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch.iouring;

import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

import static sun.nio.ch.iouring.Util.strerror;

/**
 * A context for invoking a system call (typically) which needs
 * to capture and return errno
 *
 * Contexts cannot be shared among threads.
 */
class SystemCallContext {
    private final Arena confinedArena = Arena.ofConfined();
    private final Arena autoArena = Arena.ofAuto();
    private static final Linker.Option ccs =
            Linker.Option.captureCallState("errno");
    private static final StructLayout capturedStateLayout =
            Linker.Option.captureStateLayout();
    private final VarHandle errnoHandle =
            capturedStateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private final MemorySegment captureSegment = autoArena.allocate(capturedStateLayout);

    private static ThreadLocal<SystemCallContext> TL = new ThreadLocal<>() {
        protected SystemCallContext initialValue() {
            return new SystemCallContext();
        }
    };

    private SystemCallContext() {
    }

    /**
     * Returns a thread local SystemCallContext
     */
    public static SystemCallContext get() {
        return TL.get();
    }

    public static Linker.Option errnoLinkerOption() {
        return ccs;
    }
    public MemorySegment errnoCaptureSegment() {
        return captureSegment;
    }

    /**
     * If ret < 0 then errno is checked and an IOException thrown
     * containing the textual description of errno
     *
     * @param ret
     * @throws IOException
     */
    public void throwIOExceptionOnError(int ret) throws IOException {
        if (ret < 0) {
            ret = (int)errnoHandle().get(captureSegment, 0L);
            String errmsg = strerror(ret);
            throw new IOException(errmsg);
        }
    }
    public VarHandle errnoHandle() {
        return errnoHandle;
    }

    protected Arena autoArena() {
        return autoArena;
    }

    protected Arena confinedArena() {return confinedArena;}
}

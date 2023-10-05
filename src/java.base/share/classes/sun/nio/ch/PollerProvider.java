/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
package sun.nio.ch;

import java.io.IOException;
import java.util.ServiceConfigurationError;
import sun.security.action.GetPropertyAction;

abstract class PollerProvider {
    PollerProvider() { }

    /**
     * The default poller mode.
     *
     * Mode 1: Virtual thread (the client) arms file descriptor and parks. ReadPoller and
     * WritePoller threads unpark the virtual thread when file descriptor ready for I/O.
     *
     * Mode 2: Virtual thread (the client) arms file descriptor and parks. ReadPoller and
     * WritePoller threads are virtual threads that poll and yield a few times before
     * parking. If a file descriptor becomes ready for I/O then the client is unparked.
     * A Master Poller unparks the ReadPoller and WritePoller threads when there are I/O
     * events to read.
     */
    int defaultPollerMode() {
        return 1;
    }

    /**
     * Default number of read pollers.
     */
    int defaultReadPollers() {
        return 1;
    }

    /**
     * Default number of write pollers.
     */
    int defaultWritePollers() {
        return 1;
    }

    /**
     * Creates a Poller for read ops.
     */
    abstract Poller readPoller() throws IOException;

    /**
     * Creates a Poller for write ops.
     */
    abstract Poller writePoller() throws IOException;

    /**
     * Creates the PollerProvider.
     */
    static PollerProvider provider() {
        String cn = GetPropertyAction.privilegedGetProperty("jdk.PollerProvider");
        if (cn != null) {
            try {
                Class<?> clazz = Class.forName(cn, true, ClassLoader.getSystemClassLoader());
                return (PollerProvider) clazz.getConstructor().newInstance();
            } catch (Exception e) {
                throw new ServiceConfigurationError(null, e);
            }
        } else {
            return new DefaultPollerProvider();
        }
    }
}

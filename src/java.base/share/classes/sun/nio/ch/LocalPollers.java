/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
import jdk.internal.vm.ContinuationSupport;

/**
 * Whether carrier-local pollers (poller Mode 4) are in use, and their creation.
 * Decided once, when this class initializes: {@code jdk.pollerMode} is absent or
 * "4", continuations are supported, and the platform's {@code PollerProvider}
 * supports local pollers. Mode 4 is thus the default where it is available; an
 * explicit {@code jdk.pollerMode} of 1, 2 or 3 turns it off. A built-in scheduler
 * whose carriers own local pollers creates one per carrier when {@link #enabled()}
 * is true; Poller selects Mode 4 by the same test, for such a scheduler only.
 *
 * <p>This class initializes no Poller: it names only PollerProvider (and through it
 * Poller.Mode), LocalPoller and ContinuationSupport.
 */
public final class LocalPollers {
    private LocalPollers() { }

    // the provider that creates local pollers, or null if they are not in use
    private static final PollerProvider PROVIDER = provider();

    private static PollerProvider provider() {
        // the property and continuations first: no provider is created without them;
        // an absent property means the default, Mode 4 where available
        String mode = System.getProperty("jdk.pollerMode");
        if ((mode != null && !mode.equals("4")) || !ContinuationSupport.isSupported()) {
            return null;
        }
        PollerProvider provider = PollerProvider.createProvider();
        return provider.supportsLocalPoller() ? provider : null;
    }

    /**
     * Returns true if poller Mode 4 is requested, or no mode is, and this platform
     * can provide it.
     */
    public static boolean enabled() {
        return PROVIDER != null;
    }

    /**
     * Creates a local poller, for one carrier thread to own.
     * @throws IOException if the poller cannot be created
     * @throws UnsupportedOperationException if {@link #enabled()} is false
     */
    public static LocalPoller create() throws IOException {
        if (PROVIDER == null) {
            throw new UnsupportedOperationException();
        }
        return PROVIDER.localPoller();
    }
}

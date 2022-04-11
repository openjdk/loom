/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.Selector;
import java.util.concurrent.locks.ReentrantLock;

public class SelectorUtils {

    private static Field SELECTOR_LOCK;
    private static Field SELECTOR_SELECTEDKEY_LOCK;
    private static Method OWNER;

    static {
        try {
            SELECTOR_LOCK = Class.forName("sun.nio.ch.SelectorImpl").getDeclaredField("selectorLock");
            SELECTOR_LOCK.setAccessible(true);
            SELECTOR_SELECTEDKEY_LOCK = Class.forName("sun.nio.ch.SelectorImpl").getDeclaredField("publicSelectedKeys");
            SELECTOR_SELECTEDKEY_LOCK.setAccessible(true);
            OWNER = ReentrantLock.class.getDeclaredMethod("getOwner");
            OWNER.setAccessible(true);
        } catch (Exception e) {
            throw new InternalError(e);
        }
    }

    public static boolean mightHoldSelectorLock(Thread t, Object selector) {
        try {
            ReentrantLock lock = (ReentrantLock) SELECTOR_LOCK.get(selector);
            if (lock == null)
                return false;
            return OWNER.invoke(lock) == t;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean mightHoldKeysLock(Thread t, Object selector) {
        try {
            ReentrantLock lock = (ReentrantLock) SELECTOR_LOCK.get(selector);
            if (lock == null)
                return false;
            return OWNER.invoke(lock) == t;
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * Spin until the monitor of the selected-key set is likely held
     * as selected operations are specified to synchronize on the
     * selected-key set.
     * @param t   the Thread to hold the monitor of the selected-key set
     * @param sel the Selector
     * @throws Exception
     */
    public static void spinUntilLocked(Thread t, Selector sel) throws Exception {
        while (!mightHoldSelectorLock(t, sel)) {
            Thread.sleep(50);
        }
    }
}

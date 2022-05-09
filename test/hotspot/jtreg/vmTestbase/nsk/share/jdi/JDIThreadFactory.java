/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

package nsk.share.jdi;

import java.util.concurrent.ThreadFactory;

/*
    This factory is used to run new threads in debuggee in JDI tests.
 */

public class JDIThreadFactory {

    private static ThreadFactory threadFactory = "Virtual".equals(System.getProperty("main.wrapper"))
            ? virtualThreadFactory() : platformThreadFactory();

    public static Thread newThread(NamedTask task) {
        return newThread(task, task.getName());
    }

    public static Thread newThread(Runnable task) {
        return threadFactory.newThread(task);
    }

    public static Thread newThread(Runnable task, String name) {
        Thread t = threadFactory.newThread(task);
        t.setName(name);
        return t;
    }

    private static ThreadFactory platformThreadFactory() {
        return task -> new Thread(task);
    }

    private static ThreadFactory virtualThreadFactory() {
        try {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            Class<?> clazz = Class.forName("java.lang.Thread$Builder");
            java.lang.reflect.Method factory = clazz.getMethod("factory");
            return (ThreadFactory) factory.invoke(builder);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

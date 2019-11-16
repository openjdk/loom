/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs tasks in virtual threads.
 */

class TestHelper {

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void run(String name, int characteristics, ThrowingRunnable task)
        throws Exception
    {
        characteristics |= Thread.VIRTUAL;
        AtomicReference<Exception> exc = new AtomicReference<>();
        Runnable target =  () -> {
            try {
                task.run();
            } catch (Error e) {
                exc.set(new RuntimeException(e));
            } catch (Exception e) {
                exc.set(e);
            }
        };
        Thread t;
        if (name == null) {
            t = Thread.newThread(characteristics, target);
        } else {
            t = Thread.newThread(name, characteristics, target);
        }
        t.start();
        t.join();
        Exception e = exc.get();
        if (e != null) {
            throw e;
        }
    }

    static void runInVirtualThread(String name, int characteristics, ThrowingRunnable task)
        throws Exception
    {
        run(name, characteristics, task);
    }

    static void runInVirtualThread(int characteristics, ThrowingRunnable task)
        throws Exception
    {
        run(null, characteristics, task);
    }

    static void runInVirtualThread(ThrowingRunnable task) throws Exception {
        run(null, 0, task);
    }

    static void scheduleInterrupt(Thread thread, long delay) {
        Interrupter task  = new Interrupter(thread, delay);
        new Thread(task).start();
    }

    private static class Interrupter implements Runnable {
        final Thread thread;
        final long delay;

        Interrupter(Thread thread, long delay) {
            this.thread = thread;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

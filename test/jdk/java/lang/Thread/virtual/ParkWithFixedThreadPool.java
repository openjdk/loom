/*
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation. THL A29 Limited designates
 * this particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License version 2 for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @run testng ParkWithFixedThreadPool
 * @summary Test virtual thread park when scheduler is fixed thread pool
 */
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import static org.testng.Assert.*;
import org.testng.annotations.Test;

public class ParkWithFixedThreadPool {
    @Test
    public static void multipleThreadPoolParkTest() throws Exception {
        try (ExecutorService scheduler = Executors.newFixedThreadPool(8)) {
            int vt_count = 300;
            Thread[] vts = new Thread[vt_count];
            Runnable target = new Runnable() {
                public void run() {
                    int myIndex = -1;
                    for (int i = 0; i < vt_count; i++) {
                        if (vts[i] == Thread.currentThread()) {
                            myIndex = i;
                            break;
                        }
                    }

                    if (myIndex > 0) {
                        LockSupport.unpark(vts[myIndex - 1]);
                    }

                    if (myIndex != (vt_count - 1)) {
                        LockSupport.park();
                    }
                }
            };

            ThreadFactory f = Thread.ofVirtual().scheduler(scheduler).name("vt", 0).factory();
            for (int i = 0; i < vt_count; i++) {
                vts[i] = f.newThread(target);
            }
            for (int i = 0; i < vt_count; i++) {
                vts[i].start();
            }

            for (int i = 0; i < vt_count; i++) {
                vts[i].join();
            }
        }
    }
}

/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @requires vm.continuations
 * @run main/othervm Interference
 */

import java.lang.management.ManagementFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import jdk.management.VirtualThreadSchedulerMXBean;

public class Interference {

    private enum Mode { SLEEP, SPIN };
    private static volatile boolean done;
    private static volatile Mode mode = Mode.SLEEP;

    public static void main(String[] args) throws Exception {
        int parallelism = Runtime.getRuntime().availableProcessors();
        if (parallelism < 2) {
            return;
        }

        Callable<?> task = () -> {
            while (!done) {
                switch (mode) {
                    case SPIN  -> Thread.onSpinWait();
                    case SLEEP -> Thread.sleep(10);
                }
            }
            return null;
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                IntStream.range(0, parallelism).forEach(_ -> executor.submit(task));

                // oscillate between sleeping and spinning
                for (int i = 1; i <= 10; i++) {
                    System.out.println("--- iteration " + i + " ---");

                    mode = Mode.SLEEP;
                    System.out.println("await mounted < " + parallelism);
                    awaitMounted(n -> n < parallelism);

                    mode = Mode.SPIN;
                    System.out.println("await mounted >= " + parallelism);
                    awaitMounted(n -> n >= parallelism);
                }
            } finally {
                done = true;
            }
        }
    }

    static void awaitMounted(IntPredicate predicate) throws InterruptedException {
        var bean = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
        int attempts = 0;
        for (;;) {
            if (predicate.test(bean.getMountedVirtualThreadCount()))
                return;
            System.out.println(bean);
            if (++attempts > 20)
                throw new RuntimeException("Gave up");
            Thread.sleep(500);
        }
    }
}

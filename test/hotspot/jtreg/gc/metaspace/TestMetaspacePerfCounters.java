/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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

package gc.metaspace;

import java.lang.management.GarbageCollectorMXBean;
import java.util.List;
import java.util.ArrayList;

import jdk.test.lib.ByteCodeLoader;
import jdk.test.lib.compiler.InMemoryJavaCompiler;
import jdk.test.lib.Platform;

import sun.management.ManagementFactoryHelper;

import static jdk.test.lib.Asserts.*;
import gc.testlibrary.PerfCounter;
import gc.testlibrary.PerfCounters;

/* @test id=Serial-64
 * @bug 8014659
 * @requires vm.gc.Serial
 * @requires vm.bits == "64"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -XX:+UsePerfData -XX:+UseSerialGC gc.metaspace.TestMetaspacePerfCounters
 * @run main/othervm -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UsePerfData -XX:+UseSerialGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Parallel-64
 * @bug 8014659
 * @requires vm.gc.Parallel
 * @requires vm.bits == "64"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -XX:+UsePerfData -XX:+UseParallelGC gc.metaspace.TestMetaspacePerfCounters
 * @run main/othervm -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UsePerfData -XX:+UseParallelGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=G1-64
 * @bug 8014659
 * @requires vm.gc.G1
 * @requires vm.bits == "64"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -XX:+UsePerfData -XX:+UseG1GC gc.metaspace.TestMetaspacePerfCounters
 * @run main/othervm -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UsePerfData -XX:+UseG1GC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Shenandoah-64
 * @bug 8014659
 * @requires vm.gc.Shenandoah
 * @requires vm.bits == "64"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -XX:+UsePerfData -XX:+UseShenandoahGC gc.metaspace.TestMetaspacePerfCounters
 * @run main/othervm -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UsePerfData -XX:+UseShenandoahGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Epsilon-64
 * @bug 8014659
 * @requires vm.gc.Epsilon
 * @requires vm.bits == "64"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UnlockExperimentalVMOptions -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -XX:+UsePerfData -XX:+UseEpsilonGC gc.metaspace.TestMetaspacePerfCounters
 * @run main/othervm -XX:+UnlockExperimentalVMOptions -XX:+UseCompressedOops -XX:+UseCompressedClassPointers -XX:+UsePerfData -XX:+UseEpsilonGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Serial-32
 * @bug 8014659
 * @requires vm.gc.Serial
 * @requires vm.bits == "32"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UsePerfData -XX:+UseSerialGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Parallel-32
 * @bug 8014659
 * @requires vm.gc.Parallel
 * @requires vm.bits == "32"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UsePerfData -XX:+UseParallelGC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=G1-32
 * @bug 8014659
 * @requires vm.gc.G1
 * @requires vm.bits == "32"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UsePerfData -XX:+UseG1GC gc.metaspace.TestMetaspacePerfCounters
 */

/* @test id=Shenandoah-32
 * @bug 8014659
 * @requires vm.gc.Shenandoah
 * @requires vm.bits == "32"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UsePerfData -XX:+UseShenandoahGC gc.metaspace.TestMetaspacePerfCounters
 */


/* @test id=Epsilon-32
 * @bug 8014659
 * @requires vm.gc.Epsilon
 * @requires vm.bits == "32"
 * @library /test/lib /
 * @summary Tests that performance counters for metaspace and compressed class
 *          space exists and works.
 * @modules java.base/jdk.internal.misc
 *          java.compiler
 *          java.management/sun.management
 *          jdk.internal.jvmstat/sun.jvmstat.monitor
 * @run main/othervm -XX:+UsePerfData -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC gc.metaspace.TestMetaspacePerfCounters
 */

public class TestMetaspacePerfCounters {
    public static Class<?> fooClass = null;
    private static final String[] counterNames = {"minCapacity", "maxCapacity", "capacity", "used"};
    private static final List<GarbageCollectorMXBean> gcBeans = ManagementFactoryHelper.getGarbageCollectorMXBeans();

    public static void main(String[] args) throws Exception {
        String metaspace = "sun.gc.metaspace";
        String ccs = "sun.gc.compressedclassspace";

        checkPerfCounters(metaspace);

        if (isUsingCompressedClassPointers()) {
            checkPerfCounters(ccs);
            checkUsedIncreasesWhenLoadingClass(ccs);
        } else {
            checkEmptyPerfCounters(ccs);
            checkUsedIncreasesWhenLoadingClass(metaspace);
        }
    }

    private static void checkPerfCounters(String ns) throws Exception {
        long gcCountBefore;
        long gcCountAfter;
        long minCapacity;
        long maxCapacity;
        long capacity;
        long used;

        // The perf counter values are updated during GC and to be able to
        // do the assertions below we need to ensure that the values are from
        // the same GC cycle.
        do {
            gcCountBefore = currentGCCount();

            minCapacity = getMinCapacity(ns);
            maxCapacity = getMaxCapacity(ns);
            capacity = getCapacity(ns);
            used = getUsed(ns);

            gcCountAfter = currentGCCount();
            assertGTE(gcCountAfter, gcCountBefore);
        } while(gcCountAfter > gcCountBefore);

        assertGTE(minCapacity, 0L);
        assertGTE(used, minCapacity);
        assertGTE(capacity, used);
        assertGTE(maxCapacity, capacity);
    }

    private static void checkEmptyPerfCounters(String ns) throws Exception {
        for (PerfCounter counter : countersInNamespace(ns)) {
            String msg = "Expected " + counter.getName() + " to equal 0";
            assertEQ(counter.longValue(), 0L, msg);
        }
    }

    private static void checkUsedIncreasesWhenLoadingClass(String ns) throws Exception {
        // Need to ensure that used is up to date and that all unreachable
        // classes are unloaded before doing this check.
        System.gc();
        long before = getUsed(ns);
        fooClass = compileAndLoad("Foo", "public class Foo { }");
        System.gc();
        long after = getUsed(ns);

        assertGT(after, before);
    }

    private static List<PerfCounter> countersInNamespace(String ns) throws Exception {
        List<PerfCounter> counters = new ArrayList<>();
        for (String name : counterNames) {
            counters.add(PerfCounters.findByName(ns + "." + name));
        }
        return counters;
    }

    private static Class<?> compileAndLoad(String name, String source) throws Exception {
        byte[] byteCode = InMemoryJavaCompiler.compile(name, source);
        return ByteCodeLoader.load(name, byteCode);
    }

    private static boolean isUsingCompressedClassPointers() {
        return Platform.is64bit() && InputArguments.contains("-XX:+UseCompressedClassPointers");
    }

    private static long getMinCapacity(String ns) throws Exception {
        return PerfCounters.findByName(ns + ".minCapacity").longValue();
    }

    private static long getCapacity(String ns) throws Exception {
        return PerfCounters.findByName(ns + ".capacity").longValue();
    }

    private static long getMaxCapacity(String ns) throws Exception {
        return PerfCounters.findByName(ns + ".maxCapacity").longValue();
    }

    private static long getUsed(String ns) throws Exception {
        return PerfCounters.findByName(ns + ".used").longValue();
    }

    private static long currentGCCount() {
        long gcCount = 0;
        for (GarbageCollectorMXBean bean : gcBeans) {
            gcCount += bean.getCollectionCount();
        }
        return gcCount;
    }
}

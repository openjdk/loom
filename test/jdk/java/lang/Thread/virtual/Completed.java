/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @run testng Completed
 * @summary Basic tests for CompletableFuture.completed with a virtual thread executor
 */

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

@Test
public class Completed {

    /**
     * Test the stream returned by the completed method when elements are
     * immediately available.
     */
    public void testBasic1() {
        var cf1 = CompletableFuture.completedFuture("foo");
        var cf2 = CompletableFuture.completedFuture("bar");

        long count = CompletableFuture.completed(cf1, cf2).mapToLong(e -> 1L).sum();
        assertTrue(count == 2);

        Set<String> results = CompletableFuture.completed(cf1, cf2)
                .map(CompletableFuture::join)
                .collect(Collectors.toSet());
        assertEquals(results, Set.of("foo", "bar"));
    }

    /**
     * Test the stream returned by the completed method when elements are
     * not immediately available.
     */
    public void testBasic2() {
        try (var executor = Executors.newVirtualThreadExecutor()) {
            var cf1 = executor.submitTask(() -> {
                Thread.sleep(Duration.ofMillis(500));
                return "foo";
            });
            var cf2 = executor.submitTask(() -> {
                Thread.sleep(Duration.ofSeconds(1));
                return "bar";
            });

            long count = CompletableFuture.completed(cf1, cf2).mapToLong(e -> 1L).sum();
            assertTrue(count == 2);

            Set<String> results = CompletableFuture.completed(cf1, cf2)
                    .map(CompletableFuture::join)
                    .collect(Collectors.toSet());
            assertEquals(results, Set.of("foo", "bar"));
        }
    }

    /**
     * Test waiting on the stream with the interrupt status set.
     */
    public void testInterrupt1() {
        var cf = new CompletableFuture<String>();
        Stream<CompletableFuture<String>> stream = CompletableFuture.completed(cf);
        Thread.currentThread().interrupt();
        try {
            stream.forEach(System.out::println);
            assertTrue(false);
        } catch (CancellationException e) {
            // interrupt status should be set
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear interrupt
        }
    }

    /**
     * Test interruption while waiting on the stream.
     */
    public void testInterrupt2() {
        var cf = new CompletableFuture<String>();
        Stream<CompletableFuture<String>> stream = CompletableFuture.completed(cf);
        Thread thread = Thread.currentThread();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException ignore) { }
            thread.interrupt();
        });
        try {
            stream.forEach(System.out::println);
            assertTrue(false);
        } catch (CancellationException e) {
            // interrupt status should be set
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear interrupt
        }
    }

    public void testEmpty1() {
        long count = CompletableFuture.completed().mapToLong(e -> 1L).sum();
        assertTrue(count == 0);
    }

    public void testEmpty2() {
        long count = CompletableFuture.completed(Set.of()).mapToLong(e -> 1L).sum();
        assertTrue(count == 0);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull1() {
        Collection<? extends CompletableFuture<String>> cfs = null;
        CompletableFuture.completed(cfs);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull2() {
        var cfs = new ArrayList<CompletableFuture<String>>();
        cfs.add(CompletableFuture.completedFuture("foo"));
        cfs.add(null);
        CompletableFuture.completed(cfs);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull3() {
        CompletableFuture<String>[] cfs = null;
        CompletableFuture.completed(cfs);
    }

    @Test(expectedExceptions = { NullPointerException.class })
    public void testNull4() {
        CompletableFuture<String>[] cfs = new CompletableFuture[2];
        cfs[0] = CompletableFuture.completedFuture("foo");
        cfs[1] = null;
        CompletableFuture.completed(cfs);
    }

}
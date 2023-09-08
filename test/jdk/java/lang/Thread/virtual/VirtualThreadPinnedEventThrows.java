/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test parking when pinned and emitting the JFR VirtualThreadPinnedEvent throws
 * @modules java.base/jdk.internal.event
 * @compile/module=java.base jdk/internal/event/VirtualThreadPinnedEvent.java
 * @run junit VirtualThreadPinnedEventThrows
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.lang.management.ManagementFactory;

import jdk.jfr.EventType;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.lang.ref.Reference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.event.VirtualThreadPinnedEvent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadPinnedEventThrows {

    /**
     * Test parking when pinned and creating the VirtualThreadPinnedEvent fails with OOME.
     */
    @Test
    void testVirtualThreadPinnedEventCreateThrows() throws Exception {
        VirtualThreadPinnedEvent.setCreateThrows(true);
        try {
            testParkWhenPinned();
        } finally {
            VirtualThreadPinnedEvent.setCreateThrows(false);
        }
    }

    /**
     * Test parking when pinned and VirtualThreadPinnedEvent.begin fails with OOME.
     */
    @Test
    void testVirtualThreadPinnedEventBeginThrows() throws Exception {
        VirtualThreadPinnedEvent.setBeginThrows(true);
        try {
            testParkWhenPinned();
        } finally {
            VirtualThreadPinnedEvent.setBeginThrows(false);
        }
    }

    /**
     * Test parking when pinned and VirtualThreadPinnedEvent.commit fails with OOME.
     */
    @Test
    void testVirtualThreadPinnedEventCommitThrows() throws Exception {
        VirtualThreadPinnedEvent.setCommitThrows(true);
        try {
            testParkWhenPinned();
        } finally {
            VirtualThreadPinnedEvent.setCommitThrows(false);
        }
    }

    /**
     * Test parking a virtual thread when pinned.
     */
    private void testParkWhenPinned() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned");
            recording.start();

            Object lock = new Object();
            try {
                var completed = new AtomicBoolean();
                Thread thread = Thread.startVirtualThread(() -> {
                    synchronized (lock) {
                        LockSupport.park();
                        completed.set(true);
                    }
                });

                // wait for thread to park
                Thread.State state;
                while ((state = thread.getState()) != Thread.State.WAITING) {
                    assertTrue(state != Thread.State.TERMINATED);
                    Thread.sleep(10);
                }

                // unpark and check that thread completed without exception
                LockSupport.unpark(thread);
                thread.join();
                assertTrue(completed.get());
            } finally {
                Reference.reachabilityFence(lock);
            }

            recording.stop();

            Map<String, Integer> events = sumEvents(recording);
            int virtualThreadPinnedEventCount = events.getOrDefault("jdk.VirtualThreadPinned", 0);
            assertEquals(0, virtualThreadPinnedEventCount);
        }
    }

    /**
     * Read the events from the recording and return a map of event name to count.
     */
    private static Map<String, Integer> sumEvents(Recording recording) throws IOException {
        Path recordingFile = recordingFile(recording);
        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile);
        return events.stream()
                .map(RecordedEvent::getEventType)
                .collect(Collectors.groupingBy(EventType::getName,
                        Collectors.summingInt(x -> 1)));
    }

    /**
     * Return the file path to the recording file.
     */
    private static Path recordingFile(Recording recording) throws IOException {
        Path recordingFile = recording.getDestination();
        if (recordingFile == null) {
            File directory = new File(".");
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            recordingFile = new File(directory.getAbsolutePath(), "recording-" + recording.getId() + "-pid" + pid + ".jfr").toPath();
            recording.dump(recordingFile);
        }
        return recordingFile;
    }
}

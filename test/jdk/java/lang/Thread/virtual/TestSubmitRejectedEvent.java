/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Basic test for JFR VirtualThreadSubmitRejected event
 * @run testng/othervm TestSubmitRejectedEvent
 */

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class TestSubmitRejectedEvent {

    /**
     * Basic test of jdk.VirtualThreadSubmitRejected
     */
    @Test
    public void testEvent() throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadSubmitRejected");

            int nEventsExpected;
            recording.start();
            try {
                nEventsExpected = doStuff();
            } finally {
                recording.stop();
            }

            // process recording file and check that the expected events were recorded
            Path recordingFile = recordingFile(recording);
            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile);
            int nEventsRecorded = events.size();
            if (nEventsRecorded != nEventsExpected) {
                String msg = String.format("Recorded %d events, expected %s",
                        nEventsRecorded, nEventsExpected);
                throw new RuntimeException(msg);
            }
            for (RecordedEvent e : events) {
                System.out.println(e);
            }
        }
    }

    static int doStuff() throws Exception {
        try (ExecutorService pool = Executors.newCachedThreadPool()) {
            Executor scheduler = task -> pool.execute(task);
            ThreadFactory factory = Thread.ofVirtual().scheduler(scheduler).factory();

            // start a thread
            Thread thread = factory.newThread(LockSupport::park);
            thread.start();

            // give time for thread to park
            boolean terminated = thread.join(Duration.ofMillis(1000));
            assertFalse(terminated);

            // shutdown scheduler
            pool.shutdown();

            // unpark, it should fail and an event should be recorded
            try {
                LockSupport.unpark(thread);
                assertTrue(false);
            } catch (RejectedExecutionException expected) { }

            // start another thread, it should fail and an event should be recorded
            try {
                factory.newThread(LockSupport::park).start();
                throw new RuntimeException("RejectedExecutionException expected");
            } catch (RejectedExecutionException expected) { }

            // two events should be recorded
            return 2;
        }
    }

    static Path recordingFile(Recording recording) throws IOException {
        Path recordingFile = recording.getDestination();
        if (recordingFile == null) {
            ProcessHandle h = ProcessHandle.current();
            recordingFile = Path.of("recording-" + recording.getId() + "-pid" + h.pid() + ".jfr");
            recording.dump(recordingFile);
        }
        return recordingFile;
    }
}


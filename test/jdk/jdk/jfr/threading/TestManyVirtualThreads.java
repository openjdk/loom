package jdk.jfr.threading;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Tests starting virtual threads from a set of ordinary threads
 * @key jfr
 * @requires vm.hasJFR
 * @library /test/lib /test/jdk
 * @modules jdk.jfr/jdk.jfr.internal
 * @run main/othervm jdk.jfr.threading.TestManyVirtualThreads
 */
public class TestManyVirtualThreads {

    private static final int VIRTUAL_THREAD_COUNT = 100_000;
    private static final int STARTER_THREADS = 10;

    @Name("test.Tester")
    private static class TestEvent extends Event {
    }

    public static void main(String... args) throws Exception {
        try (Recording r = new Recording()) {
            r.start();

            ThreadFactory factory = Thread.ofVirtual().factory();
            CompletableFuture<?>[] c = new CompletableFuture[STARTER_THREADS];
            for (int j = 0; j < STARTER_THREADS; j++) {
                c[j] = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < VIRTUAL_THREAD_COUNT / STARTER_THREADS; i++) {
                        try {
                            Thread vt = factory.newThread(TestManyVirtualThreads::emitEvent);
                            vt.start();
                            vt.join();
                        } catch (InterruptedException ie) {
                            ie.printStackTrace();
                        }
                    }
                });
            }
            for (int j = 0; j < STARTER_THREADS; j++) {
                c[j].get();
            }

            r.stop();
            Path p = Files.createTempFile("test", ".jfr");
            r.dump(p);
            long size = Files.size(p);
            Asserts.assertLessThan(size, 100_000_000L, "Size of recording looks suspicious large");
            System.out.println("File size: " + size);
            List<RecordedEvent> events = RecordingFile.readAllEvents(p);
            Asserts.assertEquals(events.size(), VIRTUAL_THREAD_COUNT, "Expected " + VIRTUAL_THREAD_COUNT + " events");
            for (RecordedEvent e : events) {
                RecordedThread t = e.getThread();
                Asserts.assertNotNull(t);
                Asserts.assertTrue(t.isVirtual());
                Asserts.assertEquals(t.getJavaName(), "<unnamed>");
                Asserts.assertEquals(t.getOSName(), "<unnamed>");
                Asserts.assertEquals(t.getThreadGroup().getName(), "VirtualThreads");
                Asserts.assertGreaterThan(t.getJavaThreadId(), 0L);
                Asserts.assertGreaterThan(t.getOSThreadId(), 0L);
            }
        }
    }

    private static void emitEvent() {
        TestEvent t = new TestEvent();
        t.commit();
    }
}

package jdk.jfr.threading;

import java.util.List;
import java.util.concurrent.ThreadFactory;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.test.lib.Asserts;
import jdk.test.lib.jfr.Events;

/**
 * @test
 * @summary Tests committing an event in a virtual thread created by a virtual
 *          thread
 * @key jfr
 * @requires vm.hasJFR
 * @library /test/lib /test/jdk
 * @modules jdk.jfr/jdk.jfr.internal
 * @run main/othervm jdk.jfr.threading.TestNestedVirtualThreads
 */
public class TestNestedVirtualThreads {
    @Name("test.Nested")
    private static class NestedEvent extends Event {
    }

    public static void main(String... args) throws Exception {
        try (Recording r = new Recording()) {
            r.start();
            ThreadFactory factory1 = Thread.builder().virtual().factory();
            Thread vt1 = factory1.newThread(() -> {
                ThreadFactory factory2 = Thread.builder().virtual().factory();
                Thread vt2 = factory2.newThread(() -> {
                    NestedEvent event = new NestedEvent();
                    event.commit();
                });
                vt2.start();
                try {
                    vt2.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
            vt1.start();
            vt1.join();
            r.stop();
            List<RecordedEvent> events = Events.fromRecording(r);
            Events.hasEvents(events);
            System.out.println(events.get(0));
            RecordedEvent e = events.get(0);
            RecordedThread t = e.getThread();
            Asserts.assertTrue(t.isVirtual());
            Asserts.assertEquals(t.getJavaName(), "<unnamed>");
            Asserts.assertEquals(t.getOSName(), "<unnamed>");
            Asserts.assertEquals(t.getThreadGroup().getName(), "VirtualThreads");
            Asserts.assertGreaterThan(t.getJavaThreadId(), 0L);
            Asserts.assertGreaterThan(t.getOSThreadId(), 0L);
        }
    }
}

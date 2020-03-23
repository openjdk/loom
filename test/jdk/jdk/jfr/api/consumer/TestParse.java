package jdk.jfr.api.consumer;

import java.nio.file.Paths;

import jdk.jfr.consumer.RecordingFile;

public class TestParse {
    public static RecordedEvent event;

    public static void main(String... args) {
        RecordingFile rf = new RecordingFile(Paths.get(args[0]));
        while (rf.hasMoreEvents()) {
            event = rf.readEvent();
        }
    }
}

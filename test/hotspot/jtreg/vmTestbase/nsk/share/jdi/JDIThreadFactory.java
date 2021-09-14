package nsk.share.jdi;

import java.util.concurrent.ThreadFactory;

public class JDIThreadFactory {

    private static ThreadFactory threadFactory = "Virtual".equals(System.getProperty("main.wrapper"))
        ? Thread.ofVirtual().factory() : Thread.ofPlatform().factory();

    public static Thread newThread(Runnable task) {
        System.out.printf("AAAAAAAAAAAAAAAAAAAA " + threadFactory.toString());
        return threadFactory.newThread(task);
    }
}

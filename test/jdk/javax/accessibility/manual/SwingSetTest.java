/**
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

import lib.ManualTestFrame;
import lib.TestResult;

import java.util.function.Consumer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.Supplier;
import javax.swing.JEditorPane;

import static java.io.File.separator;

public class SwingSetTest {

    public static void main(String[] args) throws IOException, InterruptedException,
            ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        System.out.println("test image " + System.getenv("TEST_IMAGE_DIR"));

        Consumer<JEditorPane> testInstructionProvider = e -> {
            try {
                e.setContentType("text/html");
                e.setPage(SwingSetTest.class.getResource(args[0] + ".html"));
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        };

        Supplier<TestResult> resultSupplier = ManualTestFrame.showUI(args[0],
                "Wait for SwingSet2 to load, follow the instructions, select pass or fail. " +
                        "Do not close windows manually.",
                testInstructionProvider);

        String swingSetJar = System.getenv("SWINGSET2_JAR");
        if (swingSetJar == null) {
            swingSetJar = "file://" + System.getProperty("java.home") +
                    separator + "demo" +
                    separator + "jfc" +
                    separator + "SwingSet2" +
                    separator + "SwingSet2.jar";
        }
        System.out.println("Loading SwingSet2 from " + swingSetJar);
        ClassLoader ss = new URLClassLoader(new URL[]{new URL(swingSetJar)});
        ss.loadClass("SwingSet2").getMethod("main", String[].class).invoke(null, (Object)new String[0]);

        //this will block until user decision to pass or fail the test
        TestResult result = resultSupplier.get();
        ManualTestFrame.handleResult(result, args[0]);
    }
}


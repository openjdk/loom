
/*
 * Copyright (c) 2013, 2020, Oracle and/or its affiliates. All rights reserved.
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

package jdk.test.lib.process;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import jdk.test.lib.JDKToolFinder;
import jdk.test.lib.Platform;
import jdk.test.lib.Utils;

public final class ProcessTools {
    private static final class LineForwarder extends StreamPumper.LinePump {
        private final PrintStream ps;
        private final String prefix;
        LineForwarder(String prefix, PrintStream os) {
            this.ps = os;
            this.prefix = prefix;
        }
        @Override
        protected void processLine(String line) {
            ps.println("[" + prefix + "] " + line);
        }
    }

    private ProcessTools() {
    }

    /**
     * <p>Starts a process from its builder.</p>
     * <span>The default redirects of STDOUT and STDERR are started</span>
     * @param name The process name
     * @param processBuilder The process builder
     * @return Returns the initialized process
     * @throws IOException
     */
    public static Process startProcess(String name,
                                       ProcessBuilder processBuilder)
    throws IOException {
        return startProcess(name, processBuilder, (Consumer<String>)null);
    }

    /**
     * <p>Starts a process from its builder.</p>
     * <span>The default redirects of STDOUT and STDERR are started</span>
     * <p>It is possible to monitor the in-streams via the provided {@code consumer}
     * @param name The process name
     * @param consumer {@linkplain Consumer} instance to process the in-streams
     * @param processBuilder The process builder
     * @return Returns the initialized process
     * @throws IOException
     */
    @SuppressWarnings("overloads")
    public static Process startProcess(String name,
                                       ProcessBuilder processBuilder,
                                       Consumer<String> consumer)
    throws IOException {
        try {
            return startProcess(name, processBuilder, consumer, null, -1, TimeUnit.NANOSECONDS);
        } catch (InterruptedException | TimeoutException e) {
            // will never happen
            throw new RuntimeException(e);
        }
    }

    /**
     * <p>Starts a process from its builder.</p>
     * <span>The default redirects of STDOUT and STDERR are started</span>
     * <p>
     * It is possible to wait for the process to get to a warmed-up state
     * via {@linkplain Predicate} condition on the STDOUT
     * </p>
     * @param name The process name
     * @param processBuilder The process builder
     * @param linePredicate The {@linkplain Predicate} to use on the STDOUT
     *                      Used to determine the moment the target app is
     *                      properly warmed-up.
     *                      It can be null - in that case the warmup is skipped.
     * @param timeout The timeout for the warmup waiting; -1 = no wait; 0 = wait forever
     * @param unit The timeout {@linkplain TimeUnit}
     * @return Returns the initialized {@linkplain Process}
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    public static Process startProcess(String name,
                                       ProcessBuilder processBuilder,
                                       final Predicate<String> linePredicate,
                                       long timeout,
                                       TimeUnit unit)
    throws IOException, InterruptedException, TimeoutException {
        return startProcess(name, processBuilder, null, linePredicate, timeout, unit);
    }

    /**
     * <p>Starts a process from its builder.</p>
     * <span>The default redirects of STDOUT and STDERR are started</span>
     * <p>
     * It is possible to wait for the process to get to a warmed-up state
     * via {@linkplain Predicate} condition on the STDOUT and monitor the
     * in-streams via the provided {@linkplain Consumer}
     * </p>
     * @param name The process name
     * @param processBuilder The process builder
     * @param lineConsumer  The {@linkplain Consumer} the lines will be forwarded to
     * @param linePredicate The {@linkplain Predicate} to use on the STDOUT
     *                      Used to determine the moment the target app is
     *                      properly warmed-up.
     *                      It can be null - in that case the warmup is skipped.
     * @param timeout The timeout for the warmup waiting; -1 = no wait; 0 = wait forever
     * @param unit The timeout {@linkplain TimeUnit}
     * @return Returns the initialized {@linkplain Process}
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    public static Process startProcess(String name,
                                       ProcessBuilder processBuilder,
                                       final Consumer<String> lineConsumer,
                                       final Predicate<String> linePredicate,
                                       long timeout,
                                       TimeUnit unit)
    throws IOException, InterruptedException, TimeoutException {
        System.out.println("["+name+"]:" + processBuilder.command().stream().collect(Collectors.joining(" ")));
        Process p = privilegedStart(processBuilder);
        StreamPumper stdout = new StreamPumper(p.getInputStream());
        StreamPumper stderr = new StreamPumper(p.getErrorStream());

        stdout.addPump(new LineForwarder(name, System.out));
        stderr.addPump(new LineForwarder(name, System.err));
        if (lineConsumer != null) {
            StreamPumper.LinePump pump = new StreamPumper.LinePump() {
                @Override
                protected void processLine(String line) {
                    lineConsumer.accept(line);
                }
            };
            stdout.addPump(pump);
            stderr.addPump(pump);
        }


        CountDownLatch latch = new CountDownLatch(1);
        if (linePredicate != null) {
            StreamPumper.LinePump pump = new StreamPumper.LinePump() {
                @Override
                protected void processLine(String line) {
                    if (latch.getCount() > 0 && linePredicate.test(line)) {
                        latch.countDown();
                    }
                }
            };
            stdout.addPump(pump);
            stderr.addPump(pump);
        } else {
            latch.countDown();
        }
        final Future<Void> stdoutTask = stdout.process();
        final Future<Void> stderrTask = stderr.process();

        try {
            if (timeout > -1) {
                if (timeout == 0) {
                    latch.await();
                } else {
                    if (!latch.await(Utils.adjustTimeout(timeout), unit)) {
                        throw new TimeoutException();
                    }
                }
            }
        } catch (TimeoutException | InterruptedException e) {
            System.err.println("Failed to start a process (thread dump follows)");
            for(Map.Entry<Thread, StackTraceElement[]> s : Thread.getAllStackTraces().entrySet()) {
                printStack(s.getKey(), s.getValue());
            }

            if (p.isAlive()) {
                p.destroyForcibly();
            }

            stdoutTask.cancel(true);
            stderrTask.cancel(true);
            throw e;
        }

        return new ProcessImpl(p, stdoutTask, stderrTask);
    }

    /**
     * <p>Starts a process from its builder.</p>
     * <span>The default redirects of STDOUT and STDERR are started</span>
     * <p>
     * It is possible to wait for the process to get to a warmed-up state
     * via {@linkplain Predicate} condition on the STDOUT. The warm-up will
     * wait indefinitely.
     * </p>
     * @param name The process name
     * @param processBuilder The process builder
     * @param linePredicate The {@linkplain Predicate} to use on the STDOUT
     *                      Used to determine the moment the target app is
     *                      properly warmed-up.
     *                      It can be null - in that case the warmup is skipped.
     * @return Returns the initialized {@linkplain Process}
     * @throws IOException
     * @throws InterruptedException
     * @throws TimeoutException
     */
    @SuppressWarnings("overloads")
    public static Process startProcess(String name,
                                       ProcessBuilder processBuilder,
                                       final Predicate<String> linePredicate)
    throws IOException, InterruptedException, TimeoutException {
        return startProcess(name, processBuilder, linePredicate, 0, TimeUnit.SECONDS);
    }

    /**
     * Get the process id of the current running Java process
     *
     * @return Process id
     */
    public static long getProcessId() throws Exception {
        return ProcessHandle.current().pid();
    }



    /**
     * Create ProcessBuilder using the java launcher from the jdk to be tested and
     * with any platform specific arguments prepended
     */
    public static ProcessBuilder createJavaProcessBuilder(String... command) {
        return createJavaProcessBuilder(false, command);
    }

    /**
     * Create ProcessBuilder using the java launcher from the jdk to be tested,
     * and with any platform specific arguments prepended.
     *
     * @param addTestVmAndJavaOptions If true, adds test.vm.opts and test.java.opts
     *        to the java arguments.
     * @param command Arguments to pass to the java command.
     * @return The ProcessBuilder instance representing the java command.
     */
    public static ProcessBuilder createJavaProcessBuilder(boolean addTestVmAndJavaOptions, String... command) {
        String javapath = JDKToolFinder.getJDKTool("java");

        ArrayList<String> args = new ArrayList<>();
        args.add(javapath);

        args.add("-cp");
        args.add(System.getProperty("java.class.path"));

        if (addTestVmAndJavaOptions) {
            Collections.addAll(args, Utils.getTestJavaOpts());
        }

        boolean noModule = true;
        for (String cmd: command) {
            if (cmd.equals("-m")) {
                noModule = false;
            }
        }

        String[] doubleWordArgs = {"-cp", "-classpath", "--add-opens", "--class-path", "--upgrade-module-path",
                                   "--add-modules", "-d", "--add-exports", "--patch-module", "--module-path"};

        if (noModule && System.getProperty("main.wrapper") != null) {
            boolean skipNext = false;
            boolean added = false;
            for (String cmd : command) {
                if (added) {
                    args.add(cmd);
                    continue;
                }

                if (skipNext) {
                    skipNext = false;
                    args.add(cmd);
                    continue;
                }
                for (String dWArg : doubleWordArgs) {
                    if (cmd.equals(dWArg)) {
                        skipNext = true;
                        args.add(cmd);
                        continue;
                    }
                }
                if (skipNext) {
                    continue;
                }
                if (cmd.startsWith("-cp")) {
                    skipNext = true;
                    args.add(cmd);
                    continue;
                }
                if (cmd.startsWith("--add-exports")) {
                    skipNext = true;
                    args.add(cmd);
                    continue;
                }
                if (cmd.startsWith("--patch-module")) {
                    skipNext = true;
                    args.add(cmd);
                    continue;
                }
                if (cmd.startsWith("-")) {
                    args.add(cmd);
                    continue;
                }
                args.add("jdk.test.lib.process.ProcessTools");
                args.add(System.getProperty("main.wrapper"));
                added = true;
                // Should be main
                // System.out.println("Wrapped TOFIND: " + cmd);
                args.add(cmd);
            }
        } else {
            Collections.addAll(args, command);
        }

        // Reporting
        StringBuilder cmdLine = new StringBuilder();
        for (String cmd : args)
            cmdLine.append(cmd).append(' ');
        System.out.println("Command line: [" + cmdLine.toString() + "]");

        return new ProcessBuilder(args.toArray(new String[args.size()]));
    }

    private static void printStack(Thread t, StackTraceElement[] stack) {
        System.out.println("\t" +  t +
                           " stack: (length = " + stack.length + ")");
        if (t != null) {
            for (StackTraceElement stack1 : stack) {
                System.out.println("\t" + stack1);
            }
            System.out.println();
        }
    }

    /**
     * Executes a test jvm process, waits for it to finish and returns the process output.
     * The default jvm options from jtreg, test.vm.opts and test.java.opts, are added.
     * The java from the test.jdk is used to execute the command.
     *
     * The command line will be like:
     * {test.jdk}/bin/java {test.vm.opts} {test.java.opts} cmds
     *
     * The jvm process will have exited before this method returns.
     *
     * @param cmds User specified arguments.
     * @return The output from the process.
     */
    public static OutputAnalyzer executeTestJvm(String... cmds) throws Exception {
        ProcessBuilder pb = createJavaProcessBuilder(Utils.addTestJavaOpts(cmds));
        return executeProcess(pb);
    }

    /**
     * @see #executeTestJvm(String...)
     * @param cmds User specified arguments.
     * @return The output from the process.
     */
    public static OutputAnalyzer executeTestJava(String... cmds) throws Exception {
        return executeTestJvm(cmds);
    }

    /**
     * Executes a process, waits for it to finish and returns the process output.
     * The process will have exited before this method returns.
     * @param pb The ProcessBuilder to execute.
     * @return The {@linkplain OutputAnalyzer} instance wrapping the process.
     */
    public static OutputAnalyzer executeProcess(ProcessBuilder pb) throws Exception {
        return executeProcess(pb, null);
    }

    /**
     * Executes a process, pipe some text into its STDIN, waits for it
     * to finish and returns the process output. The process will have exited
     * before this method returns.
     * @param pb The ProcessBuilder to execute.
     * @param input The text to pipe into STDIN. Can be null.
     * @return The {@linkplain OutputAnalyzer} instance wrapping the process.
     */
    public static OutputAnalyzer executeProcess(ProcessBuilder pb, String input) throws Exception {
        return executeProcess(pb, input, null);
    }

    /**
     * Executes a process, pipe some text into its STDIN, waits for it
     * to finish and returns the process output. The process will have exited
     * before this method returns.
     * @param pb The ProcessBuilder to execute.
     * @param input The text to pipe into STDIN. Can be null.
     * @param cs The charset used to convert from bytes to chars or null for
     *           the default charset.
     * @return The {@linkplain OutputAnalyzer} instance wrapping the process.
     */
    public static OutputAnalyzer executeProcess(ProcessBuilder pb, String input,
            Charset cs) throws Exception {
        OutputAnalyzer output = null;
        Process p = null;
        boolean failed = false;
        try {
            p = privilegedStart(pb);
            if (input != null) {
               try (PrintStream ps = new PrintStream(p.getOutputStream())) {
                   ps.print(input);
               }
            }

            output = new OutputAnalyzer(p, cs);
            p.waitFor();

            return output;
        } catch (Throwable t) {
            if (p != null) {
                p.destroyForcibly().waitFor();
            }

            failed = true;
            System.out.println("executeProcess() failed: " + t);
            throw t;
        } finally {
            if (failed) {
                System.err.println(getProcessLog(pb, output));
            }
        }
    }

    /**
     * Executes a process, waits for it to finish and returns the process output.
     *
     * The process will have exited before this method returns.
     *
     * @param cmds The command line to execute.
     * @return The output from the process.
     */
    public static OutputAnalyzer executeProcess(String... cmds) throws Throwable {
        return executeProcess(new ProcessBuilder(cmds));
    }

    /**
     * Used to log command line, stdout, stderr and exit code from an executed process.
     * @param pb The executed process.
     * @param output The output from the process.
     */
    public static String getProcessLog(ProcessBuilder pb, OutputAnalyzer output) {
        String stderr = output == null ? "null" : output.getStderr();
        String stdout = output == null ? "null" : output.getStdout();
        String exitValue = output == null ? "null": Integer.toString(output.getExitValue());
        StringBuilder logMsg = new StringBuilder();
        final String nl = System.getProperty("line.separator");
        logMsg.append("--- ProcessLog ---" + nl);
        logMsg.append("cmd: " + getCommandLine(pb) + nl);
        logMsg.append("exitvalue: " + exitValue + nl);
        logMsg.append("stderr: " + stderr + nl);
        logMsg.append("stdout: " + stdout + nl);

        return logMsg.toString();
    }

    /**
     * @return The full command line for the ProcessBuilder.
     */
    public static String getCommandLine(ProcessBuilder pb) {
        if (pb == null) {
            return "null";
        }
        StringBuilder cmd = new StringBuilder();
        for (String s : pb.command()) {
            cmd.append(s).append(" ");
        }
        return cmd.toString().trim();
    }

    /**
     * Executes a process, waits for it to finish, prints the process output
     * to stdout, and returns the process output.
     *
     * The process will have exited before this method returns.
     *
     * @param cmds The command line to execute.
     * @return The {@linkplain OutputAnalyzer} instance wrapping the process.
     */
    public static OutputAnalyzer executeCommand(String... cmds)
            throws Throwable {
        String cmdLine = Arrays.stream(cmds).collect(Collectors.joining(" "));
        System.out.println("Command line: [" + cmdLine + "]");
        OutputAnalyzer analyzer = ProcessTools.executeProcess(cmds);
        System.out.println(analyzer.getOutput());
        return analyzer;
    }

    /**
     * Executes a process, waits for it to finish, prints the process output
     * to stdout and returns the process output.
     *
     * The process will have exited before this method returns.
     *
     * @param pb The ProcessBuilder to execute.
     * @return The {@linkplain OutputAnalyzer} instance wrapping the process.
     */
    public static OutputAnalyzer executeCommand(ProcessBuilder pb)
            throws Throwable {
        String cmdLine = pb.command().stream()
                .map(x -> (x.contains(" ") || x.contains("$"))
                        ? ("'" + x + "'") : x)
                .collect(Collectors.joining(" "));
        System.out.println("Command line: [" + cmdLine + "]");
        OutputAnalyzer analyzer = ProcessTools.executeProcess(pb);
        System.out.println(analyzer.getOutput());
        return analyzer;
    }

    /**
     * Helper method to create a process builder for launching native executable
     * test that uses/loads JVM.
     *
     * @param executableName The name of an executable to be launched.
     * @param args Arguments for the executable.
     * @return New ProcessBuilder instance representing the command.
     */
    public static ProcessBuilder createNativeTestProcessBuilder(String executableName,
                                                                String... args) throws Exception {
        executableName = Platform.isWindows() ? executableName + ".exe" : executableName;
        String executable = Paths.get(System.getProperty("test.nativepath"), executableName)
            .toAbsolutePath()
            .toString();

        ProcessBuilder pb = new ProcessBuilder(executable);
        pb.command().addAll(Arrays.asList(args));
        addJvmLib(pb);
        return pb;
    }

    /**
     * Adds JVM library path to the native library path.
     *
     * @param pb ProcessBuilder to be updated with JVM library path.
     * @return pb Update ProcessBuilder instance.
     */
    public static ProcessBuilder addJvmLib(ProcessBuilder pb) throws Exception {
        String jvmLibDir = Platform.jvmLibDir().toString();
        String libPathVar = Platform.sharedLibraryPathVariableName();
        String currentLibPath = pb.environment().get(libPathVar);

        String newLibPath = jvmLibDir;
        if ( (currentLibPath != null) && !currentLibPath.isEmpty() ) {
            newLibPath = currentLibPath + File.pathSeparator + jvmLibDir;
        }

        pb.environment().put(libPathVar, newLibPath);

        return pb;
    }

    private static Process privilegedStart(ProcessBuilder pb) throws IOException {
        try {
            return AccessController.doPrivileged(
                (PrivilegedExceptionAction<Process>) () -> pb.start());
        } catch (PrivilegedActionException e) {
            IOException t = (IOException) e.getException();
            throw t;
        }
    }

    private static class ProcessImpl extends Process {

        private final Process p;
        private final Future<Void> stdoutTask;
        private final Future<Void> stderrTask;

        public ProcessImpl(Process p, Future<Void> stdoutTask, Future<Void> stderrTask) {
            this.p = p;
            this.stdoutTask = stdoutTask;
            this.stderrTask = stderrTask;
        }

        @Override
        public OutputStream getOutputStream() {
            return p.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return p.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return p.getErrorStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            int rslt = p.waitFor();
            waitForStreams();
            return rslt;
        }

        @Override
        public int exitValue() {
            return p.exitValue();
        }

        @Override
        public void destroy() {
            p.destroy();
        }

        @Override
        public long pid() {
            return p.pid();
        }

        @Override
        public boolean isAlive() {
            return p.isAlive();
        }

        @Override
        public Process destroyForcibly() {
            return p.destroyForcibly();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            boolean rslt = p.waitFor(timeout, unit);
            if (rslt) {
                waitForStreams();
            }
            return rslt;
        }

        private void waitForStreams() throws InterruptedException {
            try {
                stdoutTask.get();
            } catch (ExecutionException e) {
            }
            try {
                stderrTask.get();
            } catch (ExecutionException e) {
            }
        }
    }

    // ProcessTools as a wrapper
    public static void main(String[] args) throws Throwable {
        String wrapper = args[0];
        String className = args[1];
        String[] classArgs = new String[args.length - 2];
        System.arraycopy(args, 2, classArgs, 0, args.length - 2);
        Class c = Class.forName(className);
        Method mainMethod = c.getMethod("main", new Class[] { String[].class });

        if (wrapper.equals("Virtual")) {
            MainThreadGroup tg = new MainThreadGroup();
            // TODO fix to set virtual scheduler group when become available
            Thread vthread = Thread.builder().virtual().task(() -> {
                    try {
                        mainMethod.invoke(null, new Object[] { classArgs });
                    } catch (Throwable error) {
                        tg.uncaughtThrowable = error;
                    }
                }).build();
            vthread.start();
            vthread.join();
        } else if (wrapper.equals("Kernel")) {
            MainThreadGroup tg = new MainThreadGroup();
            Thread t = new Thread(tg, () -> {
                    try {
                        mainMethod.invoke(null, new Object[] { classArgs });
                    } catch (Throwable error) {
                        tg.uncaughtThrowable = error;
                    }
                });
            t.start();
            t.join();
            if (tg.uncaughtThrowable != null) {
                throw new RuntimeException(tg.uncaughtThrowable);
            }
        } else {
            mainMethod.invoke(null, new Object[] { classArgs });
        }
    }

    static class MainThreadGroup extends ThreadGroup {
        MainThreadGroup() {
            super("MainThreadGroup");
        }

        public void uncaughtException(Thread t, Throwable e) {
            if (e instanceof ThreadDeath) {
                return;
            }
            e.printStackTrace(System.err);
            uncaughtThrowable = e;
        }
        Throwable uncaughtThrowable = null;
    }
}

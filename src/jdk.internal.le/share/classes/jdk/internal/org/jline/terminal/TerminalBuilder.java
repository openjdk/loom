/*
 * Copyright (c) 2002-2020, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package jdk.internal.org.jline.terminal;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jdk.internal.org.jline.terminal.impl.AbstractPosixTerminal;
import jdk.internal.org.jline.terminal.impl.AbstractTerminal;
import jdk.internal.org.jline.terminal.impl.DumbTerminal;
import jdk.internal.org.jline.terminal.impl.ExecPty;
import jdk.internal.org.jline.terminal.impl.ExternalTerminal;
import jdk.internal.org.jline.terminal.impl.PosixPtyTerminal;
import jdk.internal.org.jline.terminal.impl.PosixSysTerminal;
import jdk.internal.org.jline.terminal.spi.JansiSupport;
import jdk.internal.org.jline.terminal.spi.JnaSupport;
import jdk.internal.org.jline.terminal.spi.Pty;
import jdk.internal.org.jline.utils.Log;
import jdk.internal.org.jline.utils.OSUtils;

/**
 * Builder class to create terminals.
 */
public final class TerminalBuilder {

    //
    // System properties
    //

    public static final String PROP_ENCODING = "org.jline.terminal.encoding";
    public static final String PROP_CODEPAGE = "org.jline.terminal.codepage";
    public static final String PROP_TYPE = "org.jline.terminal.type";
    public static final String PROP_JNA = "org.jline.terminal.jna";
    public static final String PROP_JANSI = "org.jline.terminal.jansi";
    public static final String PROP_EXEC = "org.jline.terminal.exec";
    public static final String PROP_DUMB = "org.jline.terminal.dumb";
    public static final String PROP_DUMB_COLOR = "org.jline.terminal.dumb.color";

    //
    // Other system properties controlling various jline parts
    //

    public static final String PROP_NON_BLOCKING_READS = "org.jline.terminal.pty.nonBlockingReads";
    public static final String PROP_COLOR_DISTANCE = "org.jline.utils.colorDistance";
    public static final String PROP_DISABLE_ALTERNATE_CHARSET = "org.jline.utils.disableAlternateCharset";

    /**
     * Returns the default system terminal.
     * Terminals should be closed properly using the {@link Terminal#close()}
     * method in order to restore the original terminal state.
     *
     * <p>
     * This call is equivalent to:
     * <code>builder().build()</code>
     * </p>
     *
     * @return the default system terminal
     * @throws IOException if an error occurs
     */
    public static Terminal terminal() throws IOException {
        return builder().build();
    }

    /**
     * Creates a new terminal builder instance.
     *
     * @return a builder
     */
    public static TerminalBuilder builder() {
        return new TerminalBuilder();
    }

    private static final AtomicReference<Terminal> SYSTEM_TERMINAL = new AtomicReference<>();
    private static final AtomicReference<Terminal> TERMINAL_OVERRIDE = new AtomicReference<>();

    private String name;
    private InputStream in;
    private OutputStream out;
    private String type;
    private Charset encoding;
    private int codepage;
    private Boolean system;
    private Boolean jna;
    private Boolean jansi;
    private Boolean exec;
    private Boolean dumb;
    private Boolean color;
    private Attributes attributes;
    private Size size;
    private boolean nativeSignals = false;
    private Function<InputStream, InputStream> inputStreamWrapper = in -> in;
    private Terminal.SignalHandler signalHandler = Terminal.SignalHandler.SIG_DFL;
    private boolean paused = false;

    private TerminalBuilder() {
    }

    public TerminalBuilder name(String name) {
        this.name = name;
        return this;
    }

    public TerminalBuilder streams(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
        return this;
    }

    public TerminalBuilder system(boolean system) {
        this.system = system;
        return this;
    }

    public TerminalBuilder jna(boolean jna) {
        this.jna = jna;
        return this;
    }

    public TerminalBuilder jansi(boolean jansi) {
        this.jansi = jansi;
        return this;
    }

    public TerminalBuilder exec(boolean exec) {
        this.exec = exec;
        return this;
    }

    public TerminalBuilder dumb(boolean dumb) {
        this.dumb = dumb;
        return this;
    }

    public TerminalBuilder type(String type) {
        this.type = type;
        return this;
    }

    public TerminalBuilder color(boolean color) {
        this.color = color;
        return this;
    }

    /**
     * Set the encoding to use for reading/writing from the console.
     * If {@code null} (the default value), JLine will automatically select
     * a {@link Charset}, usually the default system encoding. However,
     * on some platforms (e.g. Windows) it may use a different one depending
     * on the {@link Terminal} implementation.
     *
     * <p>Use {@link Terminal#encoding()} to get the {@link Charset} that
     * should be used for a {@link Terminal}.</p>
     *
     * @param encoding The encoding to use or null to automatically select one
     * @return The builder
     * @throws UnsupportedCharsetException If the given encoding is not supported
     * @see Terminal#encoding()
     */
    public TerminalBuilder encoding(String encoding) throws UnsupportedCharsetException {
        return encoding(encoding != null ? Charset.forName(encoding) : null);
    }

    /**
     * Set the {@link Charset} to use for reading/writing from the console.
     * If {@code null} (the default value), JLine will automatically select
     * a {@link Charset}, usually the default system encoding. However,
     * on some platforms (e.g. Windows) it may use a different one depending
     * on the {@link Terminal} implementation.
     *
     * <p>Use {@link Terminal#encoding()} to get the {@link Charset} that
     * should be used to read/write from a {@link Terminal}.</p>
     *
     * @param encoding The encoding to use or null to automatically select one
     * @return The builder
     * @see Terminal#encoding()
     */
    public TerminalBuilder encoding(Charset encoding) {
        this.encoding = encoding;
        return this;
    }

    /**
     * @param codepage the codepage
     * @return The builder
     * @deprecated JLine now writes Unicode output independently from the selected
     *   code page. Using this option will only make it emulate the selected code
     *   page for {@link Terminal#input()} and {@link Terminal#output()}.
     */
    @Deprecated
    public TerminalBuilder codepage(int codepage) {
        this.codepage = codepage;
        return this;
    }

    /**
     * Attributes to use when creating a non system terminal,
     * i.e. when the builder has been given the input and
     * output streams using the {@link #streams(InputStream, OutputStream)} method
     * or when {@link #system(boolean)} has been explicitly called with
     * <code>false</code>.
     *
     * @param attributes the attributes to use
     * @return The builder
     * @see #size(Size)
     * @see #system(boolean)
     */
    public TerminalBuilder attributes(Attributes attributes) {
        this.attributes = attributes;
        return this;
    }

    /**
     * Initial size to use when creating a non system terminal,
     * i.e. when the builder has been given the input and
     * output streams using the {@link #streams(InputStream, OutputStream)} method
     * or when {@link #system(boolean)} has been explicitly called with
     * <code>false</code>.
     *
     * @param size the initial size
     * @return The builder
     * @see #attributes(Attributes)
     * @see #system(boolean)
     */
    public TerminalBuilder size(Size size) {
        this.size = size;
        return this;
    }

    public TerminalBuilder nativeSignals(boolean nativeSignals) {
        this.nativeSignals = nativeSignals;
        return this;
    }

    public TerminalBuilder signalHandler(Terminal.SignalHandler signalHandler) {
        this.signalHandler = signalHandler;
        return this;
    }

    public TerminalBuilder inputStreamWrapper(Function<InputStream, InputStream> wrapper) {
        this.inputStreamWrapper = wrapper;
        return this;
    }

    /**
     * Initial paused state of the terminal (defaults to false).
     * By default, the terminal is started, but in some cases,
     * one might want to make sure the input stream is not consumed
     * before needed, in which case the terminal needs to be created
     * in a paused state.
     * @param paused the initial paused state
     * @return The builder
     * @see Terminal#pause()
     */
    public TerminalBuilder paused(boolean paused) {
        this.paused = paused;
        return this;
    }

    public Terminal build() throws IOException {
        Terminal override = TERMINAL_OVERRIDE.get();
        Terminal terminal = override != null ? override : doBuild();
        if (override != null) {
            Log.debug(() -> "Overriding terminal with global value set by TerminalBuilder.setTerminalOverride");
        }
        Log.debug(() -> "Using terminal " + terminal.getClass().getSimpleName());
        if (terminal instanceof AbstractPosixTerminal) {
            Log.debug(() -> "Using pty " + ((AbstractPosixTerminal) terminal).getPty().getClass().getSimpleName());
        }
        return terminal;
    }

    private Terminal doBuild() throws IOException {
        String name = this.name;
        if (name == null) {
            name = "JLine terminal";
        }
        Charset encoding = this.encoding;
        if (encoding == null) {
            String charsetName = System.getProperty(PROP_ENCODING);
            if (charsetName != null && Charset.isSupported(charsetName)) {
                encoding = Charset.forName(charsetName);
            }
        }
        int codepage = this.codepage;
        if (codepage <= 0) {
            String str = System.getProperty(PROP_CODEPAGE);
            if (str != null) {
                codepage = Integer.parseInt(str);
            }
        }
        String type = this.type;
        if (type == null) {
            type = System.getProperty(PROP_TYPE);
        }
        if (type == null) {
            type = System.getenv("TERM");
        }
        Boolean jna = this.jna;
        if (jna == null) {
            jna = getBoolean(PROP_JNA, true);
        }
        Boolean jansi = this.jansi;
        if (jansi == null) {
            jansi = getBoolean(PROP_JANSI, true);
        }
        Boolean exec = this.exec;
        if (exec == null) {
            exec = getBoolean(PROP_EXEC, true);
        }
        Boolean dumb = this.dumb;
        if (dumb == null) {
            dumb = getBoolean(PROP_DUMB, null);
        }
        if ((system != null && system) || (system == null && in == null && out == null)) {
            if (system != null && ((in != null && !in.equals(System.in)) ||  (out != null && !out.equals(System.out)))) {
                throw new IllegalArgumentException("Cannot create a system terminal using non System streams");
            }
            Terminal terminal = null;
            IllegalStateException exception = new IllegalStateException("Unable to create a system terminal");
            TerminalBuilderSupport tbs = new TerminalBuilderSupport(jna, jansi);
            if (tbs.isConsoleInput() && tbs.isConsoleOutput()) {
                if (attributes != null || size != null) {
                    Log.warn("Attributes and size fields are ignored when creating a system terminal");
                }
                if (OSUtils.IS_WINDOWS) {
                    if (!OSUtils.IS_CYGWIN && !OSUtils.IS_MSYSTEM) {
                        boolean ansiPassThrough = OSUtils.IS_CONEMU;
                        if (tbs.hasJnaSupport()) {
                            try {
                                terminal = tbs.getJnaSupport().winSysTerminal(name, type, ansiPassThrough, encoding, codepage
                                        , nativeSignals, signalHandler, paused, inputStreamWrapper);
                            } catch (Throwable t) {
                                Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                                exception.addSuppressed(t);
                            }
                        }
                        if (terminal == null && tbs.hasJansiSupport()) {
                            try {
                                terminal = tbs.getJansiSupport().winSysTerminal(name, type, ansiPassThrough, encoding, codepage
                                        , nativeSignals, signalHandler, paused);
                            } catch (Throwable t) {
                                Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                                exception.addSuppressed(t);
                            }
                        }
                    } else if (exec) {
                        //
                        // Cygwin support
                        //
                        try {
                            // Cygwin defaults to XTERM, but actually supports 256 colors,
                            // so if the value comes from the environment, change it to xterm-256color
                            if ("xterm".equals(type) && this.type == null && System.getProperty(PROP_TYPE) == null) {
                                type = "xterm-256color";
                            }
                            Pty pty = tbs.getExecPty();
                            terminal = new PosixSysTerminal(name, type, pty, inputStreamWrapper.apply(pty.getSlaveInput()), pty.getSlaveOutput(), encoding, nativeSignals, signalHandler);
                        } catch (IOException e) {
                            // Ignore if not a tty
                            Log.debug("Error creating EXEC based terminal: ", e.getMessage(), e);
                            exception.addSuppressed(e);
                        }
                    }
                    if (terminal == null && !jna && !jansi && (dumb == null || !dumb)) {
                        throw new IllegalStateException("Unable to create a system terminal. On windows, either "
                                + "JNA or JANSI library is required.  Make sure to add one of those in the classpath.");
                    }
                } else {
                    if (tbs.hasJnaSupport()) {
                        try {
                            Pty pty = tbs.getJnaSupport().current();
                            terminal = new PosixSysTerminal(name, type, pty, inputStreamWrapper.apply(pty.getSlaveInput()), pty.getSlaveOutput(), encoding, nativeSignals, signalHandler);
                        } catch (Throwable t) {
                            // ignore
                            Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                            exception.addSuppressed(t);
                        }
                    }
                    if (terminal == null && tbs.hasJansiSupport()) {
                        try {
                            Pty pty = tbs.getJansiSupport().current();
                            terminal = new PosixSysTerminal(name, type, pty, inputStreamWrapper.apply(pty.getSlaveInput()), pty.getSlaveOutput(), encoding, nativeSignals, signalHandler);
                        } catch (Throwable t) {
                            Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                            exception.addSuppressed(t);
                        }
                    }
                    if (terminal == null && exec) {
                        try {
                            Pty pty = tbs.getExecPty();
                            terminal = new PosixSysTerminal(name, type, pty, inputStreamWrapper.apply(pty.getSlaveInput()), pty.getSlaveOutput(), encoding, nativeSignals, signalHandler);
                        } catch (Throwable t) {
                            // Ignore if not a tty
                            Log.debug("Error creating EXEC based terminal: ", t.getMessage(), t);
                            exception.addSuppressed(t);
                        }
                    }
                }
                if (terminal instanceof AbstractTerminal) {
                    AbstractTerminal t = (AbstractTerminal) terminal;
                    if (SYSTEM_TERMINAL.compareAndSet(null, t)) {
                        t.setOnClose(() -> SYSTEM_TERMINAL.compareAndSet(t, null));
                    } else {
                        exception.addSuppressed(new IllegalStateException("A system terminal is already running. " +
                                "Make sure to use the created system Terminal on the LineReaderBuilder if you're using one " +
                                "or that previously created system Terminals have been correctly closed."));
                        terminal.close();
                        terminal = null;
                    }
                }
            }
            if (terminal == null && (dumb == null || dumb)) {
                // forced colored dumb terminal
                Boolean color = this.color;
                if (color == null) {
                    color = getBoolean(PROP_DUMB_COLOR, false);
                    // detect emacs using the env variable
                    if (!color) {
                        color = System.getenv("INSIDE_EMACS") != null;
                    }
                    // detect Intellij Idea
                    if (!color) {
                        String command = getParentProcessCommand();
                        color = command != null && command.contains("idea");
                    }
                    if (!color) {
                        color = tbs.isConsoleOutput() && System.getenv("TERM") != null;
                    }
                    if (!color && dumb == null) {
                        if (Log.isDebugEnabled()) {
                            Log.warn("input is tty: {}", tbs.isConsoleInput());
                            Log.warn("output is tty: {}", tbs.isConsoleOutput());
                            Log.warn("Creating a dumb terminal", exception);
                        } else {
                            Log.warn("Unable to create a system terminal, creating a dumb terminal (enable debug logging for more information)");
                        }
                    }
                }
                terminal = new DumbTerminal(name, color ? Terminal.TYPE_DUMB_COLOR : Terminal.TYPE_DUMB,
                        inputStreamWrapper.apply(new FileInputStream(FileDescriptor.in)),
                        new FileOutputStream(FileDescriptor.out),
                        encoding, signalHandler);
            }
            if (terminal == null) {
                throw exception;
            }
            return terminal;
        } else {
            if (jna) {
                try {
                    Pty pty = load(JnaSupport.class).open(attributes, size);
                    return new PosixPtyTerminal(name, type, pty, inputStreamWrapper.apply(in), out, encoding, signalHandler, paused);
                } catch (Throwable t) {
                    Log.debug("Error creating JNA based terminal: ", t.getMessage(), t);
                }
            }
            if (jansi) {
                try {
                    Pty pty = load(JansiSupport.class).open(attributes, size);
                    return new PosixPtyTerminal(name, type, pty, inputStreamWrapper.apply(in), out, encoding, signalHandler, paused);
                } catch (Throwable t) {
                    Log.debug("Error creating JANSI based terminal: ", t.getMessage(), t);
                }
            }
            return new ExternalTerminal(name, type, inputStreamWrapper.apply(in), out, encoding, signalHandler, paused, attributes, size);
        }
    }

    private static String getParentProcessCommand() {
        try {
            Class<?> phClass = Class.forName("java.lang.ProcessHandle");
            Object current = phClass.getMethod("current").invoke(null);
            Object parent = ((Optional<?>) phClass.getMethod("parent").invoke(current)).orElse(null);
            Method infoMethod = phClass.getMethod("info");
            Object info = infoMethod.invoke(parent);
            Object command = ((Optional<?>) infoMethod.getReturnType().getMethod("command").invoke(info)).orElse(null);
            return (String) command;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean getBoolean(String name, Boolean def) {
        try {
            String str = System.getProperty(name);
            if (str != null) {
                return Boolean.parseBoolean(str);
            }
        } catch (IllegalArgumentException | NullPointerException e) {
        }
        return def;
    }

    private static <S> S load(Class<S> clazz) {
        return ServiceLoader.load(clazz, clazz.getClassLoader()).iterator().next();
    }

    /**
     * Allows an application to override the result of {@link #build()}. The
     * intended use case is to allow a container or server application to control
     * an embedded application that uses a LineReader that uses Terminal
     * constructed with TerminalBuilder.build but provides no public api for setting
     * the <code>LineReader</code> of the {@link Terminal}. For example, the sbt
     * build tool uses a <code>LineReader</code> to implement an interactive shell.
     * One of its supported commands is <code>console</code> which invokes
     * the scala REPL. The scala REPL also uses a <code>LineReader</code> and it
     * is necessary to override the {@link Terminal} used by the the REPL to
     * share the same {@link Terminal} instance used by sbt.
     *
     * <p>
     * When this method is called with a non-null {@link Terminal}, all subsequent
     * calls to {@link #build()} will return the provided {@link Terminal} regardless
     * of how the {@link TerminalBuilder} was constructed. The default behavior
     * of {@link TerminalBuilder} can be restored by calling setTerminalOverride
     * with a null {@link Terminal}
     * </p>
     *
     * <p>
     * Usage of setTerminalOverride should be restricted to cases where it
     * isn't possible to update the api of the nested application to accept
     * a {@link Terminal instance}.
     * </p>
     *
     * @param terminal the {@link Terminal} to globally override
     */
    @Deprecated
    public static void setTerminalOverride(final Terminal terminal) {
        TERMINAL_OVERRIDE.set(terminal);
    }

    private static class TerminalBuilderSupport {
        private JansiSupport jansiSupport = null;
        private JnaSupport jnaSupport = null;
        private Pty pty = null;
        private boolean consoleOutput;

        TerminalBuilderSupport(boolean jna, boolean jansi) {
            if (jna) {
                try {
                    jnaSupport = load(JnaSupport.class);
                    consoleOutput = jnaSupport.isConsoleOutput();
                } catch (Throwable e) {
                    jnaSupport = null;
                    Log.debug("jnaSupport.isConsoleOutput(): ", e);
                }
            }
            if (jansi) {
                try {
                    jansiSupport = load(JansiSupport.class);
                    consoleOutput = jansiSupport.isConsoleOutput();
                } catch (Throwable e) {
                    jansiSupport = null;
                    Log.debug("jansiSupport.isConsoleOutput(): ", e);
                }
            }
            if (jnaSupport == null && jansiSupport == null) {
                try {
                    pty = ExecPty.current();
                    consoleOutput = true;
                } catch (Exception e) {
                    Log.debug("ExecPty.current(): ", e);
                }
            }
        }

        public boolean isConsoleOutput() {
            return consoleOutput;
        }

        public boolean isConsoleInput() {
            if (pty != null) {
                return true;
            } else if (hasJnaSupport()) {
                return jnaSupport.isConsoleInput();
            } else if (hasJansiSupport()) {
                return jansiSupport.isConsoleInput();
            } else {
                return false;
            }
        }

        public boolean hasJnaSupport() {
            return jnaSupport != null;
        }

        public boolean hasJansiSupport() {
            return jansiSupport != null;
        }

        public JnaSupport getJnaSupport() {
            return jnaSupport;
        }

        public JansiSupport getJansiSupport() {
            return jansiSupport;
        }

        public Pty getExecPty() throws IOException {
            if (pty == null) {
                pty = ExecPty.current();
            }
            return pty;
        }

    }
}

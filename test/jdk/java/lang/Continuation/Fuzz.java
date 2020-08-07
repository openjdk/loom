/*
* Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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



/*
 * @test
 * @summary Fuzz tests for java.lang.Continuation
 *
 * @modules java.base java.base/jdk.internal.vm.annotation
 * @library /test/lib
 * @build java.base/java.lang.StackWalkerHelper
 * @build sun.hotspot.WhiteBox
 * @run driver ClassFileInstaller sun.hotspot.WhiteBox
 *
 * @run main/othervm/timeout=300 -XX:-UseContinuationLazyCopy -XX:-UseContinuationChunks -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. Fuzz
 * @run main/othervm/timeout=300 -XX:-UseContinuationLazyCopy -XX:+UseContinuationChunks -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. Fuzz
 * @run main/othervm/timeout=300 -XX:+UseContinuationLazyCopy -XX:-UseContinuationChunks -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. Fuzz
 * @run main/othervm/timeout=300 -XX:+UseContinuationLazyCopy -XX:+UseContinuationChunks -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:. Fuzz
 *
 */

// Anything excluded or not compileonly is not compiled; see CompilerOracle::should_exclude

// @run driver jdk.test.lib.FileInstaller compilerDirectives.json compilerDirectives.json
// -XX:CompilerDirectivesFile=compilerDirectives.json

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.StackWalker.StackFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.lang.Math.max;
import static java.lang.Math.min;

import jdk.internal.vm.annotation.DontInline;
import jdk.test.lib.Utils;
import sun.hotspot.WhiteBox;

public class Fuzz implements Runnable {
    static final boolean VERBOSE = false;
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    private static boolean COMPILE_RUN;
    private static int COMPILE_LEVEL;

    public static void main(String[] args) {
        for (int compileLevel : new int[]{4})
            for (boolean compileRun : new boolean[]{true})
                test(compileLevel, compileRun);
    }

    static void test(int compileLevel, boolean compileRun) {
        resetCompilation();
        COMPILE_LEVEL = compileLevel;
        COMPILE_RUN = compileRun;

        testFile();
        testRandom();
    }

    static void testFile() {
        System.out.println("-- FILE --");
        try {
            testStream(file(Path.of(System.getProperty("test.src", ".")).resolve("fuzz.dat")));
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    static void testRandom() {
        long seed = 1L; // System.currentTimeMillis();
        System.out.println("-- RANDOM (seed: " + seed + ") --");
        testStream(random(new Random(seed)).limit(50));
    }

    static Stream<Op[]> file(Path file) throws IOException {
        return Files.lines(file).map(String::trim).filter(s -> !s.isBlank() && !s.startsWith("#")).map(Fuzz::parse);
    }

    static Stream<Op[]> random(Random rnd) {
        var g = new Generator(rnd);
        return Stream.iterate(0, x->x+1).map(__ -> g.generate());
    }

    static void testStream(Stream<Op[]> traces) {
        traces.forEach(Fuzz::testTrace);
    }

    static void testTrace(Op[] trace) {
        System.out.println();
        System.out.println("COMPILE_LEVEL: " + COMPILE_LEVEL + " COMPILE_RUN: " + COMPILE_RUN);
 
        int retry = 0;
        for (;;) {
            compile();

            var fuzz = new Fuzz(trace);
            fuzz.verbose = VERBOSE && retry == 0;
            fuzz.print();

            fuzz.test();

            Op[] newTrace = Arrays.copyOf(trace, trace.length);
            if (!checkCompilation(newTrace)) {
                System.out.println("CHANGED COMPILATION AFTER");
                printTrace(newTrace);
                if (retry++ < 2) {
                    System.out.println("RETRYING");
                    continue;
                }
            }
            break;
        }
    }

    ////////////////

    enum Op {
        CALL_I_INT, CALL_I_DBL, CALL_I_MANY, 
        CALL_C_INT, CALL_C_DBL, CALL_C_MANY, 
        CALL_I_CTCH, CALL_C_CTCH,
        CALL_I_PIN, CALL_C_PIN,
        MH_I_INT, MH_C_INT, MH_I_MANY, MH_C_MANY,
        REF_I_INT, REF_C_INT, REF_I_MANY, REF_C_MANY,
        LOOP, YIELD, THROW, DONE;

        static final EnumSet<Op> BASIC       = EnumSet.of(LOOP, YIELD);
        static final EnumSet<Op> STANDARD    = EnumSet.range(CALL_I_INT, CALL_C_CTCH);
        static final EnumSet<Op> PIN         = EnumSet.range(CALL_I_PIN, CALL_C_PIN);
        static final EnumSet<Op> MH          = EnumSet.range(MH_I_INT, MH_C_MANY);
        static final EnumSet<Op> REFLECTED   = EnumSet.range(REF_I_INT, REF_C_MANY);
        static final EnumSet<Op> NON_CALLS   = EnumSet.range(LOOP, DONE);
        static final EnumSet<Op> COMPILED    = EnumSet.copyOf(Arrays.stream(Op.values()).filter(x -> x.toString().contains("_C_")).collect(Collectors.toList()));
        static final EnumSet<Op> INTERPRETED = EnumSet.copyOf(Arrays.stream(Op.values()).filter(x -> x.toString().contains("_I_")).collect(Collectors.toList()));

        static Op toInterpreted(Op op) { return INTERPRETED.contains(op) ? op : Enum.valueOf(Op.class, op.toString().replace("_C_", "_I_")); }
        static Op toCompiled(Op op)    { return COMPILED.contains(op)    ? op : Enum.valueOf(Op.class, op.toString().replace("_I_", "_C_")); }

        static final Op[] ARRAY = new Op[0];
    }

    ///// Trace Gnereation

    static class Generator {
        public Op[] generate() {
            final int length = max(1, pick(5, 10, 50/*, 200*/) + plusOrMinus(5));

            Set<Op> highProb = new HashSet<Op>();
            Set<Op> lowProb  = new HashSet<Op>();

            if (percent(100)) highProb.addAll(Op.BASIC);
            if (percent(100)) highProb.addAll(Op.STANDARD);
            if (percent(1)) lowProb.add(Op.THROW);
            if (percent(3)) lowProb.addAll(Op.PIN);
            if (percent(3)) lowProb.addAll(Op.MH);
            if (percent(0)) lowProb.addAll(Op.REFLECTED);
            if (percent(90)) {
                highProb.removeAll(Op.INTERPRETED);
                lowProb.removeAll(Op.INTERPRETED);
            }
            Op[] highProb0 = highProb.toArray(Op.ARRAY);
            Op[] lowProb0  = lowProb.toArray(Op.ARRAY);
            
            Op[] trace = new Op[length];
            for (int i=0; i < trace.length; i++) {
                trace[i] = pick((lowProb.isEmpty() || percent(90)) ? highProb0 : lowProb0);
            }
            return trace;
        }

        private final Random rnd;
        public Generator(Random rnd) { this.rnd = rnd; }
        @SafeVarargs
        private <T> T pick(T... values) { return values[rnd.nextInt(values.length)]; }
        private boolean percent(int percent) { return rnd.nextInt(100) < percent; }
        private int plusOrMinus(int n) { return rnd.nextInt(2*n + 1) - n; }
    }


    ////////////////////////////////////////

    static final ContinuationScope SCOPE = new ContinuationScope() {};

    static class FuzzException extends RuntimeException {
        public FuzzException(String msg) { super(msg); }
    }

    boolean verbose = false;

    private final Op[] trace;
    private int index;
    private int result = -1;

    private Fuzz(Op[] trace) {
        this.trace = trace;
        this.index = -1;
    }

    void print() { printTrace(trace); }

    private Op trace(int i) { return i < trace.length ? trace[i] : Op.DONE; }
    private Op current()    { return trace(index); }
    private Op next(int c)  { logOp(c); index++; return current(); }

    void test() {
        Continuation cont = new Continuation(SCOPE, this) {
            @Override protected void onPinned(Pinned reason) { if (verbose) System.out.println("PINNED " + reason); }
        };

        try {
            while (true) {
                cont.run();
                if (cont.isDone()) break;

                assert !shouldThrow();
                verifyStack(cont);
            }
            verifyResult(result);
        } catch (FuzzException e) {
            assert shouldThrow();
            assert e.getMessage().equals("EX");
            assert cont.isDone();
        }
    }

    /////////// Instance Helpers

    private StackTraceElement[] backtrace;
    private StackFrame[] fbacktrace;
    private StackFrame[] lfbacktrace;

    void indent(int depth) {
        // depth = index;
        for (int i=0; i<depth; i++) System.out.print("  ");
    }

    void logOp(int iter) {
        if (!verbose) return;
        
        int depth = depth();
        System.out.print("> " + depth + " ");
        indent(depth);
        System.out.println("iter: " + iter + " index: " + index + " op: " + trace(index+1));
    }

    <T> T log(T result) {
        if (!verbose) return result;
        
        int depth = depth();
        System.out.print("> " + depth + " ");
        indent(depth);
        System.out.println("result " + result);
        return result;
    }

    int depth() {
        int d = 0;
        for (int i=0; i<=index && i < trace.length; i++) if (!Op.NON_CALLS.contains(trace[i])) d++;
        return d;
    }

    String[] expectedStackTrace() {
        var ms = new ArrayList<String>();
        for (int i = index; i >= 0; i--) if (!Op.NON_CALLS.contains(trace[i])) ms.add(method(trace[i]).getName());
        ms.add("run");
        return ms.toArray(new String[0]);
    }

    int computeResult() {
        // To compute the expected result, we remove all YIELDs from the trace and run it
        Op[] trace0 = Arrays.stream(trace).filter(op -> op != Op.YIELD)
            .collect(Collectors.toList()).toArray(Op.ARRAY);
        
        Fuzz f0 = new Fuzz(trace0);
        f0.run();
        return f0.result;
    }

    void verifyResult(int result) {
        int computed = computeResult();
        assert result == computed : "result: " + result + " expected: " + computed;
    }

    boolean shouldPin() {
        for (int i = 0; i < index; i++)
            if (trace[i] == Op.CALL_I_PIN || trace[i] == Op.CALL_C_PIN) return true;
        return false;
    }

    void verifyPin(boolean yieldResult) {
        assert yieldResult != shouldPin() : "res: " + yieldResult + " shouldPin: " + shouldPin();
    }

    boolean shouldThrow() {
        for (int i = 0; i <= index && i < trace.length; i++) {
            switch (trace[i]) {
                case CALL_I_CTCH, CALL_C_CTCH -> { return false; }
                case THROW -> { return true; }
            }
        }
        return false;
    }

    void captureStack() {
        backtrace = Thread.currentThread().getStackTrace();
        fbacktrace = StackWalkerHelper.getStackFrames(SCOPE);
        lfbacktrace = StackWalkerHelper.getLiveStackFrames(SCOPE);
    }

    void verifyStack() {
        verifyStack(backtrace);
        verifyStack(backtrace, StackWalkerHelper.toStackTraceElement(fbacktrace));
        verifyStack(fbacktrace, lfbacktrace);

        verifyStack(backtrace, Thread.currentThread().getStackTrace());
        verifyStack(fbacktrace, StackWalkerHelper.getStackFrames(SCOPE));
        verifyStack(lfbacktrace, StackWalkerHelper.getLiveStackFrames(SCOPE));
    }

    void verifyStack(Continuation cont) {
        verifyStack(backtrace);
        verifyStack(backtrace, StackWalkerHelper.toStackTraceElement(fbacktrace));
        verifyStack(fbacktrace, lfbacktrace);
        
        verifyStack(backtrace, cont.getStackTrace());
        verifyStack(fbacktrace, StackWalkerHelper.getStackFrames(cont));
        verifyStack(lfbacktrace, StackWalkerHelper.getLiveStackFrames(cont));
    }

    static boolean isStackCaptureMechanism(Object sf) {
        return Fuzz.class.getName().equals(sfClassName(sf)) 
            && ("captureStack".equals(sfMethodName(sf)) || "verifyStack".equals(sfMethodName(sf)));
    }

    static boolean isPrePostYield(Object sf) {
        return Fuzz.class.getName().equals(sfClassName(sf))
            && ("preYield".equals(sfMethodName(sf)) || "postYield".equals(sfMethodName(sf)));
    }

    static <T> T[] cutStack(T[] stack) {
        var list = new ArrayList<T>();
        int i = 0;
        while (i < stack.length && (!Fuzz.class.getName().equals(sfClassName(stack[i])) || isPrePostYield(stack[i]) || isStackCaptureMechanism(stack[i]))) i++;
        while (i < stack.length && !Continuation.class.getName().equals(sfClassName(stack[i]))) { list.add(stack[i]); i++; }
        // while (i < stack.length && Continuation.class.getName().equals(sfClassName(stack[i])) && !"enterSpecial".equals(sfMethodName(stack[i]))) { list.add(stack[i]); i++; }
        return list.toArray(arrayType(stack));
    }

    void verifyStack(Object[] observed) {
        verifyStack(
            expectedStackTrace(),
            Arrays.stream(cutStack(observed)).filter(sf -> Fuzz.class.getName().equals(sfClassName(sf)))
                            .collect(Collectors.toList()).toArray(new Object[0]));
    }

    static void verifyStack(Object[] expected, Object[] observed) {
        expected = cutStack(expected);
        observed = cutStack(observed);
        boolean equal = true;
        if (expected.length == observed.length) {
            for (int i=0; i < expected.length; i++) {
                if (!sfEquals(expected[i], observed[i])) {
                    // we allow a different line number for the first element
                    if (i > 0 || !Objects.equals(sfClassName(expected[i]), sfClassName(observed[i])) || !Objects.equals(sfMethodName(expected[i]), sfMethodName(observed[i]))) {
                        System.out.println("At index " + i + " expected: " + sfToString(expected[i]) + " observed: " + sfToString(observed[i]));

                        equal = false;
                        break;
                    }
                }
            }
        } else {
            equal = false;
            System.out.println("Expected length: " + expected.length + " Observed length: " + observed.length);
        }
        if (!equal) {
            System.out.println("Expected: "); for (var sf : expected) System.out.println("\t" + sf);
            System.out.println("Observed: "); for (var sf : observed) System.out.println("\t" + sf);
        }
        assert equal;
    }

    static String sfClassName(Object f)  {
        return f instanceof String ? Fuzz.class.getName() :
            (f instanceof StackTraceElement ? ((StackTraceElement)f).getClassName()  : ((StackFrame)f).getClassName()); }
    static String sfMethodName(Object f) { 
        return f instanceof String ? (String)f :
            (f instanceof StackTraceElement ? ((StackTraceElement)f).getMethodName() : ((StackFrame)f).getMethodName()); }

    static boolean sfEquals(Object a, Object b) {
        if (a instanceof String)
            return sfClassName(a).equals(sfClassName(b)) && sfMethodName(a).equals(sfMethodName(b));
        
        return a instanceof StackTraceElement ? Objects.equals(a, b)
                                              : StackWalkerHelper.equals((StackFrame)a, (StackFrame)b);
    }

    static String sfToString(Object f) { 
        return f instanceof StackFrame ? StackWalkerHelper.frameToString((StackFrame)f) : Objects.toString(f);
    }

    ////// Static Helpers

    static void resetCompilation() {
        Set<Method> compile = Op.COMPILED.stream().map(Fuzz::method).collect(Collectors.toCollection(HashSet::new));
        compile.add(run);

        for (Method m : compile) {
            WB.deoptimizeMethod(m);
            WB.clearMethodState(m);
        }
    }

    static void compileContinuation() {
        var compile = new HashSet<Method>();
        for (Method m : Continuation.class.getDeclaredMethods()) {
            if (!WB.isMethodCompiled(m)) {
                if (!Modifier.isNative(m.getModifiers()) 
                    && (m.getName().startsWith("enter")
                     || m.getName().startsWith("yield"))) {
                    WB.enqueueMethodForCompilation(m, COMPILE_LEVEL);
                    compile.add(m);
                }
            }
        }

        for (Method m : compile) Utils.waitForCondition(() -> WB.isMethodCompiled(m));
    }

    static void compile() {
        final long start = System.nanoTime();

        compileContinuation();

        Set<Method> compile   =    Op.COMPILED.stream().map(Fuzz::method).collect(Collectors.toCollection(HashSet::new));
        Set<Method> interpret = Op.INTERPRETED.stream().map(Fuzz::method).collect(Collectors.toCollection(HashSet::new));
        (COMPILE_RUN ? compile : interpret).add(run);

        compile.addAll(precompile);

        for (Method m : interpret) WB.makeMethodNotCompilable(m);

        for (Method m : compile) if (!WB.isMethodCompiled(m)) WB.enqueueMethodForCompilation(m, COMPILE_LEVEL);
        for (Method m : compile) Utils.waitForCondition(() -> WB.isMethodCompiled(m));

        for (Method m : compile)   assert  WB.isMethodCompiled(m) : "method: " + m;
        for (Method m : interpret) assert !WB.isMethodCompiled(m) : "method: " + m;

        final long duration = (System.nanoTime() - start)/1_000_000;
        if (duration > 500)
            System.out.println("Compile in " + duration + " ms");
    }

    static boolean checkCompilation(Op[] trace) {
        boolean ok = true;
        for (int i = 0; i < trace.length; i++) {
            Op op = trace[i];
            if (Op.COMPILED.contains(op)    && !WB.isMethodCompiled(method(op))) trace[i] = Op.toInterpreted(op);
            if (Op.INTERPRETED.contains(op) &&  WB.isMethodCompiled(method(op))) trace[i] = Op.toCompiled(op);
            if (op != trace[i]) ok = false;
        }
        return ok;
    }

    static void rethrow(Throwable t) {
        if (t instanceof Error) throw (Error)t;
        if (t instanceof RuntimeException) throw (RuntimeException)t;
        throw new AssertionError(t);
    }

    static <T> T[] arrayType(T[] array) {
        return (T[])java.lang.reflect.Array.newInstance(array.getClass().componentType(), 0);
    }
   
    static void printTrace(Op[] trace) { System.out.println(write(trace)); }

    static String write(Op[] trace) { 
        return Arrays.stream(trace).map(Object::toString).collect(Collectors.joining(", ")); 
    }

    static Op[] parse(String line) {
        return Arrays.stream(line.split(", ")).map(s -> Enum.valueOf(Op.class, s))
            .collect(Collectors.toList()).toArray(Op.ARRAY);
    }    

    static Method method(Op op)       { return method.get(op); }
    static MethodHandle handle(Op op) { return handle.get(op); }
 
    //////

    static final Class<?>[] run_sig = new Class<?>[]{};
    static final Class<?>[] int_sig = new Class<?>[]{int.class, int.class};
    static final Class<?>[] dbl_sig = new Class<?>[]{int.class, double.class};
    static final Class<?>[] mny_sig = new Class<?>[]{int.class,
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class};
    static final MethodType run_type = MethodType.methodType(void.class, run_sig);
    static final MethodType int_type = MethodType.methodType(int.class, int_sig);
    static final MethodType dbl_type = MethodType.methodType(double.class, dbl_sig);
    static final MethodType mny_type = MethodType.methodType(int.class, mny_sig);

    static final List<Method> precompile = new ArrayList<>();

    static final Method run;
    static final Map<Op, Method>       method = new EnumMap<>(Op.class);
    static final Map<Op, MethodHandle> handle = new EnumMap<>(Op.class);

    static {
        try {
            run = Fuzz.class.getDeclaredMethod("run", run_sig);
            // precompile.add(Fuzz.class.getDeclaredMethod("maybeResetIndex", new Class<?>[]{int.class}));

            method.put(Op.CALL_I_INT,  Fuzz.class.getDeclaredMethod("int_int", int_sig));
            method.put(Op.CALL_C_INT,  Fuzz.class.getDeclaredMethod("com_int", int_sig));
            method.put(Op.CALL_I_DBL,  Fuzz.class.getDeclaredMethod("int_dbl", dbl_sig));
            method.put(Op.CALL_C_DBL,  Fuzz.class.getDeclaredMethod("com_dbl", dbl_sig));
            method.put(Op.CALL_I_MANY, Fuzz.class.getDeclaredMethod("int_mny", mny_sig));
            method.put(Op.CALL_C_MANY, Fuzz.class.getDeclaredMethod("com_mny", mny_sig));
            method.put(Op.CALL_I_PIN,  Fuzz.class.getDeclaredMethod("int_pin", int_sig));
            method.put(Op.CALL_C_PIN,  Fuzz.class.getDeclaredMethod("com_pin", int_sig));

            method.put(Op.CALL_I_CTCH, method(Op.CALL_I_INT));
            method.put(Op.CALL_C_CTCH, method(Op.CALL_C_INT));

            method.put(Op.MH_I_INT,  method(Op.CALL_I_INT));
            method.put(Op.MH_C_INT,  method(Op.CALL_C_INT));
            method.put(Op.MH_I_MANY, method(Op.CALL_I_MANY));
            method.put(Op.MH_C_MANY, method(Op.CALL_C_MANY));

            method.put(Op.REF_I_INT,  method(Op.CALL_I_INT));
            method.put(Op.REF_C_INT,  method(Op.CALL_C_INT));
            method.put(Op.REF_I_MANY, method(Op.CALL_I_MANY));
            method.put(Op.REF_C_MANY, method(Op.CALL_C_MANY)); 

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            handle.put(Op.MH_I_INT,  lookup.unreflect(method(Op.CALL_I_INT)));
            handle.put(Op.MH_C_INT,  lookup.unreflect(method(Op.CALL_C_INT)));
            handle.put(Op.MH_I_MANY, lookup.unreflect(method(Op.CALL_I_MANY)));
            handle.put(Op.MH_C_MANY, lookup.unreflect(method(Op.CALL_C_MANY)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @DontInline void preYield() { captureStack(); }
    @DontInline void postYield(boolean yieldResult) { verifyPin(yieldResult); verifyStack(); }
    @DontInline void maybeResetIndex(int index0) { this.index = current() != Op.YIELD ? index0 : index; }
    @DontInline static void throwException() { throw new FuzzException("EX"); }

    @Override
    public void run() {
        final int depth = 0;
        int res = 3;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }
        
        this.result = log(res);
    }

    @DontInline
    int int_int(final int depth, int x) {
        int res = x;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    @DontInline
    int com_int(final int depth, int x) {
        int res = x;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    @DontInline
    double int_dbl(final int depth, double x) {
        double res = 3.0;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    @DontInline
    double com_dbl(final int depth, double x) {
        double res = 3.0;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    @DontInline
    int int_pin(final int depth, int x) {
        int res = x;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        synchronized (this) {

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        }

        return log(res);
    }

    @DontInline
    int com_pin(final int depth, int x) {
        int res = x;

        int x1 = (int)res, x2 = (int)res, x3 = (int)res, x4 = (int)res;
        double d1 = (double)res, d2 = (double)res, d3 = (double)res, d4 = (double)res;
        long l1 = (long)res, l2 = (long)res, l3 = (long)res, l4 = (long)res;
        float f1 = (float)res, f2 = (float)res, f3 = (float)res, f4 = (float)res;
        Object o1 = res, o2 = res, o3 = res, o4 = res;

        synchronized (this) {

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        }

        return log(res);
    }

    @DontInline
    int int_mny(int depth,
        int x1, double d1, long l1, float f1, Object o1,
        int x2, double d2, long l2, float f2, Object o2,
        int x3, double d3, long l3, float f3, Object o3,
        int x4, double d4, long l4, float f4, Object o4) {

        double res = x1 + d2 + f3 + l4 + (double)(o4 instanceof Double ? (Double)o4 : (Integer)o4);

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log((int)res);
    }

    @DontInline
    int com_mny(int depth,
        int x1, double d1, long l1, float f1, Object o1,
        int x2, double d2, long l2, float f2, Object o2,
        int x3, double d3, long l3, float f3, Object o3,
        int x4, double d4, long l4, float f4, Object o4) {

        double res = x1 + d2 + f3 + l4 + (double)(o4 instanceof Double ? (Double)o4 : (Integer)o4);

        for (int c = 1, index0 = index; c > 0; c--, maybeResetIndex(index0)) { // index0 is the index to which we return when we loop
            switch (next(c)) {
            case THROW -> throwException();
            case LOOP  -> { c += 2; index0 = index; }
            case YIELD -> { preYield(); boolean y = Continuation.yield(SCOPE); postYield(y); c++; }
            case DONE  -> { break; }
            case CALL_I_INT  -> res += int_int(depth+1, (int)res);
            case CALL_C_INT  -> res += com_int(depth+1, (int)res);
            case CALL_I_DBL  -> res += (int)int_dbl(depth+1, res);
            case CALL_C_DBL  -> res += (int)com_dbl(depth+1, res);
            case CALL_I_PIN  -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN  -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY -> res += int_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY -> res += com_mny(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CTCH -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CTCH -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT, MH_C_INT     -> {try { res += (int)handle(current()).invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY, MH_C_MANY   -> {try { res += (int)handle(current()).invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT,  REF_C_INT  -> {try { res += (int)method(current()).invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY, REF_C_MANY -> {try { res += (int)method(current()).invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log((int)res);
    }
}

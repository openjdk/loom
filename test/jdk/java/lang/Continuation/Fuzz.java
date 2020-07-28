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



/**
* @test
* @summary Fuzz tests for java.lang.Continuation
*
* @build java.base/java.lang.StackWalkerHelper
*
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:-UseContinuationLazyCopy -XX:-UseContinuationChunks                                       -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseContinuationLazyCopy -XX:-UseContinuationChunks                                       -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:-UseContinuationLazyCopy -XX:+UseContinuationChunks                                       -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseContinuationLazyCopy -XX:+UseContinuationChunks                                       -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:-UseContinuationLazyCopy -XX:-UseContinuationChunks -XX:CompileCommand=exclude,Fuzz.enter -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseContinuationLazyCopy -XX:-UseContinuationChunks -XX:CompileCommand=exclude,Fuzz.enter -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:-UseContinuationLazyCopy -XX:+UseContinuationChunks -XX:CompileCommand=exclude,Fuzz.enter -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
* @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseContinuationLazyCopy -XX:+UseContinuationChunks -XX:CompileCommand=exclude,Fuzz.enter -XX:CompileOnly=java/lang/Continuation,Fuzz -XX:CompileCommand=exclude,Fuzz.int_int -XX:CompileCommand=exclude,Fuzz.int_double -XX:CompileCommand=exclude,Fuzz.int_many -XX:CompileCommand=exclude,Fuzz.int_pin Fuzz
*
**/

// Anything excluded or not compileonly is not compiled; see CompilerOracle::should_exclude

// @run driver jdk.test.lib.FileInstaller compilerDirectives.json compilerDirectives.json
// -XX:CompilerDirectivesFile=compilerDirectives.json

// import sun.hotspot.WhiteBox;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.StackWalker.StackFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static java.lang.Math.max;
import static java.lang.Math.min;

public class Fuzz {
    static final boolean VERBOSE = false;

    public static void main(String[] args) throws Exception {
        System.out.println("-- FILE --");
        testStream(file(Path.of(System.getProperty("test.src", ".")).resolve("fuzz.dat")));

        System.out.println("-- RANDOM --");
        testStream(random(new Random(1)).limit(50));
    }
    
    static void testStream(Stream<Op[]> traces) {
        traces.forEach(Fuzz::testTrace);
    }

    static void testTrace(Op[] trace) {
        var fuzz = new Fuzz(trace);

        System.out.println();
        fuzz.print();

        fuzz.verbose = VERBOSE;
        fuzz.run();
    }

    static Stream<Op[]> random(Random rnd) {
        return new Generator(rnd).stream();
    }

    static Stream<Op[]> file(Path file) throws IOException {
        return Files.lines(file).map(String::trim).filter(s -> !s.isBlank() && !s.startsWith("#")).map(Fuzz::parse);
    }

    ////////////////

    enum Op {
        CALL_I_INT, CALL_I_DOUBLE, CALL_I_MANY, 
        CALL_C_INT, CALL_C_DOUBLE, CALL_C_MANY, 
        CALL_I_PIN, CALL_C_PIN,
        CALL_I_CATCH, CALL_C_CATCH,
        MH_I_INT, MH_C_INT, MH_I_MANY, MH_C_MANY,
        REF_I_INT, REF_C_INT, REF_I_MANY, REF_C_MANY,
        LOOP, YIELD, THROW, DONE;

    static final EnumSet<Op> BASIC       = EnumSet.of(LOOP, YIELD);
    static final EnumSet<Op> PIN         = EnumSet.of(CALL_I_PIN, CALL_C_PIN);
    static final EnumSet<Op> MH          = EnumSet.of(MH_I_INT, MH_C_INT, MH_I_MANY, MH_C_MANY);
    static final EnumSet<Op> REFLECTED   = EnumSet.of(REF_I_INT, REF_C_INT, REF_I_MANY, REF_C_MANY);
    static final EnumSet<Op> STANDARD    = EnumSet.of(CALL_C_INT, CALL_I_INT, CALL_C_DOUBLE, CALL_I_DOUBLE, CALL_C_MANY, CALL_I_MANY, CALL_C_CATCH, CALL_I_CATCH);
    static final EnumSet<Op> COMPILED    = EnumSet.of(CALL_C_INT, CALL_C_DOUBLE, CALL_C_MANY, CALL_C_PIN, CALL_C_CATCH, MH_C_INT, MH_C_MANY, REF_C_INT, REF_C_MANY);
    static final EnumSet<Op> INTERPRETED = EnumSet.of(CALL_I_INT, CALL_I_DOUBLE, CALL_I_MANY, CALL_I_PIN, CALL_I_CATCH, MH_I_INT, MH_I_MANY, REF_I_INT, REF_I_MANY);
    static final EnumSet<Op> NON_CALLS   = EnumSet.of(LOOP, YIELD, THROW, DONE);

    static final Op[] ARRAY = new Op[0];
    }

    ///// Trace Gnereation

    static class Generator {
        private final Random rnd;

        public Generator(Random rnd) { this.rnd = rnd; }

        public Stream<Op[]> stream() { 
            return Stream.iterate(0, x->x+1).map(__ -> generate()); 
        }

        public Op[] generate() {
            final int length = max(1, pick(new Integer[]{5, 10, 50/*, 200*/}) + plusOrMinus(5));

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

        private boolean percent(int percent) { return rnd.nextInt(100) < percent; }
        private int plusOrMinus(int n) { return rnd.nextInt(2*n + 1) - n; }
        private <T> T pick(T[] array) { return array[rnd.nextInt(array.length)]; }
    }


    ////////////////////////////////////////

    static final ContinuationScope SCOPE = new ContinuationScope() {};

    static class FuzzException extends RuntimeException {
        public FuzzException(String msg) { super(msg); }
    }

    private final Op[] trace;
    private int index;
    private int result = -1;
    boolean verbose = false;

    StackTraceElement[] backtrace;
    StackFrame[] fbacktrace;
    StackFrame[] lfbacktrace;

    private Fuzz(Op[] trace) {
        this.trace = trace;
        this.index = -1;
    }

    void print() { printTrace(trace); }

    private Op trace(int i) { return i < trace.length ? trace[i] : Op.DONE; }
    private Op current()    { return trace(index); }
    private Op next(int c)  { logOp(c); index++; return current(); }

    void run() {
        Continuation cont = new Continuation(SCOPE, this::enter) {
            @Override protected void onPinned(Pinned reason) { if (verbose) System.out.println("PINNED " + reason); }
        };

        try {
            while (true) {
                cont.run();
                if (cont.isDone()) break;

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

    void indent(int depth) {
        // depth = index;
        for (int i=0; i<depth; i++) System.out.print("  ");
    }

    int depth() {
        int d = 0;
        for (int i=0; i<=index && i < trace.length; i++) if (!Op.NON_CALLS.contains(trace[i])) d++;
        return d;
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

    int computeResult() {
        // To compute the expected result, we remove all YIELDs from the trace and run it
        Op[] trace0 = Arrays.stream(trace).filter(op -> op != Op.YIELD)
            .collect(Collectors.toList()).toArray(Op.ARRAY);
        
        Fuzz f0 = new Fuzz(trace0);
        f0.enter();
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
        for (int i = 0; i < trace.length; i++) {
            switch (trace[i]) {
                case CALL_I_CATCH, CALL_C_CATCH -> { return false; }
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
        verifyStack(backtrace, StackWalkerHelper.toStackTraceElement(fbacktrace));
        // verifyStack(fbacktrace, lfbacktrace);

        verifyStack(backtrace, Thread.currentThread().getStackTrace());
        verifyStack(fbacktrace, StackWalkerHelper.getStackFrames(SCOPE));
        // verifyStack(lfbacktrace, StackWalkerHelper.getLiveStackFrames(SCOPE));
    }

    void verifyStack(Continuation cont) {
        verifyStack(backtrace, StackWalkerHelper.toStackTraceElement(fbacktrace));
        // verifyStack(fbacktrace, lfbacktrace);
        
        verifyStack(backtrace, cont.getStackTrace());
        verifyStack(fbacktrace, StackWalkerHelper.getStackFrames(cont));
        // verifyStack(lfbacktrace, StackWalkerHelper.getLiveStackFrames(cont));
    }

    static boolean isStackCaptureMechanism(StackTraceElement ste) {
        return Fuzz.class.getName().equals(ste.getClassName()) 
            && ("captureStack".equals(ste.getMethodName()) || "verifyStack".equals(ste.getMethodName()));
    }

    static boolean isPrePostYield(StackTraceElement ste) {
        return Fuzz.class.getName().equals(ste.getClassName())
            && ("preYield".equals(ste.getMethodName()) || "postYield".equals(ste.getMethodName()));
    }

    static StackTraceElement[] cutStack(StackTraceElement[] stack) {
        var list = new ArrayList<StackTraceElement>();
        int i = 0;
        while (i < stack.length && (!Fuzz.class.getName().equals(stack[i].getClassName()) || isPrePostYield(stack[i]) || isStackCaptureMechanism(stack[i]))) i++;
        while (i < stack.length && !Continuation.class.getName().equals(stack[i].getClassName())) { list.add(stack[i]); i++; }
        while (i < stack.length && Continuation.class.getName().equals(stack[i].getClassName()) && !"enterSpecial".equals(stack[i].getMethodName())) { list.add(stack[i]); i++; }
        return list.toArray(new StackTraceElement[0]);
    }

    static void verifyStack(StackTraceElement[] expected, StackTraceElement[] observed) {
        expected = cutStack(expected);
        observed = cutStack(observed);
        boolean equal = true;
        if (expected.length == observed.length) {
            for (int i=0; i < expected.length; i++) {
                if (!Objects.equals(expected[i], observed[i])) {
                    // we allow a different line number for the first element
                    if (i > 0 || !Objects.equals(expected[i].getClassName(), observed[i].getClassName()) || !Objects.equals(expected[i].getMethodName(), observed[i].getMethodName())) {
                        System.out.println("At index " + i);
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
            System.out.println("Expected: "); for (var ste : expected) System.out.println("\t" + ste);
            System.out.println("Observed: "); for (var ste : observed) System.out.println("\t" + ste);
        }
        assert equal;
    }

    static StackFrame[] cutStack(StackFrame[] stack) {
        var list = new ArrayList<StackFrame>();
        int i = 0;
        while (i < stack.length && (!Fuzz.class.getName().equals(stack[i].getClassName()) || isPrePostYield(stack[i].toStackTraceElement()) || isStackCaptureMechanism(stack[i].toStackTraceElement()))) i++;
        while (i < stack.length && !Continuation.class.getName().equals(stack[i].getClassName())) { list.add(stack[i]); i++; }
        while (i < stack.length && Continuation.class.getName().equals(stack[i].getClassName()) && !"enterSpecial".equals(stack[i].getMethodName())) { list.add(stack[i]); i++; }
        return list.toArray(new StackFrame[0]);
    }

    static void verifyStack(StackFrame[] expected, StackFrame[] observed) {
        expected = cutStack(expected);
        observed = cutStack(observed);
        boolean equal = true;
        if (expected.length == observed.length) {
            for (int i=0; i < expected.length; i++) {
                if (!StackWalkerHelper.equals(expected[i], observed[i])) {
                    // we allow a different line number for the first element
                    if (i > 0 || !Objects.equals(expected[i].getClassName(), observed[i].getClassName()) || !Objects.equals(expected[i].getMethodName(), observed[i].getMethodName())) {
                        System.out.println("At index " + i);
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

    ////// Static Helpers

    static void rethrow(Throwable t) {
        if (t instanceof Error) throw (Error)t;
        if (t instanceof RuntimeException) throw (RuntimeException)t;
        throw new AssertionError(t);
    }

    static void printTrace(Op[] trace) {
        System.out.println(write(trace));
    }

    static String write(Op[] trace) {
        return Arrays.stream(trace)
            .map(Object::toString)
            .collect(Collectors.joining(", "));
    }

    static Op[] parse(String line) {
        return Arrays.stream(line.split(", "))
            .map(s -> Enum.valueOf(Op.class, s))
            .collect(Collectors.toList())
            .toArray(Op.ARRAY);
    }

    //////

    static final Class<?>[] int_sig = new Class<?>[]{int.class, int.class};
    static final Class<?>[] many_sig = new Class<?>[]{int.class, 
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class,
        int.class, double.class, long.class, float.class, Object.class};
    static final MethodType int_type = MethodType.methodType(int.class, int_sig);
    static final MethodType many_type = MethodType.methodType(int.class, many_sig);

    static final MethodHandle int_int_mh, com_int_mh, int_many_mh, com_many_mh;
    static final Method int_int_ref, com_int_ref, int_many_ref, com_many_ref;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            int_int_mh  = lookup.findVirtual(Fuzz.class, "int_int", int_type);
            com_int_mh  = lookup.findVirtual(Fuzz.class, "com_int", int_type);
            int_many_mh = lookup.findVirtual(Fuzz.class, "int_many", many_type);
            com_many_mh = lookup.findVirtual(Fuzz.class, "com_many", many_type);
            
            int_int_ref  = Fuzz.class.getDeclaredMethod("int_int", int_sig);
            com_int_ref  = Fuzz.class.getDeclaredMethod("com_int", int_sig);
            int_many_ref = Fuzz.class.getDeclaredMethod("int_many", many_sig);
            com_many_ref = Fuzz.class.getDeclaredMethod("com_many", many_sig);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    void preYield() { captureStack(); }
    void postYield(boolean yieldResult) { verifyPin(yieldResult); verifyStack(); }
    void maybeResetIndex(int index0) { this.index = current() != Op.YIELD ? index0 : index; }

    void throwException() { throw new FuzzException("EX"); }

    void enter() {
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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }
        
        this.result = log(res);
    }

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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    double int_double(final int depth, double x) {
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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

    double com_double(final int depth, double x) {
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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log(res);
    }

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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        }

        return log(res);
    }

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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        }

        return log(res);
    }

    int int_many(int depth,
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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log((int)res);
    }

    int com_many(int depth,
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
            case CALL_I_INT    -> res += int_int(depth+1, (int)res);
            case CALL_C_INT    -> res += com_int(depth+1, (int)res);
            case CALL_I_DOUBLE -> res += (int)int_double(depth+1, res);
            case CALL_C_DOUBLE -> res += (int)com_double(depth+1, res);
            case CALL_I_PIN    -> res += int_pin(depth+1, (int)res);
            case CALL_C_PIN    -> res += com_pin(depth+1, (int)res);
            case CALL_I_MANY   -> res += int_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_C_MANY   -> res += com_many(depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4);
            case CALL_I_CATCH  -> {try { res += int_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case CALL_C_CATCH  -> {try { res += com_int(depth+1, (int)res); } catch (FuzzException e) {}}
            case MH_I_INT      -> {try { res += (int)int_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_C_INT      -> {try { res += (int)com_int_mh.invokeExact(this, depth+1, (int)res);  } catch (Throwable e) { rethrow(e); }}
            case MH_I_MANY     -> {try { res += (int)int_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case MH_C_MANY     -> {try { res += (int)com_many_mh.invokeExact(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (Throwable e) { rethrow(e); }}
            case REF_I_INT     -> {try { res += (int)int_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_INT     -> {try { res += (int)com_int_ref.invoke(this, depth+1, (int)res); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_I_MANY    -> {try { res += (int)int_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            case REF_C_MANY    -> {try { res += (int)com_many_ref.invoke(this, depth+1, x1, d1, l1, f1, o1, x2, d2, l2, f2, o2, x3, d3, l3, f3, o3, x4, d4, l4, f4, o4); } catch (InvocationTargetException e) { rethrow(e.getCause()); } catch (IllegalAccessException e) { assert false; }}
            default -> throw new AssertionError("Unknown op: " + current());
            }
        }

        return log((int)res);
    }
}

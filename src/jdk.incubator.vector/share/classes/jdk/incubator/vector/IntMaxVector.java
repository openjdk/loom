/*
 * Copyright (c) 2017, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.incubator.vector;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

import jdk.internal.vm.annotation.ForceInline;
import jdk.internal.vm.vector.VectorSupport;

import static jdk.internal.vm.vector.VectorSupport.*;

import static jdk.incubator.vector.VectorOperators.*;

// -- This file was mechanically generated: Do not edit! -- //

@SuppressWarnings("cast")  // warning: redundant cast
final class IntMaxVector extends IntVector {
    static final IntSpecies VSPECIES =
        (IntSpecies) IntVector.SPECIES_MAX;

    static final VectorShape VSHAPE =
        VSPECIES.vectorShape();

    static final Class<IntMaxVector> VCLASS = IntMaxVector.class;

    static final int VSIZE = VSPECIES.vectorBitSize();

    static final int VLENGTH = VSPECIES.laneCount(); // used by the JVM

    static final Class<Integer> ETYPE = int.class; // used by the JVM

    IntMaxVector(int[] v) {
        super(v);
    }

    // For compatibility as IntMaxVector::new,
    // stored into species.vectorFactory.
    IntMaxVector(Object v) {
        this((int[]) v);
    }

    static final IntMaxVector ZERO = new IntMaxVector(new int[VLENGTH]);
    static final IntMaxVector IOTA = new IntMaxVector(VSPECIES.iotaArray());

    static {
        // Warm up a few species caches.
        // If we do this too much we will
        // get NPEs from bootstrap circularity.
        VSPECIES.dummyVector();
        VSPECIES.withLanes(LaneType.BYTE);
    }

    // Specialized extractors

    @ForceInline
    final @Override
    public IntSpecies vspecies() {
        // ISSUE:  This should probably be a @Stable
        // field inside AbstractVector, rather than
        // a megamorphic method.
        return VSPECIES;
    }

    @ForceInline
    @Override
    public final Class<Integer> elementType() { return int.class; }

    @ForceInline
    @Override
    public final int elementSize() { return Integer.SIZE; }

    @ForceInline
    @Override
    public final VectorShape shape() { return VSHAPE; }

    @ForceInline
    @Override
    public final int length() { return VLENGTH; }

    @ForceInline
    @Override
    public final int bitSize() { return VSIZE; }

    @ForceInline
    @Override
    public final int byteSize() { return VSIZE / Byte.SIZE; }

    /*package-private*/
    @ForceInline
    final @Override
    int[] vec() {
        return (int[])getPayload();
    }

    // Virtualized constructors

    @Override
    @ForceInline
    public final IntMaxVector broadcast(int e) {
        return (IntMaxVector) super.broadcastTemplate(e);  // specialize
    }

    @Override
    @ForceInline
    public final IntMaxVector broadcast(long e) {
        return (IntMaxVector) super.broadcastTemplate(e);  // specialize
    }

    @Override
    @ForceInline
    IntMaxMask maskFromArray(boolean[] bits) {
        return new IntMaxMask(bits);
    }

    @Override
    @ForceInline
    IntMaxShuffle iotaShuffle() { return IntMaxShuffle.IOTA; }

    @ForceInline
    IntMaxShuffle iotaShuffle(int start, int step, boolean wrap) {
      if (wrap) {
        return (IntMaxShuffle)VectorSupport.shuffleIota(ETYPE, IntMaxShuffle.class, VSPECIES, VLENGTH, start, step, 1,
                (l, lstart, lstep, s) -> s.shuffleFromOp(i -> (VectorIntrinsics.wrapToRange(i*lstep + lstart, l))));
      } else {
        return (IntMaxShuffle)VectorSupport.shuffleIota(ETYPE, IntMaxShuffle.class, VSPECIES, VLENGTH, start, step, 0,
                (l, lstart, lstep, s) -> s.shuffleFromOp(i -> (i*lstep + lstart)));
      }
    }

    @Override
    @ForceInline
    IntMaxShuffle shuffleFromBytes(byte[] reorder) { return new IntMaxShuffle(reorder); }

    @Override
    @ForceInline
    IntMaxShuffle shuffleFromArray(int[] indexes, int i) { return new IntMaxShuffle(indexes, i); }

    @Override
    @ForceInline
    IntMaxShuffle shuffleFromOp(IntUnaryOperator fn) { return new IntMaxShuffle(fn); }

    // Make a vector of the same species but the given elements:
    @ForceInline
    final @Override
    IntMaxVector vectorFactory(int[] vec) {
        return new IntMaxVector(vec);
    }

    @ForceInline
    final @Override
    ByteMaxVector asByteVectorRaw() {
        return (ByteMaxVector) super.asByteVectorRawTemplate();  // specialize
    }

    @ForceInline
    final @Override
    AbstractVector<?> asVectorRaw(LaneType laneType) {
        return super.asVectorRawTemplate(laneType);  // specialize
    }

    // Unary operator

    @ForceInline
    final @Override
    IntMaxVector uOp(FUnOp f) {
        return (IntMaxVector) super.uOpTemplate(f);  // specialize
    }

    @ForceInline
    final @Override
    IntMaxVector uOp(VectorMask<Integer> m, FUnOp f) {
        return (IntMaxVector)
            super.uOpTemplate((IntMaxMask)m, f);  // specialize
    }

    // Binary operator

    @ForceInline
    final @Override
    IntMaxVector bOp(Vector<Integer> v, FBinOp f) {
        return (IntMaxVector) super.bOpTemplate((IntMaxVector)v, f);  // specialize
    }

    @ForceInline
    final @Override
    IntMaxVector bOp(Vector<Integer> v,
                     VectorMask<Integer> m, FBinOp f) {
        return (IntMaxVector)
            super.bOpTemplate((IntMaxVector)v, (IntMaxMask)m,
                              f);  // specialize
    }

    // Ternary operator

    @ForceInline
    final @Override
    IntMaxVector tOp(Vector<Integer> v1, Vector<Integer> v2, FTriOp f) {
        return (IntMaxVector)
            super.tOpTemplate((IntMaxVector)v1, (IntMaxVector)v2,
                              f);  // specialize
    }

    @ForceInline
    final @Override
    IntMaxVector tOp(Vector<Integer> v1, Vector<Integer> v2,
                     VectorMask<Integer> m, FTriOp f) {
        return (IntMaxVector)
            super.tOpTemplate((IntMaxVector)v1, (IntMaxVector)v2,
                              (IntMaxMask)m, f);  // specialize
    }

    @ForceInline
    final @Override
    int rOp(int v, FBinOp f) {
        return super.rOpTemplate(v, f);  // specialize
    }

    @Override
    @ForceInline
    public final <F>
    Vector<F> convertShape(VectorOperators.Conversion<Integer,F> conv,
                           VectorSpecies<F> rsp, int part) {
        return super.convertShapeTemplate(conv, rsp, part);  // specialize
    }

    @Override
    @ForceInline
    public final <F>
    Vector<F> reinterpretShape(VectorSpecies<F> toSpecies, int part) {
        return super.reinterpretShapeTemplate(toSpecies, part);  // specialize
    }

    // Specialized algebraic operations:

    // The following definition forces a specialized version of this
    // crucial method into the v-table of this class.  A call to add()
    // will inline to a call to lanewise(ADD,), at which point the JIT
    // intrinsic will have the opcode of ADD, plus all the metadata
    // for this particular class, enabling it to generate precise
    // code.
    //
    // There is probably no benefit to the JIT to specialize the
    // masked or broadcast versions of the lanewise method.

    @Override
    @ForceInline
    public IntMaxVector lanewise(Unary op) {
        return (IntMaxVector) super.lanewiseTemplate(op);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector lanewise(Binary op, Vector<Integer> v) {
        return (IntMaxVector) super.lanewiseTemplate(op, v);  // specialize
    }

    /*package-private*/
    @Override
    @ForceInline IntMaxVector
    lanewiseShift(VectorOperators.Binary op, int e) {
        return (IntMaxVector) super.lanewiseShiftTemplate(op, e);  // specialize
    }

    /*package-private*/
    @Override
    @ForceInline
    public final
    IntMaxVector
    lanewise(VectorOperators.Ternary op, Vector<Integer> v1, Vector<Integer> v2) {
        return (IntMaxVector) super.lanewiseTemplate(op, v1, v2);  // specialize
    }

    @Override
    @ForceInline
    public final
    IntMaxVector addIndex(int scale) {
        return (IntMaxVector) super.addIndexTemplate(scale);  // specialize
    }

    // Type specific horizontal reductions

    @Override
    @ForceInline
    public final int reduceLanes(VectorOperators.Associative op) {
        return super.reduceLanesTemplate(op);  // specialized
    }

    @Override
    @ForceInline
    public final int reduceLanes(VectorOperators.Associative op,
                                    VectorMask<Integer> m) {
        return super.reduceLanesTemplate(op, m);  // specialized
    }

    @Override
    @ForceInline
    public final long reduceLanesToLong(VectorOperators.Associative op) {
        return (long) super.reduceLanesTemplate(op);  // specialized
    }

    @Override
    @ForceInline
    public final long reduceLanesToLong(VectorOperators.Associative op,
                                        VectorMask<Integer> m) {
        return (long) super.reduceLanesTemplate(op, m);  // specialized
    }

    @Override
    @ForceInline
    public VectorShuffle<Integer> toShuffle() {
        int[] a = toArray();
        int[] sa = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            sa[i] = (int) a[i];
        }
        return VectorShuffle.fromArray(VSPECIES, sa, 0);
    }

    // Specialized unary testing

    @Override
    @ForceInline
    public final IntMaxMask test(Test op) {
        return super.testTemplate(IntMaxMask.class, op);  // specialize
    }

    // Specialized comparisons

    @Override
    @ForceInline
    public final IntMaxMask compare(Comparison op, Vector<Integer> v) {
        return super.compareTemplate(IntMaxMask.class, op, v);  // specialize
    }

    @Override
    @ForceInline
    public final IntMaxMask compare(Comparison op, int s) {
        return super.compareTemplate(IntMaxMask.class, op, s);  // specialize
    }

    @Override
    @ForceInline
    public final IntMaxMask compare(Comparison op, long s) {
        return super.compareTemplate(IntMaxMask.class, op, s);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector blend(Vector<Integer> v, VectorMask<Integer> m) {
        return (IntMaxVector)
            super.blendTemplate(IntMaxMask.class,
                                (IntMaxVector) v,
                                (IntMaxMask) m);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector slice(int origin, Vector<Integer> v) {
        return (IntMaxVector) super.sliceTemplate(origin, v);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector slice(int origin) {
       if ((origin < 0) || (origin >= VLENGTH)) {
         throw new ArrayIndexOutOfBoundsException("Index " + origin + " out of bounds for vector length " + VLENGTH);
       } else {
         IntMaxShuffle Iota = iotaShuffle();
         VectorMask<Integer> BlendMask = Iota.toVector().compare(VectorOperators.LT, (broadcast((int)(VLENGTH-origin))));
         Iota = iotaShuffle(origin, 1, true);
         return ZERO.blend(this.rearrange(Iota), BlendMask);
       }
    }

    @Override
    @ForceInline
    public IntMaxVector unslice(int origin, Vector<Integer> w, int part) {
        return (IntMaxVector) super.unsliceTemplate(origin, w, part);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector unslice(int origin, Vector<Integer> w, int part, VectorMask<Integer> m) {
        return (IntMaxVector)
            super.unsliceTemplate(IntMaxMask.class,
                                  origin, w, part,
                                  (IntMaxMask) m);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector unslice(int origin) {
       if ((origin < 0) || (origin >= VLENGTH)) {
         throw new ArrayIndexOutOfBoundsException("Index " + origin + " out of bounds for vector length " + VLENGTH);
       } else {
         IntMaxShuffle Iota = iotaShuffle();
         VectorMask<Integer> BlendMask = Iota.toVector().compare(VectorOperators.GE, (broadcast((int)(origin))));
         Iota = iotaShuffle(-origin, 1, true);
         return ZERO.blend(this.rearrange(Iota), BlendMask);
       }
    }

    @Override
    @ForceInline
    public IntMaxVector rearrange(VectorShuffle<Integer> s) {
        return (IntMaxVector)
            super.rearrangeTemplate(IntMaxShuffle.class,
                                    (IntMaxShuffle) s);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector rearrange(VectorShuffle<Integer> shuffle,
                                  VectorMask<Integer> m) {
        return (IntMaxVector)
            super.rearrangeTemplate(IntMaxShuffle.class,
                                    (IntMaxShuffle) shuffle,
                                    (IntMaxMask) m);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector rearrange(VectorShuffle<Integer> s,
                                  Vector<Integer> v) {
        return (IntMaxVector)
            super.rearrangeTemplate(IntMaxShuffle.class,
                                    (IntMaxShuffle) s,
                                    (IntMaxVector) v);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector selectFrom(Vector<Integer> v) {
        return (IntMaxVector)
            super.selectFromTemplate((IntMaxVector) v);  // specialize
    }

    @Override
    @ForceInline
    public IntMaxVector selectFrom(Vector<Integer> v,
                                   VectorMask<Integer> m) {
        return (IntMaxVector)
            super.selectFromTemplate((IntMaxVector) v,
                                     (IntMaxMask) m);  // specialize
    }


    @ForceInline
    @Override
    public int lane(int i) {
        if (i < 0 || i >= VLENGTH) {
            throw new IllegalArgumentException("Index " + i + " must be zero or positive, and less than " + VLENGTH);
        }
        return laneHelper(i);
    }

    public int laneHelper(int i) {
        return (int) VectorSupport.extract(
                                VCLASS, ETYPE, VLENGTH,
                                this, i,
                                (vec, ix) -> {
                                    int[] vecarr = vec.vec();
                                    return (long)vecarr[ix];
                                });
    }

    @ForceInline
    @Override
    public IntMaxVector withLane(int i, int e) {
        if (i < 0 || i >= VLENGTH) {
            throw new IllegalArgumentException("Index " + i + " must be zero or positive, and less than " + VLENGTH);
        }
        return withLaneHelper(i, e);
    }

    public IntMaxVector withLaneHelper(int i, int e) {
        return VectorSupport.insert(
                                VCLASS, ETYPE, VLENGTH,
                                this, i, (long)e,
                                (v, ix, bits) -> {
                                    int[] res = v.vec().clone();
                                    res[ix] = (int)bits;
                                    return v.vectorFactory(res);
                                });
    }

    // Mask

    static final class IntMaxMask extends AbstractMask<Integer> {
        static final int VLENGTH = VSPECIES.laneCount();    // used by the JVM
        static final Class<Integer> ETYPE = int.class; // used by the JVM

        IntMaxMask(boolean[] bits) {
            this(bits, 0);
        }

        IntMaxMask(boolean[] bits, int offset) {
            super(prepare(bits, offset));
        }

        IntMaxMask(boolean val) {
            super(prepare(val));
        }

        private static boolean[] prepare(boolean[] bits, int offset) {
            boolean[] newBits = new boolean[VSPECIES.laneCount()];
            for (int i = 0; i < newBits.length; i++) {
                newBits[i] = bits[offset + i];
            }
            return newBits;
        }

        private static boolean[] prepare(boolean val) {
            boolean[] bits = new boolean[VSPECIES.laneCount()];
            Arrays.fill(bits, val);
            return bits;
        }

        @ForceInline
        final @Override
        public IntSpecies vspecies() {
            // ISSUE:  This should probably be a @Stable
            // field inside AbstractMask, rather than
            // a megamorphic method.
            return VSPECIES;
        }

        @ForceInline
        boolean[] getBits() {
            return (boolean[])getPayload();
        }

        @Override
        IntMaxMask uOp(MUnOp f) {
            boolean[] res = new boolean[vspecies().laneCount()];
            boolean[] bits = getBits();
            for (int i = 0; i < res.length; i++) {
                res[i] = f.apply(i, bits[i]);
            }
            return new IntMaxMask(res);
        }

        @Override
        IntMaxMask bOp(VectorMask<Integer> m, MBinOp f) {
            boolean[] res = new boolean[vspecies().laneCount()];
            boolean[] bits = getBits();
            boolean[] mbits = ((IntMaxMask)m).getBits();
            for (int i = 0; i < res.length; i++) {
                res[i] = f.apply(i, bits[i], mbits[i]);
            }
            return new IntMaxMask(res);
        }

        @ForceInline
        @Override
        public final
        IntMaxVector toVector() {
            return (IntMaxVector) super.toVectorTemplate();  // specialize
        }

        @Override
        @ForceInline
        public <E> VectorMask<E> cast(VectorSpecies<E> s) {
            AbstractSpecies<E> species = (AbstractSpecies<E>) s;
            if (length() != species.laneCount())
                throw new IllegalArgumentException("VectorMask length and species length differ");
            boolean[] maskArray = toArray();
            // enum-switches don't optimize properly JDK-8161245
            switch (species.laneType.switchKey) {
            case LaneType.SK_BYTE:
                return new ByteMaxVector.ByteMaxMask(maskArray).check(species);
            case LaneType.SK_SHORT:
                return new ShortMaxVector.ShortMaxMask(maskArray).check(species);
            case LaneType.SK_INT:
                return new IntMaxVector.IntMaxMask(maskArray).check(species);
            case LaneType.SK_LONG:
                return new LongMaxVector.LongMaxMask(maskArray).check(species);
            case LaneType.SK_FLOAT:
                return new FloatMaxVector.FloatMaxMask(maskArray).check(species);
            case LaneType.SK_DOUBLE:
                return new DoubleMaxVector.DoubleMaxMask(maskArray).check(species);
            }

            // Should not reach here.
            throw new AssertionError(species);
        }

        // Unary operations

        @Override
        @ForceInline
        public IntMaxMask not() {
            return xor(maskAll(true));
        }

        // Binary operations

        @Override
        @ForceInline
        public IntMaxMask and(VectorMask<Integer> mask) {
            Objects.requireNonNull(mask);
            IntMaxMask m = (IntMaxMask)mask;
            return VectorSupport.binaryOp(VECTOR_OP_AND, IntMaxMask.class, int.class, VLENGTH,
                                             this, m,
                                             (m1, m2) -> m1.bOp(m2, (i, a, b) -> a & b));
        }

        @Override
        @ForceInline
        public IntMaxMask or(VectorMask<Integer> mask) {
            Objects.requireNonNull(mask);
            IntMaxMask m = (IntMaxMask)mask;
            return VectorSupport.binaryOp(VECTOR_OP_OR, IntMaxMask.class, int.class, VLENGTH,
                                             this, m,
                                             (m1, m2) -> m1.bOp(m2, (i, a, b) -> a | b));
        }

        @ForceInline
        /* package-private */
        IntMaxMask xor(VectorMask<Integer> mask) {
            Objects.requireNonNull(mask);
            IntMaxMask m = (IntMaxMask)mask;
            return VectorSupport.binaryOp(VECTOR_OP_XOR, IntMaxMask.class, int.class, VLENGTH,
                                          this, m,
                                          (m1, m2) -> m1.bOp(m2, (i, a, b) -> a ^ b));
        }

        // Reductions

        @Override
        @ForceInline
        public boolean anyTrue() {
            return VectorSupport.test(BT_ne, IntMaxMask.class, int.class, VLENGTH,
                                         this, vspecies().maskAll(true),
                                         (m, __) -> anyTrueHelper(((IntMaxMask)m).getBits()));
        }

        @Override
        @ForceInline
        public boolean allTrue() {
            return VectorSupport.test(BT_overflow, IntMaxMask.class, int.class, VLENGTH,
                                         this, vspecies().maskAll(true),
                                         (m, __) -> allTrueHelper(((IntMaxMask)m).getBits()));
        }

        @ForceInline
        /*package-private*/
        static IntMaxMask maskAll(boolean bit) {
            return VectorSupport.broadcastCoerced(IntMaxMask.class, int.class, VLENGTH,
                                                  (bit ? -1 : 0), null,
                                                  (v, __) -> (v != 0 ? TRUE_MASK : FALSE_MASK));
        }
        private static final IntMaxMask  TRUE_MASK = new IntMaxMask(true);
        private static final IntMaxMask FALSE_MASK = new IntMaxMask(false);


        static boolean[] maskLowerHalf() {
            boolean[] a = new boolean[VLENGTH];
            int len = a.length >> 1;
            for (int i = 0; i < len; i++) {
                a[i] = true;
            }
            return a;
        }

        static final IntMaxMask LOWER_HALF_TRUE_MASK = new IntMaxMask(maskLowerHalf());
    }

    // Shuffle

    static final class IntMaxShuffle extends AbstractShuffle<Integer> {
        static final int VLENGTH = VSPECIES.laneCount();    // used by the JVM
        static final Class<Integer> ETYPE = int.class; // used by the JVM

        IntMaxShuffle(byte[] reorder) {
            super(VLENGTH, reorder);
        }

        public IntMaxShuffle(int[] reorder) {
            super(VLENGTH, reorder);
        }

        public IntMaxShuffle(int[] reorder, int i) {
            super(VLENGTH, reorder, i);
        }

        public IntMaxShuffle(IntUnaryOperator fn) {
            super(VLENGTH, fn);
        }

        @Override
        public IntSpecies vspecies() {
            return VSPECIES;
        }

        static {
            // There must be enough bits in the shuffle lanes to encode
            // VLENGTH valid indexes and VLENGTH exceptional ones.
            assert(VLENGTH < Byte.MAX_VALUE);
            assert(Byte.MIN_VALUE <= -VLENGTH);
        }
        static final IntMaxShuffle IOTA = new IntMaxShuffle(IDENTITY);

        @Override
        @ForceInline
        public IntMaxVector toVector() {
            return VectorSupport.shuffleToVector(VCLASS, ETYPE, IntMaxShuffle.class, this, VLENGTH,
                                                    (s) -> ((IntMaxVector)(((AbstractShuffle<Integer>)(s)).toVectorTemplate())));
        }

        @Override
        @ForceInline
        public <F> VectorShuffle<F> cast(VectorSpecies<F> s) {
            AbstractSpecies<F> species = (AbstractSpecies<F>) s;
            if (length() != species.laneCount())
                throw new IllegalArgumentException("VectorShuffle length and species length differ");
            int[] shuffleArray = toArray();
            // enum-switches don't optimize properly JDK-8161245
            switch (species.laneType.switchKey) {
            case LaneType.SK_BYTE:
                return new ByteMaxVector.ByteMaxShuffle(shuffleArray).check(species);
            case LaneType.SK_SHORT:
                return new ShortMaxVector.ShortMaxShuffle(shuffleArray).check(species);
            case LaneType.SK_INT:
                return new IntMaxVector.IntMaxShuffle(shuffleArray).check(species);
            case LaneType.SK_LONG:
                return new LongMaxVector.LongMaxShuffle(shuffleArray).check(species);
            case LaneType.SK_FLOAT:
                return new FloatMaxVector.FloatMaxShuffle(shuffleArray).check(species);
            case LaneType.SK_DOUBLE:
                return new DoubleMaxVector.DoubleMaxShuffle(shuffleArray).check(species);
            }

            // Should not reach here.
            throw new AssertionError(species);
        }

        @ForceInline
        @Override
        public IntMaxShuffle rearrange(VectorShuffle<Integer> shuffle) {
            IntMaxShuffle s = (IntMaxShuffle) shuffle;
            byte[] reorder1 = reorder();
            byte[] reorder2 = s.reorder();
            byte[] r = new byte[reorder1.length];
            for (int i = 0; i < reorder1.length; i++) {
                int ssi = reorder2[i];
                r[i] = reorder1[ssi];  // throws on exceptional index
            }
            return new IntMaxShuffle(r);
        }
    }

    // ================================================

    // Specialized low-level memory operations.

    @ForceInline
    @Override
    final
    IntVector fromArray0(int[] a, int offset) {
        return super.fromArray0Template(a, offset);  // specialize
    }

    @ForceInline
    @Override
    final
    IntVector fromByteArray0(byte[] a, int offset) {
        return super.fromByteArray0Template(a, offset);  // specialize
    }

    @ForceInline
    @Override
    final
    IntVector fromByteBuffer0(ByteBuffer bb, int offset) {
        return super.fromByteBuffer0Template(bb, offset);  // specialize
    }

    @ForceInline
    @Override
    final
    void intoArray0(int[] a, int offset) {
        super.intoArray0Template(a, offset);  // specialize
    }

    @ForceInline
    @Override
    final
    void intoByteArray0(byte[] a, int offset) {
        super.intoByteArray0Template(a, offset);  // specialize
    }

    // End of specialized low-level memory operations.

    // ================================================

}

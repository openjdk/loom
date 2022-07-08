/*
 * Copyright (c) 2012, 2022, Oracle and/or its affiliates. All rights reserved.
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
 * @key randomness
 * @bug 7119644
 * @summary Increase superword's vector size up to 256 bits
 * @library /test/lib
 * @run main/othervm/timeout=300 -Xbatch -XX:+IgnoreUnrecognizedVMOptions
 *    -XX:-TieredCompilation -XX:-OptimizeFill
 *    compiler.codegen.TestByteLongVect
 */

package compiler.codegen;

import java.util.Random;
import jdk.test.lib.Utils;

public class TestByteLongVect {
  private static final int ARRLEN = 997;
  private static final int ITERS  = 11000;
  private static final int OFFSET = 3;
  private static final int SCALE = 2;
  private static final int ALIGN_OFF = 8;
  private static final int UNALIGN_OFF = 5;

  private static final byte[] bspecial = {
    0, 0x8, 0xF, 0x3F, 0x7C, 0x7F, (byte)0x80, (byte)0x81, (byte)0x8F,
    (byte)0xF3, (byte)0xF8, (byte)0xFF, (byte)0x38FF, (byte)0x3FFF,
    (byte)0xFFFF, (byte)Integer.MAX_VALUE, (byte)Integer.MIN_VALUE
  };

  private static final long[] lspecial = {
    0,
    Integer.MAX_VALUE,
    Integer.MIN_VALUE,
    -Integer.MAX_VALUE,
    -Integer.MIN_VALUE,
    Long.MAX_VALUE,
    Long.MIN_VALUE,
    -Long.MAX_VALUE,
    -Long.MIN_VALUE
  };

  public static void main(String args[]) {
    System.out.println("Testing Byte + Long vectors");
    int errn = test();
    if (errn > 0) {
      System.err.println("FAILED: " + errn + " errors");
      System.exit(97);
    }
    System.out.println("PASSED");
  }

  static int test() {
    byte[] a1 = new byte[ARRLEN];
    byte[] a2 = new byte[ARRLEN];
    long[] b1 = new long[ARRLEN];
    long[] b2 = new long[ARRLEN];
    System.out.println("Warmup");
    for (int i=0; i<ITERS; i++) {
      test_ci(a1, b1);
      test_vi(a2, b2, (byte)123, (long)103);
      test_cp(a1, a2, b1, b2);
      test_ci_neg(a1, b1);
      test_vi_neg(a1, b1, (byte)123, (long)103);
      test_cp_neg(a1, a2, b1, b2);
      test_ci_oppos(a1, b1);
      test_vi_oppos(a1, b1, (byte)123, (long)103);
      test_cp_oppos(a1, a2, b1, b2);
      test_ci_aln(a1, b1);
      test_vi_aln(a1, b1, (byte)123, (long)103);
      test_cp_alndst(a1, a2, b1, b2);
      test_cp_alnsrc(a1, a2, b1, b2);
      test_ci_unaln(a1, b1);
      test_vi_unaln(a1, b1, (byte)123, (long)103);
      test_cp_unalndst(a1, a2, b1, b2);
      test_cp_unalnsrc(a1, a2, b1, b2);
      test_conv_b2l(a1, b1);
      test_conv_l2b(a1, b1);
    }
    // Initialize
    for (int i=0; i<ARRLEN; i++) {
      a1[i] = -1;
      a2[i] = -1;
      b1[i] = -1;
      b2[i] = -1;
    }
    // Test and verify results
    System.out.println("Verification");
    int errn = 0;
    {
      test_ci(a1, b1);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_ci: a1", i, a1[i], (byte)-123);
        errn += verify("test_ci: b1", i, b1[i], (long)-103);
      }
      test_vi(a2, b2, (byte)123, (long)103);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_vi: a2", i, a2[i], (byte)123);
        errn += verify("test_vi: b2", i, b2[i], (long)103);
      }
      test_cp(a1, a2, b1, b2);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_cp: a1", i, a1[i], (byte)123);
        errn += verify("test_cp: b1", i, b1[i], (long)103);
      }

      // Reset for negative stride
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        a2[i] = -1;
        b1[i] = -1;
        b2[i] = -1;
      }
      test_ci_neg(a1, b1);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_ci_neg: a1", i, a1[i], (byte)-123);
        errn += verify("test_ci_neg: b1", i, b1[i], (long)-103);
      }
      test_vi_neg(a2, b2, (byte)123, (long)103);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_vi_neg: a2", i, a2[i], (byte)123);
        errn += verify("test_vi_neg: b2", i, b2[i], (long)103);
      }
      test_cp_neg(a1, a2, b1, b2);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_cp_neg: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_neg: b1", i, b1[i], (long)103);
      }

      // Reset for opposite stride
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        a2[i] = -1;
        b1[i] = -1;
        b2[i] = -1;
      }
      test_ci_oppos(a1, b1);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_ci_oppos: a1", i, a1[i], (byte)-123);
        errn += verify("test_ci_oppos: b1", i, b1[i], (long)-103);
      }
      test_vi_oppos(a2, b2, (byte)123, (long)103);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_vi_oppos: a2", i, a2[i], (byte)123);
        errn += verify("test_vi_oppos: b2", i, b2[i], (long)103);
      }
      test_cp_oppos(a1, a2, b1, b2);
      for (int i=0; i<ARRLEN; i++) {
        errn += verify("test_cp_oppos: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_oppos: b1", i, b1[i], (long)103);
      }

      // Reset for 2 arrays with relative aligned offset
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        a2[i] = 123;
        b1[i] = -1;
        b2[i] = 123;
      }
      test_cp_alndst(a1, a2, b1, b2);
      for (int i=0; i<ALIGN_OFF; i++) {
        errn += verify("test_cp_alndst: a1", i, a1[i], (byte)-1);
        errn += verify("test_cp_alndst: b1", i, b1[i], (long)-1);
      }
      for (int i=ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_cp_alndst: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_alndst: b1", i, b1[i], (long)123);
      }
      for (int i=0; i<ARRLEN; i++) {
        a2[i] = -123;
        b2[i] = -123;
      }
      test_cp_alnsrc(a1, a2, b1, b2);
      for (int i=0; i<ARRLEN-ALIGN_OFF; i++) {
        errn += verify("test_cp_alnsrc: a1", i, a1[i], (byte)-123);
        errn += verify("test_cp_alnsrc: b1", i, b1[i], (long)-123);
      }
      for (int i=ARRLEN-ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_cp_alnsrc: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_alnsrc: b1", i, b1[i], (long)123);
      }

      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_ci_aln(a1, b1);
      for (int i=0; i<ALIGN_OFF; i++) {
        errn += verify("test_ci_aln: a1", i, a1[i], (byte)-1);
      }
      for (int i=ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_ci_aln: a1", i, a1[i], (byte)-123);
      }
      for (int i=0; i<ARRLEN-ALIGN_OFF; i++) {
        errn += verify("test_ci_aln: b1", i, b1[i], (long)-103);
      }
      for (int i=ARRLEN-ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_ci_aln: b1", i, b1[i], (long)-1);
      }

      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_vi_aln(a1, b1, (byte)123, (long)103);
      for (int i=0; i<ARRLEN-ALIGN_OFF; i++) {
        errn += verify("test_vi_aln: a1", i, a1[i], (byte)123);
      }
      for (int i=ARRLEN-ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_vi_aln: a1", i, a1[i], (byte)-1);
      }
      for (int i=0; i<ALIGN_OFF; i++) {
        errn += verify("test_vi_aln: b1", i, b1[i], (long)-1);
      }
      for (int i=ALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_vi_aln: b1", i, b1[i], (long)103);
      }

      // Reset for 2 arrays with relative unaligned offset
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        a2[i] = 123;
        b1[i] = -1;
        b2[i] = 123;
      }
      test_cp_unalndst(a1, a2, b1, b2);
      for (int i=0; i<UNALIGN_OFF; i++) {
        errn += verify("test_cp_unalndst: a1", i, a1[i], (byte)-1);
        errn += verify("test_cp_unalndst: b1", i, b1[i], (long)-1);
      }
      for (int i=UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_cp_unalndst: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_unalndst: b1", i, b1[i], (long)123);
      }
      for (int i=0; i<ARRLEN; i++) {
        a2[i] = -123;
        b2[i] = -123;
      }
      test_cp_unalnsrc(a1, a2, b1, b2);
      for (int i=0; i<ARRLEN-UNALIGN_OFF; i++) {
        errn += verify("test_cp_unalnsrc: a1", i, a1[i], (byte)-123);
        errn += verify("test_cp_unalnsrc: b1", i, b1[i], (long)-123);
      }
      for (int i=ARRLEN-UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_cp_unalnsrc: a1", i, a1[i], (byte)123);
        errn += verify("test_cp_unalnsrc: b1", i, b1[i], (long)123);
      }
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_ci_unaln(a1, b1);
      for (int i=0; i<UNALIGN_OFF; i++) {
        errn += verify("test_ci_unaln: a1", i, a1[i], (byte)-1);
      }
      for (int i=UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_ci_unaln: a1", i, a1[i], (byte)-123);
      }
      for (int i=0; i<ARRLEN-UNALIGN_OFF; i++) {
        errn += verify("test_ci_unaln: b1", i, b1[i], (long)-103);
      }
      for (int i=ARRLEN-UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_ci_unaln: b1", i, b1[i], (long)-1);
      }
      for (int i=0; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_vi_unaln(a1, b1, (byte)123, (long)103);
      for (int i=0; i<ARRLEN-UNALIGN_OFF; i++) {
        errn += verify("test_vi_unaln: a1", i, a1[i], (byte)123);
      }
      for (int i=ARRLEN-UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_vi_unaln: a1", i, a1[i], (byte)-1);
      }
      for (int i=0; i<UNALIGN_OFF; i++) {
        errn += verify("test_vi_unaln: b1", i, b1[i], (long)-1);
      }
      for (int i=UNALIGN_OFF; i<ARRLEN; i++) {
        errn += verify("test_vi_unaln: b1", i, b1[i], (long)103);
      }

      // Reset for aligned overlap initialization
      for (int i=0; i<ALIGN_OFF; i++) {
        a1[i] = (byte)i;
        b1[i] = (long)i;
      }
      for (int i=ALIGN_OFF; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_cp_alndst(a1, a1, b1, b1);
      for (int i=0; i<ARRLEN; i++) {
        int v = i%ALIGN_OFF;
        errn += verify("test_cp_alndst_overlap: a1", i, a1[i], (byte)v);
        errn += verify("test_cp_alndst_overlap: b1", i, b1[i], (long)v);
      }
      for (int i=0; i<ALIGN_OFF; i++) {
        a1[i+ALIGN_OFF] = -1;
        b1[i+ALIGN_OFF] = -1;
      }
      test_cp_alnsrc(a1, a1, b1, b1);
      for (int i=0; i<ALIGN_OFF; i++) {
        errn += verify("test_cp_alnsrc_overlap: a1", i, a1[i], (byte)-1);
        errn += verify("test_cp_alnsrc_overlap: b1", i, b1[i], (long)-1);
      }
      for (int i=ALIGN_OFF; i<ARRLEN; i++) {
        int v = i%ALIGN_OFF;
        errn += verify("test_cp_alnsrc_overlap: a1", i, a1[i], (byte)v);
        errn += verify("test_cp_alnsrc_overlap: b1", i, b1[i], (long)v);
      }

      // Reset for unaligned overlap initialization
      for (int i=0; i<UNALIGN_OFF; i++) {
        a1[i] = (byte)i;
        b1[i] = (long)i;
      }
      for (int i=UNALIGN_OFF; i<ARRLEN; i++) {
        a1[i] = -1;
        b1[i] = -1;
      }
      test_cp_unalndst(a1, a1, b1, b1);
      for (int i=0; i<ARRLEN; i++) {
        int v = i%UNALIGN_OFF;
        errn += verify("test_cp_unalndst_overlap: a1", i, a1[i], (byte)v);
        errn += verify("test_cp_unalndst_overlap: b1", i, b1[i], (long)v);
      }
      for (int i=0; i<UNALIGN_OFF; i++) {
        a1[i+UNALIGN_OFF] = -1;
        b1[i+UNALIGN_OFF] = -1;
      }
      test_cp_unalnsrc(a1, a1, b1, b1);
      for (int i=0; i<UNALIGN_OFF; i++) {
        errn += verify("test_cp_unalnsrc_overlap: a1", i, a1[i], (byte)-1);
        errn += verify("test_cp_unalnsrc_overlap: b1", i, b1[i], (long)-1);
      }
      for (int i=UNALIGN_OFF; i<ARRLEN; i++) {
        int v = i%UNALIGN_OFF;
        errn += verify("test_cp_unalnsrc_overlap: a1", i, a1[i], (byte)v);
        errn += verify("test_cp_unalnsrc_overlap: b1", i, b1[i], (long)v);
      }
      for (int j = 0; j < bspecial.length; j++) {
        byte bytevalue = bspecial[j];
        for (int i = 0; i < ARRLEN; i++) {
          a1[i] = bytevalue;
        }
        test_conv_b2l(a1, b1);
        for (int i = 0; i < ARRLEN; i++) {
          errn += verify("test_conv_b2l: b1", i, b1[i], (long)bytevalue);
        }
      }
      for (int j = 0; j < lspecial.length; j++) {
        long longValue = lspecial[j];
        for (int i = 0; i < ARRLEN; i++) {
          b1[i] = longValue;
        }
        test_conv_l2b(a1, b1);
        for (int i = 0; i < ARRLEN; i++) {
          errn += verify("test_conv_l2b: a1", i, a1[i], (byte)longValue);
        }
      }
      Random r = Utils.getRandomInstance();
      for (int i = 0; i < ARRLEN; i++) {
        a1[i] = (byte)r.nextInt();
      }
      test_conv_b2l(a1, b1);
      for (int i = 0; i < ARRLEN; i++) {
        errn += verify("test_conv_b2l: b1", i, b1[i], (long)a1[i]);
      }
      for (int i = 0; i < ARRLEN; i++) {
        b1[i] = r.nextLong();
      }
      test_conv_l2b(a1, b1);
      for (int i = 0; i < ARRLEN; i++) {
        errn += verify("test_conv_l2b: a1", i, a1[i], (byte)b1[i]);
      }

    }

    if (errn > 0)
      return errn;

    System.out.println("Time");
    long start, end;
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_ci(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_ci: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_vi(a2, b2, (byte)123, (long)103);
    }
    end = System.currentTimeMillis();
    System.out.println("test_vi: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_ci_neg(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_ci_neg: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_vi_neg(a1, b1, (byte)123, (long)103);
    }
    end = System.currentTimeMillis();
    System.out.println("test_vi_neg: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_neg(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_neg: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_ci_oppos(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_ci_oppos: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_vi_oppos(a1, b1, (byte)123, (long)103);
    }
    end = System.currentTimeMillis();
    System.out.println("test_vi_oppos: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_oppos(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_oppos: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_ci_aln(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_ci_aln: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_vi_aln(a1, b1, (byte)123, (long)103);
    }
    end = System.currentTimeMillis();
    System.out.println("test_vi_aln: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_alndst(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_alndst: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_alnsrc(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_alnsrc: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_ci_unaln(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_ci_unaln: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_vi_unaln(a1, b1, (byte)123, (long)103);
    }
    end = System.currentTimeMillis();
    System.out.println("test_vi_unaln: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_unalndst(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_unalndst: " + (end - start));
    start = System.currentTimeMillis();
    for (int i=0; i<ITERS; i++) {
      test_cp_unalnsrc(a1, a2, b1, b2);
    }
    end = System.currentTimeMillis();
    System.out.println("test_cp_unalnsrc: " + (end - start));
    start = System.currentTimeMillis();
    for (int i = 0; i < ITERS; i++) {
      test_conv_b2l(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_conv_b2l: " + (end - start));
    start = System.currentTimeMillis();
    for (int i = 0; i < ITERS; i++) {
      test_conv_l2b(a1, b1);
    }
    end = System.currentTimeMillis();
    System.out.println("test_conv_l2b: " + (end - start));
    return errn;
  }

  static void test_ci(byte[] a, long[] b) {
    for (int i = 0; i < a.length; i+=1) {
      a[i] = -123;
      b[i] = -103;
    }
  }
  static void test_vi(byte[] a, long[] b, byte c, long d) {
    for (int i = 0; i < a.length; i+=1) {
      a[i] = c;
      b[i] = d;
    }
  }
  static void test_cp(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = 0; i < a.length; i+=1) {
      a[i] = b[i];
      c[i] = d[i];
    }
  }
  static void test_ci_neg(byte[] a, long[] b) {
    for (int i = a.length-1; i >= 0; i-=1) {
      a[i] = -123;
      b[i] = -103;
    }
  }
  static void test_vi_neg(byte[] a, long[] b, byte c, long d) {
    for (int i = a.length-1; i >= 0; i-=1) {
      a[i] = c;
      b[i] = d;
    }
  }
  static void test_cp_neg(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = a.length-1; i >= 0; i-=1) {
      a[i] = b[i];
      c[i] = d[i];
    }
  }
  static void test_ci_oppos(byte[] a, long[] b) {
    int limit = a.length-1;
    for (int i = 0; i < a.length; i+=1) {
      a[limit-i] = -123;
      b[i] = -103;
    }
  }
  static void test_vi_oppos(byte[] a, long[] b, byte c, long d) {
    int limit = a.length-1;
    for (int i = a.length-1; i >= 0; i-=1) {
      a[i] = c;
      b[limit-i] = d;
    }
  }
  static void test_cp_oppos(byte[] a, byte[] b, long[] c, long[] d) {
    int limit = a.length-1;
    for (int i = 0; i < a.length; i+=1) {
      a[i] = b[limit-i];
      c[limit-i] = d[i];
    }
  }
  static void test_ci_aln(byte[] a, long[] b) {
    for (int i = 0; i < a.length-ALIGN_OFF; i+=1) {
      a[i+ALIGN_OFF] = -123;
      b[i] = -103;
    }
  }
  static void test_vi_aln(byte[] a, long[] b, byte c, long d) {
    for (int i = 0; i < a.length-ALIGN_OFF; i+=1) {
      a[i] = c;
      b[i+ALIGN_OFF] = d;
    }
  }
  static void test_cp_alndst(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = 0; i < a.length-ALIGN_OFF; i+=1) {
      a[i+ALIGN_OFF] = b[i];
      c[i+ALIGN_OFF] = d[i];
    }
  }
  static void test_cp_alnsrc(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = 0; i < a.length-ALIGN_OFF; i+=1) {
      a[i] = b[i+ALIGN_OFF];
      c[i] = d[i+ALIGN_OFF];
    }
  }
  static void test_ci_unaln(byte[] a, long[] b) {
    for (int i = 0; i < a.length-UNALIGN_OFF; i+=1) {
      a[i+UNALIGN_OFF] = -123;
      b[i] = -103;
    }
  }
  static void test_vi_unaln(byte[] a, long[] b, byte c, long d) {
    for (int i = 0; i < a.length-UNALIGN_OFF; i+=1) {
      a[i] = c;
      b[i+UNALIGN_OFF] = d;
    }
  }
  static void test_cp_unalndst(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = 0; i < a.length-UNALIGN_OFF; i+=1) {
      a[i+UNALIGN_OFF] = b[i];
      c[i+UNALIGN_OFF] = d[i];
    }
  }
  static void test_cp_unalnsrc(byte[] a, byte[] b, long[] c, long[] d) {
    for (int i = 0; i < a.length-UNALIGN_OFF; i+=1) {
      a[i] = b[i+UNALIGN_OFF];
      c[i] = d[i+UNALIGN_OFF];
    }
  }

  static void test_conv_b2l(byte[] a, long[] b) {
    for (int i = 0; i < a.length; i+=1) {
      b[i] = (long) a[i];
    }
  }

  static void test_conv_l2b(byte[] a, long[] b) {
    for (int i = 0; i < a.length; i+=1) {
      a[i] = (byte) b[i];
    }
  }

  static int verify(String text, int i, byte elem, byte val) {
    if (elem != val) {
      System.err.println(text + "[" + i + "] = " + elem + " != " + val);
      return 1;
    }
    return 0;
  }
  static int verify(String text, int i, long elem, long val) {
    if (elem != val) {
      System.err.println(text + "[" + i + "] = " + elem + " != " + val);
      return 1;
    }
    return 0;
  }
}

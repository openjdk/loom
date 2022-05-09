/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef CPU_AARCH64_C2_MACROASSEMBLER_AARCH64_HPP
#define CPU_AARCH64_C2_MACROASSEMBLER_AARCH64_HPP

// C2_MacroAssembler contains high-level macros for C2

 public:

  void string_compare(Register str1, Register str2,
                      Register cnt1, Register cnt2, Register result,
                      Register tmp1, Register tmp2, FloatRegister vtmp1,
                      FloatRegister vtmp2, FloatRegister vtmp3, int ae);

  void string_indexof(Register str1, Register str2,
                      Register cnt1, Register cnt2,
                      Register tmp1, Register tmp2,
                      Register tmp3, Register tmp4,
                      Register tmp5, Register tmp6,
                      int int_cnt1, Register result, int ae);

  void string_indexof_char(Register str1, Register cnt1,
                           Register ch, Register result,
                           Register tmp1, Register tmp2, Register tmp3);

  void stringL_indexof_char(Register str1, Register cnt1,
                            Register ch, Register result,
                            Register tmp1, Register tmp2, Register tmp3);

  void string_indexof_char_sve(Register str1, Register cnt1,
                               Register ch, Register result,
                               FloatRegister ztmp1, FloatRegister ztmp2,
                               PRegister pgtmp, PRegister ptmp, bool isL);

  // Compress the least significant bit of each byte to the rightmost and clear
  // the higher garbage bits.
  void bytemask_compress(Register dst);

  // Pack the lowest-numbered bit of each mask element in src into a long value
  // in dst, at most the first 64 lane elements.
  void sve_vmask_tolong(Register dst, PRegister src, BasicType bt, int lane_cnt,
                        FloatRegister vtmp1, FloatRegister vtmp2);

  // SIMD&FP comparison
  void neon_compare(FloatRegister dst, BasicType bt, FloatRegister src1,
                    FloatRegister src2, int cond, bool isQ);

  void sve_compare(PRegister pd, BasicType bt, PRegister pg,
                   FloatRegister zn, FloatRegister zm, int cond);

  void sve_vmask_lasttrue(Register dst, BasicType bt, PRegister src, PRegister ptmp);

  void sve_vector_extend(FloatRegister dst, SIMD_RegVariant dst_size,
                         FloatRegister src, SIMD_RegVariant src_size);

  void sve_vector_narrow(FloatRegister dst, SIMD_RegVariant dst_size,
                         FloatRegister src, SIMD_RegVariant src_size, FloatRegister tmp);

  void sve_vmaskcast_extend(PRegister dst, PRegister src,
                            uint dst_element_length_in_bytes, uint src_element_lenght_in_bytes);

  void sve_vmaskcast_narrow(PRegister dst, PRegister src,
                            uint dst_element_length_in_bytes, uint src_element_lenght_in_bytes);

  void sve_reduce_integral(int opc, Register dst, BasicType bt, Register src1,
                           FloatRegister src2, PRegister pg, FloatRegister tmp);

  // Set elements of the dst predicate to true if the element number is
  // in the range of [0, lane_cnt), or to false otherwise.
  void sve_ptrue_lanecnt(PRegister dst, SIMD_RegVariant size, int lane_cnt);

  // Extract a scalar element from an sve vector at position 'idx'.
  // The input elements in src are expected to be of integral type.
  void sve_extract_integral(Register dst, SIMD_RegVariant size, FloatRegister src, int idx,
                            bool is_signed, FloatRegister vtmp);

  // java.lang.Math::round intrinsics
  void vector_round_neon(FloatRegister dst, FloatRegister src, FloatRegister tmp1,
                         FloatRegister tmp2, FloatRegister tmp3,
                         SIMD_Arrangement T);
  void vector_round_sve(FloatRegister dst, FloatRegister src, FloatRegister tmp1,
                        FloatRegister tmp2, PRegister ptmp,
                        SIMD_RegVariant T);

#endif // CPU_AARCH64_C2_MACROASSEMBLER_AARCH64_HPP

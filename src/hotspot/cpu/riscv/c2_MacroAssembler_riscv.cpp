/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2022, Huawei Technologies Co., Ltd. All rights reserved.
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

#include "precompiled.hpp"
#include "asm/assembler.hpp"
#include "asm/assembler.inline.hpp"
#include "opto/c2_MacroAssembler.hpp"
#include "opto/intrinsicnode.hpp"
#include "opto/subnode.hpp"
#include "runtime/stubRoutines.hpp"

#ifdef PRODUCT
#define BLOCK_COMMENT(str) /* nothing */
#define STOP(error) stop(error)
#else
#define BLOCK_COMMENT(str) block_comment(str)
#define STOP(error) block_comment(error); stop(error)
#endif

#define BIND(label) bind(label); BLOCK_COMMENT(#label ":")

// short string
// StringUTF16.indexOfChar
// StringLatin1.indexOfChar
void C2_MacroAssembler::string_indexof_char_short(Register str1, Register cnt1,
                                                  Register ch, Register result,
                                                  bool isL)
{
  Register ch1 = t0;
  Register index = t1;

  BLOCK_COMMENT("string_indexof_char_short {");

  Label LOOP, LOOP1, LOOP4, LOOP8;
  Label MATCH,  MATCH1, MATCH2, MATCH3,
        MATCH4, MATCH5, MATCH6, MATCH7, NOMATCH;

  mv(result, -1);
  mv(index, zr);

  bind(LOOP);
  addi(t0, index, 8);
  ble(t0, cnt1, LOOP8);
  addi(t0, index, 4);
  ble(t0, cnt1, LOOP4);
  j(LOOP1);

  bind(LOOP8);
  isL ? lbu(ch1, Address(str1, 0)) : lhu(ch1, Address(str1, 0));
  beq(ch, ch1, MATCH);
  isL ? lbu(ch1, Address(str1, 1)) : lhu(ch1, Address(str1, 2));
  beq(ch, ch1, MATCH1);
  isL ? lbu(ch1, Address(str1, 2)) : lhu(ch1, Address(str1, 4));
  beq(ch, ch1, MATCH2);
  isL ? lbu(ch1, Address(str1, 3)) : lhu(ch1, Address(str1, 6));
  beq(ch, ch1, MATCH3);
  isL ? lbu(ch1, Address(str1, 4)) : lhu(ch1, Address(str1, 8));
  beq(ch, ch1, MATCH4);
  isL ? lbu(ch1, Address(str1, 5)) : lhu(ch1, Address(str1, 10));
  beq(ch, ch1, MATCH5);
  isL ? lbu(ch1, Address(str1, 6)) : lhu(ch1, Address(str1, 12));
  beq(ch, ch1, MATCH6);
  isL ? lbu(ch1, Address(str1, 7)) : lhu(ch1, Address(str1, 14));
  beq(ch, ch1, MATCH7);
  addi(index, index, 8);
  addi(str1, str1, isL ? 8 : 16);
  blt(index, cnt1, LOOP);
  j(NOMATCH);

  bind(LOOP4);
  isL ? lbu(ch1, Address(str1, 0)) : lhu(ch1, Address(str1, 0));
  beq(ch, ch1, MATCH);
  isL ? lbu(ch1, Address(str1, 1)) : lhu(ch1, Address(str1, 2));
  beq(ch, ch1, MATCH1);
  isL ? lbu(ch1, Address(str1, 2)) : lhu(ch1, Address(str1, 4));
  beq(ch, ch1, MATCH2);
  isL ? lbu(ch1, Address(str1, 3)) : lhu(ch1, Address(str1, 6));
  beq(ch, ch1, MATCH3);
  addi(index, index, 4);
  addi(str1, str1, isL ? 4 : 8);
  bge(index, cnt1, NOMATCH);

  bind(LOOP1);
  isL ? lbu(ch1, Address(str1)) : lhu(ch1, Address(str1));
  beq(ch, ch1, MATCH);
  addi(index, index, 1);
  addi(str1, str1, isL ? 1 : 2);
  blt(index, cnt1, LOOP1);
  j(NOMATCH);

  bind(MATCH1);
  addi(index, index, 1);
  j(MATCH);

  bind(MATCH2);
  addi(index, index, 2);
  j(MATCH);

  bind(MATCH3);
  addi(index, index, 3);
  j(MATCH);

  bind(MATCH4);
  addi(index, index, 4);
  j(MATCH);

  bind(MATCH5);
  addi(index, index, 5);
  j(MATCH);

  bind(MATCH6);
  addi(index, index, 6);
  j(MATCH);

  bind(MATCH7);
  addi(index, index, 7);

  bind(MATCH);
  mv(result, index);
  bind(NOMATCH);
  BLOCK_COMMENT("} string_indexof_char_short");
}

// StringUTF16.indexOfChar
// StringLatin1.indexOfChar
void C2_MacroAssembler::string_indexof_char(Register str1, Register cnt1,
                                            Register ch, Register result,
                                            Register tmp1, Register tmp2,
                                            Register tmp3, Register tmp4,
                                            bool isL)
{
  Label CH1_LOOP, HIT, NOMATCH, DONE, DO_LONG;
  Register ch1 = t0;
  Register orig_cnt = t1;
  Register mask1 = tmp3;
  Register mask2 = tmp2;
  Register match_mask = tmp1;
  Register trailing_char = tmp4;
  Register unaligned_elems = tmp4;

  BLOCK_COMMENT("string_indexof_char {");
  beqz(cnt1, NOMATCH);

  addi(t0, cnt1, isL ? -32 : -16);
  bgtz(t0, DO_LONG);
  string_indexof_char_short(str1, cnt1, ch, result, isL);
  j(DONE);

  bind(DO_LONG);
  mv(orig_cnt, cnt1);
  if (AvoidUnalignedAccesses) {
    Label ALIGNED;
    andi(unaligned_elems, str1, 0x7);
    beqz(unaligned_elems, ALIGNED);
    sub(unaligned_elems, unaligned_elems, 8);
    neg(unaligned_elems, unaligned_elems);
    if (!isL) {
      srli(unaligned_elems, unaligned_elems, 1);
    }
    // do unaligned part per element
    string_indexof_char_short(str1, unaligned_elems, ch, result, isL);
    bgez(result, DONE);
    mv(orig_cnt, cnt1);
    sub(cnt1, cnt1, unaligned_elems);
    bind(ALIGNED);
  }

  // duplicate ch
  if (isL) {
    slli(ch1, ch, 8);
    orr(ch, ch1, ch);
  }
  slli(ch1, ch, 16);
  orr(ch, ch1, ch);
  slli(ch1, ch, 32);
  orr(ch, ch1, ch);

  if (!isL) {
    slli(cnt1, cnt1, 1);
  }

  uint64_t mask0101 = UCONST64(0x0101010101010101);
  uint64_t mask0001 = UCONST64(0x0001000100010001);
  mv(mask1, isL ? mask0101 : mask0001);
  uint64_t mask7f7f = UCONST64(0x7f7f7f7f7f7f7f7f);
  uint64_t mask7fff = UCONST64(0x7fff7fff7fff7fff);
  mv(mask2, isL ? mask7f7f : mask7fff);

  bind(CH1_LOOP);
  ld(ch1, Address(str1));
  addi(str1, str1, 8);
  addi(cnt1, cnt1, -8);
  compute_match_mask(ch1, ch, match_mask, mask1, mask2);
  bnez(match_mask, HIT);
  bgtz(cnt1, CH1_LOOP);
  j(NOMATCH);

  bind(HIT);
  ctzc_bit(trailing_char, match_mask, isL, ch1, result);
  srli(trailing_char, trailing_char, 3);
  addi(cnt1, cnt1, 8);
  ble(cnt1, trailing_char, NOMATCH);
  // match case
  if (!isL) {
    srli(cnt1, cnt1, 1);
    srli(trailing_char, trailing_char, 1);
  }

  sub(result, orig_cnt, cnt1);
  add(result, result, trailing_char);
  j(DONE);

  bind(NOMATCH);
  mv(result, -1);

  bind(DONE);
  BLOCK_COMMENT("} string_indexof_char");
}

typedef void (MacroAssembler::* load_chr_insn)(Register rd, const Address &adr, Register temp);

// Search for needle in haystack and return index or -1
// x10: result
// x11: haystack
// x12: haystack_len
// x13: needle
// x14: needle_len
void C2_MacroAssembler::string_indexof(Register haystack, Register needle,
                                       Register haystack_len, Register needle_len,
                                       Register tmp1, Register tmp2,
                                       Register tmp3, Register tmp4,
                                       Register tmp5, Register tmp6,
                                       Register result, int ae)
{
  assert(ae != StrIntrinsicNode::LU, "Invalid encoding");

  Label LINEARSEARCH, LINEARSTUB, DONE, NOMATCH;

  Register ch1 = t0;
  Register ch2 = t1;
  Register nlen_tmp = tmp1; // needle len tmp
  Register hlen_tmp = tmp2; // haystack len tmp
  Register result_tmp = tmp4;

  bool isLL = ae == StrIntrinsicNode::LL;

  bool needle_isL = ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UL;
  bool haystack_isL = ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::LU;
  int needle_chr_shift = needle_isL ? 0 : 1;
  int haystack_chr_shift = haystack_isL ? 0 : 1;
  int needle_chr_size = needle_isL ? 1 : 2;
  int haystack_chr_size = haystack_isL ? 1 : 2;
  load_chr_insn needle_load_1chr = needle_isL ? (load_chr_insn)&MacroAssembler::lbu :
                              (load_chr_insn)&MacroAssembler::lhu;
  load_chr_insn haystack_load_1chr = haystack_isL ? (load_chr_insn)&MacroAssembler::lbu :
                                (load_chr_insn)&MacroAssembler::lhu;

  BLOCK_COMMENT("string_indexof {");

  // Note, inline_string_indexOf() generates checks:
  // if (pattern.count > src.count) return -1;
  // if (pattern.count == 0) return 0;

  // We have two strings, a source string in haystack, haystack_len and a pattern string
  // in needle, needle_len. Find the first occurence of pattern in source or return -1.

  // For larger pattern and source we use a simplified Boyer Moore algorithm.
  // With a small pattern and source we use linear scan.

  // needle_len >=8 && needle_len < 256 && needle_len < haystack_len/4, use bmh algorithm.
  sub(result_tmp, haystack_len, needle_len);
  // needle_len < 8, use linear scan
  sub(t0, needle_len, 8);
  bltz(t0, LINEARSEARCH);
  // needle_len >= 256, use linear scan
  sub(t0, needle_len, 256);
  bgez(t0, LINEARSTUB);
  // needle_len >= haystack_len/4, use linear scan
  srli(t0, haystack_len, 2);
  bge(needle_len, t0, LINEARSTUB);

  // Boyer-Moore-Horspool introduction:
  // The Boyer Moore alogorithm is based on the description here:-
  //
  // http://en.wikipedia.org/wiki/Boyer%E2%80%93Moore_string_search_algorithm
  //
  // This describes and algorithm with 2 shift rules. The 'Bad Character' rule
  // and the 'Good Suffix' rule.
  //
  // These rules are essentially heuristics for how far we can shift the
  // pattern along the search string.
  //
  // The implementation here uses the 'Bad Character' rule only because of the
  // complexity of initialisation for the 'Good Suffix' rule.
  //
  // This is also known as the Boyer-Moore-Horspool algorithm:
  //
  // http://en.wikipedia.org/wiki/Boyer-Moore-Horspool_algorithm
  //
  // #define ASIZE 256
  //
  //    int bm(unsigned char *pattern, int m, unsigned char *src, int n) {
  //      int i, j;
  //      unsigned c;
  //      unsigned char bc[ASIZE];
  //
  //      /* Preprocessing */
  //      for (i = 0; i < ASIZE; ++i)
  //        bc[i] = m;
  //      for (i = 0; i < m - 1; ) {
  //        c = pattern[i];
  //        ++i;
  //        // c < 256 for Latin1 string, so, no need for branch
  //        #ifdef PATTERN_STRING_IS_LATIN1
  //        bc[c] = m - i;
  //        #else
  //        if (c < ASIZE) bc[c] = m - i;
  //        #endif
  //      }
  //
  //      /* Searching */
  //      j = 0;
  //      while (j <= n - m) {
  //        c = src[i+j];
  //        if (pattern[m-1] == c)
  //          int k;
  //          for (k = m - 2; k >= 0 && pattern[k] == src[k + j]; --k);
  //          if (k < 0) return j;
  //          // c < 256 for Latin1 string, so, no need for branch
  //          #ifdef SOURCE_STRING_IS_LATIN1_AND_PATTERN_STRING_IS_LATIN1
  //          // LL case: (c< 256) always true. Remove branch
  //          j += bc[pattern[j+m-1]];
  //          #endif
  //          #ifdef SOURCE_STRING_IS_UTF_AND_PATTERN_STRING_IS_UTF
  //          // UU case: need if (c<ASIZE) check. Skip 1 character if not.
  //          if (c < ASIZE)
  //            j += bc[pattern[j+m-1]];
  //          else
  //            j += 1
  //          #endif
  //          #ifdef SOURCE_IS_UTF_AND_PATTERN_IS_LATIN1
  //          // UL case: need if (c<ASIZE) check. Skip <pattern length> if not.
  //          if (c < ASIZE)
  //            j += bc[pattern[j+m-1]];
  //          else
  //            j += m
  //          #endif
  //      }
  //      return -1;
  //    }

  // temp register:t0, tmp1, tmp2, tmp3, tmp4, tmp5, tmp6, result
  Label BCLOOP, BCSKIP, BMLOOPSTR2, BMLOOPSTR1, BMSKIP, BMADV, BMMATCH,
        BMLOOPSTR1_LASTCMP, BMLOOPSTR1_CMP, BMLOOPSTR1_AFTER_LOAD, BM_INIT_LOOP;

  Register haystack_end = haystack_len;
  Register skipch = tmp2;

  // pattern length is >=8, so, we can read at least 1 register for cases when
  // UTF->Latin1 conversion is not needed(8 LL or 4UU) and half register for
  // UL case. We'll re-read last character in inner pre-loop code to have
  // single outer pre-loop load
  const int firstStep = isLL ? 7 : 3;

  const int ASIZE = 256;
  const int STORE_BYTES = 8; // 8 bytes stored per instruction(sd)

  sub(sp, sp, ASIZE);

  // init BC offset table with default value: needle_len
  slli(t0, needle_len, 8);
  orr(t0, t0, needle_len); // [63...16][needle_len][needle_len]
  slli(tmp1, t0, 16);
  orr(t0, tmp1, t0); // [63...32][needle_len][needle_len][needle_len][needle_len]
  slli(tmp1, t0, 32);
  orr(tmp5, tmp1, t0); // tmp5: 8 elements [needle_len]

  mv(ch1, sp);  // ch1 is t0
  mv(tmp6, ASIZE / STORE_BYTES); // loop iterations

  bind(BM_INIT_LOOP);
  // for (i = 0; i < ASIZE; ++i)
  //   bc[i] = m;
  for (int i = 0; i < 4; i++) {
    sd(tmp5, Address(ch1, i * wordSize));
  }
  add(ch1, ch1, 32);
  sub(tmp6, tmp6, 4);
  bgtz(tmp6, BM_INIT_LOOP);

  sub(nlen_tmp, needle_len, 1); // m - 1, index of the last element in pattern
  Register orig_haystack = tmp5;
  mv(orig_haystack, haystack);
  // result_tmp = tmp4
  shadd(haystack_end, result_tmp, haystack, haystack_end, haystack_chr_shift);
  sub(ch2, needle_len, 1); // bc offset init value, ch2 is t1
  mv(tmp3, needle);

  //  for (i = 0; i < m - 1; ) {
  //    c = pattern[i];
  //    ++i;
  //    // c < 256 for Latin1 string, so, no need for branch
  //    #ifdef PATTERN_STRING_IS_LATIN1
  //    bc[c] = m - i;
  //    #else
  //    if (c < ASIZE) bc[c] = m - i;
  //    #endif
  //  }
  bind(BCLOOP);
  (this->*needle_load_1chr)(ch1, Address(tmp3), noreg);
  add(tmp3, tmp3, needle_chr_size);
  if (!needle_isL) {
    // ae == StrIntrinsicNode::UU
    mv(tmp6, ASIZE);
    bgeu(ch1, tmp6, BCSKIP);
  }
  add(tmp4, sp, ch1);
  sb(ch2, Address(tmp4)); // store skip offset to BC offset table

  bind(BCSKIP);
  sub(ch2, ch2, 1); // for next pattern element, skip distance -1
  bgtz(ch2, BCLOOP);

  // tmp6: pattern end, address after needle
  shadd(tmp6, needle_len, needle, tmp6, needle_chr_shift);
  if (needle_isL == haystack_isL) {
    // load last 8 bytes (8LL/4UU symbols)
    ld(tmp6, Address(tmp6, -wordSize));
  } else {
    // UL: from UTF-16(source) search Latin1(pattern)
    lwu(tmp6, Address(tmp6, -wordSize / 2)); // load last 4 bytes(4 symbols)
    // convert Latin1 to UTF. eg: 0x0000abcd -> 0x0a0b0c0d
    // We'll have to wait until load completed, but it's still faster than per-character loads+checks
    srli(tmp3, tmp6, BitsPerByte * (wordSize / 2 - needle_chr_size)); // pattern[m-1], eg:0x0000000a
    slli(ch2, tmp6, XLEN - 24);
    srli(ch2, ch2, XLEN - 8); // pattern[m-2], 0x0000000b
    slli(ch1, tmp6, XLEN - 16);
    srli(ch1, ch1, XLEN - 8); // pattern[m-3], 0x0000000c
    andi(tmp6, tmp6, 0xff); // pattern[m-4], 0x0000000d
    slli(ch2, ch2, 16);
    orr(ch2, ch2, ch1); // 0x00000b0c
    slli(result, tmp3, 48); // use result as temp register
    orr(tmp6, tmp6, result); // 0x0a00000d
    slli(result, ch2, 16);
    orr(tmp6, tmp6, result); // UTF-16:0x0a0b0c0d
  }

  // i = m - 1;
  // skipch = j + i;
  // if (skipch == pattern[m - 1]
  //   for (k = m - 2; k >= 0 && pattern[k] == src[k + j]; --k);
  // else
  //   move j with bad char offset table
  bind(BMLOOPSTR2);
  // compare pattern to source string backward
  shadd(result, nlen_tmp, haystack, result, haystack_chr_shift);
  (this->*haystack_load_1chr)(skipch, Address(result), noreg);
  sub(nlen_tmp, nlen_tmp, firstStep); // nlen_tmp is positive here, because needle_len >= 8
  if (needle_isL == haystack_isL) {
    // re-init tmp3. It's for free because it's executed in parallel with
    // load above. Alternative is to initialize it before loop, but it'll
    // affect performance on in-order systems with 2 or more ld/st pipelines
    srli(tmp3, tmp6, BitsPerByte * (wordSize - needle_chr_size)); // UU/LL: pattern[m-1]
  }
  if (!isLL) { // UU/UL case
    slli(ch2, nlen_tmp, 1); // offsets in bytes
  }
  bne(tmp3, skipch, BMSKIP); // if not equal, skipch is bad char
  add(result, haystack, isLL ? nlen_tmp : ch2);
  ld(ch2, Address(result)); // load 8 bytes from source string
  mv(ch1, tmp6);
  if (isLL) {
    j(BMLOOPSTR1_AFTER_LOAD);
  } else {
    sub(nlen_tmp, nlen_tmp, 1); // no need to branch for UU/UL case. cnt1 >= 8
    j(BMLOOPSTR1_CMP);
  }

  bind(BMLOOPSTR1);
  shadd(ch1, nlen_tmp, needle, ch1, needle_chr_shift);
  (this->*needle_load_1chr)(ch1, Address(ch1), noreg);
  shadd(ch2, nlen_tmp, haystack, ch2, haystack_chr_shift);
  (this->*haystack_load_1chr)(ch2, Address(ch2), noreg);

  bind(BMLOOPSTR1_AFTER_LOAD);
  sub(nlen_tmp, nlen_tmp, 1);
  bltz(nlen_tmp, BMLOOPSTR1_LASTCMP);

  bind(BMLOOPSTR1_CMP);
  beq(ch1, ch2, BMLOOPSTR1);

  bind(BMSKIP);
  if (!isLL) {
    // if we've met UTF symbol while searching Latin1 pattern, then we can
    // skip needle_len symbols
    if (needle_isL != haystack_isL) {
      mv(result_tmp, needle_len);
    } else {
      mv(result_tmp, 1);
    }
    mv(t0, ASIZE);
    bgeu(skipch, t0, BMADV);
  }
  add(result_tmp, sp, skipch);
  lbu(result_tmp, Address(result_tmp)); // load skip offset

  bind(BMADV);
  sub(nlen_tmp, needle_len, 1);
  // move haystack after bad char skip offset
  shadd(haystack, result_tmp, haystack, result, haystack_chr_shift);
  ble(haystack, haystack_end, BMLOOPSTR2);
  add(sp, sp, ASIZE);
  j(NOMATCH);

  bind(BMLOOPSTR1_LASTCMP);
  bne(ch1, ch2, BMSKIP);

  bind(BMMATCH);
  sub(result, haystack, orig_haystack);
  if (!haystack_isL) {
    srli(result, result, 1);
  }
  add(sp, sp, ASIZE);
  j(DONE);

  bind(LINEARSTUB);
  sub(t0, needle_len, 16); // small patterns still should be handled by simple algorithm
  bltz(t0, LINEARSEARCH);
  mv(result, zr);
  RuntimeAddress stub = NULL;
  if (isLL) {
    stub = RuntimeAddress(StubRoutines::riscv::string_indexof_linear_ll());
    assert(stub.target() != NULL, "string_indexof_linear_ll stub has not been generated");
  } else if (needle_isL) {
    stub = RuntimeAddress(StubRoutines::riscv::string_indexof_linear_ul());
    assert(stub.target() != NULL, "string_indexof_linear_ul stub has not been generated");
  } else {
    stub = RuntimeAddress(StubRoutines::riscv::string_indexof_linear_uu());
    assert(stub.target() != NULL, "string_indexof_linear_uu stub has not been generated");
  }
  trampoline_call(stub);
  j(DONE);

  bind(NOMATCH);
  mv(result, -1);
  j(DONE);

  bind(LINEARSEARCH);
  string_indexof_linearscan(haystack, needle, haystack_len, needle_len, tmp1, tmp2, tmp3, tmp4, -1, result, ae);

  bind(DONE);
  BLOCK_COMMENT("} string_indexof");
}

// string_indexof
// result: x10
// src: x11
// src_count: x12
// pattern: x13
// pattern_count: x14 or 1/2/3/4
void C2_MacroAssembler::string_indexof_linearscan(Register haystack, Register needle,
                                               Register haystack_len, Register needle_len,
                                               Register tmp1, Register tmp2,
                                               Register tmp3, Register tmp4,
                                               int needle_con_cnt, Register result, int ae)
{
  // Note:
  // needle_con_cnt > 0 means needle_len register is invalid, needle length is constant
  // for UU/LL: needle_con_cnt[1, 4], UL: needle_con_cnt = 1
  assert(needle_con_cnt <= 4, "Invalid needle constant count");
  assert(ae != StrIntrinsicNode::LU, "Invalid encoding");

  Register ch1 = t0;
  Register ch2 = t1;
  Register hlen_neg = haystack_len, nlen_neg = needle_len;
  Register nlen_tmp = tmp1, hlen_tmp = tmp2, result_tmp = tmp4;

  bool isLL = ae == StrIntrinsicNode::LL;

  bool needle_isL = ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UL;
  bool haystack_isL = ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::LU;
  int needle_chr_shift = needle_isL ? 0 : 1;
  int haystack_chr_shift = haystack_isL ? 0 : 1;
  int needle_chr_size = needle_isL ? 1 : 2;
  int haystack_chr_size = haystack_isL ? 1 : 2;

  load_chr_insn needle_load_1chr = needle_isL ? (load_chr_insn)&MacroAssembler::lbu :
                              (load_chr_insn)&MacroAssembler::lhu;
  load_chr_insn haystack_load_1chr = haystack_isL ? (load_chr_insn)&MacroAssembler::lbu :
                                (load_chr_insn)&MacroAssembler::lhu;
  load_chr_insn load_2chr = isLL ? (load_chr_insn)&MacroAssembler::lhu : (load_chr_insn)&MacroAssembler::lwu;
  load_chr_insn load_4chr = isLL ? (load_chr_insn)&MacroAssembler::lwu : (load_chr_insn)&MacroAssembler::ld;

  Label DO1, DO2, DO3, MATCH, NOMATCH, DONE;

  Register first = tmp3;

  if (needle_con_cnt == -1) {
    Label DOSHORT, FIRST_LOOP, STR2_NEXT, STR1_LOOP, STR1_NEXT;

    sub(t0, needle_len, needle_isL == haystack_isL ? 4 : 2);
    bltz(t0, DOSHORT);

    (this->*needle_load_1chr)(first, Address(needle), noreg);
    slli(t0, needle_len, needle_chr_shift);
    add(needle, needle, t0);
    neg(nlen_neg, t0);
    slli(t0, result_tmp, haystack_chr_shift);
    add(haystack, haystack, t0);
    neg(hlen_neg, t0);

    bind(FIRST_LOOP);
    add(t0, haystack, hlen_neg);
    (this->*haystack_load_1chr)(ch2, Address(t0), noreg);
    beq(first, ch2, STR1_LOOP);

    bind(STR2_NEXT);
    add(hlen_neg, hlen_neg, haystack_chr_size);
    blez(hlen_neg, FIRST_LOOP);
    j(NOMATCH);

    bind(STR1_LOOP);
    add(nlen_tmp, nlen_neg, needle_chr_size);
    add(hlen_tmp, hlen_neg, haystack_chr_size);
    bgez(nlen_tmp, MATCH);

    bind(STR1_NEXT);
    add(ch1, needle, nlen_tmp);
    (this->*needle_load_1chr)(ch1, Address(ch1), noreg);
    add(ch2, haystack, hlen_tmp);
    (this->*haystack_load_1chr)(ch2, Address(ch2), noreg);
    bne(ch1, ch2, STR2_NEXT);
    add(nlen_tmp, nlen_tmp, needle_chr_size);
    add(hlen_tmp, hlen_tmp, haystack_chr_size);
    bltz(nlen_tmp, STR1_NEXT);
    j(MATCH);

    bind(DOSHORT);
    if (needle_isL == haystack_isL) {
      sub(t0, needle_len, 2);
      bltz(t0, DO1);
      bgtz(t0, DO3);
    }
  }

  if (needle_con_cnt == 4) {
    Label CH1_LOOP;
    (this->*load_4chr)(ch1, Address(needle), noreg);
    sub(result_tmp, haystack_len, 4);
    slli(tmp3, result_tmp, haystack_chr_shift); // result as tmp
    add(haystack, haystack, tmp3);
    neg(hlen_neg, tmp3);

    bind(CH1_LOOP);
    add(ch2, haystack, hlen_neg);
    (this->*load_4chr)(ch2, Address(ch2), noreg);
    beq(ch1, ch2, MATCH);
    add(hlen_neg, hlen_neg, haystack_chr_size);
    blez(hlen_neg, CH1_LOOP);
    j(NOMATCH);
  }

  if ((needle_con_cnt == -1 && needle_isL == haystack_isL) || needle_con_cnt == 2) {
    Label CH1_LOOP;
    BLOCK_COMMENT("string_indexof DO2 {");
    bind(DO2);
    (this->*load_2chr)(ch1, Address(needle), noreg);
    if (needle_con_cnt == 2) {
      sub(result_tmp, haystack_len, 2);
    }
    slli(tmp3, result_tmp, haystack_chr_shift);
    add(haystack, haystack, tmp3);
    neg(hlen_neg, tmp3);

    bind(CH1_LOOP);
    add(tmp3, haystack, hlen_neg);
    (this->*load_2chr)(ch2, Address(tmp3), noreg);
    beq(ch1, ch2, MATCH);
    add(hlen_neg, hlen_neg, haystack_chr_size);
    blez(hlen_neg, CH1_LOOP);
    j(NOMATCH);
    BLOCK_COMMENT("} string_indexof DO2");
  }

  if ((needle_con_cnt == -1 && needle_isL == haystack_isL) || needle_con_cnt == 3) {
    Label FIRST_LOOP, STR2_NEXT, STR1_LOOP;
    BLOCK_COMMENT("string_indexof DO3 {");

    bind(DO3);
    (this->*load_2chr)(first, Address(needle), noreg);
    (this->*needle_load_1chr)(ch1, Address(needle, 2 * needle_chr_size), noreg);
    if (needle_con_cnt == 3) {
      sub(result_tmp, haystack_len, 3);
    }
    slli(hlen_tmp, result_tmp, haystack_chr_shift);
    add(haystack, haystack, hlen_tmp);
    neg(hlen_neg, hlen_tmp);

    bind(FIRST_LOOP);
    add(ch2, haystack, hlen_neg);
    (this->*load_2chr)(ch2, Address(ch2), noreg);
    beq(first, ch2, STR1_LOOP);

    bind(STR2_NEXT);
    add(hlen_neg, hlen_neg, haystack_chr_size);
    blez(hlen_neg, FIRST_LOOP);
    j(NOMATCH);

    bind(STR1_LOOP);
    add(hlen_tmp, hlen_neg, 2 * haystack_chr_size);
    add(ch2, haystack, hlen_tmp);
    (this->*haystack_load_1chr)(ch2, Address(ch2), noreg);
    bne(ch1, ch2, STR2_NEXT);
    j(MATCH);
    BLOCK_COMMENT("} string_indexof DO3");
  }

  if (needle_con_cnt == -1 || needle_con_cnt == 1) {
    Label DO1_LOOP;

    BLOCK_COMMENT("string_indexof DO1 {");
    bind(DO1);
    (this->*needle_load_1chr)(ch1, Address(needle), noreg);
    sub(result_tmp, haystack_len, 1);
    mv(tmp3, result_tmp);
    if (haystack_chr_shift) {
      slli(tmp3, result_tmp, haystack_chr_shift);
    }
    add(haystack, haystack, tmp3);
    neg(hlen_neg, tmp3);

    bind(DO1_LOOP);
    add(tmp3, haystack, hlen_neg);
    (this->*haystack_load_1chr)(ch2, Address(tmp3), noreg);
    beq(ch1, ch2, MATCH);
    add(hlen_neg, hlen_neg, haystack_chr_size);
    blez(hlen_neg, DO1_LOOP);
    BLOCK_COMMENT("} string_indexof DO1");
  }

  bind(NOMATCH);
  mv(result, -1);
  j(DONE);

  bind(MATCH);
  srai(t0, hlen_neg, haystack_chr_shift);
  add(result, result_tmp, t0);

  bind(DONE);
}

// Compare strings.
void C2_MacroAssembler::string_compare(Register str1, Register str2,
                                    Register cnt1, Register cnt2, Register result, Register tmp1, Register tmp2,
                                    Register tmp3, int ae)
{
  Label DONE, SHORT_LOOP, SHORT_STRING, SHORT_LAST, TAIL, STUB,
      DIFFERENCE, NEXT_WORD, SHORT_LOOP_TAIL, SHORT_LAST2, SHORT_LAST_INIT,
      SHORT_LOOP_START, TAIL_CHECK, L;

  const int STUB_THRESHOLD = 64 + 8;
  bool isLL = ae == StrIntrinsicNode::LL;
  bool isLU = ae == StrIntrinsicNode::LU;
  bool isUL = ae == StrIntrinsicNode::UL;

  bool str1_isL = isLL || isLU;
  bool str2_isL = isLL || isUL;

  // for L strings, 1 byte for 1 character
  // for U strings, 2 bytes for 1 character
  int str1_chr_size = str1_isL ? 1 : 2;
  int str2_chr_size = str2_isL ? 1 : 2;
  int minCharsInWord = isLL ? wordSize : wordSize / 2;

  load_chr_insn str1_load_chr = str1_isL ? (load_chr_insn)&MacroAssembler::lbu : (load_chr_insn)&MacroAssembler::lhu;
  load_chr_insn str2_load_chr = str2_isL ? (load_chr_insn)&MacroAssembler::lbu : (load_chr_insn)&MacroAssembler::lhu;

  BLOCK_COMMENT("string_compare {");

  // Bizzarely, the counts are passed in bytes, regardless of whether they
  // are L or U strings, however the result is always in characters.
  if (!str1_isL) {
    sraiw(cnt1, cnt1, 1);
  }
  if (!str2_isL) {
    sraiw(cnt2, cnt2, 1);
  }

  // Compute the minimum of the string lengths and save the difference in result.
  sub(result, cnt1, cnt2);
  bgt(cnt1, cnt2, L);
  mv(cnt2, cnt1);
  bind(L);

  // A very short string
  li(t0, minCharsInWord);
  ble(cnt2, t0, SHORT_STRING);

  // Compare longwords
  // load first parts of strings and finish initialization while loading
  {
    if (str1_isL == str2_isL) { // LL or UU
      // load 8 bytes once to compare
      ld(tmp1, Address(str1));
      beq(str1, str2, DONE);
      ld(tmp2, Address(str2));
      li(t0, STUB_THRESHOLD);
      bge(cnt2, t0, STUB);
      sub(cnt2, cnt2, minCharsInWord);
      beqz(cnt2, TAIL_CHECK);
      // convert cnt2 from characters to bytes
      if (!str1_isL) {
        slli(cnt2, cnt2, 1);
      }
      add(str2, str2, cnt2);
      add(str1, str1, cnt2);
      sub(cnt2, zr, cnt2);
    } else if (isLU) { // LU case
      lwu(tmp1, Address(str1));
      ld(tmp2, Address(str2));
      li(t0, STUB_THRESHOLD);
      bge(cnt2, t0, STUB);
      addi(cnt2, cnt2, -4);
      add(str1, str1, cnt2);
      sub(cnt1, zr, cnt2);
      slli(cnt2, cnt2, 1);
      add(str2, str2, cnt2);
      inflate_lo32(tmp3, tmp1);
      mv(tmp1, tmp3);
      sub(cnt2, zr, cnt2);
      addi(cnt1, cnt1, 4);
    } else { // UL case
      ld(tmp1, Address(str1));
      lwu(tmp2, Address(str2));
      li(t0, STUB_THRESHOLD);
      bge(cnt2, t0, STUB);
      addi(cnt2, cnt2, -4);
      slli(t0, cnt2, 1);
      sub(cnt1, zr, t0);
      add(str1, str1, t0);
      add(str2, str2, cnt2);
      inflate_lo32(tmp3, tmp2);
      mv(tmp2, tmp3);
      sub(cnt2, zr, cnt2);
      addi(cnt1, cnt1, 8);
    }
    addi(cnt2, cnt2, isUL ? 4 : 8);
    bgez(cnt2, TAIL);
    xorr(tmp3, tmp1, tmp2);
    bnez(tmp3, DIFFERENCE);

    // main loop
    bind(NEXT_WORD);
    if (str1_isL == str2_isL) { // LL or UU
      add(t0, str1, cnt2);
      ld(tmp1, Address(t0));
      add(t0, str2, cnt2);
      ld(tmp2, Address(t0));
      addi(cnt2, cnt2, 8);
    } else if (isLU) { // LU case
      add(t0, str1, cnt1);
      lwu(tmp1, Address(t0));
      add(t0, str2, cnt2);
      ld(tmp2, Address(t0));
      addi(cnt1, cnt1, 4);
      inflate_lo32(tmp3, tmp1);
      mv(tmp1, tmp3);
      addi(cnt2, cnt2, 8);
    } else { // UL case
      add(t0, str2, cnt2);
      lwu(tmp2, Address(t0));
      add(t0, str1, cnt1);
      ld(tmp1, Address(t0));
      inflate_lo32(tmp3, tmp2);
      mv(tmp2, tmp3);
      addi(cnt1, cnt1, 8);
      addi(cnt2, cnt2, 4);
    }
    bgez(cnt2, TAIL);

    xorr(tmp3, tmp1, tmp2);
    beqz(tmp3, NEXT_WORD);
    j(DIFFERENCE);
    bind(TAIL);
    xorr(tmp3, tmp1, tmp2);
    bnez(tmp3, DIFFERENCE);
    // Last longword.  In the case where length == 4 we compare the
    // same longword twice, but that's still faster than another
    // conditional branch.
    if (str1_isL == str2_isL) { // LL or UU
      ld(tmp1, Address(str1));
      ld(tmp2, Address(str2));
    } else if (isLU) { // LU case
      lwu(tmp1, Address(str1));
      ld(tmp2, Address(str2));
      inflate_lo32(tmp3, tmp1);
      mv(tmp1, tmp3);
    } else { // UL case
      lwu(tmp2, Address(str2));
      ld(tmp1, Address(str1));
      inflate_lo32(tmp3, tmp2);
      mv(tmp2, tmp3);
    }
    bind(TAIL_CHECK);
    xorr(tmp3, tmp1, tmp2);
    beqz(tmp3, DONE);

    // Find the first different characters in the longwords and
    // compute their difference.
    bind(DIFFERENCE);
    ctzc_bit(result, tmp3, isLL); // count zero from lsb to msb
    srl(tmp1, tmp1, result);
    srl(tmp2, tmp2, result);
    if (isLL) {
      andi(tmp1, tmp1, 0xFF);
      andi(tmp2, tmp2, 0xFF);
    } else {
      andi(tmp1, tmp1, 0xFFFF);
      andi(tmp2, tmp2, 0xFFFF);
    }
    sub(result, tmp1, tmp2);
    j(DONE);
  }

  bind(STUB);
  RuntimeAddress stub = NULL;
  switch (ae) {
    case StrIntrinsicNode::LL:
      stub = RuntimeAddress(StubRoutines::riscv::compare_long_string_LL());
      break;
    case StrIntrinsicNode::UU:
      stub = RuntimeAddress(StubRoutines::riscv::compare_long_string_UU());
      break;
    case StrIntrinsicNode::LU:
      stub = RuntimeAddress(StubRoutines::riscv::compare_long_string_LU());
      break;
    case StrIntrinsicNode::UL:
      stub = RuntimeAddress(StubRoutines::riscv::compare_long_string_UL());
      break;
    default:
      ShouldNotReachHere();
  }
  assert(stub.target() != NULL, "compare_long_string stub has not been generated");
  trampoline_call(stub);
  j(DONE);

  bind(SHORT_STRING);
  // Is the minimum length zero?
  beqz(cnt2, DONE);
  // arrange code to do most branches while loading and loading next characters
  // while comparing previous
  (this->*str1_load_chr)(tmp1, Address(str1), t0);
  addi(str1, str1, str1_chr_size);
  addi(cnt2, cnt2, -1);
  beqz(cnt2, SHORT_LAST_INIT);
  (this->*str2_load_chr)(cnt1, Address(str2), t0);
  addi(str2, str2, str2_chr_size);
  j(SHORT_LOOP_START);
  bind(SHORT_LOOP);
  addi(cnt2, cnt2, -1);
  beqz(cnt2, SHORT_LAST);
  bind(SHORT_LOOP_START);
  (this->*str1_load_chr)(tmp2, Address(str1), t0);
  addi(str1, str1, str1_chr_size);
  (this->*str2_load_chr)(t0, Address(str2), t0);
  addi(str2, str2, str2_chr_size);
  bne(tmp1, cnt1, SHORT_LOOP_TAIL);
  addi(cnt2, cnt2, -1);
  beqz(cnt2, SHORT_LAST2);
  (this->*str1_load_chr)(tmp1, Address(str1), t0);
  addi(str1, str1, str1_chr_size);
  (this->*str2_load_chr)(cnt1, Address(str2), t0);
  addi(str2, str2, str2_chr_size);
  beq(tmp2, t0, SHORT_LOOP);
  sub(result, tmp2, t0);
  j(DONE);
  bind(SHORT_LOOP_TAIL);
  sub(result, tmp1, cnt1);
  j(DONE);
  bind(SHORT_LAST2);
  beq(tmp2, t0, DONE);
  sub(result, tmp2, t0);

  j(DONE);
  bind(SHORT_LAST_INIT);
  (this->*str2_load_chr)(cnt1, Address(str2), t0);
  addi(str2, str2, str2_chr_size);
  bind(SHORT_LAST);
  beq(tmp1, cnt1, DONE);
  sub(result, tmp1, cnt1);

  bind(DONE);

  BLOCK_COMMENT("} string_compare");
}

void C2_MacroAssembler::arrays_equals(Register a1, Register a2, Register tmp3,
                                      Register tmp4, Register tmp5, Register tmp6, Register result,
                                      Register cnt1, int elem_size) {
  Label DONE, SAME, NEXT_DWORD, SHORT, TAIL, TAIL2, IS_TMP5_ZR;
  Register tmp1 = t0;
  Register tmp2 = t1;
  Register cnt2 = tmp2;  // cnt2 only used in array length compare
  Register elem_per_word = tmp6;
  int log_elem_size = exact_log2(elem_size);
  int length_offset = arrayOopDesc::length_offset_in_bytes();
  int base_offset   = arrayOopDesc::base_offset_in_bytes(elem_size == 2 ? T_CHAR : T_BYTE);

  assert(elem_size == 1 || elem_size == 2, "must be char or byte");
  assert_different_registers(a1, a2, result, cnt1, t0, t1, tmp3, tmp4, tmp5, tmp6);
  li(elem_per_word, wordSize / elem_size);

  BLOCK_COMMENT("arrays_equals {");

  // if (a1 == a2), return true
  beq(a1, a2, SAME);

  mv(result, false);
  beqz(a1, DONE);
  beqz(a2, DONE);
  lwu(cnt1, Address(a1, length_offset));
  lwu(cnt2, Address(a2, length_offset));
  bne(cnt2, cnt1, DONE);
  beqz(cnt1, SAME);

  slli(tmp5, cnt1, 3 + log_elem_size);
  sub(tmp5, zr, tmp5);
  add(a1, a1, base_offset);
  add(a2, a2, base_offset);
  ld(tmp3, Address(a1, 0));
  ld(tmp4, Address(a2, 0));
  ble(cnt1, elem_per_word, SHORT); // short or same

  // Main 16 byte comparison loop with 2 exits
  bind(NEXT_DWORD); {
    ld(tmp1, Address(a1, wordSize));
    ld(tmp2, Address(a2, wordSize));
    sub(cnt1, cnt1, 2 * wordSize / elem_size);
    blez(cnt1, TAIL);
    bne(tmp3, tmp4, DONE);
    ld(tmp3, Address(a1, 2 * wordSize));
    ld(tmp4, Address(a2, 2 * wordSize));
    add(a1, a1, 2 * wordSize);
    add(a2, a2, 2 * wordSize);
    ble(cnt1, elem_per_word, TAIL2);
  } beq(tmp1, tmp2, NEXT_DWORD);
  j(DONE);

  bind(TAIL);
  xorr(tmp4, tmp3, tmp4);
  xorr(tmp2, tmp1, tmp2);
  sll(tmp2, tmp2, tmp5);
  orr(tmp5, tmp4, tmp2);
  j(IS_TMP5_ZR);

  bind(TAIL2);
  bne(tmp1, tmp2, DONE);

  bind(SHORT);
  xorr(tmp4, tmp3, tmp4);
  sll(tmp5, tmp4, tmp5);

  bind(IS_TMP5_ZR);
  bnez(tmp5, DONE);

  bind(SAME);
  mv(result, true);
  // That's it.
  bind(DONE);

  BLOCK_COMMENT("} array_equals");
}

// Compare Strings

// For Strings we're passed the address of the first characters in a1
// and a2 and the length in cnt1.
// elem_size is the element size in bytes: either 1 or 2.
// There are two implementations.  For arrays >= 8 bytes, all
// comparisons (including the final one, which may overlap) are
// performed 8 bytes at a time.  For strings < 8 bytes, we compare a
// halfword, then a short, and then a byte.

void C2_MacroAssembler::string_equals(Register a1, Register a2,
                                      Register result, Register cnt1, int elem_size)
{
  Label SAME, DONE, SHORT, NEXT_WORD;
  Register tmp1 = t0;
  Register tmp2 = t1;

  assert(elem_size == 1 || elem_size == 2, "must be 2 or 1 byte");
  assert_different_registers(a1, a2, result, cnt1, t0, t1);

  BLOCK_COMMENT("string_equals {");

  mv(result, false);

  // Check for short strings, i.e. smaller than wordSize.
  sub(cnt1, cnt1, wordSize);
  bltz(cnt1, SHORT);

  // Main 8 byte comparison loop.
  bind(NEXT_WORD); {
    ld(tmp1, Address(a1, 0));
    add(a1, a1, wordSize);
    ld(tmp2, Address(a2, 0));
    add(a2, a2, wordSize);
    sub(cnt1, cnt1, wordSize);
    bne(tmp1, tmp2, DONE);
  } bgtz(cnt1, NEXT_WORD);

  // Last longword.  In the case where length == 4 we compare the
  // same longword twice, but that's still faster than another
  // conditional branch.
  // cnt1 could be 0, -1, -2, -3, -4 for chars; -4 only happens when
  // length == 4.
  add(tmp1, a1, cnt1);
  ld(tmp1, Address(tmp1, 0));
  add(tmp2, a2, cnt1);
  ld(tmp2, Address(tmp2, 0));
  bne(tmp1, tmp2, DONE);
  j(SAME);

  bind(SHORT);
  Label TAIL03, TAIL01;

  // 0-7 bytes left.
  andi(t0, cnt1, 4);
  beqz(t0, TAIL03);
  {
    lwu(tmp1, Address(a1, 0));
    add(a1, a1, 4);
    lwu(tmp2, Address(a2, 0));
    add(a2, a2, 4);
    bne(tmp1, tmp2, DONE);
  }

  bind(TAIL03);
  // 0-3 bytes left.
  andi(t0, cnt1, 2);
  beqz(t0, TAIL01);
  {
    lhu(tmp1, Address(a1, 0));
    add(a1, a1, 2);
    lhu(tmp2, Address(a2, 0));
    add(a2, a2, 2);
    bne(tmp1, tmp2, DONE);
  }

  bind(TAIL01);
  if (elem_size == 1) { // Only needed when comparing 1-byte elements
    // 0-1 bytes left.
    andi(t0, cnt1, 1);
    beqz(t0, SAME);
    {
      lbu(tmp1, a1, 0);
      lbu(tmp2, a2, 0);
      bne(tmp1, tmp2, DONE);
    }
  }

  // Arrays are equal.
  bind(SAME);
  mv(result, true);

  // That's it.
  bind(DONE);
  BLOCK_COMMENT("} string_equals");
}

typedef void (Assembler::*conditional_branch_insn)(Register op1, Register op2, Label& label, bool is_far);
typedef void (MacroAssembler::*float_conditional_branch_insn)(FloatRegister op1, FloatRegister op2, Label& label,
                                                              bool is_far, bool is_unordered);

static conditional_branch_insn conditional_branches[] =
{
  /* SHORT branches */
  (conditional_branch_insn)&Assembler::beq,
  (conditional_branch_insn)&Assembler::bgt,
  NULL, // BoolTest::overflow
  (conditional_branch_insn)&Assembler::blt,
  (conditional_branch_insn)&Assembler::bne,
  (conditional_branch_insn)&Assembler::ble,
  NULL, // BoolTest::no_overflow
  (conditional_branch_insn)&Assembler::bge,

  /* UNSIGNED branches */
  (conditional_branch_insn)&Assembler::beq,
  (conditional_branch_insn)&Assembler::bgtu,
  NULL,
  (conditional_branch_insn)&Assembler::bltu,
  (conditional_branch_insn)&Assembler::bne,
  (conditional_branch_insn)&Assembler::bleu,
  NULL,
  (conditional_branch_insn)&Assembler::bgeu
};

static float_conditional_branch_insn float_conditional_branches[] =
{
  /* FLOAT SHORT branches */
  (float_conditional_branch_insn)&MacroAssembler::float_beq,
  (float_conditional_branch_insn)&MacroAssembler::float_bgt,
  NULL,  // BoolTest::overflow
  (float_conditional_branch_insn)&MacroAssembler::float_blt,
  (float_conditional_branch_insn)&MacroAssembler::float_bne,
  (float_conditional_branch_insn)&MacroAssembler::float_ble,
  NULL, // BoolTest::no_overflow
  (float_conditional_branch_insn)&MacroAssembler::float_bge,

  /* DOUBLE SHORT branches */
  (float_conditional_branch_insn)&MacroAssembler::double_beq,
  (float_conditional_branch_insn)&MacroAssembler::double_bgt,
  NULL,
  (float_conditional_branch_insn)&MacroAssembler::double_blt,
  (float_conditional_branch_insn)&MacroAssembler::double_bne,
  (float_conditional_branch_insn)&MacroAssembler::double_ble,
  NULL,
  (float_conditional_branch_insn)&MacroAssembler::double_bge
};

void C2_MacroAssembler::cmp_branch(int cmpFlag, Register op1, Register op2, Label& label, bool is_far) {
  assert(cmpFlag >= 0 && cmpFlag < (int)(sizeof(conditional_branches) / sizeof(conditional_branches[0])),
         "invalid conditional branch index");
  (this->*conditional_branches[cmpFlag])(op1, op2, label, is_far);
}

// This is a function should only be used by C2. Flip the unordered when unordered-greater, C2 would use
// unordered-lesser instead of unordered-greater. Finally, commute the result bits at function do_one_bytecode().
void C2_MacroAssembler::float_cmp_branch(int cmpFlag, FloatRegister op1, FloatRegister op2, Label& label, bool is_far) {
  assert(cmpFlag >= 0 && cmpFlag < (int)(sizeof(float_conditional_branches) / sizeof(float_conditional_branches[0])),
         "invalid float conditional branch index");
  int booltest_flag = cmpFlag & ~(C2_MacroAssembler::double_branch_mask);
  (this->*float_conditional_branches[cmpFlag])(op1, op2, label, is_far,
    (booltest_flag == (BoolTest::ge) || booltest_flag == (BoolTest::gt)) ? false : true);
}

void C2_MacroAssembler::enc_cmpUEqNeLeGt_imm0_branch(int cmpFlag, Register op1, Label& L, bool is_far) {
  switch (cmpFlag) {
    case BoolTest::eq:
    case BoolTest::le:
      beqz(op1, L, is_far);
      break;
    case BoolTest::ne:
    case BoolTest::gt:
      bnez(op1, L, is_far);
      break;
    default:
      ShouldNotReachHere();
  }
}

void C2_MacroAssembler::enc_cmpEqNe_imm0_branch(int cmpFlag, Register op1, Label& L, bool is_far) {
  switch (cmpFlag) {
    case BoolTest::eq:
      beqz(op1, L, is_far);
      break;
    case BoolTest::ne:
      bnez(op1, L, is_far);
      break;
    default:
      ShouldNotReachHere();
  }
}

void C2_MacroAssembler::enc_cmove(int cmpFlag, Register op1, Register op2, Register dst, Register src) {
  Label L;
  cmp_branch(cmpFlag ^ (1 << neg_cond_bits), op1, op2, L);
  mv(dst, src);
  bind(L);
}

// Set dst to NaN if any NaN input.
void C2_MacroAssembler::minmax_FD(FloatRegister dst, FloatRegister src1, FloatRegister src2,
                                  bool is_double, bool is_min) {
  assert_different_registers(dst, src1, src2);

  Label Done;
  fsflags(zr);
  if (is_double) {
    is_min ? fmin_d(dst, src1, src2)
           : fmax_d(dst, src1, src2);
    // Checking NaNs
    flt_d(zr, src1, src2);
  } else {
    is_min ? fmin_s(dst, src1, src2)
           : fmax_s(dst, src1, src2);
    // Checking NaNs
    flt_s(zr, src1, src2);
  }

  frflags(t0);
  beqz(t0, Done);

  // In case of NaNs
  is_double ? fadd_d(dst, src1, src2)
            : fadd_s(dst, src1, src2);

  bind(Done);
}

void C2_MacroAssembler::element_compare(Register a1, Register a2, Register result, Register cnt, Register tmp1, Register tmp2,
                                        VectorRegister vr1, VectorRegister vr2, VectorRegister vrs, bool islatin, Label &DONE) {
  Label loop;
  Assembler::SEW sew = islatin ? Assembler::e8 : Assembler::e16;

  bind(loop);
  vsetvli(tmp1, cnt, sew, Assembler::m2);
  vlex_v(vr1, a1, sew);
  vlex_v(vr2, a2, sew);
  vmsne_vv(vrs, vr1, vr2);
  vfirst_m(tmp2, vrs);
  bgez(tmp2, DONE);
  sub(cnt, cnt, tmp1);
  if (!islatin) {
    slli(tmp1, tmp1, 1); // get byte counts
  }
  add(a1, a1, tmp1);
  add(a2, a2, tmp1);
  bnez(cnt, loop);

  mv(result, true);
}

void C2_MacroAssembler::string_equals_v(Register a1, Register a2, Register result, Register cnt, int elem_size) {
  Label DONE;
  Register tmp1 = t0;
  Register tmp2 = t1;

  BLOCK_COMMENT("string_equals_v {");

  mv(result, false);

  if (elem_size == 2) {
    srli(cnt, cnt, 1);
  }

  element_compare(a1, a2, result, cnt, tmp1, tmp2, v0, v2, v0, elem_size == 1, DONE);

  bind(DONE);
  BLOCK_COMMENT("} string_equals_v");
}

// used by C2 ClearArray patterns.
// base: Address of a buffer to be zeroed
// cnt: Count in HeapWords
//
// base, cnt, v0, v1 and t0 are clobbered.
void C2_MacroAssembler::clear_array_v(Register base, Register cnt) {
  Label loop;

  // making zero words
  vsetvli(t0, cnt, Assembler::e64, Assembler::m4);
  vxor_vv(v0, v0, v0);

  bind(loop);
  vsetvli(t0, cnt, Assembler::e64, Assembler::m4);
  vse64_v(v0, base);
  sub(cnt, cnt, t0);
  shadd(base, t0, base, t0, 3);
  bnez(cnt, loop);
}

void C2_MacroAssembler::arrays_equals_v(Register a1, Register a2, Register result,
                                        Register cnt1, int elem_size) {
  Label DONE;
  Register tmp1 = t0;
  Register tmp2 = t1;
  Register cnt2 = tmp2;
  int length_offset = arrayOopDesc::length_offset_in_bytes();
  int base_offset = arrayOopDesc::base_offset_in_bytes(elem_size == 2 ? T_CHAR : T_BYTE);

  BLOCK_COMMENT("arrays_equals_v {");

  // if (a1 == a2), return true
  mv(result, true);
  beq(a1, a2, DONE);

  mv(result, false);
  // if a1 == null or a2 == null, return false
  beqz(a1, DONE);
  beqz(a2, DONE);
  // if (a1.length != a2.length), return false
  lwu(cnt1, Address(a1, length_offset));
  lwu(cnt2, Address(a2, length_offset));
  bne(cnt1, cnt2, DONE);

  la(a1, Address(a1, base_offset));
  la(a2, Address(a2, base_offset));

  element_compare(a1, a2, result, cnt1, tmp1, tmp2, v0, v2, v0, elem_size == 1, DONE);

  bind(DONE);

  BLOCK_COMMENT("} arrays_equals_v");
}

void C2_MacroAssembler::string_compare_v(Register str1, Register str2, Register cnt1, Register cnt2,
                                         Register result, Register tmp1, Register tmp2, int encForm) {
  Label DIFFERENCE, DONE, L, loop;
  bool encLL = encForm == StrIntrinsicNode::LL;
  bool encLU = encForm == StrIntrinsicNode::LU;
  bool encUL = encForm == StrIntrinsicNode::UL;

  bool str1_isL = encLL || encLU;
  bool str2_isL = encLL || encUL;

  int minCharsInWord = encLL ? wordSize : wordSize / 2;

  BLOCK_COMMENT("string_compare {");

  // for Lating strings, 1 byte for 1 character
  // for UTF16 strings, 2 bytes for 1 character
  if (!str1_isL)
    sraiw(cnt1, cnt1, 1);
  if (!str2_isL)
    sraiw(cnt2, cnt2, 1);

  // if str1 == str2, return the difference
  // save the minimum of the string lengths in cnt2.
  sub(result, cnt1, cnt2);
  bgt(cnt1, cnt2, L);
  mv(cnt2, cnt1);
  bind(L);

  if (str1_isL == str2_isL) { // LL or UU
    element_compare(str1, str2, zr, cnt2, tmp1, tmp2, v2, v4, v1, encLL, DIFFERENCE);
    j(DONE);
  } else { // LU or UL
    Register strL = encLU ? str1 : str2;
    Register strU = encLU ? str2 : str1;
    VectorRegister vstr1 = encLU ? v4 : v0;
    VectorRegister vstr2 = encLU ? v0 : v4;

    bind(loop);
    vsetvli(tmp1, cnt2, Assembler::e8, Assembler::m2);
    vle8_v(vstr1, strL);
    vsetvli(tmp1, cnt2, Assembler::e16, Assembler::m4);
    vzext_vf2(vstr2, vstr1);
    vle16_v(vstr1, strU);
    vmsne_vv(v0, vstr2, vstr1);
    vfirst_m(tmp2, v0);
    bgez(tmp2, DIFFERENCE);
    sub(cnt2, cnt2, tmp1);
    add(strL, strL, tmp1);
    shadd(strU, tmp1, strU, tmp1, 1);
    bnez(cnt2, loop);
    j(DONE);
  }
  bind(DIFFERENCE);
  slli(tmp1, tmp2, 1);
  add(str1, str1, str1_isL ? tmp2 : tmp1);
  add(str2, str2, str2_isL ? tmp2 : tmp1);
  str1_isL ? lbu(tmp1, Address(str1, 0)) : lhu(tmp1, Address(str1, 0));
  str2_isL ? lbu(tmp2, Address(str2, 0)) : lhu(tmp2, Address(str2, 0));
  sub(result, tmp1, tmp2);

  bind(DONE);
}

void C2_MacroAssembler::byte_array_inflate_v(Register src, Register dst, Register len, Register tmp) {
  Label loop;
  assert_different_registers(src, dst, len, tmp, t0);

  BLOCK_COMMENT("byte_array_inflate_v {");
  bind(loop);
  vsetvli(tmp, len, Assembler::e8, Assembler::m2);
  vle8_v(v2, src);
  vsetvli(t0, len, Assembler::e16, Assembler::m4);
  vzext_vf2(v0, v2);
  vse16_v(v0, dst);
  sub(len, len, tmp);
  add(src, src, tmp);
  shadd(dst, tmp, dst, tmp, 1);
  bnez(len, loop);
  BLOCK_COMMENT("} byte_array_inflate_v");
}

// Compress char[] array to byte[].
// result: the array length if every element in array can be encoded; 0, otherwise.
void C2_MacroAssembler::char_array_compress_v(Register src, Register dst, Register len, Register result, Register tmp) {
  Label done;
  encode_iso_array_v(src, dst, len, result, tmp);
  beqz(len, done);
  mv(result, zr);
  bind(done);
}

// result: the number of elements had been encoded.
void C2_MacroAssembler::encode_iso_array_v(Register src, Register dst, Register len, Register result, Register tmp) {
  Label loop, DIFFERENCE, DONE;

  BLOCK_COMMENT("encode_iso_array_v {");
  mv(result, 0);

  bind(loop);
  mv(tmp, 0xff);
  vsetvli(t0, len, Assembler::e16, Assembler::m2);
  vle16_v(v2, src);
  // if element > 0xff, stop
  vmsgtu_vx(v1, v2, tmp);
  vfirst_m(tmp, v1);
  vmsbf_m(v0, v1);
  // compress char to byte
  vsetvli(t0, len, Assembler::e8);
  vncvt_x_x_w(v1, v2, Assembler::v0_t);
  vse8_v(v1, dst, Assembler::v0_t);

  bgez(tmp, DIFFERENCE);
  add(result, result, t0);
  add(dst, dst, t0);
  sub(len, len, t0);
  shadd(src, t0, src, t0, 1);
  bnez(len, loop);
  j(DONE);

  bind(DIFFERENCE);
  add(result, result, tmp);

  bind(DONE);
  BLOCK_COMMENT("} encode_iso_array_v");
}

void C2_MacroAssembler::count_positives_v(Register ary, Register len, Register result, Register tmp) {
  Label LOOP, SET_RESULT, DONE;

  BLOCK_COMMENT("count_positives_v {");
  mv(result, zr);

  bind(LOOP);
  vsetvli(t0, len, Assembler::e8, Assembler::m4);
  vle8_v(v0, ary);
  vmslt_vx(v0, v0, zr);
  vfirst_m(tmp, v0);
  bgez(tmp, SET_RESULT);
  // if tmp == -1, all bytes are positive
  add(result, result, t0);

  sub(len, len, t0);
  add(ary, ary, t0);
  bnez(len, LOOP);
  j(DONE);

  // add remaining positive bytes count
  bind(SET_RESULT);
  add(result, result, tmp);

  bind(DONE);
  BLOCK_COMMENT("} count_positives_v");
}

void C2_MacroAssembler::string_indexof_char_v(Register str1, Register cnt1,
                                              Register ch, Register result,
                                              Register tmp1, Register tmp2,
                                              bool isL) {
  mv(result, zr);

  Label loop, MATCH, DONE;
  Assembler::SEW sew = isL ? Assembler::e8 : Assembler::e16;
  bind(loop);
  vsetvli(tmp1, cnt1, sew, Assembler::m4);
  vlex_v(v0, str1, sew);
  vmseq_vx(v0, v0, ch);
  vfirst_m(tmp2, v0);
  bgez(tmp2, MATCH); // if equal, return index

  add(result, result, tmp1);
  sub(cnt1, cnt1, tmp1);
  if (!isL) slli(tmp1, tmp1, 1);
  add(str1, str1, tmp1);
  bnez(cnt1, loop);

  mv(result, -1);
  j(DONE);

  bind(MATCH);
  add(result, result, tmp2);

  bind(DONE);
}

// Set dst to NaN if any NaN input.
void C2_MacroAssembler::minmax_FD_v(VectorRegister dst, VectorRegister src1, VectorRegister src2,
                                    bool is_double, bool is_min) {
  assert_different_registers(dst, src1, src2);

  vsetvli(t0, x0, is_double ? Assembler::e64 : Assembler::e32);

  is_min ? vfmin_vv(dst, src1, src2)
         : vfmax_vv(dst, src1, src2);

  vmfne_vv(v0,  src1, src1);
  vfadd_vv(dst, src1, src1, Assembler::v0_t);
  vmfne_vv(v0,  src2, src2);
  vfadd_vv(dst, src2, src2, Assembler::v0_t);
}

// Set dst to NaN if any NaN input.
void C2_MacroAssembler::reduce_minmax_FD_v(FloatRegister dst,
                                           FloatRegister src1, VectorRegister src2,
                                           VectorRegister tmp1, VectorRegister tmp2,
                                           bool is_double, bool is_min) {
  assert_different_registers(src2, tmp1, tmp2);

  Label L_done, L_NaN;
  vsetvli(t0, x0, is_double ? Assembler::e64 : Assembler::e32);
  vfmv_s_f(tmp2, src1);

  is_min ? vfredmin_vs(tmp1, src2, tmp2)
         : vfredmax_vs(tmp1, src2, tmp2);

  fsflags(zr);
  // Checking NaNs
  vmflt_vf(tmp2, src2, src1);
  frflags(t0);
  bnez(t0, L_NaN);
  j(L_done);

  bind(L_NaN);
  vfmv_s_f(tmp2, src1);
  vfredsum_vs(tmp1, src2, tmp2);

  bind(L_done);
  vfmv_f_s(dst, tmp1);
}

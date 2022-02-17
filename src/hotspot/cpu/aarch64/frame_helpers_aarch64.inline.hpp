/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_AARCH64_FRAME_HELPERS_AARCH64_INLINE_HPP
#define CPU_AARCH64_FRAME_HELPERS_AARCH64_INLINE_HPP


#ifdef ASSERT
bool Frame::assert_frame_laid_out(frame f) {
  intptr_t* sp = f.sp();
  address pc = *(address*)(sp - frame::sender_sp_ret_address_offset());
  intptr_t* fp = *(intptr_t**)(sp - frame::sender_sp_offset);
  assert (f.raw_pc() == pc, "f.ra_pc: " INTPTR_FORMAT " actual: " INTPTR_FORMAT, p2i(f.raw_pc()), p2i(pc));
  assert (f.fp() == fp, "f.fp: " INTPTR_FORMAT " actual: " INTPTR_FORMAT, p2i(f.fp()), p2i(fp));
  return f.raw_pc() == pc && f.fp() == fp;
}
#endif

inline intptr_t** Frame::callee_link_address(const frame& f) {
  return (intptr_t**)(f.sp() - frame::sender_sp_offset);
}

inline address* Frame::return_pc_address(const frame& f) {
  return (address*)(f.real_fp() - 1);
}

inline address* Interpreted::return_pc_address(const frame& f) {
  return (address*)(f.fp() + frame::return_addr_offset);
}

template <frame::addressing pointers>
void Interpreted::patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  intptr_t* la = f.addr_at(frame::interpreter_frame_sender_sp_offset);
  *la = pointers == frame::addressing::RELATIVE ? (intptr_t)(sp - f.fp()) : (intptr_t)sp;
}

// inline address* Frame::pc_address(const frame& f) {
//   return (address*)(f.sp() - frame::return_addr_offset);
// }

inline address Frame::real_pc(const frame& f) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  return *pc_addr;
}

inline void Frame::patch_pc(const frame& f, address pc) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  *pc_addr = pc;
}

inline intptr_t* Interpreted::frame_top(const frame& f, InterpreterOopMap* mask) { // inclusive; this will be copied with the frame
  // interpreter_frame_last_sp_offset, points to unextended_sp includes arguments in the frame
  // interpreter_frame_initial_sp_offset excludes expression stack slots
  int expression_stack_sz = expression_stack_size(f, mask);
  intptr_t* res = *(intptr_t**)f.addr_at(frame::interpreter_frame_initial_sp_offset) - expression_stack_sz;
  assert (res == (intptr_t*)f.interpreter_frame_monitor_end() - expression_stack_sz, "");
  assert (res >= f.unextended_sp(),
    "res: " INTPTR_FORMAT " initial_sp: " INTPTR_FORMAT " last_sp: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT " expression_stack_size: %d",
    p2i(res), p2i(f.addr_at(frame::interpreter_frame_initial_sp_offset)), f.at(frame::interpreter_frame_last_sp_offset), p2i(f.unextended_sp()), expression_stack_sz);
  return res;
  // Not true, but using unextended_sp might work
  // assert (res == f.unextended_sp(), "res: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(res), p2i(f.unextended_sp() + 1));
}

template <frame::addressing pointers>
inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  return (intptr_t*)f.at<pointers>(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}

inline intptr_t* Interpreted::frame_top(const frame& f, int callee_argsize, bool callee_interpreted) {
  return f.unextended_sp() + (callee_interpreted ? callee_argsize : 0);
}

#endif // CPU_AARCH64_FRAME_HELPERS_AARCH64_INLINE_HPP

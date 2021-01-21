/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP
#define CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP

#include "runtime/frame.inline.hpp"

int InstanceStackChunkKlass::metadata_words() {
  return frame::sender_sp_offset;
}

inline address StackChunkFrameStream::get_pc() const {
  assert (!is_done(), "");
  return *(address*)(_sp - 1);
}

inline void* StackChunkFrameStream::reg_to_loc(VMReg reg) const {
  assert (!is_done(), "");
  assert (!reg->is_reg() || reg == rbp->as_VMReg(), "");
  return reg->is_reg() ? (void*)(_sp - frame::sender_sp_offset) // see frame::update_map_with_saved_link(&map, link_addr);
                       : (void*)((address)_sp + (reg->reg2stack() * VMRegImpl::stack_slot_size));
}

inline frame StackChunkFrameStream::to_frame() const {
  intptr_t* fp = *(intptr_t**)(_sp - frame::sender_sp_offset);
  return frame(_sp, _sp, fp, pc(), cb(), NULL, true);
}

#ifdef ASSERT
inline bool StackChunkFrameStream::is_in_frame(void* p0) const {
  assert (!is_done(), "");
  intptr_t* p = (intptr_t*)p0;
  int argsize = cb()->is_compiled() ? (_cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord : 0;
  int frame_size = _cb->frame_size() + argsize;
  return p == _sp - frame::sender_sp_offset || ((p - _sp) >= 0 && (p - _sp) < frame_size);
}
#endif

#endif // CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP

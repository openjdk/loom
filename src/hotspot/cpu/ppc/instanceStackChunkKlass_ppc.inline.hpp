/*
 * Copyright (c) 2019, 2022 Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_PPC_INSTANCESTACKCHUNKKLASS_PPC_INLINE_HPP
#define CPU_PPC_INSTANCESTACKCHUNKKLASS_PPC_INLINE_HPP

#include "interpreter/oopMapCache.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/registerMap.hpp"

int InstanceStackChunkKlass::metadata_words() {
  Unimplemented();
  return 0;
}

int InstanceStackChunkKlass::align_wiggle()   {
  Unimplemented();
  return 0;
}

#ifdef ASSERT
template <chunk_frames frame_kind>
inline bool StackChunkFrameStream<frame_kind>::is_in_frame(void* p0) const {
  Unimplemented();
  return true;
}
#endif

template <chunk_frames frame_kind>
inline frame StackChunkFrameStream<frame_kind>::to_frame() const {
  Unimplemented();
  return frame();
}

template <chunk_frames frame_kind>
inline address StackChunkFrameStream<frame_kind>::get_pc() const {
  Unimplemented();
  return NULL;
}

template <chunk_frames frame_kind>
inline intptr_t* StackChunkFrameStream<frame_kind>::fp() const {
  Unimplemented();
  return NULL;
}

template <chunk_frames frame_kind>
inline intptr_t* StackChunkFrameStream<frame_kind>::derelativize(int offset) const {
  Unimplemented();
  return NULL;
}

template <chunk_frames frame_kind>
inline intptr_t* StackChunkFrameStream<frame_kind>::unextended_sp_for_interpreter_frame() const {
  Unimplemented();
  return NULL;
}

template <chunk_frames frame_kind>
intptr_t* StackChunkFrameStream<frame_kind>::next_sp_for_interpreter_frame() const {
  Unimplemented();
  return NULL;
}

template <chunk_frames frame_kind>
inline void StackChunkFrameStream<frame_kind>::next_for_interpreter_frame() {
  Unimplemented();
}

template <chunk_frames frame_kind>
inline int StackChunkFrameStream<frame_kind>::interpreter_frame_size() const {
  Unimplemented();
  return 0;
}

template <chunk_frames frame_kind>
inline int StackChunkFrameStream<frame_kind>::interpreter_frame_stack_argsize() const {
  Unimplemented();
  return 0;
}

template <chunk_frames frame_kind>
inline int StackChunkFrameStream<frame_kind>::interpreter_frame_num_oops() const {
  Unimplemented();
  return 0;
}

inline void stackChunkOopDesc::relativize_frame_pd(frame& fr) const {
  Unimplemented();
}

inline void stackChunkOopDesc::derelativize_frame_pd(frame& fr) const {
  Unimplemented();
}

template<>
template<>
inline void StackChunkFrameStream<chunk_frames::MIXED>::update_reg_map_pd(RegisterMap* map) {
  Unimplemented();
}

template<>
template<>
inline void StackChunkFrameStream<chunk_frames::COMPILED_ONLY>::update_reg_map_pd(RegisterMap* map) {
  Unimplemented();
}

template <chunk_frames frame_kind>
template <typename RegisterMapT>
inline void StackChunkFrameStream<frame_kind>::update_reg_map_pd(RegisterMapT* map) {}

// Java frames don't have callee saved registers (except for rfp), so we can use a smaller RegisterMap
class SmallRegisterMap {
public:
  static constexpr SmallRegisterMap* instance = nullptr;
private:
  static void assert_is_rfp(VMReg r) PRODUCT_RETURN
                                     DEBUG_ONLY({ Unimplemented(); })
public:
  // as_RegisterMap is used when we didn't want to templatize and abstract over RegisterMap type to support SmallRegisterMap
  // Consider enhancing SmallRegisterMap to support those cases
  const RegisterMap* as_RegisterMap() const { return nullptr; }
  RegisterMap* as_RegisterMap() { return nullptr; }

  RegisterMap* copy_to_RegisterMap(RegisterMap* map, intptr_t* sp) const {
    Unimplemented();
    return map;
  }

  SmallRegisterMap() {}

  SmallRegisterMap(const RegisterMap* map) {
    Unimplemented();
  }

  inline address location(VMReg reg, intptr_t* sp) const {
    Unimplemented();
    return NULL;
  }

  inline void set_location(VMReg reg, address loc) { assert_is_rfp(reg); }

  JavaThread* thread() const {
  #ifndef ASSERT
    guarantee (false, "");
  #endif
    return nullptr;
  }

  bool update_map()    const { return false; }
  bool walk_cont()     const { return false; }
  bool include_argument_oops() const { return false; }
  void set_include_argument_oops(bool f)  {}
  bool in_cont()       const { return false; }
  stackChunkHandle stack_chunk() const { return stackChunkHandle(); }

#ifdef ASSERT
  bool should_skip_missing() const  { return false; }
  VMReg find_register_spilled_here(void* p, intptr_t* sp) {
    Unimplemented();
    return NULL;
  }
  void print() const { print_on(tty); }
  void print_on(outputStream* st) const { st->print_cr("Small register map"); }
#endif
};

#endif // CPU_PPC_INSTANCESTACKCHUNKKLASS_PPC_INLINE_HPP

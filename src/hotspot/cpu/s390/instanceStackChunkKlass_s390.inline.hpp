/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_S390_INSTANCESTACKCHUNKKLASS_S390_INLINE_HPP
#define CPU_S390_INSTANCESTACKCHUNKKLASS_S390_INLINE_HPP

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
template <bool mixed>
inline bool StackChunkFrameStream<mixed>::is_in_frame(void* p0) const {
  Unimplemented();
  return true;
}
#endif

template <bool mixed>
inline frame StackChunkFrameStream<mixed>::to_frame() const {
  Unimplemented();
  return frame();
}

template <bool mixed>
inline address StackChunkFrameStream<mixed>::get_pc() const {
  Unimplemented();
  return NULL;
}

template <bool mixed>
inline intptr_t* StackChunkFrameStream<mixed>::fp() const {
  Unimplemented();
  return NULL;
}

template <bool mixed>
inline intptr_t* StackChunkFrameStream<mixed>::derelativize(int offset) const {
  Unimplemented();
  return NULL;
}

template <bool mixed>
inline intptr_t* StackChunkFrameStream<mixed>::unextended_sp_for_interpreter_frame() const {
  Unimplemented();
  return NULL;
}

template <bool mixed>
intptr_t* StackChunkFrameStream<mixed>::next_sp_for_interpreter_frame() const {
  Unimplemented();
  return NULL;
}

template <bool mixed>
inline void StackChunkFrameStream<mixed>::next_for_interpreter_frame() {
  Unimplemented();
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::interpreter_frame_size() const {
  Unimplemented();
  return 0;
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::interpreter_frame_stack_argsize() const {
  Unimplemented();
  return 0;
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::interpreter_frame_num_oops() const {
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
inline void StackChunkFrameStream<true>::update_reg_map_pd(RegisterMap* map) {
  Unimplemented();
}

template<>
template<>
inline void StackChunkFrameStream<false>::update_reg_map_pd(RegisterMap* map) {
  Unimplemented();
}

template <bool mixed>
template <typename RegisterMapT>
inline void StackChunkFrameStream<mixed>::update_reg_map_pd(RegisterMapT* map) {}

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

#endif // CPU_S390_INSTANCESTACKCHUNKKLASS_S390_INLINE_HPP

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

#ifndef SHARE_VM_RUNTIME_FRAME_HELPERS_INLINE_HPP
#define SHARE_VM_RUNTIME_FRAME_HELPERS_INLINE_HPP

// No frame_helpers.hpp

#include "code/scopeDesc.hpp"
#include "compiler/oopMap.hpp"
#include "compiler/oopMap.inline.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/stackValue.hpp"
#include "utilities/macros.hpp"

class Frame {
public:
  static inline intptr_t** callee_link_address(const frame& f);
  static inline Method* frame_method(const frame& f);
  static inline address real_pc(const frame& f);
  static inline void patch_pc(const frame& f, address pc);
  static address* return_pc_address(const frame& f);
  static address return_pc(const frame& f);
  static bool is_stub(CodeBlob* cb);

#ifdef ASSERT
  static inline intptr_t* frame_top(const frame &f);
  static inline bool is_deopt_return(address pc, const frame& sender);
  static bool assert_frame_laid_out(frame f);

  static char* method_name(Method* m) { return m != nullptr ? m->name_and_sig_as_C_string() : nullptr; }
  static Method* top_java_frame_method(const frame& f);
  static Method* bottom_java_frame_method(const frame& f)  { return Frame::frame_method(f); }
#endif
};

template<typename Self>
class FrameCommon : public Frame {
public:
  template <typename FrameT> static bool is_instance(const FrameT& f);
};

class Interpreted : public FrameCommon<Interpreted> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = true;
  static const bool stub = false;
  static const int extra_oops = 0;
  static const char type = 'i';

  static inline intptr_t* frame_top(const frame& f, InterpreterOopMap* mask);
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_top(const frame& f, int callee_argsize, bool callee_interpreted);
  static inline intptr_t* frame_bottom(const frame& f);
  static inline intptr_t* sender_unextended_sp(const frame& f);
  static inline int stack_argsize(const frame& f);

  static inline address* return_pc_address(const frame& f);
  static inline address return_pc(const frame& f);
  static void patch_sender_sp(frame& f, intptr_t* sp);

  static int size(const frame& f, InterpreterOopMap* mask);
  static int size(const frame& f);
  static inline int expression_stack_size(const frame &f, InterpreterOopMap* mask);
  static bool is_owning_locks(const frame& f);

  typedef InterpreterOopMap* ExtraT;
};

DEBUG_ONLY(const char* Interpreted::name = "Interpreted";)

template<typename Self>
class NonInterpreted : public FrameCommon<Self>  {
public:
  static inline intptr_t* frame_top(const frame& f, int callee_argsize, bool callee_interpreted);
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_bottom(const frame& f);

  static inline int size(const frame& f);
  static inline int stack_argsize(const frame& f);
  static inline int num_oops(const frame& f);
};

class NonInterpretedUnknown : public NonInterpreted<NonInterpretedUnknown>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;

  template <typename FrameT> static bool is_instance(const FrameT& f);
};

DEBUG_ONLY(const char* NonInterpretedUnknown::name = "NonInterpretedUnknown";)

class Compiled : public NonInterpreted<Compiled>  {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = false;
  static const int extra_oops = 1;
  static const char type = 'c';

  template <typename RegisterMapT>
  static bool is_owning_locks(JavaThread* thread, RegisterMapT* map, const frame& f);
};

DEBUG_ONLY(const char* Compiled::name = "Compiled";)

class StubF : public NonInterpreted<StubF> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = false;
  static const bool stub = true;
  static const int extra_oops = 0;
  static const char type = 's';
};

DEBUG_ONLY(const char* StubF::name = "Stub";)

template<typename Self>
template <typename FrameT>
bool FrameCommon<Self>::is_instance(const FrameT& f) {
  return (Self::interpreted == f.is_interpreted_frame()) && (Self::stub == (!Self::interpreted && is_stub(f.cb())));
}

template <typename FrameT>
bool NonInterpretedUnknown::is_instance(const FrameT& f) {
  return (interpreted == f.is_interpreted_frame());
}

bool Frame::is_stub(CodeBlob* cb) {
  return cb != nullptr && (cb->is_safepoint_stub() || cb->is_runtime_stub());
}

inline Method* Frame::frame_method(const frame& f) {
  return f.is_interpreted_frame() ? f.interpreter_frame_method() : f.cb()->as_compiled_method()->method();
}

address Frame::return_pc(const frame& f) {
  return *return_pc_address(f);
}

#ifdef ASSERT
intptr_t* Frame::frame_top(const frame &f) {
  if (f.is_interpreted_frame()) {
    ResourceMark rm;
    InterpreterOopMap mask;
    f.interpreted_frame_oop_map(&mask);
    return Interpreted::frame_top(f, &mask);
  } else {
    return Compiled::frame_top(f);
  }
}

Method* Frame::top_java_frame_method(const frame& f) {
  Method* m = nullptr;
  if (f.is_interpreted_frame()) {
    m = f.interpreter_frame_method();
  } else if (f.is_compiled_frame()) {
    CompiledMethod* cm = f.cb()->as_compiled_method();
    ScopeDesc* scope = cm->scope_desc_at(f.pc());
    m = scope->method();
  } else if (f.is_native_frame()) {
    m = f.cb()->as_nmethod()->method();
  }

  return m;
}

bool Frame::is_deopt_return(address pc, const frame& sender) {
  if (sender.is_interpreted_frame()) return false;

  CompiledMethod* cm = sender.cb()->as_compiled_method();
  return cm->is_deopt_pc(pc);
}

#endif

address Interpreted::return_pc(const frame& f) {
  return *return_pc_address(f);
}

int Interpreted::size(const frame&f) {
  return Interpreted::frame_bottom(f) - Interpreted::frame_top(f);
}

inline int Interpreted::stack_argsize(const frame& f) {
  return f.interpreter_frame_method()->size_of_parameters();
}

inline int Interpreted::expression_stack_size(const frame &f, InterpreterOopMap* mask) {
  int size = mask->expression_stack_size();
  assert (size <= f.interpreter_frame_expression_stack_size(), "size1: %d size2: %d", size, f.interpreter_frame_expression_stack_size());
  return size;
}

bool Interpreted::is_owning_locks(const frame& f) {
  assert (f.interpreter_frame_monitor_end() <= f.interpreter_frame_monitor_begin(), "must be");
  if (f.interpreter_frame_monitor_end() == f.interpreter_frame_monitor_begin()) {
    return false;
  }

  for (BasicObjectLock* current = f.previous_monitor_in_interpreter_frame(f.interpreter_frame_monitor_begin());
        current >= f.interpreter_frame_monitor_end();
        current = f.previous_monitor_in_interpreter_frame(current)) {

      oop obj = current->obj();
      if (obj != nullptr) {
        return true;
      }
  }
  return false;
}

inline intptr_t* Interpreted::frame_top(const frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

int Interpreted::size(const frame&f, InterpreterOopMap* mask) {
  return Interpreted::frame_bottom(f) - Interpreted::frame_top(f, mask);
}

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_top(const frame& f, int callee_argsize, bool callee_interpreted) {
  return f.unextended_sp() + (callee_interpreted ? 0 : callee_argsize);
}

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_top(const frame& f) { // inclusive; this will be copied with the frame
  return f.unextended_sp();
}

template<typename Self>
inline intptr_t* NonInterpreted<Self>::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  return f.unextended_sp() + f.cb()->frame_size();
}

template<typename Self>
inline int NonInterpreted<Self>::size(const frame& f) {
  assert (!f.is_interpreted_frame() && Self::is_instance(f), "");
  return f.cb()->frame_size();
}

template<typename Self>
inline int NonInterpreted<Self>::stack_argsize(const frame& f) {
  return f.compiled_frame_stack_argsize();
}

template<typename Self>
inline int NonInterpreted<Self>::num_oops(const frame& f) {
  assert (!f.is_interpreted_frame() && Self::is_instance(f), "");
  return f.num_oops() + Self::extra_oops;
}

template<typename RegisterMapT>
bool Compiled::is_owning_locks(JavaThread* thread, RegisterMapT* map, const frame& f) {
  assert (!f.is_interpreted_frame() && Compiled::is_instance(f), "");

  CompiledMethod* cm = f.cb()->as_compiled_method();
  assert (!cm->is_compiled() || !cm->as_compiled_method()->is_native_method(), ""); // See compiledVFrame::compiledVFrame(...) in vframe_hp.cpp

  if (!cm->has_monitors()) {
    return false;
  }

  frame::update_map_with_saved_link(map, Frame::callee_link_address(f)); // the monitor object could be stored in the link register
  ResourceMark rm;
  for (ScopeDesc* scope = cm->scope_desc_at(f.pc()); scope != nullptr; scope = scope->sender()) {
    GrowableArray<MonitorValue*>* mons = scope->monitors();
    if (mons == nullptr || mons->is_empty()) {
      continue;
    }

    for (int index = (mons->length()-1); index >= 0; index--) { // see compiledVFrame::monitors()
      MonitorValue* mon = mons->at(index);
      if (mon->eliminated()) {
        continue; // we ignore scalar-replaced monitors
      }
      ScopeValue* ov = mon->owner();
      StackValue* owner_sv = StackValue::create_stack_value(&f, map, ov); // it is an oop
      oop owner = owner_sv->get_obj()();
      if (owner != nullptr) {
        //assert(cm->has_monitors(), "");
        return true;
      }
    }
  }
  return false;
}

#include CPU_HEADER_INLINE(frame_helpers)

#endif // SHARE_VM_RUNTIME_FRAME_HELPERS_INLINE_HPP

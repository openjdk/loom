/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/javaClasses.hpp"
#include "gc/shared/gc_globals.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/stackChunkOop.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/codeCache.inline.hpp"
#include "code/compiledMethod.inline.hpp"
#include "code/scopeDesc.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/oopMap.hpp"
#include "compiler/oopMap.inline.hpp"
#include "jfr/jfrEvents.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/memAllocator.hpp"
#include "gc/shared/oopStorage.hpp"
#include "gc/shared/threadLocalAllocBuffer.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/linkResolver.hpp"
#include "interpreter/oopMapCache.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "metaprogramming/conditional.hpp"
#include "oops/access.inline.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/weakHandle.hpp"
#include "oops/weakHandle.inline.hpp"
#include "prims/jvmtiDeferredUpdates.hpp"
#include "prims/jvmtiThreadState.hpp"
#include "runtime/continuation.inline.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/keepStackGCProcessed.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/prefetch.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stackFrameStream.inline.hpp"
#include "runtime/stackWatermarkSet.inline.hpp"
#include "runtime/stackOverflow.hpp"
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/debug.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

#define CONT_JFR false

#define SENDER_SP_RET_ADDRESS_OFFSET (frame::sender_sp_offset - frame::return_addr_offset)

static const bool TEST_THAW_ONE_CHUNK_FRAME = false; // force thawing frames one-at-a-time from chunks for testing purposes

#ifdef __has_include
#  if __has_include(<valgrind/callgrind.h>)
#    include <valgrind/callgrind.h>
#  endif
#endif
#ifndef VG
#define VG(X)
#endif
#ifdef __has_include
#  if __has_include(<valgrind/memcheck.h>)
#    include <valgrind/memcheck.h>
#    undef VG
#    define VG(x) x
#  endif
#endif

#ifdef CALLGRIND_START_INSTRUMENTATION
  static int callgrind_counter = 1;
  // static void callgrind() {
  //   if (callgrind_counter != 0) {
  //     if (callgrind_counter > 20000) {
  //       tty->print_cr("Starting callgrind instrumentation");
  //       CALLGRIND_START_INSTRUMENTATION;
  //       callgrind_counter = 0;
  //     } else
  //       callgrind_counter++;
  //   }
  // }
#else
  // static void callgrind() {}
#endif

// #undef log_develop_info
// #undef log_develop_debug
// #undef log_develop_trace
// #undef log_develop_is_enabled
// #define log_develop_info(...)  (!log_is_enabled(Info, __VA_ARGS__))   ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Info>
// #define log_develop_debug(...) (!log_is_enabled(Debug, __VA_ARGS__)) ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Debug>
// #define log_develop_trace(...) (!log_is_enabled(Trace, __VA_ARGS__))  ? (void)0 : LogImpl<LOG_TAGS(__VA_ARGS__)>::write<LogLevel::Trace>
// #define log_develop_is_enabled(level, ...)  log_is_enabled(level, __VA_ARGS__)

// #undef ASSERT
// #undef assert
// #define assert(p, ...)

#ifdef ASSERT
template<int x> NOINLINE static bool verify_continuation(oop cont) { return Continuation::debug_verify_continuation(cont); }
template<int x> NOINLINE static bool verify_stack_chunk(oop chunk) { return InstanceStackChunkKlass::verify(chunk); }
#endif

#ifdef ASSERT
extern "C" void pns2();
extern "C" void pfl();
extern "C" void find(intptr_t x);
#endif

// Returns true iff the address p is readable and *(intptr_t*)p != errvalue
extern "C" bool dbg_is_safe(const void* p, intptr_t errvalue);
static bool is_good_oop(oop o) { return dbg_is_safe(o, -1) && dbg_is_safe(o->klass(), -1) && oopDesc::is_oop(o) && o->klass()->is_klass(); }

// Freeze:
// 5 - no call into C
// 10 - immediate return from C
// <100 - don't allocate
// 100 - everything
//
// Thaw:
// 105 - no call into C (prepare_thaw)
// 110 - immediate return from C (prepare_thaw)
// 130 - thaw oops

// TODO: See AbstractAssembler::generate_stack_overflow_check (assembler.cpp), Compile::bang_size_in_bytes() (compile.cpp), m->as_SafePoint()->jvms()->interpreter_frame_size()
// when we stack-bang, we need to update a thread field with the lowest (farthest) bang point.


// TODO
//
// Nested continuations: must restore fastpath, held_monitor_count, cont_frame->sp (entrySP of parent)
// Add:
//  - compress interpreted frames
//  - compiled->intrepreted for serialization (look at scopeDesc)
//  - caching h-stacks in thread stacks
//
// Things to compress in interpreted frames: return address, monitors, last_sp
//

//
// The data structure invariants are defined by Continuation::debug_verify_continuation and Continuation::debug_verify_stack_chunk
//

#define YIELD_SIG  "jdk.internal.vm.Continuation.yield(Ljdk/internal/vm/ContinuationScope;)V"
#define YIELD0_SIG "jdk.internal.vm.Continuation.yield0(Ljdk/internal/vm/ContinuationScope;Ljdk/internal/vm/Continuation;)Z"
#define ENTER_SIG  "jdk.internal.vm.Continuation.enter(Ljdk/internal/vm/Continuation;Z)V"
#define ENTER_SPECIAL_SIG "jdk.internal.vm.Continuation.enterSpecial(Ljdk/internal/vm/Continuation;Z)V"
#define RUN_SIG    "jdk.internal.vm.Continuation.run()V"

// debugging functions
bool do_verify_after_thaw(JavaThread* thread);
template<int x> NOINLINE static bool do_verify_after_thaw1(JavaThread* thread) { return do_verify_after_thaw(thread); }
static void print_vframe(frame f, const RegisterMap* map = nullptr, outputStream* st = tty);
void do_deopt_after_thaw(JavaThread* thread);

#ifndef PRODUCT
  template <bool relative>
  static void print_frame_layout(const frame& f, outputStream* st = tty);
  static void print_frames(JavaThread* thread, outputStream* st = tty);
  static jlong java_tid(JavaThread* thread);

  // static void print_blob(outputStream* st, address addr);
  // template<int x> static void walk_frames(JavaThread* thread);
  // void static stop();
  // void static stop(const frame& f);
  // static void print_JavaThread_offsets();
#endif

void continuations_init() {
  Continuations::init();
}

class SmallRegisterMap;


class Frame {
public:
  template<typename RegisterMapT> static inline intptr_t** map_link_address(const RegisterMapT* map, intptr_t* sp);
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

  static char* method_name(Method* m);
  static Method* top_java_frame_method(const frame& f);
  static Method* bottom_java_frame_method(const frame& f)  { return Frame::frame_method(f); }
  static char* top_java_frame_name(const frame& f) { return method_name(top_java_frame_method(f)); }
  static char* bottom_java_frame_name(const frame& f) { return method_name(bottom_java_frame_method(f)); }
  static bool assert_bottom_java_frame_name(const frame& f, const char* name);
#endif
};

template<typename Self>
class FrameCommon : public Frame {
public:
  static inline Method* frame_method(const frame& f);

  template <typename FrameT> static bool is_instance(const FrameT& f);
};

class Interpreted : public FrameCommon<Interpreted> {
public:
  DEBUG_ONLY(static const char* name;)
  static const bool interpreted = true;
  static const bool stub = false;
  static const int extra_oops = 0;
  static const char type = 'i';

public:

  static inline intptr_t* frame_top(const frame& f, InterpreterOopMap* mask);
  static inline intptr_t* frame_top(const frame& f);
  static inline intptr_t* frame_top(const frame& f, int callee_argsize, bool callee_interpreted);
  template <bool relative = false>
  static inline intptr_t* frame_bottom(const frame& f);
  template <bool relative = false>
  static inline intptr_t* sender_unextended_sp(const frame& f);
  template <bool relative = false>
  static inline int stack_argsize(const frame& f);

  static inline address* return_pc_address(const frame& f);
  static inline address return_pc(const frame& f);
  template <bool relative>
  static void patch_sender_sp(frame& f, intptr_t* sp);

  static int num_oops(const frame&f, InterpreterOopMap* mask);
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
  static address deopt_original_pc(intptr_t* sp, address pc, CodeBlob* cb);
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
      InterpreterOopMap mask;
      f.interpreted_frame_oop_map(&mask);
      return Interpreted::frame_top(f, &mask);
    } else {
      return Compiled::frame_top(f);
    }
  }


char* Frame::method_name(Method* m) {
  return m != nullptr ? m->name_and_sig_as_C_string() : nullptr;
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
    return f.cb()->as_nmethod()->method();
  }
  // m = ((CompiledMethod*)f.cb())->method();
  return m;
}

bool Frame::assert_bottom_java_frame_name(const frame& f, const char* name) {
  ResourceMark rm;
  bool res = (strcmp(bottom_java_frame_name(f), name) == 0);
  assert (res, "name: %s", bottom_java_frame_name(f));
  return res;
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

int Interpreted::num_oops(const frame&f, InterpreterOopMap* mask) {
  // all locks must be nullptr when freezing, but f.oops_do walks them, so we count them
  return f.interpreted_frame_num_oops(mask);
}

int Interpreted::size(const frame&f) {
  return Interpreted::frame_bottom<true>(f) - Interpreted::frame_top(f);
}

template <bool relative>
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
  if (f.interpreter_frame_monitor_end() == f.interpreter_frame_monitor_begin())
    return false;

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


address Compiled::deopt_original_pc(intptr_t* sp, address pc, CodeBlob* cb) {
  // TODO DEOPT: unnecessary in the long term solution of unroll on freeze

  assert (cb != nullptr && cb->is_compiled(), "");
  CompiledMethod* cm = cb->as_compiled_method();
  if (cm->is_deopt_pc(pc)) {
    log_develop_trace(jvmcont)("Compiled::deopt_original_pc deoptimized frame");
    pc = *(address*)((address)sp + cm->orig_pc_offset());
    assert(pc != nullptr, "");
    assert(cm->insts_contains_inclusive(pc), "original PC must be in the main code section of the the compiled method (or must be immediately following it)");
    assert(!cm->is_deopt_pc(pc), "");
    // _deopt_state = is_deoptimized;
  }

  return pc;
}

template<typename RegisterMapT>
bool Compiled::is_owning_locks(JavaThread* thread, RegisterMapT* map, const frame& f) {
  // if (!DetectLocksInCompiledFrames) return false;
  assert (!f.is_interpreted_frame() && Compiled::is_instance(f), "");

  CompiledMethod* cm = f.cb()->as_compiled_method();
  assert (!cm->is_compiled() || !cm->as_compiled_method()->is_native_method(), ""); // See compiledVFrame::compiledVFrame(...) in vframe_hp.cpp

  if (!cm->has_monitors()) return false;

  frame::update_map_with_saved_link(map, Frame::callee_link_address(f)); // the monitor object could be stored in the link register
  ResourceMark rm;
  for (ScopeDesc* scope = cm->scope_desc_at(f.pc()); scope != nullptr; scope = scope->sender()) {
    GrowableArray<MonitorValue*>* mons = scope->monitors();
    if (mons == nullptr || mons->is_empty())
      continue;

    for (int index = (mons->length()-1); index >= 0; index--) { // see compiledVFrame::monitors()
      MonitorValue* mon = mons->at(index);
      if (mon->eliminated())
        continue; // we ignore scalar-replaced monitors
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


// Mirrors the Java continuation objects.
// This object is created when we begin a freeze/thaw operation for a continuation, and is destroyed when the operation completes.
// Contents are read from the Java object at the entry points of this module, and written at exists or intermediate calls into Java
class ContMirror {
private:
  JavaThread* const _thread;   // Thread being frozen/thawed
  ContinuationEntry* _entry;
  oop _cont;

  stackChunkOop _tail;

  // Profiling data for the JFR event
  short _e_size;
  short _e_num_interpreted_frames;
  short _e_num_frames;

public:
  inline void post_safepoint(Handle conth);
  stackChunkOop allocate_stack_chunk(int stack_size, bool is_preempt);

private:
  ContMirror(const ContMirror& cont); // no copy constructor

public:
  // does not automatically read the continuation object
  ContMirror(JavaThread* thread, oop cont);
  ContMirror(oop cont);
  ContMirror(const RegisterMap* map);

  intptr_t hash() {
    #ifndef PRODUCT
      return Thread::current()->is_Java_thread() ? _cont->identity_hash() : -1;
    #else
      return 0;
    #endif
  }

  void read();
  inline void read_minimal();
  void read_rest();

  inline void write();

  oop mirror() { return _cont; }
  oop parent() { return jdk_internal_vm_Continuation::parent(_cont); }

  ContinuationEntry* entry() const { return _entry; }
  intptr_t* entrySP() const { return _entry->entry_sp(); }
  intptr_t* entryFP() const { return _entry->entry_fp(); }
  address   entryPC() const { return _entry->entry_pc(); }

  int argsize() const { return _entry->argsize(); }
  void set_argsize(int value) { _entry->set_argsize(value); }

  bool is_mounted() { return _entry != nullptr; }

  stackChunkOop tail() const         { return _tail; }
  void set_tail(stackChunkOop chunk) { _tail = chunk; }

  JavaThread* thread() const { return _thread; }

  bool is_empty() const { return !has_nonempty_chunk(); }
  bool has_nonempty_chunk() const { return last_nonempty_chunk() != nullptr; };
  stackChunkOop last_nonempty_chunk() const { return nonempty_chunk(_tail); }
  stackChunkOop prev_nonempty_chunk(stackChunkOop chunk) const { return nonempty_chunk(chunk->parent()); }
  inline stackChunkOop nonempty_chunk(stackChunkOop chunk) const;

  template <bool aligned = true>
  void copy_to_chunk(intptr_t* from, intptr_t* to, int size);

  address last_pc() { return last_nonempty_chunk()->pc(); }

  stackChunkOop find_chunk_by_address(void* p) const;

  const frame last_frame();
  inline void set_empty() { _tail = nullptr; }

  bool is_preempted() { return jdk_internal_vm_Continuation::is_preempted(_cont); }
  void set_preempted(bool value) { jdk_internal_vm_Continuation::set_preempted(_cont, value); }

  inline void inc_num_interpreted_frames() { _e_num_interpreted_frames++; }
  inline void dec_num_interpreted_frames() { _e_num_interpreted_frames++; }

  template<typename Event> void post_jfr_event(Event *e, JavaThread* jt);

#ifdef ASSERT
  inline bool is_entry_frame(const frame& f);
  bool has_mixed_frames();
  bool chunk_invariant();
#endif
};


class ContinuationHelper {
public:
  static const int frame_metadata; // size, in words, of frame metadata (e.g. pc and link)
  static const int align_wiggle; // size, in words, of maximum shift in frame position due to alignment

  static oop get_continuation(JavaThread* thread);

  static void set_anchor_to_entry(JavaThread* thread, ContinuationEntry* cont);
  static void set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* cont);
  static void set_anchor(JavaThread* thread, intptr_t* sp);
  static void set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp);
  static inline void clear_anchor(JavaThread* thread);

  static void update_register_map_for_entry_frame(const ContMirror& cont, RegisterMap* map);
  static void update_map_for_chunk_frame(RegisterMap* map);
  template<typename FKind, typename RegisterMapT> static void update_register_map(RegisterMapT* map, const frame& f); // TODO invert parameter order
  template<typename RegisterMapT> static void update_register_map_with_callee(RegisterMapT* map, const frame& f); // TODO invert parameter order

  static inline frame last_frame(JavaThread* thread);
  static inline void push_pd(const frame& f);

  template <bool dword_aligned = true>
  static inline void copy_from_stack(void* from, void* to, size_t size);
  template <bool dword_aligned = true>
  static inline void copy_to_stack(void* from, void* to, size_t size);
};

void ContinuationHelper::set_anchor_to_entry(JavaThread* thread, ContinuationEntry* cont) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(cont->entry_sp());
  anchor->set_last_Java_pc(cont->entry_pc());
  set_anchor_to_entry_pd(anchor, cont);

  assert (thread->has_last_Java_frame(), "");
  assert(thread->last_frame().cb() != nullptr, "");
  log_develop_trace(jvmcont)("set_anchor: [%ld] [%ld]", java_tid(thread), (long) thread->osthread()->thread_id());
  print_vframe(thread->last_frame());
}

void ContinuationHelper::set_anchor(JavaThread* thread, intptr_t* sp) {
  address pc = *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET);
  assert (pc != nullptr, "");

  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(sp);
  anchor->set_last_Java_pc(pc);
  set_anchor_pd(anchor, sp);

  assert (thread->has_last_Java_frame(), "");
  log_develop_trace(jvmcont)("set_anchor: [%ld] [%ld]", java_tid(thread), (long) thread->osthread()->thread_id());
  print_vframe(thread->last_frame());
  assert(thread->last_frame().cb() != nullptr, "");
}

inline void ContinuationHelper::clear_anchor(JavaThread* thread) {
  thread->frame_anchor()->clear();
}

void ContinuationHelper::update_register_map_for_entry_frame(const ContMirror& cont, RegisterMap* map) { // TODO NOT PD
  // we need to register the link address for the entry frame
  if (cont.entry() != nullptr) {
    cont.entry()->update_register_map(map);
    log_develop_trace(jvmcont)("ContinuationHelper::update_register_map_for_entry_frame");
  } else {
    log_develop_trace(jvmcont)("ContinuationHelper::update_register_map_for_entry_frame: clearing register map.");
    map->clear();
  }
}

oop ContinuationHelper::get_continuation(JavaThread* thread) {
  assert (thread != nullptr, "");
  assert (thread->threadObj() != nullptr, "");
  return java_lang_Thread::continuation(thread->threadObj());
}

ContMirror::ContMirror(JavaThread* thread, oop cont)
 : _thread(thread), _entry(thread->last_continuation()), _cont(cont),
#ifndef PRODUCT
  _tail(nullptr),
#endif
  _e_size(0) {

  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));
  assert (_cont == _entry->cont_oop(), "mirror: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: "
          INTPTR_FORMAT, p2i((oopDesc*)_cont), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
}

ContMirror::ContMirror(oop cont)
 : _thread(nullptr), _entry(nullptr), _cont(cont),
#ifndef PRODUCT
  _tail(nullptr),
#endif
  _e_size(0) {
  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));

  read();
}

ContMirror::ContMirror(const RegisterMap* map)
 : _thread(map->thread()),
   _entry(Continuation::get_continuation_entry_for_continuation(_thread, map->stack_chunk()->cont())),
   _cont(map->stack_chunk()->cont()),
#ifndef PRODUCT
  _tail(nullptr),
#endif
  _e_size(0) {

  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));

  assert (_entry == nullptr || _cont == _entry->cont_oop(), "mirror: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: " INTPTR_FORMAT, p2i((oopDesc*)_cont), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  read();
}

void ContMirror::read() {
  read_minimal();
  read_rest();
}

ALWAYSINLINE void ContMirror::read_minimal() {
  _tail  = (stackChunkOop)jdk_internal_vm_Continuation::tail(_cont);

  // if (log_develop_is_enabled(Trace, jvmcont)) {
  //   log_develop_trace(jvmcont)("Reading continuation object: " INTPTR_FORMAT, p2i((oopDesc*)_cont));
  //   log_develop_trace(jvmcont)("\ttail: " INTPTR_FORMAT, p2i((oopDesc*)_tail));
  //   if (_tail != nullptr) _tail->print_on(tty);
  // }
}

void ContMirror::read_rest() {
  _e_num_interpreted_frames = 0;
  _e_num_frames = 0;
}

inline void ContMirror::write() {
  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Writing continuation object:");
    log_develop_trace(jvmcont)("\ttail: " INTPTR_FORMAT, p2i((oopDesc*)_tail));
    if (_tail != nullptr) _tail->print_on(tty);
  }

  jdk_internal_vm_Continuation::set_tail(_cont, _tail);
}

inline stackChunkOop ContMirror::nonempty_chunk(stackChunkOop chunk) const {
  while (chunk != nullptr && chunk->is_empty()) chunk = chunk->parent();
  return chunk;
}

const frame ContMirror::last_frame() {
  stackChunkOop chunk = last_nonempty_chunk();
  if (chunk == nullptr) return frame();
  return StackChunkFrameStream<true>(chunk).to_frame();
}

stackChunkOop ContMirror::find_chunk_by_address(void* p) const {
  for (stackChunkOop chunk = tail(); chunk != nullptr; chunk = chunk->parent()) {
    if (chunk->is_in_chunk(p)) {
      assert (chunk->is_usable_in_chunk(p), "");
      return chunk;
    }
  }
  return nullptr;
}

template<typename Event> void ContMirror::post_jfr_event(Event* e, JavaThread* jt) {
#if INCLUDE_JFR
  if (e->should_commit()) {
    log_develop_trace(jvmcont)("JFR event: frames: %d iframes: %d size: %d", _e_num_frames, _e_num_interpreted_frames, _e_size);
    e->set_carrierThread(JFR_VM_THREAD_ID(jt));
    e->set_contClass(_cont->klass());
    e->set_numFrames(_e_num_frames);
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->commit();
  }
#endif
}

template <bool aligned>
void ContMirror::copy_to_chunk(intptr_t* from, intptr_t* to, int size) {
  tail()->copy_from_stack_to_chunk<aligned>(from, to, size);
  _e_size += size << LogBytesPerWord;
}

#ifdef ASSERT
inline bool ContMirror::is_entry_frame(const frame& f) {
  return f.sp() == entrySP();
}

bool ContMirror::chunk_invariant() {
  // only the topmost chunk can be empty
  if (_tail == (oop)nullptr)
    return true;
  assert (_tail->is_stackChunk(), "");
  int i = 1;
  for (stackChunkOop chunk = _tail->parent(); chunk != (oop)nullptr; chunk = chunk->parent()) {
    if (chunk->is_empty()) {
      assert (chunk != _tail, "");
      tty->print_cr("i: %d", i);
      chunk->print_on(true, tty);
      return false;
    }
    i++;
  }
  return true;
}

bool ContMirror::has_mixed_frames() {
  for (stackChunkOop c = tail(); c != nullptr; c = c->parent()) if (c->has_mixed_frames()) return true;
  return false;
}
#endif

#if INCLUDE_JVMTI
static int num_java_frames(ContMirror& cont) {
  ResourceMark rm; // used for scope traversal in num_java_frames(CompiledMethod*, address)
  int count = 0;
  for (stackChunkOop chunk = cont.tail(); chunk != (oop)nullptr; chunk = chunk->parent()) {
    count += chunk->num_java_frames();
  }
  return count;
}
#endif // INCLUDE_JVMTI

typedef int (*FreezeContFnT)(JavaThread*, intptr_t*, bool);

static FreezeContFnT cont_freeze = nullptr;


class CachedCompiledMetadata; // defined in PD
template<class P>
static inline oop safe_load(P *addr) {
  oop obj = (oop)RawAccess<>::oop_load(addr);
  obj = (oop)NativeAccess<>::oop_load(&obj);
  return obj;
}

#ifdef ASSERT
template <class P>
static void verify_oop_at(P* p) {
  oop obj = (oop)NativeAccess<>::oop_load(p);
  assert(oopDesc::is_oop_or_null(obj), "");
}
#endif

class CountOops : public OopClosure {
private:
  int _nr_oops;
public:
  CountOops() : _nr_oops(0) {}
  int nr_oops() const { return _nr_oops; }


  virtual void do_oop(oop* o) { _nr_oops++; }
  virtual void do_oop(narrowOop* o) { _nr_oops++; }
};

// should match Continuation.preemptStatus() in Continuation.java
enum freeze_result {
  freeze_ok = 0,
  freeze_ok_bottom = 1,
  freeze_pinned_cs = 2,
  freeze_pinned_native = 3,
  freeze_pinned_monitor = 4,
  freeze_exception = 5
};

const char* freeze_result_names[6] = {
  "freeze_ok",
  "freeze_ok_bottom",
  "freeze_pinned_cs",
  "freeze_pinned_native",
  "freeze_pinned_monitor",
  "freeze_exception"
};

template <typename ConfigT>
class Freeze {

private:
  JavaThread* const _thread;
  ContMirror& _cont;
  bool _barriers;
  const bool _preempt;

  intptr_t *_bottom_address;
  intptr_t *_top_address;

  int _size; // total size of all frames plus metadata in words. keeps track of offset where a frame should be written and how many bytes we need to allocate.
  int _frames;
  int _align_size;

  DEBUG_ONLY(intptr_t* _last_write;)

  inline void set_top_frame_metadata_pd(const frame& hf);
  template <typename FKind, bool bottom> inline void patch_pd(frame& callee, const frame& caller);
  inline void patch_chunk_pd(intptr_t* vsp, intptr_t* hsp);
  template<typename FKind> frame new_hframe(frame& f, frame& caller);
  inline intptr_t* align_bottom(intptr_t* vsp, int argsize);
  static inline void relativize_interpreted_frame_metadata(const frame& f, const frame& hf);

  template<typename FKind> static inline frame sender(const frame& f);

public:

  Freeze(JavaThread* thread, ContMirror& mirror, bool preempt) :
    _thread(thread), _cont(mirror), _barriers(false), _preempt(preempt) {

    // _cont.read_entry(); // even when retrying, because deopt can change entryPC; see Continuation::get_continuation_entry_pc_for_sender
    _cont.read(); // read_minimal

    assert (thread->last_continuation()->entry_sp() == _cont.entrySP(), "");

    int argsize = bottom_argsize();
    _bottom_address = _cont.entrySP() - argsize;
    DEBUG_ONLY(_cont.entry()->verify_cookie();)

    assert (!Interpreter::contains(_cont.entryPC()), "");

  #ifdef _LP64
    if (((intptr_t)_bottom_address & 0xf) != 0) {
      _bottom_address--;
    }
    assert((intptr_t)_bottom_address % 16 == 0, "");
  #endif

    log_develop_trace(jvmcont)("bottom_address: " INTPTR_FORMAT " entrySP: " INTPTR_FORMAT " argsize: %ld", p2i(_bottom_address), p2i(_cont.entrySP()), (_cont.entrySP() - _bottom_address) << LogBytesPerWord);
    assert (_bottom_address != nullptr && _bottom_address <= _cont.entrySP(), "");
  }

  void init_rest() { // we want to postpone some initialization after chunk handling
    _size = 0;
    _frames = 0;
    _align_size = 0;
  }

  int nr_bytes() const  { return _size << LogBytesPerWord; }
  int nr_frames() const { return _frames; }

  inline bool should_flush_stack_processing() {
    StackWatermark* sw;
    uintptr_t watermark;
    return ((sw = StackWatermarkSet::get(_thread, StackWatermarkKind::gc)) != nullptr
      && (watermark = sw->watermark()) != 0
      && watermark <= ((uintptr_t)_cont.entrySP() + ContinuationEntry::size()));
  }

  NOINLINE void flush_stack_processing() {
    log_develop_trace(jvmcont)("flush_stack_processing");
    for (StackFrameStream fst(_thread, true, true); !Continuation::is_continuation_enterSpecial(*fst.current()); fst.next())
      ;
  }

  template <bool aligned = true>
  void copy_to_chunk(intptr_t* from, intptr_t* to, int size) {
    _cont.copy_to_chunk<aligned>(from, to, size);
  #ifdef ASSERT
    stackChunkOop chunk = _cont.tail();
    assert (_last_write == to + size, "Missed a spot: _last_write: " INTPTR_FORMAT " to+size: " INTPTR_FORMAT " stack_size: %d _last_write offset: %ld to+size: %ld",
      p2i(_last_write), p2i(to + size), chunk->stack_size(), _last_write - chunk->start_address(), to + size - chunk->start_address());
    _last_write = to;
    // tty->print_cr(">>> copy_to_chunk _last_write: %p", _last_write);
  #endif
  }

  freeze_result try_freeze_fast(intptr_t* sp, bool chunk_available) {
    if (freeze_fast(sp, chunk_available)) {
      return freeze_ok;
    }
    if (_thread != nullptr && _thread->has_pending_exception()) {
      return freeze_exception;
    }

    EventContinuationFreezeOld e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(_cont.mirror()));
      e.commit();
    }
    // TODO R REMOVE when deopt change is fixed
    assert (!_thread->cont_fastpath() || _barriers, "");
    log_develop_trace(jvmcont)("-- RETRYING SLOW --");
    return freeze_slow();
  }


  inline int bottom_argsize() {
    int argsize = _cont.argsize(); // in words
    log_develop_trace(jvmcont)("bottom_argsize: %d", argsize);
    assert (argsize >= 0, "argsize: %d", argsize);
    return argsize;
  }

  // returns true iff there's room in the chunk for a fast, compiled-frame-only freeze
  // TODO PERF: consider inlining in stub
  bool is_chunk_available(intptr_t* top_sp
#ifdef ASSERT
    , int* out_size = nullptr
#endif
  ) {
    stackChunkOop chunk = _cont.tail();
    if (chunk == nullptr || chunk->is_gc_mode() || ConfigT::requires_barriers(chunk) || chunk->has_mixed_frames()) {
      log_develop_trace(jvmcont)("is_chunk_available %s", chunk == nullptr ? "no chunk" : "chunk requires barriers");
      return false;
    }

    // assert (CodeCache::find_blob(*(address*)(top_sp - SENDER_SP_RET_ADDRESS_OFFSET)) == StubRoutines::cont_doYield_stub(), ""); -- fails on Windows
    assert (StubRoutines::cont_doYield_stub()->frame_size() == ContinuationHelper::frame_metadata, "");
    intptr_t* const top = top_sp + ContinuationHelper::frame_metadata;
    const int argsize = bottom_argsize();
    intptr_t* const bottom = align_bottom(_cont.entrySP(), argsize);
    int size = bottom - top; // in words

    const int sp = chunk->sp();
    if (sp < chunk->stack_size()) {
      size -= argsize;
    }
    assert (size > 0, "");

    bool available = sp - ContinuationHelper::frame_metadata >= size;
    log_develop_trace(jvmcont)("is_chunk_available available: %d size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT, available, argsize, size, p2i(top), p2i(bottom));
    DEBUG_ONLY(if (out_size != nullptr) *out_size = size;)
    return available;
  }

  bool freeze_fast(intptr_t* top_sp, bool chunk_available) {
  #ifdef CALLGRIND_START_INSTRUMENTATION
    if (_frames > 0 && callgrind_counter == 1) {
      callgrind_counter = 2;
      tty->print_cr("Starting callgrind instrumentation");
      CALLGRIND_START_INSTRUMENTATION;
    }
  #endif

    // tty->print_cr("FREEZE FAST");
    log_develop_trace(jvmcont)("freeze_fast");
    assert (_thread != nullptr, "");
    assert(_cont.chunk_invariant(), "");
    assert (!Interpreter::contains(_cont.entryPC()), "");

    stackChunkOop chunk = _cont.tail();

    // On Windows, this finds, `BufferBlob (0x00000290bae6fc90) used for I2C/C2I adapters` and `BufferBlob (0x0000023375f38110) used for Interpreter`
    // if (!(CodeCache::find_blob(*(address*)(top_sp - SENDER_SP_RET_ADDRESS_OFFSET)) == StubRoutines::cont_doYield_stub())) {
    //   CodeBlob* cb11 = CodeCache::find_blob(*(address*)(top_sp - SENDER_SP_RET_ADDRESS_OFFSET));
    //   if (cb11 == nullptr) tty->print_cr(">>>> WHOA NULL"); else {tty->print_cr(">>>> WHOA"); cb11->print_value_on(tty);}
    // }
    // assert (CodeCache::find_blob(*(address*)(top_sp - SENDER_SP_RET_ADDRESS_OFFSET)) == StubRoutines::cont_doYield_stub(), ""); -- fails on Windows
    assert (StubRoutines::cont_doYield_stub()->frame_size() == ContinuationHelper::frame_metadata, "");
    intptr_t* const top = top_sp + ContinuationHelper::frame_metadata;

    const int argsize = bottom_argsize();
    intptr_t* const bottom = align_bottom(_cont.entrySP(), argsize);
    const int size = bottom - top; // in words
    log_develop_trace(jvmcont)("freeze_fast size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT, size, argsize, p2i(top), p2i(bottom));
    assert (size > 0, "");

    int sp;
  #ifdef ASSERT
    bool allocated, empty;
    int is_chunk_available_size;
    bool is_chunk_available0 = is_chunk_available(top_sp, &is_chunk_available_size);
    intptr_t* orig_chunk_sp = nullptr;
  #endif
    if (LIKELY(chunk_available)) {
      assert (chunk == _cont.tail() && is_chunk_available0, "");
      DEBUG_ONLY(allocated = false;)
      DEBUG_ONLY(orig_chunk_sp = chunk->sp_address();)
      sp = chunk->sp();

      if (sp < chunk->stack_size()) { // we are copying into a non-empty chunk
        assert (sp < (chunk->stack_size() - chunk->argsize()), "");
        assert (*(address*)(chunk->sp_address() - SENDER_SP_RET_ADDRESS_OFFSET) == chunk->pc(), "chunk->sp_address() - SENDER_SP_RET_ADDRESS_OFFSET: %p *(address*)(chunk->sp_address() - SENDER_SP_RET_ADDRESS_OFFSET): %p chunk->pc(): %p", chunk->sp_address() - SENDER_SP_RET_ADDRESS_OFFSET, *(address*)(chunk->sp_address() - SENDER_SP_RET_ADDRESS_OFFSET), chunk->pc());

        DEBUG_ONLY(empty = false;)
        sp += argsize; // we overlap
        assert (sp <= chunk->stack_size(), "");

        log_develop_trace(jvmcont)("add max_size: %d -- %d", size - argsize, chunk->max_size() + size - argsize);
        chunk->set_max_size(chunk->max_size() + size - argsize);

        intptr_t* const bottom_sp = bottom - argsize;
        log_develop_trace(jvmcont)("patching bottom sp: " INTPTR_FORMAT, p2i(bottom_sp));
        assert (bottom_sp == _bottom_address, "");
        assert (*(address*)(bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET) == StubRoutines::cont_returnBarrier(), "");
        patch_chunk_pd(bottom_sp, chunk->sp_address());
        // we don't patch the pc at this time, so as not to make the stack unwalkable
      } else { // the chunk is empty
        assert(sp == chunk->stack_size(), "sp: %d chunk->stack_size(): %d", sp, chunk->stack_size());
        DEBUG_ONLY(empty = true;)
        log_develop_trace(jvmcont)("add max_size: %d -- %d", size + ContinuationHelper::frame_metadata, size + ContinuationHelper::frame_metadata);
        chunk->set_max_size(size);
        chunk->set_argsize(argsize);
      }

      // chunk->reset_counters(chunk);
    } else {
      assert (_thread->thread_state() == _thread_in_vm, "");
      assert (!is_chunk_available(top_sp), "");
      assert (_thread->cont_fastpath(), "");

      chunk = allocate_chunk(size + ContinuationHelper::frame_metadata);
      if (UNLIKELY(chunk == nullptr || !_thread->cont_fastpath())) {
        return false;
      }

      DEBUG_ONLY(empty = true;)
      DEBUG_ONLY(allocated = true;)

      sp = size + ContinuationHelper::frame_metadata;
      DEBUG_ONLY(orig_chunk_sp = chunk->start_address() + sp;)

      assert (chunk->parent() == (oop)nullptr || chunk->parent()->is_stackChunk(), "");
      // in a fresh chunk, we freeze *with* the bottom-most frame's stack arguments.
      // They'll then be stored twice: in the chunk and in the parent

      _cont.set_tail(chunk);
      // jdk_internal_vm_Continuation::set_tail(_cont.mirror(), chunk);

      if (UNLIKELY(ConfigT::requires_barriers(chunk))) { // probably humongous
        log_develop_trace(jvmcont)("allocation requires barriers; retrying slow");
        chunk->set_argsize(0);
        chunk->set_sp(sp);
        _barriers = true;
        return false;
      }

      log_develop_trace(jvmcont)("add max_size: %d -- %d", size + ContinuationHelper::frame_metadata, size + ContinuationHelper::frame_metadata);
      chunk->set_max_size(size);
      chunk->set_argsize(argsize);
    }

    assert (chunk != nullptr, "");
    assert (!chunk->has_mixed_frames(), "");
    assert (!chunk->is_gc_mode(), "");
    assert (!chunk->has_bitmap(), "");

    if (should_flush_stack_processing())
      flush_stack_processing();

    NoSafepointVerifier nsv;
    assert (chunk->is_stackChunk(), "");
    assert (!chunk->requires_barriers(), "");
    assert (chunk == _cont.tail(), "");
    // assert (chunk == jdk_internal_vm_Continuation::tail(_cont.mirror()), "");
    // assert (!chunk->is_gc_mode(), "allocated: %d empty: %d", allocated, empty);
    assert (sp <= chunk->stack_size(), "sp: %d chunk size: %d size: %d argsize: %d allocated: %d", sp, chunk->stack_size(), size, argsize, allocated);

    log_develop_trace(jvmcont)("freeze_fast start: chunk " INTPTR_FORMAT " size: %d orig sp: %d argsize: %d", p2i((oopDesc*)chunk), chunk->stack_size(), sp, argsize);
    assert (sp >= size, "");
    sp -= size;
    assert (!is_chunk_available0 || orig_chunk_sp - (chunk->start_address() + sp) == is_chunk_available_size, "mismatched size calculation: orig_sp - sp: %ld size: %d argsize: %d is_chunk_available_size: %d empty: %d allocated: %d", orig_chunk_sp - (chunk->start_address() + sp), size, argsize, is_chunk_available_size, empty, allocated);

    intptr_t* chunk_top = chunk->start_address() + sp;
    assert (empty || *(address*)(orig_chunk_sp - SENDER_SP_RET_ADDRESS_OFFSET) == chunk->pc(), "corig_chunk_sp - SENDER_SP_RET_ADDRESS_OFFSET: %p *(address*)(orig_chunk_sp - SENDER_SP_RET_ADDRESS_OFFSET): %p chunk->pc(): %p", orig_chunk_sp - SENDER_SP_RET_ADDRESS_OFFSET, *(address*)(orig_chunk_sp - SENDER_SP_RET_ADDRESS_OFFSET), chunk->pc());

    log_develop_trace(jvmcont)("freeze_fast start: " INTPTR_FORMAT " sp: %d chunk_top: " INTPTR_FORMAT, p2i(chunk->start_address()), sp, p2i(chunk_top));
    intptr_t* from = top       - ContinuationHelper::frame_metadata;
    intptr_t* to   = chunk_top - ContinuationHelper::frame_metadata;
    _cont.copy_to_chunk(from, to, size + ContinuationHelper::frame_metadata);

    // patch pc
    intptr_t* chunk_bottom_sp = chunk_top + size - argsize;
    log_develop_trace(jvmcont)("freeze_fast patching return address at: " INTPTR_FORMAT " to: " INTPTR_FORMAT, p2i(chunk_bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET), p2i(chunk->pc()));
    assert (empty || *(address*)(chunk_bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET) == StubRoutines::cont_returnBarrier(), "");
    *(address*)(chunk_bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET) = chunk->pc();

    // We're always writing to a young chunk, so the GC can't see it until the next safepoint.
    OrderAccess::storestore();
    chunk->set_sp(sp);
    chunk->set_pc(*(address*)(top - SENDER_SP_RET_ADDRESS_OFFSET));
    chunk->set_gc_sp(sp);
    assert (chunk->sp_address() == chunk_top, "");

    _cont.write();

    // if (UNLIKELY(argsize != 0)) {
    //   // we're patching the chunk itself rather than the stack before the copy becuase of concurrent stack scanning
    //   intptr_t* const chunk_bottom_sp = to + size - argsize;
    //   log_develop_trace(jvmcont)("patching chunk's bottom sp: " INTPTR_FORMAT, p2i(chunk_bottom_sp));
    //   assert (*(address*)(chunk_bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET) == StubRoutines::cont_returnBarrier(), "");
    //   *(address*)(chunk_bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET) = chunk->pc();
    // }

    // // We're always writing to a young chunk, so the GC can't see it until the next safepoint.
    // chunk->set_sp(sp);
    // chunk->set_pc(*(address*)(top - SENDER_SP_RET_ADDRESS_OFFSET));
    // chunk->set_gc_sp(sp);

    log_develop_trace(jvmcont)("Young chunk success");
    if (log_develop_is_enabled(Debug, jvmcont)) chunk->print_on(true, tty);

    log_develop_trace(jvmcont)("FREEZE CHUNK #" INTPTR_FORMAT, _cont.hash());
    assert (_cont.chunk_invariant(), "");
    assert (verify_stack_chunk<1>(chunk), "");

  #if CONT_JFR
    EventContinuationFreezeYoung e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(chunk));
      e.set_allocate(allocated);
      e.set_size(size << LogBytesPerWord);
      e.commit();
    }
  #endif

    // assert(verify_continuation<222>(_cont.mirror()), "");

    return true;
  }

 freeze_result freeze_slow() {
  #ifdef ASSERT
    ResourceMark rm;
  #endif
    // tty->print_cr("FREEZE SLOW");
    log_develop_trace(jvmcont)("freeze_slow  #" INTPTR_FORMAT, _cont.hash());

    assert (_thread->thread_state() == _thread_in_vm || _thread->thread_state() == _thread_blocked, "");

    init_rest();
    // _cont.read_rest();

    HandleMark hm(Thread::current());

    frame f = freeze_start_frame();

    _top_address = f.sp();
    frame caller;
    freeze_result res = freeze(f, caller, 0, false, true);

    if (res == freeze_ok) {
      finish_freeze(f, caller);
      _cont.write();
    }

    return res;
  }

  inline bool stack_overflow() { // detect stack overflow in recursive native code
    JavaThread* t = !_preempt ? _thread : JavaThread::current();
    assert (t == JavaThread::current(), "");
    if ((address)&t < t->stack_overflow_state()->stack_overflow_limit()) {
      Exceptions::_throw_msg(t, __FILE__, __LINE__, vmSymbols::java_lang_StackOverflowError(), "Stack overflow while freezing");
      return true;
    }
    return false;
  }

  frame freeze_start_frame() {
    frame f = _thread->last_frame();
    if (LIKELY(!_preempt)) {
      assert (StubRoutines::cont_doYield_stub()->contains(f.pc()), "");
      return freeze_start_frame_yield_stub(f);
    } else {
      return freeze_start_frame_safepoint_stub(f);
    }
  }

  frame freeze_start_frame_yield_stub(frame f) {
    // log_develop_trace(jvmcont)("%s nop at freeze yield", nativePostCallNop_at(_fi->pc) != nullptr ? "has" : "no");
    assert(StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
    f = sender<StubF>(f);

    // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
    if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

    return f;
  }

  frame freeze_start_frame_safepoint_stub(frame f) {
    f.set_fp(f.real_fp()); // f.set_fp(*Frame::callee_link_address(f)); // ????
    if (!Interpreter::contains(f.pc())) {
  #ifdef ASSERT
      if (!Frame::is_stub(f.cb())) { f.print_value_on(tty, JavaThread::current()); }
  #endif
      assert (Frame::is_stub(f.cb()), "must be");
      assert (f.oop_map() != nullptr, "must be");

      if (Interpreter::contains(StubF::return_pc(f))) {
        log_develop_trace(jvmcont)("Safepoint stub in interpreter");
        f = sender<StubF>(f);
      }
    }

    // Log(jvmcont) logv; LogStream st(logv.debug()); f.print_on(st);
    if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);

    return f;
  }

  NOINLINE freeze_result freeze(frame& f, frame& caller, int callee_argsize, bool callee_interpreted, bool top) {
    assert (f.unextended_sp() < _bottom_address, ""); // see recurse_freeze_java_frame
    assert (f.is_interpreted_frame() || ((top && _preempt) == Frame::is_stub(f.cb())), "");

    if (stack_overflow()) return freeze_exception;

    // Dynamically branch on frame type
    if (f.is_compiled_frame()) {
      if (UNLIKELY(f.oop_map() == nullptr)) return freeze_pinned_native; // special native frame
      if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), SmallRegisterMap::instance, f))) return freeze_pinned_monitor;

      return recurse_freeze_compiled_frame(f, caller, callee_argsize, callee_interpreted);
    } else if (f.is_interpreted_frame()) {
      assert ((_preempt && top) || !f.interpreter_frame_method()->is_native(), "");
      if (Interpreted::is_owning_locks(f)) return freeze_pinned_monitor;
      if (_preempt && top && f.interpreter_frame_method()->is_native()) return freeze_pinned_native; // interpreter native entry

      return recurse_freeze_interpreted_frame(f, caller, callee_argsize, callee_interpreted);
    } else if (_preempt && top && Frame::is_stub(f.cb())) {
      return recurse_freeze_stub_frame(f, caller);
    } else {
      return freeze_pinned_native;
    }
  }

  template<typename FKind>
  inline freeze_result recurse_freeze_java_frame(const frame& f, frame& caller, int fsize, int argsize) {
    assert (FKind::is_instance(f), "");
    // log_develop_trace(jvmcont)("recurse_freeze_java_frame fsize: %d frame_bottom: " INTPTR_FORMAT " _bottom_address: " INTPTR_FORMAT, fsize, p2i(FKind::frame_bottom(f)), p2i(_bottom_address));

    assert (fsize > 0 && argsize >= 0, "");
    _frames++;
    _size += fsize;

    if (FKind::frame_bottom(f) >= _bottom_address - 1) { // sometimes there's a space between enterSpecial and the next frame
      return finalize_freeze<FKind>(f, caller, argsize); // recursion end
    } else {
      frame senderf = sender<FKind>(f);
      assert (FKind::interpreted || senderf.sp() == senderf.unextended_sp(), "");
      freeze_result result = freeze(senderf, caller, argsize, FKind::interpreted, false); // recursive call

      return result;
    }
  }

  inline void before_freeze_java_frame(const frame& f, const frame& caller, int fsize, int argsize, bool bottom) {
    log_develop_trace(jvmcont)("============================= FREEZING FRAME interpreted: %d bottom: %d", f.is_interpreted_frame(), bottom);
    log_develop_trace(jvmcont)("fsize: %d argsize: %d", fsize, argsize);
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);
    assert (caller.is_interpreted_frame() == Interpreter::contains(caller.pc()), "");
  }

  inline void after_freeze_java_frame(const frame& hf, bool bottom) {
    DEBUG_ONLY(if (log_develop_is_enabled(Trace, jvmcont)) hf.print_value_on(tty, nullptr);)
    DEBUG_ONLY(if (log_develop_is_enabled(Trace, jvmcont)) print_frame_layout<true>(hf);)
    if (bottom && log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("bottom h-frame:");
      hf.print_on<true>(tty);
    }
  }

  template<typename FKind> // the callee's type
  freeze_result finalize_freeze(const frame& callee, frame& caller, int argsize) {
  #ifdef CALLGRIND_START_INSTRUMENTATION
    if (_frames > 0 && _cgrind_interpreted_frames == 0 && callgrind_counter == 1) {
      callgrind_counter = 2;
      tty->print_cr("Starting callgrind instrumentation");
      CALLGRIND_START_INSTRUMENTATION;
    }
  #endif

    // const int argsize = _cont.argsize();
    // assert (FKind::interpreted || argsize == Compiled::stack_argsize(callee), "argsize: %d argsize(callee): %d", argsize, Compiled::stack_argsize(callee));
    assert (FKind::interpreted || argsize == _cont.argsize(), "argsize: %d _cont.argsize(): %d", argsize, _cont.argsize());
    log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d argsize: %d", p2i(_bottom_address), nr_frames(), nr_bytes(), argsize);

  #ifdef ASSERT
    bool empty = _cont.is_empty();
    log_develop_trace(jvmcont)("empty: %d", empty);
  #endif

    stackChunkOop chunk = _cont.tail();

    assert (chunk == nullptr || (chunk->max_size() == 0) == chunk->is_empty(), "chunk->max_size(): %d chunk->is_empty(): %d", chunk->max_size(), chunk->is_empty());

    _size += ContinuationHelper::frame_metadata; // for top frame's metadata

    int overlap = 0; // the args overlap the caller -- if there is one in this chunk and is of the same kind
    int unextended_sp = -1;
    if (chunk != nullptr) {
      unextended_sp = chunk->sp();
      if (!chunk->is_empty()) {
        bool top_interpreted = Interpreter::contains(chunk->pc());
        unextended_sp = chunk->sp();
        if (top_interpreted) {
          StackChunkFrameStream<true> last(chunk);
          unextended_sp += last.unextended_sp() - last.sp(); // can be negative (-1), often with lambda forms
        }
        if (FKind::interpreted == top_interpreted) {
          overlap = argsize;
        }
      }
    }
    // else if (FKind::interpreted) {
    //   argsize = 0;
    // }

    log_develop_trace(jvmcont)("finalize _size: %d overlap: %d unextended_sp: %d", _size, overlap, unextended_sp);

    _size -= overlap;
    assert (_size >= 0, "");

    assert (chunk == nullptr || chunk->is_empty() || unextended_sp == chunk->to_offset(StackChunkFrameStream<true>(chunk).unextended_sp()), "");
    assert (chunk != nullptr || unextended_sp < _size, "");

     // _barriers can be set to true by an allocation in freeze_fast, in which case the chunk is available
    assert (!_barriers || (unextended_sp >= _size && chunk->is_empty()), "unextended_sp: %d size: %d is_empty: %d", unextended_sp, _size, chunk->is_empty());

    DEBUG_ONLY(bool empty_chunk = true);
    if (unextended_sp < _size || chunk->is_gc_mode() || (!_barriers && ConfigT::requires_barriers(chunk))) {
      // ALLOCATION

      if (log_develop_is_enabled(Trace, jvmcont)) {
        if (chunk == nullptr) log_develop_trace(jvmcont)("is chunk available: no chunk");
        else {
          log_develop_trace(jvmcont)("is chunk available: barriers: %d _size: %d free size: %d", chunk->requires_barriers(), _size, chunk->sp() - ContinuationHelper::frame_metadata);
          chunk->print_on(tty);
        }
      }

      _size += overlap; // we're allocating a new chunk, so no overlap
      // overlap = 0;

      chunk = allocate_chunk(_size);
      if (chunk == (oop)nullptr) {
        return freeze_exception;
      }

      int sp = chunk->stack_size() - argsize;
      chunk->set_sp(sp);
      chunk->set_gc_sp(sp);
      chunk->set_argsize(argsize);
      assert (chunk->is_empty(), "");
      _barriers = ConfigT::requires_barriers(chunk);

      if (_barriers) { log_develop_trace(jvmcont)("allocation requires barriers"); }

      _cont.set_tail(chunk);
      // jdk_internal_vm_Continuation::set_tail(_cont.mirror(), _cont.tail()); -- doesn't seem to help
    } else {
      log_develop_trace(jvmcont)("Reusing chunk mixed: %d empty: %d interpreted callee: %d caller: %d", chunk->has_mixed_frames(), chunk->is_empty(), callee.is_interpreted_frame(), Interpreter::contains(chunk->pc()));
      if (chunk->is_empty()) {
        int sp = chunk->stack_size() - argsize;
        chunk->set_sp(sp);
        chunk->set_gc_sp(sp);
        chunk->set_argsize(argsize);
        _size += overlap;
        assert (chunk->max_size() == 0, "");
      } DEBUG_ONLY(else empty_chunk = false;)
    }
    chunk->set_has_mixed_frames(true);

    assert (chunk->requires_barriers() == _barriers, "");
    assert (!_barriers || chunk->is_empty(), "");

    assert (!chunk->has_bitmap(), "");
    assert (!chunk->is_empty() || StackChunkFrameStream<true>(chunk).is_done(), "");
    assert (!chunk->is_empty() || StackChunkFrameStream<true>(chunk).to_frame().is_empty(), "");

    if (should_flush_stack_processing())
      flush_stack_processing();

    log_develop_trace(jvmcont)("add max_size: %d -- %d", _size - ContinuationHelper::frame_metadata, chunk->max_size() + _size - ContinuationHelper::frame_metadata);
    chunk->set_max_size(chunk->max_size() + _size - ContinuationHelper::frame_metadata);

    log_develop_trace(jvmcont)("top chunk:");
    if (log_develop_is_enabled(Trace, jvmcont)) chunk->print_on(tty);

    caller = StackChunkFrameStream<true>(chunk).to_frame();

    DEBUG_ONLY(_last_write = caller.unextended_sp() + (empty_chunk ? argsize : overlap);)
    // tty->print_cr(">>> finalize_freeze chunk->sp_address(): %p empty_chunk: %d argsize: %d overlap: %d _last_write: %p _last_write - _size: %p", chunk->sp_address(), empty_chunk, argsize, overlap, _last_write, _last_write - _size); // caller.print_on<true>(tty);
    assert(chunk->is_in_chunk(_last_write - _size), "_last_write - _size: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(_last_write - _size), p2i(chunk->start_address()));
  #ifdef ASSERT
    log_develop_trace(jvmcont)("top_hframe before (freeze):");
    if (log_develop_is_enabled(Trace, jvmcont)) caller.print_on<true>(tty);

    assert (!empty || Frame::assert_bottom_java_frame_name(callee, ENTER_SIG), "");

    frame entry = sender<FKind>(callee);

    log_develop_trace(jvmcont)("Found entry:");
    if (log_develop_is_enabled(Trace, jvmcont)) entry.print_on(tty);

    assert (FKind::interpreted || entry.sp() == entry.unextended_sp(), "");
#endif

    return freeze_ok_bottom;
  }

  template <typename FKind>
  void patch(const frame& f, frame& hf, const frame& caller, bool bottom) {
    assert (FKind::is_instance(f), "");

    if (bottom) {
      address last_pc = caller.pc();
      assert ((last_pc == nullptr) == _cont.tail()->is_empty(), "");

      log_develop_trace(jvmcont)("Fixing return address on bottom frame: " INTPTR_FORMAT, p2i(last_pc));

      FKind::patch_pc(caller, last_pc);

      patch_pd<FKind, true>(hf, caller);
    } else {
      assert (!caller.is_empty(), "");
      // in fast mode, partial copy does not copy _is_interpreted for the caller
      assert (Interpreter::contains(FKind::interpreted ? FKind::return_pc(hf) : Frame::real_pc(caller)) == caller.is_interpreted_frame(),
        "FKind: %s contains: %d is_interpreted: %d", FKind::name, Interpreter::contains(FKind::interpreted ? FKind::return_pc(hf) : Frame::real_pc(caller)), caller.is_interpreted_frame());

      patch_pd<FKind, false>(hf, caller);
    }
    if (FKind::interpreted) {
      Interpreted::patch_sender_sp<true>(hf, caller.unextended_sp());
    }

#ifdef ASSERT
    // TODO DEOPT: long term solution: unroll on freeze and patch pc
    if (!FKind::interpreted && !FKind::stub) {
      assert (hf.get_cb()->is_compiled(), "");
      if (f.is_deoptimized_frame()) {
        log_develop_trace(jvmcont)("Freezing deoptimized frame");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f)), "");
      }
    }
#endif
  }

  NOINLINE freeze_result recurse_freeze_interpreted_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted) {
    { // TODO PD
      assert ((f.at<false>(frame::interpreter_frame_last_sp_offset) != 0) || (f.unextended_sp() == f.sp()), "");
      intptr_t* real_unextended_sp = (intptr_t*)f.at<false>(frame::interpreter_frame_last_sp_offset);
      if (real_unextended_sp != nullptr) f.set_unextended_sp(real_unextended_sp); // can be null at a safepoint
    }

    intptr_t* const vsp = Interpreted::frame_top(f, callee_argsize, callee_interpreted);
    const int argsize = Interpreted::stack_argsize(f);
    const int locals = f.interpreter_frame_method()->max_locals();
    assert (Interpreted::frame_bottom<false>(f) >= f.fp() + ContinuationHelper::frame_metadata + locals, ""); // equal on x86
    const int fsize = f.fp() + ContinuationHelper::frame_metadata + locals - vsp;

#ifdef ASSERT
  {
    ResourceMark rm;
    InterpreterOopMap mask;
    f.interpreted_frame_oop_map(&mask);
    assert (vsp <= Interpreted::frame_top(f, &mask), "vsp: " INTPTR_FORMAT " Interpreted::frame_top: " INTPTR_FORMAT, p2i(vsp), p2i(Interpreted::frame_top(f, &mask)));
    // if (!(fsize + 1 >= Interpreted::size(f, &mask))) {
    //   tty->print_cr("bottom: %p top: %p", Interpreted::frame_bottom<false>(f), Interpreted::frame_top(f, &mask));
    //   tty->print_cr("vsp: %p fp: %p locals: %d", vsp, f.fp(), locals);
    //   f.print_on(tty);
    //   print_frame_layout<false>(f);
    // }
    // Seen to fail on serviceability/jvmti/vthread/SuspendResume[1/2] on AArch64
    // assert (fsize + 1 >= Interpreted::size(f, &mask), "fsize: %d Interpreted::size: %d", fsize, Interpreted::size(f, &mask)); // add 1 for possible alignment padding
    if (fsize > Interpreted::size(f, &mask) + 1) {
      log_develop_trace(jvmcont)("III fsize: %d Interpreted::size: %d", fsize, Interpreted::size(f, &mask));
      log_develop_trace(jvmcont)("    vsp: " INTPTR_FORMAT " Interpreted::frame_top: " INTPTR_FORMAT, p2i(vsp), p2i(Interpreted::frame_top(f, &mask)));
    }
  }
#endif

    Method* frame_method = Frame::frame_method(f);

    log_develop_trace(jvmcont)("recurse_freeze_interpreted_frame %s _size: %d fsize: %d argsize: %d callee_interpreted: %d callee_argsize: %d :: " INTPTR_FORMAT " - " INTPTR_FORMAT,
      frame_method->name_and_sig_as_C_string(), _size, fsize, argsize, callee_interpreted, callee_argsize, p2i(vsp), p2i(vsp+fsize));

    freeze_result result = recurse_freeze_java_frame<Interpreted>(f, caller, fsize, argsize);
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    bool bottom = result == freeze_ok_bottom;

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, bottom);)

    frame hf = new_hframe<Interpreted>(f, caller);

    // tty->print_cr(">>> INTERPRETED bottom: %d argsize: %d callee_argsize: %d callee_interpreted: %d caller_interpreted: %d", bottom, argsize, callee_argsize, callee_interpreted, caller.is_interpreted_frame());

    intptr_t* hsp = Interpreted::frame_top(hf, callee_argsize, callee_interpreted);
    assert (Interpreted::frame_bottom<true>(hf) == hsp + fsize, "");

    // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
    copy_to_chunk<false>(Interpreted::frame_bottom<false>(f) - locals, Interpreted::frame_bottom<true>(hf) - locals, locals); // copy locals
    copy_to_chunk<false>(vsp, hsp, fsize - locals); // copy rest
    assert (!bottom || !caller.is_interpreted_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

    relativize_interpreted_frame_metadata(f, hf);

    patch<Interpreted>(f, hf, caller, bottom);

    _cont.inc_num_interpreted_frames();
    DEBUG_ONLY(after_freeze_java_frame(hf, bottom);)
    caller = hf;

    // Mark frame_method's marking cycle for GC and redefinition on_stack calculation.
    frame_method->record_marking_cycle();

    return freeze_ok;
  }

  freeze_result recurse_freeze_compiled_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted) {
    intptr_t* const vsp = Compiled::frame_top(f, callee_argsize, callee_interpreted);
    const int argsize = Compiled::stack_argsize(f);
    const int fsize = Compiled::frame_bottom(f) + argsize - vsp;

    log_develop_trace(jvmcont)("recurse_freeze_compiled_frame %s _size: %d fsize: %d argsize: %d callee_interpreted: %d callee_argsize: %d :: " INTPTR_FORMAT " - " INTPTR_FORMAT,
      Frame::frame_method(f) != nullptr ? Frame::frame_method(f)->name_and_sig_as_C_string() : "", _size, fsize, argsize, callee_interpreted, callee_argsize, p2i(vsp), p2i(vsp+fsize));

    freeze_result result = recurse_freeze_java_frame<Compiled>(f, caller, fsize, argsize);
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    bool bottom = result == freeze_ok_bottom;

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, argsize, bottom);)

    frame hf = new_hframe<Compiled>(f, caller);

    intptr_t* hsp = Compiled::frame_top(hf, callee_argsize, callee_interpreted);

    // tty->print_cr(">>> COMPILED bottom: %d argsize: %d callee_argsize: %d callee_interpreted: %d caller_interpreted: %d", bottom, argsize, callee_argsize, callee_interpreted, caller.is_interpreted_frame());
    copy_to_chunk<false>(vsp, hsp, fsize);
    assert (!bottom || !caller.is_compiled_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

    if (caller.is_interpreted_frame()) {
      log_develop_trace(jvmcont)("add max_size align %d", ContinuationHelper::align_wiggle);
      _align_size += ContinuationHelper::align_wiggle; // See Thaw::align
    }

    patch<Compiled>(f, hf, caller, bottom);

    // log_develop_trace(jvmcont)("freeze_compiled_frame real_pc: " INTPTR_FORMAT " address: " INTPTR_FORMAT " sp: " INTPTR_FORMAT, p2i(Frame::real_pc(f)), p2i(&(((address*) f.sp())[-1])), p2i(f.sp()));
    assert(bottom || Interpreter::contains(Compiled::real_pc(caller)) == caller.is_interpreted_frame(), "");

    DEBUG_ONLY(after_freeze_java_frame(hf, bottom);)
    caller = hf;
    return freeze_ok;
  }

  NOINLINE freeze_result recurse_freeze_stub_frame(frame& f, frame& caller) {
    intptr_t* const vsp = StubF::frame_top(f, 0, 0);
    const int fsize = f.cb()->frame_size();

    log_develop_trace(jvmcont)("recurse_freeze_stub_frame %s _size: %d fsize: %d :: " INTPTR_FORMAT " - " INTPTR_FORMAT,
      f.cb()->name(), _size, fsize, p2i(vsp), p2i(vsp+fsize));

    // we're inlining recurse_freeze_java_frame and freeze here because we need to use a full RegisterMap to test lock ownership
    _frames++;
    _size += fsize;

    RegisterMap map(_cont.thread(), true, false, false);
    map.set_include_argument_oops(false);
    ContinuationHelper::update_register_map<StubF>(&map, f);
    f.oop_map()->update_register_map(&f, &map); // we have callee-save registers in this case
    frame senderf = sender<StubF>(f);
    assert (senderf.unextended_sp() < _bottom_address - 1, "");
    assert (senderf.is_compiled_frame(), "");

    if (UNLIKELY(senderf.oop_map() == nullptr)) return freeze_pinned_native; // native frame
    if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), &map, senderf))) return freeze_pinned_monitor;

    freeze_result result = recurse_freeze_compiled_frame(senderf, caller, 0, 0);
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    assert (result != freeze_ok_bottom, "");
    assert (!caller.is_interpreted_frame(), "");

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, false);)
    frame hf = new_hframe<StubF>(f, caller);
    intptr_t* hsp = StubF::frame_top(hf, 0, 0);
    copy_to_chunk<false>(vsp, hsp, fsize);
    DEBUG_ONLY(after_freeze_java_frame(hf, false);)

    caller = hf;
    return freeze_ok;
  }

  NOINLINE void finish_freeze(const frame& f, const frame& top) {
    stackChunkOop chunk = _cont.tail();
    assert (chunk->to_offset(top.sp()) <= chunk->sp(), "top.sp(): %d sp: %d", chunk->to_offset(top.sp()), chunk->sp());

    if (log_develop_is_enabled(Trace, jvmcont)) top.print_on<true>(tty);

    set_top_frame_metadata_pd(top);
    assert (top.pc() == Frame::real_pc(top), "");

    OrderAccess::storestore();
    chunk->set_sp(chunk->to_offset(top.sp()));
    chunk->set_pc(top.pc());

    log_develop_trace(jvmcont)("add max_size _align_size: %d -- %d", _align_size, chunk->max_size() + _align_size);
    chunk->set_max_size(chunk->max_size() + _align_size);

    if (UNLIKELY(_barriers)) {
      log_develop_trace(jvmcont)("do barriers on humongous chunk");
      InstanceStackChunkKlass::do_barriers<true>(_cont.tail());
    }

    log_develop_trace(jvmcont)("finish_freeze: has_mixed_frames: %d", chunk->has_mixed_frames());

    if (log_develop_is_enabled(Trace, jvmcont)) {
      log_develop_trace(jvmcont)("top_hframe after (freeze):");
      _cont.last_frame().template print_on<true>(tty);
    }

    assert(_cont.chunk_invariant(), "");
  }

  static inline void relativize(intptr_t* const vfp, intptr_t* const hfp, int offset) {
    assert (*(hfp + offset) == *(vfp + offset), "vaddr: " INTPTR_FORMAT " *vaddr: " INTPTR_FORMAT " haddr: " INTPTR_FORMAT " *haddr: " INTPTR_FORMAT, p2i(vfp + offset) , *(vfp + offset), p2i(hfp + offset) , *(hfp + offset));
    intptr_t* addr = hfp + offset;
    intptr_t value = *(intptr_t**)addr - vfp;
    // tty->print_cr(">>>> relativize offset: %d fp: %p delta: %ld derel: %p", offset, vfp, value, *(intptr_t**)addr);
    *addr = value;
  }

  stackChunkOop allocate_chunk(int size) {
    log_develop_trace(jvmcont)("allocate_chunk allocating new chunk");
    stackChunkOop chunk = _cont.allocate_stack_chunk(size, _preempt);
    if (chunk == nullptr) { // OOM
      return nullptr;
    }
    assert (chunk->stack_size() == size, "");
    assert (chunk->size() >= size, "chunk->size(): %d size: %d", chunk->size(), size);
    assert ((intptr_t)chunk->start_address() % 8 == 0, "");

    stackChunkOop chunk0 = _cont.tail();
    if (chunk0 != (oop)nullptr && chunk0->is_empty()) {
      // chunk0 = chunk0->is_parent_null<typename ConfigT::OopT>() ? (oop)nullptr : chunk0->parent();
      chunk0 = chunk0->parent();
      assert (chunk0 == (oop)nullptr || !chunk0->is_empty(), "");
    }

    // TODO PERF: maybe just memset 0, and only set non-zero fields.
    // chunk->set_pc(nullptr);
    // chunk->set_argsize(0);
    chunk->clear_flags();
    chunk->set_gc_mode(false);
    chunk->set_max_size(0);
    chunk->set_mark_cycle(0);
    chunk->reset_counters();
    // chunk->set_pc(nullptr); // TODO PERF: necessary?

    assert (chunk->flags() == 0, "");
    assert (chunk->is_gc_mode() == false, "");
    assert (chunk->mark_cycle() == 0, "");
    assert (chunk->numFrames() <= 0, "");
    assert (chunk->numOops() <= 0, "");
    assert (chunk->max_size() == 0, "");

    // fields are uninitialized
    chunk->set_parent_raw<typename ConfigT::OopT>(chunk0);
    chunk->set_cont_raw<typename ConfigT::OopT>(_cont.mirror());

    // Promote young chunks quickly
    chunk->set_mark(chunk->mark().set_age(15));

    return chunk;
  }

  int remaining_in_chunk(stackChunkOop chunk) {
    return chunk->stack_size() - chunk->sp();
  }
};

int early_return(int res, JavaThread* thread) {
  thread->set_cont_yield(false);
  log_develop_trace(jvmcont)("=== end of freeze (fail %d)", res);
  return res;
}

#if INCLUDE_JVMTI
static void invalidate_JVMTI_stack(JavaThread* thread) {
  if (thread->is_interp_only_mode()) {
    JvmtiThreadState *state = thread->jvmti_thread_state();
    if (state != nullptr)
      state->invalidate_cur_stack_depth();
  }
}
#endif // INCLUDE_JVMTI

static void JVMTI_yield_cleanup(JavaThread* thread, ContMirror& cont) {
#if INCLUDE_JVMTI
  if (JvmtiExport::can_post_frame_pop()) {
    ContinuationHelper::set_anchor_to_entry(thread, cont.entry()); // ensure frozen frames are invisible

    // cont.read_rest();
    int num_frames = num_java_frames(cont);

    // The call to JVMTI can safepoint, so we need to restore oops.
    Handle conth(Thread::current(), cont.mirror());
    JvmtiExport::continuation_yield_cleanup(JavaThread::current(), num_frames);
    cont.post_safepoint(conth);
  }
  invalidate_JVMTI_stack(thread);
#endif
}

static freeze_result is_pinned(const frame& f, RegisterMap* map) {
  if (f.is_interpreted_frame()) {
    if (Interpreted::is_owning_locks(f))           return freeze_pinned_monitor;
    if (f.interpreter_frame_method()->is_native()) return freeze_pinned_native; // interpreter native entry
  } else if (f.is_compiled_frame()) {
    if (Compiled::is_owning_locks(map->thread(), map, f)) return freeze_pinned_monitor;
  } else {
    return freeze_pinned_native;
  }
  return freeze_ok;
}

#ifdef ASSERT
static bool monitors_on_stack(JavaThread* thread) {
  ContinuationEntry* cont = thread->last_continuation();
  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  for (frame f = thread->last_frame(); Continuation::is_frame_in_continuation(cont, f); f = f.sender(&map)) {
    if (is_pinned(f, &map) == freeze_pinned_monitor) return true;
  }
  return false;
}

static bool interpreted_native_or_deoptimized_on_stack(JavaThread* thread) {
  ContinuationEntry* cont = thread->last_continuation();
  RegisterMap map(thread, false, false, false);
  map.set_include_argument_oops(false);
  for (frame f = thread->last_frame(); Continuation::is_frame_in_continuation(cont, f); f = f.sender(&map)) {
    if (f.is_interpreted_frame() || f.is_native_frame() || f.is_deoptimized_frame()) {
      // tty->print_cr("interpreted_native_or_deoptimized_on_stack"); f.print_on(tty);
      return true;
    }
  }
  return false;
}
#endif
static inline bool can_freeze_fast(JavaThread* thread) {
  // There are no interpreted frames if we're not called from the interpreter and we haven't ancountered an i2c adapter or called Deoptimization::unpack_frames
  // Calls from native frames also go through the interpreter (see JavaCalls::call_helper)

  #ifdef ASSERT
    if (!(!thread->cont_fastpath() || (thread->cont_fastpath_thread_state() && !interpreted_native_or_deoptimized_on_stack(thread)))) { pns2(); pfl(); }
  #endif
  assert (!thread->cont_fastpath() || (thread->cont_fastpath_thread_state() && !interpreted_native_or_deoptimized_on_stack(thread)), "thread->raw_cont_fastpath(): " INTPTR_FORMAT " thread->cont_fastpath_thread_state(): %d", p2i(thread->raw_cont_fastpath()), thread->cont_fastpath_thread_state());

  // We also clear thread->cont_fastpath on deoptimization (notify_deopt) and when we thaw interpreted frames
  bool fast = UseContinuationFastPath && thread->cont_fastpath();
  assert (!fast || monitors_on_stack(thread) == (thread->held_monitor_count() > 0), "monitors_on_stack: %d held_monitor_count: %d", monitors_on_stack(thread), thread->held_monitor_count());
  fast = fast && thread->held_monitor_count() == 0;
  // if (!fast) tty->print_cr(">>> freeze fast: %d thread.cont_fastpath: %d held_monitor_count: %d", fast, thread->cont_fastpath(), thread->held_monitor_count());
  return fast;
}


static inline int freeze_epilog(JavaThread* thread, ContMirror& cont, bool preempt) {
  assert (verify_continuation<2>(cont.mirror()), "");

  assert (!cont.is_empty(), "");

  ContinuationHelper::set_anchor_to_entry(thread, cont.entry()); // ensure frozen frames are invisible to stack walks
  if (!preempt) {
    StackWatermarkSet::after_unwind(thread);
  }

  thread->set_cont_yield(false);

  log_develop_debug(jvmcont)("=== End of freeze cont ### #" INTPTR_FORMAT, cont.hash());

  return 0;
}

static int freeze_epilog(JavaThread* thread, ContMirror& cont, freeze_result res, bool preempt) {
  if (UNLIKELY(res != freeze_ok)) {
    assert (verify_continuation<11>(cont.mirror()), "");
    return early_return(res, thread);
  }
#if CONT_JFR
  cont.post_jfr_event(&event, thread);
#endif
  JVMTI_yield_cleanup(thread, cont); // can safepoint
  return freeze_epilog(thread, cont, preempt);
}

// returns the continuation yielding (based on context), or nullptr for failure (due to pinning)
// it freezes multiple continuations, depending on contex
// it must set Continuation.stackSize
// sets Continuation.fp/sp to relative indices
template<typename ConfigT>
int freeze0(JavaThread* current, intptr_t* const sp, bool preempt) {
  //callgrind();
  assert (!current->cont_yield(), "");
  assert (!current->has_pending_exception(), ""); // if (current->has_pending_exception()) return early_return(freeze_exception, current, fi);
  assert (current->deferred_updates() == nullptr || current->deferred_updates()->count() == 0, "");
  assert (!preempt || current->thread_state() == _thread_in_vm || current->thread_state() == _thread_blocked
          /*|| current->thread_state() == _thread_in_native*/,
          "thread_state: %d %s", current->thread_state(), current->thread_state_name());

#ifdef ASSERT
  log_develop_trace(jvmcont)("~~~~~~~~~ freeze sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT,
                             p2i(current->last_continuation()->entry_sp()),
                             p2i(current->last_continuation()->entry_fp()),
                             p2i(current->last_continuation()->entry_pc()));

  /* ContinuationHelper::set_anchor(current, fi); */ print_frames(current);
#endif

#if CONT_JFR
  EventContinuationFreeze event;
#endif

  current->set_cont_yield(true);

  oop oopCont = ContinuationHelper::get_continuation(current);
  assert (oopCont == current->last_continuation()->cont_oop(), "");
  assert (ContinuationEntry::assert_entry_frame_laid_out(current), "");

  assert (verify_continuation<1>(oopCont), "");
  ContMirror cont(current, oopCont);
  log_develop_debug(jvmcont)("FREEZE #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  if (jdk_internal_vm_Continuation::critical_section(oopCont) > 0) {
    log_develop_debug(jvmcont)("PINNED due to critical section");
    assert (verify_continuation<10>(cont.mirror()), "");
    return early_return(freeze_pinned_cs, current);
  }

  bool fast = can_freeze_fast(current);
  assert (!fast || current->held_monitor_count() == 0, "");

  Freeze<ConfigT> fr(current, cont, preempt);

  if (UNLIKELY(preempt)) {
    freeze_result res = fr.freeze_slow();
    cont.set_preempted(true);
    return freeze_epilog(current, cont, res, preempt);
  }

  if (fast && fr.is_chunk_available(sp)) {
    log_develop_trace(jvmcont)("chunk available; no transition");
    freeze_result res = fr.try_freeze_fast(sp, true);
    assert (res == freeze_ok, "");
  #if CONT_JFR
    cont.post_jfr_event(&event, current);
  #endif

    // if (UNLIKELY(preempt)) cont.set_preempted(true);
    return freeze_epilog(current, cont, preempt);
  }

  // if (current->held_monitor_count() > 0) {
  //    // tty->print_cr(">>> FAIL FAST");
  //    return freeze_pinned_monitor;
  // }

  log_develop_trace(jvmcont)("chunk unavailable; transitioning to VM");
  assert(current == JavaThread::current(), "must be current thread except for preempt");
  JRT_BLOCK
    freeze_result res = fast ? fr.try_freeze_fast(sp, false) : fr.freeze_slow();
    return freeze_epilog(current, cont, res, preempt);
  JRT_BLOCK_END
}

// Entry point to freeze. Transitions are handled manually
JRT_BLOCK_ENTRY(int, Continuation::freeze(JavaThread* current, intptr_t* sp))
  // current->frame_anchor()->set_last_Java_sp(sp);
  // current->frame_anchor()->make_walkable(current);

  assert (sp == current->frame_anchor()->last_Java_sp(), "");

  if (current->raw_cont_fastpath() > current->last_continuation()->entry_sp() || current->raw_cont_fastpath() < sp) {
    current->set_cont_fastpath(nullptr);
  }

  return cont_freeze(current, sp, false);
JRT_END

static freeze_result is_pinned0(JavaThread* thread, oop cont_scope, bool safepoint) {
  ContinuationEntry* cont = thread->last_continuation();
  if (cont == nullptr) {
    return freeze_ok;
  }
  if (jdk_internal_vm_Continuation::critical_section(cont->continuation()) > 0)
    return freeze_pinned_cs;

  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  frame f = thread->last_frame();

  if (!safepoint) {
    f = f.sender(&map); // this is the yield frame
  } else { // safepoint yield
    f.set_fp(f.real_fp()); // Instead of this, maybe in ContMirror::set_last_frame always use the real_fp?
    if (!Interpreter::contains(f.pc())) {
      assert (Frame::is_stub(f.cb()), "must be");
      assert (f.oop_map() != nullptr, "must be");
      f.oop_map()->update_register_map(&f, &map); // we have callee-save registers in this case
    }
  }

  while (true) {
    freeze_result res = is_pinned(f, &map);
    if (res != freeze_ok)
      return res;

    f = f.sender(&map);
    if (!Continuation::is_frame_in_continuation(cont, f)) {
      oop scope = jdk_internal_vm_Continuation::scope(cont->continuation());
      if (scope == cont_scope)
        break;
      cont = cont->parent();
      if (cont == nullptr)
        break;
      if (jdk_internal_vm_Continuation::critical_section(cont->continuation()) > 0)
        return freeze_pinned_cs;
    }
  }
  return freeze_ok;
}

static void print_stack_trace(JavaThread* thread) {
  if (log_is_enabled(Trace, jvmcont, preempt)) {
    LogTarget(Trace, jvmcont, preempt) lt;
    assert(lt.is_enabled(), "already tested");
    ResourceMark rm;
    LogStream ls(lt);
    char buf[256];
    ls.print_cr("Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)");
    for (StackFrameStream sfs(thread, true /* update */, true /* process_frames */); !sfs.is_done(); sfs.next()) {
      sfs.current()->print_on_error(&ls, buf, 256, false);
      ls.cr();
    }
  }
}

static bool is_safe_to_preempt(JavaThread* thread) {

  if (!thread->has_last_Java_frame()) {
    log_trace(jvmcont, preempt)("is_safe_to_preempt: no last Java frame");
    return false;
  }

  if (log_is_enabled(Trace, jvmcont, preempt)) {
    LogTarget(Trace, jvmcont, preempt) lt;
    assert(lt.is_enabled(), "already tested");
    ResourceMark rm;
    LogStream ls(lt);
    frame f = thread->last_frame();
    ls.print("is_safe_to_preempt %sSAFEPOINT ", Interpreter::contains(f.pc()) ? "INTERPRETER " : "");
    f.print_on(&ls);
  }

  address pc = thread->last_Java_pc();
  if (Interpreter::contains(pc)) {
    // Generally, we don't want to preempt when returning from some useful VM function, and certainly not when inside one.
    InterpreterCodelet* codelet = Interpreter::codelet_containing(pc);
    if (codelet != nullptr) {
      // We allow preemption only when at a safepoint codelet or a return byteocde
      if (codelet->bytecode() >= 0 && Bytecodes::is_return(codelet->bytecode())) {
        log_trace(jvmcont, preempt)("is_safe_to_preempt: safe bytecode: %s",
                                    Bytecodes::name(codelet->bytecode()));
        assert (codelet->kind() == InterpreterCodelet::codelet_bytecode, "");
        return true;
      } else if (codelet->kind() == InterpreterCodelet::codelet_safepoint_entry) {
        log_trace(jvmcont, preempt)("is_safe_to_preempt: safepoint entry: %s", codelet->description());
        return true;
      } else {
        log_trace(jvmcont, preempt)("is_safe_to_preempt: %s (unsafe)", codelet->description());
        print_stack_trace(thread);
        return false;
      }
    } else {
      log_trace(jvmcont, preempt)("is_safe_to_preempt: no codelet (safe?)");
      return true;
    }
  } else {
    CodeBlob* cb = CodeCache::find_blob(pc);
    if (cb->is_safepoint_stub()) {
      log_trace(jvmcont, preempt)("is_safe_to_preempt: safepoint stub");
      return true;
    } else {
      log_trace(jvmcont, preempt)("is_safe_to_preempt: not safepoint stub");
      return false;
    }
  }
}

// Called while the thread is blocked by the JavaThread caller, might not be completely in blocked state.
// May still be in thread_in_vm getting to the blocked state.  I don't think we care that much since
// the only frames we're looking at are Java frames.
int Continuation::try_force_yield(JavaThread* target, const oop cont) {
  log_trace(jvmcont, preempt)("try_force_yield: thread state: %s", target->thread_state_name());

  ContinuationEntry* ce = target->last_continuation();
  oop innermost = ce->continuation();
  while (ce != nullptr && ce->continuation() != cont) {
    ce = ce->parent();
  }
  if (ce == nullptr) {
    return -1; // no continuation
  }
  if (target->_cont_yield) {
    return -2; // during yield
  }
  if (!is_safe_to_preempt(target)) {
    return freeze_pinned_native;
  }

  assert (target->has_last_Java_frame(), "");
  // if (Interpreter::contains(thread->last_Java_pc())) { thread->push_cont_fastpath(thread->last_Java_sp()); }
  assert (!Interpreter::contains(target->last_Java_pc()) || !target->cont_fastpath(),
          "fast_path at codelet %s",
          Interpreter::codelet_containing(target->last_Java_pc())->description());

  const oop scope = jdk_internal_vm_Continuation::scope(cont);
  if (innermost != cont) { // we have nested continuations
    // make sure none of the continuations in the hierarchy are pinned
    freeze_result res_pinned = is_pinned0(target, scope, true);
    if (res_pinned != freeze_ok) {
      log_trace(jvmcont, preempt)("try_force_yield: res_pinned");
      return res_pinned;
    }
    jdk_internal_vm_Continuation::set_yieldInfo(cont, scope);
  }

  assert (target->has_last_Java_frame(), "need to test again?");
  int res = cont_freeze(target, target->last_Java_sp(), true);
  log_trace(jvmcont, preempt)("try_force_yield: %s", freeze_result_names[res]);
  if (res == 0) { // success
    target->set_cont_preempt(true);

    // The target thread calls
    // Continuation::jump_from_safepoint from JavaThread::handle_special_runtime_exit_condition
    // to yield on return from suspension/blocking handshake.
  }
  return res;
}

typedef void (*cont_jump_from_sp_t)();

void Continuation::jump_from_safepoint(JavaThread* thread) {
  assert (thread == JavaThread::current(), "");
  assert (thread->is_cont_force_yield(), "");
  log_develop_trace(jvmcont)("force_yield_if_preempted: is_cont_force_yield");
  thread->set_cont_preempt(false);
  if (thread->thread_state() == _thread_in_vm) {
    thread->set_thread_state(_thread_in_Java);
  }
  StackWatermarkSet::after_unwind(thread);
  MACOS_AARCH64_ONLY(thread->enable_wx(WXExec));
  CAST_TO_FN_PTR(cont_jump_from_sp_t, StubRoutines::cont_jump_from_sp())(); // does not return
  ShouldNotReachHere();
}

/////////////// THAW ////

enum thaw_kind {
  thaw_top = 0,
  thaw_return_barrier = 1,
  thaw_exception = 2,
};

typedef intptr_t* (*ThawContFnT)(JavaThread*, thaw_kind);

static ThawContFnT cont_thaw = nullptr;

static bool stack_overflow_check(JavaThread* thread, int size, address sp) {
  const int page_size = os::vm_page_size();
  if (size > page_size) {
    if (sp - size < thread->stack_overflow_state()->stack_overflow_limit()) {
      return false;
    }
  }
  return true;
}

// make room on the stack for thaw
// returns the size in bytes, or 0 on failure
JRT_LEAF(int, Continuation::prepare_thaw(JavaThread* thread, bool return_barrier))
  log_develop_trace(jvmcont)("~~~~~~~~~ prepare_thaw return_barrier: %d", return_barrier);

  assert (thread == JavaThread::current(), "");

  oop cont = thread->last_continuation()->cont_oop(); // ContinuationHelper::get_continuation(thread);
  assert (cont == ContinuationHelper::get_continuation(thread), "cont: %p entry cont: %p", (oopDesc*)cont, (oopDesc*)ContinuationHelper::get_continuation(thread));
  assert (verify_continuation<1>(cont), "");

  stackChunkOop chunk = jdk_internal_vm_Continuation::tail(cont);
  assert (chunk != nullptr, "");
  if (UNLIKELY(chunk->is_empty())) {
    chunk = chunk->parent();
    jdk_internal_vm_Continuation::set_tail(cont, chunk);
  }
  assert (chunk != nullptr, "");
  assert (!chunk->is_empty(), "");
  assert (verify_stack_chunk<1>(chunk), "");

  int size = chunk->max_size();
  guarantee (size > 0, "");

  size += 2*ContinuationHelper::frame_metadata; // twice, because we might want to add a frame for StubRoutines::cont_interpreter_forced_preempt_return()
  size += ContinuationHelper::align_wiggle; // just in case we have an interpreted entry after which we need to align
  size <<= LogBytesPerWord;

  const address bottom = (address)thread->last_continuation()->entry_sp(); // os::current_stack_pointer(); points to the entry frame
  if (!stack_overflow_check(thread, size + 300, bottom)) {
    return 0;
  }

  log_develop_trace(jvmcont)("prepare_thaw bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d", p2i(bottom), p2i(bottom - size), size);

  return size;
JRT_END

template <typename ConfigT>
class Thaw {
private:
  JavaThread* _thread;
  ContMirror& _cont;

  intptr_t* _fastpath;
  bool _barriers;
  intptr_t* _top_unextended_sp;

  void maybe_set_fastpath(intptr_t* sp) { if (sp > _fastpath) _fastpath = sp; }

  StackChunkFrameStream<true> _stream;

  int _align_size;

  NOT_PRODUCT(int _frames;)

  inline frame new_entry_frame();
  template<typename FKind> frame new_frame(const frame& hf, frame& caller, bool bottom);
  template<typename FKind, bool bottom> inline void patch_pd(frame& f, const frame& sender);
  inline intptr_t* align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom);
  void patch_chunk_pd(intptr_t* sp);
  inline intptr_t* align_chunk(intptr_t* vsp);
  inline void prefetch_chunk_pd(void* start, int size_words);
  intptr_t* push_interpreter_return_frame(intptr_t* sp);
  static inline void derelativize_interpreted_frame_metadata(const frame& hf, const frame& f);
  static inline void set_interpreter_frame_bottom(const frame& f, intptr_t* bottom);

  bool should_deoptimize() { return true; /* _thread->is_interp_only_mode(); */ } // TODO PERF

public:
  DEBUG_ONLY(int _mode;)
  DEBUG_ONLY(bool barriers() { return _barriers; })

  Thaw(JavaThread* thread, ContMirror& cont) :
    _thread(thread), _cont(cont),
    _fastpath(nullptr) {
      DEBUG_ONLY(_top_unextended_sp = nullptr;)
      DEBUG_ONLY(_mode = 0;)
  }

  inline bool can_thaw_fast(stackChunkOop chunk) {
    return    !_barriers
           &&  _thread->cont_fastpath_thread_state()
           && !chunk->has_mixed_frames();
  }

  intptr_t* thaw(thaw_kind kind) {
    assert (!Interpreter::contains(_cont.entryPC()), "");
    // if (Interpreter::contains(_cont.entryPC())) _fastpath = false; // set _fastpath to false if entry is interpreted

    assert (verify_continuation<1>(_cont.mirror()), "");
    assert (!jdk_internal_vm_Continuation::done(_cont.mirror()), "");
    assert (!_cont.is_empty(), "");

    DEBUG_ONLY(_frames = 0;)

    stackChunkOop chunk = _cont.tail();
    assert (chunk != nullptr && !chunk->is_empty(), ""); // guaranteed by prepare_thaw

    _barriers = (chunk->should_fix<typename ConfigT::OopT, ConfigT::_concurrent_gc>() || ConfigT::requires_barriers(chunk));
    if (LIKELY(can_thaw_fast(chunk))) {
      // if (kind != thaw_return_barrier) tty->print_cr("THAW FAST");
      return thaw_fast(chunk);
    } else {
      // if (kind != thaw_return_barrier) tty->print_cr("THAW SLOW");
      return thaw_slow(chunk, kind != thaw_top);
    }
  }

  NOINLINE intptr_t* thaw_fast(stackChunkOop chunk) {
    assert (chunk != (oop) nullptr, "");
    assert (chunk == _cont.tail(), "");
    assert (!chunk->is_empty(), "");
    assert (!chunk->has_mixed_frames(), "");
    assert (!chunk->requires_barriers(), "");
    assert (!_thread->is_interp_only_mode(), "");

    log_develop_trace(jvmcont)("thaw_fast");
    if (log_develop_is_enabled(Debug, jvmcont)) chunk->print_on(true, tty);

    static const int threshold = 500; // words

    int sp = chunk->sp();
    int size = chunk->stack_size() - sp;
    int argsize;

    // this initial size could be reduced if it's a partial thaw

    // assert (verify_continuation<99>(_cont.mirror()), "");

    intptr_t* const hsp = chunk->start_address() + sp;

    // Instead of invoking barriers on oops in thawed frames, we use the gcSP field; see continuationChunk's get_chunk_sp
    chunk->set_mark_cycle(CodeCache::marking_cycle());

    bool partial, empty;
    if (LIKELY(!TEST_THAW_ONE_CHUNK_FRAME && (size < threshold))) {
      // prefetch with anticipation of memcpy starting at highest address
      prefetch_chunk_pd(chunk->start_address(), size);

      partial = false;
      DEBUG_ONLY(_mode = 1;)

      argsize = chunk->argsize();
      empty = true;

      chunk->set_sp(chunk->stack_size());
      chunk->set_argsize(0);
      // chunk->clear_flags();
      chunk->reset_counters();
      chunk->set_max_size(0);
      log_develop_trace(jvmcont)("set max_size: 0");
      // chunk->set_pc(nullptr);

    } else { // thaw a single frame
      partial = true;
      DEBUG_ONLY(_mode = 2;)

      StackChunkFrameStream<false> f(chunk);
      assert (hsp == f.sp() && hsp == f.unextended_sp(), "");
      size = f.cb()->frame_size();
      argsize = f.stack_argsize();
      f.next(SmallRegisterMap::instance);
      empty = f.is_done(); // (chunk->sp() + size) >= (chunk->stack_size() - chunk->argsize());
      assert (!empty || argsize == chunk->argsize(), "");

      if (empty) {
        chunk->set_sp(chunk->stack_size());
        chunk->set_argsize(0);
        chunk->reset_counters();
        chunk->set_max_size(0);
        log_develop_trace(jvmcont)("set max_size: 0");
        // chunk->set_pc(nullptr);
      } else {
        chunk->set_sp(chunk->sp() + size);
        address top_pc = *(address*)(hsp + size - SENDER_SP_RET_ADDRESS_OFFSET);
        chunk->set_pc(top_pc);
        chunk->set_max_size(chunk->max_size() - size);
        log_develop_trace(jvmcont)("sub max_size: %d -- %d", size, chunk->max_size());
      }
      assert (empty == chunk->is_empty(), "");
      size += argsize;
    }

    const bool is_last = empty && chunk->is_parent_null<typename ConfigT::OopT>();

    log_develop_trace(jvmcont)("thaw_fast partial: %d is_last: %d empty: %d size: %d argsize: %d", partial, is_last, empty, size, argsize);

    intptr_t* vsp = _cont.entrySP();
    intptr_t* bottom_sp = align_chunk(vsp - argsize);

    vsp -= size;
    assert (argsize != 0 || vsp == align_chunk(vsp), "");
    vsp = align_chunk(vsp);

    intptr_t* from = hsp - ContinuationHelper::frame_metadata;
    intptr_t* to   = vsp - ContinuationHelper::frame_metadata;
    copy_from_chunk(from, to, size + ContinuationHelper::frame_metadata); // TODO: maybe use a memcpy that cares about ordering because we're racing with the GC
    assert (_cont.entrySP() - 1 <= to + size + ContinuationHelper::frame_metadata && to + size + ContinuationHelper::frame_metadata <= _cont.entrySP(), "");
    assert (argsize != 0 || to + size + ContinuationHelper::frame_metadata == _cont.entrySP(), "");

    assert (!is_last || argsize == 0, "");
    _cont.set_argsize(argsize);
    log_develop_trace(jvmcont)("setting entry argsize: %d", _cont.argsize());
    patch_chunk(bottom_sp, is_last);

    DEBUG_ONLY(address pc = *(address*)(bottom_sp - SENDER_SP_RET_ADDRESS_OFFSET);)
    assert (is_last ? CodeCache::find_blob(pc)->as_compiled_method()->method()->is_continuation_enter_intrinsic() : pc == StubRoutines::cont_returnBarrier(), "is_last: %d", is_last);

    assert (is_last == _cont.is_empty(), "is_last: %d _cont.is_empty(): %d", is_last, _cont.is_empty());
    assert(_cont.chunk_invariant(), "");

  #if CONT_JFR
    EventContinuationThawYoung e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(chunk));
      e.set_size(size << LogBytesPerWord);
      e.set_full(!partial);
      e.commit();
    }
  #endif

#ifdef ASSERT
  intptr_t* sp0 = vsp;
  ContinuationHelper::set_anchor(_thread, sp0);
  print_frames(_thread, tty); // must be done after write(), as frame walking reads fields off the Java objects.
  if (LoomDeoptAfterThaw) {
    do_deopt_after_thaw(_thread);
  }
  // if (LoomVerifyAfterThaw) {
  //   assert(do_verify_after_thaw(_thread), "partial: %d empty: %d is_last: %d fix: %d", partial, empty, is_last, fix);
  // }
  ContinuationHelper::clear_anchor(_thread);
#endif

    // assert (verify_continuation<100>(_cont.mirror()), "");
    return vsp;
  }

  template <bool aligned = true>
  void copy_from_chunk(intptr_t* from, intptr_t* to, int size) {
    assert (to + size <= _cont.entrySP(), "");
    _cont.tail()->template copy_from_chunk_to_stack<aligned>(from, to, size);
  }

  void patch_chunk(intptr_t* sp, bool is_last) {
    log_develop_trace(jvmcont)("thaw_fast patching -- sp: " INTPTR_FORMAT, p2i(sp));

    address pc = !is_last ? StubRoutines::cont_returnBarrier() : _cont.entryPC();
    *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET) = pc;
    log_develop_trace(jvmcont)("thaw_fast is_last: %d sp: " INTPTR_FORMAT " patching pc at " INTPTR_FORMAT " to " INTPTR_FORMAT, is_last, p2i(sp), p2i(sp - SENDER_SP_RET_ADDRESS_OFFSET), p2i(pc));

    // patch_chunk_pd(sp);
  }

  intptr_t* thaw_slow(stackChunkOop chunk, bool return_barrier) {
    assert (!_cont.is_empty(), "");
    assert (chunk != nullptr, "");
    assert (!chunk->is_empty(), "");

    log_develop_trace(jvmcont)("thaw slow return_barrier: %d chunk: " INTPTR_FORMAT, return_barrier, p2i((stackChunkOopDesc*)chunk));
    if (log_develop_is_enabled(Trace, jvmcont)) chunk->print_on(true, tty);

    EventContinuationThawOld e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(_cont.mirror()));
      e.commit();
    }

    DEBUG_ONLY(_mode = 3;)
    // _cont.read_rest();
    _align_size = 0;
    int num_frames = (return_barrier ? 1 : 2);
    // _frames = 0;

    log_develop_trace(jvmcont)("thaw slow");

    bool last_interpreted = false;
    if (chunk->has_mixed_frames()) {
      last_interpreted = Interpreter::contains(chunk->pc());
      log_develop_trace(jvmcont)("thaw: preempt; last_interpreted: %d", last_interpreted);
    }

    _stream = StackChunkFrameStream<true>(chunk);
    _top_unextended_sp = _stream.unextended_sp();

    frame hf = _stream.to_frame();
    log_develop_trace(jvmcont)("top_hframe before (thaw):"); if (log_develop_is_enabled(Trace, jvmcont)) hf.print_on<true>(tty);

    frame f;
    thaw(hf, f, num_frames, true);

    finish_thaw(f); // f is now the topmost thawed frame

    _cont.write();
    assert(_cont.chunk_invariant(), "");

    if (!return_barrier) JVMTI_continue_cleanup(_thread);

    assert(_cont.chunk_invariant(), "");
    _thread->set_cont_fastpath(_fastpath);

    intptr_t* sp = f.sp();

  #ifdef ASSERT
    {
      log_develop_debug(jvmcont)("Jumping to frame (thaw): [%ld]", java_tid(_thread));
      frame f(sp);
      if (log_develop_is_enabled(Debug, jvmcont)) f.print_on(tty);
      assert (f.is_interpreted_frame() || f.is_compiled_frame() || f.is_safepoint_blob_frame(), "");
    }
  #endif

    if (last_interpreted && _cont.is_preempted()) {
      assert (f.pc() == *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET), "");
      assert (Interpreter::contains(f.pc()), "");
      // InterpreterCodelet* codelet = Interpreter::codelet_containing(f.pc());
      // if (codelet != nullptr) {
      //   sp = push_interpreter_return_frame(sp);
      // }
      sp = push_interpreter_return_frame(sp);
    }

    return sp;
  }

  void thaw(const frame& hf, frame& caller, int num_frames, bool top) {
    log_develop_debug(jvmcont)("thaw num_frames: %d", num_frames);
    assert(!_cont.is_empty(), "no more frames");
    assert (num_frames > 0 && !hf.is_empty(), "");

    // Dynamically branch on frame type
    if (top && hf.is_safepoint_blob_frame()) {
      assert (Frame::is_stub(hf.cb()), "cb: %s", hf.cb()->name());
      recurse_thaw_stub_frame(hf, caller, num_frames);
    } else if (!hf.is_interpreted_frame()) {
      recurse_thaw_compiled_frame(hf, caller, num_frames);
    } else {
      recurse_thaw_interpreted_frame(hf, caller, num_frames);
    }
  }

  template<typename FKind>
  bool recurse_thaw_java_frame(frame& caller, int num_frames) {
    assert (num_frames > 0, "");

    DEBUG_ONLY(_frames++;)

    if (UNLIKELY(_barriers)) {
      InstanceStackChunkKlass::do_barriers<true>(_cont.tail(), _stream, SmallRegisterMap::instance);
    }

    int argsize = _stream.stack_argsize();

    _stream.next(SmallRegisterMap::instance);
    assert (_stream.to_frame().is_empty() == _stream.is_done(), "");

    // we never leave a compiled caller of an interpreted frame as the top frame in the chunk as it makes detecting that situation and adjusting unextended_sp tricky
    if (num_frames == 1 && !_stream.is_done() && FKind::interpreted && _stream.is_compiled()) {
      log_develop_trace(jvmcont)("thawing extra compiled frame to not leave a compiled interpreted-caller at top");
      num_frames++;
    }

    if (num_frames == 1 || _stream.is_done()) { // end recursion
      log_develop_trace(jvmcont)("is_empty: %d", _stream.is_done());
      finalize_thaw<FKind>(caller, argsize);
      return true; // bottom
    } else { // recurse
      thaw(_stream.to_frame(), caller, num_frames - 1, false);
      return false;
    }
  }

  template<typename FKind>
  void finalize_thaw(frame& entry, int argsize) {
    stackChunkOop chunk = _cont.tail();

    OrderAccess::storestore();
    if (!_stream.is_done()) {
      assert (_stream.sp() >= chunk->sp_address(), "");
      chunk->set_sp(chunk->to_offset(_stream.sp()));
      chunk->set_pc(_stream.pc());
    } else {
      chunk->set_pc(nullptr);
      chunk->set_argsize(0);
      chunk->set_sp(chunk->stack_size());
    }
    assert(_stream.is_done() == chunk->is_empty(), "_stream.is_done(): %d chunk->is_empty(): %d", _stream.is_done(), chunk->is_empty());

    int delta = _stream.unextended_sp() - _top_unextended_sp;
    log_develop_trace(jvmcont)("sub max_size: %d -- %d (unextended_sp: " INTPTR_FORMAT " orig unextended_sp: " INTPTR_FORMAT ")", delta, chunk->max_size() - delta, p2i(_stream.unextended_sp()), p2i(_top_unextended_sp));
    chunk->set_max_size(chunk->max_size() - delta);

    // assert (!_stream.is_done() || chunk->parent() != nullptr || argsize == 0, "");
    _cont.set_argsize(FKind::interpreted ? 0 : argsize);
    log_develop_trace(jvmcont)("setting entry argsize: %d (bottom interpreted: %d)", _cont.argsize(), FKind::interpreted);

    entry = new_entry_frame();

    assert (entry.sp() == _cont.entrySP(), "entry.sp: %p entrySP: %p", entry.sp(), _cont.entrySP());

  #ifdef ASSERT
    log_develop_trace(jvmcont)("Found entry:");
    print_vframe(entry);
    Frame::assert_bottom_java_frame_name(entry, ENTER_SPECIAL_SIG);
  #endif

    assert (_cont.is_entry_frame(entry), "");
  }

  inline void before_thaw_java_frame(const frame& hf, const frame& caller, bool bottom, int num_frame) {
    log_develop_trace(jvmcont)("============================= THAWING FRAME: %d", num_frame);
    // frame hf = StackChunkFrameStream(_cont.tail(), hsp).to_frame();
    if (log_develop_is_enabled(Trace, jvmcont)) hf.print_on<true>(tty);
    assert (bottom == _cont.is_entry_frame(caller), "bottom: %d is_entry_frame: %d", bottom, _cont.is_entry_frame(hf));
}

  inline void after_thaw_java_frame(const frame& f, bool bottom) {
    log_develop_trace(jvmcont)("thawed frame:");
    DEBUG_ONLY(print_vframe(f);)
  }

  template<typename FKind, bool bottom>
  inline void patch(frame& f, const frame& caller) {
    // assert (_cont.is_empty0() == _cont.is_empty(), "is_empty0: %d is_empty: %d", _cont.is_empty0(), _cont.is_empty());
    if (bottom && !_cont.is_empty()) {
      log_develop_trace(jvmcont)("Setting return address to return barrier: " INTPTR_FORMAT, p2i(StubRoutines::cont_returnBarrier()));
      FKind::patch_pc(caller, StubRoutines::cont_returnBarrier());
    } else if (bottom || should_deoptimize()) {
      FKind::patch_pc(caller, caller.raw_pc()); // this patches the return address to the deopt handler if necessary
    }
    patch_pd<FKind, bottom>(f, caller); // TODO R: reevaluate if and when this is necessary -- only bottom and interpreted caller?

    if (FKind::interpreted) {
      Interpreted::patch_sender_sp<false>(f, caller.unextended_sp());
    }

    assert (!bottom || !_cont.is_empty() || Frame::assert_bottom_java_frame_name(f, ENTER_SIG), "");
    assert (!bottom || (_cont.is_empty() != Continuation::is_cont_barrier_frame(f)), "cont.is_empty(): %d is_cont_barrier_frame(f): %d ", _cont.is_empty(), Continuation::is_cont_barrier_frame(f));
  }

  NOINLINE void recurse_thaw_interpreted_frame(const frame& hf, frame& caller, int num_frames) {
    assert (hf.is_interpreted_frame(), "");

    const bool bottom = recurse_thaw_java_frame<Interpreted>(caller, num_frames);

    DEBUG_ONLY(before_thaw_java_frame(hf, caller, bottom, num_frames);)

    frame f = new_frame<Interpreted>(hf, caller, bottom);
    intptr_t* const vsp = f.sp();
    intptr_t* const hsp = hf.unextended_sp();
    intptr_t* const frame_bottom = Interpreted::frame_bottom<false>(f);

    const int fsize = Interpreted::frame_bottom<true>(hf) - hsp;
    log_develop_trace(jvmcont)("fsize: %d", fsize);

    assert (!bottom || vsp + fsize >= _cont.entrySP() - 2, "");
    assert (!bottom || vsp + fsize <= _cont.entrySP(), "");

    assert (Interpreted::frame_bottom<false>(f) == vsp + fsize, "");

    // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
    const int locals = hf.interpreter_frame_method()->max_locals();
    copy_from_chunk<false>(Interpreted::frame_bottom<true>(hf) - locals, Interpreted::frame_bottom<false>(f) - locals, locals); // copy locals
    copy_from_chunk<false>(hsp, vsp, fsize - locals); // copy rest

    set_interpreter_frame_bottom(f, frame_bottom); // the copy overwrites the metadata
    derelativize_interpreted_frame_metadata(hf, f);
    bottom ? patch<Interpreted, true>(f, caller) : patch<Interpreted, false>(f, caller);

    DEBUG_ONLY(if (log_develop_is_enabled(Trace, jvmcont)) print_frame_layout<false>(f);)

    assert(f.is_interpreted_frame_valid(_cont.thread()), "invalid thawed frame");

    assert(Interpreted::frame_bottom<false>(f) <= Frame::frame_top(caller), "Interpreted::frame_bottom<false>(f): %p Frame::frame_top(caller): %p", Interpreted::frame_bottom<false>(f), Frame::frame_top(caller));

    _cont.dec_num_interpreted_frames();

    maybe_set_fastpath(f.sp());

    if (!bottom) {
      log_develop_trace(jvmcont)("fix thawed caller");
      InstanceStackChunkKlass::fix_thawed_frame(_cont.tail(), caller, SmallRegisterMap::instance); // can only fix caller once this frame is thawed (due to callee saved regs)
    }

    DEBUG_ONLY(after_thaw_java_frame(f, bottom);)
    caller = f;
  }

  void recurse_thaw_compiled_frame(const frame& hf, frame& caller, int num_frames) {
    assert (!hf.is_interpreted_frame(), "");

    const bool bottom = recurse_thaw_java_frame<Compiled>(caller, num_frames);

    DEBUG_ONLY(before_thaw_java_frame(hf, caller, bottom, num_frames);)

    assert (caller.sp() == caller.unextended_sp(), "");

    if ((!bottom && caller.is_interpreted_frame()) || (bottom && Interpreter::contains(_cont.tail()->pc()))) {
      log_develop_trace(jvmcont)("sub max_size align %d", ContinuationHelper::align_wiggle);
      _align_size += ContinuationHelper::align_wiggle; // we add one whether or not we've aligned because we add it in freeze_interpreted_frame
    }

    frame f = new_frame<Compiled>(hf, caller, bottom);
    intptr_t* const vsp = f.sp();
    intptr_t* const hsp = hf.unextended_sp();
    log_develop_trace(jvmcont)("vsp: " INTPTR_FORMAT, p2i(vsp));
    log_develop_trace(jvmcont)("hsp: %d ", _cont.tail()->to_offset(hsp));

    int fsize = Compiled::size(hf);
    log_develop_trace(jvmcont)("fsize: %d", fsize);
    fsize += (bottom || caller.is_interpreted_frame()) ? hf.compiled_frame_stack_argsize() : 0;
    assert (fsize <= (int)(caller.unextended_sp() - f.unextended_sp()), "%d %ld", fsize, caller.unextended_sp() - f.unextended_sp());

    intptr_t* from = hsp - ContinuationHelper::frame_metadata;
    intptr_t* to   = vsp - ContinuationHelper::frame_metadata;
    int sz = fsize + ContinuationHelper::frame_metadata;

    assert (!bottom || _cont.entrySP() - 1 <= to + sz && to + sz <= _cont.entrySP(), "");
    assert (!bottom || hf.compiled_frame_stack_argsize() != 0 || to + sz && to + sz == _cont.entrySP(), "");

    copy_from_chunk(from, to, sz);

    bottom ? patch<Compiled, true>(f, caller) : patch<Compiled, false>(f, caller);

    if (f.cb()->is_nmethod()) {
      f.cb()->as_nmethod()->run_nmethod_entry_barrier();
    }

    if (f.is_deoptimized_frame()) { // TODO PERF
      maybe_set_fastpath(f.sp());
    } else if (should_deoptimize() && (f.cb()->as_compiled_method()->is_marked_for_deoptimization() || _thread->is_interp_only_mode())) {
      log_develop_trace(jvmcont)("Deoptimizing thawed frame");
      DEBUG_ONLY(Frame::patch_pc(f, nullptr));

      f.deoptimize(_thread); // we're assuming there are no monitors; this doesn't revoke biased locks
      // ContinuationHelper::set_anchor(_thread, f); // deoptimization may need this
      // Deoptimization::deoptimize(_thread, f, &_map);
      // ContinuationHelper::clear_anchor(_thread);

      assert (f.is_deoptimized_frame() && Frame::is_deopt_return(f.raw_pc(), f), "");
      maybe_set_fastpath(f.sp());
    }

    if (!bottom) {
      log_develop_trace(jvmcont)("fix thawed caller");
      InstanceStackChunkKlass::fix_thawed_frame(_cont.tail(), caller, SmallRegisterMap::instance); // can only fix caller once this frame is thawed (due to callee saved regs)
    }

    DEBUG_ONLY(after_thaw_java_frame(f, bottom);)
    caller = f;
  }

  void recurse_thaw_stub_frame(const frame& hf, frame& caller, int num_frames) {
    log_develop_trace(jvmcont)("Found safepoint stub");

    DEBUG_ONLY(_frames++;)

    {
      RegisterMap map(nullptr, true, false, false);
      map.set_include_argument_oops(false);
      _stream.next(&map);
      assert (!_stream.is_done(), "");
      if (UNLIKELY(_barriers)) { // we're now doing this on the stub's caller
        InstanceStackChunkKlass::do_barriers<true>(_cont.tail(), _stream, &map);
      }
      assert (!_stream.is_done(), "");
    }

    recurse_thaw_compiled_frame(_stream.to_frame(), caller, num_frames);

    DEBUG_ONLY(before_thaw_java_frame(hf, caller, false, num_frames);)

    assert(Frame::is_stub(hf.cb()), "");
    assert (caller.sp() == caller.unextended_sp(), "");
    assert (!caller.is_interpreted_frame(), "");

    int fsize = StubF::size(hf);
    log_develop_trace(jvmcont)("fsize: %d", fsize);

    frame f = new_frame<StubF>(hf, caller, false);
    intptr_t* vsp = f.sp();
    intptr_t* hsp = hf.sp();
    log_develop_trace(jvmcont)("hsp: %d ", _cont.tail()->to_offset(hsp));
    log_develop_trace(jvmcont)("vsp: " INTPTR_FORMAT, p2i(vsp));

    copy_from_chunk(hsp - ContinuationHelper::frame_metadata, vsp - ContinuationHelper::frame_metadata, fsize + ContinuationHelper::frame_metadata);

    { // can only fix caller once this frame is thawed (due to callee saved regs)
      RegisterMap map(nullptr, true, false, false); // map.clear();
      map.set_include_argument_oops(false);
      f.oop_map()->update_register_map(&f, &map);
      ContinuationHelper::update_register_map_with_callee(&map, caller);
      InstanceStackChunkKlass::fix_thawed_frame(_cont.tail(), caller, &map);
    }

    DEBUG_ONLY(after_thaw_java_frame(f, false);)
    caller = f;
  }

  void finish_thaw(frame& f) {
    stackChunkOop chunk = _cont.tail();

    if (chunk->is_empty()) {
      if (_barriers) {
        _cont.set_tail(chunk->parent());
      } else {
        chunk->set_has_mixed_frames(false);
      }
      chunk->set_max_size(0);
      assert (chunk->argsize() == 0, "");
    } else {
      log_develop_trace(jvmcont)("sub max_size _align_size: %d -- %d", _align_size, chunk->max_size() - _align_size);
      chunk->set_max_size(chunk->max_size() - _align_size);
    }
    assert (chunk->is_empty() == (chunk->max_size() == 0), "chunk->is_empty: %d chunk->max_size: %d", chunk->is_empty(), chunk->max_size());

    if ((intptr_t)f.sp() % 16 != 0) {
      assert (f.is_interpreted_frame(), "");
      f.set_sp(f.sp() - 1);
    }
    push_return_frame(f);
    InstanceStackChunkKlass::fix_thawed_frame(chunk, f, SmallRegisterMap::instance); // can only fix caller after push_return_frame (due to callee saved regs)

    assert (_cont.is_empty() == _cont.last_frame().is_empty(), "cont.is_empty: %d cont.last_frame().is_empty(): %d", _cont.is_empty(), _cont.last_frame().is_empty());

    log_develop_trace(jvmcont)("thawed %d frames", _frames);

    log_develop_trace(jvmcont)("top_hframe after (thaw):");
    if (log_develop_is_enabled(Trace, jvmcont)) _cont.last_frame().template print_on<true>(tty);
  }

  void push_return_frame(frame& f) { // see generate_cont_thaw
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == (f.pc() != f.raw_pc()), "");

    log_develop_trace(jvmcont)("push_return_frame");
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

    intptr_t* sp = f.sp();
    address pc = f.raw_pc();
    *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET) = pc;
    Frame::patch_pc(f, pc); // in case we want to deopt the frame in a full transition, this is checked.
    ContinuationHelper::push_pd(f);

    assert(Frame::assert_frame_laid_out(f), "");
  }

  static inline void derelativize(intptr_t* const fp, int offset) {
    intptr_t* addr = fp + offset;
    // tty->print_cr(">>>> derelativize offset: %d fp: %p delta: %ld derel: %p", offset, fp, *addr, fp + *addr);
    *addr = (intptr_t)(fp + *addr);
  }

  static void JVMTI_continue_cleanup(JavaThread* thread) {
#if INCLUDE_JVMTI
    invalidate_JVMTI_stack(thread);
#endif // INCLUDE_JVMTI
  }
};

// returns new top sp; right below it are the pc and fp; see generate_cont_thaw
// called after preparations (stack overflow check and making room)
template<typename ConfigT>
static inline intptr_t* thaw0(JavaThread* thread, const thaw_kind kind) {
  //callgrind();
  // NoSafepointVerifier nsv;
#if CONT_JFR
  EventContinuationThaw event;
#endif

  if (kind != thaw_top) {
    log_develop_trace(jvmcont)("== RETURN BARRIER");
  }

  log_develop_trace(jvmcont)("~~~~~~~~~ thaw kind: %d", kind);
  log_develop_trace(jvmcont)("sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT,
    p2i(thread->last_continuation()->entry_sp()), p2i(thread->last_continuation()->entry_fp()), p2i(thread->last_continuation()->entry_pc()));

  assert (thread == JavaThread::current(), "");

  oop oopCont = thread->last_continuation()->cont_oop();

  assert (!jdk_internal_vm_Continuation::done(oopCont), "");

  assert (oopCont == ContinuationHelper::get_continuation(thread), "");

  assert (verify_continuation<1>(oopCont), "");
  ContMirror cont(thread, oopCont);
  log_develop_debug(jvmcont)("THAW #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  cont.read(); // read_minimal

#ifdef ASSERT
  ContinuationHelper::set_anchor_to_entry(thread, cont.entry());
  print_frames(thread);
#endif

  Thaw<ConfigT> thw(thread, cont);
  intptr_t* const sp = thw.thaw(kind);
  assert ((intptr_t)sp % 16 == 0, "");

  thread->reset_held_monitor_count();

  assert (verify_continuation<2>(cont.mirror()), "");

#ifndef PRODUCT
  intptr_t* sp0 = sp;
  address pc0 = *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET);
  if (pc0 == StubRoutines::cont_interpreter_forced_preempt_return()) {
    sp0 += ContinuationHelper::frame_metadata; // see push_interpreter_return_frame
  }
  ContinuationHelper::set_anchor(thread, sp0);
  print_frames(thread, tty); // must be done after write(), as frame walking reads fields off the Java objects.
  if (LoomVerifyAfterThaw) {
    assert(do_verify_after_thaw(thread, thw._mode, thw.barriers(), cont.tail()), "");
  }
  assert (ContinuationEntry::assert_entry_frame_laid_out(thread), "");
  ContinuationHelper::clear_anchor(thread);
#endif

  if (log_develop_is_enabled(Trace, jvmcont)) {
    log_develop_trace(jvmcont)("Jumping to frame (thaw):");
    frame f(sp);
    print_vframe(f, nullptr);
  }

#if CONT_JFR
  cont.post_jfr_event(&event, thread);
#endif

  // assert (thread->last_continuation()->argsize() == 0 || Continuation::is_return_barrier_entry(*(address*)(thread->last_continuation()->bottom_sender_sp() - SENDER_SP_RET_ADDRESS_OFFSET)), "");
  assert (verify_continuation<3>(cont.mirror()), "");
  log_develop_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());

  return sp;
}

class ThawVerifyOopsClosure: public OopClosure {
  intptr_t* _p;
public:
  ThawVerifyOopsClosure() : _p(nullptr) {}
  intptr_t* p() { return _p; }
  void reset() { _p = nullptr; }

  virtual void do_oop(oop* p) {
    oop o = *p;
    if (o == (oop)nullptr || is_good_oop(o)) return;
    _p = (intptr_t*)p;
    tty->print_cr("*** non-oop " PTR_FORMAT " found at " PTR_FORMAT, p2i(*p), p2i(p));
  }
  virtual void do_oop(narrowOop* p) {
    oop o = RawAccess<>::oop_load(p);
    if (o == (oop)nullptr || is_good_oop(o)) return;
    _p = (intptr_t*)p;
    tty->print_cr("*** (narrow) non-oop %x found at " PTR_FORMAT, (int)(*p), p2i(p));
  }
};

void do_deopt_after_thaw(JavaThread* thread) {
  int i = 0;
  StackFrameStream fst(thread, true, false);
  fst.register_map()->set_include_argument_oops(false);
  ContinuationHelper::update_register_map_with_callee(fst.register_map(), *fst.current());
  for (; !fst.is_done(); fst.next()) {
    if (fst.current()->cb()->is_compiled()) {
      CompiledMethod* cm = fst.current()->cb()->as_compiled_method();
      if (!cm->method()->is_continuation_enter_intrinsic()) {
        cm->make_deoptimized();
      }
    }
  }
}


bool do_verify_after_thaw(JavaThread* thread, int mode, bool barriers, stackChunkOop chunk) {
  assert(thread->has_last_Java_frame(), "");

  ResourceMark rm;
  ThawVerifyOopsClosure cl;
  CodeBlobToOopClosure cf(&cl, false);

  int i = 0;
  StackFrameStream fst(thread, true, false);
  fst.register_map()->set_include_argument_oops(false);
  ContinuationHelper::update_register_map_with_callee(fst.register_map(), *fst.current());
  for (; !fst.is_done() && !Continuation::is_continuation_enterSpecial(*fst.current()); fst.next()) {
    if (fst.current()->cb()->is_compiled() && fst.current()->cb()->as_compiled_method()->is_marked_for_deoptimization()) {
      tty->print_cr(">>> do_verify_after_thaw deopt");
      fst.current()->deoptimize(nullptr);
      fst.current()->print_on(tty);
    }

    fst.current()->oops_do(&cl, &cf, fst.register_map());
    if (cl.p() != nullptr) {

      frame fr = *fst.current();
      tty->print_cr("Failed for frame %d, pc: %p, sp: %p, fp: %p; mode: %d barriers: %d %d", i, fr.pc(), fr.unextended_sp(), fr.fp(), mode, barriers, chunk->requires_barriers());
      if (!fr.is_interpreted_frame()) {
        tty->print_cr("size: %d argsize: %d", NonInterpretedUnknown::size(fr), NonInterpretedUnknown::stack_argsize(fr));
      }
  #ifdef ASSERT
      VMReg reg = fst.register_map()->find_register_spilled_here(cl.p(), fst.current()->sp());
      if (reg != nullptr) tty->print_cr("Reg %s %d", reg->name(), reg->is_stack() ? (int)reg->reg2stack() : -99);
  #endif
      fr.print_on(tty);
      cl.reset();
  #ifdef ASSERT
      pfl();
  #endif
      chunk->print_on(true, tty);
      return false;
    }
  }
  return true;
}

static void print_vframe(frame f, const RegisterMap* map, outputStream* st) {
  if (st != nullptr && !log_is_enabled(Trace, jvmcont)) return;
  if (st == nullptr) st = tty;

  st->print_cr("\tfp: " INTPTR_FORMAT " real_fp: " INTPTR_FORMAT ", sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT " usp: " INTPTR_FORMAT, p2i(f.fp()), p2i(f.real_fp()), p2i(f.sp()), p2i(f.pc()), p2i(f.unextended_sp()));

  f.print_on(st);
}

JRT_LEAF(intptr_t*, Continuation::thaw(JavaThread* thread, int kind))
  // TODO: JRT_LEAF and NoHandleMark is problematic for JFR events.
  // vFrameStreamCommon allocates Handles in RegisterMap for continuations.
  // JRT_ENTRY instead?
  ResetNoHandleMark rnhm;

  intptr_t* sp = cont_thaw(thread, (thaw_kind)kind);
  // ContinuationHelper::clear_anchor(thread);
  return sp;
JRT_END

bool Continuation::is_continuation_enterSpecial(const frame& f) {
  if (f.cb() == nullptr || !f.cb()->is_compiled()) return false;
  Method* m = f.cb()->as_compiled_method()->method();
  return (m != nullptr && m->is_continuation_enter_intrinsic());
}

bool Continuation::is_continuation_entry_frame(const frame& f, const RegisterMap *map) {
  // frame f = map->in_cont() ? map->stack_chunk()->derelativize(fr) : fr;
  // tty->print_cr(">>> is_continuation_entry_frame %d", map->in_cont()); map->in_cont() ? f.print_on<true>(tty) : f.print_on<false>(tty);
  // Method* m = Frame::frame_method(f);

  // we can do this because the entry frame is never inlined
  Method* m = (map->in_cont() && f.is_interpreted_frame()) ? map->stack_chunk()->interpreter_frame_method(f)
                                                           : Frame::frame_method(f);
  return m != nullptr && m->intrinsic_id() == vmIntrinsics::_Continuation_enter;
}

// bool Continuation::is_cont_post_barrier_entry_frame(const frame& f) {
//   return is_return_barrier_entry(Frame::real_pc(f));
// }

// When walking the virtual stack, this method returns true
// iff the frame is a thawed continuation frame whose
// caller is still frozen on the h-stack.
// The continuation object can be extracted from the thread.
bool Continuation::is_cont_barrier_frame(const frame& f) {
  assert (f.is_interpreted_frame() || f.cb() != nullptr, "");
  return is_return_barrier_entry(f.is_interpreted_frame() ? Interpreted::return_pc(f) : Compiled::return_pc(f));
}

bool Continuation::is_return_barrier_entry(const address pc) {
  return pc == StubRoutines::cont_returnBarrier();
}

static inline bool is_sp_in_continuation(ContinuationEntry* cont, intptr_t* const sp) {
  // tty->print_cr(">>>> is_sp_in_continuation cont: %p sp: %p entry: %p in: %d", (oopDesc*)cont, sp, jdk_internal_vm_Continuation::entrySP(cont), jdk_internal_vm_Continuation::entrySP(cont) > sp);
  return cont->entry_sp() > sp;
}

bool Continuation::is_frame_in_continuation(ContinuationEntry* cont, const frame& f) {
  return is_sp_in_continuation(cont, f.unextended_sp());
}

static ContinuationEntry* get_continuation_entry_for_frame(JavaThread* thread, intptr_t* const sp) {
  assert (thread != nullptr, "");
  ContinuationEntry* cont = thread->last_continuation();
  while (cont != nullptr && !is_sp_in_continuation(cont, sp)) {
    cont = cont->parent();
  }
  // if (cont != nullptr) tty->print_cr(">>> get_continuation_entry_for_frame: %p entry.sp: %p oop: %p", sp, cont->entry_sp(), (oopDesc*)cont->continuation());
  return cont;
}

static oop get_continuation_for_sp(JavaThread* thread, intptr_t* const sp) {
  assert (thread != nullptr, "");
  ContinuationEntry* cont = get_continuation_entry_for_frame(thread, sp);
  return cont != nullptr ? cont->continuation() : (oop)nullptr;
}

oop Continuation::get_continuation_for_frame(JavaThread* thread, const frame& f) {
  return get_continuation_for_sp(thread, f.unextended_sp());
}

ContinuationEntry* Continuation::get_continuation_entry_for_continuation(JavaThread* thread, oop cont) {
  if (thread == nullptr || cont == (oop)nullptr) return nullptr;

  for (ContinuationEntry* entry = thread->last_continuation(); entry != nullptr; entry = entry->parent()) {
    if (cont == entry->continuation()) return entry;
  }
  return nullptr;
}

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return get_continuation_entry_for_frame(thread, f.unextended_sp()) != nullptr;
}

bool Continuation::is_mounted(JavaThread* thread, oop cont_scope) {
  return last_continuation(thread, cont_scope) != nullptr;
}

ContinuationEntry* Continuation::last_continuation(const JavaThread* thread, oop cont_scope) {
  guarantee (thread->has_last_Java_frame(), "");
  for (ContinuationEntry* entry = thread->last_continuation(); entry != nullptr; entry = entry->parent()) {
    if (cont_scope == jdk_internal_vm_Continuation::scope(entry->continuation()))
      return entry;
  }
  return nullptr;
}

void Continuation::notify_deopt(JavaThread* thread, intptr_t* sp) {
  ContinuationEntry* cont = thread->last_continuation();

  if (cont == nullptr) return;

  if (is_sp_in_continuation(cont, sp)) {
    thread->push_cont_fastpath(sp);
    return;
  }

  ContinuationEntry* prev;
  do {
    prev = cont;
    cont = cont->parent();
  } while (cont != nullptr && !is_sp_in_continuation(cont, sp));

  if (cont == nullptr) return;
  assert (is_sp_in_continuation(cont, sp), "");
  if (sp > prev->parent_cont_fastpath())
      prev->set_parent_cont_fastpath(sp);
}

bool Continuation::fix_continuation_bottom_sender(JavaThread* thread, const frame& callee, address* sender_pc, intptr_t** sender_sp) {
  if (thread != nullptr && is_return_barrier_entry(*sender_pc)) {
    ContinuationEntry* cont = get_continuation_entry_for_frame(thread, callee.is_interpreted_frame() ? callee.interpreter_frame_last_sp() : callee.unextended_sp());
    assert (cont != nullptr, "callee.unextended_sp(): " INTPTR_FORMAT, p2i(callee.unextended_sp()));

    log_develop_debug(jvmcont)("fix_continuation_bottom_sender: [%ld] [%ld]", java_tid(thread), (long) thread->osthread()->thread_id());
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_pc: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_pc), p2i(cont->entry_pc()));
    log_develop_trace(jvmcont)("fix_continuation_bottom_sender: sender_sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_sp), p2i(cont->entry_sp()));
    // log_develop_trace(jvmcont)("fix_continuation_bottom_sender callee:"); if (log_develop_is_enabled(Debug, jvmcont)) callee.print_value_on(tty, thread);

    *sender_pc = cont->entry_pc();
    *sender_sp = cont->entry_sp();
    // We DO NOT want to fix FP. It could contain an oop that has changed on the stack, and its location should be OK anyway

    return true;
  }
  return false;
}

address Continuation::get_top_return_pc_post_barrier(JavaThread* thread, address pc) {
  ContinuationEntry* cont;
  if (thread != nullptr && is_return_barrier_entry(pc) && (cont = thread->last_continuation()) != nullptr) {
    pc = cont->entry_pc();
  }
  return pc;
}

bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
  if (cont_scope == nullptr || !is_continuation_entry_frame(f, map))
    return false;

  oop cont = get_continuation_for_sp(map->thread(), f.sp());
  if (cont == nullptr)
    return false;

  oop sc = continuation_scope(cont);
  assert(sc != nullptr, "");
  return sc == cont_scope;
}

frame Continuation::continuation_parent_frame(RegisterMap* map) {
  assert (map->in_cont(), "");
  ContMirror cont(map);
  assert (map->thread() != nullptr || !cont.is_mounted(), "map->thread() == nullptr: %d cont.is_mounted(): %d", map->thread() == nullptr, cont.is_mounted());

  log_develop_trace(jvmcont)("continuation_parent_frame");
  if (map->update_map()) {
    ContinuationHelper::update_register_map_for_entry_frame(cont, map);
  }

  if (!cont.is_mounted()) { // When we're walking an unmounted continuation and reached the end
    oop parent = jdk_internal_vm_Continuation::parent(cont.mirror());
    stackChunkOop chunk = parent != nullptr ? ContMirror(parent).last_nonempty_chunk() : nullptr;
    if (chunk != nullptr) {
      return chunk->top_frame(map);
    }

    // tty->print_cr("continuation_parent_frame: no more");
    map->set_stack_chunk(nullptr);
    return frame();
  }

  map->set_stack_chunk(nullptr);

  frame sender(cont.entrySP(), cont.entryFP(), cont.entryPC());

  // tty->print_cr("continuation_parent_frame");
  // print_vframe(sender, map, nullptr);

  return sender;
}

static frame continuation_top_frame(oop contOop, RegisterMap* map) {
  stackChunkOop chunk = ContMirror(contOop).last_nonempty_chunk();
  map->set_stack_chunk(chunk);
  return chunk != nullptr ? chunk->top_frame(map) : frame();
}

frame Continuation::top_frame(const frame& callee, RegisterMap* map) {
  assert (map != nullptr, "");
  return continuation_top_frame(get_continuation_for_sp(map->thread(), callee.sp()), map);
}

frame Continuation::last_frame(oop continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  return continuation_top_frame(continuation, map);
}

bool Continuation::has_last_Java_frame(oop continuation) {
  return !ContMirror(continuation).is_empty();
}

stackChunkOop Continuation::last_nonempty_chunk(oop continuation) {
  return ContMirror(continuation).last_nonempty_chunk();
}

javaVFrame* Continuation::last_java_vframe(Handle continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  // tty->print_cr(">>> Continuation::last_java_vframe");
  if (!ContMirror(continuation()).is_empty()) {
    frame f = last_frame(continuation(), map);
    for (vframe* vf = vframe::new_vframe(&f, map, nullptr); vf; vf = vf->sender()) {
      if (vf->is_java_frame()) return javaVFrame::cast(vf);
    }
  }
  return nullptr;
}

bool Continuation::is_in_usable_stack(address addr, const RegisterMap* map) {
  ContMirror cont(map);
  stackChunkOop chunk = cont.find_chunk_by_address(addr);
  return chunk != nullptr ? chunk->is_usable_in_chunk(addr) : false;
}

stackChunkOop Continuation::continuation_parent_chunk(stackChunkOop chunk) {
  assert(chunk->cont() != nullptr, "");
  oop cont_parent = jdk_internal_vm_Continuation::parent(chunk->cont());
  return cont_parent != nullptr ? Continuation::last_nonempty_chunk(cont_parent) : nullptr;
}

oop Continuation::continuation_scope(oop cont) {
  return cont != nullptr ? jdk_internal_vm_Continuation::scope(cont) : nullptr;
}

bool Continuation::pin(JavaThread* current) {
  ContinuationEntry* ce = current->last_continuation();
  if (ce == nullptr)
    return true;

  oop cont = ce->cont_oop();
  assert (cont != nullptr, "");
  assert (cont == ContinuationHelper::get_continuation(current), "");

  jshort value = jdk_internal_vm_Continuation::critical_section(cont);
  if (value < max_jshort) {
    jdk_internal_vm_Continuation::set_critical_section(cont, value + 1);
    return true;
  }
  return false;
}

bool Continuation::unpin(JavaThread* current) {
  ContinuationEntry* ce = current->last_continuation();
  if (ce == nullptr)
    return true;

  oop cont = ce->cont_oop();
  assert (cont != nullptr, "");
  assert (cont == ContinuationHelper::get_continuation(current), "");
  
  jshort value = jdk_internal_vm_Continuation::critical_section(cont);
  if (value > 0) {
    jdk_internal_vm_Continuation::set_critical_section(cont, value - 1);
    return true;
  }
  return false;
}

///// Allocation

inline void ContMirror::post_safepoint(Handle conth) {
  _cont = conth(); // reload oop
  if (_tail != (oop)nullptr) {
    _tail = (stackChunkOop)jdk_internal_vm_Continuation::tail(_cont);
  }
}


/* try to allocate a chunk from the tlab, if it doesn't work allocate one using the allocate
 * method. In the later case we might have done a safepoint and need to reload our oops */
stackChunkOop ContMirror::allocate_stack_chunk(int stack_size, bool is_preempt) {
  InstanceStackChunkKlass* klass = InstanceStackChunkKlass::cast(vmClasses::StackChunk_klass());
  int size_in_words = klass->instance_size(stack_size);

  assert(is_preempt || _thread == JavaThread::current(), "should be current");
  JavaThread* current = is_preempt ? JavaThread::current() : _thread;

  StackChunkAllocator allocator(klass, size_in_words, stack_size, current);
  HeapWord* start = current->tlab().allocate(size_in_words);
  if (start != nullptr) {
    return (stackChunkOop)allocator.initialize(start);
  }

  //HandleMark hm(current);
  Handle conth(current, _cont);
  stackChunkOop result = (stackChunkOop)allocator.allocate();
  post_safepoint(conth);
  return result;
}

void Continuation::emit_chunk_iterate_event(oop chunk, int num_frames, int num_oops) {
  EventContinuationIterateOops e;
  if (e.should_commit()) {
    e.set_id(cast_from_oop<u8>(chunk));
    e.set_safepoint(SafepointSynchronize::is_at_safepoint());
    e.set_numFrames((u2)num_frames);
    e.set_numOops((u2)num_oops);
    e.commit();
  }
}

JVM_ENTRY(jint, CONT_isPinned0(JNIEnv* env, jobject cont_scope)) {
  JavaThread* thread = JavaThread::thread_from_jni_environment(env);
  return is_pinned0(thread, JNIHandles::resolve(cont_scope), false);
}
JVM_END

JVM_ENTRY(jint, CONT_TryForceYield0(JNIEnv* env, jobject jcont, jobject jthread)) {
  JavaThread* current = JavaThread::thread_from_jni_environment(env);
  assert(current == JavaThread::current(), "should be");
  jint result = -1; // no continuation (should have enum)

  oop thread_oop = JNIHandles::resolve(jthread);
  if (thread_oop != nullptr) {
    JavaThread* target = java_lang_Thread::thread(thread_oop);
    assert(target != current, "should be different threads");
    // Suspend the target thread and freeze it.
    if (target->block_suspend(current)) {
      oop oopCont = JNIHandles::resolve_non_null(jcont);
      result = Continuation::try_force_yield(target, oopCont);
      target->continue_resume(current);
    }
  }
  return result;
}
JVM_END


void Continuation::init() {
}

void Continuation::set_cont_fastpath_thread_state(JavaThread* thread) {
  assert (thread != nullptr, "");
  bool fast = !thread->is_interp_only_mode();
  thread->set_cont_fastpath_thread_state(fast);
}

#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod CONT_methods[] = {
    {CC"tryForceYield0",   CC"(Ljava/lang/Thread;)I",            FN_PTR(CONT_TryForceYield0)},
    {CC"isPinned0",        CC"(Ljdk/internal/vm/ContinuationScope;)I", FN_PTR(CONT_isPinned0)},
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls) {
    Thread* thread = Thread::current();
    assert(thread->is_Java_thread(), "");
    ThreadToNativeFromVM trans((JavaThread*)thread);
    int status = env->RegisterNatives(cls, CONT_methods, sizeof(CONT_methods)/sizeof(JNINativeMethod));
    guarantee(status == JNI_OK && !env->ExceptionOccurred(), "register jdk.internal.vm.Continuation natives");
}

#include CPU_HEADER_INLINE(continuation)

template <bool compressed_oops, typename BarrierSetT>
class Config {
public:
  typedef Config<compressed_oops, BarrierSetT> SelfT;
  typedef typename Conditional<compressed_oops, narrowOop, oop>::type OopT;

  static const bool _compressed_oops = compressed_oops;
  static const bool _concurrent_gc = BarrierSetT::is_concurrent_gc();
  // static const bool _post_barrier = post_barrier;
  // static const bool allow_stubs = gen_stubs && post_barrier && compressed_oops;
  // static const bool has_young = use_chunks;
  // static const bool full_stack = full;

  static int freeze(JavaThread* thread, intptr_t* sp, bool preempt) {
    return freeze0<SelfT>(thread, sp, preempt);
  }

  static intptr_t* thaw(JavaThread* thread, thaw_kind kind) {
    return thaw0<SelfT>(thread, kind);
  }

  static bool requires_barriers(oop obj) {
    return BarrierSetT::requires_barriers(obj);
  }

  static void print() {
    tty->print_cr(">>> Config compressed_oops: %d concurrent_gc: %d", _compressed_oops, _concurrent_gc);
    // tty->print_cr(">>> Config UseAVX: %ld UseUnalignedLoadStores: %d Enhanced REP MOVSB: %d Fast Short REP MOVSB: %d rdtscp: %d rdpid: %d", UseAVX, UseUnalignedLoadStores, VM_Version::supports_erms(), VM_Version::supports_fsrm(), VM_Version::supports_rdtscp(), VM_Version::supports_rdpid());
    // tty->print_cr(">>> Config avx512bw (not legacy bw): %d avx512dq (not legacy dq): %d avx512vl (not legacy vl): %d avx512vlbw (not legacy vlbw): %d", VM_Version::supports_avx512bw(), VM_Version::supports_avx512dq(), VM_Version::supports_avx512vl(), VM_Version::supports_avx512vlbw());
  }
};

class ConfigResolve {
public:
  static void resolve() { resolve_compressed(); }

  static void resolve_compressed() {
    UseCompressedOops ? resolve_gc<true>()
                      : resolve_gc<false>();
  }

  template <bool use_compressed>
  static void resolve_gc() {
    BarrierSet* bs = BarrierSet::barrier_set();
    assert(bs != NULL, "freeze/thaw invoked before BarrierSet is set");
    switch (bs->kind()) {
#define BARRIER_SET_RESOLVE_BARRIER_CLOSURE(bs_name)                    \
      case BarrierSet::bs_name: {                                       \
        resolve<use_compressed, typename BarrierSet::GetType<BarrierSet::bs_name>::type>(); \
      }                                                                 \
        break;
      FOR_EACH_CONCRETE_BARRIER_SET_DO(BARRIER_SET_RESOLVE_BARRIER_CLOSURE)
#undef BARRIER_SET_RESOLVE_BARRIER_CLOSURE

    default:
      fatal("BarrierSet resolving not implemented");
    };
  }

  template <bool use_compressed, typename BarrierSetT>
  static void resolve() {
    typedef Config<use_compressed, BarrierSetT> SelectedConfigT;
    // SelectedConfigT::print();

    cont_freeze = SelectedConfigT::freeze;
    cont_thaw   = SelectedConfigT::thaw;
  }
};

void Continuations::init() {
  ConfigResolve::resolve();
  InstanceStackChunkKlass::resolve_memcpy_functions();
  Continuation::init();
}

void Continuations::print_statistics() {
  //tty->print_cr("Continuations hit/miss %ld / %ld", _exploded_hit, _exploded_miss);
  //tty->print_cr("Continuations nmethod hit/miss %ld / %ld", _nmethod_hit, _nmethod_miss);
}

///// DEBUGGING

#ifndef PRODUCT
void Continuation::describe(FrameValues &values) {
  JavaThread* thread = JavaThread::active();
  if (thread != nullptr) {
    for (ContinuationEntry* cont = thread->last_continuation(); cont != nullptr; cont = cont->parent()) {
      intptr_t* bottom = cont->entry_sp();
      if (bottom != nullptr)
        values.describe(-1, bottom, "continuation entry");
    }
  }
}

#ifdef ASSERT
bool Continuation::debug_is_stack_chunk(Klass* k) {
  return k->is_instance_klass() && InstanceKlass::cast(k)->is_stack_chunk_instance_klass();
}

bool Continuation::debug_is_stack_chunk(oop obj) {
  return obj != (oop)nullptr && obj->is_stackChunk();
}

bool Continuation::debug_is_continuation(Klass* klass) {
  return klass->is_subtype_of(vmClasses::Continuation_klass());
}

bool Continuation::debug_is_continuation(oop obj) {
  return obj->is_a(vmClasses::Continuation_klass());
}

bool Continuation::debug_is_continuation_run_frame(const frame& f) {
  bool is_continuation_run = false;
  if (f.is_compiled_frame()) {
    HandleMark hm(Thread::current());
    ResourceMark rm;
    Method* m = f.cb()->as_compiled_method()->scope_desc_at(f.pc())->method();
    if (m != nullptr) {
      char buf[50];
      if (0 == strcmp(ENTER_SPECIAL_SIG, m->name_and_sig_as_C_string(buf, 50))) {
        is_continuation_run = true;
      }
    }
  }
  return is_continuation_run;
}


NOINLINE bool Continuation::debug_verify_continuation(oop contOop) {
  DEBUG_ONLY(if (!VerifyContinuations) return true;)
  assert (contOop != (oop)nullptr, "");
  assert (oopDesc::is_oop(contOop), "");
  ContMirror cont(contOop);
  cont.read();

  assert (oopDesc::is_oop_or_null(cont.tail()), "");
  assert (cont.chunk_invariant(), "");

  bool nonempty_chunk = false;
  size_t max_size = 0;
  int num_chunks = 0;
  int num_frames = 0;
  int num_interpreted_frames = 0;
  int num_oops = 0;
  // tty->print_cr(">>> debug_verify_continuation traversing chunks");
  for (stackChunkOop chunk = cont.tail(); chunk != nullptr; chunk = chunk->parent()) {
    log_develop_trace(jvmcont)("debug_verify_continuation chunk %d", num_chunks);
    chunk->verify(&max_size, &num_oops, &num_frames, &num_interpreted_frames);
    if (!chunk->is_empty()) nonempty_chunk = true;
    num_chunks++;
  }

  // assert (cont.max_size() >= 0, ""); // size_t can't be negative...
  const bool is_empty = cont.is_empty();
  assert (!nonempty_chunk || !is_empty, "");
  assert (is_empty == (!nonempty_chunk && cont.last_frame().is_empty()), "");
  // assert (num_interpreted_frames == cont.num_interpreted_frames(), "interpreted_frames: %d cont.num_interpreted_frames(): %d", num_interpreted_frames, cont.num_interpreted_frames());

  return true;
}

void Continuation::debug_print_continuation(oop contOop, outputStream* st) {
  if (st == nullptr) st = tty;

  ContMirror cont(contOop);

  st->print_cr("CONTINUATION: 0x%lx done: %d", contOop->identity_hash(), jdk_internal_vm_Continuation::done(contOop));
  st->print_cr("CHUNKS:");
  for (stackChunkOop chunk = cont.tail(); chunk != (oop)nullptr; chunk = chunk->parent()) {
    st->print("* ");
    chunk->print_on(true, tty);
  }

  // st->print_cr("frames: %d interpreted frames: %d oops: %d", cont.num_frames(), cont.num_interpreted_frames(), cont.num_oops());
}
#endif // ASSERT

static jlong java_tid(JavaThread* thread) {
  return java_lang_Thread::thread_id(thread->threadObj());
}

#ifndef PRODUCT
template <bool relative>
static void print_frame_layout(const frame& f, outputStream* st) {
  ResourceMark rm;
  FrameValues values;
  assert (f.get_cb() != nullptr, "");
  RegisterMap map(relative ? (JavaThread*)nullptr : JavaThread::current(), true, false, false);
  map.set_include_argument_oops(false);
  map.set_skip_missing(true);
  frame::update_map_with_saved_link(&map, Frame::callee_link_address(f));
  const_cast<frame&>(f).describe<relative>(values, 0, &map);
  values.print_on((JavaThread*)nullptr, st);
}
#endif

static void print_frames(JavaThread* thread, outputStream* st) {
  if (st != nullptr && !log_develop_is_enabled(Trace, jvmcont)) return;
  if (st == nullptr) st = tty;

  if (!thread->has_last_Java_frame()) st->print_cr("NO ANCHOR!");

  st->print_cr("------- frames ---------");
  RegisterMap map(thread, true, true, false);
  map.set_include_argument_oops(false);
#ifndef PRODUCT
  map.set_skip_missing(true);
  ResetNoHandleMark rnhm;
  ResourceMark rm;
  HandleMark hm(Thread::current());
  FrameValues values;
#endif

  int i = 0;
  for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) {
#ifndef PRODUCT
    // print_vframe(f, &map, st);
    f.describe(values, i, &map);
#else
    // f.print_on(st);
    // tty->print_cr("===");
    print_vframe(f, &map, st);
#endif
    i++;
  }
#ifndef PRODUCT
  values.print(thread);
#endif
  st->print_cr("======= end frames =========");
}

// template<int x>
// NOINLINE static void walk_frames(JavaThread* thread) {
//   RegisterMap map(thread, false, false, false);
//   for (frame f = thread->last_frame(); !f.is_first_frame(); f = f.sender(&map));
// }

#endif

int ContinuationEntry::return_pc_offset = 0;
nmethod* ContinuationEntry::continuation_enter = nullptr;
address ContinuationEntry::return_pc = nullptr;

void ContinuationEntry::set_enter_nmethod(nmethod* nm) {
  assert (return_pc_offset != 0, "");
  continuation_enter = nm;
  return_pc = nm->code_begin() + return_pc_offset;
}

ContinuationEntry* ContinuationEntry::from_frame(const frame& f) {
  assert (Continuation::is_continuation_enterSpecial(f), "");
  return (ContinuationEntry*)f.unextended_sp();
}

#ifdef ASSERT
bool ContinuationEntry::assert_entry_frame_laid_out(JavaThread* thread) {
  assert (thread->has_last_Java_frame(), "Wrong place to use this assertion");

  ContinuationEntry* cont = Continuation::get_continuation_entry_for_continuation(thread, ContinuationHelper::get_continuation(thread));
  assert (cont != nullptr, "");

  intptr_t* unextended_sp = cont->entry_sp();
  intptr_t* sp;
  if (cont->argsize() > 0) {
    sp = cont->bottom_sender_sp();
  } else {
    sp = unextended_sp;
    bool interpreted_bottom = false;
    RegisterMap map(thread, false, false, false);
    frame f;
    for (f = thread->last_frame(); !f.is_first_frame() && f.sp() <= unextended_sp && !Continuation::is_continuation_enterSpecial(f); f = f.sender(&map)) {
      if (Continuation::is_continuation_enterSpecial(f))
        break;
      interpreted_bottom = f.is_interpreted_frame();
      if (!(f.sp() != nullptr && f.sp() <= cont->bottom_sender_sp())) {
        tty->print_cr("oops");
        f.print_on(tty);
      }
    }
    assert (Continuation::is_continuation_enterSpecial(f), "");
    sp = interpreted_bottom ? f.sp() : cont->bottom_sender_sp();
  }

  assert (sp != nullptr && sp <= cont->entry_sp(), "sp: " INTPTR_FORMAT " entry_sp: " INTPTR_FORMAT " bottom_sender_sp: " INTPTR_FORMAT, p2i(sp), p2i(cont->entry_sp()), p2i(cont->bottom_sender_sp()));
  address pc = *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET);

  if (pc != StubRoutines::cont_returnBarrier()) {
    CodeBlob* cb = pc != nullptr ? CodeCache::find_blob(pc) : nullptr;

    if (cb == nullptr || !cb->is_compiled() || !cb->as_compiled_method()->method()->is_continuation_enter_intrinsic()) {
      tty->print_cr(">>>> entry unextended_sp: %p sp: %p", unextended_sp, sp);
      if (cb == nullptr) tty->print_cr("NULL"); else cb->print_on(tty);
      os::print_location(tty, (intptr_t)pc);
     }

    assert (cb != nullptr, "");
    assert (cb->is_compiled(), "");
    assert (cb->as_compiled_method()->method()->is_continuation_enter_intrinsic(), "");
  }

  // intptr_t* fp = *(intptr_t**)(sp - frame::sender_sp_offset);
  // assert (cont->entry_fp() == fp, "entry_fp: " INTPTR_FORMAT " actual: " INTPTR_FORMAT, p2i(cont->entry_sp()), p2i(fp));

  return true;
}
#endif

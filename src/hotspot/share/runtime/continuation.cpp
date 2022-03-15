/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "runtime/arguments.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/frame_helpers.inline.hpp"
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
#include "runtime/vframe.inline.hpp"
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/debug.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

#define CONT_JFR false
#if CONT_JFR
  #define CONT_JFR_ONLY(code) code
#else
  #define CONT_JFR_ONLY(code)
#endif

static const bool TEST_THAW_ONE_CHUNK_FRAME = false; // force thawing frames one-at-a-time for testing

/*
 * This file contains the implementation of continuation freezing (yield) and thawing (run).
 *
 * This code is very latency-critical and very hot. An ordinary and well-behaved server application
 * would likely call these operations many thousands of times per second second, on every core.
 *
 * Freeze might be called every time the application performs any I/O operation, every time it
 * acquires a j.u.c. lock, every time it takes a message from a queue, and thaw can be called
 * multiple times in each of those cases, as it is called by the return barrier, which may be
 * invoked on method return.
 *
 * The amortized budget for each of those two operations is ~100-150ns. That is why, for
 * example, every effort is made to avoid Java-VM transitions as much as possible.
 *
 * On the fast path, all frames are known to be compiled, and the chunk requires no barriers
 * and so frames simply copied, and the bottom-most one is patched.
 * On the slow path, internal pointers in interpreted frames are de/relativized to/from offsets
 * and absolute pointers, and barriers invoked.
 */

/************************************************

Thread-stack layout on freeze/thaw.
See corresponding stack-chunk layout in instanceStackChunkKlass.hpp

            +----------------------------+
            |      .                     |
            |      .                     |
            |      .                     |
            |   carrier frames           |
            |                            |
            |----------------------------|
            |                            |
            |    Continuation.run        |
            |                            |
            |============================|
            |    enterSpecial frame      |
            |  pc                        |
            |  rbp                       |
            |  -----                     |
        ^   |  int argsize               | = ContinuationEntry
        |   |  oopDesc* cont             |
        |   |  oopDesc* chunk            |
        |   |  ContinuationEntry* parent |
        |   |  ...                       |
        |   |============================| <------ JavaThread::_cont_entry
        |   |  ? alignment word ?        |
        |   |----------------------------| <--\
        |   |                            |    |
        |   |  ? caller stack args ?     |    |   argsize (might not be 2-word aligned) words
Address |   |                            |    |   Caller is still in the chunk.
        |   |----------------------------|    |
        |   |  pc (? return barrier ?)   |    |  This pc contains the return barrier when the bottom-most frame
        |   |  rbp                       |    |  isn't the last one in the continuation.
        |   |============================|    |
        |   |                            |    |
        |   |    frame                   |    |
        |   |                            |    |
            +----------------------------|     \__ Continuation frames to be frozen/thawed
            |                            |     /
            |    frame                   |    |
            |                            |    |
            |----------------------------|    |
            |                            |    |
            |    frame                   |    |
            |                            |    |
            |----------------------------| <--/
            |                            |
            |    doYield/safepoint stub  | When preempting forcefully, we could have a safepoint stub
            |                            | instead of a doYield stub
            |============================|
            |                            |
            |  Native freeze/thaw frames |
            |      .                     |
            |      .                     |
            |      .                     |
            +----------------------------+

************************************************/

// TODO: See AbstractAssembler::generate_stack_overflow_check,
// Compile::bang_size_in_bytes(), m->as_SafePoint()->jvms()->interpreter_frame_size()
// when we stack-bang, we need to update a thread field with the lowest (farthest) bang point.

// Data invariants are defined by Continuation::debug_verify_continuation and Continuation::debug_verify_stack_chunk

// debugging functions
#ifdef ASSERT
extern "C" bool dbg_is_safe(const void* p, intptr_t errvalue); // address p is readable and *(intptr_t*)p != errvalue

template<int x> NOINLINE static bool verify_continuation(oop cont) { return Continuation::debug_verify_continuation(cont); }
#define VERIFY_CONTINUATION(cont) verify_continuation<__LINE__>((cont))
template<int x> NOINLINE static bool verify_stack_chunk(oop chunk) { return InstanceStackChunkKlass::verify(chunk); }
#define VERIFY_STACK_CHUNK(chunk) verify_stack_chunk<__LINE__>((chunk))

static void do_deopt_after_thaw(JavaThread* thread);
static bool do_verify_after_thaw(JavaThread* thread, int mode, bool barriers, stackChunkOop chunk, outputStream* st);
#endif

#ifndef PRODUCT
static void print_frame_layout(const frame& f, outputStream* st = tty);
static void print_frames(JavaThread* thread, outputStream* st = tty);
static jlong java_tid(JavaThread* thread);
#endif

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

static freeze_result is_pinned0(JavaThread* thread, oop cont_scope, bool safepoint);
static bool is_safe_to_preempt(JavaThread* thread);
template<typename ConfigT> static inline int freeze0(JavaThread* current, intptr_t* const sp);

enum thaw_kind {
  thaw_top = 0,
  thaw_return_barrier = 1,
  thaw_exception = 2,
};

static inline int prepare_thaw0(JavaThread* thread, bool return_barrier);
template<typename ConfigT> static inline intptr_t* thaw0(JavaThread* thread, const thaw_kind kind);

extern "C" jint JNICALL CONT_isPinned0(JNIEnv* env, jobject cont_scope);
extern "C" jint JNICALL CONT_TryForceYield0(JNIEnv* env, jobject jcont, jobject jthread);

enum class oop_kind { NARROW, WIDE };
template <oop_kind oops, typename BarrierSetT>
class Config {
public:
  typedef Config<oops, BarrierSetT> SelfT;
  typedef typename Conditional<oops == oop_kind::NARROW, narrowOop, oop>::type OopT;

  static int freeze(JavaThread* thread, intptr_t* const sp) {
    return freeze0<SelfT, false>(thread, sp);
  }

  __COLD
  static int freeze_preempt(JavaThread* thread, intptr_t* const sp) {
    return freeze0<SelfT, true>(thread, sp);
  }

  static intptr_t* thaw(JavaThread* thread, thaw_kind kind) {
    return thaw0<SelfT>(thread, kind);
  }

  static bool requires_barriers(stackChunkOop obj) {
    return BarrierSetT::requires_barriers(obj);
  }
};

class SmallRegisterMap;

class ContinuationHelper {
public:
  static const int frame_metadata; // size, in words, of frame metadata (e.g. pc and link)
  static const int align_wiggle; // size, in words, of maximum shift in frame position due to alignment

  static oop get_continuation(JavaThread* thread);
  static bool stack_overflow_check(JavaThread* thread, int size, address sp);

  static inline void clear_anchor(JavaThread* thread);
  static void set_anchor(JavaThread* thread, intptr_t* sp);
  static void set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp);
  static void set_anchor_to_entry(JavaThread* thread, ContinuationEntry* cont);
  static void set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* cont);

  template<typename FKind, typename RegisterMapT> static void update_register_map(const frame& f, RegisterMapT* map);
  template<typename RegisterMapT> static void update_register_map_with_callee(const frame& f, RegisterMapT* map);

  static inline void push_pd(const frame& f);

  static inline void maybe_flush_stack_processing(JavaThread* thread, const ContinuationEntry* entry);
  static inline void maybe_flush_stack_processing(JavaThread* thread, intptr_t* sp);
  static NOINLINE void flush_stack_processing(JavaThread* thread, intptr_t* sp);

  static inline int frame_align_words(int size);
  static inline intptr_t* frame_align_pointer(intptr_t* sp);
};

oop ContinuationHelper::get_continuation(JavaThread* thread) {
  assert (thread != nullptr, "");
  assert (thread->threadObj() != nullptr, "");
  return java_lang_Thread::continuation(thread->threadObj());
}

bool ContinuationHelper::stack_overflow_check(JavaThread* thread, int size, address sp) {
  const int page_size = os::vm_page_size();
  if (size > page_size) {
    if (sp - size < thread->stack_overflow_state()->stack_overflow_limit()) {
      return false;
    }
  }
  return true;
}

inline void ContinuationHelper::clear_anchor(JavaThread* thread) {
  thread->frame_anchor()->clear();
}

void ContinuationHelper::set_anchor(JavaThread* thread, intptr_t* sp) {
  address pc = *(address*)(sp - frame::sender_sp_ret_address_offset());
  assert (pc != nullptr, "");

  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(sp);
  anchor->set_last_Java_pc(pc);
  set_anchor_pd(anchor, sp);

  assert (thread->has_last_Java_frame(), "");
  assert(thread->last_frame().cb() != nullptr, "");
}

void ContinuationHelper::set_anchor_to_entry(JavaThread* thread, ContinuationEntry* cont) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(cont->entry_sp());
  anchor->set_last_Java_pc(cont->entry_pc());
  set_anchor_to_entry_pd(anchor, cont);

  assert (thread->has_last_Java_frame(), "");
  assert(thread->last_frame().cb() != nullptr, "");
}

inline void ContinuationHelper::maybe_flush_stack_processing(JavaThread* thread, const ContinuationEntry* entry) {
  maybe_flush_stack_processing(thread, (intptr_t*)((uintptr_t)entry->entry_sp() + ContinuationEntry::size()));
}

inline void ContinuationHelper::maybe_flush_stack_processing(JavaThread* thread, intptr_t* sp) {
  StackWatermark* sw;
  uintptr_t watermark;
  if ((sw = StackWatermarkSet::get(thread, StackWatermarkKind::gc)) != nullptr
        && (watermark = sw->watermark()) != 0
        && watermark <= (uintptr_t)sp) {
    flush_stack_processing(thread, sp);
  }
}

NOINLINE void ContinuationHelper::flush_stack_processing(JavaThread* thread, intptr_t* sp) {
  log_develop_trace(jvmcont)("flush_stack_processing");
  for (StackFrameStream fst(thread, true, true); fst.current()->sp() <= sp; fst.next())
    ;
}

/////////////////////////////////////////////////////////////////////

// Mirrors the Java continuation objects.
// This object is created when we begin am operation for a continuation, and is destroyed when the operation completes.
// Contents are read from the Java object at the entry points of this module, and written at exist or calls into Java
class ContMirror {
private:
  JavaThread* const _thread;   // Thread being frozen/thawed
  ContinuationEntry* _entry;
  oop _cont;
  stackChunkOop _tail;

#if CONT_JFR // Profiling data for the JFR event
  short _e_size;
  short _e_num_interpreted_frames;
#endif

private:
  ContMirror(const ContMirror& cont); // no copy constructor

public:
  ContMirror(JavaThread* thread, oop cont);
  ContMirror(oop cont);
  ContMirror(const RegisterMap* map);

  inline void read();
  inline void write();
  inline void post_safepoint(Handle conth);

  JavaThread* thread() const         { return _thread; }
  oop mirror()                       { return _cont; }
  stackChunkOop tail() const         { return _tail; }
  void set_tail(stackChunkOop chunk) { _tail = chunk; }

  oop parent() { return jdk_internal_vm_Continuation::parent(_cont); }
  bool is_preempted() { return jdk_internal_vm_Continuation::is_preempted(_cont); }
  void set_preempted(bool value) { jdk_internal_vm_Continuation::set_preempted(_cont, value); }
  NOT_PRODUCT(intptr_t hash() { return Thread::current()->is_Java_thread() ? _cont->identity_hash() : -1; })

  ContinuationEntry* entry() const { return _entry; }
  bool is_mounted()   const { return _entry != nullptr; }
  intptr_t* entrySP() const { return _entry->entry_sp(); }
  intptr_t* entryFP() const { return _entry->entry_fp(); }
  address   entryPC() const { return _entry->entry_pc(); }
  int argsize()       const { assert (_entry->argsize() >= 0, ""); return _entry->argsize(); }
  void set_argsize(int value) { _entry->set_argsize(value); }

  bool is_empty() const { return last_nonempty_chunk() == nullptr; }
  const frame last_frame();

  stackChunkOop last_nonempty_chunk() const { return nonempty_chunk(_tail); }
  inline stackChunkOop nonempty_chunk(stackChunkOop chunk) const;
  stackChunkOop find_chunk_by_address(void* p) const;

#if CONT_JFR
  inline void record_interpreted_frame() { _e_num_interpreted_frames++; }
  inline void record_size_copied(int size) { _e_size += size << LogBytesPerWord; }
  template<typename Event> void post_jfr_event(Event *e, JavaThread* jt);
#endif

#ifdef ASSERT
  inline bool is_entry_frame(const frame& f);
  bool chunk_invariant(outputStream* st);
#endif
};

ContMirror::ContMirror(JavaThread* thread, oop cont)
 : _thread(thread), _entry(thread->last_continuation()), _cont(cont)
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));
  assert (_cont == _entry->cont_oop(), "mirror: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: "
          INTPTR_FORMAT, p2i((oopDesc*)_cont), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  read();
}

ContMirror::ContMirror(oop cont)
 : _thread(nullptr), _entry(nullptr), _cont(cont)
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));
  read();
}

ContMirror::ContMirror(const RegisterMap* map)
 : _thread(map->thread()),
   _entry(Continuation::get_continuation_entry_for_continuation(_thread, map->stack_chunk()->cont())),
   _cont(map->stack_chunk()->cont())
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(_cont != nullptr && oopDesc::is_oop_or_null(_cont), "Invalid cont: " INTPTR_FORMAT, p2i((void*)_cont));
  assert (_entry == nullptr || _cont == _entry->cont_oop(),
    "mirror: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: " INTPTR_FORMAT,
    p2i( (oopDesc*)_cont), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  read();
}

inline void ContMirror::read() {
  _tail  = (stackChunkOop)jdk_internal_vm_Continuation::tail(_cont);
}

inline void ContMirror::write() {
  jdk_internal_vm_Continuation::set_tail(_cont, _tail);
}

inline void ContMirror::post_safepoint(Handle conth) {
  _cont = conth(); // reload oop
  if (_tail != (oop)nullptr) {
    _tail = (stackChunkOop)jdk_internal_vm_Continuation::tail(_cont);
  }
}

const frame ContMirror::last_frame() {
  stackChunkOop chunk = last_nonempty_chunk();
  if (chunk == nullptr) return frame();
  return StackChunkFrameStream<chunk_frames::MIXED>(chunk).to_frame();
}

inline stackChunkOop ContMirror::nonempty_chunk(stackChunkOop chunk) const {
  while (chunk != nullptr && chunk->is_empty()) chunk = chunk->parent();
  return chunk;
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

#if CONT_JFR
template<typename Event> void ContMirror::post_jfr_event(Event* e, JavaThread* jt) {
  if (e->should_commit()) {
    log_develop_trace(jvmcont)("JFR event: iframes: %d size: %d", _e_num_interpreted_frames, _e_size);
    e->set_carrierThread(JFR_VM_THREAD_ID(jt));
    e->set_contClass(_cont->klass());
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->commit();
  }
}
#endif

#ifdef ASSERT
inline bool ContMirror::is_entry_frame(const frame& f) {
  return f.sp() == entrySP();
}

bool ContMirror::chunk_invariant(outputStream* st) {
  // only the topmost chunk can be empty
  if (_tail == (oop)nullptr)
    return true;
  assert (_tail->is_stackChunk(), "");
  int i = 1;
  for (stackChunkOop chunk = _tail->parent(); chunk != (oop)nullptr; chunk = chunk->parent()) {
    if (chunk->is_empty()) {
      assert (chunk != _tail, "");
      st->print_cr("i: %d", i);
      chunk->print_on(true, st);
      return false;
    }
    i++;
  }
  return true;
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

/////////////////////////////////////////////////////////////////

// Entry point to freeze. Transitions are handled manually
template<typename ConfigT>
static JRT_BLOCK_ENTRY(int, freeze(JavaThread* current, intptr_t* sp))
  assert (sp == current->frame_anchor()->last_Java_sp(), "");

  if (current->raw_cont_fastpath() > current->last_continuation()->entry_sp() || current->raw_cont_fastpath() < sp) {
    current->set_cont_fastpath(nullptr);
  }

  return ConfigT::freeze(current, sp);
JRT_END

typedef int (*FreezeContFnT)(JavaThread*, intptr_t*);
static FreezeContFnT preempt_freeze = nullptr;

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

  assert (target->has_last_Java_frame(), "");
  int res = preempt_freeze(target, target->last_Java_sp());
  log_trace(jvmcont, preempt)("try_force_yield: %s", freeze_result_names[res]);
  if (res == 0) { // success
    target->set_cont_preempt(true);

    // The target thread calls
    // Continuation::jump_from_safepoint from JavaThread::handle_special_runtime_exit_condition
    // to yield on return from suspension/blocking handshake.
  }
  return res;
}

JRT_LEAF(int, Continuation::prepare_thaw(JavaThread* thread, bool return_barrier))
  return prepare_thaw0(thread, return_barrier);
JRT_END

template<typename ConfigT>
static JRT_LEAF(intptr_t*, thaw(JavaThread* thread, int kind))
  // TODO: JRT_LEAF and NoHandleMark is problematic for JFR events.
  // vFrameStreamCommon allocates Handles in RegisterMap for continuations.
  // JRT_ENTRY instead?
  ResetNoHandleMark rnhm;

  return ConfigT::thaw(thread, (thaw_kind)kind);
JRT_END

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

const ContinuationEntry* Continuation::last_continuation(const JavaThread* thread, oop cont_scope) {
  // guarantee (thread->has_last_Java_frame(), "");
  for (ContinuationEntry* entry = thread->last_continuation(); entry != nullptr; entry = entry->parent()) {
    if (cont_scope == jdk_internal_vm_Continuation::scope(entry->continuation()))
      return entry;
  }
  return nullptr;
}

ContinuationEntry* Continuation::get_continuation_entry_for_continuation(JavaThread* thread, oop cont) {
  if (thread == nullptr || cont == (oop)nullptr) return nullptr;

  for (ContinuationEntry* entry = thread->last_continuation(); entry != nullptr; entry = entry->parent()) {
    if (cont == entry->continuation()) return entry;
  }
  return nullptr;
}

ContinuationEntry* Continuation::get_continuation_entry_for_entry_frame(JavaThread* thread, const frame& f) {
  assert (is_continuation_enterSpecial(f), "");
  return (ContinuationEntry*)f.unextended_sp();
}

static bool is_on_stack(JavaThread* thread, const ContinuationEntry* cont) {
  if (cont == nullptr) return false;
  assert (thread->is_in_full_stack((address)cont), "");
  return true;
  // return false if called when transitioning to Java on return from freeze
  // return !thread->has_last_Java_frame() || thread->last_Java_sp() < cont->entry_sp();
}

bool Continuation::is_continuation_mounted(JavaThread* thread, oop cont) {
  return is_on_stack(thread, get_continuation_entry_for_continuation(thread, cont));
}

bool Continuation::is_continuation_scope_mounted(JavaThread* thread, oop cont_scope) {
  return is_on_stack(thread, last_continuation(thread, cont_scope));
}

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

bool Continuation::is_continuation_enterSpecial(const frame& f) {
  if (f.cb() == nullptr || !f.cb()->is_compiled()) return false;
  Method* m = f.cb()->as_compiled_method()->method();
  return (m != nullptr && m->is_continuation_enter_intrinsic());
}

bool Continuation::is_continuation_entry_frame(const frame& f, const RegisterMap *map) {
  // we can do this because the entry frame is never inlined
  Method* m = (map != nullptr && map->in_cont() && f.is_interpreted_frame())
                  ? map->stack_chunk()->interpreter_frame_method(f)
                  : Frame::frame_method(f);
  return m != nullptr && m->intrinsic_id() == vmIntrinsics::_Continuation_enter;
}

static inline bool is_sp_in_continuation(const ContinuationEntry* cont, intptr_t* const sp) {
  return cont->entry_sp() > sp;
}

bool Continuation::is_frame_in_continuation(const ContinuationEntry* cont, const frame& f) {
  return is_sp_in_continuation(cont, f.unextended_sp());
}

static ContinuationEntry* get_continuation_entry_for_frame(JavaThread* thread, intptr_t* const sp) {
  assert (thread != nullptr, "");
  ContinuationEntry* cont = thread->last_continuation();
  while (cont != nullptr && !is_sp_in_continuation(cont, sp)) {
    cont = cont->parent();
  }
  return cont;
}

oop Continuation::get_continuation_for_sp(JavaThread* thread, intptr_t* const sp) {
  assert (thread != nullptr, "");
  ContinuationEntry* cont = get_continuation_entry_for_frame(thread, sp);
  return cont != nullptr ? cont->continuation() : (oop)nullptr;
}

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return get_continuation_entry_for_frame(thread, f.unextended_sp()) != nullptr;
}

bool Continuation::has_last_Java_frame(oop continuation) {
  return !ContMirror(continuation).is_empty();
}

static frame continuation_top_frame(oop contOop, RegisterMap* map) {
  stackChunkOop chunk = ContMirror(contOop).last_nonempty_chunk();
  map->set_stack_chunk(chunk);
  return chunk != nullptr ? chunk->top_frame(map) : frame();
}

frame Continuation::last_frame(oop continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  return continuation_top_frame(continuation, map);
}

frame Continuation::top_frame(const frame& callee, RegisterMap* map) {
  assert (map != nullptr, "");
  return continuation_top_frame(get_continuation_for_sp(map->thread(), callee.sp()), map);
}

javaVFrame* Continuation::last_java_vframe(Handle continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  if (!ContMirror(continuation()).is_empty()) {
    frame f = last_frame(continuation(), map);
    for (vframe* vf = vframe::new_vframe(&f, map, nullptr); vf; vf = vf->sender()) {
      if (vf->is_java_frame()) return javaVFrame::cast(vf);
    }
  }
  return nullptr;
}

frame Continuation::continuation_parent_frame(RegisterMap* map) {
  assert (map->in_cont(), "");
  ContMirror cont(map);
  assert (map->thread() != nullptr || !cont.is_mounted(), "");

  log_develop_trace(jvmcont)("continuation_parent_frame");
  if (map->update_map()) {
    // we need to register the link address for the entry frame
    if (cont.entry() != nullptr) {
      cont.entry()->update_register_map(map);
    } else {
      map->clear();
    }
  }

  if (!cont.is_mounted()) { // When we're walking an unmounted continuation and reached the end
    oop parent = jdk_internal_vm_Continuation::parent(cont.mirror());
    stackChunkOop chunk = parent != nullptr ? ContMirror(parent).last_nonempty_chunk() : nullptr;
    if (chunk != nullptr) {
      return chunk->top_frame(map);
    }

    map->set_stack_chunk(nullptr);
    return frame();
  }

  map->set_stack_chunk(nullptr);

#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
  frame sender(cont.entrySP(), cont.entryFP(), cont.entryPC());
#else
  frame sender = frame();
  Unimplemented();
#endif

  return sender;
}

oop Continuation::continuation_scope(oop cont) {
  return cont != nullptr ? jdk_internal_vm_Continuation::scope(cont) : nullptr;
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

bool Continuation::is_in_usable_stack(address addr, const RegisterMap* map) {
  ContMirror cont(map);
  stackChunkOop chunk = cont.find_chunk_by_address(addr);
  return chunk != nullptr ? chunk->is_usable_in_chunk(addr) : false;
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

bool Continuation::fix_continuation_bottom_sender(JavaThread* thread, const frame& callee,
                                                  address* sender_pc, intptr_t** sender_sp) {
  if (thread != nullptr && is_return_barrier_entry(*sender_pc)) {
    ContinuationEntry* cont = get_continuation_entry_for_frame(thread,
          callee.is_interpreted_frame() ? callee.interpreter_frame_last_sp() : callee.unextended_sp());
    assert (cont != nullptr, "callee.unextended_sp(): " INTPTR_FORMAT, p2i(callee.unextended_sp()));

    log_develop_debug(jvmcont)("fix_continuation_bottom_sender: "
                                  "[" JLONG_FORMAT "] [%d]", java_tid(thread), thread->osthread()->thread_id());
    log_develop_trace(jvmcont)("sender_pc: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_pc), p2i(cont->entry_pc()));
    log_develop_trace(jvmcont)("sender_sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_sp), p2i(cont->entry_sp()));

    *sender_pc = cont->entry_pc();
    *sender_sp = cont->entry_sp();
    // We DO NOT fix FP. It could contain an oop that has changed on the stack, and its location should be OK anyway

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

void Continuation::set_cont_fastpath_thread_state(JavaThread* thread) {
  assert (thread != nullptr, "");
  bool fast = !thread->is_interp_only_mode();
  thread->set_cont_fastpath_thread_state(fast);
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
#endif

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

#ifdef ASSERT
NOINLINE bool Continuation::debug_verify_continuation(oop contOop) {
  DEBUG_ONLY(if (!VerifyContinuations) return true;)
  assert (contOop != (oop)nullptr, "");
  assert (oopDesc::is_oop(contOop), "");
  ContMirror cont(contOop);

  assert (oopDesc::is_oop_or_null(cont.tail()), "");
  assert (cont.chunk_invariant(tty), "");

  bool nonempty_chunk = false;
  size_t max_size = 0;
  int num_chunks = 0;
  int num_frames = 0;
  int num_interpreted_frames = 0;
  int num_oops = 0;

  for (stackChunkOop chunk = cont.tail(); chunk != nullptr; chunk = chunk->parent()) {
    log_develop_trace(jvmcont)("debug_verify_continuation chunk %d", num_chunks);
    chunk->verify(&max_size, &num_oops, &num_frames, &num_interpreted_frames);
    if (!chunk->is_empty()) nonempty_chunk = true;
    num_chunks++;
  }

  const bool is_empty = cont.is_empty();
  assert (!nonempty_chunk || !is_empty, "");
  assert (is_empty == (!nonempty_chunk && cont.last_frame().is_empty()), "");

  return true;
}

void Continuation::debug_print_continuation(oop contOop, outputStream* st) {
  if (st == nullptr) st = tty;

  ContMirror cont(contOop);

  st->print_cr("CONTINUATION: " PTR_FORMAT " done: %d",
    contOop->identity_hash(), jdk_internal_vm_Continuation::done(contOop));
  st->print_cr("CHUNKS:");
  for (stackChunkOop chunk = cont.tail(); chunk != (oop)nullptr; chunk = chunk->parent()) {
    st->print("* ");
    chunk->print_on(true, st);
  }
}
#endif // ASSERT

/////////////// FREEZE ////

class FreezeBase { // avoids the template of the Freeze class
protected:
  static inline void relativize_interpreted_frame_metadata(const frame& f, const frame& hf);
  template<typename FKind> static inline frame sender(const frame& f);
};

template <typename ConfigT>
class Freeze : public FreezeBase {
private:
  JavaThread* const _thread;
  ContMirror& _cont;
  bool _barriers;
  const bool _preempt; // used only on the slow path

  intptr_t *_bottom_address;
  intptr_t *_top_address;

  int _size; // total size of all frames plus metadata in words.
  int _align_size;

  NOT_PRODUCT(int _frames;)
  DEBUG_ONLY(intptr_t* _last_write;)

  inline void set_top_frame_metadata_pd(const frame& hf);
  template <typename FKind, bool bottom> inline void patch_pd(frame& callee, const frame& caller);
  inline void patch_chunk_pd(intptr_t* vsp, intptr_t* hsp);
  template<typename FKind> frame new_hframe(frame& f, frame& caller);

public:

  Freeze(JavaThread* thread, ContMirror& mirror, bool preempt) :
    _thread(thread), _cont(mirror), _barriers(false), _preempt(preempt) {

    assert (thread->last_continuation()->entry_sp() == _cont.entrySP(), "");

    int argsize = _cont.argsize();
    _bottom_address = _cont.entrySP() - argsize;
    DEBUG_ONLY(_cont.entry()->verify_cookie();)

    assert (!Interpreter::contains(_cont.entryPC()), "");

  #ifdef _LP64
    if (((intptr_t)_bottom_address & 0xf) != 0) {
      _bottom_address--;
    }
    assert((intptr_t)_bottom_address % 16 == 0, "");
  #endif

    log_develop_trace(jvmcont)("bottom_address: " INTPTR_FORMAT " entrySP: " INTPTR_FORMAT " argsize: " PTR_FORMAT,
                  p2i(_bottom_address), p2i(_cont.entrySP()), (_cont.entrySP() - _bottom_address) << LogBytesPerWord);
    assert (_bottom_address != nullptr && _bottom_address <= _cont.entrySP(), "");
    DEBUG_ONLY(_last_write = nullptr;)
  }

  void init_rest() { // we want to postpone some initialization after chunk handling
    _size = 0;
    _align_size = 0;
    NOT_PRODUCT(_frames = 0;)
  }

  template <copy_alignment aligned = copy_alignment::DWORD_ALIGNED>
  void copy_to_chunk(intptr_t* from, intptr_t* to, int size) {
    stackChunkOop chunk = _cont.tail();
    chunk->copy_from_stack_to_chunk<aligned>(from, to, size);
    CONT_JFR_ONLY(_cont.record_size_copied(size);)

  #ifdef ASSERT
    if (_last_write != nullptr) {
      assert (_last_write == to + size, "Missed a spot: _last_write: " INTPTR_FORMAT " to+size: " INTPTR_FORMAT
          " stack_size: %d _last_write offset: " PTR_FORMAT " to+size: " PTR_FORMAT, p2i(_last_write), p2i(to+size),
          chunk->stack_size(), _last_write-chunk->start_address(), to+size-chunk->start_address());
      _last_write = to;
    }
  #endif
  }

  // Called _after_ the last possible sfepoint during the freeze operation (chunk allocation)
  void unwind_frames() {
    JavaThread* thread = _thread;
    ContinuationEntry* entry = _cont.entry();
    ContinuationHelper::maybe_flush_stack_processing(thread, entry);
    ContinuationHelper::set_anchor_to_entry(thread, entry);
  }

  template <bool chunk_available>
  freeze_result try_freeze_fast(intptr_t* sp) {
    if (freeze_fast<chunk_available>(sp)) {
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
    intptr_t* const stack_top     = top_sp + ContinuationHelper::frame_metadata;
    const int       stack_argsize = _cont.argsize();
    intptr_t* const stack_bottom  = _cont.entrySP() - ContinuationHelper::frame_align_words(stack_argsize);

    int size = stack_bottom - stack_top; // in words

    const int chunk_sp = chunk->sp();
    if (chunk_sp < chunk->stack_size()) {
      size -= stack_argsize;
    }
    assert (size > 0, "");

    bool available = chunk_sp - ContinuationHelper::frame_metadata >= size;
    log_develop_trace(jvmcont)("is_chunk_available: %d size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT,
      available, stack_argsize, size, p2i(stack_top), p2i(stack_bottom));
    DEBUG_ONLY(if (out_size != nullptr) *out_size = size;)
    return available;
  }

  template <bool chunk_available>
  bool freeze_fast(intptr_t* top_sp) {
    assert (_thread != nullptr, "");
    assert (_cont.chunk_invariant(tty), "");
    assert (!Interpreter::contains(_cont.entryPC()), "");
    assert (StubRoutines::cont_doYield_stub()->frame_size() == ContinuationHelper::frame_metadata, "");

    // properties of the continuation on the stack; all sizes are in words
    intptr_t* const stack_top     = top_sp + ContinuationHelper::frame_metadata;
    const int       stack_argsize = _cont.argsize();
    intptr_t* const stack_bottom  = _cont.entrySP() - ContinuationHelper::frame_align_words(stack_argsize); // see alignment in thaw

    const int size = stack_bottom - stack_top;

    log_develop_trace(jvmcont)("freeze_fast size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT,
      size, stack_argsize, p2i(stack_top), p2i(stack_bottom));
    assert (size > 0, "");

  #ifdef ASSERT
    bool allocated, empty;
    int is_chunk_available_size;
    bool is_chunk_available0 = is_chunk_available(top_sp, &is_chunk_available_size);
    intptr_t* orig_chunk_sp = nullptr;
  #endif

    stackChunkOop chunk = _cont.tail();
    int sp_before; // the chunk's sp before the freeze, adjusted to point beyond the stack-passed arguments in the topmost frame
    if (chunk_available) { // LIKELY
      DEBUG_ONLY(allocated = false;)
      DEBUG_ONLY(orig_chunk_sp = chunk->sp_address();)

      assert (is_chunk_available0, "");

      sp_before = chunk->sp();

      if (sp_before < chunk->stack_size()) { // we are copying into a non-empty chunk
        DEBUG_ONLY(empty = false;)
        assert (sp_before < (chunk->stack_size() - chunk->argsize()), "");
        assert (*(address*)(chunk->sp_address() - frame::sender_sp_ret_address_offset()) == chunk->pc(), "");

        sp_before += stack_argsize; // we overlap; we'll overwrite the chunk's top frame's callee arguments
        assert (sp_before <= chunk->stack_size(), "");

        chunk->set_max_size(chunk->max_size() + size - stack_argsize);

        intptr_t* const bottom_sp = stack_bottom - stack_argsize;
        assert (bottom_sp == _bottom_address, "");
        assert (*(address*)(bottom_sp-frame::sender_sp_ret_address_offset()) == StubRoutines::cont_returnBarrier(), "");
        patch_chunk_pd(bottom_sp, chunk->sp_address());
        // we don't patch the pc at this time, so as not to make the stack unwalkable
      } else { // the chunk is empty
        DEBUG_ONLY(empty = true;)
        assert(sp_before == chunk->stack_size(), "");

        chunk->set_max_size(size);
        chunk->set_argsize(stack_argsize);
      }
    } else { // no chunk; allocate
      DEBUG_ONLY(empty = true; allocated = true;)

      assert (_thread->thread_state() == _thread_in_vm, "");
      assert (!is_chunk_available(top_sp), "");
      assert (_thread->cont_fastpath(), "");

      chunk = allocate_chunk(size + ContinuationHelper::frame_metadata);
      if (UNLIKELY(chunk == nullptr)) return false; // OOME
      if (UNLIKELY(!_thread->cont_fastpath()
                  || _barriers)) { // probably humongous
        log_develop_trace(jvmcont)("Retrying slow. Barriers: %d", _barriers);
        init_empty_chunk(chunk);
        return false;
      }

      chunk->set_max_size(size);
      chunk->set_argsize(stack_argsize);

      // in a fresh chunk, we freeze *with* the bottom-most frame's stack arguments.
      // They'll then be stored twice: in the chunk and in the parent chunk's top frame
      sp_before = size + ContinuationHelper::frame_metadata;
      assert (sp_before == chunk->stack_size(), "");

      DEBUG_ONLY(orig_chunk_sp = chunk->start_address() + sp_before;)
    }

    assert (chunk != nullptr, "");
    assert (chunk->is_stackChunk(), "");
    assert (!chunk->has_mixed_frames(), "");
    assert (!chunk->is_gc_mode(), "");
    assert (!chunk->has_bitmap(), "");
    assert (!chunk->requires_barriers(), "");
    assert (chunk == _cont.tail(), "");

    // We unwind frames after the last safepoint so that the GC will have found the oops in the frames, but before
    // writing into the chunk. This is so that an asynchronous stack walk (not at a safepoint) that suspends us here
    // will either see no continuation on the stack, or a consistent chunk.
    unwind_frames();
    OrderAccess::storestore();

    NoSafepointVerifier nsv;

    log_develop_trace(jvmcont)("freeze_fast start: chunk " INTPTR_FORMAT " size: %d orig sp: %d argsize: %d",
      p2i((oopDesc*)chunk), chunk->stack_size(), sp_before, stack_argsize);
    assert (sp_before <= chunk->stack_size(), "");
    assert (sp_before >= size, "");

    const int sp_after = sp_before - size; // the chunk's new sp, after freeze
    assert (!is_chunk_available0 || orig_chunk_sp - (chunk->start_address() + sp_after) == is_chunk_available_size, "");

    intptr_t* chunk_top = chunk->start_address() + sp_after;
    assert (empty || *(address*)(orig_chunk_sp - frame::sender_sp_ret_address_offset()) == chunk->pc(), "");

    log_develop_trace(jvmcont)("freeze_fast start: " INTPTR_FORMAT " sp: %d chunk_top: " INTPTR_FORMAT,
                                p2i(chunk->start_address()), sp_after, p2i(chunk_top));
    intptr_t* from = stack_top - ContinuationHelper::frame_metadata;
    intptr_t* to   = chunk_top - ContinuationHelper::frame_metadata;
    copy_to_chunk(from, to, size + ContinuationHelper::frame_metadata);
    // Because we're not patched yet, the chunk is now in a bad state

    // patch pc
    intptr_t* chunk_bottom_sp = chunk_top + size - stack_argsize;
    assert (empty || *(address*)(chunk_bottom_sp-frame::sender_sp_ret_address_offset()) == StubRoutines::cont_returnBarrier(), "");
    *(address*)(chunk_bottom_sp - frame::sender_sp_ret_address_offset()) = chunk->pc();

    // We're always writing to a young chunk, so the GC can't see it until the next safepoint.
    OrderAccess::storestore();
    chunk->set_sp(sp_after);
    assert (chunk->sp_address() == chunk_top, "");
    chunk->set_pc(*(address*)(stack_top - frame::sender_sp_ret_address_offset()));

    _cont.write();

    log_develop_trace(jvmcont)("FREEZE CHUNK #" INTPTR_FORMAT " (young)", _cont.hash());
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      chunk->print_on(true, &ls);
    }

    assert (_cont.chunk_invariant(tty), "");
    assert (VERIFY_STACK_CHUNK(chunk), "");

  #if CONT_JFR
    EventContinuationFreezeYoung e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(chunk));
      DEBUG_ONLY(e.set_allocate(allocated);)
      e.set_size(size << LogBytesPerWord);
      e.commit();
    }
  #endif

    return true;
  }

 NOINLINE freeze_result freeze_slow() {
  #ifdef ASSERT
    ResourceMark rm;
  #endif

    log_develop_trace(jvmcont)("freeze_slow  #" INTPTR_FORMAT, _cont.hash());
    assert (_thread->thread_state() == _thread_in_vm || _thread->thread_state() == _thread_blocked, "");

    init_rest();

    HandleMark hm(Thread::current());

    frame f = freeze_start_frame();

    LogTarget(Debug, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      f.print_on(&ls);
    }

    _top_address = f.sp();
    frame caller;
    freeze_result res = freeze(f, caller, 0, false, true);

    if (res == freeze_ok) {
      finish_freeze(f, caller);
      _cont.write();
    }

    return res;
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
    return f;
  }

  frame freeze_start_frame_safepoint_stub(frame f) {
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
    f.set_fp(f.real_fp()); // f.set_fp(*Frame::callee_link_address(f)); // ????
#else
    Unimplemented();
#endif
    if (!Interpreter::contains(f.pc())) {
      assert (Frame::is_stub(f.cb()), "must be");
      assert (f.oop_map() != nullptr, "must be");

      if (Interpreter::contains(StubF::return_pc(f))) {
        f = sender<StubF>(f); // Safepoint stub in interpreter
      }
    }
    return f;
  }

  NOINLINE freeze_result freeze(frame& f, frame& caller, int callee_argsize, bool callee_interpreted, bool top) {
    assert (f.unextended_sp() < _bottom_address, ""); // see recurse_freeze_java_frame
    assert (f.is_interpreted_frame() || ((top && _preempt) == Frame::is_stub(f.cb())), "");

    if (stack_overflow()) return freeze_exception;

    if (f.is_compiled_frame()) {
      if (UNLIKELY(f.oop_map() == nullptr)) return freeze_pinned_native; // special native frame
      if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), SmallRegisterMap::instance, f))) return freeze_pinned_monitor;

      return recurse_freeze_compiled_frame(f, caller, callee_argsize, callee_interpreted);
    } else if (f.is_interpreted_frame()) {
      assert ((_preempt && top) || !f.interpreter_frame_method()->is_native(), "");
      if (Interpreted::is_owning_locks(f)) return freeze_pinned_monitor;
      if (_preempt && top && f.interpreter_frame_method()->is_native()) return freeze_pinned_native; // int native entry

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

    assert (fsize > 0 && argsize >= 0, "");
    _size += fsize;
    NOT_PRODUCT(_frames++;)

    if (FKind::frame_bottom(f) >= _bottom_address - 1) { // sometimes there's space after enterSpecial
      return finalize_freeze<FKind>(f, caller, argsize); // recursion end
    } else {
      frame senderf = sender<FKind>(f);
      assert (FKind::interpreted || senderf.sp() == senderf.unextended_sp(), "");
      freeze_result result = freeze(senderf, caller, argsize, FKind::interpreted, false); // recursive call
      return result;
    }
  }

  inline void before_freeze_java_frame(const frame& f, const frame& caller, int fsize, int argsize, bool bottom) {
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("======== FREEZING FRAME interpreted: %d bottom: %d", f.is_interpreted_frame(), bottom);
      ls.print_cr("fsize: %d argsize: %d", fsize, argsize);
      f.print_on(&ls);
    }
    assert (caller.is_interpreted_frame() == Interpreter::contains(caller.pc()), "");
  }

  inline void after_freeze_java_frame(const frame& hf, bool bottom) {
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      DEBUG_ONLY(hf.print_value_on(&ls, nullptr);)
      assert(hf.is_interpreted_heap_frame(), "should be");
      DEBUG_ONLY(print_frame_layout(hf, &ls);)
      if (bottom) {
        ls.print_cr("bottom h-frame:");
        hf.print_on(&ls);
      }
    }
  }

  template<typename FKind> // the callee's type
  freeze_result finalize_freeze(const frame& callee, frame& caller, int argsize) {
    assert (FKind::interpreted || argsize == _cont.argsize(), "argsize: %d cont.argsize: %d", argsize, _cont.argsize());
    log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d argsize: %d",
      p2i(_bottom_address), _frames, _size << LogBytesPerWord, argsize);

    LogTarget(Trace, jvmcont) lt;

  #ifdef ASSERT
    bool empty = _cont.is_empty();
    log_develop_trace(jvmcont)("empty: %d", empty);
  #endif

    stackChunkOop chunk = _cont.tail();

    assert (chunk == nullptr || (chunk->max_size() == 0) == chunk->is_empty(), "");

    _size += ContinuationHelper::frame_metadata; // for top frame's metadata

    int overlap = 0; // the args overlap the caller -- if there is one in this chunk and is of the same kind
    int unextended_sp = -1;
    if (chunk != nullptr) {
      unextended_sp = chunk->sp();
      if (!chunk->is_empty()) {
        bool top_interpreted = Interpreter::contains(chunk->pc());
        unextended_sp = chunk->sp();
        if (top_interpreted) {
          StackChunkFrameStream<chunk_frames::MIXED> last(chunk);
          unextended_sp += last.unextended_sp() - last.sp(); // can be negative (-1), often with lambda forms
        }
        if (FKind::interpreted == top_interpreted) {
          overlap = argsize;
        }
      }
    }

    log_develop_trace(jvmcont)("finalize _size: %d overlap: %d unextended_sp: %d", _size, overlap, unextended_sp);

    _size -= overlap;
    assert (_size >= 0, "");

    assert (chunk == nullptr || chunk->is_empty()
            || unextended_sp == chunk->to_offset(StackChunkFrameStream<chunk_frames::MIXED>(chunk).unextended_sp()), "");
    assert (chunk != nullptr || unextended_sp < _size, "");

     // _barriers can be set to true by an allocation in freeze_fast, in which case the chunk is available
    assert (!_barriers || (unextended_sp >= _size && chunk->is_empty()),
      "unextended_sp: %d size: %d is_empty: %d", unextended_sp, _size, chunk->is_empty());

    DEBUG_ONLY(bool empty_chunk = true);
    if (unextended_sp < _size || chunk->is_gc_mode() || (!_barriers && ConfigT::requires_barriers(chunk))) {
      // ALLOCATION

      if (lt.develop_is_enabled()) {
        LogStream ls(lt);
        if (chunk == nullptr) ls.print_cr("no chunk");
        else {
          ls.print_cr("chunk barriers: %d _size: %d free size: %d",
            chunk->requires_barriers(), _size, chunk->sp() - ContinuationHelper::frame_metadata);
          chunk->print_on(&ls);
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
      chunk->set_argsize(argsize);
      assert (chunk->is_empty(), "");

      if (_barriers) { log_develop_trace(jvmcont)("allocation requires barriers"); }
    } else {
      log_develop_trace(jvmcont)("Reusing chunk mixed: %d empty: %d", chunk->has_mixed_frames(), chunk->is_empty());
      if (chunk->is_empty()) {
        int sp = chunk->stack_size() - argsize;
        chunk->set_sp(sp);
        chunk->set_argsize(argsize);
        _size += overlap;
        assert (chunk->max_size() == 0, "");
      } DEBUG_ONLY(else empty_chunk = false;)
    }
    chunk->set_has_mixed_frames(true);

    assert (chunk->requires_barriers() == _barriers, "");
    assert (!_barriers || chunk->is_empty(), "");

    assert (!chunk->has_bitmap(), "");
    assert (!chunk->is_empty() || StackChunkFrameStream<chunk_frames::MIXED>(chunk).is_done(), "");
    assert (!chunk->is_empty() || StackChunkFrameStream<chunk_frames::MIXED>(chunk).to_frame().is_empty(), "");

    // We unwind frames after the last safepoint so that the GC will have found the oops in the frames, but before
    // writing into the chunk. This is so that an asynchronous stack walk (not at a safepoint) that suspends us here
    // will either see no continuation or a consistent chunk.
    unwind_frames();
    OrderAccess::storestore();

    chunk->set_max_size(chunk->max_size() + _size - ContinuationHelper::frame_metadata);

    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("top chunk:");
      chunk->print_on(&ls);
    }

    caller = StackChunkFrameStream<chunk_frames::MIXED>(chunk).to_frame();

    DEBUG_ONLY(_last_write = caller.unextended_sp() + (empty_chunk ? argsize : overlap);)
    assert(chunk->is_in_chunk(_last_write - _size),
      "last_write-size: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(_last_write-_size), p2i(chunk->start_address()));
  #ifdef ASSERT
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("top hframe before (freeze):");
      assert(caller.is_interpreted_heap_frame(), "should be");
      caller.print_on(&ls);
    }

    assert (!empty || Continuation::is_continuation_entry_frame(callee, nullptr), "");

    frame entry = sender<FKind>(callee);

    assert (Continuation::is_return_barrier_entry(entry.pc()) || Continuation::is_continuation_enterSpecial(entry), "");
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
      FKind::patch_pc(caller, last_pc);
      patch_pd<FKind, true>(hf, caller);
    } else {
      assert (!caller.is_empty(), "");
      patch_pd<FKind, false>(hf, caller);
    }
    if (FKind::interpreted) {
      assert(hf.is_interpreted_heap_frame(), "should be");
      Interpreted::patch_sender_sp(hf, caller.unextended_sp());
    }

#ifdef ASSERT
    if (!FKind::interpreted && !FKind::stub) {
      assert (hf.get_cb()->is_compiled(), "");
      if (f.is_deoptimized_frame()) { // TODO DEOPT: long term solution: unroll on freeze and patch pc
        log_develop_trace(jvmcont)("Freezing deoptimized frame");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
        assert (f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f)), "");
      }
    }
#endif
  }

  NOINLINE freeze_result recurse_freeze_interpreted_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted) {
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
    { // TODO PD
      assert ((f.at(frame::interpreter_frame_last_sp_offset) != 0) || (f.unextended_sp() == f.sp()), "");
      intptr_t* real_unextended_sp = (intptr_t*)f.at(frame::interpreter_frame_last_sp_offset);
      if (real_unextended_sp != nullptr) f.set_unextended_sp(real_unextended_sp); // can be null at a safepoint
    }
#else
    Unimplemented();
#endif

    intptr_t* const vsp = Interpreted::frame_top(f, callee_argsize, callee_interpreted);
    const int argsize = Interpreted::stack_argsize(f);
    const int locals = f.interpreter_frame_method()->max_locals();
    assert (Interpreted::frame_bottom(f) >= f.fp() + ContinuationHelper::frame_metadata + locals, "");// = on x86
    const int fsize = f.fp() + ContinuationHelper::frame_metadata + locals - vsp;

#ifdef ASSERT
  {
    ResourceMark rm;
    InterpreterOopMap mask;
    f.interpreted_frame_oop_map(&mask);
    assert (vsp <= Interpreted::frame_top(f, &mask), "vsp: " INTPTR_FORMAT " Interpreted::frame_top: " INTPTR_FORMAT,
      p2i(vsp), p2i(Interpreted::frame_top(f, &mask)));
  }
#endif

    Method* frame_method = Frame::frame_method(f);

    log_develop_trace(jvmcont)("recurse_freeze_interpreted_frame %s _size: %d fsize: %d argsize: %d",
      frame_method->name_and_sig_as_C_string(), _size, fsize, argsize);
    // we'd rather not yield inside methods annotated with @JvmtiMountTransition
    assert (!Frame::frame_method(f)->jvmti_mount_transition(), "");

    freeze_result result = recurse_freeze_java_frame<Interpreted>(f, caller, fsize, argsize);
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    bool bottom = result == freeze_ok_bottom;

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, bottom);)

    frame hf = new_hframe<Interpreted>(f, caller);

    intptr_t* hsp = Interpreted::frame_top(hf, callee_argsize, callee_interpreted);
    assert (Interpreted::frame_bottom(hf) == hsp + fsize, "");

    // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
    copy_to_chunk<copy_alignment::WORD_ALIGNED>(Interpreted::frame_bottom(f) - locals,
                                             Interpreted::frame_bottom(hf) - locals, locals); // copy locals
    copy_to_chunk<copy_alignment::WORD_ALIGNED>(vsp, hsp, fsize - locals); // copy rest
    assert (!bottom || !caller.is_interpreted_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

    relativize_interpreted_frame_metadata(f, hf);

    patch<Interpreted>(f, hf, caller, bottom);

    CONT_JFR_ONLY(_cont.record_interpreted_frame();)
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

    log_develop_trace(jvmcont)("recurse_freeze_compiled_frame %s _size: %d fsize: %d argsize: %d",
      Frame::frame_method(f) != nullptr ? Frame::frame_method(f)->name_and_sig_as_C_string():"", _size,fsize,argsize);
    // we'd rather not yield inside methods annotated with @JvmtiMountTransition
    assert (!Frame::frame_method(f)->jvmti_mount_transition(), "");

    freeze_result result = recurse_freeze_java_frame<Compiled>(f, caller, fsize, argsize);
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    bool bottom = result == freeze_ok_bottom;

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, argsize, bottom);)

    frame hf = new_hframe<Compiled>(f, caller);

    intptr_t* hsp = Compiled::frame_top(hf, callee_argsize, callee_interpreted);

    copy_to_chunk<copy_alignment::WORD_ALIGNED>(vsp, hsp, fsize);
    assert (!bottom || !caller.is_compiled_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

    if (caller.is_interpreted_frame()) {
      _align_size += ContinuationHelper::align_wiggle; // See Thaw::align
    }

    patch<Compiled>(f, hf, caller, bottom);

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

    // recurse_freeze_java_frame and freeze inlined here because we need to use a full RegisterMap for lock ownership
    NOT_PRODUCT(_frames++;)
    _size += fsize;

    RegisterMap map(_cont.thread(), true, false, false);
    map.set_include_argument_oops(false);
    ContinuationHelper::update_register_map<StubF>(f, &map);
    f.oop_map()->update_register_map(&f, &map); // we have callee-save registers in this case
    frame senderf = sender<StubF>(f);
    assert (senderf.unextended_sp() < _bottom_address - 1, "");
    assert (senderf.is_compiled_frame(), "");

    if (UNLIKELY(senderf.oop_map() == nullptr)) return freeze_pinned_native; // native frame
    if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), &map, senderf))) return freeze_pinned_monitor;

    freeze_result result = recurse_freeze_compiled_frame(senderf, caller, 0, 0); // This might be deoptimized
    if (UNLIKELY(result > freeze_ok_bottom)) return result;
    assert (result != freeze_ok_bottom, "");
    assert (!caller.is_interpreted_frame(), "");

    DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, false);)
    frame hf = new_hframe<StubF>(f, caller);
    intptr_t* hsp = StubF::frame_top(hf, 0, 0);
    copy_to_chunk<copy_alignment::WORD_ALIGNED>(vsp, hsp, fsize);
    DEBUG_ONLY(after_freeze_java_frame(hf, false);)

    caller = hf;
    return freeze_ok;
  }

  NOINLINE void finish_freeze(const frame& f, const frame& top) {
    stackChunkOop chunk = _cont.tail();
    assert (chunk->to_offset(top.sp()) <= chunk->sp(), "");

    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      assert(top.is_interpreted_heap_frame(), "should be");
      top.print_on(&ls);
    }

    set_top_frame_metadata_pd(top);
    assert (top.pc() == Frame::real_pc(top), "");

    OrderAccess::storestore();
    chunk->set_sp(chunk->to_offset(top.sp()));
    chunk->set_pc(top.pc());

    chunk->set_max_size(chunk->max_size() + _align_size);

    if (UNLIKELY(_barriers)) {
      log_develop_trace(jvmcont)("do barriers on humongous chunk");
      InstanceStackChunkKlass::do_barriers<InstanceStackChunkKlass::barrier_type::STORE>(_cont.tail());
    }

    log_develop_trace(jvmcont)("finish_freeze: has_mixed_frames: %d", chunk->has_mixed_frames());

    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("top hframe after (freeze):");
      assert(_cont.last_frame().is_interpreted_heap_frame(), "should be");
      _cont.last_frame().print_on(&ls);
    }

    assert(_cont.chunk_invariant(tty), "");
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

  void init_empty_chunk(stackChunkOop chunk) {
    chunk->set_sp(chunk->stack_size());
    chunk->set_pc(nullptr);
    chunk->set_argsize(0);
  }

  stackChunkOop allocate_chunk(size_t stack_size) {
    log_develop_trace(jvmcont)("allocate_chunk allocating new chunk");

    InstanceStackChunkKlass* klass = InstanceStackChunkKlass::cast(vmClasses::StackChunk_klass());
    size_t size_in_words = klass->instance_size(stack_size);

    JavaThread* current = _preempt ? JavaThread::current() : _thread;
    assert(current == JavaThread::current(), "should be current");

    stackChunkOop chunk;
    StackChunkAllocator allocator(klass, size_in_words, stack_size, current);
    HeapWord* start = current->tlab().allocate(size_in_words);
    if (start != nullptr) {
      chunk = (stackChunkOop)allocator.initialize(start);
    } else {
      //HandleMark hm(current);
      Handle conth(current, _cont.mirror());
      chunk = (stackChunkOop)allocator.allocate(); // can safepoint
      _cont.post_safepoint(conth);

      if (chunk == nullptr) {
        // OOME
        return nullptr;
      }

      _barriers = ConfigT::requires_barriers(chunk);
    }

    assert (chunk->stack_size() == (int)stack_size, "");
    assert (chunk->size() >= stack_size, "chunk->size(): %zu size: %zu", chunk->size(), stack_size);
    assert ((intptr_t)chunk->start_address() % 8 == 0, "");

    // TODO PERF: maybe just memset 0, and only set non-zero fields.
    chunk->clear_flags();
    chunk->set_gc_mode(false);
    chunk->set_max_size(0);
    // chunk->set_pc(nullptr);
    // chunk->set_argsize(0);

    assert (chunk->flags() == 0, "");
    assert (chunk->is_gc_mode() == false, "");
    assert (chunk->max_size() == 0, "");

    chunk->set_mark(chunk->mark().set_age(15)); // Promote young chunks quickly

    stackChunkOop chunk0 = _cont.tail();
    if (chunk0 != (oop)nullptr && chunk0->is_empty()) {
      chunk0 = chunk0->parent();
      assert (chunk0 == (oop)nullptr || !chunk0->is_empty(), "");
    }
    // fields are uninitialized
    chunk->set_parent_raw<typename ConfigT::OopT>(chunk0);
    chunk->set_cont_raw<typename ConfigT::OopT>(_cont.mirror());
    assert (chunk->parent() == (oop)nullptr || chunk->parent()->is_stackChunk(), "");

    if (start != nullptr) {
      assert(!ConfigT::requires_barriers(chunk), "Unfamiliar GC requires barriers on TLAB allocation");
    } else {
      _barriers = ConfigT::requires_barriers(chunk);
    }

    _cont.set_tail(chunk);
    return chunk;
  }
};

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
  assert (!thread->cont_fastpath()
              || (thread->cont_fastpath_thread_state() && !interpreted_native_or_deoptimized_on_stack(thread)), "");

  // We also clear thread->cont_fastpath on deoptimization (notify_deopt) and when we thaw interpreted frames
  bool fast = thread->cont_fastpath() && UseContinuationFastPath;
  assert (!fast || monitors_on_stack(thread) == (thread->held_monitor_count() > 0), "");
  fast = fast && thread->held_monitor_count() == 0;
  return fast;
}

static int early_return(int res, JavaThread* thread) {
  thread->set_cont_yield(false);
  log_develop_trace(jvmcont)("=== end of freeze (fail %d)", res);
  return res;
}

static inline int freeze_epilog(JavaThread* thread, ContMirror& cont) {
  assert (VERIFY_CONTINUATION(cont.mirror()), "");
  assert (!cont.is_empty(), "");

  thread->set_cont_yield(false);
  log_develop_debug(jvmcont)("=== End of freeze cont ### #" INTPTR_FORMAT, cont.hash());

  return 0;
}

static int freeze_epilog(JavaThread* thread, ContMirror& cont, freeze_result res) {
  if (UNLIKELY(res != freeze_ok)) {
    assert (VERIFY_CONTINUATION(cont.mirror()), "");
    return early_return(res, thread);
  }

  JVMTI_yield_cleanup(thread, cont); // can safepoint
  return freeze_epilog(thread, cont);
}

template<typename ConfigT, bool preempt>
static inline int freeze0(JavaThread* current, intptr_t* const sp) {
  assert (!current->cont_yield(), "");
  assert (!current->has_pending_exception(), ""); // if (current->has_pending_exception()) return early_return(freeze_exception, current, fi);
  assert (current->deferred_updates() == nullptr || current->deferred_updates()->count() == 0, "");
  assert (!preempt || current->thread_state() == _thread_in_vm || current->thread_state() == _thread_blocked,
          "thread_state: %d %s", current->thread_state(), current->thread_state_name());

  LogTarget(Trace, jvmcont) lt;
#ifdef ASSERT
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("~~~~ freeze sp: " INTPTR_FORMAT, p2i(current->last_continuation()->entry_sp()));
    print_frames(current, &ls);
  }
#endif

  CONT_JFR_ONLY(EventContinuationFreeze event;)

  current->set_cont_yield(true);

  oop oopCont = ContinuationHelper::get_continuation(current);
  assert (oopCont == current->last_continuation()->cont_oop(), "");
  assert (ContinuationEntry::assert_entry_frame_laid_out(current), "");

  assert (VERIFY_CONTINUATION(oopCont), "");
  ContMirror cont(current, oopCont);
  log_develop_debug(jvmcont)("FREEZE #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  if (jdk_internal_vm_Continuation::critical_section(oopCont) > 0) {
    log_develop_debug(jvmcont)("PINNED due to critical section");
    assert (VERIFY_CONTINUATION(cont.mirror()), "");
    return early_return(freeze_pinned_cs, current);
  }

  bool fast = can_freeze_fast(current);
  assert (!fast || current->held_monitor_count() == 0, "");

  // no need to templatize on preempt, as it's currently only used on the slow path
  Freeze<ConfigT> fr(current, cont, preempt);

  if (preempt) { // UNLIKELY
    freeze_result res = fr.freeze_slow();
    cont.set_preempted(true);
    CONT_JFR_ONLY(cont.post_jfr_event(&event, current);)
    return freeze_epilog(current, cont, res);
  }

  if (fast && fr.is_chunk_available(sp)) {
    freeze_result res = fr.template try_freeze_fast<true>(sp);
    assert (res == freeze_ok, "");
    CONT_JFR_ONLY(cont.post_jfr_event(&event, current);)
    freeze_epilog(current, cont);
    StackWatermarkSet::after_unwind(current);
    return 0;
  }

  // if (current->held_monitor_count() > 0) {
  //    return freeze_pinned_monitor;
  // }

  log_develop_trace(jvmcont)("chunk unavailable; transitioning to VM");
  assert(current == JavaThread::current(), "must be current thread except for preempt");
  JRT_BLOCK
    freeze_result res = fast ? fr.template try_freeze_fast<false>(sp)
                             : fr.freeze_slow();
    CONT_JFR_ONLY(cont.post_jfr_event(&event, current);)
    freeze_epilog(current, cont, res);
    StackWatermarkSet::after_unwind(current);
    return res;
  JRT_BLOCK_END
}

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
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
    f.set_fp(f.real_fp()); // Instead of this, maybe in ContMirror::set_last_frame always use the real_fp?
#else
    Unimplemented();
#endif
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

static bool is_safe_frame_to_preempt(JavaThread* thread) {
  assert (thread->has_last_Java_frame(), "");
  vframeStream st(thread);
  st.dont_walk_cont();

  // We don't want to preempt inside methods annotated with @JvmtiMountTransition
  int i = 0;
  for (;!st.at_end(); st.next()) {
    if (++i > 5) break; // annotations are never deep
    if (st.method()->jvmti_mount_transition())
      return false;
  }
  return true;
}

static bool is_safe_pc_to_preempt(address pc) {
  if (Interpreter::contains(pc)) {
    // We don't want to preempt when returning from some useful VM function, and certainly not when inside one.
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
static bool is_safe_to_preempt(JavaThread* thread) {
  if (!thread->has_last_Java_frame()) {
    return false;
  }

  LogTarget(Trace, jvmcont, preempt) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    frame f = thread->last_frame();
    ls.print("is_safe_to_preempt %sSAFEPOINT ", Interpreter::contains(f.pc()) ? "INTERPRETER " : "");
    f.print_on(&ls);
  }

  if (!is_safe_pc_to_preempt(thread->last_Java_pc())) return false;
  if (!is_safe_frame_to_preempt(thread)) return false;
  return true;
}

/////////////// THAW ////

// make room on the stack for thaw
// returns the size in bytes, or 0 on failure
static inline int prepare_thaw0(JavaThread* thread, bool return_barrier) {
  log_develop_trace(jvmcont)("~~~~ prepare_thaw return_barrier: %d", return_barrier);

  assert (thread == JavaThread::current(), "");

  oop cont = thread->last_continuation()->cont_oop();
  assert (cont == ContinuationHelper::get_continuation(thread), "");
  assert (VERIFY_CONTINUATION(cont), "");

  stackChunkOop chunk = jdk_internal_vm_Continuation::tail(cont);
  assert (chunk != nullptr, "");
  if (UNLIKELY(chunk->is_empty())) {
    chunk = chunk->parent();
    jdk_internal_vm_Continuation::set_tail(cont, chunk);
  }
  assert (chunk != nullptr, "");
  assert (!chunk->is_empty(), "");
  assert (VERIFY_STACK_CHUNK(chunk), "");

  int size = chunk->max_size();
  guarantee (size > 0, "");

  size += 2*ContinuationHelper::frame_metadata; // 2x because we might want to add a frame for StubRoutines::cont_interpreter_forced_preempt_return()
  size += ContinuationHelper::align_wiggle; // just in case we have an interpreted entry after which we need to align
  size <<= LogBytesPerWord;

  const address bottom = (address)thread->last_continuation()->entry_sp();
  if (!ContinuationHelper::stack_overflow_check(thread, size + 300, bottom)) {
    return 0;
  }

  log_develop_trace(jvmcont)("prepare_thaw bottom: " INTPTR_FORMAT " top: " INTPTR_FORMAT " size: %d",
                              p2i(bottom), p2i(bottom - size), size);
  return size;
}

class ThawBase { // avoids the template of the Thaw class
protected:
  static inline void derelativize_interpreted_frame_metadata(const frame& hf, const frame& f);
  static inline void set_interpreter_frame_bottom(const frame& f, intptr_t* bottom);
};

template <typename ConfigT>
class Thaw : public ThawBase {
private:
  JavaThread* _thread;
  ContMirror& _cont;

  intptr_t* _fastpath;
  bool _barriers;
  intptr_t* _top_unextended_sp;
  int _align_size;

  StackChunkFrameStream<chunk_frames::MIXED> _stream;

  NOT_PRODUCT(int _frames;)

  inline frame new_entry_frame();
  template<typename FKind> frame new_frame(const frame& hf, frame& caller, bool bottom);
  template<typename FKind, bool bottom> inline void patch_pd(frame& f, const frame& sender);
  inline intptr_t* align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom);
  void patch_chunk_pd(intptr_t* sp);
  inline void prefetch_chunk_pd(void* start, int size_words);
  intptr_t* push_interpreter_return_frame(intptr_t* sp);
  void maybe_set_fastpath(intptr_t* sp) { if (sp > _fastpath) _fastpath = sp; }

public:
  DEBUG_ONLY(int _mode;) // TODO: remove
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

    assert (VERIFY_CONTINUATION(_cont.mirror()), "");
    assert (!jdk_internal_vm_Continuation::done(_cont.mirror()), "");
    assert (!_cont.is_empty(), "");

    stackChunkOop chunk = _cont.tail();
    assert (chunk != nullptr && !chunk->is_empty(), ""); // guaranteed by prepare_thaw

    _barriers = ConfigT::requires_barriers(chunk);
    return (LIKELY(can_thaw_fast(chunk))) ? thaw_fast(chunk)
                                          : thaw_slow(chunk, kind != thaw_top);
  }

  NOINLINE intptr_t* thaw_fast(stackChunkOop chunk) {
    assert (chunk != (oop) nullptr, "");
    assert (chunk == _cont.tail(), "");
    assert (!chunk->is_empty(), "");
    assert (!chunk->has_mixed_frames(), "");
    assert (!chunk->requires_barriers(), "");
    assert (!chunk->has_bitmap(), "");
    assert (!_thread->is_interp_only_mode(), "");

    // TODO: explain why we're not setting the tail

    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("thaw_fast");
      chunk->print_on(true, &ls);
    }

    static const int threshold = 500; // words

    int sp = chunk->sp();
    int size = chunk->stack_size() - sp; // this initial size could be reduced if it's a partial thaw
    int argsize;

    intptr_t* const chunk_sp = chunk->start_address() + sp;

    bool partial, empty;
    if (LIKELY(!TEST_THAW_ONE_CHUNK_FRAME && (size < threshold))) {
      DEBUG_ONLY(_mode = 1;)
      prefetch_chunk_pd(chunk->start_address(), size); // prefetch anticipating memcpy starting at highest address

      partial = false;

      argsize = chunk->argsize();
      empty = true;

      chunk->set_sp(chunk->stack_size());
      chunk->set_argsize(0);
      // chunk->clear_flags();
      chunk->set_max_size(0);
      log_develop_trace(jvmcont)("set max_size: 0");
      // chunk->set_pc(nullptr);
    } else { // thaw a single frame
      DEBUG_ONLY(_mode = 2;)
      partial = true;

      StackChunkFrameStream<chunk_frames::COMPILED_ONLY> f(chunk);
      assert (chunk_sp == f.sp() && chunk_sp == f.unextended_sp(), "");
      size = f.cb()->frame_size();
      argsize = f.stack_argsize();
      f.next(SmallRegisterMap::instance);
      empty = f.is_done();
      assert (!empty || argsize == chunk->argsize(), "");

      if (empty) {
        chunk->set_sp(chunk->stack_size());
        chunk->set_argsize(0);
        chunk->set_max_size(0);
        log_develop_trace(jvmcont)("set max_size: 0");
        // chunk->set_pc(nullptr);
      } else {
        chunk->set_sp(chunk->sp() + size);
        chunk->set_max_size(chunk->max_size() - size);
        address top_pc = *(address*)(chunk_sp + size - frame::sender_sp_ret_address_offset());
        chunk->set_pc(top_pc);
      }
      assert (empty == chunk->is_empty(), "");
      size += argsize;
    }

    const bool is_last = empty && chunk->is_parent_null<typename ConfigT::OopT>();

    log_develop_trace(jvmcont)("thaw_fast partial: %d is_last: %d empty: %d size: %d argsize: %d",
                                partial, is_last, empty, size, argsize);

    intptr_t* stack_sp = _cont.entrySP();
    intptr_t* bottom_sp = ContinuationHelper::frame_align_pointer(stack_sp - argsize);

    stack_sp -= size;
    assert (argsize != 0 || stack_sp == ContinuationHelper::frame_align_pointer(stack_sp), "");
    stack_sp = ContinuationHelper::frame_align_pointer(stack_sp);

    intptr_t* from = chunk_sp - ContinuationHelper::frame_metadata;
    intptr_t* to   = stack_sp - ContinuationHelper::frame_metadata;
    copy_from_chunk(from, to, size + ContinuationHelper::frame_metadata);
    assert (_cont.entrySP() - 1 <= to + size + ContinuationHelper::frame_metadata
              && to + size + ContinuationHelper::frame_metadata <= _cont.entrySP(), "");
    assert (argsize != 0 || to + size + ContinuationHelper::frame_metadata == _cont.entrySP(), "");

    assert (!is_last || argsize == 0, "");
    _cont.set_argsize(argsize);
    log_develop_trace(jvmcont)("setting entry argsize: %d", _cont.argsize());
    patch_chunk(bottom_sp, is_last);

    DEBUG_ONLY(address pc = *(address*)(bottom_sp - frame::sender_sp_ret_address_offset());)
    assert (is_last ? CodeCache::find_blob(pc)->as_compiled_method()->method()->is_continuation_enter_intrinsic()
                    : pc == StubRoutines::cont_returnBarrier(), "is_last: %d", is_last);
    assert (is_last == _cont.is_empty(), "");
    assert(_cont.chunk_invariant(tty), "");

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
  intptr_t* sp0 = stack_sp;
  ContinuationHelper::set_anchor(_thread, sp0);
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    print_frames(_thread, &ls);
  }
  if (LoomDeoptAfterThaw) {
    do_deopt_after_thaw(_thread);
  }
  ContinuationHelper::clear_anchor(_thread);
#endif

    return stack_sp;
  }

  template <copy_alignment aligned = copy_alignment::DWORD_ALIGNED>
  void copy_from_chunk(intptr_t* from, intptr_t* to, int size) {
    assert (to + size <= _cont.entrySP(), "");
    _cont.tail()->template copy_from_chunk_to_stack<aligned>(from, to, size);
    CONT_JFR_ONLY(_cont.record_size_copied(size);)
  }

  void patch_chunk(intptr_t* sp, bool is_last) {
    log_develop_trace(jvmcont)("thaw_fast patching -- sp: " INTPTR_FORMAT, p2i(sp));

    address pc = !is_last ? StubRoutines::cont_returnBarrier() : _cont.entryPC();
    *(address*)(sp - frame::sender_sp_ret_address_offset()) = pc;

    // patch_chunk_pd(sp); -- TODO: If not needed - remove method; it's not used elsewhere
  }

  NOINLINE intptr_t* thaw_slow(stackChunkOop chunk, bool return_barrier) {
    assert (!_cont.is_empty(), "");
    assert (chunk != nullptr, "");
    assert (!chunk->is_empty(), "");

    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("thaw slow return_barrier: %d " INTPTR_FORMAT, return_barrier, p2i((stackChunkOopDesc*)chunk));
      chunk->print_on(true, &ls);
    }

    EventContinuationThawOld e;
    if (e.should_commit()) {
      e.set_id(cast_from_oop<u8>(_cont.mirror()));
      e.commit();
    }

    DEBUG_ONLY(_mode = 3;)
    DEBUG_ONLY(_frames = 0;)
    _align_size = 0;
    int num_frames = (return_barrier ? 1 : 2);
    bool last_interpreted = chunk->has_mixed_frames() && Interpreter::contains(chunk->pc());

    _stream = StackChunkFrameStream<chunk_frames::MIXED>(chunk);
    _top_unextended_sp = _stream.unextended_sp();

    frame hf = _stream.to_frame();
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("top hframe before (thaw):");
      assert(hf.is_interpreted_heap_frame(), "should have created a relative frame");
      hf.print_on(&ls);
    }

    frame f;
    thaw(hf, f, num_frames, true);
    finish_thaw(f); // f is now the topmost thawed frame
    _cont.write();

    assert(_cont.chunk_invariant(tty), "");

    if (!return_barrier) JVMTI_continue_cleanup(_thread);

    _thread->set_cont_fastpath(_fastpath);

    intptr_t* sp = f.sp();

  #ifdef ASSERT
    {
      frame f(sp);
      if (lt.develop_is_enabled()) {
        LogStream ls(lt);
        ls.print_cr("Jumping to frame (thaw): [" JLONG_FORMAT "]", java_tid(_thread));
        f.print_on(&ls);
      }
      assert (f.is_interpreted_frame() || f.is_compiled_frame() || f.is_safepoint_blob_frame(), "");
    }
  #endif

    if (last_interpreted && _cont.is_preempted()) {
      assert (f.pc() == *(address*)(sp - frame::sender_sp_ret_address_offset()), "");
      assert (Interpreter::contains(f.pc()), "");
      sp = push_interpreter_return_frame(sp);
    }

    return sp;
  }

  void thaw(const frame& hf, frame& caller, int num_frames, bool top) {
    log_develop_debug(jvmcont)("thaw num_frames: %d", num_frames);
    assert(!_cont.is_empty(), "no more frames");
    assert (num_frames > 0 && !hf.is_empty(), "");

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
      InstanceStackChunkKlass::do_barriers<InstanceStackChunkKlass::barrier_type::STORE>(_cont.tail(), _stream, SmallRegisterMap::instance);
    }

    int argsize = _stream.stack_argsize();

    _stream.next(SmallRegisterMap::instance);
    assert (_stream.to_frame().is_empty() == _stream.is_done(), "");

    // we never leave a compiled caller of an interpreted frame as the top frame in the chunk
    // as it makes detecting that situation and adjusting unextended_sp tricky
    if (num_frames == 1 && !_stream.is_done() && FKind::interpreted && _stream.is_compiled()) {
      log_develop_trace(jvmcont)("thawing extra compiled frame to not leave a compiled interpreted-caller at top");
      num_frames++;
    }

    if (num_frames == 1 || _stream.is_done()) { // end recursion
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
      chunk->set_argsize(0);
      chunk->set_sp(chunk->stack_size());
      chunk->set_pc(nullptr);
    }
    assert(_stream.is_done() == chunk->is_empty(), "");

    int delta = _stream.unextended_sp() - _top_unextended_sp;
    chunk->set_max_size(chunk->max_size() - delta);

    _cont.set_argsize(FKind::interpreted ? 0 : argsize);
    entry = new_entry_frame();

    assert (entry.sp() == _cont.entrySP(), "");
    assert (Continuation::is_continuation_enterSpecial(entry), "");
    assert (_cont.is_entry_frame(entry), "");
  }

  inline void before_thaw_java_frame(const frame& hf, const frame& caller, bool bottom, int num_frame) {
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("======== THAWING FRAME: %d", num_frame);
      assert(hf.is_interpreted_heap_frame(), "should be");
      hf.print_on(&ls);
    }
    assert (bottom == _cont.is_entry_frame(caller), "bottom: %d is_entry_frame: %d", bottom, _cont.is_entry_frame(hf));
  }

  inline void after_thaw_java_frame(const frame& f, bool bottom) {
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("thawed frame:");
      f.print_on(&ls);
    }
  }

  template<typename FKind, bool bottom>
  inline void patch(frame& f, const frame& caller) {
    if (bottom) {
      FKind::patch_pc(caller, _cont.is_empty() ? caller.raw_pc() : StubRoutines::cont_returnBarrier());
    }

    patch_pd<FKind, bottom>(f, caller); // TODO: reevaluate if and when this is necessary -only bottom & interpreted caller?

    if (FKind::interpreted) {
      Interpreted::patch_sender_sp(f, caller.unextended_sp());
    }

    assert (!bottom || !_cont.is_empty() || Continuation::is_continuation_entry_frame(f, nullptr), "");
    assert (!bottom || (_cont.is_empty() != Continuation::is_cont_barrier_frame(f)), "");
  }

  void clear_bitmap_bits(intptr_t* start, int range) {
    // we need to clear the bits that correspond to arguments as they reside in the caller frame
    log_develop_trace(jvmcont)("clearing bitmap for " INTPTR_FORMAT " - " INTPTR_FORMAT, p2i(start), p2i(start+range));
    stackChunkOop chunk = _cont.tail();
    chunk->bitmap().clear_range(chunk->bit_index_for((typename ConfigT::OopT*)start),
                                chunk->bit_index_for((typename ConfigT::OopT*)(start+range)));
  }

  NOINLINE void recurse_thaw_interpreted_frame(const frame& hf, frame& caller, int num_frames) {
    assert (hf.is_interpreted_frame(), "");

    const bool bottom = recurse_thaw_java_frame<Interpreted>(caller, num_frames);

    DEBUG_ONLY(before_thaw_java_frame(hf, caller, bottom, num_frames);)

    frame f = new_frame<Interpreted>(hf, caller, bottom);

    intptr_t* const vsp = f.sp();
    intptr_t* const hsp = hf.unextended_sp();
    intptr_t* const frame_bottom = Interpreted::frame_bottom(f);

    assert(hf.is_interpreted_heap_frame(), "should be");
    const int fsize = Interpreted::frame_bottom(hf) - hsp;

    assert (!bottom || vsp + fsize >= _cont.entrySP() - 2, "");
    assert (!bottom || vsp + fsize <= _cont.entrySP(), "");

    assert (Interpreted::frame_bottom(f) == vsp + fsize, "");

    // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
    const int locals = hf.interpreter_frame_method()->max_locals();
    assert(hf.is_interpreted_heap_frame(), "should be");
    assert(!f.is_interpreted_heap_frame(), "should not be");

    copy_from_chunk<copy_alignment::WORD_ALIGNED>(Interpreted::frame_bottom(hf) - locals,
                                          Interpreted::frame_bottom(f) - locals, locals); // copy locals
    copy_from_chunk<copy_alignment::WORD_ALIGNED>(hsp, vsp, fsize - locals); // copy rest

    set_interpreter_frame_bottom(f, frame_bottom); // the copy overwrites the metadata
    derelativize_interpreted_frame_metadata(hf, f);
    bottom ? patch<Interpreted, true>(f, caller) : patch<Interpreted, false>(f, caller);

  #ifdef ASSERT
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      print_frame_layout(f, &ls);
    }
  #endif

    assert(f.is_interpreted_frame_valid(_cont.thread()), "invalid thawed frame");
    assert(Interpreted::frame_bottom(f) <= Frame::frame_top(caller), "");

    CONT_JFR_ONLY(_cont.record_interpreted_frame();)

    maybe_set_fastpath(f.sp());

    if (!bottom) {
      // can only fix caller once this frame is thawed (due to callee saved regs)
      InstanceStackChunkKlass::fix_thawed_frame(_cont.tail(), caller, SmallRegisterMap::instance);
    } else if (_cont.tail()->has_bitmap() && locals > 0) {
      assert(hf.is_interpreted_heap_frame(), "should be");
      clear_bitmap_bits(Interpreted::frame_bottom(hf) - locals, locals);
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
      _align_size += ContinuationHelper::align_wiggle; // we add one whether or not we've aligned because we add it in freeze_interpreted_frame
    }

    frame f = new_frame<Compiled>(hf, caller, bottom);
    intptr_t* const vsp = f.sp();
    intptr_t* const hsp = hf.unextended_sp();

    int fsize = Compiled::size(hf);
    const int added_argsize = (bottom || caller.is_interpreted_frame()) ? hf.compiled_frame_stack_argsize() : 0;
    fsize += added_argsize;
    assert (fsize <= (int)(caller.unextended_sp() - f.unextended_sp()), "");

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

    if (f.is_deoptimized_frame()) {
      maybe_set_fastpath(f.sp());
    } else if (_thread->is_interp_only_mode()
                || (_cont.is_preempted() && f.cb()->as_compiled_method()->is_marked_for_deoptimization())) {
      // The caller of the safepoint stub when the continuation is preempted is not at a call instruction, and so
      // cannot rely on nmethod patching for deopt.

      log_develop_trace(jvmcont)("Deoptimizing thawed frame");
      DEBUG_ONLY(Frame::patch_pc(f, nullptr));

      f.deoptimize(nullptr); // we're assuming there are no monitors; this doesn't revoke biased locks
      assert (f.is_deoptimized_frame() && Frame::is_deopt_return(f.raw_pc(), f), "");
      maybe_set_fastpath(f.sp());
    }

    if (!bottom) {
      // can only fix caller once this frame is thawed (due to callee saved regs)
      InstanceStackChunkKlass::fix_thawed_frame(_cont.tail(), caller, SmallRegisterMap::instance);
    } else if (_cont.tail()->has_bitmap() && added_argsize > 0) {
      clear_bitmap_bits(hsp + Compiled::size(hf), added_argsize);
    }

    DEBUG_ONLY(after_thaw_java_frame(f, bottom);)
    caller = f;
  }

  void recurse_thaw_stub_frame(const frame& hf, frame& caller, int num_frames) {
    DEBUG_ONLY(_frames++;)

    {
      RegisterMap map(nullptr, true, false, false);
      map.set_include_argument_oops(false);
      _stream.next(&map);
      assert (!_stream.is_done(), "");
      if (UNLIKELY(_barriers)) { // we're now doing this on the stub's caller
        InstanceStackChunkKlass::do_barriers<InstanceStackChunkKlass::barrier_type::STORE>(_cont.tail(), _stream, &map);
      }
      assert (!_stream.is_done(), "");
    }

    recurse_thaw_compiled_frame(_stream.to_frame(), caller, num_frames); // this could be deoptimized

    DEBUG_ONLY(before_thaw_java_frame(hf, caller, false, num_frames);)

    assert(Frame::is_stub(hf.cb()), "");
    assert (caller.sp() == caller.unextended_sp(), "");
    assert (!caller.is_interpreted_frame(), "");

    int fsize = StubF::size(hf);

    frame f = new_frame<StubF>(hf, caller, false);
    intptr_t* vsp = f.sp();
    intptr_t* hsp = hf.sp();

    copy_from_chunk(hsp - ContinuationHelper::frame_metadata, vsp - ContinuationHelper::frame_metadata,
                    fsize + ContinuationHelper::frame_metadata);

    { // can only fix caller once this frame is thawed (due to callee saved regs)
      RegisterMap map(nullptr, true, false, false); // map.clear();
      map.set_include_argument_oops(false);
      f.oop_map()->update_register_map(&f, &map);
      ContinuationHelper::update_register_map_with_callee(caller, &map);
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
      chunk->set_max_size(chunk->max_size() - _align_size);
    }
    assert (chunk->is_empty() == (chunk->max_size() == 0), "");

    if ((intptr_t)f.sp() % 16 != 0) {
      assert (f.is_interpreted_frame(), "");
      f.set_sp(f.sp() - 1);
    }
    push_return_frame(f);
    InstanceStackChunkKlass::fix_thawed_frame(chunk, f, SmallRegisterMap::instance); // can only fix caller after push_return_frame (due to callee saved regs)

    assert (_cont.is_empty() == _cont.last_frame().is_empty(), "");

    log_develop_trace(jvmcont)("thawed %d frames", _frames);

    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("top hframe after (thaw):");
      assert(_cont.last_frame().is_interpreted_heap_frame(), "should be");
      _cont.last_frame().print_on(&ls);
    }
  }

  void push_return_frame(frame& f) { // see generate_cont_thaw
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
    assert (!f.is_compiled_frame() || f.is_deoptimized_frame() == (f.pc() != f.raw_pc()), "");

    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      ls.print_cr("push_return_frame");
      f.print_on(&ls);
    }

    intptr_t* sp = f.sp();
    address pc = f.raw_pc();
    *(address*)(sp - frame::sender_sp_ret_address_offset()) = pc;
    Frame::patch_pc(f, pc); // in case we want to deopt the frame in a full transition, this is checked.
    ContinuationHelper::push_pd(f);

    assert(Frame::assert_frame_laid_out(f), "");
  }

  static void JVMTI_continue_cleanup(JavaThread* thread) {
#if INCLUDE_JVMTI
    invalidate_JVMTI_stack(thread);
#endif // INCLUDE_JVMTI
  }
};

// returns new top sp
// called after preparations (stack overflow check and making room)
template<typename ConfigT>
static inline intptr_t* thaw0(JavaThread* thread, const thaw_kind kind) {
  LogTarget(Trace, jvmcont) lt;
#ifdef ASSERT
  LogStream ls(lt);
#endif
  // NoSafepointVerifier nsv;
  CONT_JFR_ONLY(EventContinuationThaw event;)

  if (kind != thaw_top) { log_develop_trace(jvmcont)("== RETURN BARRIER"); }
  log_develop_trace(jvmcont)("~~~~ thaw kind: %d sp: " INTPTR_FORMAT, kind, p2i(thread->last_continuation()->entry_sp()));

  assert (thread == JavaThread::current(), "");

  oop oopCont = thread->last_continuation()->cont_oop();

  assert (!jdk_internal_vm_Continuation::done(oopCont), "");
  assert (oopCont == ContinuationHelper::get_continuation(thread), "");
  assert (VERIFY_CONTINUATION(oopCont), "");

  ContMirror cont(thread, oopCont);
  log_develop_debug(jvmcont)("THAW #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

#ifdef ASSERT
  if (lt.develop_is_enabled()) {
    ContinuationHelper::set_anchor_to_entry(thread, cont.entry());
    print_frames(thread, &ls);
  }
#endif

  Thaw<ConfigT> thw(thread, cont);
  intptr_t* const sp = thw.thaw(kind);
  assert ((intptr_t)sp % 16 == 0, "");

  thread->reset_held_monitor_count();

  assert (VERIFY_CONTINUATION(cont.mirror()), "");

#ifdef ASSERT
  intptr_t* sp0 = sp;
  address pc0 = *(address*)(sp - frame::sender_sp_ret_address_offset());
  if (pc0 == StubRoutines::cont_interpreter_forced_preempt_return()) {
    sp0 += ContinuationHelper::frame_metadata; // see push_interpreter_return_frame
  }
  ContinuationHelper::set_anchor(thread, sp0);
  if (lt.develop_is_enabled()) print_frames(thread, &ls);
  if (LoomVerifyAfterThaw) {
    assert(do_verify_after_thaw(thread, thw._mode, thw.barriers(), cont.tail(), tty), "");
  }
  assert (ContinuationEntry::assert_entry_frame_laid_out(thread), "");
  ContinuationHelper::clear_anchor(thread);

  if (lt.develop_is_enabled()) {
    ls.print_cr("Jumping to frame (thaw):");
    frame(sp).print_on(&ls);
  }
#endif

  CONT_JFR_ONLY(cont.post_jfr_event(&event, thread);)

  assert (VERIFY_CONTINUATION(cont.mirror()), "");
  log_develop_debug(jvmcont)("=== End of thaw #" INTPTR_FORMAT, cont.hash());

  return sp;
}

#ifdef ASSERT
static void do_deopt_after_thaw(JavaThread* thread) {
  int i = 0;
  StackFrameStream fst(thread, true, false);
  fst.register_map()->set_include_argument_oops(false);
  ContinuationHelper::update_register_map_with_callee(*fst.current(), fst.register_map());
  for (; !fst.is_done(); fst.next()) {
    if (fst.current()->cb()->is_compiled()) {
      CompiledMethod* cm = fst.current()->cb()->as_compiled_method();
      if (!cm->method()->is_continuation_enter_intrinsic())
        cm->make_deoptimized();
    }
  }
}

static bool is_good_oop(oop o) {
  return dbg_is_safe(o, -1) && dbg_is_safe(o->klass(), -1) && oopDesc::is_oop(o) && o->klass()->is_klass();
}

class ThawVerifyOopsClosure: public OopClosure {
  intptr_t* _p;
  outputStream* _st;
public:
  ThawVerifyOopsClosure(outputStream* st) : _p(nullptr), _st(st) {}
  intptr_t* p() { return _p; }
  void reset() { _p = nullptr; }

  virtual void do_oop(oop* p) {
    oop o = *p;
    if (o == (oop)nullptr || is_good_oop(o)) return;
    _p = (intptr_t*)p;
    _st->print_cr("*** non-oop " PTR_FORMAT " found at " PTR_FORMAT, p2i(*p), p2i(p));
  }
  virtual void do_oop(narrowOop* p) {
    oop o = RawAccess<>::oop_load(p);
    if (o == (oop)nullptr || is_good_oop(o)) return;
    _p = (intptr_t*)p;
    _st->print_cr("*** (narrow) non-oop %x found at " PTR_FORMAT, (int)(*p), p2i(p));
  }
};

static bool do_verify_after_thaw(JavaThread* thread, int mode, bool barriers, stackChunkOop chunk, outputStream* st) {
  assert(thread->has_last_Java_frame(), "");

  ResourceMark rm;
  ThawVerifyOopsClosure cl(st);
  CodeBlobToOopClosure cf(&cl, false);

  StackFrameStream fst(thread, true, false);
  fst.register_map()->set_include_argument_oops(false);
  ContinuationHelper::update_register_map_with_callee(*fst.current(), fst.register_map());
  for (; !fst.is_done() && !Continuation::is_continuation_enterSpecial(*fst.current()); fst.next()) {
    if (fst.current()->cb()->is_compiled() && fst.current()->cb()->as_compiled_method()->is_marked_for_deoptimization()) {
      st->print_cr(">>> do_verify_after_thaw deopt");
      fst.current()->deoptimize(nullptr);
      fst.current()->print_on(st);
    }

    fst.current()->oops_do(&cl, &cf, fst.register_map());
    if (cl.p() != nullptr) {
      frame fr = *fst.current();
      st->print_cr("Failed for frame mode: %d barriers: %d %d", mode, barriers, chunk->requires_barriers());
      fr.print_on(st);
      if (!fr.is_interpreted_frame()) {
        st->print_cr("size: %d argsize: %d", NonInterpretedUnknown::size(fr), NonInterpretedUnknown::stack_argsize(fr));
      }
  #ifdef ASSERT
      VMReg reg = fst.register_map()->find_register_spilled_here(cl.p(), fst.current()->sp());
      if (reg != nullptr) st->print_cr("Reg %s %d", reg->name(), reg->is_stack() ? (int)reg->reg2stack() : -99);
  #endif
      cl.reset();
      DEBUG_ONLY(thread->print_frame_layout();)
      chunk->print_on(true, st);
      return false;
    }
  }
  return true;
}
#endif

#include CPU_HEADER_INLINE(continuation)

/////////////////////////////////////////////

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

void ContinuationEntry::flush_stack_processing(JavaThread* thread) const {
  ContinuationHelper::maybe_flush_stack_processing(thread, this);
}

/////////////////////////////////////////////

#ifdef ASSERT
bool ContinuationEntry::assert_entry_frame_laid_out(JavaThread* thread) {
  assert (thread->has_last_Java_frame(), "Wrong place to use this assertion");

  ContinuationEntry* cont =
    Continuation::get_continuation_entry_for_continuation(thread, ContinuationHelper::get_continuation(thread));
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
    for (f = thread->last_frame();
         !f.is_first_frame() && f.sp() <= unextended_sp && !Continuation::is_continuation_enterSpecial(f);
         f = f.sender(&map)) {
      interpreted_bottom = f.is_interpreted_frame();
    }
    assert (Continuation::is_continuation_enterSpecial(f), "");
    sp = interpreted_bottom ? f.sp() : cont->bottom_sender_sp();
  }

  assert (sp != nullptr && sp <= cont->entry_sp(), "");
  address pc = *(address*)(sp - frame::sender_sp_ret_address_offset());

  if (pc != StubRoutines::cont_returnBarrier()) {
    CodeBlob* cb = pc != nullptr ? CodeCache::find_blob(pc) : nullptr;
    assert (cb->as_compiled_method()->method()->is_continuation_enter_intrinsic(), "");
  }

  return true;
}
#endif

#ifndef PRODUCT
static jlong java_tid(JavaThread* thread) {
  return java_lang_Thread::thread_id(thread->threadObj());
}

static void print_frame_layout(const frame& f, outputStream* st) {
  ResourceMark rm;
  FrameValues values;
  assert (f.get_cb() != nullptr, "");
  RegisterMap map(f._pointers == frame::addressing::RELATIVE ?
                     (JavaThread*)nullptr :
                     JavaThread::current(), true, false, false);
  map.set_include_argument_oops(false);
  map.set_skip_missing(true);
  frame::update_map_with_saved_link(&map, Frame::callee_link_address(f));
  const_cast<frame&>(f).describe(values, 0, &map);
  values.print_on((JavaThread*)nullptr, st);
}

static void print_frames(JavaThread* thread, outputStream* st) {
  st->print_cr("------- frames ---------");
  if (!thread->has_last_Java_frame()) st->print_cr("NO ANCHOR!");

  RegisterMap map(thread, true, true, false);
  map.set_include_argument_oops(false);

  if (false) {
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) f.print_on(st);
  } else {
    map.set_skip_missing(true);
    ResetNoHandleMark rnhm;
    ResourceMark rm;
    HandleMark hm(Thread::current());
    FrameValues values;

    int i = 0;
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) f.describe(values, i++, &map);
    values.print_on(thread, st);
  }

  st->print_cr("======= end frames =========");
}
#endif

static address thaw_entry   = nullptr;
static address freeze_entry = nullptr;

address Continuation::thaw_entry() {
  assert (::thaw_entry != nullptr,  "");
  return ::thaw_entry;
}

address Continuation::freeze_entry() {
  assert (::freeze_entry != nullptr, "");
  return ::freeze_entry;
}

class ConfigResolve {
public:
  static void resolve() { resolve_compressed(); }

  static void resolve_compressed() {
    UseCompressedOops ? resolve_gc<true>()
                      : resolve_gc<false>();
  }

private:
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
    typedef Config<use_compressed ? oop_kind::NARROW : oop_kind::WIDE, BarrierSetT> SelectedConfigT;

    freeze_entry = (address)freeze<SelectedConfigT>;
    preempt_freeze = SelectedConfigT::freeze_preempt;

    // if we want, we could templatize by king and have three different that entries
    thaw_entry   = (address)thaw<SelectedConfigT>;
  }
};

void continuations_init() { Continuations::init(); }

void Continuations::init() {
  Continuation::init();
}

// While virtual threads are in Preview, there are some VM mechanisms we disable if continuations aren't used
// See NMethodSweeper::do_stack_scanning and nmethod::is_not_on_continuation_stack
bool Continuations::enabled() {
  return vmClasses::Continuation_klass()->is_initialized(); // Arguments::enable_preview();
}

void Continuation::init() {
  ConfigResolve::resolve();
}

#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod CONT_methods[] = {
    {CC"tryForceYield0",   CC"(Ljava/lang/Thread;)I",                  FN_PTR(CONT_TryForceYield0)},
    {CC"isPinned0",        CC"(Ljdk/internal/vm/ContinuationScope;)I", FN_PTR(CONT_isPinned0)},
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls) {
    Thread* thread = Thread::current();
    assert(thread->is_Java_thread(), "");
    ThreadToNativeFromVM trans((JavaThread*)thread);
    int status = env->RegisterNatives(cls, CONT_methods, sizeof(CONT_methods)/sizeof(JNINativeMethod));
    guarantee(status == JNI_OK && !env->ExceptionOccurred(), "register jdk.internal.vm.Continuation natives");
}

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
#include "classfile/javaClasses.inline.hpp"
#include "classfile/vmSymbols.hpp"
#include "code/codeCache.inline.hpp"
#include "code/compiledMethod.inline.hpp"
#include "code/scopeDesc.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/oopMap.inline.hpp"
#include "gc/shared/gc_globals.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/memAllocator.hpp"
#include "gc/shared/oopStorage.hpp"
#include "gc/shared/threadLocalAllocBuffer.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/linkResolver.hpp"
#include "interpreter/oopMapCache.hpp"
#include "jfr/jfrEvents.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "metaprogramming/conditional.hpp"
#include "oops/access.inline.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/stackChunkOop.inline.hpp"
#include "oops/weakHandle.inline.hpp"
#include "prims/jvmtiDeferredUpdates.hpp"
#include "prims/jvmtiThreadState.hpp"
#include "runtime/arguments.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/frame_helpers.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/keepStackGCProcessed.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/prefetch.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/smallRegisterMap.inline.hpp"
#include "runtime/stackChunkFrameStream.inline.hpp"
#include "runtime/stackFrameStream.inline.hpp"
#include "runtime/stackOverflow.hpp"
#include "runtime/stackWatermarkSet.inline.hpp"
#include "runtime/vframe.inline.hpp"
#include "runtime/vframe_hp.hpp"
#include "utilities/copy.hpp"
#include "utilities/debug.hpp"
#include "utilities/exceptions.hpp"
#include "utilities/macros.hpp"

#define CONT_JFR false // emit low-level JFR events that count slow/fast path for continuation peformance debugging only
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

static void verify_continuation(oop continuation) { Continuation::debug_verify_continuation(continuation); }
static void verify_stack_chunk(oop chunk) { InstanceStackChunkKlass::verify(chunk); }

static void do_deopt_after_thaw(JavaThread* thread);
static bool do_verify_after_thaw(JavaThread* thread, bool barriers, stackChunkOop chunk, outputStream* st);
static void log_frames(JavaThread* thread);
#else
static void verify_continuation(oop continuation) { }
static void verify_stack_chunk(oop chunk) { }
#endif

#ifndef PRODUCT
static void print_frame_layout(const frame& f, outputStream* st = tty);
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
};

class ContinuationHelper {
public:
  static const int frame_metadata; // size, in words, of frame metadata (e.g. pc and link)
  static const int align_wiggle; // size, in words, of maximum shift in frame position due to alignment

  static oop get_continuation(JavaThread* thread);
  static bool stack_overflow_check(JavaThread* thread, int size, address sp);

  static inline void clear_anchor(JavaThread* thread);
  static void set_anchor(JavaThread* thread, intptr_t* sp);
  static void set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp);
  static void set_anchor_to_entry(JavaThread* thread, ContinuationEntry* entry);
  static void set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* entry);

  template<typename FKind> static void update_register_map(const frame& f, RegisterMap* map);
  static void update_register_map_with_callee(const frame& f, RegisterMap* map);

  static inline void push_pd(const frame& f);

  static inline void maybe_flush_stack_processing(JavaThread* thread, const ContinuationEntry* entry);
  static inline void maybe_flush_stack_processing(JavaThread* thread, intptr_t* sp);
  static NOINLINE void flush_stack_processing(JavaThread* thread, intptr_t* sp);

  static inline int frame_align_words(int size);
  static inline intptr_t* frame_align_pointer(intptr_t* sp);
};

oop ContinuationHelper::get_continuation(JavaThread* thread) {
  assert(thread != nullptr, "");
  assert(thread->threadObj() != nullptr, "");
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
  assert(pc != nullptr, "");

  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(sp);
  anchor->set_last_Java_pc(pc);
  set_anchor_pd(anchor, sp);

  assert(thread->has_last_Java_frame(), "");
  assert(thread->last_frame().cb() != nullptr, "");
}

void ContinuationHelper::set_anchor_to_entry(JavaThread* thread, ContinuationEntry* entry) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  anchor->set_last_Java_sp(entry->entry_sp());
  anchor->set_last_Java_pc(entry->entry_pc());
  set_anchor_to_entry_pd(anchor, entry);

  assert(thread->has_last_Java_frame(), "");
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
  for (StackFrameStream fst(thread, true, true); fst.current()->sp() <= sp; fst.next()) {
    ;
  }
}

/////////////////////////////////////////////////////////////////////

// Intermediary to the jdk.internal.vm.Continuation objects and ContinuationEntry
// This object is created when we begin a operation for a continuation, and is destroyed when the operation completes.
// Contents are read from the Java object at the entry points of this module, and written at exit or calls into Java
class ContinuationWrapper : public StackObj {
private:
  JavaThread* const  _thread;   // Thread being frozen/thawed
  ContinuationEntry* _entry;
  oop                _continuation;  // jdk.internal.vm.Continuation instance
  stackChunkOop      _tail;

#if CONT_JFR // Profiling data for the JFR event
  short _e_size;
  short _e_num_interpreted_frames;
#endif

  ContinuationWrapper(const ContinuationWrapper& cont); // no copy constructor

public:
  ContinuationWrapper(JavaThread* thread, oop continuation);
  ContinuationWrapper(oop continuation);
  ContinuationWrapper(const RegisterMap* map);

  inline void post_safepoint(Handle conth);

  JavaThread* thread() const         { return _thread; }
  oop continuation()                 { return _continuation; }
  stackChunkOop tail() const         { return _tail; }
  void set_tail(stackChunkOop chunk) { _tail = chunk; }

  oop parent()                   { return jdk_internal_vm_Continuation::parent(_continuation); }
  bool is_preempted()            { return jdk_internal_vm_Continuation::is_preempted(_continuation); }
  void set_preempted(bool value) { jdk_internal_vm_Continuation::set_preempted(_continuation, value); }
  void read()                    { _tail  = jdk_internal_vm_Continuation::tail(_continuation); }
  void write()                   { jdk_internal_vm_Continuation::set_tail(_continuation, _tail); }

  NOT_PRODUCT(intptr_t hash()    { return Thread::current()->is_Java_thread() ? _continuation->identity_hash() : -1; })

  ContinuationEntry* entry() const { return _entry; }
  bool is_mounted()   const { return _entry != nullptr; }
  intptr_t* entrySP() const { return _entry->entry_sp(); }
  intptr_t* entryFP() const { return _entry->entry_fp(); }
  address   entryPC() const { return _entry->entry_pc(); }
  int argsize()       const { assert(_entry->argsize() >= 0, ""); return _entry->argsize(); }
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

ContinuationWrapper::ContinuationWrapper(JavaThread* thread, oop continuation)
  : _thread(thread), _entry(thread->last_continuation()), _continuation(continuation)
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(oopDesc::is_oop(_continuation),
         "Invalid continuation object: " INTPTR_FORMAT, p2i((void*)_continuation));
  assert(_continuation == _entry->cont_oop(), "cont: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: "
         INTPTR_FORMAT, p2i((oopDesc*)_continuation), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  read();
}

ContinuationWrapper::ContinuationWrapper(oop continuation)
  : _thread(nullptr), _entry(nullptr), _continuation(continuation)
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(oopDesc::is_oop(_continuation),
         "Invalid continuation object: " INTPTR_FORMAT, p2i((void*)_continuation));
  read();
}

ContinuationWrapper::ContinuationWrapper(const RegisterMap* map)
  : _thread(map->thread()),
    _entry(Continuation::get_continuation_entry_for_continuation(_thread, map->stack_chunk()->cont())),
    _continuation(map->stack_chunk()->cont())
#if CONT_JFR
  , _e_size(0), _e_num_interpreted_frames(0)
#endif
  {
  assert(oopDesc::is_oop(_continuation),"Invalid cont: " INTPTR_FORMAT, p2i((void*)_continuation));
  assert(_entry == nullptr || _continuation == _entry->cont_oop(),
    "cont: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: " INTPTR_FORMAT,
    p2i( (oopDesc*)_continuation), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  read();
}

inline void ContinuationWrapper::post_safepoint(Handle conth) {
  _continuation = conth(); // reload oop
  if (_tail != (oop)nullptr) {
    _tail = (stackChunkOop)jdk_internal_vm_Continuation::tail(_continuation);
  }
}

const frame ContinuationWrapper::last_frame() {
  stackChunkOop chunk = last_nonempty_chunk();
  if (chunk == nullptr) {
    return frame();
  }
  return StackChunkFrameStream<chunk_frames::MIXED>(chunk).to_frame();
}

inline stackChunkOop ContinuationWrapper::nonempty_chunk(stackChunkOop chunk) const {
  while (chunk != nullptr && chunk->is_empty()) {
    chunk = chunk->parent();
  }
  return chunk;
}

stackChunkOop ContinuationWrapper::find_chunk_by_address(void* p) const {
  for (stackChunkOop chunk = tail(); chunk != nullptr; chunk = chunk->parent()) {
    if (chunk->is_in_chunk(p)) {
      assert(chunk->is_usable_in_chunk(p), "");
      return chunk;
    }
  }
  return nullptr;
}

#if CONT_JFR
template<typename Event> void ContinuationWrapper::post_jfr_event(Event* e, JavaThread* jt) {
  if (e->should_commit()) {
    log_develop_trace(jvmcont)("JFR event: iframes: %d size: %d", _e_num_interpreted_frames, _e_size);
    e->set_carrierThread(JFR_JVM_THREAD_ID(jt));
    e->set_contClass(_continuation->klass());
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->commit();
  }
}
#endif

#ifdef ASSERT
inline bool ContinuationWrapper::is_entry_frame(const frame& f) {
  return f.sp() == entrySP();
}

bool ContinuationWrapper::chunk_invariant(outputStream* st) {
  // only the topmost chunk can be empty
  if (_tail == nullptr) {
    return true;
  }

  int i = 1;
  for (stackChunkOop chunk = _tail->parent(); chunk != nullptr; chunk = chunk->parent()) {
    if (chunk->is_empty()) {
      assert(chunk != _tail, "");
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
static int num_java_frames(ContinuationWrapper& cont) {
  ResourceMark rm; // used for scope traversal in num_java_frames(CompiledMethod*, address)
  int count = 0;
  for (stackChunkOop chunk = cont.tail(); chunk != nullptr; chunk = chunk->parent()) {
    count += chunk->num_java_frames();
  }
  return count;
}
#endif // INCLUDE_JVMTI

/////////////////////////////////////////////////////////////////

// Entry point to freeze. Transitions are handled manually
template<typename ConfigT>
static JRT_BLOCK_ENTRY(int, freeze(JavaThread* current, intptr_t* sp))
  assert(sp == current->frame_anchor()->last_Java_sp(), "");

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
int Continuation::try_force_yield(JavaThread* target, const oop continuation) {
  log_trace(jvmcont, preempt)("try_force_yield: thread state: %s", target->thread_state_name());

  ContinuationEntry* ce = target->last_continuation();
  while (ce != nullptr && ce->continuation() != continuation) {
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

  assert(target->has_last_Java_frame(), "");
  assert(!Interpreter::contains(target->last_Java_pc()) || !target->cont_fastpath(),
         "fast_path at codelet %s",
         Interpreter::codelet_containing(target->last_Java_pc())->description());

  const oop innermost = ce->continuation();
  const oop scope = jdk_internal_vm_Continuation::scope(continuation);
  if (innermost != continuation) { // we have nested continuations
    // make sure none of the continuations in the hierarchy are pinned
    freeze_result res_pinned = is_pinned0(target, scope, true);
    if (res_pinned != freeze_ok) {
      log_trace(jvmcont, preempt)("try_force_yield: res_pinned");
      return res_pinned;
    }
    jdk_internal_vm_Continuation::set_yieldInfo(continuation, scope);
  }

  assert(target->has_last_Java_frame(), "");
  int res = preempt_freeze(target, target->last_Java_sp());
  log_trace(jvmcont, preempt)("try_force_yield: %s", freeze_result_names[res]);
  if (res == 0) { // success
    target->set_cont_preempt(true);
    // target->frame_anchor()->patch_last_Java_pc(StubRoutines::cont_jump_from_sp());

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
  assert(thread == JavaThread::current(), "");
  assert(thread->is_cont_force_yield(), "");
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

JVM_ENTRY(void, CONT_pin(JNIEnv* env, jclass cls)) {
  if (!Continuation::pin(JavaThread::thread_from_jni_environment(env))) {
     THROW_MSG(vmSymbols::java_lang_IllegalStateException(), "pin overflow");
  }
}
JVM_END

JVM_ENTRY(void, CONT_unpin(JNIEnv* env, jclass cls)) {
  if (!Continuation::unpin(JavaThread::thread_from_jni_environment(env))) {
     THROW_MSG(vmSymbols::java_lang_IllegalStateException(), "pin underflow");
  }
}
JVM_END

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
    if (cont_scope == jdk_internal_vm_Continuation::scope(entry->continuation())) {
      return entry;
    }
  }
  return nullptr;
}

ContinuationEntry* Continuation::get_continuation_entry_for_continuation(JavaThread* thread, oop continuation) {
  if (thread == nullptr || continuation == nullptr) {
    return nullptr;
  }

  for (ContinuationEntry* entry = thread->last_continuation(); entry != nullptr; entry = entry->parent()) {
    if (continuation == entry->continuation()) {
      return entry;
    }
  }
  return nullptr;
}

static bool is_on_stack(JavaThread* thread, const ContinuationEntry* entry) {
  if (entry == nullptr) {
    return false;
  }

  assert(thread->is_in_full_stack((address)entry), "");
  return true;
  // return false if called when transitioning to Java on return from freeze
  // return !thread->has_last_Java_frame() || thread->last_Java_sp() < cont->entry_sp();
}

bool Continuation::is_continuation_mounted(JavaThread* thread, oop continuation) {
  return is_on_stack(thread, get_continuation_entry_for_continuation(thread, continuation));
}

bool Continuation::is_continuation_scope_mounted(JavaThread* thread, oop cont_scope) {
  return is_on_stack(thread, last_continuation(thread, cont_scope));
}

// When walking the virtual stack, this method returns true
// iff the frame is a thawed continuation frame whose
// caller is still frozen on the h-stack.
// The continuation object can be extracted from the thread.
bool Continuation::is_cont_barrier_frame(const frame& f) {
  assert(f.is_interpreted_frame() || f.cb() != nullptr, "");
  return is_return_barrier_entry(f.is_interpreted_frame() ? Interpreted::return_pc(f) : Compiled::return_pc(f));
}

bool Continuation::is_return_barrier_entry(const address pc) {
  if (!Continuations::enabled()) return false;
  return pc == StubRoutines::cont_returnBarrier();
}

bool Continuation::is_continuation_enterSpecial(const frame& f) {
  if (f.cb() == nullptr || !f.cb()->is_compiled()) {
    return false;
  }
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

static inline bool is_sp_in_continuation(const ContinuationEntry* entry, intptr_t* const sp) {
  return entry->entry_sp() > sp;
}

bool Continuation::is_frame_in_continuation(const ContinuationEntry* entry, const frame& f) {
  return is_sp_in_continuation(entry, f.unextended_sp());
}

ContinuationEntry* Continuation::get_continuation_entry_for_sp(JavaThread* thread, intptr_t* const sp) {
  assert(thread != nullptr, "");
  ContinuationEntry* entry = thread->last_continuation();
  while (entry != nullptr && !is_sp_in_continuation(entry, sp)) {
    entry = entry->parent();
  }
  return entry;
}

bool Continuation::is_frame_in_continuation(JavaThread* thread, const frame& f) {
  return get_continuation_entry_for_sp(thread, f.unextended_sp()) != nullptr;
}

static frame continuation_top_frame(const ContinuationWrapper& cont, RegisterMap* map) {
  stackChunkOop chunk = cont.last_nonempty_chunk();
  map->set_stack_chunk(chunk);
  return chunk != nullptr ? chunk->top_frame(map) : frame();
}

bool Continuation::has_last_Java_frame(oop continuation, frame* frame, RegisterMap* map) {
  ContinuationWrapper cont(continuation);
  if (!cont.is_empty()) {
    *frame = continuation_top_frame(cont, map);
    return true;
  } else {
    return false;
  }
}

frame Continuation::last_frame(oop continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  return continuation_top_frame(ContinuationWrapper(continuation), map);
}

frame Continuation::top_frame(const frame& callee, RegisterMap* map) {
  assert(map != nullptr, "");
  oop continuation = get_continuation_entry_for_sp(map->thread(), callee.sp())->cont_oop();
  ContinuationWrapper cont(continuation);
  return continuation_top_frame(cont, map);
}

javaVFrame* Continuation::last_java_vframe(Handle continuation, RegisterMap *map) {
  assert(map != nullptr, "a map must be given");
  if (!ContinuationWrapper(continuation()).is_empty()) {
    frame f = last_frame(continuation(), map);
    for (vframe* vf = vframe::new_vframe(&f, map, nullptr); vf; vf = vf->sender()) {
      if (vf->is_java_frame()) {
        return javaVFrame::cast(vf);
      }
    }
  }
  return nullptr;
}

frame Continuation::continuation_parent_frame(RegisterMap* map) {
  assert(map->in_cont(), "");
  ContinuationWrapper cont(map);
  assert(map->thread() != nullptr || !cont.is_mounted(), "");

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
    oop parent = jdk_internal_vm_Continuation::parent(cont.continuation());
    stackChunkOop chunk = parent != nullptr ? ContinuationWrapper(parent).last_nonempty_chunk() : nullptr;
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

oop Continuation::continuation_scope(oop continuation) {
  return continuation != nullptr ? jdk_internal_vm_Continuation::scope(continuation) : nullptr;
}

bool Continuation::is_scope_bottom(oop cont_scope, const frame& f, const RegisterMap* map) {
  if (cont_scope == nullptr || !is_continuation_entry_frame(f, map)) {
    return false;
  }

  oop continuation = get_continuation_entry_for_sp(map->thread(), f.sp())->cont_oop();
  if (continuation == nullptr) {
    return false;
  }

  oop sc = continuation_scope(continuation);
  assert(sc != nullptr, "");
  return sc == cont_scope;
}

bool Continuation::is_in_usable_stack(address addr, const RegisterMap* map) {
  ContinuationWrapper cont(map);
  stackChunkOop chunk = cont.find_chunk_by_address(addr);
  return chunk != nullptr ? chunk->is_usable_in_chunk(addr) : false;
}

bool Continuation::pin(JavaThread* current) {
  ContinuationEntry* ce = current->last_continuation();
  if (ce == nullptr) {
    return true; // no continuation mounted
  }
  return ce->pin();
}

bool Continuation::unpin(JavaThread* current) {
  ContinuationEntry* ce = current->last_continuation();
  if (ce == nullptr) {
    return true; // no continuation mounted
  }
  return ce->unpin();
}

bool Continuation::fix_continuation_bottom_sender(JavaThread* thread, const frame& callee,
                                                  address* sender_pc, intptr_t** sender_sp) {
  if (thread != nullptr && is_return_barrier_entry(*sender_pc)) {
    ContinuationEntry* ce = get_continuation_entry_for_sp(thread,
          callee.is_interpreted_frame() ? callee.interpreter_frame_last_sp() : callee.unextended_sp());
    assert(ce != nullptr, "callee.unextended_sp(): " INTPTR_FORMAT, p2i(callee.unextended_sp()));

    log_develop_debug(jvmcont)("fix_continuation_bottom_sender: "
                                  "[" JLONG_FORMAT "] [%d]", java_tid(thread), thread->osthread()->thread_id());
    log_develop_trace(jvmcont)("sender_pc: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_pc), p2i(ce->entry_pc()));
    log_develop_trace(jvmcont)("sender_sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(*sender_sp), p2i(ce->entry_sp()));

    *sender_pc = ce->entry_pc();
    *sender_sp = ce->entry_sp();
    // We DO NOT fix FP. It could contain an oop that has changed on the stack, and its location should be OK anyway

    return true;
  }
  return false;
}

address Continuation::get_top_return_pc_post_barrier(JavaThread* thread, address pc) {
  ContinuationEntry* ce;
  if (thread != nullptr && is_return_barrier_entry(pc) && (ce = thread->last_continuation()) != nullptr) {
    return ce->entry_pc();
  }
  return pc;
}

void Continuation::set_cont_fastpath_thread_state(JavaThread* thread) {
  assert(thread != nullptr, "");
  bool fast = !thread->is_interp_only_mode();
  thread->set_cont_fastpath_thread_state(fast);
}

void Continuation::notify_deopt(JavaThread* thread, intptr_t* sp) {
  ContinuationEntry* entry = thread->last_continuation();

  if (entry == nullptr) {
    return;
  }

  if (is_sp_in_continuation(entry, sp)) {
    thread->push_cont_fastpath(sp);
    return;
  }

  ContinuationEntry* prev;
  do {
    prev = entry;
    entry = entry->parent();
  } while (entry != nullptr && !is_sp_in_continuation(entry, sp));

  if (entry == nullptr) {
    return;
  }
  assert(is_sp_in_continuation(entry, sp), "");
  if (sp > prev->parent_cont_fastpath()) {
    prev->set_parent_cont_fastpath(sp);
  }
}

#ifndef PRODUCT
void Continuation::describe(FrameValues &values) {
  JavaThread* thread = JavaThread::active();
  if (thread != nullptr) {
    for (ContinuationEntry* ce = thread->last_continuation(); ce != nullptr; ce = ce->parent()) {
      intptr_t* bottom = ce->entry_sp();
      if (bottom != nullptr) {
        values.describe(-1, bottom, "continuation entry");
      }
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
void Continuation::debug_verify_continuation(oop contOop) {
  if (!VerifyContinuations) {
    return;
  }
  assert(contOop != nullptr, "");
  assert(oopDesc::is_oop(contOop), "");
  ContinuationWrapper cont(contOop);

  assert(oopDesc::is_oop_or_null(cont.tail()), "");
  assert(cont.chunk_invariant(tty), "");

  bool nonempty_chunk = false;
  size_t max_size = 0;
  int num_chunks = 0;
  int num_frames = 0;
  int num_interpreted_frames = 0;
  int num_oops = 0;

  for (stackChunkOop chunk = cont.tail(); chunk != nullptr; chunk = chunk->parent()) {
    log_develop_trace(jvmcont)("debug_verify_continuation chunk %d", num_chunks);
    chunk->verify(&max_size, &num_oops, &num_frames, &num_interpreted_frames);
    if (!chunk->is_empty()) {
      nonempty_chunk = true;
    }
    num_chunks++;
  }

  const bool is_empty = cont.is_empty();
  assert(!nonempty_chunk || !is_empty, "");
  assert(is_empty == (!nonempty_chunk && cont.last_frame().is_empty()), "");
}

void Continuation::print(oop continuation) { print_on(tty, continuation); }

void Continuation::print_on(outputStream* st, oop continuation) {
  ContinuationWrapper cont(continuation);

  st->print_cr("CONTINUATION: " PTR_FORMAT " done: %d",
    continuation->identity_hash(), jdk_internal_vm_Continuation::done(continuation));
  st->print_cr("CHUNKS:");
  for (stackChunkOop chunk = cont.tail(); chunk != nullptr; chunk = chunk->parent()) {
    st->print("* ");
    chunk->print_on(true, st);
  }
}
#endif // ASSERT

/////////////// FREEZE ////

class FreezeBase : public StackObj {
protected:
  JavaThread* const _thread;
  ContinuationWrapper& _cont;
  bool _barriers;
  const bool _preempt; // used only on the slow path

  intptr_t *_bottom_address;

  int _size; // total size of all frames plus metadata in words.
  int _align_size;

  NOT_PRODUCT(int _frames;)
  DEBUG_ONLY(intptr_t* _last_write;)

  inline FreezeBase(JavaThread* thread, ContinuationWrapper& cont, bool preempt);

public:
  NOINLINE freeze_result freeze_slow();

protected:
  inline void init_rest();
  inline void init_chunk(stackChunkOop chunk);
  void throw_stack_overflow_on_humongous_chunk();

  // fast path
  inline void copy_to_chunk(intptr_t* from, intptr_t* to, int size);
  inline void unwind_frames();

  inline void patch_chunk_pd(intptr_t* vsp, intptr_t* hsp);

private:
  // slow path
  frame freeze_start_frame();
  frame freeze_start_frame_safepoint_stub(frame f);
  NOINLINE freeze_result freeze(frame& f, frame& caller, int callee_argsize, bool callee_interpreted, bool top);
  inline frame freeze_start_frame_yield_stub(frame f);
  template<typename FKind>
  inline freeze_result recurse_freeze_java_frame(const frame& f, frame& caller, int fsize, int argsize);
  inline void before_freeze_java_frame(const frame& f, const frame& caller, int fsize, int argsize, bool bottom);
  inline void after_freeze_java_frame(const frame& hf, bool bottom);
  freeze_result finalize_freeze(const frame& callee, frame& caller, int argsize);
  void patch(const frame& f, frame& hf, const frame& caller, bool bottom);
  NOINLINE freeze_result recurse_freeze_interpreted_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted);
  freeze_result recurse_freeze_compiled_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted);
  NOINLINE freeze_result recurse_freeze_stub_frame(frame& f, frame& caller);
  NOINLINE void finish_freeze(const frame& f, const frame& top);

  inline bool stack_overflow();

  static frame sender(const frame& f) { return f.is_interpreted_frame() ? sender<Interpreted>(f): sender<NonInterpretedUnknown>(f); }
  template<typename FKind> static inline frame sender(const frame& f);
  template<typename FKind> frame new_hframe(frame& f, frame& caller);
  inline void set_top_frame_metadata_pd(const frame& hf);
  inline void patch_pd(frame& callee, const frame& caller);
  static inline void relativize_interpreted_frame_metadata(const frame& f, const frame& hf);

protected:
  virtual stackChunkOop allocate_chunk_slow(size_t stack_size) = 0;
};

template <typename ConfigT>
class Freeze : public FreezeBase {
private:
  stackChunkOop allocate_chunk(size_t stack_size);

public:
  inline Freeze(JavaThread* thread, ContinuationWrapper& cont, bool preempt)
    : FreezeBase(thread, cont, preempt) {}

  inline bool is_chunk_available(intptr_t* top_sp
#ifdef ASSERT
    , int* out_size = nullptr
#endif
  );
  template <bool chunk_available> freeze_result try_freeze_fast(intptr_t* sp);
  template <bool chunk_available> bool freeze_fast(intptr_t* top_sp);

protected:
  virtual stackChunkOop allocate_chunk_slow(size_t stack_size) override { return allocate_chunk(stack_size); }
};

FreezeBase::FreezeBase(JavaThread* thread, ContinuationWrapper& cont, bool preempt) :
    _thread(thread), _cont(cont), _barriers(false), _preempt(preempt) {

  assert(_thread != nullptr, "");
  assert(_thread->last_continuation()->entry_sp() == _cont.entrySP(), "");

  _bottom_address = _cont.entrySP() - _cont.argsize();
  DEBUG_ONLY(_cont.entry()->verify_cookie();)

  assert(!Interpreter::contains(_cont.entryPC()), "");

#ifdef _LP64
  if (((intptr_t)_bottom_address & 0xf) != 0) {
    _bottom_address--;
  }
  assert((intptr_t)_bottom_address % 16 == 0, "");
#endif

  log_develop_trace(jvmcont)("bottom_address: " INTPTR_FORMAT " entrySP: " INTPTR_FORMAT " argsize: " PTR_FORMAT,
                p2i(_bottom_address), p2i(_cont.entrySP()), (_cont.entrySP() - _bottom_address) << LogBytesPerWord);
  assert(_bottom_address != nullptr && _bottom_address <= _cont.entrySP(), "");
  DEBUG_ONLY(_last_write = nullptr;)
}

void FreezeBase::init_rest() { // we want to postpone some initialization after chunk handling
  _size = 0;
  _align_size = 0;
  NOT_PRODUCT(_frames = 0;)
}

void FreezeBase::copy_to_chunk(intptr_t* from, intptr_t* to, int size) {
  stackChunkOop chunk = _cont.tail();
  chunk->copy_from_stack_to_chunk(from, to, size);
  CONT_JFR_ONLY(_cont.record_size_copied(size);)

#ifdef ASSERT
  if (_last_write != nullptr) {
    assert(_last_write == to + size, "Missed a spot: _last_write: " INTPTR_FORMAT " to+size: " INTPTR_FORMAT
        " stack_size: %d _last_write offset: " PTR_FORMAT " to+size: " PTR_FORMAT, p2i(_last_write), p2i(to+size),
        chunk->stack_size(), _last_write-chunk->start_address(), to+size-chunk->start_address());
    _last_write = to;
  }
#endif
}

// Called _after_ the last possible sfepoint during the freeze operation (chunk allocation)
void FreezeBase::unwind_frames() {
  ContinuationEntry* entry = _cont.entry();
  ContinuationHelper::maybe_flush_stack_processing(_thread, entry);
  ContinuationHelper::set_anchor_to_entry(_thread, entry);
}

template <typename ConfigT>
template <bool chunk_available>
freeze_result Freeze<ConfigT>::try_freeze_fast(intptr_t* sp) {
  if (freeze_fast<chunk_available>(sp)) {
    return freeze_ok;
  }
  if (_barriers) {
    throw_stack_overflow_on_humongous_chunk();
    return freeze_exception;
  }
  if (_thread->has_pending_exception()) {
    return freeze_exception;
  }

  EventContinuationFreezeOld e;
  if (e.should_commit()) {
    e.set_id(cast_from_oop<u8>(_cont.continuation()));
    e.commit();
  }
  // TODO R REMOVE when deopt change is fixed
  assert(!_thread->cont_fastpath() || _barriers, "");
  log_develop_trace(jvmcont)("-- RETRYING SLOW --");
  return freeze_slow();
}

// returns true iff there's room in the chunk for a fast, compiled-frame-only freeze
template <typename ConfigT>
bool Freeze<ConfigT>::is_chunk_available(intptr_t* top_sp
#ifdef ASSERT
    , int* out_size
#endif
  ) {
  stackChunkOop chunk = _cont.tail();
  if (chunk == nullptr || chunk->is_gc_mode() || chunk->requires_barriers() || chunk->has_mixed_frames()) {
    log_develop_trace(jvmcont)("is_chunk_available %s", chunk == nullptr ? "no chunk" : "chunk requires barriers");
    return false;
  }

  // assert(CodeCache::find_blob(*(address*)(top_sp - SENDER_SP_RET_ADDRESS_OFFSET)) == StubRoutines::cont_doYield_stub(), ""); -- fails on Windows
  assert(StubRoutines::cont_doYield_stub()->frame_size() == ContinuationHelper::frame_metadata, "");
  intptr_t* const stack_top     = top_sp + ContinuationHelper::frame_metadata;
  intptr_t* const stack_bottom  = _cont.entrySP() - ContinuationHelper::frame_align_words(_cont.argsize());

  int size = stack_bottom - stack_top; // in words

  const int chunk_sp = chunk->sp();
  if (chunk_sp < chunk->stack_size()) {
    size -= _cont.argsize();
  }
  assert(size > 0, "");

  bool available = chunk_sp - ContinuationHelper::frame_metadata >= size;
  log_develop_trace(jvmcont)("is_chunk_available: %d size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT,
    available, _cont.argsize(), size, p2i(stack_top), p2i(stack_bottom));
  DEBUG_ONLY(if (out_size != nullptr) *out_size = size;)
  return available;
}

template <typename ConfigT>
template <bool chunk_available>
bool Freeze<ConfigT>::freeze_fast(intptr_t* top_sp) {
  assert(_cont.chunk_invariant(tty), "");
  assert(!Interpreter::contains(_cont.entryPC()), "");
  assert(StubRoutines::cont_doYield_stub()->frame_size() == ContinuationHelper::frame_metadata, "");

  // properties of the continuation on the stack; all sizes are in words
  intptr_t* const cont_stack_top    = top_sp + ContinuationHelper::frame_metadata;
  intptr_t* const cont_stack_bottom = _cont.entrySP() - ContinuationHelper::frame_align_words(_cont.argsize()); // see alignment in thaw

  const int cont_size = cont_stack_bottom - cont_stack_top;

  log_develop_trace(jvmcont)("freeze_fast size: %d argsize: %d top: " INTPTR_FORMAT " bottom: " INTPTR_FORMAT,
    cont_size, _cont.argsize(), p2i(cont_stack_top), p2i(cont_stack_bottom));
  assert(cont_size > 0, "");

#ifdef ASSERT
  bool empty = true;
  int is_chunk_available_size;
  bool is_chunk_available0 = is_chunk_available(top_sp, &is_chunk_available_size);
  intptr_t* orig_chunk_sp = nullptr;
#endif

  stackChunkOop chunk = _cont.tail();
  int chunk_start_sp; // the chunk's sp before the freeze, adjusted to point beyond the stack-passed arguments in the topmost frame
  if (chunk_available) { // LIKELY
    DEBUG_ONLY(orig_chunk_sp = chunk->sp_address();)

    assert(is_chunk_available0, "");

    if (chunk->sp() < chunk->stack_size()) { // we are copying into a non-empty chunk
      DEBUG_ONLY(empty = false;)
      assert(chunk->sp() < (chunk->stack_size() - chunk->argsize()), "");
      assert(*(address*)(chunk->sp_address() - frame::sender_sp_ret_address_offset()) == chunk->pc(), "");

      chunk_start_sp = chunk->sp() + _cont.argsize(); // we overlap; we'll overwrite the chunk's top frame's callee arguments
      assert(chunk_start_sp <= chunk->stack_size(), "");

      chunk->set_max_size(chunk->max_size() + cont_size - _cont.argsize());

      intptr_t* const bottom_sp = cont_stack_bottom - _cont.argsize();
      assert(bottom_sp == _bottom_address, "");
      assert(*(address*)(bottom_sp-frame::sender_sp_ret_address_offset()) == StubRoutines::cont_returnBarrier(), "");
      patch_chunk_pd(bottom_sp, chunk->sp_address());
      // we don't patch the pc at this time, so as not to make the stack unwalkable
    } else { // the chunk is empty
      chunk_start_sp = chunk->sp();

      assert(chunk_start_sp == chunk->stack_size(), "");

      chunk->set_max_size(cont_size);
      chunk->set_argsize(_cont.argsize());
    }
  } else { // no chunk; allocate
    assert(_thread->thread_state() == _thread_in_vm, "");
    assert(!is_chunk_available(top_sp), "");
    assert(_thread->cont_fastpath(), "");

    chunk = allocate_chunk(cont_size + ContinuationHelper::frame_metadata);
    if (UNLIKELY(chunk == nullptr || !_thread->cont_fastpath() || _barriers)) { // OOME/probably humongous
      log_develop_trace(jvmcont)("Retrying slow. Barriers: %d", _barriers);
      return false;
    }

    chunk->set_max_size(cont_size);
    chunk->set_argsize(_cont.argsize());

    // in a fresh chunk, we freeze *with* the bottom-most frame's stack arguments.
    // They'll then be stored twice: in the chunk and in the parent chunk's top frame
    chunk_start_sp = cont_size + ContinuationHelper::frame_metadata;
    assert(chunk_start_sp == chunk->stack_size(), "");

    DEBUG_ONLY(orig_chunk_sp = chunk->start_address() + chunk_start_sp;)
  }

  assert(chunk != nullptr, "");
  assert(!chunk->has_mixed_frames(), "");
  assert(!chunk->is_gc_mode(), "");
  assert(!chunk->has_bitmap(), "");
  assert(!chunk->requires_barriers(), "");
  assert(chunk == _cont.tail(), "");

  // We unwind frames after the last safepoint so that the GC will have found the oops in the frames, but before
  // writing into the chunk. This is so that an asynchronous stack walk (not at a safepoint) that suspends us here
  // will either see no continuation on the stack, or a consistent chunk.
  unwind_frames();
  OrderAccess::storestore();

  NoSafepointVerifier nsv;

  log_develop_trace(jvmcont)("freeze_fast start: chunk " INTPTR_FORMAT " size: %d orig sp: %d argsize: %d",
    p2i((oopDesc*)chunk), chunk->stack_size(), chunk_start_sp, _cont.argsize());
  assert(chunk_start_sp <= chunk->stack_size(), "");
  assert(chunk_start_sp >= cont_size, "");

  const int chunk_new_sp = chunk_start_sp - cont_size; // the chunk's new sp, after freeze
  assert(!is_chunk_available0 || orig_chunk_sp - (chunk->start_address() + chunk_new_sp) == is_chunk_available_size, "");

  intptr_t* chunk_top = chunk->start_address() + chunk_new_sp;
  assert(empty || *(address*)(orig_chunk_sp - frame::sender_sp_ret_address_offset()) == chunk->pc(), "");

  log_develop_trace(jvmcont)("freeze_fast start: " INTPTR_FORMAT " sp: %d chunk_top: " INTPTR_FORMAT,
                              p2i(chunk->start_address()), chunk_new_sp, p2i(chunk_top));
  intptr_t* from = cont_stack_top - ContinuationHelper::frame_metadata;
  intptr_t* to   = chunk_top - ContinuationHelper::frame_metadata;
  copy_to_chunk(from, to, cont_size + ContinuationHelper::frame_metadata);
  // Because we're not patched yet, the chunk is now in a bad state

  // patch pc
  intptr_t* chunk_bottom_sp = chunk_top + cont_size - _cont.argsize();
  assert(empty || *(address*)(chunk_bottom_sp-frame::sender_sp_ret_address_offset()) == StubRoutines::cont_returnBarrier(), "");
  *(address*)(chunk_bottom_sp - frame::sender_sp_ret_address_offset()) = chunk->pc();

  // We're always writing to a young chunk, so the GC can't see it until the next safepoint.
  OrderAccess::storestore();
  chunk->set_sp(chunk_new_sp);
  assert(chunk->sp_address() == chunk_top, "");
  chunk->set_pc(*(address*)(cont_stack_top - frame::sender_sp_ret_address_offset()));

  _cont.write();

  log_develop_trace(jvmcont)("FREEZE CHUNK #" INTPTR_FORMAT " (young)", _cont.hash());
  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    chunk->print_on(true, &ls);
  }

  assert(_cont.chunk_invariant(tty), "");
  verify_stack_chunk(chunk);

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

NOINLINE freeze_result FreezeBase::freeze_slow() {
#ifdef ASSERT
  ResourceMark rm;
#endif

  log_develop_trace(jvmcont)("freeze_slow  #" INTPTR_FORMAT, _cont.hash());
  assert(_thread->thread_state() == _thread_in_vm || _thread->thread_state() == _thread_blocked, "");

  init_rest();

  HandleMark hm(Thread::current());

  frame f = freeze_start_frame();

  LogTarget(Debug, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    f.print_on(&ls);
  }

  frame caller;
  freeze_result res = freeze(f, caller, 0, false, true);

  if (res == freeze_ok) {
    finish_freeze(f, caller);
    _cont.write();
  }
  if (_barriers && !_preempt) {
    throw_stack_overflow_on_humongous_chunk();
  }

  return res;
}

frame FreezeBase::freeze_start_frame() {
  frame f = _thread->last_frame();
  if (LIKELY(!_preempt)) {
    assert(StubRoutines::cont_doYield_stub()->contains(f.pc()), "");
    return freeze_start_frame_yield_stub(f);
  } else {
    return freeze_start_frame_safepoint_stub(f);
  }
}

frame FreezeBase::freeze_start_frame_yield_stub(frame f) {
  // log_develop_trace(jvmcont)("%s nop at freeze yield", nativePostCallNop_at(_fi->pc) != nullptr ? "has" : "no");
  assert(StubRoutines::cont_doYield_stub()->contains(f.pc()), "must be");
  f = sender<StubF>(f);
  return f;
}

frame FreezeBase::freeze_start_frame_safepoint_stub(frame f) {
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
  f.set_fp(f.real_fp()); // f.set_fp(*Frame::callee_link_address(f)); // ????
#else
  Unimplemented();
#endif
  if (!Interpreter::contains(f.pc())) {
    assert(Frame::is_stub(f.cb()), "must be");
    assert(f.oop_map() != nullptr, "must be");

    if (Interpreter::contains(StubF::return_pc(f))) {
      f = sender<StubF>(f); // Safepoint stub in interpreter
    }
  }
  return f;
}

NOINLINE freeze_result FreezeBase::freeze(frame& f, frame& caller, int callee_argsize, bool callee_interpreted, bool top) {
  assert(f.unextended_sp() < _bottom_address, ""); // see recurse_freeze_java_frame
  assert(f.is_interpreted_frame() || ((top && _preempt) == Frame::is_stub(f.cb())), "");

  if (stack_overflow()) {
    return freeze_exception;
  }

  if (f.is_compiled_frame()) {
    if (UNLIKELY(f.oop_map() == nullptr)) {
      // special native frame
      return freeze_pinned_native;
    }
    if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), SmallRegisterMap::instance, f))) {
      return freeze_pinned_monitor;
    }

    return recurse_freeze_compiled_frame(f, caller, callee_argsize, callee_interpreted);
  } else if (f.is_interpreted_frame()) {
    assert((_preempt && top) || !f.interpreter_frame_method()->is_native(), "");
    if (Interpreted::is_owning_locks(f)) {
      return freeze_pinned_monitor;
    }
    if (_preempt && top && f.interpreter_frame_method()->is_native()) {
      // int native entry
      return freeze_pinned_native;
    }

    return recurse_freeze_interpreted_frame(f, caller, callee_argsize, callee_interpreted);
  } else if (_preempt && top && Frame::is_stub(f.cb())) {
    return recurse_freeze_stub_frame(f, caller);
  } else {
    return freeze_pinned_native;
  }
}

template<typename FKind>
inline freeze_result FreezeBase::recurse_freeze_java_frame(const frame& f, frame& caller, int fsize, int argsize) {
  assert(FKind::is_instance(f), "");

  assert(fsize > 0 && argsize >= 0, "");
  _size += fsize;
  NOT_PRODUCT(_frames++;)

  if (FKind::frame_bottom(f) >= _bottom_address - 1) { // sometimes there's space after enterSpecial
    return finalize_freeze(f, caller, argsize); // recursion end
  } else {
    frame senderf = sender<FKind>(f);
    assert(FKind::interpreted || senderf.sp() == senderf.unextended_sp(), "");
    freeze_result result = freeze(senderf, caller, argsize, FKind::interpreted, false); // recursive call
    return result;
  }
}

inline void FreezeBase::before_freeze_java_frame(const frame& f, const frame& caller, int fsize, int argsize, bool bottom) {
  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("======== FREEZING FRAME interpreted: %d bottom: %d", f.is_interpreted_frame(), bottom);
    ls.print_cr("fsize: %d argsize: %d", fsize, argsize);
    f.print_on(&ls);
  }
  assert(caller.is_interpreted_frame() == Interpreter::contains(caller.pc()), "");
}

inline void FreezeBase::after_freeze_java_frame(const frame& hf, bool bottom) {
  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    DEBUG_ONLY(hf.print_value_on(&ls, nullptr);)
    assert(hf.is_heap_frame(), "should be");
    DEBUG_ONLY(print_frame_layout(hf, &ls);)
    if (bottom) {
      ls.print_cr("bottom h-frame:");
      hf.print_on(&ls);
    }
  }
}

freeze_result FreezeBase::finalize_freeze(const frame& callee, frame& caller, int argsize) {
  assert(callee.is_interpreted_frame() || argsize == _cont.argsize(), "argsize: %d cont.argsize: %d", argsize, _cont.argsize());
  log_develop_trace(jvmcont)("bottom: " INTPTR_FORMAT " count %d size: %d argsize: %d",
    p2i(_bottom_address), _frames, _size << LogBytesPerWord, argsize);

  LogTarget(Trace, jvmcont) lt;

#ifdef ASSERT
  bool empty = _cont.is_empty();
  log_develop_trace(jvmcont)("empty: %d", empty);
#endif

  stackChunkOop chunk = _cont.tail();

  assert(chunk == nullptr || (chunk->max_size() == 0) == chunk->is_empty(), "");

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
      if (callee.is_interpreted_frame() == top_interpreted) {
        overlap = argsize;
      }
    }
  }

  log_develop_trace(jvmcont)("finalize _size: %d overlap: %d unextended_sp: %d", _size, overlap, unextended_sp);

  _size -= overlap;
  assert(_size >= 0, "");

  assert(chunk == nullptr || chunk->is_empty()
          || unextended_sp == chunk->to_offset(StackChunkFrameStream<chunk_frames::MIXED>(chunk).unextended_sp()), "");
  assert(chunk != nullptr || unextended_sp < _size, "");

    // _barriers can be set to true by an allocation in freeze_fast, in which case the chunk is available
  assert(!_barriers || (unextended_sp >= _size && chunk->is_empty()),
    "unextended_sp: %d size: %d is_empty: %d", unextended_sp, _size, chunk->is_empty());

  DEBUG_ONLY(bool empty_chunk = true);
  if (unextended_sp < _size || chunk->is_gc_mode() || (!_barriers && chunk->requires_barriers())) {
    // ALLOCATION

    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      if (chunk == nullptr) {
        ls.print_cr("no chunk");
      } else {
        ls.print_cr("chunk barriers: %d _size: %d free size: %d",
          chunk->requires_barriers(), _size, chunk->sp() - ContinuationHelper::frame_metadata);
        chunk->print_on(&ls);
      }
    }

    _size += overlap; // we're allocating a new chunk, so no overlap
    // overlap = 0;

    chunk = allocate_chunk_slow(_size);
    if (chunk == nullptr) {
      return freeze_exception;
    }
    if (_barriers) {
      log_develop_trace(jvmcont)("allocation requires barriers");
      return freeze_exception;
    }
    int sp = chunk->stack_size() - argsize;
    chunk->set_sp(sp);
    chunk->set_argsize(argsize);
    assert(chunk->is_empty(), "");
  } else {
    log_develop_trace(jvmcont)("Reusing chunk mixed: %d empty: %d", chunk->has_mixed_frames(), chunk->is_empty());
    if (chunk->is_empty()) {
      int sp = chunk->stack_size() - argsize;
      chunk->set_sp(sp);
      chunk->set_argsize(argsize);
      _size += overlap;
      assert(chunk->max_size() == 0, "");
    } DEBUG_ONLY(else empty_chunk = false;)
  }
  chunk->set_has_mixed_frames(true);

  assert(chunk->requires_barriers() == _barriers, "");
  assert(!_barriers || chunk->is_empty(), "");

  assert(!chunk->has_bitmap(), "");
  assert(!chunk->is_empty() || StackChunkFrameStream<chunk_frames::MIXED>(chunk).is_done(), "");
  assert(!chunk->is_empty() || StackChunkFrameStream<chunk_frames::MIXED>(chunk).to_frame().is_empty(), "");

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
    assert(caller.is_heap_frame(), "should be");
    caller.print_on(&ls);
  }

  assert(!empty || Continuation::is_continuation_entry_frame(callee, nullptr), "");

  frame entry = sender(callee);

  assert(Continuation::is_return_barrier_entry(entry.pc()) || Continuation::is_continuation_enterSpecial(entry), "");
  assert(callee.is_interpreted_frame() || entry.sp() == entry.unextended_sp(), "");
#endif

  return freeze_ok_bottom;
}

void FreezeBase::patch(const frame& f, frame& hf, const frame& caller, bool bottom) {
  if (bottom) {
    address last_pc = caller.pc();
    assert((last_pc == nullptr) == _cont.tail()->is_empty(), "");
    Frame::patch_pc(caller, last_pc);
  } else {
    assert(!caller.is_empty(), "");
  }

  patch_pd(hf, caller);

  if (f.is_interpreted_frame()) {
    assert(hf.is_heap_frame(), "should be");
    Interpreted::patch_sender_sp(hf, caller.unextended_sp());
  }

#ifdef ASSERT
  if (hf.is_compiled_frame()) {
    if (f.is_deoptimized_frame()) { // TODO DEOPT: long term solution: unroll on freeze and patch pc
      log_develop_trace(jvmcont)("Freezing deoptimized frame");
      assert(f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
      assert(f.cb()->as_compiled_method()->is_deopt_pc(Frame::real_pc(f)), "");
    }
  }
#endif
}

NOINLINE freeze_result FreezeBase::recurse_freeze_interpreted_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted) {
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
  { // TODO PD
    assert((f.at(frame::interpreter_frame_last_sp_offset) != 0) || (f.unextended_sp() == f.sp()), "");
    intptr_t* real_unextended_sp = (intptr_t*)f.at(frame::interpreter_frame_last_sp_offset);
    if (real_unextended_sp != nullptr) {
      f.set_unextended_sp(real_unextended_sp); // can be null at a safepoint
    }
  }
#else
  Unimplemented();
#endif

  intptr_t* const vsp = Interpreted::frame_top(f, callee_argsize, callee_interpreted);
  const int argsize = Interpreted::stack_argsize(f);
  const int locals = f.interpreter_frame_method()->max_locals();
  assert(Interpreted::frame_bottom(f) >= f.fp() + ContinuationHelper::frame_metadata + locals, "");// = on x86
  const int fsize = f.fp() + ContinuationHelper::frame_metadata + locals - vsp;

#ifdef ASSERT
{
  ResourceMark rm;
  InterpreterOopMap mask;
  f.interpreted_frame_oop_map(&mask);
  assert(vsp <= Interpreted::frame_top(f, &mask), "vsp: " INTPTR_FORMAT " Interpreted::frame_top: " INTPTR_FORMAT,
    p2i(vsp), p2i(Interpreted::frame_top(f, &mask)));
}
#endif

  Method* frame_method = Frame::frame_method(f);

  log_develop_trace(jvmcont)("recurse_freeze_interpreted_frame %s _size: %d fsize: %d argsize: %d",
    frame_method->name_and_sig_as_C_string(), _size, fsize, argsize);
  // we'd rather not yield inside methods annotated with @JvmtiMountTransition
  assert(!Frame::frame_method(f)->jvmti_mount_transition(), "");

  freeze_result result = recurse_freeze_java_frame<Interpreted>(f, caller, fsize, argsize);
  if (UNLIKELY(result > freeze_ok_bottom)) {
    return result;
  }

  bool bottom = result == freeze_ok_bottom;

  DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, bottom);)

  frame hf = new_hframe<Interpreted>(f, caller);

  intptr_t* hsp = Interpreted::frame_top(hf, callee_argsize, callee_interpreted);
  assert(Interpreted::frame_bottom(hf) == hsp + fsize, "");

  // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
  copy_to_chunk(Interpreted::frame_bottom(f) - locals,
                Interpreted::frame_bottom(hf) - locals, locals); // copy locals
  copy_to_chunk(vsp, hsp, fsize - locals); // copy rest
  assert(!bottom || !caller.is_interpreted_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

  relativize_interpreted_frame_metadata(f, hf);

  patch(f, hf, caller, bottom);

  CONT_JFR_ONLY(_cont.record_interpreted_frame();)
  DEBUG_ONLY(after_freeze_java_frame(hf, bottom);)
  caller = hf;

  // Mark frame_method's marking cycle for GC and redefinition on_stack calculation.
  frame_method->record_marking_cycle();

  return freeze_ok;
}

freeze_result FreezeBase::recurse_freeze_compiled_frame(frame& f, frame& caller, int callee_argsize, bool callee_interpreted) {
  intptr_t* const vsp = Compiled::frame_top(f, callee_argsize, callee_interpreted);
  const int argsize = Compiled::stack_argsize(f);
  const int fsize = Compiled::frame_bottom(f) + argsize - vsp;

  log_develop_trace(jvmcont)("recurse_freeze_compiled_frame %s _size: %d fsize: %d argsize: %d",
    Frame::frame_method(f) != nullptr ? Frame::frame_method(f)->name_and_sig_as_C_string():"", _size,fsize,argsize);
  // we'd rather not yield inside methods annotated with @JvmtiMountTransition
  assert(!Frame::frame_method(f)->jvmti_mount_transition(), "");

  freeze_result result = recurse_freeze_java_frame<Compiled>(f, caller, fsize, argsize);
  if (UNLIKELY(result > freeze_ok_bottom)) {
    return result;
  }

  bool bottom = result == freeze_ok_bottom;

  DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, argsize, bottom);)

  frame hf = new_hframe<Compiled>(f, caller);

  intptr_t* hsp = Compiled::frame_top(hf, callee_argsize, callee_interpreted);

  copy_to_chunk(vsp, hsp, fsize);
  assert(!bottom || !caller.is_compiled_frame() || (hsp + fsize) == (caller.unextended_sp() + argsize), "");

  if (caller.is_interpreted_frame()) {
    _align_size += ContinuationHelper::align_wiggle; // See Thaw::align
  }

  patch(f, hf, caller, bottom);

  assert(bottom || Interpreter::contains(Compiled::real_pc(caller)) == caller.is_interpreted_frame(), "");

  DEBUG_ONLY(after_freeze_java_frame(hf, bottom);)
  caller = hf;
  return freeze_ok;
}

NOINLINE freeze_result FreezeBase::recurse_freeze_stub_frame(frame& f, frame& caller) {
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
  assert(senderf.unextended_sp() < _bottom_address - 1, "");
  assert(senderf.is_compiled_frame(), "");

  if (UNLIKELY(senderf.oop_map() == nullptr)) {
    // native frame
    return freeze_pinned_native;
  }
  if (UNLIKELY(Compiled::is_owning_locks(_cont.thread(), &map, senderf))) {
    return freeze_pinned_monitor;
  }

  freeze_result result = recurse_freeze_compiled_frame(senderf, caller, 0, 0); // This might be deoptimized
  if (UNLIKELY(result > freeze_ok_bottom)) {
    return result;
  }
  assert(result != freeze_ok_bottom, "");
  assert(!caller.is_interpreted_frame(), "");

  DEBUG_ONLY(before_freeze_java_frame(f, caller, fsize, 0, false);)
  frame hf = new_hframe<StubF>(f, caller);
  intptr_t* hsp = StubF::frame_top(hf, 0, 0);
  copy_to_chunk(vsp, hsp, fsize);
  DEBUG_ONLY(after_freeze_java_frame(hf, false);)

  caller = hf;
  return freeze_ok;
}

NOINLINE void FreezeBase::finish_freeze(const frame& f, const frame& top) {
  stackChunkOop chunk = _cont.tail();
  assert(chunk->to_offset(top.sp()) <= chunk->sp(), "");

  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    assert(top.is_heap_frame(), "should be");
    top.print_on(&ls);
  }

  set_top_frame_metadata_pd(top);

  OrderAccess::storestore();
  chunk->set_sp(chunk->to_offset(top.sp()));
  chunk->set_pc(top.pc());

  chunk->set_max_size(chunk->max_size() + _align_size);

  log_develop_trace(jvmcont)("finish_freeze: has_mixed_frames: %d", chunk->has_mixed_frames());

  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("top hframe after (freeze):");
    assert(_cont.last_frame().is_heap_frame(), "should be");
    _cont.last_frame().print_on(&ls);
  }

  assert(_cont.chunk_invariant(tty), "");
}

inline bool FreezeBase::stack_overflow() { // detect stack overflow in recursive native code
  JavaThread* t = !_preempt ? _thread : JavaThread::current();
  assert(t == JavaThread::current(), "");
  if ((address)&t < t->stack_overflow_state()->stack_overflow_limit()) {
    Exceptions::_throw_msg(t, __FILE__, __LINE__, vmSymbols::java_lang_StackOverflowError(), "Stack overflow while freezing");
    return true;
  }
  return false;
}

void FreezeBase::init_chunk(stackChunkOop chunk) {
  chunk->clear_flags();
  chunk->set_gc_mode(false);
  chunk->set_max_size(0);
  chunk->set_sp(chunk->stack_size());
  chunk->set_pc(nullptr);
  chunk->set_argsize(0);
}

template <typename ConfigT>
stackChunkOop Freeze<ConfigT>::allocate_chunk(size_t stack_size) {
  log_develop_trace(jvmcont)("allocate_chunk allocating new chunk");

  InstanceStackChunkKlass* klass = InstanceStackChunkKlass::cast(vmClasses::StackChunk_klass());
  size_t size_in_words = klass->instance_size(stack_size);

  JavaThread* current = _preempt ? JavaThread::current() : _thread;
  assert(current == JavaThread::current(), "should be current");

  stackChunkOop chunk;
  StackChunkAllocator allocator(klass, size_in_words, stack_size, current);
  HeapWord* start = current->tlab().allocate(size_in_words);
  if (start != nullptr) {
    chunk = stackChunkOopDesc::cast(allocator.init_partial_for_tlab(start));
  } else {
    Handle conth(current, _cont.continuation());
    chunk = stackChunkOopDesc::cast(allocator.allocate()); // can safepoint
    _cont.post_safepoint(conth);

    if (chunk == nullptr) { // OOME
      return nullptr;
    }
  }

  assert(chunk->stack_size() == (int)stack_size, "");
  assert(chunk->size() >= stack_size, "chunk->size(): %zu size: %zu", chunk->size(), stack_size);
  assert((intptr_t)chunk->start_address() % 8 == 0, "");

  init_chunk(chunk);

  assert(chunk->flags() == 0, "");
  assert(chunk->is_gc_mode() == false, "");
  assert(chunk->max_size() == 0, "");

  chunk->set_mark(chunk->mark().set_age(15)); // Promote young chunks quickly

  stackChunkOop chunk0 = _cont.tail();
  if (chunk0 != nullptr && chunk0->is_empty()) {
    chunk0 = chunk0->parent();
    assert(chunk0 == nullptr || !chunk0->is_empty(), "");
  }
  // fields are uninitialized
  chunk->set_parent_raw<typename ConfigT::OopT>(chunk0);
  chunk->set_cont_raw<typename ConfigT::OopT>(_cont.continuation());

  assert(chunk->parent() == nullptr || chunk->parent()->is_stackChunk(), "");

  if (start != nullptr) {
    assert(!chunk->requires_barriers(), "Unfamiliar GC requires barriers on TLAB allocation");
  } else {
    assert(!UseZGC || !chunk->requires_barriers(), "Allocated ZGC object requires barriers");
    _barriers = !UseZGC && chunk->requires_barriers();
  }

  _cont.set_tail(chunk);
  return chunk;
}

void FreezeBase::throw_stack_overflow_on_humongous_chunk() {
  Exceptions::_throw_msg(_thread, __FILE__, __LINE__, vmSymbols::java_lang_StackOverflowError(), "Humongous stack chunk");
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

static void JVMTI_yield_cleanup(JavaThread* thread, ContinuationWrapper& cont) {
#if INCLUDE_JVMTI
  if (JvmtiExport::can_post_frame_pop()) {
    int num_frames = num_java_frames(cont);

    // The call to JVMTI can safepoint, so we need to restore oops.
    Handle conth(Thread::current(), cont.continuation());
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
  ContinuationEntry* ce = thread->last_continuation();
  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  for (frame f = thread->last_frame(); Continuation::is_frame_in_continuation(ce, f); f = f.sender(&map)) {
    if (is_pinned(f, &map) == freeze_pinned_monitor) {
      return true;
    }
  }
  return false;
}

static bool interpreted_native_or_deoptimized_on_stack(JavaThread* thread) {
  ContinuationEntry* ce = thread->last_continuation();
  RegisterMap map(thread, false, false, false);
  map.set_include_argument_oops(false);
  for (frame f = thread->last_frame(); Continuation::is_frame_in_continuation(ce, f); f = f.sender(&map)) {
    if (f.is_interpreted_frame() || f.is_native_frame() || f.is_deoptimized_frame()) {
      return true;
    }
  }
  return false;
}
#endif

static inline bool can_freeze_fast(JavaThread* thread) {
  // There are no interpreted frames if we're not called from the interpreter and we haven't ancountered an i2c adapter or called Deoptimization::unpack_frames
  // Calls from native frames also go through the interpreter (see JavaCalls::call_helper)
  assert(!thread->cont_fastpath()
         || (thread->cont_fastpath_thread_state() && !interpreted_native_or_deoptimized_on_stack(thread)), "");

  // We also clear thread->cont_fastpath on deoptimization (notify_deopt) and when we thaw interpreted frames
  bool fast = thread->cont_fastpath() && UseContinuationFastPath;
  assert(!fast || monitors_on_stack(thread) == (thread->held_monitor_count() > 0), "");
  fast = fast && thread->held_monitor_count() == 0;
  return fast;
}

static int early_return(int res, JavaThread* thread) {
  thread->set_cont_yield(false);
  log_develop_trace(jvmcont)("=== end of freeze (fail %d)", res);
  return res;
}

static inline int freeze_epilog(JavaThread* thread, ContinuationWrapper& cont) {
  verify_continuation(cont.continuation());
  assert(!cont.is_empty(), "");

  thread->set_cont_yield(false);
  log_develop_debug(jvmcont)("=== End of freeze cont ### #" INTPTR_FORMAT, cont.hash());

  return 0;
}

static int freeze_epilog(JavaThread* thread, ContinuationWrapper& cont, freeze_result res) {
  if (UNLIKELY(res != freeze_ok)) {
    verify_continuation(cont.continuation());
    return early_return(res, thread);
  }

  JVMTI_yield_cleanup(thread, cont); // can safepoint
  return freeze_epilog(thread, cont);
}

template<typename ConfigT, bool preempt>
static inline int freeze0(JavaThread* current, intptr_t* const sp) {
  assert(!current->cont_yield(), "");
  assert(!current->has_pending_exception(), "");
  assert(current->deferred_updates() == nullptr || current->deferred_updates()->count() == 0, "");
  assert(!preempt || current->thread_state() == _thread_in_vm || current->thread_state() == _thread_blocked,
         "thread_state: %d %s", current->thread_state(), current->thread_state_name());

#ifdef ASSERT
  log_trace(jvmcont)("~~~~ freeze sp: " INTPTR_FORMAT, p2i(current->last_continuation()->entry_sp()));
  log_frames(current);
#endif

  CONT_JFR_ONLY(EventContinuationFreeze event;)

  current->set_cont_yield(true);

  ContinuationEntry* entry = current->last_continuation();

  oop oopCont = ContinuationHelper::get_continuation(current);
  assert(oopCont == current->last_continuation()->cont_oop(), "");
  assert(ContinuationEntry::assert_entry_frame_laid_out(current), "");

  verify_continuation(oopCont);
  ContinuationWrapper cont(current, oopCont);
  log_develop_debug(jvmcont)("FREEZE #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

  assert(entry->is_virtual_thread() == (entry->scope() == java_lang_VirtualThread::vthread_scope()), "");

  if (entry->is_pinned()) {
    log_develop_debug(jvmcont)("PINNED due to critical section");
    verify_continuation(cont.continuation());
    return early_return(freeze_pinned_cs, current);
  }

  bool fast = can_freeze_fast(current);
  assert(!fast || current->held_monitor_count() == 0, "");

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
    assert(res == freeze_ok, "");
    CONT_JFR_ONLY(cont.post_jfr_event(&event, current);)
    freeze_epilog(current, cont);
    StackWatermarkSet::after_unwind(current);
    return 0;
  }

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
  ContinuationEntry* entry = thread->last_continuation();
  if (entry == nullptr) {
    return freeze_ok;
  }
  if (entry->is_pinned()) {
    return freeze_pinned_cs;
  }

  RegisterMap map(thread, true, false, false);
  map.set_include_argument_oops(false);
  frame f = thread->last_frame();

  if (!safepoint) {
    f = f.sender(&map); // this is the yield frame
  } else { // safepoint yield
#if (defined(X86) || defined(AARCH64)) && !defined(ZERO)
    f.set_fp(f.real_fp()); // Instead of this, maybe in ContinuationWrapper::set_last_frame always use the real_fp?
#else
    Unimplemented();
#endif
    if (!Interpreter::contains(f.pc())) {
      assert(Frame::is_stub(f.cb()), "must be");
      assert(f.oop_map() != nullptr, "must be");
      f.oop_map()->update_register_map(&f, &map); // we have callee-save registers in this case
    }
  }

  while (true) {
    freeze_result res = is_pinned(f, &map);
    if (res != freeze_ok) {
      return res;
    }

    f = f.sender(&map);
    if (!Continuation::is_frame_in_continuation(entry, f)) {
      oop scope = jdk_internal_vm_Continuation::scope(entry->continuation());
      if (scope == cont_scope) {
        break;
      }
      entry = entry->parent();
      if (entry == nullptr) {
        break;
      }
      if (entry->is_pinned()) {
        return freeze_pinned_cs;
      }
    }
  }
  return freeze_ok;
}

static bool is_safe_frame_to_preempt(JavaThread* thread) {
  assert(thread->has_last_Java_frame(), "");
  vframeStream st(thread);
  st.dont_walk_cont();

  // We don't want to preempt inside methods annotated with @JvmtiMountTransition
  int i = 0;
  for (;!st.at_end(); st.next()) {
    if (++i > 5) {
      // annotations are never deep
      break;
    }
    if (st.method()->jvmti_mount_transition()) {
      return false;
    }
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
        assert(codelet->kind() == InterpreterCodelet::codelet_bytecode, "");
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
  if (!is_safe_pc_to_preempt(thread->last_Java_pc())) {
    return false;
  }
  if (!is_safe_frame_to_preempt(thread)) {
    return false;
  }
  return true;
}

/////////////// THAW ////

// make room on the stack for thaw
// returns the size in bytes, or 0 on failure
static inline int prepare_thaw0(JavaThread* thread, bool return_barrier) {
  log_develop_trace(jvmcont)("~~~~ prepare_thaw return_barrier: %d", return_barrier);

  assert(thread == JavaThread::current(), "");

  oop continuation = thread->last_continuation()->cont_oop();
  assert(continuation == ContinuationHelper::get_continuation(thread), "");
  verify_continuation(continuation);

  stackChunkOop chunk = jdk_internal_vm_Continuation::tail(continuation);
  assert(chunk != nullptr, "");
  if (UNLIKELY(chunk->is_empty())) {
    chunk = chunk->parent();
    jdk_internal_vm_Continuation::set_tail(continuation, chunk);
  }
  assert(chunk != nullptr, "");
  assert(!chunk->is_empty(), "");
  verify_stack_chunk(chunk);

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

class ThawBase : public StackObj {
protected:
  JavaThread* _thread;
  ContinuationWrapper& _cont;

  intptr_t* _fastpath;
  bool _barriers;
  intptr_t* _top_unextended_sp;
  int _align_size;

  StackChunkFrameStream<chunk_frames::MIXED> _stream;

  NOT_PRODUCT(int _frames;)

#ifdef ASSERT
  public:
    bool barriers() { return _barriers; }
  protected:
#endif

protected:
  ThawBase(JavaThread* thread, ContinuationWrapper& cont) :
      _thread(thread), _cont(cont),
      _fastpath(nullptr) {
    DEBUG_ONLY(_top_unextended_sp = nullptr;)
  }

  void copy_from_chunk(intptr_t* from, intptr_t* to, int size);

  // fast path
  inline void prefetch_chunk_pd(void* start, int size_words);
  void patch_chunk(intptr_t* sp, bool is_last);
  void patch_chunk_pd(intptr_t* sp);

  // slow path
  NOINLINE intptr_t* thaw_slow(stackChunkOop chunk, bool return_barrier);

private:
  void thaw(const frame& hf, frame& caller, int num_frames, bool top);
  template<typename FKind> bool recurse_thaw_java_frame(frame& caller, int num_frames);
  void finalize_thaw(frame& entry, int argsize);

  inline void before_thaw_java_frame(const frame& hf, const frame& caller, bool bottom, int num_frame);
  inline void after_thaw_java_frame(const frame& f, bool bottom);
  inline void patch(frame& f, const frame& caller, bool bottom);
  void clear_bitmap_bits(intptr_t* start, int range);

  NOINLINE void recurse_thaw_interpreted_frame(const frame& hf, frame& caller, int num_frames);
  void recurse_thaw_compiled_frame(const frame& hf, frame& caller, int num_frames, bool stub_caller);
  void recurse_thaw_stub_frame(const frame& hf, frame& caller, int num_frames);
  void finish_thaw(frame& f);

  void push_return_frame(frame& f);
  inline frame new_entry_frame();
  template<typename FKind> frame new_frame(const frame& hf, frame& caller, bool bottom);
  inline void patch_pd(frame& f, const frame& sender);
  inline intptr_t* align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom);

  intptr_t* push_interpreter_return_frame(intptr_t* sp);
  void maybe_set_fastpath(intptr_t* sp) { if (sp > _fastpath) _fastpath = sp; }

  static inline void derelativize_interpreted_frame_metadata(const frame& hf, const frame& f);
  static inline void set_interpreter_frame_bottom(const frame& f, intptr_t* bottom);
  static void JVMTI_continue_cleanup(JavaThread* thread);
};

template <typename ConfigT>
class Thaw : public ThawBase {
public:
  Thaw(JavaThread* thread, ContinuationWrapper& cont) : ThawBase(thread, cont) {}

  inline bool can_thaw_fast(stackChunkOop chunk) {
    return    !_barriers
           &&  _thread->cont_fastpath_thread_state()
           && !chunk->has_thaw_slowpath_condition();
  }

  inline intptr_t* thaw(thaw_kind kind);
  NOINLINE intptr_t* thaw_fast(stackChunkOop chunk);
};

template <typename ConfigT>
inline intptr_t* Thaw<ConfigT>::thaw(thaw_kind kind) {
  assert(!Interpreter::contains(_cont.entryPC()), "");

  verify_continuation(_cont.continuation());
  assert(!jdk_internal_vm_Continuation::done(_cont.continuation()), "");
  assert(!_cont.is_empty(), "");

  stackChunkOop chunk = _cont.tail();
  assert(chunk != nullptr && !chunk->is_empty(), ""); // guaranteed by prepare_thaw

  _barriers = chunk->requires_barriers();
  return (LIKELY(can_thaw_fast(chunk))) ? thaw_fast(chunk)
                                        : thaw_slow(chunk, kind != thaw_top);
}

template <typename ConfigT>
NOINLINE intptr_t* Thaw<ConfigT>::thaw_fast(stackChunkOop chunk) {
  assert(chunk != (oop) nullptr, "");
  assert(chunk == _cont.tail(), "");
  assert(!chunk->is_empty(), "");
  assert(!chunk->has_mixed_frames(), "");
  assert(!chunk->requires_barriers(), "");
  assert(!chunk->has_bitmap(), "");
  assert(!_thread->is_interp_only_mode(), "");

  // TODO: explain why we're not setting the tail

  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("thaw_fast");
    chunk->print_on(true, &ls);
  }

  static const int threshold = 500; // words

  int chunk_start_sp = chunk->sp();
  const int full_chunk_size = chunk->stack_size() - chunk_start_sp; // this initial size could be reduced if it's a partial thaw
  int argsize, thaw_size;

  intptr_t* const chunk_sp = chunk->start_address() + chunk_start_sp;

  bool partial, empty;
  if (LIKELY(!TEST_THAW_ONE_CHUNK_FRAME && (full_chunk_size < threshold))) {
    prefetch_chunk_pd(chunk->start_address(), full_chunk_size); // prefetch anticipating memcpy starting at highest address

    partial = false;

    argsize = chunk->argsize();
    empty = true;

    chunk->set_sp(chunk->stack_size());
    chunk->set_argsize(0);
    chunk->set_max_size(0);

    thaw_size = full_chunk_size;
  } else { // thaw a single frame
    partial = true;

    StackChunkFrameStream<chunk_frames::COMPILED_ONLY> f(chunk);
    assert(chunk_sp == f.sp() && chunk_sp == f.unextended_sp(), "");

    const int frame_size = f.cb()->frame_size();
    argsize = f.stack_argsize();

    f.next(SmallRegisterMap::instance);
    empty = f.is_done();
    assert(!empty || argsize == chunk->argsize(), "");

    if (empty) {
      chunk->set_sp(chunk->stack_size());
      chunk->set_argsize(0);
      chunk->set_max_size(0);
    } else {
      chunk->set_sp(chunk->sp() + frame_size);
      chunk->set_max_size(chunk->max_size() - frame_size);
      address top_pc = *(address*)(chunk_sp + frame_size - frame::sender_sp_ret_address_offset());
      chunk->set_pc(top_pc);
    }
    assert(empty == chunk->is_empty(), "");
    thaw_size = frame_size + argsize;
  }

  const bool is_last = empty && chunk->is_parent_null<typename ConfigT::OopT>();

  log_develop_trace(jvmcont)("thaw_fast partial: %d is_last: %d empty: %d size: %d argsize: %d",
                              partial, is_last, empty, thaw_size, argsize);

  intptr_t* stack_sp = _cont.entrySP();
  intptr_t* bottom_sp = ContinuationHelper::frame_align_pointer(stack_sp - argsize);

  stack_sp -= thaw_size;
  assert(argsize != 0 || stack_sp == ContinuationHelper::frame_align_pointer(stack_sp), "");
  stack_sp = ContinuationHelper::frame_align_pointer(stack_sp);

  intptr_t* from = chunk_sp - ContinuationHelper::frame_metadata;
  intptr_t* to   = stack_sp - ContinuationHelper::frame_metadata;
  copy_from_chunk(from, to, thaw_size + ContinuationHelper::frame_metadata);
  assert(_cont.entrySP() - 1 <= to + thaw_size + ContinuationHelper::frame_metadata
            && to + thaw_size + ContinuationHelper::frame_metadata <= _cont.entrySP(), "");
  assert(argsize != 0 || to + thaw_size + ContinuationHelper::frame_metadata == _cont.entrySP(), "");

  assert(!is_last || argsize == 0, "");
  _cont.set_argsize(argsize);
  log_develop_trace(jvmcont)("setting entry argsize: %d", _cont.argsize());
  patch_chunk(bottom_sp, is_last);

  DEBUG_ONLY(address pc = *(address*)(bottom_sp - frame::sender_sp_ret_address_offset());)
  assert(is_last ? CodeCache::find_blob(pc)->as_compiled_method()->method()->is_continuation_enter_intrinsic()
                  : pc == StubRoutines::cont_returnBarrier(), "is_last: %d", is_last);
  assert(is_last == _cont.is_empty(), "");
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
  ContinuationHelper::set_anchor(_thread, stack_sp);
  log_frames(_thread);
  if (LoomDeoptAfterThaw) {
    do_deopt_after_thaw(_thread);
  }
  ContinuationHelper::clear_anchor(_thread);
#endif

  return stack_sp;
}

void ThawBase::copy_from_chunk(intptr_t* from, intptr_t* to, int size) {
  assert(to + size <= _cont.entrySP(), "");
  _cont.tail()->copy_from_chunk_to_stack(from, to, size);
  CONT_JFR_ONLY(_cont.record_size_copied(size);)
}

void ThawBase::patch_chunk(intptr_t* sp, bool is_last) {
  log_develop_trace(jvmcont)("thaw_fast patching -- sp: " INTPTR_FORMAT, p2i(sp));

  address pc = !is_last ? StubRoutines::cont_returnBarrier() : _cont.entryPC();
  *(address*)(sp - frame::sender_sp_ret_address_offset()) = pc;

  // patch_chunk_pd(sp); -- TODO: If not needed - remove method; it's not used elsewhere
}

NOINLINE intptr_t* ThawBase::thaw_slow(stackChunkOop chunk, bool return_barrier) {
  assert(!_cont.is_empty(), "");
  assert(chunk != nullptr, "");
  assert(!chunk->is_empty(), "");

  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("thaw slow return_barrier: %d " INTPTR_FORMAT, return_barrier, p2i(chunk));
    chunk->print_on(true, &ls);
  }

  EventContinuationThawOld e;
  if (e.should_commit()) {
    e.set_id(cast_from_oop<u8>(_cont.continuation()));
    e.commit();
  }

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
    assert(hf.is_heap_frame(), "should have created a relative frame");
    hf.print_on(&ls);
  }

  frame f;
  thaw(hf, f, num_frames, true);
  finish_thaw(f); // f is now the topmost thawed frame
  _cont.write();

  assert(_cont.chunk_invariant(tty), "");

  if (!return_barrier) {
    JVMTI_continue_cleanup(_thread);
  }

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
    assert(f.is_interpreted_frame() || f.is_compiled_frame() || f.is_safepoint_blob_frame(), "");
  }
#endif

  if (last_interpreted && _cont.is_preempted()) {
    assert(f.pc() == *(address*)(sp - frame::sender_sp_ret_address_offset()), "");
    assert(Interpreter::contains(f.pc()), "");
    sp = push_interpreter_return_frame(sp);
  }

  return sp;
}

void ThawBase::thaw(const frame& hf, frame& caller, int num_frames, bool top) {
  log_develop_debug(jvmcont)("thaw num_frames: %d", num_frames);
  assert(!_cont.is_empty(), "no more frames");
  assert(num_frames > 0 && !hf.is_empty(), "");

  if (top && hf.is_safepoint_blob_frame()) {
    assert(Frame::is_stub(hf.cb()), "cb: %s", hf.cb()->name());
    recurse_thaw_stub_frame(hf, caller, num_frames);
  } else if (!hf.is_interpreted_frame()) {
    recurse_thaw_compiled_frame(hf, caller, num_frames, false);
  } else {
    recurse_thaw_interpreted_frame(hf, caller, num_frames);
  }
}

template<typename FKind>
bool ThawBase::recurse_thaw_java_frame(frame& caller, int num_frames) {
  assert(num_frames > 0, "");

  DEBUG_ONLY(_frames++;)

  int argsize = _stream.stack_argsize();

  _stream.next(SmallRegisterMap::instance);
  assert(_stream.to_frame().is_empty() == _stream.is_done(), "");

  // we never leave a compiled caller of an interpreted frame as the top frame in the chunk
  // as it makes detecting that situation and adjusting unextended_sp tricky
  if (num_frames == 1 && !_stream.is_done() && FKind::interpreted && _stream.is_compiled()) {
    log_develop_trace(jvmcont)("thawing extra compiled frame to not leave a compiled interpreted-caller at top");
    num_frames++;
  }

  if (num_frames == 1 || _stream.is_done()) { // end recursion
    finalize_thaw(caller, FKind::interpreted ? 0 : argsize);
    return true; // bottom
  } else { // recurse
    thaw(_stream.to_frame(), caller, num_frames - 1, false);
    return false;
  }
}

void ThawBase::finalize_thaw(frame& entry, int argsize) {
  stackChunkOop chunk = _cont.tail();

  OrderAccess::storestore();
  if (!_stream.is_done()) {
    assert(_stream.sp() >= chunk->sp_address(), "");
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

  _cont.set_argsize(argsize);
  entry = new_entry_frame();

  assert(entry.sp() == _cont.entrySP(), "");
  assert(Continuation::is_continuation_enterSpecial(entry), "");
  assert(_cont.is_entry_frame(entry), "");
}

inline void ThawBase::before_thaw_java_frame(const frame& hf, const frame& caller, bool bottom, int num_frame) {
  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("======== THAWING FRAME: %d", num_frame);
    assert(hf.is_heap_frame(), "should be");
    hf.print_on(&ls);
  }
  assert(bottom == _cont.is_entry_frame(caller), "bottom: %d is_entry_frame: %d", bottom, _cont.is_entry_frame(hf));
}

inline void ThawBase::after_thaw_java_frame(const frame& f, bool bottom) {
  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("thawed frame:");
    f.print_on(&ls);
  }
}

inline void ThawBase::patch(frame& f, const frame& caller, bool bottom) {
  assert(!bottom || caller.fp() == _cont.entryFP(), "");
  if (bottom) {
    Frame::patch_pc(caller, _cont.is_empty() ? caller.raw_pc() : StubRoutines::cont_returnBarrier());
  }

  patch_pd(f, caller); // TODO: reevaluate if and when this is necessary -only bottom & interpreted caller?

  if (f.is_interpreted_frame()) {
    Interpreted::patch_sender_sp(f, caller.unextended_sp());
  }

  assert(!bottom || !_cont.is_empty() || Continuation::is_continuation_entry_frame(f, nullptr), "");
  assert(!bottom || (_cont.is_empty() != Continuation::is_cont_barrier_frame(f)), "");
}

  void ThawBase::clear_bitmap_bits(intptr_t* start, int range) {
    // we need to clear the bits that correspond to arguments as they reside in the caller frame
    log_develop_trace(jvmcont)("clearing bitmap for " INTPTR_FORMAT " - " INTPTR_FORMAT, p2i(start), p2i(start+range));
    stackChunkOop chunk = _cont.tail();
    chunk->bitmap().clear_range(chunk->bit_index_for(start),
                                chunk->bit_index_for(start+range));
  }

NOINLINE void ThawBase::recurse_thaw_interpreted_frame(const frame& hf, frame& caller, int num_frames) {
  assert(hf.is_interpreted_frame(), "");

  if (UNLIKELY(_barriers)) {
    _cont.tail()->do_barriers<stackChunkOopDesc::barrier_type::STORE>(_stream, SmallRegisterMap::instance);
  }

  const bool bottom = recurse_thaw_java_frame<Interpreted>(caller, num_frames);

  DEBUG_ONLY(before_thaw_java_frame(hf, caller, bottom, num_frames);)

  frame f = new_frame<Interpreted>(hf, caller, bottom);

  intptr_t* const vsp = f.sp();
  intptr_t* const hsp = hf.unextended_sp();
  intptr_t* const frame_bottom = Interpreted::frame_bottom(f);

  assert(hf.is_heap_frame(), "should be");
  const int fsize = Interpreted::frame_bottom(hf) - hsp;

  assert(!bottom || vsp + fsize >= _cont.entrySP() - 2, "");
  assert(!bottom || vsp + fsize <= _cont.entrySP(), "");

  assert(Interpreted::frame_bottom(f) == vsp + fsize, "");

  // on AArch64 we add padding between the locals and the rest of the frame to keep the fp 16-byte-aligned
  const int locals = hf.interpreter_frame_method()->max_locals();
  assert(hf.is_heap_frame(), "should be");
  assert(!f.is_heap_frame(), "should not be");

  copy_from_chunk(Interpreted::frame_bottom(hf) - locals,
                  Interpreted::frame_bottom(f) - locals, locals); // copy locals
  copy_from_chunk(hsp, vsp, fsize - locals); // copy rest

  set_interpreter_frame_bottom(f, frame_bottom); // the copy overwrites the metadata
  derelativize_interpreted_frame_metadata(hf, f);
  patch(f, caller, bottom);

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
    _cont.tail()->fix_thawed_frame(caller, SmallRegisterMap::instance);
  } else if (_cont.tail()->has_bitmap() && locals > 0) {
    assert(hf.is_heap_frame(), "should be");
    clear_bitmap_bits(Interpreted::frame_bottom(hf) - locals, locals);
  }

  DEBUG_ONLY(after_thaw_java_frame(f, bottom);)
  caller = f;
}

void ThawBase::recurse_thaw_compiled_frame(const frame& hf, frame& caller, int num_frames, bool stub_caller) {
  assert(!hf.is_interpreted_frame(), "");
  assert(_cont.is_preempted() || !stub_caller, "stub caller not at preemption");

  if (!stub_caller && UNLIKELY(_barriers)) { // recurse_thaw_stub_frame already invoked our barriers with a full regmap
    _cont.tail()->do_barriers<stackChunkOopDesc::barrier_type::STORE>(_stream, SmallRegisterMap::instance);
  }

  const bool bottom = recurse_thaw_java_frame<Compiled>(caller, num_frames);

  DEBUG_ONLY(before_thaw_java_frame(hf, caller, bottom, num_frames);)

  assert(caller.sp() == caller.unextended_sp(), "");

  if ((!bottom && caller.is_interpreted_frame()) || (bottom && Interpreter::contains(_cont.tail()->pc()))) {
    _align_size += ContinuationHelper::align_wiggle; // we add one whether or not we've aligned because we add it in freeze_interpreted_frame
  }

  frame f = new_frame<Compiled>(hf, caller, bottom);
  intptr_t* const vsp = f.sp();
  intptr_t* const hsp = hf.unextended_sp();

  const int added_argsize = (bottom || caller.is_interpreted_frame()) ? hf.compiled_frame_stack_argsize() : 0;
  int fsize = Compiled::size(hf) + added_argsize;
  assert(fsize <= (int)(caller.unextended_sp() - f.unextended_sp()), "");

  intptr_t* from = hsp - ContinuationHelper::frame_metadata;
  intptr_t* to   = vsp - ContinuationHelper::frame_metadata;
  int sz = fsize + ContinuationHelper::frame_metadata;

  assert(!bottom || _cont.entrySP() - 1 <= to + sz && to + sz <= _cont.entrySP(), "");
  assert(!bottom || hf.compiled_frame_stack_argsize() != 0 || to + sz && to + sz == _cont.entrySP(), "");

  copy_from_chunk(from, to, sz);

  patch(f, caller, bottom);

  if (f.cb()->is_nmethod()) {
    f.cb()->as_nmethod()->run_nmethod_entry_barrier();
  }

  if (f.is_deoptimized_frame()) {
    maybe_set_fastpath(f.sp());
  } else if (_thread->is_interp_only_mode()
              || (_cont.is_preempted() && f.cb()->as_compiled_method()->is_marked_for_deoptimization())) {
    // The caller of the safepoint stub when the continuation is preempted is not at a call instruction, and so
    // cannot rely on nmethod patching for deopt.
    assert(_thread->is_interp_only_mode() || stub_caller, "expected a stub-caller");

    log_develop_trace(jvmcont)("Deoptimizing thawed frame");
    DEBUG_ONLY(Frame::patch_pc(f, nullptr));

    f.deoptimize(nullptr); // we're assuming there are no monitors; this doesn't revoke biased locks
    assert(f.is_deoptimized_frame() && Frame::is_deopt_return(f.raw_pc(), f), "");
    maybe_set_fastpath(f.sp());
  }

  if (!bottom) {
    // can only fix caller once this frame is thawed (due to callee saved regs)
    _cont.tail()->fix_thawed_frame(caller, SmallRegisterMap::instance);
  } else if (_cont.tail()->has_bitmap() && added_argsize > 0) {
    clear_bitmap_bits(hsp + Compiled::size(hf), added_argsize);
  }

  DEBUG_ONLY(after_thaw_java_frame(f, bottom);)
  caller = f;
}

void ThawBase::recurse_thaw_stub_frame(const frame& hf, frame& caller, int num_frames) {
  DEBUG_ONLY(_frames++;)

  {
    RegisterMap map(nullptr, true, false, false);
    map.set_include_argument_oops(false);
    _stream.next(&map);
    assert(!_stream.is_done(), "");
    if (UNLIKELY(_barriers)) { // we're now doing this on the stub's caller
      _cont.tail()->do_barriers<stackChunkOopDesc::barrier_type::STORE>(_stream, &map);
    }
    assert(!_stream.is_done(), "");
  }

  recurse_thaw_compiled_frame(_stream.to_frame(), caller, num_frames, true); // this could be deoptimized

  DEBUG_ONLY(before_thaw_java_frame(hf, caller, false, num_frames);)

  assert(Frame::is_stub(hf.cb()), "");
  assert(caller.sp() == caller.unextended_sp(), "");
  assert(!caller.is_interpreted_frame(), "");

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
    _cont.tail()->fix_thawed_frame(caller, &map);
  }

  DEBUG_ONLY(after_thaw_java_frame(f, false);)
  caller = f;
}

void ThawBase::finish_thaw(frame& f) {
  stackChunkOop chunk = _cont.tail();

  if (chunk->is_empty()) {
    if (_barriers) {
      _cont.set_tail(chunk->parent());
    } else {
      chunk->set_has_mixed_frames(false);
    }
    chunk->set_max_size(0);
    assert(chunk->argsize() == 0, "");
  } else {
    chunk->set_max_size(chunk->max_size() - _align_size);
  }
  assert(chunk->is_empty() == (chunk->max_size() == 0), "");

  if ((intptr_t)f.sp() % 16 != 0) {
    assert(f.is_interpreted_frame(), "");
    f.set_sp(f.sp() - 1);
  }
  push_return_frame(f);
  chunk->fix_thawed_frame(f, SmallRegisterMap::instance); // can only fix caller after push_return_frame (due to callee saved regs)

  assert(_cont.is_empty() == _cont.last_frame().is_empty(), "");

  log_develop_trace(jvmcont)("thawed %d frames", _frames);

  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("top hframe after (thaw):");
    _cont.last_frame().print_on(&ls);
  }
}

void ThawBase::push_return_frame(frame& f) { // see generate_cont_thaw
  assert(!f.is_compiled_frame() || f.is_deoptimized_frame() == f.cb()->as_compiled_method()->is_deopt_pc(f.raw_pc()), "");
  assert(!f.is_compiled_frame() || f.is_deoptimized_frame() == (f.pc() != f.raw_pc()), "");

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

void ThawBase::JVMTI_continue_cleanup(JavaThread* thread) {
#if INCLUDE_JVMTI
  invalidate_JVMTI_stack(thread);
#endif // INCLUDE_JVMTI
}

// returns new top sp
// called after preparations (stack overflow check and making room)
template<typename ConfigT>
static inline intptr_t* thaw0(JavaThread* thread, const thaw_kind kind) {
  assert(thread == JavaThread::current(), "Must be current thread");

  CONT_JFR_ONLY(EventContinuationThaw event;)

  log_develop_trace(jvmcont)("~~~~ thaw kind: %d sp: " INTPTR_FORMAT, kind, p2i(thread->last_continuation()->entry_sp()));

  ContinuationEntry* entry = thread->last_continuation();
  oop oopCont = entry->cont_oop();

  assert(!jdk_internal_vm_Continuation::done(oopCont), "");
  assert(oopCont == ContinuationHelper::get_continuation(thread), "");
  verify_continuation(oopCont);

  assert(entry->is_virtual_thread() == (entry->scope() == java_lang_VirtualThread::vthread_scope()), "");

  ContinuationWrapper cont(thread, oopCont);
  log_develop_debug(jvmcont)("THAW #" INTPTR_FORMAT " " INTPTR_FORMAT, cont.hash(), p2i((oopDesc*)oopCont));

#ifdef ASSERT
  ContinuationHelper::set_anchor_to_entry(thread, cont.entry());
  log_frames(thread);
  ContinuationHelper::clear_anchor(thread);
#endif

  Thaw<ConfigT> thw(thread, cont);
  intptr_t* const sp = thw.thaw(kind);
  assert((intptr_t)sp % 16 == 0, "");

  thread->reset_held_monitor_count();

  verify_continuation(cont.continuation());

#ifdef ASSERT
  intptr_t* sp0 = sp;
  address pc0 = *(address*)(sp - frame::sender_sp_ret_address_offset());
  if (pc0 == StubRoutines::cont_interpreter_forced_preempt_return()) {
    sp0 += ContinuationHelper::frame_metadata; // see push_interpreter_return_frame
  }
  ContinuationHelper::set_anchor(thread, sp0);
  log_frames(thread);
  if (LoomVerifyAfterThaw) {
    assert(do_verify_after_thaw(thread, thw.barriers(), cont.tail(), tty), "");
  }
  assert(ContinuationEntry::assert_entry_frame_laid_out(thread), "");
  ContinuationHelper::clear_anchor(thread);

  LogTarget(Trace, jvmcont) lt;
  if (lt.develop_is_enabled()) {
    LogStream ls(lt);
    ls.print_cr("Jumping to frame (thaw):");
    frame(sp).print_on(&ls);
  }
#endif

  CONT_JFR_ONLY(cont.post_jfr_event(&event, thread);)

  verify_continuation(cont.continuation());
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
      if (!cm->method()->is_continuation_enter_intrinsic()) {
        cm->make_deoptimized();
      }
    }
  }
}

class ThawVerifyOopsClosure: public OopClosure {
  intptr_t* _p;
  outputStream* _st;
  bool is_good_oop(oop o) {
    return dbg_is_safe(o, -1) && dbg_is_safe(o->klass(), -1) && oopDesc::is_oop(o) && o->klass()->is_klass();
  }
public:
  ThawVerifyOopsClosure(outputStream* st) : _p(nullptr), _st(st) {}
  intptr_t* p() { return _p; }
  void reset() { _p = nullptr; }

  virtual void do_oop(oop* p) {
    oop o = *p;
    if (o == nullptr || is_good_oop(o)) {
      return;
    }
    _p = (intptr_t*)p;
    _st->print_cr("*** non-oop " PTR_FORMAT " found at " PTR_FORMAT, p2i(*p), p2i(p));
  }
  virtual void do_oop(narrowOop* p) {
    oop o = RawAccess<>::oop_load(p);
    if (o == nullptr || is_good_oop(o)) {
      return;
    }
    _p = (intptr_t*)p;
    _st->print_cr("*** (narrow) non-oop %x found at " PTR_FORMAT, (int)(*p), p2i(p));
  }
};

static bool do_verify_after_thaw(JavaThread* thread, bool barriers, stackChunkOop chunk, outputStream* st) {
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
      st->print_cr("Failed for frame barriers: %d %d", barriers, chunk->requires_barriers());
      fr.print_on(st);
      if (!fr.is_interpreted_frame()) {
        st->print_cr("size: %d argsize: %d", NonInterpretedUnknown::size(fr), NonInterpretedUnknown::stack_argsize(fr));
      }
      VMReg reg = fst.register_map()->find_register_spilled_here(cl.p(), fst.current()->sp());
      if (reg != nullptr) {
        st->print_cr("Reg %s %d", reg->name(), reg->is_stack() ? (int)reg->reg2stack() : -99);
      }
      cl.reset();
      DEBUG_ONLY(thread->print_frame_layout();)
      chunk->print_on(true, st);
      return false;
    }
  }
  return true;
}

static void log_frames(JavaThread* thread) {
  LogTarget(Trace, jvmcont) lt;
  if (!lt.develop_is_enabled()) {
    return;
  }
  LogStream ls(lt);

  ls.print_cr("------- frames ---------");
  if (!thread->has_last_Java_frame()) {
    ls.print_cr("NO ANCHOR!");
  }

  RegisterMap map(thread, true, true, false);
  map.set_include_argument_oops(false);

  if (false) {
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) {
      f.print_on(&ls);
    }
  } else {
    map.set_skip_missing(true);
    ResetNoHandleMark rnhm;
    ResourceMark rm;
    HandleMark hm(Thread::current());
    FrameValues values;

    int i = 0;
    for (frame f = thread->last_frame(); !f.is_entry_frame(); f = f.sender(&map)) {
      f.describe(values, i++, &map);
    }
    values.print_on(thread, &ls);
  }

  ls.print_cr("======= end frames =========");
}
#endif

#include CPU_HEADER_INLINE(continuation)

/////////////////////////////////////////////

int ContinuationEntry::return_pc_offset = 0;
nmethod* ContinuationEntry::continuation_enter = nullptr;
address ContinuationEntry::return_pc = nullptr;

void ContinuationEntry::set_enter_nmethod(nmethod* nm) {
  assert(return_pc_offset != 0, "");
  continuation_enter = nm;
  return_pc = nm->code_begin() + return_pc_offset;
}

ContinuationEntry* ContinuationEntry::from_frame(const frame& f) {
  assert(Continuation::is_continuation_enterSpecial(f), "");
  return (ContinuationEntry*)f.unextended_sp();
}

void ContinuationEntry::flush_stack_processing(JavaThread* thread) const {
  ContinuationHelper::maybe_flush_stack_processing(thread, this);
}

/////////////////////////////////////////////

#ifdef ASSERT
bool ContinuationEntry::assert_entry_frame_laid_out(JavaThread* thread) {
  assert(thread->has_last_Java_frame(), "Wrong place to use this assertion");

  ContinuationEntry* entry =
    Continuation::get_continuation_entry_for_continuation(thread, ContinuationHelper::get_continuation(thread));
  assert(entry != nullptr, "");

  intptr_t* unextended_sp = entry->entry_sp();
  intptr_t* sp;
  if (entry->argsize() > 0) {
    sp = entry->bottom_sender_sp();
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
    assert(Continuation::is_continuation_enterSpecial(f), "");
    sp = interpreted_bottom ? f.sp() : entry->bottom_sender_sp();
  }

  assert(sp != nullptr && sp <= entry->entry_sp(), "");
  address pc = *(address*)(sp - frame::sender_sp_ret_address_offset());

  if (pc != StubRoutines::cont_returnBarrier()) {
    CodeBlob* cb = pc != nullptr ? CodeCache::find_blob(pc) : nullptr;
    assert(cb->as_compiled_method()->method()->is_continuation_enter_intrinsic(), "");
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
  assert(f.get_cb() != nullptr, "");
  RegisterMap map(f.is_heap_frame() ?
                  (JavaThread*)nullptr :
                  JavaThread::current(), true, false, false);
  map.set_include_argument_oops(false);
  map.set_skip_missing(true);
  frame::update_map_with_saved_link(&map, Frame::callee_link_address(f));
  const_cast<frame&>(f).describe(values, 0, &map);
  values.print_on((JavaThread*)nullptr, st);
}
#endif

static address thaw_entry   = nullptr;
static address freeze_entry = nullptr;

address Continuation::thaw_entry() {
  return ::thaw_entry;
}

address Continuation::freeze_entry() {
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
  return LoomVM; // vmClasses::Continuation_klass()->is_initialized(); // Arguments::enable_preview();
}

void Continuation::init() {
  ConfigResolve::resolve();
}

#define CC (char*)  /*cast a literal from (const char*)*/
#define FN_PTR(f) CAST_FROM_FN_PTR(void*, &f)

static JNINativeMethod CONT_methods[] = {
    {CC"tryForceYield0",   CC"(Ljava/lang/Thread;)I",                  FN_PTR(CONT_TryForceYield0)},
    {CC"pin",              CC"()V",                                    FN_PTR(CONT_pin)},
    {CC"unpin",            CC"()V",                                    FN_PTR(CONT_unpin)},
    {CC"isPinned0",        CC"(Ljdk/internal/vm/ContinuationScope;)I", FN_PTR(CONT_isPinned0)},
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls) {
    Thread* thread = Thread::current();
    assert(thread->is_Java_thread(), "");
    ThreadToNativeFromVM trans((JavaThread*)thread);
    int status = env->RegisterNatives(cls, CONT_methods, sizeof(CONT_methods)/sizeof(JNINativeMethod));
    guarantee(status == JNI_OK && !env->ExceptionOccurred(), "register jdk.internal.vm.Continuation natives");
}

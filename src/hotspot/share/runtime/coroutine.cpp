/*
 * Copyright 2001-2010 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *
 */

#include "precompiled.hpp"

#if INCLUDE_KONA_FIBER
#include "runtime/coroutine.hpp"
#ifdef TARGET_ARCH_x86
# include "vmreg_x86.inline.hpp"
#endif
#ifdef TARGET_ARCH_aarch64
# include "vmreg_aarch64.inline.hpp"
#endif
#include "services/threadService.hpp"
//#include "gc/cms/concurrentMarkSweepThread.hpp"
#include "gc/g1/g1ConcurrentMarkThread.inline.hpp"
//#include "gc/parallel/pcTasks.hpp"
#include "gc/shared/barrierSetNMethod.hpp"
#include "runtime/java.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/jniHandles.inline.hpp"
#include "runtime/stackFrameStream.inline.hpp"
#include "runtime/thread.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/os.inline.hpp"
#include "runtime/globals_extension.hpp"
#include "oops/method.inline.hpp"
#if INCLUDE_ZGC
#include "gc/z/zBarrierSet.inline.hpp"
#endif

JavaThread* Coroutine::_main_thread = NULL;
Method* Coroutine::_continuation_start = NULL;
Coroutine::ConcCoroStage Coroutine::_conc_stage = Coroutine::_Uninitialized;
int Coroutine::_conc_claim_parity = 1;

ContBucket* ContContainer::_buckets= NULL;

Mutex* ContReservedStack::_lock = NULL;
GrowableArray<address>* ContReservedStack::free_array = NULL;
ContPreMappedStack* ContReservedStack::current_pre_mapped_stack = NULL;
uintx ContReservedStack::stack_size = 0;
int ContReservedStack::free_array_uncommit_index = 0;
Method* Coroutine::_try_compensate_method = NULL;
Method* Coroutine::_update_active_count_method = NULL;

void ContReservedStack::init() {
  _lock = new Mutex(Mutex::nosafepoint, "InitializedStack");

  free_array = new (ResourceObj::C_HEAP, mtCoroutine)GrowableArray<address>(CONT_RESERVED_PHYSICAL_MEM_MAX, mtCoroutine);
  stack_size = align_up(DefaultCoroutineStackSize +
               StackOverflow::stack_reserved_zone_size() +
               StackOverflow::stack_yellow_zone_size() +
               StackOverflow::stack_red_zone_size()
               , os::vm_page_size());
}

bool ContReservedStack::add_pre_mapped_stack() {
  uintx alloc_real_stack_size = stack_size * CONT_PREMAPPED_STACK_NUM;
  uintx reserved_size = align_up(alloc_real_stack_size, os::vm_allocation_granularity());

  ContPreMappedStack* node = new ContPreMappedStack(reserved_size, current_pre_mapped_stack);
  if (node == NULL) {
    return false;
  }

  if (!node->initialize_virtual_space(alloc_real_stack_size)) {
    delete node;
    return false;
  }

  current_pre_mapped_stack = node;
  MemTracker::record_virtual_memory_type((address)node->get_base_address() - reserved_size, mtCoroutineStack);
  return true;
}

void ContReservedStack::insert_stack(address node) {
  MutexLocker ml(_lock, Mutex::_no_safepoint_check_flag);
  free_array->append(node);

  /*if (free_array->length() - free_array_uncommit_index > CONT_RESERVED_PHYSICAL_MEM_MAX) {
    address target = free_array->at(free_array_uncommit_index);
    os::free_heap_physical_memory((char *)(target - ContReservedStack::stack_size), ContReservedStack::stack_size);
    free_array_uncommit_index++;
  }*/
}

address ContReservedStack::get_stack_from_free_array() {
  MutexLocker ml(_lock, Mutex::_no_safepoint_check_flag);
  if (free_array->is_empty()) {
    return NULL;
  }

  address stack_base = free_array->pop();
  //if (free_array->length() <= free_array_uncommit_index) {
    /* The node which is ahead of uncommit index has no physical memory */
  //  free_array_uncommit_index = free_array->length();
  //}
  return stack_base;
}

bool ContReservedStack::pre_mapped_stack_is_full() {
  if (current_pre_mapped_stack->allocated_num >= CONT_PREMAPPED_STACK_NUM) {
    return true;
  }

  return false;
}

address ContReservedStack::acquire_stack() {
  address result = current_pre_mapped_stack->get_base_address() - current_pre_mapped_stack->allocated_num * stack_size;
  current_pre_mapped_stack->allocated_num++;

  return result;
}

address ContReservedStack::get_stack_from_pre_mapped() {
  address stack_base;
  {
    MutexLocker ml(_lock, Mutex::_no_safepoint_check_flag);
    if ((current_pre_mapped_stack == NULL) || pre_mapped_stack_is_full()) {
      if (!add_pre_mapped_stack()) {
        return NULL;
      }
    }

    stack_base = acquire_stack();
  }

  /* guard reserved, yellow page and red page of virtual space */
  if (os::uses_stack_guard_pages()) {
    address low_addr = stack_base - ContReservedStack::stack_size;
    size_t len = StackOverflow::stack_guard_zone_size();
    assert(is_aligned(low_addr, os::vm_page_size()), "Stack base should be the start of a page");
    assert(is_aligned(len, os::vm_page_size()), "Stack size should be a multiple of page size");

    if (os::guard_memory((char *) low_addr, len)) {
      //_stack_guard_state = stack_guard_enabled;
    } else {
      log_warning(os, thread)("Attempt to protect stack guard pages failed ("
        PTR_FORMAT "-" PTR_FORMAT ").", p2i(low_addr), p2i(low_addr + len));
      if (os::uncommit_memory((char *) low_addr, len)) {
        log_warning(os, thread)("Attempt to deallocate stack guard pages failed.");
      }
    }
  }
  return stack_base;
}

address ContReservedStack::get_stack() {
  address stack_base = ContReservedStack::get_stack_from_free_array();
  if (stack_base != NULL) {
    return stack_base;
  }

  return ContReservedStack::get_stack_from_pre_mapped();
}

bool ContPreMappedStack::initialize_virtual_space(intptr_t real_stack_size) {
  if (!_virtual_space.initialize(_reserved_space, real_stack_size)) {
    _reserved_space.release();
    return false;
  }

  return true;
}

ContBucket::ContBucket() : _lock(Mutex::nosafepoint, "ContBucket") {
  _head = NULL;
  _count = 0;

  // This initial value ==> never claimed.
  _oops_do_parity = 0;
}

void ContBucket::insert(Coroutine* cont) {
  cont->insert_into_list(_head);
  _count++;
}

void ContBucket::remove(Coroutine* cont) {
  cont->remove_from_list(_head);
  _count--;
  assert(_count >= 0, "illegal count");
}

// GC Support
bool ContBucket::claim_oops_do_par_case(int new_parity) {
  jint old_parity = _oops_do_parity;
  if (old_parity != new_parity) {
    jint res = Atomic::cmpxchg(&_oops_do_parity, old_parity, new_parity);
    if (res == old_parity) {
      return true;
    } else {
      guarantee(res == new_parity, "Or else what?");
    }
  }
  return false;
}

// Used by ParallelScavenge
/*void ContBucket::create_cont_bucket_roots_tasks(GCTaskQueue* q) {
  for (size_t i = 0; i < CONT_CONTAINER_SIZE; i++) {
    q->enqueue(new ContBucketRootsTask((int)i));
  }
}

// Used by Parallel Old
void ContBucket::create_cont_bucket_roots_marking_tasks(GCTaskQueue* q) {
  for (size_t i = 0; i < CONT_CONTAINER_SIZE; i++) {
    q->enqueue(new ContBucketRootsMarkingTask((int)i));
  }
}*/

#define ALL_BUCKET_CONTS(OPR)      \
  {                                \
    Coroutine* head = _head;       \
    if (head != NULL) {            \
      Coroutine* current = head;   \
      do {                         \
        current->OPR;              \
        current = current->next(); \
      } while (current != head);   \
    }                              \
  }

void ContBucket::frames_do(void f(frame*, const RegisterMap*)) {
  ALL_BUCKET_CONTS(frames_do(f));
}

void ContBucket::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  ALL_BUCKET_CONTS(oops_do(f, cf));
}

void ContBucket::nmethods_do(CodeBlobClosure* cf) {
  ALL_BUCKET_CONTS(nmethods_do(cf));
}

void ContBucket::metadata_do(MetadataClosure* f) {
  ALL_BUCKET_CONTS(metadata_do(f));
}

/*void ContBucket::print_stack_on(outputStream* st) {
  ALL_BUCKET_CONTS(print_stack_on(st));
}*/

void ContContainer::init() {
  assert(is_power_of_2(CONT_CONTAINER_SIZE), "Must be a power of two");
  _buckets = new ContBucket[CONT_CONTAINER_SIZE];
}

ContBucket* ContContainer::bucket(size_t i) {
  return &(_buckets[i]);
}

size_t ContContainer::hash_code(Coroutine* cont) {
  return ((uintptr_t)cont >> CONT_MASK_SHIFT) & CONT_MASK;
}

void ContContainer::insert(Coroutine* cont) {
  size_t index = hash_code(cont);
  guarantee(index < CONT_CONTAINER_SIZE, "Must in the range from 0 to CONT_CONTAINER_SIZE - 1");
  {
    ContBucket* bucket = ContContainer::bucket(index);
    MutexLocker ml(bucket->lock(), Mutex::_no_safepoint_check_flag);
    bucket->insert(cont);
    if (log_is_enabled(Trace, coroutine)) {
      ResourceMark rm;
      Log(coroutine) log;
      log.trace("[insert] cont: %p, index: %d, count : %d", cont, (int)index, bucket->count());
    }
  }
}

void ContContainer::remove(Coroutine* cont) {
  size_t index = hash_code(cont);
  guarantee(index < CONT_CONTAINER_SIZE, "Must in the range from 0 to CONT_CONTAINER_SIZE - 1");
  {
    ContBucket* bucket = ContContainer::bucket(index);
    MutexLocker ml(bucket->lock(), Mutex::_no_safepoint_check_flag);
    bucket->remove(cont);
    if (log_is_enabled(Trace, coroutine)) {
      ResourceMark rm;
      Log(coroutine) log;
      log.trace("[remove] cont: %p, index: %d, count : %d", cont, (int)index, bucket->count());
    }
  }
}

#define ALL_BUCKETS_DO(OPR)                                     \
  {                                                             \
    for (size_t i = 0; i < CONT_CONTAINER_SIZE; i++) {          \
      ContBucket* bucket = ContContainer::bucket(i);            \
      MutexLocker ml(bucket->lock(), Mutex::_no_safepoint_check_flag); \
      bucket->OPR;                                              \
    }                                                           \
  }

void ContContainer::frames_do(void f(frame*, const RegisterMap*)) {
  ALL_BUCKETS_DO(frames_do(f));
}

void ContContainer::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  ALL_BUCKETS_DO(oops_do(f, cf));
}

void ContContainer::nmethods_do(CodeBlobClosure* cf) {
  ALL_BUCKETS_DO(nmethods_do(cf));
}

void ContContainer::metadata_do(MetadataClosure* f) {
  ALL_BUCKETS_DO(metadata_do(f));
}

/*void ContContainer::print_stack_on(outputStream* st) {
  ALL_BUCKETS_DO(print_stack_on(st));
}*/

void Coroutine::add_stack_frame(void* frames, int* depth, javaVFrame* jvf) {
  StackFrameInfo* frame = new StackFrameInfo(jvf, false);
  ((GrowableArray<StackFrameInfo*>*)frames)->append(frame);
  (*depth)++;
}

#if defined(LINUX) || defined(_ALLBSD_SOURCE) || defined(_WINDOWS)
void coroutine_start(void* dummy, const void* coroutineObjAddr) {
#if !defined(AMD64) && !defined(AARCH64)
  fatal("Corotuine not supported on current platform");
#endif
  JavaThread* thread = JavaThread::current();
  thread->set_thread_state(_thread_in_vm);
  // passing raw object address form stub to C method
  // normally oop is OopDesc*, can use raw object directly
  // in fastdebug mode, oop is "class oop", raw object addrss is stored in class oop structure
#ifdef CHECK_UNHANDLED_OOPS
  oop coroutineObj = oop(coroutineObjAddr);
#else
  oop coroutineObj = (oop)coroutineObjAddr;
#endif
  JavaCalls::call_continuation_start(coroutineObj, thread);
  ShouldNotReachHere();
}
#endif

void Coroutine::TerminateCoroutine(Coroutine* coro, JavaThread* thread) {
  if (!jdk_internal_vm_Continuation::done(coro->_continuation)) {
    return;
  }

  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(coroutine) log;
    log.trace("[Co]: TerminateCoroutine %p in thread %s(%p)", coro, thread->name(), thread);
  }
  guarantee(thread == JavaThread::current(), "thread not match");

  {
    ContContainer::remove(coro);
    if (thread->coroutine_cache_size() < MaxFreeCoroutinesCacheSize) {
      coro->insert_into_list(thread->coroutine_cache());
      thread->coroutine_cache_size() ++;
    } else {
      delete coro;
    }
  }
}

void Coroutine::Initialize() {
  guarantee(_continuation_start == NULL, "continuation start already initialized");
  /*Klass* resolved_klass = vmClasses::Continuation_klass();
  Symbol* method_name = vmSymbols::enter_name();
  Symbol* method_signature = vmSymbols::continuationEnter_signature();
  Klass*  current_klass = resolved_klass;
  LinkInfo link_info(resolved_klass, method_name, method_signature, current_klass);
  methodHandle method(JavaThread::current(), LinkResolver::linktime_resolve_virtual_method_or_null(link_info));
  _continuation_start = method();*/
  Klass* resolved_klass = vmClasses::Continuation_klass();
  Symbol* method_name = vmSymbols::enter_name();
  Symbol* method_signature = vmSymbols::continuationEnter_signature();
  Klass*  current_klass = resolved_klass;
  LinkInfo link_info(resolved_klass, method_name, method_signature, current_klass);
  //  methodHandle method(JavaThread::current(), LinkResolver::linktime_resolve_static_method(link_info));
  _continuation_start = LinkResolver::resolve_static_call_or_null(link_info);
  //_continuation_start = resolve_method(link_info, Bytecodes::_invokestatic, JavaThread::current());
  guarantee(_continuation_start != NULL, "continuation start not resolveds");
}

void Coroutine::start_concurrent(ConcCoroStage stage) {
  guarantee(SafepointSynchronize::is_at_safepoint(), "should be at safepoint");
  assert(_conc_claim_parity >= 1 && _conc_claim_parity <= 2, "unexpected _conc_claim_parity");
  _conc_stage = stage;
  _conc_claim_parity++;
  if (_conc_claim_parity == 3) {
    _conc_claim_parity = 1;
  }
  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(gc) log;
    log.trace("[Coroutine::start_concurrent] stage %d, parity %d", stage, _conc_claim_parity);
  }
}

void Coroutine::end_concurrent() {
  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(gc) log;
    log.trace("[Coroutine::end_concurrent]");
  }
}

class ConcCoroutineCodeBlobClosure : public CodeBlobToOopClosure {
private:
  BarrierSetNMethod* _bs;

public:
  ConcCoroutineCodeBlobClosure(OopClosure* cl) :
    CodeBlobToOopClosure(cl, true /* fix_relocations */),
    _bs(BarrierSet::barrier_set()->barrier_set_nmethod()) {}

  virtual void do_code_blob(CodeBlob* cb) {
    nmethod* const nm = cb->as_nmethod_or_null();
    if (nm != NULL) {
      _bs->nmethod_entry_barrier(nm);
    }
  }
};

Coroutine* Coroutine::createContinuation() {
  Coroutine* coro = NULL;
  JavaThread* thread = JavaThread::current();
  if (thread->coroutine_cache_size() > 0) {
    coro = thread->coroutine_cache();
    coro->remove_from_list(thread->coroutine_cache());
    thread->coroutine_cache_size()--;
    Coroutine::reset_coroutine(coro);
    Coroutine::init_coroutine(coro, thread);
  }
  if (coro == NULL) {
    coro = Coroutine::create_coroutine(thread, 0);
    if (coro == NULL) {
      HandleMark mark(thread);
      //THROW_0(vmSymbols::java_lang_OutOfMemoryError());
    }
  }
  ContContainer::insert(coro);
  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(coroutine) log;
    log.trace("CONT_createContinuation: create continuation %p", coro);
  }
  return coro;
}

void Coroutine::concurrent_task_run(OopClosure* f, int* claim) {
  ConcCoroutineCodeBlobClosure cf(f);
  while (true) {
    int res = Atomic::add(claim, 1);
    int cur = res - 1;
    if (cur >= (int)CONT_CONTAINER_SIZE) {
      break;
    }
    ContBucket* bucket = ContContainer::bucket(cur);
    MutexLocker ml(bucket->lock(), Mutex::_no_safepoint_check_flag);
    Coroutine* head = bucket->head();
    if (head == NULL) {
      continue;
    }
    Coroutine* current = head;
    do {
      if (current->conc_claim(true)) {
        ResourceMark rm;
        current->oops_do(f, ClassUnloading ? &cf : NULL);
        bool res = current->conc_claim(false);
        guarantee(res == true, "must success release");
      }
      current = current->next();
    } while (current != head);
  }
}

Coroutine::Coroutine() {
  _has_javacall = false;
  _continuation = NULL;
#if defined(_WINDOWS)
  _guaranteed_stack_bytes = 0;
#endif
#ifdef CHECK_UNHANDLED_OOPS
  _t = NULL;
#endif
}

Coroutine::~Coroutine() {
  if (_verify_state != NULL) {
    delete _verify_state; 
  } else {
    assert(VerifyCoroutineStateOnYield == false || _is_thread_coroutine,
      "VerifyCoroutineStateOnYield is on and _verify_state is NULL");
  }
  free_stack();
}

Coroutine* Coroutine::create_thread_coroutine(JavaThread* thread) {
  Coroutine* coro = new Coroutine();
  coro->_state = _current;
  coro->_verify_state = NULL;
  coro->_is_thread_coroutine = true;
  coro->_thread = thread;
  coro->_coro_claim = _conc_claim_parity;
  coro->init_thread_stack(thread);
  coro->_has_javacall = true;
  coro->_t = thread;
#ifdef ASSERT
  coro->_java_call_counter = 0;
#endif
#if defined(_WINDOWS)
  coro->_last_SEH = NULL;
#endif
  ContContainer::insert(coro);
  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(coroutine) log;
    log.trace("[Co]: CreateThreadCoroutine %p in thread %s(%p)", coro, thread->name(), thread);
  }
  return coro;
}

void Coroutine::reset_coroutine(Coroutine* coro) {
  coro->_has_javacall = false;
}

void Coroutine::init_coroutine(Coroutine* coro, JavaThread* thread) {
  intptr_t** d = (intptr_t**)coro->_stack_base;
  // 7 is async profiler's lookup slots count, avoid cross stack
  // boundary when using async profiler
  // must be odd number to keep frame pointer align to 16 bytes.
  for (int32_t i = 0; i < 7; i++) {
    *(--d) = NULL;
  }
#if defined TARGET_ARCH_aarch64
  // aarch64 pops 2 slots when doing coroutine switch
  // must keep frame pointer align to 16 bytes
  *(--d) = NULL;
#endif
  *(--d) = (intptr_t*)coroutine_start;
  *(--d) = NULL;

  coro->set_last_sp((address) d);

  coro->_state = _onstack;
  coro->_is_thread_coroutine = false;
  coro->_thread = thread;
  coro->_coro_claim = _conc_claim_parity;

#ifdef ASSERT
  coro->_java_call_counter = 0;
#endif
#if defined(_WINDOWS)
  coro->_last_SEH = NULL;
#endif
  if (log_is_enabled(Trace, coroutine)) {
    ResourceMark rm;
    Log(coroutine) log;
    log.trace("[Co]: CreateCoroutine %p in thread %s(%p)", coro, coro->thread()->name(), coro->thread());
  }
}

Coroutine* Coroutine::create_coroutine(JavaThread* thread, long stack_size) {
  assert(stack_size <= 0, "Can not specify stack size by users");

  Coroutine* coro = new Coroutine();
  if (VerifyCoroutineStateOnYield) {
    coro->_verify_state = new CoroutineVerify();
  } else {
    coro->_verify_state = NULL;
  }
  if (!coro->init_stack(thread)) {
    return NULL;
  }

  Coroutine::init_coroutine(coro, thread);
  return coro;
}

void Coroutine::frames_do(FrameClosure* fc) {
  if (_state == Coroutine::_onstack) {
    on_stack_frames_do(fc, _is_thread_coroutine);
  }
}

bool Coroutine::conc_claim(bool gc_thread) {
  int old_parity = _coro_claim;
  if (old_parity != _conc_claim_parity) {
    int new_parity = gc_thread ? 4 : _conc_claim_parity;
    int res = Atomic::cmpxchg(&_coro_claim, old_parity, new_parity);
    if (res == old_parity) {
      return true;
    } else {
      if (gc_thread) {
        guarantee(res == _conc_claim_parity, "Or else what?");
      } else {
        guarantee(res == _conc_claim_parity || res == 4, "Or else what?");
      }
    }
  }
  return false;
}

class oops_do_Closure: public FrameClosure {
private:
  OopClosure* _f;
  CodeBlobClosure* _cf;

public:
  oops_do_Closure(OopClosure* f, CodeBlobClosure* cf): _f(f), _cf(cf) { }
  void frames_do(frame* fr, RegisterMap* map) { fr->oops_do(_f, _cf, map); }
};

void Coroutine::oops_do(OopClosure* f, CodeBlobClosure* cf) {
  if (is_thread_coroutine() == false) {
    f->do_oop(&_continuation);
  }
  if(state() != Coroutine::_onstack) {
    //tty->print_cr("oops_do on %p is skipped", this);
    return;
  }
  //tty->print_cr("oops_do on %p is performed", this);
  oops_do_Closure fc(f, cf);
  frames_do(&fc);
}

class nmethods_do_Closure: public FrameClosure {
private:
  CodeBlobClosure* _cf;
public:
  nmethods_do_Closure(CodeBlobClosure* cf): _cf(cf) { }
  void frames_do(frame* fr, RegisterMap* map) { fr->nmethods_do(_cf); }
};

void Coroutine::nmethods_do(CodeBlobClosure* cf) {
  nmethods_do_Closure fc(cf);
  frames_do(&fc);
}

class metadata_do_Closure: public FrameClosure {
private:
  MetadataClosure* _f;
public:
  metadata_do_Closure(MetadataClosure *f): _f(f) { }
  void frames_do(frame* fr, RegisterMap* map) { 
    fr->metadata_do(_f); 
  }
};

void Coroutine::metadata_do(MetadataClosure* f) {
  if(state() != Coroutine::_onstack) {
    return;
  }
  metadata_do_Closure fc(f);
  frames_do(&fc);
}

class frames_do_Closure: public FrameClosure {
private:
  void (*_f)(frame*, const RegisterMap*);
public:
  frames_do_Closure(void f(frame*, const RegisterMap*)): _f(f) { }
  void frames_do(frame* fr, RegisterMap* map) { _f(fr, map); }
};

void Coroutine::frames_do(void f(frame*, const RegisterMap* map)) {
  frames_do_Closure fc(f);
  frames_do(&fc);
}

bool Coroutine::is_disposable() {
  return false;
}


ObjectMonitor* Coroutine::current_pending_monitor() {
  // if coroutine is detached(_onstack), it doesn't pend on monitor
  // if coroutine is attached(_current), its pending monitor is thread's pending monitor
  if (_state == _onstack) {
    return NULL;
  } else {
    assert(_state == _current, "unexpected");
    return _thread->current_pending_monitor();
  }
}

bool Coroutine::is_attaching_via_jni() const {
    if (_is_thread_coroutine) {
      return _t->is_attaching_via_jni();
    }

    return false;
}

void Coroutine::init_thread_stack(JavaThread* thread) {
  _stack_base = thread->stack_base();
  _stack_size = thread->stack_size();
  _stack_overflow_limit = thread->stack_overflow_state()->stack_overflow_limit();
  _stack_end = thread->stack_overflow_state()->stack_end();
  _shadow_zone_safe_limit = thread->stack_overflow_state()->shadow_zone_safe_limit();
  _shadow_zone_growth_watermark = thread->stack_overflow_state()->shadow_zone_growth_watermark(); 
  _last_sp = NULL;
}

bool Coroutine::init_stack(JavaThread* thread) {
  _stack_base = ContReservedStack::get_stack();
  if (_stack_base == NULL) {
    return false;
  }
  _stack_size = ContReservedStack::stack_size;
  _stack_overflow_limit = _stack_base - _stack_size + MAX2(StackOverflow::stack_guard_zone_size(), StackOverflow::stack_shadow_zone_size());
  _stack_end = _stack_base - _stack_size;
  _shadow_zone_safe_limit = _stack_end + StackOverflow::stack_guard_zone_size() + StackOverflow::stack_shadow_zone_size();
  _shadow_zone_growth_watermark = _stack_base;
  _last_sp = NULL;
  return true;
}

void Coroutine::free_stack() {
  if(!is_thread_coroutine()) {
    ContReservedStack::insert_stack(_stack_base);
  }
}

static const char* VirtualThreadStateNames[] = {
  "NEW",
  "STARTED",
  "RUNNABLE",
  "RUNNING",
  "PARKING",
  "PARKED",
  "PINNED"
};

#if defined TARGET_ARCH_x86
#define FRAME_POINTER rbp
#elif defined TARGET_ARCH_aarch64
#define FRAME_POINTER rfp
#else
#error "Arch is not supported."
#endif

void Coroutine::on_stack_frames_do(FrameClosure* fc, bool isThreadCoroutine) {
  assert(_last_sp != NULL, "CoroutineStack with NULL last_sp");

  // optimization to skip coroutine not started yet, check if return address is coroutine_start
  // fp is only valid for call from interperter, from compiled code fp register is not gurantee valid
  // JIT method utilize sp and oop map for oops iteration.
  address pc = ((address*)_last_sp)[1];
  intptr_t* fp = ((intptr_t**)_last_sp)[0];
  if (pc != (address)coroutine_start) {
    intptr_t* sp = ((intptr_t*)_last_sp) + 2;
    frame fr(sp, fp, pc);
    StackFrameStream fst(_thread, fr);
    fst.register_map()->set_location(FRAME_POINTER->as_VMReg(), (address)_last_sp);
    fst.register_map()->set_include_argument_oops(false);
    for(; !fst.is_done(); fst.next()) {
      fc->frames_do(fst.current(), fst.register_map());
    }
  } else {
    guarantee(!isThreadCoroutine, "thread conrotuine with coroutine_start as return address");
    guarantee(fp == NULL, "conrotuine fp not in init status");
  }
}
#endif// INCLUDE_KONA_FIBER

/*
 * Copyright 1999-2010 Sun Microsystems, Inc.  All Rights Reserved.
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

#if INCLUDE_KONA_FIBER
#ifndef SHARE_RUNTIME_COROUTINE_HPP
#define SHARE_RUNTIME_COROUTINE_HPP

#include "runtime/jniHandles.inline.hpp"
#include "runtime/handles.hpp"
#include "memory/allocation.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/javaFrameAnchor.hpp"
#include "runtime/monitorChunk.hpp"

#define CORO_ONLY(x) x
#define CORO_NOT_ONLY(x)

class Coroutine; // ? why

const size_t CONT_BITMAP_LEN             = 10;
const size_t CONT_CONTAINER_SIZE         = 1 << CONT_BITMAP_LEN;
const size_t CONT_MASK_SHIFT             = 5;
const size_t CONT_MASK                   = CONT_CONTAINER_SIZE - 1;
const int CONT_PREMAPPED_STACK_NUM       = 100;
const int CONT_RESERVED_PHYSICAL_MEM_MAX = 100;

// same with PIN value in Continuation.java
const int CONT_PIN_JNI                   = 2;
const int CONT_PIN_MONITOR               = 3;

/* Mapping numbers of stacks and set its permission as PROT_READ | PROT_WRITE */
class ContPreMappedStack : public CHeapObj<mtCoroutine> {
private:
  ReservedSpace _reserved_space;
  VirtualSpace _virtual_space;
  ContPreMappedStack* _next;

public:
  int allocated_num;
  ContPreMappedStack(intptr_t size, ContPreMappedStack* next) : _reserved_space(size) {
    _next = next;
    allocated_num = 0;
  };
  ~ContPreMappedStack() {
    _reserved_space.release();
  };
  bool initialize_virtual_space(intptr_t real_stack_size);
  // stack is from high address to low address on X86
  address get_base_address() {
    return (address)_virtual_space.high();
  };
};

class ContReservedStack : AllStatic {
private:
  static Mutex* _lock;
  /*
   * free_array contains stacks which are initialized, we can reuse it directly.
   * free_array is an array, the most recently used stack is at the bottom of
   * this array.
   */
  static GrowableArray<address>* free_array;
  /* 
   * A list of pre mapped stack, each node contains stacks which number is CONT_PREMAPPED_STACK_NUM,
   * current_pre_mapped_stack pointed to the node which is used currently, 
   * we should alloc a new pre mapped node when current_pre_mapped_stack is full.
   */
  static ContPreMappedStack* current_pre_mapped_stack;
  static int free_array_uncommit_index;

  static address get_stack_from_free_array();
  static address get_stack_from_pre_mapped();
  static bool add_pre_mapped_stack();

  static inline address acquire_stack();
  static inline bool pre_mapped_stack_is_full();

public:
  static uintx stack_size;

  static void init();
  /*
   * 1. Try to get stack from free array.
   * 2. If free array is not empty, get a stack from free array and return.
   * 3. If free array is empty, try to get stack from pre-mapped memory.
   * 4. If pre-mapped memory has no space to assign, add a new pre-mapped block.
   * 5. Get pre-mapped memory.
   * 6. Set the permisson of yellow page and red page as PROT_NONE.
   */ 
  static address get_stack();
  /* Release stack and insert the stack into free array */
  static void insert_stack(address node);
};

class ContBucket : public CHeapObj<mtCoroutine> {
private:
  Mutex      _lock;
  Coroutine* _head;
  int        _count;

  // The parity of the last strong_roots iteration in which this ContBucket was
  // claimed as a task.
  jint _oops_do_parity;
  bool claim_oops_do_par_case(int collection_parity);

public:
  Mutex* lock() { return &_lock; }
  Coroutine* head() const { return _head; }
  int count() const { return _count; } // Is this count useful?
  void insert(Coroutine* cont);
  void remove(Coroutine* cont);
  ContBucket();
  bool claim_oops_do(bool is_par, int collection_parity) {
    if (!is_par) {
      _oops_do_parity = collection_parity;
      return true;
    } else {
      return claim_oops_do_par_case(collection_parity);
    }
  }
  uintx parity() const { return (uintx)_oops_do_parity; }
  //static void create_cont_bucket_roots_tasks(GCTaskQueue* q);
  //static void create_cont_bucket_roots_marking_tasks(GCTaskQueue* q);

  void frames_do(void f(frame*, const RegisterMap*));
  void oops_do(OopClosure* f, CodeBlobClosure* cf);
  void nmethods_do(CodeBlobClosure* cf);
  void metadata_do(MetadataClosure* f);
  void print_stack_on(outputStream* st);
};

class ContContainer : AllStatic {
private:
  static ContBucket* _buckets;
public:
  static size_t hash_code(Coroutine* cont);
  static ContBucket* bucket(size_t index);
  static ContBucket* buckets() { return _buckets; };
  static void insert(Coroutine* cont);
  static void remove(Coroutine* cont);
  static void init();

  static void frames_do(void f(frame*, const RegisterMap*));
  static void oops_do(OopClosure* f, CodeBlobClosure* cf);
  static void nmethods_do(CodeBlobClosure* cf);
  static void metadata_do(MetadataClosure* f);
  static void print_stack_on(outputStream* st);
};

template<class T>
class DoublyLinkedList {
private:
  T*  _last;
  T*  _next;

public:
  DoublyLinkedList() {
    _last = NULL;
    _next = NULL;
  }

  typedef T* pointer;

  void remove_from_list(pointer& list);
  void insert_into_list(pointer& list);

  T* last() const   { return _last; }
  T* next() const   { return _next; }
};

class FrameClosure: public StackObj {
public:
  virtual void frames_do(frame* fr, RegisterMap* map) = 0;
};

class CoroutineVerify: public CHeapObj<mtCoroutine> {
public:
  // for verify check
  JNIHandleBlock* saved_active_handles;
  size_t saved_active_handle_count;
  char* saved_handle_area_hwm;
  char* saved_resource_area_hwm;
};

class Coroutine: public CHeapObj<mtCoroutine>, public DoublyLinkedList<Coroutine> {
public:
  enum CoroutineState {
    _onstack    = 0x00000001,
    _current    = 0x00000002,
    _dead       = 0x00000003,      // TODO is this really needed?
    _dummy      = 0xffffffff
  };

  enum ConcCoroStage {
    _Uninitialized    = 0x00000000,
    _ZConcurrent      = 0x00000001
  };
  // similar with _thread_claim_parity, flip-flop between 1,2. As mutator thread might racing
  // with concurrent GC thread, define a third state 4 (GC claimed and processing).
  // 1. In GC Pause STW, invoke start_concurrent and flip _conc_claim_parity
  // 2. Concurrent GC thread CAS _coro_claim from old parity to 4 before processing.
  // 3. Concurrent GC thread set _coro_claim from 4 to new parity after processing.
  // 4. Mutator thread claim CAS _coro_claim from old parity to _conc_claim_parity
  // 5. Mutator thread wait if _coro_claim is 4 until it changes to _conc_claim_parity.
  static int              _conc_claim_parity;
private:
  static Method*  _try_compensate_method;
  static Method*  _update_active_count_method;
  CoroutineState  _state;
  bool            _is_thread_coroutine;
  //for javacall stack reclaim
  bool            _has_javacall;

  address         _stack_base;
  intptr_t        _stack_size;
#if defined(_WINDOWS)
  intptr_t        _guaranteed_stack_bytes;
#endif
  address         _last_sp;
  address         _stack_overflow_limit;
  address         _stack_end;
  address         _shadow_zone_safe_limit;
  address         _shadow_zone_growth_watermark;
#ifndef CHECK_UNHANDLED_OOPS
  union {
#endif
    oop             _continuation;
    JavaThread*     _t;
#ifndef CHECK_UNHANDLED_OOPS
  };
#endif

  JavaThread*     _thread;
  CoroutineVerify* _verify_state;
  volatile int    _coro_claim;
  int             _depth_first_number;
#ifdef ASSERT
  int             _java_call_counter;
#endif

  // objects of this type can only be created via static functions
  Coroutine();

  void frames_do(FrameClosure* fc);

  static JavaThread* _main_thread;
  static Method* _continuation_start;

  // _conc_stage help muator thread decide which closure use for coroutine processing.
  static ConcCoroStage    _conc_stage;

  bool init_stack(JavaThread* thread);

  void add_stack_frame(void* frames, int* depth, javaVFrame* jvf);
  void print_stack_on(outputStream* st, void* frames, int* depth);
  const char* get_vt_name_string(char* buf = NULL, int buflen = 0) const;
  static JavaThreadState update_thread_state(Thread *Self, JavaThreadState new_jts);
  static void init_forkjoinpool_method(Method** init_method, Symbol* method_name, Symbol* signature);
  static void call_forkjoinpool_method(Thread* Self, Method* target_method, JavaCallArguments* args, JavaValue* result);

public:
  virtual ~Coroutine();
  static void Initialize();
  static void start_concurrent(ConcCoroStage stage);
  static void end_concurrent();
  static void concurrent_task_run(OopClosure* f, int* claim);
  static Coroutine* createContinuation();
  // 1. try claim or wait finish
  // 2. claim success invoke _conc_cl on coroutine
#if INCLUDE_ZGC
  static void Concurrent_Coroutine_slowpath(Coroutine* coro);
#endif
#if defined(_WINDOWS)
  intptr_t get_guaranteed_stack_bytes() { return _guaranteed_stack_bytes; }
#endif

  static void yield_verify(Coroutine* from, Coroutine* to, bool terminate);
  static JavaThread* main_thread() { return _main_thread; }
  static void set_main_thread(JavaThread* t) { _main_thread = t; }
  static Method* cont_start_method() { return _continuation_start; }
  static int try_compensate(Thread* Self);
  static void update_active_count(Thread* Self);

  void print_stack_on(outputStream* st);
  void print_stack_on(void* frames, int* depth);
  void print_VT_info(outputStream* st);

  bool has_javacall() const { return _has_javacall; }
  void set_has_javacall(bool hjc) { _has_javacall = hjc; }

  static Coroutine* create_thread_coroutine(JavaThread* thread);
  static Coroutine* create_coroutine(JavaThread* thread, long stack_size);
  static void reset_coroutine(Coroutine* coro);
  static void init_coroutine(Coroutine* coro, JavaThread* thread);

  CoroutineState state() const      { return _state; }
  void set_state(CoroutineState x)  { _state = x; }

  bool is_thread_coroutine() const  { return _is_thread_coroutine; }

  JavaThread* thread() const        { return _thread; }
  void set_thread(JavaThread* x)    { _thread = x; }

  void set_continuation(oop o)      {
    assert(!is_thread_coroutine(), "could not be thread coroutine");
    _continuation = o;
  }

  // For deadlock detection
  int depth_first_number() { return _depth_first_number; }
  void set_depth_first_number(int dfn) { _depth_first_number = dfn; }
  ObjectMonitor* current_pending_monitor();
  oop current_park_blocker();
  oop threadObj() const;
  const char* get_thread_name() const;
  bool current_pending_monitor_is_from_java();
  static Coroutine* owning_coro_from_monitor_owner(address owner);

#ifdef ASSERT
  int java_call_counter() const           { return _java_call_counter; }
  void set_java_call_counter(int x)       { _java_call_counter = x; }
#endif

  bool is_disposable();

  // GC support
  bool conc_claim(bool is_gc_thread);
  void oops_do(OopClosure* f, CodeBlobClosure* cf);
  void nmethods_do(CodeBlobClosure* cf);
  void metadata_do(MetadataClosure* f);
  void frames_do(void f(frame*, const RegisterMap* map));
  static void TerminateCoroutine(Coroutine* coro, JavaThread* thread);
  static ByteSize state_offset()              { return byte_offset_of(Coroutine, _state); }

  static ByteSize thread_offset()             { return byte_offset_of(Coroutine, _thread); }
  static ByteSize has_javacall_offset()       { return byte_offset_of(Coroutine, _has_javacall); }
  static ByteSize continuation_oop_offset()   { return byte_offset_of(Coroutine, _continuation); }

  void init_thread_stack(JavaThread* thread);
  void free_stack();
  void on_stack_frames_do(FrameClosure* fc, bool isThreadCoroutine);
  void set_last_sp(address x)               { _last_sp = x; }

  static ByteSize stack_base_offset()         { return byte_offset_of(Coroutine, _stack_base); }
  static ByteSize stack_size_offset()         { return byte_offset_of(Coroutine, _stack_size); }
#if defined(_WINDOWS)
  static ByteSize guaranteed_stack_bytes_offset() { return byte_offset_of(Coroutine, _guaranteed_stack_bytes); }
#endif

  static ByteSize last_sp_offset()            { return byte_offset_of(Coroutine, _last_sp); }
  static ByteSize stack_overflow_limit_offset() { return byte_offset_of(Coroutine, _stack_overflow_limit); }
  static ByteSize stack_end_offset() { return byte_offset_of(Coroutine, _stack_end); }
  static ByteSize stack_shadow_zone_safe_limit_offset() { return byte_offset_of(Coroutine, _shadow_zone_safe_limit); }
  static ByteSize stack_shadow_zone_growth_watermark_offset() { return byte_offset_of(Coroutine, _shadow_zone_growth_watermark); }

  bool is_lock_owned(address adr) const {
    return _stack_base >= adr && adr > (_stack_base - _stack_size);
  }

  bool is_attaching_via_jni() const;

  static ByteSize coro_claim_offset()         { return byte_offset_of(Coroutine, _coro_claim); }
#ifdef ASSERT
  static ByteSize java_call_counter_offset()  { return byte_offset_of(Coroutine, _java_call_counter); }
#endif

#if defined(_WINDOWS)
private:
  address _last_SEH;
public:
  static ByteSize last_SEH_offset()           { return byte_offset_of(Coroutine, _last_SEH); }
#endif
};

template<class T> void DoublyLinkedList<T>::remove_from_list(pointer& list) {
  if (list == this) {
    if (list->_next == list)
      list = NULL;
    else
      list = list->_next;
  }
  _last->_next = _next;
  _next->_last = _last;
  _last = NULL;
  _next = NULL;
}

template<class T> void DoublyLinkedList<T>::insert_into_list(pointer& list) {
  if (list == NULL) {
    _next = (T*)this;
    _last = (T*)this;
    list = (T*)this;
  } else {
    _next = list->_next;
    list->_next = (T*)this;
    _last = list;
    _next->_last = (T*)this;
  }
}

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls, JavaThread* thread);
#endif // SHARE_RUNTIME_COROUTINE_HPP
#else
#define CORO_ONLY(x)
#define CORO_NOT_ONLY(x) x
#endif // INCLUDE_KONA_FIBER

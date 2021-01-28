/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_RUNTIME_CONTINUATION_HPP
#define SHARE_VM_RUNTIME_CONTINUATION_HPP

#include "oops/access.hpp"
#include "oops/oopsHierarchy.hpp"
#include "memory/iterator.hpp"
#include "runtime/frame.hpp"
#include "runtime/globals.hpp"
#include "jni.h"

// #define CONT_DOUBLE_NOP 1

#define CONT_FULL_STACK (!UseContinuationLazyCopy)

// The order of this struct matters as it's directly manipulated by assembly code (push/pop)

class ContinuationEntry;

class Continuations : public AllStatic {
private:
  static volatile intptr_t _exploded_miss;
  static volatile intptr_t _exploded_hit;
  static volatile intptr_t _nmethod_hit;
  static volatile intptr_t _nmethod_miss;

public:
  static void exploded_miss();
  static void exploded_hit();
  static void nmethod_miss();
  static void nmethod_hit();

  static void print_statistics();
  static void init();

  static void cleanup_keepalives();

  static address default_freeze_oops_stub();
  static address freeze_oops_slow();
  static address default_thaw_oops_stub();
  static address thaw_oops_slow();
};

void continuations_init();

class javaVFrame;
class JavaThread;
class OopStorage;

class Continuation : AllStatic {
private:
  static OopStorage* _weak_handles;
public:
  static void init();

  static OopStorage* weak_storage() { return _weak_handles; }

  static int freeze(JavaThread* thread, intptr_t* sp);
  static int prepare_thaw(JavaThread* thread, bool return_barrier);
  static intptr_t* thaw_leaf(JavaThread* thread, int kind);
  static intptr_t* thaw(JavaThread* thread, int kind);
  static int try_force_yield(JavaThread* thread, oop cont);

  static void notify_deopt(JavaThread* thread, intptr_t* sp);

  static oop  get_continuation_for_frame(JavaThread* thread, const frame& f);
  static ContinuationEntry* last_continuation(const JavaThread* thread, oop cont_scope);
  static bool is_mounted(JavaThread* thread, oop cont_scope);
  static bool is_continuation_enterSpecial(const frame& f);
  static bool is_continuation_entry_frame(const frame& f, const RegisterMap* map);
  static bool is_cont_barrier_frame(const frame& f);
  static bool is_return_barrier_entry(const address pc);
  static bool is_frame_in_continuation(ContinuationEntry* cont, const frame& f);
  static bool is_frame_in_continuation(JavaThread* thread, const frame& f);
  static bool fix_continuation_bottom_sender(JavaThread* thread, const frame& callee, address* sender_pc, intptr_t** sender_sp);
  static address get_top_return_pc_post_barrier(JavaThread* thread, address pc);

  static frame top_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_interpreter_frame(const frame& callee, RegisterMap* map);
  static frame sender_for_compiled_frame(const frame& callee, RegisterMap* map);
  static int frame_size(const frame& f, const RegisterMap* map);

  static bool has_last_Java_frame(Handle continuation);
  static frame last_frame(Handle continuation, RegisterMap *map);
  static javaVFrame* last_java_vframe(Handle continuation, RegisterMap *map);

  // access frame data
  static bool is_in_usable_stack(address addr, const RegisterMap* map);
  static int usp_offset_to_index(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes);
  static address usp_offset_to_location(const frame& fr, const RegisterMap* map, const int usp_offset_in_bytes, bool is_oop);
  static address reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg, bool is_oop);

  static address interpreter_frame_expression_stack_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);
  static address interpreter_frame_local_at(const frame& fr, const RegisterMap* map, const InterpreterOopMap& oop_mask, int index);
  static Method* interpreter_frame_method(const frame& fr, const RegisterMap* map);
  static address interpreter_frame_bcp(const frame& fr, const RegisterMap* map);

  static oop continuation_parent(oop cont);
  static oop continuation_scope(oop cont);
  static bool is_scope_bottom(oop cont_scope, const frame& fr, const RegisterMap* map);

  static void set_cont_fastpath_thread_state(JavaThread* thread);

  static ContinuationEntry* get_continuation_entry_for_continuation(JavaThread* thread, oop cont);

#ifndef PRODUCT
  static void describe(FrameValues &values);
#endif

  static void nmethod_patched(nmethod* nm);

private:
  // declared here as it's used in friend declarations
  static address oop_address(objArrayOop ref_stack, int ref_sp, int index);
  static address oop_address(objArrayOop ref_stack, int ref_sp, address stack_address);

private:
  friend class InstanceStackChunkKlass;
  static void emit_chunk_iterate_event(oop chunk, int num_frames, int num_oops);

#ifdef ASSERT
public:
  static bool debug_is_stack_chunk(Klass* klass);
  static bool debug_is_stack_chunk(oop obj);
  static void debug_print_stack_chunk(oop obj);
  static bool debug_is_continuation(Klass* klass);
  static bool debug_is_continuation(oop obj);
  static bool debug_verify_continuation(oop cont);
  static void debug_print_continuation(oop cont, outputStream* st = NULL);
  static bool debug_is_continuation_run_frame(const frame& f);
#endif
};

// Metadata stored in the continuation entry frame
class ContinuationEntry {
public:
#ifdef ASSERT
  int cookie;
  static ByteSize cookie_offset() { return byte_offset_of(ContinuationEntry, cookie); }
  void verify_cookie() { assert(this->cookie == 0x1234, ""); }
#endif

public: 
  static int return_pc_offset; // friend gen_continuation_enter
  static void set_enter_nmethod(nmethod* nm); // friend SharedRuntime::generate_native_wrapper

private:
  static nmethod* continuation_enter;
  static address return_pc;

private:
  ContinuationEntry* _parent;
  oopDesc* _cont;
  oopDesc* _chunk;
  int _argsize;
  intptr_t* _parent_cont_fastpath;
  int _parent_held_monitor_count;

public:
  static ByteSize parent_offset()   { return byte_offset_of(ContinuationEntry, _parent); }
  static ByteSize cont_offset()     { return byte_offset_of(ContinuationEntry, _cont); }
  static ByteSize chunk_offset()    { return byte_offset_of(ContinuationEntry, _chunk); }
  static ByteSize argsize_offset()  { return byte_offset_of(ContinuationEntry, _argsize); }

  static void setup_oopmap(OopMap* map) {
    map->set_oop(VMRegImpl::stack2reg(in_bytes(cont_offset())  / VMRegImpl::stack_slot_size));
    map->set_oop(VMRegImpl::stack2reg(in_bytes(chunk_offset()) / VMRegImpl::stack_slot_size));
  }

  static ByteSize parent_cont_fastpath_offset()      { return byte_offset_of(ContinuationEntry, _parent_cont_fastpath); }
  static ByteSize parent_held_monitor_count_offset() { return byte_offset_of(ContinuationEntry, _parent_held_monitor_count); }
  
public:
  static size_t size() { return align_up((int)sizeof(ContinuationEntry), 2*wordSize); }

  ContinuationEntry* parent() { return _parent; }

  static address entry_pc() { return return_pc; }
  intptr_t* entry_sp() { return (intptr_t*)this; }
  intptr_t* entry_fp() { return *(intptr_t**)((address)this + size()); } // TODO PD

  int argsize() { return _argsize; }
  void set_argsize(int value) { _argsize = value; }

  intptr_t* parent_cont_fastpath() { return _parent_cont_fastpath; }
  void set_parent_cont_fastpath(intptr_t* x) { _parent_cont_fastpath = x; }

  static ContinuationEntry* from_frame(const frame& f);
  frame to_frame();
  void update_register_map(RegisterMap* map);

  intptr_t* bottom_sender_sp() {
    intptr_t* sp = entry_sp() - argsize();
#ifdef _LP64
    sp = align_down(sp, 16);
#endif
    return sp;
  }

  oop continuation() {
    oop snapshot = _cont;
    return NativeAccess<>::oop_load(&snapshot);
  }

  oop cont_oop() { return this != NULL ? continuation() : (oop)NULL; }
  oop cont_raw() { return _cont; }
  oop chunk()        { return _chunk; }
  void set_continuation(oop c) { _cont = c;  }
  void set_chunk(oop c)        { _chunk = c; }

#ifdef ASSERT
  static bool assert_entry_frame_laid_out(JavaThread* thread);
#endif
};

void CONT_RegisterNativeMethods(JNIEnv *env, jclass cls);

#endif // SHARE_VM_RUNTIME_CONTINUATION_HPP

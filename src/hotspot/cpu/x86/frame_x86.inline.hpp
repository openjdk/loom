/*
 * Copyright (c) 1997, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_FRAME_X86_INLINE_HPP
#define CPU_X86_FRAME_X86_INLINE_HPP

#include "code/codeCache.hpp"
#include "code/codeCache.inline.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/oopMap.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/oopMapCache.hpp"
#include "runtime/sharedRuntime.hpp"

// Inline functions for Intel frames:

class ContinuationCodeBlobLookup {
public:
  enum { has_oopmap_lookup = true };

  static CodeBlob* find_blob(address pc) {
    CodeBlob* cb = CodeCache::find_blob_fast(pc);
    /*Prefetch::read(cb, PrefetchScanIntervalInBytes);
    Prefetch::read((void*)cb->is_compiled_addr(), PrefetchScanIntervalInBytes);
    Prefetch::read((void*) ((CompiledMethod*) cb)->deopt_handler_begin_addr(), PrefetchScanIntervalInBytes);*/
    return cb;
  }

  static CodeBlob* find_blob_and_oopmap(address pc, int& slot) {
    return CodeCache::find_blob_and_oopmap(pc, slot);
  }
};

// Constructors:

inline frame::frame() {
  _pc = NULL;
  _sp = NULL;
  _unextended_sp = NULL;
  _fp = NULL;
  _cb = NULL;
  _deopt_state = unknown;
  _oop_map = NULL;
}

inline void frame::init(intptr_t* sp, intptr_t* fp, address pc) {
  _sp = sp;
  _unextended_sp = sp;
  _fp = fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = CodeCache::find_blob(pc); // not fast because this constructor can be used on native frames
  
  setup(pc);

  _oop_map = NULL;
}

inline frame::frame(intptr_t* sp, intptr_t* fp, address pc) {
  init(sp, fp, pc);
}

inline frame::frame(intptr_t* sp, intptr_t* unextended_sp, intptr_t* fp, address pc, CodeBlob* cb) {
  _sp = sp;
  _unextended_sp = unextended_sp;
  _fp = fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = cb;
  _oop_map = NULL;
  assert(_cb != NULL, "pc: " INTPTR_FORMAT, p2i(pc));

  setup(pc);
}

inline frame::frame(intptr_t* sp, intptr_t* unextended_sp, intptr_t* fp, address pc, CodeBlob* cb, const ImmutableOopMap* oop_map) {
  _sp = sp;
  _unextended_sp = unextended_sp;
  _fp = fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = cb;
  _oop_map = oop_map;
  assert(_cb != NULL, "pc: " INTPTR_FORMAT, p2i(pc));

  setup(pc);
}

inline frame::frame(intptr_t* sp, intptr_t* unextended_sp, intptr_t* fp, address pc, CodeBlob* cb, const ImmutableOopMap* oop_map, bool dummy) {
  _sp = sp;
  _unextended_sp = unextended_sp;
  _fp = fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = cb;
  _oop_map = oop_map;
  _deopt_state = not_deoptimized;
#ifdef ASSERT
  // The following assertion has been disabled because it would sometime trap for Continuation.run, which is not *in* a continuation
  // and therefore does not clear the _cont_fastpath flag, but this is benign even in fast mode (see Freeze::setup_jump)
  // if (cb != NULL) {
  //   setup(pc);
  //   assert(_pc == pc && _deopt_state == not_deoptimized, "");
  // }
#endif
}

inline frame::frame(intptr_t* sp, intptr_t* unextended_sp, intptr_t* fp, address pc) {
  _sp = sp;
  _unextended_sp = unextended_sp;
  _fp = fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = CodeCache::find_blob_fast(pc);
  _oop_map = NULL;
  assert(_cb != NULL, "pc: " INTPTR_FORMAT " sp: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT, p2i(pc), p2i(sp), p2i(unextended_sp), p2i(fp));

  setup(pc);
}

inline frame::frame(intptr_t* sp) : frame(sp, sp, *(intptr_t**)(sp - frame::sender_sp_offset), *(address*)(sp - 1)) {}

inline void frame::setup(address pc) {
  adjust_unextended_sp();

  address original_pc = CompiledMethod::get_deopt_original_pc(this);
  if (original_pc != NULL) {
    _pc = original_pc;
    _deopt_state = is_deoptimized;
    assert(_cb == NULL || _cb->as_compiled_method()->insts_contains_inclusive(_pc),
           "original PC must be in the main code section of the the compiled method (or must be immediately following it)");
  } else {
    if (_cb == SharedRuntime::deopt_blob()) {
      _deopt_state = is_deoptimized;
    } else {
      _deopt_state = not_deoptimized;
    }
  }
}

inline frame::frame(intptr_t* sp, intptr_t* fp) {
  _sp = sp;
  _unextended_sp = sp;
  _fp = fp;
  _pc = (address)(sp[-1]);

  // Here's a sticky one. This constructor can be called via AsyncGetCallTrace
  // when last_Java_sp is non-null but the pc fetched is junk. If we are truly
  // unlucky the junk value could be to a zombied method and we'll die on the
  // find_blob call. This is also why we can have no asserts on the validity
  // of the pc we find here. AsyncGetCallTrace -> pd_get_top_frame_for_signal_handler
  // -> pd_last_frame should use a specialized version of pd_last_frame which could
  // call a specialized frame constructor instead of this one.
  // Then we could use the assert below. However this assert is of somewhat dubious
  // value.
  // UPDATE: this constructor is only used by trace_method_handle_stub() now.
  // assert(_pc != NULL, "no pc?");

  _cb = CodeCache::find_blob(_pc);
  adjust_unextended_sp();

  address original_pc = CompiledMethod::get_deopt_original_pc(this);
  if (original_pc != NULL) {
    _pc = original_pc;
    _deopt_state = is_deoptimized;
  } else {
    _deopt_state = not_deoptimized;
  }
  _oop_map = NULL;
}

inline frame::frame(int sp, int ref_sp, intptr_t fp, address pc, CodeBlob* cb, bool deopt) {
  _cont_sp._sp = sp;
  _cont_sp._ref_sp = ref_sp;
  _unextended_sp = NULL;
  _fp = (intptr_t*)fp;
  _pc = pc;
  assert(pc != NULL, "no pc?");
  _cb = cb;
  _deopt_state = deopt ? is_deoptimized : not_deoptimized;
  _oop_map = NULL;
}

// Accessors

inline bool frame::equal(frame other) const {
  bool ret =  sp() == other.sp()
              && unextended_sp() == other.unextended_sp()
              && fp() == other.fp()
              && pc() == other.pc();
  assert(!ret || ret && cb() == other.cb() && _deopt_state == other._deopt_state, "inconsistent construction");
  return ret;
}

// Return unique id for this frame. The id must have a value where we can distinguish
// identity and younger/older relationship. NULL represents an invalid (incomparable)
// frame.
inline intptr_t* frame::id(void) const { return unextended_sp(); }

// Return true if the frame is older (less recent activation) than the frame represented by id
inline bool frame::is_older(intptr_t* id) const   { assert(this->id() != NULL && id != NULL, "NULL frame id");
                                                    return this->id() > id ; }



inline intptr_t* frame::link() const              { return (intptr_t*) *(intptr_t **)addr_at(link_offset); }

inline intptr_t* frame::unextended_sp() const     { return _unextended_sp; }

inline size_t frame::frame_index() const          { return _frame_index; }

inline void frame::set_frame_index(size_t index)  { _frame_index = index; }

inline intptr_t* frame::real_fp() const {
  if (_cb != NULL) {
    // use the frame size if valid
    int size = _cb->frame_size();
    if (size > 0) {
      return unextended_sp() + size;
    }
  }
  // else rely on fp()
  assert(! is_compiled_frame(), "unknown compiled frame size");
  return fp();
}

inline int frame::frame_size() const {
  return is_interpreted_frame()
    ? sender_sp() - sp()
    : cb()->frame_size();
}

inline int frame::num_oops() const {
  assert (!is_interpreted_frame(), "interpreted");
  assert (oop_map() != NULL, "");
  return oop_map()->num_oops() ;
}

inline int frame::compiled_frame_stack_argsize() const {
  assert (cb()->is_compiled(), "");
  return cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size;
}

inline void frame::interpreted_frame_oop_map(InterpreterOopMap* mask) const {
  assert (mask != NULL, "");
  Method* m = interpreter_frame_method();
  int   bci = interpreter_frame_bci();
  m->mask_for(bci, mask); // OopMapCache::compute_one_oop_map(m, bci, mask);
}


inline int frame::interpreted_frame_num_oops(InterpreterOopMap* mask) const {
  return   mask->num_oops()
        + 1 // for the mirror oop
        + ((intptr_t*)interpreter_frame_monitor_begin() - (intptr_t*)interpreter_frame_monitor_end())/BasicObjectLock::size();
}

// helper to update a map with callee-saved RBP

template <typename RegisterMapT>
void frame::update_map_with_saved_link(RegisterMapT* map, intptr_t** link_addr) {
  // The interpreter and compiler(s) always save EBP/RBP in a known
  // location on entry. We must record where that location is
  // so this if EBP/RBP was live on callout from c2 we can find
  // the saved copy no matter what it called.

  // Since the interpreter always saves EBP/RBP if we record where it is then
  // we don't have to always save EBP/RBP on entry and exit to c2 compiled
  // code, on entry will be enough.
  map->set_location(rbp->as_VMReg(), (address) link_addr);
#ifdef AMD64
  // this is weird "H" ought to be at a higher address however the
  // oopMaps seems to have the "H" regs at the same address and the
  // vanilla register.
  // XXXX make this go away
  if (true) {
    map->set_location(rbp->as_VMReg()->next(), (address) link_addr);
  }
#endif // AMD64
}

template <typename RegisterMapT>
intptr_t** frame::saved_link_address(const RegisterMapT* map) {
  return (intptr_t**)map->location(rbp->as_VMReg());
}

// Return address:

inline address* frame::sender_pc_addr()      const { return (address*) addr_at( return_addr_offset); }
inline address  frame::sender_pc()           const { return *sender_pc_addr(); }

inline intptr_t*    frame::sender_sp()        const { return            addr_at(   sender_sp_offset); }

inline intptr_t** frame::interpreter_frame_locals_addr() const {
  return (intptr_t**)addr_at(interpreter_frame_locals_offset);
}

inline intptr_t* frame::interpreter_frame_last_sp() const {
  return *(intptr_t**)addr_at(interpreter_frame_last_sp_offset);
}

inline intptr_t* frame::interpreter_frame_bcp_addr() const {
  return (intptr_t*)addr_at(interpreter_frame_bcp_offset);
}


inline intptr_t* frame::interpreter_frame_mdp_addr() const {
  return (intptr_t*)addr_at(interpreter_frame_mdp_offset);
}



// Constant pool cache

inline ConstantPoolCache** frame::interpreter_frame_cache_addr() const {
  return (ConstantPoolCache**)addr_at(interpreter_frame_cache_offset);
}

// Method

inline Method** frame::interpreter_frame_method_addr() const {
  return (Method**)addr_at(interpreter_frame_method_offset);
}

// Mirror

inline oop* frame::interpreter_frame_mirror_addr() const {
  return (oop*)addr_at(interpreter_frame_mirror_offset);
}

// top of expression stack
inline intptr_t* frame::interpreter_frame_tos_address() const {
  intptr_t* last_sp = interpreter_frame_last_sp();
  if (last_sp == NULL) {
    return sp();
  } else {
    // sp() may have been extended or shrunk by an adapter.  At least
    // check that we don't fall behind the legal region.
    // For top deoptimized frame last_sp == interpreter_frame_monitor_end.
    assert(last_sp <= (intptr_t*) interpreter_frame_monitor_end(), "bad tos");
    return last_sp;
  }
}

inline oop* frame::interpreter_frame_temp_oop_addr() const {
  return (oop *)(fp() + interpreter_frame_oop_temp_offset);
}

inline int frame::interpreter_frame_monitor_size() {
  return BasicObjectLock::size();
}


// expression stack
// (the max_stack arguments are used by the GC; see class FrameClosure)

inline intptr_t* frame::interpreter_frame_expression_stack() const {
  intptr_t* monitor_end = (intptr_t*) interpreter_frame_monitor_end();
  return monitor_end-1;
}

// Entry frames

inline JavaCallWrapper** frame::entry_frame_call_wrapper_addr() const {
 return (JavaCallWrapper**)addr_at(entry_frame_call_wrapper_offset);
}

// Compiled frames

inline oop frame::saved_oop_result(RegisterMap* map) const {
  oop* result_adr = (oop *)map->location(rax->as_VMReg());
  guarantee(result_adr != NULL, "bad register save location");
  oop result = *result_adr;

  // TODO: Erik: remove after integration with concurrent stack scanning
  result = NativeAccess<>::oop_load(&result);

  return result;
}

inline void frame::set_saved_oop_result(RegisterMap* map, oop obj) {
  oop* result_adr = (oop *)map->location(rax->as_VMReg());
  guarantee(result_adr != NULL, "bad register save location");

  *result_adr = obj;
}

inline bool frame::is_interpreted_frame() const {
  return Interpreter::contains(pc());
}

template <typename LOOKUP>
frame frame::frame_sender(RegisterMap* map) const {
  // Default is we done have to follow them. The sender_for_xxx will
  // update it accordingly
  map->set_include_argument_oops(false);

  if (is_entry_frame())       return sender_for_entry_frame(map);
  if (is_interpreted_frame()) return sender_for_interpreter_frame(map);

  assert(_cb == CodeCache::find_blob(pc()), "Must be the same");

  if (_cb != NULL) {
    return _cb->is_compiled() ? sender_for_compiled_frame<LOOKUP, false>(map) : sender_for_compiled_frame<LOOKUP, true>(map);
  }
  // Must be native-compiled frame, i.e. the marshaling code for native
  // methods that exists in the core system.
  return frame(sender_sp(), link(), sender_pc());
}

//------------------------------------------------------------------------------
// frame::sender_for_compiled_frame
template <typename LOOKUP, bool stub>
frame frame::sender_for_compiled_frame(RegisterMap* map) const {
  assert(map != NULL, "map must be set");

  if (map->in_cont()) { // already in an h-stack
    return Continuation::sender_for_compiled_frame(*this, map);
  }

  // frame owned by optimizing compiler
  assert(_cb->frame_size() >= 0, "must have non-zero frame size");
  intptr_t* sender_sp = unextended_sp() + _cb->frame_size();

  assert (sender_sp == real_fp(), "sender_sp: " INTPTR_FORMAT " real_fp: " INTPTR_FORMAT, p2i(sender_sp), p2i(real_fp()));

  // On Intel the return_address is always the word on the stack
  address sender_pc = (address) *(sender_sp-1);

  // This is the saved value of EBP which may or may not really be an FP.
  // It is only an FP if the sender is an interpreter frame (or C1?).
  // saved_fp_addr should be correct even for a bottom thawed frame (with a return barrier)
  intptr_t** saved_fp_addr = (intptr_t**) (sender_sp - frame::sender_sp_offset);
  intptr_t* sender_fp = *saved_fp_addr;
  if (map->update_map()) {
    // Tell GC to use argument oopmaps for some runtime stubs that need it.
    // For C1, the runtime stub might not have oop maps, so set this flag
    // outside of update_register_map.
    if (stub) { // compiled frames do not use callee-saved registers
      map->set_include_argument_oops(_cb->caller_must_gc_arguments(map->thread()));
      if (oop_map() != NULL) { 
        _oop_map->update_register_map(this, map);
      }
    } else {
      assert (!_cb->caller_must_gc_arguments(map->thread()), "");
      assert (!map->include_argument_oops(), "");
      assert (oop_map() == NULL || !oop_map()->has_any(OopMapValue::callee_saved_value), "callee-saved value in compiled frame");
    }

    // Since the prolog does the save and restore of EBP there is no oopmap
    // for it so we must fill in its location as if there was an oopmap entry
    // since if our caller was compiled code there could be live jvm state in it.
    update_map_with_saved_link(map, saved_fp_addr);
  }

  assert(sender_sp != sp(), "must have changed");

  if (Continuation::is_return_barrier_entry(sender_pc)) {	
    if (map->walk_cont()) { // about to walk into an h-stack 	
      return Continuation::top_frame(*this, map);	
    } else {
      Continuation::fix_continuation_bottom_sender(map->thread(), *this, &sender_pc, &sender_sp);	
    }
  } else if (map->walk_cont() && Continuation::is_continuation_enterSpecial(*this)) {
    assert (map->cont() != (oop)NULL, "");
    map->set_cont(Continuation::continuation_parent(map->cont()));
  }

  intptr_t* unextended_sp = sender_sp;
  CodeBlob* sender_cb = LOOKUP::find_blob(sender_pc);
  if (sender_cb != NULL) {
    return frame(sender_sp, unextended_sp, sender_fp, sender_pc, sender_cb);
  }
  // tty->print_cr(">>>> NO CB sender_pc: %p", sender_pc); os::print_location(tty, (intptr_t)sender_pc); print_on(tty);
  return frame(sender_sp, unextended_sp, sender_fp, sender_pc);
}

inline const ImmutableOopMap* frame::get_oop_map() const {
  if (_cb == NULL) return NULL;
  if (_cb->oop_maps() != NULL) {
    NativePostCallNop* nop = nativePostCallNop_at(_pc);
    if (nop != NULL &&
#ifdef CONT_DOUBLE_NOP
      !nop->is_mode2() &&
#endif
      nop->displacement() != 0
    ) {
      int slot = ((nop->displacement() >> 24) & 0xff);
      return _cb->oop_map_for_slot(slot, _pc);
    }
    const ImmutableOopMap* oop_map = OopMapSet::find_map(this);
    return oop_map;
  }
  return NULL;
}

#endif // CPU_X86_FRAME_X86_INLINE_HPP

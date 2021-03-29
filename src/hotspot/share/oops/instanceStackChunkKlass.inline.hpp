/* Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP
#define SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

#include "classfile/javaClasses.hpp"
#include "code/codeBlob.hpp"
#include "code/codeCache.hpp"
#include "code/nativeInst.hpp"
#include "compiler/oopMap.hpp"
#include "gc/shared/barrierSetNMethod.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/gc_globals.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/klass.hpp"
#include "oops/oop.inline.hpp"
#include "oops/stackChunkOop.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/globals.hpp"
#include "runtime/handles.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

#include CPU_HEADER_INLINE(instanceStackChunkKlass)

#if INCLUDE_ZGC
#include "gc/z/zAddress.inline.hpp"
#define FIX_DERIVED_POINTERS true
#endif
#if INCLUDE_SHENANDOAHGC
#define FIX_DERIVED_POINTERS true
#endif

#ifdef ASSERT
extern "C" bool dbg_is_safe(void* p, intptr_t errvalue);
extern "C" void pns2();
#endif

DEF_HANDLE_CONSTR(stackChunk, is_stackChunk_noinline)

inline stackChunkOopDesc* stackChunkOopDesc::parent() const         { return (stackChunkOopDesc*)(oopDesc*)jdk_internal_misc_StackChunk::parent(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_parent(stackChunkOopDesc* value) { jdk_internal_misc_StackChunk::set_parent(this, (oop)value); }
template<typename P> inline void stackChunkOopDesc::set_parent_raw(oop value) { jdk_internal_misc_StackChunk::set_parent_raw<P>(this, value); }
template<typename P> inline bool stackChunkOopDesc::is_parent_null() const    { return jdk_internal_misc_StackChunk::is_parent_null<P>(const_cast<stackChunkOopDesc*>(this)); }
inline oop stackChunkOopDesc::cont() const               { return jdk_internal_misc_StackChunk::cont(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_cont(oop value)       { jdk_internal_misc_StackChunk::set_cont(this, value); }
template<typename P> inline void stackChunkOopDesc::set_cont_raw(oop value)   { jdk_internal_misc_StackChunk::set_cont_raw<P>(this, value); }
inline int stackChunkOopDesc::stack_size() const         { return jdk_internal_misc_StackChunk::size(const_cast<stackChunkOopDesc*>(this)); }
inline int stackChunkOopDesc::sp() const                 { return jdk_internal_misc_StackChunk::sp(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_sp(int value)         { jdk_internal_misc_StackChunk::set_sp(this, value); }
inline address stackChunkOopDesc::pc() const             { return (address)jdk_internal_misc_StackChunk::pc(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_pc(address value)     { jdk_internal_misc_StackChunk::set_pc(this, (jlong)value); }
inline int stackChunkOopDesc::argsize() const            { return jdk_internal_misc_StackChunk::argsize(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_argsize(int value)    { jdk_internal_misc_StackChunk::set_argsize(const_cast<stackChunkOopDesc*>(this), value); }
inline uint8_t stackChunkOopDesc::flags() const         { return (uint8_t)jdk_internal_misc_StackChunk::flags(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_flags(uint8_t value) { jdk_internal_misc_StackChunk::set_flags(this, (jbyte)value); }
inline int stackChunkOopDesc::max_size() const           { return (int)jdk_internal_misc_StackChunk::maxSize(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_max_size(int value)   { jdk_internal_misc_StackChunk::set_maxSize(this, (jint)value); }
inline int stackChunkOopDesc::numFrames() const          { return jdk_internal_misc_StackChunk::numFrames(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_numFrames(int value)  { jdk_internal_misc_StackChunk::set_numFrames(this, value); }
inline int stackChunkOopDesc::numOops() const            { return jdk_internal_misc_StackChunk::numOops(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_numOops(int value)    { jdk_internal_misc_StackChunk::set_numOops(this, value); }
inline bool stackChunkOopDesc::gc_mode() const           { return (bool)jdk_internal_misc_StackChunk::gc_mode(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_gc_mode(bool value)   { jdk_internal_misc_StackChunk::set_gc_mode(this, (jboolean)value); }
inline int stackChunkOopDesc::gc_sp() const              { return jdk_internal_misc_StackChunk::gc_sp(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_gc_sp(int value)      { jdk_internal_misc_StackChunk::set_gc_sp(this, value); }
inline uint64_t stackChunkOopDesc::mark_cycle() const         { return (uint64_t)jdk_internal_misc_StackChunk::mark_cycle(const_cast<stackChunkOopDesc*>(this)); }
inline void stackChunkOopDesc::set_mark_cycle(uint64_t value) { jdk_internal_misc_StackChunk::set_mark_cycle(this, (jlong)value); }

inline int stackChunkOopDesc::end() const { return stack_size() - argsize(); }

inline intptr_t* stackChunkOopDesc::start_address() const { return (intptr_t*)InstanceStackChunkKlass::start_of_stack(const_cast<stackChunkOopDesc*>(this)); }

inline intptr_t* stackChunkOopDesc::end_address() const { return start_address() + end(); }
inline intptr_t* stackChunkOopDesc::sp_address()  const { return start_address() + sp(); }

inline int stackChunkOopDesc::to_offset(intptr_t* p) const {
  assert(is_in_chunk(p) || (p >= start_address() && (p - start_address()) <= stack_size() + InstanceStackChunkKlass::metadata_words()), 
    "p: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(p), p2i(start_address()), p2i(end_address()));
  return p - start_address();
}

inline intptr_t* stackChunkOopDesc::from_offset(int offset) const {
  assert(offset <= stack_size(), "");
  return start_address() + offset;
}

inline bool stackChunkOopDesc::is_empty() const {
  assert (is_stackChunk(), "");
  // assert ((sp() < end()) || (sp() >= stack_size()), "");
  return sp() >= stack_size() - argsize();
}

inline bool stackChunkOopDesc::is_in_chunk(void* p) const {
  assert (is_stackChunk(), "");
  HeapWord* start = (HeapWord*)start_address();
  HeapWord* end = start + stack_size();
  return (HeapWord*)p >= start && (HeapWord*)p < end;
}

bool stackChunkOopDesc::is_usable_in_chunk(void* p) const {
  assert (is_stackChunk(), "");
  HeapWord* start = (HeapWord*)start_address() + sp() - frame::sender_sp_offset;
  HeapWord* end = start + stack_size();
  return (HeapWord*)p >= start && (HeapWord*)p < end;
}

inline bool stackChunkOopDesc::is_flag(uint8_t flag) const {
  return (flags() & flag) != 0;
}
inline bool stackChunkOopDesc::is_non_null_and_flag(uint8_t flag) const {
  return this != nullptr && is_flag(flag);
}
inline void stackChunkOopDesc::set_flag(uint8_t flag, bool value) {
  uint32_t flags = this->flags();
  set_flags((uint8_t)(value ? flags |= flag : flags &= ~flag));
}
inline void stackChunkOopDesc::clear_flags() {
  set_flags(0);
}
inline bool stackChunkOopDesc::requires_barriers() const { 
  return Universe::heap()->requires_barriers(const_cast<stackChunkOopDesc*>(this));
}

inline bool stackChunkOopDesc::has_mixed_frames() const { return is_flag(FLAG_HAS_INTERPRETED_FRAMES); }

inline void stackChunkOopDesc::reset_counters() {
  set_numFrames(-1);
  set_numOops(-1);
}

inline intptr_t* stackChunkOopDesc::relative_base() const {
  // we relativize with respect to end rather than start because GC might compact the chunk
  return start_address() + stack_size() + InstanceStackChunkKlass::metadata_words();
}

inline intptr_t* stackChunkOopDesc::derelativize_address(int offset) const {
  intptr_t* base = relative_base();
  intptr_t* p = base - offset;
  // tty->print_cr(">>> derelativize_address: %d -> %p (base: %p)", offset, p, base);
  assert (start_address() <= p && p <= base, "");
  return p;
}

inline int stackChunkOopDesc::relativize_address(intptr_t* p) const {
  intptr_t* base = relative_base();
  intptr_t offset = base - p;
  // tty->print_cr(">>> relativize_address: %p -> %ld (base: %p)", p, offset, base);
  assert (start_address() <= p && p <= base, "");
  assert (0 <= offset && offset <= std::numeric_limits<int>::max(), "");
  return offset;
}

inline void stackChunkOopDesc::relativize_frame(frame& fr) const {
  fr.set_offset_sp(relativize_address(fr.sp()));
  fr.set_offset_unextended_sp(relativize_address(fr.unextended_sp()));
  relativize_frame_pd(fr);
}

inline void stackChunkOopDesc::derelativize_frame(frame& fr) const {
  fr.set_sp(derelativize_address(fr.offset_sp()));
  fr.set_unextended_sp(derelativize_address(fr.offset_unextended_sp()));
  derelativize_frame_pd(fr);
}

inline frame stackChunkOopDesc::relativize(frame fr)   const { relativize_frame(fr);   return fr; }
inline frame stackChunkOopDesc::derelativize(frame fr) const { derelativize_frame(fr); return fr; }

inline int stackChunkOopDesc::relativize_usp_offset(const frame& fr, const int usp_offset_in_bytes) const {
  assert (fr.is_compiled_frame() || fr.cb()->is_safepoint_stub(), "");
  assert (is_in_chunk(fr.unextended_sp()), "");

  intptr_t* base = fr.real_fp(); // equal to the caller's sp
  intptr_t* loc = (intptr_t*)((address)fr.unextended_sp() + usp_offset_in_bytes);
  assert (base > loc, "");
  int res = (int)(base - loc);
  // tty->print_cr(">>> relativize_usp_offset: %d -> %d -- address %p", usp_offset_in_bytes, res, loc); fr.print_on<true>(tty);
  return res;
}

inline address stackChunkOopDesc::reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg) const {
  assert (fr.is_compiled_frame(), "");
  assert (map != nullptr && map->stack_chunk() == const_cast<stackChunkOopDesc*>(this), "");

  // the offsets are saved in the map after going through relativize_usp_offset, so they are sp - loc, in words
  intptr_t offset = (intptr_t)map->location(reg, fr.sp()); // see usp_offset_to_index for the chunk case
  intptr_t* base = derelativize_address(fr.offset_sp());
  // tty->print_cr(">>> reg_to_location: %s -> %ld -- address: %p", reg->name(), offset, base - offset); fr.print_on(tty);
  return (address)(base - offset);
}

inline address stackChunkOopDesc::usp_offset_to_location(const frame& fr, const int usp_offset_in_bytes) const {
  assert (fr.is_compiled_frame(), "");
  // tty->print_cr(">>> usp_offset_to_location"); fr.print_on<true>(tty);
  return (address)derelativize_address(fr.offset_unextended_sp()) + usp_offset_in_bytes;
}

inline Method* stackChunkOopDesc::interpreter_frame_method(const frame& fr) {
  // tty->print_cr(">>> interpreter_frame_method"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_method();
}

inline address stackChunkOopDesc::interpreter_frame_bcp(const frame& fr) {
  // tty->print_cr(">>> interpreter_frame_bcp"); derelativize(fr).print_on<true>(tty);
  return derelativize(fr).interpreter_frame_bcp();
}

inline intptr_t* stackChunkOopDesc::interpreter_frame_expression_stack_at(const frame& fr, int index) const {
  // tty->print_cr(">>> interpreter_frame_expression_stack_at"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_expression_stack_at<true>(index);
}

inline intptr_t* stackChunkOopDesc::interpreter_frame_local_at(const frame& fr, int index) const {
  // tty->print_cr(">>> interpreter_frame_local_at"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_local_at<true>(index);
}


const int TwoWordAlignmentMask  = (1 << (LogBytesPerWord+1)) - 1;

inline void copy_from_stack_to_chunk(intptr_t* from, intptr_t* to, int size);
inline void copy_from_chunk_to_stack(intptr_t* from, intptr_t* to, int size);

template <bool dword_aligned>
inline void InstanceStackChunkKlass::copy_from_stack_to_chunk(void* from, void* to, size_t size) {
  if (dword_aligned) {
    assert (size >= 2, ""); // one word for return address, another for rbp spill
    assert(((intptr_t)from & TwoWordAlignmentMask) == 0, "");
    assert(((intptr_t)to   & WordAlignmentMask)    == 0, "");

    memcpy_fn_from_stack_to_chunk(from, to, size);
  } else {
    default_memcpy(from, to, size);
  }
}

template <bool dword_aligned>
inline void InstanceStackChunkKlass::copy_from_chunk_to_stack(void* from, void* to, size_t size) {
  if (dword_aligned) {
    assert (size >= 2, ""); // one word for return address, another for rbp spill
    assert(((intptr_t)from & WordAlignmentMask)    == 0, "");
    assert(((intptr_t)to   & TwoWordAlignmentMask) == 0, "");

    memcpy_fn_from_chunk_to_stack(from, to, size);
  } else {
    default_memcpy(from, to, size);
  }
}

template <bool dword_aligned>
inline void stackChunkOopDesc::copy_from_stack_to_chunk(intptr_t* from, intptr_t* to, int size) {
  assert (!requires_barriers(), "");

  log_develop_trace(jvmcont)("Chunk bounds: " INTPTR_FORMAT "(%d) - " INTPTR_FORMAT "(%d) (%d words, %d bytes)", 
    p2i(start_address()), to_offset(start_address()), p2i(end_address()), to_offset(end_address() - 1) + 1, stack_size(), stack_size() << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying from v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d words, %d bytes)", p2i(from), p2i(from + size), size, size << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying to h: " INTPTR_FORMAT "(%ld,%ld) - " INTPTR_FORMAT "(%ld,%ld) (%d words, %d bytes)", 
    p2i(to), to - start_address(), relative_base() - to, p2i(to + size), to + size - start_address(), relative_base() - (to + size), size, size << LogBytesPerWord);

  assert (to >= start_address(), "to: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(to), p2i(start_address()));
  assert (to + size <= start_address() + stack_size(), "to + size: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(to + size), p2i(start_address() + stack_size()));

  InstanceStackChunkKlass::copy_from_stack_to_chunk<dword_aligned>(from, to, size);
}

template <bool dword_aligned>
inline void stackChunkOopDesc::copy_from_chunk_to_stack(intptr_t* from, intptr_t* to, int size) {
  log_develop_trace(jvmcont)("Copying from h: " INTPTR_FORMAT "(%ld,%ld) - " INTPTR_FORMAT "(%ld,%ld) (%d words, %d bytes)", 
    p2i(from), from - start_address(), relative_base() - from, p2i(from + size), from + size - start_address(), relative_base() - (from + size), size, size << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying to v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d words, %d bytes)", p2i(to), p2i(to + size), size, size << LogBytesPerWord);

  assert (from >= start_address(), "from: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(from), p2i(start_address()));
  assert (from + size <= start_address() + stack_size(), "from + size: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(from + size), p2i(start_address() + stack_size()));

  InstanceStackChunkKlass::copy_from_chunk_to_stack<dword_aligned>(from, to, size);
}

template <class StackChunkFrameClosureType>
inline void stackChunkOopDesc::iterate_stack(StackChunkFrameClosureType* closure, MemRegion mr) {
  has_mixed_frames() ? iterate_stack<true >(closure, mr)
                     : iterate_stack<false>(closure, mr);
}

template <bool mixed, class StackChunkFrameClosureType>
inline void stackChunkOopDesc::iterate_stack(StackChunkFrameClosureType* closure, MemRegion mr) {
  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();


  // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " mixed: %d", p2i(this), mixed);

  const SmallRegisterMap* map = SmallRegisterMap::instance;
  assert (!map->in_cont(), "");

  StackChunkFrameStream<mixed> f(this);
  if (f.end() > h) {
    // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " bounded", p2i(this));
    f.set_end(h);
  }
  bool should_continue = true;

  if (f.is_stub()) {
    // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " safepoint yield stub frame: %d", p2i(this), f.index());
    // if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

    RegisterMap full_map((JavaThread*)nullptr, true, false, true);
    full_map.set_include_argument_oops(false);
    
    f.next(&full_map);

    // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " safepoint yield caller frame: %d", p2i(this), f.index());

    assert (!f.is_done(), "");
    assert (f.is_compiled(), "");

    if (f.sp() + f.frame_size() >= l) {
      // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " stub-caller frame: %d", p2i(this), f.index());
      // if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);

      should_continue = closure->template do_frame<mixed>((const StackChunkFrameStream<mixed>&)f, &full_map, mr);
    }
    f.next(map);
  }
  assert (!f.is_stub(), "");

  for(; should_continue && !f.is_done(); f.next(map)) {
    // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " frame: %d interpreted: %d", p2i(this), f.index(), f.is_interpreted());
    if (f.sp() + f.frame_size() < l) {
      // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " skip frame: %d", p2i(this), f.index());
      continue;
    }
    // if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);
    if (mixed) f.handle_deopted(); // in slow mode we might freeze deoptimized frames
    should_continue = closure->template do_frame<mixed>((const StackChunkFrameStream<mixed>&)f, map, mr);
    // if (!should_continue) log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " stop", p2i(this));
  }
  // log_develop_trace(jvmcont)("stackChunkOopDesc::iterate_stack this: " INTPTR_FORMAT " done index: %d", p2i(this), f.index());
}

#ifdef ASSERT
void InstanceStackChunkKlass::assert_mixed_correct(stackChunkOop chunk, bool mixed) {
  assert (!chunk->has_mixed_frames() || mixed, "has mixed frames: %d mixed: %d", chunk->has_mixed_frames(), mixed);
}
#endif

template <bool mixed>
StackChunkFrameStream<mixed>::StackChunkFrameStream(stackChunkOop chunk, bool gc) DEBUG_ONLY(: _chunk(chunk)) {
  assert (chunk->is_stackChunk(), "");
  assert (mixed || !chunk->has_mixed_frames(), "");

  DEBUG_ONLY(_index = 0;)
  _end = chunk->end_address();
  _sp = chunk->start_address() + get_initial_sp(chunk, gc);
  assert (_sp <= chunk->start_address() + chunk->stack_size() + InstanceStackChunkKlass::metadata_words(), "");

  get_cb();

  if (mixed) {
    if (!is_done() && is_interpreted()) {
      _unextended_sp = unextended_sp_for_interpreter_frame();
    } else {
      _unextended_sp = _sp;
    }
    assert (_unextended_sp >= _sp - InstanceStackChunkKlass::metadata_words(), "");
    // else if (is_compiled()) {
    //   tty->print_cr(">>>>> XXXX"); os::print_location(tty, (intptr_t)nativeCall_before(pc())->destination());
    //   assert (NativeCall::is_call_before(pc()) && nativeCall_before(pc()) != nullptr && nativeCall_before(pc())->destination() != nullptr, "");
    //   if (Interpreter::contains(nativeCall_before(pc())->destination())) { // interpreted callee
    //     _unextended_sp = unextended_sp_for_interpreter_frame_caller();
    //   }
    // }
  }
  DEBUG_ONLY(else _unextended_sp = nullptr;)
  
  if (is_stub()) {
    get_oopmap(pc(), 0);
    DEBUG_ONLY(_has_stub = true);
  } DEBUG_ONLY(else _has_stub = false;)
}

template <bool mixed>
StackChunkFrameStream<mixed>::StackChunkFrameStream(stackChunkOop chunk, const frame& f) DEBUG_ONLY(: _chunk(chunk)) {
  assert (chunk->is_stackChunk(), "");
  assert (mixed || !chunk->has_mixed_frames(), "");

  DEBUG_ONLY(_index = 0;)
  
  _end = chunk->end_address();

  assert (chunk->is_in_chunk(f.sp()), "");
  _sp = f.sp();
  if (mixed) {
    _unextended_sp = f.unextended_sp();
    assert (_unextended_sp >= _sp - InstanceStackChunkKlass::metadata_words(), "");
  }
  DEBUG_ONLY(else _unextended_sp = nullptr;)
  assert (_sp >= chunk->start_address() && _sp <= chunk->start_address() + chunk->stack_size() + InstanceStackChunkKlass::metadata_words(), "");

  if (f.cb() != nullptr) {
    _oopmap = nullptr;
    _cb = f.cb();
  } else {
    get_cb();
  }

  if (is_stub()) {
    get_oopmap(pc(), 0);
    DEBUG_ONLY(_has_stub = true);
  } DEBUG_ONLY(else _has_stub = false;)
}

template <bool mixed>
inline bool StackChunkFrameStream<mixed>::is_stub() const {
  return cb() != nullptr && (_cb->is_safepoint_stub() || _cb->is_runtime_stub());
}

template <bool mixed>
inline bool StackChunkFrameStream<mixed>::is_compiled() const {
  return cb() != nullptr && _cb->is_compiled();
}

template <bool mixed>
inline bool StackChunkFrameStream<mixed>::is_interpreted() const { 
  return mixed ? (!is_done() && Interpreter::contains(pc())) : false; 
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::frame_size() const {
  return (mixed && is_interpreted()) ? interpreter_frame_size() 
                                     : cb()->frame_size() + stack_argsize();
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::stack_argsize() const {
  if (mixed && is_interpreted()) return interpreter_frame_stack_argsize();
  if (is_stub()) return 0;
  guarantee (cb() != nullptr, "");
  guarantee (cb()->is_compiled(), "");
  guarantee (cb()->as_compiled_method()->method() != nullptr, "");
  return (cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord;
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::num_oops() const {
  return (mixed && is_interpreted()) ? interpreter_frame_num_oops() : oopmap()->num_oops();
}

template <bool mixed>
inline void StackChunkFrameStream<mixed>::initialize_register_map(RegisterMap* map) {
  update_reg_map_pd(map);
}

template <bool mixed>
template <typename RegisterMapT>
inline void StackChunkFrameStream<mixed>::next(RegisterMapT* map) {
  update_reg_map(map);
  bool safepoint = is_stub();
  if (mixed) {
    if (is_interpreted()) next_for_interpreter_frame();
    else {
      _sp = _unextended_sp + cb()->frame_size();
      if (_sp >= _end - InstanceStackChunkKlass::metadata_words()) {
        _sp = _end;
      }
      _unextended_sp = is_interpreted() ? unextended_sp_for_interpreter_frame() : _sp;
    }
    assert (_unextended_sp >= _sp - InstanceStackChunkKlass::metadata_words(), "");
  } else {
    _sp += cb()->frame_size();
  }
  assert (!is_interpreted() || _unextended_sp == unextended_sp_for_interpreter_frame(), "_unextended_sp: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(_unextended_sp), p2i(unextended_sp_for_interpreter_frame()));

  get_cb();
  update_reg_map_pd(map);
  if (safepoint && cb() != nullptr) _oopmap = cb()->oop_map_for_return_address(pc()); // there's no post-call nop and no fast oopmap lookup
  DEBUG_ONLY(_index++;)
}

template <bool mixed>
inline intptr_t* StackChunkFrameStream<mixed>::next_sp() const {
  return (mixed && is_interpreted()) ? next_sp_for_interpreter_frame() : unextended_sp() + cb()->frame_size();
}

template <bool mixed>
inline void StackChunkFrameStream<mixed>::get_cb() {
  _oopmap = nullptr;
  if (is_done() || (mixed && is_interpreted())) {
    _cb = nullptr;
    return;
  }

  assert (pc() != nullptr, 
  "index: %d sp: " INTPTR_FORMAT " sp offset: %d end offset: %d size: %d chunk sp: %d", 
  _index, p2i(sp()), _chunk->to_offset(sp()), _chunk->to_offset(_chunk->end_address()), _chunk->stack_size(), _chunk->sp());

  _cb = CodeCache::find_blob_fast(pc());

  // if (_cb == nullptr) { tty->print_cr("OOPS"); os::print_location(tty, (intptr_t)pc()); }
  assert (_cb != nullptr, 
    "index: %d sp: " INTPTR_FORMAT " sp offset: %d end offset: %d size: %d chunk sp: %d", 
    _index, p2i(sp()), _chunk->to_offset(sp()), _chunk->to_offset(_chunk->end_address()), _chunk->stack_size(), _chunk->sp());
  assert ((mixed && is_interpreted()) || ((is_stub() || is_compiled()) && _cb->frame_size() > 0), 
    "index: %d sp: " INTPTR_FORMAT " sp offset: %d end offset: %d size: %d chunk sp: %d is_stub: %d is_compiled: %d frame_size: %d mixed: %d", 
    _index, p2i(sp()), _chunk->to_offset(sp()), _chunk->to_offset(_chunk->end_address()), _chunk->stack_size(), _chunk->sp(), is_stub(), is_compiled(), _cb->frame_size(), mixed);
}

template <bool mixed>
inline void StackChunkFrameStream<mixed>::get_oopmap() const {
  if (mixed && is_interpreted()) return;
  assert (is_compiled(), "");
  get_oopmap(pc(), CodeCache::find_oopmap_slot_fast(pc())); 
}

template <bool mixed>
inline void StackChunkFrameStream<mixed>::get_oopmap(address pc, int oopmap_slot) const {
  assert (cb() != nullptr, "");
  assert (!is_compiled() || !cb()->as_compiled_method()->is_deopt_pc(pc), "oopmap_slot: %d", oopmap_slot);
  if (oopmap_slot >= 0) {
    assert (oopmap_slot >= 0, "");
    assert (cb()->oop_map_for_slot(oopmap_slot, pc) != nullptr, "");
    assert (cb()->oop_map_for_slot(oopmap_slot, pc) == cb()->oop_map_for_return_address(pc), "");

    _oopmap = cb()->oop_map_for_slot(oopmap_slot, pc);
  } else {
    _oopmap = cb()->oop_map_for_return_address(pc);
  }
  assert (_oopmap != nullptr, "");
}

template <bool mixed>
inline int StackChunkFrameStream<mixed>::get_initial_sp(stackChunkOop chunk, bool gc) {
  int chunk_sp = chunk->sp();
  // we don't invoke write barriers on oops in thawed frames, so we use the gcSP field to traverse thawed frames
  // if (gc && chunk_sp != chunk->gc_sp() && chunk->requires_barriers()) {
  //   uint64_t marking_cycle = CodeCache::marking_cycle() >> 1;
  //   uint64_t chunk_marking_cycle = chunk->mark_cycle() >> 1;
  //   if (marking_cycle == chunk_marking_cycle) {
  //     // Marking isn't finished, so we need to traverse thawed frames
  //     chunk_sp = chunk->gc_sp();
  //     assert (chunk_sp >= 0 && chunk_sp <= chunk->sp(), "");
  //   } else {
  //     chunk->set_gc_sp(chunk_sp); // atomic; benign race
  //   }
  // }
  assert (chunk_sp >= 0, "");
  return chunk_sp;
}

template <bool mixed>
template <typename RegisterMapT>
inline void* StackChunkFrameStream<mixed>::reg_to_loc(VMReg reg, const RegisterMapT* map) const {
  assert (!is_done(), "");
  return reg->is_reg() ? (void*)map->location(reg, sp()) // see frame::update_map_with_saved_link(&map, link_addr);
                       : (void*)((address)unextended_sp() + (reg->reg2stack() * VMRegImpl::stack_slot_size));
}

template<>
template<>
inline void StackChunkFrameStream<true>::update_reg_map(RegisterMap* map) {
  assert (!map->in_cont() || map->stack_chunk() == _chunk, "");
  if (map->update_map() && is_stub()) {
    frame f = to_frame();
    oopmap()->update_register_map(&f, map); // we have callee-save registers in this case
  }
}

template<>
template<>
inline void StackChunkFrameStream<false>::update_reg_map(RegisterMap* map) {
  assert (map->in_cont() && map->stack_chunk()() == _chunk, "");
  if (map->update_map()) {
    frame f = to_frame();
    oopmap()->update_register_map(&f, map); // we have callee-save registers in this case
  }
}

template <bool mixed>
template <typename RegisterMapT>
inline void StackChunkFrameStream<mixed>::update_reg_map(RegisterMapT* map) {}

template <bool mixed>
inline address  StackChunkFrameStream<mixed>::orig_pc() const {
  address pc1 = pc();
  if ((mixed && is_interpreted()) || is_stub()) return pc1;
  CompiledMethod* cm = cb()->as_compiled_method();
  if (cm->is_deopt_pc(pc1)) {
    pc1 = *(address*)((address)unextended_sp() + cm->orig_pc_offset());
  }

  assert (pc1 != nullptr && !cm->is_deopt_pc(pc1), "index: %d sp - start: %ld end - sp: %ld size: %d sp: %d", _index, sp() - (_chunk->start_address() + _chunk->sp()), end() - sp(), _chunk->stack_size(), _chunk->sp());
  assert (_cb == CodeCache::find_blob_fast(pc1), "");

  return pc1;
}

template <bool mixed>
void StackChunkFrameStream<mixed>::handle_deopted() const {
  assert (!is_done(), "");

  if (_oopmap != nullptr) return;
  if (mixed && is_interpreted()) return;
  
  assert (is_compiled(), "");

  address pc1 = pc();
  int oopmap_slot = CodeCache::find_oopmap_slot_fast(pc1);
  if (UNLIKELY(oopmap_slot < 0)) { // we could have marked frames for deoptimization in thaw_chunk
    // tty->print_cr(">>>> handle_deopted: deopted");
    CompiledMethod* cm = cb()->as_compiled_method();
    if (cm->is_deopt_pc(pc1)) {
      pc1 = orig_pc();
      oopmap_slot = CodeCache::find_oopmap_slot_fast(pc1);
    }
  }
  get_oopmap(pc1, oopmap_slot);
}

template <bool mixed>
template <class OopClosureType, class RegisterMapT>
inline void StackChunkFrameStream<mixed>::iterate_oops(OopClosureType* closure, const RegisterMapT* map, MemRegion mr) const {
  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();

  if (mixed && is_interpreted()) {
    frame f = to_frame();
    // InterpreterOopMap mask;
    // f.interpreted_frame_oop_map(&mask);
    f.oops_interpreted_do<true>(closure, nullptr, true);
  } else {
    DEBUG_ONLY(int oops = 0;)
    for (OopMapStream oms(oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
      OopMapValue omv = oms.current();
      if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
        continue;

      assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");
      DEBUG_ONLY(oops++;)

      void* p = reg_to_loc(omv.reg(), map);
      assert (p != nullptr, "");
      assert ((_has_stub && _index == 1) || is_in_frame(p), "");

      if ((intptr_t*)p < l || (intptr_t*)p >= h) continue;

      // if ((intptr_t*)p >= end) continue; // we could be walking the bottom frame's stack-passed args, belonging to the caller

      // if (!SkipNullValue::should_skip(*p))
      log_develop_trace(jvmcont)("StackChunkFrameStream::iterate_oops narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", omv.type() == OopMapValue::narrowoop_value, omv.reg()->name(), p2i(p), (intptr_t*)p - sp());
      omv.type() == OopMapValue::narrowoop_value ? Devirtualizer::do_oop(closure, (narrowOop*)p) : Devirtualizer::do_oop(closure, (oop*)p);
    }
    assert (oops == oopmap()->num_oops(), "oops: %d oopmap->num_oops(): %d", oops, oopmap()->num_oops());
  }
}

template<bool mixed>
template <class DerivedOopClosureType, class RegisterMapT>
inline void StackChunkFrameStream<mixed>::iterate_derived_pointers(DerivedOopClosureType* closure, const RegisterMapT* map, MemRegion mr) const {
  if (mixed && is_interpreted()) return;

  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();
  
  for (OopMapStream oms(oopmap()); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::derived_oop_value)
      continue;
    
    intptr_t* derived_loc = (intptr_t*)reg_to_loc(omv.reg(), map);
    intptr_t* base_loc    = (intptr_t*)reg_to_loc(omv.content_reg(), map); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1
    assert (is_in_frame(base_loc), "");
    assert (is_in_frame(derived_loc), "");
    assert (derived_loc != base_loc, "Base and derived in same location");
    assert (is_in_oops(base_loc, map), "not found: " INTPTR_FORMAT, p2i(base_loc));
    assert (!is_in_oops(derived_loc, map), "found: " INTPTR_FORMAT, p2i(derived_loc));

    if (((intptr_t*)derived_loc < l || (intptr_t*)derived_loc >= h)
      && ((intptr_t*)base_loc < l || (intptr_t*)base_loc >= h))
      continue;
    
    Devirtualizer::do_derived_oop(closure, (oop*)base_loc, (oop*)derived_loc);
  }
  OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
}

#ifdef ASSERT

template <bool mixed>
template <typename RegisterMapT>
bool StackChunkFrameStream<mixed>::is_in_oops(void* p, const RegisterMapT* map) const {
  for (OopMapStream oms(oopmap()); !oms.is_done(); oms.next()) {
    if (oms.current().type() != OopMapValue::oop_value)
      continue;
    if (reg_to_loc(oms.current().reg(), map) == p)
      return true;
  }
  return false;
}
#endif

template <bool mixed>
void InstanceStackChunkKlass::run_nmethod_entry_barrier_if_needed(const StackChunkFrameStream<mixed>& f) {
  CodeBlob* cb = f.cb();
  if ((mixed && cb == nullptr) || !cb->is_nmethod()) return;
  nmethod* nm = cb->as_nmethod();
  if (BarrierSet::barrier_set()->barrier_set_nmethod()->is_armed(nm)) {
    nm->run_nmethod_entry_barrier();
  }
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate(oop obj, OopClosureType* closure) {
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;
  if (Devirtualizer::do_metadata(closure)) {
    Devirtualizer::do_klass(closure, this);
  }
  (UseZGC || UseShenandoahGC)
    ? oop_oop_iterate_stack<true,  OopClosureType>(chunk, closure)
    : oop_oop_iterate_stack<false, OopClosureType>(chunk, closure);
  oop_oop_iterate_header<T>(chunk, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_reverse(oop obj, OopClosureType* closure) {
  assert (obj->is_stackChunk(), "");
  assert(!Devirtualizer::do_metadata(closure), "Code to handle metadata is not implemented");
  stackChunkOop chunk = (stackChunkOop)obj;
  (UseZGC || UseShenandoahGC)
    ? oop_oop_iterate_stack<true,  OopClosureType>(chunk, closure)
    : oop_oop_iterate_stack<false, OopClosureType>(chunk, closure);
  oop_oop_iterate_header<T>(chunk, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr) {
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;
  if (Devirtualizer::do_metadata(closure)) {
    if (mr.contains(obj)) {
      Devirtualizer::do_klass(closure, this);
    }
  }
  // InstanceKlass::oop_oop_iterate_bounded<T>(obj, closure, mr);
  oop_oop_iterate_stack_bounded<false>(chunk, closure, mr);
  oop_oop_iterate_header<T>(chunk, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_header(stackChunkOop chunk, OopClosureType* closure) {
  OrderAccess::storestore();
  Devirtualizer::do_oop(closure, (T*)chunk->obj_field_addr<T>(jdk_internal_misc_StackChunk::parent_offset()));
  OrderAccess::storestore();
  Devirtualizer::do_oop(closure, (T*)chunk->obj_field_addr<T>(jdk_internal_misc_StackChunk::cont_offset())); // must be last oop iterated
}

// template <class OopClosureType>
// void InstanceStackChunkKlass::stack_chunk_iterate_stack(stackChunkOop chunk, OopClosureType* closure) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack(chunk, (OopClosure*)closure, closure->do_metadata());
// }

// template <class OopClosureType>
// void InstanceStackChunkKlass::stack_chunk_iterate_stack_bounded(stackChunkOop chunk, OopClosureType* closure, MemRegion mr) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack_bounded(chunk, (OopClosure*)closure, closure->do_metadata(), mr);
// }

template<typename OopClosureType>
class CheckMutationWrapper: public OopClosure {
private:
  OopClosureType* const _closure;
  stackChunkOop _chunk;
public:
  CheckMutationWrapper(OopClosureType* closure, stackChunkOop chunk) : _closure(closure), _chunk(chunk), _mutated(false), _num_oops(0) {}
  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  bool _mutated;
  int _num_oops;
  
  template <typename T>
  void do_oop_work(T* p) {
    T before = *p;
    Devirtualizer::do_oop(_closure, p);
    _mutated |= before != *p;
    _num_oops++;
  }
};


// We replace derived pointers with offsets; the converse is done in DerelativizeDerivedPointers
template <bool concurrent_gc>
class RelativizeDerivedPointers : public DerivedOopClosure {
private:
  const stackChunkOop _chunk;

public:
  RelativizeDerivedPointers(stackChunkOop chunk) : _chunk(chunk) {}

  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) override {
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    // assert (Universe::heap()->is_in_or_null(base), "not an oop"); -- base might be invalid at this point
    if (base != (oop)nullptr) {
      assert (!CompressedOops::is_base(base), "");

#if INCLUDE_ZGC
      if (concurrent_gc && UseZGC) {
        if (ZAddress::is_good(cast_from_oop<uintptr_t>(base))) 
          return;
      }
#endif
#if INCLUDE_SHENANDOAHGC
      if (concurrent_gc && UseShenandoahGC) {
        if (!ShenandoahHeap::heap()->in_collection_set(base)) {
          return;
        }
      }
#endif

      OrderAccess::loadload();
      intptr_t derived_int_val = Atomic::load((intptr_t*)derived_loc); // *derived_loc;
      if (derived_int_val < 0) {
        return;
      }

      if (concurrent_gc) {
        _chunk->set_gc_mode(true);
        OrderAccess::storestore(); // if you see any following writes, you'll see this
      }

      // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
      // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
      intptr_t offset = derived_int_val - cast_from_oop<intptr_t>(base);
      // assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "offset: %ld size: %d", offset, (base->size() << LogHeapWordSize)); -- base might be invalid at this point
      Atomic::store((intptr_t*)derived_loc, -offset); // there could be a benign race here; we write a negative offset to let the sign bit signify it's an offset rather than an address
    } else {
      assert (*derived_loc == 0, "");
    }
  }
};

class DerelativizeDerivedPointers : public DerivedOopClosure {
public:
  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) override {
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load(base_loc);
    if (base != (oop)nullptr) {
      assert (!CompressedOops::is_base(base), "");
      ZGC_ONLY(assert (ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)

      OrderAccess::loadload();
      intptr_t offset = Atomic::load((intptr_t*)derived_loc); // *derived_loc;
      if (offset >= 0)
        return;

      // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
      // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
      if (offset < 0) {
        offset = -offset;
        assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "");
        Atomic::store((intptr_t*)derived_loc, cast_from_oop<intptr_t>(base) + offset);
      }
  #ifdef ASSERT 
      else { // DEBUG ONLY
        offset = offset - cast_from_oop<intptr_t>(base);
        assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "offset: %ld size: %d", offset, (base->size() << LogHeapWordSize));
      }
  #endif
    }
  }
};

template <bool concurrent_gc, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_stack(stackChunkOop chunk, OopClosureType* closure) {
  oop_oop_iterate_stack_bounded<concurrent_gc, OopClosureType>(chunk, closure, MemRegion(nullptr, SIZE_MAX));
}

template <bool concurrent_gc, typename OopClosureType>
class OopOopIterateStackClosure {
  stackChunkOop _chunk;
  const bool _do_destructive_processing;
  OopClosureType * const _closure;

public:
  int _num_frames, _num_oops;
  OopOopIterateStackClosure(stackChunkOop chunk, bool do_destructive_processing, OopClosureType* closure) 
    : _chunk(chunk), _do_destructive_processing(do_destructive_processing), _closure(closure), 
    _num_frames(0), _num_oops(0) {}

  template <bool mixed, typename RegisterMapT> 
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map, MemRegion mr) {
    log_develop_trace(jvmcont)("stack_chunk_iterate_stack sp: %ld pc: " INTPTR_FORMAT, f.sp() - _chunk->start_address(), p2i(f.pc()));
    // if (Continuation::is_return_barrier_entry(f.pc())) {
    //   assert ((int)(f.sp() - chunk->start_address(chunk)) < chunk->sp(), ""); // only happens when starting from gcSP
    //   return;
    // }

    _num_frames++;
    assert (_closure != nullptr, "");

    if (f.is_compiled()) f.handle_deopted(); // because of deopt in thaw; TODO: remove when changing deoptimization

    // For unload method debugging
    // tty->print_cr(">>>> OopOopIterateStackClosure::do_frame is_compiled: %d return_barrier: %d pc: %p", f.is_compiled(), Continuation::is_return_barrier_entry(f.pc()), f.pc()); f.print_on(tty);
    // if (f.is_compiled()) tty->print_cr(">>>> OopOopIterateStackClosure::do_frame nmethod: %p method: %p", f.cb()->as_nmethod(), f.cb()->as_compiled_method()->method());

    // if (log_develop_is_enabled(Trace, jvmcont)) cb->print_value_on(tty);

    CodeBlob* cb = f.cb();
    if (Devirtualizer::do_metadata(_closure)) {
      // The nmethod entry barrier takes care of having the right synchronization
      // when keeping the nmethod alive during concurrent execution.
      InstanceStackChunkKlass::run_nmethod_entry_barrier_if_needed<mixed>(f);
    }
    
    if (_do_destructive_processing) { // evacuation always takes place at a safepoint; for concurrent iterations, we skip derived pointers, which is ok b/c coarse card marking is used for chunks
      RelativizeDerivedPointers<concurrent_gc> derived_closure(_chunk);
      f.iterate_derived_pointers(&derived_closure, map, mr);
    }

    CheckMutationWrapper<OopClosureType> cl(_closure, _chunk);
    f.iterate_oops(&cl, map, mr);
    bool mutated_oops = cl._mutated;
    _num_oops += cl._num_oops;// f.oopmap()->num_oops();

    if (FIX_DERIVED_POINTERS && concurrent_gc && mutated_oops && _chunk->gc_mode()) { // TODO: this is a ZGC-specific optimization that depends on the one in iterate_derived_pointers
      DerelativizeDerivedPointers derived_closure;
      f.iterate_derived_pointers(&derived_closure, map, mr);
    }
    return true;
  }
};

template <bool concurrent_gc, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_stack_bounded(stackChunkOop chunk, OopClosureType* closure, MemRegion mr) {
  assert (Continuation::debug_is_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack requires_barriers: %d", !chunk->requires_barriers());

  bool do_destructive_processing; // should really be `= closure.is_destructive()`, if we had such a thing
  if (concurrent_gc) {
    do_destructive_processing = true;
  } else {
    if (SafepointSynchronize::is_at_safepoint() /*&& !chunk->gc_mode()*/) {
      do_destructive_processing = true;
      chunk->set_gc_mode(true);
    } else {
      do_destructive_processing = false;
    }
    assert (!SafepointSynchronize::is_at_safepoint() || chunk->gc_mode(), "gc_mode: %d is_at_safepoint: %d", chunk->gc_mode(), SafepointSynchronize::is_at_safepoint());
  }

  // tty->print_cr(">>>> OopOopIterateStackClosure::oop_oop_iterate_stack_bounded");
  OopOopIterateStackClosure<concurrent_gc, OopClosureType> frame_closure(chunk, do_destructive_processing, closure);
  chunk->iterate_stack(&frame_closure, mr);

  if (FIX_DERIVED_POINTERS && concurrent_gc) {
    OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
    chunk->set_gc_mode(false);
  }

  assert (frame_closure._num_frames >= 0, "");
  assert (frame_closure._num_oops >= 0, "");
  if (do_destructive_processing || closure == nullptr) {
    // chunk->set_numFrames(frame_closure._num_frames); -- TODO: remove those fields
    // chunk->set_numOops(frame_closure._num_oops);
  }

  if (closure != nullptr) {
    Continuation::emit_chunk_iterate_event(chunk, frame_closure._num_frames, frame_closure._num_oops);
  }

  log_develop_trace(jvmcont)("stack_chunk_iterate_stack ------- end -------");
  // tty->print_cr("<<< stack_chunk_iterate_stack %p %p", (oopDesc*)chunk, Thread::current());
}

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

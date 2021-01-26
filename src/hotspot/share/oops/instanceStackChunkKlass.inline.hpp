/* Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#include "compiler/oopMap.hpp"
#include "classfile/javaClasses.hpp"
#include "gc/shared/gc_globals.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/klass.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/frame.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

#if INCLUDE_ZGC
#include "gc/z/zAddress.inline.hpp"
#define FIX_DERIVED_POINTERS true
#endif

class StackChunkFrameStream : public StackObj {
 private:
  intptr_t* _end;
  intptr_t* _sp;
  CodeBlob* _cb;
  mutable const ImmutableOopMap* _oopmap;
  DEBUG_ONLY(const oop _chunk;)
  DEBUG_ONLY(int _index;)

 public:
  // Iteration
  StackChunkFrameStream(oop chunk, int gc = false) DEBUG_ONLY(: _chunk(chunk)) {
    DEBUG_ONLY(_index = 0;)
    assert (jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
    _end = jdk_internal_misc_StackChunk::end_address(chunk);
    intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
    _sp = start + get_initial_sp(chunk, gc);
    get_cb();
  }

  StackChunkFrameStream(oop chunk, const frame& f) DEBUG_ONLY(: _chunk(chunk)) {
    DEBUG_ONLY(_index = 0;)
    assert (jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
    _end = jdk_internal_misc_StackChunk::end_address(chunk);

    assert (jdk_internal_misc_StackChunk::is_in_chunk(chunk, f.sp()), "");
    _sp = f.sp();
    if (f.cb() != NULL) {
      _oopmap = NULL;
      _cb = f.cb();
    } else {
      get_cb();
    }
  }

  bool is_done() const { return _sp >= _end; }

  void next() { DEBUG_ONLY(_index++;) _sp += cb()->frame_size(); get_cb(); }

  intptr_t* end() { return _end; }
  void set_end(intptr_t* end) { _end = end; }

  // Query
  intptr_t*      end() const { return _end; }
  intptr_t*      sp() const  { return _sp; }
  inline address pc() const  { return get_pc(); }

  CodeBlob* cb() const { return _cb; }

  const ImmutableOopMap* oopmap() const {
    if (_oopmap == NULL) get_oopmap();
    return _oopmap;
  }

  void handle_deopted() {
    if (_oopmap != NULL) return;

    assert (!is_done(), "");
    address pc1 = pc();
    int oopmap_slot = CodeCache::find_oopmap_slot_fast(pc1);
    if (UNLIKELY(oopmap_slot < 0)) { // we could have marked frames for deoptimization in thaw_chunk
      CompiledMethod* cm = cb()->as_compiled_method();
      assert (cm->is_deopt_pc(pc()), "");
      pc1 = *(address*)((address)_sp + cm->orig_pc_offset());
      assert (!cm->is_deopt_pc(pc1), "");
      assert (_cb == ContinuationCodeBlobLookup::find_blob(pc1), "");
      oopmap_slot = CodeCache::find_oopmap_slot_fast(pc1);
    }
    get_oopmap(pc1, oopmap_slot);
  }

  inline int to_offset(oop chunk) const {
    assert (!is_done(), "");
    return _sp - jdk_internal_misc_StackChunk::start_address(chunk);
  }

  inline frame to_frame() const;

  inline void* reg_to_loc(VMReg reg) const;

#ifdef ASSERT
  bool is_in_frame(void* p) const;
  bool is_in_oops(void* p) const;
#endif

 private:
  inline address get_pc() const;
  inline void get_cb() {
    _oopmap = NULL;
    if (is_done()) {
      _cb = NULL;
      return;
    }

    assert (pc() != NULL, "");
    _cb = CodeCache::find_blob_fast(pc());
    assert (_cb != NULL && _cb->is_compiled() && _cb->frame_size() > 0, "");
  }

  inline void get_oopmap() const { get_oopmap(pc(), CodeCache::find_oopmap_slot_fast(pc())); }
  inline const void get_oopmap(address pc, int oopmap_slot) const {
    assert (cb() != NULL, "");
    assert (!cb()->as_compiled_method()->is_deopt_pc(pc), "oopmap_slot: %d", oopmap_slot);
    assert (oopmap_slot >= 0, "");
    _oopmap = cb()->oop_map_for_slot(oopmap_slot, pc);
    assert (_oopmap != NULL, "");
  }

  static inline int get_initial_sp(oop chunk, bool gc) {
    int chunk_sp = jdk_internal_misc_StackChunk::sp(chunk);
    // we don't invoke write barriers on oops in thawed frames, so we use the gcSP field to traverse thawed frames
    // if (gc && chunk_sp != jdk_internal_misc_StackChunk::gc_sp(chunk) && Universe::heap()->requires_barriers(chunk)) {
    //   uint64_t marking_cycle = CodeCache::marking_cycle() >> 1;
    //   uint64_t chunk_marking_cycle = jdk_internal_misc_StackChunk::mark_cycle(chunk) >> 1;
    //   if (marking_cycle == chunk_marking_cycle) {
    //     // Marking isn't finished, so we need to traverse thawed frames
    //     chunk_sp = jdk_internal_misc_StackChunk::gc_sp(chunk);
    //     assert (chunk_sp >= 0 && chunk_sp <= jdk_internal_misc_StackChunk::sp(chunk), "");
    //   } else {
    //     jdk_internal_misc_StackChunk::set_gc_sp(chunk, chunk_sp); // atomic; benign race
    //   }
    // }
    return chunk_sp;
  }
};

#include CPU_HEADER_INLINE(instanceStackChunkKlass)

// class DecoratorOopClosure : public BasicOopIterateClosure {
//   OopIterateClosure* _cl;
//  public:
//   DecoratorOopClosure(OopIterateClosure* cl) : _cl(cl) {}

//   virtual bool do_metadata() { return _cl->do_metadata(); }

//   virtual void do_klass(Klass* k) { _cl->do_klass(k); }
//   virtual void do_cld(ClassLoaderData* cld) { _cl->do_cld(cld); }

//   virtual void do_oop(oop* o) {
//     _cl->do_oop(o);
//   }
//   virtual void do_oop(narrowOop* o) {
//     if (!CompressedOops::is_null(*o)) {
//       oop obj = CompressedOops::decode_not_null(*o);
//     }
//     _cl->do_oop(o);
//   }
// };

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate(oop obj, OopClosureType* closure) {
  if (Devirtualizer::do_metadata(closure)) {
    Devirtualizer::do_klass(closure, this);
  }
  UseZGC
    ? oop_oop_iterate_stack<OopClosureType, true> (obj, closure)
    : oop_oop_iterate_stack<OopClosureType, false>(obj, closure);
  oop_oop_iterate_header<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_reverse(oop obj, OopClosureType* closure) {
  assert(!Devirtualizer::do_metadata(closure), "Code to handle metadata is not implemented");

  UseZGC
    ? oop_oop_iterate_stack<OopClosureType, true> (obj, closure)
    : oop_oop_iterate_stack<OopClosureType, false>(obj, closure);
  oop_oop_iterate_header<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr) {
  if (Devirtualizer::do_metadata(closure)) {
    if (mr.contains(obj)) {
      Devirtualizer::do_klass(closure, this);
    }
  }
  // InstanceKlass::oop_oop_iterate_bounded<T>(obj, closure, mr);
  oop_oop_iterate_stack_bounded(obj, closure, mr);
  oop_oop_iterate_header<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_header(oop obj, OopClosureType* closure) {
  OrderAccess::storestore();
  Devirtualizer::do_oop(closure, (T*)obj->obj_field_addr<T>(jdk_internal_misc_StackChunk::parent_offset()));
  OrderAccess::storestore();
  Devirtualizer::do_oop(closure, (T*)obj->obj_field_addr<T>(jdk_internal_misc_StackChunk::cont_offset())); // must be last oop iterated
}

// template <class OopClosureType>
// void InstanceStackChunkKlass::stack_chunk_iterate_stack(oop chunk, OopClosureType* closure) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack(chunk, (OopClosure*)closure, closure->do_metadata());
// }

// template <class OopClosureType>
// void InstanceStackChunkKlass::stack_chunk_iterate_stack_bounded(oop chunk, OopClosureType* closure, MemRegion mr) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack_bounded(chunk, (OopClosure*)closure, closure->do_metadata(), mr);
// }

template <class OopClosureType>
bool InstanceStackChunkKlass::iterate_oops(OopClosureType* closure, const StackChunkFrameStream& f) {
  DEBUG_ONLY(int oops = 0;)
  bool mutated = false;
  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;

    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");
    DEBUG_ONLY(oops++;)

    void* p = f.reg_to_loc(omv.reg());
    assert (p != NULL, "");
    assert (f.is_in_frame(p), "");

    // if ((intptr_t*)p >= end) continue; // we could be walking the bottom frame's stack-passed args, belonging to the caller

    // if (!SkipNullValue::should_skip(*p))
    log_develop_trace(jvmcont)("stack_chunk_iterate_stack narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", omv.type() == OopMapValue::narrowoop_value, omv.reg()->name(), p2i(p), (intptr_t*)p - f.sp());
    // DEBUG_ONLY(intptr_t old = *(intptr_t*)p;)
    intptr_t before = *(intptr_t*)p;
    omv.type() == OopMapValue::narrowoop_value ? Devirtualizer::do_oop(closure, (narrowOop*)p) : Devirtualizer::do_oop(closure, (oop*)p);
    mutated |= before != *(intptr_t*)p;
  }
  assert (oops == f.oopmap()->num_oops(), "oops: %d oopmap->num_oops(): %d", oops, f.oopmap()->num_oops());
  return mutated;
}

template <class OopClosureType, bool concurrent_gc>
void InstanceStackChunkKlass::oop_oop_iterate_stack(oop chunk, OopClosureType* closure) {
  // see sender_for_compiled_frame
  const int frame_metadata = 2;

  assert (Continuation::debug_is_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack requires_barriers: %d", !Universe::heap()->requires_barriers(chunk));

  // TODO: return if chunk is empty; add is_empty to jdk_internal_misc_StackChunk and remove ContMirror::is_chunk_empty
  int num_frames = 0;
  int num_oops = 0;

  bool do_destructive_processing; // should really be `= closure.is_destructive()`, if we had such a thing
  if (concurrent_gc) {
    do_destructive_processing = true;
  } else {
    if (SafepointSynchronize::is_at_safepoint() /*&& !jdk_internal_misc_StackChunk::gc_mode(chunk)*/) {
      do_destructive_processing = true;
      jdk_internal_misc_StackChunk::set_gc_mode(chunk, true);
    } else {
      do_destructive_processing = false;
    }
    assert (!SafepointSynchronize::is_at_safepoint() || jdk_internal_misc_StackChunk::gc_mode(chunk), "gc_mode: %d is_at_safepoint: %d", jdk_internal_misc_StackChunk::gc_mode(chunk), SafepointSynchronize::is_at_safepoint());
  }

  for (StackChunkFrameStream f(chunk, true); !f.is_done(); f.next()) {
    log_develop_trace(jvmcont)("stack_chunk_iterate_stack sp: %ld pc: " INTPTR_FORMAT, f.sp() - jdk_internal_misc_StackChunk::start_address(chunk), p2i(f.pc()));
    // if (Continuation::is_return_barrier_entry(f.pc())) {
    //   assert ((int)(f.sp() - jdk_internal_misc_StackChunk::start_address(chunk)) < jdk_internal_misc_StackChunk::sp(chunk), ""); // only happens when starting from gcSP
    //   break;
    // }

    f.handle_deopted(); // because of deopt in thaw; TODO: remove when changing deoptimization
    CodeBlob* cb = f.cb();
    const ImmutableOopMap* oopmap = f.oopmap();

    if (log_develop_is_enabled(Trace, jvmcont)) cb->print_value_on(tty);

    if (Devirtualizer::do_metadata(closure) && cb->is_nmethod()) {
      // The nmethod entry barrier takes care of having the right synchronization
      // when keeping the nmethod alive during concurrent execution.
      cb->as_nmethod_or_null()->run_nmethod_entry_barrier();
    }

    num_frames++;
    num_oops += oopmap->num_oops();
    if (closure == NULL) {
      continue;
    }
    
    if (do_destructive_processing) { // evacuation always takes place at a safepoint; for concurrent iterations, we skip derived pointers, which is ok b/c coarse card marking is used for chunks
      iterate_derived_pointers<concurrent_gc>(chunk, f);
    }

    bool mutated_oops = iterate_oops(closure, f);

    if (FIX_DERIVED_POINTERS && concurrent_gc && mutated_oops && jdk_internal_misc_StackChunk::gc_mode(chunk)) { // TODO: this is a ZGC-specific optimization that depends on the one in iterate_derived_pointers
      fix_derived_pointers(f);
    }
  }

  if (FIX_DERIVED_POINTERS && concurrent_gc) {
    OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
    jdk_internal_misc_StackChunk::set_gc_mode(chunk, false);
  }

  assert (num_frames >= 0, "");
  assert (num_oops >= 0, "");
  if (do_destructive_processing || closure == NULL) {
    // jdk_internal_misc_StackChunk::set_numFrames(chunk, num_frames); -- TODO: remove those fields
    // jdk_internal_misc_StackChunk::set_numOops(chunk, num_oops);
  }

  if (closure != NULL) {
    Continuation::emit_chunk_iterate_event(chunk, num_frames, num_oops);
  }

  // assert(Continuation::debug_verify_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack ------- end -------");
  // tty->print_cr("<<< stack_chunk_iterate_stack %p %p", (oopDesc*)chunk, Thread::current());
}

template <class OopClosureType>
bool InstanceStackChunkKlass::iterate_oops(OopClosureType* closure, const StackChunkFrameStream& f, MemRegion mr) {
  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();

  DEBUG_ONLY(int oops = 0;)
  bool mutated = false;
  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;

    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");
    DEBUG_ONLY(oops++;)

    void* p = f.reg_to_loc(omv.reg());
    assert (p != NULL, "");
    assert (f.is_in_frame(p), "");
    if ((intptr_t*)p < l || (intptr_t*)p >= h) continue;

    // if ((intptr_t*)p >= end) continue; // we could be walking the bottom frame's stack-passed args, belonging to the caller

    // if (!SkipNullValue::should_skip(*p))
    log_develop_trace(jvmcont)("stack_chunk_iterate_stack_bounded narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", omv.type() == OopMapValue::narrowoop_value, omv.reg()->name(), p2i(p), (intptr_t*)p - f.sp());
    // DEBUG_ONLY(intptr_t old = *(intptr_t*)p;)
    intptr_t before = *(intptr_t*)p;
    omv.type() == OopMapValue::narrowoop_value ? Devirtualizer::do_oop(closure, (narrowOop*)p) : Devirtualizer::do_oop(closure, (oop*)p);
    mutated |= before != *(intptr_t*)p;
  }
  assert (oops == f.oopmap()->num_oops(), "oops: %d oopmap->num_oops(): %d", oops, f.oopmap()->num_oops());
  return mutated;
}

template <class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_stack_bounded(oop chunk, OopClosureType* closure, MemRegion mr) {
  assert (!UseZGC, "");
  
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack_bounded");
  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();

  // see sender_for_compiled_frame
  const int frame_metadata = 2;

  assert (Continuation::debug_is_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack_bounded requires_barriers: %d", !Universe::heap()->requires_barriers(chunk));

  int num_frames = 0;
  int num_oops = 0;

  bool do_destructive_processing; // should really be `= closure.is_destructive()`, if we had such a thing
  if (SafepointSynchronize::is_at_safepoint() && !jdk_internal_misc_StackChunk::gc_mode(chunk)) {
    do_destructive_processing = true;
    jdk_internal_misc_StackChunk::set_gc_mode(chunk, true);
  } else {
    do_destructive_processing = false;
  }
  assert (!SafepointSynchronize::is_at_safepoint() || jdk_internal_misc_StackChunk::gc_mode(chunk), "gc_mode: %d is_at_safepoint: %d", jdk_internal_misc_StackChunk::gc_mode(chunk), SafepointSynchronize::is_at_safepoint());

  StackChunkFrameStream f(chunk, true);
  if (f.end() > h) f.set_end(h);
  for (; !f.is_done(); f.next()) {
    if (f.sp() + f.cb()->frame_size() < l) {
      continue;
    }

    log_develop_trace(jvmcont)("stack_chunk_iterate_stack_bounded sp: %ld pc: " INTPTR_FORMAT, f.sp() - jdk_internal_misc_StackChunk::start_address(chunk), p2i(f.pc()));

    if (log_develop_is_enabled(Trace, jvmcont)) f.cb()->print_value_on(tty);

    if (Devirtualizer::do_metadata(closure) && f.cb()->is_nmethod()) {
      // The nmethod entry barrier takes care of having the right synchronization
      // when keeping the nmethod alive during concurrent execution.
      f.cb()->as_nmethod_or_null()->run_nmethod_entry_barrier();
    }

    num_frames++;
    num_oops += f.oopmap()->num_oops();
    if (closure == NULL) {
      continue;
    }
    
    if (do_destructive_processing) { // evacuation always takes place at a safepoint; for concurrent iterations, we skip derived pointers, which is ok b/c coarse card marking is used for chunks
      iterate_derived_pointers(chunk, f, mr);
    }

    bool mutated_oops = iterate_oops(closure, f, mr);
  }

  assert (num_frames >= 0, "");
  assert (num_oops >= 0, "");
  if (do_destructive_processing || closure == NULL) {
    jdk_internal_misc_StackChunk::set_numFrames(chunk, num_frames);
    jdk_internal_misc_StackChunk::set_numOops(chunk, num_oops);
  }

  if (closure != NULL) {
    Continuation::emit_chunk_iterate_event(chunk, num_frames, num_oops);
  }

  // assert(Continuation::debug_verify_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack_bounded ------- end -------");
  // tty->print_cr("<<< stack_chunk_iterate_stack %p %p", (oopDesc*)chunk, Thread::current());
}

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

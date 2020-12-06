/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP
#define CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP

#include "memory/iterator.inline.hpp"
#include "runtime/frame.inline.hpp"

#if INCLUDE_ZGC
#include "gc/z/zAddress.inline.hpp"
#define FIX_DERIVED_POINTERS true
#endif

inline address StackChunkFrameStream::get_pc() const {
  assert (!is_done(), "");
  return *(address*)(_sp - 1);
}

inline void* StackChunkFrameStream::reg_to_loc(VMReg reg) const {
  assert (!is_done(), "");
  assert (!reg->is_reg() || reg == rbp->as_VMReg(), "");
  return reg->is_reg() ? (void*)(_sp - frame::sender_sp_offset) // see frame::update_map_with_saved_link(&map, link_addr);
                       : (void*)((address)_sp + (reg->reg2stack() * VMRegImpl::stack_slot_size));
}

inline frame StackChunkFrameStream::to_frame() const {
  intptr_t* fp = *(intptr_t**)(_sp - frame::sender_sp_offset);
  return frame(_sp, _sp, fp, pc(), cb(), NULL, true);
}

// We replace derived pointers with offsets; the converse is done in fix_stack_chunk
template <bool concurrent_gc>
static void iterate_derived_pointers(oop chunk, const StackChunkFrameStream& f) {
  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::derived_oop_value)
      continue;
    
    intptr_t* derived_loc = (intptr_t*)f.reg_to_loc(omv.reg());
    intptr_t* base_loc    = (intptr_t*)f.reg_to_loc(omv.content_reg()); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1
    assert (f.is_in_frame(base_loc), "");
    assert (f.is_in_frame(derived_loc), "");
    assert (derived_loc != base_loc, "Base and derived in same location");
    assert (f.is_in_oops(base_loc), "not found: " INTPTR_FORMAT, p2i(base_loc));
    assert (!f.is_in_oops(derived_loc), "found: " INTPTR_FORMAT, p2i(derived_loc));
    
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    // assert (Universe::heap()->is_in_or_null(base), "not an oop"); -- base might be invalid at this point
    if (base != (oop)NULL) {
      assert (!CompressedOops::is_base(base), "");

#if INCLUDE_ZGC
      if (concurrent_gc) { //  && UseZG
        if (ZAddress::is_good(cast_from_oop<uintptr_t>(base))) 
          continue;
      }
#endif

      OrderAccess::loadload();
      intptr_t derived_int_val = Atomic::load(derived_loc); // *derived_loc;
      if (derived_int_val < 0) {
        continue;
      }

      if (concurrent_gc) {
        jdk_internal_misc_StackChunk::set_gc_mode(chunk, true);
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
  OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
}

template <class OopClosureType>
static bool iterate_oops(OopClosureType* closure, const StackChunkFrameStream& f) {
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


// We replace derived pointers with offsets; the converse is done in fix_stack_chunk
static void iterate_derived_pointers(oop chunk, const StackChunkFrameStream& f, MemRegion mr) {
  intptr_t* const l = (intptr_t*)mr.start();
  intptr_t* const h = (intptr_t*)mr.end();

  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::derived_oop_value)
      continue;
    
    intptr_t* derived_loc = (intptr_t*)f.reg_to_loc(omv.reg());
    intptr_t* base_loc    = (intptr_t*)f.reg_to_loc(omv.content_reg()); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1
    
    if (((intptr_t*)derived_loc < l || (intptr_t*)derived_loc >= h)
      && ((intptr_t*)base_loc < l || (intptr_t*)base_loc >= h))
      continue;

    assert (f.is_in_frame(base_loc), "");
    assert (f.is_in_frame(derived_loc), "");
    assert (derived_loc != base_loc, "Base and derived in same location");
    assert (f.is_in_oops(base_loc), "not found: " INTPTR_FORMAT, p2i(base_loc));
    assert (!f.is_in_oops(derived_loc), "found: " INTPTR_FORMAT, p2i(derived_loc));
    
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    assert (oopDesc::is_oop_or_null(base), "not an oop");
    assert (Universe::heap()->is_in_or_null(base), "not an oop");
    if (base != (oop)NULL) {
      assert (!CompressedOops::is_base(base), "");

      OrderAccess::loadload();
      intptr_t derived_int_val = Atomic::load(derived_loc); // *derived_loc;
      if (derived_int_val < 0) {
        continue;
      }

      // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
      // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
      intptr_t offset = derived_int_val - cast_from_oop<intptr_t>(base);
      assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "offset: %ld size: %d", offset, (base->size() << LogHeapWordSize));
      Atomic::store((intptr_t*)derived_loc, -offset); // there could be a benign race here; we write a negative offset to let the sign bit signify it's an offset rather than an address
    } else {
      assert (*derived_loc == 0, "");
    }
  }
  OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
}

template <class OopClosureType>
static bool iterate_oops(OopClosureType* closure, const StackChunkFrameStream& f, MemRegion mr) {
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

#endif // CPU_X86_INSTANCESTACKCHUNKKLASS_X86_INLINE_HPP

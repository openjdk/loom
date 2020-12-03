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

#include "precompiled.hpp"
#include "jfr/jfrEvents.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"

#define SENDER_SP_RET_ADDRESS_OFFSET (frame::sender_sp_offset - frame::return_addr_offset)

#ifdef ASSERT
static bool requires_barriers(oop obj) {
  return Universe::heap()->requires_barriers(obj);
}
#endif

int InstanceStackChunkKlass::count_frames(oop chunk) {
  int frames = 0;
  CodeBlob* cb = NULL;
  intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* end   = jdk_internal_misc_StackChunk::end_address(chunk);
  for (intptr_t* sp = start + jdk_internal_misc_StackChunk::sp(chunk); sp < end; sp += cb->frame_size()) {
    address pc = *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET);
    cb = ContinuationCodeBlobLookup::find_blob(pc);
    frames++;
  }
  return frames;
}

void InstanceStackChunkKlass::fix_derived_pointers(const ImmutableOopMap* oopmap, intptr_t* sp, CodeBlob* cb) {
  for (OopMapStream oms(oopmap); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::derived_oop_value)
      continue;
    
    intptr_t* derived_loc = (intptr_t*)reg_to_loc(omv.reg(), sp);
    intptr_t* base_loc    = (intptr_t*)reg_to_loc(omv.content_reg(), sp); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1
    
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    if (base != (oop)NULL) {
      assert (!CompressedOops::is_base(base), "");
      ZGC_ONLY(assert (ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)

      OrderAccess::loadload();
      intptr_t offset = Atomic::load(derived_loc); // *derived_loc;
      if (offset >= 0)
        continue;

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
}

template void InstanceStackChunkKlass::barriers_for_oops_in_chunk<false>(oop chunk);
template void InstanceStackChunkKlass::barriers_for_oops_in_chunk<true> (oop chunk);
template void InstanceStackChunkKlass::barriers_for_oops_in_frame<false>(intptr_t* sp, CodeBlob* cb, const ImmutableOopMap* oopmap);
template void InstanceStackChunkKlass::barriers_for_oops_in_frame<true> (intptr_t* sp, CodeBlob* cb, const ImmutableOopMap* oopmap);

template <bool store>
void InstanceStackChunkKlass::barriers_for_oops_in_chunk(oop chunk) {
  CodeBlob* cb = NULL;
  intptr_t* const start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* const end   = jdk_internal_misc_StackChunk::end_address(chunk);
  for (intptr_t* sp = start + jdk_internal_misc_StackChunk::sp(chunk); sp < end; sp += cb->frame_size()) {
    address pc = *(address*)(sp - 1);
    int slot;
    cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
    const ImmutableOopMap* oopmap = cb->oop_map_for_slot(slot, pc);
    assert (oopmap != NULL, "");
    barriers_for_oops_in_frame<store>(sp, cb, oopmap);
  }
}

template <bool store>
void InstanceStackChunkKlass::barriers_for_oops_in_frame(intptr_t* sp, CodeBlob* cb, const ImmutableOopMap* oopmap) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned

  if (cb->is_nmethod()) {
    cb->as_nmethod_or_null()->run_nmethod_entry_barrier();
  }

  for (OopMapStream oms(oopmap); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;
    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");

    void* p = reg_to_loc(omv.reg(), sp);
    assert (p != NULL, "");
    assert (is_in_frame(cb, sp, p), "");

    const bool narrow = omv.type() == OopMapValue::narrowoop_value;
    oop value = narrow ? (oop)HeapAccess<>::oop_load((narrowOop*)p) : HeapAccess<>::oop_load((oop*)p);
    if (store) {
      narrow ? HeapAccess<>::oop_store((narrowOop*)p, value) : HeapAccess<>::oop_store((oop*)p, value);
    }
    log_develop_trace(jvmcont)("barriers_for_oops_in_frame narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", narrow, omv.reg()->name(), p2i(p), (intptr_t*)p - sp);
  }
}

static void fix_oops(const ImmutableOopMap* oopmap, intptr_t* sp, CodeBlob* cb) {
  for (OopMapStream oms(oopmap); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;

    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");

    void* p = reg_to_loc(omv.reg(), sp);
    assert (p != NULL, "");
    assert (is_in_frame(cb, sp, p), "");

    log_develop_trace(jvmcont)("fix_oops narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", omv.type() == OopMapValue::narrowoop_value, omv.reg()->name(), p2i(p), (intptr_t*)p - sp);
    // Does the barrier change the value, or do we need to write it back?
    // oop obj = omv.type() == OopMapValue::narrowoop_value ? (oop)NativeAccess<>::oop_load((narrowOop*)p) : (oop)NativeAccess<>::oop_load((oop*)p);
    if (omv.type() == OopMapValue::narrowoop_value) {
      *(narrowOop*)p = CompressedOops::encode((oop)NativeAccess<>::oop_load((narrowOop*)p));
    } else {
      *(oop*)p = (oop)NativeAccess<>::oop_load((oop*)p);
      ZGC_ONLY(assert (!UseZGC || ZAddress::is_good_or_null(cast_from_oop<uintptr_t>(*(oop*)p)), "");)
    }
  }
}

NOINLINE void InstanceStackChunkKlass::fix_chunk(oop chunk) {
  assert (jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");

  log_develop_trace(jvmcont)("fix_stack_chunk young: %d", !requires_barriers(chunk));
  bool narrow = UseCompressedOops; // TODO PERF: templatize

  int num_frames = 0;
  int num_oops = 0;
  CodeBlob* cb = NULL;

  intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* end   = jdk_internal_misc_StackChunk::end_address(chunk);
  start += jdk_internal_misc_StackChunk::sp(chunk);

  for (intptr_t* sp = start; sp < end; sp += cb->frame_size()) {
    address pc = *(address*)(sp - 1);
    log_develop_trace(jvmcont)("fix_stack_chunk sp: %ld pc: " INTPTR_FORMAT, sp - start, p2i(pc));
    assert (pc != NULL, "");

    int slot;
    cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
    assert (cb != NULL, "");
    if (log_develop_is_enabled(Trace, jvmcont)) cb->print_value_on(tty);
    assert (cb->is_nmethod(), "");
    assert (cb->frame_size() > 0, "");

    if (UNLIKELY(slot < 0)) { // we could have marked frames for deoptimization in thaw_chunk
      CompiledMethod* cm = cb->as_compiled_method();
      assert (cm->is_deopt_pc(pc), "");
      pc = *(address*)((address)sp + cm->orig_pc_offset());
      assert (cb == ContinuationCodeBlobLookup::find_blob(pc), "");
      ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
    }
    assert (slot >= 0, "");
    const ImmutableOopMap* oopmap = cb->oop_map_for_slot(slot, pc);
    assert (oopmap != NULL, "");
    log_develop_trace(jvmcont)("fix_stack_chunk slot: %d codeblob:", slot);

    num_frames++;
    num_oops += oopmap->num_oops();

    cb->as_compiled_method()->run_nmethod_entry_barrier();
    if (UseZGC) {
      iterate_derived_pointers<true>(chunk, oopmap, sp, cb);
      fix_oops(oopmap, sp, cb);
      OrderAccess::loadload();
    }
    fix_derived_pointers(oopmap, sp, cb);
  }
  OrderAccess::storestore();
  jdk_internal_misc_StackChunk::set_gc_mode(chunk, false);

  assert (num_frames >= 0, "");
  assert (num_oops >= 0, "");

  EventContinuationFix e;
  if (e.should_commit()) {
    e.set_id(cast_from_oop<u8>(chunk));
    e.set_numFrames((u2)num_frames);
    e.set_numOops((u2)num_oops);
    e.commit();
  }

  log_develop_trace(jvmcont)("fix_stack_chunk ------- end -------");
  // tty->print_cr("<<< fix_stack_chunk %p %p", (oopDesc*)chunk, Thread::current());
}

#ifdef ASSERT
bool InstanceStackChunkKlass::verify(oop chunk, oop cont, size_t* out_size, int* out_frames, int* out_oops) {  
  DEBUG_ONLY(if (!VerifyContinuations) return true;)
  assert (oopDesc::is_oop(chunk), "");
  log_develop_trace(jvmcont)("debug_verify_stack_chunk young: %d", !requires_barriers(chunk));
  assert (jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
  assert (jdk_internal_misc_StackChunk::size(chunk) >= 0, "");
  assert (jdk_internal_misc_StackChunk::argsize(chunk) >= 0, "");
  if (!SafepointSynchronize::is_at_safepoint()) {
    assert (oopDesc::is_oop_or_null(jdk_internal_misc_StackChunk::parent(chunk)), "");
  }

  bool check_deopt = false;
  if (Thread::current()->is_Java_thread() && !SafepointSynchronize::is_at_safepoint()) {
    if (Thread::current()->as_Java_thread()->cont_fastpath_thread_state())
      check_deopt = true;
  }

  const bool gc_mode = jdk_internal_misc_StackChunk::gc_mode(chunk);
  const bool concurrent = !SafepointSynchronize::is_at_safepoint() && !Thread::current()->is_Java_thread();
  const bool is_last = jdk_internal_misc_StackChunk::parent(chunk) == NULL && (cont == (oop)NULL || java_lang_Continuation::pc(cont) == NULL);
  // const bool narrow = UseCompressedOops;

  // if argsize == 0, the chunk contains the metadata (pc, fp -- frame::sender_sp_offset) for the top frame (below sp), and *not* for the bottom frame
  int size = jdk_internal_misc_StackChunk::size(chunk) - jdk_internal_misc_StackChunk::sp(chunk)
              - jdk_internal_misc_StackChunk::argsize(chunk)
              + ((jdk_internal_misc_StackChunk::argsize(chunk) == 0) ? frame::sender_sp_offset : 0);
  // if (cont != (oop)NULL && !is_last) size -= jdk_internal_misc_StackChunk::argsize(chunk);
  size <<= LogBytesPerWord;

  int num_frames = 0;
  int num_oops = 0;

  intptr_t* const start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* const end   = jdk_internal_misc_StackChunk::end_address(chunk);
  int size0 = 0;
  intptr_t* sp;
  CodeBlob* cb = NULL;
  for (sp = start + jdk_internal_misc_StackChunk::sp(chunk); sp < end; sp += cb->frame_size()) {
    // assert (!requires_barriers(chunk) || num_frames <= jdk_internal_misc_StackChunk::numFrames(chunk), "");
    address pc = *(address*)(sp - 1);
    log_develop_trace(jvmcont)("debug_verify_stack_chunk sp: %ld pc: " INTPTR_FORMAT, sp - start, p2i(pc));
    assert (pc != NULL, 
      "young: %d jdk_internal_misc_StackChunk::numFrames(chunk): %d num_frames: %d sp: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT, 
      !requires_barriers(chunk), jdk_internal_misc_StackChunk::numFrames(chunk), num_frames, p2i(sp), p2i(start), p2i(end));
    if (num_frames == 0) {
      assert (pc == jdk_internal_misc_StackChunk::pc(chunk), "");
    }

    int slot;
    cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc, slot);
    assert (cb != NULL, "");
    log_develop_trace(jvmcont)("debug_verify_stack_chunk slot: %d codeblob:", slot);
    if (log_develop_is_enabled(Trace, jvmcont)) cb->print_value_on(tty);
    assert (cb->is_compiled(), "");
    assert (cb->frame_size() > 0, "");
    assert (!cb->as_compiled_method()->is_deopt_pc(pc), "");

    // if (cb->is_nmethod()) {
    //   nmethod* nm = cb->as_nmethod();
    //   if (check_deopt && nm->is_marked_for_deoptimization() && nm->is_not_entrant()) {
    //     tty->print_cr("-- FOUND NON ENTRANT NMETHOD IN CHUNK: ");
    //     if (nm->method() != NULL) nm->method()->print_on(tty);
    //     nm->print_on(tty);
    //   }
    // }

    num_frames++;
    size0 += cb->frame_size() << LogBytesPerWord;

    assert (slot >= 0, "");
    const ImmutableOopMap* oopmap = cb->oop_map_for_slot(slot, pc);
    // if (slot >= 0) {
    //   oopmap = cb->oop_map_for_slot(slot, pc);
    // } else {
    //   CompiledMethod* cm = cb->as_compiled_method();
    //   assert (cm->is_deopt_pc(pc), "");
    //   pc = *(address*)((address)sp + cm->orig_pc_offset());
    //   oopmap = cb->oop_map_for_return_address(pc);
    // }

    assert (oopmap != NULL, "");
    assert (oopmap->num_oops() >= 0, "");

    // cb = CodeCache::find_blob(pc);
    // const ImmutableOopMap* oopmap = cb->oop_map_for_return_address(pc);

    num_oops += oopmap->num_oops();

    int oops = 0;
    for (OopMapStream oms(oopmap); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
      OopMapValue omv = oms.current();
      if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
        continue;

      oops++;
      void* p = reg_to_loc(omv.reg(), sp);
      assert (p != NULL, "");
      assert (is_in_frame(cb, sp, p), "reg: %s p: " INTPTR_FORMAT " sp: " INTPTR_FORMAT " size: %d argsize: %d", omv.reg()->name(), p2i(p), p2i(sp), cb->frame_size(), (cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord);
      assert ((intptr_t*)p >= start, "");
      if ((intptr_t*)p >= end) continue; // we could be walking the bottom frame's stack-passed args, belonging to the caller

      log_develop_trace(jvmcont)("debug_verify_stack_chunk narrow: %d reg: %d p: " INTPTR_FORMAT, omv.type() == OopMapValue::narrowoop_value, omv.reg()->is_reg(), p2i(p));
      assert (omv.type() == OopMapValue::oop_value || omv.type() == OopMapValue::narrowoop_value, "");
      assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");
      
      oop obj = omv.type() == OopMapValue::narrowoop_value ? (oop)HeapAccess<>::oop_load((narrowOop*)p) : (oop)HeapAccess<>::oop_load((oop*)p);
      if (!SafepointSynchronize::is_at_safepoint()) {
        assert (oopDesc::is_oop_or_null(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
      }
    }
    assert (oops == oopmap->num_oops(), "oops: %d oopmap->num_oops(): %d", oops, oopmap->num_oops());

    for (OopMapStream oms(oopmap); !oms.is_done(); oms.next()) {
      OopMapValue omv = oms.current();
      if (omv.type() != OopMapValue::derived_oop_value)
        continue;
      
      oop* derived_loc = (oop*)reg_to_loc(omv.reg(), sp);
      oop* base_loc    = (oop*)reg_to_loc(omv.content_reg(), sp); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1

      assert (base_loc != NULL, "");
      assert (is_in_frame(cb, sp, base_loc), "");
      assert (is_in_frame(cb, sp, derived_loc), "");
      assert (derived_loc != base_loc, "Base and derived in same location");
      assert (is_in_oops(oopmap, sp, base_loc), "not found: " INTPTR_FORMAT, p2i(base_loc));
      assert (!is_in_oops(oopmap, sp, derived_loc), "found: " INTPTR_FORMAT, p2i(derived_loc));
      log_develop_trace(jvmcont)("debug_verify_stack_chunk base: " INTPTR_FORMAT " derived: " INTPTR_FORMAT, p2i(base_loc), p2i(derived_loc));
      oop base = (oop)NativeAccess<>::oop_load((oop*)base_loc); // *(oop*)base_loc;
      if (base != (oop)NULL) {
        assert (!CompressedOops::is_base(base), "");
        assert (oopDesc::is_oop(base), "");
        ZGC_ONLY(assert (!UseZGC || ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)
        intptr_t offset = *(intptr_t*)derived_loc;
        offset = offset < 0
                   ? -offset
                   : offset - cast_from_oop<intptr_t>(base);
        assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "");
      } else {
        assert (*derived_loc == (oop)NULL, "");
      }
    }
  }

  if (cb != NULL) {
    assert (jdk_internal_misc_StackChunk::argsize(chunk) == (cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord, 
      "chunk argsize: %d bottom frame argsize: %d", jdk_internal_misc_StackChunk::argsize(chunk), (cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord);
  } else {
    assert (jdk_internal_misc_StackChunk::argsize(chunk) == 0, "");
  }

  if (cont == (oop)NULL || is_last) {
    if (cont != (oop)NULL) {
      assert (jdk_internal_misc_StackChunk::argsize(chunk) == 0, "");
    } else {
      // size0 += jdk_internal_misc_StackChunk::argsize(chunk) << LogBytesPerWord;
      sp    += cb != NULL ? ((cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord) : 0;
    }
  }
  if (!concurrent) {
    assert (size0 == size, "size: %d size0: %d end sp: %ld start sp: %d chunk size: %d argsize: %d", size, size0, sp - start, jdk_internal_misc_StackChunk::sp(chunk), jdk_internal_misc_StackChunk::size(chunk), jdk_internal_misc_StackChunk::argsize(chunk));
  }

  // if (!concurrent) {
  //   assert (jdk_internal_misc_StackChunk::numFrames(chunk) == -1 || num_frames == jdk_internal_misc_StackChunk::numFrames(chunk), "young: %d num_frames: %d jdk_internal_misc_StackChunk::numFrames(chunk): %d", !requires_barriers(chunk), num_frames, jdk_internal_misc_StackChunk::numFrames(chunk));
  //   assert (jdk_internal_misc_StackChunk::numOops(chunk)   == -1 || num_oops   == jdk_internal_misc_StackChunk::numOops(chunk),   "young: %d num_oops: %d jdk_internal_misc_StackChunk::numOops(chunk): %d",     !requires_barriers(chunk), num_oops,   jdk_internal_misc_StackChunk::numOops(chunk));
  // }

  if (out_size   != NULL) *out_size   += size;
  if (out_frames != NULL) *out_frames += num_frames;
  if (out_oops   != NULL) *out_oops   += num_oops;

  return true;
}
#endif
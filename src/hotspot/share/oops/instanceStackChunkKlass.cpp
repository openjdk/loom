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
#include "classfile/javaClasses.inline.hpp"
#include "classfile/systemDictionary.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "jfr/jfrEvents.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/oopFactory.hpp"
#include "memory/universe.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/instanceOop.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "runtime/continuation.hpp"
#include "utilities/macros.hpp"

#ifdef ASSERT
static bool requires_barriers(oop obj) {
  return Universe::heap()->requires_barriers(obj);
}
#endif

int InstanceStackChunkKlass::_offset_of_stack = 0;

#if INCLUDE_CDS
void InstanceStackChunkKlass::serialize_offsets(SerializeClosure* f) {
  f->do_u4((u4*)&_offset_of_stack);
}
#endif

InstanceStackChunkKlass::InstanceStackChunkKlass(const ClassFileParser& parser)
 : InstanceKlass(parser, InstanceKlass::_misc_kind_stack_chunk, ID) {
   // see oopDesc::size_given_klass TODO perf
   const jint lh = Klass::instance_layout_helper(size_helper(), true);
   set_layout_helper(lh);
   assert (layout_helper_is_instance(layout_helper()), "");
   assert (layout_helper_needs_slow_path(layout_helper()), "");
}

int InstanceStackChunkKlass::instance_size(int stack_size_in_words) const {
  return align_object_size(size_helper() + stack_size_in_words);
}

int InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_misc_StackChunk::size(obj));
}

int InstanceStackChunkKlass::compact_oop_size(oop obj) const {
  assert (jdk_internal_misc_StackChunk::is_stack_chunk(obj), "");
  // We therefore don't trim chunks with ZGC. See copy_compact
  if (UseZGC) return instance_size(jdk_internal_misc_StackChunk::size(obj));
  int used_stack_in_words = jdk_internal_misc_StackChunk::size(obj) - jdk_internal_misc_StackChunk::sp(obj) + metadata_words();
  assert (used_stack_in_words <= jdk_internal_misc_StackChunk::size(obj), "");
  return align_object_size(size_helper() + used_stack_in_words);
}

template size_t InstanceStackChunkKlass::copy_compact<false>(oop obj, HeapWord* to_addr);
template size_t InstanceStackChunkKlass::copy_compact<true>(oop obj, HeapWord* to_addr);

template<bool disjoint>
size_t InstanceStackChunkKlass::copy_compact(oop obj, HeapWord* to_addr) {
  assert (jdk_internal_misc_StackChunk::is_stack_chunk(obj), "");

  int from_sp = jdk_internal_misc_StackChunk::sp(obj);
  assert (from_sp >= metadata_words(), "");
  assert (obj->compact_size() <= obj->size(), "");
  assert (UseZGC || (from_sp <= metadata_words()) == (obj->compact_size() == obj->size()), "");

  // ZGC usually relocates objects into allocating regions that don't require barriers, so they keep/make the chunk mutable.
  // We therefore don't trim with ZGC.
  if (from_sp <= metadata_words() || UseZGC) {
    return disjoint ? obj->copy_disjoint(to_addr) : obj->copy_conjoint(to_addr);
  }

  // from_sp <= metadata_words()
  //   ? tty->print_cr(">>> InstanceStackChunkKlass::copy_compact disjoint: %d", disjoint)
  //   : tty->print_cr(">>> InstanceStackChunkKlass::copy_compact disjoint: %d size: %d compact_size: %d saved: %d", disjoint, obj->size(), obj->compact_size(), obj->size() - obj->compact_size());

#ifdef ASSERT
  int old_compact_size = obj->compact_size();
  int old_size = obj->size();
#endif

  int header = size_helper();

  int from_size = jdk_internal_misc_StackChunk::size(obj);
  assert (from_sp < from_size || from_sp == from_size + metadata_words(), "sp: %d size: %d", from_sp, from_size);
  int used_stack_in_words = from_size - from_sp + metadata_words();
  assert (used_stack_in_words >= 0, "");
  assert (used_stack_in_words > 0 || jdk_internal_misc_StackChunk::argsize(obj) == 0, "");
  
  // copy header
  HeapWord* from_addr = cast_from_oop<HeapWord*>(obj);
  disjoint ? Copy::aligned_disjoint_words(from_addr, to_addr, header)
           : Copy::aligned_conjoint_words(from_addr, to_addr, header);

  // update header
  jdk_internal_misc_StackChunk::set_size(to_addr, used_stack_in_words);
  jdk_internal_misc_StackChunk::set_sp(oop(to_addr), metadata_words());

  // copy stack
  if (used_stack_in_words > 0) {
    assert ((from_addr + header) == start_of_stack(obj), "");
    HeapWord* from_start = from_addr + header + from_sp - metadata_words();
    HeapWord* to_start = to_addr + header;
    disjoint ? Copy::aligned_disjoint_words(from_start, to_start, used_stack_in_words)
             : Copy::aligned_conjoint_words(from_start, to_start, used_stack_in_words);
  }
 
  assert (oop(to_addr)->size() == old_compact_size, "");
  assert (oop(to_addr)->size() == instance_size(used_stack_in_words), "");
  assert (from_sp <= metadata_words() || oop(to_addr)->size() < old_size, "");
  assert (verify(oop(to_addr)), "");

  // assert (Universe::heap()->requires_barriers(oop(to_addr))); // G1 sometimes compacts a young region and *then* turns it old ((G1CollectedHeap*)Universe::heap())->heap_region_containing(oop(to_addr))->print();
  
  return align_object_size(header + used_stack_in_words);
}

int InstanceStackChunkKlass::count_frames(oop chunk) {
  int frames = 0;
  for (StackChunkFrameStream f(chunk); !f.is_done(); f.next()) frames++;
  return frames;
}

#ifndef PRODUCT
void InstanceStackChunkKlass::oop_print_on(oop obj, outputStream* st) {
  InstanceKlass::oop_print_on(obj, st);
  print_chunk(obj, false, st);
}
#endif

#ifdef ASSERT
bool StackChunkFrameStream::is_in_oops(void* p) const {
  for (OopMapStream oms(oopmap()); !oms.is_done(); oms.next()) {
    if (oms.current().type() != OopMapValue::oop_value)
      continue;
    if (reg_to_loc(oms.current().reg()) == p)
      return true;
  }
  return false;
}
#endif

template void InstanceStackChunkKlass::iterate_derived_pointers<false>(oop chunk, const StackChunkFrameStream& f);
template void InstanceStackChunkKlass::iterate_derived_pointers<true> (oop chunk, const StackChunkFrameStream& f);

// We replace derived pointers with offsets; the converse is done in fix_stack_chunk
template <bool concurrent_gc>
void InstanceStackChunkKlass::iterate_derived_pointers(oop chunk, const StackChunkFrameStream& f) {
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

void InstanceStackChunkKlass::iterate_derived_pointers(oop chunk, const StackChunkFrameStream& f, MemRegion mr) {
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

void InstanceStackChunkKlass::fix_derived_pointers(const StackChunkFrameStream& f) {
  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) {
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::derived_oop_value)
      continue;
    
    intptr_t* derived_loc = (intptr_t*)f.reg_to_loc(omv.reg());
    intptr_t* base_loc    = (intptr_t*)f.reg_to_loc(omv.content_reg()); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1
    
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
template void InstanceStackChunkKlass::barriers_for_oops_in_frame<false>(const StackChunkFrameStream& f);
template void InstanceStackChunkKlass::barriers_for_oops_in_frame<true> (const StackChunkFrameStream& f);

template <bool store>
void InstanceStackChunkKlass::barriers_for_oops_in_chunk(oop chunk) {
  for (StackChunkFrameStream f(chunk); !f.is_done(); f.next()) {
    barriers_for_oops_in_frame<store>(f);
  }
}

template <bool store>
void InstanceStackChunkKlass::barriers_for_oops_in_frame(const StackChunkFrameStream& f) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned

  if (f.cb()->is_nmethod()) {
    f.cb()->as_nmethod_or_null()->run_nmethod_entry_barrier();
  }

  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;
    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");

    void* p = f.reg_to_loc(omv.reg());
    assert (p != NULL, "");
    assert (f.is_in_frame(p), "");

    const bool narrow = omv.type() == OopMapValue::narrowoop_value;
    oop value = narrow ? (oop)HeapAccess<>::oop_load((narrowOop*)p) : HeapAccess<>::oop_load((oop*)p);
    if (store) {
      narrow ? HeapAccess<>::oop_store((narrowOop*)p, value) : HeapAccess<>::oop_store((oop*)p, value);
    }
    log_develop_trace(jvmcont)("barriers_for_oops_in_frame narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", narrow, omv.reg()->name(), p2i(p), (intptr_t*)p - f.sp());
  }
}

static void fix_oops(const StackChunkFrameStream& f) {
  for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
    OopMapValue omv = oms.current();
    if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
      continue;

    assert (UseCompressedOops || omv.type() == OopMapValue::oop_value, "");

    void* p = f.reg_to_loc(omv.reg());
    assert (p != NULL, "");
    assert (f.is_in_frame(p), "");

    log_develop_trace(jvmcont)("fix_oops narrow: %d reg: %s p: " INTPTR_FORMAT " sp offset: %ld", omv.type() == OopMapValue::narrowoop_value, omv.reg()->name(), p2i(p), (intptr_t*)p - f.sp());
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

  for (StackChunkFrameStream f(chunk); !f.is_done(); f.next()) {
    log_develop_trace(jvmcont)("fix_stack_chunk sp: %ld pc: " INTPTR_FORMAT, f.sp() - jdk_internal_misc_StackChunk::start_address(chunk), p2i(f.pc()));

    if (log_develop_is_enabled(Trace, jvmcont)) f.cb()->print_value_on(tty);

    f.handle_deopted();

    num_frames++;
    num_oops += f.oopmap()->num_oops();

    f.cb()->as_compiled_method()->run_nmethod_entry_barrier();
    if (UseZGC) {
      iterate_derived_pointers<true>(chunk, f);
      fix_oops(f);
      OrderAccess::loadload();
    }
    fix_derived_pointers(f);
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
  intptr_t* sp;
  CodeBlob* cb = NULL;
  StackChunkFrameStream f(chunk);
  int size0 = 0;
  for (; !f.is_done(); f.next()) {
    sp = f.sp();
    cb = f.cb();

    // assert (!requires_barriers(chunk) || num_frames <= jdk_internal_misc_StackChunk::numFrames(chunk), "");
    log_develop_trace(jvmcont)("debug_verify_stack_chunk sp: %ld pc: " INTPTR_FORMAT, f.sp() - start, p2i(f.pc()));
    assert (f.pc() != NULL, 
      "young: %d jdk_internal_misc_StackChunk::numFrames(chunk): %d num_frames: %d sp: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT, 
      !requires_barriers(chunk), jdk_internal_misc_StackChunk::numFrames(chunk), num_frames, p2i(f.sp()), p2i(start), p2i(end));
    if (num_frames == 0) {
      assert (f.pc() == jdk_internal_misc_StackChunk::pc(chunk), "");
    }

    // if (cb->is_nmethod()) {
    //   nmethod* nm = cb->as_nmethod();
    //   if (check_deopt && nm->is_marked_for_deoptimization() && nm->is_not_entrant()) {
    //     tty->print_cr("-- FOUND NON ENTRANT NMETHOD IN CHUNK: ");
    //     if (nm->method() != NULL) nm->method()->print_on(tty);
    //     nm->print_on(tty);
    //   }
    // }

    num_frames++;
    size0 += f.cb()->frame_size() << LogBytesPerWord;

    assert (f.oopmap()->num_oops() >= 0, "");

    // cb = CodeCache::find_blob(pc);
    // const ImmutableOopMap* oopmap = cb->oop_map_for_return_address(pc);

    num_oops += f.oopmap()->num_oops();

    int oops = 0;
    for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) { // see void OopMapDo<OopFnT, DerivedOopFnT, ValueFilterT>::iterate_oops_do
      OopMapValue omv = oms.current();
      if (omv.type() != OopMapValue::oop_value && omv.type() != OopMapValue::narrowoop_value)
        continue;

      oops++;
      void* p = f.reg_to_loc(omv.reg());
      assert (p != NULL, "");
      assert (f.is_in_frame(p), "reg: %s p: " INTPTR_FORMAT " sp: " INTPTR_FORMAT " size: %d argsize: %d", omv.reg()->name(), p2i(p), p2i(f.sp()), f.cb()->frame_size(), (f.cb()->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord);
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
    assert (oops == f.oopmap()->num_oops(), "oops: %d oopmap->num_oops(): %d", oops, f.oopmap()->num_oops());

    for (OopMapStream oms(f.oopmap()); !oms.is_done(); oms.next()) {
      OopMapValue omv = oms.current();
      if (omv.type() != OopMapValue::derived_oop_value)
        continue;
      
      oop* derived_loc = (oop*)f.reg_to_loc(omv.reg());
      oop* base_loc    = (oop*)f.reg_to_loc(omv.content_reg()); // see OopMapDo<OopMapFnT, DerivedOopFnT, ValueFilterT>::walk_derived_pointers1

      assert (base_loc != NULL, "");
      assert (f.is_in_frame(base_loc), "");
      assert (f.is_in_frame(derived_loc), "");
      assert (derived_loc != base_loc, "Base and derived in same location");
      assert (f.is_in_oops(base_loc), "not found: " INTPTR_FORMAT, p2i(base_loc));
      assert (!f.is_in_oops(derived_loc), "found: " INTPTR_FORMAT, p2i(derived_loc));
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

  assert (jdk_internal_misc_StackChunk::is_empty(chunk) == (cb == NULL), "");
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


void InstanceStackChunkKlass::print_chunk(oop chunk, bool verbose, outputStream* st) {
  if (chunk == (oop)NULL) {
    st->print_cr("CHUNK NULL");
    return;
  }
  // tty->print_cr("CHUNK " INTPTR_FORMAT " ::", p2i((oopDesc*)chunk));
  assert(jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
  // HeapRegion* hr = G1CollectedHeap::heap()->heap_region_containing(chunk);
  st->print_cr("CHUNK " INTPTR_FORMAT " - " INTPTR_FORMAT " :: 0x%lx", p2i((oopDesc*)chunk), p2i((HeapWord*)(chunk + chunk->size())), chunk->identity_hash());
  st->print("CHUNK " INTPTR_FORMAT " young: %d gc_mode: %d, size: %d argsize: %d sp: %d num_frames: %d num_oops: %d parent: " INTPTR_FORMAT,
    p2i((oopDesc*)chunk), !Universe::heap()->requires_barriers(chunk), jdk_internal_misc_StackChunk::gc_mode(chunk),
    jdk_internal_misc_StackChunk::size(chunk), jdk_internal_misc_StackChunk::argsize(chunk), jdk_internal_misc_StackChunk::sp(chunk),
    jdk_internal_misc_StackChunk::numFrames(chunk), jdk_internal_misc_StackChunk::numOops(chunk),
    p2i((oopDesc*)jdk_internal_misc_StackChunk::parent(chunk)));

  intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* end   = jdk_internal_misc_StackChunk::end_address(chunk);

  if (verbose) {
    st->cr();
    st->print_cr("------ chunk frames end: " INTPTR_FORMAT, p2i(end));
    for (StackChunkFrameStream fs(chunk); !fs.is_done(); fs.next()) {
      frame f = fs.to_frame();
      st->print_cr("-- frame sp: " INTPTR_FORMAT " size: %d argsize: %d", p2i(fs.sp()), f.frame_size(), f.compiled_frame_stack_argsize());
      f.print_on(st);
      const ImmutableOopMap* oopmap = fs.oopmap();
      if (oopmap != NULL) {
        oopmap->print_on(st);
        st->cr();
      }
    }
    st->print_cr("------");
  } else {
    st->print_cr(" frames: %d", count_frames(chunk));
  }
}
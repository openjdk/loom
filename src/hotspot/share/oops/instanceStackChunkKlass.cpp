/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "compiler/oopMap.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "gc/shared/gc_globals.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/stackChunkOop.hpp"
#include "code/scopeDesc.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/systemDictionary.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "jfr/jfrEvents.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/oopFactory.hpp"
#include "memory/universe.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/instanceOop.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "runtime/continuation.hpp"
#include "runtime/globals.hpp"
#include "utilities/bitMap.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

int InstanceStackChunkKlass::_offset_of_stack = 0;

#if INCLUDE_CDS
void InstanceStackChunkKlass::serialize_offsets(SerializeClosure* f) {
  f->do_u4((u4*)&_offset_of_stack);
}
#endif

typedef void (*MemcpyFnT)(void* src, void* dst, size_t count);

void InstanceStackChunkKlass::default_memcpy(void* from, void* to, size_t size) {
  memcpy(to, from, size << LogBytesPerWord);
}

MemcpyFnT InstanceStackChunkKlass::memcpy_fn_from_stack_to_chunk = nullptr;
MemcpyFnT InstanceStackChunkKlass::memcpy_fn_from_chunk_to_stack = nullptr;

void InstanceStackChunkKlass::resolve_memcpy_functions() {
  if (!StubRoutines::has_word_memcpy() || UseNewCode) {
    // tty->print_cr(">> Config memcpy: default");
    memcpy_fn_from_stack_to_chunk = (MemcpyFnT)InstanceStackChunkKlass::default_memcpy;
    memcpy_fn_from_chunk_to_stack = (MemcpyFnT)InstanceStackChunkKlass::default_memcpy;
  } else {
    // tty->print_cr(">> Config memcpy: %s", UseContinuationStreamingCopy ? "NT" : "T");
    memcpy_fn_from_stack_to_chunk = UseContinuationStreamingCopy ? (MemcpyFnT)StubRoutines::word_memcpy_up_nt()
                                                                 : (MemcpyFnT)StubRoutines::word_memcpy_up();
    memcpy_fn_from_chunk_to_stack = UseContinuationStreamingCopy ? (MemcpyFnT)StubRoutines::word_memcpy_down_nt()
                                                                 : (MemcpyFnT)StubRoutines::word_memcpy_down();
  }
  assert (memcpy_fn_from_stack_to_chunk != nullptr, "");
  assert (memcpy_fn_from_chunk_to_stack != nullptr, "");
}

InstanceStackChunkKlass::InstanceStackChunkKlass(const ClassFileParser& parser)
 : InstanceKlass(parser, InstanceKlass::_misc_kind_stack_chunk, ID) {
   // see oopDesc::size_given_klass TODO perf
   const jint lh = Klass::instance_layout_helper(size_helper(), true);
   set_layout_helper(lh);
   assert (layout_helper_is_instance(layout_helper()), "");
   assert (layout_helper_needs_slow_path(layout_helper()), "");

  //  resolve_memcpy_functions(); -- too early here
}

int InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_misc_StackChunk::size(obj));
}

template <int x> NOINLINE static bool verify_chunk(stackChunkOop c) { return c->verify(); }

template <bool disjoint>
size_t InstanceStackChunkKlass::copy(oop obj, HeapWord* to_addr, size_t word_size) {
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;

  // pns2();
  // tty->print_cr(">>> CPY %s %p-%p (%zu) -> %p-%p (%zu) -- %d", disjoint ? "DIS" : "CON", cast_from_oop<HeapWord*>(obj), cast_from_oop<HeapWord*>(obj) + word_size, word_size, to_addr, to_addr + word_size, word_size, chunk->is_gc_mode());

  HeapWord* from_addr = cast_from_oop<HeapWord*>(obj);
  assert (from_addr != to_addr, "");
  disjoint ? Copy::aligned_disjoint_words(from_addr, to_addr, word_size)
           : Copy::aligned_conjoint_words(from_addr, to_addr, word_size);

  stackChunkOop to_chunk = (stackChunkOop) cast_to_oop(to_addr);
  assert (!to_chunk->has_bitmap()|| to_chunk->is_gc_mode(), "");
  if (!to_chunk->has_bitmap()) {
    build_bitmap(to_chunk);
  }

  return word_size;
}

template size_t InstanceStackChunkKlass::copy<false>(oop obj, HeapWord* to_addr, size_t word_size);
template size_t InstanceStackChunkKlass::copy<true>(oop obj, HeapWord* to_addr, size_t word_size);

int InstanceStackChunkKlass::compact_oop_size(oop obj) const {
  // tty->print_cr(">>>> InstanceStackChunkKlass::compact_oop_size");
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;
  // We don't trim chunks with ZGC. See copy_compact
  // if (UseZGC) return instance_size(chunk->stack_size());
  int used_stack_in_words = chunk->stack_size() - chunk->sp() + metadata_words();
  assert (used_stack_in_words <= chunk->stack_size(), "");
  return align_object_size(size_helper() + used_stack_in_words + bitmap_size(used_stack_in_words));
}

template size_t InstanceStackChunkKlass::copy_compact<false>(oop obj, HeapWord* to_addr);
template size_t InstanceStackChunkKlass::copy_compact<true>(oop obj, HeapWord* to_addr);

template <bool disjoint>
size_t InstanceStackChunkKlass::copy_compact(oop obj, HeapWord* to_addr) {
  // tty->print_cr(">>> InstanceStackChunkKlass::copy_compact from: %p to: %p", (oopDesc*)obj, to_addr);
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;

  assert (chunk->verify(), "");

#ifdef ASSERT
  int old_compact_size = obj->compact_size();
  int old_size = obj->size();
  assert (old_compact_size <= old_size, "");
#endif

  const int from_sp = chunk->sp();
  assert (from_sp >= metadata_words(), "");

  // ZGC usually relocates objects into allocating regions that don't require barriers, which keeps/makes the chunk mutable.
  if (from_sp <= metadata_words() /*|| UseZGC*/) {
    assert (oop_size(obj) == compact_oop_size(obj), "");
    return copy<disjoint>(obj, to_addr, oop_size(obj));
  }

  const int header = size_helper();
  const int from_stack_size = chunk->stack_size();
  const int to_stack_size = from_stack_size - from_sp + metadata_words();
  const int from_bitmap_size = bitmap_size(from_stack_size);
  const int to_bitmap_size = bitmap_size(to_stack_size);
  const bool has_bitmap = chunk->has_bitmap();
  assert (to_stack_size >= 0, "");
  assert (to_stack_size > 0 || chunk->argsize() == 0, "");
#ifdef ASSERT
  HeapWord* const start_of_stack0 = start_of_stack(obj);
  HeapWord* const start_of_bitmap0 = start_of_bitmap(obj);
#endif

  // pns2();
  // tty->print_cr(">>> CPY %s %p-%p (%d) -> %p-%p (%d) [%d] -- %d", disjoint ? "DIS" : "CON", cast_from_oop<HeapWord*>(obj), cast_from_oop<HeapWord*>(obj) + header + from_size, from_size, to_addr, to_addr + header + to_stack_size, to_stack_size, old_compact_size, chunk->is_gc_mode());

  stackChunkOop to_chunk = (stackChunkOop) cast_to_oop(to_addr);
  HeapWord* from_addr = cast_from_oop<HeapWord*>(obj);

  // copy header and stack in the appropriate order if conjoint; must not touch old chunk after we start b/c this can be a conjoint copy
  const int first  = (disjoint || to_addr <= from_addr) ? 0 : 2;
  const int stride = 1 - first;
  for (int i = first; 0 <= i && i <= 2; i += stride) {
    switch(i) { // copy header and update it
    case 0:
      // tty->print_cr(">>> CPY header %p-%p -> %p-%p (%d)", from_addr, from_addr + header , to_addr, to_addr + header, header);
      if (to_addr != from_addr) {
        disjoint ? Copy::aligned_disjoint_words(from_addr, to_addr, header)
                 : Copy::aligned_conjoint_words(from_addr, to_addr, header);
      }

      jdk_internal_misc_StackChunk::set_size(to_addr, to_stack_size);
      to_chunk->set_sp(metadata_words());
      break;
    case 1: // copy stack
      if (to_stack_size > 0) {
        assert ((from_addr + header) == start_of_stack0, "");
        HeapWord* from_start = from_addr + header + from_sp - metadata_words();
        HeapWord* to_start = to_addr + header;
        // tty->print_cr(">>> CPY stack  %p-%p -> %p-%p (%d)", from_start, from_start + to_stack_size , to_start, to_start + to_stack_size, to_stack_size);
        disjoint ? Copy::aligned_disjoint_words(from_start, to_start, to_stack_size)
                 : Copy::aligned_conjoint_words(from_start, to_start, to_stack_size);
      }
      break;
    case 2: // copy bitmap
      if (to_stack_size > 0 && has_bitmap) {
        assert ((from_addr + header + from_stack_size) == start_of_bitmap0, "");
        assert (from_bitmap_size >= to_bitmap_size, "");
        HeapWord* from_start = from_addr + header + from_stack_size + (from_bitmap_size - to_bitmap_size);
        HeapWord* to_start = to_addr + header + to_stack_size;
        // tty->print_cr(">>> CPY bitmap  %p-%p -> %p-%p (%d)", from_start, from_start + to_bitmap_size , to_start, to_start + to_bitmap_size, to_bitmap_size);
        disjoint ? Copy::aligned_disjoint_words(from_start, to_start, to_bitmap_size)
                 : Copy::aligned_conjoint_words(from_start, to_start, to_bitmap_size);
      }
      break;
    }
  }

  assert (!to_chunk->has_bitmap()|| to_chunk->is_gc_mode(), "");
  assert (to_chunk->has_bitmap() == has_bitmap, "");
  if (!to_chunk->has_bitmap()) {
    build_bitmap(to_chunk);
  }

  assert (to_chunk->size() == old_compact_size, "");
  assert (to_chunk->size() == instance_size(to_stack_size), "");
  assert (from_sp <= metadata_words() ?  (to_chunk->size() == old_size) : (to_chunk->size() < old_size), "");
  assert (to_chunk->verify(), "");

  // assert (to_chunk->requires_barriers(), ""); // G1 sometimes compacts a young region and *then* turns it old ((G1CollectedHeap*)Universe::heap())->heap_region_containing(oop(to_addr))->print();

  return align_object_size(header + to_stack_size + to_bitmap_size);
}

// template class StackChunkFrameStream<true>;
// template class StackChunkFrameStream<false>;

template <bool mixed>
int InstanceStackChunkKlass::count_frames(stackChunkOop chunk) {
  int frames = 0;
  for (StackChunkFrameStream<mixed> f(chunk); !f.is_done(); f.next(SmallRegisterMap::instance)) frames++;
  return frames;
}

#ifndef PRODUCT
void InstanceStackChunkKlass::oop_print_on(oop obj, outputStream* st) {
  // InstanceKlass::oop_print_on(obj, st);
  print_chunk((stackChunkOop)obj, false, st);
}
#endif


// We replace derived pointers with offsets; the converse is done in DerelativizeDerivedPointers
template <bool concurrent_gc>
class RelativizeDerivedPointers : public DerivedOopClosure {
public:
  RelativizeDerivedPointers() {}

  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
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

      // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
      // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
      intptr_t offset = derived_int_val - cast_from_oop<intptr_t>(base);
      // assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "offset: %ld size: %d", offset, (base->size() << LogHeapWordSize)); -- base might be invalid at this point
      Atomic::store((intptr_t*)derived_loc, -offset); // there could be a benign race here; we write a negative offset to let the sign bit signify it's an offset rather than an address
    } else {
      assert (*derived_loc == derived_pointer(0), "");
    }
  }
};

class DerelativizeDerivedPointers : public DerivedOopClosure {
public:
  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
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

template <bool store, bool compressedOopsWithBitmap>
class BarrierClosure: public OopClosure {
  DEBUG_ONLY(intptr_t* _sp;)
public:
  BarrierClosure(intptr_t* sp) DEBUG_ONLY(: _sp(sp)) {}

  virtual void do_oop(oop* p)       override { compressedOopsWithBitmap ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    oop value = (oop)HeapAccess<>::oop_load(p);
    if (store) HeapAccess<>::oop_store(p, value);
    log_develop_trace(jvmcont)("barriers_for_oops_in_frame narrow: %d p: " INTPTR_FORMAT " sp offset: %ld", sizeof(T) < sizeof(intptr_t), p2i(p), (intptr_t*)p - _sp);
  }

// virtual void do_oop(oop* p) override { *(oop*)p = (oop)NativeAccess<>::oop_load((oop*)p); }
// virtual void do_oop(narrowOop* p) override { *(narrowOop*)p = CompressedOops::encode((oop)NativeAccess<>::oop_load((narrowOop*)p)); }
};


template<typename OopClosureType>
class StackChunkOopIterateFilterClosure: public OopClosure {
private:
  OopClosureType* const _closure;
  stackChunkOop _chunk;
  MemRegion _bound;

public:
  StackChunkOopIterateFilterClosure(OopClosureType* closure, stackChunkOop chunk, MemRegion bound)
    : _closure(closure),
      _chunk(chunk),
      _bound(bound),
      _mutated(false),
      _num_oops(0) {}

  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  bool _mutated;
  int _num_oops;

  template <typename T>
  void do_oop_work(T* p) {
    if (_bound.contains(p)) {
      T before = *p;
      Devirtualizer::do_oop(_closure, p);
      _mutated |= before != *p;
      _num_oops++;
    }
  }
};

template <bool concurrent_gc, typename OopClosureType>
class OopOopIterateStackClosure {
  stackChunkOop _chunk;
  const bool _do_destructive_processing;
  OopClosureType * const _closure;
  MemRegion _bound;

public:
  int _num_frames, _num_oops;
  OopOopIterateStackClosure(stackChunkOop chunk, bool do_destructive_processing, OopClosureType* closure, MemRegion mr)
    : _chunk(chunk),
      _do_destructive_processing(do_destructive_processing),
      _closure(closure),
      _bound(mr),
      _num_frames(0),
      _num_oops(0) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    log_develop_trace(jvmcont)("stack_chunk_iterate_stack sp: %ld pc: " INTPTR_FORMAT, f.sp() - _chunk->start_address(), p2i(f.pc()));
    // if (Continuation::is_return_barrier_entry(f.pc())) {
    //   assert ((int)(f.sp() - chunk->start_address(chunk)) < chunk->sp(), ""); // only happens when starting from gcSP
    //   return;
    // }

    _num_frames++;
    assert (_closure != nullptr, "");

    assert (!f.is_deoptimized(), ""); // if (f.is_compiled()) f.handle_deopted();

    // For unload method debugging
    // tty->print_cr(">>>> OopOopIterateStackClosure::do_frame is_compiled: %d return_barrier: %d pc: %p", f.is_compiled(), Continuation::is_return_barrier_entry(f.pc()), f.pc()); f.print_on(tty);
    // if (f.is_compiled()) tty->print_cr(">>>> OopOopIterateStackClosure::do_frame nmethod: %p method: %p", f.cb()->as_nmethod(), f.cb()->as_compiled_method()->method());

    // if (log_develop_is_enabled(Trace, jvmcont)) cb->print_value_on(tty);

    if (Devirtualizer::do_metadata(_closure)) {
      if (f.is_interpreted()) {
        Method* im = f.to_frame().interpreter_frame_method();
        _closure->do_method(im);
      } else if (f.is_compiled()) {
        nmethod* nm = f.cb()->as_nmethod();
        // The do_nmethod function takes care of having the right synchronization
        // when keeping the nmethod alive during concurrent execution.
        _closure->do_nmethod(nm);
      }
    }

    if (_do_destructive_processing) { // evacuation always takes place at a safepoint; for concurrent iterations, we skip derived pointers, which is ok b/c coarse card marking is used for chunks
      assert (!f.is_compiled() || f.oopmap()->has_derived_oops() == f.oopmap()->has_any(OopMapValue::derived_oop_value), "");
      if (f.is_compiled() && f.oopmap()->has_derived_oops()) {
        if (concurrent_gc) {
          _chunk->set_gc_mode(true);
          OrderAccess::storestore();
        }
        InstanceStackChunkKlass::relativize_derived_pointers<concurrent_gc>(f, map);
        // OrderAccess::storestore();
      }
    }

    StackChunkOopIterateFilterClosure<OopClosureType> cl(_closure, _chunk, _bound);
    f.iterate_oops(&cl, map);
    bool mutated_oops = cl._mutated;
    _num_oops += cl._num_oops;// f.oopmap()->num_oops();

    // if (FIX_DERIVED_POINTERS && concurrent_gc && mutated_oops && _chunk->is_gc_mode()) { // TODO: this is a ZGC-specific optimization that depends on the one in iterate_derived_pointers
    //   InstanceStackChunkKlass::derelativize_derived_pointers(f, map);
    // }
    return true;
  }
};

template <bool concurrent_gc>
void InstanceStackChunkKlass::oop_oop_iterate_stack_slow(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr) {
  // oop_oop_iterate_stack_bounded<concurrent_gc, OopClosureType>(chunk, closure, MemRegion(nullptr, SIZE_MAX));
  assert (Continuation::debug_is_stack_chunk(chunk), "");
  log_develop_trace(jvmcont)("stack_chunk_iterate_stack requires_barriers: %d", !chunk->requires_barriers());

  bool do_destructive_processing; // should really be `= closure.is_destructive()`, if we had such a thing
  if (concurrent_gc) {
    do_destructive_processing = true;
  } else {
    if (SafepointSynchronize::is_at_safepoint() /*&& !chunk->is_gc_mode()*/) {
      do_destructive_processing = true;
      chunk->set_gc_mode(true);
    } else {
      do_destructive_processing = false;
    }
    assert (!SafepointSynchronize::is_at_safepoint() || chunk->is_gc_mode(), "gc_mode: %d is_at_safepoint: %d", chunk->is_gc_mode(), SafepointSynchronize::is_at_safepoint());
  }

  // tty->print_cr(">>>> OopOopIterateStackClosure::oop_oop_iterate_stack");
  OopOopIterateStackClosure<concurrent_gc, OopIterateClosure> frame_closure(chunk, do_destructive_processing, closure, mr);
  chunk->iterate_stack(&frame_closure);

  // if (FIX_DERIVED_POINTERS && concurrent_gc) {
  //   OrderAccess::storestore(); // to preserve that we set the offset *before* fixing the base oop
  //   chunk->set_gc_mode(false);
  // }

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

template void InstanceStackChunkKlass::oop_oop_iterate_stack_slow<false>(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr);
template void InstanceStackChunkKlass::oop_oop_iterate_stack_slow<true> (stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr);

class MarkMethodsStackClosure {
  OopIterateClosure* _closure;

public:
  MarkMethodsStackClosure(OopIterateClosure* cl) : _closure(cl) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    if (f.is_interpreted()) {
      Method* im = f.to_frame().interpreter_frame_method();
      _closure->do_method(im);
    } else if (f.is_compiled()) {
      nmethod* nm = f.cb()->as_nmethod();
      // The do_nmethod function takes care of having the right synchronization
      // when keeping the nmethod alive during concurrent execution.
      _closure->do_nmethod(nm);
    }
    return true;
  }
};

void InstanceStackChunkKlass::mark_methods(stackChunkOop chunk, OopIterateClosure* cl) {
  MarkMethodsStackClosure closure(cl);
  chunk->iterate_stack(&closure);
}

template <bool concurrent_gc, bool mixed, typename RegisterMapT>
void InstanceStackChunkKlass::relativize_derived_pointers(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
  RelativizeDerivedPointers<concurrent_gc> derived_closure;
  f.iterate_derived_pointers(&derived_closure, map);
}

template <bool mixed, typename RegisterMapT>
void InstanceStackChunkKlass::derelativize_derived_pointers(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
  DerelativizeDerivedPointers derived_closure;
  f.iterate_derived_pointers(&derived_closure, map);
}

template void InstanceStackChunkKlass::relativize_derived_pointers<false>(const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<true> (const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<false>(const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<true> (const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<false>(const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<true> (const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<false>(const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<true> (const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);


template <bool store, bool mixed, typename RegisterMapT>
void InstanceStackChunkKlass::do_barriers(stackChunkOop chunk, const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned
  if (f.is_done()) return;
  log_develop_trace(jvmcont)("InstanceStackChunkKlass::invoke_barriers sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(f.sp()), p2i(f.pc()));

  if (log_develop_is_enabled(Trace, jvmcont) && !mixed && f.is_interpreted()) f.cb()->print_value_on(tty);

  if (mixed) f.handle_deopted(); // we could freeze deopted frames in slow mode.

  if (f.is_interpreted()) {
    Method* m = f.to_frame().interpreter_frame_method();
    m->record_marking_cycle();
  } else if (f.is_compiled()) {
    nmethod* nm = f.cb()->as_nmethod();
    // The entry barrier takes care of having the right synchronization
    // when keeping the nmethod alive during concurrent execution.
    nm->run_nmethod_entry_barrier();
  }

  assert (!f.is_compiled() || f.oopmap()->has_derived_oops() == f.oopmap()->has_any(OopMapValue::derived_oop_value), "");
  bool has_derived = f.is_compiled() && f.oopmap()->has_derived_oops();
  if (has_derived) {
    if (UseZGC || UseShenandoahGC) {
      relativize_derived_pointers<true>(f, map);
    }
  }

  if (chunk->has_bitmap() && UseCompressedOops) {
    BarrierClosure<store, true> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  } else {
    BarrierClosure<store, false> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  }
  OrderAccess::loadload(); // observing the barriers will prevent derived pointers from being derelativized concurrently

  // if (has_derived) { // we do this in fix_thawed_frame
  //   derelativize_derived_pointers(f, map);
  // }
}

template void InstanceStackChunkKlass::do_barriers<false>(stackChunkOop chunk, const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<true> (stackChunkOop chunk, const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<false>(stackChunkOop chunk, const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<true> (stackChunkOop chunk, const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<false>(stackChunkOop chunk, const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<true> (stackChunkOop chunk, const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<false>(stackChunkOop chunk, const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers<true> (stackChunkOop chunk, const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);

template void InstanceStackChunkKlass::fix_thawed_frame(stackChunkOop chunk, const frame& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_thawed_frame(stackChunkOop chunk, const frame& f, const SmallRegisterMap* map);

template <bool store>
class DoBarriersStackClosure {
  const stackChunkOop _chunk;
public:
  DoBarriersStackClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    InstanceStackChunkKlass::do_barriers<store>(_chunk, f, map);
    return true;
  }
};

template void InstanceStackChunkKlass::do_barriers<false>(stackChunkOop chunk);
template void InstanceStackChunkKlass::do_barriers<true>(stackChunkOop chunk);

template <bool store>
void InstanceStackChunkKlass::do_barriers(stackChunkOop chunk) {
  DoBarriersStackClosure<store> closure(chunk);
  chunk->iterate_stack(&closure);
}

#ifdef ASSERT
template<class P>
static inline oop safe_load(P *addr) {
  oop obj = (oop)RawAccess<>::oop_load(addr);
  obj = (oop)NativeAccess<>::oop_load(&obj);
  return obj;
}

// Returns true iff the address p is readable and *(intptr_t*)p != errvalue
extern "C" bool dbg_is_safe(const void* p, intptr_t errvalue);
static bool is_good_oop(oop o) { return dbg_is_safe(o, -1) && dbg_is_safe(o->klass(), -1) && oopDesc::is_oop(o) && o->klass()->is_klass(); }
#endif

class FixCompressedOopClosure : public OopClosure {
  void do_oop(oop* p) override {
    assert (UseCompressedOops, "");
    oop obj = CompressedOops::decode(*(narrowOop*)p);
    assert (obj == nullptr || is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
    *p = obj;
  }

  void do_oop(narrowOop* p) override {}
};

template <bool compressedOops>
class BuildBitmapOopClosure : public OopClosure {
  intptr_t* const _stack_start;
  const BitMap::idx_t _bit_offset;
  BitMapView _bm;
public:
  BuildBitmapOopClosure(intptr_t* stack_start, BitMap::idx_t bit_offset, BitMapView bm) : _stack_start(stack_start), _bit_offset(bit_offset), _bm(bm) {}

  virtual void do_oop(oop* p) override {
    assert (p >= (oop*)_stack_start, "");
    if (compressedOops) {
      // Convert all oops to narrow before marking bit
      oop obj = *p;
      *p = nullptr;
      // assuming little endian
      *(narrowOop*)p = CompressedOops::encode(obj);
      do_oop((narrowOop*)p);
    } else {
      BitMap::idx_t index = _bit_offset + (p - (oop*)_stack_start);
      log_develop_trace(jvmcont)("Build bitmap wide oop p: " INTPTR_FORMAT " index: %lu bit_offset: %lu", p2i(p), index, _bit_offset);
      assert (!_bm.at(index), "");
      _bm.set_bit(index);
    }
  }

  virtual void do_oop(narrowOop* p) override {
    assert (p >= (narrowOop*)_stack_start, "");
    BitMap::idx_t index = _bit_offset + (p - (narrowOop*)_stack_start);
    log_develop_trace(jvmcont)("Build bitmap narrow oop p: " INTPTR_FORMAT " index: %lu bit_offset: %lu", p2i(p), index, _bit_offset);
    assert (!_bm.at(index), "");
    _bm.set_bit(index);
  }
};

template <bool compressedOops>
class BuildBitmapStackClosure {
  stackChunkOop _chunk;
  const BitMap::idx_t _bit_offset;
public:
  BuildBitmapStackClosure(stackChunkOop chunk) : _chunk(chunk), _bit_offset(chunk->bit_offset()) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    if (!_chunk->is_gc_mode() && f.is_compiled() && f.oopmap()->has_derived_oops()) {
      RelativizeDerivedPointers<false> derived_oops_closure;
      f.iterate_derived_pointers(&derived_oops_closure, map);
    }

    if (UseChunkBitmaps) {
      BuildBitmapOopClosure<compressedOops> oops_closure(_chunk->start_address(), _chunk->bit_offset(), _chunk->bitmap());
      f.iterate_oops(&oops_closure, map);
    }

    return true;
  }
};

void InstanceStackChunkKlass::build_bitmap(stackChunkOop chunk) {
  assert (!chunk->has_bitmap(), "");
  if (UseChunkBitmaps) {
    chunk->set_has_bitmap(true);
    BitMapView bm = chunk->bitmap();
    bm.clear();
  }

  if (UseCompressedOops) {
    BuildBitmapStackClosure<true> closure(chunk);
    chunk->iterate_stack(&closure);
  } else {
    BuildBitmapStackClosure<false> closure(chunk);
    chunk->iterate_stack(&closure);
  }

  chunk->set_gc_mode(true); // must be set *after* the above closure
}

// template <bool store>
// class BarriersIterateStackClosure {
// public:
//   template <bool mixed, typename RegisterMapT>
//   bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
//     InstanceStackChunkKlass::barriers_for_oops_in_frame<mixed, store>(f, map);
//     return true;
//   }
// };

// template <bool store>
// void InstanceStackChunkKlass::barriers_for_oops_in_chunk(stackChunkOop chunk) {
//   BarriersIterateStackClosure<store> frame_closure;
//   chunk->iterate_stack(&frame_closure);
// }

// NOINLINE void InstanceStackChunkKlass::fix_chunk(stackChunkOop chunk) {
//   log_develop_trace(jvmcont)("fix_stack_chunk young: %d", !chunk->requires_barriers());
//   FixChunkIterateStackClosure frame_closure(chunk);
//   chunk->iterate_stack(&frame_closure);
//   log_develop_trace(jvmcont)("fix_stack_chunk ------- end -------");
// }

template <typename RegisterMapT>
void InstanceStackChunkKlass::fix_thawed_frame(stackChunkOop chunk, const frame& f, const RegisterMapT* map) {
  if (chunk->has_bitmap() && UseCompressedOops) {
    FixCompressedOopClosure oop_closure;
    if (f.is_interpreted_frame()) {
      f.oops_interpreted_do(&oop_closure, nullptr);
    } else {
      OopMapDo<OopClosure, DerelativizeDerivedPointers, SkipNullValue> visitor(&oop_closure, nullptr);
      visitor.oops_do(&f, map, f.oop_map());
    }
  }

  if (f.is_compiled_frame() && f.oop_map()->has_derived_oops()) {
    DerelativizeDerivedPointers derived_closure;
    OopMapDo<OopClosure, DerelativizeDerivedPointers, SkipNullValue> visitor(nullptr, &derived_closure);
    visitor.oops_do(&f, map, f.oop_map());
  }
}

#ifdef ASSERT

template <typename OopT>
class StackChunkVerifyBitmapClosure : public BitMapClosure {
  stackChunkOop _chunk;
  intptr_t* _top;
  intptr_t* _next;
public:
  int _count;
  bool _exact; // whether or not the top, and therefore the count, is exact
  StackChunkVerifyBitmapClosure(stackChunkOop chunk) : _chunk(chunk), _count(0) {
    find_frame_top(StackChunkFrameStream<true>(chunk));
    log_develop_trace(jvmcont)("debug_verify_stack_chunk bitmap stack top: " INTPTR_FORMAT, p2i(_top));
  }

  bool do_bit(BitMap::idx_t index) override {
    OopT* p = _chunk->address_for_bit<OopT>(index);
    if ((intptr_t*)p < _top && (intptr_t*)p != _chunk->sp_address() - frame::sender_sp_offset) return true; // skip oops that are not seen by the oopmap scan

    log_develop_trace(jvmcont)("debug_verify_stack_chunk bitmap oop p: " INTPTR_FORMAT " index: %lu bit_offset: %lu", p2i(p), index, _chunk->bit_offset());
    _count++;
    if (SafepointSynchronize::is_at_safepoint()) return true;

    oop obj = safe_load(p);
    assert ((!_exact && (intptr_t*)p < _next) || obj == nullptr || is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT " index: %lu bit_offset: %lu", p2i(p), p2i((oopDesc*)obj), index, _chunk->bit_offset());
    return true; // continue processing
  }

  void find_frame_top(const StackChunkFrameStream<true>& f) {
    // We do this just so that the count is the same as the oopmap scan for verification purposes, but for GC traveral it's not important.
    _next = nullptr;
    _exact = true;
    if (f.is_interpreted()) {
      ResourceMark rm;
      InterpreterOopMap mask;
      frame fr = f.to_frame();
      fr.interpreted_frame_oop_map(&mask);
      _top = fr.addr_at(frame::interpreter_frame_initial_sp_offset) - mask.expression_stack_size();
    } else if (f.is_compiled()) {
      Method* callee = f.cb()->as_compiled_method()->attached_method_before_pc(f.pc());
      if (callee != nullptr) {
        int outgoing_args_words = (callee->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord;
        _top = f.unextended_sp() + outgoing_args_words;
      } else {
        _top = f.unextended_sp();
        _next = _top + f.cb()->frame_size() - frame::sender_sp_offset;
        _exact = false;
      }
    } else {
      _top = f.unextended_sp();
    }
  }
};

class StackChunkVerifyOopsClosure : public OopClosure {
  stackChunkOop _chunk;
  intptr_t* _sp;
  int _count;
public:
  StackChunkVerifyOopsClosure(stackChunkOop chunk, intptr_t* sp) : _chunk(chunk), _sp(sp), _count(0) {}
  int count() { return _count; }
  void do_oop(oop* p) override { (_chunk->has_bitmap() && UseCompressedOops) ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    log_develop_trace(jvmcont)("debug_verify_stack_chunk oop narrow: %d p: " INTPTR_FORMAT, sizeof(T) < sizeof(intptr_t), p2i(p));
     _count++;
    if (SafepointSynchronize::is_at_safepoint()) return;

    oop obj = safe_load(p);
    assert (obj == nullptr || is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
    if (_chunk->has_bitmap()) {
      BitMap::idx_t index = (p - (T*)_chunk->start_address()) + _chunk->bit_offset();
      assert (_chunk->bitmap().at(index), "Bit not set at index %lu corresponding to " INTPTR_FORMAT, index, p2i(p));
    }
  }
};

class StackChunkVerifyDerivedPointersClosure : public DerivedOopClosure {
  stackChunkOop _chunk;
public:
  StackChunkVerifyDerivedPointersClosure(stackChunkOop chunk) : _chunk(chunk) {}

  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
    log_develop_trace(jvmcont)("debug_verify_stack_chunk base: " INTPTR_FORMAT " derived: " INTPTR_FORMAT, p2i(base_loc), p2i(derived_loc));
    if (SafepointSynchronize::is_at_safepoint()) return;

    oop base = (_chunk->has_bitmap() && UseCompressedOops) ? CompressedOops::decode(Atomic::load((narrowOop*)base_loc)) : Atomic::load((oop*)base_loc);
    // (oop)NativeAccess<>::oop_load((oop*)base_loc); //
    if (base != nullptr) {
      ZGC_ONLY(if (UseZGC && !ZAddress::is_good(cast_from_oop<uintptr_t>(base))) return;)
      assert (!CompressedOops::is_base(base), "");
      assert (oopDesc::is_oop(base), "");
      ZGC_ONLY(assert (!UseZGC || ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)
      OrderAccess::loadload();
      intptr_t offset = Atomic::load((intptr_t*)derived_loc);
      offset = offset < 0
                  ? -offset
                  : offset - cast_from_oop<intptr_t>(base);

      // The following assert fails on AArch64 for some reason
      // assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "offset: %ld base->size: %d relative: %d", offset, base->size() << LogHeapWordSize, *(intptr_t*)derived_loc < 0);
    } else {
      assert (*derived_loc == derived_pointer(0), "");
    }
  }
};

class VerifyStackClosure {
  stackChunkOop _chunk;
public:
  intptr_t* _sp;
  CodeBlob* _cb;
  bool _callee_interpreted;
  int _size;
  int _argsize;
  int _num_oops, _num_frames, _num_interpreted_frames, _num_i2c;
  VerifyStackClosure(stackChunkOop chunk, int num_frames, int size)
    : _chunk(chunk), _sp(nullptr), _cb(nullptr), _callee_interpreted(false),
      _size(size), _argsize(0), _num_oops(0), _num_frames(num_frames), _num_interpreted_frames(0), _num_i2c(0) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    _sp = f.sp();
    _cb = f.cb();

    int fsize = f.frame_size() - ((f.is_interpreted() == _callee_interpreted) ? _argsize : 0);
    int num_oops = f.num_oops();
    assert (num_oops >= 0, "");
    // tty->print_cr(">>> fsize: %d f.frame_size(): %d callee_interpreted: %d callee_argsize: %d", fsize, f.frame_size(), _callee_interpreted, _argsize);

    _argsize   = f.stack_argsize();
    _size     += fsize;
    _num_oops += num_oops;
    if (f.is_interpreted()) {
      _num_interpreted_frames++;
    }

    // assert (!chunk->requires_barriers() || num_frames <= chunk->numFrames(), "");
    log_develop_trace(jvmcont)("debug_verify_stack_chunk frame: %d sp: %ld pc: " INTPTR_FORMAT " interpreted: %d size: %d argsize: %d oops: %d", _num_frames, f.sp() - _chunk->start_address(), p2i(f.pc()), f.is_interpreted(), fsize, _argsize, num_oops);
    if (log_develop_is_enabled(Trace, jvmcont)) f.print_on(tty);
    assert (f.pc() != nullptr,
      "young: %d chunk->numFrames(): %d num_frames: %d sp: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT,
      !_chunk->requires_barriers(), _chunk->numFrames(), _num_frames, p2i(f.sp()), p2i(_chunk->start_address()), p2i(_chunk->bottom_address()));

    if (_num_frames == 0) {
      assert (f.pc() == _chunk->pc(), "");
    }

    if (_num_frames > 0 && !_callee_interpreted && f.is_interpreted()) {
      log_develop_trace(jvmcont)("debug_verify_stack_chunk i2c");
      _num_i2c++;
    }

    // if (_cb != nullptr && _cb->is_nmethod()) {
    //   nmethod* nm = cb->as_nmethod();
    //   if (check_deopt && nm->is_marked_for_deoptimization() && nm->is_not_entrant()) {
    //     tty->print_cr("-- FOUND NON ENTRANT NMETHOD IN CHUNK: ");
    //     if (nm->method() != nullptr) nm->method()->print_on(tty);
    //     nm->print_on(tty);
    //   }
    // }

    StackChunkVerifyOopsClosure oops_closure(_chunk, f.sp());
    f.iterate_oops(&oops_closure, map);
    assert (oops_closure.count() == num_oops, "oops: %d oopmap->num_oops(): %d", oops_closure.count(), num_oops);

    StackChunkVerifyDerivedPointersClosure derived_oops_closure(_chunk);
    f.iterate_derived_pointers(&derived_oops_closure, map);

    _callee_interpreted = f.is_interpreted();
    _num_frames++;
    return true;
  }
};

// verifies the consistency of the chunk's data
bool InstanceStackChunkKlass::verify(oop obj, size_t* out_size, int* out_oops, int* out_frames, int* out_interpreted_frames) {
  DEBUG_ONLY(if (!VerifyContinuations) return true;)

  assert (oopDesc::is_oop(obj), "");
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;

  log_develop_trace(jvmcont)("debug_verify_stack_chunk barriers: %d", chunk->requires_barriers());
  // chunk->print_on(true, tty);

  assert (chunk->is_stackChunk(), "");
  assert (chunk->stack_size() >= 0, "");
  assert (chunk->argsize() >= 0, "");
  assert (!chunk->has_bitmap() || chunk->is_gc_mode(), "");

  if (chunk->is_empty()) {
    assert (chunk->argsize() == 0, "");
    assert (chunk->max_size() == 0, "");
    assert (chunk->numFrames() == -1, "");
    assert (chunk->numOops() == -1, "");
  }

  if (!SafepointSynchronize::is_at_safepoint()) {
    assert (oopDesc::is_oop_or_null(chunk->parent()), "");
  }

  bool check_deopt = false;
  if (Thread::current()->is_Java_thread() && !SafepointSynchronize::is_at_safepoint()) {
    if (JavaThread::cast(Thread::current())->cont_fastpath_thread_state())
      check_deopt = true;
  }

  const bool concurrent = !SafepointSynchronize::is_at_safepoint() && !Thread::current()->is_Java_thread();
  const bool gc_mode = chunk->is_gc_mode();
  const bool is_last = chunk->parent() == nullptr;
  const bool mixed = chunk->has_mixed_frames();

  // if argsize == 0 and the chunk isn't mixed, the chunk contains the metadata (pc, fp -- frame::sender_sp_offset) for the top frame (below sp), and *not* for the bottom frame
  int size = chunk->stack_size() - chunk->argsize() - chunk->sp();
  // tty->print_cr("size: %d chunk->stack_size(): %d chunk->sp(): %d chunk->argsize(): %d", size, chunk->stack_size(), chunk->sp(), chunk->argsize());
  assert (size >= 0, "");
  assert ((size == 0) == chunk->is_empty(), "");

  const StackChunkFrameStream<true> first(chunk);
  const bool has_safepoint_stub_frame = first.is_stub();

  VerifyStackClosure closure(chunk,
    has_safepoint_stub_frame ? 1 : 0, // iterate_stack skips the safepoint stub
    has_safepoint_stub_frame ? first.frame_size() : 0);
  chunk->iterate_stack(&closure);

  assert (!chunk->is_empty() || closure._cb == nullptr, "");
  if (closure._cb != nullptr && closure._cb->is_compiled()) {
    assert (chunk->argsize() == (closure._cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord,
      "chunk argsize: %d bottom frame argsize: %d", chunk->argsize(), (closure._cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord);
  } 
  // else {
  //   assert (chunk->argsize() == 0, "");
  // }
  assert (closure._num_interpreted_frames == 0 || chunk->has_mixed_frames(), "");

  if (!concurrent) {
    assert (closure._size <= size + chunk->argsize() + metadata_words(), "size: %d argsize: %d closure.size: %d end sp: %ld start sp: %d chunk size: %d", size, chunk->argsize(), closure._size, closure._sp - chunk->start_address(), chunk->sp(), chunk->stack_size());
    assert (chunk->argsize() == closure._argsize, "chunk->argsize(): %d closure.argsize: %d closure.callee_interpreted: %d", chunk->argsize(), closure._argsize, closure._callee_interpreted);

    int max_size = closure._size + closure._num_i2c * align_wiggle();
    assert (chunk->max_size() == max_size, "max_size(): %d max_size: %d argsize: %d num_i2c: %d", chunk->max_size(), max_size, closure._argsize, closure._num_i2c);

    assert (chunk->numFrames() == -1 || closure._num_frames == chunk->numFrames(), "barriers: %d num_frames: %d chunk->numFrames(): %d", chunk->requires_barriers(), closure._num_frames, chunk->numFrames());
    assert (chunk->numOops()   == -1 || closure._num_oops   == chunk->numOops(),   "barriers: %d num_oops: %d chunk->numOops(): %d",     chunk->requires_barriers(), closure._num_oops,   chunk->numOops());

    if (out_size   != nullptr) *out_size   += size;
    if (out_oops   != nullptr) *out_oops   += closure._num_oops;
    if (out_frames != nullptr) *out_frames += closure._num_frames;
    if (out_interpreted_frames != nullptr) *out_interpreted_frames += closure._num_interpreted_frames;
  } else assert (out_size == nullptr && out_oops == nullptr && out_frames == nullptr && out_interpreted_frames == nullptr, "");

  if (chunk->has_bitmap()) {
    assert (chunk->bitmap().size() == chunk->bit_offset() + (size_t)(chunk->stack_size() << (UseCompressedOops ? 1 : 0)), "bitmap().size(): %zu bit_offset: %zu stack_size: %d", chunk->bitmap().size(), chunk->bit_offset(), chunk->stack_size());
    bool exact;
    int oop_count;
    if (UseCompressedOops) {
      StackChunkVerifyBitmapClosure<narrowOop> bitmap_closure(chunk);
      chunk->bitmap().iterate(&bitmap_closure, chunk->bit_index_for((narrowOop*)(chunk->sp_address() - metadata_words())), chunk->bit_index_for((narrowOop*)chunk->end_address()));
      exact = bitmap_closure._exact;
      oop_count = bitmap_closure._count;
    } else {
      StackChunkVerifyBitmapClosure<oop> bitmap_closure(chunk);
      chunk->bitmap().iterate(&bitmap_closure, chunk->bit_index_for((oop*)(chunk->sp_address() - metadata_words())), chunk->bit_index_for((oop*)chunk->end_address()));
      exact = bitmap_closure._exact;
      oop_count = bitmap_closure._count;
    }
    assert (exact ? oop_count == closure._num_oops : oop_count >= closure._num_oops, "bitmap_closure._count: %d closure._num_oops: %d", oop_count, closure._num_oops);
  }

  return true;
}

namespace {
class DescribeStackChunkClosure {
  stackChunkOop _chunk;
  FrameValues _values;
  RegisterMap _map;
  int _frame_no;
public:
  DescribeStackChunkClosure(stackChunkOop chunk) : _chunk(chunk), _map((JavaThread*)nullptr, true, false, true), _frame_no(0) {
    _map.set_include_argument_oops(false);
  }

  const RegisterMap* get_map(const RegisterMap* map,      intptr_t* sp) { return map; }
  const RegisterMap* get_map(const SmallRegisterMap* map, intptr_t* sp) { return map->copy_to_RegisterMap(&_map, sp); }

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
    ResetNoHandleMark rnhm;
    HandleMark hm(Thread::current());

    frame fr = f.to_frame();
    if (_frame_no == 0) fr.describe_top(_values);
    fr.template describe<true>(_values, _frame_no++, get_map(map, f.sp()));
    return true;
  }

  void describe_chunk() {
    // _values.describe(-1, _chunk->start_address(), "CHUNK START");
    _values.describe(-1, _chunk->sp_address(),      "CHUNK SP");
    _values.describe(-1, _chunk->bottom_address() - 1, "CHUNK ARGS");
    _values.describe(-1, _chunk->end_address() - 1, "CHUNK END");
  }

  void print_on(outputStream* out) {
    if (_frame_no > 0) {
      describe_chunk();
      _values.print_on(_chunk, out);
    } else {
      out->print_cr(" EMPTY");
    }
  }
};
}
#endif

namespace {
class PrintStackChunkClosure {
  stackChunkOop _chunk;
  outputStream* _st;
public:
  PrintStackChunkClosure(stackChunkOop chunk, outputStream* st) : _chunk(chunk), _st(st) {}

  template <bool mixed, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<mixed>& fs, const RegisterMapT* map) {
    frame f = fs.to_frame();
    _st->print_cr("-- frame sp: " INTPTR_FORMAT " interpreted: %d size: %d argsize: %d", p2i(fs.sp()), fs.is_interpreted(), f.frame_size(), fs.is_interpreted() ? 0 : f.compiled_frame_stack_argsize());
    f.print_on<true>(_st);
    const ImmutableOopMap* oopmap = fs.oopmap();
    if (oopmap != nullptr) {
      oopmap->print_on(_st);
      _st->cr();
    }
    return true;
  }
};
}

void InstanceStackChunkKlass::print_chunk(const stackChunkOop c, bool verbose, outputStream* st) {
  if (c == (oop)nullptr) {
    st->print_cr("CHUNK NULL");
    return;
  }
  assert(c->is_stackChunk(), "");

  // HeapRegion* hr = G1CollectedHeap::heap()->heap_region_containing(chunk);
  st->print_cr("CHUNK " INTPTR_FORMAT " " INTPTR_FORMAT " - " INTPTR_FORMAT " :: 0x%lx", p2i((oopDesc*)c), p2i(c->start_address()), p2i(c->end_address()), c->identity_hash());
  st->print_cr("       barriers: %d gc_mode: %d bitmap: %d parent: " INTPTR_FORMAT, c->requires_barriers(), c->is_gc_mode(), c->has_bitmap(), p2i((oopDesc*)c->parent()));
  st->print_cr("       flags mixed: %d", c->has_mixed_frames());
  st->print_cr("       size: %d argsize: %d max_size: %d sp: %d pc: " INTPTR_FORMAT " num_frames: %d num_oops: %d", c->stack_size(), c->argsize(), c->max_size(), c->sp(), p2i(c->pc()), c->numFrames(), c->numOops());

  if (verbose) {
    st->cr();
    st->print_cr("------ chunk frames end: " INTPTR_FORMAT, p2i(c->bottom_address()));
    PrintStackChunkClosure closure(c, st);
    c->iterate_stack(&closure);
    st->print_cr("------");

  #ifdef ASSERT
    ResourceMark rm;
    DescribeStackChunkClosure describe(c);
    c->iterate_stack(&describe);
    describe.print_on(st);
    st->print_cr("======");
  #endif
  }
}

#ifndef PRODUCT
template void StackChunkFrameStream<true >::print_on(outputStream* st) const;
template void StackChunkFrameStream<false>::print_on(outputStream* st) const;

template <bool mixed>
void StackChunkFrameStream<mixed>::print_on(outputStream* st) const {
  st->print_cr("chunk: " INTPTR_FORMAT " index: %d sp offset: %d stack size: %d", p2i(_chunk), _index, _chunk->to_offset(_sp), _chunk->stack_size());
  to_frame().template print_on<true>(st);
}
#endif

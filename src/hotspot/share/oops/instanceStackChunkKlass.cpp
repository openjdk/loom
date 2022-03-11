/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "compiler/compiler_globals.hpp"
#include "compiler/oopMap.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
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

InstanceStackChunkKlass::InstanceStackChunkKlass(const ClassFileParser& parser)
 : InstanceKlass(parser, InstanceKlass::_misc_kind_stack_chunk, ID) {
  // see oopDesc::size_given_klass
   const jint lh = Klass::instance_layout_helper(size_helper(), true);
   set_layout_helper(lh);
   assert (layout_helper_is_instance(layout_helper()), "");
   assert (layout_helper_needs_slow_path(layout_helper()), "");
}

size_t InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_vm_StackChunk::size(obj));
}

template <int x> NOINLINE static bool verify_chunk(stackChunkOop c) { return c->verify(); }

template <InstanceStackChunkKlass::copy_type overlap>
size_t InstanceStackChunkKlass::copy(oop obj, HeapWord* to_addr, size_t word_size) {
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;

  HeapWord* from_addr = cast_from_oop<HeapWord*>(obj);
  overlap == copy_type::DISJOINT ? Copy::aligned_disjoint_words(from_addr, to_addr, word_size)
                                 : Copy::aligned_conjoint_words(from_addr, to_addr, word_size);

  stackChunkOop to_chunk = (stackChunkOop) cast_to_oop(to_addr);
  assert (!to_chunk->has_bitmap() || to_chunk->is_gc_mode(), "");

  if (!to_chunk->has_bitmap()) {
    build_bitmap(to_chunk);
  }

  return word_size;
}

template size_t InstanceStackChunkKlass::copy<InstanceStackChunkKlass::copy_type::CONJOINT>(oop obj, HeapWord* to_addr, size_t word_size);
template size_t InstanceStackChunkKlass::copy<InstanceStackChunkKlass::copy_type::DISJOINT>(oop obj, HeapWord* to_addr, size_t word_size);

template <chunk_frames frame_kind>
int InstanceStackChunkKlass::count_frames(stackChunkOop chunk) {
  int frames = 0;
  for (StackChunkFrameStream<frame_kind> f(chunk); !f.is_done(); f.next(SmallRegisterMap::instance)) frames++;
  return frames;
}

#ifndef PRODUCT
void InstanceStackChunkKlass::oop_print_on(oop obj, outputStream* st) {
  print_chunk((stackChunkOop)obj, false, st);
}
#endif


// We replace derived pointers with offsets; the converse is done in DerelativizeDerivedPointers
template <gc_type gc>
class RelativizeDerivedPointers : public DerivedOopClosure {
public:
  RelativizeDerivedPointers() {}

  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    if (base == (oop)nullptr) {
      assert (*derived_loc == derived_pointer(0), "");
      return;
    }
    assert (!CompressedOops::is_base(base), "");

#if INCLUDE_ZGC
    if (gc == gc_type::CONCURRENT && UseZGC) {
      if (ZAddress::is_good(cast_from_oop<uintptr_t>(base))) {
        return;
      }
    }
#endif
#if INCLUDE_SHENANDOAHGC
    if (gc == gc_type::CONCURRENT && UseShenandoahGC) {
      if (!ShenandoahHeap::heap()->in_collection_set(base)) {
        return;
      }
    }
#endif

    OrderAccess::loadload();
    intptr_t derived_int_val = Atomic::load((intptr_t*)derived_loc);
    if (derived_int_val <= 0) {
      return;
    }

    // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
    // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
    intptr_t offset = derived_int_val - cast_from_oop<intptr_t>(base);
    assert (offset >= 0, "");
    Atomic::store((intptr_t*)derived_loc, -offset);
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

      // at this point, we've seen a non-offset value *after* we've read the base, but we write the offset *before* fixing the base,
      // so we are guaranteed that the value in derived_loc is consistent with base (i.e. points into the object).
      if (offset <= 0) {
        offset = -offset;
        Atomic::store((intptr_t*)derived_loc, cast_from_oop<intptr_t>(base) + offset);
      }
    }
  }
};

template <InstanceStackChunkKlass::barrier_type barrier, bool compressedOopsWithBitmap>
class BarrierClosure: public OopClosure {
  NOT_PRODUCT(intptr_t* _sp;)
public:
  BarrierClosure(intptr_t* sp) NOT_PRODUCT(: _sp(sp)) {}

  virtual void do_oop(oop* p)       override { compressedOopsWithBitmap ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    oop value = (oop)HeapAccess<>::oop_load(p);
    if (barrier == InstanceStackChunkKlass::barrier_type::STORE) HeapAccess<>::oop_store(p, value);
  }
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
      _num_oops(0) {}

  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  int _num_oops;

  template <typename T>
  void do_oop_work(T* p) {
    if (_bound.contains(p)) {
      T before = *p;
      Devirtualizer::do_oop(_closure, p);
      _num_oops++;
    }
  }
};

template <gc_type gc>
class OopOopIterateStackClosure {
  stackChunkOop _chunk;
  OopIterateClosure* const _closure;
  MemRegion _bound;

public:
  int _num_frames, _num_oops;
  OopOopIterateStackClosure(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr)
    : _chunk(chunk),
      _closure(closure),
      _bound(mr),
      _num_frames(0),
      _num_oops(0) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    _num_frames++;
    assert (_closure != nullptr, "");

    if (_closure->do_metadata()) {
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

    StackChunkOopIterateFilterClosure<OopIterateClosure> cl(_closure, _chunk, _bound);
    f.iterate_oops(&cl, map);
    _num_oops += cl._num_oops;

    return true;
  }
};

template <gc_type gc>
void InstanceStackChunkKlass::oop_oop_iterate_stack_slow(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr) {
  assert (chunk->is_stackChunk(), "");

  OopOopIterateStackClosure<gc> frame_closure(chunk, closure, mr);
  chunk->iterate_stack(&frame_closure);

  assert (frame_closure._num_frames >= 0, "");
  assert (frame_closure._num_oops >= 0, "");

  if (closure != nullptr) {
    Continuation::emit_chunk_iterate_event(chunk, frame_closure._num_frames, frame_closure._num_oops);
  }
}

template void InstanceStackChunkKlass::oop_oop_iterate_stack_slow<gc_type::STW>        (stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr);
template void InstanceStackChunkKlass::oop_oop_iterate_stack_slow<gc_type::CONCURRENT> (stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr);

class MarkMethodsStackClosure {
  OopIterateClosure* _closure;

public:
  MarkMethodsStackClosure(OopIterateClosure* cl) : _closure(cl) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
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

template <gc_type gc, chunk_frames frame_kind, typename RegisterMapT>
void InstanceStackChunkKlass::relativize_derived_pointers(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
  RelativizeDerivedPointers<gc> derived_closure;
  f.iterate_derived_pointers(&derived_closure, map);
}

template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::STW>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::CONCURRENT> (const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::STW>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::CONCURRENT> (const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::STW>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::CONCURRENT> (const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::STW>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::relativize_derived_pointers<gc_type::CONCURRENT> (const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);


template <InstanceStackChunkKlass::barrier_type barrier, chunk_frames frame_kind, typename RegisterMapT>
void InstanceStackChunkKlass::do_barriers0(stackChunkOop chunk, const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned
  if (f.is_done()) return;

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
      relativize_derived_pointers<gc_type::CONCURRENT>(f, map);
    }
  }

  if (chunk->has_bitmap() && UseCompressedOops) {
    BarrierClosure<barrier, true> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  } else {
    BarrierClosure<barrier, false> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  }
  OrderAccess::loadload(); // observing the barriers will prevent derived pointers from being derelativized concurrently
}

template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::LOAD> (stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::STORE>(stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::LOAD> (stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::STORE>(stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::LOAD> (stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::STORE>(stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::LOAD> (stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::do_barriers0<InstanceStackChunkKlass::barrier_type::STORE>(stackChunkOop chunk, const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);

template <InstanceStackChunkKlass::barrier_type barrier>
class DoBarriersStackClosure {
  const stackChunkOop _chunk;
public:
  DoBarriersStackClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    InstanceStackChunkKlass::do_barriers0<barrier>(_chunk, f, map);
    return true;
  }
};

template <InstanceStackChunkKlass::barrier_type barrier>
void InstanceStackChunkKlass::do_barriers(stackChunkOop chunk) {
  DoBarriersStackClosure<barrier> closure(chunk);
  chunk->iterate_stack(&closure);
}

class RelativizeStackClosure {
  const stackChunkOop _chunk;
public:
  RelativizeStackClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    bool has_derived = f.is_compiled() && f.oopmap()->has_derived_oops();
    if (has_derived) {
      RelativizeDerivedPointers<gc_type::CONCURRENT> derived_closure;
      f.iterate_derived_pointers(&derived_closure, map);
    }
    return true;
  }
};

void InstanceStackChunkKlass::relativize_chunk(stackChunkOop chunk) {
  if (chunk->is_gc_mode()) {
    // Already relativized
    return;
  }
  chunk->set_gc_mode(true);
  OrderAccess::storestore();
  RelativizeStackClosure closure(chunk);
  chunk->iterate_stack(&closure);
}

template void InstanceStackChunkKlass::do_barriers<InstanceStackChunkKlass::barrier_type::LOAD> (stackChunkOop chunk);
template void InstanceStackChunkKlass::do_barriers<InstanceStackChunkKlass::barrier_type::STORE>(stackChunkOop chunk);

#ifdef ASSERT
template<class P>
static inline oop safe_load(P *addr) {
  oop obj = (oop)RawAccess<>::oop_load(addr);
  obj = (oop)NativeAccess<>::oop_load(&obj);
  return obj;
}

// Returns true iff the address p is readable and *(intptr_t*)p != errvalue
extern "C" bool dbg_is_safe(const void* p, intptr_t errvalue);
static bool is_good_oop(oop o) { return    dbg_is_safe(o, -1)
                                        && dbg_is_safe(o->klass(), -1)
                                        && oopDesc::is_oop(o)
                                        && o->klass()->is_klass(); }
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

enum class oop_kind { NARROW, WIDE };

template <oop_kind oops>
class BuildBitmapOopClosure : public OopClosure {
  intptr_t* const _stack_start;
  const BitMap::idx_t _bit_offset;
  BitMapView _bm;
public:
  BuildBitmapOopClosure(intptr_t* stack_start, BitMap::idx_t bit_offset, BitMapView bm)
    : _stack_start(stack_start), _bit_offset(bit_offset), _bm(bm) {}

  virtual void do_oop(oop* p) override {
    assert (p >= (oop*)_stack_start, "");
    if (oops == oop_kind::NARROW) {
      // Convert all oops to narrow before marking bit
      oop obj = *p;
      *p = nullptr;
      // assuming little endian
      *(narrowOop*)p = CompressedOops::encode(obj);
      do_oop((narrowOop*)p);
    } else {
      BitMap::idx_t index = _bit_offset + (p - (oop*)_stack_start);
      assert (!_bm.at(index), "");
      _bm.set_bit(index);
    }
  }

  virtual void do_oop(narrowOop* p) override {
    assert (p >= (narrowOop*)_stack_start, "");
    BitMap::idx_t index = _bit_offset + (p - (narrowOop*)_stack_start);
    assert (!_bm.at(index), "");
    _bm.set_bit(index);
  }
};

template <oop_kind oops>
class BuildBitmapStackClosure {
  stackChunkOop _chunk;
  const BitMap::idx_t _bit_offset;
public:
  BuildBitmapStackClosure(stackChunkOop chunk) : _chunk(chunk), _bit_offset(chunk->bit_offset()) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    if (!_chunk->is_gc_mode() && f.is_compiled() && f.oopmap()->has_derived_oops()) {
      RelativizeDerivedPointers<gc_type::STW> derived_oops_closure;
      f.iterate_derived_pointers(&derived_oops_closure, map);
    }

    if (UseChunkBitmaps) {
      BuildBitmapOopClosure<oops> oops_closure(_chunk->start_address(), _chunk->bit_offset(), _chunk->bitmap());
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
    BuildBitmapStackClosure<oop_kind::NARROW> closure(chunk);
    chunk->iterate_stack(&closure);
  } else {
    BuildBitmapStackClosure<oop_kind::WIDE> closure(chunk);
    chunk->iterate_stack(&closure);
  }

  chunk->set_gc_mode(true); // must be set *after* the above closure
}

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

template void InstanceStackChunkKlass::fix_thawed_frame(stackChunkOop chunk, const frame& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_thawed_frame(stackChunkOop chunk, const frame& f, const SmallRegisterMap* map);

#ifdef ASSERT

template <typename OopT>
class StackChunkVerifyBitmapClosure : public BitMapClosure {
  stackChunkOop _chunk;
public:
  int _count;

  StackChunkVerifyBitmapClosure(stackChunkOop chunk) : _chunk(chunk), _count(0) {}

  bool do_bit(BitMap::idx_t index) override {
    OopT* p = _chunk->address_for_bit<OopT>(index);
    _count++;

    if (!SafepointSynchronize::is_at_safepoint()) {
      oop obj = safe_load(p);
      assert (obj == nullptr || is_good_oop(obj),
              "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT " index: " SIZE_FORMAT " bit_offset: " SIZE_FORMAT,
              p2i(p), p2i((oopDesc*)obj), index, _chunk->bit_offset());
    }

    return true; // continue processing
  }
};

class StackChunkVerifyOopsClosure : public OopClosure {
  stackChunkOop _chunk;
  intptr_t* _unextended_sp;
  int _count;
public:
  StackChunkVerifyOopsClosure(stackChunkOop chunk, intptr_t* unextended_sp)
    : _chunk(chunk), _unextended_sp(unextended_sp), _count(0) {}
  int count() { return _count; }
  void do_oop(oop* p) override { (_chunk->has_bitmap() && UseCompressedOops) ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
     _count++;
    if (SafepointSynchronize::is_at_safepoint()) return;

    oop obj = safe_load(p);
    assert (obj == nullptr || is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
    if (_chunk->has_bitmap()) {
      BitMap::idx_t index = (p - (T*)_chunk->start_address()) + _chunk->bit_offset();
      assert (_chunk->bitmap().at(index), "Bit not set at index " SIZE_FORMAT " corresponding to " INTPTR_FORMAT, index, p2i(p));
    }
  }
};

class StackChunkVerifyDerivedPointersClosure : public DerivedOopClosure {
  stackChunkOop _chunk;
  intptr_t* _unextended_sp;
public:

  StackChunkVerifyDerivedPointersClosure(stackChunkOop chunk, intptr_t* unextended_sp)
    : _chunk(chunk), _unextended_sp(unextended_sp) {}

  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
    if (SafepointSynchronize::is_at_safepoint()) return;

    oop base = (_chunk->has_bitmap() && UseCompressedOops)
                  ? CompressedOops::decode(Atomic::load((narrowOop*)base_loc))
                  : Atomic::load((oop*)base_loc);
    if (base != nullptr) {
      ZGC_ONLY(if (UseZGC && !ZAddress::is_good(cast_from_oop<uintptr_t>(base))) return;)
      assert (!CompressedOops::is_base(base), "");
      assert (oopDesc::is_oop(base), "");
      ZGC_ONLY(assert (!UseZGC || ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)
      OrderAccess::loadload();
      intptr_t offset = Atomic::load((intptr_t*)derived_loc);
      offset = offset <= 0
                  ? -offset
                  : offset - cast_from_oop<intptr_t>(base);
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

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    _sp = f.sp();
    _cb = f.cb();

    int fsize = f.frame_size() - ((f.is_interpreted() == _callee_interpreted) ? _argsize : 0);
    int num_oops = f.num_oops();
    assert (num_oops >= 0, "");

    _argsize   = f.stack_argsize();
    _size     += fsize;
    _num_oops += num_oops;
    if (f.is_interpreted()) {
      _num_interpreted_frames++;
    }

    log_develop_trace(jvmcont)("debug_verify_stack_chunk frame: %d sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT " interpreted: %d size: %d argsize: %d oops: %d", _num_frames, f.sp() - _chunk->start_address(), p2i(f.pc()), f.is_interpreted(), fsize, _argsize, num_oops);
    LogTarget(Trace, jvmcont) lt;
    if (lt.develop_is_enabled()) {
      LogStream ls(lt);
      f.print_on(&ls);
    }
    assert (f.pc() != nullptr,
      "young: %d num_frames: %d sp: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT,
      !_chunk->requires_barriers(), _num_frames, p2i(f.sp()), p2i(_chunk->start_address()), p2i(_chunk->bottom_address()));

    if (_num_frames == 0) {
      assert (f.pc() == _chunk->pc(), "");
    }

    if (_num_frames > 0 && !_callee_interpreted && f.is_interpreted()) {
      log_develop_trace(jvmcont)("debug_verify_stack_chunk i2c");
      _num_i2c++;
    }

    StackChunkVerifyOopsClosure oops_closure(_chunk, f.unextended_sp());
    f.iterate_oops(&oops_closure, map);
    assert (oops_closure.count() == num_oops, "oops: %d oopmap->num_oops(): %d", oops_closure.count(), num_oops);

    StackChunkVerifyDerivedPointersClosure derived_oops_closure(_chunk, f.unextended_sp());
    f.iterate_derived_pointers(&derived_oops_closure, map);

    _callee_interpreted = f.is_interpreted();
    _num_frames++;
    return true;
  }
};

// verifies the consistency of the chunk's data
bool InstanceStackChunkKlass::verify(oop obj, size_t* out_size, int* out_oops,
                                     int* out_frames, int* out_interpreted_frames) {
  DEBUG_ONLY(if (!VerifyContinuations) return true;)

  assert (oopDesc::is_oop(obj), "");
  assert (obj->is_stackChunk(), "");

  stackChunkOop chunk = (stackChunkOop)obj;

  assert (chunk->is_stackChunk(), "");
  assert (chunk->stack_size() >= 0, "");
  assert (chunk->argsize() >= 0, "");
  assert (!chunk->has_bitmap() || chunk->is_gc_mode(), "");

  if (chunk->is_empty()) {
    assert (chunk->argsize() == 0, "");
    assert (chunk->max_size() == 0, "");
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

  // if argsize == 0 and the chunk isn't mixed, the chunk contains the metadata (pc, fp -- frame::sender_sp_offset)
  // for the top frame (below sp), and *not* for the bottom frame
  int size = chunk->stack_size() - chunk->argsize() - chunk->sp();
  assert (size >= 0, "");
  assert ((size == 0) == chunk->is_empty(), "");

  const StackChunkFrameStream<chunk_frames::MIXED> first(chunk);
  const bool has_safepoint_stub_frame = first.is_stub();

  VerifyStackClosure closure(chunk,
    has_safepoint_stub_frame ? 1 : 0, // iterate_stack skips the safepoint stub
    has_safepoint_stub_frame ? first.frame_size() : 0);
  chunk->iterate_stack(&closure);

  assert (!chunk->is_empty() || closure._cb == nullptr, "");
  if (closure._cb != nullptr && closure._cb->is_compiled()) {
    assert (chunk->argsize() ==
      (closure._cb->as_compiled_method()->method()->num_stack_arg_slots()*VMRegImpl::stack_slot_size) >>LogBytesPerWord,
      "chunk argsize: %d bottom frame argsize: %d", chunk->argsize(),
      (closure._cb->as_compiled_method()->method()->num_stack_arg_slots()*VMRegImpl::stack_slot_size) >>LogBytesPerWord);
  }

  assert (closure._num_interpreted_frames == 0 || chunk->has_mixed_frames(), "");

  if (!concurrent) {
    assert (closure._size <= size + chunk->argsize() + metadata_words(),
      "size: %d argsize: %d closure.size: %d end sp: " PTR_FORMAT " start sp: %d chunk size: %d",
      size, chunk->argsize(), closure._size, closure._sp - chunk->start_address(), chunk->sp(), chunk->stack_size());
    assert (chunk->argsize() == closure._argsize,
      "chunk->argsize(): %d closure.argsize: %d closure.callee_interpreted: %d",
      chunk->argsize(), closure._argsize, closure._callee_interpreted);

    int max_size = closure._size + closure._num_i2c * align_wiggle();
    assert (chunk->max_size() == max_size,
      "max_size(): %d max_size: %d argsize: %d num_i2c: %d",
      chunk->max_size(), max_size, closure._argsize, closure._num_i2c);

    if (out_size   != nullptr) *out_size   += size;
    if (out_oops   != nullptr) *out_oops   += closure._num_oops;
    if (out_frames != nullptr) *out_frames += closure._num_frames;
    if (out_interpreted_frames != nullptr) *out_interpreted_frames += closure._num_interpreted_frames;
  } else {
    assert (out_size == nullptr && out_oops == nullptr && out_frames == nullptr && out_interpreted_frames == nullptr, "");
  }

  if (chunk->has_bitmap()) {
    assert (chunk->bitmap().size() == chunk->bit_offset() + (size_t)(chunk->stack_size() << (UseCompressedOops ? 1 : 0)),
      "bitmap().size(): %zu bit_offset: %zu stack_size: %d",
      chunk->bitmap().size(), chunk->bit_offset(), chunk->stack_size());
    int oop_count;
    if (UseCompressedOops) {
      StackChunkVerifyBitmapClosure<narrowOop> bitmap_closure(chunk);
      chunk->bitmap().iterate(&bitmap_closure,
        chunk->bit_index_for((narrowOop*)(chunk->sp_address() - metadata_words())),
        chunk->bit_index_for((narrowOop*)chunk->end_address()));
      oop_count = bitmap_closure._count;
    } else {
      StackChunkVerifyBitmapClosure<oop> bitmap_closure(chunk);
      chunk->bitmap().iterate(&bitmap_closure,
        chunk->bit_index_for((oop*)(chunk->sp_address() - metadata_words())),
        chunk->bit_index_for((oop*)chunk->end_address()));
      oop_count = bitmap_closure._count;
    }
    assert (oop_count == closure._num_oops,
      "bitmap_closure._count: %d closure._num_oops: %d", oop_count, closure._num_oops);
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
  DescribeStackChunkClosure(stackChunkOop chunk)
    : _chunk(chunk), _map((JavaThread*)nullptr, true, false, true), _frame_no(0) {
    _map.set_include_argument_oops(false);
  }

  const RegisterMap* get_map(const RegisterMap* map,      intptr_t* sp) { return map; }
  const RegisterMap* get_map(const SmallRegisterMap* map, intptr_t* sp) { return map->copy_to_RegisterMap(&_map, sp); }

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    ResetNoHandleMark rnhm;
    HandleMark hm(Thread::current());

    frame fr = f.to_frame();
    fr.template describe<frame::addressing::RELATIVE>(_values, _frame_no++, get_map(map, f.sp()));
    return true;
  }

  void describe_chunk() {
    // _values.describe(-1, _chunk->start_address(), "CHUNK START");
    _values.describe(-1, _chunk->sp_address(),         "CHUNK SP");
    _values.describe(-1, _chunk->bottom_address() - 1, "CHUNK ARGS");
    _values.describe(-1, _chunk->end_address() - 1,    "CHUNK END");
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

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& fs, const RegisterMapT* map) {
    frame f = fs.to_frame();
    _st->print_cr("-- frame sp: " INTPTR_FORMAT " interpreted: %d size: %d argsize: %d",
      p2i(fs.sp()), fs.is_interpreted(), f.frame_size(), fs.is_interpreted() ? 0 : f.compiled_frame_stack_argsize());
    f.print_on<frame::addressing::RELATIVE>(_st);
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
  st->print_cr("CHUNK " INTPTR_FORMAT " " INTPTR_FORMAT " - " INTPTR_FORMAT " :: " INTPTR_FORMAT,
    p2i((oopDesc*)c), p2i(c->start_address()), p2i(c->end_address()), c->identity_hash());
  st->print_cr("       barriers: %d gc_mode: %d bitmap: %d parent: " INTPTR_FORMAT,
    c->requires_barriers(), c->is_gc_mode(), c->has_bitmap(), p2i((oopDesc*)c->parent()));
  st->print_cr("       flags mixed: %d", c->has_mixed_frames());
  st->print_cr("       size: %d argsize: %d max_size: %d sp: %d pc: " INTPTR_FORMAT,
    c->stack_size(), c->argsize(), c->max_size(), c->sp(), p2i(c->pc()));

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
template void StackChunkFrameStream<chunk_frames::MIXED>::print_on(outputStream* st) const;
template void StackChunkFrameStream<chunk_frames::COMPILED_ONLY>::print_on(outputStream* st) const;

template <chunk_frames frames>
void StackChunkFrameStream<frames>::print_on(outputStream* st) const {
  st->print_cr("chunk: " INTPTR_FORMAT " index: %d sp offset: %d stack size: %d",
    p2i(_chunk), _index, _chunk->to_offset(_sp), _chunk->stack_size());
  to_frame().template print_on<frame::addressing::RELATIVE>(st);
}
#endif

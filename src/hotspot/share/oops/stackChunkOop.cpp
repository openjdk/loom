/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "code/compiledMethod.hpp"
#include "code/scopeDesc.hpp"
#include "memory/memRegion.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/stackChunkOop.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/registerMap.hpp"
#include "runtime/smallRegisterMap.inline.hpp"
#include "runtime/stackChunkFrameStream.inline.hpp"

frame stackChunkOopDesc::top_frame(RegisterMap* map) {
  assert(!is_empty(), "");
  StackChunkFrameStream<chunk_frames::MIXED> fs(this);

  map->set_stack_chunk(this);
  fs.initialize_register_map(map);
  // if (map->update_map() && should_fix()) InstanceStackChunkKlass::fix_frame<true, false>(fs, map);

  frame f = fs.to_frame();
  // if (!maybe_fix_async_walk(f, map)) return frame();

  assert(to_offset(f.sp()) == sp(), "f.offset_sp(): %d sp(): %d async: %d", f.offset_sp(), sp(), map->is_async());
  relativize_frame(f);
  f.set_frame_index(0);
  return f;
}

frame stackChunkOopDesc::sender(const frame& f, RegisterMap* map) {
  assert(map->in_cont(), "");
  assert(!map->include_argument_oops(), "");
  assert(!f.is_empty(), "");
  assert(map->stack_chunk() == this, "");
  assert(this != nullptr, "");
  assert(!is_empty(), "");

  int index = f.frame_index();
  StackChunkFrameStream<chunk_frames::MIXED> fs(this, derelativize(f));
  fs.next(map);

  if (!fs.is_done()) {
    frame sender = fs.to_frame();
    assert(is_usable_in_chunk(sender.unextended_sp()), "");
    relativize_frame(sender);

    sender.set_frame_index(index+1);
    return sender;
  }

  if (parent() != nullptr) {
    assert(!parent()->is_empty(), "");
    return parent()->top_frame(map);
  }

  return Continuation::continuation_parent_frame(map);
}

// bool stackChunkOopDesc::maybe_fix_async_walk(frame& f, RegisterMap* map) const {
//   if (!Continuation::is_return_barrier_entry(f.pc()))
//     return true;

//   // Can happen during async stack walks, where the continuation is in the midst of a freeze/thaw
//   assert(map->is_async(), "");
//   address pc0 = pc();

//   // we write sp first, then pc; here we read in the opposite order, so if sp is right, so is pc.
//   OrderAccess::loadload();
//   if (sp() == to_offset(f.sp())) {
//     f.set_pc(pc0);
//     return true;
//   }
//   assert(false, "");
//   log_debug(jvmcont)("failed to fix frame during async stackwalk");
//   return false;
// }

static int num_java_frames(CompiledMethod* cm, address pc) {
  int count = 0;
  for (ScopeDesc* scope = cm->scope_desc_at(pc); scope != nullptr; scope = scope->sender()) {
    count++;
  }
  return count;
}

static int num_java_frames(const StackChunkFrameStream<chunk_frames::MIXED>& f) {
  assert(f.is_interpreted()
         || (f.cb() != nullptr && f.cb()->is_compiled() && f.cb()->as_compiled_method()->is_java_method()), "");
  return f.is_interpreted() ? 1 : num_java_frames(f.cb()->as_compiled_method(), f.orig_pc());
}

int stackChunkOopDesc::num_java_frames() const {
  int n = 0;
  for (StackChunkFrameStream<chunk_frames::MIXED> f(const_cast<stackChunkOopDesc*>(this)); !f.is_done();
       f.next(SmallRegisterMap::instance)) {
    if (!f.is_stub()) {
      n += ::num_java_frames(f);
    }
  }
  return n;
}

template <stackChunkOopDesc::barrier_type barrier>
class DoBarriersStackClosure {
  const stackChunkOop _chunk;

public:
  DoBarriersStackClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    _chunk->do_barriers0<barrier>(f, map);
    return true;
  }
};

template <stackChunkOopDesc::barrier_type barrier>
void stackChunkOopDesc::do_barriers() {
  DoBarriersStackClosure<barrier> closure(this);
  iterate_stack(&closure);
}

template void stackChunkOopDesc::do_barriers<stackChunkOopDesc::barrier_type::LOAD> ();
template void stackChunkOopDesc::do_barriers<stackChunkOopDesc::barrier_type::STORE>();

// We replace derived pointers with offsets; the converse is done in DerelativizeDerivedPointers
class RelativizeDerivedPointers : public DerivedOopClosure {
public:
  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
    // The ordering in the following is crucial
    OrderAccess::loadload();
    oop base = Atomic::load((oop*)base_loc);
    if (base == nullptr) {
      assert(*derived_loc == derived_pointer(0), "");
      return;
    }
    assert(!CompressedOops::is_base(base), "");

#if INCLUDE_ZGC
    if (UseZGC) {
      if (ZAddress::is_good(cast_from_oop<uintptr_t>(base))) {
        return;
      }
    }
#endif
#if INCLUDE_SHENANDOAHGC
    if (UseShenandoahGC) {
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
    if (offset < 0) {
      // It looks as if a derived pointer appears live in the oopMap but isn't pointing into the object.
      // This might be the result of address computation floating above corresponding range check for array access.
      offset = -1;
    }
    Atomic::store((intptr_t*)derived_loc, -offset);
  }
};

template <chunk_frames frame_kind, typename RegisterMapT>
static void relativize_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
  bool has_derived = f.is_compiled() && f.oopmap()->has_derived_oops();
  if (has_derived) {
    RelativizeDerivedPointers derived_closure;
    f.iterate_derived_pointers(&derived_closure, map);
  }
}

class RelativizeStackClosure {
  const stackChunkOop _chunk;

public:
  RelativizeStackClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    relativize_frame(f, map);
    return true;
  }
};

void stackChunkOopDesc::relativize() {
  assert(!is_gc_mode(), "Should only be called once per chunk");
  set_gc_mode(true);
  OrderAccess::storestore();
  RelativizeStackClosure closure(this);
  iterate_stack(&closure);
}

enum class oop_kind { NARROW, WIDE };

template <oop_kind oops>
class CompressOopsAndBuildBitmapOopClosure : public OopClosure {
  stackChunkOop _chunk;
  BitMapView _bm;

  void convert_oop_to_narrowOop(oop* p) {
    oop obj = *p;
    *p = nullptr;
    // assuming little endian
    *(narrowOop*)p = CompressedOops::encode(obj);
  }

  template <typename T>
  void do_oop_work(T* p) {
    BitMap::idx_t index = _chunk->bit_index_for(p);
    assert(!_bm.at(index), "");
    _bm.set_bit(index);
  }

public:
  CompressOopsAndBuildBitmapOopClosure(stackChunkOop chunk)
    : _chunk(chunk), _bm(chunk->bitmap()) {}

  virtual void do_oop(oop* p) override {
    if (oops == oop_kind::NARROW) {
      // Convert all oops to narrow before marking bit
      convert_oop_to_narrowOop(p);
      do_oop_work((narrowOop*)p);
    } else {
      do_oop_work(p);
    }
  }

  virtual void do_oop(narrowOop* p) override {
    do_oop_work(p);
  }
};

template <oop_kind oops>
class TransformStackChunkClosure {
  stackChunkOop _chunk;

public:
  TransformStackChunkClosure(stackChunkOop chunk) : _chunk(chunk) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    // Relativize derived oops
    relativize_frame(f, map);

    if (UseChunkBitmaps) {
      CompressOopsAndBuildBitmapOopClosure<oops> cl(_chunk);
      f.iterate_oops(&cl, map);
    }

    return true;
  }
};

void stackChunkOopDesc::transform() {
  assert(!is_gc_mode(), "Should only be called once per chunk");
  set_gc_mode(true);

  if (UseChunkBitmaps) {
    assert(!has_bitmap(), "Should only be set once");
    set_has_bitmap(true);
    bitmap().clear();
  }

  if (UseCompressedOops) {
    TransformStackChunkClosure<oop_kind::NARROW> closure(this);
    iterate_stack(&closure);
  } else {
    TransformStackChunkClosure<oop_kind::WIDE> closure(this);
    iterate_stack(&closure);
  }
}

template <stackChunkOopDesc::barrier_type barrier, bool compressedOopsWithBitmap>
class BarrierClosure: public OopClosure {
  NOT_PRODUCT(intptr_t* _sp;)

public:
  BarrierClosure(intptr_t* sp) NOT_PRODUCT(: _sp(sp)) {}

  virtual void do_oop(oop* p)       override { compressedOopsWithBitmap ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    oop value = (oop)HeapAccess<>::oop_load(p);
    if (barrier == stackChunkOopDesc::barrier_type::STORE) {
      HeapAccess<>::oop_store(p, value);
    }
  }
};


template <stackChunkOopDesc::barrier_type barrier, chunk_frames frame_kind, typename RegisterMapT>
void stackChunkOopDesc::do_barriers0(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned
  if (f.is_done()) {
    return;
  }

  if (f.is_interpreted()) {
    Method* m = f.to_frame().interpreter_frame_method();
    m->record_marking_cycle();
  } else if (f.is_compiled()) {
    nmethod* nm = f.cb()->as_nmethod();
    // The entry barrier takes care of having the right synchronization
    // when keeping the nmethod alive during concurrent execution.
    nm->run_nmethod_entry_barrier();
    // there's no need to mark the Method, as class redefinition will walk the CodeCache, noting their Methods
  }

  assert(!f.is_compiled() || f.oopmap()->has_derived_oops() == f.oopmap()->has_any(OopMapValue::derived_oop_value), "");
  bool has_derived = f.is_compiled() && f.oopmap()->has_derived_oops();
  if (has_derived) {
    relativize_derived_pointers(f, map);
  }

  if (has_bitmap() && UseCompressedOops) {
    BarrierClosure<barrier, true> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  } else {
    BarrierClosure<barrier, false> oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
  }
  OrderAccess::loadload(); // observing the barriers will prevent derived pointers from being derelativized concurrently
}

template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::LOAD> (const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::STORE>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::LOAD> (const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::STORE>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::LOAD> (const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::STORE>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::LOAD> (const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);
template void stackChunkOopDesc::do_barriers0<stackChunkOopDesc::barrier_type::STORE>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);

template <chunk_frames frame_kind, typename RegisterMapT>
void stackChunkOopDesc::relativize_derived_pointers(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
  RelativizeDerivedPointers derived_closure;
  f.iterate_derived_pointers(&derived_closure, map);
}

template void stackChunkOopDesc::relativize_derived_pointers<>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const RegisterMap* map);
template void stackChunkOopDesc::relativize_derived_pointers<>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const RegisterMap* map);
template void stackChunkOopDesc::relativize_derived_pointers<>(const StackChunkFrameStream<chunk_frames::MIXED>& f, const SmallRegisterMap* map);
template void stackChunkOopDesc::relativize_derived_pointers<>(const StackChunkFrameStream<chunk_frames::COMPILED_ONLY>& f, const SmallRegisterMap* map);

void stackChunkOopDesc::print_on(bool verbose, outputStream* st) const {
  if (this == nullptr) {
    st->print_cr("NULL");
  } else if (*((juint*)this) == badHeapWordVal) {
    st->print("BAD WORD");
  } else if (*((juint*)this) == badMetaWordVal) {
    st->print("BAD META WORD");
  } else {
    InstanceStackChunkKlass::print_chunk(const_cast<stackChunkOopDesc*>(this), verbose, st);
  }
}

#ifdef ASSERT
bool stackChunkOopDesc::verify(size_t* out_size, int* out_oops, int* out_frames, int* out_interpreted_frames) {
  return InstanceStackChunkKlass::verify(this, out_size, out_oops, out_frames, out_interpreted_frames);
}
#endif

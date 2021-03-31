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

#include "oops/instanceStackChunkKlass.hpp"
#include "gc/shared/gc_globals.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oopsHierarchy.hpp"
#include "precompiled.hpp"
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
#include "utilities/macros.hpp"

int InstanceStackChunkKlass::_offset_of_stack = 0;

#ifndef PRODUCT
bool stackChunkOopDesc::verify(size_t* out_size, int* out_oops, int* out_frames, int* out_interpreted_frames) {
  return InstanceStackChunkKlass::verify(this, out_size, out_oops, out_frames, out_interpreted_frames);
}
#endif

bool stackChunkOopDesc::should_fix() const {
  const bool concurrent_gc = (UseZGC || UseShenandoahGC);
  return 
    UseCompressedOops
      ? (concurrent_gc ? should_fix<narrowOop, true>()
                       : should_fix<narrowOop, false>())
      : (concurrent_gc ? should_fix<oop,       true>()
                       : should_fix<oop,       false>());
}

// void stackChunkOopDesc::fix_frame_if_needed(const frame& fr, const RegisterMap* map) const {
//   if (should_fix()) {
//     InstanceStackChunkKlass::fix_frame<true, false>(StackChunkFrameStream<true>(const_cast<stackChunkOopDesc*>(this), derelativize(fr)), map);
//   }
// }

frame stackChunkOopDesc::top_frame(RegisterMap* map) {
  // tty->print_cr(">>> stackChunkOopDesc::top_frame this: %p map: %p map->chunk: %p", this, map, (stackChunkOopDesc*)map->stack_chunk()());
  StackChunkFrameStream<true> fs(this);

  map->set_stack_chunk(this);
  fs.initialize_register_map(map);
  // if (map->update_map() && should_fix()) InstanceStackChunkKlass::fix_frame<true, false>(fs, map);

  frame f = fs.to_frame();
  relativize_frame(f);
  f.set_frame_index(0);  
  return f;
}

frame stackChunkOopDesc::sender(const frame& f, RegisterMap* map) {
  // tty->print_cr(">>> stackChunkOopDesc::sender this: %p map: %p map->chunk: %p", this, map, (stackChunkOopDesc*)map->stack_chunk()()); derelativize(f).print_on<true>(tty);
  assert (map->in_cont(), "");
  assert (!map->include_argument_oops(), "");
  assert (!f.is_empty(), "");
  assert (map->stack_chunk() == this, "");
  assert (this != nullptr, "");
  assert (!is_empty(), "");

  int index = f.frame_index();
  StackChunkFrameStream<true> fs(this, derelativize(f));
  fs.next(map);
  // if (map->update_map() && should_fix()) InstanceStackChunkKlass::fix_frame<true, false>(fs, map);
  if (!fs.is_done()) {
    frame sender = fs.to_frame();
    assert (is_usable_in_chunk(sender.unextended_sp()), "");
    relativize_frame(sender);

    sender.set_frame_index(index+1);
    return sender;
  }

  if (parent() != (oop)nullptr) {
    assert (!parent()->is_empty(), "");
    return parent()->top_frame(map);
  }

  return Continuation::continuation_parent_frame(map);
}

static int num_java_frames(CompiledMethod* cm, address pc) {
  int count = 0;
  for (ScopeDesc* scope = cm->scope_desc_at(pc); scope != nullptr; scope = scope->sender())
    count++;
  return count;
}

static int num_java_frames(const StackChunkFrameStream<true>& f) {
  assert (f.is_interpreted() || (f.cb() != nullptr && f.cb()->is_compiled() && f.cb()->as_compiled_method()->is_java_method()), "");
  return f.is_interpreted() ? 1 : num_java_frames(f.cb()->as_compiled_method(), f.orig_pc());
}

int stackChunkOopDesc::num_java_frames() const {
  int n = 0;
  for (StackChunkFrameStream<true> f(const_cast<stackChunkOopDesc*>(this)); !f.is_done(); f.next(SmallRegisterMap::instance)) {
    if (!f.is_stub()) n += ::num_java_frames(f);
  }
  return n;
}

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
  if (UseNewCode) {
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

int InstanceStackChunkKlass::instance_size(int stack_size_in_words) const {
  return align_object_size(size_helper() + stack_size_in_words);
}

int InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_misc_StackChunk::size(obj));
}

int InstanceStackChunkKlass::compact_oop_size(oop obj) const {
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;
  // We therefore don't trim chunks with ZGC. See copy_compact
  if (UseZGC) return instance_size(chunk->stack_size());
  int used_stack_in_words = chunk->stack_size() - chunk->sp() + metadata_words();
  assert (used_stack_in_words <= chunk->stack_size(), "");
  return align_object_size(size_helper() + used_stack_in_words);
}

template size_t InstanceStackChunkKlass::copy_compact<false>(oop obj, HeapWord* to_addr);
template size_t InstanceStackChunkKlass::copy_compact<true>(oop obj, HeapWord* to_addr);

template<bool disjoint>
size_t InstanceStackChunkKlass::copy_compact(oop obj, HeapWord* to_addr) {
  // tty->print_cr(">>> InstanceStackChunkKlass::copy_compact from: %p to: %p", (oopDesc*)obj, to_addr);
  assert (obj->is_stackChunk(), "");
  stackChunkOop chunk = (stackChunkOop)obj;

  int from_sp = chunk->sp();
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

  int from_size = chunk->stack_size();
  assert (from_sp < from_size || from_sp == from_size + metadata_words(), "sp: %d size: %d", from_sp, from_size);
  int used_stack_in_words = from_size - from_sp + metadata_words();
  assert (used_stack_in_words >= 0, "");
  assert (used_stack_in_words > 0 || chunk->argsize() == 0, "");
  
  // copy header
  HeapWord* from_addr = cast_from_oop<HeapWord*>(obj);
  disjoint ? Copy::aligned_disjoint_words(from_addr, to_addr, header)
           : Copy::aligned_conjoint_words(from_addr, to_addr, header);

  // update header
  stackChunkOop to_chunk = (stackChunkOop) cast_to_oop(to_addr);
  jdk_internal_misc_StackChunk::set_size(to_addr, used_stack_in_words);
  to_chunk->set_sp(metadata_words());

  // copy stack
  if (used_stack_in_words > 0) {
    assert ((from_addr + header) == start_of_stack(obj), "");
    HeapWord* from_start = from_addr + header + from_sp - metadata_words();
    HeapWord* to_start = to_addr + header;
    disjoint ? Copy::aligned_disjoint_words(from_start, to_start, used_stack_in_words)
             : Copy::aligned_conjoint_words(from_start, to_start, used_stack_in_words);
  }
 
  assert (to_chunk->size() == old_compact_size, "");
  assert (to_chunk->size() == instance_size(used_stack_in_words), "");
  assert (from_sp <= metadata_words() || to_chunk->size() < old_size, "");
  assert (to_chunk->verify(), "");

  // assert (to_chunk->requires_barriers(), ""); // G1 sometimes compacts a young region and *then* turns it old ((G1CollectedHeap*)Universe::heap())->heap_region_containing(oop(to_addr))->print();
  
  return align_object_size(header + used_stack_in_words);
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

template<bool store>
class BarrierClosure: public OopClosure {
  DEBUG_ONLY(intptr_t* _sp;)
public:
  BarrierClosure(intptr_t* sp) DEBUG_ONLY(: _sp(sp)) {}

  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    oop value = (oop)HeapAccess<>::oop_load(p);
    if (store) HeapAccess<>::oop_store(p, value);
    log_develop_trace(jvmcont)("barriers_for_oops_in_frame narrow: %d p: " INTPTR_FORMAT " sp offset: %ld", sizeof(T) < sizeof(intptr_t), p2i(p), (intptr_t*)p - _sp);
  }

// virtual void do_oop(oop* p) override { *(oop*)p = (oop)NativeAccess<>::oop_load((oop*)p); }
// virtual void do_oop(narrowOop* p) override { *(narrowOop*)p = CompressedOops::encode((oop)NativeAccess<>::oop_load((narrowOop*)p)); }
};


template <bool mixed, bool store, typename RegisterMapT>
void InstanceStackChunkKlass::fix_frame(const StackChunkFrameStream<mixed>& f, const RegisterMapT* map) {
  // we need to invoke the write barriers so as not to miss oops in old chunks that haven't yet been concurrently scanned
  if (f.is_done()) return;
  log_develop_trace(jvmcont)("InstanceStackChunkKlass::fix_frame sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(f.sp()), p2i(f.pc()));

  if (log_develop_is_enabled(Trace, jvmcont) && !mixed && f.is_interpreted()) f.cb()->print_value_on(tty);

  if (mixed) f.handle_deopted(); // we could freeze deopted frames in slow mode.

  run_nmethod_entry_barrier_if_needed<mixed>(f);

  assert (!f.is_compiled() || f.oopmap()->has_derived_oops() == f.oopmap()->has_any(OopMapValue::derived_oop_value), "");
  bool has_derived = f.is_compiled() && f.oopmap()->has_derived_oops();
  if (has_derived) {
    if (UseZGC || UseShenandoahGC) {
      RelativizeDerivedPointers<true> derived_closure;
      f.iterate_derived_pointers(&derived_closure, map);
    }
  }

  BarrierClosure<store> oops_closure(f.sp());
  f.iterate_oops(&oops_closure, map);
  OrderAccess::loadload(); // observing the barriers will prevent derived pointers from being derelativized concurrently

  if (has_derived) {
    DerelativizeDerivedPointers derived_closure;
    f.iterate_derived_pointers(&derived_closure, map);
  }
}

template void InstanceStackChunkKlass::fix_frame<true,  false>(const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<true,  true> (const StackChunkFrameStream<true >& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<false, false>(const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<false, true> (const StackChunkFrameStream<false>& f, const RegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<true,  false>(const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<true,  true> (const StackChunkFrameStream<true >& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<false, false>(const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);
template void InstanceStackChunkKlass::fix_frame<false, true> (const StackChunkFrameStream<false>& f, const SmallRegisterMap* map);


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

#ifdef ASSERT

template<class P>
static inline oop safe_load(P *addr) {
  oop obj = (oop)RawAccess<>::oop_load(addr);
  obj = (oop)NativeAccess<>::oop_load(&obj);
  return obj;
}

// Returns true iff the address p is readable and *(intptr_t*)p != errvalue
extern "C" bool dbg_is_safe(void* p, intptr_t errvalue);
static bool is_good_oop(oop o) { return dbg_is_safe(o, -1) && dbg_is_safe(o->klass(), -1) && oopDesc::is_oop(o) && o->klass()->is_klass(); }

class StackChunkVerifyOopsClosure : public OopClosure {
  intptr_t* _sp;
  int _count;
public:
  StackChunkVerifyOopsClosure(intptr_t* sp) : _sp(sp), _count(0) {}
  int count() { return _count; }
  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <class T> inline void do_oop_work(T* p) {
    log_develop_trace(jvmcont)("debug_verify_stack_chunk narrow: %d p: " INTPTR_FORMAT, sizeof(T) < sizeof(intptr_t), p2i(p));
    _count++;
    oop obj = safe_load(p);
    if (!SafepointSynchronize::is_at_safepoint()) {
      assert (obj == nullptr || is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
    }
  }
};

class StackChunkVerifyDerivedPointersClosure : public DerivedOopClosure {
public:
  virtual void do_derived_oop(oop *base_loc, oop *derived_loc) override {
    log_develop_trace(jvmcont)("debug_verify_stack_chunk base: " INTPTR_FORMAT " derived: " INTPTR_FORMAT, p2i(base_loc), p2i(derived_loc));
    oop base = *(oop*)base_loc; // (oop)NativeAccess<>::oop_load((oop*)base_loc); // 
    assert (base == nullptr || is_good_oop(base), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(base_loc), p2i((oopDesc*)base));
    if (base != (oop)nullptr) {
      assert (!CompressedOops::is_base(base), "");
      assert (oopDesc::is_oop(base), "");
      ZGC_ONLY(assert (!UseZGC || ZAddress::is_good(cast_from_oop<uintptr_t>(base)), "");)
      intptr_t offset = *(intptr_t*)derived_loc;
      offset = offset < 0
                  ? -offset
                  : offset - cast_from_oop<intptr_t>(base);
      assert (offset >= 0 && offset <= (base->size() << LogHeapWordSize), "");
    } else {
      assert (*derived_loc == (oop)nullptr, "");
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
      !_chunk->requires_barriers(), _chunk->numFrames(), _num_frames, p2i(f.sp()), p2i(_chunk->start_address()), p2i(_chunk->end_address()));
    
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

    StackChunkVerifyOopsClosure oops_closure(f.sp());
    f.iterate_oops(&oops_closure, map);
    assert (oops_closure.count() == num_oops, "oops: %d oopmap->num_oops(): %d", oops_closure.count(), num_oops);

    StackChunkVerifyDerivedPointersClosure derived_oops_closure;
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
    if (Thread::current()->as_Java_thread()->cont_fastpath_thread_state())
      check_deopt = true;
  }

  const bool concurrent = !SafepointSynchronize::is_at_safepoint() && !Thread::current()->is_Java_thread();
  const bool gc_mode = chunk->gc_mode();
  const bool is_last = chunk->parent() == nullptr;
  const bool mixed = chunk->has_mixed_frames();

  // if argsize == 0 and the chunk isn't mixed, the chunk contains the metadata (pc, fp -- frame::sender_sp_offset) for the top frame (below sp), and *not* for the bottom frame
  int size = chunk->stack_size() - chunk->argsize() - chunk->sp();
  // tty->print_cr("size: %d chunk->stack_size(): %d chunk->sp(): %d chunk->argsize(): %d", size, chunk->stack_size(), chunk->sp(), chunk->argsize());
  assert (size >= 0, "");
  assert ((size == 0) == chunk->is_empty(), "");

  StackChunkFrameStream<true> first(chunk);
  const bool has_safepoint_stub_frame = first.is_stub();

  VerifyStackClosure closure(chunk,
    has_safepoint_stub_frame ? 1 : 0, // iterate_stack skips the safepoint stub
    has_safepoint_stub_frame ? first.frame_size() : 0);
  chunk->iterate_stack(&closure);

  assert (!chunk->is_empty() || closure._cb == nullptr, "");
  if (closure._cb != nullptr && closure._cb->is_compiled()) {
    assert (chunk->argsize() == (closure._cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord, 
      "chunk argsize: %d bottom frame argsize: %d", chunk->argsize(), (closure._cb->as_compiled_method()->method()->num_stack_arg_slots() * VMRegImpl::stack_slot_size) >> LogBytesPerWord);
  } else {
    assert (chunk->argsize() == 0, "");
  }
  assert (closure._num_interpreted_frames == 0 || chunk->has_mixed_frames(), "");

  if (!concurrent) {
    assert (closure._size <= size + chunk->argsize() + metadata_words(), "size: %d argsize: %d closure.size: %d end sp: %ld start sp: %d chunk size: %d", size, chunk->argsize(), closure._size, closure._sp - chunk->start_address(), chunk->sp(), chunk->stack_size());
    assert (chunk->argsize() == (closure._callee_interpreted ? 0 : closure._argsize), "chunk->argsize(): %d closure.argsize: %d closure.callee_interpreted: %d", chunk->argsize(), closure._argsize, closure._callee_interpreted);
    
    int max_size = closure._size + closure._num_i2c * align_wiggle();
    assert (chunk->max_size() == max_size, "max_size(): %d max_size: %d argsize: %d num_i2c: %d", chunk->max_size(), max_size, closure._argsize, closure._num_i2c);

    assert (chunk->numFrames() == -1 || closure._num_frames == chunk->numFrames(), "barriers: %d num_frames: %d chunk->numFrames(): %d", chunk->requires_barriers(), closure._num_frames, chunk->numFrames());
    assert (chunk->numOops()   == -1 || closure._num_oops   == chunk->numOops(),   "barriers: %d num_oops: %d chunk->numOops(): %d",     chunk->requires_barriers(), closure._num_oops,   chunk->numOops());

    if (out_size   != nullptr) *out_size   += size;
    if (out_oops   != nullptr) *out_oops   += closure._num_oops;
    if (out_frames != nullptr) *out_frames += closure._num_frames;
    if (out_interpreted_frames != nullptr) *out_interpreted_frames += closure._num_interpreted_frames;
  } else assert (out_size == nullptr && out_oops == nullptr && out_frames == nullptr && out_interpreted_frames == nullptr, "");


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
    _values.describe(-1, _chunk->end_address() - 1, "CHUNK ARGS");
    _values.describe(-1, _chunk->start_address() + _chunk->stack_size() - 1, "CHUNK END");
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
  st->print_cr("CHUNK " INTPTR_FORMAT " " INTPTR_FORMAT " - " INTPTR_FORMAT " :: 0x%lx", p2i((oopDesc*)c), p2i(c->start_address()), p2i(c->start_address() + c->stack_size()), c->identity_hash());
  st->print_cr("       barriers: %d gc_mode: %d parent: " INTPTR_FORMAT, c->requires_barriers(), c->gc_mode(), p2i((oopDesc*)c->parent()));
  st->print_cr("       flags mixed: %d", c->has_mixed_frames());
  st->print_cr("       size: %d argsize: %d max_size: %d sp: %d pc: " INTPTR_FORMAT " num_frames: %d num_oops: %d", c->stack_size(), c->argsize(), c->max_size(), c->sp(), p2i(c->pc()), c->numFrames(), c->numOops());

  if (verbose) {
    st->cr();
    st->print_cr("------ chunk frames end: " INTPTR_FORMAT, p2i(c->end_address()));
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
  } else {
    st->print_cr(" frames: %d", count_frames(c));
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

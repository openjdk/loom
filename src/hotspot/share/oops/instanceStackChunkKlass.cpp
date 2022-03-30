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
#include "code/scopeDesc.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/systemDictionary.hpp"
#include "compiler/compiler_globals.hpp"
#include "compiler/oopMap.inline.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/oopFactory.hpp"
#include "memory/resourceArea.hpp"
#include "jfr/jfrEvents.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "oops/compressedOops.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceOop.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/klass.hpp"
#include "oops/oop.inline.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/stackChunkOop.inline.hpp"
#include "oops/symbol.hpp"
#include "runtime/atomic.hpp"
#include "runtime/continuation.hpp"
#include "runtime/globals.hpp"
#include "runtime/orderAccess.hpp"
#include "runtime/smallRegisterMap.inline.hpp"
#include "runtime/stackChunkFrameStream.inline.hpp"
#include "utilities/bitMap.inline.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"
#include "utilities/ostream.hpp"
#if INCLUDE_SHENANDOAHGC
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#endif
#if INCLUDE_ZGC
#include "gc/z/zAddress.inline.hpp"
#endif

int InstanceStackChunkKlass::_offset_of_stack = 0;

#if INCLUDE_CDS
void InstanceStackChunkKlass::serialize_offsets(SerializeClosure* f) {
  f->do_u4((u4*)&_offset_of_stack);
}
#endif

InstanceStackChunkKlass::InstanceStackChunkKlass(const ClassFileParser& parser)
  : InstanceKlass(parser, ID) {
  // see oopDesc::size_given_klass
  const jint lh = Klass::instance_layout_helper(size_helper(), true);
  set_layout_helper(lh);
  assert(layout_helper_is_instance(layout_helper()), "");
  assert(layout_helper_needs_slow_path(layout_helper()), "");
}

size_t InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_vm_StackChunk::size(obj));
}

#ifndef PRODUCT
void InstanceStackChunkKlass::oop_print_on(oop obj, outputStream* st) {
  print_chunk(stackChunkOopDesc::cast(obj), false, st);
}
#endif

template<typename OopClosureType>
class StackChunkOopIterateFilterClosure: public OopClosure {
private:
  OopClosureType* const _closure;
  MemRegion _bound;

public:
  int _num_oops;

  StackChunkOopIterateFilterClosure(OopClosureType* closure, MemRegion bound)
    : _closure(closure),
      _bound(bound),
      _num_oops(0) {}

  virtual void do_oop(oop* p)       override { do_oop_work(p); }
  virtual void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <typename T>
  void do_oop_work(T* p) {
    if (_bound.contains(p)) {
      Devirtualizer::do_oop(_closure, p);
      _num_oops++;
    }
  }
};

class DoMethodsStackChunkFrameClosure {
  OopIterateClosure* _closure;

public:
  DoMethodsStackChunkFrameClosure(OopIterateClosure* cl) : _closure(cl) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    if (f.is_interpreted()) {
      Method* m = f.to_frame().interpreter_frame_method();
      _closure->do_method(m);
    } else if (f.is_compiled()) {
      nmethod* nm = f.cb()->as_nmethod();
      // The do_nmethod function takes care of having the right synchronization
      // when keeping the nmethod alive during concurrent execution.
      _closure->do_nmethod(nm);
      // there's no need to mark the Method, as class redefinition will walk the CodeCache, noting their Methods
    }
    return true;
  }
};

void InstanceStackChunkKlass::do_methods(stackChunkOop chunk, OopIterateClosure* cl) {
  DoMethodsStackChunkFrameClosure closure(cl);
  chunk->iterate_stack(&closure);
}

class OopIterateStackChunkFrameClosure {
  OopIterateClosure* const _closure;
  MemRegion _bound;
  const bool _do_metadata;

public:
  int _num_frames;
  int _num_oops;

  OopIterateStackChunkFrameClosure(OopIterateClosure* closure, MemRegion mr)
    : _closure(closure),
      _bound(mr),
      _do_metadata(_closure->do_metadata()),
      _num_frames(0),
      _num_oops(0) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    _num_frames++;
    assert(_closure != nullptr, "");

    if (_do_metadata) {
      DoMethodsStackChunkFrameClosure(_closure).do_frame(f, map);
    }

    StackChunkOopIterateFilterClosure<OopIterateClosure> cl(_closure, _bound);
    f.iterate_oops(&cl, map);
    _num_oops += cl._num_oops;

    return true;
  }
};

void InstanceStackChunkKlass::oop_oop_iterate_stack_slow(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr) {
  OopIterateStackChunkFrameClosure frame_closure(closure, mr);
  chunk->iterate_stack(&frame_closure);

  assert(frame_closure._num_frames >= 0, "");
  assert(frame_closure._num_oops >= 0, "");

  if (closure != nullptr) {
    Continuation::emit_chunk_iterate_event(chunk, frame_closure._num_frames, frame_closure._num_oops);
  }
}

#ifdef ASSERT
template <typename P>
static inline oop safe_load(P* addr) {
  oop obj = RawAccess<>::oop_load(addr);
  return NativeAccess<>::oop_load(&obj);
}
#endif

#ifdef ASSERT

template <typename T>
class StackChunkVerifyBitmapClosure : public BitMapClosure {
  stackChunkOop _chunk;

public:
  int _count;

  StackChunkVerifyBitmapClosure(stackChunkOop chunk) : _chunk(chunk), _count(0) {}

  bool do_bit(BitMap::idx_t index) override {
    T* p = _chunk->address_for_bit<T>(index);
    _count++;

    if (!SafepointSynchronize::is_at_safepoint()) {
      oop obj = safe_load(p);
      assert(obj == nullptr || dbg_is_good_oop(obj),
              "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT " index: " SIZE_FORMAT,
              p2i(p), p2i((oopDesc*)obj), index);
    }

    return true; // continue processing
  }
};

class StackChunkVerifyOopsClosure : public OopClosure {
  stackChunkOop _chunk;
  int _count;

public:
  StackChunkVerifyOopsClosure(stackChunkOop chunk)
    : _chunk(chunk), _count(0) {}

  void do_oop(oop* p) override { (_chunk->has_bitmap() && UseCompressedOops) ? do_oop_work((narrowOop*)p) : do_oop_work(p); }
  void do_oop(narrowOop* p) override { do_oop_work(p); }

  template <typename T> inline void do_oop_work(T* p) {
     _count++;
    if (SafepointSynchronize::is_at_safepoint()) return;

    oop obj = safe_load(p);
    assert(obj == nullptr || dbg_is_good_oop(obj), "p: " INTPTR_FORMAT " obj: " INTPTR_FORMAT, p2i(p), p2i((oopDesc*)obj));
    if (_chunk->has_bitmap()) {
      BitMap::idx_t index = _chunk->bit_index_for(p);
      assert(_chunk->bitmap().at(index), "Bit not set at index " SIZE_FORMAT " corresponding to " INTPTR_FORMAT, index, p2i(p));
    }
  }

  int count() const { return _count; }
};

class StackChunkVerifyDerivedPointersClosure : public DerivedOopClosure {
  stackChunkOop _chunk;
  const bool    _requires_barriers;

public:
  StackChunkVerifyDerivedPointersClosure(stackChunkOop chunk)
    : _chunk(chunk),
      _requires_barriers(chunk->requires_barriers()) {}

  virtual void do_derived_oop(oop* base_loc, derived_pointer* derived_loc) override {
    if (SafepointSynchronize::is_at_safepoint()) {
      return;
    }

    oop base = (_chunk->has_bitmap() && UseCompressedOops)
                  ? CompressedOops::decode(Atomic::load((narrowOop*)base_loc))
                  : Atomic::load((oop*)base_loc);
    if (base == nullptr) {
      assert(*derived_loc == derived_pointer(0), "Unexpected");
      return;
    }

#if INCLUDE_ZGC
    if (UseZGC && !ZAddress::is_good(cast_from_oop<uintptr_t>(base))) {
      // If the base oops has not been fixed, other threads could be fixing
      // the derived oops concurrently with this code. Don't proceed with the
      // verification.
      return;
    }
#endif

    assert(!UseCompressedOops || !CompressedOops::is_base(base), "Should not be the compressed oops base");
    assert(oopDesc::is_oop(base), "Should be a valid oop");

    // Order the loads of the base oop and the derived oop
    OrderAccess::loadload();

    intptr_t offset = Atomic::load((intptr_t*)derived_loc);

    if (offset == 0 || offset == 1) {
      // Special-case. See: RelativizeDerivedOopClosure
      return;
    }

    // Offsets are "tagged" as negative values

    if (UseZGC && _requires_barriers) {
      // For ZGC when the _chunk has transition to a state where the layout has
      // become stable(requires_barriers()), the earlier is_good check should
      // guarantee that all derived oops have been converted too offsets.
      assert(offset < 0, "Unexpected non-offset value: " PTR_FORMAT, offset);
    } else {
      if (offset <= 0) {
        // The offset was actually an offset
        offset = -offset;
      } else {
        // The offset was a non-offset derived pointer that
        // had not been converted to an offset yet.
        offset = offset - cast_from_oop<intptr_t>(base);
      }
    }

    // Check that the offset is within the object
    size_t base_size_in_bytes = base->size() * BytesPerWord;
    assert(size_t(offset) < base_size_in_bytes, "Offset must be within object: "
           PTR_FORMAT " object size: " SIZE_FORMAT, offset, base_size_in_bytes);
  }
};

class VerifyStackChunkFrameClosure {
  stackChunkOop _chunk;

public:
  intptr_t* _sp;
  CodeBlob* _cb;
  bool _callee_interpreted;
  int _size;
  int _argsize;
  int _num_oops;
  int _num_frames;
  int _num_interpreted_frames;
  int _num_i2c;

  VerifyStackChunkFrameClosure(stackChunkOop chunk, int num_frames, int size)
    : _chunk(chunk), _sp(nullptr), _cb(nullptr), _callee_interpreted(false),
      _size(size), _argsize(0), _num_oops(0), _num_frames(num_frames), _num_interpreted_frames(0), _num_i2c(0) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& f, const RegisterMapT* map) {
    _sp = f.sp();
    _cb = f.cb();

    int fsize = f.frame_size() - ((f.is_interpreted() == _callee_interpreted) ? _argsize : 0);
    int num_oops = f.num_oops();
    assert(num_oops >= 0, "");

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
    assert(f.pc() != nullptr,
      "young: %d num_frames: %d sp: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT,
      !_chunk->requires_barriers(), _num_frames, p2i(f.sp()), p2i(_chunk->start_address()), p2i(_chunk->bottom_address()));

    if (_num_frames == 0) {
      assert(f.pc() == _chunk->pc(), "");
    }

    if (_num_frames > 0 && !_callee_interpreted && f.is_interpreted()) {
      log_develop_trace(jvmcont)("debug_verify_stack_chunk i2c");
      _num_i2c++;
    }

    StackChunkVerifyOopsClosure oops_closure(_chunk);
    f.iterate_oops(&oops_closure, map);
    assert(oops_closure.count() == num_oops, "oops: %d oopmap->num_oops(): %d", oops_closure.count(), num_oops);

    StackChunkVerifyDerivedPointersClosure derived_oops_closure(_chunk);
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

  assert(oopDesc::is_oop(obj), "");

  stackChunkOop chunk = stackChunkOopDesc::cast(obj);

  assert(chunk->stack_size() >= 0, "");
  assert(chunk->argsize() >= 0, "");
  assert(!chunk->has_bitmap() || chunk->is_gc_mode(), "");

  if (chunk->is_empty()) {
    assert(chunk->argsize() == 0, "");
    assert(chunk->max_size() == 0, "");
  }

  if (!SafepointSynchronize::is_at_safepoint()) {
    assert(oopDesc::is_oop_or_null(chunk->parent()), "");
  }

  const bool concurrent = !SafepointSynchronize::is_at_safepoint() && !Thread::current()->is_Java_thread();
  const bool gc_mode = chunk->is_gc_mode();
  const bool is_last = chunk->parent() == nullptr;
  const bool mixed = chunk->has_mixed_frames();

  // if argsize == 0 and the chunk isn't mixed, the chunk contains the metadata (pc, fp -- frame::sender_sp_offset)
  // for the top frame (below sp), and *not* for the bottom frame
  int size = chunk->stack_size() - chunk->argsize() - chunk->sp();
  assert(size >= 0, "");
  assert((size == 0) == chunk->is_empty(), "");

  const StackChunkFrameStream<chunk_frames::MIXED> first(chunk);
  const bool has_safepoint_stub_frame = first.is_stub();

  VerifyStackChunkFrameClosure closure(chunk,
    has_safepoint_stub_frame ? 1 : 0, // iterate_stack skips the safepoint stub
    has_safepoint_stub_frame ? first.frame_size() : 0);
  chunk->iterate_stack(&closure);

  assert(!chunk->is_empty() || closure._cb == nullptr, "");
  if (closure._cb != nullptr && closure._cb->is_compiled()) {
    assert(chunk->argsize() ==
      (closure._cb->as_compiled_method()->method()->num_stack_arg_slots()*VMRegImpl::stack_slot_size) >>LogBytesPerWord,
      "chunk argsize: %d bottom frame argsize: %d", chunk->argsize(),
      (closure._cb->as_compiled_method()->method()->num_stack_arg_slots()*VMRegImpl::stack_slot_size) >>LogBytesPerWord);
  }

  assert(closure._num_interpreted_frames == 0 || chunk->has_mixed_frames(), "");

  if (!concurrent) {
    assert(closure._size <= size + chunk->argsize() + metadata_words(),
      "size: %d argsize: %d closure.size: %d end sp: " PTR_FORMAT " start sp: %d chunk size: %d",
      size, chunk->argsize(), closure._size, closure._sp - chunk->start_address(), chunk->sp(), chunk->stack_size());
    assert(chunk->argsize() == closure._argsize,
      "chunk->argsize(): %d closure.argsize: %d closure.callee_interpreted: %d",
      chunk->argsize(), closure._argsize, closure._callee_interpreted);

    int max_size = closure._size + closure._num_i2c * align_wiggle();
    assert(chunk->max_size() == max_size,
      "max_size(): %d max_size: %d argsize: %d num_i2c: %d",
      chunk->max_size(), max_size, closure._argsize, closure._num_i2c);

    if (out_size   != nullptr) *out_size   += size;
    if (out_oops   != nullptr) *out_oops   += closure._num_oops;
    if (out_frames != nullptr) *out_frames += closure._num_frames;
    if (out_interpreted_frames != nullptr) *out_interpreted_frames += closure._num_interpreted_frames;
  } else {
    assert(out_size == nullptr && out_oops == nullptr && out_frames == nullptr && out_interpreted_frames == nullptr, "");
  }

  if (chunk->has_bitmap()) {
    assert(chunk->bitmap().size() == align_up((size_t)(chunk->stack_size() << (UseCompressedOops ? 1 : 0)), BitsPerWord),
      "bitmap().size(): %zu stack_size: %d",
      chunk->bitmap().size(), chunk->stack_size());
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
    assert(oop_count == closure._num_oops,
      "bitmap_closure._count: %d closure._num_oops: %d", oop_count, closure._num_oops);
  }

  return true;
}

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
    fr.describe(_values, _frame_no++, get_map(map, f.sp()));
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
#endif

class PrintStackChunkClosure {
  outputStream* _st;

public:
  PrintStackChunkClosure(outputStream* st) : _st(st) {}

  template <chunk_frames frame_kind, typename RegisterMapT>
  bool do_frame(const StackChunkFrameStream<frame_kind>& fs, const RegisterMapT* map) {
    frame f = fs.to_frame();
    _st->print_cr("-- frame sp: " INTPTR_FORMAT " interpreted: %d size: %d argsize: %d",
      p2i(fs.sp()), fs.is_interpreted(), f.frame_size(), fs.is_interpreted() ? 0 : f.compiled_frame_stack_argsize());
    f.print_on(_st);
    const ImmutableOopMap* oopmap = fs.oopmap();
    if (oopmap != nullptr) {
      oopmap->print_on(_st);
      _st->cr();
    }
    return true;
  }
};

void InstanceStackChunkKlass::print_chunk(const stackChunkOop c, bool verbose, outputStream* st) {
  if (c == nullptr) {
    st->print_cr("CHUNK NULL");
    return;
  }
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
    PrintStackChunkClosure closure(st);
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

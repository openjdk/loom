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
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/klass.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/frame.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

class StackChunkFrameStream : public StackObj {
 private:
  intptr_t* _end;
  intptr_t* _sp;
  CodeBlob* _cb;
  mutable const ImmutableOopMap* _oopmap;
  int _oopmap_slot;

 public:
  // Iteration
  StackChunkFrameStream(oop chunk, int gc = false) {
    assert (jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
    _end = jdk_internal_misc_StackChunk::end_address(chunk);
    intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
    _sp = start + get_initial_sp(chunk, gc);
    get_cb();
  }

  bool is_done() const { return _sp >= _end; }

  void next() { _sp += cb()->frame_size(); get_cb(); }

  intptr_t* end() { return _end; }
  void set_end(intptr_t* end) { _end = end; }

  // Query
  intptr_t*      sp() const { return _sp; }
  inline address pc() const { return get_pc(); }

  CodeBlob* cb() const { return _cb; }

  const ImmutableOopMap* oopmap() const {
    if (_oopmap == NULL) get_oopmap();
    return _oopmap;
  }

  StackChunkFrameStream& handle_deopted() {
    if (UNLIKELY(_oopmap_slot < 0)) { // we could have marked frames for deoptimization in thaw_chunk
      CompiledMethod* cm = cb()->as_compiled_method();
      assert (cm->is_deopt_pc(pc()), "");
      address pc1 = *(address*)((address)_sp + cm->orig_pc_offset());
      assert (!cm->is_deopt_pc(pc1), "");
      assert (_cb == ContinuationCodeBlobLookup::find_blob(pc1), "");
      ContinuationCodeBlobLookup::find_blob_and_oopmap(pc1, _oopmap_slot);
      get_oopmap(pc1);
    }
    return *this;
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
    _cb = ContinuationCodeBlobLookup::find_blob_and_oopmap(pc(), _oopmap_slot);
    assert (_cb != NULL && _cb->is_compiled() && _cb->frame_size() > 0, "");
  }

  inline void get_oopmap() const { get_oopmap(pc()); }
  inline const void get_oopmap(address pc) const {
    assert (cb() != NULL, "");
    assert (_oopmap_slot >= 0, "");
    _oopmap = cb()->oop_map_for_slot(_oopmap_slot, pc);
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

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

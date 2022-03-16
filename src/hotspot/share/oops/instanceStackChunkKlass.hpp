/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP
#define SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP

#include "classfile/vmClasses.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/stackChunkOop.hpp"
#include "runtime/handles.hpp"
#include "utilities/macros.hpp"

class frame;
class ClassFileParser;
class ImmutableOopMap;
class VMRegImpl;
typedef VMRegImpl* VMReg;

template <chunk_frames = chunk_frames::MIXED> class StackChunkFrameStream;

// An InstanceStackChunkKlass is a specialization of the InstanceKlass.
// It has a header containing metadata, and a blob containing a stack segment
// (some integral number of stack frames)
//
// A chunk is said to be "mixed" if it contains interpreter frames or stubs
// (which can only be a safepoint stub as the topmost frame). Otherwise, it
// must contain only compiled Java frames.
//
// Interpreter frames in chunks have their internal pointers converted to
// relative offsets from fp. Derived pointers in compiled frames might also
// be converted to relative offsets from their base.

/************************************************

Chunk layout:

                   +-------------------+
                   |                   |
                   |  oop bitmap       |
                   |                   |
                   | ----------------- |
                   |                   |
                   |  [empty]          |
                   |                   |
                  -|===================|
                /  |                   |
               |   | caller stack args |  argsize
               |   |                   |  words
               |   | ----------------- |
               |   |                   |
         ^     |   | frame             |
         |     |   |                   |
         |   size  | ----------------- |
         |   words |                   |
         |     |   | frame             |
         |     |   |                   |
 Address |     |   | ----------------- |
         |     |   |                   |
         |     |   | frame             |
         |     |   |                   |
         |     |   | callee stack args |
         |     |   | ----------------- |<--\
         |     |   | pc                |   |
         |     |   | rbp               |   |
         |     |   |                   |   |
         |     |   | [empty]           |   |
         |     \   |                   |   |
                 - |===================|   |
                   | int maxSize       |   |
                   | long pc           |   |
            header | byte flags        |   |
                   | int argsize       |   |
                   | int sp            +---/
                   | int size          |
                   +-------------------+

************************************************/


class InstanceStackChunkKlass: public InstanceKlass {
private:
  enum class copy_type { CONJOINT, DISJOINT };

public:
  enum class barrier_type { LOAD, STORE };

private:
  friend class VMStructs;
  friend class InstanceKlass;
  friend class stackChunkOopDesc;
  friend class Continuations;
  template <chunk_frames frames> friend class StackChunkFrameStream;
  friend class FixChunkIterateStackClosure;
  friend class MarkMethodsStackClosure;
  friend class OopOopIterateStackClosure;
  template <barrier_type barrier> friend class DoBarriersStackClosure;

public:
  static const KlassID ID = InstanceStackChunkKlassID;

private:
  static int _offset_of_stack;

  InstanceStackChunkKlass(const ClassFileParser& parser);
  static inline int metadata_words(); // size, in words, of frame metadata (e.g. pc and link)
  static inline int align_wiggle();   // size, in words, of maximum shift in frame position due to alignment

public:
  InstanceStackChunkKlass() { assert(DumpSharedSpaces || UseSharedSpaces, "only for CDS"); }

  // Casting from Klass*
  static InstanceStackChunkKlass* cast(Klass* k) {
    assert(InstanceKlass::cast(k)->is_stack_chunk_instance_klass(), "");
    return static_cast<InstanceStackChunkKlass*>(k);
  }

  inline size_t instance_size(size_t stack_size_in_words) const;
  static inline size_t bitmap_size(size_t stack_size_in_words); // in words
  // the *last* bit in the bitmap corresponds to the last word in the stack; this returns the bit index corresponding to the first word
  static inline BitMap::idx_t bit_offset(size_t stack_size_in_words);

  // Returns the size of the instance including the stack data.
  virtual size_t oop_size(oop obj) const override;

  virtual void copy_disjoint(oop obj, HeapWord* to, size_t word_size) override { copy<copy_type::DISJOINT> (obj, to, word_size); }
  virtual void copy_conjoint(oop obj, HeapWord* to, size_t word_size) override { copy<copy_type::CONJOINT>(obj, to, word_size); }


  static void serialize_offsets(class SerializeClosure* f) NOT_CDS_RETURN;

  static void print_chunk(const stackChunkOop chunk, bool verbose, outputStream* st = tty);

  static inline void assert_mixed_correct(stackChunkOop chunk, chunk_frames frame_kind) PRODUCT_RETURN;
#ifndef PRODUCT
  void oop_print_on(oop obj, outputStream* st) override;
#endif

  static bool verify(oop obj, size_t* out_size = NULL, int* out_oops = NULL,
                     int* out_frames = NULL, int* out_interpreted_frames = NULL) NOT_DEBUG({ return true; });

  // Stack offset is an offset into the Heap
  static HeapWord* start_of_stack(oop obj) { return (HeapWord*)(cast_from_oop<intptr_t>(obj) + offset_of_stack()); }
  static inline HeapWord* start_of_bitmap(oop obj);

  static int offset_of_stack() { return _offset_of_stack; }
  static void init_offset_of_stack() {
    // Cache the offset of the static fields in the Class instance
    assert(_offset_of_stack == 0, "once");
    _offset_of_stack = InstanceStackChunkKlass::cast(vmClasses::StackChunk_klass())->size_helper() << LogHeapWordSize;
  }


  template<chunk_frames frames = chunk_frames::MIXED>
  static int count_frames(stackChunkOop chunk);

  // Oop fields (and metadata) iterators
  //
  // The InstanceClassLoaderKlass iterators also visit the CLD pointer (or mirror of anonymous klasses.)

  // Forward iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate(oop obj, OopClosureType* closure);

  // Reverse iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_reverse(oop obj, OopClosureType* closure);

  // Bounded range iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr);

public:
  static void relativize_chunk(stackChunkOop chunk);

  template <barrier_type>
  static void do_barriers(stackChunkOop chunk);

  template <barrier_type, chunk_frames frames, typename RegisterMapT>
  inline static void do_barriers(stackChunkOop chunk, const StackChunkFrameStream<frames>& f, const RegisterMapT* map);

  template <typename RegisterMapT>
  static void fix_thawed_frame(stackChunkOop chunk, const frame& f, const RegisterMapT* map);

  static void build_bitmap(stackChunkOop chunk);

private:
  static size_t bitmap_size_in_bits(size_t stack_size_in_words) { return stack_size_in_words << (UseCompressedOops ? 1 : 0); }

  template<copy_type disjoint> size_t copy(oop obj, HeapWord* to, size_t word_size);

  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_header(stackChunkOop chunk, OopClosureType* closure);

  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_header_bounded(stackChunkOop chunk, OopClosureType* closure, MemRegion mr);

  template <class OopClosureType>
  inline void oop_oop_iterate_stack(stackChunkOop chunk, OopClosureType* closure);

  template <class OopClosureType>
  inline void oop_oop_iterate_stack_bounded(stackChunkOop chunk, OopClosureType* closure, MemRegion mr);

  template <class OopClosureType>
  inline void oop_oop_iterate_stack_helper(stackChunkOop chunk, OopClosureType* closure, intptr_t* start, intptr_t* end);

  void mark_methods(stackChunkOop chunk, OopIterateClosure* cl);

  template <chunk_frames frames, class StackChunkFrameClosureType>
  static inline void iterate_stack(stackChunkOop obj, StackChunkFrameClosureType* closure);

  void oop_oop_iterate_stack_slow(stackChunkOop chunk, OopIterateClosure* closure, MemRegion mr);

  template <chunk_frames frames, typename RegisterMapT>
  static void relativize_derived_pointers(const StackChunkFrameStream<frames>& f, const RegisterMapT* map);

  template <barrier_type barrier, chunk_frames frames = chunk_frames::MIXED, typename RegisterMapT>
  static void do_barriers0(stackChunkOop chunk, const StackChunkFrameStream<frames>& f, const RegisterMapT* map);

  template <copy_alignment alignment> inline static void copy_from_stack_to_chunk(void* from, void* to, size_t size);
  template <copy_alignment alignment> inline static void copy_from_chunk_to_stack(void* from, void* to, size_t size);
};

template <chunk_frames frame_kind>
class StackChunkFrameStream : public StackObj {
private:
  intptr_t* _end;
  intptr_t* _sp;
  intptr_t* _unextended_sp; // used only when mixed
  CodeBlob* _cb;
  mutable const ImmutableOopMap* _oopmap;

#ifndef PRODUCT
  stackChunkOop _chunk;
  int _index;
#endif

#ifdef ASSERT
  int _has_stub;
#endif

public:
  StackChunkFrameStream() { NOT_PRODUCT(_chunk = nullptr; _index = -1;) DEBUG_ONLY(_has_stub = false;) }
  inline StackChunkFrameStream(stackChunkOop chunk, bool gc = false);
  inline StackChunkFrameStream(stackChunkOop chunk, const frame& f);

  bool is_done() const { return _sp >= _end; }
  bool is_last() const { return next_sp() >= _end; }

  intptr_t* end() { return _end; }
  void set_end(intptr_t* end) { _end = end; }

  // Query
  intptr_t* end() const { return _end; }

  intptr_t*        sp() const  { return _sp; }
  inline address   pc() const  { return get_pc(); }
  inline intptr_t* fp() const;
  inline intptr_t* unextended_sp() const { return frame_kind == chunk_frames::MIXED ? _unextended_sp : _sp; }
  NOT_PRODUCT(int index() { return _index; })
  inline address orig_pc() const;

  inline bool is_interpreted() const;
  inline bool is_stub() const;
  inline bool is_compiled() const;
  CodeBlob* cb() const { return _cb; }
  const ImmutableOopMap* oopmap() const { if (_oopmap == NULL) get_oopmap(); return _oopmap; }
  inline int frame_size() const;
  inline int stack_argsize() const;
  inline int num_oops() const;

  inline void initialize_register_map(RegisterMap* map);
  template <typename RegisterMapT> inline void next(RegisterMapT* map);

  template <typename RegisterMapT> inline void update_reg_map(RegisterMapT* map);

  void handle_deopted() const;

  inline int to_offset(stackChunkOop chunk) const { assert (!is_done(), ""); return _sp - chunk->start_address(); }

  inline frame to_frame() const;

#ifdef ASSERT
  bool is_in_frame(void* p) const;
  bool is_deoptimized() const;
  template <typename RegisterMapT> bool is_in_oops(void* p, const RegisterMapT* map) const;
#endif

  void print_on(outputStream* st) const PRODUCT_RETURN;

 private:
  inline address get_pc() const;
  inline void get_cb();

  inline intptr_t* next_sp() const;
  inline int interpreter_frame_size() const;
  inline int interpreter_frame_num_oops() const;
  inline int interpreter_frame_stack_argsize() const;
  inline void next_for_interpreter_frame();
  inline intptr_t* next_sp_for_interpreter_frame() const;
  inline intptr_t* unextended_sp_for_interpreter_frame() const;
  inline intptr_t* derelativize(int offset) const;
  inline void get_oopmap() const;
  inline void get_oopmap(address pc, int oopmap_slot) const;

  template <typename RegisterMapT> inline void update_reg_map_pd(RegisterMapT* map);

  template <typename RegisterMapT>
  inline void* reg_to_loc(VMReg reg, const RegisterMapT* map) const;

public:
  template <class OopClosureType, class RegisterMapT>
  inline void iterate_oops(OopClosureType* closure, const RegisterMapT* map) const;
  template <class DerivedOopClosureType, class RegisterMapT>
  inline void iterate_derived_pointers(DerivedOopClosureType* closure, const RegisterMapT* map) const;
};

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP

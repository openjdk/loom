/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_STACKCHUNKOOP_INLINE_HPP
#define SHARE_OOPS_STACKCHUNKOOP_INLINE_HPP

#include "oops/stackChunkOop.hpp"

#include "memory/memRegion.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/registerMap.hpp"
#include "utilities/macros.hpp"

DEF_HANDLE_CONSTR(stackChunk, is_stackChunk_noinline)

inline stackChunkOopDesc* stackChunkOopDesc::parent() const         { return (stackChunkOopDesc*)(oopDesc*)jdk_internal_vm_StackChunk::parent(as_oop()); }
inline void stackChunkOopDesc::set_parent(stackChunkOopDesc* value) { jdk_internal_vm_StackChunk::set_parent(this, (oop)value); }
template<typename P> inline void stackChunkOopDesc::set_parent_raw(oop value) { jdk_internal_vm_StackChunk::set_parent_raw<P>(this, value); }
template<typename P> inline bool stackChunkOopDesc::is_parent_null() const    { return jdk_internal_vm_StackChunk::is_parent_null<P>(as_oop()); }
inline int stackChunkOopDesc::stack_size() const         { return jdk_internal_vm_StackChunk::size(as_oop()); }
inline int stackChunkOopDesc::sp() const                 { return jdk_internal_vm_StackChunk::sp(as_oop()); }
inline void stackChunkOopDesc::set_sp(int value)         { jdk_internal_vm_StackChunk::set_sp(this, value); }
inline address stackChunkOopDesc::pc() const             { return (address)jdk_internal_vm_StackChunk::pc(as_oop()); }
inline void stackChunkOopDesc::set_pc(address value)     { jdk_internal_vm_StackChunk::set_pc(this, (jlong)value); }
inline int stackChunkOopDesc::argsize() const            { return jdk_internal_vm_StackChunk::argsize(as_oop()); }
inline void stackChunkOopDesc::set_argsize(int value)    { jdk_internal_vm_StackChunk::set_argsize(as_oop(), value); }
inline uint8_t stackChunkOopDesc::flags() const          { return (uint8_t)jdk_internal_vm_StackChunk::flags(as_oop()); }
inline void stackChunkOopDesc::set_flags(uint8_t value)  { jdk_internal_vm_StackChunk::set_flags(this, (jbyte)value); }
inline int stackChunkOopDesc::max_size() const           { return (int)jdk_internal_vm_StackChunk::maxSize(as_oop()); }
inline void stackChunkOopDesc::set_max_size(int value)   { jdk_internal_vm_StackChunk::set_maxSize(this, (jint)value); }
inline int stackChunkOopDesc::numFrames() const          { return jdk_internal_vm_StackChunk::numFrames(as_oop()); }
inline void stackChunkOopDesc::set_numFrames(int value)  { jdk_internal_vm_StackChunk::set_numFrames(this, value); }
inline int stackChunkOopDesc::numOops() const            { return jdk_internal_vm_StackChunk::numOops(as_oop()); }
inline void stackChunkOopDesc::set_numOops(int value)    { jdk_internal_vm_StackChunk::set_numOops(this, value); }
inline int stackChunkOopDesc::gc_sp() const              { return jdk_internal_vm_StackChunk::gc_sp(as_oop()); }
inline void stackChunkOopDesc::set_gc_sp(int value)      { jdk_internal_vm_StackChunk::set_gc_sp(this, value); }
inline uint64_t stackChunkOopDesc::mark_cycle() const         { return (uint64_t)jdk_internal_vm_StackChunk::mark_cycle(as_oop()); }
inline void stackChunkOopDesc::set_mark_cycle(uint64_t value) { jdk_internal_vm_StackChunk::set_mark_cycle(this, (jlong)value); }

inline void stackChunkOopDesc::set_cont(oop value) { jdk_internal_vm_StackChunk::set_cont(this, value); }
template<typename P> inline void stackChunkOopDesc::set_cont_raw(oop value)   { jdk_internal_vm_StackChunk::set_cont_raw<P>(this, value); }
inline oop stackChunkOopDesc::cont() const  { return UseCompressedOops ? cont<narrowOop>() : cont<oop>(); /* jdk_internal_vm_StackChunk::cont(as_oop()); */ }
template<typename P> inline oop stackChunkOopDesc::cont() const { 
  // this is a special field used to detect GC processing status (see should_fix) and so we don't want to invoke a barrier directly on it
  oop obj = jdk_internal_vm_StackChunk::cont_raw<P>(as_oop()); 
  obj = (oop)NativeAccess<>::oop_load(&obj);
  return obj;
}

inline int stackChunkOopDesc::bottom() const { return stack_size() - argsize(); }

inline intptr_t* stackChunkOopDesc::start_address() const { return (intptr_t*)InstanceStackChunkKlass::start_of_stack(as_oop()); }
inline intptr_t* stackChunkOopDesc::end_address() const { return start_address() + stack_size(); }
inline intptr_t* stackChunkOopDesc::bottom_address() const { return start_address() + bottom(); }
inline intptr_t* stackChunkOopDesc::sp_address()  const { return start_address() + sp(); }

inline int stackChunkOopDesc::to_offset(intptr_t* p) const {
  assert(is_in_chunk(p) || (p >= start_address() && (p - start_address()) <= stack_size() + InstanceStackChunkKlass::metadata_words()), 
    "p: " INTPTR_FORMAT " start: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(p), p2i(start_address()), p2i(bottom_address()));
  return p - start_address();
}

inline intptr_t* stackChunkOopDesc::from_offset(int offset) const {
  assert(offset <= stack_size(), "");
  return start_address() + offset;
}

inline bool stackChunkOopDesc::is_empty() const {
  assert (is_stackChunk(), "");
  // assert ((sp() < end()) || (sp() >= stack_size()), "");
  return sp() >= stack_size() - argsize();
}

inline bool stackChunkOopDesc::is_in_chunk(void* p) const {
  assert (is_stackChunk(), "");
  HeapWord* start = (HeapWord*)start_address();
  HeapWord* end = start + stack_size();
  return (HeapWord*)p >= start && (HeapWord*)p < end;
}

bool stackChunkOopDesc::is_usable_in_chunk(void* p) const {
  assert (is_stackChunk(), "");
  HeapWord* start = (HeapWord*)start_address() + sp() - frame::sender_sp_offset;
  HeapWord* end = start + stack_size();
  return (HeapWord*)p >= start && (HeapWord*)p < end;
}

inline bool stackChunkOopDesc::is_flag(uint8_t flag) const {
  return (flags() & flag) != 0;
}
inline bool stackChunkOopDesc::is_non_null_and_flag(uint8_t flag) const {
  return this != nullptr && is_flag(flag);
}
inline void stackChunkOopDesc::set_flag(uint8_t flag, bool value) {
  uint32_t flags = this->flags();
  set_flags((uint8_t)(value ? flags |= flag : flags &= ~flag));
}
inline void stackChunkOopDesc::clear_flags() {
  set_flags(0);
}
inline bool stackChunkOopDesc::requires_barriers() const { 
  return Universe::heap()->requires_barriers(as_oop());
}

template <typename OopT>
inline static bool is_oop_fixed(oop obj, int offset) {
  OopT value = *obj->obj_field_addr<OopT>(offset);
  intptr_t before = *(intptr_t*)&value;
  intptr_t after  = cast_from_oop<intptr_t>(NativeAccess<>::oop_load(&value));
  // tty->print_cr(">>> fixed %d: " INTPTR_FORMAT " -> " INTPTR_FORMAT, before == after, before, after);
  return before == after;
}

template <typename OopT, bool concurrent_gc>
inline bool stackChunkOopDesc::should_fix() const {
  if (UNLIKELY(is_gc_mode())) return true;
  // the last oop traversed in this object -- see InstanceStackChunkKlass::oop_oop_iterate
  if (concurrent_gc) return !is_oop_fixed<OopT>(as_oop(), jdk_internal_vm_StackChunk::cont_offset());
  return false;
}

inline bool stackChunkOopDesc::has_mixed_frames() const         { return is_flag(FLAG_HAS_INTERPRETED_FRAMES); }
inline void stackChunkOopDesc::set_has_mixed_frames(bool value) { set_flag(FLAG_HAS_INTERPRETED_FRAMES, value); }
inline bool stackChunkOopDesc::is_gc_mode() const               { return is_flag(FLAG_GC_MODE); }
inline void stackChunkOopDesc::set_gc_mode(bool value)          { set_flag(FLAG_GC_MODE, value); }
inline bool stackChunkOopDesc::has_bitmap() const               { return is_flag(FLAG_HAS_BITMAP); }
inline void stackChunkOopDesc::set_has_bitmap(bool value)       { set_flag(FLAG_HAS_BITMAP, value); assert (!value || UseChunkBitmaps, ""); }

inline void stackChunkOopDesc::reset_counters() {
  set_numFrames(-1);
  set_numOops(-1);
}

inline intptr_t* stackChunkOopDesc::relative_base() const {
  // we relativize with respect to end rather than start because GC might compact the chunk
  return end_address() + InstanceStackChunkKlass::metadata_words();
}

inline intptr_t* stackChunkOopDesc::derelativize_address(int offset) const {
  intptr_t* base = relative_base();
  intptr_t* p = base - offset;
  // tty->print_cr(">>> derelativize_address: %d -> %p (base: %p)", offset, p, base);
  assert (start_address() <= p && p <= base, "");
  return p;
}

inline int stackChunkOopDesc::relativize_address(intptr_t* p) const {
  intptr_t* base = relative_base();
  intptr_t offset = base - p;
  // tty->print_cr(">>> relativize_address: %p -> %ld (base: %p)", p, offset, base);
  assert (start_address() <= p && p <= base, "");
  assert (0 <= offset && offset <= std::numeric_limits<int>::max(), "");
  return offset;
}

inline void stackChunkOopDesc::relativize_frame(frame& fr) const {
  fr.set_offset_sp(relativize_address(fr.sp()));
  fr.set_offset_unextended_sp(relativize_address(fr.unextended_sp()));
  relativize_frame_pd(fr);
}

inline void stackChunkOopDesc::derelativize_frame(frame& fr) const {
  fr.set_sp(derelativize_address(fr.offset_sp()));
  fr.set_unextended_sp(derelativize_address(fr.offset_unextended_sp()));
  derelativize_frame_pd(fr);
}

inline frame stackChunkOopDesc::relativize(frame fr)   const { relativize_frame(fr);   return fr; }
inline frame stackChunkOopDesc::derelativize(frame fr) const { derelativize_frame(fr); return fr; }

inline int stackChunkOopDesc::relativize_usp_offset(const frame& fr, const int usp_offset_in_bytes) const {
  assert (fr.is_compiled_frame() || fr.cb()->is_safepoint_stub(), "");
  assert (is_in_chunk(fr.unextended_sp()), "");

  intptr_t* base = fr.real_fp(); // equal to the caller's sp
  intptr_t* loc = (intptr_t*)((address)fr.unextended_sp() + usp_offset_in_bytes);
  assert (base > loc, "");
  int res = (int)(base - loc);
  // tty->print_cr(">>> relativize_usp_offset: %d -> %d -- address %p", usp_offset_in_bytes, res, loc); fr.print_on<true>(tty);
  return res;
}

inline address stackChunkOopDesc::reg_to_location(const frame& fr, const RegisterMap* map, VMReg reg) const {
  assert (fr.is_compiled_frame(), "");
  assert (map != nullptr && map->stack_chunk() == as_oop(), "");

  // the offsets are saved in the map after going through relativize_usp_offset, so they are sp - loc, in words
  intptr_t offset = (intptr_t)map->location(reg, nullptr); // see usp_offset_to_index for the chunk case
  intptr_t* base = derelativize_address(fr.offset_sp());
  // tty->print_cr(">>> reg_to_location: %s -> %ld -- address: %p", reg->name(), offset, base - offset); fr.print_on(tty);
  return (address)(base - offset);
}

inline address stackChunkOopDesc::usp_offset_to_location(const frame& fr, const int usp_offset_in_bytes) const {
  assert (fr.is_compiled_frame(), "");
  // tty->print_cr(">>> usp_offset_to_location"); fr.print_on<true>(tty);
  return (address)derelativize_address(fr.offset_unextended_sp()) + usp_offset_in_bytes;
}

inline Method* stackChunkOopDesc::interpreter_frame_method(const frame& fr) {
  // tty->print_cr(">>> interpreter_frame_method"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_method();
}

inline address stackChunkOopDesc::interpreter_frame_bcp(const frame& fr) {
  // tty->print_cr(">>> interpreter_frame_bcp"); derelativize(fr).print_on<true>(tty);
  return derelativize(fr).interpreter_frame_bcp();
}

inline intptr_t* stackChunkOopDesc::interpreter_frame_expression_stack_at(const frame& fr, int index) const {
  // tty->print_cr(">>> interpreter_frame_expression_stack_at"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_expression_stack_at<true>(index);
}

inline intptr_t* stackChunkOopDesc::interpreter_frame_local_at(const frame& fr, int index) const {
  // tty->print_cr(">>> interpreter_frame_local_at"); fr.print_on<true>(tty);
  return derelativize(fr).interpreter_frame_local_at<true>(index);
}

template <bool dword_aligned>
inline void stackChunkOopDesc::copy_from_stack_to_chunk(intptr_t* from, intptr_t* to, int size) {
  log_develop_trace(jvmcont)("Chunk bounds: " INTPTR_FORMAT "(%d) - " INTPTR_FORMAT "(%d) (%d words, %d bytes)",
    p2i(start_address()), to_offset(start_address()), p2i(end_address()), to_offset(end_address() - 1) + 1, stack_size(), stack_size() << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying from v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d words, %d bytes)", p2i(from), p2i(from + size), size, size << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying to h: " INTPTR_FORMAT "(%ld,%ld) - " INTPTR_FORMAT "(%ld,%ld) (%d words, %d bytes)", 
    p2i(to), to - start_address(), relative_base() - to, p2i(to + size), to + size - start_address(), relative_base() - (to + size), size, size << LogBytesPerWord);

  assert (to >= start_address(), "to: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(to), p2i(start_address()));
  assert (to + size <= end_address(), "to + size: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(to + size), p2i(end_address()));

  InstanceStackChunkKlass::copy_from_stack_to_chunk<dword_aligned>(from, to, size);
}

template <bool dword_aligned>
inline void stackChunkOopDesc::copy_from_chunk_to_stack(intptr_t* from, intptr_t* to, int size) {
  log_develop_trace(jvmcont)("Copying from h: " INTPTR_FORMAT "(%ld,%ld) - " INTPTR_FORMAT "(%ld,%ld) (%d words, %d bytes)", 
    p2i(from), from - start_address(), relative_base() - from, p2i(from + size), from + size - start_address(), relative_base() - (from + size), size, size << LogBytesPerWord);
  log_develop_trace(jvmcont)("Copying to v: " INTPTR_FORMAT " - " INTPTR_FORMAT " (%d words, %d bytes)", p2i(to), p2i(to + size), size, size << LogBytesPerWord);

  assert (from >= start_address(), "from: " INTPTR_FORMAT " start: " INTPTR_FORMAT, p2i(from), p2i(start_address()));
  assert (from + size <= end_address(), "from + size: " INTPTR_FORMAT " end: " INTPTR_FORMAT, p2i(from + size), p2i(end_address()));

  InstanceStackChunkKlass::copy_from_chunk_to_stack<dword_aligned>(from, to, size);
}

inline BitMapView stackChunkOopDesc::bitmap() const {
  assert (has_bitmap(), "");
  size_t size_in_bits = InstanceStackChunkKlass::bitmap_size(stack_size()) << LogBitsPerWord;
#ifdef ASSERT
  BitMapView bm((BitMap::bm_word_t*)InstanceStackChunkKlass::start_of_bitmap(as_oop()), size_in_bits);
  assert (bm.size() == size_in_bits, "bm.size(): %zu size_in_bits: %zu", bm.size(), size_in_bits);
  assert (bm.size_in_words() == (size_t)InstanceStackChunkKlass::bitmap_size(stack_size()), "bm.size_in_words(): %zu InstanceStackChunkKlass::bitmap_size(stack_size()): %d", bm.size_in_words(), InstanceStackChunkKlass::bitmap_size(stack_size()));
  bm.verify_range(bit_index_for(start_address()), bit_index_for(end_address()));
#endif
  return BitMapView((BitMap::bm_word_t*)InstanceStackChunkKlass::start_of_bitmap(as_oop()), size_in_bits);
}

inline BitMap::idx_t stackChunkOopDesc::bit_offset() const {
  return InstanceStackChunkKlass::bit_offset(stack_size());
}

inline BitMap::idx_t stackChunkOopDesc::bit_index_for(intptr_t* p) const {
  return UseCompressedOops ? bit_index_for((narrowOop*)p) : bit_index_for((oop*)p);
}

template <typename OopT>
inline BitMap::idx_t stackChunkOopDesc::bit_index_for(OopT* p) const {
  return bit_offset() + (p - (OopT*)start_address());
}

inline intptr_t* stackChunkOopDesc::address_for_bit(BitMap::idx_t index) const {
  return UseCompressedOops ? (intptr_t*)address_for_bit<narrowOop>(index) : (intptr_t*)address_for_bit<oop>(index);
}

template <typename OopT>
inline OopT* stackChunkOopDesc::address_for_bit(BitMap::idx_t index) const {
  return (OopT*)start_address() + (index - bit_offset());
}

template <class StackChunkFrameClosureType>
inline void stackChunkOopDesc::iterate_stack(StackChunkFrameClosureType* closure) {
  has_mixed_frames() ? InstanceStackChunkKlass::iterate_stack<true >(this, closure)
                     : InstanceStackChunkKlass::iterate_stack<false>(this, closure);
}

inline MemRegion stackChunkOopDesc::range() {
  return MemRegion((HeapWord*)this, size());
}

#endif // SHARE_OOPS_STACKCHUNKOOP_INLINE_HPP

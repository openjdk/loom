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

#ifndef CPU_S390_CONTINUATION_S390_INLINE_HPP
#define CPU_S390_CONTINUATION_S390_INLINE_HPP

#include "oops/instanceStackChunkKlass.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"

// TODO: Implement
const int ContinuationHelper::frame_metadata = 0;
const int ContinuationHelper::align_wiggle = 0;

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  Unimplemented();
  return NULL;
}

inline int ContinuationHelper::frame_align_words(int size) {
  Unimplemented();
  return 0;
}

inline intptr_t* ContinuationHelper::frame_align_pointer(intptr_t* sp) {
  Unimplemented();
  return NULL;
}

template<typename FKind>
inline void ContinuationHelper::update_register_map(const frame& f, RegisterMap* map) {
  Unimplemented();
}

inline void ContinuationHelper::update_register_map_with_callee(const frame& f, RegisterMap* map) {
  Unimplemented();
}

inline void ContinuationHelper::push_pd(const frame& f) {
  Unimplemented();
}

frame ContinuationEntry::to_frame() const {
  Unimplemented();
  return frame();
}

void ContinuationEntry::update_register_map(RegisterMap* map) const {
  Unimplemented();
}

void ContinuationHelper::set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* cont) {
  Unimplemented();
}

void ContinuationHelper::set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp) {
  Unimplemented();
}

inline void Freeze::set_top_frame_metadata_pd(const frame& hf) {
  Unimplemented();
}

template<typename FKind>
inline frame Freeze::sender(const frame& f) {
  Unimplemented();
  return frame();
}

template<typename FKind> frame Freeze::new_hframe(frame& f, frame& caller) {
  Unimplemented();
  return frame();
}

inline void Freeze::relativize_interpreted_frame_metadata(const frame& f, const frame& hf) {
  Unimplemented();
}

inline void Freeze::patch_pd(frame& hf, const frame& caller) {
  Unimplemented();
}

inline void Freeze::patch_chunk_pd(intptr_t* vsp, intptr_t* hsp) {
  Unimplemented();
}

inline frame Thaw::new_entry_frame() {
  Unimplemented();
  return frame();
}

template<typename FKind> frame Thaw::new_frame(const frame& hf, frame& caller, bool bottom) {
  Unimplemented();
  return frame();
}

inline void Thaw::set_interpreter_frame_bottom(const frame& f, intptr_t* bottom) {
  Unimplemented();
}

inline void Thaw::derelativize_interpreted_frame_metadata(const frame& hf, const frame& f) {
  Unimplemented();
}

inline intptr_t* Thaw::align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom) {
  Unimplemented();
  return NULL;
}

inline void Thaw::patch_pd(frame& f, const frame& caller) {
  Unimplemented();
}

intptr_t* Thaw::push_interpreter_return_frame(intptr_t* sp) {
  Unimplemented();
  return NULL;
}

void Thaw::patch_chunk_pd(intptr_t* sp) {
  Unimplemented();
}

inline void Thaw::prefetch_chunk_pd(void* start, int size) {
  Unimplemented();
}

#endif // CPU_S390_CONTINUATION_S390_INLINE_HPP

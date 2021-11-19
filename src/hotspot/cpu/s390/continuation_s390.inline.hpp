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

#ifndef CPU_S390_CONTINUATION_S390_INLINE_HPP
#define CPU_S390_CONTINUATION_S390_INLINE_HPP

#include "oops/instanceStackChunkKlass.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"

// TODO: Implement
const int ContinuationHelper::frame_metadata = 0;
const int ContinuationHelper::align_wiggle = 0;

#ifdef ASSERT
bool Frame::assert_frame_laid_out(frame f) {
  Unimplemented();
  return false;
}
#endif

inline intptr_t** Frame::callee_link_address(const frame& f) {
  Unimplemented();
  return NULL;
}

template<typename FKind>
static inline intptr_t* real_fp(const frame& f) {
  Unimplemented();
  return NULL;
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  Unimplemented();
  return NULL;
}

inline address* Interpreted::return_pc_address(const frame& f) {
  Unimplemented();
  return NULL;
}

template <bool relative>
void Interpreted::patch_sender_sp(frame& f, intptr_t* sp) {
  Unimplemented();
}

inline address* Frame::return_pc_address(const frame& f) {
  Unimplemented();
  return NULL;
}

inline address Frame::real_pc(const frame& f) {
  Unimplemented();
  return NULL;
}

inline void Frame::patch_pc(const frame& f, address pc) {
  Unimplemented();
}

inline intptr_t* Interpreted::frame_top(const frame& f, InterpreterOopMap* mask) { // inclusive; this will be copied with the frame
  Unimplemented();
  return NULL;
}

template <bool relative>
inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  Unimplemented();
  return NULL;
}

inline intptr_t* Interpreted::frame_top(const frame& f, int callee_argsize, bool callee_interpreted) {
  Unimplemented();
  return NULL;
}

template<typename FKind, typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, const frame& f) {
  Unimplemented();
}

template<typename RegisterMapT>
inline void ContinuationHelper::update_register_map_with_callee(RegisterMapT* map, const frame& f) {
  Unimplemented();
}

inline void ContinuationHelper::push_pd(const frame& f) {
  Unimplemented();
}

// creates the yield stub frame faster than JavaThread::last_frame
inline frame ContinuationHelper::last_frame(JavaThread* thread) {
  Unimplemented();
  return frame();
}

frame ContinuationEntry::to_frame() {
  Unimplemented();
  return frame();
}

void ContinuationEntry::update_register_map(RegisterMap* map) {
  Unimplemented();
}

void ContinuationHelper::set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* cont) {
  Unimplemented();
}

void ContinuationHelper::set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp) {
  Unimplemented();
}

template <typename ConfigT>
inline void Freeze<ConfigT>::set_top_frame_metadata_pd(const frame& hf) {
  Unimplemented();
}

template <typename ConfigT>
inline intptr_t* Freeze<ConfigT>::align_bottom(intptr_t* bottom, int argsize) {
  Unimplemented();
  return NULL;
}

template <typename ConfigT>
template<typename FKind>
inline frame Freeze<ConfigT>::sender(const frame& f) {
  Unimplemented();
  return frame();
}

template <typename ConfigT>
template<typename FKind> frame Freeze<ConfigT>::new_hframe(frame& f, frame& caller) {
  Unimplemented();
  return frame();
}

template <typename ConfigT>
inline void Freeze<ConfigT>::relativize_interpreted_frame_metadata(const frame& f, const frame& hf) {
  Unimplemented();
}

template <typename ConfigT>
template <typename FKind, bool bottom>
inline void Freeze<ConfigT>::patch_pd(frame& hf, const frame& caller) {
  Unimplemented();
}

template <typename ConfigT>
inline void Freeze<ConfigT>::patch_chunk_pd(intptr_t* vsp, intptr_t* hsp) {
  Unimplemented();
}

template <typename ConfigT>
inline frame Thaw<ConfigT>::new_entry_frame() {
  Unimplemented();
  return frame();
}

template <typename ConfigT>
template<typename FKind> frame Thaw<ConfigT>::new_frame(const frame& hf, frame& caller, bool bottom) {
  Unimplemented();
  return frame();
}

template <typename ConfigT>
inline void Thaw<ConfigT>::set_interpreter_frame_bottom(const frame& f, intptr_t* bottom) {
  Unimplemented();
}

template <typename ConfigT>
inline void Thaw<ConfigT>::derelativize_interpreted_frame_metadata(const frame& hf, const frame& f) {
  Unimplemented();
}

template <typename ConfigT>
inline intptr_t* Thaw<ConfigT>::align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom) {
  Unimplemented();
  return NULL;
}

template <typename ConfigT>
template<typename FKind, bool bottom>
inline void Thaw<ConfigT>::patch_pd(frame& f, const frame& caller) {
  Unimplemented();
}

template <typename ConfigT>
intptr_t* Thaw<ConfigT>::push_interpreter_return_frame(intptr_t* sp) {
  Unimplemented();
  return NULL;
}

template <typename ConfigT>
void Thaw<ConfigT>::patch_chunk_pd(intptr_t* sp) {
  Unimplemented();
}

template <typename ConfigT>
inline void Thaw<ConfigT>::prefetch_chunk_pd(void* start, int size) {
  Unimplemented();
}

template <typename ConfigT>
inline intptr_t* Thaw<ConfigT>::align_chunk(intptr_t* vsp) {
  Unimplemented();
  return NULL;
}

#endif // CPU_S390_CONTINUATION_S390_INLINE_HPP

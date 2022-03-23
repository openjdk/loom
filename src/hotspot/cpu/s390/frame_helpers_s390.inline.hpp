/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_S390_FRAME_HELPERS_S390_INLINE_HPP
#define CPU_S390_FRAME_HELPERS_S390_INLINE_HPP


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

inline address* Interpreted::return_pc_address(const frame& f) {
  Unimplemented();
  return NULL;
}

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

inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  Unimplemented();
  return NULL;
}

inline intptr_t* Interpreted::frame_top(const frame& f, int callee_argsize, bool callee_interpreted) {
  Unimplemented();
  return NULL;
}

#endif // CPU_S390_FRAME_HELPERS_S390_INLINE_HPP

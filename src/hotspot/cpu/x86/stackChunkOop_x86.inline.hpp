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

#ifndef CPU_X86_STACKCHUNKOOP_X86_INLINE_HPP
#define CPU_X86_STACKCHUNKOOP_X86_INLINE_HPP

#include "runtime/frame.inline.hpp"

inline void stackChunkOopDesc::relativize_frame_pd(frame& fr) const {
  if (fr.is_interpreted_frame()) {
    fr.set_offset_fp(relativize_address(fr.fp()));
  }
}

inline void stackChunkOopDesc::derelativize_frame_pd(frame& fr) const {
  if (fr.is_interpreted_frame()) {
    fr.set_fp(derelativize_address(fr.offset_fp()));
  }
}

#endif // CPU_X86_STACKCHUNKOOP_X86_INLINE_HPP

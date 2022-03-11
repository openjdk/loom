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
 */

#ifndef SHARE_GC_Z_ZCOLLECTEDHEAP_INLINE_HPP
#define SHARE_GC_Z_ZCOLLECTEDHEAP_INLINE_HPP

#include "gc/z/zCollectedHeap.hpp"

#include "gc/z/zAddress.inline.hpp"
#include "gc/z/zHeap.inline.hpp"

inline bool ZCollectedHeap::requires_barriers(stackChunkOop obj) const {
  uintptr_t* cont_addr = obj->field_addr<uintptr_t>(jdk_internal_vm_StackChunk::cont_offset());

  if (!_heap.is_allocating(cast_from_oop<uintptr_t>(obj))) {
    // An object that isn't allocating, is visible from GC tracing. Such
    // stack chunks require barriers.
    return true;
  }

  if (!ZAddress::is_good_or_null(*cont_addr)) {
    // If a chunk is allocated after a GC started, but before relocate start
    // we can have an allocating chunk that isn't deeply good. That means that
    // the contained oops might be bad and require GC barriers.
    return true;
  }

  // The chunk is allocating and its pointers are good. This chunk needs no
  // GC barriers
  return false;
}

#endif // SHARE_GC_Z_ZCOLLECTEDHEAP_INLINE_HPP

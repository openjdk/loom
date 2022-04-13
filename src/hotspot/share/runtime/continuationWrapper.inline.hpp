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

#ifndef SHARE_VM_RUNTIME_CONTINUATIONWRAPPER_INLINE_HPP
#define SHARE_VM_RUNTIME_CONTINUATIONWRAPPER_INLINE_HPP

#include "runtime/continuationWrapper.hpp"

#include "classfile/javaClasses.inline.hpp"
#include "oops/oop.inline.hpp"

inline oop ContinuationWrapper::parent() {
  return jdk_internal_vm_Continuation::parent(_continuation);
}

inline bool ContinuationWrapper::is_preempted() {
  return jdk_internal_vm_Continuation::is_preempted(_continuation);
}

inline void ContinuationWrapper::set_preempted(bool value) {
  jdk_internal_vm_Continuation::set_preempted(_continuation, value);
}

inline void ContinuationWrapper::read() {
  _tail  = jdk_internal_vm_Continuation::tail(_continuation);
}

inline void ContinuationWrapper::write() {
  assert(oopDesc::is_oop(_continuation), "bad oop");
  assert(oopDesc::is_oop_or_null(_tail), "bad oop");
  jdk_internal_vm_Continuation::set_tail(_continuation, _tail);
}

inline stackChunkOop ContinuationWrapper::nonempty_chunk(stackChunkOop chunk) const {
  while (chunk != nullptr && chunk->is_empty()) {
    chunk = chunk->parent();
  }
  return chunk;
}

#endif // SHARE_VM_RUNTIME_CONTINUATIONWRAPPER_INLINE_HPP

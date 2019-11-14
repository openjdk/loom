/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_RUNTIME_CONTINUATION_INLINE_HPP
#define SHARE_VM_RUNTIME_CONTINUATION_INLINE_HPP

// #include "code/vmreg.inline.hpp"
// #include "compiler/oopMap.hpp"
// #include "logging/log.hpp"
#include "runtime/continuation.hpp"

#include CPU_HEADER_INLINE(continuationChunk)

// template <class OopClosureType>
// void Continuation::stack_chunk_iterate_stack(oop chunk, OopClosureType* closure) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack(chunk, (OopClosure*)closure, closure->do_metadata());
// }

// template <class OopClosureType>
// void Continuation::stack_chunk_iterate_stack_bounded(oop chunk, OopClosureType* closure, MemRegion mr) {
//   // for now, we don't devirtualize for faster compilation
//   Continuation::stack_chunk_iterate_stack_bounded(chunk, (OopClosure*)closure, closure->do_metadata(), mr);
// }

#endif // SHARE_VM_RUNTIME_CONTINUATION_INLINE_HPP

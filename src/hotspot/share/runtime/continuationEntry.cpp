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

#include "precompiled.hpp"
#include "code/nmethod.hpp"
#include "runtime/continuation.hpp"
#include "runtime/continuationEntry.inline.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/stackFrameStream.inline.hpp"
#include "runtime/stackWatermarkSet.inline.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/thread.inline.hpp"

int ContinuationEntry::_return_pc_offset = 0;
address ContinuationEntry::_return_pc = nullptr;

void ContinuationEntry::set_enter_code(CompiledMethod* cm) {
  assert(_return_pc_offset != 0, "");
  _return_pc = cm->code_begin() + _return_pc_offset;
}

ContinuationEntry* ContinuationEntry::from_frame(const frame& f) {
  assert(Continuation::is_continuation_enterSpecial(f), "");
  return (ContinuationEntry*)f.unextended_sp();
}

NOINLINE static void flush_stack_processing(JavaThread* thread, intptr_t* sp) {
  log_develop_trace(continuations)("flush_stack_processing");
  for (StackFrameStream fst(thread, true, true); fst.current()->sp() <= sp; fst.next()) {
    ;
  }
}

inline void maybe_flush_stack_processing(JavaThread* thread, intptr_t* sp) {
  StackWatermark* sw;
  uintptr_t watermark;
  if ((sw = StackWatermarkSet::get(thread, StackWatermarkKind::gc)) != nullptr
        && (watermark = sw->watermark()) != 0
        && watermark <= (uintptr_t)sp) {
    flush_stack_processing(thread, sp);
  }
}

void ContinuationEntry::flush_stack_processing(JavaThread* thread) const {
  maybe_flush_stack_processing(thread, (intptr_t*)((uintptr_t)entry_sp() + ContinuationEntry::size()));
}

void ContinuationEntry::setup_oopmap(OopMap* map) {
  map->set_oop(VMRegImpl::stack2reg(in_bytes(cont_offset())  / VMRegImpl::stack_slot_size));
  map->set_oop(VMRegImpl::stack2reg(in_bytes(chunk_offset()) / VMRegImpl::stack_slot_size));
}

#ifdef ASSERT
bool ContinuationEntry::assert_entry_frame_laid_out(JavaThread* thread) {
  assert(thread->has_last_Java_frame(), "Wrong place to use this assertion");

  ContinuationEntry* entry = thread->last_continuation();
  assert(entry != nullptr, "");

  intptr_t* unextended_sp = entry->entry_sp();
  intptr_t* sp;
  if (entry->argsize() > 0) {
    sp = entry->bottom_sender_sp();
  } else {
    sp = unextended_sp;
    bool interpreted_bottom = false;
    RegisterMap map(thread, false, false, false);
    frame f;
    for (f = thread->last_frame();
         !f.is_first_frame() && f.sp() <= unextended_sp && !Continuation::is_continuation_enterSpecial(f);
         f = f.sender(&map)) {
      interpreted_bottom = f.is_interpreted_frame();
    }
    assert(Continuation::is_continuation_enterSpecial(f), "");
    sp = interpreted_bottom ? f.sp() : entry->bottom_sender_sp();
  }

  assert(sp != nullptr, "");
  assert(sp <= entry->entry_sp(), "");
  address pc = *(address*)(sp - frame::sender_sp_ret_address_offset());

  if (pc != StubRoutines::cont_returnBarrier()) {
    CodeBlob* cb = pc != nullptr ? CodeCache::find_blob(pc) : nullptr;
    assert(cb != nullptr, "sp: " INTPTR_FORMAT " pc: " INTPTR_FORMAT, p2i(sp), p2i(pc));
    assert(cb->as_compiled_method()->method()->is_continuation_enter_intrinsic(), "");
  }

  return true;
}
#endif // ASSERT

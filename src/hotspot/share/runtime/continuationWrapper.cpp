/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/javaClasses.inline.hpp"
#include "jfr/jfrEvents.hpp"
#include "jfr/support/jfrThreadId.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/stackChunkOop.inline.hpp"
#include "runtime/continuation.hpp"
#include "runtime/continuationEntry.hpp"
#include "runtime/continuationHelper.inline.hpp"
#include "runtime/continuationWrapper.inline.hpp"
#include "runtime/stackChunkFrameStream.inline.hpp"
#include "runtime/thread.inline.hpp"

ContinuationWrapper::ContinuationWrapper(JavaThread* thread, oop continuation)
  : _thread(thread), _entry(thread->last_continuation()), _continuation(continuation),
    _e_size(0), _e_num_interpreted_frames(0)
  {
  assert(oopDesc::is_oop(_continuation),
         "Invalid continuation object: " INTPTR_FORMAT, p2i((void*)_continuation));
  assert(_continuation == _entry->cont_oop(), "cont: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: "
         INTPTR_FORMAT, p2i((oopDesc*)_continuation), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  disallow_safepoint();
  read();
}

ContinuationWrapper::ContinuationWrapper(oop continuation)
  : _thread(nullptr), _entry(nullptr), _continuation(continuation),
    _e_size(0), _e_num_interpreted_frames(0)
  {
  assert(oopDesc::is_oop(_continuation),
         "Invalid continuation object: " INTPTR_FORMAT, p2i((void*)_continuation));
  disallow_safepoint();
  read();
}

ContinuationWrapper::ContinuationWrapper(const RegisterMap* map)
  : _thread(map->thread()),
    _entry(Continuation::get_continuation_entry_for_continuation(_thread, map->stack_chunk()->cont())),
    _continuation(map->stack_chunk()->cont()),
    _e_size(0), _e_num_interpreted_frames(0)
  {
  assert(oopDesc::is_oop(_continuation),"Invalid cont: " INTPTR_FORMAT, p2i((void*)_continuation));
  assert(_entry == nullptr || _continuation == _entry->cont_oop(),
    "cont: " INTPTR_FORMAT " entry: " INTPTR_FORMAT " entry_sp: " INTPTR_FORMAT,
    p2i( (oopDesc*)_continuation), p2i((oopDesc*)_entry->cont_oop()), p2i(entrySP()));
  disallow_safepoint();
  read();
}

const frame ContinuationWrapper::last_frame() {
  stackChunkOop chunk = last_nonempty_chunk();
  if (chunk == nullptr) {
    return frame();
  }
  return StackChunkFrameStream<ChunkFrames::Mixed>(chunk).to_frame();
}

stackChunkOop ContinuationWrapper::find_chunk_by_address(void* p) const {
  for (stackChunkOop chunk = tail(); chunk != nullptr; chunk = chunk->parent()) {
    if (chunk->is_in_chunk(p)) {
      assert(chunk->is_usable_in_chunk(p), "");
      return chunk;
    }
  }
  return nullptr;
}

template<typename Event> void ContinuationWrapper::post_jfr_event(Event* e, JavaThread* jt) {
  if (e->should_commit()) {
    log_develop_trace(continuations)("JFR event: iframes: %d size: %d", _e_num_interpreted_frames, _e_size);
    e->set_carrierThread(JFR_JVM_THREAD_ID(jt));
    e->set_contClass(_continuation->klass());
    e->set_numIFrames(_e_num_interpreted_frames);
    e->set_size(_e_size);
    e->commit();
  }
}

#ifndef PRODUCT
intptr_t ContinuationWrapper::hash() {
  return Thread::current()->is_Java_thread() ? _continuation->identity_hash() : -1;
}
#endif

#ifdef ASSERT
bool ContinuationWrapper::is_entry_frame(const frame& f) {
  return f.sp() == entrySP();
}

bool ContinuationWrapper::chunk_invariant(outputStream* st) {
  // only the topmost chunk can be empty
  if (_tail == nullptr) {
    return true;
  }

  int i = 1;
  for (stackChunkOop chunk = _tail->parent(); chunk != nullptr; chunk = chunk->parent()) {
    if (chunk->is_empty()) {
      assert(chunk != _tail, "");
      st->print_cr("i: %d", i);
      chunk->print_on(true, st);
      return false;
    }
    i++;
  }
  return true;
}
#endif // ASSERT


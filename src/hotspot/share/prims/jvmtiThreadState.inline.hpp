/*
 * Copyright (c) 2006, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_PRIMS_JVMTITHREADSTATE_INLINE_HPP
#define SHARE_PRIMS_JVMTITHREADSTATE_INLINE_HPP

#include "classfile/javaClasses.hpp"
#include "prims/jvmtiEnvThreadState.hpp"
#include "prims/jvmtiThreadState.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/thread.inline.hpp"

// JvmtiEnvThreadStateIterator implementation

inline JvmtiEnvThreadStateIterator::JvmtiEnvThreadStateIterator(JvmtiThreadState* thread_state) {
  state = thread_state;
  Thread::current()->entering_jvmti_env_iteration();
}

inline JvmtiEnvThreadStateIterator::~JvmtiEnvThreadStateIterator() {
  Thread::current()->leaving_jvmti_env_iteration();
}

inline JvmtiEnvThreadState* JvmtiEnvThreadStateIterator::first() {
  return state->head_env_thread_state();
}

inline JvmtiEnvThreadState* JvmtiEnvThreadStateIterator::next(JvmtiEnvThreadState* ets) {
  return ets->next();
}

// JvmtiThreadState implementation

JvmtiEnvThreadState* JvmtiThreadState::env_thread_state(JvmtiEnvBase *env) {
  JvmtiEnvThreadStateIterator it(this);
  for (JvmtiEnvThreadState* ets = it.first(); ets != NULL; ets = it.next(ets)) {
    if ((JvmtiEnvBase*)(ets->get_env()) == env) {
      return ets;
    }
  }
  return NULL;
}

JvmtiEnvThreadState* JvmtiThreadState::head_env_thread_state() {
  return _head_env_thread_state;
}

void JvmtiThreadState::set_head_env_thread_state(JvmtiEnvThreadState* ets) {
  _head_env_thread_state = ets;
}

inline JvmtiThreadState* JvmtiThreadState::state_for_while_locked(JavaThread *thread, oop thread_oop) {
  assert(JvmtiThreadState_lock->is_locked(), "sanity check");
  assert(thread != NULL || thread_oop != NULL, "sanity check");
  assert(thread_oop == NULL || !java_lang_VirtualThread::is_instance(thread_oop) ||
         JvmtiExport::can_support_virtual_threads(), "sanity check");

  if (thread_oop == NULL) { // then thread should not be NULL (see assert above)
    thread_oop = thread->mounted_vthread() != NULL ? thread->mounted_vthread() : thread->threadObj();
  }

  // in a case of unmounted virtual thread the thread can be NULL
  JvmtiThreadState *state = thread == NULL ? NULL : thread->jvmti_thread_state();

  if (state == NULL) {
    if (thread != NULL && thread->is_exiting()) {
      // don't add a JvmtiThreadState to a thread that is exiting
      return NULL;
    }
    state = new JvmtiThreadState(thread, thread_oop);
  }
  return state;
}

inline JvmtiThreadState* JvmtiThreadState::state_for(JavaThread *thread, oop thread_oop) {
  // in a case of unmounted virtual thread the thread can be NULL
  JvmtiThreadState *state = (thread == NULL) ? NULL : thread->jvmti_thread_state();
  if (state == NULL) {
    Thread* current_thread = Thread::current();
    HandleMark hm(current_thread);
    Handle h_thread_oop = Handle(current_thread, thread_oop);
    MutexLocker mu(JvmtiThreadState_lock);
    // check again with the lock held
    state = state_for_while_locked(thread, h_thread_oop());
  } else {
    // Check possible safepoint even if state is non-null.
    // (Note: the thread argument isn't the current thread)
    DEBUG_ONLY(JavaThread::current()->check_possible_safepoint());
  }
  return state;
}

inline void JvmtiThreadState::unbind_from(JavaThread* thread) {
  if (this == NULL) {
    return;
  }
  // save interp_only_mode
  _saved_interp_only_mode = thread->get_interp_only_mode();

#ifdef ASSERT
  if (is_virtual()) {
    // unbind from JavaThread
    set_thread(NULL); // it is to make sure stale _thread value is never used
  }
#endif
}

inline void JvmtiThreadState::bind_to(JavaThread* thread) {
  // restore thread interp_only_mode
  thread->set_interp_only_mode(this == NULL ? 0 : _saved_interp_only_mode);

  // bind JavaThread to JvmtiThreadState
  thread->set_jvmti_thread_state(this);

  JvmtiEventController::thread_started(thread);

  if (this != NULL && is_virtual()) {
    // bind to JavaThread
    set_thread(thread);
  }
}
#endif // SHARE_PRIMS_JVMTITHREADSTATE_INLINE_HPP

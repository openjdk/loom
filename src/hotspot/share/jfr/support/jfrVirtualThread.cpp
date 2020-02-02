/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/systemDictionary.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/vmSymbols.hpp"
#include "jfr/recorder/checkpoint/jfrCheckpointManager.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrOopTraceId.inline.hpp"
#include "jfr/support/jfrVirtualThread.hpp"
#include "jfr/support/jfrThreadLocal.hpp"
#include "runtime/thread.inline.hpp"

typedef JfrOopTraceId<java_lang_VirtualThread> VirtualThreadTraceId;

static traceid write_checkpoint(const JavaThread* jt, traceid value, oop vthread) {
  assert(vthread != NULL, "invariant");
  assert(value != 0, "invariant");
  assert(jt != NULL, "invariant");
  const traceid tid = VirtualThreadTraceId::id(value);
  if (VirtualThreadTraceId::should_write_checkpoint(vthread, value)) {
    JfrCheckpointManager::write_checkpoint(const_cast<JavaThread*>(jt), tid, vthread);
  }
  return tid;
}

traceid JfrVirtualThread::assign_id(oop vthread, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(jt != NULL, "invariant");
  const traceid tid = jt->jfr_thread_local()->next_thread_local_id();
  assert(tid != 0, "invariant");
  return write_checkpoint(jt , tid, vthread);
}

static traceid id(oop vthread, traceid value, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(value != 0, "invariant");
  assert(jt != NULL, "invariant");
  return write_checkpoint(jt, value, vthread);
}

inline const JavaThread* as_java_thread(const Thread* t) {
  assert(t != NULL, "invariant");
  return t->is_Java_thread() ? (JavaThread*)t : NULL;
}

inline oop get_vthread(oop threadObj) {
  assert(threadObj != NULL, "invariant");
  return java_lang_Thread::vthread(threadObj);
}

inline oop get_threadObj(const JavaThread* jt) {
  assert(jt != NULL, "invariant");
  return jt->threadObj();
}

inline traceid JfrVirtualThread::vthread_id(oop vthread, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(jt != NULL, "invariant");
  const traceid value = VirtualThreadTraceId::load(vthread);
  return value != 0 ? id(vthread, value, jt) : assign_id(vthread, jt);
}

bool JfrVirtualThread::is_virtual(const Thread* t) {
  const JavaThread* const jt = as_java_thread(t);
  if (jt == NULL) {
    return false;
  }
  const oop threadObj = get_threadObj(jt);
  return threadObj != NULL && java_lang_Thread::vthread(threadObj) != NULL;
}

traceid JfrVirtualThread::thread_id(const Thread* t) {
  assert(t != NULL, "invariant");
  const JavaThread* const jt = as_java_thread(t);
  if (jt == NULL) {
    return 0;
  }
  const oop threadObj = get_threadObj(jt);
  if (threadObj == NULL) {
    return 0;
  }

  const oop vthread = get_vthread(threadObj);
  if (vthread == NULL) {
    return 0;
  }
  return vthread_id(vthread, jt);
}

traceid JfrVirtualThread::thread_id(oop vthread, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(jt != NULL, "invariant");
  return vthread_id(vthread, jt);
}

bool JfrVirtualThread::initialize(bool notify) {
  static bool initialized = false;
  if (!initialized) {
    Thread* const t = Thread::current();
    Klass* const k = SystemDictionary::resolve_or_fail(vmSymbols::java_lang_Thread_VirtualThreads(), false, t);
    assert(k != NULL, "invariant");
    k->initialize(t);
    java_lang_VirtualThread::set_notify_jvmti_events(true);
    java_lang_VirtualThread::init_static_notify_jvmti_events();
    initialized = true;
  }
  return initialized;
}


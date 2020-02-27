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
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceId.hpp"
#include "jfr/support/jfrJavaThread.hpp"
#include "jfr/support/jfrThreadLocal.hpp"
#include "runtime/thread.inline.hpp"

static void set_jvmti_notifications(bool notify_vthread_events) {
  java_lang_VirtualThread::set_notify_jvmti_events(notify_vthread_events);
  java_lang_VirtualThread::init_static_notify_jvmti_events();
}

bool JfrJavaThread::initialize(bool notify_vthread_events) {
  static bool initialized = false;
  if (!initialized) {
    Thread* const t = Thread::current();
    assert(t->is_Java_thread(), "invariant");
    Symbol* const sym = vmSymbols::java_lang_Thread_VirtualThreads();
    assert(sym != NULL, "invariant");
    Klass* const k = SystemDictionary::resolve_or_fail(sym, false, t);
    assert(k != NULL, "invariant");
    k->initialize(t);
    set_jvmti_notifications(notify_vthread_events);
    initialized = true;
  }
  return initialized;
}

class ThreadIdAccess : AllStatic {
 public:
  static traceid load(oop ref) {
    return (traceid)java_lang_Thread::thread_id(ref);
  }
  static traceid store(oop ref, traceid value) {
    return (traceid)java_lang_VirtualThread::set_jfrTraceId(ref, (jlong)value);
  }
};

typedef JfrOopTraceId<ThreadIdAccess> AccessThreadTraceId;

static traceid load_vthread_id(oop vthread, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(jt != NULL, "invariant");
  const traceid value = AccessThreadTraceId::load(vthread);
  const traceid tid = AccessThreadTraceId::id(value);
  if (AccessThreadTraceId::should_write_checkpoint(vthread, value)) {
    JfrCheckpointManager::write_checkpoint(const_cast<JavaThread*>(jt), tid, vthread);
  }
  return tid;
}

inline traceid load_java_thread_id(oop threadObj, const JavaThread* jt) {
  assert(threadObj != NULL, "invariant");
  return java_lang_Thread::thread_id(threadObj);
}

inline oop get_vthread(oop threadObj) {
  assert(threadObj != NULL, "invariant");
  return java_lang_Thread::vthread(threadObj);
}

inline oop get_threadObj(const JavaThread* jt) {
  assert(jt != NULL, "invariant");
  return jt->threadObj();
}

traceid JfrJavaThread::contextual_thread_id(const JavaThread* jt, bool* is_virtual) {
  assert(jt != NULL, "invariant");
  assert(is_virtual != NULL ? !(*is_virtual) : true, "invariant");
  const oop threadObj = get_threadObj(jt);
  if (threadObj == NULL) {
    // to early
    return 0;
  }
  const oop vthread = get_vthread(threadObj);
  if (vthread == NULL) {
    return load_java_thread_id(threadObj, jt);
  }
  if (is_virtual != NULL) {
    *is_virtual = true;
  }
  return load_vthread_id(vthread, jt);
}

traceid JfrJavaThread::java_thread_id(const JavaThread* jt) {
  assert(jt != NULL, "invariant");
  const oop threadObj = get_threadObj(jt);
  return threadObj != NULL ? load_java_thread_id(threadObj, jt) : 0;
}

traceid JfrJavaThread::virtual_thread_id(const oop vthread, const JavaThread* jt) {
  assert(vthread != NULL, "invariant");
  assert(jt != NULL, "invariant");
  return load_vthread_id(vthread, jt);
}

bool JfrJavaThread::is_virtual(const JavaThread* jt) {
  assert(jt != NULL, "invariant");
  const oop threadObj = get_threadObj(jt);
  return threadObj != NULL && get_vthread(threadObj) != NULL;
}

oop JfrJavaThread::virtual_thread(const JavaThread* jt) {
  assert(is_virtual(jt), "invariant");
  return get_vthread(get_threadObj(jt));
}


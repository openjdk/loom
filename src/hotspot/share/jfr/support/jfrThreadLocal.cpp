/*
 * Copyright (c) 2012, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "jfr/jfrEvents.hpp"
#include "jfr/jni/jfrJavaSupport.hpp"
#include "jfr/leakprofiler/checkpoint/objectSampleCheckpoint.hpp"
#include "jfr/periodic/jfrThreadCPULoadEvent.hpp"
#include "jfr/recorder/checkpoint/jfrCheckpointManager.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceIdEpoch.hpp"
#include "jfr/recorder/jfrRecorder.hpp"
#include "jfr/recorder/service/jfrOptionSet.hpp"
#include "jfr/recorder/storage/jfrStorage.hpp"
#include "jfr/support/jfrJavaThread.hpp"
#include "jfr/support/jfrThreadLocal.hpp"
#include "jfr/utilities/jfrSpinlockHelper.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/os.hpp"
#include "runtime/thread.inline.hpp"
#include "utilities/sizes.hpp"

JfrThreadLocal::JfrThreadLocal() :
  _java_event_writer(NULL),
  _java_buffer(NULL),
  _native_buffer(NULL),
  _shelved_buffer(NULL),
  _load_barrier_buffer_epoch_0(NULL),
  _load_barrier_buffer_epoch_1(NULL),
  _checkpoint_buffer_epoch_0(NULL),
  _checkpoint_buffer_epoch_1(NULL),
  _stackframes(NULL),
  _thread(),
  _thread_id(0),
  _thread_id_alias(max_julong),
  _data_lost(0),
  _stack_trace_id(max_julong),
  _user_time(0),
  _cpu_time(0),
  _wallclock_time(os::javaTimeNanos()),
  _stack_trace_hash(0),
  _stackdepth(0),
  _entering_suspend_flag(0),
  _critical_section(0),
  _excluded(false),
  _dead(false) {
  Thread* thread = Thread::current_or_null();
  _parent_trace_id = thread != NULL ? thread->jfr_thread_local()->trace_id() : (traceid)0;
}

u8 JfrThreadLocal::add_data_lost(u8 value) {
  _data_lost += value;
  return _data_lost;
}

bool JfrThreadLocal::has_thread_blob() const {
  return _thread.valid();
}

void JfrThreadLocal::set_thread_blob(const JfrBlobHandle& ref) {
  assert(!_thread.valid(), "invariant");
  _thread = ref;
}

const JfrBlobHandle& JfrThreadLocal::thread_blob() const {
  return _thread;
}

static void send_java_thread_start_event(JavaThread* jt, jobject vthread) {
  assert(jt != NULL, "invariant");
  assert(Thread::current() == jt, "invariant");
  if (!JfrJavaSupport::on_thread_start(jt, vthread)) {
    // thread is excluded
    return;
  }
  if (JfrRecorder::is_recording()) {
    EventThreadStart event;
    event.set_thread(vthread != NULL ? JfrThreadLocal::virtual_thread_id(jt, JfrJavaSupport::resolve_non_null(vthread)) : JfrThreadLocal::vm_thread_id(jt));
    event.set_parentThread(jt->jfr_thread_local()->parent_thread_id());
    event.commit();
  }
}

void JfrThreadLocal::on_start(Thread* t) {
  if (JfrRecorder::is_recording()) {
    JfrCheckpointManager::write_checkpoint(t);
    if (t->is_Java_thread()) {
      send_java_thread_start_event(t->as_Java_thread(), NULL);
    }
  }
  if (t->jfr_thread_local()->has_cached_stack_trace()) {
    t->jfr_thread_local()->clear_cached_stack_trace();
  }
}

void JfrThreadLocal::on_vthread_start(JavaThread* jt, jobject vthread) {
  send_java_thread_start_event(jt, vthread);
}

void JfrThreadLocal::release(Thread* t) {
  if (has_java_event_writer()) {
    assert(t->is_Java_thread(), "invariant");
    JfrJavaSupport::destroy_global_jni_handle(java_event_writer());
    _java_event_writer = NULL;
  }
  if (has_native_buffer()) {
    JfrStorage::release_thread_local(native_buffer(), t);
    _native_buffer = NULL;
  }
  if (has_java_buffer()) {
    JfrStorage::release_thread_local(java_buffer(), t);
    _java_buffer = NULL;
  }
  if (_stackframes != NULL) {
    FREE_C_HEAP_ARRAY(JfrStackFrame, _stackframes);
    _stackframes = NULL;
  }
  if (_load_barrier_buffer_epoch_0 != NULL) {
    _load_barrier_buffer_epoch_0->set_retired();
    _load_barrier_buffer_epoch_0 = NULL;
  }
  if (_load_barrier_buffer_epoch_1 != NULL) {
    _load_barrier_buffer_epoch_1->set_retired();
    _load_barrier_buffer_epoch_1 = NULL;
  }
  if (_checkpoint_buffer_epoch_0 != NULL) {
    _checkpoint_buffer_epoch_0->set_retired();
    _checkpoint_buffer_epoch_0 = NULL;
  }
  if (_checkpoint_buffer_epoch_1 != NULL) {
    _checkpoint_buffer_epoch_1->set_retired();
    _checkpoint_buffer_epoch_1 = NULL;
  }
}

void JfrThreadLocal::release(JfrThreadLocal* tl, Thread* t) {
  assert(tl != NULL, "invariant");
  assert(t != NULL, "invariant");
  assert(Thread::current() == t, "invariant");
  assert(!tl->is_dead(), "invariant");
  assert(tl->shelved_buffer() == NULL, "invariant");
  tl->_dead = true;
  tl->release(t);
}

static void send_java_thread_end_event(JavaThread* jt, traceid tid) {
  assert(jt != NULL, "invariant");
  assert(Thread::current() == jt, "invariant");
  assert(tid != 0, "invariant");
  if (JfrRecorder::is_recording()) {
    EventThreadEnd event;
    event.set_thread(tid);
    event.commit();
    ObjectSampleCheckpoint::on_thread_exit(tid);
  }
}

void JfrThreadLocal::on_exit(Thread* t) {
  assert(t != NULL, "invariant");
  JfrThreadLocal * const tl = t->jfr_thread_local();
  assert(!tl->is_dead(), "invariant");
  if (t->is_Java_thread()) {
    JavaThread* const jt = t->as_Java_thread();
    send_java_thread_end_event(jt, JfrThreadLocal::thread_id(jt));
    JfrThreadCPULoadEvent::send_event_for_thread(jt);
  }
  release(tl, Thread::current()); // because it could be that Thread::current() != t
}

void JfrThreadLocal::on_vthread_exit(JavaThread* jt, jobject vthread) {
  assert(vthread != NULL, "invariant");
  const traceid id = JfrJavaThread::virtual_thread_id(JfrJavaSupport::resolve_non_null(vthread), jt);
  JfrThreadLocal::impersonate(jt, id);
  send_java_thread_end_event(jt, id);
  JfrThreadLocal::stop_impersonating(jt);
}

static JfrBuffer* acquire_buffer(bool excluded) {
  JfrBuffer* const buffer = JfrStorage::acquire_thread_local(Thread::current());
  if (buffer != NULL && excluded) {
    buffer->set_excluded();
  }
  return buffer;
}

JfrBuffer* JfrThreadLocal::install_native_buffer() const {
  assert(!has_native_buffer(), "invariant");
  _native_buffer = acquire_buffer(_excluded);
  return _native_buffer;
}

JfrBuffer* JfrThreadLocal::install_java_buffer() const {
  assert(!has_java_buffer(), "invariant");
  assert(!has_java_event_writer(), "invariant");
  _java_buffer = acquire_buffer(_excluded);
  return _java_buffer;
}

JfrStackFrame* JfrThreadLocal::install_stackframes() const {
  assert(_stackframes == NULL, "invariant");
  _stackframes = NEW_C_HEAP_ARRAY(JfrStackFrame, stackdepth(), mtTracing);
  return _stackframes;
}

ByteSize JfrThreadLocal::trace_id_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _thread_id));
}

ByteSize JfrThreadLocal::java_event_writer_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _java_event_writer));
}

void JfrThreadLocal::exclude(Thread* t) {
  assert(t != NULL, "invariant");
  t->jfr_thread_local()->_excluded = true;
  t->jfr_thread_local()->release(t);
}

void JfrThreadLocal::include(Thread* t) {
  assert(t != NULL, "invariant");
  t->jfr_thread_local()->_excluded = false;
  t->jfr_thread_local()->release(t);
}

u4 JfrThreadLocal::stackdepth() const {
  return _stackdepth != 0 ? _stackdepth : (u4)JfrOptionSet::stackdepth();
}

bool JfrThreadLocal::is_impersonating(const Thread* t) {
  return t->jfr_thread_local()->_thread_id_alias != max_julong;
}

void JfrThreadLocal::impersonate(const Thread* t, traceid other_thread_id) {
  assert(t != NULL, "invariant");
  assert(other_thread_id != 0, "invariant");
  JfrThreadLocal* const tl = t->jfr_thread_local();
  tl->_thread_id_alias = other_thread_id;
}

void JfrThreadLocal::stop_impersonating(const Thread* t) {
  assert(t != NULL, "invariant");
  JfrThreadLocal* const tl = t->jfr_thread_local();
  if (is_impersonating(t)) {
    tl->_thread_id_alias = max_julong;
  }
  assert(!is_impersonating(t), "invariant");
}

traceid JfrThreadLocal::thread_id(const Thread* t, bool* is_virtual) {
  assert(t != NULL, "invariant");
  if (is_impersonating(t)) {
    return t->jfr_thread_local()->_thread_id_alias;
  }
  return t->is_Java_thread() ? JfrJavaThread::contextual_thread_id(t->as_Java_thread(), is_virtual) : vm_thread_id(t);
}

traceid JfrThreadLocal::virtual_thread_id(const Thread* t, oop vthread) {
  assert(t != NULL, "invariant");
  assert(vthread != NULL, "invariant");
  return JfrJavaThread::virtual_thread_id(vthread, (JavaThread*)t);
}

traceid JfrThreadLocal::assign_thread_id(const Thread* t) {
  assert(t != NULL, "invariant");
  JfrThreadLocal* const tl = t->jfr_thread_local();
  JfrSpinlockHelper spinlock(&tl->_critical_section);
  traceid tid = tl->_thread_id;
  if (tid == 0) {
    tid = t->is_Java_thread() ? JfrJavaThread::java_thread_id((JavaThread*)t) :
                                JfrTraceId::assign_thread_id();
    tl->_thread_id = tid;
  }
  assert(tid != 0, "invariant");
  return tid;
}

traceid JfrThreadLocal::vm_thread_id(const Thread* t) {
  assert(t != NULL, "invariant");
  JfrThreadLocal* const tl = t->jfr_thread_local();
  return tl->_thread_id != 0 ? tl->_thread_id : JfrThreadLocal::assign_thread_id(t);
}

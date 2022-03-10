/*
 * Copyright (c) 2012, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "jfr/jni/jfrJavaSupport.hpp"
#include "jfr/leakprofiler/checkpoint/objectSampleCheckpoint.hpp"
#include "jfr/periodic/jfrThreadCPULoadEvent.hpp"
#include "jfr/recorder/checkpoint/jfrCheckpointManager.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrOopTraceId.inline.hpp"
#include "jfr/recorder/jfrRecorder.hpp"
#include "jfr/recorder/service/jfrOptionSet.hpp"
#include "jfr/recorder/stacktrace/jfrStackTraceRepository.hpp"
#include "jfr/recorder/storage/jfrStorage.hpp"
#include "jfr/support/jfrThreadId.inline.hpp"
#include "jfr/support/jfrThreadLocal.hpp"
#include "jfr/utilities/jfrSpinlockHelper.hpp"
#include "logging/log.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/os.hpp"
#include "runtime/thread.inline.hpp"
#include "runtime/threadIdentifiers.hpp"
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
  _contextual_thread_id(0),
  _vm_thread_id(0),
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
  _vthread_epoch(0),
  _vthread(false),
  _excluded(false),
  _dead(false) {
  Thread* thread = Thread::current_or_null();
  _parent_trace_id = thread != NULL ? thread_id(thread) : (traceid)0;
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

static void send_java_thread_start_event(JavaThread* jt) {
  assert(jt != NULL, "invariant");
  assert(Thread::current() == jt, "invariant");
  if (!JfrJavaSupport::on_thread_start(jt)) {
    // thread is excluded
    return;
  }
  if (JfrRecorder::is_recording()) {
    EventThreadStart event;
    traceid thread_id = JfrThreadLocal::vm_thread_id(jt);
    assert(thread_id != 0, "invariant");
    event.set_thread(thread_id);
    event.set_parentThread(jt->jfr_thread_local()->parent_thread_id());
    event.commit();
  }
}

void JfrThreadLocal::on_start(Thread* t) {
  assign_thread_id(t, t->jfr_thread_local());
  if (JfrRecorder::is_recording()) {
    JfrCheckpointManager::write_checkpoint(t);
    if (t->is_Java_thread()) {
      send_java_thread_start_event(JavaThread::cast(t));
    }
  }
  if (t->jfr_thread_local()->has_cached_stack_trace()) {
    t->jfr_thread_local()->clear_cached_stack_trace();
  }
}

// The starter thread ensures that the startee has a valid _vm_thread_id and _contextual_id.
// This is to avoid recursion in thread assignment since accessing the java threadObj can lead
// to events being fired, a situation the starter thread can handle but not the startee.
void JfrThreadLocal::on_java_thread_start(JavaThread* starter, JavaThread* startee) {
  assert(starter != nullptr, "invariant");
  assert(startee != nullptr, "invariant");
  JfrThreadLocal* const tl = startee->jfr_thread_local();
  assign_thread_id(startee, tl);
  assert(Atomic::load(&tl->_contextual_thread_id) != 0, "invariant");
  assert(Atomic::load(&tl->_vm_thread_id) == Atomic::load(&tl->_contextual_thread_id), "invariant");
  if (JfrRecorder::is_recording() && EventThreadStart::is_enabled() && EventThreadStart::is_stacktrace_enabled()) {
    // skip level 2 to skip frames Thread.start() and Thread.start0()
    startee->jfr_thread_local()->set_cached_stack_trace_id(JfrStackTraceRepository::record(starter, 2));
  }
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
    JavaThread* const jt = JavaThread::cast(t);
    send_java_thread_end_event(jt, JfrThreadLocal::vm_thread_id(jt));
    JfrThreadCPULoadEvent::send_event_for_thread(jt);
  }
  release(tl, Thread::current()); // because it could be that Thread::current() != t
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

ByteSize JfrThreadLocal::java_event_writer_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _java_event_writer));
}

ByteSize JfrThreadLocal::trace_id_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _contextual_thread_id));
}

ByteSize JfrThreadLocal::vthread_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _vthread));
}

ByteSize JfrThreadLocal::vthread_epoch_offset() {
  return in_ByteSize(offset_of(JfrThreadLocal, _vthread_epoch));
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

void JfrThreadLocal::set_vthread_epoch(const JavaThread* jt, traceid tid, u2 epoch) {
  assert(jt != nullptr, "invariant");
  assert(is_vthread(jt), "invariant");
  // To support event recursion, we update the native side first,
  // this provides the terminating case.
  Atomic::store(&jt->jfr_thread_local()->_vthread_epoch, epoch);
  /*
  * The java side, i.e. the vthread object, can now be updated.
  * Accessing the vthread object itself is a recursive case,
  * because it can trigger additional events, e.g.
  * loading the oop through load barriers.
  * Note there is a potential problem with this solution:
  * The recursive write hitting the terminating case will
  * use the thread id _before_ the checkpoint is committed.
  * Hence, the periodic thread can possibly flush that event
  * to a segment that does not include an associated checkpoint.
  * Considered rare and quite benign for now. The worst case is
  * that thread information for that event is not resolvable,
  * i.e. will be null.
  */
  typedef JfrOopTraceId<VThreadIdAccess> AccessThreadTraceId;
  oop vthread = jt->vthread();
  assert(vthread != nullptr, "invariant");
  AccessThreadTraceId::set_epoch(vthread, epoch);
  JfrCheckpointManager::write_checkpoint(const_cast<JavaThread*>(jt), tid, vthread);
}

traceid JfrThreadLocal::contextual_id(const Thread* t) {
  assert(t != nullptr, "invariant");
  return Atomic::load(&t->jfr_thread_local()->_contextual_thread_id);
}

u2 JfrThreadLocal::vthread_epoch(const JavaThread* jt) {
  assert(jt != nullptr, "invariant");
  return Atomic::load(&jt->jfr_thread_local()->_vthread_epoch);
}

traceid JfrThreadLocal::thread_id(const Thread* t) {
  assert(t != NULL, "invariant");
  if (is_impersonating(t)) {
    return t->jfr_thread_local()->_thread_id_alias;
  }
  JfrThreadLocal* const tl = t->jfr_thread_local();
  if (!t->is_Java_thread() || !Atomic::load_acquire(&tl->_vthread)) {
    return vm_thread_id(t, tl);
  }
  // virtual thread
  const JavaThread* jt = JavaThread::cast(t);
  const traceid tid = contextual_id(jt);
  assert(tid != 0, "invariant");
  const u2 current_epoch = JfrTraceIdEpoch::epoch_generation();
  if (vthread_epoch(jt) != current_epoch) {
    set_vthread_epoch(jt, tid, current_epoch);
  }
  return tid;
}

inline traceid load_java_thread_id(const Thread* t) {
  assert(t != nullptr, "invariant");
  assert(t->is_Java_thread(), "invariant");
  oop threadObj = JavaThread::cast(t)->threadObj();
  return threadObj != nullptr ? java_lang_Thread::thread_id(threadObj) : 0;
}

traceid JfrThreadLocal::assign_thread_id(const Thread* t, JfrThreadLocal* tl) {
  assert(t != nullptr, "invariant");
  assert(tl != nullptr, "invariant");
  JfrSpinlockHelper spinlock(&tl->_critical_section);
  traceid tid = tl->_vm_thread_id;
  if (tid == 0) {
    if (t->is_Java_thread()) {
      tid = load_java_thread_id(t);
      tl->_vm_thread_id = tl->_contextual_thread_id = tid;
      return tid;
    }
    tid = static_cast<traceid>(ThreadIdentifiers::next());
    tl->_vm_thread_id = tid;
  }
  return tid;
}

traceid JfrThreadLocal::vm_thread_id(const Thread* t, JfrThreadLocal* tl) {
  assert(t != nullptr, "invariant");
  assert(tl != nullptr, "invariant");
  return tl->_vm_thread_id != 0 ? tl->_vm_thread_id : JfrThreadLocal::assign_thread_id(t, tl);
}

traceid JfrThreadLocal::vm_thread_id(const Thread* t) {
  assert(t != nullptr, "invariant");
  return vm_thread_id(t, t->jfr_thread_local());
}

bool JfrThreadLocal::is_vthread(const JavaThread* jt) {
  assert(jt != nullptr, "invariant");
  return Atomic::load_acquire(&jt->jfr_thread_local()->_vthread);
}

inline bool is_virtual(const JavaThread* jt, oop thread) {
  assert(jt != nullptr, "invariant");
  return thread != jt->threadObj();
}

void JfrThreadLocal::on_set_current_thread(JavaThread* jt, oop thread) {
  assert(jt != nullptr, "invariant");
  assert(thread != nullptr, "invariant");
  JfrThreadLocal* const tl = jt->jfr_thread_local();
  if (is_virtual(jt, thread)) {
    Atomic::store(&tl->_vthread_epoch, java_lang_VirtualThread::jfr_epoch(thread));
    Atomic::store(&tl->_contextual_thread_id, static_cast<traceid>(java_lang_Thread::thread_id(thread)));
    Atomic::release_store(&tl->_vthread, true);
    return;
  }
  Atomic::release_store(&tl->_vthread, false);
}

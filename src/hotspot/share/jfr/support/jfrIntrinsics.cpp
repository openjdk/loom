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
#include "jfr/jni/jfrJavaSupport.hpp"
#include "jfr/recorder/checkpoint/types/jfrTypeManager.hpp"
#include "jfr/support/jfrIntrinsics.hpp"
#include "jfr/support/jfrJavaThread.hpp"
#include "jfr/writers/jfrJavaEventWriter.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "utilities/macros.hpp"

static jobject get_event_writer_impl(Thread* t, traceid tid, bool write_checkpoint) {
  assert(t != NULL, "invariant");
  assert(t->is_Java_thread(), "invariant");
  JavaThread* const jt = (JavaThread*)t;
  DEBUG_ONLY(JfrJavaSupport::check_java_thread_in_java(jt);)
  assert(jt->has_last_Java_frame(), "invariant");
  // can safepoint here
  ThreadInVMfromJava transition(jt);
  if (write_checkpoint) {
    assert(tid != 0, "only for virtual threads");
    JfrTypeManager::write_checkpoint(t, tid, JfrJavaThread::virtual_thread(jt));
  }
  return JfrJavaEventWriter::event_writer(t, tid);
}

void* JfrIntrinsicSupport::get_event_writer(Thread* t) {
  return get_event_writer_impl(t, 0, false);
}

void* JfrIntrinsicSupport::write_checkpoint(Thread* t, traceid tid) {
  return get_event_writer_impl(t, tid, true);
}

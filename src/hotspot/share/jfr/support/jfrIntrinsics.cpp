/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceIdEpoch.hpp"
#include "jfr/support/jfrIntrinsics.hpp"
#include "jfr/support/jfrThreadLocal.hpp"
#include "jfr/writers/jfrJavaEventWriter.hpp"
#include "runtime/interfaceSupport.inline.hpp"

#ifdef ASSERT
static void assert_precondition(JavaThread* jt) {
  assert(jt != NULL, "invariant");
  DEBUG_ONLY(JfrJavaSupport::check_java_thread_in_java(jt);)
  assert(jt->has_last_Java_frame(), "invariant");
}
#endif

void* JfrIntrinsicSupport::get_event_writer(JavaThread* jt) {
  DEBUG_ONLY(assert_precondition(jt);)
  // Can safepoint here.
  ThreadInVMfromJava transition(jt);
  return JfrJavaEventWriter::event_writer(jt);
}

void JfrIntrinsicSupport::write_checkpoint(JavaThread* jt) {
  DEBUG_ONLY(assert_precondition(jt);)
  assert(JfrThreadLocal::is_vthread(jt), "invariant");
  const traceid vthread_tid = JfrThreadLocal::contextual_id(jt);
  // Transition to read correct epoch, can safepoint here.
  ThreadInVMfromJava transition(jt);
  JfrThreadLocal::set_vthread_epoch(jt, vthread_tid, JfrTraceIdEpoch::epoch_generation());
}

/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 */

#include <string.h>
#include "jvmti.h"
#include "jvmti_common.h"

jrawMonitorID monitor;
jrawMonitorID monitor_completed;

jvmtiEnv *jvmti_env;


static void
set_breakpoint(JNIEnv *jni, jclass klass, const char *mname) {
  jlocation location = (jlocation)0L;
  jmethodID method = find_method(jvmti_env, jni, klass, mname);
  jvmtiError err;

  if (method == NULL) {
    jni->FatalError("Error in set_breakpoint: not found method");
  }
  err = jvmti_env->SetBreakpoint(method, location);
  check_jvmti_status(jni, err, "set_or_clear_breakpoint: error in JVMTI SetBreakpoint");

  fflush(0);
}

extern "C" {

JNIEXPORT void JNICALL
Java_WaitNotifySuspendedVThreadTask_setBreakpoint(JNIEnv *jni, jclass klass) {
  jvmtiError err;

  printf("setBreakpoint: started\n");
  set_breakpoint(jni, klass, "methBreakpoint");

  // Enable Breakpoint events globally
  err = jvmti_env->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable BREAKPOINT");

  printf("setBreakpoint: finished\n");
  fflush(0);
}

JNIEXPORT void JNICALL
Java_WaitNotifySuspendedVThreadTask_notifyRawMonitors(JNIEnv *jni, jclass klass, jthread thread) {
  printf("Main thread: suspending virtual and carrier threads\n"); fflush(0);

  check_jvmti_status(jni, jvmti_env->SuspendThread(thread), "SuspendThread thread");
  jthread cthread = get_carrier_thread(jvmti_env, jni, thread);
  check_jvmti_status(jni, jvmti_env->SuspendThread(cthread), "SuspendThread thread");

  {
    RawMonitorLocker rml(jvmti_env, jni, monitor);

    printf("Main thread: calling monitor.notifyAll()\n"); fflush(0);
    rml.notify_all();
  }

  RawMonitorLocker completed(jvmti_env, jni, monitor_completed);

  printf("Main thread: resuming virtual thread\n"); fflush(0);
  check_jvmti_status(jni, jvmti_env->ResumeThread(thread), "ResumeThread thread");

  printf("Main thread: before monitor_completed.wait()\n"); fflush(0);
  completed.wait();
  printf("Main thread: after monitor_completed.wait()\n"); fflush(0);

  printf("Main thread: resuming carrier thread\n"); fflush(0);
  check_jvmti_status(jni, jvmti_env->ResumeThread(cthread), "ResumeThread cthread");
}

} // extern "C"

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = get_method_name(jvmti, jni, method);

  if (strcmp(mname, "methBreakpoint") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    deallocate(jvmti, jni, (void*)mname);
    fatal(jni, "Error in breakpoint");
    return;
  }
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  {
    RawMonitorLocker rml(jvmti, jni, monitor);

    printf("Breakpoint: before monitor.wait(): %s in %s thread\n", mname, virt); fflush(0);
    rml.wait();
    printf("Breakpoint: after monitor.wait(): %s in %s thread\n", mname, virt); fflush(0);
  }

  RawMonitorLocker completed(jvmti, jni, monitor_completed);

  printf("Breakpoint: calling monitor_completed.notifyAll()\n"); fflush(0);
  completed.notify_all();

  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)mname);
}

/* ============================================================================= */

jint
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEnv * jvmti = NULL;

  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  jvmti_env = jvmti;
  monitor = create_raw_monitor(jvmti, "Monitor");
  monitor_completed = create_raw_monitor(jvmti, "Monitor Completed");

  /* add capability to generate compiled method events */
  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_support_virtual_threads = 1;
  caps.can_generate_breakpoint_events = 1;
  caps.can_suspend = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(AddCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  /* set event callback */
  printf("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.Breakpoint = &Breakpoint;

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  return JNI_OK;
}

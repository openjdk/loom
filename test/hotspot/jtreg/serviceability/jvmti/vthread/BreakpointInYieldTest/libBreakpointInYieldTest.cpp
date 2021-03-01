/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

extern "C" {

static jvmtiEnv *jvmti = NULL;
static jthread exp_thread = NULL;
static jrawMonitorID event_mon = NULL;
static int breakpoint_count = 0;
static int method_entry_count = 0;
static int method_exit_count = 0;
static jboolean received_method_exit_event = JNI_FALSE;
static jboolean passed = JNI_TRUE;


static void
print_frame_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
                       const char* event_name, int event_count) {
  char* cname = NULL;
  char* mname = NULL;
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "event handler: error in JVMTI GetThreadInfo call");
  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;

  cname = get_method_class_name(jvmti, jni, method);

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "event handler: error in JVMTI GetMethodName call");

  printf("\n%s #%d: method: %s::%s, thread: %s\n",
         event_name, event_count, cname, mname, thr_name);

  print_stack_trace(jvmti, jni, thread);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
                      jint frames_cnt, const char* event_name) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "event handler failed during JVMTI GetThreadInfo call");

  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("%s: thread: %s, frames: %d\n\n", event_name, thr_name, frames_cnt);

  print_stack_trace(jvmti, jni, thread);
}

static void
set_breakpoint(JNIEnv *jni, jclass klass, const char *mname)
{
  jlocation location = (jlocation)0L;
  jmethodID method = NULL;
  jvmtiError err;

  // Find the jmethodID of the specified method
  method = find_method(jvmti, jni, klass, mname);

  if (method == NULL) {
    jni->FatalError("Error in set_breakpoint: not found method");
  }
  err = jvmti->SetBreakpoint(method, location);
  check_jvmti_status(jni, err, "set_or_clear_breakpoint: error in JVMTI SetBreakpoint");

  fflush(0);
}

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread cthread,
           jmethodID method, jlocation location) {
  jboolean is_virtual = jni->IsVirtualThread(cthread);
  const char* virt = is_virtual ? "virtual" : "carrier";
  char* mname = NULL;
  jvmtiError err;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetMethodName call");

  if (strcmp(mname, "run") != 0 && strcmp(mname, "yield") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    passed = JNI_FALSE;
    return;
  }

  RawMonitorLocker rml(jvmti, jni, event_mon);

  printf("Breakpoint: %s: Stack Trace of %s thread: %p\n",
         mname, virt, (void*)cthread);

  print_frame_event_info(jvmti, jni, cthread, method,
                         "Breakpoint", ++breakpoint_count);
  fflush(0);
}

static void JNICALL
ThreadStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "ThreadStart: error in JVMTI GetThreadInfo call");

  RawMonitorLocker rml(jvmti, jni, event_mon);

  printf("\nThreadStart: thread: %p, name: %s\n", (void*)thread, thr_info.name);
  fflush(0);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "VirtualThreadScheduled: error in JVMTI GetThreadInfo call");

  RawMonitorLocker rml(jvmti, jni, event_mon);

  printf("\nVirtualThreadScheduled: thread: %p, name: %s\n",(void*)thread, thr_info.name);
  fflush(0);
}

#if 0
static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  lock_events();
  method_entry_count++;

  jvmtiError err;
  char* mname = NULL;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "MethodEntry: error in JVMTI GetMethodName call");

  printf("MethodEntry #%d: method: %s, thread: %p\n",
         method_entry_count,  mname, (void*)thread);

  print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count);

  fflush(0);
  unlock_events();
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  lock_events();
  method_exit_count++;

  jvmtiError err;
  char* mname = NULL;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "MethodExit: error in JVMTI GetMethodName call");

  printf("MethodExit #%d: method: %s, thread: %p\n",
         method_exit_count,  mname, (void*)thread);

  print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_exit_count);
  fflush(0);
  unlock_events();
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint fcount) {
  lock_events();

  printf("\nContinuationRun: thread: %p\n", (void*)thread);

  fflush(0);
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint fcount) {
  lock_events();

  printf("\nContinuationYield: thread: %p\n", (void*)thread);
  
  fflush(0);
  unlock_events();
}
#endif

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.Breakpoint  = &Breakpoint;
  callbacks.ThreadStart = &ThreadStart;
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_generate_breakpoint_events = 1;

#if 0
  caps.can_generate_method_entry_events = 1;
  caps.can_generate_method_exit_events = 1;
  caps.can_support_continuations = 1;

  callbacks.MethodEntry = &MethodEntry;
  callbacks.MethodExit  = &MethodExit;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;
#endif

  event_mon = create_raw_monitor(jvmti, "Events Monitor");

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI AddCapabilities: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI SetEventCallbacks: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

#if 0
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }
#endif

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_BreakpointInYieldTest_enableEvents(JNIEnv *jni, jclass klass, jthread thread) {
  jvmtiError err;

  printf("enableEvents: started\n");

  jclass k1 = find_class(jvmti, jni, NULL, "Ljava/lang/VirtualThread;");
  jclass k2 = find_class(jvmti, jni, NULL, "Ljava/lang/Continuation;");
  if (k1 == NULL || k2 == NULL) {
    jni->FatalError("Did not find one of the classes by name: VirtualThread or Continuation");
  }
  set_breakpoint(jni, k1, "run");
  set_breakpoint(jni, k2, "yield");

  // Enable Breakpoint events globally
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable BREAKPOINT");

  printf("enableEvents: finished\n");
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_BreakpointInYieldTest_check(JNIEnv *jni, jclass cls) {
  printf("\n");
  printf("check: breakpoint_count:        %d\n", breakpoint_count);
  printf("check: method_exit_count:       %d\n", method_exit_count);
  printf("\n");
  fflush(0);

  return passed;
}
} // extern "C"

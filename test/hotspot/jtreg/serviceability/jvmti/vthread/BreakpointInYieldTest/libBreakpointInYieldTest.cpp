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
static jrawMonitorID event_mon = NULL;
static int method_entry_count = 0;
static int method_exit_count = 0;
static int breakpoint_count = 0;
static int vt_mounted_count = 0;
static int vt_unmounted_count = 0;
static jboolean pass_status = JNI_TRUE;


static void
print_frame_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
                       const char* event_name, int event_count) {
  char* tname = get_thread_name(jvmti, jni, thread);
  char* mname = get_method_name(jvmti, jni, method);
  char* cname = get_method_class_name(jvmti, jni, method);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  printf("\n%s #%d: method: %s::%s, %s thread: %s\n",
         event_name, event_count, cname, mname, virt, tname);

  print_stack_trace(jvmti, jni, thread);

  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)mname);
  deallocate(jvmti, jni, (void*)cname);
}

static void
set_breakpoint(JNIEnv *jni, jclass klass, const char *mname)
{
  // Find the jmethodID of the specified method
  jmethodID method = find_method(jvmti, jni, klass, mname);
  jlocation location = (jlocation)0L;
  jvmtiError err;

  if (method == NULL) {
    jni->FatalError("Error in set_breakpoint: not found method");
  }
  err = jvmti->SetBreakpoint(method, location);
  check_jvmti_status(jni, err, "set_or_clear_breakpoint: error in JVMTI SetBreakpoint");

  fflush(0);
}

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = get_method_name(jvmti, jni, method);

  if (strcmp(mname, "run") != 0 && strcmp(mname, "yield") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    pass_status = JNI_FALSE;
    deallocate(jvmti, jni, (void*)mname);
    return;
  }
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  {
    RawMonitorLocker rml(jvmti, jni, event_mon);

    printf("Breakpoint: %s: Stack Trace of %s thread: %s\n", mname, virt, tname);

    print_frame_event_info(jvmti, jni, thread, method,
                           "Breakpoint", ++breakpoint_count);
    fflush(0);
  }

  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
ThreadStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  char* tname = get_thread_name(jvmti, jni, thread);

  {
    RawMonitorLocker rml(jvmti, jni, event_mon);
    printf("\nThreadStart: thread: %p, name: %s\n", (void *) thread, tname);
    fflush(0);
  }

  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  {
    RawMonitorLocker rml(jvmti, jni, event_mon);
    printf("\nVirtualThreadStart: %s, thread: %s\n", virt, tname);
    fflush(0);
  }

  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  jvmtiError err;

  err = jvmti->GetFrameLocation(thread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI GetFrameLocation");


  RawMonitorLocker rml(jvmti, jni, event_mon);
  print_frame_event_info(jvmti, jni, thread, method,
                         "VirtualThreadMounted", ++vt_mounted_count);
  fflush(0);
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  jvmtiError err;

  err = jvmti->GetFrameLocation(thread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadMUnmounted: error in JVMTI GetFrameLocation");

  RawMonitorLocker rml(jvmti, jni, event_mon);

  print_frame_event_info(jvmti, jni, thread, method,
                         "VirtualThreadUnmounted", ++vt_unmounted_count);
  fflush(0);
}

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
  callbacks.VirtualThreadStart     = &VirtualThreadStart;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_generate_breakpoint_events = 1;

  err = jvmti->CreateRawMonitor("Events Monitor", &event_mon);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI CreateRawMonitor: %d\n", err);
    return JNI_ERR;
  }

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

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_BreakpointInYieldTest_enableEvents(JNIEnv *jni, jclass klass, jthread thread) {
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, event_mon);

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
  printf("check: breakpoint_count:     %d\n", breakpoint_count);
  printf("check: vt_mounted_count:     %d\n", vt_mounted_count);
  printf("check: vt_unmounted_count:   %d\n", vt_unmounted_count);
  printf("\n");
  fflush(0);

  return pass_status;
}
} // extern "C"

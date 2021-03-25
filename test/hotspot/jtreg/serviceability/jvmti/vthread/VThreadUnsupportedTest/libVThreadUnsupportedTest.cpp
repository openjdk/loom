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
static volatile jboolean is_completed_test_in_event = JNI_FALSE;

static void
check_jvmti_error_invalid_thread(JNIEnv* jni, const char* msg, jvmtiError err) {
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    printf("%s failed: expected JVMTI_ERROR_INVALID_THREAD instead of: %d\n", msg, err);
    fatal(jni, msg);
  }
}

JNIEXPORT jboolean JNICALL
Java_VThreadUnsupportedTest_isCompletedTestInEvent(JNIEnv *env, jobject obj) {
  return is_completed_test_in_event;
}

/*
 * Execute JVMTI functions which currently don't support vthreads and check that
 * they return error code JVMTI_ERROR_INVALID_THREAD correctly.
 */
static void
test_unsupported_jvmti_functions(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  jvmtiError err;
  jboolean is_vthread;
  jvmtiCapabilities caps;
  void* local_storage_data = NULL;
  jlong nanos;

  printf("test_unsupported_jvmti_functions: started\n");
  fflush(0);

  is_vthread = jni->IsVirtualThread(vthread);
  if (is_vthread != JNI_TRUE) {
    fatal(jni, "IsVirtualThread failed to return JNI_TRUE");
  }

  err = jvmti->GetCapabilities(&caps);
  check_jvmti_status(jni, err, "GetCapabilities");

  if (caps.can_support_virtual_threads != JNI_TRUE) {
    fatal(jni, "Virtual threads are not supported");
  }

  printf("Testing JVMTI functions which should not accept a virtual thread argument\n");
  fflush(0);

  err = jvmti->StopThread(vthread, vthread);
  check_jvmti_error_invalid_thread(jni, "StopThread", err);

  err = jvmti->InterruptThread(vthread);
  check_jvmti_error_invalid_thread(jni, "InterruptThread", err);

  err = jvmti->PopFrame(vthread);
  check_jvmti_error_invalid_thread(jni, "PopFrame", err);

  err = jvmti->ForceEarlyReturnVoid(vthread);
  check_jvmti_error_invalid_thread(jni, "ForceEarlyReturnVoid", err);

  err = jvmti->GetThreadCpuTime(vthread, &nanos);
  check_jvmti_error_invalid_thread(jni, "GetThreadCpuTime", err);

  printf("test_unsupported_jvmti_functions: finished\n");
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_VThreadUnsupportedTest_testJvmtiFunctionsInJNICall(JNIEnv *jni, jobject obj) {
  jvmtiError err = JVMTI_ERROR_NONE;
  jint threads_count = 0;
  jthread *threads = NULL;
  jthread cthread = NULL;

  printf("testJvmtiFunctionsInJNICall: started\n");
  fflush(0);

  err = jvmti->GetCurrentThread(&cthread);
  check_jvmti_status(jni, err, "GetCurrentThread");
  printf("\n#### GetCurrentThread returned thread: %p\n", (void*)cthread);
  fflush(0);

  err = jvmti->GetAllThreads(&threads_count, &threads);
  check_jvmti_status(jni, err, "GetAllThreads");

  for (int thread_idx = 0; thread_idx < (int) threads_count; thread_idx++) {
    jthread thread = threads[thread_idx];
    jthread vthread;
    char* tname = get_thread_name(jvmti, jni, thread);

    if (jni->IsSameObject(cthread, thread) == JNI_TRUE) {
      continue;
    }
    err = jvmti->SuspendThread(thread);
    if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
      continue;
    }
    check_jvmti_status(jni, err, "SuspendThread");

    err = GetVirtualThread(jvmti, jni, thread, &vthread);
    if (err == JVMTI_ERROR_THREAD_NOT_SUSPENDED) {
      // Some system threads might not fully suspended. so just skip them
      err = jvmti->ResumeThread(thread);
      check_jvmti_status(jni, err, "ResumeThread");
      continue;
    }
    check_jvmti_status(jni, err, "JVMTI extension GetVirtualThread");
    if (vthread != NULL) {
      printf("\n#### Found carrier thread: %s\n", tname);
      fflush(stdout);
      test_unsupported_jvmti_functions(jvmti, jni, vthread);
    }
    err = jvmti->ResumeThread(thread);
    check_jvmti_status(jni, err, "ResumeThread");

    deallocate(jvmti, jni, (void*)tname);
  }
  printf("testJvmtiFunctionsInJNICall: finished\n");
  fflush(0);
  return JNI_TRUE;
}

// Parameters: (jvmtiEnv *jvmti, JNIEnv* jni, jthread thread)
static void JNICALL
VirtualThreadMount(jvmtiEnv *jvmti, ...) {
  va_list ap;
  JNIEnv* jni = NULL;
  jthread thread = NULL;

  va_start(ap, jvmti);
  jni = va_arg(ap, JNIEnv*);
  thread = va_arg(ap, jthread);
  va_end(ap);

  printf("Got VirtualThreadMount event\n");
  fflush(stdout);
  test_unsupported_jvmti_functions(jvmti, jni, thread);

  jlong nanos;
  jvmtiError err = jvmti->GetCurrentThreadCpuTime(&nanos);
  check_jvmti_error_invalid_thread(jni, "GetCurrentThreadCpuTime", err);

  is_completed_test_in_event = JNI_TRUE;
}

extern JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **)(&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  err = set_ext_event_callback(jvmti, "VirtualThreadMount", VirtualThreadMount);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI SetExtEventCallback for VirtualThreadMount: %s(%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }
 
  memset(&caps, 0, sizeof (caps));
  caps.can_suspend = 1;
  caps.can_pop_frame = 1;
  caps.can_force_early_return = 1;
  caps.can_signal_thread = 1;
  caps.can_support_virtual_threads = 1;
  caps.can_access_local_variables = 1;
  caps.can_get_thread_cpu_time = 1;
  caps.can_get_current_thread_cpu_time = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI AddCapabilities: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, EXT_EVENT_VIRTUAL_THREAD_MOUNT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  printf("Agent_OnLoad finished\n");
  return JNI_OK;
}

} // extern "C"

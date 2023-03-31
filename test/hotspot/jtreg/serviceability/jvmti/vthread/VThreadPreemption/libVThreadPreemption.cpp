/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

#ifdef WINDOWS
#include <windows.h>
#else
#include <unistd.h>
#endif

extern "C" {

#define MAX_FRAME_COUNT 100

static jvmtiEnv *jvmti = NULL;

static jint vthread_mount_count = 0;
static jint vthread_unmount_count = 0;
static const char* main_name;
static volatile bool task_running = false;
static volatile bool terminate_suspend = false;


static void
checkStackTrace(JNIEnv *jni, jthread vthread) {
  jvmtiError err;
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = -1;

  err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, frames, &count);
  check_jvmti_status(jni, err, "checkStackTrace: error in JVMTI GetStackTrace");

  if (count <= 0) {
    fatal(jni, "checkStackTrace: JVMTI GetStackTrace returned frame count <= 0\n");
  }

  bool found = false;
  const char* expected_method = task_running ? main_name : "enter";
  for (int depth = 0; depth < count && !found; depth++) {
    char*  mname = NULL;
    char*  msign = NULL;

    err = jvmti->GetMethodName(frames[depth].method, &mname, &msign, NULL);
    check_jvmti_status(jni, err, "checkStackTrace: error in JVMTI GetMethodName");

    if (strcmp(mname, expected_method) == 0) found = true;

    deallocate(jvmti, jni, (void*)mname);
    deallocate(jvmti, jni, (void*)msign);
  }
  if (!found) {
    LOG("expected method %s not found in stacktrace", expected_method);
    fatal(jni, "error in checkStackTrace");
  }
}

static void
checkVThreadState(JNIEnv *jni, jthread vthread) {
  jvmtiError err;

  // vthread -> cthread -> vthread should return original vthread
  jobject thread_vthread = NULL;
  jthread cthread = NULL;
  err = GetCarrierThread(jvmti, jni, vthread, &cthread);
  check_jvmti_status(jni, err, "checkVThreadState: error in JVMTI GetCarrierThread");
  err = GetVirtualThread(jvmti, jni, cthread, &thread_vthread);
  check_jvmti_status(jni, err, "checkVThreadState: error in JVMTI GetVirtualThread");

  if (jni->IsSameObject(thread_vthread, vthread) != JNI_TRUE) {
    fatal(jni, "thread_vthread different than expected");
  }
}

// Parameters: (jvmtiEnv *jvmti, JNIEnv* jni, jthread thread)
static void JNICALL
VirtualThreadMount(jvmtiEnv *jvmti, ...) {
  va_list ap;
  JNIEnv* jni = NULL;
  jthread vthread = NULL;

  va_start(ap, jvmti);
  jni = va_arg(ap, JNIEnv*);
  vthread = va_arg(ap, jthread);
  va_end(ap);

  checkVThreadState(jni, vthread);
  checkStackTrace(jni, vthread);
  vthread_mount_count++;
}

// Parameters: (jvmtiEnv *jvmti, JNIEnv* jni, jthread thread)
static void JNICALL
VirtualThreadUnmount(jvmtiEnv *jvmti, ...) {
  va_list ap;
  JNIEnv* jni = NULL;
  jthread vthread = NULL;

  va_start(ap, jvmti);
  jni = va_arg(ap, JNIEnv*);
  vthread = va_arg(ap, jthread);
  va_end(ap);

  checkStackTrace(jni, vthread);
  vthread_unmount_count++;
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;

  LOG("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  err = set_ext_event_callback(jvmti, "VirtualThreadMount", VirtualThreadMount);
  if (err != JVMTI_ERROR_NONE) {
    LOG("Agent_OnLoad: Error in JVMTI SetExtEventCallback for VirtualThreadMount: %s(%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }
  err = set_ext_event_callback(jvmti, "VirtualThreadUnmount", VirtualThreadUnmount);
  if (err != JVMTI_ERROR_NONE) {
    LOG("Agent_OnLoad: Error in JVMTI SetExtEventCallback for VirtualThreadUnmount: %s(%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_suspend = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("error in JVMTI AddCapabilities: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, EXT_EVENT_VIRTUAL_THREAD_MOUNT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    LOG("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, EXT_EVENT_VIRTUAL_THREAD_UNMOUNT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    LOG("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  LOG("Agent_OnLoad finished\n");
  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_VThreadPreemption_markStart(JNIEnv *jni, jclass cls, jint val) {
  if (val == 1) {
    main_name = "foo1";
  } else if (val == 2) {
    main_name = "foo2";
  } else {
    main_name = "foo3";
  }
  task_running = true;
  LOG("starting task: %s\n", main_name);
}

JNIEXPORT void JNICALL
Java_VThreadPreemption_markFinish(JNIEnv *jni, jclass cls) {
  LOG("finished task: %s\n", main_name);
  task_running = false;
  terminate_suspend = true;
}

JNIEXPORT void JNICALL
Java_VThreadPreemption_suspendResume(JNIEnv *jni, jclass cls, jthread vthread) {
  jvmtiError err;
  jint state;
  jint count = 0;

  while (!terminate_suspend) {
    err = jvmti->SuspendThread(vthread);
    if (err == JVMTI_ERROR_NONE) {
      state = 0;
      err = jvmti->GetThreadState(vthread, &state);
      check_jvmti_status(jni, err, "suspendResume: error in JVMTI GetThreadState");
      if ((state & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        fatal(jni, "suspendResume: expected SUSPENDED flag in thread state");
      }

      if (task_running) {
        checkStackTrace(jni, vthread);
      }

      err = jvmti->ResumeThread(vthread);
      check_jvmti_status(jni, err, "suspendResume: error in JVMTI ResumeThread");
      count++;
    }
    // sleep 5ms to ensure progress
#ifdef WINDOWS
    Sleep(5);
#else
    usleep(5*1000);
#endif
  }
  LOG("suspendResumer exiting. Suspend/Resume count: %d\n", count);
}

JNIEXPORT jboolean JNICALL
Java_VThreadPreemption_check(JNIEnv *jni, jclass cls, jint count, jboolean strict) {
  bool passed = JNI_FALSE;
  const char* enforced_relation = strict ? "equal to" : "greater or equal than";

  if ((strict && (vthread_mount_count != count || vthread_unmount_count != count)) ||
      (!strict && (vthread_mount_count < count || vthread_unmount_count < count))) {
    LOG("FAILURE: vthread_mount_count:%d expected to be %s %d, vthread_unmount_count:%d expected to be %s %d\n",
        vthread_mount_count, enforced_relation, count, vthread_unmount_count, enforced_relation, count);
  } else {
    passed = JNI_TRUE;
    LOG("SUCCESS:\n");
  }

  // for next run
  vthread_mount_count = 0;
  vthread_unmount_count = 0;
  terminate_suspend = false;
  return passed;
}

} // extern "C"

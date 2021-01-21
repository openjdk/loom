/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <jvmti.h>
#include "jvmti_common.h"


extern "C" {


#define PASSED 0
#define STATUS_FAILED 2
#define WAIT_TIME 1000

static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jint result = PASSED;
static jrawMonitorID wait_lock;
static const char *threadName = NULL;
static int startsCount = 0;
static int startsExpected = 0;
static int endsCount = 0;
static int endsExpected = 0;

void JNICALL ThreadStart(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  jvmtiError err;
  jvmtiThreadInfo inf;

  err = jvmti->GetThreadInfo(thread, &inf);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetThreadInfo, start) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  printf(">>> start: %s\n", inf.name);

  if (inf.name != NULL && strcmp(inf.name, threadName) == 0) {
    startsCount++;
  }
}

void JNICALL ThreadEnd(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  jvmtiError err;
  jvmtiThreadInfo inf;

  err = jvmti->GetThreadInfo(thread, &inf);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetThreadInfo, end) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  printf(">>> end: %s\n", inf.name);

  if (inf.name != NULL && strcmp(inf.name, threadName) == 0) {
    endsCount++;
  }
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_threadstart03(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_threadstart03(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_threadstart03(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  callbacks.ThreadStart = &ThreadStart;
  callbacks.ThreadEnd = &ThreadEnd;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  return JNI_OK;
}

static void JNICALL
threadProc(jvmtiEnv* jvmti, JNIEnv* jni, void *unused) {
  jvmtiError err;

  err = jvmti->RawMonitorEnter(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorEnter) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RawMonitorNotify(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorNotify) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RawMonitorExit(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorExit) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
}

JNIEXPORT jint JNICALL
Java_threadstart03_check(JNIEnv *jni,
                                                jclass cls, jthread thr, jstring name) {
  jvmtiError err;

  if (jvmti == NULL) {
    printf("JVMTI client was not properly loaded!\n");
    return STATUS_FAILED;
  }

  threadName = jni->GetStringUTFChars(name, NULL);
  if (threadName == NULL) {
    printf("Failed to copy UTF-8 string!\n");
    return STATUS_FAILED;
  }

  err = jvmti->CreateRawMonitor("_wait_lock", &wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(CreateRawMonitor) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return STATUS_FAILED;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_THREAD_START, NULL);
  if (err == JVMTI_ERROR_NONE) {
    startsExpected = 1;
  } else {
    printf("Failed to enable JVMTI_EVENT_THREAD_START: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_THREAD_END, NULL);
  if (err == JVMTI_ERROR_NONE) {
    endsExpected = 1;
  } else {
    printf("Failed to enable JVMTI_EVENT_THREAD_END: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  printf(">>> starting agent thread ...\n");

  err = jvmti->RawMonitorEnter(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorEnter) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RunAgentThread(thr, threadProc,
                              NULL, JVMTI_THREAD_MAX_PRIORITY);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RunAgentThread) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RawMonitorWait(wait_lock, 0);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorWait) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RawMonitorExit(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorExit) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  err = jvmti->RawMonitorEnter(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorEnter) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  // Wait for up to 3 seconds for the thread end event

  for (int i = 0; i < 3 ; i++) {
    err = jvmti->RawMonitorWait(wait_lock, (jlong)WAIT_TIME);
    if (endsCount == endsExpected || err != JVMTI_ERROR_NONE) {
      break;
    }
  }

  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorWait) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->RawMonitorExit(wait_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(RawMonitorExit) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_THREAD_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_THREAD_START: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_THREAD_END, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_THREAD_END: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  if (startsCount != startsExpected) {
    printf("Wrong number of thread start events: %d, expected: %d\n",
           startsCount, startsExpected);
    result = STATUS_FAILED;
  }

  if (endsCount != endsExpected) {
    printf("Wrong number of thread end events: %d, expected: %d\n",
           endsCount, endsExpected);
    result = STATUS_FAILED;
  }

  return result;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}

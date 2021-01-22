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
#include <string.h>
#include <jni.h>
#include <jvmti.h>

#include "jvmti_common.h"
#include "jvmti_thread.h"


extern "C" {

/* ========================================================================== */

/* scaffold objects */
static JNIEnv *jni = NULL;
static jvmtiEnv *jvmti = NULL;
static jlong timeout = 0;

/* test objects */
static jthread expected_thread = NULL;
static jobject expected_object = NULL;
static volatile int eventsCount = 0;

/* ========================================================================== */

void JNICALL
MonitorContendedEnter(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr, jobject obj) {

  printf("MonitorContendedEnter event:\n\tthread: %p, object: %p, expected object: %p\n",thr, obj, expected_object);

  print_thread_info(jni, jvmti, thr);

  if (expected_thread == NULL) {
    jni->FatalError("expected_thread is NULL.");
  }

  if (expected_object == NULL) {
    jni->FatalError("expected_object is NULL.");
  }

  /* check if event is for tested thread and for tested object */
  if (jni->IsSameObject(expected_thread, thr) &&
      jni->IsSameObject(expected_object, obj)) {
    eventsCount++;
    printf("Increasing eventCount to %d\n", eventsCount);
  }
}

/* ========================================================================== */

static int prepare() {
  jvmtiError err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL);
  if (err != JVMTI_ERROR_NONE) {
    jni->FatalError("Error enabling JVMTI_EVENT_MONITOR_CONTENDED_ENTER.");
  }
  return NSK_TRUE;
}

static int clean() {
  jvmtiError err;
  printf("Disabling events\n");
  /* disable MonitorContendedEnter event */
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_MONITOR_CONTENDED_ENTER,
                                        NULL);
  if (err != JVMTI_ERROR_NONE) {
    nsk_jvmti_setFailStatus();
  }

  jni->DeleteGlobalRef(expected_object);
  jni->DeleteGlobalRef(expected_thread);
  return NSK_TRUE;
}

/* ========================================================================== */

/* agent algorithm
 */
static void JNICALL
agentProc(jvmtiEnv *jvmti, JNIEnv *agentJNI, void *arg) {
  jni = agentJNI;

  /* wait for initial sync */
  if (!nsk_jvmti_waitForSync(timeout)) {
    return;
  }

  if (!prepare()) {
    nsk_jvmti_setFailStatus();
    return;
  }

  /* clear events count */
  eventsCount = 0;

  /* resume debugee to catch MonitorContendedEnter event */
  if (!((nsk_jvmti_resumeSync() == NSK_TRUE) && (nsk_jvmti_waitForSync(timeout) ==NSK_TRUE))) {
    return;
  }
  NSK_DISPLAY1("Number of MonitorContendedEnter events: %d\n", eventsCount);

  if (eventsCount == 0) {
    NSK_COMPLAIN0("No any MonitorContendedEnter event\n");
    nsk_jvmti_setFailStatus();
  }

  if (!clean()) {
    nsk_jvmti_setFailStatus();
    return;
  }

/* resume debugee after last sync */
  if (!nsk_jvmti_resumeSync())
    return;
}

/* ========================================================================== */

/* agent library initialization
 */
#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_mcontenter01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_mcontenter01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_mcontenter01(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiEventCallbacks callbacks;
  jvmtiError err;
  jint res;

  timeout = 60000; //TODO fix
  NSK_DISPLAY1("Timeout: %d msc\n", (int) timeout);

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_monitor_events = 1;
  caps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(AddCapabilities) unexpected error: %s (%d)\n", TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetCapabilities) unexpected error: %s (%d)\n", TranslateError(err), err);
    return JNI_ERR;
  }

  if (!caps.can_generate_monitor_events) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.MonitorContendedEnter = &MonitorContendedEnter;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  /* register agent proc and arg */
  nsk_jvmti_setAgentProc(agentProc, NULL);

  return JNI_OK;
}

JNIEXPORT jint JNICALL Java_mcontenter01_getEventCount(JNIEnv *jni, jobject obj) {
  return eventsCount;
}

JNIEXPORT void JNICALL Java_mcontenter01_setExpected(JNIEnv *jni, jobject clz, jobject obj, jobject thread) {
  printf("Remembering global reference for monitor object is %p\n", obj);
  /* make object accessible for a long time */
  expected_object = jni->NewGlobalRef(obj);
  if (expected_object == NULL) {
    jni->FatalError("Error saving global reference to monitor.\n");
  }

  /* make thread accessable for a long time */
  expected_thread = jni->NewGlobalRef(thread);
  if (thread == NULL) {
    jni->FatalError("Error saving global reference to thread.\n");
  }

  return;
}


JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
}

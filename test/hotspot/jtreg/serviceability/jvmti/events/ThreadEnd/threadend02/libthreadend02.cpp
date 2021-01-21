/*
 * Copyright (c) 2004, 2018, Oracle and/or its affiliates. All rights reserved.
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
#include "jvmti_thread.h"


extern "C" {

/* ============================================================================= */

/* scaffold objects */
static jvmtiEnv *jvmti = NULL;
static jlong timeout = 0;

static int eventCount = 0;

/* ============================================================================= */

JNIEXPORT void JNICALL
cbThreadEnd(jvmtiEnv* jvmti, JNIEnv* jni, jthread thread) {
  eventCount++;
}

/* ============================================================================= */

static int
enableEvent(jvmtiEventMode enable, jvmtiEvent event) {
  jvmtiError err;

  if (enable == JVMTI_ENABLE) {
    NSK_DISPLAY1("enabling %s\n", TranslateEvent(event));
  } else {
    NSK_DISPLAY1("disabling %s\n", TranslateEvent(event));
  }

  err = jvmti->SetEventNotificationMode(enable, event, NULL);
  if (err != JVMTI_ERROR_NONE) {
    nsk_jvmti_setFailStatus();
    return NSK_FALSE;
  }

  return NSK_TRUE;
}

/* ============================================================================= */

int checkEvents() {

  int result = NSK_TRUE;

  if (eventCount == 0) {
    nsk_jvmti_setFailStatus();
    NSK_COMPLAIN0("Number of THREAD_END events must be greater than 0\n");
    nsk_jvmti_setFailStatus();
    result = NSK_FALSE;
  }

  return result;
}

/* ============================================================================= */

static int
setCallBacks() {
  jvmtiError err;
  jvmtiEventCallbacks eventCallbacks;
  memset(&eventCallbacks, 0, sizeof(eventCallbacks));

  eventCallbacks.ThreadEnd = cbThreadEnd;

  err = jvmti->SetEventCallbacks(&eventCallbacks, sizeof(eventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    return NSK_FALSE;
  }

  return NSK_TRUE;
}

/* ============================================================================= */

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* agentJNI, void* arg) {

  NSK_DISPLAY0("Wait for debuggee to become ready\n");
  if (!nsk_jvmti_waitForSync(timeout))
    return;

  NSK_DISPLAY0("Let debuggee to continue\n");
  if (!nsk_jvmti_resumeSync())
    return;

  if (!nsk_jvmti_waitForSync(timeout))
    return;

  if (!checkEvents()) {
    nsk_jvmti_setFailStatus();
  }

  NSK_DISPLAY0("Let debuggee to finish\n");
  if (!nsk_jvmti_resumeSync())
    return;

}

/* ============================================================================= */

/** Agent library initialization. */
#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_threadend02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_threadend02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_threadend02(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;
  jint res;

  timeout = 60 * 1000; // TODO change timeout

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }


  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  if (!setCallBacks()) {
    return JNI_ERR;
  }

  if (!enableEvent(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END)) {
    NSK_COMPLAIN0("Events could not be enabled");
    nsk_jvmti_setFailStatus();
    return JNI_ERR;
  }

  nsk_jvmti_setAgentProc(agentProc, NULL);

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}

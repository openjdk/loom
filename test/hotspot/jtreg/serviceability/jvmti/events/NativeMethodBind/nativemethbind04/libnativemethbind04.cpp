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

#include <stdlib.h>
#include <string.h>
#include <jvmti.h>
#include "jvmti_common.h"

extern "C" {

#define STATUS_FAILED 2
#define PASSED 0

/* tested method */
static const char *METHOD[] = {
    "nativeMethod", "()V"
};

/* counter for the original method calls */
static volatile int origCalls = 0;
/* counter for the redirected method calls */
static volatile int redirCalls = 0;

static volatile jint result = PASSED;
static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jrawMonitorID countLock;

/* method to be redirected used to check the native method redirection
   through the NativeMethodBind event */
JNIEXPORT void JNICALL
Java_nativemethbind04_nativeMethod(
    JNIEnv *jni, jobject obj) {
  origCalls++;
  printf("inside the nativeMethod(): calls=%d\n",
               origCalls);
}

/* redirected method used to check the native method redirection
   through the NativeMethodBind event */
static void JNICALL
redirNativeMethod(JNIEnv *jni, jobject obj) {
  redirCalls++;
  printf("inside the redirNativeMethod(): calls=%d\n",
               redirCalls);
}

/** callback functions **/
void JNICALL
NativeMethodBind(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread,
                 jmethodID method, void *addr, void **new_addr) {
  jvmtiPhase phase;
  jvmtiError err;

  char *methNam, *methSig;
  RawMonitorEnter(jni, jvmti, countLock);
  printf(">>>> NativeMethodBind event received\n");

  err = jvmti->GetPhase(&phase);
  if (err != JVMTI_ERROR_NONE) {
    printf(">>>> Error getting phase\n");
    result = STATUS_FAILED;
    RawMonitorExit(jni, jvmti, countLock);
    return;
  }

  if (phase != JVMTI_PHASE_LIVE && phase != JVMTI_PHASE_START) {
    RawMonitorExit(jni, jvmti, countLock);
    return;
  }

  err = jvmti->GetMethodName(method, &methNam, &methSig, NULL);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to get method name during NativeMethodBind callback\n\n");
    RawMonitorExit(jni, jvmti, countLock);
    return;
  }

  if ((strcmp(methNam, METHOD[0]) == 0) &&
      (strcmp(methSig, METHOD[1]) == 0)) {
    printf("\tmethod: \"%s %s\"\nRedirecting the method address from 0x%p to 0x%p ...\n",
                 methNam, methSig, addr, (void *) redirNativeMethod);

    *new_addr = (void *) redirNativeMethod;
  }

  if (methNam != NULL) {
    err = jvmti->Deallocate((unsigned char *) methNam);
    if (err != JVMTI_ERROR_NONE) {
      result = STATUS_FAILED;
      printf("TEST FAILED: unable to deallocate memory pointed to method name\n\n");
    }
  }

  if (methSig != NULL) {
    err = jvmti->Deallocate((unsigned char *) methSig);
    if (err != JVMTI_ERROR_NONE) {
      result = STATUS_FAILED;
      printf("TEST FAILED: unable to deallocate memory pointed to method signature\n\n");
    }
  }
  printf("<<<<\n\n");

  RawMonitorExit(jni, jvmti, countLock);
}
/************************/

JNIEXPORT jint JNICALL
Java_nativemethbind04_check(
    JNIEnv *jni, jobject obj) {

  if (origCalls == 0) {
    printf(
        "CHECK PASSED: original nativeMethod() to be redirected\n"
        "\thas not been invoked as expected\n");
  } else {
    result = STATUS_FAILED;
    NSK_COMPLAIN1(
        "TEST FAILED: nativeMethod() has not been redirected by the NativeMethodBind:\n"
        "\t%d calls\texpected: 0\n\n",
        origCalls);
  }

  if (redirCalls == 1) {
    printf(
        "CHECK PASSED: nativeMethod() has been redirected by the NativeMethodBind:\n"
        "\t%d calls of redirected method as expected\n",
        redirCalls);
  } else {
    result = STATUS_FAILED;
    NSK_COMPLAIN1(
        "TEST FAILED: nativeMethod() has not been redirected by the NativeMethodBind:\n"
        "\t%d calls of redirected method\texpected: 1\n\n",
        redirCalls);
  }

  return result;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_nativemethbind04(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_nativemethbind04(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_nativemethbind04(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_9);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  /* create a raw monitor */
  err = jvmti->CreateRawMonitor("_counter_lock", &countLock);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  /* add capability to generate compiled method events */
  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_native_method_bind_events = 1;

  // TODO Fix!!
  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  if (!caps.can_generate_native_method_bind_events)
    printf("Warning: generation of native method bind events is not implemented\n");

  /* set event callback */
  printf("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.NativeMethodBind = &NativeMethodBind;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE)
    return JNI_ERR;

  printf("setting event callbacks done\nenabling JVMTI events ...\n");
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_NATIVE_METHOD_BIND,
                                        NULL);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  printf("enabling the events done\n\n");

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}

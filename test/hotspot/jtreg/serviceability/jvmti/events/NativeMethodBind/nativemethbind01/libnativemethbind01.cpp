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
#include <jvmti.h>
#include "jvmti_common.h"

extern "C" {

#define STATUS_FAILED 2
#define PASSED 0

/* tested methods */
#define METH_NUM 2
static const char *METHODS[][2] = {
    { "nativeMethod", "(Z)V" },
    { "anotherNativeMethod", "()V" },
};

/* event counters for the tested methods and expected numbers
   of the events */
static volatile int bindEv[][2] = {
    { 0, 1 },
    { 0, 1 }
};

static const char *CLASS_SIG =
    "Lnativemethbind01$TestedClass;";

static volatile jint result = PASSED;
static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jrawMonitorID countLock;

/** callback functions **/
void JNICALL
NativeMethodBind(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
                 jmethodID method, void *addr, void **new_addr) {
  jvmtiPhase phase;
  char *methNam, *methSig;
  int i;
  jvmtiError err;

  RawMonitorEnter(jni, jvmti, countLock);

  printf(">>>> NativeMethodBind event received\n");

  err = jvmti->GetPhase(&phase);
  if (err != JVMTI_ERROR_NONE) {
    printf(">>>> Error getting phase\n");
    result = STATUS_FAILED;
    RawMonitorExit(jni, jvmti, countLock);
    return;
  }

  if (phase != JVMTI_PHASE_START && phase != JVMTI_PHASE_LIVE) {
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

  printf("method: \"%s %s\"\n", methNam, methSig);

  for (i=0; i<METH_NUM; i++) {
    if ((strcmp(methNam,METHODS[i][0]) == 0) &&
        (strcmp(methSig,METHODS[i][1]) == 0)) {
      bindEv[i][0]++;

      printf(
          "CHECK PASSED: NativeMethodBind event received for the method:\n"
          "\t\"%s\" as expected\n",
          methNam);
      break;
    }
  }

  err = jvmti->Deallocate((unsigned char*) methNam);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to deallocate memory pointed to method name\n\n");
  }
  err =  jvmti->Deallocate((unsigned char*) methSig);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILED: unable to deallocate memory pointed to method signature\n\n");
  }

  printf("<<<<\n\n");

  RawMonitorExit(jni, jvmti, countLock);
}
/************************/

/* dummy method used only to provoke NativeMethodBind event */
static void JNICALL
anotherNativeMethod(JNIEnv *jni, jobject obj) {
  printf("inside the anotherNativeMethod()\n");
}

/* dummy method used only to provoke NativeMethodBind event */
JNIEXPORT void JNICALL
Java_nativemethbind01_nativeMethod(
    JNIEnv *jni, jobject obj, jboolean registerNative) {
  jclass testedCls = NULL;
  JNINativeMethod meth;

  printf("Inside the nativeMethod()\n");

  if (registerNative == JNI_TRUE) {
    printf("Finding class \"%s\" ...\n", CLASS_SIG);
    testedCls = jni->FindClass(CLASS_SIG);
    if (testedCls == NULL) {
      result = STATUS_FAILED;
      NSK_COMPLAIN1("TEST FAILURE: unable to find class \"%s\"\n\n",
                    CLASS_SIG);
      return;
    }

    meth.name = (char *) METHODS[1][0];
    meth.signature = (char *) METHODS[1][1];
    meth.fnPtr = (void *) &anotherNativeMethod;

    printf("Calling RegisterNatives() with \"%s %s\"\n"
                 "\tfor class \"%s\" ...\n",
                 METHODS[1][0], METHODS[1][1], CLASS_SIG);
    if (jni->RegisterNatives(testedCls, &meth, 1) != 0) {
      result = STATUS_FAILED;
      NSK_COMPLAIN3("TEST FAILURE: unable to RegisterNatives() \"%s %s\" for class \"%s\"\n\n",
                    METHODS[1][0], METHODS[1][1], CLASS_SIG);
    }
  }
}

JNIEXPORT jint JNICALL
Java_nativemethbind01_check(
    JNIEnv *jni, jobject obj) {
  int i;

  for (i=0; i<METH_NUM; i++)
    if (bindEv[i][0] == bindEv[i][1]) {
      printf("CHECK PASSED: %d NativeMethodBind event(s) for the method \"%s\" as expected\n",
                   bindEv[i][0], METHODS[i][0]);
    }
    else {
      result = STATUS_FAILED;
      NSK_COMPLAIN3(
          "TEST FAILED: wrong number of NativeMethodBind events for the method \"%s\":\n"
          "got: %d\texpected: %d\n\n",
          METHODS[i][0], bindEv[i][0], bindEv[i][1]);
    }

  return result;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_nativemethbind01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_nativemethbind01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_nativemethbind01(JavaVM *jvm, char *options, void *reserved) {
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

  if (!caps.can_generate_native_method_bind_events) {
    printf("Warning: generation of native method bind events is not implemented\n");
  }

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
  if (err != JVMTI_ERROR_NONE){
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

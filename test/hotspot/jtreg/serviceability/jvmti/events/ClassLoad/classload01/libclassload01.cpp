/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

#define PASSED  0
#define STATUS_FAILED  2

/* classes which must have the class load event */
static const char *expSigs[] = {
    "Lclassload01;",
    "Lclassload01$TestedClass;",
    "LTestedClassVirtual;",
};
#define EXP_SIG_NUM (sizeof(expSigs)/sizeof(char*))

static const jboolean expThreadIsVirtual[] = {
    JNI_FALSE,
    JNI_FALSE,
    JNI_TRUE
};

/* classes which must not have the class load event */
static const char *unexpSigs[] = {
    "Z", /* boolean */
    "B", /* byte */
    "C", /* char */
    "D", /* double */
    "F", /* float */
    "I", /* integer */
    "J", /* long */
    "S", /* short */

    "[Z", /* boolean array */
    "[B", /* byte array */
    "[C", /* char array */
    "[D", /* double array */
    "[F", /* float array */
    "[I", /* integer array */
    "[J", /* long array */
    "[S", /* short array */
    "[Lclassload01$TestedClass;"
};
#define UNEXP_SIG_NUM (sizeof(unexpSigs)/sizeof(char*))

static volatile int clsEvents[EXP_SIG_NUM];
static volatile int primClsEvents[UNEXP_SIG_NUM];

static jint result = PASSED;
static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jrawMonitorID countLock;

static void initCounters() {
  size_t i;

  for (i = 0; i < EXP_SIG_NUM; i++) {
    clsEvents[i] = 0;
  }

  for (i = 0; i < UNEXP_SIG_NUM; i++) {
    primClsEvents[i] = 0;
  }
}

static int findSig(char *sig, int expected) {
  unsigned int i;

  for (i = 0; i < ((expected == 1) ? EXP_SIG_NUM : UNEXP_SIG_NUM); i++) {
    if (sig != NULL &&
        strcmp(((expected == 1) ? expSigs[i] : unexpSigs[i]), sig) == 0) {
      return i; /* the signature found, return index */
    }
  }
  return -1; /* the signature not found */
}

/** callback functions **/
void JNICALL
ClassLoad(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jclass klass) {
  int i = 0;
  char *sig, *generic;
  jvmtiError err;

  RawMonitorEnter(jni, jvmti, countLock);

  err = jvmti->GetClassSignature(klass, &sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    result = STATUS_FAILED;
    printf("TEST FAILURE: unable to obtain a class signature. Error %d\n", err);
    RawMonitorExit(jni, jvmti, countLock);
    return;
  }

  i = findSig(sig, 1);
  if (i != -1) {
    jboolean is_virtual_thread = jni->IsVirtualThread(thread);
    print_thread_info(jni, jvmti, thread);
    if (is_virtual_thread != expThreadIsVirtual[i]) {
      printf("TEST FAILED: IsVirtualThread(thread) is not expected: %d\n", is_virtual_thread);
      result = STATUS_FAILED;
    } else {
      clsEvents[i]++;
      printf("CHECK PASSED: ClassLoad event received for the class \"%s\" as expected\n", sig);
    }
  } else {
    i = findSig(sig, 0);
    if (i != -1) {
      result = STATUS_FAILED;
      primClsEvents[i]++;
      printf(
          "TEST FAILED: JVMTI_EVENT_CLASS_LOAD event received for\n"
          "\t a primitive class/array of primitive types with the signature \"%s\"\n",
          sig);
    }
  }

  RawMonitorExit(jni, jvmti, countLock);
}
/************************/

JNIEXPORT jint JNICALL
Java_classload01_check(JNIEnv *jni, jobject obj) {
  size_t i;

  for (i = 0; i < EXP_SIG_NUM; i++) {
    if (clsEvents[i] != 1) {
      result = STATUS_FAILED;
      printf("TEST FAILED: wrong number of JVMTI_EVENT_CLASS_LOAD events for \"%s\":\n\tgot: %d\texpected: 1\n",
             expSigs[i], clsEvents[i]);
    }
  }

  for (i = 0; i < UNEXP_SIG_NUM; i++) {
    if (primClsEvents[i] != 0) {
      printf("TEST FAILED: there are JVMTI_EVENT_CLASS_LOAD events for the primitive classes\n");
    }
  }
  return result;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_classload01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_classload01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_classload01(JavaVM *jvm, char *options, void *reserved) {
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

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  if (!caps.can_support_virtual_threads) {
    printf("ERROR: virtual thread support is not implemented.\n");
    return JNI_ERR;
  }


  initCounters();

  countLock = CreateRawMonitor(jvmti, "_counter_lock");
  if (countLock == nullptr) {
    printf("Error in CreateRawMonitor %d/n", err);
    return JNI_ERR;
  }

  printf("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.ClassLoad = &ClassLoad;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Error in SetEventCallbacks %d/n", err);
    return JNI_ERR;
  }

  printf("setting event callbacks done\nenabling ClassLoad event ...\n");
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_LOAD, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Error in SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }
  printf("the event enabled\n");

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}

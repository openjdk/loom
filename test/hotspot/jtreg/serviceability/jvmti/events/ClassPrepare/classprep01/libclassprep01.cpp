/*
 * Copyright (c) 2003, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include <inttypes.h>
#include "jvmti.h"

#include "jvmti_common.h"


extern "C" {


#define PASSED 0
#define STATUS_FAILED 2

#define EXP_STATUS (JVMTI_CLASS_STATUS_VERIFIED | JVMTI_CLASS_STATUS_PREPARED)

typedef struct {
  char *sig;
  jint status;
  jint mcount;
  jint fcount;
  jint icount;
} writable_class_info;

typedef struct {
  const char *sig;
  jint status;
  jint mcount;
  jint fcount;
  jint icount;
} class_info;

static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jint result = PASSED;
static volatile size_t eventsCount = 0; // TODO these 2 vars mofified from different threads in getReady/check. What to DO???
static size_t eventsExpected = 0;

static class_info *classes;

static class_info kernel_classes[] = {
    { "Lclassprep01$TestInterface;", EXP_STATUS, 2, 1, 0 },
    { "Lclassprep01$TestClass;", EXP_STATUS, 3, 2, 1 }
};

static class_info virtual_classes[] = {
    { "Lclassprep01$TestInterfaceVirtual;", EXP_STATUS, 2, 1, 0 },
    { "Lclassprep01$TestClassVirtual;", EXP_STATUS, 3, 2, 1 }
};

void printStatus(jint status) {
  int flags = 0;
  if ((status & JVMTI_CLASS_STATUS_VERIFIED) != 0) {
    printf("JVMTI_CLASS_STATUS_VERIFIED");
    flags++;
  }
  if ((status & JVMTI_CLASS_STATUS_PREPARED) != 0) {
    if (flags > 0) printf(" | ");
    printf("JVMTI_CLASS_STATUS_PREPARED");
    flags++;
  }
  if ((status & JVMTI_CLASS_STATUS_INITIALIZED) != 0) {
    if (flags > 0) printf(" | ");
    printf("JVMTI_CLASS_STATUS_INITIALIZED");
    flags++;
  }
  if ((status & JVMTI_CLASS_STATUS_ERROR) != 0) {
    if (flags > 0) printf(" | ");
    printf("JVMTI_CLASS_STATUS_ERROR");
    flags++;
  }
  printf(" (0x%x)\n", status);
}

void JNICALL ClassPrepare(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr, jclass cls) {
  jvmtiError err;
  writable_class_info inf;
  jmethodID *methods;
  jfieldID *fields;
  jclass *interfaces;
  char *name, *sig, *generic;
  int i;

  err = jvmti->GetClassSignature(cls, &inf.sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  // filter only events for test classes using classprep01 as a prefix
  // there are a lot of classes might be generated and loaded
  if (strncmp("Lclassprep01", inf.sig, 12) !=0) {
    return;
  }
  err = jvmti->GetClassStatus(cls, &inf.status);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassStatus#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->GetClassMethods(cls, &inf.mcount, &methods);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassMethods#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetClassFields(cls, &inf.fcount, &fields);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassMethods#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetImplementedInterfaces(cls, &inf.icount, &interfaces);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetImplementedInterfaces#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  printf(">>> [class prepare event #%" PRIuPTR "]", eventsCount);
  printf(" \"%s\"\n", inf.sig);
  printf(">>> Got ClassPrep event in thread.\n");
  print_thread_info(jvmti, jni, thr);
  printf(">>>   status: ");
  printStatus(inf.status);
  printf(">>>   %d methods:", inf.mcount);
  for (i = 0; i < inf.mcount; i++) {
    if (i > 0) printf(",");
    if (methods[i] == NULL) {
      printf(" null");
    } else {
      err = jvmti->GetMethodName(methods[i], &name, &sig, &generic);
      if (err == JVMTI_ERROR_NONE) {
        printf(" \"%s%s\"", name, sig);
      } else {
        printf(" ???");
      }
    }
  }
  printf("\n");
  printf(">>>   %d fields:", inf.fcount);
  for (i = 0; i < inf.fcount; i++) {
    if (i > 0) printf(",");
    if (fields[i] == NULL) {
      printf(" null");
    } else {
      err = jvmti->GetFieldName(cls, fields[i],
                                    &name, &sig, &generic);
      if (err == JVMTI_ERROR_NONE) {
        printf(" \"%s, %s\"", name, sig);
      } else {
        printf(" ???");
      }
    }
  }
  printf("\n");
  printf(">>>   %d interfaces:", inf.icount);
  for (i = 0; i < inf.icount; i++) {
    if (i > 0) printf(",");
    if (interfaces[i] == NULL) {
      printf(" null");
    } else {
      err = jvmti->GetClassSignature(interfaces[i], &sig, &generic);
      if (err == JVMTI_ERROR_NONE) {
        printf(" \"%s\"", sig);
      } else {
        printf(" ???");
      }
    }
  }
  printf("\n");


  if (eventsCount >= eventsExpected) {
    printf("(#%" PRIuPTR ") too many events: %" PRIuPTR ", expected: %" PRIuPTR "\n",
           eventsCount, eventsCount + 1, eventsExpected);
    result = STATUS_FAILED;
    return;
  }

  if (jni->IsVirtualThread(thr) != (classes == virtual_classes)) {
    printf("Thread IsVirtual differs from expected. Check log.\n");
    result = STATUS_FAILED;
    return;
  }

  if (inf.sig == NULL || strcmp(inf.sig, classes[eventsCount].sig) != 0) {
    printf("(#%" PRIuPTR ") wrong class: \"%s\"", eventsCount, inf.sig);
    printf(", expected: \"%s\"\n", classes[eventsCount].sig);
    result = STATUS_FAILED;
  }
  if (inf.status != classes[eventsCount].status) {
    printf("(#%" PRIuPTR ") wrong status: ", eventsCount);
    printStatus(inf.status);
    printf("     expected: ");
    printStatus(classes[eventsCount].status);
    result = STATUS_FAILED;
  }
  if (inf.mcount != classes[eventsCount].mcount) {
    printf("(#%" PRIuPTR ") wrong number of methods: 0x%x",
           eventsCount, inf.mcount);
    printf(", expected: 0x%x\n", classes[eventsCount].mcount);
    result = STATUS_FAILED;
  }
  if (inf.fcount != classes[eventsCount].fcount) {
    printf("(#%" PRIuPTR ") wrong number of fields: 0x%x",
           eventsCount, inf.fcount);
    printf(", expected: 0x%x\n", classes[eventsCount].fcount);
    result = STATUS_FAILED;
  }
  if (inf.icount != classes[eventsCount].icount) {
    printf("(#%" PRIuPTR ") wrong number of interfaces: 0x%x",
           eventsCount, inf.icount);
    printf(", expected: 0x%x\n", classes[eventsCount].icount);
    result = STATUS_FAILED;
  }
  eventsCount++;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_classprep01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_classprep01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_classprep01(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
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

  callbacks.ClassPrepare = &ClassPrepare;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_classprep01_getReady(JNIEnv *jni, jclass cls) {
  jvmtiError err;
  jthread prep_thread;

  if (jvmti == NULL) {
    printf("JVMTI client was not properly loaded!\n");
    return;
  }

  err = jvmti->GetCurrentThread(&prep_thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  eventsCount = 0;
  if (jni->IsVirtualThread(prep_thread)) {
    classes = virtual_classes;
    eventsExpected = sizeof(virtual_classes)/sizeof(class_info);
  } else {
    classes = kernel_classes;
    eventsExpected = sizeof(kernel_classes)/sizeof(class_info);
  }
  printf("Requesting enabling JVMTI_EVENT_CLASS_PREPARE in thread.\n");
  print_thread_info(jvmti, jni, prep_thread);

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_CLASS_PREPARE, prep_thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to enable JVMTI_EVENT_CLASS_PREPARE: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
}

JNIEXPORT jint JNICALL
Java_classprep01_check(JNIEnv *jni, jclass cls) {
  jvmtiError err;
  jthread prep_thread;

  if (jvmti == NULL) {
    printf("JVMTI client was not properly loaded!\n");
    return STATUS_FAILED;
  }

  err = jvmti->GetCurrentThread(&prep_thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    return STATUS_FAILED;
  }

  printf("Requesting disabling JVMTI_EVENT_CLASS_PREPARE in thread.\n");
  print_thread_info(jvmti, jni, prep_thread);

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_CLASS_PREPARE, prep_thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_CLASS_PREPARE: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  if (eventsCount != eventsExpected) {
    printf("Wrong number of class prepare events: %" PRIuPTR ", expected: %" PRIuPTR "\n",
           eventsCount, eventsExpected);
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

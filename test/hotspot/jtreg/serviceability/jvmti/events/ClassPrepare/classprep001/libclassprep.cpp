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
static jboolean printdump = JNI_TRUE;
static size_t eventsCount = 0;
static size_t eventsExpected = 0;
static class_info classes[] = {
    { "Lclassprep001$TestInterface;", EXP_STATUS, 2, 1, 0 },
    { "Lclassprep001$TestClass;", EXP_STATUS, 3, 2, 1 }
};

const char* TranslateError(jvmtiError err) {
  switch (err) {
    case JVMTI_ERROR_NONE:
      return ("JVMTI_ERROR_NONE");
    case JVMTI_ERROR_INVALID_THREAD:
      return ("JVMTI_ERROR_INVALID_THREAD");
    case JVMTI_ERROR_INVALID_THREAD_GROUP:
      return ("JVMTI_ERROR_INVALID_THREAD_GROUP");
    case JVMTI_ERROR_INVALID_PRIORITY:
      return ("JVMTI_ERROR_INVALID_PRIORITY");
    case JVMTI_ERROR_THREAD_NOT_SUSPENDED:
      return ("JVMTI_ERROR_THREAD_NOT_SUSPENDED");
    case JVMTI_ERROR_THREAD_SUSPENDED:
      return ("JVMTI_ERROR_THREAD_SUSPENDED");
    case JVMTI_ERROR_THREAD_NOT_ALIVE:
      return ("JVMTI_ERROR_THREAD_NOT_ALIVE");
    case JVMTI_ERROR_INVALID_OBJECT:
      return ("JVMTI_ERROR_INVALID_OBJECT");
    case JVMTI_ERROR_INVALID_CLASS:
      return ("JVMTI_ERROR_INVALID_CLASS");
    case JVMTI_ERROR_CLASS_NOT_PREPARED:
      return ("JVMTI_ERROR_CLASS_NOT_PREPARED");
    case JVMTI_ERROR_INVALID_METHODID:
      return ("JVMTI_ERROR_INVALID_METHODID");
    case JVMTI_ERROR_INVALID_LOCATION:
      return ("JVMTI_ERROR_INVALID_LOCATION");
    case JVMTI_ERROR_INVALID_FIELDID:
      return ("JVMTI_ERROR_INVALID_FIELDID");
    case JVMTI_ERROR_NO_MORE_FRAMES:
      return ("JVMTI_ERROR_NO_MORE_FRAMES");
    case JVMTI_ERROR_OPAQUE_FRAME:
      return ("JVMTI_ERROR_OPAQUE_FRAME");
    case JVMTI_ERROR_TYPE_MISMATCH:
      return ("JVMTI_ERROR_TYPE_MISMATCH");
    case JVMTI_ERROR_INVALID_SLOT:
      return ("JVMTI_ERROR_INVALID_SLOT");
    case JVMTI_ERROR_DUPLICATE:
      return ("JVMTI_ERROR_DUPLICATE");
    case JVMTI_ERROR_NOT_FOUND:
      return ("JVMTI_ERROR_NOT_FOUND");
    case JVMTI_ERROR_INVALID_MONITOR:
      return ("JVMTI_ERROR_INVALID_MONITOR");
    case JVMTI_ERROR_NOT_MONITOR_OWNER:
      return ("JVMTI_ERROR_NOT_MONITOR_OWNER");
    case JVMTI_ERROR_INTERRUPT:
      return ("JVMTI_ERROR_INTERRUPT");
    case JVMTI_ERROR_INVALID_CLASS_FORMAT:
      return ("JVMTI_ERROR_INVALID_CLASS_FORMAT");
    case JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION:
      return ("JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION");
    case JVMTI_ERROR_FAILS_VERIFICATION:
      return ("JVMTI_ERROR_FAILS_VERIFICATION");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED");
    case JVMTI_ERROR_INVALID_TYPESTATE:
      return ("JVMTI_ERROR_INVALID_TYPESTATE");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED");
    case JVMTI_ERROR_UNSUPPORTED_VERSION:
      return ("JVMTI_ERROR_UNSUPPORTED_VERSION");
    case JVMTI_ERROR_NAMES_DONT_MATCH:
      return ("JVMTI_ERROR_NAMES_DONT_MATCH");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED:
      return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED");
    case JVMTI_ERROR_UNMODIFIABLE_CLASS:
      return ("JVMTI_ERROR_UNMODIFIABLE_CLASS");
    case JVMTI_ERROR_NOT_AVAILABLE:
      return ("JVMTI_ERROR_NOT_AVAILABLE");
    case JVMTI_ERROR_MUST_POSSESS_CAPABILITY:
      return ("JVMTI_ERROR_MUST_POSSESS_CAPABILITY");
    case JVMTI_ERROR_NULL_POINTER:
      return ("JVMTI_ERROR_NULL_POINTER");
    case JVMTI_ERROR_ABSENT_INFORMATION:
      return ("JVMTI_ERROR_ABSENT_INFORMATION");
    case JVMTI_ERROR_INVALID_EVENT_TYPE:
      return ("JVMTI_ERROR_INVALID_EVENT_TYPE");
    case JVMTI_ERROR_ILLEGAL_ARGUMENT:
      return ("JVMTI_ERROR_ILLEGAL_ARGUMENT");
    case JVMTI_ERROR_NATIVE_METHOD:
      return ("JVMTI_ERROR_NATIVE_METHOD");
    case JVMTI_ERROR_OUT_OF_MEMORY:
      return ("JVMTI_ERROR_OUT_OF_MEMORY");
    case JVMTI_ERROR_ACCESS_DENIED:
      return ("JVMTI_ERROR_ACCESS_DENIED");
    case JVMTI_ERROR_WRONG_PHASE:
      return ("JVMTI_ERROR_WRONG_PHASE");
    case JVMTI_ERROR_INTERNAL:
      return ("JVMTI_ERROR_INTERNAL");
    case JVMTI_ERROR_UNATTACHED_THREAD:
      return ("JVMTI_ERROR_UNATTACHED_THREAD");
    case JVMTI_ERROR_INVALID_ENVIRONMENT:
      return ("JVMTI_ERROR_INVALID_ENVIRONMENT");
    default:
      return ("<unknown error>");
  }
}

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

void JNICALL ClassPrepare(jvmtiEnv *jvmti_env, JNIEnv *env,
                          jthread thr, jclass cls) {
  jvmtiError err;
  writable_class_info inf;
  jmethodID *methods;
  jfieldID *fields;
  jclass *interfaces;
  char *name, *sig, *generic;
  int i;

  err = jvmti_env->GetClassSignature(cls, &inf.sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti_env->GetClassStatus(cls, &inf.status);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassStatus#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti_env->GetClassMethods(cls, &inf.mcount, &methods);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassMethods#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti_env->GetClassFields(cls, &inf.fcount, &fields);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassMethods#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti_env->GetImplementedInterfaces(cls,
                                            &inf.icount, &interfaces);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetImplementedInterfaces#%" PRIuPTR ") unexpected error: %s (%d)\n",
           eventsCount, TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  if (printdump == JNI_TRUE) {
    printf(">>> [class prepare event #%" PRIuPTR "]", eventsCount);
    printf(" \"%s\"\n", inf.sig);
    printf(">>>   status: ");
    printStatus(inf.status);
    printf(">>>   %d methods:", inf.mcount);
    for (i = 0; i < inf.mcount; i++) {
      if (i > 0) printf(",");
      if (methods[i] == NULL) {
        printf(" null");
      } else {
        err = jvmti_env->GetMethodName(methods[i],
                                       &name, &sig, &generic);
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
        err = jvmti_env->GetFieldName(cls, fields[i],
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
        err = jvmti_env->GetClassSignature(
            interfaces[i], &sig, &generic);
        if (err == JVMTI_ERROR_NONE) {
          printf(" \"%s\"", sig);
        } else {
          printf(" ???");
        }
      }
    }
    printf("\n");
  }

  if (eventsCount >= eventsExpected) {
    printf("(#%" PRIuPTR ") too many events: %" PRIuPTR ", expected: %" PRIuPTR "\n",
           eventsCount, eventsCount + 1, eventsExpected);
    result = STATUS_FAILED;
    return;
  }

  if (inf.sig == NULL || strcmp(inf.sig, classes[eventsCount].sig) != 0) {
    printf("(#%" PRIuPTR ") wrong class: \"%s\"",
           eventsCount, inf.sig);
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
JNIEXPORT jint JNICALL Agent_OnLoad_classprep001(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_classprep001(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_classprep001(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;
  jint res;

  if (options != NULL && strcmp(options, "printdump") == 0) {
    printdump = JNI_TRUE;
  }

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
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
Java_classprep001_getReady(JNIEnv *env, jclass cls) {
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

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_CLASS_PREPARE, prep_thread);
  if (err == JVMTI_ERROR_NONE) {
    eventsExpected = sizeof(classes)/sizeof(class_info);
  } else {
    printf("Failed to enable JVMTI_EVENT_CLASS_PREPARE: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
}

JNIEXPORT jint JNICALL
Java_classprep001_check(JNIEnv *env, jclass cls) {
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

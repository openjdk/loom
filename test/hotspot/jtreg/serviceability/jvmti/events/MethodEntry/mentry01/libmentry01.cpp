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

typedef struct {
  char *name;
  char *sig;
  jlocation loc;
} writable_entry_info;

typedef struct {
  const char *name;
  const char *sig;
  const jlocation loc;
} entry_info;

static jvmtiEnv *jvmti = NULL;
static jvmtiCapabilities caps;
static jvmtiEventCallbacks callbacks;
static jint result = PASSED;
static jboolean isVirtualExpected = JNI_FALSE;
static size_t eventsExpected = 0;
static size_t eventsCount = 0;
static entry_info entries[] = {
    { "check", "()I", -1 },
    { "dummy", "()V", 0 },
    { "chain", "()V", -1 }
};

void JNICALL MethodEntry(jvmtiEnv *jvmti, JNIEnv *jni,
                         jthread thread_obj, jmethodID method) {
  jvmtiError err;
  char *cls_sig, *generic;
  writable_entry_info entry;
  jclass cls;
  jmethodID mid;
  char buffer[32];

  err = jvmti->GetMethodDeclaringClass(method, &cls);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetMethodDeclaringClass) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetClassSignature(cls, &cls_sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  if (cls_sig != NULL && strcmp(cls_sig, "Lmentry01;") == 0) {
    printf(">>> retrieving method entry info ...\n");

    err = jvmti->GetMethodName(method, &entry.name, &entry.sig, &generic);
    if (err != JVMTI_ERROR_NONE) {
      printf("(GetMethodName) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      result = STATUS_FAILED;
      return;
    }
    err = jvmti->GetFrameLocation(thread_obj, 0, &mid, &entry.loc);
    if (err != JVMTI_ERROR_NONE) {
      printf("(GetFrameLocation) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      result = STATUS_FAILED;
      return;
    }

    printf(">>>      class: \"%s\"\n", cls_sig);
    printf(">>>     method: \"%s%s\"\n", entry.name, entry.sig);
    printf(">>>   location: %s\n", jlong_to_string(entry.loc, buffer));
    printf(">>> ... done\n");

    if (eventsCount < sizeof(entries)/sizeof(entry_info)) {
      if (entry.name == NULL ||
          strcmp(entry.name, entries[eventsCount].name) != 0) {
        printf("(entry#%" PRIuPTR ") wrong method name: \"%s\"",
               eventsCount, entry.name);
        printf(", expected: \"%s\"\n", entries[eventsCount].name);
        result = STATUS_FAILED;
      }
      if (entry.sig == NULL ||
          strcmp(entry.sig, entries[eventsCount].sig) != 0) {
        printf("(entry#%" PRIuPTR ") wrong method sig: \"%s\"",
               eventsCount, entry.sig);
        printf(", expected: \"%s\"\n", entries[eventsCount].sig);
        result = STATUS_FAILED;
      }
      if (entry.loc != entries[eventsCount].loc) {
        printf("(entry#%" PRIuPTR ") wrong location: %s",
               eventsCount, jlong_to_string(entry.loc, buffer));
        printf(", expected: %s\n",
               jlong_to_string(entries[eventsCount].loc, buffer));
        result = STATUS_FAILED;
      }
      jboolean isVirtual = jni->IsVirtualThread(thread_obj);
      if (isVirtualExpected != isVirtual) {
        printf("The thread IsVirtualThread %d differs from expected %d.\n", isVirtual, isVirtualExpected);
        result = STATUS_FAILED;
      }
    } else {
      printf("Unexpected method entry catched:");
      printf("     class: \"%s\"\n", cls_sig);
      printf("    method: \"%s%s\"\n", entry.name, entry.sig);
      printf("  location: %s\n", jlong_to_string(entry.loc, buffer));
      result = STATUS_FAILED;
    }
    eventsCount++;
  }
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_mentry01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_mentry01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_mentry01(JavaVM *jvm, char *options, void *reserved) {
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
  caps.can_generate_method_entry_events = 1;
  caps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(AddCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetCapabilities) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  if (caps.can_generate_method_entry_events) {
    callbacks.MethodEntry = &MethodEntry;
    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      return JNI_ERR;
    }
  } else {
    printf("Warning: MethodEntry event is not implemented\n");
  }

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_mentry01_enable(JNIEnv *jni, jclass cls) {
  jvmtiError err;

  if (jvmti == NULL) {
    return;
  }

  jthread thread;
  err = jvmti->GetCurrentThread(&thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
  }
  isVirtualExpected = jni->IsVirtualThread(thread);

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_METHOD_ENTRY, NULL);
  if (err == JVMTI_ERROR_NONE) {
    eventsExpected = sizeof(entries)/sizeof(entry_info);
    eventsCount = 0;
  } else {
    printf("Failed to enable JVMTI_EVENT_METHOD_ENTRY event: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
  }
}

JNIEXPORT jint JNICALL
Java_mentry01_check(JNIEnv *jni, jclass cls) {
  jmethodID mid;

  mid = jni->GetStaticMethodID(cls, "dummy", "()V");
  if (mid == NULL) {
    printf("Cannot find metod \"dummy()\"!\n");
    return STATUS_FAILED;
  }

  jni->CallStaticVoidMethod(cls, mid);
  if (eventsCount != eventsExpected) {
    printf("Wrong number of MethodEntry events: %" PRIuPTR ", expected: %" PRIuPTR "\n", eventsCount, eventsExpected);
    result = STATUS_FAILED;
  }
  return result;
}

JNIEXPORT void JNICALL
Java_mentry01_chain(JNIEnv *jni, jclass cls) {
  jvmtiError err;

  if (jvmti == NULL) {
    printf("JVMTI client was not properly loaded!\n");
    result = STATUS_FAILED;
    return;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_METHOD_ENTRY, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_METHOD_ENTRY event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}

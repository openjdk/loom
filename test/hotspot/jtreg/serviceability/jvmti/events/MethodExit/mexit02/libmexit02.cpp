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
  const char *cls_sig;
  const char *name;
  const char *sig;
  jlocation loc;
} method_location_info;

static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jint result = PASSED;
static jboolean isVirtualExpected = JNI_FALSE;
static size_t eventsExpected = 0;
static size_t eventsCount = 0;
static method_location_info exits[] = {
    { "Lmexit02a;", "chain", "()V", -1 },
    { "Lmexit02a;", "dummy", "()V", 3 }
};

void JNICALL MethodExit(jvmtiEnv *jvmti, JNIEnv *jni,
                        jthread thread, jmethodID method,
                        jboolean was_poped_by_exc, jvalue return_value) {
  jvmtiError err;
  char *cls_sig, *name, *sig, *generic;
  jclass cls;
  jmethodID mid;
  jlocation loc;
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
  if (cls_sig != NULL &&
      strcmp(cls_sig, "Lmexit02a;") == 0) {
    printf(">>> retrieving method exit info ...\n");

    err = jvmti->GetMethodName(method,
                                   &name, &sig, &generic);
    if (err != JVMTI_ERROR_NONE) {
      printf("(GetMethodName) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      result = STATUS_FAILED;
      return;
    }
    err = jvmti->GetFrameLocation(thread, 0, &mid, &loc);
    if (err != JVMTI_ERROR_NONE) {
      printf("(GetFrameLocation) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      result = STATUS_FAILED;
      return;
    }
    printf(">>>      class: \"%s\"\n", cls_sig);
    printf(">>>     method: \"%s%s\"\n", name, sig);
    printf(">>>   location: %s\n", jlong_to_string(loc, buffer));
    printf(">>> ... done\n");

    jboolean isVirtual = jni->IsVirtualThread(thread);
    if (isVirtualExpected != isVirtual) {
      printf("The thread IsVirtualThread %d differs from expected %d.\n", isVirtual, isVirtualExpected);
      result = STATUS_FAILED;
    }

    if (eventsCount < sizeof(exits)/sizeof(method_location_info)) {
      if (cls_sig == NULL ||
          strcmp(cls_sig, exits[eventsCount].cls_sig) != 0) {
        printf("(exit#%" PRIuPTR ") wrong class: \"%s\"",
               eventsCount, cls_sig);
        printf(", expected: \"%s\"\n", exits[eventsCount].cls_sig);
        result = STATUS_FAILED;
      }
      if (name == NULL ||
          strcmp(name, exits[eventsCount].name) != 0) {
        printf("(exit#%" PRIuPTR ") wrong method name: \"%s\"",
               eventsCount, name);
        printf(", expected: \"%s\"\n", exits[eventsCount].name);
        result = STATUS_FAILED;
      }
      if (sig == NULL ||
          strcmp(sig, exits[eventsCount].sig) != 0) {
        printf("(exit#%" PRIuPTR ") wrong method sig: \"%s\"",
               eventsCount, sig);
        printf(", expected: \"%s\"\n", exits[eventsCount].sig);
        result = STATUS_FAILED;
      }
      if (loc != exits[eventsCount].loc) {
        printf("(exit#%" PRIuPTR ") wrong location: %s",
               eventsCount, jlong_to_string(loc, buffer));
        printf(", expected: %s\n",
               jlong_to_string(exits[eventsCount].loc, buffer));
        result = STATUS_FAILED;
      }
    } else {
      printf("Unexpected method exit catched:");
      printf("     class: \"%s\"\n", cls_sig);
      printf("    method: \"%s%s\"\n", name, sig);
      printf("  location: %s\n", jlong_to_string(loc, buffer));
      result = STATUS_FAILED;
    }
    eventsCount++;
  }
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_mexit02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_mexit02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_mexit02(JavaVM *jvm, char *options, void *reserved) {
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
  caps.can_generate_method_exit_events = 1;
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

  if (caps.can_generate_method_exit_events) {
    callbacks.MethodExit = &MethodExit;
    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      return JNI_ERR;
    }
  } else {
    printf("Warning: MethodExit event is not implemented\n");
  }

  return JNI_OK;
}

JNIEXPORT jint JNICALL
Java_mexit02_check(JNIEnv *jni, jclass cls) {
  jvmtiError err;
  jclass clz;
  jmethodID mid;

  if (jvmti == NULL) {
    printf("JVMTI client was not properly loaded!\n");
    return STATUS_FAILED;
  }

  jthread thread;
  err = jvmti->GetCurrentThread(&thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
  }
  isVirtualExpected = jni->IsVirtualThread(thread);

  clz = jni->FindClass("mexit02a");
  if (clz == NULL) {
    printf("Failed to find class \"mexit02a\"!\n");
    return STATUS_FAILED;
  }

  mid = jni->GetStaticMethodID(clz, "dummy", "()V");
  if (mid == NULL) {
    printf("Failed to get method \"dummy\"!\n");
    return STATUS_FAILED;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_METHOD_EXIT, NULL);
  if (err == JVMTI_ERROR_NONE) {
    eventsExpected = sizeof(exits)/sizeof(method_location_info);
    eventsCount = 0;
  } else {
    printf("Failed to enable JVMTI_EVENT_METHOD_EXIT event: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
  }

  jni->CallStaticVoidMethod(clz, mid);

  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_METHOD_EXIT, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_METHOD_EXIT event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  if (eventsCount != eventsExpected) {
    printf("Wrong number of method exit events: %" PRIuPTR ", expected: %" PRIuPTR "\n",
           eventsCount, eventsExpected);
    result = STATUS_FAILED;
  }
  return result;
}

JNIEXPORT void JNICALL
Java_mexit02a_chain(JNIEnv *jni, jclass cls) {
  printf(">>> about to exit method chain ...\n");
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}



}

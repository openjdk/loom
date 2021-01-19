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
  jfieldID fid;
  char *m_cls;
  char *m_name;
  char *m_sig;
  jlocation loc;
  char *f_cls;
  char *f_name;
  char *f_sig;
  jboolean is_static;
} writable_watch_info;

typedef struct {
  jfieldID fid;
  const char *m_cls;
  const char *m_name;
  const char *m_sig;
  jlocation loc;
  const char *f_cls;
  const char *f_name;
  const char *f_sig;
  jboolean is_static;
} watch_info;

static jvmtiEnv *jvmti;
static jvmtiEventCallbacks callbacks;
static jvmtiCapabilities caps;
static jint result = PASSED;
static volatile jboolean isVirtualExpected = JNI_FALSE;
static volatile int eventsExpected = 0;
static volatile int eventsCount = 0;
static watch_info watches[] = {
    { NULL, "Lfieldacc01a;", "run", "()I", 2,
        "Lfieldacc01a;", "staticBoolean", "Z", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 6,
        "Lfieldacc01a;", "instanceBoolean", "Z", JNI_FALSE },
    { NULL, "Lfieldacc01a;",   "run", "()I", 15,
        "Lfieldacc01a;", "staticByte", "B", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 19,
        "Lfieldacc01a;", "instanceByte", "B", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 28,
        "Lfieldacc01a;", "staticShort", "S", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 32,
        "Lfieldacc01a;", "instanceShort", "S", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 41,
        "Lfieldacc01a;", "staticInt", "I", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 45,
        "Lfieldacc01a;", "instanceInt", "I", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 54,
        "Lfieldacc01a;", "staticLong", "J", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 58,
        "Lfieldacc01a;", "instanceLong", "J", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 68,
        "Lfieldacc01a;", "staticFloat", "F", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 72,
        "Lfieldacc01a;", "instanceFloat", "F", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 82,
        "Lfieldacc01a;", "staticDouble", "D", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 86,
        "Lfieldacc01a;", "instanceDouble", "D", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 96,
        "Lfieldacc01a;", "staticChar", "C", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 100,
        "Lfieldacc01a;", "instanceChar", "C", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 109,
        "Lfieldacc01a;", "staticObject", "Ljava/lang/Object;", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 113,
        "Lfieldacc01a;", "instanceObject", "Ljava/lang/Object;", JNI_FALSE },
    { NULL, "Lfieldacc01a;", "run", "()I", 122,
        "Lfieldacc01a;", "staticArrInt", "[I", JNI_TRUE },
    { NULL, "Lfieldacc01a;", "run", "()I", 128,
        "Lfieldacc01a;", "instanceArrInt", "[I", JNI_FALSE }
};


void JNICALL FieldAccess(jvmtiEnv *jvmti, JNIEnv *jni,
                         jthread thr, jmethodID method,
                         jlocation location, jclass field_klass, jobject obj, jfieldID field) {
  jvmtiError err;
  jclass cls;
  writable_watch_info watch;
  char *generic;
  size_t i;

  eventsCount++;
  printf(">>> retrieving access watch info ...\n");

  watch.fid = field;
  watch.loc = location;
  watch.is_static = (obj == NULL) ? JNI_TRUE : JNI_FALSE;
  err = jvmti->GetMethodDeclaringClass(method, &cls);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetMethodDeclaringClass) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetClassSignature(cls, &watch.m_cls, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetMethodName(method, &watch.m_name, &watch.m_sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetMethodName) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetClassSignature(field_klass, &watch.f_cls, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetFieldName(field_klass, field, &watch.f_name, &watch.f_sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetFieldName) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }
  printf(">>>      class: \"%s\"\n", watch.m_cls);
  printf(">>>     method: \"%s%s\"\n", watch.m_name, watch.m_sig);
  printf(">>>   location: 0x%x%08x\n",
         (jint)(watch.loc >> 32), (jint)watch.loc);
  printf(">>>  field cls: \"%s\"\n", watch.f_cls);
  printf(">>>      field: \"%s:%s\"\n", watch.f_name, watch.f_sig);
  printf(">>>     object: 0x%p\n", obj);
  printf(">>> ... done\n");

  for (i = 0; i < sizeof(watches)/sizeof(watch_info); i++) {
    if (watch.fid == watches[i].fid) {
      if (watch.m_cls == NULL ||
          strcmp(watch.m_cls, watches[i].m_cls) != 0) {
        printf("(watch#%" PRIuPTR ") wrong class: \"%s\", expected: \"%s\"\n",
               i, watch.m_cls, watches[i].m_cls);
        result = STATUS_FAILED;
      }
      if (watch.m_name == NULL ||
          strcmp(watch.m_name, watches[i].m_name) != 0) {
        printf("(watch#%" PRIuPTR ") wrong method name: \"%s\"",
               i, watch.m_name);
        printf(", expected: \"%s\"\n", watches[i].m_name);
        result = STATUS_FAILED;
      }
      if (watch.m_sig == NULL ||
          strcmp(watch.m_sig, watches[i].m_sig) != 0) {
        printf("(watch#%" PRIuPTR ") wrong method sig: \"%s\"",
               i, watch.m_sig);
        printf(", expected: \"%s\"\n", watches[i].m_sig);
        result = STATUS_FAILED;
      }
      if (watch.loc != watches[i].loc) {
        printf("(watch#%" PRIuPTR ") wrong location: 0x%x%08x",
               i, (jint)(watch.loc >> 32), (jint)watch.loc);
        printf(", expected: 0x%x%08x\n",
               (jint)(watches[i].loc >> 32), (jint)watches[i].loc);
        result = STATUS_FAILED;
      }
      if (watch.f_name == NULL ||
          strcmp(watch.f_name, watches[i].f_name) != 0) {
        printf("(watch#%" PRIuPTR ") wrong field name: \"%s\"",
               i, watch.f_name);
        printf(", expected: \"%s\"\n", watches[i].f_name);
        result = STATUS_FAILED;
      }
      if (watch.f_sig == NULL ||
          strcmp(watch.f_sig, watches[i].f_sig) != 0) {
        printf("(watch#%" PRIuPTR ") wrong field sig: \"%s\"",
               i, watch.f_sig);
        printf(", expected: \"%s\"\n", watches[i].f_sig);
        result = STATUS_FAILED;
      }
      if (watch.is_static != watches[i].is_static) {
        printf("(watch#%" PRIuPTR ") wrong field type: %s", i,
               (watch.is_static == JNI_TRUE) ? "static" : "instance");
        printf(", expected: %s\n",
               (watches[i].is_static == JNI_TRUE) ? "static" : "instance");
        result = STATUS_FAILED;
      }
      jboolean isVirtual = jni->IsVirtualThread(thr);
      if (isVirtualExpected != isVirtual) {
        printf("The thread IsVirtualThread %d differs from expected %d.\n", isVirtual, isVirtualExpected);
        result = STATUS_FAILED;
      }
      return;
    }
  }
  printf("Unexpected field access catched: 0x%p\n", watch.fid);
  result = STATUS_FAILED;
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_fieldacc01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_fieldacc01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_fieldacc01(JavaVM *jvm, char *options, void *reserved) {
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
  caps.can_generate_field_access_events = 1;
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

  if (caps.can_generate_field_access_events) {
    callbacks.FieldAccess = &FieldAccess;
    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      return JNI_ERR;
    }

    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                          JVMTI_EVENT_FIELD_ACCESS, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("Failed to enable JVMTI_EVENT_FIELD_ACCESS: %s (%d)\n",
             TranslateError(err), err);
      return JNI_ERR;
    }
  } else {
    printf("Warning: FieldAccess watch is not implemented\n");
  }

  return JNI_OK;
}


JNIEXPORT void JNICALL
Java_fieldacc01_getReady(JNIEnv *jni, jclass klass) {
  jvmtiError err;
  jclass cls;
  size_t i;
  jthread thread;

  printf(">>> setting field access watches ...\n");

  cls = jni->FindClass("fieldacc01a");
  if (cls == NULL) {
    printf("Cannot find fieldacc01a class!\n");
    result = STATUS_FAILED;
    return;
  }
  err = jvmti->GetCurrentThread(&thread);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to get current thread: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  eventsCount = 0;
  eventsExpected = 0;
  isVirtualExpected = jni->IsVirtualThread(thread);

  for (i = 0; i < sizeof(watches)/sizeof(watch_info); i++) {
    if (watches[i].is_static == JNI_TRUE) {
      watches[i].fid = jni->GetStaticFieldID(
          cls, watches[i].f_name, watches[i].f_sig);
    } else {
      watches[i].fid = jni->GetFieldID(
          cls, watches[i].f_name, watches[i].f_sig);
    }
    if (watches[i].fid == NULL) {
      printf("Cannot find field \"%s\"!\n", watches[i].f_name);
      result = STATUS_FAILED;
      return;
    }
    err = jvmti->SetFieldAccessWatch(cls, watches[i].fid);
    if (err == JVMTI_ERROR_NONE) {
      eventsExpected++;
    } else {
      printf("(SetFieldAccessWatch#%" PRIuPTR ") unexpected error: %s (%d)\n",
             i, TranslateError(err), err);
      result = STATUS_FAILED;
    }
  }

  printf(">>> ... done\n");
}

JNIEXPORT jint JNICALL
Java_fieldacc01_check(JNIEnv *jni, jclass klass) {
  jvmtiError err;
  size_t i;
  jclass cls;

  if (eventsCount != eventsExpected) {
    printf("Wrong number of field access events: %d, expected: %d\n",
           eventsCount, eventsExpected);
    result = STATUS_FAILED;
  }

  cls = jni->FindClass("fieldacc01a");
  if (cls == NULL) {
    printf("Cannot find fieldacc01a class!\n");
    result = STATUS_FAILED;
    return result;
  }

  for (i = 0; i < sizeof(watches)/sizeof(watch_info); i++) {
    err = jvmti->ClearFieldAccessWatch(cls, watches[i].fid);
    if (err == JVMTI_ERROR_NONE) {
      eventsExpected++;
    } else {
      printf("(ClearFieldAccessWatch#%" PRIuPTR ") unexpected error: %s (%d)\n",
             i, TranslateError(err), err);
      result = STATUS_FAILED;
    }
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

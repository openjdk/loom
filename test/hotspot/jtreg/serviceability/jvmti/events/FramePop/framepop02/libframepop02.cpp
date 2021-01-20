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
#include <stdlib.h>
#include <string.h>
#include "jvmti.h"
#include "jvmti_common.h"


extern "C" {


#define PASSED 0
#define STATUS_FAILED 2
#define MAX_THREADS 32

typedef struct item *item_t;
struct item {
  item_t next;
  jmethodID method;
  int depth;
} item;

typedef struct thr {
  jthread thread;
  item_t tos;
} thr;

static jvmtiEnv *jvmti = NULL;
static jvmtiEventCallbacks callbacks;
static jrawMonitorID event_lock;
static jint result = PASSED;
static jboolean printdump = JNI_TRUE;
static jboolean watch_events = JNI_FALSE;

static int pop_count = 0;
static int push_count = 0;
static int thr_count = 0;
static int max_depth = 0;
static thr threads[MAX_THREADS];

static volatile int callbacksEnabled = NSK_FALSE;
static jrawMonitorID agent_lock;

static
int isTestThread(jvmtiEnv *jvmti, jthread thr) {
  jvmtiError err;
  jvmtiThreadInfo inf;
  const char* TEST_THREAD_NAME_BASE = "Test Thread";

  err = jvmti->GetThreadInfo(thr, &inf);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetThreadInfo) unexpected error: %s (%d)\n", TranslateError(err), err);
    result = STATUS_FAILED;
    return 0;
  }
  return strncmp(inf.name, TEST_THREAD_NAME_BASE, strlen(TEST_THREAD_NAME_BASE)) == 0;
}

static
void printInfo(jvmtiEnv *jvmti, jthread thr, jmethodID method, int depth) {
  jvmtiError err;
  jvmtiThreadInfo inf;
  char *clsig, *name, *sig, *generic;
  jclass cls;

  err = jvmti->GetThreadInfo(thr, &inf);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetThreadInfo) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  err = jvmti->GetMethodDeclaringClass(method, &cls);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetMethodDeclaringClass) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  err = jvmti->GetClassSignature(cls, &clsig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetClassSignature) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  err = jvmti->GetMethodName(method, &name, &sig, &generic);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetMethodName) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
    return;
  }

  printf("  %s: %s.%s%s, depth = %d\n", inf.name, clsig, name, sig, depth);

  jvmti->Deallocate((unsigned char *)sig);
  jvmti->Deallocate((unsigned char *)name);
  jvmti->Deallocate((unsigned char *)clsig);
  jvmti->Deallocate((unsigned char *)inf.name);
}

static
void pop(jvmtiEnv *jvmti, JNIEnv *jni, jthread thr, jmethodID method, int depth) {
  item_t old;
  int i, count = 0;

  for (i = 0; i < thr_count; i++) {
    if (jni->IsSameObject(threads[i].thread, thr)) {
      break;
    }
  }

  if (i == thr_count) {
    watch_events = JNI_FALSE;
    printf("Unknown thread:\n");
    printInfo(jvmti, thr, method, depth);
    result = STATUS_FAILED;
    return;
  }

  if (threads[i].tos == NULL) {
    watch_events = JNI_FALSE;
    printf("Stack underflow:\n");
    printInfo(jvmti, thr, method, depth);
    result = STATUS_FAILED;
    return;
  }

  do {
    pop_count++;
    old = threads[i].tos;
    threads[i].tos = threads[i].tos->next;
    if (old->method == method && old->depth == depth) {
      free(old);
      return;
    }
    free(old);
  } while (threads[i].tos != NULL);

  watch_events = JNI_FALSE;
  printf("Frame pop does not match any entry:\n");
  printInfo(jvmti, thr, method, depth);
  result = STATUS_FAILED;
}

static
void push(JNIEnv *jni, jthread thr, jmethodID method, int depth) {
  item_t new_item;
  int i;

  for (i = 0; i < thr_count; i++) {
    if (jni->IsSameObject(threads[i].thread, thr)) {
      break;
    }
  }

  if (i == thr_count) {
    thr_count++;
    if (thr_count == MAX_THREADS) {
      watch_events = JNI_FALSE;
      printf("Out of threads\n");
      result = STATUS_FAILED;
      return;
    }
    threads[i].thread = jni->NewGlobalRef(thr);
    threads[i].tos = NULL;
  }

  new_item = (item_t)malloc(sizeof(item));
  if (new_item == NULL) {
    watch_events = JNI_FALSE;
    printf("Out of memory\n");
    result = STATUS_FAILED;
    return;
  }

  new_item->next = threads[i].tos;
  new_item->method = method;
  new_item->depth = depth;
  threads[i].tos = new_item;
  push_count++;
  max_depth = (max_depth < depth) ? depth : max_depth;
}

void JNICALL MethodEntry(jvmtiEnv *jvmti, JNIEnv *jni,
                         jthread thr, jmethodID method) {
  jvmtiError err;
  jboolean isNative;
  jint frameCount;

  if (watch_events == JNI_FALSE) return;

  jvmti->RawMonitorEnter(agent_lock);

  if (!callbacksEnabled) {
    jvmti->RawMonitorExit(agent_lock);
    return;
  }

  err = jvmti->GetFrameCount(thr, &frameCount);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetFrameCount#entry) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    printInfo(jvmti, thr, method, frameCount);
    result = STATUS_FAILED;
    jvmti->RawMonitorExit(agent_lock);
    return;
  }

  err = jvmti->IsMethodNative(method, &isNative);
  if (err != JVMTI_ERROR_NONE) {
    printf("(IsMethodNative) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    printInfo(jvmti, thr, method, frameCount);
    result = STATUS_FAILED;
  }

  if (isTestThread(jvmti, thr)) {
    if (printdump == JNI_TRUE) {
      printf(">>> %sMethod entry\n>>>",
             (isNative == JNI_TRUE) ? "Native " : "");
      printInfo(jvmti, thr, method, frameCount);
    }
    if (isNative == JNI_FALSE) {
      err = jvmti->RawMonitorEnter(event_lock);
      if (err != JVMTI_ERROR_NONE) {
        printf("(RawMonitorEnter) unexpected error: %s (%d)\n",
               TranslateError(err), err);
        printInfo(jvmti, thr, method, frameCount);
        result = STATUS_FAILED;
      }
      push((JNIEnv *)jni, thr, method, frameCount);
      err = jvmti->RawMonitorExit(event_lock);
      if (err != JVMTI_ERROR_NONE) {
        printf("(RawMonitorExit) unexpected error: %s (%d)\n",
               TranslateError(err), err);
        printInfo(jvmti, thr, method, frameCount);
        result = STATUS_FAILED;
      }
      err = jvmti->NotifyFramePop(thr, 0);
      if (err != JVMTI_ERROR_NONE) {
        printf("(NotifyFramePop) unexpected error: %s (%d)\n",
               TranslateError(err), err);
        printInfo(jvmti, thr, method, frameCount);
        result = STATUS_FAILED;
      }
    }
  }

  jvmti->RawMonitorExit(agent_lock);
}

void JNICALL VMStart(jvmtiEnv *jvmti, JNIEnv* jni) {
  jvmti->RawMonitorEnter(agent_lock);

  callbacksEnabled = NSK_TRUE;

  jvmti->RawMonitorExit(agent_lock);
}


void JNICALL VMDeath(jvmtiEnv *jvmti, JNIEnv* jni) {
  jvmti->RawMonitorEnter(agent_lock);

  callbacksEnabled = NSK_FALSE;

  jvmti->RawMonitorExit(agent_lock);
}

void JNICALL FramePop(jvmtiEnv *jvmti, JNIEnv *jni,
                      jthread thr, jmethodID method, jboolean wasPopedByException) {
  jvmtiError err;
  jint frameCount;

  jvmti->RawMonitorEnter(agent_lock);

  if (!callbacksEnabled) {
    jvmti->RawMonitorExit(agent_lock);
    return;
  }
  err = jvmti->GetFrameCount(thr, &frameCount);
  if (err != JVMTI_ERROR_NONE) {
    printf("(GetFrameCount#entry) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    printInfo(jvmti, thr, method, frameCount);
    result = STATUS_FAILED;
    jvmti->RawMonitorExit(agent_lock);
    return;
  }

  if (isTestThread(jvmti, thr)) {
    if (printdump == JNI_TRUE) {
      printf(">>> Frame Pop\n>>>");
      printInfo(jvmti, thr, method, frameCount);
    }
    err = jvmti->RawMonitorEnter(event_lock);
    if (err != JVMTI_ERROR_NONE) {
      printf("(RawMonitorEnter) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      printInfo(jvmti, thr, method, frameCount);
      result = STATUS_FAILED;
    }
    pop(jvmti, (JNIEnv *)jni, thr, method, frameCount);
    err = jvmti->RawMonitorExit(event_lock);
    if (err != JVMTI_ERROR_NONE) {
      printf("(RawMonitorExit) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      printInfo(jvmti, thr, method, frameCount);
      result = STATUS_FAILED;
    }
  }

  jvmti->RawMonitorExit(agent_lock);
}

#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_framepop02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_framepop02(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_framepop02(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
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

  err = jvmti->CreateRawMonitor("_event_lock", &event_lock);
  if (err != JVMTI_ERROR_NONE) {
    printf("(CreateRawMonitor) unexpected error: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(jvmtiCapabilities));
  caps.can_generate_frame_pop_events = 1;
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

  if (caps.can_generate_frame_pop_events &&
      caps.can_generate_method_entry_events) {
    callbacks.MethodEntry = &MethodEntry;
    callbacks.FramePop = &FramePop;
    callbacks.VMStart = &VMStart;
    callbacks.VMDeath = &VMDeath;

    err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      printf("(SetEventCallbacks) unexpected error: %s (%d)\n",
             TranslateError(err), err);
      return JNI_ERR;
    }
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_START, NULL);
    if (err != JVMTI_ERROR_NONE) {
      return JNI_ERR;
    }
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
    if (err != JVMTI_ERROR_NONE) {
      return JNI_ERR;
    }

    if (jvmti->CreateRawMonitor("agent_lock", &agent_lock) != JVMTI_ERROR_NONE) {
      return JNI_ERR;
    }

  } else {
    printf("Warning: FramePop or MethodEntry event is not implemented\n");
  }

  return JNI_OK;
}

JNIEXPORT void JNICALL Java_framepop02_getReady(JNIEnv *jni, jclass cls) {
  jvmtiError err;

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_METHOD_ENTRY, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to enable JVMTI_EVENT_METHOD_ENTRY event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_FRAME_POP, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to enable JVMTI_EVENT_FRAME_POP event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  watch_events = JNI_TRUE;
}

JNIEXPORT jint JNICALL Java_framepop02_check(JNIEnv *jni, jclass cls) {
  jvmtiError err;

  watch_events = JNI_FALSE;
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_FRAME_POP, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_FRAME_POP event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE,
                                        JVMTI_EVENT_METHOD_ENTRY, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Failed to disable JVMTI_EVENT_METHOD_ENTRY event: %s (%d)\n",
           TranslateError(err), err);
    result = STATUS_FAILED;
  }

  if (printdump == JNI_TRUE) {
    printf("%d threads, %d method entrys, %d frame pops, max depth = %d\n",
           thr_count, push_count, pop_count, max_depth);
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

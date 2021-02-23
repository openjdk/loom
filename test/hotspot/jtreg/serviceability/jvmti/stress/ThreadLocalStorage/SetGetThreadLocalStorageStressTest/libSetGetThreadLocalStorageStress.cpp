/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

#include <string.h>
#include "jvmti.h"
#include <jvmti_common.h>
#include <jvmti_thread.h>

extern "C" {

/* scaffold objects */
static jlong timeout = 0;

/* constant names */
#define THREAD_NAME     "TestedThread"

/* constants */
#define STORAGE_DATA_SIZE       1024

const char* MAIN_THREAD_NAME = "main";

/* storage structure */
typedef struct _StorageStructure {
  void *self_pointer;
  char data[STORAGE_DATA_SIZE];
} StorageStructure;

StorageStructure* init(jvmtiEnv * jvmti, JNIEnv * jni, const char name[]) {
  unsigned char *tmp;
  check_jvmti_status(jni, jvmti->Allocate(sizeof(StorageStructure), &tmp), "Allocation failed.");

  StorageStructure* storage = (StorageStructure *)tmp;

// Fill data
  storage->self_pointer = storage;
  strncpy(storage->data, name, 100);

  return (StorageStructure*) tmp;
}

void check(jvmtiEnv * jvmti, JNIEnv * jni, jthread thread, StorageStructure* storage, const char name[]) {
  if (storage == NULL) {
    jvmtiThreadInfo thread_info;
    check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo");
//    if (strncmp(THREAD_NAME, thread_info.name, strlen(THREAD_NAME)) == 0) {
//      printf("Error in thread\n");
//      print_thread_info(jni, jvmti, thread);
//      jni->FatalError("Unexpected NULL for initialized storage");
//    }
    return;
  }

  if (storage->self_pointer != storage || (strcmp(name, storage->data) != 0)) {
    printf("Unexpected value in storage storage=%p, the self_pointer=%p, date (current thread name): %s\n",
           storage, storage->self_pointer, storage->data);
    print_thread_info(jni, jvmti, thread);
    jni->FatalError("Incorrect value in storage.");
  }
  check_jvmti_status(jni, jvmti->Deallocate((unsigned char *)storage), "Deallocation failed.");
}


jrawMonitorID monitor;
int main_thread_still_running = true;

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv * jvmti, JNIEnv * jni, void * arg) {
  printf("Wait for thread to start\n");
  if (!nsk_jvmti_waitForSync(timeout))
    return;
  if (!nsk_jvmti_resumeSync())
    return;
  printf("Started.....\n");

  while(true) {
    jthread *threads = NULL;
    jint count = 0;
    RawMonitorEnter(jni, jvmti, monitor);
    if (!main_thread_still_running) {
      RawMonitorExit(jni, jvmti, monitor);
      return;
    }
    check_jvmti_status(jni, jvmti->GetAllThreads(&count, &threads), "Error in GetAllThreads");
    for (int i = 0; i < count; i++) {
      jthread testedThread = NULL;
      jvmtiError err;

      err = jvmti->GetVirtualThread(threads[i], &testedThread);
      if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
        continue;
      }
      check_jvmti_status(jni, err,  "Error in GetVirtualThread\n");

      if (testedThread == NULL) {
        testedThread = threads[i];
      }

      StorageStructure *obtainedStorage;
      //printf("GetThreadLocalStorage() for tested thread\n");
      err = jvmti->GetThreadLocalStorage(testedThread, (void **) &obtainedStorage);

      if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
        continue;
      }

      check_jvmti_status(jni, err, "Error in GetThreadLocalStorage");

      jvmtiThreadInfo thread_info;
      check_jvmti_status(jni, jvmti->GetThreadInfo(testedThread, &thread_info), "Error in GetThreadInfo");

      check(jvmti, jni, testedThread, obtainedStorage, thread_info.name);

      // Set for next iteration
      StorageStructure *initialStorage = init(jvmti, jni, thread_info.name);

      err = jvmti->SetThreadLocalStorage(testedThread, (void *) initialStorage);
      if (err != JVMTI_ERROR_THREAD_NOT_ALIVE) {
        check_jvmti_status(jni, err, "Error in SetThreadLocalStorage");
      }
    }
    RawMonitorExit(jni, jvmti, monitor);
    check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) threads), "");
  }

}

/** callback functions **/
void JNICALL VMDeath(jvmtiEnv *jvmti, JNIEnv *jni) {
  RawMonitorEnter(jni, jvmti, monitor);
  printf("Exiting....");
  main_thread_still_running = false;
  RawMonitorExit(jni, jvmti, monitor);
}

void JNICALL ThreadStart(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (main_thread_still_running) {
    jvmtiThreadInfo thread_info;
    check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo");
    StorageStructure *initialStorage = init(jvmti, jni, thread_info.name);
  //  printf("Setting initial thread storage: %p for ",(void *) initialStorage); print_thread_info(jni, jvmti, thread);
    check_jvmti_status(jni, jvmti->SetThreadLocalStorage(thread, (void *) initialStorage), "Error in SetThreadLocalStorage");
  }
  RawMonitorExit(jni, jvmti, monitor);
}

void JNICALL ThreadEnd(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (main_thread_still_running) {
    StorageStructure *obtainedStorage;
    jvmtiThreadInfo thread_info;
    check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo");
  //  printf("Final testing of thread storage: %p for ",(void *) thread); print_thread_info(jni, jvmti, thread);
    check_jvmti_status(jni,  jvmti->GetThreadLocalStorage(thread, (void **) &obtainedStorage), "Error in GetThreadLocalStorage");
    check_jvmti_status(jni,  jvmti->SetThreadLocalStorage(thread, NULL), "Error in SetThreadLocalStorage");
    check(jvmti, jni, thread, obtainedStorage, thread_info.name);
  }
  RawMonitorExit(jni, jvmti, monitor);
}

/* ============================================================================= */

jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEnv * jvmti = NULL;

  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;
  jint res;

  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti == NULL) {
    printf("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  createRawMonitor(jvmti, "Monitor", &monitor);


  /* add capability to generate compiled method events */
  memset(&caps, 0, sizeof(jvmtiCapabilities));
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

  /* set event callback */
  printf("setting event callbacks ...\n");
  (void) memset(&callbacks, 0, sizeof(callbacks));
  callbacks.VMDeath = &VMDeath;
  callbacks.ThreadStart = &ThreadStart;
  callbacks.ThreadEnd = &ThreadEnd;
  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, NULL);



  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  /* register agent proc and arg */
  if (nsk_jvmti_setAgentProc(agentProc, NULL) != NSK_TRUE) {
    return JNI_ERR;
  }


  return JNI_OK;
  }

/* ============================================================================= */

}


JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return JNI_VERSION_9;
}

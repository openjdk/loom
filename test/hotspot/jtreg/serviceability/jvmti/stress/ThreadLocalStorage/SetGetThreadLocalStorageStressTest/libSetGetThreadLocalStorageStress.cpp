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
#include "jvmti_common.h"
#include "jvmti_thread.h"


/* constant names */
#define THREAD_NAME     "TestedThread"

/* constants */
#define STORAGE_DATA_SIZE       1024
#define THREAD_NAME_LENGTH      100

/* storage structure */
typedef struct _StorageStructure {
  void *self_pointer;
  char data[STORAGE_DATA_SIZE];
} StorageStructure;

StorageStructure* check_tls(jvmtiEnv * jvmti, JNIEnv * jni, jthread thread, const char* source) {
  jvmtiThreadInfo thread_info;
  check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo");

  StorageStructure *storage;

  jvmtiError err = jvmti->GetThreadLocalStorage(thread, (void **) &storage);
  if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
    return NULL;
  }
  check_jvmti_status(jni, err, "Error in GetThreadLocalStorage");
  printf("Check %s with %p in %s\n", thread_info.name, storage, source);
  fflush(0);

  if (storage == NULL) {
    // Might be not set
    return NULL;
  }

  if (storage->self_pointer != storage || (strcmp(thread_info.name, storage->data) != 0)) {
    printf("Unexpected value in storage storage=%p, the self_pointer=%p, data (owner thread name): %s\n",
           storage, storage->self_pointer, storage->data);
    print_thread_info(jni, jvmti, thread);
    jni->FatalError("Incorrect value in storage.");
  }
  return storage;
}

void check_delete_tls(jvmtiEnv * jvmti, JNIEnv * jni, jthread thread, const char* source) {
  StorageStructure *storage = check_tls(jvmti, jni, thread, source);

  if (storage == NULL) {
    return;
  }

  check_jvmti_status(jni, jvmti->Deallocate((unsigned char *)storage), "Deallocation failed.");
  jvmtiError err = jvmti->SetThreadLocalStorage(thread, NULL);
  if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
    return;
  }
  check_jvmti_status(jni, err, "Error in SetThreadLocalStorage");
}


void check_reset_tls(jvmtiEnv * jvmti, JNIEnv * jni, jthread thread, const char* source) {
  check_delete_tls(jvmti, jni, thread, source);
  jvmtiThreadInfo thread_info;
  check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo");

  unsigned char *tmp;
  check_jvmti_status(jni, jvmti->Allocate(sizeof(StorageStructure), &tmp), "Allocation failed.");

  StorageStructure* storage = (StorageStructure *)tmp;

  printf("Init %s with %p in %s\n", thread_info.name, storage, source);
  fflush(0);

  // Fill data
  storage->self_pointer = storage;
  strncpy(storage->data, thread_info.name, THREAD_NAME_LENGTH);
  jvmtiError err = jvmti->SetThreadLocalStorage(thread, (void *) storage);
  if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
    return;
  }
  check_jvmti_status(jni, err, "Error in SetThreadLocalStorage");

  check_tls(jvmti, jni, thread, "check_reset_tls");
}

jrawMonitorID monitor;
int is_vm_running = false;

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv * jvmti, JNIEnv * jni, void * arg) {
  /* scaffold objects */
  static jlong timeout = 0;
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
    if (!is_vm_running) {
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
        continue;
      }

      check_reset_tls(jvmti, jni, testedThread, "agentThread");

    }
    RawMonitorExit(jni, jvmti, monitor);
    check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) threads), "Error Deallocating memory.");
  }

}

/** callback functions **/
void JNICALL VMInit(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  RawMonitorEnter(jni, jvmti, monitor);
  printf("Starting ...\n");
  is_vm_running = true;
  RawMonitorExit(jni, jvmti, monitor);
}

void JNICALL VMDeath(jvmtiEnv *jvmti, JNIEnv *jni) {
  RawMonitorEnter(jni, jvmti, monitor);
  printf("Exiting ...\n");
  is_vm_running = false;
  RawMonitorExit(jni, jvmti, monitor);
}

void JNICALL ThreadStart(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (is_vm_running) {
    check_reset_tls(jvmti, jni, thread, "ThreadStart");
  }
  RawMonitorExit(jni, jvmti, monitor);
}

void JNICALL ThreadEnd(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (is_vm_running) {
    check_reset_tls(jvmti, jni, thread, "ThreadEnd");
  }
  RawMonitorExit(jni, jvmti, monitor);
}
/*
void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  fflush(0);
  int p = ((long) method / 128 % 100);
  if (p < 1) {
    RawMonitorEnter(jni, jvmti, monitor);
    if (is_vm_running) {
      jvmtiThreadInfo thread_info;
      check_jvmti_status(jni, jvmti->GetThreadInfo(thread, &thread_info), "Error in GetThreadInfo11");
      if (strcmp("main", thread_info.name) == 0) {
        // Skip main() method entries
        RawMonitorExit(jni, jvmti, monitor);
        return;
      }
      check_reset_tls(jvmti, jni, thread, "MethodEntry");
    }
    RawMonitorExit(jni, jvmti, monitor);
  }
}
*/

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (is_vm_running) {
    check_reset_tls(jvmti, jni, vthread, "VirtualThreadScheduled");
  }
  RawMonitorExit(jni, jvmti, monitor);
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorEnter(jni, jvmti, monitor);
  if (is_vm_running) {
    check_reset_tls(jvmti, jni, vthread, "VirtualThreadTerminated");
  }
  RawMonitorExit(jni, jvmti, monitor);
}

/* ============================================================================= */

jint Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
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
  caps.can_generate_method_entry_events = 1;
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
  callbacks.VMInit = &VMInit;
  callbacks.VMDeath = &VMDeath;
  callbacks.ThreadStart = &ThreadStart;
  callbacks.ThreadEnd = &ThreadEnd;
 // callbacks.MethodEntry = &MethodEntry;
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;
  callbacks.VirtualThreadTerminated = &VirtualThreadTerminated;

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_DEATH, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_END, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_TERMINATED, NULL);

  //jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, NULL);

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


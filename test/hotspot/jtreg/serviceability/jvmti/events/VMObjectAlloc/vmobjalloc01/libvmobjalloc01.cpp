/*
 * Copyright (c) 2004, 2018, Oracle and/or its affiliates. All rights reserved.
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


extern "C" {

/* ========================================================================== */

/* scaffold objects */
static jlong timeout = 0;

/* test objects */
static int eventsCount = 0;

/* ========================================================================== */

/* check if any VMObjectAlloc events received */
static int checkVMObjectAllocEvents() {

  NSK_DISPLAY1("VMObjectAlloc events received: %d\n", eventsCount);

  if (eventsCount == 0) {
    NSK_DISPLAY0("# WARNING: no VMObjectAlloc events\n");
    NSK_DISPLAY0("#    (VM might not allocate such objects at all)\n");
  }

  return NSK_TRUE;
}

/* ========================================================================== */

JNIEXPORT void JNICALL
VMObjectAlloc(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jobject object,
              jclass object_klass, jlong size) {
  char *signature, *generic;
  jvmtiError err;

  eventsCount++;

  err = jvmti->GetClassSignature(object_klass, &signature, &generic);
  if (err != JVMTI_ERROR_NONE) {
    nsk_jvmti_setFailStatus();
    return;
  }

  NSK_DISPLAY2("VMObjectAlloc: \"%s\", size=%ld\n", signature, (long)size);

  if (signature != NULL)
    jvmti->Deallocate((unsigned char*)signature);

  if (generic != NULL)
    jvmti->Deallocate((unsigned char*)generic);

}

/* ========================================================================== */

/* agent algorithm */
static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {

  /* wait for debuggee start */
  if (!nsk_jvmti_waitForSync(timeout))
    return;

  /* testcase #1: check if any VMObjectAlloc events received*/
  NSK_DISPLAY0("Testcase #1: check if any VMObjectAlloc events received\n");
  if (!checkVMObjectAllocEvents())
    nsk_jvmti_setFailStatus();

  /* resume debugee after last sync */
  if (!nsk_jvmti_resumeSync())
    return;
}

/* ========================================================================== */

/* agent library initialization */
#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_vmobjalloc01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_vmobjalloc01(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_vmobjalloc01(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEnv* jvmti = NULL;
  jvmtiCapabilities caps;
  jvmtiEventCallbacks callbacks;
  jvmtiError err;
  jint res;

  timeout = 60000; // TODO Fix timeout
  NSK_DISPLAY1("Timeout: %d msc\n", (int)timeout);

  /* create JVMTI environment */
  res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_9);
  if (res != JNI_OK || jvmti == NULL) {
    LOG("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }


  memset(&caps, 0, sizeof(caps));
  caps.can_generate_vm_object_alloc_events = 1;
  if (jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.VMObjectAlloc= &VMObjectAlloc;
  if (jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks) != JVMTI_ERROR_NONE)) {
    return JNI_ERR;
  }

  /* enable VMObjectAlloc event */
  if (jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_OBJECT_ALLOC, NULL) != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  /* register agent proc and arg */
  nsk_jvmti_setAgentProc(agentProc, NULL);

  return JNI_OK;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}
}

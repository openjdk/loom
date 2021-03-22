/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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

#define MAX_FRAME_COUNT 80

const char CONTINUATION_CLASS_NAME[] = "java/lang/Continuation";
const char CONTINUATION_METHOD_NAME[] = "enter";

static void test_stack_trace(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = -1;
  jmethodID method = NULL;
  jvmtiError err;

  err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, frames, &count);
  check_jvmti_status(jni, err, "GetStackTrace returns error.");
  if (count <= 0) {
    printf("Stacktrace in virtual thread is incorrect.\n");
    print_thread_info(jvmti, jni, vthread);
    print_stack_trace_frames(jvmti, jni, count, frames);
    fatal(jni, "Incorrect frame count.");
  }
  method = frames[count -1].method;
  const char* class_name = get_method_class_name(jvmti, jni, method);
  const char* method_name = get_method_name(jvmti, jni, method);

  if (strcmp(CONTINUATION_CLASS_NAME, class_name) !=0 || strcmp(CONTINUATION_METHOD_NAME, method_name) != 0) {
    printf("Stacktrace in virtual thread is incorrect (doesn't start from enter(...):\n");
    print_stack_trace_frames(jvmti, jni, count, frames);
    fatal(jni, "incorrect stacktrace.");
  }

  jint frame_count = -1;
  check_jvmti_status(jni, jvmti->GetFrameCount(vthread, &frame_count), "GetFrameCount failed.");
  if (frame_count != count) {
    printf("Incorrect frame count %d while %d expected.\n", frame_count, count);
    print_stack_trace_frames(jvmti, jni, count, frames);
    fatal(jni, "Incorrect frame count.");
  }
}

jint get_thread_state(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread) {
  jint thread_state_ptr;
  check_jvmti_status(jni, jvmti->GetThreadState(thread, &thread_state_ptr), "Error in GetThreadState");
  return thread_state_ptr;
}

void check_link_consistency(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  jthread cthread = get_carrier_thread(jvmti, jni, vthread);
  jint vstate = get_thread_state(jvmti, jni, vthread);
  jint cstate = get_thread_state(jvmti, jni, cthread);

  if ( !(vstate & JVMTI_THREAD_STATE_SUSPENDED) || (cstate & JVMTI_THREAD_STATE_SUSPENDED)) {
    printf("Incorrect state of threads: \n");
    print_thread_info(jvmti, jni, vthread);
    print_thread_info(jvmti, jni, cthread);
    // TODO uncomment fatal(jni, "");
  }

  if (cthread != NULL) {
    jthread cthread_to_vthread = get_virtual_thread(jvmti, jni, cthread);
    if (!jni->IsSameObject(vthread, cthread_to_vthread)) {
      printf("GetVirtualThread(GetCarrierThread(vthread)) not equals to vthread.\n");
      printf("Result: ");
      print_thread_info(jvmti, jni, cthread_to_vthread);
      printf("Expected: ");
      print_thread_info(jvmti, jni, vthread);
      printf("Carrier: ");
      print_thread_info(jvmti, jni, cthread);
      // fatal(jni, "GetVirtualThread(GetCarrierThread(vthread)) not equals to vthread.");
    }
  }
}


static void check_vthread_consistency_suspended(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  check_link_consistency(jvmti, jni, vthread);
  test_stack_trace(jvmti, jni, vthread);
}


/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv * jvmti, JNIEnv * jni, void * arg) {

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
    jvmtiError err;

    err = jvmti->GetAllThreads(&count, &threads);
    if (err == JVMTI_ERROR_WRONG_PHASE) {
      return;
    }
    check_jvmti_status(jni, err,  "Error in GetAllThreads\n");
    for (int i = 0; i < count; i++) {
      jthread tested_thread = NULL;

      err = GetVirtualThread(jvmti, jni, threads[i], &tested_thread);
      if (err == JVMTI_ERROR_THREAD_NOT_ALIVE) {
        continue;
      }
      if (err == JVMTI_ERROR_WRONG_PHASE) {
        return;
      }
      check_jvmti_status(jni, err,  "Error in GetVirtualThread\n");
      if (tested_thread != NULL) {
        check_jvmti_status(jni, jvmti->SuspendThread(tested_thread), "Error in SuspendThread");
        check_vthread_consistency_suspended(jvmti, jni, tested_thread);
        check_jvmti_status(jni, jvmti->ResumeThread(tested_thread), "Error in ResumeThread");
      }

    }
    check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) threads), "Error Deallocating memory.");
  }
}


extern JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiCapabilities caps;
  jvmtiError err;
  jvmtiEnv* jvmti;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_suspend = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI AddCapabilities: %d\n", err);
  }

  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  if (nsk_jvmti_setAgentProc(agentProc, NULL) != NSK_TRUE) {
    return JNI_ERR;
  }

  printf("Agent_OnLoad finished\n");
  return 0;
}

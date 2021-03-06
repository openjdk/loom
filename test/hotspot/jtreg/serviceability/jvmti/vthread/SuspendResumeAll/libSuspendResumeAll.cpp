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

extern "C" {

/* ============================================================================= */

#define VTHREAD_CNT   30

static const char* CTHREAD_NAME_START = "ForkJoinPool";
static const int CTHREAD_NAME_START_LEN = (int)strlen("ForkJoinPool");

static jvmtiEnv *jvmti = NULL;
static jrawMonitorID agent_event_lock = NULL;
static volatile jthread agent_thread = NULL;
static jthread tested_vthreads[VTHREAD_CNT];
static int vthread_no = 0;
static jlong timeout = 5 * 60 * 1000; // 5 minutes


static void
test_get_stack_trace(JNIEnv *jni, jthread thread) {
  print_stack_trace(jvmti, jni, thread);
}

static void
test_get_thread_list_stack_traces(JNIEnv *jni, bool is_virt, jint thread_cnt, jthread* thread_list) {
  jvmtiStackInfo* stack_info_arr = NULL;

  printf("## Agent: test_get_thread_list_stack_traces started: is virtual: %d, count: %d\n\n",
         is_virt, thread_cnt);

  jvmtiError err = jvmti->GetThreadListStackTraces(thread_cnt, thread_list,
                                        MAX_FRAME_COUNT_PRINT_STACK_TRACE, &stack_info_arr);
  check_jvmti_status(jni, err, "test_get_thread_list_stack_traces: error in JVMTI GetThreadListStackTraces");

  for (int idx = 0; idx < thread_cnt; idx++) {
    jthread thread = stack_info_arr[idx].thread;

    print_stack_trace(jvmti, jni, thread);
  }
  printf("## Agent: test_get_thread_list_stack_traces finished: virtual: %d, count: %d\n\n",
         is_virt, thread_cnt);
}

static void
test_get_frame_location(JNIEnv* jni, jthread thread, char* tname) {
  const jint DEPTH = 1;
  jlocation loc = 0;
  jmethodID method = NULL;
  char* name = NULL;
  char* sign = NULL;
  jboolean is_virtual = jni->IsVirtualThread(thread);

  jvmtiError err = jvmti->GetFrameLocation(thread, DEPTH, &method, &loc);
  if (err != JVMTI_ERROR_NONE) {
    if (!is_virtual || err != JVMTI_ERROR_NO_MORE_FRAMES) { // TMP work around
      check_jvmti_status(jni, err, "test_get_frame_location: error in JVMTI GetFrameLocation");
    } else {
      printf("## Agent: test_get_frame_location: ignoring JVMTI_ERROR_NO_MORE_FRAMES for vt\n\n");
    }
    return;
  }
  err = jvmti->GetMethodName(method, &name, &sign, NULL);
  check_jvmti_status(jni, err, "test_get_frame_location: error in JVMTI GetMethodName");

  printf("Agent: GetFrameLocation: frame for current thread %s: method: %s%s, loc: %lld\n",
         tname, name, sign, (long long)loc);
}

static void
check_suspended_state(JNIEnv* jni, jthread thread, int thr_idx, char* tname, const char* func_name) {
  void *thread_p = (void*)thread;
  jint state = 0;

  jvmtiError err = jvmti->GetThreadState(thread, &state);
  check_jvmti_status(jni, err, "check_suspended_state: error in JVMTI GetThreadState");

  printf("## Agent: thread[%d] %p %s: state after suspend: %s (%d)\n",
         thr_idx, thread_p,  tname, TranslateState(state), (int)state); fflush(0);

  if ((state & (JVMTI_THREAD_STATE_SUSPENDED | JVMTI_THREAD_STATE_TERMINATED)) == 0) {
    printf("## Agent: FAILED: %s did not turn on SUSPENDED flag:\n"
           "#  state: %s (%d)\n", func_name, TranslateState(state), (int)state);
    nsk_jvmti_setFailStatus();
  }
}

static void
check_resumed_state(JNIEnv* jni, jthread thread, int thr_idx, char* tname, const char* func_name) {
  void *thread_p = (void*)thread;
  jint state = 0;

  jvmtiError err = jvmti->GetThreadState(thread, &state);
  check_jvmti_status(jni, err, "check_resumed_state: error in JVMTI GetThreadState");

  printf("## Agent: thread[%d] %p %s: state after resume: %s (%d)\n",
         thr_idx, thread_p, tname, TranslateState(state), (int)state); fflush(0);

  if (!((state & (JVMTI_THREAD_STATE_SUSPENDED | JVMTI_THREAD_STATE_TERMINATED)) == 0)) {
    printf("## Agent: FAILED: %s did not turn off SUSPENDED flag:\n"
           "#   state: %s (%d)\n", func_name, TranslateState(state), (int)state);
    nsk_jvmti_setFailStatus();
  }
}

static void
test_thread_suspend(JNIEnv* jni, jthread thread, int thr_idx, char* tname) {
  jvmtiError err = jvmti->SuspendThread(thread);
  check_jvmti_status(jni, err, "test_thread_suspend: error in JVMTI SuspendThread");

  check_suspended_state(jni, thread, thr_idx, tname, "SuspendThread");
}

static void
test_thread_resume(JNIEnv* jni, jthread thread, int thr_idx, char* tname) {
  jvmtiError err = jvmti->ResumeThread(thread);
  check_jvmti_status(jni, err, "test_thread_resume: error in JVMTI ResumeThread");

  check_resumed_state(jni, thread, thr_idx, tname, "ResumeThread");
}

static void
test_thread_suspend_list(JNIEnv* jni, const jthread* thread_list) {
  jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max

  printf("\n## Agent: test_thread_suspend_list started\n"); fflush(0);

  jvmtiError err = jvmti->SuspendThreadList(VTHREAD_CNT, thread_list, results);
  check_jvmti_status(jni, err, "test_thread_suspend_list: error in JVMTI SuspendThreadList");

  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    check_suspended_state(jni, thread, idx, tname,"SuspendThreadList");
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_thread_suspend_list finished\n"); fflush(0);
}

static void
test_thread_resume_list(JNIEnv* jni, const jthread* thread_list) {
  jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max

  printf("\n## Agent: test_thread_resume_list: started\n"); fflush(0);

  jvmtiError err = jvmti->ResumeThreadList(VTHREAD_CNT, thread_list, results);
  check_jvmti_status(jni, err, "test_thread_resume_list: error in JVMTI ResumeThreadList");

  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    check_resumed_state(jni, thread, idx, tname, "ResumeThreadList");
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_thread_resume_list: finished\n"); fflush(0);
}

static void
test_vthread_suspend_all(JNIEnv* jni, const jthread* thread_list, int suspend_mask) {

  printf("\n## Agent: test_vthread_suspend_all started\n"); fflush(0);

  const jint EXCLUDE_CNT = 2;
  jthread exclude_list[EXCLUDE_CNT] = { NULL, NULL };
  for (int idx = 0; idx < EXCLUDE_CNT; idx++) {
    exclude_list[idx] = thread_list[idx];
  }

  jvmtiError err = jvmti->SuspendAllVirtualThreads(EXCLUDE_CNT, exclude_list);
  check_jvmti_status(jni, err, "test_vthread_suspend_all: error in JVMTI SuspendAllVirtualThreads");

  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    if (idx < EXCLUDE_CNT && ((1 << idx) & suspend_mask) == 0) {
      // thread is in exclude list and initially resumed: expected to remain resumed
      check_resumed_state(jni, thread, idx, tname, "SuspendAllVirtualThreads");

      err = jvmti->SuspendThread(thread);
      check_jvmti_status(jni, err, "test_vthread_suspend_all: error in JVMTI SuspendThread");
    } else {
      // thread is not in exclude list or was initially suspended: expected to be suspended
      check_suspended_state(jni, thread, idx, tname, "SuspendAllVirtualThreads");
    } 
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_vthread_suspend_all finished\n"); fflush(0);
}

static void
test_vthread_resume_all(JNIEnv* jni, const jthread* thread_list, int suspend_mask) {

  printf("\n## Agent: test_vthread_resume_all started\n"); fflush(0);

  const jint EXCLUDE_CNT = 2;
  jthread exclude_list[EXCLUDE_CNT] = { NULL, NULL };
  for (int idx = 0; idx < EXCLUDE_CNT; idx++) {
    exclude_list[idx] = thread_list[idx];
  }

  jvmtiError err = jvmti->ResumeAllVirtualThreads(EXCLUDE_CNT, exclude_list);
  check_jvmti_status(jni, err, "test_vthread_resume_all: error in JVMTI ResumeAllVirtualThreads");

  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    if (idx < EXCLUDE_CNT && ((1 << idx) & suspend_mask) != 0) {
      // thread is in exclude list and suspended: expected to remain suspended
      check_suspended_state(jni, thread, idx, tname, "ResumeAllVirtualThreads");

      err = jvmti->ResumeThread(thread); // is expected to be resumed later 
      check_jvmti_status(jni, err, "test_vthread_resume_all: error in JVMTI ResumeThread");
    } else {
      // thread is not in exclude list or was initially resumed: expected to be resumed
      check_resumed_state(jni, thread, idx, tname, "ResumeAllVirtualThreads");
    }
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_vthread_resume_all: finished\n"); fflush(0);
}

static void
test_vthread_suspend_half(JNIEnv* jni, const jthread* thread_list) {
  jvmtiError err;

  printf("\n## Agent: test_vthread_suspend_half started\n"); fflush(0);
  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    if (idx % 2 == 1) {
      continue; // skip odd indeces
    }
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    err = jvmti->SuspendThread(thread);
    check_jvmti_status(jni, err, "test_vthread_suspend_half: error in JVMTI SuspendThread");

    check_suspended_state(jni, thread, idx, tname, "SuspendThread");
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_vthread_suspend_half finished\n"); fflush(0);
}

static void
test_vthread_resume_half(JNIEnv* jni, const jthread* thread_list) {
  jvmtiError err;

  printf("\n## Agent: test_vthread_resume_half started\n"); fflush(0);
  for (int idx = 0; idx < VTHREAD_CNT; idx++) {
    if (idx % 2 == 1) {
      continue; // skip odd indeces
    }
    jthread thread = thread_list[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    err = jvmti->ResumeThread(thread); 
    check_jvmti_status(jni, err, "test_vthread_resume_half: error in JVMTI ResumeThread");

    check_resumed_state(jni, thread, idx, tname, "ResumeThread");
    deallocate(jvmti, jni, (void*)tname);
  }
  printf("\n## Agent: test_vthread_resume_half: finished\n"); fflush(0);
}

static void
test_threads_suspend_resume(JNIEnv* jni, jint thread_cnt, jthread* tested_threads) {

  for (int idx = 0; idx < thread_cnt; idx++) {
    jthread thread = tested_threads[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    printf("\n");
    test_thread_suspend(jni, thread, idx, tname);
    test_thread_resume(jni, thread, idx, tname);

    deallocate(jvmti, jni, (void*)tname);
  }
}

static void
test_jvmti_functions_for_one_thread(JNIEnv* jni, jthread thread) {
  jint frame_count = 0;
  char* tname = get_thread_name(jvmti, jni, thread);

  // test JVMTI GetFrameCount
  jvmtiError err = jvmti->GetFrameCount(thread, &frame_count);
  check_jvmti_status(jni, err, "test_jvmti_functions_for_one_thread: error in JVMTI GetStackTrace");

  printf("## Agent: thread %s frame count: %d\n", tname, frame_count); fflush(0);

  // test JVMTI GetFrameLocation
  test_get_frame_location(jni, thread, tname);

  // test JVMTI GetStackTrace
  test_get_stack_trace(jni, thread);

  deallocate(jvmti, jni, (void*)tname);
}

static void
test_jvmti_functions_for_threads(JNIEnv* jni, bool is_virt, jint thread_cnt, jthread* thread_list) {
  jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max

  printf("\n## Agent: test_jvmti_functions_for_threads started: virtual: %d\n\n", is_virt);
  fflush(0);

  // iterate over all vthreads
  for (int idx = 0; idx < thread_cnt; idx++) {
    jthread thread = thread_list[idx];
    test_jvmti_functions_for_one_thread(jni, thread);
  }

  // test JVMTI GetTheadListStackTraces
  test_get_thread_list_stack_traces(jni, is_virt, 1, thread_list);          // test with one thread
  test_get_thread_list_stack_traces(jni, is_virt, thread_cnt, thread_list); // test with multiple threads

  printf("\n## Agent: test_jvmti_functions_for_threads finished: virtual: %d\n", is_virt);
  fflush(0);
}

static jint
get_cthreads(JNIEnv* jni, jthread** cthreads_p) {
  jthread* tested_cthreads = NULL;
  jint all_cnt = 0;
  jint ct_cnt = 0;

  jvmtiError err = jvmti->GetAllThreads(&all_cnt, &tested_cthreads);
  check_jvmti_status(jni, err, "get_cthreads: error in JVMTI GetAllThreads");

  for (int idx = 0; idx < all_cnt; idx++) {
    jthread thread = tested_cthreads[idx];
    char* tname = get_thread_name(jvmti, jni, thread);

    if (strncmp(tname, CTHREAD_NAME_START, CTHREAD_NAME_START_LEN) != 0) {
      continue;
    }
    tested_cthreads[ct_cnt++] = thread;
    check_jvmti_status(jni, err, "get_cthreads: error in JVMTI Deallocate");
    deallocate(jvmti, jni, (void*)tname);
  }
  *cthreads_p = tested_cthreads;
  return ct_cnt;
}

static void JNICALL
agent_proc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {
  jthread* tested_cthreads = NULL;
  jint cthread_cnt = 0;

  printf("\n## Agent: Wait for vthreads to start\n"); fflush(0);
  if (!nsk_jvmti_waitForSync(timeout)) {
    return;
  }
  if (!nsk_jvmti_resumeSync()) {
    return;
  }
  printf("\n## Agent: Test carrier threads\n"); fflush(0);
  cthread_cnt = get_cthreads(jni, &tested_cthreads);
  test_threads_suspend_resume(jni, cthread_cnt, tested_cthreads);
  test_jvmti_functions_for_threads(jni, false /*virtual */, cthread_cnt, tested_cthreads);

  printf("\n## Agent: Test virtual threads\n"); fflush(0);
  test_threads_suspend_resume(jni, VTHREAD_CNT, tested_vthreads);
  test_jvmti_functions_for_threads(jni, true /* virtual */, VTHREAD_CNT, tested_vthreads);

  test_thread_suspend_list(jni, tested_vthreads);
  test_thread_resume_list(jni, tested_vthreads);

  test_vthread_suspend_all(jni, tested_vthreads, 0x0);
  test_vthread_resume_all(jni, tested_vthreads, 0xFFFFFFFF);

  test_vthread_suspend_half(jni, tested_vthreads);
  test_vthread_resume_all(jni, tested_vthreads, 0x55555555);
  test_vthread_suspend_all(jni, tested_vthreads, 0x0);
  test_vthread_resume_half(jni, tested_vthreads);
  test_vthread_resume_all(jni, tested_vthreads, 0xAAAAAAAA);

  printf("\n## Agent: Wait for vthreads to finish\n"); fflush(0);

  for (int i = 0; i < VTHREAD_CNT; i++) {
    jni->DeleteGlobalRef(tested_vthreads[i]);
  }
  printf("\n## Agent: Let debugee to finish\n"); fflush(0);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorLocker agent_start_locker(jvmti, jni, agent_event_lock);

  tested_vthreads[vthread_no++] = jni->NewGlobalRef(vthread);
}

JNIEXPORT jint JNICALL
Java_SuspendResumeAll_GetStatus(JNIEnv* jni, jclass cls) {
  return nsk_jvmti_getStatus();
}

jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;

  printf("Agent init started\n");

  /* create JVMTI environment */
  if (jvm->GetEnv((void **)(&jvmti), JVMTI_VERSION) != JNI_OK) {
    printf("Agent init: error in getting JvmtiEnv with GetEnv\n");
    return JNI_ERR;
  }

  err = init_agent_data(jvmti, &agent_data);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent init: error in init_agent_data: %s (%d)\n",
           TranslateError(err), err);
    return JNI_ERR;
  }

  /* add specific capabilities for suspending thread */
  jvmtiCapabilities suspendCaps;
  jvmtiEventCallbacks callbacks;

  memset(&suspendCaps, 0, sizeof(suspendCaps));
  suspendCaps.can_suspend = 1;
  suspendCaps.can_support_virtual_threads = 1;

  err = jvmti->AddCapabilities(&suspendCaps);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent init: error in JVMTI AddCapabilities: %s (%d)\n",
           TranslateError(err), err);
    nsk_jvmti_setFailStatus();
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent init: error in JVMTI SetEventCallbacks: %s (%d)\n",
           TranslateError(err), err);
    nsk_jvmti_setFailStatus();
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent init: error in JVMTI SetEventNotificationMode: %s (%d)\n",
           TranslateError(err), err);
    nsk_jvmti_setFailStatus();
   return JNI_ERR;
  }

  agent_event_lock = create_raw_monitor(jvmti, "_agent_event_lock");

  /* register agent proc and arg */
  if (!nsk_jvmti_setAgentProc(agent_proc, NULL)) {
    printf("Agent init: error in nsk_jvmti_setAgentProc\n");
    nsk_jvmti_setFailStatus();
    return JNI_ERR;
  }

  printf("Agent init finished\n");
  fflush(0);
  return JNI_OK;
}

/** Agent library initialization. */
JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

}


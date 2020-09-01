/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "agent_common.h"
#include "jni_tools.h"
#include "jvmti_tools.h"

extern "C" {

/* ============================================================================= */

#define VTHREADS_CNT    30

static jlong timeout = 0;
static jvmtiEnv *jvmti = NULL;
static jrawMonitorID events_monitor = NULL;
static jthread agent_thread = NULL;
static jthread tested_vthreads[VTHREADS_CNT];
static int vthread_no = 0;
static const char* CTHREAD_NAME_START = "ForkJoinPool";
static const int CTHREAD_NAME_START_LEN = (int)strlen("ForkJoinPool");

static void
lock_events() {
    jvmti->RawMonitorEnter(events_monitor);
}

static void
unlock_events() {
    jvmti->RawMonitorExit(events_monitor);
}

static void
fatal(JNIEnv* jni, const char* msg) {
    jni->FatalError(msg);
    fflush(stdout);
}

static void
check_suspended_state(jthread thread, int thr_idx, char* tname, const char* func_name) {
    void *thread_p = (void*)thread;
    jint state = 0;

    if (!NSK_JVMTI_VERIFY(jvmti->GetThreadState(thread, &state))) {
        nsk_jvmti_setFailStatus();
    }
    printf("## Agent: thread[%d] %p %s: state after suspend: %s (%d)\n",
           thr_idx, thread_p,  tname, TranslateState(state), (int)state); fflush(0);
    if ((state & (JVMTI_THREAD_STATE_SUSPENDED | JVMTI_THREAD_STATE_TERMINATED)) == 0) {
        printf("## Agent: FAILED: %s did not turn on SUSPENDED flag:\n"
               "#  state: %s (%d)\n", func_name, TranslateState(state), (int)state);
        nsk_jvmti_setFailStatus();
    }
}

static void
check_resumed_state(jthread thread, int thr_idx, char* tname, const char* func_name) {
    void *thread_p = (void*)thread;
    jint state = 0;

    if (!NSK_JVMTI_VERIFY(jvmti->GetThreadState(thread, &state))) {
        nsk_jvmti_setFailStatus();
    }
    printf("## Agent: thread[%d] %p %s: state after resume: %s (%d)\n",
           thr_idx, thread_p, tname, TranslateState(state), (int)state); fflush(0);
    if (!((state & (JVMTI_THREAD_STATE_SUSPENDED | JVMTI_THREAD_STATE_TERMINATED)) == 0)) {
        printf("## Agent: FAILED: %s did not turn off SUSPENDED flag:\n"
               "#   state: %s (%d)\n", func_name, TranslateState(state), (int)state);
        nsk_jvmti_setFailStatus();
    }
}

static void
test_thread_suspend(jthread thread, int thr_idx, char* tname) {
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendThread(thread))) {
        nsk_jvmti_setFailStatus();
        return;
    }
    check_suspended_state(thread, thr_idx, tname, "SuspendThread");
}

static void
test_thread_resume(jthread thread, int thr_idx, char* tname) {
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeThread(thread))) {
        nsk_jvmti_setFailStatus();
    }
    check_resumed_state(thread, thr_idx, tname, "ResumeThread");
}

static void
test_thread_suspend_list(const jthread* thread_list) {
    jvmtiError results[VTHREADS_CNT] = {JVMTI_ERROR_NONE};
    jvmtiThreadInfo info;

    printf("\n## Agent: test_thread_suspend_list started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendThreadList(VTHREADS_CNT, thread_list, results))) {
        nsk_jvmti_setFailStatus();
        return;
    }   
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        check_suspended_state(thread, idx, tname,"SuspendThreadList");
    }
    printf("\n## Agent: test_thread_suspend_list finished\n"); fflush(0);
}

static void
test_thread_resume_list(const jthread* thread_list) {
    jvmtiError results[VTHREADS_CNT] = {JVMTI_ERROR_NONE};
    jvmtiThreadInfo info;

    printf("\n## Agent: test_thread_resume_list: started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeThreadList(VTHREADS_CNT, thread_list, results))) {
        nsk_jvmti_setFailStatus();
    }
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        check_resumed_state(thread, idx, tname, "ResumeThreadList");
    }
    printf("\n## Agent: test_thread_resume_list: finished\n"); fflush(0);
}

static void
test_vthread_suspend_all(const jthread* thread_list) {
    jvmtiThreadInfo info;

    printf("\n## Agent: test_vthread_suspend_all started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendAllVirtualThreads())) {
        nsk_jvmti_setFailStatus();
        return;
    }
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        check_suspended_state(thread_list[idx], idx, tname, "SuspendAllVirtualThreads");
    }
    printf("\n## Agent: test_vthread_suspend_all finished\n"); fflush(0);
}

static void
test_vthread_resume_all(const jthread* thread_list) {
    jvmtiThreadInfo info;

    printf("\n## Agent: test_vthread_resume_all started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeAllVirtualThreads())) {
        nsk_jvmti_setFailStatus();
    }
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        check_resumed_state(thread_list[idx], idx, tname, "ResumeAllVirtualThreads");
    }
    printf("\n## Agent: test_vthread_resume_all: finished\n"); fflush(0);
}

static void
test_vthread_suspend_half(const jthread* thread_list) {
    jvmtiThreadInfo info;

    printf("\n## Agent: test_vthread_suspend_half started\n"); fflush(0);
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        if (idx % 2 == 1) {
            continue; // skip odd indeces
        }
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        if (!NSK_JVMTI_VERIFY(jvmti->SuspendThread(thread))) {
            nsk_jvmti_setFailStatus();
        }
        check_suspended_state(thread, idx, tname, "SuspendThread");
    }
    printf("\n## Agent: test_vthread_suspend_half finished\n"); fflush(0);
}

static void
test_vthread_resume_half(const jthread* thread_list) {
    jvmtiThreadInfo info;
    jint state = 0;

    printf("\n## Agent: test_vthread_resume_half started\n"); fflush(0);
    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        if (idx % 2 == 1) {
            continue; // skip odd indeces
        }
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->ResumeThread(thread))) {
            nsk_jvmti_setFailStatus();
        }
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        check_resumed_state(thread, idx, tname, "ResumeThread");
    }
    printf("\n## Agent: test_vthread_resume_half: finished\n"); fflush(0);
}

static void test_threads_suspend_resume(JNIEnv* jni, jint threads_cnt, jthread* tested_vthreads) {
    jvmtiThreadInfo info;

    for (int idx = 0, ct_idx = 0; idx < threads_cnt; idx++) {
        jthread thread = tested_vthreads[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        if (strncmp(tname, CTHREAD_NAME_START, CTHREAD_NAME_START_LEN) != 0) {
            continue;
        }
        printf("\n");
        test_thread_suspend(thread, ct_idx, tname);
        test_thread_resume(thread, ct_idx, tname);
        ct_idx++;
        if (!NSK_JVMTI_VERIFY(jvmti->Deallocate((unsigned char*)tname))) {
            nsk_jvmti_setFailStatus();
        }    
    }
}

static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {
   jthread* tested_threads = NULL;
   jint threads_cnt = 0;

    printf("\n## Agent: Wait for vthreads to start\n"); fflush(0);
    if (!nsk_jvmti_waitForSync(timeout)) {
        return;
    }
    if (!nsk_jvmti_resumeSync()) {
        return;
    }
    if (!NSK_JVMTI_VERIFY(jvmti->GetCurrentThread(&agent_thread))) {
        nsk_jvmti_setFailStatus();
    }
    agent_thread = jni->NewGlobalRef(agent_thread);
    if (!NSK_JVMTI_VERIFY(jvmti->GetAllThreads(&threads_cnt, &tested_threads))) {
        nsk_jvmti_setFailStatus();
    }
    printf("\n## Agent: Test carrier threads\n"); fflush(0);
    test_threads_suspend_resume(jni, threads_cnt, tested_threads);

    printf("\n## Agent: Test virtual threads\n"); fflush(0);
    test_threads_suspend_resume(jni, VTHREADS_CNT, tested_vthreads);

    test_thread_suspend_list(tested_vthreads);
    test_thread_resume_list(tested_vthreads);

    test_vthread_suspend_all(tested_vthreads);
    test_vthread_resume_all(tested_vthreads);

    test_vthread_suspend_half(tested_vthreads);
    test_vthread_resume_all(tested_vthreads);
    test_vthread_suspend_all(tested_vthreads);
    test_vthread_resume_half(tested_vthreads);
    test_vthread_resume_all(tested_vthreads);

    printf("\n## Agent: Wait for vthreads to finish\n"); fflush(0);
    for (int i = 0; i < VTHREADS_CNT; i++) {
        jni->DeleteGlobalRef(tested_vthreads[i]);
    }
    printf("\n## Agent: Let debugee to finish\n"); fflush(0);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jvmtiThreadInfo thr_info;

  lock_events();

  if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(vthread, &thr_info))) {
      fatal(jni, "Agent: event handler failed during JVMTI GetThreadInfo call");
  }
  tested_vthreads[vthread_no++] = jni->NewGlobalRef(vthread);
  unlock_events();
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jvmtiThreadInfo thr_info;

  lock_events();

  if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(vthread, &thr_info))) {
      fatal(jni, "Agent: event handler failed during JVMTI GetThreadInfo call");
  }
  unlock_events();
}

JNIEXPORT jint JNICALL
Java_nsk_jvmti_SuspendThread_suspendvthr001_GetStatus(JNIEnv* jni, jclass cls) {
   return nsk_jvmti_getStatus();
}

/** Agent library initialization. */
#ifdef STATIC_BUILD
JNIEXPORT jint JNICALL Agent_OnLoad_suspendvthr001(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNICALL Agent_OnAttach_suspendvthr001(JavaVM *jvm, char *options, void *reserved) {
    return Agent_Initialize(jvm, options, reserved);
}
JNIEXPORT jint JNI_OnLoad_suspendvthr001(JavaVM *jvm, char *options, void *reserved) {
    return JNI_VERSION_1_8;
}
#endif
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
    /* init framework and parse options */
    if (!NSK_VERIFY(nsk_jvmti_parseOptions(options))) {
        return JNI_ERR;
    }
    timeout = nsk_jvmti_getWaitTime() * 60 * 1000;

    /* create JVMTI environment */
    if (!NSK_VERIFY((jvmti = nsk_jvmti_createJVMTIEnv(jvm, reserved)) != NULL))
        return JNI_ERR;

    /* add specific capabilities for suspending thread */
    {
        jvmtiCapabilities suspendCaps;
        jvmtiEventCallbacks callbacks;

        memset(&suspendCaps, 0, sizeof(suspendCaps));
        suspendCaps.can_suspend = 1;
        suspendCaps.can_support_virtual_threads = 1;
        if (!NSK_JVMTI_VERIFY(jvmti->AddCapabilities(&suspendCaps))) {
            return JNI_ERR;
        }
        memset(&callbacks, 0, sizeof(callbacks));
        callbacks.VirtualThreadScheduled  = &VirtualThreadScheduled;
        callbacks.VirtualThreadTerminated = &VirtualThreadTerminated;

        if (!NSK_JVMTI_VERIFY(jvmti->SetEventCallbacks(&callbacks,
                                         sizeof(jvmtiEventCallbacks)))) {
            return JNI_ERR;
        }
        if (!NSK_JVMTI_VERIFY(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                         JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL))) {
            return JNI_ERR;
        }
        if (!NSK_JVMTI_VERIFY(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                         JVMTI_EVENT_VIRTUAL_THREAD_TERMINATED, NULL))) {
            return JNI_ERR;
        }
        jvmti->CreateRawMonitor("Events Monitor", &events_monitor);
    }

    /* register agent proc and arg */
    if (!NSK_VERIFY(nsk_jvmti_setAgentProc(agentProc, NULL))) {
        return JNI_ERR;
    }
    return JNI_OK;
}

}


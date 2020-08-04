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
static jthread tested_vthreads[VTHREADS_CNT];
static int vthread_no = 0;

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
check_suspended_state(jthread vt, int thr_idx, const char* fname) {
    void *vtp = (void*) vt;
    jint state = 0;

    if (!NSK_JVMTI_VERIFY(jvmti->GetThreadState(vt, &state))) {
        nsk_jvmti_setFailStatus();
    }
    printf("## Agent: vthread[%d] %p: state after suspend: %s (%d)\n",
           thr_idx, vtp,  TranslateState(state), (int)state); fflush(0);
    if ((state & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        printf("## Agent: %s did not turn on SUSPENDED flag:\n"
               "#  state: %s (%d)\n", fname, TranslateState(state), (int)state);
        nsk_jvmti_setFailStatus();
    }
}

static void
check_resumed_state(jthread vt, int thr_idx, const char* fname) {
    void *vtp = (void*) vt;
    jint state = 0;

    if (!NSK_JVMTI_VERIFY(jvmti->GetThreadState(vt, &state))) {
        nsk_jvmti_setFailStatus();
    }
    printf("## Agent: vthread[%d] %p: state after resume: %s (%d)\n",
           thr_idx, vtp,  TranslateState(state), (int)state); fflush(0);
    if (!((state & JVMTI_THREAD_STATE_SUSPENDED) == 0)) {
        printf("## Agent: %s did not turn off SUSPENDED flag:\n"
               "#   state: %s (%d)\n", fname, TranslateState(state), (int)state);
        nsk_jvmti_setFailStatus();
    }
}

static void
test_vthread_suspend(jthread vt, int thr_idx) {
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendThread(vt))) {
        nsk_jvmti_setFailStatus();
        return;
    }
    check_suspended_state(vt, thr_idx, "SuspendThread");
}

static void
test_vthread_resume(jthread vt, int thr_idx) {
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeThread(vt))) {
        nsk_jvmti_setFailStatus();
    }
    check_resumed_state(vt, thr_idx, "ResumeThread");
}

static void
test_vthread_suspend_list(const jthread* vt_list) {
    jvmtiError results[VTHREADS_CNT] = {JVMTI_ERROR_NONE};

    printf("\n## Agent: test_vthread_suspend_list started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendThreadList(VTHREADS_CNT, vt_list, results))) {
        nsk_jvmti_setFailStatus();
        return;
    }   
    for (int i = 0; i < VTHREADS_CNT; i++) {
        check_suspended_state(vt_list[i], i, "SuspendThreadList");
    }
    printf("\n## Agent: test_vthread_suspend_list finished\n"); fflush(0);
}

static void
test_vthread_resume_list(const jthread* vt_list) {
    jvmtiError results[VTHREADS_CNT] = {JVMTI_ERROR_NONE};

    printf("\n## Agent: test_vthread_resume_list: started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeThreadList(VTHREADS_CNT, vt_list, results))) {
        nsk_jvmti_setFailStatus();
    }
    for (int i = 0; i < VTHREADS_CNT; i++) {
        check_resumed_state(vt_list[i], i, "ResumeThreadList");
    }
    printf("\n## Agent: test_vthread_resume_list: finished\n"); fflush(0);
}

static void
test_vthread_suspend_all(const jthread* vt_list) {
    printf("\n## Agent: test_vthread_suspend_all started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendAllVirtualThreads())) {
        nsk_jvmti_setFailStatus();
        return;
    }
    for (int i = 0; i < VTHREADS_CNT; i++) {
        check_suspended_state(vt_list[i], i, "SuspendAllVirtualThreads");
    }
    printf("\n## Agent: test_vthread_suspend_all finished\n"); fflush(0);
}

static void
test_vthread_resume_all(const jthread* vt_list) {
    printf("\n## Agent: test_vthread_resume_all started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeAllVirtualThreads())) {
        nsk_jvmti_setFailStatus();
    }
    for (int i = 0; i < VTHREADS_CNT; i++) {
        check_resumed_state(vt_list[i], i, "ResumeAllVirtualThreads");
    }
    printf("\n## Agent: test_vthread_resume_all: finished\n"); fflush(0);
}

static void
test_vthread_suspend_half(const jthread* vt_list) {
    printf("\n## Agent: test_vthread_suspend_half started\n"); fflush(0);
    for (int i = 0; i < VTHREADS_CNT; i++) {
        if (i % 2 == 1) {
            continue; // skip odd indeces
        }
        jthread vt = vt_list[i];
        if (!NSK_JVMTI_VERIFY(jvmti->SuspendThread(vt))) {
            nsk_jvmti_setFailStatus();
        }
        check_suspended_state(vt, i, "SuspendThread");
    }
    printf("\n## Agent: test_vthread_suspend_half finished\n"); fflush(0);
}

static void
test_vthread_resume_half(const jthread* vt_list) {
    jint state = 0;
    printf("\n## Agent: test_vthread_resume_half started\n"); fflush(0);
    for (int i = 0; i < VTHREADS_CNT; i++) {
        if (i % 2 == 1) {
            continue; // skip odd indeces
        }
        jthread vt = vt_list[i];
        if (!NSK_JVMTI_VERIFY(jvmti->ResumeThread(vt))) {
            nsk_jvmti_setFailStatus();
        }
        check_resumed_state(vt, i, "ResumeThread");
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadState(vt, &state))) {
            nsk_jvmti_setFailStatus();
        }
    }
    printf("\n## Agent: test_vthread_resume_half: finished\n"); fflush(0);
}


static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {

    printf("\n## Agent: Wait for vthreads to start\n"); fflush(0);
    if (!nsk_jvmti_waitForSync(timeout)) {
        return;
    }
    if (!nsk_jvmti_resumeSync()) {
        return;
    }
    printf("\n## Agent: Test vthreads\n"); fflush(0);

    for (int idx = 0; idx < VTHREADS_CNT; idx++) {
        jthread vt = tested_vthreads[idx];
        printf("\n");
        test_vthread_suspend(vt, idx);
        test_vthread_resume(vt, idx);
    }
    test_vthread_suspend_list(tested_vthreads);
    test_vthread_resume_list(tested_vthreads);

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
        nsk_jvmti_waitForSync(timeout);
    }
    printf("\n## Agent: Let debugee to finish\n"); fflush(0);
    nsk_jvmti_waitForSync(timeout);
    nsk_jvmti_resumeSync();
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jvmtiThreadInfo thr_info;

  lock_events();

  jvmtiError err = jvmti->GetThreadInfo(vthread, &thr_info);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "Agent: event handler failed during JVMTI GetThreadInfo call");
  }
  tested_vthreads[vthread_no++] = jni->NewGlobalRef(vthread);
  unlock_events();
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jvmtiThreadInfo thr_info;

  lock_events();

  jvmtiError err = jvmti->GetThreadInfo(vthread, &thr_info);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "Agent: event handler failed during JVMTI GetThreadInfo call");
  }
  unlock_events();
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
    if (!NSK_VERIFY((jvmti =
            nsk_jvmti_createJVMTIEnv(jvm, reserved)) != NULL))
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

        jvmtiError err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
        if (err != JVMTI_ERROR_NONE) {
            printf("Agent: error in JVMTI SetEventCallbacks: %d\n", err);
        }
        err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                              JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
        if (err != JVMTI_ERROR_NONE) {
            printf("Agent: error in JVMTI SetEventNotificationMode: %d\n", err);
        }
        err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                              JVMTI_EVENT_VIRTUAL_THREAD_TERMINATED, NULL);
        if (err != JVMTI_ERROR_NONE) {
            printf("Agent: error in JVMTI SetEventNotificationMode: %d\n", err);
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


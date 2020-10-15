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

#define MAX_FRAME_CNT 30
#define VTHREAD_CNT   30

static jlong timeout = 0;
static jvmtiEnv *jvmti = NULL;
static jrawMonitorID events_monitor = NULL;
static jthread tested_vthreads[VTHREAD_CNT];
static int vthread_no = 0;
static const char* CTHREAD_NAME_START = "ForkJoinPool";
static const int CTHREAD_NAME_START_LEN = (int)strlen("ForkJoinPool");

static void
lock() {
    jvmti->RawMonitorEnter(events_monitor);
}

static void
unlock() {
    jvmti->RawMonitorExit(events_monitor);
}
static void
fatal(JNIEnv* jni, const char* msg) {
    jni->FatalError(msg);
    fflush(stdout);
}
static char*
get_method_class_name(JNIEnv *jni, jmethodID method) {
    jvmtiError err;
    jclass klass = NULL;
    char*  cname = NULL;

    err = jvmti->GetMethodDeclaringClass(method, &klass);
    if (err != JVMTI_ERROR_NONE) {
        fatal(jni, "get_method_class_name: error in JVMTI GetMethodDeclaringClass");
    }
    err = jvmti->GetClassSignature(klass, &cname, NULL);
    if (err != JVMTI_ERROR_NONE) {
        fatal(jni, "get_method_class_name: error in JVMTI GetClassSignature");
    }
    cname[strlen(cname) - 1] = '\0'; // get rid of trailing ';'
    return cname + 1;                // get rid of leading 'L'
}

static void
print_method(JNIEnv *jni, jmethodID method, jint depth) {
    char*  cname = NULL;
    char*  mname = NULL;
    char*  msign = NULL;
    jvmtiError err;

    cname = get_method_class_name(jni, method);

    err = jvmti->GetMethodName(method, &mname, &msign, NULL);
    if (err != JVMTI_ERROR_NONE) {
        fatal(jni, "print_method: error in JVMTI GetMethodName");
    }
    printf("%2d: %s: %s%s\n", depth, cname, mname, msign);
}

static void
print_stack_trace(JNIEnv *jni, jint frame_count, jvmtiFrameInfo* frames, char* tname) {
    printf("## Agent: thread %s stack trace: frame count: %d\n", tname, frame_count);
    for (int depth = 0; depth < frame_count; depth++) {
        print_method(jni, frames[depth].method, depth);
    }
    printf("\n");
}

static void
test_get_stack_trace(JNIEnv *jni, jthread thread, char* tname) {
    jint frame_count = -1;
    jvmtiFrameInfo frames[MAX_FRAME_CNT];
    jvmtiError err = jvmti->GetStackTrace(thread, 0, MAX_FRAME_CNT, frames, &frame_count);

    if (err != JVMTI_ERROR_NONE) {
        printf("test_print_stack_trace: JVMTI GetStackTrace  returned error: %d\n", err);
        fatal(jni, "failed in JVMTI GetStackTrace call");
    }
    print_stack_trace(jni, frame_count, frames, tname);
}

static void
test_get_thread_list_stack_traces(JNIEnv *jni, bool is_virt, jint thread_cnt, jthread* thread_list) {
    jvmtiStackInfo* stack_info_arr = NULL;
    jvmtiThreadInfo info;

    printf("## Agent: test_get_thread_list_stack_traces started: is virtual: %d, count: %d\n\n",
           is_virt, thread_cnt);
    if (!NSK_JVMTI_VERIFY(jvmti->GetThreadListStackTraces(thread_cnt, thread_list,
                                                          MAX_FRAME_CNT, &stack_info_arr))) {
        nsk_jvmti_setFailStatus();
    }
    for (int idx = 0; idx < thread_cnt; idx++) {
        jvmtiStackInfo sinfo = stack_info_arr[idx];
        jthread thread = sinfo.thread;
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
            return;
        }
        print_stack_trace(jni, sinfo.frame_count, sinfo.frame_buffer, info.name);
    }
    printf("## Agent: test_get_thread_list_stack_traces finished: virtual: %d, count: %d\n\n",
          is_virt, thread_cnt);
}

static void
test_get_frame_location(JNIEnv* jni, jthread thread, char* tname) {
    jvmtiError err;
    const jint DEPTH = 1;
    jlocation loc = 0;
    jmethodID method = NULL;
    char* name = NULL;
    char* sign = NULL;
    jboolean is_virtual = jni->IsVirtualThread(thread);

    if (!NSK_JVMTI_VERIFY((err = jvmti->GetFrameLocation(thread, DEPTH, &method, &loc)))) {
        if (!is_virtual || err != JVMTI_ERROR_NO_MORE_FRAMES) { // TMP work around
            nsk_jvmti_setFailStatus();
        } else {
            printf("## Agent: test_get_frame_location: ignoring JVMTI_ERROR_NO_MORE_FRAMES for vt\n\n");
        }
        return;
    }
    if (!NSK_JVMTI_VERIFY(jvmti->GetMethodName(method, &name, &sign, NULL))) {
        nsk_jvmti_setFailStatus();
        return;
    }
    printf("Agent: GetFrameLocation: frame for current thread %s: method: %s%s, loc: %lld\n",
           tname, name, sign, (long long)loc);
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
    jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max
    jvmtiThreadInfo info;

    printf("\n## Agent: test_thread_suspend_list started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->SuspendThreadList(VTHREAD_CNT, thread_list, results))) {
        nsk_jvmti_setFailStatus();
        return;
    }   
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        check_suspended_state(thread, idx, info.name,"SuspendThreadList");
    }
    printf("\n## Agent: test_thread_suspend_list finished\n"); fflush(0);
}

static void
test_thread_resume_list(const jthread* thread_list) {
    jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max
    jvmtiThreadInfo info;

    printf("\n## Agent: test_thread_resume_list: started\n"); fflush(0);
    if (!NSK_JVMTI_VERIFY(jvmti->ResumeThreadList(VTHREAD_CNT, thread_list, results))) {
        nsk_jvmti_setFailStatus();
    }
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        check_resumed_state(thread, idx, info.name, "ResumeThreadList");
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
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        check_suspended_state(thread_list[idx], idx, info.name, "SuspendAllVirtualThreads");
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
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        check_resumed_state(thread_list[idx], idx, info.name, "ResumeAllVirtualThreads");
    }
    printf("\n## Agent: test_vthread_resume_all: finished\n"); fflush(0);
}

static void
test_vthread_suspend_half(const jthread* thread_list) {
    jvmtiThreadInfo info;

    printf("\n## Agent: test_vthread_suspend_half started\n"); fflush(0);
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
        if (idx % 2 == 1) {
            continue; // skip odd indeces
        }
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        if (!NSK_JVMTI_VERIFY(jvmti->SuspendThread(thread))) {
            nsk_jvmti_setFailStatus();
        }
        check_suspended_state(thread, idx, info.name, "SuspendThread");
    }
    printf("\n## Agent: test_vthread_suspend_half finished\n"); fflush(0);
}

static void
test_vthread_resume_half(const jthread* thread_list) {
    jvmtiThreadInfo info;
    jint state = 0;

    printf("\n## Agent: test_vthread_resume_half started\n"); fflush(0);
    for (int idx = 0; idx < VTHREAD_CNT; idx++) {
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
        check_resumed_state(thread, idx, info.name, "ResumeThread");
    }
    printf("\n## Agent: test_vthread_resume_half: finished\n"); fflush(0);
}

static void
test_threads_suspend_resume(JNIEnv* jni, jint thread_cnt, jthread* tested_threads) {
    jvmtiThreadInfo info;

    for (int idx = 0; idx < thread_cnt; idx++) {
        jthread thread = tested_threads[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        printf("\n");
        test_thread_suspend(thread, idx, info.name);
        test_thread_resume(thread, idx, info.name);
        if (!NSK_JVMTI_VERIFY(jvmti->Deallocate((unsigned char*)info.name))) {
            nsk_jvmti_setFailStatus();
        }    
    }
}

static void
test_jvmti_functions_for_one_thread(JNIEnv* jni, jthread thread, char* tname) {
    jint frame_count = 0;

    // test JVMTI GetFrameCount
    if (!NSK_JVMTI_VERIFY(jvmti->GetFrameCount(thread, &frame_count))) {
        nsk_jvmti_setFailStatus();
    }
    printf("## Agent: thread %s frame count: %d\n", tname, frame_count); fflush(0);

    // test JVMTI GetFrameLocation
    test_get_frame_location(jni, thread, tname);

    // test JVMTI GetStackTrace
    test_get_stack_trace(jni, thread, tname);
}

static void
test_jvmti_functions_for_threads(JNIEnv* jni, bool is_virt, jint thread_cnt, jthread* thread_list) {
    jvmtiError results[VTHREAD_CNT] = {JVMTI_ERROR_NONE}; // VTHREAD_CNT is max
    jvmtiThreadInfo info;
    jint frame_count = 0;

    printf("\n## Agent: test_jvmti_functions_for_threads started: virtual: %d\n\n", is_virt);
    fflush(0);

    // iterate over all vthreads
    for (int idx = 0; idx < thread_cnt; idx++) {
        jthread thread = thread_list[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        test_jvmti_functions_for_one_thread(jni, thread, info.name);
    }

    // test JVMTI GetTheadListStackTraces
    test_get_thread_list_stack_traces(jni, is_virt, 1, thread_list);          // test with one thread
    test_get_thread_list_stack_traces(jni, is_virt, thread_cnt, thread_list); // test with multiple threads

    printf("\n## Agent: test_jvmti_functions_for_threads finished: virtual: %d\n", is_virt);
    fflush(0);
}

static jint
get_cthreads(jthread** cthreads_p) {
    jvmtiThreadInfo info;
    jthread* tested_cthreads = NULL;
    jint all_cnt = 0;
    jint ct_cnt = 0;

    if (!NSK_JVMTI_VERIFY(jvmti->GetAllThreads(&all_cnt, &tested_cthreads))) {
        nsk_jvmti_setFailStatus();
    }

    for (int idx = 0; idx < all_cnt; idx++) {
        jthread thread = tested_cthreads[idx];
        if (!NSK_JVMTI_VERIFY(jvmti->GetThreadInfo(thread, &info))) {
            nsk_jvmti_setFailStatus();
        }
        char* tname = info.name;
        if (strncmp(tname, CTHREAD_NAME_START, CTHREAD_NAME_START_LEN) != 0) {
            continue;
        }
        tested_cthreads[ct_cnt++] = thread;
        if (!NSK_JVMTI_VERIFY(jvmti->Deallocate((unsigned char*)tname))) {
            nsk_jvmti_setFailStatus();
        }
    }
    *cthreads_p = tested_cthreads;
    return ct_cnt;
}

static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {
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
    cthread_cnt = get_cthreads(&tested_cthreads);
    test_threads_suspend_resume(jni, cthread_cnt, tested_cthreads);
    test_jvmti_functions_for_threads(jni, false /*virtual */, cthread_cnt, tested_cthreads);

    printf("\n## Agent: Test virtual threads\n"); fflush(0);
    test_threads_suspend_resume(jni, VTHREAD_CNT, tested_vthreads);
    test_jvmti_functions_for_threads(jni, true /* virtual */, VTHREAD_CNT, tested_vthreads);

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

    for (int i = 0; i < VTHREAD_CNT; i++) {
        jni->DeleteGlobalRef(tested_vthreads[i]);
    }
    printf("\n## Agent: Let debugee to finish\n"); fflush(0);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  lock();
  tested_vthreads[vthread_no++] = jni->NewGlobalRef(vthread);
  unlock();
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
        callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;

        if (!NSK_JVMTI_VERIFY(jvmti->SetEventCallbacks(&callbacks,
                                         sizeof(jvmtiEventCallbacks)))) {
            return JNI_ERR;
        }
        if (!NSK_JVMTI_VERIFY(jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                         JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL))) {
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


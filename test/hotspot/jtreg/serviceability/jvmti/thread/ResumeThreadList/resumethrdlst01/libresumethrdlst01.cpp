/*
 * Copyright (c) 2003, 2018, Oracle and/or its affiliates. All rights reserved.
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

/* scaffold objects */
static jlong timeout = 0;

/* constant names */
#define THREAD_NAME     "TestedThread"

/* constants */
#define DEFAULT_THREADS_COUNT   10

static int threadsCount = 0;

/* ============================================================================= */

static int find_threads_by_name(jvmtiEnv* jvmti, JNIEnv* jni,
                                const char name[], int foundCount, jthread foundThreads[]);

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {

    NSK_DISPLAY0("Wait for threads to start\n");
    if (!nsk_jvmti_waitForSync(timeout))
        return;

    /* perform testing */
    {
        jthread* threads = NULL;
        jvmtiError* results = NULL;
        int i;

        NSK_DISPLAY1("Allocate threads array: %d threads\n", threadsCount);
        check_jvmti_status(jni, jvmti->Allocate((threadsCount * sizeof(jthread)),
                                              (unsigned char**)&threads), "");

        NSK_DISPLAY1("  ... allocated array: %p\n", (void*)threads);

        NSK_DISPLAY1("Allocate results array: %d threads\n", threadsCount);
         check_jvmti_status(jni, jvmti->Allocate((threadsCount * sizeof(jvmtiError)),
                                              (unsigned char**)&results), "");

        NSK_DISPLAY1("  ... allocated array: %p\n", (void*)threads);

        NSK_DISPLAY1("Find threads: %d threads\n", threadsCount);
        if (find_threads_by_name(jvmti, jni, THREAD_NAME, threadsCount, threads) == 0) {
          return;
        }

        NSK_DISPLAY0("Suspend threads list\n");
        jvmtiError err = jvmti->SuspendThreadList(threadsCount, threads, results);
        if (err != JVMTI_ERROR_NONE) {
          nsk_jvmti_setFailStatus();
          return;
        }

        NSK_DISPLAY0("Check threads results:\n");
        for (i = 0; i < threadsCount; i++) {
            NSK_DISPLAY3("  ... thread #%d: %s (%d)\n",
                                i, TranslateError(results[i]), (int)results[i]);
            if (results[i] != JVMTI_ERROR_NONE) {
              nsk_jvmti_setFailStatus();
            }
        }

        NSK_DISPLAY0("Resume threads list\n");
        err = jvmti->ResumeThreadList(threadsCount, threads, results);
        if (err != JVMTI_ERROR_NONE) {
          nsk_jvmti_setFailStatus();
          return;
        }

        NSK_DISPLAY0("Check threads results:\n");
        for (i = 0; i < threadsCount; i++) {
            NSK_DISPLAY3("  ... thread #%d: %s (%d)\n",
                                i, TranslateError(results[i]), (int)results[i]);
            if (results[i] != JVMTI_ERROR_NONE) {
              nsk_jvmti_setFailStatus();
            }
        }

        NSK_DISPLAY0("Get state vector for each thread\n");
        for (i = 0; i < threadsCount; i++) {
            jint state = 0;

            NSK_DISPLAY2("  thread #%d (%p):\n", i, (void*)threads[i]);
            check_jvmti_status(jni, jvmti->GetThreadState(threads[i], &state), "");
            NSK_DISPLAY2("  ... got state vector: %s (%d)\n",
                            TranslateState(state), (int)state);

            if ((state & JVMTI_THREAD_STATE_SUSPENDED) != 0) {
                NSK_COMPLAIN3("ResumeThreadList() does not turn off flag SUSPENDED for thread #%i:\n"
                              "#   state:  %s (%d)\n",
                                i, TranslateState(state), (int)state);
                nsk_jvmti_setFailStatus();
            }
        }

        NSK_DISPLAY0("Let threads to run and finish\n");
        if (!nsk_jvmti_resumeSync())
            return;

        NSK_DISPLAY0("Wait for thread to finish\n");
        if (!nsk_jvmti_waitForSync(timeout))
            return;

        NSK_DISPLAY0("Delete threads references\n");
        for (i = 0; i < threadsCount; i++) {
            if (threads[i] != NULL)
                 jni->DeleteGlobalRef(threads[i]);
        }

        NSK_DISPLAY1("Deallocate threads array: %p\n", (void*)threads);
      check_jvmti_status(jni, jvmti->Deallocate((unsigned char*)threads), "");

        NSK_DISPLAY1("Deallocate results array: %p\n", (void*)results);
      check_jvmti_status(jni, jvmti->Deallocate((unsigned char*)results), "");
    }

    NSK_DISPLAY0("Let debugee to finish\n");
    if (!nsk_jvmti_resumeSync())
        return;
}

/* ============================================================================= */

/** Find threads whose name starts with specified name prefix. */
static int find_threads_by_name(jvmtiEnv* jvmti, JNIEnv* jni,
                            const char name[], int foundCount, jthread foundThreads[]) {
    jint count = 0;
    jthread* threads = NULL;

    size_t len = strlen(name);
    int found = 0;
    int i;

    for (i = 0; i < foundCount; i++) {
        foundThreads[i] = NULL;
    }

    check_jvmti_status(jni, jvmti->GetAllThreads(&count, &threads), "Error in GetAllThreads");

    found = 0;
    for (i = 0; i < count; i++) {
        jvmtiThreadInfo info;

      check_jvmti_status(jni, jvmti->GetThreadInfo(threads[i], &info), "");

        if (info.name != NULL && strncmp(name, info.name, len) == 0) {
            NSK_DISPLAY3("  ... found thread #%d: %p (%s)\n",
                                    found, threads[i], info.name);
            if (found < foundCount)
                foundThreads[found] = threads[i];
            found++;
        }

    }

    check_jvmti_status(jni, jvmti->Deallocate((unsigned char*)threads), "");

    if (found != foundCount) {
        NSK_COMPLAIN3("Unexpected number of tested threads found:\n"
                      "#   name:     %s\n"
                      "#   found:    %d\n"
                      "#   expected: %d\n",
                      name, found, foundCount);
        nsk_jvmti_setFailStatus();
        return NSK_FALSE;
    }

    NSK_DISPLAY1("Make global references for threads: %d threads\n", foundCount);
    for (i = 0; i < foundCount; i++) {
        foundThreads[i] = (jthread) jni->NewGlobalRef(foundThreads[i]);
        if ( foundThreads[i] == NULL) {
            nsk_jvmti_setFailStatus();
            return NSK_FALSE;
        }
        NSK_DISPLAY2("  ... thread #%d: %p\n", i, foundThreads[i]);
    }

    return NSK_TRUE;
}

/* ============================================================================= */

jint Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEnv* jvmti = NULL;

  timeout =  60 * 1000;

  jint res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_9);
  if (res != JNI_OK || jvmti == NULL) {
    LOG("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  /* add specific capabilities for suspending thread */
  {
    jvmtiCapabilities suspendCaps;
    memset(&suspendCaps, 0, sizeof(suspendCaps));
    suspendCaps.can_suspend = 1;
    if (jvmti->AddCapabilities(&suspendCaps) != JVMTI_ERROR_NONE) {
      return JNI_ERR;
    }
  }

  // TODO set somhow
  threadsCount = 10;

  if (init_agent_data(jvmti, &agent_data) != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  
  /* register agent proc and arg */
  if (!nsk_jvmti_setAgentProc(agentProc, NULL)) {
    return JNI_ERR;
  }

  return JNI_OK;
}

/* ============================================================================= */

}

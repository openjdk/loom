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

static int threads_count = 0;

/* ============================================================================= */

static int find_threads_by_name(jvmtiEnv *jvmti, JNIEnv *jni,
                             const char name[], int found_count, jthread found_threads[]);

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv *jvmti, JNIEnv *jni, void *arg) {

  LOG("Wait for threads to start\n");
  if (!nsk_jvmti_waitForSync(timeout))
    return;

  /* perform testing */
  {
    jthread *threads = NULL;
    jvmtiError *results = NULL;
    int i;

    LOG("Allocate threads array: %d threads\n", threads_count);
    check_jvmti_status(jni, jvmti->Allocate((threads_count * sizeof(jthread)),
                                            (unsigned char **) &threads), "");

    LOG("  ... allocated array: %p\n", (void *) threads);

    LOG("Allocate results array: %d threads\n", threads_count);
    check_jvmti_status(jni, jvmti->Allocate((threads_count * sizeof(jvmtiError)),
                                            (unsigned char **) &results), "");
    LOG("  ... allocated array: %p\n", (void *) threads);

    LOG("Find threads: %d threads\n", threads_count);
    if (find_threads_by_name(jvmti, jni, THREAD_NAME, threads_count, threads) == 0) {
      return;
    }

    LOG("Suspend threads list\n");
    jvmtiError err = jvmti->SuspendThreadList(threads_count, threads, results);
    if (err != JVMTI_ERROR_NONE) {
      nsk_jvmti_setFailStatus();
      return;
    }

    LOG("Check threads results:\n");
    for (i = 0; i < threads_count; i++) {
      NSK_DISPLAY3("  ... thread #%d: %s (%d)\n",
                   i, TranslateError(results[i]), (int) results[i]);
      if (results[i] != JVMTI_ERROR_NONE) {
        nsk_jvmti_setFailStatus();
      }
    }

    LOG("Let threads to run and finish\n");
    if (!nsk_jvmti_resumeSync())
      return;

    LOG("Get state vector for each thread\n");
    for (i = 0; i < threads_count; i++) {
      jint state = 0;

      LOG("  thread #%d (%p):\n", i, (void *) threads[i]);
      check_jvmti_status(jni, jvmti->GetThreadState(threads[i], &state), "");
      LOG("  ... got state vector: %s (%d)\n",
                   TranslateState(state), (int) state);

      if ((state & JVMTI_THREAD_STATE_SUSPENDED) == 0) {
        LOG("SuspendThreadList() does not turn on flag SUSPENDED for thread #%i:\n"
                      "#   state: %s (%d)", i, TranslateState(state), (int) state);
        nsk_jvmti_setFailStatus();
      }
    }

    LOG("Resume threads list\n");
    err = jvmti->ResumeThreadList(threads_count, threads, results);
    if (err != JVMTI_ERROR_NONE) {
      nsk_jvmti_setFailStatus();
      return;
    }

    LOG("Wait for thread to finish\n");
    if (!nsk_jvmti_waitForSync(timeout))
      return;

    LOG("Delete threads references\n");
    for (i = 0; i < threads_count; i++) {
      if (threads[i] != NULL)
        jni->DeleteGlobalRef(threads[i]);
    }

    LOG("Deallocate threads array: %p\n", (void *) threads);
    check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) threads), "");

    LOG("Deallocate results array: %p\n", (void *) results);
    check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) results), "");
  }

  LOG("Let debugee to finish\n");
  if (!nsk_jvmti_resumeSync())
    return;
}

/* ============================================================================= */

/** Find threads whose name starts with specified name prefix. */
static int find_threads_by_name(jvmtiEnv *jvmti, JNIEnv *jni, const char *name, int found_count, jthread *found_threads) {
  jint count = 0;
  jthread *threads = NULL;

  size_t len = strlen(name);
  int found = 0;

  for (int i = 0; i < found_count; i++) {
    found_threads[i] = NULL;
  }

  check_jvmti_status(jni, jvmti->GetAllThreads(&count, &threads), "Error in GetAllThreads");

  found = 0;
  for (int i = 0; i < count; i++) {
    jvmtiThreadInfo info;

    check_jvmti_status(jni, jvmti->GetThreadInfo(threads[i], &info), "");

    if (info.name != NULL && strncmp(name, info.name, len) == 0) {
      NSK_DISPLAY3("  ... found thread #%d: %p (%s)\n",
                   found, threads[i], info.name);
      if (found < found_count)
        found_threads[found] = threads[i];
      found++;
    }

  }

  check_jvmti_status(jni, jvmti->Deallocate((unsigned char *) threads), "");

  if (found != found_count) {
    LOG("Unexpected number of tested threads found:\n"
                  "#   name:     %s"
                  "#   found:    %d"
                  "#   expected: %d",
           name, found, found_count);
    nsk_jvmti_setFailStatus();
    return NSK_FALSE;
  }

  LOG("Make global references for threads: %d threads\n", found_count);
  for (int i = 0; i < found_count; i++) {
    found_threads[i] = (jthread) jni->NewGlobalRef(found_threads[i]);
    if (found_threads[i] == NULL) {
      nsk_jvmti_setFailStatus();
      return NSK_FALSE;
    }
    LOG("  ... thread #%d: %p\n", i, found_threads[i]);
  }

  return NSK_TRUE;
}

jint Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEnv *jvmti = NULL;

  timeout = 60 * 1000;

  jint res = jvm->GetEnv((void **) &jvmti, JVMTI_VERSION_9);
  if (res != JNI_OK || jvmti == NULL) {
    LOG("Wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  /* add specific capabilities for suspending thread */
  jvmtiCapabilities suspendCaps;
  memset(&suspendCaps, 0, sizeof(suspendCaps));
  suspendCaps.can_suspend = 1;
  if (jvmti->AddCapabilities(&suspendCaps) != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }


  // TODO set somehow configure
  threads_count = 10;

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

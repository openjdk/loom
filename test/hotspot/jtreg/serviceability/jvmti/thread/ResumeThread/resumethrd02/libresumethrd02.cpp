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
#define EVENTS_COUNT    1

/* events list */
static jvmtiEvent eventsList[EVENTS_COUNT] = {
    JVMTI_EVENT_THREAD_END
};

static volatile int eventsReceived = 0;
static jthread testedThread = NULL;

/* ============================================================================= */

/** Agent algorithm. */
static void JNICALL
agentProc(jvmtiEnv* jvmti, JNIEnv* jni, void* arg) {

    NSK_DISPLAY0("Wait for thread to start\n");
    if (!nsk_jvmti_waitForSync(timeout))
        return;

    /* perform testing */
    {
        NSK_DISPLAY1("Find thread: %s\n", THREAD_NAME);
        testedThread = nsk_jvmti_threadByName(jvmti, jni,THREAD_NAME);
        if (testedThread == NULL) {
          return;
        }
        NSK_DISPLAY1("  ... found thread: %p\n", (void*)testedThread);

        eventsReceived = 0;
        NSK_DISPLAY1("Enable event: %s\n", "THREAD_END");
        nsk_jvmti_enableEvents(jvmti, jni, JVMTI_ENABLE, EVENTS_COUNT, eventsList, NULL);

        NSK_DISPLAY1("Suspend thread: %p\n", (void*)testedThread);
        jvmtiError err = jvmti->SuspendThread(testedThread);
        if (err != JVMTI_ERROR_NONE) {
          nsk_jvmti_setFailStatus();
          return;
        }

        NSK_DISPLAY0("Let thread to run and finish\n");
        if (!nsk_jvmti_resumeSync())
            return;

        NSK_DISPLAY1("Resume thread: %p\n", (void*)testedThread);
        err = jvmti->ResumeThread(testedThread);
        if (err != JVMTI_ERROR_NONE) {
          nsk_jvmti_setFailStatus();
          return;
        }

        NSK_DISPLAY1("Check that THREAD_END event received for timeout: %ld ms\n", (long)timeout);
        {
            jlong delta = 1000;
            jlong time;
            for (time = 0; time < timeout; time += delta) {
                if (eventsReceived > 0)
                    break;
                nsk_jvmti_sleep(delta);
            }
            if (eventsReceived <= 0) {
                NSK_COMPLAIN0("Thread has not run and finished after resuming\n");
                nsk_jvmti_setFailStatus();
            }
        }

        NSK_DISPLAY1("Disable event: %s\n", "THREAD_END");
        nsk_jvmti_enableEvents(jvmti, jni,JVMTI_DISABLE, EVENTS_COUNT, eventsList, NULL);

        NSK_DISPLAY0("Wait for thread to finish\n");
        if (!nsk_jvmti_waitForSync(timeout))
            return;

        NSK_DISPLAY0("Delete thread reference\n");
        jni->DeleteGlobalRef(testedThread);
    }

    NSK_DISPLAY0("Let debugee to finish\n");
    if (!nsk_jvmti_resumeSync())
        return;
}

/* ============================================================================= */

/** THREAD_END callback. */
JNIEXPORT void JNICALL
callbackThreadEnd(jvmtiEnv* jvmti, JNIEnv* jni, jthread thread) {
    /* check if event is for tested thread */
    if (thread != NULL && jni->IsSameObject(testedThread, thread)) {
        NSK_DISPLAY1("  ... received THREAD_END event for tested thread: %p\n", (void*)thread);
        eventsReceived++;
    } else {
        NSK_DISPLAY1("  ... received THREAD_END event for unknown thread: %p\n", (void*)thread);
    }
}

/* ============================================================================= */

/** Agent library initialization. */
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

  /* set callbacks for THREAD_END event */
  {
    jvmtiEventCallbacks callbacks;
    memset(&callbacks, 0, sizeof(callbacks));
    callbacks.ThreadEnd = callbackThreadEnd;
    jvmtiError err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
      LOG("(SetEventCallbacks) unexpected error: %s (%d)\n", TranslateError(err), err);
      return JNI_ERR;
    }
  }

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

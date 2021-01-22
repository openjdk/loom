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

#ifndef JVMTI_THREAD_H
#define JVMTI_THREAD_H

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <ctype.h>



#ifdef _WIN32

#define LL "I64"
#include <STDDEF.H>

#else // !_WIN32

#include <stdint.h>

#ifdef _LP64
#define LL "l"
#else
#define LL "ll"
#endif

#endif // !_WIN32


extern "C" {


#define NSK_STATUS_PASSED       0
#define NSK_STATUS_FAILED       2

static jvmtiEnv* agent_jvmti_env = NULL;
static JNIEnv* agent_jni_env = NULL;

static volatile int currentAgentStatus = NSK_STATUS_PASSED;

static jthread agentThread = NULL;
static jvmtiStartFunction agentThreadProc = NULL;
static void* agentThreadArg = NULL;

void nsk_jvmti_setFailStatus() {
  currentAgentStatus = NSK_STATUS_FAILED;
}

jint nsk_jvmti_getStatus() {
  return currentAgentStatus;
}

typedef enum { NEW, RUNNABLE, WAITING, SUSPENDED, TERMINATED } thread_state_t;

typedef struct agent_data_t {
  volatile thread_state_t thread_state;
  int last_debuggee_status;
  jrawMonitorID monitor;
} agent_data_t;

int nsk_jvmti_setAgentProc(jvmtiStartFunction proc, void* arg) {
  agentThreadProc = proc;
  agentThreadArg = arg;
  return NSK_TRUE;
}

static agent_data_t agent_data;


static jvmtiError init_agent_data(jvmtiEnv *jvmti_env, agent_data_t *data) {
  data->thread_state = NEW;
  data->last_debuggee_status = NSK_STATUS_PASSED;
  agent_jvmti_env = jvmti_env;
  return jvmti_env->CreateRawMonitor("agent_data_monitor", &data->monitor);
}

jint createRawMonitor(jvmtiEnv *env, const char *name, jrawMonitorID *monitor) {
  jvmtiError error = env->CreateRawMonitor(name, monitor);
  if (error != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }
  return JNI_OK;
}

void exitOnError(jvmtiError error) {
  if (error != JVMTI_ERROR_NONE) {
    exit(error);
  }
}

void rawMonitorEnter(jvmtiEnv *env, jrawMonitorID monitor) {
  jvmtiError error = env->RawMonitorEnter(monitor);
  exitOnError(error);
}

void rawMonitorExit(jvmtiEnv *env, jrawMonitorID monitor) {
  jvmtiError error = env->RawMonitorExit(monitor);
  exitOnError(error);
}

void rawMonitorNotify(jvmtiEnv *env, jrawMonitorID monitor) {
  jvmtiError error = env->RawMonitorNotify(monitor);
  exitOnError(error);
}

void rawMonitorWait(jvmtiEnv *env, jrawMonitorID monitor, jlong millis) {
  jvmtiError error = env->RawMonitorWait(monitor, millis);
  exitOnError(error);
}


/** Wait for sync point with Java code. */
int nsk_jvmti_waitForSync(jlong timeout) {
  static const int inc_timeout = 1000;

  jlong t = 0;
  int result = NSK_TRUE;

  rawMonitorEnter(agent_jvmti_env, agent_data.monitor);

  agent_data.thread_state = WAITING;

  /* SP2.2-n - notify agent is waiting and wait */
  /* SP4.1-n - notify agent is waiting and wait */
  rawMonitorNotify(agent_jvmti_env, agent_data.monitor);

  while (agent_data.thread_state == WAITING) {
    /* SP3.2-w - wait to start test */
    /* SP6.2-w - wait to end test */
    rawMonitorWait(agent_jvmti_env, agent_data.monitor, inc_timeout);

    if (timeout == 0) continue;

    t += inc_timeout;

    if (t >= timeout) break;
  }

  if (agent_data.thread_state == WAITING) {
      NSK_COMPLAIN1("No status sync occured for timeout: %" LL "d ms\n", timeout);
    nsk_jvmti_setFailStatus();
    result = NSK_FALSE;
  }

  rawMonitorExit(agent_jvmti_env, agent_data.monitor);

  return result;
}

/** Resume java code suspended on sync point. */
int nsk_jvmti_resumeSync() {
  int result;
  rawMonitorEnter(agent_jvmti_env, agent_data.monitor);

  if (agent_data.thread_state == SUSPENDED) {
    result = NSK_TRUE;
    agent_data.thread_state = RUNNABLE;
    /* SP5.2-n - notify suspend done */
    /* SP7.2-n - notify agent end */
    rawMonitorNotify(agent_jvmti_env, agent_data.monitor);
  }
  else {
    NSK_COMPLAIN0("Debuggee was not suspended on status sync\n");
    nsk_jvmti_setFailStatus();
    result = NSK_FALSE;
  }

  rawMonitorExit(agent_jvmti_env, agent_data.monitor);
  return NSK_TRUE;
}

/* ============================================================================= */
static void set_agent_thread_state(thread_state_t value) {
  rawMonitorEnter(agent_jvmti_env, agent_data.monitor);
  agent_data.thread_state = value;
  rawMonitorNotify(agent_jvmti_env, agent_data.monitor);
  rawMonitorExit(agent_jvmti_env, agent_data.monitor);
}

/** Wrapper for user agent thread. */
static void JNICALL
agentThreadWrapper(jvmtiEnv* jvmti_env, JNIEnv* agentJNI, void* arg) {
  agent_jni_env = agentJNI;

  /* run user agent proc */
  {
    set_agent_thread_state(RUNNABLE);

    // TODO was NSK_TRACE
    (*agentThreadProc)(jvmti_env, agentJNI, agentThreadArg);

    set_agent_thread_state(TERMINATED);
  }

  /* finalize agent thread */
  {
    /* gelete global ref for agent thread */
    agentJNI->DeleteGlobalRef(agentThread);
    agentThread = NULL;
  }
}


/** Start wrapper for user agent thread. */
static jthread startAgentThreadWrapper(JNIEnv *jni_env, jvmtiEnv* jvmti_env) {
  const jint  THREAD_PRIORITY = JVMTI_THREAD_MAX_PRIORITY;
  const char* THREAD_NAME = "JVMTI agent thread";
  const char* THREAD_CLASS_NAME = "java/lang/Thread";
  const char* THREAD_CTOR_NAME = "<init>";
  const char* THREAD_CTOR_SIGNATURE = "(Ljava/lang/String;)V";

  jobject threadName = NULL;
  jclass threadClass = NULL;
  jmethodID threadCtor = NULL;
  jobject threadObject = NULL;
  jobject threadGlobalRef = NULL;
  jvmtiError err;

  threadClass = jni_env->FindClass(THREAD_CLASS_NAME);
  if (threadClass == NULL) {
    return NULL;
  }

  threadCtor = jni_env->GetMethodID(threadClass, THREAD_CTOR_NAME, THREAD_CTOR_SIGNATURE);
  if (threadCtor == NULL) {
    return NULL;
  }

  threadName = jni_env->NewStringUTF(THREAD_NAME);
  if (threadName == NULL) {
    return NULL;
  }

  threadObject = jni_env->NewObject(threadClass, threadCtor, threadName);
  if (threadObject == NULL) {
    return NULL;
  }

  threadGlobalRef = jni_env->NewGlobalRef(threadObject);
  if (threadGlobalRef == NULL) {
    jni_env->DeleteLocalRef(threadObject);
    return NULL;
  }
  agentThread = (jthread)threadGlobalRef;

  err = jvmti_env->RunAgentThread(agentThread, &agentThreadWrapper, agentThreadArg, THREAD_PRIORITY);
  if (err != JVMTI_ERROR_NONE) {
    jni_env->DeleteGlobalRef(threadGlobalRef);
    jni_env->DeleteLocalRef(threadObject);
    return NULL;
  }
  return agentThread;
}

/** Run registered user agent thread via wrapper. */
static jthread nsk_jvmti_runAgentThread(JNIEnv *jni_env, jvmtiEnv* jvmti_env) {
  /* start agent thread wrapper */
  jthread thread = startAgentThreadWrapper(jni_env, jvmti_env);
  if (thread == NULL) {
    nsk_jvmti_setFailStatus();
    return NULL;
  }

  return thread;
}

/** Sync point called from Java code. */
static jint syncDebuggeeStatus(JNIEnv* jni_env, jvmtiEnv* jvmti_env, jint debuggeeStatus) {
  jint result = NSK_STATUS_FAILED;

  printf("Data %p %p\n", jvmti_env, agent_data.monitor);
  rawMonitorEnter(jvmti_env, agent_data.monitor);

  /* save last debugee status */
  agent_data.last_debuggee_status = debuggeeStatus;

  /* we don't enter if-stmt in second call */
  if (agent_data.thread_state == NEW) {
    if (nsk_jvmti_runAgentThread(jni_env, jvmti_env) == NULL)
      goto monitor_exit_and_return;

    /* SP2.2-w - wait for agent thread */
    while (agent_data.thread_state == NEW) {
      rawMonitorWait(jvmti_env, agent_data.monitor, 0);
    }
  }

  /* wait for sync permit */
  /* we don't enter loop in first call */
  while (agent_data.thread_state != WAITING && agent_data.thread_state != TERMINATED) {
    /* SP4.2-w - second wait for agent thread */
    rawMonitorWait(jvmti_env, agent_data.monitor, 0);
  }

  if (agent_data.thread_state != TERMINATED) {
    agent_data.thread_state = SUSPENDED;
    /* SP3.2-n - notify to start test */
    /* SP6.2-n - notify to end test */
    rawMonitorNotify(jvmti_env, agent_data.monitor);
  } else {
    NSK_COMPLAIN0("Debuggee status sync aborted because agent thread has finished\n");
    goto monitor_exit_and_return;
  }

  /* update status from debuggee */
  if (debuggeeStatus != NSK_STATUS_PASSED) {
    printf("FAIL: Status is %d\n", debuggeeStatus);
    nsk_jvmti_setFailStatus();
  }

  while (agent_data.thread_state == SUSPENDED) {
    /* SP5.2-w - wait while testing */
    /* SP7.2 - wait for agent end */
    rawMonitorWait(jvmti_env, agent_data.monitor, 0);
  }

  agent_data.last_debuggee_status = nsk_jvmti_getStatus();
  result = agent_data.last_debuggee_status;

  monitor_exit_and_return:
  rawMonitorExit(jvmti_env, agent_data.monitor);
  return result;
}

/** Native function for Java code to provide sync point. */
JNIEXPORT jint JNICALL
Java_jdk_test_lib_jvmti_DebugeeClass_checkStatus(JNIEnv* jni_env, jclass cls, jint debuggeeStatus) {
  jint status;
  printf("Synchronization point checkStatus(%d) called.\n", debuggeeStatus);
  status = syncDebuggeeStatus(jni_env, agent_jvmti_env, debuggeeStatus);
  return status;
}

}

#endif

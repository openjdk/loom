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

#ifndef AGENT_COMMON_H
#define AGENT_COMMON_H

#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <ctype.h>

#define NSK_TRUE 1
#define NSK_FALSE 0

#define NSK_DISPLAY0 printf
#define NSK_DISPLAY1 printf
#define NSK_DISPLAY2 printf
#define NSK_DISPLAY3 printf
#define NSK_DISPLAY4 printf

#define NSK_COMPLAIN0 printf
#define NSK_COMPLAIN1 printf
#define NSK_COMPLAIN3 printf



#define NSK_JNI_VERIFY(jni, action)  (action)
#define NSK_JVMTI_VERIFY(action) (action)

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

static jvmtiEnv* jvmti_env = NULL;
static JavaVM* jvm = NULL;
static JNIEnv* jni_env = NULL;

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

int  nsk_jvmti_getWaitTime() {
  return 1;
}

char *jlong_to_string(jlong value, char *string) {
  char buffer[32];
  char *pbuf, *pstr;

  pstr = string;
  if (value == 0) {
    *pstr++ = '0';
  } else {
    if (value < 0) {
      *pstr++ = '-';
      value = -value;
    }
    pbuf = buffer;
    while (value != 0) {
      *pbuf++ = '0' + (char)(value % 10);
      value = value / 10;
    }
    while (pbuf != buffer) {
      *pstr++ = *--pbuf;
    }
  }
  *pstr = '\0';

  return string;
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


static void
check_jvmti_status(JNIEnv* jni, jvmtiError err, const char* msg) {
  if (err != JVMTI_ERROR_NONE) {
    printf("check_jvmti_status: JVMTI function returned error: %d\n", err);
    jni->FatalError(msg);
  }
}


static jvmtiError init_agent_data(jvmtiEnv *jvmti_env, agent_data_t *data) {
  data->thread_state = NEW;
  data->last_debuggee_status = NSK_STATUS_PASSED;

  return jvmti_env->CreateRawMonitor("agent_data_monitor", &data->monitor);
}

jint createRawMonitor(jvmtiEnv *env, const char *name, jrawMonitorID *monitor) {
  jvmtiError error = env->CreateRawMonitor(name, monitor);
  if (!NSK_JVMTI_VERIFY(error)) {
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

  rawMonitorEnter(jvmti_env, agent_data.monitor);

  agent_data.thread_state = WAITING;

  /* SP2.2-n - notify agent is waiting and wait */
  /* SP4.1-n - notify agent is waiting and wait */
  rawMonitorNotify(jvmti_env, agent_data.monitor);

  while (agent_data.thread_state == WAITING) {
    /* SP3.2-w - wait to start test */
    /* SP6.2-w - wait to end test */
    rawMonitorWait(jvmti_env, agent_data.monitor, inc_timeout);

    if (timeout == 0) continue;

    t += inc_timeout;

    if (t >= timeout) break;
  }

  if (agent_data.thread_state == WAITING) {
      NSK_COMPLAIN1("No status sync occured for timeout: %" LL "d ms\n", timeout);
    nsk_jvmti_setFailStatus();
    result = NSK_FALSE;
  }

  rawMonitorExit(jvmti_env, agent_data.monitor);

  return result;
}

/** Resume java code suspended on sync point. */
int nsk_jvmti_resumeSync() {
  int result;
  rawMonitorEnter(jvmti_env, agent_data.monitor);

  if (agent_data.thread_state == SUSPENDED) {
    result = NSK_TRUE;
    agent_data.thread_state = RUNNABLE;
    /* SP5.2-n - notify suspend done */
    /* SP7.2-n - notify agent end */
    rawMonitorNotify(jvmti_env, agent_data.monitor);
  }
  else {
    NSK_COMPLAIN0("Debuggee was not suspended on status sync\n");
    nsk_jvmti_setFailStatus();
    result = NSK_FALSE;
  }

  rawMonitorExit(jvmti_env, agent_data.monitor);
  return NSK_TRUE;
}

/* ============================================================================= */
static void set_agent_thread_state(thread_state_t value) {
  rawMonitorEnter(jvmti_env, agent_data.monitor);
  agent_data.thread_state = value;
  rawMonitorNotify(jvmti_env, agent_data.monitor);
  rawMonitorExit(jvmti_env, agent_data.monitor);
}

/** Wrapper for user agent thread. */
static void JNICALL
agentThreadWrapper(jvmtiEnv* jvmti_env, JNIEnv* agentJNI, void* arg) {
  jni_env = agentJNI;

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

  printf("PPPPPPPPPPPPPPPP 1\n");
  printf("Data %p %p\n", jvmti_env, agent_data.monitor);
  rawMonitorEnter(jvmti_env, agent_data.monitor);
  printf("PPPPPPPPPPPPPPPP 2\n");
  /* save last debugee status */
  agent_data.last_debuggee_status = debuggeeStatus;
  printf("PPPPPPPPPPPPPPPP 3\n");
  /* we don't enter if-stmt in second call */
  if (agent_data.thread_state == NEW) {
    if (nsk_jvmti_runAgentThread(jni_env, jvmti_env) == NULL)
      goto monitor_exit_and_return;

    /* SP2.2-w - wait for agent thread */
    while (agent_data.thread_state == NEW) {
      rawMonitorWait(jvmti_env, agent_data.monitor, 0);
    }
  }
  printf("PPPPPPPPPPPPPPPP 4\n");
  /* wait for sync permit */
  /* we don't enter loop in first call */
  while (agent_data.thread_state != WAITING && agent_data.thread_state != TERMINATED) {
    /* SP4.2-w - second wait for agent thread */
    rawMonitorWait(jvmti_env, agent_data.monitor, 0);
  }
  printf("PPPPPPPPPPPPPPPP 5\n");
  if (agent_data.thread_state != TERMINATED) {
    agent_data.thread_state = SUSPENDED;
    /* SP3.2-n - notify to start test */
    /* SP6.2-n - notify to end test */
    rawMonitorNotify(jvmti_env, agent_data.monitor);
  } else {
    NSK_COMPLAIN0("Debuggee status sync aborted because agent thread has finished\n");
    goto monitor_exit_and_return;
  }
  printf("PPPPPPPPPPPPPPPP 6\n");
  /* update status from debuggee */
  if (debuggeeStatus != NSK_STATUS_PASSED) {
    printf("FAIL: Status is %d\n", debuggeeStatus);
    nsk_jvmti_setFailStatus();
  }
  printf("PPPPPPPPPPPPPPPP 7\n");
  while (agent_data.thread_state == SUSPENDED) {
    /* SP5.2-w - wait while testing */
    /* SP7.2 - wait for agent end */
    rawMonitorWait(jvmti_env, agent_data.monitor, 0);
  }
  printf("PPPPPPPPPPPPPPPP 8\n");
  agent_data.last_debuggee_status = nsk_jvmti_getStatus();
  result = agent_data.last_debuggee_status;
  printf("PPPPPPPPPPPPPPPP 9\n");

  monitor_exit_and_return:
  rawMonitorExit(jvmti_env, agent_data.monitor);
  return result;
}

/** Native function for Java code to provide sync point. */
JNIEXPORT jint JNICALL
Java_jdk_test_lib_jvmti_DebugeeClass_checkStatus(JNIEnv* jni_env, jclass cls, jint debuggeeStatus) {
  jint status;
  // TODO NSK_TRACE
  printf("SSSSSSSSSTATUS %d\n", debuggeeStatus);
  status = syncDebuggeeStatus(jni_env, jvmti_env, debuggeeStatus);
  return status;
}

/** Create JVMTI environment. */
jvmtiEnv* nsk_jvmti_createJVMTIEnv(JavaVM* javaVM, void* reserved) {
  jvm = javaVM;
  if (javaVM->GetEnv((void **)&jvmti_env, JVMTI_VERSION_1_1) != JNI_OK) {
    nsk_jvmti_setFailStatus();
    return NULL;
  }

  if (init_agent_data(jvmti_env, &agent_data) != 0) {
    nsk_jvmti_setFailStatus();
    return NULL;
  }

  return jvmti_env;
}

int nsk_jvmti_parseOptions(const char options[]) {
  return 0;
}

const char* TranslateState(jint flags) {
    static char str[15 * 20];

    if (flags == 0)
        return "<none>";

    str[0] = '\0';

    if (flags & JVMTI_THREAD_STATE_ALIVE) {
        strcat(str, " ALIVE");
    }

    if (flags & JVMTI_THREAD_STATE_TERMINATED) {
        strcat(str, " TERMINATED");
    }

    if (flags & JVMTI_THREAD_STATE_RUNNABLE) {
        strcat(str, " RUNNABLE");
    }

    if (flags & JVMTI_THREAD_STATE_WAITING) {
        strcat(str, " WAITING");
    }

    if (flags & JVMTI_THREAD_STATE_WAITING_INDEFINITELY) {
        strcat(str, " WAITING_INDEFINITELY");
    }

    if (flags & JVMTI_THREAD_STATE_WAITING_WITH_TIMEOUT) {
        strcat(str, " WAITING_WITH_TIMEOUT");
    }

    if (flags & JVMTI_THREAD_STATE_SLEEPING) {
        strcat(str, " SLEEPING");
    }

    if (flags & JVMTI_THREAD_STATE_IN_OBJECT_WAIT) {
        strcat(str, " IN_OBJECT_WAIT");
    }

    if (flags & JVMTI_THREAD_STATE_PARKED) {
        strcat(str, " PARKED");
    }

    if (flags & JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER) {
        strcat(str, " BLOCKED_ON_MONITOR_ENTER");
    }

    if (flags & JVMTI_THREAD_STATE_SUSPENDED) {
        strcat(str, " SUSPENDED");
    }

    if (flags & JVMTI_THREAD_STATE_INTERRUPTED) {
        strcat(str, " INTERRUPTED");
    }

    if (flags & JVMTI_THREAD_STATE_IN_NATIVE) {
        strcat(str, " IN_NATIVE");
    }

    return str;
}

const char* TranslateEvent(jvmtiEvent event_type) {
    switch (event_type) {
    case JVMTI_EVENT_VM_INIT:
        return ("JVMTI_EVENT_VM_INIT");
    case JVMTI_EVENT_VM_DEATH:
        return ("JVMTI_EVENT_VM_DEATH");
    case JVMTI_EVENT_THREAD_START:
        return ("JVMTI_EVENT_THREAD_START");
    case JVMTI_EVENT_THREAD_END:
        return ("JVMTI_EVENT_THREAD_END");
    case JVMTI_EVENT_CLASS_FILE_LOAD_HOOK:
        return ("JVMTI_EVENT_CLASS_FILE_LOAD_HOOK");
    case JVMTI_EVENT_CLASS_LOAD:
        return ("JVMTI_EVENT_CLASS_LOAD");
    case JVMTI_EVENT_CLASS_PREPARE:
        return ("JVMTI_EVENT_CLASS_PREPARE");
    case JVMTI_EVENT_VM_START:
        return ("JVMTI_EVENT_VM_START");
    case JVMTI_EVENT_EXCEPTION:
        return ("JVMTI_EVENT_EXCEPTION");
    case JVMTI_EVENT_EXCEPTION_CATCH:
        return ("JVMTI_EVENT_EXCEPTION_CATCH");
    case JVMTI_EVENT_SINGLE_STEP:
        return ("JVMTI_EVENT_SINGLE_STEP");
    case JVMTI_EVENT_FRAME_POP:
        return ("JVMTI_EVENT_FRAME_POP");
    case JVMTI_EVENT_BREAKPOINT:
        return ("JVMTI_EVENT_BREAKPOINT");
    case JVMTI_EVENT_FIELD_ACCESS:
        return ("JVMTI_EVENT_FIELD_ACCESS");
    case JVMTI_EVENT_FIELD_MODIFICATION:
        return ("JVMTI_EVENT_FIELD_MODIFICATION");
    case JVMTI_EVENT_METHOD_ENTRY:
        return ("JVMTI_EVENT_METHOD_ENTRY");
    case JVMTI_EVENT_METHOD_EXIT:
        return ("JVMTI_EVENT_METHOD_EXIT");
    case JVMTI_EVENT_NATIVE_METHOD_BIND:
        return ("JVMTI_EVENT_NATIVE_METHOD_BIND");
    case JVMTI_EVENT_COMPILED_METHOD_LOAD:
        return ("JVMTI_EVENT_COMPILED_METHOD_LOAD");
    case JVMTI_EVENT_COMPILED_METHOD_UNLOAD:
        return ("JVMTI_EVENT_COMPILED_METHOD_UNLOAD");
    case JVMTI_EVENT_DYNAMIC_CODE_GENERATED:
        return ("JVMTI_EVENT_DYNAMIC_CODE_GENERATED");
    case JVMTI_EVENT_DATA_DUMP_REQUEST:
        return ("JVMTI_EVENT_DATA_DUMP_REQUEST");
    case JVMTI_EVENT_MONITOR_WAIT:
        return ("JVMTI_EVENT_MONITOR_WAIT");
    case JVMTI_EVENT_MONITOR_WAITED:
        return ("JVMTI_EVENT_MONITOR_WAITED");
    case JVMTI_EVENT_MONITOR_CONTENDED_ENTER:
        return ("JVMTI_EVENT_MONITOR_CONTENDED_ENTER");
    case JVMTI_EVENT_MONITOR_CONTENDED_ENTERED:
        return ("JVMTI_EVENT_MONITOR_CONTENDED_ENTERED");
    case JVMTI_EVENT_GARBAGE_COLLECTION_START:
        return ("JVMTI_EVENT_GARBAGE_COLLECTION_START");
    case JVMTI_EVENT_GARBAGE_COLLECTION_FINISH:
        return ("JVMTI_EVENT_GARBAGE_COLLECTION_FINISH");
    case JVMTI_EVENT_OBJECT_FREE:
        return ("JVMTI_EVENT_OBJECT_FREE");
    case JVMTI_EVENT_VM_OBJECT_ALLOC:
        return ("JVMTI_EVENT_VM_OBJECT_ALLOC");
    default:
        return ("<unknown event>");
    }
}

const char* TranslateError(jvmtiError err) {
    switch (err) {
    case JVMTI_ERROR_NONE:
        return ("JVMTI_ERROR_NONE");
    case JVMTI_ERROR_INVALID_THREAD:
        return ("JVMTI_ERROR_INVALID_THREAD");
    case JVMTI_ERROR_INVALID_THREAD_GROUP:
        return ("JVMTI_ERROR_INVALID_THREAD_GROUP");
    case JVMTI_ERROR_INVALID_PRIORITY:
        return ("JVMTI_ERROR_INVALID_PRIORITY");
    case JVMTI_ERROR_THREAD_NOT_SUSPENDED:
        return ("JVMTI_ERROR_THREAD_NOT_SUSPENDED");
    case JVMTI_ERROR_THREAD_SUSPENDED:
        return ("JVMTI_ERROR_THREAD_SUSPENDED");
    case JVMTI_ERROR_THREAD_NOT_ALIVE:
        return ("JVMTI_ERROR_THREAD_NOT_ALIVE");
    case JVMTI_ERROR_INVALID_OBJECT:
        return ("JVMTI_ERROR_INVALID_OBJECT");
    case JVMTI_ERROR_INVALID_CLASS:
        return ("JVMTI_ERROR_INVALID_CLASS");
    case JVMTI_ERROR_CLASS_NOT_PREPARED:
        return ("JVMTI_ERROR_CLASS_NOT_PREPARED");
    case JVMTI_ERROR_INVALID_METHODID:
        return ("JVMTI_ERROR_INVALID_METHODID");
    case JVMTI_ERROR_INVALID_LOCATION:
        return ("JVMTI_ERROR_INVALID_LOCATION");
    case JVMTI_ERROR_INVALID_FIELDID:
        return ("JVMTI_ERROR_INVALID_FIELDID");
    case JVMTI_ERROR_NO_MORE_FRAMES:
        return ("JVMTI_ERROR_NO_MORE_FRAMES");
    case JVMTI_ERROR_OPAQUE_FRAME:
        return ("JVMTI_ERROR_OPAQUE_FRAME");
    case JVMTI_ERROR_TYPE_MISMATCH:
        return ("JVMTI_ERROR_TYPE_MISMATCH");
    case JVMTI_ERROR_INVALID_SLOT:
        return ("JVMTI_ERROR_INVALID_SLOT");
    case JVMTI_ERROR_DUPLICATE:
        return ("JVMTI_ERROR_DUPLICATE");
    case JVMTI_ERROR_NOT_FOUND:
        return ("JVMTI_ERROR_NOT_FOUND");
    case JVMTI_ERROR_INVALID_MONITOR:
        return ("JVMTI_ERROR_INVALID_MONITOR");
    case JVMTI_ERROR_NOT_MONITOR_OWNER:
        return ("JVMTI_ERROR_NOT_MONITOR_OWNER");
    case JVMTI_ERROR_INTERRUPT:
        return ("JVMTI_ERROR_INTERRUPT");
    case JVMTI_ERROR_INVALID_CLASS_FORMAT:
        return ("JVMTI_ERROR_INVALID_CLASS_FORMAT");
    case JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION:
        return ("JVMTI_ERROR_CIRCULAR_CLASS_DEFINITION");
    case JVMTI_ERROR_FAILS_VERIFICATION:
        return ("JVMTI_ERROR_FAILS_VERIFICATION");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED");
    case JVMTI_ERROR_INVALID_TYPESTATE:
        return ("JVMTI_ERROR_INVALID_TYPESTATE");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED");
    case JVMTI_ERROR_UNSUPPORTED_VERSION:
        return ("JVMTI_ERROR_UNSUPPORTED_VERSION");
    case JVMTI_ERROR_NAMES_DONT_MATCH:
        return ("JVMTI_ERROR_NAMES_DONT_MATCH");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED");
    case JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED:
        return ("JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED");
    case JVMTI_ERROR_UNMODIFIABLE_CLASS:
        return ("JVMTI_ERROR_UNMODIFIABLE_CLASS");
    case JVMTI_ERROR_NOT_AVAILABLE:
        return ("JVMTI_ERROR_NOT_AVAILABLE");
    case JVMTI_ERROR_MUST_POSSESS_CAPABILITY:
        return ("JVMTI_ERROR_MUST_POSSESS_CAPABILITY");
    case JVMTI_ERROR_NULL_POINTER:
        return ("JVMTI_ERROR_NULL_POINTER");
    case JVMTI_ERROR_ABSENT_INFORMATION:
        return ("JVMTI_ERROR_ABSENT_INFORMATION");
    case JVMTI_ERROR_INVALID_EVENT_TYPE:
        return ("JVMTI_ERROR_INVALID_EVENT_TYPE");
    case JVMTI_ERROR_ILLEGAL_ARGUMENT:
        return ("JVMTI_ERROR_ILLEGAL_ARGUMENT");
    case JVMTI_ERROR_NATIVE_METHOD:
        return ("JVMTI_ERROR_NATIVE_METHOD");
    case JVMTI_ERROR_OUT_OF_MEMORY:
        return ("JVMTI_ERROR_OUT_OF_MEMORY");
    case JVMTI_ERROR_ACCESS_DENIED:
        return ("JVMTI_ERROR_ACCESS_DENIED");
    case JVMTI_ERROR_WRONG_PHASE:
        return ("JVMTI_ERROR_WRONG_PHASE");
    case JVMTI_ERROR_INTERNAL:
        return ("JVMTI_ERROR_INTERNAL");
    case JVMTI_ERROR_UNATTACHED_THREAD:
        return ("JVMTI_ERROR_UNATTACHED_THREAD");
    case JVMTI_ERROR_INVALID_ENVIRONMENT:
        return ("JVMTI_ERROR_INVALID_ENVIRONMENT");
    default:
        return ("<unknown error>");
    }
}

const char* TranslatePhase(jvmtiPhase phase) {
    switch (phase) {
    case JVMTI_PHASE_ONLOAD:
        return ("JVMTI_PHASE_ONLOAD");
    case JVMTI_PHASE_PRIMORDIAL:
        return ("JVMTI_PHASE_PRIMORDIAL");
    case JVMTI_PHASE_START:
        return ("JVMTI_PHASE_START");
    case JVMTI_PHASE_LIVE:
        return ("JVMTI_PHASE_LIVE");
    case JVMTI_PHASE_DEAD:
        return ("JVMTI_PHASE_DEAD");
    default:
        return ("<unknown phase>");
    }
}

const char* TranslateRootKind(jvmtiHeapRootKind root) {
    switch (root) {
    case JVMTI_HEAP_ROOT_JNI_GLOBAL:
        return ("JVMTI_HEAP_ROOT_JNI_GLOBAL");
    case JVMTI_HEAP_ROOT_JNI_LOCAL:
        return ("JVMTI_HEAP_ROOT_JNI_LOCAL");
    case JVMTI_HEAP_ROOT_SYSTEM_CLASS:
        return ("JVMTI_HEAP_ROOT_SYSTEM_CLASS");
    case JVMTI_HEAP_ROOT_MONITOR:
        return ("JVMTI_HEAP_ROOT_MONITOR");
    case JVMTI_HEAP_ROOT_STACK_LOCAL:
        return ("JVMTI_HEAP_ROOT_STACK_LOCAL");
    case JVMTI_HEAP_ROOT_THREAD:
        return ("JVMTI_HEAP_ROOT_THREAD");
    case JVMTI_HEAP_ROOT_OTHER:
        return ("JVMTI_HEAP_ROOT_OTHER");
    default:
        return ("<unknown root kind>");
    }
}

const char* TranslateObjectRefKind(jvmtiObjectReferenceKind ref) {
    switch (ref) {
    case JVMTI_REFERENCE_CLASS:
        return ("JVMTI_REFERENCE_CLASS");
    case JVMTI_REFERENCE_FIELD:
        return ("JVMTI_REFERENCE_FIELD");
    case JVMTI_REFERENCE_ARRAY_ELEMENT:
        return ("JVMTI_REFERENCE_ARRAY_ELEMENT");
    case JVMTI_REFERENCE_CLASS_LOADER:
        return ("JVMTI_REFERENCE_CLASS_LOADER");
    case JVMTI_REFERENCE_SIGNERS:
        return ("JVMTI_REFERENCE_SIGNERS");
    case JVMTI_REFERENCE_PROTECTION_DOMAIN:
        return ("JVMTI_REFERENCE_PROTECTION_DOMAIN");
    case JVMTI_REFERENCE_INTERFACE:
        return ("JVMTI_REFERENCE_INTERFACE");
    case JVMTI_REFERENCE_STATIC_FIELD:
        return ("JVMTI_REFERENCE_STATIC_FIELD");
    case JVMTI_REFERENCE_CONSTANT_POOL:
        return ("JVMTI_REFERENCE_CONSTANT_POOL");
    default:
        return ("<unknown reference kind>");
    }
}

}

#endif

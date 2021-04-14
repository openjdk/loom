/*
 * Copyright (c) 2003, 2020, Oracle and/or its affiliates. All rights reserved.
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jvmti.h"
#include "jvmti_common.h"

extern "C" {

typedef struct {
  int cnt;
  const char **thr_names;
} info;

static jvmtiEnv *jvmti_env;
static jrawMonitorID lock1;
static jrawMonitorID lock2;
static int system_threads_count;
static const char *names0[] = {"main"};
static const char *names1[] = {"main", "thread1"};
static const char *names2[] = {"main", "Thread-"};
static info thrInfo[] = {
    {1, names0}, {1, names0}, {2, names1}, {1, names0}, {2, names2}
};

const int IDX_AGENT_THREAD = 4;

jthread create_jthread(JNIEnv *jni) {
  jclass thrClass = jni->FindClass("java/lang/Thread");
  jmethodID cid = jni->GetMethodID(thrClass, "<init>", "()V");
  return jni->NewObject(thrClass, cid);
}

static void JNICALL
sys_thread(jvmtiEnv *jvmti, JNIEnv *jni, void *p) {
  RawMonitorLocker rml2 = RawMonitorLocker(jvmti, jni, lock2);
  {
    RawMonitorLocker rml1 = RawMonitorLocker(jvmti, jni, lock1);
    rml1.notify();
  }
  rml2.wait();
}

jint Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jint res;

  res = jvm->GetEnv((void **) &jvmti_env, JVMTI_VERSION_1_1);
  if (res != JNI_OK || jvmti_env == NULL) {
    printf("Wrong result of a valid call to GetEnv !\n");
    return JNI_ERR;
  }
  lock1 = create_raw_monitor(jvmti_env, "_lock1");
  lock2 = create_raw_monitor(jvmti_env, "_lock2");

  return JNI_OK;
}

JNIEXPORT jboolean check_info(JNIEnv *jni, int ind) {
  jboolean result = JNI_TRUE;
  jint threadsCount = -1;
  jthread *threads;
  int num_unexpected = 0;

  printf(" >>> Check: %d\n", ind);

  if (ind == IDX_AGENT_THREAD) {
    RawMonitorLocker rml1 = RawMonitorLocker(jvmti_env, jni, lock1);
    jvmtiError err = jvmti_env->RunAgentThread(create_jthread(jni), sys_thread, NULL,JVMTI_THREAD_NORM_PRIORITY);
    check_jvmti_status(jni, err, "Failed to run AgentThread");
    rml1.wait();
  }

  check_jvmti_status(jni, jvmti_env->GetAllThreads(&threadsCount, &threads), "Failed in GetAllThreads");

  for (int i = 0; i < threadsCount; i++) {
    if (!isThreadExpected(jvmti_env, threads[i])) {
      num_unexpected++;
    }
  }

  if (threadsCount - num_unexpected != thrInfo[ind].cnt + system_threads_count) {
    printf("Point %d: number of threads expected: %d, got: %d\n",
           ind, thrInfo[ind].cnt + system_threads_count, threadsCount - num_unexpected);
    return JNI_FALSE;
  }

  for (int i = 0; i < thrInfo[ind].cnt; i++) {
    bool found = false;
    for (int j = 0; j < threadsCount && !found; j++) {
      char *name = get_thread_name(jvmti_env, jni, threads[j]);
      printf(" >>> %s\n", name);

      found = (name != NULL &&
          strstr(name, thrInfo[ind].thr_names[i]) == name &&
          (ind == IDX_AGENT_THREAD || strlen(name) ==
              strlen(thrInfo[ind].thr_names[i])));
    }

    printf("\n");
    if (!found) {
      printf("Point %d: thread %s not detected\n",
             ind, thrInfo[ind].thr_names[i]);
      result = JNI_FALSE;
    }
  }

  deallocate(jvmti_env, jni, threads);

  if (ind == IDX_AGENT_THREAD) {
    RawMonitorLocker rml2 = RawMonitorLocker(jvmti_env, jni, lock2);
    rml2.notify();
  }
  return result;
}

JNIEXPORT void JNICALL Java_allthr01_setSysCnt(JNIEnv *env, jclass cls) {
  jint threadsCount = -1;
  jthread *threads;

  check_jvmti_status(env, jvmti_env->GetAllThreads(&threadsCount, &threads), "Failed in GetAllThreads");

  system_threads_count = threadsCount - 1;

  for (int i = 0; i < threadsCount; i++) {
    if (!isThreadExpected(jvmti_env, threads[i])) {
      system_threads_count--;
    }
  }

  printf(" >>> number of system threads: %d\n", system_threads_count);

}

JNIEXPORT jboolean JNICALL
Java_allthr01_checkInfo0(JNIEnv *env, jclass cls, jint ind) {
  return check_info(env, ind);
}

}

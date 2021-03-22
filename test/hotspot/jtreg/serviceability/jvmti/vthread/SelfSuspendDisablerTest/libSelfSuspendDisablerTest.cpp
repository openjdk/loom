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

static jvmtiEnv *jvmti_env = NULL;

extern "C" {

JNIEXPORT void JNICALL
Java_SelfSuspendDisablerTest_resume(JNIEnv* jni, jclass cls,jthread thread) {
  check_jvmti_status(jni, jvmti_env->ResumeThread(thread), "Error in ResumeThread");
}

JNIEXPORT void JNICALL
Java_SelfSuspendDisablerTest_selfSuspend(JNIEnv* jni, jclass cls) {
  jthread thread;
  check_jvmti_status(jni, jvmti_env->GetCurrentThread(&thread), "Error in CurrentThread");
  check_jvmti_status(jni, jvmti_env->SuspendThread(thread), "Error in SuspendThread");
}

JNIEXPORT jboolean JNICALL
Java_SelfSuspendDisablerTest_isSuspended(JNIEnv* jni, jclass cls, jthread thread) {
  jint state;
  check_jvmti_status(jni, jvmti_env->GetThreadState(thread, &state), "Error in GetThreadState");
  return (state & JVMTI_THREAD_STATE_SUSPENDED) != 0;
}

}


jint Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiError err;

  printf("Agent init started\n");

  if (jvm->GetEnv((void **)(&jvmti_env), JVMTI_VERSION) != JNI_OK) {
    printf("Agent init: error in getting JvmtiEnv with GetEnv\n");
    return JNI_ERR;
  }

  jvmtiCapabilities caps;
  memset(&caps, 0, sizeof(caps));
  caps.can_suspend = 1;

  err = jvmti_env->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    return JNI_ERR;
  }

  return JNI_OK;
}
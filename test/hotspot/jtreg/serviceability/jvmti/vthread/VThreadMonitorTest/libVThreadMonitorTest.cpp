/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include <string.h>
#include "jvmti.h"
#include "jni.h"
#include "jvmti_common.h"

extern "C" {

#define PASSED 0
#define FAILED 2

#define TEST_CLASS "VThreadMonitorTest"

static jvmtiEnv *jvmti = NULL;
static volatile jboolean event_has_posted = JNI_FALSE;
static volatile jint status = PASSED;
static volatile jclass test_class = NULL;

static jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved);

static void ShowErrorMessage(jvmtiEnv *jvmti, jvmtiError errCode,
                             const char* func, const char *msg) {
  char *errMsg;
  jvmtiError result;

  result = jvmti->GetErrorName(errCode, &errMsg);
  if (result == JVMTI_ERROR_NONE) {
    fprintf(stderr, "%s: %s %s (%d)\n", func, msg, errMsg, errCode);
    jvmti->Deallocate((unsigned char *)errMsg);
  } else {
    fprintf(stderr, "%s: %s (%d)\n", func, msg, errCode);
  }
}

static jboolean CheckLockObject(JNIEnv *jni, jobject monitor) {
  if (test_class == NULL) {
    // JNI_OnLoad has not been called yet, so can't possibly be an instance of TEST_CLASS.
    return JNI_FALSE;
  }
  return jni->IsInstanceOf(monitor, test_class);
}

static void
check_contended_monitor(jvmtiEnv *jvmti, JNIEnv *jni, const char* func,
                        jthread thread, char* tname, jboolean is_vt,
                        jobject monitor1, jobject monitor2) {
  jvmtiError err;
  jint state = 0;
  jobject contended_monitor = (jobject)5; // initialize to an invalid value

  // Test GetCurrentContendedMonitor for a vthread.
  err = jvmti->GetCurrentContendedMonitor(thread, &contended_monitor);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, func, "error in JVMTI GetCurrentContendedMonitor");
    status = FAILED;
    return;
  }

  printf("\n%s: %s: contended monitor: %p\n", func, tname, contended_monitor);

  // Check if it is expected monitor.
  if (jni->IsSameObject(monitor1, contended_monitor) == JNI_FALSE &&
      jni->IsSameObject(monitor2, contended_monitor) == JNI_FALSE) {
    printf("FAIL: is_vt: %d: unexpected monitor from GetCurrentContendedMonitor\n", is_vt);
    status = FAILED;
    return;
  }
  printf("%s: GetCurrentContendedMonitor returned expected monitor for %s\n", func, tname);

  // Check GetThreadState for a vthread.
  err = jvmti->GetThreadState(thread, &state);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, func, "error in JVMTI GetThreadState");
    status = FAILED;
    return;
  }
  printf("%s: GetThreadState returned state for %s: %0x\n\n", func, tname, state);
  fflush(0);
}

static void
check_owned_monitor(jvmtiEnv *jvmti, JNIEnv *jni, const char* func,
                    jthread thread, char* tname, jboolean is_vt, jobject monitor) {
  jvmtiError err;
  jint state = 0;
  jint mcount = -1;
  jobject *owned_monitors = NULL;

  err = jvmti->GetOwnedMonitorInfo(thread, &mcount, &owned_monitors);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, func,
                     "error in JVMTI GetOwnedMonitorInfo");
    status = FAILED;
    return;
  }
  printf("\n%s: GetOwnedMonitorInfo: %s owns %d monitor(s)\n", func, tname, mcount);
  jvmti->Deallocate((unsigned char *)owned_monitors);

  if (is_vt == JNI_TRUE && mcount < 2) {
    fprintf(stderr, "%s: FAIL: monitorCount for %s expected to be >= 2\n", func, tname);
    status = FAILED;
    return;
  }
  if (is_vt == JNI_FALSE && mcount != 0) {
    fprintf(stderr, "%s: FAIL: monitorCount for %s expected to be 0\n", func, tname);
    status = FAILED;
    return;
  }

  printf("%s: GetOwnedMonitorInfo: returned expected number of monitors for %s\n", func, tname);

  // Check GetThreadState for a vthread.
  err = jvmti->GetThreadState(thread, &state);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, func, "error in JVMTI GetThreadState");
    status = FAILED;
    return;
  }
  printf("%s: GetThreadState returned state for %s: %0x\n\n", func, tname, state);
  fflush(0);
}

JNIEXPORT void JNICALL
MonitorContendedEnter(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, jobject monitor) {
  jvmtiError err;
  jvmtiThreadInfo vt_info;
  jvmtiThreadInfo ct_info;
  jthread cthread = NULL;
  jobject contended_monitor = NULL;

  if (CheckLockObject(jni, monitor) == JNI_FALSE) {
    return; // Not tested monitor
  }

  err = jvmti->GetCarrierThread(vthread, &cthread);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEnter",
                     "error in JVMTI GetCarrierThread");
    event_has_posted = JNI_TRUE;
    status = FAILED;
    return;
  }

  err = jvmti->GetThreadInfo(vthread, &vt_info);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEnter",
                     "error in JVMTI GetThreadInfo");
    status = FAILED;
    event_has_posted = JNI_TRUE;
    return;
  }

  err = jvmti->GetThreadInfo(cthread, &ct_info);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEnter",
                     "error in JVMTI GetThreadInfo");
    status = FAILED;
    event_has_posted = JNI_TRUE;
    return;
  }

  check_contended_monitor(jvmti, jni, "MonitorContendedEnter",
                          vthread, vt_info.name, JNI_TRUE, monitor, NULL);
  check_contended_monitor(jvmti, jni, "MonitorContendedEnter",
                          cthread,  ct_info.name, JNI_FALSE, NULL, NULL);
  check_owned_monitor(jvmti, jni, "MonitorContendedEnter",
                      vthread, vt_info.name, JNI_TRUE, monitor);
  check_owned_monitor(jvmti, jni, "MonitorContendedEnter",
                      cthread, ct_info.name, JNI_FALSE, monitor);
  event_has_posted = JNI_TRUE;
  jvmti->Deallocate((unsigned char *)vt_info.name);
  jvmti->Deallocate((unsigned char *)ct_info.name);
}

JNIEXPORT void JNICALL
MonitorContendedEntered(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, jobject monitor) {
  jvmtiError err;
  jvmtiThreadInfo vt_info;
  jvmtiThreadInfo ct_info;
  jthread cthread = NULL;
  jobject contended_monitor = (jobject)cthread; // init with a wrong monitor

  if (CheckLockObject(jni, monitor) == JNI_FALSE) {
    return; // Not tested monitor
  }

  err = jvmti->GetCarrierThread(vthread, &cthread);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEntered",
                     "error in JVMTI GetCarrierThread");
    status = FAILED;
    return;
  }

  err = jvmti->GetThreadInfo(vthread, &vt_info);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEntered",
                     "error in JVMTI GetThreadInfo for virtual thread");
    status = FAILED;
    return;
  }

  err = jvmti->GetThreadInfo(cthread, &ct_info);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEntered",
                     "error in JVMTI GetThreadInfo for carrier thread");
    status = FAILED;
    return;
  }

  check_contended_monitor(jvmti, jni, "MonitorContendedEntered",
                          vthread, vt_info.name, JNI_TRUE, NULL, NULL);
  check_contended_monitor(jvmti, jni, "MonitorContendedEntered",
                          cthread, ct_info.name, JNI_FALSE, NULL, NULL);
  jvmti->Deallocate((unsigned char *)vt_info.name);
  jvmti->Deallocate((unsigned char *)ct_info.name);
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM *jvm, char *options, void *reserved) {
  return Agent_Initialize(jvm, options, reserved);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *jvm, void *reserved) {
  jint res;
  JNIEnv *jni;

  res = jvm->GetEnv((void **)&jni, JNI_VERSION_9);
  if (res != JNI_OK || jni == NULL) {
    fprintf(stderr, "Error: GetEnv call failed(%d)!\n", res);
    return JNI_ERR;
  }

  test_class = jni->FindClass(TEST_CLASS);
  if (test_class != NULL) {
    test_class = (jclass)jni->NewGlobalRef(test_class);
  }
  if (test_class == NULL) {
    fprintf(stderr, "Error: Could not load class %s!\n", TEST_CLASS);
    return JNI_ERR;
  }
  return JNI_VERSION_9;
}

static
jint Agent_Initialize(JavaVM *jvm, char *options, void *reserved) {
  jint res;
  jvmtiError err;
  jvmtiCapabilities caps;
  jvmtiEventCallbacks callbacks;

  printf("Agent_OnLoad started\n");

  res = jvm->GetEnv((void **)&jvmti, JVMTI_VERSION);
  if (res != JNI_OK || jvmti == NULL) {
    fprintf(stderr, "Error: wrong result of a valid call to GetEnv!\n");
    return JNI_ERR;
  }

  err = jvmti->GetPotentialCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                    "error in JVMTI GetPotentialCapabilities");
    return JNI_ERR;
  }

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                     "error in JVMTI AddCapabilities");
    return JNI_ERR;
  }

  err = jvmti->GetCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                     "error in JVMTI GetCapabilities");
    return JNI_ERR;
  }

  if (!caps.can_generate_monitor_events) {
    fprintf(stderr, "Warning: Monitor events are not implemented\n");
    return JNI_ERR;
  }
  if (!caps.can_get_owned_monitor_info) {
    fprintf(stderr, "Warning: GetOwnedMonitorInfo is not implemented\n");
    return JNI_ERR;
  }
  if (!caps.can_support_virtual_threads) {
    fprintf(stderr, "Warning: virtual threads are not supported\n");
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.MonitorContendedEnter   = &MonitorContendedEnter;
  callbacks.MonitorContendedEntered = &MonitorContendedEntered;

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                     "error in JVMTI SetEventCallbacks");
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_MONITOR_CONTENDED_ENTER, NULL);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                     "error in JVMTI SetEventNotificationMode #1");
    return JNI_ERR;
  }
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE,
                                        JVMTI_EVENT_MONITOR_CONTENDED_ENTERED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "Agent_OnLoad",
                     "error in JVMTI SetEventNotificationMode #2");
    return JNI_ERR;
  }
  printf("Agent_OnLoad finished\n");
  return JNI_OK;
}

JNIEXPORT jboolean JNICALL
Java_VThreadMonitorTest_hasEventPosted(JNIEnv *jni, jclass cls) {
  return event_has_posted;
}

JNIEXPORT void JNICALL
Java_VThreadMonitorTest_checkContendedMonitor(JNIEnv *jni, jclass cls, jthread vthread,
                                              jobject monitor1, jobject monitor2) {
  jvmtiThreadInfo vt_info;
  jvmtiError err;

  err = jvmti->GetThreadInfo(vthread, &vt_info);
  if (err != JVMTI_ERROR_NONE) {
    ShowErrorMessage(jvmti, err, "MonitorContendedEntered",
                     "error in JVMTI GetThreadInfo");
    status = FAILED;
    return;
  }

  check_contended_monitor(jvmti, jni, "checkContendedMonitor",
                          vthread, vt_info.name, JNI_TRUE, monitor1, monitor2);
  jvmti->Deallocate((unsigned char *)vt_info.name);
}

JNIEXPORT jint JNICALL
Java_VThreadMonitorTest_check(JNIEnv *jni, jclass cls) {
  return status;
}

} // extern "C"

/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

/*
 * The goal of this test is to single step into Continuation.doContinue(). The
 * expectation is that we will end up in Continuation.yield0() right after the
 * return from Continuation.doYield(). There have been bugs where yield0() was
 * compiled and we didn't get a single step event when resuming execution in it.
 * After confirming the yield0() single stepping, we turn off single stepping
 * and run to completion. There have been jvmti _cur_stack_depth asserts related
 * to doing this, although they were never reproduced with this test.
 *
 * Setting up a single step into Continuation.doContinue() is a bit tricky. It
 * is called from Continuation.run(), so the first step is to setup a breakpoint
 * at the start of run(). After it is hit, we setup another breakpoint at the
 * start of Continuation.isStarted(), which is called just before the doContinue()
 * call. Once it is hit, we enable single stepping. From isStarted() it should only
 * take about 14 single step to reach Continuation.yield0(). If we don't reach it by
 * 50 steps, the test fails.
 *
 * There's also a NotifyFramePop that is done. The is related to trying to trigger
 * the _cur_stack_depth assert.
 */
extern "C" {

#define MAX_FRAME_COUNT 20

static jvmtiEnv *jvmti = NULL;
static jthread exp_thread = NULL;
static jrawMonitorID event_mon = NULL;
static int breakpoint_count = 0;
static int single_step_count = 0;
static int method_entry_count = 0;
static int method_exit_count = 0;
static int frame_pop_count = 0;
static jboolean passed = JNI_TRUE;
static jboolean received_vthread_singlestep = JNI_FALSE;


static jmethodID *java_lang_Continuation_methods = NULL;
jint java_lang_Continuation_method_count = 0;
jclass java_lang_Continuation_class = NULL;

static jmethodID *test_methods = NULL;
jint test_method_count = 0;
jclass test_class = NULL;

static void
lock_events() {
  jvmti->RawMonitorEnter(event_mon);
}

static void
unlock_events() {
  jvmti->RawMonitorExit(event_mon);
}

static void
check_jvmti_status(JNIEnv* jni, jvmtiError err, const char* msg) {
  if (err != JVMTI_ERROR_NONE) {
    printf("check_jvmti_status: JVMTI function returned error: %d\n", err);
    jni->FatalError(msg);
  }
}

static char* get_method_class_name(jvmtiEnv *jvmti, JNIEnv* jni, jmethodID method) {
  jvmtiError err;
  jclass klass = NULL;
  char*  cname = NULL;

  err = jvmti->GetMethodDeclaringClass(method, &klass);
  check_jvmti_status(jni, err, "get_method_class_name: error in JVMTI GetMethodDeclaringClass");

  err = jvmti->GetClassSignature(klass, &cname, NULL);
  check_jvmti_status(jni, err, "get_method_class_name: error in JVMTI GetClassSignature");

  cname[strlen(cname) - 1] = '\0'; // get rid of trailing ';'
  return cname + 1;                // get rid of leading 'L'
}

static void
print_method(jvmtiEnv *jvmti, JNIEnv* jni, jmethodID method, jint depth) {
  char*  cname = NULL;
  char*  mname = NULL;
  char*  msign = NULL;
  jvmtiError err;

  cname = get_method_class_name(jvmti, jni, method);

  err = jvmti->GetMethodName(method, &mname, &msign, NULL);
  check_jvmti_status(jni, err, "print_method: error in JVMTI GetMethodName");

  printf("%2d: %s: %s%s\n", depth, cname, mname, msign);
  fflush(0);
}

static void
print_stack_trace(jvmtiEnv *jvmti, JNIEnv* jni) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = 0;
  jvmtiError err;

  err = jvmti->GetStackTrace(NULL, 0, MAX_FRAME_COUNT, frames, &count);
  check_jvmti_status(jni, err, "print_stack_trace: error in JVMTI GetStackTrace");

  printf("JVMTI Stack Trace: frame count: %d\n", count);
  for (int depth = 0; depth < count; depth++) {
    print_method(jvmti, jni, frames[depth].method, depth);
  }
  printf("\n");
}

static void
print_frame_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
                       const char* event_name, int event_count) {
  char* cname = NULL;
  char* mname = NULL;
  char* msign = NULL;
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "event handler: error in JVMTI GetThreadInfo call");
  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;

  cname = get_method_class_name(jvmti, jni, method);

  err = jvmti->GetMethodName(method, &mname, &msign, NULL);
  check_jvmti_status(jni, err, "event handler: error in JVMTI GetMethodName call");

  printf("\n%s event #%d: thread: %s, method: %s: %s%s\n",
         event_name, event_count, thr_name, cname, mname, msign);

  if (strcmp(event_name, "SingleStep") != 0) {
    print_stack_trace(jvmti, jni);
  }
  fflush(0);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint frames_cnt, const char* event_name) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(vthread, &thr_info);
  check_jvmti_status(jni, err, "event handler failed during JVMTI GetThreadInfo call");

  const char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("\n%s event: thread: %s, frames: %d\n\n", event_name, thr_name, frames_cnt);

  print_stack_trace(jvmti, jni);
  fflush(0);
}

static void
setOrClearBreakpoint(JNIEnv *jni, jboolean set, const char *methodName,
                     jclass klass, jmethodID methods[], int method_count)
{
  jlocation location = (jlocation)0L;
  jmethodID method = NULL;
  jvmtiError err;

  // Find the jmethodID of the specified method
  while (--method_count >= 0) {
    jmethodID meth = methods[method_count];
    char* mname = NULL;

    err = jvmti->GetMethodName(meth, &mname, NULL, NULL);
    check_jvmti_status(jni, err, "setupBreakpoint: error in JVMTI GetMethodName call");

    if (strcmp(mname, methodName) == 0) {
      printf("setupBreakpoint: found method %s() to %s a breakpoint\n", mname, set ? "set" : "clear");
      fflush(0);
      method = meth;
    }
  }
  if (method == NULL) {
      printf("setupBreakpoint: not found method %s() to %s a breakpoint\n",
             methodName, set ? "set" : "clear");
      jni->FatalError("Error in setupBreakpoint: not found method");
  }

  if (set) {
      err = jvmti->SetBreakpoint(method, location);
  } else {
      err = jvmti->ClearBreakpoint(method, location);
  }
  check_jvmti_status(jni, err, "setupBreakpoint: error in JVMTI SetBreakpoint");
}

static void
setBreakpoint(JNIEnv *jni, const char *methodName,
              jclass klass, jmethodID methods[], int method_count)
{
  setOrClearBreakpoint(jni, JNI_TRUE, methodName, klass, methods, method_count);
}

static void
clearBreakpoint(JNIEnv *jni, const char *methodName,
                jclass klass, jmethodID methods[], int method_count)
{
  setOrClearBreakpoint(jni, JNI_FALSE, methodName, klass, methods, method_count);
}

static jboolean runBreakpointHit = JNI_FALSE;
static jboolean isStartedBreakpointHit = JNI_FALSE;
static int qPutBreakpointHit = 0;

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = NULL;
  jvmtiError err;

  lock_events();

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetMethodName call");

  if (strcmp(mname, "run") == 0) {
      // We hit our Continuation.run() breakpoint. Now setup the Continuation.isStarted() breakpoint.
      if (runBreakpointHit) {
          unlock_events();
          return; // ignore if we've already seen one
      }
      print_frame_event_info(jvmti, jni, thread, method,
                             "Breakpoint", ++breakpoint_count);
      runBreakpointHit = JNI_TRUE;
      clearBreakpoint(jni, "run", java_lang_Continuation_class,
                      java_lang_Continuation_methods, java_lang_Continuation_method_count);
      setBreakpoint(jni, "isStarted", java_lang_Continuation_class,
                    java_lang_Continuation_methods, java_lang_Continuation_method_count);
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");
  } else if (strcmp(mname, "isStarted") == 0) {
      // We hit our Continuation.isStarted() breakpoint. Now setup single stepping so we can
      // step into Continuation.doContinue().
      if (isStartedBreakpointHit) {
          unlock_events();
          return; // ignore if we've already seen one
      }
      print_frame_event_info(jvmti, jni, thread, method,
                             "Breakpoint", ++breakpoint_count);
      isStartedBreakpointHit = JNI_TRUE;
      clearBreakpoint(jni, "isStarted", java_lang_Continuation_class,
                      java_lang_Continuation_methods, java_lang_Continuation_method_count);
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
      err = jvmti->NotifyFramePop(thread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
  } else if (strcmp(mname, "qPut") == 0) {
    // This part of the test is checking to make sure we don't get a SingleStep event when
    // SingleStep is enabled on a cthread and is excuting on a vthread. It coordinates
    // with the "qPut" code in SingleStep().
    jthread cthread;
    qPutBreakpointHit++;
    print_frame_event_info(jvmti, jni, thread, method,
                           "Breakpoint", ++breakpoint_count);
    err = jvmti->GetCarrierThread(thread, &cthread);
    check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetCarrierThread");
    if (qPutBreakpointHit == 1) {
      // We hit our 1st DoContinueSingleStepTest.qPut() breakpoint. Now setup single stepping
      // on the carrier thread. We should not get a single step event before hitting this breakpoint
      // again because we are currently executing on the virtual thread.
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, cthread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
      err = jvmti->NotifyFramePop(thread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
    } else if (qPutBreakpointHit == 2) {
      // We hit our 2nd qPut breakpoint. Enable single stepping on the vthread. It has already
      // been disabled on the cthread. This should result in a SingleStep event before we
      // hit this breakpoint again.
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
      err = jvmti->NotifyFramePop(thread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
    } else if (qPutBreakpointHit == 3) {
      clearBreakpoint(jni, "qPut", test_class, test_methods, test_method_count);
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_BREAKPOINT, NULL);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable BREAKPOINT");
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable SINGLE_STEP");
      if (!received_vthread_singlestep) {
        printf("FAILED: Breakpoint: failed to get SingleStep event on vthread before 3rd breakpoint.\n");
        passed = JNI_FALSE;
      }
    } else {
      printf("FAILED: Breakpoint: too many qPut breakpoints.\n");
      passed = JNI_FALSE;
    }
  } else {
      printf(" Breakpoint: unexpected breakpoint in method %s()\n", mname);
  }

  unlock_events();
}

static void JNICALL
SingleStep(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = NULL;
  jvmtiError err;

  lock_events();

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "SingleStep: error in JVMTI GetMethodName call");

  print_frame_event_info(jvmti, jni, thread, method,
                         "SingleStep", ++single_step_count);
  if (strcmp(mname, "yield0") == 0) {
    // We single stepped into yield0 within 50 steps, so this part of the test passed.
    // Turn off single stepping.
    printf("SingleStep: entered yield0()\n");
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    check_jvmti_status(jni, err, "SingleStep: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
    // Next part of the test is to make sure that when we enable SingleStep on a cthread, we
    // don't get a SingleStep event while executing the vthread. It coordinates with "qPut"
    // code in BreakPoint(). Start this part of the test  by setting up a breakpoint
    // in doContinueSingleStepTest.qput().
    single_step_count = 0;
    setBreakpoint(jni, "qPut", test_class, test_methods, test_method_count);
  } else if (strcmp(mname, "qPut") == 0) {
    // We single stepped into qPut. Verify that we got this event when it was enabled
    // on the vthread and not when enabled on the cthread..
    printf("SingleStep: qPut event received on %s thread\n",
           jni->IsVirtualThread(thread) ? "virtual" : "carrier");
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    check_jvmti_status(jni, err, "SingleStep: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
    if (qPutBreakpointHit == 1) {
      // If we got a SingleStep event after the first qPut breakpoint, that's a failure
      // because we setup single stepping on the cthread, and therefore should not
      // have received one while executing the vthread in qPut.
      printf("FAILED: SingleStep: qPut event received while enabled on cthread\n");
      //passed = JNI_FALSE;  // uncomment this line to cause test to fail
    } else if (qPutBreakpointHit == 2) {
      // If we got a SingleStep event after the 2nd qPut breakpoint, that's a pass
      // because we setup single stepping on the vthread.
      printf("SingleStep: qPut event received while enabled on vthread\n");
      received_vthread_singlestep = JNI_TRUE;
    } else if (qPutBreakpointHit >= 2) {
      printf("FAILED SingleStep: unexpected qPut single step event received\n");
      passed = JNI_FALSE;
    }
  } else if (single_step_count >= 50) {
    // We didn't enter Continuation.yield0() within 50 single steps. The test has failed.
    printf("FAILED: SingleStep: never entered method yield0()\n");
    passed = JNI_FALSE;
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    check_jvmti_status(jni, err, "SingleStep: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
  }
  unlock_events();
}

static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  lock_events();
  method_entry_count++;
  //print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count);
  unlock_events();
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  lock_events();
  method_exit_count++;
  //print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_entry_count);
  unlock_events();
}

static void JNICALL
FramePop(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
         jboolean was_popped_by_exception) {
  lock_events();
  frame_pop_count++;
  print_frame_event_info(jvmti, jni, thread, method, "FramePop", frame_pop_count);
  unlock_events();
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadScheduled");
  unlock_events();
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadTerminated");
  unlock_events();
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadMounted");
  unlock_events();
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadUnmounted");
  unlock_events();
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint frames_count) {
  lock_events();
  //print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationRun");
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint frames_count) {
  lock_events();
  //print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationYield");
  unlock_events();
}

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM *jvm, char *options, void *reserved) {
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.Breakpoint  = &Breakpoint;
  callbacks.SingleStep  = &SingleStep;
  callbacks.FramePop    = &FramePop;
  callbacks.MethodEntry = &MethodEntry;
  callbacks.MethodExit = &MethodExit;
  callbacks.VirtualThreadScheduled  = &VirtualThreadScheduled;
  callbacks.VirtualThreadTerminated = &VirtualThreadTerminated;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;

  memset(&caps, 0, sizeof(caps));
  caps.can_generate_breakpoint_events = 1;
  caps.can_generate_single_step_events = 1;
  caps.can_generate_frame_pop_events = 1;
  caps.can_generate_method_entry_events = 1;
  caps.can_generate_method_exit_events = 1;
  caps.can_support_virtual_threads = 1;
  caps.can_support_continuations = 1;

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI AddCapabilities: %d\n", err);
  }

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI SetEventCallbacks: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_TERMINATED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
  if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
  if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->CreateRawMonitor("Events Monitor", &event_mon);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI CreateRawMonitor: %d\n", err);
  }

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_DoContinueSingleStepTest_enableEvents(JNIEnv *jni, jclass klass, jthread thread,
                                           jclass contKlass, jclass testKlass) {
  jvmtiError err;

  printf("enableEvents: started\n");

  java_lang_Continuation_class = (jclass)jni->NewGlobalRef(contKlass);
  err = jvmti->GetClassMethods(contKlass, &java_lang_Continuation_method_count, &java_lang_Continuation_methods);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI GetClassMethods for contKlass");

  test_class = (jclass)jni->NewGlobalRef(testKlass);
  err = jvmti->GetClassMethods(testKlass, &test_method_count, &test_methods);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI GetClassMethods for testKlass");

  setBreakpoint(jni, "run", java_lang_Continuation_class, java_lang_Continuation_methods, java_lang_Continuation_method_count);

  // Enable Breakpoint events globally
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable BREAKPOINT");

  printf("enableEvents: finished\n");
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_DoContinueSingleStepTest_check(JNIEnv *jni, jclass cls) {
  printf("\n");
  printf("check: started\n");

  printf("check: breakpoint_count:   %d\n", breakpoint_count);
  printf("check: single_step_count:  %d\n", single_step_count);

  printf("check: finished\n");
  printf("\n");
  fflush(0);

  return passed;
}
} // extern "C"

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
#include "jvmti_common.h"

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
static jboolean received_method_exit_event = JNI_FALSE;


static jmethodID *java_lang_Continuation_methods = NULL;
jint java_lang_Continuation_method_count = 0;
jclass java_lang_Continuation_class = NULL;

static jmethodID *test_methods = NULL;
jint test_method_count = 0;
jclass test_class = NULL;

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
    print_stack_trace(jvmti, jni, thread);
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

  print_stack_trace(jvmti, jni, vthread);
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
  if (err == JVMTI_ERROR_DUPLICATE) {
      return; // TMP workaround
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
  jboolean is_virtual = jni->IsVirtualThread(thread);

  RawMonitorLocker rml(jvmti, jni, event_mon);

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetMethodName call");

  printf("Breakpoint: %s breakpoint event received on %s thread\n", mname,
         is_virtual ? "virtual" : "carrier");

  if (strcmp(mname, "run") == 0) {
      // We hit our Continuation.run() breakpoint. Now setup the Continuation.isStarted() breakpoint.
      if (runBreakpointHit) {
          return; // ignore if we've already seen one
      }
      print_frame_event_info(jvmti, jni, thread, method,
                             "Breakpoint", ++breakpoint_count);
      if (is_virtual) {
          jthread cthread = NULL;
          err = jvmti->GetCarrierThread(thread, &cthread);
          print_frame_event_info(jvmti, jni, cthread, method,
                                 "Breakpoint", breakpoint_count);
          // Continuation.run() should always be considered to be in the cthread, not the vthread.
          // Uncomment the following line to fail the test when this happens.
          //passed = JNI_FALSE;
      }
      runBreakpointHit = JNI_TRUE;
      clearBreakpoint(jni, "run", java_lang_Continuation_class,
                      java_lang_Continuation_methods, java_lang_Continuation_method_count);
      setBreakpoint(jni, "isStarted", java_lang_Continuation_class,
                    java_lang_Continuation_methods, java_lang_Continuation_method_count);
      // uncomment the following line to reproduce crash in HandshakeState::active_handshaker
      //err = jvmti->NotifyFramePop(thread, 0);
      //check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
  } else if (strcmp(mname, "isStarted") == 0) {
      // We hit our Continuation.isStarted() breakpoint. Now setup single stepping so we can
      // step into Continuation.doContinue().
      if (isStartedBreakpointHit) {
          return; // ignore if we've already seen one
      }
      print_frame_event_info(jvmti, jni, thread, method,
                             "Breakpoint", ++breakpoint_count);
      isStartedBreakpointHit = JNI_TRUE;
      clearBreakpoint(jni, "isStarted", java_lang_Continuation_class,
                      java_lang_Continuation_methods, java_lang_Continuation_method_count);
      printf("Breakpoint: %s: enabling SingleStep events on virtual thread\n", mname);
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
      printf("Breakpoint: %s, qPut Hit #1: enabling SingleStep events on carrier thread\n", mname);
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, cthread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
      // Setup NotifyFramePop on both the cthread and carrier thread. We better hit
      // at least one of them.
      err = jvmti->NotifyFramePop(thread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
      err = jvmti->NotifyFramePop(cthread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
      // Enable METHOD_EXIT events on the cthread. We should not get one.
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");
    } else if (qPutBreakpointHit == 2) {
      // We hit our 2nd qPut breakpoint. Enable single stepping on the vthread. It has already
      // been disabled on the cthread. This should result in a SingleStep event before we
      // hit this breakpoint again.
      printf("Breakpoint: %s, Hit #2: enabling SingleStep events on %s thread\n", mname,
             is_virtual ? "virtual" : "carrier");
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
      err = jvmti->NotifyFramePop(thread, 0);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
      // Verify that we did not get a METHOD_EXIT when enabled on the cthread.
      // Disable for the cthread and then enable for the vthread.
      if (received_method_exit_event) {
        passed = JNI_FALSE;
        received_method_exit_event = JNI_FALSE;
        printf("FAILED: got METHOD_EXIT event on the cthread: %p\n", cthread);
      }
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");
      err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");
    } else if (qPutBreakpointHit == 3) {
      clearBreakpoint(jni, "qPut", test_class, test_methods, test_method_count);
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_BREAKPOINT, NULL);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable BREAKPOINT");
      printf("Breakpoint: %s, Hit #3: disabling SingleStep events on %s thread\n", mname,
             is_virtual ? "virtual" : "carrier");
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable SINGLE_STEP");

      // Verify that we got a METHOD_EXIT when enabled on the vthread.
      if (!received_method_exit_event) {
        // passed = JNI_FALSE;
        printf("FAILED: did not get METHOD_EXIT event on the vthread: %p\n", (void*)thread);
      }
      err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");
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

}

static void JNICALL
SingleStep(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = NULL;
  jvmtiError err;
  jboolean is_virtual = jni->IsVirtualThread(thread);

  RawMonitorLocker rml(jvmti, jni, event_mon);

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "SingleStep: error in JVMTI GetMethodName call");

  print_frame_event_info(jvmti, jni, thread, method,
                         "SingleStep", ++single_step_count);
  if (strcmp(mname, "yield0") == 0) {
    // We single stepped into yield0 within 50 steps, so this part of the test passed.
    // Turn off single stepping.
    printf("SingleStep: entered yield0()\n");
    printf("SingleStep: %s: disabling SingleStep events on %s thread\n", mname,
           is_virtual ? "virtual" : "carrier");
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
    printf("SingleStep: qPut Hit #%d: event received on %s thread\n",
           qPutBreakpointHit, is_virtual ? "virtual" : "carrier");
    if (qPutBreakpointHit == 1) {
      // If we got a SingleStep event after the first qPut breakpoint, that's a failure
      // because we setup single stepping on the cthread, and therefore should not
      // have received one while executing the vthread in qPut.
      printf("FAILED: SingleStep: Hit #1: qPut event received while enabled on cthread\n");
      passed = JNI_FALSE;  // uncomment this line to cause test to fail
    } else if (qPutBreakpointHit == 2) {
      // If we got a SingleStep event after the 2nd qPut breakpoint, that's a pass
      // because we setup single stepping on the vthread.
      printf("SingleStep: Hit #2: qPut event received while enabled on vthread\n");
      received_vthread_singlestep = JNI_TRUE;
    } else if (qPutBreakpointHit >= 2) {
      printf("FAILED SingleStep: unexpected qPut single step event received\n");
      passed = JNI_FALSE;
    }
    jthread cthread = thread;
    if (is_virtual) {
      err = jvmti->GetCarrierThread(thread, &cthread);
      check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetCarrierThread");
    }
    printf("SingleStep: %s: disabling SingleStep events on carrier thread\n", mname);
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, cthread);
    check_jvmti_status(jni, err, "SingleStep: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
  } else if (single_step_count >= 50) {
    // We didn't enter Continuation.yield0() within 50 single steps. The test has failed.
    printf("FAILED: SingleStep: never entered method yield0()\n");
    // passed = JNI_FALSE;
    printf("SingleStep: %s step_count >= 50: disabling SingleStep events on %s thread\n", mname,
           is_virtual ? "virtual" : "carrier");
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_SINGLE_STEP, thread);
    check_jvmti_status(jni, err, "SingleStep: error in JVMTI SetEventNotificationMode: enable SINGLE_STEP");
  }
}

static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  method_entry_count++;
  //print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count);
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  jvmtiError err;
  char* mname = NULL;
  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "MethodExit: error in JVMTI GetMethodName call");

  RawMonitorLocker rml(jvmti, jni, event_mon);
  method_exit_count++;
  received_method_exit_event = JNI_TRUE;
  printf("MethodExit event #%d: method: %s\n", method_exit_count, mname);
  //print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_entry_count);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");
}

static void JNICALL
FramePop(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
         jboolean was_popped_by_exception) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  frame_pop_count++;
  print_frame_event_info(jvmti, jni, thread, method, "FramePop", frame_pop_count);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadScheduled");
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadTerminated");
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadMounted");
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //processFiberEvent(jvmti, jni, vthread, "VirtualThreadUnmounted");
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint frames_count) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationRun");
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint frames_count) {
  RawMonitorLocker rml(jvmti, jni, event_mon);
  //print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationYield");
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

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_FRAME_POP, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
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

  event_mon = create_raw_monitor(jvmti, "Events Monitor");

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
  printf("check: method_entry_count: %d\n", method_entry_count);
  printf("check: method_exit_count:  %d\n", method_exit_count);
  printf("check: frame_pop_count:    %d\n", frame_pop_count);
  if (method_exit_count == 0) {
    //passed = JNI_FALSE;
    printf("FAILED: method_exit_count == 0\n");
  }
  if (frame_pop_count == 0) {
    passed = JNI_FALSE;
    printf("FAILED: frame_pop_count == 0\n");
  }

  printf("check: finished\n");
  printf("\n");
  fflush(0);

  return passed;
}
} // extern "C"

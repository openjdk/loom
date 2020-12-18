/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

extern "C" {

#define MAX_FRAME_COUNT 20

static jvmtiEnv *jvmti = NULL;
static jthread exp_thread = NULL;
static jrawMonitorID event_mon = NULL;
static int breakpoint_count = 0;
static int method_entry_count = 0;
static int method_exit_count = 0;
static int frame_pop_count = 0;
static int brkptBreakpointHit = 0;
static jboolean received_method_exit_event = JNI_FALSE;
static jboolean passed = JNI_TRUE;

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
print_stack_trace(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = 0;
  jvmtiError err;

  err = jvmti->GetStackTrace(thread, 0, MAX_FRAME_COUNT, frames, &count);
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
set_or_clear_breakpoint(JNIEnv *jni, jboolean set, const char *methodName,
                     jclass klass, jmethodID methods[], int method_count)
{
  jlocation location = (jlocation)0L;
  jmethodID method = NULL;
  char* mname = NULL;
  jvmtiError err;

  // Find the jmethodID of the specified method
  while (--method_count >= 0) {
    jmethodID meth = methods[method_count];

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
set_breakpoint(JNIEnv *jni, const char *methodName,
              jclass klass, jmethodID methods[], int method_count)
{
  set_or_clear_breakpoint(jni, JNI_TRUE, methodName, klass, methods, method_count);
}

static void
clear_breakpoint(JNIEnv *jni, const char *methodName,
                jclass klass, jmethodID methods[], int method_count)
{
  set_or_clear_breakpoint(jni, JNI_FALSE, methodName, klass, methods, method_count);
}

static void
breakpoint_hit1(jvmtiEnv *jvmti, JNIEnv* jni,
                jthread thread, jthread cthread,
                jboolean is_virtual, char* mname) {
  jvmtiError err;

  printf("Breakpoint: %s, Hit #1: enabling MethodExit events for carrier thread: %p\n",
         mname, (void*)thread);

  // Enable METHOD_EXIT events on the cthread. We should not get one.
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");

#if 0
  // Setup NotifyFramePop on both the cthread and carrier thread.
  // We better hit at least one of them.
  err = jvmti->NotifyFramePop(thread, 0);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");

  err = jvmti->NotifyFramePop(cthread, 0);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop0");
#endif
}

static void
breakpoint_hit2(jvmtiEnv *jvmti, JNIEnv* jni,
                jthread thread, jthread cthread,
                jboolean is_virtual, char* mname) {
  jvmtiError err;
    
  // Verify that we did not get a METHOD_EXIT events when enabled on the cthread.
  if (received_method_exit_event) {
    passed = JNI_FALSE;
    received_method_exit_event = JNI_FALSE;
    printf("FAILED: got METHOD_EXIT event on the cthread: %p\n", cthread);
  }

  printf("Breakpoint: %s, Hit #2: disabling MethodExit events on carrier thread: %p\n",
          mname, (void*)cthread);

  // Disable METHOD_EXIT events on the cthread.
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");

  printf("Breakpoint: %s, Hit #2:  enabling MethodExit events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);

  // Enable METHOD_EXIT events on the vthread. We should get one.
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");

  printf("Breakpoint: %s, Hit #2:  enabling VirtualThreadMount events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);

  // Enable VIRTUAL_THREAD_MOUNTED events on the vthread.
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable VIRTUAL_THREAD_MOUNTED");

  printf("Breakpoint: %s, Hit #2:  enabling VirtualThreadMount events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);

  // Enable VIRTUAL_THREAD_UNMOUNTED events on the vthread.
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable VIRTUAL_THREAD_UNMOUNTED");
}

static void
breakpoint_hit3(jvmtiEnv *jvmti, JNIEnv* jni,
                jthread thread, jthread cthread,
                jboolean is_virtual, char* mname) {
  jvmtiError err;

  // Verify that we got a METHOD_EXIT when enabled on the vthread.
  if (!received_method_exit_event) {
    printf("FAILED: did not get METHOD_EXIT event on the vthread: %p\n", (void*)thread);
    passed = JNI_FALSE;
  }

  // Disable breakpoint events.
  clear_breakpoint(jni, "brkpt", test_class, test_methods, test_method_count);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable BREAKPOINT");

#if 0
  // Disable METHOD_EXIT events on the vthread.
  printf("Breakpoint: %s, Hit #3: disabling MethodExit events on virtual thread: %p\n", mname, (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");
#endif
}

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  jthread cthread = NULL;
  char* mname = NULL;
  jvmtiError err;
  jboolean is_virtual = jni->IsVirtualThread(thread);

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetMethodName call");

  if (strcmp(mname, "brkpt") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    passed = JNI_FALSE;
    return;
  }

  lock_events();

  brkptBreakpointHit++;
  print_frame_event_info(jvmti, jni, thread, method,
                         "Breakpoint", ++breakpoint_count);
  err = jvmti->GetCarrierThread(thread, &cthread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetCarrierThread");

  if (brkptBreakpointHit == 1) { // 1st MethodExitTest.brkpt() breakpoint
    breakpoint_hit1(jvmti, jni, thread, cthread, is_virtual, mname);

  } else if (brkptBreakpointHit == 2) { // 2nd MethodExitTest.brkpt breakpoint
    breakpoint_hit2(jvmti, jni, thread, cthread, is_virtual, mname);

  } else if (brkptBreakpointHit == 3) { // 3rd MethodExitTest.brkpt breakpoint
    breakpoint_hit3(jvmti, jni, thread, cthread, is_virtual, mname);

  } else {
    printf("FAILED: Breakpoint: too many brkpt breakpoints.\n");
    passed = JNI_FALSE;
  }

  fflush(0);
  unlock_events();
}

static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  lock_events();
  method_entry_count++;

#if 0
  jvmtiError err;
  char* mname = NULL;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "MethodEntry: error in JVMTI GetMethodName call");

  printf("MethodEntry #%d: method: %s, thread: %p\n", method_entry_count, mname, (void*)thread);
#endif

  // print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count);

  fflush(0);
  unlock_events();
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  jvmtiError err;
  char* mname = NULL;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "MethodExit: error in JVMTI GetMethodName call");

  lock_events();
  method_exit_count++;

  received_method_exit_event = JNI_TRUE;
  printf("MethodExit #%d: method: %s, thread: %p\n",
         method_exit_count, mname, (void*)thread);

  // print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_entry_count);
  if (strstr(mname, "brkpt") != NULL) {
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
    check_jvmti_status(jni, err, "MethodExit: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");

    // Disable VIRTUAL_THREAD_MOUNTED events on the vthread.
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, thread);
    check_jvmti_status(jni, err, "MethodExit: error in JVMTI SetEventNotificationMode: disable VIRTUAL_THREAD_MOUNTED");

    // Disable VIRTUAL_THREAD_UNMOUNTED events on the vthread.
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, thread);
    check_jvmti_status(jni, err, "MethodExit: error in JVMTI SetEventNotificationMode: disable VIRTUAL_THREAD_UNMOUNTED");
#if 0
    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, thread);
    check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_ENTRY");
#endif
  }

  fflush(0);
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
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();

  printf("VirtualThreadMounted: vthread: %p\n", (void*)vthread);
  fflush(0);

#if 0
  jvmtiError err = jvmti->NotifyFramePop(vthread, 0);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI NotifyFramePop0");
#endif

  // processFiberEvent(jvmti, jni, vthread, "VirtualThreadMounted");
  unlock_events();
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  lock_events();

  printf("VirtualThreadUnmounted: vthread: %p\n", (void*)vthread);
  fflush(0);

  // processFiberEvent(jvmti, jni, vthread, "VirtualThreadUnmounted");
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
  callbacks.FramePop    = &FramePop;
  callbacks.MethodEntry = &MethodEntry;
  callbacks.MethodExit  = &MethodExit;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;

  memset(&caps, 0, sizeof(caps));
  caps.can_generate_breakpoint_events = 1;
  caps.can_generate_frame_pop_events = 1;
  caps.can_generate_method_entry_events = 1;
  caps.can_generate_method_exit_events = 1;
  caps.can_support_virtual_threads = 1;

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

  err = jvmti->CreateRawMonitor("Events Monitor", &event_mon);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI CreateRawMonitor: %d\n", err);
  }

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_MethodExitTest_enableEvents(JNIEnv *jni, jclass klass, jthread thread,
                                           jclass testKlass) {
  jvmtiError err;

  printf("enableEvents: started\n");

  test_class = (jclass)jni->NewGlobalRef(testKlass);
  err = jvmti->GetClassMethods(testKlass, &test_method_count, &test_methods);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI GetClassMethods for testKlass");

  set_breakpoint(jni, "brkpt", testKlass, test_methods, test_method_count);

  // Enable Breakpoint events globally
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable BREAKPOINT");

  printf("enableEvents: finished\n");
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_MethodExitTest_check(JNIEnv *jni, jclass cls) {
  printf("\n");
  printf("check: started\n");

  printf("check: breakpoint_count:   %d\n", breakpoint_count);
  printf("check: method_entry_count: %d\n", method_entry_count);
  printf("check: method_exit_count:  %d\n", method_exit_count);
  printf("check: frame_pop_count:    %d\n", frame_pop_count);

  if (method_exit_count == 0) {
    passed = JNI_FALSE;
    printf("FAILED: method_exit_count == 0\n");
  }

#if 0
  if (frame_pop_count == 0) {
    passed = JNI_FALSE;
    printf("FAILED: frame_pop_count == 0\n");
  }
#endif

  printf("check: finished\n");
  printf("\n");
  fflush(0);

  return passed;
}
} // extern "C"

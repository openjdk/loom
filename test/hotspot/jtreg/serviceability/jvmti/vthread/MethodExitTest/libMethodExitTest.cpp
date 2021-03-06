/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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

extern "C" {

static jvmtiEnv *jvmti = NULL;
static jthread exp_thread = NULL;
static jrawMonitorID event_mon = NULL;
static int vthread_mounted_count = 0;
static int vthread_unmounted_count = 0;
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
print_frame_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
                       const char* event_name, int event_count) {
  char* tname = get_thread_name(jvmti, jni, thread);
  char* cname = get_method_class_name(jvmti, jni, method);
  char* mname = get_method_name(jvmti, jni, method);

  printf("\n%s #%d: method: %s::%s, thread: %s\n",
         event_name, event_count, cname, mname, tname);

  if (strcmp(event_name, "SingleStep") != 0) {
    print_stack_trace(jvmti, jni, thread);
  }
  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)cname);
  deallocate(jvmti, jni, (void*)mname);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint frames_cnt, const char* event_name) {
  char* tname = get_thread_name(jvmti, jni, thread);

  printf("%s: thread: %s, frames: %d\n\n", event_name, tname, frames_cnt);

  print_stack_trace(jvmti, jni, thread);

  deallocate(jvmti, jni, (void*)tname);
}

static void
set_or_clear_breakpoint(JNIEnv *jni, jboolean set, const char *methodName,
                     jclass klass, jmethodID methods[], int method_count)
{
  jlocation location = (jlocation)0L;
  jmethodID method = NULL;
  jvmtiError err;

  // Find the jmethodID of the specified method
  while (--method_count >= 0) {
    jmethodID meth = methods[method_count];
    char* mname = get_method_name(jvmti, jni, meth);

    if (strcmp(mname, methodName) == 0) {
      // printf("setupBreakpoint: found method %s() to %s a breakpoint\n", mname, set ? "set" : "clear");
      method = meth;
    }
    deallocate(jvmti, jni, (void*)mname);
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
  fflush(0);
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

static long tls_data = 0;

static void
breakpoint_hit1(jvmtiEnv *jvmti, JNIEnv* jni,
                jthread thread, jthread cthread,
                jboolean is_virtual, char* mname) {
  char* tname = get_thread_name(jvmti, jni, thread);
  jthread vthread = NULL;
  jvmtiError err;

  // Test GetVirtualThread for carrier thread.
  printf("Hit #1: Breakpoint: %s: checking GetVirtualThread on carrier thread: %p, %s\n",
         mname, (void*)cthread, tname); fflush(0);
  err = jvmti->GetVirtualThread(cthread, &vthread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetVirtualThread");

  if (jni->IsSameObject(thread, vthread) != JNI_TRUE) {
    passed = JNI_FALSE;
    printf("FAILED: GetVirtualThread for carrier thread returned wrong vthread\n\n");
  } else {
    printf("GetVirtualThread for carrier thread %p returned expected virtual thread: %p\n\n",
           (void*)cthread, (void*)vthread);
  }

  // Test GetThreadLocalStorage for carrier thread.
  printf("Hit #1: Breakpoint: %s: checking GetThreadLocalStorage on carrier thread: %p\n",
         mname, (void*)cthread); fflush(0);
  err = jvmti->GetThreadLocalStorage(cthread, (void**)&tls_data);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetThreadLocalStorage");

  if (tls_data != 111) {
    passed = JNI_FALSE;
    printf("FAILED: GetThreadLocalStorage for carrier thread returned value: %d, expected 111\n\n", (int)tls_data);
  } else {
    printf("GetThreadLocalStorage for carrier thread returned value %d as expected\n\n", (int)tls_data);
  }
  {
    jmethodID method = NULL;
    jlocation loc = 0L;
    char* mname1 = NULL;
    char* cname1 = NULL;

    err = jvmti->GetFrameLocation(cthread, 0, &method, &loc);
    check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetFrameLocation");

    mname1 = get_method_name(jvmti, jni, method);
    cname1 = get_method_class_name(jvmti, jni, method);

    // Enable METHOD_EXIT events on the cthread. We should not get one.
    printf("Hit #1: Breakpoint: %s: enabling MethodExit events on carrier thread: %p\n",
           mname, (void*)cthread);
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
    check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");

    // Setup NotifyFramePop on the cthread.
    printf("Hit #1: Breakpoint: %s: enabling FramePop event for method: %s::%s on carrier thread: %p\n",
           mname, cname1, mname1, (void*)cthread);
    err = jvmti->NotifyFramePop(cthread, 0);
    check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop");

    // Print stack trace of cthread.
    printf("Hit #1: Breakpoint: %s: Stack Trace of carrier thread: %p\n",
           mname, (void*)cthread);
    print_stack_trace(jvmti, jni, cthread);
  }
  deallocate(jvmti, jni, (void*)tname);
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

  // Disable METHOD_EXIT events on the cthread.
  printf("Hit #2: Breakpoint: %s: disabling MethodExit events on carrier thread: %p\n",
          mname, (void*)cthread);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, cthread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");

  // Enable METHOD_EXIT events on the vthread. We should get one.
  printf("Hit #2: Breakpoint: %s: enabling MethodExit events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable METHOD_EXIT");

  // Enable VIRTUAL_THREAD_MOUNTED events on the vthread.
  printf("Hit #2: Breakpoint: %s: enabling VirtualThreadMounted events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable VIRTUAL_THREAD_MOUNTED");

  // Enable VIRTUAL_THREAD_UNMOUNTED events on the vthread.
  printf("Hit #2: Breakpoint: %s: enabling VirtualThreadUnmounted events on %s thread: %p\n",
          mname, is_virtual ? "virtual" : "carrier", (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: enable VIRTUAL_THREAD_UNMOUNTED");


  // Test GetThreadLocalStorage for virtual thread.
  printf("Hit #2: Breakpoint: %s: checking GetThreadLocalStorage on virtual thread: %p\n",
         mname, (void*)thread); fflush(0);
  err = jvmti->GetThreadLocalStorage(thread, (void**)&tls_data);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI GetThreadLocalStorage");

  if (tls_data != 222) {
    passed = JNI_FALSE;
    printf("FAILED: GetThreadLocalStorage for virtual thread returned value: %d, expected 222\n\n", (int)tls_data);
  } else {
    printf("GetThreadLocalStorage for virtual thread returned value %d as expected\n\n", (int)tls_data);
  }
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

  // Disable METHOD_EXIT events on the vthread.
  printf("Hit #3: Breakpoint: %s: disabling MethodExit events on virtual thread: %p\n", mname, (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");

  // Setup NotifyFramePop on the vthread.
  printf("Hit #3: Breakpoint: %s: enabling FramePop event for method: %s on virtual thread: %p\n",
         mname, mname, (void*)thread);
  err = jvmti->NotifyFramePop(thread, 0);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI NotifyFramePop");

  // Disable VIRTUAL_THREAD_MOUNTED events on the vthread.
  printf("Hit #3: Breakpoint: %s: disabling VirtualThreadMounted events on virtual thread: %p\n", mname, (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable VIRTUAL_THREAD_MOUNTED");

  // Disable VIRTUAL_THREAD_UNMOUNTED events on the vthread.
  printf("Hit #3: Breakpoint: %s: disabling VirtualThreadUnmounted events on virtual thread: %p\n", mname, (void*)thread);
  err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, thread);
  check_jvmti_status(jni, err, "Breakpoint: error in JVMTI SetEventNotificationMode: disable VIRTUAL_THREAD_UNMOUNTED");
}

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  jthread cthread = NULL;
  char* mname = get_method_name(jvmti, jni, method);
  jboolean is_virtual = jni->IsVirtualThread(thread);
  jvmtiError err;

  if (strcmp(mname, "brkpt") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    passed = JNI_FALSE;
    deallocate(jvmti, jni, (void*)mname);
    return;
  }

  RawMonitorLocker rml(jvmti, jni, event_mon);

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
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  char* mname = get_method_name(jvmti, jni, method);

  RawMonitorLocker rml(jvmti, jni, event_mon);
  method_entry_count++;

  printf("Hit #%d: MethodEntry #%d: method: %s, thread: %p\n",
         brkptBreakpointHit, method_entry_count,  mname, (void*)thread);

  // print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count);

  fflush(0);
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  char* mname = get_method_name(jvmti, jni, method);
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, event_mon);
  method_exit_count++;

  if (brkptBreakpointHit == 1) {
    received_method_exit_event = JNI_TRUE; // set it for any method as it is not expected
  }

  // print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_exit_count);
  if (strstr(mname, "brkpt") != NULL) { // event IS in the "brkpt" method
    printf("Hit #%d: MethodExit #%d: method: %s on thread: %p\n",
           brkptBreakpointHit, method_exit_count, mname, (void*)thread);
    received_method_exit_event = JNI_TRUE; // set it for brkpt method only if brkptBreakpointHit > 1

    err = jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread);
    check_jvmti_status(jni, err, "MethodExit: error in JVMTI SetEventNotificationMode: disable METHOD_EXIT");
  }
  fflush(0);
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
FramePop(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
         jboolean was_popped_by_exception) {
  char* mname = get_method_name(jvmti, jni, method);

  RawMonitorLocker rml(jvmti, jni, event_mon);
  frame_pop_count++;

  printf("\nHit #%d: FramePop #%d: method: %s on thread: %p\n",
         brkptBreakpointHit, frame_pop_count, mname, (void*)thread);

  print_frame_event_info(jvmti, jni, thread, method, "FramePop", frame_pop_count);

  fflush(0);
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
ThreadStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread cthread) {
  char* tname = get_thread_name(jvmti, jni, cthread);
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, event_mon);

  printf("\nThreadStart: cthread: %p, name: %s\n", (void*)cthread, tname);

  // Test SetThreadLocalStorage for carrier thread.
  err = jvmti->SetThreadLocalStorage(cthread, (void*)111);
  check_jvmti_status(jni, err, "ThreadStart: error in JVMTI SetThreadLocalStorage");

  fflush(0);
  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  char* tname = get_thread_name(jvmti, jni, vthread);
  jvmtiError err;
  jboolean is_virtual = jni->IsVirtualThread(vthread);
  const char* virt = is_virtual == JNI_TRUE ? "virtual" : "carrier";

  RawMonitorLocker rml(jvmti, jni, event_mon);

  printf("\nVirtualThreadScheduled: %s thread: %p, name: %s\n", virt, (void*)vthread, tname);

  // Test SetThreadLocalStorage for virtual thread.
  err = jvmti->SetThreadLocalStorage(vthread, (void*)222);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI SetThreadLocalStorage");

  fflush(0);
  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  char* mname = NULL;
  char* cname = NULL;
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, event_mon);

  err = jvmti->GetFrameLocation(vthread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI GetFrameLocation");

  mname = get_method_name(jvmti, jni, method);
  cname = get_method_class_name(jvmti, jni, method);

  printf("\nHit #%d: VirtualThreadMounted #%d: enabling FramePop for method: %s::%s on virtual thread: %p\n",
         brkptBreakpointHit, ++vthread_mounted_count, cname, mname, (void*)vthread);

  err = jvmti->NotifyFramePop(vthread, 0);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI NotifyFramePop");

  print_frame_event_info(jvmti, jni, vthread, method, "VirtualThreadMounted", vthread_mounted_count);

  // Test SetThreadLocalStorage for virtual thread.
  err = jvmti->SetThreadLocalStorage(vthread, (void*)222);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI SetThreadLocalStorage");

  fflush(0);
  deallocate(jvmti, jni, (void*)mname);
  deallocate(jvmti, jni, (void*)cname);
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  char* mname = NULL;
  char* cname = NULL;
  jvmtiError err;

  RawMonitorLocker rml(jvmti, jni, event_mon);

  err = jvmti->GetFrameLocation(vthread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadUnmounted: error in JVMTI GetFrameLocation");

  mname = get_method_name(jvmti, jni, method);
  cname = get_method_class_name(jvmti, jni, method);

  printf("\nHit #%d: VirtualThreadUnmounted #%d: enabling FramePop for method: %s::%s on virtual thread: %p\n",
         brkptBreakpointHit, ++vthread_unmounted_count, cname, mname, (void*)vthread);

  err = jvmti->NotifyFramePop(vthread, 0);
  check_jvmti_status(jni, err, "VirtualThreadUnmounted: error in JVMTI NotifyFramePop");

  print_frame_event_info(jvmti, jni, vthread, method, "VirtualThreadUnmounted", vthread_unmounted_count);

  fflush(0);
  deallocate(jvmti, jni, (void*)mname);
  deallocate(jvmti, jni, (void*)cname);
}

#if 0
static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint fcount) {
  lock_events();

  printf("\nHit #%d: ContinuationRun: vthread: %p\n",
         brkptBreakpointHit, (void*)vthread);

  fflush(0);
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread vthread, jint fcount) {
  lock_events();

  printf("\nHit #%d: ContinuationYield: vthread: %p\n",
         brkptBreakpointHit, (void*)vthread);
  
  fflush(0);
  unlock_events();
}
#endif

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
  callbacks.ThreadStart = &ThreadStart;
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;

  memset(&caps, 0, sizeof(caps));
  caps.can_generate_breakpoint_events = 1;
  caps.can_generate_frame_pop_events = 1;
  caps.can_generate_method_entry_events = 1;
  caps.can_generate_method_exit_events = 1;
  caps.can_support_virtual_threads = 1;

#if 0
  caps.can_support_continuations = 1;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;
#endif

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

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

#if 0
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }
#endif

  event_mon = create_raw_monitor(jvmti, "Events Monitor");

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

  printf("check: vthread_mounted_count:   %d\n", vthread_mounted_count);
  printf("check: vthread_unmounted_count: %d\n", vthread_unmounted_count);
  printf("check: breakpoint_count:        %d\n", breakpoint_count);
  printf("check: method_exit_count:       %d\n", method_exit_count);
  printf("check: frame_pop_count:         %d\n", frame_pop_count);

  if (method_exit_count == 0) {
    passed = JNI_FALSE;
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

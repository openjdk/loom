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

extern "C" {

#define MAX_FRAME_COUNT 40

static jvmtiEnv *jvmti = NULL;
static jrawMonitorID event_mon = NULL;
static int method_entry_count = 0;
static int method_exit_count = 0;
static int breakpoint_count = 0;
static int vt_mounted_count = 0;
static int vt_unmounted_count = 0;
static int cont_run_count = 0;
static int cont_yield_count = 0;
static jboolean pass_status = JNI_TRUE;


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

static void
deallocate(jvmtiEnv *jvmti, JNIEnv* jni, void* ptr) {
  jvmtiError err;

  err = jvmti->Deallocate((unsigned char*)ptr);
  check_jvmti_status(jni, err, "deallocate: error in JVMTI Deallocate call");
}

static char*
get_thread_name(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jvmtiThreadInfo thr_info;
  jvmtiError err;

  memset(&thr_info, 0, sizeof(thr_info));
  err = jvmti->GetThreadInfo(thread, &thr_info);
  check_jvmti_status(jni, err, "get_thread_name: error in JVMTI GetThreadInfo call");

  return thr_info.name == NULL ? (char*)"<Unnamed thread>" : thr_info.name;
}

static char*
get_method_name(jvmtiEnv *jvmti, JNIEnv* jni, jmethodID method) {
  char*  mname = NULL;
  jvmtiError err;

  err = jvmti->GetMethodName(method, &mname, NULL, NULL);
  check_jvmti_status(jni, err, "get_method_name: error in JVMTI GetMethodName call");

  return mname;
}

static char*
get_method_class_name(jvmtiEnv *jvmti, JNIEnv* jni, jmethodID method) {
  jclass klass = NULL;
  char*  cname = NULL;
  char*  result = NULL;
  jvmtiError err;

  err = jvmti->GetMethodDeclaringClass(method, &klass);
  check_jvmti_status(jni, err, "get_method_class_name: error in JVMTI GetMethodDeclaringClass");

  err = jvmti->GetClassSignature(klass, &cname, NULL);
  check_jvmti_status(jni, err, "get_method_class_name: error in JVMTI GetClassSignature");

  size_t len = strlen(cname) - 2; // get rid of leading 'L' and trailing ';'

  err = jvmti->Allocate((jlong)(len + 1), (unsigned char**)&result);
  check_jvmti_status(jni, err, "get_method_class_name: error in JVMTI Allocate");

  strncpy(result, cname + 1, len); // skip leading 'L'
  result[len] = '\0';
  return result;
}

static jclass
find_class(jvmtiEnv *jvmti, JNIEnv *jni, jobject loader, const char* cname) {
  jclass *classes = NULL;
  jint count = 0;
  jvmtiError err;

  err = jvmti->GetClassLoaderClasses(loader, &count, &classes);
  check_jvmti_status(jni, err, "find_class: error in JVMTI GetClassLoaderClasses");

  // Find the jmethodID of the specified method
  while (--count >= 0) {
    char* name = NULL;
    jclass klass = classes[count];

    err = jvmti->GetClassSignature(klass, &name, NULL);
    check_jvmti_status(jni, err, "find_class: error in JVMTI GetClassSignature call");

    bool found = (strcmp(name, cname) == 0);
    deallocate(jvmti, jni, (void*)name);
    if (found) {
      return klass;
    }
  }
  return NULL;
}

static jmethodID
find_method(JNIEnv *jni, jclass klass, const char* mname) {
  jmethodID *methods = NULL;
  jmethodID method = NULL;
  jint count = 0;
  jvmtiError err;

  err = jvmti->GetClassMethods(klass, &count, &methods);
  check_jvmti_status(jni, err, "find_method: error in JVMTI GetClassMethods");

  // Find the jmethodID of the specified method
  while (--count >= 0) {
    char* name = NULL;

    jmethodID meth = methods[count];

    err = jvmti->GetMethodName(meth, &name, NULL, NULL);
    check_jvmti_status(jni, err, "find_method: error in JVMTI GetMethodName call");

    bool found = (strcmp(name, mname) == 0);
    deallocate(jvmti, jni, (void*)name);
    if (found) {
      method = meth;
      break;
    }
  }
  deallocate(jvmti, jni, (void*)methods);
  return method;
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
  char* tname = get_thread_name(jvmti, jni, thread);
  char* mname = get_method_name(jvmti, jni, method);
  char* cname = get_method_class_name(jvmti, jni, method);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  printf("\n%s #%d: method: %s::%s, %s thread: %s\n",
         event_name, event_count, cname, mname, virt, tname);

  print_stack_trace(jvmti, jni, thread);

  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)mname);
  deallocate(jvmti, jni, (void*)cname);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
                      jint frames_cnt, const char* event_name, int event_count) {
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  printf("\n%s #%d: %s thread: %s, frames: %d\n",
          event_name, event_count, virt, tname, frames_cnt);

  print_stack_trace(jvmti, jni, thread);

  deallocate(jvmti, jni, (void*)tname);
}

static void
set_breakpoint(JNIEnv *jni, jclass klass, const char *mname)
{
  jlocation location = (jlocation)0L;
  jmethodID method = find_method(jni, klass, mname);
  jvmtiError err;

  // Find the jmethodID of the specified method

  if (method == NULL) {
    jni->FatalError("Error in set_breakpoint: not found method");
  }
  err = jvmti->SetBreakpoint(method, location);
  check_jvmti_status(jni, err, "set_or_clear_breakpoint: error in JVMTI SetBreakpoint");

  fflush(0);
}

static void JNICALL
Breakpoint(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread,
           jmethodID method, jlocation location) {
  char* mname = get_method_name(jvmti, jni, method);

  if (strcmp(mname, "run") != 0 && strcmp(mname, "yield") != 0) {
    printf("FAILED: got  unexpected breakpoint in method %s()\n", mname);
    pass_status = JNI_FALSE;
    deallocate(jvmti, jni, (void*)mname);
    return;
  }
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  lock_events();

  printf("Breakpoint: %s: Stack Trace of %s thread: %s\n", mname, virt, tname);

  print_frame_event_info(jvmti, jni, thread, method,
                         "Breakpoint", ++breakpoint_count);
  fflush(0);
  unlock_events();

  deallocate(jvmti, jni, (void*)tname);
  deallocate(jvmti, jni, (void*)mname);
}

static void JNICALL
ThreadStart(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  char* tname = get_thread_name(jvmti, jni, thread);

  lock_events();

  printf("\nThreadStart: thread: %p, name: %s\n", (void*)thread, tname);
  fflush(0);
  unlock_events();

  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  char* tname = get_thread_name(jvmti, jni, thread);
  const char* virt = jni->IsVirtualThread(thread) ? "virtual" : "carrier";

  lock_events();

  printf("\nVirtualThreadScheduled: %s, thread: %s\n", virt, tname);
  fflush(0);
  unlock_events();

  deallocate(jvmti, jni, (void*)tname);
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  jvmtiError err;

  err = jvmti->GetFrameLocation(thread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadMounted: error in JVMTI GetFrameLocation");

  lock_events();

  print_frame_event_info(jvmti, jni, thread, method,
                         "VirtualThreadMounted", ++vt_mounted_count);
  fflush(0);
  unlock_events();
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread) {
  jmethodID method = NULL;
  jlocation loc = 0L;
  jvmtiError err;

  err = jvmti->GetFrameLocation(thread, 0, &method, &loc);
  check_jvmti_status(jni, err, "VirtualThreadMUnmounted: error in JVMTI GetFrameLocation");

  lock_events();

  print_frame_event_info(jvmti, jni, thread, method,
                         "VirtualThreadUnmounted", ++vt_unmounted_count);
  fflush(0);
  unlock_events();
}

#if 0
static void JNICALL
MethodEntry(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method) {
  lock_events();

  print_frame_event_info(jvmti, jni, thread, method, "MethodEntry", method_entry_count++);

  fflush(0);
  unlock_events();
}

static void JNICALL
MethodExit(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jmethodID method,
           jboolean was_popped_by_exception, jvalue return_value) {
  lock_events();

  print_frame_event_info(jvmti, jni, thread, method, "MethodExit", method_exit_count++);
  fflush(0);
  unlock_events();
}
#endif

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint fcount) {
  lock_events();

  print_cont_event_info(jvmti, jni, thread, fcount, "ContinuationRun", cont_run_count++);  

  fflush(0);
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv* jni, jthread thread, jint fcount) {
  lock_events();

  print_cont_event_info(jvmti, jni, thread, fcount, "ContinuationYield", cont_yield_count++);  

  fflush(0);
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
  callbacks.ThreadStart = &ThreadStart;
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_generate_breakpoint_events = 1;

#if 0
  caps.can_generate_method_entry_events = 1;
  caps.can_generate_method_exit_events = 1;
  callbacks.MethodEntry = &MethodEntry;
  callbacks.MethodExit  = &MethodExit;
#endif

  caps.can_support_continuations = 1;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;

  err = jvmti->CreateRawMonitor("Events Monitor", &event_mon);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI CreateRawMonitor: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI AddCapabilities: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("Agent_OnLoad: Error in JVMTI SetEventCallbacks: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_THREAD_START, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    return JNI_ERR;
  }

  printf("Agent_OnLoad finished\n");
  fflush(0);

  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_BreakpointInYieldTest_enableEvents(JNIEnv *jni, jclass klass, jthread thread) {
  jvmtiError err;

  lock_events();

  printf("enableEvents: started\n");

  jclass k1 = find_class(jvmti, jni, NULL, "Ljava/lang/VirtualThread;");
  jclass k2 = find_class(jvmti, jni, NULL, "Ljava/lang/Continuation;");
  if (k1 == NULL || k2 == NULL) {
    jni->FatalError("Did not find one of the classes by name: VirtualThread or Continuation");
  }
  set_breakpoint(jni, k1, "run");
  set_breakpoint(jni, k2, "yield");

  // Enable Breakpoint events globally
  err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, NULL);
  check_jvmti_status(jni, err, "enableEvents: error in JVMTI SetEventNotificationMode: enable BREAKPOINT");

  printf("enableEvents: finished\n");

  unlock_events();
  fflush(0);
}

JNIEXPORT jboolean JNICALL
Java_BreakpointInYieldTest_check(JNIEnv *jni, jclass cls) {
  printf("\n");
  printf("check: breakpoint_count:     %d\n", breakpoint_count);
  printf("check: vt_mounted_count:     %d\n", vt_mounted_count);
  printf("check: vt_unmounted_count:   %d\n", vt_unmounted_count);
  printf("check: cont_run_count:       %d\n", cont_run_count);
  printf("check: cont_yield_count:     %d\n", cont_yield_count);
  printf("\n");
  fflush(0);

  return pass_status;
}
} // extern "C"

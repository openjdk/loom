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

#include <string.h>
#include "jvmti.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MAX_FRAME_COUNT 30
#define MAX_WORKER_THREADS 10

typedef struct Tinfo {
  jboolean just_scheduled;
  jboolean was_run;
  jboolean was_yield;
  char* thr_name;
} Tinfo;

static const int MAX_EVENTS_TO_PROCESS = 20;
static jvmtiEnv *jvmti = NULL;
static jrawMonitorID events_monitor = NULL;
static Tinfo tinfo[MAX_WORKER_THREADS];
static jboolean continuation_events_enabled = JNI_FALSE;

static void
lock_events() {
  (*jvmti)->RawMonitorEnter(jvmti, events_monitor);
}

static void
unlock_events() {
  (*jvmti)->RawMonitorExit(jvmti, events_monitor);
}

static void
fatal(JNIEnv* jni, char* msg) {
  (*jni)->FatalError(jni, msg);
  fflush(stdout);
}

static Tinfo*
find_tinfo(JNIEnv* jni, char* thr_name) {
  Tinfo* inf = NULL;
  int idx = 0;

  // Find slot with named worker thread or empty slot
  for (; idx < MAX_WORKER_THREADS; idx++) {
    inf = &tinfo[idx];
    if (inf->thr_name == NULL) {
      inf->thr_name = thr_name;
      break;
    }
    if (strcmp(inf->thr_name, thr_name) == 0) {
      break;
    }
  }
  if (idx >= MAX_WORKER_THREADS) {
    fatal(jni, "find_tinfo: found more than 10 worker threads!");
  }
  return inf; // return slot
}

static char*
get_method_class_name(jvmtiEnv *jvmti, JNIEnv *jni, jmethodID method) {
  jvmtiError err;
  jclass klass = NULL;
  char*  cname = NULL;

  err = (*jvmti)->GetMethodDeclaringClass(jvmti, method, &klass);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "get_method_class_name: error in JVMTI GetMethodDeclaringClass");
  }
  err = (*jvmti)->GetClassSignature(jvmti, klass, &cname, NULL);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "get_method_class_name: error in JVMTI GetClassSignature");
  }
  cname[strlen(cname) - 1] = '\0'; // get rid of trailing ';'
  return cname + 1;                // get rid of leading 'L'
}

static void
print_method(jvmtiEnv *jvmti, JNIEnv *jni, jmethodID method, jint depth) {
  char*  cname = NULL;
  char*  mname = NULL;
  char*  msign = NULL;
  jvmtiError err;

  cname = get_method_class_name(jvmti, jni, method);

  err = (*jvmti)->GetMethodName(jvmti, method, &mname, &msign, NULL);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "print_method: error in JVMTI GetMethodName");
  }
  printf("%2d: %s: %s%s\n", depth, cname, mname, msign);
}

static void
print_stack_trace(jvmtiEnv *jvmti, JNIEnv *jni, int count, jvmtiFrameInfo *frames) {
  printf("JVMTI Stack Trace: frame count: %d\n", count);
  for (int depth = 0; depth < count; depth++) {
    print_method(jvmti, jni, frames[depth].method, depth);
  }
  printf("\n");
}

static jint
find_method_depth(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char *mname) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = -1;
  jvmtiError err;

  err = (*jvmti)->GetStackTrace(jvmti, vthread, 0, MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_NONE) {
    printf("find_method_depth: JVMTI GetStackTrace  returned error: %d\n", err);
    fatal(jni, "event handler: failed during JVMTI GetStackTrace call");
  }

  for (int depth = 0; depth < count; depth++) {
    jmethodID method = frames[depth].method;
    char* name = NULL;
    char* sign = NULL;

    err = (*jvmti)->GetMethodName(jvmti, method, &name, &sign, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("find_method_depth: JVMTI GetMethodName with returned error: %d\n", err);
      fatal(jni, "event handler: failed during JVMTI GetMethodName call");
    }
    if (strcmp(name, mname) == 0) {
      return depth;
    }
  }
  return -1;
}

static void
print_vthread_event_info(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, char* event_name) {
  jvmtiThreadInfo thr_info;
  jvmtiError err = (*jvmti)->GetThreadInfo(jvmti, thread, &thr_info);

  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler failed during JVMTI GetThreadInfo call");
  }
  char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("\n#### %s event: thread: %s, vthread: %p\n", event_name, thr_name, vthread);

  Tinfo* inf = find_tinfo(jni, thr_name); // Find slot with named worker thread

  if (strcmp(event_name, "VirtualThreadScheduled") == 0) {
    inf->just_scheduled = JNI_TRUE;
  }
  else {
    if (inf->thr_name == NULL && strcmp(event_name, "VirtualThreadTerminated") != 0) {
      fatal(jni, "VThread event: worker thread not found!");
    }
    if (strcmp(event_name, "VirtualThreadMounted") == 0) {
      if (!inf->just_scheduled) { // There is no ContinuationRun for just scheduled vthreads
        if (inf->was_yield) {
          fatal(jni, "VirtualThreadMounted: event with ContinuationYield before!");
        }
        if (continuation_events_enabled && inf->was_run) {
          fatal(jni, "VirtualThreadMounted: event with ContinuationRun before!");
        }
      }
    }
    if (strcmp(event_name, "VirtualThreadUnmounted") == 0) {
      if (inf->just_scheduled) {
        fatal(jni, "VirtualThreadUnmounted: event without VirtualThreadMounted before!");
      }
      if (inf->was_run) {
        fatal(jni, "VirtualThreadUnmounted: event with ContinuationRun before!");
      }
      if (continuation_events_enabled && !inf->was_yield) {
        fatal(jni, "VirtualThreadUnmounted: event without ContinuationYield before!");
      }
    }
    inf->just_scheduled = JNI_FALSE;
  }
  inf->was_run = JNI_FALSE;
  inf->was_yield = JNI_FALSE;
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jint frames_cnt, char* event_name) {
  static int cont_events_cnt = 0;
  if (cont_events_cnt++ > MAX_EVENTS_TO_PROCESS) {
    return; // No need to test all events
  }

  jvmtiThreadInfo thr_info;
  jvmtiError err = (*jvmti)->GetThreadInfo(jvmti, thread, &thr_info);

  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler failed during JVMTI GetThreadInfo call");
  }
  char* thr_name = (thr_info.name == NULL) ? "<Unnamed thread>" : thr_info.name;
  printf("\n#### %s event: thread: %s, frames count: %d\n", event_name, thr_name, frames_cnt);

  Tinfo* inf = find_tinfo(jni, thr_name); // Find slot with named worker thread
  if (inf->thr_name == NULL) {
    fatal(jni, "Continuation event: worker thread not found!");
  }
  if (strcmp(event_name, "ContinuationRun") == 0) {
    inf->was_run = JNI_TRUE;
    inf->was_yield = JNI_FALSE;
  }
  if (strcmp(event_name, "ContinuationYield") == 0) {
    inf->was_run = JNI_FALSE;
    inf->was_yield = JNI_TRUE;
  }
}

static void
test_GetVirtualThread(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, char* event_name) {
  jobject thread_vthread = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetVirtualThread function NULL thread (current)
  err = (*jvmti)->GetVirtualThread(jvmti, NULL, &thread_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with NULL thread (current) returned error status");
  }
  if (thread_vthread == NULL) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with NULL thread (current) failed to return non-NULL vthread");
  }
  printf("JVMTI GetVirtualThread with NULL thread (current) returned non-NULL vthread as expected\n");

  // #2: Test JVMTI GetVirtualThread function with a bad thread
  err = (*jvmti)->GetVirtualThread(jvmti, vthread, &thread_vthread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with bad thread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #3: Test JVMTI GetVirtualThread function with a good thread
  err = (*jvmti)->GetVirtualThread(jvmti, thread, &thread_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: failed during JVMTI GetVirtualThread call");
  }
  if (thread_vthread == NULL) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with good thread failed to return non-NULL vthread");
  }
  printf("JVMTI GetVirtualThread with good thread returned non-NULL vthread as expected\n");
}

static void
test_GetCarrierThread(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, char* event_name) {
  jthread vthread_thread = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetCarrierThread function with NULL vthread
  err = (*jvmti)->GetCarrierThread(jvmti, NULL, &vthread_thread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with NULL vthread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #2: Test JVMTI GetCarrierThread function with a bad vthread
  err = (*jvmti)->GetCarrierThread(jvmti, thread, &vthread_thread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with bad vthread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #3: Test JVMTI GetCarrierThread function with a good vthread
  err = (*jvmti)->GetCarrierThread(jvmti, vthread, &vthread_thread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: failed during JVMTI GetCarrierThread call");
  }
  if (vthread_thread == NULL) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with good vthread failed to return non-NULL carrier thread");
  }
  printf("JVMTI GetCarrierThread with good vthread returned non-NULL carrier thread as expected\n");
}

static void
test_GetThreadInfo(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char* event_name) {
  jvmtiError err;
  jvmtiThreadInfo tinfo;
  jvmtiThreadGroupInfo ginfo;
  jint class_count = -1;
  jclass* classes = NULL;
  jboolean found = JNI_FALSE;

  printf("test_GetThreadInfo: started\n");

  // #1: Test JVMTI GetThreadInfo function with a good vthread
  err = (*jvmti)->GetThreadInfo(jvmti, vthread, &tinfo);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetThreadInfo returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetThreadInfo failed to return JVMTI_ERROR_NONE");
  }
  printf("GetThreadInfo: name: %s, prio: %d, is_daemon: %d\n",
         tinfo.name, tinfo.priority, tinfo.is_daemon);

  // #2: Test JVMTI GetThreadGroupInfo
  err = (*jvmti)->GetThreadGroupInfo(jvmti, tinfo.thread_group, &ginfo);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetThreadGroupInfo returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetThreadGroupInfo failed to return JVMTI_ERROR_NONE");
  }
  printf("GetThreadGroupInfo: name: %s, max prio: %d, is_daemon: %d\n",
         ginfo.name, ginfo.max_priority, ginfo.is_daemon);

  // #3: Test JVMTI GetClassLoaderClasses
  err = (*jvmti)->GetClassLoaderClasses(jvmti, tinfo.context_class_loader, &class_count, &classes);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetClassLoaderClasses returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetClassLoaderClasses failed to return JVMTI_ERROR_NONE");
  }
  printf("tinfo.context_class_loader: %p, class_count: %d\n", tinfo.context_class_loader, class_count);

  // #4: Test the tinfo.context_class_loader has the VThreadTest class
  for (int idx = 0; idx < class_count; idx++) {
    char* sign = NULL;
    err = (*jvmti)->GetClassSignature(jvmti, classes[idx], &sign, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("JVMTI GetClassSignature returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetClassSignature failed to return JVMTI_ERROR_NONE");
    }
    if (strstr(sign, "VThreadTest") != NULL) {
      found = JNI_TRUE;
      break;
    }
  }
  if (found == JNI_FALSE) {
    fatal(jni, "event handler: VThreadTest class was not found in virtual thread context_class_loader classes");
  }
  printf("test_GetThreadInfo: finished\n");
}


static int
test_GetFrameCount(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char* event_name) {
  int frame_count = -1;
  jvmtiError err;

  // #1: Test JVMTI GetFrameCount function with NULL count_ptr pointer
  err = (*jvmti)->GetFrameCount(jvmti, vthread, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameCount with NULL count_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameCount with NULL count_ptr pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #2: Test JVMTI GetFrameCount function with a good vthread
  err = (*jvmti)->GetFrameCount(jvmti, vthread, &frame_count);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetFrameCount with good vthread returned error: %d\n", err);
    fatal(jni, "event handler: failed during JVMTI GetFrameCount call");
  }
  if (frame_count < 0) {
    fatal(jni, "event handler: JVMTI GetFrameCount with good vthread returned negative frame_count\n");
  }
  printf("JVMTI GetFrameCount with good vthread returned frame_count: %d\n", frame_count);

  return frame_count;
}

static void
test_GetFrameLocation(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char* event_name, int frame_count) {
  jmethodID method = NULL;
  jlocation location = -1;
  jvmtiError err;

  // #1: Test JVMTI GetFrameLocation function with negative frame depth
  err = (*jvmti)->GetFrameLocation(jvmti, vthread, -1, &method, &location);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetFrameLocation with negative frame depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with negative frame depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetFrameLocation function with NULL method_ptr
  err = (*jvmti)->GetFrameLocation(jvmti, vthread, 0, NULL, &location);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameLocation with NULL method_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with NULL method_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #3: Test JVMTI GetFrameLocation function with NULL location_ptr
  err = (*jvmti)->GetFrameLocation(jvmti, vthread, 0, &method, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameCount with NULL location_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with NULL location_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #4: Test JVMTI GetFrameLocation function with a good vthread
  if (frame_count == 0) {
    err = (*jvmti)->GetFrameLocation(jvmti, vthread, 0, &method, &location);
    if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
      printf("JVMTI GetFrameLocation for empty stack returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetFrameLocation for empty stack failed to return JVMTI_ERROR_NO_MORE_FRAMES");
    }
    printf("JVMTI GetFrameLocation for empty stack returned JVMTI_ERROR_NO_MORE_FRAMES as expected\n");
  } else {
    err = (*jvmti)->GetFrameLocation(jvmti, vthread, frame_count, &method, &location);
    if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
      printf("JVMTI GetFrameLocation for bid depth == frame_count returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetFrameLocation for too big depth failed to return JVMTI_ERROR_NO_MORE_FRAMES");
    }
    printf("JVMTI GetFrameLocation for too big depth returned JVMTI_ERROR_NO_MORE_FRAMES as expected\n");

    err = (*jvmti)->GetFrameLocation(jvmti, vthread, 1, &method, &location);
    if (err != JVMTI_ERROR_NONE) {
      printf("JVMTI GetFrameLocation with good vthread returned error: %d\n", err);
      fatal(jni, "event handler: failed during JVMTI GetFrameCount call");
    }
    if (location < 0) {
      fatal(jni, "event handler: JVMTI GetFrameLocation with good vthread returned negative location\n");
    }
    printf("JVMTI GetFrameLocation with good vthread returned location: %d\n", (int) location);
  }
}

static void
test_GetStackTrace(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char* event_name, int frame_count) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  int count = -1;
  jmethodID method = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetStackTrace function with bad start_depth
  err = (*jvmti)->GetStackTrace(jvmti, vthread, -(frame_count + 1), MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with negative start_depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with verynegative start_depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }
  err = (*jvmti)->GetStackTrace(jvmti, vthread, (frame_count + 1), MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with very big start_depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with very big start_depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetStackTrace function with negative max_frame_count
  err = (*jvmti)->GetStackTrace(jvmti, vthread, 0, -1, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with negative max_frame_count returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with negative max_frame_count failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #3: Test JVMTI GetStackTrace function with NULL frame_buffer pointer
  err = (*jvmti)->GetStackTrace(jvmti, vthread, 0, MAX_FRAME_COUNT, NULL, &count);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetStackTrace with NULL frame_buffer pointer returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace witt NULL frame_buffer pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #4: Test JVMTI GetStackTrace function with NULL count_ptr pointer
  err = (*jvmti)->GetStackTrace(jvmti, vthread, 0, MAX_FRAME_COUNT, frames, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetStackTrace with NULL count_ptr pointer returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace witt NULL count_ptr pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #5: Test JVMTI GetStackTrace function with a good vthread
  if (frame_count == 0) {
    err = (*jvmti)->GetStackTrace(jvmti, vthread, 1, MAX_FRAME_COUNT, frames, &count);
    if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
      printf("JVMTI GetStackTrace for empty stack returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetStackTrace for empty stack failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
    }
  } else {
    err = (*jvmti)->GetStackTrace(jvmti, vthread, 0, MAX_FRAME_COUNT, frames, &count);
    if (err != JVMTI_ERROR_NONE) {
      printf("JVMTI GetStackTrace with good vthread returned error: %d\n", err);
      fatal(jni, "event handler: failed during JVMTI GetStackTrace call");
    }
    if (count <= 0) {
      fatal(jni, "event handler: JVMTI GetStackTrace with good vthread returned negative frame count\n");
    }
    print_stack_trace(jvmti, jni, count, frames);
  }
}

enum Slots { SlotInvalid0 = -1, SlotObj = 0, SlotInt = 1, SlotLong = 2, SlotUnaligned = 3, SlotFloat = 4, SlotDouble = 5 };

static void
test_GetLocal(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, char* event_name, int frame_count) {
  jmethodID method = NULL;
  jobject obj = NULL;
  jint ii = 0;
  jlong ll = 0L;
  jfloat ff = 0.0;
  jdouble dd = 0.0;
  jint depth = -1;
  jvmtiError err;

  if (strcmp(event_name, "VirtualThreadMounted") != 0 && strcmp(event_name, "VirtualThreadUnmounted") != 0) {
    return; // Check GetLocal at VirtualThreadMounted/VirtualThreadUnmounted events only
  }

  depth = find_method_depth(jvmti, jni, vthread, "producer");
  if (depth == -1) {
    return; // skip testing CONSUMER vthreads wich have no producer(String msg) method
  }

  printf("Testing GetLocal<Type> for method: producer(Ljava/Lang/String;)V at depth: %d\n", depth);

  // #1: Test JVMTI GetLocalObject function with negative frame depth
  err = (*jvmti)->GetLocalObject(jvmti, vthread, -1, SlotObj, &obj);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetLocalObject with negative frame depth returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with negative frame depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetLocalObject function with big frame depth
  err = (*jvmti)->GetLocalObject(jvmti, vthread, frame_count, SlotObj, &obj);
  if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
    printf("JVMTI GetLocalObject with big frame depth returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with big frame depth failed to return JVMTI_ERROR_NO_MORE_FRAMES");
  }

  // #3: Test JVMTI GetLocalObject function with invalid slot -1
  err = (*jvmti)->GetLocalObject(jvmti, vthread, depth, SlotInvalid0, &obj);
  if (err != JVMTI_ERROR_INVALID_SLOT) {
    printf("JVMTI GetLocalObject with invalid slot -1 returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with invalid slot -1 failed to return JVMTI_ERROR_INVALID_SLOT");
  }

  // #4: Test JVMTI GetLocalObject function with unaligned slot 3
  err = (*jvmti)->GetLocalObject(jvmti, vthread, depth, SlotUnaligned, &obj);
  if (err != JVMTI_ERROR_INVALID_SLOT && err != JVMTI_ERROR_TYPE_MISMATCH) {
    printf("JVMTI GetLocalObject with unaligned slot 3 returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with unaligned slot 3 failed"
               " to return JVMTI_ERROR_INVALID_SLOT or JVMTI_ERROR_TYPE_MISMATCH");
  }

  // #5: Test JVMTI GetLocalObject function with NULL value_ptr
  err = (*jvmti)->GetLocalObject(jvmti, vthread, depth, SlotObj, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetLocalObject with NULL method_ptr returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with NULL method_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #6: Test JVMTI GetLocal<Type> functions with a good vthread
  err = (*jvmti)->GetLocalObject(jvmti, vthread, depth, SlotObj, &obj);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalObject with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalObject call");
  }
  const char* str = (*jni)->GetStringUTFChars(jni, (jstring)obj, NULL);
  printf("    local String value at slot %d: %s\n", SlotObj, str);
  const char* exp_str = "msg: ...";
  if (strncmp(str, exp_str, 5) != 0) {
    printf("    Failed: Expected local String value: %s, got: %s\n", exp_str, str);
    fatal(jni, "Got unexpected local String value");
  }
  (*jni)->ReleaseStringUTFChars(jni, (jstring)obj, str);

  err = (*jvmti)->GetLocalInt(jvmti, vthread, depth, SlotInt, &ii);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalInt with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalInt call");
  }
  printf("    local int value at slot %d: %d\n", SlotInt, ii);
  if (ii != 1) {
    printf("    Failed: Expected local int value: 1, got %d\n", ii);
    fatal(jni, "Got unexpected local int value");
  }

  err = (*jvmti)->GetLocalLong(jvmti, vthread, depth, SlotLong, &ll);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalInt with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalInt call");
  }
  printf("    local long value at slot %d: %lld\n", SlotLong, (long long)ll);
  if (ll != 2L) {
    printf("    Failed: Expected local long value: 2L, got %lld\n", (long long)ll);
    fatal(jni, "Got unexpected local long value");
  }

  err = (*jvmti)->GetLocalFloat(jvmti, vthread, depth, SlotFloat, &ff);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalFloat with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalFloat call");
  }
  printf("    local float value at slot %d: %f\n", SlotFloat, ff);
  if (ff < 3.200000 || ff > 3.200001) {
    printf("    Failed: Expected local float value: 3.200000, got %f\n", ff);
    fatal(jni, "Got unexpected local float value");
  }

  err = (*jvmti)->GetLocalDouble(jvmti, vthread, depth, SlotDouble, &dd);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalDouble with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalDouble call");
  }
  printf("    local double value at slot %d: %f\n", SlotDouble, dd);
  if (dd < 4.500000047683716 || dd > 4.500000047683717) {
    printf("    Failed: Expected local double value: 4.500000047683716, got %f\n", dd);
    fatal(jni, "Got unexpected local double value");
  }
}

static void
processVThreadEvent(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, char* event_name) {
  static int vthread_events_cnt = 0;

  if (strcmp(event_name, "VirtualThreadTerminated") != 0 &&
      strcmp(event_name, "VirtualThreadScheduled")  != 0) {
    if (vthread_events_cnt++ > MAX_EVENTS_TO_PROCESS) {
      return; // No need to test all events
    }
  }

  print_vthread_event_info(jvmti, jni, thread, vthread, event_name);

  if (strcmp(event_name, "VirtualThreadTerminated") == 0) {
    return; // skip further testing as GetVirtualThread can return NULL
  }

  test_GetVirtualThread(jvmti, jni, thread, vthread, event_name);
  test_GetCarrierThread(jvmti, jni, thread, vthread, event_name);

  if (strcmp(event_name, "VirtualThreadScheduled") == 0) {
    test_GetThreadInfo(jvmti, jni, vthread, event_name);
    return; // skip testing of GetFrame* for VirtualThreadScheduled events
  }
  int frame_count = test_GetFrameCount(jvmti, jni, vthread, event_name);
  test_GetFrameLocation(jvmti, jni, vthread, event_name, frame_count);
  test_GetStackTrace(jvmti, jni, vthread, event_name, frame_count);
  test_GetLocal(jvmti, jni, vthread, event_name, frame_count);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jobject mounted_vthread = NULL;
  jvmtiError err;

  lock_events();

  processVThreadEvent(jvmti, jni, thread, vthread, "VirtualThreadScheduled");

  err = (*jvmti)->GetVirtualThread(jvmti, thread, &mounted_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "VirtualThreadScheduled event handler: failed during JVMTI GetVirtualThread call");
  }
  if (!(*jni)->IsSameObject(jni, mounted_vthread, vthread)) {
    fatal(jni, "VirtualThreadScheduled event handler: JVMTI GetVirtualThread failed to return proper vthread");
  }

  unlock_events();
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  jobject mounted_vthread = NULL;
  jvmtiError err;

  lock_events();

  processVThreadEvent(jvmti, jni, thread, vthread, "VirtualThreadTerminated");

  err = (*jvmti)->GetVirtualThread(jvmti, thread, &mounted_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "VirtualThreadTerminated event handler: failed during JVMTI GetVirtualThread call");
  }
  if (mounted_vthread != NULL) {
    fatal(jni, "VirtualThreadTerminated event handler: JVMTI GetVirtualThread failed to return NULL vthread");
  }

  unlock_events();
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  lock_events();
  processVThreadEvent(jvmti, jni, thread, vthread, "VirtualThreadMounted");
  unlock_events();
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread) {
  lock_events();
  processVThreadEvent(jvmti, jni, thread, vthread, "VirtualThreadUnmounted");
  unlock_events();
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jint frames_count) {
  lock_events();
  print_cont_event_info(jvmti, jni, thread, frames_count, "ContinuationRun");
  unlock_events();
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jint frames_count) {
  lock_events();
  print_cont_event_info(jvmti, jni, thread, frames_count, "ContinuationYield");
  unlock_events();
}

extern JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options,
                                           void *reserved) {
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if ((*jvm)->GetEnv(jvm, (void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
    return JNI_ERR;
  }

  if (strcmp(options, "EnableContinuationEvents") == 0) {
    continuation_events_enabled = JNI_TRUE;
  } else if (strcmp(options, "DisableContinuationEvents") == 0) {
    continuation_events_enabled = JNI_FALSE;
  } else {
    printf("bad option passed to Agent_OnLoad: \"%s\"\n", options);
    return 2;
  }

  memset(&callbacks, 0, sizeof(callbacks));
  callbacks.VirtualThreadScheduled  = &VirtualThreadScheduled;
  callbacks.VirtualThreadTerminated = &VirtualThreadTerminated;
  callbacks.VirtualThreadMounted   = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;
  callbacks.ContinuationRun   = &ContinuationRun;
  callbacks.ContinuationYield = &ContinuationYield;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_access_local_variables = 1;
  if (continuation_events_enabled) {
    caps.can_support_continuations = 1;
  }
  err = (*jvmti)->AddCapabilities(jvmti, &caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI AddCapabilities: %d\n", err);
  }

  err = (*jvmti)->SetEventCallbacks(jvmti, &callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventCallbacks: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_SCHEDULED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_TERMINATED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_MOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_VIRTUAL_THREAD_UNMOUNTED, NULL);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventNotificationMode: %d\n", err);
  }

  if (continuation_events_enabled) {
    err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    }

    err = (*jvmti)->SetEventNotificationMode(jvmti, JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    }
  }

  (*jvmti)->CreateRawMonitor(jvmti, "Events Monitor", &events_monitor);
  printf("Agent_OnLoad finished\n");
  return 0;
}

#ifdef __cplusplus
}
#endif

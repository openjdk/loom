/*
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
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

#define MAX_FRAME_COUNT 30
#define MAX_WORKER_THREADS 10

typedef struct Tinfo {
  jboolean just_scheduled;
  const char* tname;
} Tinfo;

static const int MAX_EVENTS_TO_PROCESS = 20;
static jvmtiEnv *jvmti = NULL;
static jrawMonitorID events_monitor = NULL;
static Tinfo tinfo[MAX_WORKER_THREADS];
static jboolean continuation_events_enabled = JNI_FALSE;


static Tinfo*
find_tinfo(JNIEnv* jni, const char* tname) {
  Tinfo* inf = NULL;
  int idx = 0;

  // Find slot with named worker thread or empty slot
  for (; idx < MAX_WORKER_THREADS; idx++) {
    inf = &tinfo[idx];
    if (inf->tname == NULL) {
      inf->tname = tname;
      break;
    }
    if (strcmp(inf->tname, tname) == 0) {
      break;
    }
  }
  if (idx >= MAX_WORKER_THREADS) {
    fatal(jni, "find_tinfo: found more than 10 worker threads!");
  }
  return inf; // return slot
}

static jint
find_method_depth(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *mname) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = -1;
  jvmtiError err;

  err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_NONE) {
    printf("find_method_depth: JVMTI GetStackTrace  returned error: %d\n", err);
    fatal(jni, "event handler: failed during JVMTI GetStackTrace call");
  }

  for (int depth = 0; depth < count; depth++) {
    jmethodID method = frames[depth].method;
    char* name = NULL;
    char* sign = NULL;

    err = jvmti->GetMethodName(method, &name, &sign, NULL);
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
print_vthread_event_info(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, const char *event_name) {
  char* tname = get_thread_name(jvmti, jni, vthread);
  Tinfo* inf = find_tinfo(jni, tname); // Find slot with named worker thread

  printf("\n#### %s event: thread: %s, vthread: %p\n", event_name, tname, vthread);

  if (strcmp(event_name, "VirtualThreadScheduled") == 0) {
    inf->just_scheduled = JNI_TRUE;
  }
  else {
    if (inf->tname == NULL && strcmp(event_name, "VirtualThreadTerminated") != 0) {
      fatal(jni, "VThread event: worker thread not found!");
    }
    if (strcmp(event_name, "VirtualThreadUnmounted") == 0) {
      if (inf->just_scheduled) {
        fatal(jni, "VirtualThreadUnmounted: event without VirtualThreadMounted before!");
      }
    }
    inf->just_scheduled = JNI_FALSE;
  }
  //deallocate(jvmti, jni, (void*)tname);
}

static void
print_cont_event_info(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jint frames_cnt, const char *event_name) {
  static int cont_events_cnt = 0;
  if (cont_events_cnt++ > MAX_EVENTS_TO_PROCESS) {
    return; // No need to test all events
  }

  char* tname = get_thread_name(jvmti, jni, thread);
  Tinfo* inf = find_tinfo(jni, tname); // Find slot with named worker thread

  printf("\n#### %s event: thread: %s, frames count: %d\n", event_name, tname, frames_cnt);

  if (inf->tname == NULL) {
    fatal(jni, "Continuation event: worker thread not found!");
  }
  //deallocate(jvmti, jni, (void*)tname);
}

static void
test_GetVirtualThread(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, const char *event_name) {
  jobject thread_vthread = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetVirtualThread function NULL thread (current)
  err = jvmti->GetVirtualThread(NULL, &thread_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with NULL thread (current) returned error status");
  }
  if (thread_vthread == NULL) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with NULL thread (current) failed to return non-NULL vthread");
  }
  printf("JVMTI GetVirtualThread with NULL thread (current) returned non-NULL vthread as expected\n");

  // #2: Test JVMTI GetVirtualThread function with a bad thread
  err = jvmti->GetVirtualThread(vthread, &thread_vthread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with bad thread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #3: Test JVMTI GetVirtualThread function with a good thread
  err = jvmti->GetVirtualThread(thread, &thread_vthread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: failed during JVMTI GetVirtualThread call");
  }
  if (thread_vthread == NULL) {
    fatal(jni, "event handler: JVMTI GetVirtualThread with good thread failed to return non-NULL vthread");
  }
  printf("JVMTI GetVirtualThread with good thread returned non-NULL vthread as expected\n");
}

static void
test_GetCarrierThread(jvmtiEnv *jvmti, JNIEnv *jni, jthread thread, jthread vthread, const char *event_name) {
  jthread vthread_thread = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetCarrierThread function with NULL vthread
  err = jvmti->GetCarrierThread(NULL, &vthread_thread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with NULL vthread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #2: Test JVMTI GetCarrierThread function with a bad vthread
  err = jvmti->GetCarrierThread(thread, &vthread_thread);
  if (err != JVMTI_ERROR_INVALID_THREAD) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with bad vthread failed to return JVMTI_ERROR_INVALID_THREAD");
  }

  // #3: Test JVMTI GetCarrierThread function with a good vthread
  err = jvmti->GetCarrierThread(vthread, &vthread_thread);
  if (err != JVMTI_ERROR_NONE) {
    fatal(jni, "event handler: failed during JVMTI GetCarrierThread call");
  }
  if (vthread_thread == NULL) {
    fatal(jni, "event handler: JVMTI GetCarrierThread with good vthread failed to return non-NULL carrier thread");
  }
  printf("JVMTI GetCarrierThread with good vthread returned non-NULL carrier thread as expected\n");
}

static void
test_GetThreadInfo(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *event_name) {
  jvmtiError err;
  jvmtiThreadInfo thr_info;
  jvmtiThreadGroupInfo ginfo;
  jint class_count = -1;
  jclass* classes = NULL;
  jboolean found = JNI_FALSE;

  printf("test_GetThreadInfo: started\n");

  // #1: Test JVMTI GetThreadInfo function with a good vthread
  err = jvmti->GetThreadInfo(vthread, &thr_info);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetThreadInfo returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetThreadInfo failed to return JVMTI_ERROR_NONE");
  }
  printf("GetThreadInfo: name: %s, prio: %d, is_daemon: %d\n",
         thr_info.name, thr_info.priority, thr_info.is_daemon);

  // #2: Test JVMTI GetThreadGroupInfo
  err = jvmti->GetThreadGroupInfo(thr_info.thread_group, &ginfo);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetThreadGroupInfo returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetThreadGroupInfo failed to return JVMTI_ERROR_NONE");
  }
  printf("GetThreadGroupInfo: name: %s, max prio: %d, is_daemon: %d\n",
         ginfo.name, ginfo.max_priority, ginfo.is_daemon);

  // #3: Test JVMTI GetClassLoaderClasses
  err = jvmti->GetClassLoaderClasses(thr_info.context_class_loader, &class_count, &classes);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetClassLoaderClasses returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetClassLoaderClasses failed to return JVMTI_ERROR_NONE");
  }
  printf("thr_info.context_class_loader: %p, class_count: %d\n", thr_info.context_class_loader, class_count);

  // #4: Test the thr_info.context_class_loader has the VThreadTest class
  for (int idx = 0; idx < class_count; idx++) {
    char* sign = NULL;
    err = jvmti->GetClassSignature(classes[idx], &sign, NULL);
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
test_GetFrameCount(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *event_name) {
  jint frame_count = -1;
  jvmtiError err;

  // #1: Test JVMTI GetFrameCount function with NULL count_ptr pointer
  err = jvmti->GetFrameCount(vthread, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameCount with NULL count_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameCount with NULL count_ptr pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #2: Test JVMTI GetFrameCount function with a good vthread
  err = jvmti->GetFrameCount(vthread, &frame_count);
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
test_GetFrameLocation(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *event_name, jint frame_count) {
  jmethodID method = NULL;
  jlocation location = -1;
  jvmtiError err;

  // #1: Test JVMTI GetFrameLocation function with negative frame depth
  err = jvmti->GetFrameLocation(vthread, -1, &method, &location);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetFrameLocation with negative frame depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with negative frame depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetFrameLocation function with NULL method_ptr
  err = jvmti->GetFrameLocation(vthread, 0, NULL, &location);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameLocation with NULL method_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with NULL method_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #3: Test JVMTI GetFrameLocation function with NULL location_ptr
  err = jvmti->GetFrameLocation(vthread, 0, &method, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetFrameCount with NULL location_ptr returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetFrameLocation with NULL location_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #4: Test JVMTI GetFrameLocation function with a good vthread
  if (frame_count == 0) {
    err = jvmti->GetFrameLocation(vthread, 0, &method, &location);
    if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
      printf("JVMTI GetFrameLocation for empty stack returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetFrameLocation for empty stack failed to return JVMTI_ERROR_NO_MORE_FRAMES");
    }
    printf("JVMTI GetFrameLocation for empty stack returned JVMTI_ERROR_NO_MORE_FRAMES as expected\n");
  } else {
    err = jvmti->GetFrameLocation(vthread, frame_count, &method, &location);
    if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
      printf("JVMTI GetFrameLocation for bid depth == frame_count returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetFrameLocation for too big depth failed to return JVMTI_ERROR_NO_MORE_FRAMES");
    }
    printf("JVMTI GetFrameLocation for too big depth returned JVMTI_ERROR_NO_MORE_FRAMES as expected\n");

    err = jvmti->GetFrameLocation(vthread, 1, &method, &location);
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
test_GetStackTrace(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *event_name, jint frame_count) {
  jvmtiFrameInfo frames[MAX_FRAME_COUNT];
  jint count = -1;
  jmethodID method = NULL;
  jvmtiError err;

  printf("\n");

  // #1: Test JVMTI GetStackTrace function with bad start_depth
  err = jvmti->GetStackTrace(vthread, -(frame_count + 1), MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with negative start_depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with verynegative start_depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }
  err = jvmti->GetStackTrace(vthread, (frame_count + 1), MAX_FRAME_COUNT, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with very big start_depth returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with very big start_depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetStackTrace function with negative max_frame_count
  err = jvmti->GetStackTrace(vthread, 0, -1, frames, &count);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetStackTrace with negative max_frame_count returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace with negative max_frame_count failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #3: Test JVMTI GetStackTrace function with NULL frame_buffer pointer
  err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, NULL, &count);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetStackTrace with NULL frame_buffer pointer returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace witt NULL frame_buffer pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #4: Test JVMTI GetStackTrace function with NULL count_ptr pointer
  err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, frames, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetStackTrace with NULL count_ptr pointer returned error: %d\n", err);
    fatal(jni, "event handler: JVMTI GetStackTrace witt NULL count_ptr pointer failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #5: Test JVMTI GetStackTrace function with a good vthread
  if (frame_count == 0) {
    err = jvmti->GetStackTrace(vthread, 1, MAX_FRAME_COUNT, frames, &count);
    if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
      printf("JVMTI GetStackTrace for empty stack returned error: %d\n", err);
      fatal(jni, "event handler: JVMTI GetStackTrace for empty stack failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
    }
  } else {
    err = jvmti->GetStackTrace(vthread, 0, MAX_FRAME_COUNT, frames, &count);
    if (err != JVMTI_ERROR_NONE) {
      printf("JVMTI GetStackTrace with good vthread returned error: %d\n", err);
      fatal(jni, "event handler: failed during JVMTI GetStackTrace call");
    }
    if (count <= 0) {
      fatal(jni, "event handler: JVMTI GetStackTrace with good vthread returned negative frame count\n");
    }
    print_stack_trace_frames(jvmti, jni, count, frames);
  }
}

enum Slots { SlotInvalid0 = -1, SlotObj = 0, SlotInt = 1, SlotLong = 2, SlotUnaligned = 3, SlotFloat = 4, SlotDouble = 5 };

static void
test_GetLocal(jvmtiEnv *jvmti, JNIEnv *jni, jthread cthread, jthread vthread, const char *event_name, jint frame_count) {
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

  // #0: Test JVMTI GetLocalInstance function for carrier thread
  err = jvmti->GetLocalInstance(cthread, 3, &obj);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalInstance for carrier thread top frame Continuation.run() returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalInstance failed for carrier thread top frame Continuation.run()");
  }
  printf("JVMTI GetLocalInstance succeed for carrier thread top frame Continuation.run()\n");

  depth = find_method_depth(jvmti, jni, vthread, "producer");
  if (depth == -1) {
    return; // skip testing CONSUMER vthreads wich have no producer(String msg) method
  }
  printf("Testing GetLocal<Type> for method: producer(Ljava/Lang/String;)V at depth: %d\n", depth);

  // #1: Test JVMTI GetLocalObject function with negative frame depth
  err = jvmti->GetLocalObject(vthread, -1, SlotObj, &obj);
  if (err != JVMTI_ERROR_ILLEGAL_ARGUMENT) {
    printf("JVMTI GetLocalObject with negative frame depth returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with negative frame depth failed to return JVMTI_ERROR_ILLEGAL_ARGUMENT");
  }

  // #2: Test JVMTI GetLocalObject function with big frame depth
  err = jvmti->GetLocalObject(vthread, frame_count, SlotObj, &obj);
  if (err != JVMTI_ERROR_NO_MORE_FRAMES) {
    printf("JVMTI GetLocalObject with big frame depth returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with big frame depth failed to return JVMTI_ERROR_NO_MORE_FRAMES");
  }

  // #3: Test JVMTI GetLocalObject function with invalid slot -1
  err = jvmti->GetLocalObject(vthread, depth, SlotInvalid0, &obj);
  if (err != JVMTI_ERROR_INVALID_SLOT) {
    printf("JVMTI GetLocalObject with invalid slot -1 returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with invalid slot -1 failed to return JVMTI_ERROR_INVALID_SLOT");
  }

  // #4: Test JVMTI GetLocalObject function with unaligned slot 3
  err = jvmti->GetLocalObject(vthread, depth, SlotUnaligned, &obj);
  if (err != JVMTI_ERROR_INVALID_SLOT && err != JVMTI_ERROR_TYPE_MISMATCH) {
    printf("JVMTI GetLocalObject with unaligned slot 3 returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with unaligned slot 3 failed"
               " to return JVMTI_ERROR_INVALID_SLOT or JVMTI_ERROR_TYPE_MISMATCH");
  }

  // #5: Test JVMTI GetLocalObject function with NULL value_ptr
  err = jvmti->GetLocalObject(vthread, depth, SlotObj, NULL);
  if (err != JVMTI_ERROR_NULL_POINTER) {
    printf("JVMTI GetLocalObject with NULL method_ptr returned error: %d\n", err);
    fatal(jni, "JVMTI GetLocalObject with NULL method_ptr failed to return JVMTI_ERROR_NULL_POINTER");
  }

  // #6: Test JVMTI GetLocal<Type> functions with a good vthread
  err = jvmti->GetLocalObject(vthread, depth, SlotObj, &obj);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalObject with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalObject call");
  }
  const char* str = jni->GetStringUTFChars((jstring)obj, NULL);
  printf("    local String value at slot %d: %s\n", SlotObj, str);
  const char* exp_str = "msg: ...";
  if (strncmp(str, exp_str, 5) != 0) {
    printf("    Failed: Expected local String value: %s, got: %s\n", exp_str, str);
    fatal(jni, "Got unexpected local String value");
  }
  jni->ReleaseStringUTFChars((jstring)obj, str);

  err = jvmti->GetLocalInt(vthread, depth, SlotInt, &ii);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalInt with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalInt call");
  }
  printf("    local int value at slot %d: %d\n", SlotInt, ii);
  if (ii != 1) {
    printf("    Failed: Expected local int value: 1, got %d\n", ii);
    fatal(jni, "Got unexpected local int value");
  }

  err = jvmti->GetLocalLong(vthread, depth, SlotLong, &ll);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalInt with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalInt call");
  }
  printf("    local long value at slot %d: %lld\n", SlotLong, (long long)ll);
  if (ll != 2L) {
    printf("    Failed: Expected local long value: 2L, got %lld\n", (long long)ll);
    fatal(jni, "Got unexpected local long value");
  }

  err = jvmti->GetLocalFloat(vthread, depth, SlotFloat, &ff);
  if (err != JVMTI_ERROR_NONE) {
    printf("JVMTI GetLocalFloat with good vthread returned error: %d\n", err);
    fatal(jni, "failed during JVMTI GetLocalFloat call");
  }
  printf("    local float value at slot %d: %f\n", SlotFloat, ff);
  if (ff < 3.200000 || ff > 3.200001) {
    printf("    Failed: Expected local float value: 3.200000, got %f\n", ff);
    fatal(jni, "Got unexpected local float value");
  }

  err = jvmti->GetLocalDouble(vthread, depth, SlotDouble, &dd);
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
processVThreadEvent(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, const char *event_name) {
  static int vthread_events_cnt = 0;
  char* tname = get_thread_name(jvmti, jni, vthread);
  jthread cthread = NULL;
  jvmtiError err;

  if (strcmp(event_name, "VirtualThreadTerminated") != 0 &&
      strcmp(event_name, "VirtualThreadScheduled")  != 0) {
    if (vthread_events_cnt++ > MAX_EVENTS_TO_PROCESS) {
      return; // No need to test all events
    }
  }
  printf("processVThreadEvent: event: %s, thread: %s\n", event_name, tname); fflush(0);

  err = jvmti->GetCarrierThread(vthread, &cthread);
  if (err != JVMTI_ERROR_NONE) {
    printf("processVThreadEvent: GetCarrierThread returned error code: %d\n", err);
    fatal(jni, "event handler: JVMTI GetCarrierThread failed to return JVMTI_ERROR_NONE");
  }

  print_vthread_event_info(jvmti, jni, cthread, vthread, event_name);

  deallocate(jvmti, jni, (void*)tname);

  if (strcmp(event_name, "VirtualThreadTerminated") == 0) {
    return; // skip further testing as GetVirtualThread can return NULL
  }

  test_GetVirtualThread(jvmti, jni, cthread, vthread, event_name);
  test_GetCarrierThread(jvmti, jni, cthread, vthread, event_name);

  if (strcmp(event_name, "VirtualThreadScheduled") == 0) {
    test_GetThreadInfo(jvmti, jni, vthread, event_name);
    return; // skip testing of GetFrame* for VirtualThreadScheduled events
  }
  jint frame_count = test_GetFrameCount(jvmti, jni, vthread, event_name);
  test_GetFrameLocation(jvmti, jni, vthread, event_name, frame_count);
  test_GetStackTrace(jvmti, jni, vthread, event_name, frame_count);
  test_GetLocal(jvmti, jni, cthread, vthread, event_name, frame_count);
}

static void JNICALL
VirtualThreadScheduled(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  processVThreadEvent(jvmti, jni, vthread, "VirtualThreadScheduled");
}

static void JNICALL
VirtualThreadTerminated(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  processVThreadEvent(jvmti, jni, vthread, "VirtualThreadTerminated");
}

static void JNICALL
VirtualThreadMounted(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  processVThreadEvent(jvmti, jni, vthread, "VirtualThreadMounted");
}

static void JNICALL
VirtualThreadUnmounted(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  processVThreadEvent(jvmti, jni, vthread, "VirtualThreadUnmounted");
}

static void JNICALL
ContinuationRun(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, jint frames_count) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationRun");
}

static void JNICALL
ContinuationYield(jvmtiEnv *jvmti, JNIEnv *jni, jthread vthread, jint frames_count) {
  RawMonitorLocker rml(jvmti, jni, events_monitor);
  print_cont_event_info(jvmti, jni, vthread, frames_count, "ContinuationYield");
}

extern JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *jvm, char *options,
                                           void *reserved) {
  jvmtiEventCallbacks callbacks;
  jvmtiCapabilities caps;
  jvmtiError err;

  printf("Agent_OnLoad started\n");
  if (jvm->GetEnv((void **) (&jvmti), JVMTI_VERSION) != JNI_OK) {
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
  callbacks.VirtualThreadScheduled = &VirtualThreadScheduled;
  callbacks.VirtualThreadTerminated = &VirtualThreadTerminated;
  callbacks.VirtualThreadMounted = &VirtualThreadMounted;
  callbacks.VirtualThreadUnmounted = &VirtualThreadUnmounted;

  memset(&caps, 0, sizeof(caps));
  caps.can_support_virtual_threads = 1;
  caps.can_access_local_variables = 1;
  if (continuation_events_enabled == JNI_TRUE) {
    caps.can_support_continuations = 1;
    callbacks.ContinuationRun = &ContinuationRun;
    callbacks.ContinuationYield = &ContinuationYield;
  }
  err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI AddCapabilities: %d\n", err);
  }

  err = jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks));
  if (err != JVMTI_ERROR_NONE) {
    printf("error in JVMTI SetEventCallbacks: %d\n", err);
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

  if (continuation_events_enabled == JNI_TRUE) {
    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_RUN, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    }

    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CONTINUATION_YIELD, NULL);
    if (err != JVMTI_ERROR_NONE) {
      printf("error in JVMTI SetEventNotificationMode: %d\n", err);
    }
  }

  events_monitor = create_raw_monitor(jvmti, "Events Monitor");
  printf("Agent_OnLoad finished\n");
  return 0;
}

} // extern "C"

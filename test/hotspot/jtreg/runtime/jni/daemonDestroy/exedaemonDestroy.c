/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

#include <jni.h>
#include <stdlib.h>

static JavaVMOption options[] = {
  { "-Djava.class.path=.", NULL }, // gets overwritten with real value
};

static JavaVMInitArgs vm_args = {
  JNI_VERSION_19,
  sizeof(options) / sizeof(JavaVMOption),
  options,
  JNI_FALSE
};

int main(int argc, char *argv[]) {
  JavaVM *jvm;
  JNIEnv *env;

  if (argc < 2) {
    fprintf(stderr, "Usage: main <classpath property> [daemon]\n");
    exit(1);
  }

  char* cp = argv[1];

  printf("Test using classpath: %s\n", cp);

  options[0].optionString = cp;

  jint res = JNI_CreateJavaVM(&jvm, (void**)&env, &vm_args);
  if (res != JNI_OK) {
    fprintf(stderr, "Test Error: JNI_CreateJavaVM failed: %d\n", res);
    exit(1);
  }

  jclass cls = (*env)->FindClass(env, "Main");
  if (cls == NULL) {
    fprintf(stderr, "Test Error. Can't load class Main\n");
    (*env)->ExceptionDescribe(env);
    exit(1);
  }

  jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "()V");
  if (mid == NULL) {
    fprintf(stderr, "Test Error. Can't find method main\n");
    (*env)->ExceptionDescribe(env);
    exit(1);
  }

  (*env)->CallStaticVoidMethod(env, cls, mid);

  res = (*jvm)->DetachCurrentThread(jvm);
  if (res != JNI_OK) {
    fprintf(stderr, "Test Error: DetachCurrentThread failed: %d\n", res);
    exit(1);
  }

  // Any additional arg implies to use a daemon thread.
  if (argc > 2) {
    res = (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, NULL);
    if (res != JNI_OK) {
      fprintf(stderr, "Test Error: AttachCurrentThreadAsDaemon failed: %d\n", res);
      exit(1);
    }
    puts("Test: attached as daemon");
  } else {
    res = (*jvm)->AttachCurrentThread(jvm, (void **)&env, NULL);
    if (res != JNI_OK) {
      fprintf(stderr, "Test Error: AttachCurrentThread failed: %d\n", res);
      exit(1);
    }
    puts("Test: attached as non-daemon");
  }

  puts("Test: calling DestroyJavaVM");
  res = (*jvm)->DestroyJavaVM(jvm);
  if (res != JNI_OK) {
    fprintf(stderr, "Test Error: DestroyJavaVM failed: %d\n", res);
    exit(1);
  }
  puts("Test: DestroyJavaVM returned");
}

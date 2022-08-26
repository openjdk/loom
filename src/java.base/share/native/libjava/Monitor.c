/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

#include "jni.h"
#include "jvm.h"

#include "java_lang_Monitor.h"

#define ARRAY_LENGTH(a) (sizeof(a)/sizeof(a[0]))

static JNINativeMethod methods[] = {
    {"casLockState",     "(Ljava/lang/Object;II)Z", (void *)&JVM_Monitor_casLockState},
    {"getLockState",     "(Ljava/lang/Object;)I"  , (void *)&JVM_Monitor_getLockState},
    {"getVMResult",      "()Ljava/lang/Object;"   , (void *)&JVM_Monitor_getVMResult},
    {"storeVMResult",    "(Ljava/lang/Object;)V"  , (void *)&JVM_Monitor_storeVMResult},
    {"abort",            "(Ljava/lang/String;)V"  , (void *)&JVM_Monitor_abort},
    {"log",              "(Ljava/lang/String;)V"  , (void *)&JVM_Monitor_log},
    {"logEnter",         "(Ljava/lang/Object;J)V"  , (void *)&JVM_Monitor_log_enter},
    {"logExit",          "(Ljava/lang/Object;J)V"  , (void *)&JVM_Monitor_log_exit},
    {"postJvmtiEvent",   "(ILjava/lang/Thread;Ljava/lang/Object;JZ)V", (void *)&JVM_Monitor_postJvmtiEvent}
};

JNIEXPORT void JNICALL Java_java_lang_Monitor_registerNatives(JNIEnv *env, jclass cls)
{
    (*env)->RegisterNatives(env, cls, methods, ARRAY_LENGTH(methods));
}

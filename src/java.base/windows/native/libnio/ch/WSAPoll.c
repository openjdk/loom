/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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
#include <winsock2.h>

#include "jni.h"
#include "jni_util.h"
#include "jlong.h"
#include "nio.h"
#include "sun_nio_ch_WSAPoll.h"

JNIEXPORT jint JNICALL
Java_sun_nio_ch_WSAPoll_pollfdSize(JNIEnv* env, jclass clazz)
{
    return sizeof(struct pollfd);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_WSAPoll_fdOffset(JNIEnv* env, jclass clazz)
{
    return offsetof(struct pollfd, fd);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_WSAPoll_eventsOffset(JNIEnv* env, jclass clazz)
{
    return offsetof(struct pollfd, events);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_WSAPoll_reventsOffset(JNIEnv* env, jclass clazz)
{
    return offsetof(struct pollfd, revents);
}

JNIEXPORT jint JNICALL
Java_sun_nio_ch_WSAPoll_poll(JNIEnv *env, jclass clazz,
                             jlong address, jint numfds, jint timeout)
{
    LPWSAPOLLFD a = (LPWSAPOLLFD) jlong_to_ptr(address);
    int res = WSAPoll(a, numfds, timeout);
    if (res == SOCKET_ERROR) {
        JNU_ThrowIOExceptionWithLastError(env, "WSAPoll failed");
        return IOS_THROWN;
    }
    return (jint) res;
}
/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.jfr.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class StringPool {
    public static final int MIN_LIMIT = 16;
    public static final int MAX_LIMIT = 128; /* 0 MAX means disabled */

    private static final long DO_NOT_POOL = -1;
    /* max size */
    private static final int MAX_SIZE = 32 * 1024;
    /* max size bytes */
    private static final long MAX_SIZE_UTF16 = 16 * 1024 * 1024;
    /* string id index */
    private static final AtomicLong sidIdx = new AtomicLong(1);
    /* looking at a biased data set 4 is a good value */
    private static final String[] preCache = new String[] { "", "", "", "" };
    /* the cache */
    private static final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>(MAX_SIZE, 0.75f);
    /* loop mask */
    private static final int preCacheMask = 0x03;
    /* index of oldest */
    private static int preCacheOld = 0;
    /* max size bytes */
    private static long currentSizeUTF16;

    public static void reset() {
        cache.clear();
        synchronized (StringPool.class) {
            currentSizeUTF16 = 0;
        }
    }

    public static long addString(String s) {
        Long lsid = cache.get(s);
        if (lsid != null) {
            return lsid.longValue();
        }
        if (!preCache(s)) {
            /* we should not pool this string */
            return DO_NOT_POOL;
        }
        if (cache.size() > MAX_SIZE || currentSizeUTF16 > MAX_SIZE_UTF16) {
            /* pool was full */
            reset();
        }
        return storeString(s);
    }

    private static long storeString(String s) {
        long sid = sidIdx.getAndIncrement();
        /* we can race but it is ok */
        cache.put(s, sid);
        synchronized (StringPool.class) {
            JVM.addStringConstant(sid, s);
            currentSizeUTF16 += s.length();
        }
        return sid;
    }

    private static boolean preCache(String s) {
        if (preCache[0].equals(s)) {
            return true;
        }
        if (preCache[1].equals(s)) {
            return true;
        }
        if (preCache[2].equals(s)) {
            return true;
        }
        if (preCache[3].equals(s)) {
            return true;
        }
        preCacheOld = (preCacheOld - 1) & preCacheMask;
        preCache[preCacheOld] = s;
        return false;
    }
}

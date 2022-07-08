/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#ifndef OS_WINDOWS_THREADCRASHPROTECTION_WINDOWS_HPP
#define OS_WINDOWS_THREADCRASHPROTECTION_WINDOWS_HPP

#include "memory/allocation.hpp"

class CrashProtectionCallback;
class Thread;

/*
 * Crash protection for the JfrSampler thread. Wrap the callback
 * with a __try { call() }
 * To be able to use this - don't take locks, don't rely on destructors,
 * don't make OS library calls, don't allocate memory, don't print,
 * don't call code that could leave the heap / memory in an inconsistent state,
 * or anything else where we are not in control if we suddenly jump out.
 */
class ThreadCrashProtection : public StackObj {
public:
  static bool is_crash_protected(Thread* thr) {
    return _crash_protection != NULL && _protected_thread == thr;
  }

  ThreadCrashProtection();
  bool call(CrashProtectionCallback& cb);
private:
  static Thread* _protected_thread;
  static ThreadCrashProtection* _crash_protection;
};

#endif // OS_WINDOWS_THREADCRASHPROTECTION_WINDOWS_HPP

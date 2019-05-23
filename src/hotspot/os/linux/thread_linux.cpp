/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#include "runtime/thread.hpp"

#include <sys/syscall.h>
#ifdef __NR_rseq
#include <linux/rseq.h>
#endif

#define RSEQ_SIG 0x7ff7effe

void JavaThread::pd_initialize_self() {
  assert(current() == this, "must be called on current thread");
#if defined(__NR_rseq)
  if (os::Linux::supports_rseq()) {
    // rseq
    int flags = 0;
    uint32_t sig = RSEQ_SIG;
    rs.cpu_id = RSEQ_CPU_ID_UNINITIALIZED;
    int r;
    int e;
    struct rseq *rsp = &rs;
    r = syscall(__NR_rseq, rsp, sizeof rs, flags, sig);
    e = errno;
    assert(r == 0 || errno == EBUSY, "rseq register failed");
  }
#else
  assert(!os::Linux::supports_rseq(), "that does not compute!");
#endif
}

void JavaThread::pd_destroy_self() {
  assert(current() == this, "must be called on current thread");
#if defined(__NR_rseq)
  if (os::Linux::supports_rseq()) {
    // rseq
    int flags = RSEQ_FLAG_UNREGISTER;
    uint32_t sig = RSEQ_SIG;
    int r;
    int e;
    struct rseq *rsp = &rs;
    r = syscall(__NR_rseq, rsp, sizeof rs, flags, sig);
    e = errno;
    assert(r == 0 || errno == EBUSY, "rseq unregister failed");
    rs.cpu_id = RSEQ_CPU_ID_UNINITIALIZED;
  }
#else
  assert(!os::Linux::supports_rseq(), "that does not compute!");
#endif
}

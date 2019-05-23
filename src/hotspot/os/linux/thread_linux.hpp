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

#ifndef OS_LINUX_THREAD_LINUX_HPP
#define OS_LINUX_THREAD_LINUX_HPP

 private:
#ifdef __NR_rseq
  friend class os;
  struct rseq rs;
#endif
 public:
#ifdef __NR_rseq
  static ByteSize rseq_cpuid_offset() {
    return byte_offset_of(JavaThread, rs.cpu_id);
  }
  static ByteSize rseq_cpuid_start_offset() {
    return byte_offset_of(JavaThread, rs.cpu_id_start);
  }
  static ByteSize rseq_cs_offset() {
    return byte_offset_of(JavaThread, rs.rseq_cs);
  }
#endif
  void pd_initialize_self();
#define PD_DESTROY_SELF
  void pd_destroy_self();

  void pd_linux_initialize() {
#ifdef __NR_rseq
    memset(&rs, 0, sizeof rs);
#endif
  }

#endif // OS_LINUX_THREAD_LINUX_HPP

/*
 * Copyright (c) 2011, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef OS_CPU_LINUX_X86_OS_LINUX_X86_INLINE_HPP
#define OS_CPU_LINUX_X86_OS_LINUX_X86_INLINE_HPP

#include "runtime/os.hpp"
#include "runtime/thread.hpp"

// See http://www.technovelty.org/code/c/reading-rdtsc.htl for details
inline jlong os::rdtsc() {
#ifndef AMD64
  // 64 bit result in edx:eax
  uint64_t res;
  __asm__ __volatile__ ("rdtsc" : "=A" (res));
  return (jlong)res;
#else
  uint64_t res;
  uint32_t ts1, ts2;
  __asm__ __volatile__ ("rdtsc" : "=a" (ts1), "=d" (ts2));
  res = ((uint64_t)ts1 | (uint64_t)ts2 << 32);
  return (jlong)res;
#endif // AMD64
}

#if defined(__NR_rseq)
inline bool os::compareAndSetLongCPU(JavaThread *thread, volatile jlong* addr, jlong offset, int cpu, jlong e, jlong x) {
  register void *tmp = NULL;
  __asm__ volatile goto (
  ".pushsection rseq_cs, \"ax\"\n\t"
  ".balign 32\n\t"	
  "Lcs:\n\t"
  ".long 0x0, 0x0\n\t"
  ".quad Lstart_ip, (Lpost_commit_offset-Lstart_ip), Labort\n\t"
  ".popsection\n\t"

  "leaq Lcs(%%rip), %[tmp]\n\t"
  "movq %[tmp], %[rseq_cs]\n\t"
  "Lstart_ip:\n\t"

  "cmpl %[cpu_id], %[current_cpu_id]\n\t"
  "jne %l[fail]\n\t"

  "cmpq %[mp], %[e]\n\t"
  "jne %l[fail]\n\t"

  "movq %[x], %[mp]\n\t"
  "Lpost_commit_offset:\n\t"

  ".pushsection rseq_abort, \"ax\"\n\t"
  ".long 0x7ff7effe\n\t"
  "Labort:\n\t"
  "jmp %l[fail]\n\t"
  ".popsection\n\t"
  :
  : [rseq_cs] "m" (thread->rs.rseq_cs),
    [tmp] "r" (tmp),
    [mp] "m" (*(volatile jlong *)addr),
    [offset] "r" (offset),
    [current_cpu_id] "m" (thread->rs.cpu_id),
    [cpu_id]  "r" (cpu),
    [e]  "r" (e),
    [x]  "r" (x)
  : "memory", "rax", "cc"
  : fail);
  // Need volatile barrier here?
  return true;
fail:
  return false;
}
#endif

#endif // OS_CPU_LINUX_X86_OS_LINUX_X86_INLINE_HPP

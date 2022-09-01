/*
 * Copyright (c) 1997, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef OS_WINDOWS_OS_WINDOWS_HPP
#define OS_WINDOWS_OS_WINDOWS_HPP

#include "runtime/os.hpp"

// Win32_OS defines the interface to windows operating systems

class outputStream;
class Thread;

class os::win32 {
  friend class os;
  friend unsigned __stdcall thread_native_entry(Thread*);

 protected:
  static int    _processor_type;
  static int    _processor_level;
  static julong _physical_memory;
  static bool   _is_windows_server;
  static bool   _has_exit_bug;

  static void print_windows_version(outputStream* st);
  static void print_uptime_info(outputStream* st);

  static bool platform_print_native_stack(outputStream* st, const void* context,
                                          char *buf, int buf_size);

  static bool register_code_area(char *low, char *high);

 public:
  // Windows-specific interface:
  static void   initialize_system_info();
  static void   setmode_streams();

  // Processor info as provided by NT
  static int processor_type()  { return _processor_type;  }
  static int processor_level() {
    return _processor_level;
  }
  static julong available_memory();
  static julong physical_memory() { return _physical_memory; }

  // load dll from Windows system directory or Windows directory
  static HINSTANCE load_Windows_dll(const char* name, char *ebuf, int ebuflen);

 private:
  enum Ept { EPT_THREAD, EPT_PROCESS, EPT_PROCESS_DIE };
  // Wrapper around _endthreadex(), exit() and _exit()
  static int exit_process_or_thread(Ept what, int exit_code);

  static void initialize_performance_counter();

 public:
  // Generic interface:

  // Tells whether this is a server version of Windows
  static bool is_windows_server() { return _is_windows_server; }

  // Tells whether there can be the race bug during process exit on this platform
  static bool has_exit_bug() { return _has_exit_bug; }

  // Read the headers for the executable that started the current process into
  // the structure passed in (see winnt.h).
  static void read_executable_headers(PIMAGE_NT_HEADERS);

  static bool get_frame_at_stack_banging_point(JavaThread* thread,
                          struct _EXCEPTION_POINTERS* exceptionInfo,
                          address pc, frame* fr);

  struct mapping_info_t {
    // Start of allocation (AllocationBase)
    address base;
    // Total size of allocation over all regions
    size_t size;
    // Total committed size
    size_t committed_size;
    // Number of regions
    int regions;
  };
  // Given an address p which points into an area allocated with VirtualAlloc(),
  // return information about that area.
  static bool find_mapping(address p, mapping_info_t* mapping_info);

#ifndef _WIN64
  // A wrapper to install a structured exception handler for fast JNI accessors.
  static address fast_jni_accessor_wrapper(BasicType);
#endif

  // Fast access to current thread
protected:
  static int _thread_ptr_offset;
private:
  static void initialize_thread_ptr_offset();
public:
  static inline void set_thread_ptr_offset(int offset) {
    _thread_ptr_offset = offset;
  }
  static inline int get_thread_ptr_offset() { return _thread_ptr_offset; }
};

#endif // OS_WINDOWS_OS_WINDOWS_HPP

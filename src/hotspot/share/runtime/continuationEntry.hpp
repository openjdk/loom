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
 *
 */

#ifndef SHARE_VM_RUNTIME_CONTINUATIONENTRY_HPP
#define SHARE_VM_RUNTIME_CONTINUATIONENTRY_HPP

#include "oops/oop.hpp"
#include "oops/oopsHierarchy.hpp"
#include "runtime/continuation.hpp"
#include "utilities/sizes.hpp"

class RegisterMap;
class OopMap;
class JavaThread;

// Metadata stored in the continuation entry frame
class ContinuationEntry {
#ifdef ASSERT
private:
  static const int COOKIE_VALUE = 0x1234;
  int cookie;

public:
  static int cookie_value() { return COOKIE_VALUE; }
  static ByteSize cookie_offset() { return byte_offset_of(ContinuationEntry, cookie); }

  void verify_cookie() {
    assert(cookie == COOKIE_VALUE, "Bad cookie: %#x, expected: %#x", cookie, COOKIE_VALUE);
  }
#endif

public:
  static int _return_pc_offset; // friend gen_continuation_enter
  static void set_enter_code(CompiledMethod* nm); // friend SharedRuntime::generate_native_wrapper

private:
  static address _return_pc;

private:
  ContinuationEntry* _parent;
  oopDesc* _cont;
  oopDesc* _chunk;
  int _flags;
  int _argsize;
  intptr_t* _parent_cont_fastpath;
  int _parent_held_monitor_count;
  uint _pin_count;

public:
  static ByteSize parent_offset()   { return byte_offset_of(ContinuationEntry, _parent); }
  static ByteSize cont_offset()     { return byte_offset_of(ContinuationEntry, _cont); }
  static ByteSize chunk_offset()    { return byte_offset_of(ContinuationEntry, _chunk); }
  static ByteSize flags_offset()    { return byte_offset_of(ContinuationEntry, _flags); }
  static ByteSize argsize_offset()  { return byte_offset_of(ContinuationEntry, _argsize); }
  static ByteSize pin_count_offset(){ return byte_offset_of(ContinuationEntry, _pin_count); }
  static ByteSize parent_cont_fastpath_offset()      { return byte_offset_of(ContinuationEntry, _parent_cont_fastpath); }
  static ByteSize parent_held_monitor_count_offset() { return byte_offset_of(ContinuationEntry, _parent_held_monitor_count); }

  static void setup_oopmap(OopMap* map);

public:
  static size_t size() { return align_up((int)sizeof(ContinuationEntry), 2*wordSize); }

  ContinuationEntry* parent() const { return _parent; }
  int parent_held_monitor_count() const { return _parent_held_monitor_count; }

  static address entry_pc() { return _return_pc; }
  intptr_t* entry_sp() const { return (intptr_t*)this; }
  intptr_t* entry_fp() const;

  int argsize() const { return _argsize; }
  void set_argsize(int value) { _argsize = value; }

  bool is_pinned() { return _pin_count > 0; }
  bool pin() {
    if (_pin_count == UINT_MAX) return false;
    _pin_count++;
    return true;
  }
  bool unpin() {
    if (_pin_count == 0) return false;
    _pin_count--;
    return true;
  }

  intptr_t* parent_cont_fastpath() const { return _parent_cont_fastpath; }
  void set_parent_cont_fastpath(intptr_t* x) { _parent_cont_fastpath = x; }

  static ContinuationEntry* from_frame(const frame& f);
  frame to_frame() const;
  void update_register_map(RegisterMap* map) const;
  void flush_stack_processing(JavaThread* thread) const;

  intptr_t* bottom_sender_sp() const {
    intptr_t* sp = entry_sp() - argsize();
#ifdef _LP64
    sp = align_down(sp, frame::frame_alignment);
#endif
    return sp;
  }

  inline oop cont_oop() const;
  inline oop scope() const;
  inline static oop cont_oop_or_null(const ContinuationEntry* ce);

  bool is_virtual_thread() const { return _flags != 0; }

#ifndef PRODUCT
  void describe(FrameValues& values, int frame_no) const {
    address usp = (address)this;
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_offset())),    "parent");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::cont_offset())),      "continuation");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::flags_offset())),     "flags");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::chunk_offset())),     "chunk");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::argsize_offset())),   "argsize");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::pin_count_offset())), "pin_count");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_cont_fastpath_offset())),      "parent fastpath");
    values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_held_monitor_count_offset())), "parent held monitor count");
  }
#endif

#ifdef ASSERT
  static bool assert_entry_frame_laid_out(JavaThread* thread);
#endif
};

#endif // SHARE_VM_RUNTIME_CONTINUATIONENTRY_HPP

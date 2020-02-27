/*
 * Copyright (c) 2011, 2020, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "jfr/recorder/checkpoint/jfrCheckpointWriter.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceId.inline.hpp"
#include "jfr/recorder/repository/jfrChunkWriter.hpp"
#include "jfr/recorder/stacktrace/jfrStackTrace.hpp"
#include "jfr/support/jfrMethodLookup.hpp"

#include "memory/allocation.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/vframe.inline.hpp"

static void copy_frames(JfrStackFrame** lhs_frames, u4 length, const JfrStackFrame* rhs_frames) {
  assert(lhs_frames != NULL, "invariant");
  assert(rhs_frames != NULL, "invariant");
  if (length > 0) {
    *lhs_frames = NEW_C_HEAP_ARRAY(JfrStackFrame, length, mtTracing);
    memcpy(*lhs_frames, rhs_frames, length * sizeof(JfrStackFrame));
  }
}

JfrStackFrame::JfrStackFrame(const traceid& id, int bci, int type, const InstanceKlass* ik) :
  _klass(ik), _methodid(id), _line(0), _bci(bci), _type(type) {}

JfrStackFrame::JfrStackFrame(const traceid& id, int bci, int type, int lineno, const InstanceKlass* ik) :
  _klass(ik), _methodid(id), _line(lineno), _bci(bci), _type(type) {}

JfrStackTrace::JfrStackTrace(JfrStackFrame* frames, u4 max_frames) :
  _next(NULL),
  _frames(frames),
  _id(0),
  _hash(0),
  _nr_of_frames(0),
  _max_frames(max_frames),
  _frames_ownership(false),
  _reached_root(false),
  _lineno(false),
  _written(false) {}

JfrStackTrace::JfrStackTrace(traceid id, const JfrStackTrace& trace, const JfrStackTrace* next) :
  _next(next),
  _frames(NULL),
  _id(id),
  _hash(trace._hash),
  _nr_of_frames(trace._nr_of_frames),
  _max_frames(trace._max_frames),
  _frames_ownership(true),
  _reached_root(trace._reached_root),
  _lineno(trace._lineno),
  _written(false) {
  copy_frames(&_frames, trace._nr_of_frames, trace._frames);
}

JfrStackTrace::~JfrStackTrace() {
  if (_frames_ownership) {
    FREE_C_HEAP_ARRAY(JfrStackFrame, _frames);
  }
}

template <typename Writer>
static void write_stacktrace(Writer& w, traceid id, bool reached_root, u4 nr_of_frames, const JfrStackFrame* frames) {
  w.write((u8)id);
  w.write((u1)!reached_root);
  w.write(nr_of_frames);
  for (u4 i = 0; i < nr_of_frames; ++i) {
    frames[i].write(w);
  }
}

void JfrStackTrace::write(JfrChunkWriter& sw) const {
  assert(!_written, "invariant");
  write_stacktrace(sw, _id, _reached_root, _nr_of_frames, _frames);
  _written = true;
}

void JfrStackTrace::write(JfrCheckpointWriter& cpw) const {
  write_stacktrace(cpw, _id, _reached_root, _nr_of_frames, _frames);
}

bool JfrStackFrame::equals(const JfrStackFrame& rhs) const {
  return _methodid == rhs._methodid && _bci == rhs._bci && _type == rhs._type;
}

bool JfrStackTrace::equals(const JfrStackTrace& rhs) const {
  if (_reached_root != rhs._reached_root || _nr_of_frames != rhs._nr_of_frames || _hash != rhs._hash) {
    return false;
  }
  for (u4 i = 0; i < _nr_of_frames; ++i) {
    if (!_frames[i].equals(rhs._frames[i])) {
      return false;
    }
  }
  return true;
}

template <typename Writer>
static void write_frame(Writer& w, traceid methodid, int line, int bci, u1 type) {
  w.write((u8)methodid);
  w.write((u4)line);
  w.write((u4)bci);
  w.write((u8)type);
}

void JfrStackFrame::write(JfrChunkWriter& cw) const {
  write_frame(cw, _methodid, _line, _bci, _type);
}

void JfrStackFrame::write(JfrCheckpointWriter& cpw) const {
  write_frame(cpw, _methodid, _line, _bci, _type);
}

class JfrVframeStream : public vframeStreamCommon {
 private:
  oop _continuation;
  oop _continuation_scope;
  bool _continuation_scope_end_condition;
  bool _async_mode;

  bool at_continuation_entry_frame() const;
  bool at_continuation_scope_entry_frame();
  void set_parent_continuation();
  void set_continuation_scope();
  void seek_stable_frame();
  bool get_sender_frame();
  void up();
  DEBUG_ONLY(void assert_continuation_state() const;)
 public:
  JfrVframeStream(JavaThread* jt, const frame& fr, bool async_mode);
  void next_vframe();
  bool continuation_scope_end_condition() const;
};

inline void JfrVframeStream::seek_stable_frame() {
  while (!fill_from_frame()) {
    if (at_continuation_entry_frame()) {
      set_parent_continuation();
    }
    get_sender_frame();
  }
}

JfrVframeStream::JfrVframeStream(JavaThread* jt, const frame& fr, bool async_mode) : vframeStreamCommon(RegisterMap(jt, false, true)),
_continuation(jt->last_continuation()), _continuation_scope(NULL), _continuation_scope_end_condition(false), _async_mode(async_mode) {
  _stop_at_java_call_stub = false;
  _frame = fr;
  seek_stable_frame();
  if (at_continuation_entry_frame()) {
    set_parent_continuation();
    if (get_sender_frame()) {
      seek_stable_frame();
    }
  }
  set_continuation_scope();
  DEBUG_ONLY(assert_continuation_state();)
}

inline bool JfrVframeStream::at_continuation_entry_frame() const {
  return _continuation != (oop)NULL && Continuation::is_continuation_entry_frame(_frame, &_reg_map);
}

inline void JfrVframeStream::set_parent_continuation() {
  _continuation = java_lang_Continuation::parent(_continuation);
}

inline void JfrVframeStream::set_continuation_scope() {
  if (_continuation != (oop)NULL) {
    _continuation_scope = java_lang_Continuation::scope(_continuation);
  }
}

#ifdef ASSERT
void JfrVframeStream::assert_continuation_state() const {
  assert(_reg_map.cont() == (oop)NULL || (_continuation == _reg_map.cont()),
    "map.cont: " INTPTR_FORMAT " JfrVframeStream: " INTPTR_FORMAT,
    p2i((oopDesc*)_reg_map.cont()), p2i((oopDesc*)_continuation));
}
#endif

inline bool JfrVframeStream::at_continuation_scope_entry_frame() {
  assert(_continuation_scope == (oop)NULL || _continuation != (oop)NULL, "must be");
  if (!at_continuation_entry_frame()) {
    return false;
  }
  if (_continuation_scope != (oop)NULL && java_lang_Continuation::scope(_continuation) == _continuation_scope) {
    assert(Continuation::is_frame_in_continuation(_frame, _continuation), "");
    return true;
  }
  set_parent_continuation();
  // set_continuation_scope();
  DEBUG_ONLY(assert_continuation_state();)
  return false;
}

inline bool JfrVframeStream::get_sender_frame() {
  if (_async_mode && !_frame.safe_for_sender(_thread)) {
    _mode = at_end_mode;
    return false;
  }
  _frame = _frame.sender(&_reg_map);
  return true;
}

inline void JfrVframeStream::up() {
  do {
    if (at_continuation_scope_entry_frame()) {
      _continuation_scope_end_condition = true;
      _mode = at_end_mode;
      return;
    }
  } while (get_sender_frame() && !fill_from_frame());
}

// Solaris SPARC Compiler1 needs an additional check on the grandparent
// of the top_frame when the parent of the top_frame is interpreted and
// the grandparent is compiled. However, in this method we do not know
// the relationship of the current _frame relative to the top_frame so
// we implement a more broad sanity check. When the previous callee is
// interpreted and the current sender is compiled, we verify that the
// current sender is also walkable. If it is not walkable, then we mark
// the current vframeStream as at the end.
void JfrVframeStream::next_vframe() {
  // handle frames with inlining
  if (_mode == compiled_mode && fill_in_compiled_inlined_sender()) {
    return;
  }
  // handle general case
  up();
}

inline bool JfrVframeStream::continuation_scope_end_condition() const {
  assert(_mode == at_end_mode, "invariant");
  return _continuation_scope_end_condition;
}

inline bool is_virtual(JavaThread* jt) {
  assert(jt != NULL, "invariant");
  assert(jt->threadObj() != (oop)NULL, "invariant");
  return java_lang_Thread::vthread(jt->threadObj()) != NULL;
}

bool JfrStackTrace::record(JavaThread* jt, const frame& frame, int skip, bool async_mode, bool* virtual_thread) {
  assert(jt != NULL, "invariant");
  HandleMark hm; // TODO: RegisterMap uses Handles for continuations. But some callers here have NoHandleMark set.
  JfrVframeStream vfs(jt, frame, async_mode);
  u4 count = 0;
  _reached_root = true;
  for (int i = 0; i < skip; ++i) {
    if (vfs.at_end()) {
      break;
    }
    vfs.next_vframe();
  }
  while (!vfs.at_end()) {
    if (count >= _max_frames) {
      _reached_root = false;
      break;
    }
    const Method* method = vfs.method();
    if (async_mode) {
      if (!Method::is_valid_method(method)) {
        // we throw away everything we've gathered
        // in this sample since none of it is safe
        return false;
      }
    }
    const traceid mid = JfrTraceId::use(method);
    int type = vfs.is_interpreted_frame() ? JfrStackFrame::FRAME_INTERPRETER : JfrStackFrame::FRAME_JIT;
    int bci = 0;
    if (method->is_native()) {
      type = JfrStackFrame::FRAME_NATIVE;
    } else {
      bci = vfs.bci();
    }
    // Can we determine if it's inlined?
    _hash = (_hash << 2) + (unsigned int)(((size_t)mid >> 2) + (bci << 4) + type);
    _frames[count] = JfrStackFrame(mid, bci, type, async_mode ? method->line_number_from_bci(bci) : 0, method->method_holder());
    vfs.next_vframe();
    count++;
  }
  if (async_mode) {
    assert(virtual_thread != NULL, "invariant");
    assert(_lineno, "invarinat");
    *virtual_thread = _reached_root ? vfs.continuation_scope_end_condition() : is_virtual(jt);
  }
  _nr_of_frames = count;
  return count > 0;
}

bool JfrStackTrace::record_async(JavaThread* other_thread, frame& frame, bool* virtual_thread) {
  assert(other_thread != NULL, "invariant");
  assert(other_thread != Thread::current(), "invariant");
  assert(virtual_thread != NULL, "invariant");
  _lineno = true;
  return record(other_thread, frame, 0, true, virtual_thread);
}

bool JfrStackTrace::record(JavaThread* current_thread, int skip) {
  assert(current_thread != NULL, "invariant");
  assert(current_thread == Thread::current(), "invariant");
  if (!current_thread->has_last_Java_frame()) {
    return false;
  }
  _lineno = false;
  return record(current_thread, current_thread->last_frame(), skip, false, NULL);
}

void JfrStackFrame::resolve_lineno() const {
  assert(_klass, "no klass pointer");
  assert(_line == 0, "already have linenumber");
  const Method* const method = JfrMethodLookup::lookup(_klass, _methodid);
  assert(method != NULL, "invariant");
  assert(method->method_holder() == _klass, "invariant");
  _line = method->line_number_from_bci(_bci);
}

void JfrStackTrace::resolve_linenos() const {
  assert(!_lineno, "invariant");
  for (unsigned int i = 0; i < _nr_of_frames; i++) {
    _frames[i].resolve_lineno();
  }
  _lineno = true;
}


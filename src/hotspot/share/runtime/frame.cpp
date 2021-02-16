/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/moduleEntry.hpp"
#include "code/codeCache.hpp"
#include "code/scopeDesc.hpp"
#include "code/vmreg.inline.hpp"
#include "compiler/abstractCompiler.hpp"
#include "compiler/disassembler.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/oopMapCache.hpp"
#include "logging/log.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/markWord.hpp"
#include "oops/method.hpp"
#include "oops/methodData.hpp"
#include "oops/oop.inline.hpp"
#include "oops/verifyOopClosure.hpp"
#include "prims/methodHandles.hpp"
#include "runtime/continuation.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/monitorChunk.hpp"
#include "runtime/os.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/signature.hpp"
#include "runtime/stackValue.hpp"
#include "runtime/stubCodeGenerator.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/thread.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/decoder.hpp"
#include "utilities/formatBuffer.hpp"

RegisterMap::RegisterMap(JavaThread *thread, bool update_map, bool process_frames, bool walk_cont, bool validate_oops) :
    _cont(Handle()) {
  _thread         = thread;
  _update_map     = update_map;
  _validate_oops  = validate_oops;
  _walk_cont      = walk_cont;
  DEBUG_ONLY(_skip_missing = false;)
  _process_frames = process_frames;
  clear();
  debug_only(_update_for_id = NULL;)

  _on_hstack = false;
  _in_chunk = false;
  if (walk_cont && thread != NULL && thread->last_continuation() != NULL) {
      _cont = Handle(Thread::current(), thread->last_continuation()->cont_oop());
  }

#ifndef PRODUCT
  for (int i = 0; i < reg_count ; i++ ) _location[i] = NULL;
#endif /* PRODUCT */
}

RegisterMap::RegisterMap(Handle cont, bool update_map, bool process_frames, bool validate_oops) 
  : _cont(cont) {
  _thread         = NULL;
  _update_map     = update_map;
  _validate_oops  = validate_oops;
  _walk_cont      = true;
  DEBUG_ONLY(_skip_missing = false;)
    _process_frames = process_frames;
  clear();
  debug_only(_update_for_id = NULL;)

  _on_hstack = false;
  _in_chunk = false;

#ifndef PRODUCT
  for (int i = 0; i < reg_count ; i++ ) _location[i] = NULL;
#endif /* PRODUCT */
}

RegisterMap::RegisterMap(const RegisterMap* map) {
  assert(map != this, "bad initialization parameter");
  assert(map != NULL, "RegisterMap must be present");
  _thread                = map->thread();
  _update_map            = map->update_map();
  _process_frames        = map->process_frames();
  _include_argument_oops = map->include_argument_oops();
  debug_only(_update_for_id = map->_update_for_id;)
  _validate_oops = map->_validate_oops;
  _walk_cont     = map->_walk_cont;
  DEBUG_ONLY(_skip_missing = map->_skip_missing;)

  // only the original RegisterMap's handle lives long enough for StackWalker; this is bound to cause trouble with nested continuations.
  _cont = map->_cont; // !map->_cont.is_null() && map->_cont() != NULL ? Handle(Thread::current(), map->_cont()) : Handle();
  _on_hstack = map->_on_hstack;
  _in_chunk = map->_in_chunk;

  pd_initialize_from(map);
  if (update_map()) {
    for(int i = 0; i < location_valid_size; i++) {
      LocationValidType bits = map->_location_valid[i];
      _location_valid[i] = bits;
      // for whichever bits are set, pull in the corresponding map->_location
      int j = i*location_valid_type_size;
      while (bits != 0) {
        if ((bits & 1) != 0) {
          assert(0 <= j && j < reg_count, "range check");
          _location[j] = map->_location[j];
        }
        bits >>= 1;
        j += 1;
      }
    }
  }
}

void RegisterMap::set_in_cont(bool on_hstack, bool in_chunk) {
   assert (_walk_cont, ""); 
   assert (!in_chunk || on_hstack, "");
   _on_hstack = (int)on_hstack;
   _in_chunk = (int)in_chunk;
   log_trace(jvmcont)("set_in_cont on_hstack: %d in_chunk: %d", on_hstack, in_chunk);
}

void RegisterMap::set_cont(oop cont) {
  assert (_walk_cont, "");
  assert (oopDesc::is_oop_or_null(cont), "");
  assert (_cont.not_null(), "");
  log_trace(jvmcont)("set_cont " INTPTR_FORMAT, p2i((oopDesc*)cont));
  *(_cont.raw_value()) = cont; // reuse handle. see comment above in the constructor
}

void RegisterMap::clear() {
  set_include_argument_oops(true);
  if (update_map()) {
    for(int i = 0; i < location_valid_size; i++) {
      _location_valid[i] = 0;
    }
    pd_clear();
  } else {
    pd_initialize();
  }
}

#ifndef PRODUCT

VMReg RegisterMap::find_register_spilled_here(void* p) {
  for(int i = 0; i < RegisterMap::reg_count; i++) {
    VMReg r = VMRegImpl::as_VMReg(i);
    if (p == location(r)) return r;
  }
  return NULL;
}

void RegisterMap::print_on(outputStream* st) const {
  st->print_cr("Register map");
  for(int i = 0; i < reg_count; i++) {

    VMReg r = VMRegImpl::as_VMReg(i);
    intptr_t* src = (intptr_t*) location(r);
    if (src != NULL) {

      r->print_on(st);
      st->print(" [" INTPTR_FORMAT "] = ", p2i(src));
      if (((uintptr_t)src & (sizeof(*src)-1)) != 0) {
        st->print_cr("<misaligned>");
      } else {
        st->print_cr(INTPTR_FORMAT, *src);
      }
    }
  }
}

void RegisterMap::print() const {
  print_on(tty);
}

#endif
// This returns the pc that if you were in the debugger you'd see. Not
// the idealized value in the frame object. This undoes the magic conversion
// that happens for deoptimized frames. In addition it makes the value the
// hardware would want to see in the native frame. The only user (at this point)
// is deoptimization. It likely no one else should ever use it.

address frame::raw_pc() const {
  if (is_deoptimized_frame()) {
    CompiledMethod* cm = cb()->as_compiled_method_or_null();
    if (cm->is_method_handle_return(pc()))
      return cm->deopt_mh_handler_begin() - pc_return_offset;
    else
      return cm->deopt_handler_begin() - pc_return_offset;
  } else {
    return (pc() - pc_return_offset);
  }
}

// Change the pc in a frame object. This does not change the actual pc in
// actual frame. To do that use patch_pc.
//
void frame::set_pc(address   newpc ) {
#ifdef ASSERT
  if (_cb != NULL && _cb->is_nmethod()) {
    assert(!((nmethod*)_cb)->is_deopt_pc(_pc), "invariant violation");
  }
#endif // ASSERT

  // Unsafe to use the is_deoptimzed tester after changing pc
  _deopt_state = unknown;
  _pc = newpc;
  _cb = CodeCache::find_blob_unsafe(_pc);

}

void frame::set_pc_preserve_deopt(address newpc) {
  set_pc_preserve_deopt(newpc, CodeCache::find_blob_unsafe(newpc));
}

void frame::set_pc_preserve_deopt(address newpc, CodeBlob* cb) {
#ifdef ASSERT
  if (_cb != NULL && _cb->is_nmethod()) {
    assert(!((nmethod*)_cb)->is_deopt_pc(_pc), "invariant violation");
  }
#endif // ASSERT

  _pc = newpc;
  _cb = cb;
}

// type testers
bool frame::is_ignored_frame() const {
  return false;  // FIXME: some LambdaForm frames should be ignored
}

bool frame::is_native_frame() const {
  return (_cb != NULL &&
          _cb->is_nmethod() &&
          ((nmethod*)_cb)->is_native_method());
}

bool frame::is_java_frame() const {
  if (is_interpreted_frame()) return true;
  if (is_compiled_frame())    return true;
  return false;
}

bool frame::is_runtime_frame() const {
  return (_cb != NULL && _cb->is_runtime_stub());
}

bool frame::is_safepoint_blob_frame() const {
  return (_cb != NULL && _cb->is_safepoint_stub());
}

// testers

bool frame::is_first_java_frame() const {
  RegisterMap map(JavaThread::current(), false); // No update
  frame s;
  for (s = sender(&map); !(s.is_java_frame() || s.is_first_frame()); s = s.sender(&map));
  return s.is_first_frame();
}


bool frame::entry_frame_is_first() const {
  return entry_frame_call_wrapper()->is_first_frame();
}

JavaCallWrapper* frame::entry_frame_call_wrapper_if_safe(JavaThread* thread) const {
  JavaCallWrapper** jcw = entry_frame_call_wrapper_addr();
  address addr = (address) jcw;

  // addr must be within the usable part of the stack
  if (thread->is_in_usable_stack(addr)) {
    return *jcw;
  }

  return NULL;
}

bool frame::is_entry_frame_valid(JavaThread* thread) const {
  // Validate the JavaCallWrapper an entry frame must have
  address jcw = (address)entry_frame_call_wrapper();
  if (!thread->is_in_stack_range_excl(jcw, (address)fp())) {
    return false;
  }

  // Validate sp saved in the java frame anchor
  JavaFrameAnchor* jfa = entry_frame_call_wrapper()->anchor();
  return (jfa->last_Java_sp() > sp());
}

bool frame::should_be_deoptimized() const {
  if (_deopt_state == is_deoptimized ||
      !is_compiled_frame() ) return false;
  assert(_cb != NULL && _cb->is_compiled(), "must be an nmethod");
  CompiledMethod* nm = (CompiledMethod *)_cb;
  if (TraceDependencies) {
    tty->print("checking (%s) ", nm->is_marked_for_deoptimization() ? "true" : "false");
    nm->print_value_on(tty);
    tty->cr();
  }

  if( !nm->is_marked_for_deoptimization() )
    return false;

  // If at the return point, then the frame has already been popped, and
  // only the return needs to be executed. Don't deoptimize here.
  return !nm->is_at_poll_return(pc());
}

bool frame::can_be_deoptimized() const {
  if (!is_compiled_frame()) return false;
  CompiledMethod* nm = (CompiledMethod*)_cb;

  if( !nm->can_be_deoptimized() )
    return false;

  // address* pc_addr = &(((address*) sp())[-1]); // TODO: PLATFORM
  // if (Continuation::is_return_barrier_entry(*pc_addr)) {
  //   log_trace(jvmcont)("Can't deopt entry:");
  //   if (log_is_enabled(Trace, jvmcont)) {
  //     print_value_on(tty, NULL);
  //   }
  //   return false;
  // }

  return !nm->is_at_poll_return(pc());
}

void frame::deoptimize(JavaThread* thread) {
  // tty->print_cr(">>> frame::deoptimize");
  // print_on(tty);
  assert(thread == NULL
         || (thread->frame_anchor()->has_last_Java_frame() &&
             thread->frame_anchor()->walkable()), "must be");
  // Schedule deoptimization of an nmethod activation with this frame.
  assert(_cb != NULL && _cb->is_compiled(), "must be");

  // log_develop_trace(jvmcont)(">>>> frame::deoptimize %ld", os::current_thread_id());
  // tty->print_cr(">>>> frame::deoptimize: %ld", os::current_thread_id()); print_on(tty);

  // If the call site is a MethodHandle call site use the MH deopt
  // handler.
  CompiledMethod* cm = (CompiledMethod*) _cb;
  address deopt = cm->is_method_handle_return(pc()) ?
                        cm->deopt_mh_handler_begin() :
                        cm->deopt_handler_begin();

  // Save the original pc before we patch in the new one
  cm->set_original_pc(this, pc());
  patch_pc(thread, deopt);
  assert(is_deoptimized_frame(), "must be");

#ifdef ASSERT
  if (thread != NULL) {
    frame check = thread->last_frame();
    if (is_older(check.id())) {
      RegisterMap map(thread, false);
      while (id() != check.id()) {
        check = check.sender(&map);
      }
      assert(check.is_deoptimized_frame(), "missed deopt");
    }
  }
#endif // ASSERT
}

frame frame::java_sender() const {
  RegisterMap map(JavaThread::current(), false);
  frame s;
  for (s = sender(&map); !(s.is_java_frame() || s.is_first_frame()); s = s.sender(&map)) ;
  guarantee(s.is_java_frame(), "tried to get caller of first java frame");
  return s;
}

frame frame::real_sender(RegisterMap* map) const {
  frame result = sender(map);
  while (result.is_runtime_frame() ||
         result.is_ignored_frame()) {
    result = result.sender(map);
  }
  return result;
}

// Interpreter frames


void frame::interpreter_frame_set_locals(intptr_t* locs)  {
  assert(is_interpreted_frame(), "Not an interpreted frame");
  *interpreter_frame_locals_addr() = locs;
}

Method* frame::interpreter_frame_method() const {
  assert(is_interpreted_frame(), "interpreted frame expected");
  Method* m = *interpreter_frame_method_addr();
  assert(m->is_method(), "not a Method*");
  return m;
}

void frame::interpreter_frame_set_method(Method* method) {
  assert(is_interpreted_frame(), "interpreted frame expected");
  *interpreter_frame_method_addr() = method;
}

void frame::interpreter_frame_set_mirror(oop mirror) {
  assert(is_interpreted_frame(), "interpreted frame expected");
  *interpreter_frame_mirror_addr() = mirror;
}

jint frame::interpreter_frame_bci() const {
  assert(is_interpreted_frame(), "interpreted frame expected");
  address bcp = interpreter_frame_bcp();
  return interpreter_frame_method()->bci_from(bcp);
}

address frame::interpreter_frame_bcp() const {
  assert(is_interpreted_frame(), "interpreted frame expected");
  address bcp = (address)*interpreter_frame_bcp_addr();
  return interpreter_frame_method()->bcp_from(bcp);
}

void frame::interpreter_frame_set_bcp(address bcp) {
  assert(is_interpreted_frame(), "interpreted frame expected");
  *interpreter_frame_bcp_addr() = (intptr_t)bcp;
}

address frame::interpreter_frame_mdp() const {
  assert(ProfileInterpreter, "must be profiling interpreter");
  assert(is_interpreted_frame(), "interpreted frame expected");
  return (address)*interpreter_frame_mdp_addr();
}

void frame::interpreter_frame_set_mdp(address mdp) {
  assert(is_interpreted_frame(), "interpreted frame expected");
  assert(ProfileInterpreter, "must be profiling interpreter");
  *interpreter_frame_mdp_addr() = (intptr_t)mdp;
}

BasicObjectLock* frame::next_monitor_in_interpreter_frame(BasicObjectLock* current) const {
  assert(is_interpreted_frame(), "Not an interpreted frame");
#ifdef ASSERT
  interpreter_frame_verify_monitor(current);
#endif
  BasicObjectLock* next = (BasicObjectLock*) (((intptr_t*) current) + interpreter_frame_monitor_size());
  return next;
}

BasicObjectLock* frame::previous_monitor_in_interpreter_frame(BasicObjectLock* current) const {
  assert(is_interpreted_frame(), "Not an interpreted frame");
#ifdef ASSERT
//   // This verification needs to be checked before being enabled
//   interpreter_frame_verify_monitor(current);
#endif
  BasicObjectLock* previous = (BasicObjectLock*) (((intptr_t*) current) - interpreter_frame_monitor_size());
  return previous;
}

// Interpreter locals and expression stack locations.

intptr_t* frame::interpreter_frame_local_at(int index) const {
  const int n = Interpreter::local_offset_in_bytes(index)/wordSize;
  return &((*interpreter_frame_locals_addr())[n]);
}

intptr_t* frame::interpreter_frame_expression_stack_at(jint offset) const {
  const int i = offset * interpreter_frame_expression_stack_direction();
  const int n = i * Interpreter::stackElementWords;
  return &(interpreter_frame_expression_stack()[n]);
}

jint frame::interpreter_frame_expression_stack_size() const {
  // Number of elements on the interpreter expression stack
  // Callers should span by stackElementWords
  int element_size = Interpreter::stackElementWords;
  size_t stack_size = 0;
  if (frame::interpreter_frame_expression_stack_direction() < 0) {
    stack_size = (interpreter_frame_expression_stack() -
                  interpreter_frame_tos_address() + 1)/element_size;
  } else {
    stack_size = (interpreter_frame_tos_address() -
                  interpreter_frame_expression_stack() + 1)/element_size;
  }
  assert( stack_size <= (size_t)max_jint, "stack size too big");
  return ((jint)stack_size);
}


// (frame::interpreter_frame_sender_sp accessor is in frame_<arch>.cpp)

const char* frame::print_name() const {
  if (is_native_frame())      return "Native";
  if (is_interpreted_frame()) return "Interpreted";
  if (is_compiled_frame()) {
    if (is_deoptimized_frame()) return "Deoptimized";
    return "Compiled";
  }
  if (sp() == NULL)            return "Empty";
  return "C";
}

void frame::print_value_on(outputStream* st, JavaThread *thread) const {
  NOT_PRODUCT(address begin = pc()-40;)
  NOT_PRODUCT(address end   = NULL;)

  st->print("%s frame (sp=" INTPTR_FORMAT " unextended sp=" INTPTR_FORMAT, print_name(), p2i(sp()), p2i(unextended_sp()));
  if (sp() != NULL)
    st->print(", fp=" INTPTR_FORMAT ", real_fp=" INTPTR_FORMAT ", pc=" INTPTR_FORMAT,
              p2i(fp()), p2i(real_fp()), p2i(pc()));

  if (StubRoutines::contains(pc())) {
    st->print_cr(")");
    st->print("(");
    StubCodeDesc* desc = StubCodeDesc::desc_for(pc());
    st->print("~Stub::%s", desc->name());
    NOT_PRODUCT(begin = desc->begin(); end = desc->end();)
  } else if (Interpreter::contains(pc())) {
    st->print_cr(")");
    st->print("(");
    InterpreterCodelet* desc = Interpreter::codelet_containing(pc());
    if (desc != NULL) {
      st->print("~");
      desc->print_on(st);
      NOT_PRODUCT(begin = desc->code_begin(); end = desc->code_end();)
    } else {
      st->print("~interpreter");
    }
  }
  st->print_cr(")");

  if (_cb != NULL) {
    st->print("     ");
    _cb->print_value_on(st);
    st->cr();
#ifndef PRODUCT
    if (end == NULL) {
      begin = _cb->code_begin();
      end   = _cb->code_end();
    }
#endif
  }
  NOT_PRODUCT(if (WizardMode && Verbose) Disassembler::decode(begin, end);)
}


void frame::print_on(outputStream* st) const {
  print_value_on(st,NULL);
  if (is_interpreted_frame()) {
    interpreter_frame_print_on(st);
  }
}


void frame::interpreter_frame_print_on(outputStream* st) const {
#ifndef PRODUCT
  assert(is_interpreted_frame(), "Not an interpreted frame");
  jint i;
  for (i = 0; i < interpreter_frame_method()->max_locals(); i++ ) {
    intptr_t x = *interpreter_frame_local_at(i);
    st->print(" - local  [" INTPTR_FORMAT "]", x);
    st->fill_to(23);
    st->print_cr("; #%d", i);
  }
  for (i = interpreter_frame_expression_stack_size() - 1; i >= 0; --i ) {
    intptr_t x = *interpreter_frame_expression_stack_at(i);
    st->print(" - stack  [" INTPTR_FORMAT "]", x);
    st->fill_to(23);
    st->print_cr("; #%d", i);
  }
  // locks for synchronization
  for (BasicObjectLock* current = interpreter_frame_monitor_end();
       current < interpreter_frame_monitor_begin();
       current = next_monitor_in_interpreter_frame(current)) {
    st->print(" - obj    [");
    current->obj()->print_value_on(st);
    st->print_cr("]");
    st->print(" - lock   [");
    current->lock()->print_on(st, current->obj());
    st->print_cr("]");
  }
  // monitor
  st->print_cr(" - monitor[" INTPTR_FORMAT "]", p2i(interpreter_frame_monitor_begin()));
  // bcp
  st->print(" - bcp    [" INTPTR_FORMAT "]", p2i(interpreter_frame_bcp()));
  st->fill_to(23);
  st->print_cr("; @%d", interpreter_frame_bci());
  // locals
  st->print_cr(" - locals [" INTPTR_FORMAT "]", p2i(interpreter_frame_local_at(0)));
  // method
  st->print(" - method [" INTPTR_FORMAT "]", p2i(interpreter_frame_method()));
  st->fill_to(23);
  st->print("; ");
  interpreter_frame_method()->print_name(st);
  st->cr();
#endif
}

// Print whether the frame is in the VM or OS indicating a HotSpot problem.
// Otherwise, it's likely a bug in the native library that the Java code calls,
// hopefully indicating where to submit bugs.
void frame::print_C_frame(outputStream* st, char* buf, int buflen, address pc) {
  // C/C++ frame
  bool in_vm = os::address_is_in_vm(pc);
  st->print(in_vm ? "V" : "C");

  int offset;
  bool found;

  if (buf == NULL || buflen < 1) return;
  // libname
  buf[0] = '\0';
  found = os::dll_address_to_library_name(pc, buf, buflen, &offset);
  if (found && buf[0] != '\0') {
    // skip directory names
    const char *p1, *p2;
    p1 = buf;
    int len = (int)strlen(os::file_separator());
    while ((p2 = strstr(p1, os::file_separator())) != NULL) p1 = p2 + len;
    st->print("  [%s+0x%x]", p1, offset);
  } else {
    st->print("  " PTR_FORMAT, p2i(pc));
  }

  found = os::dll_address_to_function_name(pc, buf, buflen, &offset);
  if (found) {
    st->print("  %s+0x%x", buf, offset);
  }
}

// frame::print_on_error() is called by fatal error handler. Notice that we may
// crash inside this function if stack frame is corrupted. The fatal error
// handler can catch and handle the crash. Here we assume the frame is valid.
//
// First letter indicates type of the frame:
//    J: Java frame (compiled)
//    A: Java frame (aot compiled)
//    j: Java frame (interpreted)
//    V: VM frame (C/C++)
//    v: Other frames running VM generated code (e.g. stubs, adapters, etc.)
//    C: C/C++ frame
//
// We don't need detailed frame type as that in frame::print_name(). "C"
// suggests the problem is in user lib; everything else is likely a VM bug.

void frame::print_on_error(outputStream* st, char* buf, int buflen, bool verbose) const {
  if (_cb != NULL) {
    if (Interpreter::contains(pc())) {
      Method* m = this->interpreter_frame_method();
      if (m != NULL) {
        m->name_and_sig_as_C_string(buf, buflen);
        st->print("j  %s", buf);
        st->print("+%d", this->interpreter_frame_bci());
        ModuleEntry* module = m->method_holder()->module();
        if (module->is_named()) {
          module->name()->as_C_string(buf, buflen);
          st->print(" %s", buf);
          if (module->version() != NULL) {
            module->version()->as_C_string(buf, buflen);
            st->print("@%s", buf);
          }
        }
      } else {
        st->print("j  " PTR_FORMAT, p2i(pc()));
      }
    } else if (StubRoutines::contains(pc())) {
      StubCodeDesc* desc = StubCodeDesc::desc_for(pc());
      if (desc != NULL) {
        st->print("v  ~StubRoutines::%s " PTR_FORMAT, desc->name(), p2i(pc()));
      } else {
        st->print("v  ~StubRoutines::" PTR_FORMAT, p2i(pc()));
      }
    } else if (_cb->is_buffer_blob()) {
      st->print("v  ~BufferBlob::%s " PTR_FORMAT, ((BufferBlob *)_cb)->name(), p2i(pc()));
    } else if (_cb->is_compiled()) {
      CompiledMethod* cm = (CompiledMethod*)_cb;
      Method* m = cm->method();
      if (m != NULL) {
        if (cm->is_aot()) {
          st->print("A %d ", cm->compile_id());
        } else if (cm->is_nmethod()) {
          nmethod* nm = cm->as_nmethod();
          st->print("J %d%s", nm->compile_id(), (nm->is_osr_method() ? "%" : ""));
          st->print(" %s", nm->compiler_name());
        }
        m->name_and_sig_as_C_string(buf, buflen);
        st->print(" %s", buf);
        ModuleEntry* module = m->method_holder()->module();
        if (module->is_named()) {
          module->name()->as_C_string(buf, buflen);
          st->print(" %s", buf);
          if (module->version() != NULL) {
            module->version()->as_C_string(buf, buflen);
            st->print("@%s", buf);
          }
        }
        st->print(" (%d bytes) @ " PTR_FORMAT " [" PTR_FORMAT "+" INTPTR_FORMAT "]",
                  m->code_size(), p2i(_pc), p2i(_cb->code_begin()), _pc - _cb->code_begin());
#if INCLUDE_JVMCI
        if (cm->is_nmethod()) {
          nmethod* nm = cm->as_nmethod();
          const char* jvmciName = nm->jvmci_name();
          if (jvmciName != NULL) {
            st->print(" (%s)", jvmciName);
          }
        }
#endif
      } else {
        st->print("J  " PTR_FORMAT, p2i(pc()));
      }
    } else if (_cb->is_runtime_stub()) {
      st->print("v  ~RuntimeStub::%s " PTR_FORMAT, ((RuntimeStub *)_cb)->name(), p2i(pc()));
    } else if (_cb->is_deoptimization_stub()) {
      st->print("v  ~DeoptimizationBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_exception_stub()) {
      st->print("v  ~ExceptionBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_safepoint_stub()) {
      st->print("v  ~SafepointBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_adapter_blob()) {
      st->print("v  ~AdapterBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_vtable_blob()) {
      st->print("v  ~VtableBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_method_handles_adapter_blob()) {
      st->print("v  ~MethodHandlesAdapterBlob " PTR_FORMAT, p2i(pc()));
    } else if (_cb->is_uncommon_trap_stub()) {
      st->print("v  ~UncommonTrapBlob " PTR_FORMAT, p2i(pc()));
    } else {
      st->print("v  blob " PTR_FORMAT, p2i(pc()));
    }
  } else {
    print_C_frame(st, buf, buflen, pc());
  }
}


/*
  The interpreter_frame_expression_stack_at method in the case of SPARC needs the
  max_stack value of the method in order to compute the expression stack address.
  It uses the Method* in order to get the max_stack value but during GC this
  Method* value saved on the frame is changed by reverse_and_push and hence cannot
  be used. So we save the max_stack value in the FrameClosure object and pass it
  down to the interpreter_frame_expression_stack_at method
*/
class InterpreterFrameClosure : public OffsetClosure {
 private:
  const frame* _fr;
  OopClosure*  _f;
  int          _max_locals;
  int          _max_stack;

 public:
  InterpreterFrameClosure(const frame* fr, int max_locals, int max_stack,
                          OopClosure* f) {
    _fr         = fr;
    _max_locals = max_locals;
    _max_stack  = max_stack;
    _f          = f;
  }

  void offset_do(int offset) {
    oop* addr;
    if (offset < _max_locals) {
      addr = (oop*) _fr->interpreter_frame_local_at(offset);
      assert((intptr_t*)addr >= _fr->sp(), "must be inside the frame");
      _f->do_oop(addr);
    } else {
      addr = (oop*) _fr->interpreter_frame_expression_stack_at((offset - _max_locals));
      // In case of exceptions, the expression stack is invalid and the esp will be reset to express
      // this condition. Therefore, we call f only if addr is 'inside' the stack (i.e., addr >= esp for Intel).
      bool in_stack;
      if (frame::interpreter_frame_expression_stack_direction() > 0) {
        in_stack = (intptr_t*)addr <= _fr->interpreter_frame_tos_address();
      } else {
        in_stack = (intptr_t*)addr >= _fr->interpreter_frame_tos_address();
      }
      if (in_stack) {
        _f->do_oop(addr);
      }
    }
  }

  int max_locals()  { return _max_locals; }
};


class InterpretedArgumentOopFinder: public SignatureIterator {
 private:
  OopClosure*  _f;             // Closure to invoke
  int          _offset;        // TOS-relative offset, decremented with each argument
  bool         _has_receiver;  // true if the callee has a receiver
  const frame* _fr;

  friend class SignatureIterator;  // so do_parameters_on can call do_type
  void do_type(BasicType type) {
    _offset -= parameter_type_word_count(type);
    if (is_reference_type(type)) oop_offset_do();
   }

  void oop_offset_do() {
    oop* addr;
    addr = (oop*)_fr->interpreter_frame_tos_at(_offset);
    _f->do_oop(addr);
  }

 public:
  InterpretedArgumentOopFinder(Symbol* signature, bool has_receiver, const frame* fr, OopClosure* f) : SignatureIterator(signature), _has_receiver(has_receiver) {
    // compute size of arguments
    int args_size = ArgumentSizeComputer(signature).size() + (has_receiver ? 1 : 0);
    assert(!fr->is_interpreted_frame() ||
           args_size <= fr->interpreter_frame_expression_stack_size(),
            "args cannot be on stack anymore");
    // initialize InterpretedArgumentOopFinder
    _f         = f;
    _fr        = fr;
    _offset    = args_size;
  }

  void oops_do() {
    if (_has_receiver) {
      --_offset;
      oop_offset_do();
    }
    do_parameters_on(this);
  }
};


// Entry frame has following form (n arguments)
//         +-----------+
//   sp -> |  last arg |
//         +-----------+
//         :    :::    :
//         +-----------+
// (sp+n)->|  first arg|
//         +-----------+



// visits and GC's all the arguments in entry frame
class EntryFrameOopFinder: public SignatureIterator {
 private:
  bool         _is_static;
  int          _offset;
  const frame* _fr;
  OopClosure*  _f;

  friend class SignatureIterator;  // so do_parameters_on can call do_type
  void do_type(BasicType type) {
    // decrement offset before processing the type
    _offset -= parameter_type_word_count(type);
    assert (_offset >= 0, "illegal offset");
    if (is_reference_type(type))  oop_at_offset_do(_offset);
 }

  void oop_at_offset_do(int offset) {
    assert (offset >= 0, "illegal offset");
    oop* addr = (oop*) _fr->entry_frame_argument_at(offset);
    _f->do_oop(addr);
  }

 public:
  EntryFrameOopFinder(const frame* frame, Symbol* signature, bool is_static) : SignatureIterator(signature) {
    _f = NULL; // will be set later
    _fr = frame;
    _is_static = is_static;
    _offset = ArgumentSizeComputer(signature).size();  // pre-decremented down to zero
  }

  void arguments_do(OopClosure* f) {
    _f = f;
    if (!_is_static)  oop_at_offset_do(_offset); // do the receiver
    do_parameters_on(this);
  }

};

oop* frame::interpreter_callee_receiver_addr(Symbol* signature) {
  ArgumentSizeComputer asc(signature);
  int size = asc.size();
  return (oop *)interpreter_frame_tos_at(size);
}

oop frame::interpreter_callee_receiver(Symbol* signature) {
  // TODO: Erik: remove after integration with concurrent stack scanning
  oop r = *interpreter_callee_receiver_addr(signature);
  r = NativeAccess<>::oop_load(&r);
  return r;
}

void frame::oops_interpreted_do(OopClosure* f, const RegisterMap* map, bool query_oop_map_cache) const {
  Thread *thread = Thread::current();
  methodHandle m (thread, interpreter_frame_method());
  jint bci = interpreter_frame_bci();

  InterpreterOopMap mask;
  if (query_oop_map_cache) {
    m->mask_for(bci, &mask);
  } else {
    OopMapCache::compute_one_oop_map(m, bci, &mask);
  }
  
  oops_interpreted_do0(f, map, m, bci, mask);
}

void frame::oops_interpreted_do(OopClosure* f, const RegisterMap* map, const InterpreterOopMap& mask) const {
  Thread *thread = Thread::current();
  methodHandle m (thread, interpreter_frame_method());
  jint bci = interpreter_frame_bci();
  oops_interpreted_do0(f, map, m, bci, mask);
}

void frame::oops_interpreted_do0(OopClosure* f, const RegisterMap* map, methodHandle m, jint bci, const InterpreterOopMap& mask) const {
  assert(is_interpreted_frame(), "Not an interpreted frame");
  // assert(map != NULL, "map must be set");

  assert(!Universe::heap()->is_in(m()),
          "must be valid oop");
  assert(m->is_method(), "checking frame value");
  assert((m->is_native() && bci == 0)  ||
         (!m->is_native() && bci >= 0 && bci < m->code_size()),
         "invalid bci value");

  // Handle the monitor elements in the activation
  for (
    BasicObjectLock* current = interpreter_frame_monitor_end();
    current < interpreter_frame_monitor_begin();
    current = next_monitor_in_interpreter_frame(current)
  ) {
#ifdef ASSERT
    interpreter_frame_verify_monitor(current);
#endif
    current->oops_do(f);
  }

  if (m->is_native()) {
    f->do_oop(interpreter_frame_temp_oop_addr());
  }

  // The method pointer in the frame might be the only path to the method's
  // klass, and the klass needs to be kept alive while executing. The GCs
  // don't trace through method pointers, so the mirror of the method's klass
  // is installed as a GC root.
  f->do_oop(interpreter_frame_mirror_addr());

  int max_locals = m->is_native() ? m->size_of_parameters() : m->max_locals();

  Symbol* signature = NULL;
  bool has_receiver = false;

  // Process a callee's arguments if we are at a call site
  // (i.e., if we are at an invoke bytecode)
  // This is used sometimes for calling into the VM, not for another
  // interpreted or compiled frame.
  if (!m->is_native()) {
    Bytecode_invoke call = Bytecode_invoke_check(m, bci);
    if (call.is_valid()) {
      signature = call.signature();
      has_receiver = call.has_receiver();
      if (map != NULL && map->include_argument_oops() &&
          interpreter_frame_expression_stack_size() > 0) {
        // ResourceMark rm(thread);  // is this right ???
        // we are at a call site & the expression stack is not empty
        // => process callee's arguments
        //
        // Note: The expression stack can be empty if an exception
        //       occurred during method resolution/execution. In all
        //       cases we empty the expression stack completely be-
        //       fore handling the exception (the exception handling
        //       code in the interpreter calls a blocking runtime
        //       routine which can cause this code to be executed).
        //       (was bug gri 7/27/98)
        oops_interpreted_arguments_do(signature, has_receiver, f);
      }
    }
  }

  InterpreterFrameClosure blk(this, max_locals, m->max_stack(), f);

  // process locals & expression stack
  // mask.print();
  mask.iterate_oop(&blk);
}


void frame::oops_interpreted_arguments_do(Symbol* signature, bool has_receiver, OopClosure* f) const {
  InterpretedArgumentOopFinder finder(signature, has_receiver, this, f);
  finder.oops_do();
}

void frame::oops_code_blob_do(OopClosure* f, CodeBlobClosure* cf, DerivedOopClosure* df, DerivedPointerIterationMode derived_mode, const RegisterMap* reg_map) const {
  assert(_cb != NULL, "sanity check");
  assert((oop_map() == NULL) == (_cb->oop_maps() == NULL), "frame and _cb must agree that oopmap is set or not");
  if (oop_map() != NULL) {
    if (df != NULL) {
      _oop_map->oops_do(this, reg_map, f, df);
    } else {
      _oop_map->oops_do(this, reg_map, f, derived_mode);
    }

    // Preserve potential arguments for a callee. We handle this by dispatching
    // on the codeblob. For c2i, we do
    if (reg_map->include_argument_oops()) {
      _cb->preserve_callee_argument_oops(*this, reg_map, f);
    }
  }
  // In cases where perm gen is collected, GC will want to mark
  // oops referenced from nmethods active on thread stacks so as to
  // prevent them from being collected. However, this visit should be
  // restricted to certain phases of the collection only. The
  // closure decides how it wants nmethods to be traced.
  if (cf != NULL)
    cf->do_code_blob(_cb);
}

class CompiledArgumentOopFinder: public SignatureIterator {
 protected:
  OopClosure*     _f;
  int             _offset;        // the current offset, incremented with each argument
  bool            _has_receiver;  // true if the callee has a receiver
  bool            _has_appendix;  // true if the call has an appendix
  frame           _fr;
  RegisterMap*    _reg_map;
  int             _arg_size;
  VMRegPair*      _regs;        // VMReg list of arguments

  friend class SignatureIterator;  // so do_parameters_on can call do_type
  void do_type(BasicType type) {
    if (is_reference_type(type))  handle_oop_offset();
    _offset += parameter_type_word_count(type);
  }

  virtual void handle_oop_offset() {
    // Extract low order register number from register array.
    // In LP64-land, the high-order bits are valid but unhelpful.
    VMReg reg = _regs[_offset].first();
    oop *loc = _fr.oopmapreg_to_location(reg, _reg_map);
  #ifdef ASSERT
    if (loc == NULL) {
      if (_reg_map->should_skip_missing())
        return;
      tty->print_cr("Error walking frame oops:");
      _fr.print_on(tty);
      assert(loc != NULL, "reg: " INTPTR_FORMAT " %s loc: " INTPTR_FORMAT, reg->value(), reg->name(), p2i(loc));
    }
  #endif
    _f->do_oop(loc);
  }

 public:
  CompiledArgumentOopFinder(Symbol* signature, bool has_receiver, bool has_appendix, OopClosure* f, frame fr, const RegisterMap* reg_map)
    : SignatureIterator(signature) {

    // initialize CompiledArgumentOopFinder
    _f         = f;
    _offset    = 0;
    _has_receiver = has_receiver;
    _has_appendix = has_appendix;
    _fr        = fr;
    _reg_map   = (RegisterMap*)reg_map;
    _arg_size  = ArgumentSizeComputer(signature).size() + (has_receiver ? 1 : 0) + (has_appendix ? 1 : 0);

    int arg_size;
    _regs = SharedRuntime::find_callee_arguments(signature, has_receiver, has_appendix, &arg_size);
    assert(arg_size == _arg_size, "wrong arg size");
  }

  void oops_do() {
    if (_has_receiver) {
      handle_oop_offset();
      _offset++;
    }
    do_parameters_on(this);
    if (_has_appendix) {
      handle_oop_offset();
      _offset++;
    }
  }
};

void frame::oops_compiled_arguments_do(Symbol* signature, bool has_receiver, bool has_appendix,
                                       const RegisterMap* reg_map, OopClosure* f) const {
  // ResourceMark rm;
  CompiledArgumentOopFinder finder(signature, has_receiver, has_appendix, f, *this, reg_map);
  finder.oops_do();
}

// Get receiver out of callers frame, i.e. find parameter 0 in callers
// frame.  Consult ADLC for where parameter 0 is to be found.  Then
// check local reg_map for it being a callee-save register or argument
// register, both of which are saved in the local frame.  If not found
// there, it must be an in-stack argument of the caller.
// Note: caller.sp() points to callee-arguments
oop frame::retrieve_receiver(RegisterMap* reg_map) {
  frame caller = *this;

  // First consult the ADLC on where it puts parameter 0 for this signature.
  VMReg reg = SharedRuntime::name_for_receiver();
  oop* oop_adr = caller.oopmapreg_to_location(reg, reg_map);
  if (oop_adr == NULL) {
    guarantee(oop_adr != NULL, "bad register save location");
    return NULL;
  }
  oop r = *oop_adr;
  // TODO: Erik: remove after integration with concurrent stack scanning
  r = NativeAccess<>::oop_load(&r);
  assert(Universe::heap()->is_in_or_null(r), "bad receiver: " INTPTR_FORMAT " (" INTX_FORMAT ")", p2i(r), p2i(r));
  return r;
}


BasicLock* frame::get_native_monitor() {
  nmethod* nm = (nmethod*)_cb;
  assert(_cb != NULL && _cb->is_nmethod() && nm->method()->is_native(),
         "Should not call this unless it's a native nmethod");
  int byte_offset = in_bytes(nm->native_basic_lock_sp_offset());
  assert(byte_offset >= 0, "should not see invalid offset");
  return (BasicLock*) &sp()[byte_offset / wordSize];
}

oop frame::get_native_receiver() {
  nmethod* nm = (nmethod*)_cb;
  assert(_cb != NULL && _cb->is_nmethod() && nm->method()->is_native(),
         "Should not call this unless it's a native nmethod");
  int byte_offset = in_bytes(nm->native_receiver_sp_offset());
  assert(byte_offset >= 0, "should not see invalid offset");
  oop owner = ((oop*) sp())[byte_offset / wordSize];
  // TODO: Erik: remove after integration with concurrent stack scanning
  owner = NativeAccess<>::oop_load(&owner);
  assert( Universe::heap()->is_in(owner), "bad receiver" );
  return owner;
}

void frame::oops_entry_do(OopClosure* f, const RegisterMap* map) const {
  assert(map != NULL, "map must be set");
  if (map->include_argument_oops()) {
    // must collect argument oops, as nobody else is doing it
    Thread *thread = Thread::current();
    methodHandle m (thread, entry_frame_call_wrapper()->callee_method());
    EntryFrameOopFinder finder(this, m->signature(), m->is_static());
    finder.arguments_do(f);
  }
  // Traverse the Handle Block saved in the entry frame
  entry_frame_call_wrapper()->oops_do(f);
}

void frame::oops_do_internal(OopClosure* f, CodeBlobClosure* cf, DerivedOopClosure* df, DerivedPointerIterationMode derived_mode, const RegisterMap* map, bool use_interpreter_oop_map_cache) const {
#ifndef PRODUCT
  // simulate GC crash here to dump java thread in error report
  if (CrashGCForDumpingJavaThread) {
    char *t = NULL;
    *t = 'c';
  }
#endif
  if (is_interpreted_frame()) {
    oops_interpreted_do(f, map, use_interpreter_oop_map_cache);
  } else if (is_entry_frame()) {
    oops_entry_do(f, map);
  } else if (CodeCache::contains(pc())) {
    oops_code_blob_do(f, cf, df, derived_mode, map);
  } else {
    ShouldNotReachHere();
  }
}

void frame::nmethods_do(CodeBlobClosure* cf) const {
  if (_cb != NULL && _cb->is_nmethod()) {
    cf->do_code_blob(_cb);
  }
}


// Call f closure on the interpreted Method*s in the stack.
void frame::metadata_do(MetadataClosure* f) const {
  ResourceMark rm;
  if (is_interpreted_frame()) {
    Method* m = this->interpreter_frame_method();
    assert(m != NULL, "expecting a method in this frame");
    f->do_metadata(m);
  }
}

void frame::verify(const RegisterMap* map) const {
#ifndef PRODUCT
  if (TraceCodeBlobStacks) {
    tty->print_cr("*** verify");
    print_on(tty);
  }
#endif

  // for now make sure receiver type is correct
  if (is_interpreted_frame()) {
    Method* method = interpreter_frame_method();
    guarantee(method->is_method(), "method is wrong in frame::verify");
    if (!method->is_static()) {
      // fetch the receiver
      oop* p = (oop*) interpreter_frame_local_at(0);
      // make sure we have the right receiver type
    }
  }
#if COMPILER2_OR_JVMCI
  assert(DerivedPointerTable::is_empty(), "must be empty before verify");
#endif

  oops_do_internal(&VerifyOopClosure::verify_oop, NULL, NULL, DerivedPointerIterationMode::_ignore, map, false);
}


#ifdef ASSERT
bool frame::verify_return_pc(address x) {
  if (StubRoutines::returns_to_call_stub(x)) {
    return true;
  }
  if (CodeCache::contains(x)) {
    return true;
  }
  if (Interpreter::contains(x)) {
    return true;
  }
  return false;
}
#endif

#ifdef ASSERT
void frame::interpreter_frame_verify_monitor(BasicObjectLock* value) const {
  assert(is_interpreted_frame(), "Not an interpreted frame");
  // verify that the value is in the right part of the frame
  address low_mark  = (address) interpreter_frame_monitor_end();
  address high_mark = (address) interpreter_frame_monitor_begin();
  address current   = (address) value;

  const int monitor_size = frame::interpreter_frame_monitor_size();
  guarantee((high_mark - current) % monitor_size  ==  0         , "Misaligned top of BasicObjectLock*");
  guarantee( high_mark > current                                , "Current BasicObjectLock* higher than high_mark");

  guarantee((current - low_mark) % monitor_size  ==  0         , "Misaligned bottom of BasicObjectLock*");
  guarantee( current >= low_mark                               , "Current BasicObjectLock* below than low_mark");
}
#endif

#ifndef PRODUCT

class FrameValuesOopClosure: public OopClosure, public DerivedOopClosure {
private:
  FrameValues& _values;
  int _frame_no;
public:
  FrameValuesOopClosure(FrameValues& values, int frame_no) : _values(values), _frame_no(frame_no) {}
  virtual void do_oop(oop* p)       { _values.describe(_frame_no, (intptr_t*)p, err_msg("oop for #%d", _frame_no)); }
  virtual void do_oop(narrowOop* p) { _values.describe(_frame_no, (intptr_t*)p, err_msg("narrow oop for #%d", _frame_no)); }
  virtual void do_derived_oop(oop *base, oop *derived) { 
    _values.describe(_frame_no, (intptr_t*)derived, err_msg("derived pointer (base: " INTPTR_FORMAT ") for #%d", p2i(base), _frame_no));
  }
};

class FrameValuesOopMapClosure: public OopMapClosure {
private:
  const frame* _fr;
  const RegisterMap* _reg_map;
  FrameValues& _values;
  int _frame_no;
public:
  FrameValuesOopMapClosure(const frame* fr, const RegisterMap* reg_map, FrameValues& values, int frame_no)
   : _fr(fr), _reg_map(reg_map), _values(values), _frame_no(frame_no) {}

  virtual void do_value(VMReg reg, OopMapValue::oop_types type) {
    intptr_t* p = (intptr_t*)_fr->oopmapreg_to_location(reg, _reg_map);
    if (p != NULL && (((intptr_t)p & WordAlignmentMask) == 0)) {
      const char* type_name = NULL;
      switch(type) {
        case OopMapValue::oop_value:          type_name = "oop";          break;
        case OopMapValue::narrowoop_value:    type_name = "narrow oop";   break;
        case OopMapValue::callee_saved_value: type_name = "callee-saved"; break;
        case OopMapValue::derived_oop_value:  type_name = "derived";      break;
        // case OopMapValue::live_value:         type_name = "live";         break;
        default: break;
      }
      if (type_name != NULL)
        _values.describe(_frame_no, p, err_msg("%s for #%d", type_name, _frame_no));
    }
  }
};

// callers need a ResourceMark because of name_and_sig_as_C_string() usage,
// RA allocated string is returned to the caller
void frame::describe(FrameValues& values, int frame_no, const RegisterMap* reg_map) {
  // boundaries: sp and the 'real' frame pointer
  values.describe(-1, sp(), err_msg("sp for #%d", frame_no), 0);
  intptr_t* frame_pointer = real_fp(); // Note: may differ from fp()

  // print frame info at the highest boundary
  intptr_t* info_address = MAX2(sp(), frame_pointer);

  if (info_address != frame_pointer) {
    // print frame_pointer explicitly if not marked by the frame info
    values.describe(-1, frame_pointer, err_msg("frame pointer for #%d", frame_no), 1);
  }

  if (is_entry_frame() || is_compiled_frame() || is_interpreted_frame() || is_native_frame()) {
    // Label values common to most frames
    values.describe(-1, unextended_sp(), err_msg("unextended_sp for #%d", frame_no), 0);
  }

  if (is_interpreted_frame()) {
    Method* m = interpreter_frame_method();
    int bci = interpreter_frame_bci();
    InterpreterCodelet* desc = Interpreter::codelet_containing(pc());

    // Label the method and current bci
    values.describe(-1, info_address,
                    FormatBuffer<1024>("#%d method %s @ %d", frame_no, m->name_and_sig_as_C_string(), bci), 3);
    if (desc != NULL) {
      values.describe(-1, info_address, err_msg("- %s codelet: %s", 
        desc->bytecode()    >= 0    ? Bytecodes::name(desc->bytecode()) : "",
        desc->description() != NULL ? desc->description()               : "?"), 2);
    }
    values.describe(-1, info_address,
                    err_msg("- %d locals %d max stack", m->max_locals(), m->max_stack()), 2);
    values.describe(frame_no, (intptr_t*)sender_pc_addr(), Continuation::is_return_barrier_entry(*sender_pc_addr()) ? "return address (return barrier)" : "return address");

    if (m->max_locals() > 0) {
      intptr_t* l0 = interpreter_frame_local_at(0);
      intptr_t* ln = interpreter_frame_local_at(m->max_locals() - 1);
      values.describe(-1, MAX2(l0, ln), err_msg("locals for #%d", frame_no), 2);
      // Report each local and mark as owned by this frame
      for (int l = 0; l < m->max_locals(); l++) {
        intptr_t* l0 = interpreter_frame_local_at(l);
        values.describe(frame_no, l0, err_msg("local %d", l), 1);
      }
    }

    if (interpreter_frame_monitor_begin() != interpreter_frame_monitor_end()) {
      values.describe(frame_no, (intptr_t*)interpreter_frame_monitor_begin(), "monitors begin");
      values.describe(frame_no, (intptr_t*)interpreter_frame_monitor_end(), "monitors end");
    }

    // Compute the actual expression stack size
    InterpreterOopMap mask;
    OopMapCache::compute_one_oop_map(methodHandle(Thread::current(), m), bci, &mask);
    intptr_t* tos = NULL;
    // Report each stack element and mark as owned by this frame
    for (int e = 0; e < mask.expression_stack_size(); e++) {
      tos = MAX2(tos, interpreter_frame_expression_stack_at(e));
      values.describe(frame_no, interpreter_frame_expression_stack_at(e),
                      err_msg("stack %d", e), 1);
    }
    if (tos != NULL) {
      values.describe(-1, tos, err_msg("expression stack for #%d", frame_no), 2);
    }

    if (reg_map != NULL) {
      FrameValuesOopClosure oopsFn(values, frame_no);
      oops_do(&oopsFn, NULL, &oopsFn, reg_map);
    }
  } else if (is_entry_frame()) {
    // For now just label the frame
    values.describe(-1, info_address, err_msg("#%d entry frame", frame_no), 2);
  } else if (cb()->is_compiled()) {
    // For now just label the frame
    CompiledMethod* cm = cb()->as_compiled_method();
    values.describe(-1, info_address,
                    FormatBuffer<1024>("#%d nmethod " INTPTR_FORMAT " for method %s%s%s", frame_no,
                                       p2i(cm),
                                       (cm->is_aot() ? "A ": "J "),
                                       cm->method()->name_and_sig_as_C_string(),
                                       (_deopt_state == is_deoptimized) ?
                                       " (deoptimized)" :
                                       ((_deopt_state == unknown) ? " (state unknown)" : "")),
                    3);

    { // mark arguments (see nmethod::print_nmethod_labels)
      Method* m = cm->method();

      int stack_slot_offset = cm->frame_size() * wordSize; // offset, in bytes, to caller sp
      int sizeargs = m->size_of_parameters();

      BasicType* sig_bt = NEW_RESOURCE_ARRAY(BasicType, sizeargs);
      VMRegPair* regs   = NEW_RESOURCE_ARRAY(VMRegPair, sizeargs);
      {
        int sig_index = 0;
        if (!m->is_static()) sig_bt[sig_index++] = T_OBJECT; // 'this'
        for (SignatureStream ss(m->signature()); !ss.at_return_type(); ss.next()) {
          BasicType t = ss.type();
          assert(type2size[t] == 1 || type2size[t] == 2, "size is 1 or 2");
          sig_bt[sig_index++] = t;
          if (type2size[t] == 2) sig_bt[sig_index++] = T_VOID;
        }
        assert(sig_index == sizeargs, "");
      }
      int out_preserve = SharedRuntime::java_calling_convention(sig_bt, regs, sizeargs);
      assert (out_preserve ==  m->num_stack_arg_slots(), "");
      int sig_index = 0;
      int arg_index = (m->is_static() ? 0 : -1);
      for (SignatureStream ss(m->signature()); !ss.at_return_type(); ) {
        bool at_this = (arg_index == -1);
        bool at_old_sp = false;
        BasicType t = (at_this ? T_OBJECT : ss.type());
        assert(t == sig_bt[sig_index], "sigs in sync");
        VMReg fst = regs[sig_index].first();
        if (fst->is_stack()) {
          assert (((int)fst->reg2stack()) >= 0, "reg2stack: %lu | %d", fst->reg2stack(), (int)fst->reg2stack());
          int offset = fst->reg2stack() * VMRegImpl::stack_slot_size + stack_slot_offset;
          intptr_t* stack_address = (intptr_t*)((address)sp() + offset);
          if (at_this)
            values.describe(frame_no, stack_address, err_msg("this for #%d", frame_no), 1);
          else
            values.describe(frame_no, stack_address, err_msg("param %d %s for #%d", arg_index, type2name(t), frame_no), 1);
        }
        sig_index += type2size[t];
        arg_index += 1;
        if (!at_this) ss.next();
      }
    }

    if (reg_map != NULL && is_java_frame()) {
      int scope_no = 0;
      for (ScopeDesc* scope = cm->scope_desc_at(pc()); scope != NULL; scope = scope->sender(), scope_no++) {
        Method* m = scope->method();
        int  bci = scope->bci();
        values.describe(-1, info_address, err_msg("- #%d scope %s @ %d", scope_no, m->name_and_sig_as_C_string(), bci), 2);

        { // mark locals
          GrowableArray<ScopeValue*>* scvs = scope->locals();
          int scvs_length = scvs != NULL ? scvs->length() : 0;
          for (int i = 0; i < scvs_length; i++) {
            intptr_t* stack_address = (intptr_t*)StackValue::stack_value_address(this, reg_map, scvs->at(i));
            if (stack_address != NULL)
              values.describe(frame_no, stack_address, err_msg("local %d for #%d (scope %d)", i, frame_no, scope_no), 1);
          }
        }
        { // mark expression stack
          GrowableArray<ScopeValue*>* scvs = scope->expressions();
          int scvs_length = scvs != NULL ? scvs->length() : 0;
          for (int i = 0; i < scvs_length; i++) {
            intptr_t* stack_address = (intptr_t*)StackValue::stack_value_address(this, reg_map, scvs->at(i));
            if (stack_address != NULL)
              values.describe(frame_no, stack_address, err_msg("stack %d for #%d (scope %d)", i, frame_no, scope_no), 1);
          }
        }
      }

      FrameValuesOopClosure oopsFn(values, frame_no);
      oops_do(&oopsFn, NULL, &oopsFn, reg_map);

      if (oop_map() != NULL) {
        FrameValuesOopMapClosure valuesFn(this, reg_map, values, frame_no);
        // also OopMapValue::live_value ??
        oop_map()->all_type_do(this, OopMapValue::callee_saved_value, &valuesFn);
      }
    }

    if (cm->method()->is_continuation_enter_intrinsic()) {
      address usp = (address)unextended_sp();
      values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_offset())), "parent");
      values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::cont_offset())),   "continuation");
      values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::chunk_offset())),   "chunk");
      values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::argsize_offset())), "argsize");
      // values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_cont_fastpath_offset())),      "parent fastpath");
      // values.describe(frame_no, (intptr_t*)(usp + in_bytes(ContinuationEntry::parent_held_monitor_count_offset())), "parent held monitor count");
    }
  } else if (is_native_frame()) {
    // For now just label the frame
    nmethod* nm = cb()->as_nmethod_or_null();
    values.describe(-1, info_address,
                    FormatBuffer<1024>("#%d nmethod " INTPTR_FORMAT " for native method %s", frame_no,
                                       p2i(nm), nm->method()->name_and_sig_as_C_string()), 2);
  } else {
    // provide default info if not handled before
    char *info = (char *) "special frame";
    if ((_cb != NULL) &&
        (_cb->name() != NULL)) {
      info = (char *)_cb->name();
    }
    values.describe(-1, info_address, err_msg("#%d <%s>", frame_no, info), 2);
  }

  // platform dependent additional data
  describe_pd(values, frame_no);
}

#endif


//-----------------------------------------------------------------------------------
// StackFrameStream implementation

StackFrameStream::StackFrameStream(JavaThread *thread, bool update, bool process_frames, bool allow_missing_reg) : _reg_map(thread, update, process_frames) {
  assert(thread->has_last_Java_frame(), "sanity check");
  _fr = thread->last_frame();
  _is_done = false;
#ifndef PRODUCT
  if (allow_missing_reg) {
    _reg_map.set_skip_missing(true);
  }
#endif
}


#ifndef PRODUCT

void FrameValues::describe(int owner, intptr_t* location, const char* description, int priority) {
  FrameValue fv;
  fv.location = location;
  fv.owner = owner;
  fv.priority = priority;
  fv.description = NEW_RESOURCE_ARRAY(char, strlen(description) + 1);
  strcpy(fv.description, description);
  _values.append(fv);
}


#ifdef ASSERT
void FrameValues::validate() {
  _values.sort(compare);
  bool error = false;
  FrameValue prev;
  prev.owner = -1;
  for (int i = _values.length() - 1; i >= 0; i--) {
    FrameValue fv = _values.at(i);
    if (fv.owner == -1) continue;
    if (prev.owner == -1) {
      prev = fv;
      continue;
    }
    if (prev.location == fv.location) {
      if (fv.owner != prev.owner) {
        tty->print_cr("overlapping storage");
        tty->print_cr(" " INTPTR_FORMAT ": " INTPTR_FORMAT " %s", p2i(prev.location), *prev.location, prev.description);
        tty->print_cr(" " INTPTR_FORMAT ": " INTPTR_FORMAT " %s", p2i(fv.location), *fv.location, fv.description);
        error = true;
      }
    } else {
      prev = fv;
    }
  }
  assert(!error, "invalid layout");
}
#endif // ASSERT

void FrameValues::print_on(JavaThread* thread, outputStream* st) {
  _values.sort(compare);

  // Sometimes values like the fp can be invalid values if the
  // register map wasn't updated during the walk.  Trim out values
  // that aren't actually in the stack of the thread.
  int min_index = 0;
  int max_index = _values.length() - 1;
  intptr_t* v0 = _values.at(min_index).location;
  intptr_t* v1 = _values.at(max_index).location;

  if (thread == Thread::current()) {
    while (!thread->is_in_live_stack((address)v0)) {
      v0 = _values.at(++min_index).location;
    }
    while (!thread->is_in_live_stack((address)v1)) {
      v1 = _values.at(--max_index).location;
    }
  } else {
    while (!thread->is_in_full_stack((address)v0)) {
      v0 = _values.at(++min_index).location;
    }
    while (!thread->is_in_full_stack((address)v1)) {
      v1 = _values.at(--max_index).location;
    }
  }
  intptr_t* min = MIN2(v0, v1);
  intptr_t* max = MAX2(v0, v1);
  intptr_t* cur = max;
  intptr_t* last = NULL;
  for (int i = max_index; i >= min_index; i--) {
    FrameValue fv = _values.at(i);
    while (cur > fv.location) {
      st->print_cr(" " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(cur), *cur);
      cur--;
    }
    if (last == fv.location) {
      const char* spacer = "          " LP64_ONLY("        ");
      st->print_cr(" %s  %s %s", spacer, spacer, fv.description);
    } else {
      st->print_cr(" " INTPTR_FORMAT ": " INTPTR_FORMAT " %s", p2i(fv.location), *fv.location, fv.description);
      last = fv.location;
      cur--;
    }
  }
}

#endif // ndef PRODUCT

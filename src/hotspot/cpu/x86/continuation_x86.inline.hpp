/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_CONTINUATION_X86_INLINE_HPP
#define CPU_X86_CONTINUATION_X86_INLINE_HPP

#include "compiler/oopMapStubGenerator.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/frame.inline.hpp"

const int ContinuationHelper::frame_metadata = frame::sender_sp_offset;
const int ContinuationHelper::align_wiggle = 1;

#ifdef ASSERT
bool Frame::assert_frame_laid_out(frame f) {
  intptr_t* sp = f.sp();
  address pc = *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET);
  intptr_t* fp = *(intptr_t**)(sp - frame::sender_sp_offset);
  assert (f.raw_pc() == pc, "f.ra_pc: " INTPTR_FORMAT " actual: " INTPTR_FORMAT, p2i(f.raw_pc()), p2i(pc));
  assert (f.fp() == fp, "f.fp: " INTPTR_FORMAT " actual: " INTPTR_FORMAT, p2i(f.fp()), p2i(fp));
  return f.raw_pc() == pc && f.fp() == fp;
}
#endif

inline intptr_t** Frame::callee_link_address(const frame& f) {
  return (intptr_t**)(f.sp() - frame::sender_sp_offset);
}

template<typename FKind>
static inline intptr_t* real_fp(const frame& f) {
  assert (FKind::is_instance(f), "");
  assert (FKind::interpreted || f.cb() != nullptr, "");

  return FKind::interpreted ? f.fp() : f.unextended_sp() + f.cb()->frame_size();
}

template<typename FKind> // TODO: maybe do the same CRTP trick with Interpreted and Compiled as with hframe
static inline intptr_t** link_address(const frame& f) {
  assert (FKind::is_instance(f), "");
  return FKind::interpreted
            ? (intptr_t**)(f.fp() + frame::link_offset)
            : (intptr_t**)(real_fp<FKind>(f) - frame::sender_sp_offset);
}

static void patch_callee_link(const frame& f, intptr_t* fp) {
  *Frame::callee_link_address(f) = fp;
  log_trace(jvmcont)("patched link at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(Frame::callee_link_address(f)), p2i(fp));
}

static void patch_callee_link_relative(const frame& f, intptr_t* fp) {
  intptr_t* la = (intptr_t*)Frame::callee_link_address(f);
  intptr_t new_value = fp - la;
  *la = new_value;
  log_trace(jvmcont)("patched link at " INTPTR_FORMAT ": to relative %ld", p2i(Frame::callee_link_address(f)), new_value);
}

inline address* Interpreted::return_pc_address(const frame& f) {
  return (address*)(f.fp() + frame::return_addr_offset);
}

template <bool relative>
void Interpreted::patch_sender_sp(frame& f, intptr_t* sp) {
  assert (f.is_interpreted_frame(), "");
  intptr_t* la = f.addr_at(frame::interpreter_frame_sender_sp_offset);
  *la = relative ? (intptr_t)(sp - f.fp()) : (intptr_t)sp;
  log_trace(jvmcont)("patched sender_sp: " INTPTR_FORMAT, *la);
}

inline address* Frame::return_pc_address(const frame& f) {
  return (address*)(f.real_fp() - 1);
}

// inline address* Frame::pc_address(const frame& f) {
//   return (address*)(f.sp() - frame::return_addr_offset);
// }

inline address Frame::real_pc(const frame& f) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  return *pc_addr;
}

inline void Frame::patch_pc(const frame& f, address pc) {
  address* pc_addr = &(((address*) f.sp())[-1]);
  *pc_addr = pc;
  log_develop_trace(jvmcont)("patch_pc at " INTPTR_FORMAT ": " INTPTR_FORMAT, p2i(pc_addr), p2i(pc));
}

inline intptr_t* Interpreted::frame_top(const frame& f, InterpreterOopMap* mask) { // inclusive; this will be copied with the frame
  // interpreter_frame_last_sp_offset, points to unextended_sp includes arguments in the frame
  // interpreter_frame_initial_sp_offset excludes expression stack slots
  int expression_stack_sz = expression_stack_size(f, mask);
  intptr_t* res = *(intptr_t**)f.addr_at(frame::interpreter_frame_initial_sp_offset) - expression_stack_sz;
  assert (res == (intptr_t*)f.interpreter_frame_monitor_end() - expression_stack_sz, "");
  assert (res >= f.unextended_sp(),
    "res: " INTPTR_FORMAT " initial_sp: " INTPTR_FORMAT " last_sp: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT " expression_stack_size: %d", 
    p2i(res), p2i(f.addr_at(frame::interpreter_frame_initial_sp_offset)), f.at(frame::interpreter_frame_last_sp_offset), p2i(f.unextended_sp()), expression_stack_sz);
  return res;
  // Not true, but using unextended_sp might work
  // assert (res == f.unextended_sp(), "res: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(res), p2i(f.unextended_sp() + 1));
}

template <bool relative>
inline intptr_t* Interpreted::frame_bottom(const frame& f) { // exclusive; this will not be copied with the frame
  return (intptr_t*)f.at<relative>(frame::interpreter_frame_locals_offset) + 1; // exclusive, so we add 1 word
}

inline intptr_t* Interpreted::frame_top(const frame& f, int callee_argsize, bool callee_interpreted) {
  // tty->print_cr(">>> f.unextended_sp(): %p callee_argsize: %d callee_interpreted: %d", f.unextended_sp(), callee_argsize, callee_interpreted);
  return f.unextended_sp() + (callee_interpreted ? callee_argsize : 0);
}

template <bool relative>
inline int Interpreted::stack_argsize(const frame& f) { // exclusive; this will not be copied with the frame
  int diff = (int)(f.at(frame::interpreter_frame_locals_offset) - f.at(frame::interpreter_frame_sender_sp_offset) + sizeof(intptr_t));
  // tty->print_cr(">>>> Interpreted::stack_argsize: %ld -- %ld relative: %d", f.at(frame::interpreter_frame_locals_offset), f.at(frame::interpreter_frame_sender_sp_offset), relative);
  if (!relative) diff >>= LogBytesPerWord;
  DEBUG_ONLY(if (!(!Interpreter::contains(Interpreted::return_pc(f)) || diff >= 0)) { tty->print_cr("ohoh"); f.print_on<relative>(tty); pfl(); })
  assert (!Interpreter::contains(Interpreted::return_pc(f)) || diff >= 0, "diff: %d", diff);
  assert (!CodeCache::find_blob(Interpreted::return_pc(f))->is_compiled() || diff <= 0, "diff: %d", diff);
  if (diff < 0) diff = 0; // happens when caller is compiled
  return diff;
}

template<typename FKind, typename RegisterMapT>
inline void ContinuationHelper::update_register_map(RegisterMapT* map, const frame& f) {
  frame::update_map_with_saved_link(map, link_address<FKind>(f));
}

template<typename RegisterMapT>
inline void ContinuationHelper::update_register_map_with_callee(RegisterMapT* map, const frame& f) {
  frame::update_map_with_saved_link(map, Frame::callee_link_address(f));
}

inline void ContinuationHelper::push_pd(const frame& f) {
  log_develop_trace(jvmcont)("ContinuationHelper::push_pd: " INTPTR_FORMAT, p2i(f.fp()));
  // os::print_location(tty, (intptr_t)f.fp());
  *(intptr_t**)(f.sp() - frame::sender_sp_offset) = f.fp();
}

// creates the yield stub frame faster than JavaThread::last_frame
inline frame ContinuationHelper::last_frame(JavaThread* thread) {
  JavaFrameAnchor* anchor = thread->frame_anchor();
  assert (anchor->last_Java_sp() != nullptr, "");
  assert (anchor->last_Java_pc() != nullptr, "");

  assert (StubRoutines::cont_doYield_stub()->contains(anchor->last_Java_pc()), "must be");
  assert (StubRoutines::cont_doYield_stub()->oop_maps()->count() == 1, "must be");

  return frame(anchor->last_Java_sp(), anchor->last_Java_sp(), anchor->last_Java_fp(), anchor->last_Java_pc(), nullptr, nullptr, true);
  // return frame(anchor->last_Java_sp(), anchor->last_Java_sp(), anchor->last_Java_fp(), anchor->last_Java_pc(), 
  //   StubRoutines::cont_doYield_stub(), StubRoutines::cont_doYield_stub()->oop_map_for_slot(0, anchor->last_Java_pc()), true);
}

frame ContinuationEntry::to_frame() {
  static CodeBlob* cb = CodeCache::find_blob(entry_pc());
  return frame(entry_sp(), entry_sp(), entry_fp(), entry_pc(), cb);
}

void ContinuationEntry::update_register_map(RegisterMap* map) {
  intptr_t** fp = (intptr_t**)(bottom_sender_sp() - frame::sender_sp_offset);
  frame::update_map_with_saved_link(map, fp);
}

void ContinuationHelper::set_anchor_to_entry_pd(JavaFrameAnchor* anchor, ContinuationEntry* cont) {
  anchor->set_last_Java_fp(cont->entry_fp());
}

void ContinuationHelper::set_anchor_pd(JavaFrameAnchor* anchor, intptr_t* sp) {
  intptr_t* fp = *(intptr_t**)(sp - frame::sender_sp_offset);
  anchor->set_last_Java_fp(fp);
}

/////

template <typename ConfigT>
inline void Freeze<ConfigT>::set_top_frame_metadata_pd(const frame& hf) {
  stackChunkOop chunk = _cont.tail();
  assert (chunk->is_in_chunk(hf.sp() - 1), "");
  assert (chunk->is_in_chunk(hf.sp() - frame::sender_sp_offset), "");

  *(hf.sp() - 1) = (intptr_t)hf.pc();

  intptr_t* fp_addr = hf.sp() - frame::sender_sp_offset;
  *fp_addr = hf.is_interpreted_frame() ? (intptr_t)(hf.fp() - fp_addr) 
                                       : (intptr_t)hf.fp();

  log_develop_trace(jvmcont)("set_top_frame_metadata_pd pc: " INTPTR_FORMAT " fp: %ld", p2i(hf.pc()), *fp_addr);
}

template <typename ConfigT>
inline intptr_t* Freeze<ConfigT>::align_bottom(intptr_t* bottom, int argsize) {
#ifdef _LP64
  bottom -= (argsize & 1);
#endif
  return bottom;
}

template <typename ConfigT>
template<typename FKind>
inline frame Freeze<ConfigT>::sender(const frame& f) {
  assert (FKind::is_instance(f), "");
  if (FKind::interpreted) {
    return frame(f.sender_sp(), f.interpreter_frame_sender_sp(), f.link(), f.sender_pc());
  }
  intptr_t** link_addr = link_address<FKind>(f);

  intptr_t* sender_sp = (intptr_t*)(link_addr + frame::sender_sp_offset); //  f.unextended_sp() + (fsize/wordSize); // 
  address sender_pc = (address) *(sender_sp-1);
  assert(sender_sp != f.sp(), "must have changed");

  int slot = 0;
  CodeBlob* sender_cb = CodeCache::find_blob_and_oopmap(sender_pc, slot);
  return sender_cb != nullptr
    ? frame(sender_sp, sender_sp, *link_addr, sender_pc, sender_cb, slot == -1 ? nullptr : sender_cb->oop_map_for_slot(slot, sender_pc))
    : frame(sender_sp, sender_sp, *link_addr, sender_pc);
}

template <typename ConfigT>
template<typename FKind> frame Freeze<ConfigT>::new_hframe(frame& f, frame& caller, int num_oops) {
  assert (FKind::is_instance(f), "");
  assert (!caller.is_interpreted_frame() || caller.unextended_sp() == (intptr_t*)caller.at<true>(frame::interpreter_frame_last_sp_offset), "");

  intptr_t *sp, *fp; // sp is really our unextended_sp
  if (FKind::interpreted) {
    assert ((intptr_t*)f.at<false>(frame::interpreter_frame_last_sp_offset) == nullptr || f.unextended_sp() == (intptr_t*)f.at<false>(frame::interpreter_frame_last_sp_offset), "");
    bool overlap_caller = caller.is_interpreted_frame() || caller.is_empty();
    fp = caller.unextended_sp() - ((intptr_t*)f.at(frame::interpreter_frame_locals_offset) - f.fp()) + (overlap_caller ? Interpreted::stack_argsize(f) : 0) - 1;
    sp = fp - (f.fp() - f.unextended_sp());
    assert (sp <= fp && fp <= caller.unextended_sp(), "");
    caller.set_sp(fp + frame::sender_sp_offset);
  } else {
    fp = *(intptr_t**)(f.sp() - frame::sender_sp_offset); // we need to re-read fp because it may be an oop and we might have had a safepoint in finalize_freeze, after constructing f.
    int fsize = FKind::size(f);
    sp = caller.unextended_sp() - fsize;
    if (caller.is_interpreted_frame()) {
      int argsize = FKind::stack_argsize(f);
      sp -= argsize;
    }
    caller.set_sp(sp + fsize);
  }

  assert (_cont.tail()->is_in_chunk(sp), "sp: " INTPTR_FORMAT " caller.sp(): " INTPTR_FORMAT " start_address: " INTPTR_FORMAT, p2i(sp), p2i(caller.sp()), p2i(_cont.tail()->start_address()));
  return frame(sp, sp, fp, f.pc(), nullptr, nullptr, false);
}

template <typename ConfigT>
template <typename FKind, bool bottom>
inline void Freeze<ConfigT>::patch_pd(frame& hf, const frame& caller) {
  if (caller.is_interpreted_frame()) {
    assert (!caller.is_empty(), "");
    patch_callee_link_relative(caller, caller.fp());
  } else {
    patch_callee_link(caller, caller.fp());
  }
}

template <typename ConfigT>
inline void Freeze<ConfigT>::patch_chunk_pd(intptr_t* vsp, intptr_t* hsp) {
  *(vsp - frame::sender_sp_offset) = *(hsp - frame::sender_sp_offset);
}

template <typename ConfigT>
inline frame Thaw<ConfigT>::new_entry_frame() {
  // if (Interpreter::contains(_cont.entryPC())) _cont.set_entrySP(_cont.entrySP() - 1);
  intptr_t* sp = _cont.entrySP();
  return frame(sp, sp, _cont.entryFP(), _cont.entryPC()); // TODO PERF: This finds code blob and computes deopt state
}

template <typename ConfigT>
template<typename FKind> frame Thaw<ConfigT>::new_frame(const frame& hf, intptr_t* vsp, frame& caller) {
  assert (FKind::is_instance(hf), "");

  if (FKind::interpreted) {
    // intptr_t* sp = vsp - (hsp - hf.sp());
    intptr_t* hsp = hf.unextended_sp();
    intptr_t* fp = vsp + (hf.fp() - hsp);
    DEBUG_ONLY(intptr_t* unextended_sp = fp + *hf.addr_at(frame::interpreter_frame_last_sp_offset);)
    assert (vsp == unextended_sp, "vsp: " INTPTR_FORMAT " unextended_sp: " INTPTR_FORMAT, p2i(vsp), p2i(unextended_sp));
    caller.set_sp(fp + frame::sender_sp_offset);
    return frame(vsp, vsp, fp, hf.pc());
  } else {
    assert (hf.cb() != nullptr && hf.oop_map() != nullptr, "");
    intptr_t* fp = *(intptr_t**)(hf.sp() - frame::sender_sp_offset); // we need to re-read fp because it may be an oop and we might have fixed the frame.
    return frame(vsp, vsp, fp, hf.pc(), hf.cb(), hf.oop_map()); // TODO PERF : this computes deopt state; is it necessary?
  }
}

template <typename ConfigT>
inline intptr_t* Thaw<ConfigT>::align(const frame& hf, intptr_t* vsp, frame& caller, bool bottom) {
  // if (caller.is_interpreted_frame()) {
  //   // Deoptimization likes ample room between interpreted frames and compiled frames. 
  //   // This is due to caller_adjustment calculation in Deoptimization::fetch_unroll_info_helper.
  //   // An attempt to simplify that calculation and make more room during deopt has failed some tests.

  //   int addedWords = 0;

  //   // SharedRuntime::gen_i2c_adapter makes room that's twice as big as required for the stack-passed arguments by counting slots but subtracting words from rsp 
  //   assert (VMRegImpl::stack_slot_size == 4, "");
  //   int argsize = hf.compiled_frame_stack_argsize();
  //   assert (argsize >= 0, "");
  //   addedWords += (argsize /* / 2*/) >> LogBytesPerWord; // Not sure why dividing by 2 is not big enough.

  //   log_develop_trace(jvmcont)("Aligning compiled frame 0: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - addedWords));
  //   vsp -= addedWords;
  //   log_develop_trace(jvmcont)("Aligning sender sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(caller.sp()), p2i(caller.sp() - addedWords));
  //   caller.set_sp(caller.sp() - addedWords);
  // }
#ifdef _LP64
  if (((intptr_t)vsp & 0xf) != 0) {
    log_develop_trace(jvmcont)("Aligning compiled frame 1: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(vsp), p2i(vsp - 1));
    assert(caller.is_interpreted_frame() || (bottom && hf.compiled_frame_stack_argsize() % 2 != 0), "");
    vsp--;

    log_develop_trace(jvmcont)("Aligning sender sp: " INTPTR_FORMAT " -> " INTPTR_FORMAT, p2i(caller.sp()), p2i(caller.sp() - 1));
    caller.set_sp(caller.sp() - 1);
  }
  assert((intptr_t)vsp % 16 == 0, "");
#endif

  return vsp;
}

template <typename ConfigT>
template<typename FKind, bool bottom>
inline void Thaw<ConfigT>::patch_pd(frame& f, const frame& caller) {
  assert (!bottom || caller.fp() == _cont.entryFP(), "caller.fp: " INTPTR_FORMAT " entryFP: " INTPTR_FORMAT, p2i(caller.fp()), p2i(_cont.entryFP()));
  patch_callee_link(caller, caller.fp());
}

template <typename ConfigT>
intptr_t* Thaw<ConfigT>::push_interpreter_return_frame(intptr_t* sp) {
  address pc = StubRoutines::cont_interpreter_forced_preempt_return();
  intptr_t* fp = *(intptr_t**)(sp - frame::sender_sp_offset);

  log_develop_trace(jvmcont)("push_interpreter_return_frame initial sp: " INTPTR_FORMAT " final sp: " INTPTR_FORMAT " fp: " INTPTR_FORMAT, p2i(sp), p2i(sp - ContinuationHelper::frame_metadata), p2i(fp));

  sp -= ContinuationHelper::frame_metadata;
  *(address*)(sp - SENDER_SP_RET_ADDRESS_OFFSET) = pc;
  *(intptr_t**)(sp - frame::sender_sp_offset) = fp;
  return sp;
}

template <typename ConfigT>
void Thaw<ConfigT>::patch_chunk_pd(intptr_t* sp) {
  intptr_t* fp = _cont.entryFP();
  *(intptr_t**)(sp - frame::sender_sp_offset) = fp;
  log_develop_trace(jvmcont)("thaw_chunk patching fp at " INTPTR_FORMAT " to " INTPTR_FORMAT, p2i(sp - frame::sender_sp_offset), p2i(fp));
}

template <typename ConfigT>
inline void Thaw<ConfigT>::prefetch_chunk_pd(void* start, int size) {
  size <<= LogBytesPerWord;
  Prefetch::read_streaming(start, size);
  Prefetch::read_streaming(start, size - 64);
}

template <typename ConfigT>
inline intptr_t* Thaw<ConfigT>::align_chunk(intptr_t* vsp) {
#ifdef _LP64
  vsp = align_down(vsp, 16);
  assert((intptr_t)vsp % 16 == 0, "");
#endif
  return vsp;
}
////////

#endif // CPU_X86_CONTINUATION_X86_INLINE_HPP

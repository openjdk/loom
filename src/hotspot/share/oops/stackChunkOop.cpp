/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "code/compiledMethod.hpp"
#include "code/scopeDesc.hpp"
#include "memory/memRegion.hpp"
#include "oops/instanceStackChunkKlass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/stackChunkOop.inline.hpp"
#include "runtime/frame.hpp"
#include "runtime/registerMap.hpp"

#ifdef ASSERT
bool stackChunkOopDesc::verify(size_t* out_size, int* out_oops, int* out_frames, int* out_interpreted_frames) {
  return InstanceStackChunkKlass::verify(this, out_size, out_oops, out_frames, out_interpreted_frames);
}
#endif

bool stackChunkOopDesc::should_fix() const {
  const bool concurrent_gc = (UseZGC || UseShenandoahGC);
  return
    UseCompressedOops
      ? (concurrent_gc ? should_fix<narrowOop, true>()
                       : should_fix<narrowOop, false>())
      : (concurrent_gc ? should_fix<oop,       true>()
                       : should_fix<oop,       false>());
}

frame stackChunkOopDesc::top_frame(RegisterMap* map) {
  // tty->print_cr(">>> stackChunkOopDesc::top_frame this: %p map: %p map->chunk: %p", this, map, (stackChunkOopDesc*)map->stack_chunk()());
  StackChunkFrameStream<true> fs(this);

  map->set_stack_chunk(this);
  fs.initialize_register_map(map);
  // if (map->update_map() && should_fix()) InstanceStackChunkKlass::fix_frame<true, false>(fs, map);

  frame f = fs.to_frame();
  relativize_frame(f);
  f.set_frame_index(0);
  return f;
}

frame stackChunkOopDesc::sender(const frame& f, RegisterMap* map) {
  // tty->print_cr(">>> stackChunkOopDesc::sender this: %p map: %p map->chunk: %p", this, map, (stackChunkOopDesc*)map->stack_chunk()()); derelativize(f).print_on<true>(tty);
  assert (map->in_cont(), "");
  assert (!map->include_argument_oops(), "");
  assert (!f.is_empty(), "");
  assert (map->stack_chunk() == this, "");
  assert (this != nullptr, "");
  assert (!is_empty(), "");

  int index = f.frame_index();
  StackChunkFrameStream<true> fs(this, derelativize(f));
  fs.next(map);

  if (!fs.is_done()) {
    frame sender = fs.to_frame();
    assert (is_usable_in_chunk(sender.unextended_sp()), "");
    relativize_frame(sender);

    sender.set_frame_index(index+1);
    return sender;
  }

  if (parent() != (oop)nullptr) {
    assert (!parent()->is_empty(), "");
    return parent()->top_frame(map);
  }

  return Continuation::continuation_parent_frame(map);
}

static int num_java_frames(CompiledMethod* cm, address pc) {
  int count = 0;
  for (ScopeDesc* scope = cm->scope_desc_at(pc); scope != nullptr; scope = scope->sender())
    count++;
  return count;
}

static int num_java_frames(const StackChunkFrameStream<true>& f) {
  assert (f.is_interpreted() || (f.cb() != nullptr && f.cb()->is_compiled() && f.cb()->as_compiled_method()->is_java_method()), "");
  return f.is_interpreted() ? 1 : num_java_frames(f.cb()->as_compiled_method(), f.orig_pc());
}

int stackChunkOopDesc::num_java_frames() const {
  int n = 0;
  for (StackChunkFrameStream<true> f(const_cast<stackChunkOopDesc*>(this)); !f.is_done(); f.next(SmallRegisterMap::instance)) {
    if (!f.is_stub()) n += ::num_java_frames(f);
  }
  return n;
}

void stackChunkOopDesc::print_on(bool verbose, outputStream* st) const {
  if (this == nullptr) {
    st->print_cr("NULL");
  } else if (*((juint*)this) == badHeapWordVal) {
    st->print("BAD WORD");
  } else if (*((juint*)this) == badMetaWordVal) {
    st->print("BAD META WORD");
  } else {
    InstanceStackChunkKlass::print_chunk(const_cast<stackChunkOopDesc*>(this), verbose, st);
  }
}


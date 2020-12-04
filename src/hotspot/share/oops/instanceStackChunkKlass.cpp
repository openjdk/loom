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

#include "precompiled.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/systemDictionary.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/oopFactory.hpp"
#include "memory/universe.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/instanceOop.hpp"
#include "oops/oop.inline.hpp"
#include "oops/symbol.hpp"
#include "runtime/continuation.hpp"
#include "utilities/macros.hpp"

int InstanceStackChunkKlass::_offset_of_stack = 0;

InstanceStackChunkKlass::InstanceStackChunkKlass(const ClassFileParser& parser)
 : InstanceKlass(parser, InstanceKlass::_misc_kind_stack_chunk, ID) {
   // see oopDesc::size_given_klass TODO perf
   const jint lh = Klass::instance_layout_helper(size_helper(), true);
   set_layout_helper(lh);
   assert (layout_helper_is_instance(layout_helper()), "");
   assert (layout_helper_needs_slow_path(layout_helper()), "");
}

int InstanceStackChunkKlass::instance_size(int stack_size_in_words) const {
  return align_object_size(size_helper() + stack_size_in_words);
}

int InstanceStackChunkKlass::oop_size(oop obj) const {
  // see oopDesc::size_given_klass
  return instance_size(jdk_internal_misc_StackChunk::size(obj));
}

#if INCLUDE_CDS
void InstanceStackChunkKlass::serialize_offsets(SerializeClosure* f) {
  f->do_u4((u4*)&_offset_of_stack);
}
#endif

void InstanceStackChunkKlass::print_chunk(oop chunk, bool verbose, outputStream* st) {
  if (chunk == (oop)NULL) {
    st->print_cr("CHUNK NULL");
    return;
  }
  // tty->print_cr("CHUNK " INTPTR_FORMAT " ::", p2i((oopDesc*)chunk));
  assert(jdk_internal_misc_StackChunk::is_stack_chunk(chunk), "");
  // HeapRegion* hr = G1CollectedHeap::heap()->heap_region_containing(chunk);
  st->print_cr("CHUNK " INTPTR_FORMAT " - " INTPTR_FORMAT " :: 0x%lx", p2i((oopDesc*)chunk), p2i((HeapWord*)(chunk + chunk->size())), chunk->identity_hash());
  st->print("CHUNK " INTPTR_FORMAT " young: %d gc_mode: %d, size: %d argsize: %d sp: %d num_frames: %d num_oops: %d parent: " INTPTR_FORMAT,
    p2i((oopDesc*)chunk), !Universe::heap()->requires_barriers(chunk), jdk_internal_misc_StackChunk::gc_mode(chunk),
    jdk_internal_misc_StackChunk::size(chunk), jdk_internal_misc_StackChunk::argsize(chunk), jdk_internal_misc_StackChunk::sp(chunk),
    jdk_internal_misc_StackChunk::numFrames(chunk), jdk_internal_misc_StackChunk::numOops(chunk),
    p2i((oopDesc*)jdk_internal_misc_StackChunk::parent(chunk)));

  intptr_t* start = jdk_internal_misc_StackChunk::start_address(chunk);
  intptr_t* end   = jdk_internal_misc_StackChunk::end_address(chunk);

  if (verbose) {
    intptr_t* sp = start + jdk_internal_misc_StackChunk::sp(chunk);
    st->cr();
    st->print_cr("------ chunk frames end: " INTPTR_FORMAT, p2i(end));
    if (sp < end) {
      RegisterMap map(NULL, true, false, false);
      frame f(sp);
      st->print_cr("-- frame size: %d argsize: %d", f.frame_size(), f.compiled_frame_stack_argsize());
      f.print_on(st);
      while (f.sp() + ((f.frame_size() + f.compiled_frame_stack_argsize()) >> LogBytesPerWord) < end) {
        f = f.sender(&map);
        st->print_cr("-- frame size: %d argsize: %d", f.frame_size(), f.compiled_frame_stack_argsize());
        f.print_on(st);
      }
    }
    st->print_cr("------");
  } else {
    st->print_cr(" frames: %d", count_frames(chunk));
  }
}

#ifndef PRODUCT
void InstanceStackChunkKlass::oop_print_on(oop obj, outputStream* st) {
  InstanceKlass::oop_print_on(obj, st);
  print_chunk(obj, false, st);
}
#endif
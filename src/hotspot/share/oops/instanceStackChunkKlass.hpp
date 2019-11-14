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

#ifndef SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP
#define SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP

#include "oops/instanceKlass.hpp"
#include "utilities/macros.hpp"

class ClassFileParser;

// An InstanceStackChunkKlass is a specialization of the InstanceKlass. 
// It has a header containing metadata, and a blob containing a stack segment
// (some integral number of stack frames),

class InstanceStackChunkKlass: public InstanceKlass {
  friend class VMStructs;
  friend class InstanceKlass;
public:
  static const KlassID ID = InstanceStackChunkKlassID;

private:
  static int _offset_of_stack;

  InstanceStackChunkKlass(const ClassFileParser& parser);

public:
  InstanceStackChunkKlass() { assert(DumpSharedSpaces || UseSharedSpaces, "only for CDS"); }

  // Casting from Klass*
  static InstanceStackChunkKlass* cast(Klass* k) {
    assert(InstanceKlass::cast(k)->is_stack_chunk_instance_klass(), "");
    return static_cast<InstanceStackChunkKlass*>(k);
  }

  int instance_size(int stack_size_in_words) const;

  // Returns the size of the instance including the stack data.
  virtual int oop_size(oop obj) const;

  static void serialize_offsets(class SerializeClosure* f) NOT_CDS_RETURN;
  
  // Stack offset is an offset into the Heap
  static HeapWord* start_of_stack(oop obj) {
    return (HeapWord*)(cast_from_oop<intptr_t>(obj) + offset_of_stack());
  }

  static void init_offset_of_stack() {
    // Cache the offset of the static fields in the Class instance
    assert(_offset_of_stack == 0, "once");
    _offset_of_stack = InstanceStackChunkKlass::cast(SystemDictionary::StackChunk_klass())->size_helper() << LogHeapWordSize;
  }

  static int offset_of_stack() {
    return _offset_of_stack;
  }

  // Oop fields (and metadata) iterators
  //
  // The InstanceClassLoaderKlass iterators also visit the CLD pointer (or mirror of anonymous klasses.)

  // Forward iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate(oop obj, OopClosureType* closure);

  // Reverse iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_reverse(oop obj, OopClosureType* closure);

  // Bounded range iteration
  // Iterate over the oop fields and metadata.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr);

private:
  // Iterate over the stack.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_stack(oop obj, OopClosureType* closure);

  // Iterate over the stack.
  template <typename T, class OopClosureType>
  inline void oop_oop_iterate_stack_bounded(oop obj, OopClosureType* closure, MemRegion mr);
};

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_HPP

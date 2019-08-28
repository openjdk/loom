/* Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP
#define SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

#include "classfile/javaClasses.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceStackChunkKlass.hpp"
#include "oops/klass.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/continuation.inline.hpp"
#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"

// class DecoratorOopClosure : public BasicOopIterateClosure {
//   OopIterateClosure* _cl;
//  public:
//   DecoratorOopClosure(OopIterateClosure* cl) : _cl(cl) {}

//   virtual bool do_metadata() { return _cl->do_metadata(); }

//   virtual void do_klass(Klass* k) { _cl->do_klass(k); }
//   virtual void do_cld(ClassLoaderData* cld) { _cl->do_cld(cld); }

//   virtual void do_oop(oop* o) {
//     _cl->do_oop(o);
//   }
//   virtual void do_oop(narrowOop* o) {
//     if (!CompressedOops::is_null(*o)) {
//       oop obj = CompressedOops::decode_not_null(*o);
//     }
//     _cl->do_oop(o);
//   }
// };

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate(oop obj, OopClosureType* closure) {
  InstanceKlass::oop_oop_iterate<T>(obj, closure);
  oop_oop_iterate_stack<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_reverse(oop obj, OopClosureType* closure) {
  InstanceKlass::oop_oop_iterate_reverse<T>(obj, closure);
  oop_oop_iterate_stack<T>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_bounded(oop obj, OopClosureType* closure, MemRegion mr) {
  InstanceKlass::oop_oop_iterate_bounded<T>(obj, closure, mr);
  oop_oop_iterate_stack_bounded<T>(obj, closure, mr);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_stack(oop obj, OopClosureType* closure) {
  Continuation::stack_chunk_iterate_stack<OopClosureType>(obj, closure);
}

template <typename T, class OopClosureType>
void InstanceStackChunkKlass::oop_oop_iterate_stack_bounded(oop obj, OopClosureType* closure, MemRegion mr) {
  Continuation::stack_chunk_iterate_stack_bounded<OopClosureType>(obj, closure, mr);
}

#endif // SHARE_OOPS_INSTANCESTACKCHUNKKLASS_INLINE_HPP

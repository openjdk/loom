/*
 * Copyright (c) 1997, 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OOPS_TYPEARRAYOOP_INLINE_HPP
#define SHARE_OOPS_TYPEARRAYOOP_INLINE_HPP

#include "oops/typeArrayOop.hpp"

#include "oops/access.inline.hpp"
#include "oops/arrayOop.hpp"
#include "oops/oop.inline.hpp"

size_t typeArrayOopDesc::object_size(const TypeArrayKlass* tk) const {
  return object_size(tk->layout_helper(), length());
}

inline jchar*    typeArrayOopDesc::char_base()   const { return (jchar*)   base(T_CHAR); }
inline jboolean* typeArrayOopDesc::bool_base()   const { return (jboolean*)base(T_BOOLEAN); }
inline jbyte*    typeArrayOopDesc::byte_base()   const { return (jbyte*)   base(T_BYTE); }
inline jint*     typeArrayOopDesc::int_base()    const { return (jint*)    base(T_INT); }
inline jlong*    typeArrayOopDesc::long_base()   const { return (jlong*)   base(T_LONG); }
inline jshort*   typeArrayOopDesc::short_base()  const { return (jshort*)  base(T_SHORT); }
inline jfloat*   typeArrayOopDesc::float_base()  const { return (jfloat*)  base(T_FLOAT); }
inline jdouble*  typeArrayOopDesc::double_base() const { return (jdouble*) base(T_DOUBLE); }

inline jbyte* typeArrayOopDesc::byte_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &byte_base()[which];
}

inline jboolean* typeArrayOopDesc::bool_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &bool_base()[which];
}

inline jchar* typeArrayOopDesc::char_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &char_base()[which];
}

inline jint* typeArrayOopDesc::int_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &int_base()[which];
}

inline jshort* typeArrayOopDesc::short_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &short_base()[which];
}

inline jushort* typeArrayOopDesc::ushort_at_addr(int which) const {  // for field descriptor arrays
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return (jushort*) &short_base()[which];
}

inline jlong* typeArrayOopDesc::long_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &long_base()[which];
}

inline jfloat* typeArrayOopDesc::float_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &float_base()[which];
}

inline jdouble* typeArrayOopDesc::double_at_addr(int which) const {
  assert(is_within_bounds(which), "index %d out of bounds %d", which, length());
  return &double_base()[which];
}

inline jbyte typeArrayOopDesc::byte_at(int which) const {
  return *byte_at_addr(which);
}
inline void typeArrayOopDesc::byte_at_put(int which, jbyte contents) {
  *byte_at_addr(which) = contents;
}

inline jboolean typeArrayOopDesc::bool_at(int which) const {
  return *bool_at_addr(which);
}
inline void typeArrayOopDesc::bool_at_put(int which, jboolean contents) {
  *bool_at_addr(which) = jboolean(contents & 1);
}

inline jchar typeArrayOopDesc::char_at(int which) const {
  return *char_at_addr(which);
}
inline void typeArrayOopDesc::char_at_put(int which, jchar contents) {
  *char_at_addr(which) = contents;
}

inline jint typeArrayOopDesc::int_at(int which) const {
  return *int_at_addr(which);
}
inline void typeArrayOopDesc::int_at_put(int which, jint contents) {
  *int_at_addr(which) = contents;
}

inline jshort typeArrayOopDesc::short_at(int which) const {
  return *short_at_addr(which);
}
inline void typeArrayOopDesc::short_at_put(int which, jshort contents) {
  *short_at_addr(which) = contents;
}

inline jushort typeArrayOopDesc::ushort_at(int which) const {
  return *ushort_at_addr(which);
}

inline void typeArrayOopDesc::ushort_at_put(int which, jushort contents) {
  *ushort_at_addr(which) = contents;
}

inline jlong typeArrayOopDesc::long_at(int which) const {
  return *long_at_addr(which);
}
inline void typeArrayOopDesc::long_at_put(int which, jlong contents) {
  *long_at_addr(which) = contents;
}

inline jfloat typeArrayOopDesc::float_at(int which) const {
  return *float_at_addr(which);
}

inline void typeArrayOopDesc::float_at_put(int which, jfloat contents) {
  *float_at_addr(which) = contents;
}

inline jdouble typeArrayOopDesc::double_at(int which) const {
  return *double_at_addr(which);
}

inline void typeArrayOopDesc::double_at_put(int which, jdouble contents) {
  *double_at_addr(which) = contents;
}

inline jbyte typeArrayOopDesc::byte_at_acquire(int which) const {
  return Atomic::load_acquire(byte_at_addr(which));
}
inline void typeArrayOopDesc::release_byte_at_put(int which, jbyte contents) {
  Atomic::release_store(byte_at_addr(which), contents);
}

// Java thinks Symbol arrays are just arrays of either long or int, since
// there doesn't seem to be T_ADDRESS, so this is a bit of unfortunate
// casting
#ifdef _LP64
inline Symbol* typeArrayOopDesc::symbol_at(int which) const {
  return *reinterpret_cast<Symbol**>(long_at_addr(which));
}

inline void typeArrayOopDesc::symbol_at_put(int which, Symbol* contents) {
  *reinterpret_cast<Symbol**>(long_at_addr(which)) = contents;
}
#else
inline Symbol* typeArrayOopDesc::symbol_at(int which) const {
  return *reinterpret_cast<Symbol**>(int_at_addr(which));
}
inline void typeArrayOopDesc::symbol_at_put(int which, Symbol* contents) {
  *reinterpret_cast<Symbol**>(int_at_addr(which)) = contents;
}
#endif // _LP64


#endif // SHARE_OOPS_TYPEARRAYOOP_INLINE_HPP

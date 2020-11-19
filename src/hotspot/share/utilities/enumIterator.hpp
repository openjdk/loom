/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_UTILITIES_ENUMITERATOR_HPP
#define SHARE_UTILITIES_ENUMITERATOR_HPP

#include <type_traits>
#include <limits>
#include "memory/allStatic.hpp"
#include "utilities/debug.hpp"

// Iteration support for enums.
//
// E is enum type, U is underlying type of E.
//
// case 1:
// enum has sequential enumerators, with E first and E last (inclusive).
//
// case 2:
// enum has sequential values, with U start and U end (exclusive).
// WeakProcessorPhases is an example because of oopstorage.
// This can be mapped onto case 1 by casting start/(end-1).
//
// case 3:
// enum has non-sequential non-duplicate enumerators
// Iteration could be supported via array or other sequence of enumerators.
// Don't bother.
//
// case 4:
// enum has non-sequential enumerators with duplicate values
// Not clear what iteration should mean in this case.
// Don't bother trying to figure this out.
//
//
// EnumRange -- defines the range of *one specific* iteration loop.
// EnumIterator -- the current point in the iteration loop.

// Example (see vmSymbols.hpp/cpp)
//
// ENUMERATOR_RANGE(vmSymbolID, vmSymbolID::FIRST_SID, vmSymbolID::LAST_SID)
// constexpr EnumRange<vmSymbolID> vmSymbolsRange;
// using vmSymbolsIterator = EnumIterator<vmSymbolID>;
//
// /* Without range-based for, allowed */
// for (vmSymbolsIterator it = vmSymbolsRange.begin(); it != vmSymbolsRange.end(); ++it) {
//  vmSymbolID index = *it; ....
// }
//
// /* With range-base for, not allowed by HotSpot coding style yet */
// for (vmSymbolID index : vmSymbolsRange) {
//    ....
// }

// EnumeratorRange is a traits type supporting iteration over the enumerators of T.
// Specializations must provide static const data members named
// "_first" and "_last", whose values are the smallest / largest
// (resp.) enumerator values for T. For iteration, the enumerators of
// T must have sequential values in that range.
template<typename T> struct EnumeratorRange;

// Specialize EnumeratorRange<T>.
#define ENUMERATOR_RANGE(T, First, Last)        \
  template<> struct EnumeratorRange<T> {        \
    static constexpr T _first = First;          \
    static constexpr T _last = Last;            \
  };

// A helper class for EnumIterator, computing some additional information the
// iterator uses, based on T and EnumeratorRange.
template<typename T>
class EnumIterationTraits : AllStatic {
  using RangeType = EnumeratorRange<T>;

public:
  // The underlying type for T.
  using Underlying = std::underlying_type_t<T>;

  // The first enumerator of T.
  static constexpr T _first = RangeType::_first;

  // The last enumerator of T.
  static constexpr T _last = RangeType::_last;

  static_assert(static_cast<Underlying>(_last) <
                std::numeric_limits<Underlying>::max(),
                "No one-past-the-end value for enum");

  // The value of the first enumerator of T.
  static constexpr Underlying _start = static_cast<Underlying>(_first);

  // The one-past-the-end value for T.
  static constexpr Underlying _end = static_cast<Underlying>(_last) + 1;
};

template<typename T>
class EnumIterator {
  using Traits = EnumIterationTraits<T>;

  using Underlying = typename Traits::Underlying;
  Underlying _value;

  constexpr void assert_in_bounds() const {
    assert(_value < Traits::_end, "beyond the end");
  }

public:
  // Return a beyond-the-end iterator.
  constexpr EnumIterator() : _value(Traits::_end) {}

  // Return an iterator with the indicated value.
  constexpr explicit EnumIterator(T value) :
    _value(static_cast<Underlying>(value))
  {
    assert(_value >= Traits::_start, "out of range");
    assert(_value <= Traits::_end, "out of range");
  }

  // True if the iterators designate the same enumeration value.
  constexpr bool operator==(EnumIterator other) const {
    return _value == other._value;
  }

  // True if the iterators designate different enumeration values.
  constexpr bool operator!=(EnumIterator other) const {
    return _value != other._value;
  }

  // Return the current value.
  // precondition: this is not beyond the last enumerator.
  constexpr T operator*() const {
    assert_in_bounds();
    return static_cast<T>(_value);
  }

  // Step this iterator to the next value.
  // precondition: this is not beyond the last enumerator.
  constexpr EnumIterator& operator++() {
    assert_in_bounds();
    ++_value;
    return *this;
  }

  // Return a copy and step this iterator to the next value.
  // precondition: this is not beyond the last enumerator.
  constexpr EnumIterator operator++(int) {
    assert_in_bounds();
    EnumIterator result = *this;
    ++_value;
    return result;
  }
};

template<typename T>
class EnumRange {
  using Traits = EnumIterationTraits<T>;
  using Underlying = typename Traits::Underlying;

  Underlying _start;
  Underlying _end;

public:
  using Iterator = EnumIterator<T>;

  // Default constructor gives the full range.
  constexpr EnumRange() :
    EnumRange(Traits::_first) {}

  // Range from start to the (exclusive) end of the enumerator range.
  constexpr explicit EnumRange(T start) :
    EnumRange(start, static_cast<T>(Traits::_end)) {}

  // Range from start (inclusive) to end (exclusive).
  // precondition: start <= end.
  constexpr EnumRange(T start, T end) :
    _start(static_cast<Underlying>(start)),
    _end(static_cast<Underlying>(end))
  {
    assert(Traits::_start <= _start, "out of range");
    assert(_end <= Traits::_end, "out of range");
    assert(_start <= _end, "invalid range");
  }

  // Return an iterator for the start of the range.
  constexpr Iterator begin() const {
    return Iterator(static_cast<T>(_start));
  }

  // Return an iterator for the end of the range.
  constexpr Iterator end() const {
    return Iterator(static_cast<T>(_end));
  }

  constexpr size_t size() const {
    return static_cast<size_t>(_end - _start); // _end is exclusive
  }
};

#endif // SHARE_UTILITIES_ENUMITERATOR_HPP

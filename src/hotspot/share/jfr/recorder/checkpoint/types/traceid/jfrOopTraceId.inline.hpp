/*
* Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP
#define SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP

#include "jfr/recorder/checkpoint/types/traceid/jfrOopTraceId.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceIdEpoch.hpp"

template <typename T>
inline void JfrOopTraceId<T>::store(oop ref, traceid value) {
  assert(ref != nullptr, "invariant");
  assert(value != 0, "invariant");
  T::store(ref, value);
}

template <typename T>
inline traceid JfrOopTraceId<T>::load(oop ref) {
  assert(ref != nullptr, "invariant");
  return T::load(ref);
}

template <typename T>
inline traceid JfrOopTraceId<T>::epoch(traceid value) {
  return value >> jfr_epoch_shift;
}

template <typename T>
inline traceid JfrOopTraceId<T>::id(traceid value) {
  return value & jfr_id_mask;
}

inline traceid map(traceid current_epoch_gen, traceid tid) {
  return (current_epoch_gen << jfr_epoch_shift) | tid;
}

template <typename T>
inline traceid JfrOopTraceId<T>::epoch_identity(traceid value) {
  const traceid current_epoch_gen = JfrTraceIdEpoch::epoch_generation();
  return epoch(value) != current_epoch_gen ? map(current_epoch_gen, id(value)) : value;
}

#endif // SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP

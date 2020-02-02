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

#ifndef SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP
#define SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP

#include "jfr/recorder/checkpoint/types/traceid/jfrOopTraceId.hpp"
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceIdEpoch.hpp"

const int jfr_epoch_shift = 48;
const traceid jfr_id_mask = ((((traceid)1) << jfr_epoch_shift) - 1);
const traceid jfr_epoch_mask = ~jfr_id_mask;

template <typename T>
inline bool JfrOopTraceId<T>::store(oop ref, traceid value) {
  assert(ref != NULL, "invariant");
  assert(value != 0, "invariant");
  return (traceid)T::set_jfrTraceId(ref, (jlong)value) == value;
}

template <typename T>
inline traceid JfrOopTraceId<T>::load(oop ref) {
  assert(ref != NULL, "invariant");
  return (traceid)T::jfrTraceId(ref);
}

template <typename T>
inline traceid JfrOopTraceId<T>::epoch(traceid value) {
  return value >> jfr_epoch_shift;
}

template <typename T>
inline traceid JfrOopTraceId<T>::id(traceid value) {
  return value & jfr_id_mask;
}

inline bool is_current(traceid epoch) {
  return JfrTraceIdEpoch::is_current_epoch_generation(epoch);
}

inline bool need_checkpoint(traceid epoch) {
  return !is_current(epoch);
}

inline traceid embed_current_epoch(traceid id) {
  return (JfrTraceIdEpoch::epoch_generation() << jfr_epoch_shift) | id;
}

template <typename T>
inline bool JfrOopTraceId<T>::store_current_epoch(oop ref, traceid id) {
  assert(ref != NULL, "invariant");
  assert(id != 0, "invariant");
  return store(ref, embed_current_epoch(id));
}

template <typename T>
inline bool JfrOopTraceId<T>::should_write_checkpoint(oop ref, traceid value) {
  assert(ref != NULL, "invariant");
  return need_checkpoint(epoch(value)) && store_current_epoch(ref, id(value));
}

#endif // SHARE_JFR_RECORDER_CHECKPOINT_TYPES_TRACEID_JFROOPTRACEID_INLINE_HPP

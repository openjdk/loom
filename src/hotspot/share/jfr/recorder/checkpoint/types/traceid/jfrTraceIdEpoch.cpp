/*
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "jfr/recorder/checkpoint/types/traceid/jfrTraceIdEpoch.hpp"
#include "runtime/safepoint.hpp"

u2 JfrTraceIdEpoch::_generation = 0;
JfrSignal JfrTraceIdEpoch::_tag_state;
bool JfrTraceIdEpoch::_epoch_state = false;
bool JfrTraceIdEpoch::_synchronizing = false;

void JfrTraceIdEpoch::begin_epoch_shift() {
  assert(SafepointSynchronize::is_at_safepoint(), "invariant");
  _synchronizing = true;
  OrderAccess::fence();
}

void JfrTraceIdEpoch::end_epoch_shift() {
  assert(SafepointSynchronize::is_at_safepoint(), "invariant");
  assert(_synchronizing, "invariant");
  _epoch_state = !_epoch_state;
  ++_generation;
  if (0 == _generation) {
    ++_generation;
  }
  assert(_generation != 0, "invariant");
  OrderAccess::storestore();
  _synchronizing = false;
}


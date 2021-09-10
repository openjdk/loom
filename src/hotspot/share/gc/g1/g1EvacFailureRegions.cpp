/*
 * Copyright (c) 2021, Huawei Technologies Co. Ltd. All rights reserved.
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

#include "gc/g1/g1CollectedHeap.hpp"
#include "gc/g1/g1EvacFailureRegions.hpp"
#include "gc/g1/heapRegion.hpp"
#include "memory/allocation.hpp"
#include "runtime/atomic.hpp"
#include "utilities/bitMap.inline.hpp"


G1EvacFailureRegions::G1EvacFailureRegions() :
  _regions_failed_evacuation(mtGC) {
}

G1EvacFailureRegions::~G1EvacFailureRegions() {
  FREE_C_HEAP_ARRAY(uint, _evac_failure_regions);
}

void G1EvacFailureRegions::initialize(uint max_regions) {
  Atomic::store(&_evac_failure_regions_cur_length, 0u);
  _max_regions = max_regions;
  _regions_failed_evacuation.resize(_max_regions);
  _evac_failure_regions = NEW_C_HEAP_ARRAY(uint, _max_regions, mtGC);
}

void G1EvacFailureRegions::par_iterate(HeapRegionClosure* closure,
                                       HeapRegionClaimer* _hrclaimer,
                                       uint worker_id) {
  G1CollectedHeap::heap()->par_iterate_regions_array_part_from(closure,
                                                               _hrclaimer,
                                                               _evac_failure_regions,
                                                               0,
                                                               Atomic::load(&_evac_failure_regions_cur_length),
                                                               worker_id);
}

void G1EvacFailureRegions::reset() {
  Atomic::store(&_evac_failure_regions_cur_length, 0u);
  _regions_failed_evacuation.clear();
}

bool G1EvacFailureRegions::contains(uint region_idx) const {
  assert(region_idx < _max_regions, "must be");
  return _regions_failed_evacuation.par_at(region_idx, memory_order_relaxed);
}

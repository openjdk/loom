/*
 * Copyright (c) 2017, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "gc/g1/g1CollectedHeap.inline.hpp"
#include "gc/g1/g1ConcurrentMarkBitMap.inline.hpp"
#include "gc/g1/g1FullCollector.inline.hpp"
#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/g1FullGCMarker.hpp"
#include "gc/g1/g1FullGCOopClosures.inline.hpp"
#include "gc/g1/g1FullGCPrepareTask.inline.hpp"
#include "gc/g1/g1HotCardCache.hpp"
#include "gc/g1/heapRegion.inline.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/referenceProcessor.hpp"
#include "logging/log.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/ticks.hpp"

G1DetermineCompactionQueueClosure::G1DetermineCompactionQueueClosure(G1FullCollector* collector) :
  _g1h(G1CollectedHeap::heap()),
  _collector(collector),
  _cur_worker(0) { }

bool G1FullGCPrepareTask::G1CalculatePointersClosure::do_heap_region(HeapRegion* hr) {
  uint region_idx = hr->hrm_index();
  assert(_collector->is_compaction_target(region_idx), "must be");

  assert(!hr->is_pinned(), "must be");
  assert(!hr->is_closed_archive(), "must be");
  assert(!hr->is_open_archive(), "must be");

  prepare_for_compaction(hr);

  return false;
}

G1FullGCPrepareTask::G1FullGCPrepareTask(G1FullCollector* collector) :
    G1FullGCTask("G1 Prepare Compact Task", collector),
    _has_free_compaction_targets(false),
    _hrclaimer(collector->workers()) {
}

void G1FullGCPrepareTask::set_has_free_compaction_targets() {
  if (!_has_free_compaction_targets) {
    _has_free_compaction_targets = true;
  }
}

bool G1FullGCPrepareTask::has_free_compaction_targets() {
  return _has_free_compaction_targets;
}

void G1FullGCPrepareTask::work(uint worker_id) {
  Ticks start = Ticks::now();
  // Calculate the target locations for the objects in the non-free regions of
  // the compaction queues provided by the associate compaction point.
  {
    G1FullGCCompactionPoint* compaction_point = collector()->compaction_point(worker_id);
    G1CalculatePointersClosure closure(collector(), compaction_point);

    for (GrowableArrayIterator<HeapRegion*> it = compaction_point->regions()->begin();
         it != compaction_point->regions()->end();
         ++it) {
      closure.do_heap_region(*it);
    }
    compaction_point->update();
    // Determine if there are any unused compaction targets. This is only the case if
    // there are
    // - any regions in queue, so no free ones either.
    // - and the current region is not the last one in the list.
    if (compaction_point->has_regions() &&
        compaction_point->current_region() != compaction_point->regions()->last()) {
      set_has_free_compaction_targets();
    }
  }

  // Clear region metadata that is invalid after GC for all regions.
  {
    G1ResetMetadataClosure closure(collector());
    G1CollectedHeap::heap()->heap_region_par_iterate_from_start(&closure, &_hrclaimer);
  }
  log_task("Prepare compaction task", worker_id, start);
}

G1FullGCPrepareTask::G1CalculatePointersClosure::G1CalculatePointersClosure(G1FullCollector* collector,
                                                                            G1FullGCCompactionPoint* cp) :
  _g1h(G1CollectedHeap::heap()),
  _collector(collector),
  _bitmap(collector->mark_bitmap()),
  _cp(cp) { }

G1FullGCPrepareTask::G1ResetMetadataClosure::G1ResetMetadataClosure(G1FullCollector* collector) :
  _g1h(G1CollectedHeap::heap()),
  _collector(collector) { }

void G1FullGCPrepareTask::G1ResetMetadataClosure::reset_region_metadata(HeapRegion* hr) {
  hr->rem_set()->clear();
  hr->clear_cardtable();

  G1HotCardCache* hcc = _g1h->hot_card_cache();
  if (G1HotCardCache::use_cache()) {
    hcc->reset_card_counts(hr);
  }
}

bool G1FullGCPrepareTask::G1ResetMetadataClosure::do_heap_region(HeapRegion* hr) {
  uint const region_idx = hr->hrm_index();
  if (!_collector->is_compaction_target(region_idx)) {
    assert(!hr->is_free(), "all free regions should be compaction targets");
    assert(_collector->is_skip_compacting(region_idx) || hr->is_closed_archive(), "must be");
    if (hr->needs_scrubbing_during_full_gc()) {
      scrub_skip_compacting_region(hr, hr->is_young());
    }
  }

  // Reset data structures not valid after Full GC.
  reset_region_metadata(hr);

  return false;
}

G1FullGCPrepareTask::G1PrepareCompactLiveClosure::G1PrepareCompactLiveClosure(G1FullGCCompactionPoint* cp) :
    _cp(cp) { }

size_t G1FullGCPrepareTask::G1PrepareCompactLiveClosure::apply(oop object) {
  size_t size = object->size();
  _cp->forward(object, size);
  return size;
}

void G1FullGCPrepareTask::G1CalculatePointersClosure::prepare_for_compaction(HeapRegion* hr) {
  if (!_collector->is_free(hr->hrm_index())) {
    G1PrepareCompactLiveClosure prepare_compact(_cp);
    hr->apply_to_marked_objects(_bitmap, &prepare_compact);
  }
}

void G1FullGCPrepareTask::G1ResetMetadataClosure::scrub_skip_compacting_region(HeapRegion* hr, bool update_bot_for_live) {
  assert(hr->needs_scrubbing_during_full_gc(), "must be");

  HeapWord* limit = hr->top();
  HeapWord* current_obj = hr->bottom();
  G1CMBitMap* bitmap = _collector->mark_bitmap();

  while (current_obj < limit) {
    if (bitmap->is_marked(current_obj)) {
      oop current = cast_to_oop(current_obj);
      size_t size = current->size();
      if (update_bot_for_live) {
        hr->update_bot_for_block(current_obj, current_obj + size);
      }
      current_obj += size;
      continue;
    }
    // Found dead object, which is potentially unloaded, scrub to next
    // marked object.
    HeapWord* scrub_start = current_obj;
    HeapWord* scrub_end = bitmap->get_next_marked_addr(scrub_start, limit);
    assert(scrub_start != scrub_end, "must advance");
    hr->fill_range_with_dead_objects(scrub_start, scrub_end);

    current_obj = scrub_end;
  }
}

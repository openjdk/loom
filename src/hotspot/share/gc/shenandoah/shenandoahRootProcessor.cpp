/*
 * Copyright (c) 2015, 2020, Red Hat, Inc. All rights reserved.
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

#include "classfile/classLoaderData.hpp"
#include "code/nmethod.hpp"
#include "gc/shenandoah/shenandoahClosures.inline.hpp"
#include "gc/shenandoah/shenandoahConcurrentRoots.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "memory/iterator.hpp"
#include "memory/resourceArea.hpp"
#include "prims/jvmtiExport.hpp"
#include "runtime/thread.hpp"

ShenandoahThreadRoots::ShenandoahThreadRoots(ShenandoahPhaseTimings::Phase phase, bool is_par) :
  _phase(phase), _is_par(is_par) {
  Threads::change_thread_claim_token();
}

void ShenandoahThreadRoots::oops_do(OopClosure* oops_cl, CodeBlobClosure* code_cl, uint worker_id) {
  ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::ThreadRoots, worker_id);
  ResourceMark rm;
  Threads::possibly_parallel_oops_do(_is_par, oops_cl, code_cl);
}

void ShenandoahThreadRoots::threads_do(ThreadClosure* tc, uint worker_id) {
  ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::ThreadRoots, worker_id);
  ResourceMark rm;
  Threads::possibly_parallel_threads_do(_is_par, tc);
}

ShenandoahThreadRoots::~ShenandoahThreadRoots() {
  Threads::assert_all_threads_claimed();
}

ShenandoahStringDedupRoots::ShenandoahStringDedupRoots(ShenandoahPhaseTimings::Phase phase) : _phase(phase) {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedup::gc_prologue(false);
  }
}

ShenandoahStringDedupRoots::~ShenandoahStringDedupRoots() {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedup::gc_epilogue();
  }
}

void ShenandoahStringDedupRoots::oops_do(BoolObjectClosure* is_alive, OopClosure* keep_alive, uint worker_id) {
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::parallel_oops_do(_phase, is_alive, keep_alive, worker_id);
  }
}

ShenandoahConcurrentStringDedupRoots::ShenandoahConcurrentStringDedupRoots(ShenandoahPhaseTimings::Phase phase) :
  _phase(phase) {
}

void ShenandoahConcurrentStringDedupRoots::prologue() {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedupTable_lock->lock_without_safepoint_check();
    StringDedupQueue_lock->lock_without_safepoint_check();
    StringDedup::gc_prologue(true);
  }
}

void ShenandoahConcurrentStringDedupRoots::epilogue() {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedup::gc_epilogue();
    StringDedupQueue_lock->unlock();
    StringDedupTable_lock->unlock();
  }
}

void ShenandoahConcurrentStringDedupRoots::oops_do(BoolObjectClosure* is_alive, OopClosure* keep_alive, uint worker_id) {
  if (ShenandoahStringDedup::is_enabled()) {
    assert_locked_or_safepoint_weak(StringDedupQueue_lock);
    assert_locked_or_safepoint_weak(StringDedupTable_lock);

    StringDedupUnlinkOrOopsDoClosure sd_cl(is_alive, keep_alive);
    {
      ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::StringDedupQueueRoots, worker_id);
      StringDedupQueue::unlink_or_oops_do(&sd_cl);
    }

    {
      ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::StringDedupTableRoots, worker_id);
      StringDedupTable::unlink_or_oops_do(&sd_cl, worker_id);
    }
  }
}

ShenandoahCodeCacheRoots::ShenandoahCodeCacheRoots(ShenandoahPhaseTimings::Phase phase) : _phase(phase) {
  nmethod::oops_do_marking_prologue();
}

void ShenandoahCodeCacheRoots::code_blobs_do(CodeBlobClosure* blob_cl, uint worker_id) {
  ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::CodeCacheRoots, worker_id);
  _coderoots_iterator.possibly_parallel_blobs_do(blob_cl);
}

ShenandoahCodeCacheRoots::~ShenandoahCodeCacheRoots() {
  nmethod::oops_do_marking_epilogue();
}

ShenandoahRootProcessor::ShenandoahRootProcessor(ShenandoahPhaseTimings::Phase phase) :
  _heap(ShenandoahHeap::heap()),
  _phase(phase),
  _worker_phase(phase) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must at safepoint");
}

ShenandoahRootScanner::ShenandoahRootScanner(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _thread_roots(phase, n_workers > 1) {
  nmethod::oops_do_marking_prologue();
}

ShenandoahRootScanner::~ShenandoahRootScanner() {
  nmethod::oops_do_marking_epilogue();
}

void ShenandoahRootScanner::roots_do(uint worker_id, OopClosure* oops) {
  MarkingCodeBlobClosure blobs_cl(oops, !CodeBlobToOopClosure::FixRelocations, true /*FIXME*/);
  roots_do(worker_id, oops, &blobs_cl);
}

void ShenandoahRootScanner::roots_do(uint worker_id, OopClosure* oops, CodeBlobClosure* code, ThreadClosure *tc) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  ShenandoahParallelOopsDoThreadClosure tc_cl(oops, code, tc);
  ResourceMark rm;
  _thread_roots.threads_do(&tc_cl, worker_id);
}

ShenandoahRootEvacuator::ShenandoahRootEvacuator(uint n_workers,
                                                 ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _thread_roots(phase, n_workers > 1) {
  nmethod::oops_do_marking_prologue();
}

ShenandoahRootEvacuator::~ShenandoahRootEvacuator() {
  nmethod::oops_do_marking_epilogue();
}

void ShenandoahRootEvacuator::roots_do(uint worker_id, OopClosure* oops) {
  // Always disarm on-stack nmethods, because we are evacuating/updating them
  // here
  ShenandoahCodeBlobAndDisarmClosure codeblob_cl(oops);
  _thread_roots.oops_do(oops, &codeblob_cl, worker_id);
}

ShenandoahRootUpdater::ShenandoahRootUpdater(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _vm_roots(phase),
  _cld_roots(phase, n_workers),
  _thread_roots(phase, n_workers > 1),
  _weak_roots(phase),
  _dedup_roots(phase),
  _code_roots(phase) {
}

ShenandoahRootAdjuster::ShenandoahRootAdjuster(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _vm_roots(phase),
  _cld_roots(phase, n_workers),
  _thread_roots(phase, n_workers > 1),
  _weak_roots(phase),
  _dedup_roots(phase),
  _code_roots(phase) {
  assert(ShenandoahHeap::heap()->is_full_gc_in_progress(), "Full GC only");
}

void ShenandoahRootAdjuster::roots_do(uint worker_id, OopClosure* oops) {
  CodeBlobToOopClosure code_blob_cl(oops, CodeBlobToOopClosure::FixRelocations);
  ShenandoahCodeBlobAndDisarmClosure blobs_and_disarm_Cl(oops);
  CodeBlobToOopClosure* adjust_code_closure = ShenandoahConcurrentRoots::can_do_concurrent_class_unloading() ?
                                              static_cast<CodeBlobToOopClosure*>(&blobs_and_disarm_Cl) :
                                              static_cast<CodeBlobToOopClosure*>(&code_blob_cl);
  CLDToOopClosure adjust_cld_closure(oops, ClassLoaderData::_claim_strong);
  AlwaysTrueClosure always_true;

  // Process light-weight/limited parallel roots then
  _vm_roots.oops_do(oops, worker_id);
  _weak_roots.oops_do<OopClosure>(oops, worker_id);
  _dedup_roots.oops_do(&always_true, oops, worker_id);
  _cld_roots.cld_do(&adjust_cld_closure, worker_id);

  // Process heavy-weight/fully parallel roots the last
  _code_roots.code_blobs_do(adjust_code_closure, worker_id);
  _thread_roots.oops_do(oops, NULL, worker_id);
}

ShenandoahHeapIterationRootScanner::ShenandoahHeapIterationRootScanner() :
   ShenandoahRootProcessor(ShenandoahPhaseTimings::heap_iteration_roots),
   _thread_roots(ShenandoahPhaseTimings::heap_iteration_roots, false /*is par*/),
   _vm_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _cld_roots(ShenandoahPhaseTimings::heap_iteration_roots, 1),
   _weak_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _dedup_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _code_roots(ShenandoahPhaseTimings::heap_iteration_roots) {
   _dedup_roots.prologue();
 }

ShenandoahHeapIterationRootScanner::~ShenandoahHeapIterationRootScanner() {
  _dedup_roots.epilogue();
}

 void ShenandoahHeapIterationRootScanner::roots_do(OopClosure* oops) {
   assert(Thread::current()->is_VM_thread(), "Only by VM thread");
   // Must use _claim_none to avoid interfering with concurrent CLDG iteration
   CLDToOopClosure clds(oops, ClassLoaderData::_claim_none);
   MarkingCodeBlobClosure code(oops, !CodeBlobToOopClosure::FixRelocations, true /*FIXME*/);
   ShenandoahParallelOopsDoThreadClosure tc_cl(oops, &code, NULL);
   AlwaysTrueClosure always_true;

   ResourceMark rm;

   // Process light-weight/limited parallel roots then
   _vm_roots.oops_do(oops, 0);
   _weak_roots.oops_do<OopClosure>(oops, 0);
   _dedup_roots.oops_do(&always_true, oops, 0);
   _cld_roots.cld_do(&clds, 0);

   // Process heavy-weight/fully parallel roots the last
   _code_roots.code_blobs_do(&code, 0);
   _thread_roots.threads_do(&tc_cl, 0);
 }

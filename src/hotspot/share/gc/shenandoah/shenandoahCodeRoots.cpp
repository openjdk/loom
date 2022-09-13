/*
 * Copyright (c) 2017, 2022, Red Hat, Inc. All rights reserved.
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
#include "code/codeCache.hpp"
#include "code/icBuffer.hpp"
#include "code/nmethod.hpp"
#include "gc/shenandoah/shenandoahClosures.inline.hpp"
#include "gc/shenandoah/shenandoahEvacOOMHandler.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahNMethod.inline.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "runtime/atomic.hpp"
#include "utilities/powerOfTwo.hpp"

ShenandoahParallelCodeCacheIterator::ShenandoahParallelCodeCacheIterator(const GrowableArray<CodeHeap*>* heaps) {
  _length = heaps->length();
  _iters = NEW_C_HEAP_ARRAY(ShenandoahParallelCodeHeapIterator, _length, mtGC);
  for (int h = 0; h < _length; h++) {
    _iters[h] = ShenandoahParallelCodeHeapIterator(heaps->at(h));
  }
}

ShenandoahParallelCodeCacheIterator::~ShenandoahParallelCodeCacheIterator() {
  FREE_C_HEAP_ARRAY(ParallelCodeHeapIterator, _iters);
}

void ShenandoahParallelCodeCacheIterator::parallel_blobs_do(CodeBlobClosure* f) {
  for (int c = 0; c < _length; c++) {
    _iters[c].parallel_blobs_do(f);
  }
}

ShenandoahParallelCodeHeapIterator::ShenandoahParallelCodeHeapIterator(CodeHeap* heap) :
        _heap(heap), _claimed_idx(0), _finished(false) {
}

void ShenandoahParallelCodeHeapIterator::parallel_blobs_do(CodeBlobClosure* f) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at safepoint");

  /*
   * Parallel code heap walk.
   *
   * This code makes all threads scan all code heaps, but only one thread would execute the
   * closure on given blob. This is achieved by recording the "claimed" blocks: if a thread
   * had claimed the block, it can process all blobs in it. Others have to fast-forward to
   * next attempt without processing.
   *
   * Late threads would return immediately if iterator is finished.
   */

  if (_finished) {
    return;
  }

  int stride = 256; // educated guess
  int stride_mask = stride - 1;
  assert (is_power_of_2(stride), "sanity");

  int count = 0;
  bool process_block = true;

  for (CodeBlob *cb = CodeCache::first_blob(_heap); cb != NULL; cb = CodeCache::next_blob(_heap, cb)) {
    int current = count++;
    if ((current & stride_mask) == 0) {
      process_block = (current >= _claimed_idx) &&
                      (Atomic::cmpxchg(&_claimed_idx, current, current + stride, memory_order_relaxed) == current);
    }
    if (process_block) {
      f->do_code_blob(cb);
#ifdef ASSERT
      if (cb->is_nmethod())
        Universe::heap()->verify_nmethod((nmethod*)cb);
#endif
    }
  }

  _finished = true;
}

ShenandoahNMethodTable* ShenandoahCodeRoots::_nmethod_table;
int ShenandoahCodeRoots::_disarmed_value = 1;

void ShenandoahCodeRoots::initialize() {
  _nmethod_table = new ShenandoahNMethodTable();
}

void ShenandoahCodeRoots::register_nmethod(nmethod* nm) {
  assert(CodeCache_lock->owned_by_self(), "Must have CodeCache_lock held");
  _nmethod_table->register_nmethod(nm);
}

void ShenandoahCodeRoots::unregister_nmethod(nmethod* nm) {
  assert_locked_or_safepoint(CodeCache_lock);
  _nmethod_table->unregister_nmethod(nm);
}

void ShenandoahCodeRoots::arm_nmethods() {
  assert(BarrierSet::barrier_set()->barrier_set_nmethod() != NULL, "Sanity");
  BarrierSet::barrier_set()->barrier_set_nmethod()->arm_all_nmethods();
}

class ShenandoahDisarmNMethodClosure : public NMethodClosure {
private:
  BarrierSetNMethod* const _bs;

public:
  ShenandoahDisarmNMethodClosure() :
    _bs(BarrierSet::barrier_set()->barrier_set_nmethod()) {
  }

  virtual void do_nmethod(nmethod* nm) {
    _bs->disarm(nm);
  }
};

class ShenandoahDisarmNMethodsTask : public WorkerTask {
private:
  ShenandoahDisarmNMethodClosure      _cl;
  ShenandoahConcurrentNMethodIterator _iterator;

public:
  ShenandoahDisarmNMethodsTask() :
    WorkerTask("Shenandoah Disarm NMethods"),
    _iterator(ShenandoahCodeRoots::table()) {
    assert(SafepointSynchronize::is_at_safepoint(), "Only at a safepoint");
    MutexLocker mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
    _iterator.nmethods_do_begin();
  }

  ~ShenandoahDisarmNMethodsTask() {
    MutexLocker mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
    _iterator.nmethods_do_end();
  }

  virtual void work(uint worker_id) {
    ShenandoahParallelWorkerSession worker_session(worker_id);
    _iterator.nmethods_do(&_cl);
  }
};

void ShenandoahCodeRoots::disarm_nmethods() {
  if (ShenandoahNMethodBarrier) {
    ShenandoahDisarmNMethodsTask task;
    ShenandoahHeap::heap()->workers()->run_task(&task);
  }
}

class ShenandoahNMethodUnlinkClosure : public NMethodClosure {
private:
  bool                      _unloading_occurred;
  volatile bool             _failed;
  ShenandoahHeap* const     _heap;
  BarrierSetNMethod* const  _bs;

  void set_failed() {
    Atomic::store(&_failed, true);
  }

public:
  ShenandoahNMethodUnlinkClosure(bool unloading_occurred) :
      _unloading_occurred(unloading_occurred),
      _failed(false),
      _heap(ShenandoahHeap::heap()),
      _bs(ShenandoahBarrierSet::barrier_set()->barrier_set_nmethod()) {}

  virtual void do_nmethod(nmethod* nm) {
    assert(_heap->is_concurrent_weak_root_in_progress(), "Only this phase");
    if (failed()) {
      return;
    }

    ShenandoahNMethod* nm_data = ShenandoahNMethod::gc_data(nm);
    assert(!nm_data->is_unregistered(), "Should not see unregistered entry");

    if (nm->is_unloading()) {
      ShenandoahReentrantLocker locker(nm_data->lock());
      nm->unlink();
      return;
    }

    ShenandoahReentrantLocker locker(nm_data->lock());

    // Heal oops and disarm
    if (_bs->is_armed(nm)) {
      ShenandoahEvacOOMScope oom_evac_scope;
      ShenandoahNMethod::heal_nmethod_metadata(nm_data);
      // Code cache unloading needs to know about on-stack nmethods. Arm the nmethods to get
      // mark_as_maybe_on_stack() callbacks when they are used again.
      _bs->arm(nm, 0);
    }

    // Clear compiled ICs and exception caches
    if (!nm->unload_nmethod_caches(_unloading_occurred)) {
      set_failed();
    }
  }

  bool failed() const {
    return Atomic::load(&_failed);
  }
};

class ShenandoahUnlinkTask : public WorkerTask {
private:
  ShenandoahNMethodUnlinkClosure      _cl;
  ICRefillVerifier*                   _verifier;
  ShenandoahConcurrentNMethodIterator _iterator;

public:
  ShenandoahUnlinkTask(bool unloading_occurred, ICRefillVerifier* verifier) :
    WorkerTask("Shenandoah Unlink NMethods"),
    _cl(unloading_occurred),
    _verifier(verifier),
    _iterator(ShenandoahCodeRoots::table()) {
    MutexLocker mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
    _iterator.nmethods_do_begin();
  }

  ~ShenandoahUnlinkTask() {
    MutexLocker mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
    _iterator.nmethods_do_end();
  }

  virtual void work(uint worker_id) {
    ICRefillVerifierMark mark(_verifier);
    _iterator.nmethods_do(&_cl);
  }

  bool success() const {
    return !_cl.failed();
  }
};

void ShenandoahCodeRoots::unlink(WorkerThreads* workers, bool unloading_occurred) {
  assert(ShenandoahHeap::heap()->unload_classes(), "Only when running concurrent class unloading");

  for (;;) {
    ICRefillVerifier verifier;

    {
      ShenandoahUnlinkTask task(unloading_occurred, &verifier);
      workers->run_task(&task);
      if (task.success()) {
        return;
      }
    }

    // Cleaning failed because we ran out of transitional IC stubs,
    // so we have to refill and try again. Refilling requires taking
    // a safepoint, so we temporarily leave the suspendible thread set.
    SuspendibleThreadSetLeaver sts;
    InlineCacheBuffer::refill_ic_stubs();
  }
}

void ShenandoahCodeRoots::purge() {
  assert(ShenandoahHeap::heap()->unload_classes(), "Only when running concurrent class unloading");

  CodeCache::flush_unlinked_nmethods();
}

ShenandoahCodeRootsIterator::ShenandoahCodeRootsIterator() :
        _par_iterator(CodeCache::heaps()),
        _table_snapshot(NULL) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at safepoint");
  MutexLocker locker(CodeCache_lock, Mutex::_no_safepoint_check_flag);
  _table_snapshot = ShenandoahCodeRoots::table()->snapshot_for_iteration();
}

ShenandoahCodeRootsIterator::~ShenandoahCodeRootsIterator() {
  MonitorLocker locker(CodeCache_lock, Mutex::_no_safepoint_check_flag);
  ShenandoahCodeRoots::table()->finish_iteration(_table_snapshot);
  _table_snapshot = NULL;
  locker.notify_all();
}

void ShenandoahCodeRootsIterator::possibly_parallel_blobs_do(CodeBlobClosure *f) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at safepoint");
  assert(_table_snapshot != NULL, "Sanity");
  _table_snapshot->parallel_blobs_do(f);
}

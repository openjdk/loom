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
 */

#include "precompiled.hpp"
#include "code/relocInfo.hpp"
#include "code/nmethod.hpp"
#include "code/icBuffer.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/barrierSetNMethod.hpp"
#include "gc/shared/suspendibleThreadSet.hpp"
#include "gc/z/zBarrier.inline.hpp"
#include "gc/z/zGlobals.hpp"
#include "gc/z/zLock.inline.hpp"
#include "gc/z/zNMethod.hpp"
#include "gc/z/zNMethodData.hpp"
#include "gc/z/zNMethodTable.hpp"
#include "gc/z/zTask.hpp"
#include "gc/z/zWorkers.hpp"
#include "logging/log.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/iterator.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/atomic.hpp"
#include "runtime/continuation.hpp"
#include "utilities/debug.hpp"

static ZNMethodData* gc_data(const nmethod* nm) {
  return nm->gc_data<ZNMethodData>();
}

static void set_gc_data(nmethod* nm, ZNMethodData* data) {
  return nm->set_gc_data<ZNMethodData>(data);
}

void ZNMethod::attach_gc_data(nmethod* nm) {
  GrowableArray<oop*> immediate_oops;
  bool non_immediate_oops = false;

  // Find all oop relocations
  RelocIterator iter(nm);
  while (iter.next()) {
    if (iter.type() != relocInfo::oop_type) {
      // Not an oop
      continue;
    }

    oop_Relocation* r = iter.oop_reloc();

    if (!r->oop_is_immediate()) {
      // Non-immediate oop found
      non_immediate_oops = true;
      continue;
    }

    if (r->oop_value() != NULL) {
      // Non-NULL immediate oop found. NULL oops can safely be
      // ignored since the method will be re-registered if they
      // are later patched to be non-NULL.
      immediate_oops.push(r->oop_addr());
    }
  }

  // Attach GC data to nmethod
  ZNMethodData* data = gc_data(nm);
  if (data == NULL) {
    data = new ZNMethodData();
    set_gc_data(nm, data);
  }

  // Attach oops in GC data
  ZNMethodDataOops* const new_oops = ZNMethodDataOops::create(immediate_oops, non_immediate_oops);
  ZNMethodDataOops* const old_oops = data->swap_oops(new_oops);
  ZNMethodDataOops::destroy(old_oops);
}

ZReentrantLock* ZNMethod::lock_for_nmethod(nmethod* nm) {
  return gc_data(nm)->lock();
}

void ZNMethod::log_register(const nmethod* nm) {
  LogTarget(Trace, gc, nmethod) log;
  if (!log.is_enabled()) {
    return;
  }

  const ZNMethodDataOops* const oops = gc_data(nm)->oops();

  log.print("Register NMethod: %s.%s (" PTR_FORMAT "), "
            "Compiler: %s, Oops: %d, ImmediateOops: " SIZE_FORMAT ", NonImmediateOops: %s",
            nm->method()->method_holder()->external_name(),
            nm->method()->name()->as_C_string(),
            p2i(nm),
            nm->compiler_name(),
            nm->oops_count() - 1,
            oops->immediates_count(),
            oops->has_non_immediates() ? "Yes" : "No");

  LogTarget(Trace, gc, nmethod, oops) log_oops;
  if (!log_oops.is_enabled()) {
    return;
  }

  // Print nmethod oops table
  {
    oop* const begin = nm->oops_begin();
    oop* const end = nm->oops_end();
    for (oop* p = begin; p < end; p++) {
      const oop o = Atomic::load(p); // C1 PatchingStub may replace it concurrently.
      const char* external_name = (o == nullptr) ? "N/A" : o->klass()->external_name();
      log_oops.print("           Oop[" SIZE_FORMAT "] " PTR_FORMAT " (%s)",
                     (p - begin), p2i(o), external_name);
    }
  }

  // Print nmethod immediate oops
  {
    oop** const begin = oops->immediates_begin();
    oop** const end = oops->immediates_end();
    for (oop** p = begin; p < end; p++) {
      log_oops.print("  ImmediateOop[" SIZE_FORMAT "] " PTR_FORMAT " @ " PTR_FORMAT " (%s)",
                     (p - begin), p2i(**p), p2i(*p), (**p)->klass()->external_name());
    }
  }
}

void ZNMethod::log_unregister(const nmethod* nm) {
  LogTarget(Debug, gc, nmethod) log;
  if (!log.is_enabled()) {
    return;
  }

  log.print("Unregister NMethod: %s.%s (" PTR_FORMAT ")",
            nm->method()->method_holder()->external_name(),
            nm->method()->name()->as_C_string(),
            p2i(nm));
}

void ZNMethod::register_nmethod(nmethod* nm) {
  ResourceMark rm;

  // Create and attach gc data
  attach_gc_data(nm);

  log_register(nm);

  ZNMethodTable::register_nmethod(nm);

  // Disarm nmethod entry barrier
  disarm(nm);
}

void ZNMethod::unregister_nmethod(nmethod* nm) {
  ResourceMark rm;

  log_unregister(nm);

  ZNMethodTable::unregister_nmethod(nm);

  // Destroy GC data
  delete gc_data(nm);
}

bool ZNMethod::supports_entry_barrier(nmethod* nm) {
  BarrierSetNMethod* const bs = BarrierSet::barrier_set()->barrier_set_nmethod();
  return bs->supports_entry_barrier(nm);
}

bool ZNMethod::is_armed(nmethod* nm) {
  BarrierSetNMethod* const bs = BarrierSet::barrier_set()->barrier_set_nmethod();
  return bs->is_armed(nm);
}

void ZNMethod::disarm(nmethod* nm) {
  BarrierSetNMethod* const bs = BarrierSet::barrier_set()->barrier_set_nmethod();
  bs->disarm(nm);
}

void ZNMethod::arm(nmethod* nm, int arm_value) {
  BarrierSetNMethod* const bs = BarrierSet::barrier_set()->barrier_set_nmethod();
  if (bs != NULL) {
    bs->arm(nm, arm_value);
  }
}

void ZNMethod::nmethod_oops_do(nmethod* nm, OopClosure* cl) {
  ZLocker<ZReentrantLock> locker(ZNMethod::lock_for_nmethod(nm));
  ZNMethod::nmethod_oops_do_inner(nm, cl);
}

void ZNMethod::nmethod_oops_do_inner(nmethod* nm, OopClosure* cl) {
  // Process oops table
  {
    oop* const begin = nm->oops_begin();
    oop* const end = nm->oops_end();
    for (oop* p = begin; p < end; p++) {
      if (!Universe::contains_non_oop_word(p)) {
        cl->do_oop(p);
      }
    }
  }

  ZNMethodDataOops* const oops = gc_data(nm)->oops();

  // Process immediate oops
  {
    oop** const begin = oops->immediates_begin();
    oop** const end = oops->immediates_end();
    for (oop** p = begin; p < end; p++) {
      if (*p != Universe::non_oop_word()) {
        cl->do_oop(*p);
      }
    }
  }

  // Process non-immediate oops
  if (oops->has_non_immediates()) {
    nm->fix_oop_relocations();
  }
}

class ZNMethodOopClosure : public OopClosure {
public:
  virtual void do_oop(oop* p) {
    if (ZResurrection::is_blocked()) {
      ZBarrier::keep_alive_barrier_on_phantom_root_oop_field(p);
    } else {
      ZBarrier::load_barrier_on_root_oop_field(p);
    }
  }

  virtual void do_oop(narrowOop* p) {
    ShouldNotReachHere();
  }
};

void ZNMethod::nmethod_oops_barrier(nmethod* nm) {
  ZNMethodOopClosure cl;
  nmethod_oops_do_inner(nm, &cl);
}

void ZNMethod::nmethods_do_begin() {
  ZNMethodTable::nmethods_do_begin();
}

void ZNMethod::nmethods_do_end() {
  ZNMethodTable::nmethods_do_end();
}

void ZNMethod::nmethods_do(NMethodClosure* cl) {
  ZNMethodTable::nmethods_do(cl);
}

class ZNMethodUnlinkClosure : public NMethodClosure {
private:
  bool          _unloading_occurred;
  volatile bool _failed;

  void set_failed() {
    Atomic::store(&_failed, true);
  }

public:
  ZNMethodUnlinkClosure(bool unloading_occurred) :
      _unloading_occurred(unloading_occurred),
      _failed(false) {}

  virtual void do_nmethod(nmethod* nm) {
    if (failed()) {
      return;
    }

    if (nm->is_unloading()) {
      ZLocker<ZReentrantLock> locker(ZNMethod::lock_for_nmethod(nm));
      nm->unlink();
      return;
    }

    ZLocker<ZReentrantLock> locker(ZNMethod::lock_for_nmethod(nm));

    if (ZNMethod::is_armed(nm)) {
      // Heal oops and disarm
      ZNMethod::nmethod_oops_barrier(nm);
      ZNMethod::arm(nm, 0);
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

class ZNMethodUnlinkTask : public ZTask {
private:
  ZNMethodUnlinkClosure _cl;
  ICRefillVerifier*     _verifier;

public:
  ZNMethodUnlinkTask(bool unloading_occurred, ICRefillVerifier* verifier) :
      ZTask("ZNMethodUnlinkTask"),
      _cl(unloading_occurred),
      _verifier(verifier) {
    ZNMethodTable::nmethods_do_begin();
  }

  ~ZNMethodUnlinkTask() {
    ZNMethodTable::nmethods_do_end();
  }

  virtual void work() {
    ICRefillVerifierMark mark(_verifier);
    ZNMethodTable::nmethods_do(&_cl);
  }

  bool success() const {
    return !_cl.failed();
  }
};

void ZNMethod::unlink(ZWorkers* workers, bool unloading_occurred) {
  for (;;) {
    ICRefillVerifier verifier;

    {
      ZNMethodUnlinkTask task(unloading_occurred, &verifier);
      workers->run(&task);
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

void ZNMethod::purge() {
  CodeCache::flush_unlinked_nmethods();
}

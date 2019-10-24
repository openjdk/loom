/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
#include "code/nmethod.hpp"
#include "gc/g1/g1BarrierSetNMethod.hpp"
#include "gc/g1/g1ThreadLocalData.hpp"
#include "oops/access.inline.hpp"

class G1LoadPhantomOopClosure : public OopClosure {
public:
  virtual void do_oop(oop* p) {
    NativeAccess<ON_PHANTOM_OOP_REF>::oop_load(p);
  }
  virtual void do_oop(narrowOop* p) { ShouldNotReachHere(); }
};

bool G1BarrierSetNMethod::nmethod_entry_barrier(nmethod* nm) {
  G1LoadPhantomOopClosure cl;
  nm->oops_do(&cl);
  disarm(nm);

  return true;
}

int G1BarrierSetNMethod::disarmed_value() const {
  return _current_phase;
}

ByteSize G1BarrierSetNMethod::thread_disarmed_offset() const {
  return G1ThreadLocalData::nmethod_disarmed_offset();
}

class G1BarrierSetNMethodArmClosure : public ThreadClosure {
private:
  int _disarm_value;

public:
  G1BarrierSetNMethodArmClosure(int disarm_value) :
      _disarm_value(disarm_value) { }

  virtual void do_thread(Thread* thread) {
    G1ThreadLocalData::set_nmethod_disarm_value(thread, _disarm_value);
  }
};

void G1BarrierSetNMethod::arm_all_nmethods() {
  ++_current_phase;
  if (_current_phase == 3) {
    _current_phase = 1;
  }
  G1BarrierSetNMethodArmClosure cl(_current_phase);
  Threads::threads_do(&cl);
}

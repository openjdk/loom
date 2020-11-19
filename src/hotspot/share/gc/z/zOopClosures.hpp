/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_Z_ZOOPCLOSURES_HPP
#define SHARE_GC_Z_ZOOPCLOSURES_HPP

#include "memory/iterator.hpp"
#include "gc/z/zRootsIterator.hpp"

class ZLoadBarrierOopClosure : public BasicOopIterateClosure {
public:
  virtual void do_oop(oop* p);
  virtual void do_oop(narrowOop* p);
};

class ZNMethodOopClosure : public OopClosure {
public:
  virtual void do_oop(oop* p);
  virtual void do_oop(narrowOop* p);
};

template <bool finalizable>
class ZMarkBarrierOopClosure : public ClaimMetadataVisitingOopIterateClosure {
public:
  ZMarkBarrierOopClosure();

  virtual void do_oop(oop* p);
  virtual void do_oop(narrowOop* p);
};

class ZPhantomIsAliveObjectClosure : public BoolObjectClosure {
public:
  virtual bool do_object_b(oop o);
};

class ZPhantomKeepAliveOopClosure : public OopClosure {
public:
  virtual void do_oop(oop* p);
  virtual void do_oop(narrowOop* p);
};
class ZPhantomCleanOopClosure : public OopClosure {
public:
  virtual void do_oop(oop* p);
  virtual void do_oop(narrowOop* p);
};

#endif // SHARE_GC_Z_ZOOPCLOSURES_HPP

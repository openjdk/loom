/*
 * Copyright (c) 2005, 2024, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_SHARED_GCVMOPERATIONS_HPP
#define SHARE_GC_SHARED_GCVMOPERATIONS_HPP

#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/collectorCounters.hpp"
#include "memory/metaspace.hpp"
#include "prims/jvmtiExport.hpp"
#include "runtime/handles.hpp"
#include "runtime/synchronizer.hpp"
#include "runtime/vmOperation.hpp"

// The following class hierarchy represents
// a set of operations (VM_Operation) related to GC.
//
//  VM_Operation
//    VM_Heap_Sync_Operation
//      VM_GC_Operation
//        VM_GC_Collect_Operation
//          VM_SerialGCCollect
//          VM_ParallelGCCollect
//          VM_CollectForAllocation
//            VM_SerialCollectForAllocation
//            VM_ParallelCollectForAllocation
//          VM_CollectForMetadataAllocation
//        VM_GC_Service_Operation
//          VM_GC_HeapInspection
//      VM_Verify
//      VM_PopulateDynamicDumpSharedSpace
//      VM_PopulateDumpSharedSpace
//
//  VM_Heap_Sync_Operation
//   - implements only synchronization with other VM operations of the
//     same kind using the Heap_lock, not actually doing a GC.
//
//  VM_GC_Operation
//   - implements methods common to all operations that perform garbage collections,
//     checking that the VM is in a state to do GC and preventing multiple GC
//     requests.
//
//  VM_GC_HeapInspection
//   - prints class histogram on SIGBREAK if PrintClassHistogram
//     is specified; and also the attach "inspectheap" operation
//
//  VM_CollectForAllocation
//  VM_SerialCollectForAllocation
//  VM_ParallelCollectForAllocation
//   - this operation is invoked when allocation is failed;
//     operation performs garbage collection and tries to
//     allocate afterwards;
//
//  VM_SerialGCCollect
//  VM_ParallelGCCollect
//   - these operations perform full collection of heaps of
//     different kind
//
//  VM_Verify
//   - verifies the heap
//
//  VM_PopulateDynamicDumpSharedSpace
//   - populates the CDS archive area with the information from the archive file.
//
//  VM_PopulateDumpSharedSpace
//   - creates the CDS archive
//

class VM_Heap_Sync_Operation : public VM_Operation {
public:

  VM_Heap_Sync_Operation() : VM_Operation() { }

  // Acquires the Heap_lock.
  virtual bool doit_prologue();
  // Releases the Heap_lock.
  virtual void doit_epilogue();
};

class VM_Verify : public VM_Heap_Sync_Operation {
 public:
  VMOp_Type type() const { return VMOp_Verify; }
  void doit();
};

class VM_GC_Operation: public VM_Heap_Sync_Operation {
 protected:
  uint           _gc_count_before;         // gc count before acquiring the Heap_lock
  uint           _full_gc_count_before;    // full gc count before acquiring the Heap_lock
  bool           _full;                    // whether a "full" collection
  bool           _prologue_succeeded;      // whether doit_prologue succeeded
  GCCause::Cause _gc_cause;                // the putative cause for this gc op

  virtual bool skip_operation() const;

 public:
  VM_GC_Operation(uint gc_count_before,
                  GCCause::Cause _cause,
                  uint full_gc_count_before = 0,
                  bool full = false) : VM_Heap_Sync_Operation() {
    _full = full;
    _prologue_succeeded = false;
    _gc_count_before    = gc_count_before;

    _gc_cause           = _cause;

    _full_gc_count_before = full_gc_count_before;
    // In ParallelScavengeHeap::mem_allocate() collections can be
    // executed within a loop and _all_soft_refs_clear can be set
    // true after they have been cleared by a collection and another
    // collection started so that _all_soft_refs_clear can be true
    // when this collection is started.  Don't assert that
    // _all_soft_refs_clear have to be false here even though
    // mutators have run.  Soft refs will be cleared again in this
    // collection.
  }
  ~VM_GC_Operation();

  virtual const char* cause() const;

  // Acquire the Heap_lock and determine if this VM operation should be executed
  // (i.e. not skipped). Return this result, and also store it in _prologue_succeeded.
  virtual bool doit_prologue();
  // Notify the Heap_lock if needed and release it.
  virtual void doit_epilogue();

  virtual bool allow_nested_vm_operations() const  { return true; }
  virtual bool gc_succeeded() const { return _prologue_succeeded; }

  static void notify_gc_begin(bool full = false);
  static void notify_gc_end();
};

class VM_GC_Service_Operation : public VM_GC_Operation {
public:
  VM_GC_Service_Operation(uint gc_count_before,
                          GCCause::Cause _cause,
                          uint full_gc_count_before = 0,
                          bool full = false) :
    VM_GC_Operation(gc_count_before, _cause, full_gc_count_before, full) {}
};

class VM_GC_Collect_Operation : public VM_GC_Operation {
public:
  VM_GC_Collect_Operation(uint gc_count_before,
                          GCCause::Cause _cause,
                          uint full_gc_count_before = 0,
                          bool full = false) :
    VM_GC_Operation(gc_count_before, _cause, full_gc_count_before, full) {}

  bool is_gc_operation() const { return true; }
};


class VM_GC_HeapInspection : public VM_GC_Service_Operation {
 private:
  outputStream* _out;
  bool _full_gc;
  uint _parallel_thread_num;

 public:
  VM_GC_HeapInspection(outputStream* out, bool request_full_gc,
                       uint parallel_thread_num = 1) :
    VM_GC_Service_Operation(0 /* total collections,      dummy, ignored */,
                            GCCause::_heap_inspection /* GC Cause */,
                            0 /* total full collections, dummy, ignored */,
                            request_full_gc), _out(out), _full_gc(request_full_gc),
                            _parallel_thread_num(parallel_thread_num) {}

  ~VM_GC_HeapInspection() {}
  virtual VMOp_Type type() const { return VMOp_GC_HeapInspection; }
  virtual bool skip_operation() const;
  virtual bool doit_prologue();
  virtual void doit();

 protected:
  bool collect();
};

class VM_CollectForAllocation : public VM_GC_Collect_Operation {
 protected:
  size_t    _word_size; // Size of object to be allocated (in number of words)
  HeapWord* _result;    // Allocation result (null if allocation failed)

 public:
  VM_CollectForAllocation(size_t word_size, uint gc_count_before, GCCause::Cause cause);

  HeapWord* result() const {
    return _result;
  }
};

class VM_CollectForMetadataAllocation: public VM_GC_Collect_Operation {
 private:
  MetaWord*                _result;
  size_t                   _size;     // size of object to be allocated
  Metaspace::MetadataType  _mdtype;
  ClassLoaderData*         _loader_data;

 public:
  VM_CollectForMetadataAllocation(ClassLoaderData* loader_data,
                                  size_t size,
                                  Metaspace::MetadataType mdtype,
                                  uint gc_count_before,
                                  uint full_gc_count_before,
                                  GCCause::Cause gc_cause);

  virtual VMOp_Type type() const { return VMOp_CollectForMetadataAllocation; }
  virtual void doit();
  MetaWord* result() const       { return _result; }
};

class SvcGCMarker : public StackObj {
 private:
  JvmtiGCMarker _jgcm;
 public:
  typedef enum { MINOR, FULL, CONCURRENT } reason_type;

  SvcGCMarker(reason_type reason ) {
    VM_GC_Operation::notify_gc_begin(reason == FULL);
  }

  ~SvcGCMarker() {
    VM_GC_Operation::notify_gc_end();
  }
};

#endif // SHARE_GC_SHARED_GCVMOPERATIONS_HPP

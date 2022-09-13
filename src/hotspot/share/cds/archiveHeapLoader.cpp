/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
#include "cds/archiveHeapLoader.inline.hpp"
#include "cds/filemap.hpp"
#include "cds/heapShared.hpp"
#include "cds/metaspaceShared.hpp"
#include "classfile/classLoaderDataShared.hpp"
#include "classfile/systemDictionaryShared.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "logging/log.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "utilities/bitMap.inline.hpp"
#include "utilities/copy.hpp"

#if INCLUDE_CDS_JAVA_HEAP

bool ArchiveHeapLoader::_closed_regions_mapped = false;
bool ArchiveHeapLoader::_open_regions_mapped = false;
bool ArchiveHeapLoader::_is_loaded = false;
address ArchiveHeapLoader::_narrow_oop_base;
int     ArchiveHeapLoader::_narrow_oop_shift;

// Support for loaded heap.
uintptr_t ArchiveHeapLoader::_loaded_heap_bottom = 0;
uintptr_t ArchiveHeapLoader::_loaded_heap_top = 0;
uintptr_t ArchiveHeapLoader::_dumptime_base_0 = UINTPTR_MAX;
uintptr_t ArchiveHeapLoader::_dumptime_base_1 = UINTPTR_MAX;
uintptr_t ArchiveHeapLoader::_dumptime_base_2 = UINTPTR_MAX;
uintptr_t ArchiveHeapLoader::_dumptime_base_3 = UINTPTR_MAX;
uintptr_t ArchiveHeapLoader::_dumptime_top    = 0;
intx ArchiveHeapLoader::_runtime_offset_0 = 0;
intx ArchiveHeapLoader::_runtime_offset_1 = 0;
intx ArchiveHeapLoader::_runtime_offset_2 = 0;
intx ArchiveHeapLoader::_runtime_offset_3 = 0;
bool ArchiveHeapLoader::_loading_failed = false;

// Support for mapped heap (!UseCompressedOops only)
ptrdiff_t ArchiveHeapLoader::_runtime_delta = 0;

void ArchiveHeapLoader::init_narrow_oop_decoding(address base, int shift) {
  _narrow_oop_base = base;
  _narrow_oop_shift = shift;
}

void ArchiveHeapLoader::fixup_regions() {
  FileMapInfo* mapinfo = FileMapInfo::current_info();
  if (is_mapped()) {
    mapinfo->fixup_mapped_heap_regions();
  } else if (_loading_failed) {
    fill_failed_loaded_region();
  }
  if (is_fully_available()) {
    if (!MetaspaceShared::use_full_module_graph()) {
      // Need to remove all the archived java.lang.Module objects from HeapShared::roots().
      ClassLoaderDataShared::clear_archived_oops();
    }
  }
  SystemDictionaryShared::update_archived_mirror_native_pointers();
}

// ------------------ Support for Region MAPPING -----------------------------------------

// Patch all the embedded oop pointers inside an archived heap region,
// to be consistent with the runtime oop encoding.
class PatchCompressedEmbeddedPointers: public BitMapClosure {
  narrowOop* _start;

 public:
  PatchCompressedEmbeddedPointers(narrowOop* start) : _start(start) {}

  bool do_bit(size_t offset) {
    narrowOop* p = _start + offset;
    narrowOop v = *p;
    assert(!CompressedOops::is_null(v), "null oops should have been filtered out at dump time");
    oop o = ArchiveHeapLoader::decode_from_archive(v);
    RawAccess<IS_NOT_NULL>::oop_store(p, o);
    return true;
  }
};

class PatchUncompressedEmbeddedPointers: public BitMapClosure {
  oop* _start;

 public:
  PatchUncompressedEmbeddedPointers(oop* start) : _start(start) {}

  bool do_bit(size_t offset) {
    oop* p = _start + offset;
    intptr_t dumptime_oop = (intptr_t)((void*)*p);
    assert(dumptime_oop != 0, "null oops should have been filtered out at dump time");
    intptr_t runtime_oop = dumptime_oop + ArchiveHeapLoader::runtime_delta();
    RawAccess<IS_NOT_NULL>::oop_store(p, cast_to_oop(runtime_oop));
    return true;
  }
};

// Patch all the non-null pointers that are embedded in the archived heap objects
// in this (mapped) region
void ArchiveHeapLoader::patch_embedded_pointers(MemRegion region, address oopmap,
                                                size_t oopmap_size_in_bits) {
  BitMapView bm((BitMap::bm_word_t*)oopmap, oopmap_size_in_bits);

#ifndef PRODUCT
  ResourceMark rm;
  ResourceBitMap checkBm = HeapShared::calculate_oopmap(region);
  assert(bm.is_same(checkBm), "sanity");
#endif

  if (UseCompressedOops) {
    PatchCompressedEmbeddedPointers patcher((narrowOop*)region.start());
    bm.iterate(&patcher);
  } else {
    PatchUncompressedEmbeddedPointers patcher((oop*)region.start());
    bm.iterate(&patcher);
  }
}

// ------------------ Support for Region LOADING -----------------------------------------

// The CDS archive remembers each heap object by its address at dump time, but
// the heap object may be loaded at a different address at run time. This structure is used
// to translate the dump time addresses for all objects in FileMapInfo::space_at(region_index)
// to their runtime addresses.
struct LoadedArchiveHeapRegion {
  int       _region_index;   // index for FileMapInfo::space_at(index)
  size_t    _region_size;    // number of bytes in this region
  uintptr_t _dumptime_base;  // The dump-time (decoded) address of the first object in this region
  intx      _runtime_offset; // If an object's dump time address P is within in this region, its
                             // runtime address is P + _runtime_offset

  static int comparator(const void* a, const void* b) {
    LoadedArchiveHeapRegion* reg_a = (LoadedArchiveHeapRegion*)a;
    LoadedArchiveHeapRegion* reg_b = (LoadedArchiveHeapRegion*)b;
    if (reg_a->_dumptime_base < reg_b->_dumptime_base) {
      return -1;
    } else if (reg_a->_dumptime_base == reg_b->_dumptime_base) {
      return 0;
    } else {
      return 1;
    }
  }

  uintptr_t top() {
    return _dumptime_base + _region_size;
  }
};

void ArchiveHeapLoader::init_loaded_heap_relocation(LoadedArchiveHeapRegion* loaded_regions,
                                             int num_loaded_regions) {
  _dumptime_base_0 = loaded_regions[0]._dumptime_base;
  _dumptime_base_1 = loaded_regions[1]._dumptime_base;
  _dumptime_base_2 = loaded_regions[2]._dumptime_base;
  _dumptime_base_3 = loaded_regions[3]._dumptime_base;
  _dumptime_top = loaded_regions[num_loaded_regions-1].top();

  _runtime_offset_0 = loaded_regions[0]._runtime_offset;
  _runtime_offset_1 = loaded_regions[1]._runtime_offset;
  _runtime_offset_2 = loaded_regions[2]._runtime_offset;
  _runtime_offset_3 = loaded_regions[3]._runtime_offset;

  assert(2 <= num_loaded_regions && num_loaded_regions <= 4, "must be");
  if (num_loaded_regions < 4) {
    _dumptime_base_3 = UINTPTR_MAX;
  }
  if (num_loaded_regions < 3) {
    _dumptime_base_2 = UINTPTR_MAX;
  }
}

bool ArchiveHeapLoader::can_load() {
  return Universe::heap()->can_load_archived_objects();
}

template <int NUM_LOADED_REGIONS>
class PatchLoadedRegionPointers: public BitMapClosure {
  narrowOop* _start;
  intx _offset_0;
  intx _offset_1;
  intx _offset_2;
  intx _offset_3;
  uintptr_t _base_0;
  uintptr_t _base_1;
  uintptr_t _base_2;
  uintptr_t _base_3;
  uintptr_t _top;

  static_assert(MetaspaceShared::max_num_heap_regions == 4, "can't handle more than 4 regions");
  static_assert(NUM_LOADED_REGIONS >= 2, "we have at least 2 loaded regions");
  static_assert(NUM_LOADED_REGIONS <= 4, "we have at most 4 loaded regions");

 public:
  PatchLoadedRegionPointers(narrowOop* start, LoadedArchiveHeapRegion* loaded_regions)
    : _start(start),
      _offset_0(loaded_regions[0]._runtime_offset),
      _offset_1(loaded_regions[1]._runtime_offset),
      _offset_2(loaded_regions[2]._runtime_offset),
      _offset_3(loaded_regions[3]._runtime_offset),
      _base_0(loaded_regions[0]._dumptime_base),
      _base_1(loaded_regions[1]._dumptime_base),
      _base_2(loaded_regions[2]._dumptime_base),
      _base_3(loaded_regions[3]._dumptime_base) {
    _top = loaded_regions[NUM_LOADED_REGIONS-1].top();
  }

  bool do_bit(size_t offset) {
    narrowOop* p = _start + offset;
    narrowOop v = *p;
    assert(!CompressedOops::is_null(v), "null oops should have been filtered out at dump time");
    uintptr_t o = cast_from_oop<uintptr_t>(ArchiveHeapLoader::decode_from_archive(v));
    assert(_base_0 <= o && o < _top, "must be");


    // We usually have only 2 regions for the default archive. Use template to avoid unnecessary comparisons.
    if (NUM_LOADED_REGIONS > 3 && o >= _base_3) {
      o += _offset_3;
    } else if (NUM_LOADED_REGIONS > 2 && o >= _base_2) {
      o += _offset_2;
    } else if (o >= _base_1) {
      o += _offset_1;
    } else {
      o += _offset_0;
    }
    ArchiveHeapLoader::assert_in_loaded_heap(o);
    RawAccess<IS_NOT_NULL>::oop_store(p, cast_to_oop(o));
    return true;
  }
};

int ArchiveHeapLoader::init_loaded_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
                                           MemRegion& archive_space) {
  size_t total_bytes = 0;
  int num_loaded_regions = 0;
  for (int i = MetaspaceShared::first_archive_heap_region;
       i <= MetaspaceShared::last_archive_heap_region; i++) {
    FileMapRegion* r = mapinfo->space_at(i);
    r->assert_is_heap_region();
    if (r->used() > 0) {
      assert(is_aligned(r->used(), HeapWordSize), "must be");
      total_bytes += r->used();
      LoadedArchiveHeapRegion* ri = &loaded_regions[num_loaded_regions++];
      ri->_region_index = i;
      ri->_region_size = r->used();
      ri->_dumptime_base = (uintptr_t)mapinfo->start_address_as_decoded_from_archive(r);
    }
  }

  assert(is_aligned(total_bytes, HeapWordSize), "must be");
  size_t word_size = total_bytes / HeapWordSize;
  HeapWord* buffer = Universe::heap()->allocate_loaded_archive_space(word_size);
  if (buffer == nullptr) {
    return 0;
  }

  archive_space = MemRegion(buffer, word_size);
  _loaded_heap_bottom = (uintptr_t)archive_space.start();
  _loaded_heap_top    = _loaded_heap_bottom + total_bytes;

  return num_loaded_regions;
}

void ArchiveHeapLoader::sort_loaded_regions(LoadedArchiveHeapRegion* loaded_regions, int num_loaded_regions,
                                            uintptr_t buffer) {
  // Find the relocation offset of the pointers in each region
  qsort(loaded_regions, num_loaded_regions, sizeof(LoadedArchiveHeapRegion),
        LoadedArchiveHeapRegion::comparator);

  uintptr_t p = buffer;
  for (int i = 0; i < num_loaded_regions; i++) {
    // This region will be loaded at p, so all objects inside this
    // region will be shifted by ri->offset
    LoadedArchiveHeapRegion* ri = &loaded_regions[i];
    ri->_runtime_offset = p - ri->_dumptime_base;
    p += ri->_region_size;
  }
  assert(p == _loaded_heap_top, "must be");
}

bool ArchiveHeapLoader::load_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
                                     int num_loaded_regions, uintptr_t buffer) {
  uintptr_t bitmap_base = (uintptr_t)mapinfo->map_bitmap_region();
  if (bitmap_base == 0) {
    _loading_failed = true;
    return false; // OOM or CRC error
  }
  uintptr_t load_address = buffer;
  for (int i = 0; i < num_loaded_regions; i++) {
    LoadedArchiveHeapRegion* ri = &loaded_regions[i];
    FileMapRegion* r = mapinfo->space_at(ri->_region_index);

    if (!mapinfo->read_region(ri->_region_index, (char*)load_address, r->used(), /* do_commit = */ false)) {
      // There's no easy way to free the buffer, so we will fill it with zero later
      // in fill_failed_loaded_region(), and it will eventually be GC'ed.
      log_warning(cds)("Loading of heap region %d has failed. Archived objects are disabled", i);
      _loading_failed = true;
      return false;
    }
    log_info(cds)("Loaded heap    region #%d at base " INTPTR_FORMAT " top " INTPTR_FORMAT
                  " size " SIZE_FORMAT_W(6) " delta " INTX_FORMAT,
                  ri->_region_index, load_address, load_address + ri->_region_size,
                  ri->_region_size, ri->_runtime_offset);

    uintptr_t oopmap = bitmap_base + r->oopmap_offset();
    BitMapView bm((BitMap::bm_word_t*)oopmap, r->oopmap_size_in_bits());

    if (num_loaded_regions == 4) {
      PatchLoadedRegionPointers<4> patcher((narrowOop*)load_address, loaded_regions);
      bm.iterate(&patcher);
    } else if (num_loaded_regions == 3) {
      PatchLoadedRegionPointers<3> patcher((narrowOop*)load_address, loaded_regions);
      bm.iterate(&patcher);
    } else {
      assert(num_loaded_regions == 2, "must be");
      PatchLoadedRegionPointers<2> patcher((narrowOop*)load_address, loaded_regions);
      bm.iterate(&patcher);
    }

    load_address += r->used();
  }

  return true;
}

bool ArchiveHeapLoader::load_heap_regions(FileMapInfo* mapinfo) {
  init_narrow_oop_decoding(mapinfo->narrow_oop_base(), mapinfo->narrow_oop_shift());

  LoadedArchiveHeapRegion loaded_regions[MetaspaceShared::max_num_heap_regions];
  memset(loaded_regions, 0, sizeof(loaded_regions));

  MemRegion archive_space;
  int num_loaded_regions = init_loaded_regions(mapinfo, loaded_regions, archive_space);
  if (num_loaded_regions <= 0) {
    return false;
  }
  sort_loaded_regions(loaded_regions, num_loaded_regions, (uintptr_t)archive_space.start());
  if (!load_regions(mapinfo, loaded_regions, num_loaded_regions, (uintptr_t)archive_space.start())) {
    assert(_loading_failed, "must be");
    return false;
  }

  init_loaded_heap_relocation(loaded_regions, num_loaded_regions);
  _is_loaded = true;

  return true;
}

class VerifyLoadedHeapEmbeddedPointers: public BasicOopIterateClosure {
  ResourceHashtable<uintptr_t, bool>* _table;

 public:
  VerifyLoadedHeapEmbeddedPointers(ResourceHashtable<uintptr_t, bool>* table) : _table(table) {}

  virtual void do_oop(narrowOop* p) {
    // This should be called before the loaded regions are modified, so all the embedded pointers
    // must be NULL, or must point to a valid object in the loaded regions.
    narrowOop v = *p;
    if (!CompressedOops::is_null(v)) {
      oop o = CompressedOops::decode_not_null(v);
      uintptr_t u = cast_from_oop<uintptr_t>(o);
      ArchiveHeapLoader::assert_in_loaded_heap(u);
      guarantee(_table->contains(u), "must point to beginning of object in loaded archived regions");
    }
  }
  virtual void do_oop(oop* p) {
    ShouldNotReachHere();
  }
};

void ArchiveHeapLoader::finish_initialization() {
  if (is_loaded()) {
    HeapWord* bottom = (HeapWord*)_loaded_heap_bottom;
    HeapWord* top    = (HeapWord*)_loaded_heap_top;

    MemRegion archive_space = MemRegion(bottom, top);
    Universe::heap()->complete_loaded_archive_space(archive_space);
  }

  if (VerifyArchivedFields <= 0 || !is_loaded()) {
    return;
  }

  log_info(cds, heap)("Verify all oops and pointers in loaded heap");

  ResourceMark rm;
  ResourceHashtable<uintptr_t, bool> table;
  VerifyLoadedHeapEmbeddedPointers verifier(&table);
  HeapWord* bottom = (HeapWord*)_loaded_heap_bottom;
  HeapWord* top    = (HeapWord*)_loaded_heap_top;

  for (HeapWord* p = bottom; p < top; ) {
    oop o = cast_to_oop(p);
    table.put(cast_from_oop<uintptr_t>(o), true);
    p += o->size();
  }

  for (HeapWord* p = bottom; p < top; ) {
    oop o = cast_to_oop(p);
    o->oop_iterate(&verifier);
    p += o->size();
  }
}

void ArchiveHeapLoader::fill_failed_loaded_region() {
  assert(_loading_failed, "must be");
  if (_loaded_heap_bottom != 0) {
    assert(_loaded_heap_top != 0, "must be");
    HeapWord* bottom = (HeapWord*)_loaded_heap_bottom;
    HeapWord* top = (HeapWord*)_loaded_heap_top;
    Universe::heap()->fill_with_objects(bottom, top - bottom);
  }
}

#endif // INCLUDE_CDS_JAVA_HEAP

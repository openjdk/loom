/*
 * Copyright (c) 2020, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_CDS_ARCHIVEBUILDER_HPP
#define SHARE_CDS_ARCHIVEBUILDER_HPP

#include "cds/archiveUtils.hpp"
#include "cds/dumpAllocStats.hpp"
#include "memory/metaspaceClosure.hpp"
#include "oops/array.hpp"
#include "oops/klass.hpp"
#include "runtime/os.hpp"
#include "utilities/bitMap.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/resizeableResourceHash.hpp"
#include "utilities/resourceHash.hpp"

struct ArchiveHeapOopmapInfo;
class CHeapBitMap;
class FileMapInfo;
class Klass;
class MemRegion;
class Symbol;

// Metaspace::allocate() requires that all blocks must be aligned with KlassAlignmentInBytes.
// We enforce the same alignment rule in blocks allocated from the shared space.
const int SharedSpaceObjectAlignment = KlassAlignmentInBytes;

// Overview of CDS archive creation (for both static and dynamic dump):
//
// [1] Load all classes (static dump: from the classlist, dynamic dump: as part of app execution)
// [2] Allocate "output buffer"
// [3] Copy contents of the 2 "core" regions (rw/ro) into the output buffer.
//       - allocate the cpp vtables in rw (static dump only)
//       - memcpy the MetaspaceObjs into rw/ro:
//         dump_rw_region();
//         dump_ro_region();
//       - fix all the pointers in the MetaspaceObjs to point to the copies
//         relocate_metaspaceobj_embedded_pointers()
// [4] Copy symbol table, dictionary, etc, into the ro region
// [5] Relocate all the pointers in rw/ro, so that the archive can be mapped to
//     the "requested" location without runtime relocation. See relocate_to_requested()
//
// "source" vs "buffered" vs "requested"
//
// The ArchiveBuilder deals with three types of addresses.
//
// "source":    These are the addresses of objects created in step [1] above. They are the actual
//              InstanceKlass*, Method*, etc, of the Java classes that are loaded for executing
//              Java bytecodes in the JVM process that's dumping the CDS archive.
//
//              It may be necessary to contiue Java execution after ArchiveBuilder is finished.
//              Therefore, we don't modify any of the "source" objects.
//
// "buffered":  The "source" objects that are deemed archivable are copied into a temporary buffer.
//              Objects in the buffer are modified in steps [2, 3, 4] (e.g., unshareable info is
//              removed, pointers are relocated, etc) to prepare them to be loaded at runtime.
//
// "requested": These are the addreses where the "buffered" objects should be loaded at runtime.
//              When the "buffered" objects are written into the archive file, their addresses
//              are adjusted in step [5] such that the lowest of these objects would be mapped
//              at SharedBaseAddress.
//
// Translation between "source" and "buffered" addresses is done with two hashtables:
//     _src_obj_table          : "source"   -> "buffered"
//     _buffered_to_src_table  : "buffered" -> "source"
//
// Translation between "buffered" and "requested" addresses is done with a simple shift:
//    buffered_address + _buffer_to_requested_delta == requested_address
//
class ArchiveBuilder : public StackObj {
protected:
  DumpRegion* _current_dump_space;
  address _buffer_bottom;                      // for writing the contents of rw/ro regions
  address _last_verified_top;
  int _num_dump_regions_used;
  size_t _other_region_used_bytes;

  // These are the addresses where we will request the static and dynamic archives to be
  // mapped at run time. If the request fails (due to ASLR), we will map the archives at
  // os-selected addresses.
  address _requested_static_archive_bottom;     // This is determined solely by the value of
                                                // SharedBaseAddress during -Xshare:dump.
  address _requested_static_archive_top;
  address _requested_dynamic_archive_bottom;    // Used only during dynamic dump. It's placed
                                                // immediately above _requested_static_archive_top.
  address _requested_dynamic_archive_top;

  // (Used only during dynamic dump) where the static archive is actually mapped. This
  // may be different than _requested_static_archive_{bottom,top} due to ASLR
  address _mapped_static_archive_bottom;
  address _mapped_static_archive_top;

  intx _buffer_to_requested_delta;

  DumpRegion* current_dump_space() const {  return _current_dump_space;  }

public:
  enum FollowMode {
    make_a_copy, point_to_it, set_to_null
  };

private:
  class SpecialRefInfo {
    // We have a "special pointer" of the given _type at _field_offset of _src_obj.
    // See MetaspaceClosure::push_special().
    MetaspaceClosure::SpecialRef _type;
    address _src_obj;
    size_t _field_offset;

  public:
    SpecialRefInfo() {}
    SpecialRefInfo(MetaspaceClosure::SpecialRef type, address src_obj, size_t field_offset)
      : _type(type), _src_obj(src_obj), _field_offset(field_offset) {}

    MetaspaceClosure::SpecialRef type() const { return _type;         }
    address src_obj()                   const { return _src_obj;      }
    size_t field_offset()               const { return _field_offset; }
  };

  class SourceObjInfo {
    MetaspaceClosure::Ref* _ref; // The object that's copied into the buffer
    uintx _ptrmap_start;     // The bit-offset of the start of this object (inclusive)
    uintx _ptrmap_end;       // The bit-offset of the end   of this object (exclusive)
    bool _read_only;
    FollowMode _follow_mode;
    int _size_in_bytes;
    MetaspaceObj::Type _msotype;
    address _source_addr;    // The value of the source object (_ref->obj()) when this
                             // SourceObjInfo was created. Note that _ref->obj() may change
                             // later if _ref is relocated.
    address _buffered_addr;  // The copy of _ref->obj() insider the buffer.
  public:
    SourceObjInfo(MetaspaceClosure::Ref* ref, bool read_only, FollowMode follow_mode) :
      _ref(ref), _ptrmap_start(0), _ptrmap_end(0), _read_only(read_only), _follow_mode(follow_mode),
      _size_in_bytes(ref->size() * BytesPerWord), _msotype(ref->msotype()),
      _source_addr(ref->obj()) {
      if (follow_mode == point_to_it) {
        _buffered_addr = ref->obj();
      } else {
        _buffered_addr = NULL;
      }
    }

    bool should_copy() const { return _follow_mode == make_a_copy; }
    MetaspaceClosure::Ref* ref() const { return  _ref; }
    void set_buffered_addr(address addr)  {
      assert(should_copy(), "must be");
      assert(_buffered_addr == NULL, "cannot be copied twice");
      assert(addr != NULL, "must be a valid copy");
      _buffered_addr = addr;
    }
    void set_ptrmap_start(uintx v) { _ptrmap_start = v;    }
    void set_ptrmap_end(uintx v)   { _ptrmap_end = v;      }
    uintx ptrmap_start()  const    { return _ptrmap_start; } // inclusive
    uintx ptrmap_end()    const    { return _ptrmap_end;   } // exclusive
    bool read_only()      const    { return _read_only;    }
    int size_in_bytes()   const    { return _size_in_bytes; }
    address source_addr() const    { return _source_addr; }
    address buffered_addr() const  { return _buffered_addr; }
    MetaspaceObj::Type msotype() const { return _msotype; }

    // convenience accessor
    address obj() const { return ref()->obj(); }
  };

  class SourceObjList {
    uintx _total_bytes;
    GrowableArray<SourceObjInfo*>* _objs;     // Source objects to be archived
    CHeapBitMap _ptrmap;                      // Marks the addresses of the pointer fields
                                              // in the source objects
  public:
    SourceObjList();
    ~SourceObjList();

    GrowableArray<SourceObjInfo*>* objs() const { return _objs; }

    void append(MetaspaceClosure::Ref* enclosing_ref, SourceObjInfo* src_info);
    void remember_embedded_pointer(SourceObjInfo* pointing_obj, MetaspaceClosure::Ref* ref);
    void relocate(int i, ArchiveBuilder* builder);

    // convenience accessor
    SourceObjInfo* at(int i) const { return objs()->at(i); }
  };

  class SrcObjTableCleaner {
  public:
    bool do_entry(address key, const SourceObjInfo& value) {
      delete value.ref();
      return true;
    }
  };

  class CDSMapLogger;

  static const int INITIAL_TABLE_SIZE = 15889;
  static const int MAX_TABLE_SIZE     = 1000000;

  ReservedSpace _shared_rs;
  VirtualSpace _shared_vs;

  DumpRegion _rw_region;
  DumpRegion _ro_region;
  CHeapBitMap _ptrmap;    // bitmap used by ArchivePtrMarker

  SourceObjList _rw_src_objs;                 // objs to put in rw region
  SourceObjList _ro_src_objs;                 // objs to put in ro region
  ResizeableResourceHashtable<address, SourceObjInfo, ResourceObj::C_HEAP, mtClassShared> _src_obj_table;
  ResizeableResourceHashtable<address, address, ResourceObj::C_HEAP, mtClassShared> _buffered_to_src_table;
  GrowableArray<Klass*>* _klasses;
  GrowableArray<Symbol*>* _symbols;
  GrowableArray<SpecialRefInfo>* _special_refs;

  // statistics
  DumpAllocStats _alloc_stats;
  size_t _total_closed_heap_region_size;
  size_t _total_open_heap_region_size;

  void print_region_stats(FileMapInfo *map_info,
                          GrowableArray<MemRegion>* closed_heap_regions,
                          GrowableArray<MemRegion>* open_heap_regions);
  void print_bitmap_region_stats(size_t size, size_t total_size);
  void print_heap_region_stats(GrowableArray<MemRegion>* regions,
                               const char *name, size_t total_size);

  // For global access.
  static ArchiveBuilder* _current;

public:
  // Use this when you allocate space outside of ArchiveBuilder::dump_{rw,ro}_region.
  // These are usually for misc tables that are allocated in the RO space.
  class OtherROAllocMark {
    char* _oldtop;
  public:
    OtherROAllocMark() {
      _oldtop = _current->_ro_region.top();
    }
    ~OtherROAllocMark();
  };

private:
  bool is_dumping_full_module_graph();
  FollowMode get_follow_mode(MetaspaceClosure::Ref *ref);

  void iterate_sorted_roots(MetaspaceClosure* it, bool is_relocating_pointers);
  void sort_symbols_and_fix_hash();
  void sort_klasses();
  static int compare_symbols_by_address(Symbol** a, Symbol** b);
  static int compare_klass_by_name(Klass** a, Klass** b);

  void make_shallow_copies(DumpRegion *dump_region, const SourceObjList* src_objs);
  void make_shallow_copy(DumpRegion *dump_region, SourceObjInfo* src_info);

  void update_special_refs();
  void relocate_embedded_pointers(SourceObjList* src_objs);

  bool is_excluded(Klass* k);
  void clean_up_src_obj_table();

protected:
  virtual void iterate_roots(MetaspaceClosure* it, bool is_relocating_pointers) = 0;

  // Conservative estimate for number of bytes needed for:
  size_t _estimated_metaspaceobj_bytes;   // all archived MetaspaceObj's.
  size_t _estimated_hashtable_bytes;     // symbol table and dictionaries

  static const int _total_dump_regions = 2;

  size_t estimate_archive_size();

  void start_dump_space(DumpRegion* next);
  void verify_estimate_size(size_t estimate, const char* which);

public:
  address reserve_buffer();

  address buffer_bottom()                    const { return _buffer_bottom;                       }
  address buffer_top()                       const { return (address)current_dump_space()->top(); }
  address requested_static_archive_bottom()  const { return  _requested_static_archive_bottom;    }
  address mapped_static_archive_bottom()     const { return  _mapped_static_archive_bottom;       }
  intx buffer_to_requested_delta()           const { return _buffer_to_requested_delta;           }

  bool is_in_buffer_space(address p) const {
    return (buffer_bottom() <= p && p < buffer_top());
  }

  template <typename T> bool is_in_requested_static_archive(T p) const {
    return _requested_static_archive_bottom <= (address)p && (address)p < _requested_static_archive_top;
  }

  template <typename T> bool is_in_mapped_static_archive(T p) const {
    return _mapped_static_archive_bottom <= (address)p && (address)p < _mapped_static_archive_top;
  }

  template <typename T> bool is_in_buffer_space(T obj) const {
    return is_in_buffer_space(address(obj));
  }

  template <typename T> T to_requested(T obj) const {
    assert(is_in_buffer_space(obj), "must be");
    return (T)(address(obj) + _buffer_to_requested_delta);
  }

  static intx get_buffer_to_requested_delta() {
    return current()->buffer_to_requested_delta();
  }

public:
  static const uintx MAX_SHARED_DELTA = 0x7FFFFFFF;

  // The address p points to an object inside the output buffer. When the archive is mapped
  // at the requested address, what's the offset of this object from _requested_static_archive_bottom?
  uintx buffer_to_offset(address p) const;

  // Same as buffer_to_offset, except that the address p points to either (a) an object
  // inside the output buffer, or (b), an object in the currently mapped static archive.
  uintx any_to_offset(address p) const;

  template <typename T>
  u4 buffer_to_offset_u4(T p) const {
    uintx offset = buffer_to_offset((address)p);
    guarantee(offset <= MAX_SHARED_DELTA, "must be 32-bit offset " INTPTR_FORMAT, offset);
    return (u4)offset;
  }

  template <typename T>
  u4 any_to_offset_u4(T p) const {
    uintx offset = any_to_offset((address)p);
    guarantee(offset <= MAX_SHARED_DELTA, "must be 32-bit offset " INTPTR_FORMAT, offset);
    return (u4)offset;
  }

  static void assert_is_vm_thread() PRODUCT_RETURN;

public:
  ArchiveBuilder();
  ~ArchiveBuilder();

  void gather_klasses_and_symbols();
  void gather_source_objs();
  bool gather_klass_and_symbol(MetaspaceClosure::Ref* ref, bool read_only);
  bool gather_one_source_obj(MetaspaceClosure::Ref* enclosing_ref, MetaspaceClosure::Ref* ref, bool read_only);
  void add_special_ref(MetaspaceClosure::SpecialRef type, address src_obj, size_t field_offset);
  void remember_embedded_pointer_in_copied_obj(MetaspaceClosure::Ref* enclosing_ref, MetaspaceClosure::Ref* ref);

  DumpRegion* rw_region() { return &_rw_region; }
  DumpRegion* ro_region() { return &_ro_region; }

  static char* rw_region_alloc(size_t num_bytes) {
    return current()->rw_region()->allocate(num_bytes);
  }
  static char* ro_region_alloc(size_t num_bytes) {
    return current()->ro_region()->allocate(num_bytes);
  }

  template <typename T>
  static Array<T>* new_ro_array(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    Array<T>* array = (Array<T>*)ro_region_alloc(byte_size);
    array->initialize(length);
    return array;
  }

  template <typename T>
  static Array<T>* new_rw_array(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    Array<T>* array = (Array<T>*)rw_region_alloc(byte_size);
    array->initialize(length);
    return array;
  }

  template <typename T>
  static size_t ro_array_bytesize(int length) {
    size_t byte_size = Array<T>::byte_sizeof(length, sizeof(T));
    return align_up(byte_size, SharedSpaceObjectAlignment);
  }

  void dump_rw_metadata();
  void dump_ro_metadata();
  void relocate_metaspaceobj_embedded_pointers();
  void relocate_roots();
  void relocate_vm_classes();
  void make_klasses_shareable();
  void relocate_to_requested();
  void write_archive(FileMapInfo* mapinfo,
                     GrowableArray<MemRegion>* closed_heap_regions,
                     GrowableArray<MemRegion>* open_heap_regions,
                     GrowableArray<ArchiveHeapOopmapInfo>* closed_heap_oopmaps,
                     GrowableArray<ArchiveHeapOopmapInfo>* open_heap_oopmaps);
  void write_region(FileMapInfo* mapinfo, int region_idx, DumpRegion* dump_region,
                    bool read_only,  bool allow_exec);

  address get_buffered_addr(address src_addr) const;
  address get_source_addr(address buffered_addr) const;
  template <typename T> T get_source_addr(T buffered_addr) const {
    return (T)get_source_addr((address)buffered_addr);
  }

  // All klasses and symbols that will be copied into the archive
  GrowableArray<Klass*>*  klasses() const { return _klasses; }
  GrowableArray<Symbol*>* symbols() const { return _symbols; }

  static bool is_active() {
    return (_current != NULL);
  }

  static ArchiveBuilder* current() {
    assert_is_vm_thread();
    assert(_current != NULL, "ArchiveBuilder must be active");
    return _current;
  }

  static DumpAllocStats* alloc_stats() {
    return &(current()->_alloc_stats);
  }

  static CompactHashtableStats* symbol_stats() {
    return alloc_stats()->symbol_stats();
  }

  static CompactHashtableStats* string_stats() {
    return alloc_stats()->string_stats();
  }

  void relocate_klass_ptr_of_oop(oop o);

  static Klass* get_buffered_klass(Klass* src_klass) {
    Klass* klass = (Klass*)current()->get_buffered_addr((address)src_klass);
    assert(klass != NULL && klass->is_klass(), "must be");
    return klass;
  }

  static Symbol* get_buffered_symbol(Symbol* src_symbol) {
    return (Symbol*)current()->get_buffered_addr((address)src_symbol);
  }

  void print_stats();
  void report_out_of_space(const char* name, size_t needed_bytes);
};

#endif // SHARE_CDS_ARCHIVEBUILDER_HPP

/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/classLoaderDataShared.hpp"
#include "classfile/systemDictionaryShared.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/allStatic.hpp"
#include "memory/archiveBuilder.hpp"
#include "memory/archiveUtils.hpp"
#include "memory/cppVtables.hpp"
#include "memory/dumpAllocStats.hpp"
#include "memory/memRegion.hpp"
#include "memory/metaspaceShared.hpp"
#include "memory/resourceArea.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/oopHandle.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "utilities/align.hpp"
#include "utilities/bitMap.inline.hpp"
#include "utilities/hashtable.inline.hpp"

ArchiveBuilder* ArchiveBuilder::_singleton = NULL;
intx ArchiveBuilder::_buffer_to_target_delta = 0;

class AdapterHandlerEntry;

class MethodTrampolineInfo {
  address _c2i_entry_trampoline;
  AdapterHandlerEntry** _adapter_trampoline;
public:
  address c2i_entry_trampoline() { return _c2i_entry_trampoline; }
  AdapterHandlerEntry** adapter_trampoline() { return _adapter_trampoline; }
  void set_c2i_entry_trampoline(address addr) { _c2i_entry_trampoline = addr; }
  void set_adapter_trampoline(AdapterHandlerEntry** entry) { _adapter_trampoline = entry; }
};

class AdapterToTrampoline : public ResourceHashtable<
  AdapterHandlerEntry*, MethodTrampolineInfo,
  primitive_hash<AdapterHandlerEntry*>,
  primitive_equals<AdapterHandlerEntry*>,
  941, // prime number
  ResourceObj::C_HEAP> {};

static AdapterToTrampoline* _adapter_to_trampoline = NULL;

ArchiveBuilder::OtherROAllocMark::~OtherROAllocMark() {
  char* newtop = ArchiveBuilder::singleton()->_ro_region->top();
  ArchiveBuilder::alloc_stats()->record_other_type(int(newtop - _oldtop), true);
}

ArchiveBuilder::SourceObjList::SourceObjList() : _ptrmap(16 * K) {
  _total_bytes = 0;
  _objs = new (ResourceObj::C_HEAP, mtClassShared) GrowableArray<SourceObjInfo*>(128 * K, mtClassShared);
}

ArchiveBuilder::SourceObjList::~SourceObjList() {
  delete _objs;
}

void ArchiveBuilder::SourceObjList::append(MetaspaceClosure::Ref* enclosing_ref, SourceObjInfo* src_info) {
  // Save this source object for copying
  _objs->append(src_info);

  // Prepare for marking the pointers in this source object
  assert(is_aligned(_total_bytes, sizeof(address)), "must be");
  src_info->set_ptrmap_start(_total_bytes / sizeof(address));
  _total_bytes = align_up(_total_bytes + (uintx)src_info->size_in_bytes(), sizeof(address));
  src_info->set_ptrmap_end(_total_bytes / sizeof(address));

  BitMap::idx_t bitmap_size_needed = BitMap::idx_t(src_info->ptrmap_end());
  if (_ptrmap.size() <= bitmap_size_needed) {
    _ptrmap.resize((bitmap_size_needed + 1) * 2);
  }
}

void ArchiveBuilder::SourceObjList::remember_embedded_pointer(SourceObjInfo* src_info, MetaspaceClosure::Ref* ref) {
  // src_obj contains a pointer. Remember the location of this pointer in _ptrmap,
  // so that we can copy/relocate it later. E.g., if we have
  //    class Foo { intx scala; Bar* ptr; }
  //    Foo *f = 0x100;
  // To mark the f->ptr pointer on 64-bit platform, this function is called with
  //    src_info()->obj() == 0x100
  //    ref->addr() == 0x108
  address src_obj = src_info->obj();
  address* field_addr = ref->addr();
  assert(src_info->ptrmap_start() < _total_bytes, "sanity");
  assert(src_info->ptrmap_end() <= _total_bytes, "sanity");
  assert(*field_addr != NULL, "should have checked");

  intx field_offset_in_bytes = ((address)field_addr) - src_obj;
  DEBUG_ONLY(int src_obj_size = src_info->size_in_bytes();)
  assert(field_offset_in_bytes >= 0, "must be");
  assert(field_offset_in_bytes + intx(sizeof(intptr_t)) <= intx(src_obj_size), "must be");
  assert(is_aligned(field_offset_in_bytes, sizeof(address)), "must be");

  BitMap::idx_t idx = BitMap::idx_t(src_info->ptrmap_start() + (uintx)(field_offset_in_bytes / sizeof(address)));
  _ptrmap.set_bit(BitMap::idx_t(idx));
}

class RelocateEmbeddedPointers : public BitMapClosure {
  ArchiveBuilder* _builder;
  address _dumped_obj;
  BitMap::idx_t _start_idx;
public:
  RelocateEmbeddedPointers(ArchiveBuilder* builder, address dumped_obj, BitMap::idx_t start_idx) :
    _builder(builder), _dumped_obj(dumped_obj), _start_idx(start_idx) {}

  bool do_bit(BitMap::idx_t bit_offset) {
    uintx FLAG_MASK = 0x03; // See comments around MetaspaceClosure::FLAG_MASK
    size_t field_offset = size_t(bit_offset - _start_idx) * sizeof(address);
    address* ptr_loc = (address*)(_dumped_obj + field_offset);

    uintx old_p_and_bits = (uintx)(*ptr_loc);
    uintx flag_bits = (old_p_and_bits & FLAG_MASK);
    address old_p = (address)(old_p_and_bits & (~FLAG_MASK));
    address new_p = _builder->get_dumped_addr(old_p);
    uintx new_p_and_bits = ((uintx)new_p) | flag_bits;

    log_trace(cds)("Ref: [" PTR_FORMAT "] -> " PTR_FORMAT " => " PTR_FORMAT,
                   p2i(ptr_loc), p2i(old_p), p2i(new_p));

    ArchivePtrMarker::set_and_mark_pointer(ptr_loc, (address)(new_p_and_bits));
    return true; // keep iterating the bitmap
  }
};

void ArchiveBuilder::SourceObjList::relocate(int i, ArchiveBuilder* builder) {
  SourceObjInfo* src_info = objs()->at(i);
  assert(src_info->should_copy(), "must be");
  BitMap::idx_t start = BitMap::idx_t(src_info->ptrmap_start()); // inclusive
  BitMap::idx_t end = BitMap::idx_t(src_info->ptrmap_end());     // exclusive

  RelocateEmbeddedPointers relocator(builder, src_info->dumped_addr(), start);
  _ptrmap.iterate(&relocator, start, end);
}

ArchiveBuilder::ArchiveBuilder(DumpRegion* mc_region, DumpRegion* rw_region, DumpRegion* ro_region)
  : _rw_src_objs(), _ro_src_objs(), _src_obj_table(INITIAL_TABLE_SIZE) {
  assert(_singleton == NULL, "must be");
  _singleton = this;

  _klasses = new (ResourceObj::C_HEAP, mtClassShared) GrowableArray<Klass*>(4 * K, mtClassShared);
  _symbols = new (ResourceObj::C_HEAP, mtClassShared) GrowableArray<Symbol*>(256 * K, mtClassShared);
  _special_refs = new (ResourceObj::C_HEAP, mtClassShared) GrowableArray<SpecialRefInfo>(24 * K, mtClassShared);

  _num_instance_klasses = 0;
  _num_obj_array_klasses = 0;
  _num_type_array_klasses = 0;
  _alloc_stats = new (ResourceObj::C_HEAP, mtClassShared) DumpAllocStats;

  _mc_region = mc_region;
  _rw_region = rw_region;
  _ro_region = ro_region;

  _estimated_metaspaceobj_bytes = 0;
}

ArchiveBuilder::~ArchiveBuilder() {
  assert(_singleton == this, "must be");
  _singleton = NULL;

  clean_up_src_obj_table();

  for (int i = 0; i < _symbols->length(); i++) {
    _symbols->at(i)->decrement_refcount();
  }

  delete _klasses;
  delete _symbols;
  delete _special_refs;
  delete _alloc_stats;
}

class GatherKlassesAndSymbols : public UniqueMetaspaceClosure {
  ArchiveBuilder* _builder;

public:
  GatherKlassesAndSymbols(ArchiveBuilder* builder) : _builder(builder) {}

  virtual bool do_unique_ref(Ref* ref, bool read_only) {
    return _builder->gather_klass_and_symbol(ref, read_only);
  }
};

bool ArchiveBuilder::gather_klass_and_symbol(MetaspaceClosure::Ref* ref, bool read_only) {
  if (ref->obj() == NULL) {
    return false;
  }
  if (get_follow_mode(ref) != make_a_copy) {
    return false;
  }
  if (ref->msotype() == MetaspaceObj::ClassType) {
    Klass* klass = (Klass*)ref->obj();
    assert(klass->is_klass(), "must be");
    if (!is_excluded(klass)) {
      _klasses->append(klass);
      if (klass->is_instance_klass()) {
        _num_instance_klasses ++;
      } else if (klass->is_objArray_klass()) {
        _num_obj_array_klasses ++;
      } else {
        assert(klass->is_typeArray_klass(), "sanity");
        _num_type_array_klasses ++;
      }
    }
    // See RunTimeSharedClassInfo::get_for()
    _estimated_metaspaceobj_bytes += align_up(BytesPerWord, SharedSpaceObjectAlignment);
  } else if (ref->msotype() == MetaspaceObj::SymbolType) {
    // Make sure the symbol won't be GC'ed while we are dumping the archive.
    Symbol* sym = (Symbol*)ref->obj();
    sym->increment_refcount();
    _symbols->append(sym);
  }

  int bytes = ref->size() * BytesPerWord;
  _estimated_metaspaceobj_bytes += align_up(bytes, SharedSpaceObjectAlignment);

  return true; // recurse
}

void ArchiveBuilder::gather_klasses_and_symbols() {
  ResourceMark rm;
  log_info(cds)("Gathering classes and symbols ... ");
  GatherKlassesAndSymbols doit(this);
  iterate_roots(&doit, /*is_relocating_pointers=*/false);
#if INCLUDE_CDS_JAVA_HEAP
  if (DumpSharedSpaces && MetaspaceShared::use_full_module_graph()) {
    ClassLoaderDataShared::iterate_symbols(&doit);
  }
#endif
  doit.finish();

  log_info(cds)("Number of classes %d", _num_instance_klasses + _num_obj_array_klasses + _num_type_array_klasses);
  log_info(cds)("    instance classes   = %5d", _num_instance_klasses);
  log_info(cds)("    obj array classes  = %5d", _num_obj_array_klasses);
  log_info(cds)("    type array classes = %5d", _num_type_array_klasses);

  if (DumpSharedSpaces) {
    // To ensure deterministic contents in the static archive, we need to ensure that
    // we iterate the MetaspaceObjs in a deterministic order. It doesn't matter where
    // the MetaspaceObjs are located originally, as they are copied sequentially into
    // the archive during the iteration.
    //
    // The only issue here is that the symbol table and the system directories may be
    // randomly ordered, so we copy the symbols and klasses into two arrays and sort
    // them deterministically.
    //
    // During -Xshare:dump, the order of Symbol creation is strictly determined by
    // the SharedClassListFile (class loading is done in a single thread and the JIT
    // is disabled). Also, Symbols are allocated in monotonically increasing addresses
    // (see Symbol::operator new(size_t, int)). So if we iterate the Symbols by
    // ascending address order, we ensure that all Symbols are copied into deterministic
    // locations in the archive.
    //
    // TODO: in the future, if we want to produce deterministic contents in the
    // dynamic archive, we might need to sort the symbols alphabetically (also see
    // DynamicArchiveBuilder::sort_methods()).
    sort_symbols_and_fix_hash();
    sort_klasses();
  }
}

int ArchiveBuilder::compare_symbols_by_address(Symbol** a, Symbol** b) {
  if (a[0] < b[0]) {
    return -1;
  } else {
    assert(a[0] > b[0], "Duplicated symbol %s unexpected", (*a)->as_C_string());
    return 1;
  }
}

void ArchiveBuilder::sort_symbols_and_fix_hash() {
  log_info(cds)("Sorting symbols and fixing identity hash ... ");
  os::init_random(0x12345678);
  _symbols->sort(compare_symbols_by_address);
  for (int i = 0; i < _symbols->length(); i++) {
    assert(_symbols->at(i)->is_permanent(), "archived symbols must be permanent");
    _symbols->at(i)->update_identity_hash();
  }
}

int ArchiveBuilder::compare_klass_by_name(Klass** a, Klass** b) {
  return a[0]->name()->fast_compare(b[0]->name());
}

void ArchiveBuilder::sort_klasses() {
  log_info(cds)("Sorting classes ... ");
  _klasses->sort(compare_klass_by_name);
}

void ArchiveBuilder::iterate_sorted_roots(MetaspaceClosure* it, bool is_relocating_pointers) {
  int i;

  if (!is_relocating_pointers) {
    // Don't relocate _symbol, so we can safely call decrement_refcount on the
    // original symbols.
    int num_symbols = _symbols->length();
    for (i = 0; i < num_symbols; i++) {
      it->push(_symbols->adr_at(i));
    }
  }

  int num_klasses = _klasses->length();
  for (i = 0; i < num_klasses; i++) {
    it->push(_klasses->adr_at(i));
  }

  iterate_roots(it, is_relocating_pointers);
}

class GatherSortedSourceObjs : public MetaspaceClosure {
  ArchiveBuilder* _builder;

public:
  GatherSortedSourceObjs(ArchiveBuilder* builder) : _builder(builder) {}

  virtual bool do_ref(Ref* ref, bool read_only) {
    return _builder->gather_one_source_obj(enclosing_ref(), ref, read_only);
  }

  virtual void push_special(SpecialRef type, Ref* ref, intptr_t* p) {
    assert(type == _method_entry_ref, "only special type allowed for now");
    address src_obj = ref->obj();
    size_t field_offset = pointer_delta(p, src_obj,  sizeof(u1));
    _builder->add_special_ref(type, src_obj, field_offset);
  };

  virtual void do_pending_ref(Ref* ref) {
    if (ref->obj() != NULL) {
      _builder->remember_embedded_pointer_in_copied_obj(enclosing_ref(), ref);
    }
  }
};

bool ArchiveBuilder::gather_one_source_obj(MetaspaceClosure::Ref* enclosing_ref,
                                           MetaspaceClosure::Ref* ref, bool read_only) {
  address src_obj = ref->obj();
  if (src_obj == NULL) {
    return false;
  }
  ref->set_keep_after_pushing();
  remember_embedded_pointer_in_copied_obj(enclosing_ref, ref);

  FollowMode follow_mode = get_follow_mode(ref);
  SourceObjInfo src_info(ref, read_only, follow_mode);
  bool created;
  SourceObjInfo* p = _src_obj_table.add_if_absent(src_obj, src_info, &created);
  if (created) {
    if (_src_obj_table.maybe_grow(MAX_TABLE_SIZE)) {
      log_info(cds, hashtables)("Expanded _src_obj_table table to %d", _src_obj_table.table_size());
    }
  }

  assert(p->read_only() == src_info.read_only(), "must be");

  if (created && src_info.should_copy()) {
    ref->set_user_data((void*)p);
    if (read_only) {
      _ro_src_objs.append(enclosing_ref, p);
    } else {
      _rw_src_objs.append(enclosing_ref, p);
    }
    return true; // Need to recurse into this ref only if we are copying it
  } else {
    return false;
  }
}

void ArchiveBuilder::add_special_ref(MetaspaceClosure::SpecialRef type, address src_obj, size_t field_offset) {
  _special_refs->append(SpecialRefInfo(type, src_obj, field_offset));
}

void ArchiveBuilder::remember_embedded_pointer_in_copied_obj(MetaspaceClosure::Ref* enclosing_ref,
                                                             MetaspaceClosure::Ref* ref) {
  assert(ref->obj() != NULL, "should have checked");

  if (enclosing_ref != NULL) {
    SourceObjInfo* src_info = (SourceObjInfo*)enclosing_ref->user_data();
    if (src_info == NULL) {
      // source objects of point_to_it/set_to_null types are not copied
      // so we don't need to remember their pointers.
    } else {
      if (src_info->read_only()) {
        _ro_src_objs.remember_embedded_pointer(src_info, ref);
      } else {
        _rw_src_objs.remember_embedded_pointer(src_info, ref);
      }
    }
  }
}

void ArchiveBuilder::gather_source_objs() {
  ResourceMark rm;
  log_info(cds)("Gathering all archivable objects ... ");
  GatherSortedSourceObjs doit(this);
  iterate_sorted_roots(&doit, /*is_relocating_pointers=*/false);
  doit.finish();
}

bool ArchiveBuilder::is_excluded(Klass* klass) {
  if (klass->is_instance_klass()) {
    InstanceKlass* ik = InstanceKlass::cast(klass);
    return SystemDictionaryShared::is_excluded_class(ik);
  } else if (klass->is_objArray_klass()) {
    if (DynamicDumpSharedSpaces) {
      // Don't support archiving of array klasses for now (WHY???)
      return true;
    }
    Klass* bottom = ObjArrayKlass::cast(klass)->bottom_klass();
    if (bottom->is_instance_klass()) {
      return SystemDictionaryShared::is_excluded_class(InstanceKlass::cast(bottom));
    }
  }

  return false;
}

ArchiveBuilder::FollowMode ArchiveBuilder::get_follow_mode(MetaspaceClosure::Ref *ref) {
  address obj = ref->obj();
  if (MetaspaceShared::is_in_shared_metaspace(obj)) {
    // Don't dump existing shared metadata again.
    return point_to_it;
  } else if (ref->msotype() == MetaspaceObj::MethodDataType) {
    return set_to_null;
  } else {
    if (ref->msotype() == MetaspaceObj::ClassType) {
      Klass* klass = (Klass*)ref->obj();
      assert(klass->is_klass(), "must be");
      if (is_excluded(klass)) {
        ResourceMark rm;
        log_debug(cds, dynamic)("Skipping class (excluded): %s", klass->external_name());
        return set_to_null;
      }
    }

    return make_a_copy;
  }
}

void ArchiveBuilder::dump_rw_region() {
  ResourceMark rm;
  log_info(cds)("Allocating RW objects ... ");
  make_shallow_copies(_rw_region, &_rw_src_objs);
}

void ArchiveBuilder::dump_ro_region() {
  ResourceMark rm;
  log_info(cds)("Allocating RO objects ... ");
  make_shallow_copies(_ro_region, &_ro_src_objs);
}

void ArchiveBuilder::make_shallow_copies(DumpRegion *dump_region,
                                         const ArchiveBuilder::SourceObjList* src_objs) {
  for (int i = 0; i < src_objs->objs()->length(); i++) {
    make_shallow_copy(dump_region, src_objs->objs()->at(i));
  }
  log_info(cds)("done (%d objects)", src_objs->objs()->length());
}

void ArchiveBuilder::make_shallow_copy(DumpRegion *dump_region, SourceObjInfo* src_info) {
  MetaspaceClosure::Ref* ref = src_info->ref();
  address src = ref->obj();
  int bytes = src_info->size_in_bytes();
  char* dest;
  char* oldtop;
  char* newtop;

  oldtop = dump_region->top();
  if (ref->msotype() == MetaspaceObj::ClassType) {
    // Save a pointer immediate in front of an InstanceKlass, so
    // we can do a quick lookup from InstanceKlass* -> RunTimeSharedClassInfo*
    // without building another hashtable. See RunTimeSharedClassInfo::get_for()
    // in systemDictionaryShared.cpp.
    Klass* klass = (Klass*)src;
    if (klass->is_instance_klass()) {
      SystemDictionaryShared::validate_before_archiving(InstanceKlass::cast(klass));
      dump_region->allocate(sizeof(address));
    }
  }
  dest = dump_region->allocate(bytes);
  newtop = dump_region->top();

  memcpy(dest, src, bytes);

  intptr_t* archived_vtable = CppVtables::get_archived_vtable(ref->msotype(), (address)dest);
  if (archived_vtable != NULL) {
    *(address*)dest = (address)archived_vtable;
    ArchivePtrMarker::mark_pointer((address*)dest);
  }

  log_trace(cds)("Copy: " PTR_FORMAT " ==> " PTR_FORMAT " %d", p2i(src), p2i(dest), bytes);
  src_info->set_dumped_addr((address)dest);

  _alloc_stats->record(ref->msotype(), int(newtop - oldtop), src_info->read_only());
}

address ArchiveBuilder::get_dumped_addr(address src_obj) const {
  SourceObjInfo* p = _src_obj_table.lookup(src_obj);
  assert(p != NULL, "must be");

  return p->dumped_addr();
}

void ArchiveBuilder::relocate_embedded_pointers(ArchiveBuilder::SourceObjList* src_objs) {
  for (int i = 0; i < src_objs->objs()->length(); i++) {
    src_objs->relocate(i, this);
  }
}

void ArchiveBuilder::update_special_refs() {
  for (int i = 0; i < _special_refs->length(); i++) {
    SpecialRefInfo s = _special_refs->at(i);
    size_t field_offset = s.field_offset();
    address src_obj = s.src_obj();
    address dst_obj = get_dumped_addr(src_obj);
    intptr_t* src_p = (intptr_t*)(src_obj + field_offset);
    intptr_t* dst_p = (intptr_t*)(dst_obj + field_offset);
    assert(s.type() == MetaspaceClosure::_method_entry_ref, "only special type allowed for now");

    assert(*src_p == *dst_p, "must be a copy");
    ArchivePtrMarker::mark_pointer((address*)dst_p);
  }
}

class RefRelocator: public MetaspaceClosure {
  ArchiveBuilder* _builder;

public:
  RefRelocator(ArchiveBuilder* builder) : _builder(builder) {}

  virtual bool do_ref(Ref* ref, bool read_only) {
    if (ref->not_null()) {
      ref->update(_builder->get_dumped_addr(ref->obj()));
      ArchivePtrMarker::mark_pointer(ref->addr());
    }
    return false; // Do not recurse.
  }
};

void ArchiveBuilder::relocate_roots() {
  ResourceMark rm;
  RefRelocator doit(this);
  iterate_sorted_roots(&doit, /*is_relocating_pointers=*/true);
  doit.finish();
}

void ArchiveBuilder::relocate_pointers() {
  log_info(cds)("Relocating embedded pointers ... ");
  relocate_embedded_pointers(&_rw_src_objs);
  relocate_embedded_pointers(&_ro_src_objs);
  update_special_refs();

  log_info(cds)("Relocating external roots ... ");
  relocate_roots();

  log_info(cds)("done");
}

// We must relocate the System::_well_known_klasses only after we have copied the
// java objects in during dump_java_heap_objects(): during the object copy, we operate on
// old objects which assert that their klass is the original klass.
void ArchiveBuilder::relocate_well_known_klasses() {
  log_info(cds)("Relocating SystemDictionary::_well_known_klasses[] ... ");
  ResourceMark rm;
  RefRelocator doit(this);
  SystemDictionary::well_known_klasses_do(&doit);
}

void ArchiveBuilder::make_klasses_shareable() {
  for (int i = 0; i < klasses()->length(); i++) {
    Klass* k = klasses()->at(i);
    k->remove_java_mirror();
    if (k->is_objArray_klass()) {
      // InstanceKlass and TypeArrayKlass will in turn call remove_unshareable_info
      // on their array classes.
    } else if (k->is_typeArray_klass()) {
      k->remove_unshareable_info();
    } else {
      assert(k->is_instance_klass(), " must be");
      InstanceKlass* ik = InstanceKlass::cast(k);
      if (DynamicDumpSharedSpaces) {
        // For static dump, class loader type are already set.
        ik->assign_class_loader_type();
      }

      MetaspaceShared::rewrite_nofast_bytecodes_and_calculate_fingerprints(Thread::current(), ik);
      ik->remove_unshareable_info();

      if (log_is_enabled(Debug, cds, class)) {
        ResourceMark rm;
        log_debug(cds, class)("klasses[%4d] = " PTR_FORMAT " %s", i, p2i(to_target(ik)), ik->external_name());
      }
    }
  }
}

// Write detailed info to a mapfile to analyze contents of the archive.
// static dump:
//   java -Xshare:dump -Xlog:cds+map=trace:file=cds.map:none:filesize=0
// dynamic dump:
//   java -cp MyApp.jar -XX:ArchiveClassesAtExit=MyApp.jsa \
//        -Xlog:cds+map=trace:file=cds.map:none:filesize=0 MyApp
//
// We need to do some address translation because the buffers used at dump time may be mapped to
// a different location at runtime. At dump time, the buffers may be at arbitrary locations
// picked by the OS. At runtime, we try to map at a fixed location (SharedBaseAddress). For
// consistency, we log everything using runtime addresses.
class ArchiveBuilder::CDSMapLogger : AllStatic {
  static intx buffer_to_runtime_delta() {
    // Translate the buffers used by the MC/RW/RO regions to their eventual locations
    // at runtime.
    return _buffer_to_target_delta + MetaspaceShared::final_delta();
  }

  // mc/rw/ro regions only
  static void write_dump_region(const char* name, DumpRegion* region) {
    address region_base = address(region->base());
    address region_top  = address(region->top());
    write_region(name, region_base, region_top, region_base + buffer_to_runtime_delta());
  }

#define _LOG_PREFIX PTR_FORMAT ": @@ %-17s %d"

  static void write_klass(Klass* k, address runtime_dest, const char* type_name, int bytes, Thread* THREAD) {
    ResourceMark rm(THREAD);
    log_debug(cds, map)(_LOG_PREFIX " %s",
                        p2i(runtime_dest), type_name, bytes, k->external_name());
  }
  static void write_method(Method* m, address runtime_dest, const char* type_name, int bytes, Thread* THREAD) {
    ResourceMark rm(THREAD);
    log_debug(cds, map)(_LOG_PREFIX " %s",
                        p2i(runtime_dest), type_name, bytes,  m->external_name());
  }

  // rw/ro regions only
  static void write_objects(DumpRegion* region, const ArchiveBuilder::SourceObjList* src_objs) {
    address last_obj_base = address(region->base());
    address last_obj_end  = address(region->base());
    address region_end    = address(region->end());
    Thread* THREAD = Thread::current();
    for (int i = 0; i < src_objs->objs()->length(); i++) {
      SourceObjInfo* src_info = src_objs->at(i);
      address src = src_info->orig_obj();
      address dest = src_info->dumped_addr();
      write_data(last_obj_base, dest, last_obj_base + buffer_to_runtime_delta());
      address runtime_dest = dest + buffer_to_runtime_delta();
      int bytes = src_info->size_in_bytes();

      MetaspaceObj::Type type = src_info->msotype();
      const char* type_name = MetaspaceObj::type_name(type);

      switch (type) {
      case MetaspaceObj::ClassType:
        write_klass((Klass*)src, runtime_dest, type_name, bytes, THREAD);
        break;
      case MetaspaceObj::ConstantPoolType:
        write_klass(((ConstantPool*)src)->pool_holder(),
                    runtime_dest, type_name, bytes, THREAD);
        break;
      case MetaspaceObj::ConstantPoolCacheType:
        write_klass(((ConstantPoolCache*)src)->constant_pool()->pool_holder(),
                    runtime_dest, type_name, bytes, THREAD);
        break;
      case MetaspaceObj::MethodType:
        write_method((Method*)src, runtime_dest, type_name, bytes, THREAD);
        break;
      case MetaspaceObj::ConstMethodType:
        write_method(((ConstMethod*)src)->method(), runtime_dest, type_name, bytes, THREAD);
        break;
      case MetaspaceObj::SymbolType:
        {
          ResourceMark rm(THREAD);
          Symbol* s = (Symbol*)src;
          log_debug(cds, map)(_LOG_PREFIX " %s", p2i(runtime_dest), type_name, bytes,
                              s->as_quoted_ascii());
        }
        break;
      default:
        log_debug(cds, map)(_LOG_PREFIX, p2i(runtime_dest), type_name, bytes);
        break;
      }

      last_obj_base = dest;
      last_obj_end  = dest + bytes;
    }

    write_data(last_obj_base, last_obj_end, last_obj_base + buffer_to_runtime_delta());
    if (last_obj_end < region_end) {
      log_debug(cds, map)(PTR_FORMAT ": @@ Misc data " SIZE_FORMAT " bytes",
                          p2i(last_obj_end + buffer_to_runtime_delta()),
                          size_t(region_end - last_obj_end));
      write_data(last_obj_end, region_end, last_obj_end + buffer_to_runtime_delta());
    }
  }

#undef _LOG_PREFIX

  // Write information about a region, whose address at dump time is [base .. top). At
  // runtime, this region will be mapped to runtime_base.  runtime_base is 0 if this
  // region will be mapped at os-selected addresses (such as the bitmap region), or will
  // be accessed with os::read (the header).
  static void write_region(const char* name, address base, address top, address runtime_base) {
    size_t size = top - base;
    base = runtime_base;
    top = runtime_base + size;
    log_info(cds, map)("[%-18s " PTR_FORMAT " - " PTR_FORMAT " " SIZE_FORMAT_W(9) " bytes]",
                       name, p2i(base), p2i(top), size);
  }

  // open and closed archive regions
  static void write_heap_region(const char* which, GrowableArray<MemRegion> *regions) {
    for (int i = 0; i < regions->length(); i++) {
      address start = address(regions->at(i).start());
      address end = address(regions->at(i).end());
      write_region(which, start, end, start);
      write_data(start, end, start);
    }
  }

  // Dump all the data [base...top). Pretend that the base address
  // will be mapped to runtime_base at run-time.
  static void write_data(address base, address top, address runtime_base) {
    assert(top >= base, "must be");

    LogStreamHandle(Trace, cds, map) lsh;
    if (lsh.is_enabled()) {
      os::print_hex_dump(&lsh, base, top, sizeof(address), 32, runtime_base);
    }
  }

  static void write_header(FileMapInfo* mapinfo) {
    LogStreamHandle(Info, cds, map) lsh;
    if (lsh.is_enabled()) {
      mapinfo->print(&lsh);
    }
  }

public:
  static void write(ArchiveBuilder* builder, FileMapInfo* mapinfo,
             GrowableArray<MemRegion> *closed_heap_regions,
             GrowableArray<MemRegion> *open_heap_regions,
             char* bitmap, size_t bitmap_size_in_bytes) {
    log_info(cds, map)("%s CDS archive map for %s", DumpSharedSpaces ? "Static" : "Dynamic", mapinfo->full_path());

    address header = address(mapinfo->header());
    address header_end = header + mapinfo->header()->header_size();
    write_region("header", header, header_end, 0);
    write_header(mapinfo);
    write_data(header, header_end, 0);

    DumpRegion* mc_region = builder->_mc_region;
    DumpRegion* rw_region = builder->_rw_region;
    DumpRegion* ro_region = builder->_ro_region;

    address mc = address(mc_region->base());
    address mc_end = address(mc_region->end());
    write_dump_region("mc region", mc_region);
    write_data(mc, mc_end, mc + buffer_to_runtime_delta());

    write_dump_region("rw region", rw_region);
    write_objects(rw_region, &builder->_rw_src_objs);

    write_dump_region("ro region", ro_region);
    write_objects(ro_region, &builder->_ro_src_objs);

    address bitmap_end = address(bitmap + bitmap_size_in_bytes);
    write_region("bitmap", address(bitmap), bitmap_end, 0);
    write_data(header, header_end, 0);

    if (closed_heap_regions != NULL) {
      write_heap_region("closed heap region", closed_heap_regions);
    }
    if (open_heap_regions != NULL) {
      write_heap_region("open heap region", open_heap_regions);
    }

    log_info(cds, map)("[End of CDS archive map]");
  }
};

void ArchiveBuilder::write_cds_map_to_log(FileMapInfo* mapinfo,
                                          GrowableArray<MemRegion> *closed_heap_regions,
                                          GrowableArray<MemRegion> *open_heap_regions,
                                          char* bitmap, size_t bitmap_size_in_bytes) {
  if (log_is_enabled(Info, cds, map)) {
    CDSMapLogger::write(this, mapinfo, closed_heap_regions, open_heap_regions,
                        bitmap, bitmap_size_in_bytes);
  }
}

void ArchiveBuilder::print_stats(int ro_all, int rw_all, int mc_all) {
  _alloc_stats->print_stats(ro_all, rw_all, mc_all);
}

void ArchiveBuilder::clean_up_src_obj_table() {
  SrcObjTableCleaner cleaner;
  _src_obj_table.iterate(&cleaner);
}

void ArchiveBuilder::allocate_method_trampolines_for(InstanceKlass* ik) {
  if (ik->methods() != NULL) {
    for (int j = 0; j < ik->methods()->length(); j++) {
      // Walk the methods in a deterministic order so that the trampolines are
      // created in a deterministic order.
      Method* m = ik->methods()->at(j);
      AdapterHandlerEntry* ent = m->adapter(); // different methods can share the same AdapterHandlerEntry
      MethodTrampolineInfo* info = _adapter_to_trampoline->get(ent);
      if (info->c2i_entry_trampoline() == NULL) {
        info->set_c2i_entry_trampoline(
          (address)MetaspaceShared::misc_code_space_alloc(SharedRuntime::trampoline_size()));
        info->set_adapter_trampoline(
          (AdapterHandlerEntry**)MetaspaceShared::misc_code_space_alloc(sizeof(AdapterHandlerEntry*)));
      }
    }
  }
}

void ArchiveBuilder::allocate_method_trampolines() {
  for (int i = 0; i < _klasses->length(); i++) {
    Klass* k = _klasses->at(i);
    if (k->is_instance_klass()) {
      InstanceKlass* ik = InstanceKlass::cast(k);
      allocate_method_trampolines_for(ik);
    }
  }
}

// Allocate MethodTrampolineInfo for all Methods that will be archived. Also
// return the total number of bytes needed by the method trampolines in the MC
// region.
size_t ArchiveBuilder::allocate_method_trampoline_info() {
  size_t total = 0;
  size_t each_method_bytes =
    align_up(SharedRuntime::trampoline_size(), BytesPerWord) +
    align_up(sizeof(AdapterHandlerEntry*), BytesPerWord);

  if (_adapter_to_trampoline == NULL) {
    _adapter_to_trampoline = new (ResourceObj::C_HEAP, mtClass)AdapterToTrampoline();
  }
  int count = 0;
  for (int i = 0; i < _klasses->length(); i++) {
    Klass* k = _klasses->at(i);
    if (k->is_instance_klass()) {
      InstanceKlass* ik = InstanceKlass::cast(k);
      if (ik->methods() != NULL) {
        for (int j = 0; j < ik->methods()->length(); j++) {
          Method* m = ik->methods()->at(j);
          AdapterHandlerEntry* ent = m->adapter(); // different methods can share the same AdapterHandlerEntry
          bool is_created = false;
          MethodTrampolineInfo* info = _adapter_to_trampoline->put_if_absent(ent, &is_created);
          if (is_created) {
            count++;
          }
        }
      }
    }
  }
  if (count == 0) {
    // We have nothing to archive, but let's avoid having an empty region.
    total = SharedRuntime::trampoline_size();
  } else {
    total = count * each_method_bytes;
  }
  return align_up(total, SharedSpaceObjectAlignment);
}

void ArchiveBuilder::update_method_trampolines() {
  for (int i = 0; i < klasses()->length(); i++) {
    Klass* k = klasses()->at(i);
    if (k->is_instance_klass()) {
      InstanceKlass* ik = InstanceKlass::cast(k);
      Array<Method*>* methods = ik->methods();
      for (int j = 0; j < methods->length(); j++) {
        Method* m = methods->at(j);
        AdapterHandlerEntry* ent = m->adapter();
        MethodTrampolineInfo* info = _adapter_to_trampoline->get(ent);
        // m is the "copy" of the original Method, but its adapter() field is still valid because
        // we haven't called make_klasses_shareable() yet.
        m->set_from_compiled_entry(info->c2i_entry_trampoline());
        m->set_adapter_trampoline(info->adapter_trampoline());
      }
    }
  }
}

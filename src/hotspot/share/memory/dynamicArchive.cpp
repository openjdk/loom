/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "jvm.h"
#include "classfile/classLoaderData.inline.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionaryShared.hpp"
#include "logging/log.hpp"
#include "memory/archiveBuilder.hpp"
#include "memory/archiveUtils.inline.hpp"
#include "memory/dynamicArchive.hpp"
#include "memory/metaspaceClosure.hpp"
#include "memory/metaspaceShared.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/os.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/vmThread.hpp"
#include "runtime/vmOperations.hpp"
#include "utilities/align.hpp"
#include "utilities/bitMap.inline.hpp"


class DynamicArchiveBuilder : public ArchiveBuilder {
public:

  static size_t reserve_alignment() {
    return os::vm_allocation_granularity();
  }

  static const int _total_dump_regions = 3;
  int _num_dump_regions_used;

public:
  void mark_pointer(address* ptr_loc) {
    ArchivePtrMarker::mark_pointer(ptr_loc);
  }

  template <typename T> T get_dumped_addr(T obj) {
    return (T)ArchiveBuilder::get_dumped_addr((address)obj);
  }

  static int dynamic_dump_method_comparator(Method* a, Method* b) {
    Symbol* a_name = a->name();
    Symbol* b_name = b->name();

    if (a_name == b_name) {
      return 0;
    }

    if (!MetaspaceShared::is_in_shared_metaspace(a_name)) {
      // a_name points to a Symbol in the top archive.
      // When this method is called, a_name is still pointing to the output space.
      // Translate it to point to the output space, so that it can be compared with
      // Symbols in the base archive.
      a_name = (Symbol*)(address(a_name) + _buffer_to_target_delta);
    }
    if (!MetaspaceShared::is_in_shared_metaspace(b_name)) {
      b_name = (Symbol*)(address(b_name) + _buffer_to_target_delta);
    }

    return a_name->fast_compare(b_name);
  }

public:
  DynamicArchiveHeader *_header;
  address _last_verified_top;
  size_t _other_region_used_bytes;

  // Conservative estimate for number of bytes needed for:
  size_t _estimated_hashtable_bytes;     // symbol table and dictionaries
  size_t _estimated_trampoline_bytes;    // method entry trampolines

  size_t estimate_archive_size();
  size_t estimate_class_file_size();
  address reserve_space_and_init_buffer_to_target_delta();
  void init_header(address addr);
  void release_header();
  void sort_methods();
  void sort_methods(InstanceKlass* ik) const;
  void remark_pointers_for_instance_klass(InstanceKlass* k, bool should_mark) const;
  void relocate_buffer_to_target();
  void write_archive(char* serialized_data);

  void init_first_dump_space(address reserved_bottom) {
    DumpRegion* mc_space = MetaspaceShared::misc_code_dump_space();
    DumpRegion* rw_space = MetaspaceShared::read_write_dump_space();

    // Use the same MC->RW->RO ordering as in the base archive.
    MetaspaceShared::init_shared_dump_space(mc_space);
    _current_dump_space = mc_space;
    _last_verified_top = reserved_bottom;
    _num_dump_regions_used = 1;
  }

public:
  DynamicArchiveBuilder() : ArchiveBuilder(MetaspaceShared::misc_code_dump_space(),
                                           MetaspaceShared::read_write_dump_space(),
                                           MetaspaceShared::read_only_dump_space()) {
    _estimated_hashtable_bytes = 0;
    _estimated_trampoline_bytes = 0;

    _num_dump_regions_used = 0;
  }

  void start_dump_space(DumpRegion* next) {
    address bottom = _last_verified_top;
    address top = (address)(current_dump_space()->top());
    _other_region_used_bytes += size_t(top - bottom);

    MetaspaceShared::pack_dump_space(current_dump_space(), next, MetaspaceShared::shared_rs());
    _current_dump_space = next;
    _num_dump_regions_used ++;

    _last_verified_top = (address)(current_dump_space()->top());
  }

  void verify_estimate_size(size_t estimate, const char* which) {
    address bottom = _last_verified_top;
    address top = (address)(current_dump_space()->top());
    size_t used = size_t(top - bottom) + _other_region_used_bytes;
    int diff = int(estimate) - int(used);

    log_info(cds)("%s estimate = " SIZE_FORMAT " used = " SIZE_FORMAT "; diff = %d bytes", which, estimate, used, diff);
    assert(diff >= 0, "Estimate is too small");

    _last_verified_top = top;
    _other_region_used_bytes = 0;
  }

  // Do this before and after the archive dump to see if any corruption
  // is caused by dynamic dumping.
  void verify_universe(const char* info) {
    if (VerifyBeforeExit) {
      log_info(cds)("Verify %s", info);
      // Among other things, this ensures that Eden top is correct.
      Universe::heap()->prepare_for_verify();
      Universe::verify(info);
    }
  }

  void doit() {
    SystemDictionaryShared::start_dumping();

    verify_universe("Before CDS dynamic dump");
    DEBUG_ONLY(SystemDictionaryShared::NoClassLoadingMark nclm);
    SystemDictionaryShared::check_excluded_classes();

    gather_klasses_and_symbols();

    // rw space starts ...
    address reserved_bottom = reserve_space_and_init_buffer_to_target_delta();
    init_header(reserved_bottom);

    CHeapBitMap ptrmap;
    ArchivePtrMarker::initialize(&ptrmap, (address*)reserved_bottom, (address*)current_dump_space()->top());

    allocate_method_trampolines();
    verify_estimate_size(_estimated_trampoline_bytes, "Trampolines");

    gather_source_objs();
    start_dump_space(MetaspaceShared::read_write_dump_space());

    log_info(cds, dynamic)("Copying %d klasses and %d symbols",
                           klasses()->length(), symbols()->length());

    dump_rw_region();

    // ro space starts ...
    DumpRegion* ro_space = MetaspaceShared::read_only_dump_space();
    start_dump_space(ro_space);
    dump_ro_region();
    relocate_pointers();

    verify_estimate_size(_estimated_metaspaceobj_bytes, "MetaspaceObjs");

    char* serialized_data;
    {
      // Write the symbol table and system dictionaries to the RO space.
      // Note that these tables still point to the *original* objects, so
      // they would need to call DynamicArchive::original_to_target() to
      // get the correct addresses.
      assert(current_dump_space() == ro_space, "Must be RO space");
      SymbolTable::write_to_archive(symbols());
      SystemDictionaryShared::write_to_archive(false);

      serialized_data = ro_space->top();
      WriteClosure wc(ro_space);
      SymbolTable::serialize_shared_table_header(&wc, false);
      SystemDictionaryShared::serialize_dictionary_headers(&wc, false);
    }

    verify_estimate_size(_estimated_hashtable_bytes, "Hashtables");

    update_method_trampolines();
    sort_methods();

    log_info(cds)("Make classes shareable");
    make_klasses_shareable();

    log_info(cds)("Adjust lambda proxy class dictionary");
    SystemDictionaryShared::adjust_lambda_proxy_class_dictionary();

    log_info(cds)("Final relocation of pointers ... ");
    relocate_buffer_to_target();

    write_archive(serialized_data);
    release_header();

    assert(_num_dump_regions_used == _total_dump_regions, "must be");
    verify_universe("After CDS dynamic dump");
  }

  virtual void iterate_roots(MetaspaceClosure* it, bool is_relocating_pointers) {
    if (!is_relocating_pointers) {
      SystemDictionaryShared::dumptime_classes_do(it);
    }
    FileMapInfo::metaspace_pointers_do(it);
  }
};

size_t DynamicArchiveBuilder::estimate_archive_size() {
  // size of the symbol table and two dictionaries, plus the RunTimeSharedClassInfo's
  size_t symbol_table_est = SymbolTable::estimate_size_for_archive();
  size_t dictionary_est = SystemDictionaryShared::estimate_size_for_archive();
  _estimated_hashtable_bytes = symbol_table_est + dictionary_est;

  _estimated_trampoline_bytes = allocate_method_trampoline_info();

  size_t total = 0;

  total += _estimated_metaspaceobj_bytes;
  total += _estimated_hashtable_bytes;
  total += _estimated_trampoline_bytes;

  // allow fragmentation at the end of each dump region
  total += _total_dump_regions * reserve_alignment();

  log_info(cds, dynamic)("_estimated_hashtable_bytes = " SIZE_FORMAT " + " SIZE_FORMAT " = " SIZE_FORMAT,
                         symbol_table_est, dictionary_est, _estimated_hashtable_bytes);
  log_info(cds, dynamic)("_estimated_metaspaceobj_bytes = " SIZE_FORMAT, _estimated_metaspaceobj_bytes);
  log_info(cds, dynamic)("_estimated_trampoline_bytes = " SIZE_FORMAT, _estimated_trampoline_bytes);
  log_info(cds, dynamic)("total estimate bytes = " SIZE_FORMAT, total);

  return align_up(total, reserve_alignment());
}

address DynamicArchiveBuilder::reserve_space_and_init_buffer_to_target_delta() {
  size_t total = estimate_archive_size();
  ReservedSpace rs(total);
  if (!rs.is_reserved()) {
    log_error(cds, dynamic)("Failed to reserve %d bytes of output buffer.", (int)total);
    vm_direct_exit(0);
  }

  address buffer_base = (address)rs.base();
  log_info(cds, dynamic)("Reserved output buffer space at    : " PTR_FORMAT " [%d bytes]",
                         p2i(buffer_base), (int)total);
  MetaspaceShared::set_shared_rs(rs);

  // At run time, we will mmap the dynamic archive at target_space_bottom.
  // However, at dump time, we may not be able to write into the target_space,
  // as it's occupied by dynamically loaded Klasses. So we allocate a buffer
  // at an arbitrary location chosen by the OS. We will write all the dynamically
  // archived classes into this buffer. At the final stage of dumping, we relocate
  // all pointers that are inside the buffer_space to point to their (runtime)
  // target location inside thetarget_space.
  address target_space_bottom =
    (address)align_up(MetaspaceShared::shared_metaspace_top(), reserve_alignment());
  _buffer_to_target_delta = intx(target_space_bottom) - intx(buffer_base);

  log_info(cds, dynamic)("Target archive space at            : " PTR_FORMAT, p2i(target_space_bottom));
  log_info(cds, dynamic)("Buffer-space to target-space delta : " PTR_FORMAT, p2i((address)_buffer_to_target_delta));

  return buffer_base;
}

void DynamicArchiveBuilder::init_header(address reserved_bottom) {
  _alloc_bottom = reserved_bottom;
  _last_verified_top = reserved_bottom;
  _other_region_used_bytes = 0;

  init_first_dump_space(reserved_bottom);

  FileMapInfo* mapinfo = new FileMapInfo(false);
  assert(FileMapInfo::dynamic_info() == mapinfo, "must be");
  _header = mapinfo->dynamic_header();

  Thread* THREAD = Thread::current();
  FileMapInfo* base_info = FileMapInfo::current_info();
  _header->set_base_header_crc(base_info->crc());
  for (int i = 0; i < MetaspaceShared::n_regions; i++) {
    _header->set_base_region_crc(i, base_info->space_crc(i));
  }
  _header->populate(base_info, os::vm_allocation_granularity());
}

void DynamicArchiveBuilder::release_header() {
  // We temporarily allocated a dynamic FileMapInfo for dumping, which makes it appear we
  // have mapped a dynamic archive, but we actually have not. We are in a safepoint now.
  // Let's free it so that if class loading happens after we leave the safepoint, nothing
  // bad will happen.
  assert(SafepointSynchronize::is_at_safepoint(), "must be");
  FileMapInfo *mapinfo = FileMapInfo::dynamic_info();
  assert(mapinfo != NULL && _header == mapinfo->dynamic_header(), "must be");
  delete mapinfo;
  assert(!DynamicArchive::is_mapped(), "must be");
  _header = NULL;
}

void DynamicArchiveBuilder::sort_methods() {
  InstanceKlass::disable_method_binary_search();
  for (int i = 0; i < klasses()->length(); i++) {
    Klass* k = klasses()->at(i);
    if (k->is_instance_klass()) {
      sort_methods(InstanceKlass::cast(k));
    }
  }
}

// The address order of the copied Symbols may be different than when the original
// klasses were created. Re-sort all the tables. See Method::sort_methods().
void DynamicArchiveBuilder::sort_methods(InstanceKlass* ik) const {
  assert(ik != NULL, "DynamicArchiveBuilder currently doesn't support dumping the base archive");
  if (MetaspaceShared::is_in_shared_metaspace(ik)) {
    // We have reached a supertype that's already in the base archive
    return;
  }

  if (ik->java_mirror() == NULL) {
    // NULL mirror means this class has already been visited and methods are already sorted
    return;
  }
  ik->remove_java_mirror();

  if (log_is_enabled(Debug, cds, dynamic)) {
    ResourceMark rm;
    log_debug(cds, dynamic)("sorting methods for " PTR_FORMAT " %s", p2i(to_target(ik)), ik->external_name());
  }

  // Method sorting may re-layout the [iv]tables, which would change the offset(s)
  // of the locations in an InstanceKlass that would contain pointers. Let's clear
  // all the existing pointer marking bits, and re-mark the pointers after sorting.
  remark_pointers_for_instance_klass(ik, false);

  // Make sure all supertypes have been sorted
  sort_methods(ik->java_super());
  Array<InstanceKlass*>* interfaces = ik->local_interfaces();
  int len = interfaces->length();
  for (int i = 0; i < len; i++) {
    sort_methods(interfaces->at(i));
  }

#ifdef ASSERT
  if (ik->methods() != NULL) {
    for (int m = 0; m < ik->methods()->length(); m++) {
      Symbol* name = ik->methods()->at(m)->name();
      assert(MetaspaceShared::is_in_shared_metaspace(name) || is_in_buffer_space(name), "must be");
    }
  }
  if (ik->default_methods() != NULL) {
    for (int m = 0; m < ik->default_methods()->length(); m++) {
      Symbol* name = ik->default_methods()->at(m)->name();
      assert(MetaspaceShared::is_in_shared_metaspace(name) || is_in_buffer_space(name), "must be");
    }
  }
#endif

  Thread* THREAD = Thread::current();
  Method::sort_methods(ik->methods(), /*set_idnums=*/true, dynamic_dump_method_comparator);
  if (ik->default_methods() != NULL) {
    Method::sort_methods(ik->default_methods(), /*set_idnums=*/false, dynamic_dump_method_comparator);
  }
  ik->vtable().initialize_vtable(true, THREAD); assert(!HAS_PENDING_EXCEPTION, "cannot fail");
  ik->itable().initialize_itable(true, THREAD); assert(!HAS_PENDING_EXCEPTION, "cannot fail");

  // Set all the pointer marking bits after sorting.
  remark_pointers_for_instance_klass(ik, true);
}

template<bool should_mark>
class PointerRemarker: public MetaspaceClosure {
public:
  virtual bool do_ref(Ref* ref, bool read_only) {
    if (should_mark) {
      ArchivePtrMarker::mark_pointer(ref->addr());
    } else {
      ArchivePtrMarker::clear_pointer(ref->addr());
    }
    return false; // don't recurse
  }
};

void DynamicArchiveBuilder::remark_pointers_for_instance_klass(InstanceKlass* k, bool should_mark) const {
  if (should_mark) {
    PointerRemarker<true> marker;
    k->metaspace_pointers_do(&marker);
    marker.finish();
  } else {
    PointerRemarker<false> marker;
    k->metaspace_pointers_do(&marker);
    marker.finish();
  }
}

class RelocateBufferToTarget: public BitMapClosure {
  DynamicArchiveBuilder *_builder;
  address* _buffer_bottom;
  intx _buffer_to_target_delta;
 public:
  RelocateBufferToTarget(DynamicArchiveBuilder* builder, address* bottom, intx delta) :
    _builder(builder), _buffer_bottom(bottom), _buffer_to_target_delta(delta) {}

  bool do_bit(size_t offset) {
    address* p = _buffer_bottom + offset;
    assert(_builder->is_in_buffer_space(p), "pointer must live in buffer space");

    address old_ptr = *p;
    if (_builder->is_in_buffer_space(old_ptr)) {
      address new_ptr = old_ptr + _buffer_to_target_delta;
      log_trace(cds, dynamic)("Final patch: @%6d [" PTR_FORMAT " -> " PTR_FORMAT "] " PTR_FORMAT " => " PTR_FORMAT,
                              (int)offset, p2i(p), p2i(_builder->to_target(p)),
                              p2i(old_ptr), p2i(new_ptr));
      *p = new_ptr;
    }

    return true; // keep iterating
  }
};

void DynamicArchiveBuilder::relocate_buffer_to_target() {
  RelocateBufferToTarget patcher(this, (address*)_alloc_bottom, _buffer_to_target_delta);
  ArchivePtrMarker::ptrmap()->iterate(&patcher);

  Array<u8>* table = FileMapInfo::saved_shared_path_table().table();
  SharedPathTable runtime_table(to_target(table), FileMapInfo::shared_path_table().size());
  _header->set_shared_path_table(runtime_table);

  address relocatable_base = (address)SharedBaseAddress;
  address relocatable_end = (address)(current_dump_space()->top()) + _buffer_to_target_delta;

  intx addr_delta = MetaspaceShared::final_delta();
  if (addr_delta == 0) {
    ArchivePtrMarker::compact(relocatable_base, relocatable_end);
  } else {
    // The base archive is NOT mapped at MetaspaceShared::requested_base_address() (due to ASLR).
    // This means that the current content of the dynamic archive is based on a random
    // address. Let's relocate all the pointers, so that it can be mapped to
    // MetaspaceShared::requested_base_address() without runtime relocation.
    //
    // Note: both the base and dynamic archive are written with
    // FileMapHeader::_requested_base_address == MetaspaceShared::requested_base_address()

    // Patch all pointers that are marked by ptrmap within this region,
    // where we have just dumped all the metaspace data.
    address patch_base = (address)_alloc_bottom;
    address patch_end  = (address)current_dump_space()->top();

    // the current value of the pointers to be patched must be within this
    // range (i.e., must point to either the top archive (as currently mapped), or to the
    // (targeted address of) the top archive)
    address valid_old_base = relocatable_base;
    address valid_old_end  = relocatable_end;
    size_t base_plus_top_size = valid_old_end - valid_old_base;
    size_t top_size = patch_end - patch_base;
    size_t base_size = base_plus_top_size - top_size;
    assert(base_plus_top_size > base_size, "no overflow");
    assert(base_plus_top_size > top_size, "no overflow");

    // after patching, the pointers must point inside this range
    // (the requested location of the archive, as mapped at runtime).
    address valid_new_base = (address)MetaspaceShared::requested_base_address();
    address valid_new_end  = valid_new_base + base_plus_top_size;

    log_debug(cds)("Relocating archive from [" INTPTR_FORMAT " - " INTPTR_FORMAT "] to "
                   "[" INTPTR_FORMAT " - " INTPTR_FORMAT "], delta = " INTX_FORMAT " bytes",
                   p2i(patch_base + base_size), p2i(patch_end),
                   p2i(valid_new_base + base_size), p2i(valid_new_end), addr_delta);

    SharedDataRelocator<true> patcher((address*)patch_base, (address*)patch_end, valid_old_base, valid_old_end,
                                      valid_new_base, valid_new_end, addr_delta, ArchivePtrMarker::ptrmap());
    ArchivePtrMarker::ptrmap()->iterate(&patcher);
    ArchivePtrMarker::compact(patcher.max_non_null_offset());
  }
}

void DynamicArchiveBuilder::write_archive(char* serialized_data) {
  int num_klasses = klasses()->length();
  int num_symbols = symbols()->length();

  _header->set_serialized_data(to_target(serialized_data));

  FileMapInfo* dynamic_info = FileMapInfo::dynamic_info();
  assert(dynamic_info != NULL, "Sanity");

  // Now write the archived data including the file offsets.
  const char* archive_name = Arguments::GetSharedDynamicArchivePath();
  dynamic_info->open_for_write(archive_name);
  size_t bitmap_size_in_bytes;
  char* bitmap = MetaspaceShared::write_core_archive_regions(dynamic_info, NULL, NULL, bitmap_size_in_bytes);
  dynamic_info->set_final_requested_base((char*)MetaspaceShared::requested_base_address());
  dynamic_info->set_header_crc(dynamic_info->compute_header_crc());
  dynamic_info->write_header();
  dynamic_info->close();

  write_cds_map_to_log(dynamic_info, NULL, NULL,
                       bitmap, bitmap_size_in_bytes);
  FREE_C_HEAP_ARRAY(char, bitmap);

  address base = to_target(_alloc_bottom);
  address top  = address(current_dump_space()->top()) + _buffer_to_target_delta;
  size_t file_size = pointer_delta(top, base, sizeof(char));

  base += MetaspaceShared::final_delta();
  top += MetaspaceShared::final_delta();
  log_info(cds, dynamic)("Written dynamic archive " PTR_FORMAT " - " PTR_FORMAT
                         " [" SIZE_FORMAT " bytes header, " SIZE_FORMAT " bytes total]",
                         p2i(base), p2i(top), _header->header_size(), file_size);
  log_info(cds, dynamic)("%d klasses; %d symbols", num_klasses, num_symbols);
}

class VM_PopulateDynamicDumpSharedSpace: public VM_Operation {
  DynamicArchiveBuilder* _builder;
public:
  VM_PopulateDynamicDumpSharedSpace(DynamicArchiveBuilder* builder) : _builder(builder) {}
  VMOp_Type type() const { return VMOp_PopulateDumpSharedSpace; }
  void doit() {
    ResourceMark rm;
    if (SystemDictionaryShared::empty_dumptime_table()) {
      log_warning(cds, dynamic)("There is no class to be included in the dynamic archive.");
      return;
    }
    if (AllowArchivingWithJavaAgent) {
      warning("This archive was created with AllowArchivingWithJavaAgent. It should be used "
              "for testing purposes only and should not be used in a production environment");
    }
    FileMapInfo::check_nonempty_dir_in_shared_path_table();

    _builder->doit();
  }
};


void DynamicArchive::dump() {
  if (Arguments::GetSharedDynamicArchivePath() == NULL) {
    log_warning(cds, dynamic)("SharedDynamicArchivePath is not specified");
    return;
  }

  DynamicArchiveBuilder builder;
  _builder = &builder;
  VM_PopulateDynamicDumpSharedSpace op(&builder);
  VMThread::execute(&op);
  _builder = NULL;
}

address DynamicArchive::original_to_buffer_impl(address orig_obj) {
  assert(DynamicDumpSharedSpaces, "must be");
  address buff_obj = _builder->get_dumped_addr(orig_obj);
  assert(buff_obj != NULL, "orig_obj must be used by the dynamic archive");
  assert(buff_obj != orig_obj, "call this only when you know orig_obj must be copied and not just referenced");
  assert(_builder->is_in_buffer_space(buff_obj), "must be");
  return buff_obj;
}

address DynamicArchive::buffer_to_target_impl(address buff_obj) {
  assert(DynamicDumpSharedSpaces, "must be");
  assert(_builder->is_in_buffer_space(buff_obj), "must be");
  return _builder->to_target(buff_obj);
}

address DynamicArchive::original_to_target_impl(address orig_obj) {
  assert(DynamicDumpSharedSpaces, "must be");
  if (MetaspaceShared::is_in_shared_metaspace(orig_obj)) {
    // This happens when the top archive points to a Symbol* in the base archive.
    return orig_obj;
  }
  address buff_obj = _builder->get_dumped_addr(orig_obj);
  assert(buff_obj != NULL, "orig_obj must be used by the dynamic archive");
  if (buff_obj == orig_obj) {
    // We are storing a pointer to an original object into the dynamic buffer. E.g.,
    // a Symbol* that used by both the base and top archives.
    assert(MetaspaceShared::is_in_shared_metaspace(orig_obj), "must be");
    return orig_obj;
  } else {
    return _builder->to_target(buff_obj);
  }
}

uintx DynamicArchive::object_delta_uintx(void* buff_obj) {
  assert(DynamicDumpSharedSpaces, "must be");
  address target_obj = _builder->to_target_no_check(address(buff_obj));
  assert(uintx(target_obj) >= SharedBaseAddress, "must be");
  return uintx(target_obj) - SharedBaseAddress;
}

bool DynamicArchive::is_in_target_space(void *obj) {
  assert(DynamicDumpSharedSpaces, "must be");
  return _builder->is_in_target_space(obj);
}


DynamicArchiveBuilder* DynamicArchive::_builder = NULL;


bool DynamicArchive::validate(FileMapInfo* dynamic_info) {
  assert(!dynamic_info->is_static(), "must be");
  // Check if the recorded base archive matches with the current one
  FileMapInfo* base_info = FileMapInfo::current_info();
  DynamicArchiveHeader* dynamic_header = dynamic_info->dynamic_header();

  // Check the header crc
  if (dynamic_header->base_header_crc() != base_info->crc()) {
    FileMapInfo::fail_continue("Dynamic archive cannot be used: static archive header checksum verification failed.");
    return false;
  }

  // Check each space's crc
  for (int i = 0; i < MetaspaceShared::n_regions; i++) {
    if (dynamic_header->base_region_crc(i) != base_info->space_crc(i)) {
      FileMapInfo::fail_continue("Dynamic archive cannot be used: static archive region #%d checksum verification failed.", i);
      return false;
    }
  }

  return true;
}

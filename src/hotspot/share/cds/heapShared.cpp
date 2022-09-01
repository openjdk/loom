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
#include "cds/archiveBuilder.hpp"
#include "cds/archiveUtils.hpp"
#include "cds/cdsHeapVerifier.hpp"
#include "cds/filemap.hpp"
#include "cds/heapShared.inline.hpp"
#include "cds/metaspaceShared.hpp"
#include "classfile/classLoaderData.hpp"
#include "classfile/classLoaderDataShared.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "classfile/moduleEntry.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/systemDictionaryShared.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/vmSymbols.hpp"
#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/gcLocker.hpp"
#include "gc/shared/gcVMOperations.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/metadataFactory.hpp"
#include "memory/metaspaceClosure.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/compressedOops.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/objArrayOop.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/typeArrayOop.inline.hpp"
#include "prims/jvmtiExport.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/globals_extension.hpp"
#include "runtime/init.hpp"
#include "runtime/java.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "utilities/bitMap.inline.hpp"
#include "utilities/copy.hpp"
#if INCLUDE_G1GC
#include "gc/g1/g1CollectedHeap.hpp"
#endif

#if INCLUDE_CDS_JAVA_HEAP

bool HeapShared::_closed_regions_mapped = false;
bool HeapShared::_open_regions_mapped = false;
bool HeapShared::_is_loaded = false;
bool HeapShared::_disable_writing = false;
address   HeapShared::_narrow_oop_base;
int       HeapShared::_narrow_oop_shift;
DumpedInternedStrings *HeapShared::_dumped_interned_strings = NULL;

// Support for loaded heap.
uintptr_t HeapShared::_loaded_heap_bottom = 0;
uintptr_t HeapShared::_loaded_heap_top = 0;
uintptr_t HeapShared::_dumptime_base_0 = UINTPTR_MAX;
uintptr_t HeapShared::_dumptime_base_1 = UINTPTR_MAX;
uintptr_t HeapShared::_dumptime_base_2 = UINTPTR_MAX;
uintptr_t HeapShared::_dumptime_base_3 = UINTPTR_MAX;
uintptr_t HeapShared::_dumptime_top    = 0;
intx HeapShared::_runtime_offset_0 = 0;
intx HeapShared::_runtime_offset_1 = 0;
intx HeapShared::_runtime_offset_2 = 0;
intx HeapShared::_runtime_offset_3 = 0;
bool HeapShared::_loading_failed = false;

// Support for mapped heap (!UseCompressedOops only)
ptrdiff_t HeapShared::_runtime_delta = 0;

//
// If you add new entries to the following tables, you should know what you're doing!
//

// Entry fields for shareable subgraphs archived in the closed archive heap
// region. Warning: Objects in the subgraphs should not have reference fields
// assigned at runtime.
static ArchivableStaticFieldInfo closed_archive_subgraph_entry_fields[] = {
  {"java/lang/Integer$IntegerCache",              "archivedCache"},
  {"java/lang/Long$LongCache",                    "archivedCache"},
  {"java/lang/Byte$ByteCache",                    "archivedCache"},
  {"java/lang/Short$ShortCache",                  "archivedCache"},
  {"java/lang/Character$CharacterCache",          "archivedCache"},
  {"java/util/jar/Attributes$Name",               "KNOWN_NAMES"},
  {"sun/util/locale/BaseLocale",                  "constantBaseLocales"},
};
// Entry fields for subgraphs archived in the open archive heap region.
static ArchivableStaticFieldInfo open_archive_subgraph_entry_fields[] = {
  {"jdk/internal/module/ArchivedModuleGraph",     "archivedModuleGraph"},
  {"java/util/ImmutableCollections",              "archivedObjects"},
  {"java/lang/ModuleLayer",                       "EMPTY_LAYER"},
  {"java/lang/module/Configuration",              "EMPTY_CONFIGURATION"},
  {"jdk/internal/math/FDBigInteger",              "archivedCaches"},
};

// Entry fields for subgraphs archived in the open archive heap region (full module graph).
static ArchivableStaticFieldInfo fmg_open_archive_subgraph_entry_fields[] = {
  {"jdk/internal/loader/ArchivedClassLoaders",    "archivedClassLoaders"},
  {"jdk/internal/module/ArchivedBootLayer",       "archivedBootLayer"},
  {"java/lang/Module$ArchivedData",               "archivedData"},
};

const static int num_closed_archive_subgraph_entry_fields =
  sizeof(closed_archive_subgraph_entry_fields) / sizeof(ArchivableStaticFieldInfo);
const static int num_open_archive_subgraph_entry_fields =
  sizeof(open_archive_subgraph_entry_fields) / sizeof(ArchivableStaticFieldInfo);
const static int num_fmg_open_archive_subgraph_entry_fields =
  sizeof(fmg_open_archive_subgraph_entry_fields) / sizeof(ArchivableStaticFieldInfo);

GrowableArrayCHeap<oop, mtClassShared>* HeapShared::_pending_roots = NULL;
OopHandle HeapShared::_roots;

#ifdef ASSERT
bool HeapShared::is_archived_object_during_dumptime(oop p) {
  assert(HeapShared::can_write(), "must be");
  assert(DumpSharedSpaces, "this function is only used with -Xshare:dump");
  return Universe::heap()->is_archived_object(p);
}
#endif

static bool is_subgraph_root_class_of(ArchivableStaticFieldInfo fields[], int num, InstanceKlass* ik) {
  for (int i = 0; i < num; i++) {
    if (fields[i].klass == ik) {
      return true;
    }
  }
  return false;
}

bool HeapShared::is_subgraph_root_class(InstanceKlass* ik) {
  return is_subgraph_root_class_of(closed_archive_subgraph_entry_fields,
                                   num_closed_archive_subgraph_entry_fields, ik) ||
         is_subgraph_root_class_of(open_archive_subgraph_entry_fields,
                                   num_open_archive_subgraph_entry_fields, ik) ||
         is_subgraph_root_class_of(fmg_open_archive_subgraph_entry_fields,
                                   num_fmg_open_archive_subgraph_entry_fields, ik);
}

void HeapShared::fixup_regions() {
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

unsigned HeapShared::oop_hash(oop const& p) {
  // Do not call p->identity_hash() as that will update the
  // object header.
  return primitive_hash(cast_from_oop<intptr_t>(p));
}

static void reset_states(oop obj, TRAPS) {
  Handle h_obj(THREAD, obj);
  InstanceKlass* klass = InstanceKlass::cast(obj->klass());
  TempNewSymbol method_name = SymbolTable::new_symbol("resetArchivedStates");
  Symbol* method_sig = vmSymbols::void_method_signature();

  while (klass != NULL) {
    Method* method = klass->find_method(method_name, method_sig);
    if (method != NULL) {
      assert(method->is_private(), "must be");
      if (log_is_enabled(Debug, cds)) {
        ResourceMark rm(THREAD);
        log_debug(cds)("  calling %s", method->name_and_sig_as_C_string());
      }
      JavaValue result(T_VOID);
      JavaCalls::call_special(&result, h_obj, klass,
                              method_name, method_sig, CHECK);
    }
    klass = klass->java_super();
  }
}

void HeapShared::reset_archived_object_states(TRAPS) {
  assert(DumpSharedSpaces, "dump-time only");
  log_debug(cds)("Resetting platform loader");
  reset_states(SystemDictionary::java_platform_loader(), CHECK);
  log_debug(cds)("Resetting system loader");
  reset_states(SystemDictionary::java_system_loader(), CHECK);

  // Clean up jdk.internal.loader.ClassLoaders::bootLoader(), which is not
  // directly used for class loading, but rather is used by the core library
  // to keep track of resources, etc, loaded by the null class loader.
  //
  // Note, this object is non-null, and is not the same as
  // ClassLoaderData::the_null_class_loader_data()->class_loader(),
  // which is null.
  log_debug(cds)("Resetting boot loader");
  JavaValue result(T_OBJECT);
  JavaCalls::call_static(&result,
                         vmClasses::jdk_internal_loader_ClassLoaders_klass(),
                         vmSymbols::bootLoader_name(),
                         vmSymbols::void_BuiltinClassLoader_signature(),
                         CHECK);
  Handle boot_loader(THREAD, result.get_oop());
  reset_states(boot_loader(), CHECK);
}

HeapShared::ArchivedObjectCache* HeapShared::_archived_object_cache = NULL;
HeapShared::OriginalObjectTable* HeapShared::_original_object_table = NULL;
oop HeapShared::find_archived_heap_object(oop obj) {
  assert(DumpSharedSpaces, "dump-time only");
  ArchivedObjectCache* cache = archived_object_cache();
  CachedOopInfo* p = cache->get(obj);
  if (p != NULL) {
    return p->_obj;
  } else {
    return NULL;
  }
}

int HeapShared::append_root(oop obj) {
  assert(DumpSharedSpaces, "dump-time only");

  // No GC should happen since we aren't scanning _pending_roots.
  assert(Thread::current() == (Thread*)VMThread::vm_thread(), "should be in vm thread");

  if (_pending_roots == NULL) {
    _pending_roots = new GrowableArrayCHeap<oop, mtClassShared>(500);
  }

  return _pending_roots->append(obj);
}

objArrayOop HeapShared::roots() {
  if (DumpSharedSpaces) {
    assert(Thread::current() == (Thread*)VMThread::vm_thread(), "should be in vm thread");
    if (!HeapShared::can_write()) {
      return NULL;
    }
  } else {
    assert(UseSharedSpaces, "must be");
  }

  objArrayOop roots = (objArrayOop)_roots.resolve();
  assert(roots != NULL, "should have been initialized");
  return roots;
}

// Returns an objArray that contains all the roots of the archived objects
oop HeapShared::get_root(int index, bool clear) {
  assert(index >= 0, "sanity");
  if (DumpSharedSpaces) {
    assert(Thread::current() == (Thread*)VMThread::vm_thread(), "should be in vm thread");
    assert(_pending_roots != NULL, "sanity");
    return _pending_roots->at(index);
  } else {
    assert(UseSharedSpaces, "must be");
    assert(!_roots.is_empty(), "must have loaded shared heap");
    oop result = roots()->obj_at(index);
    if (clear) {
      clear_root(index);
    }
    return result;
  }
}

void HeapShared::clear_root(int index) {
  assert(index >= 0, "sanity");
  assert(UseSharedSpaces, "must be");
  if (is_fully_available()) {
    if (log_is_enabled(Debug, cds, heap)) {
      oop old = roots()->obj_at(index);
      log_debug(cds, heap)("Clearing root %d: was " PTR_FORMAT, index, p2i(old));
    }
    roots()->obj_at_put(index, NULL);
  }
}

oop HeapShared::archive_object(oop obj) {
  assert(DumpSharedSpaces, "dump-time only");

  assert(!obj->is_stackChunk(), "do not archive stack chunks");

  oop ao = find_archived_heap_object(obj);
  if (ao != NULL) {
    // already archived
    return ao;
  }

  int len = obj->size();
  if (G1CollectedHeap::heap()->is_archive_alloc_too_large(len)) {
    log_debug(cds, heap)("Cannot archive, object (" PTR_FORMAT ") is too large: " SIZE_FORMAT,
                         p2i(obj), (size_t)obj->size());
    return NULL;
  }

  oop archived_oop = cast_to_oop(G1CollectedHeap::heap()->archive_mem_allocate(len));
  if (archived_oop != NULL) {
    Copy::aligned_disjoint_words(cast_from_oop<HeapWord*>(obj), cast_from_oop<HeapWord*>(archived_oop), len);
    // Reinitialize markword to remove age/marking/locking/etc.
    //
    // We need to retain the identity_hash, because it may have been used by some hashtables
    // in the shared heap. This also has the side effect of pre-initializing the
    // identity_hash for all shared objects, so they are less likely to be written
    // into during run time, increasing the potential of memory sharing.
    int hash_original = obj->identity_hash();
    archived_oop->set_mark(markWord::prototype().copy_set_hash(hash_original));
    assert(archived_oop->mark().is_unlocked(), "sanity");

    DEBUG_ONLY(int hash_archived = archived_oop->identity_hash());
    assert(hash_original == hash_archived, "Different hash codes: original %x, archived %x", hash_original, hash_archived);

    ArchivedObjectCache* cache = archived_object_cache();
    CachedOopInfo info = make_cached_oop_info(archived_oop);
    cache->put(obj, info);
    if (_original_object_table != NULL) {
      _original_object_table->put(archived_oop, obj);
    }
    if (log_is_enabled(Debug, cds, heap)) {
      ResourceMark rm;
      log_debug(cds, heap)("Archived heap object " PTR_FORMAT " ==> " PTR_FORMAT " : %s",
                           p2i(obj), p2i(archived_oop), obj->klass()->external_name());
    }
  } else {
    log_error(cds, heap)(
      "Cannot allocate space for object " PTR_FORMAT " in archived heap region",
      p2i(obj));
    log_error(cds)("Out of memory. Please run with a larger Java heap, current MaxHeapSize = "
        SIZE_FORMAT "M", MaxHeapSize/M);
    os::_exit(-1);
  }
  return archived_oop;
}

void HeapShared::archive_klass_objects() {
  GrowableArray<Klass*>* klasses = ArchiveBuilder::current()->klasses();
  assert(klasses != NULL, "sanity");
  for (int i = 0; i < klasses->length(); i++) {
    Klass* k = ArchiveBuilder::get_relocated_klass(klasses->at(i));

    // archive mirror object
    java_lang_Class::archive_mirror(k);

    // archive the resolved_referenes array
    if (k->is_instance_klass()) {
      InstanceKlass* ik = InstanceKlass::cast(k);
      ik->constants()->archive_resolved_references();
    }
  }
}

// -- Handling of Enum objects
// Java Enum classes have synthetic <clinit> methods that look like this
//     enum MyEnum {FOO, BAR}
//     MyEnum::<clinint> {
//        /*static final MyEnum*/ MyEnum::FOO = new MyEnum("FOO");
//        /*static final MyEnum*/ MyEnum::BAR = new MyEnum("BAR");
//     }
//
// If MyEnum::FOO object is referenced by any of the archived subgraphs, we must
// ensure the archived value equals (in object address) to the runtime value of
// MyEnum::FOO.
//
// However, since MyEnum::<clinint> is synthetically generated by javac, there's
// no way of programmatically handling this inside the Java code (as you would handle
// ModuleLayer::EMPTY_LAYER, for example).
//
// Instead, we archive all static field of such Enum classes. At runtime,
// HeapShared::initialize_enum_klass() will skip the <clinit> method and pull
// the static fields out of the archived heap.
void HeapShared::check_enum_obj(int level,
                                KlassSubGraphInfo* subgraph_info,
                                oop orig_obj,
                                bool is_closed_archive) {
  Klass* k = orig_obj->klass();
  Klass* relocated_k = ArchiveBuilder::get_relocated_klass(k);
  if (!k->is_instance_klass()) {
    return;
  }
  InstanceKlass* ik = InstanceKlass::cast(k);
  if (ik->java_super() == vmClasses::Enum_klass() && !ik->has_archived_enum_objs()) {
    ResourceMark rm;
    ik->set_has_archived_enum_objs();
    relocated_k->set_has_archived_enum_objs();
    oop mirror = ik->java_mirror();

    for (JavaFieldStream fs(ik); !fs.done(); fs.next()) {
      if (fs.access_flags().is_static()) {
        fieldDescriptor& fd = fs.field_descriptor();
        if (fd.field_type() != T_OBJECT && fd.field_type() != T_ARRAY) {
          guarantee(false, "static field %s::%s must be T_OBJECT or T_ARRAY",
                    ik->external_name(), fd.name()->as_C_string());
        }
        oop oop_field = mirror->obj_field(fd.offset());
        if (oop_field == NULL) {
          guarantee(false, "static field %s::%s must not be null",
                    ik->external_name(), fd.name()->as_C_string());
        } else if (oop_field->klass() != ik && oop_field->klass() != ik->array_klass_or_null()) {
          guarantee(false, "static field %s::%s is of the wrong type",
                    ik->external_name(), fd.name()->as_C_string());
        }
        oop archived_oop_field = archive_reachable_objects_from(level, subgraph_info, oop_field, is_closed_archive);
        int root_index = append_root(archived_oop_field);
        log_info(cds, heap)("Archived enum obj @%d %s::%s (" INTPTR_FORMAT " -> " INTPTR_FORMAT ")",
                            root_index, ik->external_name(), fd.name()->as_C_string(),
                            p2i((oopDesc*)oop_field), p2i((oopDesc*)archived_oop_field));
        SystemDictionaryShared::add_enum_klass_static_field(ik, root_index);
      }
    }
  }
}

// See comments in HeapShared::check_enum_obj()
bool HeapShared::initialize_enum_klass(InstanceKlass* k, TRAPS) {
  if (!is_fully_available()) {
    return false;
  }

  RunTimeClassInfo* info = RunTimeClassInfo::get_for(k);
  assert(info != NULL, "sanity");

  if (log_is_enabled(Info, cds, heap)) {
    ResourceMark rm;
    log_info(cds, heap)("Initializing Enum class: %s", k->external_name());
  }

  oop mirror = k->java_mirror();
  int i = 0;
  for (JavaFieldStream fs(k); !fs.done(); fs.next()) {
    if (fs.access_flags().is_static()) {
      int root_index = info->enum_klass_static_field_root_index_at(i++);
      fieldDescriptor& fd = fs.field_descriptor();
      assert(fd.field_type() == T_OBJECT || fd.field_type() == T_ARRAY, "must be");
      mirror->obj_field_put(fd.offset(), get_root(root_index, /*clear=*/true));
    }
  }
  return true;
}

void HeapShared::run_full_gc_in_vm_thread() {
  if (HeapShared::can_write()) {
    // Avoid fragmentation while archiving heap objects.
    // We do this inside a safepoint, so that no further allocation can happen after GC
    // has finished.
    if (GCLocker::is_active()) {
      // Just checking for safety ...
      // This should not happen during -Xshare:dump. If you see this, probably the Java core lib
      // has been modified such that JNI code is executed in some clean up threads after
      // we have finished class loading.
      log_warning(cds)("GC locker is held, unable to start extra compacting GC. This may produce suboptimal results.");
    } else {
      log_info(cds)("Run GC ...");
      Universe::heap()->collect_as_vm_thread(GCCause::_archive_time_gc);
      log_info(cds)("Run GC done");
    }
  }
}

void HeapShared::archive_objects(GrowableArray<MemRegion>* closed_regions,
                                 GrowableArray<MemRegion>* open_regions) {

  G1HeapVerifier::verify_ready_for_archiving();

  {
    NoSafepointVerifier nsv;

    // Cache for recording where the archived objects are copied to
    create_archived_object_cache(log_is_enabled(Info, cds, map));

    log_info(cds)("Heap range = [" PTR_FORMAT " - "  PTR_FORMAT "]",
                   UseCompressedOops ? p2i(CompressedOops::begin()) :
                                       p2i((address)G1CollectedHeap::heap()->reserved().start()),
                   UseCompressedOops ? p2i(CompressedOops::end()) :
                                       p2i((address)G1CollectedHeap::heap()->reserved().end()));
    log_info(cds)("Dumping objects to closed archive heap region ...");
    copy_closed_objects(closed_regions);

    log_info(cds)("Dumping objects to open archive heap region ...");
    copy_open_objects(open_regions);

    CDSHeapVerifier::verify();
  }

  G1HeapVerifier::verify_archive_regions();
}

void HeapShared::copy_closed_objects(GrowableArray<MemRegion>* closed_regions) {
  assert(HeapShared::can_write(), "must be");

  G1CollectedHeap::heap()->begin_archive_alloc_range();

  // Archive interned string objects
  StringTable::write_to_archive(_dumped_interned_strings);

  archive_object_subgraphs(closed_archive_subgraph_entry_fields,
                           num_closed_archive_subgraph_entry_fields,
                           true /* is_closed_archive */,
                           false /* is_full_module_graph */);

  G1CollectedHeap::heap()->end_archive_alloc_range(closed_regions,
                                                   os::vm_allocation_granularity());
}

void HeapShared::copy_open_objects(GrowableArray<MemRegion>* open_regions) {
  assert(HeapShared::can_write(), "must be");

  G1CollectedHeap::heap()->begin_archive_alloc_range(true /* open */);

  java_lang_Class::archive_basic_type_mirrors();

  archive_klass_objects();

  archive_object_subgraphs(open_archive_subgraph_entry_fields,
                           num_open_archive_subgraph_entry_fields,
                           false /* is_closed_archive */,
                           false /* is_full_module_graph */);
  if (MetaspaceShared::use_full_module_graph()) {
    archive_object_subgraphs(fmg_open_archive_subgraph_entry_fields,
                             num_fmg_open_archive_subgraph_entry_fields,
                             false /* is_closed_archive */,
                             true /* is_full_module_graph */);
    ClassLoaderDataShared::init_archived_oops();
  }

  copy_roots();

  G1CollectedHeap::heap()->end_archive_alloc_range(open_regions,
                                                   os::vm_allocation_granularity());
}

// Copy _pending_archive_roots into an objArray
void HeapShared::copy_roots() {
  // HeapShared::roots() points into an ObjArray in the open archive region. A portion of the
  // objects in this array are discovered during HeapShared::archive_objects(). For example,
  // in HeapShared::archive_reachable_objects_from() ->  HeapShared::check_enum_obj().
  // However, HeapShared::archive_objects() happens inside a safepoint, so we can't
  // allocate a "regular" ObjArray and pass the result to HeapShared::archive_object().
  // Instead, we have to roll our own alloc/copy routine here.
  int length = _pending_roots != NULL ? _pending_roots->length() : 0;
  size_t size = objArrayOopDesc::object_size(length);
  Klass* k = Universe::objectArrayKlassObj(); // already relocated to point to archived klass
  HeapWord* mem = G1CollectedHeap::heap()->archive_mem_allocate(size);

  memset(mem, 0, size * BytesPerWord);
  {
    // This is copied from MemAllocator::finish
    oopDesc::set_mark(mem, markWord::prototype());
    oopDesc::release_set_klass(mem, k);
  }
  {
    // This is copied from ObjArrayAllocator::initialize
    arrayOopDesc::set_length(mem, length);
  }

  _roots = OopHandle(Universe::vm_global(), cast_to_oop(mem));
  for (int i = 0; i < length; i++) {
    roots()->obj_at_put(i, _pending_roots->at(i));
  }
  log_info(cds)("archived obj roots[%d] = " SIZE_FORMAT " words, klass = %p, obj = %p", length, size, k, mem);
}

void HeapShared::init_narrow_oop_decoding(address base, int shift) {
  _narrow_oop_base = base;
  _narrow_oop_shift = shift;
}

//
// Subgraph archiving support
//
HeapShared::DumpTimeKlassSubGraphInfoTable* HeapShared::_dump_time_subgraph_info_table = NULL;
HeapShared::RunTimeKlassSubGraphInfoTable   HeapShared::_run_time_subgraph_info_table;

// Get the subgraph_info for Klass k. A new subgraph_info is created if
// there is no existing one for k. The subgraph_info records the relocated
// Klass* of the original k.
KlassSubGraphInfo* HeapShared::init_subgraph_info(Klass* k, bool is_full_module_graph) {
  assert(DumpSharedSpaces, "dump time only");
  bool created;
  Klass* relocated_k = ArchiveBuilder::get_relocated_klass(k);
  KlassSubGraphInfo* info =
    _dump_time_subgraph_info_table->put_if_absent(k, KlassSubGraphInfo(relocated_k, is_full_module_graph),
                                                  &created);
  assert(created, "must not initialize twice");
  return info;
}

KlassSubGraphInfo* HeapShared::get_subgraph_info(Klass* k) {
  assert(DumpSharedSpaces, "dump time only");
  KlassSubGraphInfo* info = _dump_time_subgraph_info_table->get(k);
  assert(info != NULL, "must have been initialized");
  return info;
}

// Add an entry field to the current KlassSubGraphInfo.
void KlassSubGraphInfo::add_subgraph_entry_field(
      int static_field_offset, oop v, bool is_closed_archive) {
  assert(DumpSharedSpaces, "dump time only");
  if (_subgraph_entry_fields == NULL) {
    _subgraph_entry_fields =
      new(ResourceObj::C_HEAP, mtClass) GrowableArray<int>(10, mtClass);
  }
  _subgraph_entry_fields->append(static_field_offset);
  _subgraph_entry_fields->append(HeapShared::append_root(v));
}

// Add the Klass* for an object in the current KlassSubGraphInfo's subgraphs.
// Only objects of boot classes can be included in sub-graph.
void KlassSubGraphInfo::add_subgraph_object_klass(Klass* orig_k) {
  assert(DumpSharedSpaces, "dump time only");
  Klass* relocated_k = ArchiveBuilder::get_relocated_klass(orig_k);

  if (_subgraph_object_klasses == NULL) {
    _subgraph_object_klasses =
      new(ResourceObj::C_HEAP, mtClass) GrowableArray<Klass*>(50, mtClass);
  }

  assert(ArchiveBuilder::current()->is_in_buffer_space(relocated_k), "must be a shared class");

  if (_k == relocated_k) {
    // Don't add the Klass containing the sub-graph to it's own klass
    // initialization list.
    return;
  }

  if (relocated_k->is_instance_klass()) {
    assert(InstanceKlass::cast(relocated_k)->is_shared_boot_class(),
          "must be boot class");
    // vmClasses::xxx_klass() are not updated, need to check
    // the original Klass*
    if (orig_k == vmClasses::String_klass() ||
        orig_k == vmClasses::Object_klass()) {
      // Initialized early during VM initialization. No need to be added
      // to the sub-graph object class list.
      return;
    }
  } else if (relocated_k->is_objArray_klass()) {
    Klass* abk = ObjArrayKlass::cast(relocated_k)->bottom_klass();
    if (abk->is_instance_klass()) {
      assert(InstanceKlass::cast(abk)->is_shared_boot_class(),
            "must be boot class");
    }
    if (relocated_k == Universe::objectArrayKlassObj()) {
      // Initialized early during Universe::genesis. No need to be added
      // to the list.
      return;
    }
  } else {
    assert(relocated_k->is_typeArray_klass(), "must be");
    // Primitive type arrays are created early during Universe::genesis.
    return;
  }

  if (log_is_enabled(Debug, cds, heap)) {
    if (!_subgraph_object_klasses->contains(relocated_k)) {
      ResourceMark rm;
      log_debug(cds, heap)("Adding klass %s", orig_k->external_name());
    }
  }

  _subgraph_object_klasses->append_if_missing(relocated_k);
  _has_non_early_klasses |= is_non_early_klass(orig_k);
}

bool KlassSubGraphInfo::is_non_early_klass(Klass* k) {
  if (k->is_objArray_klass()) {
    k = ObjArrayKlass::cast(k)->bottom_klass();
  }
  if (k->is_instance_klass()) {
    if (!SystemDictionaryShared::is_early_klass(InstanceKlass::cast(k))) {
      ResourceMark rm;
      log_info(cds, heap)("non-early: %s", k->external_name());
      return true;
    } else {
      return false;
    }
  } else {
    return false;
  }
}

// Initialize an archived subgraph_info_record from the given KlassSubGraphInfo.
void ArchivedKlassSubGraphInfoRecord::init(KlassSubGraphInfo* info) {
  _k = info->klass();
  _entry_field_records = NULL;
  _subgraph_object_klasses = NULL;
  _is_full_module_graph = info->is_full_module_graph();

  if (_is_full_module_graph) {
    // Consider all classes referenced by the full module graph as early -- we will be
    // allocating objects of these classes during JVMTI early phase, so they cannot
    // be processed by (non-early) JVMTI ClassFileLoadHook
    _has_non_early_klasses = false;
  } else {
    _has_non_early_klasses = info->has_non_early_klasses();
  }

  if (_has_non_early_klasses) {
    ResourceMark rm;
    log_info(cds, heap)(
          "Subgraph of klass %s has non-early klasses and cannot be used when JVMTI ClassFileLoadHook is enabled",
          _k->external_name());
  }

  // populate the entry fields
  GrowableArray<int>* entry_fields = info->subgraph_entry_fields();
  if (entry_fields != NULL) {
    int num_entry_fields = entry_fields->length();
    assert(num_entry_fields % 2 == 0, "sanity");
    _entry_field_records =
      ArchiveBuilder::new_ro_array<int>(num_entry_fields);
    for (int i = 0 ; i < num_entry_fields; i++) {
      _entry_field_records->at_put(i, entry_fields->at(i));
    }
  }

  // the Klasses of the objects in the sub-graphs
  GrowableArray<Klass*>* subgraph_object_klasses = info->subgraph_object_klasses();
  if (subgraph_object_klasses != NULL) {
    int num_subgraphs_klasses = subgraph_object_klasses->length();
    _subgraph_object_klasses =
      ArchiveBuilder::new_ro_array<Klass*>(num_subgraphs_klasses);
    for (int i = 0; i < num_subgraphs_klasses; i++) {
      Klass* subgraph_k = subgraph_object_klasses->at(i);
      if (log_is_enabled(Info, cds, heap)) {
        ResourceMark rm;
        log_info(cds, heap)(
          "Archived object klass %s (%2d) => %s",
          _k->external_name(), i, subgraph_k->external_name());
      }
      _subgraph_object_klasses->at_put(i, subgraph_k);
      ArchivePtrMarker::mark_pointer(_subgraph_object_klasses->adr_at(i));
    }
  }

  ArchivePtrMarker::mark_pointer(&_k);
  ArchivePtrMarker::mark_pointer(&_entry_field_records);
  ArchivePtrMarker::mark_pointer(&_subgraph_object_klasses);
}

struct CopyKlassSubGraphInfoToArchive : StackObj {
  CompactHashtableWriter* _writer;
  CopyKlassSubGraphInfoToArchive(CompactHashtableWriter* writer) : _writer(writer) {}

  bool do_entry(Klass* klass, KlassSubGraphInfo& info) {
    if (info.subgraph_object_klasses() != NULL || info.subgraph_entry_fields() != NULL) {
      ArchivedKlassSubGraphInfoRecord* record =
        (ArchivedKlassSubGraphInfoRecord*)ArchiveBuilder::ro_region_alloc(sizeof(ArchivedKlassSubGraphInfoRecord));
      record->init(&info);

      Klass* relocated_k = ArchiveBuilder::get_relocated_klass(klass);
      unsigned int hash = SystemDictionaryShared::hash_for_shared_dictionary((address)relocated_k);
      u4 delta = ArchiveBuilder::current()->any_to_offset_u4(record);
      _writer->add(hash, delta);
    }
    return true; // keep on iterating
  }
};

// Build the records of archived subgraph infos, which include:
// - Entry points to all subgraphs from the containing class mirror. The entry
//   points are static fields in the mirror. For each entry point, the field
//   offset, value and is_closed_archive flag are recorded in the sub-graph
//   info. The value is stored back to the corresponding field at runtime.
// - A list of klasses that need to be loaded/initialized before archived
//   java object sub-graph can be accessed at runtime.
void HeapShared::write_subgraph_info_table() {
  // Allocate the contents of the hashtable(s) inside the RO region of the CDS archive.
  DumpTimeKlassSubGraphInfoTable* d_table = _dump_time_subgraph_info_table;
  CompactHashtableStats stats;

  _run_time_subgraph_info_table.reset();

  CompactHashtableWriter writer(d_table->_count, &stats);
  CopyKlassSubGraphInfoToArchive copy(&writer);
  d_table->iterate(&copy);
  writer.dump(&_run_time_subgraph_info_table, "subgraphs");
}

void HeapShared::serialize(SerializeClosure* soc) {
  oop roots_oop = NULL;

  if (soc->reading()) {
    soc->do_oop(&roots_oop); // read from archive
    assert(oopDesc::is_oop_or_null(roots_oop), "is oop");
    // Create an OopHandle only if we have actually mapped or loaded the roots
    if (roots_oop != NULL) {
      assert(HeapShared::is_fully_available(), "must be");
      _roots = OopHandle(Universe::vm_global(), roots_oop);
    }
  } else {
    // writing
    roots_oop = roots();
    soc->do_oop(&roots_oop); // write to archive
  }

  _run_time_subgraph_info_table.serialize_header(soc);
}

static void verify_the_heap(Klass* k, const char* which) {
  if (VerifyArchivedFields > 0) {
    ResourceMark rm;
    log_info(cds, heap)("Verify heap %s initializing static field(s) in %s",
                        which, k->external_name());

    VM_Verify verify_op;
    VMThread::execute(&verify_op);

    if (VerifyArchivedFields > 1 && is_init_completed()) {
      // At this time, the oop->klass() of some archived objects in the heap may not
      // have been loaded into the system dictionary yet. Nevertheless, oop->klass() should
      // have enough information (object size, oop maps, etc) so that a GC can be safely
      // performed.
      //
      // -XX:VerifyArchivedFields=2 force a GC to happen in such an early stage
      // to check for GC safety.
      log_info(cds, heap)("Trigger GC %s initializing static field(s) in %s",
                          which, k->external_name());
      FlagSetting fs1(VerifyBeforeGC, true);
      FlagSetting fs2(VerifyDuringGC, true);
      FlagSetting fs3(VerifyAfterGC,  true);
      Universe::heap()->collect(GCCause::_java_lang_system_gc);
    }
  }
}

// Before GC can execute, we must ensure that all oops reachable from HeapShared::roots()
// have a valid klass. I.e., oopDesc::klass() must have already been resolved.
//
// Note: if a ArchivedKlassSubGraphInfoRecord contains non-early classes, and JVMTI
// ClassFileLoadHook is enabled, it's possible for this class to be dynamically replaced. In
// this case, we will not load the ArchivedKlassSubGraphInfoRecord and will clear its roots.
void HeapShared::resolve_classes(JavaThread* THREAD) {
  if (!is_fully_available()) {
    return; // nothing to do
  }
  resolve_classes_for_subgraphs(closed_archive_subgraph_entry_fields,
                                num_closed_archive_subgraph_entry_fields,
                                THREAD);
  resolve_classes_for_subgraphs(open_archive_subgraph_entry_fields,
                                num_open_archive_subgraph_entry_fields,
                                THREAD);
  resolve_classes_for_subgraphs(fmg_open_archive_subgraph_entry_fields,
                                num_fmg_open_archive_subgraph_entry_fields,
                                THREAD);
}

void HeapShared::resolve_classes_for_subgraphs(ArchivableStaticFieldInfo fields[],
                                               int num, JavaThread* THREAD) {
  for (int i = 0; i < num; i++) {
    ArchivableStaticFieldInfo* info = &fields[i];
    TempNewSymbol klass_name = SymbolTable::new_symbol(info->klass_name);
    InstanceKlass* k = SystemDictionaryShared::find_builtin_class(klass_name);
    assert(k != NULL && k->is_shared_boot_class(), "sanity");
    resolve_classes_for_subgraph_of(k, THREAD);
  }
}

void HeapShared::resolve_classes_for_subgraph_of(Klass* k, JavaThread* THREAD) {
  ExceptionMark em(THREAD);
  const ArchivedKlassSubGraphInfoRecord* record =
   resolve_or_init_classes_for_subgraph_of(k, /*do_init=*/false, THREAD);
  if (HAS_PENDING_EXCEPTION) {
   CLEAR_PENDING_EXCEPTION;
  }
  if (record == NULL) {
   clear_archived_roots_of(k);
  }
}

void HeapShared::initialize_from_archived_subgraph(Klass* k, JavaThread* THREAD) {
  if (!is_fully_available()) {
    return; // nothing to do
  }

  ExceptionMark em(THREAD);
  const ArchivedKlassSubGraphInfoRecord* record =
    resolve_or_init_classes_for_subgraph_of(k, /*do_init=*/true, THREAD);

  if (HAS_PENDING_EXCEPTION) {
    CLEAR_PENDING_EXCEPTION;
    // None of the field value will be set if there was an exception when initializing the classes.
    // The java code will not see any of the archived objects in the
    // subgraphs referenced from k in this case.
    return;
  }

  if (record != NULL) {
    init_archived_fields_for(k, record);
  }
}

const ArchivedKlassSubGraphInfoRecord*
HeapShared::resolve_or_init_classes_for_subgraph_of(Klass* k, bool do_init, TRAPS) {
  assert(!DumpSharedSpaces, "Should not be called with DumpSharedSpaces");

  if (!k->is_shared()) {
    return NULL;
  }
  unsigned int hash = SystemDictionaryShared::hash_for_shared_dictionary_quick(k);
  const ArchivedKlassSubGraphInfoRecord* record = _run_time_subgraph_info_table.lookup(k, hash, 0);

  // Initialize from archived data. Currently this is done only
  // during VM initialization time. No lock is needed.
  if (record != NULL) {
    if (record->is_full_module_graph() && !MetaspaceShared::use_full_module_graph()) {
      if (log_is_enabled(Info, cds, heap)) {
        ResourceMark rm(THREAD);
        log_info(cds, heap)("subgraph %s cannot be used because full module graph is disabled",
                            k->external_name());
      }
      return NULL;
    }

    if (record->has_non_early_klasses() && JvmtiExport::should_post_class_file_load_hook()) {
      if (log_is_enabled(Info, cds, heap)) {
        ResourceMark rm(THREAD);
        log_info(cds, heap)("subgraph %s cannot be used because JVMTI ClassFileLoadHook is enabled",
                            k->external_name());
      }
      return NULL;
    }

    resolve_or_init(k, do_init, CHECK_NULL);

    // Load/link/initialize the klasses of the objects in the subgraph.
    // NULL class loader is used.
    Array<Klass*>* klasses = record->subgraph_object_klasses();
    if (klasses != NULL) {
      for (int i = 0; i < klasses->length(); i++) {
        Klass* klass = klasses->at(i);
        if (!klass->is_shared()) {
          return NULL;
        }
        resolve_or_init(klass, do_init, CHECK_NULL);
      }
    }
  }

  return record;
}

void HeapShared::resolve_or_init(Klass* k, bool do_init, TRAPS) {
  if (!do_init) {
    if (k->class_loader_data() == NULL) {
      Klass* resolved_k = SystemDictionary::resolve_or_null(k->name(), CHECK);
      assert(resolved_k == k, "classes used by archived heap must not be replaced by JVMTI ClassFileLoadHook");
    }
  } else {
    assert(k->class_loader_data() != NULL, "must have been resolved by HeapShared::resolve_classes");
    if (k->is_instance_klass()) {
      InstanceKlass* ik = InstanceKlass::cast(k);
      ik->initialize(CHECK);
    } else if (k->is_objArray_klass()) {
      ObjArrayKlass* oak = ObjArrayKlass::cast(k);
      oak->initialize(CHECK);
    }
  }
}

void HeapShared::init_archived_fields_for(Klass* k, const ArchivedKlassSubGraphInfoRecord* record) {
  verify_the_heap(k, "before");

  // Load the subgraph entry fields from the record and store them back to
  // the corresponding fields within the mirror.
  oop m = k->java_mirror();
  Array<int>* entry_field_records = record->entry_field_records();
  if (entry_field_records != NULL) {
    int efr_len = entry_field_records->length();
    assert(efr_len % 2 == 0, "sanity");
    for (int i = 0; i < efr_len; i += 2) {
      int field_offset = entry_field_records->at(i);
      int root_index = entry_field_records->at(i+1);
      oop v = get_root(root_index, /*clear=*/true);
      m->obj_field_put(field_offset, v);
      log_debug(cds, heap)("  " PTR_FORMAT " init field @ %2d = " PTR_FORMAT, p2i(k), field_offset, p2i(v));
    }

    // Done. Java code can see the archived sub-graphs referenced from k's
    // mirror after this point.
    if (log_is_enabled(Info, cds, heap)) {
      ResourceMark rm;
      log_info(cds, heap)("initialize_from_archived_subgraph %s " PTR_FORMAT "%s",
                          k->external_name(), p2i(k), JvmtiExport::is_early_phase() ? " (early)" : "");
    }
  }

  verify_the_heap(k, "after ");
}

void HeapShared::clear_archived_roots_of(Klass* k) {
  unsigned int hash = SystemDictionaryShared::hash_for_shared_dictionary_quick(k);
  const ArchivedKlassSubGraphInfoRecord* record = _run_time_subgraph_info_table.lookup(k, hash, 0);
  if (record != NULL) {
    Array<int>* entry_field_records = record->entry_field_records();
    if (entry_field_records != NULL) {
      int efr_len = entry_field_records->length();
      assert(efr_len % 2 == 0, "sanity");
      for (int i = 0; i < efr_len; i += 2) {
        int root_index = entry_field_records->at(i+1);
        clear_root(root_index);
      }
    }
  }
}

class WalkOopAndArchiveClosure: public BasicOopIterateClosure {
  int _level;
  bool _is_closed_archive;
  bool _record_klasses_only;
  KlassSubGraphInfo* _subgraph_info;
  oop _orig_referencing_obj;
  oop _archived_referencing_obj;

  // The following are for maintaining a stack for determining
  // CachedOopInfo::_referrer
  static WalkOopAndArchiveClosure* _current;
  WalkOopAndArchiveClosure* _last;
 public:
  WalkOopAndArchiveClosure(int level,
                           bool is_closed_archive,
                           bool record_klasses_only,
                           KlassSubGraphInfo* subgraph_info,
                           oop orig, oop archived) :
    _level(level), _is_closed_archive(is_closed_archive),
    _record_klasses_only(record_klasses_only),
    _subgraph_info(subgraph_info),
    _orig_referencing_obj(orig), _archived_referencing_obj(archived) {
    _last = _current;
    _current = this;
  }
  ~WalkOopAndArchiveClosure() {
    _current = _last;
  }
  void do_oop(narrowOop *p) { WalkOopAndArchiveClosure::do_oop_work(p); }
  void do_oop(      oop *p) { WalkOopAndArchiveClosure::do_oop_work(p); }

 protected:
  template <class T> void do_oop_work(T *p) {
    oop obj = RawAccess<>::oop_load(p);
    if (!CompressedOops::is_null(obj)) {
      assert(!HeapShared::is_archived_object_during_dumptime(obj),
             "original objects must not point to archived objects");

      size_t field_delta = pointer_delta(p, _orig_referencing_obj, sizeof(char));
      T* new_p = (T*)(cast_from_oop<address>(_archived_referencing_obj) + field_delta);

      if (!_record_klasses_only && log_is_enabled(Debug, cds, heap)) {
        ResourceMark rm;
        log_debug(cds, heap)("(%d) %s[" SIZE_FORMAT "] ==> " PTR_FORMAT " size " SIZE_FORMAT " %s", _level,
                             _orig_referencing_obj->klass()->external_name(), field_delta,
                             p2i(obj), obj->size() * HeapWordSize, obj->klass()->external_name());
        LogTarget(Trace, cds, heap) log;
        LogStream out(log);
        obj->print_on(&out);
      }

      oop archived = HeapShared::archive_reachable_objects_from(
          _level + 1, _subgraph_info, obj, _is_closed_archive);
      assert(archived != NULL, "VM should have exited with unarchivable objects for _level > 1");
      assert(HeapShared::is_archived_object_during_dumptime(archived), "must be");

      if (!_record_klasses_only) {
        // Update the reference in the archived copy of the referencing object.
        log_debug(cds, heap)("(%d) updating oop @[" PTR_FORMAT "] " PTR_FORMAT " ==> " PTR_FORMAT,
                             _level, p2i(new_p), p2i(obj), p2i(archived));
        RawAccess<IS_NOT_NULL>::oop_store(new_p, archived);
      }
    }
  }

 public:
  static WalkOopAndArchiveClosure* current()  { return _current;              }
  oop orig_referencing_obj()                  { return _orig_referencing_obj; }
  KlassSubGraphInfo* subgraph_info()          { return _subgraph_info;        }
};

WalkOopAndArchiveClosure* WalkOopAndArchiveClosure::_current = NULL;

HeapShared::CachedOopInfo HeapShared::make_cached_oop_info(oop orig_obj) {
  CachedOopInfo info;
  WalkOopAndArchiveClosure* walker = WalkOopAndArchiveClosure::current();

  info._subgraph_info = (walker == NULL) ? NULL : walker->subgraph_info();
  info._referrer = (walker == NULL) ? NULL : walker->orig_referencing_obj();
  info._obj = orig_obj;

  return info;
}

void HeapShared::check_closed_region_object(InstanceKlass* k) {
  // Check fields in the object
  for (JavaFieldStream fs(k); !fs.done(); fs.next()) {
    if (!fs.access_flags().is_static()) {
      BasicType ft = fs.field_descriptor().field_type();
      if (!fs.access_flags().is_final() && is_reference_type(ft)) {
        ResourceMark rm;
        log_warning(cds, heap)(
          "Please check reference field in %s instance in closed archive heap region: %s %s",
          k->external_name(), (fs.name())->as_C_string(),
          (fs.signature())->as_C_string());
      }
    }
  }
}

void HeapShared::check_module_oop(oop orig_module_obj) {
  assert(DumpSharedSpaces, "must be");
  assert(java_lang_Module::is_instance(orig_module_obj), "must be");
  ModuleEntry* orig_module_ent = java_lang_Module::module_entry_raw(orig_module_obj);
  if (orig_module_ent == NULL) {
    // These special Module objects are created in Java code. They are not
    // defined via Modules::define_module(), so they don't have a ModuleEntry:
    //     java.lang.Module::ALL_UNNAMED_MODULE
    //     java.lang.Module::EVERYONE_MODULE
    //     jdk.internal.loader.ClassLoaders$BootClassLoader::unnamedModule
    assert(java_lang_Module::name(orig_module_obj) == NULL, "must be unnamed");
    log_info(cds, heap)("Module oop with No ModuleEntry* @[" PTR_FORMAT "]", p2i(orig_module_obj));
  } else {
    ClassLoaderData* loader_data = orig_module_ent->loader_data();
    assert(loader_data->is_builtin_class_loader_data(), "must be");
  }
}


// (1) If orig_obj has not been archived yet, archive it.
// (2) If orig_obj has not been seen yet (since start_recording_subgraph() was called),
//     trace all  objects that are reachable from it, and make sure these objects are archived.
// (3) Record the klasses of all orig_obj and all reachable objects.
oop HeapShared::archive_reachable_objects_from(int level,
                                               KlassSubGraphInfo* subgraph_info,
                                               oop orig_obj,
                                               bool is_closed_archive) {
  assert(orig_obj != NULL, "must be");
  assert(!is_archived_object_during_dumptime(orig_obj), "sanity");

  if (!JavaClasses::is_supported_for_archiving(orig_obj)) {
    // This object has injected fields that cannot be supported easily, so we disallow them for now.
    // If you get an error here, you probably made a change in the JDK library that has added
    // these objects that are referenced (directly or indirectly) by static fields.
    ResourceMark rm;
    log_error(cds, heap)("Cannot archive object of class %s", orig_obj->klass()->external_name());
    os::_exit(1);
  }

  // java.lang.Class instances cannot be included in an archived object sub-graph. We only support
  // them as Klass::_archived_mirror because they need to be specially restored at run time.
  //
  // If you get an error here, you probably made a change in the JDK library that has added a Class
  // object that is referenced (directly or indirectly) by static fields.
  if (java_lang_Class::is_instance(orig_obj)) {
    log_error(cds, heap)("(%d) Unknown java.lang.Class object is in the archived sub-graph", level);
    os::_exit(1);
  }

  oop archived_obj = find_archived_heap_object(orig_obj);
  if (java_lang_String::is_instance(orig_obj) && archived_obj != NULL) {
    // To save time, don't walk strings that are already archived. They just contain
    // pointers to a type array, whose klass doesn't need to be recorded.
    return archived_obj;
  }

  if (has_been_seen_during_subgraph_recording(orig_obj)) {
    // orig_obj has already been archived and traced. Nothing more to do.
    return archived_obj;
  } else {
    set_has_been_seen_during_subgraph_recording(orig_obj);
  }

  bool record_klasses_only = (archived_obj != NULL);
  if (archived_obj == NULL) {
    ++_num_new_archived_objs;
    archived_obj = archive_object(orig_obj);
    if (archived_obj == NULL) {
      // Skip archiving the sub-graph referenced from the current entry field.
      ResourceMark rm;
      log_error(cds, heap)(
        "Cannot archive the sub-graph referenced from %s object ("
        PTR_FORMAT ") size " SIZE_FORMAT ", skipped.",
        orig_obj->klass()->external_name(), p2i(orig_obj), orig_obj->size() * HeapWordSize);
      if (level == 1) {
        // Don't archive a subgraph root that's too big. For archives static fields, that's OK
        // as the Java code will take care of initializing this field dynamically.
        return NULL;
      } else {
        // We don't know how to handle an object that has been archived, but some of its reachable
        // objects cannot be archived. Bail out for now. We might need to fix this in the future if
        // we have a real use case.
        os::_exit(1);
      }
    }

    if (java_lang_Module::is_instance(orig_obj)) {
      check_module_oop(orig_obj);
      java_lang_Module::set_module_entry(archived_obj, NULL);
      java_lang_Module::set_loader(archived_obj, NULL);
    } else if (java_lang_ClassLoader::is_instance(orig_obj)) {
      // class_data will be restored explicitly at run time.
      guarantee(orig_obj == SystemDictionary::java_platform_loader() ||
                orig_obj == SystemDictionary::java_system_loader() ||
                java_lang_ClassLoader::loader_data(orig_obj) == NULL, "must be");
      java_lang_ClassLoader::release_set_loader_data(archived_obj, NULL);
    }
  }

  assert(archived_obj != NULL, "must be");
  Klass *orig_k = orig_obj->klass();
  subgraph_info->add_subgraph_object_klass(orig_k);

  WalkOopAndArchiveClosure walker(level, is_closed_archive, record_klasses_only,
                                  subgraph_info, orig_obj, archived_obj);
  orig_obj->oop_iterate(&walker);
  if (is_closed_archive && orig_k->is_instance_klass()) {
    check_closed_region_object(InstanceKlass::cast(orig_k));
  }

  check_enum_obj(level + 1, subgraph_info, orig_obj, is_closed_archive);
  return archived_obj;
}

//
// Start from the given static field in a java mirror and archive the
// complete sub-graph of java heap objects that are reached directly
// or indirectly from the starting object by following references.
// Sub-graph archiving restrictions (current):
//
// - All classes of objects in the archived sub-graph (including the
//   entry class) must be boot class only.
// - No java.lang.Class instance (java mirror) can be included inside
//   an archived sub-graph. Mirror can only be the sub-graph entry object.
//
// The Java heap object sub-graph archiving process (see
// WalkOopAndArchiveClosure):
//
// 1) Java object sub-graph archiving starts from a given static field
// within a Class instance (java mirror). If the static field is a
// reference field and points to a non-null java object, proceed to
// the next step.
//
// 2) Archives the referenced java object. If an archived copy of the
// current object already exists, updates the pointer in the archived
// copy of the referencing object to point to the current archived object.
// Otherwise, proceed to the next step.
//
// 3) Follows all references within the current java object and recursively
// archive the sub-graph of objects starting from each reference.
//
// 4) Updates the pointer in the archived copy of referencing object to
// point to the current archived object.
//
// 5) The Klass of the current java object is added to the list of Klasses
// for loading and initializing before any object in the archived graph can
// be accessed at runtime.
//
void HeapShared::archive_reachable_objects_from_static_field(InstanceKlass *k,
                                                             const char* klass_name,
                                                             int field_offset,
                                                             const char* field_name,
                                                             bool is_closed_archive) {
  assert(DumpSharedSpaces, "dump time only");
  assert(k->is_shared_boot_class(), "must be boot class");

  oop m = k->java_mirror();

  KlassSubGraphInfo* subgraph_info = get_subgraph_info(k);
  oop f = m->obj_field(field_offset);

  log_debug(cds, heap)("Start archiving from: %s::%s (" PTR_FORMAT ")", klass_name, field_name, p2i(f));

  if (!CompressedOops::is_null(f)) {
    if (log_is_enabled(Trace, cds, heap)) {
      LogTarget(Trace, cds, heap) log;
      LogStream out(log);
      f->print_on(&out);
    }

    oop af = archive_reachable_objects_from(1, subgraph_info, f, is_closed_archive);

    if (af == NULL) {
      log_error(cds, heap)("Archiving failed %s::%s (some reachable objects cannot be archived)",
                           klass_name, field_name);
    } else {
      // Note: the field value is not preserved in the archived mirror.
      // Record the field as a new subGraph entry point. The recorded
      // information is restored from the archive at runtime.
      subgraph_info->add_subgraph_entry_field(field_offset, af, is_closed_archive);
      log_info(cds, heap)("Archived field %s::%s => " PTR_FORMAT, klass_name, field_name, p2i(af));
    }
  } else {
    // The field contains null, we still need to record the entry point,
    // so it can be restored at runtime.
    subgraph_info->add_subgraph_entry_field(field_offset, NULL, false);
  }
}

#ifndef PRODUCT
class VerifySharedOopClosure: public BasicOopIterateClosure {
 private:
  bool _is_archived;

 public:
  VerifySharedOopClosure(bool is_archived) : _is_archived(is_archived) {}

  void do_oop(narrowOop *p) { VerifySharedOopClosure::do_oop_work(p); }
  void do_oop(      oop *p) { VerifySharedOopClosure::do_oop_work(p); }

 protected:
  template <class T> void do_oop_work(T *p) {
    oop obj = RawAccess<>::oop_load(p);
    if (!CompressedOops::is_null(obj)) {
      HeapShared::verify_reachable_objects_from(obj, _is_archived);
    }
  }
};

void HeapShared::verify_subgraph_from_static_field(InstanceKlass* k, int field_offset) {
  assert(DumpSharedSpaces, "dump time only");
  assert(k->is_shared_boot_class(), "must be boot class");

  oop m = k->java_mirror();
  oop f = m->obj_field(field_offset);
  if (!CompressedOops::is_null(f)) {
    verify_subgraph_from(f);
  }
}

void HeapShared::verify_subgraph_from(oop orig_obj) {
  oop archived_obj = find_archived_heap_object(orig_obj);
  if (archived_obj == NULL) {
    // It's OK for the root of a subgraph to be not archived. See comments in
    // archive_reachable_objects_from().
    return;
  }

  // Verify that all objects reachable from orig_obj are archived.
  init_seen_objects_table();
  verify_reachable_objects_from(orig_obj, false);
  delete_seen_objects_table();

  // Note: we could also verify that all objects reachable from the archived
  // copy of orig_obj can only point to archived objects, with:
  //      init_seen_objects_table();
  //      verify_reachable_objects_from(archived_obj, true);
  //      init_seen_objects_table();
  // but that's already done in G1HeapVerifier::verify_archive_regions so we
  // won't do it here.
}

void HeapShared::verify_reachable_objects_from(oop obj, bool is_archived) {
  _num_total_verifications ++;
  if (!has_been_seen_during_subgraph_recording(obj)) {
    set_has_been_seen_during_subgraph_recording(obj);

    if (is_archived) {
      assert(is_archived_object_during_dumptime(obj), "must be");
      assert(find_archived_heap_object(obj) == NULL, "must be");
    } else {
      assert(!is_archived_object_during_dumptime(obj), "must be");
      assert(find_archived_heap_object(obj) != NULL, "must be");
    }

    VerifySharedOopClosure walker(is_archived);
    obj->oop_iterate(&walker);
  }
}
#endif

HeapShared::SeenObjectsTable* HeapShared::_seen_objects_table = NULL;
int HeapShared::_num_new_walked_objs;
int HeapShared::_num_new_archived_objs;
int HeapShared::_num_old_recorded_klasses;

int HeapShared::_num_total_subgraph_recordings = 0;
int HeapShared::_num_total_walked_objs = 0;
int HeapShared::_num_total_archived_objs = 0;
int HeapShared::_num_total_recorded_klasses = 0;
int HeapShared::_num_total_verifications = 0;

bool HeapShared::has_been_seen_during_subgraph_recording(oop obj) {
  return _seen_objects_table->get(obj) != NULL;
}

void HeapShared::set_has_been_seen_during_subgraph_recording(oop obj) {
  assert(!has_been_seen_during_subgraph_recording(obj), "sanity");
  _seen_objects_table->put(obj, true);
  ++ _num_new_walked_objs;
}

void HeapShared::start_recording_subgraph(InstanceKlass *k, const char* class_name, bool is_full_module_graph) {
  log_info(cds, heap)("Start recording subgraph(s) for archived fields in %s", class_name);
  init_subgraph_info(k, is_full_module_graph);
  init_seen_objects_table();
  _num_new_walked_objs = 0;
  _num_new_archived_objs = 0;
  _num_old_recorded_klasses = get_subgraph_info(k)->num_subgraph_object_klasses();
}

void HeapShared::done_recording_subgraph(InstanceKlass *k, const char* class_name) {
  int num_new_recorded_klasses = get_subgraph_info(k)->num_subgraph_object_klasses() -
    _num_old_recorded_klasses;
  log_info(cds, heap)("Done recording subgraph(s) for archived fields in %s: "
                      "walked %d objs, archived %d new objs, recorded %d classes",
                      class_name, _num_new_walked_objs, _num_new_archived_objs,
                      num_new_recorded_klasses);

  delete_seen_objects_table();

  _num_total_subgraph_recordings ++;
  _num_total_walked_objs      += _num_new_walked_objs;
  _num_total_archived_objs    += _num_new_archived_objs;
  _num_total_recorded_klasses +=  num_new_recorded_klasses;
}

class ArchivableStaticFieldFinder: public FieldClosure {
  InstanceKlass* _ik;
  Symbol* _field_name;
  bool _found;
  int _offset;
public:
  ArchivableStaticFieldFinder(InstanceKlass* ik, Symbol* field_name) :
    _ik(ik), _field_name(field_name), _found(false), _offset(-1) {}

  virtual void do_field(fieldDescriptor* fd) {
    if (fd->name() == _field_name) {
      assert(!_found, "fields cannot be overloaded");
      assert(is_reference_type(fd->field_type()), "can archive only fields that are references");
      _found = true;
      _offset = fd->offset();
    }
  }
  bool found()     { return _found;  }
  int offset()     { return _offset; }
};

void HeapShared::init_subgraph_entry_fields(ArchivableStaticFieldInfo fields[],
                                            int num, TRAPS) {
  for (int i = 0; i < num; i++) {
    ArchivableStaticFieldInfo* info = &fields[i];
    TempNewSymbol klass_name =  SymbolTable::new_symbol(info->klass_name);
    TempNewSymbol field_name =  SymbolTable::new_symbol(info->field_name);

    Klass* k = SystemDictionary::resolve_or_fail(klass_name, true, CHECK);
    InstanceKlass* ik = InstanceKlass::cast(k);
    assert(InstanceKlass::cast(ik)->is_shared_boot_class(),
           "Only support boot classes");
    ik->initialize(CHECK);

    ArchivableStaticFieldFinder finder(ik, field_name);
    ik->do_local_static_fields(&finder);
    assert(finder.found(), "field must exist");

    info->klass = ik;
    info->offset = finder.offset();
  }
}

void HeapShared::init_subgraph_entry_fields(TRAPS) {
  assert(HeapShared::can_write(), "must be");
  _dump_time_subgraph_info_table = new (ResourceObj::C_HEAP, mtClass)DumpTimeKlassSubGraphInfoTable();
  init_subgraph_entry_fields(closed_archive_subgraph_entry_fields,
                             num_closed_archive_subgraph_entry_fields,
                             CHECK);
  init_subgraph_entry_fields(open_archive_subgraph_entry_fields,
                             num_open_archive_subgraph_entry_fields,
                             CHECK);
  if (MetaspaceShared::use_full_module_graph()) {
    init_subgraph_entry_fields(fmg_open_archive_subgraph_entry_fields,
                               num_fmg_open_archive_subgraph_entry_fields,
                               CHECK);
  }
}

void HeapShared::init_for_dumping(TRAPS) {
  if (HeapShared::can_write()) {
    _dumped_interned_strings = new (ResourceObj::C_HEAP, mtClass)DumpedInternedStrings();
    init_subgraph_entry_fields(CHECK);
  }
}

void HeapShared::archive_object_subgraphs(ArchivableStaticFieldInfo fields[],
                                          int num, bool is_closed_archive,
                                          bool is_full_module_graph) {
  _num_total_subgraph_recordings = 0;
  _num_total_walked_objs = 0;
  _num_total_archived_objs = 0;
  _num_total_recorded_klasses = 0;
  _num_total_verifications = 0;

  // For each class X that has one or more archived fields:
  // [1] Dump the subgraph of each archived field
  // [2] Create a list of all the class of the objects that can be reached
  //     by any of these static fields.
  //     At runtime, these classes are initialized before X's archived fields
  //     are restored by HeapShared::initialize_from_archived_subgraph().
  int i;
  for (i = 0; i < num; ) {
    ArchivableStaticFieldInfo* info = &fields[i];
    const char* klass_name = info->klass_name;
    start_recording_subgraph(info->klass, klass_name, is_full_module_graph);

    // If you have specified consecutive fields of the same klass in
    // fields[], these will be archived in the same
    // {start_recording_subgraph ... done_recording_subgraph} pass to
    // save time.
    for (; i < num; i++) {
      ArchivableStaticFieldInfo* f = &fields[i];
      if (f->klass_name != klass_name) {
        break;
      }

      archive_reachable_objects_from_static_field(f->klass, f->klass_name,
                                                  f->offset, f->field_name,
                                                  is_closed_archive);
    }
    done_recording_subgraph(info->klass, klass_name);
  }

  log_info(cds, heap)("Archived subgraph records in %s archive heap region = %d",
                      is_closed_archive ? "closed" : "open",
                      _num_total_subgraph_recordings);
  log_info(cds, heap)("  Walked %d objects", _num_total_walked_objs);
  log_info(cds, heap)("  Archived %d objects", _num_total_archived_objs);
  log_info(cds, heap)("  Recorded %d klasses", _num_total_recorded_klasses);

#ifndef PRODUCT
  for (int i = 0; i < num; i++) {
    ArchivableStaticFieldInfo* f = &fields[i];
    verify_subgraph_from_static_field(f->klass, f->offset);
  }
  log_info(cds, heap)("  Verified %d references", _num_total_verifications);
#endif
}

// Not all the strings in the global StringTable are dumped into the archive, because
// some of those strings may be only referenced by classes that are excluded from
// the archive. We need to explicitly mark the strings that are:
//   [1] used by classes that WILL be archived;
//   [2] included in the SharedArchiveConfigFile.
void HeapShared::add_to_dumped_interned_strings(oop string) {
  assert_at_safepoint(); // DumpedInternedStrings uses raw oops
  bool created;
  _dumped_interned_strings->put_if_absent(string, true, &created);
}

// At dump-time, find the location of all the non-null oop pointers in an archived heap
// region. This way we can quickly relocate all the pointers without using
// BasicOopIterateClosure at runtime.
class FindEmbeddedNonNullPointers: public BasicOopIterateClosure {
  void* _start;
  BitMap *_oopmap;
  int _num_total_oops;
  int _num_null_oops;
 public:
  FindEmbeddedNonNullPointers(void* start, BitMap* oopmap)
    : _start(start), _oopmap(oopmap), _num_total_oops(0),  _num_null_oops(0) {}

  virtual void do_oop(narrowOop* p) {
    assert(UseCompressedOops, "sanity");
    _num_total_oops ++;
    narrowOop v = *p;
    if (!CompressedOops::is_null(v)) {
      // Note: HeapShared::to_requested_address() is not necessary because
      // the heap always starts at a deterministic address with UseCompressedOops==true.
      size_t idx = p - (narrowOop*)_start;
      _oopmap->set_bit(idx);
    } else {
      _num_null_oops ++;
    }
  }
  virtual void do_oop(oop* p) {
    assert(!UseCompressedOops, "sanity");
    _num_total_oops ++;
    if ((*p) != NULL) {
      size_t idx = p - (oop*)_start;
      _oopmap->set_bit(idx);
      if (DumpSharedSpaces) {
        // Make heap content deterministic.
        *p = HeapShared::to_requested_address(*p);
      }
    } else {
      _num_null_oops ++;
    }
  }
  int num_total_oops() const { return _num_total_oops; }
  int num_null_oops()  const { return _num_null_oops; }
};


address HeapShared::to_requested_address(address dumptime_addr) {
  assert(DumpSharedSpaces, "static dump time only");
  if (dumptime_addr == NULL || UseCompressedOops) {
    return dumptime_addr;
  }

  // With UseCompressedOops==false, actual_base is selected by the OS so
  // it's different across -Xshare:dump runs.
  address actual_base = (address)G1CollectedHeap::heap()->reserved().start();
  address actual_end  = (address)G1CollectedHeap::heap()->reserved().end();
  assert(actual_base <= dumptime_addr && dumptime_addr <= actual_end, "must be an address in the heap");

  // We always write the objects as if the heap started at this address. This
  // makes the heap content deterministic.
  //
  // Note that at runtime, the heap address is also selected by the OS, so
  // the archive heap will not be mapped at 0x10000000. Instead, we will call
  // HeapShared::patch_embedded_pointers() to relocate the heap contents
  // accordingly.
  const address REQUESTED_BASE = (address)0x10000000;
  intx delta = REQUESTED_BASE - actual_base;

  address requested_addr = dumptime_addr + delta;
  assert(REQUESTED_BASE != 0 && requested_addr != NULL, "sanity");
  return requested_addr;
}

ResourceBitMap HeapShared::calculate_oopmap(MemRegion region) {
  size_t num_bits = region.byte_size() / (UseCompressedOops ? sizeof(narrowOop) : sizeof(oop));
  ResourceBitMap oopmap(num_bits);

  HeapWord* p   = region.start();
  HeapWord* end = region.end();
  FindEmbeddedNonNullPointers finder((void*)p, &oopmap);
  ArchiveBuilder* builder = DumpSharedSpaces ? ArchiveBuilder::current() : NULL;

  int num_objs = 0;
  while (p < end) {
    oop o = cast_to_oop(p);
    o->oop_iterate(&finder);
    p += o->size();
    if (DumpSharedSpaces) {
      builder->relocate_klass_ptr(o);
    }
    ++ num_objs;
  }

  log_info(cds, heap)("calculate_oopmap: objects = %6d, oop fields = %7d (nulls = %7d)",
                      num_objs, finder.num_total_oops(), finder.num_null_oops());
  return oopmap;
}

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
    oop o = HeapShared::decode_from_archive(v);
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
    intptr_t runtime_oop = dumptime_oop + HeapShared::runtime_delta();
    RawAccess<IS_NOT_NULL>::oop_store(p, cast_to_oop(runtime_oop));
    return true;
  }
};

// Patch all the non-null pointers that are embedded in the archived heap objects
// in this region
void HeapShared::patch_embedded_pointers(MemRegion region, address oopmap,
                                         size_t oopmap_size_in_bits) {
  BitMapView bm((BitMap::bm_word_t*)oopmap, oopmap_size_in_bits);

#ifndef PRODUCT
  ResourceMark rm;
  ResourceBitMap checkBm = calculate_oopmap(region);
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

void HeapShared::init_loaded_heap_relocation(LoadedArchiveHeapRegion* loaded_regions,
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

bool HeapShared::can_load() {
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
    uintptr_t o = cast_from_oop<uintptr_t>(HeapShared::decode_from_archive(v));
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
    HeapShared::assert_in_loaded_heap(o);
    RawAccess<IS_NOT_NULL>::oop_store(p, cast_to_oop(o));
    return true;
  }
};

int HeapShared::init_loaded_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
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

void HeapShared::sort_loaded_regions(LoadedArchiveHeapRegion* loaded_regions, int num_loaded_regions,
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

bool HeapShared::load_regions(FileMapInfo* mapinfo, LoadedArchiveHeapRegion* loaded_regions,
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

bool HeapShared::load_heap_regions(FileMapInfo* mapinfo) {
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
      HeapShared::assert_in_loaded_heap(u);
      guarantee(_table->contains(u), "must point to beginning of object in loaded archived regions");
    }
  }
  virtual void do_oop(oop* p) {
    ShouldNotReachHere();
  }
};

void HeapShared::finish_initialization() {
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

void HeapShared::fill_failed_loaded_region() {
  assert(_loading_failed, "must be");
  if (_loaded_heap_bottom != 0) {
    assert(_loaded_heap_top != 0, "must be");
    HeapWord* bottom = (HeapWord*)_loaded_heap_bottom;
    HeapWord* top = (HeapWord*)_loaded_heap_top;
    Universe::heap()->fill_with_objects(bottom, top - bottom);
  }
}

#endif // INCLUDE_CDS_JAVA_HEAP

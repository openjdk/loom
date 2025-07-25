/*
 * Copyright (c) 2016, 2025, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_CLASSFILE_MODULEENTRY_HPP
#define SHARE_CLASSFILE_MODULEENTRY_HPP

#include "jni.h"
#include "oops/oopHandle.hpp"
#include "oops/symbol.hpp"
#include "oops/symbolHandle.hpp"
#include "runtime/mutexLocker.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/macros.hpp"
#include "utilities/ostream.hpp"
#include "utilities/resourceHash.hpp"
#if INCLUDE_JFR
#include "jfr/support/jfrTraceIdExtension.hpp"
#endif

#define UNNAMED_MODULE "unnamed module"
#define UNNAMED_MODULE_LEN 14
#define JAVAPKG "java"
#define JAVAPKG_LEN 4
#define JAVA_BASE_NAME "java.base"
#define JAVA_BASE_NAME_LEN 9

template <class T> class Array;
class ClassLoaderData;
class MetaspaceClosure;
class ModuleClosure;

// A ModuleEntry describes a module that has been defined by a call to JVM_DefineModule.
// It contains:
//   - Symbol* containing the module's name.
//   - pointer to the java.lang.Module: the representation of this module as a Java object
//   - pointer to the java.security.ProtectionDomain shared by classes defined to this module.
//   - ClassLoaderData*, class loader of this module.
//   - a growable array containing other module entries that this module can read.
//   - a flag indicating if this module can read all unnamed modules.
//
// The Mutex Module_lock is shared between ModuleEntry and PackageEntry, to lock either
// data structure.  This lock must be taken on all accesses to either table.
class ModuleEntry : public CHeapObj<mtModule> {
private:
  OopHandle _module_handle;            // java.lang.Module
  OopHandle _shared_pd;                // java.security.ProtectionDomain, cached
                                       // for shared classes from this module
  Symbol*          _name;              // name of this module
  ClassLoaderData* _loader_data;

  union {
    GrowableArray<ModuleEntry*>* _reads;  // list of modules that are readable by this module
    Array<ModuleEntry*>* _archived_reads; // List of readable modules stored in the CDS archive
  };
  Symbol* _version;                    // module version number
  Symbol* _location;                   // module location
  CDS_ONLY(int _shared_path_index;)    // >=0 if classes in this module are in CDS archive
  bool _can_read_all_unnamed;
  bool _has_default_read_edges;        // JVMTI redefine/retransform support
  bool _must_walk_reads;               // walk module's reads list at GC safepoints to purge out dead modules
  bool _is_open;                       // whether the packages in the module are all unqualifiedly exported
  bool _is_patched;                    // whether the module is patched via --patch-module
  DEBUG_ONLY(bool _reads_is_archived);
  CDS_JAVA_HEAP_ONLY(int _archived_module_index;)

  JFR_ONLY(DEFINE_TRACE_ID_FIELD;)
  enum {MODULE_READS_SIZE = 101};      // Initial size of list of modules that the module can read.

public:
  ModuleEntry(Handle module_handle,
              bool is_open, Symbol* name,
              Symbol* version, Symbol* location,
              ClassLoaderData* loader_data);

  ~ModuleEntry();

  Symbol*          name() const                        { return _name; }
  oop              module_oop() const;
  OopHandle        module_handle() const               { return _module_handle; }
  void             set_module_handle(OopHandle j)      { _module_handle = j; }

  // The shared ProtectionDomain reference is set once the VM loads a shared class
  // originated from the current Module. The referenced ProtectionDomain object is
  // created by the ClassLoader when loading a class (shared or non-shared) from the
  // Module for the first time. This ProtectionDomain object is used for all
  // classes from the Module loaded by the same ClassLoader.
  oop              shared_protection_domain();
  void             set_shared_protection_domain(ClassLoaderData *loader_data, Handle pd);

  ClassLoaderData* loader_data() const                 { return _loader_data; }
  void set_loader_data(ClassLoaderData* cld);

  Symbol*          version() const                     { return _version; }
  void             set_version(Symbol* version);

  Symbol*          location() const                    { return _location; }
  void             set_location(Symbol* location);
  bool             should_show_version();

  bool             can_read(ModuleEntry* m) const;
  bool             has_reads_list() const;
  GrowableArray<ModuleEntry*>* reads() const {
    assert(!_reads_is_archived, "sanity");
    return _reads;
  }
  void set_reads(GrowableArray<ModuleEntry*>* r) {
    _reads = r;
    DEBUG_ONLY(_reads_is_archived = false);
  }
  Array<ModuleEntry*>* archived_reads() const {
    assert(_reads_is_archived, "sanity");
    return _archived_reads;
  }
  void set_archived_reads(Array<ModuleEntry*>* r) {
    _archived_reads = r;
    DEBUG_ONLY(_reads_is_archived = true);
  }
  void             add_read(ModuleEntry* m);
  void             set_read_walk_required(ClassLoaderData* m_loader_data);

  bool             is_open() const                     { return _is_open; }
  void             set_is_open(bool is_open);

  bool             is_named() const                    { return (_name != nullptr); }

  bool can_read_all_unnamed() const {
    assert(is_named() || _can_read_all_unnamed == true,
           "unnamed modules can always read all unnamed modules");
    return _can_read_all_unnamed;
  }

  // Modules can only go from strict to loose.
  void set_can_read_all_unnamed() { _can_read_all_unnamed = true; }

  bool has_default_read_edges() const {
    return _has_default_read_edges;
  }

  // Sets true and returns the previous value.
  bool set_has_default_read_edges() {
    MutexLocker ml(Module_lock);
    bool prev = _has_default_read_edges;
    _has_default_read_edges = true;
    return prev;
  }

  void set_is_patched() {
      _is_patched = true;
      CDS_ONLY(_shared_path_index = -1); // Mark all shared classes in this module invisible.
  }
  bool is_patched() {
      return _is_patched;
  }

  // iteration support for readability
  void module_reads_do(ModuleClosure* const f);

  // Purge dead weak references out of reads list when any given class loader is unloaded.
  void purge_reads();
  void delete_reads();

  // Special handling for unnamed module, one per class loader
  static ModuleEntry* create_unnamed_module(ClassLoaderData* cld);
  static ModuleEntry* create_boot_unnamed_module(ClassLoaderData* cld);
  static ModuleEntry* new_unnamed_module_entry(Handle module_handle, ClassLoaderData* cld);

  // Note caller requires ResourceMark
  const char* name_as_C_string() const {
    return is_named() ? name()->as_C_string() : UNNAMED_MODULE;
  }
  void print(outputStream* st = tty) const;
  void verify();

  CDS_ONLY(int shared_path_index() { return _shared_path_index;})

  JFR_ONLY(DEFINE_TRACE_ID_METHODS;)

#if INCLUDE_CDS_JAVA_HEAP
  bool should_be_archived() const;
  void iterate_symbols(MetaspaceClosure* closure);
  ModuleEntry* allocate_archived_entry() const;
  void init_as_archived_entry();
  static ModuleEntry* get_archived_entry(ModuleEntry* orig_entry);
  bool has_been_archived();
  static Array<ModuleEntry*>* write_growable_array(GrowableArray<ModuleEntry*>* array);
  static GrowableArray<ModuleEntry*>* restore_growable_array(Array<ModuleEntry*>* archived_array);
  void load_from_archive(ClassLoaderData* loader_data);
  void restore_archived_oops(ClassLoaderData* loader_data);
  void clear_archived_oops();
  static void verify_archived_module_entries() PRODUCT_RETURN;
#endif
};

// Iterator interface
class ModuleClosure: public StackObj {
 public:
  virtual void do_module(ModuleEntry* module) = 0;
};


// The ModuleEntryTable is a Hashtable containing a list of all modules defined
// by a particular class loader.  Each module is represented as a ModuleEntry node.
//
// Each ModuleEntryTable contains a _javabase_module field which allows for the
// creation of java.base's ModuleEntry very early in bootstrapping before the
// corresponding JVM_DefineModule call for java.base occurs during module system
// initialization.  Setting up java.base's ModuleEntry early enables classes,
// loaded prior to the module system being initialized to be created with their
// PackageEntry node's correctly pointing at java.base's ModuleEntry.  No class
// outside of java.base is allowed to be loaded pre-module system initialization.
//
class ModuleEntryTable : public CHeapObj<mtModule> {
private:
  static ModuleEntry* _javabase_module;
  ResourceHashtable<SymbolHandle, ModuleEntry*, 109, AnyObj::C_HEAP, mtModule,
                    SymbolHandle::compute_hash> _table;

public:
  ModuleEntryTable();
  ~ModuleEntryTable();

  // Create module in loader's module entry table.  Assume Module_lock
  // has been locked by caller.
  ModuleEntry* locked_create_entry(Handle module_handle,
                                   bool is_open,
                                   Symbol* module_name,
                                   Symbol* module_version,
                                   Symbol* module_location,
                                   ClassLoaderData* loader_data);

  // Only lookup module within loader's module entry table.
  ModuleEntry* lookup_only(Symbol* name);

  // purge dead weak references out of reads list
  void purge_all_module_reads();

  // Special handling for java.base
  static ModuleEntry* javabase_moduleEntry()                   { return _javabase_module; }
  static void set_javabase_moduleEntry(ModuleEntry* java_base) {
    assert(_javabase_module == nullptr, "_javabase_module is already defined");
    _javabase_module = java_base;
  }

  static bool javabase_defined() { return ((_javabase_module != nullptr) &&
                                           (_javabase_module->module_oop() != nullptr)); }
  static void finalize_javabase(Handle module_handle, Symbol* version, Symbol* location);
  static void patch_javabase_entries(JavaThread* current, Handle module_handle);

  void modules_do(void f(ModuleEntry*));
  void modules_do(ModuleClosure* closure);

  void print(outputStream* st = tty);
  void verify();

#if INCLUDE_CDS_JAVA_HEAP
  void iterate_symbols(MetaspaceClosure* closure);
  Array<ModuleEntry*>* allocate_archived_entries();
  void init_archived_entries(Array<ModuleEntry*>* archived_modules);
  void load_archived_entries(ClassLoaderData* loader_data,
                             Array<ModuleEntry*>* archived_modules);
  void restore_archived_oops(ClassLoaderData* loader_data,
                             Array<ModuleEntry*>* archived_modules);
#endif
};

#endif // SHARE_CLASSFILE_MODULEENTRY_HPP

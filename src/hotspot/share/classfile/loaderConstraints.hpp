/*
 * Copyright (c) 2003, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_CLASSFILE_LOADERCONSTRAINTS_HPP
#define SHARE_CLASSFILE_LOADERCONSTRAINTS_HPP

#include "oops/oopsHierarchy.hpp"
#include "runtime/handles.hpp"

class ClassLoaderData;
class LoaderConstraint;
class Symbol;

class LoaderConstraintTable : public AllStatic {

private:
  static LoaderConstraint* find_loader_constraint(Symbol* name, Handle loader);

  static void add_loader_constraint(Symbol* name, InstanceKlass* klass, oop class_loader1, oop class_loader2);

  static void merge_loader_constraints(Symbol* class_name, LoaderConstraint* pp1,
                                       LoaderConstraint* pp2, InstanceKlass* klass);
public:

  // Check class loader constraints
  static bool add_entry(Symbol* name, InstanceKlass* klass1, Handle loader1,
                        InstanceKlass* klass2, Handle loader2);

  // Note:  The main entry point for this module is via SystemDictionary.
  // SystemDictionary::check_signature_loaders(Symbol* signature,
  //                                           Klass* klass_being_linked,
  //                                           Handle loader1, Handle loader2,
  //                                           bool is_method)

  static InstanceKlass* find_constrained_klass(Symbol* name, Handle loader);

  // Class loader constraints
  static bool check_or_update(InstanceKlass* k, Handle loader, Symbol* name);

  static void purge_loader_constraints();

  static void print_table_statistics(outputStream* st);
  static void verify();
  static void print();
  static void print_on(outputStream* st);
};

#endif // SHARE_CLASSFILE_LOADERCONSTRAINTS_HPP

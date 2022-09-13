/*
 * Copyright (c) 1997, 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/vmSymbols.hpp"
#include "memory/resourceArea.hpp"
#include "oops/annotations.hpp"
#include "oops/constantPool.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/signature.hpp"


oop fieldDescriptor::loader() const {
  return _cp->pool_holder()->class_loader();
}

Symbol* fieldDescriptor::generic_signature() const {
  if (!has_generic_signature()) {
    return NULL;
  }

  int idx = 0;
  InstanceKlass* ik = field_holder();
  for (AllFieldStream fs(ik); !fs.done(); fs.next()) {
    if (idx == _index) {
      return fs.generic_signature();
    } else {
      idx ++;
    }
  }
  assert(false, "should never happen");
  return vmSymbols::void_signature(); // return a default value (for code analyzers)
}

bool fieldDescriptor::is_trusted_final() const {
  InstanceKlass* ik = field_holder();
  return is_final() && (is_static() || ik->is_hidden() || ik->is_record());
}

AnnotationArray* fieldDescriptor::annotations() const {
  InstanceKlass* ik = field_holder();
  Array<AnnotationArray*>* md = ik->fields_annotations();
  if (md == NULL)
    return NULL;
  return md->at(index());
}

AnnotationArray* fieldDescriptor::type_annotations() const {
  InstanceKlass* ik = field_holder();
  Array<AnnotationArray*>* type_annos = ik->fields_type_annotations();
  if (type_annos == NULL)
    return NULL;
  return type_annos->at(index());
}

constantTag fieldDescriptor::initial_value_tag() const {
  return constants()->tag_at(initial_value_index());
}

jint fieldDescriptor::int_initial_value() const {
  return constants()->int_at(initial_value_index());
}

jlong fieldDescriptor::long_initial_value() const {
  return constants()->long_at(initial_value_index());
}

jfloat fieldDescriptor::float_initial_value() const {
  return constants()->float_at(initial_value_index());
}

jdouble fieldDescriptor::double_initial_value() const {
  return constants()->double_at(initial_value_index());
}

oop fieldDescriptor::string_initial_value(TRAPS) const {
  return constants()->uncached_string_at(initial_value_index(), THREAD);
}

void fieldDescriptor::reinitialize(InstanceKlass* ik, int index) {
  if (_cp.is_null() || field_holder() != ik) {
    _cp = constantPoolHandle(Thread::current(), ik->constants());
    // _cp should now reference ik's constant pool; i.e., ik is now field_holder.
    assert(field_holder() == ik, "must be already initialized to this class");
  }
  FieldInfo* f = ik->field(index);
  _access_flags = accessFlags_from(f->access_flags());
  guarantee(f->name_index() != 0 && f->signature_index() != 0, "bad constant pool index for fieldDescriptor");
  _index = index;
  verify();
}

#ifndef PRODUCT

void fieldDescriptor::verify() const {
  if (_cp.is_null()) {
    assert(_index == badInt, "constructor must be called");  // see constructor
  } else {
    assert(_index >= 0, "good index");
    assert(access_flags().is_internal() ||
           _index < field_holder()->java_fields_count(), "oob");
  }
}

#endif /* PRODUCT */

void fieldDescriptor::print_on(outputStream* st) const {
  access_flags().print_on(st);
  if (access_flags().is_internal()) st->print("internal ");
  name()->print_value_on(st);
  st->print(" ");
  signature()->print_value_on(st);
  st->print(" @%d ", offset());
  if (WizardMode && has_initial_value()) {
    st->print("(initval ");
    constantTag t = initial_value_tag();
    if (t.is_int()) {
      st->print("int %d)", int_initial_value());
    } else if (t.is_long()){
      st->print_jlong(long_initial_value());
    } else if (t.is_float()){
      st->print("float %f)", float_initial_value());
    } else if (t.is_double()){
      st->print("double %lf)", double_initial_value());
    }
  }
}

void fieldDescriptor::print() const { print_on(tty); }

void fieldDescriptor::print_on_for(outputStream* st, oop obj) {
  print_on(st);
  st->print(" ");

  BasicType ft = field_type();
  switch (ft) {
    case T_BYTE:
      st->print("%d", obj->byte_field(offset()));
      break;
    case T_CHAR:
      {
        jchar c = obj->char_field(offset());
        st->print("%c %d", isprint(c) ? c : ' ', c);
      }
      break;
    case T_DOUBLE:
      st->print("%lf", obj->double_field(offset()));
      break;
    case T_FLOAT:
      st->print("%f", obj->float_field(offset()));
      break;
    case T_INT:
      st->print("%d", obj->int_field(offset()));
      break;
    case T_LONG:
      st->print_jlong(obj->long_field(offset()));
      break;
    case T_SHORT:
      st->print("%d", obj->short_field(offset()));
      break;
    case T_BOOLEAN:
      st->print("%s", obj->bool_field(offset()) ? "true" : "false");
      break;
    case T_ARRAY:
      if (obj->obj_field(offset()) != NULL) {
        obj->obj_field(offset())->print_value_on(st);
      } else {
        st->print("NULL");
      }
      break;
    case T_OBJECT:
      if (obj->obj_field(offset()) != NULL) {
        obj->obj_field(offset())->print_value_on(st);
      } else {
        st->print("NULL");
      }
      break;
    default:
      ShouldNotReachHere();
      break;
  }

  // Print a hint as to the underlying integer representation.
  if (is_reference_type(ft)) {
#ifdef _LP64
    if (UseCompressedOops) {
      st->print(" (" INT32_FORMAT_X_0 ")", obj->int_field(offset()));
    } else {
      st->print(" (" INT64_FORMAT_X_0 ")", (int64_t)obj->long_field(offset()));
    }
#else
    st->print(" (" INT32_FORMAT_X_0 ")", obj->int_field(offset()));
#endif
  } else { // Primitives
    switch (ft) {
      case T_LONG:    st->print(" (" INT64_FORMAT_X_0 ")", (int64_t)obj->long_field(offset())); break;
      case T_DOUBLE:  st->print(" (" INT64_FORMAT_X_0 ")", (int64_t)obj->long_field(offset())); break;
      case T_BYTE:    st->print(" (" INT8_FORMAT_X_0  ")", obj->byte_field(offset()));          break;
      case T_CHAR:    st->print(" (" INT16_FORMAT_X_0 ")", obj->char_field(offset()));          break;
      case T_FLOAT:   st->print(" (" INT32_FORMAT_X_0 ")", obj->int_field(offset()));           break;
      case T_INT:     st->print(" (" INT32_FORMAT_X_0 ")", obj->int_field(offset()));           break;
      case T_SHORT:   st->print(" (" INT16_FORMAT_X_0 ")", obj->short_field(offset()));         break;
      case T_BOOLEAN: st->print(" (" INT8_FORMAT_X_0  ")", obj->bool_field(offset()));          break;
    default:
      ShouldNotReachHere();
      break;
    }
  }
}

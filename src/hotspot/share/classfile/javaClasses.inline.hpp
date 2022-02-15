/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_CLASSFILE_JAVACLASSES_INLINE_HPP
#define SHARE_CLASSFILE_JAVACLASSES_INLINE_HPP

#include "classfile/javaClasses.hpp"

#include "oops/access.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/method.hpp"
#include "oops/oop.inline.hpp"
#include "oops/oopsHierarchy.hpp"
#include "oops/typeArrayOop.inline.hpp"

void java_lang_String::set_coder(oop string, jbyte coder) {
  string->byte_field_put(_coder_offset, coder);
}

void java_lang_String::set_value_raw(oop string, typeArrayOop buffer) {
  string->obj_field_put_raw(_value_offset, buffer);
}

void java_lang_String::set_value(oop string, typeArrayOop buffer) {
  string->obj_field_put(_value_offset, buffer);
}

bool java_lang_String::hash_is_set(oop java_string) {
  return java_string->int_field(_hash_offset) != 0 || java_string->bool_field(_hashIsZero_offset) != 0;
}

// Accessors
bool java_lang_String::value_equals(typeArrayOop str_value1, typeArrayOop str_value2) {
  return ((str_value1 == str_value2) ||
          (str_value1->length() == str_value2->length() &&
           (!memcmp(str_value1->base(T_BYTE),
                    str_value2->base(T_BYTE),
                    str_value2->length() * sizeof(jbyte)))));
}

typeArrayOop java_lang_String::value(oop java_string) {
  assert(is_instance(java_string), "must be java_string");
  return (typeArrayOop) java_string->obj_field(_value_offset);
}

typeArrayOop java_lang_String::value_no_keepalive(oop java_string) {
  assert(is_instance(java_string), "must be java_string");
  return (typeArrayOop) java_string->obj_field_access<AS_NO_KEEPALIVE>(_value_offset);
}

bool java_lang_String::is_latin1(oop java_string) {
  assert(is_instance(java_string), "must be java_string");
  jbyte coder = java_string->byte_field(_coder_offset);
  assert(CompactStrings || coder == CODER_UTF16, "Must be UTF16 without CompactStrings");
  return coder == CODER_LATIN1;
}

uint8_t* java_lang_String::flags_addr(oop java_string) {
  assert(_initialized, "Must be initialized");
  assert(is_instance(java_string), "Must be java string");
  return java_string->field_addr<uint8_t>(_flags_offset);
}

bool java_lang_String::is_flag_set(oop java_string, uint8_t flag_mask) {
  return (Atomic::load(flags_addr(java_string)) & flag_mask) != 0;
}

bool java_lang_String::deduplication_forbidden(oop java_string) {
  return is_flag_set(java_string, _deduplication_forbidden_mask);
}

bool java_lang_String::deduplication_requested(oop java_string) {
  return is_flag_set(java_string, _deduplication_requested_mask);
}

void java_lang_String::set_deduplication_forbidden(oop java_string) {
  test_and_set_flag(java_string, _deduplication_forbidden_mask);
}

bool java_lang_String::test_and_set_deduplication_requested(oop java_string) {
  return test_and_set_flag(java_string, _deduplication_requested_mask);
}

int java_lang_String::length(oop java_string, typeArrayOop value) {
  assert(_initialized, "Must be initialized");
  assert(is_instance(java_string), "must be java_string");
  assert(value_equals(value, java_lang_String::value(java_string)),
         "value must be equal to java_lang_String::value(java_string)");
  if (value == NULL) {
    return 0;
  }
  int arr_length = value->length();
  if (!is_latin1(java_string)) {
    assert((arr_length & 1) == 0, "should be even for UTF16 string");
    arr_length >>= 1; // convert number of bytes to number of elements
  }
  return arr_length;
}

int java_lang_String::length(oop java_string) {
  assert(_initialized, "Must be initialized");
  assert(is_instance(java_string), "must be java_string");
  typeArrayOop value = java_lang_String::value_no_keepalive(java_string);
  return length(java_string, value);
}

bool java_lang_String::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::String_klass();
}

// Accessors

oop java_lang_ref_Reference::weak_referent_no_keepalive(oop ref) {
  return ref->obj_field_access<ON_WEAK_OOP_REF | AS_NO_KEEPALIVE>(_referent_offset);
}

oop java_lang_ref_Reference::phantom_referent_no_keepalive(oop ref) {
  return ref->obj_field_access<ON_PHANTOM_OOP_REF | AS_NO_KEEPALIVE>(_referent_offset);
}

oop java_lang_ref_Reference::unknown_referent_no_keepalive(oop ref) {
  return ref->obj_field_access<ON_UNKNOWN_OOP_REF | AS_NO_KEEPALIVE>(_referent_offset);
}

oop java_lang_ref_Reference::unknown_referent(oop ref) {
  return ref->obj_field_access<ON_UNKNOWN_OOP_REF>(_referent_offset);
}

void java_lang_ref_Reference::clear_referent(oop ref) {
  ref->obj_field_put_raw(_referent_offset, nullptr);
}

HeapWord* java_lang_ref_Reference::referent_addr_raw(oop ref) {
  return ref->field_addr<HeapWord>(_referent_offset);
}

oop java_lang_ref_Reference::next(oop ref) {
  return ref->obj_field(_next_offset);
}

void java_lang_ref_Reference::set_next(oop ref, oop value) {
  ref->obj_field_put(_next_offset, value);
}

void java_lang_ref_Reference::set_next_raw(oop ref, oop value) {
  ref->obj_field_put_raw(_next_offset, value);
}

HeapWord* java_lang_ref_Reference::next_addr_raw(oop ref) {
  return ref->field_addr<HeapWord>(_next_offset);
}

oop java_lang_ref_Reference::discovered(oop ref) {
  return ref->obj_field(_discovered_offset);
}

void java_lang_ref_Reference::set_discovered(oop ref, oop value) {
  ref->obj_field_put(_discovered_offset, value);
}

void java_lang_ref_Reference::set_discovered_raw(oop ref, oop value) {
  ref->obj_field_put_raw(_discovered_offset, value);
}

HeapWord* java_lang_ref_Reference::discovered_addr_raw(oop ref) {
  return ref->field_addr<HeapWord>(_discovered_offset);
}

bool java_lang_ref_Reference::is_final(oop ref) {
  return InstanceKlass::cast(ref->klass())->reference_type() == REF_FINAL;
}

bool java_lang_ref_Reference::is_phantom(oop ref) {
  return InstanceKlass::cast(ref->klass())->reference_type() == REF_PHANTOM;
}

inline oop java_lang_Thread::continuation(oop java_thread) {
  return java_thread->obj_field(_continuation_offset);
}

inline oop jdk_internal_vm_ContinuationScope::name(oop ref) {
  return ref->obj_field(_name_offset);
}

inline oop jdk_internal_vm_Continuation::scope(oop ref) {
  return ref->obj_field(_scope_offset);
}
inline oop jdk_internal_vm_Continuation::target(oop ref) {
  return ref->obj_field(_target_offset);
}
inline oop jdk_internal_vm_Continuation::parent(oop ref) {
  return ref->obj_field(_parent_offset);
}
inline oop jdk_internal_vm_Continuation::yieldInfo(oop ref) {
  return ref->obj_field(_yieldInfo_offset);
}
inline void jdk_internal_vm_Continuation::set_yieldInfo(oop ref, oop value) {
  ref->obj_field_put(_yieldInfo_offset, value);
}
inline stackChunkOop jdk_internal_vm_Continuation::tail(oop ref) {
  return (stackChunkOop)ref->obj_field(_tail_offset);
}
inline void jdk_internal_vm_Continuation::set_tail(oop ref, stackChunkOop value) {
  ref->obj_field_put(_tail_offset, value);
}
inline jshort jdk_internal_vm_Continuation::critical_section(oop ref) {
  return ref->short_field(_cs_offset);
}
inline void jdk_internal_vm_Continuation::set_critical_section(oop ref, jshort value) {
  ref->short_field_put(_cs_offset, value);
}

inline bool jdk_internal_vm_Continuation::done(oop ref) {
  return ref->bool_field(_done_offset);
}
inline bool jdk_internal_vm_Continuation::is_mounted(oop ref) {
  return ref->bool_field(_mounted_offset) != 0;
}
inline bool jdk_internal_vm_Continuation::is_preempted(oop ref) {
  return ref->bool_field(_preempted_offset);
}
inline void jdk_internal_vm_Continuation::set_preempted(oop ref, bool value) {
  ref->bool_field_put(_preempted_offset, (jboolean)value);
}

inline oop java_lang_VirtualThread::vthread_scope() {
  oop base = vmClasses::VirtualThread_klass()->static_field_base_raw();
  return base->obj_field(static_vthread_scope_offset);
}

inline jlong java_lang_VirtualThread::set_jfrTraceId(oop ref, jlong id) {
  ref->long_field_put(java_lang_Thread::_tid_offset, id);
  return id;
}

inline oop jdk_internal_vm_StackChunk::parent(oop ref) {
  return ref->obj_field(_parent_offset);
}
inline void jdk_internal_vm_StackChunk::set_parent(oop ref, oop value) {
  ref->obj_field_put(_parent_offset, value);
}

template<typename P>
inline bool jdk_internal_vm_StackChunk::is_parent_null(oop ref) {
  return (oop)RawAccess<>::oop_load(ref->field_addr<P>(_parent_offset)) == NULL;
}

template<typename P>
inline void jdk_internal_vm_StackChunk::set_parent_raw(oop ref, oop value) {
  RawAccess<IS_DEST_UNINITIALIZED>::oop_store(ref->field_addr<P>(_parent_offset), value);
}
inline oop jdk_internal_vm_StackChunk::cont(oop ref) {
  return ref->obj_field(_cont_offset);
}
inline void jdk_internal_vm_StackChunk::set_cont(oop ref, oop value) {
  ref->obj_field_put(_cont_offset, value);
}

template<typename P>
inline oop jdk_internal_vm_StackChunk::cont_raw(oop ref) {
  return (oop)RawAccess<>::oop_load(ref->field_addr<P>(_cont_offset));
}

template<typename P>
inline void jdk_internal_vm_StackChunk::set_cont_raw(oop ref, oop value) {
  RawAccess<IS_DEST_UNINITIALIZED>::oop_store(ref->field_addr<P>(_cont_offset), value);
}

inline jint jdk_internal_vm_StackChunk::size(oop ref) {
  return ref->int_field(_size_offset);
}
inline void jdk_internal_vm_StackChunk::set_size(HeapWord* ref, jint value) {
  *(cast_to_oop(ref))->field_addr<jint>(_size_offset) = value; // ref->int_field_put(_size_offset, value);
}
inline jint jdk_internal_vm_StackChunk::sp(oop ref) {
  return ref->int_field(_sp_offset);
}
inline void jdk_internal_vm_StackChunk::set_sp(oop ref, jint value) {
  ref->int_field_put(_sp_offset, value);
}
inline jlong jdk_internal_vm_StackChunk::pc(oop ref) {
  return ref->long_field(_pc_offset);
}
inline void jdk_internal_vm_StackChunk::set_pc(oop ref, jlong value) {
  ref->long_field_put(_pc_offset, value);
}
inline jint jdk_internal_vm_StackChunk::argsize(oop ref) {
  return ref->int_field(_argsize_offset);
}
inline void jdk_internal_vm_StackChunk::set_argsize(oop ref, jint value) {
  ref->int_field_put(_argsize_offset, value);
}
inline jbyte jdk_internal_vm_StackChunk::flags(oop ref) {
  return ref->byte_field(_flags_offset);
}
inline void jdk_internal_vm_StackChunk::set_flags(oop ref, jbyte value) {
  ref->byte_field_put(_flags_offset, value);
}
inline jint jdk_internal_vm_StackChunk::gc_sp(oop ref) {
  return ref->int_field(_gcSP_offset);
}
inline void jdk_internal_vm_StackChunk::set_gc_sp(oop ref, jint value) {
  ref->int_field_put(_gcSP_offset, value);
}
inline jlong jdk_internal_vm_StackChunk::mark_cycle(oop ref) {
  return ref->long_field(_markCycle_offset);
}
inline void jdk_internal_vm_StackChunk::set_mark_cycle(oop ref, jlong value) {
  ref->long_field_put(_markCycle_offset, value);
}
inline jint jdk_internal_vm_StackChunk::maxSize(oop ref) {
  return ref->int_field(_maxSize_offset);
}
inline void jdk_internal_vm_StackChunk::set_maxSize(oop ref, jint value) {
#ifdef ASSERT
  jint old = maxSize(ref);
  log_develop_trace(jvmcont)("%s max_size: %d -> %d", value >= old ? "add" : "sub", old, value);
#endif
  ref->int_field_put(_maxSize_offset, value);
}

inline void java_lang_invoke_CallSite::set_target_volatile(oop site, oop target) {
  site->obj_field_put_volatile(_target_offset, target);
}

inline oop  java_lang_invoke_CallSite::target(oop site) {
  return site->obj_field(_target_offset);
}

inline void java_lang_invoke_CallSite::set_target(oop site, oop target) {
  site->obj_field_put(_target_offset, target);
}

inline bool java_lang_invoke_CallSite::is_instance(oop obj) {
  return obj != NULL && is_subclass(obj->klass());
}

inline jboolean java_lang_invoke_ConstantCallSite::is_frozen(oop site) {
  return site->bool_field(_is_frozen_offset);
}

inline bool java_lang_invoke_ConstantCallSite::is_instance(oop obj) {
  return obj != NULL && is_subclass(obj->klass());
}

inline bool java_lang_invoke_MethodHandleNatives_CallSiteContext::is_instance(oop obj) {
  return obj != NULL && is_subclass(obj->klass());
}

inline bool java_lang_invoke_MemberName::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::MemberName_klass();
}

inline bool java_lang_invoke_ResolvedMethodName::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::ResolvedMethodName_klass();
}

inline bool java_lang_invoke_MethodType::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::MethodType_klass();
}

inline bool java_lang_invoke_MethodHandle::is_instance(oop obj) {
  return obj != NULL && is_subclass(obj->klass());
}

inline bool java_lang_Class::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::Class_klass();
}

inline Klass* java_lang_Class::as_Klass(oop java_class) {
  //%note memory_2
  assert(java_lang_Class::is_instance(java_class), "must be a Class object");
  Klass* k = ((Klass*)java_class->metadata_field(_klass_offset));
  assert(k == NULL || k->is_klass(), "type check");
  return k;
}

inline bool java_lang_Class::is_primitive(oop java_class) {
  // should assert:
  //assert(java_lang_Class::is_instance(java_class), "must be a Class object");
  bool is_primitive = (java_class->metadata_field(_klass_offset) == NULL);

#ifdef ASSERT
  if (is_primitive) {
    Klass* k = ((Klass*)java_class->metadata_field(_array_klass_offset));
    assert(k == NULL || is_java_primitive(ArrayKlass::cast(k)->element_type()),
        "Should be either the T_VOID primitive or a java primitive");
  }
#endif

  return is_primitive;
}

inline size_t java_lang_Class::oop_size(oop java_class) {
  assert(_oop_size_offset != 0, "must be set");
  int size = java_class->int_field(_oop_size_offset);
  assert(size > 0, "Oop size must be greater than zero, not %d", size);
  return size;
}

inline bool java_lang_invoke_DirectMethodHandle::is_instance(oop obj) {
  return obj != NULL && is_subclass(obj->klass());
}

inline bool java_lang_Module::is_instance(oop obj) {
  return obj != NULL && obj->klass() == vmClasses::Module_klass();
}

inline int Backtrace::merge_bci_and_version(int bci, int version) {
  // only store u2 for version, checking for overflow.
  if (version > USHRT_MAX || version < 0) version = USHRT_MAX;
  assert((jushort)bci == bci, "bci should be short");
  return build_int_from_shorts(version, bci);
}

inline int Backtrace::merge_mid_and_cpref(int mid, int cpref) {
  // only store u2 for mid and cpref, checking for overflow.
  assert((jushort)mid == mid, "mid should be short");
  assert((jushort)cpref == cpref, "cpref should be short");
  return build_int_from_shorts(cpref, mid);
}

inline int Backtrace::bci_at(unsigned int merged) {
  return extract_high_short_from_int(merged);
}

inline int Backtrace::version_at(unsigned int merged) {
  return extract_low_short_from_int(merged);
}

inline int Backtrace::mid_at(unsigned int merged) {
  return extract_high_short_from_int(merged);
}

inline int Backtrace::cpref_at(unsigned int merged) {
  return extract_low_short_from_int(merged);
}

inline int Backtrace::get_line_number(Method* method, int bci) {
  int line_number = 0;
  if (method->is_native()) {
    // Negative value different from -1 below, enabling Java code in
    // class java.lang.StackTraceElement to distinguish "native" from
    // "no LineNumberTable".  JDK tests for -2.
    line_number = -2;
  } else {
    // Returns -1 if no LineNumberTable, and otherwise actual line number
    line_number = method->line_number_from_bci(bci);
  }
  return line_number;
}

inline Symbol* Backtrace::get_source_file_name(InstanceKlass* holder, int version) {
  // RedefineClasses() currently permits redefine operations to
  // happen in parallel using a "last one wins" philosophy. That
  // spec laxness allows the constant pool entry associated with
  // the source_file_name_index for any older constant pool version
  // to be unstable so we shouldn't try to use it.
  if (holder->constants()->version() != version) {
    return NULL;
  } else {
    return holder->source_file_name();
  }
}

#endif // SHARE_CLASSFILE_JAVACLASSES_INLINE_HPP

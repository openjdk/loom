/*
 * Copyright (c) 1997, 2024, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_INTERPRETER_LINKRESOLVER_HPP
#define SHARE_INTERPRETER_LINKRESOLVER_HPP

#include "interpreter/bootstrapInfo.hpp"
#include "oops/method.hpp"

// All the necessary definitions for run-time link resolution.

// CallInfo provides all the information gathered for a particular
// linked call site after resolving it. A link is any reference
// made from within the bytecodes of a method to an object outside of
// that method. If the info is invalid, the link has not been resolved
// successfully.

class CallInfo : public StackObj {
 public:
  // Ways that a method call might be selected (or not) based on receiver type.
  // Note that an invokevirtual instruction might be linked with no_dispatch,
  // and an invokeinterface instruction might be linked with any of the three options
  enum CallKind {
    direct_call,                        // jump into resolved_method (must be concrete)
    vtable_call,                        // select recv.klass.method_at_vtable(index)
    itable_call,                        // select recv.klass.method_at_itable(resolved_method.holder, index)
    unknown_kind = -1
  };
 private:
  Klass*       _resolved_klass;         // static receiver klass, resolved from a symbolic reference
  methodHandle _resolved_method;        // static target method
  methodHandle _selected_method;        // dynamic (actual) target method
  CallKind     _call_kind;              // kind of call (static(=bytecode static/special +
                                        //               others inferred), vtable, itable)
  int          _call_index;             // vtable or itable index of selected class method (if any)
  Handle       _resolved_appendix;      // extra argument in constant pool (if CPCE::has_appendix)
  Handle       _resolved_method_name;   // Object holding the ResolvedMethodName

  void set_static(Klass* resolved_klass, const methodHandle& resolved_method, TRAPS);
  void set_interface(Klass* resolved_klass,
                     const methodHandle& resolved_method,
                     const methodHandle& selected_method,
                     int itable_index, TRAPS);
  void set_virtual(Klass* resolved_klass,
                   const methodHandle& resolved_method,
                   const methodHandle& selected_method,
                   int vtable_index, TRAPS);
  void set_handle(Klass* resolved_klass,
                  const methodHandle& resolved_method,
                  Handle resolved_appendix, TRAPS);
  void set_common(Klass* resolved_klass,
                  const methodHandle& resolved_method,
                  const methodHandle& selected_method,
                  CallKind kind,
                  int index, TRAPS);

  friend class BootstrapInfo;
  friend class LinkResolver;

 public:
  CallInfo() {
#ifndef PRODUCT
    _call_kind  = CallInfo::unknown_kind;
    _call_index = Method::garbage_vtable_index;
#endif //PRODUCT
  }

  // utility to extract an effective CallInfo from a method and an optional receiver limit
  // does not queue the method for compilation.  This also creates a ResolvedMethodName
  // object for the resolved_method.
  CallInfo(Method* resolved_method, Klass* resolved_klass, TRAPS);

  Klass*  resolved_klass() const                 { return _resolved_klass; }
  Method* resolved_method() const;
  Method* selected_method() const;
  Handle       resolved_appendix() const         { return _resolved_appendix; }
  Handle       resolved_method_name() const      { return _resolved_method_name; }
  // Materialize a java.lang.invoke.ResolvedMethodName for this resolved_method
  void     set_resolved_method_name(TRAPS);

  CallKind     call_kind() const                 { return _call_kind; }
  int          vtable_index() const {
    // Even for interface calls the vtable index could be non-negative.
    // See CallInfo::set_interface.
    assert(has_vtable_index() || is_statically_bound(), "");
    assert(call_kind() == vtable_call || call_kind() == direct_call, "");
    // The returned value is < 0 if the call is statically bound.
    // But, the returned value may be >= 0 even if the kind is direct_call.
    // It is up to the caller to decide which way to go.
    return _call_index;
  }
  int          itable_index() const {
    assert(call_kind() == itable_call, "");
    // The returned value is always >= 0, a valid itable index.
    return _call_index;
  }

  // debugging
#ifdef ASSERT
  bool         has_vtable_index() const          { return _call_index >= 0 && _call_kind != CallInfo::itable_call; }
  bool         is_statically_bound() const       { return _call_index == Method::nonvirtual_vtable_index; }
#endif //ASSERT
  void         verify() PRODUCT_RETURN;
  void         print()  PRODUCT_RETURN;
};


// Condensed information from constant pool to use to resolve the method or field.
//   resolved_klass = specified class (i.e., static receiver class)
//   current_klass  = sending method holder (i.e., class containing the method
//                    containing the call being resolved)
//   current_method = sending method (relevant for field resolution)
class LinkInfo : public StackObj {
  Symbol*     _name;            // extracted from JVM_CONSTANT_NameAndType
  Symbol*     _signature;
  Klass*      _resolved_klass;  // class that the constant pool entry points to
  Klass*      _current_klass;   // class that owns the constant pool
  methodHandle _current_method;  // sending method
  bool        _check_access;
  bool        _check_loader_constraints;
  constantTag _tag;

 public:
  enum class AccessCheck { required, skip };
  enum class LoaderConstraintCheck { required, skip };

  LinkInfo(const constantPoolHandle& pool, int index, const methodHandle& current_method, Bytecodes::Code code, TRAPS);
  LinkInfo(const constantPoolHandle& pool, int index, Bytecodes::Code code, TRAPS);

  // Condensed information from other call sites within the vm.
  LinkInfo(Klass* resolved_klass, Symbol* name, Symbol* signature, Klass* current_klass,
           AccessCheck check_access = AccessCheck::required,
           LoaderConstraintCheck check_loader_constraints = LoaderConstraintCheck::required,
           constantTag tag = JVM_CONSTANT_Invalid) :
      _name(name),
      _signature(signature),
      _resolved_klass(resolved_klass),
      _current_klass(current_klass),
      _current_method(methodHandle()),
      _check_access(check_access == AccessCheck::required),
      _check_loader_constraints(check_loader_constraints == LoaderConstraintCheck::required),
      _tag(tag) {
    assert(_resolved_klass != nullptr, "must always have a resolved_klass");
  }

  LinkInfo(Klass* resolved_klass, Symbol* name, Symbol* signature, const methodHandle& current_method,
           AccessCheck check_access = AccessCheck::required,
           LoaderConstraintCheck check_loader_constraints = LoaderConstraintCheck::required,
           constantTag tag = JVM_CONSTANT_Invalid) :
    LinkInfo(resolved_klass, name, signature, current_method->method_holder(), check_access, check_loader_constraints, tag) {
    _current_method = current_method;
  }

  // Case where we just find the method and don't check access against the current class, used by JavaCalls
  LinkInfo(Klass* resolved_klass, Symbol*name, Symbol* signature) :
    LinkInfo(resolved_klass, name, signature, nullptr, AccessCheck::skip, LoaderConstraintCheck::skip,
             JVM_CONSTANT_Invalid) {}

  // accessors
  Symbol* name() const                  { return _name; }
  Symbol* signature() const             { return _signature; }
  Klass* resolved_klass() const         { return _resolved_klass; }
  Klass* current_klass() const          { return _current_klass; }
  Method* current_method() const        { return _current_method(); }
  constantTag tag() const               { return _tag; }
  bool check_access() const             { return _check_access; }
  bool check_loader_constraints() const { return _check_loader_constraints; }
  void         print()  PRODUCT_RETURN;
};

enum class StaticMode : uint8_t {
  dont_initialize_klass,
  initialize_klass,
  initialize_klass_preemptable
};

// Link information for getfield/putfield & getstatic/putstatic bytecodes
// is represented using a fieldDescriptor.

// The LinkResolver is used to resolve constant-pool references at run-time.
// It does all necessary link-time checks & throws exceptions if necessary.

class LinkResolver: AllStatic {
  friend class klassVtable;
  friend class klassItable;

 private:

  static Method* lookup_method_in_klasses(const LinkInfo& link_info,
                                          bool checkpolymorphism,
                                          bool in_imethod_resolve);
  static Method* lookup_method_in_interfaces(const LinkInfo& link_info);

  static Method* lookup_polymorphic_method(const LinkInfo& link_info,
                                           Handle *appendix_result_or_null, TRAPS);
 JVMCI_ONLY(public:) // Needed for CompilerToVM.resolveMethod()
  // Not Linktime so doesn't take LinkInfo
  static Method* lookup_instance_method_in_klasses (Klass* klass, Symbol* name, Symbol* signature,
                                                    Klass::PrivateLookupMode private_mode);
 JVMCI_ONLY(private:)

  // Similar loader constraint checking functions that throw
  // LinkageError with descriptive message.
  static void check_method_loader_constraints(const LinkInfo& link_info,
                                              const methodHandle& resolved_method,
                                              const char* method_type, TRAPS);
  static void check_field_loader_constraints(Symbol* field, Symbol* sig,
                                             Klass* current_klass,
                                             Klass* sel_klass, TRAPS);

  static Method* resolve_interface_method(const LinkInfo& link_info, Bytecodes::Code code, TRAPS);
  static Method* resolve_method          (const LinkInfo& link_info, Bytecodes::Code code, TRAPS);

  static Method* linktime_resolve_static_method    (const LinkInfo& link_info, TRAPS);
  static Method* linktime_resolve_special_method   (const LinkInfo& link_info, TRAPS);
  static Method* linktime_resolve_virtual_method   (const LinkInfo& link_info, TRAPS);
  static Method* linktime_resolve_interface_method (const LinkInfo& link_info, TRAPS);

  static void runtime_resolve_special_method    (CallInfo& result,
                                                 const LinkInfo& link_info,
                                                 const methodHandle& resolved_method,
                                                 Handle recv, TRAPS);

  static void runtime_resolve_virtual_method    (CallInfo& result,
                                                 const methodHandle& resolved_method,
                                                 Klass* resolved_klass,
                                                 Handle recv,
                                                 Klass* recv_klass,
                                                 bool check_null_and_abstract,
                                                 bool is_abstract_interpretation, TRAPS);
  static void runtime_resolve_interface_method  (CallInfo& result,
                                                 const methodHandle& resolved_method,
                                                 Klass* resolved_klass,
                                                 Handle recv,
                                                 Klass* recv_klass,
                                                 bool check_null_and_abstract,
                                                 bool is_abstract_interpretation, TRAPS);

  static bool resolve_previously_linked_invokehandle(CallInfo& result,
                                                     const LinkInfo& link_info,
                                                     const constantPoolHandle& pool,
                                                     int index, TRAPS);

  static void check_field_accessability(Klass* ref_klass,
                                        Klass* resolved_klass,
                                        Klass* sel_klass,
                                        const fieldDescriptor& fd, TRAPS);
  static void check_method_accessability(Klass* ref_klass,
                                         Klass* resolved_klass,
                                         Klass* sel_klass,
                                         const methodHandle& sel_method, TRAPS);

  // runtime resolving from constant pool
  static void resolve_invokestatic   (CallInfo& result,
                                      const constantPoolHandle& pool, int index, StaticMode mode, TRAPS);
  static void resolve_invokespecial  (CallInfo& result, Handle recv,
                                      const constantPoolHandle& pool, int index, TRAPS);
  static void resolve_invokevirtual  (CallInfo& result, Handle recv,
                                      const constantPoolHandle& pool, int index, TRAPS);
  static void resolve_invokeinterface(CallInfo& result, Handle recv,
                                      const constantPoolHandle& pool, int index, TRAPS);
  static void resolve_invokedynamic  (CallInfo& result,
                                      const constantPoolHandle& pool, int index, TRAPS);
  static void resolve_invokehandle   (CallInfo& result,
                                      const constantPoolHandle& pool, int index, TRAPS);
 public:
  // constant pool resolving
  static void check_klass_accessibility(Klass* ref_klass, Klass* sel_klass, TRAPS);

  // static resolving calls (will not run any Java code);
  // used only from Bytecode_invoke::static_target
  static Method* resolve_method_statically(Bytecodes::Code code,
                                           const constantPoolHandle& pool,
                                           int index, TRAPS);

  static void resolve_continuation_enter(CallInfo& callinfo, TRAPS);

  static void resolve_field_access(fieldDescriptor& result,
                                   const constantPoolHandle& pool,
                                   int index,
                                   const methodHandle& method,
                                   Bytecodes::Code byte,
                                   StaticMode mode, TRAPS);
  static void resolve_field_access(fieldDescriptor& result,
                                   const constantPoolHandle& pool,
                                   int index,
                                   const methodHandle& method,
                                   Bytecodes::Code byte, TRAPS) {
    resolve_field_access(result, pool, index, method, byte,
                         StaticMode::initialize_klass, THREAD);
  }
  static void resolve_field(fieldDescriptor& result, const LinkInfo& link_info,
                            Bytecodes::Code access_kind,
                            StaticMode mode, TRAPS);

  static void resolve_static_call   (CallInfo& result,
                                     const LinkInfo& link_info,
                                     StaticMode mode, TRAPS);
  static void resolve_special_call  (CallInfo& result,
                                     Handle recv,
                                     const LinkInfo& link_info,
                                     TRAPS);
  static void resolve_virtual_call  (CallInfo& result, Handle recv, Klass* recv_klass,
                                     const LinkInfo& link_info,
                                     bool check_null_and_abstract, TRAPS);
  static void resolve_interface_call(CallInfo& result, Handle recv, Klass* recv_klass,
                                     const LinkInfo& link_info,
                                     bool check_null_and_abstract, TRAPS);
  static void resolve_handle_call   (CallInfo& result,
                                     const LinkInfo& link_info, TRAPS);
  static void resolve_dynamic_call  (CallInfo& result,
                                     BootstrapInfo& bootstrap_specifier, TRAPS);

  static void cds_resolve_virtual_call  (CallInfo& result, const LinkInfo& link_info, TRAPS);
  static void cds_resolve_interface_call(CallInfo& result, const LinkInfo& link_info, TRAPS);
  static void cds_resolve_special_call  (CallInfo& result, const LinkInfo& link_info, TRAPS);

  // same as above for compile-time resolution; but returns null handle instead of throwing
  // an exception on error also, does not initialize klass (i.e., no side effects)
  static Method* resolve_virtual_call_or_null(Klass* receiver_klass,
                                              const LinkInfo& link_info);
  static Method* resolve_interface_call_or_null(Klass* receiver_klass,
                                                const LinkInfo& link_info);
  static Method* resolve_static_call_or_null(const LinkInfo& link_info);
  static Method* resolve_special_call_or_null(const LinkInfo& link_info);

  static int vtable_index_of_interface_method(Klass* klass, const methodHandle& resolved_method);

  // same as above for compile-time resolution; returns vtable_index if current_klass if linked
  static int resolve_virtual_vtable_index  (Klass* receiver_klass,
                                            const LinkInfo& link_info);

  // static resolving for compiler (does not throw exceptions, returns null handle if unsuccessful)
  static Method* linktime_resolve_virtual_method_or_null  (const LinkInfo& link_info);
  static Method* linktime_resolve_interface_method_or_null(const LinkInfo& link_info);

  // runtime resolving from constant pool
  static void resolve_invoke(CallInfo& result, Handle recv,
                             const constantPoolHandle& pool, int index,
                             Bytecodes::Code byte, StaticMode static_mode, TRAPS);
  static void resolve_invoke(CallInfo& result, Handle recv,
                             const constantPoolHandle& pool, int index,
                             Bytecodes::Code byte, TRAPS) {
    resolve_invoke(result, recv, pool, index, byte, StaticMode::initialize_klass, THREAD);
  }

  // runtime resolving from attached method
  static void resolve_invoke(CallInfo& result, Handle& recv,
                             const methodHandle& attached_method,
                             Bytecodes::Code byte, TRAPS);

  // Only resolved method known.
  static void throw_abstract_method_error(const methodHandle& resolved_method, TRAPS) {
    throw_abstract_method_error(resolved_method, methodHandle(), nullptr, CHECK);
  }
  // Resolved method and receiver klass know.
  static void throw_abstract_method_error(const methodHandle& resolved_method, Klass *recv_klass, TRAPS) {
    throw_abstract_method_error(resolved_method, methodHandle(), recv_klass, CHECK);
  }
  // Selected method is abstract.
  static void throw_abstract_method_error(const methodHandle& resolved_method,
                                          const methodHandle& selected_method,
                                          Klass *recv_klass, TRAPS);
};
#endif // SHARE_INTERPRETER_LINKRESOLVER_HPP

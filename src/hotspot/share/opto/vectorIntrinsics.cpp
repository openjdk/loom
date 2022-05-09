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

#include "precompiled.hpp"
#include "ci/ciSymbols.hpp"
#include "classfile/vmSymbols.hpp"
#include "opto/library_call.hpp"
#include "opto/runtime.hpp"
#include "opto/vectornode.hpp"
#include "prims/vectorSupport.hpp"
#include "runtime/stubRoutines.hpp"

#ifdef ASSERT
static bool is_vector(ciKlass* klass) {
  return klass->is_subclass_of(ciEnv::current()->vector_VectorPayload_klass());
}

static bool check_vbox(const TypeInstPtr* vbox_type) {
  assert(vbox_type->klass_is_exact(), "");

  ciInstanceKlass* ik = vbox_type->klass()->as_instance_klass();
  assert(is_vector(ik), "not a vector");

  ciField* fd1 = ik->get_field_by_name(ciSymbols::ETYPE_name(), ciSymbols::class_signature(), /* is_static */ true);
  assert(fd1 != NULL, "element type info is missing");

  ciConstant val1 = fd1->constant_value();
  BasicType elem_bt = val1.as_object()->as_instance()->java_mirror_type()->basic_type();
  assert(is_java_primitive(elem_bt), "element type info is missing");

  ciField* fd2 = ik->get_field_by_name(ciSymbols::VLENGTH_name(), ciSymbols::int_signature(), /* is_static */ true);
  assert(fd2 != NULL, "vector length info is missing");

  ciConstant val2 = fd2->constant_value();
  assert(val2.as_int() > 0, "vector length info is missing");

  return true;
}
#endif

static bool is_vector_mask(ciKlass* klass) {
  return klass->is_subclass_of(ciEnv::current()->vector_VectorMask_klass());
}

static bool is_vector_shuffle(ciKlass* klass) {
  return klass->is_subclass_of(ciEnv::current()->vector_VectorShuffle_klass());
}

bool LibraryCallKit::arch_supports_vector_rotate(int opc, int num_elem, BasicType elem_bt,
                                                 VectorMaskUseType mask_use_type, bool has_scalar_args) {
  bool is_supported = true;

  // has_scalar_args flag is true only for non-constant scalar shift count,
  // since in this case shift needs to be broadcasted.
  if (!Matcher::match_rule_supported_vector(opc, num_elem, elem_bt) ||
       (has_scalar_args &&
         !arch_supports_vector(VectorNode::replicate_opcode(elem_bt), num_elem, elem_bt, VecMaskNotUsed))) {
    is_supported = false;
  }

  if (is_supported) {
    // Check whether mask unboxing is supported.
    if ((mask_use_type & VecMaskUseLoad) != 0) {
      if (!Matcher::match_rule_supported_vector(Op_VectorLoadMask, num_elem, elem_bt)) {
      #ifndef PRODUCT
        if (C->print_intrinsics()) {
          tty->print_cr("  ** Rejected vector mask loading (%s,%s,%d) because architecture does not support it",
                        NodeClassNames[Op_VectorLoadMask], type2name(elem_bt), num_elem);
        }
      #endif
        return false;
      }
    }

    if ((mask_use_type & VecMaskUsePred) != 0) {
      if (!Matcher::has_predicated_vectors() ||
          !Matcher::match_rule_supported_vector_masked(opc, num_elem, elem_bt)) {
      #ifndef PRODUCT
        if (C->print_intrinsics()) {
          tty->print_cr("Rejected vector mask predicate using (%s,%s,%d) because architecture does not support it",
                        NodeClassNames[opc], type2name(elem_bt), num_elem);
        }
      #endif
        return false;
      }
    }
  }

  int lshiftopc, rshiftopc;
  switch(elem_bt) {
    case T_BYTE:
      lshiftopc = Op_LShiftI;
      rshiftopc = Op_URShiftB;
      break;
    case T_SHORT:
      lshiftopc = Op_LShiftI;
      rshiftopc = Op_URShiftS;
      break;
    case T_INT:
      lshiftopc = Op_LShiftI;
      rshiftopc = Op_URShiftI;
      break;
    case T_LONG:
      lshiftopc = Op_LShiftL;
      rshiftopc = Op_URShiftL;
      break;
    default:
      assert(false, "Unexpected type");
  }
  int lshiftvopc = VectorNode::opcode(lshiftopc, elem_bt);
  int rshiftvopc = VectorNode::opcode(rshiftopc, elem_bt);
  if (!is_supported &&
      arch_supports_vector(lshiftvopc, num_elem, elem_bt, VecMaskNotUsed, has_scalar_args) &&
      arch_supports_vector(rshiftvopc, num_elem, elem_bt, VecMaskNotUsed, has_scalar_args) &&
      arch_supports_vector(Op_OrV, num_elem, elem_bt, VecMaskNotUsed)) {
    is_supported = true;
  }
  return is_supported;
}

Node* GraphKit::box_vector(Node* vector, const TypeInstPtr* vbox_type, BasicType elem_bt, int num_elem, bool deoptimize_on_exception) {
  assert(EnableVectorSupport, "");

  PreserveReexecuteState preexecs(this);
  jvms()->set_should_reexecute(true);

  VectorBoxAllocateNode* alloc = new VectorBoxAllocateNode(C, vbox_type);
  set_edges_for_java_call(alloc, /*must_throw=*/false, /*separate_io_proj=*/true);
  make_slow_call_ex(alloc, env()->Throwable_klass(), /*separate_io_proj=*/true, deoptimize_on_exception);
  set_i_o(gvn().transform( new ProjNode(alloc, TypeFunc::I_O) ));
  set_all_memory(gvn().transform( new ProjNode(alloc, TypeFunc::Memory) ));
  Node* ret = gvn().transform(new ProjNode(alloc, TypeFunc::Parms));

  assert(check_vbox(vbox_type), "");
  const TypeVect* vt = TypeVect::make(elem_bt, num_elem, is_vector_mask(vbox_type->klass()));
  VectorBoxNode* vbox = new VectorBoxNode(C, ret, vector, vbox_type, vt);
  return gvn().transform(vbox);
}

Node* GraphKit::unbox_vector(Node* v, const TypeInstPtr* vbox_type, BasicType elem_bt, int num_elem, bool shuffle_to_vector) {
  assert(EnableVectorSupport, "");
  const TypeInstPtr* vbox_type_v = gvn().type(v)->is_instptr();
  if (vbox_type->klass() != vbox_type_v->klass()) {
    return NULL; // arguments don't agree on vector shapes
  }
  if (vbox_type_v->maybe_null()) {
    return NULL; // no nulls are allowed
  }
  assert(check_vbox(vbox_type), "");
  const TypeVect* vt = TypeVect::make(elem_bt, num_elem, is_vector_mask(vbox_type->klass()));
  Node* unbox = gvn().transform(new VectorUnboxNode(C, vt, v, merged_memory(), shuffle_to_vector));
  return unbox;
}

Node* GraphKit::vector_shift_count(Node* cnt, int shift_op, BasicType bt, int num_elem) {
  assert(bt == T_INT || bt == T_LONG || bt == T_SHORT || bt == T_BYTE, "byte, short, long and int are supported");
  juint mask = (type2aelembytes(bt) * BitsPerByte - 1);
  Node* nmask = gvn().transform(ConNode::make(TypeInt::make(mask)));
  Node* mcnt = gvn().transform(new AndINode(cnt, nmask));
  return gvn().transform(VectorNode::shift_count(shift_op, mcnt, num_elem, bt));
}

bool LibraryCallKit::arch_supports_vector(int sopc, int num_elem, BasicType type, VectorMaskUseType mask_use_type, bool has_scalar_args) {
  // Check that the operation is valid.
  if (sopc <= 0) {
#ifndef PRODUCT
    if (C->print_intrinsics()) {
      tty->print_cr("  ** Rejected intrinsification because no valid vector op could be extracted");
    }
#endif
    return false;
  }

  if (VectorNode::is_vector_rotate(sopc)) {
    if(!arch_supports_vector_rotate(sopc, num_elem, type, mask_use_type, has_scalar_args)) {
#ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("  ** Rejected vector op (%s,%s,%d) because architecture does not support variable vector shifts",
                      NodeClassNames[sopc], type2name(type), num_elem);
      }
#endif
      return false;
    }
  } else if (VectorNode::is_vector_integral_negate(sopc)) {
    if (!VectorNode::is_vector_integral_negate_supported(sopc, num_elem, type, false)) {
#ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("  ** Rejected vector op (%s,%s,%d) because architecture does not support integral vector negate",
                      NodeClassNames[sopc], type2name(type), num_elem);
      }
#endif
      return false;
    }
  } else {
    // Check that architecture supports this op-size-type combination.
    if (!Matcher::match_rule_supported_vector(sopc, num_elem, type)) {
#ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("  ** Rejected vector op (%s,%s,%d) because architecture does not support it",
                      NodeClassNames[sopc], type2name(type), num_elem);
      }
#endif
      return false;
    } else {
      assert(Matcher::match_rule_supported(sopc), "must be supported");
    }
  }

  if (num_elem == 1) {
    if (mask_use_type != VecMaskNotUsed) {
#ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("  ** Rejected vector mask op (%s,%s,%d) because architecture does not support it",
                      NodeClassNames[sopc], type2name(type), num_elem);
      }
#endif
      return false;
    }

    if (sopc != 0) {
      if (sopc != Op_LoadVector && sopc != Op_StoreVector) {
#ifndef PRODUCT
        if (C->print_intrinsics()) {
          tty->print_cr("  ** Not a svml call or load/store vector op (%s,%s,%d)",
                        NodeClassNames[sopc], type2name(type), num_elem);
        }
#endif
        return false;
      }
    }
  }

  if (!has_scalar_args && VectorNode::is_vector_shift(sopc) &&
      Matcher::supports_vector_variable_shifts() == false) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** Rejected vector op (%s,%s,%d) because architecture does not support variable vector shifts",
                    NodeClassNames[sopc], type2name(type), num_elem);
    }
    return false;
  }

  // Check whether mask unboxing is supported.
  if ((mask_use_type & VecMaskUseLoad) != 0) {
    if (!Matcher::match_rule_supported_vector(Op_VectorLoadMask, num_elem, type)) {
    #ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("  ** Rejected vector mask loading (%s,%s,%d) because architecture does not support it",
                      NodeClassNames[Op_VectorLoadMask], type2name(type), num_elem);
      }
    #endif
      return false;
    }
  }

  // Check whether mask boxing is supported.
  if ((mask_use_type & VecMaskUseStore) != 0) {
    if (!Matcher::match_rule_supported_vector(Op_VectorStoreMask, num_elem, type)) {
    #ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("Rejected vector mask storing (%s,%s,%d) because architecture does not support it",
                      NodeClassNames[Op_VectorStoreMask], type2name(type), num_elem);
      }
    #endif
      return false;
    }
  }

  if ((mask_use_type & VecMaskUsePred) != 0) {
    bool is_supported = false;
    if (Matcher::has_predicated_vectors()) {
      if (VectorNode::is_vector_integral_negate(sopc)) {
        is_supported = VectorNode::is_vector_integral_negate_supported(sopc, num_elem, type, true);
      } else {
        is_supported = Matcher::match_rule_supported_vector_masked(sopc, num_elem, type);
      }
    }

    if (!is_supported) {
    #ifndef PRODUCT
      if (C->print_intrinsics()) {
        tty->print_cr("Rejected vector mask predicate using (%s,%s,%d) because architecture does not support it",
                      NodeClassNames[sopc], type2name(type), num_elem);
      }
    #endif
      return false;
    }
  }

  return true;
}

static bool is_klass_initialized(const TypeInstPtr* vec_klass) {
  if (vec_klass->const_oop() == NULL) {
    return false; // uninitialized or some kind of unsafe access
  }
  assert(vec_klass->const_oop()->as_instance()->java_lang_Class_klass() != NULL, "klass instance expected");
  ciInstanceKlass* klass =  vec_klass->const_oop()->as_instance()->java_lang_Class_klass()->as_instance_klass();
  return klass->is_initialized();
}

// public static
// <V extends Vector<E>,
//  M extends VectorMask<E>,
//  E>
// V unaryOp(int oprId, Class<? extends V> vmClass, Class<? extends M> maskClass, Class<E> elementType,
//           int length, V v, M m,
//           UnaryOperation<V, M> defaultImpl)
//
// public static
// <V,
//  M extends VectorMask<E>,
//  E>
// V binaryOp(int oprId, Class<? extends V> vmClass, Class<? extends M> maskClass, Class<E> elementType,
//            int length, V v1, V v2, M m,
//            BinaryOperation<V, M> defaultImpl)
//
// public static
// <V extends Vector<E>,
//  M extends VectorMask<E>,
//  E>
// V ternaryOp(int oprId, Class<? extends V> vmClass, Class<? extends M> maskClass, Class<E> elementType,
//             int length, V v1, V v2, V v3, M m,
//             TernaryOperation<V, M> defaultImpl)
//
bool LibraryCallKit::inline_vector_nary_operation(int n) {
  const TypeInt*     opr          = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* vector_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(3))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(4))->isa_int();

  if (opr == NULL || vector_klass == NULL || elem_klass == NULL || vlen == NULL ||
      !opr->is_con() || vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: opr=%s vclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }

  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  // "argument(n + 5)" should be the mask object. We assume it is "null" when no mask
  // is used to control this operation.
  const Type* vmask_type = gvn().type(argument(n + 5));
  bool is_masked_op = vmask_type != TypePtr::NULL_PTR;
  if (is_masked_op) {
    if (mask_klass == NULL || mask_klass->const_oop() == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** missing constant: maskclass=%s", NodeClassNames[argument(2)->Opcode()]);
      }
      return false; // not enough info for intrinsification
    }

    if (!is_klass_initialized(mask_klass)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** mask klass argument not initialized");
      }
      return false;
    }

    if (vmask_type->maybe_null()) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** null mask values are not allowed for masked op");
      }
      return false;
    }
  }

  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  int opc = VectorSupport::vop2ideal(opr->get_con(), elem_bt);
  int sopc = VectorNode::opcode(opc, elem_bt);
  if ((opc != Op_CallLeafVector) && (sopc == 0)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** operation not supported: opc=%s bt=%s", NodeClassNames[opc], type2name(elem_bt));
    }
    return false; // operation not supported
  }
  if (num_elem == 1) {
    if (opc != Op_CallLeafVector || elem_bt != T_DOUBLE) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not a svml call: arity=%d opc=%d vlen=%d etype=%s",
                      n, opc, num_elem, type2name(elem_bt));
      }
      return false;
    }
  }
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  if (is_vector_mask(vbox_klass)) {
    assert(!is_masked_op, "mask operations do not need mask to control");
  }

  if (opc == Op_CallLeafVector) {
    if (!UseVectorStubs) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** vector stubs support is disabled");
      }
      return false;
    }
    if (!Matcher::supports_vector_calling_convention()) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** no vector calling conventions supported");
      }
      return false;
    }
    if (!Matcher::vector_size_supported(elem_bt, num_elem)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** vector size (vlen=%d, etype=%s) is not supported",
                      num_elem, type2name(elem_bt));
      }
      return false;
    }
  }

  // When using mask, mask use type needs to be VecMaskUseLoad.
  VectorMaskUseType mask_use_type = is_vector_mask(vbox_klass) ? VecMaskUseAll
                                      : is_masked_op ? VecMaskUseLoad : VecMaskNotUsed;
  if ((sopc != 0) && !arch_supports_vector(sopc, num_elem, elem_bt, mask_use_type)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d opc=%d vlen=%d etype=%s ismask=%d is_masked_op=%d",
                    n, sopc, num_elem, type2name(elem_bt),
                    is_vector_mask(vbox_klass) ? 1 : 0, is_masked_op ? 1 : 0);
    }
    return false; // not supported
  }

  // Return true if current platform has implemented the masked operation with predicate feature.
  bool use_predicate = is_masked_op && sopc != 0 && arch_supports_vector(sopc, num_elem, elem_bt, VecMaskUsePred);
  if (is_masked_op && !use_predicate && !arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d opc=%d vlen=%d etype=%s ismask=0 is_masked_op=1",
                    n, sopc, num_elem, type2name(elem_bt));
    }
    return false;
  }

  Node* opd1 = NULL; Node* opd2 = NULL; Node* opd3 = NULL;
  switch (n) {
    case 3: {
      opd3 = unbox_vector(argument(7), vbox_type, elem_bt, num_elem);
      if (opd3 == NULL) {
        if (C->print_intrinsics()) {
          tty->print_cr("  ** unbox failed v3=%s",
                        NodeClassNames[argument(7)->Opcode()]);
        }
        return false;
      }
      // fall-through
    }
    case 2: {
      opd2 = unbox_vector(argument(6), vbox_type, elem_bt, num_elem);
      if (opd2 == NULL) {
        if (C->print_intrinsics()) {
          tty->print_cr("  ** unbox failed v2=%s",
                        NodeClassNames[argument(6)->Opcode()]);
        }
        return false;
      }
      // fall-through
    }
    case 1: {
      opd1 = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
      if (opd1 == NULL) {
        if (C->print_intrinsics()) {
          tty->print_cr("  ** unbox failed v1=%s",
                        NodeClassNames[argument(5)->Opcode()]);
        }
        return false;
      }
      break;
    }
    default: fatal("unsupported arity: %d", n);
  }

  Node* mask = NULL;
  if (is_masked_op) {
    ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
    assert(is_vector_mask(mbox_klass), "argument(2) should be a mask class");
    const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
    mask = unbox_vector(argument(n + 5), mbox_type, elem_bt, num_elem);
    if (mask == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed mask=%s",
                      NodeClassNames[argument(n + 5)->Opcode()]);
      }
      return false;
    }
  }

  Node* operation = NULL;
  if (opc == Op_CallLeafVector) {
    assert(UseVectorStubs, "sanity");
    operation = gen_call_to_svml(opr->get_con(), elem_bt, num_elem, opd1, opd2);
    if (operation == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** svml call failed for %s_%s_%d",
                         (elem_bt == T_FLOAT)?"float":"double",
                         VectorSupport::svmlname[opr->get_con() - VectorSupport::VECTOR_OP_SVML_START],
                         num_elem * type2aelembytes(elem_bt));
      }
      return false;
     }
  } else {
    const TypeVect* vt = TypeVect::make(elem_bt, num_elem, is_vector_mask(vbox_klass));
    switch (n) {
      case 1:
      case 2: {
        operation = VectorNode::make(sopc, opd1, opd2, vt, is_vector_mask(vbox_klass), VectorNode::is_shift_opcode(opc));
        break;
      }
      case 3: {
        operation = VectorNode::make(sopc, opd1, opd2, opd3, vt);
        break;
      }
      default: fatal("unsupported arity: %d", n);
    }
  }

  if (is_masked_op && mask != NULL) {
    if (use_predicate) {
      operation->add_req(mask);
      operation->add_flag(Node::Flag_is_predicated_vector);
    } else {
      operation = gvn().transform(operation);
      operation = new VectorBlendNode(opd1, operation, mask);
    }
  }
  operation = gvn().transform(operation);

  // Wrap it up in VectorBox to keep object type information.
  Node* vbox = box_vector(operation, vbox_type, elem_bt, num_elem);
  set_result(vbox);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// <Sh extends VectorShuffle<E>,  E>
//  Sh ShuffleIota(Class<?> E, Class<?> shuffleClass, Vector.Species<E> s, int length,
//                  int start, int step, int wrap, ShuffleIotaOperation<Sh, E> defaultImpl)
bool LibraryCallKit::inline_vector_shuffle_iota() {
  const TypeInstPtr* shuffle_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInt*     vlen          = gvn().type(argument(3))->isa_int();
  const TypeInt*     start_val     = gvn().type(argument(4))->isa_int();
  const TypeInt*     step_val      = gvn().type(argument(5))->isa_int();
  const TypeInt*     wrap          = gvn().type(argument(6))->isa_int();

  Node* start = argument(4);
  Node* step  = argument(5);

  if (shuffle_klass == NULL || vlen == NULL || start_val == NULL || step_val == NULL || wrap == NULL) {
    return false; // dead code
  }
  if (!vlen->is_con() || !is_power_of_2(vlen->get_con()) ||
      shuffle_klass->const_oop() == NULL || !wrap->is_con()) {
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(shuffle_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  int do_wrap = wrap->get_con();
  int num_elem = vlen->get_con();
  BasicType elem_bt = T_BYTE;

  if (!arch_supports_vector(VectorNode::replicate_opcode(elem_bt), num_elem, elem_bt, VecMaskNotUsed)) {
    return false;
  }
  if (!arch_supports_vector(Op_AddVB, num_elem, elem_bt, VecMaskNotUsed)) {
    return false;
  }
  if (!arch_supports_vector(Op_AndV, num_elem, elem_bt, VecMaskNotUsed)) {
    return false;
  }
  if (!arch_supports_vector(Op_VectorLoadConst, num_elem, elem_bt, VecMaskNotUsed)) {
    return false;
  }
  if (!arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad)) {
    return false;
  }
  if (!arch_supports_vector(Op_VectorMaskCmp, num_elem, elem_bt, VecMaskUseStore)) {
    return false;
  }

  const Type * type_bt = Type::get_const_basic_type(elem_bt);
  const TypeVect * vt  = TypeVect::make(type_bt, num_elem);

  Node* res =  gvn().transform(new VectorLoadConstNode(gvn().makecon(TypeInt::ZERO), vt));

  if(!step_val->is_con() || !is_power_of_2(step_val->get_con())) {
    Node* bcast_step     = gvn().transform(VectorNode::scalar2vector(step, num_elem, type_bt));
    res = gvn().transform(VectorNode::make(Op_MulI, res, bcast_step, num_elem, elem_bt));
  } else if (step_val->get_con() > 1) {
    Node* cnt = gvn().makecon(TypeInt::make(log2i_exact(step_val->get_con())));
    Node* shift_cnt = vector_shift_count(cnt, Op_LShiftI, elem_bt, num_elem);
    res = gvn().transform(VectorNode::make(Op_LShiftVB, res, shift_cnt, vt));
  }

  if (!start_val->is_con() || start_val->get_con() != 0) {
    Node* bcast_start    = gvn().transform(VectorNode::scalar2vector(start, num_elem, type_bt));
    res = gvn().transform(VectorNode::make(Op_AddI, res, bcast_start, num_elem, elem_bt));
  }

  Node * mod_val = gvn().makecon(TypeInt::make(num_elem-1));
  Node * bcast_mod  = gvn().transform(VectorNode::scalar2vector(mod_val, num_elem, type_bt));
  if(do_wrap)  {
    // Wrap the indices greater than lane count.
    res = gvn().transform(VectorNode::make(Op_AndI, res, bcast_mod, num_elem, elem_bt));
  } else {
    ConINode* pred_node = (ConINode*)gvn().makecon(TypeInt::make(BoolTest::ge));
    Node * lane_cnt  = gvn().makecon(TypeInt::make(num_elem));
    Node * bcast_lane_cnt = gvn().transform(VectorNode::scalar2vector(lane_cnt, num_elem, type_bt));
    const TypeVect* vmask_type = TypeVect::makemask(elem_bt, num_elem);
    Node* mask = gvn().transform(new VectorMaskCmpNode(BoolTest::ge, bcast_lane_cnt, res, pred_node, vmask_type));

    // Make the indices greater than lane count as -ve values. This matches the java side implementation.
    res = gvn().transform(VectorNode::make(Op_AndI, res, bcast_mod, num_elem, elem_bt));
    Node * biased_val = gvn().transform(VectorNode::make(Op_SubI, res, bcast_lane_cnt, num_elem, elem_bt));
    res = gvn().transform(new VectorBlendNode(biased_val, res, mask));
  }

  ciKlass* sbox_klass = shuffle_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* shuffle_box_type = TypeInstPtr::make_exact(TypePtr::NotNull, sbox_klass);

  // Wrap it up in VectorBox to keep object type information.
  res = box_vector(res, shuffle_box_type, elem_bt, num_elem);
  set_result(res);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// <E, M>
// long maskReductionCoerced(int oper, Class<? extends M> maskClass, Class<?> elemClass,
//                          int length, M m, VectorMaskOp<M> defaultImpl)
bool LibraryCallKit::inline_vector_mask_operation() {
  const TypeInt*     oper       = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* mask_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen       = gvn().type(argument(3))->isa_int();
  Node*              mask       = argument(4);

  if (mask_klass == NULL || elem_klass == NULL || mask->is_top() || vlen == NULL) {
    return false; // dead code
  }

  if (!is_klass_initialized(mask_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  int num_elem = vlen->get_con();
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  BasicType elem_bt = elem_type->basic_type();

  if (!arch_supports_vector(Op_LoadVector, num_elem, T_BOOLEAN, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=cast#%d/3 vlen2=%d etype2=%s",
                    Op_LoadVector, num_elem, type2name(T_BOOLEAN));
    }
    return false; // not supported
  }

  int mopc = VectorSupport::vop2ideal(oper->get_con(), elem_bt);
  if (!arch_supports_vector(mopc, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=cast#%d/3 vlen2=%d etype2=%s",
                    mopc, num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }

  const Type* elem_ty = Type::get_const_basic_type(elem_bt);
  ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* mask_box_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
  Node* mask_vec = unbox_vector(mask, mask_box_type, elem_bt, num_elem, true);
  if (mask_vec == NULL) {
    if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed mask=%s",
                      NodeClassNames[argument(4)->Opcode()]);
    }
    return false;
  }

  if (mask_vec->bottom_type()->isa_vectmask() == NULL) {
    mask_vec = gvn().transform(VectorStoreMaskNode::make(gvn(), mask_vec, elem_bt, num_elem));
  }
  const Type* maskoper_ty = mopc == Op_VectorMaskToLong ? (const Type*)TypeLong::LONG : (const Type*)TypeInt::INT;
  Node* maskoper = gvn().transform(VectorMaskOpNode::make(mask_vec, maskoper_ty, mopc));
  if (mopc != Op_VectorMaskToLong) {
    maskoper = ConvI2L(maskoper);
  }
  set_result(maskoper);

  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <V,
//  Sh extends VectorShuffle<E>,
//  E>
// V shuffleToVector(Class<? extends Vector<E>> vclass, Class<E> elementType,
//                   Class<? extends Sh> shuffleClass, Sh s, int length,
//                   ShuffleToVectorOperation<V, Sh, E> defaultImpl)
bool LibraryCallKit::inline_vector_shuffle_to_vector() {
  const TypeInstPtr* vector_klass  = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* elem_klass    = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* shuffle_klass = gvn().type(argument(2))->isa_instptr();
  Node*              shuffle       = argument(3);
  const TypeInt*     vlen          = gvn().type(argument(4))->isa_int();

  if (vector_klass == NULL || elem_klass == NULL || shuffle_klass == NULL || shuffle->is_top() || vlen == NULL) {
    return false; // dead code
  }
  if (!vlen->is_con() || vector_klass->const_oop() == NULL || shuffle_klass->const_oop() == NULL) {
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(shuffle_klass) || !is_klass_initialized(vector_klass) ) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  int num_elem = vlen->get_con();
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  BasicType elem_bt = elem_type->basic_type();

  if (num_elem < 4) {
    return false;
  }

  int cast_vopc = VectorCastNode::opcode(T_BYTE); // from shuffle of type T_BYTE
  // Make sure that cast is implemented to particular type/size combination.
  if (!arch_supports_vector(cast_vopc, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=cast#%d/3 vlen2=%d etype2=%s",
        cast_vopc, num_elem, type2name(elem_bt));
    }
    return false;
  }

  ciKlass* sbox_klass = shuffle_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* shuffle_box_type = TypeInstPtr::make_exact(TypePtr::NotNull, sbox_klass);

  // Unbox shuffle with true flag to indicate its load shuffle to vector
  // shuffle is a byte array
  Node* shuffle_vec = unbox_vector(shuffle, shuffle_box_type, T_BYTE, num_elem, true);

  // cast byte to target element type
  shuffle_vec = gvn().transform(VectorCastNode::make(cast_vopc, shuffle_vec, elem_bt, num_elem));

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vec_box_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  // Box vector
  Node* res = box_vector(shuffle_vec, vec_box_type, elem_bt, num_elem);
  set_result(res);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <M,
//  S extends VectorSpecies<E>,
//  E>
// M fromBitsCoerced(Class<? extends M> vmClass, Class<E> elementType, int length,
//                    long bits, int mode, S s,
//                    BroadcastOperation<M, E, S> defaultImpl)
bool LibraryCallKit::inline_vector_frombits_coerced() {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(2))->isa_int();
  const TypeLong*    bits_type    = gvn().type(argument(3))->isa_long();
  // Mode argument determines the mode of operation it can take following values:-
  // MODE_BROADCAST for vector Vector.broadcast and VectorMask.maskAll operations.
  // MODE_BITS_COERCED_LONG_TO_MASK for VectorMask.fromLong operation.
  const TypeInt*     mode         = gvn().type(argument(5))->isa_int();

  if (vector_klass == NULL || elem_klass == NULL || vlen == NULL || mode == NULL ||
      bits_type == NULL || vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL ||
      !vlen->is_con() || !mode->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s etype=%s vlen=%s bitwise=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(5)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }

  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  bool is_mask = is_vector_mask(vbox_klass);
  int  bcast_mode = mode->get_con();
  VectorMaskUseType checkFlags = (VectorMaskUseType)(is_mask ? VecMaskUseAll : VecMaskNotUsed);
  int opc = bcast_mode == VectorSupport::MODE_BITS_COERCED_LONG_TO_MASK ? Op_VectorLongToMask : VectorNode::replicate_opcode(elem_bt);

  if (!arch_supports_vector(opc, num_elem, elem_bt, checkFlags, true /*has_scalar_args*/)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=0 op=broadcast vlen=%d etype=%s ismask=%d bcast_mode=%d",
                    num_elem, type2name(elem_bt),
                    is_mask ? 1 : 0,
                    bcast_mode);
    }
    return false; // not supported
  }

  Node* broadcast = NULL;
  Node* bits = argument(3);
  Node* elem = bits;

  if (opc == Op_VectorLongToMask) {
    const TypeVect* vt = TypeVect::makemask(elem_bt, num_elem);
    if (vt->isa_vectmask()) {
      broadcast = gvn().transform(new VectorLongToMaskNode(elem, vt));
    } else {
      const TypeVect* mvt = TypeVect::make(T_BOOLEAN, num_elem);
      broadcast = gvn().transform(new VectorLongToMaskNode(elem, mvt));
      broadcast = gvn().transform(new VectorLoadMaskNode(broadcast, vt));
    }
  } else {
    switch (elem_bt) {
      case T_BOOLEAN: // fall-through
      case T_BYTE:    // fall-through
      case T_SHORT:   // fall-through
      case T_CHAR:    // fall-through
      case T_INT: {
        elem = gvn().transform(new ConvL2INode(bits));
        break;
      }
      case T_DOUBLE: {
        elem = gvn().transform(new MoveL2DNode(bits));
        break;
      }
      case T_FLOAT: {
        bits = gvn().transform(new ConvL2INode(bits));
        elem = gvn().transform(new MoveI2FNode(bits));
        break;
      }
      case T_LONG: {
        // no conversion needed
        break;
      }
      default: fatal("%s", type2name(elem_bt));
    }
    broadcast = VectorNode::scalar2vector(elem, num_elem, Type::get_const_basic_type(elem_bt), is_mask);
    broadcast = gvn().transform(broadcast);
  }

  Node* box = box_vector(broadcast, vbox_type, elem_bt, num_elem);
  set_result(box);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

static bool elem_consistent_with_arr(BasicType elem_bt, const TypeAryPtr* arr_type) {
  assert(arr_type != NULL, "unexpected");
  BasicType arr_elem_bt = arr_type->elem()->array_element_basic_type();
  if (elem_bt == arr_elem_bt) {
    return true;
  } else if (elem_bt == T_SHORT && arr_elem_bt == T_CHAR) {
    // Load/store of short vector from/to char[] is supported
    return true;
  } else if (elem_bt == T_BYTE && arr_elem_bt == T_BOOLEAN) {
    // Load/store of byte vector from/to boolean[] is supported
    return true;
  } else {
    return false;
  }
}

// public static
// <C,
//  VM,
//  E,
//  S extends VectorSpecies<E>>
// VM load(Class<? extends VM> vmClass, Class<E> elementType, int length,
//         Object base, long offset,    // Unsafe addressing
//         C container, int index, S s,     // Arguments for default implementation
//         LoadOperation<C, VM, E, S> defaultImpl)
//
// public static
// <C,
//  V extends Vector<?>>
// void store(Class<?> vectorClass, Class<?> elementType, int length,
//            Object base, long offset,    // Unsafe addressing
//            V v,
//            C container, int index,      // Arguments for default implementation
//            StoreVectorOperation<C, V> defaultImpl)

bool LibraryCallKit::inline_vector_mem_operation(bool is_store) {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(2))->isa_int();

  if (vector_klass == NULL || elem_klass == NULL || vlen == NULL ||
      vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();

  // TODO When mask usage is supported, VecMaskNotUsed needs to be VecMaskUseLoad.
  if (!arch_supports_vector(is_store ? Op_StoreVector : Op_LoadVector, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s ismask=no",
                    is_store, is_store ? "store" : "load",
                    num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  bool is_mask = is_vector_mask(vbox_klass);

  Node* base = argument(3);
  Node* offset = ConvL2X(argument(4));

  // Save state and restore on bailout
  uint old_sp = sp();
  SafePointNode* old_map = clone_map();

  Node* addr = make_unsafe_address(base, offset, (is_mask ? T_BOOLEAN : elem_bt), true);

  // The memory barrier checks are based on ones for unsafe access.
  // This is not 1-1 implementation.
  const Type *const base_type = gvn().type(base);

  const TypePtr *addr_type = gvn().type(addr)->isa_ptr();
  const TypeAryPtr* arr_type = addr_type->isa_aryptr();

  const bool in_native = TypePtr::NULL_PTR == base_type; // base always null
  const bool in_heap   = !TypePtr::NULL_PTR->higher_equal(base_type); // base never null

  const bool is_mixed_access = !in_heap && !in_native;

  const bool is_mismatched_access = in_heap && (addr_type->isa_aryptr() == NULL);

  const bool needs_cpu_membar = is_mixed_access || is_mismatched_access;

  // Now handle special case where load/store happens from/to byte array but element type is not byte.
  bool using_byte_array = arr_type != NULL && arr_type->elem()->array_element_basic_type() == T_BYTE && elem_bt != T_BYTE;
  // Handle loading masks.
  // If there is no consistency between array and vector element types, it must be special byte array case or loading masks
  if (arr_type != NULL && !using_byte_array && !is_mask && !elem_consistent_with_arr(elem_bt, arr_type)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s atype=%s ismask=no",
                    is_store, is_store ? "store" : "load",
                    num_elem, type2name(elem_bt), type2name(arr_type->elem()->array_element_basic_type()));
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }
  // Since we are using byte array, we need to double check that the byte operations are supported by backend.
  if (using_byte_array) {
    int byte_num_elem = num_elem * type2aelembytes(elem_bt);
    if (!arch_supports_vector(is_store ? Op_StoreVector : Op_LoadVector, byte_num_elem, T_BYTE, VecMaskNotUsed)
        || !arch_supports_vector(Op_VectorReinterpret, byte_num_elem, T_BYTE, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d*8 etype=%s/8 ismask=no",
                      is_store, is_store ? "store" : "load",
                      byte_num_elem, type2name(elem_bt));
      }
      set_map(old_map);
      set_sp(old_sp);
      return false; // not supported
    }
  }
  if (is_mask) {
    if (!arch_supports_vector(Op_LoadVector, num_elem, T_BOOLEAN, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s/mask vlen=%d etype=bit ismask=no",
                      is_store, is_store ? "store" : "load",
                      num_elem);
      }
      set_map(old_map);
      set_sp(old_sp);
      return false; // not supported
    }
    if (!is_store) {
      if (!arch_supports_vector(Op_LoadVector, num_elem, elem_bt, VecMaskUseLoad)) {
        set_map(old_map);
        set_sp(old_sp);
        return false; // not supported
      }
    } else {
      if (!arch_supports_vector(Op_StoreVector, num_elem, elem_bt, VecMaskUseStore)) {
        set_map(old_map);
        set_sp(old_sp);
        return false; // not supported
      }
    }
  }

  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  if (needs_cpu_membar) {
    insert_mem_bar(Op_MemBarCPUOrder);
  }

  if (is_store) {
    Node* val = unbox_vector(argument(6), vbox_type, elem_bt, num_elem);
    if (val == NULL) {
      set_map(old_map);
      set_sp(old_sp);
      return false; // operand unboxing failed
    }
    set_all_memory(reset_memory());

    // In case the store needs to happen to byte array, reinterpret the incoming vector to byte vector.
    int store_num_elem = num_elem;
    if (using_byte_array) {
      store_num_elem = num_elem * type2aelembytes(elem_bt);
      const TypeVect* to_vect_type = TypeVect::make(T_BYTE, store_num_elem);
      val = gvn().transform(new VectorReinterpretNode(val, val->bottom_type()->is_vect(), to_vect_type));
    }

    Node* vstore = gvn().transform(StoreVectorNode::make(0, control(), memory(addr), addr, addr_type, val, store_num_elem));
    set_memory(vstore, addr_type);
  } else {
    // When using byte array, we need to load as byte then reinterpret the value. Otherwise, do a simple vector load.
    Node* vload = NULL;
    if (using_byte_array) {
      int load_num_elem = num_elem * type2aelembytes(elem_bt);
      vload = gvn().transform(LoadVectorNode::make(0, control(), memory(addr), addr, addr_type, load_num_elem, T_BYTE));
      const TypeVect* to_vect_type = TypeVect::make(elem_bt, num_elem);
      vload = gvn().transform(new VectorReinterpretNode(vload, vload->bottom_type()->is_vect(), to_vect_type));
    } else {
      // Special handle for masks
      if (is_mask) {
        vload = gvn().transform(LoadVectorNode::make(0, control(), memory(addr), addr, addr_type, num_elem, T_BOOLEAN));
        vload = gvn().transform(new VectorLoadMaskNode(vload, TypeVect::makemask(elem_bt, num_elem)));
      } else {
        vload = gvn().transform(LoadVectorNode::make(0, control(), memory(addr), addr, addr_type, num_elem, elem_bt));
      }
    }
    Node* box = box_vector(vload, vbox_type, elem_bt, num_elem);
    set_result(box);
  }

  old_map->destruct(&_gvn);

  if (needs_cpu_membar) {
    insert_mem_bar(Op_MemBarCPUOrder);
  }

  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <C,
//  V extends Vector<?>,
//  E,
//  S extends VectorSpecies<E>,
//  M extends VectorMask<E>>
// V loadMasked(Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType,
//              int length, Object base, long offset, M m,
//              C container, int index, S s,  // Arguments for default implementation
//              LoadVectorMaskedOperation<C, V, S, M> defaultImpl) {
//
// public static
// <C,
//  V extends Vector<E>,
//  M extends VectorMask<E>,
//  E>
// void storeMasked(Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType,
//                  int length, Object base, long offset,
//                  V v, M m,
//                  C container, int index,  // Arguments for default implementation
//                  StoreVectorMaskedOperation<C, V, M, E> defaultImpl) {
//
bool LibraryCallKit::inline_vector_mem_masked_operation(bool is_store) {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(3))->isa_int();

  if (vector_klass == NULL || mask_klass == NULL || elem_klass == NULL || vlen == NULL ||
      vector_klass->const_oop() == NULL || mask_klass->const_oop() == NULL ||
      elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s mclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  if (!is_klass_initialized(mask_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** mask klass argument not initialized");
    }
    return false;
  }

  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }

  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();

  Node* base = argument(4);
  Node* offset = ConvL2X(argument(5));

  // Save state and restore on bailout
  uint old_sp = sp();
  SafePointNode* old_map = clone_map();

  Node* addr = make_unsafe_address(base, offset, elem_bt, true);
  const TypePtr *addr_type = gvn().type(addr)->isa_ptr();
  const TypeAryPtr* arr_type = addr_type->isa_aryptr();

  // Now handle special case where load/store happens from/to byte array but element type is not byte.
  bool using_byte_array = arr_type != NULL && arr_type->elem()->array_element_basic_type() == T_BYTE && elem_bt != T_BYTE;
  // If there is no consistency between array and vector element types, it must be special byte array case
  if (arr_type != NULL && !using_byte_array && !elem_consistent_with_arr(elem_bt, arr_type)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s atype=%s",
                    is_store, is_store ? "storeMasked" : "loadMasked",
                    num_elem, type2name(elem_bt), type2name(arr_type->elem()->array_element_basic_type()));
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  int mem_num_elem = using_byte_array ? num_elem * type2aelembytes(elem_bt) : num_elem;
  BasicType mem_elem_bt = using_byte_array ? T_BYTE : elem_bt;
  bool use_predicate = arch_supports_vector(is_store ? Op_StoreVectorMasked : Op_LoadVectorMasked,
                                            mem_num_elem, mem_elem_bt,
                                            (VectorMaskUseType) (VecMaskUseLoad | VecMaskUsePred));
  // Masked vector store operation needs the architecture predicate feature. We need to check
  // whether the predicated vector operation is supported by backend.
  if (is_store && !use_predicate) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: op=storeMasked vlen=%d etype=%s using_byte_array=%d",
                    num_elem, type2name(elem_bt), using_byte_array ? 1 : 0);
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  // This only happens for masked vector load. If predicate is not supported, then check whether
  // the normal vector load and blend operations are supported by backend.
  if (!use_predicate && (!arch_supports_vector(Op_LoadVector, mem_num_elem, mem_elem_bt, VecMaskNotUsed) ||
      !arch_supports_vector(Op_VectorBlend, mem_num_elem, mem_elem_bt, VecMaskUseLoad))) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: op=loadMasked vlen=%d etype=%s using_byte_array=%d",
                    num_elem, type2name(elem_bt), using_byte_array ? 1 : 0);
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  // Since we are using byte array, we need to double check that the vector reinterpret operation
  // with byte type is supported by backend.
  if (using_byte_array) {
    if (!arch_supports_vector(Op_VectorReinterpret, mem_num_elem, T_BYTE, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s using_byte_array=1",
                      is_store, is_store ? "storeMasked" : "loadMasked",
                      num_elem, type2name(elem_bt));
      }
      set_map(old_map);
      set_sp(old_sp);
      return false;
    }
  }

  // Since it needs to unbox the mask, we need to double check that the related load operations
  // for mask are supported by backend.
  if (!arch_supports_vector(Op_LoadVector, num_elem, elem_bt, VecMaskUseLoad)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s",
                      is_store, is_store ? "storeMasked" : "loadMasked",
                      num_elem, type2name(elem_bt));
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  // Can base be NULL? Otherwise, always on-heap access.
  bool can_access_non_heap = TypePtr::NULL_PTR->higher_equal(gvn().type(base));
  if (can_access_non_heap) {
    insert_mem_bar(Op_MemBarCPUOrder);
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
  assert(!is_vector_mask(vbox_klass) && is_vector_mask(mbox_klass), "Invalid class type");
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);
  const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);

  Node* mask = unbox_vector(is_store ? argument(8) : argument(7), mbox_type, elem_bt, num_elem);
  if (mask == NULL) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** unbox failed mask=%s",
                    is_store ? NodeClassNames[argument(8)->Opcode()]
                             : NodeClassNames[argument(7)->Opcode()]);
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  if (is_store) {
    Node* val = unbox_vector(argument(7), vbox_type, elem_bt, num_elem);
    if (val == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed vector=%s",
                      NodeClassNames[argument(7)->Opcode()]);
      }
      set_map(old_map);
      set_sp(old_sp);
      return false; // operand unboxing failed
    }
    set_all_memory(reset_memory());

    if (using_byte_array) {
      // Reinterpret the incoming vector to byte vector.
      const TypeVect* to_vect_type = TypeVect::make(mem_elem_bt, mem_num_elem);
      val = gvn().transform(new VectorReinterpretNode(val, val->bottom_type()->is_vect(), to_vect_type));
      // Reinterpret the vector mask to byte type.
      const TypeVect* from_mask_type = TypeVect::makemask(elem_bt, num_elem);
      const TypeVect* to_mask_type = TypeVect::makemask(mem_elem_bt, mem_num_elem);
      mask = gvn().transform(new VectorReinterpretNode(mask, from_mask_type, to_mask_type));
    }
    Node* vstore = gvn().transform(new StoreVectorMaskedNode(control(), memory(addr), addr, val, addr_type, mask));
    set_memory(vstore, addr_type);
  } else {
    Node* vload = NULL;

    if (using_byte_array) {
      // Reinterpret the vector mask to byte type.
      const TypeVect* from_mask_type = TypeVect::makemask(elem_bt, num_elem);
      const TypeVect* to_mask_type = TypeVect::makemask(mem_elem_bt, mem_num_elem);
      mask = gvn().transform(new VectorReinterpretNode(mask, from_mask_type, to_mask_type));
    }

    if (use_predicate) {
      // Generate masked load vector node if predicate feature is supported.
      const TypeVect* vt = TypeVect::make(mem_elem_bt, mem_num_elem);
      vload = gvn().transform(new LoadVectorMaskedNode(control(), memory(addr), addr, addr_type, vt, mask));
    } else {
      // Use the vector blend to implement the masked load vector. The biased elements are zeros.
      Node* zero = gvn().transform(gvn().zerocon(mem_elem_bt));
      zero = gvn().transform(VectorNode::scalar2vector(zero, mem_num_elem, Type::get_const_basic_type(mem_elem_bt)));
      vload = gvn().transform(LoadVectorNode::make(0, control(), memory(addr), addr, addr_type, mem_num_elem, mem_elem_bt));
      vload = gvn().transform(new VectorBlendNode(zero, vload, mask));
    }

    if (using_byte_array) {
      const TypeVect* to_vect_type = TypeVect::make(elem_bt, num_elem);
      vload = gvn().transform(new VectorReinterpretNode(vload, vload->bottom_type()->is_vect(), to_vect_type));
    }

    Node* box = box_vector(vload, vbox_type, elem_bt, num_elem);
    set_result(box);
  }

  old_map->destruct(&_gvn);

  if (can_access_non_heap) {
    insert_mem_bar(Op_MemBarCPUOrder);
  }

  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// <C,
//  V extends Vector<?>,
//  W extends Vector<Integer>,
//  S extends VectorSpecies<E>,
//  M extends VectorMask<E>,
//  E>
// V loadWithMap(Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType, int length,
//               Class<? extends Vector<Integer>> vectorIndexClass,
//               Object base, long offset, // Unsafe addressing
//               W index_vector, M m,
//               C container, int index, int[] indexMap, int indexM, S s, // Arguments for default implementation
//               LoadVectorOperationWithMap<C, V, E, S, M> defaultImpl)
//
//  <C,
//   V extends Vector<E>,
//   W extends Vector<Integer>,
//   M extends VectorMask<E>,
//   E>
//  void storeWithMap(Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType,
//                    int length, Class<? extends Vector<Integer>> vectorIndexClass, Object base, long offset,    // Unsafe addressing
//                    W index_vector, V v, M m,
//                    C container, int index, int[] indexMap, int indexM, // Arguments for default implementation
//                    StoreVectorOperationWithMap<C, V, M, E> defaultImpl)
//
bool LibraryCallKit::inline_vector_gather_scatter(bool is_scatter) {
  const TypeInstPtr* vector_klass     = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* mask_klass       = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass       = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen             = gvn().type(argument(3))->isa_int();
  const TypeInstPtr* vector_idx_klass = gvn().type(argument(4))->isa_instptr();

  if (vector_klass == NULL || elem_klass == NULL || vector_idx_klass == NULL || vlen == NULL ||
      vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || vector_idx_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s etype=%s vlen=%s viclass=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }

  if (!is_klass_initialized(vector_klass) || !is_klass_initialized(vector_idx_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }

  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();

  const Type* vmask_type = gvn().type(is_scatter ? argument(10) : argument(9));
  bool is_masked_op = vmask_type != TypePtr::NULL_PTR;
  if (is_masked_op) {
    if (mask_klass == NULL || mask_klass->const_oop() == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** missing constant: maskclass=%s", NodeClassNames[argument(1)->Opcode()]);
      }
      return false; // not enough info for intrinsification
    }

    if (!is_klass_initialized(mask_klass)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** mask klass argument not initialized");
      }
      return false;
    }

    if (vmask_type->maybe_null()) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** null mask values are not allowed for masked op");
      }
      return false;
    }

    // Check whether the predicated gather/scatter node is supported by architecture.
    if (!arch_supports_vector(is_scatter ? Op_StoreVectorScatterMasked : Op_LoadVectorGatherMasked, num_elem, elem_bt,
                              (VectorMaskUseType) (VecMaskUseLoad | VecMaskUsePred))) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s is_masked_op=1",
                      is_scatter, is_scatter ? "scatterMasked" : "gatherMasked",
                      num_elem, type2name(elem_bt));
      }
      return false; // not supported
    }
  } else {
    // Check whether the normal gather/scatter node is supported for non-masked operation.
    if (!arch_supports_vector(is_scatter ? Op_StoreVectorScatter : Op_LoadVectorGather, num_elem, elem_bt, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s is_masked_op=0",
                      is_scatter, is_scatter ? "scatter" : "gather",
                      num_elem, type2name(elem_bt));
      }
      return false; // not supported
    }
  }

  // Check that the vector holding indices is supported by architecture
  if (!arch_supports_vector(Op_LoadVector, num_elem, T_INT, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=%d op=%s/loadindex vlen=%d etype=int is_masked_op=%d",
                      is_scatter, is_scatter ? "scatter" : "gather",
                      num_elem, is_masked_op ? 1 : 0);
      }
      return false; // not supported
  }

  Node* base = argument(5);
  Node* offset = ConvL2X(argument(6));

  // Save state and restore on bailout
  uint old_sp = sp();
  SafePointNode* old_map = clone_map();

  Node* addr = make_unsafe_address(base, offset, elem_bt, true);

  const TypePtr *addr_type = gvn().type(addr)->isa_ptr();
  const TypeAryPtr* arr_type = addr_type->isa_aryptr();

  // The array must be consistent with vector type
  if (arr_type == NULL || (arr_type != NULL && !elem_consistent_with_arr(elem_bt, arr_type))) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=%d op=%s vlen=%d etype=%s atype=%s ismask=no",
                    is_scatter, is_scatter ? "scatter" : "gather",
                    num_elem, type2name(elem_bt), type2name(arr_type->elem()->array_element_basic_type()));
    }
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);
  ciKlass* vbox_idx_klass = vector_idx_klass->const_oop()->as_instance()->java_lang_Class_klass();
  if (vbox_idx_klass == NULL) {
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  const TypeInstPtr* vbox_idx_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_idx_klass);
  Node* index_vect = unbox_vector(argument(8), vbox_idx_type, T_INT, num_elem);
  if (index_vect == NULL) {
    set_map(old_map);
    set_sp(old_sp);
    return false;
  }

  Node* mask = NULL;
  if (is_masked_op) {
    ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
    const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
    mask = unbox_vector(is_scatter ? argument(10) : argument(9), mbox_type, elem_bt, num_elem);
    if (mask == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed mask=%s",
                    is_scatter ? NodeClassNames[argument(10)->Opcode()]
                               : NodeClassNames[argument(9)->Opcode()]);
      }
      set_map(old_map);
      set_sp(old_sp);
      return false;
    }
  }

  const TypeVect* vector_type = TypeVect::make(elem_bt, num_elem);
  if (is_scatter) {
    Node* val = unbox_vector(argument(9), vbox_type, elem_bt, num_elem);
    if (val == NULL) {
      set_map(old_map);
      set_sp(old_sp);
      return false; // operand unboxing failed
    }
    set_all_memory(reset_memory());

    Node* vstore = NULL;
    if (mask != NULL) {
      vstore = gvn().transform(new StoreVectorScatterMaskedNode(control(), memory(addr), addr, addr_type, val, index_vect, mask));
    } else {
      vstore = gvn().transform(new StoreVectorScatterNode(control(), memory(addr), addr, addr_type, val, index_vect));
    }
    set_memory(vstore, addr_type);
  } else {
    Node* vload = NULL;
    if (mask != NULL) {
      vload = gvn().transform(new LoadVectorGatherMaskedNode(control(), memory(addr), addr, addr_type, vector_type, index_vect, mask));
    } else {
      vload = gvn().transform(new LoadVectorGatherNode(control(), memory(addr), addr, addr_type, vector_type, index_vect));
    }
    Node* box = box_vector(vload, vbox_type, elem_bt, num_elem);
    set_result(box);
  }

  old_map->destruct(&_gvn);

  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <V extends Vector<E>,
//  M extends VectorMask<E>,
//  E>
// long reductionCoerced(int oprId, Class<? extends V> vectorClass, Class<? extends M> maskClass,
//                       Class<E> elementType, int length, V v, M m,
//                       ReductionOperation<V, M> defaultImpl)
bool LibraryCallKit::inline_vector_reduction() {
  const TypeInt*     opr          = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* vector_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(3))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(4))->isa_int();

  if (opr == NULL || vector_klass == NULL || elem_klass == NULL || vlen == NULL ||
      !opr->is_con() || vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: opr=%s vclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }

  const Type* vmask_type = gvn().type(argument(6));
  bool is_masked_op = vmask_type != TypePtr::NULL_PTR;
  if (is_masked_op) {
    if (mask_klass == NULL || mask_klass->const_oop() == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** missing constant: maskclass=%s", NodeClassNames[argument(2)->Opcode()]);
      }
      return false; // not enough info for intrinsification
    }

    if (!is_klass_initialized(mask_klass)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** mask klass argument not initialized");
      }
      return false;
    }

    if (vmask_type->maybe_null()) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** null mask values are not allowed for masked op");
      }
      return false;
    }
  }

  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  int opc  = VectorSupport::vop2ideal(opr->get_con(), elem_bt);
  int sopc = ReductionNode::opcode(opc, elem_bt);

  // When using mask, mask use type needs to be VecMaskUseLoad.
  if (!arch_supports_vector(sopc, num_elem, elem_bt, is_masked_op ? VecMaskUseLoad : VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=%d/reduce vlen=%d etype=%s is_masked_op=%d",
                    sopc, num_elem, type2name(elem_bt), is_masked_op ? 1 : 0);
    }
    return false;
  }

  // Return true if current platform has implemented the masked operation with predicate feature.
  bool use_predicate = is_masked_op && arch_supports_vector(sopc, num_elem, elem_bt, VecMaskUsePred);
  if (is_masked_op && !use_predicate && !arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=%d/reduce vlen=%d etype=%s is_masked_op=1",
                    sopc, num_elem, type2name(elem_bt));
    }
    return false;
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  Node* opd = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  if (opd == NULL) {
    return false; // operand unboxing failed
  }

  Node* mask = NULL;
  if (is_masked_op) {
    ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
    assert(is_vector_mask(mbox_klass), "argument(2) should be a mask class");
    const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
    mask = unbox_vector(argument(6), mbox_type, elem_bt, num_elem);
    if (mask == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed mask=%s",
                      NodeClassNames[argument(6)->Opcode()]);
      }
      return false;
    }
  }

  Node* init = ReductionNode::make_reduction_input(gvn(), opc, elem_bt);
  Node* value = NULL;
  if (mask == NULL) {
    assert(!is_masked_op, "Masked op needs the mask value never null");
    value = ReductionNode::make(opc, NULL, init, opd, elem_bt);
  } else {
    if (use_predicate) {
      value = ReductionNode::make(opc, NULL, init, opd, elem_bt);
      value->add_req(mask);
      value->add_flag(Node::Flag_is_predicated_vector);
    } else {
      Node* reduce_identity = gvn().transform(VectorNode::scalar2vector(init, num_elem, Type::get_const_basic_type(elem_bt)));
      value = gvn().transform(new VectorBlendNode(reduce_identity, opd, mask));
      value = ReductionNode::make(opc, NULL, init, value, elem_bt);
    }
  }
  value = gvn().transform(value);

  Node* bits = NULL;
  switch (elem_bt) {
    case T_BYTE:
    case T_SHORT:
    case T_INT: {
      bits = gvn().transform(new ConvI2LNode(value));
      break;
    }
    case T_FLOAT: {
      value = gvn().transform(new MoveF2INode(value));
      bits  = gvn().transform(new ConvI2LNode(value));
      break;
    }
    case T_DOUBLE: {
      bits = gvn().transform(new MoveD2LNode(value));
      break;
    }
    case T_LONG: {
      bits = value; // no conversion needed
      break;
    }
    default: fatal("%s", type2name(elem_bt));
  }
  set_result(bits);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static <V> boolean test(int cond, Class<?> vectorClass, Class<?> elementType, int vlen,
//                                V v1, V v2,
//                                BiFunction<V, V, Boolean> defaultImpl)
//
bool LibraryCallKit::inline_vector_test() {
  const TypeInt*     cond         = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* vector_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(3))->isa_int();

  if (cond == NULL || vector_klass == NULL || elem_klass == NULL || vlen == NULL ||
      !cond->is_con() || vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: cond=%s vclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  BoolTest::mask booltest = (BoolTest::mask)cond->get_con();
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  if (!arch_supports_vector(Op_VectorTest, num_elem, elem_bt, is_vector_mask(vbox_klass) ? VecMaskUseLoad : VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=2 op=test/%d vlen=%d etype=%s ismask=%d",
                    cond->get_con(), num_elem, type2name(elem_bt),
                    is_vector_mask(vbox_klass));
    }
    return false;
  }

  Node* opd1 = unbox_vector(argument(4), vbox_type, elem_bt, num_elem);
  Node* opd2 = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  if (opd1 == NULL || opd2 == NULL) {
    return false; // operand unboxing failed
  }
  Node* test = new VectorTestNode(opd1, opd2, booltest);
  test = gvn().transform(test);

  set_result(test);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <V extends Vector<E>,
//  M extends VectorMask<E>,
//  E>
// V blend(Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType, int vlen,
//         V v1, V v2, M m,
//         VectorBlendOp<V, M, E> defaultImpl)
bool LibraryCallKit::inline_vector_blend() {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(3))->isa_int();

  if (mask_klass == NULL || vector_klass == NULL || elem_klass == NULL || vlen == NULL) {
    return false; // dead code
  }
  if (mask_klass->const_oop() == NULL || vector_klass->const_oop() == NULL ||
      elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s mclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass) || !is_klass_initialized(mask_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  BasicType mask_bt = elem_bt;
  int num_elem = vlen->get_con();

  if (!arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=2 op=blend vlen=%d etype=%s ismask=useload",
                    num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);

  Node* v1   = unbox_vector(argument(4), vbox_type, elem_bt, num_elem);
  Node* v2   = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  Node* mask = unbox_vector(argument(6), mbox_type, mask_bt, num_elem);

  if (v1 == NULL || v2 == NULL || mask == NULL) {
    return false; // operand unboxing failed
  }

  Node* blend = gvn().transform(new VectorBlendNode(v1, v2, mask));

  Node* box = box_vector(blend, vbox_type, elem_bt, num_elem);
  set_result(box);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

//  public static
//  <V extends Vector<E>,
//   M extends VectorMask<E>,
//   E>
//  M compare(int cond, Class<? extends V> vectorClass, Class<M> maskClass, Class<E> elementType, int vlen,
//            V v1, V v2, M m,
//            VectorCompareOp<V,M> defaultImpl)
bool LibraryCallKit::inline_vector_compare() {
  const TypeInt*     cond         = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* vector_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(3))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(4))->isa_int();

  if (cond == NULL || vector_klass == NULL || mask_klass == NULL || elem_klass == NULL || vlen == NULL) {
    return false; // dead code
  }
  if (!cond->is_con() || vector_klass->const_oop() == NULL || mask_klass->const_oop() == NULL ||
      elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: cond=%s vclass=%s mclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass) || !is_klass_initialized(mask_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }

  int num_elem = vlen->get_con();
  BasicType elem_bt = elem_type->basic_type();
  BasicType mask_bt = elem_bt;

  if ((cond->get_con() & BoolTest::unsigned_compare) != 0) {
    if (!Matcher::supports_vector_comparison_unsigned(num_elem, elem_bt)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: unsigned comparison op=comp/%d vlen=%d etype=%s ismask=usestore",
                      cond->get_con() & (BoolTest::unsigned_compare - 1), num_elem, type2name(elem_bt));
      }
      return false;
    }
  }

  if (!arch_supports_vector(Op_VectorMaskCmp, num_elem, elem_bt, VecMaskUseStore)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=2 op=comp/%d vlen=%d etype=%s ismask=usestore",
                    cond->get_con(), num_elem, type2name(elem_bt));
    }
    return false;
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);

  Node* v1 = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  Node* v2 = unbox_vector(argument(6), vbox_type, elem_bt, num_elem);

  bool is_masked_op = argument(7)->bottom_type() != TypePtr::NULL_PTR;
  Node* mask = is_masked_op ? unbox_vector(argument(7), mbox_type, elem_bt, num_elem) : NULL;
  if (is_masked_op && mask == NULL) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: mask = null arity=2 op=comp/%d vlen=%d etype=%s ismask=usestore is_masked_op=1",
                    cond->get_con(), num_elem, type2name(elem_bt));
    }
    return false;
  }

  bool use_predicate = is_masked_op && arch_supports_vector(Op_VectorMaskCmp, num_elem, elem_bt, VecMaskUsePred);
  if (is_masked_op && !use_predicate && !arch_supports_vector(Op_AndV, num_elem, elem_bt, VecMaskUseLoad)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=2 op=comp/%d vlen=%d etype=%s ismask=usestore is_masked_op=1",
                    cond->get_con(), num_elem, type2name(elem_bt));
    }
    return false;
  }

  if (v1 == NULL || v2 == NULL) {
    return false; // operand unboxing failed
  }
  BoolTest::mask pred = (BoolTest::mask)cond->get_con();
  ConINode* pred_node = (ConINode*)gvn().makecon(cond);

  const TypeVect* vmask_type = TypeVect::makemask(mask_bt, num_elem);
  Node* operation = new VectorMaskCmpNode(pred, v1, v2, pred_node, vmask_type);

  if (is_masked_op) {
    if (use_predicate) {
      operation->add_req(mask);
      operation->add_flag(Node::Flag_is_predicated_vector);
    } else {
      operation = gvn().transform(operation);
      operation = VectorNode::make(Op_AndV, operation, mask, vmask_type);
    }
  }

  operation = gvn().transform(operation);

  Node* box = box_vector(operation, mbox_type, mask_bt, num_elem);
  set_result(box);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static
// <V extends Vector<E>,
//  Sh extends VectorShuffle<E>,
//  M extends VectorMask<E>,
//  E>
// V rearrangeOp(Class<? extends V> vectorClass, Class<Sh> shuffleClass, Class<M> maskClass, Class<E> elementType, int vlen,
//               V v1, Sh sh, M m,
//               VectorRearrangeOp<V, Sh, M, E> defaultImpl)
bool LibraryCallKit::inline_vector_rearrange() {
  const TypeInstPtr* vector_klass  = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* shuffle_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* mask_klass    = gvn().type(argument(2))->isa_instptr();
  const TypeInstPtr* elem_klass    = gvn().type(argument(3))->isa_instptr();
  const TypeInt*     vlen          = gvn().type(argument(4))->isa_int();

  if (vector_klass == NULL  || shuffle_klass == NULL ||  elem_klass == NULL || vlen == NULL) {
    return false; // dead code
  }
  if (shuffle_klass->const_oop() == NULL ||
      vector_klass->const_oop()  == NULL ||
      elem_klass->const_oop()    == NULL ||
      !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s sclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)  ||
      !is_klass_initialized(shuffle_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  BasicType shuffle_bt = elem_bt;
  int num_elem = vlen->get_con();

  if (!arch_supports_vector(Op_VectorLoadShuffle, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=0 op=load/shuffle vlen=%d etype=%s ismask=no",
                    num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }

  bool is_masked_op = argument(7)->bottom_type() != TypePtr::NULL_PTR;
  bool use_predicate = is_masked_op;
  if (is_masked_op &&
      (mask_klass == NULL ||
       mask_klass->const_oop() == NULL ||
       !is_klass_initialized(mask_klass))) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** mask_klass argument not initialized");
    }
  }
  VectorMaskUseType checkFlags = (VectorMaskUseType)(is_masked_op ? (VecMaskUseLoad | VecMaskUsePred) : VecMaskNotUsed);
  if (!arch_supports_vector(Op_VectorRearrange, num_elem, elem_bt, checkFlags)) {
    use_predicate = false;
    if(!is_masked_op ||
       (!arch_supports_vector(Op_VectorRearrange, num_elem, elem_bt, VecMaskNotUsed) ||
        !arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad)     ||
        !arch_supports_vector(VectorNode::replicate_opcode(elem_bt), num_elem, elem_bt, VecMaskNotUsed))) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=2 op=shuffle/rearrange vlen=%d etype=%s ismask=no",
                      num_elem, type2name(elem_bt));
      }
      return false; // not supported
    }
  }
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  ciKlass* shbox_klass = shuffle_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* shbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, shbox_klass);

  Node* v1 = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  Node* shuffle = unbox_vector(argument(6), shbox_type, shuffle_bt, num_elem);

  if (v1 == NULL || shuffle == NULL) {
    return false; // operand unboxing failed
  }

  Node* mask = NULL;
  if (is_masked_op) {
    ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
    const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
    mask = unbox_vector(argument(7), mbox_type, elem_bt, num_elem);
    if (mask == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=3 op=shuffle/rearrange vlen=%d etype=%s ismask=useload is_masked_op=1",
                      num_elem, type2name(elem_bt));
      }
      return false;
    }
  }

  Node* rearrange = new VectorRearrangeNode(v1, shuffle);
  if (is_masked_op) {
    if (use_predicate) {
      rearrange->add_req(mask);
      rearrange->add_flag(Node::Flag_is_predicated_vector);
    } else {
      const TypeVect* vt = v1->bottom_type()->is_vect();
      rearrange = gvn().transform(rearrange);
      Node* zero = gvn().makecon(Type::get_zero_type(elem_bt));
      Node* zerovec = gvn().transform(VectorNode::scalar2vector(zero, num_elem, Type::get_const_basic_type(elem_bt)));
      rearrange = new VectorBlendNode(zerovec, rearrange, mask);
    }
  }
  rearrange = gvn().transform(rearrange);

  Node* box = box_vector(rearrange, vbox_type, elem_bt, num_elem);
  set_result(box);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

static address get_svml_address(int vop, int bits, BasicType bt, char* name_ptr, int name_len) {
  address addr = NULL;
  assert(UseVectorStubs, "sanity");
  assert(name_ptr != NULL, "unexpected");
  assert((vop >= VectorSupport::VECTOR_OP_SVML_START) && (vop <= VectorSupport::VECTOR_OP_SVML_END), "unexpected");
  int op = vop - VectorSupport::VECTOR_OP_SVML_START;

  switch(bits) {
    case 64:  //fallthough
    case 128: //fallthough
    case 256: //fallthough
    case 512:
      if (bt == T_FLOAT) {
        snprintf(name_ptr, name_len, "vector_%s_float%d", VectorSupport::svmlname[op], bits);
        addr = StubRoutines::_vector_f_math[exact_log2(bits/64)][op];
      } else {
        assert(bt == T_DOUBLE, "must be FP type only");
        snprintf(name_ptr, name_len, "vector_%s_double%d", VectorSupport::svmlname[op], bits);
        addr = StubRoutines::_vector_d_math[exact_log2(bits/64)][op];
      }
      break;
    default:
      snprintf(name_ptr, name_len, "invalid");
      addr = NULL;
      Unimplemented();
      break;
  }

  return addr;
}

Node* LibraryCallKit::gen_call_to_svml(int vector_api_op_id, BasicType bt, int num_elem, Node* opd1, Node* opd2) {
  assert(UseVectorStubs, "sanity");
  assert(vector_api_op_id >= VectorSupport::VECTOR_OP_SVML_START && vector_api_op_id <= VectorSupport::VECTOR_OP_SVML_END, "need valid op id");
  assert(opd1 != NULL, "must not be null");
  const TypeVect* vt = TypeVect::make(bt, num_elem);
  const TypeFunc* call_type = OptoRuntime::Math_Vector_Vector_Type(opd2 != NULL ? 2 : 1, vt, vt);
  char name[100] = "";

  // Get address for svml method.
  address addr = get_svml_address(vector_api_op_id, vt->length_in_bytes() * BitsPerByte, bt, name, 100);

  if (addr == NULL) {
    return NULL;
  }

  assert(name != NULL, "name must not be null");
  Node* operation = make_runtime_call(RC_VECTOR,
                                      call_type,
                                      addr,
                                      name,
                                      TypePtr::BOTTOM,
                                      opd1,
                                      opd2);
  return gvn().transform(new ProjNode(gvn().transform(operation), TypeFunc::Parms));
}

//  public static
//  <V extends Vector<E>,
//   M extends VectorMask<E>,
//   E>
//  V broadcastInt(int opr, Class<? extends V> vectorClass, Class<? extends M> maskClass,
//                 Class<E> elementType, int length,
//                 V v, int n, M m,
//                 VectorBroadcastIntOp<V, M> defaultImpl)
bool LibraryCallKit::inline_vector_broadcast_int() {
  const TypeInt*     opr          = gvn().type(argument(0))->isa_int();
  const TypeInstPtr* vector_klass = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* mask_klass   = gvn().type(argument(2))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(3))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(4))->isa_int();

  if (opr == NULL || vector_klass == NULL || elem_klass == NULL || vlen == NULL) {
    return false; // dead code
  }
  if (!opr->is_con() || vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: opr=%s vclass=%s etype=%s vlen=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  const Type* vmask_type = gvn().type(argument(7));
  bool is_masked_op = vmask_type != TypePtr::NULL_PTR;
  if (is_masked_op) {
    if (mask_klass == NULL || mask_klass->const_oop() == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** missing constant: maskclass=%s", NodeClassNames[argument(2)->Opcode()]);
      }
      return false; // not enough info for intrinsification
    }

    if (!is_klass_initialized(mask_klass)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** mask klass argument not initialized");
      }
      return false;
    }

    if (vmask_type->maybe_null()) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** null mask values are not allowed for masked op");
      }
      return false;
    }
  }

  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }

  int num_elem = vlen->get_con();
  BasicType elem_bt = elem_type->basic_type();
  int opc = VectorSupport::vop2ideal(opr->get_con(), elem_bt);

  bool is_shift  = VectorNode::is_shift_opcode(opc);
  bool is_rotate = VectorNode::is_rotate_opcode(opc);

  if (opc == 0 || (!is_shift && !is_rotate)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** operation not supported: op=%d bt=%s", opr->get_con(), type2name(elem_bt));
    }
    return false; // operation not supported
  }

  int sopc = VectorNode::opcode(opc, elem_bt);
  if (sopc == 0) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** operation not supported: opc=%s bt=%s", NodeClassNames[opc], type2name(elem_bt));
    }
    return false; // operation not supported
  }

  Node* cnt  = argument(6);
  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);
  const TypeInt* cnt_type = cnt->bottom_type()->isa_int();

  // If CPU supports vector constant rotate instructions pass it directly
  bool is_const_rotate = is_rotate && cnt_type && cnt_type->is_con() &&
                         Matcher::supports_vector_constant_rotates(cnt_type->get_con());
  bool has_scalar_args = is_rotate ? !is_const_rotate : true;

  VectorMaskUseType checkFlags = (VectorMaskUseType)(is_masked_op ? (VecMaskUseLoad | VecMaskUsePred) : VecMaskNotUsed);
  bool use_predicate = is_masked_op;

  if (!arch_supports_vector(sopc, num_elem, elem_bt, checkFlags, has_scalar_args)) {
    use_predicate = false;
    if (!is_masked_op ||
        (!arch_supports_vector(sopc, num_elem, elem_bt, VecMaskNotUsed, has_scalar_args) ||
         !arch_supports_vector(Op_VectorBlend, num_elem, elem_bt, VecMaskUseLoad))) {

      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=0 op=int/%d vlen=%d etype=%s is_masked_op=%d",
                      sopc, num_elem, type2name(elem_bt), is_masked_op ? 1 : 0);
      }
      return false; // not supported
    }
  }

  Node* opd1 = unbox_vector(argument(5), vbox_type, elem_bt, num_elem);
  Node* opd2 = NULL;
  if (is_shift) {
    opd2 = vector_shift_count(cnt, opc, elem_bt, num_elem);
  } else {
    assert(is_rotate, "unexpected operation");
    if (!is_const_rotate) {
      const Type * type_bt = Type::get_const_basic_type(elem_bt);
      cnt = elem_bt == T_LONG ? gvn().transform(new ConvI2LNode(cnt)) : cnt;
      opd2 = gvn().transform(VectorNode::scalar2vector(cnt, num_elem, type_bt));
    } else {
      // Constant shift value.
      opd2 = cnt;
    }
  }

  if (opd1 == NULL || opd2 == NULL) {
    return false;
  }

  Node* mask = NULL;
  if (is_masked_op) {
    ciKlass* mbox_klass = mask_klass->const_oop()->as_instance()->java_lang_Class_klass();
    const TypeInstPtr* mbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, mbox_klass);
    mask = unbox_vector(argument(7), mbox_type, elem_bt, num_elem);
    if (mask == NULL) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** unbox failed mask=%s", NodeClassNames[argument(7)->Opcode()]);
      }
      return false;
    }
  }

  Node* operation = VectorNode::make(opc, opd1, opd2, num_elem, elem_bt);
  if (is_masked_op && mask != NULL) {
    if (use_predicate) {
      operation->add_req(mask);
      operation->add_flag(Node::Flag_is_predicated_vector);
    } else {
      operation = gvn().transform(operation);
      operation = new VectorBlendNode(opd1, operation, mask);
    }
  }
  operation = gvn().transform(operation);
  Node* vbox = box_vector(operation, vbox_type, elem_bt, num_elem);
  set_result(vbox);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

// public static <VOUT extends VectorPayload,
//                 VIN extends VectorPayload,
//                   S extends VectorSpecies>
// VOUT convert(int oprId,
//           Class<?> fromVectorClass, Class<?> fromElementType, int fromVLen,
//           Class<?>   toVectorClass, Class<?>   toElementType, int   toVLen,
//           VIN v, S s,
//           VectorConvertOp<VOUT, VIN, S> defaultImpl)
//
bool LibraryCallKit::inline_vector_convert() {
  const TypeInt*     opr               = gvn().type(argument(0))->isa_int();

  const TypeInstPtr* vector_klass_from = gvn().type(argument(1))->isa_instptr();
  const TypeInstPtr* elem_klass_from   = gvn().type(argument(2))->isa_instptr();
  const TypeInt*     vlen_from         = gvn().type(argument(3))->isa_int();

  const TypeInstPtr* vector_klass_to   = gvn().type(argument(4))->isa_instptr();
  const TypeInstPtr* elem_klass_to     = gvn().type(argument(5))->isa_instptr();
  const TypeInt*     vlen_to           = gvn().type(argument(6))->isa_int();

  if (opr == NULL ||
      vector_klass_from == NULL || elem_klass_from == NULL || vlen_from == NULL ||
      vector_klass_to   == NULL || elem_klass_to   == NULL || vlen_to   == NULL) {
    return false; // dead code
  }
  if (!opr->is_con() ||
      vector_klass_from->const_oop() == NULL || elem_klass_from->const_oop() == NULL || !vlen_from->is_con() ||
      vector_klass_to->const_oop() == NULL || elem_klass_to->const_oop() == NULL || !vlen_to->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: opr=%s vclass_from=%s etype_from=%s vlen_from=%s vclass_to=%s etype_to=%s vlen_to=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(3)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()],
                    NodeClassNames[argument(5)->Opcode()],
                    NodeClassNames[argument(6)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass_from) || !is_klass_initialized(vector_klass_to)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }

  assert(opr->get_con() == VectorSupport::VECTOR_OP_CAST  ||
         opr->get_con() == VectorSupport::VECTOR_OP_UCAST ||
         opr->get_con() == VectorSupport::VECTOR_OP_REINTERPRET, "wrong opcode");
  bool is_cast = (opr->get_con() == VectorSupport::VECTOR_OP_CAST || opr->get_con() == VectorSupport::VECTOR_OP_UCAST);
  bool is_ucast = (opr->get_con() == VectorSupport::VECTOR_OP_UCAST);

  ciKlass* vbox_klass_from = vector_klass_from->const_oop()->as_instance()->java_lang_Class_klass();
  ciKlass* vbox_klass_to = vector_klass_to->const_oop()->as_instance()->java_lang_Class_klass();
  if (is_vector_shuffle(vbox_klass_from)) {
    return false; // vector shuffles aren't supported
  }
  bool is_mask = is_vector_mask(vbox_klass_from);

  ciType* elem_type_from = elem_klass_from->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type_from->is_primitive_type()) {
    return false; // should be primitive type
  }
  BasicType elem_bt_from = elem_type_from->basic_type();
  ciType* elem_type_to = elem_klass_to->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type_to->is_primitive_type()) {
    return false; // should be primitive type
  }
  BasicType elem_bt_to = elem_type_to->basic_type();

  int num_elem_from = vlen_from->get_con();
  int num_elem_to = vlen_to->get_con();

  // Check whether we can unbox to appropriate size. Even with casting, checking for reinterpret is needed
  // since we may need to change size.
  if (!arch_supports_vector(Op_VectorReinterpret,
                            num_elem_from,
                            elem_bt_from,
                            is_mask ? VecMaskUseAll : VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=%s/1 vlen1=%d etype1=%s ismask=%d",
                    is_cast ? "cast" : "reinterpret",
                    num_elem_from, type2name(elem_bt_from), is_mask);
    }
    return false;
  }

  // Check whether we can support resizing/reinterpreting to the new size.
  if (!arch_supports_vector(Op_VectorReinterpret,
                            num_elem_to,
                            elem_bt_to,
                            is_mask ? VecMaskUseAll : VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=%s/2 vlen2=%d etype2=%s ismask=%d",
                    is_cast ? "cast" : "reinterpret",
                    num_elem_to, type2name(elem_bt_to), is_mask);
    }
    return false;
  }

  // At this point, we know that both input and output vector registers are supported
  // by the architecture. Next check if the casted type is simply to same type - which means
  // that it is actually a resize and not a cast.
  if (is_cast && elem_bt_from == elem_bt_to) {
    is_cast = false;
  }

  const TypeInstPtr* vbox_type_from = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass_from);

  Node* opd1 = unbox_vector(argument(7), vbox_type_from, elem_bt_from, num_elem_from);
  if (opd1 == NULL) {
    return false;
  }

  const TypeVect* src_type = TypeVect::make(elem_bt_from, num_elem_from, is_mask);
  const TypeVect* dst_type = TypeVect::make(elem_bt_to, num_elem_to, is_mask);

  // Safety check to prevent casting if source mask is of type vector
  // and destination mask of type predicate vector and vice-versa.
  // From X86 standpoint, this case will only arise over KNL target,
  // where certain masks (depending on the species) are either propagated
  // through a vector or predicate register.
  if (is_mask &&
      ((src_type->isa_vectmask() == NULL && dst_type->isa_vectmask()) ||
       (dst_type->isa_vectmask() == NULL && src_type->isa_vectmask()))) {
    return false;
  }

  Node* op = opd1;
  if (is_cast) {
    BasicType new_elem_bt_to = elem_bt_to;
    BasicType new_elem_bt_from = elem_bt_from;
    if (is_mask && is_floating_point_type(elem_bt_from)) {
      new_elem_bt_from = elem_bt_from == T_FLOAT ? T_INT : T_LONG;
    }
    int cast_vopc = VectorCastNode::opcode(new_elem_bt_from, !is_ucast);
    // Make sure that cast is implemented to particular type/size combination.
    if (!arch_supports_vector(cast_vopc, num_elem_to, elem_bt_to, VecMaskNotUsed)) {
      if (C->print_intrinsics()) {
        tty->print_cr("  ** not supported: arity=1 op=cast#%d/3 vlen2=%d etype2=%s ismask=%d",
                      cast_vopc,
                      num_elem_to, type2name(elem_bt_to), is_mask);
      }
      return false;
    }

    if (num_elem_from < num_elem_to) {
      // Since input and output number of elements are not consistent, we need to make sure we
      // properly size. Thus, first make a cast that retains the number of elements from source.
      int num_elem_for_cast = num_elem_from;

      // It is possible that arch does not support this intermediate vector size
      // TODO More complex logic required here to handle this corner case for the sizes.
      if (!arch_supports_vector(cast_vopc, num_elem_for_cast, elem_bt_to, VecMaskNotUsed)) {
        if (C->print_intrinsics()) {
          tty->print_cr("  ** not supported: arity=1 op=cast#%d/4 vlen1=%d etype2=%s ismask=%d",
                        cast_vopc,
                        num_elem_for_cast, type2name(elem_bt_to), is_mask);
        }
        return false;
      }

      op = gvn().transform(VectorCastNode::make(cast_vopc, op, elem_bt_to, num_elem_for_cast));
      // Now ensure that the destination gets properly resized to needed size.
      op = gvn().transform(new VectorReinterpretNode(op, op->bottom_type()->is_vect(), dst_type));
    } else if (num_elem_from > num_elem_to) {
      // Since number elements from input is larger than output, simply reduce size of input (we are supposed to
      // drop top elements anyway).
      int num_elem_for_resize = num_elem_to;

      // It is possible that arch does not support this intermediate vector size
      // TODO More complex logic required here to handle this corner case for the sizes.
      if (!arch_supports_vector(Op_VectorReinterpret,
                                num_elem_for_resize,
                                elem_bt_from,
                                VecMaskNotUsed)) {
        if (C->print_intrinsics()) {
          tty->print_cr("  ** not supported: arity=1 op=cast/5 vlen2=%d etype1=%s ismask=%d",
                        num_elem_for_resize, type2name(elem_bt_from), is_mask);
        }
        return false;
      }

      op = gvn().transform(new VectorReinterpretNode(op,
                                                     src_type,
                                                     TypeVect::make(elem_bt_from,
                                                                    num_elem_for_resize)));
      op = gvn().transform(VectorCastNode::make(cast_vopc, op, elem_bt_to, num_elem_to));
    } else {
      if (is_mask) {
        if ((dst_type->isa_vectmask() && src_type->isa_vectmask()) ||
            (type2aelembytes(elem_bt_from) == type2aelembytes(elem_bt_to))) {
          op = gvn().transform(new VectorMaskCastNode(op, dst_type));
        } else {
          op = VectorMaskCastNode::makeCastNode(&gvn(), op, dst_type);
        }
      } else {
        // Since input and output number of elements match, and since we know this vector size is
        // supported, simply do a cast with no resize needed.
        op = gvn().transform(VectorCastNode::make(cast_vopc, op, elem_bt_to, num_elem_to));
      }
    }
  } else if (Type::cmp(src_type, dst_type) != 0) {
    assert(!is_cast, "must be reinterpret");
    op = gvn().transform(new VectorReinterpretNode(op, src_type, dst_type));
  }

  const TypeInstPtr* vbox_type_to = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass_to);
  Node* vbox = box_vector(op, vbox_type_to, elem_bt_to, num_elem_to);
  set_result(vbox);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem_to * type2aelembytes(elem_bt_to))));
  return true;
}

//  public static
//  <V extends Vector<E>,
//   E>
//  V insert(Class<? extends V> vectorClass, Class<E> elementType, int vlen,
//           V vec, int ix, long val,
//           VecInsertOp<V> defaultImpl)
bool LibraryCallKit::inline_vector_insert() {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(2))->isa_int();
  const TypeInt*     idx          = gvn().type(argument(4))->isa_int();

  if (vector_klass == NULL || elem_klass == NULL || vlen == NULL || idx == NULL) {
    return false; // dead code
  }
  if (vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con() || !idx->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s etype=%s vlen=%s idx=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  if (!arch_supports_vector(Op_VectorInsert, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=insert vlen=%d etype=%s ismask=no",
                    num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  Node* opd = unbox_vector(argument(3), vbox_type, elem_bt, num_elem);
  if (opd == NULL) {
    return false;
  }

  Node* insert_val = argument(5);
  assert(gvn().type(insert_val)->isa_long() != NULL, "expected to be long");

  // Convert insert value back to its appropriate type.
  switch (elem_bt) {
    case T_BYTE:
      insert_val = gvn().transform(new ConvL2INode(insert_val));
      insert_val = gvn().transform(new CastIINode(insert_val, TypeInt::BYTE));
      break;
    case T_SHORT:
      insert_val = gvn().transform(new ConvL2INode(insert_val));
      insert_val = gvn().transform(new CastIINode(insert_val, TypeInt::SHORT));
      break;
    case T_INT:
      insert_val = gvn().transform(new ConvL2INode(insert_val));
      break;
    case T_FLOAT:
      insert_val = gvn().transform(new ConvL2INode(insert_val));
      insert_val = gvn().transform(new MoveI2FNode(insert_val));
      break;
    case T_DOUBLE:
      insert_val = gvn().transform(new MoveL2DNode(insert_val));
      break;
    case T_LONG:
      // no conversion needed
      break;
    default: fatal("%s", type2name(elem_bt)); break;
  }

  Node* operation = gvn().transform(VectorInsertNode::make(opd, insert_val, idx->get_con()));

  Node* vbox = box_vector(operation, vbox_type, elem_bt, num_elem);
  set_result(vbox);
  C->set_max_vector_size(MAX2(C->max_vector_size(), (uint)(num_elem * type2aelembytes(elem_bt))));
  return true;
}

//  public static
//  <V extends Vector<E>,
//   E>
//  long extract(Class<? extends V> vectorClass, Class<E> elementType, int vlen,
//               V vec, int ix,
//               VecExtractOp<V> defaultImpl)
bool LibraryCallKit::inline_vector_extract() {
  const TypeInstPtr* vector_klass = gvn().type(argument(0))->isa_instptr();
  const TypeInstPtr* elem_klass   = gvn().type(argument(1))->isa_instptr();
  const TypeInt*     vlen         = gvn().type(argument(2))->isa_int();
  const TypeInt*     idx          = gvn().type(argument(4))->isa_int();

  if (vector_klass == NULL || elem_klass == NULL || vlen == NULL || idx == NULL) {
    return false; // dead code
  }
  if (vector_klass->const_oop() == NULL || elem_klass->const_oop() == NULL || !vlen->is_con() || !idx->is_con()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** missing constant: vclass=%s etype=%s vlen=%s idx=%s",
                    NodeClassNames[argument(0)->Opcode()],
                    NodeClassNames[argument(1)->Opcode()],
                    NodeClassNames[argument(2)->Opcode()],
                    NodeClassNames[argument(4)->Opcode()]);
    }
    return false; // not enough info for intrinsification
  }
  if (!is_klass_initialized(vector_klass)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** klass argument not initialized");
    }
    return false;
  }
  ciType* elem_type = elem_klass->const_oop()->as_instance()->java_mirror_type();
  if (!elem_type->is_primitive_type()) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not a primitive bt=%d", elem_type->basic_type());
    }
    return false; // should be primitive type
  }
  BasicType elem_bt = elem_type->basic_type();
  int num_elem = vlen->get_con();
  int vopc = ExtractNode::opcode(elem_bt);
  if (!arch_supports_vector(vopc, num_elem, elem_bt, VecMaskNotUsed)) {
    if (C->print_intrinsics()) {
      tty->print_cr("  ** not supported: arity=1 op=extract vlen=%d etype=%s ismask=no",
                    num_elem, type2name(elem_bt));
    }
    return false; // not supported
  }

  ciKlass* vbox_klass = vector_klass->const_oop()->as_instance()->java_lang_Class_klass();
  const TypeInstPtr* vbox_type = TypeInstPtr::make_exact(TypePtr::NotNull, vbox_klass);

  Node* opd = unbox_vector(argument(3), vbox_type, elem_bt, num_elem);
  if (opd == NULL) {
    return false;
  }

  Node* operation = gvn().transform(ExtractNode::make(opd, idx->get_con(), elem_bt));

  Node* bits = NULL;
  switch (elem_bt) {
    case T_BYTE:
    case T_SHORT:
    case T_INT: {
      bits = gvn().transform(new ConvI2LNode(operation));
      break;
    }
    case T_FLOAT: {
      bits = gvn().transform(new MoveF2INode(operation));
      bits = gvn().transform(new ConvI2LNode(bits));
      break;
    }
    case T_DOUBLE: {
      bits = gvn().transform(new MoveD2LNode(operation));
      break;
    }
    case T_LONG: {
      bits = operation; // no conversion needed
      break;
    }
    default: fatal("%s", type2name(elem_bt));
  }

  set_result(bits);
  return true;
}


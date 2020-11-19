/*
 * Copyright (c) 2014, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_OPTO_CASTNODE_HPP
#define SHARE_OPTO_CASTNODE_HPP

#include "opto/node.hpp"
#include "opto/opcodes.hpp"


//------------------------------ConstraintCastNode-----------------------------
// cast to a different range
class ConstraintCastNode: public TypeNode {
  protected:
  // Can this node be removed post CCP or does it carry a required dependency?
  const bool _carry_dependency;
  virtual bool cmp( const Node &n ) const;
  virtual uint size_of() const;

  public:
  ConstraintCastNode(Node *n, const Type *t, bool carry_dependency)
    : TypeNode(t,2), _carry_dependency(carry_dependency) {
    init_class_id(Class_ConstraintCast);
    init_req(1, n);
  }
  virtual Node* Identity(PhaseGVN* phase);
  virtual const Type* Value(PhaseGVN* phase) const;
  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  virtual int Opcode() const;
  virtual uint ideal_reg() const = 0;
  virtual bool depends_only_on_test() const { return !_carry_dependency; }
  bool carry_dependency() const { return _carry_dependency; }
  TypeNode* dominating_cast(PhaseGVN* gvn, PhaseTransform* pt) const;
  static Node* make_cast(int opcode,  Node* c, Node *n, const Type *t, bool carry_dependency);

#ifndef PRODUCT
  virtual void dump_spec(outputStream *st) const;
#endif
};

//------------------------------CastIINode-------------------------------------
// cast integer to integer (different range)
class CastIINode: public ConstraintCastNode {
  protected:
  // Is this node dependent on a range check?
  const bool _range_check_dependency;
  virtual bool cmp(const Node &n) const;
  virtual uint size_of() const;

  public:
  CastIINode(Node* n, const Type* t, bool carry_dependency = false, bool range_check_dependency = false)
    : ConstraintCastNode(n, t, carry_dependency), _range_check_dependency(range_check_dependency) {
    init_class_id(Class_CastII);
  }
  virtual int Opcode() const;
  virtual uint ideal_reg() const { return Op_RegI; }
  virtual Node* Identity(PhaseGVN* phase);
  virtual const Type* Value(PhaseGVN* phase) const;
  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  const bool has_range_check() {
#ifdef _LP64
    return _range_check_dependency;
#else
    assert(!_range_check_dependency, "Should not have range check dependency");
    return false;
#endif
  }

#ifndef PRODUCT
  virtual void dump_spec(outputStream* st) const;
#endif
};

//------------------------------CastPPNode-------------------------------------
// cast pointer to pointer (different type)
class CastPPNode: public ConstraintCastNode {
  public:
  CastPPNode (Node *n, const Type *t, bool carry_dependency = false)
    : ConstraintCastNode(n, t, carry_dependency) {
  }
  virtual int Opcode() const;
  virtual uint ideal_reg() const { return Op_RegP; }
};

//------------------------------CheckCastPPNode--------------------------------
// for _checkcast, cast pointer to pointer (different type), without JOIN,
class CheckCastPPNode: public ConstraintCastNode {
  public:
  CheckCastPPNode(Node *c, Node *n, const Type *t, bool carry_dependency = false)
    : ConstraintCastNode(n, t, carry_dependency) {
    init_class_id(Class_CheckCastPP);
    init_req(0, c);
  }

  virtual Node* Identity(PhaseGVN* phase);
  virtual const Type* Value(PhaseGVN* phase) const;
  virtual int   Opcode() const;
  virtual uint  ideal_reg() const { return Op_RegP; }
  bool depends_only_on_test() const { return !type()->isa_rawptr() && ConstraintCastNode::depends_only_on_test(); }
 };


//------------------------------CastX2PNode-------------------------------------
// convert a machine-pointer-sized integer to a raw pointer
class CastX2PNode : public Node {
  public:
  CastX2PNode( Node *n ) : Node(NULL, n) {}
  virtual int Opcode() const;
  virtual const Type* Value(PhaseGVN* phase) const;
  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual uint ideal_reg() const { return Op_RegP; }
  virtual const Type *bottom_type() const { return TypeRawPtr::BOTTOM; }
};

//------------------------------CastP2XNode-------------------------------------
// Used in both 32-bit and 64-bit land.
// Used for card-marks and unsafe pointer math.
class CastP2XNode : public Node {
  public:
  CastP2XNode( Node *ctrl, Node *n ) : Node(ctrl, n) {}
  virtual int Opcode() const;
  virtual const Type* Value(PhaseGVN* phase) const;
  virtual Node *Ideal(PhaseGVN *phase, bool can_reshape);
  virtual Node* Identity(PhaseGVN* phase);
  virtual uint ideal_reg() const { return Op_RegX; }
  virtual const Type *bottom_type() const { return TypeX_X; }
  // Return false to keep node from moving away from an associated card mark.
  virtual bool depends_only_on_test() const { return false; }
};



#endif // SHARE_OPTO_CASTNODE_HPP

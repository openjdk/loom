/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_RUNTIME_STACKVALUE_INLINE_HPP
#define SHARE_RUNTIME_STACKVALUE_INLINE_HPP

#include "runtime/stackValue.hpp"

#include "code/debugInfo.hpp"
#include "code/location.hpp"
#include "runtime/continuation.hpp"
#include "runtime/handles.hpp"
#include "runtime/thread.hpp"

template<typename RegisterMapT>
StackValue* StackValue::create_stack_value(const frame* fr, const RegisterMapT* reg_map, ScopeValue* sv) {
  return create_stack_value(sv, stack_value_address(fr, reg_map, sv), reg_map);
}

template<typename RegisterMapT>
address StackValue::stack_value_address(const frame* fr, const RegisterMapT* reg_map, ScopeValue* sv) {
  if (!sv->is_location()) {
    return NULL;
  }
  Location loc = ((LocationValue *)sv)->location();
  if (loc.type() == Location::invalid) {
    return NULL;
  }

  if (!reg_map->in_cont()) {
    address value_addr = loc.is_register()
      // Value was in a callee-save register
      ? reg_map->location(VMRegImpl::as_VMReg(loc.register_number()), fr->sp())
      // Else value was directly saved on the stack. The frame's original stack pointer,
      // before any extension by its callee (due to Compiler1 linkage on SPARC), must be used.
      : ((address)fr->unextended_sp()) + loc.stack_offset();

    assert(value_addr == NULL || reg_map->thread() == NULL || reg_map->thread()->is_in_usable_stack(value_addr), INTPTR_FORMAT, p2i(value_addr));
    return value_addr;
  }

  address value_addr = loc.is_register()
    ? reg_map->as_RegisterMap()->stack_chunk()->reg_to_location(*fr, reg_map->as_RegisterMap(), VMRegImpl::as_VMReg(loc.register_number()))
    : reg_map->as_RegisterMap()->stack_chunk()->usp_offset_to_location(*fr, loc.stack_offset());

  assert(value_addr == NULL || Continuation::is_in_usable_stack(value_addr, reg_map->as_RegisterMap()) || (reg_map->thread() != NULL && reg_map->thread()->is_in_usable_stack(value_addr)), INTPTR_FORMAT, p2i(value_addr));
  return value_addr;
}

#endif // SHARE_RUNTIME_STACKVALUE_INLINE_HPP

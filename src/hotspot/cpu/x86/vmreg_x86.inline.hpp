/*
 * Copyright (c) 2006, 2021, Oracle and/or its affiliates. All rights reserved.
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

#ifndef CPU_X86_VMREG_X86_INLINE_HPP
#define CPU_X86_VMREG_X86_INLINE_HPP

inline VMReg Register::RegisterImpl::as_VMReg() const {
  return VMRegImpl::as_VMReg(encoding() LP64_ONLY( << 1 ));
}

inline VMReg FloatRegister::FloatRegisterImpl::as_VMReg() const {
  return VMRegImpl::as_VMReg((encoding() << 1) + ConcreteRegisterImpl::max_gpr);
}

inline VMReg XMMRegister::XMMRegisterImpl::as_VMReg() const {
  return VMRegImpl::as_VMReg((encoding() << 4) + ConcreteRegisterImpl::max_fpr);
}

inline VMReg KRegister::KRegisterImpl::as_VMReg() const {
  return VMRegImpl::as_VMReg((encoding() << 1) + ConcreteRegisterImpl::max_xmm);
}

#endif // CPU_X86_VMREG_X86_INLINE_HPP

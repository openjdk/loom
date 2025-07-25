/*
 * Copyright (c) 1997, 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2012, 2025 SAP SE. All rights reserved.
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

#include "asm/macroAssembler.inline.hpp"
#include "code/debugInfoRec.hpp"
#include "code/compiledIC.hpp"
#include "code/vtableStubs.hpp"
#include "frame_ppc.hpp"
#include "compiler/oopMap.hpp"
#include "gc/shared/gcLocker.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/interp_masm.hpp"
#include "memory/resourceArea.hpp"
#include "oops/klass.inline.hpp"
#include "prims/methodHandles.hpp"
#include "runtime/continuation.hpp"
#include "runtime/continuationEntry.inline.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/os.inline.hpp"
#include "runtime/safepointMechanism.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/signature.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/timerTrace.hpp"
#include "runtime/vframeArray.hpp"
#include "utilities/align.hpp"
#include "utilities/macros.hpp"
#include "vmreg_ppc.inline.hpp"
#ifdef COMPILER1
#include "c1/c1_Runtime1.hpp"
#endif
#ifdef COMPILER2
#include "opto/ad.hpp"
#include "opto/runtime.hpp"
#endif

#include <alloca.h>

#define __ masm->

#ifdef PRODUCT
#define BLOCK_COMMENT(str) // nothing
#else
#define BLOCK_COMMENT(str) __ block_comment(str)
#endif

#define BIND(label) bind(label); BLOCK_COMMENT(#label ":")


class RegisterSaver {
 // Used for saving volatile registers.
 public:

  // Support different return pc locations.
  enum ReturnPCLocation {
    return_pc_is_lr,
    return_pc_is_pre_saved,
    return_pc_is_thread_saved_exception_pc
  };

  static OopMap* push_frame_reg_args_and_save_live_registers(MacroAssembler* masm,
                         int* out_frame_size_in_bytes,
                         bool generate_oop_map,
                         int return_pc_adjustment,
                         ReturnPCLocation return_pc_location,
                         bool save_vectors = false);
  static void    restore_live_registers_and_pop_frame(MacroAssembler* masm,
                         int frame_size_in_bytes,
                         bool restore_ctr,
                         bool save_vectors = false);

  static void push_frame_and_save_argument_registers(MacroAssembler* masm,
                         Register r_temp,
                         int frame_size,
                         int total_args,
                         const VMRegPair *regs, const VMRegPair *regs2 = nullptr);
  static void restore_argument_registers_and_pop_frame(MacroAssembler*masm,
                         int frame_size,
                         int total_args,
                         const VMRegPair *regs, const VMRegPair *regs2 = nullptr);

  // During deoptimization only the result registers need to be restored
  // all the other values have already been extracted.
  static void restore_result_registers(MacroAssembler* masm, int frame_size_in_bytes);

  // Constants and data structures:

  typedef enum {
    int_reg,
    float_reg,
    special_reg,
    vec_reg
  } RegisterType;

  typedef enum {
    reg_size          = 8,
    half_reg_size     = reg_size / 2,
    vec_reg_size      = 16
  } RegisterConstants;

  typedef struct {
    RegisterType        reg_type;
    int                 reg_num;
    VMReg               vmreg;
  } LiveRegType;
};


#define RegisterSaver_LiveIntReg(regname) \
  { RegisterSaver::int_reg,     regname->encoding(), regname->as_VMReg() }

#define RegisterSaver_LiveFloatReg(regname) \
  { RegisterSaver::float_reg,   regname->encoding(), regname->as_VMReg() }

#define RegisterSaver_LiveSpecialReg(regname) \
  { RegisterSaver::special_reg, regname->encoding(), regname->as_VMReg() }

#define RegisterSaver_LiveVecReg(regname) \
  { RegisterSaver::vec_reg,      regname->encoding(), regname->as_VMReg() }

static const RegisterSaver::LiveRegType RegisterSaver_LiveRegs[] = {
  // Live registers which get spilled to the stack. Register
  // positions in this array correspond directly to the stack layout.

  //
  // live special registers:
  //
  RegisterSaver_LiveSpecialReg(SR_CTR),
  //
  // live float registers:
  //
  RegisterSaver_LiveFloatReg( F0  ),
  RegisterSaver_LiveFloatReg( F1  ),
  RegisterSaver_LiveFloatReg( F2  ),
  RegisterSaver_LiveFloatReg( F3  ),
  RegisterSaver_LiveFloatReg( F4  ),
  RegisterSaver_LiveFloatReg( F5  ),
  RegisterSaver_LiveFloatReg( F6  ),
  RegisterSaver_LiveFloatReg( F7  ),
  RegisterSaver_LiveFloatReg( F8  ),
  RegisterSaver_LiveFloatReg( F9  ),
  RegisterSaver_LiveFloatReg( F10 ),
  RegisterSaver_LiveFloatReg( F11 ),
  RegisterSaver_LiveFloatReg( F12 ),
  RegisterSaver_LiveFloatReg( F13 ),
  RegisterSaver_LiveFloatReg( F14 ),
  RegisterSaver_LiveFloatReg( F15 ),
  RegisterSaver_LiveFloatReg( F16 ),
  RegisterSaver_LiveFloatReg( F17 ),
  RegisterSaver_LiveFloatReg( F18 ),
  RegisterSaver_LiveFloatReg( F19 ),
  RegisterSaver_LiveFloatReg( F20 ),
  RegisterSaver_LiveFloatReg( F21 ),
  RegisterSaver_LiveFloatReg( F22 ),
  RegisterSaver_LiveFloatReg( F23 ),
  RegisterSaver_LiveFloatReg( F24 ),
  RegisterSaver_LiveFloatReg( F25 ),
  RegisterSaver_LiveFloatReg( F26 ),
  RegisterSaver_LiveFloatReg( F27 ),
  RegisterSaver_LiveFloatReg( F28 ),
  RegisterSaver_LiveFloatReg( F29 ),
  RegisterSaver_LiveFloatReg( F30 ),
  RegisterSaver_LiveFloatReg( F31 ),
  //
  // live integer registers:
  //
  RegisterSaver_LiveIntReg(   R0  ),
  //RegisterSaver_LiveIntReg( R1  ), // stack pointer
  RegisterSaver_LiveIntReg(   R2  ),
  RegisterSaver_LiveIntReg(   R3  ),
  RegisterSaver_LiveIntReg(   R4  ),
  RegisterSaver_LiveIntReg(   R5  ),
  RegisterSaver_LiveIntReg(   R6  ),
  RegisterSaver_LiveIntReg(   R7  ),
  RegisterSaver_LiveIntReg(   R8  ),
  RegisterSaver_LiveIntReg(   R9  ),
  RegisterSaver_LiveIntReg(   R10 ),
  RegisterSaver_LiveIntReg(   R11 ),
  RegisterSaver_LiveIntReg(   R12 ),
  //RegisterSaver_LiveIntReg( R13 ), // system thread id
  RegisterSaver_LiveIntReg(   R14 ),
  RegisterSaver_LiveIntReg(   R15 ),
  RegisterSaver_LiveIntReg(   R16 ),
  RegisterSaver_LiveIntReg(   R17 ),
  RegisterSaver_LiveIntReg(   R18 ),
  RegisterSaver_LiveIntReg(   R19 ),
  RegisterSaver_LiveIntReg(   R20 ),
  RegisterSaver_LiveIntReg(   R21 ),
  RegisterSaver_LiveIntReg(   R22 ),
  RegisterSaver_LiveIntReg(   R23 ),
  RegisterSaver_LiveIntReg(   R24 ),
  RegisterSaver_LiveIntReg(   R25 ),
  RegisterSaver_LiveIntReg(   R26 ),
  RegisterSaver_LiveIntReg(   R27 ),
  RegisterSaver_LiveIntReg(   R28 ),
  RegisterSaver_LiveIntReg(   R29 ),
  RegisterSaver_LiveIntReg(   R30 ),
  RegisterSaver_LiveIntReg(   R31 )  // must be the last register (see save/restore functions below)
};

static const RegisterSaver::LiveRegType RegisterSaver_LiveVecRegs[] = {
  //
  // live vector registers (optional, only these ones are used by C2):
  //
  RegisterSaver_LiveVecReg( VR0 ),
  RegisterSaver_LiveVecReg( VR1 ),
  RegisterSaver_LiveVecReg( VR2 ),
  RegisterSaver_LiveVecReg( VR3 ),
  RegisterSaver_LiveVecReg( VR4 ),
  RegisterSaver_LiveVecReg( VR5 ),
  RegisterSaver_LiveVecReg( VR6 ),
  RegisterSaver_LiveVecReg( VR7 ),
  RegisterSaver_LiveVecReg( VR8 ),
  RegisterSaver_LiveVecReg( VR9 ),
  RegisterSaver_LiveVecReg( VR10 ),
  RegisterSaver_LiveVecReg( VR11 ),
  RegisterSaver_LiveVecReg( VR12 ),
  RegisterSaver_LiveVecReg( VR13 ),
  RegisterSaver_LiveVecReg( VR14 ),
  RegisterSaver_LiveVecReg( VR15 ),
  RegisterSaver_LiveVecReg( VR16 ),
  RegisterSaver_LiveVecReg( VR17 ),
  RegisterSaver_LiveVecReg( VR18 ),
  RegisterSaver_LiveVecReg( VR19 ),
  RegisterSaver_LiveVecReg( VR20 ),
  RegisterSaver_LiveVecReg( VR21 ),
  RegisterSaver_LiveVecReg( VR22 ),
  RegisterSaver_LiveVecReg( VR23 ),
  RegisterSaver_LiveVecReg( VR24 ),
  RegisterSaver_LiveVecReg( VR25 ),
  RegisterSaver_LiveVecReg( VR26 ),
  RegisterSaver_LiveVecReg( VR27 ),
  RegisterSaver_LiveVecReg( VR28 ),
  RegisterSaver_LiveVecReg( VR29 ),
  RegisterSaver_LiveVecReg( VR30 ),
  RegisterSaver_LiveVecReg( VR31 )
};


OopMap* RegisterSaver::push_frame_reg_args_and_save_live_registers(MacroAssembler* masm,
                         int* out_frame_size_in_bytes,
                         bool generate_oop_map,
                         int return_pc_adjustment,
                         ReturnPCLocation return_pc_location,
                         bool save_vectors) {
  // Push an abi_reg_args-frame and store all registers which may be live.
  // If requested, create an OopMap: Record volatile registers as
  // callee-save values in an OopMap so their save locations will be
  // propagated to the RegisterMap of the caller frame during
  // StackFrameStream construction (needed for deoptimization; see
  // compiledVFrame::create_stack_value).
  // If return_pc_adjustment != 0 adjust the return pc by return_pc_adjustment.
  // Updated return pc is returned in R31 (if not return_pc_is_pre_saved).

  // calculate frame size
  const int regstosave_num       = sizeof(RegisterSaver_LiveRegs) /
                                   sizeof(RegisterSaver::LiveRegType);
  const int vecregstosave_num    = save_vectors ? (sizeof(RegisterSaver_LiveVecRegs) /
                                                   sizeof(RegisterSaver::LiveRegType))
                                                : 0;
  const int register_save_size   = regstosave_num * reg_size + vecregstosave_num * vec_reg_size;
  const int frame_size_in_bytes  = align_up(register_save_size, frame::alignment_in_bytes)
                                   + frame::native_abi_reg_args_size;

  *out_frame_size_in_bytes       = frame_size_in_bytes;
  const int frame_size_in_slots  = frame_size_in_bytes / sizeof(jint);
  const int register_save_offset = frame_size_in_bytes - register_save_size;

  // OopMap frame size is in c2 stack slots (sizeof(jint)) not bytes or words.
  OopMap* map = generate_oop_map ? new OopMap(frame_size_in_slots, 0) : nullptr;

  BLOCK_COMMENT("push_frame_reg_args_and_save_live_registers {");

  // push a new frame
  __ push_frame(frame_size_in_bytes, noreg);

  // Save some registers in the last (non-vector) slots of the new frame so we
  // can use them as scratch regs or to determine the return pc.
  __ std(R31, frame_size_in_bytes -   reg_size - vecregstosave_num * vec_reg_size, R1_SP);
  __ std(R30, frame_size_in_bytes - 2*reg_size - vecregstosave_num * vec_reg_size, R1_SP);

  // save the flags
  // Do the save_LR by hand and adjust the return pc if requested.
  switch (return_pc_location) {
    case return_pc_is_lr: __ mflr(R31); break;
    case return_pc_is_pre_saved: assert(return_pc_adjustment == 0, "unsupported"); break;
    case return_pc_is_thread_saved_exception_pc: __ ld(R31, thread_(saved_exception_pc)); break;
    default: ShouldNotReachHere();
  }
  if (return_pc_location != return_pc_is_pre_saved) {
    if (return_pc_adjustment != 0) {
      __ addi(R31, R31, return_pc_adjustment);
    }
    __ std(R31, frame_size_in_bytes + _abi0(lr), R1_SP);
  }

  // save all registers (ints and floats)
  int offset = register_save_offset;

  for (int i = 0; i < regstosave_num; i++) {
    int reg_num  = RegisterSaver_LiveRegs[i].reg_num;
    int reg_type = RegisterSaver_LiveRegs[i].reg_type;

    switch (reg_type) {
      case RegisterSaver::int_reg: {
        if (reg_num < 30) { // We spilled R30-31 right at the beginning.
          __ std(as_Register(reg_num), offset, R1_SP);
        }
        break;
      }
      case RegisterSaver::float_reg: {
        __ stfd(as_FloatRegister(reg_num), offset, R1_SP);
        break;
      }
      case RegisterSaver::special_reg: {
        if (reg_num == SR_CTR.encoding()) {
          __ mfctr(R30);
          __ std(R30, offset, R1_SP);
        } else {
          Unimplemented();
        }
        break;
      }
      default:
        ShouldNotReachHere();
    }

    if (generate_oop_map) {
      map->set_callee_saved(VMRegImpl::stack2reg(offset >> 2),
                            RegisterSaver_LiveRegs[i].vmreg);
    }
    offset += reg_size;
  }

  // Note that generate_oop_map in the following loop is only used for the
  // polling_page_vectors_safepoint_handler_blob.
  // The order in which the vector contents are stored depends on Endianess and
  // the utilized instructions (PowerArchitecturePPC64).
  assert(is_aligned(offset, StackAlignmentInBytes), "should be");
  if (PowerArchitecturePPC64 >= 10) {
    assert(is_even(vecregstosave_num), "expectation");
    for (int i = 0; i < vecregstosave_num; i += 2) {
      int reg_num = RegisterSaver_LiveVecRegs[i].reg_num;
      assert(RegisterSaver_LiveVecRegs[i + 1].reg_num == reg_num + 1, "or use other instructions!");

      __ stxvp(as_VectorRegister(reg_num).to_vsr(), offset, R1_SP);
      // Note: The contents were read in the same order (see loadV16_Power9 node in ppc.ad).
      if (generate_oop_map) {
        map->set_callee_saved(VMRegImpl::stack2reg(offset >> 2),
                              RegisterSaver_LiveVecRegs[i LITTLE_ENDIAN_ONLY(+1) ].vmreg);
        map->set_callee_saved(VMRegImpl::stack2reg((offset + vec_reg_size) >> 2),
                              RegisterSaver_LiveVecRegs[i BIG_ENDIAN_ONLY(+1) ].vmreg);
      }
      offset += (2 * vec_reg_size);
    }
  } else {
    for (int i = 0; i < vecregstosave_num; i++) {
      int reg_num = RegisterSaver_LiveVecRegs[i].reg_num;

      if (PowerArchitecturePPC64 >= 9) {
        __ stxv(as_VectorRegister(reg_num)->to_vsr(), offset, R1_SP);
      } else {
        __ li(R31, offset);
        __ stxvd2x(as_VectorRegister(reg_num)->to_vsr(), R31, R1_SP);
      }
      // Note: The contents were read in the same order (see loadV16_Power8 / loadV16_Power9 node in ppc.ad).
      if (generate_oop_map) {
        VMReg vsr = RegisterSaver_LiveVecRegs[i].vmreg;
        map->set_callee_saved(VMRegImpl::stack2reg(offset >> 2), vsr);
      }
      offset += vec_reg_size;
    }
  }

  assert(offset == frame_size_in_bytes, "consistency check");

  BLOCK_COMMENT("} push_frame_reg_args_and_save_live_registers");

  // And we're done.
  return map;
}


// Pop the current frame and restore all the registers that we
// saved.
void RegisterSaver::restore_live_registers_and_pop_frame(MacroAssembler* masm,
                                                         int frame_size_in_bytes,
                                                         bool restore_ctr,
                                                         bool save_vectors) {
  const int regstosave_num       = sizeof(RegisterSaver_LiveRegs) /
                                   sizeof(RegisterSaver::LiveRegType);
  const int vecregstosave_num    = save_vectors ? (sizeof(RegisterSaver_LiveVecRegs) /
                                                   sizeof(RegisterSaver::LiveRegType))
                                                : 0;
  const int register_save_size   = regstosave_num * reg_size + vecregstosave_num * vec_reg_size;

  const int register_save_offset = frame_size_in_bytes - register_save_size;

  BLOCK_COMMENT("restore_live_registers_and_pop_frame {");

  // restore all registers (ints and floats)
  int offset = register_save_offset;

  for (int i = 0; i < regstosave_num; i++) {
    int reg_num  = RegisterSaver_LiveRegs[i].reg_num;
    int reg_type = RegisterSaver_LiveRegs[i].reg_type;

    switch (reg_type) {
      case RegisterSaver::int_reg: {
        if (reg_num != 31) // R31 restored at the end, it's the tmp reg!
          __ ld(as_Register(reg_num), offset, R1_SP);
        break;
      }
      case RegisterSaver::float_reg: {
        __ lfd(as_FloatRegister(reg_num), offset, R1_SP);
        break;
      }
      case RegisterSaver::special_reg: {
        if (reg_num == SR_CTR.encoding()) {
          if (restore_ctr) { // Nothing to do here if ctr already contains the next address.
            __ ld(R31, offset, R1_SP);
            __ mtctr(R31);
          }
        } else {
          Unimplemented();
        }
        break;
      }
      default:
        ShouldNotReachHere();
    }
    offset += reg_size;
  }

  assert(is_aligned(offset, StackAlignmentInBytes), "should be");
  if (PowerArchitecturePPC64 >= 10) {
    for (int i = 0; i < vecregstosave_num; i += 2) {
      int reg_num  = RegisterSaver_LiveVecRegs[i].reg_num;
      assert(RegisterSaver_LiveVecRegs[i + 1].reg_num == reg_num + 1, "or use other instructions!");

      __ lxvp(as_VectorRegister(reg_num).to_vsr(), offset, R1_SP);

      offset += (2 * vec_reg_size);
    }
  } else {
    for (int i = 0; i < vecregstosave_num; i++) {
      int reg_num  = RegisterSaver_LiveVecRegs[i].reg_num;

      if (PowerArchitecturePPC64 >= 9) {
        __ lxv(as_VectorRegister(reg_num).to_vsr(), offset, R1_SP);
      } else {
        __ li(R31, offset);
        __ lxvd2x(as_VectorRegister(reg_num).to_vsr(), R31, R1_SP);
      }

      offset += vec_reg_size;
    }
  }

  assert(offset == frame_size_in_bytes, "consistency check");

  // restore link and the flags
  __ ld(R31, frame_size_in_bytes + _abi0(lr), R1_SP);
  __ mtlr(R31);

  // restore scratch register's value
  __ ld(R31, frame_size_in_bytes - reg_size - vecregstosave_num * vec_reg_size, R1_SP);

  // pop the frame
  __ addi(R1_SP, R1_SP, frame_size_in_bytes);

  BLOCK_COMMENT("} restore_live_registers_and_pop_frame");
}

void RegisterSaver::push_frame_and_save_argument_registers(MacroAssembler* masm, Register r_temp,
                                                           int frame_size,int total_args, const VMRegPair *regs,
                                                           const VMRegPair *regs2) {
  __ push_frame(frame_size, r_temp);
  int st_off = frame_size - wordSize;
  for (int i = 0; i < total_args; i++) {
    VMReg r_1 = regs[i].first();
    VMReg r_2 = regs[i].second();
    if (!r_1->is_valid()) {
      assert(!r_2->is_valid(), "");
      continue;
    }
    if (r_1->is_Register()) {
      Register r = r_1->as_Register();
      __ std(r, st_off, R1_SP);
      st_off -= wordSize;
    } else if (r_1->is_FloatRegister()) {
      FloatRegister f = r_1->as_FloatRegister();
      __ stfd(f, st_off, R1_SP);
      st_off -= wordSize;
    }
  }
  if (regs2 != nullptr) {
    for (int i = 0; i < total_args; i++) {
      VMReg r_1 = regs2[i].first();
      VMReg r_2 = regs2[i].second();
      if (!r_1->is_valid()) {
        assert(!r_2->is_valid(), "");
        continue;
      }
      if (r_1->is_Register()) {
        Register r = r_1->as_Register();
        __ std(r, st_off, R1_SP);
        st_off -= wordSize;
      } else if (r_1->is_FloatRegister()) {
        FloatRegister f = r_1->as_FloatRegister();
        __ stfd(f, st_off, R1_SP);
        st_off -= wordSize;
      }
    }
  }
}

void RegisterSaver::restore_argument_registers_and_pop_frame(MacroAssembler*masm, int frame_size,
                                                             int total_args, const VMRegPair *regs,
                                                             const VMRegPair *regs2) {
  int st_off = frame_size - wordSize;
  for (int i = 0; i < total_args; i++) {
    VMReg r_1 = regs[i].first();
    VMReg r_2 = regs[i].second();
    if (r_1->is_Register()) {
      Register r = r_1->as_Register();
      __ ld(r, st_off, R1_SP);
      st_off -= wordSize;
    } else if (r_1->is_FloatRegister()) {
      FloatRegister f = r_1->as_FloatRegister();
      __ lfd(f, st_off, R1_SP);
      st_off -= wordSize;
    }
  }
  if (regs2 != nullptr)
    for (int i = 0; i < total_args; i++) {
      VMReg r_1 = regs2[i].first();
      VMReg r_2 = regs2[i].second();
      if (r_1->is_Register()) {
        Register r = r_1->as_Register();
        __ ld(r, st_off, R1_SP);
        st_off -= wordSize;
      } else if (r_1->is_FloatRegister()) {
        FloatRegister f = r_1->as_FloatRegister();
        __ lfd(f, st_off, R1_SP);
        st_off -= wordSize;
      }
    }
  __ pop_frame();
}

// Restore the registers that might be holding a result.
void RegisterSaver::restore_result_registers(MacroAssembler* masm, int frame_size_in_bytes) {
  const int regstosave_num       = sizeof(RegisterSaver_LiveRegs) /
                                   sizeof(RegisterSaver::LiveRegType);
  const int register_save_size   = regstosave_num * reg_size; // VS registers not relevant here.
  const int register_save_offset = frame_size_in_bytes - register_save_size;

  // restore all result registers (ints and floats)
  int offset = register_save_offset;
  for (int i = 0; i < regstosave_num; i++) {
    int reg_num  = RegisterSaver_LiveRegs[i].reg_num;
    int reg_type = RegisterSaver_LiveRegs[i].reg_type;
    switch (reg_type) {
      case RegisterSaver::int_reg: {
        if (as_Register(reg_num)==R3_RET) // int result_reg
          __ ld(as_Register(reg_num), offset, R1_SP);
        break;
      }
      case RegisterSaver::float_reg: {
        if (as_FloatRegister(reg_num)==F1_RET) // float result_reg
          __ lfd(as_FloatRegister(reg_num), offset, R1_SP);
        break;
      }
      case RegisterSaver::special_reg: {
        // Special registers don't hold a result.
        break;
      }
      default:
        ShouldNotReachHere();
    }
    offset += reg_size;
  }

  assert(offset == frame_size_in_bytes, "consistency check");
}

// Is vector's size (in bytes) bigger than a size saved by default?
bool SharedRuntime::is_wide_vector(int size) {
  // Note, MaxVectorSize == 8/16 on PPC64.
  assert(size <= (SuperwordUseVSX ? 16 : 8), "%d bytes vectors are not supported", size);
  return size > 8;
}

static int reg2slot(VMReg r) {
  return r->reg2stack() + SharedRuntime::out_preserve_stack_slots();
}

static int reg2offset(VMReg r) {
  return (r->reg2stack() + SharedRuntime::out_preserve_stack_slots()) * VMRegImpl::stack_slot_size;
}

// ---------------------------------------------------------------------------
// Read the array of BasicTypes from a signature, and compute where the
// arguments should go. Values in the VMRegPair regs array refer to 4-byte
// quantities. Values less than VMRegImpl::stack0 are registers, those above
// refer to 4-byte stack slots. All stack slots are based off of the stack pointer
// as framesizes are fixed.
// VMRegImpl::stack0 refers to the first slot 0(sp).
// and VMRegImpl::stack0+1 refers to the memory word 4-bytes higher. Register
// up to Register::number_of_registers) are the 64-bit
// integer registers.

// Note: the INPUTS in sig_bt are in units of Java argument words, which are
// either 32-bit or 64-bit depending on the build. The OUTPUTS are in 32-bit
// units regardless of build. Of course for i486 there is no 64 bit build

// The Java calling convention is a "shifted" version of the C ABI.
// By skipping the first C ABI register we can call non-static jni methods
// with small numbers of arguments without having to shuffle the arguments
// at all. Since we control the java ABI we ought to at least get some
// advantage out of it.

const VMReg java_iarg_reg[8] = {
  R3->as_VMReg(),
  R4->as_VMReg(),
  R5->as_VMReg(),
  R6->as_VMReg(),
  R7->as_VMReg(),
  R8->as_VMReg(),
  R9->as_VMReg(),
  R10->as_VMReg()
};

const VMReg java_farg_reg[13] = {
  F1->as_VMReg(),
  F2->as_VMReg(),
  F3->as_VMReg(),
  F4->as_VMReg(),
  F5->as_VMReg(),
  F6->as_VMReg(),
  F7->as_VMReg(),
  F8->as_VMReg(),
  F9->as_VMReg(),
  F10->as_VMReg(),
  F11->as_VMReg(),
  F12->as_VMReg(),
  F13->as_VMReg()
};

const int num_java_iarg_registers = sizeof(java_iarg_reg) / sizeof(java_iarg_reg[0]);
const int num_java_farg_registers = sizeof(java_farg_reg) / sizeof(java_farg_reg[0]);

STATIC_ASSERT(num_java_iarg_registers == Argument::n_int_register_parameters_j);
STATIC_ASSERT(num_java_farg_registers == Argument::n_float_register_parameters_j);

int SharedRuntime::java_calling_convention(const BasicType *sig_bt,
                                           VMRegPair *regs,
                                           int total_args_passed) {
  // C2c calling conventions for compiled-compiled calls.
  // Put 8 ints/longs into registers _AND_ 13 float/doubles into
  // registers _AND_ put the rest on the stack.

  const int inc_stk_for_intfloat   = 1; // 1 slots for ints and floats
  const int inc_stk_for_longdouble = 2; // 2 slots for longs and doubles

  int i;
  VMReg reg;
  int stk = 0;
  int ireg = 0;
  int freg = 0;

  // We put the first 8 arguments into registers and the rest on the
  // stack, float arguments are already in their argument registers
  // due to c2c calling conventions (see calling_convention).
  for (int i = 0; i < total_args_passed; ++i) {
    switch(sig_bt[i]) {
    case T_BOOLEAN:
    case T_CHAR:
    case T_BYTE:
    case T_SHORT:
    case T_INT:
      if (ireg < num_java_iarg_registers) {
        // Put int/ptr in register
        reg = java_iarg_reg[ireg];
        ++ireg;
      } else {
        // Put int/ptr on stack.
        reg = VMRegImpl::stack2reg(stk);
        stk += inc_stk_for_intfloat;
      }
      regs[i].set1(reg);
      break;
    case T_LONG:
      assert((i + 1) < total_args_passed && sig_bt[i+1] == T_VOID, "expecting half");
      if (ireg < num_java_iarg_registers) {
        // Put long in register.
        reg = java_iarg_reg[ireg];
        ++ireg;
      } else {
        // Put long on stack. They must be aligned to 2 slots.
        if (stk & 0x1) ++stk;
        reg = VMRegImpl::stack2reg(stk);
        stk += inc_stk_for_longdouble;
      }
      regs[i].set2(reg);
      break;
    case T_OBJECT:
    case T_ARRAY:
    case T_ADDRESS:
      if (ireg < num_java_iarg_registers) {
        // Put ptr in register.
        reg = java_iarg_reg[ireg];
        ++ireg;
      } else {
        // Put ptr on stack. Objects must be aligned to 2 slots too,
        // because "64-bit pointers record oop-ishness on 2 aligned
        // adjacent registers." (see OopFlow::build_oop_map).
        if (stk & 0x1) ++stk;
        reg = VMRegImpl::stack2reg(stk);
        stk += inc_stk_for_longdouble;
      }
      regs[i].set2(reg);
      break;
    case T_FLOAT:
      if (freg < num_java_farg_registers) {
        // Put float in register.
        reg = java_farg_reg[freg];
        ++freg;
      } else {
        // Put float on stack.
        reg = VMRegImpl::stack2reg(stk);
        stk += inc_stk_for_intfloat;
      }
      regs[i].set1(reg);
      break;
    case T_DOUBLE:
      assert((i + 1) < total_args_passed && sig_bt[i+1] == T_VOID, "expecting half");
      if (freg < num_java_farg_registers) {
        // Put double in register.
        reg = java_farg_reg[freg];
        ++freg;
      } else {
        // Put double on stack. They must be aligned to 2 slots.
        if (stk & 0x1) ++stk;
        reg = VMRegImpl::stack2reg(stk);
        stk += inc_stk_for_longdouble;
      }
      regs[i].set2(reg);
      break;
    case T_VOID:
      // Do not count halves.
      regs[i].set_bad();
      break;
    default:
      ShouldNotReachHere();
    }
  }
  return stk;
}

#if defined(COMPILER1) || defined(COMPILER2)
// Calling convention for calling C code.
int SharedRuntime::c_calling_convention(const BasicType *sig_bt,
                                        VMRegPair *regs,
                                        int total_args_passed) {
  // Calling conventions for C runtime calls and calls to JNI native methods.
  //
  // PPC64 convention: Hoist the first 8 int/ptr/long's in the first 8
  // int regs, leaving int regs undefined if the arg is flt/dbl. Hoist
  // the first 13 flt/dbl's in the first 13 fp regs but additionally
  // copy flt/dbl to the stack if they are beyond the 8th argument.

  const VMReg iarg_reg[8] = {
    R3->as_VMReg(),
    R4->as_VMReg(),
    R5->as_VMReg(),
    R6->as_VMReg(),
    R7->as_VMReg(),
    R8->as_VMReg(),
    R9->as_VMReg(),
    R10->as_VMReg()
  };

  const VMReg farg_reg[13] = {
    F1->as_VMReg(),
    F2->as_VMReg(),
    F3->as_VMReg(),
    F4->as_VMReg(),
    F5->as_VMReg(),
    F6->as_VMReg(),
    F7->as_VMReg(),
    F8->as_VMReg(),
    F9->as_VMReg(),
    F10->as_VMReg(),
    F11->as_VMReg(),
    F12->as_VMReg(),
    F13->as_VMReg()
  };

  // Check calling conventions consistency.
  assert(sizeof(iarg_reg) / sizeof(iarg_reg[0]) == Argument::n_int_register_parameters_c &&
         sizeof(farg_reg) / sizeof(farg_reg[0]) == Argument::n_float_register_parameters_c,
         "consistency");

  const int additional_frame_header_slots = ((frame::native_abi_minframe_size - frame::jit_out_preserve_size)
                                            / VMRegImpl::stack_slot_size);
  const int float_offset_in_slots = Argument::float_on_stack_offset_in_bytes_c / VMRegImpl::stack_slot_size;

  VMReg reg;
  int arg = 0;
  int freg = 0;
  bool stack_used = false;

  for (int i = 0; i < total_args_passed; ++i, ++arg) {
    // Each argument corresponds to a slot in the Parameter Save Area (if not omitted)
    int stk = (arg * 2) + additional_frame_header_slots;

    switch(sig_bt[i]) {
    //
    // If arguments 0-7 are integers, they are passed in integer registers.
    // Argument i is placed in iarg_reg[i].
    //
    case T_BOOLEAN:
    case T_CHAR:
    case T_BYTE:
    case T_SHORT:
    case T_INT:
      // We must cast ints to longs and use full 64 bit stack slots
      // here.  Thus fall through, handle as long.
    case T_LONG:
    case T_OBJECT:
    case T_ARRAY:
    case T_ADDRESS:
    case T_METADATA:
      // Oops are already boxed if required (JNI).
      if (arg < Argument::n_int_register_parameters_c) {
        reg = iarg_reg[arg];
      } else {
        reg = VMRegImpl::stack2reg(stk);
        stack_used = true;
      }
      regs[i].set2(reg);
      break;

    //
    // Floats are treated differently from int regs:  The first 13 float arguments
    // are passed in registers (not the float args among the first 13 args).
    // Thus argument i is NOT passed in farg_reg[i] if it is float.  It is passed
    // in farg_reg[j] if argument i is the j-th float argument of this call.
    //
    case T_FLOAT:
      if (freg < Argument::n_float_register_parameters_c) {
        // Put float in register ...
        reg = farg_reg[freg];
        ++freg;
      } else {
        // Put float on stack.
        reg = VMRegImpl::stack2reg(stk + float_offset_in_slots);
        stack_used = true;
      }
      regs[i].set1(reg);
      break;
    case T_DOUBLE:
      assert((i + 1) < total_args_passed && sig_bt[i+1] == T_VOID, "expecting half");
      if (freg < Argument::n_float_register_parameters_c) {
        // Put double in register ...
        reg = farg_reg[freg];
        ++freg;
      } else {
        // Put double on stack.
        reg = VMRegImpl::stack2reg(stk);
        stack_used = true;
      }
      regs[i].set2(reg);
      break;

    case T_VOID:
      // Do not count halves.
      regs[i].set_bad();
      --arg;
      break;
    default:
      ShouldNotReachHere();
    }
  }

  // Return size of the stack frame excluding the jit_out_preserve part in single-word slots.
#if defined(ABI_ELFv2)
  assert(additional_frame_header_slots == 0, "ABIv2 shouldn't use extra slots");
  // ABIv2 allows omitting the Parameter Save Area if the callee's prototype
  // indicates that all parameters can be passed in registers.
  return stack_used ? (arg * 2) : 0;
#else
  // The Parameter Save Area needs to be at least 8 double-word slots for ABIv1.
  // We have to add extra slots because ABIv1 uses a larger header.
  return MAX2(arg, 8) * 2 + additional_frame_header_slots;
#endif
}
#endif // COMPILER2

int SharedRuntime::vector_calling_convention(VMRegPair *regs,
                                             uint num_bits,
                                             uint total_args_passed) {
  Unimplemented();
  return 0;
}

static address gen_c2i_adapter(MacroAssembler *masm,
                            int total_args_passed,
                            int comp_args_on_stack,
                            const BasicType *sig_bt,
                            const VMRegPair *regs,
                            Label& call_interpreter,
                            const Register& ientry) {

  address c2i_entrypoint;

  const Register sender_SP = R21_sender_SP; // == R21_tmp1
  const Register code      = R22_tmp2;
  //const Register ientry  = R23_tmp3;
  const Register value_regs[] = { R24_tmp4, R25_tmp5, R26_tmp6 };
  const int num_value_regs = sizeof(value_regs) / sizeof(Register);
  int value_regs_index = 0;

  const Register return_pc = R27_tmp7;
  const Register tmp       = R28_tmp8;

  assert_different_registers(sender_SP, code, ientry, return_pc, tmp);

  // Adapter needs TOP_IJAVA_FRAME_ABI.
  const int adapter_size = frame::top_ijava_frame_abi_size +
                           align_up(total_args_passed * wordSize, frame::alignment_in_bytes);

  // regular (verified) c2i entry point
  c2i_entrypoint = __ pc();

  // Does compiled code exists? If yes, patch the caller's callsite.
  __ ld(code, method_(code));
  __ cmpdi(CR0, code, 0);
  __ ld(ientry, method_(interpreter_entry)); // preloaded
  __ beq(CR0, call_interpreter);


  // Patch caller's callsite, method_(code) was not null which means that
  // compiled code exists.
  __ mflr(return_pc);
  __ std(return_pc, _abi0(lr), R1_SP);
  RegisterSaver::push_frame_and_save_argument_registers(masm, tmp, adapter_size, total_args_passed, regs);

  __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::fixup_callers_callsite), R19_method, return_pc);

  RegisterSaver::restore_argument_registers_and_pop_frame(masm, adapter_size, total_args_passed, regs);
  __ ld(return_pc, _abi0(lr), R1_SP);
  __ ld(ientry, method_(interpreter_entry)); // preloaded
  __ mtlr(return_pc);


  // Call the interpreter.
  __ BIND(call_interpreter);
  __ mtctr(ientry);

  // Get a copy of the current SP for loading caller's arguments.
  __ mr(sender_SP, R1_SP);

  // Add space for the adapter.
  __ resize_frame(-adapter_size, R12_scratch2);

  int st_off = adapter_size - wordSize;

  // Write the args into the outgoing interpreter space.
  for (int i = 0; i < total_args_passed; i++) {
    VMReg r_1 = regs[i].first();
    VMReg r_2 = regs[i].second();
    if (!r_1->is_valid()) {
      assert(!r_2->is_valid(), "");
      continue;
    }
    if (r_1->is_stack()) {
      Register tmp_reg = value_regs[value_regs_index];
      value_regs_index = (value_regs_index + 1) % num_value_regs;
      // The calling convention produces OptoRegs that ignore the out
      // preserve area (JIT's ABI). We must account for it here.
      int ld_off = (r_1->reg2stack() + SharedRuntime::out_preserve_stack_slots()) * VMRegImpl::stack_slot_size;
      if (!r_2->is_valid()) {
        __ lwz(tmp_reg, ld_off, sender_SP);
      } else {
        __ ld(tmp_reg, ld_off, sender_SP);
      }
      // Pretend stack targets were loaded into tmp_reg.
      r_1 = tmp_reg->as_VMReg();
    }

    if (r_1->is_Register()) {
      Register r = r_1->as_Register();
      if (!r_2->is_valid()) {
        __ stw(r, st_off, R1_SP);
        st_off-=wordSize;
      } else {
        // Longs are given 2 64-bit slots in the interpreter, but the
        // data is passed in only 1 slot.
        if (sig_bt[i] == T_LONG || sig_bt[i] == T_DOUBLE) {
          DEBUG_ONLY( __ li(tmp, 0); __ std(tmp, st_off, R1_SP); )
          st_off-=wordSize;
        }
        __ std(r, st_off, R1_SP);
        st_off-=wordSize;
      }
    } else {
      assert(r_1->is_FloatRegister(), "");
      FloatRegister f = r_1->as_FloatRegister();
      if (!r_2->is_valid()) {
        __ stfs(f, st_off, R1_SP);
        st_off-=wordSize;
      } else {
        // In 64bit, doubles are given 2 64-bit slots in the interpreter, but the
        // data is passed in only 1 slot.
        // One of these should get known junk...
        DEBUG_ONLY( __ li(tmp, 0); __ std(tmp, st_off, R1_SP); )
        st_off-=wordSize;
        __ stfd(f, st_off, R1_SP);
        st_off-=wordSize;
      }
    }
  }

  // Jump to the interpreter just as if interpreter was doing it.

  __ load_const_optimized(R25_templateTableBase, (address)Interpreter::dispatch_table((TosState)0), R11_scratch1);

  // load TOS
  __ addi(R15_esp, R1_SP, st_off);

  // Frame_manager expects initial_caller_sp (= SP without resize by c2i) in R21_tmp1.
  assert(sender_SP == R21_sender_SP, "passing initial caller's SP in wrong register");
  __ bctr();

  return c2i_entrypoint;
}

void SharedRuntime::gen_i2c_adapter(MacroAssembler *masm,
                                    int total_args_passed,
                                    int comp_args_on_stack,
                                    const BasicType *sig_bt,
                                    const VMRegPair *regs) {

  // Load method's entry-point from method.
  __ ld(R12_scratch2, in_bytes(Method::from_compiled_offset()), R19_method);
  __ mtctr(R12_scratch2);

  // We will only enter here from an interpreted frame and never from after
  // passing thru a c2i. Azul allowed this but we do not. If we lose the
  // race and use a c2i we will remain interpreted for the race loser(s).
  // This removes all sorts of headaches on the x86 side and also eliminates
  // the possibility of having c2i -> i2c -> c2i -> ... endless transitions.

  // Note: r13 contains the senderSP on entry. We must preserve it since
  // we may do a i2c -> c2i transition if we lose a race where compiled
  // code goes non-entrant while we get args ready.
  // In addition we use r13 to locate all the interpreter args as
  // we must align the stack to 16 bytes on an i2c entry else we
  // lose alignment we expect in all compiled code and register
  // save code can segv when fxsave instructions find improperly
  // aligned stack pointer.

  const Register ld_ptr = R15_esp;
  const Register value_regs[] = { R22_tmp2, R23_tmp3, R24_tmp4, R25_tmp5, R26_tmp6 };
  const int num_value_regs = sizeof(value_regs) / sizeof(Register);
  int value_regs_index = 0;

  int ld_offset = total_args_passed*wordSize;

  // Cut-out for having no stack args. Since up to 2 int/oop args are passed
  // in registers, we will occasionally have no stack args.
  int comp_words_on_stack = 0;
  if (comp_args_on_stack) {
    // Sig words on the stack are greater-than VMRegImpl::stack0. Those in
    // registers are below. By subtracting stack0, we either get a negative
    // number (all values in registers) or the maximum stack slot accessed.

    // Convert 4-byte c2 stack slots to words.
    comp_words_on_stack = align_up(comp_args_on_stack*VMRegImpl::stack_slot_size, wordSize)>>LogBytesPerWord;
    // Round up to miminum stack alignment, in wordSize.
    comp_words_on_stack = align_up(comp_words_on_stack, 2);
    __ resize_frame(-comp_words_on_stack * wordSize, R11_scratch1);
  }

  // Now generate the shuffle code.  Pick up all register args and move the
  // rest through register value=Z_R12.
  BLOCK_COMMENT("Shuffle arguments");
  for (int i = 0; i < total_args_passed; i++) {
    if (sig_bt[i] == T_VOID) {
      assert(i > 0 && (sig_bt[i-1] == T_LONG || sig_bt[i-1] == T_DOUBLE), "missing half");
      continue;
    }

    // Pick up 0, 1 or 2 words from ld_ptr.
    assert(!regs[i].second()->is_valid() || regs[i].first()->next() == regs[i].second(),
            "scrambled load targets?");
    VMReg r_1 = regs[i].first();
    VMReg r_2 = regs[i].second();
    if (!r_1->is_valid()) {
      assert(!r_2->is_valid(), "");
      continue;
    }
    if (r_1->is_FloatRegister()) {
      if (!r_2->is_valid()) {
        __ lfs(r_1->as_FloatRegister(), ld_offset, ld_ptr);
        ld_offset-=wordSize;
      } else {
        // Skip the unused interpreter slot.
        __ lfd(r_1->as_FloatRegister(), ld_offset-wordSize, ld_ptr);
        ld_offset-=2*wordSize;
      }
    } else {
      Register r;
      if (r_1->is_stack()) {
        // Must do a memory to memory move thru "value".
        r = value_regs[value_regs_index];
        value_regs_index = (value_regs_index + 1) % num_value_regs;
      } else {
        r = r_1->as_Register();
      }
      if (!r_2->is_valid()) {
        // Not sure we need to do this but it shouldn't hurt.
        if (is_reference_type(sig_bt[i]) || sig_bt[i] == T_ADDRESS) {
          __ ld(r, ld_offset, ld_ptr);
          ld_offset-=wordSize;
        } else {
          __ lwz(r, ld_offset, ld_ptr);
          ld_offset-=wordSize;
        }
      } else {
        // In 64bit, longs are given 2 64-bit slots in the interpreter, but the
        // data is passed in only 1 slot.
        if (sig_bt[i] == T_LONG || sig_bt[i] == T_DOUBLE) {
          ld_offset-=wordSize;
        }
        __ ld(r, ld_offset, ld_ptr);
        ld_offset-=wordSize;
      }

      if (r_1->is_stack()) {
        // Now store value where the compiler expects it
        int st_off = (r_1->reg2stack() + SharedRuntime::out_preserve_stack_slots())*VMRegImpl::stack_slot_size;

        if (sig_bt[i] == T_INT   || sig_bt[i] == T_FLOAT ||sig_bt[i] == T_BOOLEAN ||
            sig_bt[i] == T_SHORT || sig_bt[i] == T_CHAR  || sig_bt[i] == T_BYTE) {
          __ stw(r, st_off, R1_SP);
        } else {
          __ std(r, st_off, R1_SP);
        }
      }
    }
  }

  __ push_cont_fastpath(); // Set JavaThread::_cont_fastpath to the sp of the oldest interpreted frame we know about

  BLOCK_COMMENT("Store method");
  // Store method into thread->callee_target.
  // We might end up in handle_wrong_method if the callee is
  // deoptimized as we race thru here. If that happens we don't want
  // to take a safepoint because the caller frame will look
  // interpreted and arguments are now "compiled" so it is much better
  // to make this transition invisible to the stack walking
  // code. Unfortunately if we try and find the callee by normal means
  // a safepoint is possible. So we stash the desired callee in the
  // thread and the vm will find there should this case occur.
  __ std(R19_method, thread_(callee_target));

  // Jump to the compiled code just as if compiled code was doing it.
  __ bctr();
}

void SharedRuntime::generate_i2c2i_adapters(MacroAssembler *masm,
                                            int total_args_passed,
                                            int comp_args_on_stack,
                                            const BasicType *sig_bt,
                                            const VMRegPair *regs,
                                            AdapterHandlerEntry* handler) {
  address i2c_entry;
  address c2i_unverified_entry;
  address c2i_entry;


  // entry: i2c

  __ align(CodeEntryAlignment);
  i2c_entry = __ pc();
  gen_i2c_adapter(masm, total_args_passed, comp_args_on_stack, sig_bt, regs);


  // entry: c2i unverified

  __ align(CodeEntryAlignment);
  BLOCK_COMMENT("c2i unverified entry");
  c2i_unverified_entry = __ pc();

  // inline_cache contains a CompiledICData
  const Register ic             = R19_inline_cache_reg;
  const Register ic_klass       = R11_scratch1;
  const Register receiver_klass = R12_scratch2;
  const Register code           = R21_tmp1;
  const Register ientry         = R23_tmp3;

  assert_different_registers(ic, ic_klass, receiver_klass, R3_ARG1, code, ientry);
  assert(R11_scratch1 == R11, "need prologue scratch register");

  Label call_interpreter;

  __ ic_check(4 /* end_alignment */);
  __ ld(R19_method, CompiledICData::speculated_method_offset(), ic);
  // Argument is valid and klass is as expected, continue.

  __ ld(code, method_(code));
  __ cmpdi(CR0, code, 0);
  __ ld(ientry, method_(interpreter_entry)); // preloaded
  __ beq_predict_taken(CR0, call_interpreter);

  // Branch to ic_miss_stub.
  __ b64_patchable((address)SharedRuntime::get_ic_miss_stub(), relocInfo::runtime_call_type);

  // entry: c2i

  c2i_entry = __ pc();

  // Class initialization barrier for static methods
  address c2i_no_clinit_check_entry = nullptr;
  if (VM_Version::supports_fast_class_init_checks()) {
    Label L_skip_barrier;

    { // Bypass the barrier for non-static methods
      __ lhz(R0, in_bytes(Method::access_flags_offset()), R19_method);
      __ andi_(R0, R0, JVM_ACC_STATIC);
      __ beq(CR0, L_skip_barrier); // non-static
    }

    Register klass = R11_scratch1;
    __ load_method_holder(klass, R19_method);
    __ clinit_barrier(klass, R16_thread, &L_skip_barrier /*L_fast_path*/);

    __ load_const_optimized(klass, SharedRuntime::get_handle_wrong_method_stub(), R0);
    __ mtctr(klass);
    __ bctr();

    __ bind(L_skip_barrier);
    c2i_no_clinit_check_entry = __ pc();
  }

  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->c2i_entry_barrier(masm, /* tmp register*/ ic_klass, /* tmp register*/ receiver_klass, /* tmp register*/ code);

  gen_c2i_adapter(masm, total_args_passed, comp_args_on_stack, sig_bt, regs, call_interpreter, ientry);

  handler->set_entry_points(i2c_entry, c2i_entry, c2i_unverified_entry, c2i_no_clinit_check_entry);
  return;
}

// An oop arg. Must pass a handle not the oop itself.
static void object_move(MacroAssembler* masm,
                        int frame_size_in_slots,
                        OopMap* oop_map, int oop_handle_offset,
                        bool is_receiver, int* receiver_offset,
                        VMRegPair src, VMRegPair dst,
                        Register r_caller_sp, Register r_temp_1, Register r_temp_2) {
  assert(!is_receiver || (is_receiver && (*receiver_offset == -1)),
         "receiver has already been moved");

  // We must pass a handle. First figure out the location we use as a handle.

  if (src.first()->is_stack()) {
    // stack to stack or reg

    const Register r_handle = dst.first()->is_stack() ? r_temp_1 : dst.first()->as_Register();
    Label skip;
    const int oop_slot_in_callers_frame = reg2slot(src.first());

    guarantee(!is_receiver, "expecting receiver in register");
    oop_map->set_oop(VMRegImpl::stack2reg(oop_slot_in_callers_frame + frame_size_in_slots));

    __ addi(r_handle, r_caller_sp, reg2offset(src.first()));
    __ ld(  r_temp_2, reg2offset(src.first()), r_caller_sp);
    __ cmpdi(CR0, r_temp_2, 0);
    __ bne(CR0, skip);
    // Use a null handle if oop is null.
    __ li(r_handle, 0);
    __ bind(skip);

    if (dst.first()->is_stack()) {
      // stack to stack
      __ std(r_handle, reg2offset(dst.first()), R1_SP);
    } else {
      // stack to reg
      // Nothing to do, r_handle is already the dst register.
    }
  } else {
    // reg to stack or reg
    const Register r_oop      = src.first()->as_Register();
    const Register r_handle   = dst.first()->is_stack() ? r_temp_1 : dst.first()->as_Register();
    const int oop_slot        = (r_oop->encoding()-R3_ARG1->encoding()) * VMRegImpl::slots_per_word
                                + oop_handle_offset; // in slots
    const int oop_offset = oop_slot * VMRegImpl::stack_slot_size;
    Label skip;

    if (is_receiver) {
      *receiver_offset = oop_offset;
    }
    oop_map->set_oop(VMRegImpl::stack2reg(oop_slot));

    __ std( r_oop,    oop_offset, R1_SP);
    __ addi(r_handle, R1_SP, oop_offset);

    __ cmpdi(CR0, r_oop, 0);
    __ bne(CR0, skip);
    // Use a null handle if oop is null.
    __ li(r_handle, 0);
    __ bind(skip);

    if (dst.first()->is_stack()) {
      // reg to stack
      __ std(r_handle, reg2offset(dst.first()), R1_SP);
    } else {
      // reg to reg
      // Nothing to do, r_handle is already the dst register.
    }
  }
}

static void int_move(MacroAssembler*masm,
                     VMRegPair src, VMRegPair dst,
                     Register r_caller_sp, Register r_temp) {
  assert(src.first()->is_valid(), "incoming must be int");
  assert(dst.first()->is_valid() && dst.second() == dst.first()->next(), "outgoing must be long");

  if (src.first()->is_stack()) {
    if (dst.first()->is_stack()) {
      // stack to stack
      __ lwa(r_temp, reg2offset(src.first()), r_caller_sp);
      __ std(r_temp, reg2offset(dst.first()), R1_SP);
    } else {
      // stack to reg
      __ lwa(dst.first()->as_Register(), reg2offset(src.first()), r_caller_sp);
    }
  } else if (dst.first()->is_stack()) {
    // reg to stack
    __ extsw(r_temp, src.first()->as_Register());
    __ std(r_temp, reg2offset(dst.first()), R1_SP);
  } else {
    // reg to reg
    __ extsw(dst.first()->as_Register(), src.first()->as_Register());
  }
}

static void long_move(MacroAssembler*masm,
                      VMRegPair src, VMRegPair dst,
                      Register r_caller_sp, Register r_temp) {
  assert(src.first()->is_valid() && src.second() == src.first()->next(), "incoming must be long");
  assert(dst.first()->is_valid() && dst.second() == dst.first()->next(), "outgoing must be long");

  if (src.first()->is_stack()) {
    if (dst.first()->is_stack()) {
      // stack to stack
      __ ld( r_temp, reg2offset(src.first()), r_caller_sp);
      __ std(r_temp, reg2offset(dst.first()), R1_SP);
    } else {
      // stack to reg
      __ ld(dst.first()->as_Register(), reg2offset(src.first()), r_caller_sp);
    }
  } else if (dst.first()->is_stack()) {
    // reg to stack
    __ std(src.first()->as_Register(), reg2offset(dst.first()), R1_SP);
  } else {
    // reg to reg
    if (dst.first()->as_Register() != src.first()->as_Register())
      __ mr(dst.first()->as_Register(), src.first()->as_Register());
  }
}

static void float_move(MacroAssembler*masm,
                       VMRegPair src, VMRegPair dst,
                       Register r_caller_sp, Register r_temp) {
  assert(src.first()->is_valid() && !src.second()->is_valid(), "incoming must be float");
  assert(dst.first()->is_valid() && !dst.second()->is_valid(), "outgoing must be float");

  if (src.first()->is_stack()) {
    if (dst.first()->is_stack()) {
      // stack to stack
      __ lwz(r_temp, reg2offset(src.first()), r_caller_sp);
      __ stw(r_temp, reg2offset(dst.first()), R1_SP);
    } else {
      // stack to reg
      __ lfs(dst.first()->as_FloatRegister(), reg2offset(src.first()), r_caller_sp);
    }
  } else if (dst.first()->is_stack()) {
    // reg to stack
    __ stfs(src.first()->as_FloatRegister(), reg2offset(dst.first()), R1_SP);
  } else {
    // reg to reg
    if (dst.first()->as_FloatRegister() != src.first()->as_FloatRegister())
      __ fmr(dst.first()->as_FloatRegister(), src.first()->as_FloatRegister());
  }
}

static void double_move(MacroAssembler*masm,
                        VMRegPair src, VMRegPair dst,
                        Register r_caller_sp, Register r_temp) {
  assert(src.first()->is_valid() && src.second() == src.first()->next(), "incoming must be double");
  assert(dst.first()->is_valid() && dst.second() == dst.first()->next(), "outgoing must be double");

  if (src.first()->is_stack()) {
    if (dst.first()->is_stack()) {
      // stack to stack
      __ ld( r_temp, reg2offset(src.first()), r_caller_sp);
      __ std(r_temp, reg2offset(dst.first()), R1_SP);
    } else {
      // stack to reg
      __ lfd(dst.first()->as_FloatRegister(), reg2offset(src.first()), r_caller_sp);
    }
  } else if (dst.first()->is_stack()) {
    // reg to stack
    __ stfd(src.first()->as_FloatRegister(), reg2offset(dst.first()), R1_SP);
  } else {
    // reg to reg
    if (dst.first()->as_FloatRegister() != src.first()->as_FloatRegister())
      __ fmr(dst.first()->as_FloatRegister(), src.first()->as_FloatRegister());
  }
}

void SharedRuntime::save_native_result(MacroAssembler *masm, BasicType ret_type, int frame_slots) {
  switch (ret_type) {
    case T_BOOLEAN:
    case T_CHAR:
    case T_BYTE:
    case T_SHORT:
    case T_INT:
      __ stw (R3_RET,  frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_ARRAY:
    case T_OBJECT:
    case T_LONG:
      __ std (R3_RET,  frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_FLOAT:
      __ stfs(F1_RET, frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_DOUBLE:
      __ stfd(F1_RET, frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_VOID:
      break;
    default:
      ShouldNotReachHere();
      break;
  }
}

void SharedRuntime::restore_native_result(MacroAssembler *masm, BasicType ret_type, int frame_slots) {
  switch (ret_type) {
    case T_BOOLEAN:
    case T_CHAR:
    case T_BYTE:
    case T_SHORT:
    case T_INT:
      __ lwz(R3_RET,  frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_ARRAY:
    case T_OBJECT:
    case T_LONG:
      __ ld (R3_RET,  frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_FLOAT:
      __ lfs(F1_RET, frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_DOUBLE:
      __ lfd(F1_RET, frame_slots*VMRegImpl::stack_slot_size, R1_SP);
      break;
    case T_VOID:
      break;
    default:
      ShouldNotReachHere();
      break;
  }
}

static void verify_oop_args(MacroAssembler* masm,
                            const methodHandle& method,
                            const BasicType* sig_bt,
                            const VMRegPair* regs) {
  Register temp_reg = R19_method;  // not part of any compiled calling seq
  if (VerifyOops) {
    for (int i = 0; i < method->size_of_parameters(); i++) {
      if (is_reference_type(sig_bt[i])) {
        VMReg r = regs[i].first();
        assert(r->is_valid(), "bad oop arg");
        if (r->is_stack()) {
          __ ld(temp_reg, reg2offset(r), R1_SP);
          __ verify_oop(temp_reg, FILE_AND_LINE);
        } else {
          __ verify_oop(r->as_Register(), FILE_AND_LINE);
        }
      }
    }
  }
}

static void gen_special_dispatch(MacroAssembler* masm,
                                 const methodHandle& method,
                                 const BasicType* sig_bt,
                                 const VMRegPair* regs) {
  verify_oop_args(masm, method, sig_bt, regs);
  vmIntrinsics::ID iid = method->intrinsic_id();

  // Now write the args into the outgoing interpreter space
  bool     has_receiver   = false;
  Register receiver_reg   = noreg;
  int      member_arg_pos = -1;
  Register member_reg     = noreg;
  int      ref_kind       = MethodHandles::signature_polymorphic_intrinsic_ref_kind(iid);
  if (ref_kind != 0) {
    member_arg_pos = method->size_of_parameters() - 1;  // trailing MemberName argument
    member_reg = R19_method;  // known to be free at this point
    has_receiver = MethodHandles::ref_kind_has_receiver(ref_kind);
  } else if (iid == vmIntrinsics::_invokeBasic) {
    has_receiver = true;
  } else if (iid == vmIntrinsics::_linkToNative) {
    member_arg_pos = method->size_of_parameters() - 1;  // trailing NativeEntryPoint argument
    member_reg = R19_method;  // known to be free at this point
  } else {
    fatal("unexpected intrinsic id %d", vmIntrinsics::as_int(iid));
  }

  if (member_reg != noreg) {
    // Load the member_arg into register, if necessary.
    SharedRuntime::check_member_name_argument_is_last_argument(method, sig_bt, regs);
    VMReg r = regs[member_arg_pos].first();
    if (r->is_stack()) {
      __ ld(member_reg, reg2offset(r), R1_SP);
    } else {
      // no data motion is needed
      member_reg = r->as_Register();
    }
  }

  if (has_receiver) {
    // Make sure the receiver is loaded into a register.
    assert(method->size_of_parameters() > 0, "oob");
    assert(sig_bt[0] == T_OBJECT, "receiver argument must be an object");
    VMReg r = regs[0].first();
    assert(r->is_valid(), "bad receiver arg");
    if (r->is_stack()) {
      // Porting note:  This assumes that compiled calling conventions always
      // pass the receiver oop in a register.  If this is not true on some
      // platform, pick a temp and load the receiver from stack.
      fatal("receiver always in a register");
      receiver_reg = R11_scratch1;  // TODO (hs24): is R11_scratch1 really free at this point?
      __ ld(receiver_reg, reg2offset(r), R1_SP);
    } else {
      // no data motion is needed
      receiver_reg = r->as_Register();
    }
  }

  // Figure out which address we are really jumping to:
  MethodHandles::generate_method_handle_dispatch(masm, iid,
                                                 receiver_reg, member_reg, /*for_compiler_entry:*/ true);
}

//---------------------------- continuation_enter_setup ---------------------------
//
// Frame setup.
//
// Arguments:
//   None.
//
// Results:
//   R1_SP: pointer to blank ContinuationEntry in the pushed frame.
//
// Kills:
//   R0, R20
//
static OopMap* continuation_enter_setup(MacroAssembler* masm, int& framesize_words) {
  assert(ContinuationEntry::size() % VMRegImpl::stack_slot_size == 0, "");
  assert(in_bytes(ContinuationEntry::cont_offset())  % VMRegImpl::stack_slot_size == 0, "");
  assert(in_bytes(ContinuationEntry::chunk_offset()) % VMRegImpl::stack_slot_size == 0, "");

  const int frame_size_in_bytes = (int)ContinuationEntry::size();
  assert(is_aligned(frame_size_in_bytes, frame::alignment_in_bytes), "alignment error");

  framesize_words = frame_size_in_bytes / wordSize;

  DEBUG_ONLY(__ block_comment("setup {"));
  // Save return pc and push entry frame
  const Register return_pc = R20;
  __ mflr(return_pc);
  __ std(return_pc, _abi0(lr), R1_SP);     // SP->lr = return_pc
  __ push_frame(frame_size_in_bytes , R0); // SP -= frame_size_in_bytes

  OopMap* map = new OopMap((int)frame_size_in_bytes / VMRegImpl::stack_slot_size, 0 /* arg_slots*/);

  __ ld_ptr(R0, JavaThread::cont_entry_offset(), R16_thread);
  __ st_ptr(R1_SP, JavaThread::cont_entry_offset(), R16_thread);
  __ st_ptr(R0, ContinuationEntry::parent_offset(), R1_SP);
  DEBUG_ONLY(__ block_comment("} setup"));

  return map;
}

//---------------------------- fill_continuation_entry ---------------------------
//
// Initialize the new ContinuationEntry.
//
// Arguments:
//   R1_SP: pointer to blank Continuation entry
//   reg_cont_obj: pointer to the continuation
//   reg_flags: flags
//
// Results:
//   R1_SP: pointer to filled out ContinuationEntry
//
// Kills:
//   R8_ARG6, R9_ARG7, R10_ARG8
//
static void fill_continuation_entry(MacroAssembler* masm, Register reg_cont_obj, Register reg_flags) {
  assert_different_registers(reg_cont_obj, reg_flags);
  Register zero = R8_ARG6;
  Register tmp2 = R9_ARG7;
  Register tmp3 = R10_ARG8;

  DEBUG_ONLY(__ block_comment("fill {"));
#ifdef ASSERT
  __ load_const_optimized(tmp2, ContinuationEntry::cookie_value());
  __ stw(tmp2, in_bytes(ContinuationEntry::cookie_offset()), R1_SP);
#endif //ASSERT

  __ li(zero, 0);
  __ st_ptr(reg_cont_obj, ContinuationEntry::cont_offset(), R1_SP);
  __ stw(reg_flags, in_bytes(ContinuationEntry::flags_offset()), R1_SP);
  __ st_ptr(zero, ContinuationEntry::chunk_offset(), R1_SP);
  __ stw(zero, in_bytes(ContinuationEntry::argsize_offset()), R1_SP);
  __ stw(zero, in_bytes(ContinuationEntry::pin_count_offset()), R1_SP);

  __ ld_ptr(tmp2, JavaThread::cont_fastpath_offset(), R16_thread);
  __ ld(tmp3, in_bytes(JavaThread::held_monitor_count_offset()), R16_thread);
  __ st_ptr(tmp2, ContinuationEntry::parent_cont_fastpath_offset(), R1_SP);
  __ std(tmp3, in_bytes(ContinuationEntry::parent_held_monitor_count_offset()), R1_SP);

  __ st_ptr(zero, JavaThread::cont_fastpath_offset(), R16_thread);
  __ std(zero, in_bytes(JavaThread::held_monitor_count_offset()), R16_thread);
  DEBUG_ONLY(__ block_comment("} fill"));
}

//---------------------------- continuation_enter_cleanup ---------------------------
//
// Copy corresponding attributes from the top ContinuationEntry to the JavaThread
// before deleting it.
//
// Arguments:
//   R1_SP: pointer to the ContinuationEntry
//
// Results:
//   None.
//
// Kills:
//   R8_ARG6, R9_ARG7, R10_ARG8, R15_esp
//
static void continuation_enter_cleanup(MacroAssembler* masm) {
  Register tmp1 = R8_ARG6;
  Register tmp2 = R9_ARG7;
  Register tmp3 = R10_ARG8;

#ifdef ASSERT
  __ block_comment("clean {");
  __ ld_ptr(tmp1, JavaThread::cont_entry_offset(), R16_thread);
  __ cmpd(CR0, R1_SP, tmp1);
  __ asm_assert_eq(FILE_AND_LINE ": incorrect R1_SP");
#endif

  __ ld_ptr(tmp1, ContinuationEntry::parent_cont_fastpath_offset(), R1_SP);
  __ st_ptr(tmp1, JavaThread::cont_fastpath_offset(), R16_thread);

  if (CheckJNICalls) {
    // Check if this is a virtual thread continuation
    Label L_skip_vthread_code;
    __ lwz(R0, in_bytes(ContinuationEntry::flags_offset()), R1_SP);
    __ cmpwi(CR0, R0, 0);
    __ beq(CR0, L_skip_vthread_code);

    // If the held monitor count is > 0 and this vthread is terminating then
    // it failed to release a JNI monitor. So we issue the same log message
    // that JavaThread::exit does.
    __ ld(R0, in_bytes(JavaThread::jni_monitor_count_offset()), R16_thread);
    __ cmpdi(CR0, R0, 0);
    __ beq(CR0, L_skip_vthread_code);

    // Save return value potentially containing the exception oop
    Register ex_oop = R15_esp;   // nonvolatile register
    __ mr(ex_oop, R3_RET);
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::log_jni_monitor_still_held));
    // Restore potental return value
    __ mr(R3_RET, ex_oop);

    // For vthreads we have to explicitly zero the JNI monitor count of the carrier
    // on termination. The held count is implicitly zeroed below when we restore from
    // the parent held count (which has to be zero).
    __ li(tmp1, 0);
    __ std(tmp1, in_bytes(JavaThread::jni_monitor_count_offset()), R16_thread);

    __ bind(L_skip_vthread_code);
  }
#ifdef ASSERT
  else {
    // Check if this is a virtual thread continuation
    Label L_skip_vthread_code;
    __ lwz(R0, in_bytes(ContinuationEntry::flags_offset()), R1_SP);
    __ cmpwi(CR0, R0, 0);
    __ beq(CR0, L_skip_vthread_code);

    // See comment just above. If not checking JNI calls the JNI count is only
    // needed for assertion checking.
    __ li(tmp1, 0);
    __ std(tmp1, in_bytes(JavaThread::jni_monitor_count_offset()), R16_thread);

    __ bind(L_skip_vthread_code);
  }
#endif

  __ ld(tmp2, in_bytes(ContinuationEntry::parent_held_monitor_count_offset()), R1_SP);
  __ ld_ptr(tmp3, ContinuationEntry::parent_offset(), R1_SP);
  __ std(tmp2, in_bytes(JavaThread::held_monitor_count_offset()), R16_thread);
  __ st_ptr(tmp3, JavaThread::cont_entry_offset(), R16_thread);
  DEBUG_ONLY(__ block_comment("} clean"));
}

static void check_continuation_enter_argument(VMReg actual_vmreg,
                                              Register expected_reg,
                                              const char* name) {
  assert(!actual_vmreg->is_stack(), "%s cannot be on stack", name);
  assert(actual_vmreg->as_Register() == expected_reg,
         "%s is in unexpected register: %s instead of %s",
         name, actual_vmreg->as_Register()->name(), expected_reg->name());
}

static void gen_continuation_enter(MacroAssembler* masm,
                                   const VMRegPair* regs,
                                   int& exception_offset,
                                   OopMapSet* oop_maps,
                                   int& frame_complete,
                                   int& framesize_words,
                                   int& interpreted_entry_offset,
                                   int& compiled_entry_offset) {

  // enterSpecial(Continuation c, boolean isContinue, boolean isVirtualThread)
  int pos_cont_obj   = 0;
  int pos_is_cont    = 1;
  int pos_is_virtual = 2;

  // The platform-specific calling convention may present the arguments in various registers.
  // To simplify the rest of the code, we expect the arguments to reside at these known
  // registers, and we additionally check the placement here in case calling convention ever
  // changes.
  Register reg_cont_obj   = R3_ARG1;
  Register reg_is_cont    = R4_ARG2;
  Register reg_is_virtual = R5_ARG3;

  check_continuation_enter_argument(regs[pos_cont_obj].first(),   reg_cont_obj,   "Continuation object");
  check_continuation_enter_argument(regs[pos_is_cont].first(),    reg_is_cont,    "isContinue");
  check_continuation_enter_argument(regs[pos_is_virtual].first(), reg_is_virtual, "isVirtualThread");

  address resolve_static_call = SharedRuntime::get_resolve_static_call_stub();

  address start = __ pc();

  Label L_thaw, L_exit;

  // i2i entry used at interp_only_mode only
  interpreted_entry_offset = __ pc() - start;
  {
#ifdef ASSERT
    Label is_interp_only;
    __ lwz(R0, in_bytes(JavaThread::interp_only_mode_offset()), R16_thread);
    __ cmpwi(CR0, R0, 0);
    __ bne(CR0, is_interp_only);
    __ stop("enterSpecial interpreter entry called when not in interp_only_mode");
    __ bind(is_interp_only);
#endif

    // Read interpreter arguments into registers (this is an ad-hoc i2c adapter)
    __ ld(reg_cont_obj,    Interpreter::stackElementSize*3, R15_esp);
    __ lwz(reg_is_cont,    Interpreter::stackElementSize*2, R15_esp);
    __ lwz(reg_is_virtual, Interpreter::stackElementSize*1, R15_esp);

    __ push_cont_fastpath();

    OopMap* map = continuation_enter_setup(masm, framesize_words);

    // The frame is complete here, but we only record it for the compiled entry, so the frame would appear unsafe,
    // but that's okay because at the very worst we'll miss an async sample, but we're in interp_only_mode anyway.

    fill_continuation_entry(masm, reg_cont_obj, reg_is_virtual);

    // If isContinue, call to thaw. Otherwise, call Continuation.enter(Continuation c, boolean isContinue)
    __ cmpwi(CR0, reg_is_cont, 0);
    __ bne(CR0, L_thaw);

    // --- call Continuation.enter(Continuation c, boolean isContinue)

    // Emit compiled static call. The call will be always resolved to the c2i
    // entry of Continuation.enter(Continuation c, boolean isContinue).
    // There are special cases in SharedRuntime::resolve_static_call_C() and
    // SharedRuntime::resolve_sub_helper_internal() to achieve this
    // See also corresponding call below.
    address c2i_call_pc = __ pc();
    int start_offset = __ offset();
    // Put the entry point as a constant into the constant pool.
    const address entry_point_toc_addr   = __ address_constant(resolve_static_call, RelocationHolder::none);
    const int     entry_point_toc_offset = __ offset_to_method_toc(entry_point_toc_addr);
    guarantee(entry_point_toc_addr != nullptr, "const section overflow");

    // Emit the trampoline stub which will be related to the branch-and-link below.
    address stub = __ emit_trampoline_stub(entry_point_toc_offset, start_offset);
    guarantee(stub != nullptr, "no space for trampoline stub");

    __ relocate(relocInfo::static_call_type);
    // Note: At this point we do not have the address of the trampoline
    // stub, and the entry point might be too far away for bl, so __ pc()
    // serves as dummy and the bl will be patched later.
    __ bl(__ pc());
    oop_maps->add_gc_map(__ pc() - start, map);
    __ post_call_nop();

    __ b(L_exit);

    // static stub for the call above
    stub = CompiledDirectCall::emit_to_interp_stub(masm, c2i_call_pc);
    guarantee(stub != nullptr, "no space for static stub");
  }

  // compiled entry
  __ align(CodeEntryAlignment);
  compiled_entry_offset = __ pc() - start;

  OopMap* map = continuation_enter_setup(masm, framesize_words);

  // Frame is now completed as far as size and linkage.
  frame_complete =__ pc() - start;

  fill_continuation_entry(masm, reg_cont_obj, reg_is_virtual);

  // If isContinue, call to thaw. Otherwise, call Continuation.enter(Continuation c, boolean isContinue)
  __ cmpwi(CR0, reg_is_cont, 0);
  __ bne(CR0, L_thaw);

  // --- call Continuation.enter(Continuation c, boolean isContinue)

  // Emit compiled static call
  // The call needs to be resolved. There's a special case for this in
  // SharedRuntime::find_callee_info_helper() which calls
  // LinkResolver::resolve_continuation_enter() which resolves the call to
  // Continuation.enter(Continuation c, boolean isContinue).
  address call_pc = __ pc();
  int start_offset = __ offset();
  // Put the entry point as a constant into the constant pool.
  const address entry_point_toc_addr   = __ address_constant(resolve_static_call, RelocationHolder::none);
  const int     entry_point_toc_offset = __ offset_to_method_toc(entry_point_toc_addr);
  guarantee(entry_point_toc_addr != nullptr, "const section overflow");

  // Emit the trampoline stub which will be related to the branch-and-link below.
  address stub = __ emit_trampoline_stub(entry_point_toc_offset, start_offset);
  guarantee(stub != nullptr, "no space for trampoline stub");

  __ relocate(relocInfo::static_call_type);
  // Note: At this point we do not have the address of the trampoline
  // stub, and the entry point might be too far away for bl, so __ pc()
  // serves as dummy and the bl will be patched later.
  __ bl(__ pc());
  oop_maps->add_gc_map(__ pc() - start, map);
  __ post_call_nop();

  __ b(L_exit);

  // --- Thawing path

  __ bind(L_thaw);
  ContinuationEntry::_thaw_call_pc_offset = __ pc() - start;
  __ add_const_optimized(R0, R29_TOC, MacroAssembler::offset_to_global_toc(StubRoutines::cont_thaw()));
  __ mtctr(R0);
  __ bctrl();
  oop_maps->add_gc_map(__ pc() - start, map->deep_copy());
  ContinuationEntry::_return_pc_offset = __ pc() - start;
  __ post_call_nop();

  // --- Normal exit (resolve/thawing)

  __ bind(L_exit);
  ContinuationEntry::_cleanup_offset = __ pc() - start;
  continuation_enter_cleanup(masm);

  // Pop frame and return
  DEBUG_ONLY(__ ld_ptr(R0, 0, R1_SP));
  __ addi(R1_SP, R1_SP, framesize_words*wordSize);
  DEBUG_ONLY(__ cmpd(CR0, R0, R1_SP));
  __ asm_assert_eq(FILE_AND_LINE ": inconsistent frame size");
  __ ld(R0, _abi0(lr), R1_SP); // Return pc
  __ mtlr(R0);
  __ blr();

  // --- Exception handling path

  exception_offset = __ pc() - start;

  continuation_enter_cleanup(masm);
  Register ex_pc  = R17_tos;   // nonvolatile register
  Register ex_oop = R15_esp;   // nonvolatile register
  __ ld(ex_pc, _abi0(callers_sp), R1_SP); // Load caller's return pc
  __ ld(ex_pc, _abi0(lr), ex_pc);
  __ mr(ex_oop, R3_RET);                  // save return value containing the exception oop
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::exception_handler_for_return_address), R16_thread, ex_pc);
  __ mtlr(R3_RET);                        // the exception handler
  __ ld(R1_SP, _abi0(callers_sp), R1_SP); // remove enterSpecial frame

  // Continue at exception handler
  // See OptoRuntime::generate_exception_blob for register arguments
  __ mr(R3_ARG1, ex_oop); // pass exception oop
  __ mr(R4_ARG2, ex_pc);  // pass exception pc
  __ blr();

  // static stub for the call above
  stub = CompiledDirectCall::emit_to_interp_stub(masm, call_pc);
  guarantee(stub != nullptr, "no space for static stub");
}

static void gen_continuation_yield(MacroAssembler* masm,
                                   const VMRegPair* regs,
                                   OopMapSet* oop_maps,
                                   int& frame_complete,
                                   int& framesize_words,
                                   int& compiled_entry_offset) {
  Register tmp = R10_ARG8;

  const int framesize_bytes = (int)align_up((int)frame::native_abi_reg_args_size, frame::alignment_in_bytes);
  framesize_words = framesize_bytes / wordSize;

  address start = __ pc();
  compiled_entry_offset = __ pc() - start;

  // Save return pc and push entry frame
  __ mflr(tmp);
  __ std(tmp, _abi0(lr), R1_SP);       // SP->lr = return_pc
  __ push_frame(framesize_bytes , R0); // SP -= frame_size_in_bytes

  DEBUG_ONLY(__ block_comment("Frame Complete"));
  frame_complete = __ pc() - start;
  address last_java_pc = __ pc();

  // This nop must be exactly at the PC we push into the frame info.
  // We use this nop for fast CodeBlob lookup, associate the OopMap
  // with it right away.
  __ post_call_nop();
  OopMap* map = new OopMap(framesize_bytes / VMRegImpl::stack_slot_size, 1);
  oop_maps->add_gc_map(last_java_pc - start, map);

  __ calculate_address_from_global_toc(tmp, last_java_pc); // will be relocated
  __ set_last_Java_frame(R1_SP, tmp);
  __ call_VM_leaf(Continuation::freeze_entry(), R16_thread, R1_SP);
  __ reset_last_Java_frame();

  Label L_pinned;

  __ cmpwi(CR0, R3_RET, 0);
  __ bne(CR0, L_pinned);

  // yield succeeded

  // Pop frames of continuation including this stub's frame
  __ ld_ptr(R1_SP, JavaThread::cont_entry_offset(), R16_thread);
  // The frame pushed by gen_continuation_enter is on top now again
  continuation_enter_cleanup(masm);

  // Pop frame and return
  Label L_return;
  __ bind(L_return);
  __ pop_frame();
  __ ld(R0, _abi0(lr), R1_SP); // Return pc
  __ mtlr(R0);
  __ blr();

  // yield failed - continuation is pinned

  __ bind(L_pinned);

  // handle pending exception thrown by freeze
  __ ld(tmp, in_bytes(JavaThread::pending_exception_offset()), R16_thread);
  __ cmpdi(CR0, tmp, 0);
  __ beq(CR0, L_return); // return if no exception is pending
  __ pop_frame();
  __ ld(R0, _abi0(lr), R1_SP); // Return pc
  __ mtlr(R0);
  __ load_const_optimized(tmp, StubRoutines::forward_exception_entry(), R0);
  __ mtctr(tmp);
  __ bctr();
}

void SharedRuntime::continuation_enter_cleanup(MacroAssembler* masm) {
  ::continuation_enter_cleanup(masm);
}

// ---------------------------------------------------------------------------
// Generate a native wrapper for a given method. The method takes arguments
// in the Java compiled code convention, marshals them to the native
// convention (handlizes oops, etc), transitions to native, makes the call,
// returns to java state (possibly blocking), unhandlizes any result and
// returns.
//
// Critical native functions are a shorthand for the use of
// GetPrimtiveArrayCritical and disallow the use of any other JNI
// functions.  The wrapper is expected to unpack the arguments before
// passing them to the callee. Critical native functions leave the state _in_Java,
// since they cannot stop for GC.
// Some other parts of JNI setup are skipped like the tear down of the JNI handle
// block and the check for pending exceptions it's impossible for them
// to be thrown.
//
nmethod *SharedRuntime::generate_native_wrapper(MacroAssembler *masm,
                                                const methodHandle& method,
                                                int compile_id,
                                                BasicType *in_sig_bt,
                                                VMRegPair *in_regs,
                                                BasicType ret_type) {
  if (method->is_continuation_native_intrinsic()) {
    int exception_offset = -1;
    OopMapSet* oop_maps = new OopMapSet();
    int frame_complete = -1;
    int stack_slots = -1;
    int interpreted_entry_offset = -1;
    int vep_offset = -1;
    if (method->is_continuation_enter_intrinsic()) {
      gen_continuation_enter(masm,
                             in_regs,
                             exception_offset,
                             oop_maps,
                             frame_complete,
                             stack_slots,
                             interpreted_entry_offset,
                             vep_offset);
    } else if (method->is_continuation_yield_intrinsic()) {
      gen_continuation_yield(masm,
                             in_regs,
                             oop_maps,
                             frame_complete,
                             stack_slots,
                             vep_offset);
    } else {
      guarantee(false, "Unknown Continuation native intrinsic");
    }

#ifdef ASSERT
    if (method->is_continuation_enter_intrinsic()) {
      assert(interpreted_entry_offset != -1, "Must be set");
      assert(exception_offset != -1,         "Must be set");
    } else {
      assert(interpreted_entry_offset == -1, "Must be unset");
      assert(exception_offset == -1,         "Must be unset");
    }
    assert(frame_complete != -1,    "Must be set");
    assert(stack_slots != -1,       "Must be set");
    assert(vep_offset != -1,        "Must be set");
#endif

    __ flush();
    nmethod* nm = nmethod::new_native_nmethod(method,
                                              compile_id,
                                              masm->code(),
                                              vep_offset,
                                              frame_complete,
                                              stack_slots,
                                              in_ByteSize(-1),
                                              in_ByteSize(-1),
                                              oop_maps,
                                              exception_offset);
    if (nm == nullptr) return nm;
    if (method->is_continuation_enter_intrinsic()) {
      ContinuationEntry::set_enter_code(nm, interpreted_entry_offset);
    } else if (method->is_continuation_yield_intrinsic()) {
      ContinuationEntry::set_yield_code(nm);
    }
    return nm;
  }

  if (method->is_method_handle_intrinsic()) {
    vmIntrinsics::ID iid = method->intrinsic_id();
    intptr_t start = (intptr_t)__ pc();
    int vep_offset = ((intptr_t)__ pc()) - start;
    gen_special_dispatch(masm,
                         method,
                         in_sig_bt,
                         in_regs);
    int frame_complete = ((intptr_t)__ pc()) - start;  // not complete, period
    __ flush();
    int stack_slots = SharedRuntime::out_preserve_stack_slots();  // no out slots at all, actually
    return nmethod::new_native_nmethod(method,
                                       compile_id,
                                       masm->code(),
                                       vep_offset,
                                       frame_complete,
                                       stack_slots / VMRegImpl::slots_per_word,
                                       in_ByteSize(-1),
                                       in_ByteSize(-1),
                                       (OopMapSet*)nullptr);
  }

  address native_func = method->native_function();
  assert(native_func != nullptr, "must have function");

  // First, create signature for outgoing C call
  // --------------------------------------------------------------------------

  int total_in_args = method->size_of_parameters();
  // We have received a description of where all the java args are located
  // on entry to the wrapper. We need to convert these args to where
  // the jni function will expect them. To figure out where they go
  // we convert the java signature to a C signature by inserting
  // the hidden arguments as arg[0] and possibly arg[1] (static method)

  // Calculate the total number of C arguments and create arrays for the
  // signature and the outgoing registers.
  // On ppc64, we have two arrays for the outgoing registers, because
  // some floating-point arguments must be passed in registers _and_
  // in stack locations.
  bool method_is_static = method->is_static();
  int  total_c_args     = total_in_args + (method_is_static ? 2 : 1);

  BasicType *out_sig_bt = NEW_RESOURCE_ARRAY(BasicType, total_c_args);
  VMRegPair *out_regs   = NEW_RESOURCE_ARRAY(VMRegPair, total_c_args);

  // Create the signature for the C call:
  //   1) add the JNIEnv*
  //   2) add the class if the method is static
  //   3) copy the rest of the incoming signature (shifted by the number of
  //      hidden arguments).

  int argc = 0;
  out_sig_bt[argc++] = T_ADDRESS;
  if (method->is_static()) {
    out_sig_bt[argc++] = T_OBJECT;
  }

  for (int i = 0; i < total_in_args ; i++ ) {
    out_sig_bt[argc++] = in_sig_bt[i];
  }


  // Compute the wrapper's frame size.
  // --------------------------------------------------------------------------

  // Now figure out where the args must be stored and how much stack space
  // they require.
  //
  // Compute framesize for the wrapper. We need to handlize all oops in
  // incoming registers.
  //
  // Calculate the total number of stack slots we will need:
  //   1) abi requirements
  //   2) outgoing arguments
  //   3) space for inbound oop handle area
  //   4) space for handlizing a klass if static method
  //   5) space for a lock if synchronized method
  //   6) workspace for saving return values, int <-> float reg moves, etc.
  //   7) alignment
  //
  // Layout of the native wrapper frame:
  // (stack grows upwards, memory grows downwards)
  //
  // NW     [ABI_REG_ARGS]             <-- 1) R1_SP
  //        [outgoing arguments]       <-- 2) R1_SP + out_arg_slot_offset
  //        [oopHandle area]           <-- 3) R1_SP + oop_handle_offset
  //        klass                      <-- 4) R1_SP + klass_offset
  //        lock                       <-- 5) R1_SP + lock_offset
  //        [workspace]                <-- 6) R1_SP + workspace_offset
  //        [alignment] (optional)     <-- 7)
  // caller [JIT_TOP_ABI_48]           <-- r_callers_sp
  //
  // - *_slot_offset Indicates offset from SP in number of stack slots.
  // - *_offset      Indicates offset from SP in bytes.

  int stack_slots = c_calling_convention(out_sig_bt, out_regs, total_c_args) + // 1+2)
                    SharedRuntime::out_preserve_stack_slots(); // See c_calling_convention.

  // Now the space for the inbound oop handle area.
  int total_save_slots = num_java_iarg_registers * VMRegImpl::slots_per_word;

  int oop_handle_slot_offset = stack_slots;
  stack_slots += total_save_slots;                                                // 3)

  int klass_slot_offset = 0;
  int klass_offset      = -1;
  if (method_is_static) {                                                         // 4)
    klass_slot_offset  = stack_slots;
    klass_offset       = klass_slot_offset * VMRegImpl::stack_slot_size;
    stack_slots       += VMRegImpl::slots_per_word;
  }

  int lock_slot_offset = 0;
  int lock_offset      = -1;
  if (method->is_synchronized()) {                                                // 5)
    lock_slot_offset   = stack_slots;
    lock_offset        = lock_slot_offset * VMRegImpl::stack_slot_size;
    stack_slots       += VMRegImpl::slots_per_word;
  }

  int workspace_slot_offset = stack_slots;                                        // 6)
  stack_slots         += 2;

  // Now compute actual number of stack words we need.
  // Rounding to make stack properly aligned.
  stack_slots = align_up(stack_slots,                                             // 7)
                         frame::alignment_in_bytes / VMRegImpl::stack_slot_size);
  int frame_size_in_bytes = stack_slots * VMRegImpl::stack_slot_size;


  // Now we can start generating code.
  // --------------------------------------------------------------------------

  intptr_t start_pc = (intptr_t)__ pc();
  intptr_t vep_start_pc;
  intptr_t frame_done_pc;

  Label    handle_pending_exception;
  Label    last_java_pc;

  Register r_callers_sp = R21;
  Register r_temp_1     = R22;
  Register r_temp_2     = R23;
  Register r_temp_3     = R24;
  Register r_temp_4     = R25;
  Register r_temp_5     = R26;
  Register r_temp_6     = R27;
  Register r_last_java_pc = R28;

  Register r_carg1_jnienv        = noreg;
  Register r_carg2_classorobject = noreg;
  r_carg1_jnienv        = out_regs[0].first()->as_Register();
  r_carg2_classorobject = out_regs[1].first()->as_Register();


  // Generate the Unverified Entry Point (UEP).
  // --------------------------------------------------------------------------
  assert(start_pc == (intptr_t)__ pc(), "uep must be at start");

  // Check ic: object class == cached class?
  if (!method_is_static) {
    __ ic_check(4 /* end_alignment */);
  }

  // Generate the Verified Entry Point (VEP).
  // --------------------------------------------------------------------------
  vep_start_pc = (intptr_t)__ pc();

  if (VM_Version::supports_fast_class_init_checks() && method->needs_clinit_barrier()) {
    Label L_skip_barrier;
    Register klass = r_temp_1;
    // Notify OOP recorder (don't need the relocation)
    AddressLiteral md = __ constant_metadata_address(method->method_holder());
    __ load_const_optimized(klass, md.value(), R0);
    __ clinit_barrier(klass, R16_thread, &L_skip_barrier /*L_fast_path*/);

    __ load_const_optimized(klass, SharedRuntime::get_handle_wrong_method_stub(), R0);
    __ mtctr(klass);
    __ bctr();

    __ bind(L_skip_barrier);
  }

  __ save_LR(r_temp_1);
  __ generate_stack_overflow_check(frame_size_in_bytes); // Check before creating frame.
  __ mr(r_callers_sp, R1_SP);                            // Remember frame pointer.
  __ push_frame(frame_size_in_bytes, r_temp_1);          // Push the c2n adapter's frame.

  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->nmethod_entry_barrier(masm, r_temp_1);

  frame_done_pc = (intptr_t)__ pc();

  // Native nmethod wrappers never take possession of the oop arguments.
  // So the caller will gc the arguments.
  // The only thing we need an oopMap for is if the call is static.
  //
  // An OopMap for lock (and class if static), and one for the VM call itself.
  OopMapSet *oop_maps = new OopMapSet();
  OopMap    *oop_map  = new OopMap(stack_slots * 2, 0 /* arg_slots*/);

  // Move arguments from register/stack to register/stack.
  // --------------------------------------------------------------------------
  //
  // We immediately shuffle the arguments so that for any vm call we have
  // to make from here on out (sync slow path, jvmti, etc.) we will have
  // captured the oops from our caller and have a valid oopMap for them.
  //
  // Natives require 1 or 2 extra arguments over the normal ones: the JNIEnv*
  // (derived from JavaThread* which is in R16_thread) and, if static,
  // the class mirror instead of a receiver. This pretty much guarantees that
  // register layout will not match. We ignore these extra arguments during
  // the shuffle. The shuffle is described by the two calling convention
  // vectors we have in our possession. We simply walk the java vector to
  // get the source locations and the c vector to get the destinations.

  // Record sp-based slot for receiver on stack for non-static methods.
  int receiver_offset = -1;

  // We move the arguments backward because the floating point registers
  // destination will always be to a register with a greater or equal
  // register number or the stack.
  //   in  is the index of the incoming Java arguments
  //   out is the index of the outgoing C arguments

#ifdef ASSERT
  bool reg_destroyed[Register::number_of_registers];
  bool freg_destroyed[FloatRegister::number_of_registers];
  for (int r = 0 ; r < Register::number_of_registers ; r++) {
    reg_destroyed[r] = false;
  }
  for (int f = 0 ; f < FloatRegister::number_of_registers ; f++) {
    freg_destroyed[f] = false;
  }
#endif // ASSERT

  for (int in = total_in_args - 1, out = total_c_args - 1; in >= 0 ; in--, out--) {

#ifdef ASSERT
    if (in_regs[in].first()->is_Register()) {
      assert(!reg_destroyed[in_regs[in].first()->as_Register()->encoding()], "ack!");
    } else if (in_regs[in].first()->is_FloatRegister()) {
      assert(!freg_destroyed[in_regs[in].first()->as_FloatRegister()->encoding()], "ack!");
    }
    if (out_regs[out].first()->is_Register()) {
      reg_destroyed[out_regs[out].first()->as_Register()->encoding()] = true;
    } else if (out_regs[out].first()->is_FloatRegister()) {
      freg_destroyed[out_regs[out].first()->as_FloatRegister()->encoding()] = true;
    }
#endif // ASSERT

    switch (in_sig_bt[in]) {
      case T_BOOLEAN:
      case T_CHAR:
      case T_BYTE:
      case T_SHORT:
      case T_INT:
        // Move int and do sign extension.
        int_move(masm, in_regs[in], out_regs[out], r_callers_sp, r_temp_1);
        break;
      case T_LONG:
        long_move(masm, in_regs[in], out_regs[out], r_callers_sp, r_temp_1);
        break;
      case T_ARRAY:
      case T_OBJECT:
        object_move(masm, stack_slots,
                    oop_map, oop_handle_slot_offset,
                    ((in == 0) && (!method_is_static)), &receiver_offset,
                    in_regs[in], out_regs[out],
                    r_callers_sp, r_temp_1, r_temp_2);
        break;
      case T_VOID:
        break;
      case T_FLOAT:
        float_move(masm, in_regs[in], out_regs[out], r_callers_sp, r_temp_1);
        break;
      case T_DOUBLE:
        double_move(masm, in_regs[in], out_regs[out], r_callers_sp, r_temp_1);
        break;
      case T_ADDRESS:
        fatal("found type (T_ADDRESS) in java args");
        break;
      default:
        ShouldNotReachHere();
        break;
    }
  }

  // Pre-load a static method's oop into ARG2.
  // Used both by locking code and the normal JNI call code.
  if (method_is_static) {
    __ set_oop_constant(JNIHandles::make_local(method->method_holder()->java_mirror()),
                        r_carg2_classorobject);

    // Now handlize the static class mirror in carg2. It's known not-null.
    __ std(r_carg2_classorobject, klass_offset, R1_SP);
    oop_map->set_oop(VMRegImpl::stack2reg(klass_slot_offset));
    __ addi(r_carg2_classorobject, R1_SP, klass_offset);
  }

  // Get JNIEnv* which is first argument to native.
  __ addi(r_carg1_jnienv, R16_thread, in_bytes(JavaThread::jni_environment_offset()));

  // NOTE:
  //
  // We have all of the arguments setup at this point.
  // We MUST NOT touch any outgoing regs from this point on.
  // So if we must call out we must push a new frame.

  // The last java pc will also be used as resume pc if this is the wrapper for wait0.
  // For this purpose the precise location matters but not for oopmap lookup.
  __ calculate_address_from_global_toc(r_last_java_pc, last_java_pc, true, true, true, true);

  // Make sure that thread is non-volatile; it crosses a bunch of VM calls below.
  assert(R16_thread->is_nonvolatile(), "thread must be in non-volatile register");

# if 0
  // DTrace method entry
# endif

  // Lock a synchronized method.
  // --------------------------------------------------------------------------

  if (method->is_synchronized()) {
    Register          r_oop  = r_temp_4;
    const Register    r_box  = r_temp_5;
    Label             done, locked;

    // Load the oop for the object or class. r_carg2_classorobject contains
    // either the handlized oop from the incoming arguments or the handlized
    // class mirror (if the method is static).
    __ ld(r_oop, 0, r_carg2_classorobject);

    // Get the lock box slot's address.
    __ addi(r_box, R1_SP, lock_offset);

    // Try fastpath for locking.
    if (LockingMode == LM_LIGHTWEIGHT) {
      // fast_lock kills r_temp_1, r_temp_2, r_temp_3.
      Register r_temp_3_or_noreg = UseObjectMonitorTable ? r_temp_3 : noreg;
      __ compiler_fast_lock_lightweight_object(CR0, r_oop, r_box, r_temp_1, r_temp_2, r_temp_3_or_noreg);
    } else {
      // fast_lock kills r_temp_1, r_temp_2, r_temp_3.
      __ compiler_fast_lock_object(CR0, r_oop, r_box, r_temp_1, r_temp_2, r_temp_3);
    }
    __ beq(CR0, locked);

    // None of the above fast optimizations worked so we have to get into the
    // slow case of monitor enter. Inline a special case of call_VM that
    // disallows any pending_exception.

    // Save argument registers and leave room for C-compatible ABI_REG_ARGS.
    int frame_size = frame::native_abi_reg_args_size + align_up(total_c_args * wordSize, frame::alignment_in_bytes);
    __ mr(R11_scratch1, R1_SP);
    RegisterSaver::push_frame_and_save_argument_registers(masm, R12_scratch2, frame_size, total_c_args, out_regs);

    // Do the call.
    __ set_last_Java_frame(R11_scratch1, r_last_java_pc);
    assert(r_last_java_pc->is_nonvolatile(), "r_last_java_pc needs to be preserved accross complete_monitor_locking_C call");
    // The following call will not be preempted.
    // push_cont_fastpath forces freeze slow path in case we try to preempt where we will pin the
    // vthread to the carrier (see FreezeBase::recurse_freeze_native_frame()).
    __ push_cont_fastpath();
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::complete_monitor_locking_C), r_oop, r_box, R16_thread);
    __ pop_cont_fastpath();
    __ reset_last_Java_frame();

    RegisterSaver::restore_argument_registers_and_pop_frame(masm, frame_size, total_c_args, out_regs);

    __ asm_assert_mem8_is_zero(thread_(pending_exception),
       "no pending exception allowed on exit from SharedRuntime::complete_monitor_locking_C");

    __ bind(locked);
  }

  __ set_last_Java_frame(R1_SP, r_last_java_pc);

  // Publish thread state
  // --------------------------------------------------------------------------

  // Transition from _thread_in_Java to _thread_in_native.
  __ li(R0, _thread_in_native);
  __ release();
  // TODO: PPC port assert(4 == JavaThread::sz_thread_state(), "unexpected field size");
  __ stw(R0, thread_(thread_state));


  // The JNI call
  // --------------------------------------------------------------------------
  __ call_c(native_func, relocInfo::runtime_call_type);


  // Now, we are back from the native code.


  // Unpack the native result.
  // --------------------------------------------------------------------------

  // For int-types, we do any needed sign-extension required.
  // Care must be taken that the return values (R3_RET and F1_RET)
  // will survive any VM calls for blocking or unlocking.
  // An OOP result (handle) is done specially in the slow-path code.

  switch (ret_type) {
    case T_VOID:    break;        // Nothing to do!
    case T_FLOAT:   break;        // Got it where we want it (unless slow-path).
    case T_DOUBLE:  break;        // Got it where we want it (unless slow-path).
    case T_LONG:    break;        // Got it where we want it (unless slow-path).
    case T_OBJECT:  break;        // Really a handle.
                                  // Cannot de-handlize until after reclaiming jvm_lock.
    case T_ARRAY:   break;

    case T_BOOLEAN: {             // 0 -> false(0); !0 -> true(1)
      __ normalize_bool(R3_RET);
      break;
      }
    case T_BYTE: {                // sign extension
      __ extsb(R3_RET, R3_RET);
      break;
      }
    case T_CHAR: {                // unsigned result
      __ andi(R3_RET, R3_RET, 0xffff);
      break;
      }
    case T_SHORT: {               // sign extension
      __ extsh(R3_RET, R3_RET);
      break;
      }
    case T_INT:                   // nothing to do
      break;
    default:
      ShouldNotReachHere();
      break;
  }

  // Publish thread state
  // --------------------------------------------------------------------------

  // Switch thread to "native transition" state before reading the
  // synchronization state. This additional state is necessary because reading
  // and testing the synchronization state is not atomic w.r.t. GC, as this
  // scenario demonstrates:
  //   - Java thread A, in _thread_in_native state, loads _not_synchronized
  //     and is preempted.
  //   - VM thread changes sync state to synchronizing and suspends threads
  //     for GC.
  //   - Thread A is resumed to finish this native method, but doesn't block
  //     here since it didn't see any synchronization in progress, and escapes.

  // Transition from _thread_in_native to _thread_in_native_trans.
  __ li(R0, _thread_in_native_trans);
  __ release();
  // TODO: PPC port assert(4 == JavaThread::sz_thread_state(), "unexpected field size");
  __ stw(R0, thread_(thread_state));


  // Must we block?
  // --------------------------------------------------------------------------

  // Block, if necessary, before resuming in _thread_in_Java state.
  // In order for GC to work, don't clear the last_Java_sp until after blocking.
  {
    Label no_block, sync;

    // Force this write out before the read below.
    if (!UseSystemMemoryBarrier) {
      __ fence();
    }

    Register sync_state_addr = r_temp_4;
    Register sync_state      = r_temp_5;
    Register suspend_flags   = r_temp_6;

    // No synchronization in progress nor yet synchronized
    // (cmp-br-isync on one path, release (same as acquire on PPC64) on the other path).
    __ safepoint_poll(sync, sync_state, true /* at_return */, false /* in_nmethod */);

    // Not suspended.
    // TODO: PPC port assert(4 == Thread::sz_suspend_flags(), "unexpected field size");
    __ lwz(suspend_flags, thread_(suspend_flags));
    __ cmpwi(CR1, suspend_flags, 0);
    __ beq(CR1, no_block);

    // Block. Save any potential method result value before the operation and
    // use a leaf call to leave the last_Java_frame setup undisturbed. Doing this
    // lets us share the oopMap we used when we went native rather than create
    // a distinct one for this pc.
    __ bind(sync);
    __ isync();

    address entry_point =
      CAST_FROM_FN_PTR(address, JavaThread::check_special_condition_for_native_trans);
    save_native_result(masm, ret_type, workspace_slot_offset);
    __ call_VM_leaf(entry_point, R16_thread);
    restore_native_result(masm, ret_type, workspace_slot_offset);

    __ bind(no_block);

    // Publish thread state.
    // --------------------------------------------------------------------------

    // Thread state is thread_in_native_trans. Any safepoint blocking has
    // already happened so we can now change state to _thread_in_Java.

    // Transition from _thread_in_native_trans to _thread_in_Java.
    __ li(R0, _thread_in_Java);
    __ lwsync(); // Acquire safepoint and suspend state, release thread state.
    // TODO: PPC port assert(4 == JavaThread::sz_thread_state(), "unexpected field size");
    __ stw(R0, thread_(thread_state));

    // Check preemption for Object.wait()
    if (LockingMode != LM_LEGACY && method->is_object_wait0()) {
      Label not_preempted;
      __ ld(R0, in_bytes(JavaThread::preempt_alternate_return_offset()), R16_thread);
      __ cmpdi(CR0, R0, 0);
      __ beq(CR0, not_preempted);
      __ mtlr(R0);
      __ li(R0, 0);
      __ std(R0, in_bytes(JavaThread::preempt_alternate_return_offset()), R16_thread);
      __ blr();
      __ bind(not_preempted);
    }
    __ bind(last_java_pc);
    // We use the same pc/oopMap repeatedly when we call out above.
    intptr_t oopmap_pc = (intptr_t) __ pc();
    oop_maps->add_gc_map(oopmap_pc - start_pc, oop_map);
  }

  // Reguard any pages if necessary.
  // --------------------------------------------------------------------------

  Label no_reguard;
  __ lwz(r_temp_1, thread_(stack_guard_state));
  __ cmpwi(CR0, r_temp_1, StackOverflow::stack_guard_yellow_reserved_disabled);
  __ bne(CR0, no_reguard);

  save_native_result(masm, ret_type, workspace_slot_offset);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::reguard_yellow_pages));
  restore_native_result(masm, ret_type, workspace_slot_offset);

  __ bind(no_reguard);


  // Unlock
  // --------------------------------------------------------------------------

  if (method->is_synchronized()) {
    const Register r_oop       = r_temp_4;
    const Register r_box       = r_temp_5;
    const Register r_exception = r_temp_6;
    Label done;

    // Get oop and address of lock object box.
    if (method_is_static) {
      assert(klass_offset != -1, "");
      __ ld(r_oop, klass_offset, R1_SP);
    } else {
      assert(receiver_offset != -1, "");
      __ ld(r_oop, receiver_offset, R1_SP);
    }
    __ addi(r_box, R1_SP, lock_offset);

    // Try fastpath for unlocking.
    if (LockingMode == LM_LIGHTWEIGHT) {
      __ compiler_fast_unlock_lightweight_object(CR0, r_oop, r_box, r_temp_1, r_temp_2, r_temp_3);
    } else {
      __ compiler_fast_unlock_object(CR0, r_oop, r_box, r_temp_1, r_temp_2, r_temp_3);
    }
    __ beq(CR0, done);

    // Save and restore any potential method result value around the unlocking operation.
    save_native_result(masm, ret_type, workspace_slot_offset);

    // Must save pending exception around the slow-path VM call. Since it's a
    // leaf call, the pending exception (if any) can be kept in a register.
    __ ld(r_exception, thread_(pending_exception));
    assert(r_exception->is_nonvolatile(), "exception register must be non-volatile");
    __ li(R0, 0);
    __ std(R0, thread_(pending_exception));

    // Slow case of monitor enter.
    // Inline a special case of call_VM that disallows any pending_exception.
    // Arguments are (oop obj, BasicLock* lock, JavaThread* thread).
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::complete_monitor_unlocking_C), r_oop, r_box, R16_thread);

    __ asm_assert_mem8_is_zero(thread_(pending_exception),
       "no pending exception allowed on exit from SharedRuntime::complete_monitor_unlocking_C");

    restore_native_result(masm, ret_type, workspace_slot_offset);

    // Check_forward_pending_exception jump to forward_exception if any pending
    // exception is set. The forward_exception routine expects to see the
    // exception in pending_exception and not in a register. Kind of clumsy,
    // since all folks who branch to forward_exception must have tested
    // pending_exception first and hence have it in a register already.
    __ std(r_exception, thread_(pending_exception));

    __ bind(done);
  }

# if 0
  // DTrace method exit
# endif

  // Clear "last Java frame" SP and PC.
  // --------------------------------------------------------------------------

  // Last java frame won't be set if we're resuming after preemption
  bool maybe_preempted = LockingMode != LM_LEGACY && method->is_object_wait0();
  __ reset_last_Java_frame(!maybe_preempted /* check_last_java_sp */);

  // Unbox oop result, e.g. JNIHandles::resolve value.
  // --------------------------------------------------------------------------

  if (is_reference_type(ret_type)) {
    __ resolve_jobject(R3_RET, r_temp_1, r_temp_2, MacroAssembler::PRESERVATION_NONE);
  }

  if (CheckJNICalls) {
    // clear_pending_jni_exception_check
    __ load_const_optimized(R0, 0L);
    __ st_ptr(R0, JavaThread::pending_jni_exception_check_fn_offset(), R16_thread);
  }

  // Reset handle block.
  // --------------------------------------------------------------------------
  __ ld(r_temp_1, thread_(active_handles));
  // TODO: PPC port assert(4 == JNIHandleBlock::top_size_in_bytes(), "unexpected field size");
  __ li(r_temp_2, 0);
  __ stw(r_temp_2, in_bytes(JNIHandleBlock::top_offset()), r_temp_1);

  // Prepare for return
  // --------------------------------------------------------------------------
  __ pop_frame();
  __ restore_LR(R11);

#if INCLUDE_JFR
  // We need to do a poll test after unwind in case the sampler
  // managed to sample the native frame after returning to Java.
  Label L_stub;
  int safepoint_offset = __ offset();
  if (!UseSIGTRAP) {
    __ relocate(relocInfo::poll_return_type);
  }
  __ safepoint_poll(L_stub, r_temp_2, true /* at_return */, true /* in_nmethod: frame already popped */);
#endif // INCLUDE_JFR

  // Check for pending exceptions.
  // --------------------------------------------------------------------------
  __ ld(r_temp_2, thread_(pending_exception));
  __ cmpdi(CR0, r_temp_2, 0);
  __ bne(CR0, handle_pending_exception);

  // Return.
  __ blr();

  // Handler for return safepoint (out-of-line).
#if INCLUDE_JFR
  if (!UseSIGTRAP) {
    __ bind(L_stub);
    __ jump_to_polling_page_return_handler_blob(safepoint_offset);
  }
#endif // INCLUDE_JFR

  // Handler for pending exceptions (out-of-line).
  // --------------------------------------------------------------------------
  // Since this is a native call, we know the proper exception handler
  // is the empty function. We just pop this frame and then jump to
  // forward_exception_entry.
  __ bind(handle_pending_exception);
  __ b64_patchable((address)StubRoutines::forward_exception_entry(),
                       relocInfo::runtime_call_type);

  // Done.
  // --------------------------------------------------------------------------

  __ flush();

  nmethod *nm = nmethod::new_native_nmethod(method,
                                            compile_id,
                                            masm->code(),
                                            vep_start_pc-start_pc,
                                            frame_done_pc-start_pc,
                                            stack_slots / VMRegImpl::slots_per_word,
                                            (method_is_static ? in_ByteSize(klass_offset) : in_ByteSize(receiver_offset)),
                                            in_ByteSize(lock_offset),
                                            oop_maps);

  return nm;
}

// This function returns the adjust size (in number of words) to a c2i adapter
// activation for use during deoptimization.
int Deoptimization::last_frame_adjust(int callee_parameters, int callee_locals) {
  return align_up((callee_locals - callee_parameters) * Interpreter::stackElementWords, frame::frame_alignment_in_words);
}

uint SharedRuntime::in_preserve_stack_slots() {
  return frame::jit_in_preserve_size / VMRegImpl::stack_slot_size;
}

uint SharedRuntime::out_preserve_stack_slots() {
#if defined(COMPILER1) || defined(COMPILER2)
  return frame::jit_out_preserve_size / VMRegImpl::stack_slot_size;
#else
  return 0;
#endif
}

VMReg SharedRuntime::thread_register() {
  // On PPC virtual threads don't save the JavaThread* in their context (e.g. C1 stub frames).
  ShouldNotCallThis();
  return nullptr;
}

#if defined(COMPILER1) || defined(COMPILER2)
// Frame generation for deopt and uncommon trap blobs.
static void push_skeleton_frame(MacroAssembler* masm, bool deopt,
                                /* Read */
                                Register unroll_block_reg,
                                /* Update */
                                Register frame_sizes_reg,
                                Register number_of_frames_reg,
                                Register pcs_reg,
                                /* Invalidate */
                                Register frame_size_reg,
                                Register pc_reg) {

  __ ld(pc_reg, 0, pcs_reg);
  __ ld(frame_size_reg, 0, frame_sizes_reg);
  __ std(pc_reg, _abi0(lr), R1_SP);
  __ push_frame(frame_size_reg, R0/*tmp*/);
  __ std(R1_SP, _ijava_state_neg(sender_sp), R1_SP);
  __ addi(number_of_frames_reg, number_of_frames_reg, -1);
  __ addi(frame_sizes_reg, frame_sizes_reg, wordSize);
  __ addi(pcs_reg, pcs_reg, wordSize);
}

// Loop through the UnrollBlock info and create new frames.
static void push_skeleton_frames(MacroAssembler* masm, bool deopt,
                                 /* read */
                                 Register unroll_block_reg,
                                 /* invalidate */
                                 Register frame_sizes_reg,
                                 Register number_of_frames_reg,
                                 Register pcs_reg,
                                 Register frame_size_reg,
                                 Register pc_reg) {
  Label loop;

 // _number_of_frames is of type int (deoptimization.hpp)
  __ lwa(number_of_frames_reg,
             in_bytes(Deoptimization::UnrollBlock::number_of_frames_offset()),
             unroll_block_reg);
  __ ld(pcs_reg,
            in_bytes(Deoptimization::UnrollBlock::frame_pcs_offset()),
            unroll_block_reg);
  __ ld(frame_sizes_reg,
            in_bytes(Deoptimization::UnrollBlock::frame_sizes_offset()),
            unroll_block_reg);

  // stack: (caller_of_deoptee, ...).

  // At this point we either have an interpreter frame or a compiled
  // frame on top of stack. If it is a compiled frame we push a new c2i
  // adapter here

  // Memorize top-frame stack-pointer.
  __ mr(frame_size_reg/*old_sp*/, R1_SP);

  // Resize interpreter top frame OR C2I adapter.

  // At this moment, the top frame (which is the caller of the deoptee) is
  // an interpreter frame or a newly pushed C2I adapter or an entry frame.
  // The top frame has a TOP_IJAVA_FRAME_ABI and the frame contains the
  // outgoing arguments.
  //
  // In order to push the interpreter frame for the deoptee, we need to
  // resize the top frame such that we are able to place the deoptee's
  // locals in the frame.
  // Additionally, we have to turn the top frame's TOP_IJAVA_FRAME_ABI
  // into a valid PARENT_IJAVA_FRAME_ABI.

  __ lwa(R11_scratch1,
             in_bytes(Deoptimization::UnrollBlock::caller_adjustment_offset()),
             unroll_block_reg);
  __ neg(R11_scratch1, R11_scratch1);

  // R11_scratch1 contains size of locals for frame resizing.
  // R12_scratch2 contains top frame's lr.

  // Resize frame by complete frame size prevents TOC from being
  // overwritten by locals. A more stack space saving way would be
  // to copy the TOC to its location in the new abi.
  __ addi(R11_scratch1, R11_scratch1, - frame::parent_ijava_frame_abi_size);

  // now, resize the frame
  __ resize_frame(R11_scratch1, pc_reg/*tmp*/);

  // In the case where we have resized a c2i frame above, the optional
  // alignment below the locals has size 32 (why?).
  __ std(R12_scratch2, _abi0(lr), R1_SP);

  // Initialize initial_caller_sp.
 __ std(frame_size_reg, _ijava_state_neg(sender_sp), R1_SP);

#ifdef ASSERT
  // Make sure that there is at least one entry in the array.
  __ cmpdi(CR0, number_of_frames_reg, 0);
  __ asm_assert_ne("array_size must be > 0");
#endif

  // Now push the new interpreter frames.
  //
  __ bind(loop);
  // Allocate a new frame, fill in the pc.
  push_skeleton_frame(masm, deopt,
                      unroll_block_reg,
                      frame_sizes_reg,
                      number_of_frames_reg,
                      pcs_reg,
                      frame_size_reg,
                      pc_reg);
  __ cmpdi(CR0, number_of_frames_reg, 0);
  __ bne(CR0, loop);

  // Get the return address pointing into the template interpreter.
  __ ld(R0, 0, pcs_reg);
  // Store it in the top interpreter frame.
  __ std(R0, _abi0(lr), R1_SP);
  // Initialize frame_manager_lr of interpreter top frame.
}
#endif

void SharedRuntime::generate_deopt_blob() {
  // Allocate space for the code
  ResourceMark rm;
  // Setup code generation tools
  const char* name = SharedRuntime::stub_name(StubId::shared_deopt_id);
  CodeBuffer buffer(name, 2048, 1024);
  InterpreterMacroAssembler* masm = new InterpreterMacroAssembler(&buffer);
  Label exec_mode_initialized;
  int frame_size_in_words;
  OopMap* map = nullptr;
  OopMapSet *oop_maps = new OopMapSet();

  // size of ABI112 plus spill slots for R3_RET and F1_RET.
  const int frame_size_in_bytes = frame::native_abi_reg_args_spill_size;
  const int frame_size_in_slots = frame_size_in_bytes / sizeof(jint);
  int first_frame_size_in_bytes = 0; // frame size of "unpack frame" for call to fetch_unroll_info.

  const Register exec_mode_reg = R21_tmp1;

  const address start = __ pc();

#if defined(COMPILER1) || defined(COMPILER2)
  // --------------------------------------------------------------------------
  // Prolog for non exception case!

  // We have been called from the deopt handler of the deoptee.
  //
  // deoptee:
  //                      ...
  //                      call X
  //                      ...
  //  deopt_handler:      call_deopt_stub
  //  cur. return pc  --> ...
  //
  // So currently SR_LR points behind the call in the deopt handler.
  // We adjust it such that it points to the start of the deopt handler.
  // The return_pc has been stored in the frame of the deoptee and
  // will replace the address of the deopt_handler in the call
  // to Deoptimization::fetch_unroll_info below.
  // We can't grab a free register here, because all registers may
  // contain live values, so let the RegisterSaver do the adjustment
  // of the return pc.
  const int return_pc_adjustment_no_exception = -MacroAssembler::bl64_patchable_size;

  // Push the "unpack frame"
  // Save everything in sight.
  map = RegisterSaver::push_frame_reg_args_and_save_live_registers(masm,
                                                                   &first_frame_size_in_bytes,
                                                                   /*generate_oop_map=*/ true,
                                                                   return_pc_adjustment_no_exception,
                                                                   RegisterSaver::return_pc_is_lr);
  assert(map != nullptr, "OopMap must have been created");

  __ li(exec_mode_reg, Deoptimization::Unpack_deopt);
  // Save exec mode for unpack_frames.
  __ b(exec_mode_initialized);

  // --------------------------------------------------------------------------
  // Prolog for exception case

  // An exception is pending.
  // We have been called with a return (interpreter) or a jump (exception blob).
  //
  // - R3_ARG1: exception oop
  // - R4_ARG2: exception pc

  int exception_offset = __ pc() - start;

  BLOCK_COMMENT("Prolog for exception case");

  // Store exception oop and pc in thread (location known to GC).
  // This is needed since the call to "fetch_unroll_info()" may safepoint.
  __ std(R3_ARG1, in_bytes(JavaThread::exception_oop_offset()), R16_thread);
  __ std(R4_ARG2, in_bytes(JavaThread::exception_pc_offset()),  R16_thread);
  __ std(R4_ARG2, _abi0(lr), R1_SP);

  // Vanilla deoptimization with an exception pending in exception_oop.
  int exception_in_tls_offset = __ pc() - start;

  // Push the "unpack frame".
  // Save everything in sight.
  RegisterSaver::push_frame_reg_args_and_save_live_registers(masm,
                                                             &first_frame_size_in_bytes,
                                                             /*generate_oop_map=*/ false,
                                                             /*return_pc_adjustment_exception=*/ 0,
                                                             RegisterSaver::return_pc_is_pre_saved);

  // Deopt during an exception. Save exec mode for unpack_frames.
  __ li(exec_mode_reg, Deoptimization::Unpack_exception);

  // fall through

  int reexecute_offset = 0;
#ifdef COMPILER1
  __ b(exec_mode_initialized);

  // Reexecute entry, similar to c2 uncommon trap
  reexecute_offset = __ pc() - start;

  RegisterSaver::push_frame_reg_args_and_save_live_registers(masm,
                                                             &first_frame_size_in_bytes,
                                                             /*generate_oop_map=*/ false,
                                                             /*return_pc_adjustment_reexecute=*/ 0,
                                                             RegisterSaver::return_pc_is_pre_saved);
  __ li(exec_mode_reg, Deoptimization::Unpack_reexecute);
#endif

  // --------------------------------------------------------------------------
  __ BIND(exec_mode_initialized);

  const Register unroll_block_reg = R22_tmp2;

  // We need to set `last_Java_frame' because `fetch_unroll_info' will
  // call `last_Java_frame()'. The value of the pc in the frame is not
  // particularly important. It just needs to identify this blob.
  __ set_last_Java_frame(R1_SP, noreg);

  // With EscapeAnalysis turned on, this call may safepoint!
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, Deoptimization::fetch_unroll_info), R16_thread, exec_mode_reg);
  address calls_return_pc = __ last_calls_return_pc();
  // Set an oopmap for the call site that describes all our saved registers.
  oop_maps->add_gc_map(calls_return_pc - start, map);

  __ reset_last_Java_frame();
  // Save the return value.
  __ mr(unroll_block_reg, R3_RET);

  // Restore only the result registers that have been saved
  // by save_volatile_registers(...).
  RegisterSaver::restore_result_registers(masm, first_frame_size_in_bytes);

  // reload the exec mode from the UnrollBlock (it might have changed)
  __ lwz(exec_mode_reg, in_bytes(Deoptimization::UnrollBlock::unpack_kind_offset()), unroll_block_reg);
  // In excp_deopt_mode, restore and clear exception oop which we
  // stored in the thread during exception entry above. The exception
  // oop will be the return value of this stub.
  Label skip_restore_excp;
  __ cmpdi(CR0, exec_mode_reg, Deoptimization::Unpack_exception);
  __ bne(CR0, skip_restore_excp);
  __ ld(R3_RET, in_bytes(JavaThread::exception_oop_offset()), R16_thread);
  __ ld(R4_ARG2, in_bytes(JavaThread::exception_pc_offset()), R16_thread);
  __ li(R0, 0);
  __ std(R0, in_bytes(JavaThread::exception_pc_offset()),  R16_thread);
  __ std(R0, in_bytes(JavaThread::exception_oop_offset()), R16_thread);
  __ BIND(skip_restore_excp);

  __ pop_frame();

  // stack: (deoptee, optional i2c, caller of deoptee, ...).

  // pop the deoptee's frame
  __ pop_frame();

  // stack: (caller_of_deoptee, ...).

  // Freezing continuation frames requires that the caller is trimmed to unextended sp if compiled.
  // If not compiled the loaded value is equal to the current SP (see frame::initial_deoptimization_info())
  // and the frame is effectively not resized.
  Register caller_sp = R23_tmp3;
  __ ld_ptr(caller_sp, Deoptimization::UnrollBlock::initial_info_offset(), unroll_block_reg);
  __ resize_frame_absolute(caller_sp, R24_tmp4, R25_tmp5);

  // Loop through the `UnrollBlock' info and create interpreter frames.
  push_skeleton_frames(masm, true/*deopt*/,
                       unroll_block_reg,
                       R23_tmp3,
                       R24_tmp4,
                       R25_tmp5,
                       R26_tmp6,
                       R27_tmp7);

  // stack: (skeletal interpreter frame, ..., optional skeletal
  // interpreter frame, optional c2i, caller of deoptee, ...).

  // push an `unpack_frame' taking care of float / int return values.
  __ push_frame(frame_size_in_bytes, R0/*tmp*/);

  // stack: (unpack frame, skeletal interpreter frame, ..., optional
  // skeletal interpreter frame, optional c2i, caller of deoptee,
  // ...).

  // Spill live volatile registers since we'll do a call.
  __ std( R3_RET, _native_abi_reg_args_spill(spill_ret),  R1_SP);
  __ stfd(F1_RET, _native_abi_reg_args_spill(spill_fret), R1_SP);

  // Let the unpacker layout information in the skeletal frames just
  // allocated.
  __ calculate_address_from_global_toc(R3_RET, calls_return_pc, true, true, true, true);
  __ set_last_Java_frame(/*sp*/R1_SP, /*pc*/R3_RET);
  // This is a call to a LEAF method, so no oop map is required.
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, Deoptimization::unpack_frames),
                  R16_thread/*thread*/, exec_mode_reg/*exec_mode*/);
  __ reset_last_Java_frame();

  // Restore the volatiles saved above.
  __ ld( R3_RET, _native_abi_reg_args_spill(spill_ret),  R1_SP);
  __ lfd(F1_RET, _native_abi_reg_args_spill(spill_fret), R1_SP);

  // Pop the unpack frame.
  __ pop_frame();
  __ restore_LR(R0);

  // stack: (top interpreter frame, ..., optional interpreter frame,
  // optional c2i, caller of deoptee, ...).

  // Initialize R14_state.
  __ restore_interpreter_state(R11_scratch1);
  __ load_const_optimized(R25_templateTableBase, (address)Interpreter::dispatch_table((TosState)0), R11_scratch1);

  // Return to the interpreter entry point.
  __ blr();
  __ flush();
#else // COMPILER2
  __ unimplemented("deopt blob needed only with compiler");
  int exception_offset = __ pc() - start;
#endif // COMPILER2

  _deopt_blob = DeoptimizationBlob::create(&buffer, oop_maps, 0, exception_offset,
                                           reexecute_offset, first_frame_size_in_bytes / wordSize);
  _deopt_blob->set_unpack_with_exception_in_tls_offset(exception_in_tls_offset);
}

#ifdef COMPILER2
UncommonTrapBlob* OptoRuntime::generate_uncommon_trap_blob() {
  // Allocate space for the code.
  ResourceMark rm;
  // Setup code generation tools.
  const char* name = OptoRuntime::stub_name(StubId::c2_uncommon_trap_id);
  CodeBuffer buffer(name, 2048, 1024);
  if (buffer.blob() == nullptr) {
    return nullptr;
  }
  InterpreterMacroAssembler* masm = new InterpreterMacroAssembler(&buffer);
  address start = __ pc();

  Register unroll_block_reg = R21_tmp1;
  Register klass_index_reg  = R22_tmp2;
  Register unc_trap_reg     = R23_tmp3;
  Register r_return_pc      = R27_tmp7;

  OopMapSet* oop_maps = new OopMapSet();
  int frame_size_in_bytes = frame::native_abi_reg_args_size;
  OopMap* map = new OopMap(frame_size_in_bytes / sizeof(jint), 0);

  // stack: (deoptee, optional i2c, caller_of_deoptee, ...).

  // Push a dummy `unpack_frame' and call
  // `Deoptimization::uncommon_trap' to pack the compiled frame into a
  // vframe array and return the `UnrollBlock' information.

  // Save LR to compiled frame.
  __ save_LR(R11_scratch1);

  // Push an "uncommon_trap" frame.
  __ push_frame_reg_args(0, R11_scratch1);

  // stack: (unpack frame, deoptee, optional i2c, caller_of_deoptee, ...).

  // Set the `unpack_frame' as last_Java_frame.
  // `Deoptimization::uncommon_trap' expects it and considers its
  // sender frame as the deoptee frame.
  // Remember the offset of the instruction whose address will be
  // moved to R11_scratch1.
  address gc_map_pc = __ pc();
  __ calculate_address_from_global_toc(r_return_pc, gc_map_pc, true, true, true, true);
  __ set_last_Java_frame(/*sp*/R1_SP, r_return_pc);

  __ mr(klass_index_reg, R3);
  __ li(R5_ARG3, Deoptimization::Unpack_uncommon_trap);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, Deoptimization::uncommon_trap),
                  R16_thread, klass_index_reg, R5_ARG3);

  // Set an oopmap for the call site.
  oop_maps->add_gc_map(gc_map_pc - start, map);

  __ reset_last_Java_frame();

  // Pop the `unpack frame'.
  __ pop_frame();

  // stack: (deoptee, optional i2c, caller_of_deoptee, ...).

  // Save the return value.
  __ mr(unroll_block_reg, R3_RET);

  // Pop the uncommon_trap frame.
  __ pop_frame();

  // stack: (caller_of_deoptee, ...).

#ifdef ASSERT
  __ lwz(R22_tmp2, in_bytes(Deoptimization::UnrollBlock::unpack_kind_offset()), unroll_block_reg);
  __ cmpdi(CR0, R22_tmp2, (unsigned)Deoptimization::Unpack_uncommon_trap);
  __ asm_assert_eq("OptoRuntime::generate_uncommon_trap_blob: expected Unpack_uncommon_trap");
#endif

  // Freezing continuation frames requires that the caller is trimmed to unextended sp if compiled.
  // If not compiled the loaded value is equal to the current SP (see frame::initial_deoptimization_info())
  // and the frame is effectively not resized.
  Register caller_sp = R23_tmp3;
  __ ld_ptr(caller_sp, Deoptimization::UnrollBlock::initial_info_offset(), unroll_block_reg);
  __ resize_frame_absolute(caller_sp, R24_tmp4, R25_tmp5);

  // Allocate new interpreter frame(s) and possibly a c2i adapter
  // frame.
  push_skeleton_frames(masm, false/*deopt*/,
                       unroll_block_reg,
                       R22_tmp2,
                       R23_tmp3,
                       R24_tmp4,
                       R25_tmp5,
                       R26_tmp6);

  // stack: (skeletal interpreter frame, ..., optional skeletal
  // interpreter frame, optional c2i, caller of deoptee, ...).

  // Push a dummy `unpack_frame' taking care of float return values.
  // Call `Deoptimization::unpack_frames' to layout information in the
  // interpreter frames just created.

  // Push a simple "unpack frame" here.
  __ push_frame_reg_args(0, R11_scratch1);

  // stack: (unpack frame, skeletal interpreter frame, ..., optional
  // skeletal interpreter frame, optional c2i, caller of deoptee,
  // ...).

  // Set the "unpack_frame" as last_Java_frame.
  __ set_last_Java_frame(/*sp*/R1_SP, r_return_pc);

  // Indicate it is the uncommon trap case.
  __ li(unc_trap_reg, Deoptimization::Unpack_uncommon_trap);
  // Let the unpacker layout information in the skeletal frames just
  // allocated.
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, Deoptimization::unpack_frames),
                  R16_thread, unc_trap_reg);

  __ reset_last_Java_frame();
  // Pop the `unpack frame'.
  __ pop_frame();
  // Restore LR from top interpreter frame.
  __ restore_LR(R11_scratch1);

  // stack: (top interpreter frame, ..., optional interpreter frame,
  // optional c2i, caller of deoptee, ...).

  __ restore_interpreter_state(R11_scratch1);
  __ load_const_optimized(R25_templateTableBase, (address)Interpreter::dispatch_table((TosState)0), R11_scratch1);

  // Return to the interpreter entry point.
  __ blr();

  masm->flush();

  return UncommonTrapBlob::create(&buffer, oop_maps, frame_size_in_bytes/wordSize);
}
#endif // COMPILER2

// Generate a special Compile2Runtime blob that saves all registers, and setup oopmap.
SafepointBlob* SharedRuntime::generate_handler_blob(StubId id, address call_ptr) {
  assert(StubRoutines::forward_exception_entry() != nullptr,
         "must be generated before");
  assert(is_polling_page_id(id), "expected a polling page stub id");

  ResourceMark rm;
  OopMapSet *oop_maps = new OopMapSet();
  OopMap* map;

  // Allocate space for the code. Setup code generation tools.
  const char* name = SharedRuntime::stub_name(id);
  CodeBuffer buffer(name, 2048, 1024);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  address start = __ pc();
  int frame_size_in_bytes = 0;

  RegisterSaver::ReturnPCLocation return_pc_location;
  bool cause_return = (id == StubId::shared_polling_page_return_handler_id);
  if (cause_return) {
    // Nothing to do here. The frame has already been popped in MachEpilogNode.
    // Register LR already contains the return pc.
    return_pc_location = RegisterSaver::return_pc_is_pre_saved;
  } else {
    // Use thread()->saved_exception_pc() as return pc.
    return_pc_location = RegisterSaver::return_pc_is_thread_saved_exception_pc;
  }

  bool save_vectors = (id == StubId::shared_polling_page_vectors_safepoint_handler_id);

  // Save registers, fpu state, and flags. Set R31 = return pc.
  map = RegisterSaver::push_frame_reg_args_and_save_live_registers(masm,
                                                                   &frame_size_in_bytes,
                                                                   /*generate_oop_map=*/ true,
                                                                   /*return_pc_adjustment=*/0,
                                                                   return_pc_location, save_vectors);

  // The following is basically a call_VM. However, we need the precise
  // address of the call in order to generate an oopmap. Hence, we do all the
  // work ourselves.
  __ set_last_Java_frame(/*sp=*/R1_SP, /*pc=*/noreg);

  // The return address must always be correct so that the frame constructor
  // never sees an invalid pc.

  // Do the call
  __ call_VM_leaf(call_ptr, R16_thread);
  address calls_return_pc = __ last_calls_return_pc();

  // Set an oopmap for the call site. This oopmap will map all
  // oop-registers and debug-info registers as callee-saved. This
  // will allow deoptimization at this safepoint to find all possible
  // debug-info recordings, as well as let GC find all oops.
  oop_maps->add_gc_map(calls_return_pc - start, map);

  Label noException;

  // Clear the last Java frame.
  __ reset_last_Java_frame();

  BLOCK_COMMENT("  Check pending exception.");
  const Register pending_exception = R0;
  __ ld(pending_exception, thread_(pending_exception));
  __ cmpdi(CR0, pending_exception, 0);
  __ beq(CR0, noException);

  // Exception pending
  RegisterSaver::restore_live_registers_and_pop_frame(masm,
                                                      frame_size_in_bytes,
                                                      /*restore_ctr=*/true, save_vectors);

  BLOCK_COMMENT("  Jump to forward_exception_entry.");
  // Jump to forward_exception_entry, with the issuing PC in LR
  // so it looks like the original nmethod called forward_exception_entry.
  __ b64_patchable(StubRoutines::forward_exception_entry(), relocInfo::runtime_call_type);

  // No exception case.
  __ BIND(noException);

  if (!cause_return) {
    Label no_adjust;
    // If our stashed return pc was modified by the runtime we avoid touching it
    __ ld(R0, frame_size_in_bytes + _abi0(lr), R1_SP);
    __ cmpd(CR0, R0, R31);
    __ bne(CR0, no_adjust);

    // Adjust return pc forward to step over the safepoint poll instruction
    __ addi(R31, R31, 4);
    __ std(R31, frame_size_in_bytes + _abi0(lr), R1_SP);

    __ bind(no_adjust);
  }

  // Normal exit, restore registers and exit.
  RegisterSaver::restore_live_registers_and_pop_frame(masm,
                                                      frame_size_in_bytes,
                                                      /*restore_ctr=*/true, save_vectors);

  __ blr();

  // Make sure all code is generated
  masm->flush();

  // Fill-out other meta info
  // CodeBlob frame size is in words.
  return SafepointBlob::create(&buffer, oop_maps, frame_size_in_bytes / wordSize);
}

// generate_resolve_blob - call resolution (static/virtual/opt-virtual/ic-miss)
//
// Generate a stub that calls into the vm to find out the proper destination
// of a java call. All the argument registers are live at this point
// but since this is generic code we don't know what they are and the caller
// must do any gc of the args.
//
RuntimeStub* SharedRuntime::generate_resolve_blob(StubId id, address destination) {
  assert(is_resolve_id(id), "expected a resolve stub id");

  // allocate space for the code
  ResourceMark rm;

  const char* name = SharedRuntime::stub_name(id);
  CodeBuffer buffer(name, 1000, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  int frame_size_in_bytes;

  OopMapSet *oop_maps = new OopMapSet();
  OopMap* map = nullptr;

  address start = __ pc();

  map = RegisterSaver::push_frame_reg_args_and_save_live_registers(masm,
                                                                   &frame_size_in_bytes,
                                                                   /*generate_oop_map*/ true,
                                                                   /*return_pc_adjustment*/ 0,
                                                                   RegisterSaver::return_pc_is_lr);

  // Use noreg as last_Java_pc, the return pc will be reconstructed
  // from the physical frame.
  __ set_last_Java_frame(/*sp*/R1_SP, noreg);

  int frame_complete = __ offset();

  // Pass R19_method as 2nd (optional) argument, used by
  // counter_overflow_stub.
  __ call_VM_leaf(destination, R16_thread, R19_method);
  address calls_return_pc = __ last_calls_return_pc();
  // Set an oopmap for the call site.
  // We need this not only for callee-saved registers, but also for volatile
  // registers that the compiler might be keeping live across a safepoint.
  // Create the oopmap for the call's return pc.
  oop_maps->add_gc_map(calls_return_pc - start, map);

  // R3_RET contains the address we are going to jump to assuming no exception got installed.

  // clear last_Java_sp
  __ reset_last_Java_frame();

  // Check for pending exceptions.
  BLOCK_COMMENT("Check for pending exceptions.");
  Label pending;
  __ ld(R11_scratch1, thread_(pending_exception));
  __ cmpdi(CR0, R11_scratch1, 0);
  __ bne(CR0, pending);

  __ mtctr(R3_RET); // Ctr will not be touched by restore_live_registers_and_pop_frame.

  RegisterSaver::restore_live_registers_and_pop_frame(masm, frame_size_in_bytes, /*restore_ctr*/ false);

  // Get the returned method.
  __ get_vm_result_metadata(R19_method);

  __ bctr();


  // Pending exception after the safepoint.
  __ BIND(pending);

  RegisterSaver::restore_live_registers_and_pop_frame(masm, frame_size_in_bytes, /*restore_ctr*/ true);

  // exception pending => remove activation and forward to exception handler

  __ li(R11_scratch1, 0);
  __ ld(R3_ARG1, thread_(pending_exception));
  __ std(R11_scratch1, in_bytes(JavaThread::vm_result_oop_offset()), R16_thread);
  __ b64_patchable(StubRoutines::forward_exception_entry(), relocInfo::runtime_call_type);

  // -------------
  // Make sure all code is generated.
  masm->flush();

  // return the blob
  // frame_size_words or bytes??
  return RuntimeStub::new_runtime_stub(name, &buffer, frame_complete, frame_size_in_bytes/wordSize,
                                       oop_maps, true);
}

// Continuation point for throwing of implicit exceptions that are
// not handled in the current activation. Fabricates an exception
// oop and initiates normal exception dispatching in this
// frame. Only callee-saved registers are preserved (through the
// normal register window / RegisterMap handling).  If the compiler
// needs all registers to be preserved between the fault point and
// the exception handler then it must assume responsibility for that
// in AbstractCompiler::continuation_for_implicit_null_exception or
// continuation_for_implicit_division_by_zero_exception. All other
// implicit exceptions (e.g., NullPointerException or
// AbstractMethodError on entry) are either at call sites or
// otherwise assume that stack unwinding will be initiated, so
// caller saved registers were assumed volatile in the compiler.
//
// Note that we generate only this stub into a RuntimeStub, because
// it needs to be properly traversed and ignored during GC, so we
// change the meaning of the "__" macro within this method.
//
// Note: the routine set_pc_not_at_call_for_caller in
// SharedRuntime.cpp requires that this code be generated into a
// RuntimeStub.
RuntimeStub* SharedRuntime::generate_throw_exception(StubId id, address runtime_entry) {
  assert(is_throw_id(id), "expected a throw stub id");

  const char* name = SharedRuntime::stub_name(id);

  ResourceMark rm;
  const char* timer_msg = "SharedRuntime generate_throw_exception";
  TraceTime timer(timer_msg, TRACETIME_LOG(Info, startuptime));

  CodeBuffer code(name, 1024 DEBUG_ONLY(+ 512), 0);
  MacroAssembler* masm = new MacroAssembler(&code);

  OopMapSet* oop_maps  = new OopMapSet();
  int frame_size_in_bytes = frame::native_abi_reg_args_size;
  OopMap* map = new OopMap(frame_size_in_bytes / sizeof(jint), 0);

  address start = __ pc();

  __ save_LR(R11_scratch1);

  // Push a frame.
  __ push_frame_reg_args(0, R11_scratch1);

  address frame_complete_pc = __ pc();

  // Note that we always have a runtime stub frame on the top of
  // stack by this point. Remember the offset of the instruction
  // whose address will be moved to R11_scratch1.
  address gc_map_pc = __ get_PC_trash_LR(R11_scratch1);

  __ set_last_Java_frame(/*sp*/R1_SP, /*pc*/R11_scratch1);

  __ mr(R3_ARG1, R16_thread);
  __ call_c(runtime_entry);

  // Set an oopmap for the call site.
  oop_maps->add_gc_map((int)(gc_map_pc - start), map);

  __ reset_last_Java_frame();

#ifdef ASSERT
  // Make sure that this code is only executed if there is a pending
  // exception.
  {
    Label L;
    __ ld(R0,
          in_bytes(Thread::pending_exception_offset()),
          R16_thread);
    __ cmpdi(CR0, R0, 0);
    __ bne(CR0, L);
    __ stop("SharedRuntime::throw_exception: no pending exception");
    __ bind(L);
  }
#endif

  // Pop frame.
  __ pop_frame();

  __ restore_LR(R11_scratch1);

  __ load_const(R11_scratch1, StubRoutines::forward_exception_entry());
  __ mtctr(R11_scratch1);
  __ bctr();

  // Create runtime stub with OopMap.
  RuntimeStub* stub =
    RuntimeStub::new_runtime_stub(name, &code,
                                  /*frame_complete=*/ (int)(frame_complete_pc - start),
                                  frame_size_in_bytes/wordSize,
                                  oop_maps,
                                  false);
  return stub;
}

//------------------------------Montgomery multiplication------------------------
//

// Subtract 0:b from carry:a. Return carry.
static unsigned long
sub(unsigned long a[], unsigned long b[], unsigned long carry, long len) {
  long i = 0;
  unsigned long tmp, tmp2;
  __asm__ __volatile__ (
    "subfc  %[tmp], %[tmp], %[tmp]   \n" // pre-set CA
    "mtctr  %[len]                   \n"
    "0:                              \n"
    "ldx    %[tmp], %[i], %[a]       \n"
    "ldx    %[tmp2], %[i], %[b]      \n"
    "subfe  %[tmp], %[tmp2], %[tmp]  \n" // subtract extended
    "stdx   %[tmp], %[i], %[a]       \n"
    "addi   %[i], %[i], 8            \n"
    "bdnz   0b                       \n"
    "addme  %[tmp], %[carry]         \n" // carry + CA - 1
    : [i]"+b"(i), [tmp]"=&r"(tmp), [tmp2]"=&r"(tmp2)
    : [a]"r"(a), [b]"r"(b), [carry]"r"(carry), [len]"r"(len)
    : "ctr", "xer", "memory"
  );
  return tmp;
}

// Multiply (unsigned) Long A by Long B, accumulating the double-
// length result into the accumulator formed of T0, T1, and T2.
inline void MACC(unsigned long A, unsigned long B, unsigned long &T0, unsigned long &T1, unsigned long &T2) {
  unsigned long hi, lo;
  __asm__ __volatile__ (
    "mulld  %[lo], %[A], %[B]    \n"
    "mulhdu %[hi], %[A], %[B]    \n"
    "addc   %[T0], %[T0], %[lo]  \n"
    "adde   %[T1], %[T1], %[hi]  \n"
    "addze  %[T2], %[T2]         \n"
    : [hi]"=&r"(hi), [lo]"=&r"(lo), [T0]"+r"(T0), [T1]"+r"(T1), [T2]"+r"(T2)
    : [A]"r"(A), [B]"r"(B)
    : "xer"
  );
}

// As above, but add twice the double-length result into the
// accumulator.
inline void MACC2(unsigned long A, unsigned long B, unsigned long &T0, unsigned long &T1, unsigned long &T2) {
  unsigned long hi, lo;
  __asm__ __volatile__ (
    "mulld  %[lo], %[A], %[B]    \n"
    "mulhdu %[hi], %[A], %[B]    \n"
    "addc   %[T0], %[T0], %[lo]  \n"
    "adde   %[T1], %[T1], %[hi]  \n"
    "addze  %[T2], %[T2]         \n"
    "addc   %[T0], %[T0], %[lo]  \n"
    "adde   %[T1], %[T1], %[hi]  \n"
    "addze  %[T2], %[T2]         \n"
    : [hi]"=&r"(hi), [lo]"=&r"(lo), [T0]"+r"(T0), [T1]"+r"(T1), [T2]"+r"(T2)
    : [A]"r"(A), [B]"r"(B)
    : "xer"
  );
}

// Fast Montgomery multiplication. The derivation of the algorithm is
// in "A Cryptographic Library for the Motorola DSP56000,
// Dusse and Kaliski, Proc. EUROCRYPT 90, pp. 230-237".
static void
montgomery_multiply(unsigned long a[], unsigned long b[], unsigned long n[],
                    unsigned long m[], unsigned long inv, int len) {
  unsigned long t0 = 0, t1 = 0, t2 = 0; // Triple-precision accumulator
  int i;

  assert(inv * n[0] == -1UL, "broken inverse in Montgomery multiply");

  for (i = 0; i < len; i++) {
    int j;
    for (j = 0; j < i; j++) {
      MACC(a[j], b[i-j], t0, t1, t2);
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    MACC(a[i], b[0], t0, t1, t2);
    m[i] = t0 * inv;
    MACC(m[i], n[0], t0, t1, t2);

    assert(t0 == 0, "broken Montgomery multiply");

    t0 = t1; t1 = t2; t2 = 0;
  }

  for (i = len; i < 2*len; i++) {
    int j;
    for (j = i-len+1; j < len; j++) {
      MACC(a[j], b[i-j], t0, t1, t2);
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    m[i-len] = t0;
    t0 = t1; t1 = t2; t2 = 0;
  }

  while (t0) {
    t0 = sub(m, n, t0, len);
  }
}

// Fast Montgomery squaring. This uses asymptotically 25% fewer
// multiplies so it should be up to 25% faster than Montgomery
// multiplication. However, its loop control is more complex and it
// may actually run slower on some machines.
static void
montgomery_square(unsigned long a[], unsigned long n[],
                  unsigned long m[], unsigned long inv, int len) {
  unsigned long t0 = 0, t1 = 0, t2 = 0; // Triple-precision accumulator
  int i;

  assert(inv * n[0] == -1UL, "broken inverse in Montgomery multiply");

  for (i = 0; i < len; i++) {
    int j;
    int end = (i+1)/2;
    for (j = 0; j < end; j++) {
      MACC2(a[j], a[i-j], t0, t1, t2);
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    if ((i & 1) == 0) {
      MACC(a[j], a[j], t0, t1, t2);
    }
    for (; j < i; j++) {
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    m[i] = t0 * inv;
    MACC(m[i], n[0], t0, t1, t2);

    assert(t0 == 0, "broken Montgomery square");

    t0 = t1; t1 = t2; t2 = 0;
  }

  for (i = len; i < 2*len; i++) {
    int start = i-len+1;
    int end = start + (len - start)/2;
    int j;
    for (j = start; j < end; j++) {
      MACC2(a[j], a[i-j], t0, t1, t2);
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    if ((i & 1) == 0) {
      MACC(a[j], a[j], t0, t1, t2);
    }
    for (; j < len; j++) {
      MACC(m[j], n[i-j], t0, t1, t2);
    }
    m[i-len] = t0;
    t0 = t1; t1 = t2; t2 = 0;
  }

  while (t0) {
    t0 = sub(m, n, t0, len);
  }
}

// The threshold at which squaring is advantageous was determined
// experimentally on an i7-3930K (Ivy Bridge) CPU @ 3.5GHz.
// Doesn't seem to be relevant for Power8 so we use the same value.
#define MONTGOMERY_SQUARING_THRESHOLD 64

// Copy len longwords from s to d, word-swapping as we go. The
// destination array is reversed.
static void reverse_words(unsigned long *s, unsigned long *d, int len) {
  d += len;
  while(len-- > 0) {
    d--;
    unsigned long s_val = *s;
    // Swap words in a longword on little endian machines.
#ifdef VM_LITTLE_ENDIAN
     s_val = (s_val << 32) | (s_val >> 32);
#endif
    *d = s_val;
    s++;
  }
}

void SharedRuntime::montgomery_multiply(jint *a_ints, jint *b_ints, jint *n_ints,
                                        jint len, jlong inv,
                                        jint *m_ints) {
  len = len & 0x7fffFFFF; // C2 does not respect int to long conversion for stub calls.
  assert(len % 2 == 0, "array length in montgomery_multiply must be even");
  int longwords = len/2;

  // Make very sure we don't use so much space that the stack might
  // overflow. 512 jints corresponds to an 16384-bit integer and
  // will use here a total of 8k bytes of stack space.
  int divisor = sizeof(unsigned long) * 4;
  guarantee(longwords <= 8192 / divisor, "must be");
  int total_allocation = longwords * sizeof (unsigned long) * 4;
  unsigned long *scratch = (unsigned long *)alloca(total_allocation);

  // Local scratch arrays
  unsigned long
    *a = scratch + 0 * longwords,
    *b = scratch + 1 * longwords,
    *n = scratch + 2 * longwords,
    *m = scratch + 3 * longwords;

  reverse_words((unsigned long *)a_ints, a, longwords);
  reverse_words((unsigned long *)b_ints, b, longwords);
  reverse_words((unsigned long *)n_ints, n, longwords);

  ::montgomery_multiply(a, b, n, m, (unsigned long)inv, longwords);

  reverse_words(m, (unsigned long *)m_ints, longwords);
}

void SharedRuntime::montgomery_square(jint *a_ints, jint *n_ints,
                                      jint len, jlong inv,
                                      jint *m_ints) {
  len = len & 0x7fffFFFF; // C2 does not respect int to long conversion for stub calls.
  assert(len % 2 == 0, "array length in montgomery_square must be even");
  int longwords = len/2;

  // Make very sure we don't use so much space that the stack might
  // overflow. 512 jints corresponds to an 16384-bit integer and
  // will use here a total of 6k bytes of stack space.
  int divisor = sizeof(unsigned long) * 3;
  guarantee(longwords <= (8192 / divisor), "must be");
  int total_allocation = longwords * sizeof (unsigned long) * 3;
  unsigned long *scratch = (unsigned long *)alloca(total_allocation);

  // Local scratch arrays
  unsigned long
    *a = scratch + 0 * longwords,
    *n = scratch + 1 * longwords,
    *m = scratch + 2 * longwords;

  reverse_words((unsigned long *)a_ints, a, longwords);
  reverse_words((unsigned long *)n_ints, n, longwords);

  if (len >= MONTGOMERY_SQUARING_THRESHOLD) {
    ::montgomery_square(a, n, m, (unsigned long)inv, longwords);
  } else {
    ::montgomery_multiply(a, a, n, m, (unsigned long)inv, longwords);
  }

  reverse_words(m, (unsigned long *)m_ints, longwords);
}

#if INCLUDE_JFR

// For c2: c_rarg0 is junk, call to runtime to write a checkpoint.
// It returns a jobject handle to the event writer.
// The handle is dereferenced and the return value is the event writer oop.
RuntimeStub* SharedRuntime::generate_jfr_write_checkpoint() {
  const char* name = SharedRuntime::stub_name(StubId::shared_jfr_write_checkpoint_id);
  CodeBuffer code(name, 512, 64);
  MacroAssembler* masm = new MacroAssembler(&code);

  Register tmp1 = R10_ARG8;
  Register tmp2 = R9_ARG7;

  int framesize = frame::native_abi_reg_args_size / VMRegImpl::stack_slot_size;
  address start = __ pc();
  __ mflr(tmp1);
  __ std(tmp1, _abi0(lr), R1_SP);  // save return pc
  __ push_frame_reg_args(0, tmp1);
  int frame_complete = __ pc() - start;
  __ set_last_Java_frame(R1_SP, noreg);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, JfrIntrinsicSupport::write_checkpoint), R16_thread);
  address calls_return_pc = __ last_calls_return_pc();
  __ reset_last_Java_frame();
  // The handle is dereferenced through a load barrier.
  __ resolve_global_jobject(R3_RET, tmp1, tmp2, MacroAssembler::PRESERVATION_NONE);
  __ pop_frame();
  __ ld(tmp1, _abi0(lr), R1_SP);
  __ mtlr(tmp1);
  __ blr();

  OopMapSet* oop_maps = new OopMapSet();
  OopMap* map = new OopMap(framesize, 0);
  oop_maps->add_gc_map(calls_return_pc - start, map);

  RuntimeStub* stub = // codeBlob framesize is in words (not VMRegImpl::slot_size)
    RuntimeStub::new_runtime_stub(name, &code, frame_complete,
                                  (framesize >> (LogBytesPerWord - LogBytesPerInt)),
                                  oop_maps, false);
  return stub;
}

// For c2: call to return a leased buffer.
RuntimeStub* SharedRuntime::generate_jfr_return_lease() {
  const char* name = SharedRuntime::stub_name(StubId::shared_jfr_return_lease_id);
  CodeBuffer code(name, 512, 64);
  MacroAssembler* masm = new MacroAssembler(&code);

  Register tmp1 = R10_ARG8;
  Register tmp2 = R9_ARG7;

  int framesize = frame::native_abi_reg_args_size / VMRegImpl::stack_slot_size;
  address start = __ pc();
  __ mflr(tmp1);
  __ std(tmp1, _abi0(lr), R1_SP);  // save return pc
  __ push_frame_reg_args(0, tmp1);
  int frame_complete = __ pc() - start;
  __ set_last_Java_frame(R1_SP, noreg);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, JfrIntrinsicSupport::return_lease), R16_thread);
  address calls_return_pc = __ last_calls_return_pc();
  __ reset_last_Java_frame();
  __ pop_frame();
  __ ld(tmp1, _abi0(lr), R1_SP);
  __ mtlr(tmp1);
  __ blr();

  OopMapSet* oop_maps = new OopMapSet();
  OopMap* map = new OopMap(framesize, 0);
  oop_maps->add_gc_map(calls_return_pc - start, map);

  RuntimeStub* stub = // codeBlob framesize is in words (not VMRegImpl::slot_size)
    RuntimeStub::new_runtime_stub(name, &code, frame_complete,
                                  (framesize >> (LogBytesPerWord - LogBytesPerInt)),
                                  oop_maps, false);
  return stub;
}

#endif // INCLUDE_JFR

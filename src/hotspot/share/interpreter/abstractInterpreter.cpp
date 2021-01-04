/*
 * Copyright (c) 1997, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "asm/macroAssembler.hpp"
#include "asm/macroAssembler.inline.hpp"
#include "compiler/disassembler.hpp"
#include "interpreter/bytecodeHistogram.hpp"
#include "interpreter/bytecodeStream.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/interpreterRuntime.hpp"
#include "interpreter/interp_masm.hpp"
#include "interpreter/templateTable.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/metaspaceShared.hpp"
#include "memory/resourceArea.hpp"
#include "oops/arrayOop.hpp"
#include "oops/constantPool.hpp"
#include "oops/cpCache.inline.hpp"
#include "oops/methodData.hpp"
#include "oops/method.hpp"
#include "oops/oop.inline.hpp"
#include "prims/forte.hpp"
#include "prims/jvmtiExport.hpp"
#include "prims/methodHandles.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/timer.hpp"

# define __ _masm->

//------------------------------------------------------------------------------------------------------------------------
// Implementation of platform independent aspects of Interpreter

void AbstractInterpreter::initialize() {
  // make sure 'imported' classes are initialized
  if (CountBytecodes || TraceBytecodes || StopInterpreterAt) BytecodeCounter::reset();
  if (PrintBytecodeHistogram)                                BytecodeHistogram::reset();
  if (PrintBytecodePairHistogram)                            BytecodePairHistogram::reset();
}

void AbstractInterpreter::print() {
  tty->cr();
  tty->print_cr("----------------------------------------------------------------------");
  tty->print_cr("Interpreter");
  tty->cr();
  tty->print_cr("code size        = %6dK bytes", (int)_code->used_space()/1024);
  tty->print_cr("total space      = %6dK bytes", (int)_code->total_space()/1024);
  tty->print_cr("wasted space     = %6dK bytes", (int)_code->available_space()/1024);
  tty->cr();
  tty->print_cr("# of codelets    = %6d"      , _code->number_of_stubs());
  if (_code->number_of_stubs() != 0) {
    tty->print_cr("avg codelet size = %6d bytes", _code->used_space() / _code->number_of_stubs());
    tty->cr();
  }
  _code->print();
  tty->print_cr("----------------------------------------------------------------------");
  tty->cr();
}


//------------------------------------------------------------------------------------------------------------------------
// Implementation of interpreter

StubQueue* AbstractInterpreter::_code                                       = NULL;
bool       AbstractInterpreter::_notice_safepoints                          = false;
address    AbstractInterpreter::_rethrow_exception_entry                    = NULL;

address    AbstractInterpreter::_native_entry_begin                         = NULL;
address    AbstractInterpreter::_native_entry_end                           = NULL;
address    AbstractInterpreter::_slow_signature_handler;
address    AbstractInterpreter::_entry_table            [AbstractInterpreter::number_of_method_entries];
address    AbstractInterpreter::_cds_entry_table        [AbstractInterpreter::number_of_method_entries];
address    AbstractInterpreter::_native_abi_to_tosca    [AbstractInterpreter::number_of_result_handlers];

//------------------------------------------------------------------------------------------------------------------------
// Generation of complete interpreter

AbstractInterpreterGenerator::AbstractInterpreterGenerator(StubQueue* _code) {
  _masm                      = NULL;
}


//------------------------------------------------------------------------------------------------------------------------
// Entry points

AbstractInterpreter::MethodKind AbstractInterpreter::method_kind(const methodHandle& m) {
  // Abstract method?
  if (m->is_abstract()) return abstract;

  // Method handle primitive?
  if (m->is_method_handle_intrinsic()) {
    vmIntrinsics::ID id = m->intrinsic_id();
    assert(MethodHandles::is_signature_polymorphic(id), "must match an intrinsic");
    MethodKind kind = (MethodKind)(method_handle_invoke_FIRST +
                                   vmIntrinsics::as_int(id) -
                                   static_cast<int>(vmIntrinsics::FIRST_MH_SIG_POLY));
    assert(kind <= method_handle_invoke_LAST, "parallel enum ranges");
    return kind;
  }

#ifndef ZERO
  switch (m->intrinsic_id()) {
    // Use optimized stub code for CRC32 native methods.
    case vmIntrinsics::_updateCRC32            : return java_util_zip_CRC32_update;
    case vmIntrinsics::_updateBytesCRC32       : return java_util_zip_CRC32_updateBytes;
    case vmIntrinsics::_updateByteBufferCRC32  : return java_util_zip_CRC32_updateByteBuffer;
    // Use optimized stub code for CRC32C methods.
    case vmIntrinsics::_updateBytesCRC32C             : return java_util_zip_CRC32C_updateBytes;
    case vmIntrinsics::_updateDirectByteBufferCRC32C  : return java_util_zip_CRC32C_updateDirectByteBuffer;
    case vmIntrinsics::_intBitsToFloat:      return java_lang_Float_intBitsToFloat;
    case vmIntrinsics::_floatToRawIntBits:   return java_lang_Float_floatToRawIntBits;
    case vmIntrinsics::_longBitsToDouble:    return java_lang_Double_longBitsToDouble;
    case vmIntrinsics::_doubleToRawLongBits: return java_lang_Double_doubleToRawLongBits;
    default:                                 break;
  }
#endif // ZERO

  // Native method?
  // Note: This test must come _before_ the test for intrinsic
  //       methods. See also comments below.
  if (m->is_native()) {
    if (m->is_continuation_enter_intrinsic()) {
      return zerolocals;
    }
    assert(!m->is_method_handle_intrinsic(), "overlapping bits here, watch out");
    return m->is_synchronized() ? native_synchronized : native;
  }

  // Synchronized?
  if (m->is_synchronized()) {
    return zerolocals_synchronized;
  }

  if (RegisterFinalizersAtInit && m->code_size() == 1 &&
      m->intrinsic_id() == vmIntrinsics::_Object_init) {
    // We need to execute the special return bytecode to check for
    // finalizer registration so create a normal frame.
    return zerolocals;
  }

  // Empty method?
  if (m->is_empty_method()) {
    return empty;
  }

  // Special intrinsic method?
  // Note: This test must come _after_ the test for native methods,
  //       otherwise we will run into problems with JDK 1.2, see also
  //       TemplateInterpreterGenerator::generate_method_entry() for
  //       for details.
  switch (m->intrinsic_id()) {
    case vmIntrinsics::_dsin  : return java_lang_math_sin  ;
    case vmIntrinsics::_dcos  : return java_lang_math_cos  ;
    case vmIntrinsics::_dtan  : return java_lang_math_tan  ;
    case vmIntrinsics::_dabs  : return java_lang_math_abs  ;
    case vmIntrinsics::_dsqrt : return java_lang_math_sqrt ;
    case vmIntrinsics::_dlog  : return java_lang_math_log  ;
    case vmIntrinsics::_dlog10: return java_lang_math_log10;
    case vmIntrinsics::_dpow  : return java_lang_math_pow  ;
    case vmIntrinsics::_dexp  : return java_lang_math_exp  ;
    case vmIntrinsics::_fmaD  : return java_lang_math_fmaD ;
    case vmIntrinsics::_fmaF  : return java_lang_math_fmaF ;

    case vmIntrinsics::_Reference_get
                              : return java_lang_ref_reference_get;
    case vmIntrinsics::_Continuation_doYield
                              : return java_lang_continuation_doYield;
    default                   : break;
  }

  // Getter method?
  if (m->is_getter()) {
    return getter;
  }

  // Setter method?
  if (m->is_setter()) {
    return setter;
  }

  // Symbol* kname = m->klass_name();
  // Symbol* name = m->name();
  // if (kname == vmSymbols::java_lang_Continuation()) {
  //   if (name == vmSymbols::enter_name()) {
  //     return java_lang_continuation_enter;
  //   }
  // }

  // Note: for now: zero locals for all non-empty methods
  return zerolocals;
}

#if INCLUDE_CDS

// For a shared Method m, to improve sharing across processes, we avoid writing to m->_i2i_entry
// at runtime. Instead, m->_i2i_entry points to a fixed location inside the CDS archive.
// This location contains a trampoline (generated by generate_entry_for_cds_method)
// which jumps to _entry_table[kind].
address AbstractInterpreter::entry_for_cds_method(const methodHandle& m) {
  MethodKind kind = method_kind(m);
  assert(0 <= kind && kind < number_of_method_entries, "illegal kind");
  return entry_for_cds_method(kind);
}

address AbstractInterpreter::entry_for_cds_method(AbstractInterpreter::MethodKind kind) {
  const size_t trampoline_size = SharedRuntime::trampoline_size();
  address addr = MetaspaceShared::i2i_entry_code_buffers();
  addr += (size_t)(kind) * trampoline_size;

  return addr;
}

void AbstractInterpreter::generate_entry_for_cds_method(AbstractInterpreter::MethodKind kind) {
  if (UseSharedSpaces) {
    address trampoline = entry_for_cds_method(kind);
    CodeBuffer buffer(trampoline, (int)(SharedRuntime::trampoline_size()));
    MacroAssembler _masm(&buffer);
    SharedRuntime::generate_trampoline(&_masm, _entry_table[kind]);
    _masm.flush();

    if (PrintInterpreter) {
      Disassembler::decode(buffer.insts_begin(), buffer.insts_end());
    }
  }
}

#endif

void AbstractInterpreter::set_entry_for_kind(AbstractInterpreter::MethodKind kind, address entry) {
  assert(kind >= method_handle_invoke_FIRST &&
         kind <= method_handle_invoke_LAST, "late initialization only for MH entry points");
  assert(_entry_table[kind] == _entry_table[abstract], "previous value must be AME entry");
  _entry_table[kind] = entry;

  generate_entry_for_cds_method(kind);
}

// Return true if the interpreter can prove that the given bytecode has
// not yet been executed (in Java semantics, not in actual operation).
bool AbstractInterpreter::is_not_reached(const methodHandle& method, int bci) {
  BytecodeStream s(method, bci);
  Bytecodes::Code code = s.next();

  if (Bytecodes::is_invoke(code)) {
    assert(!Bytecodes::must_rewrite(code), "invokes aren't rewritten");
    ConstantPool* cpool = method()->constants();

    Bytecode invoke_bc(s.bytecode());

    switch (code) {
      case Bytecodes::_invokedynamic: {
        assert(invoke_bc.has_index_u4(code), "sanity");
        int method_index = invoke_bc.get_index_u4(code);
        return cpool->invokedynamic_cp_cache_entry_at(method_index)->is_f1_null();
      }
      case Bytecodes::_invokevirtual:   // fall-through
      case Bytecodes::_invokeinterface: // fall-through
      case Bytecodes::_invokespecial:   // fall-through
      case Bytecodes::_invokestatic: {
        if (cpool->has_preresolution()) {
          return false; // might have been reached
        }
        assert(!invoke_bc.has_index_u4(code), "sanity");
        int method_index = invoke_bc.get_index_u2_cpcache(code);
        constantPoolHandle cp(Thread::current(), cpool);
        Method* resolved_method = ConstantPool::method_at_if_loaded(cp, method_index);
        return (resolved_method == NULL);
      }
      default: ShouldNotReachHere();
    }
  } else if (!Bytecodes::must_rewrite(code)) {
    // might have been reached
    return false;
  }

  // the bytecode might not be rewritten if the method is an accessor, etc.
  address ientry = method->interpreter_entry();
  if (ientry != entry_for_kind(AbstractInterpreter::zerolocals) &&
      ientry != entry_for_kind(AbstractInterpreter::zerolocals_synchronized))
    return false;  // interpreter does not run this method!

  // otherwise, we can be sure this bytecode has never been executed
  return true;
}


#ifndef PRODUCT
void AbstractInterpreter::print_method_kind(MethodKind kind) {
  switch (kind) {
    case zerolocals             : tty->print("zerolocals"             ); break;
    case zerolocals_synchronized: tty->print("zerolocals_synchronized"); break;
    case native                 : tty->print("native"                 ); break;
    case native_synchronized    : tty->print("native_synchronized"    ); break;
    case empty                  : tty->print("empty"                  ); break;
    case getter                 : tty->print("getter"                 ); break;
    case setter                 : tty->print("setter"                 ); break;
    case abstract               : tty->print("abstract"               ); break;
    case java_lang_math_sin     : tty->print("java_lang_math_sin"     ); break;
    case java_lang_math_cos     : tty->print("java_lang_math_cos"     ); break;
    case java_lang_math_tan     : tty->print("java_lang_math_tan"     ); break;
    case java_lang_math_abs     : tty->print("java_lang_math_abs"     ); break;
    case java_lang_math_sqrt    : tty->print("java_lang_math_sqrt"    ); break;
    case java_lang_math_log     : tty->print("java_lang_math_log"     ); break;
    case java_lang_math_log10   : tty->print("java_lang_math_log10"   ); break;
    case java_lang_math_fmaD    : tty->print("java_lang_math_fmaD"    ); break;
    case java_lang_math_fmaF    : tty->print("java_lang_math_fmaF"    ); break;
    case java_util_zip_CRC32_update           : tty->print("java_util_zip_CRC32_update"); break;
    case java_util_zip_CRC32_updateBytes      : tty->print("java_util_zip_CRC32_updateBytes"); break;
    case java_util_zip_CRC32_updateByteBuffer : tty->print("java_util_zip_CRC32_updateByteBuffer"); break;
    case java_util_zip_CRC32C_updateBytes     : tty->print("java_util_zip_CRC32C_updateBytes"); break;
    case java_util_zip_CRC32C_updateDirectByteBuffer: tty->print("java_util_zip_CRC32C_updateDirectByteByffer"); break;
    default:
      if (kind >= method_handle_invoke_FIRST &&
          kind <= method_handle_invoke_LAST) {
        const char* kind_name = vmIntrinsics::name_at(method_handle_intrinsic(kind));
        if (kind_name[0] == '_')  kind_name = &kind_name[1];  // '_invokeExact' => 'invokeExact'
        tty->print("method_handle_%s", kind_name);
        break;
      }
      ShouldNotReachHere();
      break;
  }
}
#endif // PRODUCT


//------------------------------------------------------------------------------------------------------------------------
// Deoptimization support

/**
 * If a deoptimization happens, this function returns the point of next bytecode to continue execution.
 */
address AbstractInterpreter::deopt_continue_after_entry(Method* method, address bcp, int callee_parameters, bool is_top_frame) {
  assert(method->contains(bcp), "just checkin'");

  // Get the original and rewritten bytecode.
  Bytecodes::Code code = Bytecodes::java_code_at(method, bcp);
  assert(!Interpreter::bytecode_should_reexecute(code), "should not reexecute");

  const int bci = method->bci_from(bcp);

  // compute continuation length
  const int length = Bytecodes::length_at(method, bcp);

  // compute result type
  BasicType type = T_ILLEGAL;

  switch (code) {
    case Bytecodes::_invokevirtual  :
    case Bytecodes::_invokespecial  :
    case Bytecodes::_invokestatic   :
    case Bytecodes::_invokeinterface: {
      Thread *thread = Thread::current();
      ResourceMark rm(thread);
      methodHandle mh(thread, method);
      type = Bytecode_invoke(mh, bci).result_type();
      // since the cache entry might not be initialized:
      // (NOT needed for the old calling convension)
      if (!is_top_frame) {
        int index = Bytes::get_native_u2(bcp+1);
        method->constants()->cache()->entry_at(index)->set_parameter_size(callee_parameters);
      }
      break;
    }

   case Bytecodes::_invokedynamic: {
      Thread *thread = Thread::current();
      ResourceMark rm(thread);
      methodHandle mh(thread, method);
      type = Bytecode_invoke(mh, bci).result_type();
      // since the cache entry might not be initialized:
      // (NOT needed for the old calling convension)
      if (!is_top_frame) {
        int index = Bytes::get_native_u4(bcp+1);
        method->constants()->invokedynamic_cp_cache_entry_at(index)->set_parameter_size(callee_parameters);
      }
      break;
    }

    case Bytecodes::_ldc   :
    case Bytecodes::_ldc_w : // fall through
    case Bytecodes::_ldc2_w:
      {
        Thread *thread = Thread::current();
        ResourceMark rm(thread);
        methodHandle mh(thread, method);
        type = Bytecode_loadconstant(mh, bci).result_type();
        break;
      }

    default:
      type = Bytecodes::result_type(code);
      break;
  }

  // return entry point for computed continuation state & bytecode length
  return
    is_top_frame
    ? Interpreter::deopt_entry (as_TosState(type), length)
    : Interpreter::return_entry(as_TosState(type), length, code);
}

// If deoptimization happens, this function returns the point where the interpreter reexecutes
// the bytecode.
// Note: Bytecodes::_athrow is a special case in that it does not return
//       Interpreter::deopt_entry(vtos, 0) like others
address AbstractInterpreter::deopt_reexecute_entry(Method* method, address bcp) {
  assert(method->contains(bcp), "just checkin'");
  Bytecodes::Code code   = Bytecodes::java_code_at(method, bcp);
#if defined(COMPILER1) || INCLUDE_JVMCI
  if(code == Bytecodes::_athrow ) {
    return Interpreter::rethrow_exception_entry();
  }
#endif /* COMPILER1 || INCLUDE_JVMCI */
  return Interpreter::deopt_entry(vtos, 0);
}

// If deoptimization happens, the interpreter should reexecute these bytecodes.
// This function mainly helps the compilers to set up the reexecute bit.
bool AbstractInterpreter::bytecode_should_reexecute(Bytecodes::Code code) {
  switch (code) {
    case Bytecodes::_lookupswitch:
    case Bytecodes::_tableswitch:
    case Bytecodes::_fast_binaryswitch:
    case Bytecodes::_fast_linearswitch:
    // recompute condtional expression folded into _if<cond>
    case Bytecodes::_lcmp      :
    case Bytecodes::_fcmpl     :
    case Bytecodes::_fcmpg     :
    case Bytecodes::_dcmpl     :
    case Bytecodes::_dcmpg     :
    case Bytecodes::_ifnull    :
    case Bytecodes::_ifnonnull :
    case Bytecodes::_goto      :
    case Bytecodes::_goto_w    :
    case Bytecodes::_ifeq      :
    case Bytecodes::_ifne      :
    case Bytecodes::_iflt      :
    case Bytecodes::_ifge      :
    case Bytecodes::_ifgt      :
    case Bytecodes::_ifle      :
    case Bytecodes::_if_icmpeq :
    case Bytecodes::_if_icmpne :
    case Bytecodes::_if_icmplt :
    case Bytecodes::_if_icmpge :
    case Bytecodes::_if_icmpgt :
    case Bytecodes::_if_icmple :
    case Bytecodes::_if_acmpeq :
    case Bytecodes::_if_acmpne :
    // special cases
    case Bytecodes::_getfield  :
    case Bytecodes::_putfield  :
    case Bytecodes::_getstatic :
    case Bytecodes::_putstatic :
    case Bytecodes::_aastore   :
#ifdef COMPILER1
    //special case of reexecution
    case Bytecodes::_athrow    :
#endif
      return true;

    default:
      return false;
  }
}

void AbstractInterpreter::initialize_method_handle_entries() {
  // method handle entry kinds are generated later in MethodHandlesAdapterGenerator::generate:
  for (int i = method_handle_invoke_FIRST; i <= method_handle_invoke_LAST; i++) {
    MethodKind kind = (MethodKind) i;
    _entry_table[kind] = _entry_table[Interpreter::abstract];
    Interpreter::generate_entry_for_cds_method(kind);
  }
}

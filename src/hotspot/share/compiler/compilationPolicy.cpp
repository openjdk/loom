/*
 * Copyright (c) 2000, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "classfile/classLoaderDataGraph.inline.hpp"
#include "code/compiledIC.hpp"
#include "code/nmethod.hpp"
#include "code/scopeDesc.hpp"
#include "compiler/compilationPolicy.hpp"
#include "compiler/tieredThresholdPolicy.hpp"
#include "interpreter/interpreter.hpp"
#include "memory/resourceArea.hpp"
#include "oops/methodData.hpp"
#include "oops/method.inline.hpp"
#include "oops/oop.inline.hpp"
#include "prims/jvmtiExport.hpp"
#include "prims/nativeLookup.hpp"
#include "runtime/frame.hpp"
#include "runtime/globals_extension.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/thread.hpp"
#include "runtime/vframe.hpp"
#include "runtime/vmOperations.hpp"
#include "utilities/events.hpp"
#include "utilities/globalDefinitions.hpp"

#ifdef COMPILER1
#include "c1/c1_Compiler.hpp"
#endif
#ifdef COMPILER2
#include "opto/c2compiler.hpp"
#endif

CompilationPolicy* CompilationPolicy::_policy;

// Determine compilation policy based on command line argument
void compilationPolicy_init() {
  #ifdef TIERED
  if (TieredCompilation) {
    CompilationPolicy::set_policy(new TieredThresholdPolicy());
  } else {
    CompilationPolicy::set_policy(new SimpleCompPolicy());
  }
  #else
  CompilationPolicy::set_policy(new SimpleCompPolicy());
  #endif

  CompilationPolicy::policy()->initialize();
}

// Returns true if m must be compiled before executing it
// This is intended to force compiles for methods (usually for
// debugging) that would otherwise be interpreted for some reason.
bool CompilationPolicy::must_be_compiled(const methodHandle& m, int comp_level) {
  // Don't allow Xcomp to cause compiles in replay mode
  if (ReplayCompiles) return false;

  if (m->has_compiled_code()) return false;       // already compiled
  if (!can_be_compiled(m, comp_level)) return false;

  return !UseInterpreter ||                                              // must compile all methods
         (UseCompiler && AlwaysCompileLoopMethods && m->has_loops() && CompileBroker::should_compile_new_jobs()); // eagerly compile loop methods
}

void CompilationPolicy::compile_if_required(const methodHandle& selected_method, TRAPS) {
  if (must_be_compiled(selected_method)) {
    // This path is unusual, mostly used by the '-Xcomp' stress test mode.

    // Note: with several active threads, the must_be_compiled may be true
    //       while can_be_compiled is false; remove assert
    // assert(CompilationPolicy::can_be_compiled(selected_method), "cannot compile");
    if (!THREAD->can_call_java() || THREAD->is_Compiler_thread()) {
      // don't force compilation, resolve was on behalf of compiler
      return;
    }
    if (selected_method->method_holder()->is_not_initialized()) {
      // 'is_not_initialized' means not only '!is_initialized', but also that
      // initialization has not been started yet ('!being_initialized')
      // Do not force compilation of methods in uninitialized classes.
      // Note that doing this would throw an assert later,
      // in CompileBroker::compile_method.
      // We sometimes use the link resolver to do reflective lookups
      // even before classes are initialized.
      return;
    }
    CompileBroker::compile_method(selected_method, InvocationEntryBci,
        CompilationPolicy::policy()->initial_compile_level(selected_method),
        methodHandle(), 0, CompileTask::Reason_MustBeCompiled, THREAD);
  }
}

// Returns true if m is allowed to be compiled
bool CompilationPolicy::can_be_compiled(const methodHandle& m, int comp_level) {
  // allow any levels for WhiteBox
  assert(WhiteBoxAPI || comp_level == CompLevel_all || is_compile(comp_level), "illegal compilation level");

  if (m->is_abstract()) return false;
  if (DontCompileHugeMethods && m->code_size() > HugeMethodLimit) return false;

  // Math intrinsics should never be compiled as this can lead to
  // monotonicity problems because the interpreter will prefer the
  // compiled code to the intrinsic version.  This can't happen in
  // production because the invocation counter can't be incremented
  // but we shouldn't expose the system to this problem in testing
  // modes.
  if (!AbstractInterpreter::can_be_compiled(m)) {
    return false;
  }
  if (comp_level == CompLevel_all) {
    if (TieredCompilation) {
      // enough to be compilable at any level for tiered
      return !m->is_not_compilable(CompLevel_simple) || !m->is_not_compilable(CompLevel_full_optimization);
    } else {
      // must be compilable at available level for non-tiered
      return !m->is_not_compilable(CompLevel_highest_tier);
    }
  } else if (is_compile(comp_level)) {
    return !m->is_not_compilable(comp_level);
  }
  return false;
}

// Returns true if m is allowed to be osr compiled
bool CompilationPolicy::can_be_osr_compiled(const methodHandle& m, int comp_level) {
  bool result = false;
  if (comp_level == CompLevel_all) {
    if (TieredCompilation) {
      // enough to be osr compilable at any level for tiered
      result = !m->is_not_osr_compilable(CompLevel_simple) || !m->is_not_osr_compilable(CompLevel_full_optimization);
    } else {
      // must be osr compilable at available level for non-tiered
      result = !m->is_not_osr_compilable(CompLevel_highest_tier);
    }
  } else if (is_compile(comp_level)) {
    result = !m->is_not_osr_compilable(comp_level);
  }
  return (result && can_be_compiled(m, comp_level));
}

bool CompilationPolicy::is_compilation_enabled() {
  // NOTE: CompileBroker::should_compile_new_jobs() checks for UseCompiler
  return CompileBroker::should_compile_new_jobs();
}

CompileTask* CompilationPolicy::select_task_helper(CompileQueue* compile_queue) {
  // Remove unloaded methods from the queue
  for (CompileTask* task = compile_queue->first(); task != NULL; ) {
    CompileTask* next = task->next();
    if (task->is_unloaded()) {
      compile_queue->remove_and_mark_stale(task);
    }
    task = next;
  }
#if INCLUDE_JVMCI
  if (UseJVMCICompiler && !BackgroundCompilation) {
    /*
     * In blocking compilation mode, the CompileBroker will make
     * compilations submitted by a JVMCI compiler thread non-blocking. These
     * compilations should be scheduled after all blocking compilations
     * to service non-compiler related compilations sooner and reduce the
     * chance of such compilations timing out.
     */
    for (CompileTask* task = compile_queue->first(); task != NULL; task = task->next()) {
      if (task->is_blocking()) {
        return task;
      }
    }
  }
#endif
  return compile_queue->first();
}


//
// CounterDecay for SimpleCompPolicy
//
// Iterates through invocation counters and decrements them. This
// is done at each safepoint.
//
class CounterDecay : public AllStatic {
  static jlong _last_timestamp;
  static void do_method(Method* m) {
    MethodCounters* mcs = m->method_counters();
    if (mcs != NULL) {
      mcs->invocation_counter()->decay();
    }
  }
public:
  static void decay();
  static bool is_decay_needed() {
    return nanos_to_millis(os::javaTimeNanos() - _last_timestamp) > CounterDecayMinIntervalLength;
  }
  static void update_last_timestamp() { _last_timestamp = os::javaTimeNanos(); }
};

jlong CounterDecay::_last_timestamp = 0;

void CounterDecay::decay() {
  update_last_timestamp();

  // This operation is going to be performed only at the end of a safepoint
  // and hence GC's will not be going on, all Java mutators are suspended
  // at this point and hence SystemDictionary_lock is also not needed.
  assert(SafepointSynchronize::is_at_safepoint(), "can only be executed at a safepoint");
  size_t nclasses = ClassLoaderDataGraph::num_instance_classes();
  size_t classes_per_tick = nclasses * (CounterDecayMinIntervalLength * 1e-3 /
                                        CounterHalfLifeTime);
  for (size_t i = 0; i < classes_per_tick; i++) {
    InstanceKlass* k = ClassLoaderDataGraph::try_get_next_class();
    if (k != NULL) {
      k->methods_do(do_method);
    }
  }
}


#ifndef PRODUCT
void SimpleCompPolicy::trace_osr_completion(nmethod* osr_nm) {
  if (TraceOnStackReplacement) {
    if (osr_nm == NULL) tty->print_cr("compilation failed");
    else tty->print_cr("nmethod " INTPTR_FORMAT, p2i(osr_nm));
  }
}
#endif // !PRODUCT

void SimpleCompPolicy::initialize() {
  // Setup the compiler thread numbers
  if (CICompilerCountPerCPU) {
    // Example: if CICompilerCountPerCPU is true, then we get
    // max(log2(8)-1,1) = 2 compiler threads on an 8-way machine.
    // May help big-app startup time.
    _compiler_count = MAX2(log2_int(os::active_processor_count())-1,1);
    // Make sure there is enough space in the code cache to hold all the compiler buffers
    size_t buffer_size = 1;
#ifdef COMPILER1
    buffer_size = is_client_compilation_mode_vm() ? Compiler::code_buffer_size() : buffer_size;
#endif
#ifdef COMPILER2
    buffer_size = is_server_compilation_mode_vm() ? C2Compiler::initial_code_buffer_size() : buffer_size;
#endif
    int max_count = (ReservedCodeCacheSize - (CodeCacheMinimumUseSpace DEBUG_ONLY(* 3))) / (int)buffer_size;
    if (_compiler_count > max_count) {
      // Lower the compiler count such that all buffers fit into the code cache
      _compiler_count = MAX2(max_count, 1);
    }
    FLAG_SET_ERGO(CICompilerCount, _compiler_count);
  } else {
    _compiler_count = CICompilerCount;
  }
  CounterDecay::update_last_timestamp();
}

// Note: this policy is used ONLY if TieredCompilation is off.
// compiler_count() behaves the following way:
// - with TIERED build (with both COMPILER1 and COMPILER2 defined) it should return
//   zero for the c1 compilation levels in server compilation mode runs
//   and c2 compilation levels in client compilation mode runs.
// - with COMPILER2 not defined it should return zero for c2 compilation levels.
// - with COMPILER1 not defined it should return zero for c1 compilation levels.
// - if neither is defined - always return zero.
int SimpleCompPolicy::compiler_count(CompLevel comp_level) {
  assert(!TieredCompilation, "This policy should not be used with TieredCompilation");
  if (COMPILER2_PRESENT(is_server_compilation_mode_vm() && is_c2_compile(comp_level) ||)
      is_client_compilation_mode_vm() && is_c1_compile(comp_level)) {
    return _compiler_count;
  }
  return 0;
}

void SimpleCompPolicy::reset_counter_for_invocation_event(const methodHandle& m) {
  // Make sure invocation and backedge counter doesn't overflow again right away
  // as would be the case for native methods.

  // BUT also make sure the method doesn't look like it was never executed.
  // Set carry bit and reduce counter's value to min(count, CompileThreshold/2).
  MethodCounters* mcs = m->method_counters();
  assert(mcs != NULL, "MethodCounters cannot be NULL for profiling");
  mcs->invocation_counter()->set_carry_and_reduce();
  mcs->backedge_counter()->set_carry_and_reduce();

  assert(!m->was_never_executed(), "don't reset to 0 -- could be mistaken for never-executed");
}

void SimpleCompPolicy::reset_counter_for_back_branch_event(const methodHandle& m) {
  // Delay next back-branch event but pump up invocation counter to trigger
  // whole method compilation.
  MethodCounters* mcs = m->method_counters();
  assert(mcs != NULL, "MethodCounters cannot be NULL for profiling");
  InvocationCounter* i = mcs->invocation_counter();
  InvocationCounter* b = mcs->backedge_counter();

  // Don't set invocation_counter's value too low otherwise the method will
  // look like immature (ic < ~5300) which prevents the inlining based on
  // the type profiling.
  i->set(CompileThreshold);
  // Don't reset counter too low - it is used to check if OSR method is ready.
  b->set(CompileThreshold / 2);
}

// Called at the end of the safepoint
void SimpleCompPolicy::do_safepoint_work() {
  if(UseCounterDecay && CounterDecay::is_decay_needed()) {
    CounterDecay::decay();
  }
}

void SimpleCompPolicy::reprofile(ScopeDesc* trap_scope, bool is_osr) {
  ScopeDesc* sd = trap_scope;
  MethodCounters* mcs;
  InvocationCounter* c;
  for (; !sd->is_top(); sd = sd->sender()) {
    mcs = sd->method()->method_counters();
    if (mcs != NULL) {
      // Reset ICs of inlined methods, since they can trigger compilations also.
      mcs->invocation_counter()->reset();
    }
  }
  mcs = sd->method()->method_counters();
  if (mcs != NULL) {
    c = mcs->invocation_counter();
    if (is_osr) {
      // It was an OSR method, so bump the count higher.
      c->set(CompileThreshold);
    } else {
      c->reset();
    }
    mcs->backedge_counter()->reset();
  }
}

// This method can be called by any component of the runtime to notify the policy
// that it's recommended to delay the compilation of this method.
void SimpleCompPolicy::delay_compilation(Method* method) {
  MethodCounters* mcs = method->method_counters();
  if (mcs != NULL) {
    mcs->invocation_counter()->decay();
    mcs->backedge_counter()->decay();
  }
}

CompileTask* SimpleCompPolicy::select_task(CompileQueue* compile_queue) {
  return select_task_helper(compile_queue);
}

bool SimpleCompPolicy::is_mature(Method* method) {
  MethodData* mdo = method->method_data();
  assert(mdo != NULL, "Should be");
  uint current = mdo->mileage_of(method);
  uint initial = mdo->creation_mileage();
  if (current < initial)
    return true;  // some sort of overflow
  uint target;
  if (ProfileMaturityPercentage <= 0)
    target = (uint) -ProfileMaturityPercentage;  // absolute value
  else
    target = (uint)( (ProfileMaturityPercentage * CompileThreshold) / 100 );
  return (current >= initial + target);
}

nmethod* SimpleCompPolicy::event(const methodHandle& method, const methodHandle& inlinee, int branch_bci,
                                    int bci, CompLevel comp_level, CompiledMethod* nm, TRAPS) {
  assert(comp_level == CompLevel_none, "This should be only called from the interpreter");
  NOT_PRODUCT(trace_frequency_counter_overflow(method, branch_bci, bci));
  if (JvmtiExport::can_post_interpreter_events() && THREAD->as_Java_thread()->is_interp_only_mode()) {
    // If certain JVMTI events (e.g. frame pop event) are requested then the
    // thread is forced to remain in interpreted code. This is
    // implemented partly by a check in the run_compiled_code
    // section of the interpreter whether we should skip running
    // compiled code, and partly by skipping OSR compiles for
    // interpreted-only threads.
    if (bci != InvocationEntryBci) {
      reset_counter_for_back_branch_event(method);
      return NULL;
    }
  }
  if (ReplayCompiles) {
    // Don't trigger other compiles in testing mode
    if (bci == InvocationEntryBci) {
      reset_counter_for_invocation_event(method);
    } else {
      reset_counter_for_back_branch_event(method);
    }
    return NULL;
  }

  if (bci == InvocationEntryBci) {
    // when code cache is full, compilation gets switched off, UseCompiler
    // is set to false
    if (!method->has_compiled_code() && UseCompiler) {
      method_invocation_event(method, THREAD);
    } else {
      // Force counter overflow on method entry, even if no compilation
      // happened.  (The method_invocation_event call does this also.)
      reset_counter_for_invocation_event(method);
    }
    // compilation at an invocation overflow no longer goes and retries test for
    // compiled method. We always run the loser of the race as interpreted.
    // so return NULL
    return NULL;
  } else {
    // counter overflow in a loop => try to do on-stack-replacement
    nmethod* osr_nm = method->lookup_osr_nmethod_for(bci, CompLevel_highest_tier, true);
    NOT_PRODUCT(trace_osr_request(method, osr_nm, bci));
    // when code cache is full, we should not compile any more...
    if (osr_nm == NULL && UseCompiler) {
      method_back_branch_event(method, bci, THREAD);
      osr_nm = method->lookup_osr_nmethod_for(bci, CompLevel_highest_tier, true);
    }
    if (osr_nm == NULL) {
      reset_counter_for_back_branch_event(method);
      return NULL;
    }
    return osr_nm;
  }
  return NULL;
}

#ifndef PRODUCT
void SimpleCompPolicy::trace_frequency_counter_overflow(const methodHandle& m, int branch_bci, int bci) {
  if (TraceInvocationCounterOverflow) {
    MethodCounters* mcs = m->method_counters();
    assert(mcs != NULL, "MethodCounters cannot be NULL for profiling");
    InvocationCounter* ic = mcs->invocation_counter();
    InvocationCounter* bc = mcs->backedge_counter();
    ResourceMark rm;
    if (bci == InvocationEntryBci) {
      tty->print("comp-policy cntr ovfl @ %d in entry of ", bci);
    } else {
      tty->print("comp-policy cntr ovfl @ %d in loop of ", bci);
    }
    m->print_value();
    tty->cr();
    ic->print();
    bc->print();
    if (ProfileInterpreter) {
      if (bci != InvocationEntryBci) {
        MethodData* mdo = m->method_data();
        if (mdo != NULL) {
          ProfileData *pd = mdo->bci_to_data(branch_bci);
          if (pd == NULL) {
            tty->print_cr("back branch count = N/A (missing ProfileData)");
          } else {
            tty->print_cr("back branch count = %d", pd->as_JumpData()->taken());
          }
        }
      }
    }
  }
}

void SimpleCompPolicy::trace_osr_request(const methodHandle& method, nmethod* osr, int bci) {
  if (TraceOnStackReplacement) {
    ResourceMark rm;
    tty->print(osr != NULL ? "Reused OSR entry for " : "Requesting OSR entry for ");
    method->print_short_name(tty);
    tty->print_cr(" at bci %d", bci);
  }
}
#endif // !PRODUCT

void SimpleCompPolicy::method_invocation_event(const methodHandle& m, TRAPS) {
  const int comp_level = CompLevel_highest_tier;
  const int hot_count = m->invocation_count();
  reset_counter_for_invocation_event(m);

  if (is_compilation_enabled() && can_be_compiled(m, comp_level)) {
    CompiledMethod* nm = m->code();
    if (nm == NULL ) {
      CompileBroker::compile_method(m, InvocationEntryBci, comp_level, m, hot_count, CompileTask::Reason_InvocationCount, THREAD);
    }
  }
}

void SimpleCompPolicy::method_back_branch_event(const methodHandle& m, int bci, TRAPS) {
  const int comp_level = CompLevel_highest_tier;
  const int hot_count = m->backedge_count();

  if (is_compilation_enabled() && can_be_osr_compiled(m, comp_level)) {
    CompileBroker::compile_method(m, bci, comp_level, m, hot_count, CompileTask::Reason_BackedgeCount, THREAD);
    NOT_PRODUCT(trace_osr_completion(m->lookup_osr_nmethod_for(bci, comp_level, true));)
  }
}

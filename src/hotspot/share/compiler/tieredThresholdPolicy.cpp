/*
 * Copyright (c) 2010, 2020, Oracle and/or its affiliates. All rights reserved.
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
#include "compiler/compileBroker.hpp"
#include "compiler/compilerOracle.hpp"
#include "compiler/tieredThresholdPolicy.hpp"
#include "memory/resourceArea.hpp"
#include "prims/jvmtiExport.hpp"
#include "runtime/arguments.hpp"
#include "runtime/frame.inline.hpp"
#include "runtime/globals_extension.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/safepointVerifiers.hpp"
#include "code/scopeDesc.hpp"
#include "oops/method.inline.hpp"
#if INCLUDE_JVMCI
#include "jvmci/jvmci.hpp"
#endif

#ifdef TIERED

#include "c1/c1_Compiler.hpp"
#include "opto/c2compiler.hpp"

bool TieredThresholdPolicy::call_predicate_helper(const methodHandle& method, CompLevel cur_level, int i, int b, double scale) {
  double threshold_scaling;
  if (CompilerOracle::has_option_value(method, CompileCommand::CompileThresholdScaling, threshold_scaling)) {
    scale *= threshold_scaling;
  }
  switch(cur_level) {
  case CompLevel_aot:
    if (CompilationModeFlag::disable_intermediate()) {
      return (i >= Tier0AOTInvocationThreshold * scale) ||
             (i >= Tier0AOTMinInvocationThreshold * scale && i + b >= Tier0AOTCompileThreshold * scale);
    } else {
      return (i >= Tier3AOTInvocationThreshold * scale) ||
             (i >= Tier3AOTMinInvocationThreshold * scale && i + b >= Tier3AOTCompileThreshold * scale);
    }
  case CompLevel_none:
    if (CompilationModeFlag::disable_intermediate()) {
      return (i >= Tier40InvocationThreshold * scale) ||
             (i >= Tier40MinInvocationThreshold * scale && i + b >= Tier40CompileThreshold * scale);
    }
    // Fall through
  case CompLevel_limited_profile:
    return (i >= Tier3InvocationThreshold * scale) ||
           (i >= Tier3MinInvocationThreshold * scale && i + b >= Tier3CompileThreshold * scale);
  case CompLevel_full_profile:
   return (i >= Tier4InvocationThreshold * scale) ||
          (i >= Tier4MinInvocationThreshold * scale && i + b >= Tier4CompileThreshold * scale);
  default:
   return true;
  }
}

bool TieredThresholdPolicy::loop_predicate_helper(const methodHandle& method, CompLevel cur_level, int i, int b, double scale) {
  double threshold_scaling;
  if (CompilerOracle::has_option_value(method, CompileCommand::CompileThresholdScaling, threshold_scaling)) {
    scale *= threshold_scaling;
  }
  switch(cur_level) {
  case CompLevel_aot:
    if (CompilationModeFlag::disable_intermediate()) {
      return b >= Tier0AOTBackEdgeThreshold * scale;
    } else {
      return b >= Tier3AOTBackEdgeThreshold * scale;
    }
  case CompLevel_none:
    if (CompilationModeFlag::disable_intermediate()) {
      return b >= Tier40BackEdgeThreshold * scale;
    }
    // Fall through
  case CompLevel_limited_profile:
    return b >= Tier3BackEdgeThreshold * scale;
  case CompLevel_full_profile:
    return b >= Tier4BackEdgeThreshold * scale;
  default:
    return true;
  }
}

// Simple methods are as good being compiled with C1 as C2.
// Determine if a given method is such a case.
bool TieredThresholdPolicy::is_trivial(Method* method) {
  if (method->is_accessor() ||
      method->is_constant_getter()) {
    return true;
  }
  return false;
}

bool TieredThresholdPolicy::force_comp_at_level_simple(const methodHandle& method) {
  if (CompilationModeFlag::quick_internal()) {
#if INCLUDE_JVMCI
    if (UseJVMCICompiler) {
      AbstractCompiler* comp = CompileBroker::compiler(CompLevel_full_optimization);
      if (comp != NULL && comp->is_jvmci() && ((JVMCICompiler*) comp)->force_comp_at_level_simple(method)) {
        return true;
      }
    }
#endif
  }
  return false;
}

CompLevel TieredThresholdPolicy::comp_level(Method* method) {
  CompiledMethod *nm = method->code();
  if (nm != NULL && nm->is_in_use()) {
    return (CompLevel)nm->comp_level();
  }
  return CompLevel_none;
}

void TieredThresholdPolicy::print_counters(const char* prefix, Method* m) {
  int invocation_count = m->invocation_count();
  int backedge_count = m->backedge_count();
  MethodData* mdh = m->method_data();
  int mdo_invocations = 0, mdo_backedges = 0;
  int mdo_invocations_start = 0, mdo_backedges_start = 0;
  if (mdh != NULL) {
    mdo_invocations = mdh->invocation_count();
    mdo_backedges = mdh->backedge_count();
    mdo_invocations_start = mdh->invocation_count_start();
    mdo_backedges_start = mdh->backedge_count_start();
  }
  tty->print(" %stotal=%d,%d %smdo=%d(%d),%d(%d)", prefix,
      invocation_count, backedge_count, prefix,
      mdo_invocations, mdo_invocations_start,
      mdo_backedges, mdo_backedges_start);
  tty->print(" %smax levels=%d,%d", prefix,
      m->highest_comp_level(), m->highest_osr_comp_level());
}

// Print an event.
void TieredThresholdPolicy::print_event(EventType type, Method* m, Method* im,
                                        int bci, CompLevel level) {
  bool inlinee_event = m != im;

  ttyLocker tty_lock;
  tty->print("%lf: [", os::elapsedTime());

  switch(type) {
  case CALL:
    tty->print("call");
    break;
  case LOOP:
    tty->print("loop");
    break;
  case COMPILE:
    tty->print("compile");
    break;
  case REMOVE_FROM_QUEUE:
    tty->print("remove-from-queue");
    break;
  case UPDATE_IN_QUEUE:
    tty->print("update-in-queue");
    break;
  case REPROFILE:
    tty->print("reprofile");
    break;
  case MAKE_NOT_ENTRANT:
    tty->print("make-not-entrant");
    break;
  default:
    tty->print("unknown");
  }

  tty->print(" level=%d ", level);

  ResourceMark rm;
  char *method_name = m->name_and_sig_as_C_string();
  tty->print("[%s", method_name);
  if (inlinee_event) {
    char *inlinee_name = im->name_and_sig_as_C_string();
    tty->print(" [%s]] ", inlinee_name);
  }
  else tty->print("] ");
  tty->print("@%d queues=%d,%d", bci, CompileBroker::queue_size(CompLevel_full_profile),
                                      CompileBroker::queue_size(CompLevel_full_optimization));

  tty->print(" rate=");
  if (m->prev_time() == 0) tty->print("n/a");
  else tty->print("%f", m->rate());

  tty->print(" k=%.2lf,%.2lf", threshold_scale(CompLevel_full_profile, Tier3LoadFeedback),
                               threshold_scale(CompLevel_full_optimization, Tier4LoadFeedback));

  if (type != COMPILE) {
    print_counters("", m);
    if (inlinee_event) {
      print_counters("inlinee ", im);
    }
    tty->print(" compilable=");
    bool need_comma = false;
    if (!m->is_not_compilable(CompLevel_full_profile)) {
      tty->print("c1");
      need_comma = true;
    }
    if (!m->is_not_osr_compilable(CompLevel_full_profile)) {
      if (need_comma) tty->print(",");
      tty->print("c1-osr");
      need_comma = true;
    }
    if (!m->is_not_compilable(CompLevel_full_optimization)) {
      if (need_comma) tty->print(",");
      tty->print("c2");
      need_comma = true;
    }
    if (!m->is_not_osr_compilable(CompLevel_full_optimization)) {
      if (need_comma) tty->print(",");
      tty->print("c2-osr");
    }
    tty->print(" status=");
    if (m->queued_for_compilation()) {
      tty->print("in-queue");
    } else tty->print("idle");
  }
  tty->print_cr("]");
}


void TieredThresholdPolicy::initialize() {
  int count = CICompilerCount;
  bool c1_only = TieredStopAtLevel < CompLevel_full_optimization || CompilationModeFlag::quick_only();
  bool c2_only = CompilationModeFlag::high_only();
#ifdef _LP64
  // Turn on ergonomic compiler count selection
  if (FLAG_IS_DEFAULT(CICompilerCountPerCPU) && FLAG_IS_DEFAULT(CICompilerCount)) {
    FLAG_SET_DEFAULT(CICompilerCountPerCPU, true);
  }
  if (CICompilerCountPerCPU) {
    // Simple log n seems to grow too slowly for tiered, try something faster: log n * log log n
    int log_cpu = log2_int(os::active_processor_count());
    int loglog_cpu = log2_int(MAX2(log_cpu, 1));
    count = MAX2(log_cpu * loglog_cpu * 3 / 2, 2);
    // Make sure there is enough space in the code cache to hold all the compiler buffers
    size_t c1_size = Compiler::code_buffer_size();
    size_t c2_size = C2Compiler::initial_code_buffer_size();
    size_t buffer_size = c1_only ? c1_size : (c1_size/3 + 2*c2_size/3);
    int max_count = (ReservedCodeCacheSize - (CodeCacheMinimumUseSpace DEBUG_ONLY(* 3))) / (int)buffer_size;
    if (count > max_count) {
      // Lower the compiler count such that all buffers fit into the code cache
      count = MAX2(max_count, c1_only ? 1 : 2);
    }
    FLAG_SET_ERGO(CICompilerCount, count);
  }
#else
  // On 32-bit systems, the number of compiler threads is limited to 3.
  // On these systems, the virtual address space available to the JVM
  // is usually limited to 2-4 GB (the exact value depends on the platform).
  // As the compilers (especially C2) can consume a large amount of
  // memory, scaling the number of compiler threads with the number of
  // available cores can result in the exhaustion of the address space
  /// available to the VM and thus cause the VM to crash.
  if (FLAG_IS_DEFAULT(CICompilerCount)) {
    count = 3;
    FLAG_SET_ERGO(CICompilerCount, count);
  }
#endif

  if (c1_only) {
    // No C2 compiler thread required
    set_c1_count(count);
  } else if (c2_only) {
    set_c2_count(count);
  } else {
    set_c1_count(MAX2(count / 3, 1));
    set_c2_count(MAX2(count - c1_count(), 1));
  }
  assert(count == c1_count() + c2_count(), "inconsistent compiler thread count");

  // Some inlining tuning
#ifdef X86
  if (FLAG_IS_DEFAULT(InlineSmallCode)) {
    FLAG_SET_DEFAULT(InlineSmallCode, 2500);
  }
#endif

#if defined AARCH64
  if (FLAG_IS_DEFAULT(InlineSmallCode)) {
    FLAG_SET_DEFAULT(InlineSmallCode, 2500);
  }
#endif

  set_increase_threshold_at_ratio();
  set_start_time(nanos_to_millis(os::javaTimeNanos()));
}


#ifdef ASSERT
bool TieredThresholdPolicy::verify_level(CompLevel level) {
  // AOT and interpreter levels are always valid.
  if (level == CompLevel_aot || level == CompLevel_none) {
    return true;
  }
  if (CompilationModeFlag::normal()) {
    return true;
  } else if (CompilationModeFlag::quick_only()) {
    return level == CompLevel_simple;
  } else if (CompilationModeFlag::high_only()) {
    return level == CompLevel_full_optimization;
  } else if (CompilationModeFlag::high_only_quick_internal()) {
    return level == CompLevel_full_optimization || level == CompLevel_simple;
  }
  return false;
}
#endif


CompLevel TieredThresholdPolicy::limit_level(CompLevel level) {
  if (CompilationModeFlag::quick_only()) {
    level = MIN2(level, CompLevel_simple);
  }
  assert(verify_level(level), "Invalid compilation level %d", level);
  if (level <= TieredStopAtLevel) {
    return level;
  }
  // Some compilation levels are not valid depending on a compilation mode:
  // a) quick_only - levels 2,3,4 are invalid; levels -1,0,1 are valid;
  // b) high_only - levels 1,2,3 are invalid; levels -1,0,4 are valid;
  // c) high_only_quick_internal - levels 2,3 are invalid; levels -1,0,1,4 are valid.
  // The invalid levels are actually sequential so a single comparison is sufficient.
  // Down here we already have (level > TieredStopAtLevel), which also implies that
  // (TieredStopAtLevel < Highest Possible Level), so we need to return a level that is:
  // a) a max level that is strictly less than the highest for a given compilation mode
  // b) less or equal to TieredStopAtLevel
  if (CompilationModeFlag::normal() || CompilationModeFlag::quick_only()) {
    return (CompLevel)TieredStopAtLevel;
  }

  if (CompilationModeFlag::high_only() || CompilationModeFlag::high_only_quick_internal()) {
    return MIN2(CompLevel_none, (CompLevel)TieredStopAtLevel);
  }

  ShouldNotReachHere();
  return CompLevel_any;
}

CompLevel TieredThresholdPolicy::initial_compile_level_helper(const methodHandle& method) {
  if (CompilationModeFlag::normal()) {
    return CompLevel_full_profile;
  } else if (CompilationModeFlag::quick_only()) {
    return CompLevel_simple;
  } else if (CompilationModeFlag::high_only()) {
    return CompLevel_full_optimization;
  } else if (CompilationModeFlag::high_only_quick_internal()) {
    if (force_comp_at_level_simple(method)) {
      return CompLevel_simple;
    } else {
      return CompLevel_full_optimization;
    }
  }
  ShouldNotReachHere();
  return CompLevel_any;
}

CompLevel TieredThresholdPolicy::initial_compile_level(const methodHandle& method) {
  return limit_level(initial_compile_level_helper(method));
}

// Set carry flags on the counters if necessary
void TieredThresholdPolicy::handle_counter_overflow(Method* method) {
  MethodCounters *mcs = method->method_counters();
  if (mcs != NULL) {
    mcs->invocation_counter()->set_carry_on_overflow();
    mcs->backedge_counter()->set_carry_on_overflow();
  }
  MethodData* mdo = method->method_data();
  if (mdo != NULL) {
    mdo->invocation_counter()->set_carry_on_overflow();
    mdo->backedge_counter()->set_carry_on_overflow();
  }
}

// Called with the queue locked and with at least one element
CompileTask* TieredThresholdPolicy::select_task(CompileQueue* compile_queue) {
  CompileTask *max_blocking_task = NULL;
  CompileTask *max_task = NULL;
  Method* max_method = NULL;
  jlong t = nanos_to_millis(os::javaTimeNanos());
  // Iterate through the queue and find a method with a maximum rate.
  for (CompileTask* task = compile_queue->first(); task != NULL;) {
    CompileTask* next_task = task->next();
    Method* method = task->method();
    // If a method was unloaded or has been stale for some time, remove it from the queue.
    // Blocking tasks and tasks submitted from whitebox API don't become stale
    if (task->is_unloaded() || (task->can_become_stale() && is_stale(t, TieredCompileTaskTimeout, method) && !is_old(method))) {
      if (!task->is_unloaded()) {
        if (PrintTieredEvents) {
          print_event(REMOVE_FROM_QUEUE, method, method, task->osr_bci(), (CompLevel) task->comp_level());
        }
        method->clear_queued_for_compilation();
      }
      compile_queue->remove_and_mark_stale(task);
      task = next_task;
      continue;
    }
    update_rate(t, method);
    if (max_task == NULL || compare_methods(method, max_method)) {
      // Select a method with the highest rate
      max_task = task;
      max_method = method;
    }

    if (task->is_blocking()) {
      if (max_blocking_task == NULL || compare_methods(method, max_blocking_task->method())) {
        max_blocking_task = task;
      }
    }

    task = next_task;
  }

  if (max_blocking_task != NULL) {
    // In blocking compilation mode, the CompileBroker will make
    // compilations submitted by a JVMCI compiler thread non-blocking. These
    // compilations should be scheduled after all blocking compilations
    // to service non-compiler related compilations sooner and reduce the
    // chance of such compilations timing out.
    max_task = max_blocking_task;
    max_method = max_task->method();
  }

  methodHandle max_method_h(Thread::current(), max_method);

  if (max_task != NULL && max_task->comp_level() == CompLevel_full_profile &&
      TieredStopAtLevel > CompLevel_full_profile &&
      max_method != NULL && is_method_profiled(max_method_h)) {
    max_task->set_comp_level(CompLevel_limited_profile);

    if (CompileBroker::compilation_is_complete(max_method_h, max_task->osr_bci(), CompLevel_limited_profile)) {
      if (PrintTieredEvents) {
        print_event(REMOVE_FROM_QUEUE, max_method, max_method, max_task->osr_bci(), (CompLevel)max_task->comp_level());
      }
      compile_queue->remove_and_mark_stale(max_task);
      max_method->clear_queued_for_compilation();
      return NULL;
    }

    if (PrintTieredEvents) {
      print_event(UPDATE_IN_QUEUE, max_method, max_method, max_task->osr_bci(), (CompLevel)max_task->comp_level());
    }
  }

  return max_task;
}

void TieredThresholdPolicy::reprofile(ScopeDesc* trap_scope, bool is_osr) {
  for (ScopeDesc* sd = trap_scope;; sd = sd->sender()) {
    if (PrintTieredEvents) {
      print_event(REPROFILE, sd->method(), sd->method(), InvocationEntryBci, CompLevel_none);
    }
    MethodData* mdo = sd->method()->method_data();
    if (mdo != NULL) {
      mdo->reset_start_counters();
    }
    if (sd->is_top()) break;
  }
}

nmethod* TieredThresholdPolicy::event(const methodHandle& method, const methodHandle& inlinee,
                                      int branch_bci, int bci, CompLevel comp_level, CompiledMethod* nm, TRAPS) {
  if (comp_level == CompLevel_none &&
      JvmtiExport::can_post_interpreter_events() &&
      THREAD->as_Java_thread()->is_interp_only_mode()) {
    return NULL;
  }
  if (ReplayCompiles) {
    // Don't trigger other compiles in testing mode
    return NULL;
  }

  handle_counter_overflow(method());
  if (method() != inlinee()) {
    handle_counter_overflow(inlinee());
  }

  if (PrintTieredEvents) {
    print_event(bci == InvocationEntryBci ? CALL : LOOP, method(), inlinee(), bci, comp_level);
  }

  if (bci == InvocationEntryBci) {
    method_invocation_event(method, inlinee, comp_level, nm, THREAD);
  } else {
    // method == inlinee if the event originated in the main method
    method_back_branch_event(method, inlinee, bci, comp_level, nm, THREAD);
    // Check if event led to a higher level OSR compilation
    CompLevel expected_comp_level = MIN2(CompLevel_full_optimization, static_cast<CompLevel>(comp_level + 1));
    if (!CompilationModeFlag::disable_intermediate() && inlinee->is_not_osr_compilable(expected_comp_level)) {
      // It's not possble to reach the expected level so fall back to simple.
      expected_comp_level = CompLevel_simple;
    }
    CompLevel max_osr_level = static_cast<CompLevel>(inlinee->highest_osr_comp_level());
    if (max_osr_level >= expected_comp_level) { // fast check to avoid locking in a typical scenario
      nmethod* osr_nm = inlinee->lookup_osr_nmethod_for(bci, expected_comp_level, false);
      assert(osr_nm == NULL || osr_nm->comp_level() >= expected_comp_level, "lookup_osr_nmethod_for is broken");
      if (osr_nm != NULL && osr_nm->comp_level() != comp_level) {
        // Perform OSR with new nmethod
        return osr_nm;
      }
    }
  }
  return NULL;
}

// Check if the method can be compiled, change level if necessary
void TieredThresholdPolicy::compile(const methodHandle& mh, int bci, CompLevel level, TRAPS) {
  assert(verify_level(level) && level <= TieredStopAtLevel, "Invalid compilation level %d", level);

  if (level == CompLevel_none) {
    if (mh->has_compiled_code()) {
      // Happens when we switch from AOT to interpreter to profile.
      MutexLocker ml(Compile_lock);
      NoSafepointVerifier nsv;
      if (mh->has_compiled_code()) {
        mh->code()->make_not_used();
      }
      // Deoptimize immediately (we don't have to wait for a compile).
      JavaThread* jt = THREAD->as_Java_thread();
      RegisterMap map(jt, false);
      frame fr = jt->last_frame().sender(&map);
      Deoptimization::deoptimize_frame(jt, fr.id());
    }
    return;
  }
  if (level == CompLevel_aot) {
    if (mh->has_aot_code()) {
      if (PrintTieredEvents) {
        print_event(COMPILE, mh(), mh(), bci, level);
      }
      MutexLocker ml(Compile_lock);
      NoSafepointVerifier nsv;
      if (mh->has_aot_code() && mh->code() != mh->aot_code()) {
        mh->aot_code()->make_entrant();
        if (mh->has_compiled_code()) {
          mh->code()->make_not_entrant();
        }
        MutexLocker pl(CompiledMethod_lock, Mutex::_no_safepoint_check_flag);
        Method::set_code(mh, mh->aot_code());
      }
    }
    return;
  }

  if (!CompilationModeFlag::disable_intermediate()) {
    // Check if the method can be compiled. If it cannot be compiled with C1, continue profiling
    // in the interpreter and then compile with C2 (the transition function will request that,
    // see common() ). If the method cannot be compiled with C2 but still can with C1, compile it with
    // pure C1.
    if ((bci == InvocationEntryBci && !can_be_compiled(mh, level))) {
      if (level == CompLevel_full_optimization && can_be_compiled(mh, CompLevel_simple)) {
        compile(mh, bci, CompLevel_simple, THREAD);
      }
      return;
    }
    if ((bci != InvocationEntryBci && !can_be_osr_compiled(mh, level))) {
      if (level == CompLevel_full_optimization && can_be_osr_compiled(mh, CompLevel_simple)) {
        nmethod* osr_nm = mh->lookup_osr_nmethod_for(bci, CompLevel_simple, false);
        if (osr_nm != NULL && osr_nm->comp_level() > CompLevel_simple) {
          // Invalidate the existing OSR nmethod so that a compile at CompLevel_simple is permitted.
          osr_nm->make_not_entrant();
        }
        compile(mh, bci, CompLevel_simple, THREAD);
      }
      return;
    }
  }
  if (bci != InvocationEntryBci && mh->is_not_osr_compilable(level)) {
    return;
  }
  if (!CompileBroker::compilation_is_in_queue(mh)) {
    if (PrintTieredEvents) {
      print_event(COMPILE, mh(), mh(), bci, level);
    }
    int hot_count = (bci == InvocationEntryBci) ? mh->invocation_count() : mh->backedge_count();
    update_rate(nanos_to_millis(os::javaTimeNanos()), mh());
    CompileBroker::compile_method(mh, bci, level, mh, hot_count, CompileTask::Reason_Tiered, THREAD);
  }
}

// update_rate() is called from select_task() while holding a compile queue lock.
void TieredThresholdPolicy::update_rate(jlong t, Method* m) {
  // Skip update if counters are absent.
  // Can't allocate them since we are holding compile queue lock.
  if (m->method_counters() == NULL)  return;

  if (is_old(m)) {
    // We don't remove old methods from the queue,
    // so we can just zero the rate.
    m->set_rate(0);
    return;
  }

  // We don't update the rate if we've just came out of a safepoint.
  // delta_s is the time since last safepoint in milliseconds.
  jlong delta_s = t - SafepointTracing::end_of_last_safepoint_ms();
  jlong delta_t = t - (m->prev_time() != 0 ? m->prev_time() : start_time()); // milliseconds since the last measurement
  // How many events were there since the last time?
  int event_count = m->invocation_count() + m->backedge_count();
  int delta_e = event_count - m->prev_event_count();

  // We should be running for at least 1ms.
  if (delta_s >= TieredRateUpdateMinTime) {
    // And we must've taken the previous point at least 1ms before.
    if (delta_t >= TieredRateUpdateMinTime && delta_e > 0) {
      m->set_prev_time(t);
      m->set_prev_event_count(event_count);
      m->set_rate((float)delta_e / (float)delta_t); // Rate is events per millisecond
    } else {
      if (delta_t > TieredRateUpdateMaxTime && delta_e == 0) {
        // If nothing happened for 25ms, zero the rate. Don't modify prev values.
        m->set_rate(0);
      }
    }
  }
}

// Check if this method has been stale for a given number of milliseconds.
// See select_task().
bool TieredThresholdPolicy::is_stale(jlong t, jlong timeout, Method* m) {
  jlong delta_s = t - SafepointTracing::end_of_last_safepoint_ms();
  jlong delta_t = t - m->prev_time();
  if (delta_t > timeout && delta_s > timeout) {
    int event_count = m->invocation_count() + m->backedge_count();
    int delta_e = event_count - m->prev_event_count();
    // Return true if there were no events.
    return delta_e == 0;
  }
  return false;
}

// We don't remove old methods from the compile queue even if they have
// very low activity. See select_task().
bool TieredThresholdPolicy::is_old(Method* method) {
  return method->invocation_count() > 50000 || method->backedge_count() > 500000;
}

double TieredThresholdPolicy::weight(Method* method) {
  return (double)(method->rate() + 1) *
    (method->invocation_count() + 1) * (method->backedge_count() + 1);
}

// Apply heuristics and return true if x should be compiled before y
bool TieredThresholdPolicy::compare_methods(Method* x, Method* y) {
  if (x->highest_comp_level() > y->highest_comp_level()) {
    // recompilation after deopt
    return true;
  } else
    if (x->highest_comp_level() == y->highest_comp_level()) {
      if (weight(x) > weight(y)) {
        return true;
      }
    }
  return false;
}

// Is method profiled enough?
bool TieredThresholdPolicy::is_method_profiled(const methodHandle& method) {
  MethodData* mdo = method->method_data();
  if (mdo != NULL) {
    int i = mdo->invocation_count_delta();
    int b = mdo->backedge_count_delta();
    return call_predicate_helper(method, CompilationModeFlag::disable_intermediate() ? CompLevel_none : CompLevel_full_profile, i, b, 1);
  }
  return false;
}

double TieredThresholdPolicy::threshold_scale(CompLevel level, int feedback_k) {
  int comp_count = compiler_count(level);
  if (comp_count > 0) {
    double queue_size = CompileBroker::queue_size(level);
    double k = queue_size / (feedback_k * comp_count) + 1;

    // Increase C1 compile threshold when the code cache is filled more
    // than specified by IncreaseFirstTierCompileThresholdAt percentage.
    // The main intention is to keep enough free space for C2 compiled code
    // to achieve peak performance if the code cache is under stress.
    if (!CompilationModeFlag::disable_intermediate() && TieredStopAtLevel == CompLevel_full_optimization && level != CompLevel_full_optimization)  {
      double current_reverse_free_ratio = CodeCache::reverse_free_ratio(CodeCache::get_code_blob_type(level));
      if (current_reverse_free_ratio > _increase_threshold_at_ratio) {
        k *= exp(current_reverse_free_ratio - _increase_threshold_at_ratio);
      }
    }
    return k;
  }
  return 1;
}

// Call and loop predicates determine whether a transition to a higher
// compilation level should be performed (pointers to predicate functions
// are passed to common()).
// Tier?LoadFeedback is basically a coefficient that determines of
// how many methods per compiler thread can be in the queue before
// the threshold values double.
bool TieredThresholdPolicy::loop_predicate(int i, int b, CompLevel cur_level, const methodHandle& method) {
  double k = 1;
  switch(cur_level) {
  case CompLevel_aot: {
    k = CompilationModeFlag::disable_intermediate() ? 1 : threshold_scale(CompLevel_full_profile, Tier3LoadFeedback);
    break;
  }
  case CompLevel_none: {
    if (CompilationModeFlag::disable_intermediate()) {
      k = threshold_scale(CompLevel_full_optimization, Tier4LoadFeedback);
      break;
    }
  }
  // Fall through
  case CompLevel_limited_profile: {
    k = threshold_scale(CompLevel_full_profile, Tier3LoadFeedback);
    break;
  }
  case CompLevel_full_profile: {
    k = threshold_scale(CompLevel_full_optimization, Tier4LoadFeedback);
    break;
  }
  default:
    return true;
  }
  return loop_predicate_helper(method, cur_level, i, b, k);
}

bool TieredThresholdPolicy::call_predicate(int i, int b, CompLevel cur_level, const methodHandle& method) {
  double k = 1;
  switch(cur_level) {
  case CompLevel_aot: {
    k = CompilationModeFlag::disable_intermediate() ? 1 : threshold_scale(CompLevel_full_profile, Tier3LoadFeedback);
    break;
  }
  case CompLevel_none: {
    if (CompilationModeFlag::disable_intermediate()) {
      k = threshold_scale(CompLevel_full_optimization, Tier4LoadFeedback);
      break;
    }
  }
  // Fall through
  case CompLevel_limited_profile: {
    k = threshold_scale(CompLevel_full_profile, Tier3LoadFeedback);
    break;
  }
  case CompLevel_full_profile: {
    k = threshold_scale(CompLevel_full_optimization, Tier4LoadFeedback);
    break;
  }
  default:
    return true;
  }
  return call_predicate_helper(method, cur_level, i, b, k);
}

// Determine is a method is mature.
bool TieredThresholdPolicy::is_mature(Method* method) {
  methodHandle mh(Thread::current(), method);
  if (is_trivial(method) || force_comp_at_level_simple(mh)) return true;
  MethodData* mdo = method->method_data();
  if (mdo != NULL) {
    int i = mdo->invocation_count();
    int b = mdo->backedge_count();
    double k = ProfileMaturityPercentage / 100.0;
    CompLevel main_profile_level = CompilationModeFlag::disable_intermediate() ? CompLevel_none : CompLevel_full_profile;
    return call_predicate_helper(mh, main_profile_level, i, b, k) || loop_predicate_helper(mh, main_profile_level, i, b, k);
  }
  return false;
}

// If a method is old enough and is still in the interpreter we would want to
// start profiling without waiting for the compiled method to arrive.
// We also take the load on compilers into the account.
bool TieredThresholdPolicy::should_create_mdo(const methodHandle& method, CompLevel cur_level) {
  if (cur_level != CompLevel_none || force_comp_at_level_simple(method) || !ProfileInterpreter) {
    return false;
  }
  int i = method->invocation_count();
  int b = method->backedge_count();
  double k = Tier0ProfilingStartPercentage / 100.0;

  // If the top level compiler is not keeping up, delay profiling.
  if (CompileBroker::queue_size(CompLevel_full_optimization) <=  (CompilationModeFlag::disable_intermediate() ? Tier0Delay : Tier3DelayOn) * compiler_count(CompLevel_full_optimization)) {
    return call_predicate_helper(method, CompLevel_none, i, b, k) || loop_predicate_helper(method, CompLevel_none, i, b, k);
  }
  return false;
}

// Inlining control: if we're compiling a profiled method with C1 and the callee
// is known to have OSRed in a C2 version, don't inline it.
bool TieredThresholdPolicy::should_not_inline(ciEnv* env, ciMethod* callee) {
  CompLevel comp_level = (CompLevel)env->comp_level();
  if (comp_level == CompLevel_full_profile ||
      comp_level == CompLevel_limited_profile) {
    return callee->highest_osr_comp_level() == CompLevel_full_optimization;
  }
  return false;
}

// Create MDO if necessary.
void TieredThresholdPolicy::create_mdo(const methodHandle& mh, Thread* THREAD) {
  if (mh->is_native() ||
      mh->is_abstract() ||
      mh->is_accessor() ||
      mh->is_constant_getter()) {
    return;
  }
  if (mh->method_data() == NULL) {
    Method::build_interpreter_method_data(mh, CHECK_AND_CLEAR);
  }
  if (ProfileInterpreter) {
    MethodData* mdo = mh->method_data();
    if (mdo != NULL) {
      JavaThread* jt = THREAD->as_Java_thread();
      frame last_frame = jt->last_frame();
      if (last_frame.is_interpreted_frame() && mh == last_frame.interpreter_frame_method()) {
        int bci = last_frame.interpreter_frame_bci();
        address dp = mdo->bci_to_dp(bci);
        last_frame.interpreter_frame_set_mdp(dp);
      }
    }
  }
}


/*
 * Method states:
 *   0 - interpreter (CompLevel_none)
 *   1 - pure C1 (CompLevel_simple)
 *   2 - C1 with invocation and backedge counting (CompLevel_limited_profile)
 *   3 - C1 with full profiling (CompLevel_full_profile)
 *   4 - C2 or Graal (CompLevel_full_optimization)
 *
 * Common state transition patterns:
 * a. 0 -> 3 -> 4.
 *    The most common path. But note that even in this straightforward case
 *    profiling can start at level 0 and finish at level 3.
 *
 * b. 0 -> 2 -> 3 -> 4.
 *    This case occurs when the load on C2 is deemed too high. So, instead of transitioning
 *    into state 3 directly and over-profiling while a method is in the C2 queue we transition to
 *    level 2 and wait until the load on C2 decreases. This path is disabled for OSRs.
 *
 * c. 0 -> (3->2) -> 4.
 *    In this case we enqueue a method for compilation at level 3, but the C1 queue is long enough
 *    to enable the profiling to fully occur at level 0. In this case we change the compilation level
 *    of the method to 2 while the request is still in-queue, because it'll allow it to run much faster
 *    without full profiling while c2 is compiling.
 *
 * d. 0 -> 3 -> 1 or 0 -> 2 -> 1.
 *    After a method was once compiled with C1 it can be identified as trivial and be compiled to
 *    level 1. These transition can also occur if a method can't be compiled with C2 but can with C1.
 *
 * e. 0 -> 4.
 *    This can happen if a method fails C1 compilation (it will still be profiled in the interpreter)
 *    or because of a deopt that didn't require reprofiling (compilation won't happen in this case because
 *    the compiled version already exists).
 *
 * Note that since state 0 can be reached from any other state via deoptimization different loops
 * are possible.
 *
 */

// Common transition function. Given a predicate determines if a method should transition to another level.
CompLevel TieredThresholdPolicy::common(Predicate p, const methodHandle& method, CompLevel cur_level, bool disable_feedback) {
  CompLevel next_level = cur_level;
  int i = method->invocation_count();
  int b = method->backedge_count();

  if (force_comp_at_level_simple(method)) {
    next_level = CompLevel_simple;
  } else {
    if (!CompilationModeFlag::disable_intermediate() && is_trivial(method())) {
      next_level = CompLevel_simple;
    } else {
      switch(cur_level) {
      default: break;
      case CompLevel_aot:
        if (CompilationModeFlag::disable_intermediate()) {
          if (disable_feedback || (CompileBroker::queue_size(CompLevel_full_optimization) <=
                                   Tier0Delay * compiler_count(CompLevel_full_optimization) &&
                                  (this->*p)(i, b, cur_level, method))) {
            next_level = CompLevel_none;
          }
        } else {
          // If we were at full profile level, would we switch to full opt?
          if (common(p, method, CompLevel_full_profile, disable_feedback) == CompLevel_full_optimization) {
            next_level = CompLevel_full_optimization;
          } else if (disable_feedback || (CompileBroker::queue_size(CompLevel_full_optimization) <=
                                          Tier3DelayOff * compiler_count(CompLevel_full_optimization) &&
                                         (this->*p)(i, b, cur_level, method))) {
            next_level = CompLevel_full_profile;
          }
        }
        break;
      case CompLevel_none:
        if (CompilationModeFlag::disable_intermediate()) {
          MethodData* mdo = method->method_data();
          if (mdo != NULL) {
            // If mdo exists that means we are in a normal profiling mode.
            int mdo_i = mdo->invocation_count_delta();
            int mdo_b = mdo->backedge_count_delta();
            if ((this->*p)(mdo_i, mdo_b, cur_level, method)) {
              next_level = CompLevel_full_optimization;
            }
          }
        } else {
          // If we were at full profile level, would we switch to full opt?
          if (common(p, method, CompLevel_full_profile, disable_feedback) == CompLevel_full_optimization) {
            next_level = CompLevel_full_optimization;
          } else if ((this->*p)(i, b, cur_level, method)) {
  #if INCLUDE_JVMCI
            if (EnableJVMCI && UseJVMCICompiler) {
              // Since JVMCI takes a while to warm up, its queue inevitably backs up during
              // early VM execution. As of 2014-06-13, JVMCI's inliner assumes that the root
              // compilation method and all potential inlinees have mature profiles (which
              // includes type profiling). If it sees immature profiles, JVMCI's inliner
              // can perform pathologically bad (e.g., causing OutOfMemoryErrors due to
              // exploring/inlining too many graphs). Since a rewrite of the inliner is
              // in progress, we simply disable the dialing back heuristic for now and will
              // revisit this decision once the new inliner is completed.
              next_level = CompLevel_full_profile;
            } else
  #endif
            {
              // C1-generated fully profiled code is about 30% slower than the limited profile
              // code that has only invocation and backedge counters. The observation is that
              // if C2 queue is large enough we can spend too much time in the fully profiled code
              // while waiting for C2 to pick the method from the queue. To alleviate this problem
              // we introduce a feedback on the C2 queue size. If the C2 queue is sufficiently long
              // we choose to compile a limited profiled version and then recompile with full profiling
              // when the load on C2 goes down.
              if (!disable_feedback && CompileBroker::queue_size(CompLevel_full_optimization) >
                  Tier3DelayOn * compiler_count(CompLevel_full_optimization)) {
                next_level = CompLevel_limited_profile;
              } else {
                next_level = CompLevel_full_profile;
              }
            }
          }
        }
        break;
      case CompLevel_limited_profile:
        if (is_method_profiled(method)) {
          // Special case: we got here because this method was fully profiled in the interpreter.
          next_level = CompLevel_full_optimization;
        } else {
          MethodData* mdo = method->method_data();
          if (mdo != NULL) {
            if (mdo->would_profile()) {
              if (disable_feedback || (CompileBroker::queue_size(CompLevel_full_optimization) <=
                                       Tier3DelayOff * compiler_count(CompLevel_full_optimization) &&
                                       (this->*p)(i, b, cur_level, method))) {
                next_level = CompLevel_full_profile;
              }
            } else {
              next_level = CompLevel_full_optimization;
            }
          } else {
            // If there is no MDO we need to profile
            if (disable_feedback || (CompileBroker::queue_size(CompLevel_full_optimization) <=
                                     Tier3DelayOff * compiler_count(CompLevel_full_optimization) &&
                                     (this->*p)(i, b, cur_level, method))) {
              next_level = CompLevel_full_profile;
            }
          }
        }
        break;
      case CompLevel_full_profile:
        {
          MethodData* mdo = method->method_data();
          if (mdo != NULL) {
            if (mdo->would_profile()) {
              int mdo_i = mdo->invocation_count_delta();
              int mdo_b = mdo->backedge_count_delta();
              if ((this->*p)(mdo_i, mdo_b, cur_level, method)) {
                next_level = CompLevel_full_optimization;
              }
            } else {
              next_level = CompLevel_full_optimization;
            }
          }
        }
        break;
      }
    }
  }
  return limit_level(next_level);
}



// Determine if a method should be compiled with a normal entry point at a different level.
CompLevel TieredThresholdPolicy::call_event(const methodHandle& method, CompLevel cur_level, Thread* thread) {
  CompLevel osr_level = MIN2((CompLevel) method->highest_osr_comp_level(),
                             common(&TieredThresholdPolicy::loop_predicate, method, cur_level, true));
  CompLevel next_level = common(&TieredThresholdPolicy::call_predicate, method, cur_level);

  // If OSR method level is greater than the regular method level, the levels should be
  // equalized by raising the regular method level in order to avoid OSRs during each
  // invocation of the method.
  if (osr_level == CompLevel_full_optimization && cur_level == CompLevel_full_profile) {
    MethodData* mdo = method->method_data();
    guarantee(mdo != NULL, "MDO should not be NULL");
    if (mdo->invocation_count() >= 1) {
      next_level = CompLevel_full_optimization;
    }
  } else {
    next_level = MAX2(osr_level, next_level);
  }
  return next_level;
}

// Determine if we should do an OSR compilation of a given method.
CompLevel TieredThresholdPolicy::loop_event(const methodHandle& method, CompLevel cur_level, Thread* thread) {
  CompLevel next_level = common(&TieredThresholdPolicy::loop_predicate, method, cur_level, true);
  if (cur_level == CompLevel_none) {
    // If there is a live OSR method that means that we deopted to the interpreter
    // for the transition.
    CompLevel osr_level = MIN2((CompLevel)method->highest_osr_comp_level(), next_level);
    if (osr_level > CompLevel_none) {
      return osr_level;
    }
  }
  return next_level;
}

bool TieredThresholdPolicy::maybe_switch_to_aot(const methodHandle& mh, CompLevel cur_level, CompLevel next_level, Thread* thread) {
  if (UseAOT) {
    if (cur_level == CompLevel_full_profile || cur_level == CompLevel_none) {
      // If the current level is full profile or interpreter and we're switching to any other level,
      // activate the AOT code back first so that we won't waste time overprofiling.
      compile(mh, InvocationEntryBci, CompLevel_aot, thread);
      // Fall through for JIT compilation.
    }
    if (next_level == CompLevel_limited_profile && cur_level != CompLevel_aot && mh->has_aot_code()) {
      // If the next level is limited profile, use the aot code (if there is any),
      // since it's essentially the same thing.
      compile(mh, InvocationEntryBci, CompLevel_aot, thread);
      // Not need to JIT, we're done.
      return true;
    }
  }
  return false;
}


// Handle the invocation event.
void TieredThresholdPolicy::method_invocation_event(const methodHandle& mh, const methodHandle& imh,
                                                      CompLevel level, CompiledMethod* nm, TRAPS) {
  if (should_create_mdo(mh, level)) {
    create_mdo(mh, THREAD);
  }
  CompLevel next_level = call_event(mh, level, THREAD);
  if (next_level != level) {
    if (maybe_switch_to_aot(mh, level, next_level, THREAD)) {
      // No JITting necessary
      return;
    }
    if (is_compilation_enabled() && !CompileBroker::compilation_is_in_queue(mh)) {
      compile(mh, InvocationEntryBci, next_level, THREAD);
    }
  }
}

// Handle the back branch event. Notice that we can compile the method
// with a regular entry from here.
void TieredThresholdPolicy::method_back_branch_event(const methodHandle& mh, const methodHandle& imh,
                                                     int bci, CompLevel level, CompiledMethod* nm, TRAPS) {
  if (should_create_mdo(mh, level)) {
    create_mdo(mh, THREAD);
  }
  // Check if MDO should be created for the inlined method
  if (should_create_mdo(imh, level)) {
    create_mdo(imh, THREAD);
  }

  if (is_compilation_enabled()) {
    CompLevel next_osr_level = loop_event(imh, level, THREAD);
    CompLevel max_osr_level = (CompLevel)imh->highest_osr_comp_level();
    // At the very least compile the OSR version
    if (!CompileBroker::compilation_is_in_queue(imh) && (next_osr_level != level)) {
      compile(imh, bci, next_osr_level, CHECK);
    }

    // Use loop event as an opportunity to also check if there's been
    // enough calls.
    CompLevel cur_level, next_level;
    if (mh() != imh()) { // If there is an enclosing method
      if (level == CompLevel_aot) {
        // Recompile the enclosing method to prevent infinite OSRs. Stay at AOT level while it's compiling.
        if (max_osr_level != CompLevel_none && !CompileBroker::compilation_is_in_queue(mh)) {
          CompLevel enclosing_level = limit_level(CompLevel_full_profile);
          compile(mh, InvocationEntryBci, enclosing_level, THREAD);
        }
      } else {
        // Current loop event level is not AOT
        guarantee(nm != NULL, "Should have nmethod here");
        cur_level = comp_level(mh());
        next_level = call_event(mh, cur_level, THREAD);

        if (max_osr_level == CompLevel_full_optimization) {
          // The inlinee OSRed to full opt, we need to modify the enclosing method to avoid deopts
          bool make_not_entrant = false;
          if (nm->is_osr_method()) {
            // This is an osr method, just make it not entrant and recompile later if needed
            make_not_entrant = true;
          } else {
            if (next_level != CompLevel_full_optimization) {
              // next_level is not full opt, so we need to recompile the
              // enclosing method without the inlinee
              cur_level = CompLevel_none;
              make_not_entrant = true;
            }
          }
          if (make_not_entrant) {
            if (PrintTieredEvents) {
              int osr_bci = nm->is_osr_method() ? nm->osr_entry_bci() : InvocationEntryBci;
              print_event(MAKE_NOT_ENTRANT, mh(), mh(), osr_bci, level);
            }
            nm->make_not_entrant();
          }
        }
        // Fix up next_level if necessary to avoid deopts
        if (next_level == CompLevel_limited_profile && max_osr_level == CompLevel_full_profile) {
          next_level = CompLevel_full_profile;
        }
        if (cur_level != next_level) {
          if (!maybe_switch_to_aot(mh, cur_level, next_level, THREAD) && !CompileBroker::compilation_is_in_queue(mh)) {
            compile(mh, InvocationEntryBci, next_level, THREAD);
          }
        }
      }
    } else {
      cur_level = comp_level(mh());
      next_level = call_event(mh, cur_level, THREAD);
      if (next_level != cur_level) {
        if (!maybe_switch_to_aot(mh, cur_level, next_level, THREAD) && !CompileBroker::compilation_is_in_queue(mh)) {
          compile(mh, InvocationEntryBci, next_level, THREAD);
        }
      }
    }
  }
}

#endif

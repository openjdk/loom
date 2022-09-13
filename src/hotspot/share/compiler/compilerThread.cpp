/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "compiler/compileTask.hpp"
#include "compiler/compilerThread.hpp"
#include "runtime/javaThread.inline.hpp"

// Create a CompilerThread
CompilerThread::CompilerThread(CompileQueue* queue,
                               CompilerCounters* counters)
                               : JavaThread(&CompilerThread::thread_entry) {
  _env   = NULL;
  _log   = NULL;
  _task  = NULL;
  _queue = queue;
  _counters = counters;
  _buffer_blob = NULL;
  _compiler = NULL;

  // Compiler uses resource area for compilation, let's bias it to mtCompiler
  resource_area()->bias_to(mtCompiler);

#ifndef PRODUCT
  _ideal_graph_printer = NULL;
#endif
}

CompilerThread::~CompilerThread() {
  // Delete objects which were allocated on heap.
  delete _counters;
}

void CompilerThread::thread_entry(JavaThread* thread, TRAPS) {
  assert(thread->is_Compiler_thread(), "must be compiler thread");
  CompileBroker::compiler_thread_loop();
}

bool CompilerThread::can_call_java() const {
  return _compiler != NULL && _compiler->is_jvmci();
}

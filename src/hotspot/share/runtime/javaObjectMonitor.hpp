/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_RUNTIME_JAVAOBJECTMONITOR_HPP
#define SHARE_RUNTIME_JAVAOBJECTMONITOR_HPP

#include "memory/allStatic.hpp"

class ObjectMonitorMode : AllStatic {
  enum class Mode {
    LEGACY = -1,
    NATIVE =  0,
    HEAVY  =  1,
    FAST   =  2
  };
  static Mode _mode;
public:
  static bool initialize();
  static bool legacy() { return _mode == Mode::LEGACY; };

  static bool java()   { return _mode != Mode::LEGACY; };

  static bool native() { return _mode == Mode::NATIVE; };

  static bool java_only()   { return heavy() || fast(); };

  static bool heavy()  { return _mode == Mode::HEAVY; };
  static bool fast()   { return _mode == Mode::FAST; };

  static int  as_int()    { return (int)_mode; };
  static const char* as_string() {
    switch(_mode) {
    case Mode::LEGACY: return "legacy";
    case Mode::NATIVE: return "native";
    case Mode::HEAVY:  return "heavy";
    case Mode::FAST:   return "fast";
    }
    return "error: invalid mode";
  };
};

#endif // SHARE_RUNTIME_JAVAOBJECTMONITOR_HPP

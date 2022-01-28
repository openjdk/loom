/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_JFR_PERIODIC_JFRFINALIZERSTATISTICSEVENT_HPP
#define SHARE_JFR_PERIODIC_JFRFINALIZERSTATISTICSEVENT_HPP

#include "memory/allStatic.hpp"

class InstanceKlass;

class JfrFinalizerStatisticsEvent : AllStatic {
 public:
  static void send_unload_event(const InstanceKlass* ik) NOT_MANAGEMENT_RETURN;
  static void generate_events() NOT_MANAGEMENT_RETURN;
};

#endif // SHARE_JFR_PERIODIC_JFRFINALIZERSTATISTICSEVENT_HPP

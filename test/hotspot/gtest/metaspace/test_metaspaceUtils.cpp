/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020 SAP SE. All rights reserved.
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
#include "memory/metaspace.hpp"
#include "unittest.hpp"

TEST_VM(MetaspaceUtils, reserved) {
  size_t reserved = MetaspaceUtils::reserved_bytes();
  EXPECT_GT(reserved, 0UL);

  size_t reserved_metadata = MetaspaceUtils::reserved_bytes(Metaspace::NonClassType);
  EXPECT_GT(reserved_metadata, 0UL);
  EXPECT_LE(reserved_metadata, reserved);
}

TEST_VM(MetaspaceUtils, reserved_compressed_class_pointers) {
  if (!UseCompressedClassPointers) {
    return;
  }
  size_t reserved = MetaspaceUtils::reserved_bytes();
  EXPECT_GT(reserved, 0UL);

  size_t reserved_class = MetaspaceUtils::reserved_bytes(Metaspace::ClassType);
  EXPECT_GT(reserved_class, 0UL);
  EXPECT_LE(reserved_class, reserved);
}

TEST_VM(MetaspaceUtils, committed) {
  size_t committed = MetaspaceUtils::committed_bytes();
  EXPECT_GT(committed, 0UL);

  size_t reserved  = MetaspaceUtils::reserved_bytes();
  EXPECT_LE(committed, reserved);

  size_t committed_metadata = MetaspaceUtils::committed_bytes(Metaspace::NonClassType);
  EXPECT_GT(committed_metadata, 0UL);
  EXPECT_LE(committed_metadata, committed);
}

TEST_VM(MetaspaceUtils, committed_compressed_class_pointers) {
  if (!UseCompressedClassPointers) {
    return;
  }
  size_t committed = MetaspaceUtils::committed_bytes();
  EXPECT_GT(committed, 0UL);

  size_t committed_class = MetaspaceUtils::committed_bytes(Metaspace::ClassType);
  EXPECT_GT(committed_class, 0UL);
  EXPECT_LE(committed_class, committed);
}


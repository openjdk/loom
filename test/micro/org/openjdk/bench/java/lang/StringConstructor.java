/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
 */

package micro.org.openjdk.bench.java.lang;

import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Fork(5)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
public class StringConstructor {
  private byte[] array;

  @Setup
  public void setup() {
    array = "".getBytes(StandardCharsets.UTF_8);
  }

  @Benchmark
  public String newStringFromArray() {
    return new String(array);
  }

  @Benchmark
  public String newStringFromArrayWithCharset() {
    return new String(array, StandardCharsets.UTF_8);
  }

  @Benchmark
  public String newStringFromArrayWithCharsetName() throws Exception {
    return new String(array, StandardCharsets.UTF_8.name());
  }

  @Benchmark
  public String newStringFromRangedArray() {
    return new String(array, 0, 0);
  }

  @Benchmark
  public String newStringFromRangedArrayWithCharset() {
    return new String(array, 0, 0, StandardCharsets.UTF_8);
  }

  @Benchmark
  public String newStringFromRangedArrayWithCharsetName() throws Exception {
    return new String(array, 0, 0, StandardCharsets.UTF_8.name());
  }
}

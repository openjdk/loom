/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */

/*
 * @test
 * @enablePreview
 * @run testng TestLayoutPaths
 */

import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.MemorySession;
import java.lang.foreign.SequenceLayout;

import java.lang.foreign.ValueLayout;
import org.testng.SkipException;
import org.testng.annotations.*;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.PathElement.sequenceElement;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.testng.Assert.*;

public class TestLayoutPaths {

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadBitSelectFromSeq() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(groupElement("foo"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadByteSelectFromSeq() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.byteOffset(groupElement("foo"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadBitSelectFromStruct() {
        GroupLayout g = MemoryLayout.structLayout(JAVA_INT);
        g.bitOffset(sequenceElement());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadByteSelectFromStruct() {
        GroupLayout g = MemoryLayout.structLayout(JAVA_INT);
        g.byteOffset(sequenceElement());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadBitSelectFromValue() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(sequenceElement(), sequenceElement());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBadByteSelectFromValue() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.byteOffset(sequenceElement(), sequenceElement());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testUnknownBitStructField() {
        GroupLayout g = MemoryLayout.structLayout(JAVA_INT);
        g.bitOffset(groupElement("foo"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testUnknownByteStructField() {
        GroupLayout g = MemoryLayout.structLayout(JAVA_INT);
        g.byteOffset(groupElement("foo"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBitOutOfBoundsSeqIndex() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(sequenceElement(6));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testByteOutOfBoundsSeqIndex() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.byteOffset(sequenceElement(6));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNegativeSeqIndex() {
       sequenceElement(-2);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBitNegativeSeqIndex() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(sequenceElement(-2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testByteNegativeSeqIndex() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.byteOffset(sequenceElement(-2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testOutOfBoundsSeqRange() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(sequenceElement(6, 2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testNegativeSeqRange() {
        sequenceElement(-2, 2);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBitNegativeSeqRange() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.bitOffset(sequenceElement(-2, 2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testByteNegativeSeqRange() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, JAVA_INT);
        seq.byteOffset(sequenceElement(-2, 2));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testIncompleteAccess() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, MemoryLayout.structLayout(JAVA_INT));
        seq.varHandle(sequenceElement());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testBitOffsetHandleBadRange() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, MemoryLayout.structLayout(JAVA_INT));
        seq.bitOffsetHandle(sequenceElement(0, 1)); // ranges not accepted
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testByteOffsetHandleBadRange() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(5, MemoryLayout.structLayout(JAVA_INT));
        seq.byteOffsetHandle(sequenceElement(0, 1)); // ranges not accepted
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testBadMultiple() {
        GroupLayout g = MemoryLayout.structLayout(MemoryLayout.paddingLayout(3), JAVA_INT.withName("foo"));
        g.byteOffset(groupElement("foo"));
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testBadByteOffsetNoMultipleOf8() {
        MemoryLayout layout = MemoryLayout.structLayout(MemoryLayout.paddingLayout(7), JAVA_INT.withName("x"));
        layout.byteOffset(groupElement("x"));
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testBadByteOffsetHandleNoMultipleOf8() throws Throwable {
        MemoryLayout layout = MemoryLayout.structLayout(MemoryLayout.paddingLayout(7), JAVA_INT.withName("x"));
        MethodHandle handle = layout.byteOffsetHandle(groupElement("x"));
        handle.invoke();
    }

    @Test
    public void testBadContainerAlign() {
        GroupLayout g = MemoryLayout.structLayout(JAVA_INT.withBitAlignment(16).withName("foo")).withBitAlignment(8);
        try {
            g.bitOffset(groupElement("foo"));
            g.byteOffset(groupElement("foo"));
        } catch (Throwable ex) {
            throw new AssertionError(ex); // should be ok!
        }
        try {
            g.varHandle(groupElement("foo")); //ok
            assertTrue(false); //should fail!
        } catch (UnsupportedOperationException ex) {
            //ok
        } catch (Throwable ex) {
            throw new AssertionError(ex); //should fail!
        }
    }

    @Test
    public void testBadAlignOffset() {
        GroupLayout g = MemoryLayout.structLayout(MemoryLayout.paddingLayout(8), JAVA_INT.withBitAlignment(16).withName("foo"));
        try {
            g.bitOffset(groupElement("foo"));
            g.byteOffset(groupElement("foo"));
        } catch (Throwable ex) {
            throw new AssertionError(ex); // should be ok!
        }
        try {
            g.varHandle(groupElement("foo")); //ok
            assertTrue(false); //should fail!
        } catch (UnsupportedOperationException ex) {
            //ok
        } catch (Throwable ex) {
            throw new AssertionError(ex); //should fail!
        }
    }

    @Test
    public void testBadSequencePathInOffset() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(10, JAVA_INT);
        // bad path elements
        for (PathElement e : List.of( sequenceElement(), sequenceElement(0, 2) )) {
            try {
                seq.bitOffset(e);
                fail();
            } catch (IllegalArgumentException ex) {
                assertTrue(true);
            }
            try {
                seq.byteOffset(e);
                fail();
            } catch (IllegalArgumentException ex) {
                assertTrue(true);
            }
        }
    }

    @Test
    public void testBadSequencePathInSelect() {
        SequenceLayout seq = MemoryLayout.sequenceLayout(10, JAVA_INT);
        for (PathElement e : List.of( sequenceElement(0), sequenceElement(0, 2) )) {
            try {
                seq.select(e);
                fail();
            } catch (IllegalArgumentException ex) {
                assertTrue(true);
            }
        }
    }

    @Test
    public void testStructPaths() {
        long[] offsets = { 0, 8, 24, 56 };
        GroupLayout g = MemoryLayout.structLayout(
                ValueLayout.JAVA_BYTE.withName("1"),
                ValueLayout.JAVA_CHAR.withName("2"),
                ValueLayout.JAVA_FLOAT.withName("3"),
                ValueLayout.JAVA_LONG.withName("4")
        );

        // test select

        for (int i = 1 ; i <= 4 ; i++) {
            MemoryLayout selected = g.select(groupElement(String.valueOf(i)));
            assertTrue(selected == g.memberLayouts().get(i - 1));
        }

        // test offset

        for (int i = 1 ; i <= 4 ; i++) {
            long bitOffset = g.bitOffset(groupElement(String.valueOf(i)));
            assertEquals(offsets[i - 1], bitOffset);
            long byteOffset = g.byteOffset(groupElement(String.valueOf(i)));
            assertEquals((offsets[i - 1]) >>> 3, byteOffset);
        }
    }

    @Test
    public void testUnionPaths() {
        long[] offsets = { 0, 0, 0, 0 };
        GroupLayout g = MemoryLayout.unionLayout(
                ValueLayout.JAVA_BYTE.withName("1"),
                ValueLayout.JAVA_CHAR.withName("2"),
                ValueLayout.JAVA_FLOAT.withName("3"),
                ValueLayout.JAVA_LONG.withName("4")
        );

        // test select

        for (int i = 1 ; i <= 4 ; i++) {
            MemoryLayout selected = g.select(groupElement(String.valueOf(i)));
            assertTrue(selected == g.memberLayouts().get(i - 1));
        }

        // test offset

        for (int i = 1 ; i <= 4 ; i++) {
            long bitOffset = g.bitOffset(groupElement(String.valueOf(i)));
            assertEquals(offsets[i - 1], bitOffset);
            long byteOffset = g.byteOffset(groupElement(String.valueOf(i)));
            assertEquals((offsets[i - 1]) >>> 3, byteOffset);
        }
    }

    @Test
    public void testSequencePaths() {
        long[] offsets = { 0, 8, 16, 24 };
        SequenceLayout g = MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_BYTE);

        // test select

        MemoryLayout selected = g.select(sequenceElement());
        assertTrue(selected == ValueLayout.JAVA_BYTE);

        // test offset

        for (int i = 0 ; i < 4 ; i++) {
            long bitOffset = g.bitOffset(sequenceElement(i));
            assertEquals(offsets[i], bitOffset);
            long byteOffset = g.byteOffset(sequenceElement(i));
            assertEquals((offsets[i]) >>> 3, byteOffset);
        }
    }

    @Test(dataProvider = "testLayouts")
    public void testOffsetHandle(MemoryLayout layout, PathElement[] pathElements, long[] indexes,
                                 long expectedBitOffset) throws Throwable {
        MethodHandle bitOffsetHandle = layout.bitOffsetHandle(pathElements);
        bitOffsetHandle = bitOffsetHandle.asSpreader(long[].class, indexes.length);
        long actualBitOffset = (long) bitOffsetHandle.invokeExact(indexes);
        assertEquals(actualBitOffset, expectedBitOffset);
        if (expectedBitOffset % 8 == 0) {
            MethodHandle byteOffsetHandle = layout.byteOffsetHandle(pathElements);
            byteOffsetHandle = byteOffsetHandle.asSpreader(long[].class, indexes.length);
            long actualByteOffset = (long) byteOffsetHandle.invokeExact(indexes);
            assertEquals(actualByteOffset, expectedBitOffset / 8);
        }
    }

    @DataProvider
    public static Object[][] testLayouts() {
        List<Object[]> testCases = new ArrayList<>();

        testCases.add(new Object[] {
            MemoryLayout.sequenceLayout(10, JAVA_INT),
            new PathElement[] { sequenceElement() },
            new long[] { 4 },
            JAVA_INT.bitSize() * 4
        });
        testCases.add(new Object[] {
            MemoryLayout.sequenceLayout(10, MemoryLayout.structLayout(JAVA_INT, JAVA_INT.withName("y"))),
            new PathElement[] { sequenceElement(), groupElement("y") },
            new long[] { 4 },
            (JAVA_INT.bitSize() * 2) * 4 + JAVA_INT.bitSize()
        });
        testCases.add(new Object[] {
            MemoryLayout.sequenceLayout(10, MemoryLayout.structLayout(MemoryLayout.paddingLayout(5), JAVA_INT.withName("y"))),
            new PathElement[] { sequenceElement(), groupElement("y") },
            new long[] { 4 },
            (JAVA_INT.bitSize() + 5) * 4 + 5
        });
        testCases.add(new Object[] {
            MemoryLayout.sequenceLayout(10, JAVA_INT),
            new PathElement[] { sequenceElement() },
            new long[] { 4 },
            JAVA_INT.bitSize() * 4
        });
        testCases.add(new Object[] {
            MemoryLayout.structLayout(
                MemoryLayout.sequenceLayout(10, JAVA_INT).withName("data")
            ),
            new PathElement[] { groupElement("data"), sequenceElement() },
            new long[] { 4 },
            JAVA_INT.bitSize() * 4
        });

        MemoryLayout complexLayout = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(10,
                MemoryLayout.sequenceLayout(10,
                    MemoryLayout.structLayout(
                        JAVA_INT.withName("x"),
                        JAVA_INT.withName("y")
                    )
                )
            ).withName("data")
        );

        testCases.add(new Object[] {
            complexLayout,
            new PathElement[] { groupElement("data"), sequenceElement(), sequenceElement(), groupElement("x") },
            new long[] { 0, 1 },
            (JAVA_INT.bitSize() * 2)
        });
        testCases.add(new Object[] {
            complexLayout,
            new PathElement[] { groupElement("data"), sequenceElement(), sequenceElement(), groupElement("x") },
            new long[] { 1, 0 },
            (JAVA_INT.bitSize() * 2) * 10
        });
        testCases.add(new Object[] {
            complexLayout,
            new PathElement[] { groupElement("data"), sequenceElement(), sequenceElement(), groupElement("y") },
            new long[] { 0, 1 },
            (JAVA_INT.bitSize() * 2) + JAVA_INT.bitSize()
        });
        testCases.add(new Object[] {
            complexLayout,
            new PathElement[] { groupElement("data"), sequenceElement(), sequenceElement(), groupElement("y") },
            new long[] { 1, 0 },
            (JAVA_INT.bitSize() * 2) * 10 + JAVA_INT.bitSize()
        });

        return testCases.toArray(Object[][]::new);
    }

    @Test(dataProvider = "testLayouts")
    public void testSliceHandle(MemoryLayout layout, PathElement[] pathElements, long[] indexes,
                                long expectedBitOffset) throws Throwable {
        if (expectedBitOffset % 8 != 0)
            throw new SkipException("Offset not a multiple of 8");

        MemoryLayout selected = layout.select(pathElements);
        MethodHandle sliceHandle = layout.sliceHandle(pathElements);
        sliceHandle = sliceHandle.asSpreader(long[].class, indexes.length);

        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment segment = MemorySegment.allocateNative(layout, session);
            MemorySegment slice = (MemorySegment) sliceHandle.invokeExact(segment, indexes);
            assertEquals(slice.address().toRawLongValue() - segment.address().toRawLongValue(), expectedBitOffset / 8);
            assertEquals(slice.byteSize(), selected.byteSize());
        }
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testSliceHandleUOEInvalidOffsetEager() throws Throwable {
        MemoryLayout layout = MemoryLayout.structLayout(
            MemoryLayout.paddingLayout(5),
            JAVA_INT.withName("y") // offset not a multiple of 8
        );

        layout.sliceHandle(groupElement("y")); // should throw
    }

    @Test(expectedExceptions = UnsupportedOperationException.class)
    public void testSliceHandleUOEInvalidOffsetLate() throws Throwable {
        MemoryLayout layout = MemoryLayout.sequenceLayout(3,
            MemoryLayout.structLayout(
                MemoryLayout.paddingLayout(4),
                    JAVA_INT.withName("y") // offset not a multiple of 8
            )
        );

        MethodHandle sliceHandle;
        try {
            sliceHandle = layout.sliceHandle(sequenceElement(), groupElement("y")); // should work
        } catch (UnsupportedOperationException uoe) {
            fail("Unexpected exception", uoe);
            return;
        }

        try (MemorySession session = MemorySession.openConfined()) {
            MemorySegment segment = MemorySegment.allocateNative(layout, session);

            try {
                sliceHandle.invokeExact(segment, 1); // should work
            } catch (UnsupportedOperationException uoe) {
                fail("Unexpected exception", uoe);
                return;
            }

            sliceHandle.invokeExact(segment, 0); // should throw
        }
    }
}


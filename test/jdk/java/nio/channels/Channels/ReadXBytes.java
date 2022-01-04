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
 */

/*
 * @test
 * @bug 8268435 8274780
 * @summary Verify ChannelInputStream methods readAllBytes and readNBytes
 * @requires (sun.arch.data.model == "64" & os.maxMemory >= 16g)
 * @library ..
 * @library /test/lib
 * @build jdk.test.lib.RandomFactory
 * @modules java.base/jdk.internal.util
 * @run testng/othervm/timeout=900 -Xmx12G ReadXBytes
 * @key randomness
 */
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import jdk.internal.util.ArraysSupport;

import static java.nio.file.StandardOpenOption.*;

import jdk.test.lib.RandomFactory;

import org.testng.Assert;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ReadXBytes {

    private static final Random RAND = RandomFactory.getRandom();

    // The largest source from which to read all bytes
    private static final int  BIG_LENGTH = ArraysSupport.SOFT_MAX_ARRAY_LENGTH;

    // A length greater than a 32-bit integer can accommodate
    private static final long HUGE_LENGTH = Integer.MAX_VALUE + 27L;

    // Current directory
    private static final Path DIR = Path.of(System.getProperty("test.dir", "."));

    // --- Framework ---

    // Create a temporary file path
    static Path createFilePath() {
        String name = String.format("ReadXBytes%d.tmp", System.nanoTime());
        return DIR.resolve(name);
    }

    // Creates a temporary file of a specified length with undefined content
    static Path createFile(long length) throws IOException {
        Path path = createFilePath();
        path.toFile().deleteOnExit();
        try (FileChannel fc = FileChannel.open(path, CREATE_NEW, SPARSE, WRITE)) {
            if (length > 0) {
                fc.position(length - 1);
                fc.write(ByteBuffer.wrap(new byte[] {27}));
            }
        }
        return path;
    }

    // Creates a temporary file of a specified length with random content
    static Path createFileWithRandomContent(long length) throws IOException {
        Path file = createFile(length);
        try (FileChannel fc = FileChannel.open(file, WRITE);) {
            long pos = 0L;
            // if the length exceeds 2 GB, skip the first 2 GB - 1 MB bytes
            if (length >= 2L*1024*1024*1024) {
                // write the last (length - 2GB - 1MB) bytes
                pos = 2047L*1024*1024;
            } else if (length > 0) {
                // write either the first or last bytes only
                long p = Math.min(Math.abs(RAND.nextLong()), length - 1);
                pos = RAND.nextBoolean() ? p : length - 1 - p;
            }
            fc.position(pos);
            int bufLength = Math.min(32768, (int)Math.min(length - pos, BIG_LENGTH));
            byte[] buf = new byte[bufLength];
            while (pos < length) {
                RAND.nextBytes(buf);
                int len = (int)Math.min(bufLength, length - pos);
                pos += fc.write(ByteBuffer.wrap(buf, 0, len));
            }
        }
        return file;
    }

    // Creates a file of a specified length
    @FunctionalInterface
    interface FileCreator {
        Path create(long length) throws IOException;
    }

    // Performs a test for checking edge cases
    @FunctionalInterface
    interface EdgeTest {
        void test(long length, InputStream source) throws IOException;
    }

    // Performs a test for evaluating correctness of content
    @FunctionalInterface
    interface DataTest {
        void test(long length, InputStream source, InputStream reference)
            throws IOException;
    }

    // Construct for testing zero length, EOF, and IAE
    public void edgeTest(long length, FileCreator c, EdgeTest f)
        throws IOException {
        Path file = c.create(length);
        try (FileChannel fc = FileChannel.open(file, READ);
             InputStream cis = Channels.newInputStream(fc)) {
            f.test(length, cis);
        } finally {
            Files.delete(file);
        }
    }

    // Construct for testing correctness of content
    public void dataTest(long length, FileCreator c, DataTest f)
        throws IOException {
        Path file = c.create(length);
        try {
            for (boolean seekable : List.of(false, true)) {
                try (FileInputStream fis = new FileInputStream(file.toFile())) {
                    ReadableByteChannel ch;
                    if (seekable) {
                        ch = FileChannel.open(file, READ);
                    } else {
                        InputStream fis2 = new FileInputStream(file.toFile());
                        ch = Channels.newChannel(new FilterInputStream(fis2) {});
                        assertFalse(ch instanceof SeekableByteChannel);
                    }
                    try (InputStream cis = Channels.newInputStream(ch)) {
                        f.test(length, cis, fis);
                    }
                }
            }
        } finally {
            Files.delete(file);
        }
    }

    // --- readAllBytes tests ---

    // Verifies readAllBytes() behavior for an empty file
    @Test
    public void readAllBytesFromEmptyFile() throws IOException {
        edgeTest(0L, (length) -> createFile(length),
            (length, cis) -> {
                byte[] bytes = cis.readAllBytes();
                assertNotNull(bytes);
                assertEquals(bytes.length, 0L);
            }
        );
    }

    // Verifies readAllBytes() behavior at EOF
    @Test
    public void readAllBytesAtEOF() throws IOException {
        edgeTest(RAND.nextInt(Short.MAX_VALUE), (length) -> createFile(length),
            (length, cis) -> {
                cis.skipNBytes(length);
                byte[] bytes = cis.readAllBytes();
                assertNotNull(bytes);
                assertEquals(bytes.length, 0);
            }
        );
    }

    // Verifies readAllBytes() behavior for a maximal length source
    @Test
    public void readAllBytesFromMaxLengthFile() throws IOException {
        dataTest(BIG_LENGTH, (length) -> createFileWithRandomContent(length),
            (length, cis, fis) -> {
                byte[] cisBytes = cis.readAllBytes();
                assertNotNull(cisBytes);
                assertEquals(cisBytes.length, (long)length);
                byte[] fisBytes = fis.readAllBytes();
                assertEquals(cisBytes, fisBytes);
            }
        );
    }

    // Verifies readAllBytes() throws OOME if the source is too large
    @Test
    public void readAllBytesFromBeyondMaxLengthFile() throws IOException {
        dataTest(HUGE_LENGTH, (length) -> createFile(length),
            (length, cis, fis) -> {
                assertThrows(OutOfMemoryError.class,
                             () -> {cis.readAllBytes();});
            }
        );
    }

    // Provides an array of lengths
    @DataProvider
    public Object[][] lengthProvider() throws IOException {
        return new Object[][] {
            {1 + RAND.nextInt(1)},
            {1 + RAND.nextInt(Byte.MAX_VALUE)},
            {1 + RAND.nextInt(Short.MAX_VALUE)},
            {1 + RAND.nextInt(1_000_000)},
            {1 + RAND.nextInt(BIG_LENGTH)}
        };
    }

    // Verifies readAllBytes() accuracy for random lengths and initial positions
    @Test(dataProvider = "lengthProvider")
    public void readAllBytes(int len) throws IOException {
        dataTest(len, (length) -> createFileWithRandomContent(length),
            (length, cis, fis) -> {
                long position = RAND.nextInt(Math.toIntExact(length));
                cis.skipNBytes(position);
                byte[] cisBytes = cis.readAllBytes();
                assertNotNull(cisBytes);
                assertEquals(cisBytes.length, length - position);
                fis.skipNBytes(position);
                byte[] fisBytes = fis.readAllBytes();
                assertEquals(cisBytes, fisBytes);
            }
        );
    }

    // --- readNBytes tests ---

    // Verifies readNBytes() behavior for a negative length
    @Test
    public void readNBytesWithNegativeLength() throws IOException {
        edgeTest(0L, (length) -> createFile(length),
            (length, cis) -> {
                assertThrows(IllegalArgumentException.class,
                             () -> {cis.readNBytes(-1);});
            }
        );
    }

    // Verifies readNBytes() for an empty file
    @Test
    public void readNBytesFromEmptyFile() throws IOException {
        edgeTest(0L, (length) -> createFile(length),
            (length, cis) -> {
                byte[] bytes = cis.readNBytes(1);
                assertNotNull(bytes);
                assertEquals(bytes.length, 0);
            }
        );
    }

    // Verifies readNBytes() behavior at EOF
    @Test
    public void readNBytesAtEOF() throws IOException {
        edgeTest(RAND.nextInt(Short.MAX_VALUE), (length) -> createFile(length),
            (length, cis) -> {
                cis.skipNBytes(length);
                byte[] bytes = cis.readNBytes(1);
                assertNotNull(bytes);
                assertEquals(bytes.length, 0);
            }
        );
    }

    // Verifies readNBytes() behavior for a maximal length source
    @Test
    public void readNBytesFromMaxLengthFile() throws IOException {
        dataTest(BIG_LENGTH, (length) -> createFileWithRandomContent(length),
            (length, cis, fis) -> {
                byte[] cisBytes = cis.readNBytes(BIG_LENGTH);
                assertNotNull(cisBytes);
                assertEquals(cisBytes.length, (long)length);
                byte[] fisBytes = fis.readNBytes(BIG_LENGTH);
                assertEquals(cisBytes, fisBytes);
            }
        );
    }

    // Verifies readNBytes() beyond the maximum length source
    @Test
    public void readNBytesFromBeyondMaxLengthFile() throws IOException {
        dataTest(HUGE_LENGTH, (length) -> createFileWithRandomContent(length),
            (length, cis, fis) -> {
                assertTrue(BIG_LENGTH < length, length + " >= " + HUGE_LENGTH);
                int n = Math.toIntExact(length - BIG_LENGTH);
                cis.skipNBytes(BIG_LENGTH);
                byte[] cisBytes = cis.readNBytes(n);
                assertNotNull(cisBytes);
                assertEquals(cisBytes.length, n);
                fis.skipNBytes(BIG_LENGTH);
                byte[] fisBytes = fis.readNBytes(n);
                assertEquals(cisBytes, fisBytes);
            }
        );
    }

    // Verifies readNBytes() accuracy for random lengths and initial positions
    @Test(dataProvider = "lengthProvider")
    public void readNBytes(int len) throws IOException {
        dataTest(len, (length) -> createFileWithRandomContent(length),
            (length, cis, fis) -> {
                int ilen = Math.toIntExact(len);
                int position = RAND.nextInt(ilen);
                int n = RAND.nextInt(ilen - position);
                cis.skipNBytes(position);
                byte[] cisBytes = cis.readNBytes(n);
                assertNotNull(cisBytes);
                assertEquals(cisBytes.length, n);
                fis.skipNBytes(position);
                byte[] fisBytes = fis.readNBytes(n);
                assertEquals(cisBytes, fisBytes);
            }
        );
    }
}

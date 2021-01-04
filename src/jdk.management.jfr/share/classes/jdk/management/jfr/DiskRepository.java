/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package jdk.management.jfr;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

import jdk.jfr.internal.management.ManagementSupport;

final class DiskRepository implements Closeable {

    final static class DiskChunk {
        final Path path;
        final Instant startTime;
        Instant endTime;
        long size;

        DiskChunk(Path path, long startNanos) {
            this.path = path;
            this.startTime = ManagementSupport.epochNanosToInstant(startNanos);
        }
    }

    enum State {
        HEADER, EVENT_SIZE, EVENT_TYPE, CHECKPOINT_EVENT_TIMESTAMP, CHECKPOINT_EVENT_DURATION, CHECKPOINT_EVENT_DELTA,
        CHECKPOINT_EVENT_FLUSH_TYPE, CHECKPOINT_EVENT_POOL_COUNT, CHECKPOINT_EVENT_HEADER_TYPE,
        CHECKPOINT_EVENT_HEADER_ITEM_COUNT, CHECKPOINT_EVENT_HEADER_KEY, CHECKPOINT_EVENT_HEADER_BYTE_ARRAY_LENGTH,
        CHECKPOINT_EVENT_HEADER_BYTE_ARRAY_CONTENT, EVENT_PAYLOAD;

        public State next() {
            return State.values()[ordinal() + 1];
        }
    }

    static final byte CHECKPOINT_WITH_HEADER = (byte) 2;
    static final byte MODIFYING_STATE = (byte) 255;
    static final byte COMPLETE_STATE = (byte) 0;
    static final int HEADER_FILE_STATE_POSITION = 64;
    static final int HEADER_START_NANOS_POSITION = 32;
    static final int HEADER_SIZE = 68;
    static final int HEADER_FILE_DURATION = 40;

    private final Deque<DiskChunk> activeChunks = new ArrayDeque<>();
    private final Deque<DiskChunk> deadChunks = new ArrayDeque<>();
    private final boolean deleteDirectory;
    private final ByteBuffer buffer = ByteBuffer.allocate(256);
    private final Path directory;

    private RandomAccessFile raf;
    private RandomAccessFile previousRAF;
    private byte previousRAFstate;
    private int index;
    private int bufferIndex;
    private State state = State.HEADER;
    private byte[] currentByteArray;
    private int typeId;
    private int typeIdshift;
    private int sizeShift;
    private int payLoadSize;
    private int longValueshift;
    private int eventFieldSize;
    private int lastFlush;
    private DiskChunk currentChunk;
    private Duration maxAge;
    private long maxSize;
    private long size;

    public DiskRepository(Path path, boolean deleteDirectory) throws IOException {
        this.directory = path;
        this.deleteDirectory = deleteDirectory;
    }

    public synchronized void write(byte[] bytes) throws IOException {
        index = 0;
        lastFlush = 0;
        currentByteArray = bytes;
        while (index < bytes.length) {
            switch (state) {
            case HEADER:
                processInitialHeader();
                break;
            case EVENT_SIZE:
                processEventSize();
                break;
            case EVENT_TYPE:
                processEventTypeId();
                break;
            case CHECKPOINT_EVENT_TIMESTAMP:
            case CHECKPOINT_EVENT_DURATION:
            case CHECKPOINT_EVENT_DELTA:
            case CHECKPOINT_EVENT_POOL_COUNT:
            case CHECKPOINT_EVENT_HEADER_TYPE:
            case CHECKPOINT_EVENT_HEADER_ITEM_COUNT:
            case CHECKPOINT_EVENT_HEADER_KEY:
            case CHECKPOINT_EVENT_HEADER_BYTE_ARRAY_LENGTH:
                processNumericValueInEvent();
                bufferIndex = 0;
                break;
            case CHECKPOINT_EVENT_HEADER_BYTE_ARRAY_CONTENT:
                processCheckPointHeader();
                break;
            case CHECKPOINT_EVENT_FLUSH_TYPE:
                processFlush();
                break;
            case EVENT_PAYLOAD:
                processEvent();
                break;
            default:
                break;
            }
        }
        // Don't write before header/file is complete
        if (raf == null) {
            return;
        }
        flush();
    }

    private void processFlush() throws IOException {
        byte b = nextByte(true);
        if ((b & CHECKPOINT_WITH_HEADER) != 0) {
            state = State.CHECKPOINT_EVENT_POOL_COUNT;
        } else {
            state = State.EVENT_PAYLOAD;
        }
    }

    private void processNumericValueInEvent() {
        int b = nextByte(true);
        // longValue += (((long) (b & 0x7FL)) << longValueshift);
        if (b >= 0 || longValueshift == 56) {
            state = state.next();
            // longValue = 0;
            longValueshift = 0;
        } else {
            longValueshift += 7;
        }
    }

    private void processEvent() {
        int left = currentByteArray.length - index;
        if (left >= payLoadSize) {
            index += payLoadSize;
            payLoadSize = 0;
            state = State.EVENT_SIZE;
        } else {
            index += left;
            payLoadSize -= left;
        }
    }

    private void processEventTypeId() {
        byte b = nextByte(true);
        long v = (b & 0x7FL);
        typeId += (v << typeIdshift);
        if (b >= 0) {
            if (typeId == 1) {
                state = State.CHECKPOINT_EVENT_TIMESTAMP;
            } else {
                state = State.EVENT_PAYLOAD;
            }
            typeIdshift = 0;
            typeId = 0;
        } else {
            typeIdshift += 7;
        }
    }

    private void processEventSize() throws IOException {
        // End of chunk
        if (previousRAF != null) {
            flush();
            state = State.HEADER;
            return;
        }

        eventFieldSize++;
        byte b = nextByte(false);
        long v = (b & 0x7FL);
        payLoadSize += (v << sizeShift);
        if (b >= 0) {
            if (payLoadSize == 0) {
                throw new IOException("Event size can't be null." + index);
            }
            state = State.EVENT_TYPE;
            sizeShift = 0;
            payLoadSize -= eventFieldSize;
            eventFieldSize = 0;
        } else {
            sizeShift += 7;
        }
    }

    private void processInitialHeader() throws IOException {
        buffer.put(bufferIndex, nextByte(false));
        if (bufferIndex == HEADER_SIZE) {
            writeInitialHeader();
            state = State.EVENT_SIZE;
            bufferIndex = 0;
            if (index != lastFlush + HEADER_SIZE) {
                throw new IOException("Expected data before header to be flushed");
            }
            lastFlush = index;
        }
    }

    private void processCheckPointHeader() throws IOException {
        buffer.put(bufferIndex, nextByte(true));
        if (bufferIndex == HEADER_SIZE) {
            writeCheckPointHeader();
            state = State.EVENT_PAYLOAD;
            bufferIndex = 0;
        }
    }

    private void writeInitialHeader() throws IOException {
        DiskChunk previous = currentChunk;
        currentChunk = nextChunk();
        raf = new RandomAccessFile(currentChunk.path.toFile(), "rw");
        byte fileState = buffer.get(HEADER_FILE_STATE_POSITION);
        buffer.put(HEADER_FILE_STATE_POSITION, MODIFYING_STATE);
        raf.write(buffer.array(), 0, HEADER_SIZE);
        // Complete previous chunk
        completePrevious(previous);

        raf.seek(HEADER_FILE_STATE_POSITION);
        raf.writeByte(fileState);
        raf.seek(HEADER_SIZE);
    }

    private void completePrevious(DiskChunk previous) throws IOException {
        if (previousRAF != null) {
            previousRAF.seek(HEADER_FILE_STATE_POSITION);
            previousRAF.writeByte(previousRAFstate);
            previousRAF.close();
            addChunk(previous);
            previousRAF = null;
            previousRAFstate = (byte) 0;
        }
    }

    private void writeCheckPointHeader() throws IOException {
        Objects.requireNonNull(raf);
        byte state = buffer.get(HEADER_FILE_STATE_POSITION);
        boolean complete = state == COMPLETE_STATE;
        buffer.put(HEADER_FILE_STATE_POSITION, MODIFYING_STATE);
        flush();
        long position = raf.getFilePointer();
        raf.seek(HEADER_FILE_STATE_POSITION);
        raf.writeByte(MODIFYING_STATE);
        raf.seek(0);
        raf.write(buffer.array(), 0, HEADER_SIZE);
        if (!complete) {
            raf.seek(HEADER_FILE_STATE_POSITION);
            raf.writeByte(state);
        } else {
            // will set state to complete when
            // header of next file is created.
            previousRAF = raf;
            previousRAFstate = state;
            currentChunk.size = Files.size(currentChunk.path);
            long durationNanos = buffer.getLong(HEADER_FILE_DURATION);
            Duration d = Duration.ofNanos(durationNanos);
            currentChunk.endTime = currentChunk.startTime.plus(d);
        }
        raf.seek(position);
    }

    private void flush() throws IOException {
        int length = index - lastFlush;
        if (length != 0) {
            raf.write(currentByteArray, lastFlush, length);
            lastFlush = index;
        }
    }

    private byte nextByte(boolean inEvent) {
        byte b = currentByteArray[index];
        index++;
        bufferIndex++;
        if (inEvent) {
            payLoadSize--;
        }
        return b;
    }

    private DiskChunk nextChunk() throws IOException {
        long nanos = buffer.getLong(HEADER_START_NANOS_POSITION);
        long epochSecond = nanos / 1_000_000_000;
        int nanoOfSecond = (int) (nanos % 1_000_000_000);
        ZoneOffset z = OffsetDateTime.now().getOffset();
        LocalDateTime d = LocalDateTime.ofEpochSecond(epochSecond, nanoOfSecond, z);
        String filename = formatDateTime(d);
        Path p1 = directory.resolve(filename + ".jfr");
        if (!Files.exists(p1)) {
            return new DiskChunk(p1, nanos);
        }
        for (int i = 1; i < 100; i++) {
            String s = Integer.toString(i);
            if (i < 10) {
                s = "0" + s;
            }
            Path p2 = directory.resolve(filename + "_" + s + ".jfr");
            if (!Files.exists(p2)) {
                return new DiskChunk(p2, nanos);
            }
        }
        throw new IOException("Could not create chunk for path " + p1);
    }

    static String formatDateTime(LocalDateTime time) {
        StringBuilder sb = new StringBuilder(19);
        sb.append(time.getYear() / 100);
        appendPadded(sb, time.getYear() % 100, true);
        appendPadded(sb, time.getMonth().getValue(), true);
        appendPadded(sb, time.getDayOfMonth(), true);
        appendPadded(sb, time.getHour(), true);
        appendPadded(sb, time.getMinute(), true);
        appendPadded(sb, time.getSecond(), false);
        return sb.toString();
    }

    private static void appendPadded(StringBuilder text, int number, boolean separator) {
        if (number < 10) {
            text.append('0');
        }
        text.append(number);
        if (separator) {
            text.append('_');
        }
    }

    @Override
    public synchronized void close() throws IOException {
        completePrevious(currentChunk);
        if (raf != null) {
            raf.close();
        }
        deadChunks.addAll(activeChunks);
        if (currentChunk != null) {
            deadChunks.add(currentChunk);
        }
        cleanUpDeadChunk(Integer.MAX_VALUE);
        if (deleteDirectory) {
            try {
                Files.delete(directory);
            } catch (IOException ioe) {
                ManagementSupport.logDebug("Could not delete temp stream repository: " + ioe.getMessage());
            }
        }
    }

    public synchronized void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
        trimToAge(Instant.now().minus(maxAge));
    }

    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        trimToSize();
    }

    private void trimToSize() {
        if (maxSize == 0) {
            return;
        }
        int count = 0;
        while (size > maxSize && activeChunks.size() > 1) {
            removeOldestChunk();
            count++;
        }
        cleanUpDeadChunk(count + 10);
    }

    private void trimToAge(Instant oldest) {
        if (maxAge == null) {
            return;
        }
        int count = 0;
        while (activeChunks.size() > 1) {
            DiskChunk oldestChunk = activeChunks.getLast();
            if (oldestChunk.endTime.isAfter(oldest)) {
                return;
            }
            removeOldestChunk();
            count++;
        }
        cleanUpDeadChunk(count + 10);
    }

    public synchronized void onChunkComplete(Instant timestamp) {
        int count = 0;
        while (!activeChunks.isEmpty()) {
            DiskChunk oldestChunk = activeChunks.peek();
            if (oldestChunk.startTime.isBefore(timestamp)) {
                removeOldestChunk();
                count++;
            } else {
                break;
            }
        }
        cleanUpDeadChunk(count + 10);
    }

    private void addChunk(DiskChunk chunk) {
        if (maxAge != null) {
            trimToAge(chunk.endTime.minus(maxAge));
        }
        activeChunks.push(chunk);
        size += chunk.size;
        trimToSize();
    }

    private void removeOldestChunk() {
        DiskChunk chunk = activeChunks.poll();
        deadChunks.add(chunk);
        size -= chunk.size;
    }

    private void cleanUpDeadChunk(int maxCount) {
        int count = 0;
        Iterator<DiskChunk> iterator = deadChunks.iterator();
        while (iterator.hasNext()) {
            DiskChunk chunk = iterator.next();
            try {
                Files.delete(chunk.path);
                iterator.remove();
            } catch (IOException e) {
                // ignore
            }
            count++;
            if (count == maxCount) {
                return;
            }
        }
    }

    public synchronized void complete() {
        if (currentChunk != null) {
            try {
                completePrevious(currentChunk);
            } catch (IOException ioe) {
                ManagementSupport.logDebug("Could not complete chunk " + currentChunk.path + " : " + ioe.getMessage());
            }
        }
    }
}

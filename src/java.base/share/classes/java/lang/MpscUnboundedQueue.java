/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
package java.lang;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Multi-Producer Single-Consumer unbounded array queue using VarHandles.
 * Based on JCTools MpscUnboundedArrayQueue but self-contained.
 *
 * @param <E> the type of elements held in this queue
 */
final class MpscUnboundedQueue<E> {

    private static final VarHandle PRODUCER_INDEX;
    private static final VarHandle CONSUMER_INDEX;
    private static final VarHandle PRODUCER_LIMIT;
    private static final VarHandle ARRAY;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PRODUCER_INDEX = lookup.findVarHandle(MpscUnboundedQueue.class, "producerIndex", long.class);
            CONSUMER_INDEX = lookup.findVarHandle(MpscUnboundedQueue.class, "consumerIndex", long.class);
            PRODUCER_LIMIT = lookup.findVarHandle(MpscUnboundedQueue.class, "producerLimit", long.class);
            ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Object JUMP = new Object();
    private static final Object BUFFER_CONSUMED = new Object();
    private static final int CONTINUE_TO_P_INDEX_CAS = 0;
    private static final int RETRY = 1;
    private static final int QUEUE_RESIZE = 3;

    private static final long RESIZE_BIT = 1L;

    @SuppressWarnings("FieldMayBeFinal")
    private long producerIndex;
    @SuppressWarnings("FieldMayBeFinal")
    private long consumerIndex;
    @SuppressWarnings("FieldMayBeFinal")
    private long producerLimit;

    private long producerMask;
    private E[] producerBuffer;
    private long consumerMask;
    private E[] consumerBuffer;

    MpscUnboundedQueue(int initialCapacity) {
        if (initialCapacity < 2) {
            throw new IllegalArgumentException("Initial capacity must be 2 or more");
        }
        int p2capacity = roundToPowerOfTwo(initialCapacity);
        long mask = (p2capacity - 1L) << 1;
        @SuppressWarnings("unchecked")
        E[] buffer = (E[]) new Object[p2capacity + 1];
        producerBuffer = buffer;
        consumerBuffer = buffer;
        producerMask = mask;
        consumerMask = mask;
        soProducerLimit(mask);
    }

    private static int roundToPowerOfTwo(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Must be positive");
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    private void soProducerLimit(long v) {
        PRODUCER_LIMIT.setRelease(this, v);
    }

    private long lvProducerLimit() {
        return (long) PRODUCER_LIMIT.getAcquire(this);
    }

    private long lvProducerIndex() {
        return (long) PRODUCER_INDEX.getAcquire(this);
    }

    private boolean casProducerIndex(long expect, long newValue) {
        return PRODUCER_INDEX.compareAndSet(this, expect, newValue);
    }

    private long lvConsumerIndex() {
        return (long) CONSUMER_INDEX.getAcquire(this);
    }

    private void soConsumerIndex(long v) {
        CONSUMER_INDEX.setRelease(this, v);
    }

    private void soProducerIndex(long v) {
        PRODUCER_INDEX.setRelease(this, v);
    }

    private boolean casProducerLimit(long expect, long newValue) {
        return PRODUCER_LIMIT.compareAndSet(this, expect, newValue);
    }

    private static <E> void soRefElement(E[] buffer, int offset, E e) {
        ARRAY.setRelease(buffer, offset, e);
    }

    @SuppressWarnings("unchecked")
    private static <E> E lvRefElement(E[] buffer, int offset) {
        return (E) ARRAY.getAcquire(buffer, offset);
    }

    void offer(E e) {
        if (null == e) {
            throw new NullPointerException();
        }

        long mask;
        E[] buffer;
        long pIndex;

        while (true) {
            long producerLimit = lvProducerLimit();
            pIndex = lvProducerIndex();
            if ((pIndex & RESIZE_BIT) == 1) {
                continue;
            }

            mask = this.producerMask;
            buffer = this.producerBuffer;

            if (producerLimit <= pIndex) {
                int result = offerSlowPath(mask, pIndex, producerLimit);
                switch (result) {
                    case CONTINUE_TO_P_INDEX_CAS:
                        break;
                    case RETRY:
                        continue;
                    case QUEUE_RESIZE:
                        resize(mask, buffer, pIndex, e);
                        return;
                }
            }

            if (casProducerIndex(pIndex, pIndex + 2)) {
                break;
            }
        }
        final int offset = modifiedCalcCircularRefElementOffset(pIndex, mask);
        soRefElement(buffer, offset, e);
    }

    private int offerSlowPath(long mask, long pIndex, long producerLimit) {
        final long cIndex = lvConsumerIndex();
        long bufferCapacity = mask;
        if (cIndex + bufferCapacity > pIndex) {
            if (!casProducerLimit(producerLimit, cIndex + bufferCapacity)) {
                return RETRY;
            }
            return CONTINUE_TO_P_INDEX_CAS;
        }
        if (casProducerIndex(pIndex, pIndex + 1)) {
            return QUEUE_RESIZE;
        }
        return RETRY;
    }

    private void resize(long oldMask, E[] oldBuffer, long pIndex, final E e) {
        int newBufferLength = oldBuffer.length;
        @SuppressWarnings("unchecked")
        final E[] newBuffer = (E[]) new Object[newBufferLength];

        producerBuffer = newBuffer;
        final int newMask = (newBufferLength - 2) << 1;
        producerMask = newMask;

        final int offsetInOld = modifiedCalcCircularRefElementOffset(pIndex, oldMask);
        final int offsetInNew = modifiedCalcCircularRefElementOffset(pIndex, newMask);

        soRefElement(newBuffer, offsetInNew, e);
        soRefElement(oldBuffer, nextArrayOffset(oldMask), newBuffer);

        final long cIndex = lvConsumerIndex();
        final long availableInQueue = Integer.MAX_VALUE - (pIndex - cIndex);
        if (availableInQueue <= 0) {
            throw new IllegalStateException();
        }

        soProducerLimit(pIndex + Math.min(newMask, availableInQueue));
        soProducerIndex(pIndex + 2);
        soRefElement(oldBuffer, offsetInOld, JUMP);
    }

    private int nextArrayOffset(final long mask) {
        return modifiedCalcCircularRefElementOffset(mask + 2, Long.MAX_VALUE);
    }

    private static int modifiedCalcCircularRefElementOffset(long index, long mask) {
        return (int) ((index & mask) >> 1);
    }

    @SuppressWarnings("unchecked")
    E poll() {
        final E[] buffer = consumerBuffer;
        final long index = consumerIndex;
        final long mask = consumerMask;

        final int offset = modifiedCalcCircularRefElementOffset(index, mask);
        Object e = lvRefElement(buffer, offset);
        if (e == null) {
            long pIndex = lvProducerIndex();
            pIndex += (pIndex & RESIZE_BIT);
            if (index == pIndex) {
                return null;
            }
            do {
                e = lvRefElement(buffer, offset);
            } while (e == null);
        }
        if (e == JUMP) {
            final E[] nextBuffer = nextBuffer(buffer, mask);
            return newBufferPoll(nextBuffer, index);
        }
        soRefElement(buffer, offset, null);
        soConsumerIndex(index + 2);
        return (E) e;
    }

    private E[] nextBuffer(final E[] buffer, final long mask) {
        final int nextArrayOffset = nextArrayOffset(mask);
        @SuppressWarnings("unchecked")
        final E[] nextBuffer = (E[]) lvRefElement(buffer, nextArrayOffset);
        consumerBuffer = nextBuffer;
        consumerMask = (nextBuffer.length - 2L) << 1;
        soRefElement(buffer, nextArrayOffset, BUFFER_CONSUMED);
        return nextBuffer;
    }

    private E newBufferPoll(E[] nextBuffer, final long index) {
        final int offset = modifiedCalcCircularRefElementOffset(index, consumerMask);
        final E n = lvRefElement(nextBuffer, offset);
        if (n == null) {
            throw new IllegalStateException("new buffer must have at least one element");
        }
        soRefElement(nextBuffer, offset, null);
        soConsumerIndex(index + 2);
        return n;
    }

    boolean isEmpty() {
        long cIndex = lvConsumerIndex();
        long pIndex = lvProducerIndex();
        pIndex += (pIndex & RESIZE_BIT);
        return cIndex == pIndex;
    }

    int size() {
        long after = lvConsumerIndex();
        long size;
        while (true) {
            final long before = after;
            final long currentProducerIndex = lvProducerIndex();
            after = lvConsumerIndex();
            if (before == after) {
                long pIndex = currentProducerIndex;
                pIndex += (pIndex & RESIZE_BIT);
                size = (pIndex - after) >> 1;
                break;
            }
        }
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }
}

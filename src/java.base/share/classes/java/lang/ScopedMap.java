/*
 * Copyright (c) 1994, 2019, Oracle and/or its affiliates. All rights reserved.
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

class ScopedMap {

    Object[] tab = new Object[16];

    int size;

    static final Object NULL_PLACEHOLDER = new Object();

    private static final int MAXIMUM_CAPACITY = 1 << 29;

    /**
     * Circularly traverses table of size len.
     */
    private static int nextKeyIndex(int i, int len) {
        return (i + 2 < len ? i + 2 : 0);
    }

    private static int hash(Scoped<?> key, int len) {
        return hash(key.hashCode(), len);
    }

    private static int hash(long k, int len) {
        k <<= 1;
        return (int)k & (len - 1);
    }

    @SuppressWarnings("unchecked")
    public Object get(long k, Scoped<?> key) {
        int len = tab.length;
        int i = hash(k, len);
        while (true) {
            Object item = tab[i];
            if (item == key)
                return tab[i + 1];
            if (item == null)
                return NULL_PLACEHOLDER;;
            i = nextKeyIndex(i, len);
        }
    }

    @SuppressWarnings(value = {"unchecked", "rawtypes"})  // one map has entries for all types <T>
    public Object put(long k, Scoped<?> key, Object value) {

        retryAfterResize: for (;;) {
            final int len = tab.length;
            int i = hash(k, len);

            for (Object item; (item = tab[i]) != null;
                 i = nextKeyIndex(i, len)) {
                if (item == key) {
                    @SuppressWarnings("unchecked")
                    Object oldValue = tab[i + 1];
                    tab[i + 1] = value;
                    return oldValue;
                }
            }

            final int s = size + 1;
            // Use optimized form of 3 * s.
            // Next capacity is len, 2 * current capacity.
            if (s + (s << 1) > len && resize(len))
                continue retryAfterResize;

            tab[i] = key;
            tab[i + 1] = value;
            size = s;
            return NULL_PLACEHOLDER;
        }
    }

    Object remove(long k, Object key) {
        int len = tab.length;
        int i = hash(k, len);

        while (true) {
            Object item = tab[i];
            if (item == key) {
                size--;
                Object oldValue = tab[i + 1];
                tab[i] = null;
                tab[i + 1] = null;
                closeDeletion(i);
                return oldValue;
            }
            if (item == null)
                throw new RuntimeException("not bound");
            i = nextKeyIndex(i, len);
        }
    }

    private void closeDeletion(int d) {
        // Adapted from Knuth Section 6.4 Algorithm R
        int len = tab.length;

        // Look for items to swap into newly vacated slot
        // starting at index immediately following deletion,
        // and continuing until a null slot is seen, indicating
        // the end of a run of possibly-colliding keys.
        Object item;
        for (int i = nextKeyIndex(d, len); (item = tab[i]) != null;
             i = nextKeyIndex(i, len) ) {
            // The following test triggers if the item at slot i (which
            // hashes to be at slot r) should take the spot vacated by d.
            // If so, we swap it in, and then continue with d now at the
            // newly vacated i.  This process will terminate when we hit
            // the null slot at the end of this run.
            // The test is messy because we are using a circular table.
            int r = hash((Scoped<?>)tab[i], len);
            if ((i < r && (r <= d || d <= i)) || (r <= d && d <= i)) {
                tab[d] = item;
                tab[d + 1] = tab[i + 1];
                tab[i] = null;
                tab[i + 1] = null;
                d = i;
            }
        }
    }


    private boolean resize(int newCapacity) {
        assert (newCapacity & -newCapacity) == newCapacity; // power of 2
        int newLength = newCapacity * 2;

        final Object[] oldTable = tab;
        int oldLength = oldTable.length;
        if (oldLength == 2 * MAXIMUM_CAPACITY) { // can't expand any further
            if (size == MAXIMUM_CAPACITY - 1)
                throw new IllegalStateException("Capacity exhausted.");
            return false;
        }
        if (oldLength >= newLength)
            return false;

        Object[] newTable = new Object[newLength];

        for (int j = 0; j < oldLength; j += 2) {
            Object key = oldTable[j];
            if (key != null) {
                Object value = oldTable[j+1];
                oldTable[j] = null;
                oldTable[j + 1] = null;
                int i = hash((Scoped<?>)key, newLength);
                while (newTable[i] != null)
                    i = nextKeyIndex(i, newLength);
                newTable[i] = key;
                newTable[i + 1] = value;
            }
        }
        tab = newTable;
        return true;
    }
}
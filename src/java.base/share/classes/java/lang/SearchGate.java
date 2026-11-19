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
import jdk.internal.access.SharedSecrets;
import jdk.internal.vm.annotation.Contended;

/**
 * The searcher token of an MpscVirtualThreadScheduler's carriers, with stealing. A
 * signal for work on a victim moves the first parked carrier it finds in the IdleSet,
 * from a random start, to SEARCHING + victim, passing it the token. The searcher
 * steals one task, then hands the token on if the victim has more, else releases it.
 *
 * <p>Invariants:
 * <ul>
 * <li>At most one searcher is in flight.
 * <li>A signal that finds the token held is not lost: the release rescans.
 * </ul>
 */
final class SearchGate {
    private static final VarHandle SEARCH_TOKEN;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            SEARCH_TOKEN = l.findVarHandle(SearchGate.class, "searching", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MpscCarrierThread[] carriers;
    private final IdleSet idleSet;

    @Contended("searching")
    private volatile int searching;

    // bestVictim walks a power of two >= the carrier count: an odd stride visits each slot once
    private final int mask;

    /** The array is filled after this, before any carrier starts or the scheduler is published. */
    SearchGate(MpscCarrierThread[] carriers, IdleSet idleSet) {
        this.carriers = carriers;
        this.idleSet = idleSet;
        int m = 1;
        while (m < carriers.length) {
            m <<= 1;
        }
        this.mask = m - 1;
    }

    void signal(MpscCarrierThread victim) {
        if (searching != 0) {
            return;
        }
        int r = random();
        if (placeSearcher(victim, r, false)) {
            release(r);
        }
    }

    /** Returns true if the caller is left holding the token. */
    private boolean placeSearcher(MpscCarrierThread victim, int r, boolean held) {
        var idleSet = this.idleSet;
        var carriers = this.carriers;
        int count = idleSet.wordCount();
        // r is uniform over 32 bits, so multiply-shift maps it to [0, n)
        int s = (int) (((r & 0xFFFFFFFFL) * carriers.length) >>> 32);
        int w = s >>> 6;
        int b = s & 63;
        for (int k = 0; k < count; k++) {
            long word = idleSet.word(w);
            if (w == victim.id >>> 6) {
                word &= ~(1L << victim.id);
            }
            // bit j of the rotated copy is bit (b + j) & 63: from b up, then wrapping
            for (long bits = Long.rotateRight(word, b); bits != 0; bits &= bits - 1) {
                MpscCarrierThread c = carriers[(w << 6) + ((b + Long.numberOfTrailingZeros(bits)) & 63)];
                if (!c.isParked()) {
                    continue;
                }
                if (!held) {
                    if (!SEARCH_TOKEN.compareAndSet(this, 0, 1)) {
                        return false;
                    }
                    held = true;
                }
                if (c.tryStartSearch(victim)) {
                    return false;
                }
            }
            b = 0;
            if (++w == count) {
                w = 0;
            }
        }
        return held;
    }

    /** Called by the searcher after its steal; releases the token if it cannot pass it. */
    void handOn(MpscCarrierThread victim, int r) {
        if (!victim.hasStealable() || placeSearcher(victim, r, true)) {
            release(r);
        }
    }

    void release(int r) {
        for (;;) {
            searching = 0;
            if (!idleSet.anyIdle()) {
                return;
            }
            MpscCarrierThread v = bestVictim(null, r);
            if (v == null || !placeSearcher(v, r, false)) {
                return;
            }
        }
    }

    MpscCarrierThread carrier(int id) {
        return carriers[id];
    }

    /** Never mpscProbe, which must stay stable, nor threadLocalRandomProbe (Striped64). */
    private int random() {
        if (Thread.currentCarrierThread() instanceof MpscCarrierThread c && c.usesGate(this)) {
            return c.nextRandom();
        }
        return SharedSecrets.getJavaUtilConcurrentTLRAccess().nextSecondaryThreadLocalRandomSeed();
    }

    /**
     * Two choices by backlog in a random permutation of the carriers: of the first two
     * other than self the one with more, else the next with any, or null.
     */
    MpscCarrierThread bestVictim(MpscCarrierThread self, int r) {
        var carriers = this.carriers;
        int n = carriers.length;
        int mask = this.mask;
        int step = (r >>> 16) | 1;
        MpscCarrierThread best = null;
        int bestSize = 0;
        int looked = 0;
        for (int i = 0, pos = r; i <= mask; i++, pos += step) {
            int j = pos & mask;
            if (j >= n) {
                continue;
            }
            MpscCarrierThread c = carriers[j];
            if (c != self) {
                int size = c.stealableBacklog();
                if (size > bestSize) {
                    best = c;
                    bestSize = size;
                }
                if (++looked >= 2 && best != null) {
                    break;
                }
            }
        }
        return best;
    }
}

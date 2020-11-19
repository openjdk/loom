/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_Z_ZRELOCATIONSETSELECTOR_INLINE_HPP
#define SHARE_GC_Z_ZRELOCATIONSETSELECTOR_INLINE_HPP

#include "gc/z/zArray.inline.hpp"
#include "gc/z/zPage.inline.hpp"
#include "gc/z/zRelocationSetSelector.hpp"

inline size_t ZRelocationSetSelectorGroupStats::npages() const {
  return _npages;
}

inline size_t ZRelocationSetSelectorGroupStats::total() const {
  return _total;
}

inline size_t ZRelocationSetSelectorGroupStats::live() const {
  return _live;
}

inline size_t ZRelocationSetSelectorGroupStats::garbage() const {
  return _garbage;
}

inline size_t ZRelocationSetSelectorGroupStats::empty() const {
  return _empty;
}

inline size_t ZRelocationSetSelectorGroupStats::compacting_from() const {
  return _compacting_from;
}

inline size_t ZRelocationSetSelectorGroupStats::compacting_to() const {
  return _compacting_to;
}

inline const ZRelocationSetSelectorGroupStats& ZRelocationSetSelectorStats::small() const {
  return _small;
}

inline const ZRelocationSetSelectorGroupStats& ZRelocationSetSelectorStats::medium() const {
  return _medium;
}

inline const ZRelocationSetSelectorGroupStats& ZRelocationSetSelectorStats::large() const {
  return _large;
}

inline void ZRelocationSetSelectorGroup::register_live_page(ZPage* page) {
  const uint8_t type = page->type();
  const size_t size = page->size();
  const size_t live = page->live_bytes();
  const size_t garbage = size - live;

  if (garbage > _fragmentation_limit) {
    _registered_pages.append(page);
  }

  _stats._npages++;
  _stats._total += size;
  _stats._live += live;
  _stats._garbage += garbage;
}

inline void ZRelocationSetSelectorGroup::register_garbage_page(ZPage* page) {
  const size_t size = page->size();

  _stats._npages++;
  _stats._total += size;
  _stats._garbage += size;
  _stats._empty += size;
}

inline const ZArray<ZPage*>* ZRelocationSetSelectorGroup::selected() const {
  return &_registered_pages;
}

inline size_t ZRelocationSetSelectorGroup::forwarding_entries() const {
  return _forwarding_entries;
}

inline const ZRelocationSetSelectorGroupStats& ZRelocationSetSelectorGroup::stats() const {
  return _stats;
}

inline void ZRelocationSetSelector::register_live_page(ZPage* page) {
  const uint8_t type = page->type();

  if (type == ZPageTypeSmall) {
    _small.register_live_page(page);
  } else if (type == ZPageTypeMedium) {
    _medium.register_live_page(page);
  } else {
    _large.register_live_page(page);
  }
}

inline void ZRelocationSetSelector::register_garbage_page(ZPage* page) {
  const uint8_t type = page->type();

  if (type == ZPageTypeSmall) {
    _small.register_garbage_page(page);
  } else if (type == ZPageTypeMedium) {
    _medium.register_garbage_page(page);
  } else {
    _large.register_garbage_page(page);
  }

  _garbage_pages.append(page);
}

inline bool ZRelocationSetSelector::should_free_garbage_pages(int bulk) const {
  return _garbage_pages.length() >= bulk && _garbage_pages.is_nonempty();
}

inline const ZArray<ZPage*>* ZRelocationSetSelector::garbage_pages() const {
  return &_garbage_pages;
}

inline void ZRelocationSetSelector::clear_garbage_pages() {
  return _garbage_pages.clear();
}

inline size_t ZRelocationSetSelector::total() const {
  return _small.stats().total() + _medium.stats().total() + _large.stats().total();
}

inline size_t ZRelocationSetSelector::empty() const {
  return _small.stats().empty() + _medium.stats().empty() + _large.stats().empty();
}

inline size_t ZRelocationSetSelector::compacting_from() const {
  return _small.stats().compacting_from() + _medium.stats().compacting_from() + _large.stats().compacting_from();
}

inline size_t ZRelocationSetSelector::compacting_to() const {
  return _small.stats().compacting_to() + _medium.stats().compacting_to() + _large.stats().compacting_to();
}

inline const ZArray<ZPage*>* ZRelocationSetSelector::small() const {
  return _small.selected();
}

inline const ZArray<ZPage*>* ZRelocationSetSelector::medium() const {
  return _medium.selected();
}

inline size_t ZRelocationSetSelector::forwarding_entries() const {
  return _small.forwarding_entries() + _medium.forwarding_entries();
}

#endif // SHARE_GC_Z_ZRELOCATIONSETSELECTOR_INLINE_HPP

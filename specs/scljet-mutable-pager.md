# SclJet mutable pager — dirty-page tracking + cell-level in-place B-tree edits

> Status: the dirty-page pager, cell-level leaf edits, insert/delete/update with
> `balance()` split, arbitrary-depth index B-trees, and DML wiring are **built and
> verified**. This spec documents the finished design and its invariants, and
> specifies the **merge/rebalance-on-underflow** slice (freed page → freelist) that
> completes SQLite's `balance()`.

## Goal

Replace the read-modify-rewrite DML (`mutate.ssc`, which rebuilds the whole file for
every row change) with a **real in-place mutable pager**: only the pages a change
touches move, journalled for crash-safety, byte-verified against reference SQLite
3.53.3 (`PRAGMA integrity_check` = `ok`) and byte-identical across the `int` and `js`
backends.

## Layering (where each piece lives)

The mutable pager is **composed across three modules** — it is not one file. The
task named `pager.ssc` as the landing spot, but `pager.ssc` is the SHARED-locked
**read-only** pager (`ReadonlyPager`); the write path was built next to the write-side
primitives it depends on, which is where it belongs:

| Concern | Module | Symbols |
|---|---|---|
| Crash-safe page primitive | `journal.ssc` | `writePagesJournaled`, `applyRollbackJournal` |
| Dirty-set pager + atomic commit | `journal.ssc` | `MutablePager`, `openMutablePager`, `mutableGet`/`mutablePut`/`mutableAllocate`, `mutableCommit`/`mutableRollback` |
| Cell-level leaf edits | `write.ssc` | `readLeafCells`, `leafInsertCell`/`leafDeleteCell`/`leafUpdateCell`, `rebuildLeafPage` |
| `balance()` on insert/delete | `write.ssc` | `pagerInsertBalanced`, `pagerDeleteBalanced`, `balanceInsertNode`, `balanceDeeper` |
| **Merge/rebalance on underflow** | `write.ssc` | `pagerDeleteRebalanced`, `freePageOnto`, `patchFreelistHeader` (this slice) |
| Arbitrary-depth index trees | `write.ssc` | `buildIndexTree` (kind-2 interior levels stacked bottom-up) |
| Read-back / freelist validation | `pager.ssc`, `freelist.ssc` | `ReadonlyPager`, `validateFreelist` |
| DML wiring | `sql.ssc` | INSERT→`pagerInsertBalanced`, DELETE→`pagerDeleteBalanced`, UPDATE→delete+reinsert |

All write symbols are re-exported through `index.ssc`, the single import surface the
conformance tests and the SQL engine use.

## Dirty-set model (`MutablePager`)

```
case class MutablePager(image: ByteSlice, pageSize: Int, sectorSize: Int, pending: List[JournalPage])
```

- `pending` is a **newest-first** stack of staged page images. Reads (`mutableGet`)
  see the newest staged image of a page, falling back to `image` (the committed file).
  So an edit is visible to later edits in the same transaction before commit.
- `mutablePut` pushes a new staged image; `mutableAllocate` returns the next
  page number at EOF (max of the file count and any staged new page + 1).
- **Commit collapses** `pending` to one entry per page number (`dedupChanges`, newest
  wins), so each touched page is journalled once and applied once — not once per
  intermediate rewrite during a multi-step `balance()`.

### Journal-before-write ordering (crash-safety)

`mutableCommit` is the only path that mutates the file, and it obeys strict ordering:

1. **Capture pre-images** of every page that already exists and is about to change
   (`preImages`), under the **original** page count.
2. **Write the rollback journal** (`writeRollbackJournal`) — the hot-journal magic
   header + one `[u32 pageNo][page bytes][u32 checksum]` record per pre-image.
3. **Grow** the image to fit any newly allocated pages (`byteSliceZeroExtend`).
4. **Apply** every staged page in place (`applyPages`).

The returned `PagerCommit(pager, journal)` pairs the mutated image with the journal.

**Crash-safety proof obligation.** For any commit, if a crash interrupts step 4 (new
pages partly written, journal present and complete), then
`applyRollbackJournal(mutated, journal)` restores the byte-exact original image and
truncates back to the original page count — i.e. `recover(commit(db).image,
commit(db).journal) == db`. This is the exact inverse relationship between
`writePagesJournaled`/`mutableCommit` and `applyRollbackJournal`. Verified in
`scljet-cell-inplace` (journal recovers original), `scljet-pager-mutate` (allocation
undone by recovery), and this slice's test after a merge.

## `balance()` on insert (built)

`pagerInsertBalanced(pager, rootPage, rowid, record, pageSize)`:

1. Descend from `rootPage` choosing the child whose divider key `>= rowid` (else the
   rightmost child), to the target table leaf (kind 13).
2. `leafInsertCell` in ascending rowid order (duplicate rowid → error).
3. On the way back up, `finishLeaf`/`finishInterior` greedily re-pack each node into
   page-fitting chunks (`packLeafChunks`/`packInteriorChunks`). One chunk = the node
   fit; multiple chunks = it **split**: the first chunk keeps the node's page number,
   the rest are allocated at EOF, and the parent's single child slot is **replaced by
   the pieces**, inserting the first `k-1` piece max-rowids as new dividers
   (`replaceChild`). An interior split **promotes** a divider (it moves to the parent,
   not copied) — unlike a table interior which copies a rowid separator.
4. When the **root** overflows, the tree grows a level (`balanceDeeper`,
   SQLite's `balance_deeper`): the root's content moves to a fresh page and the root
   **page number is kept** as the new interior over the split pieces.
5. `patchHeaderPageCount` writes the new page total to header bytes 28–31.

**Insert invariants**: divider keys never change on insert (a routed rowid is `<=` its
subtree divider, or lands in the rightmost child); every produced page fits
`pageSize`; the root page number is stable across a level-grow.

## `balance()` on delete + **merge/rebalance on underflow** (this slice)

Today's `pagerDeleteBalanced` descends to the leaf and rewrites it with the cell
removed. An underfull (even empty) leaf and a slightly-stale divider are **still a
valid B-tree** — `integrity_check` accepts them — but the file never reclaims pages,
so a heavily-deleted table stays bloated. This slice adds the reclaiming half.

`pagerDeleteRebalanced(pager, rootPage, rowid, pageSize)` performs a recursive
delete-and-rebalance:

- **Leaf**: remove the cell. The leaf is *deficient* if it has 0 cells (empty). (We
  only merge on empty rather than on a half-full threshold — this keeps the tree valid
  and reclaims fully-emptied pages, the common case for range/bulk deletes, without
  chasing SQLite's exact fill-factor heuristic, which we do not need for
  `integrity_check`.) Signal the leaf's new state (cells + deficient flag) up.
- **Interior**: choose child, recurse. If the returned child is **deficient**:
  - **Merge** the deficient child with an adjacent sibling under this interior when
    their combined contents fit one page: write the merged node to the **left** page
    (keeping the lower page number — positional stability), drop the absorbed child +
    its divider from this interior, and **free the absorbed page** (below). A table
    interior merge folds the dropped divider rowid into the merged node's key stream;
    a leaf merge simply concatenates cells.
  - If a single merge cannot absorb it (siblings full), leave the deficient child in
    place (valid, just not reclaimed) — a bounded, always-valid fallback.
  - Rewrite this interior. If it now has a **single child**, signal *collapse* up.
- **Root collapse** (`balance_shallower`): when the root interior has one child,
  copy that child's content into the root page (root number kept) and free the child.

### Freeing a page — freelist maintenance

A merged-away page is in the MIDDLE of the file; removing it would renumber every
later page (positions are page numbers). Instead — exactly as SQLite's default
(non-auto-vacuum) mode — the freed page is added to the **freelist** and the file
length is unchanged:

- **Freelist trunk** page format (inverse of `decodeFreelistTrunk`):
  `u32 nextTrunk` (0 = end) · `u32 leafCount` · `leafCount × u32` leaf page numbers.
  A trunk holds at most `usableSize/4 - 2` leaves.
- **Header**: bytes 32–35 = first-freelist-trunk page number; bytes 36–39 = total
  freelist page count (trunks + leaves).
- **Scheme** (`freePageOnto`): the first freed page becomes a trunk with 0 leaves and
  becomes the header's first trunk. Each subsequent freed page is appended as a **leaf**
  of the head trunk until it fills (`usableSize/4 - 2` leaves); the next freed page
  then becomes a new head trunk chained (`nextTrunk`) to the old one. This is a valid
  freelist — order/packing need not match SQLite byte-for-byte, only `integrity_check`
  and our own `validateFreelist` must accept it.
- The freed page's own bytes are overwritten (zeroed except the trunk header it may
  carry) so it no longer decodes as a B-tree node.

### Delete/merge invariants (proof obligations)

1. **Coverage**: every rowid present before a delete except the deleted one is present
   after, in ascending order (walk the tree).
2. **No orphans / no double-use**: every page in the file is reachable exactly once —
   from the B-tree, or from the freelist, or is page 1 / the lock-byte page.
   `integrity_check` cross-checks this; a merge that dropped a child but forgot to free
   it, or freed a page still referenced by a divider, fails immediately.
3. **Counts agree**: header page count (28–31) is unchanged by a merge (pages are
   reclaimed, not removed); header freelist count (36–39) equals trunks+leaves;
   `validateFreelist` re-derives the same number.
4. **Reclamation**: after emptying leaves, the on-disk page count reachable from the
   B-tree strictly decreases (the freed pages move to the freelist) — the observable
   that distinguishes a real merge from a leaf rewrite.
5. **Crash-safety**: the whole rebalanced delete commits through one `mutableCommit`,
   so it inherits the journal inverse (obligation above).

## Arbitrary-depth index B-trees (built)

`buildIndexTree(entries, pageSize, indexRoot)` builds an index B-tree of **any depth**:
one kind-10 leaf if it all fits; otherwise leaves packed with **promoted** separators
(the separator lives in the interior, not a leaf), and interior levels (kind 2) stacked
bottom-up until a single-page root, numbered top-down (root, interior levels, leaves).
Two-level output is byte-identical to the earlier single-interior form. A genuine
3-level index (interior level itself overflows) is exercised by `scljet-write-index-deep`
(3000 rows, `SEARCH … USING COVERING INDEX`, `integrity_check` ok, depth 3, int==js).

## Verification method

- **Oracle**: this machine's **Python** `sqlite3` module is SQLite **3.53.3** (the pinned
  oracle). The `sqlite3` CLI is only 3.51.0 — never use it for the oracle.
  `PRAGMA integrity_check` cross-validates indexes vs tables and the freelist vs the
  page inventory, so `integrity_check = ok` after an in-place edit **proves**
  structural consistency.
- **`int == js`**: every mutable-pager conformance case ends with an **iterative
  Adler-32 fingerprint** over the whole image (`byteSliceGet` in a `while` loop — never
  `byteSliceToList` on a large image, which overflows JS `toString` / int stacks). The
  same golden `expected/<name>.txt` is compared against both backends, so they must be
  byte-identical.
- **Big-DB dumps**: for images too large for a byte-list, dump on `int` in 512-byte
  chunks (`byteSliceSlice` + `byteSliceToList`), reassemble in Python, and run
  `integrity_check` + a rowid/depth walk out-of-band.

## Backend gotchas (binding for every function here)

- A bare `if cond then <stmt>` with **no else**, used as a statement, is **silently
  skipped** by the interpreter → always use an if/else **expression**
  (`x = if c then f(x) else x`).
- JS has **no TCO** → page-assembly and byte loops must be **iterative** (`while` + `var`),
  and each function uses a **unique var-name prefix** (var-scope-leak history).
- `Int.toChar` works (int + JS); build strings via a fold of `cp.toChar.toString`.

## Non-goals (deferred, queued in BACKLOG)

- Wiring `pagerDeleteRebalanced` into the SQL engine's DELETE path (changing the live
  DML would shift many `scljet-sql-*` golden fingerprints — a separate, gated slice).
- Page **reuse** on INSERT (pulling a page off the freelist instead of allocating at
  EOF) and auto-vacuum pointer-map maintenance.
- SQLite's exact fill-factor redistribution heuristic (3-way sibling balance).

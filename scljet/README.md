# SclJet — a pure ScalaScript SQLite engine

SclJet is a self-contained, SQLite-format-compatible database engine written
**entirely in ScalaScript (`.ssc`)**. It has no dependency on JDBC, `sql.js`, or
native SQLite — everything from the 100-byte file header to record codecs, B-tree
traversal, the write path, and rollback-journal recovery is implemented from
scratch as portable code.

## Standalone and version-independent

This directory is the **standalone SclJet library** — deliberately not owned by
`v1/` or `v2/`. The same source runs on every ScalaScript tier: the v1
interpreter, the bytecode VM, the direct ASM JIT, the pure tree-walk fallback,
JavaScript (Node), and the v2 standard/native tier (`ssc run`).

It is consumed as the standard import `std/scljet/index.ssc`, resolved from this
directory as a **first-class library root** (no compatibility symlink). The
interpreter `ImportResolver` discovers this `scljet/` root and maps
`std/scljet/…` here; `installBin` stages it directly into
`bin/lib/native-front/runtime/std/scljet` (and `bin/lib/standard/native-front`),
so the native/JS loaders find it in the packaged tree. See
[`../specs/scljet-standalone-library.md`](../specs/scljet-standalone-library.md)
for the resolution design.

## Status

Read-only (M0–M2) and its hardening are complete on the interpreter/VM/ASM/JS
lanes: exact codecs, header/page/record decoders, pager and B-tree cursors,
`sqlite_schema`, and a corpus of 25 valid + 30 corrupt pinned SQLite files
verified byte-for-value against reference SQLite 3.53.3.

The M3 write path (`write.ssc`, `journal.ssc`) is in progress and byte-verified
against reference SQLite: `emptyDatabase`, the record encoder (`encodeRecord`,
all five value types incl. IEEE-754 reals), single- and multi-page rowid-table
writers (`buildSingleTableDatabase` / `buildTableDatabase`, producing files that
pass reference `PRAGMA integrity_check`), cell-overflow writers that spill large
payloads onto overflow-page chains — single-leaf (`buildOverflowTableDatabase`)
and multi-leaf over a table-interior root (`buildOverflowBtreeDatabase`), an
arbitrary-depth B-tree writer (`buildDeepTableDatabase`, stacking interior levels
to any depth — verified on a real 3-level tree), a combined deep-plus-overflow
writer (`buildDeepOverflowTableDatabase`, a 3-level tree whose oversized rows also
spill onto chains), a general explicit-rowid writer (`buildKeyedDatabase` — rows
keep their own gapped rowids across a rewrite), a multi-table writer
(`buildMultiTableDatabase` — several rowid tables in one file, each at its own root
page), an index writer (`buildTableWithIndexDatabase` — a table plus an index B-tree on
one or more integer/text columns, multi-leaf when needed, that reference SQLite
validates and uses for query planning), and the
rollback journal (`writeRollbackJournal` + hot-journal `applyRollbackJournal`,
byte-identical to SQLite's journal).

Full row-level **insert / delete / update** on an existing database works
end-to-end (`mutate.ssc`) — on single-table files, and on one table of a
multi-table file (`deleteRowidsInTable` / `updateRowInTable`, which rebuild every
table and reassign root pages, keeping the others' records byte-for-byte): `insertRow` / `deleteRowids` / `keepRowids` /
`updateRowValues` open the file read-only over its own bytes, read every surviving
row back as its raw record payload, and rebuild the table with the original
`sqlite_schema` record and rowids preserved. `insertRow` adds a row at an explicit
(ascending, non-duplicate) rowid; `updateRowValues` re-encodes only the changed
row (the caller supplies the new value, so no code-point→String is needed); the
untouched rows pass through as raw records. Delete/update on a table-plus-index
database keep the index consistent (`deleteRowidsIndexed` / `updateRowIndexed`
rebuild both trees from the surviving rows, so `integrity_check`'s index
cross-check still passes). Verified against reference `integrity_check` including
overflow rows, int==js.

Crash-safe in-place writes are transactional (`journal.ssc`). The core primitive
`writePagesJournaled` journals the pre-images of the pages about to change,
overwrites them in place, and returns the mutated database plus its rollback
journal — so a crash before commit is undone by `applyRollbackJournal`. On top of it
a **write transaction** (`beginTransaction` / `stagePage` / `commitTransaction` /
`rollbackTransaction`) batches several page writes and commits them atomically under
one journal, or discards them before commit.

**Write-ahead logging** is supported on both sides (`wal.ssc`). Writing: `writeWal`
serializes a `-wal` file — the 32-byte header and one frame per changed page, with
SQLite's two-word running frame checksums — and `markWalMode` flips the database
header into WAL mode. Reference SQLite 3.53.3 recovers the resulting `-wal`,
validates every checksum, reads the framed pages, and checkpoints them into the
main database; a single flipped checksum byte makes it reject the WAL, confirming
the checksums are byte-exact. Multi-frame transactions (last-frame-wins) verify too.
Reading: `readWal` recovers the committed frame map (word order per the magic's low
bit, so it reads both our and real SQLite's WALs), the pager overlays it so
`openReadonly`/cursors return WAL'd pages (page from the latest committed frame, else
the base file), and `checkpointWal` folds committed frames back into the base —
**byte-identical** to `PRAGMA wal_checkpoint(TRUNCATE)`.

Indexes stack to any depth (a 3-level index B-tree with promoted separators is
verified against reference `integrity_check`).

A **page-oriented mutable pager** (`journal.ssc` `openMutablePager` / `mutableGet` /
`mutablePut` / `mutableAllocate` / `mutableCommit` / `mutableRollback`) mutates a
database at page granularity without rewriting the whole file: staged dirty pages
read back before commit, `mutableCommit` journals and applies them atomically (the
journal recovers the pre-commit image, allocations included), and `mutableRollback`
discards.

**Cell-level in-place edits** (`write.ssc` `readLeafCells` / `leafInsertCell` /
`leafDeleteCell` / `leafUpdateCell` / `rebuildLeafPage`) change a single table-leaf
page's cells and, through the mutable pager, move only that page.

**Incremental B-tree `balance()`** (`pagerInsertBalanced` / `pagerDeleteBalanced`)
completes the write side: an in-place insert descends to the leaf and, on the way back
up, splits any overflowing node into sibling pages, hands the parent new dividers (an
interior split promotes a divider), and grows the tree a level at the root
(`balance_deeper`) with the root page number preserved — to any depth. Verified against
reference SQLite 3.53.3: repeated in-place inserts grow a one-leaf table through 2-level
and 3-level trees (depth 3 at 160 rows, 84 pages), every intermediate `integrity_check`
ok with exact ordered rows. `pagerDeleteBalanced` rewrites the target leaf (an underfull
leaf stays a valid B-tree). `pagerDeleteRebalanced` additionally **reclaims** emptied
pages onto the freelist (page-1 bytes 32..39 + trunk pages) and collapses an interior
that drops to one child (`balance_shallower` — root page kept), so the file gets pages
back without changing its length; verified against reference SQLite 3.53.3
(`integrity_check` ok, `freelist_count` matches, journal recovers the pre-image after a
merge). Wiring the reclaiming delete into the live SQL DELETE (with matching free-page
reuse on INSERT) is the remaining follow-up; see `specs/scljet-mutable-pager.md`.

## SQL

A SQL query layer (`sql.ssc`) sits on top of the storage engine. `queryImage(dbBytes, sql)`
lexes and parses `SELECT [DISTINCT] (*|cols) FROM table [JOIN t2 ON …] [WHERE col op literal]
[GROUP BY … [HAVING …]] [ORDER BY col[,…] [ASC|DESC]] [LIMIT n [OFFSET m]]` (comparators
`= <> != < > <= >=`, integer or `'string'` literals, the implicit `rowid`, and qualified `a.col`
names), resolves the columns from each table's `CREATE TABLE` text, cursors the rows, filters,
groups, stable-sorts, dedupes, applies offset/limit, and projects — returning result rows that
`renderRows` formats like the sqlite3 CLI. Every query is
byte-identical to reference sqlite3 running the same SQL on the same file.

Data-modifying SQL runs too: `executeMutation(dbBytes, sql)` handles `INSERT INTO t VALUES (…)`
(record encoded and inserted via the balanced write path, rowid = max + 1),
`DELETE FROM t [WHERE …]` (matching rows deleted through the pager), and
`UPDATE t SET col = literal [, …] [WHERE …]` (each matching row re-encoded and replaced at the
same rowid), returning the new database image — after a sequence of statements the table matches
sqlite3 running the same SQL, with `integrity_check` ok. SQL **CRUD is complete** (SELECT / INSERT / UPDATE / DELETE, including column-list inserts).
SELECT also does `ORDER BY` (multi-column, ASC/DESC) / `LIMIT` / `OFFSET`, aggregates
(`COUNT`/`SUM`/`MIN`/`MAX`/`AVG`/`TOTAL`), and `GROUP BY`. `CREATE TABLE t(…)` appends a table
(schema row + empty root) that reference sqlite reads and `.schema` shows. Inner joins
(`FROM a JOIN b ON a.x = b.y [WHERE …]`, nested-loop, qualified or bare columns) work too. Reals
render like the sqlite3 CLI. Remaining M5 follow-ups: `HAVING`, LEFT/OUTER and multi-table joins,
aggregates over a join, a page-1 schema split, and repeating-decimal `%.15g` parity.

## Modules

| File | Role |
|---|---|
| `bytes.ssc` | immutable `ByteSlice`, varints, endian read/write |
| `values.ssc` / `header.ssc` / `page.ssc` / `record.ssc` | SQLite value types, header, B-tree page, and record codecs |
| `freelist.ssc` / `btree.ssc` / `schema.ssc` | freelist/pointer-map validation, cursors, `sqlite_schema` |
| `pager.ssc` / `readonly.ssc` | SHARED-locked immutable pager and the read-only facade |
| `vfs.ssc` / `memory-vfs.ssc` / `jvm-vfs.ssc` | file-system abstraction (in-memory + real files) |
| `write.ssc` | M3 write path: header/record encoders, table and multi-page B-tree writers (incl. overflow, arbitrary depth, explicit rowids) |
| `journal.ssc` | rollback-journal write + hot-journal recovery + transactional in-place page write + write transactions |
| `wal.ssc` | write-ahead log: `-wal` frame writer + WAL-mode marker + reader (recover frame map, overlay read, checkpoint) |
| `mutate.ssc` | read-modify-rewrite: delete/keep rows in an existing single-table database |
| `sql.ssc` | SQL layer: lexer, `SELECT` parser, and executor over the read path |
| `index.ssc` | the module manifest re-exporting the public API |

The full specification and behavior gates are in
[`../specs/scljet.md`](../specs/scljet.md). Runnable examples live in
[`../examples/`](../examples/) (`scljet-bytes`, `scljet-readonly`,
`scljet-memory-vfs`, `scljet-write-empty`, `scljet-write-table`, `scljet-crud`,
`scljet-full`).
The JVM
real-file host adapter is the separate `v1/runtime/std/scljet-vfs-plugin/`; the
engine above `SqliteVfs` remains pure ScalaScript.

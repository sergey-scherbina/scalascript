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

It is consumed as the standard import `std/scljet/index.ssc`. For toolchain
resolution that path is served from `v1/runtime/std/scljet`, which is a
**symlink to this directory** — so the library lives here, standalone, while the
existing `std/`-import resolvers (the interpreter `ImportResolver`, the native/JS
loaders, and `installBin`'s bundling into both `bin/lib/native-front` and
`bin/lib/standard/native-front`) find it unchanged. See
[`../specs/scljet-standalone-library.md`](../specs/scljet-standalone-library.md)
for the follow-up that drops the compatibility symlink by giving the resolvers a
first-class library root.

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
keep their own gapped rowids across a rewrite), and the rollback journal
(`writeRollbackJournal` + hot-journal `applyRollbackJournal`, byte-identical to
SQLite's journal).

Full row-level **insert / delete / update** on an existing single-table database
works end-to-end (`mutate.ssc`): `insertRow` / `deleteRowids` / `keepRowids` /
`updateRowValues` open the file read-only over its own bytes, read every surviving
row back as its raw record payload, and rebuild the table with the original
`sqlite_schema` record and rowids preserved. `insertRow` adds a row at an explicit
(ascending, non-duplicate) rowid; `updateRowValues` re-encodes only the changed
row (the caller supplies the new value, so no code-point→String is needed); the
untouched rows pass through as raw records. Verified against reference
`integrity_check` including overflow rows, int==js. The mutable pager and true
in-place page mutation (m3e) remain.

## Modules

| File | Role |
|---|---|
| `bytes.ssc` | immutable `ByteSlice`, varints, endian read/write |
| `values.ssc` / `header.ssc` / `page.ssc` / `record.ssc` | SQLite value types, header, B-tree page, and record codecs |
| `freelist.ssc` / `btree.ssc` / `schema.ssc` | freelist/pointer-map validation, cursors, `sqlite_schema` |
| `pager.ssc` / `readonly.ssc` | SHARED-locked immutable pager and the read-only facade |
| `vfs.ssc` / `memory-vfs.ssc` / `jvm-vfs.ssc` | file-system abstraction (in-memory + real files) |
| `write.ssc` | M3 write path: header/record encoders, table and multi-page B-tree writers (incl. overflow, arbitrary depth, explicit rowids) |
| `journal.ssc` | rollback-journal write + hot-journal recovery |
| `mutate.ssc` | read-modify-rewrite: delete/keep rows in an existing single-table database |
| `index.ssc` | the module manifest re-exporting the public API |

The full specification and behavior gates are in
[`../specs/scljet.md`](../specs/scljet.md). Runnable examples live in
[`../examples/`](../examples/) (`scljet-bytes`, `scljet-readonly`,
`scljet-memory-vfs`, `scljet-write-empty`, `scljet-write-table`, `scljet-crud`).
The JVM
real-file host adapter is the separate `v1/runtime/std/scljet-vfs-plugin/`; the
engine above `SqliteVfs` remains pure ScalaScript.

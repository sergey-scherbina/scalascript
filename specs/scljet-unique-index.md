# SclJet `CREATE UNIQUE INDEX`

Status: implemented and verified
Owner: `scljet-unique-index-not-supported`
Parent contract: [`scljet.md`](scljet.md)

## Overview

SclJet accepts and enforces ordinary SQLite-compatible unique indexes. The
feature is deliberately one semantic slice: parsing `UNIQUE` without enforcing
it is forbidden because it would let SclJet write a database whose schema
promises uniqueness while its table contains duplicate keys. Such a file is
self-consistent to SclJet but fails reference SQLite's integrity contract.

This is a standard-library engine feature. It does not change ScalaScript's
language-level `sql` fence contract, so `SPEC.md` requires no change.

## Interface

The accepted statement form is:

```sql
CREATE [UNIQUE] INDEX index_name ON table_name (column_name [, column_name] ...)
```

`CreateIndexStmt` carries `unique: Boolean = false`. Stored index metadata
retains the original SQL text and carries the parsed uniqueness bit plus the
ordered key-column names/positions into index maintenance.

On a duplicate, mutation APIs return:

```text
UNIQUE constraint failed: <table>.<column>[, <table>.<column> ...]
```

The public pure-image APIs remain unchanged:

```scalascript
executeMutation(db, sql): Either[String, ByteSlice]
executeMutationCounted(db, sql): Either[String, MutationResult]
executeMutationCountedParams(db, sql, params): Either[String, MutationResult]
```

## Behavior

- [x] Both mutation dispatchers parse `CREATE INDEX` as non-unique and
      `CREATE UNIQUE INDEX` as unique; the existing non-unique form remains
      byte-for-behavior compatible.
- [x] Creating a unique index over pre-existing duplicate non-NULL keys fails
      before any schema page or index page is committed.
- [x] Creating a unique index over valid rows writes a reference-readable
      SQLite index, preserves the original `CREATE UNIQUE INDEX` schema text,
      and reports `NON_UNIQUE=false` through JDBC metadata.
- [x] INSERT and UPDATE reject a key that duplicates another row covered by
      any stored unique index; DELETE and non-conflicting mutations remain
      valid.
- [x] A failed CREATE/INSERT/UPDATE is atomic at the pure-image boundary: the
      caller retains the original image and no partial rebuilt image is
      returned.
- [x] Composite unique keys compare the ordered key tuple without the rowid
      tie-breaker and name every indexed column in the SQLite-shaped error.
- [x] If any component of a candidate key is NULL, that key does not conflict.
      Multiple `(1, NULL)` and `(NULL, "x")` rows are therefore legal, matching
      SQLite's distinct-NULL rule.
- [x] Equality follows the supported BINARY-index storage semantics: integer
      and real numeric equivalents conflict (`1` vs `1.0`), text compares by
      exact value, blobs compare byte-for-byte, and different non-numeric
      storage classes do not conflict.
- [x] The physical index-key ordering uses that same exact comparator: distinct
      REAL and BLOB keys remain ordered and a valid mixed-value unique index
      passes reference SQLite's `PRAGMA integrity_check`.
- [x] A conformance regression runs on the declared `int` and `js` lanes and
      covers CREATE, INSERT, UPDATE, composite keys, NULL, and non-unique
      compatibility.
- [x] A JVM `sqlite-jdbc` differential proves matching duplicate rejection at
      CREATE/INSERT/UPDATE and runs reference `PRAGMA integrity_check` on a
      successful SclJet-written file.
- [x] A runnable example and SclJet user documentation show the supported
      statement and duplicate diagnostic.

## Design

### Parsing and dispatch

`parseCreateIndex` consumes an optional `UNIQUE` token between `CREATE` and
`INDEX`. Both `executeMutation` and `executeMutationCountedParams` recognize
the same two-token prefix instead of routing `CREATE UNIQUE INDEX` to
`parseCreate`.

### Persisted index metadata

`sqlite_schema.sql` remains the source of truth for indexes already in an
image. `tableIndexInfos` parses that text into an internal descriptor carrying:

- the schema record used by compact rebuilds;
- ordered key-column positions and names;
- whether the index is unique.

No parallel metadata format or SclJet-only on-disk flag is introduced.

### Enforcement point

One validator checks a complete proposed row set against every unique index
descriptor before `buildSingleTableIndexed` builds table/index pages.
Consequently the same path protects index-maintaining INSERT and UPDATE.
`executeCreateIndex` applies the same validator to existing rows before it
stages the new index. The pure APIs return `Left` on failure, so no partial
image escapes.

The validator is intentionally independent of the rowid suffix stored in an
SQLite index record. The suffix makes duplicate index entries physically
orderable; it is not part of the declared uniqueness key.

The writer's index-key comparator is also the validator's equality source. It
orders integer/real pairs with SQLite's range-safe integer-versus-float rules,
TEXT by the existing BINARY order, and BLOB byte-for-byte. This avoids a split
between “duplicate” semantics and physical B-tree ordering. In particular, the
old comparator's fallback values (`REAL` as integer zero and all BLOBs equal)
cannot be retained: they can produce a reference-invalid index even when every
declared key is unique.

## Decisions

- **Enforce before writing any index pages** — chosen so parser acceptance can
  never create silently invalid SQLite files. Rejected: parser-only support or
  relying on a later `integrity_check`.
- **Re-parse canonical `sqlite_schema.sql`** — chosen because existing index
  rebuilds already derive their columns from it and JDBC metadata uses the
  same truth. Rejected: an extra SclJet-only metadata store.
- **Validate the proposed complete row set** — chosen because current indexed
  DML already performs a compact whole-table rebuild. Rejected: a separate
  incremental lookup path that would duplicate comparison semantics.
- **Share one exact key comparator** — chosen so uniqueness equality and B-tree
  ordering cannot disagree. Rejected: a second SQL-layer equality function
  beside the writer's physical ordering logic.
- **Match SQLite NULL semantics** — chosen for compatibility. Rejected: treating
  NULL as equal under a unique index.
- **Keep the existing indexed multi-table limitation** — this slice inherits
  the current explicit `index maintenance on a multi-table database is not yet
  supported` error instead of widening pager scope.

## Out of scope

- UNIQUE or PRIMARY KEY constraints inside `CREATE TABLE`.
- `ON CONFLICT`, `INSERT OR ...`, UPSERT, deferred constraints, and extended
  SQLite result codes.
- Expression, partial, descending, or explicit-collation indexes; the current
  parser continues to accept only plain named columns with BINARY semantics.
- Removing the current single-table restriction for indexed DML.
- Incremental index maintenance; compact rebuild remains the implementation.

## Verification

```text
tests/conformance/run.sh --only 'scljet-*' --no-memo
scripts/sbtc "scljetJdbcPlugin/test"
```

The differential must compare both engines' actual rejection and then ask the
reference engine to inspect the SclJet-written file. A same-engine write/read
round-trip alone is not evidence for uniqueness correctness.

## Results

- `tests/conformance/run.sh --only 'scljet-*' --no-memo`: **103/103** cases
  passed on 2026-07-22; every declared SclJet case was green on both `int` and
  `js`, including the new composite/NULL/numeric/BLOB/non-unique regression.
- `scripts/sbtc "scljetJdbcPlugin/test"`: **63/63** tests in 6 suites passed.
  `ScljetUniqueIndexTest` compares CREATE/INSERT/UPDATE failures against
  sqlite-jdbc, confirms `NON_UNIQUE=false`, and gets `ok` from reference
  `PRAGMA integrity_check` on a SclJet-written file with deliberately unsorted
  REAL/BLOB keys and distinct integer/real values above 2^53.
- `bin/ssc run examples/scljet-unique-index.ssc` and
  `bin/ssc-tools run-js examples/scljet-unique-index.ssc` produced the documented
  identical output, including
  `UNIQUE constraint failed: books.title, books.year`.

# SclJet production completion

Status: **active implementation contract**

Owner queue: `SPRINT.md` §“SclJet production completion”

Canonical semantics: [`scljet.md`](scljet.md)

This document turns the unchecked M3–M8 behavior gates in the canonical SclJet
specification into an executable completion program. It does not weaken those gates and
does not redefine “SQLite compatible” to mean “the current examples pass”.

## Starting point

The current engine is substantial and useful:

- byte, record, page, schema, B-tree, freelist, and overflow readers/writers are pure
  ScalaScript;
- balanced insertion and reclaiming deletion primitives produce SQLite-readable files;
- rollback-journal and WAL byte formats have target-neutral image helpers;
- the SQL evaluator implements a broad curated subset, including indexes, joins,
  aggregates, subqueries, parameters, typed builders, and a JDBC façade;
- 106 `scljet-*` conformance cases pass on the declared INT and JS lanes.

Those facts are the regression baseline, not the production verdict. The following
capabilities are not production-wired at the start of this program:

- VFS-backed rollback commit and hot-journal recovery;
- the standard shared-memory wal-index and concurrent WAL protocol;
- complete affinity, constraints, transaction SQL, prepared programs, and streaming
  result execution;
- the official statement/expression families named by canonical M6;
- concrete connection-local function/collation registries and virtual tables;
- an opt-in `scljet:` provider through `Db.*` and SQL fences;
- the full differential, fault, concurrency, fuzz, benchmark, and application-migration
  evidence required by M8.

## Authority and compatibility boundary

1. `specs/scljet.md` remains the normative semantic and file-format contract.
2. This document owns ordering, evidence, and the distinction between a helper and a
   production-wired capability.
3. SQLite 3.53.3 is the pinned file/SQL oracle unless the canonical spec is updated first.
   JVM API behavior is compared to the pinned Xerial sqlite-jdbc dependency.
4. StrictSqlite output must remain writable by reference SQLite and pass reference
   `PRAGMA integrity_check`.
5. Existing `sqlite:` behavior is not changed by this program. An opt-in `scljet:`
   provider may land after its connection contract is ready; replacing `sqlite:` needs a
   separate explicit user-approved cutover.
6. `scljet-address-uniml-u2-u3` and SclJet-triggered F/bytecode capacity work retain their
   existing foreign owners. Their status is reported, never duplicated here.

## Evidence vocabulary

Every capability is classified as exactly one of:

| State | Meaning |
|---|---|
| `implemented` | Production path exists and the named behavioral gate exercises it. |
| `helper-only` | A codec or pure image primitive exists, but no live protocol uses it. |
| `subset` | A public path exists for a documented subset; unsupported behavior fails honestly. |
| `open` | Required behavior is absent. |
| `external` | Required work has another active claim and is not duplicated. |
| `approval-gated` | Implementation may be prepared, but activation changes compatibility and needs the user. |

The machine-readable capability manifest added by SC-0 must use only these values, name
the source path and real gate for each row, and reject:

- an unknown state;
- an `implemented` row without a real gate;
- an `open` or `helper-only` row whose canonical M3–M8 checkbox is marked complete;
- a canonical M3–M8 behavior gate with no manifest row;
- a claimed cross-backend result without an explicit backend list.

The manifest is inventory, not an oracle. A static label never suppresses a comparison.
Tests always compute both sides first, print a useful mismatch, and only then classify the
case.

## Initial capability inventory

This inventory is normative for the first manifest. Later slices update the manifest in
the same bookkeeping commit that changes the capability.

| Capability | Initial state | Source/evidence boundary |
|---|---|---|
| Byte/page/record/B-tree read corpus | `implemented` | M1–M2 conformance and pinned valid/corrupt corpus |
| Balanced B-tree insertion | `implemented` | `pagerInsertBalanced`; reference integrity/read-back gates |
| Reclaiming deletion primitive | `helper-only` | `pagerDeleteRebalanced` isolated test; live SQL does not call it |
| Freelist reuse during insertion | `open` | allocation is EOF-only |
| Rollback-journal codec and image recovery | `helper-only` | byte/image inverse tests only |
| VFS rollback commit/hot recovery | `open` | pager open rejects a non-empty journal |
| WAL codec/read overlay/image checkpoint | `helper-only` | no shared-memory snapshot/lock protocol |
| Standard wal-index and concurrent WAL | `open` | no wal-index module or transaction/checkpoint state machine |
| Curated SQL evaluator | `subset` | current `scljet-sql-*` cases |
| Affinity and constraints | `open` | only rowid/IPK and explicit unique-index enforcement are partial foundations |
| Typed SQL | `subset` | `typedsql.ssc` and four conformance cases; stale pre-implementation label is false |
| Portable/JVM JDBC | `subset` | façade and shim exist; prepare reparses and rows materialize |
| Connection transaction SQL/savepoints | `open` | image staging is not the canonical VFS transaction protocol |
| Function/collation registries | `open` | interfaces exist without a concrete connection registry |
| Virtual tables | `open` | requires a separate approved spec |
| Standalone resolver without symlink | `open` | repo-root source exists; compatibility symlink still exists |
| UniML address integration | `external` | owned by `uniml-production-completion` |
| F/bytecode capacity for large SclJet programs | `external` | owned by `v2-f-bytecode-probe` |
| Opt-in `scljet:` provider | `open` | no `Db.*`/SQL-fence routing |
| Replace existing `sqlite:` provider | `approval-gated` | separate compatibility decision |
| Production evidence matrix | `open` | 106 curated cases are not the M8 matrix |

## Required implementation order

### SC-1 — IPK numeric affinity

`targetRowidOf` must implement SQLite-compatible conversion for an INTEGER PRIMARY KEY
assignment:

- `SqlInteger` is accepted directly;
- a finite `SqlReal` is accepted only when it is exactly integral and representable as a
  signed 64-bit integer;
- `SqlText` is accepted only when the entire trimmed decimal token is an optional sign
  followed by one or more digits and the result is representable as signed 64-bit;
- NULL, blob, fractional/non-finite real, malformed text, and overflow fail without
  mutating the image.

Leading-numeric-prefix JDBC getter conversion is not reusable here: affinity validates the
whole value. The gate covers signs, surrounding ASCII whitespace, `Long.MinValue`,
`Long.MaxValue`, one-step overflow on both ends, fractional values, scientific text, empty
text, indexed and unindexed tables, and collisions. The same statements run through
SclJet and reference sqlite-jdbc; resulting rows and integrity status are compared.

### SC-2 — reclaim and reuse

The live SQL DELETE path and the delete phase of UPDATE use reclaiming deletion. Any page
allocator used by B-tree split or root growth must consume a validated freelist leaf before
extending EOF.

Popping a free page is a pager operation, not a change to the integer returned by
`mutableAllocate`: it must stage the changed database header and trunk page together with
the reused page. Corrupt freelist pointers/counts fail closed. A delete/insert workload
must plateau in page count once sufficient free pages exist, and rollback recovery must
reconstruct the exact original image.

### SC-3 — schema semantics

A single parsed schema representation becomes the source of truth for:

- ordered columns and declared types;
- INTEGER PRIMARY KEY/rowid aliases;
- DEFAULT, NOT NULL, PRIMARY KEY, UNIQUE, CHECK, foreign-key, STRICT, and generated-column
  metadata;
- index expressions, predicates, sort direction, and collation.

SQL execution, typed SQL, JDBC metadata, and the planner consume this representation rather
than reparsing `CREATE TABLE` independently. Affinity and each constraint family land as
separate differential slices. A failed multi-row statement is atomic.

### SC-4 — prepare and cursor execution

Preparing produces an immutable parsed program with explicit parameter slots and the schema
cookie it depends on. Repeated execution binds values without tokenizing or parsing again.
A cookie change either reparses safely or returns the documented schema error.

A query cursor exposes `step`, current row, and close. Simple table/index/range paths stream
without constructing the full result list. Sort, group, window, DISTINCT, and joins may
retain an explicitly documented materialization boundary until their own streaming work.
Portable and JVM JDBC delegate to this cursor rather than wrapping an already-materialized
list.

### SC-5 and SC-6 — rollback transactions and connection state

The rollback protocol follows the ordering and lock transitions in the canonical spec:

1. acquire the required lock state;
2. create and populate the rollback journal;
3. sync the journal;
4. acquire EXCLUSIVE before changing database pages;
5. write/truncate and sync the database;
6. invalidate the journal according to DELETE, TRUNCATE, or PERSIST;
7. sync the containing directory when required;
8. downgrade/release locks and invalidate caches.

Open detects a hot journal, obtains the required locks, restores pre-images, truncates and
syncs the database, invalidates the journal, and retains the appropriate read lock. Fault
injection at every VFS operation must yield an old or new valid database after reopen.

The concrete engine/connection then owns autocommit, BEGIN modes, COMMIT, ROLLBACK,
SAVEPOINT, RELEASE, rollback-to, busy handling, read-your-writes, and statement atomicity.
SQL text and both JDBC façades share this state machine.

### SC-7 — WAL

Production WAL requires the SQLite wal-index in shared memory, including duplicate headers,
read marks, `nBackfill`, page-number/hash regions, native byte order, and the documented
WRITE/CKPT/RECOVER/read locks. Readers hold a stable snapshot. Writers append and publish a
commit only after WAL sync. Checkpoint modes honor reader boundaries and the required
WAL-before-backfill/database-after-copy sync order.

The pure `readWal`, overlay, and `checkpointWal` image functions remain useful test helpers,
but they are never evidence for concurrent WAL. A VFS without shared memory and shared-memory
locks rejects production WAL mode.

### SC-8 — SQL compatibility

SQL families land in dependency-sized commits. The support manifest names every implemented
statement/expression family and every intentional rejection. Required families include the
canonical M6 list: compound queries, CTEs, windows, RETURNING, transaction/PRAGMA statements,
views, triggers, ALTER, ATTACH/DETACH and super-journals, VACUUM, ANALYZE, REINDEX, and the
remaining index/expression forms.

Every family is tested live against SQLite with the same schema, input values, parameter
bindings, transaction boundary, and observable rows/errors. A checked-in golden may aid
cross-backend comparison, but cannot replace the live reference comparison.

### SC-9 — extensibility

The concrete connection owns scalar, aggregate, window, and collation registries. Registration
is connection-local, deterministic, and subject to trusted-schema and resource-limit rules.
Planner/index use of a collation is allowed only when the implementation and index declaration
match.

Virtual tables and table-valued functions require a separate approved specification before
implementation. The extended on-disk profile likewise requires explicit version negotiation,
migration, downgrade, and StrictSqlite refusal behavior.

### SC-10 — distribution and provider

Both self-hosted resolver variants must discover the repository-root/staged SclJet library
without `v1/runtime/std/scljet`. Removing the symlink is gated by:

- install/staging;
- the full SclJet INT+JS regression slice;
- a non-SclJet standard-library import;
- native/default execution;
- the stage-2 bootstrap manifest projection, which must stage repo-root `scljet/` as
  `runtime/std/scljet` without relying on `find -L` through the compatibility symlink;
- the v2.1 negative-toolchain release gate.

The opt-in `scljet:` provider is added only after connection transactions and prepare semantics
are stable. It routes `Db.*`, SQL fences, manifests, and host VFS capabilities through the same
engine; it never delegates to sqlite-jdbc or sql.js. Existing `sqlite:` remains unchanged.

### SC-11 — production closure

Production closure requires all of:

- live SQL differential tests, not only static output goldens;
- valid/corrupt/fuzz file-format corpus;
- rollback and WAL fault injection at every operation boundary;
- mixed SclJet/reference concurrency tests;
- explicit INT, VM, direct ASM, and JS identity coverage where the capability is portable;
- reproducible `scripts/bench` commands and baselines for prepare, scans, writes, commit,
  checkpoint, and contention;
- representative real-application migration tests;
- accurate README, user guide, examples, site, boards, and canonical checkboxes;
- the strongest available CI evidence reported under the repository evidence policy.

Only SC-11 may mark the aggregate canonical M3–M8 behavior boxes complete. A cancelled CI run
is red, a skipped backend is not parity, and the curated 106/106 regression slice is never
reported as full production completion.

## Behavior checklist

- [ ] SC-0 capability manifest is complete and fail-loud.
- [ ] SC-1 IPK numeric affinity matches SQLite.
- [ ] SC-2 live DML reclaims and safely reuses pages.
- [ ] SC-3 affinity and constraints share parsed schema semantics.
- [ ] SC-4 prepared programs and cursor execution are real.
- [ ] SC-5 VFS rollback commit/recovery passes exhaustive faults.
- [ ] SC-6 connection transactions/savepoints pass differential concurrency.
- [ ] SC-7 standard WAL/wal-index passes mixed-implementation concurrency.
- [ ] SC-8 canonical SQL families are implemented or honestly rejected by profile.
- [ ] SC-9 extensibility/security and approved extension specs are implemented.
- [ ] SC-10 standalone distribution and opt-in provider gates pass.
- [ ] SC-11 production evidence closes canonical M3–M8.

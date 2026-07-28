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
| Balanced B-tree insertion | `subset` | Image path is correct for local non-overflow cells; production storage boundaries remain |
| Reclaiming deletion primitive | `helper-only` | `pagerDeleteRebalanced` isolated test; live SQL does not call it |
| Freelist reuse during insertion | `open` | allocation is EOF-only |
| Rollback-journal codec and image recovery | `helper-only` | byte/image inverse tests only |
| VFS rollback commit/hot recovery | `open` | pager open rejects a non-empty journal |
| WAL codec/read overlay/image checkpoint | `helper-only` | no shared-memory snapshot/lock protocol |
| Standard wal-index and concurrent WAL | `open` | no wal-index module or transaction/checkpoint state machine |
| Curated SQL evaluator | `subset` | current `scljet-sql-*` cases |
| Affinity and constraints | `subset` | portable IPK affinity is gated; scalar semantics, the JVM oracle, and general constraints remain open |
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

One shared rowid coercion must implement SQLite-compatible conversion for an INTEGER
PRIMARY KEY value on both INSERT and UPDATE:

- `SqlInteger` is accepted directly;
- `SqlReal` follows SQLite's binary64 `MustBeInt` decision: the value must be finite,
  integral after conversion to signed 64-bit, strictly greater than binary64
  `Long.MinValue`, strictly less than binary64 `2^63`, and byte-for-value equal after
  converting the resulting integer back to binary64. Consequently an integer
  `Long.MinValue` is legal while the numerically equal REAL is a datatype mismatch;
- `SqlText` consumes the whole token after trimming SQLite ASCII whitespace
  (`0x09..0x0d`, `0x20`). A sign plus decimal digits with no point or exponent uses exact
  signed-64 parsing and therefore accepts both integer endpoints. Decimal-point and
  exponent forms use SQLite's binary64 conversion followed by the same `MustBeInt`
  decision as `SqlReal`;
- NULL, blob, fractional/non-finite real, malformed text, and overflow fail without
  mutating the image.

Only an actual SQL NULL requests automatic rowid assignment on INSERT. A malformed or
non-integral explicit value never silently becomes an automatic rowid.

Automatic assignment uses the greatest existing rowid, even when every rowid is negative:
an empty table starts at 1 and otherwise uses `max + 1`. If the greatest rowid is
`Long.MaxValue`, it chooses an unused positive candidate under SQLite's bounded fallback
rule; it never wraps to `Long.MinValue`.

Leading-numeric-prefix JDBC getter conversion is not reusable here: affinity validates the
whole value. The gate covers signs, surrounding ASCII whitespace, decimal/exponent forms,
`Long.MinValue`, `Long.MaxValue`, one-step overflow, underflow to zero, and floating-point
rounding boundaries, fractional values, hexadecimal/malformed/empty text, indexed and
unindexed tables, and collisions. The oracle explicitly pins SQLite's conversion behavior
rather than mathematical decimal exactness: for example `9007199254740993.0` becomes the
accepted rowid `9007199254740992`, while a decimal form that rounds to positive `2^63` and
the REAL/decimal form of `Long.MinValue` are rejected. The same statements run through
SclJet and reference sqlite-jdbc; resulting rows and integrity status are compared.

Bound `SqliteValue` cases isolate rowid coercion from SQL tokenization. Direct SQL literals
also have a fail-closed SC-1 gate: decimal-integer lexing detects overflow instead of
wrapping, and every mutation parser must consume its complete token stream before writing.
The remaining signed `VALUES`, exponent, and hexadecimal source-literal grammar lands with
SC-8; until then an unsupported form returns a syntax error without mutating any row.

Portable SC-1a landed in `a00db1967`, and the live JDBC/file closure landed in
`b39127f61`. The compare-first
`scljet-ipk-numeric-affinity` and `scljet-mutation-complete-parse` gates pass on
INT and JS, including exact unary `Long.MinValue`, invalid-later-row atomicity,
indexed INSERT/UPDATE, and structurally incomplete DDL. The live
`ScljetIpkAffinityDifferentialTest` runs each named JVM binding through both
`jdbc:scljet:` and pinned Xerial sqlite-jdbc, distinguishes prepare/bind/execute
failures from update counts, then compares rows before classifying the reference
expectation. It covers `setDouble`/`setFloat`/boxed-object NaN-to-NULL behavior,
all SQLite ASCII numeric whitespace, collisions, indexed and unindexed paths,
empty/negative/`Long.MaxValue` auto-rowid boundaries, and occupied positive
fallback candidates. After closing SclJet it reopens the file with reference
SQLite, compares persisted rows, and requires `PRAGMA integrity_check = ok`.
Together with the existing bidirectional IPK/file suite, the post-rebase live
result is 14/14; the affected portable result is 2/2 on both INT and JS.
SC-1a is therefore closed, while the deliberately separate signed/exponent/hex
source-literal grammar remains an honest SC-8 item.

Before schema work builds on it, scalar value semantics also need live differential gates:

- comparisons with NULL and IN/NOT IN propagate SQL UNKNOWN rather than treating
  `NULL == NULL` as true;
- INTEGER/REAL comparison remains exact above 2^53 and at signed-64 boundaries;
- BLOB comparison is bytewise and does not treat all same-class blobs as equal;
- the same comparator semantics drive filtering, ordering, DISTINCT, grouping, and index
  behavior.

Portable SC-1b landed in `b63206552`, and correlated predicate closure landed
in `71d8a6f0e`. The compare-first `scljet-sql-null-semantics`,
`scljet-sql-correlated-join`, and `scljet-correlated-subquery-errors` gates pass
on INT and JS across scalar and simple CASE evaluation, WHERE/HAVING/ON
boundaries, empty and NULL-bearing IN/NOT IN, non-correlated and correlated
subqueries, two- and N-table joins, indexed residual filtering, and
UPDATE/DELETE. Every visible joined table is available to the correlated
subquery, NULL-extended rows stay visible, a directly named inner table shadows
the outer binding, and an explicit error channel plus unconditional structural
preflight prevents empty inputs or index misses from swallowing malformed or
missing subqueries. Non-correlated subquery substitution preserves exact
NULL/REAL/BLOB values and maps an empty scalar result to NULL. The complete
post-change `scljet-sql-* --no-memo` sweep is 57/57.

Live comparison landed in `3f5d8f6c1` and was expanded in `71d8a6f0e`. The
seven-test `ScljetScalarSemanticsDifferentialTest` runs statements
independently through `jdbc:scljet:` and Xerial sqlite-jdbc 3.45.3.0
(embedding SQLite 3.45.3), compares outcomes before classification, and covers
scalar, scan, join, subquery, CASE/HAVING, LIMIT, indexed residual, real
indexed/unindexed UPDATE/DELETE, and correlated multi-table paths. Its new
correlated test has nine named outcomes, retains prepare/bind/execute phase and
semantic error category without requiring unstable vendor message text, and
confirms that both drivers reject the named malformed and missing-table cases
during prepare. It then reopens the SclJet file through Xerial, reruns
persisted queries, verifies expected index names, and requires
`PRAGMA integrity_check = ok`. The live result is SC-1b 5/5 plus SC-1c 2/2,
or 7/7 for the complete suite.

The capability remains `subset`. Subqueries in
ON/HAVING/projection/ORDER, bare outer references and aliases, complete
recursive prepare-time scope/unknown-column resolution, and compiled/cached
preparation remain open; the current validator reparses a typed-NULL form
against the current transaction image rather than producing the full SC-4a
prepared plan.

Correlated DML landed in `8172f60f1`, with canonical physical-NULL-IPK DELETE
normalization in `483bef42b`. UPDATE and DELETE use the same database-aware
TRUE/FALSE/UNKNOWN plus explicit-error evaluation as SELECT, bind the mutation
target as the outer table, and derive both the write set and `changes()` from
one selected-rowid result. EXISTS, IN, and scalar forms each affect the
SQLite-3.51.0-pinned row, while a missing or malformed inner SELECT remains an
error for empty outer input and for a short-circuited rowid miss. The
fourteen-line compare-first `scljet-correlated-dml` gate passes on INT and JS,
including a record whose INTEGER PRIMARY KEY field is physically NULL.

Nested correlated scope closure landed in `4c6045266`. A table declared by an
inner SELECT shadows an equally named outer binding only within that SELECT
scope, while a differently named inner query may still reference a visible
grandparent table. Correlation detection and exact-value substitution now walk
an accumulated per-branch scope stack rather than applying one flat
immediate-table set to every nested token. The twelve-line
`scljet-sql-correlated-join` gate passes on INT and JS; EXISTS cases exercise
substitution and IN cases directly exercise scoped correlation detection.
Alias support remains SC-8 grammar work and is not conflated with scope
resolution.

Portable SC-1c landed in `f36f951ba`. SQL now reuses the physical index
comparator instead of converting INTEGER values to binary64 or collapsing
same-class BLOB values. The SQLite-3.51.0-pinned
`scljet-sql-value-compare` gate passes on INT and JS across relational
filtering, mixed INTEGER/REAL signed-64 and 2^53 boundaries, bytewise BLOB
equality/order, ORDER BY, DISTINCT, GROUP BY, two-table JOIN, and indexed
predicates. The post-change `scljet-sql-* --no-memo` sweep is 57/57. The
live test from `3f5d8f6c1` additionally compares exact signed-64/2^53
INTEGER/REAL boundaries and bytewise BLOB equality/order through unindexed and
persisted-index paths, including aggregate, DISTINCT, JOIN, and ordering
consumers. Its Xerial reopen and integrity checks pass, so
`scalar-numeric-blob-comparison` is `implemented`; its live SC-1c result is 2/2.
Multi-level numeric/BLOB divider stress and long-BLOB performance remain
explicit non-blocking SC-11 qualification work rather than semantic gaps.

The three oracle identities are deliberately separate: portable expectations
record SQLite 3.51.0 behavior, the repository's current Xerial dependency
is sqlite-jdbc 3.45.3.0 embedding SQLite core 3.45.3 for live JVM
differentials, and the canonical file/SQL oracle remains SQLite 3.53.3. A green
result from one pin is never relabelled as evidence from another.

### SC-2 — reclaim and reuse

The live SQL DELETE path and the delete phase of UPDATE use reclaiming deletion. Any page
allocator used by B-tree split or root growth must consume a validated freelist leaf before
extending a separately tracked physical EOF.

Popping a free page is a pager operation, not a change to the integer returned by
`mutableAllocate`: it must stage the changed database header and trunk page together with
the reused page. Corrupt freelist pointers/counts fail closed. A delete/insert workload
must plateau in page count once sufficient free pages exist, and rollback recovery must
reconstruct the exact original image.

#### SC-2a.1 — live reclaim and mutable freelist validation

The first independently shippable slice changes both live SQL mutation routes from
`pagerDeleteBalanced` to `pagerDeleteRebalanced`: DELETE uses it for every selected rowid,
and UPDATE uses it for the complete delete phase before reinserting any replacement row.
On the established 512-byte, 24-row split-tree fixture, `DELETE ... WHERE id <= 20` must
therefore keep the physical and header page counts at 14, report 20 changes, leave ordered
rowids 21 through 24, retain an interior root, and place exactly 10 pages on the freelist.
The matching no-op UPDATE must report 20 changes, retain all ordered rows, and expose
reclaimed pages even before reuse lands. Its temporary EOF size is not a final golden:
SC-2a.2 must consume those pages and remove that growth.

Reclaim reads the freelist from the `MutablePager` staged image, so a public pager helper
cannot rely on the read-only open path having validated the base file. The mutable reader
returns `Either[ByteError, List[Int]]` and rejects, without staging any change:

- page-size mismatch, a short page/header/trunk, or a caller page size different from the
  pager page size;
- a head/count disagreement, a graph whose exact page count differs from the header, or a
  count beyond the staged logical page bound;
- page 1, zero, an out-of-range page, or the lock-byte page in any trunk/leaf role;
- an excessive leaf count, a cycle, a repeated trunk or leaf, or trunk/leaf overlap;
- non-zero reserved bytes or auto-vacuum until SC-2b implements usable-size and pointer-map
  maintenance.

All unsigned page numbers remain `Long` until after bounds validation. The reader uses one
global seen set and validates the exact graph before `applyFreelist` combines it with newly
freed pages. Newly freed pages receive the same bounds and uniqueness checks, including
overlap with the existing graph. Validation also occurs when a delete frees no page, so
direct staged-helper callers cannot bypass it through the root-leaf path or an empty
`freedRev`. A second reclaim before commit must read the first reclaim's staged header and
trunk pages.

The permanent gates are split by observable:

- `scljet-sql-live-reclaim` proves the real DELETE and UPDATE routes on INT and JS;
- `scljet-freelist-write-corrupt` proves valid staged read-your-writes plus head/count,
  range, role, count, cycle, duplication, truncation, reserved-byte, auto-vacuum, and
  newly-freed-page failures. Every negative case must return `Left` before commit and leave
  the input pager image and pending list unchanged.

#### SC-2a.2 — checked reuse before physical EOF

`mutableAllocate` keeps its existing physical-EOF meaning. A separate error-aware pager
allocator validates the staged freelist, consumes a leaf first (or the head trunk when it
has no leaves), decrements the header count, stages the owning trunk/header mutation, and
returns the page number together with the updated pager. Only an empty validated graph
falls back to EOF. Leaf splits, interior splits, and `balance_deeper` all request pages
through that allocator. The database page-count header grows only for actual EOF pages;
reuse keeps it flat. A mixed reclaim/insert workload must exercise both sibling split and
root growth, decrease `freelist_count`, plateau `page_count`, pass the pinned reference
`integrity_check`, and recover its exact pre-image through the rollback journal.

This first reclaim/reuse gate is not storage completion. Canonical M3 additionally requires:

- allocating and freeing overflow chains for incremental large TEXT/BLOB DML;
- using `usableSize`, not raw page size, on reserved-byte databases;
- bumping the database change counter and version-valid-for header fields on commit;
- correct indexed multi-table DML rather than whole-image/single-table restrictions;
- maintaining auto-vacuum pointer maps or rejecting that mode explicitly and safely.

Each boundary is tested against a reference-created file. A green small-row,
reserved-byte-zero page-plateau test cannot close M3.

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

The target-neutral planner lowers parsed statements through explicit logical and physical
plan nodes. Its affinity/collation-aware access choices always retain a correct full-scan
alternative, and `EXPLAIN QUERY PLAN` has stable, tested output.

Execution uses the canonical immutable register/cursor program rather than treating the
direct AST evaluator as the final VM. Program validation bounds registers and jumps, rejects
unsafe backward jumps, and supports interruption and resource limits.

A query cursor exposes `step`, current row, and close. Simple table/index/range paths stream
without constructing the full result list. Sort, group, window, DISTINCT, and joins may
retain an explicitly documented materialization boundary until their own streaming work.
Portable and JVM JDBC delegate to this cursor rather than wrapping an already-materialized
list.

### SC-5 and SC-6 — rollback transactions and connection state

Normal rollback writers follow the lock progression
SHARED→RESERVED→PENDING→EXCLUSIVE. Hot recovery has a distinct
SHARED/PENDING→EXCLUSIVE path that must not advertise RESERVED ownership. Both MemoryVFS and
the JVM VFS must be able to express these transitions.

Before live recovery, the journal codec must handle real multi-header/large journals,
checked arithmetic and sizes, partial headers/record suffixes, and zero-extension when a
crash truncated the database below its original size. The deterministic fault apparatus
must model reordering, capacity/disk-full, device characteristics, and sector size in
addition to simple error/short/crash effects.

The rollback commit protocol follows the ordering in the canonical spec:

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

Implementation is split and gated in this order: lock/journal/fault hardening; open-time hot
recovery; DELETE-mode commit; TRUNCATE/PERSIST; exhaustive fault and reference-contention
closure.

### SC-7 — WAL

Production WAL first needs real golden fixtures and codec hardening: frames may grow the
database beyond base EOF, a committed WAL page 1 defines the effective database header/schema
for that snapshot, and page-size mismatch fails at open.

The SQLite wal-index in shared memory includes duplicate headers, read marks, `nBackfill`,
page-number/hash regions, native byte order, and the documented WRITE/CKPT/RECOVER/read
locks. A failed shared→exclusive upgrade under external contention must restore the prior OS
shared lock. Readers hold a stable snapshot. Writers append and publish a commit only after
WAL sync. Checkpoint modes honor reader boundaries and the required
WAL-before-backfill/database-after-copy sync order.

The pure `readWal`, overlay, and `checkpointWal` image functions remain useful test helpers,
but they are never evidence for concurrent WAL. A VFS without shared memory and shared-memory
locks rejects production WAL mode.

WAL lands in separately falsifiable stages: fixture/codec hardening; wal-index
codec/recovery; SHM capability and lock behavior; snapshot reader; append writer;
PASSIVE/FULL/RESTART/TRUNCATE checkpoint/reset; then exhaustive crash and mixed-process
concurrency.

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
- [ ] SC-1 rowid coercion and scalar value/NULL semantics match SQLite.
- [ ] SC-2 live DML reclaims/reuses pages and closes incremental storage boundaries.
- [ ] SC-3 affinity and constraints share parsed schema semantics.
- [ ] SC-4 prepared programs, planner/EXPLAIN, execution VM, and cursors are real.
- [ ] SC-5 VFS rollback commit/recovery passes exhaustive faults.
- [ ] SC-6 connection transactions/savepoints pass differential concurrency.
- [ ] SC-7 standard WAL/wal-index passes mixed-implementation concurrency.
- [ ] SC-8 canonical SQL families are implemented or honestly rejected by profile.
- [ ] SC-9 extensibility/security and approved extension specs are implemented.
- [ ] SC-10 standalone distribution and opt-in provider gates pass.
- [ ] SC-11 production evidence closes canonical M3–M8.

# SclJet — backlog

Can-wait and not-yet-started work whose code lives in `scljet/`. When an item is
picked up it moves to `scljet/SPRINT.md` as `[~]` and gets a row on the root board —
in the same commit as the claim. Layout: `specs/work-tracking-layout.md`.

Sections below were carried over whole from the flat root `SPRINT.md`/`BACKLOG.md`,
verbatim, on 2026-07-30.

## 2026-07-27 — SclJet production completion (Sergiy: "Реализуй всё по scljet")

**Claim: UNCLAIMED** — released 2026-07-28 in triage (heartbeat 1.8 h stale, no live
process).

> **Before reclaiming SC-2, look at branch `feature/scljet-production-completion`
> commit `ee382f3f5` — it already contains a candidate implementation, UNVERIFIED.**
> The released session's six finished commits are on main (`d36acd322`, re-measured
> green before pushing: `scljet-sql-live-reclaim` and `scljet-freelist-write-corrupt`
> both PASS on INT+JS). Its uncommitted SC-2 work is *not* on main and *not* thrown
> away: freelist validation helpers plus `pagerDeleteRebalanced`, with
> `deleteRowidLoop`/`applyUpdates` repointed at it, committed to the branch so a
> checkout cannot destroy it.
>
> It is mid-slice **by construction**: the slice's own landed expectation
> `expected/scljet-sql-live-reclaim.txt` pins the PRE-fix behaviour (`update
> pages=25 … freelist=10` — the file grows, nothing is reused). If the candidate
> works, that expectation has to move with it, and it is untouched. Run
> `scripts/conformance tests/conformance --only 'scljet-*' --no-memo` first and
> decide from the diff. No measurement was taken during triage: the conformance
> semaphore was held by another live agent's full-corpus run.

This is the one resume-cold entry point for
the remaining SclJet program. The current **106/106** `scljet-*` conformance sweep proves the
landed curated subset on INT+JS; it does **not** close the unchecked M3–M8 behavior gates in
`specs/scljet.md`. In particular, the repository has correct pure image codecs/primitives for
rollback journals and WAL, but not yet a VFS-backed rollback transaction state machine or the
standard shared-memory wal-index/concurrent WAL protocol. The SQL evaluator is broad, but it is
not the full official grammar/constraint/prepare/streaming contract.

Foreign work stays foreign: `scljet-address-uniml-u2-u3` belongs to the active
`uniml-production-completion` claim, and SclJet-triggered F/bytecode capacity work belongs to
`v2-f-bytecode-probe`. Do not duplicate either. The standalone-library self-hosted resolver and
same-JVM reference-lock bridge need paths temporarily owned by other live claims; wait for those
claims to release, then widen this claim through `scripts/coord-claim`/the overlap guard before
editing. Provider replacement of the existing `sqlite:` id remains a separate explicit user
approval per the canonical spec; this program may build and prove an opt-in `scljet:` provider
without silently changing existing applications.

Execution order is dependency order. Every numbered slice is independently shippable: update the
behavior spec first, add a fail-first real/differential gate, implement, run the affected
`scljet-*` conformance slice plus the relevant JVM/e2e suite, push immediately, and record the
strongest CI evidence actually available.

- [ ] **SC-0 — executable completion contract and truthful inventory.** Add
      `specs/scljet-production-completion.md` as the bridge from the canonical M3–M8 gates to
      concrete source/test observables. Record what is already implemented vs image-only vs
      production-wired; define the live sqlite3/sqlite-jdbc oracle matrix and no-proxy/no-silent-
      fallback rules. Reconcile stale facts without claiming completion: portable text projection
      is landed; typed SQL and JDBC are implemented subsets despite stale "pre-implementation"
      headers; the standalone library still has a compatibility symlink; M3/M5 transaction gates
      remain open. Gate: a support-manifest script/test fails on an unclassified SQL/storage/
      provider capability instead of inferring green from the existing 106 cases.

- [ ] **SC-1 — foundational SQL value correctness.**
  - [ ] **SC-1a — IPK numeric affinity.** Fix `BUGS.md`
        `scljet-ipk-update-numeric-affinity` through one rowid coercion used by INSERT and UPDATE:
        accept SQLite's exact-integer TEXT fast path and its binary64 `MustBeInt` behavior for
        `SqlReal` plus decimal/exponent TEXT. This deliberately accepts reference rounding inside
        range (`9007199254740993.0` → `9007199254740992`) while rejecting REAL/decimal
        `Long.MinValue`, positive `2^63`, fractional, malformed, hexadecimal, and non-finite
        values without changing the image. Only a real SQL NULL means auto-rowid on INSERT.
        Allocate from the true signed maximum (negative-only tables included), and use an unused
        positive fallback after `Long.MaxValue` rather than wrapping. Make decimal integer lexing
        overflow-aware and require complete mutation-token consumption, so unsupported exponent/
        hex forms fail before mutation until SC-8 implements them. Extend
        `scljet-update-ipk-moves-rowid` and the sqlite-jdbc differential in both directions; verify
        `integrity_check`, collisions, and indexed-table ordering after the move. This is the first
        code slice.
  - [ ] **SC-1b — three-valued predicates.** Reproduce the static `NULL = NULL` and
        `NOT IN (..., NULL)` findings in the real harness, add `BUGS.md` entries if confirmed, then
        make scalar comparison/IN/NOT IN propagate UNKNOWN exactly like SQLite. Reproduction on
        assembled INT+JS confirms both plus NULL loss in correlated/non-correlated subqueries;
        an index can mask the broken predicate, so gate unindexed and indexed paths with a live
        differential `scljet-sql-null-semantics` case rather than the existing IS-NULL-only test.
  - [ ] **SC-1c — exact value comparison.** Reproduce precision loss above 2^53 and same-class BLOB
        equality in the real harness, record confirmed bugs, then make INTEGER/REAL and BLOB
        comparisons exact and shared by filtering, ordering, DISTINCT, grouping, joins, and index
        semantics. Both are confirmed on the assembled harness; reuse the already-correct physical
        comparator in `scljet/write.ssc` and gate `scljet-sql-value-compare` on INT+JS against live
        SQLite.

- [ ] **SC-2 — reclaiming live DML plus safe freelist reuse.** Turn the already-tested
  - [ ] **SC-2a — reclaim/reuse.** Turn `pagerDeleteRebalanced` into live DELETE/UPDATE behavior
        and make insert/split/root allocation consume a checked free-page pool before the separate
        physical EOF cursor. Consuming a page stages freelist trunks and database-header bytes
        atomically; corrupt freelists fail closed. Mixed DML must plateau in page count, decrease
        freelist count on reuse, retain ordered INT+JS rows, pass reference `integrity_check`, and
        recover the exact pre-image.
  - [ ] **SC-2b — incremental storage completeness.** Add overflow allocation/freeing for large
        TEXT/BLOB cells, reserved-byte usable-size accounting, change-counter/version-valid-for
        header updates, indexed multi-table DML, and auto-vacuum pointer-map maintenance or an
        explicit fail-closed refusal. Gate each boundary with large/reserved/indexed/auto-vacuum
        reference files; small reserved=0 rows alone cannot close canonical M3.

- [ ] **SC-3 — schema metadata, affinity, and constraints foundation.** Parse declared columns and
      table constraints once into a target-neutral schema model shared by SQL, typed SQL, JDBC
      metadata, and the planner. Land separately gated sub-slices for INSERT/UPDATE affinity;
      DEFAULT + NOT NULL; column/table PRIMARY KEY and UNIQUE; CHECK; conflict actions/UPSERT;
      foreign keys; STRICT/generated columns. Every accepted/rejected mutation is differentially
      checked against the pinned SQLite oracle, including rollback of partial multi-row failures.

- [ ] **SC-4 — compiled planning, execution VM, and true cursor execution.**
  - [ ] **SC-4a — compiled prepare.** Introduce immutable prepared-select/prepared-mutation
        programs with numbered parameter slots, schema-cookie invalidation/reprepare, and no
        tokenize/parse on each execute.
  - [ ] **SC-4b — target-neutral planner and EXPLAIN.** Lower parsed statements to explicit logical
        and physical plan nodes; make affinity/collation-aware access choices and expose stable
        `EXPLAIN QUERY PLAN`, always differential against the correct full-scan plan.
  - [ ] **SC-4c — immutable execution program and cursor.** Add the canonical bounded
        register/cursor VM with checked backward jumps, interruption, and limits, then a real
        `QueryCursor/step` over simple scans/seeks. Adapt portable/JVM ResultSet to it; explicitly
        materialize only operators that require it until their own streaming slices land.
        Gate parse counts, schema changes, bind fidelity, plan identity, close/interrupt/limits,
        and row identity against the existing evaluator plus SQLite.

- [ ] **SC-5 — real rollback-mode VFS transactions and recovery.** Replace image-only claims with
  - [ ] **SC-5a — lock semantics, journal hardening, and fault apparatus.** Support normal
        SHARED→RESERVED→PENDING→EXCLUSIVE writers and hot recovery's separate
        SHARED/PENDING→EXCLUSIVE path without RESERVED. Harden multi-header/large/truncated journal
        parsing, checked sizes/arithmetic and zero-extension. Add reorder/capacity/device controls
        to the MemoryVFS fault model.
  - [ ] **SC-5b — open-time hot recovery.** Restore/truncate/sync/invalidate through the VFS while
        holding the recovery locks; cover partial journal headers/suffixes and a database already
        truncated below its original size with JVM real fixtures.
  - [ ] **SC-5c — DELETE-mode commit.** Implement journal create/write/sync → database
        write/truncate/sync → journal delete + directory sync, cache invalidation, and lock release.
  - [ ] **SC-5d — TRUNCATE/PERSIST modes.** Implement their distinct invalidation and sync rules;
        do not alias them to DELETE in either behavior or tests.
  - [ ] **SC-5e — exhaustive recovery evidence.** Inject every VFS failure/crash ordinal and run
        mixed reference-SQLite cross-process contention. Reopen must be exactly old or new, never
        mixed.

- [ ] **SC-6 — connection transactions, SQL transaction statements, and savepoints.** Build the
      concrete connection/engine state over SC-5; implement BEGIN modes, COMMIT, ROLLBACK,
      SAVEPOINT, RELEASE, rollback-to, autocommit/read-your-writes, busy timeout, and statement
      atomicity. Wire portable JDBC, the JVM driver, and SQL text to the same state machine.
      Differential/concurrency gates cover two handles, schema changes, failed statements,
      nested savepoints, and close cleanup.

- [ ] **SC-7 — standard WAL and wal-index.**
  - [ ] **SC-7a — real fixtures and codec hardening.** Compare real WAL files/checksums, support
        growth beyond base EOF, derive the effective header/schema from WAL page 1, and reject page-
        size mismatches at open.
  - [ ] **SC-7b — wal-index codec/recovery.** Implement native-endian duplicate headers, hash/page
        regions, read marks, `nBackfill`, checksum validation, and recovery.
  - [ ] **SC-7c — honest SHM capability/locking.** Reject WAL when SHM/locks are unavailable; fix
        shared→exclusive upgrade so failed external contention restores the OS shared lock.
  - [ ] **SC-7d — stable snapshot reader.** Acquire a read mark and keep page/header/schema views
        on one committed snapshot.
  - [ ] **SC-7e — append writer.** Implement WRITE locking, frame append/checksum/sync, commit
        publication, salt/reset, busy handling, and cleanup.
  - [ ] **SC-7f — checkpoint modes.** Implement PASSIVE/FULL/RESTART/TRUNCATE, reader boundaries,
        `nBackfill`, reset, and required sync order.
  - [ ] **SC-7g — crash/concurrency closure.** Run deterministic crash points and mixed
        SclJet/reference readers, writer, and checkpointer across processes. Pure image overlay
        helpers stay classified as helpers, never production-WAL evidence.

- [ ] **SC-8 — complete the official SQL families.** Close the canonical M4/M6 grammar/semantics
      matrix in dependency-sized commits: compound SELECT; CTE/recursive CTE; window clauses and
      frames; RETURNING; transaction/PRAGMA statements; CREATE VIEW/TRIGGER; ALTER; ATTACH/DETACH
      and super-journal coordination; VACUUM; ANALYZE; REINDEX; expression/partial/collated indexes;
      signed `VALUES` and complete decimal/exponent/hex source-number grammar; remaining expression
      and statement families. Keep TEXT numeric affinity distinct from source hex literals. Each
      family needs live sqlite3 differential fixtures, NULL/affinity/collation edge cases, prepared
      and transaction coverage, and a user-facing example when it adds an API pattern.

- [ ] **SC-9 — extensibility and security.** Implement connection-local scalar, aggregate, window,
      and collation registries through the existing public interfaces, with deterministic error
      propagation, planner/index collation correctness, trusted-schema restrictions, and resource
      limits. Write and approve a separate virtual-table/table-valued-function spec before that
      subsystem. Specify and implement extended-profile version negotiation, migration, downgrade,
      and strict-profile refusal; never let extensions silently alter StrictSqlite files.

- [ ] **SC-10 — standalone distribution and opt-in provider integration.** After conflicting claims
      release, teach both self-hosted `sscStdRoot` variants the repo-root SclJet library, remove the
      compatibility symlink, and run install/native/INT+JS/non-SclJet/v21-negative gates. Then wire
      the concrete opt-in `scljet:` engine through `Db.*`, SQL fences, manifests, and host VFS
      plugins without changing `sqlite:`. Document exact durability/concurrency capabilities and
      ship runnable file/transaction/WAL/typed/JDBC examples. The existing provider may be replaced
      only by a later explicit user-approved compatibility cutover.

- [ ] **SC-11 — production evidence and milestone closure.** Put the pinned SQL differential,
      file-format corpus, corruption fuzz, operation-boundary fault matrix, rollback/WAL concurrency,
      and cross-backend identity suites in CI with fail-loud backend markers. Add reproducible
      `scripts/bench` cases/baselines for parse/prepare, point/range/index scan, writes, commit,
      checkpoint, and mixed contention; validate representative real application migrations.
      Only then check the canonical M3–M8 behavior boxes, reconcile SPRINT/BACKLOG/CHANGELOG/README/
      docs/site, and state the strongest exact-SHA/job/local evidence. Cancelled CI is red; curated
      106/106 alone is never called production completion.

---

## scljet-on-default-command — make `bin/ssc run` actually run the engine (2026-07-17, Sergiy: "Заводи и берись. Исправь")

**DONE 2026-07-17** (`46f09ad29` charAt, `3b0ddea92` toLong, `59843958c`+ bugs). Sergiy asked
whether SclJet is real or a fiction; it is real — proven byte-identical against the **reference
`sqlite3` 3.51.0 in both directions through a file** (it reads sqlite3's files; sqlite3 reads ours,
`PRAGMA integrity_check` = ok, and even writes into them). But it did not run on the **default**
`bin/ssc run` at all, which for a "platform for data wherever it lives" is not a detail.

**Result: 7 of 9 `examples/scljet-*` now run on `bin/ssc run` (was 0); 23/24 sampled scljet
conformance cases pass there; `contract.sc --lanes v2` green, one closed gap recorded
(`scljet-crud v2 FAIL` removed from the baseline — that row is now the regression alarm).**
Existing lanes untouched: scljet conformance 98/98 `[int, js]`.

### The two fixes

1. **`charAt(i).toString` yields the char CODE on v2-native** (`v2-native-charAt-toString-yields-code`).
   `upperStr("INSERT")` → `"737883698284"`, so `isKw` never matched and the engine did not recognise
   its own SQL keywords (`executeMutation expects INSERT, …`). ROOT: `charAt` lowers to the `scodeAt`
   prim (the code point) because **v2 has no Char box by design**. Lowercase input survived because
   that branch already used `.toChar.toString` — which is why it hid for so long. FIXED engine-side
   with the portable idiom `charCode(s, i).toChar.toString` (correct on BOTH lanes, symmetric with
   the neighbouring branch), 9 sites in `sql.ssc`. Engine-side ON PURPOSE: without a Char box no
   lowering can satisfy both `charAt(i).toString` and `charCode = charAt(i).toInt`.
   **The language-level divergence stays OPEN** — any other `.ssc` using the idiom is silently wrong.
2. **`Double.toLong` was a no-op** (`v2-native-double-toLong-noop`). The lowering erased it on the
   reasoning in its own comment — "Long IS Int here" — true for an Int receiver, false for a Double.
   Routed to the shared method table like `toInt`/`toDouble`. The flagged risk (Int.toLong survives
   only BECAUSE the lowering erases it) was **measured** before landing, not assumed.

### Remaining (filed, not this task)
- [ ] **`v2-native-jvmvfs-externs-unbound`** — the last blocker: host-file scljet on the default
      command. `jvmVfs*` come from a **v1-style** plugin (ServiceLoader); the native tier runs its own
      `NativePluginHost` over `v2/runtime/std`. Needs a port to the v2 native plugin SPI (like
      `content-plugin`), NOT a one-liner. Until then `bin/ssc run` scljet = in-memory only.
- [ ] `String.toLong` on v2-native: wrong before (`421`, string concat) and wrong after
      (`<closure>1`) — pre-existing, not introduced; `String.toInt` works. scljet unaffected
      (all its `.toLong` receivers are Ints).
- [ ] `scljet-jdbc-basic` on v2-native: "native frontend rejected incomplete parse" — a front gap.

### METHOD — two traps that cost real time, both worth remembering
- **A checkout's `bin/` can be stale.** I reported a `StackOverflowError` to Sergiy from the shared
  main checkout's binary; a freshly `installBin`-ed worktree showed the real error. The overflow
  does not exist on current main. AGENTS.md's "reproduce in the real harness" applies to the LANE'S
  BINARY too — rebuild before believing a v2-native failure.
- **`installBin` COPIES the engine into `bin/lib/native-front/runtime/std/`.** Editing `scljet/*.ssc`
  changes nothing until you re-run it. (Known as "edit tower SOURCES not bin/lib copies".)
- **`contract.sc --update-baseline --lanes v2` is DESTRUCTIVE**: it rewrites the whole baseline from
  the lanes you ran, silently deleting every `js`/`int` row (156 → 121 here). Either run all lanes or
  hand-edit the rows you actually closed.

## scljet-address-uniml — the address leaves SQLite (2026-07-17, Sergiy)

**Goal.** The first format beyond SQLite: `read address → (type, value)` over a JSON/YAML/XML/
Markdown document. Same model, same triple, **different resolver** — the shape
`specs/scljet-address.md` promised.

### Feasibility — MEASURED, and it decides the design

- **UniML gives the physical half for free.** Its canonical tree is a lossless CST where EVERY node
  carries a `SourceSpan(source, start(offset,line,column), end)`. That is exactly "where the bits
  are", and it makes the `Raw(n)` floor total by construction: `n = end.offset − start.offset` is
  always available, for any node, understood or not.
- **The projection is NOT enough — walk the CST.** `JsonValue` (the semantic projection) carries a
  span only on `JsonMember`; array elements have none. Addressing `users/0/name` through the
  projection would silently lose the physical half of `users/0`. This is the spec's own rule biting
  in practice: the CST is canonical, the projection is optional.
- **JSON CST shape** (`JsonStructure.scala`): `Branch("json.object"|"json.array", edges, span)`;
  edge roles `member.key` / `member.colon` / `member.value` / `member.separator` / `array.element` /
  `document.value`; scalars are `Token(SourceToken(kind="json.string"|"json.number"|"json.true"|
  "json.false"|"json.null", lexeme, span))`.
- **`unimlJson` is in the MAIN build and cross-compiles to JVM + JS** (`build.sbt:599`), so a
  resolver over it is not JVM-only in principle.
- **The .ssc bridge is a plugin, not an import.** UniML's surface is Scala (`scalascript.uniml`) and
  its DIALECTS are not dual-compilable to `.ssc` yet (`uniml-portable-1c-compat`: plain classes,
  regex, `java.lang.Character`; the immutable core does compile on v2). So `.ssc` reaches it the
  same way it reaches host files: an extern from a plugin, exactly like `jvmVfs*`.
  **Verified, not assumed:** `jsonParse` (what `.ssc` has today) returns a value with **no spans** —
  it cannot serve the physical half at all.

### Slices

- [x] **U1 — the resolver. DONE 2026-07-17** (`df807d3d7`, `v1/lang/uniml-address`, 8/8). `JsonAddress.read(text, path) → Either[String, …]`
      `JsonAddress.read(text, path)` over the UniML **CST** (not the projection — it spans only
      members, so an array element would lose its physical half). Types are the format's own; values
      are the lexeme as written; the physical half is asserted by slicing the SOURCE at the reported
      offset/length. Model proven on format #2 before any plumbing.
      - **Stability earned its keep.** Key = name = stable; array index = POSITION = not stable, and
        everything beneath inherits it (a stable key under a positional index is still positional).
        The test does not assert a flag — it inserts a sibling and watches `users/0/name` go from
        `ann` to `zoe` while `active` stays put. Same distinction as IPK vs a plain rowid.
- [ ] **U2 — the bridge.** v1 plugin + v2 native plugin exposing the resolver as an extern; a shared
      zero-dep resolver module both use. Same shape as the VFS port (`6131e17a3`) — including the
      trap that `bin/lib/standard/jars` is an explicit allowlist (`standardJarPrefixes`).
- [ ] **U3 — `.ssc` surface + gates.** `addressReadDoc(text, path)` in `scljet/address.ssc`;
      conformance; and the differential that matters here — the same document read by an
      independent JSON tool, so the oracle is not ourselves.

### Note on `AddressedValue`
Its `fromRowid` field is SQLite-flavoured. It generalises honestly ("the logical value did not come
from the physical bits at this address") but the NAME does not. Rename when the second resolver
lands, not before — renaming a shipped field on a guess is how specs rot.

## scljet — pure ScalaScript SQLite-compatible engine specification (2026-07-12, Sergiy: "сделать ... чистую низкоуровневую реализацию формата данных ... блокировками и wal ... sql интерпретатора"; name: "scljet")

Goal: establish a real pure-ScalaScript module boundary and a normative, implementation-ready
specification for an independently implemented SQLite-compatible storage engine. This is not the
existing JDBC/sql.js adapter: the codec, pager, B-trees, journaling, WAL reader/writer, SQL parser,
planner, evaluator, and function registry are ScalaScript code; only the abstract VFS capability
touches a host filesystem. Compatibility is pinned to SQLite 3 file format and observable behavior,
with extensions isolated behind an explicit non-default profile.

- [ ] **scljet-typed-sql-api** — FUTURE (idea, 2026-07-14, Sergiy: "typed SQL API — которое
      компилируется непосредственно в план выполнения операций над BTree деревьями базы данных …
      занести в спринт и потом написать спецификацию когда начнем"). A typed, embedded query API
      (ScalaScript values, not SQL strings) that lowers **directly to a physical execution plan over
      the database B-trees** — no runtime SQL-string parse. Envisioned shape:
      - **Typed relations & columns.** A table is described by a typed schema (column name → SQL type);
        queries are built from typed column/table values so column references, comparisons, projections
        and aggregates are checked at ScalaScript compile time (ill-typed predicates don't compile).
      - **Query algebra → logical plan.** Typed combinators (`from`/`where`/`select`/`join`/`groupBy`/
        `orderBy`/`limit`, plus `insert`/`update`/`delete`) build a logical plan; the current
        string SQL front end (`sql.ssc`) becomes one *optional* parser that produces the same plan.
      - **Physical plan over B-trees.** A planner lowers the logical plan to explicit B-tree operations:
        `SeekRowid`, `RangeScan(lo,hi)`, `FullScan`, `IndexSeek`/`IndexRangeScan` (once CREATE INDEX
        lands), `Filter`, `Project`, `Aggregate`, `Sort`, `NestedLoopJoin`, and the write ops
        `InsertCell`/`DeleteCell`/`UpdateCell` with `Balance`. Plan nodes operate on the existing
        pager/cursor/`pagerInsertBalanced` layer — reusing the storage engine already built (M1–M5).
      - **Why:** compile-time safety, zero string-parse at query time, index-aware access paths
        (rowid/range/index seeks instead of always full-scanning), and a clean seam for a cost-based
        planner later. The reused evaluator is the `SxNode`/`evalExpr` expression layer.
      - **SPEC DONE 2026-07-15** — `specs/scljet-typed-sql.md` (644 lines, on origin/main cb94fd88c):
        typed surface (`Table[R]`/`Column[T]`/`Expr[T]`/`Predicate`/`Projection` erasing to the existing
        `SxNode`/`Condition`), a `LogicalPlan` algebra, a `PhysicalOp` IR (SeekRowid/RangeScan/FullScan/
        IndexSeek/IndexRangeScan/Filter/Project/Aggregate/Sort/NestedLoopJoin + InsertCell/DeleteCell/
        UpdateCell/Balance) pinned to real function names, a staged T0–T7 plan, and a 3-oracle
        differential test plan. Key spec findings: SELECT never uses an index yet (`executeSelectSingle`
        always full-scans); cursors are forward-only and need a new `cursorSeek`/`cursorSeekGe` primitive
        (reuse write-layer `descendToLeaf`/`chooseChild`) with a FullScan fallback until seeks are
        equivalence-tested.
      - **TYPED SURFACE DONE 2026-07-15** — `scljet/typedsql.ssc` (`2dfc2c939`, conformance
        `scljet-typedsql-basic`). `Column[T]`/`Expr[T]` carry a compile-time SQL type (ill-typed
        predicate → compile error; ssc generics verified to erase cleanly on both backends). Combinators
        `from`/`whereQ`/`selectCols`/`selectStar`/`groupByQ`/`orderByQ`/`limitQ`/`distinctQ`,
        `eqp`/`nep`/`ltp`/`lep`/`gtp`/`gep`/`betweenp`/`inp`/`likep`/`isNullp`/`andp`/`orp` (crossAnd
        distribution), `projCol`/`countStar`/`sumCol`/`min`/`max`/`avgCol` erase to the existing
        `SxNode`/`Condition`/`ProjItem`/`SelectStmt` and run through the same executor via a new additive
        `runSelectStmt`. Verified byte-identical to reference sqlite3 for the equivalent SQL on `[int,
        js]`. **Deviation from spec ordering:** I built the typed *surface* first (the user-facing
        deliverable, additive/low-risk) rather than the spec's T0 IR-adapter (routing `queryImage`
        through a new physical IR — internal refactor, regression risk, no user value). The phantom
        row-record type `Table[R]`/`Query[R]` + typed row decoder is deferred (Table/TypedQuery are
        non-generic here; safety lives on `Column[T]`/`Expr[T]`).
      - **NEXT:** typed INSERT/UPDATE/DELETE builders; joins (`joinOn`/`leftJoinOn`) + HAVING in the
        typed surface (executor already supports them); then the spec's `LogicalPlan`/`PhysicalOp` IR
        with index-aware access paths (`cursorSeek`/`RangeScan`/`IndexSeek`) — the actual perf win over
        the current always-full-scan `executeSelectSingle`. Depends on: CREATE INDEX (done).

- [ ] **scljet-jdbc-api** — FUTURE (idea, 2026-07-14, Sergiy: "нужно чтобы было API для работы с
      базой через JDBC"). A JDBC-shaped API so scljet can be driven through the standard
      `Connection`/`Statement`/`PreparedStatement`/`ResultSet` surface (JVM primarily; a portable
      façade with the same method names for int/js where a real `java.sql.*` isn't available).
      Envisioned shape:
      - **Driver + Connection.** `jdbc:scljet:<path>` URL → open the file through the scljet VFS/pager;
        `Connection` owns the write transaction (autocommit on/off → the journal/WAL commit already
        built), `commit`/`rollback` map to `mutableCommit`/`mutableRollback`.
      - **Statement / PreparedStatement.** `executeQuery(sql)` runs `queryImage` and wraps rows in a
        `ResultSet`; `executeUpdate(sql)` runs `executeMutation` and returns the affected-row count.
        Prepared statements bind `?` parameters to `SqliteValue`s (reuse the lexer/parser; a bound
        param becomes an `SxLit`), so no string interpolation.
      - **ResultSet.** Forward cursor over the result rows with `getInt`/`getLong`/`getString`/
        `getDouble`/`getObject`/`wasNull` mapping `SqliteValue` → JDBC types; `ResultSetMetaData` from the
        projection/`CREATE TABLE` columns.
      - **Why:** lets existing JVM tools, connection pools, and ORMs talk to a scljet file with no code
        changes, and gives a familiar imperative API next to the (future) typed SQL API and the SQL
        string front end — three front doors onto the same executor/storage engine.
      - **SPEC DONE 2026-07-15** — `specs/scljet-jdbc.md` (~430 lines, on origin/main f2d1372a0):
        `jdbc:scljet:<path>` URL grammar → `SqliteOpenOptions`; a two-lane split (a real
        `java.sql.Driver` shim in `runtime/std/scljet-jdbc-plugin/` + a portable pure `scljet/jdbc.ssc`
        façade for `[int, js]`); Connection transaction threading over the read-modify-rewrite whole-image
        model (autocommit/`commit`/`rollback` → `mutableCommit`/`mutableRollback`); the `?`-param
        mechanism (a new `?` lexer token + `Token.bound` + a `bindParams` pass → bound param becomes an
        `SxLit`, zero string interpolation); the forward-only `ResultSet` getter↔`SqliteValue`↔
        `java.sql.Types` map, `ResultSetMetaData` derivation, the supported-vs-`SQLFeatureNotSupportedException`
        subset, error→SQLState mapping, and a `[int, js]` conformance plan vs `sqlite3`/`sqlite-jdbc`.
      - **Two required engine additions flagged by the spec (do first in J1):** (1) `executeMutation`
        returns only `Either[String, ByteSlice]` with **no** affected-row count → add a counted variant
        `executeMutationCounted → MutationResult(image, changes, lastInsertRowid)`; (2) the lexer has no
        `?` handling → add the additive `Token.bound` field + `bindParams`/`parsePrimary`/`litValue`
        hooks. `runtime/std/scljet` is a symlink to repo-root `scljet/`, so the pure façade lands at
        `scljet/jdbc.ssc` (imported `std/scljet/jdbc.ssc`).
      - **J1 CORE DONE 2026-07-15** — the two engine additions landed: `executeMutationCounted`/
        `…Params` → `MutationResult(image, changes, lastInsertRowid)` (`30fd8fb33`), and `?`-parameter
        binding (a `param` lexer token + defaulted `Token.bound` field + a `bindParams` pass →
        `SxLit`; `queryImageParams`/`executeMutationCountedParams`) (`5a64800c7`). The portable façade
        `scljet/jdbc.ssc` (`25ea1023e`) implements `JdbcConnection` (image + autocommit/working-image;
        commit/rollback), `jdbcExecuteUpdate*` (threads image, returns count + last rowid),
        `jdbcExecuteQuery*` → forward-only `JdbcResultSet` with typed getters (getLong/Int/Double/String/
        Boolean, isNull, by-index + by-label) and metadata labels (projItemNames / imageTableColumns);
        value getters route through coerceText + Double/Int string-parse to stay BigInt-safe on JS.
        Verified end-to-end vs reference sqlite3 on `[int, js]` (conformance `scljet-jdbc-basic`,
        `scljet-sql-mutation-count`, `scljet-sql-params`). Also fixed a prereq the façade exposed:
        REAL number literals in SQL text (`555033aa4`, conformance `scljet-sql-real-literal`).
      - **J2 SHIM DONE 2026-07-15** — landed as `scljet-m7b-jvm-jdbc-shim` (see its entry below): the
        stateful JVM `java.sql.Driver`/`Connection`/`Statement`/`PreparedStatement`/`ResultSet` shim in
        `v1/runtime/std/scljet-jdbc-plugin/` (true mutable `next()`/`wasNull()`, `jdbc:scljet:` URL,
        `java.sql.Types` metadata). The earlier "NEXT (J2+)" list is stale: blob getters (`getBytes`),
        `getBigDecimal` and `ResultSetMetaData` column types all landed WITH m7b (`ScljetResultSet.scala`
        `getBytes`/`getBigDecimal`/`columnTypeNames`) — do not redo them.
      - **J2 HARDENING DONE 2026-07-15** (`scljet-jdbc-j2`; `c96d51f35` feat, `296c71eed` feat,
        `4b63441e6` spec). All four slices landed; `sbt scljetJdbcPlugin/test` **29/29** (was 14/14),
        engine untouched (`scljet/*.ssc` is the `scljet-m3-writes` lane).
        - **J2.1 static ResultSet** — `ScljetStaticResultSet.scala`: forward-only/read-only cursor over
          JVM-materialized `(columns, rows)` (null cell = SQL NULL) + its `ResultSetMetaData`.
          `ScljetResultSet` can only serve rows the ENGINE produced, so the generated key and the JDBC
          catalog shapes (which exist nowhere in the engine) needed their own substrate.
        - **J2.2 `getGeneratedKeys`** — the rowid already crossed the bridge (`JdbcUpdate.lastInsertRowid`
          → `executeUpdate` returns `(changes, rowid)`); every call site discarded it (`val (c, _)`).
          `StatementState.runUpdate/runQuery` is now the SINGLE write/read path for Statement +
          PreparedStatement + batch (3 duplicated copies before, each dropping the rowid). Key tracked
          for INSERT/REPLACE with `changes > 0` only. Semantics PROBED from Xerial, not guessed: one
          column `last_insert_rowid()`, one row after INSERT; EMPTY after CREATE/UPDATE/DELETE/SELECT and
          on a fresh statement. Deviation: Xerial labels the EMPTY case `1` (placeholder artifact) — we
          keep the stable label.
        - **J2.3 `getTables`/`getColumns`** (+ `getTableTypes`, empty `getCatalogs`/`getSchemas`) —
          `ScljetCatalog.scala`. **The spec's plan (a `SELECT` over `sqlite_schema`) does NOT work**:
          `findTable` only resolves entries WITH a `rootPage`, and the schema table is not an entry in its
          own list. Instead: `openReadonly(ImageVfs(image), …)` → `db.schema.entries` read structurally,
          columns parsed from `CREATE TABLE` with the ENGINE's own `tokenize` (mirrors `tableColumns`) —
          a test asserts the names equal the engine's `imageTableColumns` so the two cannot drift.
          Affinity → `java.sql.Types`. Reads the WORKING image → catalog read-your-writes.
        - **J2.4 durability/locking notes** — `specs/scljet-jdbc.md`. Found the spec DESCRIBED A MODEL
          THAT WAS NEVER BUILT (journaled writes via `jvmSqliteVfs()`/`journal.ssc`, "real locking and
          durability"). Reality: whole-file `Files.write` per durable change, no journal/fsync/locking.
          Documented as a property table + an explicit single-writer/single-process/non-crash-durable
          contract + the `TRANSACTION_SERIALIZABLE` scope limit.
        **GOTCHAS (each cost a debugging round; all recorded in-code):**
        - **`globalsView` exposes ALL transitively loaded engine globals**, not just the bootstrap's
          import list (`openReadonly`/`sqlOptions`/`tokenize`/`ImageVfs` are all callable without touching
          `ScljetEngine.bootstrap*`). This is why `call("byteSliceUnsafe", …)` already worked.
        - **`ImageVfs` must be `InstanceV("ImageVfs", Map("main" -> image))`.** `Value.singleValue` hardcodes
          the field name `value`, but the field is `main` — and a missing field does NOT fail loudly: the
          interpreter falls back to the GLOBAL `main`, dying deep inside as
          `No method 'length' on NativeFnV(<native:main>)`. A hand-built `InstanceV` DOES dispatch trait
          methods (that was the open risk; spike settled it).
        - **`Option` is `Value.OptionV(inner|null)`, NOT `InstanceV("Some"/"None")`** — matching the latter
          silently yields `None` (it emptied every `getColumns` row).
        - **`Token(kind, text, num)` keeps numeric literals in `num` with `text` EMPTY** (kind `"num"`), so
          `VARCHAR(255)` renders as `VARCHAR()` unless read from `num`.
        - **Driver registration was order-dependent** (fixed): `Class.forName("…ScljetDriver")` inits the
          CLASS, but `registerDriver` lives in the companion OBJECT, and DriverManager's ServiceLoader scan
          does not see the plugin under sbt. Registration only happened because the FIRST test does
          `new ScljetDriver().acceptsURL` (which reads `ScljetDriver.Prefix` → inits the object), so
          `testOnly … -- -z "<one test>"` failed with "No suitable driver found" for ANY single test.
      - **NEXT (J4+), not started:**
        - [ ] **Journaled host-file writes** — route `commit()` through `jvmSqliteVfs()` +
              `scljet/journal.ssc` so a host file is crash-atomic + `fsync`ed, and honour the
              `journal`/`sync`/`busy_timeout`/`vfs` URL params that `getPropertyInfo` ADVERTISES BUT
              `openTarget` IGNORES today (only `mode`/`page_size` are read). Blocked on a
              Connection-level `MutablePager` (Model B, spec "Open decisions" #4): journaling needs to know
              WHICH PAGES changed, and the executor only yields whole images. Cross-lane (engine + shim).
        - [ ] **Inter-process locking** for host files (`FileLock` + `busy_timeout`). Until then the
              single-writer/single-process contract in the spec stands.
        - [x] **`scljet-jdbc-j4-introspection` DONE 2026-07-15** (`1abc60fd7` feat, `8389b6a56` bug,
              `10208987b` spec). `getPrimaryKeys` (both spellings — column-level AND table-level
              `PRIMARY KEY (a,b)` incl. named `CONSTRAINT pk`; KEY_SEQ = declared key order),
              `getIndexInfo` (one row per index column; UNIQUE + key list parsed from `CREATE INDEX`
              with the engine's `tokenize`), `getTypeInfo`, and EMPTY (not `nse`)
              `getImportedKeys`/`getExportedKeys`/`getCrossReference`. `sbt scljetJdbcPlugin/test`
              **42/42** (was 29/29). Reworked `parseTable` — table-level constraints were dropped
              wholesale, so a table-level PK was invisible.
              **Deviations from the reference, asserted so they cannot rot:** (1) `getIndexInfo(unique=
              true)` FILTERS per the contract — Xerial ignores the flag (bug); a test asserts the
              reference still misbehaves so a future fix tells us. (2) `getTypeInfo` reports
              INTEGER→BIGINT/REAL→DOUBLE (ours) not Xerial's INTEGER/REAL — a test pins it against
              `getColumns`.
              **GOTCHA:** comparing `getObject` across drivers compares BOXING, not data
              (`NON_UNIQUE` = our `Boolean` vs Xerial's `Integer 1`, both correct via `getBoolean`) —
              cross-check with typed getters.
              **ENGINE GAPS this surfaced (m3-writes lane, NOT the shim):**
              - **`CREATE UNIQUE INDEX` is not parsed at all** — `parseCreateIndex` requires
                `CREATE INDEX`; `CREATE UNIQUE INDEX` falls through to `parseCreate` → "expected
                TABLE" (`scljet/sql.ssc:4658`). So NO scljet-created database can hold a unique
                index. The unique path is tested instead against a file written by the reference
                driver and opened via `jdbc:scljet:<file>` (which also proves catalog introspection
                works on real SQLite files).
              - **`BUGS.md` → `scljet-ipk-rowid-alias-not-substituted` (OPEN, silent wrong data):**
                an `INTEGER PRIMARY KEY` column in a REAL SQLite file reads back as `0` — the rowid
                alias is not substituted. scljet reading its OWN db is fine, which is why no existing
                test sees it: an oracle of "read back what we wrote" cannot catch a file-format
                divergence; the differential must cross both engines through a FILE. Suspected
                opposite direction too (our writes store the IPK value in the column with a
                sequential rowid → real SQLite would read OUR files wrong). Pinned by a test that
                flips loudly when fixed.
        - [ ] **ENGINE GAP found via a J2 test (for the `scljet-m3-writes` lane, NOT this one):**
              `INSERT INTO t SELECT …` does not parse ("expected VALUES"). Blocks testing the
              "INSERT affecting 0 rows generates no key" branch, and it is a real SQL hole.

- [x] **scljet-0-plan-and-spec** — DONE 2026-07-12. Created `specs/scljet.md`
      after reconciling `SPEC.md`,
      existing SQL runtimes, and the official SQLite file/WAL/VFS/locking/SQL contracts. Specify the
      public API, module layout, byte codec and record format, pager/cache/B-tree/freelist/overflow,
      rollback and WAL transaction protocols, abstract random-access/locking/shared-memory VFS,
      SQL parser/planner/VM, manifest typing and collations, external scalar/aggregate/window
      functions, errors/limits/security, differential/crash/concurrency tests, staged milestones,
      compatibility profiles, rejected alternatives, and explicit open decisions.
      The spec is self-contained, its M0-M8 behavior gates are testable, and no global language
      invariant needs changing. Decisions: pure core + synchronous VFS, strict/extended profiles,
      opt-in `scljet:` during development, private VDBE-inspired VM, pinned SQLite 3.53.0
      differential oracle. Public identity `scljet` is fixed; three non-blocking product choices
      remain explicit in the spec.
- [x] **scljet-1-module-scaffold** — DONE 2026-07-12 (`449cfab0f`). Created the pure `.ssc` module at `runtime/std/scljet/` with
      manifest/aggregator plus target-neutral public value, error, option, connection, prepared
      statement, VFS, random-access file, lock, shared-memory, and function-registry contracts.
      Do not add platform types or core intrinsics; future host adapters must live in std plugins.
      Imports resolve and the scaffold matches the spec without claiming an implemented engine;
      the module/package/provider identity is `scljet`.
- [x] **scljet-2-verify-and-record** — DONE 2026-07-12. Parsed/typechecked the new module with the native ScalaScript
      lane, run the affected conformance slice (or add a focused interface-only case if needed),
      verify links/manifests and spec behavior checkboxes, then record the result in the spec,
      `CHANGELOG.md`, and this section. `scripts/sbtc "installBin"` staged 108 std modules;
      affected conformance is 1/1; native VM and direct ASM both print the exact six-line expected
      result. No performance, durability, SQL, or file-format claim is made before a working pager.

### SclJet M1 — bytes, codecs, and VFS foundations (completed 2026-07-12; explicit JS parity follow-ups remain in BUGS.md)

- [x] **scljet-m1a-api-spec** — DONE 2026-07-12. Updated `specs/scljet.md` before implementation with the exact
      `ByteSlice` construction/index/slice/copy API, unsigned/signed endian codec surface, SQLite
      varint error/consumption contract, immutable in-memory VFS state transitions, fault-script
      semantics, and the JVM host-adapter boundary. Resolve the M0 `List[Int]` placeholder without
      leaking host buffers. Chosen representation: immutable 64-byte chunk table with shared
      slice windows; varint failure consumes nothing; memory VFS is a replayable immutable state
      machine; JVM locking combines a process-local coordinator with OS byte-range locks. The list
      adapter and operations are top-level pure functions. Native imports currently lose extension
      receiver types (`row []`) and link real case-class methods as `Stub`; the functional surface is
      the common executable contract until receiver operations are portable.
- [x] **scljet-m1b-bytes-codec** — DONE 2026-07-12 in `58d2e19de` (docs/example
      `3aeb22068`). Added validated immutable 64-byte chunks with shared slice windows, functional
      get/update/slice/concat/copy/zero-extend operations, big/little-endian 16/32/64-bit codecs,
      signed reads, and canonical SQLite 1–9 byte varints. Golden tests cover malformed input,
      bounds, the 63/64 chunk boundary, exact vectors, and 11-value round trips. After
      `scripts/sbtc "installBin"`, `tests/conformance/run.sh --only 'scljet-*' --no-memo` is 2/2;
      native VM and direct ASM outputs exactly diff-equal the 31-line codec golden. The runnable
      `examples/scljet-bytes.ssc` is identical on v1/native VM/ASM.
- [x] **scljet-m1c-memory-vfs** — DONE 2026-07-12 in `e6d027b92` (docs/example
      `ef9816597`). Added a pure immutable transition model for canonical identity, random I/O,
      truncate/delete/close, durable sync/crash snapshots, rollback locks, eight WAL SHM locks,
      shared regions/barriers/unmap, deterministic clock/PRNG, ordered trace, and one-shot
      error/short-read/short-write/crash rules. `VfsRead`/`VfsWrite` honestly carry initialized
      buffers/progress plus short-I/O warnings. The 33-line golden covers two-handle conflicts,
      SHM conflicts, delete-while-open, durability recovery, and all fault classes; affected
      conformance is 3/3 and native VM/direct ASM are exact. Portability gotchas resolved: native
      structural lowering requires one-line `def` parameters and reserves selector `effect`, while
      VM `Map - key` returns `Unit`, so immutable removal rebuilds maps from keys.
- [x] **scljet-m1d-jvm-vfs-plugin** — DONE 2026-07-12 in `2a594b870` (example/docs
      `1b9df2b57`). Added the dedicated JVM std plugin with positioned file I/O,
      truncate/force, canonical identity, SQLite rollback byte ranges, 32-KiB WAL SHM regions and
      eight process-visible locks, barriers, conservative device capabilities, bounded results,
      service/build wiring, and no pager/SQL policy. The full suite is 6/6 including local
      multi-handle, raw subprocess, and official Xerial SQLite cross-process contention. The first
      assembled gate found the missing explicit package task; the fix now stages 27 essential
      plugins and the real `ssc-tools` example autoloads `scljet-vfs-plugin.sscpkg`.
- [x] **scljet-m1e-verify-record** — DONE 2026-07-12 (`c38d1df2a`). Affected
      conformance is 3/3; the 31/33/6-line byte, memory-VFS, and module goldens are exact across
      v1/native VM/direct ASM; `installBin`, plugin 6/6, assembled JVM example, focused JsGen tests,
      and `git diff --check` are green. JS companion and native-array `Nil`/`Cons` bugs were fixed in
      `830c0db27`; exact-divisible chunk indexing landed in `f9518f881`. JS now executes both pure
      programs, but exact Long/bitwise codecs and two SHM lock lines remain open behavior items in
      the spec/BUGS ledger. No JDBC/sql.js fallback was used.

### SclJet M2 — read-only SQLite files (queued 2026-07-12)

- [x] **scljet-m2a-readonly-spec** — DONE 2026-07-12 (`7a6e2e70a`). Defined exact functional
      public/internal APIs and localized errors for the 100-byte header, all page sizes/reservations,
      four B-tree cell layouts and X/M/K payload formulas, overflow/freelist/pointer maps, serial
      types, lossless invalid-UTF handling, immutable SHARED-locked pager/LRU limits, forward
      table/index cursors, raw sqlite_schema classification, and a pinned valid/corrupt fixture
      matrix. Mutation, recovery/WAL overlays, SQL/DDL parsing, logical column projection and seeks
      remain explicitly outside M2. The oracle pin advanced from 3.53.0 to current bug-fix 3.53.3.
- [x] **scljet-m2b-page-record-codecs** — DONE 2026-07-12 (`66ff828b9`, docs
      `ae709c40a`). Added pure header/page/record modules: exact 100-byte header validation, all
      legal page sizes/reservations, four B-tree cell layouts, X/M/K local payload, freeblock/cell
      overlap checks, bounded overflow pages/chains, all persistent serial types, IEEE binary64,
      and lossless UTF-8/UTF-16 bytes with deterministic GIGO code points. The committed official
      SQLite 3.53.3 vector is exact on interpreter/native VM/direct ASM; affected conformance is
      4/4 and the runnable example is identical on all three lanes. JS matches 34/35 lines; its
      known Long/bitwise lowering decodes binary64 `1.5` as `0`, recorded in `BUGS.md`.
- [x] **scljet-m2c-readonly-pager-btree** — DONE 2026-07-12 (`4aba98aef`,
      `d52f89ead`, JVM adapter `c281958bd`, docs `0f5bec401`). Added a SHARED-locked immutable
      pager/LRU, fail-closed sidecar checks, table/index forward cursors, overflow ownership,
      sqlite_schema decoding and rowid/WITHOUT ROWID root classification, freelist and auto-vacuum
      pointer-map validation, plus the minimal value-level read facade. Conformance is 6/6; the
      multi-level pure cursor is exact on interpreter/VM/ASM; the assembled real JVM plugin reads
      schema/row and public close releases the handle. The discovered imported-selector close bug
      is fixed and guarded. Node's false leaf-depth result is recorded for M2d/backend parity; no
      JDBC/sql.js fallback, planner, recovery, WAL overlay, or write path was added.
- [x] **scljet-m2d-interop-verify** — DONE 2026-07-13 (codex corpus slices +
      claude-code VM/ASM parity `79fffb549`/`a93db2f11` and overflow thresholds
      `814d5c2b4`/`46150acb6`). The pinned corpus is now 24 valid files / 629 exact
      oracle lines + 25 named corruptions + 32 bounded fuzz mutations, all consumed
      from committed bytes (regeneration optional), every valid DB `integrity_check = ok`,
      and manifest SHA/value dumps match the SclJet reader. `tests/e2e/scljet-m2-corpus-smoke.sh`
      now runs the dump + corrupt + fuzz checks on three interpreter execution tiers
      (default bytecode VM + fast tier + javac JIT, ASM JIT `SSC_JIT_BACKEND=asm`, and
      the pure tree-walk fallback `SSC_JIT_BYTECODE=off SSC_FASTTIER=off`) and requires
      byte-identical results from each — closing the explicit VM/ASM corpus-execution
      requirement. `overflow-thresholds.db` pins exact table-leaf payload vectors
      (p = X-1/X/X+1, the sharp K>X fall to the m-byte residue, the K<=X branch, and a
      multi-page overflow chain), reproducible with the same byte-exact SQLite 3.53.3.
      The two aggregate M2 behavior gates (byte-for-value corpus + safe-corruption
      diagnostics) are now `[x]` in `specs/scljet.md` for the interpreter/VM/ASM/fallback
      lanes. `scljet-freelist-recursive-stack-overflow` fixed+guarded (`7399fad95`); the
      183-page valid freelist retains structured duplicate/cycle/pointer-map failures.
      Node boundary recorded honestly (byte/page-record codecs still diverge on JS —
      tracked as `scljet-js-m1-parity` / `scljet-js-m2-cursor-parity` in `BACKLOG.md`);
      no JDBC/sql.js substitution. Remaining M2d hardening (index-btree payload
      thresholds + deep record/overflow/freeblock/schema corruptions) moved to `BACKLOG.md`.
- [x] **scljet-m2d-hardening-1-index-thresholds** — DONE 2026-07-13. Generalized
      `generate.py`'s hard-coded `idx_t_a` oracle case to dump ANY rowid-table index's
      key records in physical b-tree order (`PRAGMA index_info` + `SELECT <cols>,rowid
      ... ORDER BY`); verified output-preserving by recomputing all committed oracle
      lines. Added `index-overflow-thresholds.db` (blob-keyed index, keys straddling
      index X=102: p=101/102 local, p=103 sharp K>X fall to m=39, p=200, p=1100 K<=X
      multi-page chain). Reader reproduces every index row byte-for-value on default
      VM / ASM / tree-walk tiers. Corpus now 25 valid / 643 oracle lines.
- [x] **scljet-m2d-hardening-2-deep-corruptions** — DONE 2026-07-13. Added 5 deep
      page-1 sqlite_schema byte-mutation corruptions to `generate.py`'s `corruptions()`
      (via real record parsing, not hard-coded offsets): reserved serial type 10
      (`serial types 10 and 11`), unknown schema type (`type is unknown`), out-of-range
      rootpage 127 (`outside the logical database`), negative int8 rootpage
      (`rootpage must be non-negative`), and a page-1 freeblock below the header
      (`freeblock chain is not increasing`). Each expected substring was confirmed
      against the reader's real localized error (never fabricated); corrupt corpus is
      now 30 files, all failing safely and identically on VM/ASM/tree-walk tiers.
      Only remaining item: user-table overflow-chain traversal corruption (needs a
      traversal-based negative check beyond open-time validation) — kept in BACKLOG.

### SclJet M3 — writes and rollback journal (queued 2026-07-13)

Large milestone; the design already exists in `specs/scljet.md` (M0 transaction
protocols §"Engine/connection", pager transaction state §"M2 immutable read
pager" → mutable extension, `journal.ssc` planned in the module layout). Build
the write path from the bottom up; every slice keeps the read path green and
produces files that reference SQLite `PRAGMA integrity_check` accepts. Verify
each with the byte-exact SQLite 3.53.3 already on this machine (diff our output
against reference-produced files) and by reopening with the SclJet reader.

- [ ] **scljet-m3a-write-spec** — write the M3 implementation spec section
      (`specs/scljet.md`): the mutable pager (dirty page set, page allocation from
      freelist/EOF, truncate), the write API surface actually built in M3
      (a minimal `WriteHandle` or `writeEmpty`/`insertRow` entry, NOT the full
      SqlConnection — that is M4), the rollback-journal format (header, page
      records, checksums, hot-journal recovery), and the exact slice ordering
      below with done-when gates. Commit `spec:` before code.
- [x] **scljet-m3b-empty-db-writer** — DONE 2026-07-13. Added `runtime/std/scljet/write.ssc`
      with `emptyDatabase(pageSize): Either[ByteError, ByteSlice]` (exported via index.ssc),
      a pure serializer of a freshly-created empty database. Verified **byte-identical to
      reference SQLite 3.53.3** for page sizes 512/1024/4096/65536 (512 = committed
      `empty-encoding-zero.db`; others = `PRAGMA page_size=N; VACUUM`), identical on the
      VM/ASM/tree-walk tiers, and it round-trips through `decodeDatabaseHeader`. Two layout
      corrections vs the original plan: schema cookie (40-43) = **1** and schema format
      (44-47) = **0** (empty DB is format 0, encoding 0). Conformance case
      `scljet-write-empty` (`backends: [int, js]`) + example `examples/scljet-write-empty.ssc`
      (runs on the native `ssc run` tier too) + tool `tests/tools/scljet-write-check.ssc`.
      NOTE: JS lane covers 512..8192; page sizes >=~16384 overflow node's stack in the
      recursive `ByteSlice.zeros`/`zerosList` (no JS TCO) — queued in BACKLOG as
      `scljet-byteslice-zeros-js-recursion`. int/VM/ASM cover all sizes byte-exactly.
- [x] **scljet-m3c-single-row-insert** — DONE 2026-07-13. `write.ssc` gained the
      record encoder `encodeRecord` (exact inverse of `record.ssc`: `varint(headerLen)
      ++ serial varints ++ body`, narrowest signed int serial with 0/1 → the 8/9
      storage-class serials, UTF-8 text via `charAt`, blob, NULL) and the table-leaf
      page writer + `buildSingleTableDatabase(pageSize, changeCounter, schemaCookie,
      tableName, createSql, rows)` — assembles a legal two-page single rowid-table DB
      (page 1 `sqlite_schema` cell + page 2 row cells). Verified: **byte-identical to
      the pinned `page-512.db`** with change counter 3 / schema cookie 2 (the values
      SQLite writes for `CREATE TABLE` + one `INSERT`); reference
      `PRAGMA integrity_check = ok` and correct row read for both that and an
      independent `nums(n INTEGER, label TEXT)` table with three rows incl. a NULL;
      identical on int/VM/ASM/fallback, native `ssc run`, and JS. Conformance
      `scljet-write-record` + `scljet-write-database` ([int, js]); examples
      `scljet-write-empty` + `scljet-write-table`. Byte-identity to page-512.db
      transitively proves the SclJet reader reads our output (it already reads that
      fixture). `SqlReal` record encoding (Double → IEEE-754 bits) is the one queued
      follow-up. Multi-row-on-one-page works; overflow/page-split is m3d.
- [x] **scljet-m3d-btree-insert-balance** — DONE 2026-07-13. `buildTableDatabase`
      generalizes the single-page writer to a **multi-page rowid-table B-tree** via
      bottom-up bulk build: rows are packed into leaf pages (`packLeaves`), and when
      they overflow one leaf, page 2 becomes a table-interior root (12-byte header +
      rightmost child) over the leaves on pages 3..N (`buildTableInteriorPage`,
      `encodeInteriorCell` with each divider keyed by its left child's max rowid).
      Verified with reference `PRAGMA integrity_check = ok` and full read-back: a
      200-row table is 8 pages (interior + 6 leaves), `count = 200`, `sum(n) = 20100`;
      a 60-row table is 4 pages; the 1-row case stays byte-identical to `page-512.db`.
      Identical on int/VM/ASM/fallback and JS; conformance `scljet-write-btree`.
      Found + worked around a real interpreter bug (`BUGS.md`
      `interp-if-then-no-else-after-while`) that silently dropped the last leaf.
      Follow-ups (`BACKLOG.md`): cell-overflow-page allocation for large payloads,
      3+-level trees (interior root that itself overflows), incremental
      insert-into-existing-DB (that is the pager/journal path, m3e), and `SqlReal`.
- [x] **scljet-m3e-rollback-journal** — DONE 2026-07-14. Journal format + the
      transactional in-place page write + write transactions all landed. `journal.ssc`
      `writePagesJournaled` journals the pre-images of the changed pages then overwrites
      them in place (recovery via `applyRollbackJournal` restores the original), and
      `beginTransaction`/`stagePage`/`commitTransaction`/`rollbackTransaction` batch
      several page writes into one atomic journaled commit (or discard). Conformance
      `scljet-journal-write` + `scljet-transaction`, int==js. (Historic format detail
      below.) `journal.ssc`:
      `applyRollbackJournal(db, journal)` parses the official rollback-journal
      format (header magic `d9d505f920a163d7`, nonce, initial page count, sector/
      page size; records `u32 pageNo ++ page ++ u32 checksum`), verifies SQLite's
      sparse checksum (`nonce + Σ data[pageSize-200k]`, u32 — confirmed matching a
      real SQLite journal), restores every pre-image, and truncates to the recorded
      size; `writeRollbackJournal(pageSize, sectorSize, dbSize, nonce, records)` is
      the exact inverse — **byte-identical to SQLite's journal** and it round-trips
      (write → recover → original). Verified: a dirtied `page-512.db` recovers
      byte-identical with reference `integrity_check = ok`; a 2-record journal
      restores `comprehensive.db` byte-identical; a no-magic journal is not hot;
      a corrupt checksum is rejected. int/VM/ASM/fallback + JS (conformance
      `scljet-journal-recover`, a write→recover round-trip). REMAINING m3e: wire
      recovery into `pager.ssc`'s open path (it currently rejects a non-empty
      journal — needs a writable VFS write-back + journal delete), and the
      transactional begin/mutate/commit/rollback cycle (needs the mutable pager) so
      fault-injected aborts leave the file fully committed or fully rolled back.
- [x] **scljet-m3f-delete-update** — DONE 2026-07-14 via read-modify-rewrite.
      `mutate.ssc` `insertRow`/`deleteRowids`/`keepRowids`/`updateRowValues` open the DB
      read-only over its own bytes, read every surviving row as its raw record payload,
      and rebuild the table with the original schema + rowids preserved. Works on
      single- and multi-table files, and keeps a table's index consistent
      (`deleteRowidsIndexed`/`updateRowIndexed`, integer + text keys — reference
      `integrity_check`'s index cross-check passes). Conformance `scljet-mutate-*`,
      `scljet-index-mutate*`, int==js. This is a COMPLETE, correct DML alternative to
      in-place cell editing (correct, not byte-minimal). Byte-minimal cell-level editing
      with B-tree rebalance is `scljet-m4-mutable-pager` below.

### SclJet M4 — deep indexes, WAL, mutable pager (queued 2026-07-14, Sergiy: "запиши все в спринт. и делай. можешь не останавливаться пока не сделаешь всё")

The write path (all table variants, overflow, deep trees, multi-table, indexes
int/text single/multi-leaf/composite), full DML (CRUD single+multi-table,
index-maintaining), rollback-journal write/recover, transactional in-place page
writes + write transactions, and a **WAL writer** (`wal.ssc` `writeWal`/`markWalMode`,
verified vs reference SQLite: recover/checksum/checkpoint + negative + multi-frame) are
DONE (32 conformance cases green [int,js]). The honest remainder, each a real feature:

- [x] **scljet-m4a-deep-index** — DONE 2026-07-14. Generalized `write.ssc`
      `buildIndexTree` to stack index interior levels (kind 2) with PROMOTED SEPARATORS
      until a single-page root (`packIdxLevel`/`buildIdxLevels`/`buildIdxLevelPages`/
      `buildIdxDividers`/`totalIdxPages`/`IdxNode` — the `buildDeepTableDatabase`
      top-down-numbering pattern, but interiors carry real separator records not copied
      rowids). Verified vs reference SQLite 3.53.3: a 3000-row index builds a **depth-3**
      tree (118 pages), `integrity_check` cross-validates it against the table, the
      planner uses it (`SEARCH t USING COVERING INDEX`), point + ordered lookups exact.
      Two-level output is byte-identical (existing scljet-write-index* stay green).
      int==js (Adler-32 fingerprint); conformance `scljet-write-index-deep`.
- [x] **scljet-m4b-wal-recover** — DONE 2026-07-14. `wal.ssc` `readWal(walBytes) →
      WalIndex(pageSize, dbSizePages, frames)` validates the header checksum (endian per
      the magic's low bit — reads both our 0x…83 and real SQLite's 0x…82), walks frames
      chaining the running checksum, keeps frames up to the last commit (uncommitted tail
      + first bad-checksum frame excluded), latest frame per page wins. int==js;
      conformance `scljet-wal-recover`. ORIGINAL:
      (the read-side inverse of `wal.ssc` `writeWal`). Validate the 32-byte header
      (magic, format, page size, salts, header checksum), then walk frames validating
      each frame's running two-word checksum (chained from the header); build
      `pageNumber → latest committed frame` up to and INCLUDING the last frame whose
      `dbSizeAfterCommit != 0` (a commit frame) — frames after the last commit are
      uncommitted and ignored; a frame that fails its checksum ends the valid region.
      New module `wal.ssc` reader half (or `wal-read.ssc`): `readWal(walBytes) →
      Either[…, WalIndex(pageSize, frames: Map/List, dbSizePages)]`. Done-when: reading
      the WAL my own `writeWal` produced yields the right frame for the changed page and
      the post-commit db size; a truncated/corrupt-checksum tail is excluded; int==js.
      Conformance `scljet-wal-recover`.
- [x] **scljet-m4c-wal-read-overlay** — DONE 2026-07-14. `pager.ssc` now LOADS the `-wal`
      sidecar on open (`loadWal`/`readWholeSidecar`, replacing the old M5 rejection),
      parses it with `readWal`, stores the `WalIndex` on `ReadonlyPager` (new `walIndex`
      field, defaulted so external constructions are unaffected), sets the logical page
      count from the WAL's post-commit size, and serves any page with a committed frame
      from the overlay (`pagerWalPage`) before touching the file — so `openReadonly`/
      cursors read WAL'd pages. Verified int==js: a read-only pager over base+`-wal`
      returns page 2 from the WAL frame and page 1 from the base (conformance
      `scljet-wal-read`, two-file in-memory VFS); reference SQLite reads the same two
      files identically. `walReadPage`/`walPage` are the standalone overlay primitives.
- [x] **scljet-m4d-wal-checkpoint** — DONE 2026-07-14. `wal.ssc` `checkpointWal(base,
      walBytes)` applies every committed frame in file order then truncates to the WAL's
      db size. Verified vs reference SQLite 3.53.3: **BYTE-IDENTICAL** to `PRAGMA
      wal_checkpoint(TRUNCATE)` on the same base+WAL, and SQLite reads the checkpointed DB
      as the WAL'd value with `integrity_check` ok. int==js; conformance
      `scljet-wal-checkpoint`.
- [x] **scljet-m4e-mutable-pager** — DONE 2026-07-14. `journal.ssc` `MutablePager` +
      `openMutablePager`/`mutableGet`/`mutablePut`/`mutableAllocate`/`mutableCommit`/
      `mutableRollback`. Page-granular: `mutableGet` returns the staged dirty page over
      the file, `mutablePut` stages, `mutableAllocate` gives the next EOF page number,
      `mutableCommit` journals the pre-images of the pages that already existed (under the
      ORIGINAL page count, so recovery restores + truncates back exactly), grows the image
      for allocations, and applies every staged page atomically; `mutableRollback` drops
      the dirty set. Verified int==js: staging page 2 = `bbb`'s page 2 over the `aaa` image
      and committing reproduces the `bbb` database BYTE-FOR-BYTE (a known-valid file); the
      commit journal recovers the original; allocation grows the file and is undone by
      recovery; rollback leaves the image unchanged. Conformance `scljet-pager-mutate`.
      (Freelist-page reuse on alloc is a follow-up; alloc is EOF-only.)
- [~] **scljet-m4f-cell-inplace-balance** — single-leaf case DONE 2026-07-14; multi-page
      split/merge (`balance()`) remaining. `write.ssc` `readLeafCells` decodes a table-leaf
      page's cells; `leafInsertCell` (ordered, dup-rejected) / `leafDeleteCell` /
      `leafUpdateCell` edit the cell list by rowid; `rebuildLeafPage` re-serializes the
      page compactly. Combined with the mutable pager this mutates a table one PAGE at a
      time. Verified vs reference SQLite 3.53.3: an in-place insert (rowid 4) + delete
      (rowid 2) committed through the pager gives `integrity_check` ok and reads
      `1|a, 3|c, 4|d`; int==js incl. journal recovery (conformance `scljet-cell-inplace`).
      REMAINING (genuinely huge — SQLite `balance_nonroot`/`balance_deeper`): when the
      edited leaf overflows/underflows, split/merge/redistribute pages and update parent
      dividers, to any depth. `rebuildLeafPage` returns an error in that case today;
      `mutate.ssc` read-modify-rewrite already provides correct DML for it. Also
      no-overflow-cell assumption in `readLeafCells` (spilled payloads = follow-up).

### SclJet M4g — incremental B-tree balance() (2026-07-14, Sergiy: "так берись")

- [x] **scljet-m4g-balance-insert** — DONE 2026-07-14. `write.ssc` `pagerInsertBalanced`
      descends to the leaf, inserts, and balances on the way up: `finishLeaf`/`finishInterior`
      split an overflowing node (`packLeafChunks`/`packInteriorChunks`, first chunk keeps the
      page, rest allocated at EOF), `replaceChild` swaps the parent's slot for the pieces with
      new dividers (interior split promotes a divider via `readInteriorNode`), and `balanceDeeper`
      grows the tree a level at the root (root page preserved). `patchHeaderPageCount` keeps the
      file header's page count in range. Verified vs reference SQLite 3.53.3: in-place inserts
      grow a one-leaf table through a 2-level (depth 2 at 20 rows) and a **3-level** tree (depth 3
      at 160 rows, 84 pages) — every intermediate `integrity_check` ok with exact ordered rows.
      int==js (conformance `scljet-balance-insert`, tree-walk + fingerprint). NB: this needed
      `journal.ssc` fixes — `overwritePage` copies into the chunk map (no whole-image concat that
      overflowed the interpreter's `++`) and `mutableCommit` dedups staged pages to one journal
      pre-image per page. ORIGINAL:
- [x] **scljet-m4g-balance-delete** — DONE 2026-07-14. `write.ssc` `pagerDeleteBalanced`
      descends to the leaf and rewrites it with the cell removed (`descendToLeaf` + `leafDeleteCell`
      + `rebuildLeafPage`). No rebalance is needed for validity — an underfull leaf and a stale-but-
      still-covering divider key remain a legal B-tree. Verified int==js in `scljet-balance-insert`
      (delete rowids from a multi-level tree; the remaining rows read back exact). Sibling
      merge/compaction stays an optional follow-up.

**M4g DONE — SclJet now has a real incremental B-tree `balance()`, the last remaining piece.**
`mutate.ssc` read-modify-rewrite still exists for whole-table DML; the balanced path mutates
one root-to-leaf spine per insert. Optional follow-ups only: freelist-page reuse on alloc,
overflow-cell decode in `readLeafCells`, and delete-time sibling merge (compaction, not
correctness).

### SclJet M5 — SQL query layer (2026-07-14, Sergiy: "продолжай"; original scljet vision named a "sql интерпретатора")

The storage engine is complete; M5 puts SQL on top of it. Each slice verifies against
reference sqlite3 running the SAME SQL on the SAME file (default row order = cursor = rowid
order, so `SELECT *` matches without ORDER BY), and int==js.

- [x] **scljet-m5a-sql-select** — DONE 2026-07-14. New module `scljet/sql.ssc`: `tokenize`
      (lexer), `parseSelect` (→ `SelectStmt` AST), `executeSelect`, `queryImage(dbBytes, sql)`,
      `renderRows` (sqlite3 CLI format). Parses `SELECT (*|cols) FROM table [WHERE col op literal]`
      (op ∈ `= <> != < > <= >=`, literal = integer or `'string'`), resolves columns from the
      table's `CREATE TABLE` text (+ implicit `rowid`), cursors the rows, filters, projects.
      VERIFIED: 8 SELECTs (star, projection, int/text WHERE across every comparator, rowid,
      missing-table error) over a writer-built DB are BYTE-IDENTICAL to reference sqlite3
      running the same SQL on the same file; int==js. Conformance `scljet-sql-select`. GOTCHA:
      the interpreter's `&&` does NOT short-circuit — made `charCode` bounds-safe (returns -1
      past end) so `i < n && isDigit(charCode(s,i))` can't index out of bounds. NB: sql.ssc
      imports reader types from index.ssc + `ImageVfs`/`fieldValueAt` from mutate.ssc; NOT
      re-exported by index.ssc (would cycle) — conformance imports it as `std/scljet/sql.ssc`.
- [x] **scljet-m5b-sql-orderby-limit** — DONE 2026-07-14. `sql.ssc`: `parseWhere` now returns the
      trailing tokens; `parseOrderLimit` parses `ORDER BY col [ASC|DESC]` then `LIMIT n [OFFSET m]`
      into `SelectStmt(orderBy: Option[OrderKey], limit, offset)`; `executeSelect` filters → stable
      merge-sorts by the order column (on the full row, so the sort key need not be projected) →
      applies OFFSET/LIMIT → projects. Verified vs sqlite3 (ASC/DESC, LIMIT, OFFSET, WHERE+ORDER BY,
      out-of-range LIMIT), int==js; conformance `scljet-sql-orderby`. Single ORDER BY column;
      multi-column ORDER BY (`ORDER BY a, b`) silently uses only the first — a follow-up.
- [x] **scljet-m5c-sql-insert** — DONE 2026-07-14. `sql.ssc` `parseInsert`/`executeInsert` +
      `executeMutation` dispatcher: `INSERT INTO t VALUES (…)` encodes the record and inserts via
      `pagerInsertBalanced` (rowid = max existing + 1, matching sqlite), commits, returns the new
      image. Verified vs sqlite3 (same statements → identical table, integrity ok), int==js.
      Column-list form `INSERT INTO t(cols) VALUES` is a follow-up.
- [x] **scljet-m5d-sql-delete** — DONE 2026-07-14. `sql.ssc` `parseDelete`/`executeDelete`:
      `DELETE FROM t [WHERE col op literal]` finds matching rowids (WHERE reused from SELECT) and
      deletes each via `pagerDeleteBalanced`, commits. Verified vs sqlite3, int==js. Conformance
      `scljet-sql-dml` covers INSERT + DELETE + re-query, matching sqlite after the same sequence.
- [x] **scljet-m5e-sql-update** — DONE 2026-07-14. `sql.ssc` `parseUpdate`/`executeUpdate`:
      `UPDATE t SET col=literal [, col=literal] [WHERE …]` re-encodes each matching row (current
      values with assignments applied) and replaces it at the same rowid via delete+reinsert on
      the balanced path (handles a value that grows or shrinks). Verified vs sqlite3 (multi-column
      SET, int/text WHERE, update-all), int==js; conformance `scljet-sql-update`. Found + recorded
      a real interpreter bug: **`&&`/`||` do NOT short-circuit in the interpreter** (BUGS.md
      `interp-boolean-operators-no-short-circuit`) — a no-WHERE UPDATE hit `rest.nonEmpty &&
      rest.head.kind` on `Nil`; fixed with bounds-safe `tkKind`/`tkIsKw` accessors (JS was always
      fine). SQL CRUD (SELECT/INSERT/UPDATE/DELETE) is now complete and matches sqlite3.
- [ ] **scljet-m5f-sql-create-table** — `CREATE TABLE t(…)` → build an empty table + schema row.
- [~] **scljet-m5g-sql-aggregates-join** — aggregates DONE 2026-07-14. `sql.ssc` `parseProjection`
      detects `FUNC(*|col)` (`parseAggregates`/`AggItem`); `executeSelect` computes over the filtered
      rows and returns one row. `COUNT(*)`, `COUNT(col)` (non-null), `SUM`, `MIN`, `MAX` (also `AVG`,
      `TOTAL`) — verified vs sqlite3 (multi-aggregate, WHERE, text MIN/MAX, empty-set→NULL), int==js;
      conformance `scljet-sql-aggregate`. AVG/TOTAL computed but omitted from the differential —
      SqlReal renders `35` not sqlite's `35.0` (real-formatting follow-up). REMAINING: `GROUP BY`,
      inner joins, and sqlite-exact real formatting.

### SclJet M5 SQL — remaining slices (2026-07-14, Sergiy: "продолжай, не останавливайся, делай все")

- [x] **scljet-m5h-real-format** — DONE 2026-07-14. `sql.ssc` `renderReal`: an integer-valued real
      keeps a trailing `.0` (`35` → `35.0`), else the shortest round-trip form. AVG/TOTAL now join the
      sqlite differential — all 10 aggregate queries (incl `AVG`/`TOTAL`, clean + WHERE + empty-set)
      byte-identical to sqlite3, int==js (`scljet-sql-aggregate`). Repeating-decimal `%.15g` parity
      (e.g. 1/3) is a further note.
- [x] **scljet-m5i-orderby-multi** — DONE 2026-07-14. `parseOrderLimit` parses a list of OrderKeys
      (per-key ASC/DESC); `SelectStmt.orderBy: List[OrderKey]`; `recCompare` compares lexicographically
      over the key list. Verified vs sqlite3 (`ORDER BY age DESC, id ASC`, `ORDER BY name, id LIMIT`),
      int==js; folded into `scljet-sql-orderby`.
- [x] **scljet-m5j-insert-columns** — DONE 2026-07-14. `sql.ssc` `parseInsertColumns` parses the
      optional `( col, … )` before VALUES; `InsertStmt.columns`; `executeInsert` maps the values to
      declared-column order (`reorderInsertValues`, unnamed columns → NULL). Verified vs sqlite3 (full,
      reordered, partial), int==js; conformance `scljet-sql-insert-cols`.
- [x] **scljet-m5k-group-by** — DONE 2026-07-14. Refactored the projection to a unified `ProjItem`
      list (column OR aggregate, mixed) — `parseProjItem`/`parseProjectionList`; `SelectStmt.projection:
      List[ProjItem]` + `groupBy: List[String]` (parsed by `parseGroupBy` after WHERE). `executeSelect`:
      GROUP BY → sort filtered rows by the group key, `groupPartition` into consecutive runs, one row
      per group via `projectGroupRow` (aggregate over the group, or the group's first-row value for a
      bare column); whole-table aggregate (no GROUP BY) = one group. Verified vs sqlite3 (per-group
      COUNT/SUM/MIN/MAX, WHERE+GROUP, distinct via `GROUP BY col`, group-by-int, whole-table agg),
      int==js; conformance `scljet-sql-group-by`. AVG-per-group excluded (repeating-decimal `%.15g`
      follow-up). REMAINING: `HAVING`, ORDER BY over grouped output, joins.
- [x] **scljet-m5l-create-table** — DONE 2026-07-14. `sql.ssc` `parseCreate`/`executeCreate`:
      `CREATE TABLE t(…)` reads page 1's schema leaf (`readLeafCells` at headerOffset 100), inserts a
      new `(type='table', name, tbl_name, rootpage, sql)` cell (`leafInsertCell`, sql stored verbatim),
      rebuilds the leaf portion (`rebuildLeafPage` at 100) spliced onto the file header with the page
      count bumped, allocates an empty table root (`rebuildLeafPage(Nil)` at 0), and commits both pages
      via the mutable pager. Verified vs reference sqlite3: `integrity_check` ok, `.schema` shows every
      table with exact SQL, roots assigned in order, and INSERT (incl. column-list) / SELECT / GROUP /
      ORDER on the new tables match sqlite. int==js; conformance `scljet-sql-create-table`. LIMITATION:
      the page-1 schema leaf must not overflow (a page-1 split — balance at headerOffset 100 — is the
      follow-up); `CREATE TABLE IF NOT EXISTS`, indexes, and constraints not parsed.
- [x] **scljet-m5m-join** — DONE 2026-07-14. Inner join `SELECT … FROM a [INNER] JOIN b ON a.x op b.y
      [WHERE …]`, nested-loop. Lexer now emits qualified names (`a.b`) as one ident; `parseJoin` reads
      the JOIN + ON; `JoinSpec` on `SelectStmt`; `joinExecute` nested-loops rowsA × rowsB, keeps pairs
      where ON (and WHERE) hold, and projects via `joinColValue` (qualified `a.col` → that table, bare
      col → whichever table has it; `SELECT *` = all A cols then all B cols). Verified vs sqlite3
      (qualified + bare projection, WHERE on qualified/bare, `SELECT *`, filter on join): byte-identical
      (join order = nested loop = sqlite's), int==js; conformance `scljet-sql-join`. Follow-ups:
      multi-table joins, LEFT/OUTER, aggregates/GROUP BY/ORDER BY over a join, `a.*`.

- [x] **scljet-m5n-having** — DONE 2026-07-14. `HAVING (agg|col) op literal` after GROUP BY:
      `parseHaving` (left = `parseProjItem`) → `SelectStmt.having`; `filterGroups`/`havingHolds`
      evaluate the aggregate/column over each group and keep matching groups before projection.
      Verified vs sqlite3 (`HAVING COUNT(*)>1`, `SUM>=`, column `HAVING dept=…`, `MAX<` + ORDER BY,
      WHERE+GROUP+HAVING), int==js; conformance `scljet-sql-having`.

- [x] **scljet-m5o-left-join** — DONE 2026-07-14. `LEFT [OUTER] JOIN` — `parseJoin` recognizes LEFT,
      `JoinSpec.leftOuter`; `joinExecute` tracks whether each outer row matched and, for LEFT with no
      match, emits it once with the B-side columns NULL (`joinColValue`/`joinProjectRow`/`joinWhereHolds`
      now take `Option[StorageRecord]` for B; `bValue` = NULL when absent). Verified vs sqlite3 (LEFT +
      LEFT OUTER, unmatched → NULL, WHERE on B-column filtering unmatched rows) alongside the inner-join
      cases, int==js; conformance `scljet-sql-join`.

- [x] **scljet-m5p-distinct** — DONE 2026-07-14. `SELECT DISTINCT` (and no-op `ALL`): `SelectStmt.distinct`;
      the plain path projects all rows, `dedupRows` keeps first appearance (NULLs equal), then LIMIT.
      With ORDER BY the rows sort first, so the distinct set matches sqlite's sorted output. Verified vs
      sqlite3 (bare/appearance-order, ORDER BY, multi-column, WHERE, DISTINCT+ORDER+LIMIT, ALL), int==js;
      conformance `scljet-sql-distinct`.

**SclJet SQL is now broad**: full CRUD (incl. column-list INSERT), SELECT with DISTINCT, multi-column
ORDER BY / LIMIT / OFFSET, aggregates (COUNT/SUM/MIN/MAX/AVG/TOTAL), GROUP BY + HAVING, CREATE TABLE,
and inner + LEFT joins over 2 *and* 3+ tables (incl. aggregates/GROUP BY/HAVING over a 3-join),
non-correlated subqueries, CREATE/DROP INDEX with maintenance — every feature byte-verified against
reference sqlite3, int==js. Remaining follow-ups (niche): RIGHT/FULL outer joins, correlated
subqueries / EXISTS, multi-table-with-indexes DML, page-1 schema split, repeating-decimal %.15g.
Two new front doors are specced (typed-SQL-API cb94fd88c, JDBC-API f2d1372a0); **both now have a
working first implementation** — the JDBC portable façade (m6v) and the typed SQL surface (m6w),
alongside SQL polish (REAL literals m6s, LEFT-join-3 m6r). All three "параллельно" lanes advanced.

- [x] **scljet-m7m-right-join3** — DONE 2026-07-15 (follow-up niche #2). RIGHT/FULL as the LAST join of a
      3+ table chain (N-table path). `extendPartials` now, for `rightOuter`, keeps each next-table row no
      partial matched, NULL-extending the previous tables (`nullList`). `a JOIN b … RIGHT JOIN c` keeps
      unmatched c rows with a/b NULL; FULL = both flags. Verified vs sqlite3 (3-table RIGHT-last, NULL
      emp/dept for the unmatched proj row), int==js; conformance `scljet-sql-right-join3`; 53/53 sql green.
      Scope: RIGHT/FULL as the LAST join (those rows need no further extension); a RIGHT/FULL join in the
      MIDDLE of a chain (null-extended rows must survive later joins) is a deeper follow-up.

- [x] **scljet-m7l-correlated-scalar** — DONE 2026-07-15 (follow-up niche; Sergiy "Берись за эти ниши").
      Correlated scalar subquery in a comparison — `col <cmp> (SELECT … WHERE inner.x = outer.y)`.
      Completes the correlated-subquery family (EXISTS m7i, IN m7j, scalar m7l). `parseCondition`'s
      comparison branch captures `<cmp> (SELECT …)` on `subTokens` (op = the comparison); per outer row
      `scalarSubqHolds` substitutes outer refs, runs the subquery, takes the first row's first column
      (`scalarSubqValue`, NULL if empty), compares — NULL either side → false (SQL unknown). Non-correlated
      scalar still pre-resolved. `hasExistsCond` now keys on any condition with `subTokens`. Verified vs
      sqlite3 (=/</>= correlated scalar, empty-subquery→NULL→excluded), int==js; conformance
      `scljet-sql-correlated-scalar`; 52/52 sql green.

- [x] **scljet-m7k-right-full-join** — DONE 2026-07-15 (new SQL feature; Sergiy "Делай всё автономно";
      last list item). `RIGHT [OUTER] JOIN` (keeps every right-table row, NULL-extended left) and `FULL
      [OUTER] JOIN` (both sides' unmatched rows). `JoinSpec.rightOuter` flag (`parseJoin`: RIGHT→rightOuter,
      FULL→both); `joinExecute` adds a pass over the right table emitting each ON-unmatched right row with a
      `nullRow` left sentinel — so `SELECT *` keeps the left-then-right column order (a table swap would
      reverse it). Verified vs sqlite3 (RIGHT explicit + `SELECT *`, FULL, COUNT, NULL-extended sides),
      int==js; conformance `scljet-sql-right-full-join`; 51/51 sql green. Scope: 2-table (3+ table RIGHT/
      FULL is a follow-up). **The SQL-feature list is now complete** (correlated EXISTS m7i / IN m7j,
      RIGHT/FULL joins m7k) on top of the comprehensive physical index access-path (m6z, m7c–m7h).

- [x] **scljet-m7j-correlated-in** — DONE 2026-07-15 (new SQL feature; Sergiy "Делай всё автономно").
      Correlated `[NOT] IN (subquery)` — extends the EXISTS per-row machinery. `resolveSubqueries` now
      detects a correlated subquery (its tokens contain a qualified ref to the outer FROM table —
      `firstFromTable` + `referencesOuterTable`) and skips the pre-pass, while non-correlated
      `IN (SELECT …)` still resolves once. `parseCondition` captures `IN (SELECT …)`/`NOT IN (SELECT …)`
      as `insubq`/`notinsubq` Conditions; per outer row `inSubqHolds` substitutes the outer refs, runs
      the subquery against the open db, and tests the left value's membership in the first column. Verified
      vs sqlite3 (correlated IN + NOT IN, and a non-correlated IN still pre-resolved), int==js; conformance
      `scljet-sql-correlated-in`; 50/50 sql green. Correlated scalar `=(SELECT …)` is a follow-up.
      NEXT (last list item): RIGHT/FULL joins.

- [x] **scljet-m7i-correlated-exists** — DONE 2026-07-15 (new SQL feature; Sergiy "Делай всё автономно").
      Correlated `[NOT] EXISTS (subquery)` where the subquery references the outer row (`t2.fk = t1.id`),
      evaluated PER OUTER ROW (not the non-correlated pre-pass). `Condition` gains a defaulted `subTokens`
      field (op "exists"/"notexists"); `parseCondition` recognizes the EXISTS prefix; `resolveSubqueries`
      skips a `(SELECT` preceded by EXISTS. Per row, `substituteOuterRefs` replaces qualified outer refs
      with that row's value (bound literal token) and the subquery runs against the already-open `db` —
      true iff it returns a row. The filter threads `db` (`finishRows`→`filterRowsCtx`→`whereHoldsCtx`→
      `condHoldsCtx`→`existsHolds`); seek paths pass `db` too (EXISTS composes with an indexable
      predicate); LIMIT-pushdown disabled when the WHERE has EXISTS. **Also fixed** a pre-existing gap the
      natural syntax exposed: single-table column refs were bare-only, so `t2.fk` in `FROM t2` → NULL;
      `rowValue` now drops a `t.col` qualifier (`stripQualifier`) so qualified refs work in single-table
      WHERE/projection (join path unaffected). Verified vs sqlite3 (EXISTS, NOT EXISTS, EXISTS+range
      predicate, COUNT over EXISTS, qualified inner+outer refs), int==js; conformance `scljet-sql-exists`;
      49/49 sql green non-memoized. Scope: single-table outer, qualified outer refs. NEXT: correlated
      `IN`/scalar subqueries (same per-row substitution), RIGHT/FULL joins.

- [x] **scljet-m7h-index-multi-cond** — DONE 2026-07-15 (physical access-path perf; Sergiy "Делай всё").
      Use an index in a multi-condition AND WHERE. `pointLookup` previously only fired for a single
      condition; now a single AND-group with several conditions is index-driven when ANY condition is
      indexable: `indexableConds` collects every `col op literal` in the group, `chooseSeek` picks the
      first seekable one (rowid/IPK equality → table seek, indexed col over a range → index seek). A row
      satisfying the whole AND satisfies each condition, so the seek returns a superset that `finishRows`
      (full WHERE) filters down — byte-identical to the scan. Non-seekable condition → skip to next; a
      chosen seek that bails → full scan; OR-of-groups stays full-scan. Makes `dept='eng' AND active=1`,
      `id=K AND status='x'` index-driven. Verified vs sqlite3 (indexed+residual, rowid+residual, indexable
      condition second, COUNT), int==js; conformance `scljet-sql-index-multi-cond`; 48/48 sql green.
      **Physical index access-path now broad**: equality (rowid/IPK/index descent) + ranges + multi-cond.
      NEXT: true composite multi-column index match (`a=? AND b=?` on `(a,b)`), correlated subqueries /
      EXISTS, RIGHT/FULL joins.

- [x] **scljet-m7g-range-index-seek** — DONE 2026-07-15 (physical access-path perf; Sergiy "Делай всё").
      Range index-seeks — `WHERE indexedcol >/>=/</<= K` and `BETWEEN lo AND hi` now use the index. The
      descent is generalized from an equality key to an `IndexRange` (lo/hi + inclusivity): `keyInRange`
      tests membership, `rangeChildCouldContain` prunes interior children overlapping [lo,hi],
      `readIndexLeafRowids` collects in-range rowids. Equality is `[K,K]`. Collected rowids sorted into
      rowid order (non-recursive `sortLongsAsc`) so a range seek (index order ≠ rowid order) matches the
      full scan. `rangeOfCond`/`extractColRange` map the WHERE; `pointLookup` dispatches equality→table
      seek / range→index range seek. **Also fixed a latent m7f bug**: SQLite index B-tree interior
      DIVIDER cells are REAL entries (each key appears once, not duplicated in a leaf), so
      `readIndexInterior` now returns divider rowids and `descendChildren` collects the in-range ones —
      m7f's k3/k0/k7 keys happened not to be dividers; the range test's k6 (rowid 22 = a divider) exposed
      it. **The interp `if-then-no-else` drop bit again**: a bare `if cond then acc = …` followed by
      another `acc = …` silently discarded the child-subtree rowids → restructured to one acc assignment
      per loop. Verified vs sqlite3 (int/text ranges, all ops, multi-level index with interior pruning +
      divider collection, no fallback), int==js; conformance `scljet-sql-range-seek` + `-range-descent`;
      47/47 scljet-sql green non-memoized. NEXT candidates: composite indexes (`a=? AND b=?`), correlated
      subqueries / EXISTS, RIGHT/FULL joins.

- [x] **scljet-m7f-index-descent** — DONE 2026-07-15 (physical access-path perf; Sergiy "Ок берись").
      O(log n) index descent — `WHERE indexedcol=K` now **descends** the index B-tree instead of walking
      it in order. New index-aware page readers: `readIndexInterior` (each interior cell = left-child
      pointer + divider **key record** → first key column; `readInteriorNode` couldn't be reused — it's
      table/rowid-only, reads a rowid varint) and `readIndexLeafRowids` (leaf cells → rowids where first
      key col == K). `indexDescendCollect` recurses from the root; `descendChildren` visits only children
      whose divider range could contain K (`childCouldContainKey`: conservative `<=`/`>=` on the first
      column, so equal-key runs that **span multiple leaves** are never missed — the hard case). Overflow
      /unexpected-page/decode bail → previous ordered walk (`indexWalkRowids`); both yield identical
      rowids in identical order, so `indexSeekRowids` = try-descent-else-walk is transparent. Verified vs
      sqlite3 on a **multi-level** index (40 long-text-key rows spanning several leaves under an interior
      node; confirmed out-of-band the descent runs standalone without falling back), int==js; conformance
      `scljet-sql-index-descent`; 45/45 scljet-sql green non-memoized. **Physical index access-path work
      COMPLETE**: `WHERE col=K` → PK/rowid col = table B-tree seek (m7c/m7d), any indexed col = O(log n)
      index descent + point-lookup (m7e ordered-walk → m7f descent), plus LIMIT pushdown (m6z). GOTCHA:
      the interp/JS build path (`buildTableWithIndexDatabase`/large SQL INSERT) StackOverflows above
      ~60 rows (no TCO) — force a multi-level index at a small row count via **long keys** (fewer entries
      per leaf) rather than many rows.

- [x] **scljet-m7e-index-seek** — DONE 2026-07-15 (physical access-path perf; continues the seek work).
      Index-assisted lookup — the engine previously **never** used an index for SELECT (always
      full-scanned; the spec's key gap). Now a single-table `SELECT … WHERE col = literal`, where `col`
      is the first key column of an index, walks that index B-tree (`RecordKeyTree` cursor) in key order
      collecting the rowids whose first key column == the literal (stops once a key sorts past it —
      indexes are BINARY-sorted, matching `sqlCompare`), then point-looks-up each row via the rowid seek;
      the found rows feed the same `finishRows` → byte-identical to the full scan. Equal-key entries are
      sub-ordered by rowid = table scan order, so multi-match order matches. `extractRowidEq`→`extractColEq`
      (any literal); `pointLookup` dispatches rowid/IPK-col → table seek, indexed col → index seek, else
      full scan. Overflow-safe: any point-lookup that must fall back abandons the index path.
      Verified vs sqlite3 (TEXT + INTEGER index, duplicates→rowid order, single match, miss, COUNT over
      seek, ORDER BY on top), int==js; conformance `scljet-sql-index-seek`; 44/44 scljet-sql green
      non-memoized. NOTE: this walks the index in ORDER (early-terminating) — a real index-access win over
      decoding every table row, but O(index position), not the O(log n) interior-node descent. The
      descent (index-key **record** comparison in interior nodes — `readInteriorNode` is table/rowid-only,
      so a new index-interior reader + multi-leaf span handling is needed) is the remaining follow-up.

- [x] **scljet-m7d-rowid-seek** — DONE 2026-07-15 (physical access-path perf; Sergiy chose "сначала
      IPK, потом seek"). rowid point-lookup: a single-table `SELECT` whose WHERE is exactly `rowid = K`
      or `<INTEGER PRIMARY KEY> = K` descends the table B-tree (`descendToLeaf`, reused from the write
      layer) to the row in O(log n) instead of full-scanning, then feeds the 0/1 rows to the same
      `finishRows` pipeline → byte-identical to the scan for every query shape (projection / COUNT /
      miss→empty). `queryImageParams` + `runSelectStmt` route through `rowidPointLookup` (so SQL-string
      AND typed-SQL paths benefit). **OVERFLOW-SAFE:** a payload that spills onto overflow pages
      (`payloadLen > usable-35`) or an unreadable leaf returns `SeekFallback` → full scan (which follows
      the overflow chain). Export plumbing: `descendToLeaf` via write.ssc→index.ssc→sql.ssc;
      `decodeVarint`/`decodeRecord` imported. With m7c, the common `WHERE id = K` on a PK table is now a
      point-lookup. Verified vs sqlite3 (hits, miss, rowid pseudo-col, IPK col, COUNT over seek, and a
      1016-char overflow row via fallback), int==js; conformance `scljet-sql-rowid-seek` +
      `-overflow`; 43/43 scljet-sql green non-memoized. NOTE: scljet SQL INSERT rejects rows too big for
      a leaf ("needs a split"), so SQL-created DBs never have overflow rows — the fallback only matters
      for fixture-built (`buildOverflowTableDatabase`) images. NEXT: index-seek (`WHERE indexedcol = K`
      via index-B-tree descent — the record-key comparison variant, the largest remaining perf piece).

- [x] **scljet-m7c-ipk-rowid-alias** — DONE 2026-07-15 (prerequisite the user prioritized: "сначала
      IPK-алиас"). `INTEGER PRIMARY KEY` aliases the rowid (SQLite semantics). On SQL INSERT the IPK
      column's value becomes the rowid (rows scan in that order, not insertion order); a NULL/absent IPK
      gets the next auto rowid (max+1) written back into the column; `SELECT ipk`/`SELECT rowid` return
      the same value; a duplicate IPK is rejected. `ipkColumnIndex`/`isIpkType` parse the type tokens;
      `executeInsert` routes both the plain and index-maintaining paths through `assignInsertRowids` +
      `insertSqlRowsLoop`. Non-IPK tables unchanged (sequential rowids); `buildTableDatabase` fixtures
      keep their explicit rowids (SQL-path feature only). Fixes a real divergence (scljet used to assign
      1,2,3 and store id as a plain column). Verified vs sqlite3 (non-sequential ids → rowid-order scan,
      id==rowid, auto-assign, column-list insert, duplicate rejection), int==js; conformance
      `scljet-sql-ipk-rowid`; 41/41 scljet-sql green non-memoized.

- [x] **scljet-m7b-jvm-jdbc-shim** — DONE 2026-07-15 (parallel small-feature lane of "оба параллельно";
      landed by sibling agent, code+build `9ac5d0a62`, spec `d4412a642`, on origin/main `68b2b9c1f`). The
      JVM `java.sql.Driver` shim (J2 of `specs/scljet-jdbc.md`), new module
      `v1/runtime/std/scljet-jdbc-plugin/`. **Bridge finding: JVM↔engine is clean via the embedded
      `scalascript.interpreter.Interpreter`** — bootstrap one interpreter running the same
      `std/scljet/index.ssc` + `std/scljet/jdbc.ssc` imports the conformance case uses, drive the façade
      via `Interpreter.invoke`, thread the DB image + ResultSet cursor as opaque `Value`s. **No engine
      changes.** Real `java.sql.Driver` for `jdbc:scljet:<target>` (`:memory:`/`classpath:`/host file,
      ServiceLoader-registered); Connection/Statement/PreparedStatement/ResultSet/*MetaData are
      `java.lang.reflect.Proxy` shims (supported subset; else `SQLFeatureNotSupportedException`).
      Autocommit/commit/rollback thread the image; host files = whole-image read-modify-rewrite.
      `build.sbt`: `scljetJdbcPlugin` on `backendInterpreter`+`scljetVfsPlugin`, NOT in `allPlugins` (would
      cycle). Test: `sbt scljetJdbcPlugin/test` **14/14 green** incl. a byte-for-value ResultSet
      cross-check vs `org.xerial:sqlite-jdbc`. Gotchas the agent fixed (shim-only): `globalsView` replaces
      case-class ctors with a placeholder → build SqliteValue leaves via `Value.singleValue`;
      `emptyDatabase` leaves schema-format/encoding 0 → patch header bytes 47→4, 59→1; the engine index
      needs the `jvm-vfs.ssc` externs → put `scljetVfsPlugin` on the classpath. JVM-only lane.

- [x] **scljet-m7a-typed-row-decoder** — DONE 2026-07-15 (parallel small-feature lane of "оба
      параллельно"; landed by sibling agent, `af4b65e17`). Typed row decoder on the typed-SQL surface:
      `runTypedQueryAs[S](dbBytes, q, decode: List[SqliteValue] => S): Either[String, List[S]]` maps each
      raw result row through a caller `decode` → typed records; plus bounds-safe 0-based cell extractors
      `cellLong`/`cellText`/`cellReal`/`cellIsNull` (same BigInt-safe coercion as the JDBC façade — text/
      int via 0L-seeded leading-int parse, reals via Double-space parse / direct `SqlReal.value`, never
      `.toInt`/`.toDouble`; SqlNull→0/0.0/""). Verified vs sqlite3 (decode into `case class Emp`, NULL
      column → cellIsNull/cellLong=0), int==js; conformance `scljet-typedsql-decode`; all 4 typedsql
      cases green. GOTCHA recorded by the agent: a fresh worktree needs `sbt cli/installBin` — the main
      checkout's Jul-13 jars miscompile current scljet (`Method not found: fullPath on ImageVfs`).

- [x] **scljet-m6z-limit-pushdown** — DONE 2026-07-15 (first perf op of the physical-access-path
      direction, Sergiy: "оба параллельно"). LIMIT pushdown / early scan termination. A single-table
      `SELECT` with a `LIMIT` and no `ORDER BY`/aggregate/`GROUP BY`/`DISTINCT` keeps rows in scan
      order, so only the first `offset+limit` matching rows are needed. `executeSelectSingle` split into
      `executeSelectLimited` (new `collectRowsLimited` stops the cursor walk once that many WHERE-matching
      rows are collected) + `executeSelectFullScan` (unchanged). Byte-identical to the full scan; the
      table tail is never read (O(offset+limit) vs O(n)). Wins: pagination-without-sort, `LIMIT 1`
      existence checks. Verified vs sqlite3 (LIMIT/OFFSET, filtered LIMIT, LIMIT 0, LIMIT beyond size,
      ORDER BY LIMIT via full scan), int==js; conformance `scljet-sql-limit-pushdown`; 40/40 sql green.
      NOTE on the bigger physical IR: a true index/rowid *seek* is a genuine multi-turn build (B-tree
      descent + overflow-safe cell decode + decode params) that must be differential-tested (seek vs
      full-scan vs sqlite oracle) before it can safely land — this LIMIT pushdown is the first safe,
      verified perf op; the seek lands next on a differential-tested seam. Parallel small-feature lanes
      (typed row decoder, JVM JDBC shim) delegated to sibling agents.

- [x] **scljet-m6y-typed-sql-join** — DONE 2026-07-15 (extends m6w). Typed SQL joins. `TypedQuery`
      gains a `joins` field (defaulted Nil, threaded through every builder); `joinOn`/`leftJoinOn` append
      a `JoinSpec` with qualified column names (`owner.name`) from the joined columns, erasing to
      `SelectStmt.joins` so the executor's join path runs it; `projColQ` gives qualified join projections.
      Inner join, LEFT join (NULL-extended unmatched rows), and aggregate/GROUP BY over a join verified
      byte-identical vs reference sqlite3, int==js; conformance `scljet-typedsql-join`; all 3 typed-SQL
      cases green non-memoized. **Typed HAVING also landed** (`havingQ` + `havingCount`/`Sum`/`Max`/`Min`,
      `TypedQuery.having`; conformance `scljet-typedsql-basic` q11). The typed surface now covers full
      CRUD + joins (inner/LEFT) + aggregates + GROUP BY + HAVING + ORDER BY + DISTINCT + LIMIT — the
      whole current executor. NEXT (deferred): the spec's LogicalPlan/PhysicalOp IR with index-aware
      access paths (`cursorSeek`/`RangeScan`/`IndexSeek`) — the perf win over the always-full-scan
      `executeSelectSingle`; and a typed row decoder (`Table[R]`/`Query[R]` → typed output records).

- [x] **scljet-m6x-typed-sql-dml** — DONE 2026-07-15 (extends m6w). Typed SQL writes — the typed
      front door now has full CRUD. `insertRows`/`insertColsRows`, `updateSet`/`updateAllSet` with typed
      `setInt`/`setReal`/`setText`/`setExpr` assignments (value/expr type must match the column type),
      `deleteWhere`/`deleteAll`, erasing to the existing `InsertStmt`/`UpdateStmt`/`DeleteStmt` and run
      via `executeInsert`/`executeUpdate`/`executeDelete` (`runInsert`/`runUpdate`/`runDelete`). Verified
      vs reference sqlite3 (INSERT rows, UPDATE with `setExpr` salary+10 and `setText`, DELETE by
      predicate, read-after-write on the threaded image), int==js; conformance `scljet-typedsql-dml`;
      full scljet gate 80/80 green non-memoized. NEXT (typed surface): joins (`joinOn`/`leftJoinOn`) +
      HAVING; then the spec's LogicalPlan/PhysicalOp IR with index-aware access paths.

- [x] **scljet-m6w-typed-sql-surface** — DONE 2026-07-15 (typed-SQL-API lane of "все три … параллельно";
      first stage of `specs/scljet-typed-sql.md`). `scljet/typedsql.ssc` — a typed embedded query API
      (ScalaScript values, not SQL strings). `Column[T]`/`Expr[T]` carry a compile-time SQL type so an
      ill-typed predicate (a `Column[Long]` vs a `textLit: Expr[String]`) does not compile; ssc generics
      verified to erase cleanly on int+js. Combinators (`from`/`whereQ`/`selectCols`/`selectStar`/
      `groupByQ`/`orderByQ`/`limitQ`/`distinctQ`; `eqp`/…/`gep`/`betweenp`/`inp`/`likep`/`isNullp`/`andp`/
      `orp` with correct AND-over-OR distribution; `projCol`/`countStar`/`sumCol`/`min`/`max`/`avgCol`)
      erase to the existing `SxNode`/`Condition`/`ProjItem`/`SelectStmt` and run through the same executor
      via a new additive `runSelectStmt` — so a typed query and the equivalent SQL string give byte-
      identical rows. Verified vs reference sqlite3 (star, comparisons, AND, IN, BETWEEN, LIKE, COUNT/SUM,
      GROUP BY, ORDER BY, DISTINCT), int==js; conformance `scljet-typedsql-basic`. Built the surface
      before the spec's T0 IR-adapter (user-facing deliverable first; the IR/physical-planner with index
      access paths is the later perf stage). NEXT: typed DML + joins/HAVING; then the LogicalPlan/PhysicalOp IR.

- [x] **scljet-m6v-jdbc-facade** — DONE 2026-07-15 (JDBC-API lane of "все три … параллельно"; J1 core
      of `specs/scljet-jdbc.md`). Portable `scljet/jdbc.ssc` — a `java.sql`-shaped front door in pure
      ScalaScript for `[int, js]`. `JdbcConnection` (current image + autocommit/working-image;
      `jdbcCommit`/`jdbcRollback`/`jdbcSetAutoCommit` promote/discard); `jdbcExecuteUpdate*` threads the
      new image per the autocommit rules and returns `JdbcUpdate(conn, changes, lastInsertRowid)`;
      `jdbcExecuteQuery*` → forward-only `JdbcResultSet` (`rsNext` advances a functional cursor). Typed
      getters `rsGetLong/Int/Double/String/Boolean`, `rsIsNull`, by-index + by-label (`rsFindColumn`
      case-insensitive), metadata `rsColumnCount`/`rsColumnLabel` (labels from `projItemNames` or
      `imageTableColumns`). Value conversions route through `coerceText` + Double/Int string-parse
      (`parseLongStr`/`parseDoubleStr`, 0L/0.0 seeds, bounds-safe `codeAt`) to stay BigInt-safe on JS.
      `?` params flow via `queryImageParams`/`executeMutationCountedParams`. Verified end-to-end vs
      reference sqlite3 (autocommit INSERT + count/rowid, ResultSet walk over INTEGER/TEXT/REAL, by-label,
      metadata, parameterized UPDATE + count, NULL-column read), int==js; conformance `scljet-jdbc-basic`.
      NEXT: the stateful JVM `java.sql.Driver` shim (J2), blob/BigDecimal getters, column-type metadata.

- [x] **scljet-m6u-param-binding** — DONE 2026-07-15 (JDBC prereq). `?` positional parameters. Lexer
      emits a `param` token (1-based ordinal in `num`; `?NNN` explicit); `Token` gains a defaulted
      fourth field `bound: Option[SqliteValue] = None` (keeps all 30 `Token(k,t,n)` sites valid — default
      case-class args verified on int+js); `bindParams(toks, params)` rewrites each `param` → a `bound`
      token; `parseExprAtom`/`litValue` gain a `bound` branch → the value reaches the parser as an
      `SxLit`, indistinguishable from an inline literal, so integer/real/text/blob/NULL params flow
      through WHERE / projections / VALUES / SET with zero string interpolation. New `queryImageParams`
      / `executeMutationCountedParams`; `queryImage`/`executeMutationCounted` delegate (bindParams with
      `Nil` is identity for param-free SQL). Verified vs sqlite3 (int/text/real params, param arithmetic,
      parameterized INSERT + count), int==js; conformance `scljet-sql-params`; 38/38 scljet-sql green.

- [x] **scljet-m6t-mutation-count** — DONE 2026-07-15 (JDBC prereq). `executeMutationCounted`/`…Params`
      → `MutationResult(image, changes, lastInsertRowid)`, the counted sibling of `executeMutation`.
      Counts derived from a read pass over the affected table (INSERT → #value-tuples + lastRowid =
      maxRowid+n; DELETE/UPDATE → #rows matching WHERE = sqlite `changes()`; CREATE/DROP → 0), so they
      agree with the mutation without threading a count through the large executor bodies. Row counts
      accumulate from 0L seeds / `longAdd` for BigInt-safety. Verified vs reference sqlite3
      `changes()`/`last_insert_rowid()`, int==js; conformance `scljet-sql-mutation-count`.

- [x] **scljet-m6s-real-literal** — DONE 2026-07-15 (SQL polish; prereq the JDBC INSERT of `4.5`
      exposed). REAL number literals in SQL text. The lexer reads a `digits.digits` fraction as a REAL
      literal (value parsed in Double space, carried on `Token.bound` as `SqlReal`); `litValue` and
      `parseExprAtom` gain a `real` branch → an `SxLit`. Real literals now work in `VALUES`, `WHERE`,
      projections and arithmetic (previously `INSERT … VALUES (..,4.5)` failed at the `.`). Integer-valued
      reals render with a trailing `.0` (`10.0`), matching the sqlite3 CLI. Verified vs sqlite3, int==js;
      conformance `scljet-sql-real-literal`; 39/39 scljet-sql green non-memoized.

- [x] **scljet-m6r-left-join3** — DONE 2026-07-15 (SQL-polish lane of "все три … параллельно"). LEFT
      joins over 3+ tables in the N-table path. `extendPartials` now keeps a partial join-row that
      matched no row of the next table, NULL-extended via `nullRow` = `StorageRecord(None,
      DecodedRecord(0,0,Nil))` — an empty record, so `fieldValueAt`/`multiColValue` read back SqlNull
      for every column of that table. NULLs propagate down the chain (a later LEFT join sees NULL on
      its ON and NULL-extends again); a later inner `JOIN` on the now-NULL side drops the row. Written
      all-if/else-expression (`hit`/`matched` flags, no bare `if cond then <assign>`). WHERE (incl.
      `IS NULL`), ORDER BY, `COUNT(*)` verified byte-identical vs reference sqlite3, int==js;
      conformance `scljet-sql-left-join3` (4 queries); 36/36 sql. The 2-table LEFT path (m5o) is
      unchanged. Remaining join follow-ups: RIGHT/FULL outer (SQLite has RIGHT/FULL since 3.39), and
      multi-table-with-indexes DML (still errors).

- [x] **scljet-m6q-join3-agg** — DONE 2026-07-15 (follow-up to m6o; Sergiy "все три … параллельно").
      Aggregates / GROUP BY / HAVING over 3+ table joins. Extends `multiJoinExecute`: a group is a list
      of joined rows; `multiAggValue` extracts the arg column across all tables (`multiColValue`) and
      reduces (COUNT(*)=group length); `evalExprMultiGroup` evaluates an expr over a group (SxAgg
      reduces, bare column → group's first joined row); `partitionMultiRows` splits the sorted joined
      rows into GROUP BY runs; `havingHoldsMulti` filters; `projectMultiGroupRow` projects; a no-GROUP-BY
      aggregate query reduces the whole set to one row. Written all-if/else-expression (no bare
      `if cond then <assign>`, which the interpreter mishandles). Verified vs sqlite3 (GROUP BY city
      with COUNT/SUM/MAX, HAVING, ORDER BY over groups, total COUNT), int==js; conformance
      `scljet-sql-join3-agg`; 35/35 sql. NOTE: `ROUND`/float functions still deferred (renderReal vs
      sqlite `%.15g` + `.toLong`/`.toDouble` JS lowering = the float-format rabbit hole, same as AVG
      repeating decimals). Two future milestones (typed-SQL-API, JDBC-API) have SPECS being drafted by
      sibling agents this session; correlated subqueries / EXISTS + multi-table-with-indexes DML remain.

- [x] **scljet-m6p-subquery** — DONE 2026-07-15 (Sergiy "еще что-то? … тоже нужно сделать"). Non-
      correlated subqueries via a token-substitution PRE-PASS (`resolveSubqueries`, wired into both
      `queryImage` and `executeMutation`): find a `( SELECT … )`, `evalSubquery` it once, and splice its
      first-column result back as tokens — a value list `( v1, v2, … )` when the `(` follows `IN`, else a
      single scalar value. Recurses so nested subqueries resolve. Supports `WHERE col IN (SELECT …)`,
      `NOT IN`, and scalar `col op (SELECT agg …)`, plus subqueries in DELETE/UPDATE WHERE. Non-invasive
      to the AST (no Condition/SelectStmt change). Verified vs sqlite3 (IN, NOT IN, `= (SELECT MAX)`,
      `> (SELECT AVG)`, COUNT with IN), int==js; conformance `scljet-sql-subquery`. SCOPE: non-correlated,
      integer/text results (real→truncated, empty-scalar→0 are edges). RE-HIT the interpreter bug
      `interp-if-then-no-else`: a bare `if !first then accRev = comma :: accRev` inside the value-list
      builder was SILENTLY SKIPPED (only 4 of 5 tokens produced) → use the if/else EXPRESSION form
      `accRev = if first then accRev else …`. Follow-up: correlated subqueries, EXISTS, FROM-subqueries.

- [x] **scljet-m6o-join3** — DONE 2026-07-15 (Sergiy "joins тоже нужно сделать"). 3+ table inner joins
      (`FROM a JOIN b ON … JOIN c ON …`). `SelectStmt.join: Option[JoinSpec]` → `joins: List[JoinSpec]`;
      `parseJoins` chains JOIN clauses; `executeSelect` routes 0→single, 1→existing full-featured 2-table
      path, 2+→new `multiJoinExecute`. The N-table path uses a general list-of-rows model (a joined row =
      `List[StorageRecord]` parallel to the tables): `multiColValue` resolves a column across all tables
      (qualified `t.col`→that table, bare→first table with it); an incremental nested loop
      (`buildJoinRows`/`extendPartials`) applies each ON; `multiWhereHolds`/`projectMultiRow`/
      `evalExprMulti` reuse the expression engine + `predHolds`; ORDER BY via `sortMultiRows`, plus
      DISTINCT + LIMIT. Verified vs sqlite3 (3-table join with qualified cols, WHERE, ORDER BY DESC,
      DISTINCT, `||` expression), int==js; conformance `scljet-sql-join3`. SCOPE: inner joins only for
      3+ tables (aggregates/GROUP BY/HAVING/outer over 3+ tables = follow-up; the 2-table path keeps full
      support). Remaining big: subqueries.

- [x] **scljet-m6n-drop-index** — DONE 2026-07-15. `DROP INDEX [IF EXISTS] name` rebuilds the
      single-table DB with the remaining indexes via `reindexTable` (dropped index's schema row + pages
      gone, no orphans → integrity_check ok). `tableIndexInfosExcept` (exclude by name), `findIndexEntry`,
      `executeMutation` routes DROP. Multi-index fully exercised (build 2 indexes, drop 1 — the remaining
      one still used by the planner, dropped column → SCAN). Verified vs sqlite3, int==js; conformance
      `scljet-sql-drop-index`. **INDEXES COMPLETE**: CREATE INDEX (m6l), maintenance on INSERT/UPDATE/
      DELETE (m6m), DROP INDEX + multi-index (m6n), all single-table + reference-validated. Remaining
      index follow-up: multi-table-with-indexes DML (currently errors).

- [x] **scljet-m6m-index-maintenance** — DONE 2026-07-15 (Sergiy "с индексами всё сделай"). SQL
      `INSERT`/`UPDATE`/`DELETE` now keep a table's indexes consistent. Approach: for a single-table DB
      whose table has 1+ indexes, each DML computes the NEW full row set (`SqlRow` = rowid + values) and
      rebuilds the whole database compactly via new write-layer `buildSingleTableIndexed` (table B-tree
      at page 2, each index B-tree at a successive root, all `sqlite_schema` roots reassigned) — no
      orphaned pages, so reference `integrity_check` stays ok. `executeInsert` appends the inserted
      SqlRows, `executeDelete` drops the victims (`keepSqlRows`), `executeUpdate` replaces WHERE-matched
      rows' values (`updatedSqlRows`) — so updating an indexed column moves its entry. A table with NO
      index keeps the fast incremental path; a MULTI-table DB with indexes errors (documented follow-up).
      Helpers: `tableIndexInfos` (enumerate a table's indexes + parse their key columns from the CREATE
      INDEX sql), `indexEntriesAll`/`sqlRowsToKeyed`, cc/sc read BigInt-safely via `toIntVal(SqlInteger
      (header.changeCounter))`. Plumbing: `buildSingleTableIndexed`/`KeyedRawRow`/`SchemaIndex`/
      `SchemaTable` exported write.ssc→index.ssc→sql.ssc. VERIFIED end-to-end vs reference sqlite3
      (INSERT+DELETE+UPDATE-of-indexed-column sequence → integrity_check ok, planner `SEARCH … USING
      INDEX`, new/updated/deleted keys all reflected). int==js byte-identical; conformance
      `scljet-sql-index-maintain`; FULL scljet suite green. FOLLOW-UPS: DROP INDEX, multi-table-with-
      indexes DML, multi-column-index maintenance is supported (keyColumns is a list).

- [x] **scljet-m6l-create-index** — DONE 2026-07-14 (Sergiy chose CREATE INDEX as the next big item).
      `CREATE INDEX idx ON t(col [, col]*)` on an existing DB via `executeMutation`. `parseCreateIndex`
      → `CreateIndexStmt`; `executeCreateIndex` opens the table, reads its rows, builds one `IndexEntry`
      per row preserving the **real rowid** (`buildIndexEntriesFromRecords` → `encodeRecord(keycols ++
      rowid)`), sorts them, builds the index B-tree at a new root page appended at EOF
      (`buildIndexTree` — reused from the write layer), appends the index's `sqlite_schema` row to page 1
      (mirrors `executeCreate`), patches the header page count, stages the index pages one-per-
      `ByteSlice.fromList` (JS-safe), and commits. Plumbing: exported `buildIndexTree`/`sortIndexEntries`/
      `IndexTreeBytes` from write.ssc → index.ssc → sql.ssc. VERIFIED end-to-end vs reference sqlite3
      3.53.3: `PRAGMA integrity_check` = ok (cross-validates the index against the table), the index
      appears in `sqlite_master`, and `EXPLAIN QUERY PLAN` shows `SEARCH emp USING INDEX idx_dept
      (dept=?)` — the planner actually uses it. int==js byte-identical (exact bytes locked); conformance
      `scljet-sql-create-index`. This is the storage-side prerequisite for the typed-SQL-API (index-seek
      access paths). Follow-ups: DROP INDEX, index maintenance on INSERT/UPDATE/DELETE through the SQL
      layer (the raw `deleteRowidsIndexed`/`updateRowIndexed` exist in mutate.ssc but aren't wired to SQL).

- [x] **scljet-m6k-no-from** — DONE 2026-07-14. `SELECT <exprs>` with no `FROM` → one computed row.
      `parseSelect` builds a `SelectStmt` with `table = ""` when FROM is absent; `queryImage` routes
      `table == ""` to new `executeNoFrom`, which evaluates each projection item's expr with
      `evalExprValues(e, Nil, Nil)` (empty row) — the whole expression engine (arithmetic, `||`,
      functions, `CASE`, `%`, comparisons) with no DB access. Verified vs sqlite3 (`SELECT 1+1`,
      `UPPER('hello')`, `'a'||'b'||'c'`, `CASE …`, `10 % 3`, `SUBSTR(...)`, `5 > 3`), int==js;
      conformance `scljet-sql-no-from`.

- [x] **scljet-m6j-agg-expr** — DONE 2026-07-14. Aggregates inside expressions — `COUNT(*) + 1`,
      `MAX(salary) - MIN(salary)`, `SUM(salary) / COUNT(*)`, `COUNT(*) * 10` per GROUP BY group,
      `HAVING COUNT(*) * 100 > 150`, `'total:' || SUM(salary)`. New `SxAgg(func, arg, distinct)` AST
      node; `parseExprAtom` parses an aggregate function call as `SxAgg` (via `parseAggNode`), so
      `parseProjItem`/`parseHavingItem` now parse the whole item as an expression and unwrap a lone
      `SxAgg` to the backward-compatible isAgg `ProjItem` (parseHavingItem uses `parseExprAdd` to stop
      before the comparison). `exprHasAgg`/`projHasAgg` detect aggregates anywhere in an expr tree.
      New group-aware evaluators `evalExprGroup` (single) + `evalExprGroupJoin` (join) reduce `SxAgg`
      over the group/pairs (bare column → group's first row) — wired into `projectGroupRow`,
      `havingHolds`, and `computeJoinAgg` (refactored via `joinAggValue`). Per-row evaluators return
      NULL for a stray `SxAgg`. Verified vs sqlite3 (standalone, per-group, HAVING, concat), int==js;
      conformance `scljet-sql-agg-expr`; full scljet-sql suite 28/28 (no regressions across the
      existing aggregate/group/having/join-agg tests).

- [x] **scljet-m6i-modulo** — DONE 2026-07-14. `%` modulo operator (multiplicative precedence, with
      `* /`). Lexer emits a `%` op token; `parseExprMul` handles it; `arithValue` computes integer
      modulo (operands → Long via `toLongVal` — exact for integers, truncating for reals, matching
      sqlite; NULL on a zero divisor) via BigInt-safe `longMod` (`0L`-seeded → `_arith('%',…)`,
      truncates toward zero). Works in projection and WHERE. Verified vs sqlite3 (`n%3`, `n%3+1`,
      `20%n`, `WHERE n%2=0`), int==js; conformance `scljet-sql-modulo`.

- [x] **scljet-m6h-orderby-expr** — DONE 2026-07-14. `ORDER BY <expression>` — an ORDER BY key may be
      any scalar expression, not just a column: `ORDER BY salary * -1`, `ORDER BY UPPER(name)`,
      `ORDER BY LENGTH(name), name`, `ORDER BY salary * 2 DESC LIMIT 3`. `OrderKey.column: String` →
      `expr: SxNode`; `parseOrderLimit` parses each key with `parseExpr` (stops before ASC/DESC/comma/
      LIMIT); `recCompare` (single) evaluates via `evalExpr`, `pairOrderCompare` (join) via
      `evalExprJoin`; `groupKeysOf` wraps a group column as `SxCol`. Multi-key + per-key ASC/DESC +
      stability preserved. Verified vs sqlite3, int==js; conformance `scljet-sql-orderby-expr`.

- [x] **scljet-m6g-bool-ops** — DONE 2026-07-14. Boolean operators `AND`/`OR`/`NOT` in expressions
      (below comparison; sqlite precedence `OR < AND < NOT < comparison`). New `parseExprOr`/
      `parseExprAnd`/`parseExprNot` layers; `SxNot` AST node; `andValue`/`orValue`/`notValue` implement
      three-valued logic (NULL-aware: `0 AND NULL = 0`, `1 OR NULL = 1`, else NULL). `AND`/`OR` route
      through `arithValue`, `SxNot` handled in all three evaluators. Works in the projection
      (`SELECT salary > 200 AND dept = 10` → 0/1), inside `CASE WHEN` (`WHEN a AND b`), and — via the
      existing WHERE condition chain — with bare truthy columns (`WHERE active AND salary > 100`).
      Verified vs sqlite3, int==js; conformance `scljet-sql-bool`.

- [x] **scljet-m6f-case** — DONE 2026-07-14. `CASE` expression — searched (`CASE WHEN cond THEN r …
      [ELSE r] END`) and simple (`CASE operand WHEN v THEN r … END`). To support it, comparisons became
      first-class expressions: new lowest-precedence `parseExprCompare` makes `a op b` an `SxBin` that
      yields `1`/`0`/NULL (so `SELECT salary > 200` returns 0/1, like sqlite); WHERE operands stay on
      `parseExprAdd` so `parseCondition` still splits `WHERE a > b`. New `SxCase`/`SxWhen` AST +
      `parseCase`/`parseCaseWhens`; `evalCase{Single,Join,Values}` pick the first matching WHEN
      (operand-equality or truthiness) then ELSE/NULL — wired into all three evaluators. Also `WHERE
      <expr>` (a bare boolean expression, e.g. `WHERE CASE…END`) is now a `truthy` predicate.
      Verified vs sqlite3 (searched/simple, no-ELSE→NULL, arithmetic results, bare comparison, CASE in
      WHERE), int==js; conformance `scljet-sql-case`. BUG FIXED en route: parseCondition's
      `tkIsKw(after,"NOT") && tkIsKw(after.tail,…)` crashed the interpreter on an empty `after` (bare
      CASE-WHERE) because interp `&&` doesn't short-circuit — nested the checks so `after.tail` is only
      reached when `after` is non-empty (BUGS.md `interp-boolean-operators-no-short-circuit`).

- [x] **scljet-m6e-string-functions** — DONE 2026-07-14. String functions `SUBSTR(s,y[,z])` (1-based,
      `y<0` counts from the right, window clamped), `TRIM`/`LTRIM`/`RTRIM` (default trims spaces,
      optional char set), `REPLACE(s,from,to)` (all non-overlapping). Added to `evalCall` with
      bounds-safe char helpers (`sliceStr`/`charInSet`/`ltrimCount`/`rtrimEnd`/`matchesAt`/`replaceStr`);
      compose with the other functions and `||` and work in WHERE. Verified vs sqlite3 incl. the
      significant leading/trailing spaces of LTRIM/RTRIM, int==js; conformance `scljet-sql-strfunc`.
      JS GOTCHA (extends `js-userspace-long-arith-native-operator-mixes-bigint`): converting a
      SqliteValue to a plain Int for char indexing — `Long.toInt` mislowers to `Math.trunc(BigInt)`
      (crash) and `.toDouble` lowers unpredictably (identity `(x)` vs `_dispatch`); robust fix is to
      parse the digits from the TEXT form (`coerceText`) with plain-Int `n=n*10+digit` arithmetic
      (`parseIntStr`, no BigInt ever).

- [x] **scljet-m6d-concat** — DONE 2026-07-14. `||` string concatenation. Lexer emits a `||` op token;
      new `parseExprConcat` precedence level sits between `* /` and the atoms (sqlite binds `||` tighter
      than `*`); `arithValue` handles op `||` first via `concatValue` (NULL if either side NULL, else the
      `coerceText` forms joined, so numeric operands coerce to text). Works everywhere `SxBin` is
      evaluated (projection / WHERE / UPDATE SET) and chains + composes with scalar functions
      (`UPPER(first) || '-' || LOWER(last)`). Verified vs sqlite3 (chained, numeric coercion, NULL
      propagation, with functions, in WHERE), int==js; conformance `scljet-sql-concat`.

- [x] **scljet-m6c-scalar-functions** — DONE 2026-07-14. Scalar functions inside expressions:
      `UPPER`/`LOWER` (ASCII), `LENGTH` (char count of the text form), `ABS`, `COALESCE` (first
      non-null). New `SxCall(func, args)` AST node; `parseExprAtom` parses `ident(` as a call (via
      `parseCallArgs`, comma-separated `parseExpr`), else a column — so functions compose with
      arithmetic and work in both projection and `WHERE` (`WHERE LENGTH(name) > 3`,
      `WHERE UPPER(name) = 'BOB'`). `evalCall` runs over evaluated args (NULL arg → NULL, except
      COALESCE); wired into all three evaluators (`evalExpr`/`evalExprJoin`/`evalExprValues`). Does not
      collide with aggregate parsing (aggregates are caught earlier by `isAggStart`). Verified vs
      sqlite3, int==js; conformance `scljet-sql-func`. Next candidates: `||` concat, more functions
      (SUBSTR/ROUND/TRIM), `CASE WHEN`.

- [x] **scljet-m6b-update-set-expr** — DONE 2026-07-14. `UPDATE t SET col = <expr>` — the assignment
      RHS may be a scalar expression over the row: self-reference (`salary = salary + 100`,
      `salary = salary * 2`), cross-column (`salary = salary - bonus`, `bonus = salary / 10`), and a
      multi-assignment column **swap** (`SET salary = bonus, bonus = salary`). `Assignment` gained
      `expr: Option[SxNode]` (bare literal keeps `value`); `parseAssignments` parses the RHS with
      `parseExpr`; `applyAssignments` evaluates every assignment's RHS against the **pre-update** row
      values via new `evalExprValues` (values+colNames, no StorageRecord needed) — so the swap and
      `n = n + 1` are correct, matching sqlite. Verified vs sqlite3 (5-step sequence incl. swap and
      integer division), int==js; conformance `scljet-sql-update-expr`.

- [x] **scljet-m6a-where-expr** — DONE 2026-07-14. Scalar expressions in `WHERE`. Either side of a
      comparison may now be an arithmetic expression, a column, or a literal: `salary * 2 > 400`,
      `salary > cost * 2`, column-to-column (`salary > cost`), literal LHS (`250 >= salary`), composed
      with `AND`/`OR`, and across a join (`emp.salary > dept.base * 2`). `Condition` gained
      `leftExpr`/`rightExpr: Option[SxNode]` (a bare column/literal keeps the name/`value` fields so the
      join path's `joinColValue` still works; anything compound is carried as an `SxNode`).
      `parseCondition` now parses each side with `parseExpr`; `predHolds` takes resolved (left, right)
      values; `condHolds` resolves via `evalExpr`, `joinCondHolds` via new `evalExprJoin` (columns
      resolve across both tables). Reuses the `arithValue` BigInt-safe helpers from m5z (no new JS
      issues). Verified vs sqlite3 (both-side arithmetic, column-to-column, literal LHS, AND, integer
      division, join with cross-table expression), int==js; conformance `scljet-sql-where-expr`.

- [x] **scljet-m5z-projection-expr** — DONE 2026-07-14. Scalar expressions in the projection:
      `SELECT salary * 12`, `(salary + 50) * 2`, `salary * 2 + 1`, `-salary`, `salary / 100`. Lexer
      now emits `+ - /` op tokens; new `SxNode` AST (SxCol/SxLit/SxNeg/SxBin) + recursive-descent
      `parseExpr` (precedence unary `-` → `* /` → `+ -`, parens); `evalExpr`/`arithValue` do SQL numeric
      arithmetic (integer result + truncating division when both int, else real; NULL on null operand or
      /0). A lone column stays the plain name-based ProjItem so GROUP BY / ORDER BY / DISTINCT are
      unaffected; expressions run on the single-table non-aggregate path. Verified vs sqlite3 (precedence,
      parens, unary minus, integer division, expr+WHERE+ORDER BY), int==js; conformance `scljet-sql-expr`.
      TWO bugs found+fixed en route: (1) a dangling `else` after a `match` in `parseProjItem` aborted the
      whole INT module load (JS miscompiled it); (2) native JS `x*y`/`x/y` on Long crashed with
      `Cannot mix BigInt and other types` because scljet decodes small ints to JS Numbers while literals
      are BigInt — routed integer arithmetic through `0L`/`1L`-seeded helpers that emit the BigInt-safe
      `_arith` (BUGS.md `js-userspace-long-arith-native-operator-mixes-bigint`). SCOPE: single-table,
      non-aggregate; expression in a join/aggregate/GROUP BY projection is not yet evaluated.

- [x] **scljet-m5y-insert-multi** — DONE 2026-07-14. Multi-row `INSERT INTO t VALUES (…),(…),(…)`
      (plain and with a column list). `InsertStmt.values: List[SqliteValue]` → `rows:
      List[List[SqliteValue]]`; new `parseValueRows` loops `parseValueList` over comma-separated
      tuples; new `insertRowsLoop` inserts every row (rowid = maxRowid+1, +2, …) into ONE mutable
      pager via `pagerInsertBalanced`, then a single `mutableCommit`. Unlisted columns fill NULL per
      row (reuses `reorderInsertValues`). Verified vs sqlite3 (3-tuple plain + 2-tuple column-list,
      then SELECT/COUNT/IS NULL/ORDER BY), int==js; conformance `scljet-sql-insert-multi`.

- [x] **scljet-m5x-agg-distinct** — DONE 2026-07-14. Aggregate `DISTINCT`: `COUNT(DISTINCT col)`,
      `SUM(DISTINCT col)`, `AVG(DISTINCT col)` (and MIN/MAX/TOTAL) aggregate over the distinct non-null
      argument values. `AggItem`/`ProjItem` gained a `distinct: Boolean`; `parseProjItem` recognizes
      `FUNC(DISTINCT arg)`; `computeAgg` + `computeJoinAgg`, when distinct, dedup the extracted values
      (`dedupValues` via sqlCompare, `extractColumnValues`) before `aggregateValues`. Works standalone
      and per GROUP BY group; nulls ignored, matching sqlite. Verified vs sqlite3 (COUNT/SUM/AVG DISTINCT
      vs plain, GROUP BY COUNT(DISTINCT)), int==js; conformance `scljet-sql-agg-distinct`.

- [x] **scljet-m5w-where-like** — DONE 2026-07-14. `col LIKE pattern` / `col NOT LIKE pattern`:
      `%` = any run (incl. empty), `_` = one char; ASCII case-insensitive (`upperStr` both sides),
      non-text operand coerced to text (`dept LIKE '1%'`), no ESCAPE clause. `parseCondition` adds
      the LIKE / NOT LIKE forms (ops `like`/`notlike`, pattern in `value`); `predHolds` calls new
      `likeMatch` (iterative two-pointer wildcard match with `%`-backtracking, charCode bounds-safe so
      it survives the interpreter's non-short-circuit `&&`) + `coerceText`. Verified vs sqlite3
      (prefix/suffix/`_`/exact case-insensitive, NOT LIKE, integer LIKE, LIKE+AND), int==js;
      conformance `scljet-sql-where-like`. WHERE predicate set now: =/<>/</>/<=/>=, IS [NOT] NULL,
      BETWEEN, IN/NOT IN, LIKE/NOT LIKE — all composable with AND/OR.

- [x] **scljet-m5v-where-between-in** — DONE 2026-07-14. WHERE range/set predicates: `col BETWEEN lo
      AND hi` (inclusive; BETWEEN owns its inner AND, a trailing AND still chains), `col IN (v1, …)` and
      `col NOT IN (…)` (membership over a literal list, text or integer). `Condition` gained a
      `values: List[SqliteValue]` field carrying the multi-operand list (ops `between`/`in`/`notin`);
      `parseCondition` recognizes the three forms (IN reuses `parseValueList`); a shared `predHolds(cond,
      value)` evaluates every predicate and is reused by `condHolds` + `joinCondHolds`. NULL operand →
      false for all three. Verified vs sqlite3 (ranges, int/text IN, NOT IN, BETWEEN+AND, IN+ORDER BY,
      COUNT), int==js; conformance `scljet-sql-where-set`. Deferred: `LIKE` (wildcard match) = m5w.

- [x] **scljet-m5u-where-and-or** — DONE 2026-07-14. Compound `WHERE` with `AND` / `OR`. `parseWhere`
      now parses a chain of comparisons into OR-of-ANDs (`List[List[Condition]]`), honoring SQL
      precedence (`AND` binds tighter than `OR`, no parens): `a AND b OR c AND d` = `(a AND b) OR
      (c AND d)`. New `parseCondition` (one comparison incl. `IS [NOT] NULL`) + `parseConditionChain`
      (the AND/OR loop). `whereHolds`/`joinWhereHolds` became `condHolds`→`andGroupHolds`→any-OR-group;
      `SelectStmt`/`DeleteStmt`/`UpdateStmt.where` retyped `Option[Condition]` → `List[List[Condition]]`
      (empty = no filter). Verified vs sqlite3 (AND, OR, mixed-precedence, ranges, over COUNT + ORDER BY),
      int==js; conformance `scljet-sql-where-bool`. WHERE now applies uniformly to SELECT/DELETE/UPDATE.

- [x] **scljet-m5t-is-null** — DONE 2026-07-14. `WHERE col IS NULL` / `col IS NOT NULL`: `parseWhere`
      recognizes the `IS [NOT] NULL` form (ops `isnull`/`notnull`); `whereHolds`/`joinWhereHolds` test
      the value's nullness. Works on a single table (columns unset by a column-list INSERT) and on the
      NULL-extended side of a LEFT JOIN. Verified vs sqlite3, int==js; conformance `scljet-sql-null`.

- [x] **scljet-m5s-join-group-by** — DONE 2026-07-14. GROUP BY (+ HAVING) over a join — joins now at
      full SELECT parity. `joinExecute`, when GROUP BY is present, sorts the matched `JoinPair`s by the
      group key (`sortPairsBy`), partitions into consecutive runs (`partitionPairs`/`pairGroupEqual`),
      drops groups failing HAVING (`filterPairGroups`/`havingHoldsPairs` via `computeJoinAgg`), and emits
      one row per group (`mapPairGroups` → `computeJoinAggregates`). Verified vs sqlite3 (`GROUP BY b.col`
      with COUNT/SUM/MIN over qualified columns, `GROUP BY … HAVING COUNT(*) >= n`), int==js; folded into
      `scljet-sql-join` (16 queries). **JOINS COMPLETE**: inner + LEFT, aggregates, DISTINCT, ORDER BY,
      GROUP BY, HAVING — all byte-verified vs sqlite3.

- [x] **scljet-m5r-join-orderby** — DONE 2026-07-14. ORDER BY over a join: `sortPairsBy` stable
      merge-sorts the matched `JoinPair`s by the order keys (`pairOrderCompare` via `joinPairValue`,
      per-key ASC/DESC) before projection, so `ORDER BY` on any qualified/bare column of either table
      works with LIMIT/OFFSET. Verified vs sqlite3 (`ORDER BY a.col`, `ORDER BY b.col, a.col DESC`,
      `ORDER BY … DESC LIMIT`), int==js; folded into `scljet-sql-join` (14 queries).

- [x] **scljet-m5q-join-aggregate** — DONE 2026-07-14. Aggregates + DISTINCT over a join. `joinExecute`
      now collects the matched `JoinPair`s, then if the projection has an aggregate computes it over the
      pairs (`computeJoinAggregates`/`computeJoinAgg` + `aggregateValues` over a pre-extracted value
      list; COUNT(*), COUNT(col non-null), SUM/MIN/MAX/AVG/TOTAL, bare column = first pair), else
      projects each pair with DISTINCT dedupe + LIMIT. Verified vs sqlite3 (`COUNT(*)` over inner/LEFT
      join, `COUNT(qualified)` counting non-NULL of the outer side, multi-aggregate, `DISTINCT` over a
      join), int==js; folded into `scljet-sql-join` (12 queries). Follow-up: GROUP BY over a join.

Execution order (value × tractability): m4a (template exists) → m4b → m4c → m4d →
m4e → m4f → m4g. Keep every scljet conformance case green [int,js] --no-memo after each.

## SclJet interoperability follow-ups (2026-07-12)

- [~] **scljet-standalone-library** — DONE 2026-07-13 via a compatibility symlink;
      resolver-native decoupling remains. Spec: `specs/scljet-standalone-library.md`.
      SclJet source now lives at the repo-root **`scljet/`** (standalone, not under
      `v1/`); `v1/runtime/std/scljet` is a symlink to it, so `installBin`'s glob and
      every `std/`-import resolver (interpreter `ImportResolver`, native/JS loaders)
      find it unchanged. Verified: `scljet-*` 11/11 on `[int, js]`, native `ssc run`,
      and a non-scljet std case still green. REMAINING polish: drop the symlink by
      teaching the resolvers a first-class library root — build.sbt `installBin`
      (stage from `scljet/` directly), `ImportResolver`
      (`v1/lang/core/.../imports/ImportResolver.scala`), the native/JS +
      `check-stdlib-interface-load` loaders (`Main.scala`) — all mapping `std/scljet`
      → `scljet/`. Needs FULL conformance + native + JS verification.
      ⚠️ **Attempted 2026-07-21 (`65a9a7e8a`) and REVERTED (`638b4f610`).** The drop
      taught only the Scala `ImportResolver` + `installBin` staging, but MISSED the
      self-hosted native front's own ssc-land resolver: `v2/bin/ssc1-run.ssc0`
      `sscStdRoot` (and its `ssc1-run-fsub.ssc0` sibling) resolves a `std/…` import
      against `SSC_STD` (unset in the v21 gate) or the `v1/runtime/` fallback, reading
      REAL FILES FROM THE SOURCE TREE — never the staged `bin/lib`. With the symlink
      gone, all 13 `examples/scljet-*.ssc` front-errored (`NoSuchFileException
      v1/runtime/std/scljet/index.ssc`), dropping the v21 negative-toolchain gate's
      `frontend.ok` 208→198 (below floor 200; CI 29862386090). LESSON: the symlink
      cannot go until `ssc1-run.ssc0`'s `sscStdRoot` (both variants) gains a
      first-class scljet root (repo-root `scljet/`) AND the v21 gate is re-measured
      green (`tests/e2e/v21-negative-toolchain-release-gate.sh`, `frontend.ok≥208`).


- [ ] **scljet-m3-write-followups** — edge cases beyond the m3b–m3d write path
      (`scljet/write.ssc`). `SqlReal` record encoding is DONE
      (2026-07-13): `encodeReal` decomposes a Double into IEEE-754 binary64 by
      normalizing to `[1,2)` (exact powers-of-two arithmetic) — byte-exact vs
      `struct.pack('>d')` for 1.5/-2.5/3.14159/0/100/0.1/1e20/-0.001, reads back
      through a real DB, int/VM/ASM/fallback/JS (subnormals/non-finite out of
      scope). Cell-overflow (single-leaf) is DONE (2026-07-13):
      `buildOverflowTableDatabase` keeps each cell's local portion on the leaf
      (SQLite `localPayloadBytes` formula) and spills the remainder onto a chain
      of `[u32 next][content]` overflow pages — byte-exact vs reference SQLite,
      `integrity_check` ok, reads back exact, int==js (conformance
      `scljet-write-overflow`). Multi-leaf overflow is DONE too (2026-07-13):
      `buildOverflowBtreeDatabase` packs rows into leaves (and a table-interior
      root) like `buildTableDatabase` while spilling overflowing cells onto chains
      appended after the leaves — a two-pass build (probe to fix the leaf count,
      then number the overflow pages from `3+L`); byte-exact vs reference SQLite,
      byte-identical to `buildTableDatabase` for non-overflow input, int==js
      (conformance `scljet-write-btree-overflow`). Arbitrary-depth (3+ level)
      trees are DONE too (2026-07-13): `buildDeepTableDatabase` stacks interior
      levels bottom-up until a single-page root, numbering pages top-down so each
      node's children sit in a known contiguous range — verified on a real
      3-level tree (80 pages, `integrity_check` ok, depth 3, all rows exact),
      byte-identical to `buildTableDatabase` for a two-level tree, int==js
      (conformance `scljet-write-deep-btree`, fingerprinted since the file exceeds
      the byte-list size). This required making the page assembly iterative
      (`cellsFlatten`/`buildLeafPages`) — see the byteslice-zeros item below.
      Deep + overflow together is DONE too (2026-07-13):
      `buildDeepOverflowTableDatabase` builds a tree of any depth whose oversized
      rows also spill onto chains numbered after the whole tree (two-pass: fix the
      tree shape from placeholder cells, then number overflow from `2+T`) —
      verified on an 88-page 3-level tree with overflow chains (integrity_check ok,
      depth 3, all rows exact), int==js (conformance `scljet-write-deep-overflow`).
      The bulk-build write matrix (single/multi-table × small/overflow ×
      2-level/deep) is now complete. The explicit-rowid writer `buildKeyedDatabase`
      is DONE too (2026-07-13): rows carry their OWN (strictly ascending, gapped)
      rowids — preserved across a rewrite instead of renumbered 1..n — composing
      with overflow and any depth; verified rowids `[10,25,100,500,1000]` read back
      exact incl. a 1016-char overflow row, int==js (conformance
      `scljet-write-keyed`). This is the write-side foundation for m3f. Row DELETE
      is DONE (2026-07-13): `mutate.ssc` `deleteRowids`/`keepRowids` open the DB
      read-only over its own bytes (`ImageVfs`), read each surviving row as its raw
      record payload (`reconstructRecordBytes` from the reader's `DecodedRecord` —
      no value/text round-trip), and rebuild via `buildFromRawSchema` preserving
      the raw `sqlite_schema` record and original rowids. Verified vs reference
      `integrity_check` incl. an overflow row, int==js (conformance
      `scljet-mutate-delete`). Row UPDATE is DONE too (2026-07-13):
      `updateRowValues` re-encodes ONLY the changed row from a caller-supplied
      `List[SqliteValue]` (the new value is given, not decoded — so NO
      code-point→String needed; my earlier note here was wrong) and passes the
      rest through as raw records; verified updating a row to a 1016-char overflow
      value, int==js (conformance `scljet-mutate-update`). Row INSERT is DONE too
      (2026-07-14): `insertRow` adds a row at an explicit rowid kept in ascending
      order (`insertSorted`; errors on a duplicate rowid), existing rows pass
      through raw; verified middle-insert, append, duplicate rejection, and an
      inserted 1016-char overflow value, int==js (conformance
      `scljet-mutate-insert`). `mutate.ssc` now does the full row-level CRUD
      (insert/delete/keep/update) on an existing DB. Multi-table WRITE is DONE
      (2026-07-14): `buildMultiTableDatabase` lays out several rowid tables in one
      file — page 1 = `sqlite_schema` with a CREATE TABLE entry per table (each with
      its own root page), then each table's B-tree in declaration order (interiors
      built root-page-relative via `buildTableTreeAt`/`buildInteriorLevels`);
      verified 3 tables incl. a multi-leaf table at a non-page-2 root, all read back
      exact, int==js (conformance `scljet-write-multitable`). Multi-table MUTATE is
      DONE too (2026-07-14): `mutate.ssc` `deleteRowidsInTable`/`updateRowInTable`
      (+ `readAllTables`) read every table (raw schema record + raw rows), modify
      the one at a given index, and rebuild via write.ssc `buildMultiTableRaw` —
      which reassigns root pages and re-encodes each schema record's rootpage field
      (`patchSchemaRootpage`, keeping name/tbl_name/sql byte-for-byte, so no text is
      decoded to a String). Verified deleting/updating one table of three, others
      preserved, int==js (conformance `scljet-multitable-mutate`). Index WRITE is
      DONE (2026-07-14): `buildTableWithIndexDatabase` writes a rowid table plus a
      single-leaf index B-tree (page kind 10) of `(column, rowid)` records sorted by
      `(value, rowid)` — reference `integrity_check` cross-validates the index
      against the table AND the query planner uses it (`SEARCH t USING INDEX idx`),
      int==js (conformance `scljet-write-index`). Text-column index keys work too
      (2026-07-14): `compareKeys`/`valueClass` sort by SQLite storage class then
      numeric / BINARY-text order (String `<`, ASCII/BMP-exact), so an index on a
      TEXT column validates and the planner uses it (conformance
      `scljet-write-index-text`). Multi-column (composite) index keys work too
      (2026-07-14): `buildTableWithIndexDatabase` takes `keyColumns: List[Int]`,
      records are `[keycols…, rowid]` sorted lexicographically (`compareKeyList`);
      a two-column index validates and the planner uses it for `a=? AND b=?`
      (conformance `scljet-write-index-composite`; single-column via `List(col)` is
      byte-identical). Multi-leaf indexes work too (2026-07-14): when the entries
      exceed one leaf, `packIndexTree` packs them into leaves with a PROMOTED
      separator entry between each pair (the separator lives in the interior, not a
      leaf — unlike a table interior which copies a rowid), and `buildIndexTree`
      builds an index-interior root (page kind 2) over the leaves
      (`buildInteriorPageKind`/`indexInteriorCell`); verified a 100-row two-leaf
      index — reference integrity_check cross-validates it and the planner uses it
      (conformance `scljet-write-index-multileaf`); single-leaf stays byte-identical.
      Index maintenance on mutate is DONE (2026-07-14): `mutate.ssc`
      `deleteRowidsIndexed`/`updateRowIndexed` (via `rebuildIndexed` + write.ssc
      `buildTableWithIndexRaw`) read a table+index DB's rows, apply the edit, and
      rebuild BOTH the table and the index from the surviving rows so the index
      never goes stale — reference `integrity_check`'s index cross-check passes
      after delete AND update; the caller supplies the key columns
      (conformance `scljet-index-mutate`). TEXT-key index maintenance works too
      (2026-07-14): `fieldToValue` rebuilds a text key from its code points
      (`codepointsToString` via `Int.toChar` — which works now, contrary to the old
      note), so a TEXT index stays consistent on delete/update (conformance
      `scljet-index-mutate-text`). The m3e CORE — transactional in-place
      page write — is DONE (2026-07-14): `journal.ssc` `writePagesJournaled` journals
      the pre-images of the pages about to change, overwrites them in place, and
      returns the mutated database + rollback journal; `applyRollbackJournal` undoes
      it, so a crash before commit is recoverable (conformance
      `scljet-journal-write`, verified differs+restores int==js). 3+-level indexes are
      DONE too (2026-07-14): `buildIndexTree` stacks kind-2 interior levels bottom-up
      until a single-page root (`packIdxLevel`/`buildIdxLevels`) — a genuine 3-level
      index (interior level itself overflows) verified on 3000 rows, `integrity_check`
      cross-validates it, the planner uses `SEARCH … USING COVERING INDEX`, depth 3,
      int==js (conformance `scljet-write-index-deep`). The full mutable pager is DONE
      too (2026-07-14): `journal.ssc` `MutablePager` (dirty-page set + atomic journaled
      `mutableCommit`/`mutableRollback`), `write.ssc` cell-level leaf edits
      (`leafInsertCell`/`leafDeleteCell`/`leafUpdateCell`) and incremental `balance()`
      on insert/delete (`pagerInsertBalanced` splits leaves + grows the tree via
      `balanceDeeper`; `pagerDeleteBalanced` rewrites the leaf) — all wired into the
      SQL engine's DML (`sql.ssc` INSERT/DELETE/UPDATE=delete+reinsert); conformance
      `scljet-pager-mutate`, `scljet-cell-inplace`, `scljet-balance-insert`. Spec:
      `specs/scljet-mutable-pager.md`. Merge/rebalance on delete underflow is DONE too
      (2026-07-21): `write.ssc` `pagerDeleteRebalanced` reclaims an emptied non-root
      leaf onto the freelist (page-1 bytes 32..39 + trunk pages, `buildFreelistTrunks`/
      `patchFreelistHeader`) and collapses an interior dropping to one child
      (`balance_shallower` — root page kept, interior→leaf); file length + header page
      count unchanged, freelist grows. Only empty nodes are reclaimed (dividers dropped,
      never rewritten). Verified vs reference SQLite 3.53.3: `integrity_check` ok and
      `freelist_count` matches after a partial delete (10 pages freed) AND a root
      collapse (12 freed, root→leaf); the journal recovers the original image after a
      merge (crash-safe); int==js (conformance `scljet-balance-delete-merge`). The whole
      m3 write matrix + in-place mutable pager is now complete. (JIT codegen bug found on
      the way — BUGS.md `interp-jit-nested-match-duplicate-var`.)

- [ ] **scljet-reclaiming-dml** — wire the reclaiming delete into the live SQL engine.
      `sql.ssc` DELETE currently uses `pagerDeleteBalanced` (non-reclaiming); switching
      `deleteRowidLoop` to `pagerDeleteRebalanced` makes DELETE return pages to the
      freelist, but is only a net win when paired with **free-page reuse on INSERT**
      (`pagerInsertBalanced`/`mutableAllocate` currently always allocate at EOF, ignoring
      the freelist) — otherwise a delete-then-insert workload bloats. Do both together:
      teach allocation to pop a page off the freelist (updating header bytes 32..39 +
      trunk) before extending at EOF, then flip the DELETE path. Gate: no `scljet-sql-*`
      golden shifts except intended byte-state changes; `integrity_check` ok across a
      mixed insert/delete workload; int==js. Primitive + freelist writer already exist
      (`specs/scljet-mutable-pager.md`).

- [x] **scljet-byteslice-zeros-js-recursion** — DONE 2026-07-13. The core list
      helpers in `scljet/bytes.ssc` were made iterative (`while`+`var`, not linear
      recursion): `zerosList` (`ByteSlice.zeros`), `validateBytes`/`buildChunks`
      (`ByteSlice.fromList`), and `collectBytes` (`byteSliceToList`). The v1
      interpreter TCO'd these, but the JS backend does not, so they overflowed
      node's stack for large byte lists (`RangeError: Maximum call stack size
      exceeded`) — blocking full-size empty-DB writes and any 3+-level / large
      table on JS. Now a 40 KB three-level DB builds and round-trips identically on
      `[int, js]` (conformance `scljet-write-deep-btree`), and all 14 scljet cases
      stay green `--no-memo` on both backends. NB the interpreter-side var-scope
      leak (BUGS.md `interp-var-scope-leak-across-calls`) means the new iterative
      helpers use uniquely-prefixed var names.

- [x] **scljet-m2d-hardening-overflow-traversal** — ✓ Landed (2026-07-21). The last
      M2d corpus-hardening item. Three byte-mutations of `overflow-thresholds.db`'s
      `p = 1100` two-page overflow chain (page 11 → 12) are pinned corrupt fixtures —
      `next` → 0 (truncated), → 99 (out of range), → 11 (self-loop) — added to
      `generate.py corruptions()` (full regen byte-identical). `openReadonly` accepts
      all three; they fail only during traversal. New `tests/tools/scljet-corrupt-traverse.ssc`
      walks every user table and pins the diagnostics (`corrupt-traversal-errors.txt`),
      wired into `scljet-m2-corpus-smoke.sh` (default/asm/fallback tiers green).
      Cross-backend parity via conformance `scljet-overflow-traversal-corrupt`
      (`[int, js]`), which rebuilds the chain in memory and adds the length-short
      `overflow page is truncated` case (unreachable on disk). scljet-* 100/100 INT+JS.

- [ ] **scljet-portable-text-projection** — specify and implement a general
      target-neutral `code points/UTF-16 units -> String` construction API, then
      project SclJet `DecodedText` to `SqlText` without a host/JSON decoder.
      Current real-harness repro is in `BUGS.md`: v1 lacks `Int.toChar`, while
      v2 renders dynamic chars as decimal numbers. Keep raw encoded bytes as the
      SQLite GIGO source of truth and prove interpreter/VM/ASM/JS parity before
      the M4 value API depends on this projection.

- [x] **scljet-js-m1-parity** — DONE 2026-07-13. All 6 scljet conformance cases
      now pass `[JS]` and are declared `backends: [int, js]` (CI-locks the parity).
      Two findings: (1) the byte-codec/page-record/memory-VFS/cursor "diverges on
      JS" reports were all **stale-binary artifacts** — the fixes had landed in
      `70dfb5a1f` and later (always rebuild `installBin` before re-checking JS
      codegen). (2) `scljet-readonly-pager-btree` exposed a real JsGen bug —
      case-class body methods **with parameters** were dropped (only zero-param
      registered), so `_dispatch(vfs, 'fullPath', …)` threw
      `Method not found: fullPath on FixtureVfs`; fixed in JsGen (`BUGS.md`
      `js-caseclass-body-method-params-dropped`). Remaining scljet JS work is only
      the v2 self-hosted path's `__mk_method_obj__` import primitive (`BUGS.md`
      `v2-js-imported-method-object-primitive`) — tracked with the v2 work.

- [ ] **scljet-same-jvm-reference-lock-bridge** — before SclJet may replace the
      existing `sqlite:` provider, make SclJet locks conflict with an official
      native SQLite/Xerial connection running in the same JVM. POSIX record
      locks are process-owned, so `FileChannel` plus the SclJet-local canonical-
      path coordinator only covers SclJet↔SclJet in-process and reference SQLite
      across processes. Evaluate a small lock-broker process first; a native
      bridge into SQLite's per-process inode lock table is the alternative.
      Done when rollback and WAL contention tests mix both implementations in
      one JVM without unsafe simultaneous writers.

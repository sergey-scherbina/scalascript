# SclJet — typed SQL API and physical query planner

Status: **specification / design (pre-implementation)**
Module: `scljet/` (new files `typed.ssc`, `logical.ssc`, `physical.ssc`, `planner.ssc`)
Package: `scljet`
Provider id: `scljet`
Depends on: `scljet/sql.ssc` (reference semantics), `scljet/btree.ssc`, `scljet/index.ssc`
(read cursors, schema), `scljet/journal.ssc` (mutable pager), `scljet/write.ssc`
(B-tree writers, CREATE INDEX — **done**).

## Overview

This feature adds a **typed, embedded query API** to SclJet: queries are built from
ScalaScript values — typed table and column values, typed comparisons and projections —
not from SQL strings. A typed query lowers **directly to a physical execution plan over
the database B-trees**; there is no runtime SQL-string parse on the typed path. Two things
follow immediately: column references, comparisons, projections, and aggregates are checked
at **ScalaScript compile time** (an `Int` column compared to a `String` literal does not
compile), and query dispatch never pays for tokenizing/parsing SQL text at run time.

The design introduces two new intermediate representations between the query surface and
the executor:

1. a **logical plan** (`LogicalPlan`) — a relational-algebra tree independent of access
   paths, produced *both* by the typed builder *and* by the existing string front end; and
2. a **physical plan** (`PhysicalOp`) — an explicit tree of B-tree operations
   (`SeekRowid`, `RangeScan`, `FullScan`, `IndexSeek`, `IndexRangeScan`, `Filter`,
   `Project`, `Aggregate`, `Sort`, `NestedLoopJoin`, and the write ops `InsertCell` /
   `DeleteCell` / `UpdateCell` with `Balance`) that a planner lowers the logical plan to,
   and that maps one-to-one onto the pager/cursor/`pagerInsertBalanced` primitives already
   built in M1–M5.

The existing string SQL front end (`scljet/sql.ssc`) is **not replaced**. It becomes *one
optional parser* that produces the same `LogicalPlan`. The `SxNode`/`evalExpr` scalar
expression engine and the whole value semantics layer (`sqlCompare`, `condMatches`,
`predHolds`, three-valued NULL logic, LIKE, CASE, arithmetic) are **reused verbatim** as
the per-operator scalar evaluator. The current executor (`executeSelect`) is the **reference
semantics**: for every query the new pipeline MUST produce byte-/row-identical results.

Nothing in `SPEC.md` or the core language changes: this is a standard-library module inside
the existing `scljet` package.

## Relationship to the existing SQL layer

`scljet/sql.ssc` today does three jobs that this spec re-layers:

| Today (`sql.ssc`) | After this spec |
|---|---|
| `tokenize` + `parseSelect` → `SelectStmt` | unchanged; `SelectStmt` is adapted to `LogicalPlan` by `selectStmtToLogical` |
| `executeSelect(db, stmt)` runs a **fixed pipeline** (always full-scan via `collectRows`, then filter → group/agg/sort → project → distinct → limit) | the fixed pipeline becomes the *reference implementation* of the physical operators; a **planner** now chooses access paths (rowid/range/index seeks) instead of always full-scanning |
| `SxNode` + `evalExpr`/`evalExprJoin`/`evalExprGroup`/`evalExprValues` scalar engine | reused unchanged as the per-operator scalar evaluator |
| `executeInsert`/`executeUpdate`/`executeDelete`/`executeCreateIndex`/`executeMutation` over a `ByteSlice` image | become the reference implementation of the write operators `InsertCell`/`DeleteCell`/`UpdateCell`/`Balance` |

The crucial architectural fact about the current SELECT executor: **it never uses an
index**. `executeSelectSingle` always calls `openReadonlyRoot(db, root, RowidTableTree)` +
`collectRows` (a full table scan) and then `filterRows`. Indexes built by
`executeCreateIndex` are only validated by reference `integrity_check`; no read path seeks
them. The physical planner introduced here is what turns those indexes into **access
paths**. Correctness is preserved because every physical access path is required to return
exactly the rows a `FullScan + Filter` would; the seek is a semantics-preserving
optimization, verified separately for equivalence.

## Goals

- A typed query surface (`Table[R]`, `Column[T]`, `Expr[T]`, `Query[R]`) whose predicates,
  projections, joins, and aggregates are rejected by the ScalaScript type checker when
  ill-typed.
- One `LogicalPlan` IR produced by both the typed builder and the string parser.
- One `PhysicalOp` IR that names every B-tree operation explicitly and maps onto existing
  scljet primitives with no new storage machinery for the first correct plan.
- Index-aware access-path selection (rowid seek, rowid range scan, index equality seek,
  index range scan) with a full-scan fallback that is always available and always correct.
- Byte-/row-identical results to the current `executeSelect`/`executeMutation` on every
  supported query, verified on `[int, js]` and against reference sqlite3.
- A clean seam for a later cost-based planner (statistics, join ordering) without touching
  the operators or the storage engine.

## Non-goals (this spec)

- Replacing or deleting the string SQL front end. It stays as an optional parser.
- A cost model. The first planner is rule-based (heuristic access-path selection). The
  `PhysicalOp` IR is designed so a cost model can be dropped in later.
- New on-disk structures. The typed API writes exactly the same bytes the current writers
  produce; reference `integrity_check` acceptance is unchanged.
- Streaming execution as a hard requirement. The reference operators MAY materialize
  `List`s (as the current executor does); streaming over `readonlyNext` is an explicit
  later optimization (see Open decisions).
- SQL features the current executor does not support (windows, CTEs, correlated
  subqueries, multi-table aggregates/outer joins beyond the 2-table path). The typed API
  covers exactly the current SELECT/DML surface first; new features land in both surfaces
  together.

## Design principles (binding)

1. **Reference-equivalence first.** Every logical plan has a trivially-correct physical
   lowering (`FullScan` + `Filter` + the existing pipeline). Optimizations (seeks) are only
   admitted when a differential test proves identical rows. This is the scljet rule "the
   first correct plan is a table/index scan with explicit filtering and sort; optimization
   is incremental and semantics-preserving" (`specs/scljet.md`).
2. **Reuse the scalar engine.** No operator re-implements value semantics. Scalar
   evaluation is always `evalExpr`/`evalExprJoin`/`evalExprGroup` over `SxNode`; comparison
   is always `sqlCompare`/`condMatches`; predicates are always `predHolds`.
3. **Portable functional surface.** Following the byte/VFS/record layers, the typed API is
   authored as **top-level pure functions over immutable case classes** with no case-class
   method bodies and no imported receiver methods (both are not yet portable across the v1
   interpreter, native VM, direct ASM, and JS — see `specs/scljet.md`). Operator sugar
   (`===`, `<`, `+`) is offered as top-level functions; an extension-method facade is added
   only once receiver operations are portable on every backend.
4. **No host types, no string parse on the typed path.** The typed builder constructs
   `LogicalPlan` directly. `tokenize`/`parseSelect` run only when the caller starts from a
   SQL string.

## Layer 1 — Typed relations and columns

### Types

```scalascript
sealed trait SqlType
case object IntType  extends SqlType   // INTEGER (and rowid)
case object RealType extends SqlType   // REAL
case object TextType extends SqlType   // TEXT
case object BlobType extends SqlType   // BLOB
case object BoolType extends SqlType   // integer 0/1/NULL — the result type of a Predicate
```

A **column** is a typed reference; `T` is the ScalaScript type the column decodes to
(`Long` for `IntType`, `Double` for `RealType`, `String` for `TextType`, `ByteSlice` for
`BlobType`). `owner` is the table name, `name` the column name, `index` its 0-based
position in the `CREATE TABLE` column list (as produced by `tableColumns`).

```scalascript
case class Column[T](owner: String, name: String, index: Int, sqlType: SqlType)
case class ColumnMeta(name: String, sqlType: SqlType)
```

A **typed table** carries its name, its ordered column metadata, its verbatim
`CREATE TABLE` text (stored in `sqlite_schema` exactly as today), and a **decoder** from a
positional value row to the typed record `R`:

```scalascript
case class Table[R](
  name: String,
  columns: List[ColumnMeta],
  decodeRow: List[SqliteValue] => R,
  createSql: String
)
```

`Table[R]` and its `Column[T]`s are the single source of the compile-time schema. A
column's `index` and `sqlType` are fixed by the table definition, so `Column[Int]` and
`Column[String]` are distinct ScalaScript types and cannot be interchanged.

### Typed expressions

A **typed expression** wraps an untyped `SxNode` (the exact node type from `sql.ssc`) with
a phantom result type `T` and its `SqlType`. This is the erasure boundary: the typed layer
exists only to constrain construction; once built, an `Expr[T]` is an ordinary `SxNode`
that the existing evaluators consume unchanged.

```scalascript
case class Expr[T](node: SxNode, sqlType: SqlType)

def colExpr[T](c: Column[T]): Expr[T]              = Expr(SxCol(c.name), c.sqlType)
def intLit(x: Long): Expr[Long]                    = Expr(SxLit(SqlInteger(x)), IntType)
def realLit(x: Double): Expr[Double]               = Expr(SxLit(SqlReal(x)), RealType)
def textLit(s: String): Expr[String]               = Expr(SxLit(SqlText(s)), TextType)
```

Arithmetic and concatenation preserve `T` (both operands share the type parameter, so
`Int + Text` is a compile error):

```scalascript
def plus[T](a: Expr[T], b: Expr[T]): Expr[T]  = Expr(SxBin("+", a.node, b.node), a.sqlType)
def minus[T](a: Expr[T], b: Expr[T]): Expr[T] = Expr(SxBin("-", a.node, b.node), a.sqlType)
def times[T](a: Expr[T], b: Expr[T]): Expr[T] = Expr(SxBin("*", a.node, b.node), a.sqlType)
def concat(a: Expr[String], b: Expr[String]): Expr[String] =
  Expr(SxBin("||", a.node, b.node), TextType)
```

### Typed predicates

A **predicate** erases to the existing OR-of-ANDs `List[List[Condition]]` shape (exactly
what `parseWhere` produces), so `whereHolds`/`condHolds`/`predHolds` evaluate it unchanged.
Comparisons are generic in `T`: both sides must share the type, which is where ill-typed
predicates are rejected.

```scalascript
case class Predicate(where: List[List[Condition]])

def eqp[T](a: Column[T], b: Expr[T]): Predicate   // a = b
def nep[T](a: Column[T], b: Expr[T]): Predicate   // a <> b
def ltp[T](a: Column[T], b: Expr[T]): Predicate   // a < b
def lep[T](a: Column[T], b: Expr[T]): Predicate   // a <= b
def gtp[T](a: Column[T], b: Expr[T]): Predicate   // a > b
def gep[T](a: Column[T], b: Expr[T]): Predicate   // a >= b
def betweenp[T](a: Column[T], lo: Expr[T], hi: Expr[T]): Predicate
def inp[T](a: Column[T], vs: List[Expr[T]]): Predicate
def likep(a: Column[String], pat: String): Predicate
def isNullp[T](a: Column[T]): Predicate
def andp(x: Predicate, y: Predicate): Predicate    // merges into the same AND-group
def orp(x: Predicate, y: Predicate): Predicate     // adds an OR-group
```

Each builds a `Condition(column, op, value, values, leftExpr, rightExpr)` with the same op
strings the parser uses (`"="`, `"<>"`, `"<"`, `">"`, `"<="`, `">="`, `"between"`, `"in"`,
`"like"`, `"isnull"`, `"notnull"`), so no evaluator change is needed. A comparison whose
right side is a literal keeps `value` populated and `rightExpr = None` (the shape the
planner needs to recognize an index/rowid access path); a comparison against another column
or an arithmetic expression carries `rightExpr = Some(node)`.

> **Compile-time safety, concretely.** `eqp(userId, intLit(5))` type-checks
> (`userId: Column[Long]`, `intLit(5): Expr[Long]`). `eqp(userId, textLit("x"))` does **not**
> compile — `Expr[String]` is not `Expr[Long]`. This is the property the milestone calls
> "ill-typed predicates don't compile." No run-time check and no SQL parse is involved.

### Typed projection and the query value

A **projection** names the output columns/expressions and carries a decoder to the typed
output record `S`. It erases to the existing `(star, List[ProjItem])` pair.

```scalascript
case class Projection[S](star: Boolean, items: List[ProjItem], decode: List[SqliteValue] => S)
case class Query[R](plan: LogicalPlan, columns: List[ColumnMeta], decodeRow: List[SqliteValue] => R)
```

The builder combinators thread `Query[R]` and, at each step, emit a `LogicalPlan` node
(Layer 2) while keeping the ScalaScript type parameter that guarantees later stages see the
right record shape:

```scalascript
def from[R](t: Table[R]): Query[R]
def where[R](q: Query[R], p: Predicate): Query[R]
def select[R, S](q: Query[R], proj: Projection[S]): Query[S]
def joinOn[A, B](qa: Query[A], tb: Table[B], leftCol: Column[_], op: String, rightCol: Column[_]): Query[(A, B)]
def leftJoinOn[A, B](qa: Query[A], tb: Table[B], leftCol: Column[_], op: String, rightCol: Column[_]): Query[(A, B)]
def groupByCols[R](q: Query[R], keys: List[Column[_]]): Query[R]
def havingAgg[R](q: Query[R], h: HavingCond): Query[R]
def orderByKeys[R](q: Query[R], keys: List[OrderKey]): Query[R]
def limitBy[R](q: Query[R], n: Int, offset: Int): Query[R]
def distinctQ[R](q: Query[R]): Query[R]
```

Aggregate helpers build the same `ProjItem(isAgg=true, func, name, distinct, None)` items the
parser produces, so `mapGroups`/`projectGroupRow` evaluate them unchanged:

```scalascript
def countStar(): ProjItem
def sumCol[T](c: Column[T]): ProjItem
def minCol[T](c: Column[T]): ProjItem
def maxCol[T](c: Column[T]): ProjItem
def avgCol[T](c: Column[T]): ProjItem
```

## Layer 2 — Logical plan

`LogicalPlan` is relational algebra with **no access-path decisions**. It reuses the AST
value types already exported by `sql.ssc` (`Condition`, `ProjItem`, `JoinSpec`, `OrderKey`,
`HavingCond`, `Assignment`) so the string front end maps onto it with an adapter rather than
a re-encoding.

```scalascript
sealed trait LogicalPlan

// Base relation: a named table plus its resolved column names (from `tableColumns`).
case class LogScan(table: String, columns: List[String]) extends LogicalPlan

// Literal rows: SELECT-with-no-FROM and INSERT ... VALUES.
case class LogValues(rows: List[List[SqliteValue]]) extends LogicalPlan

case class LogFilter(input: LogicalPlan, where: List[List[Condition]]) extends LogicalPlan
case class LogProject(input: LogicalPlan, star: Boolean, items: List[ProjItem], distinct: Boolean) extends LogicalPlan
case class LogJoin(left: LogicalPlan, right: LogicalPlan, on: JoinSpec) extends LogicalPlan
case class LogAggregate(input: LogicalPlan, groupBy: List[String], items: List[ProjItem], having: Option[HavingCond]) extends LogicalPlan
case class LogOrder(input: LogicalPlan, keys: List[OrderKey]) extends LogicalPlan
case class LogLimit(input: LogicalPlan, limit: Int, offset: Int) extends LogicalPlan

// Write plans.
case class LogInsert(table: String, columns: List[String], source: LogicalPlan) extends LogicalPlan
case class LogUpdate(table: String, assignments: List[Assignment], where: List[List[Condition]]) extends LogicalPlan
case class LogDelete(table: String, where: List[List[Condition]]) extends LogicalPlan
case class LogCreateIndex(indexName: String, tableName: String, columns: List[String]) extends LogicalPlan
```

### The string front end as one optional parser

An adapter turns a parsed `SelectStmt` into the same `LogicalPlan` a typed `Query` builds.
The nesting order reproduces the current `executeSelectSingle` pipeline exactly (filter →
group/aggregate → order → project → limit), so the two producers are interchangeable:

```scalascript
def selectStmtToLogical(stmt: SelectStmt): LogicalPlan
def mutationToLogical(dbBytes: ByteSlice, sql: String): Either[String, LogicalPlan]
```

`selectStmtToLogical` builds, bottom-up:

1. `LogScan(stmt.table, colNames)` (or `LogValues` when `stmt.table == ""`), then a
   `LogJoin` chain from `stmt.joins`;
2. `LogFilter` when `stmt.where` is non-empty;
3. `LogAggregate` when `stmt.groupBy` is non-empty **or** `projHasAgg(stmt.projection)`
   (carrying `stmt.having`); otherwise a `LogOrder` when `stmt.orderBy` is non-empty;
4. `LogProject(_, stmt.star, stmt.projection, stmt.distinct)`;
5. `LogLimit` when `stmt.limit >= 0` or `stmt.offset > 0`.

Because the adapter and the typed builder both terminate in the identical `LogicalPlan`, a
single planner and a single executor serve both — the unification the milestone asks for.

## Layer 3 — Physical plan over B-trees

`PhysicalOp` names every B-tree operation explicitly. Two stream shapes flow through the
tree, matching how the current executor already works:

- **record rows** — `StorageRecord` (single table) or a parallel `List[StorageRecord]`
  (join), produced by the access-path leaves and consumed by `Filter`, `NestedLoopJoin`;
- **value rows** — `List[SqliteValue]`, produced by `Project`/`Aggregate` and consumed by
  `Sort`-of-projected, `Limit`, and the caller.

`Sort` appears **below** `Project` (the current executor sorts `StorageRecord`s via
`sortStorage` before projecting), so `Sort` operates on record rows. DISTINCT is a flag on
`Project` (current `dedupRows` on the projected value rows).

```scalascript
sealed trait PhysicalOp

// ── Access paths (leaves): each yields a stream of StorageRecord over one table ──
case class FullScan(root: Long, colNames: List[String]) extends PhysicalOp
case class SeekRowid(root: Long, colNames: List[String], rowid: Long) extends PhysicalOp
case class RangeScan(root: Long, colNames: List[String], lo: Option[Long], hi: Option[Long], loIncl: Boolean, hiIncl: Boolean) extends PhysicalOp
case class IndexSeek(indexRoot: Long, tableRoot: Long, colNames: List[String], keyColumns: List[Int], key: List[SqliteValue]) extends PhysicalOp
case class IndexRangeScan(indexRoot: Long, tableRoot: Long, colNames: List[String], keyColumns: List[Int], lo: Option[List[SqliteValue]], hi: Option[List[SqliteValue]]) extends PhysicalOp

// ── Pipeline operators (single child) ──
case class Filter(input: PhysicalOp, where: List[List[Condition]], colNames: List[String]) extends PhysicalOp
case class Sort(input: PhysicalOp, keys: List[OrderKey], colNames: List[String]) extends PhysicalOp
case class Aggregate(input: PhysicalOp, groupBy: List[String], items: List[ProjItem], having: Option[HavingCond], colNames: List[String]) extends PhysicalOp
case class Project(input: PhysicalOp, star: Boolean, items: List[ProjItem], colNames: List[String], distinct: Boolean) extends PhysicalOp
case class Limit(input: PhysicalOp, limit: Int, offset: Int) extends PhysicalOp

// ── Join ──
case class NestedLoopJoin(outer: PhysicalOp, inner: PhysicalOp, on: JoinSpec) extends PhysicalOp

// ── Write operators (thread a MutablePager; see the write execution model) ──
sealed trait PhysicalWrite
case class InsertCell(root: Int, rowid: Long, record: ByteSlice, balance: Boolean) extends PhysicalWrite
case class DeleteCell(root: Int, rowid: Long, balance: Boolean) extends PhysicalWrite
case class UpdateCell(root: Int, rowid: Long, record: ByteSlice, balance: Boolean) extends PhysicalWrite
case class RebuildIndexes(table: String) extends PhysicalWrite   // whole-file reindex via buildSingleTableIndexed
```

`Balance` is not a separate operator in the current storage engine: the balanced pager
primitives `pagerInsertBalanced`/`pagerDeleteBalanced` **fuse** the cell edit and the
B-tree rebalance. The `balance` flag on the write ops therefore selects between the fused
balanced primitive (`balance = true`) and the non-balancing single-leaf fast path
(`balance = false`: `leafInsertCell`/`leafDeleteCell`/`leafUpdateCell` + `rebuildLeafPage`
+ `mutablePut`, valid only when the target leaf cannot overflow). The planner sets
`balance = true` by default; a later slice may prove a fast-path is safe and flip it.

### The planner

```scalascript
def planLogical(db: ReadonlyDatabase, plan: LogicalPlan): Either[SqliteError, PhysicalOp]
def planWrite(db: ReadonlyDatabase, plan: LogicalPlan): Either[SqliteError, List[PhysicalWrite]]
```

`planLogical` walks the logical tree and, at each `LogScan` under a `LogFilter`, chooses an
**access path**. The rule-based selection (v1, no cost model):

1. **`SeekRowid`** — the filter contains a top-level AND conjunct `rowid = k` (a `Condition`
   with `column == "rowid"` case-insensitively, `op == "="`, literal `value`).
2. **`RangeScan`** — a top-level conjunct bounds `rowid` (`<`, `<=`, `>`, `>=`, or
   `between`) and no equality is present. `lo`/`hi` come from the literal bounds.
3. **`IndexSeek`** — `tableIndexInfos(db, table, colNames)` reports an index whose leading
   key column has an equality conjunct `col = v`. `keyColumns` is the index's `keyColumns`;
   `key` is the leading literal(s).
4. **`IndexRangeScan`** — an index whose leading key column has a range conjunct.
5. **`FullScan`** — otherwise.

In cases 1–4 the predicates consumed by the access path are **removed** from the residual
`Filter` (the leftover conjuncts still run as a `Filter` above the leaf); in case 5 the
whole `where` becomes a `Filter`. **Correctness invariant:** for any `LogFilter(LogScan)`,
`plan-then-execute` MUST yield the same rows as `FullScan` + `Filter(where)`. The planner is
only allowed to pick a seek when this holds; the differential suite (below) proves it per
access path.

Joins lower to `NestedLoopJoin(outer, inner, on)` with the current 2-table
(`joinExecute`) or N-table (`multiJoinExecute`) semantics; the inner side MAY itself be an
`IndexSeek`/`SeekRowid` when the join column is the index/rowid key (an inner-index nested
loop), again only when row-equivalent to the current nested-loop scan.

## Operator → primitive mapping (normative)

Each physical operator is realized by the existing scljet functions below. This table is the
implementation contract: the operator introduces **no new storage code** for the first
correct plan; it orchestrates functions that already exist and are already tested.

| Operator | scljet primitives it calls | Notes |
|---|---|---|
| `FullScan` | `openReadonlyRoot(db, root, RowidTableTree)` → `collectRows(db, cursor)` (materializing) or `readonlyFirst`/`readonlyNext` (streaming) | exactly the current `executeSelectSingle` scan |
| `SeekRowid` | read-side `cursorSeek` (**new**, below) → single `StorageRecord`; **fallback** `FullScan` + rowid `Filter` | semantics-preserving |
| `RangeScan` | read-side `cursorSeekGe` + forward `readonlyNext` until `hi`; **fallback** `FullScan` + range `Filter` | rowid order is the table's physical order |
| `IndexSeek` | `openReadonlyRoot(db, indexRoot, RecordKeyTree)` → walk index records via `readonlyFirst`/`readonlyNext`, match `key` by `sqlCompare` over `keyColumns`, extract trailing rowid, then `SeekRowid` into `tableRoot` | index record is `keycols… ++ rowid` (as `buildIndexEntries` lays out) |
| `IndexRangeScan` | as `IndexSeek` but accept keys in `[lo,hi]` by `sqlCompare`; then `SeekRowid` per hit | |
| `Filter` | `filterRows(rows, where, colNames)` → `whereHolds`→`condHolds`→`predHolds`→`evalExpr`/`sqlCompare`/`condMatches`/`likeMatch` | scalar engine unchanged |
| `Sort` | `sortStorage(rows, colNames, keys)` (record rows) — reuses `evalExpr` + `sqlCompare` | below `Project` |
| `Aggregate` | grouped: `sortStorage` on group keys → `groupPartition` → `filterGroups`(HAVING) → `mapGroups`; ungrouped: `projectGroupRow` — all via `evalExprGroup` | matches current group path |
| `Project` | per row `projectRow(rec, colNames, stmt-shape)` via `evalExpr`; `dedupRows` when `distinct` | value rows out |
| `Limit` | `limitValueRows(rows, limit, offset)` | |
| `NestedLoopJoin` | `joinExecute(db, stmt, spec)` (2 tables) / `multiJoinExecute(db, stmt)` (N) → `joinColValue`, `evalExprJoin`, `sqlCompare`, `condMatches` | outer joins & 2-table aggregates via the existing 2-table path |
| `InsertCell(balance=true)` | `pagerInsertBalanced(pager, root, rowid, encodeRecord(values), pageSize)` | rowid = `maxRowidOf(rows)+1` (as `executeInsert`) |
| `InsertCell(balance=false)` | `leafInsertCell` + `rebuildLeafPage` + `mutablePut` | only when the leaf cannot overflow |
| `DeleteCell(balance=true)` | `pagerDeleteBalanced(pager, root, rowid, pageSize)` | victims from `matchingRowids(rows, where, colNames)` |
| `UpdateCell(balance=true)` | `pagerDeleteBalanced` then `pagerInsertBalanced` at the same rowid (current `applyUpdates`) | new record via `encodeRecord(applyAssignments(...))` |
| `RebuildIndexes` | `reindexTable` → `buildSingleTableIndexed(pageSize, cc, sc, tableSchema, indexSchemas, keyed, indexEntriesPerIndex)` | whole-file rebuild when the table has indexes (current behavior); incremental index `InsertCell` into index trees is a follow-up |
| commit | `mutableCommit(pager, nonce)` → `PagerCommit.pager.image` (new `ByteSlice`) | via `openMutablePager(dbBytes, pageSize, pageSize)` |

### Read execution model

```scalascript
def execPhysical(db: ReadonlyDatabase, op: PhysicalOp): Either[String, List[List[SqliteValue]]]
def runQuery[R](db: ReadonlyDatabase, q: Query[R]): Either[String, List[R]]
```

`execPhysical` evaluates the operator tree to value rows. `runQuery` = `planLogical` →
`execPhysical` → map `q.decodeRow` over the rows to produce typed `R` records. The reference
implementation materializes `List`s exactly like the current executor; the access-path
leaves are the only new code, and each has a `FullScan`-equivalent fallback so the pipeline
is correct before any seek primitive exists.

The **new read-side seek** primitive (the only genuinely new storage function this feature
needs, and only for the *optimized* path — the fallback needs none):

```scalascript
// Position a forward table cursor at the leaf holding `rowid` (or the first rowid >= key).
// Reuses the write layer's descent (`descendToLeaf` / `chooseChild` / `readInteriorNode`)
// to jump to the leaf instead of scanning from the first leaf.
def cursorSeek(db: ReadonlyDatabase, root: Long, rowid: Long): Either[SqliteError, ReadonlyCursorStep]
def cursorSeekGe(db: ReadonlyDatabase, root: Long, rowid: Long): Either[SqliteError, ReadonlyCursorStep]
```

This mirrors the intended B-tree cursor API in `specs/scljet.md`
(`seek / seekGe / seekLe / first / last / next / previous`); until it lands the planner
emits `FullScan` for these access paths and the results are identical, so the differential
suite is green from stage T1.

### Write execution model

A write plan is a `List[PhysicalWrite]` applied to one `MutablePager` threaded left to
right, then committed. This is precisely the shape of the current `insertRowsLoop` /
`deleteRowidLoop` / `applyUpdates`, generalized to a list of typed ops:

```scalascript
def execWrite(dbBytes: ByteSlice, ops: List[PhysicalWrite]): Either[String, ByteSlice]
```

`execWrite` opens `openMutablePager(dbBytes, pageSize, pageSize)`, folds each op via its
mapped primitive (updating the pager), then `mutableCommit(pager, nonce)` and returns
`PagerCommit.pager.image`. When the target table has indexes (`tableIndexInfos` non-empty),
the whole plan reduces to a single `RebuildIndexes` (current `reindexTable` semantics) until
incremental index maintenance lands — the same limitation the current
`executeInsert`/`executeDelete`/`executeUpdate` already carry (they reject multi-table
index maintenance with `"index maintenance on a multi-table database is not yet
supported"`).

Typed write entry points:

```scalascript
def insertRow[R](dbBytes: ByteSlice, t: Table[R], row: R): Either[String, ByteSlice]
def deleteWhere[R](dbBytes: ByteSlice, t: Table[R], p: Predicate): Either[String, ByteSlice]
def updateWhere[R](dbBytes: ByteSlice, t: Table[R], sets: List[Assignment], p: Predicate): Either[String, ByteSlice]
def createIndex(dbBytes: ByteSlice, indexName: String, t: Table[_], columns: List[Column[_]]): Either[String, ByteSlice]
```

Each builds a `LogInsert`/`LogDelete`/`LogUpdate`/`LogCreateIndex`, calls `planWrite`, then
`execWrite`. The produced `ByteSlice` is byte-identical to what the corresponding
`executeInsert`/`executeDelete`/`executeUpdate`/`executeCreateIndex` produces from the
equivalent SQL string — that byte-identity is a test obligation (below).

## Why — benefits

- **Compile-time safety.** The ScalaScript type checker rejects an `Int`-vs-`String`
  comparison, a projection of a non-existent column, or an aggregate over a text column
  where a number is required — before the program runs. The string path can only fail at
  run time.
- **Zero string-parse at query time.** The typed builder constructs `LogicalPlan` directly;
  `tokenize`/`parseSelect` never run on the typed path. Repeated queries pay no lexing cost.
- **Index-aware access paths.** Today every SELECT full-scans (`executeSelectSingle` always
  `collectRows`). The physical planner turns the indexes that `executeCreateIndex` already
  builds into `SeekRowid`/`RangeScan`/`IndexSeek`/`IndexRangeScan` — the first real use of
  those indexes on the read path.
- **A clean seam for a cost-based planner.** Access-path choice is isolated in
  `planLogical`. A statistics-driven cost model, join reordering, or covering-index
  detection can be added there without touching the operators or the storage engine.
- **One executor, three front doors.** The string front end, the typed builder, and (per
  the sibling `scljet-jdbc-api` milestone) a JDBC façade all terminate in the same
  `LogicalPlan` → planner → operators, so semantics are defined once and shared.
- **Reuse of the proven scalar engine.** `SxNode`/`evalExpr` and the value semantics
  (three-valued NULL, affinity-free `sqlCompare`, LIKE, CASE, 64-bit-safe arithmetic) are
  battle-tested against sqlite3 already; operators inherit that correctness for free.

## Staged implementation plan

Each stage is independently shippable, pushed to `origin/main` per the workflow, and gated
by the differential suite on `[int, js]`. Write every slice into `SPRINT.md` as `[ ]` first.

- **T0 — IR + adapter (no behavior change).** Add `logical.ssc` (`LogicalPlan`) and
  `selectStmtToLogical`. Add `physical.ssc` (`PhysicalOp`, `PhysicalWrite`). Add
  `execPhysical` as a thin re-expression of the current `executeSelect` pipeline over the
  physical operators, with **only** `FullScan` leaves. Route `queryImage` through
  `selectStmtToLogical` → `planLogical` (full-scan only) → `execPhysical`. Gate: the entire
  existing scljet SQL conformance corpus is unchanged (byte-identical `renderRows`).
- **T1 — planner skeleton + typed read surface.** Add `planLogical` with `FullScan` +
  residual `Filter` only (still no seeks). Add `typed.ssc` (`SqlType`, `Column`, `Expr`,
  `Table`, `Query`, predicate/projection builders) and `runQuery`. Gate: for a set of typed
  queries, `runQuery(db, q)` decodes to the same rows `queryImage(db, equivalentSql)`
  returns; both match sqlite3.
- **T2 — rowid access paths.** Add `cursorSeek`/`cursorSeekGe` (read-side descent reusing
  `descendToLeaf`/`chooseChild`). Planner emits `SeekRowid`/`RangeScan` for `rowid = k` /
  rowid ranges. Gate: results identical to T1 full-scan and to sqlite3; add an equivalence
  test `SeekRowid ≡ FullScan+Filter` over random rowids.
- **T3 — index access paths.** Planner consults `tableIndexInfos` and emits
  `IndexSeek`/`IndexRangeScan` for equality/range on a leading index column; residual
  predicates stay as `Filter`. Gate: identical rows to full-scan and sqlite3 on
  indexed/non-indexed columns; equivalence test across a table with and without the index.
- **T4 — typed writes.** `insertRow`/`deleteWhere`/`updateWhere`/`createIndex` →
  `planWrite` → `execWrite`. Gate: output `ByteSlice` byte-identical to the equivalent
  `executeInsert`/`executeDelete`/`executeUpdate`/`executeCreateIndex`; reference sqlite3
  `integrity_check = ok` and read-back equal.
- **T5 — joins & aggregates on the typed surface.** `joinOn`/`leftJoinOn`,
  `groupByCols`/`havingAgg`, aggregate projection builders; planner lowers to
  `NestedLoopJoin`/`Aggregate` (reusing `joinExecute`/`multiJoinExecute`/`mapGroups`). Gate:
  typed joins/aggregates row-identical to the SQL path and sqlite3.
- **T6 — inner-index nested loop.** When a join column is an index/rowid key, the inner side
  becomes `IndexSeek`/`SeekRowid`. Gate: equivalence to T5 nested-loop scan; results
  unchanged.
- **T7 (optional) — cost hooks.** Introduce a minimal statistics stub (row counts,
  index selectivity) behind `planLogical` and an `explainPhysical(op): String` for tests.
  No result change; only access-path *choice* may change, and every choice is
  equivalence-tested.

## Differential test plan

Conformance cases live under `tests/conformance/` with expected output under
`tests/conformance/expected/`, run by `tests/conformance/run.sh --only 'scljet-typed-*'`,
declared `backends: [int, js]` like the rest of scljet. Three oracles, checked at every
stage:

1. **Vs the SQL-string path (same results).** For each typed `Query`, assert
   `runQuery(db, q)` decoded rows equal `queryImage(db, equivalentSql)` rows, and each typed
   write's `ByteSlice` equals the equivalent `executeMutation(dbBytes, sql)` `ByteSlice`
   **byte-for-byte**. This is the primary regression gate — the two front doors must never
   diverge.
2. **Vs reference sqlite3.** Render results with the existing `renderRows` (sqlite3 CLI
   list format) and diff against sqlite3 3.53.3 on the same pinned fixtures the rest of
   scljet uses. Writes: open the produced image with reference sqlite3, run
   `PRAGMA integrity_check` (must be `ok`) and read the rows back.
3. **Access-path equivalence (internal).** For each optimized leaf (`SeekRowid`,
   `RangeScan`, `IndexSeek`, `IndexRangeScan`), assert the operator returns exactly the rows
   `FullScan` + the corresponding `Filter` returns, over randomized data — the property that
   licenses the planner to choose the seek. This runs pure (no host VFS) on int/VM/ASM and
   JS.

Coverage mirrors the current SQL conformance surface so parity is provable: point lookups
(`rowid = k`, `pk = k`), ranges (`BETWEEN`, `<`/`>`), equality/range on indexed and
non-indexed columns, projections and computed expressions, `DISTINCT`, `ORDER BY`/`LIMIT`/
`OFFSET`, `GROUP BY`/`HAVING`/aggregates, inner and left joins, and INSERT/UPDATE/DELETE/
CREATE INDEX round-trips. Each new case ships with a runnable example under `examples/`
(`examples/scljet-typed-sql.ssc`) per the project's example rule.

Every stage also asserts **cross-backend identity**: the pure planner/operator code (all
logic except the host-VFS file reads) reproduces byte-identical results on the v1
interpreter, native VM, direct ASM, and JS/Node, matching how the M2 corpus is gated. Any
backend gap is an explicit skip with a recorded reason, never a silent substitution.

## Dependencies

- **CREATE INDEX — done.** `executeCreateIndex`/`buildIndexTree`/`sortIndexEntries`/
  `buildSingleTableIndexed` exist and produce `integrity_check`-clean index B-trees. The
  index access paths (`IndexSeek`/`IndexRangeScan`) read those trees; `tableIndexInfos`
  enumerates them for the planner.
- **The current SQL executor — reference semantics.** `executeSelect` and the
  `SxNode`/`evalExpr` engine define the exact rows/values every physical plan must
  reproduce. The scalar engine is reused unchanged; the pipeline helpers (`filterRows`,
  `sortStorage`, `groupPartition`, `mapGroups`, `projectRow`, `limitValueRows`,
  `dedupRows`) are the operator bodies.
- **Mutable pager & B-tree writers — done.** `openMutablePager`/`mutableGet`/`mutablePut`/
  `mutableAllocate`/`mutableCommit` (journaled commit) and `pagerInsertBalanced`/
  `pagerDeleteBalanced` (incremental balanced writes) back the write operators.
- **Read cursors & schema — done.** `openReadonlyRoot`/`readonlyFirst`/`readonlyNext` over
  `RowidTableTree`/`RecordKeyTree`, `collectRows`, `findTable`, `SchemaEntry` back the read
  operators. The **only** genuinely new storage primitive is the read-side seek
  (`cursorSeek`/`cursorSeekGe`), and it is needed only for the *optimized* access paths —
  the correct full-scan fallback needs nothing new.

## Open decisions

1. **Typed surface ergonomics.** Ship the functional surface (`eqp`/`ltp`/`plus`) first
   (portable on every backend today). Promote to extension-method operators (`===`, `<`,
   `+`) only once receiver operations are portable across v1/VM/ASM/JS — the same constraint
   that keeps the byte/record layers functional. Decision recorded, revisit when the
   frontend supports portable receiver methods.
2. **Typed record `R`.** Case classes vs tuples vs positional `List[SqliteValue]` for the
   decoded row. Tuples are portable now; named case-class records are nicer but depend on
   case-class body/method portability. Proposed: start with tuples + an explicit
   `decodeRow`, add a case-class facade later.
3. **Streaming vs materialization.** The reference operators materialize `List`s (matching
   the current executor). `readonlyNext` already supports streaming; a pull-based operator
   model is a later performance slice, gated on identical results.
4. **Incremental index maintenance.** Writes to an indexed table currently rebuild the whole
   file (`reindexTable`/`buildSingleTableIndexed`). Incremental index-tree `InsertCell`/
   `DeleteCell` (so a single-row insert touches only the affected index leaves) is a
   follow-up; until then `RebuildIndexes` preserves current semantics.
5. **`rowid` as a first-class typed column.** Whether `Table[R]` exposes an implicit
   `Column[Long]("rowid")` so `SeekRowid` is reachable through the typed API (not only the
   string path). Proposed: yes — a synthetic rowid column, since the planner already special-
   cases the name `"rowid"` (`rowValue`).
6. **WITHOUT ROWID tables.** The first planner targets rowid tables (the current write/read
   focus). WITHOUT ROWID key seeks reuse `RecordKeyTree` and land with the broader schema
   semantics.

## Decisions (chosen / rejected)

- **Two IRs (logical + physical), not one.** Chosen so access-path choice is a single
  isolated pass and both front ends share it. Rejected: lower the typed builder straight to
  physical ops (would duplicate access-path logic across the typed and string paths and
  block a future cost model).
- **String front end demoted to an optional parser, not deleted.** Chosen — it already
  passes a large sqlite3 differential corpus and is the reference semantics. Rejected:
  replace it (throws away proven behavior and a whole test surface).
- **Reuse `SxNode`/`evalExpr` and the pipeline helpers as operator bodies.** Chosen — value
  semantics are defined once and already gated against sqlite3. Rejected: a fresh operator
  evaluator (re-litigates NULL/affinity/LIKE/arithmetic correctness the current engine
  already settled).
- **Correct full-scan fallback for every access path.** Chosen so the pipeline is correct
  and green before any seek primitive exists; seeks are admitted only after an equivalence
  test. Rejected: implement seeks as a precondition (couples correctness to a new,
  unproven storage primitive).
- **Balance fused in the balanced pager primitives, exposed as a `balance` flag.** Chosen —
  matches the current `pagerInsertBalanced`/`pagerDeleteBalanced` reality and still names
  `Balance` in the plan. Rejected: a standalone `Balance` operator (there is no
  cell-edit-without-balance primitive in the storage engine except the constrained
  single-leaf fast path, which the flag already selects).
- **Functional, method-body-free typed surface.** Chosen for cross-backend portability,
  consistent with every other pure scljet layer. Rejected: an OO builder with receiver
  methods (not yet portable to VM/ASM/JS).

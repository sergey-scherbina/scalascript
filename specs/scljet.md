# SclJet — pure ScalaScript SQLite-compatible engine

Status: **design / M0 scaffold**  
Module: `runtime/std/scljet/`  
Package: `scljet`  
Provider id: `scljet`  
Initial opt-in URL scheme: `scljet:`

## Overview

This feature is an independent implementation of the SQLite 3 database format,
transaction protocols, and SQL behavior in pure ScalaScript. It is not a wrapper
around JDBC, `sqlite-jdbc`, `sql.js`, SQLite's C API, or the current
`backend-sql-runtime`. The byte codec, pager, B-trees, freelist and overflow
handling, rollback journal, WAL, schema loader, SQL parser, planner, evaluator,
and function registry are ScalaScript modules. Only a narrow virtual-filesystem
capability crosses into platform code.

The engine has two equally important goals:

1. In its strict profile, read and write ordinary SQLite 3 files and coordinate
   safely with official SQLite connections using the same rollback/WAL locking
   protocol.
2. Expose target-independent extension seams for virtual filesystems,
   application-defined SQL functions, collations, and later virtual tables,
   without contaminating the compatible file format or depending on host types.

The project is intentionally staged. M0 defines the module and contracts; it
does not claim that the database engine is already implemented.

## Relationship to existing ScalaScript SQL support

ScalaScript already has `sql` fenced blocks, `Db.query`/`Db.execute`, a JVM JDBC
runtime, and JS providers based on `sql.js` and DuckDB-Wasm. Those are provider
adapters. This module is a new engine.

- Existing `sqlite:` and `jdbc:sqlite:` behavior remains unchanged while this
  implementation is incomplete.
- During development, `scljet:` selects this engine explicitly.
- A future `databases:` entry may select `engine: scljet` while
  retaining a canonical `sqlite:` URL. That cutover requires differential and
  crash-safety gates; it is not part of M0.
- SQL fenced-block bind rewriting remains owned by the existing frontend. The
  engine receives SQL plus ordered values and also supports SQLite's native
  parameter forms for direct prepared statements.
- The engine core never imports the current JDBC or JS SQL runtime. Reference
  SQLite may appear only in oracle/interoperability tests.

No change to `SPEC.md` is required for M0: this is a standard-library module and
provider, not a new language construct or a change to `sql` fence semantics.

## Normative compatibility sources

The implementation follows the official SQLite documentation rather than
reverse-engineering a particular host library:

- [Database File Format](https://www.sqlite.org/fileformat.html)
- [WAL-mode File Format and wal-index](https://www.sqlite.org/walformat.html)
- [File Locking and Concurrency](https://www.sqlite.org/lockingv3.html)
- [Atomic Commit](https://www.sqlite.org/atomiccommit.html)
- [The SQLite VFS](https://www.sqlite.org/vfs.html)
- [SQL understood by SQLite](https://www.sqlite.org/lang.html)
- [Application-defined SQL functions](https://www.sqlite.org/appfunc.html)

The on-disk target is SQLite format 3 with schema formats 1 through 4. The
differential behavior baseline begins with SQLite **3.53.0** (the current
release when this spec was written on 2026-07-12). A test manifest records the
exact oracle version; upgrading the oracle is an explicit compatibility task,
not an incidental CI dependency update.

Where the official documents leave behavior to a VFS, the VFS contract below
is normative. Where documentation and the reference implementation disagree,
the discrepancy is recorded as a compatibility issue before code changes.

## Goals

- Pure ScalaScript implementation above a target-neutral VFS.
- Byte-for-byte interoperability for main database, rollback-journal, WAL, and
  standard unix/windows wal-index layouts in the strict profile.
- Safe multi-connection and multi-process locking when the selected VFS offers
  compatible locks and shared memory.
- Correct recovery after application crash, OS crash, short I/O, and injected
  failure at every durable-write boundary.
- SQLite manifest typing, affinity, collation, NULL, rowid, constraint, and
  transaction semantics rather than a superficially similar SQL dialect.
- Prepared statements and streaming cursors without loading full result sets.
- Connection-local scalar, aggregate, window-function, and collation registry.
- Replaceable filesystems: host files, memory, browser worker/OPFS, encrypted or
  compressed page stores, test/fault-injection stores, and remote block stores
  that can honestly satisfy the durability and locking contract.
- Deterministic behavior across ScalaScript VM, direct ASM, JS/Node, and future
  backends for the same VFS capabilities.

## Out of scope for the initial implementation

- Copying or translating SQLite C source. This is a clean implementation from
  public format and behavior specifications.
- Drop-in C ABI compatibility with `sqlite3_*`.
- Binary compatibility with SQLite VDBE opcodes or extension `.so`/`.dll`
  modules.
- Networked multi-writer consensus. A remote VFS must itself provide the lock,
  ordering, and durability semantics it advertises.
- SQLCipher-compatible encryption in the strict profile.
- Virtual tables, FTS, R-Tree, sessions, and replication before the core pager,
  SQL, rollback, and WAL milestones are complete.
- Transparent use of platform types such as `java.nio.*`, `java.sql.*`,
  `scala.*`, Node `fs`, or browser globals in `.ssc` engine code.
- Claiming full SQLite SQL compatibility before the differential matrix proves
  each syntax/semantic family.

## Compatibility profiles

### `StrictSqlite`

The default compatibility profile.

- Writes only documented SQLite 3 structures.
- Preserves unknown legal header fields, reserved page bytes, and schema objects
  it does not need to modify.
- Refuses a write that would require understanding an unsupported structure.
- Never stores ScalaScript-only metadata in undocumented bytes.
- May extend behavior through connection-local functions, collations, VFSes,
  and virtual tables because those do not inherently change the base format.
- A file written in this profile must pass the reference engine's
  `PRAGMA integrity_check` and remain writable by it.

### `Extended`

An explicit, non-default profile for negotiated format extensions.

- Uses a registered `application_id`, an extension descriptor table, and only
  declared reserved bytes or ordinary shadow tables.
- Every extension has an id, version, required/optional flag, and migration
  rule.
- Opening with official SQLite may expose only compatible shadow tables or may
  be rejected, depending on the extension. The result is never advertised as a
  plain SQLite-compatible file.
- `Extended` cannot be selected accidentally from a `sqlite:` URL; it requires
  an explicit option or distinct scheme.

### `ReadOnlySalvage`

For inspection and recovery tools.

- Permits best-effort traversal after a precisely reported corruption.
- Never mutates the source file.
- Every recovered row carries provenance and warnings; corrupt bytes are never
  silently converted to valid values.

## Architecture and module layout

The intended pure `.ssc` layout is:

```text
runtime/std/scljet/
  index.ssc          public aggregator and engine entry contract
  values.ssc         storage classes, rows, results, limits, errors
  bytes.ssc          bounds-checked byte slices, endian and varint codecs
  vfs.ssc            VFS/file/locking/shared-memory capability contracts
  record.ssc         SQLite record serial types and comparison
  page.ssc           database header and page/cell codecs
  pager.ssc          cache, snapshots, dirty pages and transaction state
  journal.ssc        rollback journal and hot-journal recovery
  wal.ssc            WAL frames, checksums, snapshots and checkpointing
  wal-index.ssc      standard 32-KiB wal-index regions and hash lookup
  btree.ssc          table/index B-tree cursors and balancing
  freelist.ssc       freelist, overflow and pointer-map management
  schema.ssc         sqlite_schema loading and normalized schema model
  sql-lexer.ssc      tokens and source positions
  sql-parser.ssc     SQLite grammar to target-neutral AST
  planner.ssc        scans, index selection, joins and temp structures
  vm.ssc             statement execution program and register values
  functions.ssc      built-ins and application-defined registries
  connection.ssc     connection, prepared statement and cursor lifecycle
  integrity.ssc      structural validation and integrity_check
```

Files are introduced by milestone; M0 creates only the public contracts. Host
adapters are separate plugins, for example
`v2/runtime/std/scljet-vfs-plugin/`. Any new `extern def` belongs in such a
plugin under the project intrinsic rules, never in the engine core.

Dependencies point inward only:

```text
SQL API -> parser/planner/vm -> schema/B-tree -> pager -> journal or WAL -> VFS
                                 |                |
                                 +-> record/page/bytes <-+
```

No lower layer calls the SQL parser. Recovery and integrity checking operate on
pages even when the schema is unreadable.

## Public interface

The M0 `.ssc` scaffold fixes names and ownership but leaves constructors without
an engine implementation. Signatures below are normative; small syntax changes
needed by the self-hosted frontend update this spec before implementation.

### Values and results

```scalascript
sealed trait SqliteValue
case object SqlNull extends SqliteValue
case class SqlInteger(value: Long) extends SqliteValue
case class SqlReal(value: Double) extends SqliteValue
case class SqlText(value: String) extends SqliteValue
case class SqlBlob(value: ByteSlice) extends SqliteValue

case class SqliteColumn(name: String, declaredType: String, value: SqliteValue)
case class SqliteRow(columns: List[SqliteColumn])

sealed trait SqliteResult
case class Rows(cursor: SqliteCursor) extends SqliteResult
case class Updated(changes: Long, lastInsertRowid: Long) extends SqliteResult
```

`SqliteRow` is ordered because duplicate result-column labels are legal. Named
lookup returns the first matching label under SQLite's case-insensitive ASCII
identifier rules; positional lookup is unambiguous.

`ByteSlice` is a target-neutral bounded byte sequence with unsigned values
0 through 255, constant-time indexed access, slicing, and copying. Public API
does not expose `Array[Byte]` or a host buffer. A temporary `List[Int]` adapter
is allowed at VFS edges, but the pager cannot use linked lists as its internal
random-access representation.

### Open options

```scalascript
enum SqliteOpenMode:
  case ReadOnly, ReadWrite, ReadWriteCreate, Memory

enum SqliteJournalMode:
  case Delete, Truncate, Persist, Memory, Wal, Off

enum SqliteSyncMode:
  case Off, Normal, Full, Extra

case class SqliteOpenOptions(
  mode: SqliteOpenMode,
  compatibility: SqliteCompatibility,
  journalMode: SqliteJournalMode,
  synchronous: SqliteSyncMode,
  busyTimeoutMillis: Long,
  pageCachePages: Int,
  trustedSchema: Boolean,
  limits: SqliteLimits
)
```

Defaults are `ReadWriteCreate`, `StrictSqlite`, `Delete`, `Full`, a zero busy
timeout, a bounded implementation-defined cache, and `trustedSchema = false`.
Opening an existing file respects its persistent journal state; an option never
silently rewrites a WAL database as rollback mode.

### Engine, connection, statement, and cursor

```scalascript
trait SqliteEngine:
  def open(vfs: SqliteVfs, path: String, options: SqliteOpenOptions):
    Either[SqliteError, SqliteConnection]

trait SqliteConnection:
  def prepare(sql: String): Either[SqliteError, SqliteStatement]
  def execute(sql: String, params: List[SqliteValue]): Either[SqliteError, SqliteResult]
  def begin(mode: TransactionMode): Either[SqliteError, Unit]
  def commit(): Either[SqliteError, Unit]
  def rollback(): Either[SqliteError, Unit]
  def checkpoint(mode: CheckpointMode): Either[SqliteError, CheckpointResult]
  def registerScalar(function: ScalarFunction): Either[SqliteError, Unit]
  def registerAggregate(function: AggregateFunction): Either[SqliteError, Unit]
  def registerWindow(function: WindowFunction): Either[SqliteError, Unit]
  def registerCollation(collation: SqliteCollation): Either[SqliteError, Unit]
  def close(): Either[SqliteError, Unit]

trait SqliteStatement:
  def bind(index: Int, value: SqliteValue): Either[SqliteError, Unit]
  def bind(name: String, value: SqliteValue): Either[SqliteError, Unit]
  def step(): Either[SqliteError, StatementStep]
  def reset(): Either[SqliteError, Unit]
  def clearBindings(): Unit
  def close(): Unit

sealed trait StatementStep
case class RowStep(row: SqliteRow) extends StatementStep
case object DoneStep extends StatementStep
```

Connections and statements are stateful resources and are not thread-safe.
Different connections may run concurrently. Calling a closed resource returns
`Misuse`; finalization is idempotent. A cursor holds its read snapshot until it
is exhausted, reset, or closed.

Parameters support `?`, `?NNN`, `:name`, `@name`, and `$name`. Indexes are
1-based as in SQLite. Unbound parameters evaluate as NULL. The existing
ScalaScript `sql` fence still uses its mandatory ordered bind rewriting.

## Virtual filesystem contract

### Synchronous engine boundary

The pager uses a synchronous VFS contract so write ordering, lock transitions,
and durability barriers are explicit. Parameterizing every pager operation over
`F[_]` was rejected: it would spread backend scheduling into the storage
algorithm and make crash-ordering harder to audit. An inherently asynchronous
filesystem runs the engine in a worker or supplies an ordered synchronous proxy.
It must not advertise WAL or durability capabilities it cannot implement.

### VFS operations

```scalascript
trait SqliteVfs:
  def name: String
  def open(path: String, kind: FileKind, flags: OpenFlags): Either[VfsError, SqliteFile]
  def delete(path: String, syncDirectory: Boolean): Either[VfsError, Unit]
  def exists(path: String): Either[VfsError, Boolean]
  def fullPath(path: String): Either[VfsError, String]
  def randomness(length: Int): Either[VfsError, ByteSlice]
  def sleep(millis: Long): Unit
  def currentTimeMillis(): Long

trait SqliteFile:
  def readAt(offset: Long, length: Int): Either[VfsError, ByteSlice]
  def writeAt(offset: Long, bytes: ByteSlice): Either[VfsError, Unit]
  def truncate(size: Long): Either[VfsError, Unit]
  def sync(flags: SyncFlags): Either[VfsError, Unit]
  def size(): Either[VfsError, Long]
  def lock(level: LockLevel): Either[VfsError, Unit]
  def unlock(level: LockLevel): Either[VfsError, Unit]
  def checkReservedLock(): Either[VfsError, Boolean]
  def sectorSize: Int
  def deviceCharacteristics: DeviceCharacteristics
  def shmMap(region: Int, size: Int, extend: Boolean): Either[VfsError, SharedRegion]
  def shmLock(offset: Int, count: Int, mode: ShmLockMode): Either[VfsError, Unit]
  def shmBarrier(): Unit
  def shmUnmap(delete: Boolean): Either[VfsError, Unit]
  def close(): Either[VfsError, Unit]
```

`readAt` either returns exactly `length` bytes or returns `ShortRead` together
with a zero-filled tail; callers never observe uninitialized bytes. Arithmetic
on offsets and lengths is checked before VFS dispatch.

`fullPath` defines file identity. Handles for the same identity must observe
compatible locks even inside one process. Lock levels are `None`, `Shared`,
`Reserved`, `Pending`, and `Exclusive`; upgrades and downgrades match SQLite's
rollback locking protocol. Lock failure is `Busy`, not generic I/O corruption.

WAL requires shared-memory regions, a process-visible barrier, and eight lock
bytes compatible with the standard wal-index (`WRITE`, `CHECKPOINT`,
`RECOVER`, and five reader locks). A VFS without these features rejects WAL
mode with `UnsupportedCapability`. It may still support rollback mode.

Durability claims are explicit capabilities: atomic sector sizes, safe append,
sequential writes, powersafe overwrite, undeletable-when-open, and atomic file
deletion. The pager takes only documented optimizations. Unknown capability
bits are treated pessimistically.

## Main database format

### Byte and header rules

- All main-file multi-byte integers are big-endian.
- Page numbers start at 1; page 1 begins with the 100-byte database header.
- Page size is a power of two from 512 through 65536. Header value `1` means
  65536. `usableSize = pageSize - reservedBytes` and is at least 480.
- The magic bytes are exactly `SQLite format 3\u0000`.
- Read/write versions 1 and 2 mean rollback and WAL. Unknown readable versions
  follow the official read-only/reject rules.
- Payload fractions at offsets 21..23 are exactly 64, 32, and 32.
- Header parsing validates change counter, database size,
  version-valid-for, schema cookie/format, encoding, freelist, auto-vacuum,
  application id, and reserved fields before trusting them.
- UTF-8, UTF-16LE, and UTF-16BE databases are readable and writable without
  changing encoding implicitly.

The codec implements SQLite's 1-to-9-byte varint exactly, including signed
64-bit rowids represented through the unsigned bit pattern. Decoders return
structured corruption errors with file offset, page, and field; they never
throw from unchecked indexing.

### Page kinds and cells

Supported page roles are table/index interior/leaf B-tree, freelist trunk/leaf,
overflow, pointer-map, and lock-byte pages. B-tree page type bytes `2`, `5`,
`10`, and `13` select index interior, table interior, index leaf, and table
leaf. Page 1 applies a 100-byte header offset.

The page codec validates:

- header length (8 bytes leaf, 12 bytes interior), cell count, right-most
  child, cell-content offset (zero represents 65536), fragmented-byte count;
- sorted and in-range cell-pointer array;
- freeblock chain order, minimum block size, absence of cycles/overlap, and the
  60-byte fragmented-free limit;
- cell shape for all four B-tree page kinds;
- local payload size using the exact SQLite `X`, `M`, and `K` formula;
- overflow chains, page uniqueness, termination, and total payload length.

Freelist trunk/leaf handling preserves the historical rule that avoids using
the last six trunk entries. Pointer-map pages and auto/incremental vacuum are
read in the read-only milestone and written only after relocation tests exist.

### Record format and comparison

Records contain a varint header length, one serial-type varint per value, then
the bodies. Serial types 0 through 9 and length-derived TEXT/BLOB types 12 and
above are implemented exactly. Types 10 and 11 are rejected in persistent
well-formed files. Integer widths 1/2/3/4/6/8 bytes preserve sign; real values
are IEEE-754 binary64.

Index comparison follows SQLite storage-class order: NULL, numeric, TEXT under
the selected collation, then BLOB byte order. Built-in collations are:

- `BINARY`: compare encoded bytes;
- `NOCASE`: fold ASCII A through Z only;
- `RTRIM`: BINARY after ignoring trailing U+0020 spaces.

Application collations are connection-local and versioned in prepared plans.
Changing a collation invalidates dependent prepared statements and may require
`REINDEX`; the engine never assumes an existing index was built with a newly
registered comparator.

## Pager and page cache

The pager owns database-file state, page cache, transaction snapshot, dirty
page set, journaling mode, and lock state. B-tree and SQL layers never call the
VFS directly.

- Cache identity is `(vfs.name, fullPath, pageSize, generation)`.
- File change counter and schema cookie invalidate stale cache/schema state.
- A page has explicit clean, dirty, journaled/WAL-framed, spilled, and pinned
  states. Illegal transitions are errors.
- Dirty eviction is permitted only after the recovery medium contains the
  required preimage (rollback) or frame (WAL) and ordering barriers hold.
- Reads in a transaction observe one snapshot. A cursor cannot see pages from a
  later commit.
- Checked limits bound cache pages, dirty pages, page number, file bytes,
  overflow depth, schema objects, and allocation requested by corrupt input.
- `close()` either completes a legal commit/rollback/cleanup sequence or
  reports an error while retaining enough state for retry; it never discards a
  hot recovery artifact casually.

## Rollback-journal transactions

M3 implements `DELETE` first, followed by `TRUNCATE`, `PERSIST`, and `MEMORY`.
`OFF` is explicit unsafe mode and is never a default.

The lock state machine is compatible with SQLite:

```text
read:  NONE -> SHARED -> NONE
write: SHARED -> RESERVED -> PENDING -> EXCLUSIVE -> SHARED/NONE
```

Only one RESERVED writer may coexist with readers. PENDING blocks new readers;
EXCLUSIVE waits for existing readers to leave. `BEGIN DEFERRED`, `IMMEDIATE`,
and `EXCLUSIVE` acquire locks at the same observable moments as SQLite.

Before a database page is modified, its original content is recorded once in
`<db>-journal`, with the initial database size. Journal headers, sector padding,
nonce, page records, and sparse checksum follow the official format. Commit
ordering is:

1. write all required journal preimages;
2. sync the journal according to `synchronous` and device capabilities;
3. obtain EXCLUSIVE and write dirty database pages;
4. sync the database;
5. atomically invalidate the journal by delete, truncate, or header zeroing;
6. sync the directory when required, update counters, then release locks.

On open/read, the pager checks for a hot journal before trusting database pages.
Recovery obtains PENDING then EXCLUSIVE without first creating a misleading
RESERVED state, validates records, restores pages and original size, syncs the
database, invalidates the journal, and retains SHARED if the read continues.

`ATTACH` atomic super-journals are deferred until single-database rollback is
proven. Before that milestone, a transaction spanning writable attached
databases fails explicitly rather than committing them independently.

## WAL transactions and wal-index

WAL uses `<db>-wal` plus `<db>-shm` (or equivalent VFS shared memory). Main-file
read/write versions are both 2. The WAL codec implements the 32-byte header,
24-byte frame headers, salts, commit-size marker, and cumulative two-word
checksum with the endianness selected by magic `0x377f0682/0x377f0683`.

A frame is visible only when salts and the entire cumulative checksum chain are
valid. Recovery scans to the last valid committed frame and ignores a partial
transaction tail.

The standard wal-index implementation uses 32-KiB regions, duplicate 48-byte
headers, checkpoint metadata, five read marks, eight lock bytes, `aPgno`, and
`aHash`. `FindFrame(page, maxFrame)` returns the greatest matching frame not
newer than the reader snapshot. Host-native byte order is confined to the
wal-index codec; WAL and main-file byte orders remain format-defined.

Concurrency invariants:

- A connection attached to WAL holds main-file SHARED so another process cannot
  delete WAL state or switch modes underneath it.
- Appending holds exclusive `WAL_WRITE_LOCK`; there is one writer.
- Checkpointing holds `WAL_CKPT_LOCK`; `nBackfill` only increases there.
- Recovery holds WRITE, CHECKPOINT, RECOVER, and reader locks 1..4 exclusively.
- A reader holds one shared read lock for its snapshot; read mark 0 means it can
  read only the main file.
- Reset requires complete backfill and no reader on marks 1..4, and occurs under
  WRITE lock with new salts.

Checkpoint modes `PASSIVE`, `FULL`, `RESTART`, and `TRUNCATE` report busy/read
boundaries rather than silently overstating completion. WAL is synced before
backfill and the database is synced after copied pages. Closing the last
connection may checkpoint and remove WAL/SHM only while holding main-file
EXCLUSIVE; persistent-WAL mode suppresses deletion explicitly.

## B-tree layer

The B-tree API exposes cursors over table rowids and index records:

```text
seek / seekGe / seekLe / first / last / next / previous
insert / delete / payload / key / savePosition / restorePosition
```

Read-only traversal lands first. Write support then adds:

- cell allocation, defragmentation, overflow allocation/freeing;
- page split, redistribution, merge, root growth and root collapse;
- parent divider maintenance and right-most-child rules;
- freelist allocation with secure-delete policy;
- pointer-map maintenance for auto-vacuum;
- cursor save/restore across structural changes.

Every mutation is expressed as pager page edits inside a transaction. Property
tests compare ordered contents before/after random operations; page-layout tests
also validate exact bytes through official SQLite.

## Schema layer

Root page 1 is the table B-tree for `sqlite_schema`. The loader decodes the five
columns (`type`, `name`, `tbl_name`, `rootpage`, `sql`), recognizes the documented
aliases, rejects user-created reserved `sqlite_*` objects, and reparses stored
DDL into a normalized schema model.

Supported storage forms include rowid tables, `INTEGER PRIMARY KEY` aliases,
indexes and autoindexes, `WITHOUT ROWID`, `AUTOINCREMENT`/`sqlite_sequence`,
views, triggers, generated columns, expression/partial indexes, and schema
format differences. They land in separate SQL milestones; opening a schema
containing a not-yet-supported feature remains read-only if safe, otherwise the
specific operation fails. Unsupported DDL is never rewritten approximately.

## SQL frontend, planner, and execution VM

### Parser

The parser is authored with ScalaScript's parser-combinator facilities but owns
a SQLite-specific lexer and AST. It accepts one prepared statement at a time,
tracks UTF-8 source spans, preserves quoted identifier/string distinctions,
supports SQLite comments and parameters, and reports the first unexpected token
plus expected constructs.

Grammar coverage is tracked by statement family against the official syntax
diagrams. M4 starts with:

- `CREATE TABLE`/`CREATE INDEX` and corresponding DROP;
- `INSERT`, `UPDATE`, `DELETE` with constraints and conflict actions;
- `SELECT` with expressions, aliases, WHERE, ORDER BY, LIMIT/OFFSET, simple
  joins, grouping, aggregates, DISTINCT, and compound SELECT;
- `BEGIN`, `COMMIT`, `ROLLBACK`, SAVEPOINT/RELEASE;
- the pragmas required to configure and inspect this engine.

CTEs, windows, RETURNING, UPSERT, triggers, ALTER, ATTACH, VACUUM, ANALYZE,
REINDEX, generated columns, and virtual tables are subsequent explicit slices.

### Semantic values

Execution implements SQLite's five storage classes, three-valued NULL logic,
numeric conversion, column affinity, STRICT tables, comparison/collation rules,
`IS`/`IS NOT`, `IN`, `LIKE`/`GLOB`, CASE, CAST, and row-value behavior.
Integer overflow, division, NaN, signed zero, text encoding, and BLOB conversion
are differential-test obligations rather than host-language defaults.

### Planner

The first correct plan is a table/index scan with explicit filtering and sort.
Optimization is incremental and semantics-preserving:

- rowid and primary-key lookup;
- equality/range index scan with collation and affinity checks;
- covering index;
- nested-loop join ordering;
- OR/IN strategies;
- ORDER BY/GROUP BY/DISTINCT satisfaction;
- automatic indexes and statistics after ANALYZE support.

`EXPLAIN QUERY PLAN` exposes stable ScalaScript plan nodes. It need not reproduce
SQLite's textual cost estimates byte-for-byte, but chosen results and ordering
must match.

### Execution VM

Statements compile to a small register/cursor program inspired by, but not
binary-compatible with, SQLite VDBE. The program is immutable; bindings and
runtime registers belong to a statement instance. Long-running statements
check interruption and limits at backward jumps and cursor steps. Schema-cookie
changes return `SchemaChanged` and allow a single safe reprepare.

## Functions, aggregates, windows, and collations

Registrations are per connection and keyed by case-insensitive name, arity
(fixed or variadic), and preferred text encoding. Re-registering the same key
replaces the old definition and invalidates dependent statements.

```scalascript
case class FunctionFlags(
  deterministic: Boolean,
  directOnly: Boolean,
  innocuous: Boolean,
  subtypeAware: Boolean
)

trait ScalarFunction:
  def name: String
  def arity: FunctionArity
  def flags: FunctionFlags
  def call(args: List[SqliteValue]): Either[SqliteError, SqliteValue]

trait AggregateFunction:
  def initial(): Any
  def step(state: Any, args: List[SqliteValue]): Either[SqliteError, Any]
  def finish(state: Any): Either[SqliteError, SqliteValue]

trait WindowFunction extends AggregateFunction:
  def inverse(state: Any, args: List[SqliteValue]): Either[SqliteError, Any]
  def value(state: Any): Either[SqliteError, SqliteValue]
```

The final source API replaces `Any` aggregate state with an existential wrapper
when the native checker can express it without platform leakage.

With `trustedSchema = false` (the default), application functions cannot run
from views, triggers, CHECK/DEFAULT/generated expressions, expression indexes,
or partial-index predicates unless marked innocuous under the same security
rules as SQLite. Side-effecting functions must be `directOnly`. Loadable native
extensions are outside the pure engine; ScalaScript package imports provide the
extension mechanism.

Virtual/table-valued functions receive a separate spec after scalar, aggregate,
window, and collation parity.

## Errors and limits

All public fallible operations return `Either[SqliteError, A]`. `SqliteError`
contains a stable primary code, extended code, message, optional SQL span,
optional file offset/page number, and causal VFS error. Primary names follow
SQLite where semantics align: `Error`, `Internal`, `Permission`, `Abort`,
`Busy`, `Locked`, `NoMemory`, `ReadOnly`, `Interrupt`, `Io`, `Corrupt`,
`NotFound`, `Full`, `CannotOpen`, `Protocol`, `Empty`, `SchemaChanged`,
`TooBig`, `Constraint`, `Mismatch`, `Misuse`, `NoLargeFile`, `Authorization`,
`Format`, `Range`, and `NotADatabase`.

Limits are checked before allocation or recursion: SQL length, columns,
expression depth, compound terms, function args, attached databases, LIKE
pattern length, variables, trigger depth, page count, row/blob bytes, cache
pages, overflow pages, schema objects, and work memory. Defaults track the
reference engine where practical and are recorded in one `SqliteLimits` value.

Corrupt input cannot cause an infinite freeblock/overflow/B-tree loop,
out-of-bounds access, integer wrap, unbounded allocation, or platform exception.
Every traversal has visited-page detection and a limit derived from file size.

## Security and filesystem trust

- VFS paths are capabilities, not ambient authority. A confined adapter may
  restrict the database and sidecar files to one root.
- Journal/WAL/SHM names derive from the canonical main-file identity; callers
  cannot redirect sidecars independently in strict mode.
- Symlink, hard-link, and alias policy belongs to `fullPath` and must keep lock
  identity coherent.
- Untrusted schema is the default when external functions exist.
- Parser and codec fuzzing treat SQL and database bytes as attacker-controlled.
- Errors redact VFS secrets and do not include arbitrary page contents.
- Extension profiles are negotiated before any write.

## Verification strategy

### Codec and file interoperability

- Golden byte vectors for endian integers, every varint length, serial type,
  page header/cell shape, rollback header/record, WAL header/frame/checksum, and
  wal-index region.
- Generate databases with the pinned reference SQLite across page sizes,
  encodings, reserved bytes, schema formats, auto-vacuum modes, and journal
  modes; read every row/index with this engine.
- Generate/mutate databases with this engine; open them with reference SQLite,
  run representative queries and `PRAGMA integrity_check`.
- Differential record comparison covers NULL/numeric/text/BLOB and all built-in
  collations.

### SQL differential suite

For each supported SQL case, execute the same schema, bindings, and statement on
the reference engine and this engine. Compare rows including storage class,
column labels/order, changes, last rowid, errors and extended codes, transaction
visibility, and final database contents. Plan text is not compared unless the
contract explicitly promises it.

### Crash and I/O fault testing

A deterministic fault VFS records operations and can fail, short-read,
short-write, reorder (within advertised capabilities), or simulate process
death after every operation. For each commit/checkpoint/recovery trace, reopening
must yield either the complete old transaction or complete new transaction,
never a hybrid, and reference SQLite must agree.

The matrix includes sector sizes, sync modes, safe-append/powersafe-overwrite
flags, journal invalidation modes, WAL partial frames, checksum corruption,
checkpoint interruption, and disk-full boundaries.

### Concurrency

- Deterministic model tests enumerate legal lock-state interleavings.
- Multi-handle tests share one in-memory lock manager.
- Multi-process tests use the real JVM/Unix and Windows VFS adapters and mix
  reference SQLite with ScalaScript readers/writers.
- WAL tests cover many readers, one writer, busy handlers, checkpoints, recovery,
  close cleanup, persistent WAL, and stale/read-only SHM cases.

### Cross-backend

Pure codec, page, B-tree, parser, planner, and in-memory-VFS tests run on native
VM, direct ASM, and JS/Node. Host-locking and crash-durability lanes are
capability-specific and may be JVM/OS only. Backend gaps are explicit skips with
reasons, never silent substitution by JDBC/sql.js.

## Milestones and behavior gates

### M0 — specification and module contracts

- [ ] Canonical feature spec defines compatibility, layering, interfaces,
  formats, transaction protocols, SQL/function extensions, tests, and scope.
- [ ] `runtime/std/scljet/` imports and typechecks without platform types,
  intrinsics, JDBC, or sql.js.
- [ ] The module advertises design status and does not expose a fake working
  `open` implementation.

### M1 — bytes, codecs, and VFS foundations

- [ ] Bounds-checked byte slices and exact varint/endian codecs pass golden and
  property tests on VM/ASM/JS.
- [ ] In-memory VFS implements random access, lock identity, shared regions,
  sync trace, and fault injection deterministically.
- [ ] JVM host VFS lives in a std plugin and passes cross-process lock tests.

### M2 — read-only SQLite files

- [ ] Open and validate all legal page sizes, encodings, schema formats, and
  reserved-byte counts.
- [ ] Traverse table/index B-trees, overflow, freelist, and pointer maps without
  mutation; decode sqlite_schema, rowid and WITHOUT ROWID tables.
- [ ] Read the interoperability corpus byte-for-value equal to reference SQLite.
- [ ] Corrupt/fuzzed files fail safely with localized diagnostics.

### M3 — writes and rollback journal

- [ ] Insert/update/delete and B-tree balancing produce files accepted by
  reference `integrity_check`.
- [ ] DELETE/TRUNCATE/PERSIST rollback protocols and hot-journal recovery pass
  exhaustive fault-injection traces.
- [ ] Reference SQLite and this engine safely share rollback-mode files and
  locks across processes.

### M4 — SQL core

- [ ] Parser, semantics, planner, prepared statements, and execution VM pass the
  declared core SQL differential matrix.
- [ ] Constraints, affinity, NULL, collations, rowids, transactions, savepoints,
  schema invalidation, and streaming cursors match reference behavior.
- [ ] `scljet:` integrates with `Db.*` and `sql` fences without changing
  existing `sqlite:` providers.

### M5 — WAL

- [ ] WAL/frame checksums, wal-index lookup, snapshots, recovery and all
  checkpoint modes pass golden and crash tests.
- [ ] Concurrent readers/writer/checkpointer interoperate with reference SQLite
  through compatible host VFS locking and shared memory.
- [ ] A VFS lacking shared-memory locks rejects WAL honestly while retaining
  rollback support.

### M6 — broad SQLite language compatibility

- [ ] Remaining official statement/expression families are tracked and landed
  in differential slices; unsupported syntax is explicit.
- [ ] Views, triggers, ALTER, ATTACH/super-journal, VACUUM, ANALYZE, REINDEX,
  generated columns, expression/partial indexes, STRICT and advanced SELECT
  behavior pass interoperability gates.

### M7 — extensibility

- [ ] Scalar, aggregate, window functions and collations match connection-local
  resolution and trusted-schema security behavior.
- [ ] Virtual tables/table-valued functions have a separate approved spec and
  no host-type leakage.
- [ ] Extended on-disk profile has version negotiation, migration, downgrade,
  and refusal tests.

### M8 — production readiness

- [ ] Differential, fuzz, fault, concurrency, and cross-backend suites are CI
  gates with pinned oracle/tool versions.
- [ ] Benchmarks cover parse/prepare, point/range scan, insert/update, commit,
  checkpoint, cache behavior, and large BLOB streaming; commands and baselines
  are persisted before optimization.
- [ ] Provider cutover, if desired, is a separate user-approved compatibility
  decision backed by real application migrations.

## Decisions

- **Pure engine plus narrow VFS** — chosen to keep algorithms portable and
  auditable. Rejected: implement the engine as JVM/Node intrinsics (would merely
  create another platform wrapper).
- **Synchronous VFS boundary** — chosen for explicit lock and durability order.
  Rejected: make the entire engine generic in an async effect (large semantic
  and verification cost); async stores use a worker/proxy.
- **Opt-in `scljet:` during development** — chosen to avoid breaking the
  mature JDBC/sql.js `sqlite:` paths. Rejected: silently replace existing
  providers before parity gates.
- **Strict and Extended profiles are separate** — chosen so extensions never
  weaken the word “compatible”. Rejected: opportunistically use reserved bytes
  in ordinary SQLite files.
- **VDBE-inspired private VM, not VDBE bytecode compatibility** — chosen because
  observable SQL/file behavior is the contract. Rejected: bind implementation
  to unstable internal opcodes.
- **SclJet / `scljet` public identity** — chosen by Sergiy. The package,
  provider id, development URL scheme, module manifest, and module directory
  use that name. `Sqlite*` remains the compatibility vocabulary for value,
  error, format, and protocol types.
- **Clean implementation from official specifications** — chosen for clarity,
  portability, and independent design. Reference SQLite is an oracle in tests,
  not runtime code.

## Open product decisions

These do not block M0 but should be confirmed before M1 API freeze:

1. First production host priority: JVM/POSIX, browser worker/OPFS, or embedded
   in-memory. The proposed order is in-memory model -> JVM/POSIX -> JS worker.
2. Whether the first writable release must ship WAL immediately or may ship a
   fully crash-safe rollback-journal release first. This spec recommends the
   latter.
3. Whether a future Extended profile is a product goal or only an architectural
   escape hatch. Strict compatibility does not depend on it.

## Results

M0 results are filled after the module scaffold is parsed/typechecked. No
runtime, compatibility, durability, or performance result is claimed yet.

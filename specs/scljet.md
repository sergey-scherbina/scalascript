# SclJet — pure ScalaScript SQLite-compatible engine

Status: **implementation / M2 read-only pager and traversal foundations**
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
- [Invalid UTF Policy](https://www.sqlite.org/invalidutf.html)
- [WAL-mode File Format and wal-index](https://www.sqlite.org/walformat.html)
- [File Locking and Concurrency](https://www.sqlite.org/lockingv3.html)
- [Atomic Commit](https://www.sqlite.org/atomiccommit.html)
- [The SQLite VFS](https://www.sqlite.org/vfs.html)
- [SQL understood by SQLite](https://www.sqlite.org/lang.html)
- [Application-defined SQL functions](https://www.sqlite.org/appfunc.html)

The on-disk target is SQLite format 3 with schema formats 1 through 4. The
differential behavior baseline begins with SQLite **3.53.3**, the current
bug-fix release on 2026-07-12 (source id
`2026-06-26 20:14:12 d4c0e51e4aeb96955b99185ab9cde75c339e2c29c3f3f12428d364a10d782c62`).
M0 originally named 3.53.0; M2 advances to the compatible patch release because
3.53.1..3 include upstream correctness fixes. A test manifest records the
exact oracle version/source id and compile options; any later upgrade is an
explicit compatibility task, not an incidental CI dependency update.

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

### M1 byte API

M1 replaces the placeholder `case class ByteSlice(bytes: List[Int])` with an
immutable chunk table. Chunks contain at most 64 unsigned byte values; the
table is keyed by chunk number and slices retain a `(start, length)` window.
Consequently indexed access performs one map lookup plus at most 63 bounded
list steps, independent of the total byte length. Construction validates every
input value before exposing a slice, and all update/copy operations preserve
the `0..255` invariant. Chunk selection uses
`(absolute - (absolute % 64)) / 64`, which is equivalent to non-negative
integer floor division and remains exact even on a backend that temporarily
loses local integer type evidence.

```scalascript
case class ByteError(code: String, message: String, offset: Int)
case class ByteRead(value: Long, nextOffset: Int)
case class VarintRead(value: Long, length: Int, nextOffset: Int)

case class ByteSlice(chunks: Map[Int, List[Int]], start: Int, length: Int)

object ByteSlice:
  def empty: ByteSlice
  def fromList(values: List[Int]): Either[ByteError, ByteSlice]
  def zeros(length: Int): Either[ByteError, ByteSlice]

def byteSliceToList(bytes: ByteSlice): List[Int]
def byteSliceGet(bytes: ByteSlice, index: Int): Either[ByteError, Int]
def byteSliceUpdated(bytes: ByteSlice, index: Int, value: Int): Either[ByteError, ByteSlice]
def byteSliceSlice(bytes: ByteSlice, offset: Int, size: Int): Either[ByteError, ByteSlice]
def byteSliceConcat(left: ByteSlice, right: ByteSlice): ByteSlice
def byteSliceCopyTo(source: ByteSlice, target: ByteSlice, targetOffset: Int): Either[ByteError, ByteSlice]
def byteSliceZeroExtend(bytes: ByteSlice, size: Int): Either[ByteError, ByteSlice]
```

Byte operations are explicit pure functions. Current self-hosted imports do not
link case-class method bodies and lose imported extension receiver types, while
top-level functions execute identically on the v1 interpreter, native VM, and
direct ASM. A later ergonomic facade may delegate to this canonical functional
surface once receiver operations are portable.

The executable M1 usage example is
[`examples/scljet-bytes.ssc`](../examples/scljet-bytes.ssc).

`bytes.ssc` owns big- and little-endian unsigned 16/32/64-bit reads and
writes plus signed big-endian 16/24/32/48/64-bit reads. Unsigned 32-bit values
are returned as non-negative `Long`; unsigned 64-bit values use the raw signed
`Long` bit pattern because ScalaScript has no wider fixed-width unsigned type.
Every read reports the first unavailable offset and never partially succeeds.

SQLite varint decoding consumes 1 through 9 bytes. In bytes 1 through 8 the
high bit continues and the low 7 bits contribute in big-endian order; byte 9
contributes all 8 bits. A clear high bit terminates before byte 9. Truncation is
`ByteError("truncated-varint", ..., offset)` and returns no value/consumption.
Encoding is canonical: non-negative values use the shortest form, while any
negative `Long` bit pattern uses exactly 9 bytes.

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
  def readAt(offset: Long, length: Int): Either[VfsError, VfsRead]
  def writeAt(offset: Long, bytes: ByteSlice): Either[VfsError, VfsWrite]
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

`VfsRead(bytes, warning)` always contains exactly the requested length after a
successful dispatch. EOF or an injected short read returns the available
prefix plus a zero-filled tail and `Some(short-read)`; callers never observe
uninitialized bytes. `VfsWrite(bytesWritten, warning)` reports a complete write
with `None` or a deliberately short prefix with `Some(short-write)`. Validation
and host failures that produce no meaningful buffer/progress remain the outer
`Left(VfsError)`. Arithmetic on offsets and lengths is checked before dispatch.

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

### M1 deterministic in-memory VFS

The pure test VFS is an immutable transition system. `MemoryVfsState` owns
canonical-path file bytes, open-handle metadata, rollback locks, shared-memory
regions/locks, an operation counter, logical clock/PRNG state, sync trace, and
an ordered fault script. Each operation returns `(newState, result)`; a thin
connection-local cell adapts those transitions to `SqliteVfs` without global
ambient state. Tests can therefore replay and compare traces exactly.

Fault rules match operation ordinal plus optional operation/path and choose one
effect: return an error before mutation, cap a read/write to a short length, or
simulate crash by discarding changes after the most recent durable sync point.
Rules are consumed once in declaration order. Short reads return the available
prefix plus a zero-filled tail and the `short-read` error; short writes mutate
only the reported prefix and return `short-write`. A crash never invents a
filesystem ordering stronger than the advertised device characteristics.

Rollback locks are tracked per canonical file identity and handle. Any number
of SHARED holders may coexist; at most one RESERVED holder may coexist with
SHARED; PENDING blocks new SHARED; EXCLUSIVE requires all other SHARED holders
to leave. Downgrades release stronger ownership atomically. WAL shared-memory
locks cover offsets 0 through 7, support shared/exclusive acquisition over a
contiguous range, reject overlapping incompatible owners, and are released on
handle close/unmap.

The exact portable M1 model surface is:

```scalascript
case class VfsRead(bytes: ByteSlice, warning: Option[VfsError])
case class VfsWrite(bytesWritten: Int, warning: Option[VfsError])

sealed trait MemoryFaultEffect
case class FaultError(error: VfsError) extends MemoryFaultEffect
case class FaultShortRead(maxBytes: Int) extends MemoryFaultEffect
case class FaultShortWrite(maxBytes: Int) extends MemoryFaultEffect
case object FaultCrash extends MemoryFaultEffect

case class MemoryFaultRule(
  ordinal: Long,
  operation: Option[String],
  path: Option[String],
  faultEffect: MemoryFaultEffect
)
case class MemoryFileState(bytes: ByteSlice, kind: FileKind)
case class MemoryHandleState(
  id: Int,
  path: String,
  kind: FileKind,
  readOnly: Boolean,
  deleteOnClose: Boolean,
  lock: LockLevel,
  shmMapped: Boolean
)
case class MemoryRollbackLock(
  shared: List[Int],
  reserved: Option[Int],
  pending: Option[Int],
  exclusive: Option[Int]
)
case class MemoryShmByteLock(shared: List[Int], exclusive: Option[Int])
case class MemoryShmState(
  regions: Map[Int, ByteSlice],
  locks: Map[Int, MemoryShmByteLock]
)
case class MemoryTrace(
  ordinal: Long,
  operation: String,
  path: String,
  detail: String,
  resultCode: String
)
case class MemoryVfsState(
  files: Map[String, MemoryFileState],
  durableFiles: Map[String, MemoryFileState],
  handles: Map[Int, MemoryHandleState],
  rollbackLocks: Map[String, MemoryRollbackLock],
  sharedMemory: Map[String, MemoryShmState],
  nextHandle: Int,
  operationCounter: Long,
  clockMillis: Long,
  randomState: Long,
  trace: List[MemoryTrace],
  faults: List[MemoryFaultRule]
)
case class MemoryStep[A](state: MemoryVfsState, result: Either[VfsError, A])

def memoryVfs(seed: Long, clockMillis: Long, faults: List[MemoryFaultRule]): MemoryVfsState
def memoryCanonicalPath(path: String): Either[VfsError, String]
def memoryOpen(state: MemoryVfsState, path: String, kind: FileKind, flags: OpenFlags): MemoryStep[Int]
def memoryDelete(state: MemoryVfsState, path: String, syncDirectory: Boolean): MemoryStep[Unit]
def memoryExists(state: MemoryVfsState, path: String): MemoryStep[Boolean]
def memoryReadAt(state: MemoryVfsState, handle: Int, offset: Long, length: Int): MemoryStep[VfsRead]
def memoryWriteAt(state: MemoryVfsState, handle: Int, offset: Long, bytes: ByteSlice): MemoryStep[VfsWrite]
def memoryTruncate(state: MemoryVfsState, handle: Int, size: Long): MemoryStep[Unit]
def memorySync(state: MemoryVfsState, handle: Int, flags: SyncFlags): MemoryStep[Unit]
def memorySize(state: MemoryVfsState, handle: Int): MemoryStep[Long]
def memoryLock(state: MemoryVfsState, handle: Int, level: LockLevel): MemoryStep[Unit]
def memoryUnlock(state: MemoryVfsState, handle: Int, level: LockLevel): MemoryStep[Unit]
def memoryCheckReservedLock(state: MemoryVfsState, handle: Int): MemoryStep[Boolean]
def memoryShmMap(state: MemoryVfsState, handle: Int, region: Int, size: Int, extend: Boolean): MemoryStep[ByteSlice]
def memoryShmRead(state: MemoryVfsState, handle: Int, region: Int, offset: Int, length: Int): MemoryStep[ByteSlice]
def memoryShmWrite(state: MemoryVfsState, handle: Int, region: Int, offset: Int, bytes: ByteSlice): MemoryStep[Unit]
def memoryShmLock(state: MemoryVfsState, handle: Int, offset: Int, count: Int, mode: ShmLockMode): MemoryStep[Unit]
def memoryShmBarrier(state: MemoryVfsState, handle: Int): MemoryStep[Unit]
def memoryShmUnmap(state: MemoryVfsState, handle: Int, delete: Boolean): MemoryStep[Unit]
def memoryClose(state: MemoryVfsState, handle: Int): MemoryStep[Unit]
def memoryRandomness(state: MemoryVfsState, length: Int): MemoryStep[ByteSlice]
def memorySleep(state: MemoryVfsState, millis: Long): MemoryStep[Unit]
def memoryCurrentTimeMillis(state: MemoryVfsState): Long
def memoryCrash(state: MemoryVfsState): MemoryVfsState
```

All mutation is expressed by these top-level pure transitions because imported
receiver methods are not yet portable across ScalaScript backends. Handles are
opaque integer capabilities. The memory VFS advertises undeletable-when-open:
deleting an open identity returns `busy`, and `deleteOnClose` removes it when
the last handle closes. `durableFiles` is the last sync-visible snapshot;
`FaultCrash` restores it, drops handles/locks/shared memory, consumes the rule,
and preserves the deterministic operation trace.

The executable transition example is
[`examples/scljet-memory-vfs.ssc`](../examples/scljet-memory-vfs.ssc).

### M1 JVM host VFS boundary

The JVM adapter is a separate std plugin. Main-file process-visible lock bytes
use the standard SQLite layout: PENDING at `1073741824`, RESERVED at
`1073741825`, and the 510-byte SHARED range beginning at `1073741826`. Because
JVM `FileLock` rejects overlapping locks even between channels in one process,
the plugin combines a process-local canonical-path coordinator with OS
`FileChannel` locks; neither layer alone is sufficient for SQLite-compatible
multi-handle and multi-process behavior.

M1 coordinates all SclJet handles in one JVM and interoperates with reference
SQLite through OS locks across process boundaries. POSIX record locks are owned
by the process, so an unrelated native SQLite connection in the same JVM does
not conflict with locks held through `FileChannel`. Same-JVM reference mixing
therefore requires a later lock-broker process or a native SQLite lock-table
bridge and remains a required gate before the provider can replace `sqlite:`;
the M1 adapter does not claim that stronger guarantee.

WAL shared memory maps 32-KiB `-shm` regions and uses process-visible locks at
SHM offsets 120 through 127. The adapter performs positioned I/O, checked
truncate/size, `force` for sync, canonical sidecar identity, and a conservative
sector size/device-characteristic report. Service wiring exposes only VFS host
operations; byte codecs, pager, WAL algorithms, and SQL remain pure `.ssc`.

The plugin boundary intentionally uses opaque integer handles and `List[Int]`
only at the host edge; `jvm-vfs.ssc` converts the structured result and the pure
engine converts bytes to/from `ByteSlice`:

```scalascript
case class JvmVfsResult(code: String, message: String, value: Any)

extern def jvmVfsFullPath(path: String): JvmVfsResult
extern def jvmVfsOpen(path: String, readOnly: Boolean, create: Boolean): JvmVfsResult
extern def jvmVfsDelete(path: String): JvmVfsResult
extern def jvmVfsExists(path: String): JvmVfsResult
extern def jvmVfsReadAt(handle: Int, offset: Long, length: Int): JvmVfsResult
extern def jvmVfsWriteAt(handle: Int, offset: Long, bytes: List[Int]): JvmVfsResult
extern def jvmVfsTruncate(handle: Int, size: Long): JvmVfsResult
extern def jvmVfsSync(handle: Int, dataOnly: Boolean): JvmVfsResult
extern def jvmVfsSize(handle: Int): JvmVfsResult
extern def jvmVfsLock(handle: Int, level: LockLevel): JvmVfsResult
extern def jvmVfsUnlock(handle: Int, level: LockLevel): JvmVfsResult
extern def jvmVfsCheckReservedLock(handle: Int): JvmVfsResult
extern def jvmVfsShmMap(handle: Int, region: Int, size: Int, extend: Boolean): JvmVfsResult
extern def jvmVfsShmRead(handle: Int, region: Int, offset: Int, length: Int): JvmVfsResult
extern def jvmVfsShmWrite(handle: Int, region: Int, offset: Int, bytes: List[Int]): JvmVfsResult
extern def jvmVfsShmLock(handle: Int, offset: Int, count: Int, mode: ShmLockMode): JvmVfsResult
extern def jvmVfsShmBarrier(handle: Int): JvmVfsResult
extern def jvmVfsShmUnmap(handle: Int, delete: Boolean): JvmVfsResult
extern def jvmVfsSectorSize(handle: Int): JvmVfsResult
extern def jvmVfsDeviceCharacteristics(handle: Int): JvmVfsResult
extern def jvmVfsClose(handle: Int): JvmVfsResult
```

The same module provides the executable M2 adapter over that boundary:

```scalascript
case class JvmSharedRegion(handle: Int, region: Int, length: Int)
  extends SharedRegion
case class JvmSqliteFile(handle: Int) extends SqliteFile
case class JvmSqliteVfs(adapterName: String) extends SqliteVfs

def jvmSqliteVfs(): SqliteVfs
```

The wrapper only converts structured plugin results into the target-neutral
`VfsError`, `VfsRead`, `VfsWrite`, file, lock, and shared-region contracts; it
does not implement pager policy. M2 uses canonical path, exists, open, size,
positioned read, SHARED/unlock, and close. The current plugin has no clock,
sleep, or randomness host calls, so those unused M2 methods return a stable
unsupported result or deterministic no-op/zero; writable transaction work must
add real host implementations before relying on them.

The assembled JVM adapter is demonstrated by
[`examples/scljet-jvm-vfs.ssc`](../examples/scljet-jvm-vfs.ssc).

`code == "ok"` means `value` is valid; expected lock contention is `busy`
with no thrown host exception. All other host failures are bounded result codes
and messages. The plugin never parses pages or implements pager/WAL policy.

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

### M2 read-only module boundary

M2 adds target-neutral modules under `runtime/std/scljet/`:

```text
header.ssc       database header, page-number and file-offset rules
record.ssc       serial types, text decoding, and record values
page.ssc         B-tree page headers, cells, freeblocks, and local payload
freelist.ssc     freelist and pointer-map decoding/validation
pager.ssc        SHARED-locked immutable read cache over SqliteVfs
btree.ssc        forward table/index traversal and overflow assembly
schema.ssc       sqlite_schema rows and storage-tree classification
readonly.ssc     minimal public value-level read facade
```

`bytes.ssc`, the immutable `ByteSlice`, and the VFS contracts remain the only
lower layers. Page, record, freelist, and B-tree codecs are pure functions: they
accept bytes plus an explicit context and never call a host API. Only
`pager.ssc` calls `SqliteVfs`/`SqliteFile`. No M2 module imports JDBC, sql.js,
platform buffers, or native filesystem types.

The portable public M2 surface is functional because imported receiver methods
are not yet equally reliable on every backend:

```scalascript
sealed trait SqliteTextEncoding
case object EncodingUnknown extends SqliteTextEncoding
case object EncodingUtf8 extends SqliteTextEncoding
case object EncodingUtf16Le extends SqliteTextEncoding
case object EncodingUtf16Be extends SqliteTextEncoding

sealed trait StorageTreeKind
case object RowidTableTree extends StorageTreeKind
case object RecordKeyTree extends StorageTreeKind

case class StorageRecord(
  rowid: Option[Long],
  record: DecodedRecord
)

case class ReadonlyDatabase(pager: ReadonlyPager, schema: ReadonlySchema)
case class ReadonlyCursorStep(
  database: ReadonlyDatabase,
  cursor: ReadonlyBtreeCursor,
  row: Option[StorageRecord]
)

def openReadonly(
  vfs: SqliteVfs,
  path: String,
  options: SqliteOpenOptions
): Either[SqliteError, ReadonlyDatabase]

def openReadonlyRoot(
  database: ReadonlyDatabase,
  rootPage: Long,
  treeKind: StorageTreeKind
): Either[SqliteError, ReadonlyBtreeCursor]

def readonlyFirst(
  database: ReadonlyDatabase,
  cursor: ReadonlyBtreeCursor
): Either[SqliteError, ReadonlyCursorStep]

def readonlyNext(
  database: ReadonlyDatabase,
  cursor: ReadonlyBtreeCursor
): Either[SqliteError, ReadonlyCursorStep]

def closeReadonly(database: ReadonlyDatabase): Either[SqliteError, Unit]
```

`openReadonly` requires `OpenReadOnly`, acquires and retains a main-file SHARED
lock before reading size/header/pages, loads `sqlite_schema`, and owns the file
handle until `closeReadonly`. Failure after opening releases every acquired
resource. The returned database value is immutable; page reads return a new
pager/database value containing the updated cache while the underlying file
capability remains position-independent.

M2 reads a checkpointed main file only. A non-empty `-journal` or `-wal`
sidecar causes `SqliteUnsupportedCapability` rather than being ignored; hot
journal recovery lands in M3 and a WAL snapshot lands in M5. A clean database
whose header versions remain `2` is readable when both sidecars are absent or
zero length. This fail-closed rule prevents a nominally read-only milestone
from returning stale or half-committed data.

### Exact 100-byte database header

The pure header API is:

```scalascript
case class DatabaseHeader(
  pageSize: Int,
  writeVersion: Int,
  readVersion: Int,
  reservedBytes: Int,
  usableSize: Int,
  changeCounter: Long,
  headerPageCount: Long,
  freelistHead: Long,
  freelistPageCount: Long,
  schemaCookie: Long,
  schemaFormat: Int,
  defaultCacheSize: Long,
  largestRootPage: Long,
  textEncoding: SqliteTextEncoding,
  userVersion: Long,
  incrementalVacuum: Boolean,
  applicationId: Long,
  versionValidFor: Long,
  sqliteVersionNumber: Long,
  headerPageCountTrusted: Boolean
)

case class DecodeLocation(
  fileOffset: Long,
  pageNumber: Option[Long],
  field: String
)

case class DecodeFailure(
  code: SqliteErrorCode,
  message: String,
  location: DecodeLocation
)

def decodeDatabaseHeader(first100: ByteSlice): Either[DecodeFailure, DatabaseHeader]
def databasePageOffset(pageNumber: Long, pageSize: Int): Either[DecodeFailure, Long]
```

All unsigned 32-bit header fields are represented as non-negative `Long`.
`defaultCacheSize` is sign-extended from its signed 32-bit field. The exact
field table is:

| Offset | Width | M2 interpretation |
|---:|---:|---|
| 0 | 16 | exact `SQLite format 3\u0000` magic |
| 16 | 2 | page size: `1` means 65536, otherwise power of two 512..32768 |
| 18 | 1 | write version |
| 19 | 1 | read version |
| 20 | 1 | reserved bytes |
| 21..23 | 3 | exact payload fractions 64, 32, 32 |
| 24 | 4 | change counter |
| 28 | 4 | in-header page count |
| 32 | 4 | first freelist trunk |
| 36 | 4 | total freelist pages |
| 40 | 4 | schema cookie |
| 44 | 4 | schema format 0..4 |
| 48 | 4 | signed suggested cache pages |
| 52 | 4 | largest root page / pointer-map enable |
| 56 | 4 | 0 for a physically initialized empty schema, else 1/2/3 |
| 60 | 4 | user version |
| 64 | 4 | incremental-vacuum flag |
| 68 | 4 | application id |
| 72 | 20 | all zero in StrictSqlite |
| 92 | 4 | version-valid-for |
| 96 | 4 | last writer SQLite version number |

Validation is exact:

- `usableSize = pageSize - reservedBytes` and must be at least 480; odd usable
  sizes and odd non-zero reservations are legal.
- `readVersion` must be 1 or 2. A `writeVersion` of 1 or 2 is normal; a value
  greater than 2 is accepted by M2 only because the connection is read-only.
  Zero/invalid write versions and readable versions greater than 2 fail with
  `SqliteFormat`.
- The page count at offset 28 is trusted only when non-zero and equal change
  counter/version-valid-for values prove that it is current. Otherwise the
  pager derives the count from file length.
- A trusted header count may be smaller than the number of complete physical
  pages; those trailing pages are outside the logical database. It may never
  exceed the physical complete-page count. Strict mode rejects a partial
  trailing page; salvage mode may ignore it with a diagnostic.
- The logical page count must be 1..4294967294 and not exceed
  `limits.pageCount`; every checked `(pageNumber - 1) * pageSize` calculation
  must fit `Long` before I/O.
- `schemaFormat` 1..4 is normal. Zero is provisionally accepted only with an
  empty page-1 schema tree and encoding 0 or 1; schema loading confirms the
  empty-tree invariant. Encoding 0 then means the reference engine's
  not-yet-fixed empty-database encoding and is exposed as `EncodingUnknown`.
- Encoding values 1, 2, and 3 map to UTF-8, UTF-16LE, and UTF-16BE. Encoding 0
  with a non-empty schema and every other value are corrupt.
- `largestRootPage == 0` requires the offset-64 value to be zero. A non-zero
  largest root enables pointer maps; offset 64 is interpreted as false only
  when zero and true for any other unsigned value.
- A zero freelist count requires a zero freelist head and a non-zero count
  requires an in-range non-zero head. Full count and graph validation occurs
  after pages are available.

`decodeDatabaseHeader` maps bad magic or a header shorter than 100 bytes to
`SqliteNotADatabase`; unsupported/invalid version fields to `SqliteFormat`; and
otherwise structurally inconsistent fields to `SqliteCorrupt`. Internal
`DecodeFailure.field` uses stable dotted names such as `header.pageSize`,
`page.freeblock[2].size`, and `record.serialType[4]`. At the public boundary it
becomes the existing `SqliteError` with the primary code, message prefixed by
that field, and populated file/page locations. A VFS cause is reserved for the
existing `cause` field and is not overloaded with codec context.

### Page kinds and cells

Supported page roles are table/index interior/leaf B-tree, freelist trunk/leaf,
overflow, pointer-map, and lock-byte pages. B-tree page type bytes `2`, `5`,
`10`, and `13` select index interior, table interior, index leaf, and table
leaf. Page 1 applies a 100-byte header offset.

The exact pure page model/API is:

```scalascript
sealed trait BtreePageKind
case object IndexInteriorPage extends BtreePageKind   // 0x02
case object TableInteriorPage extends BtreePageKind   // 0x05
case object IndexLeafPage extends BtreePageKind       // 0x0a
case object TableLeafPage extends BtreePageKind       // 0x0d

case class PageContext(
  pageSize: Int,
  usableSize: Int,
  logicalPageCount: Long,
  limits: SqliteLimits
)

case class BtreePageHeader(
  kind: BtreePageKind,
  headerOffset: Int,
  headerBytes: Int,
  firstFreeblock: Int,
  cellCount: Int,
  cellContentStart: Int,
  fragmentedBytes: Int,
  rightMostChild: Option[Long]
)

case class CellPayload(
  totalBytes: Long,
  localBytes: ByteSlice,
  firstOverflowPage: Option[Long]
)

sealed trait BtreeCell
case class TableInteriorCell(leftChild: Long, rowid: Long) extends BtreeCell
case class TableLeafCell(rowid: Long, payload: CellPayload) extends BtreeCell
case class IndexInteriorCell(leftChild: Long, payload: CellPayload) extends BtreeCell
case class IndexLeafCell(payload: CellPayload) extends BtreeCell

case class BtreePage(
  pageNumber: Long,
  header: BtreePageHeader,
  cellPointers: List[Int],
  cells: List[BtreeCell]
)

def decodeBtreePage(
  context: PageContext,
  pageNumber: Long,
  bytes: ByteSlice
): Either[DecodeFailure, BtreePage]

def localPayloadBytes(
  kind: BtreePageKind,
  usableSize: Int,
  payloadBytes: Long
): Either[DecodeFailure, Int]
```

The B-tree header begins at byte 100 on page 1 and byte 0 otherwise. Leaves
have an 8-byte header and interiors a 12-byte header. Relative to that start:
type is at +0, first freeblock +1 (u16), cell count +3 (u16), cell-content
start +5 (u16, zero means 65536), fragments +7 (u8), and an interior-only
right-most child +8 (u32). The `2 * cellCount` pointer array follows. Pointer
array order is logical key order; numeric pointer offsets are not required to
be sorted because cell bodies may be placed arbitrarily.

Cell bytes, in order, are:

| Page kind | Cell representation |
|---|---|
| table leaf 0x0d | payload-size varint, signed-rowid varint, local payload, optional overflow page u32 |
| table interior 0x05 | left-child u32, signed-rowid varint |
| index leaf 0x0a | payload-size varint, local key payload, optional overflow page u32 |
| index interior 0x02 | left-child u32, payload-size varint, local key payload, optional overflow page u32 |

Rowid varints retain their 64-bit two's-complement bit pattern. Payload sizes
are non-negative and at most both 2147483647 and `limits.valueBytes`. A first
overflow pointer appears if and only if `localBytes.length < totalBytes`.

For usable page size `U` and payload size `P`, all arithmetic is checked integer
arithmetic with multiplication before division and truncation toward zero:

```text
M = ((U - 12) * 32 / 255) - 23

table leaf:   X = U - 35
index pages:  X = ((U - 12) * 64 / 255) - 23
K = M + ((P - M) mod (U - 4))

if P <= X       local = P
else if K <= X  local = K
else            local = M
```

Table interior cells have no payload. The modulo branch is evaluated only when
`P > X`, which also guarantees the official non-negative domain for `P - M`.
Changing or simplifying this formula is an on-disk incompatibility.

The page codec validates:

- header length (8 bytes leaf, 12 bytes interior), cell count, right-most
  child, cell-content offset (zero represents 65536), fragmented-byte count;
- logical cell-pointer count and in-range offsets without assuming physical
  offset ordering;
- freeblock chain order, minimum block size, absence of cycles/overlap, and the
  60-byte fragmented-free limit;
- cell shape for all four B-tree page kinds;
- local payload size using the exact SQLite `X`, `M`, and `K` formula;
- overflow chains, page uniqueness, termination, and total payload length.

Every pointer, cell span, freeblock, unallocated interval, and reserved region
is range-checked and pairwise non-overlapping. Freeblocks are increasing by
offset, at least four bytes, cycle-free, and contained in the cell-content
region; fragments total at most 60. Cell count is bounded by both the physical
pointer-array capacity and `limits.columns` only where a record is decoded;
page cell count itself is bounded by `usableSize / 2` before allocation.

### Overflow, freelist, lock-byte, and pointer-map pages

An overflow page stores a next-page u32 at bytes 0..3 and up to `U - 4`
payload bytes at bytes 4 through `U - 1`. Assembly consumes exactly the
declared remaining payload. The final required page must point to zero; a zero
pointer before enough bytes, an extra pointer after all bytes, reuse/cycle,
page 0, an out-of-range page, a lock-byte/ptrmap/freelist page, or more than
`limits.overflowPages` is `SqliteCorrupt` (a configured legal-size cap can
return `SqliteTooBig` before allocation). Payload is assembled incrementally
as immutable chunks and must remain under `limits.valueBytes` and
`limits.workBytes`.

The freelist API/model is:

```scalascript
case class FreelistTrunk(
  pageNumber: Long,
  nextTrunk: Long,
  leaves: List[Long]
)

case class FreelistGraph(
  trunks: List[FreelistTrunk],
  pages: List[Long]
)

def decodeFreelistTrunk(
  context: PageContext,
  pageNumber: Long,
  bytes: ByteSlice
): Either[DecodeFailure, FreelistTrunk]

def validateFreelist(
  pager: ReadonlyPager
): Either[SqliteError, FreelistGraph]
```

A trunk is an array of big-endian u32 values in usable bytes: next trunk,
leaf count `L`, then `L` leaf page numbers. A reader accepts
`0 <= L <= floor(U / 4) - 2`, including modern files that use one of the last
six array slots; the future writer deliberately leaves those six slots unused
for legacy compatibility. Trunks/leaves are unique, non-zero, in range,
not page 1, not lock-byte/ptrmap pages, and their exact total equals the header
freelist count. Leaf contents are never interpreted.

The lock-byte page contains file offsets 1073741824..1073742335 and its page
number is `floor(1073741824 / pageSize) + 1`. The core does not read it as a
database page or assign it another role.

Pointer maps exist exactly when `largestRootPage != 0`. Let `J = floor(U / 5)`.
The first ptrmap is page 2; normally subsequent maps are separated by `J + 1`
pages. If a computed map page is the lock-byte page it moves to the following
page. A target page's entry is the zero-based distance after its governing map,
times five. Each entry is a type byte plus parent u32:

```text
1 root B-tree             parent = 0
2 freelist page           parent = 0
3 first overflow page     parent = owning B-tree page
4 later overflow page     parent = previous overflow page
5 non-root B-tree page    parent = parent B-tree page
```

M2 decodes and cross-checks pointer maps against every B-tree child, overflow
edge, root, and freelist page it visits. Map pages occur only at their computed
locations; all roots are at or below `largestRootPage` and precede every
non-root B-tree/overflow/freelist page. Pointer maps are validation metadata in
M2 and are never repaired.

### Record format and comparison

The pure record API preserves both decoded values and source bytes:

```scalascript
case class DecodedText(
  encoded: ByteSlice,
  encoding: SqliteTextEncoding,
  codePoints: List[Int],
  wellFormed: Boolean
)

case class RecordField(
  serialType: Long,
  encoded: ByteSlice,
  value: Option[SqliteValue],
  text: Option[DecodedText]
)

case class DecodedRecord(
  headerBytes: Int,
  bodyBytes: Int,
  fields: List[RecordField]
)

def decodeRecord(
  payload: ByteSlice,
  encoding: SqliteTextEncoding,
  schemaFormat: Int,
  limits: SqliteLimits
): Either[DecodeFailure, DecodedRecord]
```

The first varint is the total header length including itself. Serial-type
varints must end exactly at that header boundary; their body widths must sum
exactly to the remaining payload. Column count, individual value bytes, and
total working bytes are checked before allocation.

| Serial type | Body bytes | Value |
|---:|---:|---|
| 0 | 0 | NULL |
| 1 | 1 | signed 8-bit integer |
| 2 | 2 | big-endian signed 16-bit integer |
| 3 | 3 | big-endian signed 24-bit integer |
| 4 | 4 | big-endian signed 32-bit integer |
| 5 | 6 | big-endian signed 48-bit integer |
| 6 | 8 | big-endian signed 64-bit integer |
| 7 | 8 | big-endian IEEE-754 binary64 |
| 8 | 0 | integer 0, schema format 4 only |
| 9 | 0 | integer 1, schema format 4 only |
| 10, 11 | - | corrupt in a persistent main database |
| even N >= 12 | `(N - 12) / 2` | BLOB |
| odd N >= 13 | `(N - 13) / 2` | TEXT in database encoding |

TEXT has no stored terminator. SQLite deliberately follows a garbage-in,
garbage-out policy for malformed UTF, so invalid UTF-8, unpaired UTF-16
surrogates, odd UTF-16 byte counts, embedded NUL, and noncharacters are not file
corruption. `DecodedText.encoded` is the authoritative lossless value;
`codePoints` uses U+FFFD for each maximal malformed subsequence and `wellFormed`
reports whether decoding is reversible. TEXT fields set `value = None` and
`text = Some(...)`; non-TEXT fields set `value = Some(...)` and `text = None`.

The pure M2 layer intentionally does not materialize `SqlText(String)`:
ScalaScript v1 currently has no `Int.toChar`/code-point-to-string primitive and
v2 renders dynamically produced chars numerically. Calling a host decoder or a
JSON intrinsic here would violate the pure cross-backend boundary. A later
portable text-construction task projects well-formed `DecodedText` into
`SqlText` and defines the public GIGO string policy; storage comparison and M2
fixture equality use encoded bytes plus code points. `EncodingUnknown` is valid
only for an empty schema, so no TEXT record may be decoded under it.

Index comparison follows SQLite storage-class order: NULL, numeric, TEXT under
the selected collation, then BLOB byte order. Built-in collations are:

- `BINARY`: compare encoded bytes;
- `NOCASE`: fold ASCII A through Z only;
- `RTRIM`: BINARY after ignoring trailing U+0020 spaces.

Application collations are connection-local and versioned in prepared plans.
Changing a collation invalidates dependent prepared statements and may require
`REINDEX`; the engine never assumes an existing index was built with a newly
registered comparator.

M2 implements reusable BINARY/NOCASE/RTRIM primitives and forward physical
index traversal. Arbitrary collation-aware index seek and proof that an index
is sorted require parsed index DDL and land with the SQL/schema semantics in
M4. BINARY compares the encoded byte sequence, NOCASE folds only ASCII A..Z,
and RTRIM removes only trailing U+0020 before BINARY comparison. Numeric
integer/real comparison must not first coerce both operands to a lossy host
`Double`; its differential vectors include values around 2^53 and signed
64-bit endpoints.

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

### M2 immutable read pager

M2 uses only clean cached pages and the following internal transition values:

```scalascript
case class ReadonlyPager(
  vfsName: String,
  canonicalPath: String,
  file: SqliteFile,
  header: DatabaseHeader,
  physicalPageCount: Long,
  logicalPageCount: Long,
  cache: Map[Long, ByteSlice],
  lruOldestFirst: List[Long],
  cacheCapacity: Int,
  limits: SqliteLimits,
  closed: Boolean
)

case class DatabasePage(pageNumber: Long, bytes: ByteSlice)
case class PagerPageRead(pager: ReadonlyPager, page: DatabasePage)

def openReadonlyPager(
  vfs: SqliteVfs,
  path: String,
  options: SqliteOpenOptions
): Either[SqliteError, ReadonlyPager]

def pagerReadPage(
  pager: ReadonlyPager,
  pageNumber: Long
): Either[SqliteError, PagerPageRead]

def closeReadonlyPager(pager: ReadonlyPager): Either[SqliteError, Unit]
```

Opening canonicalizes the path, opens the main database without create/write,
acquires SHARED, checks sidecars, reads exactly 100 header bytes and file size,
then fixes the logical page count. A VFS short read within the declared logical
database is `SqliteCorrupt`; an open/lock/read host failure is mapped to
`SqliteCannotOpen`, `SqliteBusy`, or `SqliteIo` with its bounded VFS cause.
Page 0, pages above the logical count, the lock-byte page, a closed pager, and
offset arithmetic overflow are rejected before I/O.

`cacheCapacity = min(options.pageCachePages, limits.cachePages)` and both
inputs must be positive. Cache replacement is deterministic LRU: a hit moves
the page to the newest end, a miss reads exactly `pageSize` bytes, and insertion
evicts oldest pages until capacity is met. A page read never exposes reserved
bytes as cell/overflow content, but the full page bytes remain cached so a
future writer can preserve them. M2 has no dirty, spill, or partial-page cache
state.

The M2 limit interpretation is fixed without adding another parallel options
object:

| `SqliteLimits` field | Read-only use |
|---|---|
| `pageCount` | logical pages and maximum distinct pages visited |
| `valueBytes` | one cell payload, record, TEXT, or BLOB |
| `cachePages` | maximum resident page count |
| `overflowPages` | links in one overflow chain |
| `schemaObjects` | decoded `sqlite_schema` entries |
| `columns` | serial fields in one record |
| `workBytes` | aggregate temporary bytes for payload/schema/traversal state |

Tree depth is bounded by both logical page count and the number of cursor
frames that fit within `workBytes`; all traversals are iterative. Limit failure
is `SqliteTooBig`, while a cycle, shared child, impossible depth, or declared
length inconsistent with file bytes is `SqliteCorrupt`. This distinction keeps
operator policy separate from malformed input.

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

The M2 forward cursor is an immutable iterative state machine:

```scalascript
case class ReadonlyCursorFrame(
  page: BtreePage,
  nextEvent: Int
)

case class ReadonlyBtreeCursor(
  rootPage: Long,
  treeKind: StorageTreeKind,
  stack: List[ReadonlyCursorFrame],
  discoveredPages: List[Long],
  current: Option[StorageRecord],
  lastRowid: Option[Long],
  leafDepth: Option[Int],
  started: Boolean,
  exhausted: Boolean
)

case class PagerCursorOpen(
  pager: ReadonlyPager,
  cursor: ReadonlyBtreeCursor
)

case class PagerCursorStep(
  pager: ReadonlyPager,
  cursor: ReadonlyBtreeCursor,
  row: Option[StorageRecord]
)
```

Frames retain the decoded page so advancing an immutable cursor never repeats
page decoding merely to recover navigation state. `nextEvent` is a logical
in-order event index: one event per leaf cell, one child event per table
interior edge, and alternating child/separator events on index interiors.
`lastRowid` enforces strict signed-rowid order across leaf boundaries and
`leafDepth` enforces the common-depth invariant. `PagerCursorOpen` and
`PagerCursorStep` are internal transitions; the public `ReadonlyCursorStep`
wraps their updated pager in a `ReadonlyDatabase`.
Each retained decoded frame is charged one `pageSize` unit against
`limits.workBytes`; a descent that would exceed
`floor(workBytes / pageSize)` frames fails with `SqliteTooBig` before reading
the child.

`RowidTableTree` requires table pages throughout. Interior table cells guide
descent but are never rows; leaf cells yield `(Some(rowid), decoded payload)`
in strictly increasing signed-rowid order. `RecordKeyTree` requires index pages
throughout and yields `(None, decoded key record)` in in-order B-tree order:
left child, its separator cell, the next child, and finally the right-most
child. Index interior cells are therefore observable records, not duplicated
navigation-only copies.

Each non-root B-tree page has exactly one discovered parent within a cursor.
Child pointers must be in range, must not target page 1 unless it is the root,
and must not target overflow/freelist/ptrmap/lock-byte roles. Leaf depths must
agree. The cursor decodes only the current path plus the current cell payload;
it never materializes a whole table. `readonlyFirst` starts or rewinds a cursor,
`readonlyNext` advances once, and an exhausted cursor returns `row = None`
idempotently. Reverse traversal and collation-aware seek remain on the general
B-tree API but are not M2 behavior claims.

## Schema layer

Root page 1 is the table B-tree for `sqlite_schema`. M2 decodes its five
columns (`type`, `name`, `tbl_name`, `rootpage`, `sql`) without parsing or
executing stored DDL:

```scalascript
sealed trait SchemaObjectKind
case object SchemaTable extends SchemaObjectKind
case object SchemaIndex extends SchemaObjectKind
case object SchemaView extends SchemaObjectKind
case object SchemaTrigger extends SchemaObjectKind

sealed trait SchemaStorageKind
case object SchemaRowidTable extends SchemaStorageKind
case object SchemaWithoutRowidTable extends SchemaStorageKind
case object SchemaIndexBtree extends SchemaStorageKind
case object SchemaNoBtree extends SchemaStorageKind

case class SchemaEntry(
  rowid: Long,
  kind: SchemaObjectKind,
  name: DecodedText,
  tableName: DecodedText,
  rootPage: Option[Long],
  sql: Option[DecodedText],
  storage: SchemaStorageKind,
  internal: Boolean,
  rawRecord: DecodedRecord
)

case class ReadonlySchema(
  cookie: Long,
  format: Int,
  encoding: SqliteTextEncoding,
  entries: List[SchemaEntry]
)

def decodeSchema(
  pager: ReadonlyPager
): Either[SqliteError, ReadonlyDatabase]
```

The schema record must have exactly five fields. `type`, `name`, and
`tbl_name` are TEXT; `rootpage` is INTEGER or NULL; `sql` is TEXT or NULL.
The raw `type` code points are exactly ASCII `table`, `index`, `view`, or
`trigger`. A positive table root
whose actual root page is a table B-tree is `SchemaRowidTable`; a positive
table root whose page is an index B-tree is `SchemaWithoutRowidTable`; a
positive index root must be an index B-tree. Views, triggers, and virtual-table
rows have zero/NULL root and `SchemaNoBtree`. Other type/root/page-kind
combinations are localized corruption.

Names beginning `sqlite_` are marked `internal` and preserved, including
future reference-engine objects unknown to this version. M2 cannot prove who
created such an object and therefore does not reject it merely by name.
`sqlite_master`, `sqlite_temp_schema`, and `sqlite_temp_master` are API aliases
for the schema table, not alternative bytes stored in the file.

The SQL text is inert data in M2. It is retained exactly as decoded but is not
tokenized to recover column names, affinity, collations, partial predicates,
generated columns, or redundant WITHOUT ROWID key suppression. The value-level
cursor consequently exposes physical record order. Normalized DDL, logical
column projection, `INTEGER PRIMARY KEY` substitution, and arbitrary index seek
land with the SQL frontend/schema semantics in M4.

The assembled real-file flow is demonstrated by
[`examples/scljet-readonly.ssc`](../examples/scljet-readonly.ssc): it writes a
pinned SQLite 3.53.3 image through the JVM VFS plugin, opens it through
`jvmSqliteVfs()`/`openReadonly`, reads schema and one table row, releases the
SHARED lock through `closeReadonly`, and removes the fixture.

### M2 explicit exclusions

M2 does not create or mutate databases, recover rollback journals, overlay WAL
frames, parse SQL/DDL, bind logical column names, apply affinity, execute
expressions, materialize decoded code points as ScalaScript `String`, or provide
a query planner. It does not claim reverse/seek cursors.
A physical zero-byte file, although some SQLite connection APIs treat it as a
logical empty database, has no format-3 header and is outside the M2 raw-file
reader; create/open semantics for that special case land with writable pager
and connection integration. These exclusions must return a stable unsupported,
read-only, or not-a-database error rather than silently using JDBC/sql.js.

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

### M2 pinned corpus and corruption matrix

Committed fixtures live under `tests/fixtures/scljet/m2/` and are immutable
inputs, not regenerated during ordinary test runs. `manifest.tsv` records for
every file: fixture id, SHA-256, exact SQLite version/source id and compile
options, generator SQL/script, page size, reserved bytes, schema format,
encoding, auto-vacuum mode, journal header versions, expected schema entries,
and an ordered storage-class/value dump from the oracle. The generator is a
separate reproducible test tool using the official SQLite 3.53.3 amalgamation;
non-zero reserved-byte fixtures use `SQLITE_FCNTL_RESERVE_BYTES`, not a SclJet
writer. Schema formats 1/2/3 are generated by the official canonical SQLite
`version-3.2.0` source (Fossil manifest
`debf40e8ffa35406685ec027ced1f147ef0487df`): a plain table remains format 1,
`ALTER TABLE ADD COLUMN` without a non-NULL default selects format 2, and the
same operation with a non-NULL default selects format 3. Version 3.2.0 is used
because it is the first release with the SQL operation that exercises the
format-2/3 capabilities; all three results are reopened and accepted by the
3.53.3 oracle. The manifest records both generator and oracle identities.
Rows used for byte/value differential comparison are inserted after the ALTER
so the low-level physical record and reference logical row have the same field
count; default synthesis for pre-ALTER records belongs to the later SQL/schema
semantic layer, not the M2 storage-record facade.

The valid corpus crosses, without requiring a Cartesian explosion:

- every page size 512, 1024, 2048, 4096, 8192, 16384, 32768, and 65536;
- UTF-8, UTF-16LE, UTF-16BE, plus a one-page empty schema with header encoding
  0; schema formats 1, 2, 3, 4, and the legal empty format 0;
- reserved-byte counts 0, odd 1/7, and boundary values that leave exactly 480
  usable bytes, with reference `integrity_check` acceptance recorded; pure
  header property tests enumerate every legal reservation for every page size;
- rollback header versions and clean WAL-version headers with no live sidecar;
- empty/single/multi-level rowid tables, negative/min/max rowids, all legal
  persistent serial types, binary64 edge values, valid and invalid UTF,
  embedded NUL, and BLOBs; invalid text compares/dumps by original bytes and
  separately pins the deterministic code-point projection;
- payloads at `M`, `K`, `X`, and each threshold +/-1 for table and index pages,
  including one and many overflow pages;
- explicit/auto indexes, index interior records, WITHOUT ROWID tables,
  auto-vacuum and incremental-vacuum pointer maps, freelist trunks/leaves, and
  a file large enough to exercise a later pointer-map page;
- unknown-but-legal application id/user version and internal `sqlite_*` schema
  rows that must be preserved.

Each valid fixture must pass reference `PRAGMA integrity_check`, and SclJet's
ordered physical records must match a reference dump including storage class,
integer/real distinction, exact text scalar values, and BLOB bytes. Page/record
pure goldens run without a host VFS; the same fixtures run through the memory
VFS and assembled JVM VFS.

Corrupt fixtures are one-byte/minimal-structure mutations of valid fixtures and
name the expected stable field plus page/file offset. They cover every header
invariant; truncated pages/varints/records; illegal page type; bad pointer
array; overlapping cell/freeblock/reserved spans; freeblock, B-tree, overflow,
freelist and pointer-map cycles/duplicates; premature/extra overflow; illegal
serial 10/11 and format-1 boolean serials; schema type/root
mismatch; limit exhaustion; and arithmetic boundaries. The test accepts no
platform exception, hang, or unbounded allocation. Fuzz smoke mutates bounded
fixture copies and asserts only success or a structured `SqliteError`.

A separate slice of overflow-chain corruptions lives *inside a user table's*
overflow pages, so it is invisible to open-time validation and only surfaces
during forward table traversal: a truncated, out-of-range, or self-looped
`next` pointer, and a length-short overflow page. These are exercised by a
traversal-based dumper rather than the open-time corrupt check.

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

- [x] Canonical feature spec defines compatibility, layering, interfaces,
  formats, transaction protocols, SQL/function extensions, tests, and scope.
- [x] `runtime/std/scljet/` imports and typechecks without platform types,
  intrinsics, JDBC, or sql.js.
- [x] The module advertises design status and does not expose a fake working
  `open` implementation.

### M1 — bytes, codecs, and VFS foundations

- [x] Bounds-checked byte slices and exact varint/endian codecs pass golden and
  property tests on the interpreter, native VM, and direct ASM.
- [x] The same byte-codec golden is exact on JS (v1 JsGen): the exact
  signed-64-bit Long/bitwise lowering landed in `70dfb5a1f` (BigInt-backed `_bit`
  with `asIntN(64)`), and `scljet-byte-codec` now passes `[JS]` in the
  conformance suite. (The v2 self-hosted JS path is a separate lane still gated
  on the `__mk_method_obj__` import primitive — tracked with the v2 work.)
- [x] In-memory VFS implements random access, lock identity, shared regions,
  sync trace, and fault injection deterministically.
- [x] The in-memory VFS golden is exact on JS: `scljet-memory-vfs` now passes
  `[JS]` (the two-handle SHM shared/exclusive transition matches) and the case
  is declared `backends: [int, js]` so CI locks the parity. Re-verified
  2026-07-13; the earlier 31/33 divergence was a stale-binary artifact.
- [x] JVM host VFS lives in a std plugin and passes cross-process lock tests.

### M2 — read-only SQLite files

- [x] Open and validate all legal page sizes, encodings, schema formats, and
  reserved-byte counts.
- [x] Traverse table/index B-trees, overflow, freelist, and pointer maps without
  mutation; decode sqlite_schema, rowid and WITHOUT ROWID tables.
- [x] Read the interoperability corpus byte-for-value equal to reference SQLite
  on the interpreter, native VM, direct ASM, and the pure tree-walk fallback —
  all four tiers reproduce the identical 643-line reference oracle.
- [x] Corrupt/fuzzed files fail safely with localized diagnostics; the 25 named
  corruptions and 32 bounded mutations yield the identical structured results on
  every interpreter execution tier, never a platform exception or hang.
- [x] The record codecs are exact on JS/Node: `scljet-byte-codec` and
  `scljet-page-record-codec` both pass `[JS]` (the signed-64-bit Long/bitwise and
  real-decoding lowering landed in `70dfb5a1f`). The full multi-file corpus
  *dump* still runs only on the `int`/JVM lane (its `jvmSqliteVfs` host reads
  real files); a JS corpus-dump lane would need a JS VFS and is out of M2 scope.

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

M0 landed in `449cfab0f`: `runtime/std/scljet/` contains the `scljet` manifest
and package, portable value/error/options, synchronous random-access/locking/
shared-memory VFS contracts, and connection/statement/cursor/function/collation
interfaces. It contains no `extern def`, platform type, JDBC/sql.js dependency,
or concrete/fake engine constructor.

Verification on 2026-07-12:

- `scripts/sbtc "installBin"` staged the distribution with 108 standard `.ssc`
  modules.
- `tests/conformance/run.sh --only 'scljet-module-contract*'` passed 1/1 on the
  declared interpreter lane.
- `bin/ssc run --native tests/conformance/scljet-module-contract.ssc` produced
  the exact six-line expected output.
- `bin/ssc run --bytecode tests/conformance/scljet-module-contract.ssc`
  produced the same output through direct ASM.

These are interface/import/typechecking results only. No runtime, file-format,
durability, SQL-compatibility, or performance claim is made before M1+.

M1 byte/codecs landed in `58d2e19de`, the deterministic memory VFS in
`e6d027b92`, and the JVM host plugin in `2a594b870` with its assembled example
in `1b9df2b57`. Backend-exact chunk indexing was clarified in `bc212e5f4` and
implemented in `f9518f881`. The JS companion/list-pattern fixes needed to run
the portable modules landed in `830c0db27`.

M1 verification on 2026-07-12:

- `scripts/sbtc "installBin"` staged 111 standard `.ssc` modules and 27
  essential `.sscpkg` plugins, including `scljet-vfs-plugin.sscpkg`.
- `tests/conformance/run.sh --only 'scljet-*' --no-memo` passed 3/3 on the
  declared interpreter lane.
- The 31-line byte golden, 33-line memory-VFS golden, and six-line module
  contract are pairwise exact on the v1 interpreter, native VM, and direct ASM.
- `scripts/sbtc "scljetVfsPlugin/test"` passed 6/6: positioned I/O,
  truncate/sync/canonical identity, bounded intrinsic results, local rollback
  transitions, WAL SHM regions/locks, raw subprocess locks, and official
  Xerial SQLite contention across processes.
- `bin/ssc-tools run --v1 examples/scljet-jvm-vfs.ssc` autoloaded the assembled
  plugin and completed open/write/sync/read/lock/unlock/close/delete.
- Focused JsGen regressions for imported explicit companions and native-array
  `Nil`/`Cons` both pass on Node. (At M1 time the byte golden retained three
  Long/bitwise differences and the memory VFS matched 31/33 lines; both were
  **fully resolved** by `70dfb5a1f` — as of 2026-07-13 `scljet-byte-codec` and
  `scljet-memory-vfs` pass `[JS]` exactly and are declared `backends: [int, js]`.)

The JVM adapter guarantees SclJet-to-SclJet coordination inside one JVM and
reference SQLite interoperability across processes. Same-JVM Xerial mixing
remains deferred to the recorded lock-broker/native lock-table bridge. These
limitations leave the two explicit JS behavior items open; no JDBC/sql.js
fallback is used or claimed.

M2 page/record codecs landed in `66ff828b9` with documentation and runnable
example in `ae709c40a`. Verification on 2026-07-12:

- `scripts/sbtc "installBin"` staged 114 standard `.ssc` modules and the
  unchanged 27 essential plugins.
- `tests/conformance/run.sh --only 'scljet-*' --no-memo` passed 4/4 on the
  declared interpreter lane.
- The 35-line M2 golden is byte-for-output exact on the interpreter, native VM,
  and direct ASM. It covers an official SQLite 3.53.3 header/table-leaf record,
  all page sizes, all four cell layouts, localized corruption, X/M/K thresholds,
  binary64, invalid/valid UTF-8 and UTF-16, and bounded overflow chains.
- `examples/scljet-readonly-codecs.ssc` prints the same header/page/record
  summary on v1, native VM, and direct ASM without JDBC, sql.js, filesystem, or
  a host buffer.
- Node executes the complete golden and matches 34/35 lines. The sole mismatch
  is binary64 `1.5 -> 0` through the existing v1 JS Long/bitwise precision bug;
  no fallback is used. Portable `DecodedText -> SqlText(String)` remains the
  separately tracked code-point string-construction gap.

These are pure codec results, not a claim that the M2 pager, schema loader, or
file-level interoperability corpus is complete; their behavior gates remain
unchecked above.

M2c read-only paging/traversal landed in `4aba98aef` and `d52f89ead`; the
executable JVM VFS facade and its cross-module close regression landed in
`c281958bd`, with the runnable flow documented in `0f5bec401`. Verification on
2026-07-12:

- `scripts/sbtc "installBin"` staged 119 standard `.ssc` modules and 27
  essential plugins.
- `tests/conformance/run.sh --only 'scljet-*' --no-memo` passed 6/6 on the
  declared interpreter lane. The new fixture covers immutable LRU replacement,
  SHARED open/close, fail-closed WAL sidecars, schema classification, row reads,
  non-empty freelists, pointer-map ownership, and corrupted pointer maps.
- The cached multi-level table/index cursor golden is exact on the interpreter,
  native VM, and direct ASM: table leaves yield signed rowids only, index
  interiors yield separator records in order, and shared/cyclic children fail.
- `tests/e2e/scljet-readonly-jvm-vfs-smoke.sh` passes through the assembled JVM
  plugin with `PATH=/usr/bin:/bin`: a pinned SQLite 3.53.3 image is opened,
  schema and one row are read, public `closeReadonly` releases the handle, and
  the file is removed without JDBC/sql.js.
- The explicit Node probe currently rejects the valid sibling leaf with a false
  common-depth error; this backend divergence is recorded as
  `v1-js-scljet-readonly-leaf-depth` and `scljet-js-m2-cursor-parity` rather
  than hidden by a fallback.

M2d's first reference corpus slices landed in `17fd8238a`, `315e68d44`,
`7139649a2`, `12a3dcffa`, and `0c190aec8`. Verification on 2026-07-12:

- Twenty-five immutable valid databases pin official SQLite 3.53.3 source id,
  compile options, SHA-256, header metadata, `integrity_check = ok`, and 643
  ordered physical dump lines. Coverage includes all eight legal page sizes,
  three text encodings plus empty encoding 0, every schema format 0 through 4,
  rowid/WITHOUT ROWID/index trees, multi-level traversal, overflow,
  freelist, full/incremental pointer maps, clean WAL header versions,
  serial/rowid/application/user-version edges, reserved counts 0/1/7/32, and
  table-leaf and index-btree cells straddling the exact overflow thresholds
  (`overflow-thresholds.db` and `index-overflow-thresholds.db`: payloads
  `p = X-1/X/X+1`, the sharp `K > X` fall to the `m`-byte residue, the
  `K <= X` mid-range case, and multi-page overflow chains — for both the
  table-leaf `X = u-35` and the index-leaf `X = ((u-12)*64/255)-23`).
- Formats 1/2/3 are genuine files from canonical SQLite 3.2.0 Fossil manifest
  `debf40e8ffa35406685ec027ced1f147ef0487df`; current SQLite 3.53.3 reopens
  them, reports `integrity_check = ok`, and supplies the logical value oracle.
  The generator requires both the old executable and matching `manifest.uuid`
  before it removes any committed fixture.
- Counts 1, 7, and the 512-byte boundary count 32 are generated by a small C
  tool compiled against the official 3.53.3 amalgamation. It invokes
  `SQLITE_FCNTL_RESERVE_BYTES` and `VACUUM`; no header bytes are fabricated.
- `tests/e2e/scljet-m2-corpus-smoke.sh` passes through the assembled JVM VFS:
  SclJet reproduces all 643 oracle lines, 30 pinned corrupt files produce
  their stable structured errors, and 32 bounded mutations yield only success
  or `SqliteError` (`32:30:2`). A valid 183-page freelist found and now guards
  the former recursive interpreter stack overflow.
- The named corruption slice (30 files) spans all decoded header value
  invariants, trusted page-count/file-size disagreement, B-tree header/pointer
  bounds, pointer-map kind/ownership, freelist range/cycle/count/duplicate
  checks, and deep page-1 sqlite_schema record damage: a reserved serial type
  (10/11), an unknown schema object type, a negative or out-of-range rootpage,
  and a page freeblock chain that is not increasing and in range. Each input
  pins its SHA plus a stable field/message substring and produces no platform
  exception or hang through the assembled runner, on all three interpreter tiers.
- `tests/conformance/run.sh --only 'scljet-*' --no-memo` remains 6/6.

Verification continued on 2026-07-13 (VM/ASM parity lock):

- `tests/e2e/scljet-m2-corpus-smoke.sh` now executes the corpus dump, the 25
  named corruption checks, and the 32 bounded fuzz mutations on three
  interpreter execution tiers and requires byte-identical results from each:
  the default bytecode VM + fast tier + javac JIT, the ASM JIT backend
  (`SSC_JIT_BACKEND=asm`), and the pure tree-walk fallback
  (`SSC_JIT_BYTECODE=off SSC_FASTTIER=off`). All three reproduce the identical
  629-line reference oracle, the identical structured corruption diagnostics,
  and the identical `32:30:2` fuzz outcome. This closes the explicit VM/ASM
  corpus-execution requirement: the pure `.ssc` reader is tier-independent.
- `overflow-thresholds.db` and `index-overflow-thresholds.db` add exact
  payload-threshold vectors on a 512-byte page for both the table-leaf boundary
  (`X = u - 35 = 477`) and the index-leaf boundary
  (`X = ((u - 12) * 64 / 255) - 23 = 102`): total payloads `p = X-1/X/X+1` (the
  inclusive local boundary and the one-byte overflow that drops to the
  `m = 39`-byte residue when `K = m + ((p - m) % (u - 4)) > X`), a `K <= X`
  mid-range/multi-page case that keeps `K` bytes local, and multi-page overflow
  chains. All three tiers reproduce the reference rows byte-for-value, and the
  index fixture exercises index-btree overflow chains distinctly from the table
  path; both are regenerated by the same pinned SQLite 3.53.3 as the rest of the
  corpus (no external helper required).
- Node boundary re-verified 2026-07-13 with a current build: `scljet-byte-codec`
  and `scljet-page-record-codec` now **pass** `[JS]` — the earlier divergence was
  a stale-binary artifact; the exact signed-64-bit Long/bitwise and real-decoding
  lowering had already landed in `70dfb5a1f`. The full scljet conformance slice is
  6/6 (`--no-memo`, fresh build) on all declared backends. The M2 corpus *dump*
  tools declare `backends: [int]` (their `jvmSqliteVfs` host reads real files);
  there is no silent JDBC/sql.js substitution. Remaining JS work is the in-memory
  VFS golden's two-handle SHM transition and the v2 self-hosted JS path
  (`__mk_method_obj__`) — tracked in `BACKLOG.md`.

With the four executable tiers reproducing the reference oracle and the corrupt
diagnostics, the byte-for-value corpus and safe-corruption M2 behavior gates are
now covered on the interpreter, VM, ASM, and fallback lanes; JS exactness stays
an explicit open gate. Exact table-leaf and index-btree payload thresholds are
pinned (`overflow-thresholds.db`, `index-overflow-thresholds.db`), and deep
page-1 record/freeblock/schema byte-mutation corruptions fail safely with
localized diagnostics.

The final M2d hardening item — user-table overflow-chain traversal corruption
on non-schema pages — landed 2026-07-21. It closes a real gap: the open-time
`openReadonly` path validates only the header, pager, and page-1 schema, so a
`next` pointer damaged *inside* a user table's overflow page is never seen until
the row is actually traversed. Three byte-mutations of the multi-page overflow
chain of `overflow-thresholds.db` (the `p = 1100` row spills page 11 → 12) are
now pinned as corrupt fixtures: a truncated `next` (→ 0) and an out-of-range
`next` (→ 99) both surface `overflow chain ended early or points out of range`,
and a self-looped `next` (→ 11) surfaces `overflow chain contains a cycle`.
`openReadonly` accepts all three; the failures appear only when the table is
walked. A new dumper `tests/tools/scljet-corrupt-traverse.ssc` (`backends:
[int]`, `jvmSqliteVfs`) traverses each corrupt file and pins the localized
diagnostics in `corrupt-traversal-errors.txt`, and
`tests/e2e/scljet-m2-corpus-smoke.sh` runs it across the default VM, ASM, and
tree-walk fallback tiers alongside the open-time corrupt checks. Cross-backend
parity (int == JS) is proved by conformance `scljet-overflow-traversal-corrupt`
(`[int, js]`), which reconstructs the same overflow chain in memory from
`buildOverflowTableDatabase`, drives it through the pager cursor, and adds the
truncated-page case (`overflow page is truncated`) that a length-consistent
on-disk file cannot express. The corrupt corpus is now 33 files: 30 open-time
mutations plus these 3 traversal-time ones.

M3 progress (2026-07-13): the first write-path slice landed. `write.ssc`'s
`emptyDatabase(pageSize)` serializes a freshly-created empty database — the
inverse of the M1 header reader — byte-identical to reference SQLite 3.53.3
(`PRAGMA page_size=N; VACUUM`) for page sizes 512/1024/4096/65536, identical on
the VM/ASM/tree-walk tiers, round-tripping through `decodeDatabaseHeader`. An
empty database is schema format 0, text encoding 0, one page, change counter 1.
Conformance `scljet-write-empty` (`[int, js]`, sizes 512..8192 — see the JS
`ByteSlice.zeros` recursion follow-up in `BACKLOG.md`) and example
`examples/scljet-write-empty.ssc`. The M3 behavior gates below (mutation +
integrity_check, rollback/recovery, cross-process locking) remain open pending
slices m3c–m3f.

The m3c record encoder landed next: `write.ssc`'s `encodeRecord(values)` is the
exact inverse of `record.ssc`'s decoder — `varint(headerLen) ++ serial-type
varints ++ body`, choosing the narrowest signed integer serial (0/1 use the 8/9
storage-class serials), UTF-8 text, blob, and NULL. It is byte-identical to both
records inside `page-512.db` (the row `(-2,'Hi',x'00ff')` and the five-field
schema record including the 41-char `CREATE TABLE`), round-trips through
`decodeRecord`, and is identical on int/VM/ASM/fallback and JS (conformance
`scljet-write-record`). `SqlReal` encoding (Double → IEEE-754 bits) is a
tracked follow-up.

m3c then completed with the page/database assembly:
`buildSingleTableDatabase(pageSize, changeCounter, schemaCookie, tableName,
createSql, rows)` writes a legal two-page single rowid-table database — page 1
is `sqlite_schema` with one `CREATE TABLE` entry (root page 2), page 2 is the
table root holding the rows (auto rowids). It is **byte-identical to the pinned
`page-512.db`** with change counter 3 / schema cookie 2 (the values SQLite
writes for `CREATE TABLE` followed by one `INSERT`), reference `PRAGMA
integrity_check` returns `ok`, and reference SQLite reads the rows back — for
that fixture and for an independent `nums` table with three rows including a
NULL. It works on int/VM/ASM/fallback, native `ssc run`, and JS (conformance
`scljet-write-database`, examples `scljet-write-empty`/`scljet-write-table`).
Byte-identity to `page-512.db` transitively proves the SclJet reader reads the
writer's output, since that fixture is already in the read corpus.

m3d added multi-page B-trees: `buildTableDatabase` generalizes the writer via a
bottom-up bulk build — rows are packed into leaf pages, and when they overflow a
single leaf, page 2 becomes a table-interior root (12-byte header with the
rightmost child) over the leaf pages, each divider cell keyed by its left child's
maximum rowid. Verified with reference `integrity_check = ok` and full read-back:
a 200-row table is eight pages (interior root + six leaves) with all rows and the
correct `sum`; a 60-row table is four pages; the single-row case stays
byte-identical to `page-512.db`. Identical on int/VM/ASM/fallback and JS
(conformance `scljet-write-btree`). Cell-overflow-page allocation for large
payloads, trees deeper than two levels, and incremental insert-into-an-existing
database (the pager path) are follow-ups.

m3e began the rollback journal with hot-journal recovery: `journal.ssc`'s
`applyRollbackJournal(db, journal)` parses the official rollback-journal format
(header magic, checksum nonce, initial page count, sector/page size; page records
`u32 pageNo ++ page ++ u32 checksum`), verifies SQLite's sparse checksum
(`nonce + Σ data[pageSize-200k]` in 32-bit arithmetic, confirmed against a real
SQLite journal), restores each pre-image, and truncates to the recorded size — so
an interrupted transaction is undone. Verified byte-identical recovery of a
dirtied `page-512.db` (one record) and `comprehensive.db` (two records) with
reference `integrity_check = ok`; a journal without the complete-header magic is
correctly not hot; a corrupt checksum is rejected (conformance
`scljet-journal-recover`, int/VM/ASM/fallback and JS — a write→recover
round-trip). `writeRollbackJournal` is the exact inverse and produces bytes
identical to what SQLite writes, so the journal format is symmetric. Wiring
recovery into the pager open path (which currently rejects a non-empty journal —
it needs a writable VFS write-back and journal delete) and the transactional
begin/commit/rollback cycle remain, along with m3f delete/update. The change
counter and schema cookie are caller-supplied because they belong to the
pager/journal layer.

# SclJet JDBC — a JDBC-shaped API over the SclJet engine

Status: **specification / design freeze for implementation**
Module (portable façade): `scljet/jdbc.ssc` (installed as `std/scljet/jdbc.ssc`)
Module (JVM driver shim): `runtime/std/scljet-jdbc-plugin/`
Package: `scljet`
URL scheme: `jdbc:scljet:<path>`
Depends on: `specs/scljet.md` (engine), the SQL executor (`scljet/sql.ssc`, done),
the mutable pager / rollback-journal transaction layer (`scljet/journal.ssc`, done).

## Overview

SclJet already exposes three ways to reach its storage engine: the pure functional
codec/pager API (`specs/scljet.md`), and the SQL string front end in
`scljet/sql.ssc` — `queryImage(dbBytes, sql)` for `SELECT`, `executeMutation(dbBytes, sql)`
for `INSERT`/`UPDATE`/`DELETE`/`CREATE`/`DROP`. This spec defines a fourth, **imperative**
front door shaped like `java.sql`: `Driver` → `Connection` → `Statement` /
`PreparedStatement` → `ResultSet` / `ResultSetMetaData`. It is a thin, honest wrapper
over the *existing* executor and mutable pager — it adds no new SQL, no new storage
behavior, and no JDBC/`sqlite-jdbc`/`sql.js` fallback. Its only job is to present the
executor's results and the pager's transactions through the method names JVM tools,
connection pools, and ORMs already speak.

The API ships in **two lanes that agree on method names and semantics**:

1. **JVM lane — a real `java.sql.Driver`.** A thin interop shim in
   `runtime/std/scljet-jdbc-plugin/` implements the `java.sql.*` interfaces in Scala and
   delegates every call to the pure engine. Registered through
   `META-INF/services/java.sql.Driver`, so `DriverManager.getConnection("jdbc:scljet:…")`
   works with no code changes in the caller. Per the project's intrinsic rules
   (AGENTS.md — "new intrinsics always go to `runtime/std/` plugins"), the shim is the
   only place `java.sql.*` and `java.*` are allowed; the engine core stays pure `.ssc`.

2. **Portable lane — a `scljet.jdbc` façade.** A pure `.ssc` module (`scljet/jdbc.ssc`)
   with the same method names (`createStatement`, `prepareStatement`, `executeQuery`,
   `executeUpdate`, `setInt`, `next`, `getString`, `commit`, …) for the interpreter,
   native VM, direct ASM, and JS/Node backends, where a real `java.sql.*` is not
   available. It returns ScalaScript-native types (`Long`, `Double`, `String`,
   `SqliteValue`) instead of `java.lang.*` boxes.

Both lanes call exactly the same executor and pager functions. The JVM shim is a
type/identity adapter over the portable façade's semantics: it converts
`SqliteValue`↔`java.lang.*`, `Either[String, _]`→`SQLException`, and ScalaScript
cursors→`java.sql.ResultSet` state. Nothing in this spec changes the pure engine's
value/error contracts from `specs/scljet.md`.

## Relationship to the existing `sqlite:`/`jdbc:sqlite:` providers

- `jdbc:scljet:` is a **new, distinct URL scheme**. It never intercepts `jdbc:sqlite:`
  or `sqlite:`; the mature Xerial `sqlite-jdbc` and `sql.js` provider paths are
  untouched. On the JVM both drivers can be registered simultaneously — `DriverManager`
  routes by URL prefix.
- The existing `sql` fenced-block front end (which rewrites `${expr}` interpolations to
  ordered binds and calls `Db.query`/`Db.execute`) is unchanged. SclJet JDBC is a
  sibling front door onto the same executor, not a replacement for the fence.
- Reference `sqlite3` (CLI) and, on the JVM, `org.xerial:sqlite-jdbc` appear only as
  **oracles in the conformance tests** (see "Conformance / test plan"). They are never
  runtime dependencies of either lane.

## Goals

- Drive a SclJet database file (or in-memory image) through the standard
  `Connection`/`Statement`/`PreparedStatement`/`ResultSet` surface.
- `PreparedStatement` binds `?` parameters to `SqliteValue`s **at the token/AST level**
  (a bound parameter becomes an `SxLit`), never by string interpolation — so bound
  text/blobs cannot break out of the SQL.
- One `Connection` owns one write transaction. `setAutoCommit`, `commit`, and `rollback`
  map onto the mutable-pager / rollback-journal transaction layer already built.
- A single `SqliteValue`↔JDBC type map, shared by both lanes and reusable by a future
  typed SQL API.
- Deterministic behavior across interpreter / native VM / direct ASM / JS for everything
  that does not require host locking (the portable lane), with a JVM lane that additionally
  satisfies the real `java.sql` contract for existing tools.

## Non-goals (throw `SQLFeatureNotSupportedException` / return "unsupported")

- Scrollable or updatable `ResultSet` (only `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`).
- `CallableStatement` / stored procedures.
- `ResultSet.getBlob`/`getClob`/`getNClob`/streaming LOBs (use `getBytes`/`getString`).
- `DatabaseMetaData` beyond a minimal identifying subset (see "Supported subset").
- `Savepoint`, `Connection.setTransactionIsolation` to anything but the one isolation
  SclJet provides, multi-database `ATTACH`, distributed/XA transactions.
- Statement batching beyond a simple sequential `addBatch`/`executeBatch` loop.
- SQL beyond what `scljet/sql.ssc` already parses/executes (the JDBC layer never extends
  the grammar; unsupported statements surface the executor's own `Left(message)`).

## Two-lane architecture

```text
          caller (ORM / pool / app / .ssc program)
                 │                         │
     java.sql.* (JVM tools)         scljet.jdbc.* (portable, same names)
                 │                         │
  runtime/std/scljet-jdbc-plugin/    scljet/jdbc.ssc  (pure .ssc)
   ScljetDriver/Connection/…            JdbcConnection/JdbcStatement/…
                 └───────────┬─────────────┘
                             ▼
        scljet/sql.ssc   queryImage · executeMutation(+counted) · tokenize · parseSelect
        scljet/journal.ssc  openMutablePager · mutableCommit · mutableRollback ·
                            beginTransaction/commitTransaction/rollbackTransaction
        scljet/mutate.ssc  ImageVfs · ImageFile          scljet/values.ssc  SqliteValue
        scljet/vfs.ssc  SqliteVfs/SqliteFile            (JVM) std/scljet/jvm-vfs.ssc
```

Dependencies point inward only: neither lane is called by the engine; the JVM shim
imports the portable façade's logic (or re-implements the same tiny state machine in
Scala) and adds only host-type conversion and `java.sql` interface conformance.

## Driver and URL grammar

### URL grammar (EBNF)

```ebnf
url        = "jdbc:scljet:" , target ;
target     = memory | resource | filepath ;
memory     = ":memory:" ;                       (* fresh empty in-memory image *)
resource   = "classpath:" , path ;              (* read-only image from the classpath (JVM) *)
filepath   = [ "file:" ] , path ;               (* a host file via the VFS *)
path       = { any-char-except-'?' } , [ "?" , params ] ;
params     = param , { "&" , param } ;
param      = key , "=" , value ;
key        = "mode" | "journal" | "sync" | "busy_timeout"
           | "cache_pages" | "vfs" | "open" | "page_size" ;
```

Examples:

| URL | Meaning |
|---|---|
| `jdbc:scljet::memory:` | new empty image, in RAM, never touches disk |
| `jdbc:scljet:/var/data/app.db` | open (or create) a host file through the VFS |
| `jdbc:scljet:file:./app.db?mode=ro` | read-only host file |
| `jdbc:scljet:classpath:seed.db` | read-only image loaded from the classpath (JVM lane) |
| `jdbc:scljet:app.db?journal=delete&sync=full&cache_pages=2000` | tuned open options |

Query-string parameters map onto `SqliteOpenOptions` (`scljet/values.ssc`):

| param | `SqliteOpenOptions` field | values |
|---|---|---|
| `mode` | `mode` | `ro`→`OpenReadOnly`, `rw`→`OpenReadWrite`, `rwc`→`OpenReadWriteCreate` (default), `memory`→`OpenMemory` |
| `journal` | `journalMode` | `delete` (default), `truncate`, `persist`, `memory`, `wal`, `off` |
| `sync` | `synchronous` | `off`, `normal`, `full` (default), `extra` |
| `busy_timeout` | `busyTimeoutMillis` | non-negative integer ms |
| `cache_pages` | `pageCachePages` | positive integer |
| `page_size` | (create only) | 512..65536, power of two — used when a `:memory:`/new file is created |
| `vfs` | selects the `SqliteVfs` | `image` (default in-memory), `jvm` (host files via `jvmSqliteVfs()`) |

`Driver.acceptsURL(u)` returns true iff `u` starts with `jdbc:scljet:`.
`Driver.getPropertyInfo` reports the keys above. `Driver.jdbcCompliant()` returns
`false` (SclJet is not full-SQL-92). Version numbers come from the module manifest.

### Opening the image

The `Connection` factory resolves `target` to an initial `image: ByteSlice` and a
`SqliteVfs`:

- `:memory:` — `image = emptyDatabase(pageSize)` (`scljet/write.ssc`), `vfs = ImageVfs`.
- `classpath:` (JVM) — read the resource bytes → `ByteSlice`, `vfs = ImageVfs`, force
  `OpenReadOnly`.
- host file — **as shipped (J3), BOTH lanes read the file's bytes into an `ImageVfs`
  (read-modify-rewrite)**; the `vfs=` param is parsed but ignored, and `jvmSqliteVfs()`
  (`std/scljet/jvm-vfs.ssc`) is not used by the shim. Routing the JVM lane through it — so
  real locking and crash-safe durability apply — is open J4 work; see "Durability boundary"
  for the contract this currently implies. A missing file is created on open unless
  `mode=ro`.

Reads always go through the read-only path (`openReadonly(ImageVfs(image), "image.db",
sqlOptions())` inside `queryImage`); writes go through `executeMutation`, which builds a
new image via the mutable pager and rollback journal.

## Connection — transaction ownership

The `Connection` owns the **current database image** and the write transaction. Because
SclJet's write model is *read-modify-rewrite of a whole image* (`executeMutation` returns
a brand-new `ByteSlice`, built internally with `openMutablePager` + `mutableCommit`), the
Connection threads the image explicitly:

### State

```text
Connection {
  url            : String
  vfs            : SqliteVfs
  path           : String                 // "image.db" for :memory:, else the file path
  committed      : ByteSlice              // last durable image
  working        : Option[ByteSlice]      // uncommitted image while autoCommit == false
  autoCommit     : Boolean = true
  readOnly       : Boolean
  options        : SqliteOpenOptions
  closed         : Boolean = false
}
```

`current()` = `working.getOrElse(committed)` — the image every statement reads/writes against.

### Autocommit semantics (precise)

- **`autoCommit == true` (default).** Each `executeUpdate` runs
  `executeMutationCounted(current(), sql)` (see "Statement" below), obtains the new image,
  then **immediately durably persists it** and sets `committed := newImage`, `working := None`.
  A `SELECT` never mutates the image. This is one implicit transaction per statement —
  exactly SQLite's autocommit.

- **`setAutoCommit(false)`.** The next mutating statement starts a transaction: on the
  first DML, `working := Some(committed)`. Each subsequent `executeUpdate` runs
  `executeMutationCounted(current(), sql)` and stores the result back into `working`
  (never touching `committed` or the durable file). `SELECT`s read `current()`, so they
  observe this connection's own uncommitted writes (read-your-writes; SQLite's
  single-connection behavior).

- **`commit()`** — flush `working` durably and promote it:
  1. persist `working` to the durable medium (see "Durability boundary"),
  2. `committed := working.get`, `working := None`.
  Maps to `mutableCommit` at the file boundary. A `commit()` in autocommit mode is a no-op.

- **`rollback()`** — discard `working`: `working := None`, so `current()` reverts to
  `committed`. The durable file is never touched. Maps to `mutableRollback`
  (drop the staged image; `scljet/journal.ssc` `mutableRollback` returns the pager with
  `pending = Nil` — the same "discard uncommitted work" contract).

- **`setAutoCommit(true)`** while a transaction is open first commits the pending work
  (per the `java.sql.Connection` contract), then returns to per-statement commit.

### Durability boundary (how commit reaches the file)

- **`:memory:` / `ImageVfs`** — "persist" is simply swapping the in-memory `ByteSlice`
  (`committed := working`). No file I/O. This is the portable-lane and `:memory:` case.
- **Host file — AS IMPLEMENTED (J3, `ScljetConnectionState.flushDurable`).** The JVM lane
  does **not** use `jvmSqliteVfs()`. Opening reads the whole file
  (`Files.readAllBytes` → `ImageVfs`); every durable change rewrites the whole file
  (`Files.write`, i.e. `TRUNCATE_EXISTING` + write). A durable change is: any statement in
  autocommit, `commit()`, or `setAutoCommit(true)` with pending work. **This is the same
  read-modify-rewrite model as the portable lane** — the "JVM lane gets real locking and
  durability" sentence under "Opening the image" describes the intended design, not the
  shipped one.

  Its consequences are load-bearing and must not be papered over:

  | Property | Status |
  |---|---|
  | Crash-atomicity | **NONE.** `Files.write` truncates first: a crash mid-write leaves a truncated/corrupt file, and the pre-image is gone. There is no journal to replay. |
  | `fsync` | **NEVER CALLED.** A completed `commit()` can still be lost to an OS/host crash; it only guarantees the bytes reached the page cache. |
  | Inter-process locking | **NONE.** No `flock`/`FileLock`, no `busy_timeout`. |
  | Multi-writer | **UNSAFE — silent data loss.** Two connections (in one JVM or two) each hold a full image; the last writer's rewrite discards the other's committed rows entirely. Not a torn row: a lost file. |
  | Reader-during-write | **UNSAFE.** An external reader can observe a partially written file. |
  | Cost | O(file) bytes read on open and written per durable change, regardless of how little changed. |

  **The contract this implies — state it to users, do not let them infer it:** a host-file
  `jdbc:scljet:` connection is **single-writer, single-process, and non-durable across a
  crash**. It is sound for the cases it is used for today (tests, single-process embedded
  storage, read-mostly images) and unsound as a shared database. Prefer `:memory:` or
  `classpath:` when persistence is not needed; use a real SQLite driver when concurrent
  processes or crash-durability are required.

  **The intended design (NOT yet built).** `commit()` should write through
  `jvmSqliteVfs()` using the rollback-journal primitives already in `scljet/journal.ssc`:
  `writePagesJournaled` / `beginTransaction`+`stagePage`+`commitTransaction` journal the
  pre-images of the changed pages, overwrite them in place (`overwritePage`, which copies
  into the chunk map without an O(file) `++`), `sync`, then delete/zero the journal. On
  reopen, a hot journal is replayed by `applyRollbackJournal`. `synchronous`/`journal`
  options would then select the sync/invalidation discipline (`specs/scljet.md`
  "Rollback-journal transactions"). That work also needs a `MutablePager` threaded through
  the Connection (Model B under "Open decisions" #4), since journaling pages requires
  knowing *which* pages changed — the current executor only produces whole images.

  **URL parameters that are advertised but ignored.** `Driver.getPropertyInfo` lists
  `journal`, `sync`, `busy_timeout`, `cache_pages` and `vfs`; `ScljetDriver.openTarget`
  reads only `mode` and `page_size`. The rest are inert until the journaled path above
  lands — they describe a discipline that currently has no implementation to select.

Rejected here (recorded as a future optimization): threading one long-lived `MutablePager`
through the whole transaction so each statement stages only its changed pages instead of
rewriting a whole image. The current executor builds a full image per statement, so
Connection-level batching of page edits requires re-plumbing the executor and is deferred.
Correctness is identical; only per-statement allocation differs.

### Isolation

One isolation level only: `TRANSACTION_SERIALIZABLE` is reported (a single-writer image
under a rollback journal is serializable). `setTransactionIsolation` accepts
`TRANSACTION_SERIALIZABLE` and throws `SQLFeatureNotSupportedException` for weaker levels
requested explicitly (they cannot be honored faithfully). `getTransactionIsolation`
returns `TRANSACTION_SERIALIZABLE`.

**Scope of that claim (J3 reality).** Serializability holds for ONE `Connection`: its
statements run in order against one image, and `commit`/`rollback` promote or discard it
atomically in memory. It does **not** extend across connections on the same host file.
Each connection snapshots the file at open and rewrites it wholesale (see "Durability
boundary"), so two writers do not serialize — they overwrite. `TRANSACTION_SERIALIZABLE`
therefore describes the isolation a single writer sees, which is exactly the supported
configuration; it is not a claim of multi-connection concurrency control.

## Statement and PreparedStatement

### Statement

- `executeQuery(sql) : ResultSet` → `queryImage(current(), sql)`. On `Right(rows)`, build a
  forward `ResultSet` over `rows : List[List[SqliteValue]]` plus the metadata derived from
  `parseSelect` (see "ResultSetMetaData"). On `Left(message)` throw `SQLException(message)`.
- `executeUpdate(sql) : Int` → `executeMutationCounted(current(), sql)` (see below). Threads
  the new image per the autocommit rules and returns the affected-row count as `Int`
  (`getLargeUpdateCount` returns the `Long`).
- `execute(sql) : Boolean` → returns `true` if the parsed statement is a `SELECT`
  (a `ResultSet` is available via `getResultSet`), else `false` with `getUpdateCount`
  giving the count. The lane decides SELECT-vs-DML by the first significant token
  (`tokenize` then `isKw(head, "SELECT")` / `"WITH"`), matching `executeMutation`'s own
  dispatch.
- `close()`, `getConnection()`, `getMoreResults()` (always `false`, single result set),
  `setMaxRows`/`getMaxRows` (honored by truncating the row list), `getWarnings` (null).

### The affected-row count — required small engine addition

`executeMutation(dbBytes, sql) : Either[String, ByteSlice]` returns only the new image;
JDBC's `executeUpdate` must also return the number of rows changed and (for INSERT) the
last rowid. The executors already know these numbers internally
(`stmt.rows.length` for INSERT, the number of deleted rowids for DELETE, the number of
matched rows for UPDATE, 0 for CREATE/DROP). The JDBC layer therefore depends on a thin
**counted variant** to be added to `scljet/sql.ssc`:

```scalascript
case class MutationResult(image: ByteSlice, changes: Long, lastInsertRowid: Long)

// Same dispatch as executeMutation, but each executeInsert/Delete/Update/Create/DropIndex
// also returns its row count. CREATE/DROP → changes = 0. INSERT → lastInsertRowid = the
// highest rowid assigned; other statements leave the previous lastInsertRowid unchanged.
def executeMutationCounted(dbBytes: ByteSlice, sql: String): Either[String, MutationResult]
```

`Connection.getLastInsertRowid()` (and the JVM `getGeneratedKeys()` shim) return
`MutationResult.lastInsertRowid` from the most recent `executeUpdate`. This is the only
engine change JDBC strictly needs beyond the parameter-binding hook below; until it lands,
`executeUpdate` may fall back to a coarse count (rows-before vs rows-after via a follow-up
`SELECT count(*)`), which is correct for INSERT/DELETE but not UPDATE — hence the counted
variant is normative.

### PreparedStatement — parameter binding (precise mechanism)

`PreparedStatement` binds `?` parameters to `SqliteValue`s **through the existing
lexer/parser**, so a bound parameter becomes an `SxLit` and is indistinguishable from a
literal that appeared in the SQL text. No value is ever spliced into the SQL string.

The parameter store is 1-based (JDBC convention, matching SQLite):

```text
PreparedStatement {
  sql     : String
  params  : Array/List[Option[SqliteValue]]   // index 1..N, None = unset
  ...Statement state
}
```

Setters convert host values to `SqliteValue` and store them:

| JDBC setter | stored `SqliteValue` |
|---|---|
| `setInt(i, v)` / `setLong(i, v)` / `setShort` / `setByte` | `SqlInteger(v.toLong)` |
| `setDouble(i, v)` / `setFloat(i, v)` | `SqlReal(v.toDouble)` |
| `setString(i, v)` | `SqlText(v)` |
| `setBytes(i, v)` | `SqlBlob(fromBytes(v))` |
| `setBoolean(i, v)` | `SqlInteger(if v then 1 else 0)` |
| `setNull(i, _)` / `setObject(i, null)` | `SqlNull` |
| `setObject(i, v)` | dispatch on runtime type per the rows above |
| `setBigDecimal` | `SqlText(v.toString)` (SQLite has no decimal; text affinity) |

Execution binds and runs:

```scalascript
// New pure entry points in scljet/sql.ssc (parameterized siblings of the existing ones):
def queryImageParams(dbBytes: ByteSlice, sql: String, params: List[SqliteValue])
  : Either[String, List[List[SqliteValue]]]
def executeMutationCountedParams(dbBytes: ByteSlice, sql: String, params: List[SqliteValue])
  : Either[String, MutationResult]
```

Both are `tokenize` → **`bindParams`** → `resolveSubqueries` → `parseSelect`/DML-dispatch →
execute — i.e. the *only* new step is `bindParams`, inserted before parsing. The mechanism
reuses the existing token stream and the existing `SxLit` node:

1. **Lexer (`tokenize`, `scljet/sql.ssc`).** Add the parameter characters. `?` (0x3F)
   emits `Token("param", "?", ordinal)` where `ordinal` is the 1-based count of `?`
   seen so far (carried in the existing `num : Long` field). `?NNN` emits
   `Token("param", "?", NNN)`. Named forms `:name` / `@name` / `$name` emit
   `Token("param", name, 0)` (optional; positional `?` is the required baseline). This is
   an additive lexer branch; no existing token kind changes.

2. **Carry the bound value on the token.** Extend the `Token` case class with a fourth
   field holding the resolved value:

   ```scalascript
   case class Token(kind: String, text: String, num: Long, bound: Option[SqliteValue])
   ```

   Every existing `Token(k, t, n)` construction passes `None` (a mechanical, behavior-
   preserving edit). Chosen over threading a `params` vector through every parser
   function (`parsePrimary`, `parseExprAtom`, `litValue`, `parseValueList`, …) because it
   keeps the parser pure and un-parameterized: the binding is resolved once, up front.

3. **`bindParams(toks, params) : Either[String, List[Token]]`.** Replace every
   `Token("param", _, ordinal, _)` with `Token("bound", "", 0L, Some(params(ordinal - 1)))`.
   An ordinal with no corresponding bound value → `Left("parameter N is not set")`
   (strict JDBC). A `treatUnboundAsNull` option (default off) may instead bind `SqlNull`,
   matching SQLite's native behavior.

4. **Parser hooks — a bound token becomes an `SxLit`.** Two additive branches, mirroring
   the existing `num`/`str` handling:
   - in `parsePrimary` (the atom rule, ~line 609): add
     `else if tkKind(toks) == "bound" then Right((SxLit(toks.head.bound.get), toks.tail))`.
   - in `litValue` (~line 255, used by `INSERT … VALUES (…)` through `parseValueList`):
     add `if token.kind == "bound" then Right(token.bound.get)`.

   Because `SxLit(value: SqliteValue)` already wraps *any* storage class, this preserves
   full fidelity for integer, real, text, blob, and NULL parameters — including in
   `WHERE`, projections, `VALUES` lists, and `SET` clauses — with zero string
   interpolation. `SqlBlob`/`SqlReal`/`SqlNull` bound parameters, which cannot be spelled
   as `num`/`str` tokens, flow through unchanged.

`clearParameters()` resets `params` to all-`None`. `getParameterMetaData().getParameterCount()`
returns the highest `?` ordinal found by a one-shot `tokenize`+scan of `sql`.

Rejected alternative: token-splice that rewrites `?` into a `num`/`str` token. It cannot
represent real/blob/NULL parameters and would silently corrupt them; the `bound`-token +
`SxLit` route is the only faithful one.

## ResultSet

A `ResultSet` is a **forward-only, read-only cursor** over the
`List[List[SqliteValue]]` returned by `queryImage`/`queryImageParams`, plus the metadata
from `parseSelect`. It mirrors the pure engine's own immutable cursor style
(`readonlyFirst`/`readonlyNext`) but presents the stateful `next()` shape JDBC requires.

### State and navigation

```text
ResultSet {
  rows      : List[List[SqliteValue]]
  meta      : ResultSetMetaData
  cursor    : Int = 0        // 0 = before first row (JDBC beforeFirst)
  currentRow: Option[List[SqliteValue]] = None
  lastWasNull: Boolean = false
  closed    : Boolean = false
}
```

- `next() : Boolean` — advance `cursor`; set `currentRow` to the row at the new position;
  return `false` and clear `currentRow` past the end. Idempotent at end (stays `false`).
- Reading a column with no `next()` yet, or after the end, throws `SQLException`
  ("no current row").
- Column access is 1-based by index, or by label. Label lookup is ASCII
  case-insensitive and returns the **first** matching label (SQLite allows duplicate
  result labels; `SqliteRow`/the projection are ordered — `specs/scljet.md`), matching
  `java.sql.ResultSet.findColumn`.
- `close()`, `isBeforeFirst`/`isAfterLast`/`getRow`, `getFetchSize`/`setFetchSize`
  (advisory, ignored), `getType() == TYPE_FORWARD_ONLY`,
  `getConcurrency() == CONCUR_READ_ONLY`, `getStatement()`, `getWarnings()` (null).

### `wasNull`

`wasNull()` returns `lastWasNull`, set by the most recent getter to `true` iff the column
value read was `SqlNull`. On a numeric getter over `SqlNull` the returned value is the
JDBC zero-default (`0`, `0.0`, `false`) and `wasNull()` is `true`; on `getString`/`getObject`/
`getBytes` over `SqlNull` the result is `null` (JVM) / `None`-equivalent (portable) and
`wasNull()` is `true`.

### The `SqliteValue`↔JDBC type map

**Column value → getter result.** `n : Long`, `x : Double`, `s : String`, `b : bytes`.

| getter | `SqlNull` (wasNull) | `SqlInteger(n)` | `SqlReal(x)` | `SqlText(s)` | `SqlBlob(b)` |
|---|---|---|---|---|---|
| `getBoolean` | `false` | `n != 0` | `x != 0.0` | `castNum(s) != 0` | `b` nonempty |
| `getByte`/`getShort`/`getInt` | `0` | narrow of `n`¹ | `x.toLong` narrowed¹ | `castInt(s)` narrowed | `SQLException` |
| `getLong` | `0` | `n` | `x.toLong` (trunc toward 0) | `castInt(s)` | `SQLException` |
| `getFloat`/`getDouble` | `0.0` | `n.toDouble` | `x` | `castReal(s)` | `SQLException` |
| `getBigDecimal` | `null` | `BigDecimal(n)` | `BigDecimal(x)` | `BigDecimal(s)`² | `SQLException` |
| `getString` | `null` | `n.toString` | `renderReal(x)`³ | `s` | hex string `x'…'`⁴ |
| `getBytes` | `null` | UTF-8 of `n.toString` | UTF-8 of `renderReal(x)` | UTF-8 of `s` | `b` |
| `getObject` | `null` | `Long` | `Double` | `String` | `byte[]` |

¹ `getInt` on a value outside `int` range throws `SQLException` (data would be truncated),
matching strict JDBC; `getLong` never overflows for `SqlInteger` (already `Long`).
² non-numeric text → `SQLException`.
³ `renderReal` is the executor's own real formatter (`scljet/sql.ssc`): integral reals
show a trailing `.0` (`35`→`35.0`), matching the sqlite3 CLI.
⁴ `getString` over a blob returns SQLite's blob-literal hex form; callers wanting raw
bytes use `getBytes`. `getObject` returns the raw `byte[]`.

`castInt`/`castReal`/`castNum` follow **SQLite `CAST`-to-numeric semantics**: parse the
longest leading numeric prefix (optional sign, digits, optional fraction/exponent for
real), else `0`. This keeps the JDBC layer consistent with the engine rather than with
`Integer.parseInt`. A strict-JDBC mode (throw on non-numeric text) is a documented option
flag; the default is SQLite semantics.

**Portable lane** returns the raw ScalaScript scalars (`Long`, `Double`, `String`, and a
`ByteSlice`/`List[Int]` for blobs); `getObject` returns the `SqliteValue` itself. The JVM
shim boxes these into `java.lang.Long`/`Double`/`String`/`byte[]` per the table.

**Value → `java.sql.Types` (used by `getObject`/metadata/`getColumnType`).**

| storage class | `java.sql.Types` | `getColumnTypeName` |
|---|---|---|
| `SqlNull` | `NULL` (0) | `"NULL"` |
| `SqlInteger` | `BIGINT` (-5) | `"INTEGER"` |
| `SqlReal` | `DOUBLE` (8) | `"REAL"` |
| `SqlText` | `VARCHAR` (12) | `"TEXT"` |
| `SqlBlob` | `BLOB` (2004) | `"BLOB"` |

## ResultSetMetaData

`queryImage` returns bare rows without labels, so the metadata is derived by re-parsing
the statement with `parseSelect` (`scljet/sql.ssc`) — the same parse the executor ran:

- **`getColumnCount()`** = length of the projected row (the length of the first result
  row, or, for an empty result, the resolved projection length).
- **`getColumnLabel(i)` / `getColumnName(i)`.**
  - `SELECT *` — the FROM table's column names via `tableColumns(createSql)` (already in
    `scljet/sql.ssc`), concatenated left+right for a star over a join.
  - explicit projection — `projItemNames(stmt.projection)` (bare column name for a column
    item; the aliased/derived name otherwise). A computed expression with no alias gets a
    synthetic label `"columnN"` (1-based), matching sqlite3's default result-column
    naming intent.
- **`getColumnType(i)` / `getColumnTypeName(i)`.** SclJet is manifest-typed (each cell
  carries its own storage class), so the metadata type is a best-effort single type:
  1. if the column is a base-table column, the **declared type affinity** from
     `CREATE TABLE` — resolved by `columnDeclaredType(createSql, colName)` (a small
     helper to add next to `tableColumns`, scanning the type tokens after the column
     name and applying SQLite affinity rules: `INT*`→INTEGER/BIGINT, `CHAR|CLOB|TEXT`→
     VARCHAR, `BLOB`/none→BLOB, `REAL|FLOA|DOUB`→DOUBLE, else NUMERIC);
  2. else the storage class of the first non-null value in that column (per the
     value→`Types` table);
  3. else `Types.NULL` for an all-null / empty column.
- **`isNullable(i)`** = `columnNullableUnknown` (SclJet does not yet surface `NOT NULL`
  from the parsed DDL); a follow-up may read it from `columnDeclaredType`.
- **`getColumnClassName(i)`** = `"java.lang.Long"` / `"…Double"` / `"…String"` /
  `"[B"` / `"java.lang.Object"` per the type.
- **`isSigned`** = true for INTEGER/REAL, false otherwise; **`isCaseSensitive`** = true
  for TEXT; **`getPrecision`/`getScale`** = 0 (SQLite has no fixed precision);
  **`getTableName`** = the FROM table (empty for a `no-FROM`/computed select or a join).

## Supported subset of each interface

Everything not listed throws `SQLFeatureNotSupportedException` (JVM) / returns a stable
"unsupported" `Left`/error (portable). `void`/no-op methods where JDBC allows a no-op
(e.g. `setFetchSize`, `setEscapeProcessing`) are honored silently.

### `Driver`
`connect`, `acceptsURL`, `getPropertyInfo`, `getMajorVersion`, `getMinorVersion`,
`jdbcCompliant()` (→ false), `getParentLogger()` (→ throws per spec).

### `Connection`
Supported: `createStatement()` (no-arg and the `TYPE_FORWARD_ONLY, CONCUR_READ_ONLY`
overload only), `prepareStatement(sql)` (and the forward-only/read-only overload; the
`autoGeneratedKeys` overload for `RETURN_GENERATED_KEYS` wired to last-insert-rowid),
`setAutoCommit`/`getAutoCommit`, `commit`, `rollback` (no-arg), `close`, `isClosed`,
`isValid`, `setReadOnly`/`isReadOnly`, `getMetaData()` (minimal `DatabaseMetaData`),
`setTransactionIsolation(TRANSACTION_SERIALIZABLE)`/`getTransactionIsolation`, `nativeSQL`
(identity), `getCatalog`/`setCatalog` (single unnamed catalog), `clearWarnings`/`getWarnings`.
Unsupported: `prepareCall` (no stored procs), `setSavepoint`/`releaseSavepoint`/
`rollback(Savepoint)`, scrollable/updatable statement overloads, `createBlob`/`createClob`/
`createArray`/`createStruct`, `setSchema`/`abort`/`setNetworkTimeout` beyond no-op,
`setHoldability(HOLD_CURSORS_OVER_COMMIT)`.

### `Statement` / `PreparedStatement`
Supported: `executeQuery`, `executeUpdate` (+`executeLargeUpdate`), `execute`,
`getResultSet`, `getUpdateCount`/`getLargeUpdateCount`, `getMoreResults()` (→ false),
`close`, `isClosed`, `getConnection`, `setMaxRows`/`getMaxRows`,
`setQueryTimeout`/`getQueryTimeout` (advisory), `setFetchSize`/`setFetchDirection(FETCH_FORWARD)`
(advisory), `getGeneratedKeys()` (single-column `ResultSet` with `last_insert_rowid`),
`addBatch`/`executeBatch`/`clearBatch` (sequential loop; `executeBatch` returns per-statement
counts, stops on the first error like a non-atomic batch). PreparedStatement adds all
`setXxx` from the setter table, `clearParameters`, `getMetaData` (the `ResultSetMetaData`
of the query without executing it — derived from `parseSelect`), `getParameterMetaData`.
Unsupported: `setFetchDirection(REVERSE|UNKNOWN)`, `setCursorName`, named-parameter setters
beyond the optional `:name` form, `setArray`/`setRef`/`setRowId`/`setSQLXML`/`setNClob`/
streaming `setBinaryStream`/`setCharacterStream` (use `setBytes`/`setString`).

### `ResultSet`
Supported: `next`, `close`, `wasNull`, all getters in the type-map table by index and by
label, `findColumn`, `getMetaData`, `isBeforeFirst`/`isAfterLast`/`isFirst`/`getRow`,
`getType`/`getConcurrency`/`getFetchDirection(FORWARD)`, `getStatement`,
`getWarnings`/`clearWarnings`, `getHoldability`. Unsupported: every `updateXxx`,
`insertRow`/`deleteRow`/`refreshRow`, `previous`/`absolute`/`relative`/`first`/`last`/
`beforeFirst`/`afterLast` (forward-only), `getBlob`/`getClob`/`getArray`/`getRef`/`getRowId`,
`getBinaryStream`/`getCharacterStream`/`getNString`.

### `DatabaseMetaData` (minimal)
`getDatabaseProductName()` = `"SclJet"`, `getDatabaseProductVersion()` = manifest version,
`getDriverName`/`getDriverVersion`, `getURL`, `getConnection`, `supportsTransactions()` =
true, `supportsResultSetType(TYPE_FORWARD_ONLY)` = true (others false),
`getSQLKeywords()`, `getIdentifierQuoteString()` = `"\""`, `storesLowerCaseIdentifiers`
= false / `storesMixedCaseIdentifiers` = true. Everything else may throw
`SQLFeatureNotSupportedException` or return the conservative default.

**Catalog queries (`getTables`/`getColumns`, landed J4).** In the JDBC row shapes the
javadoc mandates — `getTables` = 10 columns ordered by `(TABLE_TYPE, TABLE_NAME)`,
`getColumns` = 24 ordered by `(TABLE_NAME, ORDINAL_POSITION)` — plus `getTableTypes`
(`TABLE`, `VIEW`) and empty `getCatalogs`/`getSchemas`. `TABLE_CAT`/`TABLE_SCHEM` are
always NULL: one image is one anonymous namespace. Name patterns follow JDBC (`%`, `_`,
`null` = all, `\` escape per `getSearchStringEscape`) and match case-insensitively.

*Mechanism (differs from this spec's original plan).* A `SELECT` over `sqlite_schema` does
**not** work: the engine's `findTable` only resolves entries that have a `rootPage`, and
the schema table is not an entry in its own list. Instead `ScljetCatalog` reads the schema
structurally — `openReadonly(ImageVfs(current()), "image.db", sqlOptions())` →
`db.schema.entries` (`scljet/schema.ssc`) — filtered to `SchemaTable`→`TABLE` and
`SchemaView`→`VIEW` (indexes/triggers are not tables; internal entries are skipped).
Column names and declared types come from parsing each entry's `CREATE TABLE` with the
**engine's own `tokenize`**, mirroring the scan in `tableColumns` (`scljet/sql.ssc`); a
test asserts the resulting names equal the engine's own `imageTableColumns`, so the two
cannot drift. `DATA_TYPE` applies SQLite's documented affinity rules to the declared type;
`TYPE_NAME` is the declared text (`""` when absent). Nullability is reported as
`columnNullableUnknown`/`""` rather than parsed from `NOT NULL`, because the engine does
not enforce those constraints — reporting them would assert a guarantee that does not
exist. The catalog reads the *working* image, so it sees uncommitted DDL inside an open
transaction. Both are diffed against `org.xerial:sqlite-jdbc` on the same DDL.

**Introspection (landed J4).** `getPrimaryKeys` (6 cols, ordered by COLUMN_NAME; `PK_NAME`
NULL — SQLite stores no name for a PK) reads both spellings: column-level
(`id INTEGER PRIMARY KEY`) and table-level (`PRIMARY KEY (a, b)`, incl. the named
`CONSTRAINT pk …` form), with `KEY_SEQ` following the declared key order.
`getIndexInfo` (13 cols, one row per index COLUMN, ordered by
`(NON_UNIQUE, TYPE, INDEX_NAME, ORDINAL_POSITION)`; `TYPE` = `tableIndexOther`) reads the
`SchemaIndex` entries, grouping by `SchemaEntry.tableName` and parsing `UNIQUE` + the key
list out of each `CREATE INDEX`. `CARDINALITY`/`PAGES` are 0 (unknown — no statistics), so
`approximate` is accepted and ignored; `ASC_OR_DESC` is NULL (per-column direction is not
modelled). Internal `sqlite_autoindex_*` entries are skipped (no `CREATE INDEX` text to
read, and the reference driver does not report them either). `getTypeInfo` (18 cols) lists
the five storage classes. `getImportedKeys`/`getExportedKeys`/`getCrossReference` return an
EMPTY 14-column result rather than throwing: the engine models no foreign keys, and a pool
or ORM iterating relationships must not blow up on a database that simply has none.

*Two deliberate deviations from the reference driver* (both asserted by tests, so they
cannot rot into accidents):
1. **`getIndexInfo(unique = true)` filters to unique indexes** — the JDBC contract. Xerial
   `sqlite-jdbc` IGNORES the flag and returns non-unique indexes anyway; that is a bug and
   is not reproduced. A test asserts the reference still misbehaves, so if a future
   sqlite-jdbc fixes it, this note can go.
2. **`getTypeInfo.DATA_TYPE` uses THIS driver's codes** — INTEGER → `BIGINT`, REAL →
   `DOUBLE` — where the reference says `INTEGER`/`REAL`. Our `ResultSetMetaData` and
   `getColumns.DATA_TYPE` already report BIGINT/DOUBLE; matching the reference here would
   make our own metadata self-contradictory, so internal consistency wins. A test pins
   `getTypeInfo` against `getColumns` for the same type names.

*Engine limits this surface exposes (NOT shim gaps).* The engine cannot `CREATE UNIQUE
INDEX` at all (`parseCreateIndex` requires `CREATE INDEX`; `CREATE UNIQUE INDEX` falls
through to `parseCreate` → "expected TABLE"), so every index a scljet-*created* database
can hold is non-unique. The unique path is therefore exercised against a file written by
the reference driver and opened via `jdbc:scljet:<file>` — which also demonstrates that
catalog introspection works on real SQLite files. **That same test pins a live engine bug:
an `INTEGER PRIMARY KEY` column in a real SQLite file reads back as `0`, because the rowid
alias is not substituted (`BUGS.md` → `scljet-ipk-rowid-alias-not-substituted`).**

Still NOT implemented (throw `SQLFeatureNotSupportedException`): `getProcedures`,
`getFunctions`, `getUDTs`, `getSuperTables`, `getBestRowIdentifier`, `getVersionColumns`.

## Error mapping

The SQL layer returns `Either[String, _]`; the lower engine returns
`Either[SqliteError, _]` (`scljet/values.ssc`). Mapping to `java.sql`:

- A `Left(message : String)` from `queryImage`/`executeMutation*` → `SQLException(message)`
  with SQLState `"HY000"` (general) unless the message is recognized (e.g. a parse error →
  `SQLSyntaxErrorException`, SQLState `"42000"`).
- A `SqliteError` (when surfaced) maps its `code` to a SQLState / exception subclass:

  | `SqliteErrorCode` | exception | SQLState |
  |---|---|---|
  | `SqliteConstraint` | `SQLIntegrityConstraintViolationException` | `23000` |
  | `SqliteBusy` / `SqliteLocked` | `SQLTransientException` | `40001` |
  | `SqliteReadOnly` | `SQLException` | `25006` |
  | `SqliteCorrupt` / `SqliteNotADatabase` / `SqliteFormat` | `SQLNonTransientException` | `58005` |
  | `SqliteCannotOpen` / `SqliteIo` | `SQLNonTransientConnectionException` | `08001` |
  | `SqliteMisuse` / `SqliteRange` | `SQLException` | `HY000` |
  | others | `SQLException` | `HY000` |

  `SQLException.getErrorCode()` carries the `SqliteError.extendedCode`; the message
  prefixes the primary code name. The portable lane exposes the same distinctions as a
  small `JdbcError(kind, sqlState, message)` value returned in `Left`.

## Conformance / test plan

Mirror the existing SclJet conformance harness (`tests/conformance/run.sh --only 'scljet-jdbc-*'`,
`backends: [int, js]`, memoized). Every test asserts SclJet-JDBC reads equal a reference
oracle byte-for-value.

- **`scljet-jdbc-query`** (`backends: [int, js]`) — build a table with the writer
  (`buildTableDatabase`), open a `Connection`, `createStatement().executeQuery`, walk the
  `ResultSet`, and render each row through the getter table. Diff the output against
  `renderRows(queryImage(...))` (self-consistency) and against **reference `sqlite3`**
  running the same schema+query (`a|b|c`, NULL blank), so the `ResultSet` getter mapping is
  proven equal to the CLI. Cover all five storage classes, NULLs, duplicate labels,
  `SELECT *` and explicit/derived projections, aggregates, and empty results.
- **`scljet-jdbc-prepared`** (`[int, js]`) — the same queries via `prepareStatement` with
  `?` parameters bound through `setInt`/`setLong`/`setDouble`/`setString`/`setBytes`/`setNull`.
  Assert the bound run equals the literal run **and** equals reference `sqlite3` with the
  same bound values (sqlite3's `.param`/`bind` or an equivalent parameterized run), proving
  the `bound`→`SxLit` path is interpolation-free and full-fidelity (including a blob and a
  real parameter that no `num`/`str` token could carry).
- **`scljet-jdbc-update`** (`[int, js]`) — `executeUpdate` for INSERT/UPDATE/DELETE/CREATE/
  DROP; assert the returned count equals reference `changes()` and the resulting image is
  byte-identical to the corresponding `executeMutation` image (and, out-of-band, passes
  reference `PRAGMA integrity_check` and reads back through `sqlite-jdbc`).
- **`scljet-jdbc-txn`** (`[int, js]`) — `setAutoCommit(false)`, several DML, then `commit`
  vs `rollback`; assert `rollback` reverts to the pre-transaction image (`mutableRollback`
  semantics) and `commit` matches the autocommit result; assert read-your-writes inside the
  open transaction.
- **`scljet-jdbc-metadata`** (`[int, js]`) — `ResultSetMetaData` column count/labels/types
  for `*`, explicit columns, derived expressions, and a join; diff labels/types against a
  fixed expectation and (JVM) against `sqlite-jdbc`'s `ResultSetMetaData`.
- **JVM-only lane** — `scljet-jdbc-driver-smoke` (an `e2e` shell test like
  `tests/e2e/scljet-readonly-jvm-vfs-smoke.sh`): `DriverManager.getConnection("jdbc:scljet:…")`
  against a host file through `jvmSqliteVfs()`, run the query/update/txn suite, and diff the
  `ResultSet` reads against `org.xerial:sqlite-jdbc` opened on the *same* file. This is the
  only lane that needs real `java.sql.*` and host locking; the pure `[int, js]` tests never
  touch a real driver.
- **Backend honesty** — any backend gap (e.g. a JS Long/real precision edge already tracked
  in `specs/scljet.md`) is an explicit skip with a reason, never a silent `sqlite-jdbc`/
  `sql.js` substitution.

Executable example: `examples/scljet-jdbc.ssc` — open `:memory:`, `CREATE TABLE`, prepared
`INSERT`s, a parameterized `SELECT`, walk the `ResultSet` with the getters, and a
`commit`/`rollback` demo — runnable with `ssc run examples/scljet-jdbc.ssc` on the portable
lane, referenced from `README.md` and this spec.

## Dependencies

**Done (reused as-is):**
- SQL executor — `scljet/sql.ssc`: `tokenize`, `parseSelect`, `SxLit`, `SxNode`,
  `queryImage`, `executeMutation`, `renderRows`, `tableColumns`, `projItemNames`,
  `SelectStmt`, `litValue`, `parseValueList`.
- Mutable pager + rollback-journal transaction layer — `scljet/journal.ssc`:
  `openMutablePager`, `mutableGet`/`mutablePut`/`mutableAllocate`, `mutableCommit`,
  `mutableRollback`, `MutablePager`, `PagerCommit`, `beginTransaction`/`stagePage`/
  `commitTransaction`/`rollbackTransaction`, `writePagesJournaled`, `overwritePage`,
  `applyRollbackJournal`.
- Values — `scljet/values.ssc`: `SqliteValue` (`SqlNull`/`SqlInteger`/`SqlReal`/`SqlText`/
  `SqlBlob`), `SqliteError`, `SqliteOpenOptions`.
- VFS — `scljet/vfs.ssc`, `scljet/mutate.ssc` (`ImageVfs`/`ImageFile`),
  `scljet/jvm-vfs.ssc` (`jvmSqliteVfs()`) for the JVM host-file lane.

**Required small engine additions (this feature owns them):**
1. `executeMutationCounted` (+ `executeMutationCountedParams`) returning
   `MutationResult(image, changes, lastInsertRowid)` — so `executeUpdate`/`getGeneratedKeys`
   are faithful.
2. Parameter binding in `scljet/sql.ssc`: the `?`/`?NNN` lexer branch, the `Token.bound`
   field, `bindParams`, and the `parsePrimary`/`litValue` `"bound"` branches, plus
   `queryImageParams`.
3. `columnDeclaredType(createSql, colName)` for `ResultSetMetaData.getColumnType` (optional;
   metadata falls back to the first-row storage class without it).
4. Export the new façade types from `scljet/index.ssc`; add the JVM plugin
   `runtime/std/scljet-jdbc-plugin/` with its `META-INF/services/java.sql.Driver` and the
   `build.sbt` wiring (a `lazy val`, `% Test` on `backendInterpreter`, CLI plugin list) per
   AGENTS.md's intrinsic-plugin rules.

## Open decisions

1. **How the JVM interop shim reaches `java.sql.*`.** Three routes, to pick before coding:
   (a) a Scala plugin under `runtime/std/scljet-jdbc-plugin/` implementing the `java.sql`
   interfaces directly (cleanest; the interfaces are large but the unsupported methods are
   one-line throws — recommended); (b) `@jvm("…")` FFI shims per method (too fine-grained
   for ~200 interface methods); (c) generate the interface implementations from a table.
   Recommendation: (a), one Scala file per interface, delegating to the portable state
   machine.
2. **Unbound-parameter policy.** Strict JDBC (throw) vs SQLite-native (`NULL`). Default
   proposed: strict, with a `treatUnboundAsNull` connection option.
3. **`getString`/`getInt` over the "wrong" storage class.** SQLite `CAST` semantics
   (engine-consistent, proposed default) vs strict `java.sql` (throw). A per-connection
   `strictGetters` flag toggles it.
4. **Connection transaction model.** Whole-image rewrite per statement (Model A, proposed
   — simplest, correct, matches today's executor) vs a shared long-lived `MutablePager`
   staging page edits across statements (Model B — less allocation, needs executor
   re-plumbing). Ship A; keep B as a perf follow-up.
5. **Host-file access on the portable lane.** The pure lanes have no host filesystem, so
   `jdbc:scljet:<file>` on `[int, js]` either reads the whole file into an `ImageVfs`
   (read-modify-rewrite) or is limited to `:memory:`. Decide whether a JS/OPFS VFS lands
   here or stays with the engine's VFS milestones.
6. **`getGeneratedKeys` shape.** ✓ **RESOLVED (J4, 2026-07-15) — a one-column `ResultSet`
   of `last_insert_rowid`**, as proposed; the full inserted row was rejected. Confirmed
   against the reference driver rather than by convention alone: Xerial `sqlite-jdbc`
   returns exactly one column labelled `last_insert_rowid()` with one row after an INSERT,
   and an EMPTY `ResultSet` after CREATE/UPDATE/DELETE/SELECT or on a statement that has
   not executed. The shim matches that observable contract (asserted by a diff against
   sqlite-jdbc over a mixed statement sequence) with one deliberate deviation: in the empty
   case Xerial labels the column `1` — a placeholder artifact — where the shim keeps the
   stable `last_insert_rowid()` label. Keys are tracked per `Statement` for INSERT/REPLACE
   that changed ≥1 row, since UPDATE/DELETE/DDL leave SQLite's `last_insert_rowid()`
   untouched.

## Milestones and behavior gates

### J0 — spec (this document)
- [x] URL grammar, two-lane split, `Connection`/`Statement`/`PreparedStatement`/`ResultSet`
  subset, `SqliteValue`↔JDBC type map, transaction/autocommit semantics, parameter-binding
  mechanism, error mapping, conformance plan, dependencies, open decisions.

### J1 — parameter binding + counted mutation in the engine ✓ DONE 2026-07-15
- [x] `?` lexer token + defaulted `Token.bound` + `bindParams` pass → bound param becomes an
  `SxLit`; `queryImageParams`/`executeMutationCountedParams`. (`5a64800c7`.)
- [x] `executeMutationCounted`/`…Params` → `MutationResult(image, changes, lastInsertRowid)`
  (`30fd8fb33`). Note the follow-up filed later: `lastInsertRowid` is wrong for an
  `INTEGER PRIMARY KEY` table (reports a sequential counter, not the assigned rowid) —
  `BUGS.md`, found during J4.

### J2 — portable `scljet.jdbc` façade ✓ DONE (`25ea1023e`; example 2026-07-17)
- [x] `JdbcConnection`/`JdbcResultSet` over the engine, forward-only read-only cursor, typed
  getters (by index + label), autocommit + `commit`/`rollback` threading — conformance
  `scljet-jdbc-basic`/`scljet-sql-params`/`scljet-typedsql-*` green on `[int, js]` vs reference
  `sqlite3`. (`JdbcStatement`/`JdbcPreparedStatement`/`JdbcResultSetMetaData` are the JVM shim's
  J3 types; the portable façade threads the connection functionally instead of holding statement
  objects.)
- [x] `examples/scljet-jdbc.ssc` (2026-07-17) runs on the portable `[int, js]` lanes. NOT on the
  native front — importing the façade trips a parse gap there (`BUGS.md` →
  `v2-native-front-rejects-jdbc-facade`), which is a native-front issue, not the façade's.

### J3 — JVM `java.sql.Driver` shim ✓ DONE 2026-07-15 (`9ac5d0a62`, hardened through 2026-07-16)
- [x] `runtime/std/scljet-jdbc-plugin/` registers `ScljetDriver`; `DriverManager` connects;
  Connection/Statement/PreparedStatement/ResultSet/*MetaData as `java.lang.reflect.Proxy` shims
  over the embedded interpreter; the query/prepared/update/txn/metadata suite passes and
  cross-checks value-for-value against `org.xerial:sqlite-jdbc`. `sbt scljetJdbcPlugin/test`
  **56/56**.
- [x] Unsupported methods throw `SQLFeatureNotSupportedException` as enumerated (8 `nse`
  fall-throughs across the proxy handlers).
- [~] NOTE: host-file writes are whole-image `Files.write`, NOT through `jvmSqliteVfs()` — no
  journal, fsync or locking. The J3 checklist line above overstated it; corrected here and
  tracked as J4 "Journaled host-file writes" (the durability work in progress).

### J4 — hardening
- [x] `getGeneratedKeys` — one-column `last_insert_rowid()` `ResultSet`, tracked per
  statement for INSERT/REPLACE, diffed against `sqlite-jdbc` (2026-07-15, `scljet-jdbc-j2`).
- [x] `getParameterMetaData` — landed with J3.
- [x] minimal `DatabaseMetaData.getTables`/`getColumns` (+ `getTableTypes`, empty
  `getCatalogs`/`getSchemas`) — JDBC row shapes, patterns, affinity-mapped `DATA_TYPE`,
  diffed against `sqlite-jdbc` (2026-07-15, `scljet-jdbc-j2`).
- [x] error→SQLState mapping (`ScljetErrors`) and `executeBatch` — landed with J3.
- [x] Host-file durability/locking contract documented honestly ("Durability boundary" +
  "Isolation"): read-modify-rewrite, no journal/fsync/locking, single-writer/single-process
  (2026-07-15, `scljet-jdbc-j2`).
- [ ] **Journaled host-file writes** — the gap the J4 durability note records: route
  `commit()` through `jvmSqliteVfs()` + `scljet/journal.ssc` so a host file is crash-atomic
  and `fsync`ed, and honour the advertised `journal`/`sync`/`busy_timeout`/`vfs` URL params.
  Needs a Connection-level `MutablePager` (Model B, "Open decisions" #4) — journaling needs
  to know which PAGES changed, and today's executor only yields whole images.
- [ ] Inter-process locking for host files (`FileLock` + `busy_timeout`); until it exists,
  the single-writer/single-process contract stands.
- [x] `getPrimaryKeys`/`getIndexInfo`/`getTypeInfo` + empty FK queries — JDBC row shapes,
  diffed against `sqlite-jdbc` (2026-07-15, `scljet-jdbc-j4-introspection`).
- [ ] The full conformance matrix as a CI gate; backend gaps are explicit skips.
  (`sbt scljetJdbcPlugin/test` = 42/42 today and covers the JVM lane end-to-end.)

## Decisions

- **Thin wrapper, not a new executor** — chosen so JDBC adds a front door, not a second SQL
  implementation. Rejected: a JDBC-specific query path (would duplicate `queryImage`/
  `executeMutation` and drift).
- **Bound param → `SxLit` at the token level** — chosen because it is interpolation-free
  and full-fidelity across every storage class. Rejected: string interpolation (unsafe) and
  `?`→`num`/`str` token-splice (cannot carry real/blob/NULL).
- **Two lanes sharing method names and semantics** — chosen so `[int, js]` programs and JVM
  tools use the same API. Rejected: JVM-only (would exclude the portable backends that the
  rest of SclJet gates on).
- **Whole-image rewrite Connection model (A)** — chosen to match today's executor with
  minimal risk. Rejected for now: shared-pager page-staging (B) — a perf follow-up.
- **Distinct `jdbc:scljet:` scheme** — chosen so the mature `jdbc:sqlite:`/`sql.js`
  providers stay untouched. Rejected: reusing `jdbc:sqlite:` before parity gates.
- **Reference `sqlite3` + `sqlite-jdbc` as oracles only** — chosen to prove equality
  without a runtime dependency. Rejected: falling back to them at runtime (SclJet is a clean
  engine; no silent substitution).

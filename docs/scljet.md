# SclJet — a SQLite engine in pure ScalaScript

SclJet is a SQLite-compatible storage engine written entirely in ScalaScript — no C, no bundled
SQLite library. It reads and writes ordinary `.db` files, and the reference `sqlite3` reads those
files back, runs `PRAGMA integrity_check`, and writes into them. The proof is a byte-for-byte
differential against `sqlite3`, in both directions, through a real file.

- **Live overview:** <https://sergey-scherbina.github.io/scalascript/scljet.html>
- **Normative spec:** [`specs/scljet.md`](../specs/scljet.md) (engine),
  [`specs/scljet-jdbc.md`](../specs/scljet-jdbc.md) (JDBC),
  [`specs/scljet-address.md`](../specs/scljet-address.md) (addressing)
- **Runnable examples:** [`examples/scljet-hello.ssc`](../examples/scljet-hello.ssc),
  [`examples/scljet-file.ssc`](../examples/scljet-file.ssc)

SclJet is distinct from the `sqlite:` / `jdbc:sqlite:` providers documented in the
[user guide §6](user-guide.md#6-sql-databases): those wrap the mature Xerial `sqlite-jdbc` / `sql.js`
drivers. SclJet is a from-scratch engine you can read, run on any backend, and address at the level
of a single value. Both can be registered side by side.

---

## Quick start — a books database through the JDBC façade

The friendliest front door is `scljet/jdbc.ssc`, a portable façade that speaks the method names a
JDBC caller already knows. Imports are `[names](module)` markdown links; a connection is
**immutable**, so every write returns a new database image that you thread forward.

```scalascript
[SqlInteger, SqlText, buildTableDatabase](std/scljet/index.ssc)
[JdbcConnection, jdbcOpen, jdbcExecuteUpdate, jdbcExecuteQuery, rsNext, rsHasRow, rsGetLong, rsGetString](std/scljet/jdbc.ssc)

def run(c: JdbcConnection, sql: String): JdbcConnection =
  jdbcExecuteUpdate(c, sql) match
    case Right(u) => u.conn
    case Left(m)  => println("! " + m); c

buildTableDatabase(512, 1, 1, "books",
  "CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT)",
  List(List(SqlInteger(1L), SqlText("SICP")))) match
  case Left(e) => println(e.message)
  case Right(image) =>
    val c = run(jdbcOpen(image), "INSERT INTO books VALUES (2, 'TAPL')")
    jdbcExecuteQuery(c, "SELECT id, title FROM books ORDER BY id") match
      case Left(m)  => println(m)
      case Right(rs) =>
        var r = rsNext(rs)
        while rsHasRow(r) do
          println(rsGetLong(r, 1).toString + " · " + rsGetString(r, 2))
          r = rsNext(r)
```

```text
$ ssc run books.ssc
1 · SICP
2 · TAPL
```

Byte-identical on the interpreter, JS, and the default `ssc run`. `buildTableDatabase` writes a
complete, valid SQLite image to start from — use it rather than an empty database, because an empty
image leaves the text encoding unfixed until the first write.

Bound parameters keep values out of the SQL string entirely (they bind at the token level, so text
and blobs cannot break out of the query):

```scalascript
jdbcExecuteQueryParams(c, "SELECT title FROM books WHERE id > ?", List(SqlInteger(1L)))
```

## Two engines, one file — interop with real `sqlite3`

SclJet writes ordinary SQLite files. [`examples/scljet-file.ssc`](../examples/scljet-file.ssc)
writes a database to disk through the JVM VFS, then shells out to the reference `sqlite3` (via
`std.process`) to read it, check it, and write a row of its own:

```text
$ ssc-tools run --v1 scljet-file.ssc
SclJet wrote books.db
sqlite3 checks the file:
  ok
sqlite3 reads the rows SclJet wrote:
  1 · SICP
  2 · TAPL
sqlite3 adds a row of its own, then counts:
  now 3 books
```

The reverse direction works too: SclJet opens files written by `sqlite3` and returns the same rows,
byte-for-byte, across `SELECT` / `WHERE` / `GROUP BY` + aggregates / `ORDER BY` / `LIMIT` / joins.

## Every value has an address

The engine is the foundation; the idea it carries is addressing. An **address is the link between a
logical location and a physical one** — `emp/7/name` (table, row, column) on one side, the actual
bytes in the file on the other. The standing question is: *what does this bit mean, here?*

The sharpest case is an `INTEGER PRIMARY KEY`. Real SQLite stores nothing for it in the record — the
value lives in the rowid. So the two halves of the address genuinely disagree, and only the link is
correct:

```text
emp/7/id  →  logical:  7        (from the rowid)
             physical: 0 bytes  (the record field is NULL)
             fromRowid: true · stable: true
```

Reading and writing share one protocol: `read address → (type, value)`; a write is the same triple
applied. A write to an address that does not resolve **fails** rather than silently doing nothing —
an address names one specific cell. Types are the format's own where known, and `Raw(n)` (n bits)
where not; there is no universal type and no coercion. Stability is reported, never assumed: an
`INTEGER PRIMARY KEY` gives a durable address; a plain rowid is positional and a `VACUUM` may move it.
See [`specs/scljet-address.md`](../specs/scljet-address.md).

## What works today

| Capability | Status |
|---|---|
| Read real SQLite files | **solid** — byte-identical to `sqlite3` across SELECT, WHERE, GROUP BY, aggregates, ORDER BY, LIMIT, joins, indexes |
| Write valid SQLite files | **solid** — INSERT / UPDATE / DELETE / CREATE; `sqlite3` opens the result and passes `integrity_check` |
| JDBC | **solid** — a real `java.sql.Driver` for `jdbc:scljet:` plus the portable façade; cross-checked against Xerial `sqlite-jdbc` |
| Durable host-file writes | **solid** — crash-atomic (temp → fsync → atomic rename → fsync dir) and single-writer via a cross-process lock |
| Addressing | **solid** — read & write by `table/rowid/column`; read for JSON documents |
| Backends | **solid** — interpreter, JS, the JVM driver, and the default `ssc run` |
| WAL & rollback journal | **in progress** — codecs and transaction primitives exist; not yet the default write path |
| A few engine write edges | **in progress** — `UPDATE` of an IPK column, `NULL` in an `INSERT … VALUES` list; tracked in `BUGS.md` |
| Documents beyond JSON | **in progress** — YAML / XML / Markdown addressing, remote references |

## The three front doors

SclJet exposes the same engine three ways:

1. **JDBC façade** (`scljet/jdbc.ssc`) — the `Connection` / execute / `ResultSet` surface shown
   above; portable across every backend.
2. **JVM `java.sql.Driver`** (`runtime/std/scljet-jdbc-plugin`) — a real driver for
   `jdbc:scljet:<path>` URLs, so existing JVM tools and connection pools can talk to a SclJet file.
   See [`specs/scljet-jdbc.md`](../specs/scljet-jdbc.md).
3. **Typed SQL** (`scljet/typedsql.ssc`) — compile-checked `Column[T]` / `Expr[T]` builders. See
   [`specs/scljet-typed-sql.md`](../specs/scljet-typed-sql.md).

For the pure functional codec/pager API beneath all three (byte codecs, B-tree, pager, freelist,
schema), see [`specs/scljet.md`](../specs/scljet.md).

## Architecture

Twenty-two pure `.ssc` modules, semantics defined once and translated by each backend:

```
Front doors   jdbc · typedsql · address
SQL           sql (lexer · SELECT/INSERT/UPDATE/DELETE/CREATE · joins · aggregates · subqueries)
Mutation      write · mutate · journal · wal · freelist
Schema & read schema · readonly · btree · pager · page
Codec         record · header · values · bytes
VFS           vfs · memory-vfs · jvm-vfs
```

## Further reading

- [`specs/scljet.md`](../specs/scljet.md) — the normative engine spec (file format, VFS, pager,
  B-tree, transactions, SQL, compatibility gates).
- [`specs/scljet-jdbc.md`](../specs/scljet-jdbc.md) — the JDBC façade and JVM driver shim.
- [`specs/scljet-address.md`](../specs/scljet-address.md) — the addressing model.
- [`specs/scljet-typed-sql.md`](../specs/scljet-typed-sql.md) — the typed SQL surface.
- [`specs/scljet-standalone-library.md`](../specs/scljet-standalone-library.md) — using SclJet as a
  standalone library.

# PostgreSQL Client

General-purpose Postgres client for ScalaScript code.  Backed by
HikariCP + JDBC; queries run on a blocking thread pool wrapped in
`scala.concurrent.Future(blocking { ... })`.

> **Module:** `client-postgres/`, package `scalascript.db`.  This is a
> user-facing library for `scalascript` blocks that need a real Postgres
> connection.  For the `sql` *fenced block* form (SPEC § 3.3.1, v1.26),
> see [`backend-sql-runtime/`](../backend-sql-runtime/) which the
> `client-postgres` module depends on for its bind logic (v1.26.1
> reconciliation).

## Config

```scalascript
case class PgConfig(
  host:      String = "localhost",
  port:      Int    = 5432,
  database:  String,
  user:      String,
  password:  String,
  poolSize:  Int    = 10,
  fetchSize: Int    = 1000,  // rows per server-side cursor fetch (stream/foldLeft)
)
```

`PgConfig.jdbcUrl` derives `jdbc:postgresql://$host:$port/$database`.

## Row decoding

`RowDecoder[A]` decodes a `java.sql.ResultSet` row into `A` by *column
position* (1-based).  `ColumnDecoder[A]` decodes a single column.

```scalascript
trait ColumnDecoder[A]:
  def decode(rs: java.sql.ResultSet, index: Int): A

trait RowDecoder[A]:
  def decode(rs: java.sql.ResultSet): A

object RowDecoder:
  /** Generic single-column lift: any `ColumnDecoder[A]` in scope
   *  becomes `RowDecoder[A]` reading column 1. */
  given singleColumn[A](using ColumnDecoder[A]): RowDecoder[A]

  /** Tuple decoders (arity 2 + 3). */
  given [A, B](using ColumnDecoder[A], ColumnDecoder[B]): RowDecoder[(A, B)]
  given [A, B, C](using ColumnDecoder[A], ColumnDecoder[B], ColumnDecoder[C]): RowDecoder[(A, B, C)]

  /** Auto-derive for case classes by column position (1-based). */
  inline given derived[A](using Mirror.ProductOf[A]): RowDecoder[A]
```

Built-in `ColumnDecoder` givens (v1.26.1):

- Primitives: `String`, `Int`, `Long`, `Short`, `Byte`, `Double`,
  `Float`, `Boolean`.
- Numeric wide: `BigDecimal`, `BigInt`.
- Time: `java.time.LocalDate`, `LocalTime`, `LocalDateTime`,
  `Instant`, `OffsetDateTime`.  Drivers returning legacy
  `java.sql.{Date, Time, Timestamp}` are normalised to the
  corresponding `java.time.*` type so user code speaks one
  vocabulary.
- Other: `java.util.UUID`, `Array[Byte]`.
- `Option[A]` lift over any of the above — JDBC `wasNull()` drives
  `None` correctly (primitives map their default-for-NULL to `None`,
  not `Some(0)` / `Some(false)`).

## Client

```scalascript
trait PgClient:
  def query[A](sql: String, params: Any*)(using RowDecoder[A]): Future[List[A]]
  def queryOne[A](sql: String, params: Any*)(using RowDecoder[A]): Future[Option[A]]
  def execute(sql: String, params: Any*): Future[Int]               // affected rows
  def transaction[A](f: PgClient => Future[A]): Future[A]

  // cursor-based streaming (does not load the full result into memory)
  def stream[A](sql: String, params: Any*)(f: A => Unit)(using RowDecoder[A]): Future[Unit]
  def foldLeft[A, B](sql: String, params: Any*)(init: B)(f: (B, A) => B)(using RowDecoder[A]): Future[B]

  def close(): Unit

object PgClient:
  def connect(config: PgConfig)(using ExecutionContext): PgClient
  def connect(url: String, fetchSize: Int = 1000)(using ExecutionContext): PgClient
```

Parameter binding is delegated to
`scalascript.sql.Jdbc.bindAll` — the same logic used by `sql` fenced
blocks (v1.26.1 reconciliation).  Supported runtime types match the
`ColumnDecoder` matrix above plus any value the underlying JDBC driver
accepts via `setObject`.

`transaction { f }` runs `f` on a single connection with
`autoCommit = false`; commits on `Future.success`, rolls back on
`Future.failure`.

`stream` and `foldLeft` use JDBC `setFetchSize(fetchSize)` with
`TYPE_FORWARD_ONLY / CONCUR_READ_ONLY` cursor to avoid loading the full
result set into memory.  PostgreSQL requires `autoCommit = false` to
hold a server-side cursor; the client saves and restores the original
`autoCommit` state around each call.  Both methods are also available
inside `transaction { tx => tx.stream(...) }` (the cursor reuses the
existing transaction connection).

## Usage

```scalascript
import scala.concurrent.ExecutionContext.Implicits.global

val db = PgClient.connect(PgConfig(database = "mydb", user = "u", password = "p"))

case class User(id: Int, name: String, email: String)

val users: Future[List[User]] =
  db.query[User]("SELECT id, name, email FROM users WHERE active = ?", true)

val count: Future[Int] =
  db.execute("UPDATE users SET last_seen = ? WHERE id = ?",
             java.time.Instant.now(), userId)

db.transaction { tx =>
  for
    _ <- tx.execute("INSERT INTO orders VALUES (?, ?)", orderId, userId)
    _ <- tx.execute("UPDATE balance SET amount = amount - ? WHERE user_id = ?",
                    price, userId)
  yield ()
}

// cursor-based streaming (does not hold all rows in memory)
val total: Future[BigDecimal] =
  db.foldLeft[Order, BigDecimal]("SELECT * FROM orders WHERE user_id = ?", userId)(
    BigDecimal(0)) { (acc, order) => acc + order.amount }

db.stream[Event]("SELECT * FROM events ORDER BY created_at") { event =>
  println(event.id)
}
```

## Used by

- `x402-queue-postgres` — settlement queue.
- `x402-nonce-postgres` — nonce store (double-spend prevention).

## See also

- [`backend-sql-runtime/`](../backend-sql-runtime/) — the synchronous
  JDBC executor that powers `sql` fenced code blocks (SPEC § 3.3.1).
  v1.26.1 unified the bind-side type matrix between this client and
  the fenced-block runtime; both call `scalascript.sql.Jdbc.bindAll`.

# PostgreSQL Client

General-purpose async PostgreSQL client for ScalaScript.
Backed by JDBC + HikariCP connection pool; JDBC calls run on a blocking thread pool
wrapped in `Async.blocking`.

## Config

```scalascript
case class PgConfig(
  host:     String = "localhost",
  port:     Int    = 5432,
  database: String,
  user:     String,
  password: String,
  poolSize: Int    = 10,
)
```

## Row decoding

```scalascript
trait RowDecoder[A]:
  def decode(row: Row): A

object RowDecoder:
  given RowDecoder[Int]     = _.get[Int](0)
  given RowDecoder[Long]    = _.get[Long](0)
  given RowDecoder[String]  = _.get[String](0)
  given RowDecoder[Boolean] = _.get[Boolean](0)
  given RowDecoder[Double]  = _.get[Double](0)
  // Auto-derive for case classes via Scala 3 mirrors
  inline given derived[A]: RowDecoder[A] = ...
```

## Client

```scalascript
trait PgClient:
  def query[A : RowDecoder](sql: String, params: Any*): Async[List[A]]
  def queryOne[A : RowDecoder](sql: String, params: Any*): Async[Option[A]]
  def execute(sql: String, params: Any*): Async[Int]           // affected rows
  def transaction[A](f: PgClient => Async[A]): Async[A]
  def stream[A : RowDecoder](sql: String, params: Any*): AsyncStream[A]
  def close(): Async[Unit]

object Postgres:
  def connect(config: PgConfig): Async[PgClient]
  def connect(url: String): Async[PgClient]                    // JDBC URL
```

## Usage

```scalascript
val db = Postgres.connect(PgConfig(database = "mydb", user = "u", password = "p"))

case class User(id: Int, name: String, email: String)

val users = db.query[User]("SELECT id, name, email FROM users WHERE active = ?", true)
val count = db.execute("UPDATE users SET last_seen = now() WHERE id = ?", userId)

db.transaction { tx =>
  tx.execute("INSERT INTO orders VALUES (?, ?)", orderId, userId)
  tx.execute("UPDATE balance SET amount = amount - ? WHERE user_id = ?", price, userId)
}

db.stream[User]("SELECT * FROM users").foreach { user =>
  println(user.name)
}
```

## Used by

- `x402-queue-postgres` — settlement queue
- `x402-nonce-postgres` — nonce store (double-spend prevention)

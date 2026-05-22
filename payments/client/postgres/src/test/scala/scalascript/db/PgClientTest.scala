package scalascript.db

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

class PgClientTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  given ExecutionContext = ExecutionContext.global

  // H2 in Postgres compatibility mode via JDBC
  val db = PgClient.connect("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")

  def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 10.seconds)

  override def beforeAll(): Unit =
    await(db.execute("""
      CREATE TABLE IF NOT EXISTS users (
        id    INT PRIMARY KEY,
        name  VARCHAR(100),
        email VARCHAR(200),
        score DOUBLE PRECISION
      )
    """))
    await(db.execute("DELETE FROM users"))
    await(db.execute("INSERT INTO users VALUES (1, 'Alice', 'alice@example.com', 92.5)"))
    await(db.execute("INSERT INTO users VALUES (2, 'Bob',   'bob@example.com',   78.0)"))
    await(db.execute("INSERT INTO users VALUES (3, 'Carol', 'carol@example.com', 88.0)"))

  override def afterAll(): Unit = db.close()

  // ── Single-column queries ────────────────────────────────────

  test("query[String] returns single column"):
    val names = await(db.query[String]("SELECT name FROM users ORDER BY id"))
    names shouldBe List("Alice", "Bob", "Carol")

  test("query[Int] returns single column"):
    val ids = await(db.query[Int]("SELECT id FROM users ORDER BY id"))
    ids shouldBe List(1, 2, 3)

  test("queryOne returns Some when row exists"):
    val name = await(db.queryOne[String]("SELECT name FROM users WHERE id = ?", 1))
    name shouldBe Some("Alice")

  test("queryOne returns None when no row"):
    val name = await(db.queryOne[String]("SELECT name FROM users WHERE id = ?", 999))
    name shouldBe None

  // ── Case class derivation ────────────────────────────────────

  case class User(id: Int, name: String, email: String) derives RowDecoder

  test("query[CaseClass] decodes all columns by position"):
    val users = await(db.query[User]("SELECT id, name, email FROM users ORDER BY id"))
    users shouldBe List(
      User(1, "Alice", "alice@example.com"),
      User(2, "Bob",   "bob@example.com"),
      User(3, "Carol", "carol@example.com"),
    )

  test("queryOne[CaseClass] returns Some"):
    val user = await(db.queryOne[User]("SELECT id, name, email FROM users WHERE id = ?", 2))
    user shouldBe Some(User(2, "Bob", "bob@example.com"))

  // ── Tuple decoder ────────────────────────────────────────────

  test("query[(Int, String)] decodes two columns"):
    val rows = await(db.query[(Int, String)]("SELECT id, name FROM users ORDER BY id"))
    rows shouldBe List((1, "Alice"), (2, "Bob"), (3, "Carol"))

  // ── Execute + params ─────────────────────────────────────────

  test("execute with params returns affected rows"):
    val n = await(db.execute("UPDATE users SET score = ? WHERE id = ?", 99.0, 1))
    n shouldBe 1

  test("execute with no matching rows returns 0"):
    val n = await(db.execute("UPDATE users SET score = 0 WHERE id = ?", 999))
    n shouldBe 0

  // ── Transaction ──────────────────────────────────────────────

  test("transaction commits on success"):
    await(db.transaction { tx =>
      tx.execute("INSERT INTO users VALUES (10, 'Dave', 'dave@example.com', 70.0)")
        .flatMap(_ => tx.execute("UPDATE users SET score = 71.0 WHERE id = ?", 10))
    })
    val user = await(db.queryOne[User]("SELECT id, name, email FROM users WHERE id = ?", 10))
    user.map(_.name) shouldBe Some("Dave")

  test("transaction rolls back on failure"):
    val result = await(
      db.transaction { tx =>
        tx.execute("INSERT INTO users VALUES (20, 'Eve', 'eve@example.com', 80.0)")
          .flatMap(_ => scala.concurrent.Future.failed(RuntimeException("boom")))
      }.recover { case _ => -1 }
    )
    result shouldBe -1
    val after = await(db.query[Int]("SELECT id FROM users WHERE id = ?", 20))
    after shouldBe Nil

  // ── v1.26.1 reconciliation: expanded type coverage ──────────────────

  import java.time.{Instant, LocalDate}
  import java.util.UUID

  test("v1.26.1 — LocalDate bind + decode round-trip"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS dates (id INT PRIMARY KEY, d DATE)"))
    await(db.execute("DELETE FROM dates"))
    val d = LocalDate.of(2026, 5, 19)
    await(db.execute("INSERT INTO dates VALUES (?, ?)", 1, d))
    val out = await(db.queryOne[LocalDate]("SELECT d FROM dates WHERE id = ?", 1))
    assert(out == Some(d))

  test("v1.26.1 — Instant bind + decode via TIMESTAMP WITH TIME ZONE"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS events (id INT PRIMARY KEY, occurred_at TIMESTAMP WITH TIME ZONE)"))
    await(db.execute("DELETE FROM events"))
    val t = Instant.parse("2026-05-19T12:34:56Z")
    await(db.execute("INSERT INTO events VALUES (?, ?)", 1, t))
    val out = await(db.queryOne[Instant]("SELECT occurred_at FROM events WHERE id = ?", 1))
    assert(out == Some(t))

  test("v1.26.1 — UUID bind + decode round-trip"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS sessions (id UUID PRIMARY KEY, user_id INT)"))
    await(db.execute("DELETE FROM sessions"))
    val sid = UUID.fromString("9e8a4b3c-1234-4abc-9def-abcdef012345")
    await(db.execute("INSERT INTO sessions VALUES (?, ?)", sid, 42))
    val out = await(db.queryOne[UUID]("SELECT id FROM sessions WHERE user_id = ?", 42))
    assert(out == Some(sid))

  test("v1.26.1 — Option[LocalDate] decodes NULL → None"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS opt_dates (id INT PRIMARY KEY, d DATE)"))
    await(db.execute("DELETE FROM opt_dates"))
    await(db.execute("INSERT INTO opt_dates VALUES (1, NULL)"))
    val out = await(db.queryOne[Option[LocalDate]](
      "SELECT d FROM opt_dates WHERE id = ?", 1))
    assert(out == Some(None))

  test("v1.26.1 — Array[Byte] bind + decode"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS blobs (id INT PRIMARY KEY, data VARBINARY(255))"))
    await(db.execute("DELETE FROM blobs"))
    val bytes = Array[Byte](1, 2, 3, 0, -1, 127, -128)
    await(db.execute("INSERT INTO blobs VALUES (?, ?)", 1, bytes))
    val out = await(db.queryOne[Array[Byte]]("SELECT data FROM blobs WHERE id = ?", 1))
    assert(out.map(_.toList) == Some(bytes.toList))

  // ── v1.26.1 reconciliation: tx-path bind consistency fix ───────────

  test("v1.26.1 — transaction bind path handles typed values (was buggy: Some(x) was setObject'd as Some)"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS tx_typed (id INT PRIMARY KEY, name VARCHAR(20), active BOOLEAN)"))
    await(db.execute("DELETE FROM tx_typed"))
    val result = await(db.transaction { tx =>
      tx.execute("INSERT INTO tx_typed VALUES (?, ?, ?)",
        Some(99), Some("Tx"), Some(true))
        .flatMap(_ => tx.queryOne[String]("SELECT name FROM tx_typed WHERE id = ?", 99))
    })
    assert(result == Some("Tx"))

  test("v1.26.1 — transaction bind path handles LocalDate (full type matrix shared with pooled client)"):
    await(db.execute(
      "CREATE TABLE IF NOT EXISTS tx_dates (id INT PRIMARY KEY, d DATE)"))
    await(db.execute("DELETE FROM tx_dates"))
    val d = LocalDate.of(2026, 6, 1)
    val result = await(db.transaction { tx =>
      tx.execute("INSERT INTO tx_dates VALUES (?, ?)", 1, d)
        .flatMap(_ => tx.queryOne[LocalDate]("SELECT d FROM tx_dates WHERE id = ?", 1))
    })
    assert(result == Some(d))

  // ── stream + foldLeft ─────────────────────────────────────────

  test("stream delivers all rows via callback"):
    val buf = List.newBuilder[String]
    await(db.stream[String]("SELECT name FROM users WHERE id <= 3 ORDER BY id") { name =>
      buf += name
    })
    buf.result() shouldBe List("Alice", "Bob", "Carol")

  test("stream respects WHERE params"):
    val buf = List.newBuilder[Int]
    await(db.stream[Int]("SELECT id FROM users WHERE id > ? AND id <= 3", 1) { id =>
      buf += id
    })
    buf.result() shouldBe List(2, 3)

  test("stream works with case class RowDecoder"):
    val buf = List.newBuilder[User]
    await(db.stream[User]("SELECT id, name, email FROM users WHERE id <= 3 ORDER BY id") { u =>
      buf += u
    })
    buf.result() shouldBe List(
      User(1, "Alice", "alice@example.com"),
      User(2, "Bob",   "bob@example.com"),
      User(3, "Carol", "carol@example.com"),
    )

  test("stream inside transaction"):
    val buf = List.newBuilder[String]
    await(db.transaction { tx =>
      tx.stream[String]("SELECT name FROM users WHERE id <= 3 ORDER BY id") { name =>
        buf += name
      }
    })
    buf.result() shouldBe List("Alice", "Bob", "Carol")

  test("foldLeft sums values"):
    val total = await(db.foldLeft[Int, Int]("SELECT id FROM users WHERE id <= 3")(0) { (acc, id) =>
      acc + id
    })
    total shouldBe 6  // 1 + 2 + 3

  test("foldLeft builds a list in order"):
    val names = await(db.foldLeft[String, List[String]](
      "SELECT name FROM users WHERE id <= 3 ORDER BY id")(Nil) { (acc, name) =>
      acc :+ name
    })
    names shouldBe List("Alice", "Bob", "Carol")

  test("foldLeft with params"):
    val count = await(db.foldLeft[Int, Int]("SELECT id FROM users WHERE id >= ? AND id <= 3", 2)(0) { (acc, _) =>
      acc + 1
    })
    count shouldBe 2

  test("foldLeft inside transaction"):
    val sum = await(db.transaction { tx =>
      tx.foldLeft[Int, Int]("SELECT id FROM users WHERE id <= 3")(0) { (acc, id) => acc + id }
    })
    sum shouldBe 6

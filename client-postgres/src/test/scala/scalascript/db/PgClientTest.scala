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

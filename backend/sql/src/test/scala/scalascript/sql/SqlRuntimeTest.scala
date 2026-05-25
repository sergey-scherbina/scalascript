package scalascript.sql

import java.sql.{Connection, DriverManager}
import java.time.{LocalDate, Instant}
import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import scalascript.typeddata.{RowCodec, RowFieldSpec, RowValue, RowValueCodec}

/** v1.26 Phase 4 — end-to-end JDBC executor coverage.
 *
 *  Uses H2 in-memory in `DB_CLOSE_DELAY=-1` mode so every test in this
 *  suite shares the same logical database (lifetime = JVM) — required
 *  because each test acquires its own connection and we want CREATE
 *  TABLE / INSERT effects to persist across connections within the
 *  suite. */
class SqlRuntimeTest extends AnyFunSuite with BeforeAndAfterAll:

  // Unique URL per suite instance — avoids state bleeding between
  // re-runs in the same JVM under `sbt ~test`.
  private val dbName = s"sqlruntime-${UUID.randomUUID().toString.take(8)}"
  private val url    = s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"

  // Driver must be on the classpath; `Class.forName` is belt-and-braces
  // for environments where the JDBC SPI service loader skips H2.
  Class.forName("org.h2.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(url)
    try f(c) finally c.close()

  override def beforeAll(): Unit =
    withConn { c =>
      SqlRuntime.execute(c,
        """CREATE TABLE users (
          |  id    BIGINT PRIMARY KEY,
          |  name  VARCHAR(120) NOT NULL,
          |  email VARCHAR(255),
          |  active BOOLEAN NOT NULL,
          |  score DOUBLE PRECISION,
          |  joined DATE NOT NULL
          |)""".stripMargin,
        Nil)
      SqlRuntime.execute(c,
        """CREATE TABLE typed_users (
          |  id     BIGINT PRIMARY KEY,
          |  name   VARCHAR(120) NOT NULL,
          |  email  VARCHAR(255),
          |  active BOOLEAN NOT NULL
          |)""".stripMargin,
        Nil)
      SqlRuntime.execute(c,
        """CREATE TABLE schema_users (
          |  id           BIGINT PRIMARY KEY,
          |  display_name VARCHAR(120) NOT NULL,
          |  active       BOOLEAN NOT NULL
          |)""".stripMargin,
        Nil)
      ()
    }

  // ── Statement-class detection ────────────────────────────────────────

  test("isResultSetProducer: SELECT / WITH / VALUES / SHOW / EXPLAIN → true") {
    assert(SqlRuntime.isResultSetProducer("SELECT 1"))
    assert(SqlRuntime.isResultSetProducer("  select count(*) from t"))
    assert(SqlRuntime.isResultSetProducer("WITH cte AS (SELECT 1) SELECT * FROM cte"))
    assert(SqlRuntime.isResultSetProducer("VALUES (1)"))
    assert(SqlRuntime.isResultSetProducer("SHOW TABLES"))
    assert(SqlRuntime.isResultSetProducer("EXPLAIN SELECT 1"))
  }

  test("isResultSetProducer: INSERT / UPDATE / DELETE / DDL → false") {
    assert(!SqlRuntime.isResultSetProducer("INSERT INTO t VALUES (1)"))
    assert(!SqlRuntime.isResultSetProducer("UPDATE t SET x = 1"))
    assert(!SqlRuntime.isResultSetProducer("DELETE FROM t"))
    assert(!SqlRuntime.isResultSetProducer("CREATE TABLE t (x INT)"))
    assert(!SqlRuntime.isResultSetProducer("DROP TABLE t"))
    assert(!SqlRuntime.isResultSetProducer("ALTER TABLE t ADD COLUMN y INT"))
    assert(!SqlRuntime.isResultSetProducer("TRUNCATE TABLE t"))
  }

  // ── DDL / DML ────────────────────────────────────────────────────────

  test("INSERT with binds → UpdateCount(1)") {
    withConn { c =>
      val r = SqlRuntime.execute(c,
        "INSERT INTO users (id, name, email, active, score, joined) " +
          "VALUES (?, ?, ?, ?, ?, ?)",
        List(1L, "Alice", "alice@example.com", true, 95.5, LocalDate.of(2025, 1, 15)))
      assert(r == SqlResult.UpdateCount(1))
    }
  }

  test("INSERT with NULL email (None) succeeds") {
    withConn { c =>
      val r = SqlRuntime.execute(c,
        "INSERT INTO users (id, name, email, active, score, joined) " +
          "VALUES (?, ?, ?, ?, ?, ?)",
        List(2L, "Bob", None, false, 0.0, LocalDate.of(2025, 2, 1)))
      assert(r == SqlResult.UpdateCount(1))
    }
  }

  // ── SELECT ───────────────────────────────────────────────────────────

  test("SELECT with one bind returns Rows with the matching row") {
    withConn { c =>
      val r = SqlRuntime.execute(c,
        "SELECT id, name, email FROM users WHERE id = ?",
        List(1L))
      r match
        case SqlResult.Rows(rows) =>
          assert(rows.size == 1)
          val row = rows.head
          assert(row(0) == 1L)
          assert(row("NAME") == "Alice")
          assert(row("name") == "Alice", "name lookup must be case-insensitive")
          assert(row("EMAIL") == "alice@example.com")
        case other => fail(s"expected Rows(_), got $other")
    }
  }

  test("Row positional access raises on out-of-bounds") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c, "SELECT id FROM users WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      val ex = intercept[RowProjectionError](rows.head(5))
      assert(ex.getMessage.contains("out of bounds"))
    }
  }

  test("Row name access raises on missing column") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c, "SELECT id FROM users WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      val ex = intercept[RowProjectionError](rows.head("nope"))
      assert(ex.getMessage.contains("no column `nope`"))
    }
  }

  // ── .as[CaseClass] projection ────────────────────────────────────────

  case class UserSummary(id: Long, name: String, email: Option[String])

  test("Row.as[CaseClass] — happy path with one Some + one None") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c,
          "SELECT id, name, email FROM users ORDER BY id",
          Nil)
        .asInstanceOf[SqlResult.Rows].rows
      val users = rows.map(_.as[UserSummary])
      assert(users == Seq(
        UserSummary(1L, "Alice", Some("alice@example.com")),
        UserSummary(2L, "Bob",   None)
      ))
    }
  }

  case class TypedUserSummary(id: Long, name: String, email: Option[String], active: Boolean) derives RowCodec

  case class SchemaUserSummary(id: Long, displayName: String, active: Boolean)

  object SchemaUserSummary:
    private val idColumn = RowFieldSpec.key[Long]("id")
    private val displayNameColumn = RowFieldSpec.required[String]("display_name", "name")
    private val activeColumn = RowFieldSpec.withDefault[Boolean]("active", true)

    given RowCodec[SchemaUserSummary] = RowCodec.objectCodec(
      user => Map(
        idColumn.name -> RowValueCodec[Long].encode(user.id),
        displayNameColumn.name -> RowValueCodec[String].encode(user.displayName),
        activeColumn.name -> RowValueCodec[Boolean].encode(user.active)
      ),
      row =>
        for
          id <- RowCodec.field(row, idColumn)
          displayName <- RowCodec.field(row, displayNameColumn)
          active <- RowCodec.field(row, activeColumn)
        yield SchemaUserSummary(id, displayName, active),
      fields = List(idColumn, displayNameColumn, activeColumn),
      rejectUnknown = true
    )

  test("SqlRuntime.query[A] decodes rows through RowCodec") {
    withConn { c =>
      val users = SqlRuntime.query[TypedUserSummary](c,
        "SELECT id AS id, name AS name, email AS email, active AS active FROM users ORDER BY id",
        Nil)
      assert(users == Vector(
        TypedUserSummary(1L, "Alice", Some("alice@example.com"), active = true),
        TypedUserSummary(2L, "Bob", None, active = false)
      ))
    }
  }

  test("SqlRuntime.query[A] reports RowCodec decode errors") {
    withConn { c =>
      val ex = intercept[RowProjectionError] {
        SqlRuntime.query[TypedUserSummary](c,
          "SELECT id AS id, name AS name, email AS email FROM users WHERE id = ?",
          List(1L))
      }
      assert(ex.getMessage == "$.active: missing column 'active'")
    }
  }

  test("SqlRuntime.query[A] honors RowFieldSpec aliases and defaults") {
    withConn { c =>
      val users = SqlRuntime.query[SchemaUserSummary](c,
        "SELECT id AS id, name AS name FROM users WHERE id = ?",
        List(1L))
      assert(users == Vector(SchemaUserSummary(1L, "Alice", active = true)))
    }
  }

  test("SqlRuntime.insert[A] uses canonical RowFieldSpec column names") {
    withConn { c =>
      val count = SqlRuntime.insert(c, "schema_users",
        SchemaUserSummary(31L, "Canonical", active = false))
      assert(count == 1)

      val rows = SqlRuntime
        .execute(c, "SELECT id, display_name, active FROM schema_users WHERE id = ?", List(31L))
        .asInstanceOf[SqlResult.Rows].rows
      assert(rows.head("DISPLAY_NAME") == "Canonical")
      assert(rows.head.toRowValueMap("display_name") == RowValue.Str("Canonical"))
    }
  }

  test("SqlRuntime.insert[A] inserts encoded RowCodec values") {
    withConn { c =>
      val count = SqlRuntime.insert(c, "typed_users",
        TypedUserSummary(3L, "Carol", Some("carol@example.com"), active = true))
      assert(count == 1)

      val users = SqlRuntime.query[TypedUserSummary](c,
        "SELECT id AS id, name AS name, email AS email, active AS active FROM typed_users WHERE id = ?",
        List(3L))
      assert(users == Vector(TypedUserSummary(3L, "Carol", Some("carol@example.com"), active = true)))
    }
  }

  test("SqlRuntime.update[A] updates encoded RowCodec values and keeps key out of SET") {
    withConn { c =>
      SqlRuntime.insert(c, "typed_users",
        TypedUserSummary(30L, "Before", Some("before@example.com"), active = true))

      val count = SqlRuntime.update(c, "typed_users", "id", 30L,
        TypedUserSummary(30L, "After", None, active = false))
      assert(count == 1)

      val users = SqlRuntime.query[TypedUserSummary](c,
        "SELECT id AS id, name AS name, email AS email, active AS active FROM typed_users WHERE id = ?",
        List(30L))
      assert(users == Vector(TypedUserSummary(30L, "After", None, active = false)))
    }
  }

  test("typed write helpers reject invalid SQL identifiers") {
    withConn { c =>
      val badTable = intercept[IllegalArgumentException] {
        SqlRuntime.insert(c, "users; DROP TABLE users", TypedUserSummary(4L, "Mallory", None, active = true))
      }
      assert(badTable.getMessage.contains("invalid SQL table identifier"))

      val badColumn = intercept[IllegalArgumentException] {
        SqlRuntime.update(c, "users", "id = id OR 1", 4L,
          TypedUserSummary(4L, "Mallory", None, active = true))
      }
      assert(badColumn.getMessage.contains("invalid SQL key column identifier"))
    }
  }

  case class WithBadField(id: Long, doesNotExist: String)

  test("Row.as[CaseClass] — missing column names the offending field") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c, "SELECT id FROM users WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      val ex = intercept[RowProjectionError](rows.head.as[WithBadField])
      assert(ex.getMessage.contains("doesNotExist"))
    }
  }

  case class WithNullClash(id: Long, email: String)

  test("Row.as[CaseClass] — NULL into non-Option field surfaces a clear error") {
    withConn { c =>
      // Bob's email is NULL.
      val rows = SqlRuntime
        .execute(c, "SELECT id, email FROM users WHERE id = ?", List(2L))
        .asInstanceOf[SqlResult.Rows].rows
      val ex = intercept[RowProjectionError](rows.head.as[WithNullClash])
      assert(ex.getMessage.contains("email"))
      assert(ex.getMessage.contains("Option"), s"got: ${ex.getMessage}")
    }
  }

  // ── Time-typed binds round-trip ──────────────────────────────────────

  test("LocalDate bind survives JDBC round-trip") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c, "SELECT joined FROM users WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      val d = rows.head(0)
      assert(d == LocalDate.of(2025, 1, 15),
        s"expected LocalDate(2025-01-15), got $d (${d.getClass.getName})")
    }
  }

  test("Instant bind survives JDBC round-trip via TIMESTAMP WITH TIME ZONE") {
    withConn { c =>
      SqlRuntime.execute(c,
        "CREATE TABLE events (id BIGINT PRIMARY KEY, occurred_at TIMESTAMP WITH TIME ZONE)",
        Nil)
      val now = Instant.parse("2026-05-19T12:34:56Z")
      SqlRuntime.execute(c,
        "INSERT INTO events VALUES (?, ?)",
        List(1L, now))
      val rows = SqlRuntime
        .execute(c, "SELECT occurred_at FROM events WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      // Drivers return OffsetDateTime for TIMESTAMP WITH TIME ZONE.
      val back = rows.head(0)
      assert(back.toString.startsWith("2026-05-19"),
        s"expected round-tripped Instant, got $back (${back.getClass.getName})")
    }
  }

  // ── Multiple Rows ────────────────────────────────────────────────────

  test("multi-row SELECT preserves declared order") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c,
          "SELECT id FROM users ORDER BY id",
          Nil)
        .asInstanceOf[SqlResult.Rows].rows
      assert(rows.map(_(0)) == Seq(1L, 2L))
    }
  }

  // ── No-bind statements still go through the same path ───────────────

  test("SELECT with no binds works (empty bind list)") {
    withConn { c =>
      val r = SqlRuntime.execute(c, "SELECT count(*) FROM users", Nil)
      r match
        case SqlResult.Rows(rows) =>
          assert(rows.head(0).asInstanceOf[Number].longValue == 2L)
        case other => fail(s"got $other")
    }
  }

  // ── toMap convenience ────────────────────────────────────────────────

  // ── SQLite — second bundled driver must load and round-trip ─────────

  test("SQLite (in-memory) driver loads and round-trips a simple SELECT") {
    Class.forName("org.sqlite.JDBC")
    val sc = DriverManager.getConnection("jdbc:sqlite::memory:")
    try
      SqlRuntime.execute(sc,
        "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
        Nil)
      SqlRuntime.execute(sc, "INSERT INTO t VALUES (?, ?)", List(1, "ok"))
      val rows = SqlRuntime
        .execute(sc, "SELECT name FROM t WHERE id = ?", List(1))
        .asInstanceOf[SqlResult.Rows].rows
      assert(rows.size == 1)
      assert(rows.head(0) == "ok")
    finally sc.close()
  }

  test("Row.toMap exposes columns keyed by JDBC column label") {
    withConn { c =>
      val rows = SqlRuntime
        .execute(c, "SELECT id, name FROM users WHERE id = ?", List(1L))
        .asInstanceOf[SqlResult.Rows].rows
      val m = rows.head.toMap
      // H2 returns UPPER-CASE labels for unquoted identifiers.
      assert(m.keys.exists(_.equalsIgnoreCase("id")))
      assert(m.keys.exists(_.equalsIgnoreCase("name")))
    }
  }

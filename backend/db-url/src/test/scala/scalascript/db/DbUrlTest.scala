package scalascript.db

import org.scalatest.funsuite.AnyFunSuite

class DbUrlTest extends AnyFunSuite:

  // ── toJdbc ──────────────────────────────────────────────────────────────

  test("sqlite: → jdbc:sqlite:"):
    assert(DbUrl.toJdbc("sqlite:./todos.db") == "jdbc:sqlite:./todos.db")

  test("sqlite::memory: → jdbc:sqlite::memory:"):
    assert(DbUrl.toJdbc("sqlite::memory:") == "jdbc:sqlite::memory:")

  test("sqlite-opfs: → jdbc:sqlite: (JVM uses regular file-backed SQLite)"):
    assert(DbUrl.toJdbc("sqlite-opfs:./mydb.db") == "jdbc:sqlite:./mydb.db")
    assert(DbUrl.toJdbc("sqlite-opfs::memory:") == "jdbc:sqlite::memory:")

  test("duckdb: → jdbc:duckdb:"):
    assert(DbUrl.toJdbc("duckdb:") == "jdbc:duckdb:")

  test("h2:mem:test → jdbc:h2:mem:test"):
    assert(DbUrl.toJdbc("h2:mem:test") == "jdbc:h2:mem:test")

  test("postgres://host/db → jdbc:postgresql://host/db"):
    assert(DbUrl.toJdbc("postgres://localhost/mydb") == "jdbc:postgresql://localhost/mydb")

  test("mysql://host/db → jdbc:mysql://host/db"):
    assert(DbUrl.toJdbc("mysql://localhost/mydb") == "jdbc:mysql://localhost/mydb")

  test("mssql://host → jdbc:sqlserver://host"):
    assert(DbUrl.toJdbc("mssql://localhost") == "jdbc:sqlserver://localhost")

  test("already jdbc: passes through unchanged"):
    assert(DbUrl.toJdbc("jdbc:sqlite:./foo.db") == "jdbc:sqlite:./foo.db")
    assert(DbUrl.toJdbc("jdbc:h2:mem:") == "jdbc:h2:mem:")

  test("unknown scheme passes through unchanged"):
    assert(DbUrl.toJdbc("mongodb://localhost") == "mongodb://localhost")

  // ── isJsSupported ───────────────────────────────────────────────────────

  test("sqlite: is JS-supported"):
    assert(DbUrl.isJsSupported("sqlite:./todos.db"))

  test("sqlite-opfs: is JS-supported"):
    assert(DbUrl.isJsSupported("sqlite-opfs:./mydb.db"))

  test("duckdb: is JS-supported"):
    assert(DbUrl.isJsSupported("duckdb:"))

  test("h2: is NOT JS-supported"):
    assert(!DbUrl.isJsSupported("h2:mem:test"))

  test("postgres: is NOT JS-supported"):
    assert(!DbUrl.isJsSupported("postgres://localhost/db"))

  test("jdbc: URL is NOT JS-supported"):
    assert(!DbUrl.isJsSupported("jdbc:sqlite:./foo.db"))

  // ── toNative ────────────────────────────────────────────────────────────

  test("toNative: sqlite: stays sqlite:"):
    assert(DbUrl.toNative("sqlite:./todos.db") == "sqlite:./todos.db")

  test("toNative: duckdb: stays duckdb:"):
    assert(DbUrl.toNative("duckdb:") == "duckdb:")

  test("toNative: h2: returns unchanged (no JS form)"):
    assert(DbUrl.toNative("h2:mem:test") == "h2:mem:test")

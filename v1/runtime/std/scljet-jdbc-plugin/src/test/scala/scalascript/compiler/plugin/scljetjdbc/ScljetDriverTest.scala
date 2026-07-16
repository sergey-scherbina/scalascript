package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, ResultSet, SQLException, SQLFeatureNotSupportedException, Statement, Types}

/** JVM `java.sql.Driver` shim — end-to-end through `DriverManager`.
 *
 *  Proves the JVM↔engine bridge: `DriverManager.getConnection("jdbc:scljet:…")`
 *  runs CREATE/INSERT/SELECT/prepared/txn against the pure scljet engine and the
 *  `ResultSet` getters map values exactly like the engine (asserted against
 *  literals, plus cross-checked against the reference `org.xerial:sqlite-jdbc`). */
class ScljetDriverTest extends AnyFunSuite:

  // Force driver registration.  `Class.forName("…ScljetDriver")` does NOT do it:
  // it initializes the CLASS, while `DriverManager.registerDriver` lives in the
  // companion OBJECT (`ScljetDriver$`), whose initializer only runs when the
  // object is touched.  Under sbt the ServiceLoader scan (META-INF/services)
  // does not see the plugin either, since DriverManager scans the thread-context
  // classloader once at its own init.  The suite used to register the driver by
  // accident — the first test happens to call `new ScljetDriver().acceptsURL`,
  // which reads `ScljetDriver.Prefix` and thus inits the object — so running any
  // single test (`testOnly … -- -z "…"`) failed with "No suitable driver found".
  // Touch the object explicitly so every test is order-independent.
  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private def memConn(): Connection = DriverManager.getConnection("jdbc:scljet::memory:")

  private def seed(c: Connection): Unit =
    val s = c.createStatement()
    s.executeUpdate("CREATE TABLE emp(id INTEGER, name TEXT, salary INTEGER, bonus REAL)")
    s.executeUpdate("INSERT INTO emp VALUES (1,'ann',100,1.5)")
    s.executeUpdate("INSERT INTO emp VALUES (2,'bob',250,2.5)")
    s.executeUpdate("INSERT INTO emp VALUES (3,'cat',300,3.5)")
    s.close()

  test("driver accepts jdbc:scljet: URLs and only those"):
    val d = new ScljetDriver
    assert(d.acceptsURL("jdbc:scljet::memory:"))
    assert(d.acceptsURL("jdbc:scljet:/tmp/x.db"))
    assert(!d.acceptsURL("jdbc:sqlite:/tmp/x.db"))
    assert(!d.acceptsURL("jdbc:postgresql://h/db"))
    assert(d.connect("jdbc:sqlite:/tmp/x.db", new java.util.Properties()) == null)
    assert(!d.jdbcCompliant())

  test("DriverManager resolves the scljet driver for a jdbc:scljet: URL"):
    val d = DriverManager.getDriver("jdbc:scljet::memory:")
    assert(d.isInstanceOf[ScljetDriver], s"expected ScljetDriver, got ${d.getClass.getName}")

  test(":memory: CREATE / INSERT / SELECT with typed getters"):
    val c = memConn()
    try
      seed(c)
      val rs = c.createStatement().executeQuery("SELECT id, name, salary, bonus FROM emp ORDER BY id")
      val rows = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do
        rows += s"${rs.getLong(1)}|${rs.getString(2)}|${rs.getInt("salary")}|${rs.getDouble(4)}"
      assert(rows.toList == List("1|ann|100|1.5", "2|bob|250|2.5", "3|cat|300|3.5"))
    finally c.close()

  test("executeUpdate returns the affected-row count and last rowid"):
    val c = memConn()
    try
      seed(c)
      val s = c.createStatement()
      assert(s.executeUpdate("INSERT INTO emp VALUES (4,'dan',150,4.5)") == 1)
      assert(s.executeUpdate("UPDATE emp SET salary = 999 WHERE id = 1") == 1)
      assert(s.executeUpdate("DELETE FROM emp WHERE id = 2") == 1)
      val rs = s.executeQuery("SELECT salary FROM emp WHERE id = 1")
      assert(rs.next()); assert(rs.getLong(1) == 999L)
    finally c.close()

  test("PreparedStatement binds ? parameters (no string interpolation)"):
    val c = memConn()
    try
      seed(c)
      val ps = c.prepareStatement("SELECT name, salary FROM emp WHERE id = ?")
      ps.setInt(1, 2)
      val rs = ps.executeQuery()
      assert(rs.next())
      assert(rs.getString("name") == "bob")
      assert(rs.getLong("salary") == 250L)
      // Re-bind and re-run.
      val ps2 = c.prepareStatement("UPDATE emp SET salary = ? WHERE id = ?")
      ps2.setLong(1, 777L); ps2.setInt(2, 3)
      assert(ps2.executeUpdate() == 1)
      val chk = c.prepareStatement("SELECT salary FROM emp WHERE id = ?")
      chk.setInt(1, 3)
      val rs2 = chk.executeQuery()
      assert(rs2.next()); assert(rs2.getLong(1) == 777L)
    finally c.close()

  test("unset prepared parameter is a strict error"):
    val c = memConn()
    try
      seed(c)
      val ps = c.prepareStatement("SELECT * FROM emp WHERE id = ?")
      val ex = intercept[SQLException](ps.executeQuery())
      assert(ex.getMessage.contains("parameter 1 is not set"))
    finally c.close()

  test("NULL columns: wasNull + zero-defaults + null string"):
    val c = memConn()
    try
      c.createStatement().executeUpdate("CREATE TABLE t(id INTEGER, name TEXT, salary INTEGER, bonus REAL)")
      c.createStatement().executeUpdate("INSERT INTO t(id) VALUES (5)")
      val rs = c.createStatement().executeQuery("SELECT id, name, salary, bonus FROM t WHERE id = 5")
      assert(rs.next())
      assert(rs.getLong(1) == 5L)
      assert(rs.getString(2) == null)
      assert(rs.wasNull())
      assert(rs.getLong(3) == 0L)
      assert(rs.wasNull())
      assert(rs.getDouble(4) == 0.0)
      assert(rs.wasNull())
    finally c.close()

  test("ResultSetMetaData: column count, labels, and types"):
    val c = memConn()
    try
      seed(c)
      val rs = c.createStatement().executeQuery("SELECT id, name, bonus FROM emp ORDER BY id")
      val md = rs.getMetaData
      assert(md.getColumnCount == 3)
      assert(md.getColumnLabel(1) == "id")
      assert(md.getColumnLabel(2) == "name")
      assert(md.getColumnType(1) == Types.BIGINT)
      assert(md.getColumnType(2) == Types.VARCHAR)
      assert(md.getColumnType(3) == Types.DOUBLE)
      assert(md.getColumnTypeName(2) == "TEXT")
    finally c.close()

  test("transactions: rollback reverts, commit persists, read-your-writes"):
    val c = memConn()
    try
      seed(c)
      c.setAutoCommit(false)
      c.createStatement().executeUpdate("UPDATE emp SET salary = 5000 WHERE id = 1")
      // read-your-writes inside the open transaction
      val rs1 = c.createStatement().executeQuery("SELECT salary FROM emp WHERE id = 1")
      assert(rs1.next()); assert(rs1.getLong(1) == 5000L)
      c.rollback()
      val rs2 = c.createStatement().executeQuery("SELECT salary FROM emp WHERE id = 1")
      assert(rs2.next()); assert(rs2.getLong(1) == 100L)   // reverted
      // now commit a change
      c.createStatement().executeUpdate("UPDATE emp SET salary = 6000 WHERE id = 1")
      c.commit()
      val rs3 = c.createStatement().executeQuery("SELECT salary FROM emp WHERE id = 1")
      assert(rs3.next()); assert(rs3.getLong(1) == 6000L)
      c.setAutoCommit(true)
    finally c.close()

  test("host file: create-on-open, write, close, reopen persists"):
    val dir: Path = Files.createTempDirectory("scljet-jdbc-")
    val db = dir.resolve("app.db")
    try
      val url = s"jdbc:scljet:${db.toString}"
      val c1 = DriverManager.getConnection(url)
      try
        val s = c1.createStatement()
        s.executeUpdate("CREATE TABLE k(id INTEGER, v TEXT)")
        s.executeUpdate("INSERT INTO k VALUES (1,'one')")
        s.executeUpdate("INSERT INTO k VALUES (2,'two')")
      finally c1.close()
      assert(Files.exists(db) && Files.size(db) > 0)
      // Reopen a fresh connection — data must have persisted to the file.
      val c2 = DriverManager.getConnection(url)
      try
        val rs = c2.createStatement().executeQuery("SELECT id, v FROM k ORDER BY id")
        val rows = scala.collection.mutable.ArrayBuffer.empty[String]
        while rs.next() do rows += s"${rs.getLong(1)}|${rs.getString(2)}"
        assert(rows.toList == List("1|one", "2|two"))
      finally c2.close()
    finally
      Files.deleteIfExists(db); Files.deleteIfExists(dir)

  test("read-only mode rejects writes"):
    val c = DriverManager.getConnection("jdbc:scljet::memory:?mode=ro")
    try
      val ex = intercept[SQLException](c.createStatement().executeUpdate("CREATE TABLE x(a INTEGER)"))
      assert(ex.getMessage.toLowerCase.contains("read-only"))
    finally c.close()

  test("unsupported operations throw SQLFeatureNotSupportedException"):
    val c = memConn()
    try
      seed(c)
      val rs = c.createStatement().executeQuery("SELECT id FROM emp")
      // scrollable navigation is unsupported (forward-only)
      intercept[SQLFeatureNotSupportedException](rs.beforeFirst())
      intercept[SQLFeatureNotSupportedException](rs.previous())
      intercept[SQLFeatureNotSupportedException](c.prepareCall("select 1"))
      intercept[SQLFeatureNotSupportedException](c.setSavepoint())
    finally c.close()

  test("DatabaseMetaData identifies the product and driver"):
    val c = memConn()
    try
      val md = c.getMetaData
      assert(md.getDatabaseProductName == "SclJet")
      assert(md.getDriverName == "scljet-jdbc")
      assert(md.supportsResultSetType(ResultSet.TYPE_FORWARD_ONLY))
      assert(!md.supportsResultSetType(ResultSet.TYPE_SCROLL_INSENSITIVE))
      assert(md.getIdentifierQuoteString == "\"")
    finally c.close()

  // ── Oracle cross-check against reference org.xerial:sqlite-jdbc ────────────
  test("ResultSet getters equal reference sqlite-jdbc byte-for-value"):
    val schema = "CREATE TABLE emp(id INTEGER, name TEXT, salary INTEGER, bonus REAL)"
    val inserts = List(
      "INSERT INTO emp VALUES (1,'ann',100,1.5)",
      "INSERT INTO emp VALUES (2,'bob',250,2.5)",
      "INSERT INTO emp(id) VALUES (3)",
    )
    val query = "SELECT id, name, salary, bonus FROM emp ORDER BY id"

    def render(rs: ResultSet): List[String] =
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do
        val name  = Option(rs.getString(2)).getOrElse("<null>")
        out += s"${rs.getLong(1)}|$name|${rs.getLong(3)}|${rs.getDouble(4)}"
      out.toList

    // scljet lane
    val sc = memConn()
    val scRows =
      try
        val s = sc.createStatement()
        s.executeUpdate(schema); inserts.foreach(s.executeUpdate)
        render(s.executeQuery(query))
      finally sc.close()

    // reference sqlite-jdbc lane (in-memory)
    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val refRows =
      try
        val s = ref.createStatement()
        s.executeUpdate(schema); inserts.foreach(s.executeUpdate)
        render(s.executeQuery(query))
      finally ref.close()

    assert(scRows == refRows, s"scljet=$scRows\nsqlite=$refRows")

  // ── getGeneratedKeys (J2.2) ───────────────────────────────────────────────

  test("getGeneratedKeys returns the last-insert rowid as a one-column ResultSet"):
    val c = memConn()
    try
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE k(id INTEGER, v TEXT)")
      s.executeUpdate("INSERT INTO k VALUES (10,'ten')")
      val rs = s.getGeneratedKeys
      val md = rs.getMetaData
      assert(md.getColumnCount == 1)
      assert(md.getColumnLabel(1) == "last_insert_rowid()")
      assert(md.getColumnType(1) == Types.BIGINT)
      assert(rs.next())
      assert(rs.getLong(1) == 1L)                       // first row → rowid 1
      assert(rs.getLong("last_insert_rowid()") == 1L)   // by label too
      assert(!rs.next())                                // exactly one row
      assert(rs.getStatement == s)
      // the rowid advances with each insert
      s.executeUpdate("INSERT INTO k VALUES (20,'twenty')")
      val rs2 = s.getGeneratedKeys
      assert(rs2.next() && rs2.getLong(1) == 2L)
    finally c.close()

  test("getGeneratedKeys on an INTEGER PRIMARY KEY table reports the EXPLICIT id, not a counter"):
    // The test above uses a PLAIN `INTEGER` column, where a sequential rowid IS
    // the right answer — so it passed while lastInsertRowid was a counter
    // (BUGS.md scljet-ipk-rowid-alias-not-substituted).  An INTEGER PRIMARY KEY
    // is the case that tells the two apart: the explicit value IS the rowid, so
    // inserting 7 into an empty table must report 7, not 1.  Cross-checked
    // against the reference driver's last_insert_rowid() rather than a literal.
    def keys(c: Connection): List[Long] =
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE k(id INTEGER PRIMARY KEY, v TEXT)")
      val out = scala.collection.mutable.ArrayBuffer.empty[Long]
      List("INSERT INTO k VALUES (7,'seven')",   // explicit → rowid 7
           "INSERT INTO k(v) VALUES ('auto')",   // auto     → max+1 = 8
           "INSERT INTO k VALUES (3,'three')",   // explicit below the max → 3
      ).foreach: sql =>
        s.executeUpdate(sql)
        val rs = s.getGeneratedKeys
        assert(rs.next(), s"INSERT generates a key: $sql")
        out += rs.getLong(1)
      out.toList

    val sc = memConn()
    val scKeys = try keys(sc) finally sc.close()

    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val refKeys = try keys(ref) finally ref.close()

    assert(scKeys == List(7L, 8L, 3L), s"explicit IPK values are the rowids: $scKeys")
    assert(scKeys == refKeys, s"scljet=$scKeys\nsqlite=$refKeys")

  // ── INSERT … SELECT ───────────────────────────────────────────────────────

  test("INSERT INTO t SELECT … copies rows, cross-checked against the reference"):
    // Was "expected VALUES" — the row source can be a query, not just a tuple
    // list. Every assertion is the reference driver's own answer, not a literal.
    def run(c: Connection): List[String] =
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE src(id INTEGER PRIMARY KEY, name TEXT, dept INTEGER)")
      s.executeUpdate("CREATE TABLE dst(id INTEGER PRIMARY KEY, name TEXT, dept INTEGER)")
      s.executeUpdate("INSERT INTO src VALUES (1,'ann',10),(7,'bob',20),(9,'cid',10)")
      // filtered copy — the IPK values travel with the rows
      s.executeUpdate("INSERT INTO dst SELECT * FROM src WHERE dept = 10")
      // column-list form + a self-referencing source (reads the PRE-insert dst)
      s.executeUpdate("INSERT INTO dst(name, dept) SELECT name, dept FROM src WHERE id = 7")
      val rs = s.executeQuery("SELECT id, name, dept FROM dst ORDER BY id")
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do out += s"${rs.getLong(1)}|${rs.getString(2)}|${rs.getInt(3)}"
      out.toList

    val sc = memConn()
    val scRows = try run(sc) finally sc.close()

    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val refRows = try run(ref) finally ref.close()

    assert(scRows == refRows, s"scljet=$scRows\nsqlite=$refRows")
    // pin the values too, so a shared misbehaviour cannot make this vacuous
    assert(scRows == List("1|ann|10", "9|cid|10", "10|bob|20"), s"got $scRows")

  test("getGeneratedKeys is empty when the last execution generated no key"):
    val c = memConn()
    try
      val s = c.createStatement()
      // a statement that has never executed
      assert(!s.getGeneratedKeys.next())
      s.executeUpdate("CREATE TABLE k(id INTEGER, v TEXT)")
      assert(!s.getGeneratedKeys.next(), "DDL generates no key")
      s.executeUpdate("INSERT INTO k VALUES (1,'one')")
      assert(s.getGeneratedKeys.next(), "INSERT generates a key")
      s.executeUpdate("UPDATE k SET v='x' WHERE id=1")
      assert(!s.getGeneratedKeys.next(), "UPDATE generates no key")
      s.executeUpdate("INSERT INTO k VALUES (2,'two')")
      s.executeUpdate("DELETE FROM k WHERE id=1")
      assert(!s.getGeneratedKeys.next(), "DELETE generates no key")
      s.executeQuery("SELECT id FROM k")
      assert(!s.getGeneratedKeys.next(), "SELECT generates no key")
      // NOTE: the "INSERT that affects 0 rows" case (guarded by `changes > 0` in
      // StatementState.runUpdate) is not asserted here — it needs INSERT…SELECT,
      // which the engine does not parse yet ("expected VALUES").
    finally c.close()

  test("PreparedStatement getGeneratedKeys, incl. the RETURN_GENERATED_KEYS overloads"):
    val c = memConn()
    try
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE k(id INTEGER, v TEXT)")
      // Connection.prepareStatement(sql, RETURN_GENERATED_KEYS)
      val ps = c.prepareStatement("INSERT INTO k VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)
      ps.setInt(1, 7); ps.setString(2, "seven")
      assert(ps.executeUpdate() == 1)
      val rs = ps.getGeneratedKeys
      assert(rs.next() && rs.getLong(1) == 1L)
      assert(rs.getStatement == ps)
      // Statement.executeUpdate(sql, RETURN_GENERATED_KEYS)
      assert(s.executeUpdate("INSERT INTO k VALUES (8,'eight')", Statement.RETURN_GENERATED_KEYS) == 1)
      assert(s.getGeneratedKeys.next())
      // Statement.execute(sql, RETURN_GENERATED_KEYS)
      assert(!s.execute("INSERT INTO k VALUES (9,'nine')", Statement.RETURN_GENERATED_KEYS))
      val rs3 = s.getGeneratedKeys
      assert(rs3.next() && rs3.getLong(1) == 3L)
      // NO_GENERATED_KEYS still tracks the rowid (as SQLite's last_insert_rowid() does)
      assert(s.executeUpdate("INSERT INTO k VALUES (10,'ten')", Statement.NO_GENERATED_KEYS) == 1)
      assert(s.getGeneratedKeys.next())
    finally c.close()

  test("getGeneratedKeys equals reference sqlite-jdbc (rows and rowids)"):
    val schema = "CREATE TABLE k(id INTEGER, v TEXT)"
    val steps = List(
      "INSERT INTO k VALUES (1,'one')",
      "INSERT INTO k VALUES (2,'two')",
      "UPDATE k SET v='x' WHERE id=1",
      "INSERT INTO k VALUES (3,'three')",
      "DELETE FROM k WHERE id=2",
    )

    /** After each step: "<hasRow>:<rowid or ->" — the observable getGeneratedKeys contract. */
    def render(c: Connection): List[String] =
      val s = c.createStatement()
      s.executeUpdate(schema)
      steps.map { sql =>
        s.executeUpdate(sql)
        val rs = s.getGeneratedKeys
        if rs.next() then s"true:${rs.getLong(1)}" else "false:-"
      }

    val sc = memConn()
    val scRows = try render(sc) finally sc.close()

    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val refRows = try render(ref) finally ref.close()

    assert(scRows == refRows, s"scljet=$scRows\nsqlite=$refRows")

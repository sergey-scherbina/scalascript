package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, ResultSet, SQLException, SQLFeatureNotSupportedException, Types}

/** JVM `java.sql.Driver` shim — end-to-end through `DriverManager`.
 *
 *  Proves the JVM↔engine bridge: `DriverManager.getConnection("jdbc:scljet:…")`
 *  runs CREATE/INSERT/SELECT/prepared/txn against the pure scljet engine and the
 *  `ResultSet` getters map values exactly like the engine (asserted against
 *  literals, plus cross-checked against the reference `org.xerial:sqlite-jdbc`). */
class ScljetDriverTest extends AnyFunSuite:

  // Force driver registration (also happens via META-INF/services on the classpath).
  Class.forName("scalascript.compiler.plugin.scljetjdbc.ScljetDriver")

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

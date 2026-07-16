package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, DriverManager}

/** DIFFERENTIAL: `INTEGER PRIMARY KEY` = rowid alias, across the two engines
 *  THROUGH A FILE (BUGS.md `scljet-ipk-rowid-alias-not-substituted`).
 *
 *  Why this suite exists at all: every other scljet test uses "scljet reads
 *  back what scljet wrote" as its oracle, which is self-consistent by
 *  construction and CANNOT observe an interop divergence.  In real SQLite an
 *  `INTEGER PRIMARY KEY` column is an *alias for the rowid*: the record stores
 *  NULL for the column and the value lives in the rowid, and every read of that
 *  column returns the rowid regardless of what the record holds.  A file is the
 *  only shared artifact between the two engines, so the file is the contract —
 *  both directions across it are pinned below.
 *
 *  Directions:
 *   - (a) reference writes → scljet reads   — was RED: `0|ann, 0|bob`.
 *   - (b) scljet writes    → reference reads — was GREEN already; see the
 *         `writes a file whose IPK real SQLite reads correctly` test for why
 *         the hypothesised second half of the bug is NOT real. */
class ScljetIpkRowidDifferentialTest extends AnyFunSuite:

  // Register the driver (see ScljetDriverTest for why Class.forName is not enough).
  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private def withTempDb(name: String)(body: java.nio.file.Path => Unit): Unit =
    val dir = java.nio.file.Files.createTempDirectory(s"scljet-ipk-$name-")
    val db = dir.resolve("ipk.db")
    try body(db)
    finally
      java.nio.file.Files.deleteIfExists(db)
      java.nio.file.Files.deleteIfExists(dir)

  private def refConn(db: java.nio.file.Path): Connection =
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")

  private def rows(c: Connection, sql: String): List[String] =
    val rs = c.createStatement().executeQuery(sql)
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val n = rs.getMetaData.getColumnCount
    while rs.next() do out += (1 to n).map(i => String.valueOf(rs.getObject(i))).mkString("|")
    out.toList

  /** The shared fixture: an IPK whose values are NOT the sequential rowids a
   *  naive writer would assign (1,7 — not 1,2), so a rowid/column confusion in
   *  either engine shows up as a wrong VALUE rather than a coincidence. */
  private val Ddl = "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)"
  private val Ins = "INSERT INTO emp VALUES (1,'ann'),(7,'bob')"
  private val Expected = List("1|ann", "7|bob")

  // ── direction (a): reference writes → scljet reads ────────────────────────

  test("reads the IPK of a file written by the reference driver"):
    withTempDb("read"): db =>
      val ref = refConn(db)
      try
        val s = ref.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate(Ins)
        // the reference agrees with itself — pins the fixture, not the engine
        assert(rows(ref, "SELECT id, name FROM emp") == Expected)
      finally ref.close()

      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}?mode=ro")
      try
        // WAS RED: 0|ann, 0|bob — the record stores NULL for the IPK column and
        // the engine returned the stored NULL, which the getters coerce to 0.
        assert(rows(c, "SELECT id, name FROM emp") == Expected,
          "scljet must substitute the rowid for the INTEGER PRIMARY KEY column")
        // the rowid and the IPK alias are the same value, by definition
        assert(rows(c, "SELECT rowid, id FROM emp") == List("1|1", "7|7"))
      finally c.close()

  test("filters, orders and aggregates on an IPK read from a reference file"):
    // Projection is not the only path that reads a column: WHERE, ORDER BY and
    // aggregates each reach the record separately, so a projection-only fix
    // would leave these reading 0.  Pin them through the same file.
    withTempDb("paths"): db =>
      val ref = refConn(db)
      try
        val s = ref.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate(Ins)
      finally ref.close()

      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}?mode=ro")
      try
        assert(rows(c, "SELECT id, name FROM emp WHERE id = 7") == List("7|bob"))
        assert(rows(c, "SELECT id FROM emp WHERE id > 1") == List("7"))
        assert(rows(c, "SELECT id, name FROM emp ORDER BY id DESC") == List("7|bob", "1|ann"))
        assert(rows(c, "SELECT max(id), min(id), sum(id), count(id) FROM emp") == List("7|1|8|2"))
        assert(rows(c, "SELECT * FROM emp") == Expected, "star projection")
      finally c.close()

  // ── direction (b): scljet writes → reference reads ────────────────────────

  test("writes a file whose IPK real SQLite reads correctly"):
    // The hypothesis in BUGS.md was that scljet stores the IPK value in the
    // column while assigning rowids SEQUENTIALLY, so real SQLite (which always
    // reads the rowid for an IPK column) would report id=2 for 'bob' instead of
    // 7.  This test is what falsifies that: `assignInsertRowids` already uses an
    // explicit IPK value AS the rowid, so the rowid is 7 and the reference reads
    // 7.  The only divergence left is that we ALSO store the value in the
    // column, where canonical SQLite stores NULL — invisible to every reader,
    // since an IPK column read always returns the rowid.  Non-canonical bytes,
    // not wrong data.
    withTempDb("write"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate(Ins)
        assert(rows(c, "SELECT id, name FROM emp") == Expected, "scljet reads back its own write")
      finally c.close()

      val ref = refConn(db)
      try
        assert(rows(ref, "SELECT id, name FROM emp") == Expected,
          "real SQLite must read the IPK values scljet wrote, not sequential rowids")
        // the rowid IS the supplied IPK value — this is the half that already held
        assert(rows(ref, "SELECT rowid, id FROM emp") == List("1|1", "7|7"))
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"))
      finally ref.close()

  test("auto-assigned IPK rowids round-trip to real SQLite"):
    // The other write path: no explicit IPK ⇒ max(rowid)+1, written back into
    // the column.  Real SQLite must see the same ids scljet reports.
    withTempDb("auto"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate("INSERT INTO emp(name) VALUES ('ann')")
        s.executeUpdate("INSERT INTO emp VALUES (7,'bob')")
        s.executeUpdate("INSERT INTO emp(name) VALUES ('cid')")  // ⇒ 8, after the explicit 7
        assert(rows(c, "SELECT id, name FROM emp") == List("1|ann", "7|bob", "8|cid"))
      finally c.close()

      val ref = refConn(db)
      try
        assert(rows(ref, "SELECT id, name FROM emp") == List("1|ann", "7|bob", "8|cid"))
      finally ref.close()

  // ── round trip: reference writes → scljet mutates → reference reads ───────

  test("scljet INSERT into a reference-written file keeps every IPK intact"):
    // The nastiest interop case: the pre-existing rows carry NULL in the IPK
    // column (canonical SQLite), and our writer re-encodes rows it read.  If the
    // read path did not substitute the rowid, the re-encode would silently
    // rewrite ann's id to 0 — a read bug escalating into a WRITE corruption.
    withTempDb("mutate"): db =>
      val ref0 = refConn(db)
      try
        val s = ref0.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate("INSERT INTO emp VALUES (1,'ann'),(7,'bob')")
      finally ref0.close()

      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try c.createStatement().executeUpdate("INSERT INTO emp VALUES (9,'cid')")
      finally c.close()

      val ref = refConn(db)
      try
        assert(rows(ref, "SELECT id, name FROM emp") == List("1|ann", "7|bob", "9|cid"))
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"))
      finally ref.close()

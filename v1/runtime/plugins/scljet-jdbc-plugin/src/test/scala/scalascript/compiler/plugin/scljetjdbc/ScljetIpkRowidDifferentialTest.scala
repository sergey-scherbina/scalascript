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

  test("UPDATE of an IPK column MOVES the row, and real SQLite agrees through the file"):
    // BUGS.md `scljet-update-ipk-does-not-move-rowid` + `scljet-update-ipk-column-silently-ignored`
    // (fixed 2026-07-27). An IPK column IS the rowid, so assigning it relocates the row. We used to
    // rewrite the column and leave the rowid — and since an IPK read returns the rowid, the write
    // then looked like a silent no-op that still reported success.
    //
    // This is the file-level half of the proof that the `[int, js]` conformance case
    // (`scljet-update-ipk-moves-rowid`) cannot give: that case is a self-consistent oracle, whereas
    // here real SQLite reads the bytes we wrote. Before the fix the reference read `1|1|ann`
    // (row never moved); the bug report measured exactly that.
    withTempDb("update-move"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate("INSERT INTO emp VALUES (1,'ann'),(7,'bob')")
        s.executeUpdate("UPDATE emp SET id = 5 WHERE id = 1")
        assert(rows(c, "SELECT rowid, id, name FROM emp") == List("5|5|ann", "7|7|bob"),
          "scljet must report the moved row at its new rowid")
      finally c.close()

      val ref = refConn(db)
      try
        // The contract is the FILE: SQLite reads the rowid for an IPK column, so if the row had not
        // actually moved this would read `1|1|ann` no matter what our own reader claims.
        assert(rows(ref, "SELECT rowid, id, name FROM emp") == List("5|5|ann", "7|7|bob"),
          "real SQLite must see the row at rowid 5 — the row moved in the FILE, not just in our reader")
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"))
      finally ref.close()

  test("an IPK move on an INDEXED table keeps the b-tree in rowid order"):
    // The same statement takes a DIFFERENT code path when the table has an index: instead of
    // deleting and reinserting through the b-tree, executeUpdate rebuilds table + indexes from a row
    // list via `reindexTable` — which writes cells in LIST order and never calls `leafInsertCell`.
    // So a move that relocated the row's KEY while leaving its POSITION produced a file our own
    // reader rejected ("table rowids are not strictly increasing") and reference
    // `PRAGMA integrity_check` called corrupt ("Tree N page N cell 0: Rowid N out of order").
    // Every other test in this suite uses an UNINDEXED table, which is why the corruption survived
    // them: it is not a wrong value anywhere, it is a malformed b-tree.
    //
    // The target MUST move the row PAST a surviving row (1 → 9, over bob at 7). An earlier version
    // of this test moved 1 → 5, which leaves the list [5, 7] still ascending — it passed with the
    // ordering fix reverted, i.e. it was a gate that could not fail. Ordering bugs are only visible
    // when the fixture actually forces a reorder.
    withTempDb("update-indexed"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate("CREATE INDEX idx_name ON emp(name)")
        s.executeUpdate(Ins)                                   // rowids 1 (ann), 7 (bob)
        s.executeUpdate("UPDATE emp SET id = 9 WHERE id = 1")  // ann must end up AFTER bob
        assert(rows(c, "SELECT rowid, id, name FROM emp") == List("7|7|bob", "9|9|ann"),
          "the moved row must read back in rowid order — a mis-ordered b-tree fails the read itself")
      finally c.close()

      val ref = refConn(db)
      try
        // integrity_check is the assertion that matters: it validates cell ordering AND cross-checks
        // the index against the table, so it is what distinguishes "moved" from "corrupt".
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"),
          "an IPK move on an indexed table must leave a structurally valid SQLite file")
        assert(rows(ref, "SELECT rowid, id, name FROM emp") == List("7|7|bob", "9|9|ann"))
        // the index must follow the row: its entries carry the rowid as their tail
        assert(rows(ref, "SELECT id FROM emp WHERE name = 'ann'") == List("9"))
      finally ref.close()

  test("an IPK move onto an occupied rowid is refused on an INDEXED table too"):
    // The indexed path bypasses leafInsertCell, so it also bypassed the duplicate-rowid refusal the
    // unindexed path gets for free — a collision was accepted in silence. Both paths now share
    // ipkMoveConflict, so the refusal and its wording are the same on either side.
    withTempDb("update-indexed-conflict"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate("CREATE INDEX idx_name ON emp(name)")
        s.executeUpdate(Ins)
        val refused =
          try
            s.executeUpdate("UPDATE emp SET id = 7 WHERE id = 1")
            false
          catch case _: java.sql.SQLException => true
        assert(refused, "moving row 1 onto the occupied rowid 7 must be refused on an indexed table")
        assert(rows(c, "SELECT rowid, id, name FROM emp") == List("1|1|ann", "7|7|bob"),
          "a refused UPDATE must change nothing")
      finally c.close()

      val ref = refConn(db)
      try
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"))
        assert(rows(ref, "SELECT rowid, id, name FROM emp") == List("1|1|ann", "7|7|bob"))
      finally ref.close()

  test("an IPK move onto an occupied rowid is refused and leaves the file intact"):
    // Real SQLite raises `UNIQUE constraint failed: emp.id`; we refuse before writing anything, so
    // the pre-update file must survive byte-for-byte as far as both engines can tell.
    withTempDb("update-conflict"): db =>
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate(Ddl)
        s.executeUpdate(Ins)
        val refused =
          try
            s.executeUpdate("UPDATE emp SET id = 7 WHERE id = 1")
            false
          catch case _: java.sql.SQLException => true
        assert(refused, "moving row 1 onto the occupied rowid 7 must be refused, not silently applied")
        assert(rows(c, "SELECT rowid, id, name FROM emp") == List("1|1|ann", "7|7|bob"),
          "a refused UPDATE must change nothing")
      finally c.close()

      val ref = refConn(db)
      try
        assert(rows(ref, "SELECT rowid, id, name FROM emp") == List("1|1|ann", "7|7|bob"))
        assert(rows(ref, "PRAGMA integrity_check") == List("ok"))
      finally ref.close()

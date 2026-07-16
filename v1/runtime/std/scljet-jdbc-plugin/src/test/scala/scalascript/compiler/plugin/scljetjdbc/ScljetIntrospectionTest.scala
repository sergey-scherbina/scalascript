package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, DatabaseMetaData, DriverManager, ResultSet, Types}

/** `DatabaseMetaData` introspection (J4): getPrimaryKeys / getIndexInfo /
 *  getTypeInfo, plus the always-empty foreign-key queries.
 *
 *  Shapes were taken from the reference driver (Xerial `sqlite-jdbc`) and are
 *  cross-checked against it — except for two deliberate deviations, each
 *  asserted here so they cannot regress into an accident: the reference ignores
 *  getIndexInfo's `unique` filter (a bug), and its getTypeInfo reports type
 *  codes that contradict what this driver reports in getColumns. */
class ScljetIntrospectionTest extends AnyFunSuite:

  // Register the driver (see ScljetDriverTest for why Class.forName is not enough).
  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private def memConn(): Connection = DriverManager.getConnection("jdbc:scljet::memory:")

  /** NOTE: no `CREATE UNIQUE INDEX` here — the ENGINE cannot parse it
   *  (`parseCreateIndex` requires `CREATE INDEX`; `CREATE UNIQUE INDEX` falls
   *  through to `parseCreate` → "expected TABLE").  So every index a scljet
   *  database can hold is non-unique, and the unique path is exercised instead
   *  against a file written by the reference driver (see the last test). */
  private val Ddl = List(
    "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT, dept INTEGER)",
    "CREATE INDEX emp_name ON emp(name)",
    "CREATE INDEX emp_dept ON emp(dept, name)",
    "CREATE TABLE plain(a INTEGER, b TEXT)",
  )

  private def seed(c: Connection): Unit =
    val s = c.createStatement(); Ddl.foreach(s.executeUpdate); s.close()

  private def render(rs: ResultSet, cols: String*): List[String] =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    while rs.next() do out += cols.map(c => String.valueOf(rs.getObject(c))).mkString("|")
    out.toList

  // ── getPrimaryKeys ────────────────────────────────────────────────────────

  test("getPrimaryKeys: column-level PRIMARY KEY"):
    val c = memConn()
    try
      seed(c)
      val rs = c.getMetaData.getPrimaryKeys(null, null, "emp")
      val md = rs.getMetaData
      assert(md.getColumnCount == 6)
      assert((1 to 6).map(md.getColumnLabel).toList ==
        List("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"))
      assert(rs.next())
      assert(rs.getString("TABLE_NAME") == "emp")
      assert(rs.getString("COLUMN_NAME") == "id")
      assert(rs.getShort("KEY_SEQ") == 1)
      assert(rs.getString("PK_NAME") == null && rs.wasNull())
      assert(!rs.next())
    finally c.close()

  test("getPrimaryKeys: a table without a primary key yields no rows"):
    val c = memConn()
    try
      seed(c)
      assert(!c.getMetaData.getPrimaryKeys(null, null, "plain").next())
    finally c.close()

  test("getPrimaryKeys: table-level PRIMARY KEY (a, b), named and unnamed"):
    val c = memConn()
    try
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE t1(a INTEGER, b TEXT, PRIMARY KEY (a, b))")
      s.executeUpdate("CREATE TABLE t2(a INTEGER, b TEXT, CONSTRAINT t2_pk PRIMARY KEY (b, a))")
      // KEY_SEQ follows the order declared in the constraint, not the column order.
      assert(render(c.getMetaData.getPrimaryKeys(null, null, "t1"), "COLUMN_NAME", "KEY_SEQ") ==
        List("a|1", "b|2"))
      assert(render(c.getMetaData.getPrimaryKeys(null, null, "t2"), "COLUMN_NAME", "KEY_SEQ") ==
        List("a|2", "b|1"))   // ordered by COLUMN_NAME; KEY_SEQ keeps the declared order
    finally c.close()

  test("getPrimaryKeys: a table-level FOREIGN KEY / UNIQUE is not a primary key"):
    val c = memConn()
    try
      val s = c.createStatement()
      s.executeUpdate("CREATE TABLE t(a INTEGER, b TEXT, UNIQUE (a), FOREIGN KEY (b) REFERENCES other(x))")
      assert(!c.getMetaData.getPrimaryKeys(null, null, "t").next())
      // ...and neither is mistaken for a column
      val cols = render(c.getMetaData.getColumns(null, null, "t", null), "COLUMN_NAME")
      assert(cols == List("a", "b"))
    finally c.close()

  // ── getIndexInfo ──────────────────────────────────────────────────────────

  test("getIndexInfo: one row per index column, ordered by (NON_UNIQUE, INDEX_NAME, ORDINAL)"):
    val c = memConn()
    try
      seed(c)
      val rs = c.getMetaData.getIndexInfo(null, null, "emp", false, false)
      val md = rs.getMetaData
      assert(md.getColumnCount == 13)
      assert((1 to 13).map(md.getColumnLabel).toList == List(
        "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER", "INDEX_NAME",
        "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC", "CARDINALITY", "PAGES",
        "FILTER_CONDITION"))
      // ordered by INDEX_NAME (all non-unique here); multi-column keys in key order
      assert(render(rs, "INDEX_NAME", "NON_UNIQUE", "ORDINAL_POSITION", "COLUMN_NAME") == List(
        "emp_dept|true|1|dept",
        "emp_dept|true|2|name",
        "emp_name|true|1|name",
      ))
    finally c.close()

  test("getIndexInfo: constant columns and the ignored `approximate` flag"):
    val c = memConn()
    try
      seed(c)
      val rs = c.getMetaData.getIndexInfo(null, null, "emp", false, true)
      assert(rs.next())
      assert(rs.getShort("TYPE") == DatabaseMetaData.tableIndexOther)
      assert(rs.getLong("CARDINALITY") == 0L)
      assert(rs.getLong("PAGES") == 0L)
      assert(rs.getString("ASC_OR_DESC") == null)
      assert(rs.getString("INDEX_QUALIFIER") == null)
      assert(rs.getString("FILTER_CONDITION") == null)
    finally c.close()

  test("getIndexInfo: unique=true filters — DELIBERATELY unlike the reference driver"):
    val c = memConn()
    try
      seed(c)
      // Every index a scljet-created database can hold is non-unique (the engine
      // cannot CREATE UNIQUE INDEX), so the contract filter empties the result.
      assert(!c.getMetaData.getIndexInfo(null, null, "emp", true, false).next())

      // Pin the deviation: the reference driver IGNORES the flag and returns
      // non-unique indexes anyway. If a future sqlite-jdbc fixes that, this
      // assertion fails and tells us the deviation note can go.
      Class.forName("org.sqlite.JDBC")
      val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
      try
        val s = ref.createStatement(); Ddl.foreach(s.executeUpdate)
        val refNames = render(ref.getMetaData.getIndexInfo(null, null, "emp", true, false), "INDEX_NAME")
        assert(refNames.contains("emp_name"),
          "reference sqlite-jdbc no longer ignores the unique filter — re-check our deviation note")
      finally ref.close()
    finally c.close()

  test("getIndexInfo: a table with no index yields no rows"):
    val c = memConn()
    try
      seed(c)
      assert(!c.getMetaData.getIndexInfo(null, null, "plain", false, false).next())
    finally c.close()

  // ── getTypeInfo + foreign keys ────────────────────────────────────────────

  test("getTypeInfo lists the five storage classes with THIS driver's type codes"):
    val c = memConn()
    try
      val rs = c.getMetaData.getTypeInfo
      assert(rs.getMetaData.getColumnCount == 18)
      assert(render(rs, "TYPE_NAME", "DATA_TYPE") == List(
        s"INTEGER|${Types.BIGINT}",   // NOT Types.INTEGER — see the consistency test below
        s"NULL|${Types.NULL}",
        s"REAL|${Types.DOUBLE}",      // NOT Types.REAL
        s"TEXT|${Types.VARCHAR}",
        s"BLOB|${Types.BLOB}",
      ))
    finally c.close()

  test("getTypeInfo DATA_TYPE agrees with getColumns DATA_TYPE"):
    val c = memConn()
    try
      c.createStatement().executeUpdate("CREATE TABLE t(i INTEGER, r REAL, s TEXT, b BLOB)")
      val typeInfo = scala.collection.mutable.Map.empty[String, Int]
      val ti = c.getMetaData.getTypeInfo
      while ti.next() do typeInfo(ti.getString("TYPE_NAME")) = ti.getInt("DATA_TYPE")
      // Every column's declared type must resolve to the same code getTypeInfo
      // advertises for that type name — else a client cannot map the two.
      val cols = c.getMetaData.getColumns(null, null, "t", null)
      var n = 0
      while cols.next() do
        val declared = cols.getString("TYPE_NAME")
        assert(typeInfo.get(declared).contains(cols.getInt("DATA_TYPE")),
          s"getColumns says ${cols.getInt("DATA_TYPE")} for $declared, getTypeInfo says ${typeInfo.get(declared)}")
        n += 1
      assert(n == 4)
    finally c.close()

  test("foreign-key queries are empty, not unsupported"):
    val c = memConn()
    try
      seed(c)
      val md = c.getMetaData
      assert(md.getColumnCount(md.getImportedKeys(null, null, "emp")) == 14)
      assert(!md.getImportedKeys(null, null, "emp").next())
      assert(!md.getExportedKeys(null, null, "emp").next())
      assert(!md.getCrossReference(null, null, "emp", null, null, "plain").next())
      assert(!md.supportsIntegrityEnhancementFacility())
    finally c.close()

  extension (md: DatabaseMetaData)
    private def getColumnCount(rs: ResultSet): Int = rs.getMetaData.getColumnCount

  // ── Oracle cross-check (where the reference is not buggy) ──────────────────

  test("getPrimaryKeys / getIndexInfo agree with reference sqlite-jdbc"):
    def pks(c: Connection): List[String] =
      render(c.getMetaData.getPrimaryKeys(null, null, "emp"), "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME")
    // NON_UNIQUE is read with getBoolean, not getObject: JDBC declares the column
    // `boolean` and we box it as one, while the reference boxes it as Integer 1
    // (SQLite has no boolean). Comparing getObject would compare the drivers'
    // boxing choices instead of the data.
    def idx(c: Connection): List[String] =
      val rs = c.getMetaData.getIndexInfo(null, null, "emp", false, false)
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do
        out += List[Any](rs.getString("TABLE_NAME"), rs.getBoolean("NON_UNIQUE"), rs.getString("INDEX_NAME"),
          rs.getShort("TYPE"), rs.getShort("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"),
          rs.getString("ASC_OR_DESC"), rs.getLong("CARDINALITY"), rs.getLong("PAGES"),
          rs.getString("FILTER_CONDITION")).mkString("|")
      out.toList

    val sc = memConn()
    val (scPks, scIdx) = try { seed(sc); (pks(sc), idx(sc)) } finally sc.close()

    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val (refPks, refIdx) =
      try
        val s = ref.createStatement(); Ddl.foreach(s.executeUpdate)
        (pks(ref), idx(ref))
      finally ref.close()

    assert(scPks == refPks, s"scljet=$scPks\nsqlite=$refPks")
    assert(scIdx == refIdx, s"scljet=$scIdx\nsqlite=$refIdx")

  test("introspects a database file created by the reference driver, incl. a UNIQUE index"):
    // The engine cannot CREATE a unique index, but it can READ a file that has
    // one — which is the only way to exercise NON_UNIQUE=false end-to-end, and
    // the case that matters for interop with real SQLite files.
    val dir = java.nio.file.Files.createTempDirectory("scljet-ref-file-")
    val db = dir.resolve("ref.db")
    try
      Class.forName("org.sqlite.JDBC")
      val ref = DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")
      try
        val s = ref.createStatement()
        s.executeUpdate("CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT, dept INTEGER)")
        s.executeUpdate("CREATE INDEX emp_name ON emp(name)")
        s.executeUpdate("CREATE UNIQUE INDEX emp_dept_uq ON emp(dept, name)")
        s.executeUpdate("INSERT INTO emp VALUES (1,'ann',10)")
      finally ref.close()

      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}?mode=ro")
      try
        val md = c.getMetaData
        assert(render(md.getTables(null, null, null, Array("TABLE")), "TABLE_NAME") == List("emp"))
        assert(render(md.getColumns(null, null, "emp", null), "COLUMN_NAME", "TYPE_NAME") ==
          List("id|INTEGER", "name|TEXT", "dept|INTEGER"))
        assert(render(md.getPrimaryKeys(null, null, "emp"), "COLUMN_NAME", "KEY_SEQ") == List("id|1"))
        // the unique index is read back as unique, its key columns in order
        assert(render(md.getIndexInfo(null, null, "emp", false, false),
          "INDEX_NAME", "NON_UNIQUE", "ORDINAL_POSITION", "COLUMN_NAME") == List(
          "emp_dept_uq|false|1|dept",
          "emp_dept_uq|false|2|name",
          "emp_name|true|1|name",
        ))
        // ...and unique=true now has something to filter TO
        assert(render(md.getIndexInfo(null, null, "emp", true, false), "INDEX_NAME") ==
          List("emp_dept_uq", "emp_dept_uq"))

        // KNOWN ENGINE BUG (BUGS.md: scljet-ipk-rowid-alias-not-substituted):
        // real SQLite stores an INTEGER PRIMARY KEY column as NULL and keeps the
        // value in the rowid; the engine does not substitute it, so `id` reads
        // back as 0 instead of 1. Pinned so the fix flips this test loudly
        // rather than passing unnoticed.
        val rs = c.createStatement().executeQuery("SELECT id, name FROM emp")
        assert(rs.next())
        assert(rs.getString(2) == "ann", "non-IPK columns read correctly from a real SQLite file")
        assert(rs.getLong(1) == 0L,
          "IPK still reads as 0 — if this now returns 1, the engine bug is FIXED: " +
          "update BUGS.md scljet-ipk-rowid-alias-not-substituted and assert 1 here")
      finally c.close()
    finally
      java.nio.file.Files.deleteIfExists(db)
      java.nio.file.Files.deleteIfExists(dir)

package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, DriverManager, ResultSet, Types}

/** `DatabaseMetaData.getTables` / `getColumns` (J2.3).
 *
 *  The engine exports no table listing, so the catalog reads `sqlite_schema`
 *  structurally through `openReadonly` and parses each `CREATE TABLE` with the
 *  engine's own lexer.  Both halves of that claim are asserted here: the shapes
 *  against the JDBC contract and the reference driver, and the parsed column
 *  names against the engine's own `imageTableColumns`. */
class ScljetCatalogTest extends AnyFunSuite:

  // Register the driver (see ScljetDriverTest for why Class.forName is not enough).
  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private def memConn(): Connection = DriverManager.getConnection("jdbc:scljet::memory:")

  private def seed(c: Connection): Unit =
    val s = c.createStatement()
    s.executeUpdate("CREATE TABLE emp(id INTEGER, name TEXT, salary INTEGER, bonus REAL)")
    s.executeUpdate("CREATE TABLE dept(did INTEGER, label TEXT)")
    s.executeUpdate("INSERT INTO emp VALUES (1,'ann',100,1.5)")
    s.executeUpdate("CREATE INDEX emp_name ON emp(name)")
    s.close()

  private def collect(rs: ResultSet, col: String): List[String] =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    while rs.next() do out += rs.getString(col)
    out.toList

  test("getTables lists the tables, excluding indexes"):
    val c = memConn()
    try
      seed(c)
      val rs = c.getMetaData.getTables(null, null, null, null)
      val md = rs.getMetaData
      assert(md.getColumnCount == 10, "the JDBC getTables contract is 10 columns")
      assert(md.getColumnLabel(3) == "TABLE_NAME")
      assert(md.getColumnLabel(4) == "TABLE_TYPE")
      assert(rs.next())
      assert(rs.getString("TABLE_NAME") == "dept")   // ordered by (type, name)
      assert(rs.getString("TABLE_TYPE") == "TABLE")
      assert(rs.getString("TABLE_CAT") == null && rs.wasNull())
      assert(rs.next())
      assert(rs.getString("TABLE_NAME") == "emp")
      assert(!rs.next(), "the emp_name INDEX must not be listed as a table")
    finally c.close()

  test("getTables honours the name pattern and the type filter"):
    val c = memConn()
    try
      seed(c)
      val md = c.getMetaData
      assert(collect(md.getTables(null, null, "emp", null), "TABLE_NAME") == List("emp"))
      assert(collect(md.getTables(null, null, "e%", null), "TABLE_NAME") == List("emp"))
      assert(collect(md.getTables(null, null, "d_pt", null), "TABLE_NAME") == List("dept"))
      assert(collect(md.getTables(null, null, "%p%", null), "TABLE_NAME") == List("dept", "emp"))
      assert(collect(md.getTables(null, null, "EMP", null), "TABLE_NAME") == List("emp"), "patterns are case-insensitive")
      assert(collect(md.getTables(null, null, "nope", null), "TABLE_NAME") == Nil)
      assert(collect(md.getTables(null, null, null, Array("TABLE")), "TABLE_NAME") == List("dept", "emp"))
      assert(collect(md.getTables(null, null, null, Array("VIEW")), "TABLE_NAME") == Nil)
    finally c.close()

  test("getColumns reports names, ordinals and affinity-mapped types"):
    val c = memConn()
    try
      seed(c)
      val rs = c.getMetaData.getColumns(null, null, "emp", null)
      assert(rs.getMetaData.getColumnCount == 24, "the JDBC getColumns contract is 24 columns")
      val rows = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do
        rows += s"${rs.getString("COLUMN_NAME")}|${rs.getString("TYPE_NAME")}|${rs.getInt("DATA_TYPE")}|${rs.getInt("ORDINAL_POSITION")}"
      assert(rows.toList == List(
        s"id|INTEGER|${Types.BIGINT}|1",
        s"name|TEXT|${Types.VARCHAR}|2",
        s"salary|INTEGER|${Types.BIGINT}|3",
        s"bonus|REAL|${Types.DOUBLE}|4",
      ))
    finally c.close()

  test("getColumns honours the table and column patterns"):
    val c = memConn()
    try
      seed(c)
      val md = c.getMetaData
      assert(collect(md.getColumns(null, null, "dept", null), "COLUMN_NAME") == List("did", "label"))
      assert(collect(md.getColumns(null, null, "emp", "s%"), "COLUMN_NAME") == List("salary"))
      assert(collect(md.getColumns(null, null, "%", "id"), "COLUMN_NAME") == List("id"))
      // every table, every column — ordered by table then ordinal
      assert(collect(md.getColumns(null, null, null, null), "TABLE_NAME") ==
        List("dept", "dept", "emp", "emp", "emp", "emp"))
    finally c.close()

  test("SQLite type affinity → java.sql.Types"):
    val c = memConn()
    try
      // Affinity is decided by substring rules, not by an exact type list.
      c.createStatement().executeUpdate(
        "CREATE TABLE t(a INTEGER, b VARCHAR(255), c BLOB, d DOUBLE, e FLOAT, f BIGINT, g CLOB, h NUMERIC, i UNSIGNED BIG INT, j)")
      val rs = c.getMetaData.getColumns(null, null, "t", null)
      val rows = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do rows += s"${rs.getString("COLUMN_NAME")}|${rs.getString("TYPE_NAME")}|${rs.getInt("DATA_TYPE")}"
      assert(rows.toList == List(
        s"a|INTEGER|${Types.BIGINT}",
        s"b|VARCHAR(255)|${Types.VARCHAR}",
        s"c|BLOB|${Types.BLOB}",
        s"d|DOUBLE|${Types.DOUBLE}",
        s"e|FLOAT|${Types.DOUBLE}",
        s"f|BIGINT|${Types.BIGINT}",
        s"g|CLOB|${Types.VARCHAR}",
        s"h|NUMERIC|${Types.NUMERIC}",
        s"i|UNSIGNED BIG INT|${Types.BIGINT}",
        s"j||${Types.BLOB}",            // no declared type → BLOB affinity
      ))
    finally c.close()

  test("column definitions with constraints keep the declared type only"):
    val c = memConn()
    try
      c.createStatement().executeUpdate(
        "CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT NOT NULL, tag TEXT DEFAULT 'x', qty INTEGER CHECK (qty > 0))")
      val rs = c.getMetaData.getColumns(null, null, "t", null)
      val rows = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do rows += s"${rs.getString("COLUMN_NAME")}|${rs.getString("TYPE_NAME")}"
      assert(rows.toList == List("id|INTEGER", "name|TEXT", "tag|TEXT", "qty|INTEGER"))
    finally c.close()

  test("a table-level constraint is not reported as a column"):
    val c = memConn()
    try
      c.createStatement().executeUpdate("CREATE TABLE t(a INTEGER, b TEXT, PRIMARY KEY (a, b))")
      assert(collect(c.getMetaData.getColumns(null, null, "t", null), "COLUMN_NAME") == List("a", "b"))
    finally c.close()

  test("catalog column names equal the engine's own imageTableColumns"):
    val c = memConn()
    try
      seed(c)
      // The catalog parses CREATE TABLE itself; it must not drift from the engine.
      // A `SELECT *` labels its columns via the engine's own imageTableColumns,
      // so that ResultSetMetaData is the engine's answer to the same question.
      for table <- List("emp", "dept") do
        val viaCatalog = collect(c.getMetaData.getColumns(null, null, table, null), "COLUMN_NAME")
        val md = c.createStatement().executeQuery(s"SELECT * FROM $table").getMetaData
        val viaEngine = (1 to md.getColumnCount).map(md.getColumnLabel).toList
        assert(viaCatalog == viaEngine, s"catalog and engine disagree on $table's columns")
    finally c.close()

  test("getTableTypes / getCatalogs / getSchemas"):
    val c = memConn()
    try
      assert(collect(c.getMetaData.getTableTypes, "TABLE_TYPE") == List("TABLE", "VIEW"))
      assert(!c.getMetaData.getCatalogs.next())
      assert(!c.getMetaData.getSchemas.next())
    finally c.close()

  test("catalog sees uncommitted work inside an open transaction"):
    val c = memConn()
    try
      seed(c)
      c.setAutoCommit(false)
      c.createStatement().executeUpdate("CREATE TABLE staged(x INTEGER)")
      assert(collect(c.getMetaData.getTables(null, null, "staged", null), "TABLE_NAME") == List("staged"),
        "read-your-writes must hold for the catalog too")
      c.rollback()
      assert(collect(c.getMetaData.getTables(null, null, "staged", null), "TABLE_NAME") == Nil)
    finally c.close()

  // ── Oracle cross-check against reference org.xerial:sqlite-jdbc ────────────

  test("getTables / getColumns agree with reference sqlite-jdbc"):
    val ddl = List(
      "CREATE TABLE emp(id INTEGER, name TEXT, salary INTEGER, bonus REAL)",
      "CREATE TABLE dept(did INTEGER, label TEXT)",
      "CREATE INDEX emp_name ON emp(name)",
    )

    def tablesOf(c: Connection): List[String] =
      val rs = c.getMetaData.getTables(null, null, null, Array("TABLE"))
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do out += s"${rs.getString("TABLE_NAME")}|${rs.getString("TABLE_TYPE")}"
      out.toList.sorted

    def columnsOf(c: Connection): List[String] =
      val rs = c.getMetaData.getColumns(null, null, "emp", null)
      val out = scala.collection.mutable.ArrayBuffer.empty[String]
      while rs.next() do
        out += s"${rs.getString("TABLE_NAME")}|${rs.getString("COLUMN_NAME")}|${rs.getString("TYPE_NAME")}|${rs.getInt("ORDINAL_POSITION")}"
      out.toList

    val sc = memConn()
    val (scTables, scColumns) =
      try
        val s = sc.createStatement(); ddl.foreach(s.executeUpdate)
        (tablesOf(sc), columnsOf(sc))
      finally sc.close()

    Class.forName("org.sqlite.JDBC")
    val ref = DriverManager.getConnection("jdbc:sqlite::memory:")
    val (refTables, refColumns) =
      try
        val s = ref.createStatement(); ddl.foreach(s.executeUpdate)
        (tablesOf(ref), columnsOf(ref))
      finally ref.close()

    assert(scTables == refTables, s"scljet=$scTables\nsqlite=$refTables")
    assert(scColumns == refColumns, s"scljet=$scColumns\nsqlite=$refColumns")

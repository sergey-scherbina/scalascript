package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, DriverManager, PreparedStatement, SQLException, Types}

/** Live sqlite-jdbc oracle for SC-1 INTEGER PRIMARY KEY affinity.
 *
 *  Portable conformance isolates the pure coercion helper. This suite runs the
 *  same bound JVM values through `jdbc:scljet:` and Xerial `jdbc:sqlite:`,
 *  compares success plus resulting rows, and finally gives the SclJet-written
 *  file to real SQLite for `PRAGMA integrity_check`.
 */
class ScljetIpkAffinityDifferentialTest extends AnyFunSuite:

  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private final case class BoundCase(
      name: String,
      expectedId: Option[Long],
      bind: (PreparedStatement, Int) => Unit,
      failureCategory: String = "mismatch",
  )

  private sealed trait ExecOutcome
  private final case class Updated(count: Int) extends ExecOutcome
  private final case class Failed(phase: String, category: String) extends ExecOutcome

  private final case class ReadOutcome(status: String, rows: List[String])
  private final case class MatrixOutcome(executions: List[ExecOutcome], read: ReadOutcome)

  private def withTempDb(name: String)(body: java.nio.file.Path => Unit): Unit =
    val dir = java.nio.file.Files.createTempDirectory(s"scljet-affinity-$name-")
    val db = dir.resolve("affinity.db")
    try body(db)
    finally
      java.nio.file.Files.deleteIfExists(db)
      java.nio.file.Files.deleteIfExists(dir)

  private def refConn(url: String): Connection =
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(url)

  private def rows(c: Connection, sql: String): List[String] =
    val statement = c.createStatement()
    try
      val rs = statement.executeQuery(sql)
      try
        val out = scala.collection.mutable.ArrayBuffer.empty[String]
        val n = rs.getMetaData.getColumnCount
        while rs.next() do
          out += (1 to n).map(i => String.valueOf(rs.getObject(i))).mkString("|")
        out.toList
      finally rs.close()
    finally statement.close()

  private def readOutcome(c: Connection, sql: String): ReadOutcome =
    try ReadOutcome("rows", rows(c, sql))
    catch case _: SQLException => ReadOutcome("query-error", Nil)

  private def errorCategory(error: SQLException): String =
    val state = Option(error.getSQLState).getOrElse("")
    val message = Option(error.getMessage).getOrElse("")
    val text = (error.getClass.getSimpleName + " " + state + " " + message).toLowerCase
    if text.contains("mismatch") then "mismatch"
    else if text.contains("constraint") || text.contains("duplicate") || text.contains("unique") then "constraint"
    else "other:" + error.getClass.getSimpleName + ":" + state + ":" + message

  private def executePrepared(
      c: Connection,
      sql: String,
      bind: (PreparedStatement, Int) => Unit,
  ): ExecOutcome =
    val ps =
      try c.prepareStatement(sql)
      catch case error: SQLException => return Failed("prepare", errorCategory(error))
    try
      try bind(ps, 1)
      catch case error: SQLException => return Failed("bind", errorCategory(error))
      try Updated(ps.executeUpdate())
      catch case error: SQLException => Failed("execute", errorCategory(error))
    finally ps.close()

  private def expectedExecutions(cases: List[BoundCase]): List[ExecOutcome] =
    cases.map: bc =>
      bc.expectedId match
        case Some(_) => Updated(1)
        case None => Failed("execute", bc.failureCategory)

  // Every accepted id is unique so the entire matrix can share one table. That
  // matters: indexed writes on a multi-table SclJet image are a separate open
  // storage limitation and must not pre-judge this affinity gate.
  private val insertCases = List(
    BoundCase("integer", Some(4L), (ps, i) => ps.setLong(i, 4L)),
    BoundCase("collision-after-affinity", None, (ps, i) => ps.setString(i, "4.0"), "constraint"),
    BoundCase("null-auto", Some(51L), (ps, i) => ps.setNull(i, Types.NULL)),
    // sqlite3_bind_double canonicalises NaN to SQL NULL. The Xerial driver
    // therefore requests an automatic rowid here rather than storing REAL NaN.
    BoundCase("nan-double-auto", Some(52L), (ps, i) => ps.setDouble(i, Double.NaN)),
    BoundCase("nan-float-auto", Some(53L), (ps, i) => ps.setFloat(i, Float.NaN)),
    BoundCase("nan-object-double-auto", Some(54L),
      (ps, i) => ps.setObject(i, java.lang.Double.valueOf(Double.NaN))),
    BoundCase("nan-object-float-auto", Some(55L),
      (ps, i) => ps.setObject(i, java.lang.Float.valueOf(Float.NaN))),
    BoundCase("real-integral", Some(7L), (ps, i) => ps.setDouble(i, 7.0)),
    BoundCase("real-below-max", Some(9223372036854774784L),
      (ps, i) => ps.setDouble(i, 9223372036854774784.0)),
    BoundCase("real-fraction", None, (ps, i) => ps.setDouble(i, 7.5)),
    BoundCase("real-min-boundary", None, (ps, i) => ps.setDouble(i, -9223372036854775808.0)),
    BoundCase("real-max-boundary", None, (ps, i) => ps.setDouble(i, 9223372036854775808.0)),
    BoundCase("real-positive-infinity", None,
      (ps, i) => ps.setDouble(i, Double.PositiveInfinity)),
    BoundCase("real-negative-infinity", None,
      (ps, i) => ps.setDouble(i, Double.NegativeInfinity)),
    BoundCase("text-ascii-space", Some(5L),
      (ps, i) => ps.setString(i, "\t\u000b\u000c  +0005\r\n")),
    BoundCase("text-negative", Some(-5L), (ps, i) => ps.setString(i, "  -5  ")),
    BoundCase("text-decimal", Some(6L), (ps, i) => ps.setString(i, "6.0")),
    BoundCase("text-exponent", Some(8L), (ps, i) => ps.setString(i, "8e0")),
    BoundCase("text-decimal-exponent", Some(9L), (ps, i) => ps.setString(i, "0.9e1")),
    BoundCase("text-underflow-zero", Some(0L), (ps, i) => ps.setString(i, "1e-400")),
    BoundCase("text-rounded-2pow53", Some(9007199254740992L),
      (ps, i) => ps.setString(i, "9007199254740993.0")),
    BoundCase("text-min", Some(Long.MinValue),
      (ps, i) => ps.setString(i, "-9223372036854775808")),
    BoundCase("text-max", Some(Long.MaxValue),
      (ps, i) => ps.setString(i, "9223372036854775807")),
    BoundCase("text-min-decimal", None,
      (ps, i) => ps.setString(i, "-9223372036854775808.0")),
    BoundCase("text-max-decimal", None,
      (ps, i) => ps.setString(i, "9223372036854775807.0")),
    BoundCase("text-positive-overflow", None,
      (ps, i) => ps.setString(i, "9223372036854775808")),
    BoundCase("text-negative-overflow", None,
      (ps, i) => ps.setString(i, "-9223372036854775809")),
    BoundCase("text-fraction", None, (ps, i) => ps.setString(i, "5.5")),
    BoundCase("text-fraction-exponent", None, (ps, i) => ps.setString(i, "5e-1")),
    BoundCase("text-hex", None, (ps, i) => ps.setString(i, "0x10")),
    BoundCase("text-empty", None, (ps, i) => ps.setString(i, "")),
    BoundCase("text-malformed", None, (ps, i) => ps.setString(i, "5tail")),
    BoundCase("blob", None, (ps, i) => ps.setBytes(i, Array[Byte](1, 2, 3))),
  )

  private val updateCases = List(
    BoundCase("integer-min", Some(Long.MinValue), (ps, i) => ps.setLong(i, Long.MinValue)),
    BoundCase("text-decimal", Some(5L), (ps, i) => ps.setString(i, "5.0")),
    BoundCase("real-integral", Some(9L), (ps, i) => ps.setDouble(i, 9.0)),
    BoundCase("rounded-2pow53", Some(9007199254740992L),
      (ps, i) => ps.setString(i, "9007199254740993.0")),
    BoundCase("negative-zero", Some(0L), (ps, i) => ps.setString(i, "-0")),
    BoundCase("collision-after-affinity", None, (ps, i) => ps.setString(i, "7.0"), "constraint"),
    BoundCase("null", None, (ps, i) => ps.setNull(i, Types.NULL)),
    BoundCase("fraction", None, (ps, i) => ps.setDouble(i, 1.5)),
    BoundCase("overflow", None, (ps, i) => ps.setString(i, "9223372036854775808")),
    BoundCase("malformed", None, (ps, i) => ps.setString(i, "1tail")),
    BoundCase("blob", None, (ps, i) => ps.setBytes(i, Array[Byte](9))),
  )

  private def expectedInsertRows: List[String] =
    val accepted = insertCases.zipWithIndex.collect:
      case (bc, i) if bc.expectedId.nonEmpty => (bc.expectedId.get, s"case_$i")
    ((50L, "seed") :: accepted).sortBy(_._1).map((n, s) => s"$n|$s")

  private def runInsertMatrix(c: Connection, indexed: Boolean): MatrixOutcome =
    val s = c.createStatement()
    try
      s.executeUpdate("CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)")
      if indexed then s.executeUpdate("CREATE INDEX idx_emp_name ON emp(name)")
      s.executeUpdate("INSERT INTO emp VALUES (50,'seed')")
    finally s.close()

    val executions = insertCases.zipWithIndex.map: (bc, i) =>
      executePrepared(c, s"INSERT INTO emp(id,name) VALUES (?,'case_$i')", bc.bind)
    MatrixOutcome(executions, readOutcome(c, "SELECT id, name FROM emp ORDER BY id"))

  private def expectedUpdateRows: List[String] =
    val occupied = (7L, "occupied")
    val cases = updateCases.zipWithIndex.map: (bc, i) =>
      (bc.expectedId.getOrElse(1000L + i.toLong), s"case_$i")
    (occupied :: cases).sortBy(_._1).map((n, s) => s"$n|$s")

  private def runUpdateMatrix(c: Connection, indexed: Boolean): MatrixOutcome =
    val s = c.createStatement()
    try
      s.executeUpdate("CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)")
      if indexed then s.executeUpdate("CREATE INDEX idx_emp_name ON emp(name)")
      val caseRows = updateCases.indices.map(i => (1000L + i.toLong, s"case_$i")).toList
      val initial = ((7L, "occupied") :: caseRows)
        .map((id, name) => s"($id,'$name')")
        .mkString(",")
      s.executeUpdate(s"INSERT INTO emp VALUES $initial")
    finally s.close()

    val executions = updateCases.zipWithIndex.map: (bc, i) =>
      executePrepared(c, s"UPDATE emp SET id = ? WHERE name = 'case_$i'", bc.bind)
    MatrixOutcome(executions, readOutcome(c, "SELECT id, name FROM emp ORDER BY id"))

  private def runBoth[A](db: java.nio.file.Path)(run: Connection => A): (A, A) =
    var sc: Connection = null
    var ref: Connection = null
    try
      sc = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      ref = refConn("jdbc:sqlite::memory:")
      (run(sc), run(ref))
    finally
      try if ref != null then ref.close()
      finally if sc != null then sc.close()

  private def reopenedEvidence(
      db: java.nio.file.Path,
      sql: String,
  ): (ReadOutcome, ReadOutcome) =
    val ref = refConn(s"jdbc:sqlite:${db.toString}")
    try
      val persisted = readOutcome(ref, sql)
      val integrity = readOutcome(ref, "PRAGMA integrity_check")
      (persisted, integrity)
    finally ref.close()

  private def namedExecutions(cases: List[BoundCase], outcomes: List[ExecOutcome]): String =
    cases.zip(outcomes).map((bc, outcome) => s"${bc.name}=$outcome").mkString(", ")

  private def compareInsertLane(indexed: Boolean, name: String): Unit =
    withTempDb(name): db =>
      val (scOutcome, refOutcome) = runBoth(db)(runInsertMatrix(_, indexed))
      val (persisted, integrity) =
        reopenedEvidence(db, "SELECT id, name FROM emp ORDER BY id")

      // Compare every observable first. Only after both engines and the
      // reference reopen have run do we classify the reference expectation.
      assert(scOutcome == refOutcome,
        s"scljet=${namedExecutions(insertCases, scOutcome.executions)} read=${scOutcome.read}\n" +
          s"sqlite=${namedExecutions(insertCases, refOutcome.executions)} read=${refOutcome.read}")
      assert(refOutcome.executions == expectedExecutions(insertCases),
        s"expected=${namedExecutions(insertCases, expectedExecutions(insertCases))}\n" +
          s"sqlite=${namedExecutions(insertCases, refOutcome.executions)}")
      assert(refOutcome.read == ReadOutcome("rows", expectedInsertRows),
        s"expected=$expectedInsertRows got=${refOutcome.read}")
      assert(persisted == refOutcome.read,
        s"SQLite reopen of SclJet file=$persisted in-memory reference=${refOutcome.read}")
      assert(integrity == ReadOutcome("rows", List("ok")), s"integrity=$integrity")

  private def compareUpdateLane(indexed: Boolean, name: String): Unit =
    withTempDb(name): db =>
      val (scOutcome, refOutcome) = runBoth(db)(runUpdateMatrix(_, indexed))
      val (persisted, integrity) =
        reopenedEvidence(db, "SELECT id, name FROM emp ORDER BY id")

      assert(scOutcome == refOutcome,
        s"scljet=${namedExecutions(updateCases, scOutcome.executions)} read=${scOutcome.read}\n" +
          s"sqlite=${namedExecutions(updateCases, refOutcome.executions)} read=${refOutcome.read}")
      assert(refOutcome.executions == expectedExecutions(updateCases),
        s"expected=${namedExecutions(updateCases, expectedExecutions(updateCases))}\n" +
          s"sqlite=${namedExecutions(updateCases, refOutcome.executions)}")
      assert(refOutcome.read == ReadOutcome("rows", expectedUpdateRows),
        s"expected=$expectedUpdateRows got=${refOutcome.read}")
      assert(persisted == refOutcome.read,
        s"SQLite reopen of SclJet file=$persisted in-memory reference=${refOutcome.read}")
      assert(integrity == ReadOutcome("rows", List("ok")), s"integrity=$integrity")

  test("bound INSERT IPK affinity matches sqlite-jdbc without an index"):
    compareInsertLane(indexed = false, name = "insert-unindexed")

  test("bound INSERT IPK affinity matches sqlite-jdbc with an index"):
    compareInsertLane(indexed = true, name = "insert-indexed")

  test("bound UPDATE IPK affinity and collisions match sqlite-jdbc without an index"):
    compareUpdateLane(indexed = false, name = "update-unindexed")

  test("bound UPDATE IPK affinity and collisions match sqlite-jdbc with an index"):
    compareUpdateLane(indexed = true, name = "update-indexed")

  private final case class AutoOutcome(
      executions: List[ExecOutcome],
      negative: ReadOutcome,
      maxFallback: ReadOutcome,
      empty: ReadOutcome,
  )

  private val autoExecutionNames =
    List("negative-seed", "negative-auto", "max-seed", "used-one", "used-two", "max-auto", "empty-auto")

  private def executeDirect(c: Connection, sql: String): ExecOutcome =
    val statement = c.createStatement()
    try
      try Updated(statement.executeUpdate(sql))
      catch case error: SQLException => Failed("execute", errorCategory(error))
    finally statement.close()

  private def insertBoundLong(c: Connection, table: String, id: Long, name: String): ExecOutcome =
    executePrepared(c, s"INSERT INTO $table VALUES (?,'$name')", (ps, i) => ps.setLong(i, id))

  private def runAutoMatrix(c: Connection): AutoOutcome =
    val ddl = c.createStatement()
    try
      ddl.executeUpdate("CREATE TABLE neg(id INTEGER PRIMARY KEY, name TEXT)")
      ddl.executeUpdate("CREATE TABLE mx(id INTEGER PRIMARY KEY, name TEXT)")
      ddl.executeUpdate("CREATE TABLE empty_ids(id INTEGER PRIMARY KEY, name TEXT)")
    finally ddl.close()

    val executions = List(
      insertBoundLong(c, "neg", -5L, "seed"),
      executeDirect(c, "INSERT INTO neg(name) VALUES ('auto')"),
      insertBoundLong(c, "mx", Long.MaxValue, "seed"),
      insertBoundLong(c, "mx", 1L, "used-one"),
      insertBoundLong(c, "mx", 2L, "used-two"),
      executeDirect(c, "INSERT INTO mx(name) VALUES ('auto')"),
      executeDirect(c, "INSERT INTO empty_ids(name) VALUES ('auto')"),
    )
    AutoOutcome(
      executions,
      readOutcome(c, "SELECT rowid, id, name FROM neg ORDER BY id"),
      readOutcome(c, "SELECT id FROM mx WHERE name = 'auto'"),
      readOutcome(c, "SELECT id, name FROM empty_ids ORDER BY id"),
    )

  private def namedAuto(outcomes: List[ExecOutcome]): String =
    autoExecutionNames.zip(outcomes).map((name, outcome) => s"$name=$outcome").mkString(", ")

  private def validMaxFallback(outcome: ReadOutcome): Boolean =
    if outcome.status != "rows" || outcome.rows.length != 1 then false
    else
      try
        val id = java.lang.Long.parseLong(outcome.rows.head)
        id > 0L && id != 1L && id != 2L && id != Long.MaxValue
      catch case _: NumberFormatException => false

  test("empty, negative-only, and occupied Long.Max auto-rowid boundaries match SQLite properties"):
    withTempDb("auto"): db =>
      val (scOutcome, refOutcome) = runBoth(db)(runAutoMatrix)
      val fileRef = refConn(s"jdbc:sqlite:${db.toString}")
      val (persistedNegative, persistedMax, persistedEmpty, integrity) =
        try
          (
            readOutcome(fileRef, "SELECT rowid, id, name FROM neg ORDER BY id"),
            readOutcome(fileRef, "SELECT id FROM mx WHERE name = 'auto'"),
            readOutcome(fileRef, "SELECT id, name FROM empty_ids ORDER BY id"),
            readOutcome(fileRef, "PRAGMA integrity_check"),
          )
        finally fileRef.close()

      // The Long.Max fallback is intentionally stochastic in SQLite, so compare
      // its observable invariant rather than requiring the same random rowid.
      assert(scOutcome.executions == refOutcome.executions,
        s"scljet=${namedAuto(scOutcome.executions)}\nsqlite=${namedAuto(refOutcome.executions)}")
      assert(scOutcome.negative == refOutcome.negative,
        s"scljet=${scOutcome.negative} sqlite=${refOutcome.negative}")
      assert(scOutcome.empty == refOutcome.empty,
        s"scljet=${scOutcome.empty} sqlite=${refOutcome.empty}")

      val expectedExecutions = autoExecutionNames.map(_ => Updated(1))
      assert(refOutcome.executions == expectedExecutions,
        s"expected=${namedAuto(expectedExecutions)} sqlite=${namedAuto(refOutcome.executions)}")
      assert(refOutcome.negative ==
        ReadOutcome("rows", List("-5|-5|seed", "-4|-4|auto")))
      assert(refOutcome.empty == ReadOutcome("rows", List("1|auto")))
      assert(validMaxFallback(scOutcome.maxFallback),
        s"SclJet fallback must be an unused positive rowid, got ${scOutcome.maxFallback}")
      assert(validMaxFallback(refOutcome.maxFallback),
        s"SQLite fallback must be an unused positive rowid, got ${refOutcome.maxFallback}")

      assert(persistedNegative == scOutcome.negative,
        s"persisted=$persistedNegative in-process=${scOutcome.negative}")
      assert(persistedMax == scOutcome.maxFallback,
        s"persisted=$persistedMax in-process=${scOutcome.maxFallback}")
      assert(persistedEmpty == scOutcome.empty,
        s"persisted=$persistedEmpty in-process=${scOutcome.empty}")
      assert(integrity == ReadOutcome("rows", List("ok")), s"integrity=$integrity")

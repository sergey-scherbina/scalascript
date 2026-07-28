package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, SQLException, Types}
import scala.util.control.NonFatal

/** Live sqlite-jdbc oracle for SC-1b/SC-1c scalar SQL semantics.
 *
 *  Portable conformance pins the target-neutral evaluator on INT and JS. This
 *  suite sends the same SQL and bound JVM values through `jdbc:scljet:` and
 *  the build-pinned Xerial sqlite-jdbc 3.45.3.0, compares both complete
 *  outcomes first, then
 *  checks the reference expectation. After the SclJet connection closes, real
 *  SQLite reruns the query matrix on the persisted file and checks integrity.
 *
 *  This green matrix does not classify known-red joined-outer correlated
 *  subqueries, correlated-subquery error propagation, or the `==` grammar
 *  alias. Those remain explicit BUGS/SC-8 work rather than "known failure"
 *  branches that could suppress a real comparison here.
 */
class ScljetScalarSemanticsDifferentialTest extends AnyFunSuite:

  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  private sealed trait SqlOutcome
  private final case class Rows(columns: Int, values: List[String]) extends SqlOutcome
  private final case class Updated(count: Int) extends SqlOutcome
  private final case class Failed(phase: String, category: String) extends SqlOutcome

  private final case class NamedOutcome(name: String, outcome: SqlOutcome)
  private final case class ScenarioOutcome(setup: String, observations: List[NamedOutcome])
  private final case class QueryCase(
      name: String,
      sql: String,
      bind: PreparedStatement => Unit,
      expected: SqlOutcome,
  )

  private val noBind: PreparedStatement => Unit = _ => ()

  private def q(name: String, sql: String, expectedRows: String*): QueryCase =
    val columns =
      if expectedRows.nonEmpty then expectedRows.head.count(_ == '|') + 1
      else 1
    QueryCase(name, sql, noBind, Rows(columns, expectedRows.toList))

  private def qe(name: String, sql: String, columns: Int, expectedRows: String*): QueryCase =
    QueryCase(name, sql, noBind, Rows(columns, expectedRows.toList))

  private def qb(
      name: String,
      sql: String,
      bind: PreparedStatement => Unit,
      expectedRows: String*,
  ): QueryCase =
    val columns =
      if expectedRows.nonEmpty then expectedRows.head.count(_ == '|') + 1
      else 1
    QueryCase(name, sql, bind, Rows(columns, expectedRows.toList))

  private def withTempDb(name: String)(body: java.nio.file.Path => Unit): Unit =
    val dir = java.nio.file.Files.createTempDirectory(s"scljet-scalar-$name-")
    val db = dir.resolve("scalar.db")
    try body(db)
    finally
      java.nio.file.Files.deleteIfExists(db)
      java.nio.file.Files.deleteIfExists(dir)

  private def refConn(url: String): Connection =
    Class.forName("org.sqlite.JDBC")
    DriverManager.getConnection(url)

  private def errorCategory(error: SQLException): String =
    val state = Option(error.getSQLState).getOrElse("")
    val message = Option(error.getMessage).getOrElse("")
    val text = (error.getClass.getSimpleName + " " + state + " " + message).toLowerCase
    if text.contains("no such table") then "no-such-table"
    else if text.contains("mismatch") then "mismatch"
    else if text.contains("constraint") || text.contains("duplicate") || text.contains("unique") then "constraint"
    else if text.contains("syntax") || text.contains("parse") || text.contains("expected") then "syntax"
    else "other:" + error.getClass.getSimpleName + ":" + state + ":" + message

  private def hex(bytes: Array[Byte]): String =
    val chars = "0123456789abcdef"
    val out = new StringBuilder(bytes.length * 2)
    var i = 0
    while i < bytes.length do
      val value = bytes(i) & 0xff
      out.append(chars.charAt(value >>> 4))
      out.append(chars.charAt(value & 0x0f))
      i += 1
    out.toString

  private def cell(value: AnyRef): String = value match
    case null => "N"
    case bytes: Array[Byte] => "B:" + hex(bytes)
    case n: java.lang.Byte => "I:" + n.longValue
    case n: java.lang.Short => "I:" + n.longValue
    case n: java.lang.Integer => "I:" + n.longValue
    case n: java.lang.Long => "I:" + n.longValue
    case n: java.math.BigInteger => "I:" + n.toString
    case n: java.lang.Float => "R:" + java.lang.Double.toHexString(n.doubleValue)
    case n: java.lang.Double => "R:" + java.lang.Double.toHexString(n.doubleValue)
    case n: java.math.BigDecimal => "D:" + n.stripTrailingZeros.toPlainString
    case b: java.lang.Boolean => if b.booleanValue then "I:1" else "I:0"
    case s: String => "T:" + s
    case other => "O:" + other.getClass.getName + ":" + other.toString

  private def resultRows(rs: ResultSet): Rows =
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val columns = rs.getMetaData.getColumnCount
    while rs.next() do
      out += (1 to columns).map(i => cell(rs.getObject(i))).mkString("|")
    Rows(columns, out.toList)

  private def runQuery(c: Connection, query: QueryCase): SqlOutcome =
    val ps =
      try c.prepareStatement(query.sql)
      catch case error: SQLException => return Failed("prepare", errorCategory(error))
    try
      try query.bind(ps)
      catch case error: SQLException => return Failed("bind", errorCategory(error))
      val rs =
        try ps.executeQuery()
        catch case error: SQLException => return Failed("execute", errorCategory(error))
      try
        try resultRows(rs)
        catch case error: SQLException => Failed("iterate", errorCategory(error))
        catch case NonFatal(error) => Failed("iterate", throwableCategory(error))
      finally rs.close()
    finally ps.close()

  private def runUpdate(
      c: Connection,
      sql: String,
      bind: PreparedStatement => Unit = noBind,
  ): SqlOutcome =
    val ps =
      try c.prepareStatement(sql)
      catch case error: SQLException => return Failed("prepare", errorCategory(error))
    try
      try bind(ps)
      catch case error: SQLException => return Failed("bind", errorCategory(error))
      try Updated(ps.executeUpdate())
      catch case error: SQLException => Failed("execute", errorCategory(error))
    finally ps.close()

  private def setupSql(c: Connection, sql: String): Unit =
    val statement = c.createStatement()
    try statement.executeUpdate(sql)
    finally statement.close()

  private def setupPrepared(
      c: Connection,
      sql: String,
      bind: PreparedStatement => Unit,
  ): Unit =
    val ps = c.prepareStatement(sql)
    try
      bind(ps)
      val count = ps.executeUpdate()
      if count != 1 then throw SQLException(s"setup update count: expected 1, got $count")
    finally ps.close()

  private def observe(c: Connection, cases: List[QueryCase]): List[NamedOutcome] =
    cases.map(query => NamedOutcome(query.name, runQuery(c, query)))

  private def expected(cases: List[QueryCase]): List[NamedOutcome] =
    cases.map(query => NamedOutcome(query.name, query.expected))

  private def runBoth(
      db: java.nio.file.Path,
      run: Connection => ScenarioOutcome,
  ): (ScenarioOutcome, ScenarioOutcome) =
    var sc: Connection = null
    var ref: Connection = null
    var scOpenFailure: Option[ScenarioOutcome] = None
    var refOpenFailure: Option[ScenarioOutcome] = None
    try
      try sc = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      catch case NonFatal(error) =>
        scOpenFailure = Some(ScenarioOutcome("connect:" + throwableCategory(error), Nil))
      try ref = refConn("jdbc:sqlite::memory:")
      catch case NonFatal(error) =>
        refOpenFailure = Some(ScenarioOutcome("connect:" + throwableCategory(error), Nil))

      val scOutcome = scOpenFailure.getOrElse(captureScenario(run(sc)))
      val refOutcome = refOpenFailure.getOrElse(captureScenario(run(ref)))
      (scOutcome, refOutcome)
    finally
      try if ref != null then ref.close()
      finally if sc != null then sc.close()

  private def throwableCategory(error: Throwable): String = error match
    case sql: SQLException => errorCategory(sql)
    case other =>
      other.getClass.getName + ":" + Option(other.getMessage).getOrElse("")

  private def captureScenario(run: => ScenarioOutcome): ScenarioOutcome =
    try run
    catch case NonFatal(error) =>
      ScenarioOutcome("runtime:" + throwableCategory(error), Nil)

  private def reopenEvidence(
      db: java.nio.file.Path,
      cases: List[QueryCase],
  ): (List[NamedOutcome], SqlOutcome) =
    val ref = refConn(s"jdbc:sqlite:${db.toString}")
    try
      val persisted = observe(ref, cases)
      val integrity = runQuery(ref, q("integrity", "PRAGMA integrity_check", "T:ok"))
      (persisted, integrity)
    finally ref.close()

  private def assertCompared(
      sc: ScenarioOutcome,
      ref: ScenarioOutcome,
      expectedObservations: List[NamedOutcome],
      persisted: List[NamedOutcome],
      persistedExpected: List[NamedOutcome],
      integrity: SqlOutcome,
  ): Unit =
    // All engine and file observables have already been computed. Compare the
    // engines before using the pinned reference expectation to classify them.
    assert(sc == ref, s"scljet=$sc\nsqlite=$ref")
    assert(ref.setup == "ok", s"reference setup failed: ${ref.setup}")
    assert(ref.observations == expectedObservations,
      s"expected=$expectedObservations\nsqlite=${ref.observations}")
    assert(persisted == persistedExpected,
      s"persisted=$persisted\nexpected=$persistedExpected")
    assert(integrity == Rows(1, List("T:ok")), s"integrity=$integrity")

  // ── SC-1b: NULL / UNKNOWN ────────────────────────────────────────────────

  private def setupNullFixture(c: Connection, indexed: Boolean): Unit =
    setupSql(c, "CREATE TABLE t(id INTEGER PRIMARY KEY, v INTEGER, note TEXT)")
    val insertT = "INSERT INTO t VALUES (?,?,?)"
    setupPrepared(c, insertT, ps =>
      ps.setLong(1, 1L); ps.setLong(2, 1L); ps.setString(3, "a"))
    setupPrepared(c, insertT, ps =>
      ps.setLong(1, 2L); ps.setLong(2, 2L); ps.setString(3, "b"))
    setupPrepared(c, insertT, ps =>
      ps.setLong(1, 3L); ps.setNull(2, Types.NULL); ps.setNull(3, Types.NULL))
    setupPrepared(c, insertT, ps =>
      ps.setLong(1, 4L); ps.setLong(2, 0L); ps.setString(3, "z"))

    setupSql(c, "CREATE TABLE u(uid INTEGER PRIMARY KEY, x INTEGER)")
    setupPrepared(c, "INSERT INTO u VALUES (?,?)", ps =>
      ps.setLong(1, 10L); ps.setLong(2, 2L))
    setupPrepared(c, "INSERT INTO u VALUES (?,?)", ps =>
      ps.setLong(1, 11L); ps.setNull(2, Types.NULL))

    setupSql(c, "CREATE TABLE w(wid INTEGER PRIMARY KEY, y INTEGER)")
    setupPrepared(c, "INSERT INTO w VALUES (?,?)", ps =>
      ps.setLong(1, 20L); ps.setLong(2, 2L))
    setupPrepared(c, "INSERT INTO w VALUES (?,?)", ps =>
      ps.setLong(1, 21L); ps.setNull(2, Types.NULL))

    setupSql(c, "CREATE TABLE s(sid INTEGER PRIMARY KEY, owner INTEGER, x INTEGER)")
    val insertS = "INSERT INTO s VALUES (?,?,?)"
    setupPrepared(c, insertS, ps =>
      ps.setLong(1, 30L); ps.setLong(2, 1L); ps.setLong(3, 1L))
    setupPrepared(c, insertS, ps =>
      ps.setLong(1, 31L); ps.setLong(2, 2L); ps.setLong(3, 9L))
    setupPrepared(c, insertS, ps =>
      ps.setLong(1, 32L); ps.setLong(2, 2L); ps.setNull(3, Types.NULL))
    setupPrepared(c, insertS, ps =>
      ps.setLong(1, 33L); ps.setLong(2, 3L); ps.setNull(3, Types.NULL))

    if indexed then setupSql(c, "CREATE INDEX idx_t_v ON t(v)")

  private def nullCore(prefix: String): List[QueryCase] = List(
    q(s"$prefix-eq-null", "SELECT id FROM t WHERE v = NULL ORDER BY id"),
    q(s"$prefix-ne-null", "SELECT id FROM t WHERE v <> NULL ORDER BY id"),
    q(s"$prefix-gt-null", "SELECT id FROM t WHERE v > NULL ORDER BY id"),
    q(s"$prefix-in-empty", "SELECT id FROM t WHERE v IN () ORDER BY id"),
    q(s"$prefix-not-in-empty", "SELECT id FROM t WHERE v NOT IN () ORDER BY id",
      "I:1", "I:2", "I:3", "I:4"),
    q(s"$prefix-in-null", "SELECT id FROM t WHERE v IN (2, NULL) ORDER BY id", "I:2"),
    q(s"$prefix-not-in-null", "SELECT id FROM t WHERE v NOT IN (2, NULL) ORDER BY id"),
    q(s"$prefix-or-null", "SELECT id FROM t WHERE v = 1 OR v = NULL ORDER BY id", "I:1"),
    q(s"$prefix-and-null", "SELECT id FROM t WHERE v <> 2 AND v = NULL ORDER BY id"),
    q(s"$prefix-between-null", "SELECT id FROM t WHERE v BETWEEN NULL AND 2 ORDER BY id"),
    q(s"$prefix-not-like-null", "SELECT id FROM t WHERE note NOT LIKE NULL ORDER BY id"),
    q(s"$prefix-self-equality", "SELECT id FROM t WHERE v = v ORDER BY id",
      "I:1", "I:2", "I:4"),
    q(s"$prefix-nonempty-residual",
      "SELECT id FROM t WHERE v >= 0 AND NULL = NULL ORDER BY id"),
  )

  private val nullScanQueries: List[QueryCase] =
    List(
      q("scalar",
        "SELECT NULL = NULL, NULL <> NULL, NULL < 1, NOT (NULL = 1), " +
          "1 AND NULL, 0 AND NULL, 1 OR NULL, 0 OR NULL, " +
          "CASE NULL WHEN NULL THEN 1 ELSE 0 END",
        "N|N|N|N|N|I:0|I:1|N|I:0"),
      q("case-from",
        "SELECT id, CASE v WHEN NULL THEN 1 ELSE 0 END FROM t ORDER BY id",
        "I:1|I:0", "I:2|I:0", "I:3|I:0", "I:4|I:0"),
      qb("bound-scalar",
        "SELECT ? = ?, NOT (? = 1), 1 AND ?, 0 AND ?, 1 OR ?, 0 OR ?, " +
          "CASE ? WHEN ? THEN 1 ELSE 0 END",
        ps =>
          var i = 1
          while i <= 9 do
            ps.setNull(i, Types.NULL)
            i += 1,
        "N|N|N|I:0|I:1|N|I:0"),
      qb("bound-predicates",
        "SELECT id FROM t WHERE v = ? OR v IN (2, ?) OR " +
          "v BETWEEN ? AND 2 OR note LIKE ? ORDER BY id",
        ps =>
          ps.setNull(1, Types.NULL)
          ps.setNull(2, Types.NULL)
          ps.setNull(3, Types.NULL)
          ps.setNull(4, Types.NULL),
        "I:2"),
      q("rowid-residual-null",
        "SELECT id FROM t WHERE id = 3 AND v = NULL ORDER BY id"),
      q("limit-pushdown-null",
        "SELECT id FROM t WHERE v = NULL LIMIT 1"),
      q("case-group",
        "SELECT v, CASE v WHEN NULL THEN 1 ELSE 0 END " +
          "FROM t GROUP BY v ORDER BY v",
        "N|I:0", "I:0|I:0", "I:1|I:0", "I:2|I:0"),
    ) ++ nullCore("scan") ++ List(
      q("in-subquery",
        "SELECT id FROM t WHERE v IN (SELECT x FROM u) ORDER BY id", "I:2"),
      q("not-in-subquery",
        "SELECT id FROM t WHERE v NOT IN (SELECT x FROM u) ORDER BY id"),
      q("empty-not-in-subquery",
        "SELECT id FROM t WHERE v NOT IN (SELECT x FROM u WHERE uid = 999) ORDER BY id",
        "I:1", "I:2", "I:3", "I:4"),
      q("correlated-in",
        "SELECT id FROM t WHERE v IN (SELECT x FROM s WHERE owner = t.id) ORDER BY id",
        "I:1"),
      q("correlated-not-in",
        "SELECT id FROM t WHERE v NOT IN (SELECT x FROM s WHERE owner = t.id) ORDER BY id",
        "I:4"),
      q("correlated-scalar",
        "SELECT id FROM t WHERE v = (SELECT x FROM s WHERE owner = t.id) ORDER BY id",
        "I:1"),
      q("correlated-exists-null",
        "SELECT id FROM t WHERE EXISTS " +
          "(SELECT sid FROM s WHERE owner = t.id AND x = NULL) ORDER BY id"),
      q("scalar-subquery",
        "SELECT id FROM t WHERE v = (SELECT x FROM u WHERE x IS NULL) ORDER BY id"),
      q("scalar-subquery-ne-null",
        "SELECT id FROM t WHERE v <> (SELECT x FROM u WHERE x IS NULL) ORDER BY id"),
      q("scalar-empty-ne",
        "SELECT id FROM t WHERE v <> (SELECT x FROM u WHERE uid = 999) ORDER BY id"),
      q("join",
        "SELECT t.id, u.uid FROM t JOIN u ON t.v = u.x ORDER BY t.id, u.uid",
        "I:2|I:10"),
      q("left-join",
        "SELECT t.id, u.uid FROM t LEFT JOIN u ON t.v = u.x ORDER BY t.id, u.uid",
        "I:1|N", "I:2|I:10", "I:3|N", "I:4|N"),
      q("join-case-order",
        "SELECT t.id FROM t LEFT JOIN u ON t.v = u.x " +
          "ORDER BY CASE u.x WHEN NULL THEN 0 ELSE 1 END, t.id",
        "I:1", "I:2", "I:3", "I:4"),
      q("join-case-group",
        "SELECT t.v, CASE MIN(u.x) WHEN NULL THEN 1 ELSE 0 END " +
          "FROM t LEFT JOIN u ON t.v = u.x GROUP BY t.v ORDER BY t.v",
        "N|I:0", "I:0|I:0", "I:1|I:0", "I:2|I:0"),
      q("join3",
        "SELECT t.id, u.uid, w.wid FROM t " +
          "JOIN u ON t.v = u.x JOIN w ON u.x = w.y ORDER BY t.id, u.uid, w.wid",
        "I:2|I:10|I:20"),
      q("join3-case",
        "SELECT t.id, CASE w.y WHEN NULL THEN 1 ELSE 0 END FROM t " +
          "LEFT JOIN u ON t.v = u.x LEFT JOIN w ON u.x = w.y ORDER BY t.id",
        "I:1|I:0", "I:2|I:0", "I:3|I:0", "I:4|I:0"),
      q("join3-case-group",
        "SELECT t.v, CASE MIN(w.y) WHEN NULL THEN 1 ELSE 0 END FROM t " +
          "LEFT JOIN u ON t.v = u.x LEFT JOIN w ON u.x = w.y " +
          "GROUP BY t.v ORDER BY t.v",
        "N|I:0", "I:0|I:0", "I:1|I:0", "I:2|I:0"),
      qe("join-where-null",
        "SELECT t.id, u.uid FROM t LEFT JOIN u ON t.v = u.x " +
          "WHERE u.x = NULL ORDER BY t.id, u.uid", 2),
      qe("having-null",
        "SELECT v, COUNT(*) FROM t GROUP BY v HAVING v = NULL ORDER BY v", 2),
      qe("join-having-null",
        "SELECT t.v, COUNT(*) FROM t LEFT JOIN u ON t.v = u.x " +
          "GROUP BY t.v HAVING t.v = NULL ORDER BY t.v", 2),
      qe("join3-having-null",
        "SELECT t.v, COUNT(*) FROM t LEFT JOIN u ON t.v = u.x " +
          "LEFT JOIN w ON u.x = w.y GROUP BY t.v HAVING t.v = NULL ORDER BY t.v", 2),
    )

  private def runNullScan(c: Connection): ScenarioOutcome =
    try
      setupNullFixture(c, indexed = false)
      val before = observe(c, nullScanQueries)
      val update = NamedOutcome("mutation-update",
        runUpdate(c, "UPDATE t SET note = 'bad' WHERE v = NULL"))
      val afterUpdateCase = q("after-update",
        "SELECT id, note FROM t ORDER BY id",
        "I:1|T:a", "I:2|T:b", "I:3|N", "I:4|T:z")
      val afterUpdate = NamedOutcome(afterUpdateCase.name, runQuery(c, afterUpdateCase))
      val delete = NamedOutcome("mutation-delete",
        runUpdate(c, "DELETE FROM t WHERE v <> NULL"))
      val afterDeleteCase = q("after-delete",
        "SELECT id FROM t ORDER BY id", "I:1", "I:2", "I:3", "I:4")
      val afterDelete = NamedOutcome(afterDeleteCase.name, runQuery(c, afterDeleteCase))
      ScenarioOutcome("ok", before ++ List(update, afterUpdate, delete, afterDelete))
    catch case error: SQLException =>
      ScenarioOutcome("setup:" + errorCategory(error), Nil)

  private val nullScanExpected: List[NamedOutcome] =
    expected(nullScanQueries) ++ List(
      NamedOutcome("mutation-update", Updated(0)),
      NamedOutcome("after-update", Rows(2, List("I:1|T:a", "I:2|T:b", "I:3|N", "I:4|T:z"))),
      NamedOutcome("mutation-delete", Updated(0)),
      NamedOutcome("after-delete", Rows(1, List("I:1", "I:2", "I:3", "I:4"))),
    )

  private def runNullIndex(c: Connection): ScenarioOutcome =
    try
      setupNullFixture(c, indexed = true)
      ScenarioOutcome("ok", observe(c, nullCore("index")))
    catch case error: SQLException =>
      ScenarioOutcome("setup:" + errorCategory(error), Nil)

  test("NULL and UNKNOWN semantics match sqlite-jdbc across scalar, scan, join, subquery, and DML paths"):
    withTempDb("null-scan"): db =>
      val (sc, ref) = runBoth(db, runNullScan)
      val (persisted, integrity) = reopenEvidence(db, nullScanQueries)
      assertCompared(sc, ref, nullScanExpected, persisted, expected(nullScanQueries), integrity)

  test("NULL and UNKNOWN residual predicates match sqlite-jdbc with an index"):
    val cases = nullCore("index")
    val fileCases = cases :+ q(
      "persisted-index",
      "SELECT name FROM sqlite_schema WHERE type = 'index' ORDER BY name",
      "T:idx_t_v",
    )
    withTempDb("null-index"): db =>
      val (sc, ref) = runBoth(db, runNullIndex)
      val (persisted, integrity) = reopenEvidence(db, fileCases)
      assertCompared(sc, ref, expected(cases), persisted, expected(fileCases), integrity)

  private def setupNullDmlFixture(c: Connection, indexed: Boolean): Unit =
    setupSql(c, "CREATE TABLE d(id INTEGER PRIMARY KEY, v INTEGER, note TEXT)")
    val insert = "INSERT INTO d VALUES (?,?,?)"
    setupPrepared(c, insert, ps =>
      ps.setLong(1, 1L); ps.setLong(2, 1L); ps.setString(3, "seed"))
    setupPrepared(c, insert, ps =>
      ps.setLong(1, 2L); ps.setLong(2, 2L); ps.setString(3, "seed"))
    setupPrepared(c, insert, ps =>
      ps.setLong(1, 3L); ps.setNull(2, Types.NULL); ps.setString(3, "seed"))
    setupPrepared(c, insert, ps =>
      ps.setLong(1, 4L); ps.setLong(2, 0L); ps.setString(3, "seed"))
    if indexed then setupSql(c, "CREATE INDEX idx_d_v ON d(v)")

  private val nullDmlFinal = q(
    "dml-final",
    "SELECT id, v, note FROM d ORDER BY id",
    "I:1|I:1|T:ok", "I:2|I:2|T:ok", "I:3|N|T:ok", "I:4|I:0|T:ok",
  )

  private def runNullDml(c: Connection, indexed: Boolean): ScenarioOutcome =
    try
      setupNullDmlFixture(c, indexed)
      val caseUpdate = NamedOutcome(
        "case-update",
        runUpdate(c,
          "UPDATE d SET note = CASE v WHEN NULL THEN 'bad' ELSE 'ok' END"),
      )
      val boundUpdate = NamedOutcome(
        "bound-null-update",
        runUpdate(c, "UPDATE d SET note = 'bound' WHERE v = ?",
          ps => ps.setNull(1, Types.NULL)),
      )
      val notInDelete = NamedOutcome(
        "not-in-null-delete",
        runUpdate(c, "DELETE FROM d WHERE v NOT IN (2, NULL)"),
      )
      val finalRows = NamedOutcome(nullDmlFinal.name, runQuery(c, nullDmlFinal))
      ScenarioOutcome("ok", List(caseUpdate, boundUpdate, notInDelete, finalRows))
    catch case error: SQLException =>
      ScenarioOutcome("setup:" + errorCategory(error), Nil)

  private val nullDmlExpected = List(
    NamedOutcome("case-update", Updated(4)),
    NamedOutcome("bound-null-update", Updated(0)),
    NamedOutcome("not-in-null-delete", Updated(0)),
    NamedOutcome(nullDmlFinal.name, nullDmlFinal.expected),
  )

  private def checkNullDml(indexed: Boolean, name: String): Unit =
    val fileCases =
      if indexed then List(
        nullDmlFinal,
        q("persisted-index", "SELECT name FROM sqlite_schema " +
          "WHERE type = 'index' ORDER BY name", "T:idx_d_v"),
      )
      else List(nullDmlFinal)
    withTempDb(name): db =>
      val (sc, ref) = runBoth(db, runNullDml(_, indexed))
      val (persisted, integrity) = reopenEvidence(db, fileCases)
      assertCompared(sc, ref, nullDmlExpected,
        persisted, expected(fileCases), integrity)

  test("NULL CASE and predicate DML semantics match sqlite-jdbc without an index"):
    checkNullDml(indexed = false, name = "null-dml-scan")

  test("NULL CASE and predicate DML semantics match sqlite-jdbc with a persisted index"):
    checkNullDml(indexed = true, name = "null-dml-index")

  // ── SC-1c: exact INTEGER/REAL and BLOB comparison ────────────────────────

  private def setupCompareFixture(c: Connection, indexed: Boolean): Unit =
    setupSql(c, "CREATE TABLE cmp(id INTEGER PRIMARY KEY, n, b BLOB)")
    val insert = "INSERT INTO cmp VALUES (?,?,?)"
    def add(id: Long, bindN: PreparedStatement => Unit, blob: Array[Byte]): Unit =
      setupPrepared(c, insert, ps =>
        ps.setLong(1, id)
        bindN(ps)
        ps.setBytes(3, blob))

    add(1L, _.setLong(2, 9007199254740992L), Array[Byte](1))
    add(2L, _.setLong(2, 9007199254740993L), Array[Byte](2))
    add(3L, _.setDouble(2, 9007199254740992.0), Array[Byte](1, 0))
    add(4L, _.setLong(2, Long.MaxValue), Array[Byte](0xff.toByte))
    add(5L, _.setDouble(2, 9223372036854775808.0), Array[Byte](0))
    add(6L, _.setLong(2, Long.MinValue), Array[Byte](0x80.toByte))
    add(7L, _.setDouble(2, -9223372036854775808.0), Array[Byte](0x7f.toByte))

    setupSql(c, "CREATE TABLE ref(rid INTEGER PRIMARY KEY, n, b BLOB)")
    setupPrepared(c, "INSERT INTO ref VALUES (?,?,?)", ps =>
      ps.setLong(1, 100L)
      ps.setLong(2, 9007199254740993L)
      ps.setBytes(3, Array[Byte](1)))
    setupPrepared(c, "INSERT INTO ref VALUES (?,?,?)", ps =>
      ps.setLong(1, 101L)
      ps.setDouble(2, -9223372036854775808.0)
      ps.setBytes(3, Array[Byte](1, 0)))
    if indexed then
      setupSql(c, "CREATE INDEX idx_cmp_n ON cmp(n)")
      setupSql(c, "CREATE INDEX idx_cmp_b ON cmp(b)")

  private def compareCore(prefix: String): List[QueryCase] = List(
    qb(s"$prefix-eq-int",
      "SELECT id FROM cmp WHERE n = ? ORDER BY id",
      _.setLong(1, 9007199254740993L),
      "I:2"),
    qb(s"$prefix-lt-real-max",
      "SELECT id FROM cmp WHERE n < ? ORDER BY id",
      _.setDouble(1, 9223372036854775808.0),
      "I:1", "I:2", "I:3", "I:4", "I:6", "I:7"),
    qb(s"$prefix-eq-real-2pow53",
      "SELECT id FROM cmp WHERE n = ? ORDER BY id",
      _.setDouble(1, 9007199254740992.0),
      "I:1", "I:3"),
    qb(s"$prefix-eq-real-min",
      "SELECT id FROM cmp WHERE n = ? ORDER BY id",
      _.setDouble(1, -9223372036854775808.0),
      "I:6", "I:7"),
    qb(s"$prefix-blob-eq",
      "SELECT id FROM cmp WHERE b = ? ORDER BY id",
      _.setBytes(1, Array[Byte](1)),
      "I:1"),
    qb(s"$prefix-blob-lt",
      "SELECT id FROM cmp WHERE b < ? ORDER BY id",
      _.setBytes(1, Array[Byte](2)),
      "I:1", "I:3", "I:5"),
  )

  private val compareScanQueries: List[QueryCase] =
    compareCore("scan") ++ List(
      q("scan-order-n", "SELECT id FROM cmp ORDER BY n, id",
        "I:6", "I:7", "I:1", "I:3", "I:2", "I:4", "I:5"),
      q("scan-distinct-n", "SELECT COUNT(DISTINCT n) FROM cmp", "I:5"),
      q("scan-group-n",
        "SELECT MIN(id), MAX(id), COUNT(*) FROM cmp GROUP BY n ORDER BY n",
        "I:6|I:7|I:2", "I:1|I:3|I:2", "I:2|I:2|I:1",
        "I:4|I:4|I:1", "I:5|I:5|I:1"),
      q("scan-order-b", "SELECT id FROM cmp ORDER BY b, id",
        "I:5", "I:1", "I:3", "I:2", "I:7", "I:6", "I:4"),
      q("scan-distinct-b", "SELECT COUNT(DISTINCT b) FROM cmp", "I:7"),
      q("join-exact",
        "SELECT cmp.id, ref.rid FROM cmp JOIN ref ON cmp.n = ref.n " +
          "ORDER BY cmp.id, ref.rid",
        "I:2|I:100", "I:6|I:101", "I:7|I:101"),
      q("join-blob",
        "SELECT cmp.id, ref.rid FROM cmp JOIN ref ON cmp.b = ref.b " +
          "ORDER BY cmp.id, ref.rid",
        "I:1|I:100", "I:3|I:101"),
    )

  private def runCompare(
      c: Connection,
      indexed: Boolean,
      cases: List[QueryCase],
  ): ScenarioOutcome =
    try
      setupCompareFixture(c, indexed)
      ScenarioOutcome("ok", observe(c, cases))
    catch case error: SQLException =>
      ScenarioOutcome("setup:" + errorCategory(error), Nil)

  test("exact INTEGER/REAL and BLOB comparison matches sqlite-jdbc across relational operators"):
    withTempDb("compare-scan"): db =>
      val (sc, ref) = runBoth(db, runCompare(_, indexed = false, compareScanQueries))
      val (persisted, integrity) = reopenEvidence(db, compareScanQueries)
      assertCompared(sc, ref, expected(compareScanQueries),
        persisted, expected(compareScanQueries), integrity)

  test("exact INTEGER/REAL and BLOB predicates match sqlite-jdbc with persisted indexes"):
    val cases = compareCore("index")
    val fileCases = cases :+ q(
      "persisted-indexes",
      "SELECT name FROM sqlite_schema WHERE type = 'index' ORDER BY name",
      "T:idx_cmp_b", "T:idx_cmp_n",
    )
    withTempDb("compare-index"): db =>
      val (sc, ref) = runBoth(db, runCompare(_, indexed = true, cases))
      val (persisted, integrity) = reopenEvidence(db, fileCases)
      assertCompared(sc, ref, expected(cases),
        persisted, expected(fileCases), integrity)

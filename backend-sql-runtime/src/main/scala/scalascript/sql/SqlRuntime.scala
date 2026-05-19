package scalascript.sql

import java.sql.{Connection, ResultSet}

/** v1.26 — JDBC executor for `sql` fenced code blocks.
 *
 *  `execute` takes a JDBC `Connection`, a `?`-templated SQL string
 *  (already rewritten by `core/transform/SqlBindRewriter.rewriteJdbc`
 *  at compile time), and the ordered bind list.  Returns either a
 *  `Rows(Seq[Row])` for SELECT-family statements or an `UpdateCount`
 *  for DML / DDL.  Statement-type detection is by leading keyword —
 *  see `isResultSetProducer`.
 *
 *  This module is intentionally self-contained: no dependency on
 *  `core` / `ir` / `backend-spi`.  Callable from the tree-walking
 *  interpreter (`backend-interpreter`) and from JvmGen-emitted Scala
 *  code (`backend-jvm`) without pulling either into the other's
 *  classpath. */
object SqlRuntime:

  /** Execute a single SQL statement against `conn`.
   *
   *  Binds are applied in occurrence order to the `?` placeholders.
   *  `null` and `None` bind as SQL NULL.  Supported runtime types for
   *  binds: primitives (Int/Long/Double/Float/Boolean/Short/Byte),
   *  String, BigDecimal / BigInt, byte arrays, `java.time` instances
   *  (LocalDate, LocalDateTime, LocalTime, Instant, OffsetDateTime,
   *  ZonedDateTime), `java.util.UUID`, plus `Option[T]` wrappers
   *  around any of the above.  Other values fall through to
   *  `setObject` and rely on the driver. */
  def execute(conn: Connection, sql: String, binds: List[Any]): SqlResult =
    val ps = conn.prepareStatement(sql)
    try
      Jdbc.bindAll(ps, binds)
      if isResultSetProducer(sql) then
        SqlResult.Rows(collectRows(ps.executeQuery()))
      else
        SqlResult.UpdateCount(ps.executeUpdate())
    finally
      ps.close()

  /** True when `sql`'s leading non-whitespace keyword indicates a
   *  result-set-producing statement.  Per SPEC.md § 3.3.1: SELECT,
   *  WITH (CTE), VALUES, SHOW, EXPLAIN — case-insensitive.  Comments
   *  preceding the keyword are not stripped: a query starting with
   *  `/* note */ SELECT …` is classified as DML by this heuristic.
   *  In practice every `sql` block we'd execute is single-statement
   *  and lacks leading comments; the rewriter doesn't insert any. */
  def isResultSetProducer(sql: String): Boolean =
    val trimmed = sql.stripLeading()
    val firstWord =
      val end = trimmed.indexWhere(c => c.isWhitespace || c == '(')
      if end < 0 then trimmed else trimmed.substring(0, end)
    firstWord.toUpperCase match
      case "SELECT" | "WITH" | "VALUES" | "SHOW" | "EXPLAIN" => true
      case _                                                  => false

  // ─── ResultSet → Row materialisation ────────────────────────────────

  private def collectRows(rs: ResultSet): Vector[Row] =
    try
      val meta = rs.getMetaData
      val cols = (1 to meta.getColumnCount).map(meta.getColumnLabel).toVector
      val buf  = Vector.newBuilder[Row]
      while rs.next() do
        val vals = (1 to cols.length).map(i => normalize(rs.getObject(i))).toVector
        buf += Row(vals, cols)
      buf.result()
    finally
      rs.close()

  /** Lift legacy `java.sql.{Date, Time, Timestamp}` results into their
   *  modern `java.time.*` equivalents so the Row API speaks one
   *  consistent vocabulary regardless of driver version.  Drivers that
   *  already return `java.time.*` directly (modern PG, H2 in some
   *  configurations) hit the identity branch.  `Timestamp` →
   *  `LocalDateTime` is correct for `TIMESTAMP WITHOUT TIME ZONE`;
   *  zoned-timestamp columns return `OffsetDateTime` directly from
   *  the driver and skip this normalisation. */
  private def normalize(v: Any): Any = v match
    case d: java.sql.Date      => d.toLocalDate
    case t: java.sql.Time      => t.toLocalTime
    case ts: java.sql.Timestamp => ts.toLocalDateTime
    case other                 => other

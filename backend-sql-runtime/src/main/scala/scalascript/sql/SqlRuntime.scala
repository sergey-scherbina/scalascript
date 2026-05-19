package scalascript.sql

import java.sql.{Connection, PreparedStatement, ResultSet, Types}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZonedDateTime}

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
      bindAll(ps, binds)
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

  // ─── PreparedStatement binding ──────────────────────────────────────

  private def bindAll(ps: PreparedStatement, binds: List[Any]): Unit =
    var i = 1 // JDBC positions are 1-based
    var rem = binds
    while rem.nonEmpty do
      bindOne(ps, i, rem.head)
      i   += 1
      rem  = rem.tail

  /** Bind one positional parameter.  Unwraps `Option[T]` recursively,
   *  treats `null` / `None` as SQL NULL, dispatches on the runtime
   *  type for known JDBC mappings, falls back to `setObject` for the
   *  rest.  Time types use the typed `setObject(i, value, jdbcType)`
   *  form so drivers without `JSR-310` support still get the right
   *  SQL type. */
  private def bindOne(ps: PreparedStatement, i: Int, v: Any): Unit = v match
    case null | None        => ps.setNull(i, Types.NULL)
    case Some(inner)        => bindOne(ps, i, inner)

    case s: String          => ps.setString(i, s)
    case b: Boolean         => ps.setBoolean(i, b)
    case b: Byte            => ps.setByte(i, b)
    case s: Short           => ps.setShort(i, s)
    case n: Int             => ps.setInt(i, n)
    case n: Long            => ps.setLong(i, n)
    case f: Float           => ps.setFloat(i, f)
    case d: Double          => ps.setDouble(i, d)

    case bd: BigDecimal     => ps.setBigDecimal(i, bd.bigDecimal)
    case bi: BigInt         => ps.setBigDecimal(i, java.math.BigDecimal(bi.bigInteger))
    case bd: java.math.BigDecimal => ps.setBigDecimal(i, bd)

    case bs: Array[Byte]    => ps.setBytes(i, bs)

    case d: LocalDate       => ps.setObject(i, d, Types.DATE)
    case t: LocalTime       => ps.setObject(i, t, Types.TIME)
    case dt: LocalDateTime  => ps.setObject(i, dt, Types.TIMESTAMP)
    case t: Instant         => ps.setObject(i, t, Types.TIMESTAMP_WITH_TIMEZONE)
    case t: OffsetDateTime  => ps.setObject(i, t, Types.TIMESTAMP_WITH_TIMEZONE)
    case t: ZonedDateTime   => ps.setObject(i, t, Types.TIMESTAMP_WITH_TIMEZONE)

    case u: java.util.UUID  => ps.setObject(i, u)

    case other              => ps.setObject(i, other)

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

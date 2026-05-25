package scalascript.sql

import java.sql.{Connection, ResultSet}
import scalascript.typeddata.{RowCodec, RowValue}

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

  /** Execute a result-set-producing SQL statement and decode every row through
   *  the shared typed-data `RowCodec[A]` layer.
   *
   *  This is the first typed read path for the SQL runtime. It intentionally
   *  keeps statement execution, bind handling, and result-set materialisation
   *  identical to `execute`; only the final row projection changes. */
  def query[A](conn: Connection, sql: String, binds: List[Any])(using codec: RowCodec[A]): Vector[A] =
    execute(conn, sql, binds) match
      case SqlResult.Rows(rows) =>
        rows.iterator.map(row =>
          codec.decode(row.toRowValueMap) match
            case Right(value) => value
            case Left(error) => throw RowProjectionError(error.render)
        ).toVector
      case SqlResult.UpdateCount(count) =>
        throw RowProjectionError(s"typed query expected rows, got update count $count")

  /** Insert one typed value by encoding it through `RowCodec[A]`.
   *
   *  This helper deliberately stays small: table/column names are explicit SQL
   *  identifiers, values are still JDBC bind parameters, and no key/schema
   *  discovery is attempted. */
  def insert[A](conn: Connection, table: String, value: A)(using codec: RowCodec[A]): Int =
    val tableSql = validateIdentifierPath(table, "table")
    val row = codec.encode(value).toVector
    if row.isEmpty then throw IllegalArgumentException("typed insert requires at least one column")
    val columns = row.map((name, _) => validateIdentifier(name, "column"))
    val placeholders = List.fill(row.size)("?").mkString(", ")
    val sql = s"INSERT INTO $tableSql (${columns.mkString(", ")}) VALUES ($placeholders)"
    execute(conn, sql, row.map((_, v) => rowValueToBind(v)).toList) match
      case SqlResult.UpdateCount(count) => count
      case SqlResult.Rows(_) => throw RowProjectionError("typed insert expected update count, got rows")

  /** Update one typed value by encoding it through `RowCodec[A]`.
   *
   *  `keyColumn` is excluded from the SET list when present in the encoded row;
   *  the WHERE value is supplied separately so callers can update a modified
   *  copy without losing the original key. */
  def update[A](conn: Connection, table: String, keyColumn: String, keyValue: Any, value: A)(using codec: RowCodec[A]): Int =
    val tableSql = validateIdentifierPath(table, "table")
    val keySql = validateIdentifier(keyColumn, "key column")
    val row = codec.encode(value).toVector.filterNot((name, _) => name.equalsIgnoreCase(keyColumn))
    if row.isEmpty then throw IllegalArgumentException("typed update requires at least one non-key column")
    val assignments = row.map((name, _) => s"${validateIdentifier(name, "column")} = ?")
    val sql = s"UPDATE $tableSql SET ${assignments.mkString(", ")} WHERE $keySql = ?"
    val binds = row.map((_, v) => rowValueToBind(v)).toList :+ keyValue
    execute(conn, sql, binds) match
      case SqlResult.UpdateCount(count) => count
      case SqlResult.Rows(_) => throw RowProjectionError("typed update expected update count, got rows")

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

  private[sql] def toRowValue(v: Any): RowValue = v match
    case null => RowValue.Null
    case None => RowValue.Null
    case Some(value) => toRowValue(value)
    case value: java.lang.Boolean => RowValue.Bool(value.booleanValue)
    case value: java.lang.Byte => RowValue.Num(BigDecimal(value.toLong))
    case value: java.lang.Short => RowValue.Num(BigDecimal(value.toLong))
    case value: java.lang.Integer => RowValue.Num(BigDecimal(value.toLong))
    case value: java.lang.Long => RowValue.Num(BigDecimal(value.longValue))
    case value: java.lang.Float => RowValue.Num(BigDecimal(value.toDouble))
    case value: java.lang.Double => RowValue.Num(BigDecimal(value.doubleValue))
    case value: java.math.BigInteger => RowValue.Num(BigDecimal(value))
    case value: java.math.BigDecimal => RowValue.Num(BigDecimal(value))
    case value: BigInt => RowValue.Num(BigDecimal(value))
    case value: BigDecimal => RowValue.Num(value)
    case value: String => RowValue.Str(value)
    case value: Char => RowValue.Str(value.toString)
    case other => RowValue.Str(other.toString)

  private[sql] def rowValueToBind(value: RowValue): Any = value match
    case RowValue.Null => null
    case RowValue.Bool(v) => v
    case RowValue.Num(v) => v
    case RowValue.Str(v) => v

  private val Identifier = "[A-Za-z_][A-Za-z0-9_]*".r

  private[sql] def validateIdentifier(value: String, label: String): String =
    value match
      case Identifier() => value
      case _ => throw IllegalArgumentException(s"invalid SQL $label identifier `$value`")

  private[sql] def validateIdentifierPath(value: String, label: String): String =
    val parts = value.split("\\.", -1).toList
    if parts.nonEmpty then parts.map(validateIdentifier(_, label)).mkString(".")
    else throw IllegalArgumentException(s"invalid SQL $label identifier `$value`")

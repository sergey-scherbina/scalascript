package scalascript.sql

import java.sql.{PreparedStatement, Types}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZonedDateTime}

/** Shared JDBC primitives consumed by both `SqlRuntime.execute`
 *  (synchronous executor for `sql` fenced blocks) and `client-postgres`
 *  (Future-based PG client).  Public so downstream JDBC adapters can
 *  reuse the same bind-type matrix without duplicating it.
 *
 *  v1.26.1 — extracted from `SqlRuntime.bindAll` / `bindOne` so the
 *  type coverage stays in one place.  Adding a new bind type (e.g.
 *  `java.net.URI`) now only requires editing this file. */
object Jdbc:

  /** Bind every value in `binds` to `ps` at consecutive positions
   *  starting at index 1 (JDBC's positional convention). */
  def bindAll(ps: PreparedStatement, binds: Iterable[Any]): Unit =
    var i = 1
    val it = binds.iterator
    while it.hasNext do
      bindOne(ps, i, it.next())
      i += 1

  /** Bind one positional parameter at JDBC index `i` (1-based).
   *
   *  Handles, in order:
   *
   *    - `null`, `None` → SQL NULL.
   *    - `Some(inner)` → recurse on `inner` (multi-level Option
   *      unwrapping; matches what users intuit when they write
   *      `Some(Some(x))`).
   *    - Scala primitives (`String, Boolean, Byte, Short, Int,
   *      Long, Float, Double`) → typed JDBC setters.
   *    - Numeric wide types: `BigDecimal`, `BigInt`,
   *      `java.math.BigDecimal`.
   *    - `Array[Byte]` → `setBytes`.
   *    - `java.time.*` (LocalDate, LocalTime, LocalDateTime,
   *      Instant, OffsetDateTime, ZonedDateTime) → typed
   *      `setObject(i, value, JDBCType)` form so drivers without
   *      JSR-310 support still get the right SQL type.
   *    - `java.util.UUID` → `setObject` (most drivers map this
   *      correctly; PG does so natively, H2 stores as UUID type,
   *      SQLite converts to string).
   *    - Everything else → `setObject` and trust the driver. */
  def bindOne(ps: PreparedStatement, i: Int, v: Any): Unit = v match
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

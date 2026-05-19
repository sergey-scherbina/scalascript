package scalascript.db

import java.sql.ResultSet
import scala.deriving.Mirror
import scala.compiletime.*

/** Low-level per-column decoder from a JDBC ResultSet. */
trait ColumnDecoder[A]:
  def decode(rs: ResultSet, index: Int): A

object ColumnDecoder:
  // ── primitives ──────────────────────────────────────────────────────
  given ColumnDecoder[String]     = (rs, i) => rs.getString(i)
  given ColumnDecoder[Int]        = (rs, i) => rs.getInt(i)
  given ColumnDecoder[Long]       = (rs, i) => rs.getLong(i)
  given ColumnDecoder[Short]      = (rs, i) => rs.getShort(i)
  given ColumnDecoder[Byte]       = (rs, i) => rs.getByte(i)
  given ColumnDecoder[Double]     = (rs, i) => rs.getDouble(i)
  given ColumnDecoder[Float]      = (rs, i) => rs.getFloat(i)
  given ColumnDecoder[Boolean]    = (rs, i) => rs.getBoolean(i)
  given ColumnDecoder[BigDecimal] = (rs, i) =>
    val bd = rs.getBigDecimal(i)
    if bd == null then null.asInstanceOf[BigDecimal] else BigDecimal(bd)
  given ColumnDecoder[BigInt]     = (rs, i) =>
    val bd = rs.getBigDecimal(i)
    if bd == null then null.asInstanceOf[BigInt] else BigInt(bd.toBigInteger)

  // ── time + uuid + binary ────────────────────────────────────────────
  // v1.26.1 — match the bind-side coverage in `scalascript.sql.Jdbc.bindOne`.
  // Modern drivers (PG, H2 in recent versions) return `java.time.*`
  // directly; older drivers return `java.sql.{Date,Time,Timestamp}` — we
  // normalise both shapes to `java.time.*` so user code speaks one
  // vocabulary regardless of driver version.
  given ColumnDecoder[java.time.LocalDate] = (rs, i) =>
    rs.getObject(i) match
      case null                   => null.asInstanceOf[java.time.LocalDate]
      case d: java.time.LocalDate => d
      case d: java.sql.Date       => d.toLocalDate
      case _                      => rs.getObject(i, classOf[java.time.LocalDate])
  given ColumnDecoder[java.time.LocalTime] = (rs, i) =>
    rs.getObject(i) match
      case null                   => null.asInstanceOf[java.time.LocalTime]
      case t: java.time.LocalTime => t
      case t: java.sql.Time       => t.toLocalTime
      case _                      => rs.getObject(i, classOf[java.time.LocalTime])
  given ColumnDecoder[java.time.LocalDateTime] = (rs, i) =>
    rs.getObject(i) match
      case null                            => null.asInstanceOf[java.time.LocalDateTime]
      case dt: java.time.LocalDateTime     => dt
      case ts: java.sql.Timestamp          => ts.toLocalDateTime
      case _                                => rs.getObject(i, classOf[java.time.LocalDateTime])
  given ColumnDecoder[java.time.Instant] = (rs, i) =>
    rs.getObject(i) match
      case null                              => null.asInstanceOf[java.time.Instant]
      case t: java.time.Instant              => t
      case ts: java.sql.Timestamp            => ts.toInstant
      case odt: java.time.OffsetDateTime     => odt.toInstant
      case _                                  => rs.getObject(i, classOf[java.time.Instant])
  given ColumnDecoder[java.time.OffsetDateTime] = (rs, i) =>
    rs.getObject(i, classOf[java.time.OffsetDateTime])
  given ColumnDecoder[java.util.UUID] = (rs, i) =>
    rs.getObject(i) match
      case null              => null.asInstanceOf[java.util.UUID]
      case u: java.util.UUID => u
      case s: String         => java.util.UUID.fromString(s)
      case other             => java.util.UUID.fromString(other.toString)
  given ColumnDecoder[Array[Byte]] = (rs, i) => rs.getBytes(i)

  // ── Option lift — generic over every base ColumnDecoder ─────────────
  // v1.26.1 — replaces the hand-written Option-of-each-primitive givens
  // with a single generic lift parameterised over any `ColumnDecoder[A]`.
  // `rs.wasNull` is the JDBC-standard "previous getX returned default-
  // for-NULL" signal, so primitive types (Int → 0, Long → 0L, …)
  // correctly map to `None` and not `Some(0)`.
  given optionDecoder[A](using inner: ColumnDecoder[A]): ColumnDecoder[Option[A]] =
    (rs, i) =>
      val v = inner.decode(rs, i)
      if rs.wasNull() then None else Option(v)

/** Decodes a full JDBC ResultSet row into type A. */
trait RowDecoder[A]:
  def decode(rs: ResultSet): A

object RowDecoder:
  // v1.26.1 — generic single-column lift: any `ColumnDecoder[A]` in scope
  // becomes a `RowDecoder[A]` reading column 1.  Replaces the per-primitive
  // hand-written givens and now covers every type ColumnDecoder supports
  // (primitives, java.time, UUID, Array[Byte], BigDecimal, BigInt, plus
  // their `Option[T]` lifts via `optionDecoder`).  Lower-priority than
  // hand-written tuple / `derives` givens so multi-column case classes
  // still pick the position-based product decoder.
  given singleColumn[A](using col: ColumnDecoder[A]): RowDecoder[A] =
    rs => col.decode(rs, 1)

  // Tuple decoders
  given [A, B](using da: ColumnDecoder[A], db: ColumnDecoder[B]): RowDecoder[(A, B)] =
    rs => (da.decode(rs, 1), db.decode(rs, 2))

  given [A, B, C](using da: ColumnDecoder[A], db: ColumnDecoder[B], dc: ColumnDecoder[C]): RowDecoder[(A, B, C)] =
    rs => (da.decode(rs, 1), db.decode(rs, 2), dc.decode(rs, 3))

  /** Auto-derive RowDecoder for case classes by column position (1-based). */
  inline def derived[A](using m: Mirror.ProductOf[A]): RowDecoder[A] =
    rs =>
      val decoders = summonDecoders[m.MirroredElemTypes]
      val values   = decoders.zipWithIndex.map { (d, i) => d.decode(rs, i + 1) }
      m.fromProduct(Tuple.fromArray(values.toArray))

  private inline def summonDecoders[T <: Tuple]: List[ColumnDecoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[ColumnDecoder[h]] :: summonDecoders[t]

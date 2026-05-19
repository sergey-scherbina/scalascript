package scalascript.db

import java.sql.ResultSet
import scala.deriving.Mirror
import scala.compiletime.*

/** Low-level per-column decoder from a JDBC ResultSet. */
trait ColumnDecoder[A]:
  def decode(rs: ResultSet, index: Int): A

object ColumnDecoder:
  given ColumnDecoder[String]  = (rs, i) => rs.getString(i)
  given ColumnDecoder[Int]     = (rs, i) => rs.getInt(i)
  given ColumnDecoder[Long]    = (rs, i) => rs.getLong(i)
  given ColumnDecoder[Double]  = (rs, i) => rs.getDouble(i)
  given ColumnDecoder[Boolean] = (rs, i) => rs.getBoolean(i)
  given ColumnDecoder[BigDecimal] = (rs, i) => BigDecimal(rs.getBigDecimal(i))
  given optString: ColumnDecoder[Option[String]]  = (rs, i) => Option(rs.getString(i))
  given optInt:    ColumnDecoder[Option[Int]]     = (rs, i) => if rs.getObject(i) == null then None else Some(rs.getInt(i))
  given optLong:   ColumnDecoder[Option[Long]]    = (rs, i) => if rs.getObject(i) == null then None else Some(rs.getLong(i))
  given optDouble: ColumnDecoder[Option[Double]]  = (rs, i) => if rs.getObject(i) == null then None else Some(rs.getDouble(i))
  given optBigDec: ColumnDecoder[Option[BigDecimal]] = (rs, i) => Option(rs.getBigDecimal(i)).map(BigDecimal(_))

/** Decodes a full JDBC ResultSet row into type A. */
trait RowDecoder[A]:
  def decode(rs: ResultSet): A

object RowDecoder:
  // Single-column decoders
  given RowDecoder[String]     = rs => rs.getString(1)
  given RowDecoder[Int]        = rs => rs.getInt(1)
  given RowDecoder[Long]       = rs => rs.getLong(1)
  given RowDecoder[Double]     = rs => rs.getDouble(1)
  given RowDecoder[Boolean]    = rs => rs.getBoolean(1)
  given RowDecoder[BigDecimal] = rs => BigDecimal(rs.getBigDecimal(1))

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

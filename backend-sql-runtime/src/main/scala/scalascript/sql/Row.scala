package scalascript.sql

import scala.deriving.Mirror
import scala.compiletime.{constValueTuple, erasedValue}

/** A single SQL result row produced by `SqlRuntime.execute`.
 *
 *  Carries column values in both positional and name-indexed form.
 *  Column names are case-preserved from the JDBC `ResultSetMetaData`
 *  (driver-dependent — H2 / SQLite return UPPER-CASE by default for
 *  unquoted identifiers; quoted identifiers retain their case).
 *  Name lookup is case-insensitive to insulate user code from this.
 *
 *  Type-safe projection into a case class via `row.as[T]` — the
 *  inline `as` method derives a column-name → field-name mapping at
 *  compile time using Scala 3's `Mirror.ProductOf`, then performs a
 *  runtime lookup for each field.  A missing column or a column whose
 *  runtime type cannot be coerced to the field's expected type
 *  raises `RowProjectionError` with a clear message naming the field
 *  and the actual value's class. */
final class Row(
  val values:  IndexedSeq[Any],
  val columns: IndexedSeq[String]
):
  /** Positional access (0-based). */
  def apply(index: Int): Any =
    if index < 0 || index >= values.length then
      throw RowProjectionError(
        s"column index $index out of bounds (row has ${values.length} columns)"
      )
    else values(index)

  /** Name-indexed access, case-insensitive. */
  def apply(name: String): Any =
    val idx = indexOfName(name)
    if idx < 0 then
      throw RowProjectionError(
        s"no column `$name` in row — have [${columns.mkString(", ")}]"
      )
    else values(idx)

  /** Returns the row as an immutable `Map[String, Any]` keyed by the
   *  original column names (case preserved from the JDBC metadata).
   *  Duplicate column names — possible in `SELECT a.x, b.x` — collide;
   *  the last-write-wins.  Use positional access if duplicates matter. */
  def toMap: Map[String, Any] =
    columns.zip(values).toMap

  /** Project this row into a case class by field name.  Field-to-column
   *  matching is case-insensitive.  Missing fields or type mismatches
   *  raise `RowProjectionError`. */
  inline def as[A](using m: Mirror.ProductOf[A]): A =
    Row.projectByName[A](this)

  override def toString: String =
    columns.zip(values).map { (c, v) => s"$c=$v" }.mkString("Row(", ", ", ")")

  private[sql] def indexOfName(name: String): Int =
    var i = 0
    while i < columns.length do
      if columns(i).equalsIgnoreCase(name) then return i
      i += 1
    -1

object Row:

  /** Compile-time field-name extraction + per-field runtime coercion. */
  inline def projectByName[A](row: Row)(using m: Mirror.ProductOf[A]): A =
    val fieldNames = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val rawValues  = fieldNames.map { fn =>
      val idx = row.indexOfName(fn)
      if idx < 0 then
        throw RowProjectionError(
          s"case class field `$fn` has no matching column in row — " +
            s"have [${row.columns.mkString(", ")}]"
        )
      else row.values(idx)
    }
    // `Tuple.fromArray` is the cheapest path from a raw `Array[Any]` to
    // an `m.MirroredElemTypes`-shaped tuple; the per-field coercion done
    // by `coerce` below is what actually validates types.
    val coerced =
      coerceAll[m.MirroredElemTypes, m.MirroredElemLabels](rawValues)
    m.fromProduct(Tuple.fromArray(coerced.toArray))

  /** Walk the tuple of expected field types in lockstep with the
   *  field-name labels, coercing each raw value to the matching type
   *  or raising `RowProjectionError`.  Recurses via inline pattern
   *  matching on the type-level tuple shape. */
  private inline def coerceAll[Ts <: Tuple, Ls <: Tuple](
    raw: List[Any]
  ): List[Any] =
    inline erasedValue[Ts] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  =>
        inline erasedValue[Ls] match
          case _: (l *: ls) =>
            val fieldName = scala.compiletime.constValue[l].asInstanceOf[String]
            val head      = coerce[t](raw.head, fieldName)
            head :: coerceAll[ts, ls](raw.tail)
          case _ =>
            // Defensive — labels and types tuples must align.  Never hit
            // in practice for a well-formed case class.
            throw RowProjectionError("field labels exhausted before types")

  /** Coerce a raw JDBC value to the expected field type at runtime.
   *  Compile-time-driven via Scala 3's `inline match` on the type.
   *  Numeric widening / narrowing follows the standard rules
   *  (`Int` accepts `Long` / `BigDecimal` / `BigInteger` when in range);
   *  `Option[T]` wraps `null` as `None` and recurses on the inner type.
   *  Anything not explicitly handled falls through to a checked cast. */
  private inline def coerce[A](v: Any, field: String): A =
    inline erasedValue[A] match
      case _: Option[t] =>
        (if v == null then None else Some(coerce[t](v, field))).asInstanceOf[A]
      case _: String    => coerceString(v, field).asInstanceOf[A]
      case _: Int       => coerceInt(v,    field).asInstanceOf[A]
      case _: Long      => coerceLong(v,   field).asInstanceOf[A]
      case _: Double    => coerceDouble(v, field).asInstanceOf[A]
      case _: Float     => coerceFloat(v,  field).asInstanceOf[A]
      case _: Boolean   => coerceBoolean(v, field).asInstanceOf[A]
      case _: Short     => coerceShort(v,  field).asInstanceOf[A]
      case _: Byte      => coerceByte(v,   field).asInstanceOf[A]
      case _: BigDecimal => coerceBigDecimal(v, field).asInstanceOf[A]
      case _            =>
        // Fallback: trust the JDBC driver to have returned the right shape.
        // Non-null required (Options have already been handled above).
        if v == null then
          throw RowProjectionError(s"field `$field` is null but type is non-Option")
        else v.asInstanceOf[A]

  // ─── per-type coercion helpers ──────────────────────────────────────

  private def coerceString(v: Any, field: String): String = v match
    case null      => throw nullErr(field, "String")
    case s: String => s
    case other     => other.toString

  private def coerceInt(v: Any, field: String): Int = v match
    case null               => throw nullErr(field, "Int")
    case i: java.lang.Integer => i
    case l: java.lang.Long    => l.toInt
    case s: java.lang.Short   => s.toInt
    case b: java.lang.Byte    => b.toInt
    case b: java.math.BigDecimal  => b.intValueExact
    case b: java.math.BigInteger  => b.intValueExact
    case s: String          => s.toInt
    case other              => throw typeErr(field, "Int", other)

  private def coerceLong(v: Any, field: String): Long = v match
    case null               => throw nullErr(field, "Long")
    case l: java.lang.Long    => l
    case i: java.lang.Integer => i.toLong
    case s: java.lang.Short   => s.toLong
    case b: java.lang.Byte    => b.toLong
    case b: java.math.BigDecimal  => b.longValueExact
    case b: java.math.BigInteger  => b.longValueExact
    case s: String          => s.toLong
    case other              => throw typeErr(field, "Long", other)

  private def coerceDouble(v: Any, field: String): Double = v match
    case null               => throw nullErr(field, "Double")
    case d: java.lang.Double  => d
    case f: java.lang.Float   => f.toDouble
    case i: java.lang.Integer => i.toDouble
    case l: java.lang.Long    => l.toDouble
    case b: java.math.BigDecimal => b.doubleValue
    case other              => throw typeErr(field, "Double", other)

  private def coerceFloat(v: Any, field: String): Float = v match
    case null               => throw nullErr(field, "Float")
    case f: java.lang.Float   => f
    case d: java.lang.Double  => d.toFloat
    case i: java.lang.Integer => i.toFloat
    case l: java.lang.Long    => l.toFloat
    case other              => throw typeErr(field, "Float", other)

  private def coerceBoolean(v: Any, field: String): Boolean = v match
    case null                  => throw nullErr(field, "Boolean")
    case b: java.lang.Boolean    => b
    case i: java.lang.Integer    => i != 0    // SQLite stores BOOL as INTEGER
    case s: String             =>
      s.toLowerCase match
        case "true" | "t" | "1" | "yes" | "y" => true
        case "false" | "f" | "0" | "no" | "n" => false
        case _ => throw typeErr(field, "Boolean", s)
    case other                 => throw typeErr(field, "Boolean", other)

  private def coerceShort(v: Any, field: String): Short = v match
    case null               => throw nullErr(field, "Short")
    case s: java.lang.Short   => s
    case i: java.lang.Integer => i.toShort
    case l: java.lang.Long    => l.toShort
    case other              => throw typeErr(field, "Short", other)

  private def coerceByte(v: Any, field: String): Byte = v match
    case null              => throw nullErr(field, "Byte")
    case b: java.lang.Byte   => b
    case s: java.lang.Short  => s.toByte
    case i: java.lang.Integer => i.toByte
    case other             => throw typeErr(field, "Byte", other)

  private def coerceBigDecimal(v: Any, field: String): BigDecimal = v match
    case null                    => throw nullErr(field, "BigDecimal")
    case b: java.math.BigDecimal => BigDecimal(b)
    case b: java.math.BigInteger => BigDecimal(b)
    case i: java.lang.Integer    => BigDecimal(i.toLong)
    case l: java.lang.Long       => BigDecimal(l)
    case d: java.lang.Double     => BigDecimal(d)
    case s: String               => BigDecimal(s)
    case other                   => throw typeErr(field, "BigDecimal", other)

  // ─── error helpers ──────────────────────────────────────────────────

  private def nullErr(field: String, expected: String): RowProjectionError =
    RowProjectionError(
      s"field `$field` requires `$expected` but column value is null — " +
        s"use `Option[$expected]` if NULLs are expected"
    )

  private def typeErr(field: String, expected: String, actual: Any): RowProjectionError =
    val cls = if actual == null then "null" else actual.getClass.getName
    RowProjectionError(
      s"field `$field` requires `$expected` but column value is `$actual` of class $cls"
    )

/** Raised when a JDBC row cannot be projected into a case class — most
 *  commonly a missing column, a NULL where the field is non-Option, or
 *  a runtime-type mismatch the coercion table doesn't cover.  The
 *  message names the offending field so users can find it without a
 *  stack trace. */
final class RowProjectionError(message: String) extends RuntimeException(message)

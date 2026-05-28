package scalascript.wire

/** Typed encode/decode bridge between Scala types and `WireValue`.
 *
 *  Composes with existing `JsonCodec[A]`, `DatasetCodec[A]`, `ObjectCodec[A]`,
 *  and route-client codecs. `schemaId` is a stable, content-addressed hash of
 *  the schema, used for binary compatibility checks in Phase 8.
 *
 *  Spec: docs/distributed-wire-protocol.md §Canonical Model */
trait WireCodec[A]:
  def encode(value: A): WireValue
  def decode(value: WireValue): Either[WireDecodeError, A]
  def schemaId: String

object WireCodec:

  given WireCodec[scala.Unit] with
    def encode(v: scala.Unit): WireValue = WireValue.Unit
    def decode(w: WireValue): Either[WireDecodeError, scala.Unit] = w match
      case WireValue.Unit | WireValue.Null => Right(())
      case other => Left(WireDecodeError.TypeMismatch("unit", WireValue.kindOf(other)))
    val schemaId = "unit:0"

  given WireCodec[Boolean] with
    def encode(v: Boolean): WireValue = WireValue.Bool(v)
    def decode(w: WireValue): Either[WireDecodeError, Boolean] = w match
      case WireValue.Bool(b) => Right(b)
      case other => Left(WireDecodeError.TypeMismatch("bool", WireValue.kindOf(other)))
    val schemaId = "bool:0"

  given WireCodec[Long] with
    def encode(v: Long): WireValue = WireValue.Int64(v)
    def decode(w: WireValue): Either[WireDecodeError, Long] = w match
      case WireValue.Int64(n)  => Right(n)
      case WireValue.Float64(d) => Right(d.toLong)
      case other => Left(WireDecodeError.TypeMismatch("int64", WireValue.kindOf(other)))
    val schemaId = "int64:0"

  given WireCodec[Int] with
    def encode(v: Int): WireValue = WireValue.Int64(v.toLong)
    def decode(w: WireValue): Either[WireDecodeError, Int] = w match
      case WireValue.Int64(n)  => Right(n.toInt)
      case WireValue.Float64(d) => Right(d.toInt)
      case other => Left(WireDecodeError.TypeMismatch("int64", WireValue.kindOf(other)))
    val schemaId = "int32:0"

  given WireCodec[Double] with
    def encode(v: Double): WireValue = WireValue.Float64(v)
    def decode(w: WireValue): Either[WireDecodeError, Double] = w match
      case WireValue.Float64(d) => Right(d)
      case WireValue.Int64(n)   => Right(n.toDouble)
      case other => Left(WireDecodeError.TypeMismatch("float64", WireValue.kindOf(other)))
    val schemaId = "float64:0"

  given WireCodec[String] with
    def encode(v: String): WireValue = WireValue.Str(v)
    def decode(w: WireValue): Either[WireDecodeError, String] = w match
      case WireValue.Str(s) => Right(s)
      case other => Left(WireDecodeError.TypeMismatch("string", WireValue.kindOf(other)))
    val schemaId = "string:0"

  given WireCodec[Array[Byte]] with
    def encode(v: Array[Byte]): WireValue = WireValue.Bytes(v)
    def decode(w: WireValue): Either[WireDecodeError, Array[Byte]] = w match
      case WireValue.Bytes(b) => Right(b)
      case other => Left(WireDecodeError.TypeMismatch("bytes", WireValue.kindOf(other)))
    val schemaId = "bytes:0"

  given [A](using ca: WireCodec[A]): WireCodec[Option[A]] with
    def encode(v: Option[A]): WireValue = v match
      case None    => WireValue.Null
      case Some(a) => ca.encode(a)
    def decode(w: WireValue): Either[WireDecodeError, Option[A]] = w match
      case WireValue.Null => Right(None)
      case other          => ca.decode(other).map(Some(_))
    val schemaId = s"option(${ca.schemaId}):0"

  given [A](using ca: WireCodec[A]): WireCodec[List[A]] with
    def encode(v: List[A]): WireValue = WireValue.Lst(v.map(ca.encode).toVector)
    def decode(w: WireValue): Either[WireDecodeError, List[A]] = w match
      case WireValue.Lst(vs) =>
        vs.foldLeft[Either[WireDecodeError, Vector[A]]](Right(Vector.empty)) { (acc, v) =>
          acc.flatMap(xs => ca.decode(v).map(xs :+ _))
        }.map(_.toList)
      case other => Left(WireDecodeError.TypeMismatch("list", WireValue.kindOf(other)))
    val schemaId = s"list(${ca.schemaId}):0"

  given [A](using ca: WireCodec[A]): WireCodec[Vector[A]] with
    def encode(v: Vector[A]): WireValue = WireValue.Lst(v.map(ca.encode))
    def decode(w: WireValue): Either[WireDecodeError, Vector[A]] = w match
      case WireValue.Lst(vs) =>
        vs.foldLeft[Either[WireDecodeError, Vector[A]]](Right(Vector.empty)) { (acc, v) =>
          acc.flatMap(xs => ca.decode(v).map(xs :+ _))
        }
      case other => Left(WireDecodeError.TypeMismatch("list", WireValue.kindOf(other)))
    val schemaId = s"vector(${ca.schemaId}):0"

  given [K, V](using ck: WireCodec[K], cv: WireCodec[V]): WireCodec[Map[K, V]] with
    def encode(m: Map[K, V]): WireValue =
      WireValue.Map(m.map { case (k, v) => ck.encode(k) -> cv.encode(v) }.toVector)
    def decode(w: WireValue): Either[WireDecodeError, Map[K, V]] = w match
      case WireValue.Map(entries) =>
        entries.foldLeft[Either[WireDecodeError, Vector[(K, V)]]](Right(Vector.empty)) {
          case (acc, (kw, vw)) =>
            for
              xs <- acc
              k  <- ck.decode(kw)
              v  <- cv.decode(vw)
            yield xs :+ (k -> v)
        }.map(_.toMap)
      case other => Left(WireDecodeError.TypeMismatch("map", WireValue.kindOf(other)))
    val schemaId = s"map(${ck.schemaId},${cv.schemaId}):0"

  /** Identity codec — `WireValue` encodes/decodes to itself. */
  given WireCodec[WireValue] with
    def encode(v: WireValue): WireValue = v
    def decode(w: WireValue): Either[WireDecodeError, WireValue] = Right(w)
    val schemaId = "wire:0"

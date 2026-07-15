package scalascript.interop.descriptor

private[descriptor] object WireSupport:
  type Result[A] = Either[DescriptorError, A]

  def obj(fields: (String, ujson.Value)*): ujson.Obj = ujson.Obj.from(fields)
  def arr(values: IterableOnce[ujson.Value]): ujson.Arr = ujson.Arr.from(values)
  def str(value: String): ujson.Str = ujson.Str(value)
  def bool(value: Boolean): ujson.Bool = ujson.Bool(value)
  def index(value: Int): ujson.Num = ujson.Num(value.toDouble)
  def optional[A](value: Option[A])(encode: A => ujson.Value): ujson.Arr =
    value match
      case Some(item) => ujson.Arr(encode(item))
      case None       => ujson.Arr()

  def tagged(tag: String, fields: (String, ujson.Value)*): ujson.Obj =
    ujson.Obj.from(("tag" -> ujson.Str(tag)) +: fields.toVector)

  def exactObject(
      value: ujson.Value,
      path: String,
      fields: String*
  ): Result[ujson.Obj] = value match
    case objectValue: ujson.Obj =>
      val expected = fields.toSet
      val actual = objectValue.value.keySet.toSet
      if actual == expected then Right(objectValue)
      else
        val missing = (expected -- actual).toVector.sorted
        val unknown = (actual -- expected).toVector.sorted
        Left(DescriptorError(
          "SCHEMA_MISMATCH",
          path,
          s"object fields differ; missing=${missing.mkString("[", ",", "]")}, " +
            s"unknown=${unknown.mkString("[", ",", "]")}"
        ))
    case _ => schema(path, "expected JSON object")

  def field(value: ujson.Obj, name: String): ujson.Value = value.value(name)

  def string(value: ujson.Value, path: String): Result[String] = value match
    case ujson.Str(text) => Right(text)
    case _               => schema(path, "expected JSON string")

  def boolean(value: ujson.Value, path: String): Result[Boolean] = value match
    case ujson.True  => Right(true)
    case ujson.False => Right(false)
    case _           => schema(path, "expected JSON boolean")

  def int(value: ujson.Value, path: String): Result[Int] = value match
    case ujson.Num(number)
        if number.isFinite && number >= 0.0 && number <= Int.MaxValue.toDouble &&
          number == math.rint(number) => Right(number.toInt)
    case _ => schema(path, "expected integral index in 0..2147483647")

  def vector[A](
      value: ujson.Value,
      path: String
  )(decode: (ujson.Value, String) => Result[A]): Result[Vector[A]] = value match
    case array: ujson.Arr =>
      traverse(array.value.toVector.zipWithIndex) { case (item, itemIndex) =>
        decode(item, s"$path[$itemIndex]")
      }
    case _ => schema(path, "expected JSON array")

  def option[A](
      value: ujson.Value,
      path: String
  )(decode: (ujson.Value, String) => Result[A]): Result[Option[A]] = value match
    case array: ujson.Arr if array.value.isEmpty => Right(None)
    case array: ujson.Arr if array.value.size == 1 =>
      decode(array.value.head, s"$path[0]").map(Some(_))
    case _: ujson.Arr => schema(path, "option must be [] or a single-element array")
    case _            => schema(path, "option must be encoded as an array")

  def tag(value: ujson.Value, path: String, fields: String*): Result[(ujson.Obj, String)] =
    exactObject(value, path, ("tag" +: fields)*).flatMap { objectValue =>
      string(field(objectValue, "tag"), s"$path.tag").map(objectValue -> _)
    }

  def enumValue[A](
      value: ujson.Value,
      path: String,
      cases: Map[String, A]
  ): Result[A] =
    tag(value, path).flatMap { case (_, caseName) =>
      cases.get(caseName).toRight(DescriptorError(
        "SCHEMA_MISMATCH",
        s"$path.tag",
        s"unsupported tag $caseName"
      ))
    }

  def wrappedString[A](
      value: ujson.Value,
      path: String
  )(build: String => A): Result[A] =
    exactObject(value, path, "value").flatMap { objectValue =>
      string(field(objectValue, "value"), s"$path.value").map(build)
    }

  def writeWrappedString(value: String): ujson.Obj = obj("value" -> str(value))

  def traverse[A, B](values: Vector[A])(f: A => Result[B]): Result[Vector[B]] =
    values.foldLeft[Result[Vector[B]]](Right(Vector.empty)) { (acc, value) =>
      for
        completed <- acc
        next <- f(value)
      yield completed :+ next
    }

  def schema[A](path: String, message: String): Result[A] =
    Left(DescriptorError("SCHEMA_MISMATCH", path, message))

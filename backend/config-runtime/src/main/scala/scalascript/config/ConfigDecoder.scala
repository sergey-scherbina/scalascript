package scalascript.config

import scala.deriving.Mirror
import scala.compiletime.{summonInline, erasedValue, constValueTuple}

/** Typeclass: decode a [[ConfigValue]] into type `A`.
 *
 *  Instances for primitives + Option + List + Map[String, _].
 *  Case classes get an instance via `derives Config` (or by calling
 *  `ConfigDecoder.derived[A]` explicitly).
 *
 *  {{{
 *  case class Srv(port: Int, host: String) derives Config
 *  val srv = ConfigDecoder[Srv].decode(cfgValue) // Right(Srv(8080, "localhost"))
 *  }}}
 */
trait ConfigDecoder[A]:
  def decode(cv: ConfigValue): Either[ConfigError, A]

object ConfigDecoder:

  def apply[A](using d: ConfigDecoder[A]): ConfigDecoder[A] = d

  // ── Primitive instances ─────────────────────────────────────────────────

  given ConfigDecoder[String] with
    def decode(cv: ConfigValue) = cv.getString
      .toRight(ConfigError.TypeError("", "String", cv.getClass.getSimpleName))

  given ConfigDecoder[Int] with
    def decode(cv: ConfigValue) = cv.getInt
      .toRight(ConfigError.TypeError("", "Int", cv.getClass.getSimpleName))

  given ConfigDecoder[Long] with
    def decode(cv: ConfigValue) = cv.getDouble.map(_.toLong)
      .toRight(ConfigError.TypeError("", "Long", cv.getClass.getSimpleName))

  given ConfigDecoder[Double] with
    def decode(cv: ConfigValue) = cv.getDouble
      .toRight(ConfigError.TypeError("", "Double", cv.getClass.getSimpleName))

  given ConfigDecoder[Boolean] with
    def decode(cv: ConfigValue) = cv.getBool
      .toRight(ConfigError.TypeError("", "Boolean", cv.getClass.getSimpleName))

  // ── Collection instances ────────────────────────────────────────────────

  given [A](using da: ConfigDecoder[A]): ConfigDecoder[List[A]] with
    def decode(cv: ConfigValue) = cv match
      case ConfigValue.Lst(vs) =>
        vs.foldLeft(Right(Nil): Either[ConfigError, List[A]]) { (accE, v) =>
          for acc <- accE; a <- da.decode(v) yield acc :+ a
        }
      case _ => Left(ConfigError.TypeError("", "List", cv.getClass.getSimpleName))

  given [A](using da: ConfigDecoder[A]): ConfigDecoder[Map[String, A]] with
    def decode(cv: ConfigValue) = cv match
      case ConfigValue.Map(m) =>
        m.foldLeft(Right(Map.empty): Either[ConfigError, Map[String, A]]) {
          case (accE, (k, v)) =>
            for acc <- accE; a <- da.decode(v) yield acc + (k -> a)
        }
      case _ => Left(ConfigError.TypeError("", "Map", cv.getClass.getSimpleName))

  given [A](using da: ConfigDecoder[A]): ConfigDecoder[Option[A]] with
    def decode(cv: ConfigValue) = cv match
      case ConfigValue.Null => Right(None)
      case other            => da.decode(other).map(Some(_))

  // ── Derivation for case classes ─────────────────────────────────────────

  inline def derived[A](using m: Mirror.ProductOf[A]): ConfigDecoder[A] =
    val labels   = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]]
    val decoders = summonDecoders[m.MirroredElemTypes]
    makeDecoder[A](labels, decoders, m)

  private def makeDecoder[A](
    labels: List[String],
    decoders: List[ConfigDecoder[?]],
    m: Mirror.ProductOf[A],
  ): ConfigDecoder[A] =
    new ConfigDecoder[A]:
      def decode(cv: ConfigValue): Either[ConfigError, A] =
        cv match
          case ConfigValue.Map(map) =>
            val results = labels.zip(decoders).map { (label, dec) =>
              val fieldCv = map.getOrElse(label, ConfigValue.Null)
              dec.decode(fieldCv) match
                case Right(v) => Right(v)
                case Left(e)  => Left(ConfigError.TypeError(label, "?", e.getMessage))
            }
            val errors = results.collect { case Left(e) => e }
            if errors.nonEmpty then Left(errors.head)
            else Right(m.fromProduct(Tuple.fromArray(results.collect { case Right(v) => v }.toArray)))
          case _ =>
            Left(ConfigError.TypeError("", "Map", cv.getClass.getSimpleName))

  private inline def summonDecoders[T <: Tuple]: List[ConfigDecoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[ConfigDecoder[h]] :: summonDecoders[t]

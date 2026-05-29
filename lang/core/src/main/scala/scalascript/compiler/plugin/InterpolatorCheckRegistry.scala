package scalascript.compiler.plugin

import scalascript.backend.spi.{Backend, Diagnostic, InterpolatorCheck}
import scalascript.markup.{Dialect, PureMarkupCodec}
import scala.collection.concurrent.TrieMap

/** Registry for compile-time string-interpolator validators.
 *
 *  Validators are keyed by interpolator prefix. The transform pass calls
 *  `checkAll` for each interpolation it sees; plugins register checks through
 *  `Backend.interpolatorChecks` when loaded by `BackendRegistry`.
 */
object InterpolatorCheckRegistry:

  private val registry = TrieMap.empty[String, Vector[InterpolatorCheck]]

  def register(check: InterpolatorCheck): Unit =
    registry.updateWith(check.interpolatorName) {
      case Some(existing) if existing.exists(_.getClass == check.getClass) => Some(existing)
      case Some(existing) => Some(existing :+ check)
      case None           => Some(Vector(check))
    }

  def registerFrom(backend: Backend): Unit =
    backend.interpolatorChecks.foreach(register)

  def checksFor(name: String): List[InterpolatorCheck] =
    registry.getOrElse(name, Vector.empty).toList

  def checkAll(name: String, parts: List[String]): List[Diagnostic] =
    checksFor(name).flatMap(_.check(parts))

  locally {
    register(XmlInterpolatorCheck)
  }

/** Built-in well-formedness checker for `xml"..."`.
 *
 *  Dynamic holes are replaced by a placeholder element so the static
 *  surrounding XML structure can be parsed conservatively.
 */
object XmlInterpolatorCheck extends InterpolatorCheck:
  private val Placeholder = "<placeholder/>"

  def interpolatorName: String = "xml"

  def check(parts: List[String]): List[Diagnostic] =
    val candidate = parts.zipWithIndex.map { (part, i) =>
      if i < parts.length - 1 then part + Placeholder else part
    }.mkString
    PureMarkupCodec.parse(candidate, Dialect.Xml1_0) match
      case Right(_)  => Nil
      case Left(err) => List(Diagnostic.XmlParseError(err.message, err.line, err.column))

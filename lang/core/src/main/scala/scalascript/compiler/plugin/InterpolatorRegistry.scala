package scalascript.compiler.plugin

import scalascript.backend.spi.InterpolatorImpl
import scala.collection.concurrent.TrieMap

/** Registry for typed string interpolators.
 *
 *  Backends (and test harnesses) call `register` to add custom interpolators.
 *  The Typer, JvmGen, JsGen, and CapabilityCheck call `lookup` to resolve
 *  interpolator prefixes at compile / emit time.
 *
 *  Built-in interpolators (html"...", css"...") are pre-registered on object
 *  initialisation.  Plugin-provided interpolators are registered when the
 *  plugin is loaded via BackendRegistry.
 *
 *  See docs/arch-dsl-hooks.md §4a. */
object InterpolatorRegistry:

  private val registry = TrieMap.empty[String, InterpolatorImpl]

  def register(impl: InterpolatorImpl): Unit = registry(impl.name) = impl
  def lookup(name: String): Option[InterpolatorImpl] = registry.get(name)
  def all: Iterable[InterpolatorImpl] = registry.values

  /** Register all interpolators from an already-loaded backend. */
  def registerFrom(b: scalascript.backend.spi.Backend): Unit =
    b.interpolators.foreach(register)

  // ── Built-in interpolator registrations ───────────────────────────

  locally {
    register(HtmlInterpolator)
    register(CssInterpolator)
  }

  /** html"..." — HTML-safe string interpolation.
   *
   *  JVM: builds a string via StringBuilder, quoting HTML-special chars in
   *  each interpolated value.  JS: uses the _html_interp runtime helper.
   *  Return type is String; the value is an HTML-escaped string. */
  object HtmlInterpolator extends InterpolatorImpl:
    override val name           = "html"
    override val returnTypeName = "String"

    override def jvmEmit(parts: List[String], args: List[String]): String =
      val sb = StringBuilder("_htmlInterp(Seq(")
      val escaped = parts.map(p => "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
      sb.append(escaped.mkString(", "))
      sb.append(")")
      if args.nonEmpty then
        sb.append(", Seq(")
        sb.append(args.mkString(", "))
        sb.append(")")
      else
        sb.append(", Seq()")
      sb.append(")")
      sb.toString

    override def jsEmit(parts: List[String], args: List[String]): String =
      val partsJs = parts.map(p => "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .mkString(", ")
      val argsJs  = if args.isEmpty then "" else ", [" + args.mkString(", ") + "]"
      s"_html_interp_parts([$partsJs]$argsJs)"

  /** css"..." — CSS string interpolation.
   *
   *  For Phase 1 the CSS interpolator is a simple s-style string builder.
   *  Future phases may add autoprefixing or validation. */
  object CssInterpolator extends InterpolatorImpl:
    override val name           = "css"
    override val returnTypeName = "String"

    private def buildStringContext(parts: List[String], args: List[String]): String =
      val scParts = parts.map(p => "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .mkString(", ")
      val argsStr = args.mkString(", ")
      s"StringContext($scParts).css($argsStr)"

    override def jvmEmit(parts: List[String], args: List[String]): String =
      buildStringContext(parts, args)

    override def jsEmit(parts: List[String], args: List[String]): String =
      val partsJs = parts.map(p => "\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        .mkString(", ")
      val argsJs  = if args.isEmpty then "" else ", " + args.mkString(", ")
      s"_ext_StringContext_css(_sc([$partsJs])$argsJs)"

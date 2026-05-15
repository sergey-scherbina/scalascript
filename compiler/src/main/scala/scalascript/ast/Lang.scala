package scalascript.ast

/** Language identifiers for fenced code blocks inside `.ssc` files.
 *
 *  `scalascript` (alias `ssc`) — ScalaScript language: interpreted by the
 *  tree-walking interpreter, transpiled to JavaScript by JsGen, or compiled
 *  via JvmGen.  ScalaScript is a superset of Scala 3 with extensions such as
 *  algebraic effects/handlers, content helpers, and module imports.
 *
 *  `scala` — standard Scala 3: no ScalaScript-specific extensions.  The JVM
 *  backend includes these blocks as-is.  The JavaScript backend will
 *  eventually compile them via Scala.js; for now they are skipped with a
 *  comment.  The interpreter runs them using the same Scala 3 subset it
 *  already supports.
 */
object Lang:
  val Scala       = "scala"
  val ScalaScript = "scalascript"
  val Ssc         = "ssc"          // legacy alias for scalascript

  def isScalaScript(lang: String): Boolean =
    lang == ScalaScript || lang == Ssc

  def isStandardScala(lang: String): Boolean =
    lang == Scala

  /** True for any lang whose blocks are parsed by scalameta. */
  def isParseable(lang: String): Boolean =
    isScalaScript(lang) || isStandardScala(lang)

  /** Human-readable label for a lang tag. */
  def label(lang: String): String = lang match
    case Scala       => "Scala 3"
    case ScalaScript => "ScalaScript"
    case Ssc         => "ScalaScript"
    case other       => other

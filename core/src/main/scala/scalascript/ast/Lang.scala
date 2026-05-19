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
 *
 *  `html` / `css` / `javascript` — opaque string blocks with `${expr}`
 *  interpolation against the surrounding ScalaScript scope.  Not parsed,
 *  not type-checked.  See `isStringBlock`.
 *
 *  `node.js` (alias `node`) — opaque executable JavaScript for the Node
 *  backend.  Source is preserved verbatim; the Node backend concatenates
 *  these blocks with the JsGen output of the module to form one `.mjs`
 *  bundle.  Other backends reject the block via `UnknownBlockLanguage`.
 *  See `isOpaqueExec`.
 */
object Lang:
  val Scala       = "scala"
  val ScalaScript = "scalascript"
  val Ssc         = "ssc"          // legacy alias for scalascript
  val Html        = "html"
  val Css         = "css"
  val Js          = "javascript"
  val JsShort     = "js"           // alias for javascript
  val Node        = "node.js"
  val NodeShort   = "node"         // alias for node.js

  def isScalaScript(lang: String): Boolean =
    lang == ScalaScript || lang == Ssc

  def isStandardScala(lang: String): Boolean =
    lang == Scala

  def isJavaScript(lang: String): Boolean =
    lang == Js || lang == JsShort

  def isNode(lang: String): Boolean =
    lang == Node || lang == NodeShort

  /** True for blocks whose body is a `String` value with `${expr}`
   *  interpolation (html, css, javascript). Not parsed by scalameta. */
  def isStringBlock(lang: String): Boolean =
    lang == Html || lang == Css || isJavaScript(lang)

  /** True for any lang whose blocks are parsed by scalameta. */
  def isParseable(lang: String): Boolean =
    isScalaScript(lang) || isStandardScala(lang)

  /** True for blocks that are opaque executable code, linked verbatim into
   *  a target-specific bundle.  Neither parsed nor a String value at the
   *  AST level.  Only the matching backend recognises these; all others
   *  emit `UnknownBlockLanguage`. */
  def isOpaqueExec(lang: String): Boolean =
    isNode(lang)

  /** Human-readable label for a lang tag. */
  def label(lang: String): String = lang match
    case Scala                     => "Scala 3"
    case ScalaScript               => "ScalaScript"
    case Ssc                       => "ScalaScript"
    case Html                      => "HTML"
    case Css                       => "CSS"
    case Js | JsShort              => "JavaScript"
    case Node | NodeShort          => "Node.js"
    case other                     => other

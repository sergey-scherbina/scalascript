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
 *
 *  `sql` — parameterised SQL executed via JDBC on the JVM target.  Every
 *  `${expr}` inside the block becomes a positional `?` bind parameter
 *  (string substitution into SQL is *not* part of the language, with no
 *  unsafe-splice escape — see SPEC.md § 3.3.1).  Front-end recognition
 *  routes the block as opaque-exec; the bind-parameter rewriter (Phase 3
 *  of the v1.26 milestone) lifts `(sqlWithQ, binds)` into the IR.  Other
 *  backends (JS / Node / Wasm) reject via `UnknownBlockLanguage`.
 *  See `isOpaqueExec` and `isParameterizedExec`.
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
  val Sql         = "sql"
  val Transaction = "transaction"

  def isScalaScript(lang: String): Boolean =
    lang == ScalaScript || lang == Ssc

  def isStandardScala(lang: String): Boolean =
    lang == Scala

  def isJavaScript(lang: String): Boolean =
    lang == Js || lang == JsShort

  def isNode(lang: String): Boolean =
    lang == Node || lang == NodeShort

  def isSql(lang: String): Boolean =
    lang == Sql

  def isTransaction(lang: String): Boolean =
    lang == Transaction

  /** True for blocks whose body is a `String` value with `${expr}`
   *  interpolation (html, css, javascript). Not parsed by scalameta. */
  def isStringBlock(lang: String): Boolean =
    lang == Html || lang == Css || isJavaScript(lang)

  /** True for any lang whose blocks are parsed by scalameta. */
  def isParseable(lang: String): Boolean =
    isScalaScript(lang) || isStandardScala(lang)

  /** True for blocks that are opaque executable code passed verbatim
   *  to a target-specific runtime.  Neither parsed nor a String value
   *  at the AST level.  Only backends that declare the lang tag in
   *  `Capabilities.blockLanguages` recognise these; all others emit
   *  `Diagnostic.UnknownBlockLanguage`.
   *
   *  Today: `node.js` (consumed by the Node backend), `sql`
   *  (consumed by the JVM target via `backend-sql-runtime`), and
   *  `transaction` (multi-statement JDBC transaction, JVM only). */
  def isOpaqueExec(lang: String): Boolean =
    isNode(lang) || isSql(lang) || isTransaction(lang)

  /** True for opaque-exec blocks whose source is rewritten by the
   *  front-end into a `(template, binds)` pair before the backend
   *  sees it — `sql` and `transaction`, where every `${expr}` becomes
   *  a positional `?` placeholder with the expression captured in
   *  an ordered bind list.  See SPEC.md § 3.3.1. */
  def isParameterizedExec(lang: String): Boolean =
    isSql(lang) || isTransaction(lang)

  /** Human-readable label for a lang tag. */
  def label(lang: String): String = lang match
    case Scala                     => "Scala 3"
    case ScalaScript               => "ScalaScript"
    case Ssc                       => "ScalaScript"
    case Html                      => "HTML"
    case Css                       => "CSS"
    case Js | JsShort              => "JavaScript"
    case Node | NodeShort          => "Node.js"
    case Sql                       => "SQL"
    case Transaction               => "SQL Transaction"
    case other                     => other

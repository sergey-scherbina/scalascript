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
 *
 *  `xml` — well-formed XML 1.0 with `${expr}` interpolation.  Each
 *  interpolated value is XML-escaped (< > & " ') before the result is
 *  parsed by `PureMarkupCodec`, producing a `Value.MarkupV(doc)`.
 *  Bound as `<sectionIdent>.xml` in the interpreter's global scope.
 *  See `isXml` and BACKLOG.md §v1.55.2.
 *
 *  `java` — Java source for the JVM backend.  Emitted as a separate
 *  `.java` source file via `//> using sources` by JvmGen.  Other backends
 *  reject via `UnknownBlockLanguage`.  See `isNativeBackendBlock`.
 *
 *  `rust` — Rust source for the Rust backend.  Emitted verbatim into
 *  `mod inline_native` in the generated crate.  Other backends reject.
 *  See `isNativeBackendBlock`.
 *
 *  `wasm` — WAT or Rust-WASM source for the WASM backend.  Backend
 *  decides the exact mechanism.  See `isNativeBackendBlock`.
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
  val Xml         = "xml"
  val Graphql     = "graphql"
  val Java        = "java"
  val Rust        = "rust"
  val Wasm        = "wasm"

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

  def isXml(lang: String): Boolean =
    lang == Xml

  def isGraphql(lang: String): Boolean =
    lang == Graphql

  /** True for blocks whose body is a `String` value with `${expr}`
   *  interpolation (html, css, javascript). Not parsed by scalameta. */
  def isStringBlock(lang: String): Boolean =
    lang == Html || lang == Css || isJavaScript(lang)

  /** True for any lang whose blocks are parsed by scalameta. */
  def isParseable(lang: String): Boolean =
    isScalaScript(lang) || isStandardScala(lang)

  def isJava(lang: String): Boolean =
    lang == Java

  def isRust(lang: String): Boolean =
    lang == Rust

  def isWasm(lang: String): Boolean =
    lang == Wasm

  /** True for backend-specific fenced blocks: `java`, `rust`, `wasm`.
   *  These are opaque native-code blocks passed verbatim to the matching
   *  backend (`java`/`scala` → JvmGen, `rust` → RustGen, `wasm` → WasmGen).
   *  Other backends reject them via `Diagnostic.UnknownBlockLanguage`.
   *  See `specs/backend-specific-blocks.md`. */
  def isNativeBackendBlock(lang: String): Boolean =
    isJava(lang) || isRust(lang) || isWasm(lang)

  /** True for blocks that are opaque executable code passed verbatim
   *  to a target-specific runtime.  Neither parsed nor a String value
   *  at the AST level.  Only backends that declare the lang tag in
   *  `Capabilities.blockLanguages` recognise these; all others emit
   *  `Diagnostic.UnknownBlockLanguage`.
   *
   *  Includes: `node.js` (Node backend), `sql`/`transaction` (JVM JDBC),
   *  `xml` (v1.55.2+), `graphql`, and native backend blocks
   *  (`java`, `rust`, `wasm` — see `isNativeBackendBlock`). */
  def isOpaqueExec(lang: String): Boolean =
    isNode(lang) || isSql(lang) || isTransaction(lang) || isXml(lang) || isGraphql(lang) ||
    isNativeBackendBlock(lang)

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
    case Xml                       => "XML"
    case Graphql                   => "GraphQL"
    case Java                      => "Java"
    case Rust                      => "Rust"
    case Wasm                      => "WASM"
    case other                     => other

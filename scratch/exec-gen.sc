
// ── Show / println override (scripting-style Double formatting) ────────
// Mirrors the interpreter / JS backends: a Double whose value is an
// integer renders without the trailing ".0" (e.g. 4.0 → "4").
def _show(v: Any): String = v match
  case null      => "null"
  case d: Double => if d == d.toLong.toDouble then d.toLong.toString else d.toString
  // Exact numerics (v1.64): plain (non-scientific) rendering, matching
  // the interpreter / JS backends.
  case bd: BigDecimal => bd.bigDecimal.toPlainString
  case bi: BigInt     => bi.toString
  case s: String => s
  // _Raw HTML nodes (from `raw(...)`, html"...", or DSL tag fns) render
  // as their inner string so `println(div(...))` prints the markup.
  case r: _Raw   => r.html
  // Render a Range like a List so xs.indices and similar lazy
  // iterables match the interpreter / JS output ("List(0, 1, 2)").
  case r: scala.collection.immutable.Range => r.toList.map(_show).mkString("List(", ", ", ")")
  // Match interpreter/JS rendering of Option, Map, List, Tuple, and
  // case-class instances — recursively `_show` children so a Double
  // inside Some(Circle(5.0)) still drops its trailing `.0`.
  case None       => "None"
  case Some(inner) => "Some(" + _show(inner) + ")"
  case m: Map[?, ?] =>
    if m.isEmpty then "Map()"
    else m.iterator.map((k, vv) => _show(k) + " -> " + _show(vv)).mkString("Map(", ", ", ")")
  case t: scala.Tuple =>
    "(" + t.productIterator.map(_show).mkString(", ") + ")"
  case xs: List[?] => xs.map(_show).mkString("List(", ", ", ")")
  // Optics carry a printable label as their last field; route through
  // toString so callers see `Lens(_.a.b)` instead of the function refs.
  case l: Lens[?, ?]      => l.toString
  case o: Optional[?, ?]  => o.toString
  case t: Traversal[?, ?] => t.toString
  case p: Prism[?, ?]     => p.toString
  case p: Product if p.productArity > 0 =>
    p.productPrefix + "(" + p.productIterator.map(_show).mkString(", ") + ")"
  case p: Product => p.productPrefix
  case other     => other.toString

def _tupleConcat(a: Any, b: Any): Any = (a, b) match
  case (at: scala.Tuple, bt: scala.Tuple) =>
    scala.Tuple.fromArray(at.productIterator.toArray ++ bt.productIterator.toArray)
  case (at: scala.Tuple, _) if b == () => at
  case (_, bt: scala.Tuple) if a == () => bt
  case (_, _) if a == () && b == () => ()
  case (at: scala.Tuple, _) =>
    scala.Tuple.fromArray(at.productIterator.toArray :+ b)
  case (_, bt: scala.Tuple) =>
    scala.Tuple.fromArray(Array(a) ++ bt.productIterator.toArray)
  case _ if a == () => b
  case _ if b == () => a
  case (al: List[?], bl: List[?]) => al.asInstanceOf[List[Any]] ++ bl.asInstanceOf[List[Any]]
  case _ => scala.Tuple.fromArray(Array(a, b))

def println(v: Any): Unit = scala.Predef.println(_show(v))
def print(v: Any): Unit   = scala.Predef.print(_show(v))

// Stage 5+/B.3 — `Console` shadows `scala.Console` so that Normalize's
// bare-println rewrite (`println` → `Console.println`) routes through
// `_show` in both the emitExpr intrinsic path and the passthrough path.
object Console:
  def println(v: Any): Unit = scala.Predef.println(_show(v))
  def print(v: Any): Unit   = scala.Predef.print(_show(v))

// std.bench — Bench.opaque: an anti-folding identity barrier.  Returns
// `x` unchanged, but the volatile-field comparison forces HotSpot to
// materialise `x` at each call site and prevents C2 from precomputing
// pure-arith expressions whose inputs flow through `opaque`.  The
// implementation mirrors `std::hint::black_box` (Rust) and
// `org.openjdk.jmh.infra.Blackhole.consume` (JMH).
//
// Cost: ~1-3 ns/call on M1 — comparable to a volatile read.  Used by
// benchmark wrappers; user code that doesn't import Bench.opaque pays
// nothing.
object Bench:
  @volatile private var _ssc_opaque_t1: Long = 0L
  @volatile private var _ssc_opaque_t2: Long = java.lang.Long.MIN_VALUE
  @scala.annotation.nowarn def opaque[A](x: A): A =
    if _ssc_opaque_t1 == _ssc_opaque_t2 then null.asInstanceOf[A] else x

// `sx` is like `s` but routes each interpolated value through `_show`,
// so a whole-number Double interpolated into a string drops its ".0".
// Code-block emission rewrites `s"..."` to `sx"..."` for the same reason.
extension (sc: StringContext)
  def sx(args: Any*): String = sc.s(args.map(_show)*)

extension (sc: StringContext)
  def md(args: Any*): String =
    val s = sc.s(args*)
    val lines = s.split("\n", -1).toSeq
    val body = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    if body.isEmpty then ""
    else
      val indent = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).min
      body.map(_.drop(indent)).mkString("\n")

// ── HTML / CSS string interpolators ────────────────────────────────────
// html"..." auto-escapes interpolated values unless wrapped in raw(s).
case class _Raw(html: String)
def raw(s: Any): _Raw = _Raw(_show(s))

def _htmlEscape(s: String): String =
  val sb = StringBuilder(s.length)
  var i = 0
  while i < s.length do
    s.charAt(i) match
      case '&'  => sb ++= "&amp;"
      case '<'  => sb ++= "&lt;"
      case '>'  => sb ++= "&gt;"
      case '"'  => sb ++= "&quot;"
      case '\'' => sb ++= "&#39;"
      case c    => sb += c
    i += 1
  sb.toString

def escape(s: Any): String = _htmlEscape(_show(s))

/** `collectCss(comp1, comp2, ...)` — concatenate each argument's
 *  `css` field into one CSS string for a page-level <style>.
 *  Convention helper for component-style .ssc files (see SPEC §8.4).
 *  Each argument is expected to be a Scala `object` exposing a
 *  `val css: String`; reflective access keeps the helper free of
 *  a shared component supertype.  Anything without a no-arg
 *  `css` method that returns a String is silently skipped. */
def collectCss(parts: Any*): String =
  parts.flatMap { part =>
    try
      val m = part.getClass.getMethod("css")
      m.invoke(part) match
        case s: String => Some(s)
        case _         => None
    catch case _: Throwable => None
  }.mkString("\n")

/** `collectJs(comp1, comp2, ...)` — same shape as `collectCss`,
 *  reads each argument's `val js: String` for a page <script>. */
def collectJs(parts: Any*): String =
  parts.flatMap { part =>
    try
      val m = part.getClass.getMethod("js")
      m.invoke(part) match
        case s: String => Some(s)
        case _         => None
    catch case _: Throwable => None
  }.mkString("\n")

/** `scope("Card")` — class-name suffix helper for component-style
 *  .ssc files (see SPEC §8.4).
 *
 *    val s = scope("Card")
 *    val css = s.css(".title { color: blue }")  // ".title__Card { color: blue }"
 *    val c   = s.cls("title")                   // "title__Card"
 *
 *  Two components can both write bare `.title` without their
 *  concatenated CSS colliding.  The CSS rewriter is a simple
 *  `\.identifier` regex pass; class chains (`.a.b`) work, but
 *  `.ident` inside `url(...)` would also be rewritten — keep URL
 *  strings free of bare-identifier dots if you depend on them. */
class _Scope(val name: String):
  private val pat = "\\.([A-Za-z_][A-Za-z0-9_-]*)".r
  def css(s: String): String =
    pat.replaceAllIn(s, m =>
      java.util.regex.Matcher.quoteReplacement("." + m.group(1) + "__" + name)
    )
  def cls(n: String): String = n + "__" + name

def scope(name: String): _Scope = _Scope(name)

// i18n runtime helpers
var _i18nLocale: String = "en"
var _i18nTable: Map[String, Map[String, String]] = Map.empty
def setLocale(code: String): Unit = { _i18nLocale = code }
def t(key: String): String = _i18nTable.get(_i18nLocale).flatMap(_.get(key)).getOrElse(key)
/** `wc(tag, Component, args*)` — server-side render with declarative shadow DOM.
 *  Uses reflection to call `Component.css` and `Component.render(args*)`,
 *  following the same convention as `collectCss`. */
def wc(tag: String, component: Any, args: Any*): String =
  val cssStr =
    try component.getClass.getMethod("css").invoke(component) match
      case s: String => s
      case _         => ""
    catch case _: Throwable => ""
  val innerHtml =
    try
      val cls = component.getClass
      val methods = cls.getMethods.filter(_.getName == "render")
      val renderM = methods.find(m => m.getParameterCount == args.length)
        .orElse(methods.headOption)
      renderM match
        case Some(m) =>
          m.invoke(component, args.map(_.asInstanceOf[AnyRef])*) match
            case r: _Raw => r.html
            case v       => _show(v)
        case None => ""
    catch case _: Throwable => ""
  s"<$tag-component><template shadowrootmode=\"open\"><style>$cssStr</style>$innerHtml</template></$tag-component>"

// Used by heading-bound html-block emission: escape unless raw(...).
def _html_interp(v: Any): String = v match
  case r: _Raw => r.html
  case _       => _htmlEscape(_show(v))

extension (sc: StringContext)
  def html(args: Any*): String =
    val sb = StringBuilder()
    val parts = sc.parts
    var i = 0
    while i < parts.length do
      sb ++= parts(i)
      if i < args.length then args(i) match
        case r: _Raw => sb ++= r.html
        case v       => sb ++= _htmlEscape(_show(v))
      i += 1
    sb.toString

  def css(args: Any*): String = sc.s(args.map(_show)*)

// ── Typed HTML DSL — `div(attr.cls := "hero", h1("hi"))` ───────────────
case class _AttrKey(name: String):
  def := (value: Any): _Attr = _Attr(name, _show(value))
case class _Attr(name: String, value: String)

object attr:
  val cls         = _AttrKey("class")
  val id          = _AttrKey("id")
  val href        = _AttrKey("href")
  val src         = _AttrKey("src")
  val alt         = _AttrKey("alt")
  val name        = _AttrKey("name")
  val title       = _AttrKey("title")
  val style       = _AttrKey("style")
  val type_       = _AttrKey("type")
  val value_      = _AttrKey("value")
  val placeholder = _AttrKey("placeholder")
  val method_     = _AttrKey("method")
  val action      = _AttrKey("action")
  val target      = _AttrKey("target")
  val rel         = _AttrKey("rel")
  val for_        = _AttrKey("for")
  val role        = _AttrKey("role")
  val colspan     = _AttrKey("colspan")
  val rowspan     = _AttrKey("rowspan")
  val disabled    = _AttrKey("disabled")

private def _renderChild(v: Any): String = v match
  case r: _Raw         => r.html
  case xs: Iterable[_] => xs.map(_renderChild).mkString
  case other           => _htmlEscape(_show(other))

private def _renderTag(name: String, args: Seq[Any], voidTag: Boolean = false): _Raw =
  val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
  val children = StringBuilder()
  def handle(v: Any): Unit = v match
    case a: _Attr        => attrs(a.name) = a.value
    case xs: Iterable[_] => xs.foreach(handle)
    case other           => children ++= _renderChild(other)
  args.foreach(handle)
  val attrStr =
    if attrs.isEmpty then ""
    else attrs.map((k, v) => " " + k + "=\"" + _htmlEscape(v) + "\"").mkString
  if voidTag then _Raw("<" + name + attrStr + ">")
  else            _Raw("<" + name + attrStr + ">" + children.toString + "</" + name + ">")

// Each tag is a value, not a def, so `items.map(li)` works.  The class
// extends `Any => _Raw` so it eta-expands to a Function1; an additional
// `apply(args: Any*)` overload preserves the multi-arg `div(a, b, c)`
// call syntax that the DSL needs.
class _Tag(name: String, voidTag: Boolean = false) extends (Any => _Raw):
  override def apply(arg: Any): _Raw = _renderTag(name, Seq(arg), voidTag)
  def apply(args: Any*): _Raw       = _renderTag(name, args, voidTag)

case class _Doc(parts: Seq[Any])
def doc(args: Any*): _Doc = _Doc(args.toSeq)
def render(args: Any*): Unit =
  def toStr(v: Any): String = v match
    case d: _Doc => d.parts.map(toStr).mkString("\n")
    case other   => other.toString
  val text =
    if args.length == 1 && args(0).isInstanceOf[_Doc] then toStr(args(0).asInstanceOf[_Doc])
    else args.map(toStr).mkString("\n")
  println(text)

// Wall-clock for benchmarks — matches ScalaScript's `nanoTime()` primitive.
def nanoTime(): Long = java.lang.System.nanoTime()

// ── Lens runtime — pure-functional optic over case-class field paths ──
case class Lens[S, A](get: S => A, set: (S, A) => S, _label: String = ""):
  override def toString: String = if _label.isEmpty then "Lens" else _label
  def modify(s: S, f: A => A): S = set(s, f(get(s)))
  def andThen[B](other: Lens[A, B]): Lens[S, B] =
    Lens(s => other.get(get(s)), (s, b) => set(s, other.set(get(s), b)))
  def andThen[B](other: Optional[A, B]): Optional[S, B] =
    Optional(s => other.getOption(get(s)), (s, b) => set(s, other.set(get(s), b)))
  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
    Traversal(
      s => other.toList(get(s)),
      (s, f) => set(s, other.modifyF(get(s), f))
    )

// ── Prism runtime — sum-type optic, conditional get / set / modify ────
case class Prism[S, A](getOption: S => Option[A], reverseGet: A => S, _label: String = ""):
  override def toString: String = if _label.isEmpty then "Prism" else _label
  def set(s: S, a: A): S = getOption(s) match
    case Some(_) => reverseGet(a)
    case None    => s
  def modify(s: S, f: A => A): S = getOption(s) match
    case Some(a) => reverseGet(f(a))
    case None    => s
  def andThen[B](other: Prism[A, B]): Prism[S, B] =
    Prism(
      s => getOption(s).flatMap(other.getOption),
      b => reverseGet(other.reverseGet(b))
    )

// ── Optional runtime — partial optic over a path with `.some` ─────
case class Optional[S, A](getOption: S => Option[A], set: (S, A) => S, _label: String = ""):
  override def toString: String = if _label.isEmpty then "Optional" else _label
  def modify(s: S, f: A => A): S = getOption(s) match
    case Some(a) => set(s, f(a))
    case None    => s
  def andThen[B](other: Optional[A, B]): Optional[S, B] =
    Optional(
      s => getOption(s).flatMap(other.getOption),
      (s, b) => getOption(s) match
        case Some(a) => set(s, other.set(a, b))
        case None    => s
    )
  def andThen[B](other: Lens[A, B]): Optional[S, B] =
    Optional(
      s => getOption(s).map(other.get),
      (s, b) => getOption(s) match
        case Some(a) => set(s, other.set(a, b))
        case None    => s
    )
  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
    Traversal(
      s => getOption(s).toList.flatMap(other.toList),
      (s, f) => getOption(s) match
        case Some(a) => set(s, other.modifyF(a, f))
        case None    => s
    )

// ── Traversal runtime — multi-foci optic for `.each` paths ────────
case class Traversal[S, A](toList: S => List[A], modifyF: (S, A => A) => S, _label: String = ""):
  override def toString: String = if _label.isEmpty then "Traversal" else _label
  def getAll(s: S): List[A] = toList(s)
  def modify(s: S, f: A => A): S = modifyF(s, f)
  def set(s: S, a: A): S = modifyF(s, _ => a)
  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
    Traversal(
      s => toList(s).flatMap(other.toList),
      (s, f) => modifyF(s, a => other.modifyF(a, f))
    )
  def andThen[B](other: Lens[A, B]): Traversal[S, B] =
    Traversal(
      s => toList(s).map(other.get),
      (s, f) => modifyF(s, a => other.set(a, f(other.get(a))))
    )
  def andThen[B](other: Optional[A, B]): Traversal[S, B] =
    Traversal(
      s => toList(s).flatMap(a => other.getOption(a).toList),
      (s, f) => modifyF(s, a => other.modify(a, f))
    )

// Environment variable reader — same surface on all three backends.
def getenv(key: String, defaultVal: String = ""): String =
  val v = java.lang.System.getenv(key)
  if v == null || v.isEmpty then defaultVal else v

// ── Rate limiting / TOTP / Password — adapter shims ───────────
// The implementations live in runtime-server-common (inlined as
// classpath resources by JvmGen.commonRuntime); these top-level
// defs preserve the user-facing API.
def rateLimit(key: String, limit: Long, windowSeconds: Long): Boolean =
  RateLimit.tryAcquire(key, limit, windowSeconds)
def rateLimitReset(key: String): Unit = RateLimit.reset(key)
def totpSecret(): String = Totp.secret()
def totpUri(secret: String, account: String, issuer: String = ""): String =
  Totp.uri(secret, account, issuer)
def totpCode(secret: String): String = Totp.code(secret)
def totpValid(secret: String, code: String, skew: Int = 1): Boolean =
  Totp.valid(secret, code, skew)
def hashPassword(password: String, iter: Int = 200000): String =
  Password.hash(password, iter)
def verifyPassword(password: String, encoded: String): Boolean =
  Password.verify(password, encoded)


// ── scalascript-logger (inlined from classpath resources) ─────────────
// Source of truth: logger/src/main/scala/scalascript/logging/Logger.scala

import java.io.PrintStream

/** Self-contained logger that reads `scalascript.logger.*` system properties.
 *
 *  Configuration (via `--logs` CLI flag or `System.setProperty`):
 *    scalascript.logger.defaultLevel    — threshold for all loggers (default: warn)
 *    scalascript.logger.<name>.level   — per-logger threshold override
 *    scalascript.logger.logFile        — "System.err" (default) or "System.out"
 *
 *  This file is inlined verbatim into generated scala-cli scripts by JvmGen;
 *  it must not import anything outside the JDK standard library.
 */

private enum _SscLogLevel:
  case Debug, Info, Warn, Error

private object _SscLogLevel:
  def parse(s: String): _SscLogLevel = s.trim.toLowerCase match
    case "debug" => Debug
    case "info"  => Info
    case "error" => Error
    case _       => Warn

final class Logger private (val name: String):
  import _SscLogLevel.*

  private def threshold: _SscLogLevel =
    Option(System.getProperty(s"scalascript.logger.$name.level"))
      .orElse(Option(System.getProperty("scalascript.logger.defaultLevel")))
      .map(_SscLogLevel.parse)
      .getOrElse(Warn)

  def isDebugEnabled: Boolean = threshold == Debug
  def isInfoEnabled: Boolean  = threshold.ordinal <= Info.ordinal

  def debug(msg: => String): Unit =
    if threshold == Debug then _emit("DEBUG", msg)
  def info(msg: => String): Unit =
    if threshold.ordinal <= Info.ordinal then _emit("INFO", msg)
  def warn(msg: => String): Unit =
    if threshold.ordinal <= Warn.ordinal then _emit("WARN", msg)
  def error(msg: => String): Unit =
    _emit("ERROR", msg)
  def error(msg: => String, cause: Throwable): Unit =
    _emit("ERROR", msg); cause.printStackTrace(_logStream)

  private def _logStream: PrintStream =
    if System.getProperty("scalascript.logger.logFile") == "System.out"
    then System.out else System.err

  private def _emit(level: String, msg: String): Unit =
    _logStream.println(s"[$level] $msg")

object Logger:
  def info (msg: Any): Any  = _perform("Logger", "info",  msg)
  def warn (msg: Any): Any  = _perform("Logger", "warn",  msg)
  def error(msg: Any): Any  = _perform("Logger", "error", msg)
  def debug(msg: Any): Any  = _perform("Logger", "debug", msg)

  def apply(name: String): Logger  = new Logger(name)
  def apply(cls: Class[?]): Logger = new Logger(cls.getName)

/** Mixin for convenient per-class named loggers. */
trait Logging:
  protected val log: Logger = Logger(getClass)

// ── runtime-server-common (inlined from classpath resources) ──────────
// Source of truth: runtime-server-common/src/main/scala/scalascript/server/*.scala

/** Thrown by `requireString` / `requireInt` / ... when an incoming
 *  request is missing a required field or carries an unparseable
 *  value.  Caught at the route-dispatch boundary in [[WebServer]] and
 *  converted to a `400 Bad Request` response so handlers can keep
 *  writing linear validation code without explicit error checks. */
final class RestValidationError(msg: String) extends RuntimeException(msg)


/** Minimal DER (Distinguished Encoding Rules) helpers shared between
 *  the JWT RSA key loader (`JwtRsa`) and the TLS cert/key loader
 *  (`WebServer.buildSslContext`).  Kept here so the lower runtime layer
 *  has everything it needs to parse RSA keys without depending on
 *  WebServer.scala. */
object DerCodec:

  /** Wrap a PKCS#1 RSA key (no envelope) into the PKCS#8 DER structure
   *  that `PKCS8EncodedKeySpec` expects.  The RSA OID is 1.2.840.113549.1.1.1. */
  def wrapPkcs1InPkcs8(pkcs1: Array[Byte]): Array[Byte] =
    val oidSeq = Array[Byte](
      0x30, 0x0d,
      0x06, 0x09,
      0x2a, 0x86.toByte, 0x48, 0x86.toByte, 0xf7.toByte, 0x0d, 0x01, 0x01, 0x01,
      0x05, 0x00
    )
    val octetStr = encodeDerTlv(0x04, pkcs1)
    encodeDerTlv(0x30, Array[Byte](0x02, 0x01, 0x00) ++ oidSeq ++ octetStr)

  def encodeDerTlv(tag: Byte, value: Array[Byte]): Array[Byte] =
    val len = value.length
    val lenBytes =
      if len < 128 then Array(len.toByte)
      else if len < 256 then Array(0x81.toByte, len.toByte)
      else Array(0x82.toByte, (len >> 8).toByte, (len & 0xff).toByte)
    Array(tag) ++ lenBytes ++ value


import java.nio.charset.StandardCharsets

/** RFC-6455 frame parser and encoder.  Pure: no IO, no state held here —
 *  callers feed bytes in via [[tryParse]] and get back either a complete
 *  frame (with the count of bytes consumed) or `None` (need more bytes).
 *
 *  Supports the four wire-level pieces we need for an interactive WS:
 *    - text frames (opcode 0x1) — payload decoded as UTF-8
 *    - binary frames (opcode 0x2) — payload kept as raw bytes
 *    - ping  (0x9) / pong (0xA) — control frames
 *    - close (0x8) — connection-close request
 *
 *  Fragmentation is NOT handled here — every parsed frame is treated as
 *  self-contained.  A future revision can extend the parser to track
 *  partial messages across FIN=0 / opcode=0 continuation frames. */
object WsFraming:

  // GUID from RFC 6455 §1.3; concat with the client `Sec-WebSocket-Key`
  // and base64(SHA-1(·)) to produce `Sec-WebSocket-Accept`.
  val Magic: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

  /** Hard cap on a single frame's payload — protects the server from
   *  hostile clients announcing a multi-gigabyte payload (which we'd
   *  otherwise try to allocate up front).  16 MB is well above any
   *  realistic browser-sent message; bigger payloads can still be
   *  delivered as multiple fragmented frames once continuation support
   *  lands. */
  val MaxFrameBytes: Int = 16 * 1024 * 1024

  enum Opcode(val code: Int):
    case Continuation extends Opcode(0x0)
    case Text         extends Opcode(0x1)
    case Binary       extends Opcode(0x2)
    case Close        extends Opcode(0x8)
    case Ping         extends Opcode(0x9)
    case Pong         extends Opcode(0xA)

  object Opcode:
    def fromCode(c: Int): Option[Opcode] = c match
      case 0x0 => Some(Continuation)
      case 0x1 => Some(Text)
      case 0x2 => Some(Binary)
      case 0x8 => Some(Close)
      case 0x9 => Some(Ping)
      case 0xA => Some(Pong)
      case _   => None

  /** A complete, demasked frame.  `payload` is the raw byte payload
   *  (already unmasked when the client side set MASK=1, as it always
   *  must).  `consumed` is the number of bytes from the input buffer
   *  that this frame occupied — callers slide their read buffer forward
   *  by exactly that many bytes. */
  final case class Frame(
      fin:      Boolean,
      opcode:   Opcode,
      payload:  Array[Byte],
      consumed: Int
  ):
    def textPayload: String = new String(payload, StandardCharsets.UTF_8)

  /** Attempt to parse one frame from `buf[offset, until)`.  Returns
   *  `Some(frame)` when a full frame is present, `None` when more bytes
   *  are needed.  Throws [[WsProtocolError]] if the bytes already in
   *  the buffer cannot form a valid frame (unknown opcode, oversized
   *  payload, …) — caller should close the connection. */
  def tryParse(buf: Array[Byte], offset: Int, until: Int): Option[Frame] =
    val avail = until - offset
    if avail < 2 then return None
    val b0  = buf(offset)     & 0xFF
    val b1  = buf(offset + 1) & 0xFF
    val fin = (b0 & 0x80) != 0
    val op  = b0 & 0x0F
    val masked = (b1 & 0x80) != 0
    val len7   = b1 & 0x7F
    val opcode = Opcode.fromCode(op).getOrElse(
      throw WsProtocolError(s"Unknown opcode 0x${op.toHexString}")
    )

    // Resolve the payload length.  RFC encodes it in three forms:
    //   len7 = 0..125    → that's the length
    //   len7 = 126       → next 2 bytes (big-endian) carry length
    //   len7 = 127       → next 8 bytes (big-endian); we cap at Int.MaxValue
    var hdrLen = 2
    val payloadLen: Long =
      if len7 <= 125 then len7.toLong
      else if len7 == 126 then
        if avail < hdrLen + 2 then return None
        hdrLen += 2
        ((buf(offset + 2) & 0xFF).toLong << 8) | (buf(offset + 3) & 0xFF).toLong
      else
        if avail < hdrLen + 8 then return None
        hdrLen += 8
        var v = 0L
        var i = 0
        while i < 8 do
          v = (v << 8) | (buf(offset + 2 + i) & 0xFF).toLong
          i += 1
        v

    if payloadLen > MaxFrameBytes.toLong then
      throw WsProtocolError(s"Frame too large: $payloadLen bytes (max $MaxFrameBytes)")

    // Mask key (4 bytes) is present iff MASK=1.  Client-to-server frames
    // MUST mask; server-to-client frames MUST NOT.  Both directions go
    // through the same parser — `tryParse` is happy either way.
    val maskLen = if masked then 4 else 0
    val totalLen = hdrLen + maskLen + payloadLen.toInt
    if avail < totalLen then return None

    val mask = if masked then
      Array.tabulate(4)(i => buf(offset + hdrLen + i))
    else null

    val payload = new Array[Byte](payloadLen.toInt)
    val payloadStart = offset + hdrLen + maskLen
    if masked then
      var i = 0
      while i < payloadLen.toInt do
        payload(i) = (buf(payloadStart + i) ^ mask(i % 4)).toByte
        i += 1
    else
      System.arraycopy(buf, payloadStart, payload, 0, payloadLen.toInt)

    Some(Frame(fin, opcode, payload, totalLen))

  /** Encode a server-side text frame (FIN=1, MASK=0). */
  def encodeText(s: String): Array[Byte] =
    encodeFrame(Opcode.Text, s.getBytes(StandardCharsets.UTF_8))

  /** Encode a server-side binary frame. */
  def encodeBinary(bytes: Array[Byte]): Array[Byte] =
    encodeFrame(Opcode.Binary, bytes)

  /** Encode a server-side pong (RFC 6455 §5.5.3 — echo back the ping
   *  payload). */
  def encodePong(payload: Array[Byte]): Array[Byte] =
    encodeFrame(Opcode.Pong, payload)

  /** Encode a server-initiated ping.  Payload is the timestamp-ish
   *  body the peer must echo verbatim in a Pong — empty by default
   *  for cheapest heartbeat. */
  def encodePing(payload: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    encodeFrame(Opcode.Ping, payload)

  /** Encode a server-side close (status + optional reason).  Status code
   *  is the 2-byte big-endian close code; 1000 = normal closure. */
  def encodeClose(status: Int, reason: String = ""): Array[Byte] =
    val rb = reason.getBytes(StandardCharsets.UTF_8)
    val payload = new Array[Byte](2 + rb.length)
    payload(0) = ((status >> 8) & 0xFF).toByte
    payload(1) = (status & 0xFF).toByte
    System.arraycopy(rb, 0, payload, 2, rb.length)
    encodeFrame(Opcode.Close, payload)

  private def encodeFrame(opcode: Opcode, payload: Array[Byte]): Array[Byte] =
    val len = payload.length
    val buf =
      if len <= 125 then
        val b = new Array[Byte](2 + len)
        b(0) = (0x80 | opcode.code).toByte // FIN=1
        b(1) = len.toByte                  // MASK=0
        System.arraycopy(payload, 0, b, 2, len)
        b
      else if len <= 0xFFFF then
        val b = new Array[Byte](4 + len)
        b(0) = (0x80 | opcode.code).toByte
        b(1) = 126.toByte
        b(2) = ((len >> 8) & 0xFF).toByte
        b(3) = (len & 0xFF).toByte
        System.arraycopy(payload, 0, b, 4, len)
        b
      else
        val b = new Array[Byte](10 + len)
        b(0) = (0x80 | opcode.code).toByte
        b(1) = 127.toByte
        var i = 0
        var v = len.toLong
        while i < 8 do
          b(9 - i) = (v & 0xFF).toByte
          v >>>= 8
          i += 1
        System.arraycopy(payload, 0, b, 10, len)
        b
    buf

  /** Build the `Sec-WebSocket-Accept` value for a given client key.
   *  base64(SHA-1(key + MAGIC_GUID)). */
  def acceptKey(clientKey: String): String =
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val digest = md.digest((clientKey + Magic).getBytes(StandardCharsets.US_ASCII))
    java.util.Base64.getEncoder.encodeToString(digest)

  /** Indicates the bytes already in the parser buffer are syntactically
   *  invalid — the connection should be closed without waiting for more
   *  data.  Distinct from `None` (more bytes might fix things). */
  final class WsProtocolError(msg: String) extends Exception(msg)


import java.util.concurrent.atomic.AtomicLong

/** Process-global counters surfaced through the `metrics()` native.
 *
 *  Lives on a single object so every backend (interpreter `WsProxy`,
 *  JvmGen-emitted server, etc.) can poke the same counter set
 *  without each one having to plumb a metrics handle through every
 *  call site.  Counters are `AtomicLong` so updates from the
 *  selector / read-loop threads and from the executor don't race.
 *
 *  Naming convention: dotted, lowercase, namespace-prefixed
 *  (`ws.*`, `http.*`).  Stable across releases — log shippers
 *  scrape by exact name.
 */
object Metrics:

  // ── WebSocket ────────────────────────────────────────────────────

  /** Current live WS connections — single source of truth (also
   *  consulted by [[WsConnection.tryReserveSlot]] for the cap). */
  val wsActive:      AtomicLong = AtomicLong(0L)
  /** Cumulative successful upgrades (101 served, handler invoked). */
  val wsUpgraded:    AtomicLong = AtomicLong(0L)
  /** Cumulative refused upgrades (any 4xx/5xx returned at the
   *  upgrade dispatch: Origin denied, cap reached, auth refused,
   *  subprotocol mismatch). */
  val wsRejected:    AtomicLong = AtomicLong(0L)
  /** Cumulative inbound app messages (Text+Binary frames, after
   *  fragmentation reassembly; rate-limited drops don't count). */
  val wsMessagesIn:  AtomicLong = AtomicLong(0L)
  /** Cumulative outbound app frames (Text+Binary; control frames
   *  Ping/Pong/Close excluded). */
  val wsMessagesOut: AtomicLong = AtomicLong(0L)
  val wsBytesIn:     AtomicLong = AtomicLong(0L)
  val wsBytesOut:    AtomicLong = AtomicLong(0L)

  // ── HTTP ─────────────────────────────────────────────────────────

  /** Cumulative HTTP requests handled by the embedded HttpServer
   *  (incremented by `WebServer.handle` on every request). */
  val httpRequests:  AtomicLong = AtomicLong(0L)
  val http4xx:       AtomicLong = AtomicLong(0L)
  val http5xx:       AtomicLong = AtomicLong(0L)

  /** Build a snapshot Map view of every counter.  Allocates a fresh
   *  map per call; intended for scraping, not for hot-path use. */
  def snapshot(): Map[String, Long] = Map(
    "ws.active"       -> wsActive.get,
    "ws.upgraded"     -> wsUpgraded.get,
    "ws.rejected"     -> wsRejected.get,
    "ws.messages.in"  -> wsMessagesIn.get,
    "ws.messages.out" -> wsMessagesOut.get,
    "ws.bytes.in"     -> wsBytesIn.get,
    "ws.bytes.out"    -> wsBytesOut.get,
    "http.requests"   -> httpRequests.get,
    "http.4xx"        -> http4xx.get,
    "http.5xx"        -> http5xx.get
  )

  /** Reset every counter to zero.  Used by tests; not exposed to
   *  user code (no `resetMetrics()` native) — production code
   *  should treat counters as monotonic until process restart. */
  def reset(): Unit =
    wsActive.set(0L);      wsUpgraded.set(0L);     wsRejected.set(0L)
    wsMessagesIn.set(0L);  wsMessagesOut.set(0L)
    wsBytesIn.set(0L);     wsBytesOut.set(0L)
    httpRequests.set(0L);  http4xx.set(0L);        http5xx.set(0L)


import java.util.concurrent.ConcurrentHashMap

/** Lightweight fixed-window rate limiter for protecting hot endpoints
 *  (login, password reset, OTP submit) against brute-force attempts.
 *
 *  `tryAcquire(key, limit, windowSeconds)` returns `true` if the call
 *  is allowed and bumps the counter for the current window; `false`
 *  if `limit` requests have already happened within `windowSeconds`.
 *  Different keys are tracked independently — the usual pattern is to
 *  key on `s"login:${clientIp(req)}"` or per-user.
 *
 *  Fixed-window is the simplest counter scheme — gets up to 2× the
 *  nominal rate at window boundaries.  For finer accuracy a sliding
 *  window or token bucket would do, but for an HTTP auth guard the
 *  approximation is fine.  No background thread; entries are GC'd
 *  lazily on next access. */
object RateLimit:
  private case class Bucket(count: java.util.concurrent.atomic.AtomicLong, windowStartMs: Long)

  private val buckets = ConcurrentHashMap[String, Bucket]()

  def tryAcquire(key: String, limit: Long, windowSeconds: Long): Boolean =
    val now      = java.lang.System.currentTimeMillis()
    val windowMs = windowSeconds * 1000L
    val current  = buckets.get(key)
    if current == null || now - current.windowStartMs >= windowMs then
      val fresh = Bucket(java.util.concurrent.atomic.AtomicLong(1L), now)
      val prior = buckets.put(key, fresh)
      // If someone else just inserted at the same moment, fold their count in.
      if prior != null && now - prior.windowStartMs < windowMs then
        fresh.count.addAndGet(prior.count.get())
      fresh.count.get() <= limit
    else
      current.count.incrementAndGet() <= limit

  /** Reset the counter for a key — handy after a successful login. */
  def reset(key: String): Unit =
    buckets.remove(key)

  /** Wipe everything (tests). */
  def clear(): Unit = buckets.clear()


import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** PBKDF2-HMAC-SHA256 password hashing.
 *
 *  Encoded format (Django-style, self-describing so we can swap the
 *  underlying KDF without rotating every stored hash at once):
 *
 *      pbkdf2$iter=<N>$<b64_salt>$<b64_hash>
 *
 *  Default work factor: 200_000 PBKDF2 iterations + 16-byte salt +
 *  32-byte output.  OWASP's 2023 recommendation for PBKDF2-SHA256 is
 *  600k iterations; we pick a lower default so the demo runs are fast
 *  but call sites can override via [[hash]]'s `iter` argument.
 *
 *  `verify` uses [[MessageDigest.isEqual]] for constant-time hash
 *  comparison.  Malformed encodings return `false` instead of raising
 *  so a corrupted database row can't crash the login path. */
object Password:
  private val DefaultIterations = 200_000
  private val SaltBytes         = 16
  private val HashBits          = 256
  private val rng               = SecureRandom()
  private val b64Enc            = Base64.getEncoder.withoutPadding
  private val b64Dec            = Base64.getDecoder

  def hash(password: String, iter: Int = DefaultIterations): String =
    val salt = new Array[Byte](SaltBytes)
    rng.nextBytes(salt)
    val key = pbkdf2(password, salt, iter, HashBits)
    s"pbkdf2$$iter=$iter$$${b64Enc.encodeToString(salt)}$$${b64Enc.encodeToString(key)}"

  def verify(password: String, encoded: String): Boolean =
    try
      val parts = encoded.split('$')
      if parts.length != 4 || parts(0) != "pbkdf2" then false
      else
        val iter = parts(1).stripPrefix("iter=").toInt
        val salt = b64Dec.decode(parts(2))
        val expected = b64Dec.decode(parts(3))
        val actual   = pbkdf2(password, salt, iter, expected.length * 8)
        MessageDigest.isEqual(expected, actual)
    catch case _: Throwable => false

  private def pbkdf2(password: String, salt: Array[Byte], iter: Int, bits: Int): Array[Byte] =
    val spec    = PBEKeySpec(password.toCharArray, salt, iter, bits)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    try factory.generateSecret(spec).getEncoded
    finally spec.clearPassword()


import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** RFC 6238 time-based one-time passwords.  Compatible with Google
 *  Authenticator, Authy, 1Password, etc.: HMAC-SHA1, 30-second step,
 *  6-digit decimal code, base32-encoded shared secret.
 *
 *  Typical flow:
 *
 *    val secret = totpSecret()                                  // store per user
 *    val uri    = totpUri(secret, "alice@example.com", "MyApp") // show as QR
 *    // …user enrolls in authenticator app, types in current 6-digit code…
 *    if !totpValid(secret, code) then reject else accept */
object Totp:
  private val rng = SecureRandom()

  /** Generate a fresh 20-byte secret, encoded base32 (RFC 4648). */
  def secret(): String =
    val bytes = new Array[Byte](20)
    rng.nextBytes(bytes)
    base32Encode(bytes)

  /** Build the standard `otpauth://totp/...` URI suitable for a QR code.
   *  Authenticator apps recognise the `issuer:account` label format. */
  def uri(secret: String, account: String, issuer: String = ""): String =
    val labelIssuer = if issuer.isEmpty then "" else issuer + ":"
    val label   = java.net.URLEncoder.encode(labelIssuer + account, "UTF-8").replace("+", "%20")
    val params  = scala.collection.mutable.LinkedHashMap[String, String](
      "secret"    -> secret,
      "algorithm" -> "SHA1",
      "digits"    -> "6",
      "period"    -> "30",
    )
    if issuer.nonEmpty then params("issuer") = issuer
    val qs = params.iterator.map((k, v) =>
      s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
    ).mkString("&")
    s"otpauth://totp/$label?$qs"

  /** Current 6-digit TOTP code for the given secret, anchored to
   *  `nowSeconds()` (Unix seconds, UTC) modulo the 30-second step. */
  def code(secret: String, nowSeconds: Long = java.lang.System.currentTimeMillis() / 1000L): String =
    codeAt(secret, nowSeconds / 30L)

  /** Verify a user-supplied code against the secret.  `skew` allows
   *  matching codes from `skew` steps before or after the current one
   *  to absorb small clock drift between server and authenticator.
   *  Constant-time comparison; rejects malformed (non-digit) input. */
  def valid(secret: String, code: String, skew: Int = 1): Boolean =
    if code == null || code.length != 6 || !code.forall(_.isDigit) then false
    else
      val now = java.lang.System.currentTimeMillis() / 1000L / 30L
      var i = -skew
      var ok = false
      while i <= skew do
        if constEq(codeAt(secret, now + i), code) then ok = true
        i += 1
      ok

  private def codeAt(secret: String, counter: Long): String =
    val key = base32Decode(secret)
    val buf = new Array[Byte](8)
    var c = counter
    var i = 7
    while i >= 0 do { buf(i) = (c & 0xff).toByte; c >>>= 8; i -= 1 }
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    val h = mac.doFinal(buf)
    val off = h(h.length - 1) & 0x0f
    val bin = ((h(off)     & 0x7f) << 24) |
              ((h(off + 1) & 0xff) << 16) |
              ((h(off + 2) & 0xff) <<  8) |
               (h(off + 3) & 0xff)
    val n = bin % 1_000_000
    f"$n%06d"

  private def constEq(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i = 0
      while i < a.length do { diff |= a.charAt(i) ^ b.charAt(i); i += 1 }
      diff == 0

  // ── Base32 RFC 4648 ────────────────────────────────────────────────
  private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
  private val DecodeTable: Array[Int] =
    val t = Array.fill(128)(-1)
    Alphabet.zipWithIndex.foreach((c, i) => t(c.toInt) = i)
    t

  private def base32Encode(bytes: Array[Byte]): String =
    val sb = StringBuilder()
    var buf = 0L
    var bits = 0
    for b <- bytes do
      buf = (buf << 8) | (b & 0xffL)
      bits += 8
      while bits >= 5 do
        bits -= 5
        sb.append(Alphabet.charAt(((buf >> bits) & 0x1f).toInt))
    if bits > 0 then sb.append(Alphabet.charAt(((buf << (5 - bits)) & 0x1f).toInt))
    sb.toString

  private def base32Decode(s: String): Array[Byte] =
    val clean = s.toUpperCase.filter(c => c != '=' && c != ' ')
    val out   = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var buf   = 0L
    var bits  = 0
    for c <- clean do
      val v = if c.toInt < 128 then DecodeTable(c.toInt) else -1
      if v >= 0 then
        buf = (buf << 5) | v.toLong
        bits += 5
        if bits >= 8 then
          bits -= 8
          out += ((buf >> bits) & 0xff).toByte
    out.toArray


import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.security.MessageDigest

/** HS256-signed JWTs for ScalaScript's HTTP runtime.
 *
 *  Wire format (RFC 7519):
 *    `<b64url(header)>.<b64url(payload)>.<b64url(hmac_sha256(header_b64.payload_b64))>`
 *  Header is fixed to `{"alg":"HS256","typ":"JWT"}`.  Payload is a
 *  `Map[String, String]` serialised as JSON object — keeps the API
 *  symmetrical with [[SessionCookie]] without dragging in a full JSON
 *  encoder for the small surface a typical app needs (`sub`, `exp`,
 *  `role`, …).  Callers who want richer claims can stash a hand-built
 *  JSON string under a single key.
 *
 *  Secret resolution:
 *    1. `SSC_JWT_SECRET` env var (preferred — separate from session secret).
 *    2. `SSC_SESSION_SECRET` env var as a fallback so a tiny deployment
 *       only needs one secret in its env.
 *    3. Per-process random key with a one-line stderr warning.
 *
 *  `verify` rejects tampered signatures (constant-time compare) and
 *  tokens whose `exp` claim is in the past.  Other claim semantics
 *  (`nbf`, `iss`, `aud`) are left to the caller. */
object Jwt:
  private val _log = Logger("scalascript.server")
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  private val Header    = """{"alg":"HS256","typ":"JWT"}"""
  private val HeaderB64 = b64Enc.encodeToString(Header.getBytes("UTF-8"))

  /** Lazily resolved per-process secret. */
  private lazy val secret: Array[Byte] =
    sys.env.get("SSC_JWT_SECRET").filter(_.nonEmpty)
      .orElse(sys.env.get("SSC_SESSION_SECRET").filter(_.nonEmpty)) match
      case Some(s) => s.getBytes("UTF-8")
      case None    =>
        val bytes = new Array[Byte](32)
        java.security.SecureRandom().nextBytes(bytes)
        _log.warn(
          "[ssc] SSC_JWT_SECRET / SSC_SESSION_SECRET not set; JWTs signed " +
          "with a process-local random key.  Tokens will not survive a restart."
        )
        bytes

  private def hmacSha256(payload: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    mac.doFinal(payload)

  /** JSON-encode a `Map[String, String]` claim set. */
  private def jsonOf(m: Map[String, String]): String =
    def esc(s: String): String =
      val sb = StringBuilder().append('"')
      var i = 0
      while i < s.length do
        s.charAt(i) match
          case '"'  => sb.append("\\\"")
          case '\\' => sb.append("\\\\")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
          case c    => sb.append(c)
        i += 1
      sb.append('"').toString
    m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")

  /** Parse a JSON object whose values are all strings.  Returns `None`
   *  on any structural problem — `verify` must never throw. */
  private def parseJsonStringMap(s: String): Option[Map[String, String]] =
    val trimmed = s.trim
    if !trimmed.startsWith("{") || !trimmed.endsWith("}") then return None
    val inner = trimmed.substring(1, trimmed.length - 1).trim
    if inner.isEmpty then return Some(Map.empty)
    try
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
      var i  = 0
      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
      def readStr(): String =
        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
        i += 1
        val sb = StringBuilder()
        while i < inner.length && inner.charAt(i) != '"' do
          val c = inner.charAt(i)
          if c == '\\' && i + 1 < inner.length then
            inner.charAt(i + 1) match
              case '"'  => sb.append('"');  i += 2
              case '\\' => sb.append('\\'); i += 2
              case 'n'  => sb.append('\n'); i += 2
              case 'r'  => sb.append('\r'); i += 2
              case 't'  => sb.append('\t'); i += 2
              case 'u' if i + 5 < inner.length =>
                sb.append(Integer.parseInt(inner.substring(i + 2, i + 6), 16).toChar)
                i += 6
              case _ => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        if i >= inner.length then throw RuntimeException("unterminated string")
        i += 1
        sb.toString
      while i < inner.length do
        skipWs(); val k = readStr()
        skipWs()
        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
        i += 1
        skipWs(); val v = readStr()
        out(k) = v
        skipWs()
        if i < inner.length then
          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
          i += 1
      Some(out.toMap)
    catch case _: Throwable => None

  /** Sign a claim map into a compact JWT.  The caller decides whether
   *  to set `exp` etc.; `sign` does not touch the map. */
  def sign(claims: Map[String, String]): String =
    val payloadB64  = b64Enc.encodeToString(jsonOf(claims).getBytes("UTF-8"))
    val signingIn   = (HeaderB64 + "." + payloadB64).getBytes("UTF-8")
    val sigB64      = b64Enc.encodeToString(hmacSha256(signingIn))
    s"$HeaderB64.$payloadB64.$sigB64"

  /** Verify a JWT and return its claims.  Returns `None` for any of:
   *    - malformed token,
   *    - signature mismatch,
   *    - unsupported `alg`,
   *    - present `exp` claim that's not a non-negative integer or that
   *      lies in the past (Unix seconds, UTC). */
  def verify(token: String): Option[Map[String, String]] =
    val parts = token.split('.')
    if parts.length != 3 then return None
    val Array(h, p, s) = parts
    try
      val expected = b64Enc.encodeToString(hmacSha256((h + "." + p).getBytes("UTF-8")))
      if !MessageDigest.isEqual(expected.getBytes("UTF-8"), s.getBytes("UTF-8")) then return None
      val headerJson = String(b64Dec.decode(h), "UTF-8")
      if !headerJson.contains("\"alg\":\"HS256\"") then return None
      val claims = parseJsonStringMap(String(b64Dec.decode(p), "UTF-8")) match
        case Some(c) => c
        case None    => return None
      claims.get("exp") match
        case Some(expStr) =>
          val now = java.lang.System.currentTimeMillis() / 1000L
          try
            val exp = expStr.toLong
            if exp < now then None else Some(claims)
          catch case _: Throwable => None
        case None => Some(claims)
    catch case _: Throwable => None

  /** Extract a bearer token from an `Authorization: Bearer <token>` header. */
  def fromAuthHeader(authHeader: String): Option[String] =
    val trimmed = Option(authHeader).map(_.trim).getOrElse("")
    if trimmed.regionMatches(true, 0, "Bearer ", 0, 7) then Some(trimmed.substring(7).trim)
    else None


import java.security.{KeyFactory, Signature, PrivateKey, PublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64

/** RS256-signed JWTs (asymmetric, RFC 7518 §3.3).
 *
 *  Use this when verifiers are distinct from signers — multiple
 *  microservices verifying the same token without sharing a secret,
 *  or third-party consumers (mobile clients) that should only hold a
 *  public key.  When everything fits in one process, prefer the HS256
 *  surface in [[Jwt]] — half the code and the same security model.
 *
 *  Key resolution:
 *    - `SSC_JWT_PRIVATE_KEY` env var — PKCS#8 PEM-encoded RSA private
 *      key (used by `sign`).
 *    - `SSC_JWT_PUBLIC_KEY` env var — X.509 SubjectPublicKeyInfo PEM
 *      (used by `verify`).
 *
 *  Generate a fresh pair with:
 *    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out priv.pem
 *    openssl rsa -in priv.pem -pubout -out pub.pem
 */
object JwtRsa:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  private val Header    = """{"alg":"RS256","typ":"JWT"}"""
  private val HeaderB64 = b64Enc.encodeToString(Header.getBytes("UTF-8"))

  private lazy val privateKey: Option[PrivateKey] =
    sys.env.get("SSC_JWT_PRIVATE_KEY").filter(_.nonEmpty).map(parsePrivate)

  private lazy val publicKey: Option[PublicKey] =
    sys.env.get("SSC_JWT_PUBLIC_KEY").filter(_.nonEmpty).map(parsePublic)

  /** Strip PEM armor and decode the base64 body to raw DER bytes. */
  private def pemBytes(pem: String): Array[Byte] =
    val cleaned = pem
      .replaceAll("-----BEGIN [^-]+-----", "")
      .replaceAll("-----END [^-]+-----", "")
      .replaceAll("\\s+", "")
    Base64.getDecoder.decode(cleaned)

  private def parsePrivate(pem: String): PrivateKey =
    val raw  = pemBytes(pem)
    val der8 = if pem.contains("BEGIN RSA PRIVATE KEY") then DerCodec.wrapPkcs1InPkcs8(raw) else raw
    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der8))

  private def parsePublic(pem: String): PublicKey =
    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pemBytes(pem)))

  /** Encode a `Map[String, String]` payload as a compact JSON object. */
  private def jsonOf(m: Map[String, String]): String =
    def esc(s: String): String =
      val sb = StringBuilder().append('"')
      var i = 0
      while i < s.length do
        s.charAt(i) match
          case '"'  => sb.append("\\\"")
          case '\\' => sb.append("\\\\")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
          case c    => sb.append(c)
        i += 1
      sb.append('"').toString
    m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")

  /** Sign claims with the RSA private key read from
   *  `SSC_JWT_PRIVATE_KEY`.  Throws when the env var is unset / not
   *  PEM — calling code should validate config at startup. */
  def sign(claims: Map[String, String]): String =
    val pk         = privateKey.getOrElse(throw RuntimeException(
      "SSC_JWT_PRIVATE_KEY is not set (expected PKCS#8 RSA PEM)"))
    val payloadB64 = b64Enc.encodeToString(jsonOf(claims).getBytes("UTF-8"))
    val signingIn  = (HeaderB64 + "." + payloadB64).getBytes("UTF-8")
    val sig        = Signature.getInstance("SHA256withRSA")
    sig.initSign(pk)
    sig.update(signingIn)
    val sigB64 = b64Enc.encodeToString(sig.sign())
    s"$HeaderB64.$payloadB64.$sigB64"

  /** Verify a JWT against the RSA public key from `SSC_JWT_PUBLIC_KEY`.
   *  Returns `None` for malformed tokens, signature mismatches, or
   *  expired tokens (present `exp` claim in the past). */
  def verify(token: String): Option[Map[String, String]] =
    val pub = publicKey match
      case Some(k) => k
      case None    => return None
    val parts = token.split('.')
    if parts.length != 3 then return None
    val Array(h, p, s) = parts
    try
      val headerJson = String(b64Dec.decode(h), "UTF-8")
      if !headerJson.contains("\"alg\":\"RS256\"") then return None
      val sig = Signature.getInstance("SHA256withRSA")
      sig.initVerify(pub)
      sig.update((h + "." + p).getBytes("UTF-8"))
      if !sig.verify(b64Dec.decode(s)) then return None
      parseJsonStringMap(String(b64Dec.decode(p), "UTF-8")) match
        case None         => None
        case Some(claims) =>
          claims.get("exp") match
            case Some(expStr) =>
              val now = java.lang.System.currentTimeMillis() / 1000L
              try
                if expStr.toLong < now then None else Some(claims)
              catch case _: Throwable => None
            case None => Some(claims)
    catch case _: Throwable => None

  private def parseJsonStringMap(s: String): Option[Map[String, String]] =
    val trimmed = s.trim
    if !trimmed.startsWith("{") || !trimmed.endsWith("}") then return None
    val inner = trimmed.substring(1, trimmed.length - 1).trim
    if inner.isEmpty then return Some(Map.empty)
    try
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
      var i = 0
      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
      def readStr(): String =
        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
        i += 1
        val sb = StringBuilder()
        while i < inner.length && inner.charAt(i) != '"' do
          val c = inner.charAt(i)
          if c == '\\' && i + 1 < inner.length then
            inner.charAt(i + 1) match
              case '"'  => sb.append('"');  i += 2
              case '\\' => sb.append('\\'); i += 2
              case 'n'  => sb.append('\n'); i += 2
              case 'r'  => sb.append('\r'); i += 2
              case 't'  => sb.append('\t'); i += 2
              case _    => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        i += 1
        sb.toString
      while i < inner.length do
        skipWs(); val k = readStr()
        skipWs()
        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
        i += 1
        skipWs(); val v = readStr()
        out(k) = v
        skipWs()
        if i < inner.length then
          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
          i += 1
      Some(out.toMap)
    catch case _: Throwable => None


import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.security.MessageDigest

/** HMAC-signed cookie sessions for the interpreter's HTTP runtime.
 *
 *  Cookie value format:
 *    `<b64url(json(payload))>.<b64url(hmac_sha256(payload_b64))>`
 *
 *  Payload is a `Map[String, String]` serialised as a tiny JSON object
 *  (we don't need full JSON power here — keys are identifiers, values
 *  are short strings).  Both halves use the URL-safe base64 alphabet so
 *  the cookie value is itself safe to drop into a `Set-Cookie` header
 *  without further escaping.
 *
 *  Secret resolution:
 *    1. Environment variable `SSC_SESSION_SECRET` if set.
 *    2. Otherwise a process-local random secret, generated once and
 *       reused for the process lifetime.  Sessions don't survive a
 *       restart in that mode — fine for dev, surfaced via stderr.
 */
object SessionCookie:
  private val _log = Logger("scalascript.server")
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val b64Dec = Base64.getUrlDecoder

  // ── Cookie security config ─────────────────────────────────────────
  // Defaults are safe-but-permissive for local development: HttpOnly,
  // SameSite=Lax, no Secure (so localhost http works).  Production
  // deployments behind HTTPS should call cookieConfig(secure=true) and
  // may want sameSite="Strict" to block cross-site request linking.
  @volatile private var _secure   = false
  @volatile private var _sameSite = "Lax"
  def setCookieConfig(secure: Boolean, sameSite: String): Unit =
    _secure = secure
    _sameSite = sameSite match
      case s @ ("Strict" | "Lax" | "None") => s
      case _                               => "Lax"
  def cookieSecure:   Boolean = _secure
  def cookieSameSite: String  = _sameSite

  /** Lazily resolved per-process secret. */
  private lazy val secret: Array[Byte] = sys.env.get("SSC_SESSION_SECRET") match
    case Some(s) if s.nonEmpty => s.getBytes("UTF-8")
    case _ =>
      val bytes = new Array[Byte](32)
      java.security.SecureRandom().nextBytes(bytes)
      _log.warn(
        "[ssc] SSC_SESSION_SECRET not set; using a process-local random key. " +
        "Sessions will not survive a server restart."
      )
      bytes

  private def hmacSha256(payload: Array[Byte], key: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(payload)

  /** Constant-time equality so signature comparison doesn't leak timing. */
  private def constEq(a: Array[Byte], b: Array[Byte]): Boolean =
    MessageDigest.isEqual(a, b)

  /** Encode a `Map[String, String]` as a compact JSON object string. */
  private def jsonOf(m: Map[String, String]): String =
    def esc(s: String): String =
      val sb = StringBuilder().append('"')
      var i = 0
      while i < s.length do
        s.charAt(i) match
          case '"'  => sb.append("\\\"")
          case '\\' => sb.append("\\\\")
          case '\n' => sb.append("\\n")
          case '\r' => sb.append("\\r")
          case '\t' => sb.append("\\t")
          case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
          case c    => sb.append(c)
        i += 1
      sb.append('"').toString
    m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")

  /** Parse a JSON object string `{"k":"v", ...}` whose values are all
   *  strings, into a `Map[String, String]`.  Returns `None` on any
   *  structural problem — we never throw on a malformed cookie value. */
  private def parseJsonStringMap(s: String): Option[Map[String, String]] =
    val trimmed = s.trim
    if !trimmed.startsWith("{") || !trimmed.endsWith("}") then return None
    val inner = trimmed.substring(1, trimmed.length - 1).trim
    if inner.isEmpty then return Some(Map.empty)
    try
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
      var i  = 0
      def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
      def readStr(): String =
        if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
        i += 1
        val sb = StringBuilder()
        while i < inner.length && inner.charAt(i) != '"' do
          val c = inner.charAt(i)
          if c == '\\' && i + 1 < inner.length then
            inner.charAt(i + 1) match
              case '"'  => sb.append('"');  i += 2
              case '\\' => sb.append('\\'); i += 2
              case 'n'  => sb.append('\n'); i += 2
              case 'r'  => sb.append('\r'); i += 2
              case 't'  => sb.append('\t'); i += 2
              case 'u' if i + 5 < inner.length =>
                val hex = inner.substring(i + 2, i + 6)
                sb.append(Integer.parseInt(hex, 16).toChar); i += 6
              case _    => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        if i >= inner.length then throw RuntimeException("unterminated string")
        i += 1
        sb.toString
      while i < inner.length do
        skipWs()
        val k = readStr()
        skipWs()
        if i >= inner.length || inner.charAt(i) != ':' then throw RuntimeException("expected colon")
        i += 1
        skipWs()
        val v = readStr()
        out(k) = v
        skipWs()
        if i < inner.length then
          if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
          i += 1
      Some(out.toMap)
    catch case _: Throwable => None

  /** Sign and encode a session map into a cookie value. */
  def pack(payload: Map[String, String]): String =
    val jsonBytes = jsonOf(payload).getBytes("UTF-8")
    val body      = b64Enc.encodeToString(jsonBytes)
    val sig       = b64Enc.encodeToString(hmacSha256(body.getBytes("UTF-8"), secret))
    s"$body.$sig"

  /** Verify and decode a cookie value back into a session map.
   *  Returns `None` on any tampering / malformed input — never throws. */
  def unpack(cookieValue: String): Option[Map[String, String]] =
    val idx = cookieValue.indexOf('.')
    if idx <= 0 || idx == cookieValue.length - 1 then None
    else
      val body   = cookieValue.substring(0, idx)
      val sigStr = cookieValue.substring(idx + 1)
      try
        val expected = b64Enc.encodeToString(hmacSha256(body.getBytes("UTF-8"), secret))
        if !constEq(expected.getBytes("UTF-8"), sigStr.getBytes("UTF-8")) then None
        else
          val json = String(b64Dec.decode(body), "UTF-8")
          parseJsonStringMap(json)
      catch case _: Throwable => None

  /** Extract a `session=<value>` from a raw `Cookie:` header value. */
  def fromHeader(cookieHeader: String): Option[Map[String, String]] =
    val sessionPair = cookieHeader.split(';').iterator
      .map(_.trim)
      .find(_.startsWith("session="))
    sessionPair.flatMap(pair => unpack(pair.substring("session=".length)))

  /** Build a `Set-Cookie` header value for a session payload.
   *  Empty payload → cookie cleared (Max-Age=0).  The `secureFlag`
   *  argument is preserved for back-compat but the live cookie config
   *  set via [[setCookieConfig]] takes precedence when called. */
  def toSetCookie(payload: Map[String, String], secureFlag: Boolean = false): String =
    val secure   = secureFlag || _secure
    val attrs    = s"Path=/; HttpOnly; SameSite=$_sameSite" + (if secure then "; Secure" else "")
    if payload.isEmpty then s"session=; $attrs; Max-Age=0"
    else s"session=${pack(payload)}; $attrs"


import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in server-side session store.
 *
 *  When enabled via [[useStore]], `Response.html(...).withSession(payload)`
 *  no longer roundtrips the payload through the client cookie.  Instead
 *  it stores the payload in a process-local map keyed by a random SSID
 *  and sets `session=<b64url(json({"_ssid": ssid}))>.<sig>` as the
 *  cookie value.  `req.session` reads the SSID back out of the cookie
 *  payload, looks the entry up in the store, and surfaces the full
 *  Map[String, String] to the handler.
 *
 *  Why bother? Three reasons:
 *    1. Cookie size — browsers cap cookies at ~4KB.  Large payloads
 *       (lists of permissions, JWT bundles, …) overflow.
 *    2. Instant revocation — `clearSession()` wipes the store entry, so
 *       a stolen cookie stops working immediately even if it was
 *       captured before logout.
 *    3. Sensitive data — signed cookies are tamper-proof but readable
 *       by anyone with the bytes.  Server-side payloads stay on the
 *       server.
 *
 *  TTL: each `get` refreshes the entry's last-access timestamp.  A
 *  lazy sweep on every Nth access drops anything that hasn't been
 *  touched in `ttlSeconds`.  No background thread — keeps the runtime
 *  shape identical to the stateless mode. */
object SessionStore:
  private case class Entry(payload: Map[String, String], lastAccess: Long)

  private val enabled       = AtomicBoolean(false)
  private val store         = ConcurrentHashMap[String, Entry]()
  @volatile private var ttlMs: Long = 30L * 60L * 1000L  // 30 min idle by default
  private val accessCount   = java.util.concurrent.atomic.AtomicLong(0L)
  private val sweepEveryN   = 256                         // sweep on every 256th access

  /** Flip the store on for the rest of the process lifetime.  Safe to
   *  call more than once; later calls reset the TTL. */
  def useStore(ttlSeconds: Long = 30L * 60L): Unit =
    ttlMs = ttlSeconds * 1000L
    enabled.set(true)

  def isEnabled: Boolean = enabled.get()

  /** Store a fresh payload and return its SSID. */
  def put(payload: Map[String, String]): String =
    val bytes = new Array[Byte](24)
    java.security.SecureRandom().nextBytes(bytes)
    val ssid = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    store.put(ssid, Entry(payload, java.lang.System.currentTimeMillis()))
    maybeSweep()
    ssid

  /** Look an SSID up.  Returns `None` if missing or expired (and
   *  removes expired entries as a side-effect). */
  def get(ssid: String): Option[Map[String, String]] =
    Option(store.get(ssid)) match
      case None    => None
      case Some(e) =>
        val now = java.lang.System.currentTimeMillis()
        if now - e.lastAccess > ttlMs then
          store.remove(ssid, e)
          None
        else
          // Refresh last-access so an active session doesn't expire.
          store.put(ssid, e.copy(lastAccess = now))
          maybeSweep()
          Some(e.payload)

  /** Remove an entry — used by `clearSession()` for server-side logout. */
  def delete(ssid: String): Unit =
    store.remove(ssid)

  /** Drop entries that haven't been touched within `ttlMs`. */
  def sweep(): Unit =
    val now    = java.lang.System.currentTimeMillis()
    val cutoff = now - ttlMs
    val it     = store.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      if e.getValue.lastAccess < cutoff then it.remove()

  private def maybeSweep(): Unit =
    if accessCount.incrementAndGet() % sweepEveryN == 0 then sweep()

  /** Diagnostic. */
  def size: Int = store.size()

  /** Wipe everything — useful for tests. */
  def reset(): Unit =
    enabled.set(false)
    store.clear()
    ttlMs = 30L * 60L * 1000L
    accessCount.set(0L)


import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI

/** OAuth2 / OIDC helpers — authorization-code flow.
 *
 *  Two operations cover 90% of real use:
 *
 *  1. `authorizeUrl(provider, clientId, redirectUri, state, scope?)`
 *     — pure URL builder.  Hand-rolling these is the part developers
 *     get wrong (mismatched scopes, missing `response_type=code`,
 *     state forgotten); this packs the right defaults per provider.
 *
 *  2. `exchangeCode(provider, code, clientId, clientSecret, redirectUri)`
 *     — POSTs to the provider's token endpoint and returns the
 *     decoded JSON object as `Option[Map[String, String]]`.  Token,
 *     refresh token, expiry seconds, scope etc. are surfaced as the
 *     keys the provider sends.  Returns `None` on non-2xx or malformed
 *     responses; callers can `.flatMap(_.get("access_token"))` to get
 *     the token to use against the provider's API.
 *
 *  Built-in providers: `"google"` and `"github"`.  Custom providers
 *  can be supplied as `Map("authorizeUrl" -> ..., "tokenUrl" -> ...,
 *  "defaultScope" -> ...)`.
 *
 *  State token: the caller generates one (e.g. `csrfToken()`), stashes
 *  it in the session, and verifies it matches `req.query("state")` on
 *  the callback.  This module does NOT manage state — keeps the surface
 *  small and lets sessions/CSRF do their job. */
object OAuth:

  /** Preset provider configs.  Each entry's `authorizeUrl` is missing
   *  query params (built in `authorizeUrl`); `tokenUrl` is hit as-is
   *  with form-encoded body.  User-registered providers via
   *  [[registerProvider]] are layered on top — they can override
   *  individual fields or introduce wholly new providers. */
  private val builtin: Map[String, Map[String, String]] = Map(
    "google" -> Map(
      "authorizeUrl" -> "https://accounts.google.com/o/oauth2/v2/auth",
      "tokenUrl"     -> "https://oauth2.googleapis.com/token",
      "userinfoUrl"  -> "https://www.googleapis.com/oauth2/v3/userinfo",
      "defaultScope" -> "openid email profile",
    ),
    "github" -> Map(
      "authorizeUrl" -> "https://github.com/login/oauth/authorize",
      "tokenUrl"     -> "https://github.com/login/oauth/access_token",
      "userinfoUrl"  -> "https://api.github.com/user",
      "defaultScope" -> "user:email",
    ),
  )

  /** Runtime-registered providers — merged on top of `builtin`.  Each
   *  call replaces / overrides any prior entry under the same name. */
  private val custom: java.util.concurrent.ConcurrentHashMap[String, Map[String, String]] =
    java.util.concurrent.ConcurrentHashMap[String, Map[String, String]]()

  /** Register a new OAuth provider (or override fields of a built-in).
   *  Required keys: `authorizeUrl`, `tokenUrl`.  Optional: `userinfoUrl`
   *  (`oauthUserinfo` returns None if absent) and `defaultScope`. */
  def registerProvider(name: String, config: Map[String, String]): Unit =
    custom.put(name, config)

  /** All known providers — builtins layered under any runtime override. */
  def providers: Map[String, Map[String, String]] =
    builtin ++ scala.jdk.CollectionConverters.MapHasAsScala(custom).asScala.toMap

  private def cfg(provider: String, override_ : Map[String, String]): Map[String, String] =
    providers.getOrElse(provider, Map.empty) ++ override_

  private def urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

  /** Build the provider's authorize URL.  Caller picks a random
   *  `state`, stashes it in the session, and verifies on callback. */
  def authorizeUrl(
      provider:    String,
      clientId:    String,
      redirectUri: String,
      state:       String,
      scope:       String = "",
      extras:      Map[String, String] = Map.empty,
      providerCfg: Map[String, String] = Map.empty
  ): String =
    val c    = cfg(provider, providerCfg)
    val base = c.getOrElse("authorizeUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val effectiveScope =
      if scope.nonEmpty then scope else c.getOrElse("defaultScope", "")
    val params = scala.collection.mutable.LinkedHashMap[String, String](
      "response_type" -> "code",
      "client_id"     -> clientId,
      "redirect_uri"  -> redirectUri,
      "state"         -> state,
    )
    if effectiveScope.nonEmpty then params("scope") = effectiveScope
    extras.foreach((k, v) => params(k) = v)
    val qs = params.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    s"$base?$qs"

  /** Exchange an authorization code for tokens.  Returns the parsed
   *  response body as `Map[String, String]` on 2xx, otherwise `None`. */
  def exchangeCode(
      provider:     String,
      code:         String,
      clientId:     String,
      clientSecret: String,
      redirectUri:  String,
      providerCfg:  Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c        = cfg(provider, providerCfg)
    val tokenUrl = c.getOrElse("tokenUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val form = Map(
      "grant_type"    -> "authorization_code",
      "code"          -> code,
      "client_id"     -> clientId,
      "client_secret" -> clientSecret,
      "redirect_uri"  -> redirectUri,
    )
    val body = form.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(tokenUrl))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseTokenResponse(resp.body(), resp.headers().firstValue("content-type").orElse(""))
    catch case _: Throwable => None

  /** Refresh-token grant: trade a long-lived refresh token for a fresh
   *  access token.  Returns the parsed token response just like
   *  `exchangeCode`; providers typically include a new `access_token`,
   *  `expires_in`, and sometimes a rotated `refresh_token`. */
  def refreshToken(
      provider:     String,
      refreshToken: String,
      clientId:     String,
      clientSecret: String,
      providerCfg:  Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c        = cfg(provider, providerCfg)
    val tokenUrl = c.getOrElse("tokenUrl",
      throw IllegalArgumentException(s"unknown OAuth provider: $provider"))
    val form = Map(
      "grant_type"    -> "refresh_token",
      "refresh_token" -> refreshToken,
      "client_id"     -> clientId,
      "client_secret" -> clientSecret,
    )
    val body = form.iterator.map((k, v) => s"${urlEnc(k)}=${urlEnc(v)}").mkString("&")
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(tokenUrl))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept",        "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseTokenResponse(resp.body(), resp.headers().firstValue("content-type").orElse(""))
    catch case _: Throwable => None

  /** Fetch the provider's userinfo endpoint with the given access token.
   *  Returns the parsed JSON object as `Map[String, String]` (nested
   *  objects are flattened to their `toString`).  `None` on non-2xx
   *  or malformed responses.  GitHub requires a User-Agent header;
   *  we send a generic one. */
  def userinfo(
      provider:    String,
      accessToken: String,
      providerCfg: Map[String, String] = Map.empty
  ): Option[Map[String, String]] =
    val c   = cfg(provider, providerCfg)
    val url = c.getOrElse("userinfoUrl",
      throw IllegalArgumentException(s"no userinfoUrl for provider: $provider"))
    try
      val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Authorization", s"Bearer $accessToken")
        .header("Accept",        "application/json")
        .header("User-Agent",    "scalascript-oauth/0.6")
        .GET()
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() < 200 || resp.statusCode() >= 300 then None
      else parseJsonObject(resp.body())
    catch case _: Throwable => None

  /** Provider responses are either `application/json` or
   *  `application/x-www-form-urlencoded` (GitHub's default).  Accept
   *  both. */
  private def parseTokenResponse(body: String, contentType: String): Option[Map[String, String]] =
    if contentType.toLowerCase.contains("application/json") || body.trim.startsWith("{") then
      parseJsonObject(body)
    else
      Some(body.split('&').iterator.flatMap { pair =>
        val i = pair.indexOf('=')
        if i < 0 then None
        else Some(
          java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
          java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8"))
      }.toMap)

  /** Tiny JSON-object reader: keys + string/number/bool values only.
   *  Provider token responses don't nest, so this is enough. */
  private def parseJsonObject(s: String): Option[Map[String, String]] =
    val t = s.trim
    if !t.startsWith("{") || !t.endsWith("}") then None
    else
      val inner = t.substring(1, t.length - 1).trim
      if inner.isEmpty then Some(Map.empty)
      else try
        val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
        var i   = 0
        def skipWs(): Unit = while i < inner.length && inner.charAt(i).isWhitespace do i += 1
        def readStr(): String =
          if inner.charAt(i) != '"' then throw RuntimeException("expected quote")
          i += 1
          val sb = StringBuilder()
          while i < inner.length && inner.charAt(i) != '"' do
            val c = inner.charAt(i)
            if c == '\\' && i + 1 < inner.length then
              inner.charAt(i + 1) match
                case '"'  => sb.append('"');  i += 2
                case '\\' => sb.append('\\'); i += 2
                case 'n'  => sb.append('\n'); i += 2
                case 'r'  => sb.append('\r'); i += 2
                case 't'  => sb.append('\t'); i += 2
                case _    => sb.append(c); i += 1
            else { sb.append(c); i += 1 }
          i += 1
          sb.toString
        def readScalar(): String =
          val sb = StringBuilder()
          while i < inner.length && inner.charAt(i) != ',' && inner.charAt(i) != '}' do
            sb.append(inner.charAt(i)); i += 1
          sb.toString.trim
        // Skip a nested JSON value (object or array) and return its raw
        // source so the caller surfaces it as a single string.  Balances
        // braces/brackets and respects string literals containing them.
        def readNested(open: Char, close: Char): String =
          val sb = StringBuilder().append(inner.charAt(i)); i += 1
          var depth = 1
          while i < inner.length && depth > 0 do
            val c = inner.charAt(i)
            sb.append(c)
            if c == '"' then
              i += 1
              while i < inner.length && inner.charAt(i) != '"' do
                if inner.charAt(i) == '\\' && i + 1 < inner.length then
                  sb.append(inner.charAt(i)).append(inner.charAt(i + 1)); i += 2
                else { sb.append(inner.charAt(i)); i += 1 }
              if i < inner.length then { sb.append('"'); i += 1 }
            else
              if c == open  then depth += 1
              if c == close then depth -= 1
              i += 1
          sb.toString
        while i < inner.length do
          skipWs(); val k = readStr()
          skipWs()
          if inner.charAt(i) != ':' then throw RuntimeException("expected colon")
          i += 1
          skipWs()
          val v =
            if inner.charAt(i) == '"' then readStr()
            else if inner.charAt(i) == '{' then readNested('{', '}')
            else if inner.charAt(i) == '[' then readNested('[', ']')
            else readScalar()
          out(k) = v
          skipWs()
          if i < inner.length then
            if inner.charAt(i) != ',' then throw RuntimeException("expected comma")
            i += 1
            skipWs()
        Some(out.toMap)
      catch case _: Throwable => None


import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** WebAuthn / FIDO2 passkey building blocks (RFC 8809 / W3C Web
 *  Authentication Level 2).
 *
 *  This file covers the parts that don't touch CBOR or ECDSA yet:
 *
 *    - [[challenge]]      : mint a fresh 32-byte challenge (base64url)
 *                            and stash it with a TTL so the matching
 *                            attestation / assertion call can consume it.
 *    - [[consumeChallenge]] : look up + remove a pending challenge.
 *                              Returns `false` if it's missing or expired.
 *    - [[CredentialStore]] : in-memory store of `(userId → List[Credential])`.
 *                            Each credential carries a base64url credentialId
 *                            and a base64url-encoded COSE public key plus a
 *                            monotonic signCount.
 *
 *  The actual attestation / assertion verification lives in a follow-up
 *  commit — needs a small CBOR reader and ECDSA P-256 verification.
 *  Splitting the challenge / store layer out lets the example wire up
 *  the JS-side `navigator.credentials.create / get` glue first and gives
 *  the verifier something concrete to target. */
object WebAuthn:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val rng    = SecureRandom()

  // ── Pending challenges ────────────────────────────────────────────
  // Keyed by challenge string.  Carrying the userId lets a verifier
  // confirm the challenge was issued for the right account.  A fixed
  // 5-minute TTL — long enough for the user to interact with their
  // authenticator, short enough that a leaked challenge expires
  // before it's useful.
  private case class Pending(userId: String, issuedMs: Long)
  private val pending = ConcurrentHashMap[String, Pending]()
  private val TtlMs   = 5L * 60L * 1000L

  /** Mint a fresh challenge for `userId` and return it as a base64url
   *  string the client can pass straight into the WebAuthn API. */
  def challenge(userId: String): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    val s = b64Enc.encodeToString(bytes)
    pending.put(s, Pending(userId, java.lang.System.currentTimeMillis()))
    maybeSweep()
    s

  /** Consume a previously-issued challenge.  Returns `Some(userId)` if
   *  the challenge was outstanding and not expired; `None` otherwise.
   *  The challenge is removed from the store in both cases so it can't
   *  be replayed. */
  def consumeChallenge(s: String): Option[String] =
    Option(pending.remove(s)).filter { p =>
      java.lang.System.currentTimeMillis() - p.issuedMs <= TtlMs
    }.map(_.userId)

  private val sweepCounter = java.util.concurrent.atomic.AtomicLong(0L)
  private def maybeSweep(): Unit =
    if (sweepCounter.incrementAndGet() & 0xFF) == 0L then
      val cutoff = java.lang.System.currentTimeMillis() - TtlMs
      val it = pending.entrySet().iterator()
      while it.hasNext do
        val e = it.next()
        if e.getValue.issuedMs < cutoff then it.remove()

  // ── Credential store ──────────────────────────────────────────────
  // Process-local Map[userId → List[Credential]].  Per credential we
  // hold the credentialId (base64url, opaque to us), the COSE public
  // key (base64url-encoded raw CBOR bytes — verifier will decode), and
  // the latest signCount we've seen.  Authenticators bump signCount on
  // every assertion; a request whose count is ≤ the stored value is
  // either a replay or a cloned authenticator and must be rejected.
  final case class Credential(
      credentialId: String,
      publicKey:    String,
      signCount:    Long,
  )

  private val store = ConcurrentHashMap[String, java.util.List[Credential]]()

  // ── Optional disk persistence ──────────────────────────────────────
  // Without this, `store` is wiped by every server restart (deploy, crash,
  // reboot) — every enrolled passkey is lost and every device falls back to
  // whatever bootstrap mechanism guards enrolment. `configureStore` is a no-op
  // (in-memory only, unchanged default) until a caller opts in with a path.
  // Format: one line per credential, tab-separated
  // `userId\tcredentialId\tpublicKey\tsignCount` — no JSON dependency, mirrors
  // the plain-text-store convention already used elsewhere in this codebase
  // (e.g. busi's own TSV identity/session files).
  private val storeLock = Object()
  private var storePath: Option[java.nio.file.Path] = None

  /** Opt into disk persistence at `path`, loading any existing entries from it
   *  immediately (e.g. on server startup, before any request is served). Safe
   *  to call more than once (e.g. from a test); each call reloads from `path`. */
  def configureStore(path: String): Unit =
    val p = java.nio.file.Paths.get(path)
    storeLock.synchronized {
      storePath = Some(p)
      store.clear()
      if java.nio.file.Files.exists(p) then
        val lines = java.nio.file.Files.readAllLines(p)
        val it = lines.iterator()
        while it.hasNext do
          val line = it.next()
          if line.nonEmpty then
            line.split("\t", -1) match
              case Array(userId, credentialId, publicKey, signCountStr) =>
                val list = store.computeIfAbsent(userId, _ => java.util.ArrayList[Credential]())
                list.add(Credential(credentialId, publicKey, signCountStr.toLongOption.getOrElse(0L)))
              case _ => () // skip a malformed/partial line rather than fail startup
    }

  /** Rewrite the whole store file from the in-memory map (small store, simple
   *  full-rewrite; tmp-file + atomic move avoids a torn write on crash). No-op
   *  if `configureStore` was never called. Must be invoked under `storeLock`. */
  private def persist(): Unit =
    storePath.foreach { p =>
      val tmp = p.resolveSibling(p.getFileName.toString + ".tmp")
      val sb = StringBuilder()
      val entries = scala.jdk.CollectionConverters.MapHasAsScala(store).asScala
      entries.foreach { (userId, list) =>
        list.synchronized {
          val it = list.iterator()
          while it.hasNext do
            val c = it.next()
            sb.append(userId).append('\t').append(c.credentialId).append('\t')
              .append(c.publicKey).append('\t').append(c.signCount).append('\n')
        }
      }
      java.nio.file.Files.createDirectories(p.getParent)
      java.nio.file.Files.write(tmp, sb.toString.getBytes("UTF-8"))
      java.nio.file.Files.move(tmp, p,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

  def storePut(userId: String, c: Credential): Unit =
    val list = store.computeIfAbsent(userId, _ => java.util.ArrayList[Credential]())
    list.synchronized {
      // Replace any prior entry with the same credentialId so a re-
      // enrolment overwrites instead of duplicating.
      val it = list.iterator()
      while it.hasNext do
        if it.next().credentialId == c.credentialId then it.remove()
      list.add(c)
    }
    storeLock.synchronized { persist() }

  def storeGet(userId: String): List[Credential] =
    Option(store.get(userId))
      .map(l => l.synchronized(java.util.ArrayList(l)))
      .map(scala.jdk.CollectionConverters.ListHasAsScala(_).asScala.toList)
      .getOrElse(Nil)

  def storeFind(userId: String, credentialId: String): Option[Credential] =
    storeGet(userId).find(_.credentialId == credentialId)

  /** Remove every credential enrolled for `userId` (e.g. "disable Face ID" on an
   *  account) — persisted like every other store mutation. Returns `true` if the
   *  user had any credentials to remove, `false` if they had none already. */
  def storeRemove(userId: String): Boolean =
    val removed = Option(store.remove(userId)).exists(!_.isEmpty)
    if removed then storeLock.synchronized { persist() }
    removed

  /** Update signCount after a successful assertion.  Returns true if
   *  the new count is strictly greater than the stored one (the
   *  authenticator counter advanced as expected); false on cloned /
   *  replayed signatures. */
  def storeUpdateSignCount(userId: String, credentialId: String, newCount: Long): Boolean =
    val ok = Option(store.get(userId)) match
      case None       => false
      case Some(list) =>
        list.synchronized {
          var ok = false
          val n = list.size
          var i = 0
          while i < n do
            val c = list.get(i)
            if c.credentialId == credentialId then
              if newCount > c.signCount then
                list.set(i, c.copy(signCount = newCount))
                ok = true
              i = n
            else i += 1
          ok
        }
    if ok then storeLock.synchronized { persist() }
    ok

  /** Wipe everything — tests. Also clears any configured store path (the next
   *  `configureStore` call starts fresh rather than reloading stale data). */
  def reset(): Unit =
    pending.clear()
    store.clear()
    storeLock.synchronized { storePath = None }

  // ── Registration verification ─────────────────────────────────────
  // Parses a `navigator.credentials.create()` response (clientDataJSON
  // + attestationObject, both base64url-encoded) and extracts the
  // credentialId + COSE public key that we need to remember.
  //
  // Scope deliberately narrow:
  //   - `none` attestation format only (the default Apple / 1Password
  //     / iOS passkeys use; `packed` and `fido-u2f` mean the
  //     authenticator vouches for its provenance, which is useful for
  //     enterprise but optional for typical sites).
  //   - No attestation-signature verification.  We trust on first use:
  //     the assertion flow's signature check binds future logins to
  //     the public key we extract here, so a malicious enrolment
  //     can't impersonate someone else's account.
  //
  // Returns Some((credentialIdB64, publicKeyB64, signCount, userId))
  // on success — `userId` comes from the consumed challenge so the
  // caller doesn't have to thread it through separately.
  final case class Registration(
      userId:       String,
      credentialId: String,
      publicKey:    String,
      signCount:    Long,
  )

  @annotation.nowarn("msg=Non local returns")
  def verifyRegistration(
      clientDataJSONB64:   String,
      attestationObjectB64: String,
      expectedOrigin:      String,
  ): Option[Registration] =
    try
      val b64 = Base64.getUrlDecoder
      val cd  = String(b64.decode(clientDataJSONB64), "UTF-8")
      // Parse the small clientDataJSON ourselves — only need 3 fields.
      val challenge = jsonStringField(cd, "challenge").getOrElse(return None)
      val origin    = jsonStringField(cd, "origin").getOrElse(return None)
      val ctype     = jsonStringField(cd, "type").getOrElse(return None)
      if ctype != "webauthn.create" then return None
      if origin != expectedOrigin then return None
      val userId = consumeChallenge(challenge).getOrElse(return None)
      // attestationObject is a CBOR map { fmt, attStmt, authData }.
      val attObj = Cbor.read(b64.decode(attestationObjectB64))
      val attMap = attObj match { case Cbor.MapV(m) => m; case _ => return None }
      val fmt = attMap.get(Cbor.StrV("fmt")) match
        case Some(Cbor.StrV(s)) => s
        case _                  => return None
      if fmt != "none" then return None  // future stages handle other fmts
      val authData = attMap.get(Cbor.StrV("authData")) match
        case Some(Cbor.BytesV(b)) => b
        case _                    => return None
      val parsed = parseAuthData(authData).getOrElse(return None)
      Some(Registration(userId, parsed.credentialIdB64, parsed.publicKeyB64, parsed.signCount))
    catch case _: Throwable => None

  /** Layout of authData (raw bytes from authenticator):
   *    [0..32)   rpIdHash      SHA-256(rpId)
   *    [32]      flags         UP / UV / AT / ED bits
   *    [33..37)  signCount     uint32 big-endian
   *    if flags & 0x40 (AT, attested credential data present):
   *      [37..53)         AAGUID                16 bytes
   *      [53..55)         credentialIdLength    uint16 big-endian
   *      [55..55+L)       credentialId          L bytes
   *      [55+L..end)      COSE public key       remaining CBOR
   */
  private final case class ParsedAuthData(
      credentialIdB64: String,
      publicKeyB64:    String,
      signCount:       Long,
  )

  private def parseAuthData(b: Array[Byte]): Option[ParsedAuthData] =
    if b.length < 37 then None
    else
      val flags     = b(32) & 0xff
      val signCount =
        ((b(33) & 0xffL) << 24) |
        ((b(34) & 0xffL) << 16) |
        ((b(35) & 0xffL) <<  8) |
         (b(36) & 0xffL)
      val attestedCredFlag = (flags & 0x40) != 0
      if !attestedCredFlag then None
      else if b.length < 55 then None
      else
        val credIdLen =
          ((b(53) & 0xff) << 8) | (b(54) & 0xff)
        if b.length < 55 + credIdLen then None
        else
          val credId  = b.slice(55, 55 + credIdLen)
          val pubKey  = b.slice(55 + credIdLen, b.length)
          val enc     = Base64.getUrlEncoder.withoutPadding
          Some(ParsedAuthData(
            enc.encodeToString(credId),
            enc.encodeToString(pubKey),
            signCount,
          ))

  // ── Authentication verification ───────────────────────────────────
  // Counterpart to verifyRegistration.  Takes the browser's
  // `navigator.credentials.get()` response and confirms the assertion
  // was actually signed by the stored credential's private key.
  //
  // Inputs (all base64url):
  //   clientDataJSONb64    : the JSON the client signed over
  //   authenticatorDataB64 : binary authData (rpIdHash + flags + signCount)
  //   signatureB64         : ASN.1 DER ECDSA signature
  //   credentialIdB64      : which credential the client claims to be
  //
  // Returns Some((userId, newSignCount)) on success — userId from the
  // consumed challenge, signCount already validated against the stored
  // monotonic counter and persisted via `storeUpdateSignCount`.
  final case class Assertion(userId: String, signCount: Long)

  @annotation.nowarn("msg=Non local returns")
  def verifyAssertion(
      clientDataJSONb64:    String,
      authenticatorDataB64: String,
      signatureB64:         String,
      credentialIdB64:      String,
      expectedOrigin:       String,
  ): Option[Assertion] =
    try
      val b64 = Base64.getUrlDecoder
      val cd  = String(b64.decode(clientDataJSONb64), "UTF-8")
      val challenge = jsonStringField(cd, "challenge").getOrElse(return None)
      val origin    = jsonStringField(cd, "origin").getOrElse(return None)
      val ctype     = jsonStringField(cd, "type").getOrElse(return None)
      if ctype != "webauthn.get" then return None
      if origin != expectedOrigin then return None
      val userId = consumeChallenge(challenge).getOrElse(return None)
      val cred   = storeFind(userId, credentialIdB64).getOrElse(return None)

      val authData = b64.decode(authenticatorDataB64)
      if authData.length < 37 then return None
      val newCount =
        ((authData(33) & 0xffL) << 24) |
        ((authData(34) & 0xffL) << 16) |
        ((authData(35) & 0xffL) <<  8) |
         (authData(36) & 0xffL)

      // Signed payload = authenticatorData || SHA-256(clientDataJSON).
      val sha     = java.security.MessageDigest.getInstance("SHA-256")
      val cdHash  = sha.digest(b64.decode(clientDataJSONb64))
      val signed  = authData ++ cdHash

      val pubKey  = decodeCosePublicKey(b64.decode(cred.publicKey)).getOrElse(return None)
      val sig     = java.security.Signature.getInstance("SHA256withECDSA")
      sig.initVerify(pubKey)
      sig.update(signed)
      if !sig.verify(b64.decode(signatureB64)) then return None

      // Monotonic signCount — protects against cloned authenticators
      // and replayed assertions.  Authenticators that don't implement
      // counters always send 0; permit that as a no-op (don't bump).
      if newCount > 0 then
        if !storeUpdateSignCount(userId, credentialIdB64, newCount) then return None
      Some(Assertion(userId, newCount))
    catch case _: Throwable => None

  /** Decode a COSE EC2 public key (RFC 8152 §13.1) into a JCA
   *  ECPublicKey.  The key is a CBOR map with negative-int keys:
   *      1 (kty) = 2 (EC2)
   *      3 (alg) = -7 (ES256)
   *     -1 (crv) = 1 (P-256)
   *     -2 (x)   = 32-byte big-endian X coordinate
   *     -3 (y)   = 32-byte big-endian Y coordinate */
  @annotation.nowarn("msg=Non local returns")
  private def decodeCosePublicKey(bytes: Array[Byte]): Option[java.security.PublicKey] =
    try
      val m = Cbor.read(bytes) match
        case Cbor.MapV(m) => m
        case _            => return None
      def getInt(k: Long): Option[Long] = m.collectFirst {
        case (Cbor.UIntV(v), Cbor.UIntV(x))                 if v == k => x
        case (Cbor.NegV(v),  Cbor.UIntV(x))                 if v == k => x
        case (Cbor.UIntV(v), Cbor.NegV(x))                  if v == k => x
      }
      def getBytes(k: Long): Option[Array[Byte]] = m.collectFirst {
        case (Cbor.UIntV(v), Cbor.BytesV(b)) if v == k => b
        case (Cbor.NegV(v),  Cbor.BytesV(b)) if v == k => b
      }
      val kty = getInt(1).getOrElse(return None)
      val alg = getInt(3).getOrElse(return None)
      val crv = getInt(-1).getOrElse(return None)
      if kty != 2 || alg != -7L || crv != 1 then return None
      val x = getBytes(-2).getOrElse(return None)
      val y = getBytes(-3).getOrElse(return None)
      val params = java.security.AlgorithmParameters.getInstance("EC")
      params.init(java.security.spec.ECGenParameterSpec("secp256r1"))
      val ecSpec = params.getParameterSpec(classOf[java.security.spec.ECParameterSpec])
      val point  = java.security.spec.ECPoint(
        java.math.BigInteger(1, x),
        java.math.BigInteger(1, y),
      )
      val spec = java.security.spec.ECPublicKeySpec(point, ecSpec)
      Some(java.security.KeyFactory.getInstance("EC").generatePublic(spec))
    catch case _: Throwable => None

  /** Extract a top-level string field from a flat JSON object —
   *  enough for clientDataJSON which is always `{type, challenge,
   *  origin, ...}` with no nested objects we care about. */
  private def jsonStringField(json: String, key: String): Option[String] =
    val needle = "\"" + key + "\""
    val ki = json.indexOf(needle)
    if ki < 0 then None
    else
      var i = ki + needle.length
      while i < json.length && json.charAt(i).isWhitespace do i += 1
      if i >= json.length || json.charAt(i) != ':' then None
      else
        i += 1
        while i < json.length && json.charAt(i).isWhitespace do i += 1
        if i >= json.length || json.charAt(i) != '"' then None
        else
          i += 1
          val sb = StringBuilder()
          while i < json.length && json.charAt(i) != '"' do
            val c = json.charAt(i)
            if c == '\\' && i + 1 < json.length then
              json.charAt(i + 1) match
                case '"'  => sb.append('"');  i += 2
                case '\\' => sb.append('\\'); i += 2
                case 'n'  => sb.append('\n'); i += 2
                case 'r'  => sb.append('\r'); i += 2
                case 't'  => sb.append('\t'); i += 2
                case '/'  => sb.append('/');  i += 2
                case _    => sb.append(c); i += 1
            else { sb.append(c); i += 1 }
          Some(sb.toString)

// ── Minimal CBOR reader ────────────────────────────────────────────
// Covers the subset attestationObject + COSE keys use: unsigned ints
// (major 0), negative ints (major 1), byte strings (major 2), text
// strings (major 3), arrays (major 4), maps (major 5), simple values
// (major 7).  No tags, no indefinite lengths, no big-int — these
// aren't generated by real-world authenticators.

private object Cbor:
  sealed trait Value
  case class UIntV(v: Long)              extends Value
  case class NegV(v: Long)               extends Value
  case class BytesV(b: Array[Byte])      extends Value
  case class StrV(s: String)             extends Value
  case class ArrV(items: List[Value])    extends Value
  case class MapV(m: Map[Value, Value])  extends Value
  case object NullV                      extends Value
  case object UndefV                     extends Value
  case class BoolV(b: Boolean)           extends Value
  case class FloatV(d: Double)           extends Value

  def read(bytes: Array[Byte]): Value =
    val (v, _) = readAt(bytes, 0)
    v

  private def readAt(b: Array[Byte], start: Int): (Value, Int) =
    val ib   = b(start) & 0xff
    val mt   = ib >>> 5
    val info = ib & 0x1f
    val (v, after) = readArg(b, start + 1, info)
    mt match
      case 0 => (UIntV(v), after)
      case 1 => (NegV(-1L - v), after)
      case 2 =>
        val len = v.toInt
        (BytesV(b.slice(after, after + len)), after + len)
      case 3 =>
        val len = v.toInt
        (StrV(String(b.slice(after, after + len), "UTF-8")), after + len)
      case 4 =>
        var i  = after
        val n  = v.toInt
        val xs = scala.collection.mutable.ArrayBuffer.empty[Value]
        var k  = 0
        while k < n do { val (it, ni) = readAt(b, i); xs += it; i = ni; k += 1 }
        (ArrV(xs.toList), i)
      case 5 =>
        var i  = after
        val n  = v.toInt
        val xs = scala.collection.mutable.LinkedHashMap.empty[Value, Value]
        var k  = 0
        while k < n do
          val (kk, ni) = readAt(b, i)
          val (vv, mi) = readAt(b, ni)
          xs(kk) = vv
          i = mi
          k += 1
        (MapV(xs.toMap), i)
      case 7 =>
        info match
          case 20 => (BoolV(false), after)
          case 21 => (BoolV(true),  after)
          case 22 => (NullV,        after)
          case 23 => (UndefV,       after)
          // floats: skip the v bytes already in `after`
          case _  => (FloatV(java.lang.Double.longBitsToDouble(v)), after)
      case _ =>
        throw RuntimeException(s"unsupported CBOR major type $mt")

  /** Decode the additional-info / length argument that follows the
   *  initial byte.  `info < 24` is its own value; 24/25/26/27 mean
   *  1/2/4/8 more bytes carry an unsigned big-endian integer. */
  private def readArg(b: Array[Byte], start: Int, info: Int): (Long, Int) =
    info match
      case n if n < 24 => (n.toLong, start)
      case 24 => ((b(start) & 0xffL), start + 1)
      case 25 =>
        ((b(start) & 0xffL) << 8 | (b(start + 1) & 0xffL), start + 2)
      case 26 =>
        var v = 0L
        var i = 0
        while i < 4 do { v = (v << 8) | (b(start + i) & 0xffL); i += 1 }
        (v, start + 4)
      case 27 =>
        var v = 0L
        var i = 0
        while i < 8 do { v = (v << 8) | (b(start + i) & 0xffL); i += 1 }
        (v, start + 8)
      case _  => throw RuntimeException(s"unsupported CBOR length info $info")


/** POJO description of a single `multipart/form-data` upload part.
 *
 *  `bytes` is the ISO-8859-1 view of the raw bytes (1 char = 1 byte) when
 *  the part is small enough to keep in memory; round-trip back to a byte
 *  array with `bytes.getBytes("ISO-8859-1")`.  When a part exceeds the
 *  spool-to-disk threshold, `bytes` is empty and `path` points to the
 *  temp file holding the bytes. */
case class UploadedFile(
    name:        String,
    filename:    String,
    contentType: String,
    size:        Int,
    bytes:       String,
    path:        String = ""
)


/** Pure HTTP helpers shared between the interpreter's `WebServer` and the
 *  JvmGen-emitted `serveRuntime` template.  Every function here is
 *  side-effect free and depends only on the JDK — no interpreter Values,
 *  no Scala-cli runtime imports. */
object HttpHelpers:

  /** Path-segment ADT for `parsePath` / `matchPath`. */
  enum Seg:
    case Lit(s: String)
    case Cap(name: String)

  /** Parse a route pattern like `/users/:id/posts` into a list of segments. */
  def parsePath(p: String): List[Seg] =
    p.split('/').toList.filter(_.nonEmpty).map { s =>
      if s.startsWith(":") then Seg.Cap(s.tail) else Seg.Lit(s)
    }

  /** Match a parsed path pattern against the path segments of a request.
   *  Returns `Some(captures)` on match, `None` on mismatch. */
  def matchPath(pat: List[Seg], segs: List[String]): Option[Map[String, String]] =
    if pat.length != segs.length then None
    else
      val ps = scala.collection.mutable.Map.empty[String, String]
      val ok = pat.zip(segs).forall {
        case (Seg.Lit(p), a)  => p == a
        case (Seg.Cap(n), a)  => ps(n) = a; true
      }
      if ok then Some(ps.toMap) else None

  /** Parse a URL query string `k1=v1&k2=v2` into a Map.  Both keys and
   *  values are `URLDecoder.decode`-d.  An empty / null input yields
   *  `Map.empty`.  Values without `=` become `key -> ""`. */
  def parseQuery(q: String): Map[String, String] =
    if q == null || q.isEmpty then Map.empty
    else q.split('&').iterator.flatMap { pair =>
      val i = pair.indexOf('=')
      if i < 0 then Some(java.net.URLDecoder.decode(pair, "UTF-8") -> "")
      else Some(
        java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8") ->
        java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
      )
    }.toMap

  /** Drain bytes from `in` up to and including the terminating
   *  `\r\n\r\n` sentinel that marks the end of an HTTP request /
   *  response head.  Returns the head as a byte array (caller
   *  decodes ISO-8859-1).  On EOF before the sentinel arrives,
   *  returns whatever was read — caller decides whether to treat
   *  a short head as an error.
   *
   *  Used by both the codegen-side proxy and the interpreter
   *  `TlsProxy` to peel off the request line + headers before
   *  deciding HTTP-vs-WS routing.  Linear in the head size; one
   *  byte per `read()` is fine here because heads are tiny
   *  (typically < 4 KB) and the caller wraps in a `BufferedInputStream`. */
  def readHttpHead(in: java.io.InputStream): Array[Byte] =
    val sb = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var prev3 = 0; var prev2 = 0; var prev1 = 0
    var done  = false
    while !done do
      val b = in.read()
      if b < 0 then return sb.toArray
      sb += b.toByte
      if prev3 == 13 && prev2 == 10 && prev1 == 13 && b == 10 then done = true
      prev3 = prev2; prev2 = prev1; prev1 = b
    sb.toArray

  /** Parsed HTTP request head — the bits both proxy entry points
   *  (interpreter `TlsProxy` + codegen `_proxyConnection`) need to
   *  decide HTTP-vs-WS routing and to build a `Request` snapshot.
   *  `headers` keys are lowercased so `req.headers.get("authorization")`
   *  works portably; `method` is the verb as it appeared on the wire
   *  (uppercased by convention but not normalised here). */
  final case class HttpRequestHead(
      request:  String,
      method:   String,
      path:     String,
      rawQuery: String,
      headers:  Map[String, String]
  ):
    /** True iff the head announces an RFC 6455 `Upgrade: websocket`
     *  + `Connection: upgrade` (matched case-insensitively). */
    def isUpgradeWebSocket: Boolean =
      headers.get("upgrade").exists(_.equalsIgnoreCase("websocket")) &&
      headers.get("connection").exists(_.toLowerCase.contains("upgrade"))

  /** Parse the bytes returned by [[readHttpHead]] into the
   *  request-line tuple + a header Map.  Tolerant of malformed
   *  lines (those without `:` are dropped) — same convention REST
   *  pipelines have always used.  Decoding is ISO-8859-1 because
   *  RFC 7230 §3 requires the head to be octet-clean and any
   *  UTF-8 body bytes only appear AFTER the `\r\n\r\n` sentinel. */
  def parseHttpHead(head: Array[Byte]): HttpRequestHead =
    val text  = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1)
    val lines = text.split("\r\n").toList
    val req   = lines.headOption.getOrElse("")
    val hdrs: Map[String, String] = lines.drop(1).flatMap { l =>
      val i = l.indexOf(':')
      if i < 0 then None
      else Some(l.substring(0, i).trim.toLowerCase -> l.substring(i + 1).trim)
    }.toMap
    val parts         = req.split(' ').toList
    val method        = parts.headOption.getOrElse("")
    val pathWithQuery = parts.lift(1).getOrElse("/")
    val path          = pathWithQuery.split('?').head
    val rawQuery      = if pathWithQuery.contains('?')
                        then pathWithQuery.split('?').lift(1).getOrElse("")
                        else ""
    HttpRequestHead(req, method, path, rawQuery, hdrs)

  /** Parse a `Cookie:` header value like `a=1; b=2; c=3` into a Map.
   *  Whitespace around `=` and `;` is trimmed.  Pairs without `=` are
   *  dropped silently — same lenient parser the REST request pipeline
   *  and the WS-upgrade path both used to inline. */
  def parseCookieHeader(raw: String): Map[String, String] =
    if raw == null || raw.isEmpty then Map.empty
    else raw.split(';').iterator.flatMap { pair =>
      val t = pair.trim
      val i = t.indexOf('=')
      if i < 0 then None
      else Some(t.substring(0, i).trim -> t.substring(i + 1).trim)
    }.toMap

  /** Best-effort MIME type for a file name.  Recognises the handful of
   *  extensions the static-asset server actually sees; for anything else
   *  falls back to JDK's `Files.probeContentType` and finally to
   *  `application/octet-stream`. */
  def contentTypeFor(name: String): String =
    val lower = name.toLowerCase
    val explicit: Option[String] = lower match
      case n if n.endsWith(".html") || n.endsWith(".htm") => Some("text/html; charset=utf-8")
      case n if n.endsWith(".css")  => Some("text/css; charset=utf-8")
      case n if n.endsWith(".js") || n.endsWith(".mjs") => Some("application/javascript; charset=utf-8")
      case n if n.endsWith(".json") => Some("application/json; charset=utf-8")
      case n if n.endsWith(".txt") || n.endsWith(".md") => Some("text/plain; charset=utf-8")
      case n if n.endsWith(".svg")  => Some("image/svg+xml")
      case n if n.endsWith(".png")  => Some("image/png")
      case n if n.endsWith(".jpg") || n.endsWith(".jpeg") => Some("image/jpeg")
      case n if n.endsWith(".gif")  => Some("image/gif")
      case n if n.endsWith(".webp") => Some("image/webp")
      case n if n.endsWith(".ico")  => Some("image/x-icon")
      case n if n.endsWith(".woff") => Some("font/woff")
      case n if n.endsWith(".woff2") => Some("font/woff2")
      case n if n.endsWith(".wasm") => Some("application/wasm")
      case _                        => None
    explicit.orElse {
      try Option(java.nio.file.Files.probeContentType(java.nio.file.Paths.get(name)))
      catch case _: Throwable => None
    }.getOrElse("application/octet-stream")


import java.io.File
import java.nio.file.{Files, Paths}

/** `multipart/form-data` parser shared between the interpreter and
 *  the JvmGen-emitted server runtime.  Returns POJO [[UploadedFile]]
 *  instances; callers that need interpreter `Value`s (the interpreter
 *  bridge in `WebServer.parseMultipart`) wrap each one accordingly.
 *
 *  `bodyLatin1` is the request body decoded as ISO-8859-1, where each
 *  Java char is byte-equivalent to the original input byte — boundary
 *  matching and part splitting therefore work byte-exactly on Strings.
 *
 *  Text parts (no `filename=`) become String values in `form`, UTF-8
 *  decoded from their byte representation.
 *
 *  File parts (with `filename=`) become an `UploadedFile` in `files`.
 *  Parts larger than `spoolThreshold` are written to a temp file inside
 *  `uploadDir` so the in-memory map stays bounded; their `UploadedFile`
 *  has an empty `bytes` field and a non-empty `path` pointing at the
 *  spool file.  Smaller parts keep `bytes` populated and `path` empty.
 *
 *  Returns a triple of `(form, files, spooledTempFiles)` — callers can
 *  use the third element to clean up the temp files after the request
 *  handler finishes. */
object Multipart:

  def parse(
      contentType:    String,
      bodyLatin1:     String,
      spoolThreshold: Long   = 1024L * 1024L,
      uploadDir:      String = System.getProperty("java.io.tmpdir")
  ): (Map[String, String], Map[String, UploadedFile], List[File]) =
    val boundary = "boundary=([^;]+)".r.findFirstMatchIn(contentType).map { m =>
      val raw = m.group(1).trim
      if raw.startsWith("\"") && raw.endsWith("\"") then raw.substring(1, raw.length - 1) else raw
    }
    boundary.fold((Map.empty[String, String], Map.empty[String, UploadedFile], List.empty[File])) { b =>
      val sep     = "--" + b
      val parts   = bodyLatin1.split(java.util.regex.Pattern.quote(sep), -1)
      val form    = scala.collection.mutable.Map.empty[String, String]
      val files   = scala.collection.mutable.Map.empty[String, UploadedFile]
      val spooled = scala.collection.mutable.ListBuffer.empty[File]
      // First chunk before the first boundary and the trailing "--" chunk
      // contain no part data — skip both.
      parts.drop(1).dropRight(1).foreach { raw =>
        val part   = raw.stripPrefix("\r\n").stripSuffix("\r\n")
        val sepIdx = part.indexOf("\r\n\r\n")
        if sepIdx >= 0 then
          val headerText = part.substring(0, sepIdx)
          val partBody   = part.substring(sepIdx + 4) // still ISO-8859-1
          val disp = headerText.linesIterator
            .find(_.toLowerCase.startsWith("content-disposition"))
            .getOrElse("")
          val ctype = headerText.linesIterator
            .find(_.toLowerCase.startsWith("content-type"))
            .map(_.split(":", 2).lift(1).getOrElse("").trim)
            .getOrElse("application/octet-stream")
          val name     = """name="([^"]*)"""".r.findFirstMatchIn(disp).map(_.group(1))
          val filename = """filename="([^"]*)"""".r.findFirstMatchIn(disp).map(_.group(1))
          (name, filename) match
            case (Some(n), Some(fn)) =>
              val (bytesStr, pathStr) =
                if partBody.length.toLong > spoolThreshold then
                  val tmp = Files.createTempFile(Paths.get(uploadDir), "ssc-upload-", "").toFile
                  Files.write(tmp.toPath, partBody.getBytes("ISO-8859-1"))
                  spooled += tmp
                  ("", tmp.getAbsolutePath)
                else (partBody, "")
              files(n) = UploadedFile(n, fn, ctype, partBody.length, bytesStr, pathStr)
            case (Some(n), None) =>
              form(n) = new String(partBody.getBytes("ISO-8859-1"), "UTF-8")
            case _ => ()
      }
      (form.toMap, files.toMap, spooled.toList)
    }


import java.security.{KeyStore, KeyFactory}
import java.security.cert.CertificateFactory
import javax.net.ssl.{KeyManagerFactory, SSLContext}

/** Build an `SSLContext` from PEM-encoded certificate + private key files
 *  on disk.  Handles both PKCS#8 (`-----BEGIN PRIVATE KEY-----`) and
 *  PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) RSA keys, plus EC private
 *  keys; the PKCS#1 wrap uses the shared [[DerCodec]] helper.
 *
 *  Single source of truth for TLS bootstrap on the JVM — same code path
 *  for the interpreter's `WebServer.start(..., certPath, keyPath)` and
 *  the JvmGen-emitted `serve(port, tlsCfg)`. */
object TlsContextBuilder:

  def build(certPath: String, keyPath: String): SSLContext =
    val certBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(certPath))
    val keyBytes  = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(keyPath))

    val certFactory = CertificateFactory.getInstance("X.509")
    val cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certBytes))

    val keyPemRaw = new String(keyBytes, "UTF-8")
    val keyPem = keyPemRaw
      .replaceAll("-----BEGIN [^-]+-----", "")
      .replaceAll("-----END [^-]+-----", "")
      .replaceAll("\\s", "")
    val rawDer = java.util.Base64.getDecoder.decode(keyPem)

    val isPkcs1 = keyPemRaw.contains("BEGIN RSA PRIVATE KEY") ||
                  !keyPemRaw.contains("BEGIN PRIVATE KEY")
    val pkcs8Der = if isPkcs1 then DerCodec.wrapPkcs1InPkcs8(rawDer) else rawDer

    val keySpec = java.security.spec.PKCS8EncodedKeySpec(pkcs8Der)
    val privateKey =
      try KeyFactory.getInstance("RSA").generatePrivate(keySpec)
      catch case _: Throwable =>
        KeyFactory.getInstance("EC").generatePrivate(keySpec)

    val ks = KeyStore.getInstance("JKS")
    ks.load(null, null)
    ks.setCertificateEntry("cert", cert)
    ks.setKeyEntry("key", privateKey, Array.emptyCharArray, Array(cert))

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, Array.emptyCharArray)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx

  /** One virtual thread per accepted connection — requires Java 21 LTS (Project Loom).
   *  Eliminates the one-thread-per-connection OS-thread overhead; a parked virtual
   *  thread costs ~few KB of heap vs ~1 MB for a platform thread stack. */
  def vthreadPool(): java.util.concurrent.ExecutorService =
    // Java 21 requirement: virtual threads are stable in JDK 21 LTS (Project Loom).
    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()


import com.sun.net.httpserver.HttpExchange

/** Apply CORS response headers to a `HttpExchange` given the current
 *  CORS configuration.  Same behaviour on the interpreter side
 *  (`WebServer.applyCorsHeaders`) and the codegen-emitted side
 *  (`serveRuntime._applyCors`): if `origins` is non-empty and the
 *  request's `Origin` is allowed, sets `Access-Control-Allow-Origin` +
 *  the configured methods / headers and a `Vary: Origin` cache hint. */
object CorsHelpers:

  def apply(
      ex:      HttpExchange,
      origins: List[String],
      methods: List[String],
      headers: List[String]
  ): Unit =
    if origins.nonEmpty then
      val origin  = Option(ex.getRequestHeaders.getFirst("Origin")).getOrElse("")
      val allowed =
        if origins.contains("*")         then "*"
        else if origins.contains(origin) then origin
        else                                  ""
      if allowed.nonEmpty then
        ex.getResponseHeaders.add("Access-Control-Allow-Origin", allowed)
        if methods.nonEmpty then
          ex.getResponseHeaders.add("Access-Control-Allow-Methods", methods.mkString(", "))
        if headers.nonEmpty then
          ex.getResponseHeaders.add("Access-Control-Allow-Headers", headers.mkString(", "))
        ex.getResponseHeaders.add("Vary", "Origin")


/** POJO description of an incoming HTTP request, shared by every JVM-side
 *  server stack (interpreter `WebServer.dispatchRoute` + JvmGen-emitted
 *  `serveRuntime._handle`).  Both backends parse the raw `HttpExchange`
 *  into this shape; the interpreter then converts each field into
 *  `Value.InstanceV("Request", …)` before invoking the user-defined
 *  closure, while the codegen backend hands the case class through
 *  unchanged.
 *
 *  Notes:
 *    - `query` / `params` / `headers` / `form` / `session` / `cookies`
 *      are immutable Maps with lowercase header keys (so handlers can
 *      portably do `req.headers.get("authorization")`).
 *    - `files` is a Map of POJO [[UploadedFile]] records; binary parts
 *      use the ISO-8859-1 byte-view encoding (round-trip back to bytes
 *      with `bytes.getBytes("ISO-8859-1")`).
 *    - `bearerToken` / `jwtClaims` / `basicAuth` are pre-extracted from
 *      the `Authorization` header for handler convenience.  `bearerToken`
 *      is the raw `Bearer <token>` payload; `jwtClaims` is the verified
 *      HMAC-SHA256 claims when `bearerToken.isDefined`; `basicAuth` is
 *      the decoded `(user, password)` for `Basic` schemes. */
case class Request(
    method:      String,
    path:        String,
    params:      Map[String, String],
    query:       Map[String, String],
    headers:     Map[String, String],
    body:        String,
    form:        Map[String, String]         = Map.empty,
    files:       Map[String, UploadedFile]   = Map.empty,
    session:     Map[String, String]         = Map.empty,
    bearerToken: Option[String]              = None,
    jwtClaims:   Option[Map[String, String]] = None,
    basicAuth:   Option[(String, String)]    = None,
    cookies:     Map[String, String]         = Map.empty
)

/** POJO description of an HTTP response.  Same wire-equivalent fields on
 *  both backends; the codegen output uses this case class directly, the
 *  interpreter constructs a matching `Value.InstanceV("Response", …)`
 *  from user-handler output.
 *
 *  `setSession` opts into HMAC-signed session-cookie management — the
 *  dispatch loop reads it and writes the appropriate `Set-Cookie`
 *  header. */
case class Response(
    status:     Int                         = 200,
    headers:    Map[String, String]         = Map.empty,
    body:       String                      = "",
    setSession: Option[Map[String, String]] = None
):
  /** Attach a session payload — HMAC-signed and packed into Set-Cookie. */
  def withSession(payload: Map[String, String]): Response = copy(setSession = Some(payload))

  /** Clear the session cookie (Max-Age=0 on the wire). */
  def clearSession(): Response = copy(setSession = Some(Map.empty))

  /** Attach (or overwrite) a header — used by std/middleware.ssc. */
  def withHeader(name: String, value: String): Response =
    copy(headers = headers + (name -> value))

/** Sentinel returned by `streamResponse` and `sse(req)` to opt into
 *  chunked / Server-Sent-Event response writing.  The dispatch loop
 *  pattern-matches on this type and drives the `writer` lambda with
 *  a chunk-emit callback. */
case class StreamResponse(
    status:  Int,
    headers: Map[String, String],
    writer:  (String => Unit) => Any
)


/** Extract `(user, password)` from an `Authorization: Basic …` header
 *  value.  Returns `None` for any non-Basic / malformed input — never
 *  throws.  Mirrors `Jwt.fromAuthHeader` (which handles `Bearer`). */
object BasicAuth:

  def fromHeader(authHeader: String): Option[(String, String)] =
    val t = Option(authHeader).map(_.trim).getOrElse("")
    if t.length < 6 || !t.substring(0, 6).equalsIgnoreCase("Basic ") then None
    else
      try
        val decoded = String(java.util.Base64.getDecoder.decode(t.substring(6).trim), "UTF-8")
        val colon   = decoded.indexOf(':')
        if colon < 0 then None
        else Some(decoded.substring(0, colon) -> decoded.substring(colon + 1))
      catch case _: Throwable => None


import com.sun.net.httpserver.HttpExchange

/** Serialise a [[Response]] POJO onto a JDK `HttpExchange`, applying the
 *  same headers / CORS / session-cookie / ETag-304 / gzip logic on every
 *  backend.  Each side (interpreter `WebServer.writeResponse`, codegen
 *  `serveRuntime._writeResponse`) passes its own per-server config in
 *  via the [[ResponseWriter.Config]] record so the writer itself stays
 *  pure. */
object ResponseWriter:

  /** Per-server config the writer needs to apply CORS, drive the
   *  opt-in server-side session store, and decide whether to gzip
   *  the body.  Built once per server start and reused per request. */
  case class Config(
      corsOrigins:        List[String]                  = Nil,
      corsMethods:        List[String]                  = Nil,
      corsHeaders:        List[String]                  = Nil,
      gzipEnabled:        Boolean                       = false,
      sessionStoreEnabled: Boolean                      = false,
      sessionStoreDelete:  String => Unit               = _ => (),
      sessionStorePut:     Map[String, String] => String = _ => "",
      buildSetCookie:      Map[String, String] => String =
        (p: Map[String, String]) => SessionCookie.toSetCookie(p, secureFlag = false)
  )

  def write(
      ex:               HttpExchange,
      r:                Response,
      rawCookieSession: Map[String, String],
      cfg:              Config
  ): Unit =
    r.headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    if !r.headers.contains("Content-Type") then
      ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    CorsHelpers(ex, cfg.corsOrigins, cfg.corsMethods, cfg.corsHeaders)
    r.setSession.foreach { payload =>
      val cookiePayload: Map[String, String] =
        if !cfg.sessionStoreEnabled then payload
        else if payload.isEmpty then
          // clearSession: also evict from the store so a stolen cookie
          // stops working server-side, not just client-side.
          rawCookieSession.get("_ssid").foreach(cfg.sessionStoreDelete)
          Map.empty
        else
          // Replace any prior SSID so refresh-on-rotate is implicit;
          // free the old slot so the store doesn't accumulate dead
          // entries on every withSession call.
          rawCookieSession.get("_ssid").foreach(cfg.sessionStoreDelete)
          val ssid = cfg.sessionStorePut(payload)
          Map("_ssid" -> ssid)
      ex.getResponseHeaders.add("Set-Cookie", cfg.buildSetCookie(cookiePayload))
    }
    // 304 short-circuit when client's ETag matches.
    val responseEtag = r.headers.getOrElse("ETag", r.headers.getOrElse("etag", ""))
    val ifNoneMatch  = Option(ex.getRequestHeaders.getFirst("If-None-Match")).getOrElse("")
    val etagUnquoted = ifNoneMatch.stripPrefix("\"").stripSuffix("\"")
    if responseEtag.nonEmpty && ifNoneMatch.nonEmpty &&
       (responseEtag == ifNoneMatch || responseEtag == etagUnquoted ||
        s""""$responseEtag"""" == ifNoneMatch) then
      ex.sendResponseHeaders(304, -1L)
    else
      val rawBytes     = r.body.getBytes("UTF-8")
      val acceptGzip   = Option(ex.getRequestHeaders.getFirst("Accept-Encoding")).getOrElse("").contains("gzip")
      val contentType  = Option(ex.getResponseHeaders.getFirst("Content-Type")).getOrElse("")
      val compressible = contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("javascript")
      val bytes =
        if cfg.gzipEnabled && acceptGzip && compressible && rawBytes.nonEmpty then
          val baos = new java.io.ByteArrayOutputStream()
          val gz   = new java.util.zip.GZIPOutputStream(baos)
          gz.write(rawBytes); gz.finish()
          ex.getResponseHeaders.add("Content-Encoding", "gzip")
          baos.toByteArray
        else rawBytes
      ex.sendResponseHeaders(r.status, if bytes.isEmpty then -1L else bytes.length.toLong)
      if bytes.nonEmpty then ex.getResponseBody.write(bytes)


import com.sun.net.httpserver.HttpExchange
import scala.jdk.CollectionConverters.*

/** Parse a JDK `HttpExchange` into the shared POJO [[Request]] model.
 *  Same logic on both backends (interpreter `WebServer.dispatchRoute`
 *  and codegen `serveRuntime._handle`): lowercase header keys, body
 *  size guard, body decoded both as UTF-8 (`body`) and ISO-8859-1
 *  (`bodyLatin1` — for multipart byte exactness), form / multipart
 *  parsed eagerly, signed cookie session dereferenced through an
 *  opt-in server-side store, bearer/basic auth pre-extracted, JWT
 *  claims verified via the supplied callback. */
object RequestBuilder:

  /** Thrown when the body exceeds `Config.maxBodySize`.  Callers map
   *  this to `413 Request Entity Too Large` on the wire. */
  final class BodyTooLargeError extends RuntimeException("Request Entity Too Large")

  /** Per-server config + per-call hooks needed to fully populate a
   *  Request.  Built once per server start (the callbacks close over
   *  the per-backend `SessionStore` / JWT verify implementation) and
   *  reused per request.
   *
   *  Defaults assume an unconfigured server (no body cap, no opt-in
   *  session store, no JWT verification). */
  case class Config(
      maxBodySize:        Long                                 = Long.MaxValue,
      spoolThreshold:     Long                                 = 1024L * 1024L,
      uploadDir:          String                               = System.getProperty("java.io.tmpdir"),
      sessionStoreEnabled: Boolean                             = false,
      sessionStoreGet:     String => Option[Map[String, String]] = _ => None,
      jwtVerify:           String => Option[Map[String, String]] = _ => None
  )

  /** Build the Request together with the raw (signed) cookie session
   *  map.  Callers pass the raw session to [[ResponseWriter.Config]]
   *  so SSID rotation lines up between request and response. */
  def parse(
      ex:     HttpExchange,
      method: String,
      path:   String,
      params: Map[String, String],
      cfg:    Config = Config()
  ): (Request, Map[String, String], List[java.io.File]) =
    // Lowercase header keys for portable lookup — matches Node's
    // `req.headers` and the WS handshake convention.
    val headers: Map[String, String] =
      ex.getRequestHeaders.entrySet.iterator.asScala.flatMap { e =>
        if e.getValue.isEmpty then None
        else Some(e.getKey.toLowerCase -> e.getValue.get(0))
      }.toMap

    // Body size guard — reject before buffering when Content-Length is known.
    val clHdr = try
      Option(ex.getRequestHeaders.getFirst("Content-Length")).map(_.toLong).getOrElse(0L)
    catch case _: Throwable => 0L
    if clHdr > cfg.maxBodySize then throw new BodyTooLargeError()
    val bodyBytes = ex.getRequestBody.readAllBytes()
    if bodyBytes.length.toLong > cfg.maxBodySize then throw new BodyTooLargeError()

    val body       = new String(bodyBytes, "UTF-8")
    val bodyLatin1 = new String(bodyBytes, "ISO-8859-1")
    val contentType = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Content-Type") => v
    }.getOrElse("")

    val ctLower = contentType.toLowerCase
    val (form, files, spooledTmps): (Map[String, String], Map[String, UploadedFile], List[java.io.File]) =
      if ctLower.startsWith("application/x-www-form-urlencoded") then
        (HttpHelpers.parseQuery(body), Map.empty[String, UploadedFile], List.empty[java.io.File])
      else if ctLower.startsWith("multipart/form-data") then
        Multipart.parse(contentType, bodyLatin1, cfg.spoolThreshold, cfg.uploadDir)
      else
        (Map.empty[String, String], Map.empty[String, UploadedFile], List.empty[java.io.File])

    val cookieHeader = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Cookie") => v
    }.getOrElse("")
    val rawCookieSession =
      if cookieHeader.isEmpty then Map.empty[String, String]
      else SessionCookie.fromHeader(cookieHeader).getOrElse(Map.empty)
    // Generic cookie map for handler convenience (parallels the WS-side
    // `ws.request.cookies`).  Separate from the signed `session` map.
    val cookies: Map[String, String] = HttpHelpers.parseCookieHeader(cookieHeader)
    val session: Map[String, String] =
      if cfg.sessionStoreEnabled then
        rawCookieSession.get("_ssid").flatMap(cfg.sessionStoreGet).getOrElse(Map.empty)
      else rawCookieSession

    val authHeader = headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("Authorization") => v
    }.getOrElse("")
    val bearer    = Jwt.fromAuthHeader(authHeader)
    val claims    = bearer.flatMap(cfg.jwtVerify)
    val basicAuth = BasicAuth.fromHeader(authHeader)

    val rawQuery = Option(ex.getRequestURI.getRawQuery).getOrElse("")
    val req = Request(
      method      = method,
      path        = path,
      params      = params,
      query       = HttpHelpers.parseQuery(rawQuery),
      headers     = headers,
      body        = body,
      form        = form,
      files       = files,
      session     = session,
      bearerToken = bearer,
      jwtClaims   = claims,
      basicAuth   = basicAuth,
      cookies     = cookies
    )
    (req, rawCookieSession, spooledTmps)


import com.sun.net.httpserver.HttpExchange

/** Drive the wire side of a streaming HTTP response — set headers
 *  (defaulting Content-Type), apply CORS, send chunked transfer
 *  encoding (`Content-Length` -1 / 0 = unknown length), then hand a
 *  `chunk: String => Unit` writer callback to user code via the
 *  `runWriter` lambda.  Each chunk gets flushed immediately so SSE
 *  events / progress messages reach the client without buffering.
 *
 *  Backend differences live entirely inside `runWriter`:
 *    - codegen builds a `(write: String => Unit) => Unit` from
 *      `StreamResponse.writer` directly;
 *    - interpreter wraps `Interpreter.invoke(callback, List(writeNative))`
 *      where `writeNative` is a `Value.NativeFnV` that forwards into
 *      the supplied `write` closure. */
object StreamResponseWriter:

  def write(
      ex:        HttpExchange,
      status:    Int,
      headers:   Map[String, String],
      cors:      ResponseWriter.Config,
      runWriter: (String => Unit) => Unit
  ): Unit =
    headers.foreach((k, v) => ex.getResponseHeaders.add(k, v))
    if !headers.contains("Content-Type") then
      ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    CorsHelpers(ex, cors.corsOrigins, cors.corsMethods, cors.corsHeaders)
    ex.sendResponseHeaders(status, 0)
    val out = ex.getResponseBody
    try
      runWriter { chunk =>
        out.write(chunk.getBytes("UTF-8"))
        out.flush()
      }
    finally out.close()


import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.atomic.AtomicLong

/** Per-request HTTP dispatch envelope, shared by the interpreter
 *  `WebServer.dispatchRoute` and the codegen `serveRuntime._handle`.
 *  Both backends parse the same POJO `Request` (via [[RequestBuilder]]),
 *  build the same `(req, () => Any) => Any` middleware chain, write
 *  the result through [[ResponseWriter]] / [[StreamResponseWriter]],
 *  and recover from the same `RestValidationError` / oversize-body /
 *  generic-exception paths.  Phases 2a-2d already extracted every
 *  protocol primitive the envelope leans on; this object pulls the
 *  glue between them into one place.
 *
 *  The shared loop runs entirely in POJOs.  The interpreter side
 *  adapts its `Value.Closure` handlers + middleware into uniform
 *  `Request => Any` / `(Request, () => Any) => Any` shapes before
 *  calling [[run]]; the codegen output passes its native Scala
 *  functions through unchanged. */
object HttpDispatchLoop:

  /** Backend-supplied dependencies that are stable for the lifetime
   *  of a server.  Built once per request by the caller — the field
   *  values read from per-server mutable settings (max body size,
   *  CORS origins, gzip flag, session-store callbacks, …) so the
   *  shared loop never reads global state directly. */
  final case class Config(
      reqBuilder:    RequestBuilder.Config,
      respWriter:    ResponseWriter.Config,
      fiveXxCounter: AtomicLong
  )

  /** Run one HTTP request through the shared envelope.  The caller
   *  has already (a) matched the route → handler + path params and
   *  (b) lifted any backend-specific middleware closures into the
   *  uniform `(Request, () => Any) => Any` shape.
   *
   *  Side effects this function owns:
   *    - 413 + immediate return on `RequestBuilder.BodyTooLargeError`
   *    - inner `RestValidationError` → 400 text response
   *    - outer generic-exception → `onError` callback + 5xx metric
   *    - cleanup of any multipart-spooled temp files in `finally`
   *
   *  Side effects the caller still owns (so this function stays
   *  exchange-shape agnostic): access-log line, `ex.close()`, and
   *  the per-request CORS-preflight short-circuit. */
  def run(
      ex:           HttpExchange,
      method:       String,
      path:         String,
      pathParams:   Map[String, String],
      handler:      Request => Any,
      middlewares:  Seq[(Request, () => Any) => Any],
      cfg:          Config,
      onError:      Throwable => Unit = _ => ()
  ): Unit =
    val parsed =
      try Some(RequestBuilder.parse(ex, method, path, pathParams, cfg.reqBuilder))
      catch case _: RequestBuilder.BodyTooLargeError =>
        val msg = "Request Entity Too Large".getBytes("UTF-8")
        ex.sendResponseHeaders(413, msg.length.toLong)
        ex.getResponseBody.write(msg)
        None
    parsed match
      case None => ()
      case Some((req, rawCookieSession, spooledTmps)) =>
        def baseHandler(): Any =
          try handler(req)
          catch case ve: RestValidationError =>
            Response(400,
              Map("Content-Type" -> "text/plain; charset=utf-8"),
              ve.getMessage)
        var chain: () => Any = () => baseHandler()
        middlewares.reverseIterator.foreach { mw =>
          val inner = chain
          chain = () => mw(req, inner)
        }
        try
          chain() match
            case sr: StreamResponse =>
              StreamResponseWriter.write(ex, sr.status, sr.headers,
                cfg.respWriter,
                write => { sr.writer(write); () })
            case resp: Response =>
              ResponseWriter.write(ex, resp, rawCookieSession, cfg.respWriter)
            case other =>
              ResponseWriter.write(ex,
                Response(200,
                  Map("Content-Type" -> "text/plain; charset=utf-8"),
                  String.valueOf(other)),
                rawCookieSession, cfg.respWriter)
        catch case e: Exception =>
          onError(e)
          cfg.fiveXxCounter.incrementAndGet()
        finally
          spooledTmps.foreach { f => try f.delete() catch case _: Throwable => () }


import com.sun.net.httpserver.HttpExchange

/** Static-asset server — resolves a URL path against a filesystem root,
 *  guards against `..` traversal via canonical-path checks, refuses to
 *  serve `.ssc` source files (those belong to route dispatch / the
 *  `.ssc` page renderer), and writes the file bytes with a sniffed
 *  `Content-Type`.  Shared between the interpreter's `WebServer.handle`
 *  and the codegen-emitted `serveRuntime._handle` so they pick up the
 *  same security guard. */
object StaticAssetServer:

  /** Resolve `urlPath` against `root`.  Returns `Some(file)` only when
   *  the file exists, is a regular file, lives inside `root`, and isn't
   *  a `.ssc` source.  Pure — no IO beyond `File.getCanonicalFile`. */
  def resolve(root: String, urlPath: String): Option[java.io.File] =
    val cleaned   = urlPath.stripPrefix("/")
    val effective = if cleaned.isEmpty then "index.html" else cleaned
    val rootDir   = new java.io.File(root).getCanonicalFile
    val target    = new java.io.File(rootDir, effective).getCanonicalFile
    if !target.exists() || !target.isFile() then None
    else if !target.getPath.startsWith(rootDir.getPath) then None
    else if target.getName.endsWith(".ssc") then None
    else Some(target)

  /** Write `file` to the exchange with `Content-Type` from `HttpHelpers.contentTypeFor`. */
  def serve(file: java.io.File, ex: HttpExchange): Unit =
    val bytes = java.nio.file.Files.readAllBytes(file.toPath)
    ex.getResponseHeaders.add("Content-Type", HttpHelpers.contentTypeFor(file.getName))
    ex.sendResponseHeaders(200, bytes.length.toLong)
    ex.getResponseBody.write(bytes)

  /** Convenience: resolve + serve in one call.  Returns `Some(())` on
   *  hit, `None` on miss so callers can fall through to `.ssc`
   *  rendering / 404.  Used by JvmGen which only ever serves from the
   *  CWD; the interpreter handles `.ssc` pages between resolve and
   *  serve so it calls `resolve` + `serve` separately. */
  def tryServe(ex: HttpExchange, urlPath: String, root: String = "."): Option[Unit] =
    resolve(root, urlPath).map { f => serve(f, ex); () }


/** Pure helpers for the WebSocket upgrade handshake (RFC 6455 §4).
 *  No IO — each function takes parsed inputs and returns the bytes the
 *  caller should write to the client socket.  Both backends (interpreter
 *  `WsProxy._proxyConnection` upgrade arm + JvmGen serveRuntime's
 *  `_proxyConnection`) build the same response shape; centralising it
 *  here keeps the wire format (status codes, header order, Sec-WebSocket
 *  fields) in sync. */
object WsHandshake:

  /** Negotiate a subprotocol from the client's `Sec-WebSocket-Protocol`
   *  offer list against the server's preferred list.  Empty server list
   *  means "skip negotiation entirely" (returned as `Some("")` so
   *  callers don't have to distinguish "negotiated empty" from "no
   *  negotiation"); empty result with a non-empty server list means
   *  no acceptable overlap → reject with 400. */
  def negotiateSubprotocol(
      clientOffer:    String,
      serverProtocols: List[String]
  ): Option[String] =
    if serverProtocols.isEmpty then Some("")
    else
      val offered = clientOffer.split(',').iterator
        .map(_.trim).filter(_.nonEmpty).toSet
      serverProtocols.find(offered.contains)
        .map(Some(_))
        .getOrElse(None)

  /** Build the `HTTP/1.1 101 Switching Protocols` response bytes for a
   *  successful WebSocket upgrade.  `clientKey` is the value of the
   *  client's `Sec-WebSocket-Key` header (validated to be non-empty
   *  upstream); `chosenProtocol` is the negotiated subprotocol, or
   *  empty to skip the `Sec-WebSocket-Protocol` response header. */
  def upgradeResponse(clientKey: String, chosenProtocol: String): Array[Byte] =
    val accept = WsFraming.acceptKey(clientKey)
    val protoHeader = if chosenProtocol.isEmpty then "" else s"Sec-WebSocket-Protocol: $chosenProtocol\r\n"
    val resp =
      "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n"               +
      "Connection: Upgrade\r\n"              +
      s"Sec-WebSocket-Accept: $accept\r\n"   +
      protoHeader                            + "\r\n"
    resp.getBytes("US-ASCII")

  /** Build a short `HTTP/1.1 <status> <reason>` response with `Content-Length:
   *  0` and `Connection: close` — used to refuse WS upgrades for failed
   *  Origin / auth / cap / subprotocol checks. */
  def rejectResponse(status: Int, reason: String): Array[Byte] =
    s"HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII")


/** Per-connection state machine that reassembles fragmented WebSocket
 *  messages (RFC 6455 §5.4): a first Text / Binary frame with FIN=0
 *  followed by zero or more Continuation frames with FIN=0, terminated
 *  by a Continuation with FIN=1.  Pure — no IO, no thread state.  The
 *  caller still dispatches control frames (Ping / Pong / Close)
 *  directly because those drive immediate wire writes that depend on
 *  the backend's IO model. */
object WsReassembler:
  /** Result of feeding one Text / Binary / Continuation frame.  See
   *  the case docs for the meaning. */
  enum Event:
    /** A complete message is ready — caller hands `payload` to the
     *  user's `onMessage` callback under `opcode` (Text or Binary). */
    case Deliver(opcode: WsFraming.Opcode, payload: Array[Byte])
    /** Protocol violation — caller closes the WS with `code` / `reason`
     *  (1002 for framing errors, 1009 for oversize messages). */
    case ProtocolError(code: Int, reason: String)
    /** Frame buffered; need more bytes before we can deliver. */
    case Buffered

final class WsReassembler(maxFrameBytes: Int = WsFraming.MaxFrameBytes):
  import WsReassembler.Event

  private var fragOpcode: WsFraming.Opcode | Null = null
  private val fragBuf:    java.io.ByteArrayOutputStream = new java.io.ByteArrayOutputStream()

  /** Feed one data-frame (Text / Binary / Continuation).  Throws an
   *  IllegalArgumentException for control opcodes — the caller is
   *  expected to short-circuit Ping / Pong / Close paths before
   *  reaching the reassembler. */
  def feed(frame: WsFraming.Frame): Event =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Text | Opcode.Binary =>
        if frame.fin then
          // Single-frame message — deliver immediately, no buffering.
          if fragOpcode != null then
            Event.ProtocolError(1002, "new data frame mid-fragment")
          else
            Event.Deliver(frame.opcode, frame.payload)
        else
          // First fragment of a multi-frame message.
          if fragOpcode != null then
            Event.ProtocolError(1002, "new data frame mid-fragment")
          else
            fragOpcode = frame.opcode
            fragBuf.reset()
            fragBuf.write(frame.payload)
            if fragBuf.size > maxFrameBytes then
              fragOpcode = null
              fragBuf.reset()
              Event.ProtocolError(1009, "message too big")
            else Event.Buffered

      case Opcode.Continuation =>
        if fragOpcode == null then
          Event.ProtocolError(1002, "continuation without prior data frame")
        else
          fragBuf.write(frame.payload)
          if fragBuf.size > maxFrameBytes then
            fragOpcode = null
            fragBuf.reset()
            Event.ProtocolError(1009, "message too big")
          else if frame.fin then
            val op    = fragOpcode.asInstanceOf[Opcode]
            val bytes = fragBuf.toByteArray
            fragOpcode = null
            fragBuf.reset()
            Event.Deliver(op, bytes)
          else
            Event.Buffered

      case other =>
        throw new IllegalArgumentException(
          s"WsReassembler.feed: control opcode $other not handled here — " +
          "caller must dispatch Ping / Pong / Close before reaching the reassembler"
        )


/** Per-frame WebSocket dispatch logic shared by the interpreter
 *  `WsConnection.onFrame` (NIO selector thread) and the codegen
 *  `serveRuntime._runReadLoop` (per-connection virtual thread).  Both
 *  backends still own their own read-loop thread model and IO
 *  mechanics; this object only encapsulates:
 *
 *    - the RFC 6455 opcode match (Ping / Pong / Close / data)
 *    - the [[WsReassembler]] hand-off for Text / Binary / Continuation
 *    - the Close-payload status decode (`payload[0..2]` → big-endian
 *      uint16, defaulting to 1000 when no payload)
 *
 *  Backends pass in plain function values for every wire-write /
 *  executor-dispatch / user-callback step — the shared object never
 *  touches their thread state, sockets, or interpreter handles. */
object WsFrameDispatch:

  /** Tells the caller whether to keep reading after this frame.  `Stop`
   *  is returned for peer-initiated Close and for any reassembler
   *  `ProtocolError`; the caller exits its read loop, its `finally`
   *  runs cleanup, and the connection terminates. */
  enum Outcome:
    case Continue, Stop

  /** Process one frame.
   *
   *  @param frame        the demasked frame just parsed off the wire
   *  @param reassembler  per-connection reassembly state
   *  @param onPing       caller should write a Pong with `payload`
   *  @param onPong       caller should refresh liveness + fire user
   *                      `onPong` (payload is the peer's echo bytes)
   *  @param onPeerClose  peer sent us a Close; status decoded from
   *                      payload, raw payload supplied if the caller
   *                      wants the reason text.  Caller decides
   *                      whether to echo Close back (RFC 6455 §5.5.1)
   *                      or just tear down — the shared loop returns
   *                      `Stop` regardless.
   *  @param onDeliver    fully-reassembled Text / Binary message
   *                      ready for the user `onMessage` callback
   *  @param onProtocolError reassembler reported a framing violation
   *                      or oversize message — caller should send
   *                      a Close with the given code/reason */
  def handle(
      frame:             WsFraming.Frame,
      reassembler:       WsReassembler,
      onPing:            Array[Byte] => Unit,
      onPong:            Array[Byte] => Unit,
      onPeerClose:       (Int, Array[Byte]) => Unit,
      onDeliver:         (WsFraming.Opcode, Array[Byte]) => Unit,
      onProtocolError:   (Int, String) => Unit
  ): Outcome =
    import WsFraming.Opcode
    frame.opcode match
      case Opcode.Ping =>
        onPing(frame.payload)
        Outcome.Continue
      case Opcode.Pong =>
        onPong(frame.payload)
        Outcome.Continue
      case Opcode.Close =>
        val status =
          if frame.payload.length >= 2
          then ((frame.payload(0) & 0xFF) << 8) | (frame.payload(1) & 0xFF)
          else 1000
        onPeerClose(status, frame.payload)
        Outcome.Stop
      case Opcode.Text | Opcode.Binary | Opcode.Continuation =>
        reassembler.feed(frame) match
          case WsReassembler.Event.Deliver(op, bytes) =>
            onDeliver(op, bytes)
            Outcome.Continue
          case WsReassembler.Event.ProtocolError(code, reason) =>
            onProtocolError(code, reason)
            Outcome.Stop
          case WsReassembler.Event.Buffered =>
            Outcome.Continue


/** Per-connection inbound-message rate limiter for WebSocket sessions.
 *  Fixed 1-second window, single-threaded — the WS read-loop (selector
 *  thread on interpreter, dedicated virtual thread on codegen) is the
 *  only writer, so no synchronisation needed.  `maxMessagesPerSec <= 0`
 *  disables the cap entirely. */
final class WsRateLimiter(maxMessagesPerSec: Int):

  private var windowStartMs: Long = 0L
  private var msgsInWindow:  Int  = 0

  /** Bump the per-second counter and return `true` if the message is
   *  within budget, `false` if it should be rejected.  Caller closes
   *  the WS with code 1008 on `false`. */
  def admit(nowMs: Long): Boolean =
    if maxMessagesPerSec <= 0 then true
    else
      if nowMs - windowStartMs >= 1000L then
        windowStartMs = nowMs
        msgsInWindow  = 0
      msgsInWindow += 1
      msgsInWindow <= maxMessagesPerSec

// ── v1.10 Generator — pull-based lazy streams via virtual threads ────────
// Each Generator[A] runs its body in a virtual thread.
// suspend(v) blocks the body thread until the consumer calls .next().
private val _genQueueTL = new ThreadLocal[java.util.concurrent.SynchronousQueue[Option[Any]]]()

private def _suspend(v: Any): Unit =
  val q = _genQueueTL.get()
  if q == null then throw new RuntimeException("suspend called outside a coroutine or generator body")
  q.put(Some(v))

def suspend(v: Any): Any =
  val coH = _coHandleTL.get()
  if coH != null then
    coH.fromBody.put(Yielded(v))
    coH.toBody.take()
  else
    _suspend(v)

class _Generator[+A](bodyFn: () => Unit):
  private type Q = java.util.concurrent.SynchronousQueue[Option[Any]]
  private val queue: Q = new Q()
  Thread.ofVirtual().start { () =>
    _genQueueTL.set(queue)
    try bodyFn()
    catch case _: Throwable => ()
    finally try queue.put(None) catch case _ => ()
  }

  def next(): Option[A] = queue.take().asInstanceOf[Option[A]]

  def foreach(f: A => Unit): Unit =
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      f(item.get)
      item = queue.take().asInstanceOf[Option[A]]

  def toList: List[A] =
    val buf = scala.collection.mutable.ListBuffer.empty[A]
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      buf += item.get
      item = queue.take().asInstanceOf[Option[A]]
    buf.toList

  def map[B](f: A => B): _Generator[B] = new _Generator[B]({ () =>
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      _suspend(f(item.get))
      item = queue.take().asInstanceOf[Option[A]]
  })

  def filter(pred: A => Boolean): _Generator[A] = new _Generator[A]({ () =>
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      if pred(item.get) then _suspend(item.get)
      item = queue.take().asInstanceOf[Option[A]]
  })

  def take(n: Int): _Generator[A] = new _Generator[A]({ () =>
    var remaining = n
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined && remaining > 0 do
      _suspend(item.get)
      remaining -= 1
      item = if remaining > 0 then queue.take().asInstanceOf[Option[A]] else None
  })

  def drop(n: Int): _Generator[A] = new _Generator[A]({ () =>
    var toDrop = n
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined && toDrop > 0 do
      toDrop -= 1
      item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      _suspend(item.get)
      item = queue.take().asInstanceOf[Option[A]]
  })

  def flatMap[B](f: A => _Generator[B]): _Generator[B] = new _Generator[B]({ () =>
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      val inner = f(item.get)
      var sub = inner.next()
      while sub.isDefined do
        _suspend(sub.get)
        sub = inner.next()
      item = queue.take().asInstanceOf[Option[A]]
  })

  def zip[B](other: _Generator[B]): _Generator[(A, B)] = new _Generator[(A, B)]({ () =>
    var a = queue.take().asInstanceOf[Option[A]]
    var b = other.next()
    while a.isDefined && b.isDefined do
      _suspend((a.get, b.get))
      a = queue.take().asInstanceOf[Option[A]]
      if a.isDefined then b = other.next()
  })

  def zipWithIndex: _Generator[(A, Int)] = new _Generator[(A, Int)]({ () =>
    var idx = 0
    var item = queue.take().asInstanceOf[Option[A]]
    while item.isDefined do
      _suspend((item.get, idx))
      idx += 1
      item = queue.take().asInstanceOf[Option[A]]
  })

def generator[T](body: () => Unit): _Generator[T] = new _Generator[T](body)

// ── v1.9 Coroutine primitive — virtual-thread handshake ──────────────────
// Two-way suspend/resume via a pair of SynchronousQueues.
// Protocol (lazy start): body waits on toBody.take() for the first resume,
// each suspend puts Yielded(out) and takes from toBody for the next input.
case class Yielded(value: Any)
case class Returned(value: Any)
case class Errored(message: String)

private case class _CoHandle(
  fromBody: java.util.concurrent.SynchronousQueue[Any],
  toBody:   java.util.concurrent.SynchronousQueue[Any]
)
case class _Coroutine(_id: Long)
private val _coHandleTL = new ThreadLocal[_CoHandle]()
private val _coHandles  = new java.util.concurrent.ConcurrentHashMap[Long, _CoHandle]()
private val _nextCoId   = new java.util.concurrent.atomic.AtomicLong(0L)

def coroutineCreate(body: () => Any): _Coroutine =
  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
  val handle   = _CoHandle(fromBody, toBody)
  val id       = _nextCoId.getAndIncrement()
  _coHandles.put(id, handle)
  Thread.ofVirtual().start { () =>
    _coHandleTL.set(handle)
    toBody.take()
    try
      val result = body()
      fromBody.put(Returned(result))
    catch case t: Throwable =>
      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
  }
  _Coroutine(id)

def coroutineResume(co: Any, in: Any): Any =
  co match
    case _Coroutine(id) =>
      val handle = _coHandles.get(id)
      if handle == null then throw new RuntimeException("coroutineResume: coroutine already completed")
      handle.toBody.put(in)
      val step = handle.fromBody.take()
      step match
        case _: Returned | _: Errored => _coHandles.remove(id)
        case _ => ()
      step
    case _ => throw new RuntimeException("coroutineResume: not a coroutine")


// ── std.fs — synchronous file primitives (java.nio.file) ─────────────────
// Defined under the user-facing names so nested calls like
// `println(readFile(path))` resolve directly without intrinsic
// dispatch (dispatch only fires for top-level Apply, not args).
def writeFile(path: String, contents: String): Unit =
  java.nio.file.Files.write(
    java.nio.file.Paths.get(path),
    contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  ()
def readFile(path: String): String =
  new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
             java.nio.charset.StandardCharsets.UTF_8)
def deleteFile(path: String): Unit =
  java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path))
  ()
def exists(path: String): Boolean =
  java.nio.file.Files.exists(java.nio.file.Paths.get(path))
def appendFile(path: String, contents: String): Unit =
  java.nio.file.Files.writeString(java.nio.file.Paths.get(path), contents,
    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
  ()
def readBytes(path: String): List[Long] =
  java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
    .map(_sscB => (_sscB & 0xFF).toLong).toList
def writeBytes(path: String, bytes: List[Long]): Unit =
  java.nio.file.Files.write(java.nio.file.Paths.get(path), bytes.map(_.toByte).toArray)
  ()
def isFile(path: String): Boolean =
  java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(path))
def isDir(path: String): Boolean =
  java.nio.file.Files.isDirectory(java.nio.file.Paths.get(path))
def mkdir(path: String): Unit =
  val _sscP = java.nio.file.Paths.get(path)
  if !java.nio.file.Files.exists(_sscP) then java.nio.file.Files.createDirectory(_sscP)
  ()
def mkdirs(path: String): Unit =
  java.nio.file.Files.createDirectories(java.nio.file.Paths.get(path))
  ()
def listDir(path: String): List[String] =
  val _sscArr = new java.io.File(path).list()
  if _sscArr == null then (Nil: List[String]) else _sscArr.toList
def copyFile(src: String, dst: String): Unit =
  java.nio.file.Files.copy(java.nio.file.Paths.get(src), java.nio.file.Paths.get(dst))
  ()
def moveFile(src: String, dst: String): Unit =
  java.nio.file.Files.move(java.nio.file.Paths.get(src), java.nio.file.Paths.get(dst))
  ()


// ── std.process — synchronous process spawning (java.lang.ProcessBuilder) ──
// Like the http Request/Response model, std/process's case classes are
// NOT inlined from the import on JVM codegen — the runtime provides both
// the types AND `exec`. Emitted only when a module calls `exec(...)` and
// does not define its own exec/ProcessResult/ProcessOptions (see JvmGen).
// ssc Int is 64-bit → Long. `exec` honours opts.cwd / opts.env (the
// interpreter ignores them).
case class ProcessOptions(cwd: Option[String] = None, env: Map[String, String] = Map(), timeout: Option[Long] = None)
case class ProcessResult(stdout: String, stderr: String, exitCode: Long)
def exec(cmd: String, args: List[String], opts: ProcessOptions): ProcessResult =
  val _sscPb = new java.lang.ProcessBuilder((cmd :: args)*)
  _sscPb.redirectErrorStream(false)
  opts.cwd.foreach(_d => _sscPb.directory(new java.io.File(_d)))
  if opts.env.nonEmpty then
    val _sscEnv = _sscPb.environment()
    opts.env.foreach { case (_k, _v) => _sscEnv.put(_k, _v) }
  val _sscProc = _sscPb.start()
  val _sscOut  = scala.io.Source.fromInputStream(_sscProc.getInputStream).mkString
  val _sscErr  = scala.io.Source.fromInputStream(_sscProc.getErrorStream).mkString
  val _sscCode = _sscProc.waitFor()
  ProcessResult(_sscOut, _sscErr, _sscCode.toLong)


// Tag value bindings (skipped where the user binds the same name)
val html = _Tag("html")
val head = _Tag("head")
val body = _Tag("body")
val title = _Tag("title")
val style = _Tag("style")
val script = _Tag("script")
val main = _Tag("main")
val section = _Tag("section")
val header = _Tag("header")
val footer = _Tag("footer")
val nav = _Tag("nav")
val article = _Tag("article")
val aside = _Tag("aside")
val div = _Tag("div")
val span = _Tag("span")
val p = _Tag("p")
val a = _Tag("a")
val em = _Tag("em")
val strong = _Tag("strong")
val small = _Tag("small")
val code = _Tag("code")
val pre = _Tag("pre")
val h1 = _Tag("h1")
val h2 = _Tag("h2")
val h3 = _Tag("h3")
val h4 = _Tag("h4")
val h5 = _Tag("h5")
val h6 = _Tag("h6")
val ul = _Tag("ul")
val ol = _Tag("ol")
val li = _Tag("li")
val dl = _Tag("dl")
val dt = _Tag("dt")
val dd = _Tag("dd")
val table = _Tag("table")
val thead = _Tag("thead")
val tbody = _Tag("tbody")
val tfoot = _Tag("tfoot")
val tr = _Tag("tr")
val td = _Tag("td")
val th = _Tag("th")
val form = _Tag("form")
val button = _Tag("button")
val label = _Tag("label")
val select = _Tag("select")
val option = _Tag("option")
val textarea = _Tag("textarea")
val figure = _Tag("figure")
val figcaption = _Tag("figcaption")
val blockquote = _Tag("blockquote")
val br = _Tag("br", voidTag = true)
val hr = _Tag("hr", voidTag = true)
val img = _Tag("img", voidTag = true)
val input = _Tag("input", voidTag = true)
val link = _Tag("link", voidTag = true)
val meta = _Tag("meta", voidTag = true)


// ── Algebraic effects runtime (trampolined Free Monad) ─────────────────
sealed trait _Computation
case class _Perform(eff: String, op: String, args: List[Any]) extends _Computation
case class _FlatMap(sub: Any, k: Any => Any) extends _Computation

def _bind(c: Any, f: Any => Any): Any = c match
  case _: _Computation => _FlatMap(c, f)
  case v               => f(v)

def _perform(eff: String, op: String, args: Any*): _Computation =
  _Perform(eff, op, args.toList)

def _run(c0: Any): Any =
  var current: Any = c0
  while true do
    current match
      case _Perform(eff, op, _) =>
        throw new RuntimeException(s"Unhandled effect: $eff.$op")
      case _FlatMap(sub, f) => sub match
        case _Perform(eff, op, _) =>
          throw new RuntimeException(s"Unhandled effect: $eff.$op")
        case _FlatMap(s2, g) =>
          current = _FlatMap(s2, (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
        case v =>
          current = f.asInstanceOf[Any => Any](v)
      case v => return v
  throw new RuntimeException("unreachable")

def _handle(
  bodyThunk:  () => Any,
  handledOps: Set[String],
  handlers:   Map[String, List[Any] => Any]
): Any =
  def interp(initial: Any): Any =
    var current: Any = initial
    while true do
      current match
        case _Perform(eff, op, args) =>
          val key = s"$eff.$op"
          if handledOps(key) then
            val resume: Any => Any = (v: Any) => v
            current = handlers(key)(args :+ resume)
          else return current
        case _FlatMap(sub, f) => sub match
          case _Perform(eff, op, args) =>
            val key = s"$eff.$op"
            val fn = f.asInstanceOf[Any => Any]
            if handledOps(key) then
              val resume: Any => Any = (v: Any) => interp(fn(v))
              current = handlers(key)(args :+ resume)
            else
              return _FlatMap(_Perform(eff, op, args),
                              (v: Any) => interp(fn(v)))
          case _FlatMap(s2, g) =>
            current = _FlatMap(s2,
              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
          case v =>
            current = f.asInstanceOf[Any => Any](v)
        case v => return v
    throw new RuntimeException("unreachable")
  interp(bodyThunk())

// Return-clause variant of `_handle`: applies `retMap` to the handled
// computation's final pure value (and to each resumed continuation's
// completion), enabling deep-handler accumulation `msg :: resume(())`.
// RECURSIVE (resume = hwr(continuation)): the op-case-body result is returned
// directly so `retMap` maps each continuation completion exactly once (never an
// op-case-body result). Used only when the handler has a `case Return(_)` arm.
def _handleWithReturn(
  bodyThunk:  () => Any,
  handledOps: Set[String],
  handlers:   Map[String, List[Any] => Any],
  retMap:     Any => Any
): Any =
  def hwr(comp: Any): Any =
    var current: Any = comp
    while true do
      current match
        case _Perform(eff, op, args) =>
          val key = s"$eff.$op"
          if handledOps(key) then
            val resume: Any => Any = (v: Any) => hwr(v)
            return handlers(key)(args :+ resume)
          else return current
        case _FlatMap(sub, f) => sub match
          case _Perform(eff, op, args) =>
            val key = s"$eff.$op"
            val fn  = f.asInstanceOf[Any => Any]
            if handledOps(key) then
              val resume: Any => Any = (v: Any) => hwr(fn(v))
              return handlers(key)(args :+ resume)
            else
              return _FlatMap(_Perform(eff, op, args), (v: Any) => hwr(fn(v)))
          case _FlatMap(s2, g) =>
            current = _FlatMap(s2,
              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
          case v => current = f.asInstanceOf[Any => Any](v)
        case v => return retMap(v)
    throw new RuntimeException("unreachable")
  hwr(bodyThunk())

/** Loose flatMap used inside handler case bodies — accepts callbacks that
 *  return either an iterable (multi-shot resume) or a single value
 *  (one-shot resume), matching the duck-typed JS semantics. */
def _anyFlatMap(xs: Any, f: Any => Any): Any = xs match
  case ys: scala.collection.Iterable[_] =>
    ys.asInstanceOf[Iterable[Any]].toList.flatMap { x =>
      f(x) match
        case zs: scala.collection.Iterable[_] => zs.asInstanceOf[Iterable[Any]].toList
        case v                                => List(v)
    }
  case _ => xs

// jvmgen-multishot-handle-result-any: a 0-arg collection method called on an Any-typed value
// (typically the result of `handle(...)`, which `_handle` returns as Any — e.g. a multi-shot
// `val all = handle(..); all.sum`). Dispatches dynamically on the runtime type; numeric folds go
// through `_binOp` so Int/Long/Double elements all work without a static Numeric instance.
def _anyCall0(recv: Any, m: String): Any = recv match
  case ys: scala.collection.Iterable[_] =>
    val it = ys.asInstanceOf[Iterable[Any]]
    m match
      case "sum"             => it.foldLeft(0: Any)((a, x) => _binOp("+", a, x))
      case "product"         => it.foldLeft(1: Any)((a, x) => _binOp("*", a, x))
      case "length" | "size" => it.size
      case "head"            => it.head
      case "last"            => it.last
      case "min"             => it.reduce((a, x) => if _binOp("<", a, x).asInstanceOf[Boolean] then a else x)
      case "max"             => it.reduce((a, x) => if _binOp(">", a, x).asInstanceOf[Boolean] then a else x)
      case "isEmpty"         => it.isEmpty
      case "nonEmpty"        => it.nonEmpty
      case "toList"          => it.toList
      case "reverse"         => it.toList.reverse
      case "distinct"        => it.toList.distinct
      case _                 => recv
  case _ => recv

// ── Exact numerics (v1.64): BigInt / Decimal ───────────────────────────
// `Decimal`/`RoundingMode` are ScalaScript names; alias them to the native
// Scala types so emitted `Decimal("1.5")`, `Decimal(123, 2)`, `val x: Decimal`,
// and `RoundingMode.HALF_UP` all resolve.  `BigInt` is already native.
type Decimal = scala.math.BigDecimal
val Decimal = scala.math.BigDecimal
val RoundingMode = scala.math.BigDecimal.RoundingMode
// Conversions + non-native method names, matching the interpreter surface.
extension (n: Int)
  def toBigInt: BigInt      = BigInt(n)
  def toDecimal: BigDecimal = BigDecimal(n)
extension (n: BigInt)
  def toDecimal: BigDecimal = BigDecimal(n)
  def isEven: Boolean       = !n.testBit(0)
  def isOdd: Boolean        = n.testBit(0)
  def negate: BigInt        = -n
extension (d: BigDecimal)
  def toDecimal: BigDecimal = d
  def negate: BigDecimal    = -d
  def isZero: Boolean       = d.signum == 0
  def divide(o: BigDecimal, scale: Int, mode: scala.math.BigDecimal.RoundingMode.Value): BigDecimal =
    BigDecimal(d.bigDecimal.divide(o.bigDecimal, scale, java.math.RoundingMode.valueOf(mode.toString)))
  def roundTo(mode: scala.math.BigDecimal.RoundingMode.Value): BigDecimal = d.setScale(0, mode)

def _bigIntOp(op: String, x: BigInt, y: BigInt): Any = op match
  case "+" => x + y
  case "-" => x - y
  case "*" => x * y
  case "/" => x / y
  case "%" => x % y
  case "<" => x < y
  case ">" => x > y
  case "<=" => x <= y
  case ">=" => x >= y
  case "==" => x == y
  case "!=" => x != y
  case _ => sys.error(s"Cannot $op on BigInt")

def _bigDecOp(op: String, x: BigDecimal, y: BigDecimal): Any = op match
  case "+" => x + y
  case "-" => x - y
  case "*" => x * y
  case "/" => x / y
  case "%" => x % y
  case "<" => x < y
  case ">" => x > y
  case "<=" => x <= y
  case ">=" => x >= y
  case "==" => x == y
  case "!=" => x != y
  case _ => sys.error(s"Cannot $op on Decimal")

/** Dynamic binary operator dispatch for CPS contexts where operands are
 *  typed as `Any`. Mirrors the interpreter's `infix` table. */
def _binOp(op: String, a: Any, b: Any): Any = (op, a, b) match
  case ("+",  x: Int,    y: Int)    => x + y
  case ("+",  x: Long,   y: Long)   => x + y
  case ("+",  x: Long,   y: Int)    => x + y
  case ("+",  x: Int,    y: Long)   => x + y
  case ("+",  x: Double, y: Double) => x + y
  case ("+",  x: Int,    y: Double) => x + y
  case ("+",  x: Double, y: Int)    => x + y
  case ("+",  x: String, _)         => x + b.toString
  case ("+",  _,         y: String) => a.toString + y
  case ("-",  x: Int,    y: Int)    => x - y
  case ("-",  x: Long,   y: Long)   => x - y
  case ("-",  x: Double, y: Double) => x - y
  case ("-",  x: Int,    y: Double) => x.toDouble - y
  case ("-",  x: Double, y: Int)    => x - y.toDouble
  case ("*",  x: Int,    y: Int)    => x * y
  case ("*",  x: Long,   y: Long)   => x * y
  case ("*",  x: Double, y: Double) => x * y
  case ("/",  x: Int,    y: Int)    => x / y
  case ("/",  x: Long,   y: Long)   => x / y
  case ("/",  x: Double, y: Double) => x / y
  case ("%",  x: Int,    y: Int)    => x % y
  case ("<",  x: Int,    y: Int)    => x < y
  case ("<",  x: Long,   y: Long)   => x < y
  case ("<",  x: Double, y: Double) => x < y
  case (">",  x: Int,    y: Int)    => x > y
  case (">",  x: Long,   y: Long)   => x > y
  case (">",  x: Double, y: Double) => x > y
  case ("<=", x: Int,    y: Int)    => x <= y
  case ("<=", x: Long,   y: Long)   => x <= y
  case ("<=", x: Double, y: Double) => x <= y
  case (">=", x: Int,    y: Int)    => x >= y
  case (">=", x: Long,   y: Long)   => x >= y
  case (">=", x: Double, y: Double) => x >= y
  case ("%",  x: Long,   y: Long)   => x % y
  case ("%",  x: Double, y: Double) => x % y
  // Mixed-width numeric ops — widen to the dominant primitive and retry,
  // so `_binOp` is total over Int/Long/Double combinations (a `var s: Long`
  // doing `s % 7` flows here as (Long, Int)). Same-type and String cases
  // above win first; these only catch the remaining mixed pairs.
  case (o, x: Long,   y: Int)    => _binOp(o, x, y.toLong)
  case (o, x: Int,    y: Long)   => _binOp(o, x.toLong, y)
  case (o, x: Double, y: Int)    => _binOp(o, x, y.toDouble)
  case (o, x: Int,    y: Double) => _binOp(o, x.toDouble, y)
  case (o, x: Double, y: Long)   => _binOp(o, x, y.toDouble)
  case (o, x: Long,   y: Double) => _binOp(o, x.toDouble, y)
  // Exact numerics (v1.64): BigInt / BigDecimal with Int/Long/BigInt
  // widening.  Decimal⊕Double is intentionally absent → falls through to
  // the error case (mixing exact and inexact is rejected).
  case (o, x: BigInt, y: BigInt)         => _bigIntOp(o, x, y)
  case (o, x: BigInt, y: Int)            => _bigIntOp(o, x, BigInt(y))
  case (o, x: Int,    y: BigInt)         => _bigIntOp(o, BigInt(x), y)
  case (o, x: BigInt, y: Long)           => _bigIntOp(o, x, BigInt(y))
  case (o, x: Long,   y: BigInt)         => _bigIntOp(o, BigInt(x), y)
  case (o, x: BigDecimal, y: BigDecimal) => _bigDecOp(o, x, y)
  case (o, x: BigDecimal, y: Int)        => _bigDecOp(o, x, BigDecimal(y))
  case (o, x: Int,    y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
  case (o, x: BigDecimal, y: Long)       => _bigDecOp(o, x, BigDecimal(y))
  case (o, x: Long,   y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
  case (o, x: BigDecimal, y: BigInt)     => _bigDecOp(o, x, BigDecimal(y))
  case (o, x: BigInt, y: BigDecimal)     => _bigDecOp(o, BigDecimal(x), y)
  // Collection ops — `+`/`-` on Set/Map for membership update,
  // `+` on List/Map for cons/insert (CPS dep code uses these
  // via _binOp when operands' static types are Any).
  case ("+", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] + y
  case ("-", xs: Set[_], y)         => xs.asInstanceOf[Set[Any]] - y
  case ("+", xs: Map[_, _], y: (_, _)) =>
    xs.asInstanceOf[Map[Any, Any]] + y.asInstanceOf[(Any, Any)]
  case _ => sys.error(s"Cannot $op on $a, $b")

// ── Built-in `Async` effect + v1.11 coroutine-based `runAsync` ─────────
//
// v1.11: Async.* ops check _coHandleTL.  When set (inside a runAsync
// virtual thread), they suspend with an IORequest case class instead of
// returning a _Perform node.  The runAsync scheduler drives the coroutine
// and dispatches IORequests.  runAsyncParallel still uses the old Free
// monad path (Async.* return _perform nodes when _coHandleTL is null).

// IORequest types for the runAsync coroutine scheduler
private case class _DelayIO(ms: Long)
private case class _AsyncIO(thunk: () => Any)
private case class _AwaitIO(fut: Any)
private case class _ParallelIO(thunks: List[() => Any])
private case class _RecvFromIO(ws: Any)

object Async:
  def delay(ms: Int): Any =
    val coH = _coHandleTL.get()
    if coH != null then
      coH.fromBody.put(Yielded(_DelayIO(ms.toLong)))
      coH.toBody.take()
    else _perform("Async", "delay", ms)
  def async(thunk: () => Any): Any =
    val coH = _coHandleTL.get()
    if coH != null then
      coH.fromBody.put(Yielded(_AsyncIO(thunk)))
      coH.toBody.take()
    else _perform("Async", "async", thunk)
  def await(fut: Any): Any =
    val coH = _coHandleTL.get()
    if coH != null then
      coH.fromBody.put(Yielded(_AwaitIO(fut)))
      coH.toBody.take()
    else _perform("Async", "await", fut)
  def parallel(thunks: List[() => Any]): Any =
    val coH = _coHandleTL.get()
    if coH != null then
      coH.fromBody.put(Yielded(_ParallelIO(thunks)))
      coH.toBody.take()
    else _perform("Async", "parallel", thunks)
  def recvFrom(ws: Any): Any =
    val coH = _coHandleTL.get()
    if coH != null then
      coH.fromBody.put(Yielded(_RecvFromIO(ws)))
      coH.toBody.take()
    else
      ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]()

case class Future(value: Any)

// ── CPS-aware collection helpers (sequence Free callbacks) ──────────
//
// In CPS-emitted bodies the receiver of `xs.map(fn)` is typed `Any`
// (the Free monad's value carrier), so Scala can't resolve `.map`
// statically.  `_dispatch` runs the method at runtime — for HOFs
// it routes through `_seq*` helpers that thread per-element Free
// results into a single sequenced Free, matching the interpreter's
// `Computation.sequence` semantics.  Pure callbacks short-circuit
// (no Free anywhere → return the plain array).

def _isFree(c: Any): Boolean = c.isInstanceOf[_Computation]

def _seq(comps: List[Any]): Any =
  if !comps.exists(_isFree) then comps
  else
    def loop(i: Int, acc: List[Any]): Any =
      if i == comps.length then acc
      else _bind(comps(i), (v: Any) => loop(i + 1, acc :+ v))
    loop(0, Nil)

def _seqMap(xs: List[Any], fn: Any => Any): Any =
  _seq(xs.map(fn))

def _seqFlatMap(xs: List[Any], fn: Any => Any): Any =
  val s = _seqMap(xs, fn)
  // Option-returning fns flatten via .toList (Some(v) → [v];
  // None → []) so `xs.flatMap(x => Option[v])` works at
  // runtime like the Scala stdlib does.
  def flatten(v: Any): List[Any] = v match
    case ys: List[_]   => ys.asInstanceOf[List[Any]]
    case opt: Option[_] => opt.toList.asInstanceOf[List[Any]]
    case other         => List(other)
  s match
    case c: _Computation =>
      _bind(c, (rs: Any) => rs.asInstanceOf[List[Any]].flatMap(flatten))
    case rs: List[_] => rs.asInstanceOf[List[Any]].flatMap(flatten)
    case _ => s

def _seqFilter(xs: List[Any], fn: Any => Any, neg: Boolean): Any =
  val flags = xs.map(fn)
  val pick = (bs: List[Any]) => xs.zip(bs).collect {
    case (x, b: Boolean) if (if neg then !b else b) => x
  }
  _seq(flags) match
    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
    case other           => other

def _seqForeach(xs: List[Any], fn: Any => Any): Any =
  _seq(xs.map(fn)) match
    case c: _Computation => _bind(c, (_: Any) => ())
    case _               => ()

def _seqExists(xs: List[Any], fn: Any => Any): Any =
  _seq(xs.map(fn)) match
    case c: _Computation => _bind(c, (bs: Any) =>
      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false })
    case bs: List[_]     =>
      bs.asInstanceOf[List[Any]].exists { case b: Boolean => b; case _ => false }
    case _ => false

def _seqForall(xs: List[Any], fn: Any => Any): Any =
  _seq(xs.map(fn)) match
    case c: _Computation => _bind(c, (bs: Any) =>
      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false })
    case bs: List[_]     =>
      bs.asInstanceOf[List[Any]].forall { case b: Boolean => b; case _ => false }
    case _ => true

def _seqCount(xs: List[Any], fn: Any => Any): Any =
  _seq(xs.map(fn)) match
    case c: _Computation => _bind(c, (bs: Any) =>
      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false })
    case bs: List[_]     =>
      bs.asInstanceOf[List[Any]].count { case b: Boolean => b; case _ => false }
    case _ => 0

def _seqFind(xs: List[Any], fn: Any => Any): Any =
  val flags = xs.map(fn)
  val pick  = (bs: List[Any]) =>
    val i = bs.indexWhere { case b: Boolean => b; case _ => false }
    if i < 0 then None else Some(xs(i))
  _seq(flags) match
    case c: _Computation => _bind(c, (bs: Any) => pick(bs.asInstanceOf[List[Any]]))
    case bs: List[_]     => pick(bs.asInstanceOf[List[Any]])
    case _               => None

def _seqFoldLeft(xs: List[Any], init: Any, fn: (Any, Any) => Any): Any =
  def loop(i: Int, acc: Any): Any =
    if i == xs.length then acc
    else
      val next = fn(acc, xs(i))
      next match
        case c: _Computation => _bind(c, (v: Any) => loop(i + 1, v))
        case v               => loop(i + 1, v)
  loop(0, init)

/** Runtime method dispatcher used in CPS contexts where the receiver
 *  is statically `Any`.  Covers the collection HOFs that need
 *  Free-aware sequencing plus the common direct methods used inside
 *  `runAsync`/`handle` bodies.  Methods we don't know about fall
 *  through to Java reflection so a typo at the call site surfaces
 *  as the same NoSuchMethod we'd get with a direct call. */
def _dispatch(obj: Any, method: String, args: List[Any]): Any =
  (obj, method, args) match
    // List HOFs — CPS-aware
    case (xs: List[_], "map",       List(fn))   => _seqMap     (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "flatMap",   List(fn))   => _seqFlatMap (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "filter",    List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = false)
    case (xs: List[_], "filterNot", List(fn))   => _seqFilter  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any], neg = true)
    case (xs: List[_], "foreach",   List(fn))   => _seqForeach (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "exists",    List(fn))   => _seqExists  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "forall",    List(fn))   => _seqForall  (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "find",      List(fn))   => _seqFind    (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "count",     List(fn))   => _seqCount   (xs.asInstanceOf[List[Any]], fn.asInstanceOf[Any => Any])
    case (xs: List[_], "foldLeft",  List(init)) =>
      // Curried in Scala: foldLeft(init)(fn) — return the fn-taker.
      (fn: ((Any, Any) => Any)) => _seqFoldLeft(xs.asInstanceOf[List[Any]], init, fn)
    // Direct List methods we use commonly inside CPS bodies
    case (xs: List[_], "head",     Nil)       => xs.head
    case (xs: List[_], "tail",     Nil)       => xs.tail
    case (xs: List[_], "size",     Nil)       => xs.size
    case (xs: List[_], "length",   Nil)       => xs.length
    case (xs: List[_], "isEmpty",  Nil)       => xs.isEmpty
    case (xs: List[_], "nonEmpty", Nil)       => xs.nonEmpty
    case (xs: List[_], "reverse",  Nil)       => xs.reverse
    // `.toMap` / `.toSet` carry implicit evidence — reflection
    // sees them as 1-arg methods that don't match a Nil call.
    case (xs: List[_], "toMap",    Nil)       =>
      xs.asInstanceOf[List[(Any, Any)]].toMap
    case (xs: List[_], "toSet",    Nil)       => xs.toSet
    case (xs: List[_], "zip",      List(other)) =>
      xs.zip(other.asInstanceOf[Iterable[Any]])
    case (xs: List[_], "zipWithIndex", Nil)   => xs.zipWithIndex
    // `.sortBy(fn)` carries an implicit Ordering — like toMap,
    // reflection-arity check rejects the 2-arg signature.
    case (xs: List[_], "sortBy",  List(fn))   =>
      given _ordAny: Ordering[Any] = new Ordering[Any]:
        def compare(a: Any, b: Any): Int = (a, b) match
          case (x: Int,    y: Int)    => x.compare(y)
          case (x: Long,   y: Long)   => x.compare(y)
          case (x: Double, y: Double) => x.compare(y)
          case (x: String, y: String) => x.compare(y)
          case _ => a.toString.compare(b.toString)
      xs.asInstanceOf[List[Any]].sortBy(fn.asInstanceOf[Any => Any])
    case (xs: List[_], "sorted", Nil) =>
      given _ordAny: Ordering[Any] = new Ordering[Any]:
        def compare(a: Any, b: Any): Int = (a, b) match
          case (x: Int,    y: Int)    => x.compare(y)
          case (x: Long,   y: Long)   => x.compare(y)
          case (x: Double, y: Double) => x.compare(y)
          case (x: String, y: String) => x.compare(y)
          case _ => a.toString.compare(b.toString)
      xs.asInstanceOf[List[Any]].sorted
    case (xs: List[_], "groupBy", List(fn))   =>
      xs.asInstanceOf[List[Any]].groupBy(fn.asInstanceOf[Any => Any])
    case (xs: List[_], "headOption", Nil)     => xs.headOption
    case (xs: List[_], "lastOption", Nil)     => xs.lastOption
    case (xs: List[_], "drop",   List(n: Int))  => xs.drop(n)
    case (xs: List[_], "take",   List(n: Int))  => xs.take(n)
    case (xs: List[_], "distinct", Nil)       => xs.distinct
    case (xs: List[_], "contains", List(x))   =>
      xs.asInstanceOf[List[Any]].contains(x)
    case (xs: List[_], "mkString", Nil)       => xs.mkString
    case (xs: List[_], "mkString", List(s: String)) => xs.mkString(s)
    case (xs: List[_], "sum",      Nil)       => xs.asInstanceOf[List[Any]].foldLeft(0: Any)((a, b) => _binOp("+", a, b))
    case (s: String,   "length",   Nil)       => s.length
    case (s: String,   "size",     Nil)       => s.length
    case (s: String,   "toInt",    Nil)       => s.toInt
    case (s: String,   "toLong",   Nil)       => s.toLong
    case (s: String,   "toDouble", Nil)       => s.toDouble
    case (s: String,   "take",     List(n: Int))  => s.take(n)
    case (s: String,   "drop",     List(n: Int))  => s.drop(n)
    case (s: String,   "head",     Nil)       => s.head
    case (s: String,   "tail",     Nil)       => s.tail
    case (s: String,   "isEmpty",  Nil)       => s.isEmpty
    case (s: String,   "nonEmpty", Nil)       => s.nonEmpty
    case (s: String,   "trim",     Nil)       => s.trim
    case (s: String,   "toLowerCase", Nil)    => s.toLowerCase
    case (s: String,   "toUpperCase", Nil)    => s.toUpperCase
    case (s: String,   "split",    List(sep: String)) => s.split(sep).toList
    // Option — `getOrElse` takes a by-name param which Java
    // reflection can't resolve directly from a String arg.
    case (opt: Option[_], "get",        Nil)       => opt.get
    case (opt: Option[_], "getOrElse",  List(d))   => opt.getOrElse(d)
    case (opt: Option[_], "isDefined",  Nil)       => opt.isDefined
    case (opt: Option[_], "isEmpty",    Nil)       => opt.isEmpty
    case (opt: Option[_], "nonEmpty",   Nil)       => opt.nonEmpty
    case (opt: Option[_], "map",        List(fn))  =>
      opt.asInstanceOf[Option[Any]].map(fn.asInstanceOf[Any => Any])
    case (opt: Option[_], "flatMap",    List(fn))  =>
      opt.asInstanceOf[Option[Any]].flatMap(x => fn.asInstanceOf[Any => Option[Any]](x))
    case (opt: Option[_], "foreach",    List(fn))  =>
      opt.asInstanceOf[Option[Any]].foreach(fn.asInstanceOf[Any => Any]); ()
    // Map ops — by-name default arg in `getOrElse` confuses
    // the reflection fallback, so dispatch explicitly.
    case (m: Map[_, _], "getOrElse", List(k, d)) =>
      m.asInstanceOf[Map[Any, Any]].getOrElse(k, d)
    case (m: Map[_, _], "get",       List(k))    =>
      m.asInstanceOf[Map[Any, Any]].get(k)
    case (m: Map[_, _], "contains",  List(k))    =>
      m.asInstanceOf[Map[Any, Any]].contains(k)
    case (m: Map[_, _], "size",      Nil)        => m.size
    case (m: Map[_, _], "isEmpty",   Nil)        => m.isEmpty
    case (m: Map[_, _], "nonEmpty",  Nil)        => m.nonEmpty
    case (m: Map[_, _], "keys",      Nil)        =>
      m.asInstanceOf[Map[Any, Any]].keys
    case (m: Map[_, _], "values",    Nil)        =>
      m.asInstanceOf[Map[Any, Any]].values
    // Map key access for runtime record types (e.g. `info.mailboxSize` on
    // a ProcessInfo map).  Must come after the explicit method cases above.
    case (m: Map[_, _], key, Nil)               =>
      m.asInstanceOf[Map[Any, Any]].getOrElse(key, null)
    // Set ops
    case (s: Set[_], "contains",  List(x)) => s.asInstanceOf[Set[Any]].contains(x)
    case (s: Set[_], "size",      Nil)     => s.size
    case (s: Set[_], "isEmpty",   Nil)     => s.isEmpty
    case (s: Set[_], "nonEmpty",  Nil)     => s.nonEmpty
    // Numeric widening / narrowing conversions on a boxed primitive value
    // (e.g. `Bump.tick().toLong` where the perform result flows through
    // `_dispatch` as an Any). Java reflection can't resolve `toLong` on a
    // boxed Integer, so dispatch them explicitly.
    case (n: Int,    "toLong",   Nil) => n.toLong
    case (n: Int,    "toDouble", Nil) => n.toDouble
    case (n: Int,    "toFloat",  Nil) => n.toFloat
    case (n: Int,    "toInt",    Nil) => n
    case (n: Long,   "toInt",    Nil) => n.toInt
    case (n: Long,   "toDouble", Nil) => n.toDouble
    case (n: Long,   "toFloat",  Nil) => n.toFloat
    case (n: Long,   "toLong",   Nil) => n
    case (n: Double, "toInt",    Nil) => n.toInt
    case (n: Double, "toLong",   Nil) => n.toLong
    case (n: Double, "toFloat",  Nil) => n.toFloat
    case (n: Double, "toDouble", Nil) => n
    case (n: Float,  "toInt",    Nil) => n.toInt
    case (n: Float,  "toLong",   Nil) => n.toLong
    case (n: Float,  "toDouble", Nil) => n.toDouble
    // Fallback: try Java reflection so non-HOF method calls still work
    case _ =>
      val cls = obj.getClass
      val ms  = cls.getMethods.filter(m =>
        m.getName == method && m.getParameterCount == args.length)
      if ms.isEmpty then
        sys.error(s"No method '$method' on ${cls.getName} with ${args.length} arg(s)")
      val boxed: Array[Object] = args.map(_.asInstanceOf[AnyRef]).toArray
      ms.head.invoke(obj, boxed*)

// v1.11 coroutine-based runAsync scheduler
def _driveAsyncCo(
  fromBody: java.util.concurrent.SynchronousQueue[Any],
  toBody:   java.util.concurrent.SynchronousQueue[Any]
): Any =
  while true do
    fromBody.take() match
      case Returned(v)           => return v
      case Errored(msg)          => throw new RuntimeException(s"Async error: $msg")
      case Yielded(_DelayIO(ms)) =>
        if ms > 0 then Thread.sleep(ms)
        toBody.put(())
      case Yielded(_AsyncIO(thunk)) =>
        toBody.put(Future(_runAsync(thunk)))
      case Yielded(_AwaitIO(fut)) =>
        toBody.put(fut match
          case Future(v) => v
          case other     => sys.error(s"Async.await: expected Future, got $other"))
      case Yielded(_ParallelIO(thunks)) =>
        toBody.put(thunks.map(_runAsync))
      case Yielded(_RecvFromIO(ws)) =>
        toBody.put(ws.asInstanceOf[Map[String, Any]]("recv").asInstanceOf[() => Any]())
      case other =>
        sys.error(s"_driveAsyncCo: unexpected step: $other")
  sys.error("unreachable")

def _runAsync(bodyThunk: () => Any): Any =
  val fromBody = new java.util.concurrent.SynchronousQueue[Any]()
  val toBody   = new java.util.concurrent.SynchronousQueue[Any]()
  val handle   = _CoHandle(fromBody, toBody)
  Thread.ofVirtual().start { () =>
    _coHandleTL.set(handle)
    toBody.take()
    try
      val result = bodyThunk()
      fromBody.put(Returned(result))
    catch case t: Throwable =>
      val msg = Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
      try fromBody.put(Errored(msg)) catch case _: Throwable => ()
  }
  toBody.put(())
  _driveAsyncCo(fromBody, toBody)

// ── runAsyncParallel: real-thread alternate handler ────────────────────
//
// Same `Async.*` API as `runAsync` but `async` / `parallel` submit
// their thunks to an `ExecutorService`.  `await` blocks the calling
// thread on the future; `parallel` waits on each future in declared
// order so the result list mirrors input order regardless of
// completion order — value-deterministic code retains byte-identical
// output across the single- and parallel-handler variants.

val _parallelFutures =
  new java.util.concurrent.ConcurrentHashMap[Long, java.util.concurrent.Future[Any]]()
val _parallelFutureSeq = new java.util.concurrent.atomic.AtomicLong(0L)
def _freshFutureId(): Long = _parallelFutureSeq.incrementAndGet()

def _runAsyncParallel(bodyThunk: () => Any): Any =
  // Java 21 requirement: virtual threads (Project Loom) for lightweight parallelism.
  val _ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
  try
    def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
      case "delay" =>
        val ms = args(0).asInstanceOf[Int]
        if ms > 0 then Thread.sleep(ms.toLong)
        resume(())
      case "async" =>
        val thunk = args(0).asInstanceOf[() => Any]
        val fut: java.util.concurrent.Future[Any] = _ex.submit(
          new java.util.concurrent.Callable[Any] {
            def call(): Any = interp(thunk())
          })
        val fid = _freshFutureId()
        _parallelFutures.put(fid, fut)
        resume(Future(("_parId", fid)))
      case "await" =>
        args(0) match
          case Future(("_parId", fid: Long)) =>
            val fut = _parallelFutures.remove(fid)
            if fut == null then sys.error("Async.await: stale Future")
            resume(fut.get())
          case Future(v) => resume(v)
          case _         => sys.error("Async.await(future)")
      case "parallel" =>
        val thunks = args(0).asInstanceOf[List[() => Any]]
        val futs = thunks.map { t =>
          _ex.submit(new java.util.concurrent.Callable[Any] {
            def call(): Any = interp(t())
          })
        }
        resume(futs.map(_.get()))
      case _ => sys.error("Unknown Async operation: " + op)
    def interp(initial: Any): Any =
      var current: Any = initial
      while true do
        current match
          case _Perform("Async", op, args) =>
            current = dispatch(op, args, (v: Any) => v)
          case _Perform(_, _, _) => return current
          case _FlatMap(sub, f) => sub match
            case _Perform("Async", op, args) =>
              val fn = f.asInstanceOf[Any => Any]
              current = dispatch(op, args, (v: Any) => interp(fn(v)))
            case _Perform(_, _, _) =>
              val fn = f.asInstanceOf[Any => Any]
              return _FlatMap(sub, (v: Any) => interp(fn(v)))
            case _FlatMap(s2, g) =>
              current = _FlatMap(s2,
                (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
            case v =>
              current = f.asInstanceOf[Any => Any](v)
          case v => return v
      throw new RuntimeException("unreachable")
    interp(bodyThunk())
  finally _ex.shutdown()

// ── Storage: built-in key-value effect ─────────────────────────────────
//
// `Storage.{get,put,remove,has,keys}` produce `_Perform("Storage",
// op, args)` nodes; `_runStorage(bodyThunk, path)` is the handler.
// When `path` is non-null it hydrates from / flushes to that JSON
// file on every mutation (file-backed); otherwise the map stays
// in-process and is discarded at scope exit (ephemeral mode).

def _storageLoad(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
  val p = java.nio.file.Paths.get(path)
  if java.nio.file.Files.exists(p) then
    val src = java.nio.file.Files.readString(p).trim
    if src.startsWith("{") && src.endsWith("}") then
      var i = 1
      val end = src.length - 1
      def skipWs(): Unit = while i < end && src.charAt(i).isWhitespace do i += 1
      def readStr(): String =
        if i >= end || src.charAt(i) != '"' then sys.error(s"Storage JSON: expected string at $i")
        i += 1
        val sb = new StringBuilder
        while i < end && src.charAt(i) != '"' do
          if src.charAt(i) == '\\' && i + 1 < end then
            src.charAt(i + 1) match
              case '"'  => sb.append('"');  i += 2
              case '\\' => sb.append('\\'); i += 2
              case 'n'  => sb.append('\n'); i += 2
              case 't'  => sb.append('\t'); i += 2
              case 'r'  => sb.append('\r'); i += 2
              case c    => sb.append(c);    i += 2
          else { sb.append(src.charAt(i)); i += 1 }
        i += 1
        sb.toString
      skipWs()
      while i < end do
        val k = readStr(); skipWs()
        if i >= end || src.charAt(i) != ':' then sys.error("Storage JSON: expected ':'")
        i += 1; skipWs()
        val v = readStr(); skipWs()
        state(k) = v
        if i < end && src.charAt(i) == ',' then i += 1
        skipWs()

def _storageSave(path: String, state: scala.collection.mutable.LinkedHashMap[String, String]): Unit =
  def esc(s: String): String =
    val sb = new StringBuilder
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.append('"').toString
  val body = state.iterator.map { case (k, v) => esc(k) + ":" + esc(v) }.mkString(",")
  java.nio.file.Files.writeString(java.nio.file.Paths.get(path), "{" + body + "}")

def _runStorage(bodyThunk: () => Any, path: String): Any =
  val state = scala.collection.mutable.LinkedHashMap.empty[String, String]
  if path != null then _storageLoad(path, state)
  def flush(): Unit = if path != null then _storageSave(path, state)
  def dispatch(op: String, args: List[Any], resume: Any => Any): Any = op match
    case "get" =>
      val k = args(0).asInstanceOf[String]
      resume(if state.contains(k) then Some(state(k)) else None)
    case "put" =>
      val k = args(0).asInstanceOf[String]
      state(k) = _show(args(1))
      flush()
      resume(())
    case "remove" =>
      state.remove(args(0).asInstanceOf[String])
      flush()
      resume(())
    case "has" => resume(state.contains(args(0).asInstanceOf[String]))
    case "keys" => resume(state.keys.toList)
    case _ => sys.error("Unknown Storage operation: " + op)
  def interp(initial: Any): Any =
    var current: Any = initial
    while true do
      current match
        case _Perform("Storage", op, args) =>
          current = dispatch(op, args, (v: Any) => v)
        case _Perform(_, _, _) => return current
        case _FlatMap(sub, f) => sub match
          case _Perform("Storage", op, args) =>
            val fn = f.asInstanceOf[Any => Any]
            current = dispatch(op, args, (v: Any) => interp(fn(v)))
          case _Perform(_, _, _) =>
            val fn = f.asInstanceOf[Any => Any]
            return _FlatMap(sub, (v: Any) => interp(fn(v)))
          case _FlatMap(s2, g) =>
            current = _FlatMap(s2,
              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
          case v =>
            current = f.asInstanceOf[Any => Any](v)
        case v => return v
    throw new RuntimeException("unreachable")
  interp(bodyThunk())

// ── v1.6 Actors — Phase 1 cooperative scheduler ────────────────────────
//
// Same Computation / Free-Monad walk as `_runAsync` but the outer
// loop interleaves multiple actors.  Mailboxes are `LinkedBlockingQueue`s
// (v1.9.x: same infrastructure as coroutines, thread-safe);
// blocked-on-receive state lives on each actor along with the
// captured continuation.  Quiescence with timeout-armed receives
// sleeps until the earliest deadline and resumes that actor with
// `None`.  Single-threaded for parity with the interpreter and
// JsGen — a Loom variant can swap the scheduler later without
// changing the API surface.

// Phase 3: nodeId="" means local (backward-compatible default)
case class _Pid(nodeId: String, localId: Long)
// v1.6 Phase 2 — supervision message types visible to ScalaScript code
case class Exit(from: Any, reason: Any)
case class Down(ref: Any, from: Any, reason: Any)
case object noproc
// v1.23 — cluster visibility events
case class NodeJoined(nodeId: String)
case class NodeLeft(nodeId: String, reason: String)
// v1.23 — leader election (Bully) events
case class LeaderElected(nodeId: String)
case class LeaderLost(nodeId: String)
// v1.23 — config-distribution events
case class ConfigChanged(key: String, value: String)
// v1.23 — drain / rolling-restart events
case class DrainStateChanged(nodeId: String, draining: Boolean)
// v1.23 — cluster metrics aggregation events
case class MetricChanged(name: String, nodeId: String, value: Double)

/** Adapter: a partial-function literal becomes a total
 *  `Any => Option[Any]`.  Used by emitReceiveMatcher so the
 *  generated source doesn't fight Scala 3's `(x) => x match`
 *  postfix-match precedence trap. */
def _pfToFun(pf: PartialFunction[Any, Option[Any]]): Any => Option[Any] =
  (msg: Any) => pf.applyOrElse(msg, (_: Any) => None)

val _receiveSpecs =
  new java.util.concurrent.ConcurrentHashMap[Long, Any => Option[Any]]()
val _receiveSpecSeq = new java.util.concurrent.atomic.AtomicLong(0L)
def _registerReceive(matcher: Any => Option[Any]): Long =
  val id = _receiveSpecSeq.incrementAndGet()
  _receiveSpecs.put(id, matcher)
  id

object Actor:
  def spawn(thunk: () => Any): Any              = _perform("Actor", "spawn",       thunk)
  def spawn_link(thunk: () => Any): Any         = _perform("Actor", "spawnLink",   thunk)
  def self(): Any                               = _perform("Actor", "self")
  def send(pid: Any, msg: Any): Any             = _perform("Actor", "send",        pid, msg)
  def exit(pid: Any, reason: Any): Any          = _perform("Actor", "exit",        pid, reason)
  def receive_(specId: Long): Any               = _perform("Actor", "receive",     specId)
  def receive_t(specId: Long, ms: Any): Any     = _perform("Actor", "receive_t",   specId, ms)
  // v1.6 Phase 2 — supervision
  def link(pid: Any): Any                       = _perform("Actor", "link",        pid)
  def monitor(pid: Any): Any                    = _perform("Actor", "monitor",     pid)
  def demonitor(ref: Any): Any                  = _perform("Actor", "demonitor",   ref)
  def trapExit(b: Any): Any                     = _perform("Actor", "trapExit",    b)
  // v1.6 Phase 3 — distributed
  def startNode(nodeId: Any, url: Any = ""): Any = _perform("Actor", "startNode",   nodeId, url)
  def connectNode(url: Any, tok: Any = ""): Any  = _perform("Actor", "connectNode", url, tok)
  def joinCluster(seeds: Any, tok: Any = ""): Any = _perform("Actor", "joinCluster", seeds, tok)
  def register(name: Any, pid: Any): Any             = _perform("Actor", "register",       name, pid)
  def whereis(name: Any): Any                        = _perform("Actor", "whereis",        name)
  // v1.6.x — cluster-wide registry
  def globalRegister(name: Any, pid: Any): Any       = _perform("Actor", "globalRegister", name, pid)
  def globalWhereis(name: Any): Any                  = _perform("Actor", "globalWhereis",  name)
  // v1.6.x — scheduled sends
  def sendAfter(delayMs: Any, pid: Any, msg: Any): Any   = _perform("Actor", "sendAfter",   delayMs, pid, msg)
  def sendInterval(periodMs: Any, pid: Any, msg: Any): Any = _perform("Actor", "sendInterval", periodMs, pid, msg)
  def cancelTimer(ref: Any): Any                          = _perform("Actor", "cancelTimer", ref)
  // v1.6.x — bounded mailbox spawn
  def spawnBounded(cap: Any, overflow: Any, thunk: () => Any): Any = _perform("Actor", "spawnBounded", cap, overflow, thunk)
  // v1.6.x — process introspection
  def processInfo(pid: Any): Any = _perform("Actor", "processInfo", pid)
  // v1.23 — cluster visibility
  def clusterMembers(): Any         = _perform("Actor", "clusterMembers")
  def subscribeClusterEvents(): Any = _perform("Actor", "subscribeClusterEvents")
  // v1.23 — phi-accrual failure detector
  def phiOf(nid: Any): Any           = _perform("Actor", "phiOf", nid)
  def isSuspect(nid: Any, thr: Any = 8.0): Any = _perform("Actor", "isSuspect", nid, thr)
  // v1.23 — local node identity + phi vector
  def selfNode(): Any      = _perform("Actor", "selfNode")
  def clusterHealth(): Any = _perform("Actor", "clusterHealth")
  // v1.23 — cluster-wide failure detector
  def broadcastHealth(): Any                            = _perform("Actor", "broadcastHealth")
  def clusterIsDown(nid: Any, thr: Any = 8.0): Any      = _perform("Actor", "clusterIsDown", nid, thr)
  // v1.23 — leader election (Bully)
  def electLeader(): Any                                = _perform("Actor", "electLeader")
  def currentLeader(): Any                              = _perform("Actor", "currentLeader")
  def subscribeLeaderEvents(): Any                      = _perform("Actor", "subscribeLeaderEvents")
  def setAutoReelect(enabled: Any): Any                 = _perform("Actor", "setAutoReelect", enabled)
  // v1.23 — leader-protocol switch (Raft / external coordinator stubs).
  // See specs/cluster-raft.md for the spec.  Calling these promotes the
  // node off Bully but the alternative protocols' actual algorithms
  // land in subsequent phases — for now these mark intent and let
  // `leaderProtocol()` observe it.
  def useRaftLeaderElection(): Any                      = _perform("Actor", "useRaftLeaderElection")
  def useExternalCoordinator(acquireLease: Any, renewLease: Any,
                              releaseLease: Any, currentHolder: Any): Any =
    _perform("Actor", "useExternalCoordinator", acquireLease, renewLease, releaseLease, currentHolder)
  def leaderProtocol(): Any                             = _perform("Actor", "leaderProtocol")
  // v1.23 — bounded ring buffer of accepted leader claims this node has
  // observed.  Each entry is (term, leaderId, wallClockMs).
  def leaderHistory(): Any                              = _perform("Actor", "leaderHistory")
  // v1.23 — auto-reconnect policy (exponential backoff per peer)
  def setReconnectPolicy(initialMs: Any, maxMs: Any): Any = _perform("Actor", "setReconnectPolicy", initialMs, maxMs)
  def setReconnectPolicy(initialMs: Any, maxMs: Any, giveUpAfterMs: Any): Any =
    _perform("Actor", "setReconnectPolicy", initialMs, maxMs, giveUpAfterMs)
  // v1.23 — per-link heartbeat cadence + dead-after threshold
  def setHeartbeatTimeout(intervalMs: Any, deadAfterMs: Any): Any =
    _perform("Actor", "setHeartbeatTimeout", intervalMs, deadAfterMs)
  // v1.23 — quorum-aware Bully threshold (split-brain guard)
  def setQuorumSize(n: Any): Any = _perform("Actor", "setQuorumSize", n)
  // v1.23 — cluster endpoint shared-secret
  def setClusterAuthToken(token: Any): Any = _perform("Actor", "setClusterAuthToken", token)
  // v1.23 — periodic gossip re-discovery (ask peers for their peer list)
  def requestGossip(): Any = _perform("Actor", "requestGossip")
  // v1.23 — cluster configuration distribution
  def clusterConfigSet(key: Any, value: Any): Any  = _perform("Actor", "clusterConfigSet", key, value)
  def clusterConfigGet(key: Any): Any              = _perform("Actor", "clusterConfigGet", key)
  def clusterConfigKeys(): Any                     = _perform("Actor", "clusterConfigKeys")
  def subscribeConfigEvents(): Any                 = _perform("Actor", "subscribeConfigEvents")
  // v1.23 — drain / rolling-restart
  def setDraining(b: Any): Any                     = _perform("Actor", "setDraining", b)
  def isDraining(): Any                            = _perform("Actor", "isDraining")
  def drainingPeers(): Any                         = _perform("Actor", "drainingPeers")
  def subscribeDrainEvents(): Any                  = _perform("Actor", "subscribeDrainEvents")
  // v1.23 — cluster metrics aggregation
  def clusterMetricSet(name: Any, value: Any): Any = _perform("Actor", "clusterMetricSet", name, value)
  def clusterMetricGet(name: Any): Any             = _perform("Actor", "clusterMetricGet", name)
  def clusterMetricSum(name: Any): Any             = _perform("Actor", "clusterMetricSum", name)
  def clusterMetricNames(): Any                    = _perform("Actor", "clusterMetricNames")
  def subscribeMetricEvents(): Any                 = _perform("Actor", "subscribeMetricEvents")

// v1.6.x — bounded mailbox overflow strategies.  Plain string values so
// `spawnBounded(cap, Overflow.DropOldest, thunk)` compiles and passes the
// right string to the actor scheduler.
object Overflow:
  val DropOldest: Any = "DropOldest"
  val DropNewest: Any = "DropNewest"
  val Block: Any = "Block"
  val Fail: Any = "Fail"

class _ActorState:
  val mailbox = new java.util.concurrent.LinkedBlockingQueue[Any]()
  var pending: Any = null
  // (matcher, k, deadline?, wrapSome)
  var blocked: (Any => Option[Any], Any => Any, Option[Long], Boolean) = null
  // v1.6.x bounded mailbox
  var cap:      Int    = 0   // 0 = unbounded
  var overflow: String = ""
  val blockedSends = scala.collection.mutable.ArrayDeque.empty[(Long, Any, Any => Any)]

def _runActors(bodyThunk: () => Any): Any =
  val actors    = scala.collection.mutable.LongMap.empty[_ActorState]
  // Phase 2 supervision state
  val links     = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Set[Long]]
  val monitors  = scala.collection.mutable.LongMap.empty[scala.collection.mutable.Map[Long, Long]]
  val trapExitM = scala.collection.mutable.LongMap.empty[Boolean]
  var nextMonRef: Long = 0L
  // v1.6.x scheduled sends — timerId → (fireAt, periodMs, targetId, msg)
  val _timers      = scala.collection.mutable.LongMap.empty[(Long, Option[Long], Long, Any)]
  var _nextTimerId: Long = 0L
  // Phase 3 distributed state
  var _localNodeId:  String  = ""
  var _localNodeUrl: String  = ""
  @volatile var _joinMode:  Boolean = false
  @volatile var _joinToken: String  = ""
  val _peerUrls       = new java.util.concurrent.ConcurrentHashMap[String, String]()
  val _nodeRegistry    = new java.util.concurrent.ConcurrentHashMap[String, Long]()
  val _globalRegistry  = new java.util.concurrent.ConcurrentHashMap[String, _Pid]()
  val _peerChannels    = new java.util.concurrent.ConcurrentHashMap[String, String => Unit]()
  val _remoteInbox    = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Any)]()
  case class _RemoteHandlerInfo(function: String, path: Option[String], requestType: Option[String], responseType: Option[String], transports: Set[String])
  val _remoteHandlers = new java.util.concurrent.ConcurrentHashMap[String, _RemoteHandlerInfo]()
  val _peerLastPong   = new java.util.concurrent.ConcurrentHashMap[String, Long]()
  val _nodeDownQueue  = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  // cross-node monitors: nodeId → [(localActorId, monRef, remotePid.localId)]
  val _remoteMonitors = new java.util.concurrent.ConcurrentHashMap[String,
    java.util.concurrent.CopyOnWriteArrayList[(Long, Long, Long)]]()
  // cross-node links:   nodeId → [(localActorId, remotePid.localId)]
  val _remoteLinks    = new java.util.concurrent.ConcurrentHashMap[String,
    java.util.concurrent.CopyOnWriteArrayList[(Long, Long)]]()
  // v1.23 — cluster visibility
  val _clusterEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  val _clusterEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, String)]()
  // JSON string-escape helper — hoisted above all subsequent vals so
  // nested defs can forward-reference it without Scala 3 flagging the
  // ref as "extending over" a val initialiser.
  def _jstr(s: String): String =
    val sb = new StringBuilder(s.length + 2).append('"')
    s.foreach { case '"' => sb.append("\\\""); case '\\' => sb.append("\\\\")
                case '\n' => sb.append("\\n"); case c => sb.append(c) }
    sb.append('"').toString
  // v1.23 — bounded ring buffer of cluster events as JSON lines.  Feeds
  // `GET /_ssc-cluster/events`.  Independent of in-process subscribers
  // — events land here whether or not any actor has called
  // `subscribe*Events`, so ops tooling always has data.  Cap 200.
  val _CLUSTER_EVENT_LOG_MAX = 200
  val _clusterEventLog       = new java.util.concurrent.ConcurrentLinkedDeque[String]()
  def _recordEventLog(json: String): Unit =
    _clusterEventLog.offer(json)
    while _clusterEventLog.size() > _CLUSTER_EVENT_LOG_MAX do _clusterEventLog.pollFirst()
  // v1.23 — shared-secret Bearer token for /_ssc-cluster/* endpoints.
  // Reads `SSC_CLUSTER_TOKEN` env at startup.  Empty ⇒ endpoints open.
  @volatile var _clusterAuthToken: String =
    Option(System.getenv("SSC_CLUSTER_TOKEN")).getOrElse("")
  def _fireClusterEvent(tag: String, nodeId: String, reason: String = ""): Unit =
    val ts = System.currentTimeMillis()
    val logEntry =
      if tag == "NodeJoined" then
        "{\"ts\":" + ts.toString + ",\"type\":\"NodeJoined\",\"nodeId\":" + _jstr(nodeId) + "}"
      else
        "{\"ts\":" + ts.toString + ",\"type\":\"NodeLeft\",\"nodeId\":" + _jstr(nodeId) +
        ",\"reason\":" + _jstr(reason) + "}"
    _recordEventLog(logEntry)
    if !_clusterEventSubs.isEmpty then _clusterEventQueue.offer((tag, nodeId, reason))
  // v1.23 — phi-accrual failure detector: sliding window of inter-pong intervals.
  val _PHI_HIST_MAX  = 100
  val _peerPongHist  = new java.util.concurrent.ConcurrentHashMap[String,
    java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]]()
  // v1.23 — cluster-wide FD: peerNodeId -> view of (targetNodeId -> phi).
  val _peerPhiViews  = new java.util.concurrent.ConcurrentHashMap[String,
    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
  // v1.23 — leader election (Bully) state.  Single node-wide view.
  val _currentLeader        = new java.util.concurrent.atomic.AtomicReference[String]("")
  @volatile var _electionInProgress: Boolean = false
  @volatile var _electionStartedAt:  Long    = 0L
  @volatile var _gotAliveResponse:   Boolean = false
  val _ELECTION_TIMEOUT_MS  = 2000L
  val _leaderEventSubs      = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  val _leaderEventQueue     = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
  def _fireLeaderEvent(tag: String, leaderId: String): Unit =
    val ts = System.currentTimeMillis()
    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":" + _jstr(tag) +
                    ",\"nodeId\":" + _jstr(leaderId) + "}")
    if !_leaderEventSubs.isEmpty then _leaderEventQueue.offer((tag, leaderId))
  @volatile var _autoReelect: Boolean = false
  // v1.23 — protocol dispatch (cluster-raft.md §6).  "bully" today;
  //   Phase 3a flips to "raft", Phase 3b flips to "coord".
  val _leaderProtocol      = new java.util.concurrent.atomic.AtomicReference[String]("bully")
  @volatile var _leaderCoordinator: Any = null
  // v1.23 — bounded leader-claim history (cluster-raft.md §6).
  val _LEADER_HIST_MAX     = 100
  val _leaderHistTermSeq   = new java.util.concurrent.atomic.AtomicLong(0L)
  val _leaderHist          = new java.util.concurrent.ConcurrentLinkedDeque[(Long, String, Long)]()
  def _recordLeaderHist(leaderId: String): Unit =
    // Caller still gates on prev != new, so every call is a real change.
    val term = _leaderHistTermSeq.incrementAndGet()
    _leaderHist.offer((term, leaderId, System.currentTimeMillis()))
    while _leaderHist.size() > _LEADER_HIST_MAX do _leaderHist.pollFirst()
  // v1.23 — external-coordinator lease state (cluster-raft.md §5).
  // Pulled out via `productElement` so the runtime can call them
  // without structural types or reflection.
  @volatile var _coordAcquireFn: AnyRef = null  // (String, Long) => Boolean
  @volatile var _coordRenewFn:   AnyRef = null  // String => Boolean
  @volatile var _coordReleaseFn: AnyRef = null  // String => Unit
  @volatile var _coordHolderFn:  AnyRef = null  // () => Option[String]
  @volatile var _coordIsLeader:  Boolean = false
  val _coordTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
  val _COORD_LEASE_TIMEOUT_MS  = 5000L
  val _COORD_RENEW_INTERVAL_MS = 1000L
  def _ensureCoordTickThread(): Unit =
    if _coordTickThread.get() != null then return
    val t = Thread.ofVirtual().start { () =>
      try
        var done = false
        while !done && _leaderProtocol.get() == "coord" do
          try
            if !_coordIsLeader then
              val acq = _coordAcquireFn
              if acq != null then
                val got = try acq.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
                          catch case _: Throwable => false
                if got then
                  _coordIsLeader = true
                  val prev = _currentLeader.getAndSet(_localNodeId)
                  if prev != _localNodeId then
                    _fireLeaderEvent("LeaderElected", _localNodeId)
                    _recordLeaderHist(_localNodeId)
            else
              val ren = _coordRenewFn
              if ren != null then
                val ok = try ren.asInstanceOf[String => Boolean](_localNodeId)
                         catch case _: Throwable => false
                if !ok then
                  _coordIsLeader = false
                  val prev = _currentLeader.getAndSet("")
                  if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
          catch case _: Throwable => ()
          try Thread.sleep(_COORD_RENEW_INTERVAL_MS)
          catch case _: InterruptedException => done = true
      catch case _: Throwable => ()
    }
    if !_coordTickThread.compareAndSet(null, t) then t.interrupt()
  // v1.23 — Raft state (cluster-raft.md §4.1).
  @volatile var _raftCurrentTerm: Long   = 0L
  @volatile var _raftVotedFor:    String = ""        // "" = None
  @volatile var _raftState:       String = "follower" // follower | candidate | leader
  @volatile var _raftLeaderId:    String = ""
  @volatile var _raftElectionDue: Long   = 0L
  @volatile var _raftVotes:       Int    = 0
  val _RAFT_ELECTION_LO  = 150L
  val _RAFT_ELECTION_HI  = 300L
  val _RAFT_HEARTBEAT_MS = 50L
  val _raftTickThread = new java.util.concurrent.atomic.AtomicReference[Thread](null)
  val _raftRand       = new scala.util.Random()
  def _raftRandTimeout: Long =
    _RAFT_ELECTION_LO + _raftRand.nextInt((_RAFT_ELECTION_HI - _RAFT_ELECTION_LO).toInt + 1)
  def _raftBroadcastHeartbeat(): Unit =
    val payload = "{\"t\":\"raft_append\",\"from\":" + _jstr(_localNodeId) +
                  ",\"term\":" + _raftCurrentTerm.toString + "}"
    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
  def _raftAdoptLeader(newLeader: String): Unit =
    val prev = _currentLeader.getAndSet(newLeader)
    if prev != newLeader then
      _fireLeaderEvent("LeaderElected", newLeader)
      _recordLeaderHist(newLeader)
  def _startRaftElection(): Unit =
    _raftState       = "candidate"
    _raftCurrentTerm = _raftCurrentTerm + 1
    _raftVotedFor    = _localNodeId
    _raftVotes       = 1
    _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
    _raftPersist()
    val peerIds = scala.collection.mutable.ListBuffer.empty[String]
    _peerChannels.keySet().forEach(p => peerIds += p)
    val total = peerIds.size + 1
    // Single-node majority is trivially us — claim immediately.
    if _raftVotes > total / 2 then
      _raftState    = "leader"
      _raftLeaderId = _localNodeId
      _raftAdoptLeader(_localNodeId)
      _raftBroadcastHeartbeat()
    else
      val payload = "{\"t\":\"raft_vote_req\",\"from\":" + _jstr(_localNodeId) +
                    ",\"term\":" + _raftCurrentTerm.toString + ",\"lastLogTerm\":0}"
      peerIds.foreach { nid =>
        try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
        catch case _: Throwable => ()
      }
  def _ensureRaftTickThread(): Unit =
    if _raftTickThread.get() != null then return
    val t = Thread.ofVirtual().start { () =>
      try
        while _leaderProtocol.get() == "raft" do
          Thread.sleep(_RAFT_HEARTBEAT_MS)
          val now = System.currentTimeMillis()
          _raftState match
            case "leader" =>
              _raftBroadcastHeartbeat()
            case "follower" | "candidate" =>
              if now >= _raftElectionDue then _startRaftElection()
            case _ => ()
      catch case _: InterruptedException => ()
    }
    if !_raftTickThread.compareAndSet(null, t) then t.interrupt()
  // v1.23 — Raft persistence (cluster-raft.md §4.1).  One JSON file per
  // node, written on every (term, votedFor) mutation so a crashed-and-
  // restarted node doesn't double-vote in the same term.  Best-effort:
  // IO errors are swallowed (the alternative is to refuse to start,
  // which is worse for trusted-deployment use).
  def _raftStatePath: java.nio.file.Path =
    val key = if _localNodeId.isEmpty then "default" else _localNodeId.replaceAll("[^A-Za-z0-9._-]", "_")
    java.nio.file.Paths.get(s".ssc-raft-state-$key.json")
  def _raftPersist(): Unit =
    try
      val voted = _raftVotedFor.replace("\\", "\\\\").replace("\"", "\\\"")
      val json  = "{\"currentTerm\":" + _raftCurrentTerm.toString + ",\"votedFor\":\"" + voted + "\"}"
      java.nio.file.Files.writeString(_raftStatePath, json,
        java.nio.charset.StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    catch case _: Throwable => ()
  def _raftLoad(): Unit =
    try
      val p = _raftStatePath
      if java.nio.file.Files.exists(p) then
        val s = java.nio.file.Files.readString(p)
        val termIdx = s.indexOf("\"currentTerm\"")
        if termIdx >= 0 then
          val ci = s.indexOf(':', termIdx); var i = ci + 1
          while i < s.length && s(i) == ' ' do i += 1
          var j = i; while j < s.length && (s(j).isDigit || s(j) == '-') do j += 1
          if j > i then s.substring(i, j).toLongOption.foreach(t => _raftCurrentTerm = t)
        val vk = "\"votedFor\""
        val ki = s.indexOf(vk)
        if ki >= 0 then
          val qi = s.indexOf('"', ki + vk.length + 1)
          val qe = if qi > 0 then s.indexOf('"', qi + 1) else -1
          if qe > qi then _raftVotedFor = s.substring(qi + 1, qe)
    catch case _: Throwable => ()
  // v1.23 — drain-aware step-down (cluster-raft.md §7).  Called when
  // `setDraining(true)` flips while this node holds leadership.
  // Releases the lease (coord), reverts to follower (Raft), or just
  // clears the cached leader (Bully); always fires LeaderLost(self).
  def _stepDownIfLeader(): Unit =
    _leaderProtocol.get() match
      case "raft" =>
        if _raftState == "leader" then
          _raftState    = "follower"
          _raftLeaderId = ""
          val prev = _currentLeader.getAndSet("")
          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
      case "coord" =>
        if _coordIsLeader then
          _coordIsLeader = false
          val rel = _coordReleaseFn
          if rel != null then
            try rel.asInstanceOf[String => Unit](_localNodeId)
            catch case _: Throwable => ()
          val prev = _currentLeader.getAndSet("")
          if prev.nonEmpty then _fireLeaderEvent("LeaderLost", prev)
      case _ =>
        if _currentLeader.compareAndSet(_localNodeId, "") then
          _fireLeaderEvent("LeaderLost", _localNodeId)
  // v1.23 — auto-reconnect: exponential-backoff retry per peer URL after a
  // disconnect.  Both fields 0 ⇒ disabled (default).  `setReconnectPolicy`
  // sets them at runtime.  `_reconnectGiveUpMs` caps the total
  // wall-clock retry budget per URL (0 = retry forever).
  @volatile var _reconnectInitialMs: Long = 0L
  @volatile var _reconnectMaxMs:     Long = 0L
  @volatile var _reconnectGiveUpMs:  Long = 0L
  // v1.23 — per-link heartbeat tuning.  Defaults 30s ping / 40s dead
  // match the pre-v1.23 hardcoded values; `setHeartbeatTimeout` tunes
  // them for low-latency / test clusters.
  @volatile var _peerHeartbeatIntervalMs:  Long = 30000L
  @volatile var _peerHeartbeatDeadAfterMs: Long = 40000L
  // v1.23 — quorum-aware Bully threshold.  0 = no quorum check;
  // set to N/2+1 of expected cluster size for split-brain guard.
  @volatile var _quorumSize: Long = 0L
  def _hasQuorum: Boolean = _quorumSize <= 0L || (_peerChannels.size + 1L) >= _quorumSize
  // v1.23 — cluster configuration distribution.  LWW per key by timestamp;
  // ties broken by lex-greatest nodeId so all nodes converge.
  val _clusterConfig    = new java.util.concurrent.ConcurrentHashMap[String, (String, Long, String)]()
  val _configEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  val _configEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
  def _fireConfigEvent(key: String, value: String): Unit =
    val ts = System.currentTimeMillis()
    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"ConfigChanged\",\"key\":" +
                    _jstr(key) + ",\"value\":" + _jstr(value) + "}")
    if !_configEventSubs.isEmpty then _configEventQueue.offer((key, value))
  // Returns true if (ts, origin) wins over the stored (ts, origin) for key.
  def _applyConfigUpdate(key: String, value: String, ts: Long, origin: String): Boolean =
    val prev = _clusterConfig.get(key)
    val accept =
      prev == null || ts > prev._2 ||
      (ts == prev._2 && origin > prev._3)
    if accept then
      _clusterConfig.put(key, (value, ts, origin))
      _fireConfigEvent(key, value)
    accept
  // Snapshot every locally-known config entry to a single peer.  Called
  // on every successful handshake so late-joining nodes pick up entries
  // set before they joined.  LWW on the receiver protects us from
  // downgrading any value the new peer might already have.
  def _sendConfigSnapshot(targetSend: String => Unit): Unit =
    _clusterConfig.forEach { (key, tuple) =>
      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
                    ",\"value\":" + _jstr(tuple._1) +
                    ",\"ts\":" + tuple._2.toString +
                    ",\"origin\":" + _jstr(tuple._3) + "}"
      try targetSend(payload) catch case _: Throwable => ()
    }
  // v1.23 — drain / rolling-restart state
  val _isDrainingSelf  = new java.util.concurrent.atomic.AtomicBoolean(false)
  val _drainingPeers   = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  val _drainEventSubs  = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  val _drainEventQueue = new java.util.concurrent.ConcurrentLinkedQueue[(String, Boolean)]()
  def _fireDrainEvent(nodeId: String, draining: Boolean): Unit =
    val ts = System.currentTimeMillis()
    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"DrainStateChanged\",\"nodeId\":" +
                    _jstr(nodeId) + ",\"draining\":" + draining.toString + "}")
    if !_drainEventSubs.isEmpty then _drainEventQueue.offer((nodeId, draining))
  // Tell a freshly-handshaken peer our current drain state.  No-op when we
  // are not draining (peers default-assume `false`).
  def _sendDrainState(targetSend: String => Unit): Unit =
    if _isDrainingSelf.get() then
      val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) + ",\"draining\":true}"
      try targetSend(payload) catch case _: Throwable => ()
  // v1.23 — cluster metrics: per-node gauges.
  //   _clusterMetrics(name)(nodeId) = latest value
  val _clusterMetrics    = new java.util.concurrent.ConcurrentHashMap[String,
    java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]]()
  val _metricEventSubs   = new java.util.concurrent.CopyOnWriteArrayList[java.lang.Long]()
  val _metricEventQueue  = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, Double)]()
  def _fireMetricEvent(name: String, nodeId: String, value: Double): Unit =
    val ts = System.currentTimeMillis()
    _recordEventLog("{\"ts\":" + ts.toString + ",\"type\":\"MetricChanged\",\"name\":" +
                    _jstr(name) + ",\"nodeId\":" + _jstr(nodeId) +
                    ",\"value\":" + value.toString + "}")
    if !_metricEventSubs.isEmpty then _metricEventQueue.offer((name, nodeId, value))
  def _applyMetricUpdate(name: String, nodeId: String, value: Double): Unit =
    val inner = _clusterMetrics.computeIfAbsent(name, _ =>
      new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]())
    val boxed = java.lang.Double.valueOf(value)
    val prev  = inner.put(nodeId, boxed)
    if prev == null || prev.doubleValue() != value then
      _fireMetricEvent(name, nodeId, value)
  // Snapshot every local metric to a single peer on handshake so late
  // joiners catch up without waiting for the next set.
  def _sendMetricSnapshot(targetSend: String => Unit): Unit =
    _clusterMetrics.forEach { (name, inner) =>
      val localVal = inner.get(_localNodeId)
      if localVal != null then
        val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
                      ",\"name\":" + _jstr(name) +
                      ",\"value\":" + localVal.doubleValue().toString + "}"
        try targetSend(payload) catch case _: Throwable => ()
    }
  // v1.23 — Bearer-token gate shared by every /_ssc-cluster/* HTTP
  // route.  Returns Some(401-response) when the token is set and the
  // request's Authorization header doesn't carry `Bearer <token>`;
  // None when the token is empty (endpoints open) or matches.  Mirrors
  // `Interpreter.clusterAuthReject`.
  def _clusterAuthReject(req: Request): Option[Response] =
    val tok = _clusterAuthToken
    if tok.isEmpty then None
    else
      val hdr = req.headers.getOrElse("authorization", "")
      if hdr == ("Bearer " + tok) then None
      else Some(Response(
        401,
        Map("Content-Type" -> "application/json"),
        "{\"error\":\"unauthorized\",\"hint\":\"set Authorization: Bearer <token>\"}"))
  // v1.23 — `GET /_ssc-cluster/status` JSON snapshot of cluster state.
  // Idempotent: subsequent `startNode` calls are no-ops for the route
  // table.  Mirrors `Interpreter.registerClusterStatusRoute`.
  def _registerClusterStatusRoute(): Unit =
    val path = "/_ssc-cluster/status"
    if _routes.exists(r => r.method == "GET" && r.path == path) then return
    route("GET", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          val sb = new StringBuilder("{")
          def kv(k: String, jsonVal: String, first: Boolean = false): Unit =
            if !first then sb.append(',')
            sb.append('"').append(k).append("\":").append(jsonVal)
          def jsonStrArr(xs: Iterable[String]): String =
            xs.map(_jstr).mkString("[", ",", "]")
          val members = scala.collection.mutable.ListBuffer.empty[String]
          _peerChannels.keySet().forEach(p => members += p)
          val drainPeers = scala.collection.mutable.ListBuffer.empty[String]
          _drainingPeers.forEach { (nid, dr) =>
            if dr != null && dr.booleanValue() then drainPeers += nid
          }
          val leaderNow =
            _leaderProtocol.get() match
              case "raft" => _raftLeaderId
              case _      => _currentLeader.get()
          kv("nodeId",        _jstr(_localNodeId), first = true)
          kv("leader",        _jstr(leaderNow))
          kv("protocol",      _jstr(_leaderProtocol.get()))
          kv("members",       jsonStrArr(members.toList))
          kv("drainingSelf",  if _isDrainingSelf.get() then "true" else "false")
          kv("drainingPeers", jsonStrArr(drainPeers.toList))
          kv("raftTerm",      _raftCurrentTerm.toString)
          kv("raftState",     _jstr(_raftState))
          sb.append('}')
          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
    }
  // v1.23 — `POST /_ssc-cluster/drain` toggles local drain state.
  // Body is JSON `{"enabled":true|false}` (empty body = enable).
  // Mirrors the in-process `setDraining` effect: flips
  // `_isDrainingSelf`, broadcasts DrainStateChanged to peers, steps
  // down if we were leader.  Used by `ssc cluster drain <url> [--off]`.
  def _registerClusterDrainRoute(): Unit =
    val path = "/_ssc-cluster/drain"
    if _routes.exists(r => r.method == "POST" && r.path == path) then return
    route("POST", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          val body = req.body
          val enabled: Boolean =
            if body.trim.isEmpty then true
            else
              val needle = "\"enabled\":"
              val i = body.indexOf(needle)
              if i < 0 then true
              else
                val rest = body.substring(i + needle.length).trim
                !rest.startsWith("false")
          val prev = _isDrainingSelf.getAndSet(enabled)
          if prev != enabled then
            val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
                          ",\"draining\":" + enabled.toString + "}"
            _peerChannels.forEach { (_, send) =>
              try send(payload) catch case _: Throwable => ()
            }
            _fireDrainEvent(_localNodeId, enabled)
            if enabled then _stepDownIfLeader()
          Response(
            200,
            Map("Content-Type" -> "application/json"),
            "{\"drainingSelf\":" + (if enabled then "true" else "false") + "}")
    }
  // v1.23 — `GET /_ssc-cluster/events[?since=<ts>]` returns the bounded
  // ring buffer of recent cluster events as a JSON array.  Optional
  // `since` query filters to entries strictly newer than the given
  // epoch-ms.  Idempotent registration.
  def _registerClusterEventsRoute(): Unit =
    val path = "/_ssc-cluster/events"
    if _routes.exists(r => r.method == "GET" && r.path == path) then return
    route("GET", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          val sinceMs: Long =
            req.query.get("since").flatMap(_.toLongOption).getOrElse(0L)
          val sb = new StringBuilder("[")
          var first = true
          val it = _clusterEventLog.iterator()
          while it.hasNext do
            val line = it.next()
            val tsMatch =
              if sinceMs <= 0L then true
              else
                val tsPrefix = "{\"ts\":"
                if line.startsWith(tsPrefix) then
                  val end = line.indexOf(',', tsPrefix.length)
                  if end > 0 then
                    line.substring(tsPrefix.length, end).toLongOption
                      .exists(_ > sinceMs)
                  else false
                else false
            if tsMatch then
              if !first then sb.append(',')
              sb.append(line)
              first = false
          sb.append(']')
          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
    }
  // v1.23 — `POST /_ssc-cluster/step-down`.  If this node is the current
  // leader, step down (clear `_currentLeader`, broadcast `LeaderLost`,
  // surrender any external coordinator lease).  If it's not the leader,
  // returns 409 Conflict so the operator notices.  Apps with
  // `setAutoReelect(true)` re-elect automatically — that's the rolling-
  // restart pattern.
  def _registerClusterStepDownRoute(): Unit =
    val path = "/_ssc-cluster/step-down"
    if _routes.exists(r => r.method == "POST" && r.path == path) then return
    route("POST", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          val wasLeader =
            _leaderProtocol.get() match
              case "raft"  => _raftState == "leader"
              case "coord" => _coordIsLeader
              case _       => _currentLeader.get() == _localNodeId
          if !wasLeader then
            Response(
              409,
              Map("Content-Type" -> "application/json"),
              "{\"error\":\"not_leader\",\"leader\":" + _jstr(_currentLeader.get()) + "}")
          else
            _stepDownIfLeader()
            Response(
              200,
              Map("Content-Type" -> "application/json"),
              "{\"steppedDown\":true,\"nodeId\":" + _jstr(_localNodeId) + "}")
    }
  // v1.23 — `GET /_ssc-cluster/metrics-prom` returns `_clusterMetrics`
  // gauges in Prometheus text exposition format.  One
  // `<sanitized-name>{nodeId="<id>"} <value>` line per (metric, peer)
  // pair, plus `# TYPE … gauge` declarations.  Same Bearer-token gate.
  def _registerClusterMetricsPromRoute(): Unit =
    val path = "/_ssc-cluster/metrics-prom"
    if _routes.exists(r => r.method == "GET" && r.path == path) then return
    route("GET", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          // Prometheus metric names must match `[a-zA-Z_:][a-zA-Z0-9_:]*`.
          def sanitize(s: String): String =
            val sb = new StringBuilder(s.length)
            var i = 0
            while i < s.length do
              val c = s.charAt(i)
              val ok =
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || c == '_' || c == ':'
              sb.append(if ok then c else '_')
              i += 1
            val out = sb.toString
            if out.nonEmpty && out.charAt(0) >= '0' && out.charAt(0) <= '9'
            then "_" + out else out
          def escLabel(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
          val sb = new StringBuilder()
          _clusterMetrics.forEach { (name, inner) =>
            val pName = sanitize(name)
            sb.append("# TYPE ").append(pName).append(" gauge\n")
            inner.forEach { (nodeId, value) =>
              sb.append(pName)
                .append("{nodeId=\"").append(escLabel(nodeId)).append("\"} ")
                .append(value.doubleValue())
                .append('\n')
            }
          }
          Response(
            200,
            Map("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8"),
            sb.toString)
    }
  // v1.63.5 — `GET /_ssc-cluster/handlers` returns the remote handler
  // registry as a JSON array so `ssc cluster handlers` can list
  // exported operations from a running node.
  def _registerClusterHandlersRoute(): Unit =
    val path = "/_ssc-cluster/handlers"
    if _routes.exists(r => r.method == "GET" && r.path == path) then return
    route("GET", path) { req =>
      _clusterAuthReject(req) match
        case Some(r) => r
        case None =>
          val sb = new StringBuilder("[")
          var first = true
          _remoteHandlers.forEach { (name, info) =>
            if !first then sb.append(',')
            first = false
            sb.append('{')
            sb.append("\"name\":").append(_jstr(name))
            sb.append(",\"function\":").append(_jstr(info.function))
            info.path.foreach { p => sb.append(",\"path\":").append(_jstr(p)) }
            info.requestType.foreach { t => sb.append(",\"requestType\":").append(_jstr(t)) }
            info.responseType.foreach { t => sb.append(",\"responseType\":").append(_jstr(t)) }
            val transports = info.transports.map(t => "\"" + t + "\"").mkString(",")
            sb.append(s",\"transports\":[$transports]")
            sb.append('}')
          }
          sb.append(']')
          Response(200, Map("Content-Type" -> "application/json"), sb.toString)
    }
  def _broadcastCoordinator(): Unit =
    val payload = "{\"t\":\"coordinator\",\"from\":" + _jstr(_localNodeId) + "}"
    _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
  def _startElection(): Unit =
    if _localNodeId.isEmpty then
      val prev = _currentLeader.getAndSet(_localNodeId)
      if prev != _localNodeId then { _fireLeaderEvent("LeaderElected", _localNodeId); _recordLeaderHist(_localNodeId) }
    else
      val higher = scala.collection.mutable.ListBuffer.empty[String]
      _peerChannels.keySet().forEach(nid => if nid > _localNodeId then higher += nid)
      if higher.isEmpty then
        // v1.23 — quorum gate: refuse self-claim when below quorum
        // (split-brain guard).  No-op when `_quorumSize = 0`.
        if !_hasQuorum then ()
        else
          val prev = _currentLeader.getAndSet(_localNodeId)
          _broadcastCoordinator()
          if prev != _localNodeId then
            _fireLeaderEvent("LeaderElected", _localNodeId)
            _recordLeaderHist(_localNodeId)
      else
        _electionInProgress = true
        _electionStartedAt  = System.currentTimeMillis()
        _gotAliveResponse   = false
        val payload = "{\"t\":\"election\",\"from\":" + _jstr(_localNodeId) + "}"
        higher.foreach { nid =>
          try Option(_peerChannels.get(nid)).foreach(_.apply(payload))
          catch case _: Throwable => ()
        }
  def _recordPongInterval(nid: String): Unit =
    val now  = System.currentTimeMillis()
    val last = _peerLastPong.getOrDefault(nid, 0L)
    if last > 0L then
      val delta = java.lang.Long.valueOf(now - last)
      val dq    = _peerPongHist.computeIfAbsent(nid, _ =>
        new java.util.concurrent.ConcurrentLinkedDeque[java.lang.Long]())
      dq.offer(delta)
      while dq.size() > _PHI_HIST_MAX do dq.pollFirst()
  def _computePhi(nid: String): Double =
    val hist = _peerPongHist.get(nid)
    if hist == null || hist.isEmpty then return Double.PositiveInfinity
    val n = hist.size
    var s = 0.0
    val it = hist.iterator
    while it.hasNext do s += it.next().longValue().toDouble
    val mean = s / n
    var sq = 0.0
    val it2 = hist.iterator
    while it2.hasNext do
      val d = it2.next().longValue().toDouble - mean
      sq += d * d
    val variance = if n > 1 then sq / (n - 1) else 1.0
    val stddev   = math.sqrt(variance).max(50.0)
    val now      = System.currentTimeMillis()
    val last     = _peerLastPong.getOrDefault(nid, now)
    val elapsed  = (now - last).toDouble
    if elapsed <= mean then 0.0
    else
      val z    = (elapsed - mean) / stddev
      val tail = math.exp(-z * z / 2.0) / (z * math.sqrt(2.0 * math.Pi))
      if tail <= 0.0 then Double.PositiveInfinity
      else -math.log10(tail.min(1.0))

  val ready  = scala.collection.mutable.ArrayDeque.empty[Long]
  var nextId: Long = 0L
  var rootResult: Any = ()

  def spawnActor(thunk: () => Any, cap: Int = 0, overflow: String = ""): Long =
    val id = nextId
    nextId += 1
    val st = new _ActorState
    st.pending = thunk()
    st.cap = cap
    st.overflow = overflow
    actors.put(id, st)
    ready.append(id)
    id

  val rootId = spawnActor(bodyThunk)

  def _fireNodeDown(nodeId: String): Unit =
    val noconn = "noconnection"
    val deadBase = Map("nodeId" -> nodeId)
    Option(_remoteMonitors.remove(nodeId)).foreach { list =>
      list.forEach { (actorId, monRef, rPidLocalId) =>
        actors.get(actorId).foreach { st =>
          st.mailbox.offer(Down(monRef, _Pid(nodeId, rPidLocalId), noconn))
          tryWakeBlocked(actorId)
        }
      }
    }
    Option(_remoteLinks.remove(nodeId)).foreach { list =>
      list.forEach { (actorId, rPidLocalId) =>
        if trapExitM.getOrElse(actorId, false) then
          actors.get(actorId).foreach { st =>
            st.mailbox.offer(Exit(_Pid(nodeId, rPidLocalId), noconn))
            tryWakeBlocked(actorId)
          }
        else killActor(actorId, noconn)
      }
    }

  def _connectPeer(url: String, token: String): Unit =
    Thread.ofVirtual().start { () =>
      try
        import java.net.URI
        import java.net.http.{HttpClient => _JHC2, WebSocket => _JWs2}
        import java.util.concurrent.{LinkedBlockingQueue => _LBQ, CompletableFuture => _CF}
        val recvQ  = new _LBQ[String | Null]()
        val textB  = new StringBuilder()
        @volatile var _ws2: _JWs2 | Null = null
        val listener = new _JWs2.Listener:
          override def onText(ws: _JWs2, data: CharSequence, last: Boolean): _CF[?] =
            textB.append(data)
            if last then { val m = textB.toString(); textB.setLength(0); recvQ.offer(m) }
            ws.request(1); _CF.completedFuture(null)
          override def onClose(ws: _JWs2, c: Int, r: String): _CF[?] =
            recvQ.offer(null); _CF.completedFuture(null)
          override def onError(ws: _JWs2 | Null, e: Throwable): Unit =
            System.err.println("ssc-peer error [" + url + "]: " + e.getMessage); recvQ.offer(null)
        val hdrs = if token.nonEmpty then Map("Authorization" -> ("Bearer " + token)) else Map.empty[String,String]
        val builder = _JHC2.newHttpClient().newWebSocketBuilder()
        hdrs.foreach { case (k, v) => builder.header(k, v) }
        builder.subprotocols("ssc-actors-v1")
        val ws = builder.buildAsync(URI.create(url), listener).join()
        _ws2 = ws
        def sendFn(t: String): Unit = if _ws2 != null then _ws2.sendText(t, true)
        def recvFn(): String | Null  = recvQ.take()
        sendFn("{\"nodeId\":" + _jstr(_localNodeId) + "}")
        val first = recvFn()
        if first != null then
          val pnId = _parseNodeId(first)
          if pnId.nonEmpty then
            _peerUrls.put(pnId, url)
            _peerChannels.put(pnId, sendFn)
            _peerLastPong.put(pnId, System.currentTimeMillis())
            _fireClusterEvent("NodeJoined", pnId)
            if _joinMode then try sendFn("{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}") catch case _: Throwable => ()
            // v1.23 — snapshot the cluster config to the new peer so it
            // sees entries set before it joined (LWW protects existing values).
            _sendConfigSnapshot(sendFn)
            _sendDrainState(sendFn)
            _sendMetricSnapshot(sendFn)
            val hbThread = Thread.ofVirtual().start { () =>
              try
                while _peerChannels.containsKey(pnId) do
                  Thread.sleep(_peerHeartbeatIntervalMs)
                  if _peerChannels.containsKey(pnId) then
                    val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
                    if age > _peerHeartbeatDeadAfterMs then
                      _peerChannels.remove(pnId)
                      try if _ws2 != null then _ws2.abort() catch case _: Throwable => ()
                    else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
              catch case _: InterruptedException => ()
            }
            var running = true
            while running do
              val msg = recvFn()
              if msg == null then running = false
              else _dispatchPeerEnv(pnId, msg)
            hbThread.interrupt()
            _peerChannels.remove(pnId)
            _peerLastPong.remove(pnId)
            _peerUrls.remove(pnId)
            _peerPongHist.remove(pnId)
            _peerPhiViews.remove(pnId)
            _drainingPeers.remove(pnId)
            _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
            _nodeDownQueue.offer(pnId)
            _fireClusterEvent("NodeLeft", pnId, "disconnect")
            if _currentLeader.compareAndSet(pnId, "") then
              _fireLeaderEvent("LeaderLost", pnId)
              if _autoReelect then _startElection()
            // v1.23 — auto-reconnect: schedule exponential-backoff retries
            // for this URL until the peer reappears.
            if _reconnectInitialMs > 0L then _scheduleReconnect(url, token)
      catch case e: Throwable =>
        System.err.println("connectNode error [" + url + "]: " + e.getMessage)
        // v1.23 — schedule reconnect from the dial-failure path too.
        // Without this, non-seed nodes racing each other only ever
        // reach the seed and the cluster stays fragmented.
        if _reconnectInitialMs > 0L && !_peerUrls.containsValue(url) then
          _scheduleReconnect(url, token)
    }

  // v1.23 — URL-keyed dedupe so concurrent peer-loss + dial-failure
  // events for the same URL don't each spin up an independent
  // exponential-backoff loop (FD exhaustion under sustained churn).
  // `lazy` is required: `killActor` is referenced earlier in the
  // emitted preamble (`_connectPeer`'s catch) than where it's
  // defined, and a regular val here would block the forward
  // reference per Scala's init-order rule.
  lazy val _reconnectActive =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  def _scheduleReconnect(rurl: String, rtok: String): Unit =
    if !_reconnectActive.add(rurl) then return
    Thread.ofVirtual().start { () =>
      val startedAt = System.currentTimeMillis()
      var delay = _reconnectInitialMs.max(1L)
      var done  = false
      try
        while !done && !_peerUrls.containsValue(rurl) do
          try Thread.sleep(delay) catch case _: InterruptedException => done = true
          if !done && _reconnectInitialMs <= 0L then done = true
          if !done && _peerUrls.containsValue(rurl) then done = true
          // v1.23 — give-up budget: stop retrying after the
          // configured wall-clock elapsed.  0 ⇒ retry forever.
          if !done && _reconnectGiveUpMs > 0L &&
             (System.currentTimeMillis() - startedAt) >= _reconnectGiveUpMs then
            done = true
          if !done then
            try _connectPeer(rurl, rtok) catch case _: Throwable => ()
            if _peerUrls.containsValue(rurl) then done = true
            else
              val cap = if _reconnectMaxMs > 0L then _reconnectMaxMs else delay
              delay = math.min(delay * 2L, cap.max(delay))
      catch case _: Throwable => ()
      finally _reconnectActive.remove(rurl)
    }

  def _parseNodeId(json: String): String =
    val key = "\"nodeId\""
    val ki = json.indexOf(key); if ki < 0 then return ""
    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
    json.substring(vi + 1, ve)

  def _dispatchPeerEnv(pnId: String, json: String): Unit =
    val ti = json.indexOf("\"t\""); if ti < 0 then return
    val vi = json.indexOf('"', ti + 4); if vi < 0 then return
    val ve = json.indexOf('"', vi + 1); if ve < 0 then return
    val t  = json.substring(vi + 1, ve)
    t match
      case "msg" =>
        val toId = _extractToLocalId(json)
        if toId >= 0 then
          val body = _extractBody(json)
          if body != null then
            val msg = _deserializeValue(body)
            _remoteInbox.offer((toId, msg))
      case "ping" => try Option(_peerChannels.get(pnId)).foreach(_.apply("{\"t\":\"pong\"}")) catch case _: Throwable => ()
      case "pong" =>
        _recordPongInterval(pnId)
        _peerLastPong.put(pnId, System.currentTimeMillis())
      case "peers_req" =>
        val sb = new StringBuilder("{\"t\":\"peers_resp\",\"peers\":[")
        var first = true
        if _localNodeUrl.nonEmpty then
          sb.append("{\"nodeId\":" + _jstr(_localNodeId) + ",\"url\":" + _jstr(_localNodeUrl) + "}")
          first = false
        _peerUrls.forEach { (nid, u) =>
          if u.nonEmpty then
            if !first then sb.append(',')
            sb.append("{\"nodeId\":" + _jstr(nid) + ",\"url\":" + _jstr(u) + "}")
            first = false
        }
        sb.append("]}")
        try Option(_peerChannels.get(pnId)).foreach(_.apply(sb.toString)) catch case _: Throwable => ()
      case "peers_resp" =>
        _extractPeersList(json).foreach { case (pnid, purl) =>
          if pnid.nonEmpty && purl.nonEmpty && pnid != _localNodeId && !_peerChannels.containsKey(pnid) then
            _connectPeer(purl, _joinToken)
        }
      case "global_reg" =>
        val grName    = _extractJsonStr(json, "\"name\"")
        val grNodeId  = _extractJsonStr(json, "\"nodeId\"")
        val grLocalId = _extractJsonStr(json, "\"localId\"").toLongOption.getOrElse(0L)
        if grName.nonEmpty && grNodeId.nonEmpty then
          _globalRegistry.put(grName, _Pid(grNodeId, grLocalId))
      case "phi_vector" =>
        // v1.23 — peer's phi vector.  Parse out `from` and the `view`
        // pair list, replace our recorded view of that peer.
        val from = _extractJsonStr(json, "\"from\"")
        if from.nonEmpty then
          val pairs = _extractPhiView(json)
          val m = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Double]()
          pairs.foreach { case (nid, p) => m.put(nid, java.lang.Double.valueOf(p)) }
          _peerPhiViews.put(from, m)
      case "election" =>
        // v1.23 — Bully: lower-id peer is calling an election.  Respond
        // with `alive` (we're bigger) and start our own election.
        val from = _extractJsonStr(json, "\"from\"")
        if from.nonEmpty && from < _localNodeId then
          val reply = "{\"t\":\"alive\",\"from\":" + _jstr(_localNodeId) + "}"
          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
          catch case _: Throwable => ()
          if !_electionInProgress then _startElection()
      case "alive" =>
        _gotAliveResponse = true
      case "coordinator" =>
        val from = _extractJsonStr(json, "\"from\"")
        if from.nonEmpty then
          val prev = _currentLeader.getAndSet(from)
          _electionInProgress = false
          if prev != from then
            _fireLeaderEvent("LeaderElected", from)
            _recordLeaderHist(from)
      case "config_set" =>
        // v1.23 — cluster config distribution.  LWW by (ts, originNodeId).
        val key   = _extractJsonStr(json, "\"key\"")
        val value = _extractJsonStr(json, "\"value\"")
        val orig  = _extractJsonStr(json, "\"origin\"")
        val ts    = _extractJsonLong(json, "\"ts\"")
        if key.nonEmpty then _applyConfigUpdate(key, value, ts, orig)
      case "drain" =>
        // v1.23 — peer announced its drain state.
        val from = _extractJsonStr(json, "\"from\"")
        if from.nonEmpty then
          val isDraining = json.contains("\"draining\":true")
          val prev = _drainingPeers.put(from, java.lang.Boolean.valueOf(isDraining))
          if prev == null || prev.booleanValue() != isDraining then
            _fireDrainEvent(from, isDraining)
      case "metric" =>
        val from  = _extractJsonStr(json, "\"from\"")
        val name  = _extractJsonStr(json, "\"name\"")
        val value = _extractJsonDouble(json, "\"value\"")
        if from.nonEmpty && name.nonEmpty then _applyMetricUpdate(name, from, value)
      // v1.23 — Raft RPCs (cluster-raft.md §4.2).
      case "raft_vote_req" =>
        val from = _extractJsonStr(json, "\"from\"")
        val term = _extractJsonLong(json, "\"term\"")
        if from.nonEmpty then
          var mutated = false
          val granted =
            if term < _raftCurrentTerm then false
            else
              if term > _raftCurrentTerm then
                _raftCurrentTerm = term
                _raftVotedFor    = ""
                _raftState       = "follower"
                mutated = true
              if _raftVotedFor.isEmpty || _raftVotedFor == from then
                _raftVotedFor    = from
                _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
                mutated = true
                true
              else false
          if mutated then _raftPersist()
          val reply = "{\"t\":\"raft_vote_resp\",\"from\":" + _jstr(_localNodeId) +
                      ",\"term\":" + _raftCurrentTerm.toString +
                      ",\"granted\":" + granted.toString + "}"
          try Option(_peerChannels.get(from)).foreach(_.apply(reply))
          catch case _: Throwable => ()
      case "raft_vote_resp" =>
        val term = _extractJsonLong(json, "\"term\"")
        val granted = json.contains("\"granted\":true")
        if term == _raftCurrentTerm && _raftState == "candidate" && granted then
          _raftVotes = _raftVotes + 1
          val total = _peerChannels.size() + 1
          if _raftVotes > total / 2 then
            _raftState    = "leader"
            _raftLeaderId = _localNodeId
            _raftAdoptLeader(_localNodeId)
            _raftBroadcastHeartbeat()
      case "raft_append" =>
        val from = _extractJsonStr(json, "\"from\"")
        val term = _extractJsonLong(json, "\"term\"")
        if from.nonEmpty && term >= _raftCurrentTerm then
          val termChanged = term > _raftCurrentTerm
          _raftCurrentTerm = term
          _raftState       = "follower"
          val prevLeader   = _raftLeaderId
          _raftLeaderId    = from
          _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
          if termChanged then _raftPersist()
          if prevLeader != from then _raftAdoptLeader(from)
      case _      => ()

  def _extractJsonStr(json: String, key: String, fromIdx: Int = 0): String =
    val ki = json.indexOf(key, fromIdx); if ki < 0 then return ""
    val vi = json.indexOf('"', ki + key.length + 1); if vi < 0 then return ""
    val ve = json.indexOf('"', vi + 1); if ve < 0 then return ""
    json.substring(vi + 1, ve)

  def _extractJsonLong(json: String, key: String): Long =
    val ki = json.indexOf(key); if ki < 0 then return 0L
    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0L
    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
    if j > i then json.substring(i, j).toLongOption.getOrElse(0L) else 0L

  def _extractJsonDouble(json: String, key: String): Double =
    val ki = json.indexOf(key); if ki < 0 then return 0.0
    val ci = json.indexOf(':', ki + key.length); if ci < 0 then return 0.0
    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
    var j = i
    while j < json.length && (json(j).isDigit || json(j) == '-' ||
                              json(j) == '.' || json(j) == 'e' || json(j) == 'E' ||
                              json(j) == '+') do j += 1
    if j > i then json.substring(i, j).toDoubleOption.getOrElse(0.0) else 0.0

  def _extractPeersList(json: String): List[(String, String)] =
    val ak = "\"peers\""; val ai = json.indexOf(ak); if ai < 0 then return Nil
    val ab = json.indexOf('[', ai + ak.length); if ab < 0 then return Nil
    var ae = ab + 1; var depth = 1
    while ae < json.length && depth > 0 do
      if json(ae) == '[' then depth += 1
      else if json(ae) == ']' then depth -= 1
      ae += 1
    val arr = json.substring(ab + 1, ae - 1)
    val buf = scala.collection.mutable.ListBuffer.empty[(String, String)]
    var pos = 0
    while pos < arr.length do
      val ob = arr.indexOf('{', pos); if ob < 0 then pos = arr.length
      else
        var oe = ob + 1; var d2 = 1
        while oe < arr.length && d2 > 0 do
          if arr(oe) == '{' then d2 += 1
          else if arr(oe) == '}' then d2 -= 1
          oe += 1
        val obj = arr.substring(ob, oe)
        val nid = _extractJsonStr(obj, "\"nodeId\"")
        val url = _extractJsonStr(obj, "\"url\"")
        if nid.nonEmpty && url.nonEmpty then buf += ((nid, url))
        pos = oe
    buf.toList

  // v1.23 — parse a `view` field of shape [["nodeA",0.5],["nodeB",2.3], ...]
  // from a phi_vector envelope.  Returns the inner pairs.
  def _extractPhiView(json: String): List[(String, Double)] =
    val key = "\"view\""; val ki = json.indexOf(key); if ki < 0 then return Nil
    val outer = json.indexOf('[', ki + key.length); if outer < 0 then return Nil
    var oe = outer + 1; var od = 1
    while oe < json.length && od > 0 do
      if json(oe) == '[' then od += 1
      else if json(oe) == ']' then od -= 1
      oe += 1
    val arr = json.substring(outer + 1, oe - 1)
    val buf = scala.collection.mutable.ListBuffer.empty[(String, Double)]
    var pos = 0
    while pos < arr.length do
      val ib = arr.indexOf('[', pos); if ib < 0 then pos = arr.length
      else
        var ie = ib + 1; var d2 = 1
        while ie < arr.length && d2 > 0 do
          if arr(ie) == '[' then d2 += 1
          else if arr(ie) == ']' then d2 -= 1
          ie += 1
        val inner = arr.substring(ib + 1, ie - 1).trim
        val nameEnd = inner.indexOf('"', 1)
        if inner.startsWith("\"") && nameEnd > 0 then
          val nm = inner.substring(1, nameEnd)
          val tail = inner.substring(nameEnd + 1).dropWhile(c => c == ',' || c == ' ').trim
          tail.toDoubleOption match
            case Some(d) => buf += ((nm, d))
            case None    => ()
        pos = ie
    buf.toList

  def _extractToLocalId(json: String): Long =
    val toKey = "\"to\""; val ti = json.indexOf(toKey); if ti < 0 then return -1L
    val lk = "\"localId\""; val li = json.indexOf(lk, ti); if li < 0 then return -1L
    val ci = json.indexOf(':', li + lk.length); if ci < 0 then return -1L
    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
    var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
    if j > i then json.substring(i, j).toLongOption.getOrElse(-1L) else -1L

  def _extractBody(json: String): String | Null =
    val bk = "\"body\""; val bi = json.indexOf(bk); if bi < 0 then return null
    val ci = json.indexOf(':', bi + bk.length); if ci < 0 then return null
    var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
    if i >= json.length then return null
    // Body is a nested JSON object — find balanced {}
    if json(i) != '{' then return null
    var depth = 0; var j = i
    while j < json.length do
      if json(j) == '{' then depth += 1
      else if json(j) == '}' then { depth -= 1; if depth == 0 then return json.substring(i, j + 1) }
      j += 1
    null

  def _deserializeValue(json: String): Any =
    val ti = json.indexOf("\"$t\""); if ti < 0 then return json
    val vi = json.indexOf('"', ti + 5); if vi < 0 then return json
    val ve = json.indexOf('"', vi + 1); if ve < 0 then return json
    val tag = json.substring(vi + 1, ve)
    tag match
      case "i" =>
        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
        json.substring(i, j).toLongOption.getOrElse(0L)
      case "d" =>
        val nv = json.indexOf("\"v\""); val ci = json.indexOf(':', nv + 3)
        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-' || json(j) == '.' || json(j) == 'e') do j += 1
        json.substring(i, j).toDoubleOption.getOrElse(0.0)
      case "s" =>
        val nv = json.indexOf("\"v\""); val qi = json.indexOf('"', json.indexOf(':', nv + 3) + 1)
        val qe = json.indexOf('"', qi + 1)
        if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
      case "b" =>
        json.contains("true")
      case "u" => ()
      case "pid" =>
        val ni = json.indexOf("\"n\""); val qi = json.indexOf('"', json.indexOf(':', ni + 3) + 1)
        val qe = json.indexOf('"', qi + 1); val nid = if qi >= 0 && qe > qi then json.substring(qi + 1, qe) else ""
        val li = json.indexOf("\"id\""); val ci = json.indexOf(':', li + 4)
        var i = ci + 1; while i < json.length && json(i) == ' ' do i += 1
        var j = i; while j < json.length && (json(j).isDigit || json(j) == '-') do j += 1
        val lid = json.substring(i, j).toLongOption.getOrElse(0L)
        _Pid(nid, lid)
      case _ => json


  def _resumeBlockedSender(state: _ActorState): Unit =
    if state.cap <= 0 || state.blockedSends.isEmpty then return
    if state.mailbox.size >= state.cap then return
    while state.blockedSends.nonEmpty do
      val (senderId, msg, senderK) = state.blockedSends.removeHead()
      actors.get(senderId) match
        case Some(ss) if ss != null =>
          state.mailbox.offer(msg)
          // Defer senderK(()) via _FlatMap so the continuation's side
          // effects run in the sender's own stepActor turn, not in the
          // current actor's turn (ordering fix: Block overflow).
          ss.pending = _FlatMap((), senderK)
          ready.append(senderId)
          return
        case _ => ()  // dead sender — skip

  def tryDeliver(state: _ActorState, matcher: Any => Option[Any], wrapSome: Boolean): Option[Any] =
    while !state.mailbox.isEmpty do
      val msg = state.mailbox.peek()
      matcher(msg) match
        case Some(bodyC) =>
          state.mailbox.poll()
          _resumeBlockedSender(state)
          if wrapSome then
            return Some(_FlatMap(bodyC, (v: Any) => Some(v)))
          else
            return Some(bodyC)
        case None =>
          state.mailbox.poll()
          _resumeBlockedSender(state)
    None

  def tryWakeBlocked(id: Long): Unit =
    actors.get(id).foreach { st =>
      if st.blocked != null then
        val b = st.blocked
        tryDeliver(st, b._1, b._4) match
          case Some(c) =>
            st.pending = _FlatMap(c, b._2)
            st.blocked = null
            ready.append(id)
          case None => ()
    }

  def killActor(targetId: Long, reason: Any): Unit =
    if !actors.contains(targetId) then return
    val _dyingSt = actors(targetId)
    actors.remove(targetId)
    trapExitM.remove(targetId)
    // Resume blocked senders: target died → send becomes silent no-op.
    if _dyingSt.blockedSends.nonEmpty then
      _dyingSt.blockedSends.foreach { (senderId, _, senderK) =>
        actors.get(senderId).foreach { ss =>
          ss.pending = _FlatMap((), senderK)
          ready.append(senderId)
        }
      }
      _dyingSt.blockedSends.clear()
    val deadPid = _Pid("", targetId)
    links.remove(targetId).foreach { linkedSet =>
      linkedSet.foreach { linkedId =>
        links.get(linkedId).foreach(_.remove(targetId))
        if trapExitM.getOrElse(linkedId, false) then
          actors.get(linkedId).foreach { st =>
            st.mailbox.offer(Exit(deadPid, reason))
            tryWakeBlocked(linkedId)
          }
        else
          killActor(linkedId, reason)
      }
    }
    monitors.remove(targetId).foreach { monMap =>
      monMap.foreach { (monRef, observerId) =>
        actors.get(observerId).foreach { st =>
          st.mailbox.offer(Down(monRef, deadPid, reason))
          tryWakeBlocked(observerId)
        }
      }
    }

  def handleActorOp(id: Long, state: _ActorState, op: String, args: List[Any], k: Any => Any): Either[Unit, Any] = op match
    case "spawn" =>
      val thunk = args(0).asInstanceOf[() => Any]
      val childId = spawnActor(thunk)
      Right(k(_Pid(_localNodeId, childId)))
    case "spawnLink" =>
      val thunk = args(0).asInstanceOf[() => Any]
      val childId = spawnActor(thunk)
      // Atomic bidirectional link
      links.getOrElseUpdate(id,      scala.collection.mutable.Set.empty) += childId
      links.getOrElseUpdate(childId, scala.collection.mutable.Set.empty) += id
      Right(k(_Pid(_localNodeId, childId)))
    case "spawnBounded" =>
      val cap = args(0) match
        case n: Int  => n
        case n: Long => n.toInt
        case _       => 0
      val ov = args(1) match
        case s: String => s
        case m: scala.collection.immutable.Map[?, ?] =>
          m.asInstanceOf[Map[String, Any]].getOrElse("_type", "DropNewest").toString
        case _ => "DropNewest"
      val thunk = args(2).asInstanceOf[() => Any]
      val childId = spawnActor(thunk, cap, ov)
      Right(k(_Pid(_localNodeId, childId)))
    case "self" => Right(k(_Pid(_localNodeId, id)))
    case "processInfo" =>
      args(0) match
        case _Pid(_, targetId) =>
          actors.get(targetId) match
            case None => Right(k(None))
            case Some(ts) =>
              val lnks = links.get(targetId).map(_.toList.map(lid => _Pid("", lid))).getOrElse(List.empty)
              val status = if ts.blocked != null then "blocked" else "running"
              val info = Map("_type" -> "ProcessInfo", "mailboxSize" -> ts.mailbox.size,
                             "links" -> lnks, "status" -> status)
              Right(k(Some(info)))
        case _ => Right(k(None))
    case "send" =>
      args(0) match
        case _Pid(pidNode, targetId) =>
          if pidNode.nonEmpty && pidNode != _localNodeId then
            // Remote send — serialize and enqueue to peer channel
            Option(_peerChannels.get(pidNode)).foreach { sendFn =>
              val body = _serializeValue(args(1))
              sendFn(_mkMsgEnv(_localNodeId, id, pidNode, targetId, body))
            }
          else
            actors.get(targetId) match
              case Some(ts) =>
                val _delivered =
                  if ts.cap > 0 && ts.mailbox.size >= ts.cap then
                    ts.overflow match
                      case "DropOldest" =>
                        ts.mailbox.poll(); ts.mailbox.offer(args(1)); true
                      case "DropNewest" => false
                      case "Fail" =>
                        killActor(id, "mailbox_overflow")
                        return if actors.contains(id) then Right(k(())) else Left(())
                      case "Block" =>
                        ts.blockedSends.append((id, args(1), k))
                        return Left(())
                      case _ => ts.mailbox.offer(args(1)); true
                  else { ts.mailbox.offer(args(1)); true }
                if _delivered && ts.blocked != null then
                  val b = ts.blocked
                  tryDeliver(ts, b._1, b._4) match
                    case Some(c) =>
                      ts.pending = _FlatMap(c, b._2)
                      ts.blocked = null
                      ready.append(targetId)
                    case None => ()
              case None => ()
        case _ => ()
      Right(k(()))
    case "exit" =>
      args(0) match
        case _Pid(_, targetId) => killActor(targetId, args(1))
        case _                 => ()
      if actors.contains(id) then Right(k(())) else Left(())
    case "receive" =>
      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
      tryDeliver(state, matcher, false) match
        case Some(c) => Right(_FlatMap(c, k))
        case None =>
          state.blocked = (matcher, k, None, false)
          Left(())
    case "receive_t" =>
      val matcher = _receiveSpecs.get(args(0).asInstanceOf[Long])
      val ms = args(1) match
        case n: Int  => n.toLong
        case n: Long => n
        case _       => 0L
      tryDeliver(state, matcher, true) match
        case Some(c) => Right(_FlatMap(c, k))
        case None =>
          state.blocked = (matcher, k, Some(System.currentTimeMillis() + ms), true)
          Left(())
    // ── v1.6 Phase 2 — supervision ─────────────────────────────────────
    case "link" =>
      args(0) match
        case _Pid(nid, targetId) =>
          if nid.nonEmpty && nid != _localNodeId then
            if _peerChannels.containsKey(nid) then
              _remoteLinks.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, targetId))
            else
              if trapExitM.getOrElse(id, false) then
                actors.get(id).foreach(_.mailbox.offer(Exit(_Pid(nid, targetId), noproc)))
              else killActor(id, noproc)
          else if actors.contains(targetId) then
            links.getOrElseUpdate(id,       scala.collection.mutable.Set.empty) += targetId
            links.getOrElseUpdate(targetId, scala.collection.mutable.Set.empty) += id
          else
            if trapExitM.getOrElse(id, false) then
              actors.get(id).foreach(_.mailbox.offer(Exit(_Pid("", targetId), noproc)))
            else
              killActor(id, noproc)
        case _ => ()
      if actors.contains(id) then Right(k(())) else Left(())
    case "monitor" =>
      args(0) match
        case _Pid(nid, targetId) =>
          val monRef = nextMonRef; nextMonRef += 1
          if nid.nonEmpty && nid != _localNodeId then
            if _peerChannels.containsKey(nid) then
              _remoteMonitors.computeIfAbsent(nid, _ => new java.util.concurrent.CopyOnWriteArrayList()).add((id, monRef, targetId))
            else
              actors.get(id).foreach { st =>
                st.mailbox.offer(Down(monRef, _Pid(nid, targetId), "noconnection"))
                tryWakeBlocked(id)
              }
            Right(k(monRef))
          else if actors.contains(targetId) then
            monitors.getOrElseUpdate(targetId, scala.collection.mutable.Map.empty)(monRef) = id
            Right(k(monRef))
          else
            actors.get(id).foreach { st =>
              st.mailbox.offer(Down(monRef, _Pid("", targetId), noproc))
              tryWakeBlocked(id)
            }
            Right(k(monRef))
        case _ => Right(k(-1L))
    case "demonitor" =>
      val monRef = args(0).asInstanceOf[Long]
      monitors.foreachEntry((_, m) => m.remove(monRef))
      Right(k(()))
    case "trapExit" =>
      trapExitM(id) = args(0) match
        case b: Boolean => b
        case _          => args(0) == true
      Right(k(()))
    // ── Phase 3 — distributed ────────────────────────────────────────────
    case "startNode" =>
      _localNodeId  = args(0).toString
      _localNodeUrl = if args.length > 1 then args(1).toString else ""
      // Register /_ssc-actors WS route for inbound peer connections.
      // v1.23 — the blocking handshake + recv loop is wrapped in a
      // virtual thread so the WS server's single-thread dispatch
      // executor returns immediately and stays free to process the
      // next peer.  Without this the FIRST inbound peer's recv loop
      // monopolises the executor and subsequent handshakes stall
      // in the queue — fragmented clusters where every node only
      // sees the seed.
      // protocols: echo the `ssc-actors-v1` subprotocol the peer clients
      // dial with (both JS-codegen `connectNode` and JVM-codegen offer only
      // v1). Without it the WS upgrade emits no `Sec-WebSocket-Protocol`
      // header and a spec-compliant `ws` client (JS peer) rejects the
      // connection ("Server sent no subprotocol") → no Bully convergence.
      onWebSocket("/_ssc-actors", protocols = List("ssc-actors-v1")) { ws =>
        Thread.ofVirtual().start { () =>
          def wsSend(t: String): Unit = ws.send(t)
          def wsRecv(): String | Null  = ws.recv() match
            case Some(s) => s
            case None    => null
          val first = wsRecv()
          if first != null then
            val pnId = _parseNodeId(first)
            if pnId.nonEmpty then
              wsSend("{\"nodeId\":" + _jstr(_localNodeId) + "}")
              _peerChannels.put(pnId, wsSend)
              _peerLastPong.put(pnId, System.currentTimeMillis())
              _fireClusterEvent("NodeJoined", pnId)
              _sendConfigSnapshot(wsSend)
              _sendDrainState(wsSend)
              _sendMetricSnapshot(wsSend)
              val hbThread = Thread.ofVirtual().start { () =>
                try
                  while _peerChannels.containsKey(pnId) do
                    Thread.sleep(_peerHeartbeatIntervalMs)
                    if _peerChannels.containsKey(pnId) then
                      val age = System.currentTimeMillis() - _peerLastPong.getOrDefault(pnId, 0L)
                      if age > _peerHeartbeatDeadAfterMs then _peerChannels.remove(pnId)
                      else try _peerChannels.get(pnId)("{\"t\":\"ping\"}") catch case _: Throwable => ()
                catch case _: InterruptedException => ()
              }
              var running = true
              while running do
                val msg = wsRecv()
                if msg == null then running = false else _dispatchPeerEnv(pnId, msg)
              hbThread.interrupt()
              _peerChannels.remove(pnId)
              _peerLastPong.remove(pnId)
              _peerUrls.remove(pnId)
              _peerPongHist.remove(pnId)
              _peerPhiViews.remove(pnId)
              _drainingPeers.remove(pnId)
              _clusterMetrics.forEach { (_, inner) => inner.remove(pnId) }
              _nodeDownQueue.offer(pnId)
              _fireClusterEvent("NodeLeft", pnId, "disconnect")
              if _currentLeader.compareAndSet(pnId, "") then
                _fireLeaderEvent("LeaderLost", pnId)
                if _autoReelect then _startElection()
        }
      }
      // v1.23 — operational HTTP endpoints under /_ssc-cluster/* so
      // ops tooling (`ssc cluster status / drain / events / step-down
      // / metrics-prom`) can talk to codegen-built nodes just like
      // interpreter nodes.  Idempotent — repeated startNode calls
      // are no-ops for the route table.
      _registerClusterStatusRoute()
      _registerClusterDrainRoute()
      _registerClusterEventsRoute()
      _registerClusterStepDownRoute()
      _registerClusterMetricsPromRoute()
      _registerClusterHandlersRoute()
      Right(k(()))
    case "connectNode" =>
      val url = args(0).toString
      val tok = if args.length > 1 then args(1).toString else ""
      _connectPeer(url, tok)
      Right(k(()))
    case "joinCluster" =>
      _joinMode  = true
      _joinToken = if args.length > 1 then args(1).toString else ""
      val seeds = args(0) match
        case lst: List[?] => lst.collect { case s: String => s }
        case _            => Nil
      seeds.foreach(u => _connectPeer(u, _joinToken))
      Right(k(()))
    case "register" =>
      val name = args(0).toString
      val localId = args(1) match { case _Pid(_, lid) => lid; case _ => id }
      _nodeRegistry.put(name, localId)
      Right(k(()))
    case "whereis" =>
      val name = args(0).toString
      val result =
        if _nodeRegistry.containsKey(name) && actors.contains(_nodeRegistry.get(name)) then
          Some(_Pid(_localNodeId, _nodeRegistry.get(name)))
        else
          None
      Right(k(result))
    // v1.6.x — cluster-wide registry
    case "globalRegister" =>
      val grName    = args(0).toString
      val grPidRaw  = args(1).asInstanceOf[_Pid]
      // v1.23 — local-spawn Pids carry an empty nodeId.  Stamp the
      // local node identity onto the registered Pid so cross-node
      // lookups can route back here; without this the broadcast
      // payload's `nodeId` is "" and remote nodes silently drop
      // every cross-node send to this name.
      val grNid     = if grPidRaw.nodeId.nonEmpty then grPidRaw.nodeId else _localNodeId
      val grPid     = _Pid(grNid, grPidRaw.localId)
      _globalRegistry.put(grName, grPid)
      val payload = "{\"t\":\"global_reg\",\"name\":" + _jstr(grName) + ",\"nodeId\":" + _jstr(grNid) + ",\"localId\":" + _jstr(grPid.localId.toString) + "}"
      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(()))
    case "globalWhereis" =>
      val gwName = args(0).toString
      val result = Option(_globalRegistry.get(gwName))
      Right(k(result))
    // v1.23 — cluster visibility
    case "clusterMembers" =>
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      _peerChannels.keySet().forEach(k0 => buf += k0)
      Right(k(buf.toList))
    case "subscribeClusterEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !_clusterEventSubs.contains(boxed) then _clusterEventSubs.add(boxed)
      Right(k(()))
    // v1.23 — phi-accrual failure detector
    case "phiOf" =>
      Right(k(_computePhi(args(0).toString)))
    case "isSuspect" =>
      val thr = args(1) match
        case d: Double => d
        case l: Long   => l.toDouble
        case i: Int    => i.toDouble
        case _         => 8.0
      Right(k(_computePhi(args(0).toString) >= thr))
    // v1.23 — local node identity
    case "selfNode" =>
      Right(k(_localNodeId))
    // v1.23 — cluster health (phi vector for connected peers)
    case "clusterHealth" =>
      val m = scala.collection.mutable.Map.empty[String, Double]
      _peerChannels.keySet().forEach(k0 => m(k0) = _computePhi(k0))
      Right(k(m.toMap))
    // v1.23 — cluster-wide FD: broadcast phi vector to peers.
    case "broadcastHealth" =>
      val sb = new StringBuilder("{\"t\":\"phi_vector\",\"from\":")
      sb.append(_jstr(_localNodeId)).append(",\"view\":[")
      var first = true
      _peerChannels.keySet().forEach { nid =>
        val phi = _computePhi(nid)
        if !phi.isInfinite && !phi.isNaN then
          if !first then sb.append(',')
          sb.append("[").append(_jstr(nid)).append(',').append(phi).append(']')
          first = false
      }
      sb.append("]}")
      val payload = sb.toString
      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(()))
    // v1.23 — cluster-wide FD: majority vote across peer views.
    case "clusterIsDown" =>
      val target = args(0).toString
      val thr    = args(1) match
        case d: Double => d
        case l: Long   => l.toDouble
        case i: Int    => i.toDouble
        case _         => 8.0
      var votes = 0
      var total = 0
      if _peerChannels.containsKey(target) then
        total += 1
        if _computePhi(target) >= thr then votes += 1
      _peerPhiViews.forEach { (peerNid, peerView) =>
        if peerNid != target then
          val p = peerView.get(target)
          if p != null then
            total += 1
            if p.doubleValue() >= thr then votes += 1
      }
      val majority = (total + 1) / 2
      Right(k(total > 0 && votes >= majority))
    // v1.23 — leader election (Bully or Raft, picked by _leaderProtocol)
    case "electLeader" =>
      _leaderProtocol.get() match
        case "raft" => _startRaftElection()
        case _      => _startElection()
      Right(k(()))
    case "currentLeader" =>
      _leaderProtocol.get() match
        case "raft"  => Right(k(_raftLeaderId))
        case "coord" =>
          val holderFn = _coordHolderFn
          val held: Option[String] =
            if holderFn != null then
              try holderFn.asInstanceOf[() => Option[String]]()
              catch case _: Throwable => None
            else None
          Right(k(held.getOrElse("")))
        case _       => Right(k(_currentLeader.get()))
    case "subscribeLeaderEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !_leaderEventSubs.contains(boxed) then _leaderEventSubs.add(boxed)
      Right(k(()))
    case "setAutoReelect" =>
      _autoReelect = args(0) match
        case b: Boolean => b
        case _          => false
      Right(k(()))
    // v1.23 — protocol switch + history (cluster-raft.md §6).
    case "useRaftLeaderElection" =>
      _leaderProtocol.set("raft")
      _raftLoad()
      _raftState       = "follower"
      _raftElectionDue = System.currentTimeMillis() + _raftRandTimeout
      _ensureRaftTickThread()
      Right(k(()))
    case "useExternalCoordinator" =>
      _leaderProtocol.set("coord")
      if args.length >= 4 then
        _coordAcquireFn = args(0).asInstanceOf[AnyRef]
        _coordRenewFn   = args(1).asInstanceOf[AnyRef]
        _coordReleaseFn = args(2).asInstanceOf[AnyRef]
        _coordHolderFn  = args(3).asInstanceOf[AnyRef]
        // Try once synchronously so callers don't wait a tick.
        val got = try _coordAcquireFn.asInstanceOf[(String, Long) => Boolean](_localNodeId, _COORD_LEASE_TIMEOUT_MS)
                  catch case _: Throwable => false
        if got then
          _coordIsLeader = true
          val prev = _currentLeader.getAndSet(_localNodeId)
          if prev != _localNodeId then
            _fireLeaderEvent("LeaderElected", _localNodeId)
            _recordLeaderHist(_localNodeId)
        _ensureCoordTickThread()
      Right(k(()))
    case "leaderProtocol" =>
      Right(k(_leaderProtocol.get()))
    case "leaderHistory" =>
      val buf = scala.collection.mutable.ListBuffer.empty[(Long, String, Long)]
      _leaderHist.iterator().forEachRemaining(e => buf += e)
      Right(k(buf.toList))
    // v1.23 — auto-reconnect policy
    case "setReconnectPolicy" =>
      def _argL(i: Int): Long = if args.length > i then args(i) match
        case l: Long   => l
        case i2: Int   => i2.toLong
        case d: Double => d.toLong
        case _         => 0L
      else 0L
      _reconnectInitialMs = _argL(0).max(0L)
      _reconnectMaxMs     = _argL(1).max(_reconnectInitialMs)
      // v1.23 — optional 3rd arg: total wall-clock retry budget (ms)
      // per URL; 0 = no cap (retry forever).
      _reconnectGiveUpMs  = _argL(2).max(0L)
      Right(k(()))
    // v1.23 — heartbeat cadence + dead-after threshold
    case "setHeartbeatTimeout" =>
      val iv = args(0) match
        case l: Long   => l
        case i: Int    => i.toLong
        case d: Double => d.toLong
        case _         => 30000L
      val dead = args(1) match
        case l: Long   => l
        case i: Int    => i.toLong
        case d: Double => d.toLong
        case _         => 40000L
      _peerHeartbeatIntervalMs  = iv.max(1L)
      _peerHeartbeatDeadAfterMs = dead.max(_peerHeartbeatIntervalMs)
      Right(k(()))
    // v1.23 — cluster endpoint shared-secret
    case "setClusterAuthToken" =>
      _clusterAuthToken = args.headOption.map(_.toString).getOrElse("")
      Right(k(()))
    // v1.23 — quorum-aware Bully threshold
    case "setQuorumSize" =>
      val n = args(0) match
        case l: Long   => l
        case i: Int    => i.toLong
        case d: Double => d.toLong
        case _         => 0L
      _quorumSize = n.max(0L)
      Right(k(()))
    // v1.23 — periodic gossip re-discovery: ask every connected peer
    // for their peer-URL list.  Replies come back via the existing
    // `peers_resp` handler and feed `_connectPeer` for unknown URLs.
    case "requestGossip" =>
      val payload = "{\"t\":\"peers_req\",\"from\":" + _jstr(_localNodeId) + "}"
      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(()))
    // v1.23 — cluster configuration distribution.
    case "clusterConfigSet" =>
      val key   = args(0).toString
      val value = args(1).toString
      val ts    = System.currentTimeMillis()
      val orig  = _localNodeId
      _applyConfigUpdate(key, value, ts, orig)
      val payload = "{\"t\":\"config_set\",\"key\":" + _jstr(key) +
                    ",\"value\":" + _jstr(value) +
                    ",\"ts\":" + ts.toString +
                    ",\"origin\":" + _jstr(orig) + "}"
      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(()))
    case "clusterConfigGet" =>
      val key = args(0).toString
      val entry = _clusterConfig.get(key)
      val result: Any = if entry == null then None else Some(entry._1)
      Right(k(result))
    case "clusterConfigKeys" =>
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      _clusterConfig.keySet().forEach(k0 => buf += k0)
      Right(k(buf.toList))
    case "subscribeConfigEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !_configEventSubs.contains(boxed) then _configEventSubs.add(boxed)
      Right(k(()))
    // v1.23 — drain / rolling-restart
    case "setDraining" =>
      val b = args(0) match
        case bb: Boolean => bb
        case _           => false
      val prev = _isDrainingSelf.getAndSet(b)
      if prev != b then
        val payload = "{\"t\":\"drain\",\"from\":" + _jstr(_localNodeId) +
                      ",\"draining\":" + b.toString + "}"
        _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
        _fireDrainEvent(_localNodeId, b)
        // v1.23 — drain-aware step-down: if we just flipped to
        // draining and we're the leader, release leadership.
        if b then _stepDownIfLeader()
      Right(k(()))
    case "isDraining" =>
      Right(k(_isDrainingSelf.get()))
    case "drainingPeers" =>
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      _drainingPeers.forEach { (nid, v) => if v != null && v.booleanValue() then buf += nid }
      Right(k(buf.toList))
    case "subscribeDrainEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !_drainEventSubs.contains(boxed) then _drainEventSubs.add(boxed)
      Right(k(()))
    // v1.23 — cluster metrics aggregation
    case "clusterMetricSet" =>
      val name = args(0).toString
      val value = args(1) match
        case d: Double => d
        case l: Long   => l.toDouble
        case i: Int    => i.toDouble
        case _         => 0.0
      _applyMetricUpdate(name, _localNodeId, value)
      val payload = "{\"t\":\"metric\",\"from\":" + _jstr(_localNodeId) +
                    ",\"name\":" + _jstr(name) +
                    ",\"value\":" + value.toString + "}"
      _peerChannels.forEach { (_, send) => try send(payload) catch case _: Throwable => () }
      Right(k(()))
    case "clusterMetricGet" =>
      val name = args(0).toString
      val inner = _clusterMetrics.get(name)
      val m = scala.collection.mutable.Map.empty[String, Double]
      if inner != null then
        inner.forEach { (nid, v) => m(nid) = v.doubleValue() }
      Right(k(m.toMap))
    case "clusterMetricSum" =>
      val name = args(0).toString
      val inner = _clusterMetrics.get(name)
      var sum = 0.0
      if inner != null then
        inner.forEach { (_, v) => sum += v.doubleValue() }
      Right(k(sum))
    case "clusterMetricNames" =>
      val buf = scala.collection.mutable.ListBuffer.empty[String]
      _clusterMetrics.keySet().forEach(s => buf += s)
      Right(k(buf.toList))
    case "subscribeMetricEvents" =>
      val boxed = java.lang.Long.valueOf(id)
      if !_metricEventSubs.contains(boxed) then _metricEventSubs.add(boxed)
      Right(k(()))
    // v1.6.x — scheduled sends
    case "sendAfter" =>
      val delayMs  = args(0).asInstanceOf[Long]
      val targetId = args(1).asInstanceOf[_Pid].localId
      val msg      = args(2)
      val fireAt   = System.currentTimeMillis() + delayMs
      val ref      = _nextTimerId; _nextTimerId += 1
      _timers(ref) = (fireAt, None, targetId, msg)
      Right(k(ref))
    case "sendInterval" =>
      val periodMs = args(0).asInstanceOf[Long]
      val targetId = args(1).asInstanceOf[_Pid].localId
      val msg      = args(2)
      val fireAt   = System.currentTimeMillis() + periodMs
      val ref      = _nextTimerId; _nextTimerId += 1
      _timers(ref) = (fireAt, Some(periodMs), targetId, msg)
      Right(k(ref))
    case "cancelTimer" =>
      _timers.remove(args(0).asInstanceOf[Long])
      Right(k(()))
    case other => sys.error("Unknown Actor op: " + other)

  // Synchronous fallback handler for non-Actor effects performed
  // inside an actor body — dep code like std/mapreduce/distributed
  // calls `Random.uuid()` while running under `runActors`, and the
  // value-producing primitives (Random.*, Clock.now/nowIso) can be
  // evaluated in-place without a continuation.  Unsupported effects
  // still throw with a clear message.  Blocking ops (Clock.sleep)
  // intentionally don't appear here — they'd freeze the single
  // actor scheduler thread.
  lazy val _actorRng = new java.util.Random()
  def _actorFallback(eff: String, op: String, args: List[Any]): Any =
    (eff, op) match
      case ("Random", "uuid") =>
        val bytes = new Array[Byte](16)
        _actorRng.nextBytes(bytes)
        bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
        bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
        def hex(b: Byte) = f"${b & 0xff}%02x"
        val u = bytes.map(hex).mkString
        s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}"
      case ("Random", "nextInt") =>
        val n = args(0) match { case x: Int => x; case x: Long => x.toInt; case _ => 1 }
        _actorRng.nextInt(if n > 0 then n else 1)
      case ("Random", "nextDouble") => _actorRng.nextDouble()
      case ("Random", "pick") =>
        val xs = args(0).asInstanceOf[List[Any]]
        xs(_actorRng.nextInt(xs.size))
      case ("Clock", "now")    => java.lang.System.currentTimeMillis()
      case ("Clock", "nowIso") =>
        java.time.format.DateTimeFormatter.ISO_INSTANT
          .format(java.time.Instant.ofEpochMilli(java.lang.System.currentTimeMillis()))
      case _ =>
        throw new RuntimeException("Unhandled effect inside actor: " + eff + "." + op)

  def stepActor(id: Long, initial: Any): Unit =
    var current: Any = initial
    while true do
      current match
        case _Perform("Actor", op, args) =>
          handleActorOp(id, actors(id), op, args, (v: Any) => v) match
            case Right(next) => current = next
            case Left(_)     => return
        case _Perform(eff, op, args) =>
          // Plain perform with no continuation: compute via
          // fallback and use the value as the final result of
          // this actor step.
          current = _actorFallback(eff, op, args)
        case _FlatMap(sub, f) => sub match
          case _Perform("Actor", op, args) =>
            handleActorOp(id, actors(id), op, args, f.asInstanceOf[Any => Any]) match
              case Right(next) => current = next
              case Left(_)     => return
          case _Perform(eff, op, args) =>
            val v = _actorFallback(eff, op, args)
            current = f.asInstanceOf[Any => Any](v)
          case _FlatMap(s2, g) =>
            current = _FlatMap(s2,
              (x: Any) => _FlatMap(g.asInstanceOf[Any => Any](x), f))
          case v =>
            current = f.asInstanceOf[Any => Any](v)
        case v =>
          if id == rootId then rootResult = v
          // Fire monitors with reason "normal" on natural completion.
          val myPid = _Pid(_localNodeId, id)
          monitors.remove(id).foreach { monMap =>
            monMap.foreach { (monRef, observerId) =>
              actors.get(observerId).foreach { st =>
                st.mailbox.offer(Down(monRef, myPid, "normal"))
                tryWakeBlocked(observerId)
              }
            }
          }
          links.remove(id).foreach { linkedSet =>
            linkedSet.foreach { linkedId =>
              links.get(linkedId).foreach(_.remove(id))
            }
          }
          actors.remove(id)
          return

  def _mkMsgEnv(fromNode: String, fromId: Long, toNode: String, toId: Long, body: String): String =
    "{\"t\":\"msg\",\"to\":{\"nodeId\":" + _jstr(toNode) + ",\"localId\":" + toId +
    "},\"from\":{\"nodeId\":" + _jstr(fromNode) + ",\"localId\":" + fromId +
    "},\"body\":" + body + "}"

  def _serializeValue(v: Any): String = v match
    case n: Long    => "{\"$t\":\"i\",\"v\":" + n + "}"
    case n: Int     => "{\"$t\":\"i\",\"v\":" + n + "}"
    case d: Double  => "{\"$t\":\"d\",\"v\":" + d + "}"
    case s: String  => "{\"$t\":\"s\",\"v\":" + _jstr(s) + "}"
    case b: Boolean => "{\"$t\":\"b\",\"v\":" + b + "}"
    case ()         => "{\"$t\":\"u\"}"
    case _Pid(nId, lId) => "{\"$t\":\"pid\",\"n\":" + _jstr(nId) + ",\"id\":" + lId + "}"
    case xs: List[?] => "{\"$t\":\"l\",\"v\":[" + xs.map(_serializeValue).mkString(",") + "]}"
    case _          => "{\"$t\":\"s\",\"v\":" + _jstr(v.toString) + "}"

  val _isDistributed = _localNodeId.nonEmpty || !_peerChannels.isEmpty

  while ready.nonEmpty ||
        !_nodeDownQueue.isEmpty ||
        !_clusterEventQueue.isEmpty ||
        !_leaderEventQueue.isEmpty ||
        !_configEventQueue.isEmpty ||
        !_drainEventQueue.isEmpty ||
        !_metricEventQueue.isEmpty ||
        _electionInProgress ||
        _timers.nonEmpty ||
        actors.exists { (_, st) => st != null && st.blocked != null && st.blocked._3.isDefined } ||
        (_isDistributed && actors.nonEmpty && actors.exists { (_, st) => st != null && st.blocked != null })
  do
    // Drain remote inbox
    while !_remoteInbox.isEmpty do
      val (targetId, msg) = _remoteInbox.poll()
      actors.get(targetId).foreach { ts =>
        ts.mailbox.offer(msg)
        tryWakeBlocked(targetId)
      }
    // Drain node-down notifications
    while !_nodeDownQueue.isEmpty do
      _fireNodeDown(_nodeDownQueue.poll())
    // v1.23 — deliver cluster events to subscribers.
    while !_clusterEventQueue.isEmpty do
      val (_tag, _nid, _reason) = _clusterEventQueue.poll()
      val _msg: Any =
        if _tag == "NodeJoined" then NodeJoined(_nid)
        else NodeLeft(_nid, _reason)
      val _it = _clusterEventSubs.iterator
      while _it.hasNext do
        val _aid = _it.next().longValue()
        actors.get(_aid).foreach { ts =>
          ts.mailbox.offer(_msg)
          tryWakeBlocked(_aid)
        }
    // v1.23 — Bully election timeout: claim self if no higher-id peer
    // responded with `alive` within the window.
    if _electionInProgress && System.currentTimeMillis() - _electionStartedAt >= _ELECTION_TIMEOUT_MS then
      _electionInProgress = false
      // v1.23 — quorum gate: same as `_startElection.higher.isEmpty`
      // branch — even though no higher peer responded, decline to
      // self-claim when below quorum.
      if !_gotAliveResponse && _hasQuorum then
        val _prev = _currentLeader.getAndSet(_localNodeId)
        _broadcastCoordinator()
        if _prev != _localNodeId then
          _fireLeaderEvent("LeaderElected", _localNodeId)
          _recordLeaderHist(_localNodeId)
    // v1.23 — deliver leader events to subscribers.
    while !_leaderEventQueue.isEmpty do
      val (_tag, _lid) = _leaderEventQueue.poll()
      val _msg: Any =
        if _tag == "LeaderElected" then LeaderElected(_lid)
        else LeaderLost(_lid)
      val _it = _leaderEventSubs.iterator
      while _it.hasNext do
        val _aid = _it.next().longValue()
        actors.get(_aid).foreach { ts =>
          ts.mailbox.offer(_msg)
          tryWakeBlocked(_aid)
        }
    // v1.23 — deliver config-change events to subscribers.
    while !_configEventQueue.isEmpty do
      val (_key, _val) = _configEventQueue.poll()
      val _msg: Any = ConfigChanged(_key, _val)
      val _it = _configEventSubs.iterator
      while _it.hasNext do
        val _aid = _it.next().longValue()
        actors.get(_aid).foreach { ts =>
          ts.mailbox.offer(_msg)
          tryWakeBlocked(_aid)
        }
    // v1.23 — deliver drain-state events to subscribers.
    while !_drainEventQueue.isEmpty do
      val (_nid, _drn) = _drainEventQueue.poll()
      val _msg: Any = DrainStateChanged(_nid, _drn)
      val _it = _drainEventSubs.iterator
      while _it.hasNext do
        val _aid = _it.next().longValue()
        actors.get(_aid).foreach { ts =>
          ts.mailbox.offer(_msg)
          tryWakeBlocked(_aid)
        }
    // v1.23 — deliver metric events to subscribers.
    while !_metricEventQueue.isEmpty do
      val (_nm, _nid, _val) = _metricEventQueue.poll()
      val _msg: Any = MetricChanged(_nm, _nid, _val)
      val _it = _metricEventSubs.iterator
      while _it.hasNext do
        val _aid = _it.next().longValue()
        actors.get(_aid).foreach { ts =>
          ts.mailbox.offer(_msg)
          tryWakeBlocked(_aid)
        }
    // Fire scheduled sends whose deadline has passed.
    if _timers.nonEmpty then
      val _nowMs = System.currentTimeMillis()
      val _firedRefs = _timers.collect { case (r, (fa, _, _, _)) if _nowMs >= fa => r }.toList
      for _ref <- _firedRefs; _entry <- _timers.get(_ref) do
        val (fireAt, period, targetId, msg) = _entry
        actors.get(targetId).foreach { ts =>
          ts.mailbox.offer(msg)
          tryWakeBlocked(targetId)
        }
        period match
          case Some(p) => _timers(_ref) = (fireAt + p, period, targetId, msg)
          case None    => _timers.remove(_ref)
    if ready.isEmpty then
      val _blockDeadline = actors.iterator.collect {
        case (aid, st) if st != null && st.blocked != null && st.blocked._3.isDefined =>
          (aid, st.blocked._3.get)
      }.toList.minByOption(_._2)
      val _timerDeadline = if _timers.isEmpty then None else Some(_timers.values.map(_._1).min)
      val _sleepUntil = List(_blockDeadline.map(_._2), _timerDeadline).flatten.minOption
      val _sleepFor   = _sleepUntil.map(_ - System.currentTimeMillis()).getOrElse(if _isDistributed then 30L else Long.MaxValue)
      if _sleepFor > 0 then
        try Thread.sleep(_sleepFor)
        catch case _: InterruptedException => ()
      _blockDeadline match
        case Some((aid, deadline)) if System.currentTimeMillis() >= deadline =>
          val st = actors(aid)
          val (_, k, _, _) = st.blocked
          st.pending = k(None)
          st.blocked = null
          ready.append(aid)
        case _ => ()
    else
      val id = ready.removeHead()
      actors.get(id).foreach { st =>
        if st.pending != null then
          val initial = st.pending
          st.pending = null
          stepActor(id, initial)
      }

  // v1.23 — graceful cluster shutdown: release the coord lease if we
  // hold it, so the next leader can claim immediately instead of
  // waiting for the TTL.  Raft's final (term, votedFor) is already
  // on disk via _raftPersist().
  if _leaderProtocol.get() == "coord" && _coordIsLeader then
    val rel = _coordReleaseFn
    if rel != null then
      try rel.asInstanceOf[String => Unit](_localNodeId)
      catch case _: Throwable => ()
    _coordIsLeader = false
  // Interrupt tick threads so they don't leak across reused JVMs
  // (each `_runActors` call has its own closure-captured state, so
  // an orphan thread would otherwise loop forever on stale refs).
  val rtt = _raftTickThread.getAndSet(null);   if rtt != null then rtt.interrupt()
  val ctt = _coordTickThread.getAndSet(null);  if ctt != null then ctt.interrupt()
  rootResult

// ── v1.4 Logger effect ─────────────────────────────────────────────────────
//
// Logger.{info,warn,error,debug}  → _perform("Logger", op, msg)
// runLogger { body }              — "[LEVEL] msg" to stdout
// runLoggerJson { body }          — {"level":"…","msg":"…"} newline-JSON
// runLoggerToList { body }        — (result, List[(level, msg)])

private def _loggerJsonStr(s: String): String =
  val sb = new StringBuilder("\"")
  s.foreach {
    case '"'  => sb.append("\\\"")
    case '\\' => sb.append("\\\\")
    case '\n' => sb.append("\\n")
    case '\r' => sb.append("\\r")
    case '\t' => sb.append("\\t")
    case c    => sb.append(c)
  }
  sb.append('"').toString

def runLogger(bodyThunk: () => Any): Any =
  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
  _handle(bodyThunk, ops, Map(
    "Logger.info"  -> { (args: List[Any]) => println(s"[INFO] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
    "Logger.warn"  -> { (args: List[Any]) => println(s"[WARN] ${args(0)}");  args.last.asInstanceOf[Any => Any](()) },
    "Logger.error" -> { (args: List[Any]) => println(s"[ERROR] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
    "Logger.debug" -> { (args: List[Any]) => println(s"[DEBUG] ${args(0)}"); args.last.asInstanceOf[Any => Any](()) },
  ))

def runLoggerJson(bodyThunk: () => Any): Any =
  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
  def fmt(level: String)(args: List[Any]): Any =
    println(s"{\"level\":\"$level\",\"msg\":${_loggerJsonStr(args(0).toString)}}")
    args.last.asInstanceOf[Any => Any](())
  _handle(bodyThunk, ops, Map(
    "Logger.info"  -> fmt("info"),
    "Logger.warn"  -> fmt("warn"),
    "Logger.error" -> fmt("error"),
    "Logger.debug" -> fmt("debug"),
  ))

def runLoggerToList(bodyThunk: () => Any): Any =
  val log = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
  def writeLog(level: String)(args: List[Any]): Any =
    log += (level -> args(0).toString)
    args.last.asInstanceOf[Any => Any](())
  val ops = Set("Logger.info", "Logger.warn", "Logger.error", "Logger.debug")
  val result = _handle(bodyThunk, ops, Map(
    "Logger.info"  -> writeLog("info"),
    "Logger.warn"  -> writeLog("warn"),
    "Logger.error" -> writeLog("error"),
    "Logger.debug" -> writeLog("debug"),
  ))
  (result, log.toList)

// ── v1.4 Random effect ─────────────────────────────────────────────────────
//
// Random.{nextInt,nextDouble,uuid,pick}  → _perform("Random", op, args*)
// runRandom { body }          — java.util.Random (non-deterministic)
// runRandomSeeded(seed)(body) — deterministic seeded java.util.Random

object Random:
  def nextInt(n: Any): Any  = _perform("Random", "nextInt",    n)
  def nextDouble(): Any     = _perform("Random", "nextDouble")
  def uuid(): Any           = _perform("Random", "uuid")
  def pick(xs: Any): Any    = _perform("Random", "pick",       xs)

private def _randomHandlers(rng: java.util.Random): Map[String, List[Any] => Any] = Map(
  "Random.nextInt" -> { (args: List[Any]) =>
    val n = args(0).asInstanceOf[Int]
    args.last.asInstanceOf[Any => Any](rng.nextInt(if n > 0 then n else 1))
  },
  "Random.nextDouble" -> { (args: List[Any]) =>
    args.last.asInstanceOf[Any => Any](rng.nextDouble())
  },
  "Random.uuid" -> { (args: List[Any]) =>
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
    def hex(b: Byte) = f"${b & 0xff}%02x"
    val u = bytes.map(hex).mkString
    args.last.asInstanceOf[Any => Any](s"${u.take(8)}-${u.slice(8,12)}-${u.slice(12,16)}-${u.slice(16,20)}-${u.drop(20)}")
  },
  "Random.pick" -> { (args: List[Any]) =>
    val xs = args(0).asInstanceOf[List[Any]]
    args.last.asInstanceOf[Any => Any](xs(rng.nextInt(xs.size)))
  },
)

def runRandom(bodyThunk: () => Any): Any =
  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random()))

def runRandomSeeded(seed: Any)(bodyThunk: () => Any): Any =
  val ops = Set("Random.nextInt", "Random.nextDouble", "Random.uuid", "Random.pick")
  val s = seed match
    case n: Long => n
    case n: Int  => n.toLong
    case _       => sys.error("runRandomSeeded(seed: Long)")
  _handle(bodyThunk, ops, _randomHandlers(new java.util.Random(s)))

// ── v1.4 Clock effect ──────────────────────────────────────────────────────
//
// Clock.{now,nowIso,sleep}  → _perform("Clock", op, args*)
// runClock { body }         — real wall clock; sleep → Thread.sleep(ms)
// runClockAt(t0) { body }   — frozen at t0 ms since epoch; sleep is no-op

object Clock:
  def now(): Any          = _perform("Clock", "now")
  def nowIso(): Any       = _perform("Clock", "nowIso")
  def sleep(ms: Any): Any = _perform("Clock", "sleep", ms)

private def _clockHandlers(frozen: Option[Long]): Map[String, List[Any] => Any] =
  def nowMs()  = frozen.getOrElse(java.lang.System.currentTimeMillis())
  def nowIso() =
    java.time.format.DateTimeFormatter.ISO_INSTANT
      .format(java.time.Instant.ofEpochMilli(nowMs()))
  Map(
    "Clock.now"    -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowMs()) },
    "Clock.nowIso" -> { (args: List[Any]) => args.last.asInstanceOf[Any => Any](nowIso()) },
    "Clock.sleep"  -> { (args: List[Any]) =>
      val ms = args(0) match { case n: Long => n; case n: Int => n.toLong; case _ => 0L }
      if frozen.isEmpty && ms > 0 then Thread.sleep(ms)
      args.last.asInstanceOf[Any => Any](())
    },
  )

def runClock(bodyThunk: () => Any): Any =
  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
  _handle(bodyThunk, ops, _clockHandlers(None))

def runClockAt(t0: Any)(bodyThunk: () => Any): Any =
  val ops = Set("Clock.now", "Clock.nowIso", "Clock.sleep")
  val frozen = t0 match
    case n: Long => n
    case n: Int  => n.toLong
    case _       => sys.error("runClockAt(t0: Long)")
  _handle(bodyThunk, ops, _clockHandlers(Some(frozen)))

// ── v1.4 Env effect ────────────────────────────────────────────────────────
//
// Env.{get,set,required}  → _perform("Env", op, args*)
// runEnv { body }          — real process env; Env.set mutates local overlay
// runEnvWith(map) { body } — fixture map; Env.set mutates overlay

object Env:
  def get(key: Any): Any             = _perform("Env", "get",      key)
  def set(key: Any, value: Any): Any = _perform("Env", "set",      key, value)
  def required(key: Any): Any        = _perform("Env", "required", key)

private def _envHandlers(
  overlay: scala.collection.mutable.Map[String, String],
  useReal: Boolean
): Map[String, List[Any] => Any] =
  def lookup(k: String): Option[String] =
    overlay.get(k)
      .orElse(if useReal then Option(java.lang.System.getenv(k)).filter(_.nonEmpty) else None)
  Map(
    "Env.get" -> { (args: List[Any]) =>
      args.last.asInstanceOf[Any => Any](lookup(args(0).toString))
    },
    "Env.set" -> { (args: List[Any]) =>
      overlay(args(0).toString) = args(1).toString
      args.last.asInstanceOf[Any => Any](())
    },
    "Env.required" -> { (args: List[Any]) =>
      val k = args(0).toString
      lookup(k) match
        case Some(v) => args.last.asInstanceOf[Any => Any](v)
        case None    => sys.error(s"Env.required: key '$k' not found in environment")
    },
  )

def runEnv(bodyThunk: () => Any): Any =
  val ops = Set("Env.get", "Env.set", "Env.required")
  _handle(bodyThunk, ops, _envHandlers(scala.collection.mutable.Map.empty, useReal = true))

def runEnvWith(initMap: Any)(bodyThunk: () => Any): Any =
  val ops = Set("Env.get", "Env.set", "Env.required")
  val overlay = initMap match
    case m: Map[_, _] =>
      scala.collection.mutable.Map.from(m.asInstanceOf[Map[String, String]])
    case _ => sys.error("runEnvWith(map: Map[String, String])")
  _handle(bodyThunk, ops, _envHandlers(overlay, useReal = false))

// ── v1.4 Http effect ──────────────────────────────────────────────────────
//
// Http.{get,post,request}  → _perform("Http", op, args*)
// runHttp { body }              — delegates to real _httpDoRequest
// runHttpStub(routes) { body }  — test stub: url→body Map

object Http:
  def get(url: Any): Any                                   = _perform("Http", "get",     url)
  def post(url: Any, body: Any): Any                       = _perform("Http", "post",    url, body)
  def request(method: Any, url: Any, headers: Any, body: Any): Any =
    _perform("Http", "request", method, url, headers, body)

private def _httpEffectHandlers(
  routes: Option[Map[String, String]]
): Map[String, List[Any] => Any] =
  def stubResponse(url: String): Any =
    routes match
      case Some(m) if m.contains(url) =>
        Map("status" -> 200, "headers" -> Map.empty, "body" -> m(url))
      case _ =>
        Map("status" -> 404, "headers" -> Map.empty, "body" -> "")
  def mkResponse(url: String, method: String, body: String,
                 headers: Map[String, String]): Any =
    routes.fold(_httpDoRequest(method, url, body, headers))(_ => stubResponse(url))
  Map(
    "Http.get" -> { (args: List[Any]) =>
      val url = args(0).toString
      args.last.asInstanceOf[Any => Any](mkResponse(url, "GET", "", Map.empty))
    },
    "Http.post" -> { (args: List[Any]) =>
      val url = args(0).toString; val body = args(1).toString
      args.last.asInstanceOf[Any => Any](mkResponse(url, "POST", body, Map.empty))
    },
    "Http.request" -> { (args: List[Any]) =>
      val method = args(0).toString; val url = args(1).toString
      val headers = args(2) match
        case m: Map[_, _] => m.asInstanceOf[Map[String, String]]
        case _            => Map.empty[String, String]
      val body = if args.size > 3 then args(3).toString else ""
      args.last.asInstanceOf[Any => Any](mkResponse(url, method, body, headers))
    },
  )

def runHttp(bodyThunk: () => Any): Any =
  val ops = Set("Http.get", "Http.post", "Http.request")
  _handle(bodyThunk, ops, _httpEffectHandlers(None))

def runHttpStub(routes: Any)(bodyThunk: () => Any): Any =
  val ops = Set("Http.get", "Http.post", "Http.request")
  val m = routes match
    case r: Map[_, _] => r.asInstanceOf[Map[String, String]]
    case _            => sys.error("runHttpStub(routes: Map[String, String])")
  _handle(bodyThunk, ops, _httpEffectHandlers(Some(m)))

// ── v1.4 Retry effect ─────────────────────────────────────────────────────
//
// Retry.attempt(n, delayMs)(thunk)  — retries thunk up to n times on exception
// runRetry { body }        — real Thread.sleep between attempts
// runRetryNoSleep { body } — test handler: no sleep

object Retry:
  def attempt(n: Any, delayMs: Any): Any => Any =
    (thunk: Any) => _perform("Retry", "attempt", n, delayMs, thunk)

private def _retryHandlers(doSleep: Boolean): Map[String, List[Any] => Any] = Map(
  "Retry.attempt" -> { (args: List[Any]) =>
    val n = args(0) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
    val delayMs = args(1) match { case i: Int => i.toLong; case l: Long => l; case _ => 0L }
    val thunk = args(2).asInstanceOf[() => Any]
    val resume = args.last.asInstanceOf[Any => Any]
    var lastErr: Throwable = null
    var result: Any = ()
    var attempt = 0
    var succeeded = false
    while attempt <= n && !succeeded do
      try { result = thunk(); succeeded = true }
      catch case e: Throwable =>
        lastErr = e; attempt += 1
        if attempt <= n && doSleep && delayMs > 0 then Thread.sleep(delayMs)
    if succeeded then resume(result) else throw lastErr
  },
)

def runRetry(bodyThunk: () => Any): Any =
  val ops = Set("Retry.attempt")
  _handle(bodyThunk, ops, _retryHandlers(doSleep = true))

def runRetryNoSleep(bodyThunk: () => Any): Any =
  val ops = Set("Retry.attempt")
  _handle(bodyThunk, ops, _retryHandlers(doSleep = false))

// ── v1.4 Cache effect ─────────────────────────────────────────────────────
//
// Cache.memoize(key, ttlSeconds)(thunk)  — process-local TTL memoization
// runCache { body }        — uses module-level _cacheStore
// runCacheBypass { body }  — always recomputes; skips cache

private val _cacheStore = new java.util.concurrent.ConcurrentHashMap[String, (Long, Any)]()
private val _cacheBypass = ThreadLocal.withInitial[Boolean](() => false)

object Cache:
  def memoize(key: Any, ttlSeconds: Any): Any => Any =
    (thunk: Any) => _perform("Cache", "memoize", key, ttlSeconds, thunk)

private def _cacheHandlers(bypass: Boolean): Map[String, List[Any] => Any] = Map(
  "Cache.memoize" -> { (args: List[Any]) =>
    val key = args(0).toString
    val ttlMs = (args(1) match
      case i: Int  => i.toLong
      case l: Long => l
      case _       => 0L
    ) * 1000L
    val thunk = args(2).asInstanceOf[() => Any]
    val resume = args.last.asInstanceOf[Any => Any]
    if bypass || _cacheBypass.get() then resume(thunk())
    else
      val nowMs = java.lang.System.currentTimeMillis()
      val cached = Option(_cacheStore.get(key))
      cached match
        case Some((expiry, v)) if nowMs < expiry => resume(v)
        case _ =>
          val v = thunk()
          _cacheStore.put(key, (nowMs + ttlMs, v))
          resume(v)
  },
)

def runCache(bodyThunk: () => Any): Any =
  val prior = _cacheBypass.get()
  _cacheBypass.set(false)
  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(false))
  finally _cacheBypass.set(prior)

def runCacheBypass(bodyThunk: () => Any): Any =
  val prior = _cacheBypass.get()
  _cacheBypass.set(true)
  try _handle(bodyThunk, Set("Cache.memoize"), _cacheHandlers(true))
  finally _cacheBypass.set(prior)

// ── v1.4 State effect ─────────────────────────────────────────────────────
//
// State.get              → _perform("State", "get")
// State.set(s)           → _perform("State", "set", s)
// State.modify(f)        → _perform("State", "modify", f)
// runState(s0) { body }  — returns (finalState, result)

object State:
  def get(): Any          = _perform("State", "get")
  def set(s: Any): Any    = _perform("State", "set", s)
  def modify(f: Any): Any = _perform("State", "modify", f)

def runState(s0: Any)(bodyThunk: () => Any): Any =
  var state: Any = s0
  val handlers: Map[String, List[Any] => Any] = Map(
    "State.get" -> { (args: List[Any]) =>
      args.last.asInstanceOf[Any => Any](state)
    },
    "State.set" -> { (args: List[Any]) =>
      state = args(0)
      args.last.asInstanceOf[Any => Any](())
    },
    "State.modify" -> { (args: List[Any]) =>
      state = args(0).asInstanceOf[Any => Any](state)
      args.last.asInstanceOf[Any => Any](())
    },
  )
  val ops = Set("State.get", "State.set", "State.modify")
  val result = _handle(bodyThunk, ops, handlers)
  (state, result)

// ── v1.4 Tx effect ────────────────────────────────────────────────────────
//
// Tx.atomic { body }  — signals transactional scope; default is no-op
// runTx { body }      — default no-op handler (just runs body directly)

object Tx:
  def atomic(thunk: () => Any): Any = thunk()

def runTx(bodyThunk: () => Any): Any = bodyThunk()

// ── v1.4 Auth effect ──────────────────────────────────────────────────────
//
// Auth.currentUser  — Option[Any] from thread-local
// Auth.require      — current user or throw RuntimeException
// runAuthWith(user) { body }  — injects a fixed user

private val _authUser = ThreadLocal.withInitial[Option[Any]](() => None)

object Auth:
  def currentUser: Any = _authUser.get()
  def require: Any = _authUser.get() match
    case Some(u) => u
    case None    => throw new RuntimeException("Auth.require: no authenticated user in context")

def runAuthWith(user: Any)(bodyThunk: () => Any): Any =
  val prior = _authUser.get()
  _authUser.set(Some(user))
  try bodyThunk() finally _authUser.set(prior)

// ── v1.51.6 Stream algebraic effect ────────────────────────────────────────
//
// Stream.emit(x)       — push to the active stream buffer
// Stream.complete()    — no-op (body ends naturally)
// Stream.error(msg)    — throw a RuntimeException
// Stream.request(n)    — advisory demand hint (no-op)
// runStream(bodyThunk) — discharges Stream effect; returns (_Source, Any)
//   where _Source.runToList() returns the emitted values.
//   Uses a module-level var buffer so Stream.emit is a direct side effect;
//   no CPS trampoline is needed, so while/var loops work inside the body.
//   ArrayBuffer repr: O(1) length, no bulk-copy after loop.

class _Source(val _data: scala.collection.mutable.ArrayBuffer[Any]):
  def runToList(): scala.collection.mutable.ArrayBuffer[Any] = _data
  def toList: List[Any] = _data.toList

private var _streamBuf: scala.collection.mutable.ArrayBuffer[Any] = null

object Stream:
  def emit(x: Any): Any   = { if _streamBuf != null then _streamBuf += x; () }
  def complete(): Any      = ()
  def error(msg: Any): Any = throw new RuntimeException(String.valueOf(msg))
  def request(n: Any): Any = ()

def runStream(bodyThunk: () => Any): Any =
  val buf = scala.collection.mutable.ArrayBuffer.empty[Any]
  _streamBuf = buf
  try
    val result = bodyThunk()
    (new _Source(buf), result)
  finally
    _streamBuf = null


// ── stub serve runtime (no HTTP server) ──────────────────────────────────────
// No-op stubs for route/onWebSocket/_routes/_httpDoRequest so the actor and
// Http-effect runtimes compile in scripts that don't use serve()/WebSockets.
private case class _SscRouteEntry(method: String, path: String)
private val _routes = scala.collection.mutable.ArrayBuffer.empty[_SscRouteEntry]
private def route(method: String, path: String)(handler: Request => Any): Unit =
  _routes.append(_SscRouteEntry(method, path))
private trait _SscWs { def send(t: String): Unit; def recv(): Option[String] }
// Signature mirrors the real `onWebSocket` in WebSocketRuntime (incl. the
// `protocols` list the cluster actor route passes) so the actor runtime
// compiles identically with or without a real serve runtime.
private def onWebSocket(path: String, origins: List[String] = Nil,
    protocols: List[String] = Nil, maxConnections: Int = 0,
    maxMessagesPerSec: Int = 0)(handler: _SscWs => Unit): Unit = ()
private def _httpDoRequest(method: String, url: String, body: String,
    headers: Map[String, String]): Any =
  sys.error("Http effect requires a serve runtime; call runHttp{} or add serve()")
val r = exec("echo", List("hello from exec"), ProcessOptions())
println("exit=" + r.exitCode)
locally { val _auto: Any = println("out=" + r.stdout.trim); if _auto != () && _auto != null then println(_auto) }


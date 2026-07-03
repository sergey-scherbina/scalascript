package scalascript.codegen

object JvmRuntimePreamble:
  val source: String =
    """|
       |// ── Show / println override (scripting-style Double formatting) ────────
       |// Mirrors the interpreter / JS backends: a Double whose value is an
       |// integer renders without the trailing ".0" (e.g. 4.0 → "4").
       |def _show(v: Any): String = v match
       |  case null      => "null"
       |  case d: Double => if d == d.toLong.toDouble then d.toLong.toString else d.toString
       |  // Exact numerics (v1.64): plain (non-scientific) rendering, matching
       |  // the interpreter / JS backends.
       |  case bd: BigDecimal => bd.bigDecimal.toPlainString
       |  case bi: BigInt     => bi.toString
       |  case s: String => s
       |  // _Raw HTML nodes (from `raw(...)`, html"...", or DSL tag fns) render
       |  // as their inner string so `println(div(...))` prints the markup.
       |  case r: _Raw   => r.html
       |  // Render a Range like a List so xs.indices and similar lazy
       |  // iterables match the interpreter / JS output ("List(0, 1, 2)").
       |  case r: scala.collection.immutable.Range => r.toList.map(_show).mkString("List(", ", ", ")")
       |  // Match interpreter/JS rendering of Option, Map, List, Tuple, and
       |  // case-class instances — recursively `_show` children so a Double
       |  // inside Some(Circle(5.0)) still drops its trailing `.0`.
       |  case None       => "None"
       |  case Some(inner) => "Some(" + _show(inner) + ")"
       |  case m: Map[?, ?] =>
       |    if m.isEmpty then "Map()"
       |    else m.iterator.map((k, vv) => _show(k) + " -> " + _show(vv)).mkString("Map(", ", ", ")")
       |  case t: scala.Tuple =>
       |    "(" + t.productIterator.map(_show).mkString(", ") + ")"
       |  case xs: List[?] => xs.map(_show).mkString("List(", ", ", ")")
       |  // Optics carry a printable label as their last field; route through
       |  // toString so callers see `Lens(_.a.b)` instead of the function refs.
       |  case l: Lens[?, ?]      => l.toString
       |  case o: Optional[?, ?]  => o.toString
       |  case t: Traversal[?, ?] => t.toString
       |  case p: Prism[?, ?]     => p.toString
       |  case p: Product if p.productArity > 0 =>
       |    p.productPrefix + "(" + p.productIterator.map(_show).mkString(", ") + ")"
       |  case p: Product => p.productPrefix
       |  case other     => other.toString
       |
       |def _tupleConcat(a: Any, b: Any): Any = (a, b) match
       |  case (at: scala.Tuple, bt: scala.Tuple) =>
       |    scala.Tuple.fromArray(at.productIterator.toArray ++ bt.productIterator.toArray)
       |  case (at: scala.Tuple, _) if b == () => at
       |  case (_, bt: scala.Tuple) if a == () => bt
       |  case (_, _) if a == () && b == () => ()
       |  case (at: scala.Tuple, _) =>
       |    scala.Tuple.fromArray(at.productIterator.toArray :+ b)
       |  case (_, bt: scala.Tuple) =>
       |    scala.Tuple.fromArray(Array(a) ++ bt.productIterator.toArray)
       |  case _ if a == () => b
       |  case _ if b == () => a
       |  case (al: List[?], bl: List[?]) => al.asInstanceOf[List[Any]] ++ bl.asInstanceOf[List[Any]]
       |  case _ => scala.Tuple.fromArray(Array(a, b))
       |
       |def println(v: Any): Unit = scala.Predef.println(_show(v))
       |def print(v: Any): Unit   = scala.Predef.print(_show(v))
       |
       |// Stage 5+/B.3 — `Console` shadows `scala.Console` so that Normalize's
       |// bare-println rewrite (`println` → `Console.println`) routes through
       |// `_show` in both the emitExpr intrinsic path and the passthrough path.
       |object Console:
       |  def println(v: Any): Unit = scala.Predef.println(_show(v))
       |  def print(v: Any): Unit   = scala.Predef.print(_show(v))
       |
       |// std.bench — Bench.opaque: an anti-folding identity barrier.  Returns
       |// `x` unchanged, but the volatile-field comparison forces HotSpot to
       |// materialise `x` at each call site and prevents C2 from precomputing
       |// pure-arith expressions whose inputs flow through `opaque`.  The
       |// implementation mirrors `std::hint::black_box` (Rust) and
       |// `org.openjdk.jmh.infra.Blackhole.consume` (JMH).
       |//
       |// Cost: ~1-3 ns/call on M1 — comparable to a volatile read.  Used by
       |// benchmark wrappers; user code that doesn't import Bench.opaque pays
       |// nothing.
       |object Bench:
       |  @volatile private var _ssc_opaque_t1: Long = 0L
       |  @volatile private var _ssc_opaque_t2: Long = java.lang.Long.MIN_VALUE
       |  @scala.annotation.nowarn def opaque[A](x: A): A =
       |    if _ssc_opaque_t1 == _ssc_opaque_t2 then null.asInstanceOf[A] else x
       |
       |// `sx` is like `s` but routes each interpolated value through `_show`,
       |// so a whole-number Double interpolated into a string drops its ".0".
       |// Code-block emission rewrites `s"..."` to `sx"..."` for the same reason.
       |extension (sc: StringContext)
       |  def sx(args: Any*): String = sc.s(args.map(_show)*)
       |
       |extension (sc: StringContext)
       |  def md(args: Any*): String =
       |    val s = sc.s(args*)
       |    val lines = s.split("\n", -1).toSeq
       |    val body = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
       |    if body.isEmpty then ""
       |    else
       |      val indent = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).min
       |      body.map(_.drop(indent)).mkString("\n")
       |
       |// ── HTML / CSS string interpolators ────────────────────────────────────
       |// html"..." auto-escapes interpolated values unless wrapped in raw(s).
       |case class _Raw(html: String)
       |def raw(s: Any): _Raw = _Raw(_show(s))
       |
       |def _htmlEscape(s: String): String =
       |  val sb = StringBuilder(s.length)
       |  var i = 0
       |  while i < s.length do
       |    s.charAt(i) match
       |      case '&'  => sb ++= "&amp;"
       |      case '<'  => sb ++= "&lt;"
       |      case '>'  => sb ++= "&gt;"
       |      case '"'  => sb ++= "&quot;"
       |      case '\'' => sb ++= "&#39;"
       |      case c    => sb += c
       |    i += 1
       |  sb.toString
       |
       |def escape(s: Any): String = _htmlEscape(_show(s))
       |
       |/** `collectCss(comp1, comp2, ...)` — concatenate each argument's
       | *  `css` field into one CSS string for a page-level <style>.
       | *  Convention helper for component-style .ssc files (see SPEC §8.4).
       | *  Each argument is expected to be a Scala `object` exposing a
       | *  `val css: String`; reflective access keeps the helper free of
       | *  a shared component supertype.  Anything without a no-arg
       | *  `css` method that returns a String is silently skipped. */
       |def collectCss(parts: Any*): String =
       |  parts.flatMap { part =>
       |    try
       |      val m = part.getClass.getMethod("css")
       |      m.invoke(part) match
       |        case s: String => Some(s)
       |        case _         => None
       |    catch case _: Throwable => None
       |  }.mkString("\n")
       |
       |/** `collectJs(comp1, comp2, ...)` — same shape as `collectCss`,
       | *  reads each argument's `val js: String` for a page <script>. */
       |def collectJs(parts: Any*): String =
       |  parts.flatMap { part =>
       |    try
       |      val m = part.getClass.getMethod("js")
       |      m.invoke(part) match
       |        case s: String => Some(s)
       |        case _         => None
       |    catch case _: Throwable => None
       |  }.mkString("\n")
       |
       |/** `scope("Card")` — class-name suffix helper for component-style
       | *  .ssc files (see SPEC §8.4).
       | *
       | *    val s = scope("Card")
       | *    val css = s.css(".title { color: blue }")  // ".title__Card { color: blue }"
       | *    val c   = s.cls("title")                   // "title__Card"
       | *
       | *  Two components can both write bare `.title` without their
       | *  concatenated CSS colliding.  The CSS rewriter is a simple
       | *  `\.identifier` regex pass; class chains (`.a.b`) work, but
       | *  `.ident` inside `url(...)` would also be rewritten — keep URL
       | *  strings free of bare-identifier dots if you depend on them. */
       |class _Scope(val name: String):
       |  private val pat = "\\.([A-Za-z_][A-Za-z0-9_-]*)".r
       |  def css(s: String): String =
       |    pat.replaceAllIn(s, m =>
       |      java.util.regex.Matcher.quoteReplacement("." + m.group(1) + "__" + name)
       |    )
       |  def cls(n: String): String = n + "__" + name
       |
       |def scope(name: String): _Scope = _Scope(name)
       |
       |// i18n runtime helpers
       |var _i18nLocale: String = "en"
       |var _i18nTable: Map[String, Map[String, String]] = Map.empty
       |def setLocale(code: String): Unit = { _i18nLocale = code }
       |def t(key: String): String = _i18nTable.get(_i18nLocale).flatMap(_.get(key)).getOrElse(key)
       |/** `wc(tag, Component, args*)` — server-side render with declarative shadow DOM.
       | *  Uses reflection to call `Component.css` and `Component.render(args*)`,
       | *  following the same convention as `collectCss`. */
       |def wc(tag: String, component: Any, args: Any*): String =
       |  val cssStr =
       |    try component.getClass.getMethod("css").invoke(component) match
       |      case s: String => s
       |      case _         => ""
       |    catch case _: Throwable => ""
       |  val innerHtml =
       |    try
       |      val cls = component.getClass
       |      val methods = cls.getMethods.filter(_.getName == "render")
       |      val renderM = methods.find(m => m.getParameterCount == args.length)
       |        .orElse(methods.headOption)
       |      renderM match
       |        case Some(m) =>
       |          m.invoke(component, args.map(_.asInstanceOf[AnyRef])*) match
       |            case r: _Raw => r.html
       |            case v       => _show(v)
       |        case None => ""
       |    catch case _: Throwable => ""
       |  s"<$tag-component><template shadowrootmode=\"open\"><style>$cssStr</style>$innerHtml</template></$tag-component>"
       |
       |// Used by heading-bound html-block emission: escape unless raw(...).
       |def _html_interp(v: Any): String = v match
       |  case r: _Raw => r.html
       |  case _       => _htmlEscape(_show(v))
       |
       |extension (sc: StringContext)
       |  def html(args: Any*): String =
       |    val sb = StringBuilder()
       |    val parts = sc.parts
       |    var i = 0
       |    while i < parts.length do
       |      sb ++= parts(i)
       |      if i < args.length then args(i) match
       |        case r: _Raw => sb ++= r.html
       |        case v       => sb ++= _htmlEscape(_show(v))
       |      i += 1
       |    sb.toString
       |
       |  def css(args: Any*): String = sc.s(args.map(_show)*)
       |
       |// ── Typed HTML DSL — `div(attr.cls := "hero", h1("hi"))` ───────────────
       |case class _AttrKey(name: String):
       |  def := (value: Any): _Attr = _Attr(name, _show(value))
       |case class _Attr(name: String, value: String)
       |
       |object attr:
       |  val cls         = _AttrKey("class")
       |  val id          = _AttrKey("id")
       |  val href        = _AttrKey("href")
       |  val src         = _AttrKey("src")
       |  val alt         = _AttrKey("alt")
       |  val name        = _AttrKey("name")
       |  val title       = _AttrKey("title")
       |  val style       = _AttrKey("style")
       |  val type_       = _AttrKey("type")
       |  val value_      = _AttrKey("value")
       |  val placeholder = _AttrKey("placeholder")
       |  val method_     = _AttrKey("method")
       |  val action      = _AttrKey("action")
       |  val target      = _AttrKey("target")
       |  val rel         = _AttrKey("rel")
       |  val for_        = _AttrKey("for")
       |  val role        = _AttrKey("role")
       |  val colspan     = _AttrKey("colspan")
       |  val rowspan     = _AttrKey("rowspan")
       |  val disabled    = _AttrKey("disabled")
       |
       |private def _renderChild(v: Any): String = v match
       |  case r: _Raw         => r.html
       |  case xs: Iterable[_] => xs.map(_renderChild).mkString
       |  case other           => _htmlEscape(_show(other))
       |
       |private def _renderTag(name: String, args: Seq[Any], voidTag: Boolean = false): _Raw =
       |  val attrs    = scala.collection.mutable.LinkedHashMap.empty[String, String]
       |  val children = StringBuilder()
       |  def handle(v: Any): Unit = v match
       |    case a: _Attr        => attrs(a.name) = a.value
       |    case xs: Iterable[_] => xs.foreach(handle)
       |    case other           => children ++= _renderChild(other)
       |  args.foreach(handle)
       |  val attrStr =
       |    if attrs.isEmpty then ""
       |    else attrs.map((k, v) => " " + k + "=\"" + _htmlEscape(v) + "\"").mkString
       |  if voidTag then _Raw("<" + name + attrStr + ">")
       |  else            _Raw("<" + name + attrStr + ">" + children.toString + "</" + name + ">")
       |
       |// Each tag is a value, not a def, so `items.map(li)` works.  The class
       |// extends `Any => _Raw` so it eta-expands to a Function1; an additional
       |// `apply(args: Any*)` overload preserves the multi-arg `div(a, b, c)`
       |// call syntax that the DSL needs.
       |class _Tag(name: String, voidTag: Boolean = false) extends (Any => _Raw):
       |  override def apply(arg: Any): _Raw = _renderTag(name, Seq(arg), voidTag)
       |  def apply(args: Any*): _Raw       = _renderTag(name, args, voidTag)
       |
       |case class _Doc(parts: Seq[Any])
       |def doc(args: Any*): _Doc = _Doc(args.toSeq)
       |def render(args: Any*): Unit =
       |  def toStr(v: Any): String = v match
       |    case d: _Doc => d.parts.map(toStr).mkString("\n")
       |    case other   => other.toString
       |  val text =
       |    if args.length == 1 && args(0).isInstanceOf[_Doc] then toStr(args(0).asInstanceOf[_Doc])
       |    else args.map(toStr).mkString("\n")
       |  println(text)
       |
       |// Wall-clock for benchmarks — matches ScalaScript's `nanoTime()` primitive.
       |def nanoTime(): Long = java.lang.System.nanoTime()
       |
       |// ── Lens runtime — pure-functional optic over case-class field paths ──
       |case class Lens[S, A](get: S => A, set: (S, A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Lens" else _label
       |  def modify(s: S, f: A => A): S = set(s, f(get(s)))
       |  def andThen[B](other: Lens[A, B]): Lens[S, B] =
       |    Lens(s => other.get(get(s)), (s, b) => set(s, other.set(get(s), b)))
       |  def andThen[B](other: Optional[A, B]): Optional[S, B] =
       |    Optional(s => other.getOption(get(s)), (s, b) => set(s, other.set(get(s), b)))
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => other.toList(get(s)),
       |      (s, f) => set(s, other.modifyF(get(s), f))
       |    )
       |
       |// ── Prism runtime — sum-type optic, conditional get / set / modify ────
       |case class Prism[S, A](getOption: S => Option[A], reverseGet: A => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Prism" else _label
       |  def set(s: S, a: A): S = getOption(s) match
       |    case Some(_) => reverseGet(a)
       |    case None    => s
       |  def modify(s: S, f: A => A): S = getOption(s) match
       |    case Some(a) => reverseGet(f(a))
       |    case None    => s
       |  def andThen[B](other: Prism[A, B]): Prism[S, B] =
       |    Prism(
       |      s => getOption(s).flatMap(other.getOption),
       |      b => reverseGet(other.reverseGet(b))
       |    )
       |
       |// ── Optional runtime — partial optic over a path with `.some` ─────
       |case class Optional[S, A](getOption: S => Option[A], set: (S, A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Optional" else _label
       |  def modify(s: S, f: A => A): S = getOption(s) match
       |    case Some(a) => set(s, f(a))
       |    case None    => s
       |  def andThen[B](other: Optional[A, B]): Optional[S, B] =
       |    Optional(
       |      s => getOption(s).flatMap(other.getOption),
       |      (s, b) => getOption(s) match
       |        case Some(a) => set(s, other.set(a, b))
       |        case None    => s
       |    )
       |  def andThen[B](other: Lens[A, B]): Optional[S, B] =
       |    Optional(
       |      s => getOption(s).map(other.get),
       |      (s, b) => getOption(s) match
       |        case Some(a) => set(s, other.set(a, b))
       |        case None    => s
       |    )
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => getOption(s).toList.flatMap(other.toList),
       |      (s, f) => getOption(s) match
       |        case Some(a) => set(s, other.modifyF(a, f))
       |        case None    => s
       |    )
       |
       |// ── Traversal runtime — multi-foci optic for `.each` paths ────────
       |case class Traversal[S, A](toList: S => List[A], modifyF: (S, A => A) => S, _label: String = ""):
       |  override def toString: String = if _label.isEmpty then "Traversal" else _label
       |  def getAll(s: S): List[A] = toList(s)
       |  def modify(s: S, f: A => A): S = modifyF(s, f)
       |  def set(s: S, a: A): S = modifyF(s, _ => a)
       |  def andThen[B](other: Traversal[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).flatMap(other.toList),
       |      (s, f) => modifyF(s, a => other.modifyF(a, f))
       |    )
       |  def andThen[B](other: Lens[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).map(other.get),
       |      (s, f) => modifyF(s, a => other.set(a, f(other.get(a))))
       |    )
       |  def andThen[B](other: Optional[A, B]): Traversal[S, B] =
       |    Traversal(
       |      s => toList(s).flatMap(a => other.getOption(a).toList),
       |      (s, f) => modifyF(s, a => other.modify(a, f))
       |    )
       |
       |// Environment variable reader — same surface on all three backends.
       |def getenv(key: String, defaultVal: String = ""): String =
       |  val v = java.lang.System.getenv(key)
       |  if v == null || v.isEmpty then defaultVal else v
       |
       |// ── Rate limiting / TOTP / Password — adapter shims ───────────
       |// The implementations live in runtime-server-common (inlined as
       |// classpath resources by JvmGen.commonRuntime); these top-level
       |// defs preserve the user-facing API.
       |def rateLimit(key: String, limit: Long, windowSeconds: Long): Boolean =
       |  RateLimit.tryAcquire(key, limit, windowSeconds)
       |def rateLimitReset(key: String): Unit = RateLimit.reset(key)
       |def totpSecret(): String = Totp.secret()
       |def totpUri(secret: String, account: String, issuer: String = ""): String =
       |  Totp.uri(secret, account, issuer)
       |def totpCode(secret: String): String = Totp.code(secret)
       |def totpValid(secret: String, code: String, skew: Int = 1): Boolean =
       |  Totp.valid(secret, code, skew)
       |def hashPassword(password: String, iter: Int = 200000): String =
       |  Password.hash(password, iter)
       |def verifyPassword(password: String, encoded: String): Boolean =
       |  Password.verify(password, encoded)
       |
       |""".stripMargin

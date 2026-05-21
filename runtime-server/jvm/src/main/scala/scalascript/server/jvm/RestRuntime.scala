package scalascript.server.jvm

// Real-Scala migration of `JvmGen.serveRuntime`'s REST half (Part1 +
// Part1b, ~765 LOC of generated source).  Same wire-format and runtime
// behaviour; type-checked at our build time, IDE-aware, refactor-safe.
//
// The package declaration is stripped by `loadJvmRuntimeSource` when the
// file is inlined into the generated scala-cli script — the top-level
// defs end up at the script's top level alongside the
// `runtime-server-common` types (Request / Response / HttpDispatchLoop
// / RequestBuilder / ResponseWriter / SessionCookie / Jwt / OAuth / …),
// which are themselves inlined just above this file, plus the JVM
// codegen `preamble` (defines `_show` etc.) and `serveRuntime` Part2
// (defines `_Metrics`).

// BUILD-ONLY:start
// Imports and stubs for our build's type checker.  Stripped by
// loadJvmRuntimeSource at codegen-inline time; the real definitions
// come from elsewhere in the generated script.
import scalascript.server.*

@scala.annotation.unused private def _show(v: Any): String = String.valueOf(v)
// _Metrics is now a real val in WebSocketRuntime.scala (same package),
// so no build-only stub is needed here.
// BUILD-ONLY:end

private val _restLog = org.slf4j.LoggerFactory.getLogger("scalascript.server")

// ── REST routing + serve(port) ─────────────────────────────────────────
// `UploadedFile` is inlined from runtime-server-common via commonRuntime —
// the case class definition lives there as the single source of truth.

// `Request` / `Response` POJOs are inlined from runtime-server-common
// via commonRuntime (HttpModel.scala) — the single source of truth.
// `req.json` is provided here as an extension because the case class
// lives in the shared module which doesn't depend on the JSON parser
// (`_fromJson`) emitted later in this template.
extension (req: Request)
  def json: Option[Any] =
    if req.body.isEmpty then None
    else try Some(_fromJson(req.body)) catch case _: Throwable => None

// ── Signed cookie sessions ──────────────────────────────────────
// HMAC-SHA256-signed Map[String, String] roundtripped through the
// `session=<b64url(json)>.<b64url(hmac)>` cookie format.  Mirrors
// scalascript.server.SessionCookie and the JsGen Node runtime so
// the wire format is identical across all three backends.
private lazy val _sessionSecret: Array[Byte] =
  sys.env.get("SSC_SESSION_SECRET").filter(_.nonEmpty) match
    case Some(s) => s.getBytes("UTF-8")
    case None    =>
      val bytes = new Array[Byte](32)
      java.security.SecureRandom().nextBytes(bytes)
      _restLog.warn(
        "[ssc] SSC_SESSION_SECRET not set; using a process-local random key. " +
        "Sessions will not survive a server restart."
      )
      bytes
private def _hmacSha256(payload: Array[Byte]): Array[Byte] =
  val mac = javax.crypto.Mac.getInstance("HmacSHA256")
  mac.init(javax.crypto.spec.SecretKeySpec(_sessionSecret, "HmacSHA256"))
  mac.doFinal(payload)
private def _b64urlEnc(b: Array[Byte]): String =
  java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b)
private def _b64urlDec(s: String): Array[Byte] =
  java.util.Base64.getUrlDecoder.decode(s)
private def _sessionJsonEnc(m: Map[String, String]): String =
  def esc(s: String): String =
    val sb = StringBuilder().append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '"' || c == '\\' then sb.append('\\').append(c)
      else if c == '\n' then sb.append("\\n")
      else if c == '\r' then sb.append("\\r")
      else if c == '\t' then sb.append("\\t")
      else if c < 0x20 then sb.append("\\u%04x".format(c.toInt))
      else sb.append(c)
      i += 1
    sb.append('"').toString
  m.iterator.map((k, v) => esc(k) + ":" + esc(v)).mkString("{", ",", "}")
private def _sessionJsonDec(json: String): Option[Map[String, String]] =
  val t = json.trim
  if !t.startsWith("{") || !t.endsWith("}") then None
  else
    val inner = t.substring(1, t.length - 1).trim
    if inner.isEmpty then Some(Map.empty)
    else try
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
              case 'u' if i + 5 < inner.length =>
                sb.append(Integer.parseInt(inner.substring(i + 2, i + 6), 16).toChar)
                i += 6
              case _    => sb.append(c); i += 1
          else { sb.append(c); i += 1 }
        if i >= inner.length then throw RuntimeException("unterminated")
        i += 1
        sb.toString
      val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
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
// ── SessionCookie / SessionStore — adapter shims ────────────────
// Implementations live in scalascript.server.{SessionCookie,
// SessionStore} (inlined from runtime-server-common); these
// thin wrappers preserve the existing internal API surface that
// the surrounding routing code uses.
private def _packSession(m: Map[String, String]): String = SessionCookie.pack(m)
private def _unpackSession(cookieValue: String): Map[String, String] =
  SessionCookie.unpack(cookieValue).getOrElse(Map.empty)
private def _parseCookieSession(cookieHeader: String): Map[String, String] =
  if cookieHeader == null then Map.empty
  else SessionCookie.fromHeader(cookieHeader).getOrElse(Map.empty)
def cookieConfig(secure: Boolean, sameSite: String = "Lax"): Unit =
  SessionCookie.setCookieConfig(secure, sameSite match
    case s @ ("Strict" | "Lax" | "None") => s
    case _                               => "Lax")
private def _buildSetCookie(payload: Map[String, String]): String =
  SessionCookie.toSetCookie(payload)

// Opt-in server-side session store — sweep-on-access, env-controlled TTL.
@volatile private var _sessionStoreEnabled: Boolean = false
def useSessionStore(ttlSeconds: Long = 30L * 60L): Unit =
  SessionStore.useStore(ttlSeconds)
  _sessionStoreEnabled = true
private def _sessionStorePut(payload: Map[String, String]): String = SessionStore.put(payload)
private def _sessionStoreGet(ssid: String): Option[Map[String, String]] = SessionStore.get(ssid)
private def _sessionStoreDelete(ssid: String): Unit = SessionStore.delete(ssid)

// ── JWT (HS256 + RS256) — adapter shims ───────────────────────
// Implementations live in scalascript.server.{Jwt, JwtRsa} (inlined
// from runtime-server-common); these top-level defs preserve the
// user-facing `jwtSign / jwtVerify / jwtSignRsa / jwtVerifyRsa`
// API, and `_bearerFromAuth` delegates to Jwt.fromAuthHeader.
def jwtSign(claims: Map[String, String]): String           = Jwt.sign(claims)
def jwtVerify(token: String): Option[Map[String, String]]  = Jwt.verify(token)
def jwtSignRsa(claims: Map[String, String]): String        = JwtRsa.sign(claims)
def jwtVerifyRsa(token: String): Option[Map[String, String]] = JwtRsa.verify(token)
private def _bearerFromAuth(h: String): Option[String] = Jwt.fromAuthHeader(h)

// ── OAuth2 — adapter shims ───────────────────────────────────
// Implementation lives in scalascript.server.OAuth (inlined from
// runtime-server-common, with built-in provider presets for google
// / github and a mutable registry).  Shims preserve the existing
// `oauth*` top-level API.
def oauthRegisterProvider(name: String, cfg: Map[String, String]): Unit =
  OAuth.registerProvider(name, cfg)
def oauthAuthorizeUrl(provider: String, clientId: String, redirectUri: String, state: String, scope: String = ""): String =
  OAuth.authorizeUrl(provider, clientId, redirectUri, state, scope)
def oauthUserinfo(provider: String, accessToken: String): Option[Map[String, String]] =
  OAuth.userinfo(provider, accessToken)
def oauthExchangeCode(provider: String, code: String, clientId: String, clientSecret: String, redirectUri: String): Option[Map[String, String]] =
  OAuth.exchangeCode(provider, code, clientId, clientSecret, redirectUri)
def oauthRefreshToken(provider: String, refresh: String, clientId: String, clientSecret: String): Option[Map[String, String]] =
  OAuth.refreshToken(provider, refresh, clientId, clientSecret)

// HTTP Basic delegates to BasicAuth.fromHeader (inlined from
// runtime-server-common, mirrors Jwt.fromAuthHeader for Bearer).
private def _basicFromAuth(h: String): Option[(String, String)] =
  BasicAuth.fromHeader(h)

// ── CSRF helpers ────────────────────────────────────────────────
// `csrfToken()` returns a url-safe random token; the caller stashes
// it under "csrf" in the session and renders it in their form.
// `csrfValid(req)` checks form `csrf` / `X-CSRF-Token` header.
def csrfToken(): String =
  val bytes = new Array[Byte](24)
  java.security.SecureRandom().nextBytes(bytes)
  _b64urlEnc(bytes)
def csrfValid(req: Request): Boolean =
  val expected = req.session.getOrElse("csrf", "")
  val supplied = req.form.get("csrf")
    .orElse(req.headers.collectFirst {
      case (k, v) if k.equalsIgnoreCase("X-CSRF-Token") => v
    })
    .getOrElse("")
  if expected.isEmpty || supplied.isEmpty || expected.length != supplied.length then false
  else java.security.MessageDigest.isEqual(
    expected.getBytes("UTF-8"), supplied.getBytes("UTF-8"))

// JSON-encode anything: strings pass through as raw JSON (so hand-
// built JSON strings keep working); other values get structural
// emission with proper escaping.
private def _toJson(v: Any): String = v match
  case null     => "null"
  case s: String => s  // raw passthrough
  case _        => _toJsonValue(v)
private def _jsonQuote(s: String): String =
  val sb = StringBuilder().append('"')
  var i = 0
  while i < s.length do
    val c = s.charAt(i)
    if c == '"' || c == '\\' then sb.append('\\').append(c)
    else if c == '\n' then sb.append('\\').append('n')
    else if c == '\r' then sb.append('\\').append('r')
    else if c == '\t' then sb.append('\\').append('t')
    else if c == '\b' then sb.append('\\').append('b')
    else if c == '\f' then sb.append('\\').append('f')
    else if c < 0x20 then
      val hex = Integer.toHexString(c.toInt)
      sb.append('\\').append('u')
      var pad = 4 - hex.length
      while pad > 0 do sb.append('0'); pad -= 1
      sb.append(hex)
    else sb.append(c)
    i += 1
  sb.append('"').toString
private def _toJsonValue(v: Any): String = v match
  case null              => "null"
  case b: Boolean        => b.toString
  case n: (Int | Long | Short | Byte)  => n.toString
  case d: (Double | Float)             => d.toString
  case s: String         => _jsonQuote(s)
  case c: Char           => _jsonQuote(c.toString)
  case None              => "null"
  case Some(x)           => _toJsonValue(x)
  case xs: Iterable[?]   =>
    xs match
      case m: Map[?, ?] =>
        m.iterator.map { case (k, vv) =>
          val ks = k match { case s: String => s; case other => _show(other) }
          _jsonQuote(ks) + ":" + _toJsonValue(vv)
        }.mkString("{", ",", "}")
      case _ =>
        xs.iterator.map(_toJsonValue).mkString("[", ",", "]")
  case p: Product if p.productArity > 0 =>
    val names = p.productElementNames.toList
    val vals  = (0 until p.productArity).map(p.productElement).toList
    val isTuple = names.forall(_.matches("_[0-9]+"))
    if isTuple then vals.map(_toJsonValue).mkString("[", ",", "]")
    else names.iterator.zip(vals.iterator).map { (k, vv) =>
      _jsonQuote(k) + ":" + _toJsonValue(vv)
    }.mkString("{", ",", "}")
  case other             => _jsonQuote(_show(other))

// JSON read side — hand-rolled recursive-descent parser.  Returns
// Map[String, Any] for objects, List[Any] for arrays, Long for
// integers, Double for fractional / exponent numbers, String for
// strings, Boolean for booleans, None for JSON null.  Mirrors the
// interpreter / JS backends bit-for-bit.
private class _JsonParseException(msg: String) extends RuntimeException(msg)
private def _fromJson(src: String): Any =
  val state = new _JsonParser(src)
  val v = state.parseValue()
  state.skipWs()
  if state.pos < src.length then
    throw _JsonParseException("jsonParse: trailing data at position " + state.pos)
  v
private class _JsonParser(src: String):
  var pos: Int = 0
  val len: Int = src.length
  private def fail(msg: String): Nothing =
    throw _JsonParseException("jsonParse: " + msg + " at position " + pos)
  def skipWs(): Unit =
    while pos < len && { val c = src.charAt(pos); c == ' ' || c == '\t' || c == '\n' || c == '\r' } do pos += 1
  private def expect(s: String): Unit =
    if pos + s.length > len || src.substring(pos, pos + s.length) != s then fail(s"expected '$s'")
    else pos += s.length
  private def parseString(): String =
    if pos >= len || src.charAt(pos) != '"' then fail("expected '\"'")
    pos += 1
    val sb = StringBuilder()
    var done = false
    while !done do
      if pos >= len then fail("unterminated string")
      src.charAt(pos) match
        case '"'  => pos += 1; done = true
        case '\\' =>
          pos += 1
          if pos >= len then fail("dangling escape")
          src.charAt(pos) match
            case '"'  => sb.append('"');  pos += 1
            case '\\' => sb.append('\\'); pos += 1
            case '/'  => sb.append('/');  pos += 1
            case 'n'  => sb.append('\n'); pos += 1
            case 'r'  => sb.append('\r'); pos += 1
            case 't'  => sb.append('\t'); pos += 1
            case 'b'  => sb.append('\b'); pos += 1
            case 'f'  => sb.append('\f'); pos += 1
            case 'u'  =>
              pos += 1
              if pos + 4 > len then fail("short unicode escape")
              val hex = src.substring(pos, pos + 4)
              try sb.append(Integer.parseInt(hex, 16).toChar)
              catch case _: NumberFormatException => fail("bad unicode escape")
              pos += 4
            case c    => fail(s"bad escape '\\$c'")
        case c    => sb.append(c); pos += 1
    sb.toString
  private def parseNumber(): Any =
    val start = pos
    if pos < len && src.charAt(pos) == '-' then pos += 1
    while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
    var isDouble = false
    if pos < len && src.charAt(pos) == '.' then
      isDouble = true
      pos += 1
      while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
    if pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E') then
      isDouble = true
      pos += 1
      if pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-') then pos += 1
      while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
    val s = src.substring(start, pos)
    if isDouble then s.toDouble
    else try s.toLong catch case _: NumberFormatException => s.toDouble
  def parseValue(): Any =
    skipWs()
    if pos >= len then fail("unexpected end of input")
    src.charAt(pos) match
      case '"' => parseString()
      case 't' => expect("true");  true
      case 'f' => expect("false"); false
      case 'n' => expect("null");  None
      case '[' =>
        pos += 1; skipWs()
        val items = scala.collection.mutable.ListBuffer.empty[Any]
        if pos < len && src.charAt(pos) == ']' then pos += 1
        else
          var done = false
          while !done do
            items += parseValue()
            skipWs()
            if pos >= len then fail("unterminated array")
            src.charAt(pos) match
              case ',' => pos += 1; skipWs()
              case ']' => pos += 1; done = true
              case c   => fail(s"expected ',' or ']', got '$c'")
        items.toList
      case '{' =>
        pos += 1; skipWs()
        val entries = scala.collection.mutable.ListBuffer.empty[(String, Any)]
        if pos < len && src.charAt(pos) == '}' then pos += 1
        else
          var done = false
          while !done do
            skipWs()
            val k = parseString()
            skipWs()
            if pos >= len || src.charAt(pos) != ':' then fail("expected ':'")
            pos += 1
            val v = parseValue()
            entries += (k -> v)
            skipWs()
            if pos >= len then fail("unterminated object")
            src.charAt(pos) match
              case ',' => pos += 1
              case '}' => pos += 1; done = true
              case c   => fail(s"expected ',' or '}', got '$c'")
        entries.toMap
      case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
      case c   => fail(s"unexpected character '$c'")

def jsonParse(s: String): Any        = _fromJson(s)
def jsonStringify(v: Any): String    = _toJsonValue(v)

// v1.5 Tier 5 #22 option (c) — `JsonValue` wrapper.  `jsonRead(s)`
// returns a `JsonValue` that supports idiomatic apply / get /
// typed-accessor methods.  Stored as a Scala class so
// `v("k")(i).asString` resolves cleanly through the Scala typer.
class JsonValue(val raw: Any):
  def apply(k: String): JsonValue = raw match
    case m: scala.collection.Map[?, ?] =>
      m.asInstanceOf[scala.collection.Map[Any, Any]].get(k) match
        case Some(v) => new JsonValue(v)
        case None    => throw new RuntimeException("JsonValue: no key '" + k + "'")
    case _ => throw new RuntimeException("JsonValue.apply('" + k + "'): not an object")
  def apply(i: Int): JsonValue = raw match
    case xs: scala.collection.Seq[?] =>
      if i >= 0 && i < xs.length then new JsonValue(xs(i))
      else throw new RuntimeException("JsonValue: index " + i + " out of bounds (size=" + xs.length + ")")
    case _ => throw new RuntimeException("JsonValue.apply(" + i + "): not an array")
  def get(k: String): Option[JsonValue] = raw match
    case m: scala.collection.Map[?, ?] =>
      m.asInstanceOf[scala.collection.Map[Any, Any]].get(k).map(new JsonValue(_))
    case _ => None
  def get(i: Int): Option[JsonValue] = raw match
    case xs: scala.collection.Seq[?] if i >= 0 && i < xs.length =>
      Some(new JsonValue(xs(i)))
    case _ => None
  def asString: String = raw match
    case s: String => s
    case other     => throw new RuntimeException("JsonValue.asString: expected string but got " + _show(other))
  def asInt: Long = raw match
    case n: Long   => n
    case n: Int    => n.toLong
    case n: Double => n.toLong
    case other     => throw new RuntimeException("JsonValue.asInt: expected int but got " + _show(other))
  def asLong: Long = asInt
  def asDouble: Double = raw match
    case n: Double => n
    case n: Long   => n.toDouble
    case n: Int    => n.toDouble
    case other     => throw new RuntimeException("JsonValue.asDouble: expected double but got " + _show(other))
  def asBool: Boolean = raw match
    case b: Boolean => b
    case other      => throw new RuntimeException("JsonValue.asBool: expected bool but got " + _show(other))
  def asList: List[JsonValue] = raw match
    case xs: scala.collection.Seq[?] => xs.toList.map(x => new JsonValue(x))
    case other => throw new RuntimeException("JsonValue.asList: expected list but got " + _show(other))
  def asMap: scala.collection.Map[Any, JsonValue] = raw match
    case m: scala.collection.Map[?, ?] =>
      m.asInstanceOf[scala.collection.Map[Any, Any]]
        .map { case (k, v) => k -> new JsonValue(v) }
    case other => throw new RuntimeException("JsonValue.asMap: expected map but got " + _show(other))
  def isNull: Boolean = raw == null
  def keys: List[Any] = raw match
    case m: scala.collection.Map[?, ?] =>
      m.asInstanceOf[scala.collection.Map[Any, Any]].keys.toList
    case _ => Nil
  def size: Long = raw match
    case xs: scala.collection.Seq[?]   => xs.length.toLong
    case m: scala.collection.Map[?, ?] => m.size.toLong
    case s: String                      => s.length.toLong
    case _                              => 0L
  override def toString: String = _show(raw)
def jsonRead(s: String): JsonValue = new JsonValue(_fromJson(s))

// v1.5 Tier 5 #22 — indexed access on `Any`-typed JSON values.
// `jsonParse` returns `Any`; the Scala typer rejects `obj("name")`
// on `Any` because `Any` has no `apply`.  `lookup` / `lookupOpt`
// dispatch dynamically at runtime so the same source compiles on
// all three backends.  `lookup` throws on a missing key
// (matches Map.apply); `lookupOpt` returns `Option`.
private def _lookupKey(v: Any, k: Any): Option[Any] = v match
  case m: scala.collection.Map[?, ?] => m.asInstanceOf[scala.collection.Map[Any, Any]].get(k)
  case xs: scala.collection.Seq[?]   => k match
    case i: Int  => if i >= 0 && i < xs.length then Some(xs(i)) else None
    case i: Long => val ii = i.toInt; if ii >= 0 && ii < xs.length then Some(xs(ii)) else None
    case _       => None
  case s: String => k match
    case i: Int  => if i >= 0 && i < s.length then Some(s.charAt(i).toString) else None
    case i: Long => val ii = i.toInt; if ii >= 0 && ii < s.length then Some(s.charAt(ii).toString) else None
    case _       => None
  case _ => None
def lookup(v: Any, k: Any): Any = _lookupKey(v, k) match
  case Some(x) => x
  case None    => throw new RuntimeException("lookup: key " + _show(k) + " not found in " + _show(v))
def lookupOpt(v: Any, k: Any): Option[Any] = _lookupKey(v, k)

// Tier 5 #20 — typed request validation primitives.  `requireX`
// throws a `RestValidationError` (inlined from runtime-server-common)
// on missing/invalid input that the route dispatcher catches and
// turns into a 400 Bad Request.
//
// `validate { body }` flips a thread-local flag so `require*`
// records the error and returns a safe default instead of
// throwing — the body keeps running and accumulates every
// problem in one pass, returning Right(value) / Left(map).
//
// The argument is typed `Any` (not the bundled `Request` class)
// so unit tests can pass any case class with `form` / `query`
// maps; matches the dynamic semantics on the other two backends.
val _validationStack = new java.util.concurrent.atomic.AtomicReference[List[scala.collection.mutable.LinkedHashMap[String, String]]](Nil)
private def _recordOrThrow(name: String, msg: String, default: Any): Any =
  val cur = _validationStack.get()
  cur.headOption match
    case Some(buf) =>
      buf.put(name, msg); default
    case None =>
      throw new RestValidationError(msg)
private def _restFieldOf(req: Any, name: String): Option[String] =
  def look(field: String): Option[String] = req match
    case r: Request => field match
      case "form"  => r.form.get(name)
      case "query" => r.query.get(name)
      case _       => None
    case _ =>
      try
        val cls = req.getClass
        val f = cls.getMethod(field)
        f.invoke(req) match
          case m: scala.collection.Map[?, ?] =>
            m.asInstanceOf[scala.collection.Map[String, String]].get(name)
          case _ => None
      catch case _: Throwable => None
  look("form").orElse(look("query"))
def requireString(req: Any, name: String): String =
  _restFieldOf(req, name) match
    case Some(s) => s
    case None    => _recordOrThrow(name, s"missing field: $name", "").asInstanceOf[String]
def optionalString(req: Any, name: String): Option[String] =
  _restFieldOf(req, name)
def requireInt(req: Any, name: String): Long =
  _restFieldOf(req, name) match
    case Some(s) =>
      try s.trim.toLong
      catch case _: NumberFormatException =>
        _recordOrThrow(name, s"invalid integer for field: $name", 0L) match
          case n: Long => n
          case n: Int  => n.toLong
          case _       => 0L
    case None => _recordOrThrow(name, s"missing field: $name", 0L) match
      case n: Long => n
      case n: Int  => n.toLong
      case _       => 0L
def optionalInt(req: Any, name: String): Option[Long] =
  _restFieldOf(req, name).flatMap(s =>
    try Some(s.trim.toLong) catch case _: NumberFormatException => None)
def requireDouble(req: Any, name: String): Double =
  _restFieldOf(req, name) match
    case Some(s) =>
      try s.trim.toDouble
      catch case _: NumberFormatException =>
        _recordOrThrow(name, s"invalid number for field: $name", 0.0).asInstanceOf[Double]
    case None => _recordOrThrow(name, s"missing field: $name", 0.0).asInstanceOf[Double]
def optionalDouble(req: Any, name: String): Option[Double] =
  _restFieldOf(req, name).flatMap(s =>
    try Some(s.trim.toDouble) catch case _: NumberFormatException => None)
def requireBool(req: Any, name: String): Boolean =
  _restFieldOf(req, name) match
    case Some(s) => s.trim.toLowerCase match
      case "true"  | "1" | "yes" | "on"  => true
      case "false" | "0" | "no"  | "off" => false
      case _ => _recordOrThrow(name, s"invalid boolean for field: $name", false).asInstanceOf[Boolean]
    case None => _recordOrThrow(name, s"missing field: $name", false).asInstanceOf[Boolean]
def optionalBool(req: Any, name: String): Option[Boolean] =
  _restFieldOf(req, name).flatMap(s => s.trim.toLowerCase match
    case "true"  | "1" | "yes" | "on"  => Some(true)
    case "false" | "0" | "no"  | "off" => Some(false)
    case _ => None)
def requireRange(req: Any, name: String, min: Long, max: Long): Long =
  _restFieldOf(req, name) match
    case Some(s) =>
      try
        val n = s.trim.toLong
        if n < min || n > max then
          _recordOrThrow(name, s"out of range [$min..$max] for field: $name", min) match
            case x: Long => x; case x: Int => x.toLong; case _ => min
        else n
      catch case _: NumberFormatException =>
        _recordOrThrow(name, s"invalid integer for field: $name", min) match
          case x: Long => x; case x: Int => x.toLong; case _ => min
    case None => _recordOrThrow(name, s"missing field: $name", min) match
      case x: Long => x; case x: Int => x.toLong; case _ => min
def requireRangeDouble(req: Any, name: String, min: Double, max: Double): Double =
  _restFieldOf(req, name) match
    case Some(s) =>
      try
        val n = s.trim.toDouble
        if n < min || n > max then
          _recordOrThrow(name, s"out of range [$min..$max] for field: $name", min).asInstanceOf[Double]
        else n
      catch case _: NumberFormatException =>
        _recordOrThrow(name, s"invalid number for field: $name", min).asInstanceOf[Double]
    case None =>
      _recordOrThrow(name, s"missing field: $name", min).asInstanceOf[Double]
def requireOneOf(req: Any, name: String, options: List[String]): String =
  val fallback = options.headOption.getOrElse("")
  _restFieldOf(req, name) match
    case Some(s) if options.contains(s) => s
    case Some(s) =>
      _recordOrThrow(name,
        s"invalid value '$s' for field: $name (expected one of: ${options.mkString(", ")})",
        fallback).asInstanceOf[String]
    case None =>
      _recordOrThrow(name, s"missing field: $name", fallback).asInstanceOf[String]

/** v1.5 Tier 5 #20 — accumulating-error block.  Runs `body` with an
 *  active collector, returns Right(result) on success or Left(map)
 *  carrying every error in insertion order. */
def validate[A](body: => A): Any =
  val buf = scala.collection.mutable.LinkedHashMap.empty[String, String]
  _validationStack.updateAndGet(buf :: _)
  try
    val result = body
    if buf.nonEmpty then Left(buf.toMap) else Right(result)
  finally
    _validationStack.updateAndGet(_.tail)

// User-facing response factories — `Response.html("…")` etc.  Defined
// as extensions on the companion (rather than as an `object Response`
// re-opening the case-class companion) because the case class lives in
// a different compilation unit (runtime-server-common HttpModel.scala)
// and Scala doesn't let two modules merge a companion object.  At
// scala-cli-inline time the extension and the auto-companion end up at
// top level together; extension dispatch resolves `Response.html(...)`
// to the right method either way.
private val _ResponseHtml = Map("Content-Type" -> "text/html; charset=utf-8")
private val _ResponseText = Map("Content-Type" -> "text/plain; charset=utf-8")
private val _ResponseJson = Map("Content-Type" -> "application/json")

extension (r: Response.type)
  def html(body: Any): Response     = Response(200, _ResponseHtml, _show(body))
  def text(body: Any): Response     = Response(200, _ResponseText, _show(body))
  def json(body: Any): Response     = Response(200, _ResponseJson, _toJson(body))
  def redirect(to: String): Response = Response(302, Map("Location" -> to), "")
  def notFound(body: Any = "Not Found"): Response = Response(404, body = _show(body))
  def status(code: Int, body: Any = ""): Response = Response(code, body = _show(body))
  def basicAuthChallenge(realm: String): Response =
    val safe = realm.replace("\\", "\\\\").replace("\"", "\\\"")
    Response(401, Map("WWW-Authenticate" -> ("Basic realm=\"" + safe + "\"")), "Authentication required")

// `_Seg` / `_parsePath` / `_matchPath` / `_parseQuery` / `_contentTypeFor` /
// `_parseMultipart` delegate to HttpHelpers (inlined from runtime-server-common).
// The underscore-prefixed local names are kept as one-line shims so existing
// dispatch / route-matching call sites elsewhere in serveRuntime stay untouched.
private type _Seg = HttpHelpers.Seg
private val _Seg  = HttpHelpers.Seg
private def _parsePath(p: String): List[_Seg] = HttpHelpers.parsePath(p)

// Route handler returns Any (Response | _StreamResponse | primitive auto-wrapped) — the
// runtime dispatcher in `_handle` pattern-matches on the actual type and writes the wire
// bytes accordingly.  The wider Any return type is what lets MCP transports use
// `route("GET", path) { req => sse(req) { … } }`: `sse()` returns `_StreamResponse`,
// not `Response`.
private case class _Route(method: String, path: String, pattern: List[_Seg], handler: Request => Any)
private val _routes      = scala.collection.mutable.ArrayBuffer.empty[_Route]
private val _middlewares = scala.collection.mutable.ArrayBuffer.empty[(Request, () => Any) => Any]

def route(method: String, path: String)(handler: Request => Any): Unit =
  _routes += _Route(method.toUpperCase, path, _parsePath(path), handler)

def use(fn: (Request, () => Any) => Any): Unit = _middlewares += fn

// Tier 5 #21 — `/_health` and `/_ready` defaults auto-registered the
// first time `serve(...)` runs.  User-defined routes with the same
// path keep precedence.
private def _registerHealthDefaults(): Unit =
  def has(p: String): Boolean = _routes.exists(r => r.method == "GET" && r.path == p)
  val ok: Request => Response = _ =>
    Response(200, Map("Content-Type" -> "application/json"), "{\"status\":\"ok\"}")
  if !has("/_health") then _routes += _Route("GET", "/_health", _parsePath("/_health"), ok)
  if !has("/_ready")  then _routes += _Route("GET", "/_ready",  _parsePath("/_ready"),  ok)

private def _matchPath(pat: List[_Seg], segs: List[String]): Option[Map[String, String]] =
  HttpHelpers.matchPath(pat, segs)
private def _parseQuery(q: String): Map[String, String] = HttpHelpers.parseQuery(q)

private def _parseMultipart(
    contentType: String,
    bodyLatin1:  String
): (Map[String, String], Map[String, UploadedFile], List[java.io.File]) =
  Multipart.parse(contentType, bodyLatin1, _spoolThreshold, _uploadDir)

// ── Body size limit ───────────────────────────────────────────────────
@volatile private var _maxBodySizeBytes: Long = Long.MaxValue
def maxBodySize(n: Int): Unit = _maxBodySizeBytes = n.toLong

// ── Upload spool-to-disk ──────────────────────────────────────────────
@volatile private var _spoolThreshold: Long = 1024L * 1024L
@volatile private var _uploadDir: String    = System.getProperty("java.io.tmpdir")
def uploadSpoolThreshold(n: Int): Unit = _spoolThreshold = n.toLong
def uploadDir(path: String): Unit      = _uploadDir = path

// ── Static file root (set by serve(view, port) before serve(port)) ────
@volatile var _ssc_static_root: String = "."

// ── Streaming response sentinel ────────────────────────────────────────
// `_StreamResponse` aliases to the POJO `StreamResponse` inlined from
// runtime-server-common — kept as a local name so existing call sites
// (`_StreamResponse(...)`, `case sr: _StreamResponse`, return-type
// annotations) don't need to change.
private type _StreamResponse = StreamResponse
private val  _StreamResponse = StreamResponse
def streamResponse(status: Int = 200, headers: Map[String, String] = Map.empty)(block: (String => Unit) => Any): _StreamResponse =
  _StreamResponse(status, headers, block)

// ── Server-Sent Events ────────────────────────────────────────────────
private val _sseHeaders: Map[String, String] = Map(
  "Content-Type"      -> "text/event-stream",
  "Cache-Control"     -> "no-cache",
  "Connection"        -> "keep-alive",
  "X-Accel-Buffering" -> "no"
)
case class _SseStream(private val _write: String => Unit):
  def send(data: String): Unit = _write(s"data: $data\n\n")
  def send(event: String, data: String): Unit = _write(s"event: $event\ndata: $data\n\n")
  def close(): Unit = ()

def sse(@scala.annotation.unused req: Any)(block: _SseStream => Any): _StreamResponse =
  streamResponse(200, _sseHeaders) { write =>
    block(_SseStream(write))
  }

// ── CORS / gzip / cache config ────────────────────────────────────────
@volatile private var _corsOrigins: List[String] = Nil
@volatile private var _corsMethods: List[String] = Nil
@volatile private var _corsHeaders: List[String] = Nil
@volatile private var _gzipEnabled = false

def cors(origins: List[String], methods: List[String] = List("GET","POST","PUT","DELETE","OPTIONS","PATCH"), headers: List[String] = Nil): Unit =
  _corsOrigins = origins; _corsMethods = methods; _corsHeaders = headers

def useGzip(): Unit = _gzipEnabled = true

def cacheable(r: Response, maxAge: Long, etag: String = ""): Response =
  val cc = s"public, max-age=$maxAge"
  val h0 = r.headers + ("Cache-Control" -> cc)
  val h1 = if etag.nonEmpty then h0 + ("ETag" -> etag) else h0
  r.copy(headers = h1)

def noCache(r: Response): Response =
  r.copy(headers = r.headers + ("Cache-Control" -> "no-store, no-cache, must-revalidate"))

private def _applyCors(ex: com.sun.net.httpserver.HttpExchange): Unit =
  CorsHelpers(ex, _corsOrigins, _corsMethods, _corsHeaders)

private def _handle(ex: com.sun.net.httpserver.HttpExchange): Unit =
  _Metrics.httpRequests.incrementAndGet()
  val _accessStartNs = java.lang.System.nanoTime()
  val _accessMethod  = ex.getRequestMethod
  val _accessPath    = ex.getRequestURI.getPath
  val _accessIp      = try ex.getRemoteAddress.getAddress.getHostAddress catch case _: Throwable => "?"
  val _accessUa      = try ex.getRequestHeaders.getFirst("User-Agent") catch case _: Throwable => ""
  try
    val method = ex.getRequestMethod.toUpperCase
    val path   = ex.getRequestURI.getPath
    val segs   = path.split('/').toList.filter(_.nonEmpty)
    if method == "OPTIONS" && _corsOrigins.nonEmpty then
      _applyCors(ex)
      ex.sendResponseHeaders(204, -1)
    else {
    val matched = _routes.iterator
      .filter(_.method == method)
      .flatMap(r => _matchPath(r.pattern, segs).map(p => (r, p)))
      .nextOption()
    matched match
      case Some((r, params)) =>
        // Delegate the per-request envelope (parse + middleware
        // chain + RestValidationError → 400 + StreamResponse /
        // Response dispatch + tmpfile cleanup) to the shared
        // `HttpDispatchLoop` (inlined from runtime-server-common).
        HttpDispatchLoop.run(ex, method, path, params,
          r.handler, _middlewares.toSeq,
          HttpDispatchLoop.Config(
            reqBuilder = RequestBuilder.Config(
              maxBodySize         = _maxBodySizeBytes,
              spoolThreshold      = _spoolThreshold,
              uploadDir           = _uploadDir,
              sessionStoreEnabled = _sessionStoreEnabled,
              sessionStoreGet     = _sessionStoreGet,
              jwtVerify           = jwtVerify),
            respWriter = ResponseWriter.Config(
              corsOrigins         = _corsOrigins,
              corsMethods         = _corsMethods,
              corsHeaders         = _corsHeaders,
              gzipEnabled         = _gzipEnabled,
              sessionStoreEnabled = _sessionStoreEnabled,
              sessionStoreDelete  = _sessionStoreDelete,
              sessionStorePut     = _sessionStorePut,
              buildSetCookie      = _buildSetCookie),
            fiveXxCounter = _Metrics.http5xx),
          onError = e => _restLog.error(s"route error: ${e.getMessage}", e))
      case None =>
        // Fall through to a static file under the current directory
        // before 404'ing — mirrors the interpreter's WebServer.
        _serveStatic(ex, path) match
          case Some(_) => ()
          case None    =>
            val msg = s"Not Found: $path".getBytes("UTF-8")
            ex.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(404, msg.length.toLong)
            ex.getResponseBody.write(msg)
    }
  catch
    case e: Exception =>
      _restLog.error(s"route error: ${e.getMessage}", e)
      _Metrics.http5xx.incrementAndGet()
  finally
    val code = try ex.getResponseCode catch case _: Throwable => -1
    if code >= 400 && code < 500 then _Metrics.http4xx.incrementAndGet()
    else if code >= 500           then _Metrics.http5xx.incrementAndGet()
    val _durMs   = (java.lang.System.nanoTime() - _accessStartNs) / 1_000_000L
    val _effCode = if code < 0 then 0 else code
    val _uaSan   = if _accessUa == null then "" else _accessUa.replace('"', '\'')
    println("http\tip="           + _accessIp +
            "\tmethod="           + _accessMethod +
            "\tpath="             + _accessPath +
            "\tstatus="           + _effCode +
            "\tduration_ms="      + _durMs +
            "\tua=\""             + _uaSan + "\"")
    ex.close()

/** Try to serve a static asset — delegates to `StaticAssetServer.tryServe`
 *  using `_ssc_static_root` as the document root (default `"."`, overridden
 *  by `serve(view, port)` to the emitted SPA temp directory). */
private def _serveStatic(ex: com.sun.net.httpserver.HttpExchange, urlPath: String): Option[Unit] =
  StaticAssetServer.tryServe(ex, urlPath, _ssc_static_root)

private def _contentTypeFor(name: String): String = HttpHelpers.contentTypeFor(name)


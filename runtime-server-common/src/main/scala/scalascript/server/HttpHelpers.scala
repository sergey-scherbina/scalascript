package scalascript.server

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

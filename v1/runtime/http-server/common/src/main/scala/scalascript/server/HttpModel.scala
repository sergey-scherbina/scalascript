package scalascript.server

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
/** What a route handler produced, in both shapes this host has for it.
 *
 *  `std/http.ssc` declares ONE concept — `sse(req)(block)` is typed `: Response` there — and the
 *  split into `Response` (buffered body) and `StreamResponse` (chunked writer) is this host's
 *  implementation detail. They shared no type, so every middleware chain was typed `Any` to hold
 *  both, and on a lane with type inference that made `next().withHeader(...)` unreachable: `Any`
 *  has no members. Nothing is coerced or drained by naming what they already shared.
 *
 *  NOT called `HttpResponse`: that is `java.net.http.HttpResponse` in the preamble's imports, and
 *  the collision produced 41 `Reference to HttpResponse is ambiguous` the moment this trait
 *  appeared. `RouteResult` says what it is and cannot clash. */
sealed trait RouteResult:
  def status: Int
  def headers: Map[String, String]
  def withHeader(name: String, value: String): RouteResult

case class Response(
    status:     Int                         = 200,
    headers:    Map[String, String]         = Map.empty,
    body:       String                      = "",
    setSession: Option[Map[String, String]] = None
) extends RouteResult:
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
) extends RouteResult:
  def withHeader(name: String, value: String): StreamResponse =
    copy(headers = headers + (name -> value))

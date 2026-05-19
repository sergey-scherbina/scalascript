package scalascript.server.spi

import scalascript.server.{Request, Response, StreamResponse}

/** A pluggable HTTP/WS network-layer backend.  Three production impls
 *  exist:
 *
 *    - `scalascript.server.jvm.JdkServerBackend` (default, zero deps —
 *      wraps JDK `HttpServer` + custom per-VT proxy + our shared
 *      `WebSocket` class)
 *    - `scalascript.server.jvm.jetty.JettyServerBackend` (optional,
 *      Jetty 12 — HTTP/2 + mature WS + permessage-deflate)
 *    - `scalascript.server.jvm.netty.NettyServerBackend` (optional,
 *      Netty 4 — highest throughput + HTTP/3 incubator)
 *
 *  The shared runtime (`HttpDispatchLoop`, route registry, middleware
 *  chain, Request / Response POJOs, session / JWT / OAuth shims, WS
 *  framing helpers) sits ABOVE this SPI and drives whichever impl is
 *  on the classpath.
 *
 *  Selection: hybrid `ServiceLoader` discovery — the SPI is looked
 *  up via `ServiceLoader[HttpServerSpi]`; if more than one impl is
 *  present, `setHttpServerBackend(name)` picks by class name; the
 *  built-in JDK impl is the fallback.
 *
 *  Implementations register themselves via
 *  `META-INF/services/scalascript.server.spi.HttpServerSpi`.
 *
 *  Design doc: `docs/http-server-spi-plan.md`. */
trait HttpServerSpi:

  /** Short identifier — used by `setHttpServerBackend(name)` to pick
   *  one impl when multiple are on the classpath.  Convention:
   *  `"jdk"`, `"jetty"`, `"netty"`.  Lowercase, no whitespace. */
  def name: String

  /** Start listening.  `tls` is `None` for plain HTTP, `Some(cfg)`
   *  for HTTPS.  Returns when the server is ready to accept (the
   *  accept loop runs on a background thread inside the impl).
   *
   *  `handler` is the shared above-SPI runtime — knows about routing,
   *  middleware, sessions, etc.  The impl calls into it for every
   *  incoming HTTP request and every WS upgrade attempt. */
  def start(
      port:    Int,
      tls:     Option[TlsConfig],
      handler: HttpHandler
  ): Unit

  /** Stop accepting new connections.  Existing connections are given
   *  a brief grace to drain (impl-defined; typically 1-5 s) before
   *  being force-closed.  Idempotent. */
  def stop(): Unit

  /** True between successful `start` and `stop`. */
  def isRunning: Boolean

  /** The local port the server is bound to.  Useful when `port = 0`
   *  (ephemeral) was passed to `start`.  Returns 0 before `start`. */
  def localPort: Int

/** The shared above-SPI runtime's view from the network layer's
 *  perspective.  The SPI impl calls into this on its own thread; the
 *  handler dispatches user-handler invocation to its own executor
 *  if needed (the interpreter does so for thread-safe `Value` /
 *  `Computation` access). */
trait HttpHandler:

  /** Handle one HTTP request.  The impl has already translated its
   *  library-specific request type into our POJO `Request` (including
   *  parsing headers, cookies, body, multipart, auth) using the
   *  shared `RequestBuilder` helper. */
  def onHttpRequest(req: Request): HttpResult

  /** Decide whether to accept a WS upgrade.  `req` is the same POJO
   *  shape as for HTTP — `method = "GET"`, headers include
   *  `Upgrade: websocket` / `Sec-WebSocket-Key` etc.  Returns either
   *  `Accept` (the SPI will write the 101, then invoke the listener
   *  as frames arrive) or `Reject` (the SPI writes the status response
   *  and closes). */
  def onWsUpgrade(req: Request): WsUpgradeResult

/** Result of an HTTP request — three shapes:
 *
 *    - `PlainResp(resp)`: the common case — full response in memory.
 *    - `StreamResp(resp)`: chunked / SSE output; the SPI invokes
 *      `resp.writer(write)` and writes each chunk as it arrives.
 *    - `Reject(status, body)`: short-circuit (e.g. 413 body-too-large,
 *      400 validation-error).  The SPI writes a minimal text response. */
enum HttpResult:
  case PlainResp(resp: Response)
  case StreamResp(resp: StreamResponse)
  case Reject(status: Int, body: String, contentType: String = "text/plain; charset=utf-8")

/** Result of a WS upgrade.
 *
 *    - `Accept(subprotocol, listener)`: proceed.  The SPI writes the
 *      101 response (with the negotiated subprotocol) and starts
 *      delivering frames to `listener`.  `listener.onOpen(controls)`
 *      is the first call; subsequent calls are `onMessage` / `onBinary`
 *      / `onPong` / `onClose`.
 *    - `Reject(status, reason)`: refuse (404 / 403 / 401 / 503 / 400).
 *      The SPI writes a minimal status response and closes. */
enum WsUpgradeResult:
  case Accept(subprotocol: String, listener: WsListener)
  case Reject(status: Int, reason: String)

/** Per-WS-connection callbacks.  The SPI invokes these as frames
 *  arrive (already-reassembled — every impl handles frame parsing +
 *  fragmentation internally).
 *
 *  `onOpen` is invoked exactly once, before any other callback, with
 *  a `controls` handle for outbound sends.  The handler is expected
 *  to stash `controls` for the duration of the connection (e.g.
 *  pass it to the user's `onWebSocket` body so they can later call
 *  `ws.send`). */
trait WsListener:
  def onOpen(controls: WsControls): Unit
  def onMessage(text: String): Unit
  def onBinary(bytes: Array[Byte]): Unit
  def onPong(payload: Array[Byte]): Unit
  def onClose(code: Int, reason: String): Unit
  def onError(t: Throwable): Unit

/** Per-WS-connection outbound channel.  The handler holds onto this
 *  after `WsListener.onOpen` so user code (e.g. `ws.send("hi")`)
 *  has a target.  Backed by the SPI's internal write path —
 *  bounded queue + writer-VT for JDK, Jetty `Session` for Jetty,
 *  Netty `Channel` for Netty. */
trait WsControls:
  /** Stable per-connection identifier.  UUID-v4 generated at upgrade
   *  time.  Surfaced to user code as `ws.id`. */
  def id: String

  /** The remote peer's address, formatted as `host:port` (e.g.
   *  `"203.0.113.5:54321"`).  `"?"` if not available. */
  def remoteAddress: String

  /** The subprotocol the SPI negotiated during upgrade.  Empty
   *  string if negotiation was skipped (server registered no
   *  subprotocols) or if the client offered none. */
  def subprotocol: String

  def send(text: String): Unit
  def sendBytes(bytes: Array[Byte]): Unit
  def ping(payload: Array[Byte]): Unit
  def close(code: Int = 1000, reason: String = ""): Unit
  def isClosed: Boolean

  /** Blocking receive — pulls the next text frame from the connection,
   *  parked on a virtual thread until one arrives or the peer closes.
   *  Returns `None` on close.  Mutually independent from `WsListener`
   *  callbacks: both fire on every frame.
   *
   *  Used by handshake-driven server-side flows (e.g. the cluster's
   *  `/_ssc-actors` route, where the protocol reads a `{"nodeId":…}`
   *  frame then replies) that are more naturally expressed as a
   *  blocking read than as a callback state machine. */
  def recv(): Option[String]

/** Where to find the certificate + key for HTTPS.  PEM-encoded paths;
 *  PKCS#1 keys are wrapped to PKCS#8 inside the impl via
 *  `runtime-server-common.TlsContextBuilder`. */
final case class TlsConfig(certPemPath: String, keyPemPath: String)

package scalascript.plugin

import scalascript.ir.{Content, SymbolRef, Value}
import upickle.default.ReadWriter

/** Wire protocol for subprocess plugins.
 *
 *  See docs/backend-spi.md §12.2.  Stage 6.1: JSON framing only
 *  (`stdio-json` — newline-delimited JSON, one message per line).
 *  MsgPack framing (`stdio-msgpack`) is a follow-up — same case-class
 *  shapes, different upickle codec.
 *
 *  Shape mirrors JSON-RPC 2.0 (request / response with id, error
 *  envelope) without strict adherence — `jsonrpc` field is omitted.
 *
 *  Message flow (sync over stdio):
 *
 *      core   →   plugin   :  Request as JSON, one per line
 *      plugin →   core     :  Response as JSON, one per line
 *
 *  Plugins MAY write to stderr at any time — core forwards those
 *  bytes verbatim as diagnostic logs (spec §12.2 wire protocol). */

/** Method names recognised by every plugin role. */
object Methods:
  // Common to every plugin
  val Describe = "describe"
  val Shutdown = "shutdown"

  // Backend role
  val Compile = "compile"

  // InteractiveBackend role
  val OpenSession   = "openSession"
  val SessionFeed   = "session.feed"
  val SessionClose  = "session.close"
  val InvokeHandler = "invokeHandler"

  // SourceLanguage role
  val Signatures   = "signatures"
  val CompileBlock = "compileBlock"

  /** Prefix for host-callback methods (plugin → core during compile/feed).
   *  e.g. "host.nowMillis" dispatches to the `nowMillis` host callback. */
  val HostPrefix = "host."

/** One request from core to a subprocess plugin.  `params` is opaque
 *  JSON keyed by method-specific schemas; concrete codecs in
 *  `MessageBodies`. */
case class Request(
  method: String,
  params: ujson.Value = ujson.Obj(),
  id:     Long        = 0L
) derives ReadWriter

/** One response from a subprocess plugin.  Exactly one of `result` or
 *  `error` is populated. */
case class Response(
  id:     Long                  = 0L,
  result: Option[ujson.Value]   = None,
  error:  Option[ResponseError] = None
) derives ReadWriter

case class ResponseError(code: Int, message: String) derives ReadWriter

/** Standard error codes — overlap with JSON-RPC where useful. */
object ErrorCodes:
  val ParseError       = -32700
  val InvalidRequest   = -32600
  val MethodNotFound   = -32601
  val InvalidParams    = -32602
  val InternalError    = -32603
  // SPI-specific
  val PluginCrash      = 1
  val UnsupportedRole  = 2
  val CompileFailure   = 3

/** Strongly-typed bodies for the methods core understands.  Plugins
 *  see them as ujson trees and round-trip via the same codec; core
 *  reads them back into these case classes. */
object MessageBodies:

  /** describe() — plugin → core, exposes id / displayName / version. */
  case class DescribeResult(
    id:              String,
    displayName:     String,
    spiVersion:      String,
    role:            String,                 // "backend" | "source-language" | "both"
    acceptedSources: Set[String] = Set.empty,
    features:        Set[String] = Set.empty,
    outputs:         Set[String] = Set.empty,
    /** True when the plugin supports `openSession` / `session.feed` /
     *  `invokeHandler`.  Stage 6+/B. */
    interactive:     Boolean     = false
  ) derives ReadWriter

  /** compile(ir, opts) — core → plugin.  IR is forwarded as-is via
   *  upickle (the SPI's own NormalizedModule codecs); options as a
   *  flat string map (`BackendOptions.extra` shape).  Stage 6.1 does
   *  not transport NIO `Path` references — plugins receive
   *  `baseDir` as an absolute String. */
  case class CompileParams(
    irJson:  ujson.Value,
    baseDir: Option[String]      = None,
    extra:   Map[String, String] = Map.empty
  ) derives ReadWriter

  /** compile() result — mirrors `CompileResult` enum cases, flattened
   *  into a single message body via a discriminator. */
  case class CompileResultWire(
    kind:        String,                            // "text" | "segmented" | "binary" | "executed" | "failed"
    code:        Option[String]             = None, // text
    language:    Option[String]             = None, // text
    segments:    Option[List[SegmentWire]]  = None, // segmented
    bytes:       Option[Array[Byte]]        = None, // binary
    mime:        Option[String]             = None, // binary
    stdout:      Option[String]             = None, // executed
    stderr:      Option[String]             = None, // executed
    exit:        Option[Int]                = None, // executed
    diagnostics: Option[List[DiagnosticWire]] = None // failed
  ) derives ReadWriter

  case class SegmentWire(kind: String, language: String = "", code: String = "", source: String = "") derives ReadWriter

  case class DiagnosticWire(kind: String, message: String, feature: String = "", backend: String = "") derives ReadWriter

  // ── Interactive session messages (Stage 6+/B) ─────────────────────────

  /** openSession(opts) → sessionId */
  case class OpenSessionParams(baseDir: Option[String] = None, extra: Map[String, String] = Map.empty) derives ReadWriter
  case class OpenSessionResult(sessionId: String) derives ReadWriter

  /** session.feed(sessionId, block) → CompileResultWire */
  case class SessionFeedParams(sessionId: String, block: Content) derives ReadWriter

  /** session.close(sessionId) — no result body */
  case class SessionCloseParams(sessionId: String) derives ReadWriter

  /** invokeHandler(sessionId, handlerRef, args) → Value */
  case class InvokeHandlerParams(sessionId: String, handlerRef: SymbolRef, args: List[Value]) derives ReadWriter
  case class InvokeHandlerResult(value: Value) derives ReadWriter

  // ── HostCallback messages (Stage 6+/C) ───────────────────────────────

  /** Plugin → core: `host.<name>` callback during compile / feed.
   *  Same shape as a normal `Request` — `params` carries this body. */
  case class HostCallbackParams(args: List[Value] = Nil) derives ReadWriter
  /** Core → plugin: reply carrying the host function's return value. */
  case class HostCallbackResult(value: Value) derives ReadWriter

package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** HTTP server + client intrinsics for the JS (Node.js) backend.
 *
 *  All symbols are JavaScript `function` declarations emitted into
 *  `JsRuntimeHttpServer` that JsGen always prepends.  `RuntimeCall` entries
 *  here satisfy `CapabilityCheck` so any program calling these `extern def`s
 *  is accepted by the JS backend. */
val JsHttpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  // HTTP server
  QualifiedName("route")               -> RuntimeCall("route"),
  QualifiedName("tls")                 -> RuntimeCall("tls"),
  QualifiedName("serve")               -> RuntimeCall("serve"),
  QualifiedName("serveAsync")          -> RuntimeCall("serveAsync"),
  QualifiedName("stop")                -> RuntimeCall("stop"),
  // Outbound HTTP client
  QualifiedName("httpGet")             -> RuntimeCall("httpGet"),
  QualifiedName("httpPost")            -> RuntimeCall("httpPost"),
  QualifiedName("httpPut")             -> RuntimeCall("httpPut"),
  QualifiedName("httpPatch")           -> RuntimeCall("httpPatch"),
  QualifiedName("httpDelete")          -> RuntimeCall("httpDelete"),
  QualifiedName("httpGetStream")       -> RuntimeCall("httpGetStream"),
  QualifiedName("httpPostStream")      -> RuntimeCall("httpPostStream"),
  // WebSocket client
  QualifiedName("wsConnect")           -> RuntimeCall("wsConnect"),
  // CORS / gzip / cache
  QualifiedName("cors")                -> RuntimeCall("cors"),
  QualifiedName("useGzip")             -> RuntimeCall("useGzip"),
  QualifiedName("cacheable")           -> RuntimeCall("cacheable"),
  QualifiedName("noCache")             -> RuntimeCall("noCache"),
  // Streaming / SSE
  QualifiedName("streamResponse")      -> RuntimeCall("streamResponse"),
  QualifiedName("sse")                 -> RuntimeCall("sse"),
  // Body / upload limits
  QualifiedName("maxBodySize")         -> RuntimeCall("maxBodySize"),
  QualifiedName("uploadSpoolThreshold")-> RuntimeCall("uploadSpoolThreshold"),
  QualifiedName("uploadDir")           -> RuntimeCall("uploadDir"),
  // Middleware
  QualifiedName("use")                 -> RuntimeCall("use"),
  // HTTP client config. Target the non-shadowed `_`-aliases (ws-server.mjs) so a
  // namespace import (`std.http.httpTimeout`) resolves the real preamble function
  // instead of `undefined` — the identity name is shadowed by the generated
  // namespace `const httpTimeout`. (js-http-config-namespace-tdz.)
  QualifiedName("httpTimeout")         -> RuntimeCall("_httpTimeout"),
  QualifiedName("httpRetry")           -> RuntimeCall("_httpRetry"),
  // WebSocket server
  QualifiedName("onWebSocket")         -> RuntimeCall("onWebSocket"),
  QualifiedName("onWebSocketAuth")     -> RuntimeCall("onWebSocketAuth"),
  // Response builders
  QualifiedName("Response.html")       -> RuntimeCall("Response.html"),
  QualifiedName("Response.text")       -> RuntimeCall("Response.text"),
  QualifiedName("Response.json")       -> RuntimeCall("Response.json"),
  QualifiedName("Response.redirect")   -> RuntimeCall("Response.redirect"),
  QualifiedName("Response.notFound")   -> RuntimeCall("Response.notFound"),
  QualifiedName("Response.status")     -> RuntimeCall("Response.status"),
)

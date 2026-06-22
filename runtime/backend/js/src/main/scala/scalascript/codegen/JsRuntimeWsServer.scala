package scalascript.codegen

/** WebSocket / SSE / CORS JS runtime.  Gated on the `WsServer` capability. */
val JsRuntimeWsServer: String = JsRuntimeResource.load("ws-server.mjs")

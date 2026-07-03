package scalascript.codegen

/** HTTP server JS runtime — `serve` / `route` / sessions / metrics.  Gated on the `HtmlDsl` capability. */
val JsRuntimeHttpServer: String = JsRuntimeResource.load("http-server.mjs")

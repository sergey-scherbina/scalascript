package ssc.plugin.httpfast

import org.scalatest.funsuite.AnyFunSuite

/** Loads the plugin through the real ServiceLoader (as the runtime does) and asserts that
  * `install` runs cleanly — no ownership conflict, no registration throwing — and that the
  * full HTTP + WebSocket intrinsic/tagged-method/field surface lands in the registry. This
  * guards the ssc-facing surface that the engine tests don't exercise. */
class HttpFastPluginInstallTest extends AnyFunSuite:

  test("plugin installs and registers the full HTTP + WebSocket surface") {
    val loaded = ssc.plugin.NativePluginHost.loadAll()
    assert(loaded >= 1, "expected at least the http-fast plugin to load")

    val intrinsics = List(
      "route", "serve", "serveAsync", "stop",
      "httpGet", "httpPost", "httpPut", "httpPatch", "httpDelete",
      "Response.apply", "Response.json", "Response.html", "Response.text",
      "maxBodySize", "onWebSocket", "onWebSocketAuth", "wsConnect", "WsRoom",
      "use", "cors", "useGzip", "sse", "streamResponse",
      "idleTimeout", "maxConnections", "onRequest")
    for name <- intrinsics do
      assert(ssc.V2PluginRegistry.lookup(name).isDefined, s"intrinsic '$name' not registered")

    for m <- List("send", "write", "comment", "close", "isClosed") do
      assert(ssc.V2PluginRegistry.lookupTaggedMethod("HttpStream", m).isDefined, s"stream.$m not registered")

    val wsMethods = List("send", "sendBytes", "close", "ping",
      "onMessage", "onClose", "onPong", "isClosed", "request", "subprotocol", "user")
    for m <- wsMethods do
      assert(ssc.V2PluginRegistry.lookupTaggedMethod("WebSocket", m).isDefined, s"ws.$m not registered")

    for m <- List("add", "remove", "broadcast", "size") do
      assert(ssc.V2PluginRegistry.lookupTaggedMethod("WsRoom", m).isDefined, s"room.$m not registered")

    assert(ssc.V2PluginRegistry.lookupFieldNames("Request").exists(fs =>
      fs.contains("params") && fs.contains("query") && fs.contains("form")))
    assert(ssc.V2PluginRegistry.lookupFieldNames("Response").exists(_.length == 3))
    assert(ssc.V2PluginRegistry.lookupFieldNames("WebSocket").isDefined)
  }

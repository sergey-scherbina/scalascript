package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class TypedRouteDistributedExampleCliTest extends AnyFunSuite:

  private def repoRoot(): os.Path =
    LazyList.iterate(os.pwd)(p => p / os.up)
      .take(8)
      .find(p => os.exists(p / "build.sbt") && os.exists(p / "examples" / "frontend" / "typed-client-distributed" / "typed-client-distributed.ssc"))
      .getOrElse(os.pwd)

  private def examplePath: os.Path =
    repoRoot() / "examples" / "frontend" / "typed-client-distributed" / "typed-client-distributed.ssc"

  test("distributed typed client example renders SPA with raw JS and generated HTTP client"):
    val html = renderSpaHtml(examplePath, Some("http://server.example:49155"))

    assert(html.contains("globalThis.__sscBackendBaseUrl = \"http://server.example:49155\""))
    assert(html.contains("function sscTypedClientDistributedApp()"))
    assert(html.contains("const _ssc_typedRouteClients = ["))
    assert(html.contains("async function _ssc_api_request(methodRaw, pathTemplate, input)"))
    assert(html.contains("const Messages = {"))
    assert(html.contains("""create(input) { return _ssc_api_request("POST", "/api/messages", input); }"""))
    assert(html.contains("""list() { return _ssc_api_request("GET", "/api/messages", undefined); }"""))
    assert(html.contains("""get(input) { return _ssc_api_request("GET", "/api/messages/:id", input); }"""))
    assert(html.contains("const startupMessages = await _dispatch(Messages, 'list', []);"))
    assert(html.contains("Messages.create(CreateMessage(text))"))

  test("same distributed typed client source dispatches server and web client modes"):
    val app = examplePath
    val oldServerHook = runJvmServerHook
    val oldWebHook = runWebClientPreviewHook
    val serverCalls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String, RunBindOptions)]
    val webCalls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String, String, RunBindOptions)]
    try
      ActiveFlags.set(GlobalFlags(backend = Some("jvm")))
      runJvmServerHook = (path, backend, bind) => serverCalls += ((path, backend, bind))
      runWebClientPreviewHook = (path, frontend, serverUrl, bind) => webCalls += ((path, frontend, serverUrl, bind))

      runCommand(List("--mode", "server", "--host", "0.0.0.0", "--port", "49155", app.toString))
      runCommand(List("--mode", "client", "--frontend", "react", "--server-url", "http://server.example:49155", app.toString))

      assert(serverCalls.toList == List((app, "jdk", RunBindOptions(host = "0.0.0.0", port = Some(49155)))))
      assert(webCalls.toList == List((app, "react", "http://server.example:49155", RunBindOptions(host = "0.0.0.0", port = Some(49155)))))
    finally
      runJvmServerHook = oldServerHook
      runWebClientPreviewHook = oldWebHook
      ActiveFlags.set(GlobalFlags())

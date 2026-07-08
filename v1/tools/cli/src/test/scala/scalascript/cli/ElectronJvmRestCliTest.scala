package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.BackendTransportKind
import scalascript.parser.Parser

class ElectronJvmRestCliTest extends AnyFunSuite:

  private def fullStackElectronSource(port: Int): String =
    s"""
       |---
       |frontend: electron
       |---
       |
       |```scalascript
       |route("GET", "/api/items") { Response.json("[]") }
       |serve(lower(tree, defaultTheme), $port, extraCss)
       |```
       |""".stripMargin.trim

  private def fullStackServerSource(port: Int): String =
    s"""
       |---
       |frontend: electron
       |---
       |
       |```scalascript
       |route("GET", "/api/items") { req => Response.text("ok") }
       |serve($port)
       |```
       |""".stripMargin.trim

  private def fullStackTypedClientSource(port: Int): String =
    s"""
       |---
       |frontend: electron
       |apiClients:
       |  Messages:
       |    endpoints:
       |      - name: list
       |        method: GET
       |        path: /api/messages
       |        request: Unit
       |        response: List[Message]
       |      - name: get
       |        method: GET
       |        path: /api/messages/:id
       |        request: Int
       |        response: Message
       |---
       |
       |```javascript
       |globalThis.__sscTypedClientSmoke = function() {
       |  return Messages.list().then(function(rows) { return rows.length; });
       |};
       |```
       |
       |```scalascript
       |case class Message(id: Int, text: String)
       |route("GET", "/api/messages") { req => Response.json("[]") }
       |serve($port)
       |```
       |""".stripMargin.trim

  test("detectServePort reads toolkit serve(view, port) shape"):
    val src =
      """
        |```scalascript
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin.trim
    assert(detectServePort(src).contains(49152))

  test("detectServePort reads server serve(port) shape"):
    val src =
      """
        |```scalascript
        |serve(8088)
        |```
        |""".stripMargin.trim
    assert(detectServePort(src).contains(8088))

  test("detectServePort ignores invalid ports"):
    assert(detectServePort("serve(view, 70000)").isEmpty)

  test("shouldDefaultToElectronJvmRest accepts electron frontend with route and serve"):
    val src =
      """
        |---
        |frontend: electron
        |---
        |
        |```scalascript
        |route("GET", "/api/items") { Response.json("[]") }
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin.trim
    assert(shouldDefaultToElectronJvmRest(Parser.parse(src), src))

  test("shouldDefaultToElectronJvmRest accepts frontmatter routes"):
    val src =
      """
        |---
        |frontend: electron
        |routes:
        |  - method: GET
        |    path: /api/items
        |    handler: listItems
        |---
        |
        |```scalascript
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin.trim
    assert(shouldDefaultToElectronJvmRest(Parser.parse(src), src))

  test("shouldDefaultToElectronJvmRest rejects non-electron frontend"):
    val src =
      """
        |---
        |frontend: react
        |---
        |
        |```scalascript
        |route("GET", "/api/items") { Response.json("[]") }
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin.trim
    assert(!shouldDefaultToElectronJvmRest(Parser.parse(src), src))

  test("shouldDefaultToElectronJvmRest rejects electron app without backend route"):
    val src =
      """
        |---
        |frontend: electron
        |---
        |
        |```scalascript
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin.trim
    assert(!shouldDefaultToElectronJvmRest(Parser.parse(src), src))

  test("target helpers classify desktop-jvm as Electron JVM REST"):
    assert(targetRequestsElectron(Some("desktop-jvm")))
    assert(targetRequestsElectronJvmRest(Some("desktop-jvm"), None))
    assert(targetRequestsElectron(Some("desktop")))
    assert(targetRequestsElectronJvmRest(Some("desktop"), Some("jvm-rest")))
    assert(!targetRequestsElectronJvmRest(Some("desktop"), None))

  test("runCommand dispatches default full-stack electron app to JVM REST hook"):
    val dir = os.temp.dir(prefix = "ssc-electron-default-run-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackElectronSource(49152))
    val oldHook = runElectronJvmRestDevHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String)]
    try
      ActiveFlags.set(GlobalFlags())
      runElectronJvmRestDevHook = (path, backend) => calls += ((path, backend))
      CommandRegistry.dispatch("run", List(app.toString))
      assert(calls.toList == List((app, "jdk")))
    finally
      runElectronJvmRestDevHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand dispatches desktop-jvm target to JVM REST hook"):
    val dir = os.temp.dir(prefix = "ssc-electron-desktop-jvm-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, "```scalascript\nserve(49152)\n```")
    val oldHook = runElectronJvmRestDevHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String)]
    try
      ActiveFlags.set(GlobalFlags(target = Some("desktop-jvm")))
      runElectronJvmRestDevHook = (path, backend) => calls += ((path, backend))
      CommandRegistry.dispatch("run", List(app.toString))
      assert(calls.toList == List((app, "jdk")))
    finally
      runElectronJvmRestDevHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand treats explicit swing frontend as interpreter-only unsupported path"):
    val dir = os.temp.dir(prefix = "ssc-swing-run-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, "```scalascript\nprintln(\"hello\")\n```")
    assert(runRequestsSwingFrontend(Some("swing"), List(app.toString)))

  test("runCommand treats frontmatter swing frontend as interpreter-only unsupported path"):
    val dir = os.temp.dir(prefix = "ssc-swing-frontmatter-run-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |frontend: swing
        |---
        |
        |```scalascript
        |println("hello")
        |```
        |""".stripMargin.trim)
    assert(runRequestsSwingFrontend(None, List(app.toString)))

  test("runCommand does not treat non-swing frontend as Swing interpreter path"):
    val dir = os.temp.dir(prefix = "ssc-swing-desktop-jvm-run-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, "```scalascript\nprintln(\"hello\")\n```")
    assert(!runRequestsSwingFrontend(Some("electron"), List(app.toString)))

  test("runCommand dispatches mode server to JVM server hook"):
    val dir = os.temp.dir(prefix = "ssc-mode-server-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runJvmServerHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String, RunBindOptions)]
    try
      ActiveFlags.set(GlobalFlags(backend = Some("jvm")))
      runJvmServerHook = (path, backend, bind) => calls += ((path, backend, bind))
      CommandRegistry.dispatch("run", List("--mode", "server", app.toString))
      assert(calls.toList == List((app, "jdk", RunBindOptions())))
    finally
      runJvmServerHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("validateTransportSelection allows http transport"):
    assert(validateTransportSelection(None, None, Some(BackendTransportKind.Http)).isRight)
    assert(validateTransportSelection(Some("fullstack"), None, Some(BackendTransportKind.Http)).isRight)

  test("validateTransportSelection rejects in-process for split modes and server-url"):
    assert(validateTransportSelection(Some("server"), None, Some(BackendTransportKind.InProcess)).left.toOption.exists(_.contains("mode server")))
    assert(validateTransportSelection(Some("client"), Some("http://server.example:8080"), Some(BackendTransportKind.InProcess)).left.toOption.exists(_.contains("mode client")))
    assert(validateTransportSelection(None, Some("http://server.example:8080"), Some(BackendTransportKind.InProcess)).left.toOption.exists(_.contains("server-url")))

  test("validateTransportSelection allows in-process for interpreter fullstack and plain run modes"):
    assert(validateTransportSelection(Some("fullstack"), None, Some(BackendTransportKind.InProcess)).isRight)
    assert(validateTransportSelection(None, None, Some(BackendTransportKind.InProcess)).isRight)

  test("validateRunJvmTransportSelection accepts Swing in-process and rejects non-JVM frontends"):
    assert(validateRunJvmTransportSelection(Some("swing"), None).isRight)
    assert(validateRunJvmTransportSelection(Some("swing"), Some(BackendTransportKind.Http)).isRight)
    assert(validateRunJvmTransportSelection(Some("swing"), Some(BackendTransportKind.InProcess)).isRight)
    assert(validateRunJvmTransportSelection(Some("react"), Some(BackendTransportKind.InProcess))
      .left.toOption.exists(_.contains("requires a JVM-hosted frontend")))
    assert(validateRunJvmTransportSelection(None, Some(BackendTransportKind.InProcess))
      .left.toOption.exists(_.contains("planned")))

  test("frontMatterTransport reads nested fullstack transport"):
    val dir = os.temp.dir(prefix = "ssc-transport-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |fullstack:
        |  transport: in-process
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    assert(frontMatterTransport(app).contains(BackendTransportKind.InProcess))

  test("frontMatterTransport reads flat transport"):
    val dir = os.temp.dir(prefix = "ssc-flat-transport-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |transport: http
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    assert(frontMatterTransport(app).contains(BackendTransportKind.Http))

  test("resolveRunTransport rejects invalid frontmatter transport"):
    val dir = os.temp.dir(prefix = "ssc-bad-transport-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |fullstack:
        |  transport: direct
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    assert(resolveRunTransport(app, None).left.toOption.exists(_.contains("direct")))

  test("runCommand passes host and port overrides to server mode"):
    val dir = os.temp.dir(prefix = "ssc-mode-server-host-port-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runJvmServerHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[RunBindOptions]
    try
      ActiveFlags.set(GlobalFlags(backend = Some("jvm")))
      runJvmServerHook = (_, _, bind) => calls += bind
      CommandRegistry.dispatch("run", List(
        "--mode", "server",
        "--host", "127.0.0.1",
        "--port", "49997",
        app.toString
      ))
      assert(calls.toList == List(RunBindOptions(host = "127.0.0.1", port = Some(49997))))
    finally
      runJvmServerHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand reads host and port from frontmatter for server mode"):
    val dir = os.temp.dir(prefix = "ssc-mode-server-host-port-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |host: 127.0.0.1
        |port: 49996
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    val oldHook = runJvmServerHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[RunBindOptions]
    try
      ActiveFlags.set(GlobalFlags(backend = Some("jvm")))
      runJvmServerHook = (_, _, bind) => calls += bind
      CommandRegistry.dispatch("run", List("--mode", "server", app.toString))
      assert(calls.toList == List(RunBindOptions(host = "127.0.0.1", port = Some(49996))))
    finally
      runJvmServerHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand dispatches electron mode client to Electron client hook"):
    val dir = os.temp.dir(prefix = "ssc-mode-client-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runElectronClientDevHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String)]
    try
      ActiveFlags.set(GlobalFlags())
      runElectronClientDevHook = (path, serverUrl) => calls += ((path, serverUrl))
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--frontend", "electron",
        "--server-url", "http://server.example:8080",
        app.toString
      ))
      assert(calls.toList == List((app, "http://server.example:8080")))
    finally
      runElectronClientDevHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand dispatches web mode client to preview hook"):
    val dir = os.temp.dir(prefix = "ssc-web-client-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runWebClientPreviewHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String, String, RunBindOptions)]
    try
      ActiveFlags.set(GlobalFlags())
      runWebClientPreviewHook = (path, frontend, serverUrl, bind) => calls += ((path, frontend, serverUrl, bind))
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--frontend", "react",
        "--server-url", "http://server.example:8080",
        app.toString
      ))
      assert(calls.toList == List((app, "react", "http://server.example:8080", RunBindOptions())))
    finally
      runWebClientPreviewHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand passes host and port overrides to web preview"):
    val dir = os.temp.dir(prefix = "ssc-web-client-host-port-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runWebClientPreviewHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[RunBindOptions]
    try
      ActiveFlags.set(GlobalFlags())
      runWebClientPreviewHook = (_, _, _, bind) => calls += bind
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--frontend", "react",
        "--server-url", "http://server.example:8080",
        "--host", "127.0.0.1",
        "--port", "49999",
        app.toString
      ))
      assert(calls.toList == List(RunBindOptions(host = "127.0.0.1", port = Some(49999))))
    finally
      runWebClientPreviewHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand open-browser flag overrides web preview default"):
    val dir = os.temp.dir(prefix = "ssc-web-client-open-browser-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runWebClientPreviewHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[Boolean]
    try
      ActiveFlags.set(GlobalFlags())
      runWebClientPreviewHook = (_, _, _, bind) => calls += bind.openBrowser
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--frontend", "react",
        "--server-url", "http://server.example:8080",
        "--open-browser",
        app.toString
      ))
      assert(calls.toList == List(true))
    finally
      runWebClientPreviewHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand reads open-browser from frontmatter for web preview"):
    val dir = os.temp.dir(prefix = "ssc-web-client-open-browser-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |frontend: react
        |open-browser: true
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    val oldHook = runWebClientPreviewHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(String, Boolean)]
    try
      ActiveFlags.set(GlobalFlags())
      runWebClientPreviewHook = (_, frontend, _, bind) => calls += ((frontend, bind.openBrowser))
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--server-url", "http://server.example:8080",
        app.toString
      ))
      assert(calls.toList == List(("react", true)))
    finally
      runWebClientPreviewHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand reads host and port from frontmatter for web preview"):
    val dir = os.temp.dir(prefix = "ssc-web-client-host-port-frontmatter-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |frontend: react
        |host: 127.0.0.1
        |port: 49998
        |---
        |
        |```scalascript
        |serve(49152)
        |```
        |""".stripMargin.trim)
    val oldHook = runWebClientPreviewHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[RunBindOptions]
    try
      ActiveFlags.set(GlobalFlags())
      runWebClientPreviewHook = (_, _, _, bind) => calls += bind
      CommandRegistry.dispatch("run", List(
        "--mode", "client",
        "--server-url", "http://server.example:8080",
        app.toString
      ))
      assert(calls.toList == List(RunBindOptions(host = "127.0.0.1", port = Some(49998))))
    finally
      runWebClientPreviewHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("injectDesktopTokenMiddleware appends SSC_DESKTOP_TOKEN middleware to script"):
    val script = "route(\"GET\", \"/api\") { _ => \"ok\" }"
    val patched = injectDesktopTokenMiddleware(script)
    assert(patched.contains(script))
    assert(patched.contains("SSC_DESKTOP_TOKEN"))
    assert(patched.contains("use { (req, next) =>"))
    assert(patched.contains("x-scalascript-desktop-token"))
    assert(patched.contains("Response(401"))

  test("rewritePlainServePort overrides simple server serve literal"):
    assert(rewritePlainServePort("serve(8080)", 9090) == "serve(9090)")
    assert(rewritePlainServePort("serve(lower(tree), 8080)", 9090) == "serve(lower(tree), 8080)")

  test("emit-spa server-url injects backend base URL"):
    val dir = os.temp.dir(prefix = "ssc-emit-spa-server-url-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app,
      """
        |---
        |name: spa-server-url-test
        |---
        |
        |```javascript
        |fetch("/api/items")
        |```
        |""".stripMargin.trim)
    val out = java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      CommandRegistry.dispatch("emit-spa", List("--server-url", "http://server.example:8080", app.toString))
    }
    val html = out.toString("UTF-8")
    assert(html.contains("globalThis.__sscBackendBaseUrl = \"http://server.example:8080\""))
    assert(html.contains("globalThis.__sscBackendBaseUrl"))
    assert(html.contains("new URL(rawPath, String(globalThis.__sscBackendBaseUrl)).toString()"))

  test("runElectronClientDev launches generated Electron bundle with server URL"):
    val dir = os.temp.dir(prefix = "ssc-electron-client-smoke-", deleteOnExit = true)
    val app = dir / "app.ssc"
    val log = dir / "events.log"
    val serverUrl = "http://server.example:8080"
    os.write(app, fullStackServerSource(49152))

    val fakeElectron = dir / "electron"
    os.write(
      fakeElectron,
      s"""#!/bin/sh
         |set -eu
         |if [ "$${1:-}" = "--version" ]; then
         |  echo "v99.0.0"
         |  exit 0
         |fi
         |bundle="$${1:?bundle dir required}"
         |echo "electron-client $$bundle" >> "$log"
         |test -f "$$bundle/app.js"
         |grep -q "__sscBackendBaseUrl" "$$bundle/app.js"
         |grep -q "$serverUrl" "$$bundle/app.js"
         |""".stripMargin
    )
    os.perms.set(fakeElectron, "rwxr--r--")

    val oldElectron = electronCommand
    try
      electronCommand = fakeElectron.toString
      runElectronClientDev(app, serverUrl)
      assert(os.read(log).contains("electron-client "))
    finally
      electronCommand = oldElectron

  test("runElectronJvmRestDev starts fake backend and launches generated Electron bundle"):
    val pythonOk =
      scala.util.Try(os.proc("python3", "--version").call(check = false).exitCode == 0)
        .getOrElse(false)
    if !pythonOk then cancel("python3 not on PATH; needed for fake TCP backend")

    val port = 49153
    val dir = os.temp.dir(prefix = "ssc-electron-jvm-rest-smoke-", deleteOnExit = true)
    val app = dir / "app.ssc"
    val log = dir / "events.log"
    os.write(app, fullStackServerSource(port))

    val fakeScalaCli = dir / "scala-cli"
    os.write(
      fakeScalaCli,
      s"""#!/bin/sh
         |set -eu
         |echo "scala-cli $$@" >> "$log"
         |python3 - <<'PY' &
         |import socket
         |s = socket.socket()
         |s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
         |s.bind(("127.0.0.1", $port))
         |s.listen(16)
         |try:
         |    while True:
         |        conn, addr = s.accept()
         |        conn.close()
         |finally:
         |    s.close()
         |PY
         |pid=$$!
         |trap 'kill "$$pid" 2>/dev/null || true' TERM INT EXIT
         |wait "$$pid"
         |""".stripMargin
    )
    os.perms.set(fakeScalaCli, "rwxr--r--")

    val fakeElectron = dir / "electron"
    os.write(
      fakeElectron,
      s"""#!/bin/sh
         |set -eu
         |if [ "$${1:-}" = "--version" ]; then
         |  echo "v99.0.0"
         |  exit 0
         |fi
         |bundle="$${1:?bundle dir required}"
         |echo "electron $$bundle" >> "$log"
         |test -f "$$bundle/app.js"
         |grep -q "__sscBackendBaseUrl" "$$bundle/app.js"
         |grep -q "http://127.0.0.1:$port" "$$bundle/app.js"
         |""".stripMargin
    )
    os.perms.set(fakeElectron, "rwxr--r--")

    val oldScalaCli = scalaCliCommand
    val oldElectron = electronCommand
    try
      scalaCliCommand = fakeScalaCli.toString
      electronCommand = fakeElectron.toString
      runElectronJvmRestDev(app, "jdk")
      val events = os.read(log)
      assert(events.contains("scala-cli run "))
      assert(events.contains("electron "))
    finally
      scalaCliCommand = oldScalaCli
      electronCommand = oldElectron

  test("runElectronJvmRestDev injects desktop security token into Electron bundle"):
    val pythonOk =
      scala.util.Try(os.proc("python3", "--version").call(check = false).exitCode == 0)
        .getOrElse(false)
    if !pythonOk then cancel("python3 not on PATH; needed for fake TCP backend")

    val port = 49155
    val dir = os.temp.dir(prefix = "ssc-electron-token-smoke-", deleteOnExit = true)
    val app = dir / "app.ssc"
    val log = dir / "events.log"
    os.write(app, fullStackServerSource(port))

    val fakeScalaCli = dir / "scala-cli"
    os.write(
      fakeScalaCli,
      s"""#!/bin/sh
         |set -eu
         |echo "scala-cli $$@" >> "$log"
         |python3 - <<'PY' &
         |import socket
         |s = socket.socket()
         |s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
         |s.bind(("127.0.0.1", $port))
         |s.listen(16)
         |try:
         |    while True:
         |        conn, addr = s.accept()
         |        conn.close()
         |finally:
         |    s.close()
         |PY
         |pid=$$!
         |trap 'kill "$$pid" 2>/dev/null || true' TERM INT EXIT
         |wait "$$pid"
         |""".stripMargin
    )
    os.perms.set(fakeScalaCli, "rwxr--r--")

    val fakeElectron = dir / "electron"
    os.write(
      fakeElectron,
      s"""#!/bin/sh
         |set -eu
         |if [ "$${1:-}" = "--version" ]; then
         |  echo "v99.0.0"
         |  exit 0
         |fi
         |bundle="$${1:?bundle dir required}"
         |echo "electron-token $$bundle" >> "$log"
         |grep -q "__sscDesktopToken" "$$bundle/app.js"
         |grep -q "x-scalascript-desktop-token" "$$bundle/app.js"
         |""".stripMargin
    )
    os.perms.set(fakeElectron, "rwxr--r--")

    val oldScalaCli = scalaCliCommand
    val oldElectron = electronCommand
    try
      scalaCliCommand = fakeScalaCli.toString
      electronCommand = fakeElectron.toString
      runElectronJvmRestDev(app, "jdk")
      val events = os.read(log)
      assert(events.contains("electron-token "))
    finally
      scalaCliCommand = oldScalaCli
      electronCommand = oldElectron

  test("runElectronJvmRestDev bundles typed route HTTP clients for Electron"):
    val pythonOk =
      scala.util.Try(os.proc("python3", "--version").call(check = false).exitCode == 0)
        .getOrElse(false)
    if !pythonOk then cancel("python3 not on PATH; needed for fake TCP backend")

    val port = 49154
    val dir = os.temp.dir(prefix = "ssc-electron-typed-client-smoke-", deleteOnExit = true)
    val app = dir / "app.ssc"
    val log = dir / "events.log"
    os.write(app, fullStackTypedClientSource(port))

    val fakeScalaCli = dir / "scala-cli"
    os.write(
      fakeScalaCli,
      s"""#!/bin/sh
         |set -eu
         |echo "scala-cli $$@" >> "$log"
         |python3 - <<'PY' &
         |import socket
         |s = socket.socket()
         |s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
         |s.bind(("127.0.0.1", $port))
         |s.listen(16)
         |try:
         |    while True:
         |        conn, addr = s.accept()
         |        conn.close()
         |finally:
         |    s.close()
         |PY
         |pid=$$!
         |trap 'kill "$$pid" 2>/dev/null || true' TERM INT EXIT
         |wait "$$pid"
         |""".stripMargin
    )
    os.perms.set(fakeScalaCli, "rwxr--r--")

    val fakeElectron = dir / "electron"
    os.write(
      fakeElectron,
      s"""#!/bin/sh
         |set -eu
         |if [ "$${1:-}" = "--version" ]; then
         |  echo "v99.0.0"
         |  exit 0
         |fi
         |bundle="$${1:?bundle dir required}"
         |echo "electron-typed-client $$bundle" >> "$log"
         |test -f "$$bundle/app.js"
         |grep -q "__sscBackendBaseUrl" "$$bundle/app.js"
         |grep -q "http://127.0.0.1:$port" "$$bundle/app.js"
         |grep -q "const _ssc_typedRouteClients" "$$bundle/app.js"
         |grep -q "async function _ssc_api_request" "$$bundle/app.js"
         |grep -q "await fetch(url, init)" "$$bundle/app.js"
         |grep -q "const Messages = {" "$$bundle/app.js"
         |grep -F -q 'list(headers, cancelToken) { return _ssc_api_request("GET", "/api/messages", undefined, "Unit", "List[Message]", headers, cancelToken); }' "$$bundle/app.js"
         |grep -F -q 'get(input, headers, cancelToken) { return _ssc_api_request("GET", "/api/messages/:id", input, "Int", "Message", headers, cancelToken); }' "$$bundle/app.js"
         |grep -q "__sscTypedClientSmoke" "$$bundle/app.js"
         |""".stripMargin
    )
    os.perms.set(fakeElectron, "rwxr--r--")

    val oldScalaCli = scalaCliCommand
    val oldElectron = electronCommand
    try
      scalaCliCommand = fakeScalaCli.toString
      electronCommand = fakeElectron.toString
      runElectronJvmRestDev(app, "jdk")
      val events = os.read(log)
      assert(events.contains("scala-cli run "))
      assert(events.contains("electron-typed-client "))
    finally
      scalaCliCommand = oldScalaCli
      electronCommand = oldElectron

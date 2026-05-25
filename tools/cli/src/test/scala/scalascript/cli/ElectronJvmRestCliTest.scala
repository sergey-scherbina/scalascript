package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
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
      runCommand(List(app.toString))
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
      runCommand(List(app.toString))
      assert(calls.toList == List((app, "jdk")))
    finally
      runElectronJvmRestDevHook = oldHook
      ActiveFlags.set(GlobalFlags())

  test("runCommand dispatches mode server to JVM server hook"):
    val dir = os.temp.dir(prefix = "ssc-mode-server-test-", deleteOnExit = true)
    val app = dir / "app.ssc"
    os.write(app, fullStackServerSource(49152))
    val oldHook = runJvmServerHook
    val calls = scala.collection.mutable.ArrayBuffer.empty[(os.Path, String)]
    try
      ActiveFlags.set(GlobalFlags(backend = Some("jvm")))
      runJvmServerHook = (path, backend) => calls += ((path, backend))
      runCommand(List("--mode", "server", app.toString))
      assert(calls.toList == List((app, "jdk")))
    finally
      runJvmServerHook = oldHook
      ActiveFlags.set(GlobalFlags())

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

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

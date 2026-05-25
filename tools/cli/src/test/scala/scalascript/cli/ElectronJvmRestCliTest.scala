package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class ElectronJvmRestCliTest extends AnyFunSuite:

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

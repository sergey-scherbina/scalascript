package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

class ElectronJvmRestCliTest extends AnyFunSuite:

  test("detectServePort reads toolkit serve(view, port) shape"):
    val src =
      """
        |```scalascript
        |serve(lower(tree, defaultTheme), 49152, extraCss)
        |```
        |""".stripMargin
    assert(detectServePort(src).contains(49152))

  test("detectServePort reads server serve(port) shape"):
    val src =
      """
        |```scalascript
        |serve(8088)
        |```
        |""".stripMargin
    assert(detectServePort(src).contains(8088))

  test("detectServePort ignores invalid ports"):
    assert(detectServePort("serve(view, 70000)").isEmpty)

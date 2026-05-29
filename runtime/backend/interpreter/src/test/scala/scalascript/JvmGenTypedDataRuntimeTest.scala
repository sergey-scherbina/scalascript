package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class JvmGenTypedDataRuntimeTest extends AnyFunSuite:

  test("JVM codegen adds typed-data runtime jar when user code imports typeddata"):
    val source =
      """# Test
        |
        |```scala
        |import scalascript.typeddata.ObjectCodec
        |
        |case class Draft(id: String) derives ObjectCodec
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))
    assert(code.contains("scalascript-backend-typed-data-runtime"))
    assert(code.contains("scalascript-wire-core"))

  test("JVM codegen adds wire-core jar when user code imports wire runtime"):
    val source =
      """# Test
        |
        |```scala
        |import scalascript.wire.WireFormat
        |
        |val fmt = WireFormat.Cbor
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))
    assert(code.contains("scalascript-wire-core"))

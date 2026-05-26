package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class JvmGenObjectStoreRuntimeTest extends AnyFunSuite:

  test("JVM codegen adds JDBC runtime and ObjectStore facade when ObjectStore is used"):
    val source =
      """---
        |databases:
        |  default:
        |    url: "jdbc:h2:mem:object-store-codegen"
        |---
        |
        |# Test
        |
        |```scala
        |import scalascript.typeddata.{ObjectCodec, key}
        |case class Draft(@key id: String, title: String) derives ObjectCodec
        |ObjectStore.put("default", "drafts", Draft("d1", "Plan"))
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("""com.lihaoyi::ujson:4.4.2"""))
    assert(code.contains("scalascript-backend-sql-runtime"))
    assert(code.contains("object ObjectStore:"))
    assert(code.contains("scalascript.sql.ObjectStoreRuntime.put"))

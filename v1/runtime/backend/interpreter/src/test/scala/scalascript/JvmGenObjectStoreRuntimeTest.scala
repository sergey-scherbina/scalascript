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

  test("JVM codegen generates REST sync routes for objectStores front matter"):
    val source =
      """---
        |databases:
        |  default:
        |    url: "jdbc:h2:mem:object-store-sync-codegen"
        |objectStores:
        |  drafts:
        |    type: Draft
        |    sync: client-server
        |    database: default
        |    key: id
        |---
        |
        |# Test
        |
        |```scala
        |import scalascript.typeddata.{ObjectCodec, key}
        |case class Draft(@key id: String, title: String) derives ObjectCodec
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("""route("GET", "/__ssc/sync/drafts/changes")"""))
    assert(code.contains("""route("POST", "/__ssc/sync/drafts/push")"""))
    assert(code.contains("scalascript.sql.ObjectStoreRuntime.changes[Draft]"))
    assert(code.contains("scalascript.sql.ObjectStoreRuntime.decodeAny[Draft]"))
    assert(code.contains("scalascript-backend-sql-runtime"))

  test("JVM codegen carries objectStores conflict policy into sync push route"):
    val source =
      """---
        |databases:
        |  default:
        |    url: "jdbc:h2:mem:object-store-sync-policy-codegen"
        |objectStores:
        |  drafts:
        |    type: Draft
        |    sync: client-server
        |    conflict: client-wins
        |---
        |
        |# Test
        |
        |```scala
        |import scalascript.typeddata.{ObjectCodec, key}
        |case class Draft(@key id: String, title: String) derives ObjectCodec
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("val policy = \"client-wins\""))
    assert(code.contains("""if policy == "client-wins" then"""))
    assert(code.contains("""if policy == "server-wins" then"""))

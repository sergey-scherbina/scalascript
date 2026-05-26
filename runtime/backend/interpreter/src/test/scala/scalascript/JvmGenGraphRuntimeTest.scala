package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class JvmGenGraphRuntimeTest extends AnyFunSuite:

  test("JVM codegen emits graph runtime jar and Graph facade for graphs front matter"):
    val source =
      """---
        |graphs:
        |  deps:
        |    model: property
        |    side: server
        |    backend: in-memory
        |---
        |
        |# Test
        |
        |```scala
        |import scalascript.typeddata.{JsonCodec, VertexCodec, key, graphLabel}
        |@graphLabel("Module")
        |case class Module(@key id: String, path: String) derives JsonCodec, VertexCodec
        |Graph.putVertex("deps", Module("A", "a.ssc"))
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("scalascript-backend-graph-runtime"))
    assert(code.contains("val _ssc_graph_registry"))
    assert(code.contains("\"deps\" -> scalascript.graph.GraphRuntime.inMemory()"))
    assert(code.contains("object Graph:"))
    assert(code.contains("def putVertex[A](graphName: String, value: A)"))

  test("JVM codegen emits a default in-memory graph when Graph is used without graphs front matter"):
    val source =
      """# Test
        |
        |```scala
        |Graph.neighborValues("default", "A")
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("scalascript-backend-graph-runtime"))
    assert(code.contains("\"default\" -> scalascript.graph.GraphRuntime.inMemory()"))

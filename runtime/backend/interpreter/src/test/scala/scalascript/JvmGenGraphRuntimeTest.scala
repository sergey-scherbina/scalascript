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

  test("JVM codegen emits embedded graph adapters and external runtime deps"):
    val source =
      """---
        |graphs:
        |  deps:
        |    model: property
        |    side: server
        |    backend: embedded-tinkergraph
        |  kg:
        |    model: rdf
        |    side: server
        |    backend: rdf4j-memory
        |---
        |
        |# Test
        |
        |```scala
        |Graph.neighborValues("deps", "A")
        |Graph.triples("kg")
        |```
        |""".stripMargin

    val code = JvmGen.generate(Parser.parse(source))

    assert(code.contains("""//> using dep "org.apache.tinkerpop:tinkergraph-gremlin:3.8.1""""))
    assert(code.contains("""//> using dep "org.eclipse.rdf4j:rdf4j-repository-sail:5.3.1""""))
    assert(code.contains("""//> using dep "org.eclipse.rdf4j:rdf4j-sail-memory:5.3.1""""))
    assert(code.contains("\"deps\" -> scalascript.graph.GraphRuntime.tinkerGraph()"))
    assert(code.contains("\"kg\" -> scalascript.graph.GraphRuntime.rdf4jMemory()"))

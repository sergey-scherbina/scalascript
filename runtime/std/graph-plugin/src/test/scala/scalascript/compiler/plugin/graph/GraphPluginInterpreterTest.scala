package scalascript.compiler.plugin.graph

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class GraphPluginInterpreterTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(GraphPlugin()))

  test("Graph plugin stores vertices, edges, and typed neighbors in isolation"):
    val result = interp.eval(
      """
      case class Module(id: String, path: String)
      case class Imports(from: String, to: String, reason: String)

      Graph.putVertex("deps", Module("A", "a.ssc"))
      Graph.putVertex("deps", Module("B", "b.ssc"))
      Graph.putEdge("deps", Imports("A", "B", "direct"))
      Graph.neighbors("deps", "A", Some("imports")).map(_.path).mkString(",")
      """
    )

    assert(result == "b.ssc")

  test("Graph plugin stores and loads RDF-shaped values in isolation"):
    val result = interp.eval(
      """
      case class Person(id: String, name: String)
      Graph.putRdf("kg", Person("urn:person:1", "Ada"))
      val loaded = Graph.getRdf("kg", "urn:person:1").get
      val triples = Graph.triples("kg")
      loaded.name + ":" + triples.size
      """
    )

    assert(result == "Ada:1")

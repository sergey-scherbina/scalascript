package ssc.plugin.graph

import org.scalatest.funsuite.AnyFunSuite
import ssc.{Prims, V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

final class GraphNativePluginTest extends AnyFunSuite:
  private val graph = Value.DataV("Graph", Vector.empty)
  private val sparql = Value.DataV("Sparql", Vector.empty)

  private def install(): Unit =
    NativePluginHost.installProviders(List(GraphNativePlugin()))
    V2PluginRegistry.registerFieldNames("Module", Vector("id", "path"))
    V2PluginRegistry.registerFieldNames("Imports", Vector("from", "to", "reason"))
    V2PluginRegistry.registerFieldNames("Person", Vector("id", "name", "age"))

  private def call(receiver: Value, name: String, args: Value*): Value =
    Prims.methodOp(name, receiver, args.toList)

  private def module(id: String, path: String): Value =
    Value.DataV("Module", Vector(Value.StrV(id), Value.StrV(path)))

  private def imports(from: String, to: String): Value =
    Value.DataV("Imports", Vector(Value.StrV(from), Value.StrV(to), Value.StrV("direct")))

  private def person(id: String, name: String, age: Long): Value =
    Value.DataV("Person", Vector(Value.StrV(id), Value.StrV(name), Value.IntV(age)))

  private def items(value: Value): List[Value] = Prims.unlistPub(value)

  test("vertices replace in place and graphs remain isolated") {
    install()
    call(graph, "putVertex", Value.StrV("deps"), module("A", "old"))
    call(graph, "putVertex", Value.StrV("deps"), module("B", "b.ssc"))
    call(graph, "putVertex", Value.StrV("deps"), module("A", "a.ssc"))
    assert(items(call(graph, "vertices", Value.StrV("deps"))) ==
      List(module("A", "a.ssc"), module("B", "b.ssc")))
    assert(call(graph, "getVertex", Value.StrV("other"), Value.StrV("A")) ==
      Value.DataV("None", Vector.empty))
  }

  test("edges validate endpoints, allocate stable ids, and filter ordered neighbors") {
    install()
    call(graph, "putVertex", Value.StrV("deps"), module("A", "a.ssc"))
    call(graph, "putVertex", Value.StrV("deps"), module("B", "b.ssc"))
    call(graph, "putVertex", Value.StrV("deps"), module("C", "c.ssc"))
    val first = call(graph, "putEdge", Value.StrV("deps"), imports("A", "B"))
    val second = call(graph, "putEdge", Value.StrV("deps"), imports("A", "C"))
    assert(first.asInstanceOf[Value.DataV].fields.head == Value.StrV("A-imports-B"))
    assert(second.asInstanceOf[Value.DataV].fields.head == Value.StrV("A-imports-C"))
    val label = Value.DataV("Some", Vector(Value.StrV("imports")))
    assert(items(call(graph, "neighbors", Value.StrV("deps"), Value.StrV("A"), label)) ==
      List(module("B", "b.ssc"), module("C", "c.ssc")))
    val error = intercept[IllegalArgumentException] {
      call(graph, "putEdge", Value.StrV("deps"), imports("missing", "A"))
    }
    assert(error.getMessage.contains("source vertex does not exist"))
  }

  test("RDF replacement, subjects, and predicate filters are deterministic") {
    install()
    call(graph, "putRdf", Value.StrV("kg"), person("urn:1", "Old", 1))
    call(graph, "putRdf", Value.StrV("kg"), person("urn:2", "Bob", 2))
    call(graph, "putRdf", Value.StrV("kg"), person("urn:1", "Ada", 3))
    val iri = Value.DataV("Iri", Vector(Value.StrV("urn:1")))
    assert(call(graph, "getRdf", Value.StrV("kg"), iri) ==
      Value.DataV("Some", Vector(person("urn:1", "Ada", 3))))
    val none = Value.DataV("None", Vector.empty)
    val name = Value.DataV("Some", Vector(Value.StrV("name")))
    val triples = items(call(graph, "triples", Value.StrV("kg"), none, name))
    assert(triples == List(
      Value.DataV("RdfTriple", Vector(Value.StrV("urn:1"), Value.StrV("name"), Value.StrV("Ada"))),
      Value.DataV("RdfTriple", Vector(Value.StrV("urn:2"), Value.StrV("name"), Value.StrV("Bob")))))
  }

  test("remote diagnostics are exact and reinstall clears state") {
    install()
    call(graph, "putVertex", Value.StrV("deps"), module("A", "a.ssc"))
    val error = intercept[UnsupportedOperationException] {
      call(sparql, "select", Value.StrV("kg"), Value.StrV("SELECT * WHERE {}"))
    }
    assert(error.getMessage.contains("explicit rdf4j-memory or rdf4j-http backend"))
    assert(V2PluginRegistry.lookup("Graph.unknown").isEmpty)
    install()
    assert(items(call(graph, "vertices", Value.StrV("deps"))).isEmpty)
  }

package scalascript.graph

import org.scalatest.funsuite.AnyFunSuite
import scalascript.typeddata.*

class GraphRuntimeTest extends AnyFunSuite:

  @graphLabel("Module")
  final case class ModuleVertex(@key id: String, path: String) derives JsonCodec, VertexCodec

  @graphEdge("imports")
  final case class ImportEdge(@graphFrom from: String, @graphTo to: String, reason: String) derives JsonCodec, EdgeCodec

  @rdfClass("schema:Person")
  final case class PersonRdf(@rdfId id: String, @rdf("schema:name") name: String) derives JsonCodec, RdfCodec

  test("in-memory property graph stores typed vertices, edges, and neighbors"):
    val graph = GraphRuntime.inMemory()
    GraphRuntime.putVertex(graph, ModuleVertex("A", "a.ssc"))
    GraphRuntime.putVertex(graph, ModuleVertex("B", "b.ssc"))
    val edge = GraphRuntime.putEdge(graph, ImportEdge("A", "B", "direct"))

    assert(edge.id == "A-imports-B")
    assert(GraphRuntime.getVertex[ModuleVertex](graph, "A") == Some(ModuleVertex("A", "a.ssc")))
    assert(GraphRuntime.vertices[ModuleVertex](graph).map(_.id) == Vector("A", "B"))
    assert(GraphRuntime.edges[ImportEdge](graph) == Vector(ImportEdge("A", "B", "direct")))
    assert(graph.neighbors("A", Some("imports")).map(_.id) == Vector("B"))

  test("in-memory property graph rejects edges with missing endpoints"):
    val graph = GraphRuntime.inMemory()
    GraphRuntime.putVertex(graph, ModuleVertex("A", "a.ssc"))

    val error = intercept[GraphRuntimeError]:
      GraphRuntime.putEdge(graph, ImportEdge("A", "B", "direct"))
    assert(error.message == "edge to vertex does not exist: B")

  test("in-memory RDF graph stores typed RDF subjects and triples"):
    val graph = GraphRuntime.inMemory()
    val subject = RdfNode.Iri("urn:person:1")
    GraphRuntime.putRdf(graph, PersonRdf("urn:person:1", "Ada"))

    assert(graph.subjects().contains(subject))
    assert(graph.triples(subject = Some(subject), predicate = Some("schema:name")) == Vector(
      RdfTriple(subject, "schema:name", RdfNode.Literal(JsonValue.Str("Ada")))
    ))
    assert(GraphRuntime.getRdf[PersonRdf](graph, subject) == Some(PersonRdf("urn:person:1", "Ada")))

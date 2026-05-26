package scalascript.typeddata

import org.scalatest.funsuite.AnyFunSuite

class GraphCodecTest extends AnyFunSuite:

  @graphLabel("Module")
  final case class ModuleVertex(@key id: String, path: String, language: String = "ssc") derives JsonCodec, VertexCodec

  @graphEdge("imports")
  final case class ImportEdge(@graphFrom from: String, @graphTo to: String, reason: String) derives JsonCodec, EdgeCodec

  @rdfClass("schema:Person")
  final case class PersonRdf(
      @rdfId id: String,
      @rdf("schema:name") name: String,
      @rdf("schema:active") active: Boolean = true
  ) derives JsonCodec, RdfCodec

  test("derived VertexCodec maps case classes to labels, ids, and properties"):
    val value = ModuleVertex("A", "a.ssc")
    val vertex = VertexCodec[ModuleVertex].encode(value)

    assert(vertex == VertexValue(
      id = "A",
      labels = Set("Module"),
      properties = Map(
        "path" -> JsonValue.Str("a.ssc"),
        "language" -> JsonValue.Str("ssc")
      )
    ))
    assert(VertexCodec[ModuleVertex].id(value) == "A")
    assert(VertexCodec[ModuleVertex].decode(vertex) == Right(value))

  test("derived EdgeCodec maps endpoints, labels, and properties"):
    val value = ImportEdge("A", "B", "direct")
    val edge = EdgeCodec[ImportEdge].encode(value)

    assert(edge == EdgeValue(
      id = None,
      from = "A",
      to = "B",
      label = "imports",
      properties = Map("reason" -> JsonValue.Str("direct"))
    ))
    assert(EdgeCodec[ImportEdge].from(value) == "A")
    assert(EdgeCodec[ImportEdge].to(value) == "B")
    assert(EdgeCodec[ImportEdge].decode(edge) == Right(value))

  test("derived RdfCodec maps case classes to typed triples"):
    val value = PersonRdf("urn:person:1", "Ada")
    val rdf = RdfCodec[PersonRdf].encode(value)

    assert(rdf.subject == RdfNode.Iri("urn:person:1"))
    assert(rdf.triples.contains(RdfTriple(
      RdfNode.Iri("urn:person:1"),
      RdfCodec.RdfType,
      RdfNode.Iri("schema:Person")
    )))
    assert(rdf.triples.contains(RdfTriple(
      RdfNode.Iri("urn:person:1"),
      "schema:name",
      RdfNode.Literal(JsonValue.Str("Ada"))
    )))
    assert(RdfCodec[PersonRdf].decode(rdf) == Right(value))

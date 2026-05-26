package scalascript.graph

import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import scalascript.typeddata.*
import org.apache.tinkerpop.gremlin.structure.{Direction, Edge, T, Vertex}
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.eclipse.rdf4j.model.{BNode, IRI, Literal, Resource, Value}
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore

enum GraphModel:
  case Property, Rdf

enum GraphQueryLanguage:
  case Portable, Gremlin, Cypher, Sparql

final case class GraphCapabilities(
    models: Set[GraphModel],
    queryLanguages: Set[GraphQueryLanguage],
    embedded: Boolean,
    persistent: Boolean,
    remote: Boolean
):
  def supports(model: GraphModel): Boolean = models.contains(model)
  def supports(language: GraphQueryLanguage): Boolean = queryLanguages.contains(language)

final case class StoredEdge(id: String, value: EdgeValue)

final case class GraphRuntimeError(message: String) extends RuntimeException(message)

trait PropertyGraphBackend:
  def capabilities: GraphCapabilities
  def putVertex(value: VertexValue): VertexValue
  def getVertex(id: String): Option[VertexValue]
  def vertices(label: Option[String] = None): Vector[VertexValue]
  def deleteVertex(id: String): Boolean
  def putEdge(value: EdgeValue): StoredEdge
  def getEdge(id: String): Option[StoredEdge]
  def edges(label: Option[String] = None, from: Option[String] = None, to: Option[String] = None): Vector[StoredEdge]
  def deleteEdge(id: String): Boolean
  def neighbors(from: String, edgeLabel: Option[String] = None): Vector[VertexValue]

trait RdfGraphBackend:
  def capabilities: GraphCapabilities
  def put(value: RdfValue): RdfValue
  def triples(subject: Option[RdfNode] = None, predicate: Option[String] = None): Vector[RdfTriple]
  def subjects(predicate: Option[String] = None): Vector[RdfNode]
  def deleteSubject(subject: RdfNode): Boolean

trait GraphBackend extends PropertyGraphBackend with RdfGraphBackend

object GraphRuntime:
  val InMemoryCapabilities: GraphCapabilities =
    GraphCapabilities(
      models = Set(GraphModel.Property, GraphModel.Rdf),
      queryLanguages = Set(GraphQueryLanguage.Portable),
      embedded = true,
      persistent = false,
      remote = false
    )

  val TinkerGraphCapabilities: GraphCapabilities =
    GraphCapabilities(
      models = Set(GraphModel.Property),
      queryLanguages = Set(GraphQueryLanguage.Portable, GraphQueryLanguage.Gremlin),
      embedded = true,
      persistent = false,
      remote = false
    )

  val Rdf4jMemoryCapabilities: GraphCapabilities =
    GraphCapabilities(
      models = Set(GraphModel.Rdf),
      queryLanguages = Set(GraphQueryLanguage.Portable, GraphQueryLanguage.Sparql),
      embedded = true,
      persistent = false,
      remote = false
    )

  def inMemory(): GraphBackend = new InMemoryGraphBackend
  def tinkerGraph(): GraphBackend = new TinkerGraphBackend
  def rdf4jMemory(): GraphBackend = new Rdf4jMemoryGraphBackend

  def putVertex[A](backend: PropertyGraphBackend, value: A)(using codec: VertexCodec[A]): VertexValue =
    backend.putVertex(codec.encode(value))

  def getVertex[A](backend: PropertyGraphBackend, id: String)(using codec: VertexCodec[A]): Option[A] =
    backend.getVertex(id).map(decodeVertex[A](id, _))

  def vertices[A](backend: PropertyGraphBackend)(using codec: VertexCodec[A]): Vector[A] =
    backend.vertices(Some(codec.label)).map(vertex => decodeVertex[A](vertex.id, vertex))

  def putEdge[A](backend: PropertyGraphBackend, value: A)(using codec: EdgeCodec[A]): StoredEdge =
    backend.putEdge(codec.encode(value))

  def edges[A](backend: PropertyGraphBackend)(using codec: EdgeCodec[A]): Vector[A] =
    backend.edges(label = Some(codec.label)).map(edge => decodeEdge[A](edge.id, edge.value))

  def putRdf[A](backend: RdfGraphBackend, value: A)(using codec: RdfCodec[A]): RdfValue =
    backend.put(codec.encode(value))

  def getRdf[A](backend: RdfGraphBackend, subject: RdfNode)(using codec: RdfCodec[A]): Option[A] =
    val value = RdfValue(subject, backend.triples(subject = Some(subject)))
    if value.triples.isEmpty then None
    else
      codec.decode(value) match
        case Right(decoded) => Some(decoded)
        case Left(error) => throw GraphRuntimeError(s"RDF decode failed for ${renderRdfNode(subject)}: ${error.render}")

  private def decodeVertex[A](id: String, value: VertexValue)(using codec: VertexCodec[A]): A =
    codec.decode(value) match
      case Right(decoded) => decoded
      case Left(error) => throw GraphRuntimeError(s"vertex decode failed for $id: ${error.render}")

  private def decodeEdge[A](id: String, value: EdgeValue)(using codec: EdgeCodec[A]): A =
    codec.decode(value) match
      case Right(decoded) => decoded
      case Left(error) => throw GraphRuntimeError(s"edge decode failed for $id: ${error.render}")

  private def renderRdfNode(node: RdfNode): String = node match
    case RdfNode.Iri(value) => value
    case RdfNode.Blank(id) => s"_:$id"
    case RdfNode.Literal(value) => JsonValue.kind(value)

final class InMemoryGraphBackend extends GraphBackend:
  def capabilities: GraphCapabilities = GraphRuntime.InMemoryCapabilities

  private val vertexById = mutable.LinkedHashMap.empty[String, VertexValue]
  private val edgeById = mutable.LinkedHashMap.empty[String, StoredEdge]
  private val rdfBySubject = mutable.LinkedHashMap.empty[RdfNode, Vector[RdfTriple]]

  def putVertex(value: VertexValue): VertexValue =
    vertexById.update(value.id, value)
    value

  def getVertex(id: String): Option[VertexValue] =
    vertexById.get(id)

  def vertices(label: Option[String]): Vector[VertexValue] =
    vertexById.valuesIterator.filter { vertex =>
      label.forall(vertex.labels.contains)
    }.toVector

  def deleteVertex(id: String): Boolean =
    val removed = vertexById.remove(id).isDefined
    if removed then
      val dangling = edgeById.valuesIterator
        .filter(edge => edge.value.from == id || edge.value.to == id)
        .map(_.id)
        .toVector
      dangling.foreach(edgeById.remove)
    removed

  def putEdge(value: EdgeValue): StoredEdge =
    requireVertex(value.from, "from")
    requireVertex(value.to, "to")
    val id = value.id.getOrElse(nextEdgeId(value))
    val stored = StoredEdge(id, value.copy(id = Some(id)))
    edgeById.update(id, stored)
    stored

  def getEdge(id: String): Option[StoredEdge] =
    edgeById.get(id)

  def edges(label: Option[String], from: Option[String], to: Option[String]): Vector[StoredEdge] =
    edgeById.valuesIterator.filter { edge =>
      label.forall(_ == edge.value.label) &&
      from.forall(_ == edge.value.from) &&
      to.forall(_ == edge.value.to)
    }.toVector

  def deleteEdge(id: String): Boolean =
    edgeById.remove(id).isDefined

  def neighbors(from: String, edgeLabel: Option[String]): Vector[VertexValue] =
    edges(label = edgeLabel, from = Some(from), to = None).flatMap(edge => vertexById.get(edge.value.to))

  def put(value: RdfValue): RdfValue =
    val merged = (rdfBySubject.getOrElse(value.subject, Vector.empty) ++ value.triples).distinct
    rdfBySubject.update(value.subject, merged)
    value.copy(triples = merged)

  def triples(subject: Option[RdfNode], predicate: Option[String]): Vector[RdfTriple] =
    val source = subject match
      case Some(subject) => rdfBySubject.getOrElse(subject, Vector.empty)
      case None => rdfBySubject.valuesIterator.flatten.toVector
    source.filter(triple => predicate.forall(_ == triple.predicate))

  def subjects(predicate: Option[String]): Vector[RdfNode] =
    triples(subject = None, predicate = predicate).map(_.subject).distinct

  def deleteSubject(subject: RdfNode): Boolean =
    rdfBySubject.remove(subject).isDefined

  private def requireVertex(id: String, role: String): Unit =
    if !vertexById.contains(id) then
      throw GraphRuntimeError(s"edge $role vertex does not exist: $id")

  private def nextEdgeId(value: EdgeValue): String =
    val base = s"${value.from}-${value.label}-${value.to}"
    var candidate = base
    var index = 1
    while edgeById.contains(candidate) do
      index += 1
      candidate = s"$base-$index"
    candidate

final class TinkerGraphBackend private (graph: TinkerGraph) extends GraphBackend:
  def this() = this(TinkerGraph.open())

  def capabilities: GraphCapabilities = GraphRuntime.TinkerGraphCapabilities

  def putVertex(value: VertexValue): VertexValue =
    getVertexHandle(value.id).foreach(_.remove())
    val vertex = graph.addVertex(vertexArgs(value)*)
    vertexToValue(vertex)

  def getVertex(id: String): Option[VertexValue] =
    getVertexHandle(id).map(vertexToValue)

  def vertices(label: Option[String]): Vector[VertexValue] =
    graph.traversal().V().toList.asScala.iterator
      .map(vertexToValue)
      .filter(vertex => label.forall(vertex.labels.contains))
      .toVector

  def deleteVertex(id: String): Boolean =
    getVertexHandle(id) match
      case Some(vertex) =>
        vertex.remove()
        true
      case None => false

  def putEdge(value: EdgeValue): StoredEdge =
    val from = getVertexHandle(value.from).getOrElse(throw GraphRuntimeError(s"edge from vertex does not exist: ${value.from}"))
    val to = getVertexHandle(value.to).getOrElse(throw GraphRuntimeError(s"edge to vertex does not exist: ${value.to}"))
    val id = value.id.getOrElse(nextEdgeId(value))
    getEdgeHandle(id).foreach(_.remove())
    val edge = from.addEdge(value.label, to, edgeArgs(value.copy(id = Some(id)))*)
    StoredEdge(id, edgeToValue(edge))

  def getEdge(id: String): Option[StoredEdge] =
    getEdgeHandle(id).map(edge => StoredEdge(id, edgeToValue(edge)))

  def edges(label: Option[String], from: Option[String], to: Option[String]): Vector[StoredEdge] =
    graph.traversal().E().toList.asScala.iterator
      .map(edge => StoredEdge(edge.id().toString, edgeToValue(edge)))
      .filter(edge =>
        label.forall(_ == edge.value.label) &&
          from.forall(_ == edge.value.from) &&
          to.forall(_ == edge.value.to)
      )
      .toVector

  def deleteEdge(id: String): Boolean =
    getEdgeHandle(id) match
      case Some(edge) =>
        edge.remove()
        true
      case None => false

  def neighbors(from: String, edgeLabel: Option[String]): Vector[VertexValue] =
    getVertexHandle(from)
      .map { vertex =>
        vertex.edges(Direction.OUT).asScala
          .filter(edge => edgeLabel.forall(_ == edge.label()))
          .map(edge => vertexToValue(edge.inVertex()))
          .toVector
      }
      .getOrElse(Vector.empty)

  def put(value: RdfValue): RdfValue =
    throw GraphRuntimeError("TinkerGraph backend does not support RDF operations")

  def triples(subject: Option[RdfNode], predicate: Option[String]): Vector[RdfTriple] =
    throw GraphRuntimeError("TinkerGraph backend does not support RDF operations")

  def subjects(predicate: Option[String]): Vector[RdfNode] =
    throw GraphRuntimeError("TinkerGraph backend does not support RDF operations")

  def deleteSubject(subject: RdfNode): Boolean =
    throw GraphRuntimeError("TinkerGraph backend does not support RDF operations")

  private def getVertexHandle(id: String): Option[Vertex] =
    val traversal = graph.traversal().V(id)
    try Option(traversal.tryNext().orElse(null))
    finally traversal.close()

  private def getEdgeHandle(id: String): Option[Edge] =
    val traversal = graph.traversal().E(id)
    try Option(traversal.tryNext().orElse(null))
    finally traversal.close()

  private def vertexArgs(value: VertexValue): Seq[AnyRef] =
    val label = value.labels.toVector.sorted.headOption.getOrElse("Vertex")
    val properties = value.properties.toVector.flatMap { case (key, json) => Seq(key, json) }
    (Seq(T.id, value.id, T.label, label, "_ssc_labels", value.labels.toVector.sorted.mkString(",")) ++ properties)
      .map(_.asInstanceOf[AnyRef])

  private def edgeArgs(value: EdgeValue): Seq[AnyRef] =
    val properties = value.properties.toVector.flatMap { case (key, json) => Seq(key, json) }
    (Seq(T.id, value.id.getOrElse(nextEdgeId(value))) ++ properties).map(_.asInstanceOf[AnyRef])

  private def vertexToValue(vertex: Vertex): VertexValue =
    val properties = vertex.properties[Any]().asScala.map(property => property.key() -> property.value()).toMap
    val labels = properties
      .get("_ssc_labels")
      .collect { case raw: String => raw.split(",").iterator.filter(_.nonEmpty).toSet }
      .getOrElse(Set(vertex.label()))
    VertexValue(
      id = vertex.id().toString,
      labels = labels,
      properties = properties.view.filterKeys(_ != "_ssc_labels").mapValues(anyToJson).toMap
    )

  private def edgeToValue(edge: Edge): EdgeValue =
    EdgeValue(
      id = Some(edge.id().toString),
      from = edge.outVertex().id().toString,
      to = edge.inVertex().id().toString,
      label = edge.label(),
      properties = edge.properties[Any]().asScala.map(property => property.key() -> anyToJson(property.value())).toMap
    )

  private def anyToJson(value: Any): JsonValue =
    value match
      case json: JsonValue => json
      case value: String => JsonValue.Str(value)
      case value: java.lang.Boolean => JsonValue.Bool(value.booleanValue())
      case value: java.lang.Byte => JsonValue.Num(BigDecimal(value.toString))
      case value: java.lang.Short => JsonValue.Num(BigDecimal(value.toString))
      case value: java.lang.Integer => JsonValue.Num(BigDecimal(value.toString))
      case value: java.lang.Long => JsonValue.Num(BigDecimal(value.toString))
      case value: java.lang.Float => JsonValue.Num(BigDecimal(value.toString))
      case value: java.lang.Double => JsonValue.Num(BigDecimal(value.toString))
      case value: java.math.BigDecimal => JsonValue.Num(BigDecimal(value))
      case other => JsonValue.Str(other.toString)

  private def nextEdgeId(value: EdgeValue): String =
    val base = s"${value.from}-${value.label}-${value.to}"
    var candidate = base
    var index = 1
    while getEdgeHandle(candidate).isDefined do
      index += 1
      candidate = s"$base-$index"
    candidate

final class Rdf4jMemoryGraphBackend private (repository: SailRepository) extends GraphBackend:
  def this() =
    this(SailRepository(MemoryStore()))

  repository.init()

  def capabilities: GraphCapabilities = GraphRuntime.Rdf4jMemoryCapabilities

  def put(value: RdfValue): RdfValue =
    val conn = repository.getConnection
    try
      value.triples.foreach { triple =>
        conn.add(asResource(triple.subject), iri(triple.predicate), asRdf4jValue(triple.obj))
      }
      value
    finally conn.close()

  def triples(subject: Option[RdfNode], predicate: Option[String]): Vector[RdfTriple] =
    val conn = repository.getConnection
    try
      val statements = conn.getStatements(subject.map(asResource).orNull, predicate.map(iri).orNull, null)
      try
        statements.iterator().asScala.map(statement =>
          RdfTriple(fromRdf4jResource(statement.getSubject), statement.getPredicate.stringValue(), fromRdf4jValue(statement.getObject))
        ).toVector
      finally statements.close()
    finally conn.close()

  def subjects(predicate: Option[String]): Vector[RdfNode] =
    triples(subject = None, predicate = predicate).map(_.subject).distinct

  def deleteSubject(subject: RdfNode): Boolean =
    val conn = repository.getConnection
    try
      val before = triples(Some(subject), None).nonEmpty
      conn.remove(asResource(subject), null, null)
      before
    finally conn.close()

  def putVertex(value: VertexValue): VertexValue =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def getVertex(id: String): Option[VertexValue] =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def vertices(label: Option[String]): Vector[VertexValue] =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def deleteVertex(id: String): Boolean =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def putEdge(value: EdgeValue): StoredEdge =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def getEdge(id: String): Option[StoredEdge] =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def edges(label: Option[String], from: Option[String], to: Option[String]): Vector[StoredEdge] =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def deleteEdge(id: String): Boolean =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  def neighbors(from: String, edgeLabel: Option[String]): Vector[VertexValue] =
    throw GraphRuntimeError("RDF4J memory backend does not support property-graph operations")

  private def iri(value: String): IRI =
    repository.getValueFactory.createIRI(value)

  private def asResource(node: RdfNode): Resource =
    node match
      case RdfNode.Iri(value) => iri(value)
      case RdfNode.Blank(id) => repository.getValueFactory.createBNode(id)
      case RdfNode.Literal(_) => throw GraphRuntimeError("RDF literal cannot be used as a subject")

  private def asRdf4jValue(node: RdfNode): Value =
    node match
      case RdfNode.Iri(value) => iri(value)
      case RdfNode.Blank(id) => repository.getValueFactory.createBNode(id)
      case RdfNode.Literal(JsonValue.Str(value)) => repository.getValueFactory.createLiteral(value)
      case RdfNode.Literal(JsonValue.Bool(value)) => repository.getValueFactory.createLiteral(value)
      case RdfNode.Literal(JsonValue.Num(value)) => repository.getValueFactory.createLiteral(value.bigDecimal)
      case RdfNode.Literal(other) => repository.getValueFactory.createLiteral(JsonValue.kind(other), iri(XsdString))

  private def fromRdf4jResource(value: Resource): RdfNode =
    value match
      case iri: IRI => RdfNode.Iri(iri.stringValue())
      case blank: BNode => RdfNode.Blank(blank.getID)
      case other => RdfNode.Iri(other.stringValue())

  private def fromRdf4jValue(value: Value): RdfNode =
    value match
      case iri: IRI => RdfNode.Iri(iri.stringValue())
      case blank: BNode => RdfNode.Blank(blank.getID)
      case literal: Literal if literal.getDatatype.stringValue() == XsdBoolean =>
        RdfNode.Literal(JsonValue.Bool(literal.booleanValue()))
      case literal: Literal if literal.getDatatype.stringValue() == XsdDecimal || literal.getDatatype.stringValue() == XsdInteger =>
        RdfNode.Literal(JsonValue.Num(BigDecimal(literal.getLabel)))
      case literal: Literal => RdfNode.Literal(JsonValue.Str(literal.getLabel))
      case other => RdfNode.Iri(other.stringValue())

  private val XsdString = "http://www.w3.org/2001/XMLSchema#string"
  private val XsdBoolean = "http://www.w3.org/2001/XMLSchema#boolean"
  private val XsdDecimal = "http://www.w3.org/2001/XMLSchema#decimal"
  private val XsdInteger = "http://www.w3.org/2001/XMLSchema#integer"

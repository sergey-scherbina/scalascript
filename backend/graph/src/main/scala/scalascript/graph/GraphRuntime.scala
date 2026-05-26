package scalascript.graph

import scala.collection.mutable
import scalascript.typeddata.*

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

  def inMemory(): GraphBackend = new InMemoryGraphBackend

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

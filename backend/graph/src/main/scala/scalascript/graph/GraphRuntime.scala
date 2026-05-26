package scalascript.graph

import scala.jdk.CollectionConverters.*
import scala.collection.mutable
import scalascript.typeddata.*
import org.apache.tinkerpop.gremlin.structure.{Direction, Edge, T, Vertex}
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph
import org.eclipse.rdf4j.model.{BNode, IRI, Literal, Resource, Value}
import org.eclipse.rdf4j.query.QueryLanguage as Rdf4jQueryLanguage
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.neo4j.driver.{AuthTokens, GraphDatabase as Neo4jDatabase}
import org.neo4j.driver.types.{Node as Neo4jNode, Relationship as Neo4jRelationship}

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
  def cypherQuery(query: String, params: Map[String, Any] = Map.empty): Vector[Map[String, JsonValue]] =
    throw GraphRuntimeError(s"${getClass.getSimpleName} does not support Cypher queries")

trait RdfGraphBackend:
  def capabilities: GraphCapabilities
  def put(value: RdfValue): RdfValue
  def triples(subject: Option[RdfNode] = None, predicate: Option[String] = None): Vector[RdfTriple]
  def subjects(predicate: Option[String] = None): Vector[RdfNode]
  def deleteSubject(subject: RdfNode): Boolean
  def sparqlSelect(query: String): Vector[Map[String, RdfNode]]

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

  val Neo4jCapabilities: GraphCapabilities =
    GraphCapabilities(
      models = Set(GraphModel.Property),
      queryLanguages = Set(GraphQueryLanguage.Portable, GraphQueryLanguage.Cypher),
      embedded = false,
      persistent = true,
      remote = true
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
  def neo4j(uri: String, user: String, password: String): Neo4jGraphBackend =
    new Neo4jGraphBackend(uri, user, password)

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

  def sparqlSelect(backend: RdfGraphBackend, query: String): Vector[Map[String, RdfNode]] =
    backend.sparqlSelect(query)

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

  def sparqlSelect(query: String): Vector[Map[String, RdfNode]] =
    throw GraphRuntimeError("in-memory graph backend does not support SPARQL queries")

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

  def sparqlSelect(query: String): Vector[Map[String, RdfNode]] =
    throw GraphRuntimeError("TinkerGraph backend does not support SPARQL queries")

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

  def sparqlSelect(query: String): Vector[Map[String, RdfNode]] =
    val conn = repository.getConnection
    try
      val result = conn.prepareTupleQuery(Rdf4jQueryLanguage.SPARQL, query).evaluate()
      try
        result.iterator().asScala.map { bindingSet =>
          bindingSet.getBindingNames.asScala.iterator
            .flatMap { name =>
              Option(bindingSet.getValue(name)).map(value => name -> fromRdf4jValue(value))
            }
            .toMap
        }.toVector
      finally result.close()
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

final class Neo4jGraphBackend(uri: String, user: String, password: String) extends PropertyGraphBackend:
  private val EnvRef = """\$\{env:([A-Za-z_][A-Za-z0-9_]*)\}""".r
  private def resolveRef(s: String): String =
    EnvRef.replaceAllIn(s, m => sys.env.getOrElse(m.group(1),
      throw GraphRuntimeError(s"Neo4j: environment variable '${m.group(1)}' is not set")))
  private val driver = Neo4jDatabase.driver(resolveRef(uri), AuthTokens.basic(resolveRef(user), resolveRef(password)))

  def close(): Unit = driver.close()

  def capabilities: GraphCapabilities = GraphRuntime.Neo4jCapabilities

  def putVertex(value: VertexValue): VertexValue =
    val primaryLabel = value.labels.toVector.sorted.headOption.getOrElse("Vertex")
    Neo4jGraphBackend.validateIdentifier(primaryLabel)
    val allLabels = value.labels.toVector.sorted.mkString(",")
    val props = (value.properties.view.mapValues(jsonToNeo4j).toMap ++
      Map("_ssc_id" -> value.id, "_ssc_labels" -> allLabels)).asJava
    val query = s"MERGE (n:$primaryLabel {_ssc_id: $$id}) SET n = $$props RETURN n"
    withSession { session =>
      session.executeWrite { tx =>
        tx.run(query, java.util.Map.of("id", value.id, "props", props)).consume()
      }
    }
    value

  def getVertex(id: String): Option[VertexValue] =
    withSession { session =>
      session.executeRead { tx =>
        val result = tx.run("MATCH (n {_ssc_id: $id}) RETURN n LIMIT 1",
          java.util.Map.of("id", id))
        if result.hasNext then Some(neo4jNodeToVertex(result.next().get("n").asNode()))
        else None
      }
    }

  def vertices(label: Option[String]): Vector[VertexValue] =
    withSession { session =>
      session.executeRead { tx =>
        val query = label match
          case Some(l) =>
            Neo4jGraphBackend.validateIdentifier(l)
            s"MATCH (n:$l) RETURN n"
          case None => "MATCH (n) WHERE n._ssc_id IS NOT NULL RETURN n"
        tx.run(query).list().asScala.map(r => neo4jNodeToVertex(r.get("n").asNode())).toVector
      }
    }

  def deleteVertex(id: String): Boolean =
    withSession { session =>
      session.executeWrite { tx =>
        val result = tx.run("MATCH (n {_ssc_id: $id}) DETACH DELETE n RETURN count(n) AS cnt",
          java.util.Map.of("id", id))
        result.single().get("cnt").asLong() > 0
      }
    }

  def putEdge(value: EdgeValue): StoredEdge =
    Neo4jGraphBackend.validateIdentifier(value.label)
    val eid = value.id.getOrElse(s"${value.from}-${value.label}-${value.to}")
    val props = (value.properties.view.mapValues(jsonToNeo4j).toMap ++
      Map("_ssc_id" -> eid, "_ssc_from" -> value.from, "_ssc_to" -> value.to)).asJava
    val query =
      s"""MATCH (a {{_ssc_id: $$from}}), (b {{_ssc_id: $$to}})
         |MERGE (a)-[r:${value.label} {{_ssc_id: $$eid}}]->(b)
         |SET r = $$props
         |RETURN r""".stripMargin
    withSession { session =>
      session.executeWrite { tx =>
        tx.run(query, java.util.Map.of("from", value.from, "to", value.to, "eid", eid, "props", props)).consume()
      }
    }
    StoredEdge(eid, value.copy(id = Some(eid)))

  def getEdge(id: String): Option[StoredEdge] =
    withSession { session =>
      session.executeRead { tx =>
        val result = tx.run(
          "MATCH (a)-[r {_ssc_id: $id}]->(b) RETURN r, a._ssc_id AS from, b._ssc_id AS to LIMIT 1",
          java.util.Map.of("id", id))
        if result.hasNext then
          val row = result.next()
          Some(neo4jRelToEdge(row.get("r").asRelationship(), row.get("from").asString(), row.get("to").asString()))
        else None
      }
    }

  def edges(label: Option[String], from: Option[String], to: Option[String]): Vector[StoredEdge] =
    withSession { session =>
      session.executeRead { tx =>
        val labelClause = label.map { l => Neo4jGraphBackend.validateIdentifier(l); s":$l" }.getOrElse("")
        val fromClause = from.map(_ => " WHERE a._ssc_id = $from").getOrElse("")
        val toClause = to.map(_ => if fromClause.isEmpty then " WHERE b._ssc_id = $to" else " AND b._ssc_id = $to").getOrElse("")
        val query = s"MATCH (a)-[r$labelClause]->(b) WHERE r._ssc_id IS NOT NULL$fromClause$toClause RETURN r, a._ssc_id AS from, b._ssc_id AS to"
        val params = java.util.HashMap[String, AnyRef]()
        from.foreach(v => params.put("from", v))
        to.foreach(v => params.put("to", v))
        tx.run(query, params).list().asScala.map { row =>
          neo4jRelToEdge(row.get("r").asRelationship(), row.get("from").asString(), row.get("to").asString())
        }.toVector
      }
    }

  def deleteEdge(id: String): Boolean =
    withSession { session =>
      session.executeWrite { tx =>
        val result = tx.run("MATCH ()-[r {_ssc_id: $id}]->() DELETE r RETURN count(r) AS cnt",
          java.util.Map.of("id", id))
        result.single().get("cnt").asLong() > 0
      }
    }

  def neighbors(from: String, edgeLabel: Option[String]): Vector[VertexValue] =
    withSession { session =>
      session.executeRead { tx =>
        val labelClause = edgeLabel.map { l => Neo4jGraphBackend.validateIdentifier(l); s":$l" }.getOrElse("")
        val query = s"MATCH (a {_ssc_id: $$from})-[$labelClause]->(b) WHERE b._ssc_id IS NOT NULL RETURN b"
        tx.run(query, java.util.Map.of("from", from)).list().asScala
          .map(r => neo4jNodeToVertex(r.get("b").asNode())).toVector
      }
    }

  override def cypherQuery(query: String, params: Map[String, Any] = Map.empty): Vector[Map[String, JsonValue]] =
    withSession { session =>
      session.executeRead { tx =>
        val javaParams = params.view.mapValues(anyToNeo4j).toMap.asJava
        tx.run(query, javaParams).list().asScala.map { record =>
          record.keys().asScala.iterator.flatMap { key =>
            val v = record.get(key)
            if v.isNull then None
            else Some(key -> neo4jValueToJson(v))
          }.toMap
        }.toVector
      }
    }

  private def withSession[A](f: org.neo4j.driver.Session => A): A =
    val session = driver.session()
    try f(session)
    finally session.close()

  private def neo4jNodeToVertex(node: Neo4jNode): VertexValue =
    val props = node.asMap().asScala.view
      .filterKeys(k => k != "_ssc_id" && k != "_ssc_labels")
      .mapValues(anyToJson)
      .toMap
    val labels = Option(node.get("_ssc_labels"))
      .filter(!_.isNull)
      .map(_.asString().split(",").iterator.filter(_.nonEmpty).toSet)
      .getOrElse(node.labels().asScala.toSet)
    VertexValue(id = node.get("_ssc_id").asString(node.elementId()), labels = labels, properties = props)

  private def neo4jRelToEdge(rel: Neo4jRelationship, from: String, to: String): StoredEdge =
    val props = rel.asMap().asScala.view
      .filterKeys(k => k != "_ssc_id" && k != "_ssc_from" && k != "_ssc_to")
      .mapValues(anyToJson)
      .toMap
    val eid = rel.get("_ssc_id").asString(rel.elementId())
    StoredEdge(eid, EdgeValue(id = Some(eid), from = from, to = to, label = rel.`type`(), properties = props))

  private def anyToJson(value: Any): JsonValue = value match
    case null => JsonValue.Null
    case v: String => JsonValue.Str(v)
    case v: java.lang.Boolean => JsonValue.Bool(v.booleanValue())
    case v: java.lang.Number => JsonValue.Num(BigDecimal(v.toString))
    case v: java.util.List[?] => JsonValue.Arr(v.asScala.map(anyToJson).toVector)
    case v: java.util.Map[?, ?] =>
      JsonValue.Obj(v.asScala.iterator.map { case (k, vv) => k.toString -> anyToJson(vv) }.toMap)
    case other => JsonValue.Str(other.toString)

  private def neo4jValueToJson(v: org.neo4j.driver.Value): JsonValue =
    if v.isNull then JsonValue.Null
    else v.`type`().name() match
      case "BOOLEAN" => JsonValue.Bool(v.asBoolean())
      case "INTEGER" | "FLOAT" => JsonValue.Num(BigDecimal(v.asNumber().toString))
      case "STRING" => JsonValue.Str(v.asString())
      case "LIST" => JsonValue.Arr(v.asList().asScala.map(anyToJson).toVector)
      case "MAP" => JsonValue.Obj(v.asMap().asScala.map { case (k, vv) => k -> anyToJson(vv) }.toMap)
      case "NULL" => JsonValue.Null
      case _ => JsonValue.Str(v.asString())

  private def jsonToNeo4j(json: JsonValue): AnyRef = json match
    case JsonValue.Str(v) => v
    case JsonValue.Bool(v) => java.lang.Boolean.valueOf(v)
    case JsonValue.Num(v) =>
      if v.isValidLong then java.lang.Long.valueOf(v.toLongExact)
      else java.lang.Double.valueOf(v.toDouble)
    case JsonValue.Null => null
    case JsonValue.Arr(items) => items.map(jsonToNeo4j).asJava
    case JsonValue.Obj(fields) => fields.view.mapValues(jsonToNeo4j).toMap.asJava

  private def anyToNeo4j(value: Any): AnyRef = value match
    case v: String => v
    case v: Boolean => java.lang.Boolean.valueOf(v)
    case v: Int => java.lang.Long.valueOf(v.toLong)
    case v: Long => java.lang.Long.valueOf(v)
    case v: Double => java.lang.Double.valueOf(v)
    case v: Float => java.lang.Double.valueOf(v.toDouble)
    case v: BigDecimal =>
      if v.isValidLong then java.lang.Long.valueOf(v.toLongExact)
      else java.lang.Double.valueOf(v.toDouble)
    case null => null
    case other => other.toString

object Neo4jGraphBackend:
  private val ValidIdentifier = """[A-Za-z_][A-Za-z0-9_]*""".r
  def validateIdentifier(name: String): Unit =
    if !ValidIdentifier.matches(name) then
      throw GraphRuntimeError(s"'$name' is not a valid Neo4j identifier; use only letters, digits, and underscores")

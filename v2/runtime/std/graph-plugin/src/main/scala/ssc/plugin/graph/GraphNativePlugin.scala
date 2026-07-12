package ssc.plugin.graph

import scala.collection.mutable
import ssc.{Prims, V2PluginRegistry, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Core-free deterministic process-local property/RDF graph runtime. */
final class GraphNativePlugin extends NativePlugin:
  def id: String = "62-graph"

  private final case class Edge(
      id: String,
      value: Value,
      from: String,
      to: String,
      label: String)
  private final case class RdfRecord(subject: String, value: Value)
  private final class Store:
    val vertices = mutable.LinkedHashMap.empty[String, Value]
    val edges = mutable.LinkedHashMap.empty[String, Edge]
    val rdf = mutable.LinkedHashMap.empty[String, RdfRecord]

  private val stores = mutable.LinkedHashMap.empty[String, Store]

  private def store(name: String): Store = stores.synchronized {
    stores.getOrElseUpdate(name, Store())
  }

  private def list(values: IterableOnce[Value]): Value =
    val vector = Vector.from(values)
    vector.reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def option(value: Option[Value]): Value = value
    .map(item => Value.DataV("Some", Vector(item)))
    .getOrElse(Value.DataV("None", Vector.empty))

  private def text(value: Value, operation: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"$operation expects String")

  private def field(value: Value, name: String): Option[Value] = value match
    case Value.DataV(tag, fields) =>
      V2PluginRegistry.lookupFieldNames(tag, fields.length).flatMap { names =>
        val index = names.indexOf(name)
        if index >= 0 && index < fields.length then Some(fields(index)) else None
      }
    case Value.MapV(entries) => entries.get(Value.StrV(name))
    case _ => None

  private def fieldText(value: Value, name: String): Option[String] =
    field(value, name).flatMap {
      case Value.StrV(result) => Some(result)
      case _ => None
    }

  private def requireFieldText(value: Value, name: String, operation: String): String =
    fieldText(value, name).getOrElse(throw new IllegalArgumentException(
      s"$operation requires a String '$name' field"))

  private def edgeLabel(value: Value): String = value match
    case Value.DataV(tag, _) =>
      val base = tag.stripSuffix("Edge")
      if base.isEmpty then "edge" else s"${base.head.toLower}${base.tail}"
    case _ => "edge"

  private def nextEdgeId(graph: Store, from: String, label: String, to: String): String =
    val base = s"$from-$label-$to"
    if !graph.edges.contains(base) then base
    else
      var index = 2
      while graph.edges.contains(s"$base-$index") do index += 1
      s"$base-$index"

  private def optionText(value: Value, operation: String): Option[String] = value match
    case Value.DataV("None", IndexedSeq()) => None
    case Value.DataV("Some", IndexedSeq(inner)) => Some(text(inner, operation))
    case Value.StrV(result) => Some(result)
    case _ => throw new IllegalArgumentException(s"$operation expects Option[String]")

  private def subjectText(value: Value, operation: String): String = value match
    case Value.StrV(result) => result
    case Value.DataV("Some", IndexedSeq(inner)) => subjectText(inner, operation)
    case iri @ Value.DataV("Iri", _) =>
      fieldText(iri, "value").orElse(iri.fields.headOption.collect {
        case Value.StrV(result) => result
      }).getOrElse(throw new IllegalArgumentException(s"$operation expects Iri(value)"))
    case iri @ Value.DataV("RdfNode.Iri", _) =>
      fieldText(iri, "value").orElse(iri.fields.headOption.collect {
        case Value.StrV(result) => result
      }).getOrElse(throw new IllegalArgumentException(s"$operation expects RdfNode.Iri(value)"))
    case _ => throw new IllegalArgumentException(s"$operation expects a String or Iri subject")

  private def optionalSubject(value: Value, operation: String): Option[String] = value match
    case Value.DataV("None", IndexedSeq()) => None
    case other => Some(subjectText(other, operation))

  private def graphName(value: Value, operation: String): String = text(value, operation)

  private def putVertex(args: List[Value]): Value = args match
    case graph :: value :: Nil =>
      val name = graphName(graph, "Graph.putVertex graphName")
      val id = requireFieldText(value, "id", "Graph.putVertex")
      store(name).vertices.update(id, value)
      value
    case _ => throw new IllegalArgumentException("Graph.putVertex(graphName, value)")

  private def getVertex(args: List[Value]): Value = args match
    case graph :: id :: Nil => option(store(graphName(graph, "Graph.getVertex graphName"))
      .vertices.get(text(id, "Graph.getVertex id")))
    case _ => throw new IllegalArgumentException("Graph.getVertex(graphName, id)")

  private def vertices(args: List[Value]): Value = args match
    case graph :: Nil => list(store(graphName(graph, "Graph.vertices graphName")).vertices.values)
    case _ => throw new IllegalArgumentException("Graph.vertices(graphName)")

  private def putEdge(args: List[Value]): Value = args match
    case graph :: value :: Nil =>
      val name = graphName(graph, "Graph.putEdge graphName")
      val graphStore = store(name)
      val from = requireFieldText(value, "from", "Graph.putEdge")
      val to = requireFieldText(value, "to", "Graph.putEdge")
      if !graphStore.vertices.contains(from) then throw new IllegalArgumentException(
        s"Graph.putEdge source vertex does not exist: $from")
      if !graphStore.vertices.contains(to) then throw new IllegalArgumentException(
        s"Graph.putEdge destination vertex does not exist: $to")
      val label = edgeLabel(value)
      val id = fieldText(value, "id").getOrElse(nextEdgeId(graphStore, from, label, to))
      val edge = Edge(id, value, from, to, label)
      graphStore.edges.update(id, edge)
      Value.DataV("StoredEdge", Vector(Value.StrV(id), Value.StrV(from),
        Value.StrV(to), Value.StrV(label), value))
    case _ => throw new IllegalArgumentException("Graph.putEdge(graphName, value)")

  private def edges(args: List[Value]): Value = args match
    case graph :: Nil => list(store(graphName(graph, "Graph.edges graphName")).edges.valuesIterator.map(_.value))
    case _ => throw new IllegalArgumentException("Graph.edges(graphName)")

  private def neighbors(args: List[Value]): Value = args match
    case graph :: from :: Nil => neighborValues(graph, from, None)
    case graph :: from :: label :: Nil =>
      neighborValues(graph, from, optionText(label, "Graph.neighbors edgeLabel"))
    case _ => throw new IllegalArgumentException(
      "Graph.neighbors(graphName, from, edgeLabel?)")

  private def neighborValues(graph: Value, from: Value, label: Option[String]): Value =
    val graphStore = store(graphName(graph, "Graph.neighbors graphName"))
    val source = text(from, "Graph.neighbors from")
    list(graphStore.edges.valuesIterator
      .filter(edge => edge.from == source && label.forall(_ == edge.label))
      .flatMap(edge => graphStore.vertices.get(edge.to)))

  private def putRdf(args: List[Value]): Value = args match
    case graph :: value :: Nil =>
      val name = graphName(graph, "Graph.putRdf graphName")
      val subject = fieldText(value, "id").orElse(fieldText(value, "subject"))
        .getOrElse(throw new IllegalArgumentException(
          "Graph.putRdf requires a String 'id' or 'subject' field"))
      store(name).rdf.update(subject, RdfRecord(subject, value))
      value
    case _ => throw new IllegalArgumentException("Graph.putRdf(graphName, value)")

  private def getRdf(args: List[Value]): Value = args match
    case graph :: subject :: Nil => option(store(graphName(graph, "Graph.getRdf graphName"))
      .rdf.get(subjectText(subject, "Graph.getRdf subject")).map(_.value))
    case _ => throw new IllegalArgumentException("Graph.getRdf(graphName, subject)")

  private def triples(args: List[Value]): Value =
    val (graphValue, subject, predicate) = args match
      case graph :: Nil => (graph, None, None)
      case graph :: wantedSubject :: Nil =>
        (graph, optionalSubject(wantedSubject, "Graph.triples subject"), None)
      case graph :: wantedSubject :: wantedPredicate :: Nil =>
        (graph, optionalSubject(wantedSubject, "Graph.triples subject"),
          optionText(wantedPredicate, "Graph.triples predicate"))
      case _ => throw new IllegalArgumentException(
        "Graph.triples(graphName, subject?, predicate?)")
    val graphStore = store(graphName(graphValue, "Graph.triples graphName"))
    list(graphStore.rdf.valuesIterator
      .filter(record => subject.forall(_ == record.subject))
      .flatMap(record => rdfTriples(record, predicate)))

  private def rdfTriples(record: RdfRecord, predicate: Option[String]): Vector[Value] =
    record.value match
      case Value.DataV(tag, fields) =>
        V2PluginRegistry.lookupFieldNames(tag, fields.length).toVector.flatMap { names =>
          names.zip(fields).collect {
            case (name, value) if name != "id" && name != "subject" && predicate.forall(_ == name) =>
              Value.DataV("RdfTriple", Vector(
                Value.StrV(record.subject), Value.StrV(name), value))
          }
        }
      case _ => Vector.empty

  private def unavailable(name: String, backend: String): List[Value] => Value = _ =>
    throw new UnsupportedOperationException(
      s"$name is not available in the standard local Graph provider; use an explicit $backend backend")

  def install(context: NativePluginContext): Unit =
    stores.synchronized(stores.clear())
    context.registerFields("StoredEdge", Vector("id", "from", "to", "label", "value"))
    context.registerFields("RdfTriple", Vector("subject", "predicate", "obj"))
    context.register("Graph.putVertex")(putVertex)
    context.register("Graph.getVertex")(getVertex)
    context.register("Graph.vertices")(vertices)
    context.register("Graph.putEdge")(putEdge)
    context.register("Graph.edges")(edges)
    context.register("Graph.neighborValues")(neighbors)
    context.register("Graph.neighbors")(neighbors)
    context.register("Graph.putRdf")(putRdf)
    context.register("Graph.getRdf")(getRdf)
    context.register("Graph.triples")(triples)
    context.register("Cypher.query")(unavailable("Cypher.query", "neo4j"))
    context.register("Gremlin.query")(unavailable("Gremlin.query", "gremlin-server or janusgraph"))

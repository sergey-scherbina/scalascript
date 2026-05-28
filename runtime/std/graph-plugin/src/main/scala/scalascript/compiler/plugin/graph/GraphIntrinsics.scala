package scalascript.compiler.plugin.graph

import scala.collection.mutable
import scalascript.backend.spi.*
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName

object GraphIntrinsics:
  private final case class Edge(id: String, value: Value, from: String, to: String, label: String)
  private final case class RdfRecord(subject: String, value: Value)

  private final class Store:
    val vertices = mutable.LinkedHashMap.empty[String, Value]
    val edges = mutable.LinkedHashMap.empty[String, Edge]
    val rdf = mutable.LinkedHashMap.empty[String, RdfRecord]

  private val stores = mutable.Map.empty[String, Store]

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("Graph.putVertex") -> NativeImpl((_, args) => args match
      case List(graphName: String, value: Value) =>
        val id = vertexId(value)
        store(graphName).vertices.update(id, value)
        value
      case _ => throw RuntimeException("Graph.putVertex(graphName: String, value: A)")
    ),
    QualifiedName("Graph.getVertex") -> NativeImpl((_, args) => args match
      case List(graphName: String, id: String) =>
        Value.OptionV(store(graphName).vertices.get(id))
      case _ => throw RuntimeException("Graph.getVertex(graphName: String, id: String)")
    ),
    QualifiedName("Graph.vertices") -> NativeImpl((_, args) => args match
      case List(graphName: String) =>
        Value.ListV(store(graphName).vertices.values.toList)
      case _ => throw RuntimeException("Graph.vertices(graphName: String)")
    ),
    QualifiedName("Graph.putEdge") -> NativeImpl((_, args) => args match
      case List(graphName: String, value: Value) =>
        val st = store(graphName)
        val from = fieldString(value, "from").getOrElse(throw RuntimeException("Graph.putEdge requires a `from` field"))
        val to = fieldString(value, "to").getOrElse(throw RuntimeException("Graph.putEdge requires a `to` field"))
        if !st.vertices.contains(from) then throw RuntimeException(s"edge from vertex does not exist: $from")
        if !st.vertices.contains(to) then throw RuntimeException(s"edge to vertex does not exist: $to")
        val label = edgeLabel(value)
        val id = fieldString(value, "id").getOrElse(nextEdgeId(st, from, label, to))
        val edge = Edge(id, value, from, to, label)
        st.edges.update(id, edge)
        edgeValue(edge)
      case _ => throw RuntimeException("Graph.putEdge(graphName: String, value: A)")
    ),
    QualifiedName("Graph.edges") -> NativeImpl((_, args) => args match
      case List(graphName: String) =>
        Value.ListV(store(graphName).edges.values.map(_.value).toList)
      case _ => throw RuntimeException("Graph.edges(graphName: String)")
    ),
    QualifiedName("Graph.neighborValues") -> NativeImpl((_, args) =>
      val (graphName, from, label) = neighborArgs(args)
      val st = store(graphName)
      Value.ListV(st.edges.valuesIterator
        .filter(e => e.from == from && label.forall(_ == e.label))
        .flatMap(e => st.vertices.get(e.to))
        .toList)
    ),
    QualifiedName("Graph.neighbors") -> NativeImpl((_, args) =>
      val (graphName, from, label) = neighborArgs(args)
      val st = store(graphName)
      Value.ListV(st.edges.valuesIterator
        .filter(e => e.from == from && label.forall(_ == e.label))
        .flatMap(e => st.vertices.get(e.to))
        .toList)
    ),
    QualifiedName("Graph.putRdf") -> NativeImpl((_, args) => args match
      case List(graphName: String, value: Value) =>
        val subject = fieldString(value, "id")
          .orElse(fieldString(value, "subject"))
          .getOrElse(throw RuntimeException("Graph.putRdf requires an `id` or `subject` field"))
        store(graphName).rdf.update(subject, RdfRecord(subject, value))
        value
      case _ => throw RuntimeException("Graph.putRdf(graphName: String, value: A)")
    ),
    QualifiedName("Graph.getRdf") -> NativeImpl((_, args) => args match
      case List(graphName: String, subject) =>
        Value.OptionV(store(graphName).rdf.get(subjectString(subject)).map(_.value))
      case _ => throw RuntimeException("Graph.getRdf(graphName: String, subject)")
    ),
    QualifiedName("Graph.triples") -> NativeImpl((_, args) => args match
      case List(graphName: String) =>
        Value.ListV(store(graphName).rdf.valuesIterator.flatMap(record => rdfTriples(record.value, None)).toList)
      case List(graphName: String, subject) =>
        val wanted = subjectOption(subject)
        Value.ListV(store(graphName).rdf.valuesIterator
          .filter(r => wanted.forall(_ == r.subject))
          .flatMap(record => rdfTriples(record.value, None))
          .toList)
      case List(graphName: String, subject, predicate) =>
        val wanted = subjectOption(subject)
        val pred = optionString(predicate)
        Value.ListV(store(graphName).rdf.valuesIterator
          .filter(r => wanted.forall(_ == r.subject))
          .flatMap(record => rdfTriples(record.value, pred))
          .toList)
      case _ => throw RuntimeException("Graph.triples(graphName: String, subject?: Option[String], predicate?: Option[String])")
    ),
    QualifiedName("Sparql.select") -> NativeImpl((_, _) =>
      throw RuntimeException("Sparql.select is not available in interpreter mode; use ssc run-jvm with backend: rdf4j-memory")
    ),
    QualifiedName("Sparql.update") -> NativeImpl((_, _) =>
      throw RuntimeException("Sparql.update is not available in interpreter mode; use ssc run-jvm with backend: rdf4j-memory or rdf4j-http")
    ),
    QualifiedName("Cypher.query") -> NativeImpl((_, _) =>
      throw RuntimeException("Cypher.query is not available in interpreter mode; use ssc run-jvm with backend: neo4j")
    ),
    QualifiedName("Gremlin.query") -> NativeImpl((_, _) =>
      throw RuntimeException("Gremlin.query is not available in interpreter mode; use ssc run-jvm with backend: gremlin-server or janusgraph")
    ),
  )

  private def store(name: String): Store = stores.getOrElseUpdate(name, Store())

  private def vertexId(value: Value): String =
    fieldString(value, "id").getOrElse(throw RuntimeException("Graph.putVertex requires an `id` field"))

  private def edgeLabel(value: Value): String = value match
    case Value.InstanceV(typeName, _) => decap(typeName.stripSuffix("Edge"))
    case _ => "edge"

  private def nextEdgeId(store: Store, from: String, label: String, to: String): String =
    val base = s"$from-$label-$to"
    var candidate = base
    var index = 1
    while store.edges.contains(candidate) do
      index += 1
      candidate = s"$base-$index"
    candidate

  private def neighborArgs(args: List[Any]): (String, String, Option[String]) = args match
    case List(graphName: String, from: String) => (graphName, from, None)
    case List(graphName: String, from: String, label) => (graphName, from, optionString(label))
    case _ => throw RuntimeException("Graph.neighbors(graphName: String, from: String, edgeLabel?: Option[String])")

  private def edgeValue(edge: Edge): Value =
    Value.InstanceV("StoredEdge", Map(
      "id" -> Value.StringV(edge.id),
      "from" -> Value.StringV(edge.from),
      "to" -> Value.StringV(edge.to),
      "label" -> Value.StringV(edge.label),
      "value" -> edge.value
    ))

  private def rdfTriples(value: Value, predicateFilter: Option[String]): List[Value] = value match
    case Value.InstanceV(typeName, fields) =>
      val subject = fields.get("id").orElse(fields.get("subject")).map(subjectString).getOrElse(typeName)
      fields.iterator.collect {
        case (name, field) if name != "id" && name != "subject" && predicateFilter.forall(_ == name) =>
          Value.InstanceV("RdfTriple", Map(
            "subject" -> Value.StringV(subject),
            "predicate" -> Value.StringV(name),
            "obj" -> field
          ))
      }.toList
    case _ => Nil

  private def fieldString(value: Value, name: String): Option[String] = value match
    case Value.InstanceV(_, fields) => fields.get(name).flatMap(asString)
    case Value.MapV(entries) => entries.collectFirst { case (Value.StringV(`name`), v) => v }.flatMap(asString)
    case _ => None

  private def subjectString(value: Any): String = value match
    case s: String => s
    case Value.StringV(s) => s
    case Value.InstanceV("Iri", fields) => fields.get("value").flatMap(asString).getOrElse(Value.show(Value.InstanceV("Iri", fields)))
    case Value.InstanceV("RdfNode.Iri", fields) => fields.get("value").flatMap(asString).getOrElse(Value.show(Value.InstanceV("RdfNode.Iri", fields)))
    case Value.OptionV(Some(v)) => subjectString(v)
    case other: Value => Value.show(other)
    case other => other.toString

  private def subjectOption(value: Any): Option[String] = value match
    case Value.NoneV => None
    case null => None
    case other => Some(subjectString(other))

  private def optionString(value: Any): Option[String] = value match
    case null => None
    case Value.NoneV => None
    case Value.OptionV(Some(v)) => asString(v)
    case s: String => Some(s)
    case v: Value => asString(v)
    case other => Some(other.toString)

  private def asString(value: Value): Option[String] = value match
    case Value.StringV(s) => Some(s)
    case Value.IntV(n) => Some(n.toString)
    case Value.DoubleV(d) => Some(if d == d.toLong.toDouble then d.toLong.toString else d.toString)
    case Value.BoolV(b) => Some(b.toString)
    case Value.OptionV(Some(v)) => asString(v)
    case Value.NoneV => None
    case other => Some(Value.show(other))

  private def decap(value: String): String =
    if value.isEmpty then value else s"${value.head.toLower}${value.tail}"

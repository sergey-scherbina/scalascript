package scalascript.compiler.plugin.graph

import scala.collection.mutable
import scalascript.backend.spi.*
import scalascript.plugin.api.PluginValue
import scalascript.ir.QualifiedName
import scalascript.plugin.api.PluginNative

object GraphIntrinsics:
  private final case class Edge(id: String, value: PluginValue, from: String, to: String, label: String)
  private final case class RdfRecord(subject: String, value: PluginValue)

  private final class Store:
    val vertices = mutable.LinkedHashMap.empty[String, PluginValue]
    val edges = mutable.LinkedHashMap.empty[String, Edge]
    val rdf = mutable.LinkedHashMap.empty[String, RdfRecord]

  private val stores = mutable.Map.empty[String, Store]

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("Graph.putVertex") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String, value) =>
        val id = vertexId(value)
        store(graphName).vertices.update(id, PluginValue.wrap(value))
        value
      case _ => throw RuntimeException("Graph.putVertex(graphName: String, value: A)")
    },
    QualifiedName("Graph.getVertex") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String, id: String) =>
        PluginValue.option(store(graphName).vertices.get(id))
      case _ => throw RuntimeException("Graph.getVertex(graphName: String, id: String)")
    },
    QualifiedName("Graph.vertices") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String) =>
        PluginValue.list(store(graphName).vertices.values.toList)
      case _ => throw RuntimeException("Graph.vertices(graphName: String)")
    },
    QualifiedName("Graph.putEdge") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String, value) =>
        val st = store(graphName)
        val from = fieldString(value, "from").getOrElse(throw RuntimeException("Graph.putEdge requires a `from` field"))
        val to = fieldString(value, "to").getOrElse(throw RuntimeException("Graph.putEdge requires a `to` field"))
        if !st.vertices.contains(from) then throw RuntimeException(s"edge from vertex does not exist: $from")
        if !st.vertices.contains(to) then throw RuntimeException(s"edge to vertex does not exist: $to")
        val label = edgeLabel(value)
        val id = fieldString(value, "id").getOrElse(nextEdgeId(st, from, label, to))
        val edge = Edge(id, PluginValue.wrap(value), from, to, label)
        st.edges.update(id, edge)
        edgeValue(edge)
      case _ => throw RuntimeException("Graph.putEdge(graphName: String, value: A)")
    },
    QualifiedName("Graph.edges") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String) =>
        PluginValue.list(store(graphName).edges.values.map(_.value).toList)
      case _ => throw RuntimeException("Graph.edges(graphName: String)")
    },
    QualifiedName("Graph.neighborValues") -> PluginNative.evalLegacy { (_, args) =>
      val (graphName, from, label) = neighborArgs(args)
      val st = store(graphName)
      PluginValue.list(st.edges.valuesIterator
        .filter(e => e.from == from && label.forall(_ == e.label))
        .flatMap(e => st.vertices.get(e.to))
        .toList)
    },
    QualifiedName("Graph.neighbors") -> PluginNative.evalLegacy { (_, args) =>
      val (graphName, from, label) = neighborArgs(args)
      val st = store(graphName)
      PluginValue.list(st.edges.valuesIterator
        .filter(e => e.from == from && label.forall(_ == e.label))
        .flatMap(e => st.vertices.get(e.to))
        .toList)
    },
    QualifiedName("Graph.putRdf") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String, value) =>
        val subject = fieldString(value, "id")
          .orElse(fieldString(value, "subject"))
          .getOrElse(throw RuntimeException("Graph.putRdf requires an `id` or `subject` field"))
        store(graphName).rdf.update(subject, RdfRecord(subject, PluginValue.wrap(value)))
        value
      case _ => throw RuntimeException("Graph.putRdf(graphName: String, value: A)")
    },
    QualifiedName("Graph.getRdf") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String, subject) =>
        PluginValue.option(store(graphName).rdf.get(subjectString(subject)).map(_.value))
      case _ => throw RuntimeException("Graph.getRdf(graphName: String, subject)")
    },
    QualifiedName("Graph.triples") -> PluginNative.evalLegacy { (_, args) => args match
      case List(graphName: String) =>
        PluginValue.list(store(graphName).rdf.valuesIterator.flatMap(record => rdfTriples(record.value, None)).toList)
      case List(graphName: String, subject) =>
        val wanted = subjectOption(subject)
        PluginValue.list(store(graphName).rdf.valuesIterator
          .filter(r => wanted.forall(_ == r.subject))
          .flatMap(record => rdfTriples(record.value, None))
          .toList)
      case List(graphName: String, subject, predicate) =>
        val wanted = subjectOption(subject)
        val pred = optionString(predicate)
        PluginValue.list(store(graphName).rdf.valuesIterator
          .filter(r => wanted.forall(_ == r.subject))
          .flatMap(record => rdfTriples(record.value, pred))
          .toList)
      case _ => throw RuntimeException("Graph.triples(graphName: String, subject?: Option[String], predicate?: Option[String])")
    },
    QualifiedName("Sparql.select") -> PluginNative.evalLegacy { (_, _) =>
      throw RuntimeException("Sparql.select is not available in interpreter mode; use ssc run-jvm with backend: rdf4j-memory")
    },
    QualifiedName("Sparql.update") -> PluginNative.evalLegacy { (_, _) =>
      throw RuntimeException("Sparql.update is not available in interpreter mode; use ssc run-jvm with backend: rdf4j-memory or rdf4j-http")
    },
    QualifiedName("Cypher.query") -> PluginNative.evalLegacy { (_, _) =>
      throw RuntimeException("Cypher.query is not available in interpreter mode; use ssc run-jvm with backend: neo4j")
    },
    QualifiedName("Gremlin.query") -> PluginNative.evalLegacy { (_, _) =>
      throw RuntimeException("Gremlin.query is not available in interpreter mode; use ssc run-jvm with backend: gremlin-server or janusgraph")
    },
  )

  private def store(name: String): Store = stores.getOrElseUpdate(name, Store())

  private def vertexId(value: Any): String =
    fieldString(value, "id").getOrElse(throw RuntimeException("Graph.putVertex requires an `id` field"))

  private def edgeLabel(value: Any): String = value match
    case PluginValue.Inst(typeName, _) => decap(typeName.stripSuffix("Edge"))
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

  private def edgeValue(edge: Edge): PluginValue =
    PluginValue.instance("StoredEdge", Map(
      "id" -> PluginValue.string(edge.id),
      "from" -> PluginValue.string(edge.from),
      "to" -> PluginValue.string(edge.to),
      "label" -> PluginValue.string(edge.label),
      "value" -> edge.value
    ))

  private def rdfTriples(value: Any, predicateFilter: Option[String]): List[PluginValue] = value match
    case PluginValue.Inst(typeName, fields) =>
      val subject = fields.get("id").orElse(fields.get("subject")).map(subjectString).getOrElse(typeName)
      fields.iterator.collect {
        case (name, field) if name != "id" && name != "subject" && predicateFilter.forall(_ == name) =>
          PluginValue.instance("RdfTriple", Map(
            "subject" -> PluginValue.string(subject),
            "predicate" -> PluginValue.string(name),
            "obj" -> field
          ))
      }.toList
    case _ => Nil

  private def fieldString(value: Any, name: String): Option[String] = value match
    case PluginValue.Inst(_, fields) => fields.get(name).flatMap(asString)
    case PluginValue.MapVal(entries) => entries.collectFirst { case (PluginValue.Str(`name`), v) => v }.flatMap(asString)
    case _ => None

  private def subjectString(value: Any): String = value match
    case s: String => s
    case PluginValue.Str(s) => s
    case PluginValue.Inst("Iri", fields) => fields.get("value").flatMap(asString).getOrElse(PluginValue.instance("Iri", fields).show)
    case PluginValue.Inst("RdfNode.Iri", fields) => fields.get("value").flatMap(asString).getOrElse(PluginValue.instance("RdfNode.Iri", fields).show)
    case PluginValue.Opt(Some(inner)) => subjectString(inner)
    case other => PluginValue.showAny(other)

  private def subjectOption(value: Any): Option[String] = value match
    case null => None
    case PluginValue.Opt(None) => None
    case other => Some(subjectString(other))

  private def optionString(value: Any): Option[String] = value match
    case null => None
    case PluginValue.Opt(None) => None
    case PluginValue.Opt(Some(inner)) => asString(inner)
    case s: String => Some(s)
    case other => asString(other)

  private def asString(value: Any): Option[String] = value match
    case PluginValue.Str(s) => Some(s)
    case PluginValue.Num(n) => Some(n.toString)
    case PluginValue.Dbl(d) => Some(if d == d.toLong.toDouble then d.toLong.toString else d.toString)
    case PluginValue.Bool(b) => Some(b.toString)
    case PluginValue.Opt(Some(inner)) => asString(inner)
    case PluginValue.Opt(None) => None
    case other => Some(PluginValue.showAny(other))

  private def decap(value: String): String =
    if value.isEmpty then value else s"${value.head.toLower}${value.tail}"

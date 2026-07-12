package ssc.plugin.graphrdf4j

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.Base64
import ssc.{Prims, Value}
import ssc.plugin.{NativePlugin, NativePluginContext}

/** Explicit RDF4J HTTP provider. JSON parsing is an opt-in provider dependency;
 * neither this code nor ujson is staged on the standard launcher classpath. */
final class GraphRdf4jNativePlugin(
    private val environment: String => Option[String])
    extends NativePlugin:
  def this() = this(key => Option(System.getenv(key)))

  def id: String = "91-graph-rdf4j-explicit"

  private val client = HttpClient.newBuilder().build()

  private def text(value: Value, operation: String): String = value match
    case Value.StrV(result) => result
    case _ => throw new IllegalArgumentException(s"$operation expects String arguments")

  private def list(values: IterableOnce[Value]): Value =
    Vector.from(values).reverseIterator.foldLeft[Value](Value.DataV("Nil", Vector.empty)) {
      (tail, head) => Value.DataV("Cons", Vector(head, tail))
    }

  private def row(bindings: ujson.Obj): Value = Value.MapV.from(
    bindings.value.iterator.map { case (name, binding) =>
      Value.StrV(name) -> Value.StrV(binding.obj.get("value").flatMap(_.strOpt).getOrElse(""))
    })

  private def endpoint(operation: String): String = environment("RDF4J_URL")
    .filter(_.nonEmpty)
    .getOrElse(throw new IllegalStateException(
      s"$operation requires RDF4J_URL for the explicit graph-rdf4j provider"))

  private def request(operation: String, contentType: String, body: String): String =
    val builder = HttpRequest.newBuilder(URI.create(endpoint(operation)))
      .header("Content-Type", contentType)
      .header("Accept", "application/sparql-results+json")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    for
      user <- environment("RDF4J_USER").filter(_.nonEmpty)
      password <- environment("RDF4J_PASS")
    do
      val token = Base64.getEncoder.encodeToString(
        s"$user:$password".getBytes(StandardCharsets.UTF_8))
      builder.header("Authorization", s"Basic $token")
    val response = client.send(builder.build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if response.statusCode() / 100 != 2 then
      throw new RuntimeException(
        s"$operation failed with HTTP ${response.statusCode()}: ${response.body()}")
    response.body()

  private def select(args: List[Value]): Value = args match
    case Value.StrV(_) :: query :: Nil =>
      val payload = request("Sparql.select", "application/sparql-query",
        text(query, "Sparql.select"))
      val bindings = ujson.read(payload).obj
        .get("results").flatMap(_.objOpt)
        .flatMap(_.value.get("bindings")).flatMap(_.arrOpt)
        .getOrElse(collection.mutable.ArrayBuffer.empty)
      list(bindings.iterator.collect { case value: ujson.Obj => row(value) })
    case _ => throw new IllegalArgumentException("Sparql.select(graphName, query)")

  private def update(args: List[Value]): Value = args match
    case Value.StrV(_) :: update :: Nil =>
      request("Sparql.update", "application/sparql-update",
        text(update, "Sparql.update"))
      Value.UnitV
    case _ => throw new IllegalArgumentException("Sparql.update(graphName, update)")

  def install(context: NativePluginContext): Unit =
    context.register("Sparql.select")(select)
    context.register("Sparql.update")(update)

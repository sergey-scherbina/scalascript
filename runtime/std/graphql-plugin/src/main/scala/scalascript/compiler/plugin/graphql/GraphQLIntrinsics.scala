package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}
import scalascript.plugin.api.{PluginContext, PluginNative}

import graphql.GraphQL
import graphql.language.OperationDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.{RuntimeWiring, SchemaGenerator, SchemaParser, TypeRuntimeWiring}
import graphql.ExecutionInput

import scala.jdk.CollectionConverters.*

/** Intrinsics table for the GraphQL interpreter plugin.
 *
 *  Constructed per-plugin so the runner instance is captured in each closure.
 */
class GraphQLIntrinsics(runner: GraphQLJvmBlockRunner):

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // GraphQL.schema(sdl) — validate SDL and return an opaque schema value.
    // The handle is the SDL string; graphql-java schema is built lazily in
    // graphqlHandler / serveGraphQL when the resolver map is also available.
    QualifiedName("GraphQL.schema") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(sdl: String) =>
          // Parse for early validation; throw on syntax error.
          new SchemaParser().parse(sdl)
          Value.Foreign("GraphQLSchema", sdl)
        case _ => throw InterpretError("GraphQL.schema(sdl: String)")
    },

    // GraphQL.resolvers(query = Map(...), mutation = Map(...), subscription = Map(...))
    // Keys may be plain field names ("hello") or schema coordinates ("Query.hello").
    // subscription is accepted for API compatibility; Phase 3 wires it to WebSocket.
    QualifiedName("GraphQL.resolvers") -> PluginNative.evalLegacy { (_, args) =>
      val query        = extractResolverMap(args.headOption.getOrElse(Value.MapV(Map.empty)))
      val mutation     = extractResolverMap(args.drop(1).headOption.getOrElse(Value.MapV(Map.empty)))
      val subscription = extractResolverMap(args.drop(2).headOption.getOrElse(Value.MapV(Map.empty)))
      Value.Foreign("GraphQLResolvers", GraphQLResolvers(query, mutation, subscription))
    },

    // serveGraphQL(port, resolvers) — registers POST + GET /graphql, then starts server.
    QualifiedName("serveGraphQL") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(port: Long, Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          mountGraphQL(ctx, res)
          ctx.registerHealthDefaults()
          ctx.startServer(port.toInt, ".")
          Value.UnitV
        case List(port: Long,
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers),
                  Value.InstanceV("TlsContext", tls)) =>
          mountGraphQL(ctx, res)
          ctx.registerHealthDefaults()
          val cert = tls.get("cert").collect { case Value.StringV(s) => s }.getOrElse("")
          val key  = tls.get("key").collect  { case Value.StringV(s) => s }.getOrElse("")
          ctx.startTlsServer(port.toInt, ".", cert, key)
          Value.UnitV
        case _ => throw InterpretError("serveGraphQL(port: Int, resolvers: GraphQLResolvers)")
    },

    // graphqlMount(resolvers) — registers POST + GET /graphql without calling serve().
    QualifiedName("graphqlMount") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          mountGraphQL(ctx, res)
          Value.UnitV
        case _ => throw InterpretError("graphqlMount(resolvers: GraphQLResolvers)")
    },

    // graphqlHandler(schema, resolvers) — returns a Request => Response handler value.
    QualifiedName("graphqlHandler") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("GraphQLSchema", sdl: String),
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          val engine = buildEngine(sdl, res, ctx)
          handlerValue(engine)
        case _ => throw InterpretError(
          "graphqlHandler(schema: GraphQLSchema, resolvers: GraphQLResolvers)")
    },

    // graphqlQuery(url, query) or graphqlQuery(url, query, variables)
    // Makes a POST /graphql request and returns the data map.
    // Throws if the response contains GraphQL errors.
    QualifiedName("graphqlQuery") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String, query: String) =>
          executeRemoteQuery(url, query, Map.empty)
        case List(url: String, query: String, Value.MapV(vars)) =>
          executeRemoteQuery(url, query, vars.map { (k, v) => valueToJavaForVars(k) -> v })
        case _ => throw InterpretError("graphqlQuery(url: String, query: String[, variables: Map])")
    },
  )

  // ── Internal helpers ───────────────────────────────────────────────────────

  private def extractResolverMap(v: Any): Map[String, Value] = v match
    case Value.MapV(m) => m.collect { case (Value.StringV(k), fn) => k -> fn }.toMap
    case _             => Map.empty

  private def mountGraphQL(ctx: PluginContext, res: GraphQLResolvers): Unit =
    val sdl = runner.registeredSdl.getOrElse(
      throw InterpretError("No graphql SDL registered — add a ```graphql block before serveGraphQL"))
    val engine = buildEngine(sdl, res, ctx)
    val h = handlerValue(engine)
    ctx.registerRoute("POST", "/graphql", h)
    ctx.registerRoute("GET",  "/graphql", h)

  // Parse a resolver key into (typeName, fieldName).
  // Plain keys ("hello") use the provided defaultType.
  // Schema coordinate keys ("Query.user", "User.posts") supply the type explicitly.
  private def parseCoordinate(key: String, defaultType: String): (String, String) =
    val dot = key.indexOf('.')
    if dot < 0 then (defaultType, key) else (key.substring(0, dot), key.substring(dot + 1))

  private def buildEngine(sdl: String, res: GraphQLResolvers, ctx: PluginContext): graphql.GraphQL =
    val typeReg = new SchemaParser().parse(sdl)
    val wiring  = RuntimeWiring.newRuntimeWiring()

    // Collect all resolvers grouped by type name.
    // Keys may be plain ("hello") or schema coordinates ("Query.hello", "User.posts").
    val byType = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, Value]]()

    def addResolver(key: String, fn: Value, defaultType: String): Unit =
      val (typeName, fieldName) = parseCoordinate(key, defaultType)
      byType.getOrElseUpdate(typeName, scala.collection.mutable.Map()) += (fieldName -> fn)

    res.query.foreach    { (k, fn) => addResolver(k, fn, "Query") }
    res.mutation.foreach { (k, fn) => addResolver(k, fn, "Mutation") }

    byType.foreach { (typeName, fields) =>
      val tw = TypeRuntimeWiring.newTypeWiring(typeName)
      fields.foreach { (fieldName, fn) =>
        tw.dataFetcher(fieldName, (env: DataFetchingEnvironment) =>
          val argsMap = Value.MapV(env.getArguments.asScala.toMap.map { (k, v) =>
            Value.StringV(k) -> javaToValue(v)
          })
          valueToJava(ctx.invokeCallback(fn, List(argsMap)).asInstanceOf[Value])
        )
      }
      wiring.`type`(tw)
    }

    val schema = new SchemaGenerator().makeExecutableSchema(typeReg, wiring.build())
    GraphQL.newGraphQL(schema).build()

  private def handlerValue(engine: graphql.GraphQL): Value =
    Value.NativeFnV("graphql.handler", Computation.pureFn {
      case List(req) => handleRequest(engine, req)
      case _         => throw InterpretError("GraphQL handler expects a Request argument")
    })

  private def handleRequest(engine: graphql.GraphQL, req: Any): Value =
    val (method, path, body) = req match
      case Value.InstanceV("Request", fields) =>
        val m = fields.get("method").collect { case Value.StringV(s) => s }.getOrElse("POST")
        val p = fields.get("path").collect   { case Value.StringV(s) => s }.getOrElse("/graphql")
        val b = fields.get("body").collect   { case Value.StringV(s) => s }.getOrElse("{}")
        (m, p, b)
      case _ => ("POST", "/graphql", "{}")

    // For GET requests, try to extract ?query=... from the path; fall back to body.
    val source =
      if method == "GET" then
        val qs = path.split('?').lift(1).getOrElse("")
        val qp = qs.split('&').flatMap { p =>
          p.split('=').toList match
            case k :: rest => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                                   java.net.URLDecoder.decode(rest.mkString("="), "UTF-8"))
            case _         => None
        }.toMap
        qp.get("query") match
          case Some(q) => ujson.Obj("query" -> ujson.Str(q))
          case None    => ujson.read(if body.isEmpty then "{}" else body)
      else
        ujson.read(if body.isEmpty then "{}" else body)

    val query  = source.obj.get("query").map(_.str).getOrElse("")
    val vars   = source.obj.get("variables") match
      case Some(ujson.Obj(m)) => m.toMap.map { (k, v) => k -> ujsonToJava(v) }.asJava
      case _                  => java.util.Collections.emptyMap[String, Any]()

    // GET requests must not execute mutations (GraphQL-over-HTTP §6.2.2).
    if method == "GET" && query.nonEmpty then
      try
        val doc = graphql.parser.Parser.parse(query)
        val hasMutation = doc.getDefinitions.asScala.exists {
          case od: OperationDefinition =>
            od.getOperation == OperationDefinition.Operation.MUTATION
          case _ => false
        }
        if hasMutation then
          return Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(405L),
            "body"    -> Value.StringV(
              """{"errors":[{"message":"Mutations are not allowed over GET"}]}"""),
            "headers" -> Value.MapV(Map(
              Value.StringV("Content-Type") -> Value.StringV("application/json"),
            )),
          ))
      catch case _: Exception => () // parse errors surface through the engine below

    val input  = ExecutionInput.newExecutionInput(query).variables(vars).build()
    val result = engine.execute(input)
    val dataVal = if result.getData[Any] == null then ujson.Null
      else anyToUJson(result.getData[java.util.Map[String, Any]]())
    val errsArr = result.getErrors.asScala.map(e => ujson.Str(e.getMessage)).toList
    val resp    = ujson.Obj("data" -> dataVal, "errors" -> ujson.Arr.from(errsArr))
    Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(200L),
      "body"    -> Value.StringV(ujson.write(resp)),
      "headers" -> Value.MapV(Map(
        Value.StringV("Content-Type") -> Value.StringV("application/json"),
      )),
    ))

  // ── Remote GraphQL client ──────────────────────────────────────────────────

  private def executeRemoteQuery(url: String, query: String, vars: Map[String, Value]): Value =
    import java.net.http.{HttpClient as JHttpClient, HttpRequest, HttpResponse}
    import java.net.URI
    import java.time.Duration

    val varJson = if vars.isEmpty then "{}"
      else ujson.write(ujson.Obj.from(vars.map { (k, v) => k -> valueToUJson(v) }))
    val bodyStr = ujson.write(ujson.Obj(
      "query"     -> ujson.Str(query),
      "variables" -> ujson.read(varJson)
    ))

    val timeout = Duration.ofSeconds(30)
    val client  = JHttpClient.newBuilder().connectTimeout(timeout).build()
    val graphqlUrl = if url.endsWith("/graphql") then url else url.stripSuffix("/") + "/graphql"
    val request = HttpRequest.newBuilder()
      .uri(URI.create(graphqlUrl))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .header("Accept", "application/graphql-response+json, application/json;q=0.9")
      .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
      .build()

    val response = try client.send(request, HttpResponse.BodyHandlers.ofString())
      catch case e: Exception => throw InterpretError(s"graphqlQuery: network error: ${e.getMessage}")

    val json = try ujson.read(response.body())
      catch case _: Exception => throw InterpretError(s"graphqlQuery: invalid JSON response from $graphqlUrl")

    val errors = json.obj.get("errors").toList.flatMap {
      case ujson.Arr(xs) => xs.map(_.obj.get("message").map(_.str).getOrElse("unknown error"))
      case _             => Nil
    }
    if errors.nonEmpty then
      throw InterpretError(s"graphqlQuery: GraphQL errors: ${errors.mkString("; ")}")

    json.obj.get("data") match
      case Some(data) => ujsonToValue(data)
      case None       => Value.NullV

  private def valueToJavaForVars(v: Value): String = v match
    case Value.StringV(s) => s
    case other            => String.valueOf(other)

  private def valueToUJson(v: Value): ujson.Value = v match
    case Value.NullV                => ujson.Null
    case Value.StringV(s)           => ujson.Str(s)
    case Value.IntV(n)              => ujson.Num(n.toDouble)
    case Value.DoubleV(d)           => ujson.Num(d)
    case Value.BoolV(b)             => ujson.Bool(b)
    case Value.UnitV                => ujson.Null
    case Value.OptionV(Some(inner)) => valueToUJson(inner)
    case Value.OptionV(None)        => ujson.Null
    case Value.ListV(items)         => ujson.Arr.from(items.map(valueToUJson))
    case Value.MapV(m)              =>
      ujson.Obj.from(m.map { (k, vv) => String.valueOf(valueToJava(k)) -> valueToUJson(vv) })
    case Value.InstanceV(_, fields) =>
      ujson.Obj.from(fields.map { (k, vv) => k -> valueToUJson(vv) })
    case other                      => ujson.Str(String.valueOf(other))

  private def ujsonToValue(v: ujson.Value): Value = v match
    case ujson.Null    => Value.NullV
    case ujson.Str(s)  => Value.StringV(s)
    case ujson.Num(n)  =>
      if n == n.toLong.toDouble then Value.IntV(n.toLong) else Value.DoubleV(n)
    case ujson.Bool(b) => Value.BoolV(b)
    case ujson.Arr(xs) => Value.ListV(xs.map(ujsonToValue).toList)
    case ujson.Obj(m)  =>
      Value.MapV(m.toMap.map { (k, vv) => Value.StringV(k) -> ujsonToValue(vv) })

  // ── Value <-> Java conversions ─────────────────────────────────────────────

  private def javaToValue(v: Any): Value = v match
    case null                   => Value.NullV
    case s: String              => Value.StringV(s)
    case n: java.lang.Long      => Value.IntV(n)
    case n: java.lang.Integer   => Value.IntV(n.toLong)
    case d: java.lang.Double    => Value.DoubleV(d)
    case b: java.lang.Boolean   => Value.BoolV(b)
    case m: java.util.Map[?, ?] =>
      Value.MapV(m.asScala.toMap.map { (k, vv) => Value.StringV(k.toString) -> javaToValue(vv) })
    case l: java.util.List[?]   =>
      Value.ListV(l.asScala.toList.map(javaToValue))
    case other                  => Value.StringV(String.valueOf(other))

  private def valueToJava(v: Value): Any = v match
    case Value.NullV                => null
    case Value.StringV(s)           => s
    case Value.IntV(n)              => n
    case Value.DoubleV(d)           => d
    case Value.BoolV(b)             => b
    case Value.UnitV                => null
    case Value.OptionV(Some(inner)) => valueToJava(inner)
    case Value.OptionV(None)        => null
    case Value.ListV(items)         => items.map(valueToJava).asJava
    case Value.MapV(m)              =>
      m.map { (k, vv) => valueToJava(k).toString -> valueToJava(vv) }.asJava
    case Value.InstanceV(_, fields) =>
      fields.map { (k, vv) => k -> valueToJava(vv) }.asJava
    case other                      => String.valueOf(other)

  private def ujsonToJava(v: ujson.Value): Any = v match
    case ujson.Str(s)  => s
    case ujson.Num(n)  => n
    case ujson.Bool(b) => b
    case ujson.Null    => null
    case ujson.Arr(xs) => xs.map(ujsonToJava).toList.asJava
    case ujson.Obj(m)  => m.toMap.map { (k, vv) => k -> ujsonToJava(vv) }.asJava

  private def anyToUJson(v: Any): ujson.Value = v match
    case null                   => ujson.Null
    case s: String              => ujson.Str(s)
    case n: java.lang.Long      => ujson.Num(n.toDouble)
    case n: java.lang.Integer   => ujson.Num(n.toDouble)
    case d: java.lang.Double    => ujson.Num(d)
    case b: java.lang.Boolean   => ujson.Bool(b)
    case m: java.util.Map[?, ?] =>
      ujson.Obj.from(m.asScala.toMap.map { (k, vv) => k.toString -> anyToUJson(vv) })
    case l: java.util.List[?]   =>
      ujson.Arr.from(l.asScala.map(anyToUJson))
    case other                  => ujson.Str(String.valueOf(other))

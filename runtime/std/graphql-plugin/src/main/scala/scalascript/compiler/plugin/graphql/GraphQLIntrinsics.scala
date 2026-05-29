package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}
import scalascript.plugin.api.{PluginContext, PluginNative}

import graphql.GraphQL
import graphql.analysis.{MaxQueryComplexityInstrumentation, MaxQueryDepthInstrumentation}
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.language.OperationDefinition
import graphql.schema.{Coercing, DataFetchingEnvironment, GraphQLScalarType}
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

    // GraphQL.resolvers(query = Map(...), mutation = Map(...), subscription = Map(...),
    //                   scalars = Map(...), loaders = Map(...))
    // Keys may be plain field names ("hello") or schema coordinates ("Query.hello").
    // subscription is accepted for API compatibility; Phase 3 wires it to WebSocket.
    // scalars: Map[String, GraphQLScalar] — custom scalar codecs from GraphQL.scalar(...)
    // loaders: Map[String, GraphQLDataLoader] — DataLoader specs from GraphQL.dataLoader(...)
    QualifiedName("GraphQL.resolvers") -> PluginNative.evalLegacy { (_, args) =>
      val query        = extractResolverMap(args.headOption.getOrElse(Value.MapV(Map.empty)))
      val mutation     = extractResolverMap(args.drop(1).headOption.getOrElse(Value.MapV(Map.empty)))
      val subscription = extractResolverMap(args.drop(2).headOption.getOrElse(Value.MapV(Map.empty)))
      val scalars      = extractScalarMap(args.drop(3).headOption.getOrElse(Value.MapV(Map.empty)))
      val loaders      = extractLoaderMap(args.drop(4).headOption.getOrElse(Value.MapV(Map.empty)))
      Value.Foreign("GraphQLResolvers", GraphQLResolvers(query, mutation, subscription, scalars, loaders))
    },

    // GraphQL.dataLoader(name, batchFn) — register a per-request-cached DataLoader.
    // batchFn: List[K] => Map[K, V] — receives a list of keys, returns a map of key → value.
    // Each request gets a fresh cache; identical keys within one request hit the batch fn once.
    // Resolvers access loaders via the injected _load(loaderName, key) function in their args map.
    QualifiedName("GraphQL.dataLoader") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(name: String, batchFn) =>
          Value.Foreign("GraphQLDataLoader", DataLoaderSpec(name, batchFn))
        case _ => throw InterpretError("GraphQL.dataLoader(name: String, batchFn: List[K] => Map[K, V])")
    },

    // GraphQL.scalar(name, serialize, coerce) — custom scalar codec.
    // serialize: ScalaScript value (resolver output) -> JSON-serializable Any
    // coerce:    JSON input Any (from variables or literals) -> ScalaScript Value
    QualifiedName("GraphQL.scalar") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(name: String, serFn, coerceFn) =>
          val codec = ScalarCodec(
            serialize = v   => valueToJava(ctx.invokeCallback(serFn,   List(v)).asInstanceOf[Value]),
            coerce    = raw => ctx.invokeCallback(coerceFn, List(javaToValue(raw))).asInstanceOf[Value],
          )
          Value.Foreign("GraphQLScalar", (name, codec))
        case _ => throw InterpretError("GraphQL.scalar(name: String, serialize: Value => Any, coerce: Any => Value)")
    },

    // GraphQL.options(...) — runtime limits and policy.
    // Parameters (all optional, positional):
    //   maxDepth: Int, maxComplexity: Int, maxQueryLength: Int,
    //   disableIntrospection: Boolean, persistedOps: Map[String, String], persistedOnly: Boolean
    QualifiedName("GraphQL.options") -> PluginNative.evalLegacy { (_, args) =>
      val maxDepth      = args.headOption.collect { case Value.IntV(n) => n.toInt }
      val maxComplexity = args.drop(1).headOption.collect { case Value.IntV(n) => n.toInt }
      val maxQueryLen   = args.drop(2).headOption.collect { case Value.IntV(n) => n.toInt }
      val noIntrospect  = args.drop(3).headOption.collect { case Value.BoolV(b) => b }.getOrElse(false)
      val persisted     = args.drop(4).headOption match
        case Some(Value.MapV(m)) =>
          m.collect { case (Value.StringV(k), Value.StringV(v)) => k -> v }.toMap
        case _ => Map.empty[String, String]
      val persistedOnly = args.drop(5).headOption.collect { case Value.BoolV(b) => b }.getOrElse(false)
      Value.Foreign("GraphQLOptions",
        GraphQLOpts(maxDepth, maxComplexity, maxQueryLen, noIntrospect, persisted, persistedOnly))
    },

    // serveGraphQL(port, resolvers[, opts]) — registers POST + GET /graphql, then starts server.
    QualifiedName("serveGraphQL") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(port: Long, Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          mountGraphQL(ctx, res, GraphQLOpts.default)
          ctx.registerHealthDefaults()
          ctx.startServer(port.toInt, ".")
          Value.UnitV
        case List(port: Long,
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers),
                  Value.Foreign("GraphQLOptions", opts: GraphQLOpts)) =>
          mountGraphQL(ctx, res, opts)
          ctx.registerHealthDefaults()
          ctx.startServer(port.toInt, ".")
          Value.UnitV
        case List(port: Long,
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers),
                  Value.InstanceV("TlsContext", tls)) =>
          mountGraphQL(ctx, res, GraphQLOpts.default)
          ctx.registerHealthDefaults()
          val cert = tls.get("cert").collect { case Value.StringV(s) => s }.getOrElse("")
          val key  = tls.get("key").collect  { case Value.StringV(s) => s }.getOrElse("")
          ctx.startTlsServer(port.toInt, ".", cert, key)
          Value.UnitV
        case _ => throw InterpretError("serveGraphQL(port: Int, resolvers: GraphQLResolvers[, opts: GraphQLOptions])")
    },

    // graphqlMount(resolvers[, opts]) — registers POST + GET /graphql without calling serve().
    QualifiedName("graphqlMount") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          mountGraphQL(ctx, res, GraphQLOpts.default)
          Value.UnitV
        case List(Value.Foreign("GraphQLResolvers", res: GraphQLResolvers),
                  Value.Foreign("GraphQLOptions", opts: GraphQLOpts)) =>
          mountGraphQL(ctx, res, opts)
          Value.UnitV
        case _ => throw InterpretError("graphqlMount(resolvers: GraphQLResolvers[, opts: GraphQLOptions])")
    },

    // graphqlHandler(schema, resolvers[, opts]) — returns a Request => Response handler value.
    QualifiedName("graphqlHandler") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("GraphQLSchema", sdl: String),
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers)) =>
          val engine = buildEngine(sdl, res, ctx, GraphQLOpts.default)
          handlerValue(engine, GraphQLOpts.default, res.loaders, ctx)
        case List(Value.Foreign("GraphQLSchema", sdl: String),
                  Value.Foreign("GraphQLResolvers", res: GraphQLResolvers),
                  Value.Foreign("GraphQLOptions", opts: GraphQLOpts)) =>
          val engine = buildEngine(sdl, res, ctx, opts)
          handlerValue(engine, opts, res.loaders, ctx)
        case _ => throw InterpretError(
          "graphqlHandler(schema: GraphQLSchema, resolvers: GraphQLResolvers[, opts: GraphQLOptions])")
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

    // GraphQL.printSchema(schema) — return the normalized SDL string.
    // Parses and reprints the SDL via graphql-java SchemaPrinter, normalizing whitespace.
    QualifiedName("GraphQL.printSchema") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(Value.Foreign("GraphQLSchema", sdl: String)) =>
          val typeReg = new SchemaParser().parse(sdl)
          val schema  = new SchemaGenerator().makeExecutableSchema(
            typeReg, RuntimeWiring.newRuntimeWiring().build())
          Value.StringV(new graphql.schema.idl.SchemaPrinter().print(schema))
        case _ => throw InterpretError("GraphQL.printSchema(schema: GraphQLSchema)")
    },

    // GraphQL.introspectionJson(schema) — run the standard introspection query and
    // return the JSON result as a String.  Suitable for feeding to client-side codegen.
    QualifiedName("GraphQL.introspectionJson") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(Value.Foreign("GraphQLSchema", sdl: String)) =>
          val engine = buildEngine(sdl, GraphQLResolvers(Map.empty, Map.empty), ctx, GraphQLOpts.default)
          val result = engine.execute(
            ExecutionInput.newExecutionInput(graphql.introspection.IntrospectionQuery.INTROSPECTION_QUERY).build())
          Value.StringV(ujson.write(anyToUJson(result.getData[java.util.Map[String, Any]]())))
        case _ => throw InterpretError("GraphQL.introspectionJson(schema: GraphQLSchema)")
    },

    // GraphQL.diffSchemas(sdlA, sdlB) — compare two SDL strings and return a List of
    // change Maps, each with "kind" and "description" keys.
    QualifiedName("GraphQL.diffSchemas") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(sdlA: String, sdlB: String) =>
          Value.ListV(diffSdl(sdlA, sdlB).map { (kind, desc) =>
            Value.MapV(Map(
              Value.StringV("kind")        -> Value.StringV(kind),
              Value.StringV("description") -> Value.StringV(desc),
            ))
          })
        case _ => throw InterpretError("GraphQL.diffSchemas(sdlA: String, sdlB: String)")
    },
  )

  // ── Internal helpers ───────────────────────────────────────────────────────

  private def extractResolverMap(v: Any): Map[String, Value] = v match
    case Value.MapV(m) => m.collect { case (Value.StringV(k), fn) => k -> fn }.toMap
    case _             => Map.empty

  private def extractScalarMap(v: Any): Map[String, ScalarCodec] = v match
    case Value.MapV(m) =>
      m.collect { case (Value.StringV(_), Value.Foreign("GraphQLScalar", (name: String, codec: ScalarCodec))) =>
        name -> codec
      }.toMap
    case _ => Map.empty

  private def extractLoaderMap(v: Any): Map[String, DataLoaderSpec] = v match
    case Value.MapV(m) =>
      m.collect { case (Value.StringV(_), Value.Foreign("GraphQLDataLoader", spec: DataLoaderSpec)) =>
        spec.name -> spec
      }.toMap
    case _ => Map.empty

  private def mountGraphQL(ctx: PluginContext, res: GraphQLResolvers, opts: GraphQLOpts): Unit =
    val sdl = runner.registeredSdl.getOrElse(
      throw InterpretError("No graphql SDL registered — add a ```graphql block before serveGraphQL"))
    val engine = buildEngine(sdl, res, ctx, opts)
    val h = handlerValue(engine, opts, res.loaders, ctx)
    ctx.registerRoute("POST", "/graphql", h)
    ctx.registerRoute("GET",  "/graphql", h)

  // Parse a resolver key into (typeName, fieldName).
  // Plain keys ("hello") use the provided defaultType.
  // Schema coordinate keys ("Query.user", "User.posts") supply the type explicitly.
  private def parseCoordinate(key: String, defaultType: String): (String, String) =
    val dot = key.indexOf('.')
    if dot < 0 then (defaultType, key) else (key.substring(0, dot), key.substring(dot + 1))

  private def buildEngine(sdl: String, res: GraphQLResolvers, ctx: PluginContext, opts: GraphQLOpts): graphql.GraphQL =
    val typeReg = new SchemaParser().parse(sdl)
    val wiring  = RuntimeWiring.newRuntimeWiring()

    // Register custom scalars before building type wiring.
    res.scalars.foreach { (name, codec) =>
      val scalarType = GraphQLScalarType.newScalar()
        .name(name)
        .coercing(new Coercing[Any, Any]:
          override def serialize(o: Any) = codec.serialize(javaToValue(o))
          override def parseValue(o: Any) = valueToJava(codec.coerce(o))
          override def parseLiteral(o: Any) = valueToJava(codec.coerce(String.valueOf(o)))
        )
        .build()
      wiring.scalar(scalarType)
    }

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
          val baseArgs: Map[Value, Value] = env.getArguments.asScala.toMap.map { (k, v) =>
            Value.StringV(k) -> javaToValue(v)
          }
          val enrichedArgs =
            if res.loaders.isEmpty then Value.MapV(baseArgs)
            else
              val dlCtx = env.getGraphQlContext.getOrDefault("_dlCtx", null)
                .asInstanceOf[DataLoaderContext]
              if dlCtx == null then Value.MapV(baseArgs)
              else
                val loadFn = Value.NativeFnV("graphql.load", Computation.pureFn {
                  case List(Value.StringV(loaderName), key: Value) =>
                    dlCtx.load(loaderName, key)
                  case _ => throw InterpretError("_load(loaderName: String, key)")
                })
                val batchLoadFn = Value.NativeFnV("graphql.batchLoad", Computation.pureFn {
                  case List(Value.StringV(loaderName), Value.ListV(keys)) =>
                    dlCtx.batchLoad(loaderName, keys)
                  case _ => throw InterpretError("_batchLoad(loaderName: String, keys: List)")
                })
                Value.MapV(baseArgs
                  + (Value.StringV("_load")      -> loadFn)
                  + (Value.StringV("_batchLoad") -> batchLoadFn))
          valueToJava(ctx.invokeCallback(fn, List(enrichedArgs)).asInstanceOf[Value])
        )
      }
      wiring.`type`(tw)
    }

    val schema  = new SchemaGenerator().makeExecutableSchema(typeReg, wiring.build())
    val builder = GraphQL.newGraphQL(schema)

    // Register limit instrumentations.
    val instrumentations = scala.collection.mutable.ListBuffer[graphql.execution.instrumentation.Instrumentation]()
    opts.maxDepth.foreach(d => instrumentations += MaxQueryDepthInstrumentation(d))
    opts.maxComplexity.foreach(c => instrumentations += MaxQueryComplexityInstrumentation(c))
    if instrumentations.size == 1 then builder.instrumentation(instrumentations.head)
    else if instrumentations.size > 1 then
      builder.instrumentation(ChainedInstrumentation(instrumentations.toList.asJava))

    builder.build()

  // Produce a coarse-grained diff between two SDL strings.
  // Returns a list of (kind, description) tuples describing added/removed types and fields.
  private def diffSdl(sdlA: String, sdlB: String): List[(String, String)] =
    import scala.util.Try
    import graphql.language.{ObjectTypeDefinition, InterfaceTypeDefinition, InputObjectTypeDefinition}
    val changes = scala.collection.mutable.ListBuffer[(String, String)]()

    def fieldNames(td: graphql.language.TypeDefinition[?]): Set[String] = td match
      case o: ObjectTypeDefinition      => o.getFieldDefinitions.asScala.map(_.getName).toSet
      case i: InterfaceTypeDefinition   => i.getFieldDefinitions.asScala.map(_.getName).toSet
      case n: InputObjectTypeDefinition => n.getInputValueDefinitions.asScala.map(_.getName).toSet
      case _                            => Set.empty

    def typeMap(sdl: String): Map[String, graphql.language.TypeDefinition[?]] =
      Try(new SchemaParser().parse(sdl)).toOption.map(_.types().asScala.toMap)
        .getOrElse(Map.empty)

    val typesA = typeMap(sdlA)
    val typesB = typeMap(sdlB)
    val addedTypes   = typesB.keySet -- typesA.keySet
    val removedTypes = typesA.keySet -- typesB.keySet
    val commonTypes  = typesA.keySet intersect typesB.keySet

    addedTypes.toList.sorted.foreach   { t => changes += (("TYPE_ADDED",   s"Type '$t' was added")) }
    removedTypes.toList.sorted.foreach { t => changes += (("TYPE_REMOVED", s"Type '$t' was removed")) }

    commonTypes.toList.sorted.foreach { t =>
      val fieldsA = fieldNames(typesA(t))
      val fieldsB = fieldNames(typesB(t))
      (fieldsB -- fieldsA).toList.sorted.foreach { f =>
        changes += (("FIELD_ADDED",   s"Field '$f' was added to type '$t'"))
      }
      (fieldsA -- fieldsB).toList.sorted.foreach { f =>
        changes += (("FIELD_REMOVED", s"Field '$f' was removed from type '$t'"))
      }
    }
    changes.toList

  /** Per-request DataLoader cache. Deduplicates repeated key fetches within one request.
   *
   *  `load` calls the batch function with a single-element list on first access for a key,
   *  caches ALL entries returned by the batch function, then returns the cached value.
   *  `batchLoad` dispatches all uncached keys in one batch-function call.
   */
  private class DataLoaderContext(
    specs:   Map[String, DataLoaderSpec],
    pCtx:    PluginContext,
  ):
    private val cache = scala.collection.mutable.Map[String, scala.collection.mutable.Map[Value, Value]]()

    private def cacheFor(loaderName: String) =
      cache.getOrElseUpdate(loaderName, scala.collection.mutable.Map())

    def load(loaderName: String, key: Value): Value =
      val spec   = specs.getOrElse(loaderName, throw InterpretError(s"Unknown DataLoader: '$loaderName'"))
      val lCache = cacheFor(loaderName)
      lCache.getOrElse(key, {
        val result = pCtx.invokeCallback(spec.batchFn, List(Value.ListV(List(key)))).asInstanceOf[Value]
        result match
          case Value.MapV(m) =>
            m.foreach { (k, v) => lCache(k) = v }
            lCache.getOrElse(key, Value.NullV)
          case single => lCache(key) = single; single
      })

    def batchLoad(loaderName: String, keys: List[Value]): Value =
      val spec      = specs.getOrElse(loaderName, throw InterpretError(s"Unknown DataLoader: '$loaderName'"))
      val lCache    = cacheFor(loaderName)
      val uncached  = keys.filter(k => !lCache.contains(k))
      if uncached.nonEmpty then
        pCtx.invokeCallback(spec.batchFn, List(Value.ListV(uncached))).asInstanceOf[Value] match
          case Value.MapV(m) => m.foreach { (k, v) => lCache(k) = v }
          case _             => ()
      Value.MapV(keys.map { k => k -> lCache.getOrElse(k, Value.NullV) }.toMap)

  private def handlerValue(
    engine:  graphql.GraphQL,
    opts:    GraphQLOpts,
    loaders: Map[String, DataLoaderSpec],
    pCtx:    PluginContext,
  ): Value =
    Value.NativeFnV("graphql.handler", Computation.pureFn {
      case List(req) => handleRequest(engine, opts, loaders, pCtx, req)
      case _         => throw InterpretError("GraphQL handler expects a Request argument")
    })

  // GraphQL-over-HTTP §7.1 — preferred response content type.
  private val GQL_RESPONSE_JSON = "application/graphql-response+json"
  private val APP_JSON          = "application/json"

  private def headerValue(fields: Map[String, Value], name: String): Option[String] =
    fields.get("headers").collect {
      case Value.MapV(m) =>
        m.collectFirst {
          case (Value.StringV(k), Value.StringV(v)) if k.equalsIgnoreCase(name) => v
        }
    }.flatten

  private def acceptsGqlResponseJson(fields: Map[String, Value]): Boolean =
    headerValue(fields, "accept").exists(_.contains(GQL_RESPONSE_JSON))

  private def parseQueryString(qs: String): Map[String, String] =
    qs.split('&').flatMap { p =>
      p.split('=').toList match
        case k :: rest => Some(java.net.URLDecoder.decode(k, "UTF-8") ->
                               java.net.URLDecoder.decode(rest.mkString("="), "UTF-8"))
        case _         => None
    }.toMap

  private def sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(b => f"$b%02x").mkString

  private def errorResponse(status: Int, message: String, ct: String): Value =
    Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(status.toLong),
      "body"    -> Value.StringV(s"""{"errors":[{"message":"$message"}]}"""),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(ct))),
    ))

  private def handleRequest(
    engine:  graphql.GraphQL,
    opts:    GraphQLOpts,
    loaders: Map[String, DataLoaderSpec],
    pCtx:    PluginContext,
    req:     Any,
  ): Value =
    val (method, path, body, fields) = req match
      case Value.InstanceV("Request", f) =>
        val m = f.get("method").collect { case Value.StringV(s) => s }.getOrElse("POST")
        val p = f.get("path").collect   { case Value.StringV(s) => s }.getOrElse("/graphql")
        val b = f.get("body").collect   { case Value.StringV(s) => s }.getOrElse("")
        (m, p, b, f)
      case _ => ("POST", "/graphql", "", Map.empty[String, Value])

    // GraphQL-over-HTTP §7.1: if Accept contains application/graphql-response+json
    // we respond with that type and may return 4xx/5xx status codes on errors.
    val useGqlCt = acceptsGqlResponseJson(fields)
    val ct       = if useGqlCt then GQL_RESPONSE_JSON else APP_JSON

    // Apply body/query-length limit before parsing.
    if opts.maxQueryLength.exists(body.length > _) then
      return errorResponse(if useGqlCt then 400 else 200,
        s"Request body exceeds maximum length of ${opts.maxQueryLength.get} bytes", ct)

    // Decode query parameters from path for GET requests.
    val qp: Map[String, String] =
      if method == "GET" then parseQueryString(path.split('?').lift(1).getOrElse(""))
      else Map.empty

    // For POST: parse JSON body; for GET: prefer query-string params, fall back to body.
    val source: ujson.Value =
      if method == "GET" then
        if qp.contains("query") then
          val q  = qp.get("query").map(ujson.Str(_)).getOrElse(ujson.Null)
          val v  = qp.get("variables").flatMap(raw => scala.util.Try(ujson.read(raw)).toOption).getOrElse(ujson.Null)
          val op = qp.get("operationName").map(ujson.Str(_)).getOrElse(ujson.Null)
          ujson.Obj("query" -> q, "variables" -> v, "operationName" -> op)
        else
          scala.util.Try(ujson.read(if body.isEmpty then "{}" else body)).getOrElse(ujson.Obj())
      else
        scala.util.Try(ujson.read(if body.isEmpty then "{}" else body))
          .getOrElse(ujson.Obj())

    // Extract APQ hash from extensions.persistedQuery.sha256Hash if present.
    val apqHash: Option[String] = source.obj.get("extensions").collect {
      case ujson.Obj(ext) => ext.get("persistedQuery").collect {
        case ujson.Obj(pq) => pq.get("sha256Hash").collect { case ujson.Str(h) => h }
      }.flatten
    }.flatten

    var query = source.obj.get("query").collect { case ujson.Str(s) => s }.getOrElse("")

    // APQ resolution: if hash present but no query, look up in persistedOps.
    if query.isBlank && apqHash.isDefined then
      apqHash.flatMap(opts.persistedOps.get) match
        case Some(persisted) => query = persisted
        case None =>
          return Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200L),
            "body"    -> Value.StringV(
              """{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"""),
            "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(ct))),
          ))

    // persistedOnly: reject queries not in the manifest (hash not in persistedOps).
    if opts.persistedOnly && opts.persistedOps.nonEmpty then
      val queryHash = sha256Hex(query)
      if !opts.persistedOps.contains(queryHash) then
        return errorResponse(if useGqlCt then 400 else 200,
          "Only persisted operations are accepted", ct)

    val operationName = source.obj.get("operationName").collect { case ujson.Str(s) => s }.orNull
    val vars          = source.obj.get("variables") match
      case Some(ujson.Obj(m)) => m.toMap.map { (k, v) => k -> ujsonToJava(v) }.asJava
      case _                  => java.util.Collections.emptyMap[String, Any]()

    if query.isBlank then
      return if useGqlCt then errorResponse(400, "Missing query", ct)
             else errorResponse(200, "Missing query", ct)

    // Block introspection queries when configured.
    if opts.disableIntrospection then
      val lower = query.trim.replace("\n", " ").replace("\t", " ")
      val hasIntrospect = lower.contains("__schema") || lower.contains("__type")
      if hasIntrospect then
        return errorResponse(if useGqlCt then 400 else 200, "Introspection is disabled", ct)

    // GET requests must not execute mutations (GraphQL-over-HTTP §6.2.2).
    if method == "GET" then
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
            "body"    -> Value.StringV("""{"errors":[{"message":"Mutations are not allowed over GET"}]}"""),
            "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(ct))),
          ))
      catch case _: Exception => () // syntax errors surface through the engine below

    val inputBuilder = ExecutionInput.newExecutionInput(query).variables(vars)
    if operationName != null then inputBuilder.operationName(operationName)
    if loaders.nonEmpty && pCtx != null then
      val dlCtx = new DataLoaderContext(loaders, pCtx)
      inputBuilder.graphQLContext { (b: graphql.GraphQLContext.Builder) =>
        b.put("_dlCtx", dlCtx); ()
      }
    val input  = inputBuilder.build()
    val result = engine.execute(input)

    val dataVal = if result.getData[Any] == null then ujson.Null
                  else anyToUJson(result.getData[java.util.Map[String, Any]]())
    val errsArr = result.getErrors.asScala.toList

    // Under application/graphql-response+json: omit "errors" key when empty;
    // use 200 for partial results, 4xx only for request errors (no data at all).
    val hasRequestError = result.getData[Any] == null && errsArr.nonEmpty
    val status =
      if useGqlCt && hasRequestError then 400
      else 200

    val respObj = ujson.Obj("data" -> dataVal)
    if errsArr.nonEmpty then
      respObj("errors") = ujson.Arr.from(
        errsArr.map { e =>
          val obj = ujson.Obj("message" -> ujson.Str(e.getMessage))
          val locs = Option(e.getLocations).map(_.asScala.toList).getOrElse(Nil)
          if locs.nonEmpty then
            obj("locations") = ujson.Arr.from(locs.map { l =>
              ujson.Obj("line" -> ujson.Num(l.getLine), "column" -> ujson.Num(l.getColumn))
            })
          obj
        }
      )

    // Pass through extensions if the engine returned any.
    val ext = result.getExtensions
    if ext != null && !ext.isEmpty then
      respObj("extensions") = anyToUJson(ext)

    Value.InstanceV("Response", Map(
      "status"  -> Value.IntV(status.toLong),
      "body"    -> Value.StringV(ujson.write(respObj)),
      "headers" -> Value.MapV(Map(Value.StringV("Content-Type") -> Value.StringV(ct))),
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
    case Value.OptionV(inner) if inner != null => valueToUJson(inner)
    case Value.OptionV(null)                   => ujson.Null
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
    case Value.OptionV(inner) if inner != null => valueToJava(inner)
    case Value.OptionV(null)                   => null
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

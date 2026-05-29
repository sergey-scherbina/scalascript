package scalascript.compiler.plugin.graphql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.{Value, InterpretError, Computation}
import scalascript.plugin.api.{PluginContext, PluginNative}

import graphql.GraphQL
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

    // GraphQL.resolvers(query = Map(...), mutation = Map(...))
    QualifiedName("GraphQL.resolvers") -> PluginNative.evalLegacy { (_, args) =>
      val query    = extractResolverMap(args.headOption.getOrElse(Value.MapV(Map.empty)))
      val mutation = extractResolverMap(args.drop(1).headOption.getOrElse(Value.MapV(Map.empty)))
      Value.Foreign("GraphQLResolvers", GraphQLResolvers(query, mutation))
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

  private def buildEngine(sdl: String, res: GraphQLResolvers, ctx: PluginContext): graphql.GraphQL =
    val typeReg = new SchemaParser().parse(sdl)
    val wiring  = RuntimeWiring.newRuntimeWiring()
    if res.query.nonEmpty then
      val tw = TypeRuntimeWiring.newTypeWiring("Query")
      res.query.foreach { (field, fn) =>
        tw.dataFetcher(field, (env: DataFetchingEnvironment) =>
          val argsMap = Value.MapV(env.getArguments.asScala.toMap.map { (k, v) =>
            Value.StringV(k) -> javaToValue(v)
          })
          valueToJava(ctx.invokeCallback(fn, List(argsMap)).asInstanceOf[Value])
        )
      }
      wiring.`type`(tw)
    if res.mutation.nonEmpty then
      val tw = TypeRuntimeWiring.newTypeWiring("Mutation")
      res.mutation.foreach { (field, fn) =>
        tw.dataFetcher(field, (env: DataFetchingEnvironment) =>
          val argsMap = Value.MapV(env.getArguments.asScala.toMap.map { (k, v) =>
            Value.StringV(k) -> javaToValue(v)
          })
          valueToJava(ctx.invokeCallback(fn, List(argsMap)).asInstanceOf[Value])
        )
      }
      wiring.`type`(tw)
    val schema = new SchemaGenerator().makeExecutableSchema(typeReg, wiring.build())
    GraphQL.newGraphQL(schema).build()

  private def handlerValue(engine: graphql.GraphQL): Value =
    Value.NativeFnV("graphql.handler", Computation.pureFn {
      case List(req) => handleRequest(engine, req)
      case _         => throw InterpretError("GraphQL handler expects a Request argument")
    })

  private def handleRequest(engine: graphql.GraphQL, req: Any): Value =
    val body = req match
      case Value.InstanceV("Request", fields) =>
        fields.get("body").collect { case Value.StringV(s) => s }.getOrElse("{}")
      case _ => "{}"
    val parsed = ujson.read(if body.isEmpty then "{}" else body)
    val query  = parsed.obj.get("query").map(_.str).getOrElse("")
    val vars   = parsed.obj.get("variables") match
      case Some(ujson.Obj(m)) => m.toMap.map { (k, v) => k -> ujsonToJava(v) }.asJava
      case _                  => java.util.Collections.emptyMap[String, Any]()
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

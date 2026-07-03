package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Value
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import ujson.*

/** Phase 11 — schema export, diff, and introspection tests.
 *
 *  Covers: GraphQL.printSchema (normalized SDL), GraphQL.introspectionJson
 *  (standard introspection query result), GraphQL.diffSchemas (structural diff).
 */
class GraphQLSchemaExportTest extends AnyFunSuite:

  private def makeCtx(): scalascript.backend.spi.NativeContext =
    new scalascript.backend.spi.NativeContext:
      def out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
      def err = out
      override def invokeCallback(fn: Any, args: List[Any]): Any = Value.NullV

  private def schemaForeign(sdl: String) = Value.Foreign("GraphQLSchema", sdl)

  private def evalImpl(p: GraphQLInterpreterPlugin, name: String, args: List[Any]): Any =
    p.intrinsics(QualifiedName(name)).asInstanceOf[NativeImpl].eval(makeCtx(), args)

  // ── GraphQL.printSchema ───────────────────────────────────────────────────

  test("GraphQL.printSchema returns a non-empty String"):
    val p      = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.printSchema", List(schemaForeign("type Query { hello: String! }")))
    result match
      case Value.StringV(sdl) => assert(sdl.nonEmpty, "printed SDL should not be empty")
      case other              => fail(s"expected StringV, got $other")

  test("GraphQL.printSchema output contains type and field names"):
    val sdl    = "type Query { hello: String! count: Int! }"
    val p      = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.printSchema", List(schemaForeign(sdl)))
    result match
      case Value.StringV(out) =>
        assert(out.contains("Query"),  s"expected 'Query' in output: $out")
        assert(out.contains("hello"),  s"expected 'hello' in output: $out")
        assert(out.contains("count"),  s"expected 'count' in output: $out")
      case other => fail(s"expected StringV, got $other")

  test("GraphQL.printSchema normalizes whitespace in the SDL"):
    val compacted = "type Query{hello:String!}"
    val expanded  = "type Query {\n  hello: String!\n}"
    val p = new GraphQLInterpreterPlugin()
    val r1 = evalImpl(p, "GraphQL.printSchema", List(schemaForeign(compacted))).asInstanceOf[Value.StringV].v
    val r2 = evalImpl(p, "GraphQL.printSchema", List(schemaForeign(expanded))).asInstanceOf[Value.StringV].v
    assert(r1 == r2, s"compacted and expanded SDL should normalize to the same output\nr1=$r1\nr2=$r2")

  test("GraphQL.printSchema with multi-type schema"):
    val sdl =
      """|type Query { user(id: Int!): User }
         |type User { id: Int! name: String! }""".stripMargin
    val p   = new GraphQLInterpreterPlugin()
    val out = evalImpl(p, "GraphQL.printSchema", List(schemaForeign(sdl))).asInstanceOf[Value.StringV].v
    assert(out.contains("User"), s"expected User type in output: $out")
    assert(out.contains("name"), s"expected name field in output: $out")

  // ── GraphQL.introspectionJson ──────────────────────────────────────────────

  test("GraphQL.introspectionJson returns a JSON string"):
    val p      = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.introspectionJson", List(schemaForeign("type Query { hello: String! }")))
    result match
      case Value.StringV(json) =>
        val parsed = ujson.read(json)
        assert(parsed.obj.contains("__schema"), s"expected __schema key: $json")
      case other => fail(s"expected StringV, got $other")

  test("GraphQL.introspectionJson result contains queryType"):
    val p      = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.introspectionJson",
                           List(schemaForeign("type Query { hello: String! }"))).asInstanceOf[Value.StringV]
    val parsed = ujson.read(result.v)
    val queryTypeName = parsed("__schema")("queryType")("name").str
    assert(queryTypeName == "Query")

  test("GraphQL.introspectionJson lists all types"):
    val sdl = "type Query { greet: String! } type Extra { value: Int }"
    val p   = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.introspectionJson", List(schemaForeign(sdl))).asInstanceOf[Value.StringV]
    val parsed = ujson.read(result.v)
    val typeNames = parsed("__schema")("types").arr.map(_("name").str).toSet
    assert(typeNames.contains("Query"), s"expected Query in types: $typeNames")
    assert(typeNames.contains("Extra"), s"expected Extra in types: $typeNames")

  test("GraphQL.introspectionJson field names are present for Query type"):
    val sdl = "type Query { hello: String! count: Int! }"
    val p   = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.introspectionJson", List(schemaForeign(sdl))).asInstanceOf[Value.StringV]
    val parsed = ujson.read(result.v)
    val queryType = parsed("__schema")("types").arr
      .find(_("name").str == "Query").getOrElse(fail("Query not found"))
    val fieldNames = queryType("fields").arr.map(_("name").str).toSet
    assert(fieldNames.contains("hello"), s"expected hello: $fieldNames")
    assert(fieldNames.contains("count"), s"expected count: $fieldNames")

  // ── GraphQL.diffSchemas ───────────────────────────────────────────────────

  test("GraphQL.diffSchemas returns empty list for identical schemas"):
    val sdl = "type Query { hello: String! }"
    val p   = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdl, sdl))
    result match
      case Value.ListV(changes) => assert(changes.isEmpty, s"expected no changes: $changes")
      case other                => fail(s"expected ListV, got $other")

  test("GraphQL.diffSchemas detects added type"):
    val sdlA = "type Query { hello: String! }"
    val sdlB = "type Query { hello: String! } type NewType { value: Int }"
    val p    = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdlA, sdlB)).asInstanceOf[Value.ListV]
    val kinds = result.items.collect { case Value.MapV(m) =>
      m.get(Value.StringV("kind")).collect { case Value.StringV(k) => k }.getOrElse("")
    }
    assert(kinds.contains("TYPE_ADDED"), s"expected TYPE_ADDED in: $kinds")

  test("GraphQL.diffSchemas detects removed type"):
    val sdlA = "type Query { hello: String! } type OldType { value: Int }"
    val sdlB = "type Query { hello: String! }"
    val p    = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdlA, sdlB)).asInstanceOf[Value.ListV]
    val kinds = result.items.collect { case Value.MapV(m) =>
      m.get(Value.StringV("kind")).collect { case Value.StringV(k) => k }.getOrElse("")
    }
    assert(kinds.contains("TYPE_REMOVED"), s"expected TYPE_REMOVED in: $kinds")

  test("GraphQL.diffSchemas detects added field"):
    val sdlA = "type Query { hello: String! }"
    val sdlB = "type Query { hello: String! world: Int! }"
    val p    = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdlA, sdlB)).asInstanceOf[Value.ListV]
    val kinds = result.items.collect { case Value.MapV(m) =>
      m.get(Value.StringV("kind")).collect { case Value.StringV(k) => k }.getOrElse("")
    }
    assert(kinds.contains("FIELD_ADDED"), s"expected FIELD_ADDED in: $kinds")

  test("GraphQL.diffSchemas detects removed field"):
    val sdlA = "type Query { hello: String! goodbye: String! }"
    val sdlB = "type Query { hello: String! }"
    val p    = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdlA, sdlB)).asInstanceOf[Value.ListV]
    val kinds = result.items.collect { case Value.MapV(m) =>
      m.get(Value.StringV("kind")).collect { case Value.StringV(k) => k }.getOrElse("")
    }
    assert(kinds.contains("FIELD_REMOVED"), s"expected FIELD_REMOVED in: $kinds")

  test("GraphQL.diffSchemas change maps have kind and description keys"):
    val sdlA = "type Query { hello: String! }"
    val sdlB = "type Query { hello: String! world: Int! }"
    val p    = new GraphQLInterpreterPlugin()
    val result = evalImpl(p, "GraphQL.diffSchemas", List(sdlA, sdlB)).asInstanceOf[Value.ListV]
    result.items.foreach {
      case Value.MapV(m) =>
        assert(m.contains(Value.StringV("kind")),        s"missing 'kind' key: $m")
        assert(m.contains(Value.StringV("description")), s"missing 'description' key: $m")
      case other => fail(s"expected MapV, got $other")
    }

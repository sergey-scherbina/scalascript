package scalascript.compiler.plugin.graphql

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{NativeImpl, NativeContext}
import scalascript.interpreter.{Computation, InterpretError, Value}
import scalascript.ir.QualifiedName

/** Phase 7 — Typed client operations: graphqlSubscribe WS client.
 *
 *  Covers: intrinsic registration, argument validation, blank-query guard,
 *  URL scheme transformation (http→ws, https→wss), and path building.
 *  Network roundtrip tests are omitted (require a running server).
 */
class GraphQLTypedClientTest extends AnyFunSuite:

  private def nullStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  private def buildCtx(): NativeContext = new NativeContext:
    def out = nullStream
    def err = out

  private val intrinsics = new GraphQLInterpreterPlugin().intrinsics

  private val dummyHandler: Value.NativeFnV =
    Value.NativeFnV("handler", Computation.pureFn(_ => Value.UnitV))

  // ── Intrinsic registration ────────────────────────────────────────────────

  test("graphqlSubscribe intrinsic is registered in the table"):
    assert(intrinsics.contains(QualifiedName("graphqlSubscribe")),
      "graphqlSubscribe should be in the intrinsics table")

  // ── Argument validation ───────────────────────────────────────────────────

  test("graphqlSubscribe with too few args throws InterpretError"):
    val ctx  = buildCtx()
    val impl = intrinsics(QualifiedName("graphqlSubscribe")).asInstanceOf[NativeImpl]
    assertThrows[InterpretError] {
      impl.eval(ctx, List("http://localhost:8080"))
    }

  test("graphqlSubscribe with wrong arg types throws InterpretError"):
    val ctx  = buildCtx()
    val impl = intrinsics(QualifiedName("graphqlSubscribe")).asInstanceOf[NativeImpl]
    assertThrows[InterpretError] {
      impl.eval(ctx, List(Value.IntV(42), "subscription { events }"))
    }

  test("graphqlSubscribe with blank query throws InterpretError"):
    val ctx  = buildCtx()
    val impl = intrinsics(QualifiedName("graphqlSubscribe")).asInstanceOf[NativeImpl]
    val ex = intercept[InterpretError] {
      impl.eval(ctx, List("http://localhost:8080", "   ", dummyHandler))
    }
    assert(ex.getMessage.contains("blank") || ex.getMessage.contains("empty") ||
           ex.getMessage.contains("must not"),
      s"expected blank-query error, got: ${ex.getMessage}")

  // ── URL scheme transformation ─────────────────────────────────────────────

  test("graphqlWsUrl converts http:// to ws://"):
    val intr = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("http://localhost:8080")
    assert(wsUrl.startsWith("ws://"), s"expected ws://, got: $wsUrl")
    assert(!wsUrl.startsWith("wss://"), s"should not be wss://, got: $wsUrl")

  test("graphqlWsUrl converts https:// to wss://"):
    val intr = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("https://api.example.com")
    assert(wsUrl.startsWith("wss://"), s"expected wss://, got: $wsUrl")

  test("graphqlWsUrl appends /graphql/ws when path is missing"):
    val intr = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("http://localhost:8080")
    assert(wsUrl.endsWith("/graphql/ws"), s"expected /graphql/ws suffix, got: $wsUrl")

  test("graphqlWsUrl strips trailing slash before appending path"):
    val intr  = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("http://localhost:8080/")
    assert(!wsUrl.contains("//graphql"), s"should not have double slash, got: $wsUrl")
    assert(wsUrl.endsWith("/graphql/ws"), s"expected /graphql/ws suffix, got: $wsUrl")

  test("graphqlWsUrl appends /ws when URL ends with /graphql"):
    val intr  = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("http://localhost:8080/graphql")
    assert(wsUrl == "ws://localhost:8080/graphql/ws",
      s"expected ws://localhost:8080/graphql/ws, got: $wsUrl")

  test("graphqlWsUrl leaves /graphql/ws path unchanged"):
    val intr  = new GraphQLIntrinsics(null)
    val wsUrl = intr.graphqlWsUrl("ws://localhost:8080/graphql/ws")
    assert(wsUrl == "ws://localhost:8080/graphql/ws",
      s"expected unchanged, got: $wsUrl")

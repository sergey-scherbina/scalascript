package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scalascript.interpreter.{Interpreter, Value}
import scalascript.server.Routes

import java.nio.file.{Files, Path}

/** Tests for v1.30 Phase 7 — typed handler auto-deserialization / serialization.
 *
 *  All tests use [[Interpreter.mountFileAsRoute]] to register handler files and
 *  then invoke the resulting route with synthetic `Request` values.  Each test
 *  creates a fresh `Routes` table and temp directory. */
class TypedHandlerTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tmpDir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Routes.clear()
    tmpDir = Files.createTempDirectory("ssc-typed-handler-test")

  override def afterEach(): Unit =
    tmpDir.toFile.listFiles().nn.foreach(_.delete())
    tmpDir.toFile.delete()

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def handlerFile(name: String, content: String): String =
    val path = tmpDir.resolve(name)
    Files.writeString(path, s"# Handler\n\n```scala\n$content\n```\n")
    path.toAbsolutePath.toString

  private def makeInterp(): Interpreter =
    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf, true)
    val interp = Interpreter(ps, Some(os.Path(tmpDir.toAbsolutePath.toString)))
    // Run an empty document to initialize the interpreter.
    interp.run(scalascript.parser.Parser.parse("# Test\n"))
    // Force http plugin to load by resolving a plugin-provided name.
    // `ensurePluginsLoaded` is package-private; we trigger it via eval.
    interp.runSnippet("Response.text(\"init\")")
    interp

  /** Build a synthetic Request InstanceV from the given parts. */
  private def mkReq(
    method:      String = "GET",
    path:        String = "/",
    pathParams:  Map[String, String] = Map.empty,
    queryParams: Map[String, String] = Map.empty,
    body:        Option[String] = None,
    headers:     Map[String, String] = Map.empty,
  ): Value.InstanceV =
    def strMap(m: Map[String, String]): Value =
      Value.MapV(m.map { (k, v) =>
        (Value.StringV(k): Value) -> (Value.StringV(v): Value)
      })
    Value.InstanceV("Request", Map(
      "method"  -> Value.StringV(method),
      "path"    -> Value.StringV(path),
      "params"  -> strMap(pathParams),
      "query"   -> strMap(queryParams),
      "headers" -> strMap(headers),
      "body"    -> body.fold[Value](Value.StringV(""))(Value.StringV(_)),
    ))

  /** Mount `file` at `method path` and invoke the handler with `req`.
   *  Returns the Response Value. */
  private def mountAndInvoke(
    method: String,
    path:   String,
    file:   String,
    req:    Value.InstanceV,
  ): Value =
    val interp = makeInterp()
    interp.mountFileAsRoute(method, path, file, Map.empty)
    val Some((entry, _)) = Routes.matchRequest(method, path): @unchecked
    entry.interpreter.invoke(entry.handler, List(req))

  private def bodyOf(v: Value): String = v match
    case Value.InstanceV("Response", f) => f("body") match
      case Value.StringV(s) => s
      case other            => Value.show(other)
    case other => Value.show(other)

  private def statusOf(v: Value): Long = v match
    case Value.InstanceV("Response", f) => f("status") match
      case Value.IntV(n) => n
      case _             => -1L
    case _ => -1L

  // ── Tests ──────────────────────────────────────────────────────────────

  // 1. Simple case class in, case class out
  test("typed handler: case class Input → case class Output"):
    val file = handlerFile("greet.ssc",
      """case class GreetInput(name: String)
        |case class GreetOutput(greeting: String)
        |(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
        |""".stripMargin)
    val req  = mkReq(pathParams = Map("name" -> "alice"))
    val resp = mountAndInvoke("GET", "/greet/:name", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("greeting")
    bodyOf(resp) should include("Hello, alice!")

  // 2. Deserialization from path param
  test("typed handler: deserialization from path param"):
    val file = handlerFile("path.ssc",
      """case class Input(id: String)
        |case class Output(found: String)
        |(input: Input) => Output(s"item-${input.id}")
        |""".stripMargin)
    val req  = mkReq("GET", "/items/42", pathParams = Map("id" -> "42"))
    val resp = mountAndInvoke("GET", "/items/:id", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("item-42")

  // 3. Deserialization from query param
  test("typed handler: deserialization from query param"):
    val file = handlerFile("query.ssc",
      """case class Input(q: String)
        |case class Output(result: String)
        |(input: Input) => Output(s"search:${input.q}")
        |""".stripMargin)
    val req  = mkReq("GET", "/search", queryParams = Map("q" -> "hello"))
    val resp = mountAndInvoke("GET", "/search", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("search:hello")

  // 4. Deserialization from JSON body
  test("typed handler: deserialization from JSON body"):
    val file = handlerFile("body.ssc",
      """case class Input(name: String, age: Int)
        |case class Output(msg: String)
        |(input: Input) => Output(s"${input.name} is ${input.age}")
        |""".stripMargin)
    val req  = mkReq("POST", "/create", body = Some("""{"name":"bob","age":30}"""))
    val resp = mountAndInvoke("POST", "/create", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("bob is 30")

  // 5. Priority: path wins over query wins over body
  test("typed handler: path param wins over query param wins over body"):
    val file = handlerFile("priority.ssc",
      """case class Input(name: String)
        |case class Output(source: String)
        |(input: Input) => Output(input.name)
        |""".stripMargin)
    // All three sources supply "name"; path should win
    val req = mkReq("GET", "/test/path-name",
      pathParams  = Map("name" -> "path-name"),
      queryParams = Map("name" -> "query-name"),
      body        = Some("""{"name":"body-name"}"""))
    val resp = mountAndInvoke("GET", "/test/:name", file, req)
    bodyOf(resp) should include("path-name")
    bodyOf(resp) should not include "query-name"
    bodyOf(resp) should not include "body-name"

  // Query wins over body (no path param)
  test("typed handler: query param wins over body when no path param"):
    val file = handlerFile("priority2.ssc",
      """case class Input(name: String)
        |case class Output(source: String)
        |(input: Input) => Output(input.name)
        |""".stripMargin)
    val req = mkReq("GET", "/test",
      queryParams = Map("name" -> "query-name"),
      body        = Some("""{"name":"body-name"}"""))
    val resp = mountAndInvoke("GET", "/test", file, req)
    bodyOf(resp) should include("query-name")
    bodyOf(resp) should not include "body-name"

  // 6. Multi-field case class from different sources
  test("typed handler: multi-field case class from multiple sources"):
    val file = handlerFile("multi.ssc",
      """case class Input(name: String, age: Int)
        |case class Output(info: String)
        |(input: Input) => Output(s"${input.name}:${input.age}")
        |""".stripMargin)
    val req = mkReq("POST", "/user/alice",
      pathParams  = Map("name" -> "alice"),  // name from path
      body        = Some("""{"age":25}"""))  // age from body
    val resp = mountAndInvoke("POST", "/user/:name", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("alice:25")

  // 7. Missing required field → 400
  test("typed handler: missing required field returns 400"):
    val file = handlerFile("missing.ssc",
      """case class Input(name: String, email: String)
        |case class Output(ok: Boolean)
        |(input: Input) => Output(true)
        |""".stripMargin)
    // Only supply name, not email
    val req  = mkReq("POST", "/create", queryParams = Map("name" -> "alice"))
    val resp = mountAndInvoke("POST", "/create", file, req)
    statusOf(resp) shouldBe 400

  // 8. (Input, Request) => Output — typed input + raw request access
  test("typed handler: (Input, Request) => Output — access both input and raw request"):
    val file = handlerFile("with-req.ssc",
      """case class GetInput(id: Int)
        |case class Out(id: Int, authorized: Boolean)
        |(input: GetInput, req: Request) => {
        |  val auth = req.headers.getOrElse("Authorization", "") != ""
        |  Out(input.id, auth)
        |}
        |""".stripMargin)
    val req  = mkReq("GET", "/item/7",
      pathParams = Map("id" -> "7"),
      headers    = Map("Authorization" -> "Bearer token123"))
    val resp = mountAndInvoke("GET", "/item/:id", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("\"id\"")
    bodyOf(resp) should include("7")
    bodyOf(resp) should include("true")

  // 9. Output: Either[Response, Output] — Right path → JSON 200
  test("typed handler: Either[Response, Output] — Right gives JSON 200"):
    val file = handlerFile("either-out.ssc",
      """case class Input(name: String)
        |case class Output(greeting: String)
        |(input: Input) => {
        |  if input.name == "" then Left(Response.text("empty name", status = 400))
        |  else Right(Output(s"Hi, ${input.name}!"))
        |}
        |""".stripMargin)
    val req  = mkReq("GET", "/hi/world", pathParams = Map("name" -> "world"))
    val resp = mountAndInvoke("GET", "/hi/:name", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("Hi, world!")

  // 10. Output: Either[Response, Output] — Left path uses custom response
  test("typed handler: Either[Response, Output] — Left passes custom Response through"):
    val file = handlerFile("either-out-left.ssc",
      """case class Input(name: String)
        |case class Output(greeting: String)
        |(input: Input) => {
        |  if input.name == "" then Left(Response.text("empty name", status = 400))
        |  else Right(Output(s"Hi, ${input.name}!"))
        |}
        |""".stripMargin)
    val req = mkReq("GET", "/hi/", pathParams = Map("name" -> ""))
    val resp = mountAndInvoke("GET", "/hi/:name", file, req)
    statusOf(resp) shouldBe 400
    bodyOf(resp) should include("empty name")

  // 11. Input: Either[Request, Input] — deserialization failure gives Left
  test("typed handler: Either[Request, Input] input — failure gives Left(req)"):
    val file = handlerFile("either-in.ssc",
      """case class CreateInput(name: String, email: String)
        |case class Created(ok: Boolean)
        |(req: Either[Request, CreateInput]) => {
        |  req match
        |    case Left(raw) => Response.text("deser failed", status = 400)
        |    case Right(input) => Created(true)
        |}
        |""".stripMargin)
    // Missing "email" — should give Left(req)
    val req  = mkReq("POST", "/users", body = Some("""{"name":"alice"}"""))
    val resp = mountAndInvoke("POST", "/users", file, req)
    statusOf(resp) shouldBe 400
    bodyOf(resp) should include("deser failed")

  // 12. Input: Either[Request, Input] — success gives Right
  test("typed handler: Either[Request, Input] input — success gives Right(input)"):
    val file = handlerFile("either-in-ok.ssc",
      """case class CreateInput(name: String, email: String)
        |case class Created(ok: Boolean)
        |(req: Either[Request, CreateInput]) => {
        |  req match
        |    case Left(raw) => Response.text("deser failed", status = 400)
        |    case Right(input) => Created(true)
        |}
        |""".stripMargin)
    val req  = mkReq("POST", "/users", body = Some("""{"name":"alice","email":"a@b.com"}"""))
    val resp = mountAndInvoke("POST", "/users", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("true")

  // 13. Primitive scalar deserialization
  test("typed handler: primitive scalar deserialization (Int from path)"):
    val file = handlerFile("scalar.ssc",
      """case class Input(id: Int)
        |case class Output(doubled: Int)
        |(input: Input) => Output(input.id * 2)
        |""".stripMargin)
    val req  = mkReq("GET", "/items/21", pathParams = Map("id" -> "21"))
    val resp = mountAndInvoke("GET", "/items/:id", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) should include("42")

  // Debug: check what the wrapper actually returns when invoked
  test("debug: typed handler wrapper invocation result"):
    import scalascript.interpreter.TypedHandlerWrapper
    val file = handlerFile("dbg2.ssc",
      """case class GreetInput(name: String)
        |case class GreetOutput(greeting: String)
        |(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
        |""".stripMargin)
    val interp = makeInterp()
    // Manually do what mountFileAsRoute does to inspect intermediate results
    import scalascript.parser.Parser
    val child = Interpreter(interp.out, Some(os.Path(file) / os.up))
    child.run(Parser.parse(os.read(os.Path(file))))
    val rawResult = child.lastResult
    println(s"rawResult: $rawResult")
    rawResult match
      case f: Value.FunV => println(s"FunV params=${f.params} paramTypes=${f.paramTypes} closure.keys=${f.closure.keySet.take(5)}")
      case _ => ()
    // Try wrapping
    val handler = TypedHandlerWrapper.wrapIfTyped(
      rawResult,
      invoke      = (fn, args) => child.invoke(fn, args),
      globalsView = child.globalsView,
      mountedPath = "/dbg2/:name",
    )
    println(s"Wrapped handler: ${handler.getClass.getSimpleName}")
    // Try invoking
    val req = mkReq(pathParams = Map("name" -> "alice"))
    val result = child.invoke(handler, List(req))
    println(s"Result: $result")
    result match
      case Value.InstanceV("Response", f) => println(s"Response body: ${f("body")}")
      case other                          => println(s"Not a response: $other")
    result shouldBe a[Value.InstanceV]

  // Debug: check paramTypes are extracted and handler is wrapped as "typed-handler" NativeFnV
  test("debug: typed handler gets wrapped as NativeFnV"):
    val file = handlerFile("dbg.ssc",
      """case class GreetInput(name: String)
        |case class GreetOutput(greeting: String)
        |(input: GreetInput) => GreetOutput(s"Hello, ${input.name}!")
        |""".stripMargin)
    val interp = makeInterp()
    Routes.clear()
    interp.mountFileAsRoute("GET", "/dbg/:name", file, Map.empty)
    val Some((entry, _)) = Routes.matchRequest("GET", "/dbg/alice"): @unchecked
    println(s"Handler class: ${entry.handler.getClass.getSimpleName}")
    // If wrapping worked, entry.handler should be the "typed-handler" NativeFnV (not "mount.static")
    entry.handler match
      case n: Value.NativeFnV if n.name == "typed-handler" => // good
      case n: Value.NativeFnV => fail(s"Got NativeFnV but name='${n.name}', expected 'typed-handler'")
      case f: Value.FunV      => fail(s"Expected NativeFnV wrapper, got FunV with paramTypes=${f.paramTypes}")
      case other              => fail(s"Unexpected: $other")

// 14. Raw Request handler passes through unchanged (not wrapped)
  test("raw Request handler is not wrapped (pass-through)"):
    val file = handlerFile("raw.ssc",
      """req => Response.text(s"raw:${req.path}")
        |""".stripMargin)
    val req  = mkReq("GET", "/ping")
    val resp = mountAndInvoke("GET", "/ping", file, req)
    statusOf(resp) shouldBe 200
    bodyOf(resp) shouldBe "raw:/ping"

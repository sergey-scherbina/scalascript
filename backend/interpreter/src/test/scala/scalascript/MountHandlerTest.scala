package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser
import scalascript.server.Routes

import java.nio.file.{Files, Path}

/** Tests for v1.30 Phase 1 (Routes refactor) and Phase 2 (mount() intrinsic).
 *
 *  Each test gets a clean `Routes` table (cleared in `beforeEach`) and
 *  a temporary directory that holds handler `.ssc` fixture files.
 *  The interpreter is configured with `baseDir` pointing at that temp dir
 *  so `mount()` can resolve file paths the same way `import` does. */
class MountHandlerTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tmpDir: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    Routes.clear()
    tmpDir = Files.createTempDirectory("ssc-mount-test")

  override def afterEach(): Unit =
    // Best-effort cleanup — test runner may share the JVM so we tidy up.
    tmpDir.toFile.listFiles().foreach(_.delete())
    tmpDir.toFile.delete()

  // ── Helpers ─────────────────────────────────────────────────────────────

  /** Write `content` to a file named `name` in the temp dir.
   *  Returns the absolute path string. */
  private def handlerFile(name: String, content: String): String =
    val path = tmpDir.resolve(name)
    Files.writeString(path, s"# Handler\n\n```scala\n$content\n```\n")
    path.toAbsolutePath.toString

  /** Run ScalaScript source in an interpreter whose baseDir is tmpDir.
   *  Returns the interpreter so callers can inspect Routes. */
  private def runWithBaseDir(code: String): Interpreter =
    val buf  = java.io.ByteArrayOutputStream()
    val ps   = java.io.PrintStream(buf, true)
    val src  = s"# Test\n\n```scala\n$code\n```\n"
    val interp = Interpreter(ps, Some(os.Path(tmpDir.toAbsolutePath.toString)))
    interp.run(Parser.parse(src))
    interp

  /** Invoke a registered route and return the response. */
  private def invoke(method: String, path: String,
                     params: Map[String, String] = Map.empty): Value =
    val Some((entry, capturedParams)) = Routes.matchRequest(method, path): @unchecked
    val allParams = capturedParams ++ params
    val reqFields: Map[String, Value] = Map(
      "method"  -> Value.StringV(method),
      "path"    -> Value.StringV(path),
      "params"  -> Value.MapV(allParams.map { (k, v) =>
                      (Value.StringV(k): Value) -> (Value.StringV(v): Value) }),
      "query"   -> Value.MapV(Map.empty),
      "headers" -> Value.MapV(Map.empty),
      "body"    -> Value.InstanceV("None", Map.empty)
    )
    val req = Value.InstanceV("Request", reqFields)
    // Detect 2-arg handler and pass mountCtx map
    val args: List[Value] = entry.handler match
      case Value.FunV(ps, _, _, _, _, _, _, _) if ps.length >= 2 =>
        val ctxMap = Value.MapV(entry.mountCtx.map { (k, v) =>
          (Value.StringV(k): Value) -> v
        })
        List(req, ctxMap)
      case _ => List(req)
    entry.interpreter.invoke(entry.handler, args)

  // ── Phase 1: Routes refactor ─────────────────────────────────────────────

  test("Routes.register replaces existing entry for same (method, path)"):
    val interp = Interpreter()
    val handler1 = Value.NativeFnV("h1", scalascript.interpreter.Computation.pureFn(_ =>
      Value.StringV("first")))
    val handler2 = Value.NativeFnV("h2", scalascript.interpreter.Computation.pureFn(_ =>
      Value.StringV("second")))
    Routes.register("GET", "/ping", handler1, interp)
    Routes.all.length shouldBe 1
    Routes.register("GET", "/ping", handler2, interp)
    Routes.all.length shouldBe 1  // still one entry — replaced in place
    val Some((entry, _)) = Routes.matchRequest("GET", "/ping"): @unchecked
    entry.handler shouldBe handler2

  test("Routes.register keeps different (method, path) pairs separate"):
    val interp = Interpreter()
    val h = Value.UnitV
    Routes.register("GET",  "/a", h, interp)
    Routes.register("POST", "/a", h, interp)
    Routes.register("GET",  "/b", h, interp)
    Routes.all.length shouldBe 3

  test("Routes.removeBySource removes matching entries"):
    val interp = Interpreter()
    val h = Value.UnitV
    Routes.register("GET",  "/a", h, interp, source = Some("/tmp/a.ssc"))
    Routes.register("POST", "/a", h, interp, source = Some("/tmp/a.ssc"))
    Routes.register("GET",  "/b", h, interp, source = Some("/tmp/b.ssc"))
    Routes.all.length shouldBe 3
    Routes.removeBySource("/tmp/a.ssc")
    Routes.all.length shouldBe 1
    Routes.matchRequest("GET",  "/a") shouldBe None
    Routes.matchRequest("POST", "/a") shouldBe None
    Routes.matchRequest("GET",  "/b") should not be None

  test("Routes.removeBySource is a no-op when no matching source"):
    val interp = Interpreter()
    Routes.register("GET", "/x", Value.UnitV, interp, source = Some("/tmp/x.ssc"))
    Routes.removeBySource("/tmp/nonexistent.ssc")
    Routes.all.length shouldBe 1

  test("Routes.all preserves insertion order (LinkedHashMap)"):
    val interp = Interpreter()
    val h = Value.UnitV
    Routes.register("GET", "/first",  h, interp)
    Routes.register("GET", "/second", h, interp)
    Routes.register("GET", "/third",  h, interp)
    Routes.all.map(_.path) shouldBe List("/first", "/second", "/third")

  test("Route entry carries source and mountCtx fields"):
    val interp = Interpreter()
    val ctx = Map("coll" -> Value.StringV("users"))
    Routes.register("GET", "/u", Value.UnitV, interp,
      source = Some("/handlers/u.ssc"), mountCtx = ctx)
    val Some((entry, _)) = Routes.matchRequest("GET", "/u"): @unchecked
    entry.source    shouldBe Some("/handlers/u.ssc")
    entry.mountCtx  shouldBe ctx

  test("route() registered without source has source = None and empty mountCtx"):
    runWithBaseDir("""route("GET", "/ping") { req => Response.text("pong") }""")
    val Some((entry, _)) = Routes.matchRequest("GET", "/ping"): @unchecked
    entry.source    shouldBe None
    entry.mountCtx  shouldBe Map.empty

  // ── Phase 2: mount() intrinsic ───────────────────────────────────────────

  test("mount() with static Response value — auto-wrapped bare Value"):
    handlerFile("ping.ssc", """Response.text("pong")""")
    runWithBaseDir("""mount("GET", "/ping", "ping.ssc")""")

    val Some((entry, _)) = Routes.matchRequest("GET", "/ping"): @unchecked
    entry.source shouldBe Some(tmpDir.resolve("ping.ssc").toAbsolutePath.toString)

    val resp = invoke("GET", "/ping")
    resp match
      case Value.InstanceV("Response", fields) =>
        fields("body") shouldBe Value.StringV("pong")
      case other => fail(s"Expected Response, got $other")

  test("mount() with 1-arg handler (req => Response)"):
    handlerFile("hello.ssc", """req => Response.text("Hello!")""")
    runWithBaseDir("""mount("GET", "/hello", "hello.ssc")""")

    val resp = invoke("GET", "/hello")
    resp match
      case Value.InstanceV("Response", fields) =>
        fields("body") shouldBe Value.StringV("Hello!")
      case other => fail(s"Expected Response, got $other")

  test("mount() with 1-arg handler uses path params from request"):
    handlerFile("greet.ssc",
      """req => {
        |  val name = req.params("name")
        |  Response.text(s"Hello, $name!")
        |}""".stripMargin)
    runWithBaseDir("""mount("GET", "/greet/:name", "greet.ssc")""")

    val resp = invoke("GET", "/greet/alice")
    resp match
      case Value.InstanceV("Response", fields) =>
        fields("body") shouldBe Value.StringV("Hello, alice!")
      case other => fail(s"Expected Response, got $other")

  test("mount() with 2-arg handler (req, ctx) => Response — ctx forwarded"):
    handlerFile("entity.ssc",
      """(req, ctx) => {
        |  val coll = ctx("coll").toString
        |  Response.text(coll)
        |}""".stripMargin)
    runWithBaseDir(
      """mount("GET", "/users", "entity.ssc", Map("coll" -> "users"))""")

    val Some((entry, _)) = Routes.matchRequest("GET", "/users"): @unchecked
    entry.mountCtx.get("coll") shouldBe Some(Value.StringV("users"))

    val resp = invoke("GET", "/users")
    resp match
      case Value.InstanceV("Response", fields) =>
        fields("body") shouldBe Value.StringV("users")
      case other => fail(s"Expected Response, got $other")

  test("mount() same file registered twice for different paths (ctx distinguishes)"):
    handlerFile("entity.ssc",
      """(req, ctx) => {
        |  val coll = ctx("coll").toString
        |  Response.text(coll)
        |}""".stripMargin)
    runWithBaseDir(
      """mount("GET", "/users",    "entity.ssc", Map("coll" -> "users"))
        |mount("GET", "/products", "entity.ssc", Map("coll" -> "products"))
        |""".stripMargin)

    Routes.all.length shouldBe 2

    val usersResp = invoke("GET", "/users")
    usersResp match
      case Value.InstanceV("Response", f) => f("body") shouldBe Value.StringV("users")
      case other => fail(s"Unexpected: $other")

    val productsResp = invoke("GET", "/products")
    productsResp match
      case Value.InstanceV("Response", f) => f("body") shouldBe Value.StringV("products")
      case other => fail(s"Unexpected: $other")

  test("mount() replaces existing route for same method+path (idempotent)"):
    handlerFile("v1.ssc", """Response.text("v1")""")
    handlerFile("v2.ssc", """Response.text("v2")""")
    runWithBaseDir(
      """mount("GET", "/ping", "v1.ssc")
        |mount("GET", "/ping", "v2.ssc")
        |""".stripMargin)

    Routes.all.length shouldBe 1
    val resp = invoke("GET", "/ping")
    resp match
      case Value.InstanceV("Response", fields) =>
        fields("body") shouldBe Value.StringV("v2")  // second mount wins
      case other => fail(s"Expected Response, got $other")

  test("mount() sets source to the resolved absolute path of the file"):
    val absPath = handlerFile("handler.ssc", """Response.text("ok")""")
    runWithBaseDir("""mount("GET", "/ok", "handler.ssc")""")
    val Some((entry, _)) = Routes.matchRequest("GET", "/ok"): @unchecked
    entry.source shouldBe Some(absPath)

  test("Routes.removeBySource removes mount()-registered entries"):
    val absPath = handlerFile("cleanup.ssc", """Response.text("bye")""")
    runWithBaseDir("""mount("GET", "/bye", "cleanup.ssc")""")
    Routes.all.length shouldBe 1
    Routes.removeBySource(absPath)
    Routes.all.length shouldBe 0
    Routes.matchRequest("GET", "/bye") shouldBe None

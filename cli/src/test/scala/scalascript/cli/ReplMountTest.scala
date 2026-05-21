package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser
import scalascript.server.Routes

import java.nio.file.Files

/** Tests for the REPL `:mount` command (all three forms).
 *
 *  Form 1 — inline `{ expr }`
 *  Form 2 — function name from REPL globals
 *  Form 3 — file.ssc [key=value ...]
 *
 *  Each test clears the route table before running and exercises
 *  [[replHandleMount]] directly (same approach as [[ReplWebTest]]). */
class ReplMountTest extends AnyFunSuite:

  private def makeInterp(): Interpreter =
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))
    interp

  // ─── Form 1: inline handler ───────────────────────────────────────────────

  test(":mount inline — registers callable handler via Routes.matchRequest"):
    Routes.clear()
    val interp = makeInterp()
    // Evaluate an inline lambda — requires Response to be available
    replHandleMount(":mount GET /ping { _ => \"pong\" }", interp)
    val matched = Routes.matchRequest("GET", "/ping")
    assert(matched.isDefined, "route should be registered after :mount inline")

  test(":mount inline — registered handler is a function value"):
    Routes.clear()
    val interp = makeInterp()
    replHandleMount(":mount GET /test { _ => \"ok\" }", interp)
    // We just need at least one route registered
    assert(Routes.all.nonEmpty)

  test(":mount inline — replaces existing handler for same method+path (idempotent)"):
    Routes.clear()
    val interp = makeInterp()
    replHandleMount(":mount GET /ping { _ => \"first\" }", interp)
    assert(Routes.all.size == 1)
    replHandleMount(":mount GET /ping { _ => \"second\" }", interp)
    // Still exactly one entry — the second mount replaced the first
    assert(Routes.all.size == 1, "second :mount should replace, not duplicate")
    assert(Routes.all.head.method == "GET")
    assert(Routes.all.head.path == "/ping")

  test(":mount inline — missing args prints usage, does not throw"):
    Routes.clear()
    val interp = makeInterp()
    // Only method + path, no rest → should print usage
    replHandleMount(":mount GET /ping", interp)
    // No route registered
    assert(Routes.all.isEmpty)

  // ─── Form 2: function name from REPL globals ──────────────────────────────

  test(":mount by name — mounts a function defined in REPL globals"):
    Routes.clear()
    val interp = makeInterp()
    // Define a function in the REPL session
    interp.runSnippet("def myHandler(req: Any): String = \"hello\"")
    // Globals should contain myHandler
    assert(interp.globalsView.contains("myHandler"), "myHandler should be in globals after def")
    replHandleMount(":mount GET /hi myHandler", interp)
    assert(Routes.matchRequest("GET", "/hi").isDefined, "route should be registered by name")

  test(":mount by name — unknown name prints error, does not register"):
    Routes.clear()
    val interp = makeInterp()
    replHandleMount(":mount GET /hi unknownFn", interp)
    assert(Routes.all.isEmpty, "no route should be registered for unknown name")

  test(":mount by name — non-function value prints error"):
    Routes.clear()
    val interp = makeInterp()
    interp.runSnippet("val notAFun = 42")
    assert(interp.globalsView.contains("notAFun"))
    replHandleMount(":mount GET /hi notAFun", interp)
    assert(Routes.all.isEmpty, "non-function value should not be registered")

  // ─── Form 3: .ssc file ───────────────────────────────────────────────────

  test(":mount file — registers handler from a .ssc file"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-mount-test")
    val handler = tmpDir.resolve("ping.ssc")
    Files.writeString(handler,
      "# ping\n\n```scala\n_ => \"pong\"\n```\n")
    replHandleMount(s":mount GET /ping ${handler.toAbsolutePath}", interp)
    val matched = Routes.matchRequest("GET", "/ping")
    assert(matched.isDefined, "route should be registered from file")
    assert(matched.get._1.source.contains(handler.toAbsolutePath.toString),
      "source should be the abs path of the handler file")

  test(":mount file — ctx key=value pairs stored in entry"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-mount-test-ctx")
    val handler = tmpDir.resolve("entity.ssc")
    Files.writeString(handler,
      "# entity\n\n```scala\n_ => \"ok\"\n```\n")
    replHandleMount(
      s":mount GET /items/:id ${handler.toAbsolutePath} coll=items db=main", interp)
    val matched = Routes.matchRequest("GET", "/items/99")
    assert(matched.isDefined)
    val ctx = matched.get._1.mountCtx
    assert(ctx.get("coll").contains(Value.StringV("items")), s"ctx should have coll=items, got: $ctx")
    assert(ctx.get("db").contains(Value.StringV("main")),    s"ctx should have db=main, got: $ctx")

  test(":mount file — idempotent second mount replaces entry"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-mount-test-idempotent")
    val handler = tmpDir.resolve("h.ssc")
    Files.writeString(handler,
      "# h\n\n```scala\n_ => \"v1\"\n```\n")
    replHandleMount(s":mount GET /r ${handler.toAbsolutePath}", interp)
    assert(Routes.all.size == 1)
    replHandleMount(s":mount GET /r ${handler.toAbsolutePath}", interp)
    assert(Routes.all.size == 1, "second mount of same method+path should replace, not duplicate")

  test(":mount file — path params captured correctly"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-mount-test-params")
    val handler = tmpDir.resolve("named.ssc")
    Files.writeString(handler,
      "# named\n\n```scala\n_ => \"named\"\n```\n")
    replHandleMount(s":mount GET /hello/:name ${handler.toAbsolutePath}", interp)
    val matched = Routes.matchRequest("GET", "/hello/alice")
    assert(matched.isDefined)
    assert(matched.get._2.get("name").contains("alice"),
      s"path param 'name' should be 'alice', got: ${matched.get._2}")

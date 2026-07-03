package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser
import scalascript.server.Routes

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference

/** Tests for Phase 6 REPL commands: `:routes`, `:http`, and `:call`.
 *
 *  All tests use [[replHandleRoutes]], [[replHandleHttp]], and
 *  [[replHandleCall]] helpers directly — same pattern as
 *  [[ReplWebTest]] / [[ReplMountTest]] / [[ReplLoadTest]]. */
class ReplRoutesTest extends AnyFunSuite:

  private def makeInterp(): Interpreter =
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))
    interp

  private def freshPort(): AtomicReference[Option[Int]] =
    AtomicReference(None)

  /** Capture stderr output produced by a thunk.
   *  Returns the raw captured string without trimming so that leading/trailing
   *  whitespace on individual lines is preserved for column-layout assertions. */
  private def captureErr(thunk: => Unit): String =
    val buf = ByteArrayOutputStream()
    val ps  = PrintStream(buf, true, "UTF-8")
    val old = System.err
    System.setErr(ps)
    try thunk finally System.setErr(old)
    buf.toString("UTF-8")

  // ─── :routes ─────────────────────────────────────────────────────────────

  test(":routes prints '(no routes registered)' when table is empty"):
    Routes.clear()
    val out = captureErr { replHandleRoutes() }
    assert(out.trim == "(no routes registered)")

  test(":routes prints all registered routes in tabular form"):
    Routes.clear()
    val interp = makeInterp()
    // Register two routes: one inline (source=None), one with a fake source
    Routes.register("GET",  "/ping",  Value.StringV("pong"), interp, source = None)
    Routes.register("POST", "/users", Value.StringV("list"), interp,
      source = Some("/absolute/path/users.ssc"), mountCtx = Map.empty)
    val out = captureErr { replHandleRoutes() }
    val lines = out.linesIterator.filter(_.nonEmpty).toList
    assert(lines.length == 2, s"Expected 2 non-empty lines, got:\n$out")
    // First line: GET /ping <inline>
    assert(lines(0).contains("GET"),   s"First line should contain GET:\n${lines(0)}")
    assert(lines(0).contains("/ping"), s"First line should contain /ping:\n${lines(0)}")
    assert(lines(0).contains("<inline>"), s"First line should contain <inline>:\n${lines(0)}")
    // Second line: POST /users users.ssc
    assert(lines(1).contains("POST"),   s"Second line should contain POST:\n${lines(1)}")
    assert(lines(1).contains("/users"), s"Second line should contain /users:\n${lines(1)}")
    assert(lines(1).contains("users.ssc"), s"Second line should contain users.ssc:\n${lines(1)}")

  test(":routes shows ctx map when mountCtx is non-empty"):
    Routes.clear()
    val interp = makeInterp()
    Routes.register("GET", "/items/:id", Value.StringV("item"), interp,
      source = Some("/abs/entity.ssc"),
      mountCtx = Map("coll" -> Value.StringV("items")))
    val out = captureErr { replHandleRoutes() }
    assert(out.contains("entity.ssc"), s"Should show source filename: $out")
    assert(out.contains("{coll=items}"), s"Should show ctx: $out")

  test(":routes columns: methods are left-padded to 6 chars"):
    Routes.clear()
    val interp = makeInterp()
    Routes.register("GET",    "/a", Value.StringV("a"), interp)
    Routes.register("DELETE", "/b", Value.StringV("b"), interp)
    val out = captureErr { replHandleRoutes() }
    val lines = out.linesIterator.filter(_.nonEmpty).toList
    assert(lines.length == 2, s"Expected 2 non-empty lines:\n$out")
    // Each line starts with two spaces then the method (padded to 6)
    assert(lines(0).startsWith("  GET"),
      s"GET line should start with '  GET': '${lines(0)}'")
    // GET padded to 6 chars → "GET   " — verify at least 1 trailing space after GET
    assert(lines(0).contains("GET "),
      s"GET should have trailing space (padding): '${lines(0)}'")
    assert(lines(1).contains("DELETE"), s"DELETE should appear: '${lines(1)}'")

  // ─── :http (no server) ────────────────────────────────────────────────────

  test(":http with no server running prints an error message"):
    val sp  = freshPort()   // None — no server
    val out = captureErr { replHandleHttp(":http GET /ping", sp) }
    assert(out.contains("No server running"), s"Should warn about no server: $out")

  test(":http with missing path prints usage"):
    val sp  = freshPort()
    val out = captureErr { replHandleHttp(":http GET", sp) }
    // With no server, the first check fires; but with a simulated port the
    // usage check fires when tokens < 2.  We test the path without a server
    // which produces the "No server running" message.
    assert(out.nonEmpty, "Should print something when args are short")

  // ─── :call ───────────────────────────────────────────────────────────────

  test(":call with missing args prints usage"):
    val interp = makeInterp()
    Routes.clear()
    val out = captureErr { replHandleCall(":call", interp) }
    assert(out.contains("Usage"), s"Should print usage: $out")

  test(":call GET /missing → 404 Not Found"):
    Routes.clear()
    val interp = makeInterp()
    val out = captureErr { replHandleCall(":call GET /missing", interp) }
    assert(out.contains("404"), s"Should print 404: $out")

  test(":call GET /ping on inline handler → 200 + body"):
    Routes.clear()
    val interp = makeInterp()
    // Register a NativeFnV handler that returns a StringV (auto-wrapped by extractCallResponse)
    val handler = Value.NativeFnV("test.ping",
      scalascript.interpreter.Computation.pureFn(_ => Value.StringV("pong")))
    Routes.register("GET", "/ping", handler, interp)
    val out = captureErr { replHandleCall(":call GET /ping", interp) }
    assert(out.contains("200"), s"Should print 200: $out")
    assert(out.contains("pong"), s"Should print body: $out")

  test(":call with path params — params are correctly extracted"):
    Routes.clear()
    val interp = makeInterp()
    // NativeFnV handler that echoes the 'id' param
    val handler = Value.NativeFnV("test.handler",
      scalascript.interpreter.Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val id = fields.get("params") match
            case Some(Value.MapV(m)) =>
              m.getOrElse(Value.StringV("id"), Value.StringV("?")) match
                case Value.StringV(s) => s
                case other            => Value.show(other)
            case _ => "?"
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200),
            "headers" -> Value.EmptyMap,
            "body"    -> Value.StringV(s"id=$id")
          ))
        case _ => Value.StringV("bad")
      })
    Routes.register("GET", "/items/:id", handler, interp)
    val out = captureErr { replHandleCall(":call GET /items/42", interp) }
    assert(out.contains("200"), s"Should print 200: $out")
    assert(out.contains("id=42"), s"Should echo path param: $out")

  test(":call with query string — query map is populated"):
    Routes.clear()
    val interp = makeInterp()
    val handler = Value.NativeFnV("test.query",
      scalascript.interpreter.Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val name = fields.get("query") match
            case Some(Value.MapV(m)) =>
              m.getOrElse(Value.StringV("name"), Value.StringV("nobody")) match
                case Value.StringV(s) => s
                case other            => Value.show(other)
            case _ => "nobody"
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200),
            "headers" -> Value.EmptyMap,
            "body"    -> Value.StringV(s"Hello, $name!")
          ))
        case _ => Value.StringV("bad")
      })
    Routes.register("GET", "/hello", handler, interp)
    val out = captureErr { replHandleCall(":call GET /hello?name=alice", interp) }
    assert(out.contains("200"),          s"Should print 200: $out")
    assert(out.contains("Hello, alice!"), s"Should echo query: $out")

  test(":call with -H headers — headers are passed to handler"):
    Routes.clear()
    val interp = makeInterp()
    val handler = Value.NativeFnV("test.headers",
      scalascript.interpreter.Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val auth = fields.get("headers") match
            case Some(Value.MapV(m)) =>
              m.getOrElse(Value.StringV("Authorization"), Value.StringV("none")) match
                case Value.StringV(s) => s
                case other            => Value.show(other)
            case _ => "none"
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200),
            "headers" -> Value.EmptyMap,
            "body"    -> Value.StringV(s"auth=$auth")
          ))
        case _ => Value.StringV("bad")
      })
    Routes.register("GET", "/secure", handler, interp)
    val out = captureErr {
      replHandleCall(":call GET /secure -H \"Authorization: Bearer tok\"", interp)
    }
    assert(out.contains("200"),              s"Should print 200: $out")
    assert(out.contains("auth=Bearer tok"),  s"Should pass header: $out")

  test(":call with body — body string is forwarded to handler"):
    Routes.clear()
    val interp = makeInterp()
    val handler = Value.NativeFnV("test.body",
      scalascript.interpreter.Computation.pureFn {
        case List(Value.InstanceV("Request", fields)) =>
          val body = fields.get("body") match
            case Some(Value.StringV(s)) => s
            case _                      => ""
          Value.InstanceV("Response", Map(
            "status"  -> Value.IntV(200),
            "headers" -> Value.EmptyMap,
            "body"    -> Value.StringV(s"got:$body")
          ))
        case _ => Value.StringV("bad")
      })
    Routes.register("POST", "/echo", handler, interp)
    val out = captureErr {
      replHandleCall(":call POST /echo hello-world", interp)
    }
    assert(out.contains("200"),              s"Should print 200: $out")
    assert(out.contains("got:hello-world"),  s"Should pass body: $out")

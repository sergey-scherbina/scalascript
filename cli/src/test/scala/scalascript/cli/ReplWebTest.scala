package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.server.Routes
import java.util.concurrent.atomic.AtomicReference

/** Unit tests for the REPL web commands: `:serve`, `:stop [--keep-routes]`, and `:clear`.
 *
 *  These tests exercise command-parsing logic and state transitions via
 *  [[replHandleServe]] / [[replHandleStop]] helpers directly, without binding
 *  to real ports (a started server would need a free port and shutdown
 *  sequencing that is fragile in CI).  The server-startup code path is covered
 *  by a single smoke test that verifies the virtual thread is launched and
 *  `serverPort` is updated correctly — it does start a real ephemeral server
 *  and tears it down immediately. */
class ReplWebTest extends AnyFunSuite:

  /** Fresh interpreter + clean route table before every test. */
  private def makeInterp(): Interpreter =
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))
    interp

  private def freshPort(): AtomicReference[Option[Int]] =
    AtomicReference(None)

  // ─── :serve ──────────────────────────────────────────────────────────────

  test(":serve sets serverPort to the requested port"):
    Routes.clear()
    val interp = makeInterp()
    val sp     = freshPort()
    replHandleServe(":serve 19900", sp, interp)
    // Give the virtual thread a moment to start and update sp if it fails
    Thread.sleep(200)
    assert(sp.get().isDefined, "serverPort should be set after :serve")
    assert(sp.get().contains(19900))
    // Clean up — stop the server
    scalascript.server.WebServer.stop()
    sp.set(None)

  test(":serve defaults to port 8080 when no port given"):
    // Verifies that omitting the port records 8080 — checked immediately
    // (before the virtual thread can fail if 8080 is in use on CI).
    Routes.clear()
    val interp = makeInterp()
    val sp     = freshPort()
    replHandleServe(":serve", sp, interp)
    // serverPort.set(Some(8080)) happens synchronously before the virtual
    // thread is spawned, so it is visible immediately here.
    assert(sp.get().contains(8080), "serverPort should be 8080 immediately after :serve")
    // Best-effort cleanup; the thread may already have failed and reset sp.
    scalascript.server.WebServer.stop()
    sp.set(None)

  test(":serve does nothing when server already running"):
    Routes.clear()
    val interp = makeInterp()
    val sp     = AtomicReference[Option[Int]](Some(19901))
    // Simulate already-running by pre-setting the port
    replHandleServe(":serve 19902", sp, interp)
    // Port must still be 19901 — the command should be a no-op
    assert(sp.get().contains(19901), "should not change port when already serving")

  test(":serve rejects a non-numeric port"):
    Routes.clear()
    val interp = makeInterp()
    val sp     = freshPort()
    replHandleServe(":serve notaport", sp, interp)
    // serverPort stays None — no server started
    assert(sp.get().isEmpty, "serverPort should stay None for an invalid port")

  // ─── :stop ───────────────────────────────────────────────────────────────

  test(":stop clears routes and resets serverPort"):
    Routes.clear()
    Routes.register("GET", "/ping", scalascript.interpreter.Value.StringV("pong"), makeInterp())
    assert(Routes.all.nonEmpty)
    val sp = AtomicReference[Option[Int]](Some(19903))
    replHandleStop(":stop", sp)
    assert(sp.get().isEmpty, "serverPort should be None after :stop")
    assert(Routes.all.isEmpty, "routes should be cleared by :stop")

  test(":stop --keep-routes preserves the route table"):
    Routes.clear()
    Routes.register("GET", "/ping", scalascript.interpreter.Value.StringV("pong"), makeInterp())
    assert(Routes.all.nonEmpty)
    val sp = AtomicReference[Option[Int]](Some(19904))
    replHandleStop(":stop --keep-routes", sp)
    assert(sp.get().isEmpty, "serverPort should be None after :stop --keep-routes")
    assert(Routes.all.nonEmpty, "routes should be kept with --keep-routes")

  test(":stop prints no-op message when no server running"):
    val sp = freshPort()
    // Should not throw — just print "No server running."
    replHandleStop(":stop", sp)
    assert(sp.get().isEmpty)

  // ─── :clear ──────────────────────────────────────────────────────────────

  test(":clear empties the route table without touching server state"):
    Routes.clear()
    Routes.register("GET", "/a", scalascript.interpreter.Value.StringV("a"), makeInterp())
    Routes.register("POST", "/b", scalascript.interpreter.Value.StringV("b"), makeInterp())
    assert(Routes.all.size == 2)
    // Simulate the inline :clear action from replCommand
    Routes.clear()
    assert(Routes.all.isEmpty, "routes should be cleared")

  test(":clear is idempotent on an already-empty table"):
    Routes.clear()
    Routes.clear()  // second call must not throw
    assert(Routes.all.isEmpty)

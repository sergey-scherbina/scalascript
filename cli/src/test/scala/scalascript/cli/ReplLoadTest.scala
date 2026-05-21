package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.server.Routes

import java.nio.file.Files

/** Tests for `:load`, `:reload`, and `:unmount` REPL commands (v1.30 Phase 5).
 *
 *  Each test clears the route table before running and exercises the
 *  handler functions directly — same pattern as [[ReplMountTest]]. */
class ReplLoadTest extends AnyFunSuite:

  private def makeInterp(): Interpreter =
    val interp = Interpreter()
    interp.run(Parser.parse("# REPL\n"))
    interp

  // ─── :load ───────────────────────────────────────────────────────────────

  test(":load — registers routes from a file containing route() calls"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-load-test")
    val file   = tmpDir.resolve("users.ssc")
    Files.writeString(file,
      """|# users
         |
         |```scala
         |route("GET", "/users") { _ => "list" }
         |route("POST", "/users") { _ => "create" }
         |```
         |""".stripMargin)
    replHandleLoad(s":load ${file.toAbsolutePath}", interp)
    val all = Routes.all
    assert(all.size == 2, s"expected 2 routes, got ${all.size}")
    assert(Routes.matchRequest("GET",  "/users").isDefined)
    assert(Routes.matchRequest("POST", "/users").isDefined)

  test(":load — routes have source = Some(absPath)"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-load-source-test")
    val file   = tmpDir.resolve("ping.ssc")
    Files.writeString(file,
      """|# ping
         |
         |```scala
         |route("GET", "/ping") { _ => "pong" }
         |```
         |""".stripMargin)
    val absPath = file.toAbsolutePath.toString
    replHandleLoad(s":load $absPath", interp)
    val entry = Routes.matchRequest("GET", "/ping")
    assert(entry.isDefined)
    assert(entry.get._1.source.contains(absPath),
      s"expected source=${absPath}, got ${entry.get._1.source}")

  test(":load — routes have style = load"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-load-style-test")
    val file   = tmpDir.resolve("r.ssc")
    Files.writeString(file,
      """|# r
         |
         |```scala
         |route("GET", "/r") { _ => "ok" }
         |```
         |""".stripMargin)
    replHandleLoad(s":load ${file.toAbsolutePath}", interp)
    val entry = Routes.all.find(_.path == "/r")
    assert(entry.isDefined)
    assert(entry.get.style == "load", s"expected style=load, got ${entry.get.style}")

  test(":load — second :load replaces previous routes cleanly"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-load-replace-test")
    val file   = tmpDir.resolve("api.ssc")
    // First version: two routes
    Files.writeString(file,
      """|# api
         |
         |```scala
         |route("GET",  "/a") { _ => "a" }
         |route("POST", "/a") { _ => "post-a" }
         |```
         |""".stripMargin)
    replHandleLoad(s":load ${file.toAbsolutePath}", interp)
    assert(Routes.all.size == 2)
    // Second version: only one route (POST removed)
    Files.writeString(file,
      """|# api
         |
         |```scala
         |route("GET", "/a") { _ => "a-v2" }
         |```
         |""".stripMargin)
    replHandleLoad(s":load ${file.toAbsolutePath}", interp)
    // POST /a should be gone; GET /a should still exist
    assert(Routes.all.size == 1, s"expected 1 route after second load, got ${Routes.all.size}")
    assert(Routes.matchRequest("GET",  "/a").isDefined)
    assert(Routes.matchRequest("POST", "/a").isEmpty,
      "POST /a should have been removed by second :load")

  test(":load — non-existent file prints error, no route registered"):
    Routes.clear()
    val interp = makeInterp()
    replHandleLoad(":load /tmp/does-not-exist-at-all.ssc", interp)
    assert(Routes.all.isEmpty, "no route should be registered for missing file")

  // ─── :reload after :load ──────────────────────────────────────────────────

  test(":reload — re-runs a :load-style file"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-reload-load-test")
    val file   = tmpDir.resolve("data.ssc")
    Files.writeString(file,
      """|# data
         |
         |```scala
         |route("GET", "/data") { _ => "v1" }
         |```
         |""".stripMargin)
    val absPath = file.toAbsolutePath.toString
    replHandleLoad(s":load $absPath", interp)
    assert(Routes.matchRequest("GET", "/data").isDefined)
    // Update file
    Files.writeString(file,
      """|# data
         |
         |```scala
         |route("GET",  "/data") { _ => "v2" }
         |route("POST", "/data") { _ => "create" }
         |```
         |""".stripMargin)
    replHandleReload(s":reload $absPath", interp)
    assert(Routes.matchRequest("GET",  "/data").isDefined)
    assert(Routes.matchRequest("POST", "/data").isDefined,
      "POST /data should appear after :reload with updated file")

  // ─── :reload after :mount file.ssc ────────────────────────────────────────

  test(":reload — re-mounts a :mount-style file"):
    Routes.clear()
    val interp = makeInterp()
    val tmpDir = Files.createTempDirectory("repl-reload-mount-test")
    val handler = tmpDir.resolve("h.ssc")
    Files.writeString(handler,
      "# h\n\n```scala\n_ => \"v1\"\n```\n")
    val absPath = handler.toAbsolutePath.toString
    replHandleMount(s":mount GET /h $absPath", interp)
    val entryBefore = Routes.matchRequest("GET", "/h")
    assert(entryBefore.isDefined)
    assert(entryBefore.get._1.style == "mount",
      s"expected style=mount, got ${entryBefore.get._1.style}")
    // Update file content (still same method + path via :reload)
    Files.writeString(handler,
      "# h\n\n```scala\n_ => \"v2\"\n```\n")
    replHandleReload(s":reload $absPath", interp)
    val entryAfter = Routes.matchRequest("GET", "/h")
    assert(entryAfter.isDefined, "route should still exist after :reload")
    assert(entryAfter.get._1.method == "GET")
    assert(entryAfter.get._1.path   == "/h")

  test(":reload — unknown file prints error"):
    Routes.clear()
    val interp = makeInterp()
    // No route registered for this file — should print error, not throw
    replHandleReload(":reload /tmp/unknown-file-xyzzy.ssc", interp)
    // Nothing should be in the route table
    assert(Routes.all.isEmpty)

  // ─── :unmount ─────────────────────────────────────────────────────────────

  test(":unmount — removes a registered route"):
    Routes.clear()
    val interp = makeInterp()
    replHandleMount(":mount GET /ping { _ => \"pong\" }", interp)
    assert(Routes.matchRequest("GET", "/ping").isDefined)
    replHandleUnmount(":unmount GET /ping")
    assert(Routes.matchRequest("GET", "/ping").isEmpty,
      "route should be removed after :unmount")

  test(":unmount — non-existent route prints error, does not throw"):
    Routes.clear()
    replHandleUnmount(":unmount GET /not-there")
    // Should not throw; route table still empty
    assert(Routes.all.isEmpty)

  test(":unmount — removes only the specified route, not others"):
    Routes.clear()
    val interp = makeInterp()
    replHandleMount(":mount GET  /a { _ => \"a\" }", interp)
    replHandleMount(":mount POST /a { _ => \"b\" }", interp)
    assert(Routes.all.size == 2)
    replHandleUnmount(":unmount GET /a")
    assert(Routes.matchRequest("GET",  "/a").isEmpty,  "GET /a should be removed")
    assert(Routes.matchRequest("POST", "/a").isDefined, "POST /a should remain")

  test(":unmount — missing path argument prints usage, does not throw"):
    Routes.clear()
    replHandleUnmount(":unmount GET")   // only method, no path
    assert(Routes.all.isEmpty)

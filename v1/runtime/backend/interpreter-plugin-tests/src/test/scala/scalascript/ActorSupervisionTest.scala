package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.compiler.plugin.os.OsInterpreterPlugin
import scalascript.parser.Parser

/** Unit tests for v1.6 Phase 2 — Erlang-style actor supervision.
 *  All tests run through the interpreter only; JvmGen / JsGen ports
 *  land in Iteration 3+4.  Supervisor library tests use std/actors.ssc
 *  and are in Iteration 2. */
class ActorSupervisionTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    capturedWith(List(new ActorsInterpreterPlugin))(code)

  private def capturedWith(plugins: List[scalascript.backend.spi.Backend])(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    val _i = Interpreter(ps); _i.installPlugins(plugins); _i.run(module)
    ps.flush()
    buf.toString.trim

  // ── trapExit ──────────────────────────────────────────────────────

  test("trapExit(true) delivers Exit as message instead of crashing"):
    captured("""
      runActors {
        val watcher = spawn { () =>
          trapExit(true)
          val worker = spawn { () =>
            val me = self()
            exit(me, "boom")
          }
          link(worker)
          receive {
            case Exit(from, reason) => println("caught: " + reason)
          }
        }
      }
    """) shouldBe "caught: boom"

  test("trapExit(false) default: linked actor crash propagates silently"):
    // Without trapExit, a linked actor's crash kills the linked actor too.
    // The root actor has trapExit=false so it just dies with no output.
    captured("""
      runActors {
        val child = spawn { () =>
          val worker = spawn { () =>
            val me = self()
            exit(me, "boom")
          }
          link(worker)
          receive { case _ => println("unreachable") }
        }
        println("done")
      }
    """) shouldBe "done"

  // ── monitor ───────────────────────────────────────────────────────

  test("monitor delivers Down message when watched actor exits"):
    captured("""
      runActors {
        val watcher = spawn { () =>
          val worker = spawn { () =>
            val me = self()
            exit(me, "oops")
          }
          val ref = monitor(worker)
          receive {
            case Down(r, from, reason) => println("down: " + reason)
          }
        }
      }
    """) shouldBe "down: oops"

  test("monitor on already-dead actor delivers Down(noproc) immediately"):
    captured("""
      runActors {
        val watcher = spawn { () =>
          val worker = spawn { () =>
            val me = self()
            exit(me, "gone")
          }
          val ref = monitor(worker)
          receive {
            case Down(r, from, reason) => println("noproc or gone")
          }
        }
      }
    """) shouldBe "noproc or gone"

  // ── link ──────────────────────────────────────────────────────────

  test("link + trapExit: supervisor receives Exit when worker crashes"):
    captured("""
      runActors {
        val sup = spawn { () =>
          trapExit(true)
          val worker = spawn { () =>
            println("worker start")
            val me = self()
            exit(me, "crash")
          }
          link(worker)
          receive {
            case Exit(from, reason) => println("supervisor got: " + reason)
          }
        }
      }
    """) shouldBe "worker start\nsupervisor got: crash"

  test("actor exit keeps precedence over std.os exit when both plugins are loaded"):
    capturedWith(List(new ActorsInterpreterPlugin, new OsInterpreterPlugin))("""
      runActors {
        val sup = spawn { () =>
          trapExit(true)
          val worker = spawn { () =>
            val me = self()
            exit(me, "crash")
          }
          link(worker)
          receive {
            case Exit(from, reason) => println("supervisor got: " + reason)
          }
        }
      }
    """) shouldBe "supervisor got: crash"

  test("demonitor cancels a monitor before actor exits"):
    captured("""
      runActors {
        val watcher = spawn { () =>
          val worker = spawn { () =>
            println("working")
            val me = self()
            exit(me, "done")
          }
          val ref = monitor(worker)
          demonitor(ref)
          println("no down expected")
        }
      }
    """) shouldBe "working\nno down expected"

  // ── bidirectional link propagation ────────────────────────────────

  test("link propagates crash to multiple actors when all trapExit=false"):
    // A links B; B dies; A should also die. Root observes A's mailbox.
    // With no trapExit, A just dies silently. Root still runs.
    captured("""
      runActors {
        val b = spawn { () =>
          val me = self()
          exit(me, "kaboom")
        }
        val a = spawn { () =>
          link(b)
          receive { case _ => println("a unreachable") }
        }
        println("root done")
      }
    """) shouldBe "root done"

  // ── spawn_link ────────────────────────────────────────────────────

  test("spawn_link: supervisor with trapExit receives Exit when linked child crashes"):
    captured("""
      runActors {
        val sup = spawn { () =>
          trapExit(true)
          val worker = spawn_link { () =>
            val me = self()
            exit(me, "crash")
          }
          receive {
            case Exit(from, reason) => println("caught: " + reason)
          }
        }
      }
    """) shouldBe "caught: crash"

  test("spawn_link: crash propagates to parent when parent has no trapExit"):
    captured("""
      runActors {
        val sup = spawn { () =>
          val worker = spawn_link { () =>
            val me = self()
            exit(me, "kaboom")
          }
          receive { case _ => println("unreachable") }
        }
        println("root ok")
      }
    """) shouldBe "root ok"

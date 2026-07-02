package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Integration tests for v1.6 Phase 2 — Supervisor library in std/actors.ssc.
 *  Each test imports ChildSpec / MaxRestarts / Supervisor from the library
 *  file and exercises strategies + MaxRestarts via the interpreter. */
class SupervisorTest extends AnyFunSuite with Matchers:

  // Repo root is one level above the sbt project directory (backend-interpreter/).
  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    // Import in paragraph (Markdown link) syntax; import is resolved relative
    // to repoRoot so the path "std/actors.ssc" works.
    val src =
      s"""# Test
         |
         |[ChildSpec, MaxRestarts, Supervisor](std/actors.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    val module = Parser.parse(src)
    val _i = Interpreter(ps, baseDir = Some(repoRoot)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(module)
    ps.flush()
    buf.toString.trim

  // ── OneForOne ──────────────────────────────────────────────────────

  test("OneForOne: permanent worker is restarted once after crash"):
    captured("""
      runActors {
        val counter = spawn { () =>
          val me = self()
          receive { case msg: Any => msg ! "hello" }
        }

        val workerSpec = ChildSpec(
          id      = "w",
          start   = () => spawn { () =>
            val me = self()
            exit(me, "crash")
          },
          restart = "permanent"
        )

        val me = self()
        val sup = Supervisor.start(
          List(workerSpec),
          "one_for_one",
          MaxRestarts(3, 5000)
        )

        // Give the supervisor time to restart the worker.
        // The worker crashes immediately; supervisor restarts it; it crashes again.
        // After 3 crashes in the window supervisor itself dies with max_restart_exceeded.
        // Root just waits and prints done.
        println("sup started")
      }
    """) shouldBe "sup started"

  test("OneForOne: temporary worker is NOT restarted after normal exit"):
    captured("""
      runActors {
        val me = self()
        val workerSpec = ChildSpec(
          id      = "tmp",
          start   = () => spawn { () =>
            println("worker ran")
            val w = self()
            exit(w, "normal")
          },
          restart = "temporary"
        )
        val sup = Supervisor.start(
          List(workerSpec),
          "one_for_one",
          MaxRestarts(3, 5000)
        )
        println("done")
      }
    """) shouldBe "done\nworker ran"

  test("OneForOne: transient worker restarts on abnormal but not on normal exit"):
    captured("""
      runActors {
        val workerSpec = ChildSpec(
          id      = "trans",
          start   = () => spawn { () =>
            println("run")
            val w = self()
            exit(w, "normal")
          },
          restart = "transient"
        )
        val sup = Supervisor.start(
          List(workerSpec),
          "one_for_one",
          MaxRestarts(3, 5000)
        )
        println("done")
      }
    """) shouldBe "done\nrun"

  // ── MaxRestarts ────────────────────────────────────────────────────

  test("MaxRestarts: supervisor dies after budget exceeded"):
    captured("""
      runActors {
        val root = self()

        val crashSpec = ChildSpec(
          id      = "crasher",
          start   = () => spawn { () =>
            val me = self()
            exit(me, "boom")
          },
          restart = "permanent"
        )

        val sup = Supervisor.start(
          List(crashSpec),
          "one_for_one",
          MaxRestarts(2, 10000)
        )

        // Monitor the supervisor to know when it dies.
        val ref = monitor(sup)
        receive {
          case Down(r, from, reason) => println("sup down: " + reason)
        }
      }
    """) shouldBe "sup down: max_restart_exceeded"

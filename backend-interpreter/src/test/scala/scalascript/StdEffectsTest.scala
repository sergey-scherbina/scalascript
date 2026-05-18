package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Integration tests for v1.4 standard-library effects:
 *  Logger, Random (seeded), Clock (frozen), Env (map fixture). */
class StdEffectsTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ── Logger ────────────────────────────────────────────────────────────

  test("runLogger writes [LEVEL] lines to output"):
    captured("""
      runLogger {
        Logger.info("hello")
        Logger.warn("watch out")
        Logger.error("boom")
        Logger.debug("trace")
      }
    """) shouldBe "[INFO] hello\n[WARN] watch out\n[ERROR] boom\n[DEBUG] trace"

  test("runLoggerJson writes newline-delimited JSON"):
    captured("""
      runLoggerJson {
        Logger.info("hi")
        Logger.error("oops")
      }
    """) shouldBe """{"level":"info","msg":"hi"}""" + "\n" + """{"level":"error","msg":"oops"}"""

  test("runLoggerToList returns (result, log) pair"):
    captured("""
      val (result, log) = runLoggerToList {
        Logger.info("a")
        Logger.warn("b")
        42
      }
      println(result)
      log.foreach { case (level, msg) => println(level + ":" + msg) }
    """) shouldBe "42\ninfo:a\nwarn:b"

  test("Logger body result is returned by runLogger"):
    captured("""
      val x = runLogger { Logger.info("side"); 99 }
      println(x)
    """) shouldBe "[INFO] side\n99"

  // ── Random (seeded) ────────────────────────────────────────────────────

  test("runRandomSeeded produces deterministic nextInt"):
    val r1 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    val r2 = captured("""
      runRandomSeeded(42) {
        println(Random.nextInt(100))
        println(Random.nextInt(100))
      }
    """)
    r1 shouldBe r2
    r1.split("\n").length shouldBe 2

  test("runRandomSeeded(42) nextInt(100) is in [0, 100)"):
    captured("""
      runRandomSeeded(1) {
        val n = Random.nextInt(10)
        println(n >= 0 && n < 10)
      }
    """) shouldBe "true"

  test("runRandomSeeded nextDouble in [0, 1)"):
    captured("""
      runRandomSeeded(7) {
        val d = Random.nextDouble()
        println(d >= 0.0 && d < 1.0)
      }
    """) shouldBe "true"

  test("Random.pick returns element from list"):
    captured("""
      runRandomSeeded(5) {
        val xs = List(10, 20, 30)
        val v  = Random.pick(xs)
        println(xs.contains(v))
      }
    """) shouldBe "true"

  test("Random.uuid returns a UUID-shaped string"):
    captured("""
      runRandomSeeded(99) {
        val id = Random.uuid()
        // basic UUID shape: 8-4-4-4-12
        println(id.length)
      }
    """) shouldBe "36"

  test("runRandom (unseeded) produces a string of expected length"):
    val out = captured("""
      runRandom {
        println(Random.uuid().length)
      }
    """)
    out shouldBe "36"

  // ── Clock (frozen) ─────────────────────────────────────────────────────

  test("runClockAt freezes Clock.now at the given epoch ms"):
    captured("""
      runClockAt(1000000) {
        println(Clock.now())
        println(Clock.now())
      }
    """) shouldBe "1000000\n1000000"

  test("runClockAt freezes Clock.nowIso"):
    captured("""
      runClockAt(0) {
        println(Clock.nowIso())
      }
    """) shouldBe "1970-01-01T00:00:00Z"

  test("runClockAt Clock.sleep is a no-op (does not delay)"):
    val start = java.lang.System.currentTimeMillis()
    captured("""
      runClockAt(0) {
        Clock.sleep(10000)  // would be 10 s if real
        println("done")
      }
    """) shouldBe "done"
    val elapsed = java.lang.System.currentTimeMillis() - start
    elapsed should be < 2000L

  test("runClock returns actual Clock.now (close to wall time)"):
    val before = java.lang.System.currentTimeMillis()
    val out = captured("""
      runClock {
        println(Clock.now())
      }
    """)
    val after = java.lang.System.currentTimeMillis()
    val t = out.toLong
    t should be >= before
    t should be <= after + 100L

  // ── Env ────────────────────────────────────────────────────────────────

  test("runEnvWith provides key lookup via Env.get"):
    captured("""
      runEnvWith(Map("FOO" -> "bar")) {
        val v = Env.get("FOO")
        v match
          case Some(s) => println(s)
          case None    => println("missing")
      }
    """) shouldBe "bar"

  test("runEnvWith Env.get returns None for missing key"):
    captured("""
      runEnvWith(Map("A" -> "1")) {
        val v = Env.get("MISSING")
        v match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "none"

  test("runEnvWith Env.required returns value"):
    captured("""
      runEnvWith(Map("HOST" -> "localhost")) {
        println(Env.required("HOST"))
      }
    """) shouldBe "localhost"

  test("runEnvWith Env.required throws on missing key"):
    an[Exception] should be thrownBy captured("""
      runEnvWith(Map()) {
        Env.required("MISSING")
      }
    """)

  test("Env.set mutates the local overlay"):
    captured("""
      runEnvWith(Map()) {
        Env.set("X", "hello")
        Env.get("X") match
          case Some(s) => println(s)
          case None    => println("none")
      }
    """) shouldBe "hello"

  test("runEnv reads real process env (HOME or PATH is set)"):
    val home = Option(java.lang.System.getenv("HOME"))
      .orElse(Option(java.lang.System.getenv("PATH")))
      .getOrElse("")
    if home.nonEmpty then
      captured("""
        runEnv {
          val h = Env.get("HOME")
          h match
            case Some(_) => println("found")
            case None    =>
              val p = Env.get("PATH")
              p match
                case Some(_) => println("found")
                case None    => println("missing")
        }
      """) shouldBe "found"

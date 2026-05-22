package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Conformance tests for v1.11.5 — Free[F, A] stdlib monad. */
class FreeMonadTest extends AnyFunSuite with Matchers:

  // Repo root is one level above backend-interpreter/.
  private val repoRoot = TestPaths.repoRoot

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[Free, freePure, freeLiftF, freeMap, freeFlatMap, freeFoldMapList, freeFoldMapOption](std/free.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── freePure ──────────────────────────────────────────────────────────────

  test("freePure — foldMapList returns singleton"):
    captured("""
      val p = freePure[Any, Int](42)
      println(freeFoldMapList(p, (eff: Any) => List(eff)))
    """) shouldBe "List(42)"

  test("freePure — foldMapOption returns Some"):
    captured("""
      val p = freePure[Any, String]("hello")
      println(freeFoldMapOption(p, (eff: Any) => Some(eff)))
    """) shouldBe "Some(hello)"

  // ── freeLiftF ─────────────────────────────────────────────────────────────

  test("freeLiftF — nt is applied once"):
    captured("""
      case class Tick()
      val p = freeLiftF[Any, Unit](Tick())
      println(freeFoldMapList(p, (eff: Any) => List(99)))
    """) shouldBe "List(99)"

  // ── freeMap ───────────────────────────────────────────────────────────────

  test("freeMap on pure — transforms value"):
    captured("""
      val p = freeMap(freePure[Any, Int](5), (n: Int) => n * 2)
      println(freeFoldMapList(p, (eff: Any) => List(eff)))
    """) shouldBe "List(10)"

  test("freeMap on lifted — nt result is transformed"):
    captured("""
      case class Get(n: Int)
      val p = freeMap(freeLiftF[Any, Int](Get(7)), (n: Int) => n + 3)
      println(freeFoldMapList(p, (eff: Any) => eff match { case Get(n) => List(n) }))
    """) shouldBe "List(10)"

  // ── freeFlatMap ───────────────────────────────────────────────────────────

  test("freeFlatMap — result of first effect feeds second"):
    captured("""
      case class Emit(n: Int)
      val p = freeFlatMap(freeLiftF[Any, Int](Emit(4)), (n: Any) => freePure[Any, Int](n * 5))
      println(freeFoldMapList(p, (eff: Any) => eff match { case Emit(n) => List(n) }))
    """) shouldBe "List(20)"

  test("freeFlatMap — non-deterministic branch (Choice)"):
    captured("""
      case class Choice(opts: List[Any])
      val p = freeFlatMap(
        freeLiftF[Any, Any](Choice(List(1, 2, 3))),
        (n: Any) => freePure[Any, Any](n * 2))
      val nt = (eff: Any) => eff match { case Choice(opts) => opts }
      println(freeFoldMapList(p, nt))
    """) shouldBe "List(2, 4, 6)"

  // ── multi-step programs ───────────────────────────────────────────────────

  test("sequential program — three effects, side-effecting nt"):
    captured("""
      sealed trait PrintF[A]
      case class PrintMsg(msg: String) extends PrintF[Unit]

      val prog = freeFlatMap(freeLiftF(PrintMsg("a")),
                 _ => freeFlatMap(freeLiftF(PrintMsg("b")),
                 _ => freePure[PrintF, Int](42)))

      val nt = (eff: Any) => eff match
        case PrintMsg(msg) => println(msg); List(())

      val result = freeFoldMapList(prog, nt)
      println(result)
    """) shouldBe "a\nb\nList(42)"

  test("sequential program — freeFoldMapOption success chain"):
    captured("""
      case class TryGet(value: Option[Any])
      val nt = (eff: Any) => eff match { case TryGet(v) => v }

      val prog = freeFlatMap(freeLiftF(TryGet(Some(10))),
                 (n: Any) => freeFlatMap(freeLiftF(TryGet(Some(n * 2))),
                 (m: Any) => freePure[Any, Any](m + 5)))
      println(freeFoldMapOption(prog, nt))
    """) shouldBe "Some(25)"

  // ── freeFoldMapOption short-circuit ───────────────────────────────────────

  test("freeFoldMapOption — short-circuits on None"):
    captured("""
      case class TryGet(value: Option[Any])
      val nt = (eff: Any) => eff match { case TryGet(v) => v }

      val prog = freeFlatMap(freeLiftF(TryGet(None)),
                 (n: Any) => freePure[Any, Any](n * 10))
      println(freeFoldMapOption(prog, nt))
    """) shouldBe "None"

  test("freeFoldMapOption — None in middle stops chain"):
    captured("""
      case class TryGet(value: Option[Any])
      val nt = (eff: Any) => eff match { case TryGet(v) => v }

      val prog = freeFlatMap(freeLiftF(TryGet(Some(1))),
                 _ => freeFlatMap(freeLiftF(TryGet(None)),
                 _ => freePure[Any, Any]("never")))
      println(freeFoldMapOption(prog, nt))
    """) shouldBe "None"

  // ── monad laws ────────────────────────────────────────────────────────────

  test("left identity — freeFlatMap(freePure(a), f) = f(a)"):
    captured("""
      case class Tick(n: Int)
      val f = (n: Any) => freeLiftF[Any, Any](Tick(n * 10))
      val lhs = freeFoldMapList(freeFlatMap(freePure[Any, Int](3), f),
                                (eff: Any) => eff match { case Tick(n) => List(n) })
      val rhs = freeFoldMapList(f(3),
                                (eff: Any) => eff match { case Tick(n) => List(n) })
      println(lhs)
      println(rhs)
    """) shouldBe "List(30)\nList(30)"

  test("right identity — freeFlatMap(prog, freePure) = prog"):
    captured("""
      case class Choice(opts: List[Any])
      val prog = freeLiftF[Any, Any](Choice(List(10, 20)))
      val nt = (eff: Any) => eff match { case Choice(opts) => opts }
      val lhs = freeFoldMapList(freeFlatMap(prog, (a: Any) => freePure[Any, Any](a)), nt)
      val rhs = freeFoldMapList(prog, nt)
      println(lhs)
      println(rhs)
    """) shouldBe "List(10, 20)\nList(10, 20)"

  // ── same program, two interpreters ───────────────────────────────────────

  test("same program, two interpreters — program as data"):
    captured("""
      sealed trait ConfigOp[A]
      case class GetKey(key: String) extends ConfigOp[String]

      // Build program once
      val prog = freeFlatMap(freeLiftF(GetKey("host")),
                 (host: Any) => freeFlatMap(freeLiftF(GetKey("port")),
                 (port: Any) => freePure[ConfigOp, String](host + ":" + port)))

      // Interpreter A: file-backed (stub)
      val fileNt = (eff: Any) => eff match
        case GetKey("host") => List("example.com")
        case GetKey("port") => List("8080")
        case _              => List("?")
      println(freeFoldMapList(prog, fileNt))

      // Interpreter B: in-memory (test fixture)
      val memNt = (eff: Any) => eff match
        case GetKey("host") => List("localhost")
        case GetKey("port") => List("3000")
        case _              => List("?")
      println(freeFoldMapList(prog, memNt))
    """) shouldBe "List(example.com:8080)\nList(localhost:3000)"

  // ── stack safety ─────────────────────────────────────────────────────────

  test("stack safety — 1000 freeFlatMap reductions do not overflow"):
    captured("""
      var prog = freePure[Any, Int](0)
      var i = 0
      while i < 1000 do
        prog = freeFlatMap(prog, (n: Any) => freePure[Any, Int](n + 1))
        i = i + 1
      println(freeFoldMapList(prog, (eff: Any) => List(eff)))
    """) shouldBe "List(1000)"

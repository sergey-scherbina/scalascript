package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Random

/** Cross-backend property/differential test (xbackend-property-equivalence, slice 1).
 *
 *  The conformance suite checks ~127 FIXED programs agree across interp/JVM/JS. This GENERATES
 *  programs over the core deterministic-`Int` subset (arithmetic, `val`, `if`, user functions) from
 *  seeds and asserts `interp == JS(node)` (plus a small JVM/scala-cli sample) — turning the
 *  one-source-many-targets guarantee from fixed cases into generated coverage. Any divergence on a
 *  legitimate in-range program is a real cross-backend bug; the failing seed + program is printed.
 *
 *  Slice 1 scope: the core fully-supported subset, with results bounded to all backends' safe int
 *  range (|v| < 1e8 — under 32-bit `Int`, 53-bit JS double, 64-bit interp `Long`) so numeric-overflow
 *  edge cases (a documented platform difference) don't false-fail. Broadening the generator
 *  (collections / ADTs / pattern matching / effects) + the overflow dimension are follow-on slices.
 */
class CrossBackendPropertyTest extends AnyFunSuite:

  private val ArithOps = Vector("+", "-", "*")
  private val CmpOps   = Vector("<", ">", "<=", ">=", "==", "!=")

  /** A well-typed deterministic `Int` expression of bounded depth over `vars` + `fns`. */
  private def genExpr(rng: Random, depth: Int, vars: Seq[String], fns: Seq[String]): String =
    if depth <= 0 || rng.nextInt(3) == 0 then
      if vars.nonEmpty && rng.nextBoolean() then vars(rng.nextInt(vars.size))
      else rng.nextInt(6).toString
    else
      val choice = rng.nextInt(if fns.nonEmpty then 4 else 3)
      choice match
        case 0 =>
          s"(${genExpr(rng, depth-1, vars, fns)} ${ArithOps(rng.nextInt(ArithOps.size))} ${genExpr(rng, depth-1, vars, fns)})"
        case 1 =>
          val cond = s"${genExpr(rng, depth-1, vars, fns)} ${CmpOps(rng.nextInt(CmpOps.size))} ${genExpr(rng, depth-1, vars, fns)}"
          s"(if $cond then ${genExpr(rng, depth-1, vars, fns)} else ${genExpr(rng, depth-1, vars, fns)})"
        case 2 =>
          genExpr(rng, depth-1, vars, fns)
        case _ =>
          s"${fns(rng.nextInt(fns.size))}(${genExpr(rng, depth-1, vars, fns)})"

  /** Dispatch across program KINDS so the generator exercises more of the language than arithmetic.
   *  Every kind prints a deterministic `Int`/`String` — NOT a `Double` (formatting differs across
   *  backends) and NOT a `Set`/`Map` (iteration order legitimately differs); `List` is ordered, safe. */
  private val Kinds = 9
  private def genProgram(seed: Int): String =
    val rng = new Random(seed.toLong)
    (seed % Kinds) match
      case 0 => genArithProgram(rng)
      case 1 => genListProgram(rng)
      case 2 => genMatchProgram(rng)
      case 3 => genEnumProgram(rng)
      case 4 => genStringProgram(rng)
      case 5 => genCaseClassProgram(rng)
      case 6 => genOptionProgram(rng)
      case 7 => genEitherProgram(rng)
      case _ => genEffectProgram(rng)

  private def genArithProgram(rng: Random): String =
    val sb  = new StringBuilder
    val nFns    = rng.nextInt(3)            // 0–2 helper functions
    val fnNames = (0 until nFns).map(i => s"f$i")
    for i <- 0 until nFns do
      sb.append(s"def f$i(x: Int): Int = ${genExpr(rng, 2, Seq("x"), fnNames.take(i))}\n")
    val nVals    = 1 + rng.nextInt(3)       // 1–3 vals
    val valNames = (0 until nVals).map(i => s"v$i")
    for i <- 0 until nVals do
      sb.append(s"val v$i = ${genExpr(rng, 2, valNames.take(i), fnNames)}\n")
    sb.append(s"println(${genExpr(rng, 3, valNames, fnNames)})\n")
    sb.toString

  /** `List[Int]` operations producing an `Int` — ordered, deterministic. */
  private def genListProgram(rng: Random): String =
    val n  = 3 + rng.nextInt(4)
    val xs = (0 until n).map(_ => rng.nextInt(10)).mkString(", ")
    val k  = rng.nextInt(6)
    val op = rng.nextInt(8) match
      case 0 => "sum"
      case 1 => "length"
      case 2 => "max"
      case 3 => "min"
      case 4 => s"filter(e => e > $k).length"
      case 5 => "map(e => e * 2).sum"
      case 6 => "foldLeft(0)((a, b) => a + b)"
      case _ => s"count(e => e > $k)"
    s"val xs = List($xs)\nprintln(xs.$op)\n"

  /** `Int` `match` with a wildcard. */
  private def genMatchProgram(rng: Random): String =
    val n = rng.nextInt(5)
    s"val n = $n\nprintln(n match\n  case 0 => 10\n  case 1 => 21\n  case 2 => 32\n  case _ => 99\n)\n"

  /** Enum ADT + exhaustive `match`. */
  private def genEnumProgram(rng: Random): String =
    val pick = Vector("Red", "Green", "Blue")
    val a = pick(rng.nextInt(3)); val b = pick(rng.nextInt(3))
    "enum Color:\n  case Red, Green, Blue\n" +
      "def rank(c: Color): Int = c match\n  case Red => 1\n  case Green => 2\n  case Blue => 3\n" +
      s"println(rank($a) + rank($b) * 10)\n"

  /** String concatenation + `.length`. */
  private def genStringProgram(rng: Random): String =
    val parts = (0 until (2 + rng.nextInt(3))).map(_ => "\"" + ("ab".repeat(1 + rng.nextInt(3))) + "\"")
    val s = parts.mkString(" + ")
    if rng.nextBoolean() then s"val s = $s\nprintln(s.length)\n"
    else s"val s = $s\nprintln(s)\n"

  /** Case-class construction + field access. */
  private def genCaseClassProgram(rng: Random): String =
    val x = rng.nextInt(50); val y = rng.nextInt(50)
    "case class Point(x: Int, y: Int)\n" +
      s"val p = Point($x, $y)\nprintln(p.x + p.y * 2)\n"

  /** `Option[Int]` — construct via a branch, then `map`/`getOrElse`. */
  private def genOptionProgram(rng: Random): String =
    val v = rng.nextInt(20); val d = rng.nextInt(10); val some = rng.nextBoolean()
    val o = if some then s"Some($v)" else "None"
    s"val o: Option[Int] = $o\nprintln(o.map(e => e + 1).getOrElse($d))\n"

  /** `Either[String, Int]` — construct via a branch, then `match`. */
  private def genEitherProgram(rng: Random): String =
    val v = rng.nextInt(20); val right = rng.nextBoolean()
    val e = if right then s"Right($v)" else "Left(\"err\")"
    s"val e: Either[String, Int] = $e\n" +
      "println(e match\n  case Right(n) => n * 2\n  case Left(_) => -1\n)\n"

  /** Algebraic EFFECT: a `Counter.tick` loop under a one-shot `handle` returning a deterministic Int.
   *  Exercises the SEPARATE CPS codegen on each backend (JvmGenCpsTransform / JsGenCpsCodegen / interp).
   *  Result = n * k. NB: binds the handle result to a `val` (the idiomatic form) — the inline form
   *  `println(handle(...))` is excluded because JVM codegen does not lower a `handle` in call-argument
   *  position (BUGS.md `jvmgen-handle-in-arg-position`, found by this very test; interp+JS handle both). */
  private def genEffectProgram(rng: Random): String =
    val n = 1 + rng.nextInt(6); val k = 1 + rng.nextInt(4)
    "effect Counter:\n  def tick(): Int\n" +
      "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
      "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n" +
      s"val out = handle(loop($n)) {\n  case Counter.tick(resume) => resume($k)\n}\nprintln(out)\n"

  private def module(program: String) = Parser.parse(s"# Gen\n\n```scalascript\n$program\n```\n")

  private def interp(m: scalascript.ast.Module): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(m); ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    val p   = ProcessBuilder(cmd*).start()
    val out = Source.fromInputStream(p.getInputStream).mkString
    val err = Source.fromInputStream(p.getErrorStream).mkString
    if ProcTestUtil.awaitExit(p) != 0 then fail(s"${cmd.head} failed:\n$err")
    out.trim

  private def runJs(m: scalascript.ast.Module): String =
    val tmp = java.io.File.createTempFile("ssc-prop-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath, (rt + "\n" + JsGen.generate(m)).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath)

  private def runJvm(m: scalascript.ast.Module): String =
    val tmp = java.io.File.createTempFile("ssc-prop-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(m)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", tmp.getAbsolutePath)

  /** Compare the interp output unless it is a numeric value OUTSIDE all backends' safe int range
   *  (then skip — overflow is a documented platform difference). Non-numeric output (strings) is
   *  always compared. */
  private def comparable(s: String): Boolean =
    s.toLongOption match
      case Some(v) => math.abs(v) < 100000000L
      case None    => true

  test("generated programs (9 kinds incl. effects): interp == JS(node) over 54 seeds"):
    assume(has("node"), "node not available")
    var checked = 0; var skipped = 0
    for seed <- 0 until 54 do
      val prog = genProgram(seed)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp == null || !comparable(exp) then skipped += 1
      else
        val js = runJs(m)
        assert(js == exp,
          s"\nJS diverged from interp on seed $seed:\n--- program ---\n$prog--- interp: [$exp] | js: [$js] ---")
        checked += 1
    info(s"interp==JS: checked $checked generated programs, skipped $skipped (out-of-range/interp-error)")
    assert(checked >= 30, s"too few programs exercised ($checked) — generator may be degenerate")

  test("generated programs (all 9 kinds incl. effects): interp == JVM(scala-cli)"):
    assume(has("scala-cli"), "scala-cli not available")
    var checked = 0
    for seed <- 0 until Kinds do // seeds 0..8 = one program of each kind (incl. the effect kind)
      val prog = genProgram(seed)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp != null && comparable(exp) then
        assert(runJvm(m) == exp, s"\nJVM diverged from interp on kind seed=$seed:\n$prog--- interp: [$exp] ---")
        checked += 1
    info(s"interp==JVM: checked $checked generated programs (one per kind)")

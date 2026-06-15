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

  private def genProgram(seed: Int): String =
    val rng = new Random(seed.toLong)
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

  /** True when interp produced a clean integer in all backends' safe range. */
  private def inRange(s: String): Boolean =
    s.toLongOption.exists(v => math.abs(v) < 100000000L)

  test("generated core programs: interp == JS(node) over 40 seeds"):
    assume(has("node"), "node not available")
    var checked = 0; var skipped = 0
    for seed <- 0 until 40 do
      val prog = genProgram(seed)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp == null || !inRange(exp) then skipped += 1
      else
        val js = runJs(m)
        assert(js == exp,
          s"\nJS diverged from interp on seed $seed:\n--- program ---\n$prog--- interp: $exp | js: $js ---")
        checked += 1
    info(s"interp==JS: checked $checked generated programs, skipped $skipped (out-of-range/interp-error)")
    assert(checked >= 20, s"too few programs exercised ($checked) — generator may be degenerate")

  test("generated core programs: interp == JVM(scala-cli) over a 5-seed sample"):
    assume(has("scala-cli"), "scala-cli not available")
    var checked = 0
    for seed <- 0 until 5 do
      val prog = genProgram(seed * 7 + 1)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp != null && inRange(exp) then
        assert(runJvm(m) == exp, s"\nJVM diverged from interp on seed-sample $seed:\n$prog--- interp: $exp ---")
        checked += 1
    info(s"interp==JVM: checked $checked generated programs")

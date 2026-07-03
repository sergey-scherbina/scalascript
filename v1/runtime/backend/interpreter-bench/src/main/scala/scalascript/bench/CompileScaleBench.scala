package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.parser.Parser
import scalascript.typer.Typer
import scalascript.ast.Module
import scalascript.codegen.{JvmGen, JsGen}

/** Compile-time at SCALE (compile-time-at-scale, 2026-06-15).
 *
 *  The other compile/codegen benches (`CompilerBench`, `CrossBackendBench`) use 6-line inputs, so
 *  compile-time on a real-size module was unmeasured. This bench compiles a representative LARGE module
 *  (`large` ≈ 800 defs + 40 case classes, ~40 KB) through each pipeline stage, so a future O(n²)
 *  regression in parse/type/codegen shows up here instead of only on a user's big project.
 *
 *  Measured scaling profile (2026-06-15, N = 800→6400): parse + type are LINEAR (×1.7–1.9 / doubling);
 *  jvmGen/jsGen are roughly linear with a mild superlinear tail (×2.1–2.4 / doubling — not quadratic).
 *  At 6400 defs / 345 KB: jvmGen ~465 ms, jsGen ~240 ms. No pathology. See docs/compile-scale-findings.md.
 *
 *  Run: `sbt "interpreterBench/Jmh/run .*CompileScaleBench.*"`
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class CompileScaleBench:

  /** `n` arithmetic functions in a cross-referencing chain + `t` case classes under a sealed trait
   *  with a pattern-matching dispatcher + a final block — a representative large module. */
  private def genProgram(n: Int, t: Int): String =
    val sb = new StringBuilder
    sb.append("# Scale\n\n```scalascript\nsealed trait Shape\n")
    for i <- 0 until t do sb.append(s"case class S$i(a: Int, b: Int) extends Shape\n")
    sb.append("def area(s: Shape): Int = s match\n")
    for i <- 0 until t do sb.append(s"  case S$i(a, b) => a * b + $i\n")
    sb.append("def f0(x: Int): Int = x + 1\ndef f1(x: Int): Int = f0(x) + 1\n")
    for i <- 2 until n do sb.append(s"def f$i(x: Int): Int = f${i-1}(x) + f${i-2}(x) - $i\n")
    sb.append(s"val r = f${n-1}(0) + area(S0(1, 2))\nr\n```\n")
    sb.toString

  private val largeSrc: String = genProgram(800, 40)
  private val largeMod: Module = Parser.parse(largeSrc)

  @Benchmark def parseLarge(): Module           = Parser.parse(largeSrc)
  @Benchmark def typeLarge(): Any               = new Typer().typeCheck(Parser.parse(largeSrc))
  @Benchmark def jvmGenLarge(): String          = JvmGen.generate(largeMod, None)
  @Benchmark def jsGenLarge(): String           = JsGen.generate(largeMod, None)

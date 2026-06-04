package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.parser.Parser
import scalascript.typer.{Typer, SType, Constraint, Unifier}
import scalascript.ast.{Module, SsccFormat}

/** JMH microbenchmarks for the ScalaScript compiler hot paths.
 *
 *  Run all:
 *    sbt "compilerBench/Jmh/run"
 *
 *  Run with GC allocation profiler:
 *    sbt "compilerBench/Jmh/run -prof gc -wi 3 -i 5"
 *
 *  Run a single benchmark:
 *    sbt "compilerBench/Jmh/run -i 5 -wi 3 -f 1 .*ParserBench.*"
 */

// ── Parser ────────────────────────────────────────────────────────────────────

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class ParserBench:

  // Read actors.ssc once at JVM start; benchmark only the parse+type phase.
  private val actorsSsc: String =
    val relative = os.RelPath("runtime") / "std" / "actors.ssc"
    Iterator.iterate(os.pwd)(p => p / os.up).take(6).find(p => os.exists(p / relative))
      .map(p => os.read(p / relative))
      .getOrElse("""# Actors bench fallback\n\n```scalascript\ndef greet(name: String): String = s"Hello, $name"\ngreet("world")\n```\n""")

  @Benchmark
  def parseActors(): Module =
    Parser.parse(actorsSsc)


// ── Typer ─────────────────────────────────────────────────────────────────────

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class TyperBench:

  private val actorsSsc: String =
    val relative = os.RelPath("runtime") / "std" / "actors.ssc"
    Iterator.iterate(os.pwd)(p => p / os.up).take(6).find(p => os.exists(p / relative))
      .map(p => os.read(p / relative))
      .getOrElse("""# Typer bench fallback\n\n```scalascript\ndef fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2)\nfib(10)\n```\n""")

  private val parsedModule: Module = Parser.parse(actorsSsc)

  @Benchmark
  def typeActors(): scalascript.typer.TypedModule =
    new Typer().typeCheck(parsedModule)


// ── Unifier ───────────────────────────────────────────────────────────────────

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class UnifyBench:

  // Build a set of constraints that stress the substitution and occurs-check
  // paths: deeply nested Function/Tuple with fresh type variables.
  private def freshConstraints(depth: Int): List[Constraint] =
    def nested(d: Int, varBase: Int): (SType, SType) =
      if d == 0 then
        (SType.Var(varBase), SType.Int)
      else
        val (l, r) = nested(d - 1, varBase + 2)
        (SType.Function(List(SType.Var(varBase), l), SType.Var(varBase + 1)),
         SType.Function(List(SType.String,        r), SType.Boolean))
    val (l, r) = nested(depth, 0)
    List(Constraint.Equal(l, r))

  private val shallowConstraints: List[Constraint] = freshConstraints(3)
  private val deepConstraints:    List[Constraint] = freshConstraints(8)

  @Benchmark
  def unifyShallow(): Unifier.type =
    Unifier.unify(shallowConstraints)
    Unifier

  @Benchmark
  def unifyDeep(): Unifier.type =
    Unifier.unify(deepConstraints)
    Unifier


// ── SsccFormat read v2 vs v3 ──────────────────────────────────────────────────

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class SsccFormatCompilerBench:

  private val actorsSsc: String =
    // JMH forks from the bench project dir, not the repo root. Walk up to find it.
    val relative = os.RelPath("runtime") / "std" / "actors.ssc"
    val found = Iterator.iterate(os.pwd)(p => p / os.up)
      .take(6)
      .find(p => os.exists(p / relative))
      .map(p => os.read(p / relative))
    found.getOrElse {
      """# SsccFormat bench fallback
        |
        |```scalascript
        |def fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2)
        |fib(10)
        |```
        |""".stripMargin
    }

  private val module: Module        = Parser.parse(actorsSsc)
  private val ssccBytes: Array[Byte] = SsccFormat.write(module)   // v3 token stream
  private val ssccGzip: Array[Byte]  = SsccFormat.writeV3(module, gzip = true)

  @Setup(Level.Trial)
  def printSizes(): Unit =
    System.err.println(f"[SsccFormatBench] actors.ssc src=${actorsSsc.length}%d chars, " +
      f"sscc=${ssccBytes.length}%d B, sscc+gzip=${ssccGzip.length}%d B, " +
      f"gzip-ratio=${ssccGzip.length.toDouble/ssccBytes.length*100}%.1f%%")

  /** Load from pre-compiled .sscc — lazy scalameta (trees not parsed yet). */
  @Benchmark def readSscc(): Module = SsccFormat.read(ssccBytes).getOrElse(throw new RuntimeException("sscc read failed"))

  /** Load from pre-compiled .sscc with outer gzip compression. */
  @Benchmark def readSsccGzip(): Module = SsccFormat.read(ssccGzip).getOrElse(throw new RuntimeException("sscc gzip read failed"))

  /** Decode-only: trie + token stream, no scalameta parse (measures irreducible I/O floor). */
  @Benchmark def readSsccDecode(): Module = SsccFormat.readNoTrees(ssccBytes).getOrElse(throw new RuntimeException("sscc decode failed"))

  /** Load + force all trees in parallel — equivalent to the old eager read. */
  @Benchmark def readSsccForce(): Module =
    SsccFormat.forceAllTrees(SsccFormat.read(ssccBytes).getOrElse(throw new RuntimeException("sscc read failed")))

  /** Baseline: parse from .ssc source text (includes CommonMark + scalameta). */
  @Benchmark def parseSource(): Module = Parser.parse(actorsSsc)

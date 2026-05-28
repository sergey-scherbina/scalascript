package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.parser.Parser
import scalascript.typer.{Typer, SType, Constraint, Unifier}
import scalascript.ast.Module

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
    val path = os.pwd / "runtime" / "std" / "actors.ssc"
    if os.exists(path) then os.read(path)
    else
      // Inline a minimal fallback so the benchmark compiles everywhere.
      """# Actors bench fallback
        |
        |```scalascript
        |def greet(name: String): String = s"Hello, $name"
        |greet("world")
        |```
        |""".stripMargin

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
    val path = os.pwd / "runtime" / "std" / "actors.ssc"
    if os.exists(path) then os.read(path)
    else
      """# Typer bench fallback
        |
        |```scalascript
        |def fib(n: Int): Int = if n <= 1 then n else fib(n - 1) + fib(n - 2)
        |fib(10)
        |```
        |""".stripMargin

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

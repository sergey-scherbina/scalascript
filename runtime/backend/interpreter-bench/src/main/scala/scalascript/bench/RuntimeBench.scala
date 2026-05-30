package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.ast.Module
import scalascript.codegen.{JvmGen, JsGen}

/** JMH benchmarks that compare **runtime execution** of the three backends:
 *  interpreter (in-process), JS (node subprocess), and JVM (java subprocess).
 *
 *  The @Setup phase pre-generates JS bundles and pre-compiles JVM JARs so that
 *  each @Benchmark iteration measures only execution time — not codegen or
 *  scala-cli compilation.
 *
 *  Run all runtime benchmarks:
 *    sbt "interpreterBench/Jmh/run -wi 2 -i 5 -f 1 .*RuntimeBench.*"
 *
 *  Compare specific workload across backends:
 *    sbt "interpreterBench/Jmh/run -wi 2 -i 5 -f 1 .*Runtime.*arithLoop.*"
 *
 *  Save results:
 *    sbt "interpreterBench/Jmh/run -wi 2 -i 5 -f 1 -rff /tmp/runtime-bench.json -rf json .*RuntimeBench.*"
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class RuntimeBench:

  private def src(code: String): Module =
    Parser.parse(s"# Bench\n\n```scalascript\n$code\n```\n")

  private val modArithLoop: Module = src(
    """var i = 0
      |var sum = 0
      |while i < 1000000 do
      |  sum = sum + i
      |  i = i + 1
      |sum""".stripMargin
  )

  private val modFib: Module = src(
    """def fib(n: Int): Int =
      |  if n <= 1 then n else fib(n - 1) + fib(n - 2)
      |fib(30)""".stripMargin
  )

  private val modTco: Module = src(
    """def sumTco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else sumTco(n - 1, acc + n)
      |sumTco(100000, 0)""".stripMargin
  )

  private val modPatternMatch: Module = src(
    """sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |case class Point(x: Double, y: Double) extends Shape
      |case class Line(len: Double) extends Shape
      |def area(s: Shape): Double = s match
      |  case Circle(r)       => 3.14159 * r * r
      |  case Rect(w, h)      => w * h
      |  case Triangle(b, h)  => 0.5 * b * h
      |  case Point(_, _)     => 0.0
      |  case Line(_)         => 0.0
      |val shapes: List[Shape] = List(
      |  Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0), Point(0.0, 0.0), Line(7.0)
      |)
      |var total = 0.0
      |var i = 0
      |while i < 100000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin
  )

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  // JS pre-generated bundles (preamble + user code)
  private var jsArithLoop:    java.io.File = _
  private var jsFib:          java.io.File = _
  private var jsTco:          java.io.File = _
  private var jsPatternMatch: java.io.File = _

  // Pre-compiled JVM JARs
  private var jvmArithLoop:    Option[java.io.File] = None
  private var jvmFib:          Option[java.io.File] = None
  private var jvmTco:          Option[java.io.File] = None
  private var jvmPatternMatch: Option[java.io.File] = None

  @Setup(Level.Trial)
  def setup(): Unit =
    val benchDir = java.io.File("/tmp/ssc-rt-bench")
    benchDir.mkdirs()

    jsArithLoop    = genJs(modArithLoop,    benchDir, "arith_loop")
    jsFib          = genJs(modFib,          benchDir, "recursion_fib")
    jsTco          = genJs(modTco,          benchDir, "recursion_tco")
    jsPatternMatch = genJs(modPatternMatch, benchDir, "pattern_match")

    jvmArithLoop    = tryCompileJvm(modArithLoop,    benchDir, "arith_loop")
    jvmFib          = tryCompileJvm(modFib,          benchDir, "recursion_fib")
    jvmTco          = tryCompileJvm(modTco,          benchDir, "recursion_tco")
    jvmPatternMatch = tryCompileJvm(modPatternMatch, benchDir, "pattern_match")

  private def genJs(module: Module, dir: java.io.File, name: String): java.io.File =
    val caps    = JsGen.detectCapabilities(module, None)
    val runtime = JsGen.generateRuntime(caps)
    val user    = JsGen.generate(module, None)
    val bundle  = runtime + "\n" + user
    val f       = java.io.File(dir, s"$name.cjs")
    java.nio.file.Files.writeString(f.toPath, bundle)
    f

  private def tryCompileJvm(module: Module, dir: java.io.File, name: String): Option[java.io.File] =
    val scFile  = java.io.File(dir, s"$name.sc")
    val jarFile = java.io.File(dir, s"$name.jar")
    java.nio.file.Files.writeString(scFile.toPath, JvmGen.generate(module, None))
    // Skip recompilation if JAR is already up-to-date.
    if jarFile.exists() && jarFile.lastModified() >= scFile.lastModified() then
      return Some(jarFile)
    val scalaCliCmd = sys.env.getOrElse("SCALA_CLI_PATH", "scala-cli")
    val pb = java.lang.ProcessBuilder(
      scalaCliCmd, "--power", "package", "--standalone", "--force",
      "-o", jarFile.getAbsolutePath,
      scFile.getAbsolutePath
    )
    pb.redirectInput(java.io.File("/dev/null"))
    pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
    scala.util.Try(pb.start().waitFor()).toOption.flatMap { rc =>
      if rc == 0 && jarFile.exists() then Some(jarFile) else None
    }

  private def runSubproc(cmd: String*): Unit =
    val pb = java.lang.ProcessBuilder(cmd*)
    pb.redirectInput(java.io.File("/dev/null"))
    pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
    pb.start().waitFor()

  // ── Interpreter — in-process eval ───────────────────────────────────

  @Benchmark def interp_arithLoop(): Unit    = Interpreter(devNull).runSections(modArithLoop)
  @Benchmark def interp_recursionFib(): Unit = Interpreter(devNull).runSections(modFib)
  @Benchmark def interp_recursionTco(): Unit = Interpreter(devNull).runSections(modTco)
  @Benchmark def interp_patternMatch(): Unit = Interpreter(devNull).runSections(modPatternMatch)

  // ── JS backend — node.js subprocess on pre-generated bundle ─────────

  @Benchmark def js_arithLoop(): Unit    = runSubproc("node", jsArithLoop.getAbsolutePath)
  @Benchmark def js_recursionFib(): Unit = runSubproc("node", jsFib.getAbsolutePath)
  @Benchmark def js_recursionTco(): Unit = runSubproc("node", jsTco.getAbsolutePath)
  @Benchmark def js_patternMatch(): Unit = runSubproc("node", jsPatternMatch.getAbsolutePath)

  // ── JVM backend — java subprocess on pre-compiled standalone JAR ─────

  @Benchmark def jvm_arithLoop(): Unit    = jvmArithLoop.foreach(f => runSubproc("java", "-jar", f.getAbsolutePath))
  @Benchmark def jvm_recursionFib(): Unit = jvmFib.foreach(f => runSubproc("java", "-jar", f.getAbsolutePath))
  @Benchmark def jvm_recursionTco(): Unit = jvmTco.foreach(f => runSubproc("java", "-jar", f.getAbsolutePath))
  @Benchmark def jvm_patternMatch(): Unit = jvmPatternMatch.foreach(f => runSubproc("java", "-jar", f.getAbsolutePath))

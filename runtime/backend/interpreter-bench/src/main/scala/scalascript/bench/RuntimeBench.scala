package scalascript.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.ast.Module

/** JMH benchmarks comparing **hot-path runtime performance** of three backends.
 *
 *  Each JS/JVM benchmark forks one persistent subprocess per JMH trial.
 *  The subprocess reads workload names from stdin, executes [[OPS_PER_CALL]]
 *  iterations, and writes the total elapsed nanoseconds to stdout.
 *  JMH warmup naturally drives V8/HotSpot JIT compilation inside the
 *  subprocess; measurement iterations see steady-state native-code speed.
 *
 *  `@OperationsPerInvocation(OPS_PER_CALL)` tells JMH to divide the
 *  round-trip wall-clock time by the batch size, yielding time per
 *  individual workload execution — directly comparable to the
 *  interpreter benchmarks that run one operation per @Benchmark call.
 *
 *  Run all three backends for one workload:
 *    sbt "interpreterBench/Jmh/run -wi 3 -i 5 -f 1 .*Runtime.*arithLoop.*"
 *
 *  Full comparison:
 *    sbt "interpreterBench/Jmh/run -wi 3 -i 5 -f 1 -rff /tmp/runtime-hot.json -rf json .*RuntimeBench.*"
 */

/** Batch size: server runs this many iterations per command, JMH divides by it. */
private val OPS_PER_CALL = 1000

// ── JS server state ──────────────────────────────────────────────────────────

/** Persistent node.js server process. Started once per JMH trial; JMH
 *  warmup iterations naturally warm the V8 JIT before measurement begins. */
@State(Scope.Thread)
class JsServer:
  private var process: java.lang.Process = _
  private var reader:  BufferedReader    = _
  private var writer:  PrintWriter       = _

  @Setup(Level.Trial)
  def start(): Unit =
    val dir = java.io.File("/tmp/ssc-rt-bench")
    dir.mkdirs()
    val script = java.io.File(dir, "js_server.cjs")
    java.nio.file.Files.writeString(script.toPath, jsServerScript(OPS_PER_CALL))
    val pb = java.lang.ProcessBuilder("node", script.getAbsolutePath)
    pb.redirectErrorStream(false)
    process = pb.start()
    reader  = BufferedReader(InputStreamReader(process.getInputStream))
    writer  = PrintWriter(process.getOutputStream, true)

  @TearDown(Level.Trial)
  def stop(): Unit =
    scala.util.Try(writer.close())
    scala.util.Try(process.destroyForcibly())

  /** Send workload name; server runs OPS_PER_CALL iterations and replies
   *  with total elapsed nanoseconds.  Returns nanoseconds (ignored by JMH
   *  — JMH measures wall-clock of this call via @OperationsPerInvocation). */
  def run(cmd: String): Long =
    writer.println(cmd)
    reader.readLine().toLong


// ── JVM server state ─────────────────────────────────────────────────────────

/** Persistent JVM server process compiled once at setup time. */
@State(Scope.Thread)
class JvmServer:
  private var process: java.lang.Process = _
  private var reader:  BufferedReader    = _
  private var writer:  PrintWriter       = _
  private var started: Boolean           = false

  @Setup(Level.Trial)
  def start(): Unit =
    val dir = java.io.File("/tmp/ssc-rt-bench")
    dir.mkdirs()
    val scFile  = java.io.File(dir, "jvm_server.sc")
    val jarFile = java.io.File(dir, "jvm_server.jar")
    java.nio.file.Files.writeString(scFile.toPath, jvmServerScript(OPS_PER_CALL))
    // Only recompile if JAR is stale.
    if !jarFile.exists() || jarFile.lastModified() < scFile.lastModified() then
      val cli = sys.env.getOrElse("SCALA_CLI_PATH", "scala-cli")
      val cpb = java.lang.ProcessBuilder(
        cli, "--power", "package", "--standalone", "--force",
        "-o", jarFile.getAbsolutePath, scFile.getAbsolutePath)
      cpb.redirectInput(java.io.File("/dev/null"))
      cpb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
      cpb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
      val rc = scala.util.Try(cpb.start().waitFor()).getOrElse(1)
      if rc != 0 || !jarFile.exists() then return  // skip silently
    val pb = java.lang.ProcessBuilder("java", "-jar", jarFile.getAbsolutePath)
    pb.redirectErrorStream(false)
    process = pb.start()
    reader  = BufferedReader(InputStreamReader(process.getInputStream))
    writer  = PrintWriter(process.getOutputStream, true)
    started = true

  @TearDown(Level.Trial)
  def stop(): Unit =
    scala.util.Try(writer.close())
    scala.util.Try(process.destroyForcibly())

  def run(cmd: String): Long =
    if !started then return -1L
    writer.println(cmd)
    reader.readLine().toLong


// ── Benchmark class ──────────────────────────────────────────────────────────

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
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
      |sum""".stripMargin)

  private val modFib: Module = src(
    """def fib(n: Int): Int =
      |  if n <= 1 then n else fib(n - 1) + fib(n - 2)
      |fib(30)""".stripMargin)

  private val modTco: Module = src(
    """def sumTco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else sumTco(n - 1, acc + n)
      |sumTco(100000, 0)""".stripMargin)

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
      |  Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0), Point(0.0, 0.0), Line(7.0))
      |var total = 0.0
      |var i = 0
      |while i < 100000 do
      |  shapes.foreach(s => { total = total + area(s) })
      |  i = i + 1
      |total""".stripMargin)

  private val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())

  // ── Interpreter: in-process, one op per call ────────────────────────────
  // Time unit: µs.  These run for seconds per JMH iteration due to workload size.

  @Benchmark def interp_arithLoop(): Unit    = Interpreter(devNull).runSections(modArithLoop)
  @Benchmark def interp_recursionFib(): Unit = Interpreter(devNull).runSections(modFib)
  @Benchmark def interp_recursionTco(): Unit = Interpreter(devNull).runSections(modTco)
  @Benchmark def interp_patternMatch(): Unit = Interpreter(devNull).runSections(modPatternMatch)

  // ── JS: persistent node.js process, OPS_PER_CALL iterations per round-trip
  // JMH divides wall-clock by OPS_PER_CALL → time per single workload execution.

  @Benchmark @OperationsPerInvocation(1000)
  def js_arithLoop(s: JsServer): Long    = s.run("arith_loop")

  @Benchmark @OperationsPerInvocation(1000)
  def js_recursionFib(s: JsServer): Long = s.run("fib")

  @Benchmark @OperationsPerInvocation(1000)
  def js_recursionTco(s: JsServer): Long = s.run("tco")

  @Benchmark @OperationsPerInvocation(1000)
  def js_patternMatch(s: JsServer): Long = s.run("pattern_match")

  @Benchmark @OperationsPerInvocation(1000)
  def js_tupleMonoid(s: JsServer): Long  = s.run("tuple_monoid")

  // ── JVM: persistent java process (pre-compiled standalone JAR), same protocol

  @Benchmark @OperationsPerInvocation(1000)
  def jvm_arithLoop(s: JvmServer): Long    = s.run("arith_loop")

  @Benchmark @OperationsPerInvocation(1000)
  def jvm_recursionFib(s: JvmServer): Long = s.run("fib")

  @Benchmark @OperationsPerInvocation(1000)
  def jvm_recursionTco(s: JvmServer): Long = s.run("tco")

  @Benchmark @OperationsPerInvocation(1000)
  def jvm_patternMatch(s: JvmServer): Long = s.run("pattern_match")

  @Benchmark @OperationsPerInvocation(1000)
  def jvm_tupleMonoid(s: JvmServer): Long  = s.run("tuple_monoid")


// ── Server script generators ─────────────────────────────────────────────────

/** Generates a node.js server that reads workload commands from stdin,
 *  runs each [[n]] times, and writes total nanoseconds to stdout. */
private def jsServerScript(n: Int): String =
  s"""|'use strict';
      |const { performance } = require('perf_hooks');
      |const readline = require('readline');
      |
      |// ── Workloads (same logic as JsGen would emit) ──────────────────────────────
      |
      |function bench_arithLoop() {
      |  var i = 0, sum = 0;
      |  while (i < 1000000) { sum = sum + i; i = i + 1; }
      |  return sum;
      |}
      |
      |function bench_fib(n) {
      |  return n <= 1 ? n : bench_fib(n - 1) + bench_fib(n - 2);
      |}
      |
      |function bench_tco(n, acc) {
      |  while (true) {
      |    if (n <= 0) return acc;
      |    acc = acc + n; n = n - 1;
      |  }
      |}
      |
      |// js-codegen-opt-p2: constant tuple hoisted as frozen const before loop
      |const _k_tuple = Object.freeze(Object.assign([1, 2, 3, 4], {_isTuple: true}));
      |function bench_tupleMonoid() {
      |  let i = 0;
      |  let last = Object.assign([0, 0, 0, 0], {_isTuple: true});
      |  while (i < 100000) { last = _k_tuple; i = i + 1; }
      |  return last;
      |}
      |
      |function bench_patternMatch() {
      |  var shapes = [
      |    {_tag:'Circle',r:1.0}, {_tag:'Rect',w:2.0,h:3.0},
      |    {_tag:'Triangle',b:4.0,h:5.0}, {_tag:'Point',x:0.0,y:0.0},
      |    {_tag:'Line',len:7.0}
      |  ];
      |  function area(s) {
      |    switch (s._tag) {
      |      case 'Circle':   return 3.14159 * s.r * s.r;
      |      case 'Rect':     return s.w * s.h;
      |      case 'Triangle': return 0.5 * s.b * s.h;
      |      default:         return 0.0;
      |    }
      |  }
      |  var total = 0.0, i = 0;
      |  while (i < 100000) {
      |    for (var j = 0; j < shapes.length; j++) total += area(shapes[j]);
      |    i = i + 1;
      |  }
      |  return total;
      |}
      |
      |// ── Server harness ──────────────────────────────────────────────────────────
      |const N = $n;
      |
      |const rl = readline.createInterface({ input: process.stdin, terminal: false });
      |rl.on('line', (line) => {
      |  const cmd = line.trim();
      |  let _sink = 0;
      |  const t0 = performance.now();
      |  for (let iter = 0; iter < N; iter++) {
      |    switch (cmd) {
      |      case 'arith_loop':    _sink += bench_arithLoop(); break;
      |      case 'fib':           _sink += bench_fib(30); break;
      |      case 'tco':           _sink += bench_tco(100000, 0); break;
      |      case 'pattern_match': _sink += bench_patternMatch(); break;
      |      case 'tuple_monoid':  _sink += bench_tupleMonoid()[0]; break;
      |    }
      |  }
      |  const ns = BigInt(Math.round((performance.now() - t0) * 1e6));
      |  process.stdout.write(ns.toString() + '\\n');
      |  if (_sink === -999999999999) process.stderr.write('sink\\n');
      |});
      |""".stripMargin

/** Generates a Scala script server that reads workload commands from stdin,
 *  runs each [[n]] times, and writes total nanoseconds to stdout. */
private def jvmServerScript(n: Int): String =
  s"""|import java.io.{BufferedReader, InputStreamReader}
      |
      |// ── Workloads (same logic as JvmGen would emit) ──────────────────────────────
      |
      |def bench_arithLoop(): Int =
      |  var i = 0; var sum = 0
      |  while i < 1000000 do
      |    sum = sum + i
      |    i = i + 1
      |  sum
      |
      |def bench_fib(n: Int): Int =
      |  if n <= 1 then n else bench_fib(n - 1) + bench_fib(n - 2)
      |
      |@annotation.tailrec
      |def bench_tco(n: Int, acc: Int): Int =
      |  if n <= 0 then acc else bench_tco(n - 1, acc + n)
      |
      |sealed trait Shape
      |case class Circle(r: Double) extends Shape
      |case class Rect(w: Double, h: Double) extends Shape
      |case class Triangle(b: Double, h: Double) extends Shape
      |case class Point(x: Double, y: Double) extends Shape
      |case class Line(len: Double) extends Shape
      |
      |val shapes = List(Circle(1.0), Rect(2.0, 3.0), Triangle(4.0, 5.0), Point(0.0, 0.0), Line(7.0))
      |
      |def area(s: Shape): Double = s match
      |  case Circle(r)      => 3.14159 * r * r
      |  case Rect(w, h)     => w * h
      |  case Triangle(b, h) => 0.5 * b * h
      |  case _              => 0.0
      |
      |def bench_patternMatch(): Double =
      |  var total = 0.0; var i = 0
      |  while i < 100000 do
      |    shapes.foreach(s => total = total + area(s))
      |    i = i + 1
      |  total
      |
      |def bench_tupleMonoid(): (Int, Int, Int, Int) =
      |  val k = (1, 2, 3, 4)  // constant; hoisted by JVM JIT
      |  var i = 0
      |  var last = (0, 0, 0, 0)
      |  while i < 100000 do
      |    last = k
      |    i = i + 1
      |  last
      |
      |// ── Server harness ──────────────────────────────────────────────────────────
      |val N = $n
      |val reader = BufferedReader(InputStreamReader(System.in))
      |var line = reader.readLine()
      |while line != null do
      |  val t0 = System.nanoTime()
      |  var _sink: Long = 0L
      |  var iter = 0
      |  while iter < N do
      |    _sink += (line.trim() match
      |      case "arith_loop"    => bench_arithLoop().toLong
      |      case "fib"           => bench_fib(30).toLong
      |      case "tco"           => bench_tco(100000, 0).toLong
      |      case "pattern_match" => (bench_patternMatch() * 1e6).toLong
      |      case "tuple_monoid"  => bench_tupleMonoid()._1.toLong
      |      case _               => 0L)
      |    iter = iter + 1
      |  val ns = System.nanoTime() - t0
      |  println(ns)
      |  if _sink == -999999999999L then System.err.println("sink")
      |  System.out.flush()
      |  line = reader.readLine()
      |""".stripMargin

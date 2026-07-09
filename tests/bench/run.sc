#!/usr/bin/env -S scala-cli --server=false
//> using toolkit 0.9.2

// Cross-backend benchmark driver.
//
//   For each workload we keep three equivalent sources:
//     bench/<name>.ssc   — ScalaScript (runs via interpreter, JS, JVM backends)
//     bench/<name>.scala — hand-written Scala 3   (runs via scala-cli)
//     bench/<name>.js    — hand-written JavaScript (runs via node)
//
//   Each script self-times the work portion and prints `BENCH_MS: <number>`
//   so we exclude scala-cli compile / Node startup overhead from the figure.
//   The driver runs each variant `N_RUNS` times, drops the worst run, and
//   reports the median.
//
//   Usage: scala-cli bench/run.sc

val cwd = os.pwd
val repoBench = cwd / "tests" / "bench"
val dir =
  if os.exists(repoBench / "fib.ssc") then repoBench
  else if os.exists(cwd / "fib.ssc") then cwd
  else cwd / "bench"
val root =
  if os.exists(cwd / "build.sbt") then cwd
  else if os.exists(cwd / os.up / os.up / "build.sbt") then cwd / os.up / os.up
  else dir / os.up

val nRuns = 5

val sscBin  = root / "bin" / "ssc"
val jsscBin = root / "bin" / "jssc"
val ssccBin = root / "bin" / "sscc"

// `bin/` is gitignored (built by install.sh). jssc/sscc are thin shells
// around `ssc emit-js` and `ssc compile`, so we emulate them via sscBin.
def sscProc(file: os.Path): os.proc =
  os.proc(sscBin.toString, file.toString)

def jsscProc(file: os.Path): os.proc =
  if os.exists(jsscBin) then os.proc(jsscBin.toString, file.toString)
  else os.proc("bash", "-c", """"$0" emit-js "$1" | node""", sscBin.toString, file.toString)

def ssccProc(file: os.Path): os.proc =
  if os.exists(ssccBin) then os.proc(ssccBin.toString, file.toString)
  else os.proc(sscBin.toString, "run-jvm", file.toString)

case class Target(label: String, run: os.Path => os.proc)

val targets: Seq[(String, Seq[Target])] = Seq(
  "fib"      -> backends("fib"),
  "sum"      -> backends("sum"),
  "list-ops" -> backends("list-ops"),
)

def backends(name: String): Seq[Target] = Seq(
  Target("ssc-int",     _ => sscProc(dir / s"$name.ssc")),
  Target("ssc-js",      _ => jsscProc(dir / s"$name.ssc")),
  Target("ssc-jvm",     _ => ssccProc(dir / s"$name.ssc")),
  Target("scala-cli",   _ => os.proc("scala-cli", "run", "--server=false", (dir / s"$name.scala").toString)),
  Target("node",        _ => os.proc("node", (dir / s"$name.js").toString)),
)

case class Sample(ms: Long, result: String)

// JS does float division, so BENCH_MS may carry a fractional part.
val BENCH = """^BENCH_MS:\s*([\d.]+)\s*$""".r
val RES   = """^result=(.*)$""".r

def runOne(t: Target, file: os.Path): Option[Sample] =
  try
    val r = t.run(file).call(stderr = os.Pipe, check = false)
    if r.exitCode != 0 then None
    else
      var ms: Option[Long]    = None
      var res: Option[String] = None
      r.out.lines().foreach {
        case BENCH(v) => ms = Some(v.toDouble.round)
        case RES(v)   => res = Some(v)
        case _        => ()
      }
      for m <- ms; s <- res yield Sample(m, s)
  catch case _: Exception => None

def median(xs: Seq[Long]): Long =
  val sorted = xs.sorted
  sorted(sorted.length / 2)

def pad(s: String, w: Int): String =
  if s.length >= w then s else s + " " * (w - s.length)

println(s"\n${"-" * 70}")
println(s"  Benchmark · ${nRuns} runs each · median ms")
println(s"  (work-time only — script self-reports excluding startup/compile)")
println(s"${"-" * 70}\n")

val header = pad("workload", 14) + targets.head._2.map(t => pad(t.label, 12)).mkString
println(header)
println("-" * header.length)

case class Result(name: String, label: String, ms: Option[Long], result: Option[String])

val results = scala.collection.mutable.ArrayBuffer.empty[Result]

for (name, ts) <- targets do
  val row = scala.collection.mutable.ArrayBuffer[String](pad(name, 14))
  for t <- ts do
    val samples = (1 to nRuns).flatMap(_ => runOne(t, dir))
    if samples.isEmpty then
      row += pad("n/a", 12)
      results += Result(name, t.label, None, None)
    else
      val m = median(samples.map(_.ms))
      row += pad(s"$m", 12)
      results += Result(name, t.label, Some(m), samples.headOption.map(_.result))
  println(row.mkString)

println()

// Sanity: every backend's result should match for the same workload.
for (name, _) <- targets do
  val rs = results.filter(_.name == name).flatMap(_.result).distinct
  if rs.length > 1 then
    System.err.println(s"WARN: $name produced different results across backends: ${rs.mkString(" / ")}")

println(s"${"-" * 70}")

#!/usr/bin/env -S scala-cli --server=false
//> using toolkit 0.9.2

// ─────────────────────────────────────────────────────────────────────────────
// Corpus contract — one always-on DIFFERENTIAL gate over BOTH corpora
// (tests/conformance/*.ssc + examples/*.ssc) × lanes {int, js, jvm, v2}.
//
// Golden (reference semantics) per case:
//   • expected/<name>.txt if present (deterministic golden), else
//   • the live interpreter (INT) output — established by running INT twice and
//     requiring the two runs to agree (auto-skips non-deterministic cases:
//     random/uuid/time). If INT can't run (server/args/timeout) the case is
//     SKIPped for the whole corpus.
//
// Every other lane is diffed against that golden and classified
//   PASS / DIVERGE / FAIL / TIMEOUT.
//
// The current (case,lane)→status matrix is compared against a FROZEN BASELINE
// (corpus-baseline.tsv). The gate is RED on:
//   • a REGRESSION   — a (case,lane) that was PASS in the baseline is now non-PASS
//   • an IMPROVEMENT — a (case,lane) that was non-PASS is now PASS (tighten the
//                      baseline: it means a gap closed and should be recorded)
//   • a CHANGE       — a known non-PASS changed kind (DIVERGE→FAIL etc.)
// It is GREEN when the live matrix == the baseline (known feature-gaps stay
// documented and don't red the gate).
//
// This is the strangler-fig safety net: refactor the runtime / grow v2 and the
// contract instantly shows any lane that regressed; as v2 catches up to INT the
// baseline shrinks toward zero.
//
// Usage:
//   scala-cli tests/conformance/contract.sc                 # gate against baseline
//   scala-cli tests/conformance/contract.sc --update-baseline
//   scala-cli tests/conformance/contract.sc --only 'hello,lang-*'
//   scala-cli tests/conformance/contract.sc --lanes int,js,v2   (default; add jvm)
//   scala-cli tests/conformance/contract.sc --timeout 30
// ─────────────────────────────────────────────────────────────────────────────

def repoRoot: os.Path =
  Iterator.iterate(os.pwd)(_ / os.up)
    .takeWhile(p => p != p / os.up)
    .find(p => os.exists(p / "build.sbt"))
    .getOrElse(os.pwd)

val root         = repoRoot
val sscBin       = root / "bin" / "ssc"
val sscToolsBin  = root / "bin" / "ssc-tools"
val baselineFile = root / "tests" / "conformance" / "corpus-baseline.tsv"

if !os.exists(sscBin) || !os.exists(sscToolsBin) then
  System.err.println("bin/ssc or bin/ssc-tools not found — build first: bash install.sh --dev")
  System.exit(2)

val cliArgs = args.toList
def flagVal(name: String): Option[String] =
  cliArgs.sliding(2).collectFirst { case List(`name`, v) => v }
val updateBaseline = cliArgs.contains("--update-baseline")
val onlyGlobs      = flagVal("--only").toList.flatMap(_.split(',')).map(_.trim).filter(_.nonEmpty)
val lanes          = flagVal("--lanes").map(_.split(',').map(_.trim).filter(_.nonEmpty).toList)
                       .getOrElse(List("int", "js", "v2"))
val timeoutS       = flagVal("--timeout").map(_.toInt).getOrElse(30)

def globMatch(glob: String, name: String): Boolean =
  ("^" + java.util.regex.Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$").r
    .findFirstIn(name).isDefined

// A corpus case: source file, base name, optional expected-output file.
case class Case(file: os.Path, name: String, expected: Option[String], corpus: String)

def collectCorpus(dir: os.Path, corpus: String, expectedDir: Option[os.Path]): List[Case] =
  if !os.exists(dir) then Nil
  else os.list(dir).filter(_.ext == "ssc").sortBy(_.last).toList.map { f =>
    val name = f.baseName
    val exp  = expectedDir.map(_ / s"$name.txt").filter(os.exists).map(p => os.read(p).stripTrailing())
    Case(f, name, exp, corpus)
  }

val allCases =
  collectCorpus(root / "tests" / "conformance", "conf", Some(root / "tests" / "conformance" / "expected")) ++
  collectCorpus(root / "examples", "ex", None)

// Inherently non-hermetic cases (download Spark, hit a network service, need a real
// browser, …) are excluded via corpus-skip.txt — one glob per line, `#` comments.
// They FLAP (cold=timeout, warm=runs) and would produce false regressions.
val skipFile  = root / "tests" / "conformance" / "corpus-skip.txt"
val skipGlobs =
  if os.exists(skipFile) then
    os.read.lines(skipFile).map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toList
  else Nil
def isSkipped(name: String): Boolean = skipGlobs.exists(g => globMatch(g, name))

val cases = allCases
  .filter(c => onlyGlobs.isEmpty || onlyGlobs.exists(g => globMatch(g, c.name)))
  .filterNot(c => isSkipped(c.name))
  // de-dupe by name (a name appearing in both corpora — conformance wins, it has a golden)
  .groupBy(_.name).values.map(cs => cs.sortBy(c => if c.corpus == "conf" then 0 else 1).head).toList
  .sortBy(_.name)

// `backends:` frontmatter gate — restrict which lanes a case runs on. Accepts the
// same tokens as run.sc (int/js/jvm/v2, `interpreter` aliases int).
def parseBackends(src: String): Option[Set[String]] =
  val lines = src.linesIterator.toList
  val startIdx = lines.indexWhere(_.trim == "---")
  if startIdx < 0 then None
  else
    val rest = lines.drop(startIdx + 1)
    val endIdx = rest.indexWhere(_.trim == "---")
    if endIdx < 0 then None
    else
      val fm = rest.take(endIdx)
      fm.find(_.trim.startsWith("backends:")).flatMap { line =>
        val scalar = line.trim.stripPrefix("backends:").trim
        if scalar.startsWith("[") then
          Some(scalar.stripPrefix("[").stripSuffix("]").split(',').map(_.trim)
            .map(b => if b == "interpreter" then "int" else b).filter(_.nonEmpty).toSet)
        else None
      }

def laneCmd(lane: String, file: os.Path): Seq[String] = lane match
  case "int" => Seq(sscToolsBin.toString, "run", "--v1", file.toString)
  case "js"  => Seq(sscToolsBin.toString, "run-js", file.toString)
  case "jvm" => Seq(sscToolsBin.toString, "run-jvm", file.toString)
  case "v2"  => Seq(sscBin.toString, "run", "--v2", file.toString)
  case other => sys.error(s"unknown lane: $other")

// Run a lane with a hard timeout; returns (stdout, exitCode). 124 = timed out.
// Retries ONCE on timeout so parallel JVM contention (a normally-fast case pushed
// past the timeout) doesn't flap the gate — only a genuine hang times out twice.
def runLane(lane: String, file: os.Path): (String, Int) =
  def once(): (String, Int) =
    val cmd = Seq("timeout", timeoutS.toString) ++ laneCmd(lane, file)
    val r = os.proc(cmd).call(stdin = "", stderr = os.Pipe, check = false)
    (r.out.text().stripTrailing(), r.exitCode)
  val res = once()
  if res._2 == 124 then once() else res

// PASS / DIVERGE / FAIL / TIMEOUT for a lane's (out, rc) against a golden.
def classify(out: String, rc: Int, golden: String): String =
  if rc == 124 then "TIMEOUT"
  else if rc != 0 then "FAIL"
  else if out == golden then "PASS"
  else "DIVERGE"

// Establish the golden + whether the case is runnable at all.
//   Right(golden) → use it. Left(reason) → SKIP the whole case.
def golden(c: Case): Either[String, String] = c.expected match
  case Some(exp) => Right(exp)
  case None =>
    val (o1, rc1) = runLane("int", c.file)
    if rc1 == 124 then Left("int-timeout")
    else if rc1 != 0 then Left("int-nonzero")
    else
      val (o2, rc2) = runLane("int", c.file)
      if rc2 != 0 || o1 != o2 then Left("nondeterministic")
      else Right(o1)

// One case → (name, Left(skip-reason) | Right(lane→status)). Pure per-case work
// (each `runLane` is its own timeout-bounded subprocess), so cases run in parallel.
def processCase(c: Case): (String, Either[String, Map[String, String]]) =
  val gate = parseBackends(os.read(c.file))
  golden(c) match
    case Left(reason) => (c.name, Left(reason))
    case Right(g) =>
      val row =
        for lane <- lanes if gate.forall(_.contains(lane)) yield
          // For an expected-file golden we still diff INT; for a live golden INT == golden.
          if lane == "int" && c.expected.isEmpty then lane -> "PASS"
          else
            val (o, rc) = runLane(lane, c.file)
            lane -> classify(o, rc, g)
      (c.name, Right(row.toMap))

println(s"Corpus contract: ${cases.length} cases × lanes [${lanes.mkString(", ")}] (timeout ${timeoutS}s)")

// Bounded parallelism: each case runs `lanes` sequentially in its own worker, so at
// most `workers` subprocess JVMs are live at once. ~4× faster than serial; hang-safe
// (a hung case only ties up its own worker until its per-run timeout fires). Capped
// at 4 (with the 30s default timeout) so heavy JVM contention doesn't push a slow
// case past the timeout and flap the gate — a flaky contract is worse than a slow one.
val workers = flagVal("--workers").map(_.toInt)
  .getOrElse(math.min(4, math.max(2, Runtime.getRuntime.availableProcessors - 2)))
val pool    = java.util.concurrent.Executors.newFixedThreadPool(workers)
val counter = java.util.concurrent.atomic.AtomicInteger(0)
val futures = cases.map { c =>
  pool.submit(new java.util.concurrent.Callable[(String, Either[String, Map[String, String]])] {
    def call() =
      val r = processCase(c)
      val n = counter.incrementAndGet()
      if n % 25 == 0 then System.err.println(s"  … $n/${cases.length}")
      r
  })
}
val results = futures.map(_.get())
pool.shutdown()

val statuses = collection.mutable.Map.empty[String, collection.mutable.Map[String, String]]
val skips    = collection.mutable.Map.empty[String, String]
for (name, res) <- results do res match
  case Left(reason) => skips(name) = reason
  case Right(row)   => statuses(name) = collection.mutable.Map.from(row)

// Current non-PASS entries: "name\tlane\tstatus". A skipped case records
// "name\t*\tSKIP" WITHOUT the reason — the reason (int-timeout / int-nonzero /
// nondeterministic) legitimately varies run-to-run for a non-hermetic case, and
// gating on it would flap. The reason is printed for humans, not committed.
val current = collection.mutable.SortedSet.empty[String]
for (name, row) <- statuses; (lane, st) <- row if st != "PASS" do current += s"$name\t$lane\t$st"
for (name, reason) <- skips do
  current += s"$name\t*\tSKIP"
  System.err.println(s"  SKIP $name ($reason)")

if updateBaseline then
  os.write.over(baselineFile, current.mkString("\n") + (if current.nonEmpty then "\n" else ""))
  println(s"\nWrote baseline: ${current.size} non-PASS entries → ${baselineFile.relativeTo(root)}")
  System.exit(0)

// ── gate against the frozen baseline ─────────────────────────────────────────
val baseline: Set[String] =
  if os.exists(baselineFile) then os.read.lines(baselineFile).filter(_.trim.nonEmpty).toSet else Set.empty

// Only compare within the lanes/cases we actually ran (subset-safe).
val ranNames = statuses.keySet ++ skips.keySet
def inScope(entry: String): Boolean =
  val parts = entry.split('\t')
  parts.length >= 2 && ranNames.contains(parts(0)) &&
    (parts(1) == "*" || lanes.contains(parts(1)))
val baseScoped = baseline.filter(inScope)

val newRegressions = (current -- baseScoped).toList.sorted   // now non-PASS, wasn't in baseline
val improvements   = (baseScoped -- current).toList.sorted   // was in baseline, now PASS/gone

val sep = "─" * 64
println(s"\n$sep")
val passCount = statuses.values.map(_.values.count(_ == "PASS")).sum
val cellCount = statuses.values.map(_.size).sum
println(s"  PASS cells: $passCount/$cellCount   SKIP cases: ${skips.size}   baseline: ${baseScoped.size}")
println(sep)

if newRegressions.isEmpty && improvements.isEmpty then
  println("✓ contract GREEN — live matrix matches the baseline (no regressions).")
else
  if newRegressions.nonEmpty then
    System.err.println(s"\n✗ ${newRegressions.length} REGRESSION(S) — these were PASS in the baseline:")
    newRegressions.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
  if improvements.nonEmpty then
    System.err.println(s"\n△ ${improvements.length} IMPROVEMENT(S)/stale baseline — now PASS, still in baseline:")
    improvements.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
    System.err.println("  → re-run with --update-baseline to record the closed gaps.")
  System.exit(1)

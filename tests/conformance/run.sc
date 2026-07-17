#!/usr/bin/env -S scala-cli --server=false
//> using toolkit 0.9.2

// Conformance test runner for ScalaScript.
// For each .ssc file in conformance/ it:
//   1. Runs via the JVM interpreter   (INT label)
//   2. Transpiles to JS, runs via Node (JS  label)
//   3. Generates Scala 3 source and compiles+runs via scala-cli (JVM label)
//   4. Optionally runs via the v2 VM (V2 label) for cases with backends: [v2]
// All enabled outputs are compared against the same expected/*.txt.
// Usage: scala-cli conformance/run.sc [conformance-dir]
//   conformance-dir defaults to the directory of this script

// Find the repo root by walking up from cwd looking for build.sbt.
def repoRoot: os.Path =
  Iterator.iterate(os.pwd)(_ / os.up)
    .takeWhile(p => p != p / os.up)
    .find(p => os.exists(p / "build.sbt"))
    .getOrElse(os.pwd)

// CLI: run.sc [dir] [--only glob[,glob...]] [--no-memo] [--warm-jvm|--cold-jvm]
//   --only    run just the matching cases (comma-separated globs on the base
//             name, e.g. --only 'json*,optics-*') — the fix->test loop then
//             costs seconds instead of the full corpus (specs/conformance-perf.md F1).
//   --no-memo disable the green-run memo cache (F2); also SSC_CONF_NO_MEMO=1.
//   --warm-jvm opt into SSC_SCALACLI_SERVER=1 for run-jvm; default is serverless
//             to avoid Bloop BSP/socket flakes in the production gate.
val cliArgs = args.filterNot(_ == "--").toList
val onlyGlobs: List[String] =
  cliArgs.sliding(2).collectFirst { case List("--only", v) => v }.toList
    .flatMap(_.split(',').toList).map(_.trim).filter(_.nonEmpty)
val noMemo: Boolean =
  cliArgs.contains("--no-memo") || sys.env.get("SSC_CONF_NO_MEMO").contains("1")
val positional = cliArgs.filterNot(_.startsWith("--"))
  .filterNot(a => cliArgs.indexOf(a) > 0 && cliArgs(cliArgs.indexOf(a) - 1) == "--only")

val dir: os.Path =
  positional.headOption match
    case Some(p) => os.Path(p, os.pwd)
    case None    => repoRoot / "tests" / "conformance"

val expectedDir = dir / "expected"
val sscBin      = repoRoot / "bin" / "ssc"
val sscToolsBin = repoRoot / "bin" / "ssc-tools"

// Requires the pre-built launcher. Build with `bash install.sh --dev`
// (which produces cli/target/scala-3.8.3/ssc.jar via sbt-assembly and writes
// bin/ssc as a tiny java -jar wrapper).
if !os.exists(sscBin) || !os.exists(sscToolsBin) then
  System.err.println(s"bin/ssc or bin/ssc-tools not found. Build them first: bash install.sh --dev")
  System.exit(2)

def ssc(args: String*): os.proc =
  os.proc(sscBin.toString +: args.toSeq)

def sscTools(args: String*): os.proc =
  os.proc(sscToolsBin.toString +: args.toSeq)

def globMatch(glob: String, name: String): Boolean =
  val rx = ("^" + java.util.regex.Pattern.quote(glob)
    .replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$").r
  rx.findFirstIn(name).isDefined

val tests = os.list(dir)
  .filter(_.ext == "ssc")
  .filter(t => onlyGlobs.isEmpty || onlyGlobs.exists(g => globMatch(g, t.baseName)))
  .sortBy(_.last)

if onlyGlobs.nonEmpty then
  println(s"--only ${onlyGlobs.mkString(",")}: ${tests.length} matching case(s)")

val sep = "-" * 50
var passed = 0
var failed = 0
var memoSkipped = 0

// F2 memo: a case whose (input, expected, ssc.jar identity) is unchanged since
// the last GREEN run is skipped. The jar identity uses size+mtime — cheap and
// machine-local (the cache file lives under target/, not committed).
val memoFile = repoRoot / "target" / "conformance-memo.txt"
val jarId: String =
  // Compatibility lanes use the explicit tools JAR; v2 uses the standard JAR.
  val jars = Seq(repoRoot / "bin" / "lib" / "ssc.jar",
                 repoRoot / "bin" / "lib" / "standard" / "ssc.jar")
  jars.map { jar =>
    val f = jar.toIO
    if f.exists then s"${f.length}-${f.lastModified}" else "no-jar"
  }.mkString(":")
def sha(text: String): String =
  val d = java.security.MessageDigest.getInstance("SHA-256")
  d.digest(text.getBytes("UTF-8")).map("%02x".format(_)).mkString
val memo: collection.mutable.Map[String, String] =
  val m = collection.mutable.Map.empty[String, String]
  if !noMemo && os.exists(memoFile) then
    os.read.lines(memoFile).foreach { l =>
      l.split('\t') match
        case Array(k, v) => m(k) = v
        case _           => ()
    }
  m
def memoKey(name: String, src: String, expected: String): String =
  sha(src + "\u0000" + expected + "\u0000" + jarId)

def outputWithFailureContext(stdout: String, stderr: String, exitCode: Int): String =
  val out = stdout.stripTrailing()
  val err = stderr.stripTrailing()
  if exitCode == 0 then out
  else
    val parts = List(
      Option(out).filter(_.nonEmpty),
      Some(s"<exit:$exitCode>"),
      Option(err).filter(_.nonEmpty)
    ).flatten
    parts.mkString("\n").stripTrailing()

def run(cmd: os.proc): String =
  val res = cmd.call(stdin = "", stderr = os.Pipe, check = false)
  outputWithFailureContext(res.out.text(), res.err.text(), res.exitCode)

def diffLines(got: String, expected: String, limit: Int = Int.MaxValue): Unit =
  val gotLines  = got.linesIterator.toList
  val expLines  = expected.linesIterator.toList
  val maxLen    = expLines.length max gotLines.length
  var shown     = 0
  for i <- 0 until maxLen do
    val e = expLines.lift(i).getOrElse("<missing>")
    val g = gotLines.lift(i).getOrElse("<missing>")
    if e != g && shown < limit then
      println(s"    line ${i+1}: expected=${e.take(80)}  got=${g.take(80)}")
      shown += 1

def check(label: String, got: String, expected: String): Boolean =
  if got == expected then
    println(s"  PASS [$label]")
    true
  else
    println(s"  FAIL [$label]")
    diffLines(got, expected)
    false

var knownRedCount = 0

/** Compare a lane's output, then bucket it — never the other way round.
 *
 *  A `known-red:` lane is still RUN and still COMPARED and still DIFFED; only the
 *  bucket changes. That is the whole difference from the `codegen:` reroute this
 *  replaces (`SPRINT.md` §int-width-conformance W3): a declared red is a visible
 *  red, a reroute is an invisible green.
 *
 *  A known-red that PASSES fails the suite: the declaration has expired and must
 *  be deleted. Without that, a known-red outlives its bug and rots into permanent
 *  noise that hides the next real regression.
 */
def checkLane(label: String, lane: String, got: String, expected: String,
              knownRed: Map[String, String]): Boolean =
  knownRed.get(lane) match
    case None => check(label, got, expected)
    case Some(reason) =>
      if got == expected then
        println(s"  FAIL [$label] STALE known-red: this lane now PASSES — delete the " +
          s"`known-red:` declaration for '$lane' from the case's front-matter.")
        println(s"    declared reason was: $reason")
        false
      else
        println(s"  KNOWN-RED [$label] $reason")
        diffLines(got, expected, limit = 3) // stays visible; capped so it cannot drown real reds
        knownRedCount += 1
        true

// Per-backend feature support — mirrors backend-{interpreter,js,jvm}/.../Capabilities.scala.
// When backends add or drop features, update this map in lockstep.
val backendFeatures: Map[String, Set[String]] = Map(
  "int" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "Dataset", "HttpClient"
  ),
  "js" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "McpServer", "McpClient", "Dataset", "HttpClient"
  ),
  "jvm" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "McpServer", "McpClient", "Dataset", "HttpClient"
  ),
  // v2 is opt-in per case (`backends: [v2]`) so the historical corpus remains
  // unchanged while v2-only regressions can live in the same expected-output
  // harness instead of a separate shell smoke.
  "v2" -> Set(
    "AlgebraicEffects", "MutableState", "PatternMatching", "TypeClasses",
    "ExtensionMethods", "DefaultParameters", "ForComprehensions", "WhileLoops",
    "TailCallOptimization", "StringInterpolators", "ModuleImports",
    "ConsoleIO", "HttpServer", "WebSockets", "Auth", "FileSystem", "Crypto",
    "Dataset", "HttpClient"
  )
)

// Parse `requires:` from YAML frontmatter (between opening and closing `---`).
// Handles two forms:
//   requires: Feature.McpServer          (scalar)
//   requires:                            (sequence)
//     - McpServer
//     - McpClient
// Strips the optional "Feature." prefix so values match backendFeatures keys.
def parseRequires(src: String): List[String] =
  val allLines = src.linesIterator.toList
  // Skip an optional shebang (e.g. `#!/usr/bin/env ssc`) so the frontmatter
  // delimiter ("---") still matches when authors prefix the file with one.
  val lines = if allLines.headOption.exists(_.startsWith("#!")) then allLines.tail else allLines
  if !lines.headOption.contains("---") then return List.empty
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return List.empty
  val fm = lines.slice(1, fmEnd + 1)
  fm.indexWhere(_.startsWith("requires:")) match
    case -1 => List.empty
    case i  =>
      val header = fm(i)
      val scalar = header.stripPrefix("requires:").trim
      val raw =
        if scalar.nonEmpty then List(scalar)
        else
          fm.drop(i + 1).takeWhile(l => l.startsWith("  -") || l.startsWith("- "))
            .map(_.dropWhile(c => c == ' ' || c == '-').trim)
      raw.map(_.stripPrefix("Feature.").trim).filter(_.nonEmpty)

// Parse `backends:` from YAML frontmatter — limits which of int/js/jvm a
// test is allowed to run on. Supported forms:
//   backends: [jvm, js]                  (flow sequence)
//   backends:                            (block sequence)
//     - jvm
//     - js
// Returns Some(set) when present (only those backends run); None means
// eligible for all three (existing behavior). Accepts "interpreter" as an
// alias for "int" so frontmatter can use the long form for clarity.
def parseBackends(src: String): Option[Set[String]] =
  val allLines = src.linesIterator.toList
  val lines = if allLines.headOption.exists(_.startsWith("#!")) then allLines.tail else allLines
  if !lines.headOption.contains("---") then return None
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return None
  val fm = lines.slice(1, fmEnd + 1)
  fm.indexWhere(_.startsWith("backends:")) match
    case -1 => None
    case i  =>
      val header = fm(i)
      val scalar = header.stripPrefix("backends:").trim
      val raw =
        if scalar.startsWith("[") then
          scalar.stripPrefix("[").stripSuffix("]").split(',').toList.map(_.trim)
        else if scalar.nonEmpty then
          List(scalar)
        else
          fm.drop(i + 1).takeWhile(l => l.startsWith("  -") || l.startsWith("- "))
            .map(_.dropWhile(c => c == ' ' || c == '-').trim)
      val normalised = raw.map(_.toLowerCase).map {
        case "interpreter" => "int"
        case other         => other
      }.filter(_.nonEmpty).toSet
      if normalised.isEmpty then None else Some(normalised)

// The YAML front-matter lines of a case, or Nil when it has none.
def frontmatter(src: String): List[String] =
  val allLines = src.linesIterator.toList
  val lines = if allLines.headOption.exists(_.startsWith("#!")) then allLines.tail else allLines
  if !lines.headOption.contains("---") then return Nil
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return Nil
  lines.slice(1, fmEnd + 1)

// Parse `also-codegen: v2` — run this case's JVM/JS lanes on the v2 codegen
// (`run --bytecode` / `run-js --v2`) **IN ADDITION TO** the v1 codegen lanes.
//
// This key REPLACED `codegen: v2`, which routed the JVM/JS lanes to v2 *INSTEAD
// OF* v1 (removed 2026-07-17, `SPRINT.md` §int-width-conformance W3). The old
// key let a case pick the backend that agreed with it: `deep-tail-recursion`
// needs a 64-bit `Int` (its accumulator is 5e9), the v1 codegen truncates to 32
// bits, so the case opted out of the v1 codegen and the suite reported all-green
// while a real, silent backend divergence sat underneath. That is the
// conformance suite — whose entire job is to catch backend divergence — routing
// around a divergence. `AGENTS.md` ("apparatus must COMPARE, never PRE-JUDGE")
// now bans exactly this.
//
// There is deliberately NO "instead of" form: additive by construction, so the
// key cannot be used to dodge a divergence. A case that diverges on a backend
// declares `known-red:` below, which stays visible in the output and expires.
def parseAlsoCodegen(src: String): Option[String] =
  frontmatter(src).find(_.startsWith("also-codegen:"))
    .map(_.stripPrefix("also-codegen:").trim.toLowerCase).filter(_.nonEmpty)

// Parse `known-red: <lane>[,<lane>] — <reason>` — a DECLARED, EXPIRING known
// non-conformance on specific lanes (`int`, `js`, `jvm`).
//
// The lane still RUNS and its output is still COMPARED and DIFFED — only the
// bucket changes. This is the difference between a known-red and the `codegen:`
// reroute it replaces: a known-red is a *visible* red with a stated reason; a
// reroute is an invisible green.
//
// It EXPIRES BY ITSELF: if a declared-red lane starts PASSING, the suite FAILS
// and tells you to delete the declaration. A known-red that outlives its bug
// would otherwise rot into permanent noise that hides the next real regression.
def parseKnownRed(src: String): Map[String, String] =
  frontmatter(src).find(_.startsWith("known-red:")) match
    case None => Map.empty
    case Some(line) =>
      // The reason contains `: ` and `#`, so the YAML value must be quoted in the
      // front-matter (the real YAML parser rejects it otherwise) — unquote here.
      val raw = line.stripPrefix("known-red:").trim
      val body =
        if raw.length >= 2 && raw.head == '"' && raw.last == '"' then raw.drop(1).dropRight(1)
        else if raw.length >= 2 && raw.head == '\'' && raw.last == '\'' then raw.drop(1).dropRight(1)
        else raw
      // `<lanes> — <reason>`; the em-dash separator keeps lane lists unambiguous.
      val (lanesPart, reason) = body.split("—", 2) match
        case Array(l, r) => (l.trim, r.trim)
        case _           => (body, "")
      if reason.isEmpty then
        System.err.println(s"[error] known-red without a reason: '$line' — a known-red MUST state " +
          "why it is red and when it expires, or it is indistinguishable from an unnoticed bug")
        System.exit(1)
      lanesPart.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).map(_ -> reason).toMap

// Parse `pending: <reason>` — a one-line frontmatter marker that PENDINGs
// the test out of the suite (counted as pending, not failed). Use sparingly
// for tests that document an intended API that needs other infrastructure
// (e.g. AutoResolve wired into `compile`) to actually run.
def parsePending(src: String): Option[String] =
  val allLines = src.linesIterator.toList
  val lines = if allLines.headOption.exists(_.startsWith("#!")) then allLines.tail else allLines
  if !lines.headOption.contains("---") then return None
  val fmEnd = lines.tail.indexOf("---")
  if fmEnd < 0 then return None
  val fm = lines.slice(1, fmEnd + 1)
  fm.find(_.startsWith("pending:")).map(_.stripPrefix("pending:").trim).filter(_.nonEmpty)

println(s"\nConformance suite — ${tests.length} tests\n$sep")

var pendingCount = 0

// ── F4 batch lanes (specs/conformance-perf.md) ──────────────────────────────
// One JVM runs ALL eligible cases per lane instead of a cold JVM per case:
// INT executes in one explicit `ssc-tools run-batch --v1`; JS emits every
// source through the explicit tools launcher in one JVM
// (node stays per-case — its start is ~50 ms). A case missing from the batch
// output (e.g. an earlier case called exit()) falls back to a per-case run.
val BATCH_MARK = "<<<SSC-BATCH-CASE:"

case class Meta(test: os.Path, name: String, src: String, expected: Option[String],
                requires: List[String], backendsGate: Option[Set[String]],
                pending: Option[String], alsoCodegen: Option[String],
                knownRed: Map[String, String], memoHit: Boolean)

val metas: List[Meta] = tests.toList.map { t =>
  val src = os.read(t)
  val ef  = expectedDir / s"${t.baseName}.txt"
  val exp = if os.exists(ef) then Some(os.read(ef).stripTrailing()) else None
  val hit = !noMemo && exp.exists(e => memo.get(t.baseName).contains(memoKey(t.baseName, src, e)))
  Meta(t, t.baseName, src, exp, parseRequires(src), parseBackends(src), parsePending(src),
       parseAlsoCodegen(src), parseKnownRed(src), hit)
}

def metaSupports(m: Meta, b: String): Boolean =
  m.backendsGate.forall(_.contains(b)) &&
  (m.requires.isEmpty || m.requires.forall(backendFeatures.getOrElse(b, Set.empty).contains))

def splitBatch(out: String): Map[String, String] =
  val sections = collection.mutable.Map.empty[String, String]
  var current: Option[String] = None
  val buf = new StringBuilder
  def flushCur(): Unit = current.foreach { n => sections(n) = buf.toString.stripTrailing }
  out.linesIterator.foreach { l =>
    if l.startsWith(BATCH_MARK) then
      flushCur(); buf.clear(); current = Some(l.stripPrefix(BATCH_MARK).trim)
    else if current.isDefined then buf.append(l).append('\n')
  }
  flushCur()
  sections.toMap

val noBatch: Boolean =
  cliArgs.contains("--no-batch") || sys.env.get("SSC_CONF_NO_BATCH").contains("1")

def batchLane(launcher: os.Path, extra: Seq[String], eligible: List[Meta]): Map[String, String] =
  if noBatch || eligible.length < 3 then Map.empty
  else
    val cmd = Seq(launcher.toString, "run-batch", "--delim", BATCH_MARK) ++ extra ++ eligible.map(_.test.toString)
    val res = os.proc(cmd).call(stdin = "", stderr = os.Pipe, check = false)
    splitBatch(res.out.text())

val runnable   = metas.filter(m => m.pending.isEmpty && m.expected.isDefined && !m.memoHit)
val intBatch   = batchLane(sscToolsBin, Seq("--v1"), runnable.filter(metaSupports(_, "int")))
val jsEmitted  = batchLane(sscToolsBin, Seq("--emit-js"), runnable.filter(metaSupports(_, "js")))

for test <- tests do
  val name         = test.baseName
  val expectedFile = expectedDir / s"$name.txt"
  val src          = os.read(test)
  val requires     = parseRequires(src)
  val backendsGate = parseBackends(src)
  val pending      = parsePending(src)
  val alsoCodegen  = parseAlsoCodegen(src) // Some("v2") → ALSO run the v2 codegen lanes
  val knownRed     = parseKnownRed(src)    // declared, expiring per-lane non-conformance

  if pending.isDefined then
    println(s"$name: PENDING (${pending.get})")
    pendingCount += 1
  else if !os.exists(expectedFile) then
    if requires.nonEmpty then
      val eligible = List("js", "jvm").filter(b => requires.forall(backendFeatures(b).contains))
      val reason   = requires.mkString(", ")
      println(s"$name: SKIP (requires: $reason — add expected/$name.txt to enable on eligible backends: ${eligible.mkString(", ")})")
    else
      println(s"$name: SKIP (no expected/$name.txt)")
  else if !noMemo && memo.get(name).contains(memoKey(name, src, os.read(expectedFile).stripTrailing())) then
    println(s"$name: MEMO (unchanged since last green run)")
    memoSkipped += 1
    passed += 1
  else
    println(s"$name:")
    val expected = os.read(expectedFile).stripTrailing()

    def backendSupports(b: String): Boolean =
      backendsGate.forall(_.contains(b)) &&
      (requires.isEmpty || requires.forall(backendFeatures.getOrElse(b, Set.empty).contains))

    def skipReason(b: String): String =
      if backendsGate.exists(!_.contains(b)) then
        s"backends: ${backendsGate.get.toList.sorted.mkString(", ")}"
      else
        s"requires: ${requires.mkString(", ")}"

    // Interpreter
    val intOk =
      if !backendSupports("int") then
        println(s"  SKIP [INT] (${skipReason("int")})")
        true
      else
        val intOut = intBatch.getOrElse(name, run(sscTools("run", "--v1", test.toString)))
        checkLane("INT", "int", intOut, expected, knownRed)

    // JS via Node.js
    val jsOk =
      if !backendSupports("js") then
        println(s"  SKIP [JS ] (${skipReason("js")})")
        true
      else
        val jsSource = jsEmitted.getOrElse(name, run(sscTools("emit-js", test.toString)))
        val jsRes = os.proc("node").call(stdin = jsSource, stderr = os.Pipe, check = false)
        val jsOut = outputWithFailureContext(jsRes.out.text(), jsRes.err.text(), jsRes.exitCode)
        checkLane("JS ", "js", jsOut, expected, knownRed)

    // v2 JS codegen — ADDITIVE (`also-codegen: v2`), never a replacement for the
    // lane above. See `parseAlsoCodegen`.
    val jsV2Ok =
      if !alsoCodegen.contains("v2") || !backendSupports("js") then true
      else
        val r = os.proc(sscToolsBin.toString, "run-js", "--v2", test.toString)
          .call(stdin = "", stderr = os.Pipe, check = false)
        val out = outputWithFailureContext(r.out.text(), r.err.text(), r.exitCode)
        checkLane("JS/v2", "js-v2", out, expected, knownRed)

    // JVM via JvmGen + scala-cli compile+run
    val jvmOk =
      if !backendSupports("jvm") then
        println(s"  SKIP [JVM] (${skipReason("jvm")})")
        true
      else
        // Serverless by default: the production gate must not depend on a
        // long-lived Bloop BSP socket. --warm-jvm/SSC_CONF_WARM_JVM=1 (or the
        // legacy SSC_SCALACLI_SERVER=1 env) opts into the faster warm lane.
        val warmJvm =
          !cliArgs.contains("--cold-jvm") &&
          (cliArgs.contains("--warm-jvm") ||
            sys.env.get("SSC_CONF_WARM_JVM").contains("1") ||
            sys.env.get("SSC_SCALACLI_SERVER").contains("1"))
        val jvmEnv =
          if warmJvm then Map("SSC_SCALACLI_SERVER" -> "1")
          else Map("SSC_SCALACLI_SERVER" -> "0")
        val jvmRes =
          os.proc(sscToolsBin.toString, "run-jvm", test.toString)
            .call(stdin = "", stderr = os.Pipe, check = false, env = jvmEnv)
        val jvmOut = outputWithFailureContext(jvmRes.out.text(), jvmRes.err.text(), jvmRes.exitCode)
        checkLane("JVM", "jvm", jvmOut, expected, knownRed)

    // v2 JVM codegen — ADDITIVE (`also-codegen: v2`), never a replacement for the
    // lane above. See `parseAlsoCodegen`.
    val jvmV2Ok =
      if !alsoCodegen.contains("v2") || !backendSupports("jvm") then true
      else
        val r = os.proc(sscBin.toString, "run", "--bytecode", test.toString)
          .call(stdin = "", stderr = os.Pipe, check = false)
        val out = outputWithFailureContext(r.out.text(), r.err.text(), r.exitCode)
        checkLane("JVM/v2", "jvm-v2", out, expected, knownRed)

    // v2 VM lane: opt-in only. Running it for every historical conformance
    // case would change the suite contract and surface unrelated migration
    // gaps; `backends: [v2]` cases explicitly ask for this lane.
    val v2Ok =
      if !backendsGate.exists(_.contains("v2")) then true
      else if !backendSupports("v2") then
        println(s"  SKIP [V2 ] (${skipReason("v2")})")
        true
      else
        val v2Out = run(ssc("run", "--v2", test.toString))
        check("V2 ", v2Out, expected)

    if intOk && jsOk && jsV2Ok && jvmOk && jvmV2Ok && v2Ok then
      passed += 1
      if !noMemo then memo(name) = memoKey(name, src, expected)
    else failed += 1

println(s"\n$sep")
if !noMemo then
  os.makeDir.all(memoFile / os.up)
  os.write.over(memoFile, memo.toList.sorted.map { case (k, v) => s"$k\t$v" }.mkString("\n"))
val pendingSuffix = if pendingCount > 0 then s" (+ $pendingCount pending)" else ""
val knownRedSuffix =
  if knownRedCount > 0 then
    s" [$knownRedCount declared known-red lane(s) — see KNOWN-RED lines above]"
  else ""
val memoSuffix    = if memoSkipped > 0 then s" ($memoSkipped memoized)" else ""
println(s"Results: $passed passed, $failed failed out of ${passed + failed} tests$pendingSuffix$memoSuffix$knownRedSuffix")

if failed > 0 then System.exit(1)

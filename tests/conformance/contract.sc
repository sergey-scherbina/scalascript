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
// (`corpus-baseline.tsv`) and the case universe frozen with it
// (`contract-roster.tsv`). The gate is RED on:
//   • a NEW CASE     — a case absent from the frozen roster (PASS or non-PASS)
//   • a REGRESSION   — a rostered case has a non-PASS row absent from the baseline
//   • an IMPROVEMENT — a (case,lane) that was non-PASS is now PASS (tighten the
//                      baseline: it means a gap closed and should be recorded)
//   • a CHANGE       — a known non-PASS changed kind (DIVERGE→FAIL etc.)
// It is GREEN when the live matrix and case universe equal the same frozen
// observation (known feature-gaps stay documented and don't red the gate).
//
// This is the strangler-fig safety net: refactor the runtime / grow v2 and the
// contract instantly shows any lane that regressed; as v2 catches up to INT the
// baseline shrinks toward zero.
//
// Usage:
//   scala-cli tests/conformance/contract.sc                          # gate against baseline
//   scala-cli tests/conformance/contract.sc -- --update-baseline
//   scala-cli tests/conformance/contract.sc -- --only 'hello,lang-*'
//   scala-cli tests/conformance/contract.sc -- --lanes int,js,v2    # default; add jvm
//   scala-cli tests/conformance/contract.sc -- --timeout 30
//   scala-cli tests/conformance/contract.sc -- --shard 0/4          # CI matrix slice
//   scala-cli tests/conformance/contract.sc -- --shard 0/4 --list   # names only, runs nothing
//   scala-cli tests/conformance/contract.sc -- --self-test           # classifier only, no build
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
val rosterFile   = root / "tests" / "conformance" / "contract-roster.tsv"

// scala-cli forwards the shebang's `--server=false` into script `args`; it is
// runner configuration, not a Corpus Contract option.
val cliArgs = args.toList.filterNot(_ == "--server=false")

def cliFail(message: String): Nothing =
  System.err.println(s"[error] corpus contract CLI: $message")
  System.exit(2)
  throw new IllegalStateException("unreachable")

val valueFlags = Set("--only", "--lanes", "--timeout", "--lane-timeout", "--shard", "--workers")
val switchFlags = Set("--update-baseline", "--list", "--self-test")

// Fail closed on unknown, duplicate, or valueless options. In particular, a
// trailing `--only` must not disappear into the default full selection while
// `--update-baseline` is preparing to replace the whole freeze.
def validateCli(args: List[String]): Unit =
  val seen = collection.mutable.Set.empty[String]
  var i = 0
  while i < args.length do
    val arg = args(i)
    if seen.contains(arg) then cliFail(s"duplicate option: $arg")
    if valueFlags.contains(arg) then
      if i + 1 >= args.length || args(i + 1).startsWith("--") then
        cliFail(s"$arg requires a value")
      seen += arg
      i += 2
    else if switchFlags.contains(arg) then
      seen += arg
      i += 1
    else cliFail(s"unknown argument: $arg")

validateCli(cliArgs)

def flagVal(name: String): Option[String] =
  cliArgs.sliding(2).collectFirst { case List(`name`, v) => v }

def commaValues(name: String): List[String] =
  val values = flagVal(name).toList.flatMap(_.split(",", -1)).map(_.trim)
  if values.exists(_.isEmpty) then cliFail(s"$name contains an empty comma-separated value")
  values

def positiveInt(name: String, default: => Int): Int =
  flagVal(name) match
    case None => default
    case Some(raw) =>
      raw.toIntOption.filter(_ > 0).getOrElse(cliFail(s"$name expects a positive integer, got: '$raw'"))

val updateBaseline = cliArgs.contains("--update-baseline")
val onlyRequested  = cliArgs.contains("--only")
val onlyGlobs      = commaValues("--only")
if onlyRequested && onlyGlobs.isEmpty then cliFail("--only requires at least one non-empty glob")

val canonicalLanes = List("int", "js", "v2")
val allowedLanes   = Set("int", "js", "jvm", "v2")
val lanesRequested = cliArgs.contains("--lanes")
val lanes          = if lanesRequested then commaValues("--lanes") else canonicalLanes
if lanes.isEmpty then cliFail("--lanes requires at least one lane")
val unknownLanes = lanes.filterNot(allowedLanes)
if unknownLanes.nonEmpty then cliFail(s"unknown lane(s): ${unknownLanes.distinct.mkString(",")}")
val duplicateLanes = lanes.groupBy(identity).collect { case (lane, xs) if xs.size > 1 => lane }.toList.sorted
if duplicateLanes.nonEmpty then cliFail(s"duplicate lane(s): ${duplicateLanes.mkString(",")}")

val timeoutS       = positiveInt("--timeout", 30)

// TWO budgets, deliberately different (measured 2026-07-27):
//   `--timeout`      bounds the INT probe that establishes the golden. It also decides which
//                    cases become SKIPs (a server/arg case is skipped when int can't run it),
//                    and a skipped case pays this twice — with ~78 skips, raising THIS is what
//                    makes the whole gate expensive. Keep it tight.
//   `--lane-timeout` bounds the per-lane comparison runs. This one has to be generous: the `v2`
//                    lane invokes the self-hosted compiler, and since F became the default native
//                    front it costs ~7x more than legacy on the scljet cases — `scljet-crud`
//                    measured 28.2 s under F vs 4.16 s under `SSC_FRONT=legacy`, with IDENTICAL
//                    output. At a 30 s budget that is a coin flip on a loaded runner, so seven
//                    scljet cases reported `v2 TIMEOUT` as REGRESSIONS in run 30281019432 while
//                    being perfectly correct.
// This separation is NOT a way to hide the slowdown: the F compile cost is filed in BUGS.md
// (`f-front-compile-cost-7x-on-scljet`) and belongs to a perf gate. A correctness gate that
// reports perf as TIMEOUT noise trains people to ignore it — which is how this one died.
val laneTimeoutS   = positiveInt("--lane-timeout", math.max(90, timeoutS))
val workersOverride = flagVal("--workers").map(_ => positiveInt("--workers", 1))

// `--shard i/N` — run only the cases whose index in the sorted, deduped case list is
// ≡ i (mod N). ROUND-ROBIN, not contiguous blocks: the corpus is name-sorted and the
// slow cases cluster by name, so blocks would give wildly uneven shards. The baseline
// compare is already subset-safe (`inScope` scopes it to the names actually run), so a
// shard gates honestly against its own slice and N shards together cover the corpus.
val shard: Option[(Int, Int)] = flagVal("--shard").map { s =>
  s.split('/').toList.map(_.trim.toIntOption) match
    case List(Some(i), Some(n)) if n > 0 && i >= 0 && i < n => (i, n)
    case _ => cliFail(s"--shard expects i/N with 0 <= i < N (e.g. 0/4), got: '$s'")
}

/** Every partial observation that would make a full baseline rewrite destructive. */
def unsafeBaselineUpdateScopes(
    onlyRequested: Boolean,
    only: List[String],
    shardRequested: Boolean,
    shard: Option[(Int, Int)],
    lanesRequested: Boolean,
    lanes: List[String],
    listOnly: Boolean,
    selfTest: Boolean
): List[String] =
  List(
    Option.when(onlyRequested)(s"--only ${only.mkString(",")}"),
    Option.when(shardRequested)(shard.map((i, n) => s"--shard $i/$n").getOrElse("--shard")),
    Option.when(lanesRequested && lanes != canonicalLanes)(
      s"--lanes ${lanes.mkString(",")} (canonical: ${canonicalLanes.mkString(",")})"),
    Option.when(listOnly)("--list"),
    Option.when(selfTest)("--self-test")
  ).flatten

// `--update-baseline` rewrites the WHOLE non-PASS matrix AND roster. Any scoped
// run would silently erase observations it did not make, so refuse before
// checking the toolchain or touching either file.
val unsafeUpdate = unsafeBaselineUpdateScopes(
  onlyRequested,
  onlyGlobs,
  cliArgs.contains("--shard"),
  shard,
  lanesRequested,
  lanes,
  cliArgs.contains("--list"),
  cliArgs.contains("--self-test")
)
if updateBaseline && unsafeUpdate.nonEmpty then
  System.err.println(
    "--update-baseline requires the full unsharded corpus on canonical lanes int,js,v2; " +
      s"unsafe partial scope: ${unsafeUpdate.mkString("; ")}. No baseline files were written.")
  System.exit(2)

def globMatch(glob: String, name: String): Boolean =
  ("^" + java.util.regex.Pattern.quote(glob).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$").r
    .findFirstIn(name).isDefined

case class FrozenContract(baseline: Set[String], roster: Set[String])
case class ContractDelta(
    scopedBaseline: Set[String],
    newCases: List[String],
    newNonPass: List[String],
    regressions: List[String],
    statusChanges: List[String],
    improvements: List[String],
    removedCases: List[String]
)

val RosterHeader =
  """# corpus-contract-roster-v1\tbaseline-sha256=([0-9a-f]{64})\troster-sha256=([0-9a-f]{64})""".r
val Utf8 = java.nio.charset.StandardCharsets.UTF_8
val NonPassStatuses = Set("DIVERGE", "FAIL", "TIMEOUT", "KNOWN-RED", "SKIP")

def sha256(bytes: Array[Byte]): String =
  java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    .map(b => f"${b & 0xff}%02x").mkString

def canonicalText(lines: List[String]): String =
  lines.mkString("\n") + (if lines.nonEmpty then "\n" else "")

def canonicalBytes(lines: List[String]): Array[Byte] = canonicalText(lines).getBytes(Utf8)

def entryParts(entry: String): (String, String, String) =
  entry.split("\t", -1).toList match
    case List(name, lane, status) => (name, lane, status)
    case _ => throw new IllegalArgumentException(s"invalid contract entry: '$entry'")

def entryCase(entry: String): String = entryParts(entry)._1
def entryKey(entry: String): (String, String) =
  val (name, lane, _) = entryParts(entry)
  name -> lane

def rowsByKey(rows: Set[String]): Map[(String, String), String] =
  rows.iterator.map { entry =>
    val (name, lane, status) = entryParts(entry)
    (name -> lane) -> status
  }.toMap

def parseBaselineLines(lines: List[String]): Either[String, Set[String]] =
  if lines.exists(_.trim.isEmpty) then Left("blank/whitespace baseline row")
  else if lines != lines.sorted then Left("baseline rows are not sorted")
  else if lines.distinct.size != lines.size then Left("baseline contains duplicate rows")
  else
    val malformed = lines.find { line =>
      line.split("\t", -1).toList match
        case List(name, lane, status) =>
          name.isEmpty || name != name.trim || lane.isEmpty || lane != lane.trim ||
            status != status.trim || (!allowedLanes.contains(lane) && lane != "*") ||
            !NonPassStatuses.contains(status) ||
            (lane == "*") != (status == "SKIP")
        case _ => true
    }
    malformed match
      case Some(line) =>
        Left(s"malformed baseline row: '$line' (expected case<TAB>lane<TAB>non-PASS-status)")
      case None =>
        val keys = lines.map(entryKey)
        val duplicateKeys =
          keys.groupBy(identity).collect { case (key, xs) if xs.size > 1 => key }.toList.sortBy(identity)
        if duplicateKeys.nonEmpty then
          Left("baseline contains multiple statuses for cell(s): " +
            duplicateKeys.map((name, lane) => s"$name/$lane").mkString(", "))
        else Right(lines.toSet)

def parseRosterLines(
    lines: List[String],
    baselineCanonicalBytes: Array[Byte],
    baseline: Set[String]
): Either[String, Set[String]] =
  lines match
    case Nil => Left("roster is empty (missing version/hash header)")
    case header :: names =>
      header match
        case RosterHeader(recordedBaselineHash, recordedRosterHash) =>
          val actualBaselineHash = sha256(baselineCanonicalBytes)
          val actualRosterHash = sha256(canonicalBytes(names))
          if recordedBaselineHash != actualBaselineHash then
            Left(s"roster/baseline digest mismatch: roster=$recordedBaselineHash " +
              s"baseline=$actualBaselineHash")
          else if recordedRosterHash != actualRosterHash then
            Left(s"roster body digest mismatch: header=$recordedRosterHash body=$actualRosterHash")
          else if names.isEmpty then Left("roster has no case names")
          else if names.exists(n => n.isEmpty || n != n.trim || n.contains('\t') || n.startsWith("#")) then
            Left("roster names must be non-empty, unindented, one-column values")
          else if names != names.sorted then Left("roster names are not sorted")
          else if names.distinct.size != names.size then Left("roster contains duplicate case names")
          else
            val roster = names.toSet
            val missing = baseline.map(entryCase).diff(roster).toList.sorted
            if missing.nonEmpty then
              Left(s"baseline names absent from roster: ${missing.mkString(", ")}")
            else Right(roster)
        case _ =>
          Left("unsupported/malformed roster header; expected " +
            "'# corpus-contract-roster-v1<TAB>baseline-sha256=<64 lowercase hex>" +
            "<TAB>roster-sha256=<64 lowercase hex>'")

def loadFrozenContract(): FrozenContract =
  def fail(message: String): Nothing =
    System.err.println(s"[error] corpus contract freeze invalid: $message")
    System.exit(2)
    throw new IllegalStateException("unreachable")

  if !os.exists(baselineFile) then fail(s"missing ${baselineFile.relativeTo(root)}")
  if !os.exists(rosterFile) then fail(s"missing ${rosterFile.relativeTo(root)}")
  val baselineText = new String(os.read.bytes(baselineFile), Utf8)
  val baselineLines = baselineText.linesIterator.toList
  val baseline = parseBaselineLines(baselineLines) match
    case Right(value) => value
    case Left(error)  => fail(error)
  val rosterText = new String(os.read.bytes(rosterFile), Utf8)
  val roster = parseRosterLines(rosterText.linesIterator.toList, canonicalBytes(baselineLines), baseline) match
    case Right(value) => value
    case Left(error)  => fail(error)
  FrozenContract(baseline, roster)

def contractDelta(
    current: Set[String],
    baseline: Set[String],
    roster: Set[String],
    ranNames: Set[String],
    observedLaneKeys: Set[(String, String)],
    fullSelectedNames: Option[Set[String]]
): ContractDelta =
  val currentByKey = rowsByKey(current)
  val currentCases = current.map(entryCase)
  val observedKeys = observedLaneKeys ++ ranNames.map(name => name -> "*")
  val scopedBaseline = baseline.filter(entry => observedKeys.contains(entryKey(entry)))
  val scopedByKey = rowsByKey(scopedBaseline)
  val newCases = (ranNames -- roster).toList.sorted
  val newCaseSet = newCases.toSet
  val regressions = current.filter { entry =>
    roster.contains(entryCase(entry)) && !scopedByKey.contains(entryKey(entry))
  }.toList.sorted
  val statusChanges = current.flatMap { entry =>
    val key = entryKey(entry)
    scopedByKey.get(key).filter(_ != currentByKey(key)).map { oldStatus =>
      val (name, lane) = key
      s"$name\t$lane\t$oldStatus → ${currentByKey(key)}"
    }
  }.toList.sorted
  ContractDelta(
    scopedBaseline = scopedBaseline,
    newCases = newCases,
    newNonPass = current.filter(e => newCaseSet.contains(entryCase(e))).toList.sorted,
    regressions = regressions,
    statusChanges = statusChanges,
    improvements = scopedBaseline.filter { entry =>
      val (name, lane, status) = entryParts(entry)
      if lane == "*" then
        status == "SKIP" && observedLaneKeys.exists(_._1 == name) && !currentCases.contains(name)
      else !currentByKey.contains(name -> lane)
    }.toList.sorted,
    removedCases = fullSelectedNames.map(roster.diff).getOrElse(Set.empty).toList.sorted
  )

def renderRoster(baselineCanonicalBytes: Array[Byte], names: List[String]): String =
  val body = canonicalText(names)
  s"# corpus-contract-roster-v1\tbaseline-sha256=${sha256(baselineCanonicalBytes)}" +
    s"\troster-sha256=${sha256(body.getBytes(Utf8))}\n$body"

def runSelfTest(): Unit =
  var checks = 0
  def check(condition: Boolean, clue: String): Unit =
    checks += 1
    if !condition then throw new AssertionError(s"contract self-test failed: $clue")

  val baselineLines = List("gap\tjs\tFAIL")
  val baselineBytes = canonicalBytes(baselineLines)
  val baseline = parseBaselineLines(baselineLines).toOption.get
  val rosterLines = renderRoster(baselineBytes, List("gap", "stable")).linesIterator.toList
  val roster = parseRosterLines(rosterLines, baselineBytes, baseline).toOption.get
  check(roster == Set("gap", "stable"), "valid paired roster parses")
  val crlfBaselineLines = new String("gap\tjs\tFAIL\r\n".getBytes(Utf8), Utf8).linesIterator.toList
  val crlfRosterLines =
    renderRoster(baselineBytes, List("gap", "stable")).replace("\n", "\r\n").linesIterator.toList
  check(canonicalBytes(crlfBaselineLines).sameElements(baselineBytes) &&
    parseRosterLines(crlfRosterLines, canonicalBytes(crlfBaselineLines), baseline).isRight,
    "canonical hashes are checkout-EOL independent")
  check(
    parseRosterLines(rosterLines, canonicalBytes(List("gap\tjs\tTIMEOUT")), baseline).isLeft,
    "baseline digest mismatch is rejected")
  check(
    parseRosterLines(rosterLines.updated(2, "stable-renamed"), baselineBytes, baseline).isLeft,
    "roster body mutation is rejected")
  check(
    parseRosterLines(
      renderRoster(baselineBytes, List("stable", "gap")).linesIterator.toList,
      baselineBytes,
      baseline).left.exists(_.contains("not sorted")),
    "unsorted roster names are rejected")
  check(
    parseRosterLines(
      renderRoster(baselineBytes, List("gap", "gap", "stable")).linesIterator.toList,
      baselineBytes,
      baseline).left.exists(_.contains("duplicate")),
    "duplicate roster names are rejected")
  check(
    parseRosterLines(
      renderRoster(baselineBytes, List("stable")).linesIterator.toList,
      baselineBytes,
      baseline).left.exists(_.contains("absent")),
    "baseline name omitted from roster is rejected")
  check(
    parseBaselineLines(List("same\tjs\tFAIL", "same\tjs\tTIMEOUT")).left
      .exists(_.contains("multiple statuses")),
    "multiple baseline statuses for one cell are rejected")
  check(
    parseBaselineLines(List("bad\tbogus\tFAIL")).isLeft &&
      parseBaselineLines(List("bad\t js\tFAIL")).isLeft,
    "unknown or untrimmed baseline lanes are rejected")

  val current = Set("new-red\tint\tFAIL", "stable\tjs\tFAIL")
  val ran = Set("gap", "new-pass", "new-red", "stable")
  val observed = Set("gap" -> "js", "new-pass" -> "int", "new-red" -> "int", "stable" -> "js")
  val delta = contractDelta(current, baseline, roster, ran, observed, Some(ran))
  check(delta.newCases == List("new-pass", "new-red"), "new PASS and non-PASS cases stay visible")
  check(delta.newNonPass == List("new-red\tint\tFAIL"), "new red is NEW only")
  check(delta.regressions == List("stable\tjs\tFAIL"), "rostered new red is a regression")
  check(delta.statusChanges.isEmpty, "new red is not a status change")
  check(delta.improvements == List("gap\tjs\tFAIL"), "observed pass remains an improvement")
  check(delta.removedCases.isEmpty, "full current roster has no removals")
  val removed = contractDelta(Set.empty, baseline, roster, Set("stable"), Set("stable" -> "js"),
    Some(Set("stable")))
  check(removed.removedCases == List("gap"), "unfiltered shard reports a removed roster case")
  val subset = contractDelta(Set.empty, baseline, roster, Set("stable"), Set("stable" -> "js"), None)
  check(subset.removedCases.isEmpty, "--only subset never infers global removals")

  val changedBaseline = Set("changed\tjs\tFAIL")
  val changed = contractDelta(
    Set("changed\tjs\tDIVERGE"),
    changedBaseline,
    Set("changed"),
    Set("changed"),
    Set("changed" -> "js"),
    None)
  check(changed.statusChanges == List("changed\tjs\tFAIL → DIVERGE"), "status transition is CHANGE")
  check(changed.regressions.isEmpty && changed.improvements.isEmpty,
    "status transition is neither regression nor improvement")

  val knownRedChanged = contractDelta(
    Set("declared\tjs\tFAIL"),
    Set("declared\tjs\tKNOWN-RED"),
    Set("declared"),
    Set("declared"),
    Set("declared" -> "js"),
    None)
  check(knownRedChanged.statusChanges == List("declared\tjs\tKNOWN-RED → FAIL"),
    "KNOWN-RED transition stays a status change")
  check(knownRedChanged.improvements.isEmpty, "KNOWN-RED transition does not claim PASS")

  val unobserved = contractDelta(
    Set.empty,
    Set("hidden\tjs\tFAIL"),
    Set("hidden"),
    Set("hidden"),
    Set("hidden" -> "int"),
    None)
  check(unobserved.improvements.isEmpty, "backend-excluded lane is not an improvement")

  val skipBaseline = Set("formerly-skipped\t*\tSKIP")
  val skipNowFailing = contractDelta(
    Set("formerly-skipped\tjs\tFAIL"),
    skipBaseline,
    Set("formerly-skipped"),
    Set("formerly-skipped"),
    Set("formerly-skipped" -> "js"),
    None)
  check(skipNowFailing.regressions == List("formerly-skipped\tjs\tFAIL") &&
    skipNowFailing.improvements.isEmpty,
    "SKIP to runnable-but-failing does not claim PASS")
  val skipNowPassing = contractDelta(
    Set.empty,
    skipBaseline,
    Set("formerly-skipped"),
    Set("formerly-skipped"),
    Set("formerly-skipped" -> "js"),
    None)
  check(skipNowPassing.improvements == List("formerly-skipped\t*\tSKIP"),
    "SKIP to observed all-PASS is an improvement")
  val skipNoEligibleCells = contractDelta(
    Set.empty,
    skipBaseline,
    Set("formerly-skipped"),
    Set("formerly-skipped"),
    Set.empty,
    None)
  check(skipNoEligibleCells.improvements.isEmpty,
    "SKIP to zero eligible cells does not claim PASS")

  check(
    unsafeBaselineUpdateScopes(true, List("hello"), false, None, false, canonicalLanes, false, false)
      .exists(_.startsWith("--only")),
    "--only update is rejected")
  check(
    unsafeBaselineUpdateScopes(false, Nil, true, Some(0 -> 4), false, canonicalLanes, false, false)
      .exists(_.startsWith("--shard")),
    "--shard update is rejected")
  check(
    unsafeBaselineUpdateScopes(false, Nil, false, None, true, List("v2"), false, false)
      .exists(_.startsWith("--lanes")),
    "partial-lane update is rejected")
  check(
    unsafeBaselineUpdateScopes(false, Nil, false, None, true, canonicalLanes, false, false).isEmpty,
    "explicit canonical full update remains admitted")

  println(s"contract self-test: PASS ($checks checks)")

if cliArgs.contains("--self-test") then
  runSelfTest()
  System.exit(0)

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

val selected = allCases
  .filter(c => onlyGlobs.isEmpty || onlyGlobs.exists(g => globMatch(g, c.name)))
  .filterNot(c => isSkipped(c.name))
  // de-dupe by name (a name appearing in both corpora — conformance wins, it has a golden)
  .groupBy(_.name).values.map(cs => cs.sortBy(c => if c.corpus == "conf" then 0 else 1).head).toList
  .sortBy(_.name)

val cases = shard match
  case Some((i, n)) => selected.zipWithIndex.collect { case (c, idx) if idx % n == i => c }
  case None         => selected

// `--list` — print the selected case names and exit. Exists so a shard partition can be
// verified (disjoint + union == unsharded) against the REAL selection code instead of a
// re-implementation of it, which would only agree with itself.
if cliArgs.contains("--list") then
  cases.foreach(c => println(c.name))
  System.exit(0)

if cases.isEmpty then
  val scope =
    if onlyGlobs.nonEmpty then s" for --only ${onlyGlobs.mkString(",")}"
    else shard.map((i, n) => s" in --shard $i/$n").getOrElse("")
  cliFail(s"selected zero cases$scope; refusing a zero-evidence gate")

if !os.exists(sscBin) || !os.exists(sscToolsBin) then
  cliFail("bin/ssc or bin/ssc-tools not found — build first: bash install.sh --dev")

// Validate the two halves of the frozen observation BEFORE any lane runs. A
// missing/mismatched roster must fail in seconds, not after a 20-minute shard.
// A full update is the recovery/writer path, so it validates the newly built
// pair after serialization instead of requiring the old pair to be healthy.
val frozenContract: Option[FrozenContract] =
  if updateBaseline then None else Some(loadFrozenContract())

/** The case's YAML front-matter lines (between the first two `---`), or Nil. */
def frontmatter(src: String): List[String] =
  val lines = src.linesIterator.toList
  val startIdx = lines.indexWhere(_.trim == "---")
  if startIdx < 0 then Nil
  else
    val rest = lines.drop(startIdx + 1)
    val endIdx = rest.indexWhere(_.trim == "---")
    if endIdx < 0 then Nil else rest.take(endIdx).map(_.trim)

/** `known-red: <lane>[,<lane>] — <reason>` — a DECLARED, EXPIRING per-lane non-conformance,
 *  the same front-matter `run.sc` honours (`parseKnownRed` there; keep the two in step).
 *
 *  Without this the contract reports a red the project has explicitly declared — e.g.
 *  `int-width` on `js`, where the v1 codegen is documented as non-conforming for 64-bit Int
 *  and `specs/numeric-widths.md` §4 says NOT to fix it — as a REGRESSION. That is how a gate
 *  teaches people to ignore it, which is exactly how this one died the first time.
 *
 *  The lane still RUNS and is still DIFFED; only its bucket changes, to `KNOWN-RED`. The
 *  declaration expires by itself: if the lane starts PASSING, the entry drops out of `current`
 *  and the baseline's `KNOWN-RED` row surfaces as an IMPROVEMENT — i.e. "delete the
 *  declaration", which is precisely what should happen. */
def parseKnownRed(src: String): Map[String, String] =
  frontmatter(src).find(_.startsWith("known-red:")) match
    case None => Map.empty
    case Some(line) =>
      // The reason contains `: ` and `#`, so the YAML value is quoted — unquote it.
      val raw = line.stripPrefix("known-red:").trim
      val body =
        if raw.length >= 2 && raw.head == '"' && raw.last == '"' then raw.drop(1).dropRight(1)
        else if raw.length >= 2 && raw.head == '\'' && raw.last == '\'' then raw.drop(1).dropRight(1)
        else raw
      val (lanesPart, reason) = body.split("—", 2) match
        case Array(l, r) => (l.trim, r.trim)
        case _           => (body, "")
      if reason.isEmpty then
        System.err.println(s"[error] known-red without a reason: '$line' — a known-red MUST state " +
          "why it is red and when it expires, or it is indistinguishable from an unnoticed bug")
        System.exit(1)
      lanesPart.split(",").map(_.trim.toLowerCase).filter(_.nonEmpty).map(_ -> reason).toMap

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

/** `backend: <name>` — the singular front-matter key an EXAMPLE uses to declare which backend it
 *  targets. Distinct from `backends:` (the plural conformance-lane gate) and, until now, not read
 *  here at all: every case ran on every lane regardless of what it said about itself.
 *
 *  Measured 2026-07-28 on the full corpus: **10 of the 39 v2 non-PASS cases declared a non-v2
 *  target** — eight `backend: jvm` (they `derive` against HOST Scala typeclasses imported with
 *  `import scalascript.typeddata.…`, which the native lane does not link, so `TC_derived` is
 *  unbound) and two `backend: js`. Counting those against v2 says "v2 is broken" about programs
 *  that never claimed to run on v2. Honouring the key moves the honest v2 number from 39 to 29.
 *
 *  Mapping is deliberately CONSERVATIVE and fails OPEN — an unrecognised value gates nothing, so a
 *  typo or a new backend name can never silently drop a lane from the comparison. `spark` is the
 *  20-case majority and maps to no lane here, so it gates nothing either: those cases keep running
 *  exactly as before, which is the point of not guessing.
 *
 *  `int` is always kept: it is the golden source (`golden()` runs it when there is no expected
 *  file), so removing it would leave the case with nothing to diff against. */
def parseTargetBackend(src: String): Option[Set[String]] =
  frontmatter(src).find(_.startsWith("backend:")).flatMap { line =>
    line.stripPrefix("backend:").trim match
      case "jvm"                  => Some(Set("int", "jvm"))
      case "js"                   => Some(Set("int", "js"))
      case "v2" | "native"        => Some(Set("int", "v2"))
      case "int" | "interpreter"  => Some(Set("int"))
      case _                      => None   // unknown/other (e.g. `spark`) — gate nothing
  }

def laneCmd(lane: String, file: os.Path): Seq[String] = lane match
  case "int" => Seq(sscToolsBin.toString, "run", "--v1", file.toString)
  case "js"  => Seq(sscToolsBin.toString, "run-js", file.toString)
  case "jvm" => Seq(sscToolsBin.toString, "run-jvm", file.toString)
  // v2 lane = the exact STANDARD/NATIVE product command. StandardMain →
  // RunNativeV2 uses the native ssc1 frontend/checker and NativePluginHost. In the
  // default environment StandardMain passes bytecode=true, so direct ASM is primary
  // with side-effect-safe link-time VM fallback; `--interpret`/SSC_EXEC=vm is the
  // explicit VM override. `sscToolsBin run --v2` is not the retired bridge either:
  // FrontendBridge/RunV2 is gone, and the tools route now uses the same native front
  // with RunNativeV2(bytecode=false). It remains a different command/backend from the
  // production default this contract is meant to gate. Check the standard route with
  // `bin/ssc info --execution-plan --v2`. (v2-lane-is-native-tier.)
  case "v2"  => Seq(sscBin.toString, "run", "--v2", file.toString)
  case other => sys.error(s"unknown lane: $other")

// Run a lane with a hard timeout; returns (stdout, exitCode). 124 = timed out.
// Retries ONCE on timeout so parallel JVM contention (a normally-fast case pushed
// past the timeout) doesn't flap the gate — only a genuine hang times out twice.
def runLane(lane: String, file: os.Path, budgetS: Int = timeoutS): (String, Int) =
  def once(): (String, Int) =
    val cmd = Seq("timeout", budgetS.toString) ++ laneCmd(lane, file)
    // The launcher's stale-build check costs one `git rev-parse` (~11 ms); this harness spawns a
    // JVM per case per lane, so it opts out. In CI the tree is AT head and the check would never
    // fire anyway — the saving is the subprocess, not the warning.
    val r = os.proc(cmd).call(stdin = "", stderr = os.Pipe, check = false,
                              env = Map("SSC_NO_BUILD_CHECK" -> "1"))
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
  val src      = os.read(c.file)
  val gate     = parseBackends(src)
  val knownRed = parseKnownRed(src)
  golden(c) match
    case Left(reason) => (c.name, Left(reason))
    case Right(g) =>
      // Effective gate = the plural `backends:` conformance gate AND the singular `backend:`
      // target declaration. Both are optional; a case that declares neither runs on every lane, as
      // before.
      val target = parseTargetBackend(src)
      val row =
        for lane <- lanes if gate.forall(_.contains(lane)) && target.forall(_.contains(lane)) yield
          // For an expected-file golden we still diff INT; for a live golden INT == golden.
          if lane == "int" && c.expected.isEmpty then lane -> "PASS"
          else
            val (o, rc) = runLane(lane, c.file, laneTimeoutS)
            val st = classify(o, rc, g)
            // A declared known-red lane keeps running and diffing; only its bucket changes,
            // so it lands in the baseline as a documented red instead of a fake regression.
            // A known-red that PASSES stays PASS — the stale declaration then surfaces via
            // the IMPROVEMENT path, which is the expiry mechanism.
            if st != "PASS" && knownRed.contains(lane) then lane -> "KNOWN-RED"
            else lane -> st
      (c.name, Right(row.toMap))

val shardLabel = shard.map((i, n) => s" [shard $i/$n of ${selected.length}]").getOrElse("")
println(s"Corpus contract: ${cases.length} cases$shardLabel × lanes [${lanes.mkString(", ")}] " +
  s"(golden probe ${timeoutS}s, lane ${laneTimeoutS}s)")

// Bounded-by-default parallelism: each case runs `lanes` sequentially in its own
// worker, so at most `workers` subprocess JVMs are live at once. The computed
// default is capped at 4 to avoid contention-induced timeout flakes; an explicit
// positive `--workers` value deliberately overrides that cap for controlled hosts.
// A hung case only ties up its worker until the per-run timeout fires.
val workers = workersOverride
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

val passCount = statuses.values.map(_.values.count(_ == "PASS")).sum
val cellCount = statuses.values.map(_.size).sum
if cellCount == 0 && skips.isEmpty then
  cliFail(s"${cases.size} case(s) selected but no lane or SKIP cell was observed; " +
    "refusing a zero-evidence gate")

if updateBaseline then
  val baselineLines = current.toList
  val baselineText = canonicalText(baselineLines)
  val baselineBytes = canonicalBytes(baselineLines)
  val rosterNames = selected.map(_.name)
  val rosterText = renderRoster(baselineBytes, rosterNames)

  // Validate the canonical content in memory before replacing either half. The
  // dual digests make an interrupted two-file write fail closed on the next gate.
  val checkedBaseline = parseBaselineLines(baselineLines)
    .fold(e => throw new IllegalStateException(s"refusing to write invalid baseline: $e"), identity)
  parseRosterLines(rosterText.linesIterator.toList, baselineBytes, checkedBaseline)
    .fold(e => throw new IllegalStateException(s"refusing to write invalid roster: $e"), identity)

  os.write.over(baselineFile, baselineText)
  os.write.over(rosterFile, rosterText)
  println(s"\nWrote paired freeze: ${current.size} non-PASS entries → ${baselineFile.relativeTo(root)}")
  println(s"                     ${rosterNames.size} case names → ${rosterFile.relativeTo(root)}")
  System.exit(0)

// ── gate against the frozen baseline ─────────────────────────────────────────
val frozen = frozenContract.getOrElse(
  throw new IllegalStateException("frozen contract missing outside --update-baseline"))
val baseline = frozen.baseline
val roster   = frozen.roster

// Only compare cells that actually ran after each case's `backends:` filter.
// The classifier scopes wildcard SKIP rows separately: an old case-level SKIP
// improves only when the case now has eligible cells and every one passes.
val ranNames = (statuses.keySet ++ skips.keySet).toSet
val observedLaneKeys =
  statuses.iterator.flatMap { case (name, row) => row.keysIterator.map(lane => name -> lane) }.toSet

val fullSelectedNames =
  Option.when(onlyGlobs.isEmpty)(selected.map(_.name).toSet)
val delta = contractDelta(current.toSet, baseline, roster, ranNames, observedLaneKeys, fullSelectedNames)

val sep = "─" * 64
println(s"\n$sep")
println(s"  PASS cells: $passCount/$cellCount   SKIP cases: ${skips.size}   " +
  s"baseline: ${delta.scopedBaseline.size}   roster: ${roster.size}")
println(sep)

if delta.newCases.isEmpty && delta.regressions.isEmpty && delta.statusChanges.isEmpty &&
    delta.improvements.isEmpty && delta.removedCases.isEmpty then
  println("✓ contract GREEN — live matrix and case universe match the paired freeze.")
else
  if delta.newCases.nonEmpty then
    System.err.println(s"\n＋ ${delta.newCases.length} NEW CASE(S) absent from the frozen roster:")
    delta.newCases.foreach { name =>
      val rows = delta.newNonPass.filter(entryCase(_) == name)
      val observedCellCount = statuses.get(name).fold(0)(_.size)
      if rows.isEmpty && observedCellCount == 0 then
        System.err.println(s"    $name  (no eligible lane cells observed)")
      else if rows.isEmpty then System.err.println(s"    $name  (all observed cells PASS)")
      else rows.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
    }
    System.err.println("  → classify each new case, then refresh the paired freeze with one full run.")
  if delta.regressions.nonEmpty then
    System.err.println(s"\n✗ ${delta.regressions.length} REGRESSION row(s) in rostered cases:")
    delta.regressions.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
  if delta.statusChanges.nonEmpty then
    System.err.println(s"\n↕ ${delta.statusChanges.length} NON-PASS STATUS CHANGE(S):")
    delta.statusChanges.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
  if delta.removedCases.nonEmpty then
    System.err.println(s"\n− ${delta.removedCases.length} REMOVED/RENAMED/SKIPPED roster case(s):")
    delta.removedCases.foreach(name => System.err.println("    " + name))
    System.err.println("  → confirm the coverage removal, then refresh the paired freeze.")
  if delta.improvements.nonEmpty then
    System.err.println(s"\n△ ${delta.improvements.length} IMPROVEMENT(S)/stale baseline — now PASS, still in baseline:")
    delta.improvements.foreach(e => System.err.println("    " + e.replace("\t", "  ")))
    System.err.println("  → re-run with --update-baseline to record the closed gaps.")
    if delta.improvements.exists(_.contains("KNOWN-RED")) then
      System.err.println("  → a KNOWN-RED entry here means the declaration EXPIRED: that lane now " +
        "passes, so DELETE the case's `known-red:` front-matter (do not just re-baseline it).")
  System.exit(1)

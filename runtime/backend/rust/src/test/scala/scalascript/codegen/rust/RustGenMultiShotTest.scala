package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** R.6 Slice 1 — Tier-1 multi-shot (List monad) effects on Rust (spec `rust-effects.md §11`).
 *  A `multi effect` handled by `opts.flatMap(opt => resume(opt))` over a straight-line effectful def
 *  lowers to **nested `for` loops + a `Vec` accumulator** — no continuations, no `Value`, typed,
 *  stack-safe. Codegen golden + an `assume(cargo)` end-to-end run (== JVM/JS). */
class RustGenMultiShotTest extends AnyFunSuite:

  private val opts = BackendOptions(
    baseDir = None, outputDir = None, optimizationLevel = 0,
    emitSourceMaps = false, emitAssertions = false, target = None,
    extra = Map("binName" -> "smoke"))

  private val nonDetSrc =
    """```scalascript
      |multi effect NonDet:
      |  def choose(options: List[Int]): Int
      |
      |def program(seed: Int): Int ! NonDet =
      |  val a = NonDet.choose(List(1, 2, 3))
      |  val b = NonDet.choose(List(10, 20))
      |  a + b + seed
      |
      |@main def run(): Unit =
      |  val all = handle(program(0)) {
      |    case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      |  }
      |  println(all.foldLeft(0)((acc, x) => acc + x))
      |```
      |""".stripMargin

  private def rustOf(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), opts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) if n.endsWith(".rs") => new String(b, "UTF-8") }.mkString("\n")
      case other => fail(s"expected Segmented, got $other")

  test("multi-shot NonDet handle lowers to nested for-loops + a Vec accumulator (no continuations)"):
    val rs = rustOf(nonDetSrc)
    assert(rs.contains("let mut __ms_acc = Vec::new();"), rs)
    assert(rs.contains("for a in vec![1i64, 2i64, 3i64] {"), rs)
    assert(rs.contains("for b in vec![10i64, 20i64] {"), rs)
    assert(rs.contains("__ms_acc.push(((a + b) + seed));"), rs)
    // the one-shot tagless-final handler struct must NOT be emitted for a multi-shot handle
    assert(!rs.contains("struct __H_NonDet"), "multi-shot must not use the one-shot handler-struct path")

  // ── end-to-end ──────────────────────────────────────────────────────────
  private def cargoAvailable: Boolean =
    try os.proc("cargo", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  test("multi-shot NonDet runs end-to-end via cargo and matches the cross-product sum (== JVM/JS)"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    val assets = new RustBackend().compile(Normalize(Parser.parse(nonDetSrc)), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    val crateDir = os.temp.dir(prefix = "ssc-rust-ms-")
    try
      for a <- assets do
        val out = crateDir / os.RelPath(a.name)
        os.makeDir.all(out / os.up); os.write.over(out, a.bytes)
      val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
      if res.exitCode != 0 then
        fail(s"cargo run failed (exit ${res.exitCode}):\n${res.err.text()}")
      // [1,2,3] × [10,20] + 0 → 11+21+12+22+13+23 = 102
      assert(res.out.text().trim == "102", s"got: ${res.out.text().trim}")
    finally os.remove.all(crateDir)

  test("multi-shot handle inside a while-loop + foldLeft runs end-to-end via cargo"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    // Like the effect-multishot bench (handle in a loop, fold the branches) but WITHOUT the bench's
    // LCG — its `s * 2862933555777941757` overflows i64 and Rust debug-panics (JVM/JS Long wraps).
    // That Long-wrapping-arithmetic gap is orthogonal to multi-shot (see BACKLOG). Here:
    // per iter i, all = [a+b+i] over [1,2,3]×[10,20] → sum 102 + 6*i; i=0,1,2 → 102+108+114 = 324.
    val loopSrc =
      """```scalascript
        |multi effect NonDet:
        |  def choose(options: List[Int]): Int
        |
        |def program(seed: Int): Int ! NonDet =
        |  val a = NonDet.choose(List(1, 2, 3))
        |  val b = NonDet.choose(List(10, 20))
        |  a + b + seed
        |
        |def workload(n: Int): Long =
        |  var total = 0L
        |  var i = 0
        |  while i < n do
        |    val all = handle(program(i)) {
        |      case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
        |    }
        |    total = total + all.foldLeft(0L)((acc, x) => acc + x.toLong)
        |    i = i + 1
        |  total
        |
        |@main def run(): Unit = println(workload(3))
        |```
        |""".stripMargin
    val assets = new RustBackend().compile(Normalize(Parser.parse(loopSrc)), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    val crateDir = os.temp.dir(prefix = "ssc-rust-msloop-")
    try
      for a <- assets do
        val out = crateDir / os.RelPath(a.name)
        os.makeDir.all(out / os.up); os.write.over(out, a.bytes)
      val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
      if res.exitCode != 0 then fail(s"cargo run failed (exit ${res.exitCode}):\n${res.err.text()}")
      assert(res.out.text().trim == "324", s"got: ${res.out.text().trim}")
    finally os.remove.all(crateDir)

  test("the real effect-multishot bench (LCG + multi-shot) runs on rust — n/a → real number"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    // The actual bench workload: its LCG `s * 2862933555777941757` overflows i64; with
    // `overflow-checks = false` (ScalaScript Long wraps, like JVM/JS) it no longer debug-panics.
    val benchSrc =
      """```scalascript
        |multi effect NonDet:
        |  def choose(options: List[Int]): Int
        |
        |def program(seed: Long): Int ! NonDet =
        |  val a = NonDet.choose(List(1, 2, 3))
        |  val b = NonDet.choose(List(10, 20))
        |  a + b + (seed % 5).toInt
        |
        |def workload(seed: Long): Long =
        |  var s = seed + 1
        |  var total = 0L
        |  var i = 0
        |  while i < 100 do
        |    s = s * 2862933555777941757L + 3037000493L
        |    val all = handle(program(s)) {
        |      case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
        |    }
        |    total = total + all.foldLeft(0L)((acc, x) => acc + x.toLong)
        |    i = i + 1
        |  total
        |
        |@main def run(): Unit = println(workload(7))
        |```
        |""".stripMargin
    val assets = new RustBackend().compile(Normalize(Parser.parse(benchSrc)), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    val crateDir = os.temp.dir(prefix = "ssc-rust-msbench-")
    try
      for a <- assets do
        val out = crateDir / os.RelPath(a.name)
        os.makeDir.all(out / os.up); os.write.over(out, a.bytes)
      val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
      if res.exitCode != 0 then fail(s"cargo run failed (exit ${res.exitCode}):\n${res.err.text()}")
      assert(res.out.text().trim.toLongOption.isDefined, s"expected an integer, got: ${res.out.text().trim}")
    finally os.remove.all(crateDir)

  // ── Tier-1 Option monad (Slice 2) + Option consumption (rust-option-consumption) ──────────
  private def maybeSrc(get1: String, get2: String): String =
    s"""```scalascript
       |multi effect Maybe:
       |  def get(o: Option[Int]): Int
       |
       |def program(): Int ! Maybe =
       |  val a = Maybe.get($get1)
       |  val b = Maybe.get($get2)
       |  a + b
       |
       |@main def run(): Unit =
       |  val r = handle(program()) {
       |    case Maybe.get(o, resume) => o.flatMap(v => resume(v))
       |  }
       |  r match
       |    case Some(x) => println(x)
       |    case None    => println(-1)
       |```
       |""".stripMargin

  test("multi-shot Option (Maybe) handle lowers to nested if-let Some / else None (Slice 2)"):
    val rs = rustOf(maybeSrc("Some(5)", "Some(7)"))
    assert(rs.contains("if let Some(a) ="), rs)
    assert(rs.contains("if let Some(b) ="), rs)
    assert(rs.contains("} else { None }"), rs)
    assert(rs.contains("Some((a + b))"), rs)
    assert(!rs.contains("__ms_acc"), "Option monad must not use the List Vec-accumulator path")

  test("Option.getOrElse lowers to unwrap_or (rust-option-consumption)"):
    val rs = rustOf("""```scalascript
      |def f(o: Option[Int]): Int = o.getOrElse(-1)
      |```""".stripMargin)
    assert(rs.contains(".unwrap_or("), rs)

  private def runMaybe(get1: String, get2: String): String =
    val assets = new RustBackend().compile(Normalize(Parser.parse(maybeSrc(get1, get2))), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    val crateDir = os.temp.dir(prefix = "ssc-rust-maybe-")
    try
      for a <- assets do
        val out = crateDir / os.RelPath(a.name); os.makeDir.all(out / os.up); os.write.over(out, a.bytes)
      val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
      if res.exitCode != 0 then fail(s"cargo run failed (exit ${res.exitCode}):\n${res.err.text()}")
      res.out.text().trim
    finally os.remove.all(crateDir)

  test("multi-shot Option runs end-to-end via cargo: Some-chain → sum, a None → short-circuit"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    assert(runMaybe("Some(5)", "Some(7)") == "12", "Some(5)+Some(7) → Some(12) → 12")
    assert(runMaybe("Some(5)", "None")    == "-1", "a None short-circuits → None → -1")

  // ── Tier-2 multi-shot (general, static depth) — handler as `fn __h` + nested closures (§11.2) ──
  private def ambSrc(flips: String, tail: String): String =
    s"""```scalascript
       |multi effect Amb:
       |  def flip(): Boolean
       |
       |def program(): Int ! Amb =
       |$flips
       |  $tail
       |
       |@main def run(): Unit =
       |  val r = handle(program()) {
       |    case Amb.flip(resume) => resume(true) + resume(false)
       |  }
       |  println(r)
       |```
       |""".stripMargin

  private val amb1 = ambSrc("  val x = Amb.flip()", "if x then 1 else 0")
  // two nested performs: sum over x,y ∈ {T,F} of (x?1:0)+(y?10:0) = 11+1+10+0 = 22
  private val amb2 = ambSrc("  val x = Amb.flip()\n  val y = Amb.flip()",
                            "(if x then 1 else 0) + (if y then 10 else 0)")

  private def runRust(src: String): String =
    val assets = new RustBackend().compile(Normalize(Parser.parse(src)), opts) match
      case CompileResult.Segmented(segs) => segs.collect { case a: Segment.Asset => a }
      case other                         => fail(s"expected Segmented, got $other")
    val crateDir = os.temp.dir(prefix = "ssc-rust-amb-")
    try
      for a <- assets do
        val out = crateDir / os.RelPath(a.name); os.makeDir.all(out / os.up); os.write.over(out, a.bytes)
      val res = os.proc("cargo", "run", "--quiet").call(cwd = crateDir, check = false)
      if res.exitCode != 0 then fail(s"cargo run failed (exit ${res.exitCode}):\n${res.err.text()}")
      res.out.text().trim
    finally os.remove.all(crateDir)

  test("Tier-2 multi-shot emits the handler as a nested fn + reifies each continuation (Amb)"):
    val rs = rustOf(amb1)
    assert(rs.contains("fn __h(__k: &dyn Fn(bool) -> i64) -> i64 {"), rs)
    assert(rs.contains("__k(true)") && rs.contains("__k(false)"), rs)  // handler resumes both ways
    assert(rs.contains("__h(&|x: bool|"), rs)                          // perform → nested __h call
    assert(!rs.contains("struct __H_Amb"), "Tier-2 must not use the one-shot handler-struct path")

  test("Tier-2 Amb (1 flip) runs end-to-end via cargo: resume(true)+resume(false) = 1+0 = 1"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    assert(runRust(amb1) == "1", "1 flip → 1")

  test("Tier-2 multi-shot handles NESTED performs (2 flips) end-to-end via cargo → 22"):
    assume(cargoAvailable, "cargo not on PATH — skipping end-to-end Rust smoke")
    val rs = rustOf(amb2)
    assert(rs.contains("__h(&|x: bool|") && rs.contains("__h(&|y: bool|"), rs)  // nested __h calls
    assert(runRust(amb2) == "22", "sum over x,y∈{T,F} of (x?1:0)+(y?10:0) = 22")

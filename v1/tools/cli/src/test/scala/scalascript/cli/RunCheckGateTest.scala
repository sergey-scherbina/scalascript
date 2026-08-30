package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** run-gated-by-check — the pre-run type gate (owner decision 2026-08-30, option (a)).
 *
 *  In-process tests of the gate's own function, [[preRunTypeErrors]], plus the bypass
 *  predicate. The end-to-end contract (exit codes, stderr wording, `--no-check` reaching
 *  the old runtime failure) is exercised by the same repro through the launcher in the
 *  landing verification; these keep the LOGIC pinned where `sbt cli/test` always runs:
 *
 *    1. the Dog/Cat soundness repro (v2/BUGS.md
 *       trait-typed-parameter-accepts-a-non-conforming-argument) is refused, and the
 *       refusal names the mismatch;
 *    2. a clean file produces no refusals;
 *    3. a file that does not PARSE produces no refusals here — the runner's own parse
 *       diagnostics predate the gate and stay authoritative;
 *    4. a missing file produces no refusals here — same reason;
 *    5. warnings never refuse: `tests/conformance/lib/eff-a.ssc` (the `[effect-verifier]`
 *       advisory that used to be wrapped as a hard error) passes the gate;
 *    6. the bypass predicate honours the flag (the SSC_NO_CHECK env half is a plain
 *       `sys.env` read, not overridable in-process — covered by the launcher check).
 */
class RunCheckGateTest extends AnyFunSuite:

  private def tmpSsc(body: String): os.Path =
    val f = os.temp(contents = body, suffix = ".ssc")
    f

  private val dogCat =
    """trait Animal:
      |  def sound: String
      |
      |trait Dog extends Animal:
      |  def sound: String = "woof"
      |  def fetch: String = "fetch!"
      |
      |class Cat() extends Animal:
      |  def sound: String = "meow"
      |
      |def needsDog(d: Dog): String = d.fetch
      |
      |println(needsDog(Cat()))
      |""".stripMargin

  test("the Dog/Cat soundness repro is refused, naming the mismatch"):
    val f = tmpSsc(dogCat)
    val errs = preRunTypeErrors(List(f.toString))
    assert(errs.nonEmpty, "expected the gate to refuse needsDog(Cat())")
    assert(errs.exists(_._2.msg.contains("expected Dog, found Cat")),
      s"expected the Typer's own mismatch message, got: ${errs.map(_._2.msg)}")

  test("a clean file produces no refusals"):
    val f = tmpSsc("println(1 + 1)\n")
    assert(preRunTypeErrors(List(f.toString)).isEmpty)

  test("a file that does not parse is NOT refused by the gate (runner's own diagnostics own it)"):
    val f = tmpSsc("```scalascript\nval broken = (((\n```\n")
    assert(preRunTypeErrors(List(f.toString)).isEmpty)

  test("a missing file is NOT refused by the gate"):
    assert(preRunTypeErrors(List("/nonexistent/definitely-not-here.ssc")).isEmpty)

  test("warnings never refuse: the effect-verifier advisory file passes"):
    val repoRoot =
      var cur = os.pwd
      while !(os.exists(cur / "build.sbt") && os.exists(cur / "tests")) && cur != (cur / os.up) do
        cur = cur / os.up
      cur
    val effA = repoRoot / "tests" / "conformance" / "lib" / "eff-a.ssc"
    assume(os.exists(effA), s"fixture moved: $effA")
    assert(preRunTypeErrors(List(effA.toString)).isEmpty,
      "the [effect-verifier] advisory must be a warning, not a run-blocking error")

  test("the bypass predicate honours the flag"):
    assert(runCheckBypassRequested(noCheckFlag = true))

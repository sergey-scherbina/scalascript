package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 Phase 3 — end-to-end smoke tests for `ssc verify <artifact-dir>`.
 *
 *  Covers the operational health-check command that walks every v2.0
 *  artifact in a directory and reports envelope / sourceHash / cross-ref /
 *  runtime-coverage status.  The command is purely read-only; tests confirm
 *  exit-code semantics for both clean and corrupted artifact sets.
 *
 *  Tests spawn the actual `ssc` jar as a subprocess (same pattern as
 *  `InfoCliTest` / `V2ArtifactCliTest`).  When the jar is missing the tests
 *  cancel with a diagnostic.
 *
 *  Run with:  `sbt "cli/testOnly *Verify*"`
 */
class VerifyCliTest extends AnyFunSuite:

  // ── ssc jar discovery (mirrors InfoCliTest) ──────────────────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd    = cwd,
      check  = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  // Module `a.ssc` — single `add` definition, no imports.
  private val aSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |```
      |""".stripMargin

  // Module `b.ssc` — imports `a` and uses `add`.
  private val bSsc: String =
    """---
      |name: b
      |dependencies:
      |  a: ./a.ssc
      |---
      |
      |# Module B
      |
      |```scalascript
      |def bump(x: Int): Int = x + 1
      |```
      |""".stripMargin

  /** Build `a.ssc` + `b.ssc` into a sandbox with .scim + .scjvm artifacts.
   *  Returns the artifact dir (the sandbox itself — `compile-jvm` writes
   *  alongside the source by default). */
  private def buildAB(sandbox: os.Path): os.Path =
    os.write(sandbox / "a.ssc", aSsc)
    os.write(sandbox / "b.ssc", bSsc)
    assert(runSsc(sandbox, "emit-interface", "a.ssc").exitCode == 0)
    assert(runSsc(sandbox, "emit-interface", "b.ssc").exitCode == 0)
    assert(runSsc(sandbox, "compile-jvm",    "a.ssc").exitCode == 0)
    assert(runSsc(sandbox, "compile-jvm",    "b.ssc").exitCode == 0)
    sandbox

  // ── 1. empty dir ─────────────────────────────────────────────────────────

  test("verify empty dir — exit 0, output mentions 0 artifacts"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-empty-")
    try
      val res = runSsc(sandbox, "verify", sandbox.toString)
      assert(res.exitCode == 0,
        s"empty-dir verify should exit 0; got ${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("0 artifacts"),
        s"expected '0 artifacts' in output:\n$out")
      assert(out.contains("0 OK, 0 WARN, 0 FAIL"),
        s"expected summary line in output:\n$out")
    finally os.remove.all(sandbox)

  // ── 2. clean two-module dir ──────────────────────────────────────────────

  test("verify clean two-module dir — exit 0, all OK"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-clean-")
    try
      buildAB(sandbox)
      val res = runSsc(sandbox, "verify", sandbox.toString)
      assert(res.exitCode == 0,
        s"clean verify should exit 0; got ${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("[OK] a.scim"),  s"expected OK row for a.scim:\n$out")
      assert(out.contains("[OK] b.scim"),  s"expected OK row for b.scim:\n$out")
      assert(out.contains("[OK] a.scjvm"), s"expected OK row for a.scjvm:\n$out")
      assert(out.contains("[OK] b.scjvm"), s"expected OK row for b.scjvm:\n$out")
      assert(out.contains("4 OK"),         s"expected '4 OK' in summary:\n$out")
    finally os.remove.all(sandbox)

  // ── 3. hand-corrupt one .scim's magic field ──────────────────────────────

  test("verify with corrupted .scim magic — exit 1, FAIL row mentions file"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-corrupt-")
    try
      buildAB(sandbox)
      val scim = sandbox / "a.scim"
      val json = os.read(scim)
      val corrupted = json.replace("\"SSCART\"", "\"NOTSSC\"")
      os.write.over(scim, corrupted)

      val res = runSsc(sandbox, "verify", sandbox.toString)
      assert(res.exitCode == 1,
        s"corrupted verify should exit 1; got ${res.exitCode}\nstdout=${res.out.text()}")
      val out = res.out.text()
      assert(out.contains("[FAIL] a.scim"),
        s"expected FAIL row for a.scim:\n$out")
      assert(out.contains("1 FAIL"),
        s"expected '1 FAIL' in summary:\n$out")
    finally os.remove.all(sandbox)

  // ── 4. source touched — exit 0 without --strict, 1 with --strict ──────────

  test("verify after source touch — clean without --strict, FAIL with --strict"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-touch-")
    try
      buildAB(sandbox)
      // Append a stray comment so the source bytes change but the parse
      // still succeeds.
      os.write.append(sandbox / "a.ssc", "\n\n<!-- stray comment -->\n")

      val resNoStrict = runSsc(sandbox, "verify", sandbox.toString)
      assert(resNoStrict.exitCode == 0,
        s"non-strict verify should still exit 0; got ${resNoStrict.exitCode}\n${resNoStrict.out.text()}")

      val resStrict = runSsc(sandbox, "verify", sandbox.toString,
        "--src-dir", sandbox.toString, "--strict")
      assert(resStrict.exitCode == 1,
        s"--strict verify after touch should exit 1; got ${resStrict.exitCode}\n${resStrict.out.text()}")
      val out = resStrict.out.text()
      assert(out.contains("sourceHash mismatch"),
        s"expected 'sourceHash mismatch' diagnostic:\n$out")
    finally os.remove.all(sandbox)

  // ── 5. .scjvm modules but no _runtime — runtime coverage warning ─────────

  test("verify with .scjvm modules declaring caps but no runtime — WARN (or FAIL with --strict)"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-noruntime-")
    try
      // We can't easily produce a .scjvm with non-empty capabilities without
      // `--bytecode` (scala-cli is heavy).  Instead, hand-build a
      // .scjvm-shaped artifact carrying a capability we know never matches
      // a runtime — that triggers the coverage WARN path directly.
      val moduleId   = "a"
      val sourceHash = "0".repeat(64)  // valid hex shape — bypasses shape check
      val scalaSource = "object a { def add(x: Int, y: Int): Int = x + y }\n"
      val jvmArt = scalascript.ir.ModuleJvmArtifact(
        magic       = "SSCART",
        abiVersion  = scalascript.ir.ArtifactVersion.current,
        moduleId    = moduleId,
        pkg         = Nil,
        moduleName  = Some(moduleId),
        sourceHash  = sourceHash,
        scalaSource = scalaSource,
        imports     = Nil,
        classBundle = None,
        capabilities = List("effects")  // forces a runtime requirement
      )
      val json = scalascript.artifact.JvmArtifactIO.writeJvm(jvmArt)
      os.write(sandbox / "a.scjvm", json)

      val res = runSsc(sandbox, "verify", sandbox.toString)
      assert(res.exitCode == 0,
        s"non-strict should still exit 0 on WARN; got ${res.exitCode}\n${res.out.text()}")
      val out = res.out.text()
      assert(out.contains("WARN"),
        s"expected a WARN row in output:\n$out")
      assert(out.toLowerCase.contains("runtime") && out.toLowerCase.contains("missing"),
        s"expected a runtime-missing diagnostic:\n$out")

      // With --strict the same WARN should fail the exit code.
      val resStrict = runSsc(sandbox, "verify", sandbox.toString, "--strict")
      assert(resStrict.exitCode == 1,
        s"--strict should fail on WARN; got ${resStrict.exitCode}\n${resStrict.out.text()}")
    finally os.remove.all(sandbox)

  // ── 6. --json output is parseable ────────────────────────────────────────

  test("verify --json — parseable JSON with the expected schema"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-json-")
    try
      buildAB(sandbox)
      val res = runSsc(sandbox, "verify", sandbox.toString, "--json")
      assert(res.exitCode == 0,
        s"verify --json should exit 0; got ${res.exitCode}\n${res.err.text()}")
      val out = res.out.text().trim
      val json = upickleRead[JsonValue](out)
      assert(json("dir").str.nonEmpty,
        s"missing or empty 'dir' field:\n$out")
      val artifacts = json("artifacts").arr
      assert(artifacts.length == 4,
        s"expected 4 artifacts (a/b × .scim/.scjvm); got ${artifacts.length}:\n$out")
      val statuses = artifacts.map(_("status").str).toSet
      assert(statuses == Set("ok"),
        s"expected all status=ok; got $statuses")
      val sum = json("summary")
      assert(sum("ok").num.toInt == 4,  s"expected summary.ok=4; got ${sum("ok")}")
      assert(sum("warn").num.toInt == 0, s"expected summary.warn=0; got ${sum("warn")}")
      assert(sum("fail").num.toInt == 0, s"expected summary.fail=0; got ${sum("fail")}")
      // Per-row schema: path/format/status/summary always present.
      artifacts.foreach { a =>
        assert(a("path").str.nonEmpty)
        assert(a("format").str.nonEmpty)
        assert(a("status").str.nonEmpty)
        assert(a("summary").str.nonEmpty)
      }
    finally os.remove.all(sandbox)

  // ── 7. cross-ref: declared import missing on disk ────────────────────────

  test("verify with .scjvm declaring a missing import — exit 1, FAIL row mentions missing"):
    val sandbox = os.temp.dir(prefix = "ssc-verify-xref-")
    try
      // Hand-build a .scjvm referencing 'missing_dep' as an import while
      // no `missing_dep.scjvm` exists in the dir.  The shape check passes
      // (valid envelope + hex sourceHash); only the cross-ref test fires.
      val sourceHash = "f".repeat(64)
      val jvmArt = scalascript.ir.ModuleJvmArtifact(
        magic        = "SSCART",
        abiVersion   = scalascript.ir.ArtifactVersion.current,
        moduleId     = "b",
        pkg          = Nil,
        moduleName   = Some("b"),
        sourceHash   = sourceHash,
        scalaSource  = "object b { def use(): Int = missing_dep.x }\n",
        imports      = List("missing_dep"),
        classBundle  = None,
        capabilities = Nil
      )
      os.write(sandbox / "b.scjvm",
        scalascript.artifact.JvmArtifactIO.writeJvm(jvmArt))

      val res = runSsc(sandbox, "verify", sandbox.toString)
      assert(res.exitCode == 1,
        s"missing cross-ref should exit 1; got ${res.exitCode}\n${res.out.text()}")
      val out = res.out.text()
      assert(out.contains("FAIL") && out.contains("b.scjvm"),
        s"expected FAIL row for b.scjvm:\n$out")
      assert(out.toLowerCase.contains("missing"),
        s"expected 'missing' diagnostic:\n$out")
    finally os.remove.all(sandbox)

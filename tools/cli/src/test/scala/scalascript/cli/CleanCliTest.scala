package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 3 follow-up — tests for `ssc clean <artifact-dir>`.
 *
 *  Cover the four documented modes:
 *
 *   1. Default GC — only artifacts whose source `.ssc` was deleted go.
 *   2. `--dry-run` — no FS mutation, but the list is printed.
 *   3. `--all` — full wipe (incl. runtime artifacts).
 *   4. Idempotency — second run on the same state is a no-op.
 *
 *  Tests build the artifact dir by hand with stub JSON envelopes,
 *  not by running `compile-jvm` — the `clean` command only cares
 *  about filenames + the source `.ssc`'s presence, not artifact
 *  contents.  This keeps the suite fast (no scala-cli dep).
 *
 *  Run with: `sbt "cli/testOnly *CleanCliTest*"`
 */
class CleanCliTest extends AnyFunSuite:

  // ── ssc.jar discovery (mirrors JvmBytecodeLinkCliTest) ──────────────────

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
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  /** Lay out a workspace with `src/<modules>.ssc` and
   *  `artifacts/<modules>.scjvm` + matching `.scim` + a stale
   *  `_runtime.scjvm-runtime`.  Returns (sandbox, artDir, srcDir).
   *
   *  We write artifact files as raw text — the CLI's clean command
   *  doesn't parse them, only their filenames + the existence of the
   *  source `.ssc` in `srcDir`. */
  private def setupFixture(sandbox: os.Path, modulesWithSrc: List[String], modulesWithoutSrc: List[String]): (os.Path, os.Path) =
    val srcDir = sandbox / "src"
    val artDir = sandbox / "src" / "artifacts"
    os.makeDir.all(srcDir)
    os.makeDir.all(artDir)
    // Modules with .ssc present.
    for m <- modulesWithSrc do
      os.write(srcDir / s"$m.ssc",
        s"""---
           |name: $m
           |---
           |
           |# Module $m
           |
           |```scalascript
           |def $m: Int = 1
           |```
           |""".stripMargin)
      os.write(artDir / s"$m.scjvm", s"""{"magic":"SSCART","abiVersion":"2.0","moduleId":"$m"}""")
      os.write(artDir / s"$m.scim",  s"""{"magic":"SSCART","abiVersion":"2.0","moduleId":"$m"}""")
    // Modules without .ssc.
    for m <- modulesWithoutSrc do
      os.write(artDir / s"$m.scjvm", s"""{"magic":"SSCART","abiVersion":"2.0","moduleId":"$m"}""")
      os.write(artDir / s"$m.scim",  s"""{"magic":"SSCART","abiVersion":"2.0","moduleId":"$m"}""")
    // A shared runtime artifact (never tied to a single source).
    os.write(artDir / "_runtime.scjvm-runtime",
      """{"magic":"SSCART","abiVersion":"2.0","capabilities":[],"classBundle":""}""")
    (artDir, srcDir)

  // ── 1. Default GC — only stale artifacts go ─────────────────────────────

  test("clean removes artifacts whose source .ssc no longer exists"):
    val sandbox = os.temp.dir(prefix = "ssc-clean-")
    try
      val (artDir, _) = setupFixture(sandbox,
        modulesWithSrc    = List("a", "b"),
        modulesWithoutSrc = List("c"))

      val res = runSsc(sandbox, "clean", artDir.toString)
      assert(res.exitCode == 0,
        s"clean failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      // c.scjvm + c.scim should be gone; a/b artifacts and the runtime
      // should remain.
      assert(!os.exists(artDir / "c.scjvm"), "expected c.scjvm to be removed")
      assert(!os.exists(artDir / "c.scim"),  "expected c.scim to be removed")
      assert( os.exists(artDir / "a.scjvm"), "expected a.scjvm to survive")
      assert( os.exists(artDir / "a.scim"),  "expected a.scim to survive")
      assert( os.exists(artDir / "b.scjvm"), "expected b.scjvm to survive")
      assert( os.exists(artDir / "b.scim"),  "expected b.scim to survive")
      assert( os.exists(artDir / "_runtime.scjvm-runtime"),
        "expected _runtime.scjvm-runtime to survive (only --all removes it)")

      val out = res.out.text()
      assert(out.contains("REMOVE c.scjvm"), s"expected REMOVE line for c.scjvm; got:\n$out")
      assert(out.contains("REMOVE c.scim"),  s"expected REMOVE line for c.scim; got:\n$out")
      assert(out.contains("removed 2"),      s"expected 'removed 2' in summary; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 2. --dry-run doesn't mutate the FS ───────────────────────────────────

  test("clean --dry-run prints the action list but removes nothing"):
    val sandbox = os.temp.dir(prefix = "ssc-clean-")
    try
      val (artDir, _) = setupFixture(sandbox,
        modulesWithSrc    = List("a"),
        modulesWithoutSrc = List("c", "d"))

      val res = runSsc(sandbox, "clean", artDir.toString, "--dry-run")
      assert(res.exitCode == 0, s"clean --dry-run failed: ${res.err.text()}")

      // Nothing was actually deleted.
      assert(os.exists(artDir / "c.scjvm"),  "expected c.scjvm to survive dry-run")
      assert(os.exists(artDir / "c.scim"),   "expected c.scim  to survive dry-run")
      assert(os.exists(artDir / "d.scjvm"),  "expected d.scjvm to survive dry-run")
      assert(os.exists(artDir / "a.scjvm"),  "expected a.scjvm to survive dry-run")

      val out = res.out.text()
      assert(out.contains("DRY-RUN c.scjvm"), s"expected DRY-RUN line for c.scjvm; got:\n$out")
      assert(out.contains("DRY-RUN d.scjvm"), s"expected DRY-RUN line for d.scjvm; got:\n$out")
      assert(out.contains("would remove 4"),  s"expected 'would remove 4' (c+d × scjvm/scim); got:\n$out")
      // And NO actual REMOVE lines.
      assert(!out.contains("REMOVE c."),
        s"--dry-run should not print REMOVE (only DRY-RUN); got:\n$out")
    finally os.remove.all(sandbox)

  // ── 3. --all wipes everything (incl. runtime) ────────────────────────────

  test("clean --all removes every v2.0 artifact regardless of source presence"):
    val sandbox = os.temp.dir(prefix = "ssc-clean-")
    try
      val (artDir, _) = setupFixture(sandbox,
        modulesWithSrc    = List("a", "b"),
        modulesWithoutSrc = List("c"))

      val res = runSsc(sandbox, "clean", artDir.toString, "--all")
      assert(res.exitCode == 0, s"clean --all failed: ${res.err.text()}")

      assert(!os.exists(artDir / "a.scjvm"))
      assert(!os.exists(artDir / "a.scim"))
      assert(!os.exists(artDir / "b.scjvm"))
      assert(!os.exists(artDir / "b.scim"))
      assert(!os.exists(artDir / "c.scjvm"))
      assert(!os.exists(artDir / "c.scim"))
      assert(!os.exists(artDir / "_runtime.scjvm-runtime"),
        "expected the shared runtime artifact to be wiped under --all")

      val out = res.out.text()
      // 2 module ids × 2 exts + 1 runtime = 7 removals.
      assert(out.contains("removed 7"), s"expected 'removed 7' in summary; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 4. Idempotency — running twice has no further effect ────────────────

  test("clean is idempotent — running twice on the same state removes nothing on the second pass"):
    val sandbox = os.temp.dir(prefix = "ssc-clean-")
    try
      val (artDir, _) = setupFixture(sandbox,
        modulesWithSrc    = List("a"),
        modulesWithoutSrc = List("c"))

      val r1 = runSsc(sandbox, "clean", artDir.toString)
      assert(r1.exitCode == 0)
      assert(!os.exists(artDir / "c.scjvm"), "first pass should remove c.scjvm")

      // Snapshot the directory listing post-first-pass.
      val survivors1 = os.list(artDir).map(_.last).toSet

      val r2 = runSsc(sandbox, "clean", artDir.toString)
      assert(r2.exitCode == 0)
      val survivors2 = os.list(artDir).map(_.last).toSet
      assert(survivors1 == survivors2,
        s"second pass changed the dir: $survivors1 -> $survivors2")

      val out2 = r2.out.text()
      assert(out2.contains("removed 0"),
        s"expected 'removed 0' on idempotent re-run; got:\n$out2")
    finally os.remove.all(sandbox)

  // ── 5. Non-artifact files are left alone ────────────────────────────────

  test("clean ignores non-artifact files (e.g. README, .json without ssc extension)"):
    val sandbox = os.temp.dir(prefix = "ssc-clean-")
    try
      val (artDir, _) = setupFixture(sandbox,
        modulesWithSrc    = Nil,
        modulesWithoutSrc = Nil)
      // Toss in some unrelated files.
      os.write(artDir / "README.txt",      "just a readme")
      os.write(artDir / "config.json",     "{}")
      os.write(artDir / "build.log",       "build complete")

      val res = runSsc(sandbox, "clean", artDir.toString)
      assert(res.exitCode == 0, s"clean failed: ${res.err.text()}")

      assert(os.exists(artDir / "README.txt"),  "README.txt should be untouched")
      assert(os.exists(artDir / "config.json"), "config.json should be untouched")
      assert(os.exists(artDir / "build.log"),   "build.log should be untouched")
      assert(os.exists(artDir / "_runtime.scjvm-runtime"),
        "_runtime.scjvm-runtime should be untouched (no --all)")
    finally os.remove.all(sandbox)

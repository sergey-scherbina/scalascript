package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 — end-to-end smoke tests for separate-compilation CLI commands.
 *
 *  These tests spawn the actual `ssc` jar as a subprocess (no in-process
 *  mocking) and verify the produced artifacts on disk.  They cover:
 *
 *   - `ssc emit-interface <file>`         → `.scim` artifact
 *   - `ssc emit-ir <file>`                → `.scir` artifact
 *   - `ssc check-with-iface --iface-dir`  → type-checks against interfaces
 *   - `ssc link <artifact-dir>`           → merges modules
 *   - `ssc build --incremental <dir>`     → idempotent on unchanged sources
 *
 *  The tests require a built `cli/target/scala-3.8.3/ssc.jar` (produced by
 *  `sbt cli/assembly`).  When the jar is missing the tests cancel with a
 *  diagnostic message — mirroring the `RegistrySubprocessTest` pattern.
 *
 *  Run with:  `sbt "cli/testOnly *V2Artifact*"`
 */
class V2ArtifactCliTest extends AnyFunSuite:

  // ── ssc jar discovery ─────────────────────────────────────────────────────

  /** Resolve the ssc jar.  Search order:
   *   1. The current sbt project's own `cli/target/scala-3.8.3/ssc.jar`
   *      (produced by `sbt cli/assembly` from this checkout).
   *   2. When running from a `.claude/worktrees/<name>/` subtree, walk up to
   *      the canonical checkout and use its `cli/target/...` jar.
   *   3. None — tests cancel with a diagnostic.
   */
  private val sscJar: Option[os.Path] =
    val cwd = os.pwd

    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"

    // Walk up from cwd looking for the worktree-anchoring `.claude/worktrees`
    // dir; the canonical repo root is two segments above it.
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        // .claude/worktrees/<name>/  →  parts up to idx is the repo root.
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None

    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList

    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  /** Invoke the ssc CLI in `cwd` with the given args.  Returns the full
   *  `os.CommandResult` so callers can inspect exit code, stdout, stderr. */
  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd   = cwd,
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  /** Convenience: source file that exports a single `add` definition. */
  private def aSsc(extra: String = ""): String =
    s"""---
       |name: a
       |---
       |
       |# Module A
       |
       |```scalascript
       |def add(x: Int, y: Int): Int = x + y
       |```
       |$extra
       |""".stripMargin

  // ── 1. emit-interface → .scim ─────────────────────────────────────────────

  test("emit-interface produces a valid .scim with SSCART magic, ABI 2.0, and export 'add'"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val src = sandbox / "a.ssc"
      os.write(src, aSsc())

      val res = runSsc(sandbox, "emit-interface", "a.ssc")
      assert(res.exitCode == 0,
        s"emit-interface failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scim = sandbox / "a.scim"
      assert(os.exists(scim), s"expected $scim to be written; got dir: ${os.list(sandbox).mkString(", ")}")

      // Parse as raw JSON and validate envelope + exports.
      val json = upickleRead[JsonValue](os.read(scim))
      assert(json("magic").str == "SSCART", s"magic mismatch: ${json("magic")}")
      assert(json("abiVersion").str == "2.0", s"abiVersion mismatch: ${json("abiVersion")}")

      val exportNames = json("exports").arr.map(_("name").str).toList
      assert(exportNames.contains("add"),
        s"expected 'add' in exports; got: ${exportNames.mkString(", ")}")
    finally os.remove.all(sandbox)

  // ── 2. emit-ir → .scir ────────────────────────────────────────────────────

  test("emit-ir produces a valid .scir with SSCART magic, ABI 2.0, and non-empty body"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val src = sandbox / "a.ssc"
      os.write(src, aSsc())

      val res = runSsc(sandbox, "emit-ir", "a.ssc")
      assert(res.exitCode == 0,
        s"emit-ir failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scir = sandbox / "a.scir"
      assert(os.exists(scir), s"expected $scir to be written")

      val json = upickleRead[JsonValue](os.read(scir))
      assert(json("magic").str == "SSCART", s"magic mismatch: ${json("magic")}")
      assert(json("abiVersion").str == "2.0", s"abiVersion mismatch: ${json("abiVersion")}")
      // `body` is the NormalizedModule serialised as a JSON string.
      val body = json("body").str
      assert(body.nonEmpty, "expected non-empty body in .scir")
      // Body should at minimum parse as JSON and contain the section heading.
      val bodyJson = upickleRead[JsonValue](body)
      // Sanity: NormalizedModule has a `sections` array.
      assert(bodyJson.obj.contains("sections"),
        s"expected NormalizedModule body to have 'sections' field, got keys: ${bodyJson.obj.keys.mkString(", ")}")
    finally os.remove.all(sandbox)

  // ── 3. check-with-iface ───────────────────────────────────────────────────

  test("check-with-iface accepts an interface dir and type-checks consumer module"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      // Provider module: emit interface to ifaces/.
      val ifaceDir = sandbox / "ifaces"
      os.makeDir.all(ifaceDir)
      val aSrc = sandbox / "a.ssc"
      os.write(aSrc, aSsc())

      val emit = runSsc(sandbox, "emit-interface", "a.ssc", "-o", "ifaces/a.scim")
      assert(emit.exitCode == 0,
        s"emit-interface failed: ${emit.err.text()}")
      assert(os.exists(ifaceDir / "a.scim"))

      // Consumer module: references `add` from a's interface.
      val bSrc = sandbox / "b.ssc"
      os.write(bSrc,
        """---
          |name: b
          |---
          |
          |# Module B
          |
          |```scalascript
          |def useAdd(): Int = add(1, 2)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "check-with-iface", "--iface-dir", "ifaces", "b.ssc")
      // Positive case: with `add` available via interface, b should type-check.
      // Note: the typer currently produces `Any` for most names, so this
      // primarily exercises the artifact-loading + scope-population path.
      assert(res.exitCode == 0,
        s"check-with-iface failed unexpectedly: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
    finally os.remove.all(sandbox)

  // v2.0 — typer strict mode in `check-with-iface`.  References to names
  // that resolve to neither the consumer's own defs, any imported `.scim`,
  // nor the builtin prelude must surface as type errors with a non-zero
  // exit code and a diagnostic naming the missing symbol.
  test("check-with-iface — references to undefined names fail with diagnostic"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val ifaceDir = sandbox / "ifaces"
      os.makeDir.all(ifaceDir)

      // Provider exports `add` only.
      os.write(sandbox / "a.ssc", aSsc())
      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", "ifaces/a.scim").exitCode == 0)

      // Consumer references a name that is NOT in a's interface.
      os.write(sandbox / "b.ssc",
        """---
          |name: b
          |---
          |
          |# Module B
          |
          |```scalascript
          |def useUnknown(): Int = thisNameDoesNotExist(1, 2)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "check-with-iface", "--iface-dir", "ifaces", "b.ssc")
      assert(res.exitCode != 0,
        s"expected non-zero exit for undefined name; got exit=${res.exitCode}\n" +
        s"stdout=${res.out.text()}\nstderr=${res.err.text()}")
      val combined = res.out.text() + res.err.text()
      assert(combined.contains("thisNameDoesNotExist"),
        s"expected diagnostic to name the missing symbol; got:\n$combined")
    finally os.remove.all(sandbox)

  test("check-with-iface — empty iface dir is accepted (no errors from missing dir)"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val ifaceDir = sandbox / "empty-ifaces"
      os.makeDir.all(ifaceDir)
      // A self-contained module that doesn't reference anything external.
      val src = sandbox / "self.ssc"
      os.write(src,
        """---
          |name: self
          |---
          |
          |# Self
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |def useAdd(): Int = add(1, 2)
          |```
          |""".stripMargin)
      val res = runSsc(sandbox, "check-with-iface", "--iface-dir", "empty-ifaces", "self.ssc")
      assert(res.exitCode == 0,
        s"self-contained module should pass: exit=${res.exitCode}\nstderr=${res.err.text()}")
    finally os.remove.all(sandbox)

  // ── 4. link ───────────────────────────────────────────────────────────────

  test("link merges two artifact pairs and produces sections from both modules"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      // Module A — has a section "Module A".
      val aSrc = sandbox / "a.ssc"
      os.write(aSrc, aSsc())
      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", "artifacts/a.scim").exitCode == 0)
      assert(runSsc(sandbox, "emit-ir",        "a.ssc", "-o", "artifacts/a.scir").exitCode == 0)

      // Module B — has a section "Module B".
      val bSrc = sandbox / "b.ssc"
      os.write(bSrc,
        """---
          |name: b
          |---
          |
          |# Module B
          |
          |```scalascript
          |def mul(x: Int, y: Int): Int = x * y
          |```
          |""".stripMargin)
      assert(runSsc(sandbox, "emit-interface", "b.ssc", "-o", "artifacts/b.scim").exitCode == 0)
      assert(runSsc(sandbox, "emit-ir",        "b.ssc", "-o", "artifacts/b.scir").exitCode == 0)

      // Link to a `.scir` file so we can inspect the merged sections.
      val outScir = sandbox / "linked.scir"
      val res = runSsc(sandbox, "link", "artifacts", "-o", outScir.toString)
      assert(res.exitCode == 0,
        s"link failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(os.exists(outScir), s"expected $outScir to be written")

      // The linked .scir body should contain sections from both modules.
      val envelope = upickleRead[JsonValue](os.read(outScir))
      assert(envelope("magic").str == "SSCART")
      assert(envelope("abiVersion").str == "2.0")
      val body = upickleRead[JsonValue](envelope("body").str)
      val sectionTitles = body("sections").arr.map(_("heading")("text").str).toList
      assert(sectionTitles.contains("Module A"),
        s"expected 'Module A' in linked sections; got: ${sectionTitles.mkString(", ")}")
      assert(sectionTitles.contains("Module B"),
        s"expected 'Module B' in linked sections; got: ${sectionTitles.mkString(", ")}")
    finally os.remove.all(sandbox)

  // ── 5. build --incremental — idempotency + per-file rebuild ──────────────

  test("build --incremental is idempotent on unchanged sources, rebuilds touched files"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)
      val aSrc = srcDir / "a.ssc"
      val bSrc = srcDir / "b.ssc"
      os.write(aSrc, aSsc())
      os.write(bSrc,
        """---
          |name: b
          |---
          |
          |# Module B
          |
          |```scalascript
          |def mul(x: Int, y: Int): Int = x * y
          |```
          |""".stripMargin)

      val artDir = srcDir / ".ssc-artifacts"

      // First build — compiles both modules from scratch.
      val r1 = runSsc(sandbox, "build", "--incremental", "src")
      assert(r1.exitCode == 0,
        s"first build failed: ${r1.err.text()}")
      val aScim1 = artDir / "a.scim"
      val aScir1 = artDir / "a.scir"
      val bScim1 = artDir / "b.scim"
      val bScir1 = artDir / "b.scir"
      assert(os.exists(aScim1) && os.exists(aScir1), "a's artifacts should exist after first build")
      assert(os.exists(bScim1) && os.exists(bScir1), "b's artifacts should exist after first build")

      val aScimMtime1 = os.mtime(aScim1)
      val aScirMtime1 = os.mtime(aScir1)
      val bScimMtime1 = os.mtime(bScim1)
      val bScirMtime1 = os.mtime(bScir1)

      // Some filesystems have coarse mtime resolution (HFS+: 1s, APFS: ns
      // but `os.mtime` returns ms).  Ensure a noticeable delta if a write
      // does occur — sleep enough that any actual rewrite is detectable.
      Thread.sleep(1100)

      // Second build — no source changes.  isStale uses SHA-256, so both
      // files should be skipped and artifact mtimes should be untouched.
      val r2 = runSsc(sandbox, "build", "--incremental", "src")
      assert(r2.exitCode == 0,
        s"second build failed: ${r2.err.text()}")
      val r2out = r2.out.text()
      assert(r2out.contains("[skip]"),
        s"expected second build to skip unchanged modules; output:\n$r2out")

      assert(os.mtime(aScim1) == aScimMtime1,
        s"a.scim mtime changed despite no source change: ${aScimMtime1} → ${os.mtime(aScim1)}")
      assert(os.mtime(aScir1) == aScirMtime1, "a.scir mtime changed despite no source change")
      assert(os.mtime(bScim1) == bScimMtime1, "b.scim mtime changed despite no source change")
      assert(os.mtime(bScir1) == bScirMtime1, "b.scir mtime changed despite no source change")

      // Touch a.ssc by rewriting it with new content — the SHA-256 must
      // change for isStale to fire.  (Mere `os.write` of the same bytes
      // would not invalidate the artifact, since isStale hashes content
      // not mtime — this is the intended behaviour.)
      os.write.over(aSrc, aSsc(extra = "\n## New section\n\nMore prose.\n"))
      Thread.sleep(1100)

      val r3 = runSsc(sandbox, "build", "--incremental", "src")
      assert(r3.exitCode == 0,
        s"third build failed: ${r3.err.text()}")

      // a's artifacts should have been rewritten; b's untouched.
      assert(os.mtime(aScim1) > aScimMtime1,
        s"a.scim should have been rebuilt after source change; mtime ${aScimMtime1} → ${os.mtime(aScim1)}")
      assert(os.mtime(aScir1) > aScirMtime1,
        s"a.scir should have been rebuilt after source change")
      assert(os.mtime(bScim1) == bScimMtime1,
        s"b.scim should NOT have been rebuilt (b's source unchanged); mtime ${bScimMtime1} → ${os.mtime(bScim1)}")
      assert(os.mtime(bScir1) == bScirMtime1,
        s"b.scir should NOT have been rebuilt (b's source unchanged)")
    finally os.remove.all(sandbox)

  // ── 6. build --incremental — shared `package:` warning ──────────────────

  test("build --incremental warns when two files share the same `package:`"):
    // Sharing a package across files is legal (Scala-style namespace
    // grouping; std/cluster/, std/dsl/ etc. do this on purpose).  But
    // the build surfaces a warning so users notice — if their files
    // also happen to export the same symbol, the linker dedup pass
    // would silently drop the second occurrence.
    val sandbox = os.temp.dir(prefix = "ssc-v2-cli-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)

      val sharedPkgX =
        """---
          |package: acme.shared
          |---
          |
          |# X
          |
          |```scalascript
          |def aaa(): Int = 1
          |```
          |""".stripMargin
      val sharedPkgY =
        """---
          |package: acme.shared
          |---
          |
          |# Y
          |
          |```scalascript
          |def bbb(): Int = 2
          |```
          |""".stripMargin
      os.write(srcDir / "x.ssc", sharedPkgX)
      os.write(srcDir / "y.ssc", sharedPkgY)

      val r = runSsc(sandbox, "build", "--incremental", "src")
      // Build still succeeds — disjoint symbols are safe.
      assert(r.exitCode == 0,
        s"build should succeed (warning, not fatal); stdout=${r.out.text()} stderr=${r.err.text()}")
      val combined = r.err.text() + r.out.text()
      assert(combined.contains("acme.shared"),
        s"warning should mention the shared package; got: $combined")
      assert(combined.contains("x.ssc") && combined.contains("y.ssc"),
        s"warning should name both files; got: $combined")
    finally os.remove.all(sandbox)

package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 — linker top-level dedup safety net.
 *
 *  Validates that `ssc link --backend jvm|js` produces output free of
 *  duplicate top-level definitions even when per-module artifacts have
 *  divergent runtime preambles (e.g., module A uses `effect Foo:` so its
 *  `.scjvm` embeds `effectsRuntime`, while module B is plain so its
 *  preamble is shorter).
 *
 *  The longest-common-prefix dedup alone cannot handle this — it only
 *  works when every module's preamble is byte-identical.  The
 *  `dedupTopLevelDefs` pass walks the combined source and skips
 *  duplicate top-level declarations by name.
 *
 *  Run with:  `sbt "cli/testOnly *LinkerDedup*"`
 */
class LinkerDedupCliTest extends AnyFunSuite:

  // ── ssc jar discovery (mirrors JvmIncrementalCliTest) ────────────────────

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
      cwd   = cwd,
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  /** Module A — declares an algebraic effect.  Triggers `effectsRuntime`
   *  emission in `JvmGen`, producing a `.scjvm` whose runtime preamble
   *  is strictly longer than a module that doesn't use effects. */
  private val aEffectsSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A — effect declaration
      |
      |```scalascript
      |effect Foo:
      |  def bar(): Int
      |
      |def usesFoo(): Int = Foo.bar()
      |```
      |""".stripMargin

  /** Module B — no effects, just a plain top-level value. */
  private val bPlainSsc: String =
    """---
      |name: b
      |---
      |
      |# Module B — plain
      |
      |```scalascript
      |val x = 42
      |```
      |""".stripMargin

  /** Source for a module exporting `add` — distinct from `mul` so the
   *  baseline link tests don't trigger dedup. */
  private def aAddSsc: String =
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

  private def bMulSsc: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |def mul(x: Int, y: Int): Int = x * y
      |```
      |""".stripMargin

  /** Count whole-word occurrences of `name` as a top-level Scala decl —
   *  i.e., a `def name` / `val name` / `class name` etc. anchored at
   *  column 0.  Used to assert dedup actually fires. */
  private def countTopLevelDefs(source: String, name: String): Int =
    val pattern = ("(?m)^(?:private |implicit |final |sealed |case |lazy |inline )*" +
      "(?:def|val|var|class|object|trait|enum|type|given|extension)\\s+" +
      java.util.regex.Pattern.quote(name) + "\\b").r
    pattern.findAllMatchIn(source).size

  /** Count whole-word occurrences of `name` as a top-level JS decl. */
  private def countTopLevelJsDefs(source: String, name: String): Int =
    val pattern = ("(?m)^(?:async\\s+)?(?:function|const|let|var|class)\\s+" +
      java.util.regex.Pattern.quote(name) + "\\b").r
    pattern.findAllMatchIn(source).size

  // ── 1. JVM dedup with divergent effect preambles ─────────────────────────

  test("link --backend jvm deduplicates effect runtime helpers across modules with divergent preambles"):
    val sandbox = os.temp.dir(prefix = "ssc-linker-dedup-jvm-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      val aSrc = sandbox / "a.ssc"
      val bSrc = sandbox / "b.ssc"
      os.write(aSrc, aEffectsSsc)
      os.write(bSrc, bPlainSsc)

      // Compile both modules to .scjvm.  Module A's source embeds the
      // algebraic-effects runtime; module B's does not — so the two
      // .scjvm preambles are NOT byte-identical and the prefix-dedup
      // alone leaves duplicate runtime helpers in the concat.
      val ca = runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm")
      assert(ca.exitCode == 0,
        s"compile-jvm a failed: exit=${ca.exitCode}\nstderr=${ca.err.text()}")
      val cb = runSsc(sandbox, "compile-jvm", "b.ssc", "-o", "artifacts/b.scjvm")
      assert(cb.exitCode == 0,
        s"compile-jvm b failed: exit=${cb.exitCode}\nstderr=${cb.err.text()}")

      val outScala = sandbox / "out.scala"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "artifacts", "-o", outScala.toString)
      assert(rl.exitCode == 0,
        s"link --backend jvm failed: ${rl.err.text()}")
      val combined = os.read(outScala)

      // The dedup safety net should keep every named runtime helper at
      // most once.  Pick a handful of names the JvmGen effects runtime
      // is known to emit — they must all be unique in the linked output.
      for name <- List("_handle", "_bind", "_perform", "_run") do
        val n = countTopLevelDefs(combined, name)
        assert(n <= 1,
          s"$name appears $n times in linked Scala source — dedup did not fire.\n" +
            s"First 600 chars:\n${combined.take(600)}\n…")

      // Sanity — the unique user-level decls from BOTH modules should
      // still be present after dedup.
      assert(combined.contains("usesFoo"),
        "expected `usesFoo` (from module a) to survive the dedup pass")
      assert(countTopLevelDefs(combined, "x") >= 1,
        s"expected `val x` (from module b) to survive the dedup pass.\n" +
          s"First 500 chars:\n${combined.take(500)}")
    finally os.remove.all(sandbox)

  // ── 2. JS dedup with divergent runtime preambles ─────────────────────────

  test("link --backend js deduplicates JS runtime helpers across modules with divergent preambles"):
    val sandbox = os.temp.dir(prefix = "ssc-linker-dedup-js-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      val aSrc = sandbox / "a.ssc"
      val bSrc = sandbox / "b.ssc"
      os.write(aSrc, aEffectsSsc)
      os.write(bSrc, bPlainSsc)

      assert(runSsc(sandbox, "compile-js", "a.ssc", "-o", "artifacts/a.scjs").exitCode == 0)
      assert(runSsc(sandbox, "compile-js", "b.ssc", "-o", "artifacts/b.scjs").exitCode == 0)

      val outJs = sandbox / "out.js"
      val rl = runSsc(sandbox, "link", "--backend", "js", "artifacts", "-o", outJs.toString)
      assert(rl.exitCode == 0,
        s"link --backend js failed: ${rl.err.text()}")
      val combined = os.read(outJs)

      // Every named JS top-level decl must appear at most once.
      for name <- List("_handle", "_perform", "_bind", "_run") do
        val n = countTopLevelJsDefs(combined, name)
        assert(n <= 1,
          s"$name appears $n times in linked JS source — dedup did not fire.\n" +
            s"First 600 chars:\n${combined.take(600)}\n…")
    finally os.remove.all(sandbox)

  // ── 3. Sanity: existing two-module link still works (no dedup needed) ────

  test("link --backend jvm with two distinct modules (add + mul) is unaffected by dedup pass"):
    val sandbox = os.temp.dir(prefix = "ssc-linker-dedup-baseline-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aAddSsc)
      os.write(sandbox / "b.ssc", bMulSsc)

      assert(runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm").exitCode == 0)
      assert(runSsc(sandbox, "compile-jvm", "b.ssc", "-o", "artifacts/b.scjvm").exitCode == 0)

      val outScala = sandbox / "out.scala"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "artifacts", "-o", outScala.toString)
      assert(rl.exitCode == 0, s"link --backend jvm failed: ${rl.err.text()}")
      val combined = os.read(outScala)
      assert(combined.contains("def add"),
        s"expected `def add` in combined source; got:\n${combined.take(300)}")
      assert(combined.contains("def mul"),
        s"expected `def mul` in combined source; got:\n${combined.take(300)}")
    finally os.remove.all(sandbox)

  test("link --backend js with two distinct modules (add + mul) is unaffected by dedup pass"):
    val sandbox = os.temp.dir(prefix = "ssc-linker-dedup-baseline-js-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aAddSsc)
      os.write(sandbox / "b.ssc", bMulSsc)

      assert(runSsc(sandbox, "compile-js", "a.ssc", "-o", "artifacts/a.scjs").exitCode == 0)
      assert(runSsc(sandbox, "compile-js", "b.ssc", "-o", "artifacts/b.scjs").exitCode == 0)

      val outJs = sandbox / "out.js"
      val rl = runSsc(sandbox, "link", "--backend", "js", "artifacts", "-o", outJs.toString)
      assert(rl.exitCode == 0, s"link --backend js failed: ${rl.err.text()}")
      val combined = os.read(outJs)
      assert(combined.contains("add"),
        s"expected `add` in combined JS source; got first 300 chars:\n${combined.take(300)}")
      assert(combined.contains("mul"),
        s"expected `mul` in combined JS source; got first 300 chars:\n${combined.take(300)}")
    finally os.remove.all(sandbox)

  // ── 4. Sanity: collisions on user-level names are silently dropped ───────

  test("link silently drops the second definition when two modules declare the same top-level name"):
    val sandbox = os.temp.dir(prefix = "ssc-linker-dedup-collision-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      // Both modules declare `def add` — the dedup keeps the first and
      // drops the second.  This is acceptable for MVP; Scala 3 itself
      // would have rejected the duplicate at compile time anyway.
      os.write(sandbox / "a.ssc", aAddSsc)
      os.write(sandbox / "b.ssc",
        """---
          |name: b
          |---
          |
          |# Module B (duplicates a's `add`)
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y + 1
          |```
          |""".stripMargin)

      assert(runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm").exitCode == 0)
      assert(runSsc(sandbox, "compile-jvm", "b.ssc", "-o", "artifacts/b.scjvm").exitCode == 0)

      val outScala = sandbox / "out.scala"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "artifacts", "-o", outScala.toString)
      assert(rl.exitCode == 0, s"link --backend jvm failed: ${rl.err.text()}")
      val combined = os.read(outScala)
      val n = countTopLevelDefs(combined, "add")
      assert(n == 1,
        s"expected `def add` to be deduped to a single top-level decl, got $n.\n" +
          s"First 500 chars:\n${combined.take(500)}")
    finally os.remove.all(sandbox)

package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 — battle-test the separate-compilation pipeline against the REAL
 *  `std` modules (not synthetic fixtures).
 *
 *  The goal of this suite is to surface concrete bugs that the synthetic
 *  fixture tests in `V2ArtifactCliTest` / `AutoResolveCliTest` miss, by
 *  driving the full CLI pipeline (`compile-jvm`, `compile-js`, `build`,
 *  `check-with-iface`, `link`) against real production source.
 *
 *  Each test:
 *    1. Copies a small set of real `std` .ssc files into a temp dir.
 *    2. Runs the relevant `ssc` CLI command via `os.proc`.
 *    3. Asserts the expected exit code and inspects the produced artifacts.
 *
 *  Known bugs uncovered by this suite are documented with `TODO(v2.0):`
 *  markers; the tests assert the CURRENT (broken) behaviour and note a
 *  root-cause hint inline.
 *
 *  Run with:  `sbt "cli/testOnly *V2RealStd*"`
 */
class V2RealStdModulesTest extends AnyFunSuite:

  // ── ssc jar discovery (mirrors V2ArtifactCliTest) ────────────────────────

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

  // ── std/ source locator ──────────────────────────────────────────────────

  /** Locate the project's `std/` directory.  Search order:
   *   1. `<cwd>/std`               (sbt running from project root)
   *   2. `<cwd>/../std`            (sbt running from `cli/`)
   *   3. `<canonical-repo>/std`    (worktree → canonical repo)
   *  Tests cancel if `std/` cannot be found.
   */
  private val stdDir: Option[os.Path] =
    val cwd = os.pwd
    def stdCandidates(root: os.Path): List[os.Path] =
      List(root / "runtime" / "std", root / "std")
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    // Prefer the std/ that lives alongside the running checkout — when the
    // test runs from a worktree, its branch may carry a different std/ than
    // canonical main.
    val candidates =
      stdCandidates(cwd) ++
      stdCandidates(cwd / os.up) ++
      findCanonicalRepo(cwd).toList.flatMap(stdCandidates)
    candidates.find(os.isDir)

  private def requireStdDir(): os.Path = stdDir.getOrElse:
    cancel("std/ directory not found in project tree")

  /** Copy a single `std/<relative>` file into `dst`, preserving the leaf name. */
  private def copyStd(rel: String, dst: os.Path): os.Path =
    val src = requireStdDir() / os.RelPath(rel)
    if !os.exists(src) then cancel(s"std file missing: $src")
    val out = dst / src.last
    os.copy(src, out, replaceExisting = true)
    out

  // ── 1. compile-jvm std/eq.ssc — produces .scjvm + valid exports ──────────

  test("compile-jvm std/eq.ssc — succeeds, .scim exports Eq + eqv + neqv"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      copyStd("eq.ssc", sandbox)

      // First, emit-interface (we want to inspect exports).
      val emit = runSsc(sandbox, "emit-interface", "eq.ssc")
      assert(emit.exitCode == 0,
        s"emit-interface eq.ssc failed: exit=${emit.exitCode}\nstderr=${emit.err.text()}")
      val scim = sandbox / "eq.scim"
      assert(os.exists(scim), s"expected $scim")

      val json = upickleRead[JsonValue](os.read(scim))
      assert(json("magic").str == "SSCART")
      assert(json("abiVersion").str == "2.0")
      val exportNames = json("exports").arr.map(_("name").str).toSet
      assert(exportNames.contains("Eq"),   s"missing Eq export: $exportNames")
      assert(exportNames.contains("eqv"),  s"missing eqv export: $exportNames")
      assert(exportNames.contains("neqv"), s"missing neqv export: $exportNames")

      // The .scim records given/anonymous Eq instances for primitive types.
      val instances = json("instances").arr
      val instTcs = instances.map(_("typeclass").str).toSet
      assert(instTcs.contains("Eq"), s"expected Eq instances in .scim; got: $instTcs")
      val instTypes = instances.map(_("typeParam").str).toSet
      // At minimum Int and String — full set is Int/Long/Double/String/Boolean.
      assert(instTypes.contains("Int")    && instTypes.contains("String"),
        s"expected Int+String Eq instances; got: $instTypes")

      // Anonymous `given Eq[Int] with …` instances are now recorded with a
      // synthesized deterministic witness name (`given_Eq_Int`) and a
      // matching FQN (`std_eq_given_Eq_Int`), so downstream tools that key
      // by witness FQN can resolve them.  See InterfaceExtractor.synthGivenName.
      val anonWitnesses = instances.collect {
        case i if i("typeclass").str == "Eq" => i("witnessName").str -> i("fqn").str
      }
      assert(anonWitnesses.nonEmpty, "expected at least one Eq instance")
      // No witness should have an empty name anymore.
      assert(anonWitnesses.forall(_._1.nonEmpty),
        s"expected all Eq witnesses to have a synthesized name; got: $anonWitnesses")
      // No FQN should end in a trailing `_` — that was the v2.0 bug symptom.
      assert(anonWitnesses.forall(!_._2.endsWith("_")),
        s"expected no FQNs ending in `_`; got: $anonWitnesses")
      // Specifically: the Int instance is named `given_Eq_Int`.
      val intInst = instances.find(i =>
        i("typeclass").str == "Eq" && i("typeParam").str == "Int").get
      assert(intInst("witnessName").str == "given_Eq_Int",
        s"expected `given_Eq_Int`; got: ${intInst("witnessName").str}")
      assert(intInst("fqn").str == "std_eq_given_Eq_Int",
        s"expected `std_eq_given_Eq_Int`; got: ${intInst("fqn").str}")

      // Now: real JVM codegen.
      val compile = runSsc(sandbox, "compile-jvm", "eq.ssc")
      assert(compile.exitCode == 0,
        s"compile-jvm eq.ssc failed: exit=${compile.exitCode}\nstderr=${compile.err.text()}")
      assert(os.exists(sandbox / "eq.scjvm"), "expected eq.scjvm")
      // The .scjvm envelope is JSON containing the Scala source as `scalaSource`.
      val scjvm = upickleRead[JsonValue](os.read(sandbox / "eq.scjvm"))
      assert(scjvm("magic").str == "SSCART")
      assert(scjvm("abiVersion").str == "2.0")
      val src = scjvm("scalaSource").str
      assert(src.contains("eqv"), s"expected 'eqv' in generated Scala source")
    finally os.remove.all(sandbox)

  // ── 2. show / hash / order — same typeclass shape, all compile ──────────

  test("compile-jvm std/show.ssc + std/hash.ssc + std/order.ssc — all succeed"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      for name <- List("show.ssc", "hash.ssc", "order.ssc") do
        copyStd(name, sandbox)
        val res = runSsc(sandbox, "compile-jvm", name)
        assert(res.exitCode == 0,
          s"compile-jvm $name failed: exit=${res.exitCode}\nstderr=${res.err.text()}")
        val scjvm = sandbox / name.replace(".ssc", ".scjvm")
        assert(os.exists(scjvm), s"expected $scjvm to be written")
    finally os.remove.all(sandbox)

  // ── 3. std/dsl/ast + std/dsl/builders — standalone compile ──────────────

  test("compile-jvm std/dsl/ast.ssc + std/dsl/builders.ssc — both succeed standalone"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      // Note: in the real source tree, builders.ssc does NOT import ast.ssc
      // (it's a self-contained phantom-type builder template).  We still
      // compile both as smoke tests; auto-resolve has nothing to do here.
      copyStd("dsl/ast.ssc", sandbox)
      copyStd("dsl/builders.ssc", sandbox)

      val r1 = runSsc(sandbox, "compile-jvm", "ast.ssc")
      assert(r1.exitCode == 0,
        s"compile-jvm dsl/ast.ssc failed: ${r1.err.text()}")
      assert(os.exists(sandbox / "ast.scjvm"))

      val r2 = runSsc(sandbox, "compile-jvm", "builders.ssc")
      assert(r2.exitCode == 0,
        s"compile-jvm dsl/builders.ssc failed: ${r2.err.text()}")
      assert(os.exists(sandbox / "builders.scjvm"))
    finally os.remove.all(sandbox)

  // ── 4. build --incremental --backend jvm std/dsl/ — whole dir ───────────

  test("build --incremental --backend jvm std/dsl/ — all 5 modules compile to .scjvm"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      val srcDir = sandbox / "dsl"
      os.makeDir.all(srcDir)
      val expected = List("ast.ssc", "builders.ssc", "passes.ssc", "pretty.ssc", "walker.ssc")
      for f <- expected do copyStd(s"dsl/$f", srcDir)

      val res = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "dsl")
      assert(res.exitCode == 0,
        s"build --incremental --backend jvm failed:\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("5 compiled"),
        s"expected '5 compiled' in build output; got:\n$out")

      val artDir = srcDir / ".ssc-artifacts"
      for f <- expected do
        val base = f.stripSuffix(".ssc")
        assert(os.exists(artDir / s"$base.scim"),  s"missing $base.scim under $artDir")
        assert(os.exists(artDir / s"$base.scir"),  s"missing $base.scir under $artDir")
        assert(os.exists(artDir / s"$base.scjvm"), s"missing $base.scjvm under $artDir")
    finally os.remove.all(sandbox)

  // ── 5. build --incremental --backend js std/dsl/ — whole dir ────────────

  test("build --incremental --backend js std/dsl/ — all 5 modules compile to .scjs"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      val srcDir = sandbox / "dsl"
      os.makeDir.all(srcDir)
      val expected = List("ast.ssc", "builders.ssc", "passes.ssc", "pretty.ssc", "walker.ssc")
      for f <- expected do copyStd(s"dsl/$f", srcDir)

      val res = runSsc(sandbox, "build", "--incremental", "--backend", "js", "dsl")
      assert(res.exitCode == 0,
        s"build --incremental --backend js failed:\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("5 compiled"),
        s"expected '5 compiled' in build output; got:\n$out")

      val artDir = srcDir / ".ssc-artifacts"
      for f <- expected do
        val base = f.stripSuffix(".ssc")
        assert(os.exists(artDir / s"$base.scjs"), s"missing $base.scjs under $artDir")
    finally os.remove.all(sandbox)

  // ── 6. compile-jvm auto-resolves parsing dep chain (helpers → combinators → core) ──

  test("compile-jvm std/parsing/helpers.ssc — auto-resolves combinators + core via markdown link"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      // Note: deliberately copy only the three files that form a clean
      // chain.  `recovery.ssc` is excluded — its YAML front-matter has an
      // unquoted colon in the description ("recovery: skip-to-sync-token")
      // which the YAML parser rejects.  See test #8 below.
      copyStd("parsing/core.ssc", sandbox)
      copyStd("parsing/combinators.ssc", sandbox)
      copyStd("parsing/helpers.ssc", sandbox)

      val res = runSsc(sandbox, "compile-jvm", "helpers.ssc")
      assert(res.exitCode == 0,
        s"compile-jvm helpers.ssc (deps: combinators, core) failed: " +
        s"exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      // The target's artifact is written next to source.
      assert(os.exists(sandbox / "helpers.scjvm"), "expected helpers.scjvm")
      // Auto-resolved deps land in .ssc-artifacts/.
      val artDir = sandbox / ".ssc-artifacts"
      assert(os.exists(artDir / "core.scim"),         s"missing $artDir/core.scim")
      assert(os.exists(artDir / "core.scjvm"),        s"missing $artDir/core.scjvm")
      assert(os.exists(artDir / "combinators.scim"),  s"missing $artDir/combinators.scim")
      assert(os.exists(artDir / "combinators.scjvm"), s"missing $artDir/combinators.scjvm")
    finally os.remove.all(sandbox)

  // ── 7. check-with-iface against real std/eq.scim ─────────────────────────

  test("check-with-iface — consumer using std/eq.scim — accepts valid, rejects misspelling"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      val ifaceDir = sandbox / "ifaces"
      os.makeDir.all(ifaceDir)
      copyStd("eq.ssc", sandbox)
      assert(runSsc(sandbox, "emit-interface", "eq.ssc", "-o", "ifaces/eq.scim").exitCode == 0)

      // Positive consumer — uses `eqv` from std/eq.
      os.write(sandbox / "good.ssc",
        """---
          |name: good-consumer
          |---
          |
          |# Good Consumer
          |
          |```scalascript
          |def isEqual(a: Int, b: Int): Boolean = eqv(a, b)
          |```
          |""".stripMargin)
      val good = runSsc(sandbox, "check-with-iface", "--iface-dir", "ifaces", "good.ssc")
      assert(good.exitCode == 0,
        s"good consumer should pass: exit=${good.exitCode}\nstderr=${good.err.text()}")

      // Negative consumer — misspelled name.
      os.write(sandbox / "bad.ssc",
        """---
          |name: bad-consumer
          |---
          |
          |# Bad Consumer
          |
          |```scalascript
          |def isEqual(a: Int, b: Int): Boolean = eqvMisspelled(a, b)
          |```
          |""".stripMargin)
      val bad = runSsc(sandbox, "check-with-iface", "--iface-dir", "ifaces", "bad.ssc")
      assert(bad.exitCode != 0,
        s"bad consumer should fail: exit=${bad.exitCode}\nstdout=${bad.out.text()}")
      val combined = bad.out.text() + bad.err.text()
      assert(combined.contains("eqvMisspelled"),
        s"expected diagnostic to name 'eqvMisspelled'; got:\n$combined")
    finally os.remove.all(sandbox)

  // ── 8. link std/dsl/*.scjvm → out.scala — verify expected def names ─────

  test("link --backend jvm of std/dsl/ artifacts — output contains expected def names"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      val srcDir = sandbox / "dsl"
      os.makeDir.all(srcDir)
      val expected = List("ast.ssc", "builders.ssc", "passes.ssc", "pretty.ssc", "walker.ssc")
      for f <- expected do copyStd(s"dsl/$f", srcDir)

      val build = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "dsl")
      assert(build.exitCode == 0, s"build failed: ${build.err.text()}")

      val artDir = srcDir / ".ssc-artifacts"
      val outScala = sandbox / "out.scala"
      val link = runSsc(sandbox, "link", "--backend", "jvm",
        artDir.toString, "-o", outScala.toString)
      assert(link.exitCode == 0,
        s"link --backend jvm failed: exit=${link.exitCode}\nstderr=${link.err.text()}")
      assert(os.exists(outScala), s"expected $outScala")

      // The linked Scala source should textually contain the public defs
      // from std/dsl.  We don't try to scala-cli compile it (link's runtime
      // injects a script preamble with possibly-conflicting helpers); we
      // only assert textual presence.
      val src = os.read(outScala)
      assert(src.contains("def spanMerge"), "linked source missing 'def spanMerge' (from dsl/ast)")
      assert(src.contains("def render"),    "linked source missing 'def render' (from dsl/pretty)")
      assert(src.contains("def builder"),   "linked source missing 'def builder' (from dsl/builders)")
    finally os.remove.all(sandbox)

  // ── 9. build of std/parsing/ produces artifacts for all 5 modules ──────

  // Previously: recovery.ssc had an unquoted colon in `description:` that
  // SnakeYAML rejected with "mapping values are not allowed here", which
  // broke the whole 5-module build.  Stage 5.7 quoted the description in
  // the source AND added a better YAML diagnostic with a hint, so the
  // build now succeeds for all 5 modules.
  test("build --incremental std/parsing/ — all 5 modules emit .scjvm"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-")
    try
      val srcDir = sandbox / "parsing"
      os.makeDir.all(srcDir)
      val all = List("core.ssc", "combinators.ssc", "helpers.ssc", "layout.ssc", "recovery.ssc")
      for f <- all do copyStd(s"parsing/$f", srcDir)

      val res = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "parsing")
      val out = res.out.text()
      val err = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit=0 after recovery.ssc YAML fix; got ${res.exitCode}\n" +
        s"stdout=$out\nstderr=$err")
      // Confirm artifacts for ALL 5 modules ended up on disk.
      val artDir = srcDir / ".ssc-artifacts"
      for base <- List("core", "combinators", "helpers", "layout", "recovery") do
        assert(os.exists(artDir / s"$base.scjvm"),
          s"expected $base.scjvm on disk; got dir:\n" +
          (if os.exists(artDir) then os.list(artDir).map(_.last).mkString(", ") else "(missing)"))
    finally os.remove.all(sandbox)

  // ── 10. std/actors.ssc parse failure now surfaces a structured diagnostic
  //
  // Historically (pre-v2.0 parse-error-positions milestone) the scalascript
  // code-block parser rejected `std/actors.ssc`'s ~150-line `enum` +
  // `object Supervisor:` body with a single opaque line:
  //
  //   $ ssc compile-jvm std/actors.ssc
  //     Error: Failed to parse scalascript code block
  //
  // — no line number, no snippet, no hint about which construct tripped
  // the parser, so bisecting was hours of manual binary-search.
  //
  // The v2.0 milestone wires scalameta's `Parsed.Error.pos` through
  // `Content.CodeBlock.parseError`, and every CLI surface that loads a
  // `.ssc` (compile-jvm, compile-js, emit-interface, emit-ir,
  // check-with-iface, build --incremental) prints:
  //
  //   error: failed to parse scalascript block in <file>:<line>:<col>
  //   <message>
  //
  //     <context-1>
  //     <failing line>
  //     <space-padded ^>
  //     <context+1>
  //
  // actors.ssc now compiles cleanly (all `extern def` constructs handled).
  // The test was previously asserting parse failure — flipped to assert success.
  test("compile-jvm std/actors.ssc — compiles to .scjvm artifact"):
    val sandbox = os.temp.dir(prefix = "ssc-real-std-actors-")
    try
      copyStd("actors.ssc", sandbox)
      val res = runSsc(sandbox, "compile-jvm", "actors.ssc")
      assert(res.exitCode == 0,
        s"expected exit 0 for actors.ssc; got ${res.exitCode}\n" +
        s"stdout: ${res.out.text()}\nstderr: ${res.err.text()}")
      assert(os.exists(sandbox / "actors.scjvm"),
        "expected actors.scjvm to be produced")
    finally os.remove.all(sandbox)

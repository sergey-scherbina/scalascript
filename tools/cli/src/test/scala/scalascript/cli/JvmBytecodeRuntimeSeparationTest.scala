package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 2 — tests for the split-runtime emit path.
 *
 *  The bytecode linker MVP shipped the full ~180 KB runtime preamble
 *  inside every `.scjvm` artifact's `classBundle`, producing ~500 KB
 *  per-module bundles and a 554 KB JAR for two trivial modules.
 *
 *  Phase 2 factors the preamble into a shared `_runtime.scjvm-runtime`
 *  artifact compiled once per session and reused at link time.  These
 *  tests verify:
 *
 *   1. `compile-jvm --bytecode` produces a module `.scjvm` whose
 *      `classBundle` is at least an order of magnitude smaller than the
 *      legacy 500+ KB number.
 *
 *   2. The shared runtime artifact (`_runtime.scjvm-runtime`) is written
 *      alongside the module artifacts and carries a non-empty classBundle.
 *
 *   3. Re-compiling the same source is a no-op for the runtime artifact
 *      — its sourceHash and capabilities are unchanged so the existing
 *      runtime is reused.
 *
 *   4. Adding a module that needs a new capability triggers runtime
 *      regeneration with the union.
 *
 *   5. End-to-end run: `java -cp out.jar:<stdlib> a_sc` prints the
 *      expected output, and the resulting JAR is meaningfully smaller
 *      than the pre-Phase-2 baseline.
 *
 *  All tests `cancel(...)` when prerequisites (`ssc.jar`, `scala-cli`,
 *  Scala 3 stdlib in Coursier's cache) are missing.
 *
 *  Run with: `sbt "cli/testOnly *JvmBytecodeRuntimeSeparation*"`
 */
class JvmBytecodeRuntimeSeparationTest extends AnyFunSuite:

  // ── Test scaffolding (mirrors JvmBytecodeLinkCliTest) ───────────────────

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

  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for compile-jvm --bytecode")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(cwd = cwd, check = false, stderr = os.Pipe, stdout = os.Pipe)

  private def scalaStdlibClasspath(): Option[String] =
    val home = sys.env.getOrElse("HOME", "")
    if home.isEmpty then None
    else
      val coursierRoot = os.Path(home) / "Library" / "Caches" / "Coursier" / "v1" /
        "https" / "repo1.maven.org" / "maven2" / "org" / "scala-lang"
      val s3Root = coursierRoot / "scala3-library_3"
      val s2Root = coursierRoot / "scala-library"
      if !os.exists(s3Root) || !os.exists(s2Root) then None
      else
        def newestJar(root: os.Path, pattern: String): Option[os.Path] =
          if !os.isDir(root) then None
          else
            val dirs = os.list(root).filter(p => os.isDir(p) && p.last.matches("\\d+\\.\\d+\\.\\d+"))
            dirs.flatMap { d =>
              os.list(d).filter(p => p.last.matches(pattern) && !p.last.contains("sources"))
            }.sortBy(_.toString).reverse.headOption
        val s3 = newestJar(s3Root, "scala3-library_3-\\d.*\\.jar")
        val s2 = newestJar(s2Root, "scala-library-\\d.*\\.jar")
        (s3, s2) match
          case (Some(j3), Some(j2)) => Some(s"$j3:$j2")
          case _                    => None

  private def requireScalaStdlib(): String = scalaStdlibClasspath().getOrElse:
    cancel("Scala 3 stdlib JARs not found in Coursier cache — skipping JAR-run test")

  // ── Test fixtures ────────────────────────────────────────────────────────

  /** A trivial module: one def + a println.  Needs no extra capabilities. */
  private val aSscWithMain: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |println("a.add(2, 3) = " + add(2, 3))
      |```
      |""".stripMargin

  private val aSscQuiet: String =
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

  /** A second module needing the `serve` capability — adds a route(). */
  private val bSscWithRoute: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |route("GET", "/hi") { req => Response.text("hi") }
      |println("b registered route")
      |```
      |""".stripMargin

  /** Read the `classBundle` field of a `.scjvm` and return its base64 length. */
  private def classBundleSize(scjvm: os.Path): Int =
    val json = ujson.read(os.read(scjvm))
    json("classBundle").strOpt.map(_.length).getOrElse(0)

  /** Read the `classBundle` field of a `.scjvm-runtime` and return its size. */
  private def runtimeBundleSize(runtime: os.Path): Int =
    val json = ujson.read(os.read(runtime))
    json("classBundle").strOpt.map(_.length).getOrElse(0)

  // ── 1. Module .scjvm is small; shared runtime carries the preamble ─────

  test("compile-jvm --bytecode produces a small module .scjvm and a shared _runtime.scjvm-runtime"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val res = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                       "-o", "artifacts/a.scjvm")
      assert(res.exitCode == 0,
        s"compile-jvm --bytecode failed:\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjvm = artDir / "a.scjvm"
      val rt    = artDir / "_runtime.scjvm-runtime"
      assert(os.exists(scjvm), s"expected $scjvm")
      assert(os.exists(rt),    s"expected $rt — runtime artifact must accompany the module")

      // The .scjvm classBundle base64 should be well under 100 KB of
      // base64 — the legacy MVP shipped ~700-900 KB of base64 in this
      // field (full preamble baked in).
      val moduleCb = classBundleSize(scjvm)
      assert(moduleCb < 100_000,
        s"expected module classBundle to be < 100 KB of base64; got ${moduleCb} chars")

      // The runtime classBundle carries the (compiled) preamble — sane
      // lower bound to confirm we didn't end up with an empty bundle.
      val rtCb = runtimeBundleSize(rt)
      assert(rtCb > 50_000,
        s"expected runtime classBundle to be > 50 KB of base64; got ${rtCb} chars")

      // The total `.scjvm` size — including JSON wrapping — should be
      // dramatically smaller than the pre-Phase-2 ~500 KB baseline.
      val scjvmFileSize = os.size(scjvm)
      assert(scjvmFileSize < 50_000,
        s"expected a.scjvm to be < 50 KB; got ${scjvmFileSize} bytes")
    finally os.remove.all(sandbox)

  // ── 2. Re-compiling the same source is a runtime no-op ──────────────────

  test("re-running compile-jvm --bytecode on unchanged source leaves the runtime untouched"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val r1 = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(r1.exitCode == 0, s"first compile failed: ${r1.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      val rtJson1 = ujson.read(os.read(rt))
      val rtHash1 = rtJson1("sourceHash").str
      val rtCaps1 = rtJson1("capabilities").arr.map(_.str).toSet
      val rtMtime1 = os.mtime(rt)

      // Sleep so a stale mtime cache can't confuse us — and rerun.
      Thread.sleep(1100)
      val r2 = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(r2.exitCode == 0, s"second compile failed: ${r2.err.text()}")

      val rtJson2 = ujson.read(os.read(rt))
      assert(rtJson2("sourceHash").str   == rtHash1, "runtime sourceHash changed across no-op recompile")
      assert(rtJson2("capabilities").arr.map(_.str).toSet == rtCaps1,
        "runtime capabilities changed across no-op recompile")
      assert(os.mtime(rt) == rtMtime1,
        "runtime artifact was rewritten — staleness check is broken")
    finally os.remove.all(sandbox)

  // ── 3. Adding a module with a new capability regenerates the runtime ───

  test("compiling a second module with a new capability widens the runtime's capability union"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)
      val ra = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(ra.exitCode == 0, s"compile a failed: ${ra.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      val caps1 = ujson.read(os.read(rt))("capabilities")
        .arr.map(_.str).toSet
      // Module `a` has no extra capabilities — minimal preamble.
      assert(!caps1.contains("serve"),
        s"unexpected 'serve' capability in initial runtime: $caps1")

      os.write(sandbox / "b.ssc", bSscWithRoute)
      val rb = runSsc(sandbox, "compile-jvm", "--bytecode", "b.ssc",
                      "-o", "artifacts/b.scjvm")
      assert(rb.exitCode == 0, s"compile b failed: ${rb.err.text()}")

      val caps2 = ujson.read(os.read(rt))("capabilities")
        .arr.map(_.str).toSet
      assert(caps2.contains("serve"),
        s"expected 'serve' capability after compiling module with route(); got: $caps2")
      // The union should be a superset of caps1.
      assert(caps1.subsetOf(caps2), s"capability union shrank: $caps1 -> $caps2")
    finally os.remove.all(sandbox)

  // ── 4. End-to-end run: java -cp out.jar a_sc prints expected output ────

  test("compile-jvm --bytecode + link --bytecode + java -cp run a_sc"):
    requireScalaCli()
    val stdlib = requireScalaStdlib()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscWithMain)

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                    "-o", "artifacts/a.scjvm").exitCode == 0)

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJar))

      // The runtime + module JAR should be meaningfully smaller than
      // the pre-Phase-2 baseline of ~515 KB.
      val jarSize = os.size(outJar)
      assert(jarSize < 400_000,
        s"expected out.jar < 400 KB after runtime separation; got $jarSize bytes")

      val runRes = os.proc("java", "-cp", s"$outJar:$stdlib", "a_sc").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      assert(runRes.exitCode == 0,
        s"java -cp out.jar a_sc failed: exit=${runRes.exitCode}\nstderr=${runRes.err.text()}")
      val out = runRes.out.text()
      assert(out.contains("a.add(2, 3) = 5"),
        s"expected 'a.add(2, 3) = 5' in stdout; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 5. compile-runtime --capabilities exposes the runtime compile path ──

  test("compile-runtime --capabilities builds a standalone _runtime.scjvm-runtime"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      val r = runSsc(sandbox, "compile-runtime",
                     "--capabilities", "effects,serve",
                     "--artifact-dir", "artifacts")
      assert(r.exitCode == 0,
        s"compile-runtime failed:\nstdout=${r.out.text()}\nstderr=${r.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      assert(os.exists(rt))
      val json = ujson.read(os.read(rt))
      val caps = json("capabilities").arr.map(_.str).toSet
      assert(caps == Set("effects", "serve"),
        s"expected capabilities {effects, serve}; got: $caps")
      assert(json("classBundle").str.length > 50_000,
        "expected non-empty compiled runtime classBundle")
    finally os.remove.all(sandbox)

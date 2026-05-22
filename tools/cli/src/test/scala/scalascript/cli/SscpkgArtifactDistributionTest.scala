package scalascript.cli

import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 5 — pre-compiled artifact distribution via `.sscpkg`.
 *
 *  Tests cover:
 *   1. Round-trip:  `bundle --with-artifacts` produces a `.sscpkg` with
 *      `.ssc-artifacts/` and a consumer that lays out the cache picks up
 *      the pre-compiled `.scim` / `.scjvm` / `.scjs` directly.
 *   2. Source-fallback:  a `dep:` whose cached `.ssc` ships without
 *      `.ssc-artifacts/` still works — the consumer falls through to the
 *      source-parse path.
 *   3. Mixed:  `.scim` present, `.scjvm` absent → consumer uses the
 *      interface but recompiles the source.
 *   4. Corrupt artifact:  bad magic in `.scim` surfaces a clear error.
 *
 *  Like other v2.0 CLI tests, these spawn the assembled `ssc.jar` as a
 *  subprocess; when the jar is missing the test cancels.
 */
class SscpkgArtifactDistributionTest extends AnyFunSuite:

  // ── ssc jar discovery ─────────────────────────────────────────────────────

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
    val candidates = List(jarUnder(cwd), jarUnder(cwd / os.up)) ++
      findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  /** Spawn the ssc CLI.  `home`, when supplied, overrides both the JVM
   *  `user.home` system property (which `os.home` reads) and the `HOME`
   *  env var — so the dep cache lookup at `~/.cache/scalascript/deps`
   *  redirects into a per-test sandbox. */
  private def runSsc(cwd: os.Path, home: Option[os.Path], args: String*): os.CommandResult =
    val jar = requireJar()
    val homeProps: Seq[os.Shellable] = home match
      case Some(h) => Seq[os.Shellable](s"-Duser.home=${h.toString}")
      case None    => Seq.empty
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java") ++ homeProps ++ Seq[os.Shellable]("-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    val env: Map[String, String] = home match
      case Some(h) => Map("HOME" -> h.toString)
      case None    => Map.empty
    os.proc(cmd).call(
      cwd    = cwd,
      env    = env,
      check  = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  /** Set up a dep cache layout for `dep:org.example/lib:1.0`.  Returns the
   *  cache root suitable for use as `HOME` via the `XDG_CACHE_HOME`-style
   *  cache lookup — but `ImportResolver` uses `os.home`, so we override
   *  the `HOME` env var when spawning ssc. */
  private def writeDep(
      cacheHome: os.Path,
      org:       String,
      name:      String,
      version:   String,
      source:    String,
      artifacts: Map[String, Array[Byte]] = Map.empty
  ): os.Path =
    val depDir = cacheHome / ".cache" / "scalascript" / "deps" / org / name
    os.makeDir.all(depDir)
    val sscPath = depDir / s"$version.ssc"
    os.write.over(sscPath, source)
    if artifacts.nonEmpty then
      val artDir = depDir / ".ssc-artifacts"
      os.makeDir.all(artDir)
      artifacts.foreach { (ext, bytes) =>
        os.write.over(artDir / s"$version.$ext", bytes)
      }
    sscPath

  // ─── 1. Round-trip: --with-artifacts produces .sscpkg with .ssc-artifacts/ ─

  test("bundle --with-artifacts produces .sscpkg with .ssc-artifacts/ entries"):
    val sandbox = os.temp.dir(prefix = "ssc-p5-")
    try
      val aSrc = sandbox / "a.ssc"
      os.write(aSrc,
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)
      val res = runSsc(sandbox, None, "bundle", "a.ssc", "-o", "a.sscpkg", "--with-artifacts")
      assert(res.exitCode == 0,
        s"bundle --with-artifacts failed: ${res.err.text()}\n${res.out.text()}")
      val pkg = sandbox / "a.sscpkg"
      assert(os.exists(pkg))
      val zf = new ZipFile(pkg.toIO)
      try
        val names = zf.entries().asScala.toList.map(_.getName).sorted
        assert(names.contains("manifest.yaml"))
        assert(names.contains("sources/a.ssc"))
        assert(names.contains(".ssc-artifacts/a.scim"),
          s"expected .scim in .ssc-artifacts/; got: ${names.mkString(", ")}")
        assert(names.contains(".ssc-artifacts/a.scjvm"),
          s"expected .scjvm in .ssc-artifacts/; got: ${names.mkString(", ")}")
        assert(names.contains(".ssc-artifacts/a.scjs"),
          s"expected .scjs in .ssc-artifacts/; got: ${names.mkString(", ")}")
      finally zf.close()
    finally os.remove.all(sandbox)

  // ─── 2. Round-trip: consumer picks up staged artifacts from dep cache ────

  test("compile-jvm: dep: with sibling .scim + .scjvm is staged into consumer artifact dir"):
    val sandbox = os.temp.dir(prefix = "ssc-p5-consumer-")
    try
      // Produce a real .sscpkg with pre-compiled artifacts first.
      val producerDir = sandbox / "producer"
      os.makeDir.all(producerDir)
      val aSrc = producerDir / "a.ssc"
      os.write(aSrc,
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)
      val pkgRes = runSsc(producerDir, None,
        "bundle", "a.ssc", "-o", "a.sscpkg", "--with-artifacts")
      assert(pkgRes.exitCode == 0, s"producer bundle failed: ${pkgRes.err.text()}")

      // Extract source + artifacts into the consumer's dep cache layout.
      val pkgPath = producerDir / "a.sscpkg"
      val depHome = sandbox / "home"
      val depDir  = depHome / ".cache" / "scalascript" / "deps" / "org.example" / "a"
      val artDir  = depDir / ".ssc-artifacts"
      os.makeDir.all(artDir)
      val zf = new ZipFile(pkgPath.toIO)
      try
        for e <- zf.entries().asScala do
          if !e.isDirectory then
            val bytes = zf.getInputStream(e).readAllBytes()
            e.getName match
              case "sources/a.ssc"             => os.write.over(depDir / "1.0.ssc", bytes)
              case ".ssc-artifacts/a.scim"     => os.write.over(artDir / "1.0.scim", bytes)
              case ".ssc-artifacts/a.scjvm"    => os.write.over(artDir / "1.0.scjvm", bytes)
              case ".ssc-artifacts/a.scjs"     => os.write.over(artDir / "1.0.scjs",  bytes)
              case _                           => ()
      finally zf.close()

      // Consumer module references the dep.
      val consumerDir = sandbox / "consumer"
      os.makeDir.all(consumerDir)
      val bSrc = consumerDir / "b.ssc"
      os.write(bSrc,
        """---
          |name: b
          |---
          |
          |# B
          |
          |[A](dep:org.example/a:1.0)
          |
          |```scalascript
          |def use(): Int = 1
          |```
          |""".stripMargin)

      val artOut = consumerDir / ".ssc-artifacts"
      val cRes = runSsc(consumerDir, Some(depHome),
        "compile-jvm", "b.ssc", "--no-auto-deps", "--artifact-dir", artOut.toString)
      assert(cRes.exitCode == 0,
        s"compile-jvm failed: exit=${cRes.exitCode}\nout=${cRes.out.text()}\nerr=${cRes.err.text()}")

      // Pre-compiled artifacts should be staged into the consumer's artifact dir.
      val stagedScim  = artOut / "1.0.scim"
      val stagedScjvm = artOut / "1.0.scjvm"
      assert(os.exists(stagedScim),
        s"expected staged .scim at $stagedScim; got: " +
        (if os.exists(artOut) then os.list(artOut).mkString(", ") else "(missing dir)"))
      assert(os.exists(stagedScjvm),
        s"expected staged .scjvm at $stagedScjvm")
      // Output mentions the staging.
      val out = cRes.out.text()
      assert(out.contains("staged pre-compiled dep artifacts"),
        s"expected stdout to mention staging; got: $out")
    finally os.remove.all(sandbox)

  // ─── 3. Source-fallback: dep without .ssc-artifacts/ — falls through ─────

  test("compile-jvm: dep: without .ssc-artifacts/ falls through to source re-parse"):
    val sandbox = os.temp.dir(prefix = "ssc-p5-fallback-")
    try
      val depHome = sandbox / "home"
      // Cache the dep .ssc only — no artifacts dir alongside.
      writeDep(depHome, "org.example", "a", "1.0",
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)

      val consumerDir = sandbox / "consumer"
      os.makeDir.all(consumerDir)
      val bSrc = consumerDir / "b.ssc"
      os.write(bSrc,
        """---
          |name: b
          |---
          |
          |# B
          |
          |[A](dep:org.example/a:1.0)
          |
          |```scalascript
          |def use(): Int = 1
          |```
          |""".stripMargin)

      val artOut = consumerDir / ".ssc-artifacts"
      val res = runSsc(consumerDir, Some(depHome),
        "compile-jvm", "b.ssc", "--no-auto-deps", "--artifact-dir", artOut.toString)
      assert(res.exitCode == 0,
        s"compile-jvm should succeed via source-fallback: exit=${res.exitCode}\n" +
        s"err=${res.err.text()}\nout=${res.out.text()}")
      // No staged artifacts should land — no .ssc-artifacts/ next to dep.
      if os.exists(artOut) then
        assert(!os.exists(artOut / "1.0.scim"),
          "no staged .scim expected (dep ships source-only)")
        assert(!os.exists(artOut / "1.0.scjvm"),
          "no staged .scjvm expected (dep ships source-only)")
      // Output should not advertise staging since there was nothing.
      val out = res.out.text()
      assert(!out.contains("staged pre-compiled dep artifacts"),
        s"unexpected staging message in source-fallback: $out")
    finally os.remove.all(sandbox)

  // ─── 4. Mixed: .scim present, .scjvm absent → compile source against iface ─

  test("compile-jvm: dep: with .scim only stages interface; .scjvm absent → no stage"):
    val sandbox = os.temp.dir(prefix = "ssc-p5-mixed-")
    try
      val depHome = sandbox / "home"
      // Produce a real .scim by running emit-interface on a tiny module.
      val genDir = sandbox / "gen"
      os.makeDir.all(genDir)
      os.write(genDir / "a.ssc",
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin)
      val emit = runSsc(genDir, None, "emit-interface", "a.ssc")
      assert(emit.exitCode == 0, s"emit-interface failed: ${emit.err.text()}")
      val scimBytes = os.read.bytes(genDir / "a.scim")

      writeDep(depHome, "org.example", "a", "1.0",
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin,
        artifacts = Map("scim" -> scimBytes))

      val consumerDir = sandbox / "consumer"
      os.makeDir.all(consumerDir)
      os.write(consumerDir / "b.ssc",
        """---
          |name: b
          |---
          |
          |# B
          |
          |[A](dep:org.example/a:1.0)
          |
          |```scalascript
          |def use(): Int = 1
          |```
          |""".stripMargin)

      val artOut = consumerDir / ".ssc-artifacts"
      val res = runSsc(consumerDir, Some(depHome),
        "compile-jvm", "b.ssc", "--no-auto-deps", "--artifact-dir", artOut.toString)
      assert(res.exitCode == 0,
        s"compile-jvm with .scim-only dep should succeed: exit=${res.exitCode}\n" +
        s"err=${res.err.text()}\nout=${res.out.text()}")
      assert(os.exists(artOut / "1.0.scim"),
        s"expected staged .scim; got: ${if os.exists(artOut) then os.list(artOut).mkString(", ") else "(missing dir)"}")
      assert(!os.exists(artOut / "1.0.scjvm"),
        ".scjvm not in the dep — should not be staged")
    finally os.remove.all(sandbox)

  // ─── 5. Corrupt artifact: bad magic in .scim surfaces a clear error ──────

  test("compile-jvm: corrupt .scim alongside dep surfaces a clear error"):
    val sandbox = os.temp.dir(prefix = "ssc-p5-corrupt-")
    try
      val depHome = sandbox / "home"
      // Bad magic bytes — not a valid .scim envelope.
      val badScim = """{"magic":"BOGUS","abiVersion":"2.0","exports":[]}""".getBytes("UTF-8")
      writeDep(depHome, "org.example", "a", "1.0",
        """---
          |name: a
          |---
          |
          |# A
          |
          |```scalascript
          |def add(x: Int, y: Int): Int = x + y
          |```
          |""".stripMargin,
        artifacts = Map("scim" -> badScim))

      val consumerDir = sandbox / "consumer"
      os.makeDir.all(consumerDir)
      os.write(consumerDir / "b.ssc",
        """---
          |name: b
          |---
          |
          |# B
          |
          |[A](dep:org.example/a:1.0)
          |
          |```scalascript
          |def use(): Int = 1
          |```
          |""".stripMargin)

      val res = runSsc(consumerDir, Some(depHome),
        "compile-jvm", "b.ssc", "--no-auto-deps")
      assert(res.exitCode != 0,
        s"expected non-zero exit for corrupt .scim; got exit=${res.exitCode}\n" +
        s"out=${res.out.text()}\nerr=${res.err.text()}")
      val combined = res.out.text() + res.err.text()
      assert(combined.contains("magic") || combined.contains("corrupt") || combined.contains("BOGUS"),
        s"expected diagnostic to mention corrupt magic; got:\n$combined")
    finally os.remove.all(sandbox)

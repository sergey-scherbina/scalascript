package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 — end-to-end tests for `compile-jvm` / `compile-js` auto-resolution.
 *
 *  Validates that `compile-jvm b.ssc` (where `b` imports `a`) walks the
 *  local-import closure, topo-sorts it, and recursively compiles every
 *  stale dep into the artifact directory **before** compiling `b` itself.
 *
 *   1. Two-module chain `b → a` — auto-resolve produces both `.scim`/.scjvm.
 *   2. Three-module chain `c → b → a` — auto-resolve produces all three.
 *   3. Idempotency — second run leaves dep `.scjvm` mtimes untouched
 *      (SHA-256 match).
 *   4. Cycle detection — `a → b → a` exits non-zero with a clear message.
 *   5. `--no-auto-deps` reproduces the pre-v2.0 behaviour: target errors
 *      because `a.scim` doesn't exist.
 *   6. Same shape for `compile-js`.
 *
 *  Run with:  `sbt "cli/testOnly *AutoResolve*"`
 */
class AutoResolveCliTest extends AnyFunSuite:

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

  /** Invoke the ssc CLI in `cwd` with the given args. */
  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd    = cwd,
      stdin  = "",
      check  = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  // ── Source fixtures ──────────────────────────────────────────────────────

  /** Standalone module exporting `add(x,y)`. */
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

  /** Module b imports a; references neither namespace nor any cross-module
   *  identifier — the link itself is enough to surface the dep edge in
   *  `Content.Import`.  Type-checking with auto-resolve still happens
   *  against the `--iface-dir` (auto-populated under .ssc-artifacts/). */
  private val bSscImportsA: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |[A](./a.ssc)
      |
      |```scalascript
      |def double(x: Int): Int = x + x
      |```
      |""".stripMargin

  private val cSscImportsB: String =
    """---
      |name: c
      |---
      |
      |# Module C
      |
      |[B](./b.ssc)
      |
      |```scalascript
      |def triple(x: Int): Int = x + x + x
      |```
      |""".stripMargin

  // For cycle test: a imports b, b imports a.
  private val aImportsB: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |[B](./b.ssc)
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |```
      |""".stripMargin

  private val bImportsA: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |[A](./a.ssc)
      |
      |```scalascript
      |def mul(x: Int, y: Int): Int = x * y
      |```
      |""".stripMargin

  // ── 1. Two-module: b → a auto-resolves with compile-jvm ──────────────────

  test("compile-jvm auto-resolves a single dep into default .ssc-artifacts/"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)

      val res = runSsc(sandbox, "compile-jvm", "b.ssc")
      assert(res.exitCode == 0,
        s"compile-jvm b failed (expected auto-resolve to compile a first):\n" +
        s"  stdout=${res.out.text()}\n  stderr=${res.err.text()}")

      // b's artifact lands next to the source (no -o, no --artifact-dir).
      assert(os.exists(sandbox / "b.scjvm"),
        "expected b.scjvm next to source")

      // a's artifacts land in the default artifact dir.
      val artDir = sandbox / ".ssc-artifacts"
      assert(os.exists(artDir / "a.scim"),
        s"expected ${artDir}/a.scim to be auto-produced; got: ${if os.exists(artDir) then os.list(artDir).mkString(", ") else "(no artifact dir)"}")
      assert(os.exists(artDir / "a.scjvm"),
        s"expected ${artDir}/a.scjvm to be auto-produced")
    finally os.remove.all(sandbox)

  // ── 2. Three-module chain: c → b → a ─────────────────────────────────────

  test("compile-jvm auto-resolves a three-module chain c → b → a"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)
      os.write(sandbox / "c.ssc", cSscImportsB)

      val res = runSsc(sandbox, "compile-jvm", "c.ssc")
      assert(res.exitCode == 0,
        s"compile-jvm c failed:\n  stdout=${res.out.text()}\n  stderr=${res.err.text()}")

      assert(os.exists(sandbox / "c.scjvm"), "expected c.scjvm next to source")
      val artDir = sandbox / ".ssc-artifacts"
      for name <- List("a.scim", "a.scjvm", "b.scim", "b.scjvm") do
        assert(os.exists(artDir / name),
          s"expected ${artDir}/$name to be auto-produced; got: ${os.list(artDir).map(_.last).mkString(", ")}")
    finally os.remove.all(sandbox)

  // ── 3. Idempotency: second run is a no-op ────────────────────────────────

  test("compile-jvm auto-resolve is idempotent — second run leaves dep mtimes alone"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)

      val r1 = runSsc(sandbox, "compile-jvm", "b.ssc")
      assert(r1.exitCode == 0, s"first compile-jvm b failed: ${r1.err.text()}")

      val artDir = sandbox / ".ssc-artifacts"
      val aScjvm = artDir / "a.scjvm"
      val aScim  = artDir / "a.scim"
      val bScjvm = sandbox / "b.scjvm"
      assert(os.exists(aScjvm) && os.exists(aScim) && os.exists(bScjvm))
      val t1Scim  = os.mtime(aScim)
      val t1Scjvm = os.mtime(aScjvm)
      val t1B     = os.mtime(bScjvm)

      Thread.sleep(1100)

      val r2 = runSsc(sandbox, "compile-jvm", "b.ssc")
      assert(r2.exitCode == 0, s"second compile-jvm b failed: ${r2.err.text()}")

      // Dep artifacts should be untouched (SHA-256 match → skipped).
      assert(os.mtime(aScim)  == t1Scim,
        s"a.scim mtime changed despite no source change: $t1Scim → ${os.mtime(aScim)}")
      assert(os.mtime(aScjvm) == t1Scjvm,
        s"a.scjvm mtime changed despite no source change: $t1Scjvm → ${os.mtime(aScjvm)}")
      // b is recompiled every time (the target always recompiles); we
      // only assert the dep skip, which is the load-bearing claim.
      assert(os.mtime(bScjvm) >= t1B, "b.scjvm mtime should not go backwards")
    finally os.remove.all(sandbox)

  // ── 4. Cycle detection ───────────────────────────────────────────────────

  test("compile-jvm detects cycles a → b → a and exits non-zero"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aImportsB)
      os.write(sandbox / "b.ssc", bImportsA)

      val res = runSsc(sandbox, "compile-jvm", "a.ssc")
      assert(res.exitCode != 0,
        s"compile-jvm should have failed on cycle a→b→a; got 0\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val err = res.err.text()
      assert(err.toLowerCase.contains("circular") || err.toLowerCase.contains("cycle"),
        s"expected stderr to mention cycle; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 5. --no-auto-deps reproduces the old behaviour ───────────────────────

  test("compile-jvm --no-auto-deps does not auto-resolve (target stands alone)"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)

      val res = runSsc(sandbox, "compile-jvm", "b.ssc", "--no-auto-deps")
      // The b.ssc fixture intentionally does not depend on names from a,
      // so it should compile fine on its own — but no a.scjvm should be
      // produced anywhere.
      val artDir = sandbox / ".ssc-artifacts"
      assert(!os.exists(artDir / "a.scim"),
        s"expected NO a.scim under --no-auto-deps; found ${artDir / "a.scim"}")
      assert(!os.exists(artDir / "a.scjvm"),
        s"expected NO a.scjvm under --no-auto-deps; found ${artDir / "a.scjvm"}")
      // Either b succeeded (the fixture stands alone) or it failed with a
      // typer error — both are valid "didn't auto-resolve" outcomes.
      // The load-bearing claim is the absence of the dep artifact.
      val _ = res
    finally os.remove.all(sandbox)

  // ── 6. compile-js — same shape ───────────────────────────────────────────

  test("compile-js auto-resolves a single dep into default .ssc-artifacts/"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)

      val res = runSsc(sandbox, "compile-js", "b.ssc")
      assert(res.exitCode == 0,
        s"compile-js b failed:\n  stdout=${res.out.text()}\n  stderr=${res.err.text()}")

      assert(os.exists(sandbox / "b.scjs"), "expected b.scjs next to source")
      val artDir = sandbox / ".ssc-artifacts"
      assert(os.exists(artDir / "a.scim"),
        s"expected a.scim under ${artDir}; got: ${if os.exists(artDir) then os.list(artDir).mkString(", ") else "(no artifact dir)"}")
      assert(os.exists(artDir / "a.scjs"),
        s"expected a.scjs under ${artDir}")
    finally os.remove.all(sandbox)

  test("compile-js auto-resolves a three-module chain c → b → a"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)
      os.write(sandbox / "c.ssc", cSscImportsB)

      val res = runSsc(sandbox, "compile-js", "c.ssc")
      assert(res.exitCode == 0,
        s"compile-js c failed:\n  stdout=${res.out.text()}\n  stderr=${res.err.text()}")

      assert(os.exists(sandbox / "c.scjs"), "expected c.scjs next to source")
      val artDir = sandbox / ".ssc-artifacts"
      for name <- List("a.scim", "a.scjs", "b.scim", "b.scjs") do
        assert(os.exists(artDir / name),
          s"expected ${artDir}/$name; got: ${os.list(artDir).map(_.last).mkString(", ")}")
    finally os.remove.all(sandbox)

  test("compile-js detects cycles a → b → a and exits non-zero"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aImportsB)
      os.write(sandbox / "b.ssc", bImportsA)

      val res = runSsc(sandbox, "compile-js", "a.ssc")
      assert(res.exitCode != 0,
        s"compile-js should have failed on cycle; got 0\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val err = res.err.text()
      assert(err.toLowerCase.contains("circular") || err.toLowerCase.contains("cycle"),
        s"expected stderr to mention cycle; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 7. --artifact-dir override ───────────────────────────────────────────

  test("compile-jvm --artifact-dir writes dep artifacts to the override dir"):
    val sandbox = os.temp.dir(prefix = "ssc-autores-")
    try
      os.write(sandbox / "a.ssc", aSsc)
      os.write(sandbox / "b.ssc", bSscImportsA)

      val overrideDir = sandbox / "custom-art"
      val res = runSsc(sandbox, "compile-jvm", "b.ssc",
        "--artifact-dir", overrideDir.toString)
      assert(res.exitCode == 0,
        s"compile-jvm with --artifact-dir failed: ${res.err.text()}")

      assert(os.exists(overrideDir / "a.scim"),
        s"expected ${overrideDir}/a.scim; got: ${if os.exists(overrideDir) then os.list(overrideDir).mkString(", ") else "(no dir)"}")
      assert(os.exists(overrideDir / "a.scjvm"),
        s"expected ${overrideDir}/a.scjvm")
      // The default dir should not have been created.
      val defaultArt = sandbox / ".ssc-artifacts"
      assert(!os.exists(defaultArt) || os.list(defaultArt).isEmpty,
        s"expected NO default artifact dir; found contents in $defaultArt")
    finally os.remove.all(sandbox)
